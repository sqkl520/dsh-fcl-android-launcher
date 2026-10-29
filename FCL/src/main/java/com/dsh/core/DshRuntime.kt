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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ★ fix12（C-07 / C-08）：启动时刻**真正会跑哪一份 dsh 包** —— [resolveLaunchTarget] 的结果。
 *
 * 为什么是启动时刻的推导值、而不是持久化的状态：它回答的是"这一次 fork 会跑哪份包、和用户
 * 要的版本是否一致"，随磁盘（rootfs 重解压 / 实例目录被删）随时变化。写进清单会过期，
 * 且回滚 APK 时不可恢复（同 E1 §5 对 `DEGRADED` 枚举的否决理由）。
 *
 * 四个分支与脚本侧 `start-dsh.sh` 的 `DSH_SRC` 一一对应，宿主通过 `DSH_LAUNCH_TARGET`
 * 把结论**下发**给脚本（见 [DshRuntime.startLocked] 的 argvEnv）—— 两边不再各判一套。
 */
sealed interface LaunchTarget {
    /** 跑实例自己的包（`<instance>/node_modules/@deepseek-ai/dsh`） */
    data class Instance(val version: String?) : LaunchTarget

    /** 跑 rootfs 预装的包（实例目录里没有自己的包，且预装与请求版本相符/未指定版本） */
    data class Preinstalled(val version: String?) : LaunchTarget

    /**
     * 会跑预装，但预装版本 ≠ 实例请求的版本（`expected` = 用户要的，`actual` = 实际会跑的）。
     * 这是 C-07 的真实可达路径：旧实现会在用户点启动后**静默**跑起预装 0.1.6。
     */
    data class Degraded(
        val expected: String?,
        val actual: String?,
        val preinstalled: Boolean
    ) : LaunchTarget

    /**
     * 没有可跑的包。`hasPackageJson` 区分两种现场，只用于文案：
     * - `true`：实例里有 `package.json` 但缺 `lib/bin.js`（半棵树）⇒ 让用户**重装**；
     * - `false`：实例和预装都没有包 ⇒ 让用户先**安装**。
     * 两条文案在 [DshRuntime.startLocked] 里分别对应 `dsh_reason_missing_entry` / `dsh_reason_not_installed`。
     */
    data class MissingEntry(val hasPackageJson: Boolean) : LaunchTarget

    /**
     * 磁盘**读不出来**（`package.json` 或 `lib/bin.js` 的 stat 能成功，但读内容抛异常：
     * EIO / 权限 / SELinux 一时挡住）。
     *
     * ★ R2b-A1-1（fix12 收尾）：这一态必须与 [MissingEntry] 分开，因为 `planRepair` 对同一个
     *   输入走的是 `keepAsIs`（**保留原状态、不改写 dshVersion**），如果启动口把它当"半棵树 ⇒
     *   请重装"，就出现"卡片写着就绪、点启动却让人重下 500MB"——把一个**可恢复的读故障**
     *   变成了破坏性建议。所以这里单独给文案（[R.string.dsh_reason_disk_unreadable]），
     *   只让用户稍后重试 / 查日志。
     *
     * `preinstalled = true` 表示预装那份也读不出来（通常是 rootfs 正在被替换），文案里可区分。
     */
    data class Unreadable(val preinstalled: Boolean) : LaunchTarget
}

/**
 * ★ fix12（C-08）：[LaunchTarget] 的判定内核（纯函数，JVM 可测）。
 *
 * 判定顺序与 `DshInstances.planRepair` 的修复判据**严格同口径**（同一台实例在卡片与启动口
 * 给出同一结论）：
 * 1. 实例自己的 `package.json` 在 ⇒ 只看实例这棵树：`lib/bin.js` 在 = [LaunchTarget.Instance]，
 *    不在 = [LaunchTarget.MissingEntry] —— **不回退预装**（半棵树时跑预装，就是"卡片说未安装、
 *    点启动却跑起来"的那个谎；与 `effectiveDshDir` 的回退判据刻意不同）。
 * 2. 实例没有自己的包、预装的 `package.json` + `lib/bin.js` 都在 ⇒ 比对版本：请求的版本
 *    （非空、非 `"latest"`）与预装不符 = [LaunchTarget.Degraded]，否则 [LaunchTarget.Preinstalled]。
 * 3. 其余 = [LaunchTarget.MissingEntry]。
 *
 * @param readVersion 读一份 `package.json` 的 `version`。做成**必填参数**而不是直接调
 *   `DshInstaller.readVersionFromPackageJson`：生产调用点传它即可，JVM 单测则能注入假实现
 *   （`DshInstaller` 本身要 Context，测试里构造不出来）。该 lambda **不应抛异常**
 *   （`DshInstaller.readVersionFromPackageJson` 内部已 runCatching，读不到返回 null）。
 */
fun resolveLaunchTarget(
    instanceDir: File,
    rootfsDir: File,
    dshVersion: String?,
    readVersion: (File) -> String?,
    /** 读 package.json 的那一刻是否**抛了异常**（区别于"文件不存在/内容坏"）。默认恒 false，便于旧测试。 */
    readFailed: (File) -> Boolean = { false }
): LaunchTarget {
    // 实例自己那份包的相对路径：与 [DshPaths.ownDshDir] 共用同一个常量（fix12 / C6），
    // 不再各自抄字面量。不收 instanceId 而收目录，是为了让本函数不依赖 DshPaths 的静态状态、
    // 能直接用假目录单测。
    val own = File(instanceDir, DshPaths.INSTANCE_DSH_REL)
    val ownPkg = File(own, "package.json")
    if (ownPkg.isFile) {
        if (File(own, "lib/bin.js").isFile) {
            val ownVersion = readVersion(ownPkg)
            // ★ V1 §5-8（fix12 收尾）：有 bin.js 但 package.json **读不出版本**（坏 JSON / 缺 version）
            //   不能当"完整包"放行 —— planRepair 的 complete = version != null && hasBinJs 在这里
            //   为 false，判 NOT_INSTALLED；若这里仍返回 Instance(null)，就会出现"卡片说未安装、
            //   点启动却 fork 出去"，脚本读不出 DSH_VER 但照样跑 bin.js（dsh 自己可能崩在内部）。
            //   两个判据必须逐序等价，所以这里落 MissingEntry(true)（"像装了但树不可信"）。
            //
            // ★ R2b-A1-1：但"读不出来"与"内容坏"要分开 —— 前者 planRepair 走 keepAsIs
            //   （保留原状态），这里就不能建议重装（那是把一个可恢复的读故障变成破坏性动作）。
            if (ownVersion == null) {
                return if (readFailed(ownPkg)) LaunchTarget.Unreadable(preinstalled = false)
                else LaunchTarget.MissingEntry(hasPackageJson = true)
            }
            return LaunchTarget.Instance(ownVersion)
        }
        // 半棵树（有 package.json、缺入口）：判定"没装完"，且**绝不**拿预装兜底。
        return LaunchTarget.MissingEntry(hasPackageJson = true)
    }

    val pre = File(rootfsDir, DshPaths.PREINSTALLED_DSH_REL)
    val prePkg = File(pre, "package.json")
    if (prePkg.isFile && File(pre, "lib/bin.js").isFile) {
        val actual = readVersion(prePkg)
        // ★ R2b-A1-2（fix12 收尾）：直接复用 [versionMatches]，不再自己写一遍 takeIf。
        //   原来 `dshVersion = ""`（脏值）在这里被 isNotBlank 判成"没有请求 ⇒ 放行预装"，
        //   而 planRepair 的 versionMatches 判"不匹配 ⇒ NOT_INSTALLED" —— 卡片说未安装、
        //   点启动却跑起来。两套判据共用同一个函数才谈得上"逐序等价"。
        if (actual == null && readFailed(prePkg)) {
            return LaunchTarget.Unreadable(preinstalled = true)
        }
        if (!versionMatches(dshVersion, actual)) {
            return LaunchTarget.Degraded(
                expected = dshVersion,
                actual = actual,
                preinstalled = true
            )
        }
        return LaunchTarget.Preinstalled(actual)
    }
    return LaunchTarget.MissingEntry(hasPackageJson = false)
}

/**
 * ★ R26（DESIGN-A §1.4）：`startLocked` 在 fork 之前的**决策结论**（三态）。
 *
 * 为什么需要第三态 [Reuse]：现码里"同一实例已在跑"走的是 `return StartOutcome.Started`，
 * 与"拒绝启动"和"应当 fork"都不同 —— 被压进 Refuse 会让界面报一个假失败，被压进 Proceed
 * 会让 seccomp 重试**永不 fork**（H1 的成因，见 [canJoinExistingRun]）。所以必须显式建模。
 */
sealed interface StartDecision {
    /** 前置判据都满足，应当 fork（调用点必须保证 Proceed 之后紧接着就是 fork） */
    object Proceed : StartDecision

    /** 拒绝启动，[reason] 是已本地化的原因（调用方直接落 `StartOutcome.NotReady`） */
    data class Refuse(val reason: String) : StartDecision

    /** 同一实例已有**活**的 run —— 复用现有进程，**不 fork**（调用方直接落 `StartOutcome.Started`） */
    object Reuse : StartDecision
}

/**
 * ★ R26：`startLocked` 的**决策内核**（纯函数，JVM 可直测）。
 *
 * 输入全部是"已经读好的事实"，不碰文件系统、不碰 `Context`：
 * - `preflightOk` / `preflightReason`：[ProotCommand.preflight] 的结果（文案的最后一层兜底在这里）；
 * - `launchTarget`：[resolveLaunchTarget] 的结果（它本身也是纯函数）；
 * - `canJoinExisting`：[DshRuntime.canJoinExistingRunLocked] 的结果。
 *
 * 判定顺序与 `startLocked` 原 if 链**逐条一致**（顺序本身是契约）：
 * 1. 预检不过 ⇒ Refuse —— 预检是"环境根本不能用"，任何"复用在跑实例"的判据都排它之后；
 * 2. [LaunchTarget.Degraded] ⇒ Refuse（会跑预装、但不是用户要的版本：不许静默降级）；
 * 3. [LaunchTarget.MissingEntry] ⇒ Refuse（有 package.json = 半棵树，提示重装；没有 = 未安装）；
 * 4. [LaunchTarget.Unreadable] ⇒ Refuse（磁盘一时读不出来，不能建议重装，见该类型的说明）；
 * 5. `canJoinExisting` ⇒ Reuse（**必须**在三段 launchTarget 判定之后，与现码 `:661` 一致：
 *    包都不可信时不许因为"状态看着在跑"就放行）；
 * 6. 其余 ⇒ Proceed。
 *
 * 第 5 步的位置是 R26 的重点：seccomp 重试时状态仍是 `Starting` 但句柄已被清，
 * [DshRuntime.canJoinExistingRunLocked] 因此为 false ⇒ Proceed ⇒ 真的 fork。
 */
fun planStart(
    preflightOk: Boolean,
    preflightReason: String?,
    launchTarget: LaunchTarget,
    canJoinExisting: Boolean,
    degradedMessage: String,
    missingEntryMessage: String,
    notInstalledMessage: String,
    unreadableMessage: String
): StartDecision {
    // 兜底与旧实现同款：pre.reason 为空时才用这句。本地化文案（dsh_start_not_ready）由调用点先取，
    // 这里保留纯函数能给出的最后一层字面量 —— 否则纯函数就得依赖 Context。
    if (!preflightOk) return StartDecision.Refuse(preflightReason ?: "runtime not ready")
    return when {
        launchTarget is LaunchTarget.Degraded -> StartDecision.Refuse(degradedMessage)
        // 两条文案的区分保留（现场不同、用户该做的事也不同）：
        // 有 package.json = 装了一半（重装），连 package.json 都没有 = 没装（先装）。
        launchTarget is LaunchTarget.MissingEntry ->
            StartDecision.Refuse(
                if (launchTarget.hasPackageJson) missingEntryMessage else notInstalledMessage
            )
        launchTarget is LaunchTarget.Unreadable -> StartDecision.Refuse(unreadableMessage)
        canJoinExisting -> StartDecision.Reuse
        else -> StartDecision.Proceed
    }
}

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

    /**
     * 停止时，等 proot 响应 SIGQUIT / 确认它已经退出的宽限时间（毫秒）。
     *
     * ★ R16/fix4（BL-138）：两段式 —— 先总时长 [PROOT_EXIT_GRACE_MS]，仍在则以
     * [PROOT_EXIT_GRACE_EXTENDED_MS] 为增量再等（总上限 = 两段之和）。依据（bebug (2)，
     * `reports/round16/V4-orphan-proot.md`）：SIGQUIT → proot 的 `kill_all_tracees()` 要先把
     * node 的**整棵子进程树**逐个 SIGKILL 再自己退出，收整棵树可能 >1s —— 旧实现单段 1s 到期
     * 即打"停止后 proot 仍存活"警告（01:49:21 pid=13662 即该窗口产物），把正常收尾误报成异常。
     * 两段式让"1 秒外退出"不再触发警告；真残留（两段全过仍活）照样被抓到。
     */
    private const val PROOT_EXIT_GRACE_MS = 1_000L

    /** 停止校验的第二段宽限（毫秒）：第一段未退出时的增量等待，见 [PROOT_EXIT_GRACE_MS] */
    private const val PROOT_EXIT_GRACE_EXTENDED_MS = 4_000L

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

    /**
     * 启动串行化锁（K02）。[start] 是 `suspend`，不能用 `@Synchronized`（会把宿主线程锁跨越挂起点）。
     * 用协程 [Mutex] 把"停旧实例 → 等它退干净 → 清孤儿 → 起新 proot"整段串起来：
     * 两次并发 [start] 不会交错，新旧 proot 也就不会同时抢同一个端口。它只保护 [start]，不碰
     * `@Synchronized` 的那把对象锁（[startLocked]/[stop] 用的仍是对象监视器），所以不会与停止协程
     * 里的 `synchronized(this)` 互相死锁。
     */
    private val startMutex = Mutex()

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
    private fun ownerOf(st: State): String? = ownerOfState(st)

    /** 仅当"当前状态仍属于该实例"时才写入新状态，避免覆盖掉已经启动的另一个实例 */
    private fun setStateIfOwned(instanceId: String, next: () -> State) {
        synchronized(this) {
            if (ownerOf(_state.value) == instanceId) _state.value = next()
        }
    }

    /**
     * 进程句柄抽象：真实 Process 或"按 pid 管"的孤儿进程。
     *
     * ★ R24 / A8：从 `private` 提到 `internal` —— 测试需要能声明这个类型才能注入 fake handle
     *   （E5 实测：`private interface` 在 JVM 侧是 package-private，测试类无法引用，
     *   于是"可测的 fork 点"这段 seam 根本写不出来）。只改可见性，方法签名一字未动。
     */
    internal interface Handle {
        val pid: Long
        fun isAlive(): Boolean

        /**
         * ★ Round24 N-3：活着 **且** 身份仍是本实例的 proot —— 只给"能否复用现有 run"
         * （[canJoinExistingRunLocked]）这一条判据用。
         *
         * 为什么单开一个判据，而不是把身份校验塞进 [isAlive]：
         * 1) [isAlive] 还是 terminate 的第一步（`if (!isAlive()) return`）。若那里也要求 cmdline 可读，
         *    一旦设备/SELinux 一时读不到 `/proc/<pid>/cmdline`，"停止"就退化成什么都不做 —— 真孤儿
         *    比"误判可复用"更糟（后者最坏只是多 fork 一次，有端口避让兜底）。停止侧的身份校验
         *    本来就另有 canSendQuitSignal 把门，不靠这个判据。
         * 2) 两个场景的失败方向相反（PLAN D-1b）：复用不确定时应判"死"（宁可多 fork），
         *    停止不确定时应"不要动"（宁可留一行诊断）。
         *
         * 默认 = [isAlive]：[ProcessHandle] 背后是真实 `Process`，pid 不会被系统复用。
         */
        fun isAliveAndOwned(): Boolean = isAlive()

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
     * @param logInstanceId 日志归属（这个 SIGQUIT 是为哪个实例发的）；null = 全局流。
     *                     失败日志不该丢，但归属对了排障时才不用翻全局流找。
     * @return true 表示信号已发出（pid 可用）；false 表示拿不到 pid，只能靠按 pid 终止 node
     */
    private fun signalProotQuit(pid: Long, logInstanceId: String? = null): Boolean {
        if (pid <= 0) return false
        // BL-046：原实现整段静默 runCatching —— 发信号失败（权限/SELinux）会让停止悄悄退化为
        // TERM/KILL 兜底，日志里没有任何线索。现在失败留一行英文诊断（R-LOG），控制流不变。
        return runCatching {
            android.os.Process.sendSignal(pid.toInt(), 3) // SIGQUIT
            true
        }.getOrElse { e ->
            val line = "[runtime] sendSignal(SIGQUIT) failed for pid=$pid: ${e.message}; falling back to TERM/KILL"
            if (logInstanceId == null) DshLogBus.append(line) else DshLogBus.appendFor(logInstanceId, line)
            false
        }
    }

    private class ProcessHandle(
        private val handle: ProotRunner.Handle,
        /** BL-040：本句柄所属实例 —— SIGQUIT 前的 cmdline 身份复核要拿它比对 */
        private val instanceId: String
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
            val p = pid
            // ★ BL-040：发 SIGQUIT 前先复核目标身份。pid 不缓存失败值（见 [ProotRunner.Handle.pid]），
            //   在"pid 解析失败"的设备上 terminate 现场会重新扫 /proc 差集——差集里"新出现的子进程"
            //   未必是本实例的 proot（可能是自检 runOnce、或另一实例的 proot）。SIGQUIT 对 proot
            //   意味着"带走全部 tracee 再退出"（kill_all_tracees），杀错就是整条任务连根消失。
            //   身份不符（或 cmdline 读不到）时不发 SIGQUIT，落原来的 TERM/KILL 兜底路径并记一行。
            //   pid<=0（拿不到 pid）走原有路径，不新增日志（真机常态，避免每次停止都刷一行）。
            if (p > 0 && !canSendQuitSignal(p, ProcessUtil.cmdline(p).orEmpty(), instanceId)) {
                DshLogBus.appendFor(
                    instanceId,
                    "[runtime] SIGQUIT skipped for pid=$p: could not verify it is this instance's proot " +
                        "(cmdline missing or mismatched); falling back to TERM/KILL"
                )
                handle.terminate(graceMillis)
                return@withContext
            }
            if (signalProotQuit(p, instanceId)) {
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
     * [instanceId] 参与进程操作前的身份校验与日志归属。
     *
     * ★ R14-V2A F3：SIGQUIT 前的 cmdline 复核与 [ProcessHandle] 口径**对称**。PidHandle 的 pid
     *   来自认领时的 pid 文件，虽然认领当场做过 cmdline 校验，但"认领 → 停止"之间隔着任意长的
     *   时间——pid 可能已被系统复用给别的 proot。SIGQUIT 对 proot 意味着"带走全部 tracee 再退出"
     *   （kill_all_tracees），杀错就是整条任务连根消失，发信号前必须复核身份。cmdline 读不到
     *   同样不发（不确定即不发）；不匹配则落 TERM/KILL 兜底（那条路径不会误伤别的进程）。
     */
    private class PidHandle(override val pid: Long, private val instanceId: String) : Handle {
        override fun isAlive(): Boolean = ProcessUtil.isAlive(pid)

        /**
         * ★ Round24 N-3：pid 活 **且** cmdline 仍含 `proot` + 本实例 instanceId。
         *
         * 为什么必须查身份：A1a 把 [canJoinExistingRunLocked] 的"进程还活着"判据抬成了
         * "能否复用现有 run"的唯一 gates。认领态（[DshRuntime.adoptOrphan] 造的 [PidHandle]）
         * 的 pid 来自 pid 文件，而 pid 会被系统复用 —— 原 proot 已死、pid 被任何进程（甚至别的
         * 实例的 proot）用掉时，只看 `/proc/<pid>` 存在 ⇒ 每次点启动都命中"已在运行，复用现有进程"，
         * **永远不 fork**，要等 [DshRuntime.watchAdopted] 的 10s 巡检同时确认"pid 不在 且 端口关"
         * 才自愈；若端口恰被另一个 dsh 占着，两个条件永不成立 ⇒ 永久卡住（E4-N-3）。
         *
         * 口径复用 [canSendQuitSignal]（顶层纯函数，pid>0 ∧ cmdline 含 proot ∧ 含 instanceId）。
         * 它对"cmdline 读不到"取**严**（判 false）：这里不确定 = 不复用 = 走 fork，
         * 与 PLAN D-1b 的默认方向一致（宁可多 fork 一次，也不要界面空转 180 秒）。
         */
        override fun isAliveAndOwned(): Boolean =
            isAlive() && canSendQuitSignal(pid, ProcessUtil.cmdline(pid).orEmpty(), instanceId)

        override suspend fun terminate(graceMillis: Long) = withContext(Dispatchers.IO) {
            if (!isAlive()) return@withContext
            if (pid > 0) {
                // R14-V2A F3：身份复核（与 ProcessHandle 同款判据，public 纯函数，见 canSendQuitSignal）
                if (canSendQuitSignal(pid, ProcessUtil.cmdline(pid).orEmpty(), instanceId)) {
                    // BL-046：与 signalProotQuit 同款 —— 发信号失败不许静默（否则停止悄悄退化
                    // 为 TERM/KILL 兜底却查无实据）。控制流不变，仅补英文诊断。
                    runCatching { android.os.Process.sendSignal(pid.toInt(), 3) } // SIGQUIT
                        .onFailure { e ->
                            DshLogBus.appendFor(
                                instanceId,
                                "[runtime] sendSignal(SIGQUIT) failed for pid=$pid (PidHandle): ${e.message}; " +
                                    "falling back to TERM/KILL"
                            )
                        }
                } else {
                    DshLogBus.appendFor(
                        instanceId,
                        "[runtime] SIGQUIT skipped for pid=$pid (PidHandle): cmdline missing or mismatched; " +
                            "falling back to TERM/KILL"
                    )
                }
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

    /**
     * BL-038 世代号（run generation）：每**真正启动一个新进程**（[startLocked] 走到 fork 那一步）递增一。
     *
     * 为什么需要它："停后秒启"（同实例）时旧进程的收尾是异步的 —— 旧 run 通过
     * [ProotRunner.start] 注册的 onLine/onExit 回调、以及它留下的看门狗，都可能在**新 run 已经
     * 接管现场之后**才触发。没有世代隔离时，旧 onExit 会把新 run 的 `handle`/`nodePid` 清掉
     * （进程从此停不掉的"半孤儿"），退出码 137 这类值还会去烧新 run 的 seccomp 兜底机会。
     * 回调闭包捕获自己这一代的号（构造 [ProotRunner.start] 时传入），触发时与本字段比对：
     * 不符 = 旧世代的迟到事件，只允许做无副作用清理（pid 文件 compare-and-delete）并打英文日志。
     *
     * 注意两点：
     * 1. 只在**真正 fork 新进程**的那一步递增 —— [startLocked] 的"复用早退"（同实例已在跑）
     *    绝不能碰它，否则会把还活着的当前 run 的回调一次性打成 stale。
     * 2. 只在 startLocked（持对象监视器）里自增；回调侧按 @Volatile 读取，够用。
     */
    @Volatile
    private var runGen: Long = 0

    /**
     * BL-038：上一次"stale 世代输出"提示打过哪个 gen。旧进程退出前可能刷多行输出，
     * 逐行都打"已忽略"会把实例日志刷满 —— 每个 stale 世代只提示一次（退出回调那次另有日志）。
     */
    private val staleGenNoted = java.util.concurrent.atomic.AtomicLong(-1L)

    // --- 启动 ---------------------------------------------------------------

    fun runningInstanceId(): String? = (_state.value as? State.Running)?.instanceId
        ?: (_state.value as? State.Starting)?.instanceId

    /**
     * ★ BL-036：含 Stopping 的"运行时通道占用者"——当前状态是 Starting/Running/Stopping 时返回其
     * instanceId，否则（Idle/Failed/Exited）返回 null。
     *
     * 与 [runningInstanceId] 的分工（两者都保留，别互相替换）：
     * - [runningInstanceId]（不含 Stopping）回答"现在还能对着它做停止/打开界面吗"——Stopping 期再发
     *   停止是空转（[stop] 里已按 BL-036a 显式 no-op），所以它不算"在跑"。
     * - 本函数回答"运行时通道是否仍被这个实例占着、进程是否还没收尾完"——删除实例、等进程退干净
     *   这类场景必须用它：Stopping 窗口里用 [runningInstanceId] 判"没在跑"，就会把进程还活着的
     *   实例目录直接删掉（BL-036 的后半）。
     */
    fun activeInstanceId(): String? = activeOwnerOf(_state.value)

    fun currentState(): State = _state.value

    /**
     * 启动某实例的 dsh web。已有运行实例时先停掉（单实例策略：避免多个 300MB 进程压垮手机）。
     *
     * ## K02 / BL-007：先停到位、再起；阻塞清理移出同步块
     * 本方法是 `suspend`，用 [startMutex] 把整条启动路径串行化（两处入口几乎同时进来时，第二个会
     * 排队，而不是两边都看到"没实例在跑"从而同时起两个 300MB 的 node）。关键改动：
     * - **先停到位再起**：旧实现对"别的实例在跑"走的是**异步** `stop(...)`，不等旧进程真的退出就
     *   去 fork 新 proot —— 新旧两个 proot 会短暂并存、抢同一个端口（EADDRINUSE）。现在改成在
     *   进入 [startLocked] 之前先 [stopAndWait]，确保旧实例真正离开 Stopping 才继续。
     * - **阻塞清理移出同步块**：清孤儿（[ProcessUtil.killStale]）与按 pid 收掉上一轮 node
     *   （[terminateNodeProcess]，内部可能 sleep 到秒级）都放在这里（**不持对象监视器**）做，
     *   不再卡住 `@Synchronized` 的 [startLocked]/[stopIf]/[stop]。真正需要原子的"查状态 → fork →
     *   落 Starting/pid"仍在 [startLocked] 的监视器里。
     *
     * 为什么 stopAndWait 放在监视器**之外**：停止协程收尾时自己要 `synchronized(this)`，若在持锁时
     * 阻塞等它，就会死锁。[startMutex] 是协程锁、不占对象监视器，二者不冲突。
     */
    suspend fun start(context: Context, instance: DshInstance): StartOutcome = startMutex.withLock {
        // ★ BL-036/038：判据用 activeInstanceId()（含 Stopping）。旧实现只看 runningInstanceId()，
        //   于是"停后秒启"（同实例，上一轮 stop 的收尾协程还在跑 TERM→KILL 链、_state 仍是 Stopping）
        //   时这里看到的 current==null —— 不等待、直接 killStale + startLocked，旧 proot 还没死透
        //   就 fork 新 proot：两个 proot 短暂并存（抢端口），旧 onExit 迟到还会清掉新 run 的句柄。
        //   现在：Stopping(同 id) 同样走 stopAndWait 等到位（stop() 对 Stopping 已按 BL-036a no-op，
        //   不会重复发停止；旧进程的退出由 in-flight 收尾协程负责推进状态）。
        val current = activeInstanceId()
        if (current == instance.id) {
            val st = _state.value
            if (st is State.Stopping) {
                // 同实例还在停（"停后秒启"）：等到位再起 —— 旧实现此时 current==null（只看
                // runningInstanceId），直接走下面的 killStale + fork：旧 proot 被 SIGKILL（跳过
                // SIGQUIT 的 kill_all_tracees）、rootfs 内 node 变孤儿占着端口，且旧 onExit 迟到
                // 还会清掉新 run 的句柄（BL-038）。stopAndWait 到位后旧进程已收尾，无需再清理。
                if (stopAndWait(reason = stopReasonDefault())) {
                    return@withLock startLocked(context, instance, noSeccomp = false)
                }
                // 超时（★ Round24 N-1：13s 上限，见 stopAndWait 的 KDoc —— 它比 stop() 最坏
                // 耗时 ≈12s 略高；旧注释写的 8s 与四段常量矛盾，是"停后秒启走兜底分支"的成因）：
                // 旧进程可能还活着。保持与改动前同款的兜底清理再启动（killStale / terminateNodeProcess
                // 都带 pid 文件 + cmdline 身份校验，是窄口径清理，不会误伤别的进程）。
                DshLogBus.appendFor(
                    instance.id,
                    "[runtime] previous stop of ${instance.name} did not finish within grace; " +
                        "running stale cleanup before starting anyway"
                )
            } else {
                // 同实例已在跑（Running/Starting）：交给 startLocked 的"复用"快路径；
                // 绝不能 killStale（那会杀掉活着的它）。
                return@withLock startLocked(context, instance, noSeccomp = false)
            }
        }
        // K02：别的实例在跑——先停掉并**等它真的退出**（离开 Stopping），再起新 proot。
        if (current != null && current != instance.id) {
            if (!stopAndWait(stopReasonDefault())) {
                // 超时也继续（已给足宽限期）；留一行英文诊断（R-LOG）。
                DshLogBus.appendFor(
                    instance.id,
                    "[runtime] previous instance did not stop within grace; starting ${instance.name} anyway"
                )
            }
        }
        // BL-007：把阻塞的孤儿清理放在对象监视器**之外**（这里在 IO 线程、未持 @Synchronized 锁）。
        // 先清干净再启动的语义不变：killStale 收上一轮残留 proot、terminateNodeProcess 按 pid 收上一轮
        // node（proot 被 SIGKILL 时来不及带走它，会占着端口）。
        //
        // ★ BL-047 判定（R15 批 B；读全调用链后**有意保留在 startMutex 之内**，只补此注释）：
        //   R12-A1-5 建议把本段挪到 withLock 之外"以竞态复检换延迟"，评估后否决，原因是挪出会引入
        //   一个**破坏级**窗口而无互补手段可封：
        //   1) 本段按 instance.id 操作（killStale 读 dsh.pid、terminateNodeProcess 读 dsh-node.pid），
        //      而并发 start(同实例) 的窗口真实存在（DshLauncher.startInstance 被实例页/详情页/右面板/
        //      WebView 重试四处复用，UI 无启动防重）。
        //   2) 挪出后，B 的锁外清理可与 A 的持锁 fork 并发：A fork 后立刻 writePidFile、脚本写
        //      dsh-node.pid；B 的 killStale 若在新文件落盘后读文件，cmdline 身份校验（proot +
        //      instanceId）对**新生进程同样成立**（它本来就是本实例的 proot），B 会 SIGKILL 掉 A 刚
        //      起来的进程；terminateNodeProcess 对新生 node 同理。杀完 B 进锁、复检见"同实例在跑"
        //      还会走复用分支 —— 复检拦得住状态覆写，拦不住**已发出的信号**。
        //   3) 收益面很小：清理只针对"上次收尾失败留下的残留"（正常路径下两个 pid 文件已删，本段
        //      是两次文件读的 no-op）；"A 运行中 → 启动 B" 的 2s 级等待来自上面 stopAndWait 的正确性
        //      等待（R12-A1-5 原文也认定可接受），不来自本段。
        //   故：宁可让残留边角启动多等 ≤2s（有界、且端口避让 `resolveLaunchPort` 兜底），也不换
        //   一个能杀掉新生进程的窗口。
        withContext(Dispatchers.IO) {
            ProcessUtil.killStale(instance.id)
            terminateNodeProcess(instance.id, 0)
        }
        startLocked(context, instance, noSeccomp = false)
    }

    /**
     * ★ Round24 A1a：能否复用"已经在跑的这一次"（H1 / BL-012 修复）。
     *
     * **为什么不是 [runningInstanceId]**：它只看状态，**不看进程是否还活着**。seccomp 重试
     * （[retryIfSeccompLooksGuilty]）在 [onProcessExit] 已清 `handle` 之后调进来，状态仍是
     * `Starting(A)` ⇒ 旧判据打"已在运行，复用现有进程"直接返回 ⇒ 新 proot 从未 fork
     * ⇒ 界面空转 180 秒报超时（E4 已用独立模型在 fix10 上跑红复现）。
     *
     * 三元组：状态属于本实例 ∧ 状态是 `Starting`/`Running` ∧ 有**活**句柄。
     * - 不带 gen 校验：（E1-05）`Handle` 接口不暴露 owner，A 版无法校验归属；但
     *   [onProcessExit] 先清 `handle`、后进重试 ⇒ 重试路径必然读到 null。
     * - isAlive 的失败方向：取"偏死"（不确定 = 不复用 = 走 fork）—— 复用错了的后果是界面空转
     *   180 秒 + 孤儿；fork 错了只是短暂两个 proot，有端口避让（`resolveLaunchPort`）和
     *   SIGQUIT 清理兜底，可恢复性更好（PLAN D-1b）。
     *   ⚠️ 这与 terminate 里的用法方向相反（那里 `!isAlive()` 早退意味着"停止什么都不做"），
     *   所以身份校验另开了 [Handle.isAliveAndOwned]，没有塞进 [Handle.isAlive]（见那里的注释）。
     *
     * 判据本体在顶层纯函数 [canJoinExistingRun] 里（JVM 单测直接钉，不依赖 android）。
     */
    @Synchronized
    private fun canJoinExistingRunLocked(instanceId: String): Boolean {
        val st = _state.value
        // 先做两条零代价判据（归属/状态）：[Handle.isAliveAndOwned] 对 PidHandle 要读
        // /proc/<pid>/cmdline，而本函数在对象监视器里被调用 —— 不该为"状态本来就不匹配"的
        // 绝大多数情形去读文件。判据本体仍是顶层纯函数（这里只是它的短路形式）。
        if (!runStateAllowsJoin(instanceId, st)) return false
        val h = handle ?: return false
        // runCatching 默认 false：判活本身抛异常（SELinux 挡 /proc 等）时按"死"处理 ——
        // 宁可多 fork 一次，也不要把状态机永远钉在"复用"上（PLAN D-1b 的默认方向）。
        return runCatching { h.isAliveAndOwned() }.getOrDefault(false)
    }

    /**
     * [start] 的实现体。[noSeccomp] = true 时额外注入 `PROOT_NO_SECCOMP=1`
     * （部分设备内核对 proot 的 ptrace/seccomp 加速不兼容，导致 rootfs 内的进程一启动就被杀）。
     * 同一次"用户主动启动"里最多自动兜底一次，见 [seccompFallbackUsed]。
     */
    @Synchronized
    private fun startLocked(context: Context, instance: DshInstance, noSeccomp: Boolean): StartOutcome {
        appContext = context.applicationContext
        nameOf[instance.id] = instance.name
        // ★ Round24 A3（含 E4-N-4 的归属收紧）：只清**本实例**的"主动停止"标记（判据见纯函数
        //   [shouldClearStopRequested]）。旧实现无条件 `stopRequestedFor = null`："停 A → 立刻起 B"
        //   时会把 A 的标记偷走，于是 A 的进程随后退出（code=143）被当成"意外退出"上报
        //   （错误 Toast + 任务区 DONE）。反过来，本实例自己的旧标记必须清掉：不清的话，
        //   seccomp 重试/停后秒启起来的新 run 若是自己崩掉（而不是被停），onProcessExit 会读到
        //   一个过期的"主动停止"标记，把崩溃静默成"已停止"（E4 A3 CONFIRMED）。
        if (shouldClearStopRequested(stopRequestedFor, instance.id)) stopRequestedFor = null
        adoptWatcher?.cancel()  // 新一轮启动接管：旧的"认领巡检"立即失效
        if (!noSeccomp) seccompFallbackUsed.remove(instance.id) // 每次用户主动启动都重新给一次兜底机会
        // 新生命周期：清掉上一次记下的 node pid（那是上一个实例的，留着只会误导停止逻辑）
        nodePid = 0

        // 预检失败**不在这里早退**：它的结论要和 launchTarget 一起进 [planStart] 才能保证
        // "判定顺序只有一份"。顺序上它仍是第一条（见 planStart 的文档），这里只取事实。
        val pre = ProotCommand.preflight(
            context, DshPaths.ROOTFS_DIR,
            "${ProotCommand.GUEST_ROOT}/scripts/start-dsh.sh"
        )

        // 真正校验\"装好了\"：状态字段可能是过期的，以磁盘为准。
        // ★ fix12（C-08）：判据从"两次 isFile"换成 [resolveLaunchTarget] 的**单一纯函数** ——
        //   旧实现只看 `package.json` + `lib/bin.js` 在不在于 effectiveDshDir（会回退预装），
        //   于是"实例里没有自己的包、预装有"时静默跑起预装，用户要的版本与实际跑的版本可以
        //   完全不同而宿主零察觉。新判据与卡片（planRepair）同口径，且把结论下发脚本。
        val launchTarget = resolveLaunchTarget(
            DshPaths.instanceDir(instance.id),
            File(DshPaths.ROOTFS_DIR),
            instance.dshVersion,
            { f -> DshInstaller.readVersionFromPackageJson(f) },
            // ★ R2b-A1-1：把"读一键抛异常"这条**独立事实**也传进去，与 planRepair 的 readError 同源。
            //   只靠 readVersion 返回 null 分不清"文件不存在/坏 JSON"（该建议重装）与
            //   "这次读不出来"（该让用户稍后重试）—— 两者混为一谈会把可恢复故障变成重下 500MB。
            { f -> f.isFile && runCatching { f.readText() }.isFailure }
        )
        // 目标类型同时决定两件事：能不能启动、以及 fork 时下发给脚本的 DSH_LAUNCH_TARGET。
        // ★ R26：Degraded / MissingEntry / Unreadable 不再在这里 return —— 它们的结论统一由
        //   [planStart] 给（判定顺序只有一份）。这里只留 fork 能走到的两支；其余给空串占位
        //   （不可达：Refuse 分支已经在用之前返回）。
        val targetKind = when (launchTarget) {
            is LaunchTarget.Instance -> "instance"
            is LaunchTarget.Preinstalled -> "preinstalled"
            is LaunchTarget.Degraded -> ""
            is LaunchTarget.MissingEntry -> ""
            is LaunchTarget.Unreadable -> ""
        }
        // 拒绝文案也在这里先算好：planStart 是纯函数，不能自己去取 Context。
        // Degraded 要带 expected/actual 两个参数（会跑预装、但预装版本不是用户要的那个：
        // **拒绝启动并说清两个版本**；旧行为是放行后由脚本静默 fallback，用户只看到"起来了"）。
        val degradedMessage = when (launchTarget) {
            is LaunchTarget.Degraded -> context.getString(
                R.string.dsh_start_degraded,
                launchTarget.expected ?: "?",
                launchTarget.actual ?: "?"
            )
            else -> ""
        }
        // 预检原因：保持旧实现的兜底链（pre.reason → 本地化 dsh_start_not_ready → planStart 里的
        // "runtime not ready" 字面量）。前两层依赖 Context，只能在这里取。
        val decision = planStart(
            preflightOk = pre.ok,
            preflightReason = pre.reason ?: appContext?.getString(R.string.dsh_start_not_ready),
            launchTarget = launchTarget,
            // 同一实例已经在跑：直接复用
            // ★ Round24 A1a：判据从 runningInstanceId() 换成三元组（见 [canJoinExistingRunLocked]）。
            //   旧判据只看状态不看进程死没死 —— 正是 H1（seccomp 重试永不 fork）的成因。
            canJoinExisting = canJoinExistingRunLocked(instance.id),
            degradedMessage = degradedMessage,
            // 没有可跑的包。两条文案的区分保留（现场不同、用户该做的事也不同）：
            // 有 package.json = 装了一半（重装），连 package.json 都没有 = 没装（先装）。
            missingEntryMessage = context.getString(R.string.dsh_reason_missing_entry),
            notInstalledMessage = context.getString(R.string.dsh_reason_not_installed),
            // ★ R2b-A1-1：磁盘一时读不出来。**不能**套用"请重装"——那把一个可恢复的读故障
            //   （EIO / SELinux / 文件正被替换）变成让用户重下 500MB。文案只说"稍后重试"。
            unreadableMessage = context.getString(R.string.dsh_reason_disk_unreadable)
        )
        when (decision) {
            is StartDecision.Refuse -> {
                // 旧实现里 Unreadable 分支有一行取证日志，保留在同一位置（只对这一态打）。
                if (launchTarget is LaunchTarget.Unreadable) {
                    DshLogBus.appendFor(
                        instance.id,
                        "[runtime] cannot read package.json (preinstalled=${launchTarget.preinstalled}); " +
                            "refusing to start without touching instance state"
                    )
                }
                // 兜底重试阶段连判据都过不去，说明这次重试也没戏：把状态落到 Failed，
                // 否则状态会永远停在 Starting（界面上是"启动中"转圈不动）。
                // ★ 两条拒绝来源（预检 / launchTarget）都要 failRetry —— 换到统一分支后不能少。
                if (noSeccomp) failRetry(instance, decision.reason)
                return StartOutcome.NotReady(decision.reason)
            }
            is StartDecision.Reuse -> {
                // 实例级：讲的是"这个实例自己已经在跑"，属于它本次生命周期的一部分
                DshLogBus.appendFor(instance.id, "[runtime] ${instance.name} 已在运行，复用现有进程")
                return StartOutcome.Started
            }
            is StartDecision.Proceed -> Unit   // ↓ 紧接着就是 fork（结构约束，见 DESIGN-A §1.4）
        }
        // 实例级：把"这次到底跑哪份包"钉在日志里。启动后从 `ps` 看不出走了哪支，只有这行能与
        // 脚本的 `[start-dsh] dsh src/ver` 两行对上 —— C-07 之所以长期隐形，就是因为没有这行。
        val targetVersion = when (launchTarget) {
            is LaunchTarget.Instance -> launchTarget.version
            is LaunchTarget.Preinstalled -> launchTarget.version
            // Degraded / MissingEntry / Unreadable 已由 [planStart] 拒绝（Refuse 分支已 return），
            // 这里是编译器要求的穷举补位。
            is LaunchTarget.Degraded -> null
            is LaunchTarget.MissingEntry -> null
            is LaunchTarget.Unreadable -> null
        }
        DshLogBus.appendFor(
            instance.id,
            "[runtime] launch target=$targetKind version=${targetVersion ?: "?"}"
        )
        // K02 / BL-007：停掉别的实例并等它退出、以及清掉上一轮孤儿/残留 node 这些**阻塞步骤**，
        // 已经在 [start] 里（对象监视器之外）做完了，这里不再做 —— 既不等旧进程、也不在持锁时 sleep。
        // 走到这里时按单实例策略应当已无别的实例在跑；仍残留的极端情况（stopAndWait 超时）下面的
        // `_state.value = Starting` 会把归属切到本实例，旧实例的迟到回调会被 ownerOf 校验挡住。

        DshPaths.instanceWorkspace(instance.id).mkdirs()
        DshPaths.instanceHome(instance.id).mkdirs()
        File(DshPaths.NPM_CACHE_DIR).mkdirs()
        File(DshPaths.TMP_DIR).mkdirs()

        // ★ BL-149：起进程前轮转清理 dsh-start-*.log（**明文 token** 启动日志，见
        //   [ProcessUtil.pruneStartLogs] 的完整语义）。放在这里的理由：
        //   1) 脚本只在正常退出路径自删该文件，App/进程被 SIGKILL 后会残留 —— 宿主侧必须有
        //      第二删除方，否则明文 token 无上限堆积；
        //   2) 轮转的"最新 keep 份永不动"天然保护**正在写**的日志：本行之后 fork 的新进程
        //      写出的日志必然是全场最新，下轮清理也只会留下它；
        //   3) 清理是锦上添花，绝不许把启动带崩 —— runCatching 全包，失败只记英文诊断。
        runCatching {
            val pruned = ProcessUtil.pruneStartLogs(File(DshPaths.TMP_DIR), DshPaths.instanceDir(instance.id))
            if (pruned > 0) {
                DshLogBus.append(
                    "[runtime] pruned $pruned stale dsh-start-*.log file(s) (token-bearing startup logs)"
                )
            }
        }.onFailure { e ->
            DshLogBus.append("[runtime] start-log pruning failed: ${e.message}; stale token logs will accumulate")
        }

        // ★ 端口预检（B10 的第二半："端口被占时不要直接失败"）：实例端口是上次成功后回写的
        // 固定值，一旦被占（旧 node 还没退干净 / 别的 App 占了），node 起来就报 EADDRINUSE 然后
        // 整个失败。这里探测一次，被占就本次改用自动端口（0），并明确告诉用户为什么。
        // 代价：使用者手工指定的端口在这种情况下会被换成系统分配的端口（日志与实例配置都会
        // 显示新端口）。换来的是"能起来"，而不是一句无信息的失败。脚本侧还有一道同样的预检，
        // 两道互相独立：即便这里的探测判定"空闲"，脚本启动瞬间端口仍可能被别人抢走。
        val launchPort = resolveLaunchPort(instance)

        val instancePathInRootfs = "${ProotCommand.GUEST_ROOT}/instances/${instance.id}"

        // ★ R28（锐评 C2-5）：用户自定义的「启动目录」若不存在，**在这里建出来**。
        //
        // dsh 的 picker 打开时会对 `homedir()` 做 `opendir`；目录不存在 ⇒
        // `DirectoryPickerError("directory-unreadable")` ⇒ 前端只显示一行 12px 的小字
        // （`DirectoryBrowser.module.css:279-288`），用户看到的还是"空框" ——
        // 也就是同一个坑的第三次。设置页没法做这个校验（那时拿不到 guest 视图），
        // 但启动时我们知道完整的映射关系，所以在这里补齐。
        //
        // 只对落在 `$GUEST_ROOT` 下的路径生效（那一段在宿主侧有真实对应）；
        // rootfs 内部的路径交给 rootfs 自己（我们不往解压出来的 rootfs 里写东西）。
        //
        // ★ R28 / C2-B-1：判定内核抽成纯函数 [planHomeDirCreation]（JVM 可测）。
        //   最要防的一条：**绝不在 `instances/` 下建新目录** ——
        //   `adoptOrphanInstanceDirs()` 会把"存在但不在清单里"的目录收编成幽灵实例
        //   （`DshInstances.rebuildMissingInstances:1421-1428`，每个冷启成功出口都跑）。
        //
        // ★ 这里算出的 `homeGuest` 在下面 fork 时**复用**（`homeRootfs = homeGuest`），
        //   不会再算一遍 —— 两次调用之间任何"就地修正"都会漂成"建了 A 目录、HOME 指 B"。
        val homeGuest = resolveHomeRootfs(instancePathInRootfs, instance.homeDir)
        when (val plan = planHomeDirCreation(
            homeGuest = homeGuest,
            hostRootDir = File(DshPaths.ROOT_DIR),
            guestRoot = ProotCommand.GUEST_ROOT,
            instancesHostDir = File(DshPaths.INSTANCES_DIR),
            exists = { it.isDirectory }
        )) {
            is HomeDirPlan.Create -> {
                // mkdirs() 失败时**返回 false 而不是抛异常**（C2-B-2），所以必须判返回值 ——
                // 原来写的是 `runCatching {…}.onFailure{}`，那行日志几乎永不执行。
                if (!runCatching { plan.dir.mkdirs() }.getOrDefault(false)) {
                    DshLogBus.appendFor(
                        instance.id,
                        "[runtime] cannot create custom HOME ${plan.dir.absolutePath}"
                    )
                } else {
                    // ★ R28 / C1-C5(b)：刚建出来的一定是**空目录**。
                    //
                    // dsh 的 picker 对空目录返回 200 + `entries: []`，前端**没有空态文案** ——
                    // 用户看到的是一个"能打开、但什么都没有"的框，和"加载失败"几乎无法区分，
                    // 而且**没有任何报错**（原来 `opendir` 失败至少还有一行红字）。
                    //
                    // 所以这里必须留一条能对上号的日志：用户/我排查时能立刻判断
                    // "是目录空"还是"是请求失败"。这是 typo 检测的唯一线索。
                    DshLogBus.appendFor(
                        instance.id,
                        "[runtime] created custom HOME ${plan.dir.absolutePath} (empty until you add " +
                            "files there; if this path looks like a typo, fix it in instance settings)"
                    )
                }
            }
            is HomeDirPlan.RefusePhantom -> {
                DshLogBus.appendFor(
                    instance.id,
                    "[runtime] custom HOME ${plan.dir.absolutePath} is under instances/ but does not " +
                        "exist; refusing to create it (it would be adopted as a phantom instance)"
                )
            }
            HomeDirPlan.AlreadyExists, HomeDirPlan.OutsideGuestRoot -> Unit
        }

        // ★ R28 / C3-D：**自动判据** —— 生效的 HOME 里必须有至少 1 个子目录，否则打 WARN。
        //
        // 为什么需要它（这个坑已经踩了三次）：
        // - `HOME=/root`：rootfs 的 /root 只有 `.profile`/`.bashrc`，零子目录；
        // - fix15 改成 `workspace`：那个目录是 `mkdirs()` 建的空壳，零子目录；
        // - fix18 的自定义目录不存在：建出来也是空目录。
        // 三次的症状**完全一样**：用户打开「选择工作区目录」看到一个空框，且**没有任何报错**
        // （dsh 的 picker 对空目录返回 200 + `entries: []`，前端无空态文案）。
        //
        // 三次都是靠人盯着截图猜出来的。而这是一个**构造性可判的事实**：
        // dsh 的 picker 只列目录（`directory-picker-browse/src/index.ts:259`），
        // 所以"HOME 里没有子目录" ⇔ "用户必然看到空框"。
        //
        // 一行统计 + 一行为 WARN，就能让这个问题在下一次自动暴露在日志里，
        // 而不是等用户截图。注意：**只告警、不改行为** —— 用户可能是故意指向一个空目录
        // （准备往里建东西），我们不能替他改。
        hostPathForGuestDir(homeGuest, File(DshPaths.ROOT_DIR), ProotCommand.GUEST_ROOT)?.let { dir ->
            if (dir.isDirectory) {
                val subdirs = runCatching {
                    dir.listFiles()?.count { it.isDirectory } ?: 0
                }.getOrDefault(0)
                if (subdirs == 0) {
                    DshLogBus.appendFor(
                        instance.id,
                        "[runtime] WARN home-has-no-subdir path=$homeGuest —— dsh 的目录选择器只列目录，" +
                            "空目录会让「选择工作区目录」显示成一个空框（无任何报错）。" +
                            "如果你看到对话框是空的，原因就在这里。"
                    )
                }
            }
        }

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
            "NODE_OPTIONS" to "--max-old-space-size=${nodeHeapMb(context)}",
            // ★ fix12（C-08）：把宿主算出的启动目标下发给脚本，脚本按它选 DSH_SRC ——
            //   彻底消除"宿主看 package.json、脚本看 lib/bin.js"的判据错位（E1 §4.2 第 3 行）。
            //   这里只可能是 instance / preinstalled：Degraded 与 MissingEntry 已在上面拒绝启动。
            "DSH_LAUNCH_TARGET" to targetKind
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
            bindCacheAsTmp = true,
            // ★ R27 / P0-A2：`HOME` 指向**实例目录**（`instances/<id>/`），不是 workspace。
            //
            // ## 演进过程（两次都踩了同一个坑：目录"存在但为空"）
            //
            // dsh 的目录选择器（`dsh-host-directory-picker-browse`）打开对话框时用 `homedir()`
            // 当初始目录，而 `homedir()` 在 POSIX 上就读 `$HOME`；`list()` **只返回目录**
            // （`dirent.isDirectory() || isSymbolicLink()`），空目录 ⇒ 空列表 ⇒ 用户看到空框。
            //
            // - 最初 `HOME=/root`：rootfs 的 `/root` 只有 `.profile`/`.bashrc`，**零子目录** ⇒ 空框。
            // - fix15 改成 workspace：目录**确实是空的**（我们只 `mkdirs()` 建了壳，从没往里放过东西）
            //   ⇒ 依然是空框。真机截图确认：标题显示成「主目录」，说明 HOME 生效了，但列表还是空。
            // - 现在指向**实例目录本身**：它含 `home/`（DSH_HOME：会话/配置/cookie）与
            //   `workspace/`（agent 工作目录）两个子目录**恒存在**，用户自己装过 dsh 的还有
            //   `node_modules/` ⇒ 列表非空，且 `workspace/` 就在第一屏。
            //   ⚠️ 预装命中的实例（`dsh src : preinstalled`）**没有 `node_modules/`**，只有两项 ——
            //   "三个子目录"不是恒等式（R28 / C1-A3 更正）。
            //
            // ## 为什么不是 workspace 但预建几个子目录
            // 那等于替用户决定目录结构（`projects/`、`notes/`…），而用户完全可以有自己的组织方式。
            // 指向实例目录是"给出真实的、非空的、有意义的上下文"，不替他做决定。
            //
            // ## ⚠️ 安全：`home/` 里有 API Key，而 dsh 的文件 API **允许读工作区外的文件**
            //
            // 这一节原写"picker 只列目录 ⇒ `.credentials.yaml` 看不到 ⇒ 安全" —— **那句话是错的**
            // （R28 / C1-C3 实测推翻）。真实情况：
            // - `home/` 下确实有 `.credentials.yaml`（API Key）与 `settings.yaml`；
            // - dsh 的 `workspace-files.read` / `readBytes` 的文档明写
            //   "files outside it are allowed"（`dsh/packages/api/workspace-files/src/index.ts:226/251`），
            //   `confine()` 只挂在 `list`（`:345`）上；
            // - ⇒ 用户若把 `home/` 选成工作区，agent / 会话**可以读到 API Key**。
            //
            // 我们无法改 dsh 的这套语义（那是它的设计：agent 要能读任意文件）。能做的是：
            // ①让 picker 的默认落点远离 `home/`（实例目录的第一屏是 `workspace/` 与 `home/` 并列，
            //   用户点错的可能存在）；②在设置项的说明里写清"不要把 home 目录选成工作区"。
            // 后者已写进 `dsh_settings_desc_home` 的描述与发版说明。
            // 顺带更正另一处：`home/` 下**没有** `logs/` 目录（全仓 `dshHomePath('logs'` 零命中），
            // 实际是 `profiles/`、`storages/`、`sessions/`、`cache/` 与两个 yaml。
            //
            // ## 安全边界（沿 fix15 的核实结论；**fix17 起多了一条，见下**）
            // - dsh 的配置/凭据/cookie 在 `DSH_HOME`（脚本显式设为 `$INSTANCE_DIR/home`），
            //   不读 `$HOME/.dsh` ⇒ 配置不搬家；
            // - npm 缓存走显式的 `NPM_CONFIG_CACHE`（`<filesDir>/dsh/npm-cache`）
            //   ⇒ 不会因为 HOME 变了而重新下载依赖。
            //
            // ⚠️ **`HOME` 现在接受用户输入**（fix17 起的「启动目录」设置项）——
            //    原先这里写着"由本函数显式构造、不接受外部输入"，那句已经不成立，必须更正：
            //    · 输入经过 [resolveHomeRootfs] 清洗：只接受 guest 内绝对路径、拒绝 `..`/`.`、
            //      归一化尾斜杠，非法值一律回落默认；
            //    · 它只影响 `$HOME`（一个环境变量），不会改变 dsh 读配置的位置（那是 `DSH_HOME`）；
            //    · 唯一需要提防的副作用是"在 `instances/` 下建目录会被收编成幽灵实例"——
            //      已由 [planHomeDirCreation] 挡掉（见上面的说明和 `:863` 附近）。
            // 自定义 HOME 若有值就用它，否则用实例目录（判据见 [resolveHomeRootfs]）。
            // 复用上面算好的 homeGuest（C2-E-2）：两处各算一次的话，中间任何"就地修正"
            // 都会漂成"建了 A 目录、HOME 却指 B"。
            homeRootfs = homeGuest
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

        // onExit 需要知道"是哪一个 proot pid 退出的"（K03 compare-and-delete 用它判断能否删 pid 文件）。
        // 用 AtomicLong 捕获：onExit 只会在进程退出时触发——那一定晚于下面的 launchedPid.set(...)。
        val launchedPid = java.util.concurrent.atomic.AtomicLong(0L)
        // ★ BL-038 世代隔离：在**真正 fork 新进程**的这一刻递增世代号（上面的"复用早退"走不到
        //   这里，所以它永远打不脏还活着的当前 run）；onLine/onExit 闭包各自捕获自己的 gen。
        val gen = ++runGen
        val h = try {
            ProotRunner.start(
                spec = spec,
                scope = scope,
                onLine = { line -> onProcessLine(instance, gen, line) },
                onExit = { code -> onProcessExit(instance, gen, code, launchedPid.get()) }
            )
        } catch (e: Exception) {
            val reason = e.message ?: e.toString()
            // 实例级：启动失败归因给这个实例（"为什么它起不来"是这个实例页最该回答的问题）
            DshLogBus.appendFor(instance.id, "[runtime] 启动失败：$reason")
            _state.value = State.Failed(instance.id, instance.name, reason)
            _running.value = null
            // ★ BL-152（core）：置 Failed 后必须补**任务终态上报**。任务区那条"启动中"是
            //   [DshTasks.tasks] 对 State.Starting 的只读投影 —— 状态一落 Failed 它就从投影里
            //   **消失**；不上报终态的话用户只看到"行没了"，分不清是起来了还是崩了。姿态与
            //   本文件其余失败路径完全同款（[failAndCleanup]/[failRetry]/[onProcessExit] 都
            //   经 [reportRuntime] 报 FAILED，内部用具名参数构造 DshTask，不耦合字段顺序）。
            reportRuntime(instance.id, instance.name, DshTask.State.FAILED, reason, error = reason)
            return StartOutcome.Failed(reason)
        }
        handle = ProcessHandle(h, instance.id)
        val pid = h.pid()
        launchedPid.set(pid)
        // pid 文件只给"孤儿认领/清理"（冷启时按 cmdline 校验）用，所以这里记的是**proot 的 pid**；
        // node 的 pid 另有 dsh-node.pid（由脚本写，停止时用它精确终止，见 [terminateNodeProcess]）。
        if (pid > 0) {
            ProcessUtil.writePidFile(instance.id, pid, launchPort)
        } else {
            // 拿不到 pid 时（反射没有 pid() 且 /proc 兜底也失败 —— 真机两次启动全 -1 就是这条路，
            // 最可能是内核无 CONFIG_PROC_CHILDREN 或 SELinux 挡，细节见失败当时那条
            // "[proot] pid resolution failed …" 诊断行），孤儿进程无法被认领/清理，
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
            // ★ BL-038：世代校验兜掉"取消迟到"的微小窗口 —— cancel() 是协作式的，若恰在 delay
            //   返回之后、本行之前有新 run 接管（同实例秒启后 gen 已递增），这个旧看门狗不许再对
            //   新 run 的 Starting 下手（那样会误把新进程判成"启动超时"并收掉它的句柄）。
            if (st is State.Starting && st.instanceId == instance.id && gen == runGen) {
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
        if (!ProcessUtil.isAlive(meta.pid)) {
            // ★ BL-149(b)：三个早退分支里**唯一确定是残留**的一条 —— pid 文件在、但这个 pid 的
            //   进程已死。旧实现直接 return：死 pid 文件就此永久滞留（每次冷启白读、killStale
            //   再扫一遍），其配套 token 侧车是**死代次**的 token（留着只会让下次认领多探一个
            //   死候选、也在磁盘上多留一份明文凭据）。
            //   这里做 compare-and-delete（K03 家族同款口径）：**回读一次** pid 文件，仅当其中
            //   仍是认领时读到的这个 pid（没有被"停后秒启"的新进程覆盖）才删 —— 文件已变/
            //   已不存在，一个都不动。两处删除（pid 文件 + 侧车）由**同一个 latest** 判断 gate。
            //   故意不删 dsh-start-*.log：日志在共享 TMP_DIR 里、文件名不带实例身份，它可能是
            //   别的活实例的恢复源（token 进程级、只此一份）；明文日志的堆积由
            //   [ProcessUtil.pruneStartLogs] 的轮转规则统一处置。
            //   另两个早退分支（没有 pid 文件 / 端口不通）保持不动 —— 进程可能活着，
            //   删了会毁掉恢复源。
            val latest = ProcessUtil.readPidFile(instance.id)
            if (latest?.pid == meta.pid) {
                // 文件操作一律 runCatching（清理是旁路，绝不许把认领流程带崩；侧车删除内部已包）
                runCatching { DshPaths.instancePidFile(instance.id).delete() }
                ProcessUtil.deleteTokenUrlFile(instance.id)
                // ★ 保持全局：与认领族诊断同流（跨实例的冷启恢复动作，"这次冷启清理了什么"要在一起看）
                DshLogBus.append(
                    "[runtime] adoption skipped for ${instance.name}: pid ${meta.pid} is no longer alive; " +
                        "removed stale pid file and token sidecar (start logs left to rotation)"
                )
            }
            return false
        }
        // 必须确认这个 pid 确实是"我们这个实例的 proot"，避免误杀/误认别的进程
        val cmdline = ProcessUtil.cmdline(meta.pid) ?: return false
        if (!cmdline.contains("proot") || !cmdline.contains(instance.id)) return false
        val port = meta.port
        if (port <= 0 || !Probe.isPortOpen(port)) return false
        // 认领是异步做的（读 /proc + 探端口都要时间）：这期间用户完全可能已经手动启动了别的实例，
        // 此时再无条件写 _state 会把新实例的状态覆盖掉（界面显示 A 在跑，实际 B 在跑）。
        //
        // ★ Round24 A4：判据从 `runningInstanceId() != null` 换成 `ownerOf(...) != null`（"无归属"）。
        //   旧判据有两个洞：
        //   a) runningInstanceId() 对 Failed/Exited 返回 null ⇒ 慢探活（三源各 2s）期间实例启动失败
        //      落了 Failed，这里照样放行：把**失败态**覆盖成 Running(adopted)、把 handle 换成
        //      PidHandle(死 pid)，用户看不到失败原因（F12）；
        //   b) 它对 Stopping 也返回 null ⇒ 停止窗口内重建实例页会走真实认领路径（E4 实证）。
        //   新判据：Idle 之外**任何**状态都占着运行时通道（Starting/Running/Stopping 有在途进程，
        //   Failed/Exited 有尚未被用户确认的失败结论），一律拒绝认领（纯函数 [adoptionAllowed]）。
        //   ⚠️ 这条只挡"认领提交"这一段，挡不住 F21 的锁外窗口（读判据 → 锁外阻塞段 → 取锁），
        //   A 版只缩小窗口、不消除，见 IMPL-PLAN 一/A4 与 PLAN F21。
        if (!adoptionAllowed(_state.value)) return false
        // ★ Round24 A3 同族：认领成功 = 新生命周期的开始。只清**本实例**的旧标记
        //   （与 startLocked 的清理同口径，理由见那里的注释；无条件清会偷走别的实例的"主动停止"）。
        if (shouldClearStopRequested(stopRequestedFor, instance.id)) stopRequestedFor = null

        // ★ BL-135（R16/fix4，401 主根因修复）：优先用 pid 文件里持久化的**带 token URL**。
        //   dsh 的 token 是进程级的（只在该进程启动时打过一次），认领时没有任何其他恢复途径；
        //   拿不到时才回落裸 URL（旧 pid 文件无 url 字段 / 该 URL 的 pid 代次已过时）。
        //   带 token URL 对应用内 WebView 无害（cookie 模式照常 200；token 同进程有效）；
        //   对外跳浏览器则是"首访即 303 → Set-Cookie → 200"与"首访必 401 原文"的区别。
        //
        // ★ R17（H1/H2/A1 根治）：**多源候选 + 三态探活**。三个候选源按可靠性排序：
        //   ① pid 文件 url 字段（fix4；依赖 pid 解析成功）；
        //   ② 侧车文件 `dsh-token-url.txt`（R17 新增；落 filesDir 系统不回收、**不依赖 pid 解析**）；
        //   ③ start-dsh.sh 实时日志扫描（fix2 起即有；cacheDir 可被回收但也覆盖历史代次）。
        //   每个候选经 `Probe.probeTokenUrl` 三态探活：3xx=live（立即采用）、4xx/5xx=dead（换下个）、
        //   超时=unknown（记下、若最终无 live 则作 tentative 采用——最坏与裸 URL 同效、
        //   最好直接救活；X4 红队 A5：超时不许与 401 同码处理）。
        //   采用后回写 pid 文件 + 侧车文件（下次认领直读）。
        //
        //   ★ 顺序纪律（R17 自查修正的 TOCTOU）：**探活/恢复是慢操作，必须
        //   全部做完再进入状态写入段** —— 恢复段结束后、写 `_state` 之前**再复核一次**。
        val persistedUrl = meta.url?.takeIf { UrlScanner.tokenOf(it) != null }
        var liveUrl: String? = null
        var tentativelyLiveUrl: String? = null

        /** 三态探活一个候选并归类（live 立即采用；unknown 记 tentative）。 */
        fun tryCandidate(candidate: String?): Boolean {
            if (candidate.isNullOrEmpty()) return false
            // ★ BL-176（R21-E）：端口一致性门 —— ①pid 文件 / ②侧车 的候选与 ③启动日志恢复
            //   （[ProcessUtil.recoverTokenUrlsFromStartLogs] 的 expectedPort 过滤）统一口径。
            //   比对基准就是上面解析出的 [port]（= [ProcessUtil.PidMeta.port]，且已通过
            //   [Probe.isPortOpen] 确认可达）—— 与 ③级传进去的 expectedPort 同一个值。
            //   不一致一律不采信：探活 3xx 只证明"该 URL 的端口上有个 dsh 受理这个 token"，
            //   不证明那个端口属于本实例（判据见 [adoptionPortMatches]）。
            //   ③级候选已按同一端口筛过，这里对它们是幂等的。
            if (!adoptionPortMatches(candidate, port)) {
                // URL 明文（含 token）不落日志（沿用本函数既有的写回段纪律）：只记端口差。
                DshLogBus.append(
                    "[runtime] adopted ${instance.name}: adoption candidate port mismatch " +
                        "(candidate port ${UrlScanner.portOf(candidate)}, expected $port); candidate rejected"
                )
                return false
            }
            val code = Probe.probeTokenUrl(candidate)
            return when {
                code != null && code in 300..399 -> {
                    liveUrl = candidate
                    true
                }
                code == null -> {
                    // unknown（超时/连接异常）：不覆盖已记的 tentative（先到先得，新的先探）。
                    if (tentativelyLiveUrl == null) tentativelyLiveUrl = candidate
                    false
                }
                else -> false // 4xx/5xx：明确 dead，换下一个候选
            }
        }

        tryCandidate(persistedUrl)
        if (liveUrl == null && persistedUrl != null) {
            // ★ BL-176：此处不再只有"探活失败"一种成因 —— 端口不一致的候选同样走到这里
            //   （它根本没被探活），措辞按三种落空成因并列，避免把"被端口门拒绝"误读成"进程不在了"。
            DshLogBus.append(
                "[runtime] adopted ${instance.name}: persisted url not adopted " +
                    "(port mismatch, stale entry or probe timeout); searching other sources"
            )
        }
        if (liveUrl == null) {
            tryCandidate(ProcessUtil.readTokenUrlFile(instance.id))
        }
        if (liveUrl == null) {
            // 防御：TMP_DIR 是 lateinit（正常在 FCLApp.onCreate 已就绪）；整段包 runCatching，
            // 恢复是"锦上添花"，绝不许它把认领本身带崩（失败就回落裸 URL + 诊断）。
            runCatching {
                val candidates = ProcessUtil.recoverTokenUrlsFromStartLogs(
                    File(DshPaths.TMP_DIR), DshPaths.instanceDir(instance.id), port
                )
                for (candidate in candidates) {
                    if (candidate == persistedUrl) continue // 已探过（预算去重）
                    if (tryCandidate(candidate)) break
                }
                if (liveUrl == null && candidates.isNotEmpty()) {
                    DshLogBus.append(
                        "[runtime] adopted ${instance.name}: ${candidates.size} start-log candidate(s) " +
                            "probed, none confirmed live"
                    )
                }
            }.onFailure { e ->
                DshLogBus.append("[runtime] adopted ${instance.name}: start-log recovery failed: ${e.message}")
            }
        }
        // 全部源都没确认 live：unknown（超时）候选退而求其次（X4-A5：最坏与裸 URL 同效）。
        if (liveUrl == null && tentativelyLiveUrl != null) {
            liveUrl = tentativelyLiveUrl
            DshLogBus.append(
                "[runtime] adopted ${instance.name}: using tentative url after probe timeouts " +
                    "(may or may not carry a live token)"
            )
        }
        if (liveUrl != null) {
            // 回写双持久化源（下次认领直读，无需再扫日志）+ 注册脱敏。
            // 幂等：即便值来自 pid 文件本身，重写一遍也保证**侧车文件**从无到有补齐
            //（fix4→fix5 升级场景：pid 文件有 url、侧车还没建过）。
            // 日志只记"恢复了、写回成功"这个事实 —— URL 明文不落日志。
            val recovered = liveUrl
            if (recovered != null) {
                UrlScanner.tokenOf(recovered)?.let { DshLogBus.registerSecret(it) }
                // ★ BL-158（CAS）：探活/恢复是**秒级慢操作**，本回写段在锁外。窗口内用户完全可能
                //   已手动启动了本实例（startLocked 已 fork 新进程并写了自己的 pid 文件）——
                //   此时无条件回写会把**死 pid**（meta.pid）覆盖回新文件，孤儿保护失效
                //   （下次冷启按死 pid 认领会失败，或更糟：按 meta.pid 去"清理"一个已复用的 pid）。
                //   回写前重读一次 pid 文件做 compare-and-write（与 K03 家族同一判据口径）：
                //   仅当 文件不存在（null）/ 仍是认领时的 pid / pid==0（占位）才写；
                //   已被新进程接管（latest.pid 是别的值）→ 一个都不写，只记诊断。
                //   注意 writeTokenUrlFile 没有 pid 概念，但同样受"新进程已接管"影响（新 run 的
                //   侧车可能已被 onProcessLine 更新）——所以**一个 latest 判断同时 gate 两个写入**。
                val latest = ProcessUtil.readPidFile(instance.id)
                if (pidFileAllowsAdoptionWriteBack(latest?.pid, meta.pid)) {
                    ProcessUtil.writeTokenUrlFile(instance.id, recovered)
                    ProcessUtil.writePidFile(instance.id, meta.pid, port, recovered)
                    if (recovered != persistedUrl) {
                        DshLogBus.append(
                            "[runtime] adopted ${instance.name}: live token url recovered from fallback source; " +
                                "persisted to sidecar + pid file"
                        )
                    }
                } else {
                    DshLogBus.append(
                        "[runtime] adopted ${instance.name}: write-back skipped — pid file already " +
                            "replaced by a newer process (stored=${latest?.pid}, adopted=${meta.pid}); " +
                            "recovery persisted nothing"
                    )
                }
            }
        }
        // ★ R17（自查修正）：恢复（慢操作）结束后的**第二道归属复核** —— 探活窗口内用户完全
        //   可能已经启动了别的实例；此时再无条件写 _state/handle 会把新实例的状态覆盖掉
        //   （界面显示 A 在跑、实际 B 在跑）。与函数开头的早退同一判据、同一理由。
        //
        //   ★ 并且"复核 + 写入"必须**同一临界区**（与 stop/startLocked 共用对象监视器）：
        //   两个实例的认领（实例页重建可产生并行 maybeAdoptOrphan）各自走到这里时，
        //   无锁的 check-then-act 会让"后写覆盖前写"（与 R15-B #2 的 updateStatusIfOwned 同形）。
        //   ⚠️ 只锁这一小段 —— 上面的探活/恢复是秒级慢操作，绝不许持锁执行（会把 stop/start
        //   一起卡住）。探活失败重进的代价只是"再读一次 state"，可忽略。
        val adoptedUrl = liveUrl ?: "http://127.0.0.1:$port/"
        if (liveUrl == null) {
            DshLogBus.append(
                "[runtime] adopted instance ${instance.name}: no token url recovered from any source; " +
                    "falling back to bare url — external browser may 401"
            )
        }
        synchronized(this) {
            // ★ Round24 A4：与上面第一道判据同口径 —— 用"无归属"而不是 runningInstanceId()。
            //   复核必须在同一临界区里（下面马上要写 handle/_state，check-then-act 中间不许有缝）；
            //   这里比第一道严格一档是**有意**的：慢探活窗口里用户可能已经把实例启动起来了
            //   （Starting/Running），也可能刚点了停止（Stopping）—— 三种情况都不许抢。
            if (!adoptionAllowed(_state.value)) return false
            handle = PidHandle(meta.pid, instance.id)
            nameOf[instance.id] = instance.name
            // 认领来的实例同样要知道 node 的 pid：停止时要按它终止 rootfs 内的 node（B10）。
            nodePid = readNodePidFile(instance.id) ?: 0
            _state.value = State.Running(instance.id, instance.name, adoptedUrl, port, adopted = true)
            _running.value = Running(instance.id, port, adoptedUrl)
        }
        // ★ 保持全局：这是**认领巡检**（App 冷启时跨实例的恢复动作 —— "上次被系统杀掉时它还活着，
        // 直接复用"），不是某个实例自己跑出来的输出。归到全局才能看清"这次冷启到底认领/清理了什么"。
        DshLogBus.append("[runtime] 认领仍在运行的实例 ${instance.name}（pid=${meta.pid}，端口=$port）")
        watchAdopted(instance, meta.pid, port)
        // ★ BL-039：认领成功必须把前台服务一起拉起来。认领复用的 dsh 进程此刻只是"恰好还活着"——
        //   没有前台通知、没有保活，系统随时可以再把它（以及这个刚复活的会话）杀掉，认领等于白做。
        //   与 [DshLauncher.startInstance] 启动成功后调 DshRuntimeService.start 同一语义。
        //   放在这里（而不是让 UI 调用方补）是因为"认领成功"这个事实只有本函数知道；
        //   调用线程不保证是主线程（DshInstancesUI 在 IO 上调它），所以切到主线程调用
        //   （startForegroundService 的主线程要求 + 拿到主线程 Looper 的系统交互面）。
        //   极短窗口：若状态在我们切线程期间已经不是本实例的 Running，服务起来会自己按当前状态收尾
        //   （onStartCommand 里读状态发现无运行实例就立即 stopSelf，不会留僵尸通知）。
        val appCtx = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // ★ fix12（E3-N-5）：失败不许静默。旧实现整段 runCatching 无 onFailure ——
            //   FGS 没起来时界面是"运行中"、通知栏空着，系统随时杀进程，日志里查无实据。
            //   与 [DshLauncher.startInstance] 同功能的调用点有这条日志，这里补齐。
            runCatching { DshRuntimeService.start(appCtx) }
                .onFailure { e ->
                    DshLogBus.append(
                        "[runtime] FGS start failed after adoption: ${e.message}; instance is not protected"
                    )
                }
        }
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
                // K03 compare-and-delete：只有当 pid 文件仍记着这个已退出的 pid 时才删，
                // 别误删"停后秒启"时新进程刚写的 pid 文件。
                ProcessUtil.removePidFile(instance.id, pid)
                // ★ BL-038 同族（fix2 跟单）：handle/_running 的清理必须先复核状态归属 ——
                //   "状态检查（循环第一行）→ 写 handle=null"之间无锁，若新 run 已接管
                //   （用户同刻启新实例），无条件清句柄会把新 run 的 handle 抹掉（半孤儿）。
                //   与 onProcessExit 的归属+世代约定同款：只有状态仍属本认领实例才动句柄/状态。
                synchronized(this@DshRuntime) {
                    val cur = _state.value
                    if (cur is State.Running && cur.adopted && cur.instanceId == instance.id) {
                        handle = null
                        _running.value = null
                        _state.value = State.Exited(instance.id, instance.name, -1)
                    } else {
                        DshLogBus.append(
                            "[runtime] adopted-instance cleanup skipped: state no longer owned by " +
                                "${instance.name} (now ${cur::class.simpleName})"
                        )
                    }
                }
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
     *
     * ★ K02：加 `@Synchronized`——与 [startLocked]/[stopIf]/[setStateIfOwned] 以及停止协程里的
     *   `synchronized(this)` 用同一把对象监视器。旧实现里 start 侧已声明"同一把锁"，stop 却没加锁，
     *   注释与实现自相矛盾，并发时会留下窗口（句柄/状态读到一半被另一路改掉）。补齐同步后，
     *   "读取句柄/node pid、置 Stopping、起收尾协程"这段复合操作对其它入口是原子的。
     */
    @Synchronized
    fun stop(reason: String = "") {
        val resolvedReason = reason.ifBlank { stopReasonDefault() }
        // ★ BL-036a：停止中再点停止 = 空转。旧实现在这里把 Stopping 视作"没在跑"，立即置 Idle ——
        //   而进程还要数秒才真的退（TERM→5s→KILL 链上 node 可能还活着），界面/任务区于是"提前宣布
        //   停完"，还会放大"停后秒启"的竞态窗口（BL-038）。现在：识别 Stopping，保留状态、直接
        //   return，等 in-flight 的停止协程自然收尾（它会落 Idle 并打"已停止"）。
        //   对 Idle/Failed/Exited（下面 instanceId == null 的分支）的置 Idle 语义不变 —— 那些状态
        //   没有在途的进程收尾，置 Idle 是合理的复位。
        val curState = _state.value
        if (curState is State.Stopping) {
            DshLogBus.appendFor(
                curState.instanceId,
                "[runtime] stop ignored: ${curState.name} is already stopping (state=Stopping); " +
                    "in-flight stop will finish asynchronously (requested reason: $resolvedReason)"
            )
            return
        }
        val instanceId = runningInstanceId()
        adoptWatcher?.cancel()
        adoptWatcher = null
        if (instanceId == null) {
            // ★ Round24 N-2：没有"在跑"的实例，但句柄字段可能还留着一个**上一次停止没成功**的
            //   句柄（见下面收尾协程的保留逻辑）。这里补一次终止 —— 否则"失败后保留句柄以便重试"
            //   只是一句空话：状态已落 Idle/Failed，再没有任何入口会去用它。
            //   只在状态本来就没有归属时才走到这里，不会与在跑的 run 抢（新 run 的句柄是在
            //   Starting 之后才写入的，那时状态不是 Idle/Failed/Exited）。
            val leftover = handle
            if (leftover != null) {
                scope.launch {
                    // 再核一次"它还是当前句柄吗"：本协程是异步的，调度期间新 run 可能已经起来
                    // （startLocked 换了 handle）。那时这个 leftover 属于上一轮，绝不能对着它发信号
                    // （万一同实例的新 proot 恰好复用了同一个 pid，cmdline 身份校验也会通过）。
                    val stillCurrent = synchronized(this@DshRuntime) { handle === leftover }
                    if (!stillCurrent) return@launch
                    var leftoverFailed = false
                    runCatching { leftover.terminate(5000) }.onFailure { e ->
                        leftoverFailed = true
                        // 归属未知（状态已无归属，句柄是上一轮留下的）⇒ 宁可归全局，不要猜实例
                        DshLogBus.append(
                            "[runtime] retry terminate of leftover proot handle failed (pid=${leftover.pid}): ${e.message}"
                        )
                    }
                    val pid = leftover.pid
                    val gone = pid <= 0 || !ProcessUtil.isAlive(pid)
                    synchronized(this@DshRuntime) {
                        // 引用相等：期间若已有人换了句柄（新 run / 认领），一个都不许动。
                        if (handle === leftover && gone && !leftoverFailed) handle = null
                    }
                }
            }
            _state.value = State.Idle
            _running.value = null
            return
        }
        val name = nameOf[instanceId] ?: instanceId
        stopRequestedFor = instanceId // 标记：接下来的进程退出是"我们要它停"，不是崩溃
        _state.value = State.Stopping(instanceId, name)
        // 实例级：停止动作发生在该实例的生命周期里，实例页要能看到"谁在什么时候停了它"
        DshLogBus.appendFor(instanceId, "[runtime] 停止 $name（$resolvedReason）")
        // ★ Round24 N-2：这里**不再**同步 `handle = null`。旧实现"先丢句柄再终止"：终止一旦失败
        //   （terminate 抛异常 / proot 顽固），句柄已经没了 —— 而 Stopping 期的二次 stop 是 no-op
        //   （BL-036a），收尾协程随后又把状态落 Idle ⇒ 这个 proot 再也没有任何代码路径能拿到它，
        //   彻底变成杀不掉的孤儿（E4-N-2 CONFIRMED）。
        //   现在：句柄先留在字段里给收尾协程用，**确认进程已不在**之后才清（见协程末尾的
        //   `handle === h` 引用校验）；终止失败/进程仍活时保留句柄并留一行诊断。
        //   期间状态是 Stopping，[canJoinExistingRunLocked] 会因状态不匹配拒绝复用，
        //   所以"留着句柄"不会被误判成"还活着可复用"。
        val h = handle
        val np = nodePid
        nodePid = 0
        // proot 进程本身拿不到 pid 时，"孤儿认领/清理"这条路是断的（挂在 [ProotRunner.Handle.pid]：
        // 反射没有 pid() 且 /proc 兜底（task/<tid>/children 快照差集）也失败才返回 -1 ——
        // 真机两次全 -1，最可能是内核无 CONFIG_PROC_CHILDREN 或 SELinux 挡，
        // 细节见失败当时那条 "[proot] pid resolution failed …" 诊断行）。
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
            // BL-046：terminate 失败原先静默 —— 停止链最关键的一步（SIGQUIT→TERM/KILL）若整体
            // 抛出，verifyProotGone 会给出"仍存活"的结论，但**为什么**失败查无实据。补英文诊断，
            // 控制流不变。
            var terminateFailed = false
            runCatching { h?.terminate(5000) }.onFailure { e ->
                terminateFailed = true
                DshLogBus.appendFor(instanceId, "[runtime] terminate of proot handle failed during stop (pid=$prootPid): ${e.message}")
            }
            verifyProotGone(instanceId, prootPid)
            // ★ Round24 N-2（同族）：停止没成功时**也不删 pid 文件** —— 它是下一次启动的
            //   `killStale`（带 cmdline 身份校验）唯一能定位到这个残留 proot 的线索。旧实现无条件删，
            //   于是"终止失败 + 句柄已丢 + pid 文件已删"三件套一起发生，这个 proot 就彻底失联了。
            //   成功与否的判据与下面清句柄用同一组（gone && !terminateFailed），保证两者不会分叉。
            val gone = prootPid <= 0 || !ProcessUtil.isAlive(prootPid)
            val stopSucceeded = gone && !terminateFailed
            if (stopSucceeded) {
                // K03 compare-and-delete：只有当 pid 文件仍记着**我们正在停的这个** proot/node 时才删，
                // 否则"停后秒启"里新进程刚写好的 pid 文件会被这里的收尾误删 → 新进程失联成孤儿。
                ProcessUtil.removePidFile(instanceId, prootPid)
                deleteNodePidFile(instanceId, np)
            } else {
                DshLogBus.appendFor(
                    instanceId,
                    "[runtime] stop incomplete: pid files + handle kept (pid=$prootPid, " +
                        "terminateFailed=$terminateFailed, stillAlive=${!gone}); " +
                        "the next start's stale cleanup (killStale, cmdline-checked) can still reach this proot"
                )
            }
            // 关键：终止是异步的（最长 5s）。期间用户可能已经启动了新实例，
            // 只有当前状态仍停在这个实例上时才允许置 Idle / 收服务，否则会把新实例的
            // Starting/Running 覆盖掉，并且误杀它的前台通知。
            // 注意：对\"进程句柄\"（ProcessHandle）而言，terminate 会触发 onProcessExit，
            // 那里已把状态落到 Idle；这里主要覆盖\"孤儿进程句柄\"（PidHandle）没有 onExit 回调的情况。
            synchronized(this@DshRuntime) {
                // ★ Round24 N-2：句柄在这里才清 —— 且只在"仍是本次停的那个句柄"时清。
                //   ① 进程确实已不在（prootPid<=0 = 拿不到 pid，无从判活，按已结束处理）；
                //   ② 终止没抛异常。两条有一条不满足 = 本次停止没成功：**保留 handle**
                //   （旧实现先清句柄，终止失败后这个 proot 再也没有代码路径能拿到它 → 杀不掉的孤儿，E4-N-2）。
                //   用引用相等（handle === h）兜"停后秒启"：新 run 若已经换了句柄，这里一个都不许动。
                if (handle === h) {
                    if (h != null && stopSucceeded) handle = null
                }
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
            deleteNodePidFile(instanceId, pid)
            return
        }
        val cmd = ProcessUtil.cmdline(pid)
        if (cmd != null && !looksLikeNodeProcess(cmd)) {
            // pid 已被系统复用给别的进程：放手，交给 proot 侧收尾（绝不误杀）
            // 实例级：终止的是**这个实例的** node，"跳过终止"这条线索属于它（否则实例页上只会
            // 看到"停止"却没有下文，看不出停止其实没停干净）
            DshLogBus.appendFor(instanceId, "[runtime] pid=$pid 已不属于 dsh node（cmdline 不匹配），跳过按 pid 终止")
            deleteNodePidFile(instanceId, pid)
            return
        }
        DshLogBus.appendFor(instanceId, "[runtime] 终止实例 $instanceId 的 node（pid=$pid）")
        // 归属带上 instanceId：killBlocking 内部还有一次身份校验，那里"没杀成"的日志同样属于这个实例
        ProcessUtil.killBlocking(pid, NODE_KILL_GRACE_MS, expectNode = true, logInstanceId = instanceId)
        // K03 compare-and-delete：删前再核一次文件里仍是刚处理的这个 pid（秒启场景下脚本可能已经
        // 写入了新 node 的 pid 文件，别把它删了）。
        deleteNodePidFile(instanceId, pid)
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

    /**
     * 删除 node pid 文件（K03 compare-and-delete）。
     * 仅当文件里仍是 [expectedPid]（或文件已不存在/读不出）时才删，避免误删"停后秒启"时
     * 脚本为新 node 刚写下的 pid 文件（见 [pidFileBelongsTo]）。
     *
     * ★ R17：**侧车 token URL 文件与此成对清理**（同一 K03 判据守卫）——
     * 该 run 的 node 已经退出/被停，其 token 已失效，留着只会让下次认领多探一次死候选；
     * 而"停后秒启"时新 run 已写过自己的两份文件 → `pidFileBelongsTo` 为假 → 二者都不动
     *（不会误删新 run 的侧车）。expectedPid<=0（拿不到 pid）时不删——保守方向：
     * 侧车多留一会儿无害，下次认领的探活会拒绝死 token。
     */
    private fun deleteNodePidFile(instanceId: String, expectedPid: Long) {
        runCatching {
            if (pidFileBelongsTo(readNodePidFile(instanceId), expectedPid)) {
                nodePidFile(instanceId).delete()
                ProcessUtil.deleteTokenUrlFile(instanceId)
            }
        }
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
        // ★ R16/fix4（BL-138）：两段式宽限。第一段（PROOT_EXIT_GRACE_MS）覆盖常见收尾；
        //   仍在则以 PROOT_EXIT_GRACE_EXTENDED_MS 为增量再等第二段 —— SIGQUIT 的
        //   kill_all_tracees 收整棵 tracee 树可能 >1s，单段 1s 会把正常收尾误报成"仍存活"。
        //   只有两段全过仍活着才告警（此时才真正值得排障者注意）。
        var alive = waitProotExit(prootPid, PROOT_EXIT_GRACE_MS)
        if (alive) alive = waitProotExit(prootPid, PROOT_EXIT_GRACE_EXTENDED_MS)
        if (!alive) {
            // 实例级：这是"这个实例的 proot 确实走了"的结论，实例页要能看到（否则只有"停止"没有下文）
            DshLogBus.appendFor(instanceId, "[runtime] proot 已退出（pid=$prootPid）")
            return
        }
        // 实例级：这条"停止没停干净"的警告正是排障时要找的，必须出现在该实例自己的日志里。
        // BL-138：文案更新 —— 明确这是"两段宽限（1s+4s）过后仍存活"，取消"1 秒误报"的歧义。
        DshLogBus.appendFor(
            instanceId,
            "[runtime] 警告：停止后 proot 仍存活（pid=$prootPid，两段宽限后仍未退出）；" +
                "它可能还带着 rootfs 内的进程 —— 下次启动若端口被占，实例会自动改用其它端口"
        )
    }

    /**
     * R16/fix4（BL-138）：等 [pid] 退出最多 [budgetMs] 毫秒；期间每 100ms 轮询一次。
     * @return true = 预算耗尽时仍存活；false = 已退出
     */
    private suspend fun waitProotExit(pid: Long, budgetMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline && ProcessUtil.isAlive(pid)) {
            kotlinx.coroutines.delay(100)
        }
        return ProcessUtil.isAlive(pid)
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
     *
     * ★ Round24 N-1：默认值 8_000 → 13_000。旧注释说"8s 已远大于最坏耗时"是**错的**：
     * [stop] 的收尾链最坏耗时可到 ≈12s，四段构成（常量见本文件顶部）：
     * 1) 按 pid 终止 node：TERM → 等 [NODE_KILL_GRACE_MS]（2s）→ KILL，另有身份复核/文件 IO；
     * 2) proot SIGQUIT 后等一半宽限：`terminate(5000)` 里 `quitBudget = 5000/2` = 2.5s；
     * 3) 剩余宽限交给 TERM/KILL：同上 2.5s；
     * 4) [verifyProotGone] 两段宽限：1s + 4s = 5s。
     * 合计 2 + 2.5 + 2.5 + 5 ≈ 12s（单实例 stop 的 `terminate(5000)` 口径；[failAndCleanup]
     * 那条路径同量级）。8s 落在 2)~4) 中间 ⇒ "停后秒启"的兜底分支（超时后强行启动）**是常规路径**
     * 而不是边角，会把 Stopping 覆写成 Starting 并把 SIGKILL 打到正走 SIGQUIT 的 proot 上（E4-N-1）。
     * 13_000 比最坏值留 1s 余量；多等 5s 只发生在"proot 真的顽固"的设备上，正常收尾 <3s 就返回。
     */
    suspend fun stopAndWait(reason: String, timeoutMs: Long = 13_000): Boolean {
        // ★ BL-036：判据用 activeInstanceId()（含 Stopping）而不是 runningInstanceId()。
        //   旧实现在 Stopping 窗口进来时走"本来就没在跑"分支 → 立即 `_state.value = State.Idle`
        //   （二次停止秒置 Idle 的另一条出口，还把删除实例的等待条件整个跳过——进程还活着就删目录）。
        //   现在：Stopping 同实例时进下面的"等它离开 Stopping"循环；start() 里对"旧实例还在 Stopping"
        //   的 stopAndWait 调用同样因此等到位（stop() 对 Stopping 已按 BL-036a no-op，不会重复发停止）。
        val instanceId = activeInstanceId()
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
        // K05：归属校验。看门狗/超时触发这里的那一刻，用户可能已经启动了新实例（状态已是新实例的
        // Starting/Running）。那样的话，这条**旧实例的** Failed 既不能覆盖新实例的状态，也不能去动
        // 新实例的句柄/pid 文件。只有当前状态仍属于本实例时，才做"落 Failed + 收进程"这套收尾。
        // （照 onProcessExit / onProcessLine 的 Running 分支"状态写回先校验 instanceId"的既有约定办。）
        synchronized(this) {
            val st = _state.value
            if (!stateOwnedBy(st, instance.id)) {
                DshLogBus.appendFor(
                    instance.id,
                    "[runtime] stale timeout/failure for ${instance.name} ignored (state now owned by another instance)"
                )
                return
            }
            // ★ BL-037：守卫再收紧一格 —— 当前状态是**本实例的 Stopping** 时同样拒绝。
            //   Stopping 属于"用户主动停止"路径（stop 的收尾协程正在跑 TERM→KILL 链），不属于
            //   "启动超时/失败"语义；旧守卫按 instanceId 放行 Stopping，看门狗恰在停止瞬间到期就会把
            //   Stopping 覆写成"启动超时失败"（弹窗出现的时机正好卡在用户点完停止之后），而 stop 的
            //   收尾协程见 cur != Stopping 又会跳过"已停止"落 Idle，状态机两头打架。
            //   其余合法失败路径（仍在 Starting 且本实例、超时前的启动异常等）行为不变。
            if (st is State.Stopping && st.instanceId == instance.id) {
                DshLogBus.appendFor(
                    instance.id,
                    "[runtime] failAndCleanup skipped: ${instance.name} is Stopping (owned by this instance); " +
                        "a user-initiated stop is in flight, timeout/failure must not override it"
                )
                return
            }
            val h = handle
            handle = null
            val np = nodePid
            nodePid = 0
            _running.value = null
            _state.value = State.Failed(instance.id, instance.name, reason)
            scope.launch {
                // 与 stop() 同源：先按 pid 收掉 rootfs 内的 node，再收 proot。
                // 这条路径覆盖"启动超时"——超时的进程仍然是活的 node，不收掉就会占着端口，
                // 下一次启动直接 EADDRINUSE（正是 B10 的现场）。
                terminateNodeProcess(instance.id, np)
                // BL-046：与 stop() 同款 —— terminate 失败补英文诊断（启动失败收尾到底收没收干净，
                // 事后只靠 pid 文件与端口反推太贵）。控制流不变（含 h?.pid 的求值时机）。
                runCatching { h?.terminate(3000) }.onFailure { e ->
                    DshLogBus.appendFor(instance.id, "[runtime] terminate of failed proot handle failed (pid=${h?.pid ?: 0}): ${e.message}")
                }
                // K03 compare-and-delete：只删仍属于这个失败 proot 的 pid 文件（别误删秒启后新进程的）。
                ProcessUtil.removePidFile(instance.id, h?.pid ?: 0)
            }
            stopServiceIfIdle()
            // 终态上报：失败原因进任务区（否则任务行随着 Starting 一起消失，用户只看到"行没了"）
            reportRuntime(instance.id, instance.name, DshTask.State.FAILED, reason, error = reason)
        }
    }

    /** 兜底重试内部失败时的收尾（不重复打日志到"失败原因"里，失败原因由调用方给出） */
    private fun failRetry(instance: DshInstance, reason: String) {
        // ★ BL-037 同族（fix2 跟单）：seccomp 兜底重试的失败收尾与 [failAndCleanup] 同险 ——
        //   用户恰在重试的 preflight 失败前点了停止时，Stopping 会被这行覆写成 Failed，
        //   而 stop 的收尾协程见 cur != Stopping 又会跳过"已停止"落 Idle，状态机两头打架。
        //   守卫与 [failAndCleanup] 完全同款：本实例的 Stopping 一律拒绝。
        synchronized(this) {
            val st = _state.value
            if (st is State.Stopping && st.instanceId == instance.id) {
                DshLogBus.appendFor(
                    instance.id,
                    "[runtime] failRetry skipped: ${instance.name} is Stopping (owned by this instance); " +
                        "a user-initiated stop is in flight, retry failure must not override it"
                )
                return
            }
            // 守卫与写入同处一个监视器（与 [failAndCleanup] 的原子性同款）：判完到写之间
            // 不允许 stop 插进来把状态拉到 Stopping。
            setStateIfOwned(instance.id) { State.Failed(instance.id, instance.name, reason) }
        }
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
            // ★ Round24 A1b（E1-01 / E2-3）：这条重试**绕过 startMutex**，而 onProcessExit 清的是
            //   **全局** handle —— 从"决定重试"到"真的进 startLocked"之间（调度延迟 + 日志 IO），
            //   用户完全可能已经启动了另一个实例（甚至同实例的新一次 run）。不复核的话，这次迟到
            //   的重试会带着 noSeccomp 把新 run 的句柄/状态整个覆盖掉（E1-01 🔴）。
            //   判据：我还拥有通道，且状态仍是我这次生命周期留下的 Starting。
            //   ⚠️ 不能改成走 start()：start() 不接 noSeccomp 参数，`PROOT_NO_SECCOMP=1` 会丢，
            //   重试等于没重试（E2-3）。
            val stillMine = synchronized(this@DshRuntime) {
                ownerOf(_state.value) == instance.id && _state.value is State.Starting
            }
            if (!stillMine) {
                DshLogBus.appendFor(
                    instance.id,
                    "[runtime] seccomp retry skipped: another run took over (owner/state changed)"
                )
                return@launch
            }
            runCatching { startLocked(ctx, instance, noSeccomp = true) }
                .onFailure {
                    DshLogBus.appendFor(instance.id, "[runtime] seccomp 兜底重试失败：${it.message}")
                }
        }
        return true
    }

    private fun onProcessLine(instance: DshInstance, gen: Long, line: String) {
        // 实例级：这是**该实例的进程**自己打印的 stdout/stderr（dsh 的 web 输出、start-dsh.sh 的
        // 就绪/失败标记）。"从开始到停止只显示这个实例发出的日志"要的就是这些行 —— 它们同时进
        // 全局流，所以排障时的"时间线全貌"不会丢（见 DshLogBus.appendFor 的说明）。
        DshLogBus.appendFor(instance.id, line)
        // ★ BL-038：世代校验放在任何"动 nodePid / 写状态 / 回写端口"之前。停后秒启时，旧 run 的
        //   迟到输出（SIGQUIT 收尾打印、node 退出瞬间的尾部几行）会在新 run 已接管现场后才到；
        //   不拦它就会用旧 run 的 node pid / URL / 端口污染**新** run 的状态。
        //   stale 行仍进实例日志（它就是该实例进程的真实输出，排障要看），但不再参与任何状态变更。
        //   token 脱敏照旧：不管这行来自哪一代，token 不允许泄漏进日志。
        // ★ BL-109（R15）：三级解析 —— ①dsh 自己打的带 token URL；②脚本 READY url=；
        //   ③脚本 READY port=<n>（兜底：端口可达但 20s 无 token URL 时脚本报的就绪标记，
        //   以裸 URL 走既有 cookie 路径 —— 应用内 WebView 有持久 cookie，与 loadBestUrl
        //   的无 token 分支同形态）。前两级拿不到 URL 时，用第③级合成 http://127.0.0.1:<port>/。
        val url = UrlScanner.findAuthenticatedUrl(line)
            ?: UrlScanner.findMarkerUrl(line)
            ?: UrlScanner.findMarkerPort(line)?.let { "http://127.0.0.1:$it/" }
        // 先注册 token（若有）再判世代：旧 run 首次出现的 token 同样要脱敏
        url?.let { UrlScanner.tokenOf(it)?.let { t -> DshLogBus.registerSecret(t) } }
        if (gen != runGen) {
            if (staleGenNoted.getAndSet(gen) != gen) {
                DshLogBus.appendFor(
                    instance.id,
                    "[runtime] output from a stale run (gen=$gen, current=$runGen) logged only; " +
                        "node pid / state left untouched"
                )
            }
            return
        }
        // 脚本在 `node ... &` 之后立刻打印它，并在实例目录写下同一对值（见 start-dsh.sh）。
        // 记进内存是给"pid 文件被脚本收尾删掉、但进程还没死透"这种边角情况兜底的。
        NODE_PID_LINE.find(line)?.let { m ->
            m.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.let { nodePid = it }
        }
        // 1) dsh 自己打印的带 token URL（形如 dsh web: http://127.0.0.1:3080/?token=XXX）
        // 2) start-dsh.sh 的就绪标记（形如 [start-dsh] READY url=http://...）
        url ?: return

        val cur = _state.value
        if (cur !is State.Starting || cur.instanceId != instance.id) {
            // 已经 Running（dsh 重复打印 URL）时只刷新 url。
            // ★ 但必须确认"现在 Running 的仍是本实例"——否则被停掉的旧实例（终止是异步的，最长 5s）
            // 在退出前把 URL 打出来，就会把新实例的 url/port 覆盖成旧实例的，WebView 于是连到已经
            // 关掉的端口上。第三轮已把"状态跃迁先校验 instanceId"定成约定，这一处当时漏了。
            if (cur is State.Running && cur.instanceId == instance.id) {
                if (syntheticUrlWouldOverwriteToken(cur.url, url)) {
                    // ★ BL-136（R16/fix4）：**无 token 的合成 URL 不许覆盖已带 token 的 url**。
                    //   fix3 的③级兜底（READY port= → 合成 http://127.0.0.1:P/）在行序为
                    //   "dsh web:（①级带token）→ READY port=（③级裸）→ READY url=（②级带token）" 时，
                    //   中间那一步会短暂把裸 URL 写进 state（毫秒级窗口；若收集器/外跳恰在窗口内读
                    //   state 即受害）。判据见纯函数，端口仍可取（合成值里有端口）；url 保留旧值。
                    val portOnly = UrlScanner.portOf(url) ?: cur.port
                    _state.value = cur.copy(port = portOnly)
                    _running.value = Running(cur.instanceId, portOnly, cur.url)
                } else {
                    val port = UrlScanner.portOf(url) ?: cur.port
                    _state.value = cur.copy(url = url, port = port)
                    _running.value = Running(cur.instanceId, port, url)
                    // BL-135：重复打印/迟到的带 token URL 是 pid 文件里 url 字段的刷新机会
                    //（首次写入若发生在③级裸 URL 行，writePidFile 会把 url 记为 null；
                    // 此处把它补成带 token 的最终值）。
                    if (UrlScanner.tokenOf(url) != null && cur.url != url) {
                        handle?.pid?.takeIf { it > 0 }
                            ?.let { ProcessUtil.writePidFile(instance.id, it, port, url) }
                        // R17：侧车文件同刷（不依赖 pid 解析成功，见 onProcessLine 就绪段的注释）
                        ProcessUtil.writeTokenUrlFile(instance.id, url)
                    }
                }
            }
            return
        }
        val port = UrlScanner.portOf(url) ?: instance.port
        // ★ Round24 A5（E2 实测：锁外读-判-写可被 stop() 插进来，把 Stopping 覆盖回 Running）：
        //   "判定 + 写状态"必须在同一个临界区（与 startLocked/stop 同一把对象监视器），且
        //   **锁内重读**状态 —— 不能闭包捕获上面锁外读到的 cur（那是旧值，判了也白判）。
        //   判据保持"仍是本实例的 Starting"：这是整段唯一想做的迁移（就绪）。
        //   锁内只做状态与 _running 的配对写；下面所有副作用（pid 文件、token 侧车、端口回写、
        //   日志、任务上报）全部留在锁外 —— E2 的实测前提之一，避免把 IO 拖进 stop 的等待里。
        val becameRunning = synchronized(this) {
            val cur = _state.value
            if (cur is State.Starting && cur.instanceId == instance.id) {
                _state.value = State.Running(instance.id, instance.name, url, port)
                _running.value = Running(instance.id, port, url)
                true
            } else {
                // 已是**本实例的** Running（另一条路径刚把同一迁移做完了）：迁移本身不再需要，
                // 但下面的副作用（token URL 落 pid 文件/侧车）仍是幂等且必须做的 —— 吞掉它们会
                // 丢掉"App 被杀后认领孤儿还能拿到 token"的唯一来源。除此之外（Stopping/Failed/
                // 别的实例）一律拒绝，连副作用也不做。
                cur is State.Running && cur.instanceId == instance.id
            }
        }
        if (!becameRunning) {
            // 锁外读到的 Starting 已被别人改写（典型：用户刚点了停止 → Stopping）。
            // 这条 URL 仍是该实例进程的真实输出（上面已进实例日志），但不再参与状态变更。
            DshLogBus.appendFor(
                instance.id,
                "[runtime] ready marker ignored: state changed before transition " +
                    "(now ${_state.value::class.simpleName}); not writing Running"
            )
            return
        }
        // ★ BL-135：把**就绪时的带 token URL** 连同 pid 一起落进 pid 文件 —— 它是"App 被杀后
        //   认领孤儿还能拿到 token"的唯一来源（token 进程级、日志已脱敏、dsh 自身不落盘）。
        //   仅当次解析出的 url 带 token 时才存（writePidFile 内部再校验一次；②级 READY url=
        //   与①级 dsh web: 都带 token，③级合成裸 URL 不带 → 不会把裸 URL 存进去）。
        handle?.pid?.takeIf { it > 0 }?.let { ProcessUtil.writePidFile(instance.id, it, port, url) }
        // ★ R17（H1/H2/A1）：侧车文件**不依赖 pid 解析成功**（真机 pid 曾长期拿不到 -1，
        //   那样 pid 文件的 url 字段整条写入路径都不执行）；带 token 才写、同源同值。
        if (UrlScanner.tokenOf(url) != null) {
            ProcessUtil.writeTokenUrlFile(instance.id, url)
        }
        // K04：只有当实例本来就是"自动端口"（port<=0）时，才把实际端口回写进配置。
        // 旧实现无条件回写，会把用户在设置里**手工固定**的端口在"端口被占→自动避让"后永久覆盖掉。
        // 用户固定过端口（instance.port>0）就尊重它，不改他的配置（见 [shouldPersistPort]）。
        if (shouldPersistPort(instance.port, port)) {
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

    private fun onProcessExit(instance: DshInstance, gen: Long, code: Int, exitedProotPid: Long) {
        // ★ BL-038 世代校验：这个回调属于哪一次 run 的进程？启动闭包在 fork 那一刻捕获了 gen。
        //   停后秒启时旧 run 的退出（及其 137 之类的退出码）会迟到到新 run 已接管现场之后，
        //   绝不能让它清掉新 run 的 handle/nodePid（proot 会变停不掉的半孤儿）、或烧掉新 run 的
        //   seccomp 兜底机会 —— 所以把它并入下面的"无副作用清理"分支统一处理。
        val staleGen = gen != runGen
        // ★ 归属校验（第五轮修复）：退出回调是异步的。用户"停 A 起 B"时，A 的退出回调完全可能
        // 在 B 已经 Starting/Running 之后才到；旧实现无条件 `handle = null` / `_running = null`，
        // 并把状态写成 Exited(A)，于是 B 的进程句柄被抹掉（[stop] 再也拿不到句柄，只能删 pid 文件，
        // B 的 proot 变成谁也停不掉的孤儿），界面也显示成"A 退出了"。
        // 现在：只有"当前仍属本实例且世代未过期"才允许动状态与句柄，否则只做无副作用的清理。
        val cur = _state.value
        if (staleGen || ownerOf(cur) != instance.id) {
            // ★ BL-038：迟到的旧 run 回调走到这里，说明新 run 已经接管（gen 已递增）；此时
            //   `handle`/`nodePid`/`_state` 都归新 run，一个都不许碰。只做 compare-and-delete 的
            //   pid 文件清理（文件里若已是新进程的 pid 则不动，见 [ProcessUtil.removePidFile]）。
            if (staleGen) {
                // 实例级：说明的是"该实例的旧进程退出了"——实例页应能看到这个收尾事实
                DshLogBus.appendFor(
                    instance.id,
                    "[runtime] stale run exit (gen=$gen, current=$runGen, code=$code) ignored: " +
                        "handle / node pid / state belong to the current run; only pid file cleanup"
                )
            } else {
                // 实例级：说的仍是"A 的进程退出了"，A 的实例页应该看到自己的进程结束了；
                // 括号里那半句（状态已被别人接管）是跨实例竞态的线索，但主体事实属于 A。
                DshLogBus.appendFor(
                    instance.id,
                    "[runtime] ${instance.name} 的进程已退出（code=$code），" +
                        "但当前状态不属于它（现状：${cur::class.simpleName}），仅清理 pid 文件"
                )
            }
            // K03 compare-and-delete：只有当 pid 文件仍记着**这个已退出的** proot 时才删；
            // 若 B（或本实例的新 run）已经把自己的 pid 写进了就别删。
            ProcessUtil.removePidFile(instance.id, exitedProotPid)
            return
        }

        val wasStopRequested = stopRequestedFor == instance.id
        val exitedNodePid = nodePid
        handle = null
        // 进程已经退出：内存里的 node pid 作废（避免它被系统复用后误杀，见 ProcessUtil.kill 的身份校验）
        nodePid = 0
        _running.value = null
        // K03 compare-and-delete：删 pid / node pid 文件前核对仍是这个退出的进程写的，别误删秒启后新进程的。
        ProcessUtil.removePidFile(instance.id, exitedProotPid)
        deleteNodePidFile(instance.id, exitedNodePid)

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

    /**
     * 状态复位（界面确认过失败信息后调用）。
     *
     * ★ Round24 A6：加锁 + 只从终态复位。
     * - 旧实现是**无锁**的两次独立读（`!is Running && !is Starting` 各读一次 `_state.value`）：
     *   读到"非 Starting"之后、写 Idle 之前，并发的 startLocked 可能已经落了新的 Starting
     *   ⇒ 这次复位会把**刚起来的那个实例**抹成 Idle（F13(a)），界面显示"没在跑"而进程是真活的。
     * - 判据改成**显式白名单** Failed/Exited → Idle：只有"有明确终态结论、用户已看过"的状态才可
     *   复位。Stopping 不许复位（进程还在收尾，抹成 Idle 会让 stop 的收尾协程见 cur != Stopping
     *   而跳过"已停止"落点）；Idle 本身是幂等 no-op。
     * - 不复用 [setStateIfOwned]：它是"同归属任意→任意"的通行证（E4-N-7），用它等于把整张
     *   迁移约束绕过去；这里要的恰好是"只允许一条边"。
     * 三个调用点（DshLauncher 有归属守卫 / DshInstancesUI 无守卫 / Exited 后无条件）语义都不变：
     * 它们在 Running/Starting/Stopping 时本来就不该复位，现在只是从"靠调用方自觉"变成"结构拒绝"。
     */
    @Synchronized
    fun resetState() {
        val cur = _state.value
        if (cur is State.Failed || cur is State.Exited) {
            _state.value = State.Idle
            _running.value = null
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

// --- 纯逻辑助手（抽出来便于单测；无状态、无 Android 依赖） ----------------------

/**
 * K05：某个状态归属于哪个实例（Idle = 无归属）。
 * 用于"迟到的回调/看门狗不许改别人的状态"的判定（见 [DshRuntime] 的 setStateIfOwned / failAndCleanup）。
 */
fun ownerOfState(state: DshRuntime.State): String? = when (state) {
    DshRuntime.State.Idle -> null
    is DshRuntime.State.Starting -> state.instanceId
    is DshRuntime.State.Running -> state.instanceId
    is DshRuntime.State.Stopping -> state.instanceId
    is DshRuntime.State.Failed -> state.instanceId
    is DshRuntime.State.Exited -> state.instanceId
}

/** K05：当前状态是否仍属于 [instanceId]（只有属于时才允许把它写成 Failed，避免覆盖已启动的新实例）。 */
fun stateOwnedBy(state: DshRuntime.State, instanceId: String): Boolean =
    ownerOfState(state) == instanceId

/**
 * ★ Round24 A1a：`startLocked` 的"复用现有 run"判据（纯函数，便于 JVM 单测）。
 *
 * 三元组：状态属于本实例 ∧ 状态是 `Starting`/`Running` ∧ 有**活**句柄。
 *
 * @param handleAlive 句柄是否活着。`null` = 根本没有句柄。两者都判 false，但语义分开表达，
 *   测试也分两条钉（"handle 已死" 与 "handle 为 null" 是两种不同的现场）。
 *   注意：对认领来的 [DshRuntime] 内部 `PidHandle`，调用方传的是 **活且身份匹配**
 *   （[DshRuntime] 内部的 `Handle.isAliveAndOwned`，见 N-3），不只是"/proc 里有个 pid"。
 *
 * 为什么必须是它（H1）：旧判据只有"状态同 id"，而 seccomp 重试恰在 `onProcessExit` 已清 handle
 * 之后调进来，状态仍是 `Starting(A)` ⇒ 旧判据直接"复用" ⇒ 新 proot 从未 fork ⇒ 界面空转 180 秒。
 *
 * 为什么 `Stopping` 不算（哪怕有活句柄）：停止中的进程马上就没了，复用等于把"正在拆的房子"当成
 * "可以住的房子"；`Failed`/`Exited` 同理（归属还在，但没有可复用的 run）。这条在 N-2 之后尤其重要：
 * 停止失败时句柄会**故意保留**在字段里给下次 stop 用。
 *
 * 写成顶层函数而不是 private：[DshRuntime] 是 object、依赖 android，单测 harness 只加载顶层
 * 静态方法（同 [ownerOfState] / [pidFileBelongsTo] 的惯例）。
 */
fun canJoinExistingRun(
    instanceId: String,
    state: DshRuntime.State,
    handleAlive: Boolean?
): Boolean = runStateAllowsJoin(instanceId, state) && handleAlive == true

/**
 * ★ Round24 A1a 的零代价前半：状态归属 + 状态种类允许复用（不碰句柄）。
 *
 * 单独抽出来给 [DshRuntime] 的 `canJoinExistingRunLocked` 做短路：句柄判活对认领态要读
 * `/proc/<pid>/cmdline`，而那条判据在对象监视器里被调用，不该为"状态本来就不匹配"的绝大多数
 * 情形读文件。语义与 [canJoinExistingRun] 的前两元完全相同（单测两边都钉）。
 */
fun runStateAllowsJoin(instanceId: String, state: DshRuntime.State): Boolean {
    if (ownerOfState(state) != instanceId) return false
    return state is DshRuntime.State.Starting || state is DshRuntime.State.Running
}

/**
 * ★ Round24 A3（含 E4-N-4）：这个"主动停止"标记该不该在**新生命周期开始**时清掉。
 *
 * 只清**本实例**的：无条件清会把别的实例的标记偷走 —— "停 A → 立刻起 B"时 A 的进程随后退出
 * （code=143）会被当成"意外退出"上报（错误 Toast + 任务区 DONE）。反过来，本实例自己的旧标记
 * 必须清掉：不清的话，重试/停后秒启起来的新 run 若自己崩掉，会读到一个过期的"主动停止"标记，
 * 把崩溃静默成"已停止"。
 */
fun shouldClearStopRequested(stopRequestedFor: String?, instanceId: String): Boolean =
    stopRequestedFor != null && stopRequestedFor == instanceId

/**
 * ★ Round24 A4：认领（adopt）只能在"运行时通道无归属"时进行。
 *
 * 与 [activeOwnerOf] 的差别是**有意**的：认领要挡的不只是"正在跑/正在停"（Starting/Running/Stopping
 * 都占通道），还包括 **Failed/Exited** —— 它们的归属仍在，代表一个有结论但用户尚未确认的失败
 * （[DshRuntime.resetState] 才把终态复位）。旧判据 `runningInstanceId() != null` 对
 * Failed/Exited 返回 null ⇒ 慢探活期间实例启动失败落了 Failed，认领提交段照样放行：
 * 把失败态覆盖成 Running(adopted)、把 handle 换成 PidHandle(死 pid)（F12）。
 */
fun adoptionAllowed(state: DshRuntime.State): Boolean = ownerOfState(state) == null

/**
 * K04：端口被自动避让后，是否应把实际端口回写进实例配置。
 * 仅当实例本来就是"自动端口"（[configuredPort] <= 0）时才回写；用户固定过端口（>0）就别覆盖他的配置。
 */
fun shouldPersistPort(configuredPort: Int, actualPort: Int): Boolean =
    configuredPort <= 0 && actualPort > 0 && actualPort != configuredPort

/**
 * K03：pid 文件是否可以删（compare-and-delete）。
 * 只有当文件里的 pid 等于"我要清理的那个 pid"（或文件已不存在/读不出）时才允许删 ——
 * 否则在"停后秒启"场景下会删掉**新进程刚写的** pid 文件，把新进程变成失联孤儿。
 */
fun pidFileBelongsTo(storedPid: Long?, expectedPid: Long): Boolean =
    storedPid == null || storedPid == expectedPid

/**
 * ★ BL-158：认领恢复**回写**的 compare-and-write 判据（纯函数，便于单测）。
 *
 * 场景：认领的探活/恢复是秒级慢操作、回写段在锁外；窗口内用户完全可能手动启动了本实例 ——
 * 新进程已把自己的 pid 写进 pid 文件。此时若用**认领时的死 pid**（[adoptedPid]）无条件回写，
 * 会把新文件覆盖回死 pid，孤儿保护失效（与 K03 的"误删新 pid 文件"同族的覆盖版）。
 *
 * 判据（与调用点 [DshRuntime.adoptOrphan] 的注释一致）：
 * 仅当 文件不存在（[latestPid] == null，如清理竞态刚删过）／文件里仍是认领时的 pid ／
 * 文件里是 0（占位，本代码库不会写出、视为可安全覆盖）时才允许写。
 * 写出的两个持久化源（pid 文件 + token 侧车）由**这同一个判断** gate —— 侧车没有 pid 概念，
 * 但它同样会被新 run 更新，分裂判断会让"pid 文件不动、侧车却被旧 token 覆盖"。
 *
 * 写成顶层函数而不是 private：单测 harness 不加载 android 依赖，顶层落在 `DshRuntimeKt`
 * 静态方法上（同 [pidFileBelongsTo] / [canSendQuitSignal] 的惯例）。
 */
fun pidFileAllowsAdoptionWriteBack(latestPid: Long?, adoptedPid: Long): Boolean =
    latestPid == null || latestPid == adoptedPid || latestPid == 0L

/**
 * ★ BL-036：**含 Stopping** 的"运行时通道占用者"（[DshRuntime.activeInstanceId] 的纯函数核）。
 * 当前状态是 Starting/Running/Stopping 时返回其 instanceId；Idle/Failed/Exited 返回 null。
 *
 * 与 [ownerOfState] 的差别：后者给"每个有归属的状态"映射归属（含 Failed/Exited，用于"迟到回调
 * 不许改别人的状态"的校验）；本函数只回答"运行时通道此刻是否仍被占用"——
 * Failed/Exited 的进程收尾已经结束或已有明确结论，通道空闲。
 *
 * 为什么单测直接钉这个函数（public）：它是 BL-036 修复的判定核心，五个状态的取舍在 JVM 上
 * 可以精确断言；真机时序（Stopping 窗口内的删除/二次停止）没法在这里复现。
 */
fun activeOwnerOf(state: DshRuntime.State): String? = when (state) {
    is DshRuntime.State.Starting -> state.instanceId
    is DshRuntime.State.Running -> state.instanceId
    is DshRuntime.State.Stopping -> state.instanceId
    else -> null
}

/**
 * ★ BL-040：SIGQUIT 前的身份复核判据（纯函数，便于单测）。
 *
 * proot 的 SIGQUIT 意味着"kill_all_tracees 后退出"——发给错误的进程就是灾难性的（把别人的
 * 任务整条连根带走）。在"pid 解析不可用"的设备上，terminate 现场重解析拿到的 pid 未必是
 * 本实例的 proot（/proc 差集可能选中自检 runOnce 或另一实例的 proot），所以发信号前必须
 * 复核目标身份：cmdline 里同时含 `proot` 与**本实例的 instanceId**（proot 的 argv 带
 * `--bind=.../instances/<id>` 与 INSTANCE_DIR=...，见 [ProotCommand.build]）。
 *
 * 判据取严：cmdline 读不到（null/空）**同样不发** —— 复核的目的就是"不确定就不发"，
 * 放行"读不到"等于把不确定当通过。cmdline 不匹配时改走 destroy/TERM/KILL 兜底路径
 * （对 proot 基本无效，但那条路径不会误伤别的进程）。
 */
fun canSendQuitSignal(pid: Long, cmdline: String, instanceId: String): Boolean =
    pid > 0 && instanceId.isNotBlank() && cmdline.contains("proot") && cmdline.contains(instanceId)

/**
 * ★ fix12（E3-N-11）：`killBlocking` 发 SIGTERM/SIGKILL 前的身份复核判据（纯函数，便于单测）。
 *
 * 与 [canSendQuitSignal] **同方向**：不确定就不发。旧实现把 `cmd == null`（读不到）当作**通过**，
 * 于是 `/proc/<pid>/cmdline` 读不到（SELinux、pid 刚被回收、进程刚退出）时会**盲发** TERM→KILL —
 * 那个 pid 若已被内核复用给别的 App 的进程，杀的就是别人。两个函数方向相反本身就是缺陷：
 * 一个说"读不到就别发"，一个说"读不到就发"。
 *
 * @param cmdline null = 读不到（不是空串：空串是"读到了但为空"，同样不算通过）
 * @param expectNode true = 目标是 rootfs 内的 node（按 pid 收 node 用），false = 目标是 proot。
 *   两条判据都要求在 cmdline 里找到**专属特征**：node 含 dsh 包路径/node22 路径，proot 含 `proot`。
 */
fun shouldSignal(pid: Long, cmdline: String?, expectNode: Boolean): Boolean {
    if (pid <= 0) return false
    // 读不到 = 身份不明 = 不发（E3-N-11 的核心；与 canSendQuitSignal 的"取严"同口径）
    if (cmdline.isNullOrBlank()) return false
    return if (expectNode) {
        cmdline.contains("@deepseek-ai/dsh") ||
            cmdline.contains("node22/bin/node") || cmdline.contains("/node ")
    } else {
        cmdline.contains("proot")
    }
}

/**
 * 判断"进程在就绪前退出"是否像是 proot 与内核 ptrace/seccomp 不兼容。
 *
 * 典型表现（真机上出现过的一类问题）：proot 起得来，但 rootfs 里的进程刚跑就被信号杀掉，
 * 退出码是 128+signal（例如 SIGSYS=31 → 159、SIGSEGV=11 → 139），日志里出现
 * seccomp / ptrace / Bad system call / Operation not permitted 等字样。
 * 这类情况带 `PROOT_NO_SECCOMP=1` 重试通常就好了（见 [DshRuntime] 的 `seccompFallbackUsed`）。
 *
 * ★ R26（DESIGN-A §1.5）：从 [DshRuntime] 内部挪到顶层、去掉 `private`（同 [shouldSignal] /
 *   [canSendQuitSignal] 的惯例）。原因：这条判据原来**零覆盖**，而 `code > 128` 是一刀切 ——
 *   把行为钉进单测是"先钉住现状、再暴露脆弱点"，不是改 bug。
 *
 * ⚠️ 已知脆弱点：`code > 128` 意味着 **143（SIGTERM）也会命中**，而用户点「停止」走的正是
 *   TERM ⇒ 若调用点不先排除，主动停止会被误判成 seccomp 问题并自动重启。
 *   现由唯一调用点 [DshRuntime] 的 `retryIfSeccompLooksGuilty` 的上游（`onProcessExit` 里的
 *   `wasStopRequested` 守卫）先排除。这个依赖是**隐形的**：把重试挪出 `Starting` 分支就会失效，
 *   所以单测 `sigtermAlsoMatches_trapIsMovedToCallSite` 把 143=true 钉住并注明。
 *   本函数**不能**自己判 143 —— 它拿不到 `wasStopRequested`（纯函数、无状态）。
 */
fun looksLikeSeccompTrouble(code: Int, tail: List<String>): Boolean {
    // 被信号杀死：proot 自身或 rootfs 内进程被内核拦下（注：主动停止的 143 在调用点已被排除）
    if (code > 128) return true
    val text = tail.joinToString(" ").lowercase()
    return text.contains("seccomp") || text.contains("ptrace") ||
        text.contains("bad system call") || text.contains("operation not permitted")
}

/**
 * R16/fix4（BL-136）：本行解析出的 [incoming] 是否属于"会覆盖已带 token url"的坏更新。
 *
 * 判据（纯函数，供 [DshRuntime.onProcessLine] 的"已 Running 刷新"分支用）：
 * 当 **新值无 token**（③级 `READY port=` 合成裸 URL，或任何无 token 行）**且旧值带 token** 时，
 * 该更新是"降级"，不许写 —— 否则 fix3 的三级解析在
 * `dsh web:`(带token) → `READY port=`(合成裸) → `READY url=`(带token) 行序下会在中间那步
 * 把 state 的 url 短暂降级成裸 URL（毫秒级窗口；外跳恰在窗口内取 fresh URL 即得 401 隐患）。
 *
 * 允许的更新：新值带 token（升级/换代）、旧值本来就是裸的（初次/同等降级无损失）、
 * 旧值为 null（首写）。写成顶层函数而不是 private：单测 harness 不加载 android 依赖，
 * 顶层落在 `DshRuntimeKt` 静态方法上（同 `canSendQuitSignal` / `pidFileBelongsTo` 的惯例）。
 */
fun syntheticUrlWouldOverwriteToken(current: String?, incoming: String): Boolean =
    UrlScanner.tokenOf(incoming) == null &&
        current != null && UrlScanner.tokenOf(current) != null

/**
 * ★ BL-176（R21-E）：认领孤儿时，候选 token URL 的**监听端口是否等于本实例当前端口**的判据
 *（纯函数，便于单测；无状态、无 Android 依赖）。
 *
 * 背景：[DshRuntime.adoptOrphan] 的三源恢复里，③级 [ProcessUtil.recoverTokenUrlsFromStartLogs]
 * 已按 `expectedPort` 过滤候选（[UrlScanner.portOf] == expectedPort 才留），而 ①pid 文件持久化
 * url 与 ②侧车 `dsh-token-url.txt` 的候选过去只做探活 3xx 判定，不比对端口 —— 口径不一致。
 * 探活 3xx 只证明"该 URL 的端口上有个 dsh 受理这个 token"，**不证明它属于本实例当前端口**
 *（跨实例 token 换代 / 旧 node 残留 / 端口漂移后旧端口 URL 恰好仍被某持有同 token 语义的进程
 * 监听等场景）。采信一个端口与本次认领目标端口不一致的候选，会把 `state.url` 指向别的监听端口。
 *
 * 判据取严（与 ③级同一口径）：
 * - candidate 为空（null / 空串 / 纯空白）→ false（无从判定端口，不采信）；
 * - expectedPort <= 0 → false（目标端口本身不可信，不比对）；
 * - candidate 解析不出端口（裸 host、无 `:port`）→ [UrlScanner.portOf] 返回 null → false；
 * - 其余：candidate 端口 == expectedPort 才 true（`?token=` 等 query 不参与端口判定，
 *   [UrlScanner.portOf] 已只取 host:port 段）。
 *
 * 写成顶层函数而不是 private：单测 harness 不加载 android 依赖，顶层落在 `DshRuntimeKt`
 * 静态方法上（同 [syntheticUrlWouldOverwriteToken] / [pidFileBelongsTo] 的惯例）。
 */
fun adoptionPortMatches(candidate: String?, expectedPort: Int): Boolean {
    if (expectedPort <= 0) return false
    if (candidate.isNullOrBlank()) return false
    return UrlScanner.portOf(candidate) == expectedPort
}

/** 端口连通性探测（判断孤儿进程是否还活着、是否已就绪） */
object Probe {
    fun isPortOpen(port: Int, timeoutMs: Int = 800): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    /**
     * R17（H1/H2 根治）：探测一个带 token 的 URL 是否属于**当前活着的** dsh 进程。
     *
     * 返回值三态（X4 红队 A5 修正：**超时与 401 必须区分**——同为 false 会把"dsh 忙导致
     * 的慢响应"误判成"token 已死"，丢弃一个本可用的 token）：
     * - **3xx**（[HttpURLConnection.getResponseCode]）→ token 被受理（现行实现是 303 重定向；
     *   取 3xx 全族，对未来换 302/307 的 dsh 版本保持兼容）＝ **live**；
     * - **4xx/5xx**（当前实现的 401）→ token 被拒 ＝ **dead**（明确死，换下一个候选）；
     * - **null**（连接异常/超时）→ **unknown**（既不能证活也不能证死；调用方可在"没有更好
     *   候选"时作 tentative 使用——最坏与裸 URL 同效，最好直接救活）。
     *
     * 不跟随重定向、不读 body、GET 一次；探活不消耗 token（X1：token 非一次性）。
     * 超时 2s（回环请求通常 ~10ms；认领在 IO 线程，可短暂等待）。
     */
    fun probeTokenUrl(tokenUrl: String, timeoutMs: Int = 2000): Int? = runCatching {
        val conn = (java.net.URL(tokenUrl).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
        }
        try {
            conn.responseCode
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrNull()

    /** R17 便捷包装：303/302…（3xx）＝ 当前活进程的有效 token（供简化调用点）。 */
    fun tokenUrlIsLive(tokenUrl: String, timeoutMs: Int = 2000): Boolean =
        probeTokenUrl(tokenUrl, timeoutMs)?.let { it in 300..399 } == true
}

/** 从 dsh / 脚本输出里提取本地 URL 与 token */
object UrlScanner {

    /** dsh 自己打印：`dsh web: http://127.0.0.1:3080/?token=XXX (LAN: http://...)` */
    private val AUTH_URL = Regex("""https?://[^\s"']*?:\d+/[^\s"']*?[?&]token=[A-Za-z0-9_\-]+""")

    /** 脚本就绪标记：`[start-dsh] READY url=http://127.0.0.1:3080/?token=XXX` */
    private val MARKER_URL = Regex("""(?i)READY\s+url=(\S+)""")

    /**
     * BL-109（R15）：脚本的**兜底**就绪标记 `[start-dsh] READY port=<n>`。
     * 触发场景：端口可达但 20s 内没捕到 token URL（dsh 换过日志格式等）——脚本报端口，
     * 宿主以 cookie 路径接手（应用内 WebView 有持久 cookie，与 loadBestUrl 的无 token 分支同形态）。
     * 与 [MARKER_URL] 分开解析：URL 解析器保持"只认 url="的语义，既有测试契约不破坏。
     */
    private val MARKER_PORT = Regex("""(?i)READY\s+port=(\d+)""")

    /** 只要 host:port（用于 start-dsh.sh 打印的 `listening http://127.0.0.1:8080` 之类） */
    private val ANY_URL = Regex("""https?://(?:[\w\-.]+|\d+\.\d+\.\d+\.\d+):(\d+)""")

    fun findAuthenticatedUrl(line: String): String? =
        AUTH_URL.find(line)?.value

    fun findMarkerUrl(line: String): String? =
        MARKER_URL.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** 解析 `READY port=<n>`（>0 才返回；见 [MARKER_PORT]） */
    fun findMarkerPort(line: String): Int? =
        MARKER_PORT.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }

    fun portOf(url: String): Int? = runCatching {
        val p = java.net.URI(url).port
        if (p > 0) p else null
    }.getOrNull() ?: ANY_URL.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun tokenOf(url: String): String? =
        Regex("""[?&]token=([A-Za-z0-9_\-]+)""").find(url)?.groupValues?.getOrNull(1)
}

/** 进程与 pid 文件相关的小工具（孤儿进程清理用） */
object ProcessUtil {

    /**
     * pid 文件内容。
     *
     * ★ BL-135（R16/fix4）：新增 [url] 字段（默认 null，旧文件反序列化天然兼容 —— GSON 对缺字段
     * 置 null）。这是"认领孤儿后手里没有 token"问题的修复载体：dsh 的 token 是**进程级**的，
     * 只在该进程启动时打印过一次（`dsh web: http://127.0.0.1:P/?token=X`），App 重启认领孤儿时
     * 无法从任何地方恢复它（日志已脱敏、dsh 自身不落盘 URL）—— 于是认领态的 state.url 只能是
     * 裸 URL，外跳浏览器（首访无 cookie）必然 401。把**最后一个带 token 的 URL** 随 pid 文件
     * 一起持久化（该文件的生命周期与进程一一对应：写于 fork/就绪、删于停止/退出/清理），
     * 认领时读回即可让外跳/重载拿到有效 token。
     *
     * 安全口径：文件在实例私有目录（`<filesDir>/dsh/instances/<id>/dsh.pid`），与
     * `dsh-node.pid`/实例日志同级，不新增暴露面；token 换代后旧值随文件删除/覆盖而消失。
     */
    data class PidMeta(val pid: Long, val port: Int, val url: String? = null)

    fun writePidFile(instanceId: String, pid: Long, port: Int, url: String? = null) {
        // BL-046：失败必须留痕（R12-A2-8 点名）—— 这条文件是"孤儿认领/清理"的唯一凭证，
        // 静默失败 = 该实例的后台残留从此无人能认领/清理，事后只能靠"端口被占"反推。
        // 控制流不变（失败照旧吞掉，不抛给调用方）。
        runCatching {
            val f = DshPaths.instancePidFile(instanceId)
            f.parentFile?.mkdirs()
            // BL-135：url 只存**带 token 的**那种（调用方保证）；裸 URL / null 一律不存
            //（裸 URL 存进去对认领无益，反而让"文件里有没有可用的 token"这个判据变模糊）。
            JsonUtils.GSON.toJson(PidMeta(pid, port, url?.takeIf { UrlScanner.tokenOf(it) != null }))
                .let { f.writeText(it) }
        }.onFailure { e ->
            DshLogBus.appendFor(
                instanceId,
                "[runtime] writePidFile failed for pid=$pid port=$port: ${e.message}; " +
                    "orphan adoption/cleanup will not be available for this instance"
            )
        }
    }

    /**
     * R17（H1/H2/A1 根治）：**当前进程 token URL 侧车文件的读写**（认领恢复的第二恢复源）。
     *
     * 写点：`onProcessLine` 抓到带 token URL 时（不依赖 pid 解析成功 —— 那是 pid 文件 url 字段
     * 覆盖不到的真机常态）；PID 文件 url 字段仍是第一恢复源（同一次写入也照写）。
     * 读点：`adoptOrphan` 在 pid 文件 url 与 start-log 扫描之后、回落裸 URL 之前。
     * 删除点：stop/退出/killStale 的收尾（与 pid 文件同批）——防"死进程的旧 token 冒充"。
     * 读到后一律经探活（三步链的最后一道闸）。
     */
    fun writeTokenUrlFile(instanceId: String, url: String) {
        runCatching {
            val f = DshPaths.instanceTokenUrlFile(instanceId)
            f.parentFile?.mkdirs()
            f.writeText(url)
        }.onFailure { e ->
            DshLogBus.appendFor(
                instanceId,
                "[runtime] writeTokenUrlFile failed: ${e.message}; token recovery after process " +
                    "death will rely on the start logs only"
            )
        }
    }

    fun readTokenUrlFile(instanceId: String): String? {
        val f = DshPaths.instanceTokenUrlFile(instanceId)
        if (!f.isFile) return null
        return runCatching { f.readText().trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() && UrlScanner.tokenOf(it) != null }
    }

    fun deleteTokenUrlFile(instanceId: String) {
        runCatching { DshPaths.instanceTokenUrlFile(instanceId).delete() }
    }

    fun readPidFile(instanceId: String): PidMeta? {
        val f = DshPaths.instancePidFile(instanceId)
        if (!f.isFile) return null
        return runCatching { JsonUtils.fromNonNullJson(f.readText(), PidMeta::class.java) }.getOrNull()
    }

    /**
     * R17（H1/H2 根治）：从 start-dsh.sh 的**实时日志文件**里恢复带 token 的 URL 候选。
     *
     * 背景：`start-dsh.sh`（fix2 至今逐版同构）把 node 的原始输出写进
     * `${TMPDIR}/dsh-start-<pid>.log`（脚本退出时删除）；`/tmp` 在宿主侧映射到
     * `<cacheDir>/dsh/tmp`（见 ProotCommand 的 `--bind`），tmp 不可写时落到
     * `$INSTANCE_DIR/dsh-start-<pid>.log`（宿主侧 = 实例目录）。node 打印的那一行
     * `dsh web: http://127.0.0.1:P/?token=X` 因此**在 App 私有目录里有一份明文**——
     * 进程活着（认领场景）时这份文件也活着。
     *
     * 恢复出的候选**必须再经 [Probe.tokenUrlIsLive] 探活**（303=有效）才可采信：
     * 文件可能是上一代次残留。本函数只负责"找出全部候选、新的在前"，不负责判定。
     *
     * 读取有界（每个文件最多读 4MiB：token 行出现在输出前段；超大文件只读头部，
     * 避免把长会话期间的输出整读进内存）。去重、按文件 mtime 降序（新的更可能是活代次），
     * 总量上限 [MAX_TOKEN_URL_CANDIDATES]。
     *
     * @param tmpDir 宿主侧 `cacheDir/dsh/tmp`（[DshPaths.TMP_DIR]）
     * @param instanceDir 宿主侧实例目录（[DshPaths.instanceDir]；脚本的 fallback 落点）
     * @param expectedPort 只接受**监听端口等于它**的候选 —— `/tmp` 是跨实例共享的，
     *   别的实例的日志文件也在那里；不过滤就可能把别的实例的 token URL 写进本实例的
     *   state/pid 文件（串台）。认领场景"本实例的活进程"必然监听 [PidMeta.port]，
     *   按端口过滤是精确且廉价的隔离判据。
     */
    fun recoverTokenUrlsFromStartLogs(tmpDir: File, instanceDir: File, expectedPort: Int): List<String> {
        val files = mutableListOf<File>()
        runCatching {
            tmpDir.listFiles { f -> f.isFile && f.name.startsWith("dsh-start-") && f.name.endsWith(".log") }
                ?.let { files += it }
        }
        runCatching {
            instanceDir.listFiles { f -> f.isFile && f.name.startsWith("dsh-start-") && f.name.endsWith(".log") }
                ?.let { files += it }
        }
        if (files.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        files.sortedByDescending { it.lastModified() }
            .take(MAX_START_LOG_FILES)
            .forEach { file ->
                if (out.size >= MAX_TOKEN_URL_CANDIDATES) return@forEach
                runCatching {
                    val len = file.length()
                    val text = if (len <= MAX_START_LOG_READ_BYTES) {
                        file.readText()
                    } else {
                        // 超大：只读头部（token 行在输出前段）
                        java.io.RandomAccessFile(file, "r").use { raf ->
                            val buf = ByteArray(MAX_START_LOG_READ_BYTES)
                            val n = raf.read(buf)
                            String(buf, 0, n, Charsets.UTF_8)
                        }
                    }
                    // 逐行扫（与 onProcessLine 同一解析器，口径一致）；端口过滤防跨实例串台
                    text.lineSequence().forEach { line ->
                        UrlScanner.findAuthenticatedUrl(line)?.let { url ->
                            if (UrlScanner.portOf(url) == expectedPort && out.size < MAX_TOKEN_URL_CANDIDATES) {
                                out += url
                            }
                        }
                    }
                }
            }
        return out.toList()
    }

    /** 恢复候选的上限（探活有超时代价，数量要有界） */
    private const val MAX_TOKEN_URL_CANDIDATES = 6

    /** 一次恢复最多扫几个日志文件（按 mtime 新的在前） */
    private const val MAX_START_LOG_FILES = 8

    /** 单文件最多读多少字节（token 行在前段；防超大文件整读） */
    private const val MAX_START_LOG_READ_BYTES = 4 * 1024 * 1024

    /** BL-149：`dsh-start-*.log` 的最长保留龄（7 天）；仅对"不在最新 keep 份"的文件生效（见 [pruneStartLogs]） */
    private const val PRUNE_START_LOG_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * ★ BL-149：轮转清理 `dsh-start-*.log`（**明文 token 日志**，无上限残留的修复）。
     *
     * 事实：`start-dsh.sh` 把 node 的原始输出写 `${TMPDIR}/dsh-start-<pid>.log`（宿主侧 =
     * [DshPaths.TMP_DIR]，cacheDir 下；tmp 不可写时回落**实例目录**）—— 脚本只在**正常退出**
     * 路径自删（含超时/失败路径），被 SIGKILL（App 被杀、proot 被宿主 KILL）后残留；全仓此前
     * 无第二删除方。该文件含 dsh 启动时打印的 `http://127.0.0.1:P/?token=X` **明文 token**
     * （R17/fix5 起它同时是认领恢复的候选源之一，不能一禁了之，所以是"轮转"而不是"禁用"）。
     *
     * 语义（**双保险，顺序要紧**）：
     * 1. 两类目录（共享 tmp + 本实例目录）的 `dsh-start-*.log` 合并，按 mtime **降序**；
     * 2. 最新 [keep] 份**永不删** —— 正在跑的实例的日志必然在这一档里（它刚被创建/刚有输出写入，
     *    全场最新），这一步就是"不删现场、不毁恢复源"的保证；
     * 3. 只在 `drop(keep)` 的候选中**再**删 mtime 早于 7 天的 —— "keep 内永不按 7 天规则删"，
     *    否则"启动很久、之后 node 无输出（mtime 陈旧）的**活**实例"的日志可能被当成老文件删掉
     *    （毁掉它的恢复源）。顺带：最近写入的文件（10 分钟内）由 7 天规则自然覆盖 —— 不可能被删。
     *
     * 容错（全部静默 + 计数，绝不抛）：目录不存在 / `listFiles` 抛异常 / 文件被并发删除
     * （`delete()` 返回 false、mtime 读成 0）都容忍；仅当 mtime **已知**（>0）且确实早于 7 天
     * 才尝试删 —— "不确定就不删"。
     *
     * @return 实际删掉的份数（调用方据此决定要不要记一行日志）
     */
    fun pruneStartLogs(tmpDir: File, instanceDir: File, keep: Int = 6): Int {
        val files = mutableListOf<File>()
        runCatching {
            tmpDir.listFiles { f -> f.isFile && f.name.startsWith("dsh-start-") && f.name.endsWith(".log") }
                ?.let { files += it }
        }
        runCatching {
            instanceDir.listFiles { f -> f.isFile && f.name.startsWith("dsh-start-") && f.name.endsWith(".log") }
                ?.let { files += it }
        }
        if (files.size <= keep) return 0
        val cutoff = System.currentTimeMillis() - PRUNE_START_LOG_MAX_AGE_MS
        var deleted = 0
        files.sortedByDescending { it.lastModified() }
            .drop(keep.coerceAtLeast(0))
            .forEach { f ->
                runCatching {
                    val m = f.lastModified()
                    // m == 0：文件已被并发删掉（或 mtime 读不到）→ 跳过，不计数、不抛
                    if (m > 0 && m < cutoff && f.delete()) deleted++
                }
            }
        return deleted
    }

    /**
     * 删除实例的 proot pid 文件（K03 compare-and-delete）。
     * 仅当文件里仍是 [expectedPid]（或文件已不存在/读不出）时才删 —— "停后秒启"时新进程可能已经
     * 把自己的 pid 写进了这个文件，无条件删会把**新进程**的 pid 文件抹掉，使它变成失联孤儿。
     * [expectedPid] 传 0（拿不到自己的 pid）时等价于"文件里只要不是 0 就不删"，天然不会误删新进程。
     */
    fun removePidFile(instanceId: String, expectedPid: Long) {
        runCatching {
            if (pidFileBelongsTo(readPidFile(instanceId)?.pid, expectedPid)) {
                DshPaths.instancePidFile(instanceId).delete()
            }
        }
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
     * **为什么要有阻塞版**：[DshRuntime.terminateNodeProcess] 是普通（非 suspend）函数，用它按 pid
     * 收掉 rootfs 内 node 时要阻塞等 TERM→KILL 的宽限期。它的调用点（启动前的孤儿清理、[DshRuntime.stop]
     * 的收尾协程、认领巡检）都在 IO 线程上执行（见 [DshRuntime.start] 的 `withContext(Dispatchers.IO)`
     * 与各 `scope.launch`），阻塞等待是安全的；但这段等待**不再持有对象监视器**（BL-007：已从
     * `@Synchronized` 的 [DshRuntime.startLocked] 临界区移出），所以不会再拖住 stopIf / 二次 start。
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
        //
        // ★ fix12（E3-N-11）：判据收进顶层纯函数 [shouldSignal]，且 `cmd == null` **不再放行**
        //   —— 旧实现把"读不到"当通过，等于把不确定当确定，会盲发 TERM→KILL（pid 复用即误杀）。
        val cmd = cmdline(pid)
        if (!shouldSignal(pid, cmd, expectNode)) {
            // 实例级（有归属时）：这条"没杀成"的线索属于发起终止的那个实例 —— 它的实例页上
            // 只有"停止"却看不到下文，就会以为停干净了（而 pid 其实还在，下次启动可能端口冲突）。
            logFor(
                logInstanceId,
                "[runtime] pid=$pid 无法确认归属（cmdline=${if (cmd == null) "unreadable" else "mismatch"}），拒绝发信号"
            )
            // ★ 不再 removePidFileByPid(pid)：拿不到身份就不该丢掉唯一的把手。
            //   pid 文件是"下次还能按它再试一次"的唯一线索；删了，若那个 pid 真是我们的进程
            //   （只是这一瞬间 /proc 读不到），就再也找不回来了。
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
     * ★ BL-046 判定（读全调用链后定）：SIGKILL 直发**保持不变**，只补失败日志。依据：
     * 唯一调用点是 [DshRuntime.start] fork 前的清理段，且紧下一行就是 [DshRuntime.terminateNodeProcess]
     * —— "proot 被 SIGKILL 来不及 kill_all_tracees、rootfs 内 node 可能残留"这一已知缺口，
     * 有 `dsh-node.pid` 这条确定性兜底（R12-A2-8 原文也据此把它定性为"兜底清理"）。升级为
     * "SIGQUIT→等→KILL"三段式只在"node pid 文件也缺失"的边角上多救一层，代价却是给启动路径
     * 加一段秒级等待（与 BL-047"启动别被清理拖住"的方向相反）；且该边角的后果（孤儿 node
     * 占端口）已有端口避让（`resolveLaunchPort`）兜底，有界。故行为不动，仅加日志（R-LOG）。
     *
     * ★ 这两行保持**全局**：它属于"pid 文件 + 孤儿进程"这套跨实例的清理机制
     *   （`removePidFileByPid` 会扫所有实例目录找同一个 pid），是启动器在整理**进程现场**，
     *   而不是某个实例自己跑出来的输出。放到实例流里会让实例页混进"别人的 pid 被忽略"这种
     *   与它无关的行。
     */
    fun killStale(instanceId: String) {
        val meta = readPidFile(instanceId) ?: return
        if (!isAlive(meta.pid)) {
            removePidFile(instanceId, meta.pid)
            return
        }
        val cmd = cmdline(meta.pid) ?: ""
        if (cmd.contains("proot") && cmd.contains(instanceId)) {
            DshLogBus.append("[runtime] 清理实例 $instanceId 的残留进程 pid=${meta.pid}")
            runCatching { android.os.Process.sendSignal(meta.pid.toInt(), 9) }
                .onFailure { e ->
                    DshLogBus.append(
                        "[runtime] killStale SIGKILL failed for pid=${meta.pid} (instance=$instanceId): ${e.message}"
                    )
                }
        } else {
            DshLogBus.append("[runtime] pid=${meta.pid} 已不属于本实例，忽略")
        }
        removePidFile(instanceId, meta.pid)
    }
}
