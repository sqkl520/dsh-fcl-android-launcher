package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.ProotProcessExecutor
import com.dsh.core.ProotRunner
import com.dsh.core.ProotTermination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * round31 / slice-04（错误处理切片）eh-01 ~ eh-05 的回归夹具。
 *
 * ## 为什么这样钉
 * 五条缺陷全在**异常路径**上，而异常路径在 JVM 单测里没有真进程可造（没有 proot、
 * `android.os.Process` 是 android.jar 的桩、`/proc/<pid>` 造不出来）。做法与 [ProotTerminationTest]
 * 一致分两层：
 *
 * 1. **机制层**：把"要不要做某事"的判断收成可调用的小函数/纯逻辑，直接断言。这一层能真正证明
 *    行为（例如"读不到存活状态时 destroy() 必须照发"是真的驱动了生产代码的 `suspend` 路径，
 *    用的是 `java.lang.Process` 的假子类，不是反射改字段）。
 * 2. **接线层**（源码切片）：钉住"生产代码真的用了这个机制"。只把助手写对、却不接回调用点，
 *    行为依然是坏的，而这一层 JVM 跑不到 —— 本仓已有先例（[R29CopyDeferTest] 的说明）。
 *    读不到源码时**直接失败**，不静默跳过（静默跳过 = 把防回退锚点变成摆设）。
 *
 * ## 覆盖矩阵
 * - eh-01：`notifyLine`（回调抛出不得穿出）/ `describeRunFailure`（message=null 不许打印裸 null）/
 *   `reportRunFailure`（恢复中断标志 + 返回 -1 + 回调再抛也不穿出）+ `run()` 两处调用点的接线锚。
 * - eh-02：`shouldReapAfterRun`（读不到存活时**不许**摘表）+ finally 接线锚。
 * - eh-03：`Handle.terminate` 三态入口 + 三处失败的归因留痕 + `waitFor` 超时**不**记成失败 + 接线锚。
 * - eh-04：**不修**（调用方 `DshRuntime.startLocked` 已有 try/catch → `State.Failed`）。只留前提锚：
 *   若将来有人删掉那个 catch，本锚先红 —— 那时 eh-04 才真的成立。
 * - eh-05：`forEachIsolated`（第 N 项抛错不得中止第 N+1 项，含 `Error` 与归因回调自炸）+
 *   `destroyActive` 的接线锚（必须走它、失败行带 pid/tag、签名仍不返回结论）。
 *
 * ★ DshLogBus 是进程内单例：凡断言全局日志的用例都在 [Before] 里清一次，避免被别的用例污染。
 *
 * 运行：`TEST_SRC=<只含本文件的临时目录> sh run-tests.sh`
 *      或 `./gradlew :FCL:testDebugUnitTest --tests "com.dsh.R31ProotExecutorTest"`
 */
class R31ProotExecutorTest {

    /** 每个用例都从"全局日志为空"开始（DshLogBus 是单例，日志断言必须看到确定起点）。 */
    @Before
    fun clearLogBus() {
        DshLogBus.clear()
        DshLogBus.flushNow()
    }

    // ======================================================================
    // eh-01：run() 的失败路径
    // ======================================================================

    /**
     * eh-01 核心：`notifyLine` 必须把回调异常**吃掉**并如实回报"抛了"。
     *
     * 缺陷现场：`run()` 返回 `Int`，预检失败行与顶层 catch 两处裸调 `onLine` —— 回调一抛，
     * 异常从 `run()` 穿出（`finally` 只清登记表、随后继续上抛），`: Int` 契约当场作废。
     */
    @Test
    fun notifyLineSwallowsCallbackExceptionAndReportsIt() {
        var received: String? = null
        val threw = ProotProcessExecutor.notifyLine({ received = it }, "line-a")
        assertFalse("正常回调不得被判成抛异常", threw)
        assertEquals("回调必须收到原文（不得改写/截断）", "line-a", received)

        val threw2 = ProotProcessExecutor.notifyLine(
            { throw IllegalStateException("callback exploded") },
            "line-b"
        )
        assertTrue("回调抛异常时必须回报 true（且不得穿出）", threw2)
        assertEquals("抛异常的那一行只能投递一次（不许重试轰炸回调）", "line-a", received)
    }

    /**
     * eh-01：`InterruptedException.message` 通常是 **null**，旧日志会退化成
     * `[proot] 执行失败: null` —— 看不出失败类别、看不出是哪个异常。
     */
    @Test
    fun describeRunFailureNeverPrintsBareNull() {
        val noMessage = ProotProcessExecutor.describeRunFailure(InterruptedException())
        assertFalse("message=null 时不许出现裸 null：$noMessage", noMessage.contains("null"))
        assertTrue(
            "message 缺失时必须回落成异常类名（否则日志无法定位失败类别）：$noMessage",
            noMessage.contains("InterruptedException")
        )
        assertTrue("前缀必须保持可检索（日志过滤/排障按它找）：$noMessage", noMessage.contains("[proot] 执行失败:"))

        val withMessage = ProotProcessExecutor.describeRunFailure(java.io.IOException("proot binary missing"))
        assertTrue("有 message 时必须原样带上：$withMessage", withMessage.contains("proot binary missing"))
        assertFalse("有 message 时不许混进类名占位：$withMessage", withMessage.contains("java.io.IOException"))

        // 空白 message 与 null 等价（"执行失败: " 后面什么都没有同样不可定位）
        val blank = ProotProcessExecutor.describeRunFailure(java.io.IOException("   "))
        assertTrue("空白 message 也要回落成类名：$blank", blank.contains("java.io.IOException"))
    }

    /**
     * eh-01：顶层 catch 的唯一出口 ——
     * 1) `InterruptedException` 必须**恢复中断标志**（旧实现吞掉后标志永久丢失，取消信号失效）；
     * 2) 回调再抛也不许从 `run()` 穿出（返回值契约 `: Int`）；
     * 3) 返回值仍是 `-1`（调用方只区分 `0/非0` 与 [ProotProcessExecutor.TIMEOUT_EXIT_CODE]）。
     */
    @Test
    fun reportRunFailureRestoresInterruptFlagAndNeverThrows() {
        assertFalse("前置：本用例开始时当前线程不应处于中断态", Thread.currentThread().isInterrupted)
        try {
            val code = ProotProcessExecutor.reportRunFailure(InterruptedException("cancelled")) { line ->
                // 回调自己抛：诊断的投递不许把异常带回去
                throw RuntimeException("onLine exploded: $line")
            }
            assertEquals("失败码必须仍是 -1（调用方按 0/非0 判定，改动会波及 DshInstaller）", -1, code)
            assertTrue(
                "InterruptedException 必须恢复中断标志 —— 否则取消信号被吞掉（eh-01 的原始缺陷）",
                Thread.currentThread().isInterrupted
            )
        } finally {
            Thread.interrupted() // 清标志，避免污染同 JVM 里后续用例
        }
    }

    /** eh-01：普通异常不得去置中断标志（否则每次失败都让线程"看起来被取消"）。 */
    @Test
    fun reportRunFailureDoesNotFakeInterruptForOrdinaryFailures() {
        assertFalse(Thread.currentThread().isInterrupted)
        val lines = mutableListOf<String>()
        val code = ProotProcessExecutor.reportRunFailure(java.io.IOException("pipe broken")) { lines += it }
        assertEquals(-1, code)
        assertFalse("普通 IOException 不得置中断标志", Thread.currentThread().isInterrupted)
        assertEquals("必须恰好投递一行诊断", 1, lines.size)
        assertTrue("诊断要能定位原因：$lines", lines[0].contains("pipe broken"))
    }

    // ======================================================================
    // eh-02：finally 的 fail-open 默认值
    // ======================================================================

    /**
     * eh-02 本体：`isAlive` 读不到时，**不许**把进程从登记表摘掉。
     *
     * 旧写法 `shouldReapOnFinally(runCatching { p.isAlive }.getOrDefault(false))` 在异常时得到
     * `false`（= 已退出）⇒ 摘表 ⇒ `destroyActive` 再也找不到这条进程 ⇒ 取消安装杀不掉它。
     */
    @Test
    fun shouldReapAfterRunKeepsProcessWhenAlivenessIsUnknown() {
        val throwing = FakeProcess(aliveProbe = { throw SecurityException("cannot read state") })
        assertFalse(
            "读不到存活状态时必须留在表里（fail-closed）；返回 true = 永久失联（eh-02 的原始缺陷）",
            ProotTermination.shouldReapAfterRun(throwing)
        )
    }

    /** eh-02 的正向两态：确知已退出才摘；确知还活着必须留。 */
    @Test
    fun shouldReapAfterRunReapsOnlyConfirmedExited() {
        assertTrue(
            "确知已退出 → 可以摘表（否则登记表只增不减）",
            ProotTermination.shouldReapAfterRun(FakeProcess(aliveProbe = { false }))
        )
        assertFalse(
            "确知还活着 → 必须留在表里（否则取消再也找不到它）",
            ProotTermination.shouldReapAfterRun(FakeProcess(aliveProbe = { true }))
        )
    }

    /**
     * eh-02：判据**只能**看存活状态，不得因"进程对象本身的其它毛病"而改变摘表决定。
     * （这里用显式探针证明参数化入口走的是探针结果，而不是某个默认真值。）
     */
    @Test
    fun shouldReapAfterRunUsesTheProbeResult() {
        val p = FakeProcess(aliveProbe = { true })
        assertFalse("探针说活着就该留", ProotTermination.shouldReapAfterRun(p) { false.not() })
        assertTrue("探针说死了就该摘（即便是同一个 Process 实例）", ProotTermination.shouldReapAfterRun(p) { false })
    }

    // ======================================================================
    // eh-03：Handle.terminate
    // ======================================================================

    /**
     * eh-03 第一半：读不到存活状态时**必须仍然走 teardown**。
     *
     * 旧实现在入口用 `if (!isAlive()) return`（`isAlive` 把读不到折成 false）⇒ 直接返回，
     * 连 `destroy()` 都不发，整个停止动作被静默跳过。
     */
    @Test
    fun handleTerminateStillSendsDestroyWhenAlivenessIsUnknown() {
        val destroys = AtomicInteger(0)
        val process = FakeProcess(
            aliveProbe = { throw SecurityException("cannot read state") },
            onDestroy = { destroys.incrementAndGet() },
            onWait = { true } // 等到了"已退出"，不需要升级强杀
        )
        runBlocking { ProotRunner.Handle(process).terminate(10) }
        assertEquals(
            "读不到存活状态 ≠ 已退出：destroy() 必须照发（旧实现 0 次 = 整段 teardown 被跳过）",
            1, destroys.get()
        )
    }

    /** 确知已死时仍要早退（不做无用功、也不产生噪音日志）。 */
    @Test
    fun handleTerminateReturnsEarlyOnlyWhenConfirmedDead() {
        val destroys = AtomicInteger(0)
        val process = FakeProcess(
            aliveProbe = { false },
            onDestroy = { destroys.incrementAndGet() }
        )
        runBlocking { ProotRunner.Handle(process).terminate(10) }
        assertEquals("确知已退出 → 不该再发 destroy()", 0, destroys.get())
    }

    /**
     * eh-03 第二半：三处失败不许零诊断。旧实现对 `destroy()` / `waitFor` /
     * `destroyForcibly()` 一律 `runCatching {}` 吞掉且不留痕，调用方无法得知"没杀掉"。
     * 对照同文件 fix12 已跟改的 `ProotProcessExecutor.terminate`。
     */
    @Test
    fun handleTerminateLogsFailureWithPidAttribution() {
        val process = FakeProcess(
            aliveProbe = { true },
            onDestroy = { throw IllegalStateException("destroy denied") },
            onWait = { false }
        )
        // 不抛 —— best-effort 契约不变（返回值仍是 Unit）
        runBlocking { ProotRunner.Handle(process).terminate(10) }

        DshLogBus.flushNow()
        val log = DshLogBus.export()
        assertTrue(
            "destroy() 失败必须留一行诊断（旧实现整段静默，正是 fix12 在同文件另一处已修的同一缺陷类）：$log",
            log.contains("handle.terminate: destroy() failed for pid=")
        )
        assertTrue("诊断必须能定位到具体进程：$log", log.contains("pid=<unknown>"))
        assertTrue("必须带原因（否则只知道失败、不知道为什么）：$log", log.contains("destroy denied"))
    }

    /** eh-03 的第三个失败点：`destroyForcibly()`。宽限到期仍活着 → 强杀；强杀失败同样要留痕。 */
    @Test
    fun handleTerminateLogsForcibleKillFailure() {
        val forcible = AtomicInteger(0)
        val process = FakeProcess(
            aliveProbe = { true },
            onWait = { false },              // 宽限内没退出
            onForceKill = {
                forcible.incrementAndGet()
                throw IllegalStateException("kill denied")
            }
        )
        runBlocking { ProotRunner.Handle(process).terminate(10) }

        assertEquals("宽限到期仍活着 → 必须尝试强杀", 1, forcible.get())
        DshLogBus.flushNow()
        val log = DshLogBus.export()
        assertTrue(
            "destroyForcibly() 失败必须留痕（否则「取消了但 npm 还在跑」查无实据）：$log",
            log.contains("handle.terminate: destroyForcibly() failed for pid=")
        )
        assertTrue("强杀失败也要带原因：$log", log.contains("kill denied"))
    }

    /**
     * eh-03 的边界：`waitFor` 返回 `false`（**宽限超时**）不是异常 —— 不许为它记失败日志。
     *
     * 这条是"别把正常路径刷成错误"的守卫：宽限超时后升级强杀是设计行为，
     * 若哪天有人把 `exited == false` 也当失败记一行，日志页会在每次停止时多出一条假错误。
     */
    @Test
    fun handleTerminateDoesNotLogTimeoutAsFailure() {
        val process = FakeProcess(aliveProbe = { true }, onWait = { false })
        runBlocking { ProotRunner.Handle(process).terminate(10) }
        DshLogBus.flushNow()
        val log = DshLogBus.export()
        assertFalse("宽限超时是正常升级路径，不许记成失败：$log", log.contains("waitFor"))
        assertFalse("也不许把整行写进日志（本路径正常时应当零输出）：$log", log.contains("handle.terminate"))
    }

    /**
     * eh-03：`waitFor` 自身抛异常时也必须留痕（它同样是"没杀掉"的一条真实原因），
     * 并且**不得**因此跳过强杀 —— 那是唯一还能把它带走的把手。
     */
    @Test
    fun handleTerminateLogsWaitFailureAndStillEscalates() {
        val forcible = AtomicInteger(0)
        val process = FakeProcess(
            aliveProbe = { true },
            onWait = { throw IllegalStateException("wait interrupted") },
            onForceKill = { forcible.incrementAndGet() }
        )
        runBlocking { ProotRunner.Handle(process).terminate(10) }

        assertEquals("waitFor 失败 ≠ 已退出：强杀仍必须尝试", 1, forcible.get())
        DshLogBus.flushNow()
        val log = DshLogBus.export()
        assertTrue("waitFor 失败必须留痕：$log", log.contains("handle.terminate: waitFor("))
        assertTrue("并带原因：$log", log.contains("wait interrupted"))
    }

    // ======================================================================
    // eh-05：destroyActive 的异常边界粒度
    // ======================================================================

    /**
     * eh-05 本体：**第 N 项抛错不得中止第 N+1 项**。
     *
     * 旧实现把 `runCatching` 罩在整轮 `forEach` 之外：一条进程出问题，"取消全部"就退化成
     * "取消了一部分"，而用户侧看到的仍是"已取消"。
     */
    @Test
    fun forEachIsolatedContinuesAfterItemFailure() {
        val visited = mutableListOf<String>()
        val failures = mutableListOf<String>()
        ProotTermination.forEachIsolated(
            items = listOf("a", "b", "c"),
            onItemFailure = { item, e -> failures += "$item:${e.message}" }
        ) { item ->
            visited += item
            if (item == "b") throw IllegalStateException("boom-b")
        }
        assertEquals("c 必须仍被处理（旧实现到 b 就停）", listOf("a", "b", "c"), visited)
        assertEquals("失败项必须被归因一次（且只有一次）", listOf("b:boom-b"), failures)
    }

    /**
     * eh-05：捕 `Throwable` 而不只是 `Exception` —— 与 `runCatching` 口径一致。
     * 极端资源耗尽时 `Thread.start()` 抛 `OutOfMemoryError`，那也只该让**这一条**没杀掉。
     */
    @Test
    fun forEachIsolatedAlsoSurvivesErrors() {
        val visited = mutableListOf<Int>()
        ProotTermination.forEachIsolated(
            items = listOf(1, 2, 3),
            onItemFailure = { _, _ -> /* 故意什么也不做 */ }
        ) { item ->
            visited += item
            if (item == 1) throw OutOfMemoryError("simulated")
        }
        assertEquals("Error 之后也必须继续（runCatching 的本口径）", listOf(1, 2, 3), visited)
    }

    /** eh-05：归因回调自己抛错时，循环同样不许被打断（"为失败加的诊断"不能成为新崩溃点）。 */
    @Test
    fun forEachIsolatedSurvivesFailureHandlerThrowing() {
        val visited = mutableListOf<Int>()
        ProotTermination.forEachIsolated(
            items = listOf(1, 2),
            onItemFailure = { _, _ -> throw IllegalStateException("logger exploded") }
        ) { item ->
            visited += item
            if (item == 1) throw IllegalStateException("boom")
        }
        assertEquals("归因日志自己炸掉也不许中止后续项", listOf(1, 2), visited)
    }

    /** eh-05：空列表不产生任何回调（"取消时没有进程在跑"必须是无操作，而不是异常）。 */
    @Test
    fun forEachIsolatedOnEmptyListIsNoOp() {
        val visited = mutableListOf<Int>()
        ProotTermination.forEachIsolated(
            items = emptyList<Int>(),
            onItemFailure = { _, _ -> throw AssertionError("空列表不该有失败回调") }
        ) { visited += it }
        assertTrue("空列表 → 零次 action", visited.isEmpty())
    }

    // ======================================================================
    // 接线锚（源码级）：上面钉"机制对不对"，这里钉"生产代码真的用了它"
    // ======================================================================
    //
    // 为什么需要这一层：把 `notifyLine`/`shouldReapAfterRun`/`forEachIsolated` 写对了，却没有接回
    // 调用点，行为依然是坏的 —— 而 JVM 单测没有真进程/真 Context，跑不到那几处。
    // 读不到源码时**直接失败**，不静默跳过。

    /** eh-01 接线：`run()` 的预检失败行与顶层 catch 必须走带保护的助手，不得再裸调 `onLine`。 */
    @Test
    fun runFailurePathsAreWiredThroughProtectedHelpers() {
        val text = sourceOfExecutor()

        assertTrue(
            "eh-01：预检失败行必须走 notifyLine（裸调 onLine 会让回调异常穿出 run(): Int）",
            text.contains("notifyLine(onLine, \"[proot] 预检失败:")
        )
        assertFalse(
            "eh-01：不得再有裸调 `onLine(\"[proot] 预检失败:`（缺陷原文形态）",
            Regex("""\bonLine\("\[proot\] 预检失败:""").containsMatchIn(text)
        )
        assertTrue(
            "eh-01：顶层 catch 必须走 reportRunFailure（恢复中断标志 + 安全投递 + 非 null 文案）",
            text.contains("reportRunFailure(e, onLine)")
        )
        assertFalse(
            "eh-01：catch 里不得再裸调 onLine(\"[proot] 执行失败",
            Regex("""\bonLine\("\[proot\] 执行失败""").containsMatchIn(text)
        )
        assertTrue(
            "eh-01：中断标志必须被恢复（这是原始缺陷的一半）",
            text.contains("Thread.currentThread().interrupt()")
        )
    }

    /** eh-02 接线：finally 的判据必须走 [ProotTermination.shouldReapAfterRun]，而不是 `getOrDefault(false)`。 */
    @Test
    fun finallyReapDecisionUsesFailClosedSeam() {
        val text = sourceOfExecutor()
        val finallyBlock = sliceBetween(text, "} finally {", "/**\n     * 终止正在跑的子进程")
        assertTrue(
            "eh-02：finally 必须走 shouldReapAfterRun",
            finallyBlock.contains("ProotTermination.shouldReapAfterRun(p)")
        )
        assertFalse(
            "eh-02：不得再出现 isAlive 的 fail-open 默认值参与摘表决定",
            Regex("""val alive = runCatching \{ p\.isAlive \}\.getOrDefault\(false\)""").containsMatchIn(finallyBlock)
        )
        assertFalse(
            "eh-02：finally 里不得再**调用** shouldReapOnFinally（它不知道'读不到'这一态；注释里的引用不算）",
            Regex("""shouldReapOnFinally\s*\(""").containsMatchIn(finallyBlock)
        )
    }

    /**
     * eh-02 边界（**不得推翻 C-13**）：kill 升级路径的 `getOrDefault(false)` 必须**原样保留**。
     *
     * E3 第五节已显式否决"默认改 true"并推迟本轮；本修复只动"摘登记表"那一处，不动"要不要强杀"。
     * 这条锚把那条被推迟的决定钉住：若将来有人"顺手统一"把这里也改成 fail-closed，本锚先红，
     * 提醒他那是另一张单、且已有反证。
     */
    @Test
    fun forceKillEscalationKeepsDeferredC13Default() {
        val text = sourceOfExecutor()
        val killThread = sliceBetween(text, "Thread {\n            try {\n                val exited = runCatching { p.waitFor(", "}.apply { isDaemon = true; name = \"dsh-proot-kill\"")
        assertTrue(
            "C-13：kill 升级路径的 getOrDefault(false) 必须原样保留（E3 已显式推迟，不得擅自推翻）",
            killThread.contains("getOrDefault(false)")
        )
        assertTrue(
            "C-13：推迟理由的那行注释必须还在（后来者要能查到为什么没改）",
            text.contains("C-13")
        )
    }

    /** eh-05 接线：`destroyActive` 必须走 forEachIsolated，不得再有罩住整轮的 runCatching。 */
    @Test
    fun destroyActiveUsesPerItemIsolationAndAttributesFailures() {
        val text = sourceOfExecutor()
        val destroyActive = sliceBetween(text, "fun destroyActive(", "private fun terminate(active: Active)")

        assertTrue(
            "eh-05：destroyActive 必须走 ProotTermination.forEachIsolated（每条一个异常边界）",
            destroyActive.contains("ProotTermination.forEachIsolated(")
        )
        assertFalse(
            "eh-05：整轮 runCatching 的旧形态必须消失（它会让第 N+1 条起全被跳过）",
            Regex("""runCatching \{\s*val snapshot = synchronized""").containsMatchIn(destroyActive)
        )
        assertTrue(
            "eh-05：失败日志必须带 pid 与 tag 归因（只留一句 destroyActive failed 无法定位）",
            destroyActive.contains("pid=\${a.pid}") && destroyActive.contains("tag=\${a.tag")
        )
        assertTrue(
            "C-11 语义必须保持：destroyActive 仍然不返回任何结论（best-effort）",
            Regex("""fun destroyActive\(tag: String\? = null\) \{""").containsMatchIn(destroyActive)
        )
    }

    /** eh-03 接线：`Handle.terminate` 的入口判据必须是三态，而不是 `!isAlive()` 早退。 */
    @Test
    fun handleTerminateGuardsOnTriStateNotFailOpenIsAlive() {
        val text = sourceOfExecutor()
        val block = sliceBetween(text, "suspend fun terminate(graceMillis: Long = 5000)", "private fun isAliveTriState")
        assertTrue(
            "eh-03：入口必须用三态判据（读不到 ≠ 已退出）",
            block.contains("isAliveTriState() == false")
        )
        assertFalse(
            "eh-03：不得再用 `if (!isAlive()) return@withContext`（读不到状态会跳过整段 teardown）",
            block.contains("if (!isAlive()) return@withContext")
        )
        assertTrue("eh-03：destroy 失败必须留痕", block.contains("logTerminateFailure(\"destroy()\""))
        assertTrue("eh-03：waitFor 失败必须留痕", block.contains("logTerminateFailure(\"waitFor("))
        assertTrue("eh-03：强杀失败必须留痕", block.contains("logTerminateFailure(\"destroyForcibly()\""))
        assertTrue(
            "eh-03：强杀判据也必须用三态（旧 isAlive() 在'读不到'时导致漏强杀）",
            block.contains("!exited && isAliveTriState() != false")
        )
    }

    /**
     * eh-04 **不修**的证据锚：调用方已有 try/catch 并翻译成失败态（alternative 1 成立）。
     *
     * 这条锚的作用：若将来有人删掉那个 catch，"start 抛错无人兜"就真的成立了 —— 这里先红。
     */
    @Test
    fun eh04CallerStillCatchesStartFailure() {
        val runtime = resolveSource("core/DshRuntime.kt")
        assertNotNull("找不到 DshRuntime.kt 源码", runtime)
        val text = runtime!!.readText()
        val startCall = sliceBetween(
            text,
            "ProotRunner.start(\n                spec = spec,",
            "handle = ProcessHandle(h, instance.id)"
        )
        assertTrue(
            "eh-04 alternative 1 的前提：ProotRunner.start 必须被 try/catch 包住并落到 Failed 状态",
            startCall.contains("} catch (e: Exception) {") && startCall.contains("State.Failed(")
        )
        assertTrue(
            "且必须上报任务终态（BL-152），否则用户只看到任务行消失",
            startCall.contains("reportRuntime(instance.id, instance.name, DshTask.State.FAILED")
        )
        assertTrue(
            "且必须把失败返回给调用方（不是静默吞掉）",
            startCall.contains("return StartOutcome.Failed(reason)")
        )
    }

    // ======================================================================
    // 夹具
    // ======================================================================

    /**
     * `java.lang.Process` 的假子类：只覆写被测代码真正走到的成员。
     *
     * 默认值取"健康进程"：活着、destroy 什么也不做、宽限内退出。每条用例只翻它关心的那一个开关
     * （与 [ProotTerminationTest] 的"只翻一个维度"同一纪律）。
     *
     * ⚠️ `isAlive` / `destroy` / `destroyForcibly` / `waitFor` 在 `java.lang.Process` 里都是**可覆写**
     * 的（无 final），这正是本夹具能驱动真实 `terminate` 代码路径的原因 —— 不是靠反射改字段，
     * 而是走生产代码自己的调用序列。
     */
    private class FakeProcess(
        private val aliveProbe: () -> Boolean = { true },
        private val onDestroy: () -> Unit = {},
        private val onWait: (Long) -> Boolean = { true },
        private val onForceKill: () -> Unit = {}
    ) : java.lang.Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = onWait(timeout)
        override fun exitValue(): Int = if (aliveProbe()) throw IllegalThreadStateException() else 0
        override fun destroy() = onDestroy()
        override fun isAlive(): Boolean = aliveProbe()
        override fun destroyForcibly(): Process {
            onForceKill()
            return this
        }
    }

    // --- 源码定位 -----------------------------------------------------------

    private fun sourceOfExecutor(): String {
        val f = resolveSource("core/ProotProcessExecutor.kt")
        assertNotNull("找不到 ProotProcessExecutor.kt 源码：请在仓库根或 FCL/ 目录下运行测试", f)
        return f!!.readText()
    }

    /** 从测试运行目录向上找 `[FCL/[FCL/]]src/main/java/com/dsh/<rel>`（口径同 DshTaskStateTest.resolveMainSource）。 */
    private fun resolveSource(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/java/com/dsh/$rel"),
                File(dir, "FCL/src/main/java/com/dsh/$rel"),
                File(dir, "src/main/java/com/dsh/$rel")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    /**
     * 取 `[start, end)` 之间的源码切片；找不到端点时**直接失败**并给出可读信息。
     *
     * 为什么切片而不是整文件 contains：`onLine(...)` / `isAlive()` 在本文件里有十几处，
     * 整文件断言会被别处的合法用法满足（假绿）。切片把断言钉在**目标函数体内**。
     */
    private fun sliceBetween(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("切片起点未命中（函数/注释被改了？）：$start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        assertTrue("切片终点未命中：$end", to > from)
        return text.substring(from, to)
    }
}
