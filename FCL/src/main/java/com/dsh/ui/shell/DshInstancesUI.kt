package com.dsh.ui.shell

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.core.DshBootstrap
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

        binding.btnDownload.setOnClickListener { host.switchTab(DshShellHost.TAB_DOWNLOAD) }
        binding.btnLogs.setOnClickListener { host.switchTab(DshShellHost.TAB_LOGS) }
        binding.bootstrapBanner.btnPrepareRuntime.setOnClickListener { prepareRuntime() }

        observeState()
        maybeAdoptOrphan()
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
        scope.launch {
            val ready = withContext(Dispatchers.IO) { DshBootstrap.isReady() }
            binding.bootstrapBanner.root.visibility = if (ready) View.GONE else View.VISIBLE
            if (!ready) {
                binding.bootstrapBanner.bannerText.text =
                    context.getString(R.string.dsh_bootstrap_missing, DshBootstrap.missingSummary() ?: "")
            }
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
                MaterialAlertDialogBuilder(host.activity)
                    .setTitle(R.string.dsh_start_failed)
                    .setMessage(st.reason)
                    .setPositiveButton(R.string.dsh_action_view_logs) { _, _ ->
                        host.switchTab(DshShellHost.TAB_LOGS)
                    }
                    .setNegativeButton(android.R.string.ok) { _, _ -> DshRuntime.resetState() }
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
            onPrepareRuntime = { prepareRuntime() },
            onStarted = { host.openWebView() }
        )
    }

    private fun prepareRuntime() {
        DshLauncher.prepareRuntime(host.activity, scope) { ready ->
            binding.bootstrapBanner.root.visibility = if (ready) View.GONE else View.VISIBLE
            if (ready) Toast.makeText(context, R.string.dsh_bootstrap_ready, Toast.LENGTH_SHORT).show()
        }
    }

    private fun reinstall(inst: DshInstance) {
        val version = inst.dshVersion
        if (version == null) {
            MaterialAlertDialogBuilder(host.activity)
                .setTitle(R.string.dsh_action_reinstall)
                .setMessage(R.string.dsh_reinstall_pick_version)
                .setPositiveButton(R.string.dsh_action_download) { _, _ -> host.switchTab(DshShellHost.TAB_DOWNLOAD) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        DshServices.installer(context).install(inst, version)
        Toast.makeText(context, context.getString(R.string.dsh_install_started, version), Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(inst: DshInstance) {
        MaterialAlertDialogBuilder(host.activity)
            .setTitle(R.string.dsh_delete_title)
            .setMessage(context.getString(R.string.dsh_delete_message, inst.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.dsh_action_delete) { _, _ ->
                DshCredentials.clear(context, inst.id)
                DshInstances.delete(inst.id)
                Toast.makeText(context, R.string.dsh_delete_started, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
