package com.dsh.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshRuntime
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dsh_webview)
        webView = findViewById(R.id.web_view)

        setupWebView()

        findViewById<View>(R.id.btn_retry).setOnClickListener {
            retriedWithToken = false
            loadedUrl = null // 强制重载
            render(DshRuntime.state.value)
        }
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
            //   所以这里必须压出**当前运行实例**的详情页并落在「日志」那一段。
            //
            // 拿不到实例 id 时（[DshRuntime.runningInstanceId] 为 null：实例已经停了，
            // 而 WebView 还没被销毁）**回落到设置页**：此时"这个实例的日志"没有指向，
            // 但用户点「日志」的意图是"看出什么事了"，App 级日志是唯一说得通的落点。
            val id = DshRuntime.runningInstanceId()
            val intent = if (id != null) {
                com.dsh.ui.shell.DshMainActivity.intentForInstance(
                    this, id, com.dsh.ui.shell.DshInstanceDetailPage.TAB_LOGS
                )
            } else {
                com.dsh.ui.shell.DshMainActivity.intentForTab(
                    this, com.dsh.ui.shell.DshShellHost.TAB_SETTINGS
                )
            }
            // 先关覆盖层再跳：目标 Intent 带 CLEAR_TOP，WebView 会被销毁；
            // 覆盖层的订阅在 onStop 里本来也会被取消，这里显式关掉只是为了
            // "用户从最近任务切回来时"不残留一个开着的覆盖层（Activity 若被复用）。
            closeLogOverlay()
            startActivity(intent)
        }
        findViewById<View>(R.id.btn_stop).setOnClickListener {
            DshRuntime.stop(getString(R.string.dsh_reason_user_stop))
            finish()
        }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // BL-025：覆盖层开着时返回键先关覆盖层，再走原返回逻辑。
                // FCL 的 LogWindow 也是"先收日志层"（见 GameMenu 对 log_window 的显隐切换）。
                if (findViewById<View>(R.id.log_overlay).visibility == View.VISIBLE) {
                    closeLogOverlay()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
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
                msg?.let { logWeb("[web] ${it.message()} @${it.sourceId()}:${it.lineNumber()}") }
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
                    showWeb()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 失败页面也会回调 onPageFinished；若刚收到过主 frame 错误，保持错误面板，
                // 不要用 showWeb() 把它盖掉（否则用户只看到一片空白，以为 dsh 没起）。
                if (pageFailed) return
                showWeb()
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
        val shown = if (lines.size > LOG_OVERLAY_MAX_LINES) lines.takeLast(LOG_OVERLAY_MAX_LINES) else lines
        text.text = shown.joinToString("\n")
        findViewById<FCLTextView>(R.id.log_overlay_info).text =
            getString(R.string.dsh_logs_info, shown.size, lines.size)

        if (forceScroll || atBottom) pinLogOverlayToBottom(scroll, text)
    }

    /**
     * 钉底：等这一帧测量/布局落定后再钉（照抄 `DshLogsUI.pinToBottomAfterLayout`）。
     *
     * 不能直接 `post { fullScroll }`：`post` 与"新文本测量完成"之间没有先后关系，
     * 赶在测量之前执行会按旧高度算出 scrollY、随后被布局 clamp —— 先跳一下再落位。
     * 挂在 `OnPreDrawListener` 上（测量→布局→预绘制→绘制），一次到位。
     * 一次性：回调里先注销自己，否则用户拖拽的每一帧都会被拉回底部。
     */
    private fun pinLogOverlayToBottom(scroll: ScrollView, text: FCLTextView) {
        val observer = scroll.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                runCatching { scroll.viewTreeObserver.removeOnPreDrawListener(this) }
                // 直接 scrollTo 而非 fullScroll：后者可能启动 250ms 平滑滚动，
                // "贴底跟随"要的是一次到位的静态位置；值给大了 ScrollView 内部会夹紧
                scroll.scrollTo(0, text.bottom)
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
    }

    /** 根据运行时状态决定界面：加载 / 等待 / 失败 */
    private fun render(st: DshRuntime.State) {
        when (st) {
            is DshRuntime.State.Running -> {
                val url = st.url
                when {
                    url != null && loadedUrl != url -> {
                        loadedUrl = url
                        showWeb()
                        loadUrlChecked(url)
                    }
                    url != null && webView.url == null -> {
                        // WebView 尚未加载（例如从后台恢复）
                        showWeb()
                        loadUrlChecked(url)
                    }
                    url == null -> loadBestUrl(st)
                    else -> showWeb()
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
        showWeb()
        loadUrlChecked(url)
    }

    /**
     * 带 WebView 版本门禁的加载（R1b 接线，编排集成）。
     * 版本过旧时弹对话框：用户选"仍要继续"才真正 load，选"外部浏览器"则跳系统浏览器。
     * `loadedUrl` 标记照旧由调用方先置，对话框只是延迟实际 load。
     */
    private fun loadUrlChecked(url: String) {
        DshWebViewCompat.ensureSupported(this, url) { webView.loadUrl(url) }
    }

    private fun isLoopback(url: String?): Boolean {
        if (url == null) return false
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    /** 页面加载中/等待运行时：显示状态面板（带转圈），隐藏 WebView */
    private fun showLoading(text: String) {
        webView.visibility = View.GONE
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
        webView.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        webView.visibility = View.GONE
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
        // 注意：关闭 WebView 页面【不】停止 dsh 进程，进程由 DshRuntime/前台服务独立管理
        runCatching {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
