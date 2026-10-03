package com.dsh.ui.shell

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.core.DshTask
import com.dsh.core.DshTasks
import com.dsh.core.DshCredentials
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
import com.dsh.core.DshServices
import com.dsh.ui.DshInstanceAdapter
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshInstancesBinding
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 实例列表页（外壳 tab 0）。由原 `DshInstancesActivity` 迁移为 [DshPageUI]。
 *
 * 迁移要点：
 * - 原 `lifecycleScope` + `repeatOnLifecycle` 换成页面级 [scope]（页面被 ViewPager 回收即取消）。
 * - 原 `startActivity(下载/日志)` 换成 [host].switchTab（外壳内切 tab）。
 * - 启动流程复用 [DshLauncher]（与外壳右侧面板同一份逻辑）。
 */
class DshInstancesUI(
    context: Context,
    private val host: DshShellHost
) : DshPageUI(context, R.layout.activity_dsh_instances) {

    private val binding = ActivityDshInstancesBinding.bind(contentView)
    private lateinit var adapter: DshInstanceAdapter
    private var lastNotifiedState: DshRuntime.State? = null

    override fun onCreate() {
        super.onCreate()
        DshPaths.loadPaths(context)
        DshInstances.init()
        DshLogBus.attachFile(java.io.File(DshPaths.LOG_FILE))

        adapter = DshInstanceAdapter(
            onStart = ::startInstance,
            onStop = { DshRuntime.stop("用户停止") },
            onOpenSettings = { host.openInstanceSettings(it.id) },
            onOpenLogs = { host.switchTab(DshShellHost.TAB_LOGS) },
            onReinstall = ::reinstall,
            onDelete = ::confirmDelete
        )
        binding.instanceList.layoutManager = LinearLayoutManager(context)
        binding.instanceList.adapter = adapter

        observeState()
        observeTasks()
        maybeAdoptOrphan()
    }

    /**
     * 订阅「进行中任务」并渲染任务区。
     *
     * 首页要把**所有正在跑的任务**集中显示（解压运行环境 / 安装 dsh / 启动 / 删除）——
     * 它们分散在四个模块里，统一由 [com.dsh.core.DshTasks] 聚合，这里只负责画。
     * 无任务时整块隐藏。
     */
    private fun observeTasks() {
        scope.launch {
            DshTasks.tasks.collect { renderTasks(it) }
        }
    }

    private fun renderTasks(tasks: List<DshTask>) {
        binding.taskArea.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        binding.taskList.removeAllViews()
        if (tasks.isEmpty()) return

        val inflater = android.view.LayoutInflater.from(context)
        tasks.forEach { task ->
            val row = com.dsh.fcl.androidlauncher.databinding.ViewDshTaskRowBinding
                .inflate(inflater, binding.taskList, false)

            row.taskTitle.text = taskTitle(task)

            val f = task.fraction
            if (f == null) {
                row.taskProgress.isIndeterminate = true
            } else {
                row.taskProgress.isIndeterminate = false
                row.taskProgress.progress = (f * 1000).toInt().coerceIn(0, 1000)
            }

            if (task.detail.isNullOrEmpty()) {
                row.taskDetail.visibility = View.GONE
            } else {
                row.taskDetail.visibility = View.VISIBLE
                row.taskDetail.text = task.detail
            }

            when (task.action) {
                DshTask.Action.NONE -> row.taskAction.visibility = View.GONE
                DshTask.Action.CANCEL -> {
                    row.taskAction.visibility = View.VISIBLE
                    row.taskAction.setImageResource(R.drawable.ic_baseline_close_24)
                    row.taskAction.contentDescription = context.getString(R.string.dsh_tasks_cancel)
                    row.taskAction.setOnClickListener { DshTasks.cancel(task) }
                }
                DshTask.Action.STOP -> {
                    row.taskAction.visibility = View.VISIBLE
                    row.taskAction.setImageResource(R.drawable.ic_baseline_close_24)
                    row.taskAction.contentDescription = context.getString(R.string.dsh_tasks_stop)
                    row.taskAction.setOnClickListener { DshTasks.cancel(task) }
                }
            }
            binding.taskList.addView(row.root)
        }
    }

    /** 任务标题：运行环境用固定文案，其余用「实例名 · 阶段」 */
    private fun taskTitle(task: DshTask): String = when (task.kind) {
        DshTask.Kind.BOOTSTRAP -> context.getString(R.string.dsh_task_bootstrap)
        else -> listOf(task.title, task.stage).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun observeState() {
        scope.launch {
            combine(
                DshInstances.instances,
                DshRuntime.state,
                DshInstances.deleting
            ) { list, state, deleting -> Triple(list, state, deleting) }
                .collect { (list, state, deleting) ->
                    val runningId = when (state) {
                        is DshRuntime.State.Running -> state.instanceId
                        is DshRuntime.State.Starting -> state.instanceId
                        is DshRuntime.State.Stopping -> state.instanceId
                        else -> null
                    }
                    adapter.submit(list, runningId, deleting)
                    val empty = list.isEmpty()
                    binding.emptyHint.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.instanceList.visibility = if (empty) View.GONE else View.VISIBLE
                }
        }
        scope.launch {
            DshServices.installer(context).statuses.collect { statuses ->
                adapter.submitInstallStatus(statuses)
            }
        }
        scope.launch {
            DshRuntime.state.collect { st -> notifyIfNeeded(st) }
        }
    }

    private fun notifyIfNeeded(st: DshRuntime.State) {
        if (st is DshRuntime.State.Idle ||
            st is DshRuntime.State.Starting ||
            st is DshRuntime.State.Stopping
        ) {
            lastNotifiedState = null
            return
        }
        if (st == lastNotifiedState) return
        when (st) {
            is DshRuntime.State.Failed -> {
                lastNotifiedState = st
                FCLAlertDialog.Builder(host.activity)
                    .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
                    .setTitle(context.getString(R.string.dsh_start_failed))
                    .setMessage(st.reason)
                    .setPositiveButton(context.getString(R.string.dsh_action_view_logs)) {
                        host.switchTab(DshShellHost.TAB_LOGS)
                    }
                    .setNegativeButton(context.getString(R.string.dialog_positive)) {
                        DshRuntime.resetState()
                    }
                    .create()
                    .show()
            }
            is DshRuntime.State.Exited -> {
                lastNotifiedState = st
                Toast.makeText(
                    context,
                    context.getString(R.string.dsh_runtime_exited, st.code),
                    Toast.LENGTH_LONG
                ).show()
                DshRuntime.resetState()
            }
            else -> {}
        }
    }

    private fun maybeAdoptOrphan() {
        if (DshRuntime.runningInstanceId() != null) return
        scope.launch {
            val candidates = DshInstances.instances.value.filter { it.state == DshInstance.State.READY }
            for (inst in candidates) {
                val ok = withContext(Dispatchers.IO) { DshRuntime.adoptOrphan(context, inst) }
                if (ok) {
                    Toast.makeText(context, R.string.dsh_adopted_running, Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
        }
    }

    private fun startInstance(inst: DshInstance) {
        DshLauncher.startInstance(
            activity = host.activity,
            inst = inst,
            scope = scope,
            onOpenSettings = { host.openInstanceSettings(it.id) },
            onOpenLogs = { host.switchTab(DshShellHost.TAB_LOGS) },
            onPrepareRuntime = { DshLauncher.openSetup(host.activity) },
            onStarted = { host.openWebView() }
        )
    }

    private fun reinstall(inst: DshInstance) {
        val version = inst.dshVersion
        if (version == null) {
            FCLAlertDialog.Builder(host.activity)
                .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
                .setTitle(context.getString(R.string.dsh_action_reinstall))
                .setMessage(context.getString(R.string.dsh_reinstall_pick_version))
                .setPositiveButton(context.getString(R.string.dsh_action_download)) {
                    host.switchTab(DshShellHost.TAB_DOWNLOAD)
                }
                .setNegativeButton(context.getString(R.string.dialog_negative), null)
                .create()
                .show()
            return
        }
        DshServices.installer(context).install(inst, version)
        Toast.makeText(context, context.getString(R.string.dsh_install_started, version), Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(inst: DshInstance) {
        FCLAlertDialog.Builder(host.activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(context.getString(R.string.dsh_delete_title))
            .setMessage(context.getString(R.string.dsh_delete_message, inst.name))
            .setPositiveButton(context.getString(R.string.dsh_action_delete)) {
                DshCredentials.clear(context, inst.id)
                DshInstances.delete(inst.id)
                Toast.makeText(context, R.string.dsh_delete_started, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(context.getString(R.string.dialog_negative), null)
            .create()
            .show()
    }
}
