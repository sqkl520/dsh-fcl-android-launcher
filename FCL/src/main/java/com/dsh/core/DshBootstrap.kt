package com.dsh.core

import android.content.Context
import com.tungsten.fcl.util.RuntimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * dsh 运行时底座的首启解压：把打包在 assets 里的 proot 二进制 + rootfs + 脚本，
 * 首次启动时解压到 App 私有目录（[DshPaths]）。
 *
 * 复用 FCL 现成的 [RuntimeUtils]（同款 tar.xz 解压 + version 文件比对增量更新逻辑）。
 *
 * ## assets 布局约定（打包时放到 FCL/src/main/assets/dsh/ 下）
 * ```
 * assets/dsh/
 *   version                 底座版本号（说明用；实际按各子项 version 增量解压）
 *   proot/     version, libproot.so, libproot_loader.so
 *   rootfs/    version, rootfs.tar.xz
 *   scripts/   version, setup-node-dsh.sh, start-dsh.sh
 * ```
 *
 * ## 本次加固（对应审查发现的问题）
 * 1. **解压完了要能用**：新增 [verify] 自检——真正在 proot 里跑一条最小命令确认整条链路可用。
 *    原来只检查 "version 文件对不对"，解压出一个空壳也会被判为就绪，然后在安装阶段抛一堆看不懂的错。
 * 2. **rootfs 少一层目录也能救**：rootfs.tar.xz 若带顶层目录（如 `ubuntu/`），解压出来会多套一层，
 *    proot -r 直接指错根。现在解压后检测"是否存在 /bin/sh"，若只有一个顶层目录则自动上提（hoist）。
 * 3. **磁盘空间检查**：rootfs + node_modules 动辄 1GB 起，空间不够时给明确提示，
 *    而不是解到一半失败留下半个 rootfs。
 * 4. **原子替换**：rootfs 先解到 `rootfs.tmp`，成功后换名，避免"解压中途被杀 → 半成品被当成就绪"。
 * 5. **进度可见**：[progress] StateFlow（阶段 + 明细 + 百分比），界面不再只有一句"请稍候"。
 * 6. **proot 缺失时给人话**：assets 方案在 Android 10+ 常因 W^X 无法执行，
 *    现在直接给出"请把 proot 放进 jniLibs"的可操作指引，而不是静默失败。
 */
object DshBootstrap {

    private const val ASSET_ROOT = "dsh"

    /** 解压前要求的最小可用空间（大致覆盖 rootfs + 一个实例的 node_modules） */
    const val MIN_FREE_BYTES = 1500L * 1024 * 1024

    sealed class Progress {
        data class Stage(val text: String, val fraction: Double? = null) : Progress()
        data class Detail(val detail: String) : Progress()
        object Done : Progress()
        data class Failed(val reason: String) : Progress()
    }

    /** 自检结果 */
    data class VerifyReport(val ok: Boolean, val detail: String)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 互斥用：两个界面同时触发解压时，只允许一个真正执行（比读 _busy 再写更可靠） */
    private val busyGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 底座是否已就绪（三项 version 都最新 + rootfs 内容可信） */
    fun isReady(): Boolean = runCatching {
        RuntimeUtils.isLatest(prootDir().absolutePath, "/assets/$ASSET_ROOT/proot") &&
            RuntimeUtils.isLatest(DshPaths.ROOTFS_DIR, "/assets/$ASSET_ROOT/rootfs") &&
            RuntimeUtils.isLatest(DshPaths.SCRIPTS_DIR, "/assets/$ASSET_ROOT/scripts") &&
            File(DshPaths.SCRIPTS_DIR, "start-dsh.sh").isFile &&
            DshPaths.rootfsLooksUsable()
    }.getOrDefault(false)

    /** 一句话描述当前缺口（界面提示用） */
    fun missingSummary(): String? {
        if (isReady()) return null
        return when {
            !File(DshPaths.SCRIPTS_DIR, "start-dsh.sh").isFile -> "缺少启动脚本"
            !File(DshPaths.ROOTFS_DIR).isDirectory || !DshPaths.rootfsLooksUsable() -> "rootfs 未就绪"
            !DshPaths.resolveProotBin(com.tungsten.fclauncher.utils.FCLPath.NATIVE_LIB_DIR).exists() ->
                "缺少 proot 可执行文件"
            else -> "运行时底座需要更新"
        }
    }

    /**
     * 首启解压（幂等）。在 IO 线程调用。逐项检查 version，只解压过期的子项。
     */
    fun install(context: Context, onProgress: (Progress) -> Unit = {}) {
        // 原子占位：避免两个界面（列表页横幅 + 下载页）同时解压同一个 rootfs 互相踩坏
        if (!busyGuard.compareAndSet(false, true)) {
            onProgress(Progress.Failed("已有解压任务在进行"))
            return
        }
        _busy.value = true
        val emit: (Progress) -> Unit = { p ->
            _progress.value = p
            onProgress(p)
        }
        try {
            val pathErr = DshPaths.loadPaths(context)
            if (pathErr != null) {
                fail(emit, "无法创建数据目录：$pathErr（磁盘空间或权限问题）")
                return
            }

            // 0) 磁盘空间（rootfs + 一个实例的 node_modules）
            val free = DshPaths.freeSpaceBytes()
            if (free in 0 until MIN_FREE_BYTES) {
                fail(
                    emit,
                    "可用空间不足：需要约 ${DshPaths.formatSize(MIN_FREE_BYTES)}，" +
                        "当前 ${DshPaths.formatSize(free)}"
                )
                return
            }

            // 1) 脚本
            // ★ 判定带\"文件存在性\"兜底：RuntimeUtils.isLatest 用 Class.getResourceAsStream(\"/assets/...\")
            // 比对版本，与解压用的 context.getAssets().open(\"dsh/...\") 是两套访问机制；一旦前者在任何
            // 环境解析不到（返回\"已是最新\"），这里仅靠 isLatest 判定就会永远跳过首次解压，导致
            // scripts/probe.sh、start-dsh.sh 缺失、实例永远起不来。加一道\"目标文件在不在\"的硬校验，
            // 文件缺失就无条件补解压（幂等，安全）。
            if (!RuntimeUtils.isLatest(DshPaths.SCRIPTS_DIR, "/assets/$ASSET_ROOT/scripts") ||
                !File(DshPaths.SCRIPTS_DIR, "start-dsh.sh").isFile
            ) {
                emit(Progress.Stage("解压启动脚本", 0.05))
                RuntimeUtils.install(
                    context, DshPaths.SCRIPTS_DIR, "$ASSET_ROOT/scripts",
                    listener(emit)
                )
                markExecutableShellScripts(File(DshPaths.SCRIPTS_DIR))
            }

            // 2) proot 二进制（jniLibs 优先；assets 作为回退）
            if (!RuntimeUtils.isLatest(prootDir().absolutePath, "/assets/$ASSET_ROOT/proot")) {
                emit(Progress.Stage("解压 proot 运行时", 0.1))
                RuntimeUtils.install(
                    context, prootDir().absolutePath, "$ASSET_ROOT/proot",
                    listener(emit)
                )
                prootDir().walkTopDown().forEach { if (it.isFile) it.setExecutable(true, false) }
            }

            // 3) rootfs（tar.xz，最耗时）
            if (!RuntimeUtils.isLatest(DshPaths.ROOTFS_DIR, "/assets/$ASSET_ROOT/rootfs") ||
                !DshPaths.rootfsLooksUsable()
            ) {
                emit(Progress.Stage("解压 Linux rootfs（较大，请稍候）", 0.2))
                extractRootfs(context, emit)
            }

            // 4) 自检：能不能真跑
            emit(Progress.Stage("运行时自检", 0.98))
            val report = verifySync(context)
            if (!report.ok) {
                fail(emit, "自检未通过：${report.detail}")
                return
            }
            emit(Progress.Done)
            DshLogBus.append("[bootstrap] 运行时底座就绪")
        } catch (e: Exception) {
            fail(emit, e.message ?: e.toString())
        } finally {
            _busy.value = false
            busyGuard.set(false)
        }
    }

    private fun fail(emit: (Progress) -> Unit, reason: String) {
        DshLogBus.append("[bootstrap] 失败：$reason")
        emit(Progress.Failed(reason))
    }

    /**
     * 运行时自检：在 proot 里跑最小命令，确认 proot + rootfs + bind 都正常。
     * 这是"装机后第一次真正执行 proot"的地方，能提前把 W^X/proot 缺失/rootfs 布局错误暴露出来。
     */
    suspend fun verify(context: Context): VerifyReport = verifySync(context)

    private fun verifySync(context: Context): VerifyReport {
        val pre = ProotCommand.preflight(context, DshPaths.ROOTFS_DIR)
        if (!pre.ok) return VerifyReport(false, pre.reason ?: "预检失败")

        // 自检脚本：随底座一起解压的 scripts 目录里放一个最小探针（不存在则现写）
        val probe = File(DshPaths.SCRIPTS_DIR, "probe.sh")
        return try {
            if (!probe.isFile) {
                probe.writeText("#!/bin/sh\necho dsh-probe-ok\n")
                probe.setExecutable(true, false)
            }
            val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
            val code = ProotProcessExecutor(context).run(
                script = "${ProotCommand.GUEST_ROOT}/scripts/probe.sh",
                argvEnv = emptyMap(),
                secretEnv = emptyMap(),
                timeoutMs = 60_000
            ) { lines += it }
            when {
                code == 0 && lines.any { it.contains("dsh-probe-ok") } ->
                    VerifyReport(true, "proot + rootfs 正常")
                else -> VerifyReport(
                    false,
                    "proot 执行失败（退出码 $code）：${lines.takeLast(3).joinToString(" / ").take(300)}"
                )
            }
        } catch (e: Exception) {
            VerifyReport(false, e.message ?: e.toString())
        }
    }

    /**
     * 解压 rootfs.tar.xz 到临时目录，校验布局后原子替换。
     */
    private fun extractRootfs(context: Context, onProgress: (Progress) -> Unit) {
        val destDir = File(DshPaths.ROOTFS_DIR)
        val tmpDir = File("${DshPaths.ROOTFS_DIR}.tmp")
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        context.assets.open("$ASSET_ROOT/rootfs/rootfs.tar.xz").use { input ->
            RuntimeUtils.uncompressTarXZ(input, tmpDir, listener(onProgress))
        }

        // 布局校验 / 自动上提单层顶层目录
        hoistSingleTopLevelDir(tmpDir)

        if (!looksUsable(tmpDir)) {
            tmpDir.deleteRecursively()
            throw IllegalStateException(
                "rootfs 内容异常：找不到 bin/sh 或 usr/bin/env（请确认打包时没有多套一层目录）"
            )
        }

        // 写 version（与 assets 内 version 对齐，供下次 isLatest 比对）
        val version = context.assets.open("$ASSET_ROOT/rootfs/version")
            .bufferedReader().use { it.readText().trim() }
        File(tmpDir, "version").writeText(version)

        // 原子替换
        if (destDir.exists()) destDir.deleteRecursively()
        if (!tmpDir.renameTo(destDir)) {
            // 某些文件系统上跨目录 rename 失败：退化为移动内容
            destDir.mkdirs()
            tmpDir.listFiles()?.forEach { f -> f.renameTo(File(destDir, f.name)) }
            tmpDir.deleteRecursively()
        }
        DshPaths.loadPaths(context) // 重建目录（rootfs 被换过）
    }

    /** tar.xz 里若只有一个顶层目录，把其内容上提一层（rootfs 必须是"根"的形态） */
    private fun hoistSingleTopLevelDir(dir: File) {
        val entries = dir.listFiles() ?: return
        val visible = entries.filter { it.name != "version" }
        if (visible.size != 1) return
        val only = visible[0]
        if (!only.isDirectory) return
        if (looksUsable(only)) {
            DshLogBus.append("[bootstrap] rootfs 包内多了一层目录 ${only.name}，已自动上提")
            only.listFiles()?.forEach { f -> f.renameTo(File(dir, f.name)) }
            only.delete()
        }
    }

    private fun looksUsable(dir: File): Boolean =
        File(dir, "bin/sh").exists() || File(dir, "usr/bin/env").exists() ||
            File(dir, "bin/busybox").exists()

    /** 给 scripts 目录下的 .sh 加执行位 */
    private fun markExecutableShellScripts(dir: File) {
        dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".sh")) it.setExecutable(true, false) }
    }

    private fun prootDir(): File = File(DshPaths.ROOT_DIR, "proot")

    /**
     * 解压进度回调 → [Progress.Detail]。
     *
     * ★ 节流（配合 RuntimeUtils 去掉的"每文件 sleep 25ms"）：解压 rootfs 时每个文件都会回调一次
     * （几万次），如果原样往下传，UI 线程会被几万个 `runOnUiThread { dialog.setMessage(...) }`
     * 淹没。这里把回调压到约 5 次/秒：解压全速跑，界面仍然每 200ms 刷新一次。
     */
    private fun listener(onProgress: (Progress) -> Unit) =
        object : RuntimeUtils.InstallListener {
            private var lastEmitAt = 0L

            override fun onUpdate(detail: String) {
                val now = System.currentTimeMillis()
                if (now - lastEmitAt < DETAIL_THROTTLE_MS) return
                lastEmitAt = now
                onProgress(Progress.Detail(detail))
            }

            override fun onStage(resId: Int) { /* dsh 用文本 Stage，忽略 resId */ }
        }

    /** 解压进度明细的最小刷新间隔（毫秒） */
    private const val DETAIL_THROTTLE_MS = 200L
}
