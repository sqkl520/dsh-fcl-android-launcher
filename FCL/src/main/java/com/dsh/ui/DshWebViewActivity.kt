package com.dsh.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dsh.core.DshLogBus
import com.dsh.core.DshRuntime
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.view.FCLProgressBar
import com.tungsten.fcllibrary.component.view.FCLTextView
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
            // 跳到"这个实例的日志"。
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
            startActivity(intent)
        }
        findViewById<View>(R.id.btn_stop).setOnClickListener {
            DshRuntime.stop("用户从界面停止")
            finish()
        }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
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
                            webView.loadUrl(tokenUrl)
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

    /** 根据运行时状态决定界面：加载 / 等待 / 失败 */
    private fun render(st: DshRuntime.State) {
        when (st) {
            is DshRuntime.State.Running -> {
                val url = st.url
                when {
                    url != null && loadedUrl != url -> {
                        loadedUrl = url
                        showWeb()
                        webView.loadUrl(url)
                    }
                    url != null && webView.url == null -> {
                        // WebView 尚未加载（例如从后台恢复）
                        showWeb()
                        webView.loadUrl(url)
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
        webView.loadUrl(url)
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
