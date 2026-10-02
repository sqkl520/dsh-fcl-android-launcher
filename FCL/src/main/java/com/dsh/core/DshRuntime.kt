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
 * 3. **密钥不从磁盘读**：API key 通过子进程环境变量传入，不再写 `credentials.env` 明文文件；
 *    启动 token 会注册到 [DshLogBus] 做脱敏，不再泄漏进日志。
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

    private class ProcessHandle(private val handle: ProotRunner.Handle) : Handle {
        override val pid: Long get() = handle.pid()
        override fun isAlive(): Boolean = handle.isAlive()
        override suspend fun terminate(graceMillis: Long) = handle.terminate(graceMillis)
    }

    private class PidHandle(override val pid: Long) : Handle {
        override fun isAlive(): Boolean = ProcessUtil.isAlive(pid)
        override suspend fun terminate(graceMillis: Long) = ProcessUtil.kill(pid, graceMillis)
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

        val pre = ProotCommand.preflight(
            context, DshPaths.ROOTFS_DIR,
            "${ProotCommand.GUEST_ROOT}/scripts/start-dsh.sh"
        )
        if (!pre.ok) {
            val reason = pre.reason ?: "运行时未就绪"
            // 兜底重试阶段如果连预检都过不去，说明这次重试也没戏：把状态落到 Failed，
            // 否则状态会永远停在 Starting（界面上是"启动中"转圈不动）。
            if (noSeccomp) failRetry(instance, reason)
            return StartOutcome.NotReady(reason)
        }

        // 真正校验\"装好了\"：状态字段可能是过期的，以磁盘为准
        val pkg = DshPaths.instanceDshPackageJson(instance.id)
        val binJs = DshPaths.instanceDshBinJs(instance.id)
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
            DshLogBus.append("[runtime] ${instance.name} 已在运行，复用现有进程")
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

        val instancePathInRootfs = "${ProotCommand.GUEST_ROOT}/instances/${instance.id}"
        val apiKey = DshCredentials.load(context, instance.id)
        if (apiKey == null) {
            DshLogBus.append(
                "[runtime] 警告：${instance.name} 未配置可用 API Key，界面能起但对话会失败"
            )
        } else {
            DshLogBus.registerSecret(apiKey)
        }

        val argvEnv = mapOf(
            "INSTANCE_DIR" to instancePathInRootfs,
            "DSH_HOME" to "$instancePathInRootfs/home",
            "PORT" to instance.port.toString(), // 0 = 让 dsh 自己挑
            "HOST" to "127.0.0.1",               // 只绑回环，不暴露局域网
            "PROFILE" to instance.profile,
            "NPM_CONFIG_CACHE" to "${ProotCommand.GUEST_ROOT}/npm-cache",
            // 脚本侧的就绪等待比启动器看门狗略短，保证脚本先给出 FAILED 的明确原因
            "READY_TIMEOUT" to (STARTUP_TIMEOUT_MS / 1000 - 15).toString(),
            "NODE_OPTIONS" to "--max-old-space-size=${nodeHeapMb(context)}"
        )
        val secretEnv = HashMap<String, String>()
        if (apiKey != null) {
            secretEnv["DEEPSEEK_API_KEY"] = apiKey
            secretEnv["DEEPSEEK_DEFAULT_MODEL"] = instance.model
        }
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
        _running.value = Running(instance.id, instance.port, null)
        DshLogBus.append(
            "[runtime] 启动 ${instance.name}（dsh ${instance.dshVersion ?: "?"}，" +
                "profile=${instance.profile}，端口=${if (instance.port == 0) "自动" else instance.port.toString()}）"
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
            DshLogBus.append("[runtime] 启动失败：$reason")
            _state.value = State.Failed(instance.id, instance.name, reason)
            _running.value = null
            return StartOutcome.Failed(reason)
        }
        handle = ProcessHandle(h)
        val pid = h.pid()
        if (pid > 0) {
            ProcessUtil.writePidFile(instance.id, pid, instance.port)
        } else {
            // 拿不到 pid（Android 的 Process 实现没有 pid() 且反射失败）时，孤儿进程无法被认领/清理，
            // 明确记一行，避免将来\"为什么后台有个 300MB 的 node 杀不掉\"无从查起。
            DshLogBus.append("[runtime] 警告：无法取得 proot 进程 pid，孤儿进程自动清理/认领将不可用")
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
                DshLogBus.append("[runtime] 启动超时：$reason")
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

        handle = PidHandle(meta.pid)
        nameOf[instance.id] = instance.name
        _state.value = State.Running(instance.id, instance.name, "http://127.0.0.1:$port/", port, adopted = true)
        _running.value = Running(instance.id, port, "http://127.0.0.1:$port/")
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

    /** 停止当前实例。[reason] 只用于日志。 */
    fun stop(reason: String = "用户停止") {
        val instanceId = runningInstanceId()
        adoptWatcher?.cancel()
        adoptWatcher = null
        if (instanceId == null) {
            _state.value = State.Idle
            _running.value = null
            return
        }
        val name = nameOf[instanceId] ?: instanceId
        stopRequestedFor = instanceId // 标记：接下来的进程退出是\"我们要它停\"，不是崩溃
        _state.value = State.Stopping(instanceId, name)
        DshLogBus.append("[runtime] 停止 $name（$reason）")
        val h = handle
        handle = null
        _running.value = null
        scope.launch {
            runCatching { h?.terminate(5000) }
            ProcessUtil.removePidFile(instanceId)
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
                    DshLogBus.append("[runtime] $name 已停止")
                    stopServiceIfIdle()
                }
            }
        }
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
        _running.value = null
        scope.launch {
            runCatching { h?.terminate(3000) }
            ProcessUtil.removePidFile(instance.id)
        }
        _state.value = State.Failed(instance.id, instance.name, reason)
        stopServiceIfIdle()
    }

    /** 兜底重试内部失败时的收尾（不重复打日志到"失败原因"里，失败原因由调用方给出） */
    private fun failRetry(instance: DshInstance, reason: String) {
        setStateIfOwned(instance.id) { State.Failed(instance.id, instance.name, reason) }
        stopServiceIfIdle()
    }

    /** 最近若干行日志（去掉时间戳前缀），用于失败归因 */
    private fun recentLogTail(count: Int = 25): List<String> =
        DshLogBus.snapshot.value.lines.takeLast(count).map { it.substringAfter("  ") }

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
        if (!looksLikeSeccompTrouble(code, recentLogTail())) return false
        seccompFallbackUsed.add(instance.id)
        DshLogBus.append(
            "[runtime] ${instance.name} 在就绪前退出（code=$code），疑似 proot 与内核 seccomp/ptrace 不兼容；" +
                "自动带 PROOT_NO_SECCOMP=1 重试一次"
        )
        scope.launch {
            runCatching { startLocked(ctx, instance, noSeccomp = true) }
                .onFailure { DshLogBus.append("[runtime] seccomp 兜底重试失败：${it.message}") }
        }
        return true
    }

    private fun onProcessLine(instance: DshInstance, line: String) {
        DshLogBus.append(line)
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
        DshLogBus.append("[runtime] ${instance.name} 就绪：端口 $port")
    }

    private fun onProcessExit(instance: DshInstance, code: Int) {
        // ★ 归属校验（第五轮修复）：退出回调是异步的。用户"停 A 起 B"时，A 的退出回调完全可能
        // 在 B 已经 Starting/Running 之后才到；旧实现无条件 `handle = null` / `_running = null`，
        // 并把状态写成 Exited(A)，于是 B 的进程句柄被抹掉（[stop] 再也拿不到句柄，只能删 pid 文件，
        // B 的 proot 变成谁也停不掉的孤儿），界面也显示成"A 退出了"。
        // 现在：只有"当前状态确实属于本实例"才允许动状态与句柄，否则只做无副作用的清理。
        val cur = _state.value
        if (ownerOf(cur) != instance.id) {
            DshLogBus.append(
                "[runtime] ${instance.name} 的进程已退出（code=$code），" +
                    "但当前状态不属于它（现状：${cur::class.simpleName}），仅清理 pid 文件"
            )
            ProcessUtil.removePidFile(instance.id)
            return
        }

        val wasStopRequested = stopRequestedFor == instance.id
        handle = null
        _running.value = null
        ProcessUtil.removePidFile(instance.id)

        if (wasStopRequested) {
            // 主动停止导致的退出（SIGTERM→143 等）：这是预期内的，落到 Idle，不要报成崩溃。
            stopRequestedFor = null
            setStateIfOwned(instance.id) { State.Idle }
            DshLogBus.append("[runtime] ${instance.name} 已停止（code=$code）")
        } else if (cur is State.Failed) {
            // 失败后的收尾（例如启动超时已经把进程收掉）：保留失败状态与原因，别让"超时"被改写成"进程退出"
            DshLogBus.append("[runtime] ${instance.name} 的失败进程已退出（code=$code），保留失败原因")
        } else if (cur is State.Exited) {
            DshLogBus.append("[runtime] ${instance.name} 的进程已退出（code=$code）")
        } else if (cur is State.Starting) {
            // 还没就绪就退了：先看是不是 proot/seccomp 不兼容，是的话自动兜底重试一次；
            // 否则把最后几行日志作为线索抛给界面。
            if (!retryIfSeccompLooksGuilty(instance, code)) {
                val reason = appendLastLinesHint(
                    appContext?.getString(R.string.dsh_reason_exited_early, code)
                        ?: "进程提前退出（code=$code）"
                )
                setStateIfOwned(instance.id) { State.Failed(instance.id, instance.name, reason) }
                DshLogBus.append("[runtime] 启动失败：$reason")
            }
        } else {
            // 就绪后正常/异常结束（例如 agent 自己退出）
            DshLogBus.append("[runtime] 进程退出，code=$code")
            setStateIfOwned(instance.id) { State.Exited(instance.id, instance.name, code) }
        }
        stopServiceIfIdle()
    }

    private fun appendLastLinesHint(base: String): String {
        val tail = DshLogBus.snapshot.value.lines.takeLast(3)
            .joinToString(" / ") { it.substringAfter("  ") }
            .take(240)
        return if (tail.isBlank()) base else "$base；最近输出：$tail"
    }

    fun clearLogs() = DshLogBus.clear()

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

    suspend fun kill(pid: Long, graceMillis: Long = 5000) = withContext(Dispatchers.IO) {
        if (!isAlive(pid)) return@withContext
        // ★ 身份校验（第五轮修复）：pid 是会被系统复用的。停止"认领来的孤儿进程"时（PidHandle），
        // 我们手里只有一个上次记下的 pid；如果那个进程早就退出了、pid 又被别的进程用掉，
        // 直接发 SIGTERM/SIGKILL 就会误杀无辜。cmdline 能读到就必须确认它是 proot，否则放手。
        val cmd = cmdline(pid)
        if (cmd != null && !cmd.contains("proot")) {
            DshLogBus.append("[runtime] pid=$pid 已不属于 dsh（cmdline 不匹配），跳过清理")
            removePidFileByPid(pid)
            return@withContext
        }
        runCatching { android.os.Process.sendSignal(pid.toInt(), 15) } // SIGTERM
        val deadline = System.currentTimeMillis() + graceMillis
        while (System.currentTimeMillis() < deadline && isAlive(pid)) {
            kotlinx.coroutines.delay(200)
        }
        if (isAlive(pid)) {
            runCatching { android.os.Process.sendSignal(pid.toInt(), 9) } // SIGKILL
        }
        removePidFileByPid(pid)
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
