package com.dsh.ui.shell

import android.content.pm.PackageManager
import android.os.Build
import com.dsh.core.DshInstance
import com.dsh.core.DshLogBus
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
 * 启动实例的共享流程（启动 → 导航），供实例页 [DshInstancesUI]、实例详情页
 * [DshInstanceSettingPage] 与外壳右侧面板 [DshMainActivity] 复用，避免把这段逻辑复制三份。
 *
 * ★ 这里**没有**"启动前检查 API Key"那一步了（批次 5 删除）。
 * 原因不是"启动器不该管 Key"这种偏好，而是注进去会让用户改不掉：dsh 的凭据提供者把
 * 继承来的进程环境排在最高优先级且对写入直接报错（详见 [DshRuntime.start] 里的那段注释）。
 * 于是"有没有配 Key"从启动器的阻断条件变成了 dsh 自己的状态：没配也能起，只是对话会失败，
 * 而补救方式是在 dsh 页面里填 —— 首次进界面时由 [com.dsh.ui.DshWebViewActivity] 提示一次。
 *
 * 所有阻塞调用（killStale、ProcessBuilder.start）都在 IO 上执行。
 */
object DshLauncher {

    /**
     * BL-086（R15）：最近一次"启动失败弹窗"的实例与时间戳（uptimeMillis）。
     *
     * 为什么需要：启动失败的出口有两条 —— ①本类的 [showProblem]（同步启动的 Failed/NotReady
     * outcome）；②[com.dsh.ui.shell.DshInstancesUI] 的状态观察者（`State.Failed` 驱动，
     * 兜看门狗等异步失败）。同步失败时 DshRuntime 先置 `State.Failed` 再返回 outcome，
     * 两条链几乎同时弹框 → 用户连关两次。
     *
     * 策略：**以 DshRuntime 的状态为真源** —— [showProblem] 弹框前若发现"同实例的 Failed
     * 刚被状态链弹过"（窗口 [FAIL_DEDUPE_WINDOW_MS] 内），跳过本类弹窗（状态链的弹窗带
     * 相同的"查看日志/准备运行时"出口，语义等价）。反向同理：状态链弹框时检查本戳。
     * 只在主线程读写（弹框本身在主线程；状态收集也在主线程）。
     */
    @Volatile
    var lastProblemShownInstanceId: String? = null
    @Volatile
    var lastProblemShownAt: Long = 0L

    /** 去重窗口：两条链的触发间隔在毫秒级（同一主线程 turn 前后的两次 collect/返回），2 秒足够宽 */
    private const val FAIL_DEDUPE_WINDOW_MS = 2_000L

    /** 供状态链（DshInstancesUI）查/写去重戳；[instanceId] 为 null（无归属）时不参与去重 */
    fun problemJustShownFor(instanceId: String?): Boolean {
        if (instanceId == null) return false
        return lastProblemShownInstanceId == instanceId &&
            android.os.SystemClock.uptimeMillis() - lastProblemShownAt <= FAIL_DEDUPE_WINDOW_MS
    }

    fun markProblemShown(instanceId: String?) {
        lastProblemShownInstanceId = instanceId
        lastProblemShownAt = android.os.SystemClock.uptimeMillis()
    }

    /**
     * 启动一个实例。启动失败 / 未就绪时会在主线程弹对话框（原因 + 看日志 + 准备运行时）。
     *
     * @param onOpenLogs     用户选择"查看日志"时切到日志 tab
     * @param onPrepareRuntime 用户选择"准备运行时"时触发底座解压
     * @param onStarted      启动成功后的导航（打开 WebView）
     */
    fun startInstance(
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
                    inst.id, onOpenLogs, onPrepareRuntime
                )
                is DshRuntime.StartOutcome.Failed -> showProblem(
                    activity, activity.getString(R.string.dsh_start_failed), outcome.reason,
                    inst.id, onOpenLogs, onPrepareRuntime
                )
            }
        }
    }

    private fun showProblem(
        activity: FCLActivity,
        title: String,
        reason: String,
        instanceId: String?,
        onOpenLogs: () -> Unit,
        onPrepareRuntime: () -> Unit,
    ) {
        // BL-086：状态链（DshInstancesUI 的 State.Failed 观察者）刚弹过同实例的框 →
        // 本框跳过（两条链都在主线程，戳的读写无并发；窗口 2s 覆盖毫秒级交错）。
        if (problemJustShownFor(instanceId)) {
            DshLogBus.append("[launcher] start problem dialog suppressed (already shown by state observer for $instanceId)")
            return
        }
        markProblemShown(instanceId)
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
