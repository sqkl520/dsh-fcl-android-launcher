package com.dsh.core

import android.app.ActivityManager
import android.content.Context
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fclcore.util.gson.JsonUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * dsh 运行时管理器：在 proot 里启动/停止 `dsh web` 长驻进程，并捕获它打印的
 * `http://127.0.0.1:<port>/?token=XXXX`，供 WebView 加载。
 *
 * 与 [DshInstaller] 的区别：Installer 是一次性任务；Runtime 是长驻进程，所以自己管进程生命周期。
 *
 * ## 本次加固（对应审查发现的问题）
 * 1. **启动有超时、有失败出口**：原来只要 `Process.start()` 不抛异常就返回 true，之后 URL 抓不到就永远
 *    转圈。现在有 [STARTUP_TIMEOUT_MS] 看门狗 + 进程早退检测，失败会给出原因（并自动回收进程）。
 * 2. **端口不再硬编码冲突**：`port = 0` 时交给系统分配，从 dsh 输出的 URL 里回读真实端口并写回实例。
 * 3. **启动器不碰凭据**：既不从磁盘读、也不向子进程注入 API key —— Key 与模型都归 dsh 自己的
 *    设置管（原因见启动段那段注释：注入会让 dsh 模型页的写入被**拒绝**）。
 *    启动 token 会注册到 [DshLogBus] 做脱敏，不泄漏进日志。
 * 4. **孤儿进程处理**：写 `dsh.pid`；App 冷启时会**认领**（adopt）上次被系统杀掉时仍在跑的实例
 *    （端口活着就直接复用，不重启 300MB 进程），认不领就杀掉，避免"端口被旧进程占着，新实例起不来"。
 * 5. **停止可靠**：TERM → 等 5s → KILL，并清理 pid 文件；进程退出后自动让前台服务收尾（不再残留通知）。
 * 6. **崩溃可见**：进程意外退出会进入 [State.Exited]/[State.Failed]，界面据此提示而不是无声无息。
 */
object DshRuntime {

    /** 启动后等待"就绪"的最长时间（首次启动要加载大量插件，给足；脚本侧会提前 15s 放弃并给出原因） */
    const val STARTUP_TIMEOUT_MS = 180_000L

    /** 认领来的进程存活巡检间隔（见 [watchAdopted]） */
    private const val ADOPT_POLL_INTERVAL_MS = 10_000L

    /** 停止时，按 pid 终止 rootfs 内 node 的宽限时间（TERM 后等这么久再 KILL，毫秒） */
    private const val NODE_KILL_GRACE_MS = 2_000L

    /** 停止时，等 proot 响应 SIGQUIT / 确认它已经退出的宽限时间（毫秒） */
    private const val PROOT_EXIT_GRACE_MS = 1_000L

    /** 脚本打印 node 真实 pid 的行：`[start-dsh] node pid: 12345`（与 start-dsh.sh 的格式一致） */
    private val NODE_PID_LINE = Regex("""\[start-dsh]\s*node\s*pid:\s*(\d+)""")

    sealed class State {
        object Idle : State()
        data class Starting(val instanceId: String, val name: String) : State()
        data class Running(
            val instanceId: String,
            val name: String,
            val url: String?,
            val port: Int,
            /** 是否是从上一进程"认领"的（没有 Process 句柄，靠 pid 管理） */
            val adopted: Boolean = false
        ) : State()

        data class Stopping(val instanceId: String, val name: String) : State()
        data class Failed(val instanceId: String, val name: String, val reason: String) : State()
        data class Exited(val instanceId: String, val name: String, val code: Int) : State()
    }

    /** 启动请求的结果（同步预检阶段） */
    sealed class StartOutcome {
        object Started : StartOutcome()
        data class NotReady(val reason: String) : StartOutcome()
        data class Failed(val reason: String) : StartOutcome()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 兼容旧界面：[running] 仅在 [State.Running]/[State.Starting] 时非空 */
    data class Running(val instanceId: String, val port: Int, val url: String?)

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 启动看门狗：新一次启动前先取消上一次，避免"重试"时两个看门狗同时计时（会让重试的实例提前被判超时） */
    private var watchdogJob: Job? = null

    /** 认领来的进程的存活巡检（见 [watchAdopted]） */
    private var adoptWatcher: Job? = null

    /**
     * 已经用过 `PROOT_NO_SECCOMP` 兜底重试的实例。
     * 每次**用户主动启动**（[start]）都会清掉，于是"一次用户操作最多重试一次"，不会形成重试风暴。
     */
    private val seccompFallbackUsed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 状态当前归属于哪个实例（Idle 表示没有归属）。用于"迟到的回调不许改别人的状态" */
    private fun ownerOf(st: State): String? = when (st) {
        State.Idle -> null
        is State.Starting -> st.instanceId
        is State.Running -> st.instanceId
        is State.Stopping -> st.instanceId
        is State.Failed -> st.instanceId
        is State.Exited -> st.instanceId
    }

    /** 仅当"当前状态仍属于该实例"时才写入新状态，避免覆盖掉已经启动的另一个实例 */
    private fun setStateIfOwned(instanceId: String, next: () -> State) {
        synchronized(this) {
            if (ownerOf(_state.value) == instanceId) _state.value = next()
        }
    }

    /** 进程句柄抽象：真实 Process 或"按 pid 管"的孤儿进程 */
    private interface Handle {
        val pid: Long
        fun isAlive(): Boolean
        suspend fun terminate(graceMillis: Long)
    }

    /** 当前实例的 node pid（rootfs 内的 node，宿主上是 proot 的子进程）。0 = 未知 */
    @Volatile
    private var nodePid: Long = 0

    /**
     * 给 proot 发 SIGQUIT —— 这是 proot **唯一**会响应并顺手清理子进程的信号。
     *
     * proot 的 event loop 把除 `SIGQUIT/SIGILL/SIGABRT/SIGFPE/SIGSEGV` 之外的所有信号都装成
     * SIG_IGN（proot 源码 `src/tracee/event.c`："Ignore all other signals, including
     * terminating ones (^C for instance)"），所以 **SIGTERM 对 proot 完全无效** ——
     * 这也是真机日志里"停止 → 5s 后已停止"看着像成功了、其实只是等到 SIGKILL 的原因。
     * 而 SIGKILL 不可捕获：proot 的 `atexit(kill_all_tracees)` 与 `--kill-on-exit` 的清理逻辑
     * 都要求"proot 还活着、还在事件循环里"，被 KILL 时全来不及跑 → rootfs 内的 node 变孤儿、
     * 继续占着端口，下次启动就是 EADDRINUSE。
     *
     * SIGQUIT 走的正是那条清理路径（proot 源码 `kill_all_tracees2`）：先
     * `kill_all_tracees()`（对每个 tracee 发 SIGKILL）再退出事件循环。所以"先 SIGQUIT、
     * 等一会儿、不行再 SIGKILL"能让 proot 在死之前把 node 一起带走。
     *
     * @return true 表示信号已发出（pid 可用）；false 表示拿不到 pid，只能靠按 pid 终止 node
     */
    private fun signalProotQuit(pid: Long): Boolean {
        if (pid <= 0) return false
        return runCatching {
            android.os.Process.sendSignal(pid.toInt(), 3) // SIGQUIT
            true
        }.getOrDefault(false)
    }

    private class ProcessHandle(
        private val handle: ProotRunner.Handle
    ) : Handle {
        override val pid: Long get() = handle.pid()
        override fun isAlive(): Boolean = handle.isAlive()

        /**
         * 停止 proot：**SIGQUIT → 等 → 原来的 TERM/KILL 兜底**（理由见 [signalProotQuit]）。
         *
         * 预算按对半分：一半等 SIGQUIT 生效，剩下的一半留给 [ProotRunner.Handle.terminate]，
         * 所以"停止"的最坏耗时仍与改动前同量级（不会因为多了一次 SIGQUIT 而翻倍）。
         */
        override suspend fun terminate(graceMillis: Long) = withContext(Dispatchers.IO) {
            if (!isAlive()) return@withContext
            if (signalProotQuit(pid)) {
                val quitBudget = (graceMillis / 2).coerceAtLeast(500)
                val startedAt = System.currentTimeMillis()
                val deadline = startedAt + quitBudget
                while (System.currentTimeMillis() < deadline && isAlive()) {
                    kotlinx.coroutines.delay(100)
                }
                if (!isAlive()) return@withContext
                handle.terminate((graceMillis - (System.currentTimeMillis() - startedAt)).coerceAtLeast(500))
                return@withContext
            }
            // 拿不到 pid（Android 的 java.lang.Process 没有 pid()）：退化为原来的 TERM→KILL。
            // 此时"带走 node"完全依赖 [terminateNodeProcess] 那条按 pid 终止的路径。
            handle.terminate(graceMillis)
        }
    }

    /**
     * 认领来的 proot（没有 Process 句柄，只能按 pid 操作）：同样先 SIGQUIT 再 TERM/KILL。
     * [instanceId] 只用于日志归属（这个句柄属于哪个实例），不参与进程操作。
     */
    private class PidHandle(override val pid: Long, private val instanceId: String) : Handle {
        override fun isAlive(): Boolean = ProcessUtil.isAlive(pid)
        override suspend fun terminate(graceMillis: Long) = withContext(Dispatchers.IO) {
            if (!isAlive()) return@withContext
            if (pid > 0) {
                runCatching { android.os.Process.sendSignal(pid.toInt(), 3) } // SIGQUIT
                val quitBudget = (graceMillis / 2).coerceAtLeast(500)
                val startedAt = System.currentTimeMillis()
                val deadline = startedAt + quitBudget
                while (System.currentTimeMillis() < deadline && isAlive()) {
                    kotlinx.coroutines.delay(100)
                }
                if (!isAlive()) return@withContext
                ProcessUtil.kill(
                    pid,
                    (graceMillis - (System.currentTimeMillis() - startedAt)).coerceAtLeast(500),
                    logInstanceId = instanceId
                )
                return@withContext
            }
            ProcessUtil.kill(pid, graceMillis, logInstanceId = instanceId)
        }
    }

    @Volatile
    private var handle: Handle? = null

    @Volatile
    private var appContext: Context? = null

    /** 记录用：实例 id → 名称（多线程读写：start/stop/onProcessExit/adopt 各在不同协程） */
    private val nameOf = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 最近一次"主动停止"针对的实例 id。
     * 用来区分"用户/系统主动停"与"进程自己崩了"：主动停止时进程被 SIGTERM 杀死也会触发
     * [onProcessExit]（退出码 143），若不加区分就会被误报成"意外退出 code=143"，
     * WebView 也会显示成错误而不是"已停止"。start() 会清掉它，开始新的生命周期。
     */
    @Volatile
    private var stopRequestedFor: String? = null

    // --- 启动 ---------------------------------------------------------------

    fun runningInstanceId(): String? = (_state.value as? State.Running)?.instanceId
        ?: (_state.value as? State.Starting)?.instanceId

    fun currentState(): State = _state.value

    /**
     * 启动某实例的 dsh web。已有运行实例时先停掉（单实例策略：避免多个 300MB 进程压垮手机）。
     *
     * ★ 整个方法是 `@Synchronized`：它内部是"先查状态、再 fork/exec proot"的复合操作，中间还夹着
     * Keystore 解密、清孤儿进程等耗时步骤。不加锁时两处入口（列表页按钮 / 下载页安装完的引导）几乎
     * 同时进来，两边都会看到"没有实例在跑"，于是**同时起两个 300MB 的 node**，而且后写的 pid 文件
     * 覆盖前一个 —— 前一个进程谁也认领不了、也杀不掉。加锁后第二个调用会看到 Starting 并直接复用。
     */
    @Synchronized
    fun start(context: Context, instance: DshInstance): StartOutcome =
        startLocked(context, instance, noSeccomp = false)

    /**
     * [start] 的实现体。[noSeccomp] = true 时额外注入 `PROOT_NO_SECCOMP=1`
     * （部分设备内核对 proot 的 ptrace/seccomp 加速不兼容，导致 rootfs 内的进程一启动就被杀）。
     * 同一次"用户主动启动"里最多自动兜底一次，见 [seccompFallbackUsed]。
     */
    @Synchronized
    private fun startLocked(context: Context, instance: DshInstance, noSeccomp: Boolean): StartOutcome {
        appContext = context.applicationContext
        nameOf[instance.id] = instance.name
        stopRequestedFor = null // 新生命周期开始，清掉上一次的\"主动停止\"标记
        adoptWatcher?.cancel()  // 新一轮启动接管：旧的"认领巡检"立即失效
        if (!noSeccomp) seccompFallbackUsed.remove(instance.id) // 每次用户主动启动都重新给一次兜底机会
        // 新生命周期：清掉上一次记下的 node pid（那是上一个实例的，留着只会误导停止逻辑）
        nodePid = 0

        val pre = ProotCommand.preflight(
            context, DshPaths.ROOTFS_DIR,
            "${ProotCommand.GUEST_ROOT}/scripts/start-dsh.sh"
        )
        if (!pre.ok) {
            val reason = pre.reason
                ?: appContext?.getString(R.string.dsh_start_not_ready) ?: "runtime not ready"
            // 兜底重试阶段如果连预检都过不去，说明这次重试也没戏：把状态落到 Failed，
            // 否则状态会永远停在 Starting（界面上是"启动中"转圈不动）。
            if (noSeccomp) failRetry(instance, reason)
            return StartOutcome.NotReady(reason)
        }

        // 真正校验\"装好了\"：状态字段可能是过期的，以磁盘为准。
        // ★ 第十一轮：用 effectiveDsh* —— 命中 rootfs 预装版本时实例目录没有 node_modules，
        // 真正的包在 rootfs 的 /opt/dsh-preinstalled 下（阶段 D-1 的跳过下载优化）。
        val pkg = DshPaths.effectiveDshPackageJson(instance.id)
        val binJs = DshPaths.effectiveDshBinJs(instance.id)
        if (!pkg.isFile) {
            val reason = context.getString(R.string.dsh_reason_not_installed)
            if (noSeccomp) failRetry(instance, reason)
            return StartOutcome.NotReady(reason)
        }
        if (!binJs.isFile) {
            val reason = context.getString(R.string.dsh_reason_missing_entry)
            if (noSeccomp) failRetry(instance, reason)
            return StartOutcome.NotReady(reason)
        }

        // 同一实例已经在跑：直接复用
        if (runningInstanceId() == instance.id) {
            // 实例级：讲的是"这个实例自己已经在跑"，属于它本次生命周期的一部分
            DshLogBus.appendFor(instance.id, "[runtime] ${instance.name} 已在运行，复用现有进程")
            return StartOutcome.Started
        }
        // 别的实例在跑：先停
        if (runningInstanceId() != null) stop("启动新实例")

        DshPaths.instanceWorkspace(instance.id).mkdirs()
        DshPaths.instanceHome(instance.id).mkdirs()
        File(DshPaths.NPM_CACHE_DIR).mkdirs()
        File(DshPaths.TMP_DIR).mkdirs()

        // 先清掉上次残留的孤儿（同实例）
        ProcessUtil.killStale(instance.id)

        // ★ 再清一次"上一轮的 node"（B10）：proot 被 SIGKILL 时来不及带走 rootfs 内的 node，
        // 那个 node 会一直占着端口。这里在**同一个实例**即将启动前按 pid 精确收掉它。
        // 只按 pid 文件认（传 hint=0）：内存里的 nodePid 可能属于另一个实例，
        // 单实例策略下这个实例启动前刚把那个实例停掉，拿它的 pid 去杀是多余的、也可能误伤。
        terminateNodeProcess(instance.id, 0)

        // ★ 端口预检（B10 的第二半："端口被占时不要直接失败"）：实例端口是上次成功后回写的
        // 固定值，一旦被占（旧 node 还没退干净 / 别的 App 占了），node 起来就报 EADDRINUSE 然后
        // 整个失败。这里探测一次，被占就本次改用自动端口（0），并明确告诉用户为什么。
        // 代价：使用者手工指定的端口在这种情况下会被换成系统分配的端口（日志与实例配置都会
        // 显示新端口）。换来的是"能起来"，而不是一句无信息的失败。脚本侧还有一道同样的预检，
        // 两道互相独立：即便这里的探测判定"空闲"，脚本启动瞬间端口仍可能被别人抢走。
        val launchPort = resolveLaunchPort(instance)

        val instancePathInRootfs = "${ProotCommand.GUEST_ROOT}/instances/${instance.id}"
        // ★ 启动器不再向子进程注入 DEEPSEEK_API_KEY / DEEPSEEK_DEFAULT_MODEL（批次 5）。
        //
        // 硬理由不是"优先级低"，而是"注入之后用户在 App 内根本改不掉"：
        // dsh 的凭据提供者 dsh/packages/credentials/credentials-local 的文件头把继承来的进程
        // 环境排在**最高**优先级（"inherited process environment (read-only, wins)"），并且
        // 写入路径上有一道 assertUnshadowed()：环境里已经有这个引用时，写入会直接抛错
        // "supplied read-only by the launching environment, so set would be shadowed"。
        // 也就是说：启动器一旦注入，用户在 dsh「设置 → 模型」里填 Key 会**吃一个报错**，
        // 既不是"改了没用"，也不是静默覆盖 —— 在 App 内无论如何都配不成。
        // 去掉注入后，$DSH_HOME/.credentials.yaml 是唯一可写来源，改完立刻生效。
        //
        // 顺带：不再有 App 侧的明文 key，也就无需在日志里为它注册脱敏。
        // "没配 Key"不再是启动器要判断的事 —— 那是 dsh 自己的状态，UI 能起就照常起。

        val argvEnv = mapOf(
            "INSTANCE_DIR" to instancePathInRootfs,
            "DSH_HOME" to "$instancePathInRootfs/home",
            "PORT" to launchPort.toString(), // 0 = 让 dsh 自己挑（端口被占时见 resolveLaunchPort）
            "HOST" to "127.0.0.1",               // 只绑回环，不暴露局域网
            "PROFILE" to instance.profile,
            "NPM_CONFIG_CACHE" to "${ProotCommand.GUEST_ROOT}/npm-cache",
            // 脚本侧的就绪等待比启动器看门狗略短，保证脚本先给出 FAILED 的明确原因
            "READY_TIMEOUT" to (STARTUP_TIMEOUT_MS / 1000 - 15).toString(),
            "NODE_OPTIONS" to "--max-old-space-size=${nodeHeapMb(context)}"
        )
        // 这里只放"启动参数"，不放任何凭据：Key 与模型都由 dsh 自己的设置决定。
        val secretEnv = HashMap<String, String>()
        if (noSeccomp) {
            // 部分内核上 proot 的 seccomp 加速会让 rootfs 内的进程直接被信号杀死（SIGSYS）：
            // 关掉它（只影响性能，不影响功能）。由 looksLikeSeccompTrouble() 触发的兜底重试使用。
            secretEnv["PROOT_NO_SECCOMP"] = "1"
        }

        val spec = ProotCommand.build(
            context = context,
            script = "${ProotCommand.GUEST_ROOT}/scripts/start-dsh.sh",
            argvEnv = argvEnv,
            procEnv = secretEnv,
            workDirRootfs = "$instancePathInRootfs/workspace",
            bindCacheAsTmp = true
        )

        _state.value = State.Starting(instance.id, instance.name)
        _running.value = Running(instance.id, launchPort, null)
        // 实例级：本次启动的参数（版本/profile/端口）是"这个实例自己的运行记录"，
        // 用户点开它的日志页第一眼就该看到
        DshLogBus.appendFor(
            instance.id,
            "[runtime] 启动 ${instance.name}（dsh ${instance.dshVersion ?: "?"}，" +
                "profile=${instance.profile}，端口=${if (launchPort == 0) "自动" else launchPort.toString()}）"
        )

        val h = try {
            ProotRunner.start(
                spec = spec,
                scope = scope,
                onLine = { line -> onProcessLine(instance, line) },
                onExit = { code -> onProcessExit(instance, code) }
            )
        } catch (e: Exception) {
            val reason = e.message ?: e.toString()
            // 实例级：启动失败归因给这个实例（"为什么它起不来"是这个实例页最该回答的问题）
            DshLogBus.appendFor(instance.id, "[runtime] 启动失败：$reason")
            _state.value = State.Failed(instance.id, instance.name, reason)
            _running.value = null
            return StartOutcome.Failed(reason)
        }
        handle = ProcessHandle(h)
        val pid = h.pid()
        // pid 文件只给"孤儿认领/清理"（冷启时按 cmdline 校验）用，所以这里记的是**proot 的 pid**；
        // node 的 pid 另有 dsh-node.pid（由脚本写，停止时用它精确终止，见 [terminateNodeProcess]）。
        if (pid > 0) {
            ProcessUtil.writePidFile(instance.id, pid, launchPort)
        } else {
            // 拿不到 pid（Android 的 Process 实现没有 pid() 且反射失败）时，孤儿进程无法被认领/清理，
            // 明确记一行，避免将来\"为什么后台有个 300MB 的 node 杀不掉\"无从查起。
            // 实例级：这条警告是"这个实例的后台残留将无法自动清理"，属于它的运行环境问题
            DshLogBus.appendFor(
                instance.id,
                "[runtime] 警告：无法取得 proot 进程 pid，孤儿进程自动清理/认领将不可用"
            )
        }

        // 启动超时看门狗。★ 先取消上一次：兜底重试会再走一遍这里，若不取消，
        // 第一个看门狗会在"第一次启动时刻 + 180s"就到期，把重试起来的进程提前判超时。
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val st = _state.value
                if (st !is State.Starting || st.instanceId != instance.id) return@launch
                kotlinx.coroutines.delay(500)
            }
            val st = _state.value
            if (st is State.Starting && st.instanceId == instance.id) {
                val reason = context.getString(R.string.dsh_reason_start_timeout, STARTUP_TIMEOUT_MS / 1000)
                // 实例级：超时是"这个实例起不来"，实例页要能看到原因
                DshLogBus.appendFor(instance.id, "[runtime] 启动超时：$reason")
                failAndCleanup(instance, reason)
            }
        }
        return StartOutcome.Started
    }

    /**
     * 冷启时尝试"认领"上一进程留下的实例（App 被系统杀、但 proot/node 还在跑）。
     * 成功返回 true，此时 [state] 直接是 Running，界面可以立刻加载。
     */
    fun adoptOrphan(context: Context, instance: DshInstance): Boolean {
        appContext = context.applicationContext
        val meta = ProcessUtil.readPidFile(instance.id) ?: return false
        if (!ProcessUtil.isAlive(meta.pid)) return false
        // 必须确认这个 pid 确实是"我们这个实例的 proot"，避免误杀/误认别的进程
        val cmdline = ProcessUtil.cmdline(meta.pid) ?: return false
        if (!cmdline.contains("proot") || !cmdline.contains(instance.id)) return false
        val port = meta.port
        if (port <= 0 || !Probe.isPortOpen(port)) return false
        // 认领是异步做的（读 /proc + 探端口都要时间）：这期间用户完全可能已经手动启动了别的实例，
        // 此时再无条件写 _state 会把新实例的状态覆盖掉（界面显示 A 在跑，实际 B 在跑）。有实例在
        // 启动/运行时直接放弃认领。同时清掉上一轮的"主动停止"标记，避免它影响本次生命周期。
        if (runningInstanceId() != null) return false
        stopRequestedFor = null

        handle = PidHandle(meta.pid, instance.id)
        nameOf[instance.id] = instance.name
        // 认领来的实例同样要知道 node 的 pid：停止时要按它终止 rootfs 内的 node（B10）。
        nodePid = readNodePidFile(instance.id) ?: 0
        _state.value = State.Running(instance.id, instance.name, "http://127.0.0.1:$port/", port, adopted = true)
        _running.value = Running(instance.id, port, "http://127.0.0.1:$port/")
        // ★ 保持全局：这是**认领巡检**（App 冷启时跨实例的恢复动作 —— "上次被系统杀掉时它还活着，
        // 直接复用"），不是某个实例自己跑出来的输出。归到全局才能看清"这次冷启到底认领/清理了什么"。
        DshLogBus.append("[runtime] 认领仍在运行的实例 ${instance.name}（pid=${meta.pid}，端口=$port）")
        watchAdopted(instance, meta.pid, port)
        return true
    }

    /**
     * 认领来的进程**没有 Process 句柄**，也就不会有 [onProcessExit] 回调：
     * 若它随后自己退出（agent 结束、被系统杀），状态会永远停在 Running，"运行中"的界面点开 WebView
     * 只会一片加载失败，用户只能靠"停止"来复位。
     *
     * 这里起一个低频存活巡检兜底（第五轮新增）。判定采取**双条件**（进程不存在 **且** 端口已关），
     * 避免误判把还活着的实例标成已退出（那会更糟：pid 文件被删掉后，它彻底变成无法认领的孤儿）。
     */
    private fun watchAdopted(instance: DshInstance, pid: Long, port: Int) {
        adoptWatcher?.cancel()
        adoptWatcher = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(ADOPT_POLL_INTERVAL_MS)
                val st = _state.value
                if (st !is State.Running || !st.adopted || st.instanceId != instance.id) return@launch
                if (ProcessUtil.isAlive(pid)) continue
                if (Probe.isPortOpen(port, 800)) continue
                // ★ 保持全局：同属**认领巡检**（上一行的同一条线索），跨实例的存活判定
                DshLogBus.append("[runtime] 认领的实例 ${instance.name} 已不再运行（pid=$pid 已退出，端口 $port 已关闭）")
                ProcessUtil.removePidFile(instance.id)
                handle = null
                _running.value = null
                setStateIfOwned(instance.id) { State.Exited(instance.id, instance.name, -1) }
                stopServiceIfIdle()
                return@launch
            }
        }
    }

    /** 等待带 token 的 URL 就绪（界面用；超时返回 null） */
    suspend fun awaitUrl(timeoutMs: Long = STARTUP_TIMEOUT_MS): String? = withTimeoutOrNull(timeoutMs) {
        val s = state.first { it is State.Running || it is State.Failed || it is State.Exited }
        (s as? State.Running)?.url
    }

    // --- 停止 ---------------------------------------------------------------

    /**
     * 停止当前实例。
     *
     * @param reason 停止原因，用于**日志**与任务区那行的阶段文案。空串表示"调用方没给原因"，
     *   此时由 [stopReasonDefault] 填一句本地化的默认值（`"用户停止"` 这类词会显示给用户，
     *   所以不能写死在代码里）。
     */
    fun stop(reason: String = "") {
        val instanceId = runningInstanceId()
        val resolvedReason = reason.ifBlank { stopReasonDefault() }
        adoptWatcher?.cancel()
        adoptWatcher = null
        if (instanceId == null) {
            _state.value = State.Idle
            _running.value = null
            return
        }
        val name = nameOf[instanceId] ?: instanceId
        stopRequestedFor = instanceId // 标记：接下来的进程退出是"我们要它停"，不是崩溃
        _state.value = State.Stopping(instanceId, name)
        // 实例级：停止动作发生在该实例的生命周期里，实例页要能看到"谁在什么时候停了它"
        DshLogBus.appendFor(instanceId, "[runtime] 停止 $name（$resolvedReason）")
        val h = handle
        handle = null
        val np = nodePid
        nodePid = 0
        // proot 进程本身拿不到 pid 时，"孤儿认领/清理"这条路是断的（挂在 [ProotRunner.Handle.pid]：
        // Android 的 java.lang.Process 没有 pid()，反射拿不到就返回 -1）。
        // 记一行是为了区分"系统起不到 pid"（只影响后台残留清理与状态巡检）与
        // "node pid 也拿不到"（那才是真的无法保证停止干净）。
        DshLogBus.appendFor(
            instanceId,
            "[runtime] 停止：proot pid=${h?.pid?.takeIf { it > 0 } ?: "不可用"}，" +
                "node pid=${np.takeIf { it > 0 } ?: "见 dsh-node.pid"}"
        )
        _running.value = null
        scope.launch {
            // ★ 顺序很重要（B10：停止后 rootfs 里的 node 必须真的死掉）：
            //   1) 先按 pid 终止 node —— 这是唯一**确定性**能带走 rootfs 内进程的办法。
            //      脚本把 node 的真实 pid 写在实例目录的 dsh-node.pid 里（proot 不伪造 getpid，
            //      见 start-dsh.sh 的"停止语义"注释），宿主 kill 这个 pid 就是 kill 到 node 本身。
            //   2) 再 terminate proot：它会先给 proot 发 SIGQUIT（proot 的 SIGQUIT 处理器会
            //      kill_all_tracees() 再退出，等于把还没退的 tracee 一起带走），等一小会儿，
            //      最后才 SIGKILL。
            //   为什么不能只靠 terminate(proot)：proot 把除 SIGQUIT/SIGILL/SIGABRT/SIGFPE/SIGSEGV
            //   以外的信号全装成 SIG_IGN，所以 SIGTERM 对它**完全无效**（这也是日志里
            //   "停止 → 5s 后已停止" 其实是 SIGKILL 收尾的原因）；而 SIGKILL 不可捕获，
            //   proot 的 atexit(kill_all_tracees) / --kill-on-exit 都来不及跑 → node 变孤儿。
            terminateNodeProcess(instanceId, np)
            val prootPid = h?.pid ?: 0
            runCatching { h?.terminate(5000) }
            verifyProotGone(instanceId, prootPid)
            ProcessUtil.removePidFile(instanceId)
            deleteNodePidFile(instanceId)
            // 关键：终止是异步的（最长 5s）。期间用户可能已经启动了新实例，
            // 只有当前状态仍停在这个实例上时才允许置 Idle / 收服务，否则会把新实例的
            // Starting/Running 覆盖掉，并且误杀它的前台通知。
            // 注意：对\"进程句柄\"（ProcessHandle）而言，terminate 会触发 onProcessExit，
            // 那里已把状态落到 Idle；这里主要覆盖\"孤儿进程句柄\"（PidHandle）没有 onExit 回调的情况。
            synchronized(this@DshRuntime) {
                val cur = _state.value
                if (cur is State.Stopping && cur.instanceId == instanceId) {
                    _state.value = State.Idle
                    stopRequestedFor = null
                    // 实例级：该实例生命周期的收尾（对应上面的"停止"），实例页要看到闭环
                    DshLogBus.appendFor(instanceId, "[runtime] $name 已停止")
                    stopServiceIfIdle()
                }
            }
        }
        reportRuntimeCancelled(instanceId, name, resolvedReason)
    }

    /**
     * 只在"当前跑的确实是这个实例"时才停。
     *
     * ★ 为什么需要它（而不是让调用方先查 `runningInstanceId()` 再 `stop()`）：
     *   "查一下是谁在跑，然后停掉"是**两步**，中间没有锁，两处入口（任务行的"停止"按钮、
     *   通知栏、实例卡片）完全可能交错。真要原子，得把判据和停止放进同一个 `@Synchronized` 里 ——
     *   [stop] 本身就是 `@Synchronized` 的，所以把判定挪到**它内部**、由它自己决定停不停，
     *   才是真正没有窗口的做法（先查后停总会留一个 TOCTOU 缝）。
     *
     * 典型踩法：任务行渲染到用户点下按钮之间，运行状态可能已经换成了**另一个**实例
     * （单实例策略下"停 A 起 B"就会）。此时无条件 [stop] 等于"用户点的是 A 那一行，停掉的却是 B"。
     *
     * @return true = 已发起停止；false = 当前跑的不是它（或压根没在跑），**什么都没做**
     */
    @Synchronized
    fun stopIf(instanceId: String, reason: String = ""): Boolean {
        // 判据用 runningInstanceId()（Starting/Running 都算"当前跑的"）：
        // Stopping 已经不属于"还在跑"，对它再发一次停止是空转，返回 false 更诚实。
        if (runningInstanceId() != instanceId) return false
        stop(reason)
        return true
    }

    /**
     * 上报一条"运行任务被主动停止"的终态。
     *
     * ★ 放在 [stop] 里而不是放在 [stopIf] 里：只要"主动停止"发生了，无论从哪个入口进来
     *   （通知栏、前台服务被回收、WebView 返回、实例卡片、任务行），用户看到的都是同一件事 ——
     *   "这个实例被我停了"，任务区就该有一条 CANCELLED。放在 stopIf 里会让其余 5 个入口
     *   全部不上报（而它们才是大多数）。
     *
     * ★ 与 [onProcessExit] 里那条分支的关系：那边报的是"进程真退了"，这条报的是"用户要求停"。
     *   两者先后到达（停止是异步的，最长 5s），都会进 [DshTasks.finished] —— 这是**有意的**：
     *   finished 是事件列表而不是状态表，两件事确实都发生了。不做去重是因为去重需要额外的
     *   "这次退出是不是刚才那条停止引起的"状态，而那个状态本身就是 [stopRequestedFor] 已经在做的事，
     *   再复制一份到 DshTasks 只会多一个可能不同步的真值来源。
     */
    /**
     * 上报一条运行任务的终态（启动完成 / 启动失败 / 进程结束 / 被停止）。
     *
     * ★ 为什么要在这里上报：任务区（[DshTasks.tasks]）只投影"启动中 / 停止中"这类**过渡态**，
     *   进程一旦就绪或退出，那条任务行就从投影里消失了。没有终态上报的话，用户看到的永远是
     *   "行没了"，而"是起来了还是崩了"完全无从判断 —— 这正是本轮要补的那一维。
     *
     * ★ 为什么把 CANCELLED 的上报放在 [stop] 而不是 [stopIf]：见 [reportRuntimeCancelled]。
     *
     * @param name 实例名；调用点如果手里有更准的（[DshInstance.name]）就传进来
     */
    private fun reportRuntime(
        instanceId: String,
        name: String,
        state: DshTask.State,
        stage: String,
        error: String? = null
    ) {
        DshTasks.report(
            DshTask(
                id = DshTasks.runtimeTaskId(instanceId),
                kind = DshTask.Kind.RUNTIME,
                title = name,
                stage = stage,
                action = DshTask.Action.NONE,
                state = state,
                instanceId = instanceId,
                error = error
            )
        )
    }

    private fun reportRuntimeCancelled(instanceId: String, name: String, reason: String) {
        reportRuntime(instanceId, name, DshTask.State.CANCELLED, reason)
    }

    /**
     * 调用方没给停止原因时的默认文案。
     *
     * 为什么不在参数默认值里直接写 `"用户停止"`：这个字符串会**显示在任务区那一行**上
     * （`reportRuntimeCancelled` 把它当 stage），写死在代码里就等于"英文界面下也是中文"。
     * 默认值取空串，到这里再按当前语言补 —— 这样"没给原因"与"给了空原因"是同一条路径。
     */
    private fun stopReasonDefault(): String =
        appContext?.getString(R.string.dsh_reason_user_stop) ?: "stopped by user"

    /**
     * 读取 rootfs 内 node 的 pid 并终止它（B10 修复的主路径）。
     *
     * pid 文件由 start-dsh.sh 在 `node ... &` 之后立刻写入实例目录（`dsh-node.pid`，
     * 内容 `"<pid> <port>"`）：那是 rootfs 内 node 在**宿主上的真实 pid** ——
     * proot 不伪造 getpid，所以脚本里的 `$!` 就是宿主 pid，App 可以直接 kill。
     *
     * 身份校验与 [ProcessUtil.kill] 同源：pid 会被系统复用，杀掉"已经不是 dsh"的 pid 会误伤
     * 别的进程。node 的 cmdline 里必含 dsh 的 bin.js 路径（形如 `.../@deepseek-ai/dsh/lib/bin.js`），
     * 读不到 cmdline（权限/进程已退）时不做校验，交给 kill 自己的 isAlive 判断。
     *
     * @param nodePidHint 内存里记着的 node pid；传 0 表示只认 pid 文件（启动前清理用，
     *                    那时内存里的值可能属于另一个实例）
     */
    private fun terminateNodeProcess(instanceId: String, nodePidHint: Long) {
        val pid = readNodePidFile(instanceId) ?: nodePidHint
        if (pid <= 0) return
        if (!ProcessUtil.isAlive(pid)) {
            deleteNodePidFile(instanceId)
            return
        }
        val cmd = ProcessUtil.cmdline(pid)
        if (cmd != null && !looksLikeNodeProcess(cmd)) {
            // pid 已被系统复用给别的进程：放手，交给 proot 侧收尾（绝不误杀）
            // 实例级：终止的是**这个实例的** node，"跳过终止"这条线索属于它（否则实例页上只会
            // 看到"停止"却没有下文，看不出停止其实没停干净）
            DshLogBus.appendFor(instanceId, "[runtime] pid=$pid 已不属于 dsh node（cmdline 不匹配），跳过按 pid 终止")
            deleteNodePidFile(instanceId)
            return
        }
        DshLogBus.appendFor(instanceId, "[runtime] 终止实例 $instanceId 的 node（pid=$pid）")
        // 归属带上 instanceId：killBlocking 内部还有一次身份校验，那里"没杀成"的日志同样属于这个实例
        ProcessUtil.killBlocking(pid, NODE_KILL_GRACE_MS, expectNode = true, logInstanceId = instanceId)
        deleteNodePidFile(instanceId)
    }

    /**
     * 这个 cmdline 是不是"我们的 node"。
     * 判据取两者之一即可：dsh 的 bin.js 路径（最精确），或 rootfs 内 node 可执行文件路径。
     * 用 `contains` 而不是精确匹配，是因为 cmdline 里带的是 rootfs 内的路径，
     * 而不同阶段的布局（实例目录 / 预装目录）都能命中 `@deepseek-ai/dsh`。
     */
    private fun looksLikeNodeProcess(cmdline: String): Boolean =
        cmdline.contains("@deepseek-ai/dsh") ||
            cmdline.contains("node22/bin/node") ||
            cmdline.contains("/node ")

    /** node pid 文件（与 start-dsh.sh 的 NODE_PID_FILE 默认值一致：$INSTANCE_DIR/dsh-node.pid） */
    private fun nodePidFile(instanceId: String): File = File(DshPaths.instanceDir(instanceId), "dsh-node.pid")

    /** 读 node pid 文件。与 [ProcessUtil.readPidFile] 同一口径：文件只有十几个字节，同步读即可 */
    private fun readNodePidFile(instanceId: String): Long? = runCatching {
        val f = nodePidFile(instanceId)
        if (!f.isFile) return@runCatching null
        f.readText().trim().substringBefore(' ').toLongOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    private fun deleteNodePidFile(instanceId: String) {
        runCatching { nodePidFile(instanceId).delete() }
    }

    /**
     * 本次启动到底用哪个端口（B10 第二半："端口被占别直接失败"）。
     *
     * 实例的 `port` 是上次成功启动后回写的**固定值**（自动端口也会被回写），下次启动直接复用；
     * 一旦它还被占着（上一轮没退干净的 node、或别的 App），node 起来就报
     * `EADDRINUSE` 然后整个进程退出 —— 用户只看到一句"启动失败"。
     *
     * 这里的处置是**改用自动端口**（返回 0，交给系统挑一个空闲端口）并明确写入日志。
     * 为什么不"直接报错让用户改端口"：`PORT=0` 本来就是这个项目的设计（新实例默认 0），
     * 回写机制也现成（[onProcessLine] 会把真实端口写回实例配置），所以换端口是零成本的；
     * 而让用户去设置页改端口，等于把"系统能自己解决的事"推给人。
     * 代价：使用者手工指定的固定端口在这种情况下会变成随机端口（日志与界面显示新端口）。
     *
     * 注意：探测有 TOCTOU 窗口（探完到 node bind 之间端口可能被别人抢走），所以脚本侧
     * 还有一道同样的预检 + `FAILED reason=port-in-use` 归因，两道互相独立。
     */
    private fun resolveLaunchPort(instance: DshInstance): Int {
        val wanted = instance.port
        if (wanted <= 0) return 0
        if (!Probe.isPortOpen(wanted, 300)) return wanted
        // 实例级：被换掉的是**这个实例**的端口，用户需要在自己的实例页知道"端口为什么变了"
        DshLogBus.appendFor(
            instance.id,
            "[runtime] 端口 $wanted 已被占用（可能是上一轮没退干净的 node，或其它应用），" +
                "本次改用系统分配的端口；启动后端口会回写到实例配置"
        )
        return 0
    }

    /**
     * 停止之后校验 proot 是否真的走了（B10 的"可见性"那一半）。
     *
     * **为什么要显式校验**：真机日志里"已停止"这一行是 App 自己写的乐观结论 ——
     * 停止用的信号对 proot 无效（SIGTERM 被 SIG_IGN），只能靠 SIGKILL 收尾，
     * 而 SIGKILL 之后 rootfs 里的 node 未必跟着走。只报"已停止"就会出现
     * "启动 → 已停止 → 下次启动 EADDRINUSE"这种自相矛盾的现象。
     *
     * 这里做两件事：
     * 1. 给 proot 一小段宽限时间（SIGQUIT 那条清理路径需要时间跑完）；
     * 2. 仍然活着就**明说** —— 这行日志是"停止没停干净"的唯一线索，比事后猜"端口为什么被占"便宜得多。
     *
     * @param prootPid 停止前记下的 proot pid；<=0（拿不到 pid）时跳过校验 —— 那种情况下
     *                 "孤儿认领/清理"这条兜底本来也是断的，校验没有意义。
     */
    private suspend fun verifyProotGone(instanceId: String, prootPid: Long) {
        if (prootPid <= 0) return
        val deadline = System.currentTimeMillis() + PROOT_EXIT_GRACE_MS
        while (System.currentTimeMillis() < deadline && ProcessUtil.isAlive(prootPid)) {
            kotlinx.coroutines.delay(100)
        }
        if (!ProcessUtil.isAlive(prootPid)) {
            // 实例级：这是"这个实例的 proot 确实走了"的结论，实例页要能看到（否则只有"停止"没有下文）
            DshLogBus.appendFor(instanceId, "[runtime] proot 已退出（pid=$prootPid）")
            return
        }
        // 实例级：这条"停止没停干净"的警告正是排障时要找的，必须出现在该实例自己的日志里
        DshLogBus.appendFor(
            instanceId,
            "[runtime] 警告：停止后 proot 仍存活（pid=$prootPid）；" +
                "它可能还带着 rootfs 内的进程 —— 下次启动若端口被占，实例会自动改用其它端口"
        )
    }

    /**
     * [stop] 的同步版：等 [DshRuntime] 状态真正离开 Stopping 才返回。
     *
     * **为什么需要**：stop() 里进程的终止是异步的（TERM→等 5s→KILL，最长 5s），
     * stop() 返回时旧进程**可能还没死**。两个场景会踩到：
     * 1. 删除实例：调用方紧接着 `deleteRecursively()`，旧进程若还在写 node_modules，
     *    目录删不干净（残留 300MB 且界面无任何提示）。
     * 2. 启动新实例：旧 proot/node 还没退、新 node 又起来 → 短暂双 300MB 进程 + 端口抢占。
     *
     * 本方法轮询 [_state]，直到不再处于\"本实例的 Stopping\"（即进程已退出、状态已被
     * onProcessExit / stop 协程落到 Idle 或别的新状态）。超时返回 false，调用方自行决定
     * 是否继续（至少要避免\"删目录时进程还在写\"）。
     *
     * @return true 表示已离开 Stopping；false 表示 [timeoutMs] 内仍未停完。
     */
    suspend fun stopAndWait(reason: String, timeoutMs: Long = 8_000): Boolean {
        val instanceId = runningInstanceId()
        if (instanceId == null) {
            // 本来就没在跑：确保状态干净
            _state.value = State.Idle
            _running.value = null
            return true
        }
        stop(reason)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val cur = _state.value
            if (cur !is State.Stopping || cur.instanceId != instanceId) return true
            kotlinx.coroutines.delay(50)
        }
        val cur = _state.value
        return !(cur is State.Stopping && cur.instanceId == instanceId)
    }

    /** 只在确实没有实例在启动/运行时才收掉前台服务（避免误杀新实例的保活通知） */
    private fun stopServiceIfIdle() {
        val st = _state.value
        if (st is State.Starting || st is State.Running) return
        DshRuntimeService.stopQuietly(appContext)
    }

    /** 进程意外退出/启动失败后的收尾 */
    private fun failAndCleanup(instance: DshInstance, reason: String) {
        val h = handle
        handle = null
        val np = nodePid
        nodePid = 0
        _running.value = null
        scope.launch {
            // 与 stop() 同源：先按 pid 收掉 rootfs 内的 node，再收 proot。
            // 这条路径覆盖"启动超时"——超时的进程仍然是活的 node，不收掉就会占着端口，
            // 下一次启动直接 EADDRINUSE（正是 B10 的现场）。
            terminateNodeProcess(instance.id, np)
            runCatching { h?.terminate(3000) }
            ProcessUtil.removePidFile(instance.id)
        }
        _state.value = State.Failed(instance.id, instance.name, reason)
        stopServiceIfIdle()
        // 终态上报：失败原因进任务区（否则任务行随着 Starting 一起消失，用户只看到"行没了"）
        reportRuntime(instance.id, instance.name, DshTask.State.FAILED, reason, error = reason)
    }

    /** 兜底重试内部失败时的收尾（不重复打日志到"失败原因"里，失败原因由调用方给出） */
    private fun failRetry(instance: DshInstance, reason: String) {
        setStateIfOwned(instance.id) { State.Failed(instance.id, instance.name, reason) }
        stopServiceIfIdle()
        reportRuntime(instance.id, instance.name, DshTask.State.FAILED, reason, error = reason)
    }

    /**
     * 某实例的最近若干行（去掉时间戳前缀），用于**该实例**的失败归因。
     *
     * ★ 为什么读实例流而不是全局流：seccomp 误判、`EADDRINUSE` 归因、失败原因里附的"最近输出"
     *   都是在判断"这个实例为什么起不来"。全局流里混着别的实例、别的安装、底座解压的输出，
     *   拿它做判据会把别人的行当成本实例的线索（例如别的一次安装里的 `Error: ...` 触发误判），
     *   附到错误提示里的"最近输出"也会是别人的东西 —— 与实例页看到的内容对不上。
     */
    private fun recentLogTail(instanceId: String, count: Int = 25): List<String> =
        DshLogBus.snapshotFor(instanceId).value.lines.takeLast(count).map { it.substringAfter("  ") }

    /**
     * 判断"进程在就绪前退出"是否像是 proot 与内核 ptrace/seccomp 不兼容。
     *
     * 典型表现（真机上出现过的一类问题）：proot 起得来，但 rootfs 里的进程刚跑就被信号杀掉，
     * 退出码是 128+signal（例如 SIGSYS=31 → 159、SIGSEGV=11 → 139），日志里出现
     * seccomp / ptrace / Bad system call / Operation not permitted 等字样。
     * 这类情况带 `PROOT_NO_SECCOMP=1` 重试通常就好了（见 [seccompFallbackUsed]）。
     */
    private fun looksLikeSeccompTrouble(code: Int, tail: List<String>): Boolean {
        // 被信号杀死：proot 自身或 rootfs 内进程被内核拦下（注：主动停止的 143 在调用点已被排除）
        if (code > 128) return true
        val text = tail.joinToString(" ").lowercase()
        return text.contains("seccomp") || text.contains("ptrace") ||
            text.contains("bad system call") || text.contains("operation not permitted")
    }

    /**
     * 就绪前早期退出时，必要时安排一次 `PROOT_NO_SECCOMP=1` 的兜底重试。
     * @return true 表示已安排重试（此时**不要**落 Failed，状态维持在 Starting，界面不会闪一下失败）
     */
    private fun retryIfSeccompLooksGuilty(instance: DshInstance, code: Int): Boolean {
        val ctx = appContext ?: return false
        if (seccompFallbackUsed.contains(instance.id)) return false
        // 判据只看**本实例**的输出（见 [recentLogTail] 的说明），避免被别的实例的行带偏
        if (!looksLikeSeccompTrouble(code, recentLogTail(instance.id))) return false
        seccompFallbackUsed.add(instance.id)
        DshLogBus.appendFor(
            instance.id,
            "[runtime] ${instance.name} 在就绪前退出（code=$code），疑似 proot 与内核 seccomp/ptrace 不兼容；" +
                "自动带 PROOT_NO_SECCOMP=1 重试一次"
        )
        scope.launch {
            runCatching { startLocked(ctx, instance, noSeccomp = true) }
                .onFailure {
                    DshLogBus.appendFor(instance.id, "[runtime] seccomp 兜底重试失败：${it.message}")
                }
        }
        return true
    }

    private fun onProcessLine(instance: DshInstance, line: String) {
        // 实例级：这是**该实例的进程**自己打印的 stdout/stderr（dsh 的 web 输出、start-dsh.sh 的
        // 就绪/失败标记）。"从开始到停止只显示这个实例发出的日志"要的就是这些行 —— 它们同时进
        // 全局流，所以排障时的"时间线全貌"不会丢（见 DshLogBus.appendFor 的说明）。
        DshLogBus.appendFor(instance.id, line)
        // 脚本在 `node ... &` 之后立刻打印它，并在实例目录写下同一对值（见 start-dsh.sh）。
        // 记进内存是给"pid 文件被脚本收尾删掉、但进程还没死透"这种边角情况兜底的。
        NODE_PID_LINE.find(line)?.let { m ->
            m.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.let { nodePid = it }
        }
        // 1) dsh 自己打印的带 token URL（形如 dsh web: http://127.0.0.1:3080/?token=XXX）
        // 2) start-dsh.sh 的就绪标记（形如 [start-dsh] READY url=http://...）
        val url = UrlScanner.findAuthenticatedUrl(line)
            ?: UrlScanner.findMarkerUrl(line)
            ?: return
        UrlScanner.tokenOf(url)?.let { DshLogBus.registerSecret(it) }

        val cur = _state.value
        if (cur !is State.Starting || cur.instanceId != instance.id) {
            // 已经 Running（dsh 重复打印 URL）时只刷新 url。
            // ★ 但必须确认"现在 Running 的仍是本实例"——否则被停掉的旧实例（终止是异步的，最长 5s）
            // 在退出前把 URL 打出来，就会把新实例的 url/port 覆盖成旧实例的，WebView 于是连到已经
            // 关掉的端口上。第三轮已把"状态跃迁先校验 instanceId"定成约定，这一处当时漏了。
            if (cur is State.Running && cur.instanceId == instance.id) {
                val port = UrlScanner.portOf(url) ?: cur.port
                _state.value = cur.copy(url = url, port = port)
                _running.value = Running(cur.instanceId, port, url)
            }
            return
        }
        val port = UrlScanner.portOf(url) ?: instance.port
        _state.value = State.Running(instance.id, instance.name, url, port)
        _running.value = Running(instance.id, port, url)
        handle?.pid?.takeIf { it > 0 }?.let { ProcessUtil.writePidFile(instance.id, it, port) }
        // 端口是自动分配的：写回实例，便于界面显示与下次复用
        if (instance.port != port) {
            DshInstances.updateConfig(instance.id, port = port)
        }
        // 实例级：就绪是本实例生命周期里最该被看见的一行（"什么时候开始能用"），
        // 端口也是它自己的
        DshLogBus.appendFor(instance.id, "[runtime] ${instance.name} 就绪：端口 $port")
        // 终态上报：任务区那条"启动中"到这里就该有个结局（成功）；不报的话它只是**消失**，
        // 用户看不出"起来了"还是"没起来"。
        reportRuntime(
            instance.id, instance.name, DshTask.State.DONE,
            // 阶段文案跟着界面语言（任务行会显示它）
            appContext?.getString(R.string.dsh_task_port, port) ?: "port $port"
        )
    }

    private fun onProcessExit(instance: DshInstance, code: Int) {
        // ★ 归属校验（第五轮修复）：退出回调是异步的。用户"停 A 起 B"时，A 的退出回调完全可能
        // 在 B 已经 Starting/Running 之后才到；旧实现无条件 `handle = null` / `_running = null`，
        // 并把状态写成 Exited(A)，于是 B 的进程句柄被抹掉（[stop] 再也拿不到句柄，只能删 pid 文件，
        // B 的 proot 变成谁也停不掉的孤儿），界面也显示成"A 退出了"。
        // 现在：只有"当前状态确实属于本实例"才允许动状态与句柄，否则只做无副作用的清理。
        val cur = _state.value
        if (ownerOf(cur) != instance.id) {
            // 实例级：说的仍是"A 的进程退出了"，A 的实例页应该看到自己的进程结束了；
            // 括号里那半句（状态已被别人接管）是跨实例竞态的线索，但主体事实属于 A。
            DshLogBus.appendFor(
                instance.id,
                "[runtime] ${instance.name} 的进程已退出（code=$code），" +
                    "但当前状态不属于它（现状：${cur::class.simpleName}），仅清理 pid 文件"
            )
            ProcessUtil.removePidFile(instance.id)
            return
        }

        val wasStopRequested = stopRequestedFor == instance.id
        handle = null
        // 进程已经退出：内存里的 node pid 作废（避免它被系统复用后误杀，见 ProcessUtil.kill 的身份校验）
        nodePid = 0
        _running.value = null
        ProcessUtil.removePidFile(instance.id)
        deleteNodePidFile(instance.id)

        if (wasStopRequested) {
            // 主动停止导致的退出（SIGTERM→143 等）：这是预期内的，落到 Idle，不要报成崩溃。
            stopRequestedFor = null
            setStateIfOwned(instance.id) { State.Idle }
            // 实例级：退出码是**这个实例的**收尾事实
            DshLogBus.appendFor(instance.id, "[runtime] ${instance.name} 已停止（code=$code）")
            // ★ 这里**不再上报一次终态**：这次退出的起因是 [stop]，CANCELLED 已经由它在用户点下
            //   "停止"的那一刻报过了。再报一条"已停止"只会让任务区为同一次操作出现两行。
        } else if (cur is State.Failed) {
            // 失败后的收尾（例如启动超时已经把进程收掉）：保留失败状态与原因，别让"超时"被改写成"进程退出"
            DshLogBus.appendFor(instance.id, "[runtime] ${instance.name} 的失败进程已退出（code=$code），保留失败原因")
            // 终态同理已在 [failAndCleanup] 报过（FAILED），这里不重复。
        } else if (cur is State.Exited) {
            DshLogBus.appendFor(instance.id, "[runtime] ${instance.name} 的进程已退出（code=$code）")
        } else if (cur is State.Starting) {
            // 还没就绪就退了：先看是不是 proot/seccomp 不兼容，是的话自动兜底重试一次；
            // 否则把最后几行日志作为线索抛给界面。
            // ★ 端口被占（B10 的第二半）：脚本会打印 `FAILED reason=port-in-use`，node 也会打
            //   `Error: listen EADDRINUSE`。这种失败**不是** seccomp 问题，也不该只说一句
            //   "退出码 1"：必须让用户看到"端口被占"以及"下次会自动换端口"。
            //   （正常情况下脚本的预检已经把端口换掉了；能走到这里说明预检之后端口又被抢走 ——
            //   例如另一个 dsh 实例 / 别的应用在同一瞬间 bind 了同一个端口。）
            // ★ 只看本实例的输出：拿全局流判定会把别的实例/别的安装里的 EADDRINUSE 当成本实例的
            //   端口冲突（那会让一次无关的失败被归错因，重试也换不到端口）。
            val portInUse = recentLogTail(instance.id).any {
                it.contains("reason=port-in-use") || it.contains("EADDRINUSE")
            }
            if (portInUse) {
                // 文案不放进 strings.xml（本轮改动范围只允许三个文件）：直接给一句可操作的中文。
                // 文案跟着界面语言走（这条会显示在启动失败对话框里）。
                // 拿不到 Context 时回落到英文短句 —— 这是个不该发生的分支，但不能让它变成空字符串。
                val reason = appContext?.getString(R.string.dsh_start_port_in_use)
                    ?: "Port in use (EADDRINUSE). The next start will pick a free port automatically."
                setStateIfOwned(instance.id) { State.Failed(instance.id, instance.name, reason) }
                DshLogBus.appendFor(instance.id, "[runtime] 启动失败：$reason")
                reportRuntime(instance.id, instance.name, DshTask.State.FAILED, reason, error = reason)
            } else if (!retryIfSeccompLooksGuilty(instance, code)) {
                val reason = appendLastLinesHint(
                    instance.id,
                    appContext?.getString(R.string.dsh_reason_exited_early, code)
                        ?: "进程提前退出（code=$code）"
                )
                setStateIfOwned(instance.id) { State.Failed(instance.id, instance.name, reason) }
                DshLogBus.appendFor(instance.id, "[runtime] 启动失败：$reason")
                reportRuntime(instance.id, instance.name, DshTask.State.FAILED, reason, error = reason)
            }
            // ★ 走了 retryIfSeccompLooksGuilty 那条（返回 true）时**故意不报终态**：
            //   状态仍留在 Starting，兜底重试马上会再起一次 —— 此刻报"失败"是假失败，
            //   用户会看到一条根本不成立的"启动失败"，而下一次重试可能就成功了。
        } else {
            // 就绪后正常/异常结束（例如 agent 自己退出）
            DshLogBus.appendFor(instance.id, "[runtime] 进程退出，code=$code")
            setStateIfOwned(instance.id) { State.Exited(instance.id, instance.name, code) }
            // 终态上报：进程结束也是一件"有结局的事"，任务区要能说清它结束了（而不是行凭空消失）
            reportRuntime(
                instance.id, instance.name, DshTask.State.DONE,
                appContext?.getString(R.string.dsh_runtime_exited, code) ?: "进程已退出（code=$code）"
            )
        }
        stopServiceIfIdle()
    }

    /**
     * 把"最近几行输出"拼进失败原因。
     * ★ 取的是**该实例**的末尾几行（与实例页看到的一致）—— 用全局流末尾会出现"错误提示里引用了
     *   别的实例的输出"这种没法对照的怪现象。
     */
    private fun appendLastLinesHint(instanceId: String, base: String): String {
        val tail = DshLogBus.snapshotFor(instanceId).value.lines.takeLast(3)
            .joinToString(" / ") { it.substringAfter("  ") }
            .take(240)
        return if (tail.isBlank()) base else "$base；最近输出：$tail"
    }

    /**
     * 清空日志。
     * @param instanceId null（默认，行为与改造前一致）= 清全局流；给 id = 只清该实例的流
     *                   （实例详情页的"清空"按钮要的是这个：清掉别的实例或 App 级事件都不是它的语义）
     */
    fun clearLogs(instanceId: String? = null) {
        if (instanceId == null) DshLogBus.clear() else DshLogBus.clearFor(instanceId)
    }

    /** 状态复位（界面确认过失败信息后调用） */
    fun resetState() {
        if (_state.value !is State.Running && _state.value !is State.Starting) {
            _state.value = State.Idle
        }
    }

    /**
     * node 堆上限：按设备总内存给一个保守值，避免被 Android 的 lowmemorykiller 直接干掉。
     * 放 NODE_OPTIONS 里（`--expose-internals` 不允许放这里，但 `--max-old-space-size` 可以）。
     */
    private fun nodeHeapMb(context: Context): Int = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = (info.totalMem / (1024 * 1024)).toInt()
        (totalMb / 8).coerceIn(384, 2048)
    }.getOrDefault(768)
}

/** 端口连通性探测（判断孤儿进程是否还活着、是否已就绪） */
object Probe {
    fun isPortOpen(port: Int, timeoutMs: Int = 800): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    }.getOrDefault(false)
}

/** 从 dsh / 脚本输出里提取本地 URL 与 token */
object UrlScanner {

    /** dsh 自己打印：`dsh web: http://127.0.0.1:3080/?token=XXX (LAN: http://...)` */
    private val AUTH_URL = Regex("""https?://[^\s"']*?:\d+/[^\s"']*?[?&]token=[A-Za-z0-9_\-]+""")

    /** 脚本就绪标记：`[start-dsh] READY url=http://127.0.0.1:3080/?token=XXX` */
    private val MARKER_URL = Regex("""(?i)READY\s+url=(\S+)""")

    /** 只要 host:port（用于 start-dsh.sh 打印的 `listening http://127.0.0.1:8080` 之类） */
    private val ANY_URL = Regex("""https?://(?:[\w\-.]+|\d+\.\d+\.\d+\.\d+):(\d+)""")

    fun findAuthenticatedUrl(line: String): String? =
        AUTH_URL.find(line)?.value

    fun findMarkerUrl(line: String): String? =
        MARKER_URL.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    fun portOf(url: String): Int? = runCatching {
        val p = java.net.URI(url).port
        if (p > 0) p else null
    }.getOrNull() ?: ANY_URL.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun tokenOf(url: String): String? =
        Regex("""[?&]token=([A-Za-z0-9_\-]+)""").find(url)?.groupValues?.getOrNull(1)
}

/** 进程与 pid 文件相关的小工具（孤儿进程清理用） */
object ProcessUtil {

    data class PidMeta(val pid: Long, val port: Int)

    fun writePidFile(instanceId: String, pid: Long, port: Int) {
        runCatching {
            val f = DshPaths.instancePidFile(instanceId)
            f.parentFile?.mkdirs()
            // 不写 token/URL，只写 pid 与端口（够用且不落敏感信息）
            JsonUtils.GSON.toJson(PidMeta(pid, port)).let { f.writeText(it) }
        }
    }

    fun readPidFile(instanceId: String): PidMeta? {
        val f = DshPaths.instancePidFile(instanceId)
        if (!f.isFile) return null
        return runCatching { JsonUtils.fromNonNullJson(f.readText(), PidMeta::class.java) }.getOrNull()
    }

    fun removePidFile(instanceId: String) {
        runCatching { DshPaths.instancePidFile(instanceId).delete() }
    }

    fun isAlive(pid: Long): Boolean {
        if (pid <= 0) return false
        return File("/proc/$pid").exists() || runCatching {
            android.os.Process.sendSignal(pid.toInt(), 0); true
        }.getOrDefault(false)
    }

    fun cmdline(pid: Long): String? = runCatching {
        File("/proc/$pid/cmdline").readText().replace('\u0000', ' ')
    }.getOrNull()

    suspend fun kill(
        pid: Long,
        graceMillis: Long = 5000,
        expectNode: Boolean = false,
        logInstanceId: String? = null
    ) = withContext(Dispatchers.IO) { killBlocking(pid, graceMillis, expectNode, logInstanceId) }

    /**
     * [kill] 的阻塞版。
     *
     * **为什么要有阻塞版**：停止路径里有两处不在协程里 —— [DshRuntime.startLocked]（`@Synchronized`，
     * 启动前要先把上一轮残留的 node 收掉，否则它占着端口）与认领巡检。它们都在 IO 线程上执行
     * （见 DshLauncher 的 `withContext(Dispatchers.IO)`），阻塞等待 TERM→KILL 的宽限期是安全的。
     *
     * @param logInstanceId 这次终止是**为哪个实例**做的（用于日志归属）。给 id = 该实例的流；
     *                      null = 全局流（调用方也说不清是哪个实例时，宁可归全局也不要猜）。
     */
    fun killBlocking(
        pid: Long,
        graceMillis: Long = 5000,
        expectNode: Boolean = false,
        logInstanceId: String? = null
    ) {
        if (!isAlive(pid)) return
        // ★ 身份校验（第五轮修复）：pid 是会被系统复用的。停止"认领来的孤儿进程"时（PidHandle），
        // 我们手里只有一个上次记下的 pid；如果那个进程早就退出了、pid 又被别的进程用掉，
        // 直接发 SIGTERM/SIGKILL 就会误杀无辜。cmdline 能读到就必须确认它是 proot，否则放手。
        // [expectNode] = true 时改为确认"它是我们 rootfs 内的 node"（B10 按 pid 终止 node 用）。
        val cmd = cmdline(pid)
        val identityOk = if (expectNode) {
            cmd == null || cmd.contains("@deepseek-ai/dsh") ||
                cmd.contains("node22/bin/node") || cmd.contains("/node ")
        } else {
            cmd == null || cmd.contains("proot")
        }
        if (!identityOk) {
            // 实例级（有归属时）：这条"没杀成"的线索属于发起终止的那个实例 —— 它的实例页上
            // 只有"停止"却看不到下文，就会以为停干净了（而 pid 其实还在，下次启动可能端口冲突）。
            logFor(logInstanceId, "[runtime] pid=$pid 已不属于 dsh（cmdline 不匹配），跳过清理")
            removePidFileByPid(pid)
            return
        }
        runCatching { android.os.Process.sendSignal(pid.toInt(), 15) } // SIGTERM
        val deadline = System.currentTimeMillis() + graceMillis
        while (System.currentTimeMillis() < deadline && isAlive(pid)) {
            Thread.sleep(200)
        }
        if (isAlive(pid)) {
            runCatching { android.os.Process.sendSignal(pid.toInt(), 9) } // SIGKILL
        }
        removePidFileByPid(pid)
    }

    /** 日志归属的小工具：[instanceId] 为空时进全局流（不猜实例） */
    private fun logFor(instanceId: String?, line: String) {
        if (instanceId == null) DshLogBus.append(line) else DshLogBus.appendFor(instanceId, line)
    }

    private fun removePidFileByPid(pid: Long) {
        runCatching {
            File(DshPaths.INSTANCES_DIR).listFiles()?.forEach { dir ->
                val f = File(dir, "dsh.pid")
                if (f.isFile && readPidFile(dir.name)?.pid == pid) f.delete()
            }
        }
    }

    /**
     * App 冷启时清理"上次进程被系统杀掉后留下的孤儿"。
     * 只清理 cmdline 里确实是我们这个实例的 proot 进程，避免误伤。
     *
     * ★ 这两行保持**全局**：它属于"pid 文件 + 孤儿进程"这套跨实例的清理机制
     *   （`removePidFileByPid` 会扫所有实例目录找同一个 pid），是启动器在整理**进程现场**，
     *   而不是某个实例自己跑出来的输出。放到实例流里会让实例页混进"别人的 pid 被忽略"这种
     *   与它无关的行。
     */
    fun killStale(instanceId: String) {
        val meta = readPidFile(instanceId) ?: return
        if (!isAlive(meta.pid)) {
            removePidFile(instanceId)
            return
        }
        val cmd = cmdline(meta.pid) ?: ""
        if (cmd.contains("proot") && cmd.contains(instanceId)) {
            DshLogBus.append("[runtime] 清理实例 $instanceId 的残留进程 pid=${meta.pid}")
            runCatching { android.os.Process.sendSignal(meta.pid.toInt(), 9) }
        } else {
            DshLogBus.append("[runtime] pid=${meta.pid} 已不属于本实例，忽略")
        }
        removePidFile(instanceId)
    }
}
