package com.dsh.ui.shell

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.core.DshBootstrap
import com.dsh.core.DshDownloadViewModel
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.core.DshServices
import com.dsh.core.DshVersionListItem
import com.dsh.ui.DshVersionAdapter
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshDownloadBinding
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 版本下载页（外壳 tab 2）。由原 `DshDownloadActivity` 迁移为 [DshPageUI]。
 *
 * 迁移要点：
 * - `DshDownloadViewModel` 原来用 `lifecycleScope`，现在用页面级 [scope]。
 * - 装完的"去实例页"从 `finish()` 改为 [host].switchTab(实例)。
 */
class DshDownloadUI(
    context: Context,
    private val host: DshShellHost
) : DshPageUI(context, R.layout.activity_dsh_download) {

    private val binding = ActivityDshDownloadBinding.bind(contentView)
    private lateinit var adapter: DshVersionAdapter
    private lateinit var viewModel: DshDownloadViewModel

    override fun onCreate() {
        super.onCreate()
        DshPaths.loadPaths(context)
        DshInstances.init()

        val installer = DshServices.installer(context)
        viewModel = DshDownloadViewModel(scope, installer)

        adapter = DshVersionAdapter(onInstall = ::onInstall)
        binding.versionList.layoutManager = LinearLayoutManager(context)
        binding.versionList.adapter = adapter

        binding.switchPrerelease.setOnCheckedChangeListener { _, checked ->
            viewModel.togglePrerelease(checked)
        }
        // ★ 刷新反馈：原来 `refresh()` 只在列表为空时才置 Loading，于是"已有数据时点刷新"
        //   界面上毫无变化（真机反馈"右上角按钮无法刷新"）。这里自己维护刷新中状态：
        //   刷新期间显示进度圈并禁用按钮，任何结果（Loaded/Error）都结束刷新态。
        binding.btnRefresh.setOnClickListener {
            if (refreshing) return@setOnClickListener
            refreshing = true
            binding.loading.visibility = View.VISIBLE
            binding.btnRefresh.isEnabled = false
            viewModel.refresh(force = true)
        }

        scope.launch { viewModel.uiState.collect { render(it) } }
        scope.launch { viewModel.installingVersions.collect { adapter.submitInstalling(it) } }
        scope.launch {
            installer.progress.collect { p ->
                when (p) {
                    is com.dsh.core.DshInstaller.Progress.Done ->
                        toast(context.getString(R.string.dsh_install_done, p.version))
                    is com.dsh.core.DshInstaller.Progress.Failed ->
                        toast(context.getString(R.string.dsh_install_failed, p.reason))
                    else -> {}
                }
            }
        }

        viewModel.refresh()
    }

    /** 是否正在刷新（用于反馈与防重复点击） */
    private var refreshing = false

    private fun endRefresh() {
        refreshing = false
        binding.btnRefresh.isEnabled = true
    }

    private fun render(state: DshDownloadViewModel.UiState) {
        when (state) {
            is DshDownloadViewModel.UiState.Loading -> {
                binding.loading.visibility = View.VISIBLE
                binding.errorHint.visibility = View.GONE
                binding.versionList.visibility = View.GONE
            }
            is DshDownloadViewModel.UiState.Loaded -> {
                endRefresh()
                binding.loading.visibility = View.GONE
                binding.versionList.visibility = View.VISIBLE
                adapter.submit(state.items)
                if (state.warning != null) {
                    binding.errorHint.visibility = View.VISIBLE
                    binding.errorHint.text = context.getString(R.string.dsh_download_warning, state.warning)
                } else {
                    binding.errorHint.visibility = View.GONE
                }
            }
            is DshDownloadViewModel.UiState.Error -> {
                endRefresh()
                binding.loading.visibility = View.GONE
                binding.versionList.visibility = View.GONE
                binding.errorHint.visibility = View.VISIBLE
                binding.errorHint.text = context.getString(R.string.dsh_download_error, state.message)
            }
        }
    }

    private fun onInstall(item: DshVersionListItem) {
        scope.launch {
            // 底座没就绪就地准备（走进程级任务 + 由横幅/日志显示进度），等结果再继续
            if (!DshBootstrap.isReady()) {
                val ready = DshLauncher.ensureRuntimeReady(
                    host.activity, DshLauncher.OWNER_DOWNLOAD_PAGE
                )
                if (!ready) {
                    val reason = (DshBootstrap.currentProgress() as? DshBootstrap.Progress.Failed)?.reason
                    FCLAlertDialog.Builder(host.activity)
                        .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
                        .setTitle(context.getString(R.string.dsh_bootstrap_failed_title))
                        .setMessage(reason ?: context.getString(R.string.dsh_bootstrap_missing_short))
                        .setNegativeButton(context.getString(R.string.dialog_positive), null)
                        .create()
                        .show()
                    return@launch
                }
            }
            val dispatch = viewModel.installVersion(item)
            val inst = dispatch.instance
            if (dispatch.started) {
                toast(context.getString(R.string.dsh_install_started, item.version))
                // ★ 只有**真的发起**了安装才弹「已开始安装」。
                //   原来无条件弹：用户连点几次就排队弹出 N 个对话框（真机表现为"疯狂跳窗"）。
                askOpenInstances(inst?.name ?: item.version)
            } else {
                // 没有真的发起安装（该版本已装好 / 已有安装在跑）：只提示，不弹窗
                toast(context.getString(R.string.dsh_install_skipped, item.version))
            }
        }
    }


    /** 已有一个"已开始安装"对话框时不重复弹（连点场景下避免叠窗） */
    private var installDialogShowing = false

    private fun askOpenInstances(instanceName: String) {
        if (installDialogShowing) return
        installDialogShowing = true
        FCLAlertDialog.Builder(host.activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
            .setTitle(context.getString(R.string.dsh_install_started_title))
            .setMessage(context.getString(R.string.dsh_install_started_message, instanceName))
            .setPositiveButton(context.getString(R.string.dsh_action_go_instances)) {
                installDialogShowing = false
                host.switchTab(DshShellHost.TAB_INSTANCES)
            }
            .setNegativeButton(context.getString(R.string.dsh_action_stay)) {
                installDialogShowing = false
            }
            .setCancelable(false)
            .create()
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
