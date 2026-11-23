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
import com.dsh.fcl.androidlauncher.R
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
                DshRuntime.stop(getString(R.string.dsh_reason_from_notification))
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
            DshRuntime.stop(getString(R.string.dsh_reason_service_recycled))
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
                is DshRuntime.State.Stopping -> context.getString(R.string.dsh_notify_stopping)
                // Idle/Failed/Exited：只会在服务即将自停的极短窗口出现，文案别再误导成"运行中"
                else -> context.getString(R.string.dsh_notify_stopping)
            }
            // 端口与状态都是用户可见的（通知栏），跟着界面语言走。
            // 有端口用带 %2$s 的那条、没有就用两条参数的 —— 两条都带占位符，不做字符串拼接：
            // 拼接会把"· "这类分隔符也固化进代码，译文里想换成分号就没辙。
            val text = if (port != null && port > 0) {
                context.getString(R.string.dsh_notify_text_port, name, port.toString(), status)
            } else {
                context.getString(R.string.dsh_notify_text, name, status)
            }

            val pending = shellPendingIntent(context)
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
                // ★ L1：pending 现在**可空**（外壳入口注册缺失 + 系统也没给 LAUNCHER 入口）。
                //   NotificationCompat 接受 null = "通知没有点击目标"，不是崩溃。状态文案与
                //   "停止"动作照常在，用户仍能从这个通知里看到"在跑/在停"并停掉它。
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_baseline_close_24, context.getString(R.string.dsh_action_stop), stopPending)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            }
            return builder.build()
        }

        /**
         * L1：通知的 contentIntent —— 点通知把用户带回 App。
         *
         * ## 为什么不再直接 `Intent(context, DshMainActivity::class.java)`
         * 那是全仓唯一一条 `core → ui` 的编译期依赖（见 [DshShellEntry] 的 KDoc）。
         * 现在改为向 [DshNavigation] 要外壳入口，core 只认接口、不认 Activity 类型。
         *
         * ## 退化策略（三级，**任何一级都不许把服务带崩**）
         * 1. **ui 层已注册** → 用 [DshShellEntry.shellIntent] 给的 Intent。
         * 2. 未注册 / 注册的实现自己抛了 / 返回 null → 退到
         *    `packageManager.getLaunchIntentForPackage(packageName)`：系统给的 LAUNCHER 入口，
         *    与用户点桌面图标同一条路（启动页 → 主壳）。这是"脱掉 ui 依赖后"最合理的兜底 ——
         *    它由**系统**回答"这个包点开是哪个 Activity"，core 不需要知道类名。
         * 3. 连它都拿不到（包被禁用 / PM 异常 / 单元测试无 PM）→ 返回 null，
         *    通知**不挂 contentIntent**（见 [buildNotification] 里的说明），不是崩溃。
         *
         * ## 为什么不缓存
         * 外壳入口可能在进程生命周期内被换掉（单测 / 未来多外壳），且构造一次 Intent 是零成本。
         * 真正贵的 `PendingIntent.getActivity` 由系统按相同的 Intent 复用（FLAG_UPDATE_CURRENT）。
         */
        private fun shellPendingIntent(context: Context): PendingIntent? {
            val intent = resolveShellIntent(context) {
                context.packageManager.getLaunchIntentForPackage(context.packageName)
            } ?: return null
            // PendingIntent.getActivity 在极端现场（通知配额、Binder 故障）会抛；
            // "点通知没反应"远好于"构建通知时把前台服务带崩"。
            return runCatching {
                PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }.getOrNull()
        }

        /**
         * 取出 Intent 并补上启动标志。
         *
         * 标志单独一步、不并进 [pickShellIntent]，是为了让那条选择规则能在 **JVM 单测** 里
         * 跑到：`android.jar` 里 `Intent.addFlags` 的方法体是 `Stub!`
         * （`javap` 实测：`new RuntimeException("Stub!") … athrow`），一调用就抛。
         * 于是"选哪个 Intent"用纯函数钉、"补什么标志"用源码结构断言钉（本仓库既有惯例，
         * 见 `R33LoadUrlGateWiringTest` / `R34PortGateTest`）。
         */
        private fun resolveShellIntent(context: Context, launchIntentProvider: () -> Intent?): Intent? =
            pickShellIntent(DshNavigation.shellEntry, context, launchIntentProvider)?.apply {
                // NEW_TASK：从 Service（非 Activity Context）拉起必须带；SINGLE_TOP：已经在栈顶时
                // 复用而不是再开一个外壳。这两条与改造前**完全一致** —— 换的只是"Intent 从哪来"。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

        /**
         * L1 的可测接缝：**选出一个能拉起外壳的 Intent**，或 null（= 通知不加点击目标）。
         *
         * ## 为什么抽出来
         * 这是"core 不再认识任何 Activity 类型"这件事的**唯一**实现点。抽成不碰 Android
         * 资源、不建 PendingIntent、不加标志的纯选择逻辑之后，三条规则都能被 JVM 单测直接钉住
         * （见 `R49LayeringFixTest`）：注册优先 / 未注册退化 / 全都拿不到返回 null。
         *
         * ## [shellEntry] 为什么是参数而不是直接读 [DshNavigation]
         * 直接读全局会让"未注册 → 退化"这条分支只能靠 `DshNavigation.clear()` 制造，
         * 而全局状态在 MiniRunner 里是跨用例共享的 —— 一条断言失败就会污染后面所有用例。
         * 参数化之后每个用例自带条件，互不干扰；生产调用点照旧传 [DshNavigation.shellEntry]。
         *
         * ## 实现自抛异常也不许穿出去
         * 注册方是 App 层的一段 lambda，将来若有人在里面读了还没就绪的东西（例如某个
         * 只在 Activity 里才有的单例），一个异常就会把**构建通知**整条链路带崩 ——
         * 而通知是前台服务的存活凭据（Android 8+ 不 `startForeground` 直接
         * `ForegroundServiceDidNotStartInTimeException` 崩溃）。所以注册实现抛异常
         * 等价于"未注册"，继续往下一级退化。
         *
         * @param launchIntentProvider 退化用的 LAUNCHER 入口。**惰性**：注册了外壳入口时
         *        一次都不会调用（单测断言了这一点 —— 否则"注册优先"只是名义上的）
         */
        internal fun pickShellIntent(
            shellEntry: DshShellEntry?,
            context: Context,
            launchIntentProvider: () -> Intent?
        ): Intent? =
            shellEntry?.let { runCatching { it.shellIntent(context) }.getOrNull() }
                ?: runCatching { launchIntentProvider() }.getOrNull()

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeepSeek Harness Runtime",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = context.getString(R.string.dsh_notify_channel_desc)
            manager.createNotificationChannel(channel)
        }
    }
}
