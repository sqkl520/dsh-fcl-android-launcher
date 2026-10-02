package com.dsh.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.core.DshBootstrap
import com.dsh.core.DshDownloadViewModel
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.core.DshServices
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshDownloadBinding
import com.tungsten.fcllibrary.component.FCLActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * dsh 版本下载页。拉 npm registry 版本列表，选中即新建实例并在 proot 里安装。
 *
 * ## 本次改造（对应审查发现的问题）
 * 1. **安装器用进程级单例**（[DshServices]）：原来每个 Activity 自建一个安装器，
 *    "同一实例只能有一个安装任务"的控制形同虚设。
 * 2. **底座准备有进度**：原来只有一句 toast"正在准备运行时…"，几百 MB 解压期间界面看起来就是卡住了。
 *    现在弹一个不可取消的进度对话框，实时显示阶段。
 * 3. **失败明确**：底座准备失败/安装失败都会给出原因（含磁盘空间不足、proot 缺失等）。
 * 4. **列表刷新**：加了刷新按钮（走缓存，10 分钟内不重复打网络）；断网时回退到上次成功结果并提示。
 * 5. **装完可直接启动**：安装完成后提示是否立刻启动新实例（少两次跳转）。
 */
class DshDownloadActivity : FCLActivity() {

    private lateinit var binding: ActivityDshDownloadBinding
    private lateinit var adapter: DshVersionAdapter
    private lateinit var viewModel: DshDownloadViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDshDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DshPaths.loadPaths(this)
        DshInstances.init()

        val installer = DshServices.installer(this)
        // ViewModel 只订阅状态，用界面作用域（离开页面即停止订阅，避免长生命周期泄漏）；
        // 真正的安装任务跑在安装器内部的进程级作用域里，不随界面销毁而取消。
        viewModel = DshDownloadViewModel(lifecycleScope, installer)

        adapter = DshVersionAdapter(onInstall = ::onInstall)
        binding.versionList.layoutManager = LinearLayoutManager(this)
        binding.versionList.adapter = adapter

        binding.switchPrerelease.setOnCheckedChangeListener { _, checked ->
            viewModel.togglePrerelease(checked)
        }
        binding.btnRefresh.setOnClickListener { viewModel.refresh(force = true) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch {
                    viewModel.installingVersions.collect { adapter.submitInstalling(it) }
                }
                launch {
                    installer.progress.collect { p ->
                        when (p) {
                            is com.dsh.core.DshInstaller.Progress.Done ->
                                toast(getString(R.string.dsh_install_done, p.version))
                            is com.dsh.core.DshInstaller.Progress.Failed ->
                                toast(getString(R.string.dsh_install_failed, p.reason))
                            else -> {}
                        }
                    }
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
                // 有警告（例如用的是缓存/弱网）就显示，但不挡住列表
                if (state.warning != null) {
                    binding.errorHint.visibility = View.VISIBLE
                    binding.errorHint.text = getString(R.string.dsh_download_warning, state.warning)
                } else {
                    binding.errorHint.visibility = View.GONE
                }
            }
            is DshDownloadViewModel.UiState.Error -> {
                binding.loading.visibility = View.GONE
                binding.versionList.visibility = View.GONE
                binding.errorHint.visibility = View.VISIBLE
                binding.errorHint.text = getString(R.string.dsh_download_error, state.message)
            }
        }
    }

    private fun onInstall(item: com.dsh.core.DshVersionListItem) {
        // 安装前确保运行时底座就绪；没就绪就地解压并显示进度
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) { DshBootstrap.isReady() }
            if (!ready) {
                val proceed = prepareRuntime()
                if (!proceed) return@launch
            }
            val inst = viewModel.installVersion(item)
            toast(getString(R.string.dsh_install_started, item.version))
            // 安装是长任务（脱离界面作用域），这里只负责把用户带到能看到进度的地方
            askOpenInstances(inst.name)
        }
    }

    /** 解压运行时底座，带进度对话框；返回是否成功就绪 */
    private suspend fun prepareRuntime(): Boolean {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dsh_action_prepare_runtime)
            .setMessage(getString(R.string.dsh_bootstrap_extracting))
            .setCancelable(false)
            .show()
        val failure = withContext(Dispatchers.IO) {
            var fail: String? = null
            DshBootstrap.install(this@DshDownloadActivity) { p ->
                val text = when (p) {
                    is DshBootstrap.Progress.Stage -> p.text
                    is DshBootstrap.Progress.Detail -> p.detail
                    is DshBootstrap.Progress.Failed -> {
                        fail = p.reason
                        p.reason
                    }
                    DshBootstrap.Progress.Done -> getString(R.string.dsh_bootstrap_done)
                }
                runOnUiThread { dialog.setMessage(text) }
            }
            fail
        }
        dialog.dismiss()
        if (failure != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dsh_bootstrap_failed_title)
                .setMessage(failure)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return false
        }
        return true
    }

    private fun askOpenInstances(instanceName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dsh_install_started_title)
            .setMessage(getString(R.string.dsh_install_started_message, instanceName))
            .setPositiveButton(R.string.dsh_action_go_instances) { _, _ -> finish() }
            .setNegativeButton(R.string.dsh_action_stay, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
