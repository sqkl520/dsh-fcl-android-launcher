package com.dsh.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.widget.Toast
import com.dsh.core.DshLogBus
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog

/**
 * R1b（BL-026）：WebView 版本兼容检测。
 *
 * 背景：WebView（Chromium）低于 116 时，dsh 前端 JS 全崩 —— `AbortSignal.any` 需 116+、
 * `Promise.withResolvers` 需 119+。本 helper 在加载前检查当前 WebView 包的大版本，
 * 过旧时弹 FCLAlertDialog 给用户"仍要继续 / 用系统浏览器打开"两个选择。
 *
 * ## 编排接线（写在代码里，防接错）
 * 在 [DshWebViewActivity] 真正调用 `webView.loadUrl(...)` 之前（`render()` 的 Running 分支
 * 与 `loadBestUrl()`），把原 `loadUrl(url)` 换成（**onContinue 必须写出实参**，
 * 末尾不要再挂裸尾随 lambda——那会绑到 `onExternal`）：
 * ```
 * DshWebViewCompat.ensureSupported(this, url, onContinue = { webView.loadUrl(url) })
 * ```
 * 阈值沿用默认 116；外部浏览器动作默认由本模块自行 ACTION_VIEW（见 [ensureSupported] 的
 * `onExternal` 参数——传了则由调用方接管外跳）。
 *
 * 对话框为模块级单例（BL-111）：弹新框前先收旧框，杜绝"重订阅回放/多实例"叠影；
 * 宿主 Activity 在 `onDestroy` 时请调 [dismissCompatDialog]（幂等）防窗口泄漏。
 */
object DshWebViewCompat {

    /**
     * dsh 前端能用的最低 WebView（Chromium）大版本。
     * `AbortSignal.any` 需 116+（MDN/caniuse；另见 BL-026：`Promise.withResolvers` 需 119+，
     * 但 116 已是不崩的底线，按任务要求取 116）。
     */
    const val MIN_WEBVIEW_MAJOR = 116

    /** 当前正在显示的兼容对话框（模块级单例，BL-111）；只允许主线程读写。 */
    private var compatDialog: FCLAlertDialog? = null

    /**
     * 收掉当前正在显示的兼容对话框并清引用。幂等：没有框时什么都不做。
     * 宿主 Activity（DshWebViewActivity）应在 `onDestroy` 调用，防窗口泄漏、
     * 也防"返回旧实例时旧框复现"。
     */
    fun dismissCompatDialog() {
        val d = compatDialog ?: return
        compatDialog = null
        DshLogBus.append("[webview-compat] dismissing compat dialog")
        // 宿主 Activity 已销毁时窗口可能已失效：dismiss 失败只记日志，不崩。
        runCatching { d.dismiss() }
            .onFailure { DshLogBus.append("[webview-compat] compat dialog dismiss failed: $it") }
    }

    /**
     * 判一段版本号字符串的大版本是否低于 [threshold]。
     *
     * 接受 `119.0.6045.66` 这类四段式（其实只取第一个 `.` 之前的整数），以及裸大版本如 `"116"`。
     * **解析不出（null/空/首段非数字/负数）一律返回 false——不拦截、直接加载，宁可放过别误伤**
     * （误拦会让用户卡在"本可以用的页面"之外，而误放的代价只是一张可能显示异常的页面）。
     */
    fun webViewMajorBelow(versionName: String?, threshold: Int = MIN_WEBVIEW_MAJOR): Boolean {
        val major = versionName
            ?.trim()
            ?.substringBefore('.')
            ?.takeIf { it.isNotEmpty() }
            ?.toIntOrNull()
            ?: return false
        if (major < 0) return false
        return major < threshold
    }

    /**
     * 加载前守门：当前 WebView >= 阈值 → 直接调 [onContinue]；低于阈值 → 弹 FCLAlertDialog。
     *
     * 对话框模块级单例（BL-111）：弹新框前先 [dismissCompatDialog] 收掉旧框（含被压在下层的），
     * 即使本函数被重订阅回放/多实例高频调用，屏幕上最多一个框。旧框的 continue/外跳回调
     * 因 dismiss 而不会再触发——异步旧 token 的加载随框一起作废，符合预期。
     *
     * @param activity 宿主 Activity（弹框与 getString 用；必须是前台 Activity 上下文）
     * @param url 要加载的 dsh URL（仅在用户选"外部浏览器打开"时作为 ACTION_VIEW 的 data；
     * 选"仍要继续"时由调用方在 [onContinue] 里自己 loadUrl——本函数不碰 WebView）
     * @param onContinue 用户选择继续（或版本本就达标）时的回调：通常就是 `webView.loadUrl(url)`
     * @param onExternal 用户选择"外部浏览器打开"时的回调（由调用方接管外跳，例如改用点击时刻
     * 的 fresh token URL 并收尾导航）。传 null（默认）保持旧行为：本函数自己 ACTION_VIEW(url) 直跳。
     *
     * 版本取自框架 API `WebView.getCurrentWebViewPackage()`（API 26+，minSdk 26 无需守卫；
     * 返回 PackageInfo，取 `versionName`）。取不到（null、多 WebView 实现冲突等）按"达标"处理，
     * 诊断行只进日志不打扰用户（R-LOG：英文）。
     */
    fun ensureSupported(
        activity: Activity,
        url: String,
        onContinue: () -> Unit,
        onExternal: (() -> Unit)? = null,
    ) {
        val versionName = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
        if (!webViewMajorBelow(versionName)) {
            DshLogBus.append("[webview-compat] WebView version ok ($versionName), loading in-app")
            onContinue()
            return
        }
        // BL-111 单例守卫：先收旧框再弹新框（本函数可能被回放/多实例反复调用）。
        // 注意顺序：旧框先 dismiss 再记新引用，否则这里会把自己刚建的框收掉。
        dismissCompatDialog()
        DshLogBus.append("[webview-compat] WebView $versionName below $MIN_WEBVIEW_MAJOR, asking user")
        val dialog = FCLAlertDialog.Builder(activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(activity.getString(R.string.dsh_webview_compat_title))
            .setMessage(activity.getString(R.string.dsh_webview_compat_message, MIN_WEBVIEW_MAJOR))
            .setPositiveButton(activity.getString(R.string.dsh_webview_compat_continue)) {
                DshLogBus.append("[webview-compat] user chose to continue in-app on old WebView")
                compatDialog = null
                onContinue()
            }
            .setNegativeButton(activity.getString(R.string.dsh_webview_compat_external)) {
                DshLogBus.append("[webview-compat] user chose external browser")
                compatDialog = null
                if (onExternal != null) {
                    // BL-129/BL-130：调用方接管外跳（fresh URL + 导航收尾），本函数不再 ACTION_VIEW。
                    DshLogBus.append("[webview-compat] delegating external open to caller")
                    onExternal()
                } else {
                    // 带 token 的 URL 会外传给系统浏览器：这是用户在对话框里主动点的选择，可接受。
                    //（项目内 ACTION_VIEW 惯例：DshWebViewActivity:166 用 runCatching 包、DshAboutUI:81-83
                    // 起手式相同；这里沿用 runCatching，别让"没浏览器可跳"崩掉。）
                    val ok = runCatching {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }.isSuccess
                    if (!ok) {
                        // BL-065：无浏览器可跳时原本静默失败，补一条日志 + Toast（并复制链接兜底）。
                        DshLogBus.append("[webview-compat] external open failed, no browser handler?")
                        runCatching {
                            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("dsh url", url))
                        }
                        Toast.makeText(activity, R.string.open_link_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .create()
        dialog.setOnDismissListener { if (compatDialog === dialog) compatDialog = null }
        compatDialog = dialog
        dialog.show()
    }
}
