package com.dsh

import com.dsh.ui.DshWebViewCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R30/BL-223：门禁的 API 探测结果解析。
 *
 * ## 为什么这段值得单独测
 *
 * `evaluateJavascript` 的回包是**JSON 字面量**，不是裸字符串：
 * 字符串结果被包成 `"..."`，空结果可能是 `null` 也可能是 `""`。
 *
 * 这段解析写错的后果不是崩溃，而是**门禁框里出现一句怪话**：
 * - 漏了去引号 ⇒ `"Promise.withResolvers (needs 119+)"` （带引号显示给用户）
 * - 漏了还原分隔符 ⇒ 两项粘连成一串
 * - `null` 没处理 ⇒ 显示 "null"
 *
 * 用户看到的就是"这个 App 连报错都报不利索"，而我们失去的是一次可信的诊断。
 *
 * 基准值来自真机实测（HONOR X10 Max / WebView 114）：
 * ```
 * [typeof Promise.withResolvers, typeof AbortSignal.any].join(" | ")
 * → "undefined | undefined"
 * ```
 * 即两个 API 都缺 ⇒ 回包应该是两项。
 */
class R30ProbeParseTest {

    private val sep = "\u0001"

    /** 真机形态：两个 API 都缺，JSON 字符串字面量包裹 */
    @Test
    fun testBothMissing() {
        val raw = "\"Promise.withResolvers (needs 119+)${sep}AbortSignal.any (needs 116+)\""
        val got = DshWebViewCompat.parseProbeResult(raw)
        assertEquals(2, got.size)
        assertEquals("Promise.withResolvers (needs 119+)", got[0])
        assertEquals("AbortSignal.any (needs 116+)", got[1])
    }

    /** WebView 里 `\u0001` 会被 JSON 转义成字面的反斜杠 u 序列 */
    @Test
    fun testEscapedSeparator() {
        val raw = "\"Promise.withResolvers (needs 119+)\\u0001AbortSignal.any (needs 116+)\""
        val got = DshWebViewCompat.parseProbeResult(raw)
        assertEquals(2, got.size)
        assertTrue(got[0].startsWith("Promise.withResolvers"))
        assertTrue(got[1].startsWith("AbortSignal.any"))
    }

    /** 只缺一个（WebView 116~118 的情形：AbortSignal.any 有，withResolvers 没有） */
    @Test
    fun testOnlyOneMissing() {
        val got = DshWebViewCompat.parseProbeResult("\"Promise.withResolvers (needs 119+)\"")
        assertEquals(1, got.size)
        assertEquals("Promise.withResolvers (needs 119+)", got[0])
    }

    /** 都支持 ⇒ 空串 */
    @Test
    fun testNoneMissing() {
        assertEquals(0, DshWebViewCompat.parseProbeResult("\"\"").size)
        assertEquals(0, DshWebViewCompat.parseProbeResult("").size)
        assertEquals(0, DshWebViewCompat.parseProbeResult("   ").size)
    }

    /** evaluateJavascript 在没有返回值时给 "null" */
    @Test
    fun testNullLiteral() {
        assertEquals(0, DshWebViewCompat.parseProbeResult("null").size)
        assertEquals(0, DshWebViewCompat.parseProbeResult(null).size)
    }

    /** 探测脚本自身崩了 ⇒ JS 侧给的是错误对象字符串，不应被当成"缺两项" */
    @Test
    fun testGarbageIsNotTreatedAsMissing() {
        // 不崩溃；要么空，要么给出原样的一项（两者都能接受，但不能凭空造出 2 项）
        val got = DshWebViewCompat.parseProbeResult("\"TypeError: x\"")
        assertTrue("不该凭空造出两项", got.size <= 1)
    }

    /** 去引号必须成对 —— 只有一个引号时不要吞掉字符 */
    @Test
    fun testSingleQuoteNotStripped() {
        val got = DshWebViewCompat.parseProbeResult("\"only-open")
        // 末位不是引号 ⇒ 整串保留（首引号留在里面），至少不能变成空
        assertEquals(1, got.size)
        assertTrue(got[0].isNotEmpty())
    }

    /** 前后空白不该污染结果 */
    @Test
    fun testTrimsWhitespace() {
        val got = DshWebViewCompat.parseProbeResult("  \"A${sep}B\"  ")
        assertEquals(2, got.size)
        assertEquals("A", got[0])
        assertEquals("B", got[1])
    }

    /** 空片段被丢弃（join 出错时可能产生连续分隔符） */
    @Test
    fun testEmptySegmentsDropped() {
        val got = DshWebViewCompat.parseProbeResult("\"A${sep}${sep}B\"")
        assertEquals(2, got.size)
    }
}
