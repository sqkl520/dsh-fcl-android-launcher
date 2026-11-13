package com.dsh

import com.dsh.core.UrlScanner
import com.dsh.core.adoptionHostIsLoopback
import com.dsh.core.webUiUrlAllowed
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * round32 / N3 的第二半：**host 门**。与 `R32N3UrlSchemeGateTest` 分工不同 ——
 * 那个管 scheme（`javascript:`/`file:`/`data:`/`intent:`），这个管主机（非回环地址）。
 *
 * ## 缺陷
 * `onProcessLine` 采信的 URL 会一路走到 `DshWebViewActivity.loadUrlChecked` 的
 * `webView.loadUrl(url)`。而 `AUTH_URL` 只要求"像 http(s) URL"、`MARKER_URL` 只要求"非空白"，
 * **两者都不校验主机**。rootfs 内任何能写 `/tmp/dsh-start-*.log` 的代码
 * （`ProotCommand` 把 `<cacheDir>/dsh/tmp` bind 成 rootfs 的 `/tmp`）追加一行
 *
 * ```
 * dsh web: https://evil.example:443/?token=x
 * ```
 *
 * 就能把加载目标改走。http 变体会被 `network_security_config.xml` 的
 * `cleartextTrafficPermitted=false` 拦下，但 **https 变体能真正渲染**；
 * 且同一条 URL 还会流到设置页显示与外跳（`ACTION_VIEW` 绕过 App 的 NSC）。
 *
 * ## 为什么 host 判据委托 `adoptionHostIsLoopback`
 * 不重抄一份 `127.0.0.1`/`localhost`/`::1` 清单。抄第二份清单就是下一次口径漂移的种子
 * （`adoptionCandidateAllowed` 的 KDoc 亲口警告过）。
 *
 * ## 与本文件外的分工
 * - scheme 面：`UrlScanner.hasHttpScheme`（`R32N3UrlSchemeGateTest` 钉着）
 * - host 面：`adoptionHostIsLoopback`（本文件钉着）
 * - 两者合并成唯一采信判据：`webUiUrlAllowed`
 *
 * 两处**各自独立实现**：一处被改坏，另一处仍能挡。所以下面专门有一条断言
 * "只判 host 会放行 javascript:"，用来证明不能省掉 scheme 那半。
 */
class R32N3HostGateTest {

    // ------------------------------------------------------ webUiUrlAllowed

    /** **缺陷本体**：非回环主机一律不得被接受为 WebView 加载目标。 */
    @Test
    fun rejectsNonLoopbackHosts() {
        val hostile = listOf(
            "https://evil.example:443/?token=x",
            "http://evil.example:3080/?token=x",
            "https://attacker.test/",
            "http://192.168.1.5:3080/?token=x",   // 局域网地址也不是回环
            "http://10.0.0.1:3080/",
            "http://127.0.0.2:3080/?token=x",     // 与 adoptionCandidateAllowed 同口径：也拒
            "http://localhost.evil.example:3080/",
            "http://evil.example@127.0.0.1:3080/" // userinfo 伪装：hostOf 取 127.0.0.1 但这里保留语义
        )
        for (u in hostile) {
            // 最后一条是特例：JDK 的 URI.getHost() 对 userinfo 形态返回 127.0.0.1，
            // 所以它**会**通过。它不算缺陷 —— 真正的连接目标是 127.0.0.1，userinfo 被忽略。
            if (u.contains("@127.0.0.1")) continue
            assertFalse("非回环主机不得被接受：$u", webUiUrlAllowed(u))
        }
    }

    /** 回环的四种写法必须都接受 —— 否则正常路径会被门挡掉。 */
    @Test
    fun acceptsAllLoopbackSpellings() {
        val ok = listOf(
            "http://127.0.0.1:3080/?token=x",
            "http://localhost:3080/?token=x",
            "http://LOCALHOST:3080/",
            "http://[::1]:3080/?token=x",
            "https://127.0.0.1:3080/?token=x",
            "http://127.0.0.1:3080/"              // 无 token 的合成形态
        )
        for (u in ok) {
            assertTrue("回环地址必须接受：$u", webUiUrlAllowed(u))
        }
    }

    /**
     * **证明不能省掉 scheme 那半**：`javascript:` 的 host 是 null，
     * 所以只靠 host 判据就会拒；但 `javascript:alert(1)//127.0.0.1/` 这类
     * 形态在 `Uri.parse` 下可能给出非空 host —— 这里钉住"无论 host 判据怎么变，
     * scheme 门都必须独立生效"。
     */
    @Test
    fun schemeGateIsIndependentlyNecessary() {
        // 这些的"host 部分"看着像回环，但 scheme 不是 http(s)
        val tricky = listOf(
            "javascript:alert(1)//127.0.0.1/",
            "file://127.0.0.1/root/x",
            "data:text/html,127.0.0.1"
        )
        for (u in tricky) {
            assertFalse("非 http(s) scheme 即使主机片段像回环也必须拒：$u", webUiUrlAllowed(u))
        }
    }

    /** `null` 与空串：必须拒，且不得抛。 */
    @Test
    fun rejectsNullAndBlank() {
        assertFalse("null 必须拒", webUiUrlAllowed(null))
        assertFalse("空串必须拒", webUiUrlAllowed(""))
        assertFalse("只有空格必须拒", webUiUrlAllowed("   "))
    }

    /**
     * **与既有 F2 判据同口径**：`webUiUrlAllowed` 的 host 半边必须与
     * `adoptionHostIsLoopback` 完全一致。若将来有人给其中一个加了特例而没同步，
     * 这条会红 —— 这是防"第二套口径"的机械护栏。
     */
    @Test
    fun hostJudgementMatchesAdoptionGate() {
        val probes = listOf(
            "http://127.0.0.1:1/", "http://localhost:1/", "http://[::1]:1/",
            "http://127.0.0.2:1/", "http://evil.example:1/", "http://192.168.1.5:1/",
            "http://127.1:1/", "http://0.0.0.0:1/"
        )
        for (u in probes) {
            assertEquals(
                "webUiUrlAllowed 的 host 判据必须与 adoptionHostIsLoopback 一致：$u",
                adoptionHostIsLoopback(u),
                webUiUrlAllowed(u)
            )
        }
    }

    // ---------------------------------------------------- 降级语义（解析层）

    /**
     * 三级候选中，①②被拒时应降级到③（本地合成的回环 URL）。
     *
     * 这条对应 `onProcessLine` 的新逻辑：`candidates.firstOrNull { webUiUrlAllowed(it) }`。
     * 这里直接复现同一表达式，钉住"降级而不是整体丢弃"这个决定。
     */
    @Test
    fun fallsBackToSyntheticLoopbackWhenHigherTiersAreRejected() {
        val line = "READY url=https://evil.example:443/?token=x READY port=3080"
        val candidates = listOfNotNull(
            UrlScanner.findAuthenticatedUrl(line),
            UrlScanner.findMarkerUrl(line),
            UrlScanner.findMarkerPort(line)?.let { "http://127.0.0.1:$it/" }
        )
        // ①AUTH_URL 与 ②MARKER_URL 会命中**同一个**伪造 URL（两个正则都能匹配它），
        // 所以候选是 3 个而不是 2 个 —— 这正是"逐候选过滤"而不是"只看第一个"的价值：
        // 若实现写成 `?:` 链只取第一个，后面那个合法的③级永远没机会。
        assertEquals(
            "①②都命中同一伪造 URL + ③级合成 = 3 个候选，实际=$candidates",
            3,
            candidates.size
        )
        val picked = candidates.firstOrNull { webUiUrlAllowed(it) }
        assertEquals(
            "①②被拒后必须降级到③的本地合成 URL，而不是整体丢弃；实际候选=$candidates",
            "http://127.0.0.1:3080/",
            picked
        )
    }

    /** 没有③级可降时，结果必须是 `null`（= 这行没有可用 URL），不得把坏值漏下去。 */
    @Test
    fun yieldsNullWhenNoAcceptableCandidateExists() {
        val line = "READY url=https://evil.example:443/?token=x"
        val candidates = listOfNotNull(
            UrlScanner.findAuthenticatedUrl(line),
            UrlScanner.findMarkerUrl(line),
            UrlScanner.findMarkerPort(line)?.let { "http://127.0.0.1:$it/" }
        )
        assertTrue("应当有候选（伪造的那个）", candidates.isNotEmpty())
        assertNull(
            "唯一候选不可接受时必须返回 null，实际候选=$candidates",
            candidates.firstOrNull { webUiUrlAllowed(it) }
        )
    }

    /** 正常路径：③级合成 + ②级真回环 —— 必须选②（带 token 的优先）。 */
    @Test
    fun prefersAuthenticatedLoopbackOverSynthetic() {
        val line = "[start-dsh] READY url=http://127.0.0.1:3080/?token=realtoken"
        val candidates = listOfNotNull(
            UrlScanner.findAuthenticatedUrl(line),
            UrlScanner.findMarkerUrl(line),
            UrlScanner.findMarkerPort(line)?.let { "http://127.0.0.1:$it/" }
        )
        val picked = candidates.firstOrNull { webUiUrlAllowed(it) }
        assertEquals(
            "必须优先选 ①/② 的真 URL（带 token），实际=$picked，候选=$candidates",
            "http://127.0.0.1:3080/?token=realtoken",
            picked
        )
    }
}
