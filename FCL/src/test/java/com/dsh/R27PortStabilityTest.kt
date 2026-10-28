package com.dsh

import com.dsh.core.PORT_RANGE_SIZE
import com.dsh.core.PORT_RANGE_START
import com.dsh.core.preferredPort
import com.dsh.core.seqOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R27 / P0-C：新实例的**首选端口**必须稳定，不再一律 `0`（系统随机）。
 *
 * ## 为什么这条要钉
 *
 * dsh 的会话 cookie 名里编进了 `host:port`（`browser-auth.ts:106-107`：
 * `cookieName = 'dsh-auth-' + base64url(sha256(authority))`）。
 * 端口一变 ⇒ cookie 名变 ⇒ 浏览器里那条 30 天有效的 cookie **直接作废**，
 * 外部浏览器只能退回一次性 token，而 token 每次进程启动都会重新生成
 * （`browser-auth.ts:52-58`）⇒ 必然过期 ⇒ 又慢又选不了目录。
 *
 * 所以"给每个实例一个稳定端口"是让「复制链接给浏览器用」这件事成立的前提。
 * 这条逻辑错了的症状是**静默的**（页面照样能开，只是浏览器永远要重新认证），
 * 只能靠正面断言钉住。
 */
class R27PortStabilityTest {

    /** 稳定：同一个 seq 反复算，必须得同一个端口。 */
    @Test
    fun sameSeqAlwaysYieldsSamePort() {
        val first = preferredPort(7, emptySet())
        val second = preferredPort(7, emptySet())
        assertEquals("同一个实例序号必须得到同一个端口（核心目的：让 cookie 名跨重启不变）", first, second)
        assertTrue("端口必须落在首选段内", first in PORT_RANGE_START until (PORT_RANGE_START + PORT_RANGE_SIZE))
    }

    /** 不撞：不同 seq 必须得到不同端口（否则两个实例互相抢，启动时必有一个被占）。 */
    @Test
    fun differentSeqYieldsDifferentPort() {
        val ports = (1..20).map { preferredPort(it, emptySet()) }
        assertEquals(
            "20 个不同序号必须得到 20 个互不相同的端口（否则同机多实例必然抢端口）",
            ports.size,
            ports.toSet().size
        )
    }

    /** 已占用的端口必须被跳过（这是"线性探测"的全部意义）。 */
    @Test
    fun takenPortsAreSkipped() {
        val base = preferredPort(3, emptySet())
        val withClash = preferredPort(3, setOf(base))
        assertNotEquals("首选端口已被占用时必须换一个", base, withClash)
        assertTrue(
            "换到的端口也必须落在首选段内（不能漂到任意端口）",
            withClash in PORT_RANGE_START until (PORT_RANGE_START + PORT_RANGE_SIZE)
        )
    }

    /** 连续占用也要能绕开（验证探测是循环的，不是只试一次）。 */
    @Test
    fun consecutiveClashesAreSkipped() {
        val base = preferredPort(5, emptySet())
        val taken = (base until base + 10).toSet()
        val got = preferredPort(5, taken)
        assertTrue("连续占 10 个之后仍要能找到一个空闲端口", got !in taken)
        assertTrue(got in PORT_RANGE_START until (PORT_RANGE_START + PORT_RANGE_SIZE))
    }

    /**
     * 段内全占满 ⇒ **回落 0**（交给系统），而不是抛异常或返回一个假端口。
     *
     * 这条是"宁可这次不稳定，也不要让新建实例失败"的显式表达。
     */
    @Test
    fun fullyExhaustedRangeFallsBackToZero() {
        val full = (PORT_RANGE_START until PORT_RANGE_START + PORT_RANGE_SIZE).toSet()
        assertEquals(
            "整段占满时必须回落 0（交给系统分配），不能让新建实例失败",
            0,
            preferredPort(1, full)
        )
    }

    /** seq <= 0（id 形态意外 / 解析失败）⇒ 0，不崩。 */
    @Test
    fun nonPositiveSeqFallsBackToZero() {
        assertEquals(0, preferredPort(0, emptySet()))
        assertEquals(0, preferredPort(-1, emptySet()))
    }

    /** `seqOf` 要从真实 id 形态里取出序号；取不到时给 0（由 preferredPort 回落）。 */
    @Test
    fun seqOfParsesRealIdShape() {
        assertEquals("inst-1700000000000-42-7f2a 的序号是 42", 42, seqOf("inst-1700000000000-42-7f2a"))
        assertEquals("形态不符时给 0（触发回落，不崩）", 0, seqOf("garbage"))
        assertEquals("空 id 给 0", 0, seqOf(""))
        assertEquals("序号非数字时给 0", 0, seqOf("inst-1700000000000-abc-7f2a"))
    }

    /**
     * 端到端不变量：`buildId` 产出的 id 与 `seqOf` 必须互相认得。
     *
     * 这两处是**跨函数契约**（`buildId` 拼串、`seqOf` 拆串），任一方改了分隔规则而不改另一方，
     * 症状是"所有实例的序号都解析成 0 ⇒ 全部回落到随机端口"——静默失效，没有任何现有测试会红。
     */
    @Test
    fun buildIdAndSeqOfAgree() {
        for (seq in listOf(1, 7, 42, 999)) {
            val id = com.dsh.core.DshInstances.buildId(1700000000000L, seq, 0x7f2a)
            assertEquals("buildId/seqOf 的分隔规则必须一致（seq=$seq，id=$id）", seq, seqOf(id))
        }
    }
}
