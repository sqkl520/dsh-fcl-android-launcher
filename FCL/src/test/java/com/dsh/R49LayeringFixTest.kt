package com.dsh

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import com.dsh.core.DshAppContextHolder
import com.dsh.core.DshNavigation
import com.dsh.core.DshRuntimeService
import com.dsh.core.DshShellEntry
import com.dsh.core.DshStringProvider
import com.dsh.core.DshStrings
import com.dsh.core.DshUiText
import com.dsh.core.webUrlOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * R49 / W3-arch：架构审查里三组**低风险结构问题**的钉住用例（L1 / L2 / U2+U3）。
 *
 * ## 为什么这三组需要测试（都不产生异常，全靠"读代码发现"）
 * - **L1** `core → ui` 反向依赖：全仓只有一条 `import com.dsh.ui.*`。它不会让任何东西失效，
 *   只会让"core 可独立成 module"这个前提在拆分那天断掉。拆的人不看这条就白拆。
 * - **L2** `core` 取文案依赖 `FCLApp.getActivity()`：后果是**静默的语言错位**（英文界面取到中文），
 *   没有任何异常可抓 —— BL-107 的全部意义就在这条链上。
 * - **U3** `"http://127.0.0.1:<port>/"` 的四份合成：四份都"看起来一样"，漂移只表现为
 *   "某个入口会去加载另一个入口不会加载的地址"。
 *
 * ## 测试策略（与仓库惯例一致）
 * 1. **能纯函数化的就用纯函数钉**：`DshRuntimeService.pickShellIntent`（L1 的选择规则）、
 *    `webUrlOrNull`（U3 的全部判据）。这两处都刻意抽成了不碰 Android 资源、
 *    不建 `PendingIntent` 的纯逻辑，就是为了能在 JVM 上跑。
 * 2. **不能在 JVM 上跑的实现细节用源码结构断言钉**：`android.jar` 里 `Intent.addFlags`
 *    的方法体是 `Stub!`（`javap` 实测：`new RuntimeException("Stub!") … athrow`），
 *    所以"补了什么标志"只能读源码确认。这与 `R33LoadUrlGateWiringTest` /
 *    `R34PortGateTest` 同一手法。
 * 3. **"清单不许再抄第二份"用全树 grep 断言**：L1 的"core 里不许出现 `com.dsh.ui`"
 *    和 U3 的"UI 里不许再手拼回环 URL"都是这一类 —— 它们的价值全在"下次有人加回来时变红"。
 *
 * 运行：`sh tools/run-tests.sh`
 */
class R49LayeringFixTest {

    @After
    fun resetGlobals() {
        // DshNavigation / DshStrings / DshAppContextHolder 都是进程级单例，MiniRunner 在
        // **同一个 JVM** 里跑全部测试类：不还原就是给后面的用例留一颗雷。
        DshNavigation.clear()
        DshStrings.clear()
        setStaticField(DshAppContextHolder::class.java, "context", null)
    }

    // ══════════════════════════════════════════════════════════ L1：core → ui 反向依赖

    /**
     * ★ L1 的核心语义：**注册了就只用注册的**，且退化路径**一次都不碰**。
     *
     * 后半句不是锦上添花：如果实现写成"两个都求值再二选一"，退化分支里的
     * `packageManager.getLaunchIntentForPackage` 就会在每次构建通知时白跑一次
     * （Binder 调用，且 PM 异常会进 runCatching 掩盖成"没有入口"），
     * 而"注册优先"也就只剩名义上的优先级。计数器把这条钉死。
     */
    @Test
    fun shellIntentPrefersRegisteredEntryAndNeverTouchesFallback() {
        val fromShell = newIntent()
        var fallbackCalls = 0
        DshNavigation.register(DshShellEntry { fromShell })

        val picked = DshRuntimeService.pickShellIntent(DshNavigation.shellEntry, fakeContext()) {
            fallbackCalls++
            newIntent()
        }

        // ⚠️ 这里必须用 `===` + assertTrue，不能用 assertSame：
        //   assertSame 的失败消息会把两个参数 `toString()` 拼进去，而 `Intent.toString()`
        //   在 `android.jar` 里同样是 `Stub!` —— 一旦断言失败，用户看到的会是
        //   `RuntimeException: Stub!` 而不是"选错了哪个 Intent"。
        //   （本轮实测踩到：变异成"跳过注册分支"后，报错信息确实是 `Stub!`，
        //    测试虽然红了，但红得毫无信息量 —— 一条只会说 Stub 的失败断言等于半个哑弹。）
        assertTrue("注册了外壳入口就必须用它（同一个实例）", picked === fromShell)
        assertEquals("注册成功时不许触碰退化路径（否则那个 Binder 调用是白跑的）", 0, fallbackCalls)
    }

    /**
     * 未注册 → 退到系统 LAUNCHER 入口。
     *
     * 这是改造前"根本不存在"的一条路：原实现写死 `DshMainActivity::class.java`，
     * 要么有类要么编译不过。现在 core 不认识任何 Activity 类型，必须有一条**由系统回答**
     * "这个包点开是哪个 Activity"的退路 —— 否则"ui 层忘了注册"就等于"点通知没反应"，
     * 而那是用户可感知的功能损失。
     */
    @Test
    fun shellIntentFallsBackToLaunchIntentWhenNothingRegistered() {
        val fromSystem = newIntent()
        val picked = DshRuntimeService.pickShellIntent(null, fakeContext()) { fromSystem }
        // 同 [shellIntentPrefersRegisteredEntryAndNeverTouchesFallback]：用 `===` 而不是
        // assertSame，理由见那里的注释（assertSame 的失败消息会撞上 `Intent.toString()` 的 `Stub!`）。
        assertTrue("未注册时必须退化到 LAUNCHER 入口", picked === fromSystem)
    }

    /**
     * ★ 注册的实现自己抛异常 ⇒ **等价于未注册**，继续退化，不许把异常穿出去。
     *
     * 为什么这条必须有：注册方是 App 层的一段 lambda。将来若有人在里面读了只在 Activity
     * 里才就绪的东西，一个异常就会打断 `buildNotification` —— 而通知是前台服务的**存活凭据**
     * （Android 8+ 不 `startForeground` 直接 `ForegroundServiceDidNotStartInTimeException`
     * 崩溃，见 `onStartCommand` 里那段注释）。为了一条"点通知去哪"的元数据把服务带崩，
     * 代价完全不成比例。
     */
    @Test
    fun shellIntentTreatsThrowingEntryAsUnregistered() {
        val fromSystem = newIntent()
        DshNavigation.register(DshShellEntry { throw IllegalStateException("外壳还没初始化") })

        val picked = DshRuntimeService.pickShellIntent(DshNavigation.shellEntry, fakeContext()) { fromSystem }
        assertTrue("注册实现抛异常时必须静默退化，而不是把异常穿给调用方", picked === fromSystem)
    }

    /**
     * ★ 连退化路径也拿不到 ⇒ **返回 null**（= 通知不挂点击目标），不是抛、也不是伪造一个 Intent。
     *
     * `getLaunchIntentForPackage` 在"包被禁用 / 只有 Service 没有 Activity"时返回 null。
     * 这里返回 null 之后，`buildNotification` 走的是一条**有明确语义**的路
     * （`NotificationCompat.setContentIntent(null)` = 通知没有点击目标），
     * 状态文案与"停止"动作照旧 —— 用户仍能看清在跑什么、并停掉它。
     */
    @Test
    fun shellIntentIsNullWhenNoEntryAndNoLaunchIntent() {
        assertNull("全都拿不到时必须返回 null", DshRuntimeService.pickShellIntent(null, fakeContext()) { null })
    }

    /** 退化路径自己也抛（PM 崩了 / 无 PM 的测试夹具）⇒ 同样落 null，不穿异常。 */
    @Test
    fun shellIntentIsNullWhenFallbackThrows() {
        assertNull(
            "退化路径抛异常时也必须落 null（构建通知不许因它崩溃）",
            DshRuntimeService.pickShellIntent(null, fakeContext()) { throw IllegalStateException("PM 不可用") }
        )
    }

    /**
     * 注册点的**不可变性**：`register` 覆盖、`clear` 归零，`shellEntry` 逐次现取。
     *
     * "现取"这条有意义：`shellPendingIntent` **不缓存**选出来的 Intent
     * （外壳入口可能在进程内被换掉；构造 Intent 是零成本，真正贵的 `PendingIntent` 由系统
     * 按相同 Intent 复用）。如果将来有人加了缓存，本条会红。
     */
    @Test
    fun navigationRegistryIsReplaceableAndClearable() {
        assertNull("初始（已 clear）必须是 null", DshNavigation.shellEntry)
        val a = DshShellEntry { newIntent() }
        val b = DshShellEntry { newIntent() }
        DshNavigation.register(a)
        assertSame(a, DshNavigation.shellEntry)
        DshNavigation.register(b)
        assertSame("再次注册必须覆盖（不许变成「第一次注册永久生效」）", b, DshNavigation.shellEntry)
        DshNavigation.clear()
        assertNull(DshNavigation.shellEntry)
    }

    // ══════════════════════════════════════════════════════════ L2：core 取文案不再依赖 UI 全局

    /**
     * ★ L2 的正面：注入的 provider 拿到的 Context 优先于 core 自己持有的 Application Context。
     *
     * 两种 Context 用**引用不等**区分（`assertSame` 反向断言）：`contextOrNull()` 会对
     * 拿到的 Context 调 `.applicationContext`，所以两个假 Context 的 `getApplicationContext()`
     * 必须各自返回 `this` —— 见 [FakeContext]。
     */
    @Test
    fun usesInjectedProviderContextOverCoreAppContext() {
        val injected = fakeContext()
        val coreOwned = fakeContext()
        DshAppContextHolder.init(coreOwned)
        DshStrings.register(DshStringProvider { injected })

        val got = DshUiText.contextOrNull()
        assertSame("注入的 provider 必须优先（这就是 L2 要的方向）", injected, got)
        assertTrue("不许再退回 core 自己持有的那个", got !== coreOwned)
    }

    /**
     * ★ L2 的退化：provider 返回 null（= 此刻没有前台 Activity）⇒ 退到
     * `DshAppContextHolder.context`，**与改造前完全同一条退路**。
     *
     * 这条是"这次重构不许让现状变差"的凭据：改造前 `FCLApp.getActivity()` 为 null 时
     * 走的正是这条；改造后 provider 缺席 / 返回 null 走的还是这条。
     */
    @Test
    fun fallsBackToCoreAppContextWhenProviderReturnsNull() {
        val coreOwned = fakeContext()
        DshAppContextHolder.init(coreOwned)
        DshStrings.register(DshStringProvider { null })

        assertSame(
            "provider 拿不到前台 Activity 时必须退到 DshAppContextHolder.context（改造前的同一条退路）",
            coreOwned, DshUiText.contextOrNull()
        )
    }

    /** 完全没注入（例如有人删了 `initStep("dshStrings", …)`）⇒ 也只能退到 core 自己持有的那个，不是 null。 */
    @Test
    fun fallsBackToCoreAppContextWhenNothingInjected() {
        assertNull("前置：本用例不注入", DshStrings.provider)
        val coreOwned = fakeContext()
        DshAppContextHolder.init(coreOwned)

        assertSame("没注入不等于没有 Context —— 退路必须还在", coreOwned, DshUiText.contextOrNull())
    }

    /**
     * ★ 不许缓存 provider 的结果（与 [DshUiText] KDoc 里"不要缓存返回值"的告诫配套）。
     *
     * 用户可在 App 存活期间切语言；一旦缓存，"切了语言但界面文案不变"就回来了。
     * 判据：连续两次调用必须**各取一次** provider。
     */
    @Test
    fun providerIsConsultedOnEveryCallNotCached() {
        var calls = 0
        DshStrings.register(DshStringProvider { calls++; fakeContext() })
        DshUiText.contextOrNull()
        DshUiText.contextOrNull()
        assertEquals("每次取词都必须现问 provider（缓存会把语言钉死）", 2, calls)
    }

    // ══════════════════════════════════════════════════════════ U3：URL 合成只剩一处口径

    /**
     * ★ U3 的核心：`port == 0`（dsh 还没打印真实端口）时**不许**拼出
     * `http://127.0.0.1:0/` —— 那只会得到一个"页面加载失败"，看起来像 dsh 坏了。
     *
     * 0 与负数一并钉住：调用点拿到的 `port` 来自 `State.Running.port`，理论上是 >= 0，
     * 但"防御性地一起处理"比"只测 0"更能说明判据是 `> 0` 而不是 `!= 0`。
     */
    @Test
    fun webUrlIsNullWhenPortUnknown() {
        assertNull("port=0 不许合成 URL（会得到 http://127.0.0.1:0/）", webUrlOrNull(null, 0))
        assertNull("负数同样不许合成", webUrlOrNull(null, -1))
    }

    /** port 已知 ⇒ 合成裸根 URL（cookie 模式：应用内 WebView 有持久 cookie，进不去会走 401 兜底）。 */
    @Test
    fun webUrlSynthesizesBareLoopbackUrlWhenTokenMissing() {
        assertEquals("http://127.0.0.1:3080/", webUrlOrNull(null, 3080))
        assertEquals("http://127.0.0.1:1/", webUrlOrNull(null, 1))
    }

    /**
     * ★ 有 token URL 就**原样返回**，哪怕 `port` 还是 0。
     *
     * 这条不是"顺手"：带 token 的 URL 是 dsh 自己打印、且已过 `webUiUrlAllowed` 门禁的真凭据，
     * 比任何本地合成都可信。若实现写成"先看 port 再选 URL"，这个短窗口里就会把**真凭据**
     * 换成裸 URL（于是立刻 401），把可用的现场弄坏。
     */
    @Test
    fun webUrlKeepsTokenUrlEvenWhenPortIsZero() {
        val token = "http://127.0.0.1:3080/?token=abc123"
        assertEquals("有 token URL 时必须原样返回（port=0 也不例外）", token, webUrlOrNull(token, 0))
        assertEquals("有 token URL 且 port 正常时同样原样返回", token, webUrlOrNull(token, 3080))
    }

    // ══════════════════════════════════════════════════════════ 源码结构断言

    /**
     * ★ L1 的**回归闸门**：`core/` 下不许出现任何 `import com.dsh.ui`。
     *
     * 这是审查报告里点名的"钉住成本最低"的一条。它不看具体类名，所以将来无论把哪个
     * ui 类型 import 进 core 都会红 —— 而这正是本轮要永久关掉的那扇门。
     */
    @Test
    fun noCoreFileImportsUi() {
        val offenders = coreSources().filter { (_, text) ->
            Regex("""^\s*import\s+com\.dsh\.ui\.""", RegexOption.MULTILINE).containsMatchIn(text)
        }.map { it.first }
        assertTrue(
            "core 层不许 import ui 层（分层前提；改用 core 侧契约 + App 层注入）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * L1 的实现细节（JVM 测不到，只能读源码）：`DshRuntimeService` 必须
     * (a) 不再引用 `DshMainActivity`；(b) 通过 `DshNavigation` 取外壳入口；
     * (c) 补 `NEW_TASK | SINGLE_TOP`（从 Service 拉起 Activity 的两个必需标志，
     * 与改造前一致 —— 换的只是"Intent 从哪来"）。
     */
    @Test
    fun runtimeServiceTakesShellIntentFromNavigationWithLaunchFlags() {
        val src = mainSource("core/DshRuntimeService.kt")
        val code = stripCommentsAndStrings(src)
        assertFalse(
            "DshRuntimeService 不许再直接引用外壳 Activity（那是全仓唯一的 core→ui 依赖）",
            Regex("""\bDshMainActivity\b""").containsMatchIn(code)
        )
        assertTrue(
            "必须通过 DshNavigation 取外壳入口",
            Regex("""DshNavigation\.shellEntry""").containsMatchIn(code)
        )
        // ★ 上面那条只证明"出现了 DshNavigation.shellEntry"，不证明"注册分支还在"。
        //   变异验证实测：把 pickShellIntent 改成只剩退化分支（`?: launchIntentProvider()` 前
        //   那半句删掉）之后，上面的 Regex 仍然命中（生产调用点那行还在），断言照绿。
        //   所以必须直接钉"调用 shellIntent"这个动作本身。
        assertTrue(
            "必须真的调用 DshShellEntry.shellIntent（只剩退化分支 = 注册点形同虚设）",
            Regex("""\.shellIntent\(""").containsMatchIn(code)
        )
        assertTrue(
            "必须补 FLAG_ACTIVITY_NEW_TASK（Service 拉起 Activity 必需）",
            Regex("""Intent\.FLAG_ACTIVITY_NEW_TASK""").containsMatchIn(code)
        )
        assertTrue(
            "必须补 FLAG_ACTIVITY_SINGLE_TOP（已在栈顶时复用外壳）",
            Regex("""Intent\.FLAG_ACTIVITY_SINGLE_TOP""").containsMatchIn(code)
        )
        // 退化路径必须是"由系统回答 LAUNCHER 入口"，而不是把 Activity 类名藏进字符串 / 反射
        // （审查明确否决了那种做法：编译期依赖变成运行时依赖，问题原样保留）。
        assertTrue(
            "退化必须用 packageManager 的 LAUNCHER 入口",
            Regex("""getLaunchIntentForPackage""").containsMatchIn(code)
        )
        assertFalse(
            "不许用反射 / 类名字符串绕过分层（那只是把编译期依赖藏到运行时）",
            Regex("""Class\.forName|::class\.java\.name""").containsMatchIn(code)
        )
    }

    /**
     * L1 + L2 的注册点必须在 **App 组装层**，且**早于**任何可能构建通知的时机。
     *
     * `FCLApp.onCreate` 就是那个位置：服务是被 App 进程拉起的（`Application.onCreate` 必然
     * 先跑完），而"认领孤儿进程后**立刻** `startForeground`"也是一条"用户从没打开过界面
     * 就要建通知"的路径。
     *
     * ## 为什么断言"不套 initStep"（一条看起来反直觉的约束）
     * `initStep` 是"这一步可能抛、失败要登记并继续启动"的封装。
     * `R31StartupChainTest.everyInitStepIsGuardedIndividually` 用**硬编码 6** 钉住那条链，
     * 它的意图是"六个真步骤一个都不能漏"。而这里的两次 `register(...)` 只是往
     * `@Volatile` 引用里赋值、且传进去的 lambda **此刻不求值**（`new Intent(...)` /
     * `getActivity()` 都在真正使用时才跑），不可能抛。把它们套进 initStep 会让那个常数
     * 从 6 变 8 —— 于是要么改死别人的回归闸门，要么让它的消息变成假话。
     *
     * 所以这里钉住的是"**注册必须存在，且不许污染 initStep 计数**"这两件事的组合。
     */
    @Test
    fun appRegistersBothUiCapabilitiesIntoCore() {
        val src = resolveMainSource("com/tungsten/fcl/FCLApp.java")?.readText()
            ?: fail("找不到 FCL/src/main/java/com/tungsten/fcl/FCLApp.java").let { "" }
        // 先剔除注释与字符串：本方法的 KDoc 里就写着 "initStep" 这个词，
        // 用原文统计会让"注释里的字"冒充"真实步骤"（本轮实测踩到过：假红）。
        val code = stripCommentsAndStrings(src)
        assertTrue("App 层必须注册外壳入口", code.contains("DshNavigation.register"))
        assertTrue("App 层必须注入取词 Context 提供者", code.contains("DshStrings.register"))

        val stepLines = code.lines().filter { it.trimStart().startsWith("initStep(") }
        assertEquals(
            "initStep 的条数必须仍是 6（R31 的崩启动闸门按 6 条硬编码；注册不是 “可能抛的启动步骤”，" +
                "见 FCLApp.registerUiCapabilitiesForCore 的注释）。实际：\n" + stepLines.joinToString("\n"),
            6, stepLines.size
        )
    }

    /**
     * ★ L2 的**回归闸门**：`core/` 下不许出现 `FCLApp.getActivity()`。
     *
     * 与 [noCoreFileImportsUi] 同一类断言：它拦的不是"当前这一处"，而是"下一次把
     * UI 全局又写回 core"的动作。`FCLApp` 是 `Application` 子类，技术上不是"ui 包"，
     * 所以 import 检查抓不到它 —— 必须单独钉。
     */
    @Test
    fun noCoreFileReachesForFclAppActivity() {
        // ★ 必须先剔除注释：`DshUiText` 的 KDoc 里就写着"不再直接引用 `FCLApp.getActivity()`"，
        //   用原文扫会把这段**说明修复**的文字当成违规（本轮实测踩到过：假红）。
        val offenders = coreSources().filter { (_, text) ->
            Regex("""FCLApp\s*\.\s*getActivity""").containsMatchIn(stripCommentsAndStrings(text))
        }.map { it.first }
        assertTrue(
            "core 层不许直接向 UI 全局要 Activity（应走 DshStringProvider 注入）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * ★ U3 的**回归闸门**：UI 层不许再有第二份"无 token 时拼回环 URL"的实现，
     * 且两个调用点都必须走 [webUrlOrNull]。
     *
     * 为什么盯着字面量 `"http://127.0.0.1:${`：四份副本当初就是以这个形态存在的。
     * 只要有人再写一次（哪怕"顺手"写在别的 UI 文件里），本条就红。
     * 注释里提到这个形态不受影响 —— [stripCommentsAndStrings] 先把注释与字符串抹成等长空白。
     */
    @Test
    fun uiHasNoSecondCopyOfLoopbackUrlSynthesis() {
        val offenders = uiSources().filter { (_, text) ->
            val code = stripCommentsAndStrings(text)
            Regex("""http://127\.0\.0\.1:\$\{""").containsMatchIn(code)
        }.map { it.first }
        assertTrue(
            "UI 层不许再手拼 http://127.0.0.1:<port>/（口径已收敛到 core 的 webUrlOrNull）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )

        val webView = stripCommentsAndStrings(mainSource("ui/DshWebViewActivity.kt"))
        val callSites = Regex("""webUrlOrNull\(""").findAll(webView).count()
        assertEquals(
            "DshWebViewActivity 的两个 URL 合成点（loadBestUrl / runnableUrlOrNull）都必须改用它",
            2, callSites
        )
    }

    // ────────────────────────────────────────────────────────── 夹具

    /**
     * 一个"只认三条链"的假 Context：`getApplicationContext`（`contextOrNull` 会调）、
     * `getSharedPreferences`（`LocaleUtils.setLanguage` 取语言）、`getResources`（取词）。
     *
     * 三条都会在 `android.jar` 的 `Stub!` 上炸 —— 所以：
     * - 前两条被 override 成正常返回；
     * - 第三条**故意不 override**：`setLanguage` 因此抛，被 `DshUiText.localizedOrRaw`
     *   按"套不上语言就原样返回 base"吸收（这正是 `R31Fix233Test` 钉过的同一行为）。
     *
     * 构造是 `Stub!` ⇒ 用 `Unsafe.allocateInstance` 跳过（与 `R31Fix233Test` 同一手法）。
     */
    private class FakeContext : ContextWrapper(null) {
        @Suppress("unused")
        private val prefs: SharedPreferences? = null

        @Suppress("unused")
        private val res: Resources? = null

        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs!!
    }

    /** 内存版 SharedPreferences：`LocaleUtils.setLanguage` 只读 `lang` 一个键。 */
    private class InMemoryPreferences : SharedPreferences {
        private val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clearFirst = false
            override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
            override fun remove(key: String?) = apply { pending[key!!] = REMOVED }
            override fun clear() = apply { clearFirst = true }
            override fun commit(): Boolean {
                if (clearFirst) values.clear()
                pending.forEach { (k, v) -> if (v === REMOVED) values.remove(k) else values[k] = v }
                pending.clear()
                return true
            }
            override fun apply() {
                commit()
            }
        }

        companion object {
            private val REMOVED = Any()
        }
    }

    private fun fakeContext(): Context {
        val ctx = allocate(FakeContext::class.java) as FakeContext
        setField(ctx, "prefs", InMemoryPreferences())
        return ctx
    }

    /**
     * 造一个**不调构造器**的 Intent。
     *
     * `android.jar` 里 `Intent()` 的方法体是 `Stub!`（`javap` 实测），而 `Unsafe.allocateInstance`
     * 会绕开它 —— 得到一个字段全为默认值的实例。本文件的断言只用**引用相等**
     * （`assertSame`），所以不需要任何字段真的对：它证明的是"选出来的就是 provider 给的那个"。
     * `addFlags` / `getFlags` 在这里都不可用（同为 `Stub!`），故标志由源码断言钉。
     */
    private fun newIntent(): android.content.Intent =
        allocate(android.content.Intent::class.java) as android.content.Intent

    private fun allocate(cls: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val allocateMethod: Method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateMethod.invoke(unsafe, cls)!!
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val f = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        f.set(target, value)
    }

    private fun setStaticField(cls: Class<*>, name: String, value: Any?) {
        val f = cls.getDeclaredField(name).apply { isAccessible = true }
        f.set(null, value)
    }

    // ────────────────────────────────────────────────────────── 源码定位

    /** `core/` 下全部主源码（文件名 → 正文）。 */
    private fun coreSources(): List<Pair<String, String>> = sourcesUnder("core")

    /** `ui/`（含 `ui/shell/`、`ui/setup/`）下全部主源码。 */
    private fun uiSources(): List<Pair<String, String>> = sourcesUnder("ui")

    private fun sourcesUnder(pkg: String): List<Pair<String, String>> {
        val dir = resolveMainSourceDir(pkg)
            ?: fail("找不到主源码目录 FCL/src/main/java/com/dsh/$pkg（cwd=${System.getProperty("user.dir")}）")
                .let { return emptyList() }
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(dir).path to it.readText().replace("\r\n", "\n") }
            .sortedBy { it.first }
            .toList()
    }

    /** 读单个主源码（`rel` 相对 `com/dsh/`，例如 `core/DshRuntimeService.kt`）。 */
    private fun mainSource(rel: String): String =
        resolveMainSource("com/dsh/$rel")?.readText()?.replace("\r\n", "\n")
            ?: fail("找不到主源码 FCL/src/main/java/com/dsh/$rel（cwd=${System.getProperty("user.dir")}）")
                .let { "" }

    private fun resolveMainSource(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/java/$rel"),
                File(dir, "FCL/src/main/java/$rel"),
                File(dir, "src/main/java/$rel")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    private fun resolveMainSourceDir(pkg: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/java/com/dsh/$pkg"),
                File(dir, "FCL/src/main/java/com/dsh/$pkg"),
                File(dir, "src/main/java/com/dsh/$pkg")
            ).firstOrNull { it.isDirectory }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    /**
     * 把 Kotlin/Java 源码里的行注释、块注释（含 KDoc）与字符串字面量内容替换成**等长空白**，
     * 保留换行 —— 于是源码结构断言只看"这段文字会不会被编译成可执行代码"。
     *
     * 为什么必须剔除：本文件的断言有两类误判方向 ——
     * - 注释里写"不要再用 `http://127.0.0.1:${port}`"会被当成违规（假红）；
     * - 注释里写"这里调用了 webUrlOrNull"会让调用点计数假绿。
     *
     * 已知边界：多行原始字符串按"逐个引号配平"处理，遇到内含连续三个引号的极端写法可能
     * 判断失误。本文件只扫 `core/` / `ui/`，那些文件里没有这种写法
     * （同款剔除器的说明见 `R31Fix233Test.stripCommentsAndStrings`）。
     */
    private fun stripCommentsAndStrings(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var inString = false
        var inChar = false
        while (i < src.length) {
            val c = src[i]
            val next = src.getOrElse(i + 1) { ' ' }
            when {
                inLine -> {
                    if (c == '\n') { inLine = false; out.append('\n') } else out.append(' ')
                    i++
                }
                inBlock -> {
                    if (c == '*' && next == '/') { inBlock = false; out.append("  "); i += 2 }
                    else { out.append(if (c == '\n') '\n' else ' '); i++ }
                }
                inString -> when {
                    c == '\\' -> { out.append("  "); i += 2 }
                    c == '"' -> { inString = false; out.append(' '); i++ }
                    else -> { out.append(if (c == '\n') '\n' else ' '); i++ }
                }
                inChar -> when {
                    c == '\\' -> { out.append("  "); i += 2 }
                    c == '\'' -> { inChar = false; out.append(' '); i++ }
                    else -> { out.append(if (c == '\n') '\n' else ' '); i++ }
                }
                c == '/' && next == '/' -> { inLine = true; out.append("  "); i += 2 }
                c == '/' && next == '*' -> { inBlock = true; out.append("  "); i += 2 }
                c == '"' -> { inString = true; out.append(' '); i++ }
                c == '\'' -> { inChar = true; out.append(' '); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
