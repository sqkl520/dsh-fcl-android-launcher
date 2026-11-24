package com.dsh

import com.dsh.ui.redactFrontendLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R49 / F-6：前端 console 脱敏的**形态覆盖**。
 *
 * R2 安全验收实测发现第一版漏了 `sk-proj-…`（`.` 不在字符类里），并给出六条实测用例。
 * 这组测试把那些用例固化下来 —— 每一条都是"key 原样落日志"与"已脱敏"的判别点。
 */
class R49RedactProbeTest {

    @Test
    fun skProjKeysWithDotsAreRedacted() {
        val out = redactFrontendLog("key=sk-proj-abc.defghijklmnopqrstuvwxyz")
        assertFalse("★ sk-proj-…（带点）必须被脱敏 —— 第一版字符类不含 . 所以漏了", out.contains("defghijklmnop"))
        assertTrue("前缀应保留（排障时能辨认形态）", out.contains("sk-"))
    }

    @Test
    fun googleKeysAreRedacted() {
        val out = redactFrontendLog("google AIzaSyD-1234567890abcdefghijklmnopq")
        assertFalse("★ AIza… 形态必须被脱敏（dsh 可接 Gemini）", out.contains("SyD-1234567890"))
    }

    @Test
    fun jsonApiKeyFieldIsRedacted() {
        val out = redactFrontendLog("""{"apiKey":"abcdefghijklmnopqrstuvwx"}""")
        assertFalse(
            "★ JSON 字段形态必须被脱敏 —— 值里没有 sk- 前缀，单靠 SK_KEY_RE 抓不到。" +
                "实际输出：<$out>",
            out.contains("abcdefghijklmnop")
        )
        assertTrue("字段名应保留（排障时知道是哪个键）", out.contains("apiKey"))
    }

    @Test
    fun headerApiKeyIsRedacted() {
        val out = redactFrontendLog("header X-Api-Key: 0123456789abcdef0123456789abcdef")
        assertFalse("★ X-Api-Key: 形态必须被脱敏，实际输出：<$out>", out.contains("0123456789abcdef"))
    }

    @Test
    fun plainSkKeyStillWorks() {
        val out = redactFrontendLog("request failed, key=sk-1234567890abcdefghij done")
        assertFalse("原有形态不许回退", out.contains("1234567890abcdefghij"))
        assertTrue("非凭据部分应保留", out.contains("request failed"))
    }

    @Test
    fun bearerTokenStillWorks() {
        val out = redactFrontendLog("Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345")
        assertFalse("原有形态不许回退", out.contains("abcdefghijklmnopqrstuvwxyz012345"))
    }

    /** 反面：正常文本不许被误伤（脱敏过宽会让 [web] 这一路失去排障价值）。 */
    @Test
    fun ordinaryTextIsNotMangled() {
        val plain = "loaded 3 assets in 120ms, traceId=abc123def456"
        assertEquals("普通日志必须原样保留", plain, redactFrontendLog(plain))
        val shortField = """{"apiKey":"none"}"""
        assertEquals("短值（占位）不该被打码", shortField, redactFrontendLog(shortField))
    }
}
