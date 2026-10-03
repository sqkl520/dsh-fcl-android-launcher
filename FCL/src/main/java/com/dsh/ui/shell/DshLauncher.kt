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
     * 就地准备运行时底座（解压 rootfs / 脚本）。
     *
     * ★ 与界面生命周期解耦：任务跑在进程级 [DshAppScope]，**不再弹进度对话框**。
     * 进度通过 `DshBootstrap.progress` / `DshBootstrap.busy` 这两个 StateFlow 暴露，
     * 由界面（实例页横幅）订阅显示 —— 这样切页、页面被 ViewPager 回收、旋转屏幕都不会
     * 让「进度消失」或卡住一个再也动不了的对话框。
     *
     * @param owner 归属者标识（同一入口重复点击时幂等，不会误报「已有解压任务在进行」）
     * @param onDone 完成回调（参数为是否已就绪）
     */
    fun prepareRuntime(
        activity: FCLActivity,
        owner: String = OWNER_INSTANCE_PAGE,
        onDone: (ready: Boolean) -> Unit = {},
    ) {
        if (DshBootstrap.isBusy()) {
            // 已有任务在跑：进度由横幅显示，这里不再弹任何东西
            return
        }
        DshAppScope.scope.launch {
            DshBootstrap.install(activity, owner)
            val ready = withContext(Dispatchers.IO) { DshBootstrap.isReady() }
            withContext(Dispatchers.Main) {
                if (!ready) {
                    val reason = (DshBootstrap.currentProgress() as? DshBootstrap.Progress.Failed)?.reason
                    if (reason != null) {
                        FCLAlertDialog.Builder(activity)
                            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
                            .setTitle(activity.getString(R.string.dsh_bootstrap_failed_title))
                            .setMessage(reason)
                            .setNegativeButton(activity.getString(R.string.dialog_positive), null)
                            .create()
                            .show()
                    }
                }
                onDone(ready)
            }
        }
    }

    /** 实例页横幅上的「准备运行时」 */
    const val OWNER_INSTANCE_PAGE = "instances-page"

    /** 下载页触发底座准备时用（避免与实例页互斥报错） */
    const val OWNER_DOWNLOAD_PAGE = "download-page"

    /** 外壳右侧面板触发时用 */
    const val OWNER_SHELL_PANEL = "shell-panel"

    /**
     * 确保底座就绪（挂起直到成功/失败/超时）。
     *
     * 给「装 dsh 之前必须先有底座」这类**需要等结果**的流程用（下载页）。
     * 任务同样跑在进程级作用域，界面销毁/切页不会中断它。
     */
    suspend fun ensureRuntimeReady(
        activity: FCLActivity,
        owner: String,
        timeoutMs: Long = 15 * 60_000L,
    ): Boolean {
        if (DshBootstrap.isReady()) return true
        if (!DshBootstrap.isBusy()) {
            DshAppScope.scope.launch { DshBootstrap.install(activity, owner) }
        }
        // 等任务真正占位（launch 是异步的，避免"刚启动就判定结束"）
        val grace = System.currentTimeMillis() + 2_000
        while (!DshBootstrap.isBusy() && !DshBootstrap.isReady() &&
            System.currentTimeMillis() < grace
        ) {
            kotlinx.coroutines.delay(50)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (DshBootstrap.isReady()) return true
            if (!DshBootstrap.isBusy()) return DshBootstrap.isReady()
            kotlinx.coroutines.delay(300)
        }
        return DshBootstrap.isReady()
    }

    private const val REQ_POST_NOTIFICATIONS = 4101
}
