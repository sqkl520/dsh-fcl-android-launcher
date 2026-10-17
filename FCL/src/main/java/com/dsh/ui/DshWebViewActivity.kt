package com.dsh.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // aliveCount 与 onDestroy 的 -- 成对：放在最前段，之后任何路径只要系统回调 onDestroy
        // 都能配平（onCreate 都没走到 onDestroy 的失败里进程已不可用，计数无人再读）。
        aliveCount++
        registerLive(this)
        setContentView(R.layout.activity_dsh_webview)
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
                return if (isLoopback(url)) {
                    false
                } else {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    true
                }
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
     * 本函数在 `onConsoleMessage` 里被调用：仅当 **当前 WebView 确实过旧**
     * （[DshWebViewCompat.webViewMajorBelow]）+ 消息命中崩溃判据
     * （[isOldWebViewSpaCrash]）+ **本次加载尚未弹过**（[crashHintShownForThisLoad]）时，
     * 弹 [DshWebViewCompat.showCrashUpgradeHint]（带升级指引 + 外部浏览器出口）。
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
        if (!DshWebViewCompat.webViewMajorBelow(versionName)) return
        crashHintShownForThisLoad = true
        // 外部浏览器出口：与 ensureSupported 的负选同款 —— 用点击时刻 fresh URL（拿不到时
        // 按 state 现值跳；再拿不到就不显示出口按钮，见 showCrashUpgradeHint 的 onExternal 可空）。
        val fresh = (DshRuntime.state.value as? DshRuntime.State.Running)?.url
        val onExternal = fresh?.let { url -> { openExternalFresh(url) } }
        // R17：本提示的典型现场恰是"用户开着日志覆盖层排查崩坏原因"——先收日志层再弹，
        // 与 loadUrlChecked 的 onDialogWillShow 同款（两处出口都要；用户可能从任一路径触发）。
        DshWebViewCompat.showCrashUpgradeHint(this, onExternal, onDialogWillShow = { closeLogOverlay() })
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
        DshWebViewCompat.ensureSupported(
            this, url,
            onContinue = {
                compatOk = true
                if (!webViewDead) webView.loadUrl(url)
            },
            onExternal = { openExternalFresh(url) },
            // R17（作者反馈"弹出 webview 过旧提示时应该退出日志界面"）：对话框弹出前先收日志
            // 覆盖层 —— 排查时用户开着日志，对话框压在日志层上两个界面互相抢视线；
            // 先退日志层，让用户直接面对"仍要继续 / 用外部浏览器打开"的决策。
            // closeLogOverlay() 幂等（没开时是 no-op），版达标的静默放行路径不会走到这里。
            onDialogWillShow = { closeLogOverlay() }
        )
    }

    /**
     * BL-129 / BL-130 / BL-057：版本门禁里选"外部浏览器"的收尾路径。
     *
     * 1. **fresh URL**：外跳用**点击时刻**的最新 URL（重新查 `DshRuntime.state`），而不是
     *    对话框创建时刻的快照 —— dsh 的 token 是进程级 launchToken，重启即换；旧框旧 token
     *    外跳到浏览器首访又无 cookie，正是"外跳后 authentication required"（BL-129）的主链。
     *    非 Running 时打英文日志（拿不到 fresh token 时按原快照跳，404/401 总好过不跳）。
     * 2. **ACTION_VIEW 失败**（无浏览器可跳）→ 复制链接 + toast（BL-065 惯例），别静默失败。
     * 3. **收尾**：跳完"壳前移" + `finish()` 回首页 —— 本页 WebView 从未加载（版本门禁挡的），
     *    没有缓存价值；留着它回来只会又落"空白 + 重弹对话框"的单程票（BL-057）。
     *
     * 注意本场景（版本门禁负选）**不保活本页**是刻意的：`finish()` 的例外与「停止」按钮同款，
     * 属"放弃缓存"语义；SPA 已就绪后点站外链接的外跳（`shouldOverrideUrlLoading`）不 finish，
     * 那是"返回直接 resume"的另一档。
     */
    private fun openExternalFresh(snapshotUrl: String) {
        val fresh = (DshRuntime.state.value as? DshRuntime.State.Running)?.url
        if (fresh == null) {
            logWeb("[web] external open: runtime not Running, falling back to snapshot url")
        }
        val url = fresh ?: snapshotUrl
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
        // BL-111：本页销毁时收掉还挂着的兼容对话框，别让它跟着已死窗口泄漏
        //（重建路径先重建后销毁，对话框会由新 WebView 的加载路径重新接管）。
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
