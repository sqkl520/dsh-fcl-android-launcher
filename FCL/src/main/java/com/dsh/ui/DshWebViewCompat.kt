package com.dsh.ui

import android.app.Activity
import android.webkit.WebView
import com.dsh.core.DshLogBus
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog

/**
 * R1b（BL-026）：WebView 版本兼容检测。
 *
 * 背景：WebView（Chromium）低于阈值时，dsh 前端 JS 全崩 —— `AbortSignal.any` 需 116+、
 * `Promise.withResolvers` 需 **119+**（且后者出现在 dsh 的 boot 内联脚本里，崩在最前面）。
 * 本 helper 在加载前检查当前 WebView 包的大版本，过旧时弹 FCLAlertDialog 给用户
 * "仍要继续 / 复制链接 / 用外部浏览器打开"三个选择。
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
 * DshWebViewCompat.ensureSupported(
 *     this, url,
 *     onContinue = { webView.loadUrl(url) },
 *     onExternal = { openExternalFresh(url, ownerId) },   // 必填（BL-195）
 *     onCopyLink = if (canCopy) { { copyAccessLinkConfirmed(ownerId) } } else null, // BL-182
 * )
 * ```
 * 阈值见 [MIN_WEBVIEW_MAJOR]。**BL-195**：`onExternal` 不再有默认 null 与内部
 * `ACTION_VIEW(snapshot url)` 兜底 —— 那条兜底会拿**旧快照 URL**（可能是换代前的 token）外跳，
 * 是潜伏的第二出口；现在外跳动作必须由宿主在点击时刻现取 fresh URL 后传入。
 * 宿主传 `onCopyLink` 时，门禁框多一个「复制链接」第三出路（BL-182）。
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
     * BL-185：从 `versionName` 里解析 WebView 的**大版本号**（第一段连续数字）；读不出返回 null。
     *
     * 旧实现取 `substringBefore('.')` 再 `toIntOrNull`，于是任何"首段不是纯数字"的厂商串
     * （E3 实测 8 类：`V114.x` / `chrome119.0` / `android-114.x` / `114abc.x` / `114_0_5_302` /
     * `114,0,5,302` / ZWSP 前缀 / Int 溢出）**一律解析失败并被放行** —— 而误放代码价是白屏 +
     * 无限重连（dsh 0.2.x 的 boot 脚本必抛，见类注释），不是"显示异常"这么轻。
     *
     * 解析规则（刻意宽松，只取数字、不赌分隔符）：
     * - `trim()` 后取**第一个** `[0-9]+` 段：`V114.0.5.302`/`chrome119.0`/`114_0_5_302`/
     *   `114,0,5,302`/`WebView 114.x` 都能拿到正确大版本；
     * - 全角数字（`１１４`）先归一再匹配 —— 部分厂商的版本串混用全角；
     * - ZWSP（U+200B）等不可见字符不是数字，正则自然跳过，`trim` 裁不掉也不影响；
     * - 数字段超出 `Int` 范围（溢出）→ `toIntOrNull()` 返回 null，按"读不出"处理。
     *
     * 注意 `-1.2.3.4` 这类负数会取到第一段数字 `1`（不是负数）——它本来就不是合法版本号，
     * 判成"低于阈值"是保守方向（进旧版门禁框），不会误放。
     *
     * 为什么单测直接钉这个函数（public）：纯逻辑、无 Android 依赖，坑全在字符串形态上，
     * 真机没法枚举。
     */
    fun parseWebViewMajor(versionName: String?): Int? {
        val raw = versionName?.trim() ?: return null
        if (raw.isEmpty()) return null
        // 全角数字归一（'０'..'９' → '0'..'9'）：不归一的话 `１１４` 一个数字都匹配不到。
        val normalized = raw.map { c ->
            if (c in '０'..'９') '0' + (c - '０') else c
        }.joinToString("")
        val digits = FIRST_DIGIT_RUN.find(normalized)?.value ?: return null
        return digits.toIntOrNull()
    }

    /** BL-185：版本串里的"第一段连续数字"。预编译一份，免得每次门禁都重建正则。 */
    private val FIRST_DIGIT_RUN = Regex("[0-9]+")

    /**
     * 判一段版本号字符串的大版本是否低于 [threshold]。
     *
     * **保持旧契约**：解析不出（null/空/无数字段/溢出）一律返回 false —— 不判"低于"。
     * 调用方 `maybeShowCrashUpgradeHint` 已改为直接看 [parseWebViewMajor]（它对"读不出"
     * 也应给升级指引）；保留本函数是为了既有语义与单测：**未知 ≠ 低于**。
     * 真正的门禁（[ensureSupported]）对"未知"有单独的三态分支
     * （弹出 `dsh_webview_compat_unknown_version` 档，推荐复制链接），不依赖本函数。
     *
     * BL-185：解析已从"取第一个 `.` 前整段"改为"取第一段连续数字"（见 [parseWebViewMajor]），
     * 于是 `V114.x` 这类厂商串也会被判"低于"而不是误放行。
     */
    fun webViewMajorBelow(versionName: String?, threshold: Int = MIN_WEBVIEW_MAJOR): Boolean =
        parseWebViewMajor(versionName)?.let { it < threshold } ?: false

    /**
     * 加载前守门：版本达标 → 直接调 [onContinue]；过旧 / 读不出 → 弹 FCLAlertDialog。
     *
     * 三态（BL-185）：
     * - `major >= 阈值`：静默放行；
     * - `major < 阈值`：旧版提示（`dsh_webview_compat_message`）；
     * - `major == null`（厂商非标串/读不到）：**不静默放行** —— 弹同款框，文案换成
     *   `dsh_webview_compat_unknown_version`（含读到的原文），把"复制链接到较新浏览器"
     *   作为第一推荐。依据：未知版本很可能就是老内核，静默放行的代价是白屏 + 无限重连。
     *
     * 对话框模块级单例（BL-111）：弹新框前先 [dismissCompatDialog] 收掉旧框（含被压在下层的），
     * 即使本函数被重订阅回放/多实例高频调用，屏幕上最多一个框。旧框的 continue/外跳回调
     * 因 dismiss 而不会再触发——异步旧 token 的加载随框一起作废，符合预期。
     *
     * 对话框 `setCancelable(false)`（BL-186）：返回键/点外部关掉框会让页面停在空白且无出口
     *（`loadedUrl` 已在调用前置位，状态回放走"只 showWeb 不加载"分支）。宿主另有一道
     * [onDismissWithoutChoice] 兜底（程序化 dismiss 等非用户路径）。
     *
     * @param activity 宿主 Activity（弹框与 getString 用；必须是前台 Activity 上下文）
     * @param url 调用方准备加载的 dsh URL。**本函数不把它交给任何出口**：复制与外跳都由宿主在
     * **点击时刻**现取 fresh URL（BL-182/BL-187），旧的"本函数用快照 url 外跳"兜底已按
     * BL-195 删除。它只用于诊断日志（[urlSkeleton]，只留 scheme://host:port + 有无 token）。
     * @param onContinue 用户选择继续（或版本本就达标）时的回调：通常就是 `webView.loadUrl(url)`
     * @param onExternal **必填**（BL-195）：用户选择"外部浏览器打开"时的回调。宿主传
     * `openExternalFresh(...)` 绑定点击时刻 fresh URL；传空 lambda 也必须由宿主自己决定行为，
     * 本模块不再提供任何外跳兜底。
     * @param onCopyLink BL-182：非空时挂「复制链接」第三按钮（`setNeutralButton`）。宿主实现
     * 应在点击时刻现取带 token 的 URL、先弹知情确认再复制；拿不到带凭据 URL 时宿主宁可不传
     * 此参数（不挂按钮），绝不复制裸 URL。
     * **重要**：FCLAlertDialog 的按钮点击会**无条件 dismiss**（java:93-106），所以点「复制链接」
     * 也会关掉本框并触发 [onDismissWithoutChoice] —— 宿主要么在复制流程里把"重摆门禁"排到
     * 复制确认框关闭之后，要么接受"页面回到未放行的空白 + 门禁重新弹出"。
     *
     * @param onDismissWithoutChoice BL-186：框被关闭但**既没点继续也没点外跳**（例如用户点了
     * 「复制链接」——复制不等于放行）时调用。宿主用它把 `loadedUrl` 复位，让下次 render 重新
     * 加载并重新过门禁。**只在"被关的是当前显示中的框"时触发**：弹新框前的替换性 dismiss
     *（[dismissCompatDialog] 先清引用）与宿主 onDestroy 的 dismiss 都不会误报。
     * @param onDialogWillShow **仅在确实要弹框时**（版本达标早退路径不触发）于 `show()` 之前调用。
     * R17（作者反馈"弹出 webview 过旧提示时应退出日志界面"）：宿主用它先收掉同页的日志覆盖层 ——
     * 排查时用户开着日志覆盖层，对话框压在日志层上，两个界面互相抢视线（用户两头都够不着）；
     * 先退日志层，让用户直接面对决策。传 null 时行为不变。
     *
     * 版本取自框架 API `WebView.getCurrentWebViewPackage()`（API 26+，minSdk 26 无需守卫；
     * 返回 PackageInfo，取 `versionName`）。诊断日志会带上 versionName 原文与解析结果，
     * 便于用户报障时贴回来（BL-185：此前只有阈值数字，看不出判定输入）。
     */
    fun ensureSupported(
        activity: Activity,
        url: String,
        onContinue: () -> Unit,
        onExternal: () -> Unit,
        onCopyLink: (() -> Unit)? = null,
        onDismissWithoutChoice: (() -> Unit)? = null,
        onDialogWillShow: (() -> Unit)? = null,
    ) {
        val versionName = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
        val major = parseWebViewMajor(versionName)
        // BL-185：诊断日志带原文 —— "为什么被拦/被放"必须能从用户贴回的日志里看出来。
        // url 只以骨架形式出现（BL-183 同款口径：scheme://host:port + 有无 token，绝不含 token 值）。
        DshLogBus.append(
            "[webview-compat] WebView versionName=${versionName ?: "(null)"} " +
                "-> major=${major?.toString() ?: "(unparsed)"} (threshold=$MIN_WEBVIEW_MAJOR), " +
                "url=${urlSkeleton(url)}"
        )
        if (major != null && major >= MIN_WEBVIEW_MAJOR) {
            DshLogBus.append("[webview-compat] WebView version ok ($versionName), loading in-app")
            onContinue()
            return
        }
        val message = if (major == null) {
            // BL-185：读不出版本不再静默放行；文案给原文，并把"复制链接"抬成第一推荐。
            DshLogBus.append("[webview-compat] WebView version unparseable, showing unknown-version gate")
            activity.getString(
                R.string.dsh_webview_compat_unknown_version,
                versionName ?: "unknown"
            )
        } else {
            DshLogBus.append("[webview-compat] WebView $versionName below $MIN_WEBVIEW_MAJOR, asking user")
            activity.getString(R.string.dsh_webview_compat_message, MIN_WEBVIEW_MAJOR)
        }
        showGateDialog(
            activity, message, onContinue, onExternal, onCopyLink, onDismissWithoutChoice,
            onDialogWillShow
        )
    }

    /** [ensureSupported] 的弹框本体：两个版本分支共用同一套按钮编排（BL-182/186）。 */
    private fun showGateDialog(
        activity: Activity,
        message: String,
        onContinue: () -> Unit,
        onExternal: () -> Unit,
        onCopyLink: (() -> Unit)?,
        onDismissWithoutChoice: (() -> Unit)?,
        onDialogWillShow: (() -> Unit)?,
    ) {
        // BL-111 单例守卫：先收旧框再弹新框（本函数可能被回放/多实例反复调用）。
        // 注意顺序：旧框先 dismiss 再记新引用，否则这里会把自己刚建的框收掉。
        dismissCompatDialog()
        // "用户是否做过放行/离开的选择"：继续与外跳都算；复制**不算**（页面还没被放行）。
        var decided = false
        val builder = FCLAlertDialog.Builder(activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            // BL-186：返回键/点外部不能关。关掉后 loadedUrl 已置位，页面会停在空白且无出口。
            .setCancelable(false)
            .setTitle(activity.getString(R.string.dsh_webview_compat_title))
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.dsh_webview_compat_continue)) {
                DshLogBus.append("[webview-compat] user chose to continue in-app on old WebView")
                decided = true
                compatDialog = null
                onContinue()
            }
            .setNegativeButton(activity.getString(R.string.dsh_webview_compat_external)) {
                DshLogBus.append("[webview-compat] user chose external browser")
                decided = true
                compatDialog = null
                // BL-129/BL-130/BL-195：外跳由调用方接管（点击时刻 fresh URL + 导航收尾），
                // 本函数不再 ACTION_VIEW 任何 URL。
                onExternal()
            }
        if (onCopyLink != null) {
            // BL-182：第三出路。FCLAlertDialog 的 neutral 槽在 350dp 等分四支里只分到约 80dp，
            // 文案必须短（「复制链接」/ "Copy link"）。点击后不置 decided —— dismiss 监听据此
            // 通知宿主"页面还没放行"，宿主去恢复门禁（BL-186）。
            builder.setNeutralButton(activity.getString(R.string.dsh_webview_compat_copy)) {
                DshLogBus.append("[webview-compat] user chose copy access link")
                onCopyLink()
            }
        }
        val dialog = builder.create()
        dialog.setOnDismissListener {
            val wasCurrent = compatDialog === dialog
            if (wasCurrent) compatDialog = null
            // 替换性 dismiss（dismissCompatDialog 先清引用）与 Activity 销毁路径不触发：
            // 那不表示"用户没选"，宿主不该因此改 loadedUrl。
            if (wasCurrent && !decided) {
                DshLogBus.append("[webview-compat] gate dismissed without a decision")
                runCatching { onDismissWithoutChoice?.invoke() }
                    .onFailure { DshLogBus.append("[webview-compat] onDismissWithoutChoice threw: $it") }
            }
        }
        compatDialog = dialog
        // R17：先让宿主收掉日志覆盖层（若开着），再 show —— 见 onDialogWillShow 的注释。
        runCatching { onDialogWillShow?.invoke() }
            .onFailure { DshLogBus.append("[webview-compat] onDialogWillShow threw: $it") }
        dialog.show()
    }

    /**
     * R16/fix4（BL-140）：**崩溃态升级提示** —— 老 WebView 上 SPA 已崩坏时再弹一次，
     * 这一次带"怎么升级"的操作指引（[R.string.dsh_webview_compat_upgrade_hint]，此前是零引用
     * 死资源），并保留"复制链接 / 外部浏览器"两个出口。
     *
     * 为什么需要（V6 结论）：[ensureSupported] 的一次性放行（compatOk）在"用户第二次进入/实例
     * 重启后页面重载"时不会再弹框 —— 若用户此前选了"仍要继续"而页面实际崩坏（114 上必崩），
     * 用户就只剩一个白屏/连不上，既不知情也无出口。本函数由宿主在**检测到崩坏**时调用
     * （`onConsoleMessage` 捕获 `Uncaught ... is not a function` 类错误，见
     * `DshWebViewActivity.onSpaCrashDetected`），宿主自行做"每次加载只弹一次"的去重。
     *
     * 与 [ensureSupported] 的对话框共用同一个单例槽 [compatDialog]（两者不会同屏叠影）。
     *
     * @param onExternal **必填**（BL-195）：选"用外部浏览器打开"时的回调（宿主传
     *                   `openExternalFresh` 绑定好的点击时刻动作）。不再允许默认 null，
     *                   也不再有本模块自己 ACTION_VIEW 的兜底。
     * @param onCopyLink BL-182：非空时挂「复制链接」第三按钮（同 [ensureSupported] 的约定）。
     * @param onDialogWillShow **仅在确实要弹框时**于 `show()` 之前调用。
     * R17（作者反馈"弹出 webview 过旧提示时应退出日志界面"）：宿主用它先收掉同页的日志覆盖层。
     * 本函数的典型场景恰是"用户开着日志覆盖层排查崩坏原因"——对话框不加处理会直接压在日志上，
     * 两个界面互相抢视线；先退日志层，让用户干净地面对提示与出口。传 null 时行为不变。
     */
    fun showCrashUpgradeHint(
        activity: Activity,
        onExternal: () -> Unit,
        onCopyLink: (() -> Unit)? = null,
        onDialogWillShow: (() -> Unit)? = null,
    ) {
        dismissCompatDialog()
        DshLogBus.append("[webview-compat] spa crash detected on old WebView, showing upgrade hint")
        val builder = FCLAlertDialog.Builder(activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            // BL-186：同 ensureSupported —— 返回键/点外部不能把提示关成"无出口现场"。
            .setCancelable(false)
            .setTitle(activity.getString(R.string.dsh_webview_compat_title))
            .setMessage(activity.getString(R.string.dsh_webview_compat_upgrade_hint))
            .setPositiveButton(activity.getString(R.string.dialog_positive)) {
                compatDialog = null
            }
            .setNegativeButton(activity.getString(R.string.dsh_webview_compat_external)) {
                DshLogBus.append("[webview-compat] user chose external browser (crash hint)")
                compatDialog = null
                onExternal()
            }
        if (onCopyLink != null) {
            builder.setNeutralButton(activity.getString(R.string.dsh_webview_compat_copy)) {
                DshLogBus.append("[webview-compat] user chose copy access link (crash hint)")
                // 不清 compatDialog：由 dismiss 监听统一收引用（与 ensureSupported 同款）。
                onCopyLink()
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
 * 白名单会漏（而本条判据的使用场景**已由调用方门禁在版本不可信的设备上** —— 见
 * `DshWebViewActivity.maybeShowCrashUpgradeHint` 先查 `parseWebViewMajor`；
 * 在"WebView 过旧或版本读不出"的前提下，任何 `is not a function` 崩溃给"升级 WebView"指引都成立）。
 *
 * 写成顶层函数（而不是 object 内）：单测 harness 的 classpath 没有 androidx —— 顶层落在
 * `DshWebViewCompatKt` 静态方法上，可直接测（同 `trimDeltaFor` 写在 DshWebViewActivity.kt
 * 顶层的理由）。
 */
fun isOldWebViewSpaCrash(consoleMessage: String): Boolean =
    consoleMessage.contains("is not a function")

/**
 * BL-183 纯函数：外跳/门禁日志用的 **URL 骨架** —— `scheme://host:port/ token=present|none`。
 *
 * 为什么单独写一个（而不是直接打 url）：这些日志会进日志总线/落盘，而 dsh 的 URL 带着
 * 会话凭据（`?token=...`）。日志里只需要回答"我们发出了哪台机器哪个端口、带没带凭据"，
 * **不需要也不必**保留 token 值。骨架对排障足够（401 现场能一眼对上端口与有无 token），
 * 泄漏面为零。
 *
 * 口径：
 * - 解析失败（空串/不是 `scheme://authority` 形态）→ `"(unparseable url)"`，不抛出；
 * - 只取 scheme + authority（host[:port]），path 统一记 `/`（dsh 的入口就是根路径，
 *   深路径反而是异常，值得注意）；authority 里若有 userinfo 先剥掉（凭据不进日志）；
 * - 末尾标注 `token=present` / `token=none`（与 [com.dsh.core.UrlScanner.tokenOf] 同判据）。
 *
 * 用正则而不是 `java.net.URI`：URI 对厂商/异常串过严（一个非法字符就整条解析失败），
 * 而这条日志的全部价值就是"出事后还留得下骨架"—— 只有真解析不出才退化成占位符。
 *
 * 写成顶层函数：单测 harness 的 classpath 没有 androidx（`DshWebViewCompat` 是 object，
 * 但引用它的测试已按纯逻辑钉住）；顶层落在 `DshWebViewCompatKt` 上，无此负担。
 */
fun urlSkeleton(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return "(unparseable url)"
    val m = URL_HEAD.find(trimmed) ?: return "(unparseable url)"
    val scheme = m.groupValues[1].lowercase()
    // authority 可能形如 `user:pass@host:port`：只留 host[:port]，凭据部分绝不进日志。
    val authority = m.groupValues[2].substringAfterLast('@')
    val token = if (com.dsh.core.UrlScanner.tokenOf(url) != null) "present" else "none"
    return "$scheme://$authority/ token=$token"
}

/** `scheme://authority` 的头段（[urlSkeleton] 用；预编译）。 */
private val URL_HEAD = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]+)""")
