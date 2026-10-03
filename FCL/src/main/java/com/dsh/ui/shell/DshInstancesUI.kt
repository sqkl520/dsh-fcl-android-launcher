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

        binding.bootstrapBanner.btnPrepareRuntime.setOnClickListener { prepareRuntime() }

        observeState()
        observeBootstrap()
        maybeAdoptOrphan()
    }

    /**
     * 订阅底座解压进度。
     *
     * ★ 修的问题：原来进度只显示在一个「不可取消的对话框」里，而解压任务跑在**页面级协程**上——
     * 一旦切页/页面被 ViewPager 回收，协程被 cancel，对话框就再也不刷新（看起来卡死），
     * 用户再点一次还会撞上互斥只得到「已有解压任务在进行」。
     * 现在任务在进程级作用域（[com.dsh.core.DshAppScope]）里跑，进度经 StateFlow 暴露，
     * 页面只负责渲染横幅 —— 切页/回来都不会丢进度。
     */
    private fun observeBootstrap() {
        // 进度：阶段文字 + 明细 + 进度条
        scope.launch {
            DshBootstrap.progress.collect { p -> renderBootstrapProgress(p) }
        }
        // 忙闲：解压中隐藏按钮，避免重复触发
        scope.launch {
            DshBootstrap.busy.collect { busy ->
                binding.bootstrapBanner.btnPrepareRuntime.visibility =
                    if (busy) View.GONE else View.VISIBLE
                if (busy) binding.bootstrapBanner.root.visibility = View.VISIBLE
            }
        }
        // 就绪状态（任务结束后刷新一次）
        scope.launch {
            DshBootstrap.busy.collect { busy ->
                if (!busy) refreshBootstrapBanner()
            }
        }
    }

    private fun renderBootstrapProgress(p: DshBootstrap.Progress?) {
        val banner = binding.bootstrapBanner
        if (p == null) return
        banner.root.visibility = View.VISIBLE
        when (p) {
            is DshBootstrap.Progress.Stage -> {
                banner.bannerText.text = p.text
                val f = p.fraction
                if (f != null) {
                    banner.bannerProgress.visibility = View.VISIBLE
                    banner.bannerProgress.isIndeterminate = false
                    banner.bannerProgress.progress = (f * 1000).toInt().coerceIn(0, 1000)
                } else {
                    banner.bannerProgress.visibility = View.VISIBLE
                    banner.bannerProgress.isIndeterminate = true
                }
            }
            is DshBootstrap.Progress.Detail -> {
                banner.bannerDetail.visibility = View.VISIBLE
                banner.bannerDetail.text = p.detail
            }
            is DshBootstrap.Progress.Failed -> {
                banner.bannerText.text = context.getString(R.string.dsh_bootstrap_failed_title)
                banner.bannerDetail.visibility = View.VISIBLE
                banner.bannerDetail.text = p.reason
                banner.bannerProgress.visibility = View.GONE
            }
            DshBootstrap.Progress.Done -> {
                banner.bannerProgress.visibility = View.GONE
                banner.bannerDetail.visibility = View.GONE
            }
        }
    }

    /** 就绪时隐藏横幅，否则显示缺口说明 */
    private fun refreshBootstrapBanner() {
        scope.launch {
            val ready = withContext(Dispatchers.IO) { DshBootstrap.isReady() }
            val banner = binding.bootstrapBanner
            banner.root.visibility = if (ready) View.GONE else View.VISIBLE
            if (!ready) {
                banner.bannerText.text =
                    context.getString(R.string.dsh_bootstrap_missing, DshBootstrap.missingSummary() ?: "")
                banner.bannerProgress.visibility = View.GONE
            }
        }
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
        scope.launch { refreshBootstrapBanner() }
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
            onPrepareRuntime = { prepareRuntime() },
            onStarted = { host.openWebView() }
        )
    }

    private fun prepareRuntime() {
        DshLauncher.prepareRuntime(
            activity = host.activity,
            owner = DshLauncher.OWNER_INSTANCE_PAGE
        ) { ready ->
            if (ready) {
                Toast.makeText(context, R.string.dsh_bootstrap_ready, Toast.LENGTH_SHORT).show()
            }
            refreshBootstrapBanner()
        }
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
