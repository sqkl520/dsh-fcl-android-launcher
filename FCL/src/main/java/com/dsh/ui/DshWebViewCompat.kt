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
 * 背景：WebView（Chromium）低于阈值时，dsh 前端 JS 全崩 —— `AbortSignal.any` 需 116+、
 * `Promise.withResolvers` 需 **119+**（且后者出现在 dsh 的 boot 内联脚本里，崩在最前面）。
 * 本 helper 在加载前检查当前 WebView 包的大版本，过旧时弹 FCLAlertDialog 给用户
 * "仍要继续 / 用系统浏览器打开"两个选择。
 *
 * ## 阈值 116 → 119（R16/fix4，BL-137）
 * 依据（`reports/round16/V3-webview114-spa.md`）：dsh 0.2.x 的 boot 脚本
 * （`(globalThis.__DSH_BOOT_READY__ ??= Promise.withResolvers()).resolve()`，由
 * `dsh-host-frontend-static` 的 READY_MARKUP 注入）在 114 上必抛 —— 实测日志
 * `Uncaught TypeError: Promise.withResolvers is not a function @…/:47`；同时 plugin 装载
 * 链抛 `AbortSignal.any is not a function`。116~118 的 WebView 同样缺失 withResolvers
 * （119+ 才有），旧阈值 116 会放行这批必然崩坏的设备。
 *
 * ## 编排接线（写在代码里，防接错）
 * 在 [DshWebViewActivity] 真正调用 `webView.loadUrl(...)` 之前（`render()` 的 Running 分支
 * 与 `loadBestUrl()`），把原 `loadUrl(url)` 换成（**onContinue 必须写出实参**，
 * 末尾不要再挂裸尾随 lambda——那会绑到 `onExternal`）：
 * ```
 * DshWebViewCompat.ensureSupported(this, url, onContinue = { webView.loadUrl(url) })
 * ```
 * 阈值见 [MIN_WEBVIEW_MAJOR]；外部浏览器动作默认由本模块自行 ACTION_VIEW（见 [ensureSupported] 的
 * `onExternal` 参数——传了则由调用方接管外跳）。
 *
 * 对话框为模块级单例（BL-111）：弹新框前先收旧框，杜绝"重订阅回放/多实例"叠影；
 * 宿主 Activity 在 `onDestroy` 时请调 [dismissCompatDialog]（幂等）防窗口泄漏。
 * BL-140：宿主在检测到"老 WebView 上 SPA 崩坏"时还可再次主动弹一次（带升级指引），
 * 见 `DshWebViewActivity.maybeShowCrashUpgradeHint` —— 一次性放行（compatOk）不覆盖此路径。
 */
object DshWebViewCompat {

    /**
     * dsh 前端能用的最低 WebView（Chromium）大版本。
     *
     * `Promise.withResolvers` 需 **119+**（MDN/caniuse；dsh 的 boot 内联脚本直接用它 —— 缺失
     * 则 SPA 在装载最前段就崩），`AbortSignal.any` 需 116+。两者取高 = **119**。
     * （R16/fix4 由 116 上调：116~118 设备选"仍要继续"后实测必崩，见类注释。）
     */
    const val MIN_WEBVIEW_MAJOR = 119

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
     * @param onDialogWillShow **仅在确实要弹框时**（版本达标早退路径不触发）于 `show()` 之前调用。
     * R17（作者反馈"弹出 webview 过旧提示时应退出日志界面"）：宿主用它先收掉同页的日志覆盖层 ——
     * 排查时用户开着日志覆盖层，对话框压在日志层上，两个界面互相抢视线（用户两头都够不着）；
     * 先退日志层，让用户直接面对"仍要继续 / 用外部浏览器打开"的决策。传 null 时行为不变。
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
        onDialogWillShow: (() -> Unit)? = null,
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
        // R17：先让宿主收掉日志覆盖层（若开着），再 show —— 见 onDialogWillShow 的注释。
        runCatching { onDialogWillShow?.invoke() }
            .onFailure { DshLogBus.append("[webview-compat] onDialogWillShow threw: $it") }
        dialog.show()
    }

    /**
     * R16/fix4（BL-140）：**崩溃态升级提示** —— 老 WebView 上 SPA 已崩坏时再弹一次，
     * 这一次带"怎么升级"的操作指引（[R.string.dsh_webview_compat_upgrade_hint]，此前是零引用
     * 死资源），并保留"外部浏览器"出口。
     *
     * 为什么需要（V6 结论）：[ensureSupported] 的一次性放行（compatOk）在"用户第二次进入/实例
     * 重启后页面重载"时不会再弹框 —— 若用户此前选了"仍要继续"而页面实际崩坏（114 上必崩），
     * 用户就只剩一个白屏/连不上，既不知情也无出口。本函数由宿主在**检测到崩坏**时调用
     * （`onConsoleMessage` 捕获 `Uncaught ... is not a function` 类错误，见
     * `DshWebViewActivity.onSpaCrashDetected`），宿主自行做"每次加载只弹一次"的去重。
     *
     * 与 [ensureSupported] 的对话框共用同一个单例槽 [compatDialog]（两者不会同屏叠影）。
     *
     * @param onExternal 选"用外部浏览器打开"时的回调（宿主传 `openExternalFresh` 绑定好的
     *                   最新 URL 动作）；null 时不显示该按钮（与 ensureSupported 的惯例一致——
     *                   没出口就不给一个点了没用的按钮）。
     * @param onDialogWillShow **仅在确实要弹框时**于 `show()` 之前调用。
     * R17（作者反馈"弹出 webview 过旧提示时应退出日志界面"）：宿主用它先收掉同页的日志覆盖层。
     * 本函数的典型场景恰是"用户开着日志覆盖层排查崩坏原因"——对话框不加处理会直接压在日志上，
     * 两个界面互相抢视线；先退日志层，让用户干净地面对提示与两个出口。传 null 时行为不变。
     */
    fun showCrashUpgradeHint(
        activity: Activity,
        onExternal: (() -> Unit)? = null,
        onDialogWillShow: (() -> Unit)? = null,
    ) {
        dismissCompatDialog()
        DshLogBus.append("[webview-compat] spa crash detected on old WebView, showing upgrade hint")
        val builder = FCLAlertDialog.Builder(activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(activity.getString(R.string.dsh_webview_compat_title))
            .setMessage(activity.getString(R.string.dsh_webview_compat_upgrade_hint))
            .setPositiveButton(activity.getString(R.string.dialog_positive)) {
                compatDialog = null
            }
        if (onExternal != null) {
            builder.setNegativeButton(activity.getString(R.string.dsh_webview_compat_external)) {
                DshLogBus.append("[webview-compat] user chose external browser (crash hint)")
                compatDialog = null
                onExternal()
            }
        }
        val dialog = builder.create()
        dialog.setOnDismissListener { if (compatDialog === dialog) compatDialog = null }
        compatDialog = dialog
        // R17：同 ensureSupported —— 先收日志覆盖层再 show（用户开着日志排查时不被两个界面夹住）。
        runCatching { onDialogWillShow?.invoke() }
            .onFailure { DshLogBus.append("[webview-compat] onDialogWillShow threw (crash hint): $it") }
        dialog.show()
    }
}

/**
 * R16/fix4（BL-140）纯函数：一条 JS console 消息是否属于"老 WebView 上的 SPA 崩坏"信号。
 *
 * 判据取 **`is not a function`** 子串 —— 实测的三条崩溃消息（bebug (2)）全部命中：
 * - `Uncaught TypeError: Promise.withResolvers is not a function`（boot 内联脚本，:47）
 * - `Uncaught (in promise) TypeError: AbortSignal.any is not a function`（plugin 装载链）
 * - `slot entry crashed in 'settings.section': TypeError: Promise.withResolvers is not a function`
 *
 * 为什么不做更细的白名单（逐 API 名匹配）：dsh 前端依赖的新 API 集合会随版本变化，
 * 白名单会漏（而本条判据的使用场景**已由调用方门禁在老 WebView 上** —— 见
 * `DshWebViewActivity.maybeShowCrashUpgradeHint` 先查 `webViewMajorBelow`；
 * 在"WebView 已过旧"的前提下，任何 `is not a function` 崩溃给"升级 WebView"指引都成立）。
 *
 * 写成顶层函数（而不是 object 内）：单测 harness 的 classpath 没有 androidx —— 顶层落在
 * `DshWebViewCompatKt` 静态方法上，可直接测（同 `trimDeltaFor` 写在 DshWebViewActivity.kt
 * 顶层的理由）。
 */
fun isOldWebViewSpaCrash(consoleMessage: String): Boolean =
    consoleMessage.contains("is not a function")
