package com.dsh

import com.dsh.core.subresourceOriginAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R49 / S-01 补：**子资源 origin 门**（R2-SECURITY §1.4 的真洞）。
 *
 * ## 为什么必须有这一层
 *
 * `shouldOverrideUrlLoading` 只管导航，子资源（`<img>` / `<script>` / `fetch`）不走它 ——
 * 所以 S-01 修好的端口门对它们完全无效。而出货前端真的会渲染远程图片
 * （`<img src={e} loading="lazy">`，`src` 无 host/port 限制），出货 `index.html` 也无 CSP。
 *
 * 于是：rootfs 内进程在 `127.0.0.1:9999` 起监听 + LLM 回复带
 * `![](http://127.0.0.1:9999/steal.png)` ⇒ WebView 发起子资源请求 ⇒ **cookie 照带**
 * （按 host 存、不含端口）⇒ **零点击泄漏**。比 S-01 修掉的那条（要用户点一下）更省事。
 *
 * ## 这个测试是真行为测试
 *
 * `subresourceOriginAllowed` 是纯函数，不需要 Android 运行时、不需要 WebView，
 * 每一条断言都是"给定输入 ⇒ 真的拿到这个返回值"。没有源码文本匹配。
 */
class R49SubresourceOriginTest {

    private val page = "http://127.0.0.1:3080/"

    // ===== 攻击场景：必须拦 ==============================================

    @Test
    fun blocksLoopbackOnADifferentPort_whichIsTheWholePoint() {
        // 这是攻击本体：同 host（cookie 照带）、不同端口（攻击者的监听）。
        assertFalse(
            "★ 零点击泄漏的核心：页面在 3080，子资源指向 127.0.0.1:9999 —— " +
                "cookie 按 host 存、不含端口，必须拦掉",
            subresourceOriginAllowed("http://127.0.0.1:9999/steal.png", page, 3080)
        )
        assertFalse(
            "localhost 是同一台机器的另一个写法，同样要拦",
            subresourceOriginAllowed("http://localhost:9999/steal.png", page, 3080)
        )
        assertFalse(
            "https 到同一个错误端口也要拦（scheme 不同不影响 cookie 的 host 语义）",
            subresourceOriginAllowed("https://127.0.0.1:9999/steal.png", page, 3080)
        )
    }

    @Test
    fun blocksArbitraryRemoteHosts() {
        assertFalse(
            "任意外部 host 一律拦：否则 WebView 会带着 cookie 去访问它",
            subresourceOriginAllowed("https://evil.example.com/x.png", page, 3080)
        )
        assertFalse(
            "看起来像回环但不是：127.0.0.1.evil.com 的 host 是 evil.com 的子域",
            subresourceOriginAllowed("http://127.0.0.1.evil.com/x.png", page, 3080)
        )
        assertFalse(
            "十进制 IP 不是 127.0.0.1 的等价写法（UrlScanner 不认）",
            subresourceOriginAllowed("http://2130706433:3080/x.png", page, 3080)
        )
    }

    @Test
    fun blocksNonHttpSchemes() {
        listOf(
            "file:///data/data/com.dsh.fcl.androidlauncher/files/dsh/home/.credentials.yaml",
            "content://com.android.providers.media/x",
            "javascript:alert(1)",
            "intent://x#Intent;end",
            ""
        ).forEach { u ->
            assertFalse("非 http(s) 一律拒：<$u>", subresourceOriginAllowed(u, page, 3080))
        }
    }

    // ===== 正常场景：必须放 ==============================================

    @Test
    fun allowsSameOriginSubresources() {
        // 页面自己的 JS/CSS/字体/图标 —— 同源必须放，否则整个界面加载不出来。
        listOf(
            "http://127.0.0.1:3080/assets/index-abc.js",
            "http://127.0.0.1:3080/assets/index-abc.css",
            "http://127.0.0.1:3080/favicon.ico",
            "http://127.0.0.1:3080/api/messages?x=1"
        ).forEach { u ->
            assertTrue("同源子资源必须放行：<$u>", subresourceOriginAllowed(u, page, 3080))
        }
    }

    @Test
    fun allowsLoopbackOnTheExpectedPort_evenIfPageUrlIsUnknown() {
        // expectedPort 已知时，即使 pageUrl 还取不到（WebView 刚创建），
        // 回环+预期端口也该放 —— 否则首屏资源会被误拦。
        assertTrue(
            "回环 + 预期端口，pageUrl 未知时也应放行",
            subresourceOriginAllowed("http://127.0.0.1:3080/assets/a.js", null, 3080)
        )
        assertTrue(
            "localhost 等价写法",
            subresourceOriginAllowed("http://localhost:3080/a.js", null, 3080)
        )
    }

    @Test
    fun defaultPortsAreNormalizedForSameOriginComparison() {
        // http 默认 80、https 默认 443：略写与显写是同一个 origin。
        assertTrue(
            "http://127.0.0.1/x（缺省 80）与 http://127.0.0.1:80/ 同源",
            subresourceOriginAllowed("http://127.0.0.1/x.js", "http://127.0.0.1:80/", 0)
        )
        assertTrue(
            "https 缺省 443",
            subresourceOriginAllowed("https://127.0.0.1/x.js", "https://127.0.0.1:443/", 0)
        )
    }

    // ===== 边界：取严 ====================================================

    @Test
    fun unknownPortMeansLoopbackRuleIsWaivedButSameOriginStillWorks() {
        // expectedPort <= 0（自动端口档、READY 还没到）⇒ 回环那一条弃权，
        // 与 webUiPortAllowed 的极性一致。但**同源**那一条仍然生效 ——
        // 所以不是"全放"，只是少了一维。
        assertTrue(
            "端口不可知时，同源仍要放行（否则首屏加载不出来）",
            subresourceOriginAllowed("http://127.0.0.1:3080/a.js", page, 0)
        )
        assertFalse(
            "★ 端口不可知**不等于**回环上任意端口都放 —— " +
                "pageUrl 是 3080 时，9999 仍然不同源、仍然要拦",
            subresourceOriginAllowed("http://127.0.0.1:9999/steal.png", page, 0)
        )
    }

    @Test
    fun userInfoInUrlDoesNotFoolTheHostCheck() {
        // http://evil.com@127.0.0.1:9999/ 的 host 是 127.0.0.1（userinfo 是 evil.com）。
        // 这条要拦（端口不对），但更关键的是**不能**把 userinfo 当 host 判成同源。
        assertFalse(
            "userinfo 混淆必须拦",
            subresourceOriginAllowed("http://127.0.0.1:3080@evil.com/x.png", page, 3080)
        )
    }

    @Test
    fun malformedUrlsAreRejectedNotCrashed() {
        listOf(
            "http://",
            "http://:3080/",
            "://127.0.0.1:3080/x",
            "http://[::1/x"
        ).forEach { u ->
            // 只要不抛异常且返回 false 即可（取严）
            assertFalse("畸形 URL 取严拒绝：<$u>", subresourceOriginAllowed(u, page, 3080))
        }
    }
}
