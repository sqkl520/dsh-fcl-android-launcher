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
import java.util.concurrent.atomic.AtomicLong

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
 *
 * ## 失败原因要送到界面（改造前后的差别）
 * 改造前：只有脚本退出码能说明成败，失败时界面统一显示"安装失败（详见日志）" ——
 * 真实原因（比如 `npm error code EAI_AGAIN`，DNS 解析不了）就在日志里，却一个字都传不出来。
 * 现在三层：
 *   · [errorSummaries]：代码自己写的原因（预检失败、异常、超时、空间不足）；
 *   · [errorTails] + [describeFailure]：npm / setup 脚本输出的错误尾部，能归类的翻成人话；
 *   · 泛化文案 `dsh_install_failed_generic`：实在说不出所以然时的兜底。
 * 详见 [errorSummary] 与 [isNpmErrorLine]。
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
        /** [version] 为本次失败的目标版本（安装开始时会写入实例），供界面只清除对应版本的"安装中"标记 */
        data class Failed(val reason: String, val version: String? = null) : Progress()

        /**
         * 用户取消了这次安装（本次新增）。
         *
         * ★ 为什么**必须**新增这一种，而不是复用 [Failed]：
         *   取消要发一条**事件**给订了 [progress] 的界面（下载页的"安装中"标记等的就是这条事件）。
         *   复用它的话，界面上会先闪一句"安装失败：xxx"，而用户明明是自己点的取消 —— 那正是真机上
         *   被投诉的误导（"取消了却说安装失败"）。语义不同的东西不能共用一个信封。
         *
         * ★ 为什么**必须**发这条事件：改造前 [cancel] 只打标记、杀进程、清 [statuses]，**一个
         *   `Progress` 都不发**。于是订阅 `progress` 的界面永远等不到终态，"安装中"标记就留在原地，
         *   只能重启 App 才恢复（真机 bug 之一）。终态是事件，取消路径当初缺的就是"说出来"这一步。
         *
         * [version] 与 [Failed] 同义：本次取消的目标版本，供界面只清掉**对应版本**的标记
         * （同时装两个版本时，取消一个不该让另一个的标记也消失）。
         */
        data class Cancelled(val version: String?) : Progress()
    }

    /**
     * 网络类失败的分类（[classifyNetworkFailure] 的返回值）。
     *
     * 为什么要分类而不是直接把 npm 的 errno 给用户看：`EAI_AGAIN` / `ETIMEDOUT` 这些码对
     * 开发者有意义，对用户没有；而"DNS 解析失败 / 网络超时 / 网络不可达"是他立刻能判断
     * "是不是我的网"的三种情况，也是三种**不同的**下一步动作（换网络 / 重试 / 检查网络开关）。
     */
    enum class NetworkFailure {
        /** 域名解析不了：`EAI_AGAIN` / `ENOTFOUND` / `getaddrinfo` */
        DNS,

        /**
         * 连得上但超时，或连了一半被切断：`ETIMEDOUT` / `ECONNRESET` / socket hang up。
         * ★ `ECONNRESET` 归这一类而不是"不可达"：它表示连接**曾经建立**又被重置
         *   （半死的网络、WiFi 与蜂窝之间切换），下一步是重试；不可达则是压根没通。
         */
        TIMEOUT,

        /** 网络本身不可达/没开：`ENETUNREACH` / `ENETDOWN` / `EHOSTUNREACH` */
        UNREACHABLE,

        /** 确认是网络类失败，但归不到上面三类（npm 的 `npm error network ...` 措辞） */
        OTHER
    }

    /** 某个实例的安装状态（可被界面随时读取，界面重建不丢） */
    data class InstallStatus(
        val instanceId: String,
        val version: String,
        val stage: String,
        /** 0..1；无法估算时为 null */
        val fraction: Double? = null,
        val running: Boolean = true,
        val error: String? = null,
        /**
         * R20：**实时活动行**（"现在正在抓哪个包"这类高频变化的短文本；无活动为 null）。
         *
         * 为什么需要：npm 在非 TTY 下（脚本里就是）默认一行不输出，`--loglevel=http` 之后
         * 每个抓取打一行 `npm http fetch GET 200 <url> <ms>`。stage 是"阶段级"低频文案
         * （几分钟不变），活动行是"秒级"滚动细节 —— 卡片/任务区拿它显示连续的下载活动，
         * 用户能看着它动（真机反馈："屏幕上没有任何一处地方在动了，看起来像死机"）。
         * 由 [extractNpmActivity] 从 http 行提炼成 `↓ 包名` / `✓ 包名`（BL-175：不再带 npm 的
         * 累计 ms，包名按 `/-/` 回溯以保住 scoped 的作用域名）。
         */
        val activity: String? = null
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

    /**
     * BL-043②：把"闸门 + [running] 登记 + 实例终态写"的所有权变更收进同一把锁的临界区。
     *
     * 四处临界区（缺一不可）：[install] 的"占闸 + 登记 + 启动"、任务体终态裁决
     * （[resolveInstallOutcome]/[resolveInstallFailure]，含其中的状态写入）、任务体 finally 的
     * "摘登记 + 判归属 + 放闸 + flush"、[cancel] 的"守卫复核 + 放闸 + 清状态"。没有它时，
     * 被取消的旧任务 finally 落在新任务"占闸成功、尚未登记"的窗口里会误摘登记并误放**新**闸门
     * （K14 活体路径），且 cancel 与终态裁决可以互相覆盖（刚 READY 就被标回 NOT_INSTALLED）。
     * 完整推演见 [install] 与 [cancel] 的方法注释。
     *
     * 锁内只有同步的内存操作与小文件读（终态裁决判"已装完"时读一个 package.json，亚毫秒级），
     * 不等待 IO、不等待任何协程（state 写与 [DshLogBus] 的锁都是无环的叶子锁）—— 不会死锁，
     * 也不会把用户操作阻塞到可感知的量级。
     */
    private val ownershipLock = Any()

    /**
     * BL-053 节流状态，按实例分开（同时装两个实例时，一个的输出高峰不该压掉另一个的发射窗口）。
     *
     * · [lastLogEmitMs]：上次 [Progress.Log] 发射时刻（毫秒；0/缺省 = 尚未发射过）。
     * · [pendingLogLines]：节流抑制期的尾部行缓冲（有界，上限 [PENDING_LOG_MAX_LINES]，
     *   超出的从旧到新丢 —— 采样语义：只保"最后几行"）。收尾 [flushPendingInstallLog]
     *   补发，保证安装结尾的几行不丢。
     *
     * 线程约定：onLine 正常情况下与 runInstall 同线程（proot 输出在协程线程上同步读），
     * 例外是看门狗线程的超时行（单次事件）。deque 自带的 synchronized 防御那一手；
     * "同一行只进一条流"由 [DshLogBus.appendFor]（全量通道、永不节流）保证，
     * 本节流只影响 [Progress.Log] 这个 UI 采样通道。
     */
    private val lastLogEmitMs = ConcurrentHashMap<String, Long>()

    /**
     * R20：每个实例上次**活动行**（[InstallStatus.activity]）写入的时刻。
     * 与 [lastLogEmitMs] 同一理由按实例分开；窗口见 [ACTIVITY_THROTTLE_MS]。
     * 安装收尾（[runInstall] 的 finally 与 [cancel]）与 [lastLogEmitMs] 一并清理。
     */
    private val lastActivityEmitMs = ConcurrentHashMap<String, Long>()
    private val pendingLogLines = ConcurrentHashMap<String, ArrayDeque<String>>()

    /** 已被用户取消的实例：取消后 proot 会以非 0 退出，不能被误报成"安装失败(BROKEN)" */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /**
     * npm 错误尾部（[errorTails]）/ 预检失败原因与异常消息（[errorSummaries]），**按实例分开存**。
     * 旧实现是单例上的一个字段：同时安装两个实例时，A 的失败摘要会被 B 的输出冲掉，
     * 用户看到的是"另一个实例"的错误信息（很难查）。
     *
     * 两者的分工见 [errorSummary]：`errorSummaries` 里放的是**代码自己写的**原因（预检失败、
     * 异常消息、超时、空间不足），优先级最高；`errorTails` 是 npm / setup 脚本输出的原文尾部，
     * 是最后一道"总比不说强"的兜底（[describeFailure] 会顺手把能翻译的翻成人话）。
     */
    private val errorTails = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val errorSummaries = ConcurrentHashMap<String, String>()

    /**
     * 本次安装的**目标版本**（F2-8 / BL-048），key = instanceId。
     *
     * ★ 为什么需要它：[fail] 原来把 `instance.dshVersion` 塞进 [Progress.Failed]，而新建实例的
     *   `dshVersion` 快照是 null —— 下载页收到 `version == null` 时 `p.version?.let { ... }`
     *   跳过清理，「安装中」标记就永久留在那一行（必现）。目标版本只存在于**本次请求**里
     *   （install() 的 version 参数），所以入口 put、收尾 remove，与 [running] 同一生命周期。
     *
     * ★ 为什么用 map 而不是单个字段：同时装两个实例时，单字段会被后一次安装覆盖，
     *   先失败的那个又拿不到版本（与 [errorSummaries] 按实例分开存同一个理由）。
     */
    private val installVersions = ConcurrentHashMap<String, String>()

    /** 安装 pid 文件：[实例目录]/dsh-install.pid（与运行期的 dsh.pid / dsh-node.pid 同目录，生命周期独立） */
    private fun dshInstallPidFile(instanceId: String): File =
        File(DshPaths.instanceDir(instanceId), INSTALL_PID_FILE_NAME)

    /**
     * 把刚解析出的 proot pid 写进 `dsh-install.pid`（F2-6 / BL-041）。
     *
     * 格式与 [ProcessUtil.writePidFile] 完全一致（PidMeta 的 JSON；安装期没有运行端口概念，
     * port 恒 0），不写 token/URL —— 与 dsh.pid 同一隐私口径。
     *
     * ★ 必须**同步**写（不经 [DshLogBus] 的异步落盘）：App 被 LMK 杀掉时来不及刷任何异步队列，
     *   而这个 pid 的唯一用途恰好就是"App 没了之后"被下一次安装读到。
     */
    private fun writeInstallPidFile(instance: DshInstance, pid: Long) {
        // 写文件与日志分开：日志本身不该再被 runCatching 卷进来 —— 否则"日志 bus 出错"会被
        // 误报成"pid 文件写失败"（诊断日志要如实反映文件写的成败）。
        val failure = runCatching {
            val f = dshInstallPidFile(instance.id)
            f.parentFile?.mkdirs()
            f.writeText(JsonUtils.GSON.toJson(ProcessUtil.PidMeta(pid, 0)))
        }.exceptionOrNull()
        if (failure == null) {
            DshLogBus.appendFor(instance.id, "[install] installer pid=$pid written to $INSTALL_PID_FILE_NAME")
        } else {
            DshLogBus.appendFor(instance.id, "[install] failed to write $INSTALL_PID_FILE_NAME: ${failure.message ?: failure}")
        }
    }

    /** 读 `dsh-install.pid`（与 [ProcessUtil.readPidFile] 同一格式与容错口径）；无文件/坏文件/pid<=0 → null */
    private fun readInstallPidFile(instanceId: String): Long? {
        val f = dshInstallPidFile(instanceId)
        if (!f.isFile) return null
        return runCatching {
            JsonUtils.fromNonNullJson(f.readText(), ProcessUtil.PidMeta::class.java).pid.takeIf { it > 0 }
        }.getOrNull()
    }

    /**
     * 安装启动前的孤儿清理（F2-6 / BL-041 的"认领"半边）：上次安装期间 App 被系统杀掉 /
     * LMK 后，npm（proot）可能仍活着继续写 node_modules —— 不等用户重新点安装就变成双写。
     * 所以在起新 npm 之前先把残留进程收掉。
     *
     * 判据（与 [ProcessUtil.killStale] 同构，宁可不杀不误杀）：
     * 1. pid 文件存在且能解析出 pid；2. pid 仍存活；
     * 3. `/proc/<pid>/cmdline` 同时含 `proot` 与本实例 id（cmdline 读不到/不匹配 → 放手，只删文件）。
     *
     * 清理动作 = **SIGQUIT → 等待 → SIGKILL 兜底**。为什么不是直接 SIGKILL（R14-V2A F1 修正）：
     * 本仓自身的既有结论是 proot 对 SIGTERM 装死、SIGKILL 又来不及跑 kill_all_tracees
     * （rootfs 内的 node/npm 会以宿主视图继续存活，正是"防双写"要拦的东西）；只有 SIGQUIT
     * 会让 proot 优雅收尾并带走全部 tracee（同 [ProotProcessExecutor] 的 destroyActive 口径）。
     * SIGQUIT 预算 2 秒，超时仍换 SIGKILL 兜底（它至少能保证 proot 本身死透）。
     * 收尾后删 pid 文件 —— 不删文件会让下一次安装重复检查同一个死 pid。
     */
    private suspend fun cleanupStaleInstallProcess(instance: DshInstance) {
        val f = dshInstallPidFile(instance.id)
        val pid = readInstallPidFile(instance.id)
        if (pid == null) {
            // 大多数情况：文件不存在（上次安装正常收尾已删）。顺手删掉"存在但读不出"的坏文件。
            runCatching { f.delete() }
            return
        }
        if (!ProcessUtil.isAlive(pid)) {
            DshLogBus.appendFor(instance.id, "[install] stale installer pid=$pid is not alive; removed stale pid file")
            runCatching { f.delete() }
            return
        }
        val cmd = ProcessUtil.cmdline(pid).orEmpty()
        if (cmd.contains("proot") && cmd.contains(instance.id)) {
            DshLogBus.appendFor(
                instance.id,
                "[install] stale installer pid=$pid still running (proot for this instance); SIGQUIT then KILL fallback"
            )
            // SIGQUIT：唯一能让 proot 带走 tracee（旧 npm）的信号（理由见方法头注释）
            val quitSent = runCatching { android.os.Process.sendSignal(pid.toInt(), 3) }.isSuccess
            if (quitSent) {
                val deadline = System.currentTimeMillis() + STALE_INSTALL_QUIT_GRACE_MS
                while (System.currentTimeMillis() < deadline && ProcessUtil.isAlive(pid)) {
                    kotlinx.coroutines.delay(100)
                }
            }
            if (ProcessUtil.isAlive(pid)) {
                DshLogBus.appendFor(instance.id, "[install] stale installer pid=$pid survived SIGQUIT grace; SIGKILL fallback")
                runCatching { android.os.Process.sendSignal(pid.toInt(), 9) } // SIGKILL
            } else {
                DshLogBus.appendFor(instance.id, "[install] stale installer pid=$pid exited within SIGQUIT grace")
            }
        } else {
            // pid 已被系统复用或属于别的进程：不动它
            DshLogBus.appendFor(instance.id, "[install] pid=$pid in $INSTALL_PID_FILE_NAME is not this instance's proot (cmdline mismatch); left it alone")
        }
        runCatching { f.delete() }
    }

    /**
     * 终态清理（K03 compare-and-delete，与 [ProcessUtil.removePidFile] / DshRuntime 的
     * deleteNodePidFile 同一约定）：只有文件里仍是**本次**安装的 pid（或文件已不存在/读不出）
     * 才删 —— 防"取消后立刻重装"时被取消那次的收尾误删新一次安装刚写下的 pid 文件，
     * 让新安装失去孤儿保护。
     *
     * [expectedPid] 传 0（本次没解析到 pid、没写过文件）时等价于"文件里只要有别的东西就不删"。
     */
    private fun deleteInstallPidFile(instanceId: String, expectedPid: Long) {
        runCatching {
            if (pidFileBelongsTo(readInstallPidFile(instanceId), expectedPid)) {
                dshInstallPidFile(instanceId).delete()
            }
        }
    }

    fun isInstalling(instanceId: String): Boolean = gate.isRunning(instanceId)

    fun statusOf(instanceId: String): InstallStatus? = _statuses.value[instanceId]

    /**
     * 安装指定版本到实例。会把实例状态置为 INSTALLING，成功后置 READY 并记录**真实**版本。
     *
     * ## BL-043②：为什么"占闸 + 登记 + 启动"必须是一段临界区
     *
     * 修复前的三行（占闸 → 以后某个时刻登记 → start）与任务体 finally 的
     * "摘登记 → 放闸" 交错时，存在一条真正能把单飞打穿的路径（K14 活体路径）：
     *
     * ```
     * 旧任务 A（被取消，阻塞在 proot 上还没跑到 finally）
     * ── cancel() 放闸（让用户可以马上重装）──►
     * 新任务 B：gate.tryAcquire 成功 ………（窗口）……… running[id] = jobB
     *                    ▲
     * 旧任务 A 的 finally 恰好落在这里：
     *   running.remove(id, jobA) —— 此刻表里还是 jobA（B 尚未登记）→ 误判"仍归我管"
     *   → gate.release(id) 放掉的是 **B 刚占到的闸门**
     * ```
     *
     * 之后任意第三方安装请求都能再次 tryAcquire 成功 —— 两个 npm 同时写同一个
     * node_modules（正是单飞要拦的事）。把四处所有权变更（此处、任务体终态裁决与 finally、
     * [cancel]）收进同一个 [ownershipLock] 临界区后，"B 的占闸与登记"和"A 的摘登记与放闸"
     * 不可能交错：要么 A 先（放掉的是自己那口闸、B 之后正常拿新的），要么 B 先（A 的 remove
     * 失败、不碰任何新状态），没有第三种结局。
     *
     * @return true 表示本次已受理；false 表示该实例已有安装在跑（会被忽略）
     */
    fun install(instance: DshInstance, version: String): Boolean {
        synchronized(ownershipLock) {
            if (!gate.tryAcquire(instance.id)) {
                // 实例级：说的是"这个实例已有安装在跑"，用户点开它的日志页该看到这次请求为什么被忽略
                DshLogBus.appendFor(instance.id, "[install] ${instance.name} 已有安装任务在进行，忽略重复请求")
                return false
            }
            errorSummaries.remove(instance.id)
            errorTails.remove(instance.id)
            // BL-053：节流状态与共享状态同一所有权规则 —— 新安装从零开始。旧任务 drain 中
            // 的迟到行已被 [onInstallLine] 的归属门拦住，不会再写这两张表（见字段注释）。
            pendingLogLines.remove(instance.id)
            lastLogEmitMs.remove(instance.id)
            lastActivityEmitMs.remove(instance.id)
            // F2-8（BL-048）：目标版本只存在于本次请求里 —— 存下来供 [fail] 写进 Progress.Failed
            // （新建实例的 dshVersion 快照为 null，界面收不到版本就清不掉「安装中」标记）。
            // 与其它共享状态同一套所有权规则：入口 put，任务体 finally 里"仍归我管"才 remove。
            installVersions[instance.id] = version
            DshInstances.markState(instance.id, DshInstance.State.INSTALLING, version = version, error = null)
            updateStatus(
                InstallStatus(instance.id, version, DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_stage_prepare, "install failed"))
            )
            // start = LAZY：先把 job 登记进 running 再 start()，确保"任务体"不可能先跑完、
            // 再被这里把已经结束的 job 写回表里（那会让 isInstalling 永远为真）。
            // 注意：登记与 start 都必须和上面的占闸留在**同一个**临界区里（理由见方法头）。
            val job = DshAppScope.scope.launch(start = CoroutineStart.LAZY) {
                installJobBody(instance, version, coroutineContext[Job])
            }
            running[instance.id] = job
            // ★ cancelled 标记清除放在**登记之后**（顺序不可调）：被取消旧任务的 drain 尾行
            //   在 [onInstallLine] 的归属门里读"running 还是不是我 + 有没有取消标记"，
            //   而那个检查不开锁 —— 若这里提前清掉 cancelled，"cancelled 已清、running 还指着
            //   旧任务"的区间里旧尾行会被误当成活跃任务写共享状态（幽灵条目）。放在登记之后：
            //   该区间里 running 已指向新任务 → 归属门恒假；登记之前 cancelled 仍在 → 归属门
            //   也恒假。两个区间拼起来无缝隙。
            cancelled.remove(instance.id)
            job.start()
        }
        return true
    }

    /**
     * 安装任务体的协程主体（从 [install] 抽出，便于把整个"占闸 + 登记"动作收进临界区，
     * 见 [install] 的 BL-043② 说明）。行为与原内联 lambda 逐字一致。
     *
     * ★ BL-043①/②：**终态裁决（[resolveInstallOutcome]/[resolveInstallFailure]）与 finally 的
     *   "摘登记 + 放闸"全部持 [ownershipLock]**，与 [cancel] 的"守卫复核 + 清状态 + 放闸"、
     *   [install] 的"占闸 + 登记"全序化。这是"cancel 不能把刚 READY 的实例标回 NOT_INSTALLED"
     *   的最后一段拼图：只靠守卫读两遍还不够 —— 守卫通过之后，本任务恰好写完 READY 再被
     *   cancel 的 NOT_INSTALLED 覆盖，仍会丢结果。同锁后两种顺序都是确定的：
     *   要么裁决先落（cancel 复核时 statuses 已清 → no-op），要么 cancel 先（裁决看到
     *   cancelled 标记 → 转"其实已装完/已取消"分支），没有"互相覆盖"的第三种结局。
     *   锁内的磁盘读（[readInstalledVersion] 小文件）亚毫秒级，对 cancel 点击无可感影响；
     *   锁序上 ownershipLock 永远最外层（内层只有 DshLogBus/StateFlow 叶子锁，无环）。
     *
     * @param myJob 本次任务的 Job 身份（登记表 [running] 里登记的也是它），
     *              用来判断共享状态是否还归本次任务管（[isCurrentOwner]）
     */
    private suspend fun installJobBody(instance: DshInstance, version: String, myJob: Job?) {
        try {
            val ok = runInstall(instance, version, myJob)
            synchronized(ownershipLock) { resolveInstallOutcome(instance, version, myJob, ok) }
        } catch (t: Throwable) {
            synchronized(ownershipLock) { resolveInstallFailure(instance, myJob, t) }
        } finally {
            // ★ 只有"登记表里还是我"时才清理共享状态。
            // cancel() 会立刻放掉单飞闸门（让用户可以马上重装），新的安装随后接管 running[instanceId]；
            // 旧任务若仍然无条件 release / remove，就会把**新任务**的闸门放掉（于是能再起一个 npm，
            // 两个进程同时写同一个 node_modules），并抹掉新任务的错误摘要与取消标记。
            // ★ BL-043②：这段"摘登记 + 判归属 + 放闸"与 [install] 的"占闸 + 登记"共用
            //   [ownershipLock] 临界区 —— 否则两者之间那条"B 已占闸、尚未登记"的窗口里，
            //   这里的 remove(id, jobA) 会误判"仍归我管"并把 B 的闸门放掉（完整推演见 [install]）。
            synchronized(ownershipLock) {
                if (running.remove(instance.id, myJob)) {
                    // BL-053：收尾 flush —— 节流期间被抑制的尾行在这里补发（"最后几行不丢"）。
                    // 放在"仍归我管"分支里：被接管时 pending 留给新任务的收尾统一 flush，
                    // 避免旧任务把新任务缓冲里的行一起清掉（跨任务少几条采样，全量日志不受影响）。
                    flushPendingInstallLog(instance.id)
                    cancelled.remove(instance.id)
                    errorSummaries.remove(instance.id)
                    errorTails.remove(instance.id)
                    installVersions.remove(instance.id)
                    gate.release(instance.id)
                }
            }
        }
    }

    /**
     * 终态裁决（成功后写 READY / 取消后按磁盘事实处置 / 失败走 [fail]）。
     *
     * 由 [installJobBody] 在 [ownershipLock] **临界区内**调用（理由见 [installJobBody] 头注释：
     * 与 [cancel] 的状态写互斥，"刚 READY 就被标回 NOT_INSTALLED"在此彻底关闭）。
     * 逻辑与抽出前的内联版本逐字一致，只多了一处：取消且已装完的分支补 [finishStatus] ——
     * 取消后的迟到 STAGE 行可能把条目加回，不补清尾会永远留一行"安装中"投影。
     */
    private fun resolveInstallOutcome(instance: DshInstance, version: String, myJob: Job?, ok: Boolean) {
        if (!isCurrentOwner(instance.id, myJob)) {
            // 本次任务已经被"取消后重新发起的那次安装"接管：再写状态就是改别人的数据
            // （旧实现会把新安装的实例标成 BROKEN、把进度写成失败，界面显示"安装失败"而其实在装）
            // 实例级：讲的是**这个实例**的这次安装的结局（被后一次接管），属于它的日志
            DshLogBus.appendFor(instance.id, "[install] ${instance.name} 本次安装已被更新的一次安装接管，忽略本次结果")
            return
        }
        if (cancelled.contains(instance.id)) {
            // 用户取消了：不能用 npm 的失败输出把实例标成 BROKEN。
            // 但如果文件其实已经装完整（取消来得太晚），按磁盘事实标记 READY，
            // 否则状态会卡在"安装中"直到下次启动 App 才被 repair() 纠正。
            // ★ BL-054：判定改为 [readInstalledVersion] 的完整判据（package.json + lib/bin.js
            //   + 与本次请求版本相符）——"已装完"必须是"装的是要的那个版本且能启动"。
            val resolved = readInstalledVersion(instance, version)
            if (resolved != null) {
                DshInstances.markState(instance.id, DshInstance.State.READY, resolved)
                finishStatus(instance.id)
                DshLogBus.appendFor(instance.id, "[install] ${instance.name} 取消时其实已装完：dsh $resolved")
                // ★ 这里再报一条 DONE，而不是让"已取消"当成最终结论：
                //   取消那一刻报的 CANCELLED 描述的是**用户动作**，而磁盘事实是"它其实装完了"。
                //   两条都是真事，按事件记录（[DshTasks.finished] 是事件列表，不是状态表），
                //   最新的在前，用户先看到的是真正的结局；若只留"已取消"，实例卡片显示
                //   READY、任务区显示"已取消"，两处对不上反而更费解。
                DshTasks.report(doneTask(instance, resolved))
            } else {
                // 取消的"常态"路径：CANCELLED 已经由 [cancel] 在用户点击那一刻报过了，
                // 这里**不再重复上报** —— 重复报只会让任务区为同一次取消出现两行一样的"已取消"。
                // ★ 清尾（BL-043① 守卫判据的保真配套；同 BL-104 的"尾行重写 statuses"面）：
                //   取消之后、proot 死透之前到达的迟到 STAGE 行会把刚清掉的条目加回来
                //   （[onInstallLine] 的归属门已拦一道，这里是终态兜底）—— 不清掉的话任务行投影
                //   会永远画着一行"安装中"，也是"该实例还有安装任务在跑"这一守卫判据的失真源。
                finishStatus(instance.id)
                DshLogBus.appendFor(instance.id, "[install] ${instance.name} 的安装已取消，忽略本次结果")
            }
        } else if (ok) {
            val resolved = readInstalledVersion(instance, version)
            if (resolved == null) {
                // 退出码 0 但装不完整：明确报损坏，别让用户以为能用了
                fail(instance, DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_install_incomplete, "install failed"))
            } else {
                DshInstances.markState(instance.id, DshInstance.State.READY, resolved)
                _progress.value = Progress.Done(resolved)
                finishStatus(instance.id)
                // 实例级：装完是这个实例状态跃迁的关键一行（"什么时候能启动了"）
                DshLogBus.appendFor(instance.id, "[install] ${instance.name} 安装完成：dsh $resolved")
                // 终态上报：任务区要能看到"安装完成 dsh <版本>"，而不是行一消失什么都不剩
                DshTasks.report(doneTask(instance, resolved))
            }
        } else {
            fail(instance, errorSummary(instance.id))
        }
    }

    /** [resolveInstallOutcome] 的异常孪生：runInstall 抛出时的终态裁决（同样在 [ownershipLock] 内）。 */
    private fun resolveInstallFailure(instance: DshInstance, myJob: Job?, t: Throwable) {
        if (!isCurrentOwner(instance.id, myJob)) {
            DshLogBus.appendFor(instance.id, "[install] ${instance.name} 本次安装已被接管，忽略异常：${t.message ?: t}")
        } else if (!cancelled.contains(instance.id)) {
            fail(instance, t.message ?: t.toString())
        } else {
            DshLogBus.appendFor(instance.id, "[install] ${instance.name} 的安装已取消（${t.message ?: t}）")
        }
    }

    /**
     * 取消某实例的安装（界面上的"取消"）：取消协程 + 杀掉**本实例**正在跑的 proot 子进程。
     *
     * ★ BL-043① **入口守卫**：任务行是"投影"—— [DshTasks.tasks] 每帧从 [statuses] 重算，
     *   而任务到达终态后投影要等下一轮收集才摘行，中间隔着若干帧。那几帧里用户点下的"取消"
     *   会落到一个**已经没有在跑的安装任务**的实例上：旧代码无条件把它标回 NOT_INSTALLED，
     *   把刚写下的 READY（或 BROKEN 的失败展示）抹掉。
     *
     *   判据（[canCancelInstall] 纯函数）两条都要：
     *   1. [statuses] 里该实例仍有进行中条目 —— 它就是任务行投影的数据源，"投影上还画着
     *      一行可取消的任务"等价于"statuses 里还在"；
     *   2. [running] 里仍有登记的任务 —— 防"statuses 幽灵条目"（取消清空后、proot 收尾前
     *      迟到的 STAGE 行会重新 updateStatus 加回一条；终态各分支已补 [finishStatus] 清尾，
     *      此条为双保险，也覆盖"条目在但任务早已不在"的一切未知路径）。
     *   gate 不做判据：cancel 会**立刻**放闸（让用户能马上重装），"已取消、旧任务还在收尾"
     *   的窗口里 gate 已经为空。
     *
     *   守卫读两遍（入口一次、[ownershipLock] 内复核一次）：任务体终态裁决同样持锁
     *   （见 [installJobBody]），复核通过后"statuses 在 + 任务在登记表"这份事实在锁内
     *   不会再被任务体改成相反 —— 不存在"守卫通过后被终态抢先"的窗口。
     *
     * ★ 注意：这里**不**把 [running] 里的登记删掉 —— 登记同时代表"共享状态（错误摘要、闸门）
     * 当前归哪个任务管"。让它自己在 finally 里收尾（[isCurrentOwner] 判断），
     * 既保留"取消时其实已装完 → 标 READY"的判定，也避免旧任务的收尾动作伤到下一次安装。
     *
     * ★ BL-043②：下面"标记 + 取消 job + 放闸 + 杀进程 + 清状态"整段收进 [ownershipLock] ——
     *   改的正是"闸门 + 登记 + 实例终态"的所有权状态；与 [install] 的"占闸 + 登记"、
     *   任务体终态裁决/finally 共用同一临界区后，所有写者全序化（完整推演见 [install]）。
     *
     * ★ **上一轮补上的终态上报**（治"取消后界面还显示安装中"）：
     *   1. 目标版本要在**清 [statuses] 之前**读出来 —— 那一步正是把版本信息抹掉的那一步，
     *      读晚了就只能发一条 `version == null` 的取消事件，界面无从知道该清哪个版本的标记。
     *   2. 发 [Progress.Cancelled]：订阅 [progress] 的界面（下载页）等的是**事件**，
     *      而这里原来一个事件都不发 —— 订户永远收不到终态，"安装中"标记只能等重启 App。
     *   3. 上报 [DshTasks.report]（state = CANCELLED）：任务区要能显示"已取消"这个**结果**。
     *      没有它，任务行在取消的瞬间直接消失（进行中列表是每帧重算的投影），用户看不出发生了什么。
     */
    fun cancel(instanceId: String) {
        // BL-043①：入口守卫（判据与两遍读的理由见方法注释）。
        val status = _statuses.value[instanceId]
        if (!canCancelInstall(hasRunningInstall(status), hasLiveInstallTask(instanceId))) {
            logIgnoredCancel(instanceId, "no install task in progress")
            return
        }
        val error = DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_install_cancelled, "install failed")
        // 版本在**锁内复核后的 status** 上现读（见方法注释第 1 点）——从入口读到的那份可能已在
        // "取消后秒重装"的接管中过期（读到 V1、取消的却是 V2 的任务，终态事件就会带错版本，
        // 下载页清不掉 V2 的「安装中」标记）。锁内读取则与 was-cancelled 的任务版本一致。
        var version: String? = null
        var cancelledNow = false
        synchronized(ownershipLock) {
            // 锁内复核：挡"守卫通过后、动手前任务体恰好完成终态裁决"的最后一帧
            val current = _statuses.value[instanceId]
            if (canCancelInstall(hasRunningInstall(current), hasLiveInstallTask(instanceId))) {
                version = current?.version
                // 先打标记再取消：任务体是阻塞在 proot 上的（协程取消打断不了阻塞调用），
                // 它会等到进程被杀、runInstall 返回非 0 之后才继续；如果没有这个标记，
                // 用户点"取消"会看到实例变成 BROKEN（"安装失败"）而不是"已取消"。
                cancelled.add(instanceId)
                running[instanceId]?.cancel()
                gate.release(instanceId)
                // 杀进程与状态清理也留在锁内：release 之后新安装随时可能占闸登记（若从中
                // 断开，这里的 killActive/清 statuses 会误伤新任务或抹掉它的条目）。
                killActive(instanceId)
                _statuses.update { it - instanceId }
                DshInstances.markState(instanceId, DshInstance.State.NOT_INSTALLED, error = error)
                cancelledNow = true
            }
        }
        if (!cancelledNow) {
            // 与入口守卫同一口径：写英文诊断、不碰任何状态（终态裁决已经落地，本次点击即迟到帧）
            logIgnoredCancel(instanceId, "install already finished")
            return
        }
        _progress.value = Progress.Cancelled(version)
        DshTasks.report(
            DshTask(
                id = DshTasks.installTaskId(instanceId),
                kind = DshTask.Kind.INSTALL,
                title = DshInstances.byId(instanceId)?.name ?: version ?: instanceId,
                stage = error,
                action = DshTask.Action.NONE,
                state = DshTask.State.CANCELLED,
                instanceId = instanceId
            )
        )
    }

    /** 该实例当前的安装登记是否仍是 [job] 本人（没有被 cancel 之后的新安装接管） */
    private fun isCurrentOwner(instanceId: String, job: Job?): Boolean =
        job != null && running[instanceId] === job

    /**
     * 本次任务是否仍是该实例的**活跃**安装者：登记表里还是它、且未被取消。
     * [onInstallLine] / [runInstall] 的入口状态更新用它做归属门（见 [onInstallLine] 的说明）。
     */
    private fun isActiveInstallOwner(instanceId: String, job: Job?): Boolean =
        job != null && running[instanceId] === job && !cancelled.contains(instanceId)

    /** BL-043①：该实例是否仍有进行中的安装条目（判据之一，见 [cancel]） */
    private fun hasRunningInstall(status: InstallStatus?): Boolean = status?.running == true

    /** BL-043①：该实例是否仍有登记在册的安装任务（判据之二，见 [cancel]） */
    private fun hasLiveInstallTask(instanceId: String): Boolean = running.containsKey(instanceId)

    /**
     * BL-043①：被守卫拦下的取消——写一条英文诊断（R-LOG），**不碰任何状态**。
     * 实例还在就写进它自己的流（用户点开日志能看到这次点击为什么没反应）；实例已被删除就只进
     * 全局流 —— 对已删实例 appendFor 会按需重建它的内存流（与 BL-080 的效率口径一致），
     * 一次必然 no-op 的迟到点击不值得留下一条孤儿流。
     */
    private fun logIgnoredCancel(instanceId: String, reason: String) {
        val instance = DshInstances.byId(instanceId)
        val line = "[install] cancel ignored for ${instance?.name ?: instanceId}: $reason"
        if (instance != null) DshLogBus.appendFor(instanceId, line) else DshLogBus.append(line)
    }

    /**
     * 安装失败。
     *
     * ★ **N7 修复：失败也要清 [statuses]，不能只写一条 `running = false` 的条目。**
     *   改造前这里写的是"把该实例的条目改成 `running = false` + error"，而清理只在成功路径
     *   （[finishStatus]）里做 —— 于是**每一次失败都往 `_statuses` 这个 map 里永久留下一行**，
     *   既没人删也没有任何界面会显示（界面只关心 `running == true` 的行），
     *   单调增长、直到 App 进程结束。用户每点一次"重试"就多一行，内存缓慢泄漏，
     *   而且它会跟着 `statuses` 的每次 `update` 一起被复制来复制去。
     *
     *   那"失败原因去哪里了"？—— 三处，都还在，而且本来就是权威来源：
     *     · 实例自己的 `lastError`（[DshInstances.markState] 写入，卡片与实例详情页读它）；
     *     · [progress] 发一条 [Progress.Failed]（下载页据此弹提示）；
     *     · [DshTasks.report] 一条 FAILED 的终态任务（任务区显示"安装失败 + 原因"）。
     *   所以清掉 `_statuses` 不会让任何信息消失 —— 它本来就是"进行中的进度"，不是"历史结论"。
     *
     * ★ 同时上报终态任务：失败原因进 [DshTasks.finished]，用户能在任务区看到"安装失败：xxx"
     *   而不是行一消失就什么都不知道。
     */
    private fun fail(instance: DshInstance, reason: String) {
        DshInstances.markState(instance.id, DshInstance.State.BROKEN, error = reason)
        // F2-8（BL-048）：版本取"本次安装的目标版本"，快照兜底。
        // ★ 原来是 `Progress.Failed(reason, instance.dshVersion)`：新建实例的快照是 null，
        //   下载页的 `p.version?.let { ... }` 据此跳过清理，「安装中」标记永久留在那一行。
        //   快照只在"老实例重装/换版本"时还有信息量（此时目标版本与它相等，等价），
        //   所以这里的顺序对两种实例都是对的。VM 不用改：拿到正确 version 会自行清标记。
        _progress.value = Progress.Failed(reason, resolveFailVersion(installVersions[instance.id], instance.dshVersion))
        finishStatus(instance.id)
        // 实例级：安装失败原因就是这个实例自己的失败（实例页"为什么它坏了"的唯一权威解释）
        DshLogBus.appendFor(instance.id, "[install] ${instance.name} 安装失败：$reason")
        DshTasks.report(
            DshTask(
                id = DshTasks.installTaskId(instance.id),
                kind = DshTask.Kind.INSTALL,
                title = instance.name,
                stage = reason,
                action = DshTask.Action.NONE,
                state = DshTask.State.FAILED,
                instanceId = instance.id,
                error = reason
            )
        )
    }

    private fun finishStatus(instanceId: String) {
        _statuses.update { it - instanceId }
    }

    /** 安装成功的终态任务。三处上报（常规成功、取消时已装完）共用一份，免得文案/字段漂移。
     *  BL-021：stage 用纯结论文案（R.string，不带版本）—— 默认实例名已是「dsh <版本>」，
     *  旧的 "dsh $version" 会与标题拼成「dsh X · dsh X」。 */
    private fun doneTask(instance: DshInstance, version: String): DshTask = DshTask(
        id = DshTasks.installTaskId(instance.id),
        kind = DshTask.Kind.INSTALL,
        title = instance.name,
        stage = DshUiText.getString(
            com.dsh.fcl.androidlauncher.R.string.dsh_task_install_done, "Installed"
        ),
        action = DshTask.Action.NONE,
        state = DshTask.State.DONE,
        instanceId = instance.id
    )

    private fun updateStatus(status: InstallStatus) {
        _statuses.update { it + (status.instanceId to status) }
    }

    /**
     * R15-B #2（2026-10-15 锐评修正）：**归属校验与状态写入收进同一 [ownershipLock] 临界区**。
     *
     * 旧写法是"先 [isActiveInstallOwner] 判、再 [updateStatus] 写"两步（check-then-act）——
     * 两步之间 [cancel] 可以持锁插入（它把"清 statuses"整段放在锁内），于是取消刚落地的条目
     * 会被这里补写回去，任务区闪回一帧"安装中"的幽灵条目。把判据和写入合进同一临界区后，
     * 取消要么排在本次写入之前（写入时 guard 已为 false，跳过）、要么排在之后（写入先落地、
     * 取消再清掉），不存在"先清后补"的交错。
     *
     * [statusSupplier] 惰性取值：guard 通过才构造 [InstallStatus]（不在会被丢弃的路径上做垃圾）。
     * @return true = 写入完成；false = 已不属于本任务（取消/接管/终态），**什么都没做**
     */
    private fun updateStatusIfOwned(instanceId: String, job: Job?, statusSupplier: () -> InstallStatus): Boolean {
        synchronized(ownershipLock) {
            if (!isActiveInstallOwner(instanceId, job)) return false
            updateStatus(statusSupplier())
            return true
        }
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

    /**
     * 起 npm 之前的空间检查。
     *
     * @return null 表示"够用，继续装"；否则是**可以直接展示**的失败原因（中/英由资源给）
     *
     * ★ 判据（为什么这么定）：
     *   1. 用 [DshBootstrap.INSTANCE_NODE_MODULES_BYTES] 折算需要量 —— 那是"解压 rootfs
     *      那条路"已经在用的同一个门槛。这里若另写一个魔数，两处门槛迟早分叉：改了 bootstrap
     *      忘了 installer，用户就会遇到"底座说够、安装说不够"（或反过来）。
     *   2. 但**不照搬那个数字**：它按"实例自己下载整棵依赖树（约 500 MB，留余量取 600 MB）"
     *      算，而 `setup-node-dsh.sh` 在请求版本命中 rootfs 预装版本、且实例还没有自己的包时，
     *      会直接 `DONE source=preinstalled` 并**跳过下载**，一个字节都不写。此时报"空间不足"
     *      就是纯假失败 —— 把本来能装完的用户拦住。所以命中预装时预算降到 [PREINSTALLED_SKELETON_BYTES]。
     *   3. 报错条件是"**剩余 < 需要**"，且**只**看这一个条件：这件检查的目的不是替用户评判
     *      他的手机，而是拦住"注定写到一半写满盘"的那次安装 —— 写满盘时的终局与本轮要修的是
     *      同一件事（界面只剩一句"安装失败（详见日志）"）。宁可放行一次可能失败的安装，
     *      也不要卡住一个其实装得下的实例，所以不额外加系数、不设百分比闸门。
     *   4. 剩余空间读不出来时（[DshPaths.freeSpaceBytes] 约定返回负数）**直接放行**：
     *      读不到不等于不够，为此拦下一次安装是拿"未知"当"不足"。
     */
    private fun checkInstallSpace(instance: DshInstance, version: String): String? {
        val need = nodeModulesBudgetBytes(instance, version)
        val free = DshPaths.freeSpaceBytes()
        if (isEnoughSpaceForInstall(need, free)) {
            // ★ BL-133：放行也要留一行英文诊断 —— 门槛余量正在收窄（真机 573M vs 600MB 仅余 4.5%，
            //   node_modules 每版本约 +50M），"放行了多少余量"是将来判断"该不该抬门槛"的唯一数据。
            //   数字走的与失败文案同两个变量（need/free），单位统一 MB（负数=读不到，原样报出）。
            DshLogBus.appendFor(
                instance.id,
                "[install] space ok need=${need / 1024 / 1024}MB free=${free / 1024 / 1024}MB"
            )
            return null
        }
        return DshUiText.getString(
            com.dsh.fcl.androidlauncher.R.string.dsh_install_no_space,
            "Not enough space",
            DshPaths.formatSize(need),
            DshPaths.formatSize(free)
        )
    }

    /**
     * 这次安装需要在 node_modules 那个分区上留出的空间。
     *
     * 两种情形（与 `setup-node-dsh.sh` 的"预装版本命中则跳过安装"分支一一对应）：
     * - 请求的版本就是 rootfs 里预装的版本、且实例还没有自己的包 → 脚本只建目录、不下载，
     *   预算走 [PREINSTALLED_SKELETON_BYTES]；
     * - 其余（要真下载 / 已有包要换版本）→ 用 [DshBootstrap.INSTANCE_NODE_MODULES_BYTES]，
     *   与底座解压那道门槛同源。
     *
     * ★ 判据只看"实例**自己**有没有 dsh 包"，不看 [DshPaths.effectiveDshDir]：后者会回退到
     *   rootfs 里的预装包，拿它判断会把"实例要自己下载"的场景误判成"已经有包了"。
     *   必须用 [DshPaths.instanceDir] 拼实例内的路径。
     * ★ 版本号与脚本同样按**字面比较**（`latest` 在界面上是"最新版"，预装是否算命中要等
     *   脚本自己判），所以这里也不解析 `latest`。
     */
    private fun nodeModulesBudgetBytes(instance: DshInstance, version: String): Long {
        // 每次安装只算一次（[checkInstallSpace] 只调它一次），所以不做缓存 ——
        // 缓存反而要处理"装完之后实例里已经有包了"的失效问题，是白添的状态。
        val own = readVersionFromPackageJson(
            File(DshPaths.ownDshDir(instance.id), "package.json")
        )
        // 请求的是 `latest`（界面上叫"最新版"）时干脆不读预装版本：预装算不算命中要等脚本
        // 自己比，这里给不出来就不猜 —— 猜错会把要下载的安装当成"不用下载"，预算就少了一大截。
        val preinstalled = if (version == "latest") null else readVersionFromPackageJson(
            File(File(DshPaths.ROOTFS_DIR, DshPaths.PREINSTALLED_DSH_REL), "package.json")
        )
        // 已有自己的包（重装 / 换版本）：npm 会把旧内容先删一部分再写新的，按一份完整依赖树算
        return if (own == null && preinstalled != null && preinstalled == version) {
            PREINSTALLED_SKELETON_BYTES
        } else {
            DshBootstrap.INSTANCE_NODE_MODULES_BYTES
        }
    }

    /**
     * 失败原因：优先用预检/异常信息，其次是该实例自己的 npm 错误尾部（能归类就给专门文案），
     * 最后才是泛化的"安装失败（详见日志）"。
     *
     * ★ 兜底链的**顺序不能动**：`errorSummaries` 里放的是**预检失败**与**异常消息**（代码自己
     *   写的措辞，比任何自动摘要都准），它必须压过从 npm 输出里拼出来的尾部摘要；
     *   而泛化文案是"实在说不出所以然"时的那一句，永远排最后。
     *
     * ★ 本轮修的正事：改造前 `errorTails` 永远收不到东西（见 [isNpmErrorLine] 的注释），
     *   于是这条链**每一次**都落到泛化文案 —— 界面永远只有"安装失败（详见日志）"。
     */
    private fun errorSummary(instanceId: String): String =
        errorSummaries[instanceId]
            ?: errorTails[instanceId]?.takeLast(3)?.joinToString(" | ")
                ?.takeIf { it.isNotBlank() }?.let { describeFailure(it) }
            ?: DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_install_failed_generic, "install failed")

    /**
     * 把 npm 的原始错误摘要翻成"人能照着做"的一句话。
     *
     * 判定顺序（先分类、再取错误码，两者都进行不下去才原样回放）：
     * 1. 整段摘要里找网络类**特征字**（见 [classifyNetworkFailure]）→ 给一条专门文案；
     * 2. 找 npm 报告的错误码（见 [npmErrorCode]）→ 给"npm 错误 <码>"，
     *    至少把这个码摆到台面上（用户拿到码才能自己搜）；
     * 3. 都认不出 → **原样回放尾部**（只截断，不改写）。再难看的一段摘要，
     *    也比"安装失败（详见日志）"多给了信息 —— 把真实原因送到界面就是本次修复的全部目的。
     *
     * ★ 为什么不给每一类都配一句"建议怎么办"：npm 的错误有几十种（依赖冲突、生命周期脚本
     *   失败、404、权限……），给猜错的原因配建议比不给建议更坏 —— 用户会照着做，然后再失败一次。
     *   所以只有能**确证**的那几类网络故障配专门文案。
     */
    private fun describeFailure(raw: String): String {
        // 统一大写后再匹配（特征字表一律按大写书写，见 companion 里的常量）：npm 自己的措辞
        // 大小写是稳定的，但用户手抄进日志、或将来 npm 改了大小写，都不该让分类失效。
        val upper = raw.uppercase()
        when (classifyNetworkFailure(upper)) {
            NetworkFailure.DNS -> return DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_install_error_dns, "install failed")
            NetworkFailure.TIMEOUT -> return DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_install_error_timeout, "install failed")
            NetworkFailure.UNREACHABLE -> return DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_install_error_unreachable, "install failed")
            NetworkFailure.OTHER -> return DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_install_error_network, "install failed")
            null -> Unit
        }
        npmErrorCode(raw)?.let { code ->
            return DshUiText.getString(
                com.dsh.fcl.androidlauncher.R.string.dsh_install_error_npm, "npm error $code", code
            )
        }
        val kept = raw.take(SUMMARY_MAX_CHARS)
        return if (raw.length > kept.length) "$kept…" else kept
    }

    /**
     * 网络类故障分类（整段摘要走一遍；够不够"网络"由特征字表决定，见 [hasNetworkSignal]）。
     *
     * 认不出返回 null，由调用方接着走"取错误码 → 原样回放"。
     *
     * ★ 判的是**整段**摘要而不是单行：npm 把一次失败拆成多行（`code` / `syscall` / `errno` /
     *   真正的 `request to ... failed, reason: ...`），最关键的那句跨行拼起来才认得全。
     * ★ 只认**能确证**的网络信号，判不出来就返回 null：把"原生依赖编译失败"报成"网络问题"
     *   会让用户去查网络，白折腾一轮 —— 宁可退到"原文回放"这种不好看但不说谎的兜底。
     */
    private fun classifyNetworkFailure(upper: String): NetworkFailure? = when {
        hasNetworkSignal(upper, DNS_ERRNOS, DNS_PHRASES) -> NetworkFailure.DNS
        hasNetworkSignal(upper, TIMEOUT_ERRNOS, TIMEOUT_PHRASES) -> NetworkFailure.TIMEOUT
        hasNetworkSignal(upper, UNREACHABLE_ERRNOS, UNREACHABLE_PHRASES) -> NetworkFailure.UNREACHABLE
        // 兜底的"网络问题"只在**确证这是 npm 的错误行**时才认：`npm error network ...` 是 npm
        // 给网络类失败留的固定措辞。没有这道闸，"NETWORK" 这个词能从别的地方混进来。
        // （[isNpmErrorLine] 大小写不敏感，这里传的是大写过的文本，照样认。）
        isNpmErrorLine(upper) && NETWORK_GENERIC.any { it in upper } -> NetworkFailure.OTHER
        else -> null
    }

    /**
     * 特征字匹配（[classifyNetworkFailure] 的匹配器）：errno 用**词边界**，短语用子串。
     *
     * ★ errno 必须是词边界，不能裸子串：这些码都很短，裸匹配会在
     *   `/opt/dsh/instances/x/node_modules/sharp/build/...` 这类路径或 `SHARP_ERR` 这类
     *   自定义错误名里误命中，把"原生依赖装不上"报成"网络不可达" —— 那比不分类更坏。
     * ★ 短语一律用**多词**形式（"TIMED OUT" 而不是单一个 "TIMEOUT"）：npm 的报错文本里会原样
     *   出现请求的 URL（`https://registry.npmjs.org/dns-packet/-/dns-packet-5.6.1.tgz`），
     *   单个词很容易被 URL 里的包名撞上。
     */
    private fun hasNetworkSignal(upper: String, errnos: List<String>, phrases: List<String>): Boolean =
        errnos.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(upper) } ||
            phrases.any { it in upper }

    /**
     * 取 npm 报告的错误码（`npm error code EAI_AGAIN` → `EAI_AGAIN`），取不到返回 null。
     *
     * ★ 大小写不敏感、且**不锚行首**：[describeFailure] 拿到的常常不是原样的一行，而是
     *   `tail.takeLast(3)` 用 `" | "` 拼起来的摘要 —— 报错的关键那句往往在第 4 行（比如
     *   `npm error request to https://registry.npmjs.org/sharp failed, reason: ...`），
     *   取尾 3 行时 `code` 那行就不在开头了。锚行首会让它在真实数据上几乎不命中。
     */
    private fun npmErrorCode(raw: String): String? =
        NPM_ERROR_CODE_RE.find(raw)?.groupValues?.get(1)

    private suspend fun runInstall(instance: DshInstance, version: String, myJob: Job?): Boolean {
        errorSummaries.remove(instance.id)
        errorTails.remove(instance.id)
        val script = "${ProotCommand.GUEST_ROOT}/scripts/setup-node-dsh.sh"
        // ★ BL-032：外层预检已删（本函数原来在此调 ProotCommand.preflight）。
        //   ProotProcessExecutor.run() 内部第 1 步就是同一预检（同参同判据），双调用只会让 DNS 同步
        //   与磁盘检查跑两次（bebug 里 resolv.conf 成对行的来源）。外层无额外 UI 价值——错误摘要
        //   与日志都已改读内部预检回传的结果（见下的 fallback 支线与 -1 收口）。

        // BL-104 防幽灵：若用户在上一次 updateStatus（install() 入口）之后、这里之前已经取消，
        // 这条"准备"状态会把 cancel 刚清掉的条目加回去（任务区闪回一帧"安装中"）。
        // ★ R15-B #2 修正：判据与写入合并进 [updateStatusIfOwned] 的同一临界区（与 cancel 的
        //   "清状态"持同一把 [ownershipLock]）——"取消刚落、这里补写"的交错窗口从此关闭。
        updateStatusIfOwned(instance.id, myJob) {
            InstallStatus(
                instance.id, version,
                DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_stage_prepare, "install failed"),
                fraction = 0.05
            )
        }

        // 宿主侧确保实例目录结构存在
        DshPaths.instanceDir(instance.id).mkdirs()
        DshPaths.instanceHome(instance.id).mkdirs()
        DshPaths.instanceWorkspace(instance.id).mkdirs()
        File(DshPaths.NPM_CACHE_DIR).mkdirs()

        // ★ F2-6（BL-041）：起新 npm 之前先清理**上一次安装**的残留（App 被杀/安装中退出界面后
        //   留下的孤儿 proot）。不清的话重装就是两个 npm（一新一旧）同时写同一个 node_modules。
        //   放在空间检查之前：残留进程可能还在写盘，先停它，报出的"剩余空间"才是准的。
        cleanupStaleInstallProcess(instance)

        // ★ 起 npm 之前的空间检查（判据与"要留多少"见 [checkInstallSpace] / [nodeModulesBudgetBytes]）。
        //   放在这里是因为：① 预检已经过了，失败原因不会再被预检文案顶掉；
        //   ② 目录已经建好，报出来的"当前剩余"就是真正要写入的那个分区；
        //   ③ 还没起 proot/npm —— 空间不足时立刻返回，而不是让用户等 29 分钟再拿到
        //     一个"安装失败（详见日志）"（真机实测：盘上只剩约 1.1 GB，而一次安装
        //      要写 500 MB 上下；写满盘时同样只会剩下那句泛化文案）。
        val shortfall = checkInstallSpace(instance, version)
        if (shortfall != null) {
            // 归属门（与 [onInstallLine] 同一纪律）：被取消/被接管的旧任务不许再把失败摘要
            // 写进共享表 —— 新任务若随后失败，errorSummary 会读到这条陈旧文本（污染）。
            if (isActiveInstallOwner(instance.id, myJob)) {
                errorSummaries[instance.id] = shortfall
            }
            // 实例级：这是"这个实例这次装不上"的原因，装它的人该在自己的日志里看到
            DshLogBus.appendFor(instance.id, "[install] 空间不足，已跳过 npm：$shortfall")
            return false
        }

        val instancePathInRootfs = "${ProotCommand.GUEST_ROOT}/instances/${instance.id}"
        // 本次安装真正写进 dsh-install.pid 的 pid（0 = 本次没解析到/没写）。
        // ★ AtomicLong 而不是普通变量：回调来自 [ProotProcessExecutor.run] 的 pid 解析侧，
        //   与读它的 finally 不一定同线程。CAS(0, pid) 同时保证"只认第一次回调"（防御重放）。
        val installPid = AtomicLong(0L)
        var exit = -1
        try {
            // ★ F2-7（BL-051）：整段 npm 执行持 PARTIAL_WAKE_LOCK —— 安装是 10~30 分钟的
            //   CPU/网络密集活，灭屏进 Doze 会被掐网络（npm 卡死）或被 LMK 直接杀。超时用
            //   [DshBootstrap.INSTALL_WAKE_LOCK_TIMEOUT_MS]（40 分钟 = 30 分钟看门狗 + 余量）。
            //   只包进程执行段：前后的状态更新 / pid 清理不持有锁。
            //
            // ★ 结构说明：block 是 suspend 非 inline lambda，**不能**在里面 `return false` ——
            //   预检失败的早退改写成"返回 -1 退出码"，下游（`exit == 0` → false、fail → errorSummary）
            //   与之完全等价（errorSummaries 已在块内写入失败原因）。
            exit = DshBootstrap.withRuntimeBusyWakeLock(context, DshBootstrap.INSTALL_WAKE_LOCK_TIMEOUT_MS) {
                val direct = (proot as? ProotProcessExecutor)?.run(
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
                    tag = instance.id,
                    // ★ F2-6（BL-041）：pid 解析出来的那一刻同步落盘 —— App 若被杀，
                    //   下次安装的 [cleanupStaleInstallProcess] 靠这个文件认领孤儿 npm。
                    onPidResolved = { pid ->
                        if (pid > 0 && installPid.compareAndSet(0L, pid)) {
                            writeInstallPidFile(instance, pid)
                        }
                    }
                ) { line -> onInstallLine(instance, version, line, myJob) }
                if (direct != null) {
                    direct
                } else {
                    // ★ BL-032：只有"非 ProotProcessExecutor 的 ProotExecutor 实现"（单测替身）才走这里，
                    //   真机实现自己在 run() 内部做预检。替身没有内部预检，这次补调是它唯一的预检。
                    val pre = ProotCommand.preflight(context, DshPaths.ROOTFS_DIR, script)
                    if (!pre.ok) {
                        // 归属门同 [onInstallLine]：旧任务的晚到失败不许写进新任务的摘要
                        if (isActiveInstallOwner(instance.id, myJob)) {
                            errorSummaries[instance.id] = pre.reason ?: "预检失败（原因未知）"
                        }
                        DshLogBus.appendFor(instance.id, "[install] Preflight failed: ${pre.reason}")
                        -1
                    } else {
                        proot.run(
                            script = script,
                            env = mapOf(
                                "INSTANCE_DIR" to instancePathInRootfs,
                                "DSH_VERSION" to version,
                                "NPM_CONFIG_CACHE" to "${ProotCommand.GUEST_ROOT}/npm-cache"
                            ),
                            onLine = { line -> onInstallLine(instance, version, line, myJob) }
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            // 归属门同 [onInstallLine]：被接管/被取消的旧任务不许覆写新任务的失败摘要
            if (isActiveInstallOwner(instance.id, myJob)) {
                errorSummaries[instance.id] = t.message ?: t.toString()
            }
            return false
        } finally {
            // ★ F2-6（BL-041）：安装的所有终态（成功/失败/取消/异常/早退）都把本次的 pid 文件清掉 ——
            //   它只在"安装进行中"有意义，留着会让下一次安装的孤儿清理去检查一个早就结束的 pid。
            //   K03 compare-and-delete：只有文件里仍是**本次**写的 pid（或读不出）才删，
            //   防"取消后立即重装"时被取消那次的收尾误删新安装刚写的文件（新安装失去孤儿保护）。
            deleteInstallPidFile(instance.id, installPid.get())
        }
        // ★ R-14：看门狗超时（ProotProcessExecutor 约定返回 -2）时给可读文案，
        // 否则 errorSummary 只会落到泛化的\"安装失败\"，用户完全不知道是卡死/超时。
        if (exit == ProotProcessExecutor.TIMEOUT_EXIT_CODE) {
            // 归属门同 [onInstallLine]
            if (isActiveInstallOwner(instance.id, myJob)) {
                errorSummaries[instance.id] = DshUiText.getString(
                    com.dsh.fcl.androidlauncher.R.string.dsh_install_timeout,
                    "Install timed out",
                    INSTALL_TIMEOUT_MS / 60_000
                )
            }
        }
        return exit == 0
    }

    private fun onInstallLine(instance: DshInstance, version: String, line: String, myJob: Job?) {
        // 实例级全量通道：这是**这个实例的** npm / setup 脚本输出（安装进度、下载日志、失败摘要
        // 的原文）。装 A 的时候日志页就该只看到 A 的 npm 输出 —— 原来两条实例同时装时这里全混在
        // 一条流里。日志流**不节流**：日志页要看的是完整输出，节流只降 UI 采样通道（下一段）。
        DshLogBus.appendFor(instance.id, line)
        // ★ 归属门（BL-043① 判据保真的配套；同 BL-104 的"被杀任务体仍处理 npm 尾行重写 statuses"面）：
        //   被取消 / 被接管之后迟到的输出行**只进日志**，不再写共享状态 —— 否则会把 [cancel]
        //   刚清掉的条目（幽灵"安装中"投影，任务行永远摘不掉、取消守卫判据失真）或新任务的
        //   错误摘要改回来。正常的在跑任务 [isActiveInstallOwner] 恒为真，行为与改造前一致。
        if (!isActiveInstallOwner(instance.id, myJob)) return
        // ★ BL-053（K34 残余）：[Progress.Log] 是 StateFlow —— 每行写一次会让每个订阅者
        //   （下载页 VM 的 collect → rebuild()）按 npm 的输出频率被唤醒（安装高峰每秒几十行）。
        //   这里按 [shouldEmitLog] 节流：普通行约 [LOG_THROTTLE_MIN_INTERVAL_MS] 一行；
        //   重要行（[isImportantInstallLine]：STAGE / FAILED / npm 错误 / 预检失败前缀）
        //   不受窗口限制、立即发射 —— 用户点开界面时错误行必须马上看得见。
        //   （重要行各自另有的路径不受影响：STAGE 走 [Progress.Stage]、错误行走 [errorTails]，
        //   这两条都不经过本节流 —— 本节的抑制只影响 Log 采样通道。）
        //   被抑制的行不丢：进 pending 缓冲，由 [flushPendingInstallLog] 在收尾补发。
        val trimmed = line.trim()
        val important = isImportantInstallLine(trimmed)
        val now = System.currentTimeMillis()
        if (shouldEmitLog(now, lastLogEmitMs[instance.id] ?: 0L, important)) {
            lastLogEmitMs[instance.id] = now
            _progress.value = Progress.Log(line)
        } else {
            bufferPendingLogLine(instance.id, line)
        }
        when {
            trimmed.startsWith("[setup] STAGE=") -> {
                // ★ 脚本给的是**稳定 key**（`[setup] STAGE=<key> [args]`），不是给人看的句子。
                //   翻译在 [stageText] 里做，这样切到英文界面时进度也是英文 —— 改造前脚本直接
                //   输出中文句子（"下载并安装 @deepseek-ai/dsh@0.2.1-alpha.1"），而 rootfs 里的
                //   脚本没有 i18n 机制，于是英文界面下这一段永远是中文。
                val raw = trimmed.removePrefix("[setup] STAGE=")
                // ★ BL-018：阶梯百分比（npm 以 CI=true 运行无百分比可采，按阶段给单调递增的阶梯）。
                //   映射在 [stageFraction] 纯函数里（可单测），认不出 key 返回 null → 界面保持无限动画。
                val stage = stageText(raw)
                // ★ R15-B #2 修正：判据+写入同临界区（与 [cancel] 的清状态同锁）
                updateStatusIfOwned(instance.id, myJob) {
                    InstallStatus(instance.id, version, stage, fraction = stageFraction(raw))
                }
                _progress.value = Progress.Stage(stage)
            }
            // npm 失败信息可能跨行，保留尾部若干行做摘要。
            // ★ 判据交给 [isNpmErrorLine]（它同时认 npm ≤9 的 `npm ERR!` 与 npm ≥10 的
            //   `npm error`，且不收 `npm warn`），别在这里写成两个 `||` 的硬编码 ——
            //   判据写在这里就等于又藏回实例里，谁也测不到（这正是它坏了两代 npm 才被发现的原因）。
            isNpmErrorLine(trimmed) -> rememberFailureLine(instance.id, trimmed)
            // setup 脚本自己报的失败（`[setup] FAILED reason=node-version` 这类，见脚本头部约定）。
            // ★ 一并收进尾部：这类失败发生在 **npm 之前**（Node 版本不满足、npm 缺失等），
            //   此时 errorTails 里一条 npm 输出都没有，errorSummary 又会落回
            //   "安装失败（详见日志）"—— 和本轮的 npm 前缀问题一模一样，只是换了个来源。
            trimmed.startsWith("[setup] FAILED") -> rememberFailureLine(instance.id, trimmed)
            // ★ BL-032：run() 内部预检失败也走 onLine（"[proot] 预检失败: <原因>"），删掉外层预检后
            //   这里收进尾部——失败摘要链经 errorSummary → 原样回放仍能带出可读原因，不回落泛化文案。
            trimmed.startsWith(PREFLIGHT_FAIL_PREFIX) -> rememberFailureLine(instance.id, trimmed)
            // ★ R20：npm http 行 → 实时活动行（见 [extractNpmActivity]）。**不覆盖 stage**：
            //   只更新 activity 字段（卡片/任务区拿它显示"正在抓哪个包"的滚动细节）。
            //   读-改-写安全：onInstallLine 由单一 stdout 读取线程顺序调用，不存在与
            //   STAGE 行的并发交错；updateStatusIfOwned 仍按归属门复核。
            //   ★ 更新节流 [ACTIVITY_THROTTLE_MS]：npm 下载高峰每秒几十条 http 行，每条都写
            //   _statuses 会让 RecyclerView 每秒收到几十次 payload 通知（掉帧）。200ms 窗口
            //   ≈5Hz —— 肉眼"在滚动"的观感已足够（与部署页 1Hz 的文件路径滚动同量级）。
            else -> extractNpmActivity(trimmed)?.let { activity ->
                val now2 = System.currentTimeMillis()
                if (now2 - (lastActivityEmitMs[instance.id] ?: 0L) >= ACTIVITY_THROTTLE_MS) {
                    lastActivityEmitMs[instance.id] = now2
                    val cur = _statuses.value[instance.id]
                    if (cur != null && cur.activity != activity) {
                        updateStatusIfOwned(instance.id, myJob) { cur.copy(activity = activity) }
                    }
                }
            }
        }
    }

    /** 节流抑制期：把被抑制的行收进该实例的 pending 缓冲（有界，超上限丢最旧） */
    private fun bufferPendingLogLine(instanceId: String, line: String) {
        val deque = pendingLogLines.getOrPut(instanceId) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(line)
            while (deque.size > PENDING_LOG_MAX_LINES) deque.removeFirst()
        }
    }

    /**
     * 收尾 flush（BL-053）：把节流期间被抑制的尾行补发到 [Progress.Log]。
     *
     * 由 [installJobBody] 的 finally 调用（成功/失败/取消/异常都经过）——"最后几行不丢"。
     * 合并成**一条**发射而不是逐行重放：这些行的完整原文早已逐行进过 [DshLogBus]（日志页
     * 拿得到全文），采样通道补一条"尾部快照"即可；逐行重放会把收尾时刻变回一次行洪，
     * 正是节流要消除的东西。清空该实例的节流状态，下一次安装从零开始。
     */
    private fun flushPendingInstallLog(instanceId: String) {
        val deque = pendingLogLines.remove(instanceId) ?: return
        lastLogEmitMs.remove(instanceId)
        lastActivityEmitMs.remove(instanceId)
        val lines = synchronized(deque) { deque.toList().also { deque.clear() } }
        if (lines.isEmpty()) return
        _progress.value = Progress.Log(lines.joinToString("\n"))
    }

    /**
     * 把脚本给的**阶段 key** 翻成当前语言的文案。
     *
     * ## 为什么不直接把脚本输出的那句显示出来
     * 脚本跑在 rootfs 里（由 dash 执行），**没有 i18n 机制** —— 它输出什么就是什么。
     * 改造前它直接输出中文句子，于是界面切成英文之后，安装进度那一段仍然是中文。
     * 现在脚本只报 key（`installing` / `verifying-install` / …），翻译在**启动器这一侧**做，
     * 语言就跟着界面走了。
     *
     * ## key 长什么样
     * `[setup] STAGE=<key>[ <arg>…]`，key 是**小写 ASCII kebab-case**，后面是它的参数。
     * 当前这套 key 与 `assets/dsh/scripts/setup-node-dsh.sh` 里的 `stage` 调用一一对应 ——
     * **加新阶段要两边一起改**。
     *
     * ## 认不出某个 key 时怎么办：**原样显示整行**
     * 这有两种来路，处理成一样是对的：
     * 1. **升级顺序相反**——App 更新了、rootfs 里的脚本还是旧的（脚本要 bump `scripts/version`
     *    才会重新解压，中间可能有一段空窗）；
     * 2. **将来加了新 key 而启动器还没跟上。**
     * 两种情况下，`installing @deepseek-ai/dsh@0.2.1-alpha.1` 这种**读起来像英文短语**的 key
     * 都还能看懂；这也是为什么 key 不写成 `S3 v=…` 那种纯符号（那在界面上就是一行机器码）。
     * ⚠️ **宁可显示一行看不懂的英文，也不要吞掉** —— 进度行消失比文案不美观严重得多。
     */
    private fun stageText(raw: String): String {
        val key = raw.substringBefore(' ')
        val arg = raw.substringAfter(' ', "").trim().takeIf { it.isNotEmpty() }
        val res = when (key) {
            "node-ready" -> com.dsh.fcl.androidlauncher.R.string.dsh_stage_node_ready
            "adding-npm" -> com.dsh.fcl.androidlauncher.R.string.dsh_stage_adding_npm
            "installing-node" -> com.dsh.fcl.androidlauncher.R.string.dsh_stage_installing_node
            "using-preinstalled-dsh" -> com.dsh.fcl.androidlauncher.R.string.dsh_stage_using_preinstalled_dsh
            "installing" -> com.dsh.fcl.androidlauncher.R.string.dsh_stage_installing
            "verifying-install" -> com.dsh.fcl.androidlauncher.R.string.dsh_stage_verifying_install
            "reinstalling" -> com.dsh.fcl.androidlauncher.R.string.dsh_stage_reinstalling
            else -> return raw
        }
        // 带 %1$s 的那几条，参数缺失时**整行原样返回**而不是喂空串：
        // 那些文案里的参数是版本号，缺了就成了"正在安装 "这种半句话。
        return if (arg != null) {
            DshUiText.getString(res, raw, arg)
        } else {
            DshUiText.getString(res, raw)
        }
    }

    /**
     * 记一行失败输出进 [errorTails]（尾部保留 [ERROR_TAIL_LINES] 行，超出的从头丢）。
     *
     * 抽成函数是因为**两个来源**（npm 错误行、setup 脚本的 FAILED 行）要用同一套窗口：
     * 原来这段窗口逻辑只写在 npm 那个分支里，加一个来源就得再抄一遍 —— 抄歪了就成了
     * "两个来源互相挤掉"这种很难查的问题。
     */
    private fun rememberFailureLine(instanceId: String, line: String) {
        errorTails.getOrPut(instanceId) { ArrayDeque() }.let { tail ->
            tail.addLast(line)
            while (tail.size > ERROR_TAIL_LINES) tail.removeFirst()
        }
    }

    /**
     * 从 package.json 读真实版本（安装是否完整，以此为准）。
     *
     * ★ 第十一轮：走 [DshPaths.effectiveDshDir] —— 命中 rootfs 预装版本时
     * `setup-node-dsh.sh` 会打印 `DONE source=preinstalled` 并**跳过下载**，
     * 此时实例目录里并没有 node_modules（真正的包在 rootfs 的 `/opt/dsh-preinstalled`）。
     * 只查实例路径会把「刚刚装成功」的实例判成 `dsh_install_incomplete` → BROKEN，
     * 且重装会一直重复同一结果（阶段 D-1 引入的回归）。
     *
     * ★ BL-054：判定内核改为 [isInstallComplete] 纯函数（package.json 可读 + `lib/bin.js`
     *   存在 + 与 `requestedVersion` 相符）。`lib/bin.js` 缺失从"只警告"升级为**否定判据** ——
     *   `setup-node-dsh.sh` 的 verifying-install 段自己就以它为准（missing-bin-js → FAILED），
     *   启动链（start-dsh.sh）也以它为入口 —— 能读出版本号却起不来的包，不能算"装完"。
     *   请求版本为 null（旧调用点/版本未知）时跳过版本比对，行为与旧实现一致。
     *
     * @param requestedVersion 本次请求的目标版本（[resolveFailVersion] 的取值链）；null = 不比对
     * @return 版本号（判定"安装完整"时）；不完整返回 null
     */
    private fun readInstalledVersion(instance: DshInstance, requestedVersion: String?): String? {
        val dir = DshPaths.effectiveDshDir(instance.id)
        val fromDisk = readVersionFromPackageJson(File(dir, "package.json"))
        if (fromDisk != null && !File(dir, "lib/bin.js").isFile) {
            // 实例级：缺入口文件是**这个实例**装坏了（"能读出版本却启动不了"的原因就在这行）。
            // 警告与否定判据分开写：isInstallComplete 还管"版本不匹配"，那种情况不该打这条字面
            // 为"缺少 lib/bin.js"的警告（DshInstances.repair 自会校准到真实版本）。
            DshLogBus.appendFor(instance.id, "[install] 警告：${instance.name} 缺少 lib/bin.js（$dir）")
        }
        return if (isInstallComplete(dir, requestedVersion)) fromDisk else null
    }

    companion object {
        /** 安装最长容忍时间：低于此值可能是网络慢，高于此值基本是卡死 */
        const val INSTALL_TIMEOUT_MS = 30 * 60 * 1000L

        /** [errorTails] 保留的错误行数：够看清"哪一步、什么码"，又不会被前面的警告行顶掉 */
        private const val ERROR_TAIL_LINES = 12

        /**
         * run() 内部预检失败行的前缀（`ProotProcessExecutor.run:62` 发的 `[proot] 预检失败: …`）。
         * ★ BL-032：删外层预检后靠它收原因进 errorTails（与 [onInstallLine] 的收口分支配套）。
         *   字符串判据按原文写死——两边都是本仓库代码，真机无混用新旧版本的路径（预检行走同一进程）。
         */
        private const val PREFLIGHT_FAIL_PREFIX = "[proot] 预检失败:"

        /** 失败摘要送进界面/任务区的最大字符数（超出的截断，接口与实例卡片都放不下长文） */
        private const val SUMMARY_MAX_CHARS = 300

        /**
         * npm **错误行**前缀表（**小写形式**，匹配前整行已 [String.lowercase]）。
         * 两条都要有，这是跨版本兼容：`npm ERR!` 是 npm ≤ 9，`npm error` 是 npm ≥ 10。
         * 判据只比**前缀**（不 `contains`），所以 `npm warn ... ERESOLVE ...` 这类警告进不来。
         */
        private val NPM_ERROR_PREFIXES = listOf("npm err!", "npm error")

        /**
         * 这一行是不是 npm 的**错误**行（[onInstallLine] 决定要不要收进 [errorTails] 的判据）。
         *
         * ★ 必须同时认两种前缀（见 [NPM_ERROR_PREFIXES]），这是**跨版本兼容**而不是猜的：
         *   npm 10 起官方把出错行前缀从 `npm ERR! code EAI_AGAIN` 改成了全小写、不带感叹号的
         *   `npm error code EAI_AGAIN`。本项目 rootfs 里预装的正是 npm 10.9.9，于是改造前那句
         *   `startsWith("npm ERR!")` **一行都匹配不上** —— 错误窗口永远是空的，失败原因一条
         *   都收不进来。这是功能失效，不是措辞问题。
         *
         * ★ 为什么不能退回 `contains("ERR!")`（改造前的第二个半句就是这么写的）：那个判据会把
         *   `npm warn ... ERESOLVE overriding peer dependency` 之类的**警告**一起收进来
         *   （真机日志里这种行有几十条），真正的错误行反而被挤出 12 行窗口。
         *   警告不是错误，判据必须要求这一行**以** npm 的错误前缀开头。
         *
         * ★ 放在 companion 里是为了**能测**：`DshCoreLogicTest` 直接对着它跑 npm 9/10 两种
         *   前缀和警告行的真机样例。留在实例里就得先构造一个 `DshInstaller`（要 `Context`），
         *   而这个判据恰恰是坏了两代 npm 都没人发现的 —— 能直接测是最起码的补救。
         *
         * @param line 必须是已 `trim()` 的行（[onInstallLine] 传进来前已经去过首尾空白）
         */
        fun isNpmErrorLine(line: String): Boolean =
            // 整行折成小写再比前缀（[NPM_ERROR_PREFIXES] 里存的是小写形式）：一张表同时盖住两代
            // 前缀，也不怕将来某一侧改了大小写。判据不看行中间，所以不会误收警告。
            NPM_ERROR_PREFIXES.any { line.lowercase().startsWith(it) }

        /**
         * npm 报告的错误码。两种前缀都要认（npm 10 的 `npm error`、npm ≤9 的 `npm ERR!`），
         * 且大小写不敏感 —— 见 [npmErrorCode] 对"为什么不锚行首"的说明。
         *
         * ★ 前缀后的 `\s*`：npm 两种格式的写法略有出入（`code EAI_AGAIN` 与 `code: EAI_AGAIN`），
         *   都吃掉，免得某天换一代就又取不到码。
         */
        val NPM_ERROR_CODE_RE: Regex =
            Regex("""npm (?:ERR!|error)\s*code:?\s+(\S+)""", RegexOption.IGNORE_CASE)

        // --- 网络类故障的特征字 ------------------------------------------------
        //
        // **一律大写书写**：匹配前整段摘要已 `uppercase()`。
        // 分三类是因为它们对应**不同的下一步**：DNS 要换网络/等网络恢复、超时要重试、
        // 不可达要检查网络开关或代理。
        //
        // ★ 只认**能确证的**信号，宁可认不出来：这些文案会直接告诉用户"去查网络"。
        //   把"原生依赖编译失败"判成"网络不可达"，用户就去折腾网络 —— 白折腾一轮，
        //   还不如什么都不说，让他去看原文。所以下面三类都以 **errno** 为主，自然语言短语
        //   只收不会被别的报错撞上的多词形式。
        // ★ 不列 `EPIPE`：npm 的 "write EPIPE" 几乎总是本地进程提前退出（被取消/被杀），
        //   不是网络问题，归进来会给出误导性的网络建议。

        /**
         * DNS：靠 errno（`EAI_AGAIN` / `ENOTFOUND` / `EAI_FAIL` / `ESERVFAIL`）与
         * `getaddrinfo` 这个词本身判定 —— npm 的原文里两者总是一起出现
         * （`reason: getaddrinfo EAI_AGAIN registry.npmjs.org`）。
         *
         * ★ **故意不收 "could not resolve" 这种自然语言**：npm 的依赖冲突报错里写着
         *   `Could not resolve dependency:`（ERESOLVE），收进来会把"依赖版本冲突"
         *   报成"DNS 失败"，用户去折腾网络，而真正该做的是换版本。
         */
        private val DNS_ERRNOS = listOf("EAI_AGAIN", "ENOTFOUND", "EAI_FAIL", "ESERVFAIL")
        private val DNS_PHRASES = listOf("GETADDRINFO")

        /** 超时/中断：errno 为主，短语只收不会与其他报错撞车的多词形式（见 [hasNetworkSignal]） */
        private val TIMEOUT_ERRNOS = listOf("ETIMEDOUT", "ESOCKETTIMEDOUT", "ECONNRESET", "ECONNABORTED")
        private val TIMEOUT_PHRASES = listOf(
            "NETWORK TIMEOUT", "TIMED OUT", "SOCKET HANG UP", "CONNECTION RESET", "CONNECTION LOST"
        )

        /**
         * 不可达/被拒：`ENETUNREACH`（没网）、`ENETDOWN`（网卡没起来）、`EHOSTUNREACH`、
         * `ENETRESET`、`ECONNREFUSED`（连上了但没人应答 —— 多半是代理或公司网络拦的）。
         */
        private val UNREACHABLE_ERRNOS = listOf(
            "ENETUNREACH", "ENETDOWN", "EHOSTUNREACH", "ENETRESET", "ECONNREFUSED"
        )
        private val UNREACHABLE_PHRASES = listOf("NETWORK IS UNREACHABLE", "NETWORK IS DOWN")

        /**
         * npm 给网络类失败留的固定措辞；只在**确证是 npm 错误行**时才认
         * （见 [classifyNetworkFailure] 的最后一条分支）。
         *
         * 它比前面三类优先级低：只有在认不出具体原因时才落到这条"反正网络出了问题"。
         */
        private val NETWORK_GENERIC = listOf("NETWORK", "FETCH FAILED")

        /**
         * 命中 rootfs 预装版本时这次安装的预算：只要**目录骨架**。
         *
         * 依据是 `setup-node-dsh.sh` 的预装分支：命中后立刻 `DONE source=preinstalled` 退出，
         * 连 `npm init -y` 都不跑，实际写入接近 0；给 16 MB 是给目录项和日志留的余量。
         *
         * ★ 不照搬 [DshBootstrap.INSTANCE_NODE_MODULES_BYTES]（600 MB）：那套门槛放在这里，
         *   会把**本来不用下载、装得完**的安装判成"空间不足"，是假失败 —— 而这正是这件检查
         *   最该避免的事（它要拦的只是"注定写到一半写满盘"的那种）。
         */
        private const val PREINSTALLED_SKELETON_BYTES = 16L * 1024 * 1024

        /** 安装 pid 文件名（F2-6 / BL-041）：与本包内各 pid 文件名的命名风格一致（dsh.pid / dsh-node.pid） */
        const val INSTALL_PID_FILE_NAME = "dsh-install.pid"

        /**
         * R14-V2A F1：清理**上一次安装**的残留 proot 时，SIGQUIT 之后的等待预算（毫秒）。
         * proot 收尾（kill_all_tracees + 退出）通常 1 秒内完成；2 秒与运行期停止链的
         * SIGQUIT 预算同量级，超时才落 SIGKILL 兜底。
         */
        private const val STALE_INSTALL_QUIT_GRACE_MS = 2_000L

        private data class PackageJson(
            @SerializedName("version") val version: String? = null
        )

        /**
         * [fail] 写进 `Progress.Failed` 的版本号（F2-8 / BL-048 的纯判断内核，可单测）。
         *
         * 优先本次安装的目标版本（[installVersions] 里存的；新建实例只有这里有），
         * 其次实例快照（老实例重装时两者相等；快照非空而 map 空只可能出现在清理后的异常路径），
         * 都拿不到才 null。
         *
         * ★ 为什么必须"stored 优先"：下载页拿这个版本号清「安装中」标记（`p.version?.let { … }`）。
         *   只要它是非空，标记就能被清掉 —— null 才是那条"标记卡死"路径的根因。
         * ★ 为什么写在这里：与 [isEnoughSpaceForInstall] / [stageFraction] 同理 ——
         *   [DshInstaller] 要 Context，JVM 单测构造不出来，判断内核必须能独立被钉住。
         */
        fun resolveFailVersion(stored: String?, snapshot: String?): String? =
            stored?.takeIf { it.isNotBlank() } ?: snapshot?.takeIf { it.isNotBlank() }

        /**
         * 空间够不够装（[checkInstallSpace] 的纯判断内核）。
         *
         * ★ 抽成 companion 里的纯函数是为了能在 JVM 单测里直接覆盖：[DshInstaller] 本身要
         *   Context，测试里构造不出来，而"空间够不够"恰恰是只在真机上出错、出了错又说不清的地方。
         *
         * @param needBytes 本次安装预计要占的字节（负数 = 判不出来）
         * @param freeBytes [DshPaths.freeSpaceBytes] 给的剩余字节（负数 = 读不出来）
         * @return true = 放行。**只有"剩余 < 需要"才拦**（见 [checkInstallSpace] 的判据第 3 条）；
         *         两个负数都表示"不知道"，而拿"未知"当"不足"会拦下本来能装的实例。
         */
        fun isEnoughSpaceForInstall(needBytes: Long, freeBytes: Long): Boolean =
            needBytes < 0 || freeBytes < 0 || freeBytes >= needBytes

        /** 解析 package.json 的 version；文件不存在/解析失败返回 null */
        fun readVersionFromPackageJson(file: File): String? {
            if (!file.isFile) return null
            return runCatching {
                val text = file.readText()
                // ★ R26 / E1 §4.1（一致性修复）：`version` 必须是 **JSON 字符串原语** 才算数。
                //
                // 为什么不能只靠 Gson 反射：Gson 默认宽松，`{"version": 123}` 会把数字
                // "转成" 字符串 "123" 塞进 `PackageJson.version` —— 于是本函数返回 "123"，
                // 而 `DshInstances.installFactsVersionOf`（用 `stringOrNull`，要求 isJsonPrimitive
                // && isString）返回 null。两套判据对同一份磁盘内容给出**相反**结论：
                // isInstallComplete=true（判"装好了"）而 planRepair=NOT_INSTALLED（判"没装"）
                // ⇒ 卡片说未安装、启动口说可以起 —— 正是 fix12 要消灭的 S1 症状原地复活。
                //
                // `R26ConsistencyTest.bothJudgesAgreeOnEveryPackageJsonShape` 在修复前**实测红**在这条上。
                // 真实 npm 包不会写数字版本，但手工编辑 / 坏工具链 / 旧格式迁移都可能留下它，
                // 而"两套判据对边缘输入是否同结论"是跨实现不变量，不能靠"上游不会这么写"来豁免。
                val versionIsString = runCatching {
                    val root = com.google.gson.JsonParser.parseString(text)
                    val raw = if (root.isJsonObject) root.asJsonObject.get("version") else null
                    raw != null && raw.isJsonPrimitive && raw.asJsonPrimitive.isString
                }.getOrDefault(false)
                if (!versionIsString) return@runCatching null

                val pkg = JsonUtils.fromNonNullJson(text, PackageJson::class.java)
                pkg.version?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        /**
         * 安装 STAGE key → 阶梯百分比（BL-018 纯函数内核，可单测）。
         *
         * ★ 为什么是阶梯而不是真实百分比：npm 以 `CI=true` 运行（见 [runInstall] 的 argvEnv），
         *   不输出百分比进度条，唯一可采的进度信号就是 setup 脚本的 STAGE 行。
         * ★ 台阶值与脚本阶段顺序对应（`setup-node-dsh.sh` 的 stage 调用序）：
         *   - installing-node / adding-npm：补装 Node/npm（只有底座缺东西时才出现，可选段 10%~15%）
         *   - node-ready：Node 就绪 20%
         *   - using-preinstalled-dsh：命中预装直接完成 100%
         *   - installing：npm 下载安装（最长的一段）40%
         *   - verifying-install：校验收尾 90%
         * ★ key 认不出返回 null（界面保持无限动画），不猜 —— 与 [stageText] 未知 key 原样显示对应。
         *
         * @param raw `[setup] STAGE=` 后面的整段（`key[ args]`），key 是小写 ASCII kebab-case
         * @return 0..1 的阶梯值；认不出返回 null
         */
        fun stageFraction(raw: String): Double? = when (raw.substringBefore(' ')) {
            "installing-node" -> 0.10
            "adding-npm" -> 0.15
            "node-ready" -> 0.20
            "using-preinstalled-dsh" -> 1.00
            "installing" -> 0.40
            "verifying-install" -> 0.90
            // R20 自愈链第二趟：回到 40%（同一段工作的重来），不能超过 0.90（那是"校验"）。
            "reinstalling" -> 0.40
            else -> null
        }

        /**
         * R20：把 npm `--loglevel=http` 的一行提炼成**实时活动文本**（不认得返回 null）。
         *
         * 识别的两类真实行形（本机 npm 11 实抓）：
         * - `npm http fetch GET 200 https://registry.npmjs.org/@deepseek-ai%2fdsh 979ms (cache miss)`
         * - `npm http cache @deepseek-ai/dsh@https://registry.npmjs.org/…/dsh-0.2.1-rc.2.tgz 0ms (cache hit)`
         *
         * ★ BL-175（fix8）输出改成 **`↓ 包名` / `✓ 包名`** 两段（取"包名"而不是"URL 尾段"），三处修正：
         *   ① URL 一律按 scheme 起点找（不再按固定下标取字段 —— 失败/重试行没有状态码位，
         *      旧写法会把裸词 `attempt` 当包名显示成 `↓ attempt`）；
         *   ② 包名从 `/-/` 之前回溯（`…/@smithy/core/-/core-3.36.0.tgz` → `@smithy/core`，
         *      旧写法取最后一段 → `core-3.36.0.tgz`，scoped 包的作用域名整个丢掉）；
         *   ③ **不再带 ms**：npm 的 `<n>ms` 是"该请求从创建到响应体读完"的墙钟（含 socket 排队，
         *      maxsockets=1 时近乎单调递增，真机见过 214657ms），摆在活动行会被读成
         *      "这一个包下了 3.5 分钟"。活动行的职责是"在动"这一件事，行在换就是证据，
         *      不需要一个误导性的数字。
         *
         * ★ 前缀用**符号**（↓=网络下载 / ✓=缓存命中）而不是任何语言的词：这一行是高频率
         *   滚动细节，进三语 strings 会让每行活动都要过一遍资源表；符号零本地化、三语同观感，
         *   且与部署页"原始活动流"的形态一致（用户要看的是"在动"，不是语法完备的句子）。
         *
         * ★ 纯函数放 companion：与 [isNpmErrorLine]/[stageFraction] 同一理由 —— 这是给用户
         *   看的文本提炼，必须能直接单测（真机上它就是"死机感"的正解，不能再靠真机试）。
         */
        fun extractNpmActivity(trimmedLine: String): String? {
            // 前缀判据与 npm 官方输出格式绑定；不匹配的行（STAGE/DONE/包安装完成等）返回 null。
            val symbol = when {
                trimmedLine.startsWith("npm http fetch ") -> "↓ "
                trimmedLine.startsWith("npm http cache ") -> "✓ "
                else -> return null
            }
            // ★ BL-175①：认不出 URL（没有 http(s):// 段）就是认不出这行 —— 返回 null，不猜。
            //   注意：**不按 GET/HEAD 过滤** —— 失败/重试行族里状态码位是缺的，方法后面紧跟的
            //   就是 URL；用"有没有 200"当判据会把最需要显示的弱网行整条丢掉。
            val url = findUrl(trimmedLine) ?: return null
            val name = packageNameOf(url) ?: return null
            // 整行 ≤60 字符（与 fix7 同口径：那边截的是尾段，这里截活动行本体）—— 卡片明细位
            // 放的是 `"<stage> · <activity>"`，异常 URL 才会撑出这种长度，宁可截短不要撑爆。
            return (symbol + name).take(NPM_ACTIVITY_MAX)
        }

        /** 活动行总长上限（前缀 2 字符也算在内） */
        private const val NPM_ACTIVITY_MAX = 60

        /**
         * BL-175①：从 npm http 行里取 URL —— **按 scheme 起点**，不按空白字段下标。
         *
         * 三种真实形态一并覆盖：
         * - 成功 `npm http fetch GET 200 <url> 74ms (cache miss)`
         * - 失败/重试 `npm http fetch GET <url> attempt 1 failed with ECONNREFUSED`（无状态码位）
         * - 缓存 `npm http cache <name>@<url> 0ms (cache hit)`（URL 前贴着包名与 '@'）
         *
         * 只认 `http://` / `https://`（行首的 `npm http ` 里没有 "://"，不会误命中前缀），
         * 从 scheme 起到下一个空白为止 —— 后面跟着的 `<n>ms` / `(cache hit)` / `attempt` 都不进 URL。
         */
        private fun findUrl(line: String): String? {
            val a = line.indexOf("http://")
            val b = line.indexOf("https://")
            val start = when {
                a < 0 && b < 0 -> return null
                a < 0 -> b
                b < 0 -> a
                else -> minOf(a, b)
            }
            return line.substring(start).substringBefore(' ').takeIf { it.isNotEmpty() }
        }

        /**
         * BL-175②：URL → 包名。
         *
         * registry tarball 的固定形态是 `…/<pkg>/-/<name>-<version>.tgz`，`/-/` 就是"包名段"与
         * "文件段"的分隔符，所以有 `/-/` 时只看它**之前**的部分；包名段若倒数第二段以 `@` 开头
         * 就是作用域名，两段一起要（旧写法取最后一段 → `@smithy/core` 变成 `core`）。
         * 没有 `/-/` 的行（registry 元数据请求、hosted tarball）取最后一段；那段是文件名时
         * （`.tgz` / `.tar.gz`）再剥掉扩展与尾随版本，只留名字。
         *
         * `%2F` 必须**在按 '/' 分组之后**再还原：`@deepseek-ai%2fdsh` 整体才是"一段"包名，
         * 先还原会让它被当成两段、只剩 `dsh`（丢 scope 的另一条路径）。
         *
         * ★ 附带一条**行为变化**：npm 站内端点行（`/-/npm/v1/security/audits/quick` 这类，
         *   路径首段是单独的 "-"）不是包请求。旧写法对它取"URL 最后一段"，显示成
         *   `↓ quick`（或干脆 `↓ 12ms`）—— 现在返回 null，不占活动行。理由见判据处注释。
         */
        private fun packageNameOf(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
            // host 之后的路径（scheme 与 host 一律不进包名）
            val afterHost = path.substringAfter("://", "").substringAfter('/', "")
                .ifEmpty { return null }
            val segments = afterHost.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null
            // ★ npm **站内端点**（`/-/npm/v1/security/audits`、`/-/user/org.couchdb.user:x`）不是
            //   包请求：路径首段是单独的 "-"（npm 的包名不允许以 '-' 开头，所以这个判据无歧义）。
            //   这类行返回 null —— 显示 `↓ quick` 比什么都不显示更让人以为在装一个叫 quick 的包。
            if (segments.first() == "-") return null
            // 步 1：`/-/` 之前才是包名段（registry tarball 形态）：它之前的段里倒数第二段以 '@' 开头
            //    就是作用域名，两段一起要。
            val sep = afterHost.indexOf("/-/")
            val parts = if (sep >= 0) segments.dropLast(2) else segments
            if (parts.isEmpty()) return null
            val last = parts.last()
            val scope = parts.dropLast(1).lastOrNull()?.takeIf { it.startsWith("@") }
            // 步 2：%2F 还原（编码形态的 scoped 名整体就是一段）；段已过滤过空串，decoded 必非空
            val decoded = (if (scope != null) "$scope/$last" else last)
                .replace("%2F", "/").replace("%2f", "/")
            if (sep >= 0) return decoded
            // 步 3：无 `/-/` 且末段是文件名（hosted tarball）：剥掉扩展与尾随版本，只留名字
            if (!(decoded.endsWith(".tgz") || decoded.endsWith(".tar.gz"))) return decoded
            val base = decoded.removeSuffix(".tgz").removeSuffix(".tar.gz")
            val cut = TARBALL_VERSION_TAIL.find(base)?.range?.first ?: base.length
            // 整段都被当版本吃掉的畸形名（`-1.2.3.tgz`）→ null，不输出光剩前缀的活动行
            return base.substring(0, cut).takeIf { it.isNotEmpty() }
        }

        /** tarball 文件名尾部的 `-<数字开头的版本段>`（`typebox-1.3.27` → 去掉 `-1.3.27`） */
        private val TARBALL_VERSION_TAIL = Regex("-\\d[\\w.+\\-]*$")

        // --- BL-043/053/054 的纯判断内核（public，便于 JVM 单测；class 头那套理由） ----------

        /**
         * BL-054：`dir` 里是不是一份**完整**的 dsh 包（[readInstalledVersion] 的判定内核）。
         *
         * 判据三条全过才算完整：
         * 1. `package.json` 能读出非空 version（这是"装到了"的最低证据）；
         * 2. `lib/bin.js` 存在 —— 缺失是**否定判据**（不再是旧版的"只警告"）。依据：
         *    `setup-node-dsh.sh` 的 verifying-install 段自己就以它为准（missing-bin-js → FAILED），
         *    `start-dsh.sh` 也以它为入口解析起点 —— 能读出版本号却起不来的包不能算"装完"；
         * 3. [requestedVersion] 非 null 时须与磁盘版本**字面相等**（`latest` 除外，见下）——
         *    晚取消"其实已装完"的场景里，装的必须是**用户要的那个版本**。这一条同时修掉旧判定
         *    的一个误标：实例自己没有包、`effectiveDshDir` 回退到 rootfs **预装**包时，旧代码会
         *    把预装版本标成"其实已装完"（哪怕用户要的是另一个版本）；[DshInstances.repair]
         *    的口径正是"预装只在它 == 实例目标版本时才算数"，现在两边一致。
         *
         * ★ `latest` 按**跳过比对**处理：它是界面上的"最新版"（脚本对 latest 按字面命中预装分支；
         *   registry 列表里 version 字段始终是精确版本，latest 只是 tag），装到哪个精确版本都算
         *   完成。当前 UI 路径不会把 latest 传进 [install]（下载页永远传 entry.version），
         *   这里是防御 —— 与 [nodeModulesBudgetBytes] 对 latest 的处理同一口径。
         *
         * @param requestedVersion 本次请求的目标版本；null / "latest" = 跳过版本比对（只验完整性）
         * @return true = 完整可启动（版本相符）；false = 不完整 / 版本不符 / 读不到
         */
        fun isInstallComplete(dir: File, requestedVersion: String?): Boolean {
            val version = readVersionFromPackageJson(File(dir, "package.json")) ?: return false
            if (!File(dir, "lib/bin.js").isFile) return false
            if (requestedVersion != null && requestedVersion != "latest" && version != requestedVersion) return false
            return true
        }

        /**
         * BL-053：这一行是否属于"重要行"（节流必须让路、立即发射）——
         * [isNpmErrorLine]（npm 错误）/ `[setup] FAILED` / `[setup] STAGE=` / `[setup] DONE` /
         * [PREFLIGHT_FAIL_PREFIX] 与本类内各分支及脚本协议（STAGE/FAILED/DONE 三条机器可读
         * 输出，见 `setup-node-dsh.sh` 头部约定）一一对应。STAGE 行同时驱动 [Progress.Stage]、
         * DONE 行是安装完成的第一现场、错误行要马上可见 —— 都不等节流窗口。
         */
        fun isImportantInstallLine(trimmedLine: String): Boolean =
            trimmedLine.startsWith("[setup] STAGE=") ||
                trimmedLine.startsWith("[setup] FAILED") ||
                trimmedLine.startsWith("[setup] DONE") ||
                // BL-191（fix9）：预装命中/未命中这两行决定"这次装得快还是慢"，
                // 是排查"为什么又要全量下载"的第一分叉点 —— 必须立即发射，不能进 pending 缓冲。
                trimmedLine.startsWith("[setup] preinstalled") ||
                trimmedLine.startsWith("[setup] using preinstalled") ||
                trimmedLine.startsWith(PREFLIGHT_FAIL_PREFIX) ||
                isNpmErrorLine(trimmedLine)

        /**
         * BL-053：节流决策内核（`onInstallLine` 每行调用一次）。
         *
         * 规则：重要行**永远发射**；普通行距上次发射 ≥ [LOG_THROTTLE_MIN_INTERVAL_MS] 才发射。
         * 窗口从"上次**发射**时刻"起算（重要行也刷新 lastEmit —— 它自己就是一次发射）。
         * 首次发射（lastEmitMs == 0）：立即放行 —— 安装开头第一行就该看得见。
         *
         * @param nowMs 当前毫秒（注入以便单测）
         * @param lastEmitMs 上次发射时刻；0 = 还没发射过
         * @param important 是否重要行（[isImportantInstallLine]）
         */
        fun shouldEmitLog(nowMs: Long, lastEmitMs: Long, important: Boolean): Boolean =
            important || lastEmitMs == 0L || nowMs - lastEmitMs >= LOG_THROTTLE_MIN_INTERVAL_MS

        /**
         * BL-043①：cancel 入口守卫的判据（两条都要，理由见 [cancel] 的方法注释）。
         * 抽成纯函数是为了可单测 —— 守卫本身挂在 [DshInstances] 等进程级单例上，单测构造不出来。
         *
         * @param statusRunning [statuses] 里该实例是否仍有进行中条目
         * @param taskRegistered [running] 里该实例是否仍有登记的任务
         */
        fun canCancelInstall(statusRunning: Boolean, taskRegistered: Boolean): Boolean =
            statusRunning && taskRegistered

        /** BL-053：普通行的节流窗口（100ms ≈ 10Hz）—— 安装高峰每秒几十行 → 最多 10 次 StateFlow 写 */
        const val LOG_THROTTLE_MIN_INTERVAL_MS = 100L

        /** R20：活动行写入窗口（200ms ≈ 5Hz）—— 理由见 [onInstallLine] 活动分支注释 */
        const val ACTIVITY_THROTTLE_MS = 200L

        /** BL-053：pending 缓冲上限（只保尾部；收尾 flush 合并成一条发射） */
        const val PENDING_LOG_MAX_LINES = 50
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
