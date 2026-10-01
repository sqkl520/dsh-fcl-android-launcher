package com.dsh.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dsh.ui.DshInstancesActivity
import com.tungsten.fcl.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * dsh 运行时前台服务：把长驻的 `dsh web` 进程挂在前台通知上，避免安卓杀后台。
 * 思路照搬 FCL 的 [com.mio.download.DownloadService]，但承载对象是 [DshRuntime] 的常驻进程。
 *
 * ## 本次加固（对应审查发现的问题）
 * 1. **不会留下"僵尸通知"**：原来进程自己退出后服务没人停，通知一直挂在那里说"运行中"。
 *    现在本服务订阅 [DshRuntime.state]，一旦不再是 Starting/Running 就 `stopForeground + stopSelf`。
 * 2. **通知里能直接停**：加了"停止"动作（原来必须回 App 才停得掉）。
 * 3. **前台类型**：Android 14+ 用 `specialUse`，避免当 `dataSync` 用而在 Android 15 上撞到
 *    "dataSync 每天累计 6 小时"的上限（对"挂着跑很久的 agent"是致命的）。
 * 4. 通知内容带上实例名与端口，多实例时不会看错。
 */
class DshRuntimeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /** 状态观察协程（onDestroy 里取消：进程级作用域不会随服务销毁结束，不取消就会一直累积） */
    private var stateWatcher: Job? = null

    override fun onCreate() {
        super.onCreate()
        // 观察运行时状态：停了就收自己
        // 注意 drop(1)：StateFlow 订阅时会立刻回放当前值，若此刻是 Idle 就把自己停掉会与
        // onStartCommand 里的 startForeground 抢时序（服务刚起就被收）。
        // 这个协程挂在进程级作用域上，必须自己 cancel —— 否则每次"启动服务 → 服务结束"都会
        // 在 scope 上永久留下一个收集器，同时还拽着已经销毁的 Service 实例不让回收。
        stateWatcher?.cancel()
        stateWatcher = DshAppScope.scope.launch {
            DshRuntime.state.drop(1).collect { st ->
                if (st !is DshRuntime.State.Starting && st !is DshRuntime.State.Running) {
                    stopSelfSafely()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DshRuntime.stop("通知栏停止")
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }

        val st = DshRuntime.state.value
        if (st !is DshRuntime.State.Starting && st !is DshRuntime.State.Running) {
            // 已经没有运行实例：立即收尾（原实现只判了 running==null，Starting 阶段会闪一下）。
            // ★ 但必须先调一次 startForeground 再收：本服务是被 startForegroundService() 拉起来的，
            // Android 8+ 要求"5 秒内调用 startForeground()"，否则系统直接判定
            // ForegroundServiceDidNotStartInTimeException 崩溃（不是 ANR，是崩溃）。
            // 而"拉起服务的那一刻实例刚好已经退出/失败"正是最容易踩到这条的路径。
            startForegroundCompat(buildNotification(this, st))
            stopSelfSafely()
            return START_NOT_STICKY
        }
        startForegroundCompat(buildNotification(this, st))
        return START_STICKY
    }

    override fun onDestroy() {
        stateWatcher?.cancel()
        stateWatcher = null
        super.onDestroy()
        // 服务被系统回收时确保进程也停掉，避免僵尸 node 进程
        if (DshRuntime.runningInstanceId() != null) {
            DshRuntime.stop("前台服务被回收")
        }
    }

    private fun stopSelfSafely() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse：长时间运行的本地服务，不受 dataSync 的 6h/天上限约束
            runCatching {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }.onFailure {
                // 极端情况下（清单未声明 specialUse）退回 dataSync，保证还能起
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1401
        const val ACTION_STOP = "com.dsh.core.action.STOP"
        private const val CHANNEL_ID = "dsh_runtime"

        fun start(context: Context) {
            val intent = Intent(context, DshRuntimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DshRuntimeService::class.java))
        }

        /** 供 [DshRuntime] 在没有 Context 引用时静默收尾（null 安全） */
        fun stopQuietly(context: Context?) {
            val ctx = context ?: return
            runCatching { stop(ctx) }
        }

        internal fun buildNotification(context: Context, state: DshRuntime.State): Notification {
            createChannel(context)
            val name = when (state) {
                is DshRuntime.State.Starting -> state.name
                is DshRuntime.State.Running -> state.name
                else -> "dsh"
            }
            val port = (state as? DshRuntime.State.Running)?.port
            val status = when (state) {
                is DshRuntime.State.Starting -> context.getString(R.string.dsh_notify_starting)
                is DshRuntime.State.Running -> context.getString(R.string.dsh_notify_running)
                else -> context.getString(R.string.dsh_notify_running)
            }
            val text = if (port != null && port > 0) "$name · 端口 $port · $status" else "$name · $status"

            val contentIntent = Intent(context, DshInstancesActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pending = PendingIntent.getActivity(
                context, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 通知栏直接停止（原来必须切回 App 才能停）
            val stopIntent = Intent(context, DshRuntimeService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPending = PendingIntent.getService(
                context, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_download_24)
                .setContentTitle("DeepSeek Harness")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_baseline_close_24, context.getString(R.string.dsh_action_stop), stopPending)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            }
            return builder.build()
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeepSeek Harness Runtime",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "dsh web 进程保活通知"
            manager.createNotificationChannel(channel)
        }
    }
}
