package com.dsh.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.core.DshBootstrap
import com.dsh.core.DshCredentials
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
import com.dsh.core.DshRuntimeService
import com.dsh.core.DshServices
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshInstancesBinding
import com.tungsten.fcllibrary.component.FCLActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * dsh 实例列表页（启动器主入口）。
 *
 * ## 本次改造（对应审查发现的可用性问题）
 * 1. **补上缺失的入口**：日志页以前没有任何入口（失败提示却让人"详见日志"），现在列表页有日志按钮，
 *    每个实例的菜单里也有该实例的日志；设置页/重命名/修复/删除都在实例菜单里。
 * 2. **首次使用引导**：运行时底座（proot + rootfs）没准备好时，页面顶部直接出现"准备运行时"横幅，
 *    点一下就地解压并显示进度，不用先跑去下载页瞎点。
 * 3. **安装进度可见**：正在安装的实例在列表里显示阶段文案与进度条（数据来自进程级安装器状态）。
 * 4. **启动前检查 API Key**：没配 key 时给"去配置"的对话框，而不是只弹一句 toast 然后让用户自己猜。
 * 5. **失败有出口**：NOT_INSTALLED/BROKEN 的实例菜单里有"重新安装"；启动失败会弹出原因 +
 *    一键看日志/重试。
 * 6. **崩溃/退出有反馈**：运行进程意外退出时提示，不再无声无息。
 * 7. **删除有安全网**：删除前确认、运行中禁止删除、删除过程异步（不再主线程删 300MB 卡死）。
 */
class DshInstancesActivity : FCLActivity() {

    private lateinit var binding: ActivityDshInstancesBinding
    private lateinit var adapter: DshInstanceAdapter

    /** 已经提示过的状态，避免同一个失败反复弹窗 */
    private var lastNotifiedState: DshRuntime.State? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDshInstancesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 兜底初始化（FCLApp 里已做，这里防进程被系统重建等边界情况）
        DshPaths.loadPaths(this)
        DshInstances.init()
        DshLogBus.attachFile(java.io.File(DshPaths.LOG_FILE))

        adapter = DshInstanceAdapter(
            onStart = ::startInstance,
            onStop = { DshRuntime.stop("用户停止") },
            onOpenSettings = ::openSettings,
            onOpenLogs = { startActivity(Intent(this, DshLogsActivity::class.java)) },
            onReinstall = ::reinstall,
            onDelete = ::confirmDelete
        )
        binding.instanceList.layoutManager = LinearLayoutManager(this)
        binding.instanceList.adapter = adapter

        binding.btnDownload.setOnClickListener {
            startActivity(Intent(this, DshDownloadActivity::class.java))
        }
        binding.btnLogs.setOnClickListener {
            startActivity(Intent(this, DshLogsActivity::class.java))
        }
        binding.bootstrapBanner.btnPrepareRuntime.setOnClickListener { prepareRuntime() }

        observeState()
        maybeAdoptOrphan()
    }

    override fun onResume() {
        super.onResume()
        // 列表内容由 StateFlow（instances/state/deleting）驱动增量刷新，
        // 从设置页返回时 repeatOnLifecycle 会自动回放最新值，无需再全量重绘（那会打断 DiffUtil 动画）。
    }

    /** 订阅实例列表 / 运行状态 / 安装状态 / 删除中状态 / 底座状态 */
    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 1) 实例列表 + 运行中 id + 安装状态
                launch {
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
                // 2) 安装状态（阶段文案/进度）
                launch {
                    DshServices.installer(this@DshInstancesActivity).statuses.collect { statuses ->
                        adapter.submitInstallStatus(statuses)
                    }
                }
                // 3) 运行状态提示（失败/退出）
                launch {
                    DshRuntime.state.collect { st -> notifyIfNeeded(st) }
                }
                // 4) 底座就绪状态 → 顶部横幅
                launch {
                    val ready = withContext(Dispatchers.IO) { DshBootstrap.isReady() }
                    binding.bootstrapBanner.root.visibility = if (ready) View.GONE else View.VISIBLE
                    if (!ready) {
                        binding.bootstrapBanner.bannerText.text =
                            getString(R.string.dsh_bootstrap_missing, DshBootstrap.missingSummary() ?: "")
                    }
                }
            }
        }
    }

    private fun notifyIfNeeded(st: DshRuntime.State) {
        // ★ 新一轮生命周期开始（Idle/Starting/Stopping）时清掉"已提示过"标记。
        // 否则同一个原因连续失败两次（例如第二次仍然超时）会因为 Failed 数据类相等而被静默吞掉：
        // 用户只会在第一次看到失败弹窗。
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
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dsh_start_failed)
                    .setMessage(st.reason)
                    .setPositiveButton(R.string.dsh_action_view_logs) { _, _ ->
                        startActivity(Intent(this, DshLogsActivity::class.java))
                    }
                    .setNegativeButton(android.R.string.ok) { _, _ -> DshRuntime.resetState() }
                    .show()
            }
            is DshRuntime.State.Exited -> {
                lastNotifiedState = st
                Toast.makeText(
                    this,
                    getString(R.string.dsh_runtime_exited, st.code),
                    Toast.LENGTH_LONG
                ).show()
                DshRuntime.resetState()
            }
            else -> {}
        }
    }

    /**
     * 冷启后尝试认领仍在跑的实例（App 被系统杀掉但 proot/node 还活着）：
     * 认领成功就不用重启 300MB 进程，直接进界面。
     */
    private fun maybeAdoptOrphan() {
        if (DshRuntime.runningInstanceId() != null) return
        lifecycleScope.launch {
            val candidates = DshInstances.instances.value.filter { it.state == DshInstance.State.READY }
            for (inst in candidates) {
                val ok = withContext(Dispatchers.IO) { DshRuntime.adoptOrphan(this@DshInstancesActivity, inst) }
                if (ok) {
                    Toast.makeText(this@DshInstancesActivity, R.string.dsh_adopted_running, Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
        }
    }

    private fun startInstance(inst: DshInstance) {
        // 凭据状态判定要跑 Keystore 解密（部分机型 100ms+），不能放主线程：原来它就在点击回调里
        // 直接调用，于是"点启动"这一下会明显掉帧甚至 ANR。这里挪到 IO，回到主线程再弹对话框。
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                DshCredentials.status(this@DshInstancesActivity, inst.id)
            }
            when (status) {
                is DshCredentials.Status.None -> {
                    // 没配 key：引导去配置，而不是"能起但对话必失败"这种坑
                    MaterialAlertDialogBuilder(this@DshInstancesActivity)
                        .setTitle(R.string.dsh_no_key_title)
                        .setMessage(R.string.dsh_no_key_hint)
                        .setPositiveButton(R.string.dsh_action_configure) { _, _ -> openSettings(inst) }
                        .setNegativeButton(R.string.dsh_action_start_anyway) { _, _ -> doStart(inst) }
                        .show()
                }
                is DshCredentials.Status.Unreadable -> {
                    MaterialAlertDialogBuilder(this@DshInstancesActivity)
                        .setTitle(R.string.dsh_key_unreadable_title)
                        .setMessage(R.string.dsh_key_unreadable_hint)
                        .setPositiveButton(R.string.dsh_action_configure) { _, _ -> openSettings(inst) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                is DshCredentials.Status.Ok -> doStart(inst)
            }
        }
    }

    private fun doStart(inst: DshInstance) {
        // 启动涉及 killStale(读 /proc)、Keystore 解密、ProcessBuilder.start(fork+exec proot) 等阻塞调用，
        // 放主线程会抖动甚至 ANR。放到 IO 上跑，拿到结果再切回主线程决定导航。
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { DshRuntime.start(this@DshInstancesActivity, inst) }
            when (outcome) {
                is DshRuntime.StartOutcome.Started -> {
                    ensureNotificationPermission()
                    DshRuntimeService.start(this@DshInstancesActivity)
                    startActivity(Intent(this@DshInstancesActivity, DshWebViewActivity::class.java))
                }
                is DshRuntime.StartOutcome.NotReady -> showProblem(getString(R.string.dsh_start_not_ready), outcome.reason)
                is DshRuntime.StartOutcome.Failed -> showProblem(getString(R.string.dsh_start_failed), outcome.reason)
            }
        }
    }

    private fun ensureNotificationPermission() {
        // Android 13+ 前台服务通知需要运行时授权，否则"运行中"通知与通知栏"停止"按钮都不显示。
        // 只请求一次（用户拒绝也不影响 dsh 进程本身，只是少了保活通知/停止入口）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf(perm), REQ_POST_NOTIFICATIONS) }
        }
    }

    private fun showProblem(title: String, reason: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(reason)
            .setPositiveButton(R.string.dsh_action_view_logs) { _, _ ->
                startActivity(Intent(this, DshLogsActivity::class.java))
            }
            .setNeutralButton(R.string.dsh_action_prepare_runtime) { _, _ -> prepareRuntime() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /** 就地准备运行时底座（解压 proot/rootfs/脚本）并显示进度 */
    private fun prepareRuntime() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dsh_action_prepare_runtime)
            .setMessage(getString(R.string.dsh_bootstrap_extracting))
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var failure: String? = null
                DshBootstrap.install(this@DshInstancesActivity) { p ->
                    val text = when (p) {
                        is DshBootstrap.Progress.Stage -> p.text
                        is DshBootstrap.Progress.Detail -> p.detail
                        is DshBootstrap.Progress.Failed -> {
                            failure = p.reason
                            p.reason
                        }
                        DshBootstrap.Progress.Done -> getString(R.string.dsh_bootstrap_done)
                    }
                    runOnUiThread { dialog.setMessage(text) }
                }
                failure
            }
            dialog.dismiss()
            if (result != null) {
                Toast.makeText(this@DshInstancesActivity, result, Toast.LENGTH_LONG).show()
            }
            val ready = withContext(Dispatchers.IO) { DshBootstrap.isReady() }
            binding.bootstrapBanner.root.visibility = if (ready) View.GONE else View.VISIBLE
            if (ready) Toast.makeText(this@DshInstancesActivity, R.string.dsh_bootstrap_ready, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings(inst: DshInstance) {
        startActivity(
            Intent(this, DshSettingsActivity::class.java)
                .putExtra(DshSettingsActivity.EXTRA_INSTANCE_ID, inst.id)
        )
    }

    /** 重新安装（修复 NOT_INSTALLED/BROKEN） */
    private fun reinstall(inst: DshInstance) {
        val version = inst.dshVersion
        if (version == null) {
            // 从没装成功过：去下载页选版本
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dsh_action_reinstall)
                .setMessage(R.string.dsh_reinstall_pick_version)
                .setPositiveButton(R.string.dsh_action_download) { _, _ ->
                    startActivity(Intent(this, DshDownloadActivity::class.java))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        DshServices.installer(this).install(inst, version)
        Toast.makeText(this, getString(R.string.dsh_install_started, version), Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(inst: DshInstance) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dsh_delete_title)
            .setMessage(getString(R.string.dsh_delete_message, inst.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.dsh_action_delete) { _, _ ->
                DshCredentials.clear(this, inst.id)
                DshInstances.delete(inst.id)
                Toast.makeText(this, R.string.dsh_delete_started, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 4101
    }
}
