package com.dsh.ui

import android.app.Activity
import android.view.View
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
 * "仍要继续 / 复制链接 / 用外部浏览器打开"三个出路，外加「稍后」（BL-199：返回键被
 * `setCancelable(false)` 封死后，"先不动"也必须是可达的合法选择）。
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
 *     onCopyLink = { copyAccessLinkConfirmed(ownerId) },  // BL-198：恒挂，不可用由回调自己提示
 *     onDismissWithoutChoice = { ... },
 *     onLater = { logLater() },   // BL-199：只做通知；收音仍走 onDismissWithoutChoice
 * )
 * ```
 * 阈值见 [MIN_WEBVIEW_MAJOR]。**BL-195**：`onExternal` 不再有默认 null 与内部
 * `ACTION_VIEW(snapshot url)` 兜底 —— 那条兜底会拿**旧快照 URL**（可能是换代前的 token）外跳，
 * 是潜伏的第二出口；现在外跳动作必须由宿主在点击时刻现取 fresh URL 后传入。
 * 宿主传 `onCopyLink` 时，门禁框多一个「复制链接」第三出路（BL-182）。
 * **BL-198**：「复制链接」只要回调非空就**恒挂** —— 此前由调用方按"当前能否取到带凭据 URL"
 * 决定挂不挂，而三语文案却无条件推荐它（框上只剩两支时用户在找一个不存在的按钮）。
 * 拿不到链接的情况改由回调内部提示（toast），按钮本身始终可见。
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

    /**
     * BL-213：门禁框（四支全挂）按钮的左右内边距，dp。
     *
     * `FCLButton` 默认 16dp/侧，四支均分时 320dp/4=80dp 的槽里只剩 48dp 给文字 —— 连
     * 「复制链接」（≈56dp）都放不下。收到 4dp 后文字区 ≈72dp，三语四支都在预算内
     *（完整算术见 [showGateDialog] 的 KDoc）。上下仍是 10dp，按钮高度不变。
     */
    private const val GATE_BUTTON_SIDE_PADDING_DP = 4f

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
     * **BL-206**：unknown 档走**独立标题** `dsh_webview_compat_unknown_title`
     *（"无法识别 WebView 版本"）—— 此前复用"系统 WebView 版本过旧"，与正文"无法识别版本"
     * 自相矛盾（标题在断言一件我们没有证据的事）。版本串为 null 时也不再往文案里塞英文
     * `"unknown"`：改走 `dsh_webview_compat_unknown_version_no_name`（"系统未提供版本信息"）。
     *
     * 对话框模块级单例（BL-111）：弹新框前先 [dismissCompatDialog] 收掉旧框（含被压在下层的），
     * 即使本函数被重订阅回放/多实例高频调用，屏幕上最多一个框。旧框的 continue/外跳回调
     * 因 dismiss 而不会再触发——异步旧 token 的加载随框一起作废，符合预期。
     *
     * 对话框 `setCancelable(false)`（BL-186）：返回键/点外部关掉框会让页面停在空白且无出口
     *（`loadedUrl` 已在调用前置位，状态回放走"只 showWeb 不加载"分支）。宿主另有一道
     * [onDismissWithoutChoice] 兜底（程序化 dismiss 等非用户路径）。
     * **BL-199**：返回键被封死后必须有一个"先不动"的合法出口 —— 第四槽
     *（`setExtraButton`）挂「稍后」，点击只关闭、不做任何放行。详见 [showGateDialog]。
     *
     * @param activity 宿主 Activity（弹框与 getString 用；必须是前台 Activity 上下文）
     * @param url 调用方准备加载的 dsh URL。**本函数不把它交给任何出口**：复制与外跳都由宿主在
     * **点击时刻**现取 fresh URL（BL-182/BL-187），旧的"本函数用快照 url 外跳"兜底已按
     * BL-195 删除。它只用于诊断日志（[urlSkeleton]，只留 scheme://host:port + 有无凭据）。
     * @param onContinue 用户选择继续（或版本本就达标）时的回调：通常就是 `webView.loadUrl(url)`
     * @param onExternal **必填**（BL-195）：用户选择"外部浏览器打开"时的回调。宿主传
     * `openExternalFresh(...)` 绑定点击时刻 fresh URL；传空 lambda 也必须由宿主自己决定行为，
     * 本模块不再提供任何外跳兜底。
     * @param onCopyLink BL-182/BL-198：**非空即挂**「复制链接」第三按钮（`setNeutralButton`），
     * 不再由调用方用"此刻能否取到带凭据 URL"决定挂不挂（那会让文案推荐一个不存在的按钮）。
     * 宿主实现应在点击时刻现取带 token 的 URL、先弹知情确认再复制；**拿不到时由回调自己给
     * 用户提示**（toast `dsh_webview_compat_copy_unavailable`），绝不复制裸 URL。
     * **重要**：FCLAlertDialog 的按钮点击会**无条件 dismiss**（java:93-106），所以点「复制链接」
     * 也会关掉本框并触发 [onDismissWithoutChoice] —— 宿主要么在复制流程里把"重摆门禁"排到
     * 复制确认框关闭之后，要么接受"页面回到未放行的空白 + 门禁重新弹出"。
     *
     * @param onDismissWithoutChoice BL-186：框被关闭但**既没点继续也没点外跳**（例如用户点了
     * 「复制链接」——复制不等于放行——或点了「稍后」）时调用。宿主用它把 `loadedUrl` 复位，
     * 让下次 render 重新加载并重新过门禁。**只在"被关的是当前显示中的框"时触发**：弹新框前的
     * 替换性 dismiss（[dismissCompatDialog] 先清引用）与宿主 onDestroy 的 dismiss 都不会误报。
     * @param onLater BL-199：「稍后」按钮的**通知**回调。语义上不放行（本模块不调 onContinue、
     * 不置 decided），**也不改变收音路径**：之后照常触发 [onDismissWithoutChoice]。
     *
     * 为什么不让它接管收音（本可写成"传了 onLater 就不再调 onDismissWithoutChoice"）：那会
     * 把"关框之后屏上还有没有出口"整个押在宿主那一行回调上 —— 宿主只记一行日志时，`loadedUrl`
     * 已置位、WebView 从未加载、状态面板已被收起 ⇒ **空白页**（BL-186 原样复发，而同胞的
     * BL-197 刚因同类假设被三位锐评打回）。现在的写法是失败安全的：最坏情况是"框关掉又摆回来"
     *（浪费一次点击，但出口都在），而不是"什么都没有"。
     *
     * 所以：**"稍后"要有意义，得由宿主在 onLater 里做点事**（例如 `finish()` 回实例列表，
     * 让用户下次自己进来重新决策）。只记日志的话，用户看到的是"点稍后 → 框又回来了"。
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
        onLater: (() -> Unit)? = null,
    ) {
        val versionName = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
        // BL-206：空白串与 null 同等看待 —— 否则文案会变成「读到 ""」（引号里什么都没有），
        // 与"系统没给版本"同义，但读起来像程序坏了。
        val reportedName = versionName?.takeIf { it.isNotBlank() }
        val major = parseWebViewMajor(reportedName)
        // BL-185：诊断日志带原文 —— "为什么被拦/被放"必须能从用户贴回的日志里看出来。
        // url 只以骨架形式出现（BL-183 同款口径：scheme://host:port + 有无凭据，绝不含 token 值）。
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
        // BL-206：标题按档位分开，不再让"版本过旧"的断言出现在"读不出版本"的框上。
        val titleRes = if (major == null) {
            R.string.dsh_webview_compat_unknown_title
        } else {
            R.string.dsh_webview_compat_title
        }
        val message = if (major == null) {
            // BL-185：读不出版本不再静默放行；文案给原文，并把"复制链接"抬成第一推荐。
            // BL-206：null/空白时改走独立文案 —— 英文 "unknown" 塞进中文句子是"把内部占位值
            // 当成了用户可见的版本号"（用户会以为设备真报了这个版本）。
            DshLogBus.append("[webview-compat] WebView version unparseable, showing unknown-version gate")
            if (reportedName == null) {
                activity.getString(R.string.dsh_webview_compat_unknown_version_no_name)
            } else {
                activity.getString(R.string.dsh_webview_compat_unknown_version, reportedName)
            }
        } else {
            DshLogBus.append("[webview-compat] WebView $versionName below $MIN_WEBVIEW_MAJOR, asking user")
            activity.getString(R.string.dsh_webview_compat_message, MIN_WEBVIEW_MAJOR)
        }
        showGateDialog(
            activity,
            activity.getString(titleRes),
            message,
            onContinue,
            onExternal,
            onCopyLink,
            onDismissWithoutChoice,
            onDialogWillShow,
            onLater,
        )
    }

    /**
     * [ensureSupported] 的弹框本体：两个版本分支共用同一套按钮编排（BL-182/186/198/199）。
     *
     * ## 四支按钮的宽度算术（BL-213，修正此前注释里的错误算式）
     * `dialog_alert.xml` 的按钮行是 4 个 `layout_weight=1` 的槽（正/neutral/extra/负），
     * 框体 `minWidth=350dp`、内边距 15dp ⇒ 按钮行可用 **320dp**（这是**下界**：窗口是
     * WRAP_CONTENT，正文长时框会更宽，槽也跟着更宽；窄屏/大字号才逼近这个下界）。
     * - `extra` 恒 GONE 时（旧口径）：权重只分给 3 个可见槽 ⇒ 每槽 320/3 ≈ **107dp**，
     *   减去 `FCLButton` 自带左右各 16dp 内边距 ⇒ 文字区 ≈ **75dp**；
     *   —— 此前注释写的"等分四支各约 80dp"是错的：GONE 的 View 不参与权重分配。
     * - 四支全挂（`onCopyLink != null`）⇒ 每槽 320/4 = **80dp**；默认 16dp 内边距只剩 48dp
     *   文字区，三语里最长的「复制链接」/ "Copy link"（4 个汉字 ≈56dp / 9 个拉丁字符 ≈63dp）
     *   都放不下（只会 marquee 滚动，读起来像坏了）。因此**只有四支全挂时**把四支的
     *   左右内边距收到 4dp（[GATE_BUTTON_SIDE_PADDING_DP]，上下仍 10dp 保持高度一致）
     *   ⇒ 文字区 ≈ **72dp**。三支时仍用默认 16dp（≈75dp 文字区，本来就够）。
     *   预算（四支时，14sp；汉字约 1.0em=14dp、拉丁约 0.5em=7dp）：
     *   「仍要继续」56 / 「复制链接」56 / 「稍后」28 / 「浏览器」42 ✔；
     *   "Continue" 56 / "Copy link" 63 / "Later" 35 / "Browser" 49 ✔。
     *   两条压短的文案及其理由见 strings.xml 对应注释；**改文案前先算长度**：每支 ≈5 个汉字
     *   或 9 个拉丁字符，再长的只会 marquee 滚动（不是截断，但读起来像坏了）。
     *
     * ## 为什么坚持四支（而不是砍掉「稍后」保三支）
     * 四条动作各自不可替代：继续=放行、复制=把现场带走、外部=换引擎打开、稍后=**先不动**。
     * BL-186 已经用 `setCancelable(false)` 封死返回键（否则页面停在空白且无出口），
     * 于是「稍后」是唯一能表达"我现在不决定"的合法出口；砍掉它等于把用户逼进两个他不想要的
     * 选择里（BL-199）。而砍掉「复制链接」会让三语文案继续推荐一个不存在的按钮（BL-198），
     * 砍掉「外部浏览器」会让"换浏览器打开"这条最有效的自救路径消失（BL-184 已把它降为次推荐，
     * 不是删除）。缩窄内边距换取四支共存，是这里唯一不需要动共享布局文件的解。
     *
     * 屏幕上的左右顺序由 `dialog_alert.xml` 决定（positive → neutral → extra → negative），
     * 即 **继续 | 复制链接 | 稍后 | 外部**。之所以让「外部」压在最后：它是"离开本页"的动作，
     * 与 negative 槽的旧语义一致；而「稍后」放 extra 是因为那是唯一空闲槽，且它是本次新增的
     * 第四支（不想改动既有三支的位置，用户肌肉记忆与旧版一致）。
     *
     * @param title BL-206：两档标题不同（旧版 / 无法识别），由调用方选好传入。
     */
    private fun showGateDialog(
        activity: Activity,
        title: String,
        message: String,
        onContinue: () -> Unit,
        onExternal: () -> Unit,
        onCopyLink: (() -> Unit)?,
        onDismissWithoutChoice: (() -> Unit)?,
        onDialogWillShow: (() -> Unit)?,
        onLater: (() -> Unit)?,
    ) {
        // BL-111 单例守卫：先收旧框再弹新框（本函数可能被回放/多实例反复调用）。
        // 注意顺序：旧框先 dismiss 再记新引用，否则这里会把自己刚建的框收掉。
        dismissCompatDialog()
        // "用户是否做过放行/离开的选择"：继续与外跳都算；复制与稍后**都不算**（页面还没被放行）。
        var decided = false
        val builder = FCLAlertDialog.Builder(activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            // BL-186：返回键/点外部不能关。关掉后 loadedUrl 已置位，页面会停在空白且无出口。
            .setCancelable(false)
            .setTitle(title)
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
            // BL-199：第四槽「稍后」。setCancelable(false) 封死返回键后，这是唯一的"先不动"出口；
            // 语义上**不做任何放行**（不置 decided、不调 onContinue），也**不改收音路径** ——
            // 关闭后照常走 onDismissWithoutChoice（复位 loadedUrl + 重摆门禁），所以最坏情况是
            // "框关掉又摆回来"，绝不会停在空白页上。详见 ensureSupported 的 onLater 参数说明。
            .setExtraButton(activity.getString(R.string.dsh_webview_compat_later)) {
                DshLogBus.append("[webview-compat] user chose to decide later")
                val later = onLater
                if (later != null) {
                    runCatching { later() }
                        .onFailure { DshLogBus.append("[webview-compat] onLater threw: $it") }
                }
            }
        if (onCopyLink != null) {
            // BL-182/BL-198：第三出路，**回调非空即挂**（不由调用方按"此刻有没有带凭据 URL"决定，
            // 否则文案会推荐一个不存在的按钮）。点击后不置 decided —— dismiss 监听据此
            // 通知宿主"页面还没放行"，宿主去恢复门禁（BL-186）。
            builder.setNeutralButton(activity.getString(R.string.dsh_webview_compat_copy)) {
                DshLogBus.append("[webview-compat] user chose copy access link")
                onCopyLink()
            }
        }
        val dialog = builder.create()
        // BL-213：只有四支全挂时才收窄内边距（三支时 107dp 的槽本来就够，没必要改），
        // 给文字腾出 ≈72dp（见本函数 KDoc 的算术）。只作用于这一框 —— 共享布局
        // dialog_alert.xml 不动，其它对话框保持原样。
        if (onCopyLink != null) applyGateButtonPadding(dialog)
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
     * BL-213：把门禁框四支按钮的左右内边距收到 [GATE_BUTTON_SIDE_PADDING_DP]。
     *
     * **只在四支全挂时调用**（三支可见槽是 107dp，默认内边距已经够）。
     * 为什么从外面改而不用布局：`dialog_alert.xml` 被全 App 的 FCLAlertDialog 共用，
     * 为一个"只有本框才挂四支"的场景去改它，会把所有对话框的按钮都变窄（回归面太大）。
     * 这里只摸本框实例的四个 id，其它对话框完全不受影响。
     * 拿不到 View（FCL 基座改布局）时静默跳过 —— 退化成旧间距，功能不受影响。
     */
    private fun applyGateButtonPadding(dialog: FCLAlertDialog) {
        val density = dialog.context.resources.displayMetrics.density
        val sidePx = (GATE_BUTTON_SIDE_PADDING_DP * density).toInt()
        val verticalPx = (10f * density).toInt()
        val ids = intArrayOf(R.id.positive, R.id.neutral, R.id.extra, R.id.negative)
        ids.forEach { id ->
            dialog.findViewById<View>(id)?.setPadding(sidePx, verticalPx, sidePx, verticalPx)
        }
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
     * @param onCopyLink BL-182/BL-198：**非空即恒挂**「复制链接」第三按钮（同 [ensureSupported]
     *                   的约定）。调用方不得再按"此刻能否取到带凭据 URL"决定挂不挂 —— 那正是
     *                   BL-198 的成因（文案推荐一个不存在的按钮）。拿不到链接时由回调自己提示。
     * @param onDialogWillShow **仅在确实要弹框时**于 `show()` 之前调用。
     * R17（作者反馈"弹出 webview 过旧提示时应退出日志界面"）：宿主用它先收掉同页的日志覆盖层。
     * 本函数的典型场景恰是"用户开着日志覆盖层排查崩坏原因"——对话框不加处理会直接压在日志上，
     * 两个界面互相抢视线；先退日志层，让用户干净地面对提示与出口。传 null 时行为不变。
     *
     * **本框不需要「稍后」第四支**（与 [showGateDialog] 的区别）：它的正向键就是
     * `dialog_positive`（"确认"/"OK"），点击只关闭、不做任何加载 —— 语义上已经是"先不动"，
     * 再挂第四支只会把三个出口挤窄（BL-213 的宽度预算）。
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
 * BL-183 纯函数：外跳/门禁日志用的 **URL 骨架** —— `scheme://host:port/ auth=yes|no`。
 *
 * 为什么单独写一个（而不是直接打 url）：这些日志会进日志总线/落盘，而 dsh 的 URL 带着
 * 会话凭据（`?token=...`）。日志里只需要回答"我们发出了哪台机器哪个端口、带没带凭据"，
 * **不需要也不必**保留 token 值。骨架对排障足够（401 现场能一眼对上端口与有无凭据），
 * 泄漏面为零。
 *
 * 口径：
 * - 解析失败（空串/不是 `scheme://authority` 形态）→ `"(unparseable url)"`，不抛出；
 * - 只取 scheme + authority（host[:port]），path 统一记 `/`（dsh 的入口就是根路径，
 *   深路径反而是异常，值得注意）；authority 里若有 userinfo 先剥掉（凭据不进日志）；
 * - 末尾标注 `auth=yes` / `auth=no`（与 [com.dsh.core.UrlScanner.tokenOf] 同判据）。
 *
 * ## BL-202：为什么标记不叫 `token=`
 * fix9 这里写的是 `token=present` / `token=none`，而 [DshLogBus.sanitize] 的正则
 * `(?i)(token=)[A-Za-z0-9_\-]+` 会把它**统一替换成 `token=***`** —— 两种状态在日志页与落盘
 * 文件里退化成同一个串，而 `***` 恰恰是"真 token 被脱敏"的形态，于是**裸 URL 外跳会被日志
 * 写成"当时有凭据"**，伪造了一条指向错误归因的证据（比不打日志更危险）。
 * 改成 `auth=` 是因为它不在脱敏正则里，能原样留下；**不要**反过来给 sanitize 加白名单 ——
 * `token=present` 本身就是一个合法 base64url 串，白名单等于给未来留一个口子。
 * 注意 `auth=` 后面只能出现 `yes`/`no` 两个常量：这里**永远不放**任何 URL 里的值。
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
    val hasAuth = if (com.dsh.core.UrlScanner.tokenOf(url) != null) "yes" else "no"
    return "$scheme://$authority/ auth=$hasAuth"
}

/** `scheme://authority` 的头段（[urlSkeleton] 用；预编译）。 */
private val URL_HEAD = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]+)""")
