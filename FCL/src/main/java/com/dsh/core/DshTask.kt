package com.dsh.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一条任务。既能表达**进行中**，也能表达**已经结束**。
 *
 * 首页要把**所有正在跑的任务**集中展示（解压运行环境 / 安装 dsh / 启动 / 删除），
 * 而它们分散在四个模块里（[DshBootstrap] / [DshInstaller] / [DshRuntime] / [DshInstances]），
 * 所以这里做一层聚合，界面只订阅 [DshTasks.tasks]（进行中）与 [DshTasks.finished]（已结束）。
 *
 * ## 本次改造：补上"状态"这一维
 *
 * 改造前这个数据类只有 `id / kind / title / stage / detail / fraction / action` —— **没有任何
 * 字段能说"这件事已经结束了"**。后果不是文案不好看，而是两件真机上能复现的坏事：
 * 1. 取消安装后，任务行**瞬间消失**（因为进行中那份列表是每帧重算的投影，取消后那个实例的
 *    `running` 条目就没了），用户看不到"已取消"这个结果，只觉得"它还在装"；
 * 2. 订阅安装进度的那块界面（下载页的"安装中"标记）等的是一个**事件**，而取消路径当时
 *    什么事件都不发，于是标记永远留在原地，要重启 App 才恢复。
 *
 * 所以这里加上 [state]（终态维度）、[instanceId]（这条任务属于谁）、[error]、起止时间。
 *
 * ★ **新增字段全部带默认值**：[state] 默认 [State.RUNNING]，其余默认 null / 0。理由是现有构造点
 *   （[DshTasks] 内部那几处）大多在造"进行中"的任务，不该被迫逐个改；接口向后兼容之后，
 *   "哪些地方还没上报终态"就变成一件可以增量补的事，而不是一次大爆炸式改动。
 *
 * ★ **[Action] 保持原样**（`NONE / CANCEL / STOP`）：界面按它决定右侧按钮画不画、画哪个图标，
 *   换个形状等于把界面一起改掉 —— 那是另一件事。
 */
data class DshTask(
    /** 稳定标识（同一种任务同 id，避免每次刷新都换 key 导致列表动画/复用错乱） */
    val id: String,
    val kind: Kind,
    /** 主标题（实例名或"运行环境"） */
    val title: String,
    /** 阶段文案（"安装中…"、"解压 Linux rootfs"） */
    val stage: String,
    /** 明细（可为空），如正在解压的文件名 */
    val detail: String? = null,
    /** 0..1；null = 无法估算（界面用不确定进度条） */
    val fraction: Double? = null,
    /** 右侧动作 */
    val action: Action,

    // ── 以下为本次新增（全部带默认值，见类注释） ─────────────────────────────

    /**
     * 这条任务现在处于哪个终态维度。
     *
     * 默认 [State.RUNNING]，因为绝大多数构造点造的就是"进行中"；而且 [DshTasks.tasks] 那份投影里
     * 出现的一切**必然**是 RUNNING —— 这不是巧合，是定义：一件事一旦结束，它就该从那条流里消失、
     * 改从 [DshTasks.finished] 出现。
     */
    val state: State = State.RUNNING,
    /** 属于哪个实例；null = 与实例无关（例如运行环境解压） */
    val instanceId: String? = null,
    /** 失败原因；[state] == [State.FAILED] 时应当有值（其余状态允许为 null） */
    val error: String? = null,
    /** 任务开始时刻（`System.currentTimeMillis()`）；0 = 没记（默认值，别拿它算耗时） */
    val startedAt: Long = 0L,
    /** 结束时刻；null = 还没结束。与 [startedAt] 配对，供界面显示"刚刚 / 3 分钟前" */
    val finishedAt: Long? = null
) {
    enum class Kind {
        /** 解压/校验运行环境（首启前置） */
        BOOTSTRAP,

        /** 安装 dsh 到某实例 */
        INSTALL,

        /** 启动/停止实例 */
        RUNTIME,

        /** 删除实例 */
        DELETE
    }

    /** 右侧动作按钮（NONE = 不显示按钮，如删除中/停止中不可打断） */
    enum class Action { NONE, CANCEL, STOP }

    /**
     * 任务的终态维度。
     *
     * ★ 为什么"在不在 [DshTasks.tasks] 里"不够用：因为"不在列表里"**分不出**它是装完了、失败了、
     *   还是被取消了 —— 三者要给用户看的文案完全不同；而且"用户点了取消"与"npm 自己失败退出"在
     *   状态流里长得一模一样（都是"那个实例的 running 条目不见了"）。只有**产生这件事的那段代码**
     *   知道答案，所以它必须说出来（见 [DshTasks.report]）。
     */
    enum class State {
        /** 进行中（默认） */
        RUNNING,

        /** 正常完成 */
        DONE,

        /** 失败（[error] 应带原因） */
        FAILED,

        /** 被用户取消 / 主动停止 */
        CANCELLED
    }

    /** 是否已结束（非 RUNNING） */
    val isFinished: Boolean get() = state != State.RUNNING
}

/**
 * 任务聚合器。对**进行中**的任务是只读投影：所有状态都来自各模块既有的 StateFlow，不引入新的真值来源。
 *
 * ## 两个出口，两种性质
 * - [tasks]：**状态**。每帧从各模块的 StateFlow 重算（谁"现在"在跑），所以它天然只有"进行中"。
 * - [finished]：**事件**。由任务结束的那一刻、知道结局的那段代码通过 [report] 推过来。
 *
 * ★ 为什么终态必须是 push 而不是 pull：投影只能回答"这个东西现在还在不在"，回答不了"它怎么没的"。
 *   若靠"保存上一帧再做差分"来补，两个问题都过不去：① 取消与自然结束在流里完全同形（都是"条目
 *   消失"），差分不出来；② 状态流是**合帧**的 —— 同一轮里"开始"和"结束"只发一次通知，差分连
 *   "这个条目存在过"都看不到。终态是事件，事件要由当事人上报。
 */
object DshTasks {

    /** [finished] 最多保留多少条（环形列表，超了裁掉最老的） */
    const val MAX_FINISHED = 20

    private val _tasks = MutableStateFlow<List<DshTask>>(emptyList())
    val tasks: StateFlow<List<DshTask>> = _tasks.asStateFlow()

    private val _finished = MutableStateFlow<List<DshTask>>(emptyList())

    /**
     * 已结束的任务（完成/失败/取消），**最新的在前**。
     *
     * ★ **不落盘**（重启即清空）：它是给用户看"刚才那件事怎么样了"的，不是审计日志。落盘会带来
     *   一堆额外问题（陈旧条目跨版本、清理时机、与实例删除的先后关系），而对用户价值的提升为零 ——
     *   真要追溯历史，走的是按实例归档的日志。
     */
    val finished: StateFlow<List<DshTask>> = _finished.asStateFlow()

    private var started = false

    /**
     * Java 侧入口（FCLApp.onCreate 调用）：自建进程级作用域后开始聚合。
     * 单独给个方法是为了避免 Java 里拼 Kotlin 的 CoroutineScope/Job/Dispatcher。
     */
    @JvmStatic
    fun startDefault() {
        start(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    /** 开始聚合（幂等） */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        val ctx = DshAppContextHolder.context ?: return
        val installer = DshServices.installer(ctx)
        scope.launch {
            combine(
                DshBootstrap.busy,
                DshBootstrap.progress,
                installer.statuses,
                DshRuntime.state,
                DshInstances.deleting
            ) { busy, progress, statuses, runtime, deleting ->
                buildList {
                    // 1) 运行环境（解压 / 自检）
                    if (busy) add(bootstrapTask(progress))

                    // 2) 安装（每个实例一条）
                    statuses.values.filter { it.running }.forEach { add(installTask(it)) }

                    // 3) 启动中 / 停止中（Running 是稳定态，不算"进行中"，由实例卡片表达）
                    when (runtime) {
                        is DshRuntime.State.Starting -> add(
                            DshTask(
                                id = runtimeTaskId(runtime.instanceId),
                                kind = DshTask.Kind.RUNTIME,
                                title = runtime.name,
                                stage = STAGE_STARTING,
                                action = DshTask.Action.STOP,
                                instanceId = runtime.instanceId
                            )
                        )
                        is DshRuntime.State.Stopping -> add(
                            DshTask(
                                id = runtimeTaskId(runtime.instanceId),
                                kind = DshTask.Kind.RUNTIME,
                                title = runtime.name,
                                stage = STAGE_STOPPING,
                                action = DshTask.Action.NONE,
                                instanceId = runtime.instanceId
                            )
                        )
                        else -> Unit
                    }

                    // 4) 删除实例
                    deleting.forEach { id ->
                        add(
                            DshTask(
                                id = deleteTaskId(id),
                                kind = DshTask.Kind.DELETE,
                                title = DshInstances.byId(id)?.name ?: id,
                                stage = STAGE_DELETING,
                                action = DshTask.Action.NONE,
                                instanceId = id
                            )
                        )
                    }
                }
            }.collect { _tasks.value = it }
        }
    }

    /**
     * 上报一条任务的终态。
     *
     * 为什么是 push 而不是 pull：终态是**事件**。看状态流只能得到"这个东西现在不在了"，
     * 而分不清它是完成了、失败了、还是被取消了 —— 三者要显示的文案不同。
     * 只有产生这件事的那段代码知道答案，所以由它来说。
     *
     * @param task 必须带**终态**（[DshTask.State.DONE] / [DshTask.State.FAILED] /
     *             [DshTask.State.CANCELLED]）。
     *
     * ★ 传 RUNNING 进来为什么是"记一行日志、丢掉"而不是 `require` 直接抛：
     *   本方法的调用点散在四个模块里，且大多位于三类路径上 —— 协程的 `finally`、进程退出回调、
     *   用户点击取消。在这些地方抛异常不是"早失败"，而是把"上报写错"升级成"取消没生效、安装协程
     *   带着异常结束、资源没人清理"的二次故障。上报只是**辅助**信息，绝不能反过来破坏它要描述的
     *   那件事本身。所以这里选择"拒绝这条脏数据 + 落一行日志（能在日志里查到是谁写错的）+
     *   正常返回"，把代价限制在"少一条历史记录"。
     *   `require` 式校验只适合纯计算、无副作用的 API；这里是跨模块的事件入口，选取"尽量不伤人"。
     *   这条行为由 `DshTaskStateTest.reportRunningIsIgnoredWithoutThrowing` 钉住。
     */
    fun report(task: DshTask) {
        if (task.isFinished) {
            _finished.update { current ->
                // 最新在前；超上限裁掉最老的（保留最近 MAX_FINISHED 条）
                (listOf(withFinishedAt(task)) + current).take(MAX_FINISHED)
            }
            return
        }
        // 走到这里说明调用方把"进行中"当终态上报了：拒绝入库，但要留下可查的痕迹。
        // ★ 保持全局流：上报写错属于"聚合器被用错"，与任何单个实例无关。
        DshLogBus.append("[tasks] 忽略非终态的上报（state=RUNNING）：${task.kind}/${task.id}")
    }

    /**
     * 补上 [DshTask.finishedAt]。
     *
     * 放在聚合器里而不是要求每个调用点自己填：四个模块、七八个上报点，总会有漏填的；而"结束时间"
     * 的语义在**上报那一刻**是确定的（上报就等于宣告结束），由入口统一兜底比在每个调用点重复一遍
     * 可靠。调用方自己填了值就尊重它的值（例如安装协程里更精确的结束时刻）。
     */
    private fun withFinishedAt(task: DshTask): DshTask =
        if (task.finishedAt != null) task else task.copy(finishedAt = System.currentTimeMillis())

    /**
     * 清空已结束任务（界面上的「清空已完成」）。
     *
     * ★ 为什么这条必要：[finished] 只保留最近 [MAX_FINISHED] 条，**不会**无限增长 ——
     *   但那是**内存**上限，不是**界面**上限。首页任务区把进行中与已结束一起画（进行中在上、
     *   历史在下），20 行历史足以把下方的实例列表挤出屏幕：用户装了三次失败之后，首页上半屏
     *   全是"安装失败"，而他想点的实例在下面看不见。清理动作交给用户，是因为"什么时候该忘掉"
     *   只有他知道 —— 自动淡出会在用户正看着那一行时把它拿走（比如他正想点进日志），
     *   而"只显示最近 N 条"则会让更早的失败凭空消失、连"它失败过"都不留痕迹。
     *
     * ★ 与 [clearFinishedForTest] 的关系：两个方法体一样，但语义不同（一个是用户动作、
     *   一个是测试复位），且**不能合并** —— 测试需要一个"不受界面改动影响"的复位入口，
     *   而界面需要一个能随交互演进的入口（例如将来清空时顺带记一行日志）。
     *   既有签名一律不动，只新增这一个。
     */
    fun clearFinished() {
        _finished.value = emptyList()
    }

    /**
     * 清空已结束任务（测试/复位用）。
     *
     * ★ 为什么是 public 而不是 `internal`：本项目的 JVM 单测是把**测试源码单独编译**成另一个模块跑的
     *   （见 `run-tests.sh`：测试与主代码是两次独立的 kotlinc 调用，没有 friend-paths），
     *   `internal` 在那里不可见 —— 测试会因为"看不见这个方法"而编译失败，症状还像是环境坏了。
     *   [DshLogBus.flushNow] 同样是"给测试用的公开方法"，这里与它保持一致。
     *
     * ★ 为什么必须有：`DshTasks` 是进程内单例，而"上限裁掉最老的"这类断言要求 [finished] 从一个
     *   确定的起点开始；不清的话，上一条测试留下的行会把新灌的行提前挤出去，断言就随执行顺序时过时不过。
     */
    fun clearFinishedForTest() {
        _finished.value = emptyList()
    }

    /**
     * 删除任务的 id。
     *
     * ★ 抽成函数而不是在两处各拼一次字符串：投影（[tasks]）与终态上报必须**用同一个 id**，
     *   否则界面上的"删除中"和"已删除"会被当成两条不同的任务（将来的去重/替换逻辑会因此失效）。
     */
    fun deleteTaskId(instanceId: String): String = "delete:" + instanceId

    /**
     * 运行任务的 id。同上：投影与终态上报共用一份拼法 —— [DshRuntime] 上报"启动完成/失败/已停止"
     * 时用的就是它，两边拼法一旦分叉，界面上的"启动中"和"已启动"会被当成两条不同的任务。
     */
    fun runtimeTaskId(instanceId: String): String = "runtime:" + instanceId

    private fun bootstrapTask(p: DshBootstrap.Progress?): DshTask {
        // 文案由界面按 kind 本地化；这里只给"结构性"内容（阶段/明细/进度）
        val stage: String
        val detail: String?
        val fraction: Double?
        when (p) {
            is DshBootstrap.Progress.Stage -> {
                stage = p.text; detail = null; fraction = p.fraction
            }
            is DshBootstrap.Progress.Detail -> {
                stage = STAGE_BOOTSTRAP_RUNNING; detail = p.detail; fraction = null
            }
            is DshBootstrap.Progress.Failed -> {
                stage = p.reason; detail = null; fraction = null
            }
            else -> {
                stage = STAGE_BOOTSTRAP_RUNNING; detail = null; fraction = null
            }
        }
        return DshTask(
            id = "bootstrap",
            kind = DshTask.Kind.BOOTSTRAP,
            title = "",
            stage = stage,
            detail = detail,
            fraction = fraction,
            action = DshTask.Action.NONE
            // instanceId 保持 null：运行环境是所有实例共享的底座，不属于任何一个实例
        )
    }

    private fun installTask(st: DshInstaller.InstallStatus): DshTask = DshTask(
        id = installTaskId(st.instanceId),
        kind = DshTask.Kind.INSTALL,
        title = DshInstances.byId(st.instanceId)?.name ?: st.version,
        stage = st.stage,
        fraction = st.fraction,
        action = DshTask.Action.CANCEL,
        instanceId = st.instanceId
    )

    /** 安装任务的 id。同上：投影与终态上报共用一份拼法。 */
    fun installTaskId(instanceId: String): String = "install:" + instanceId

    /**
     * 取消某条任务（取消安装 / 停止实例）。
     *
     * ★ 两个分支都改成"按 [DshTask.instanceId] 定位，而不是按当前状态定位"：
     *   - INSTALL 原来从 `task.id` 里 `removePrefix("install:")` 反解实例 id。那要求 id 的拼法
     *     永远与这里保持一致（两处各写一遍字面量，改一处就静默错位），现在统一走 [DshTask.instanceId]。
     *   - RUNTIME 原来无条件 `DshRuntime.stop()` —— 而 `stop()` 停的是"当前正在跑的那个实例"，
     *     **与传进来的 task 无关**。任务行渲染到用户点下按钮之间，运行状态完全可能已经换成另一个
     *     实例（单实例策略下"停 A 起 B"就会），此时这一下点的是 A 那一行、停掉的却是 B。
     *     现在走 [DshRuntime.stopIf]，只在"当前跑的确实是这条任务说的那个实例"时才动手。
     */
    fun cancel(task: DshTask) {
        val ctx = DshAppContextHolder.context ?: return
        when (task.kind) {
            DshTask.Kind.INSTALL ->
                task.instanceId?.let { DshServices.installer(ctx).cancel(it) }
            DshTask.Kind.RUNTIME ->
                task.instanceId?.let { DshRuntime.stopIf(it, "用户取消") }
            else -> Unit
        }
    }

    // 存档：这几个常量只在"无法从 Progress 拿到文案"时兜底，界面会按 kind 再取本地化字符串
    private const val STAGE_BOOTSTRAP_RUNNING = "Preparing runtime"
    private const val STAGE_STARTING = "Starting"
    private const val STAGE_STOPPING = "Stopping"
    private const val STAGE_DELETING = "Deleting"
}

/**
 * 让 core 层拿得到 Application Context（取消安装需要它）。
 * 在 [com.tungsten.fcl.FCLApp.onCreate] 里初始化。
 */
object DshAppContextHolder {
    @Volatile
    var context: Context? = null
        private set

    @JvmStatic
    fun init(context: Context) {
        this.context = context.applicationContext
    }
}
