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
 */
class DshWebViewActivity : FCLActivity() {

    private lateinit var webView: WebView
    private var loadedUrl: String? = null
    private var retriedWithToken = false

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
            startActivity(
                com.dsh.ui.shell.DshMainActivity.intentForTab(
                    this, com.dsh.ui.shell.DshShellHost.TAB_LOGS
                )
            )
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
                val bar = findViewById<android.widget.ProgressBar>(R.id.progress)
                bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                bar.progress = newProgress
            }

            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg?.let { DshLogBus.append("[web] ${it.message()} @${it.sourceId()}:${it.lineNumber()}") }
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
                if (isLoopback(url)) showWeb()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                showWeb()
                // 让 token→cookie 的结果立刻落盘，App 重启后免 token 直接进
                runCatching { CookieManager.getInstance().flush() }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return
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
                            webView.loadUrl(tokenUrl)
                        } else {
                            showError(getString(R.string.dsh_webview_auth_failed))
                        }
                    }
                    code >= 400 -> showError(getString(R.string.dsh_webview_http_error, code))
                }
            }
        }
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
            DshLogBus.append("[web] 未捕获到 token URL，尝试用已保存的 cookie 访问 $url")
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
        findViewById<android.widget.TextView>(R.id.state_text).text = text
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
        findViewById<android.widget.TextView>(R.id.state_text).text = message
        findViewById<View>(R.id.btn_retry).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_logs).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_stop).visibility = View.VISIBLE
        DshLogBus.append("[web] $message")
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
