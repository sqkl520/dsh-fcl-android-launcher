package com.dsh

import com.dsh.core.UrlScanner
import com.dsh.core.webUiPortAllowed
import com.dsh.core.webUiUrlAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * round34 / N3 的**端口维度**：round32 只补了 host，端口漏了。
 *
 * ## 缺陷（红队实测，探针 `D:\tmp\redteam34\N3Compose.java`）
 * `webUiUrlAllowed(url)` 只判 scheme + host。实例端口 3080 时，rootfs 内写一行
 * `dsh web: http://127.0.0.1:40404/?token=BBB…` 会被**采信**：`portOf(picked)=40404` 与实例端口
 * 不符，却照样写进 `state.url` / pid 文件 url 字段 / token 侧车，最后进 WebView。
 * proot 没有网络隔离（`ProotCommand` 的 argv 里没有 `--net`/`unshare`），rootfs 内进程能监听
 * 40404；同源（都是 `127.0.0.1`）⇒ `setAcceptThirdPartyCookies(false)` 拦不住。
 *
 * ## 为什么这是"同一份 state.url 两套口径"
 * 认领路径 `adoptionCandidateAllowed(candidate, expectedPort)` 是"端口 + host"双判据
 * （`DshRuntime.kt:2706-2707`），实时路径只有 host。本文件钉住补上端口之后的实时路径。
 *
 * ## 与 `R32N3HostGateTest` 的分工（**不重复、不互相掩盖**）
 * - 那个钉 scheme + host，用的是**单参数** `webUiUrlAllowed(url)`（口径未变，仍应是
 *   纯 host/scheme 判据 —— 本文件有一条断言钉住"单参数版仍然不看端口"，防它被悄悄改宽/改窄）。
 * - 这个钉端口，用的是双参数 `webUiUrlAllowed(url, expectedPort)`。
 *
 * ## 端口基准是 `launchPort` 不是 `instance.port`
 * `resolveLaunchPort` 在"配置端口 > 0 且被占"时返回 0（脚本改用系统随机端口），而
 * `instance.port` 仍是旧值。拿 `instance.port` 当基准会把**真实** URL 全判死。下面
 * [autoPortModeSkipsPortDimension] 钉住 `expectedPort <= 0` ⇒ 放行这一档。
 */
class R34PortGateTest {

    // ---------------------------------------------------------------- 核心：端口不匹配必须拒

    /**
     * **缺陷本体**：回环但端口不匹配的 URL 一律不得被采信。
     *
     * 这条就是红队 N2/N3/N4/N5/N6 的直接回归：修复前每一条都会返回 true。
     */
    @Test
    fun rejectsLoopbackUrlOnWrongPort() {
        val hostile = listOf(
            // 红队 N3：攻击者监听端口
            "http://127.0.0.1:40404/?token=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            // 红队 N2 / N4：随便一个不等于实例端口的端口
            "http://127.0.0.1:1/?token=CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
            // 回环的其它写法也必须同样受端口约束
            "http://localhost:40404/?token=x",
            "http://[::1]:40404/?token=x",
            "https://127.0.0.1:40404/?token=x",
            // 端口号相邻也不行 —— 判据是相等，不是"接近"
            "http://127.0.0.1:3081/?token=x",
            "http://127.0.0.1:3079/?token=x"
        )
        for (u in hostile) {
            assertFalse(
                "回环但端口 != 3080 的 URL 不得被采信（红队 N3 缺陷本体）：$u",
                webUiUrlAllowed(u, 3080)
            )
            assertFalse("端口半边必须独立生效：$u", webUiPortAllowed(u, 3080))
        }
    }

    /** 端口匹配的回环 URL 必须继续通过 —— 否则正常路径被门挡死。 */
    @Test
    fun acceptsLoopbackUrlOnMatchingPort() {
        val ok = listOf(
            "http://127.0.0.1:3080/?token=x",                 // ①dsh web: 的形态
            "http://localhost:3080/?token=x",
            "http://[::1]:3080/?token=x",
            "https://127.0.0.1:3080/?token=x",
            "http://127.0.0.1:3080/"                         // 无 token 的合成形态
        )
        for (u in ok) {
            assertTrue("端口匹配的回环 URL 必须接受：$u", webUiUrlAllowed(u, 3080))
            assertTrue("端口半边必须放行：$u", webUiPortAllowed(u, 3080))
        }
    }

    /**
     * **③级合成 URL 必须继续通过**（`findMarkerPort` → `http://127.0.0.1:<port>/`）。
     *
     * 这是本修复最容易误伤的一档：③级的端口来自脚本的 `READY port=<n>`，那个 n 就是本次
     * 运行让脚本绑定的端口（`start-dsh.sh:414-424` 的 `READY_PORT`），所以它必须与
     * `expectedPort` 相等并通过。若实现把③级当成"无端口来源"另开绿灯，或把 `expectedPort`
     * 取错成 `instance.port`，这条会红。
     */
    @Test
    fun syntheticThirdTierUrlStillPasses() {
        // 复刻 onProcessLine 的候选构造（与 R32N3HostGateTest 同款表达式）
        fun candidates(line: String) = listOfNotNull(
            UrlScanner.findAuthenticatedUrl(line),
            UrlScanner.findMarkerUrl(line),
            UrlScanner.findMarkerPort(line)?.let { "http://127.0.0.1:$it/" }
        )

        // 正常路径：只有 READY port=3080 → 合成 http://127.0.0.1:3080/
        val normal = "READY port=3080"
        val pickedNormal = candidates(normal).firstOrNull { webUiUrlAllowed(it, 3080) }
        assertEquals(
            "③级合成 URL 的端口就是实例端口，必须继续通过；候选=${candidates(normal)}",
            "http://127.0.0.1:3080/",
            pickedNormal
        )

        // ①②被 host 门拒 → 降级到③级（N7 的同款场面），端口仍须匹配
        val fallback = "dsh web: https://evil.example:443/?token=x READY port=3080"
        val pickedFallback = candidates(fallback).firstOrNull { webUiUrlAllowed(it, 3080) }
        assertEquals(
            "①②被拒后必须降级到③级并继续通过；候选=${candidates(fallback)}",
            "http://127.0.0.1:3080/",
            pickedFallback
        )

        // ③级自己的端口不对 ⇒ 也拒（不能因为"是合成的"就免检端口）
        val forgedThirdTier = "READY port=40404"
        assertNull(
            "③级合成 URL 的端口若不等于实例端口也必须被拒（红队 N5）；候选=${candidates(forgedThirdTier)}",
            candidates(forgedThirdTier).firstOrNull { webUiUrlAllowed(it, 3080) }
        )
    }

    /**
     * **红队 N6**：同一行里"伪造的①级端口 + 真的③级端口"并存时，**不得回落到③级**。
     *
     * 为什么明确钉住"不降级"：③级的端口与伪造行同源，拿它兜底等于把"端口不匹配"翻译成
     * "采信另一个同样不可信的端口"。这条行里③级恰好是 3080（真端口），所以它会通过 ——
     * 这正是期望行为（它是**真的**）。下面同时钉住"只有伪造①级 + 无③级"时必须为 null。
     */
    @Test
    fun forgedHigherTierDoesNotShadowGenuineSynthetic() {
        fun candidates(line: String) = listOfNotNull(
            UrlScanner.findAuthenticatedUrl(line),
            UrlScanner.findMarkerUrl(line),
            UrlScanner.findMarkerPort(line)?.let { "http://127.0.0.1:$it/" }
        )
        // N6：①伪造 40404 + ③真 3080 → 必须选③（不能选伪造的①）
        val n6 = "READY port=3080 dsh web: http://127.0.0.1:40404/?token=DDD"
        assertEquals(
            "同一行里有真的③级（3080）时必须选它，而不是伪造的①级（40404）；候选=${candidates(n6)}",
            "http://127.0.0.1:3080/",
            candidates(n6).firstOrNull { webUiUrlAllowed(it, 3080) }
        )
        // 只有伪造①级、没有③级 → null（= 这行没有可用 URL），不得漏下去
        val onlyForged = "dsh web: http://127.0.0.1:40404/?token=DDD"
        assertNull(
            "只有伪造候选时必须返回 null；候选=${candidates(onlyForged)}",
            candidates(onlyForged).firstOrNull { webUiUrlAllowed(it, 3080) }
        )
    }

    // ------------------------------------------------------ 自动端口档：expectedPort <= 0

    /**
     * **`expectedPort <= 0` ⇒ 端口这一维弃权**（放行），且**与 `adoptionPortMatches` 极性相反**。
     *
     * 为什么必须放行：`resolveLaunchPort` 在"配置端口 <= 0"或"配置端口被占"时返回 0，
     * 端口由内核分配、此刻**不可知**。若这里也拒，自动端口实例永远拿不到 URL，state 卡在
     * Starting 直到 180s 看门狗判超时 —— 把安全门做成功能回归。
     *
     * 为什么与 `adoptionPortMatches` 相反不是"口径漂移"：认领路径的端口是**已知**的
     * （从 pid 文件读出且已探活），那时"不知道端口"本身就是可疑；这里端口还没被任何可信
     * 来源告知。两条判据的**输入前提**不同，所以极性不同是对的。
     */
    @Test
    fun autoPortModeSkipsPortDimension() {
        // 自动端口档：任意回环端口的 URL 都放行（端口维弃权，host/scheme 维照旧生效）
        assertTrue(
            "expectedPort=0（自动端口）时端口维必须弃权，否则自动端口实例永远到不了 Running",
            webUiUrlAllowed("http://127.0.0.1:40404/?token=x", 0)
        )
        assertTrue("负数同义（端口不可知）", webUiPortAllowed("http://127.0.0.1:40404/", -1))
        assertTrue("端口不可知时应放行一切端口", webUiPortAllowed("http://127.0.0.1:1/", 0))

        // 但 host / scheme 维在自动端口档下**仍然生效** —— 弃权只针对端口这一维
        assertFalse(
            "端口维弃权不等于整体放行：非回环主机仍必须被拒",
            webUiUrlAllowed("https://evil.example:443/?token=x", 0)
        )
        assertFalse(
            "端口维弃权不等于整体放行：非 http(s) scheme 仍必须被拒",
            webUiUrlAllowed("javascript:alert(1)//127.0.0.1/", 0)
        )
        assertFalse("null 仍必须被拒", webUiUrlAllowed(null, 0))

        // 对照：同一 URL 在"端口已知且不匹配"时必须拒 —— 证明差异真的来自端口维
        assertFalse(
            "对照：同一个 40404 的 URL 在 expectedPort=3080 时必须拒（差异只由端口维造成）",
            webUiUrlAllowed("http://127.0.0.1:40404/?token=x", 3080)
        )
    }

    /**
     * **端口解析不出 ⇒ 拒**（取严，与 `adoptionPortMatches` 同口径）。
     *
     * `UrlScanner.portOf` 在 URI 端口 <= 0 时会回退 `ANY_URL` 正则；两者都拿不到就是 null。
     * "看起来像回环地址"不等于"在预期端口上"。
     */
    @Test
    fun rejectsWhenPortCannotBeParsed() {
        val portless = listOf(
            "http://127.0.0.1/?token=x",      // 无 :port（URI 端口 -1，正则也不匹配）
            "http://localhost/",
            "http://[::1]/?token=x"
        )
        for (u in portless) {
            assertNull("前提：这几种形态确实解析不出端口：$u", UrlScanner.portOf(u))
            assertFalse("解析不出端口时不得采信（expectedPort 已知）：$u", webUiPortAllowed(u, 3080))
            assertFalse("完整判据同结论：$u", webUiUrlAllowed(u, 3080))
        }
        // 空/空白/null
        assertFalse("null 必须拒", webUiPortAllowed(null, 3080))
        assertFalse("空串必须拒", webUiPortAllowed("", 3080))
        assertFalse("纯空白必须拒", webUiPortAllowed("   ", 3080))
    }

    // -------------------------------------------------- 单参数版未被改宽：钉住既有口径

    /**
     * **单参数 `webUiUrlAllowed(url)` 仍然只看 scheme + host，不看端口。**
     *
     * 这条是"防悄悄改窄/改宽"的机械护栏：`R32N3HostGateTest` 用单参数版钉 host 口径
     * （它断言 `webUiUrlAllowed("http://127.0.0.1:1/")` 为 true）。如果我在修复时把端口判定
     * 塞进单参数版，那一整套 round32 的断言会莫名其妙变红；反之若有人把单参数版删掉改成
     * 双参必填，`loadUrlChecked` 之外的既有调用点会编译失败。
     *
     * 所以：**单参数版语义冻结**，端口判定走新重载。这条钉住这个分工。
     */
    @Test
    fun singleArgOverloadStaysPortBlind() {
        assertTrue(
            "单参数版必须保持端口无关（R32 的 host 口径依赖这一点）",
            webUiUrlAllowed("http://127.0.0.1:40404/?token=x")
        )
        assertTrue(
            "双参数版才有端口维度；两者对同一 URL 可以给出不同结论，这是设计",
            webUiUrlAllowed("http://127.0.0.1:40404/?token=x", 0)
        )
        assertFalse(
            "双参数版在端口已知且不匹配时给出反面结论",
            webUiUrlAllowed("http://127.0.0.1:40404/?token=x", 3080)
        )
    }

    /** 双参数版必须**蕴含**单参数版（不得比它更宽）—— 防止有人写成 `||` 之类的逻辑错。 */
    @Test
    fun twoArgOverloadImpliesOneArgOverload() {
        val probes = listOf(
            "http://127.0.0.1:3080/?token=x",
            "http://127.0.0.1:1/?token=x",
            "https://evil.example:443/?token=x",
            "javascript:alert(1)//127.0.0.1/",
            "file://127.0.0.1/root/x",
            "http://192.168.1.5:3080/",
            "http://[::1]:3080/",
            "http://127.0.0.1:3080/"
        )
        for (u in probes) {
            for (p in listOf(0, 3080)) {
                if (webUiUrlAllowed(u, p)) {
                    assertTrue(
                        "双参数版放行 ⇒ 单参数版必须也放行（否则口径比 host/scheme 门更宽）：" +
                            "$u @ expectedPort=$p",
                        webUiUrlAllowed(u)
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------- 接线：调用点必须走双参版

    /**
     * **接线断言（源码级）**：`onProcessLine` 的候选过滤必须用**双参数**版，且基准是
     * `launchPort`（本次真正让脚本绑定的端口），不是 `instance.port`。
     *
     * 为什么只能源码级：`onProcessLine` 是私有方法且依赖 Android Context，JVM 单测调不到
     * （同 `R33LoadUrlGateWiringTest` / `R31Fix233Test` 的既有处境与办法）。
     *
     * 判别力：把调用点改回 `webUiUrlAllowed(it)`（单参数），[processLineUsesPortAwareGate]
     * 立刻变红 —— 这正是本轮缺陷的形态。
     */
    @Test
    fun processLineUsesPortAwareGate() {
        val src = runtimeSourceText()
        val body = onProcessLineBody()
        assertTrue(
            "onProcessLine 必须用双参数门 webUiUrlAllowed(it, expectedPort)；" +
                "用单参数版等于端口维度没修。方法体：\n$body",
            body.contains("webUiUrlAllowed(it, expectedPort)")
        )
        // 形参表（签名，不在方法体里）：必须收一个 expectedPort
        val decl = src.substringAfter("private fun onProcessLine(").substringBefore(") {")
        assertTrue(
            "onProcessLine 必须新增 expectedPort 形参（端口基准的唯一来源）。实际签名：$decl",
            decl.contains("expectedPort: Int")
        )
        // 不得在方法体里自己重抄端口判据（应委托 webUiUrlAllowed / webUiPortAllowed）
        val duplicated = body.lines().any { line ->
            line.contains("portOf(") &&
                (line.contains("==") || line.contains("!=")) &&
                !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
        }
        assertFalse(
            "onProcessLine 里出现了手写的端口比对行 —— 应委托 webUiUrlAllowed(it, expectedPort)。实际：\n" +
                body.lines().filter { it.contains("portOf(") }.joinToString("\n"),
            duplicated
        )
    }

    /**
     * **接线断言（源码级）**：`onProcessLine` 的 `expectedPort` 形参必须由 `startLocked` 的
     * `launchPort` 传入（不是 `instance.port`）。
     *
     * 这是本修复最隐蔽的正确性条件：实测若传 `instance.port`，"配置端口被占 → launchPort=0"
     * 这条常规路径会把所有真实 URL 判死。
     */
    @Test
    fun expectedPortIsFedFromLaunchPort() {
        val src = runtimeSourceText()
        assertTrue(
            "onLine 回调必须把 launchPort 作为 expectedPort 传进去（见 startLocked 的 resolveLaunchPort）",
            src.contains("onProcessLine(instance, gen, line, launchPort)")
        )
        assertFalse(
            "onLine 回调不得把 instance.port 当 expectedPort —— 配置端口被占时它是被换掉的旧值",
            src.contains("onProcessLine(instance, gen, line, instance.port)")
        )
    }

    /**
     * **接线断言（源码级）**：`DshWebViewActivity.loadUrlChecked` 的第二道门也必须带端口，
     * 且基准取 `State.Running.port`（宿主自己算出的运行期端口）。
     *
     * **不能**取 `UrlScanner.portOf(url)` —— 那是被校验的对象，拿它当基准等于恒等放行。
     */
    @Test
    fun webViewSecondGateIsAlsoPortAware() {
        val body = loadUrlCheckedBody()
        assertTrue(
            "loadUrlChecked 的门必须带端口维度（webUiUrlAllowed(url, expectedPort)）；" +
                "否则上游挡住之后这里仍是同一个盲点，纵深防御退化成一道。方法体：\n$body",
            body.contains("webUiUrlAllowed(url, expectedPort)")
        )
        assertTrue(
            "端口基准必须来自 State.Running.port（宿主算出的运行期端口）。实际：\n" +
                body.lines().filter { it.contains("expectedPort") }.joinToString("\n"),
            body.contains("DshRuntime.State.Running") && body.contains(".port")
        )
        assertFalse(
            "不得用 portOf(url) 当端口基准 —— 那是被校验的对象，等于恒等放行。实际：\n" +
                body.lines().filter { it.contains("portOf(") }.joinToString("\n"),
            body.lines().any { it.contains("expectedPort") && it.contains("portOf(") }
        )
        // 门仍必须在 compatOk 直通之前（BL-111 放行通道）
        val gateIdx = body.indexOf("webUiUrlAllowed(")
        val passIdx = body.indexOf("if (compatOk)")
        assertTrue("先决条件：门与 compatOk 直通都必须存在", gateIdx >= 0 && passIdx >= 0)
        assertTrue("门必须仍在 compatOk 直通之前", gateIdx < passIdx)
    }

    // --------------------------------------------------------------------- 源码读取

    private fun runtimeSourceText(): String =
        resolveMainSource("core/DshRuntime.kt")
            ?: throw AssertionError(
                "找不到 core/DshRuntime.kt：请在仓库根或 FCL/ 目录下运行测试" +
                    "（兼容 FCL/FCL/ FCL/ src/main/ 三种布局）"
            )

    private fun resolveMainSource(rel: String): String? {
        val f = resolveMainSourceFile(rel) ?: return null
        return f.readText()
    }

    private fun resolveMainSourceFile(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            val candidates = listOf(
                File(dir, "FCL/FCL/src/main/java/com/dsh/$rel"),
                File(dir, "FCL/src/main/java/com/dsh/$rel"),
                File(dir, "src/main/java/com/dsh/$rel")
            )
            candidates.firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    /** 切出 `onProcessLine` 的方法体（从签名到与其配对的那个右花括号）。 */
    private fun onProcessLineBody(): String = methodBody("private fun onProcessLine(")

    /** 切出 `loadUrlChecked` 的方法体。 */
    private fun loadUrlCheckedBody(): String =
        methodBody("private fun loadUrlChecked(", "ui/DshWebViewActivity.kt")

    private fun methodBody(signature: String, rel: String = "core/DshRuntime.kt"): String {
        val text = resolveMainSource(rel)
            ?: throw AssertionError("找不到 $rel —— 请在仓库根或 FCL/ 目录下运行测试")
        val start = text.indexOf(signature)
        if (start < 0) throw AssertionError("找不到 `$signature` —— 方法被改名或删了？")
        val braceStart = text.indexOf('{', start)
        if (braceStart < 0) throw AssertionError("`$signature` 没有方法体？")
        var depth = 0
        var i = braceStart
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(braceStart, i + 1)
                }
            }
            i++
        }
        throw AssertionError("`$signature` 的花括号没有闭合 —— 解析失败")
    }
}
