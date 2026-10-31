package com.dsh.core

import java.security.MessageDigest

/**
 * R30/BL-223：dsh 会话 cookie 名的推导。
 *
 * 上游算法（`@deepseek-ai/dsh-client-connection` 的 `cookieName(authority)`）：
 * ```
 * COOKIE_PREFIX + encodeBase64Url(sha256(authority))
 * ```
 * 其中 `authority` 是 `Host` 头（**含端口**，如 `127.0.0.1:38501`）。
 *
 * ★ 为什么这个函数值得单独存在并从 UI 里抽出来：
 * 1. 它是判断"cookie 到底在不在"的唯一依据 —— 名字算错了，诊断结论就是错的；
 * 2. 它是纯函数，能在 JVM 单测里跑（`android.util.Base64` 在单测里是 stub，
 *    所以这里用 [java.util.Base64]，两边算法必须一致）；
 * 3. 端口变化会让名字整体变掉（authority 含端口），这是"换端口后立刻 401"
 *    这一类问题的根源，需要一个方便断言的地方。
 */
object DshAuthCookie {

    const val PREFIX = "dsh-auth-"

    /**
     * @param authority `Host` 头的原样值，例如 `127.0.0.1:38501`（**不要**传 URL 或带 scheme）
     * @return `dsh-auth-<base64url-nopad(sha256(authority))>`
     */
    fun nameFor(authority: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(authority.toByteArray(Charsets.UTF_8))
        return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** 从 `Cookie` 头的原样值里判断是否包含 dsh 会话 cookie（只比对名字，不校验值）。 */
    fun hasSessionCookie(cookieHeader: String?, authority: String): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val expected = nameFor(authority)
        return cookieHeader.split(";").any { it.substringBefore("=").trim() == expected }
    }

    /** 列出 `Cookie` 头里所有 `dsh-auth-*` 的名字（用于诊断"名字对不上"的场景）。 */
    fun sessionCookieNames(cookieHeader: String?): List<String> {
        if (cookieHeader.isNullOrBlank()) return emptyList()
        return cookieHeader.split(";")
            .map { it.substringBefore("=").trim() }
            .filter { it.startsWith(PREFIX) }
    }
}
