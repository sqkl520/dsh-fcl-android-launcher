package com.dsh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * R29 / BL-221：**调用点契约测试** —— 复制链接成功后，真的摆出了「链接已复制」面板。
 *
 * ## 为什么这组测试必须存在
 *
 * 本批改动把 `GateResume` 加了第 4 个值 `DeferredPanel`，并把判定内核
 * [com.dsh.ui.gateResumeAction] 的 `copied ->` 一行改成返回它。纯函数测试
 * （`R23FixTest`）只能证明**判定内核返回了 DeferredPanel**，证明不了**调用点真的拿它摆了面板**：
 * 调用点完全可能继续写着 `postRender(...)` 重摆门禁（= 用户刚答过的问题再问一遍），
 * 或者什么都不做（= 回到 BL-197 的空白页），而纯函数测试照样全绿。
 *
 * 本仓已有前车之鉴：[R28CallSiteContractTest] 就是因为在 `DshRuntime` 里只抽纯函数、
 * 没钉调用点，把 `homeRootfs` 改回 `workspace`（两次事故的真实形状）后 493 条测试**全绿**。
 * 结论：只测纯函数、不钉调用点，等于没修。
 *
 * ## 为什么用源码级断言
 *
 * 这里要断言的是 Activity 成员方法体内的语句（摆面板 / 置标记 / 不排 post），
 * 真跑起来需要 `Context` + 主线程 Handler + 生命周期，JVM 单测做不到；
 * 源码级断言是本仓已有的先例（见 `R28CallSiteContractTest` 的说明，弱但能挡住这类回归）。
 */
class R29CopyDeferTest {

    /**
     * ★ 复制成功的收尾分支必须**同步**摆出面板并置 `gateCopied`。
     *
     * 缺 `showError(` = BL-197 的空白页直接复发（门禁框已被按钮点击无条件 dismiss，
     * 状态面板也早已被 `showWeb()` 收起，这一支不摆面板就是整屏无出口）；
     * 缺 `gateCopied = true` = 复制后从浏览器切回来（onStart 状态回放）又被问一次
     * —— 或更糟：`loadUrlChecked` 直接拿旧内核把 dsh 加载起来（用户拿走链接正是要避开它）。
     */
    @Test
    fun deferredPanelBranchShowsCopiedPanelAndMarksFlag() {
        val text = mainSourceText()
        val body = deferredPanelBranchBody(text)
        if (body == null) {
            fail(
                "找不到 GateResume.DeferredPanel 的 when 分支 —— 要么分支被删了，要么写法变了" +
                    "（期望形如 `GateResume.DeferredPanel -> { ... }`）"
            )
        }
        val b = body!!
        assertTrue("DeferredPanel 分支体切出来是空的 —— 切片上界选错了，先看主源码的写法", b.trim().isNotEmpty())

        assertTrue(
            "DeferredPanel 分支必须同步调用 showError( —— 否则门禁框已 dismiss、状态面板已收起，" +
                "整屏无控件（BL-197 的空白页复发）。实际分支体：$b",
            b.contains("showError(")
        )
        assertTrue(
            "DeferredPanel 分支必须显示 dsh_webview_gate_copied —— 用户刚点完「复制链接」，" +
                "面板得明确说「链接已复制」，不能复用「暂未打开 dsh 界面」（BL-206：错误归因更糟）。" +
                "实际分支体：$b",
            b.contains("dsh_webview_gate_copied")
        )
        assertTrue(
            "DeferredPanel 分支必须置 gateCopied = true —— 否则复制后 Home 往返/切回浏览器回来，" +
                "onStart 状态回放又会走 loadUrlChecked 把门禁弹回来（同一句话问第三遍）。实际分支体：$b",
            b.contains("gateCopied = true")
        )
        assertTrue(
            "DeferredPanel 分支必须兜一次 ensureVisibleExit( —— 本页任何路径都不许以空白收场" +
                "（与其它分支同一纪律，纯防御、幂等）。实际分支体：$b",
            b.contains("ensureVisibleExit(")
        )
    }

    /**
     * 同一分支体**不许**再排 `postRender(`。
     *
     * `postRender` 会重新走一遍 render → loadUrlChecked → 版本门禁，等于把刚放弃的
     * "继续 / 复制链接 / 外部浏览器"三选一原样摆回来 —— 这正是 BL-221 要消灭的行为
     * （用户刚回答过，9 分钟内同一句话问三次）。面板是**同步** showError 摆的，不需要任何 post。
     */
    @Test
    fun deferredPanelBranchDoesNotScheduleAnotherGate() {
        val text = mainSourceText()
        val body = deferredPanelBranchBody(text)
        if (body == null) {
            fail("找不到 GateResume.DeferredPanel 的 when 分支 —— 要么分支被删了，要么写法变了")
        }
        val b = body!!
        assertTrue("DeferredPanel 分支体切出来是空的 —— 切片上界选错了，先看主源码的写法", b.trim().isNotEmpty())

        assertFalse(
            "DeferredPanel 分支不许排 postRender( —— 它会重走 render → loadUrlChecked → 版本门禁，" +
                "把用户刚答过的问题再摆回来（BL-221 的核心回归形状）。实际分支体：$b",
            b.contains("postRender(")
        )
    }

    /**
     * `gateCopied` 必须在 `loadUrlChecked` 的 **`compatOk` 直通之前**被判断，且 `onRetryClicked`
     * 必须清掉它。
     *
     * - 顺序：`compatOk` 直通会无条件 `webView.loadUrl(url)`。若 `gateCopied` 挡在它后面，
     *   一次 onStart 状态回放就会用旧内核加载 dsh —— 正是用户拿走链接要避开的场面
     *   （WebView 版本门禁存在的全部意义）。
     * - 清理：若 `onRetryClicked` 不清 `gateCopied`，「重试」就是死按钮 —— 用户点重试，
     *   render → loadUrlChecked 又命中 `gateCopied`，同一个面板原样回来，永远出不去。
     */
    @Test
    fun gateCopiedIsCheckedBeforeLoadAndClearedByRetry() {
        val text = mainSourceText()

        val loadBody = functionBody(text, "fun loadUrlChecked(")
        if (loadBody == null) fail("找不到 loadUrlChecked( 函数体 —— 切片签名变了，先看主源码")
        val lb = loadBody!!

        // 用正则而不是 indexOf("gateCopied")：注释里也会出现这个词（字段 KDoc 就引用它），
        // 只认真正的 if 语句，否则"把判断挪到 compatOk 后面"这种回归会被注释糊过去。
        val gateIf = Regex("""if\s*\(\s*gateCopied\s*\)""").find(lb)
        val compatIf = Regex("""if\s*\(\s*compatOk\s*\)""").find(lb)

        if (gateIf == null) {
            fail(
                "loadUrlChecked 里找不到 `if (gateCopied)` —— 复制链接留下的「别替我加载」标记没有接线，" +
                    "从浏览器切回来会被重新问一次（或被旧内核直接加载）。实际函数体：${snippet(lb)}"
            )
        }
        if (compatIf == null) {
            fail(
                "loadUrlChecked 里找不到 `if (compatOk)` —— 切片或写法变了，这条顺序断言失去意义。" +
                    "实际函数体：${snippet(lb)}"
            )
        }
        assertTrue(
            "`if (gateCopied)` 必须出现在 `if (compatOk)` 直通**之前** —— 否则 onStart 状态回放" +
                "会先在 compatOk 分支里 loadUrl，用不达标的 WebView 内核渲染 dsh（必崩）。" +
                "实际偏移：gateCopied@${gateIf!!.range.first}, compatOk@${compatIf!!.range.first}",
            gateIf.range.first < compatIf.range.first
        )

        val retryBody = functionBody(text, "fun onRetryClicked(")
        if (retryBody == null) fail("找不到 onRetryClicked( 函数体 —— 切片签名变了，先看主源码")
        val rb = retryBody!!
        assertTrue(
            "onRetryClicked 必须清掉 gateCopied（gateCopied = false）—— 否则「重试」是死按钮：" +
                "loadUrlChecked 又命中 gateCopied，面板原样回来。实际函数体：${snippet(rb)}",
            Regex("""gateCopied\s*=\s*false""").containsMatchIn(rb)
        )
    }

    /**
     * 三语面板文案必须：存在、非空、**提到本语言「重试」按钮的原文**、且不含撇号与反斜杠。
     *
     * - 提到 `dsh_action_retry` 的原文：钉住"面板说的按钮名与面板上真有的按钮名一致"。
     *   文案写「重试」而按钮叫别的（或反过来），用户就得在两个词之间换算 —— 三语各自为政时
     *   最容易漏掉这种不一致。
     * - 不含 `'` 和 `\`：aapt2 对 `\x27` 与 `\\'` 两种撇号转义写法都报
     *   `Invalid unicode escape sequence`，这个项目**踩过两次**。文案一旦带上撇号（例如
     *   英文的 don't / user's），构建直接红。
     */
    @Test
    fun copiedPanelCopyIsLocalizedAndNamesTheRetryButton() {
        val locales = listOf("values", "values-zh", "values-zh-rTW")
        for (locale in locales) {
            val file = resolveResFile("$locale/strings.xml")
            if (file == null) {
                fail(
                    "找不到 $locale/strings.xml：请在仓库根或 FCL/ 目录下运行测试" +
                        "（兼容 FCL/FCL/ FCL/ src/main/ 三种布局）"
                )
            }
            val xml = file!!.readText()

            val copied = xmlStringValue(xml, "dsh_webview_gate_copied")
            val retry = xmlStringValue(xml, "dsh_action_retry")
            if (copied == null) {
                fail(
                    "$locale：缺少 dsh_webview_gate_copied —— 复制成功后调用点 getString 会拿不到文案" +
                        "（编译器红，或运行期回落成资源名）"
                )
            }
            if (retry == null) {
                fail("$locale：缺少 dsh_action_retry —— 无法核对面板文案里的按钮名（这条语言可能整段缺失）")
            }

            val c = copied!!.trim()
            val r = retry!!.trim()
            assertTrue("$locale：dsh_webview_gate_copied 的文案不能为空", c.isNotEmpty())
            assertTrue("$locale：dsh_action_retry 的文案不能为空", r.isNotEmpty())
            assertTrue(
                "$locale：面板文案必须包含本语言「重试」按钮的原文 <$r> —— " +
                    "否则面板上说的按钮名和用户看到的按钮对不上。实际文案=<$c>",
                c.contains(r)
            )
            assertFalse(
                "$locale：面板文案不许含撇号 ' —— aapt2 对 \\x27 与 \\\\' 都报 " +
                    "Invalid unicode escape sequence（本项目踩过两次）。实际文案=<$c>",
                c.contains("'")
            )
            assertFalse(
                "$locale：面板文案不许含反斜杠 \\ —— 同上 aapt2 限制。实际文案=<$c>",
                c.contains("\\")
            )
        }
    }

    // ---------------------------------------------------------------------
    // 源码定位与切片（照抄 R28HomeDirPlanTest 末尾 resolveMainSource 的形态）
    // ---------------------------------------------------------------------

    /** 读主源码；找不到就 fail 得明明白白（不要读成空串再让后面的断言给出误导性信息）。 */
    private fun mainSourceText(): String {
        val file = resolveMainSource("ui/DshWebViewActivity.kt")
        if (file == null) {
            fail(
                "找不到 ui/DshWebViewActivity.kt：请在仓库根或 FCL/ 目录下运行测试" +
                    "（兼容 FCL/FCL/ FCL/ src/main/ 三种布局）"
            )
        }
        return file!!.readText()
    }

    /**
     * 切出 `GateResume.DeferredPanel` 那个 `when` 分支的**分支体**（不含分支标签、`->` 与外层花括号）。
     *
     * 稳健性：
     * - 标签匹配用正则 `GateResume\.DeferredPanel\s*->`，容忍换行/空格，但**不会**误中
     *   KDoc 里不带箭头的 `[GateResume.DeferredPanel]` 引用（全文件只有分支标签带 `->`）；
     * - 上界用**花括号配对**（[matchingBrace]，跳过字符串与注释）—— 分支体内部将来长出
     *   嵌套 `if {}` / `run {}` 也不会被截断；不依赖任何行号，分支顺序调整、注释增减都不影响。
     *
     * 找到后**去掉行注释**再交给调用方：否则分支里一句「不要 postRender()」的注释就会让
     * `doesNotScheduleAnotherGate` 假红，反过来一句注释里的 showError 又会让
     * `showsCopiedPanelAndMarksFlag` 假绿。断言只看真代码。
     *
     * 找不到（分支被删 / 改成返回表达式的写法）返回 null，由调用方给出明确失败信息。
     */
    private fun deferredPanelBranchBody(source: String): String? {
        val label = Regex("""GateResume\.DeferredPanel\s*->""").find(source) ?: return null
        val afterArrow = label.range.last + 1
        // 箭头与 `{` 之间允许有注释（本仓习惯在分支里先写 KDoc 式的行注释）：
        // 先在**去掉注释**的文本里找 `{`，再按同一偏移回到原文取配对括号。
        // 简单起见这里直接扫原文：跳过空白与 `//` 注释后看第一个有效字符。
        var i = afterArrow
        while (i < source.length && source[i].isWhitespace()) i++
        while (i + 1 < source.length && source[i] == '/' && source[i + 1] == '/') {
            while (i < source.length && source[i] != '\n') i++
            while (i < source.length && source[i].isWhitespace()) i++
        }
        val body = if (i < source.length && source[i] == '{') {
            val close = matchingBrace(source, i) ?: return null
            source.substring(i + 1, close)
        } else {
            // 表达式体写法（`DeferredPanel -> showError(...)`）：取到本行结束。
            val eol = source.indexOf('\n', afterArrow).let { if (it < 0) source.length else it }
            source.substring(afterArrow, eol)
        }
        return stripLineComments(body)
    }

    /**
     * 切出 `signature`（例如 `fun loadUrlChecked(`）对应函数的函数体：从签名后的第一个 `{`
     * 到**配对的** `}`。
     *
     * 用签名而不是行号（注释里提到函数名也不会误切）；用花括号配对而不是"下一个
     * `private fun `"（函数体里的 lambda / when 都带花括号，行号或下一定界都会在它们身上切错）。
     */
    private fun functionBody(source: String, signature: String): String? {
        val sig = source.indexOf(signature)
        if (sig < 0) return null
        val open = source.indexOf('{', sig)
        if (open < 0) return null
        val close = matchingBrace(source, open) ?: return null
        return stripLineComments(source.substring(open + 1, close))
    }

    /**
     * 从 `open`（必须指向 `{`）找配对的 `}`；跳过字符串字面量、字符字面量与注释。
     * 找不到返回 null。只用在本仓这一种缩进规整的 Kotlin 源码上，够用且不依赖格式。
     */
    private fun matchingBrace(source: String, open: Int): Int? {
        var depth = 0
        var i = open
        var inLine = false
        var inBlock = false
        var inString = false
        var inChar = false
        while (i < source.length) {
            val ch = source[i]
            val next = source.getOrElse(i + 1) { ' ' }
            when {
                inLine -> if (ch == '\n') inLine = false
                inBlock -> if (ch == '*' && next == '/') { inBlock = false; i++ }
                inString -> when {
                    ch == '\\' -> i++
                    ch == '"' -> inString = false
                }
                inChar -> when {
                    ch == '\\' -> i++
                    ch == '\'' -> inChar = false
                }
                ch == '/' && next == '/' -> inLine = true
                ch == '/' && next == '*' -> { inBlock = true; i++ }
                ch == '"' -> inString = true
                ch == '\'' -> inChar = true
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** 去掉 `//` 行注释（跳过字符串/字符字面量里的 `//`，例如 `http://`）。 */
    private fun stripLineComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        var inChar = false
        while (i < text.length) {
            val ch = text[i]
            val next = text.getOrElse(i + 1) { ' ' }
            when {
                inString || inChar -> {
                    out.append(ch)
                    when {
                        ch == '\\' && i + 1 < text.length -> { out.append(next); i++ }
                        inString && ch == '"' -> inString = false
                        inChar && ch == '\'' -> inChar = false
                    }
                }
                ch == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                    if (i < text.length) out.append('\n')
                }
                ch == '"' -> { inString = true; out.append(ch) }
                ch == '\'' -> { inChar = true; out.append(ch) }
                else -> out.append(ch)
            }
            i++
        }
        return out.toString()
    }

    /** 失败信息里贴完整函数体会把几百行源代码灌进断言消息；截一段够定位即可。 */
    private fun snippet(text: String, max: Int = 600): String {
        val t = text.trim()
        return if (t.length <= max) t else t.take(max) + " …(截断)"
    }

    /**
     * 从一份 strings.xml 里取出某个 string 的值（未做 XML 反转义 —— 这里用到的条目都只有
     * 纯文本，先读实际内容再定：三个文件里既没有 CDATA，也没有 &apos; / \uXXXX 出现在这两条里）。
     */
    private fun xmlStringValue(xml: String, name: String): String? {
        val pattern = Regex(
            """<string\s+name="${Regex.escape(name)}"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL
        )
        return pattern.find(xml)?.groupValues?.get(1)
    }

    /** 与其他测试同款：从 cwd 向上找 `FCL/src/main/java/com/dsh/<rel>`。 */
    private fun resolveMainSource(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/java/com/dsh/$rel"),
                File(dir, "FCL/src/main/java/com/dsh/$rel"),
                File(dir, "src/main/java/com/dsh/$rel")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    /** 同上，用于资源文件：从 cwd 向上找 `FCL/src/main/res/<rel>`（三布局兼容）。 */
    private fun resolveResFile(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/res/$rel"),
                File(dir, "FCL/src/main/res/$rel"),
                File(dir, "src/main/res/$rel")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }
}
