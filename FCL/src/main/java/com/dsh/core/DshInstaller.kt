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

    fun isInstalling(instanceId: String): Boolean = gate.isRunning(instanceId)

    fun statusOf(instanceId: String): InstallStatus? = _statuses.value[instanceId]

    /**
     * 安装指定版本到实例。会把实例状态置为 INSTALLING，成功后置 READY 并记录**真实**版本。
     *
     * @return true 表示本次已受理；false 表示该实例已有安装在跑（会被忽略）
     */
    fun install(instance: DshInstance, version: String): Boolean {
        if (!gate.tryAcquire(instance.id)) {
            // 实例级：说的是"这个实例已有安装在跑"，用户点开它的日志页该看到这次请求为什么被忽略
            DshLogBus.appendFor(instance.id, "[install] ${instance.name} 已有安装任务在进行，忽略重复请求")
            return false
        }
        cancelled.remove(instance.id)
        errorSummaries.remove(instance.id)
        errorTails.remove(instance.id)
        DshInstances.markState(instance.id, DshInstance.State.INSTALLING, version = version, error = null)
        updateStatus(
            InstallStatus(instance.id, version, DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_stage_prepare, "install failed"))
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
                    // 实例级：讲的是**这个实例**的这次安装的结局（被后一次接管），属于它的日志
                    DshLogBus.appendFor(instance.id, "[install] ${instance.name} 本次安装已被更新的一次安装接管，忽略本次结果")
                    return@launch
                }
                if (cancelled.contains(instance.id)) {
                    // 用户取消了：不能用 npm 的失败输出把实例标成 BROKEN。
                    // 但如果文件其实已经装完整（取消来得太晚），按磁盘事实标记 READY，
                    // 否则状态会卡在"安装中"直到下次启动 App 才被 repair() 纠正。
                    val resolved = readInstalledVersion(instance)
                    if (resolved != null) {
                        DshInstances.markState(instance.id, DshInstance.State.READY, resolved)
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
                        DshLogBus.appendFor(instance.id, "[install] ${instance.name} 的安装已取消，忽略本次结果")
                    }
                } else if (ok) {
                    val resolved = readInstalledVersion(instance)
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
            } catch (t: Throwable) {
                if (!isCurrentOwner(instance.id, myJob)) {
                    DshLogBus.appendFor(instance.id, "[install] ${instance.name} 本次安装已被接管，忽略异常：${t.message ?: t}")
                } else if (!cancelled.contains(instance.id)) {
                    fail(instance, t.message ?: t.toString())
                } else {
                    DshLogBus.appendFor(instance.id, "[install] ${instance.name} 的安装已取消（${t.message ?: t}）")
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
     *
     * ★ **本轮补上的终态上报**（治"取消后界面还显示安装中"）：
     *   1. 目标版本要在**清 [statuses] 之前**读出来 —— 那一步正是把版本信息抹掉的那一步，
     *      读晚了就只能发一条 `version == null` 的取消事件，界面无从知道该清哪个版本的标记。
     *   2. 发 [Progress.Cancelled]：订阅 [progress] 的界面（下载页）等的是**事件**，
     *      而这里原来一个事件都不发 —— 订户永远收不到终态，"安装中"标记只能等重启 App。
     *   3. 上报 [DshTasks.report]（state = CANCELLED）：任务区要能显示"已取消"这个**结果**。
     *      没有它，任务行在取消的瞬间直接消失（进行中列表是每帧重算的投影），用户看不出发生了什么。
     */
    fun cancel(instanceId: String) {
        // 先取版本：下面清 statuses 之后就再也拿不到了（见方法注释第 1 点）
        val version = _statuses.value[instanceId]?.version
        val error = DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_install_cancelled, "install failed")
        // 先打标记再取消：任务体是阻塞在 proot 上的（协程取消打断不了阻塞调用），
        // 它会等到进程被杀、runInstall 返回非 0 之后才继续；如果没有这个标记，
        // 用户点"取消"会看到实例变成 BROKEN（"安装失败"）而不是"已取消"。
        cancelled.add(instanceId)
        running[instanceId]?.cancel()
        gate.release(instanceId)
        killActive(instanceId)
        _statuses.update { it - instanceId }
        DshInstances.markState(instanceId, DshInstance.State.NOT_INSTALLED, error = error)
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
        _progress.value = Progress.Failed(reason, instance.dshVersion)
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

    /** 安装成功的终态任务。三处上报（常规成功、取消时已装完）共用一份，免得文案/字段漂移。 */
    private fun doneTask(instance: DshInstance, version: String): DshTask = DshTask(
        id = DshTasks.installTaskId(instance.id),
        kind = DshTask.Kind.INSTALL,
        title = instance.name,
        stage = "dsh $version",
        action = DshTask.Action.NONE,
        state = DshTask.State.DONE,
        instanceId = instance.id
    )

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
        if (isEnoughSpaceForInstall(need, free)) return null
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
            File(DshPaths.instanceDir(instance.id), "node_modules/@deepseek-ai/dsh/package.json")
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

    private fun runInstall(instance: DshInstance, version: String): Boolean {
        errorSummaries.remove(instance.id)
        errorTails.remove(instance.id)
        val script = "${ProotCommand.GUEST_ROOT}/scripts/setup-node-dsh.sh"
        val pre = ProotCommand.preflight(context, DshPaths.ROOTFS_DIR, script)
        if (!pre.ok) {
            errorSummaries[instance.id] = pre.reason ?: "预检失败（原因未知）"
            // 实例级：预检是"这次安装"的第一步，失败原因是该实例的安装结论
            DshLogBus.appendFor(instance.id, "[install] 预检失败：${pre.reason}")
            return false
        }

        updateStatus(
            InstallStatus(
                instance.id, version,
                DshUiText.getString(com.dsh.fcl.androidlauncher.R.string.dsh_stage_prepare, "install failed"),
                fraction = 0.05
            )
        )

        // 宿主侧确保实例目录结构存在
        DshPaths.instanceDir(instance.id).mkdirs()
        DshPaths.instanceHome(instance.id).mkdirs()
        DshPaths.instanceWorkspace(instance.id).mkdirs()
        File(DshPaths.NPM_CACHE_DIR).mkdirs()

        // ★ 起 npm 之前的空间检查（判据与"要留多少"见 [checkInstallSpace] / [nodeModulesBudgetBytes]）。
        //   放在这里是因为：① 预检已经过了，失败原因不会再被预检文案顶掉；
        //   ② 目录已经建好，报出来的"当前剩余"就是真正要写入的那个分区；
        //   ③ 还没起 proot/npm —— 空间不足时立刻返回，而不是让用户等 29 分钟再拿到
        //     一个"安装失败（详见日志）"（真机实测：盘上只剩约 1.1 GB，而一次安装
        //     要写 500 MB 上下；写满盘时同样只会剩下那句泛化文案）。
        val shortfall = checkInstallSpace(instance, version)
        if (shortfall != null) {
            errorSummaries[instance.id] = shortfall
            // 实例级：这是"这个实例这次装不上"的原因，装它的人该在自己的日志里看到
            DshLogBus.appendFor(instance.id, "[install] 空间不足，已跳过 npm：$shortfall")
            return false
        }

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
            errorSummaries[instance.id] = DshUiText.getString(
                com.dsh.fcl.androidlauncher.R.string.dsh_install_timeout,
                "Install timed out",
                INSTALL_TIMEOUT_MS / 60_000
            )
        }
        return exit == 0
    }

    private fun onInstallLine(instance: DshInstance, version: String, line: String) {
        // 实例级：这是**这个实例的** npm / setup 脚本输出（安装进度、下载日志、失败摘要的原文）。
        // 装 A 的时候日志页就该只看到 A 的 npm 输出 —— 原来两条实例同时装时这里全混在一条流里。
        DshLogBus.appendFor(instance.id, line)
        _progress.value = Progress.Log(line)
        val trimmed = line.trim()
        when {
            trimmed.startsWith("[setup] STAGE=") -> {
                // ★ 脚本给的是**稳定 key**（`[setup] STAGE=<key> [args]`），不是给人看的句子。
                //   翻译在 [stageText] 里做，这样切到英文界面时进度也是英文 —— 改造前脚本直接
                //   输出中文句子（"下载并安装 @deepseek-ai/dsh@0.2.1-alpha.1"），而 rootfs 里的
                //   脚本没有 i18n 机制，于是英文界面下这一段永远是中文。
                val stage = stageText(trimmed.removePrefix("[setup] STAGE="))
                updateStatus(InstallStatus(instance.id, version, stage, fraction = null))
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
        }
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
     */
    private fun readInstalledVersion(instance: DshInstance): String? {
        val dir = DshPaths.effectiveDshDir(instance.id)
        val version = readVersionFromPackageJson(File(dir, "package.json"))
        if (version != null && !File(dir, "lib/bin.js").isFile) {
            // 实例级：缺入口文件是**这个实例**装坏了（"能读出版本却启动不了"的原因就在这行）
            DshLogBus.appendFor(instance.id, "[install] 警告：${instance.name} 缺少 lib/bin.js（$dir）")
        }
        return version
    }

    companion object {
        /** 安装最长容忍时间：低于此值可能是网络慢，高于此值基本是卡死 */
        const val INSTALL_TIMEOUT_MS = 30 * 60 * 1000L

        /** [errorTails] 保留的错误行数：够看清"哪一步、什么码"，又不会被前面的警告行顶掉 */
        private const val ERROR_TAIL_LINES = 12

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

        private data class PackageJson(
            @SerializedName("version") val version: String? = null
        )

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
