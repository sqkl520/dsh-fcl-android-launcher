package com.dsh

import com.dsh.core.UrlScanner
import com.dsh.core.adoptionPortMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R21/fix8 W1-E（BL-176）：认领孤儿时 ①pid 文件 / ②侧车 候选的**端口一致性门**
 * （[adoptionPortMatches] 纯函数）的契约测试。
 *
 * ## 为什么测这个
 * [com.dsh.core.DshRuntime.adoptOrphan] 的三源恢复里，③级
 * `ProcessUtil.recoverTokenUrlsFromStartLogs` 一直按 `expectedPort` 过滤候选，
 * 而 ①② 级过去只做探活 3xx 判定、不比对端口 —— 口径不一致（BL-176）。
 * 修复把"端口一致性判定"抽成顶层纯函数（同 `syntheticUrlWouldOverwriteToken` /
 * `pidFileBelongsTo` 的惯例：单测 harness 不加载 android 依赖），①②③ 共用同一判据。
 *
 * ## 测不了的部分
 * tryCandidate 闭包本体（依赖 [com.dsh.core.Probe] 真实网络探活与认领现场）与
 * 跨实例串台的端到端场景 —— 需真机 + 两个并发实例（BUGLEDGER R21 取证缺口 #4）。
 * 这里钉的是判定核：给定候选与目标端口，采信/拒绝必须精确。
 */
class R21FixETest {

    // --- 主用例：端口一致采信、不一致拒绝 ---

    @Test
    fun candidateWithSamePortIsAccepted() {
        assertTrue(
            "候选端口 == 目标端口 → 采信（BL-176 主用例：正常同代次认领）",
            adoptionPortMatches("http://127.0.0.1:46501/?token=abc123", 46501)
        )
    }

    @Test
    fun candidateWithDifferentPortIsRejected() {
        // 受害现场：pid 文件/侧车里存的是**旧端口**的 URL，旧端口后来被别的监听者占用——
        // 即便探活 3xx，也不属于本实例当前认领的端口，必须拒绝（此前①②级会放行）。
        assertFalse(
            "候选端口 != 目标端口 → 拒绝（BL-176 核心修复点）",
            adoptionPortMatches("http://127.0.0.1:3080/?token=abc123", 46501)
        )
        // 反方向同样拒绝：候选端口比目标新（端口漂移后侧车未清的旧值场景对称）
        assertFalse(
            "候选端口高于目标端口同样拒绝",
            adoptionPortMatches("http://127.0.0.1:51000/?token=abc123", 50000)
        )
    }

    // --- 取严边界：判不出端口 = 不采信 ---

    @Test
    fun nullOrBlankCandidateIsRejected() {
        assertFalse("null 候选不采信", adoptionPortMatches(null, 46501))
        assertFalse("空串不采信", adoptionPortMatches("", 46501))
        assertFalse("纯空白不采信", adoptionPortMatches("   ", 46501))
    }

    @Test
    fun bareUrlWithoutPortIsRejected() {
        // 裸 host 无 :port —— portOf 拿不到端口，无从证明归属，取严拒绝
        assertFalse(
            "无端口候选不采信",
            adoptionPortMatches("http://127.0.0.1/?token=abc123", 46501)
        )
        assertFalse(
            "非 URL 文本不采信",
            adoptionPortMatches("not a url at all", 46501)
        )
    }

    @Test
    fun nonPositiveExpectedPortIsRejected() {
        // 目标端口本身不可信（<=0）→ 比对无意义，一律不采信。
        // adoptOrphan 现场有 `port <= 0` 早退兜底，这里是纯函数自身的取严契约。
        assertFalse("expectedPort=0 不采信", adoptionPortMatches("http://127.0.0.1:0/?token=x", 0))
        assertFalse("expectedPort<0 不采信", adoptionPortMatches("http://127.0.0.1:46501/?token=x", -1))
    }

    // --- ?token= 干扰下 portOf 的正确性（门的解析地基）---

    @Test
    fun tokenQueryDoesNotDisturbPortExtraction() {
        // token 值本身是数字形态、或 token 参数在路径/多参数中间 —— portOf 必须仍取 host:port
        val cases = listOf(
            "http://127.0.0.1:46501/?token=8080" to 46501,          // token 数字串形似端口
            "http://127.0.0.1:46501/?a=1&token=xyz&b=2" to 46501,   // token 夹在多参数中间
            "http://127.0.0.1:46501/path/?token=abc" to 46501,      // 带路径
            "http://localhost:46501/?token=abc" to 46501,           // 非 IP host
        )
        for ((url, expected) in cases) {
            val actual: Int? = UrlScanner.portOf(url)
            assertEquals("portOf 应只取 host:port 段: $url", expected as Int?, actual)
            assertTrue("带 ?token= 干扰的候选按端口采信: $url", adoptionPortMatches(url, expected))
            assertFalse("同候选对上别的端口仍拒绝: $url", adoptionPortMatches(url, expected + 1))
        }
    }

    // --- 与 ③级恢复的口径一致性（同一判据、同一基准值）---

    @Test
    fun sameCriterionAsTierThreeStartLogFilter() {
        // ③级 recoverTokenUrlsFromStartLogs 的过滤写成 `UrlScanner.portOf(url) == expectedPort`；
        // 本门必须与它逐值一致 —— 否则修复反而制造新的口径漂移。钉住三个代表形态：
        val acceptedByTier3 = "http://127.0.0.1:3080/?token=xyz" // portOf==3080 → ③级留
        assertTrue(adoptionPortMatches(acceptedByTier3, 3080))
        // portOf 为 null（无端口行）→ ③级 `== expectedPort` 不成立被滤掉，本门同样拒绝
        assertFalse(adoptionPortMatches("http://127.0.0.1/", 3080))
        // ③级 expectedPort<=0 时同样不可能留候选（portOf 只在 >0 时返回），本门取严一致
        assertFalse(adoptionPortMatches(acceptedByTier3, 0))
    }
}
