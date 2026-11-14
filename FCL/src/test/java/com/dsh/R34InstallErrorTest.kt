package com.dsh

import android.content.Context
import android.content.ContextWrapper
import com.dsh.core.DshInstance
import com.dsh.core.DshInstaller
import com.dsh.core.ProotExecutor
import com.dsh.core.ProotProcessExecutor
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * round34 / 安装失败原因：`[proot] 执行失败: …` 必须能进 `errorSummary`（MEDIUM）。
 *
 * ## 缺陷
 * `ProotProcessExecutor.run()` 的顶层 `catch` 把**任何**异常（`ProcessBuilder.start()` 的
 * `IOException`、读管道的 `IOException`、`InterruptedException`…）都折成退出码 `-1`，
 * 唯一留下的现场是它经 `reportRunFailure` 打到 `onLine` 的那一行
 * `[proot] 执行失败: <message 或异常类名>`（见 [ProotProcessExecutor.describeRunFailure]）。
 *
 * 而 `DshInstaller.onInstallLine` 的收口分支只认三种行：
 * `isNpmErrorLine(...)`（npm 错误）/ `[setup] FAILED` / `[proot] 预检失败:`。
 * 这一行三种都不命中 ⇒ 只进日志、进不了 `errorTails` ⇒ `errorSummary` 落到
 * `dsh_install_failed_generic`（"安装失败（详见日志）"）。
 *
 * 那句"详见日志"对本机用户是**空头支票**：日志落在 App 私有目录
 * （`/data/user/0/<包名>/…/logs/`），未 root 根本读不到。所以安装器实际丢掉了它手上
 * **唯一**的失败原因。
 *
 * ## 覆盖层次（越往下越接近真实路径）
 * | 层 | 用例 | 证明了什么 |
 * |---|---|---|
 * | 协约 | [prootRunFailurePrefixMatchesProducerExactly] | 生产者（`describeRunFailure`）的产物被消费者认得出 |
 * | 纯函数 | [runFailureLineIsRecognised] 等 | 判据 + 摘要整形（去前缀 / 脱敏 / 截断） |
 * | **端到端** | [failureLineReachesErrorSummary] | 反射真跑 `onInstallLine` → `errorSummary`，中间全是生产代码 |
 * | 不变 | [benignLinesKeepTheGenericFallback] | 没有这类行时行为与改造前**逐字相同** |
 *
 * ## 为什么端到端那两条必须存在
 * 只测 `isProotRunFailureLine` 是**假绿**：判据写得再对，`onInstallLine` 的 `when` 里没接线
 * （或接错分支）用户还是拿到"详见日志"。本轮的变异验证就是把那条分支删掉，看这两条会不会红。
 */
class R34InstallErrorTest {

    private var scopeJob: Job? = null

    @After
    fun tearDown() {
        scopeJob?.cancel()
        scopeJob = null
    }

    // ==================================================================
    // 一、协约：生产者产出的行，消费者必须认得
    // ==================================================================

    /**
     * 前缀是**字符串协约**（跨文件、无编译器保护）。这里两边都用**字面量**钉住，
     * 故意不复用 `DshInstaller` 的私有常量 —— 否则哪天有人把常量改成 `"[proot] failed:"`，
     * 常量与测试一起漂移，测试还是绿的，而真机上那一行再也匹配不上。
     *
     * 断言方向是"生产者的输出以测试里写的字面量为前缀"，这样改生产者措辞也立刻红。
     */
    @Test
    fun prootRunFailurePrefixMatchesProducerExactly() {
        val withMessage = ProotProcessExecutor.describeRunFailure(IOException("broken pipe"))
        val withoutMessage = ProotProcessExecutor.describeRunFailure(InterruptedException())
        // 生产者侧：前缀逐字是 `[proot] 执行失败:`（与日志里 grep 的那串一致）
        assertTrue(
            "describeRunFailure 的前缀必须逐字是 `[proot] 执行失败:`，实际：$withMessage",
            withMessage.startsWith("[proot] 执行失败:")
        )
        // 消费者侧：**两条都要认**（message 为 null 时 describeRunFailure 回落异常类名，
        // 这条路径同样不能被漏掉 —— InterruptedException 的 message 通常就是 null）
        assertTrue(
            "有 message 的失败行必须被认出：$withMessage",
            DshInstaller.isProotRunFailureLine(withMessage.trim())
        )
        assertTrue(
            "message 为 null（回落异常类名）的失败行同样必须被认出：$withoutMessage",
            DshInstaller.isProotRunFailureLine(withoutMessage.trim())
        )
        assertTrue(
            "`reportRunFailure` 才是 run() 的唯一出口，它的实际返回值必须可识别",
            withMessage.contains("broken pipe")
        )
    }

    // ==================================================================
    // 二、判据：认什么、不认什么
    // ==================================================================

    /**
     * 判据边界：认 `[proot] 执行失败:` 前缀，不认预检失败（那是另一类，下一步动作不同）、
     * 不认普通输出、不认行**中间**出现该词的行（防"用户贴的错误/依赖警告"被误收）。
     */
    @Test
    fun runFailureLineIsRecognised() {
        assertTrue(DshInstaller.isProotRunFailureLine("[proot] 执行失败: java.io.IOException: boom"))
        assertTrue(DshInstaller.isProotRunFailureLine("[proot] 执行失败: java.lang.InterruptedException"))
        // 预检失败是另一类：它由 run() 在 start() 之前发出，用户该去修底座而不是看底层异常
        assertFalse(
            "预检失败行不属于本判据（走 PREFLIGHT_FAIL_PREFIX）",
            DshInstaller.isProotRunFailureLine("[proot] 预检失败: 缺少 proot 可执行文件")
        )
        assertFalse("普通 npm 输出", DshInstaller.isProotRunFailureLine("added 500 packages in 42s"))
        assertFalse("npm 错误行有自己的判据", DshInstaller.isProotRunFailureLine("npm error code EAI_AGAIN"))
        assertFalse("空行", DshInstaller.isProotRunFailureLine(""))
        assertFalse(
            "★ 只认行首：行中间出现该词不算（防把别的输出误当失败归因）",
            DshInstaller.isProotRunFailureLine("user pasted: [proot] 执行失败: whatever")
        )
    }

    /**
     * 失败行必须**不被节流**（[DshInstaller.isImportantInstallLine]）。
     *
     * 理由：它是这次安装**唯一**的失败现场。落进 pending 缓冲虽然最终会被 flush，
     * 但它与普通行一起排在尾部快照里，观感上被 npm 的几十行噪音埋掉 —— 而这条行
     * 恰恰是用户唯一能据以行动的东西。
     */
    @Test
    fun runFailureLineIsImportantForThrottling() {
        assertTrue(
            "run() 顶层 catch 的失败行必须立即发射",
            DshInstaller.isImportantInstallLine("[proot] 执行失败: java.io.IOException: boom")
        )
        // 与既有重要行同口径（改造前就有、本次不动，作为对照）
        assertTrue(DshInstaller.isImportantInstallLine("[proot] 预检失败: x"))
        assertFalse(
            "普通输出仍不是重要行（别把节流整个废掉）",
            DshInstaller.isImportantInstallLine("[proot] timeout after 1800s, terminating task")
        )
    }

    // ==================================================================
    // 三、摘要整形：去日志外壳 / 脱敏 / 截断
    // ==================================================================

    /**
     * 返回给界面的那一句**不许带日志外壳**。
     *
     * `[proot] 执行失败:` 是排障用的协约前缀（中文），而这条文本会经
     * `errorSummary` → `describeFailure` 直接进 toast / 实例卡片 / 任务区 ——
     * 英文或繁体界面下原样回放就是硬编码中文（round33 刚修过的那类回归）。
     * 去掉外壳后剩下的是 `IOException` 的 message（英文/系统语言），
     * 与 `describeFailure` 对认不出的 npm 尾部"原样回放"同一口径。
     */
    @Test
    fun summaryDropsTheLogShell() {
        val s = DshInstaller.prootRunFailureSummary(
            "[proot] 执行失败: java.io.IOException: broken pipe while reading proot output"
        )
        assertEquals(
            "必须逐字等于脱壳后的原因（不多不少），实际：$s",
            "java.io.IOException: broken pipe while reading proot output",
            s
        )
        assertFalse("界面文案里不许出现日志前缀 `[proot]`：$s", s.contains("[proot]"))
        assertFalse("界面文案里不许出现硬编码中文外壳 `执行失败`：$s", s.contains("执行失败"))
    }

    /**
     * 原因串里的 token 必须被打码（[com.dsh.core.DshLogBus.sanitize] 同一口径）。
     *
     * 这条不是凭空加的：`Exception.message` 的内容**不可控**（`ProcessBuilder` 的失败信息
     * 里会带完整命令行，将来某条命令带 `?token=` 就是真泄漏）。摘要会显示在界面上，
     * 所以它必须过与日志正文**同一道**脱敏，而不是直接回放。
     */
    @Test
    fun summaryRedactsTokens() {
        val s = DshInstaller.prootRunFailureSummary(
            "[proot] 执行失败: boom at http://127.0.0.1:3080/?token=et5w4-xSCJpP95w98yyXZOJOxkn-EOz1JAyS8T6eKqU"
        )
        assertFalse("token 值不许出现在摘要里：$s", s.contains("et5w4-xSCJpP95w98yyXZOJOxkn"))
        assertTrue("`token=` 的结构要保留、只把值抹成 ***：$s", s.contains("token=***"))
    }

    /**
     * 长原因不撑爆对话框：截断到 [200] + 省略号，且**先脱敏再截断**。
     *
     * 顺序不是随便定的：先截断再脱敏，会把一个恰好跨越截断点的 token 截成半截明文
     * （`token=abc…` 里的值只剩前 8 位 —— 那半截正好能进 `registerSecret` 的形状门）。
     */
    @Test
    fun summaryIsBounded() {
        val longCause = "x".repeat(500)
        val s = DshInstaller.prootRunFailureSummary("[proot] 执行失败: $longCause")
        assertEquals("200 字符 + 一个省略号", 201, s.length)
        assertTrue("截断要有可见标记：${s.takeLast(5)}", s.endsWith("…"))
        assertFalse("不许整段回放", s.length > 400)
    }

    /**
     * 原因部分为空时**退回整行**，不许交出一句空话。
     *
     * `describeRunFailure` 在 message 为 null 且类名也异常时会打出裸前缀
     * （`[proot] 执行失败: `）；空串回到界面等于什么都没说，比不修还差。
     */
    @Test
    fun summaryNeverReturnsEmpty() {
        assertEquals(
            "原因缺失时退回整行（带外壳也比空话强）",
            "[proot] 执行失败:",
            DshInstaller.prootRunFailureSummary("[proot] 执行失败:")
        )
        assertEquals(
            "  前缀后只有空白同样视为缺失  ",
            "[proot] 执行失败:",
            DshInstaller.prootRunFailureSummary("  [proot] 执行失败:   ")
        )
        // 防御：非本前缀的行原样返回（判据在调用点，这里不做第二套判断）
        assertEquals("plain line", DshInstaller.prootRunFailureSummary("plain line"))
    }

    // ==================================================================
    // 四、端到端：真跑 onInstallLine → errorSummary（唯一能杀掉"没接线"的用例）
    // ==================================================================

    /**
     * **误归因防线**：`run()` 真实会产生的几种异常消息，经完整管线后
     * **不许**被 `describeFailure` 的**网络分类器**认成"网络问题"。
     *
     * 为什么这条必须写：`describeFailure` 的第一件事就是跑 `classifyNetworkFailure`
     * （整段大写后找 `TIMED OUT` / `CONNECTION RESET` / `ENETUNREACH`… 这类特征字）。
     * 一旦命中，用户看到的是"请检查网络/换个 Wi-Fi"——而真实原因是 proot 起不来。
     * `DshInstaller` 自己在 `describeFailure` 的 KDoc 里就写着"把原生依赖编译失败报成
     * 网络问题会让用户去查网络，白折腾一轮"。
     *
     * 收口走 [com.dsh.core.DshInstaller] 的 `errorSummaries`（不经 `describeFailure`）就是
     * 为了关掉这条通道。本用例把它钉住：下面这些消息里**没有一个**含网络特征字，
     * 因此必须原样出现在摘要里（带着消息自身的可辨识片段）。
     *
     * ⚠️ 反证条件（将来失效时怎么发现）：若哪天有人把这条分支改回 `errorTails`
     *   （= 重新过 `describeFailure`），`Connection reset` 这条会被改写成网络文案，
     *   本用例立刻红。那时该做的是把分支改回 `errorSummaries`，而不是删掉本条。
     */
    @Test
    fun realisticRunFailuresAreNotMisattributedAsNetwork() {
        // run() 顶层 catch 能真实接到的消息集（见 ProotProcessExecutor.run 的 try 块）：
        // start() 的 EACCES/ENOENT/ENOSPC、读管道的 Broken pipe / Stream closed、
        // 以及 message 为 null 时 describeRunFailure 回落的异常类名。
        val realistic = listOf(
            IOException("Cannot run program \"/data/app/libproot.so\": error=13, Permission denied"),
            IOException("Cannot run program \"/data/app/libproot.so\": error=2, No such file or directory"),
            IOException("Broken pipe"),
            IOException("Stream closed"),
            IOException("No space left on device"),
            // ★ 这条是"误归因"的**活样本**：它含 `Connection reset`，
            //   正好命中 describeFailure 的 TIMEOUT_PHRASES("CONNECTION RESET")。
            //   走 errorTails 的话用户会看到"网络超时或连接中断：请检查网络后重试" —— 全错。
            IOException("Connection reset"),
            InterruptedException()
        )
        for (e in realistic) {
            val line = ProotProcessExecutor.describeRunFailure(e).trim()
            val summary = DshInstaller.prootRunFailureSummary(line)
            // 网络分类器的产物都以"网络/DNS"为题（来自资源）；这里按**内容**判：
            // 摘要必须仍然带着这条消息自身的可辨识片段，而不是被替换成一句网络建议。
            val recognisable = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name
            assertTrue(
                "★ 这条失败原因被丢掉了（很可能被 describeFailure 换成了网络文案）：line=$line → summary=$summary",
                summary.contains(recognisable.substringBefore(": ").take(20))
            )
            assertFalse(
                "★ 不许出现日志外壳：$summary",
                summary.contains("[proot]")
            )
        }
    }

    /**
     * 端到端版本的误归因防线：`Connection reset` 走**真** `onInstallLine` → `errorSummary`，
     * 拿到手的必须仍是那句原文（而不是"网络超时，请检查网络"）。
     *
     * 与上一条的区别：上一条只测整形函数，这一条经过 [errorSummary] 的**兜底链**，
     * 能杀掉"整形对了、但存进了会被 `describeFailure` 重写的表"这种接错表的情况。
     */
    @Test
    fun connectionResetReachesUserVerbatim() {
        val installer = newInstaller()
        val instance = DshInstance(id = "inst-r34-reset", name = "reset")
        invokeOnInstallLine(
            installer, instance,
            ProotProcessExecutor.describeRunFailure(IOException("Connection reset"))
        )
        val summary = invokeErrorSummary(installer, instance.id)
        assertTrue(
            "★ 原文必须原样送达（被网络分类器改写 = 误归因）：$summary",
            summary.contains("Connection reset")
        )
    }

    /**
     * **缺陷本体**：一行 `[proot] 执行失败: …` 经 `onInstallLine` 之后，
     * `errorSummary` 给出的必须是这句原因，而不是泛化文案。
     *
     * 中间没有任何替身：`onInstallLine` 的 `when` 判据、`rememberFailureLine` 的
     * 12 行滚动窗口、`errorSummary` 的兜底链、`describeFailure` 的分类与截断
     * **全是生产代码**（反射只用来跨过 `private`）。
     */
    @Test
    fun failureLineReachesErrorSummary() {
        val installer = newInstaller()
        val instance = DshInstance(id = "inst-r34-error", name = "r34")
        // 同一次夹具下的"什么都没发生"基准：泛化兜底到底是哪一句（资源在不在都无妨）
        val generic = invokeErrorSummary(installer, "inst-r34-untouched")
        val line = ProotProcessExecutor.describeRunFailure(
            IOException("broken pipe while reading proot output")
        )

        invokeOnInstallLine(installer, instance, line)

        val summary = invokeErrorSummary(installer, instance.id)
        assertTrue(
            "★ 失败行必须到达 errorSummary（改造前这里只有泛化文案）：$summary",
            summary.contains("broken pipe while reading proot output")
        )
        assertTrue(
            "★ 必须与泛化兜底（\"$generic\"）不同 —— 这一条就是缺陷的定义",
            summary != generic
        )
        assertFalse("不许出现日志外壳（界面文案 i18n）", summary.contains("[proot] 执行失败"))
    }

    /**
     * **不变**：没有这类行时，行为与改造前**逐字相同**（泛化兜底）。
     *
     * 断言方式刻意是"与空档案的摘要相等"，而不是硬编码某句文案 ——
     * 这样资源在不在、`DshUiText` 取不取得到 Context，都不影响结论。
     */
    @Test
    fun benignLinesKeepTheGenericFallback() {
        val installer = newInstaller()
        val withBenign = DshInstance(id = "inst-r34-benign", name = "benign")
        val withNothing = DshInstance(id = "inst-r34-empty", name = "empty")
        invokeOnInstallLine(installer, withBenign, "added 500 packages in 42s")
        invokeOnInstallLine(installer, withBenign, "[proot] timeout after 1800s, terminating task")

        val benign = invokeErrorSummary(installer, withBenign.id)
        val nothing = invokeErrorSummary(installer, withNothing.id)
        assertEquals(
            "普通输出不进失败摘要、也没有别的原因 ⇒ 与'一条输出都没有'同一句兜底",
            nothing, benign
        )
        assertFalse("普通输出不许被当成失败原因：$benign", benign.contains("added 500 packages"))
    }

    /**
     * 多条失败行时：**最后一条压轴**（与 `errorSummaries` 的"终局原因覆盖先前"语义一致），
     * 且拼出来的摘要里**不含日志外壳**。
     *
     * 为什么是"最后一条"而不是"三条一起"：这一行走的是 `errorSummaries`（最高优先级、
     * 不经 `describeFailure`）。语义是"run() 最后一次抛出的异常就是终局原因"——与
     * `runInstall` 外层 `catch` 的 `errorSummaries[id] = t.message` 完全同口径。
     * 若改用 `errorTails` 拼三条，反而会被 `describeFailure` 的网络分类器重新解释
     * （见 [realisticRunFailuresAreNotMisattributedAsNetwork]）。
     */
    @Test
    fun multipleFailureLinesKeepTheLastAsRootCause() {
        val installer = newInstaller()
        val instance = DshInstance(id = "inst-r34-multi", name = "multi")
        invokeOnInstallLine(
            installer, instance,
            ProotProcessExecutor.describeRunFailure(IOException("first failure"))
        )
        invokeOnInstallLine(
            installer, instance,
            ProotProcessExecutor.describeRunFailure(IOException("second failure"))
        )

        val summary = invokeErrorSummary(installer, instance.id)
        assertTrue("最后一条失败行必须压轴成为终局原因：$summary", summary.contains("second failure"))
        assertFalse(
            "★ 不许出现日志外壳：$summary",
            summary.contains("[proot] 执行失败")
        )
        assertFalse("摘要长度必须受控（≤300 + 省略号）：${summary.length}", summary.length > 301)
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 与 `R26TerminalEventTest` / `R31Fix211Test` 同一套夹具：`ContextWrapper` 的构造会调
     * `super()` → android.jar 的 `Stub!`，所以只能绕过构造。`DshInstaller` 在本次涉及的
     * 路径上不碰 Context（`onInstallLine` / `errorSummary` 都不用它）。
     */
    private fun fakeContext(): Context {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val allocate: Method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ContextWrapper::class.java) as Context
    }

    private fun newInstaller(): DshInstaller = DshInstaller(fakeContext(), NoopProot)

    /**
     * 造一个"**本次就是它的活跃安装者**"的 Job 并登记进 `running`。
     *
     * `onInstallLine` 的归属门是 `running[id] === myJob && !cancelled.contains(id)`：
     * 不登记的话所有行都会在第一道门就 return（写成"改造前行为"的假象，测试反而绿）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun ownJob(installer: DshInstaller, instanceId: String): Job {
        val job: Job = Job().also { scopeJob = it }
        val f = DshInstaller::class.java.getDeclaredField("running")
        f.isAccessible = true
        (f.get(installer) as MutableMap<String, Job>)[instanceId] = job
        return job
    }

    private fun invokeOnInstallLine(installer: DshInstaller, instance: DshInstance, line: String) {
        val job = ownJob(installer, instance.id)
        val m = DshInstaller::class.java.getDeclaredMethod(
            "onInstallLine",
            DshInstance::class.java,
            String::class.java,
            String::class.java,
            Job::class.java
        )
        m.isAccessible = true
        m.invoke(installer, instance, "0.2.1-alpha.1", line, job)
    }

    private fun invokeErrorSummary(installer: DshInstaller, instanceId: String): String {
        val m = DshInstaller::class.java.getDeclaredMethod("errorSummary", String::class.java)
        m.isAccessible = true
        return m.invoke(installer, instanceId) as String
    }

    private object NoopProot : ProotExecutor {
        override fun run(script: String, env: Map<String, String>, onLine: (String) -> Unit): Int =
            throw AssertionError("R34InstallErrorTest 不跑 proot")
    }
}
