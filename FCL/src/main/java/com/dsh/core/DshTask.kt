package com.dsh.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 一条「进行中的任务」。
 *
 * 首页要把**所有正在跑的任务**集中展示（解压运行环境 / 安装 dsh / 启动 / 删除），
 * 而它们分散在四个模块里（[DshBootstrap] / [DshInstaller] / [DshRuntime] / [DshInstances]），
 * 所以这里做一层聚合，界面只订阅 [DshTasks.tasks]。
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
    val action: Action
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
}

/**
 * 任务聚合器。**只读**：所有状态都来自各模块既有的 StateFlow，不引入新的真值来源。
 */
object DshTasks {

    private val _tasks = MutableStateFlow<List<DshTask>>(emptyList())
    val tasks: StateFlow<List<DshTask>> = _tasks.asStateFlow()

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
                                id = "runtime:" + runtime.instanceId,
                                kind = DshTask.Kind.RUNTIME,
                                title = runtime.name,
                                stage = STAGE_STARTING,
                                action = DshTask.Action.STOP
                            )
                        )
                        is DshRuntime.State.Stopping -> add(
                            DshTask(
                                id = "runtime:" + runtime.instanceId,
                                kind = DshTask.Kind.RUNTIME,
                                title = runtime.name,
                                stage = STAGE_STOPPING,
                                action = DshTask.Action.NONE
                            )
                        )
                        else -> Unit
                    }

                    // 4) 删除实例
                    deleting.forEach { id ->
                        add(
                            DshTask(
                                id = "delete:" + id,
                                kind = DshTask.Kind.DELETE,
                                title = DshInstances.byId(id)?.name ?: id,
                                stage = STAGE_DELETING,
                                action = DshTask.Action.NONE
                            )
                        )
                    }
                }
            }.collect { _tasks.value = it }
        }
    }

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
        )
    }

    private fun installTask(st: DshInstaller.InstallStatus): DshTask = DshTask(
        id = "install:" + st.instanceId,
        kind = DshTask.Kind.INSTALL,
        title = DshInstances.byId(st.instanceId)?.name ?: st.version,
        stage = st.stage,
        fraction = st.fraction,
        action = DshTask.Action.CANCEL
    )

    /** 取消某条任务（取消安装 / 停止实例） */
    fun cancel(task: DshTask) {
        val ctx = DshAppContextHolder.context ?: return
        when (task.kind) {
            DshTask.Kind.INSTALL ->
                DshServices.installer(ctx).cancel(task.id.removePrefix("install:"))
            DshTask.Kind.RUNTIME -> DshRuntime.stop("用户取消")
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
