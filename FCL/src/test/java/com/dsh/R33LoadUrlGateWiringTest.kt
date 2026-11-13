package com.dsh

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * round33 / N3 纵深防御第二道：`DshWebViewActivity.loadUrlChecked` 的 URL 门。
 *
 * ## 为什么这里只能做源码级断言
 * `loadUrlChecked` 是 `DshWebViewActivity` 的私有方法，而那是 Android `Activity` ——
 * JVM 单测里既没有 Activity 也没有 WebView，无法调用它观察行为。
 * 本仓既有的同类约束（`R29CopyDeferTest` 对 `GateResume` 分支体、`R31Fix233Test` 对
 * `PtyNative.kt`）都是这个处境，用的是同一个办法：**对源码做结构化断言**。
 *
 * ## 断言的是"位置语义"，不是"某行文字"
 * 真正要钉住的不变式只有一条：**门必须出现在 `if (compatOk)` 直通之前**。
 * 因为那条 `webView.loadUrl(url)` 是门禁系统的放行通道 —— 门放在它后面等于没加。
 * 只断言"文件里有 webUiUrlAllowed"是不够的：它可能被放在永远走不到的分支里。
 *
 * ## 判别力
 * 把门挪到 `if (compatOk) { webView.loadUrl(url); return }` 之后，
 * [urlGateMustRunBeforeTheCompatPassthrough] 立刻变红。
 */
class R33LoadUrlGateWiringTest {

    private fun mainSourceText(): String {
        val file = resolveMainSource()
            ?: throw AssertionError(
                "找不到 ui/DshWebViewActivity.kt：请在仓库根或 FCL/ 目录下运行测试" +
                    "（兼容 FCL/FCL/ FCL/ src/main/ 三种布局）"
            )
        return file.readText()
    }

    private fun resolveMainSource(): File? {
        val rel = "ui/DshWebViewActivity.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            val candidates = listOf(
                File(dir, "FCL/FCL/src/main/java/com/dsh/$rel"),
                File(dir, "FCL/src/main/java/com/dsh/$rel"),
                File(dir, "src/main/java/com/dsh/$rel")
            )
            candidates.firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    /** 切出 `loadUrlChecked` 的方法体（从签名到与其配对的那个右花括号）。 */
    private fun loadUrlCheckedBody(text: String): String {
        val start = text.indexOf("private fun loadUrlChecked(")
        if (start < 0) throw AssertionError("找不到 loadUrlChecked 的声明 —— 方法被改名或删了？")
        // 从签名后的第一个 '{' 开始，按花括号深度找配对
        val braceStart = text.indexOf('{', start)
        if (braceStart < 0) throw AssertionError("loadUrlChecked 没有方法体？")
        var depth = 0
        var i = braceStart
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(braceStart, i + 1)
                }
            }
            i++
        }
        throw AssertionError("loadUrlChecked 的花括号没有闭合 —— 解析失败")
    }

    /**
     * **核心不变式**：门必须在 `compatOk` 直通之前。
     *
     * `if (compatOk) { webView.loadUrl(url); return }` 是全文件唯一一处无条件放行，
     * 门落在它后面 = 所有 compatOk 之后的加载都绕过门。
     */
    @Test
    fun urlGateMustRunBeforeTheCompatPassthrough() {
        val body = loadUrlCheckedBody(mainSourceText())
        val gateIdx = body.indexOf("webUiUrlAllowed(")
        val passIdx = body.indexOf("if (compatOk)")
        assertTrue(
            "loadUrlChecked 里必须调用 webUiUrlAllowed( —— 否则 N3 的纵深防御第二道没了。" +
                "方法体：\n$body",
            gateIdx >= 0
        )
        assertTrue(
            "loadUrlChecked 里必须还有 if (compatOk) 直通（找不到说明结构变了，本断言需复核）",
            passIdx >= 0
        )
        assertTrue(
            "webUiUrlAllowed( 必须出现在 if (compatOk) **之前** —— " +
                "compatOk 分支里的 webView.loadUrl(url) 是唯一无条件放行通道，门放在它后面等于没加。" +
                "实际 gateIdx=$gateIdx passIdx=$passIdx",
            gateIdx < passIdx
        )
    }

    /** 被拒时必须清 `loadedUrl` 并摆出可见面板 —— 否则用户停在一个空白页上。 */
    @Test
    fun blockedUrlLeavesNoBlankPage() {
        val body = loadUrlCheckedBody(mainSourceText())
        val gateIdx = body.indexOf("webUiUrlAllowed(")
        assertTrue("先决条件：门必须存在", gateIdx >= 0)
        // 取门之后到下一个 return 为止的那一小段
        val tail = body.substring(gateIdx)
        val upToReturn = tail.substring(0, minOf(tail.length, tail.indexOf("return").let {
            if (it < 0) tail.length else it + "return".length
        }))
        assertTrue(
            "被拒分支必须把 loadedUrl 复位成 null（否则 onStart 回放会拿着它再试一次）。实际片段：\n$upToReturn",
            upToReturn.contains("loadedUrl = null")
        )
        assertTrue(
            "被拒分支必须调用 showError( —— 本页任何路径都不许以空白收场。实际片段：\n$upToReturn",
            upToReturn.contains("showError(")
        )
    }

    /**
     * **不许重抄第二份 host 清单。**
     *
     * `adoptionCandidateAllowed` 的 KDoc 亲口警告过"某一级少一道门"的口径漂移。
     * 如果这里写出了 `host == "127.0.0.1" || ...` 这样的字面清单，说明有人绕过了
     * `webUiUrlAllowed` 自己判；那样两处清单迟早不同步。
     */
    @Test
    fun gateDelegatesInsteadOfDuplicatingTheLoopbackList() {
        val body = loadUrlCheckedBody(mainSourceText())
        assertTrue(
            "门必须走 webUiUrlAllowed(（单一判据），不得在本方法里重抄回环清单。" +
                "方法体：\n$body",
            body.contains("webUiUrlAllowed(")
        )
        // 方法体里出现 "127.0.0.1" 字面量即视为重抄（注释里提到不算：只查含 == 或 != 的判定行）
        val duplicated = body.lines().any { line ->
            line.contains("127.0.0.1") &&
                (line.contains("==") || line.contains("!=")) &&
                !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
        }
        assertTrue(
            "loadUrlChecked 的方法体里出现了手写的回环地址判定行 —— 应改为调用 webUiUrlAllowed。实际：\n" +
                body.lines().filter { it.contains("127.0.0.1") }.joinToString("\n"),
            !duplicated
        )
    }
}
