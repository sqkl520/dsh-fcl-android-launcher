package com.dsh.ui.shell

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import com.dsh.core.DshAppScope
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
     * ★ BL-150（R18）：编排**不再跑在调用方传进来的 scope 上**（`DshAppScope` 进程级 + 主线程切回）。
     *   修的是"启动等待中切 tab → ViewPager 回收页面 → 页面 scope 取消 → 续体被取消"：进程照常
     *   起来，但收尾三件事（通知权限 / 前台服务 / 打开界面）全部丢失 —— 实例在后台裸跑、
     *   没有通知、没有前台保活。`DshRuntime.start` 本身在 IO 上阻塞到 fork 完成（停旧实例最长
     *   8s + 预检），这整段都不该被任何界面生命周期打断。
     *   - `scope` 参数保留仅为签名兼容（4 个调用点不动）；实现已不读它。
     *   - UI 三件事回到主线程后各自做**宿主存活防护**：Activity 已销毁时不弹框、不导航
     *     （状态流驱动的界面会在用户回来时自然显示当前状态；失败终态另有任务区行兜底）。
     *
     * ★ BL-161（R18）：「正在启动」Toast 下沉到本函数 —— 原来只有实例列表页补过（BL-083），
     *   详情页与右面板在"先停旧实例（最长 8s）再置 Starting"的窗口里完全没有反馈。
     *   三个入口共用本函数，下沉即一致。
     *
     * @param onOpenLogs     用户选择"查看日志"时切到日志 tab
     * @param onPrepareRuntime 用户选择"准备运行时"时触发底座解压
     * @param onStarted      启动成功后的导航（打开 WebView）
     */
    fun startInstance(
        activity: FCLActivity,
        inst: DshInstance,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
        onOpenLogs: () -> Unit,
        onPrepareRuntime: () -> Unit,
        onStarted: () -> Unit,
    ) {
        // BL-161：点击瞬间的反馈（主线程；调用方都在主线程点按钮）。
        // 用 toast 而不是任务行/卡片视觉：任务区是 DshTasks 对状态流的只读投影，
        // 塞 UI 侧任务会多一个失配源；toast 是纯旁路，最坏只是多冒一句。
        runCatching { Toast.makeText(activity, R.string.dsh_webview_starting, Toast.LENGTH_SHORT).show() }
        DshAppScope.scope.launch {
            val outcome = withContext(Dispatchers.IO) { DshRuntime.start(activity, inst) }
            withContext(Dispatchers.Main) {
                val hostAlive = runCatching { !activity.isFinishing && !activity.isDestroyed }.getOrDefault(false)
                when (outcome) {
                    is DshRuntime.StartOutcome.Started -> {
                        if (hostAlive) {
                            ensureNotificationPermission(activity)
                        } else {
                            // 宿主没了也别把"进程已起"收尾丢掉：前台服务是"实例活着"的保活手段，
                            // 不依赖任何界面。权限提示需要 Activity，跳过（下次进 App 再补）。
                            DshLogBus.append("[launcher] host gone during start; skipping notification permission prompt")
                        }
                        // FGS 用 applicationContext：本调用不应拖住 Activity 引用，且服务本就进程级。
                        // 应用在后台时 startForegroundService 可能被系统拒绝（Android 12+）——
                        // 失败记日志即可：进程已经在跑，状态流会让界面显示"运行中"，用户回来时
                        // 实例页的认领/观察链会在下一轮把服务补齐（BL-039 同族语义）。
                        runCatching { DshRuntimeService.start(activity.applicationContext) }.onFailure { e ->
                            DshLogBus.append("[launcher] FGS start failed after instance start: ${e.message}")
                        }
                        if (hostAlive) onStarted()
                        else DshLogBus.append("[launcher] host gone right after start; skipping auto-open of WebView")
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
    }

    private fun showProblem(
        activity: FCLActivity,
        title: String,
        reason: String,
        instanceId: String?,
        onOpenLogs: () -> Unit,
        onPrepareRuntime: () -> Unit,
    ) {
        // BL-150（R18）：宿主已销毁时不弹（调用链可能来自进程级协程，切回主线程时 Activity
        // 可能已不在）。失败原因不会丢：任务区终态行（BL-152）与右面板/详情页状态文案都在。
        if (runCatching { activity.isFinishing || activity.isDestroyed }.getOrDefault(true)) {
            DshLogBus.append("[launcher] start problem dialog skipped (host gone) for $instanceId")
            return
        }
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
            // BL-152-UI / P3-22（R18）：原为「确认/OK」且空回调 —— 名字易被读成"确认失败"，
            // 且关框后 DshRuntime 停在 Failed（右面板一直显示失败原因）。改为「关闭」+ 复位：
            // resetState 只在非 Running/Starting 时生效（同 DshInstancesUI 失败框的既有用法），
            // 不会把并发启动起来的别的实例状态清掉。
            .setNegativeButton(activity.getString(R.string.dsh_action_close)) {
                runCatching { DshRuntime.resetState() }
            }
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
