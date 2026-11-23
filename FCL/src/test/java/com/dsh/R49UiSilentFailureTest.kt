package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * round49 / W1-D：**两处 UI 侧的"失败被静默吞掉"**（D-1 在 `DshAboutUI`，D-2 在
 * `DshWebViewCompat`）。
 *
 * ## ⚠️ 这组断言**没有运行时判别力** —— 先读这一节再读用例
 *
 * 两条都需要 Android 运行时才能真触发：
 * - D-1 需要 `Context.startActivity` 抛（无浏览器 handler）+ `ClipboardManager` 也失败；
 * - D-2 需要 `webView.webViewClient = ...` 在已销毁/已 detach 的 WebView 上抛。
 *
 * JVM 单测（本 harness 只有 `android.jar` 桩，所有方法体 `throw new RuntimeException("Stub!")`）
 * 拿不到哪怕一个可运行的 `Context`/`WebView`，**跑不出这两个分支**。所以这里只钉**代码形态**：
 * 断言失败分支存在、且断言"最省事的回退写法"不存在。
 *
 * 这意味着：**测试全绿 ≠ 运行时行为正确**。它只保证"以后没人把它改回静默"。
 * 真正的运行时证据只能来自真机（`docs/reports/round49/ONDEVICE.md` 那套手法）。
 * 写清楚这一点，是因为同类源码文本断言最容易被后人误读成"行为已验证"。
 *
 * ## 风格
 *
 * 对齐 [R49SilentFailureTest]（同一轮、同一口径）：每条正面断言配一条**反面断言**，
 * 让"把 `runCatching` 换成空 `try/catch`"或"把 `onFailure` 挂到别处"都能被抓到。
 * 两条都做了变异验证（临时撤销修复 → 确认变红 → 恢复），见
 * `docs/reports/round49/fixes/W1-D.md`。
 */
class R49UiSilentFailureTest {

    private val aboutUi by lazy { mainSourceFile("ui/shell/DshAboutUI.kt") }
    private val webViewCompat by lazy { mainSourceFile("ui/DshWebViewCompat.kt") }

    // ===== D-1：关于页「复制链接」兜底失败必须留痕 + 文案不得暗示已复制 =====

    /**
     * `openLink()` 的剪贴板兜底必须检查结果，不能吞掉。
     *
     * ## 为什么（BL-207 漏修的同一处）
     *
     * 同一 App 里 `DshWebViewActivity.copyAccessLinkConfirmed` 在 fix10 已按 BL-207 修过，
     * 那里的注释把理由写得很清楚：
     * > 失败时不能复用 `open_link_failed`（'已自动复制链接，请手动粘贴'）—— 那次复制根本
     * > 没发生，剪贴板里可能还是上一条旧凭据
     *
     * 而这里一直是裸 `runCatching { cm.setPrimaryClip(...) }`，失败后**无条件**弹
     * `open_link_failed`。所以这是一个**已知坑的复发**，不是新发现 —— 修它的价值在于
     * "同一句谎话不要再出现在第二个页面"。
     *
     * 变异验证：把 `val copied = runCatching {...}` 改回裸 `runCatching {...}`
     * （即删掉 `if (copied.isFailure)` 判断）⇒ 本条与 [aboutLinkCopyFailureUsesDedicatedCopy] 同时变红。
     */
    @Test
    fun aboutLinkClipboardFallbackChecksOutcome() {
        val body = functionBody(aboutUi, "private fun openLink(")
        assertTrue(
            "★ 找不到剪贴板兜底里的 setPrimaryClip（源码结构变了？）",
            body.contains("setPrimaryClip")
        )
        // 正面：结果必须被捕获成变量并判 isFailure
        assertTrue(
            "★ 剪贴板兜底的结果必须被捕获（`val copied = runCatching { ... }` 形态）——" +
                "裸 runCatching 意味着失败被完全吞掉，用户拿不到任何线索",
            body.contains("val copied = runCatching {")
        )
        assertTrue(
            "★ 必须显式判 `copied.isFailure` 并走单独分支；" +
                "否则复制失败与复制成功在 UI 上完全一样（都弹「已自动复制」）",
            body.contains("copied.isFailure")
        )
        // 正面：失败分支要进日志总线（口径 C：异常发生时有观察者能知道）
        assertTrue(
            "★ 复制失败的异常必须进日志（DshLogBus.append），不能只弹 Toast ——" +
                "Toast 一闪即过，用户报障时贴不出任何东西",
            Regex(
                """if \(copied\.isFailure\) \{[\s\S]{0,400}?DshLogBus\.append""",
            ).containsMatchIn(body)
        )
        // 正面：失败分支要带上异常本体（"复制失败"三个字不足以定位是 SecurityException 还是 NPE）
        assertTrue(
            "★ 失败日志里要带 exceptionOrNull()，只说「复制失败」定位不了原因",
            body.contains("copied.exceptionOrNull()")
        )
    }

    /**
     * 反面断言：`openLink()` 里不许再出现**结果被丢弃**的 runCatching 包着 setPrimaryClip。
     *
     * ★ 判据不能只看"这一行长什么样"：修复前是 `runCatching {`（裸），修复后是
     *   `val copied = runCatching {` —— 差别在**行首有没有赋值**。所以按行首判。
     *   另外还必须确认这不是"赋值了但没人读"（Kotlin 不会对此报警），因此配合
     *   [aboutLinkClipboardFallbackChecksOutcome] 的 `isFailure` 断言成对生效。
     */
    @Test
    fun aboutLinkHasNoBareClipboardRunCatching() {
        val body = functionBody(aboutUi, "private fun openLink(")
        val lines = body.lines()
        var bare = 0
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.contains("runCatching {")) continue
            // 只有"这段 runCatching 里装着 setPrimaryClip"才归本条管（openLink 里还有一处
            // startActivity 的 runCatching，它是 `.isSuccess` 消费的，不属于本缺陷）。
            val window = lines.drop(i).take(4).joinToString("\n")
            if (!window.contains("setPrimaryClip")) continue
            val head = line.trimStart()
            if (!head.startsWith("val ") && !head.startsWith("val\t")) bare++
        }
        assertEquals(
            "★ openLink 的剪贴板兜底不得使用结果被丢弃的裸 runCatching；" +
                "实测裸写法 $bare 处（应为 0）。裸写法的后果：复制失败无日志，" +
                "下一行无条件弹「已自动复制链接，请手动粘贴」—— 在描述一件没发生的事",
            0, bare
        )
    }

    /**
     * 复制失败必须用**独立文案** `dsh_copy_url_failed`，不能复用 `open_link_failed`。
     *
     * GUI 审查里的措辞问题：`open_link_failed` 的正文是"已自动复制链接，请手动粘贴"
     * （三语皆然），它**只在复制真的成功时**才成立。BL-207 当初就是为了这句话单独引了
     * `dsh_copy_url_failed`。这里断言的是"失败分支用的是新键、且成功分支才用旧键"。
     *
     * 变异验证：把失败分支的 toast 改回 `R.string.open_link_failed` ⇒ 本条变红。
     */
    @Test
    fun aboutLinkCopyFailureUsesDedicatedCopy() {
        val body = functionBody(aboutUi, "private fun openLink(")
        val failure = body.indexOf("copied.isFailure")
        assertTrue("找不到 copied.isFailure 分支", failure >= 0)

        val dedicated = body.indexOf("R.string.dsh_copy_url_failed")
        assertTrue(
            "★ 复制失败必须弹 `dsh_copy_url_failed`（「复制失败，请重试。」）；" +
                "复用 open_link_failed 会在复制失败时断言'已自动复制链接'，把用户引去粘贴旧内容",
            dedicated > failure
        )
        // 反面：open_link_failed 的 toast 必须落在 **else** 分支里（即"确实复制成功了"），
        // 不能像修复前那样在 if/else 之外无条件弹。
        val legacy = body.indexOf("R.string.open_link_failed")
        assertTrue("找不到 open_link_failed 的 toast", legacy >= 0)
        val elseIdx = body.lastIndexOf("} else {", legacy)
        assertTrue(
            "★ open_link_failed（'已自动复制链接，请手动粘贴'）只能出现在「复制成功」的 else 分支；" +
                "它现在的位置前面找不到 `} else {`，说明仍在无条件弹出 —— 那句话就成了假话",
            elseIdx in (failure + 1) until legacy
        )
    }

    /**
     * `dsh_copy_url_failed` 必须三语齐全 —— 缺一个语种会在该 locale 下退化成英文（或显示原键名）。
     *
     * 这条是**资源侧**的守卫，也是本波唯一能真正"跑"到的检查（读文件即可）。
     * BL-207 已有的三语定义见 `values/`、`values-zh/`、`values-zh-rTW/`。
     */
    @Test
    fun copyUrlFailedStringExistsInAllThreeLocales() {
        for (dir in listOf("values", "values-zh", "values-zh-rTW")) {
            val xml = resDir(dir).readText()
            assertTrue(
                "★ `$dir/strings.xml` 里缺 dsh_copy_url_failed —— " +
                    "该语种下会回落成英文/原键名，比不提示更糟",
                Regex("""<string name="dsh_copy_url_failed">\s*\S""").containsMatchIn(xml)
            )
        }
    }

    // ===== D-2：探测后还原 WebViewClient 失败必须留痕 =========================

    /**
     * `probeMissingApis` 里的 `restoreClient()` 必须记录还原失败。
     *
     * ## 为什么（注释自己点名的"自伤型 bug"）
     *
     * 同文件 `:146-148` 的注释原文：
     * > ★★ 探测**必须**把宿主原来的 WebViewClient 还原回去 —— 否则探测完 loadUrl 真实页面时，
     * > 宿主那一整套 onPageFinished/onReceivedError/401 处理全都没了，页面会加载成功但界面
     * > 永远停在"加载中"（这是个自伤型的诊断 bug，比不诊断更糟）。
     *
     * 而实现是 `runCatching { webView.webViewClient = originalClient }` —— **零日志**。
     * 于是这个"比不诊断更糟"的失败发生时，日志里一个字节都没有：用户报障只会说"一直加载中"，
     * 我们连"是诊断步骤自己弄坏了"这个假设都提不出来。
     *
     * 变异验证：删掉 `.onFailure { ... }` ⇒ 本条与 [restoreClientHasNoBareAssignment] 同时变红。
     */
    @Test
    fun restoreClientLogsFailure() {
        val body = restoreClientBody()
        // 正面：还原之后必须接 onFailure
        assertTrue(
            "★ `webView.webViewClient = originalClient` 必须接 .onFailure —— 注释自己写着" +
                "这条失败会让页面「加载成功但界面永远加载中」，是比不诊断更糟的自伤",
            Regex(
                """webView\.webViewClient = originalClient\s*\}[\s\S]{0,400}?\n\s*\.onFailure""",
            ).containsMatchIn(body)
        )
        // 正面：失败要进日志总线（用户在门禁框前，弹 Toast 会与框抢视线 —— 只记日志是对的）
        assertTrue(
            "★ 还原失败必须进 DshLogBus（带 [webview-compat] 前缀，便于日志页过滤）",
            Regex(
                """\.onFailure \{[\s\S]{0,400}?DshLogBus\.append\(""",
            ).containsMatchIn(body)
        )
        // 正面：日志要带异常本体 —— 光说 "restore failed" 分不出是 render 进程死了还是 view 已 detach
        assertTrue(
            "★ 还原失败的日志要带异常（`\$it`），否则分不出是 WebView 已销毁还是 detach",
            // `$` 在正则里是"输入结尾"锚点，必须转义成 \$ 才是字面量美元符；
            // 而 Kotlin 原始字符串里 `$` 又是模板起点，所以写成 \${'$'}（正则看到的是 \$）。
            Regex("""\.onFailure \{[\s\S]{0,400}?\${'$'}it""").containsMatchIn(body)
        )
    }

    /**
     * 反面断言：`restoreClient()` 里不许留下**结果被丢弃**的赋值。
     *
     * ★ 与 D-1 同理，修复前后那一行 `runCatching { webView.webViewClient = originalClient }`
     *   **逐字相同**，区别全在下一行有没有 `.onFailure`。所以判据是"下一条非空非注释行
     *   是否以 .onFailure 开头"——只看单行会把修好的代码判成没修（假红）。
     */
    @Test
    fun restoreClientHasNoBareAssignment() {
        val body = restoreClientBody()
        val lines = body.lines()
        var bare = 0
        for (i in lines.indices) {
            if (!lines[i].contains("webView.webViewClient = originalClient")) continue
            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].trimStart().startsWith("//"))) j++
            val next = lines.getOrNull(j)?.trimStart() ?: ""
            if (!next.startsWith(".onFailure")) bare++
        }
        assertEquals(
            "★ restoreClient 的赋值后面必须紧跟 .onFailure；实测没有收尾的 $bare 处（应为 0）。" +
                "没有它，探测把宿主 client 弄丢时日志完全空白",
            0, bare
        )
    }

    // ===== 辅助 ============================================================

    /**
     * 取 `restoreClient()` 的函数体（到下一个 `val probeClient` 为止）。
     *
     * 刻意用**边界锚**而不是固定长度窗口：窗口太短会把 `.onFailure` 切到框外造成假红，
     * 太长则会连带把 `probeClient` 里的 `runCatching`（那是另一处，已带 onFailure）算进来，
     * 影响反面断言的计数。锚在 `val probeClient` 上是因为它在 restoreClient 之后紧邻。
     */
    private fun restoreClientBody(): String {
        val src = webViewCompat.readText()
        val start = src.indexOf("fun restoreClient()")
        assertTrue("找不到 restoreClient()（源码结构变了？）", start >= 0)
        val end = src.indexOf("val probeClient", start)
        assertTrue("找不到 restoreClient 的结束锚 `val probeClient`", end > start)
        return src.substring(start, end)
    }

    /**
     * 取某个 Kotlin 声明的**函数体**：从声明处到下一个**同缩进**的顶层声明
     *（`fun` / `class` / `object` / `interface`）为止，找不到就取到文件尾。
     *
     * 为什么不用固定字符窗口：`openLink()` 只有 ~25 行，窗口取 800 字符够用，但一旦有人
     * 在后面加注释就可能在中间切断 —— 那样 `.onFailure` 会恰好落在框外，**修好的代码被判红**。
     * 按缩进锚定与内容长度无关，更稳。
     *
     * ★ 必须同时认 `class`/`object`：`openLink()` 之后紧跟的是 `private inner class AboutAdapter`，
     *   只认 `fun` 的话边界永远找不到，函数体会一路吃到文件尾 —— 那会让反面断言把整个文件的
     *   `runCatching` 都算进来（现在恰好只有 openLink 里有，但这是运气，不是设计）。
     */
    private fun functionBody(file: File, decl: String): String {
        val src = file.readText()
        val start = src.indexOf(decl)
        assertTrue("找不到声明 `$decl`（源码结构变了？）", start >= 0)
        // 声明行首的缩进量
        val lineStart = src.lastIndexOf('\n', start).let { if (it < 0) 0 else it + 1 }
        val indent = src.substring(lineStart, start).length
        var idx = src.indexOf("\n", start)
        while (idx >= 0) {
            val nextLineStart = idx + 1
            val nextEnd = src.indexOf('\n', nextLineStart).let { if (it < 0) src.length else it }
            val line = src.substring(nextLineStart, nextEnd)
            val lead = line.length - line.trimStart().length
            val head = line.trimStart()
            val isDecl = head.startsWith("fun ") || head.startsWith("class ") ||
                head.startsWith("object ") || head.startsWith("interface ")
            if (isDecl && lead <= indent) return src.substring(start, nextLineStart)
            idx = src.indexOf("\n", nextLineStart)
            if (idx < 0) break
        }
        return src.substring(start)
    }

    private fun mainSourceFile(rel: String): File {
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
        throw AssertionError("找不到 com/dsh/$rel（请在仓库根或 FCL/ 目录下运行测试）")
    }

    private fun resDir(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/res/$name/strings.xml"),
                File(dir, "FCL/src/main/res/$name/strings.xml"),
                File(dir, "src/main/res/$name/strings.xml")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("找不到 res/$name/strings.xml（请在仓库根或 FCL/ 目录下运行测试）")
    }
}
