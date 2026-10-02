package com.dsh.ui.shell

import android.content.pm.PackageManager
import android.os.Build
import com.dsh.core.DshBootstrap
import com.dsh.core.DshCredentials
import com.dsh.core.DshInstance
import com.dsh.core.DshRuntime
import com.dsh.core.DshRuntimeService
import com.dsh.fcl.androidlauncher.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tungsten.fcllibrary.component.FCLActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动实例的共享流程（凭据检查 → 启动 → 导航），供实例页 [DshInstancesUI] 与
 * 外壳右侧面板 [DshMainActivity] 复用，避免把这段逻辑复制两份。
 *
 * 所有阻塞调用（Keystore 解密、killStale、ProcessBuilder.start）都在 IO 上执行。
 */
object DshLauncher {

    /**
     * 启动一个实例。会在主线程弹出必要的对话框（缺 key / 不可读 / 失败）。
     *
     * @param onOpenSettings 用户选择"去配置"时打开该实例的设置页
     * @param onOpenLogs     用户选择"查看日志"时切到日志 tab
     * @param onPrepareRuntime 用户选择"准备运行时"时触发底座解压
     * @param onStarted      启动成功后的导航（打开 WebView）
     */
    fun startInstance(
        activity: FCLActivity,
        inst: DshInstance,
        scope: CoroutineScope,
        onOpenSettings: (DshInstance) -> Unit,
        onOpenLogs: () -> Unit,
        onPrepareRuntime: () -> Unit,
        onStarted: () -> Unit,
    ) {
        scope.launch {
            val status = withContext(Dispatchers.IO) { DshCredentials.status(activity, inst.id) }
            when (status) {
                is DshCredentials.Status.None -> {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.dsh_no_key_title)
                        .setMessage(R.string.dsh_no_key_hint)
                        .setPositiveButton(R.string.dsh_action_configure) { _, _ -> onOpenSettings(inst) }
                        .setNegativeButton(R.string.dsh_action_start_anyway) { _, _ ->
                            doStart(activity, inst, scope, onOpenLogs, onPrepareRuntime, onStarted)
                        }
                        .show()
                }
                is DshCredentials.Status.Unreadable -> {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.dsh_key_unreadable_title)
                        .setMessage(R.string.dsh_key_unreadable_hint)
                        .setPositiveButton(R.string.dsh_action_configure) { _, _ -> onOpenSettings(inst) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                is DshCredentials.Status.Ok -> doStart(activity, inst, scope, onOpenLogs, onPrepareRuntime, onStarted)
            }
        }
    }

    private fun doStart(
        activity: FCLActivity,
        inst: DshInstance,
        scope: CoroutineScope,
        onOpenLogs: () -> Unit,
        onPrepareRuntime: () -> Unit,
        onStarted: () -> Unit,
    ) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { DshRuntime.start(activity, inst) }
            when (outcome) {
                is DshRuntime.StartOutcome.Started -> {
                    ensureNotificationPermission(activity)
                    DshRuntimeService.start(activity)
                    onStarted()
                }
                is DshRuntime.StartOutcome.NotReady -> showProblem(
                    activity, activity.getString(R.string.dsh_start_not_ready), outcome.reason,
                    onOpenLogs, onPrepareRuntime
                )
                is DshRuntime.StartOutcome.Failed -> showProblem(
                    activity, activity.getString(R.string.dsh_start_failed), outcome.reason,
                    onOpenLogs, onPrepareRuntime
                )
            }
        }
    }

    private fun showProblem(
        activity: FCLActivity,
        title: String,
        reason: String,
        onOpenLogs: () -> Unit,
        onPrepareRuntime: () -> Unit,
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(reason)
            .setPositiveButton(R.string.dsh_action_view_logs) { _, _ -> onOpenLogs() }
            .setNeutralButton(R.string.dsh_action_prepare_runtime) { _, _ -> onPrepareRuntime() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun ensureNotificationPermission(activity: FCLActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (activity.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            runCatching { activity.requestPermissions(arrayOf(perm), REQ_POST_NOTIFICATIONS) }
        }
    }

    /**
     * 就地准备运行时底座（解压 proot/rootfs/脚本），带不可取消的进度对话框。
     * @param onDone 完成回调（参数为是否已就绪），用于刷新调用方的横幅/状态。
     */
    fun prepareRuntime(
        activity: FCLActivity,
        scope: CoroutineScope,
        onDone: (ready: Boolean) -> Unit,
    ) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dsh_action_prepare_runtime)
            .setMessage(activity.getString(R.string.dsh_bootstrap_extracting))
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                var fail: String? = null
                DshBootstrap.install(activity) { p ->
                    val text = when (p) {
                        is DshBootstrap.Progress.Stage -> p.text
                        is DshBootstrap.Progress.Detail -> p.detail
                        is DshBootstrap.Progress.Failed -> {
                            fail = p.reason
                            p.reason
                        }
                        DshBootstrap.Progress.Done -> activity.getString(R.string.dsh_bootstrap_done)
                    }
                    activity.runOnUiThread { dialog.setMessage(text) }
                }
                fail
            }
            dialog.dismiss()
            if (failure != null) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.dsh_bootstrap_failed_title)
                    .setMessage(failure)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            val ready = withContext(Dispatchers.IO) { DshBootstrap.isReady() }
            onDone(ready)
        }
    }

    private const val REQ_POST_NOTIFICATIONS = 4101
}
