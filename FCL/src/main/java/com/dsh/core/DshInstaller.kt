package com.dsh.core

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.tungsten.fclcore.util.gson.JsonUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * dsh 版本安装器：把指定版本的 @deepseek-ai/dsh 装进某个实例目录。
 *
 * 安装真正发生在 rootfs 内部（`npm install @deepseek-ai/dsh@<版本>`），因为原生依赖要命中
 * rootfs 的 glibc/musl。所以本类不自己下 tarball 解压，而是在 proot 里跑 setup-node-dsh.sh，
 * 把它的输出作为进度来源。
 *
 * ## 本次加固（对应审查发现的问题）
 * 1. **不再误报成功**：原来只要脚本退出码是 0 就标 READY，版本号解析失败还会把 "latest" 当版本号写进
 *    实例。现在安装后**必须**能读到 `node_modules/@deepseek-ai/dsh/package.json` 里的真实版本，
 *    否则判定 BROKEN 并带上 npm 的错误摘要。
 * 2. **生命周期脱离界面**：作用域从 Activity 的 lifecycleScope 换成进程级 [DshAppScope]，
 *    装到一半退出界面不会把 npm 变孤儿、也不会把状态卡在 INSTALLING。
 * 3. **单飞（single-flight）**：同一实例重复点击安装会被合并/拒绝，避免两个 npm 同时写一个
 *    node_modules 互相踩坏。
 * 4. **超时看门狗**：npm 卡住（网络半死）最多 [INSTALL_TIMEOUT_MS] 后被终止并标记失败，
 *    而不是永远停在"安装中"。
 * 5. **进度可持久观察**：进度状态放在 [statuses]（按实例 id），界面重建后仍能看到阶段/百分比，
 *    日志同时进 [DshLogBus]（带 token 脱敏、节流、落盘）。
 * 6. **共享 npm 缓存**：把 npm 的 cache 指到 `<filesDir>/dsh/npm-cache`，重复安装不用重新下载整棵依赖树。
 */
class DshInstaller(
    private val context: Context,
    private val proot: ProotExecutor,
    /** 兼容旧签名；内部长任务一律走 [DshAppScope]，此参数已不再用于承载任务 */
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope? = null
) {

    /** 安装进度事件（保留原有形状，便于界面继续使用） */
    sealed class Progress {
        data class Log(val line: String) : Progress()
        data class Stage(val text: String) : Progress()
        data class Done(val version: String) : Progress()
        data class Failed(val reason: String) : Progress()
    }

    /** 某个实例的安装状态（可被界面随时读取，界面重建不丢） */
    data class InstallStatus(
        val instanceId: String,
        val version: String,
        val stage: String,
        /** 0..1；无法估算时为 null */
        val fraction: Double? = null,
        val running: Boolean = true,
        val error: String? = null
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, InstallStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, InstallStatus>> = _statuses.asStateFlow()

    /**
     * 正在跑的安装任务，key = instanceId。
     * **注意**：单飞判定不要用本表做 check-then-act（见 [SingleFlight]），本表只用于"取消时找到那个 job"。
     */
    private val running = ConcurrentHashMap<String, Job>()

    /** 单飞闸门：把"查是否在跑 + 登记"合成一次原子操作 */
    private val gate = SingleFlight()

    /** 已被用户取消的实例：取消后 proot 会以非 0 退出，不能被误报成"安装失败(BROKEN)" */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /**
     * npm 错误尾部 / 预检失败原因，**按实例分开存**。
     * 旧实现是单例上的一个字段：同时安装两个实例时，A 的失败摘要会被 B 的输出冲掉，
     * 用户看到的是"另一个实例"的错误信息（很难查）。
     */
    private val errorTails = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val errorSummaries = ConcurrentHashMap<String, String>()

    fun isInstalling(instanceId: String): Boolean = gate.isRunning(instanceId)

    fun statusOf(instanceId: String): InstallStatus? = _statuses.value[instanceId]

    /**
     * 安装指定版本到实例。会把实例状态置为 INSTALLING，成功后置 READY 并记录**真实**版本。
     *
     * @return true 表示本次已受理；false 表示该实例已有安装在跑（会被忽略）
     */
    fun install(instance: DshInstance, version: String): Boolean {
        if (!gate.tryAcquire(instance.id)) {
            DshLogBus.append("[install] ${instance.name} 已有安装任务在进行，忽略重复请求")
            return false
        }
        cancelled.remove(instance.id)
        errorSummaries.remove(instance.id)
        errorTails.remove(instance.id)
        DshInstances.markState(instance.id, DshInstance.State.INSTALLING, error = null)
        updateStatus(
            InstallStatus(instance.id, version, context.getString(com.dsh.fcl.androidlauncher.R.string.dsh_stage_prepare))
        )
        // start = LAZY：先把 job 登记进 running 再 start()，确保"任务体"不可能先跑完、
        // 再被这里把已经结束的 job 写回表里（那会让 isInstalling 永远为真）。
        val job = DshAppScope.scope.launch(start = CoroutineStart.LAZY) {
            // 本次安装的"身份"：running[instanceId] 里登记的也是它，用来判断共享状态是否还归我管
            val myJob = coroutineContext[Job]
            try {
                val ok = runInstall(instance, version)
                if (!isCurrentOwner(instance.id, myJob)) {
                    // 本次任务已经被"取消后重新发起的那次安装"接管：再写状态就是改别人的数据
                    // （旧实现会把新安装的实例标成 BROKEN、把进度写成失败，界面显示"安装失败"而其实在装）
                    DshLogBus.append("[install] ${instance.name} 本次安装已被更新的一次安装接管，忽略本次结果")
                    return@launch
                }
                if (cancelled.contains(instance.id)) {
                    // 用户取消了：不能用 npm 的失败输出把实例标成 BROKEN。
                    // 但如果文件其实已经装完整（取消来得太晚），按磁盘事实标记 READY，
                    // 否则状态会卡在"安装中"直到下次启动 App 才被 repair() 纠正。
                    val resolved = readInstalledVersion(instance)
                    if (resolved != null) {
                        DshInstances.markState(instance.id, DshInstance.State.READY, resolved)
                        DshLogBus.append("[install] ${instance.name} 取消时其实已装完：dsh $resolved")
                    } else {
                        DshLogBus.append("[install] ${instance.name} 的安装已取消，忽略本次结果")
                    }
                } else if (ok) {
                    val resolved = readInstalledVersion(instance)
                    if (resolved == null) {
                        // 退出码 0 但装不完整：明确报损坏，别让用户以为能用了
                        fail(instance, context.getString(com.dsh.fcl.androidlauncher.R.string.dsh_install_incomplete))
                    } else {
                        DshInstances.markState(instance.id, DshInstance.State.READY, resolved)
                        _progress.value = Progress.Done(resolved)
                        finishStatus(instance.id)
                        DshLogBus.append("[install] ${instance.name} 安装完成：dsh $resolved")
                    }
                } else {
                    fail(instance, errorSummary(instance.id))
                }
            } catch (t: Throwable) {
                if (!isCurrentOwner(instance.id, myJob)) {
                    DshLogBus.append("[install] ${instance.name} 本次安装已被接管，忽略异常：${t.message ?: t}")
                } else if (!cancelled.contains(instance.id)) {
                    fail(instance, t.message ?: t.toString())
                } else {
                    DshLogBus.append("[install] ${instance.name} 的安装已取消（${t.message ?: t}）")
                }
            } finally {
                // ★ 只有"登记表里还是我"时才清理共享状态。
                // cancel() 会立刻放掉单飞闸门（让用户可以马上重装），新的安装随后接管 running[instanceId]；
                // 旧任务若仍然无条件 release / remove，就会把**新任务**的闸门放掉（于是能再起一个 npm，
                // 两个进程同时写同一个 node_modules），并抹掉新任务的错误摘要与取消标记。
                if (running.remove(instance.id, myJob)) {
                    cancelled.remove(instance.id)
                    errorSummaries.remove(instance.id)
                    errorTails.remove(instance.id)
                    gate.release(instance.id)
                }
            }
        }
        running[instance.id] = job
        job.start()
        return true
    }

    /**
     * 取消某实例的安装（界面上的"取消"）：取消协程 + 杀掉**本实例**正在跑的 proot 子进程。
     *
     * ★ 注意：这里**不**把 [running] 里的登记删掉 —— 登记同时代表"共享状态（错误摘要、闸门）
     * 当前归哪个任务管"。让它自己在 finally 里收尾（[isCurrentOwner] 判断），
     * 既保留"取消时其实已装完 → 标 READY"的判定，也避免旧任务的收尾动作伤到下一次安装。
     */
    fun cancel(instanceId: String) {
        // 先打标记再取消：任务体是阻塞在 proot 上的（协程取消打断不了阻塞调用），
        // 它会等到进程被杀、runInstall 返回非 0 之后才继续；如果没有这个标记，
        // 用户点"取消"会看到实例变成 BROKEN（"安装失败"）而不是"已取消"。
        cancelled.add(instanceId)
        running[instanceId]?.cancel()
        gate.release(instanceId)
        killActive(instanceId)
        _statuses.update { it - instanceId }
        DshInstances.markState(
            instanceId,
            DshInstance.State.NOT_INSTALLED,
            error = context.getString(com.dsh.fcl.androidlauncher.R.string.dsh_install_cancelled)
        )
    }

    /** 该实例当前的安装登记是否仍是 [job] 本人（没有被 cancel 之后的新安装接管） */
    private fun isCurrentOwner(instanceId: String, job: Job?): Boolean =
        job != null && running[instanceId] === job

    private fun fail(instance: DshInstance, reason: String) {
        DshInstances.markState(instance.id, DshInstance.State.BROKEN, error = reason)
        _progress.value = Progress.Failed(reason)
        _statuses.update {
            it + (instance.id to (it[instance.id]?.copy(running = false, error = reason)
                ?: InstallStatus(instance.id, "-", "", running = false, error = reason)))
        }
        DshLogBus.append("[install] ${instance.name} 安装失败：$reason")
    }

    private fun finishStatus(instanceId: String) {
        _statuses.update { it - instanceId }
    }

    private fun updateStatus(status: InstallStatus) {
        _statuses.update { it + (status.instanceId to status) }
    }

    /**
     * 取消/超时时真正终止子进程（命令由 [ProotProcessExecutor] 启的，让它自己收尾）。
     * ★ 必须带 instanceId：`destroyActive()` 原来是无条件杀"当前活跃进程"，而"当前活跃进程"是
     * 全局单例 —— 同时装两个实例时，取消 A 会把 B 的 npm 一起杀掉（B 的日志里会出现莫名其妙的
     * 中断，且状态停在 INSTALLING）。现在只有 tag 匹配才杀。
     */
    private fun killActive(instanceId: String? = null) {
        (proot as? ProotProcessExecutor)?.destroyActive(instanceId)
    }

    // --- 真正的安装流程 -----------------------------------------------------

    /** 失败原因：优先用预检/异常信息，其次用该实例自己的 npm ERR! 尾部 */
    private fun errorSummary(instanceId: String): String =
        errorSummaries[instanceId]
            ?: errorTails[instanceId]?.takeLast(3)?.joinToString(" | ")?.takeIf { it.isNotBlank() }
            ?: context.getString(com.dsh.fcl.androidlauncher.R.string.dsh_install_failed_generic)

    private fun runInstall(instance: DshInstance, version: String): Boolean {
        errorSummaries.remove(instance.id)
        errorTails.remove(instance.id)
        val script = "${ProotCommand.GUEST_ROOT}/scripts/setup-node-dsh.sh"
        val pre = ProotCommand.preflight(context, DshPaths.ROOTFS_DIR, script)
        if (!pre.ok) {
            errorSummaries[instance.id] = pre.reason ?: "预检失败（原因未知）"
            DshLogBus.append("[install] 预检失败：${pre.reason}")
            return false
        }

        updateStatus(
            InstallStatus(
                instance.id, version,
                context.getString(com.dsh.fcl.androidlauncher.R.string.dsh_stage_prepare),
                fraction = 0.05
            )
        )

        // 宿主侧确保实例目录结构存在
        DshPaths.instanceDir(instance.id).mkdirs()
        DshPaths.instanceHome(instance.id).mkdirs()
        DshPaths.instanceWorkspace(instance.id).mkdirs()
        File(DshPaths.NPM_CACHE_DIR).mkdirs()

        val instancePathInRootfs = "${ProotCommand.GUEST_ROOT}/instances/${instance.id}"
        var exit = -1
        try {
            exit = (proot as? ProotProcessExecutor)?.run(
                script = script,
                argvEnv = mapOf(
                    "INSTANCE_DIR" to instancePathInRootfs,
                    "DSH_VERSION" to version,
                    "NPM_CONFIG_CACHE" to "${ProotCommand.GUEST_ROOT}/npm-cache",
                    "CI" to "true" // 让 npm 输出更适合日志、别做交互式进度条刷屏
                ),
                secretEnv = emptyMap(),
                timeoutMs = INSTALL_TIMEOUT_MS,
                // 标记这个子进程属于哪个实例：取消/超时只杀自己那一个
                tag = instance.id
            ) { line -> onInstallLine(instance, version, line) }
                ?: proot.run(
                    script = script,
                    env = mapOf(
                        "INSTANCE_DIR" to instancePathInRootfs,
                        "DSH_VERSION" to version,
                        "NPM_CONFIG_CACHE" to "${ProotCommand.GUEST_ROOT}/npm-cache"
                    ),
                    onLine = { line -> onInstallLine(instance, version, line) }
                )
        } catch (t: Throwable) {
            errorSummaries[instance.id] = t.message ?: t.toString()
            return false
        }
        // ★ R-14：看门狗超时（ProotProcessExecutor 约定返回 -2）时给可读文案，
        // 否则 errorSummary 只会落到泛化的\"安装失败\"，用户完全不知道是卡死/超时。
        if (exit == ProotProcessExecutor.TIMEOUT_EXIT_CODE) {
            errorSummaries[instance.id] = context.getString(
                com.dsh.fcl.androidlauncher.R.string.dsh_install_timeout,
                INSTALL_TIMEOUT_MS / 60_000
            )
        }
        return exit == 0
    }

    private fun onInstallLine(instance: DshInstance, version: String, line: String) {
        DshLogBus.append(line)
        _progress.value = Progress.Log(line)
        val trimmed = line.trim()
        when {
            trimmed.startsWith("[setup] STAGE=") -> {
                val stage = trimmed.removePrefix("[setup] STAGE=")
                updateStatus(InstallStatus(instance.id, version, stage, fraction = null))
                _progress.value = Progress.Stage(stage)
            }
            // npm 失败信息可能跨行，保留尾部若干行做摘要
            trimmed.startsWith("npm ERR!") || trimmed.contains("ERR!") -> {
                errorTails.getOrPut(instance.id) { ArrayDeque() }.let { tail ->
                    tail.addLast(trimmed)
                    while (tail.size > 12) tail.removeFirst()
                }
            }
        }
    }

    /** 从 package.json 里读真实版本（安装是否完整，以此为准） */
    private fun readInstalledVersion(instance: DshInstance): String? =
        readVersionFromPackageJson(DshPaths.instanceDshPackageJson(instance.id))
            ?.also { version ->
                // 顺带确认入口文件在，避免"包在但入口被裁掉"的半成品
                if (!DshPaths.instanceDshBinJs(instance.id).isFile) {
                    DshLogBus.append("[install] 警告：${instance.name} 缺少 lib/bin.js")
                }
            }

    companion object {
        /** 安装最长容忍时间：低于此值可能是网络慢，高于此值基本是卡死 */
        const val INSTALL_TIMEOUT_MS = 30 * 60 * 1000L

        private data class PackageJson(
            @SerializedName("version") val version: String? = null
        )

        /** 解析 package.json 的 version；文件不存在/解析失败返回 null */
        fun readVersionFromPackageJson(file: File): String? {
            if (!file.isFile) return null
            return runCatching {
                val pkg = JsonUtils.fromNonNullJson(file.readText(), PackageJson::class.java)
                pkg.version?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }
}

/**
 * proot 执行抽象：真机实现负责用打包的 proot + rootfs 跑命令并逐行回传 stdout/stderr。
 * 对应任务1的 proot-run.sh。抽成接口便于测试与解耦。
 */
interface ProotExecutor {
    /**
     * 在 rootfs 内执行脚本，逐行回调输出，返回退出码。
     * @param script rootfs 内的脚本绝对路径
     * @param env    额外注入的环境变量
     * @param onLine 每行 stdout/stderr 回调（后台线程）
     */
    fun run(
        script: String,
        env: Map<String, String>,
        onLine: (String) -> Unit
    ): Int
}
