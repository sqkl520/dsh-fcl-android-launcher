package com.dsh

import com.dsh.core.DshInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix8 / W1-D 纯 JVM 单测：[DshInstaller.extractNpmActivity] 的 BL-175 三修（不需要真机/Android）。
 *
 * BL-175 的三个缺陷与这里的用例一一对应：
 * ① fetch 分支固定 `split(' ')[5]` 取 URL —— npm **失败/重试**行没有状态码位，下标 5 命中裸词
 *   `attempt`，活动行刷出 `↓ attempt` 这种"看起来像 bug 的乱码"（E3-C17 本机实抓原文）。
 *   现在按 scheme 起点（`http://` / `https://`）找 URL。
 * ② scoped 包 tarball `…/@smithy/core/-/core-3.36.0.tgz` 取 URL 最后一段 → 作用域名整个丢掉
 *   （显示成 `core-3.36.0.tgz`）。现在从 `/-/` 之前回溯，并按段保住 `@scope/`。
 * ③ `· 214657ms` 是 npm **单请求墙钟**（从请求创建到响应体读完，含 socket 排队；
 *   maxsockets=1 时近乎单调递增）。摆在活动行会被读成"这个包下了 3.5 分钟"。
 *   裁定：活动行只留 `↓ 包名` / `✓ 包名` —— "在动"的证据是行在换，不需要误导性数字。
 *
 * 行形全部取自 `docs/reports/round21/E3-install.md`（本机 npm 11 实抓）与 E3-C17 的
 * make-fetch-happen 失败行原文 —— 用例是"真实输出"，不是我编的形状。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.R21FixDTest"
 *   （或仓库的 run-tests.sh，自动扫描本类）
 */
class R21FixDTest {

    // --- BL-175①：URL 按 scheme 起点取，不再命中裸词 -------------------------------------

    /**
     * 失败/重试形态（E3-C17 实抓原文，**无状态码位**）：旧写法产出 `↓ attempt`。
     * 现在拿到的必须是真包名，且绝不含 `attempt`。
     */
    @Test
    fun retryLineYieldsRealPackageNotAttemptWord() {
        assertEquals(
            "↓ is-plain-obj",
            DshInstaller.extractNpmActivity(
                "npm http fetch GET http://127.0.0.1:9/is-plain-obj attempt 1 failed with ECONNREFUSED"
            )
        )
        // 带状态码的重试形（make-fetch-happen remote.js:97）—— 同样不能掉进 "attempt"
        assertEquals(
            "↓ is-plain-obj",
            DshInstaller.extractNpmActivity(
                "npm http fetch GET http://127.0.0.1:9/is-plain-obj attempt 1 failed with 500"
            )
        )
        // 反向钉死缺陷本体：任何输出里都不许出现 "attempt" 这个词
        assertFalse(
            "活动行不得把 npm 的重试裸词当包名",
            DshInstaller.extractNpmActivity(
                "npm http fetch GET http://127.0.0.1:9/is-plain-obj attempt 2 failed with EAI_AGAIN"
            )!!.contains("attempt")
        )
    }

    /** 认不出 URL（没有 scheme）的行就是认不出 —— 返回 null，不猜（旧写法会拿下标 5 的裸词硬拼） */
    @Test
    fun lineWithoutUrlReturnsNull() {
        assertNull(DshInstaller.extractNpmActivity("npm http fetch"))
        assertNull(DshInstaller.extractNpmActivity("npm http fetch GET"))
        assertNull(DshInstaller.extractNpmActivity("npm http cache justaname"))
        // 前缀认得、但整行没有任何 scheme
        assertNull(DshInstaller.extractNpmActivity("npm http fetch GET 200 nope 74ms (cache miss)"))
    }

    // --- BL-175②：包名（scoped 保住作用域名） --------------------------------------------

    /** registry tarball 形态 `…/<pkg>/-/<name>-<version>.tgz` → 取 `/-/` **之前**的包名段 */
    @Test
    fun scopedTarballKeepsScopeName() {
        assertEquals(
            "↓ @smithy/core",
            DshInstaller.extractNpmActivity(
                "npm http fetch GET 200 https://registry.npmjs.org/@smithy/core/-/core-3.36.0.tgz 214657ms (cache miss)"
            )
        )
        // 真机上把 dsh 打爆的那条：旧写法显示 `core-3.36.0.tgz`（scope 丢了）
        assertEquals(
            "✓ @deepseek-ai/dsh",
            DshInstaller.extractNpmActivity(
                "npm http cache @deepseek-ai/dsh@https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-0.2.1-rc.2.tgz 0ms (cache hit)"
            )
        )
        // 非 scoped 的 tarball 也走同一条路（`/-/` 之前就是包名）
        assertEquals(
            "↓ typebox",
            DshInstaller.extractNpmActivity(
                "npm http fetch GET 200 https://registry.npmjs.org/typebox/-/typebox-1.3.27.tgz 213163ms (cache miss)"
            )
        )
    }

    /** 元数据请求（路径里没有 `/-/`）取最后一段；hosted tarball 文件名再剥扩展与尾随版本 */
    @Test
    fun plainAndHostedNames() {
        assertEquals(
            "↓ js-yaml",
            DshInstaller.extractNpmActivity(
                "npm http fetch GET 200 https://registry.npmjs.org/js-yaml 74ms (cache stale)"
            )
        )
        assertEquals(
            "↓ commander",
            DshInstaller.extractNpmActivity(
                "npm http fetch GET 200 https://registry.npmjs.org/commander 84ms"
            )
        )
        // 无 `/-/` 的 tarball（hosted/codeload 形态）：去 .tgz 再去尾随版本
        assertEquals(
            "✓ typebox",
            DshInstaller.extractNpmActivity(
                "npm http cache typebox@https://registry.npmjs.org/typebox-1.3.27.tgz 1ms (cache hit)"
            )
        )
        // 预发布版本段带 '-'（`-rc.2`）也不能把名字切短
        assertEquals(
            "✓ dsh",
            DshInstaller.extractNpmActivity(
                "npm http cache https://registry.npmjs.org/dsh-0.2.1-rc.2.tgz 0ms (cache hit)"
            )
        )
    }

    /** `%2F` 是 registry 对 scoped 名的 URL 编码，必须还原成 `@scope/name` 给用户看 */
    @Test
    fun encodedSlashIsDecoded() {
        assertEquals(
            "↓ @deepseek-ai/dsh",
            DshInstaller.extractNpmActivity(
                "npm http fetch GET 200 https://registry.npmjs.org/@deepseek-ai%2fdsh 979ms (cache miss)"
            )
        )
        // 小写 %2f 同解（npm/undici 两处大小写都出现过）
        assertEquals(
            "↓ @deepseek-ai/dsh",
            DshInstaller.extractNpmActivity(
                "npm http fetch GET 200 https://registry.npmjs.org/@deepseek-ai%2fdsh 979ms"
            )
        )
        // 编码形态 + tarball 段：`/-/` 前的 `%2F` 还原后仍保住 scope
        assertEquals(
            "✓ @milk-ai/dsh-experimental-inspector",
            DshInstaller.extractNpmActivity(
                "npm http cache https://registry.npmjs.org/@milk-ai%2fdsh-experimental-inspector/-/dsh-experimental-inspector-0.3.0.tgz 0ms (cache hit)"
            )
        )
    }

    // --- BL-175③：不带 ms ---------------------------------------------------------------

    /** 耗时一律不再透传（那是含排队的单请求墙钟，会被读成"这个包下了 3.5 分钟"） */
    @Test
    fun activityHasNoDurationSegment() {
        val out = DshInstaller.extractNpmActivity(
            "npm http fetch GET 200 https://registry.npmjs.org/@smithy/core/-/core-3.36.0.tgz 214657ms (cache miss)"
        )!!
        assertFalse("活动行不得含 'ms'", out.contains("ms"))
        assertFalse("活动行不得含 ' · ' 分隔的尾段", out.contains(" · "))
        assertEquals("↓ @smithy/core", out)
    }

    // --- 前缀 / 截断 / 非 npm 行 --------------------------------------------------------

    /** 符号前缀是"在动"的最小信息（↓=网络 / ✓=缓存命中），三语同观感 */
    @Test
    fun prefixMarksNetworkVsCache() {
        assertTrue(
            DshInstaller.extractNpmActivity(
                "npm http fetch GET 200 https://registry.npmjs.org/js-yaml 74ms"
            )!!.startsWith("↓ ")
        )
        assertTrue(
            DshInstaller.extractNpmActivity(
                "npm http cache https://registry.npmjs.org/js-yaml/-/js-yaml-4.1.0.tgz 0ms (cache hit)"
            )!!.startsWith("✓ ")
        )
    }

    /**
     * 活动行总长 ≤60 字符（fix7 就有这条上限，语义从"截尾段"改成"截活动行本体"）：
     * 卡片明细位放的是 `"<stage> · <activity>"`，撑爆的是整行，所以上限必须压在整行上。
     */
    @Test
    fun longLineIsTruncatedTo60() {
        val out = DshInstaller.extractNpmActivity(
            "npm http fetch GET 200 https://registry.npmjs.org/" + "a".repeat(120) + " 84ms"
        )!!
        assertEquals(60, out.length)
        assertEquals("↓ " + "a".repeat(58), out)
    }

    /** 非 http 行（STAGE/DONE/npm error/普通输出）一律 null —— 认不出的绝不伪装成活动行 */
    @Test
    fun nonHttpLinesStillReturnNull() {
        assertNull(DshInstaller.extractNpmActivity("added 540 packages in 3m"))
        assertNull(DshInstaller.extractNpmActivity("[setup] STAGE=installing @deepseek-ai/dsh@0.2.1-rc.2"))
        assertNull(DshInstaller.extractNpmActivity("[setup] DONE ok"))
        assertNull(DshInstaller.extractNpmActivity("npm error code EAI_AGAIN"))
        // 前缀差一个词也不认（必须是 fetch/cache，不能顺手把别的 npm http 子类型当真活动）
        assertNull(DshInstaller.extractNpmActivity("npm http 304 https://registry.npmjs.org/js-yaml"))
        assertNull(DshInstaller.extractNpmActivity("npm http fetch POST https://registry.npmjs.org/-/npm/v1/security/audits 12ms"))
    }

    /**
     * 边界：`npm http fetch GET <url> …`（无状态码，只有方法）也认 —— 这就是失败/重试行族，
     * 与上面"POST 审计接口"一对照：**认不认**由前缀决定，**取哪个包名**由 `/-/` 与段位决定，
     * 状态码位有没有都不影响。
     */
    @Test
    fun fetchLineWithoutStatusCodeStillResolvesName() {
        assertEquals(
            "↓ is-plain-obj",
            DshInstaller.extractNpmActivity("npm http fetch GET http://127.0.0.1:9/is-plain-obj")
        )
    }

    /**
     * 边界：URL 段里带 query / hash / 尾斜杠 —— 都不能混进包名。
     * （`substringBefore('?')`/`('#')`/`trimEnd('/')` 这三步是旧实现就有、必须保住的性质）
     */
    @Test
    fun urlDecorationsAreStripped() {
        assertEquals(
            "↓ js-yaml",
            DshInstaller.extractNpmActivity("npm http fetch GET 200 https://registry.npmjs.org/js-yaml?cts=1&strict-v=true 74ms")
        )
        assertEquals(
            "↓ js-yaml",
            DshInstaller.extractNpmActivity("npm http fetch GET 200 https://registry.npmjs.org/js-yaml/ 74ms")
        )
        // query 里嵌着另一个 scheme：起点必须取**最前**的那个 scheme，不能被查询串里的 URL 带跑
        assertEquals(
            "↓ js-yaml",
            DshInstaller.extractNpmActivity(
                "npm http fetch GET 200 https://registry.npmjs.org/js-yaml?rb=http://evil.example/x 74ms"
            )
        )
    }
}
