package com.dsh

import com.dsh.core.DshAuthCookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R30/BL-223：dsh 会话 cookie 名推导的测试。
 *
 * ★ 基准值来自**实测**，不是推出来的：
 * 在 Windows 上真跑 dsh 0.2.1-rc.2（端口 2947），服务端 `Set-Cookie` 原文是
 * ```
 * dsh-auth-f0JycOr99OayAPcidrODALaUCTlmz9QaSl2zNiSv-Ic=v1....
 * ```
 * 所以 `nameFor("127.0.0.1:2947")` 必须**逐字**等于那个名字。
 * 这条断言是整份诊断的地基 —— 名字算法错了，"cookie 在不在"的结论就全错。
 */
class R30AuthCookieTest {

    private val realNameFromDevice =
        "dsh-auth-f0JycOr99OayAPcidrODALaUCTlmz9QaSl2zNiSv-Ic"

    @Test
    fun testNameMatchesRealServerValue() {
        assertEquals(realNameFromDevice, DshAuthCookie.nameFor("127.0.0.1:2947"))
    }

    /** 名字是 64 位 sha256 的 base64url（无填充），前缀固定 */
    @Test
    fun testNameShape() {
        val n = DshAuthCookie.nameFor("127.0.0.1:38501")
        assertTrue("应以 dsh-auth- 开头", n.startsWith("dsh-auth-"))
        assertEquals("dsh-auth- 之后应是 43 字符（sha256 base64url 无填充）", 43, n.length - 9)
        assertFalse("base64url 不该有 +", n.contains('+'))
        assertFalse("base64url 不该有 /", n.contains('/'))
        assertFalse("无填充，不该有 =", n.contains('='))
    }

    /** ★ 端口是 authority 的一部分 —— 换端口名字整体变掉（"换端口后立刻 401"的根源） */
    @Test
    fun testPortChangesName() {
        val a = DshAuthCookie.nameFor("127.0.0.1:38501")
        val b = DshAuthCookie.nameFor("127.0.0.1:38502")
        assertNotEquals("端口不同必须得到不同的 cookie 名", a, b)
    }

    /** host 变了名字也变 */
    @Test
    fun testHostChangesName() {
        assertNotEquals(
            DshAuthCookie.nameFor("127.0.0.1:38501"),
            DshAuthCookie.nameFor("localhost:38501")
        )
    }

    /** 同样的 authority 必须稳定（不能有随机性） */
    @Test
    fun testDeterministic() {
        repeat(5) {
            assertEquals(
                DshAuthCookie.nameFor("127.0.0.1:38501"),
                DshAuthCookie.nameFor("127.0.0.1:38501")
            )
        }
    }

    @Test
    fun testHasSessionCookie() {
        val authority = "127.0.0.1:2947"
        val header = "$realNameFromDevice=v1.abc.def; other=1"
        assertTrue("应能认出会话 cookie", DshAuthCookie.hasSessionCookie(header, authority))
        assertFalse("空头", DshAuthCookie.hasSessionCookie(null, authority))
        assertFalse("空白头", DshAuthCookie.hasSessionCookie("   ", authority))
        assertFalse(
            "只有别的 cookie",
            DshAuthCookie.hasSessionCookie("other=1; another=2", authority)
        )
        assertFalse(
            "端口不匹配 ⇒ 认不出（这正是 BL-223 要诊断的场景）",
            DshAuthCookie.hasSessionCookie(header, "127.0.0.1:38501")
        )
    }

    @Test
    fun testSessionCookieNames() {
        val header = "$realNameFromDevice=v1.x; foo=bar; dsh-auth-other=v1.y"
        val names = DshAuthCookie.sessionCookieNames(header)
        assertEquals(2, names.size)
        assertTrue(names.contains(realNameFromDevice))
        assertTrue(names.contains("dsh-auth-other"))
        assertEquals(0, DshAuthCookie.sessionCookieNames(null).size)
    }

    /** cookie 值里的 `=` 不能把名字解析歪（base64 填充常见） */
    @Test
    fun testValueWithEqualsSign() {
        val header = "$realNameFromDevice=v1.abc.def=="
        assertTrue(DshAuthCookie.hasSessionCookie(header, "127.0.0.1:2947"))
    }
}
