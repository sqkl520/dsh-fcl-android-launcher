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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        binding.btnRefresh.setOnClickListener { viewModel.refresh(force = true) }

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

    private fun render(state: DshDownloadViewModel.UiState) {
        when (state) {
            is DshDownloadViewModel.UiState.Loading -> {
                binding.loading.visibility = View.VISIBLE
                binding.errorHint.visibility = View.GONE
                binding.versionList.visibility = View.GONE
            }
            is DshDownloadViewModel.UiState.Loaded -> {
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
                binding.loading.visibility = View.GONE
                binding.versionList.visibility = View.GONE
                binding.errorHint.visibility = View.VISIBLE
                binding.errorHint.text = context.getString(R.string.dsh_download_error, state.message)
            }
        }
    }

    private fun onInstall(item: DshVersionListItem) {
        scope.launch {
            val ready = withContext(Dispatchers.IO) { DshBootstrap.isReady() }
            if (!ready) {
                val proceed = prepareRuntime()
                if (!proceed) return@launch
            }
            val inst = viewModel.installVersion(item)
            toast(context.getString(R.string.dsh_install_started, item.version))
            askOpenInstances(inst.name)
        }
    }

    private suspend fun prepareRuntime(): Boolean {
        val dialog = MaterialAlertDialogBuilder(host.activity)
            .setTitle(R.string.dsh_action_prepare_runtime)
            .setMessage(context.getString(R.string.dsh_bootstrap_extracting))
            .setCancelable(false)
            .show()
        val failure = withContext(Dispatchers.IO) {
            var fail: String? = null
            DshBootstrap.install(context) { p ->
                val text = when (p) {
                    is DshBootstrap.Progress.Stage -> p.text
                    is DshBootstrap.Progress.Detail -> p.detail
                    is DshBootstrap.Progress.Failed -> {
                        fail = p.reason
                        p.reason
                    }
                    DshBootstrap.Progress.Done -> context.getString(R.string.dsh_bootstrap_done)
                }
                host.activity.runOnUiThread { dialog.setMessage(text) }
            }
            fail
        }
        dialog.dismiss()
        if (failure != null) {
            MaterialAlertDialogBuilder(host.activity)
                .setTitle(R.string.dsh_bootstrap_failed_title)
                .setMessage(failure)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return false
        }
        return true
    }

    private fun askOpenInstances(instanceName: String) {
        MaterialAlertDialogBuilder(host.activity)
            .setTitle(R.string.dsh_install_started_title)
            .setMessage(context.getString(R.string.dsh_install_started_message, instanceName))
            .setPositiveButton(R.string.dsh_action_go_instances) { _, _ -> host.switchTab(DshShellHost.TAB_INSTANCES) }
            .setNegativeButton(R.string.dsh_action_stay, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
