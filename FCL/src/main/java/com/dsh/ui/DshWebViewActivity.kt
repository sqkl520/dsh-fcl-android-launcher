package com.dsh.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshRuntime
import com.dsh.core.UrlScanner
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.component.view.FCLProgressBar
import com.tungsten.fcllibrary.component.view.FCLTextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * 承载 dsh Web UI 的 WebView 页面。
 *
 * 鉴权（实测结论）：dsh 的 token 是"token→cookie"模式——首次访问 `/?token=XXXX` 会 302 并
 * `set-cookie`，此后靠 cookie。**关键实测结果**：该 cookie 的签名密钥持久化在 `DSH_HOME` 下，
 * 所以只要 cookie 还在，即使用新 token 重启进程，旧的 cookie 依然能 200 通过。
 *
 * ## 本次加固（对应审查发现的问题）
 * 1. **不会再无限转圈**：状态机覆盖 启动中 / 加载中 / 就绪 / 失败（带原因 + 重试 + 看日志 + 停止）。
 *    原来 URL 抓不到就是一个永远转的进度条。
 * 2. **cookie 持久化**：显式打开 cookie 并 `flush()`，App 重启后免 token 直接进。
 * 3. **401 兜底**：cookie 失效时，如果手里有带 token 的 URL 就自动重载一次；否则给出明确提示。
 * 4. **WebView 安全收紧**：关闭 file/content 访问、限制混合内容、禁用多窗口；
 *    站外链接改由系统浏览器打开（不在 WebView 里把本地 UI 导航走）。
 * 5. **销毁更安全**：先从父容器移除再 destroy（原来直接 destroy 有崩溃风险），并 flush cookie。
 * 6. **返回键**走 OnBackPressedDispatcher（`onKeyDown` 已废弃）。
 * 7. JS 控制台输出进日志总线，出错时能直接从 App 里看到前端报错。
 *
 * ## 首次引导（批次 5）
 * 启动器不再注入 API Key（注进去 dsh 反而写不进去，见 [com.dsh.core.DshRuntime]），"没配 Key"
 * 也就不再有任何启动前的提示。第一次真正把界面加载出来时弹一次"去哪填 Key"的说明，
 * 否则用户会卡在"UI 起得来、一发消息就失败"，而且不知道该去哪儿配。
 *
 * ## fix2（BL-056/057/058/060/063/115/124半/129/130）
 * 1. **compatOk 一次性放行**：版本门禁的选择只做一次，之后本页直连 `loadUrl`，状态回放/重建
 *    不再重弹对话框（与 [DshWebViewCompat] 的单例守卫构成双层闭环）。
 * 2. **渲染进程崩溃接管**：覆写 `onRenderProcessGone` 返回 true（阻止系统连坐杀 App），
 *    销毁旧 WebView 并按原布局位重建后重渲染；重建失败退化为错误面板 + 「重试」再试。
 * 3. **重试按钮按状态分支**：Running=重载页面；Failed/Idle/Exited=重启对应实例（找不到就提示返回）。
 * 4. **外跳收尾**：版本门禁选"外部浏览器"改走 [openExternalFresh] —— 点击时刻 fresh URL、
 *    失败复制链接兜底，跳完"壳前移" + `finish()` 回首页（消外跳后的空白死票）。
 * 5. **返回/关闭 = 壳前移**（REORDER，本页留栈保活）；仅「停止」按钮保留 `finish()`（主动停止=放弃缓存）。
 * 6. **日志覆盖层**：出口优先落覆盖层所显示实例的详情页（REORDER 非破坏变体，消 CLEAR_TOP 单程票）；
 *    裁剪位移补偿 + pin 监听去重。7. **pageFailed 守卫**；`aliveCount` 供外壳返回判活。
 */
class DshWebViewActivity : FCLActivity() {

    /** 与 [com.dsh.ui.setup.DshSetupActivity] 共用一份 "launcher" 偏好文件（同一份 App 级设置） */
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    /** 首次引导标记的前缀：后面拼实例 id（拿不到实例时拼 "global"） */
    private val KEY_FIRST_RUN_HINT = "dsh_key_first_run_hint_"

    private lateinit var webView: WebView
    private var loadedUrl: String? = null
    private var retriedWithToken = false

    /**
     * 版本门禁（[DshWebViewCompat.ensureSupported]）是否已经放行过（BL-111）。
     *
     * 用户在本页做出一次选择（"仍要继续"）后置 true：之后所有加载（状态回放、
     * `webView.url == null` 分支、401 兜底、崩溃重建后的重载）都直连 `loadUrl`，
     * 不再进门禁 —— 否则 repeatOnLifecycle 每次 STARTED 重收集回放时都会重弹对话框。
     */
    private var compatOk = false

    /**
     * BL-200/BL-201：复制确认框的实例引用（`null` = 没有确认框开着）。
     *
     * FCLAlertDialog 的按钮点击会先无条件 dismiss（java:93-106），所以点「复制链接」会让门禁框
     * 带着 onDismissWithoutChoice 一起关闭。这个引用同时承担三件事：
     * 1. **流程进行中**的判据（取代 fix9 的单布尔 `copyLinkFlowActive`）——门禁框与确认框可以
     *    并存（确认框开着时一次状态回放就会把门禁框重新摆出来，BL-201），单布尔表达不了
     *    "两张框各自属于谁、谁还开着"；
     * 2. `onDestroy` 能真的把窗口收掉（fix9 的裸 `show()` 不进任何单例槽，没人收它，BL-200）；
     * 3. 确认框的 dismiss 监听用"引用是否仍指向自己"区分**用户关框**与**外部收框**
     *    （与 DshWebViewCompat 的 wasCurrent 纪律同款），外部收框不会反过来触发一次重摆。
     */
    private var copyDialog: FCLAlertDialog? = null

    /**
     * BL-197/BL-201：「可决策窗口」（门禁框 / 崩溃升级提示）**每次真正 show 之前**自增的序号。
     *
     * 复制流程开始时会记下当时的序号；收尾时若序号已经变了，说明期间已经有新的门禁框摆出来
     *（状态回放 / Home 往返即可触发）—— 此刻屏上已有决策出口，再重摆一次只会把刚摆好的框
     * 换一次，并与确认框的关闭时序打架。这条序号是"收尾要不要干预"的唯一判据。
     */
    private var decidableDialogSeq = 0L

    /** BL-197：延迟重摆的令牌。新一次复位 / onDestroy 会自增，让在途的旧任务作废。 */
    private var resumeGateToken = 0L

    /**
     * BL-199（fix10 集成）：用户刚在本轮点了「稍后」——下一次 [onDismissWithoutChoice] 要跳过重摆。
     *
     * 为什么要一个标记而不是让 `onLater` 直接接管：`FCLAlertDialog` 的任一按钮点击都会无条件
     * `dismiss()`，随后统一走 `onDismissWithoutChoice`；而那个回调**分不清**"用户点了稍后"与
     * "框被程序化关掉"——前者要求停在原地不再弹，后者要求立刻重摆（否则空白页）。
     * 标记在重摆/放弃重摆两条路径上都要复位，见 [resumeGateAfterCopyLink] 与 render()。
     */
    private var gateDeferred = false

    /**
     * 日志覆盖层正在展示的实例 id（`null` = App 级全局流）。
     *
     * BL-025：FCL 原版（`LogWindow`）的运行期日志是**同页全屏覆盖层**，只切显隐、
     * 不出 Activity、不碰返回栈；之前的做法是 `startActivity` 带 `CLEAR_TOP` 去外壳，
     * 把返回栈上的 WebView 销毁成了"单程票"。这里照 FCL 做：覆盖层默认 gone，
     * 日志按钮只打开本页内的覆盖层。
     *
     * 订阅源在 [openLogOverlay] 里打开时**固定一次**（理由同 `DshLogsUI`：两条流的
     * revision 各自独立递增，中途换流会让去重错乱导致不刷新）。
     */
    private var logOverlayInstanceId: String? = null

    /** 覆盖层打开期间的日志订阅。只在覆盖层可见时收集：关闭就取消，不浪费刷新。 */
    private var logOverlayJob: Job? = null

    /** 覆盖层上次渲染的快照 revision（去重：同 revision 不重建文本） */
    private var logOverlayLastRevision = -1L

    /** 覆盖层上次渲染时已被 `takeLast` 从顶部裁掉的行数（BL-115 位移补偿的基线） */
    private var logOverlayLastTrimmed = 0

    /** 覆盖层待执行的一次性滚动调整（pin 与裁剪补偿共用；同一时刻最多挂一个，先摘后挂） */
    private var logOverlayAdjustListener: ViewTreeObserver.OnPreDrawListener? = null

    /**
     * 覆盖层日志刷新节流：每 500ms 最多重建一次文本。
     *
     * 总线本身 200ms 一次 flush；覆盖层是只读扫一眼，不需要跟满速，
     * 慢半拍换主线程开销减半 —— 重建+着色是 O(行数) 的工作。
     */
    private val LOG_OVERLAY_SAMPLE_MS = 500L

    /**
     * 覆盖层单次渲染的最大行数（超出只显示末尾）。
     *
     * 日志页（`DshLogsUI`）用 1500 行 + 级别着色，但那是常驻页面；
     * 覆盖层只是"扫一眼出错原因"，500 行够从尾巴往回翻好几屏，
     * 且纯文本拼接比重建 1500 个 span 便宜得多。
     */
    private val LOG_OVERLAY_MAX_LINES = 500

    /** 主 frame 当前页面是否处于\"加载失败\"状态（用 onPageStarted 与错误回调维护）。
     *  onPageFinished 无条件 showWeb() 会把 onReceivedError 刚显示的错误面板盖掉
     *  （WebView 对失败页面也会回调 onPageFinished），导致用户看到空白页而非错误信息。 */
    private var pageFailed = false

    /**
     * BL-056：渲染进程崩溃（[onRenderProcessGone][WebViewClient.onRenderProcessGone]）后
     * 重建也失败，WebView 已不可用（旧对象已销毁、新对象没建出来）。
     * 此时一切 `webView.xxx` 都不许碰：界面只剩错误面板，由「重试」再走一次重建。
     */
    private var webViewDead = false

    /** 连续崩溃重建次数（稳定存活后清零）——够上限就停自动重建，防"重建→冷载→再崩"风暴 */
    private var crashRebuilds = 0

    /**
     * R14-V2B 附注：崩溃预算的**延迟清零**时刻（uptimeMillis）。onPageFinished 时设为
     * "现在 + [CRASH_BUDGET_RESET_DELAY_MS]"，崩溃发生时若已到期才真正清零 —— 防止
     * "出页即清零、数秒后再崩"在老设备上无限重置预算。
     */
    private var crashRebuildsResetAt: Long = 0L

    /**
     * R16/fix4（BL-140）：**本次页面加载**是否已经为"老 WebView 上 SPA 崩坏"弹过升级提示。
     *
     * 为什么需要去重：`onConsoleMessage` 里一条崩溃往往连打多条同因消息（bebug (2) 里同一
     * 加载出现 boot 脚本 + plugin 装载两条 `is not a function`），不去重就会连弹两框。
     * 语义 = "每次加载最多提示一次"（`onPageStarted` 重置）—— 用户重新加载页面后若仍崩，
     * 再提示一次是合理的（他可能没看到/没行动）。
     */
    private var crashHintShownForThisLoad = false

    /** WebView 在原布局里的容器与布局参数（onCreate 时捕获，崩溃重建按原位插回新实例） */
    private var webViewContainer: ViewGroup? = null
    private var webViewLayoutParams: ViewGroup.LayoutParams? = null

    /**
     * BL-173：日志覆盖层控制台**底板 + 正文**的主题刷新（与 [com.dsh.ui.shell.DshLogsUI] 同款）。
     *
     * 底板是布局里一次性的 `android:background="@drawable/bg_log_console"`（中性色，
     * 亮/暗各一套）。本页的 `configChanges` 含 `uiMode`（见 AndroidManifest）→ 运行期切亮暗
     * **不重建 Activity**，drawable 在 inflate 那一刻解析完就被永久钉住。
     *
     * ★ 覆盖层的**正文**与底板**必须同一处、同一次回调里一起刷**，只刷底板会修出新缺陷：
     * `log_overlay_text` 刻意不挂 `app:auto_text_tint`（见 activity_dsh_webview.xml 的注释 ——
     * 那一族取黑/白，配中性底板会撞色），于是它走的是主题默认色；而默认色**和底板一样**是
     * inflate 时解析完就钉住的（布局注释里"与底板天然对得上"只在**没在运行期切过亮暗**时成立）。
     * 日志页没这个毛病是因为它每行都被 `levelColors` 的 span 整体覆盖、而那份颜色挂在刷新名单里；
     * 覆盖层是纯文本，没有任何东西替它重解析。
     * 所以现状是"一老一老、成对但整体过时"；只刷底板 = 变成"一新（night 暗底）一老
     * （day 深字）" → 对比度比修之前更差。两支色一起取，配对关系由**同一次回调**保证。
     *
     * 取色口径：底板 @color/dsh_log_console_bg、正文 @color/dsh_log_info（= 日志页 INFO 档
     * 同一支，与这块底板是配套设计的亮暗两套），两者都是 DayNight 资源。
     * 这里用 `setTextColor(Int)`（纯颜色覆盖，正文没有别的 textAppearance 要保）；
     * 覆盖层正文零 span，所以这一支就是它的全部颜色出处。
     *
     * 底板用 `backgroundTintList`（默认 SRC_IN：只换不透明像素的色，8dp 圆角仍由 shape 出），
     * **不用**重新 `setBackgroundResource`：AOSP `View` 该方法开头是
     * `if (resid != 0 && resid == mBackgroundResource) return;` —— 布局里的 background 不填
     * 这个字段，于是**只有第一次**重复调用会重解析，第二次起（亮→暗→亮往回切）直接早退，
     * 修复退化成"单向有效"的空操作。**也不用** `app:auto_tint` / `use_theme_color` 那族
     * 声明式方案（fcllibrary 源码已核）：取的是**用户主题色**（`ltColor` / `getColor2()`），
     * 这块底板要的是与主题色无关的中性色，挂上去颜色就是错的；且库里没有可替换 ScrollView
     * 的 FCL 版滚动容器（view 目录下无 FCLScrollView），换控件类型会牵动 BL-115 的 pin/补偿
     * 接线，超出"最小改动"边界。
     *
     * 字段**具名**：回调表按 View 为 key，注册与反注册必须是同一个实例（同
     * [com.dsh.ui.shell.DshLogsUI.logTextFocusListener] 那条"注销得掉"的前提）。
     */
    private var logConsoleScroll: ScrollView? = null
    private var logConsoleText: FCLTextView? = null
    private val logConsoleThemeRefresh = Runnable { refreshLogConsoleTheme() }

    /** [logConsoleThemeRefresh] 的本体；判空即"本页已销毁"（onDestroy 置 null），直接跳过。 */
    private fun refreshLogConsoleTheme() {
        val scroll = logConsoleScroll ?: return
        // 底板与正文各按**当前配置**现取一次 —— 同源同刻，不会分家
        scroll.backgroundTintList = ColorStateList.valueOf(getColor(R.color.dsh_log_console_bg))
        logConsoleText?.setTextColor(getColor(R.color.dsh_log_info))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // aliveCount 与 onDestroy 的 -- 成对：放在最前段，之后任何路径只要系统回调 onDestroy
        // 都能配平（onCreate 都没走到 onDestroy 的失败里进程已不可用，计数无人再读）。
        aliveCount++
        registerLive(this)
        setContentView(R.layout.activity_dsh_webview)
        // BL-173：覆盖层控制台的底板与正文纳入主题刷新（机理与取舍见 [logConsoleThemeRefresh]）。
        // key 用底板控件本身，[onDestroy] 按同一实例反注册。
        val console = findViewById<ScrollView>(R.id.log_overlay_scroll)
        logConsoleScroll = console
        logConsoleText = findViewById(R.id.log_overlay_text)
        ThemeEngine.getInstance().registerEvent(console, logConsoleThemeRefresh)
        webView = findViewById(R.id.web_view)
        // 崩溃重建要把新 WebView 插回原位：容器与布局参数此刻捕获一次（重建时旧对象已被摘除，
        // 再查 getChildAt(0) 会拿到进度条）。
        webViewContainer = webView.parent as? ViewGroup
        webViewLayoutParams = webView.layoutParams

        setupWebView()

        findViewById<View>(R.id.btn_retry).setOnClickListener { onRetryClicked() }
        findViewById<View>(R.id.btn_logs).setOnClickListener {
            // BL-025：默认行为**不离开本页** —— 打开同页全屏日志覆盖层
            // （FCL 的 LogWindow 做法：只切显隐，不出 Activity、不碰返回栈）。
            // 之前是 startActivity(CLEAR_TOP) 去外壳看日志，栈上的 WebView 被销毁，
            // 点"日志"等于一张回不去 dsh 页的单程票。
            openLogOverlay()
        }
        findViewById<View>(R.id.btn_log_overlay_close).setOnClickListener { closeLogOverlay() }
        findViewById<View>(R.id.btn_log_overlay_full).setOnClickListener {
            // "去外壳看完整日志"：保留的外壳跳转出口。
            //
            // ★ 为什么不再用 `intentForTab(TAB_LOGS)`：外壳**已经没有"日志"这个 tab** ——
            //   日志按实例归属收进了实例详情页（App 级那份在「设置」里）。
            //
            // BL-063：优先用覆盖层**实际显示**的实例（`logOverlayInstanceId`，开启时固定），
            // 而不是点按钮时重查 DshRuntime —— 覆盖层显示谁就必须给谁看，实例中途停止/切换
            // 也不会把用户带错庙。实例已被删（byId 未命中）时回落现逻辑。
            // 现逻辑（原样保留）：取当前运行实例 → 它的详情页「日志」段；没有运行实例 → 设置页。
            //
            // BL-124：详情页出口用 intentForInstanceReorder —— 非破坏性 REORDER，本页留栈保活
            //（旧的 intentForInstance 带 CLEAR_TOP，会把本页销毁成"回不去"的单程票）。
            // 兜底的设置页仍用 intentForTab（保留 CLEAR_TOP）：走这条路时已无具体实例可看，
            // "回来 resume 本页"的价值最低，保留旧语义最省事（自定，见任务 5① 注释）。
            val targetId = logOverlayInstanceId?.takeIf { DshInstances.byId(it) != null }
                ?: DshRuntime.runningInstanceId()
            val intent = if (targetId != null) {
                com.dsh.ui.shell.DshMainActivity.intentForInstanceReorder(
                    this, targetId, com.dsh.ui.shell.DshInstanceDetailPage.TAB_LOGS
                )
            } else {
                com.dsh.ui.shell.DshMainActivity.intentForTab(
                    this, com.dsh.ui.shell.DshShellHost.TAB_SETTINGS
                )
            }
            // 先关覆盖层再跳：覆盖层的订阅在 onStop 里本来也会被取消，这里显式关掉只是为了
            // "用户从最近任务切回来时"不残留一个开着的覆盖层（REORDER 复用场景下 Activity 会被复用，
            // 这一关是必需的）。
            closeLogOverlay()
            startActivity(intent)
        }
        findViewById<View>(R.id.btn_stop).setOnClickListener {
            DshRuntime.stop(getString(R.string.dsh_reason_user_stop))
            finish()
        }
        // BL-124：不 finish —— 改"壳前移"（REORDER，不销毁任何页面：本页留栈保活，
        // 外壳被拉到前台；重开=同一 WebView resume，不再冷载 SPA）。仅「停止」按钮除外。
        findViewById<View>(R.id.btn_back).setOnClickListener { bringShellToFront() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // BL-025：覆盖层开着时返回键先关覆盖层，再走原返回逻辑。
                // FCL 的 LogWindow 也是"先收日志层"（见 GameMenu 对 log_window 的显隐切换）。
                if (findViewById<View>(R.id.log_overlay).visibility == View.VISIBLE) {
                    closeLogOverlay()
                } else if (!webViewDead && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    bringShellToFront() // BL-124：原为 finish()，改壳前移保活
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DshRuntime.state.collect { st -> render(st) }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // 本地应用不需要文件/内容访问；关掉可减少攻击面
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mediaPlaybackRequiresUserGesture = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptCookie(true)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val bar = findViewById<FCLProgressBar>(R.id.progress)
                bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                bar.progress = newProgress
            }

            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg?.let {
                    logWeb("[web] ${it.message()} @${it.sourceId()}:${it.lineNumber()}")
                    // R16/fix4（BL-140）：老 WebView 上 SPA 崩坏的信号（is not a function 类）
                    // → 补弹一次带升级指引的对话框（每次加载最多一次，见字段注释）。
                    maybeShowCrashUpgradeHint(it.message())
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                // 只允许回环地址留在 WebView 内；其余交给系统浏览器
                if (isLoopback(url)) return false
                // BL-205（R23-E1-01）：BL-183 的"外跳留痕"此前只覆盖了门禁那一支，
                // 页面内容里的站外链接（聊天里贴的 URL）走这条路径时**零日志、失败静默** ——
                // 出事后无法回溯"我们把什么交给了谁"，用户点不开也不知道为什么。
                // 这里 URL 来自页面内容而非 state，没有归属可言（不需要 owner 校验），
                // 只需要"发出去了什么"的留痕；骨架口径与 openExternalFresh 一致
                // （scheme://host:port + 有无凭据，绝不记 token 值）。
                // BL-208：走 logWeb（实例流 + 全局双写），让实例详情页的日志 tab 也看得到。
                val dispatched = runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                dispatched.fold(
                    onSuccess = {
                        logWeb("[web] external link dispatched: " + urlSkeleton(url))
                    },
                    onFailure = { e ->
                        logWeb(
                            "[web] external link failed (no browser handler?): " +
                                urlSkeleton(url) + ", " + e
                        )
                    }
                )
                // 失败也返回 true：不能让页面自己在 WebView 里导航到站外（那会顶掉 dsh 界面），
                // 与既有行为一致（fix9 之前同样无条件 true）；用户侧至少有一条日志可查。
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (isLoopback(url)) {
                    pageFailed = false
                    // R16/fix4（BL-140）：新一次加载 → 重新给崩溃提示一次机会
                    crashHintShownForThisLoad = false
                    showWeb()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 失败页面也会回调 onPageFinished；若刚收到过主 frame 错误，保持错误面板，
                // 不要用 showWeb() 把它盖掉（否则用户只看到一片空白，以为 dsh 没起）。
                if (pageFailed) return
                showWeb()
                // 出页成功：**稳定存活一段时间后**才清掉崩溃重建计数。
                // ★ R14-V2B 附注：原实现"出页即清零"在老华为（低内存常在加载刚完成后被 OOM）
                //   会无限重置预算 —— "崩→重建→出页→再崩"永远进不了风暴上限。
                //   改为延迟清零：60 秒内没再崩才恢复满预算；期间再崩计数据实累加。
                crashRebuildsResetAt = SystemClock.uptimeMillis() + CRASH_BUDGET_RESET_DELAY_MS
                // 让 token→cookie 的结果立刻落盘，App 重启后免 token 直接进
                runCatching { CookieManager.getInstance().flush() }
                // 走到这里才算"界面真的出来了"（判据见 maybeShowFirstRunKeyHint 的注释）
                if (isLoopback(url)) maybeShowFirstRunKeyHint()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return
                pageFailed = true
                val desc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    error?.description?.toString()
                } else {
                    null
                }
                showError(getString(R.string.dsh_webview_error, desc ?: "unknown"))
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame != true) return
                val code = errorResponse?.statusCode ?: 0
                when {
                    code == 401 -> {
                        // cookie 失效：手里有带 token 的 URL 就自动重载一次
                        val tokenUrl = (DshRuntime.state.value as? DshRuntime.State.Running)?.url
                        if (!retriedWithToken && tokenUrl != null) {
                            retriedWithToken = true
                            loadedUrl = tokenUrl
                            pageFailed = false
                            loadUrlChecked(tokenUrl)
                        } else {
                            pageFailed = true
                            showError(getString(R.string.dsh_webview_auth_failed))
                        }
                    }
                    code >= 400 -> {
                        pageFailed = true
                        showError(getString(R.string.dsh_webview_http_error, code))
                    }
                }
            }

            /**
             * BL-056（R12 唯一 🔴）：渲染进程 gone 的接管。
             * 不覆写时系统会把**整个 App 进程**杀掉来"清理"崩溃的渲染进程（API 26+ 的既定行为）。
             * 这里返回 true 表示"我来处理"：WebView 对象已经失效，标准做法是销毁它并按原布局位
             * 重建一个新的（[rebuildWebViewAfterCrash] —— 重建失败退化为错误面板；两种情况的
             * 界面都要能靠「重试」再走一轮，见 [onRetryClicked]）。
             *
             * 返回 true 的另一个意思是 CPU/内存两类 gone 都不由系统回收 —— 页面重载就是回收。
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // detail.didCrash() 需要 API 26（minSdk 26），非崩溃的 OOM 回收也走这里
                val crashed = detail?.didCrash() == true
                if (webViewDead) {
                    // 当前对象已销毁（onDestroy 收尾 / 上一次崩溃放弃重建）：不重建，
                    // 只吞掉事件。缺这条时：onDestroy 里 destroy() 同步触发回调、
                    // isFinishing 尚为假的系统销毁路径，会当场再建一个新 WebView —— 泄漏。
                    logWeb("[web] render process gone after destroy (crashed=$crashed), ignored")
                    return true
                }
                if (view != null && view !== webView) {
                    // 迟到回调：属于已被替换/销毁的旧 WebView 对象。当前 webView 是好的，
                    // 不能因它就销毁重建 —— 只吞掉事件（不返还系统=不连坐杀 App）。
                    logWeb("[web] render process gone for stale webview (crashed=$crashed), ignored")
                    return true
                }
                if (isFinishing || isDestroyed) {
                    // 本页正在收尾（外跳/停止/返回链）：不需要重建，只吞掉事件防系统连坐杀 App
                    logWeb("[web] render process gone while finishing (crashed=$crashed), skip rebuild")
                    return true
                }
                logWeb("[web] render process gone (crashed=$crashed), rebuilding webview")
                rebuildWebViewAfterCrash()
                return true
            }
        }
    }

    /**
     * 首次成功加载界面时提示一次"去哪里填 API Key"。
     *
     * ## 判据：什么算"首次成功加载"
     * 取 `onPageFinished` 且主 frame 没有失败（`pageFailed == false`）+ 地址是回环。
     * - **不能**用 `onPageStarted`：它只表示"开始了"，页面可能立刻 401/连接被拒，
     *   这时弹"去填 Key"是错的 —— 用户看到的是一张错误面板，对话框还盖在上面。
     * - **不能**只用 `onPageFinished`：WebView 对**失败页面**同样回调它（本文件上面就靠
     *   `pageFailed` 专门挡这一点），照它弹会让"加载失败也弹一次"。
     * - **不能**用进度到 100：进度是估算值，`onProgressChanged(100)` 早于 onPageFinished
     *   出现，恰好在失败页上也会到 100。
     * 换句话说，这里的判据与"界面真的显示出来了"是同一个条件：`showWeb()` 刚被调用。
     *
     * ## 只弹一次
     * flag 落在 SharedPreferences 的 "launcher" 里，按**实例**记：每个实例有自己的
     * $DSH_HOME（= <实例目录>/home），Key 也存在那里，所以新实例确实是一张白纸，
     * 该再讲一次；而"同一个实例反复进出 WebView"才是要避免的骚扰。
     * 拿不到实例 id 时退回一个全局 key，宁可不弹第二次也不重复骚扰。
     * 先写标记再弹：同一次加载里 onPageFinished 可能被调用多次（重定向、重试），
     * 不先写会叠出两个对话框。
     */
    private fun maybeShowFirstRunKeyHint() {
        val key = KEY_FIRST_RUN_HINT + (DshRuntime.runningInstanceId() ?: "global")
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        FCLAlertDialog.Builder(this)
            .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
            .setTitle(getString(R.string.dsh_key_first_run_title))
            .setMessage(getString(R.string.dsh_key_first_run_message))
            .setPositiveButton(getString(R.string.dialog_positive), null)
            .create()
            .show()
    }

    /**
     * R16/fix4（BL-140）：老 WebView 上 SPA 崩坏时补弹一次"升级 WebView"指引。
     *
     * 背景（V6 结论）：[loadUrlChecked] 的一次性放行（[compatOk]）意味着用户在对话框里选过
     * "仍要继续"后，之后所有加载（状态回放/新 token URL/崩溃重建/401 重载）直连 loadUrl
     * 不再弹框 —— 而 114 这类老 WebView 上 dsh 0.2.x 的 SPA **必崩**（`Promise.withResolvers`
     * 需 119+ 等，bebug (2) 实锤），用户于是只剩白屏/连不上，既不知情也无出口。
     *
     * 本函数在 `onConsoleMessage` 里被调用：仅当 **当前 WebView 版本不可信**
     * （[DshWebViewCompat.parseWebViewMajor] 读不出，或读出的值低于
     * [DshWebViewCompat.MIN_WEBVIEW_MAJOR]）+ 消息命中崩溃判据（[isOldWebViewSpaCrash]）+
     * **本次加载尚未弹过**（[crashHintShownForThisLoad]）时，
     * 弹 [DshWebViewCompat.showCrashUpgradeHint]（带升级指引 + 复制链接 / 外部浏览器出口）。
     *
     * 为什么前提条件是"WebView 过旧"：`is not a function` 在**新** WebView 上也可能因 dsh
     * 前端 bug 出现，那种情况给"升级 WebView"是错误指引（用户已经最新）；过旧前提把误报面
     * 收窄到"本来就在兼容告警名单里"的设备。
     *
     * 弹框是 UI 操作：`onConsoleMessage` 在 WebView 内部线程的 UI 回调（主线程）上执行，
     * 直接调安全（项目内弹框惯例同 [maybeShowFirstRunKeyHint]）。
     */
    private fun maybeShowCrashUpgradeHint(consoleMessage: String) {
        if (crashHintShownForThisLoad) return
        if (!isOldWebViewSpaCrash(consoleMessage)) return
        val versionName = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
        // BL-185：这里不能用 `webViewMajorBelow`（它对"读不出"返回 false，是给"未知≠低于"的
        // 旧契约留的）。崩溃提示的前提恰恰是"版本不可信"：过旧**或**读不出都要给升级指引 ——
        // 用户当初可能正是在 unknown 档里选了"仍要继续"，页面随后崩了，这时候没有出口最糟。
        val major = DshWebViewCompat.parseWebViewMajor(versionName)
        if (major != null && major >= DshWebViewCompat.MIN_WEBVIEW_MAJOR) return
        crashHintShownForThisLoad = true
        // BL-187：外跳/复制都必须落在"本次加载所属实例"上 —— 记下此刻的运行实例，
        // 点击时刻再由 openExternalFresh/copyableUrlFor 复核（跨实例切换后不复用别人的 URL）。
        val ownerId = DshRuntime.runningInstanceId()
        // 兜底快照按 owner 归属取（BL-187）：state 里是别人的 URL 时宁可没有快照，也不拿它当回落。
        val stateRunning = DshRuntime.state.value as? DshRuntime.State.Running
        val snapshot = stateRunning?.url?.takeIf { ownerId != null && stateRunning.instanceId == ownerId }
            ?: (if (webViewDead) null else runCatching { webView.url }.getOrNull())
        // R17：本提示的典型现场恰是"用户开着日志覆盖层排查崩坏原因"——先收日志层再弹，
        // 与 loadUrlChecked 的 onDialogWillShow 同款（两处出口都要；用户可能从任一路径触发）。
        // BL-182/BL-195/BL-198：外跳回调必填；复制回调同样**恒传**（按钮恒挂，拿不到 URL 时
        // 由 copyAccessLinkConfirmed 自己 toast），与 loadUrlChecked 的门禁框保持同一口径。
        DshWebViewCompat.showCrashUpgradeHint(
            this,
            onExternal = { openExternalFresh(snapshot ?: "", ownerId) },
            onCopyLink = { copyAccessLinkConfirmed(ownerId, resumeGate = false) },
            onDialogWillShow = {
                // BL-201：崩溃提示与门禁框共用同一套"屏上可决策窗口"口径 —— 自增序号
                //（复制收尾据此判断"期间是否已有新出口"），并先收掉复制确认框再 show。
                decidableDialogSeq++
                dismissCopyDialog()
                closeLogOverlay()
            }
        )
    }

    /**
     * 打开同页日志覆盖层（BL-025 修复本体）。
     *
     * 内容为**当前运行实例**的日志（`DshLogBus.snapshotFor(id)` 只读订阅）；
     * 拿不到实例 id 时（实例已停但 WebView 还在）回落到全局流 ——
     * 此时"这个实例的日志"没有指向，但"看出什么事了"的意图还在，
     * App 级时间线是唯一说得通的落点（与 [logWeb] 的回落同理）。
     *
     * 只读：覆盖层不提供清空/复制 —— 要改日志去外壳（"去外壳看完整日志"按钮）。
     * "首开即订阅、关闭即取消"：覆盖层关着时不收流、不重建文本。
     */
    private fun openLogOverlay() {
        val id = DshRuntime.runningInstanceId()
        logOverlayInstanceId = id
        logOverlayLastRevision = -1L
        logOverlayLastTrimmed = 0 // 新一次打开：裁剪基线归零（上一轮的增量不作数）
        findViewById<FCLTextView>(R.id.log_overlay_scope).text = overlayScopeLabel(id)
        findViewById<View>(R.id.log_overlay).visibility = View.VISIBLE

        // 先画一帧当前快照，避免"打开后半秒内是空的"（sample 会吞掉首帧之前的发出）
        renderLogOverlay(
            if (id == null) DshLogBus.snapshot.value else DshLogBus.snapshotFor(id).value,
            forceScroll = true
        )

        logOverlayJob?.cancel()
        val source = if (id == null) DshLogBus.snapshot else DshLogBus.snapshotFor(id)
        logOverlayJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 节流：总线 200ms flush 一次，覆盖层 500ms 取最新一帧即可
                source.sample(LOG_OVERLAY_SAMPLE_MS).collect { snap ->
                    // 覆盖层已关（例如"去外壳看完整日志"先关层再跳）：
                    // repeatOnLifecycle 在 STOPPED 前不会停，靠显隐再挡一次
                    if (findViewById<View>(R.id.log_overlay).visibility != View.VISIBLE) return@collect
                    if (snap.revision == logOverlayLastRevision) return@collect
                    renderLogOverlay(snap)
                }
            }
        }
    }

    /** 关闭覆盖层并取消订阅（幂等）。 */
    private fun closeLogOverlay() {
        findViewById<View>(R.id.log_overlay).visibility = View.GONE
        // BL-115：摘掉可能挂着的一次性滚动调整监听（关层后 pin/补偿都不该再动手），
        // 与 DshLogsUI.removePinListener 同理：常驻监听会在下一次打开时乱动手。
        removeLogOverlayAdjustListener()
        logOverlayJob?.cancel()
        logOverlayJob = null
    }

    private fun overlayScopeLabel(instanceId: String?): String = if (instanceId == null) {
        getString(R.string.dsh_logs_scope_app)
    } else {
        val name = DshInstances.byId(instanceId)?.name ?: instanceId
        getString(R.string.dsh_logs_scope_instance, name)
    }

    /**
     * 覆盖层渲染：取末尾 [LOG_OVERLAY_MAX_LINES] 行拼成纯文本。
     *
     * 不做日志页那套级别着色（逐行正则 + span）：覆盖层只是"扫一眼出错原因"，
     * 纯文本一次 `setText` 的开销与 2Hz 节流刷新也匹配得上。
     *
     * 滚动策略照抄 `DshLogsUI.render` 的简化版：**只在贴底时跟随**。
     * `atBottom` 必须在 `setText` **之前**取 —— 内容从顶部裁掉后旧内容整体上移，
     * 赋值之后再问会把"翻到旧内容底部"误判成"贴底"，于是每来一行都把用户往下拽。
     * （完整论证见 `DshLogsUI.render` 的注释。）
     *
     * BL-115 裁剪位移补偿：`takeLast(500)` 从顶部裁 k 行、尾部又加若干行时，scrollY
     * 不变会让剩余内容整体上移 k×行高（就是"读历史时内容自己往上跑"）。用户不在底部时，
     * 在布局落定后 `scrollBy(0, -(裁掉增量 × 行高))` 把视口锚回同一批内容。行高取不到
     * （≤0）则跳过 —— 宁可不补也不要乱补。
     */
    private fun renderLogOverlay(
        snap: DshLogBus.Snapshot,
        forceScroll: Boolean = false
    ) {
        logOverlayLastRevision = snap.revision
        val scroll = findViewById<ScrollView>(R.id.log_overlay_scroll)
        val text = findViewById<FCLTextView>(R.id.log_overlay_text)
        val atBottom = !scroll.canScrollVertically(1)

        val lines = snap.lines
        val trimmed = (lines.size - LOG_OVERLAY_MAX_LINES).coerceAtLeast(0)
        val shown = if (trimmed > 0) lines.takeLast(LOG_OVERLAY_MAX_LINES) else lines
        // 与上一帧相比新裁掉的行数：这次渲染让"相同内容"在裁剪后上移了这么多行
        val trimDelta = trimDeltaFor(lines.size, LOG_OVERLAY_MAX_LINES, logOverlayLastTrimmed)
        logOverlayLastTrimmed = trimmed
        text.text = shown.joinToString("\n")
        findViewById<FCLTextView>(R.id.log_overlay_info).text =
            getString(R.string.dsh_logs_info, shown.size, lines.size)

        if (forceScroll || atBottom) {
            pinLogOverlayToBottom(scroll, text)
        } else if (trimDelta > 0) {
            compensateLogOverlayTrim(scroll, text, trimDelta)
        }
    }

    /**
     * BL-115 裁剪位移补偿本体：布局落定后把视口按新裁掉的行数拉回。
     *
     * 补偿量 `delta × 行高` 是近似值（正文是 monospace 单字号 + lineSpacingExtra，每行等高）。
     * **行高不用 TextView.getLineHeight()（API 29+，minSdk 26 上会 NoSuchMethodError）**，
     * 用 Paint 字体度量拼：`(descent - ascent) × lineSpacingMultiplier + lineSpacingExtra`
     * （与 TextView 无 Layout 时的内部算法同构，均为 API 16 起的稳定接口）。
     * 度量异常（负高/零高）就跳过本次补偿 —— 宁可漏补一帧，不冒错位风险。
     */
    private fun compensateLogOverlayTrim(scroll: ScrollView, text: FCLTextView, delta: Int) {
        val fm = text.paint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * text.lineSpacingMultiplier + text.lineSpacingExtra
        if (lineHeight <= 0f) return
        val shift = delta * lineHeight
        if (shift <= 0f) return
        // 与 pin 共用"预绘制前一次性执行"的机制；同一时刻最多挂一个（先摘后挂，BL-115 去重）
        removeLogOverlayAdjustListener()
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                runCatching { scroll.viewTreeObserver.removeOnPreDrawListener(this) }
                logOverlayAdjustListener = null
                // 负方向 = 内容往上滚了多少，就把视口往下拉回多少（clamp 交给 ScrollView）
                scroll.scrollBy(0, -shift.toInt())
                return true
            }
        }
        logOverlayAdjustListener = listener
        scroll.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /** 摘掉挂着的覆盖层滚动调整监听（幂等；viewTreeObserver 在 attach 期间会被替换，现取现摘） */
    private fun removeLogOverlayAdjustListener() {
        val listener = logOverlayAdjustListener ?: return
        logOverlayAdjustListener = null
        runCatching {
            findViewById<ScrollView>(R.id.log_overlay_scroll)
                .viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }

    /**
     * 钉底：等这一帧测量/布局落定后再钉（照抄 `DshLogsUI.pinToBottomAfterLayout`）。
     *
     * 不能直接 `post { fullScroll }`：`post` 与"新文本测量完成"之间没有先后关系，
     * 赶在测量之前执行会按旧高度算出 scrollY、随后被布局 clamp —— 先跳一下再落位。
     * 挂在 `OnPreDrawListener` 上（测量→布局→预绘制→绘制），一次到位。
     * 一次性：回调里先注销自己，否则用户拖拽的每一帧都会被拉回底部。
     * BL-115：挂之前先摘旧监听（remove→add 去重），避免同帧挂多个把位置钉多次。
     */
    private fun pinLogOverlayToBottom(scroll: ScrollView, text: FCLTextView) {
        removeLogOverlayAdjustListener()
        val observer = scroll.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                runCatching { scroll.viewTreeObserver.removeOnPreDrawListener(this) }
                logOverlayAdjustListener = null
                // 直接 scrollTo 而非 fullScroll：后者可能启动 250ms 平滑滚动，
                // "贴底跟随"要的是一次到位的静态位置；值给大了 ScrollView 内部会夹紧
                scroll.scrollTo(0, text.bottom)
                return true
            }
        }
        logOverlayAdjustListener = listener
        observer.addOnPreDrawListener(listener)
    }

    /**
     * WebView 已判死（崩溃重建失败/放弃自动重建）时的统一面板（幂等）。
     *
     * 与 [showError] 不同：不调 [logWeb] —— 状态流每次回放都会走到 render 的死面板分支，
     * 记日志会把同一条文案重复刷进总线。
     */
    private fun showDeadPanel() {
        findViewById<View>(R.id.state_panel).visibility = View.VISIBLE
        findViewById<View>(R.id.state_progress).visibility = View.GONE
        findViewById<FCLTextView>(R.id.state_text).text =
            getString(R.string.dsh_webview_render_gone_repeat)
        findViewById<View>(R.id.btn_retry).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_logs).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_stop).visibility = View.VISIBLE
    }

    /** 根据运行时状态决定界面：加载 / 等待 / 失败 */
    private fun render(st: DshRuntime.State) {
        if (webViewDead) {
            // BL-056：WebView 已判死（渲染进程重建失败/放弃自动重建）——任何状态都保持
            // "重建失败"面板 + 「重试」（重试里会先补重建），不碰已销毁对象。
            // 放顶层而不是 Running 分支内：否则 Starting 会让 showLoading 把面板换成转圈、
            // 隐藏重试按钮，用户就再也点不到重建入口了。
            showDeadPanel()
            return
        }
        when (st) {
            is DshRuntime.State.Running -> {
                val url = st.url
                when {
                    url != null && loadedUrl != url -> {
                        // 新 URL = 旧失败已作废：照旧收面板开新页（onPageStarted 亦会清 pageFailed）
                        loadedUrl = url
                        showWeb()
                        loadUrlChecked(url)
                    }
                    url != null && webView.url == null -> {
                        // WebView 尚未加载（例如从后台恢复）。经同一 loadUrlChecked：
                        // compatOk 已置时直连 load，不再重弹兼容对话框（BL-111 重入守卫）。
                        // BL-060 同源（V3 修复方向 5）：失败态下先不 showWeb，等本行触发的加载
                        // 真正开始（onPageStarted 清 pageFailed）再收面板，避免闪一下空白。
                        if (!pageFailed) showWeb()
                        loadUrlChecked(url)
                    }
                    url == null -> loadBestUrl(st)
                    else -> {
                        // BL-060：状态回放（每次 onStart 重收集）/REORDER 复用重进这里时，
                        // 若页面处于加载失败态（pageFailed），保持错误面板，不用 showWeb 盖掉。
                        if (!pageFailed) showWeb()
                    }
                }
            }
            is DshRuntime.State.Starting -> showLoading(getString(R.string.dsh_webview_starting))
            is DshRuntime.State.Stopping -> showLoading(getString(R.string.dsh_webview_stopping))
            is DshRuntime.State.Idle -> showError(getString(R.string.dsh_webview_stopped))
            is DshRuntime.State.Failed -> showError(st.reason)
            is DshRuntime.State.Exited -> showError(getString(R.string.dsh_runtime_exited, st.code))
        }
    }

    /** 有 token 用 token，没有则退回根路径（cookie 模式） */
    private fun loadBestUrl(st: DshRuntime.State) {
        val running = st as? DshRuntime.State.Running ?: return
        // ★ 端口还是 0（dsh 尚未打印真实端口）时不要拼出 http://127.0.0.1:0/ 去加载：
        // 那样只会得到一个"页面加载失败"，看起来像 dsh 坏了。显示"启动中"更准确。
        val url = running.url ?: if (running.port > 0) "http://127.0.0.1:${running.port}/" else null
        if (url == null) {
            showLoading(getString(R.string.dsh_webview_starting))
            return
        }
        if (loadedUrl == url) return
        loadedUrl = url
        if (running.url == null) {
            // 没拿到 token：先试 cookie 是否还有效；不行会走 401 兜底提示
            logWeb("[web] 未捕获到 token URL，尝试用已保存的 cookie 访问 $url")
        }
        if (!pageFailed) showWeb()
        loadUrlChecked(url)
    }

    /**
     * 带 WebView 版本门禁的加载（R1b 接线，编排集成）。
     * 版本过旧时弹对话框：用户选"仍要继续"才真正 load，选"外部浏览器"则由 [openExternalFresh] 外跳。
     * `loadedUrl` 标记照旧由调用方先置，对话框只是延迟实际 load。
     *
     * BL-111 重入守卫：本页已做过一次选择（[compatOk]）后直连 `loadUrl` ——
     * 状态回放（每次 onStart 重收集）与新实例重建不会再弹对话框。
     * [compatOk] 只在用户点"仍要继续"时置真；此时即便 [DshWebViewCompat] 的对话框单例
     * 还没收掉（点 continue 的路径里等待 `ensureSupported` 回调返回即 dismiss），也不会再走它。
     */
    private fun loadUrlChecked(url: String) {
        // BL-056：WebView 已判死（崩溃重建失败）时不再对旧对象 loadUrl。
        if (webViewDead) return
        if (compatOk) {
            webView.loadUrl(url)
            return
        }
        // BL-187：本页是"当前运行实例"的界面，外跳/复制都必须校验 instanceId 归属。
        // 在这里取一次归属（与状态回放同刻），后续点击时刻再取 fresh URL 时复核。
        val ownerId = DshRuntime.runningInstanceId()
        DshWebViewCompat.ensureSupported(
            this, url,
            onContinue = {
                compatOk = true
                if (!webViewDead) webView.loadUrl(url)
            },
            // BL-195：外跳回调必填（不再有模块内 ACTION_VIEW 兜底）；BL-187：点击时刻 fresh URL
            // + instanceId 归属校验都收在 openExternalFresh 里。
            onExternal = { openExternalFresh(url, ownerId) },
            // BL-182 + BL-198（W3-A 已改签名语义）：**恒传回调**，按钮由 DshWebViewCompat 恒挂 ——
            // 是否真的拿得到带 token 的 URL 在回调内部现取，拿不到只 toast 说明（不复制裸 URL）。
            // 这样三语文案推荐「复制链接」时，按钮一定在框上（BL-198 的判据）。
            onCopyLink = { copyAccessLinkConfirmed(ownerId, resumeGate = true) },
            // BL-186：框被关掉但没做放行选择（返回键已由 setCancelable(false) 挡住；
            // 这里兜"任一支按钮点击后 FCLAlertDialog 无条件 dismiss"这条路径）→ 清 loadedUrl
            // 并立刻重渲染：否则页面会停在"已置位所以只 showWeb"的空白上，且门禁框没了。
            // BL-197：复制流程进行中（copyDialog != null）时**同样**要复位 loadedUrl ——
            //   只把"重摆"推迟到确认框关闭之后（由 resumeGateAfterCopyLink 统一收尾），
            //   复位本身不能跳过；fix9 把这整段推迟掉，才漏出"loadedUrl=null 无人补"的空白页。
            onDismissWithoutChoice = {
                logWeb("[web] compat gate closed without choice, resetting loadedUrl")
                loadedUrl = null
                // BL-199：刚从「稍后」回来 —— 面板已经摆好（showError），不要再把门禁弹回去。
                // 这个标记必须在这里消费掉：之后任何一次真实的状态回放/重试都该重新走门禁。
                if (gateDeferred) {
                    gateDeferred = false
                    logWeb("[web] compat gate: deferred by user, keeping the panel")
                } else if (copyDialog == null) {
                    postRender()
                } else {
                    // 确认框还压在上面：此刻重摆门禁只会两层叠影（BL-201）。
                    // 收尾由 copyDialog 的 dismiss 监听统一做（BL-197：成功/取消同一路径）。
                    logWeb("[web] compat gate re-show deferred to copy-link flow")
                }
            },
            // BL-199（fix10 集成裁定）：用户点「稍后」= 现在不做决定。**这里必须真正收场**，
            // 否则 W3-A 的实现会走上面的 onDismissWithoutChoice → 立刻 postRender 重摆门禁，
            // 用户看到"框闪一下又回来"，按钮等于没作用（W3-B 实测上报）。
            //
            // 收场方式：落到"暂不打开"状态面板 —— 屏上仍有三条出口（重试 / 日志 / 返回），
            // 既不空白（BL-197 的底线），也不逼用户立刻二选一。
            // 用独立标记 gateDeferred 让 onDismissWithoutChoice 跳过这一轮重摆：那个回调
            // 分不清"稍后"与"程序化关闭"，而这两者对用户的语义要求正相反。
            onLater = {
                logWeb("[web] compat gate: user chose to decide later, showing deferred panel")
                gateDeferred = true
                loadedUrl = null
                showError(getString(R.string.dsh_webview_gate_deferred))
            },
            // R17（作者反馈"弹出 webview 过旧提示时应该退出日志界面"）：对话框弹出前先收日志
            // 覆盖层 —— 排查时用户开着日志，对话框压在日志层上两个界面互相抢视线；
            // 先退日志层，让用户直接面对"仍要继续 / 复制链接 / 用外部浏览器打开"的决策。
            // closeLogOverlay() 幂等（没开时是 no-op），版达标的静默放行路径不会走到这里。
            //
            // BL-201：**先收掉复制确认框再 show** —— 确认框开着期间一次状态回放（Home 往返即可）
            // 就会让新门禁框压到确认框上（两张框同屏、各自持有独立的 didCopy 闭包）。
            // 收框是幂等的，且 dismissCopyDialog() 会清引用 ⇒ 确认框的 dismiss 监听据此不再
            // 触发一次"重摆门禁"（避免刚摆好的框被自己收掉）。
            // BL-197：这里同时给"可决策窗口"记一次序号 —— 收尾逻辑据此判断期间是否已有出口。
            onDialogWillShow = {
                decidableDialogSeq++
                dismissCopyDialog()
                closeLogOverlay()
            }
        )
    }

    /**
     * BL-200：收掉复制确认框（幂等，与 [DshWebViewCompat.dismissCompatDialog] 同款纪律）。
     *
     * 顺序与 compat 单例槽一致：**先清引用再 dismiss** —— 确认框的 dismiss 监听以
     * "copyDialog === 自己"判断是不是用户关框，先清引用就能让外部收框走"静默"分支，
     * 不会反过来再排一次重摆。dismiss 失败只记日志（宿主可能已销毁）。
     */
    private fun dismissCopyDialog() {
        val d = copyDialog ?: return
        copyDialog = null
        runCatching { d.dismiss() }
            .onFailure { logWeb("[web] copy dialog dismiss failed: $it") }
    }

    /**
     * BL-182：**本实例当前可复制的带凭据访问 URL**，拿不到返回 null。
     *
     * 判据与设置页 `DshInstanceSettingPage.tokenUrl()` 同款（BL-187 要求的"同一份 state、
     * 两个出口、同一强度"）：
     * - 必须 `Running` 且 `instanceId == 本次加载所属实例`（[ownerId]；null 表示当时没有运行实例，
     *   那就不存在合法的归属）；
     * - URL 里必须**真的带 token**（`UrlScanner.tokenOf != null`）—— 认领孤儿/回落路径的
     *   state.url 可能是裸 URL，复制它等于给用户一条必 401 的死链。
     *
     * 点击时刻现取（不是框创建时刻的快照）：dsh 的 token 是进程级 launchToken，重启即换。
     */
    private fun copyableUrlFor(ownerId: String?): String? {
        if (ownerId == null) return null
        val st = DshRuntime.state.value
        if (st !is DshRuntime.State.Running || st.instanceId != ownerId) return null
        val url = st.url ?: return null
        return url.takeIf { UrlScanner.tokenOf(it) != null }
    }

    /**
     * BL-182：门禁框「复制链接」的实现 —— **点击时刻**现取 fresh URL + 知情确认后再复制。
     *
     * 为什么要再做一次确认：这条链接带着当前会话的访问凭据（拿到的人都能打开 dsh 界面），
     * 与设置页「复制访问链接」的 `confirmCopyUrl()` 是同一口径；用户可能只是误触第三按钮。
     *
     * 拿不到带 token 的 URL（实例已停/已切到别的实例）时**什么都不复制**，只 toast 说明 ——
     * 绝不把裸 URL 放进剪贴板（那会让用户以为链接是对的、是 dsh 坏了）。
     *
     * ## BL-186 与"复制 ≠ 放行"
     * FCLAlertDialog 的任一支按钮点击后都会**无条件 dismiss**（java:93-106），于是点「复制链接」
     * 也会把门禁框关掉。两个后果都要收拾：
     * 1. 门禁框的 dismiss 会触发宿主的 onDismissWithoutChoice（loadedUrl 复位）——复位照做，
     *    只把"重摆门禁"推迟到确认框关闭之后（[copyDialog] 非空期间），否则门禁框会压在确认框底下；
     * 2. 确认框无论复制成功、复制失败还是取消，收尾都**回到可决策现场**（见
     *    [resumeGateAfterCopyLink]）——BL-197：fix9 曾让"复制成功"跳过这一步，
     *    于是 loadedUrl=null 无人补、面板已收、WebView 未加载 ⇒ 整屏无控件的稳态空白。
     *
     * 本函数**不 dismiss 门禁框**：它由 FCLAlertDialog 自己关；这里只负责确认框这一层。
     */
    private fun copyAccessLinkConfirmed(ownerId: String?, resumeGate: Boolean) {
        // BL-201 / R23-E1-06：确认框已经开着就直接忽略。fix9 用单布尔 copyLinkFlowActive，
        // 两张确认框会共用一套收尾状态（后关的那张把前一张的 didCopy 覆盖掉）；
        // 改用实例引用后"是否在流程中"只由 copyDialog 判空决定，重入在入口处就被挡住。
        if (copyDialog != null) {
            logWeb("[web] copy link ignored: confirmation dialog is already showing")
            return
        }
        val url = copyableUrlFor(ownerId)
        if (url == null) {
            logWeb("[web] copy link unavailable: no token url for the current instance")
            Toast.makeText(this, R.string.dsh_webview_compat_copy_unavailable, Toast.LENGTH_LONG).show()
            // 不在这里 render：门禁框的按钮点击随后会 dismiss，onDismissWithoutChoice 会统一复位
            // loadedUrl（此刻 copyDialog 仍为 null ⇒ 走"立即重摆"分支，不会留空白）。
            return
        }
        // BL-197/BL-201：记下"点复制链接那一刻，屏上最后一个可决策窗口"的序号。
        // 确认框开着期间若发生状态回放（Home 往返即可），门禁框会被重新摆出，
        // 序号随之变化 —— 收尾时据此判断"屏上是否已经另有出口"，避免收掉刚摆好的框。
        val gateSeqAtStart = decidableDialogSeq
        // 本次确认框里"是否真的把新链接放进了剪贴板"。只用于日志与 toast 口径；
        // **不参与**收尾决策（BL-197：成功与取消一视同仁回到可决策现场）。
        var didCopy = false
        val dialog = FCLAlertDialog.Builder(this)
            .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
            .setTitle(getString(R.string.dsh_settings_row_copy_url))
            .setMessage(getString(R.string.dsh_copy_url_confirm_message))
            .setPositiveButton(getString(R.string.dsh_action_copy)) {
                val copied = runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("dsh access url", url))
                }
                if (copied.isFailure) {
                    // BL-207：失败时不能复用 open_link_failed（"已自动复制链接，请手动粘贴"）——
                    // 那次复制根本没发生，剪贴板里可能还是上一条旧凭据，那句话会直接把用户
                    // 引去粘贴一个错误的链接。用独立文案，只说"复制失败，请重试"。
                    // ⚠ 资源键 dsh_copy_url_failed 由主代理并入三语 strings.xml（本波不碰该文件）。
                    logWeb("[web] copy link failed: ${copied.exceptionOrNull()}")
                    Toast.makeText(this, R.string.dsh_copy_url_failed, Toast.LENGTH_LONG).show()
                } else {
                    didCopy = true
                    logWeb("[web] copy link: token url copied to clipboard")
                    Toast.makeText(this, R.string.dsh_webview_compat_copied, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.dialog_negative), null)
            .create()
            .apply {
                setOnDismissListener {
                    // BL-200/BL-201：只有"用户关掉的是当前这张框"才收尾。dismissCopyDialog()
                    // 是先清引用再 dismiss（与 DshWebViewCompat 的 wasCurrent 纪律同款），
                    // 外部收框时这里看到 copyDialog 已不是自己 ⇒ 静默跳过，
                    // 不会反过来再排一次重摆（那会把刚摆好的门禁框收掉）。
                    if (copyDialog === this) {
                        copyDialog = null
                        if (resumeGate) resumeGateAfterCopyLink(didCopy, gateSeqAtStart)
                    }
                }
            }
        // BL-200：先存引用再 show —— 不存引用的裸 show() 没有任何收框入口（onDestroy 收不到）。
        copyDialog = dialog
        dialog.show()
    }

    /**
     * BL-186 / BL-197：复制流程收尾 —— 让用户回到"可决策现场"。
     *
     * fix9 在这里写过 `if (copied) return`："复制成功就不重摆，免得三段闪烁"。那是错的：
     * 此刻 `loadedUrl=null`（门禁框的 onDismissWithoutChoice 已经复位）、`state_panel` 已被
     * `showWeb()` 收起、WebView 从未 `loadUrl` ⇒ 屏上没有任何控件，而且不会自愈
     * （下一次状态发射/onStart 回放才会重摆）。三位独立锐评同判（BL-197）。
     *
     * 现在的时序（复制成功与取消**完全一致**）：
     * 1. 确认框已关闭（本函数由它的 dismiss 监听调用）；
     * 2. 先 `loadedUrl = null`（与取消路径同一条复位），再 [postRender] 延迟 ≈180ms 重摆门禁 —
     *    延迟让旧确认框窗口先销毁干净，避免新旧窗口叠影（E1 的 P7：单靠 `decorView.post`
     *    在 detached 时会被无限期推迟，所以延迟调度改用主线程 Handler，并带空白兜底）；
     * 3. 门禁框重摆时经 `onDialogWillShow` 再收一次确认框（幂等），并自增
     *    [decidableDialogSeq] —— 收尾逻辑据此确认"出口真的出来了"。
     *
     * 例外（都不允许留在空白页）：
     * - 复制期间门禁框已被状态回放重新摆出（序号变了）→ 屏上已有出口，不再干预；
     * - `compatOk` 已置 / 宿主正在销毁 / 没有可重载的 URL → 由纯函数
     *   [gateResumeAction] 判定；Abort 时同样延迟 render（错误/加载面板带全出口），
     *   最后统一由 [ensureVisibleExit] 兜"连面板都没有"的极端情况。
     */
    private fun resumeGateAfterCopyLink(copied: Boolean, gateSeqAtStart: Long) {
        // BL-201：确认框开着期间发生过一次状态回放（Home 往返即可），门禁框已被重新摆出。
        // 此刻它就是用户要的可决策窗口，再复位重摆只会"收掉刚出现的框、再弹一个一样的"。
        if (decidableDialogSeq != gateSeqAtStart) {
            logWeb(
                "[web] copy link finished while a fresh gate is already on screen " +
                    "(copied=$copied), no re-show needed"
            )
            return
        }
        val action = gateResumeAction(
            copied = copied,
            compatOk = compatOk,
            finishing = isFinishing || isDestroyed,
            // "还有可加载的现场"：状态仍是 Running 且能拿到 URL（带 token）或至少端口已知。
            // 实例已停/已退出时不该硬摆门禁 —— render 会给出带出口的错误面板。
            canReload = runnableUrlOrNull() != null,
        )
        logWeb("[web] copy link finished (copied=$copied), gate resume action=$action")
        when (action) {
            GateResume.ReloadGate -> {
                loadedUrl = null
                postRender(GATE_RESUME_DELAY_MS)
            }
            GateResume.StayOnPage -> {
                // 防御性分支（compatOk 为真时门禁根本不会弹）：仍兜一次"屏上什么都没有"。
                ensureVisibleExit(gateShown = false)
            }
            // Abort：宿主已销毁（postRender 会自行早退，什么都不碰），或实例已停/退出 ——
            // 后者走同一条延迟 render：render 的 Idle/Failed/Exited 分支会摆出带出口的面板，
            // 末尾的 ensureVisibleExit 再兜一次"连面板都没有"的极端情况。
            GateResume.Abort -> {
                loadedUrl = null
                postRender()
            }
        }
    }

    /** BL-197：当前状态下"能重新过门禁并加载"的 URL；拿不到返回 null。 */
    private fun runnableUrlOrNull(): String? {
        val st = DshRuntime.state.value as? DshRuntime.State.Running ?: return null
        return st.url ?: if (st.port > 0) "http://127.0.0.1:${st.port}/" else null
    }

    /**
     * BL-197 兜底：确认屏上**至少有一个可点出口**。
     *
     * 出口只有三种：门禁框/崩溃提示（对话框窗口，[gateShown]）、状态面板（布局里的按钮行）、
     * 已经在加载的页面（[loadedUrl] 非空 ⇒ loadUrl 已发出，页面即将出现）。
     * 三样都没有就是 BL-197 的空白现场 —— 用现成的错误面板把「重试 / 日志 / 停止 / 关闭」
     * 露出来。这是 BL-186 验收句（"框消失、页面空白、无出口"）的最后一道闸：
     * 本页任何路径都不允许以空白收场。
     *
     * @param gateShown 本次重摆期间是否真的弹出过对话框（由调用方比对 [decidableDialogSeq] 得出）
     */
    private fun ensureVisibleExit(gateShown: Boolean) {
        if (isFinishing || isDestroyed) return
        if (gateShown) return
        if (loadedUrl != null) return
        val panelShown = findViewById<View>(R.id.state_panel).visibility == View.VISIBLE
        if (panelShown) return
        // 页面已经在 WebView 里（有 url）就不是空白 —— 别用错误面板把活页面盖掉。
        if (!webViewDead && runCatching { webView.url }.getOrNull() != null) return
        logWeb("[web] no gate / panel / page after gate resume; falling back to error panel")
        // 不用 dsh_webview_stopped：那句话断言"dsh 未在运行"，而走到这里时实例可能好好跑着，
        // 只是门禁没能摆回来（BL-206 的教训：错误归因比没有文案更糟）。
        // ⚠ 资源键 dsh_webview_no_exit 由主代理并入三语 strings.xml。
        showError(getString(R.string.dsh_webview_no_exit))
    }

    /**
     * BL-186：把一次 render 排到当前事件之后。
     *
     * 为什么必须 post：清 loadedUrl / 重摆门禁的调用点都在 **FCLAlertDialog 的 dismiss 回调**里，
     * 此刻对话框还在 show/dismiss 的收尾中；同步再 `dialog.show()`（render → loadUrlChecked →
     * 门禁框）会把新框压在正在消失的旧框窗口上，部分 ROM 上表现为新框被一起带走（黑屏）。
     * post 一拍让旧窗口先销毁干净。
     *
     * BL-197：可带延迟（复制流程收尾用 ≈180ms —— 确认框窗口的退出动画通常在这一档内结束）。
     * **调度器固定用主线程 Handler 而不是 `decorView.post`**：E1 的 P7 指出 `View.post` 在
     * decorView 已 detach 时会把任务挂到 attach 队列，无限期不执行。Handler 不依赖 attach；
     * 但宿主已 STOPPED（用户刚按 Home）时**干脆不排任务**：那会儿屏幕上没人看，
     * 恢复交给用户回来时 onStart 的那次状态回放（`loadedUrl` 已复位 ⇒ 普通 render 即重过门禁）。
     *
     * 执行末尾无条件 [ensureVisibleExit]：这条链路唯一不可接受的结局是"什么都不在屏上"。
     * [resumeGateToken] 让"更晚发起的一次复位"作废在途的旧任务（连续两次复制收尾不乱序）。
     */
    private fun postRender(delayMs: Long = 0L) {
        if (isFinishing || isDestroyed) return
        // BL-197：宿主不在前台时不排任务。两种调度器在后台都有毛病（View.post 会无限期挂起，
        // Handler 会在后台 show 对话框）；而**根本不需要**在这里恢复 —— 用户回来时
        // repeatOnLifecycle(STARTED) 会重放 state，`loadedUrl` 已被复位成 null，
        // 那条普通的 render 分支自然会重新过门禁。屏幕上此刻也没人看，不存在"空白页"。
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            logWeb("[web] gate resume deferred to the next onStart replay (host not started)")
            return
        }
        val token = ++resumeGateToken
        val task = Runnable {
            if (token != resumeGateToken) return@Runnable
            if (isFinishing || isDestroyed) return@Runnable
            // 门禁框 / 崩溃提示框是对话框窗口，不是布局里的控件 —— 它们"已在屏上"这件事
            // 只能由 onDialogWillShow 自增的序号来确认（[ensureVisibleExit] 看不到它）。
            val seqBefore = decidableDialogSeq
            if (!webViewDead) render(DshRuntime.state.value)
            ensureVisibleExit(gateShown = decidableDialogSeq != seqBefore)
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        if (delayMs <= 0L) handler.post(task) else handler.postDelayed(task, delayMs)
    }

    /**
     * BL-129 / BL-130 / BL-057 / BL-183 / BL-187：版本门禁与崩溃提示里选"外部浏览器"的收尾路径。
     *
     * 1. **fresh URL**：外跳用**点击时刻**的最新 URL（重新查 `DshRuntime.state`），而不是
     *    对话框创建时刻的快照 —— dsh 的 token 是进程级 launchToken，重启即换；旧框旧 token
     *    外跳到浏览器首访又无 cookie，正是"外跳后 authentication required"（BL-129）的主链。
     * 2. **BL-187 归属校验**：fresh 状态还必须是**本次加载所属实例**（[ownerId]）的 —— 跨实例
     *    切换后 `state` 仍是 Running，但那是另一个实例的 URL（仍带 token，token 校验不会报警）。
     *    不匹配时回落 [snapshotUrl] 并记英文日志（与设置页 `tokenUrl()` 的校验对齐）。
     * 3. **ACTION_VIEW 失败**（无浏览器可跳）→ 复制链接 + toast（BL-065 惯例），别静默失败。
     * 4. **BL-183 成功留痕**：成功分支补一条英文日志，只记 URL 骨架（scheme://host:port + 有无
     *    token），**绝不记 token 值**。manifest 无 `<queries>` + targetSdk=34 ⇒ `resolveActivity`
     *    恒 null，"目标包名"记录不了，日志里会注明这一限制。
     * 5. **收尾**：跳完"壳前移" + `finish()` 回首页 —— 本页 WebView 从未加载（版本门禁挡的），
     *    没有缓存价值；留着它回来只会又落"空白 + 重弹对话框"的单程票（BL-057）。
     *
     * 注意本场景（版本门禁负选）**不保活本页**是刻意的：`finish()` 的例外与「停止」按钮同款，
     * 属"放弃缓存"语义；SPA 已就绪后点站外链接的外跳（`shouldOverrideUrlLoading`）不 finish，
     * 那是"返回直接 resume"的另一档。
     *
     * @param snapshotUrl 对话框弹出时刻的 URL（fresh 拿不到/归属不符时的回落）
     * @param ownerId 本次加载所属实例 id（[DshRuntime.runningInstanceId] 的当时取值；可能为 null）
     */
    private fun openExternalFresh(snapshotUrl: String, ownerId: String?) {
        val running = DshRuntime.state.value as? DshRuntime.State.Running
        val fresh = running?.url
        // BL-187：Running 的必须是本次加载所属实例。ownerId 为 null（弹框时就没有运行实例）或
        // 实例已切换，都算归属不符 —— 此时 fresh 可能是**别人**的 URL（仍带 token，token 校验不会报警）。
        val belongs = running != null && ownerId != null && running.instanceId == ownerId
        if (fresh == null) {
            logWeb("[web] external open: runtime not Running, falling back to snapshot url")
        } else if (!belongs) {
            // BL-187：Running 的已经不是本次加载所属实例 —— fresh URL 属于别人（还带别人的 token），
            // 用它外跳会打开另一个实例的界面。回落快照，把归属不符摊在日志里。
            logWeb(
                "[web] external open: fresh url belongs to another instance " +
                    "(owner=${ownerId ?: "none"}, current=${running?.instanceId ?: "none"}), " +
                    "falling back to snapshot url"
            )
        }
        val url = if (fresh != null && belongs) fresh else snapshotUrl
        if (url.isBlank()) {
            // 死路兜底：既没有 fresh URL 也没有可用的快照（门禁框弹出时 state 就已经没有 URL）。
            // 不能拿空串去 ACTION_VIEW（"没浏览器可跳"的假故障会把用户带到复制空串），
            // 也不能让用户停在空白页：把门禁/状态面板重新摆出来。
            logWeb("[web] external open: no url available, aborting")
            Toast.makeText(this, R.string.dsh_webview_compat_copy_unavailable, Toast.LENGTH_LONG).show()
            if (!compatOk) {
                loadedUrl = null
                postRender()
            }
            return
        }
        // ★ R16/fix4（BL-135 第二道防线）：若外跳目标**无 token**（认领孤儿场景在旧 pid 文件
        //   迁移期 / 任何回落裸 URL 的路径），浏览器首访无 cookie 必落 401 原文页
        //   （reports/round16/V1；V0 实验 EXP2 精确复现）。这是"明知会 401 还让用户撞墙"的场景，
        //   给一条明确日志（真机排障能对上号）；不阻断外跳 —— 浏览器里至少能看到 401 的
        //   "reopen the URL"指引，且用户可能已在该端口有 cookie（那样裸 URL 也能进）。
        if (UrlScanner.tokenOf(url) == null) {
            logWeb(
                "[web] external open without token: browser first visit will hit 401 " +
                    "(adopt fallback / bare url); url=$url"
            )
        }
        val ok = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isSuccess
        if (ok) {
            // BL-183：成功分支此前**零日志**，出事后无法回溯"我们到底把什么交给了谁"。
            // 只记骨架：scheme://host:port + 有无 token，token 值绝不落盘（DshLogBus 虽会脱敏
            // `token=` 前缀，但不把凭据交给日志是最稳的口径）。"交给哪个 App"当前记不了 ——
            // manifest 无 <queries> + targetSdk=34 ⇒ resolveActivity 恒 null（E1-C-13），
            // 这条限制写在日志里，免得后来人以为漏了。
            logWeb(
                "[web] external open dispatched: ${urlSkeleton(url)} " +
                    "(target app not resolvable: no <queries> in manifest)"
            )
            // ★ R14-V2B 新问题-1：成功路径**不再**抢前台。原实现三步（ACTION_VIEW →
            //   intentForShell → finish）同帧连发，最后一个 startActivity（外壳 REORDER）
            //   通常赢得前台 —— 浏览器被我们自己的壳盖住，用户看不到"跳出去了"。
            //   现在：浏览器保持前台；本页 finish 后任务自然露出外壳——用户从浏览器返回、
            //   或经最近任务回来，落点都是首页（满足"外跳后回首页"的语义）。
            finish()
        } else {
            logWeb("[web] external open failed, no browser handler?")
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dsh url", url))
            }
            Toast.makeText(this, R.string.open_link_failed, Toast.LENGTH_LONG).show()
            // 无浏览器可跳：不能让用户停在（面板已收的）白页上 —— 显式回壳收尾。
            // REORDER 前移只留在这条兜底路径（此时没有浏览器会被它盖住）。
            startActivity(com.dsh.ui.shell.DshMainActivity.intentForShell(this))
            finish()
        }
    }

    /**
     * BL-058：状态面板「重试」按状态分支。
     *
     * - Running：页面级失败（加载错/401）→ 旧逻辑：重置 `loadedUrl` 强制重载。
     * - Failed / Idle / Exited：dsh 进程侧的失败 → 重载没有意义（没有可加载的 URL），
     *   重试=**重启对应实例**（走与外壳/详情页同一份 [com.dsh.ui.shell.DshLauncher.startInstance]）。
     *   实例不存在（已被删）则 toast 提示返回，不装作能重试。
     * - Starting / Stopping：等状态自己走完，按钮此刻语义不明 → 忽略（防手快连点起双开会话）。
     * 若 WebView 已在崩溃重建中判死（[webViewDead]），任何状态都先补重建。
     */
    private fun onRetryClicked() {
        if (webViewDead) {
            // 崩溃重建失败/放弃后的人工重试：先补重建再照常渲染。
            // 手动重试给满新一轮自动重建预算（用户在场，风暴仍被 MAX_CRASH_REBUILDS 兜住）。
            crashRebuilds = 0
            recreateWebViewOrFail()
            if (!webViewDead) {
                // ★ R14-V2B 证伪-1 同款：新对象必须真正加载（cookie 模式下否则被
                //   loadBestUrl 的 loadedUrl 早退挡掉，"点重试重新加载"永远点不动）。
                loadedUrl = null
                render(DshRuntime.state.value)
            }
            return
        }
        when (val st = DshRuntime.state.value) {
            is DshRuntime.State.Running -> {
                retriedWithToken = false
                loadedUrl = null // 强制重载
                pageFailed = false
                render(st)
            }
            is DshRuntime.State.Failed,
            is DshRuntime.State.Idle,
            is DshRuntime.State.Exited -> {
                val instanceId = when (st) {
                    is DshRuntime.State.Failed -> st.instanceId
                    is DshRuntime.State.Exited -> st.instanceId
                    else -> null // Idle 无归属
                }
                val inst = instanceId?.let { DshInstances.byId(it) }
                if (inst == null) {
                    // Idle（无归属）与"实例已被删"走同一出口：没有可重启的目标，给指路话术。
                    // 文案复用现有的错误面板句（"dsh 未在运行。返回后启动实例。"），不新增 string。
                    Toast.makeText(
                        this,
                        getString(R.string.dsh_webview_stopped),
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                // 重启前清干净页面侧状态：新实例可能拿到**相同**端口/URL
                //（loadedUrl 若不重置，"Running(同 URL)"会命中 render 的 showWeb-only 分支，
                // 旧 WebView 里的死 SPA 会被原样端出来）。pageFailed 一并复位。
                retriedWithToken = false
                loadedUrl = null
                pageFailed = false
                DshLogBus.appendFor(inst.id, "[web] retry: restarting instance ${inst.name}")
                com.dsh.ui.shell.DshLauncher.startInstance(
                    activity = this,
                    inst = inst,
                    scope = lifecycleScope,
                    onOpenLogs = { openLogOverlay() },
                    onPrepareRuntime = { com.dsh.ui.shell.DshLauncher.openSetup(this) },
                    onStarted = {
                        // 留空是有意的：本页就是 WebView，startInstance 成功启动后
                        // DshRuntime 的状态流会自然推到本页，render 自己会刷新。
                    }
                )
            }
            is DshRuntime.State.Starting, is DshRuntime.State.Stopping -> {
                // 状态过渡期，重试语义未定（启动/停止都在进行中），忽略点击
                logWeb("[web] retry ignored while ${st.javaClass.simpleName}")
            }
        }
    }

    /**
     * BL-124：把外壳（[com.dsh.ui.shell.DshMainActivity]）前移到本页之下 ——
     * REORDER 不销毁任何页面：本页留栈保活（重开 resume 而不是冷载 SPA），
     * 外壳带着它原有的临时页栈出到前台。用户在壳里想再回来，入口会自动 REORDER 回来。
     */
    private fun bringShellToFront() {
        startActivity(com.dsh.ui.shell.DshMainActivity.intentForShell(this))
    }

    /**
     * BL-056：渲染进程崩溃后的重建（自 [WebViewClient.onRenderProcessGone] 调用）。
     *
     * 标准处置：旧 WebView 已随渲染进程失效，不能复用（调它任何方法都无意义/可能崩）。
     * 销毁旧对象 → 在原布局位 new 一个新的（库内部会重新拉起渲染进程）→ 重跑
     * [setupWebView] 重新安装各 client → 重新 render 当前状态。
     * 重建失败（[recreateWebViewOrFail] 里 try 段抛出）→ 错误面板 + 「重试」——
     * [webViewDead] 置真后不会再触碰旧对象。
     */
    private fun rebuildWebViewAfterCrash() {
        // 旧对象必须销毁（官方处理样例：removeView → destroy 后重建），先摘再谈重建/放弃。
        destroyWebView()
        // 防风暴：重建 → 重载 → 渲染进程再崩 的循环要有上限（正常崩溃零次或个位数），
        // 到上限后不再自动重建，让用户自己决定要不要点「重试」（重试路径同样先重建）。
        // R14-V2B 附注：预算只在"上次出页后稳定存活满 CRASH_BUDGET_RESET_DELAY_MS"时才清零
        //（延迟清零由 onPageFinished 打的时间戳判定），避免出页即清零的无限重置。
        if (SystemClock.uptimeMillis() >= crashRebuildsResetAt) crashRebuilds = 0
        if (crashRebuilds >= MAX_CRASH_REBUILDS) {
            logWeb("[web] render process gone repeatedly ($crashRebuilds rebuilds), giving up auto-rebuild")
            webViewDead = true // 保持"已死"：重试按钮与 render 据此走重建/死面板路径
            // 专用文案 key 见报告 String 需求表（由集成者写入 strings.xml 三份）
            showDeadPanel()
            return
        }
        crashRebuilds++
        recreateWebViewOrFail()
        if (!webViewDead) {
            // ★ R14-V2B 证伪-1：新 WebView 是**空对象**，必须让 render 真正加载。
            //   token 模式下走 webView.url==null 分支能重载；但 cookie 模式（无 token，dsh
            //   抓不到 token 的常态）走 loadBestUrl，而 loadedUrl 仍是崩溃前那个
            //   http://127.0.0.1:PORT/（渲染进程崩溃不影响 dsh 进程/端口，字符串相等）
            //   → 早退不加载 → 新对象全白、无按钮（状态面板 GONE），死白页。
            //   与重试路径（:758）同款：重建即作废缓存标记。
            loadedUrl = null
            render(DshRuntime.state.value)
        }
    }

    /** 销毁并重建 WebView；重建失败置 [webViewDead] 并显示错误面板（覆盖层仍可看日志）。 */
    private fun recreateWebViewOrFail() {
        val root = webViewContainer
        if (root == null) {
            // 防御性死路（容器在 onCreate 捕获，正常不可达）：按"重建失败"处理
            webViewDead = true
            showError(getString(R.string.dsh_webview_error, "no container"))
            return
        }
        var freshRef: WebView? = null
        try {
            // 新 WebView 插在旧位置（原布局参数）；其余兄弟（进度条/面板/覆盖层）z 序不变
            val fresh = WebView(this)
            freshRef = fresh
            fresh.id = R.id.web_view
            val lp = webViewLayoutParams
            if (lp != null) fresh.layoutParams = lp
            root.addView(fresh, 0)
            webView = fresh
            webViewDead = false
            setupWebView()
        } catch (t: Throwable) {
            // 连 new WebView 都抛（极少：系统 WebView 被卸载/损坏）→ 错误面板兜底。
            // 清理半成品：失败可能发生在 addView 之后，保证"webViewDead=true ⇒ 当前对象
            // 已销毁/已摘除"这个不变式，人工重试不会往树里叠第二个 WebView。
            webViewDead = true
            runCatching {
                freshRef?.let { wv ->
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            showError(getString(R.string.dsh_webview_error, t.message ?: "rebuild failed"))
        }
    }

    /**
     * 销毁当前 [webView] 实例并从布局摘除（幂等；[webViewDead] 时直接返回）。
     * removeView → stopLoading → destroy 的顺序不能换（见原 onDestroy 的原文：直接 destroy 有崩溃风险）。
     * 访问整个包在 runCatching 里：onCreate 极早失败时 lateinit 字段可能未初始化，
     * 而 onDestroy 仍会被系统回调 —— 原实现同样用 runCatching 兜这一点，不能丢。
     */
    private fun destroyWebView() {
        if (webViewDead) return
        webViewDead = true
        runCatching {
            val wv = webView
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
    }

    private fun isLoopback(url: String?): Boolean {
        if (url == null) return false
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    /** 页面加载中/等待运行时：显示状态面板（带转圈），隐藏 WebView */
    private fun showLoading(text: String) {
        if (!webViewDead) webView.visibility = View.GONE
        findViewById<View>(R.id.state_panel).visibility = View.VISIBLE
        findViewById<View>(R.id.state_progress).visibility = View.VISIBLE
        findViewById<FCLTextView>(R.id.state_text).text = text
        findViewById<View>(R.id.btn_retry).visibility = View.GONE
        findViewById<View>(R.id.btn_logs).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_stop).visibility = View.VISIBLE
    }

    /** 已经能加载页面了：收起状态面板 */
    private fun showWeb() {
        findViewById<View>(R.id.state_panel).visibility = View.GONE
        if (!webViewDead) webView.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        if (!webViewDead) webView.visibility = View.GONE
        findViewById<View>(R.id.state_panel).visibility = View.VISIBLE
        findViewById<View>(R.id.state_progress).visibility = View.GONE
        findViewById<FCLTextView>(R.id.state_text).text = message
        findViewById<View>(R.id.btn_retry).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_logs).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_stop).visibility = View.VISIBLE
        logWeb("[web] $message")
    }

    /**
     * WebView 是"当前运行中的实例"的界面，所以 `[web]` 行要归到那个实例自己的日志里 ——
     * 用户在实例详情页看到的 WebView 报错，应该和它的运行日志在同一条流上。
     *
     * 拿不到实例 id 时（[DshRuntime.runningInstanceId] 为 null：还没启动、已停止、或状态已复位）
     * **回落全局流**：这时"这条属于谁"没有答案，猜一个实例等于往别人的日志里塞行。
     * 注意本 Activity 可能是在实例已经停掉之后才收到 401/加载失败回调的，所以 null 是常态而非异常。
     */
    private fun logWeb(line: String) {
        val id = DshRuntime.runningInstanceId()
        if (id == null) DshLogBus.append(line) else DshLogBus.appendFor(id, line)
    }

    override fun onPause() {
        super.onPause()
        runCatching { CookieManager.getInstance().flush() }
    }

    override fun onDestroy() {
        // ── 血泪教训两条（改动本方法前先读完）─────────────────────────────
        // 1. 关闭 WebView 页面【不】停止 dsh 进程，进程由 DshRuntime/前台服务独立管理；
        // 2. removeView → stopLoading → destroy 的顺序不能换（直接 destroy 有崩溃风险），
        //    且崩溃重建失败后字段指向不可用对象 —— 统一走 destroyWebView()（内部判 webViewDead）。
        // ────────────────────────────────────────────────────────────────
        // BL-124：在外壳返回判活（moveTaskToBack）用。放最前：其后任何一步异常都不影响
        // 计数配平（onDestroy 至多一次；-- 后不再有代码能把计数加回）。
        aliveCount--
        unregisterLive(this)
        // BL-173：反注册底板主题回调。ThemeEngine 的表是弱键 View（页面没了会随 GC 消失），
        // 但本页强引用着 logConsoleScroll 直到本方法结束 —— 不注销就等于自己拽着一条指向
        // 自己的回调（闭包捕获 this）。写法参照 DshLogsUI.onDestroy。
        logConsoleScroll?.let { ThemeEngine.getInstance().unregisterEvent(it) }
        logConsoleScroll = null
        logConsoleText = null
        // BL-111：本页销毁时收掉还挂着的兼容对话框，别让它跟着已死窗口泄漏
        //（重建路径先重建后销毁，对话框会由新 WebView 的加载路径重新接管）。
        // BL-200：复制确认框与门禁框分属两个窗口，onDestroy 必须各收一次；
        // fix9 只收 compatDialog 单例槽，确认框（裸 show 无引用）在 btn_stop / destroyKeptPages
        // 两条路径上都会带着活窗口销毁 Activity。两处都幂等、都 runCatching 兜宿已失效。
        resumeGateToken++ // BL-197：作废在途的延迟重摆（宿主正在销毁，任务不必再跑）
        runCatching { dismissCopyDialog() }
        runCatching { DshWebViewCompat.dismissCompatDialog() }
        destroyWebView()
        super.onDestroy()
    }

    companion object {
        /**
         * 任务栈里还活着的本 Activity 实例数（onCreate ++ / onDestroy --）。
         *
         * 供外壳与通知判活（配合 [com.dsh.ui.shell.DshMainActivity.intentForShell] 的
         * REORDER 前移：任务里还有活着的本页时，外壳首页返回应 moveTaskToBack 而不是 finish
         * 露出页面）。只在本类主线程读改，@Volatile 是给别的线程判活用。
         */
        @Volatile
        var aliveCount: Int = 0

        val isAnyAlive: Boolean get() = aliveCount > 0

        /**
         * R14-V2B 新问题-2：保活实例清单（弱引用，onCreate 登记 / onDestroy 移除）。
         * 「再按一次返回退出」需要能主动收掉还活着的页面（否则 WebView 页永远在任务栈里，
         * 外壳 finish 后它会露出，等于没退干净）。
         */
        private val liveInstances: MutableSet<DshWebViewActivity> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap())

        /** 主线程调用（onCreate/onDestroy 均在主线程序列） */
        @JvmStatic
        internal fun registerLive(activity: DshWebViewActivity) {
            liveInstances.add(activity)
        }

        @JvmStatic
        internal fun unregisterLive(activity: DshWebViewActivity) {
            liveInstances.remove(activity)
        }

        /**
         * 收掉所有还活着的 WebView 页（R14-V2B 新问题-2：外壳「再按一次返回退出」用）。
         * 在主线程 post 上逐个 finish（本函数由外壳主线程调用，post 只是防御性对齐线程约束；
         * finish → onDestroy → destroyWebView/aliveCount-- 本就在主线程）。
         */
        fun destroyKeptPages() {
            val snapshot = synchronized(liveInstances) { liveInstances.toList() }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                snapshot.forEach { runCatching { if (!it.isFinishing) it.finish() } }
            }
        }

        /** 同一实例自动重建渲染进程的次数上限（超出改人工点「重试」，防重建-冷载-再崩风暴） */
        private const val MAX_CRASH_REBUILDS = 3

        /** R14-V2B 附注：出页后崩溃预算的**延迟清零**时长（稳定存活满这么久才恢复满预算） */
        private const val CRASH_BUDGET_RESET_DELAY_MS = 60_000L

        /**
         * BL-197：复制流程收尾到重摆门禁之间的延迟。
         *
         * 确认框的窗口退出动画通常在这一档内结束；同步 `show()` 会让新旧两个窗口叠影
         * （旧框还在消失、新框已经出现）。只是"视觉让路"，不是正确性依赖 ——
         * 延迟任务的兜底见 [ensureVisibleExit]。
         */
        private const val GATE_RESUME_DELAY_MS = 180L
    }
}

/**
 * BL-115 纯函数（可单测，见 `com.dsh.W2A2Test`）：
 * 本次渲染相对上次**新增**的顶部裁剪行数（覆盖层滚动位移补偿的增量口径）。
 *
 * 覆盖层窗口 = 快照末尾 [maxShown] 行。当快照总行数 [totalLines] 超过窗口后，每来一行
 * 就从顶部裁一行，窗口内"同一批内容"整体上移；本函数算的是"这一帧比上一帧多裁了几行"，
 * 也就是该回补多少行高的滚动位移。口径约定：
 * - 首次渲染（或覆盖层重开）时 [lastTrimmed] 传 0；
 * - 快照变短（清空/换流）时裁剪量回落，增量按 0 处理（不做反向补偿）；
 * - 调用方随后把新的裁剪量（`max(0, totalLines - maxShown)`）存作下次的 [lastTrimmed]。
 *
 * 写成**顶层函数**（而不是 companion）：本文件的主类继承 AppCompatActivity，
 * 而单测 harness 的 classpath 没有 androidx —— 经 companion 访问会触发整条
 * 父类链加载直接崩；顶层函数落在 `DshWebViewActivityKt` 的静态方法上，无此负担
 * （同 `isRowBusy` 写在 `DshInstanceAdapter.kt` 顶层的理由）。
 */
fun trimDeltaFor(totalLines: Int, maxShown: Int, lastTrimmed: Int): Int =
    ((totalLines - maxShown).coerceAtLeast(0) - lastTrimmed.coerceAtLeast(0))
        .coerceAtLeast(0)

/**
 * BL-197 纯函数（可单测，见 `com.dsh.R23FixTest`）：复制流程收尾时该怎么处置页面。
 *
 * 背景：点门禁框的「复制链接」会先关掉门禁框（FCLAlertDialog 的按钮点击无条件 dismiss），
 * 那一刻 `loadedUrl` 已被 onDismissWithoutChoice 复位为 null、状态面板早被 `showWeb()` 收起。
 * 确认框关闭后必须**显式**决定"接下来屏上放什么"——fix9 的 `if (copied) return` 就是漏了
 * 这一决定，留下整屏无控件的空白（BL-197）。
 *
 * 判据顺序即优先级（任一分支都不允许返回"什么都不做且屏上无控件"）：
 * 1. [finishing]：宿主正在销毁 —— 碰 View 会崩，交给系统的既有关闭流程（[GateResume.Abort]，
 *    调用方另走"不碰 View"的兜底）；
 * 2. [compatOk]：本页早已放行过（理论上不可达，防御性保留）—— 页面已在或即将在，
 *    重摆门禁只会白弹一次（[GateResume.StayOnPage]）；
 * 3. [canReload]：还能拿到可加载的 URL（`Running` 且有 url 或端口）—— 复位 `loadedUrl`
 *    并延迟重摆门禁，让用户回到"继续 / 复制链接 / 外部浏览器"的决策现场
 *    （[GateResume.ReloadGate]）。**复制成功与取消同此一支**；
 * 4. 其余（实例已停/退出、没有 URL）：重摆门禁没有意义，转 [GateResume.Abort]，
 *    由调用方摆出带出口的错误面板。
 *
 * [copied] 只影响日志口径，**不改变**动作 —— 这正是 BL-197 的修复点（fix9 让它决定是否 return）。
 *
 * 写成顶层函数而不是成员：单测 harness 的 classpath 没有 androidx，经 Activity 访问会连带
 * 加载父类链直接崩（同 [trimDeltaFor] 的理由）。
 */
enum class GateResume {
    /** 复位 loadedUrl 并延迟重摆门禁（可决策现场恢复）。 */
    ReloadGate,

    /** 屏上已有可接受的现场（页面已放行/正在加载），不干预。 */
    StayOnPage,

    /** 无法恢复门禁：调用方必须摆出带出口的面板，不允许停在空白页。 */
    Abort,
}

/**
 * BL-197：复制流程收尾的决策函数（签名与语义见 [GateResume]）。
 *
 * @param copied 确认框里是否真的把链接放进了剪贴板（**不影响**动作，只用于日志）
 * @param compatOk 本页是否已经放行过版本门禁
 * @param finishing 宿主是否正在 finish/销毁
 * @param canReload 当前状态是否还能拿到可加载的 URL
 */
@Suppress("UNUSED_PARAMETER") // copied 只进日志口径：刻意不参与判定（BL-197 的修复点）
fun gateResumeAction(
    copied: Boolean,
    compatOk: Boolean,
    finishing: Boolean,
    canReload: Boolean,
): GateResume = when {
    // 顺序即优先级：销毁态先挡（碰 View 会崩），已放行不干预，能重载就回到决策现场，
    // 其余一律交调用方摆出带出口的面板。copied 在这里**不出现**是刻意的。
    finishing -> GateResume.Abort
    compatOk -> GateResume.StayOnPage
    canReload -> GateResume.ReloadGate
    else -> GateResume.Abort
}

