package com.dsh.ui.shell

import android.content.pm.PackageManager
import android.os.Build
import com.dsh.core.DshAppScope
import com.dsh.core.DshBootstrap
import com.dsh.core.DshCredentials
import com.dsh.core.DshInstance
import com.dsh.core.DshRuntime
import com.dsh.core.DshRuntimeService
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
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
                    FCLAlertDialog.Builder(activity)
                        .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
                        .setTitle(activity.getString(R.string.dsh_no_key_title))
                        .setMessage(activity.getString(R.string.dsh_no_key_hint))
                        .setPositiveButton(activity.getString(R.string.dsh_action_configure)) {
                            onOpenSettings(inst)
                        }
                        .setNegativeButton(activity.getString(R.string.dsh_action_start_anyway)) {
                            doStart(activity, inst, scope, onOpenLogs, onPrepareRuntime, onStarted)
                        }
                        .create()
                        .show()
                }
                is DshCredentials.Status.Unreadable -> {
                    FCLAlertDialog.Builder(activity)
                        .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
                        .setTitle(activity.getString(R.string.dsh_key_unreadable_title))
                        .setMessage(activity.getString(R.string.dsh_key_unreadable_hint))
                        .setPositiveButton(activity.getString(R.string.dsh_action_configure)) {
                            onOpenSettings(inst)
                        }
                        .setNegativeButton(activity.getString(R.string.dialog_negative), null)
                        .create()
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
        FCLAlertDialog.Builder(activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(title)
            .setMessage(reason)
            .setPositiveButton(activity.getString(R.string.dsh_action_view_logs)) { onOpenLogs() }
            .setNeutralButton(activity.getString(R.string.dsh_action_prepare_runtime)) { onPrepareRuntime() }
            .setNegativeButton(activity.getString(R.string.dialog_positive), null)
            .create()
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
     * 打开「准备运行环境」页（一次性前置页）。
     *
     * 底座没就绪时不再就地解压 + 弹对话框（真机踩过：进度会随页面回收丢失、
     * 失败原因会被覆盖）。统一交给 [com.dsh.ui.setup.DshSetupActivity]，那里有
     * 固定位置的进度/明细/准备项清单，失败可原地重试。
     */
    fun openSetup(activity: FCLActivity) {
        activity.startActivity(
            android.content.Intent(activity, com.dsh.ui.setup.DshSetupActivity::class.java)
        )
    }

    private const val REQ_POST_NOTIFICATIONS = 4101
}
