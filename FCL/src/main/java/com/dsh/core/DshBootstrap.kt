package com.dsh.core

import android.content.Context
import com.dsh.fcl.androidlauncher.R
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
 *
 * ## 日志归属（本类全部走全局流）
 * 底座是**所有实例共享**的（一份 rootfs，实例只是它里面的一个目录），所以这里的每一行都是
 * App 级事实：解压进度、空间检查、原子替换/回滚、自检结论、底座就绪或失败。
 * 归到某个实例上是错的 —— 底座失败时**所有**实例都起不来，把原因写进"当时碰巧在装的那个实例"
 * 的日志，会让另外几个实例页上什么都不显示，用户反而找不到根因。
 * 因此本类一律 `DshLogBus.append`（全局流），不进任何实例流。
 */
object DshBootstrap {

    private const val ASSET_ROOT = "dsh"

    /**
     * rootfs 解压后的**实测**体积（Debian bookworm arm64 + 官方 Node 22 + 预装 dsh）。
     * 压缩包 `rootfs.tar.xz` 314 MB → 解压后 1,607,908,864 B（≈1.50 GiB，膨胀约 5.1×）。
     * 这里取 1.65 GiB 留一点余量。
     */
    private const val ROOTFS_EXTRACTED_BYTES = 1650L * 1024 * 1024

    /**
     * 一个实例的 dsh 依赖树（node_modules）大致占用，实测 300~500 MB，取 600 MB 留余量。
     *
     * ★ 从 `private` 放开到 `internal`：这条门槛现在有**两个**使用方 —— 本类解压 rootfs 前的
     *   空间检查，以及 [DshInstaller] 起 npm 之前的空间检查。两处必须用同一个数：
     *   各写一个魔数的话迟早分叉，用户会遇到"底座说够、安装说不够"（或反过来），
     *   而这种前后矛盾的提示比不给提示更让人糊涂。
     */
    internal const val INSTANCE_NODE_MODULES_BYTES = 600L * 1024 * 1024

    /**
     * 首装需要的最小可用空间：rootfs 一份 + 一个实例的 node_modules。
     *
     * ★ 本轮修正：原值是 `1500 MiB`（=1,572,864,000 B），而**光 rootfs 解压后就已
     * 1,607,908,864 B** —— 门槛比它要保护的对象还小，于是"空间检查通过"之后照样会在解压中途
     * 写满磁盘，留下半成品（还带着一个误导性的"空间够用"结论）。现在按实测值给。
     */
    const val MIN_FREE_BYTES = ROOTFS_EXTRACTED_BYTES + INSTANCE_NODE_MODULES_BYTES

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

    /**
     * 当前解压任务的「归属者」标识（页面/入口名）。
     *
     * ★ 修的问题：原来只看 `busy` 布尔值，页面被 ViewPager 回收 → 页面级协程被 cancel →
     * 进度对话框不再刷新（看起来像卡死），用户再点一次就撞上互斥，只得到一句
     * 「已有解压任务在进行」——**完全看不到进度**。
     * 现在配合进程级作用域（[DshAppScope]）与 [progress] StateFlow：
     * 任务本身与界面生命周期解耦，界面只是订阅显示；同一归属者重复点击不再报错。
     */
    private val busyOwner = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** 是否有解压任务在跑 */
    @JvmStatic
    fun isBusy(): Boolean = busyGuard.get()

    /** 当前任务的归属者（排查用） */
    @JvmStatic
    fun currentOwner(): String? = busyOwner.get()

    /** 当前进度（界面订阅显示；无任务时为 null） */
    @JvmStatic
    fun currentProgress(): Progress? = _progress.value

    /**
     * 底座是否已就绪。
     *
     * ★ 方案 B：proot / loader / busybox 一律由 **jniLibs** 提供（nativeLibraryDir 有执行位，
     * 才能在 W^X 下 execve），因此不再要求 assets 里存在 proot 副本与版本文件。
     */
    fun isReady(): Boolean = runCatching {
        RuntimeUtils.isLatest(DshPaths.ROOTFS_DIR, "/assets/$ASSET_ROOT/rootfs") &&
            RuntimeUtils.isLatest(DshPaths.SCRIPTS_DIR, "/assets/$ASSET_ROOT/scripts") &&
            File(DshPaths.SCRIPTS_DIR, "start-dsh.sh").isFile &&
            prootBinReady() &&
            prootLoaderReady() &&
            DshPaths.rootfsLooksUsable()
    }.getOrDefault(false)

    /**
     * proot 主程序是否\"可用\"。
     * ★ 与 [ProotCommand.preflight] 的口径对齐：preflight 要求 `exists() && canExecute()`，
     * 这里原来只要求 `isFile` —— 若二进制存在但没有执行位，就会出现\"横幅隐藏（以为就绪）
     * 但一启动就报『proot 二进制没有执行权限』\"的假就绪（第九轮修的是同一类问题，
     * 只是当时漏了执行位这一维）。判据必须**不弱于**真正会失败的那个入口。
     */
    private fun prootBinReady(): Boolean =
        DshPaths.resolveProotBin(com.tungsten.fclauncher.utils.FCLPath.NATIVE_LIB_DIR)
            .let { it.isFile && it.canExecute() }

    private fun prootLoaderReady(): Boolean =
        DshPaths.resolveProotLoader(com.tungsten.fclauncher.utils.FCLPath.NATIVE_LIB_DIR).isFile

    /** 一句话描述当前缺口（界面提示用） */
    fun missingSummary(): String? {
        if (isReady()) return null
        val ctx = DshUiText.contextOrNull() ?: return null
        val prootBin = DshPaths.resolveProotBin(com.tungsten.fclauncher.utils.FCLPath.NATIVE_LIB_DIR)
        val prootLoader = DshPaths.resolveProotLoader(com.tungsten.fclauncher.utils.FCLPath.NATIVE_LIB_DIR)
        return when {
            !File(DshPaths.SCRIPTS_DIR, "start-dsh.sh").isFile -> ctx.getString(R.string.dsh_bootstrap_missing_scripts)
            !File(DshPaths.ROOTFS_DIR).isDirectory || !DshPaths.rootfsLooksUsable() -> ctx.getString(R.string.dsh_bootstrap_missing_rootfs)
            !prootBin.isFile -> ctx.getString(R.string.dsh_bootstrap_missing_proot)
            !prootBin.canExecute() -> ctx.getString(R.string.dsh_bootstrap_proot_not_executable)
            !prootLoader.isFile -> ctx.getString(R.string.dsh_bootstrap_missing_proot_loader)
            else -> ctx.getString(R.string.dsh_bootstrap_outdated)
        }
    }

    /**
     * 界面文案用的 Context。
     *
     * ## 为什么不是调用方传进来的那个
     * 这里的文案最终显示在准备页与首页任务区，而进程级单例（[DshServices]、[DshTasks]）持有的
     * 是 `applicationContext` —— Android 的**资源级**语言（`values-zh/…` 选哪一套）是在每个 Context
     * 上解析的，Application 那份跟随系统，**不认启动器设置里选的语言**。直接用它就等于"界面切成英文
     * 了，这里还是中文"。所以每次取这条链上的文本时都现取一份**当前 Activity** 的 Context
     * （`setLanguage` 在 `FCLActivity.attachBaseContext` 里生效），拿不到才退回 App Context。
     *
     * 一个字段缓存不成立：这条链是进程级的，用户可以在 App 存活期间切语言，缓存会把它钉死在旧语言上。
     */
    /**
     * 首启解压（幂等）。在 IO 线程调用。逐项检查 version，只解压过期的子项。
     */
    fun install(context: Context, owner: String? = null, onProgress: (Progress) -> Unit = {}) {
        // 原子占位：避免两个界面（列表页横幅 + 下载页）同时解压同一个 rootfs 互相踩坏
        if (!busyGuard.compareAndSet(false, true)) {
            val cur = busyOwner.get()
            if (owner != null && cur == owner) {
                // ★ 同一入口重复点击：不报错，只把"最新进度"重放给本次调用方（幂等）
                _progress.value?.let(onProgress)
            } else {
                // owner 是**内部标识**（两个界面同时进来时用来分辨"是不是同一入口重复点了"），
                // 不是给人看的名字。这里把标识保留在括号里：它是排障时唯一能对上日志的信息，
                // 而把它翻成一句人话反而会丢掉"是谁在跑"。
                onProgress(
                    Progress.Failed(
                        context.getString(R.string.dsh_bootstrap_busy, cur ?: "")
                    )
                )
            }
            return
        }
        busyOwner.set(owner)
        _busy.value = true
        val emit: (Progress) -> Unit = { p ->
            _progress.value = p
            onProgress(p)
        }
        // 界面文案统一用 DshUiText.contextOrNull()：它与调用方传进来的 context 可能不是同一个
        // （DshInstaller 那条链拿的是 applicationContext，不认启动器语言），详见 DshUiText.contextOrNull()。
        val ui = DshUiText.contextOrNull() ?: context
        try {
            val pathErr = DshPaths.loadPaths(context)
            if (pathErr != null) {
                fail(emit, ui.getString(R.string.dsh_bootstrap_data_dir_failed, pathErr))
                return
            }

            // 0) 磁盘空间检查**移到了真正要解压 rootfs 的分支里**（见第 3 步）。
            // 原实现把它放在这里，无论是否需要解压都会先卡一道门槛：rootfs 已经就绪、本次只是
            // 补脚本的场景，也会被"空间不足"直接判失败（假失败）。而且门槛值本身偏小（见
            // [MIN_FREE_BYTES] 的注释）。

            // 1) 脚本
            // ★ 判定带\"文件存在性\"兜底：RuntimeUtils.isLatest 用 Class.getResourceAsStream(\"/assets/...\")
            // 比对版本，与解压用的 context.getAssets().open(\"dsh/...\") 是两套访问机制；一旦前者在任何
            // 环境解析不到（返回\"已是最新\"），这里仅靠 isLatest 判定就会永远跳过首次解压，导致
            // scripts/probe.sh、start-dsh.sh 缺失、实例永远起不来。加一道\"目标文件在不在\"的硬校验，
            // 文件缺失就无条件补解压（幂等，安全）。
            if (!RuntimeUtils.isLatest(DshPaths.SCRIPTS_DIR, "/assets/$ASSET_ROOT/scripts") ||
                !File(DshPaths.SCRIPTS_DIR, "start-dsh.sh").isFile
            ) {
                emit(Progress.Stage(ui.getString(R.string.dsh_bootstrap_extract_scripts), 0.05))
                RuntimeUtils.install(
                    context, DshPaths.SCRIPTS_DIR, "$ASSET_ROOT/scripts",
                    listener(emit)
                )
                markExecutableShellScripts(File(DshPaths.SCRIPTS_DIR))
            }

            // 2) proot / loader / busybox：方案 B 一律走 jniLibs（nativeLibraryDir 有执行位）。
            // 不再从 assets 解压 proot 副本——assets 方案在 targetSdk>=29 会被 W^X 拒绝 execve。
            val nativeLibDir = com.tungsten.fclauncher.utils.FCLPath.NATIVE_LIB_DIR
            val prootBin = DshPaths.resolveProotBin(nativeLibDir)
            val prootLoader = DshPaths.resolveProotLoader(nativeLibDir)
            if (!prootBin.isFile || !prootBin.canExecute() || !prootLoader.isFile) {
                // 明确报出缺哪个，并给出可操作指引（这是打包问题，不是运行环境问题）
                // 缺哪个原生库是**构建产物的事实**，不是给人读的句子：库名（含"无执行位"这个
                // 形态差异）原样列出即可，列表分隔符走资源（中文用顿号、英文用逗号），
                // 拼句子的事交给 dsh_bootstrap_missing_artifacts 一条文案整体负责。
                val sep = ui.getString(R.string.dsh_list_separator)
                val libs = buildList {
                    if (!prootBin.isFile) add("libproot.so")
                    else if (!prootBin.canExecute()) {
                        add(ui.getString(R.string.dsh_bootstrap_artifact_no_exec, "libproot.so"))
                    }
                    if (!prootLoader.isFile) add("libproot-loader.so")
                }.joinToString(sep)
                fail(emit, ui.getString(R.string.dsh_bootstrap_missing_artifacts, libs))
                return
            }
            emit(Progress.Stage(ui.getString(R.string.dsh_bootstrap_proot_ready), 0.1))

            // 3) rootfs（tar.xz，最耗时）
            if (!RuntimeUtils.isLatest(DshPaths.ROOTFS_DIR, "/assets/$ASSET_ROOT/rootfs") ||
                !DshPaths.rootfsLooksUsable()
            ) {
                // 解压前才做空间检查，且按"这次到底要不要留两份"来算：
                // - 首装：解到 .tmp 一份，rename 上位 → 峰值 ≈ 1 份 rootfs
                // - 升级：旧 rootfs 仍在，新内容要同时存在 → 峰值 ≈ 2 份 rootfs
                // 另外还要给后面 npm install 留出 node_modules 的空间。
                val replacing = DshPaths.rootfsLooksUsable()
                val need = INSTANCE_NODE_MODULES_BYTES +
                    ROOTFS_EXTRACTED_BYTES * (if (replacing) 2L else 1L)
                val free = DshPaths.freeSpaceBytes()
                if (free in 0 until need) {
                    // 首装与升级**各一条**文案，而不是"共用一条 + 补一段括号说明"：
                    // 升级时那句"新旧两份会同时存在"是解释**为什么**要这么多空间，英文重写后
                    // 放进一条 %3$s 尾注里怎么排都不顺；分成两条让两边都能把话说明白，
                    // 也顺手避开了"缺了这句解释的译文看起来像凭空要双倍空间"。
                    // 两条都登记进 DshResourceFormatTest.callSites（两个参数都是 formatSize 的文本）。
                    fail(
                        emit,
                        ui.getString(
                            if (replacing) R.string.dsh_bootstrap_no_space_replace
                            else R.string.dsh_bootstrap_no_space_extract,
                            DshPaths.formatSize(need),
                            DshPaths.formatSize(free)
                        )
                    )
                    return
                }
                emit(Progress.Stage(ui.getString(R.string.dsh_bootstrap_extract_rootfs), 0.2))
                extractRootfs(context, emit)
            }

            // 4) 自检：能不能真跑
            emit(Progress.Stage(ui.getString(R.string.dsh_bootstrap_verify_check), 0.98))
            val report = verifySync(context)
            if (!report.ok) {
                fail(emit, ui.getString(R.string.dsh_bootstrap_verify_failed_prefix, report.detail))
                return
            }
            emit(Progress.Done)
            DshLogBus.append("[bootstrap] 运行时底座就绪")
        } catch (e: Exception) {
            fail(emit, e.message ?: e.toString())
        } finally {
            _busy.value = false
            busyGuard.set(false)
            busyOwner.set(null)
        }
    }

    /**
     * 底座准备失败：写日志 + 发进度事件 + **上报一条终态任务**。
     *
     * ★ 为什么失败也必须 [DshTasks.report]（而不是只发 `Progress.Failed`）：
     *   `Progress.Failed` 是"这一帧的进度状态"，而 [install] 的 `finally { _busy.value = false }`
     *   紧接着就把 `_busy` 置回 false —— [DshTasks] 那句 `if (busy) add(bootstrapTask(progress))`
     *   于是几乎在同一瞬间不再成立。结果：失败信息只在"progress 已更新、busy 还没置 false"那个
     *   竞态窗口里存在过一帧（状态流还是**合帧**的，同一轮里的两次写入只会通知一次），
     *   用户通常根本看不到，只知道"点了准备运行环境，转一圈，什么都没发生"。
     *
     *   `busy` 的语义不能改（界面拿它做门禁：解压中要禁按钮，置 false 太晚会让用户并发触发两次解压），
     *   所以这里不碰它，而是把**失败这件事**变成一条**事件**上报：终态任务是 push 进来的，
     *   不受 `busy` 的影响，会稳定留在任务区（[DshTasks.finished]）里，
     *   显示成"运行环境准备失败：xxx"。这也是"失败态该由事件承载、而不是靠状态残留"的直接体现。
     */
    private fun fail(emit: (Progress) -> Unit, reason: String) {
        DshLogBus.append("[bootstrap] 失败：$reason")
        emit(Progress.Failed(reason))
        // instanceId 留 null：底座是**所有实例共享**的，不属于任何一个实例（与 bootstrapTask 同口径）
        DshTasks.report(
            DshTask(
                id = "bootstrap",
                kind = DshTask.Kind.BOOTSTRAP,
                title = "",
                stage = reason,
                action = DshTask.Action.NONE,
                state = DshTask.State.FAILED,
                error = reason
            )
        )
    }

    /**
     * 运行时自检：在 proot 里跑最小命令，确认 proot + rootfs + bind 都正常。
     * 这是"装机后第一次真正执行 proot"的地方，能提前把 W^X/proot 缺失/rootfs 布局错误暴露出来。
     */
    suspend fun verify(context: Context): VerifyReport = verifySync(context)

    private fun verifySync(context: Context): VerifyReport {
        val ui = DshUiText.contextOrNull() ?: context
        val pre = ProotCommand.preflight(context, DshPaths.ROOTFS_DIR)
        // 预检失败时 reason 必非空（[ProotCommand.Preflight] 的约定），这里的兜底串只是
        // "万一"——真到了那一步说明 ProotCommand 自己也出问题了，所以它不配文案，用一句
        // 不带语言的机器可读串，免得把一条**本该永不出现**的兜底也塞进三套翻译里。
        if (!pre.ok) return VerifyReport(false, pre.reason ?: "preflight failed")

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
                    VerifyReport(true, ui.getString(R.string.dsh_bootstrap_verify_ok))
                else -> VerifyReport(
                    false,
                    ui.getString(
                        R.string.dsh_bootstrap_verify_proot_failed,
                        code,
                        lines.takeLast(3).joinToString(" / ").take(300)
                    )
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
            // 异常消息的去向是 catch → fail() → 准备页/首页任务区（用户可见），所以必须能本地化。
            // 这是首启最容易撞上的一条：rootfs.tar.xz 一旦多套了一层目录，用户看到的就是它。
            throw IllegalStateException(
                (DshUiText.contextOrNull() ?: context).getString(R.string.dsh_bootstrap_rootfs_layout_bad)
            )
        }

        // 写 version（与 assets 内 version 对齐，供下次 isLatest 比对）
        val version = context.assets.open("$ASSET_ROOT/rootfs/version")
            .bufferedReader().use { it.readText().trim() }
        File(tmpDir, "version").writeText(version)

        // 原子替换（★ 本轮加固）：先把旧 rootfs 挪到 .old 再让新内容上位。
        // 原实现是 \"先 deleteRecursively 旧目录，再 rename 新目录\"——删除与 rename 之间若失败
        // （磁盘满 / 被系统杀 / rename 异常），用户会**同时失去新旧两份 rootfs**：升级失败 = 底座全没了。
        // 现在失败可回滚到旧的（至少还能用），成功后再删备份。
        val backupDir = File("${DshPaths.ROOTFS_DIR}.old")
        if (backupDir.exists()) backupDir.deleteRecursively()
        val hadOld = destDir.exists()
        if (hadOld && !destDir.renameTo(backupDir)) {
            // 挪不动旧目录（少见）：退化为原行为，但记一行日志便于排查
            DshLogBus.append("[bootstrap] 旧 rootfs 无法改名备份，退化为直接覆盖")
            destDir.deleteRecursively()
        }
        if (!tmpDir.renameTo(destDir)) {
            // 某些文件系统上跨目录 rename 失败：退化为移动内容
            destDir.mkdirs()
            tmpDir.listFiles()?.forEach { f -> f.renameTo(File(destDir, f.name)) }
            tmpDir.deleteRecursively()
        }
        if (backupDir.exists()) {
            if (looksUsable(destDir)) {
                backupDir.deleteRecursively()
            } else {
                // 新内容不可用 → 回滚旧的，别让用户连旧底座都没了
                DshLogBus.append("[bootstrap] 新 rootfs 不可用，回滚到备份")
                destDir.deleteRecursively()
                backupDir.renameTo(destDir)
            }
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
