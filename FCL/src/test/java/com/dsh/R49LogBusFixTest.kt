package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R49 / W1-B：`DshLogBus.kt` 四条缺陷的钉子（B-1 / B-2 / B-3 / B-4）。
 *
 * 每条都先写"缺陷为什么会被漏掉"，再写"这条断言凭什么能抓住回归"——
 * 本轮编队的教训是：**纯函数测全、调用点被撤销，测试仍全绿**（BL-245 / BL-246）。
 * 所以凡是"某个值要被某人消费"的条目（B-2），这里都额外有一条**接线断言**，
 * 直接读源码证明消费点真的存在、且读的是那个值。
 *
 * ★ DshLogBus 是进程内单例：涉及流的用例一律用互不重复的实例 id / 临时文件，
 *   并在 finally 里 detach + clear（同 W3CTest / DshLogBusLevelTest 的约定）。
 */
class R49LogBusFixTest {

    private var seq = 0
    private fun newId(tag: String): String = "inst-r49w1b-$tag-${seq++}-${System.nanoTime()}"

    // ─────────────────────────── B-1：attachFile 截断失败不许静默 ───────────────────────────

    /**
     * **B-1 缺陷本体**：`attachFile` 的启动截断是裸 `runCatching`，而同文件 `rotateIfTooBig`
     * 用 `.getOrElse` 把读失败记进 `lastFileWriteError` —— 同一个概念两套口径。
     *
     * **怎么造出确定性失败**：把"日志文件"路径做成一个**目录**。`attachFile` 先
     * `file.exists() && file.length() > FILE_MAX_BYTES` 判断（目录的 length 恒为 0 或很小），
     * 所以单靠目录走不到截断分支。这里换一个更真实的形状：**超限 + 不可读**。
     * 在 Android/JVM 上让"存在且超 1MB 但读不出来"最稳的构造是目录 —— 但目录不超限。
     *
     * 所以本用例分两步钉住同一件事：
     * 1. **结构断言**（防"又被改回裸 runCatching"）：源码里 `attachFile` 的 `runCatching`
     *    后面必须跟一个 `.onFailure`，且其体内必须写 `noteFileWriteError`（B-1 的修复载体）；
     * 2. **行为断言**（防"接了但没生效"）：走 [DshLogBus.attachFile] 真实协程路径，
     *    用"父目录不可创建"的形状触发 `mkdirs` 之前的失败 —— 见 [attachTruncateFailureIsRecorded]。
     *
     * ⚠️ 为什么必须有第 1 条：单靠行为用例挡不住"改回裸 runCatching 但顺手改了别处"，
     *    而接线断言能（源码文本是这件事的物理事实）。
     */
    @Test
    fun attachFileTruncateFailureIsRecordedAndWired() {
        // ── ① 结构：attachFile 的 runCatching 必须有 .onFailure { noteFileWriteError(...) }
        val src = blankComments(File(findRepoRoot(), "FCL/src/main/java/com/dsh/core/DshLogBus.kt").readText())
        val attachBody = sourceOfFunction(src, "fun attachFile(file: File)")
        assertTrue(
            "前提：必须在源码里定位到 attachFile 的函数体（否则下面的断言是空转）",
            attachBody.isNotEmpty()
        )
        assertTrue(
            "★ B-1：attachFile 的截断必须带 .onFailure { … }（改前是裸 runCatching，失败静默）\n$attachBody",
            attachBody.contains(".onFailure")
        )
        assertTrue(
            "★ B-1：那条 .onFailure 必须记进统一入口 noteFileWriteError —— " +
                "光有 .onFailure 而不记录等于换个地方静默",
            attachBody.contains("noteFileWriteError")
        )
        assertTrue(
            "★ B-1：措辞必须与 rotateIfTooBig 对齐（'log file … failed: <path> (<Class>: <msg>)'）",
            attachBody.contains("log file truncate failed")
        )

        // ── ② 行为：真的失败一次，失败必须出现在 UI 订阅的那条流上
        val dir = Files.createTempDirectory("dsh-r49-b1").toFile()
        val previous = DshLogBus.attachedFilePathForTest
        try {
            DshLogBus.detachFileForTest()
            DshLogBus.resetFileWriteErrorForTest()
            assertNull("前置：起点没有失败记录", DshLogBus.logFileWriteFailure.value)

            // 用"父路径是个文件而不是目录"的形状：attachFile 里 `file.parentFile?.mkdirs()`
            // 对"父是个普通文件"会失败（mkdirs 返回 false，不抛异常）——
            // 所以换一个真会抛的：让 file 本身是个**目录**，并在 attach 之前先把它撑爆长度？
            // 目录 length 不可控。最终选**读不出来的超限文件**：建一个 >1MB 的目录结构做不到，
            // 所以这里用最直接的确定性失败 —— 让 writeText 落在只读文件上（Windows/Linux 都可）。
            val target = File(dir, "runtime.log")
            assertTrue("前置：目标必须真的建出来", target.createNewFile())
            // 造一个 > 1 MiB 的内容，并且**只读**：readLines 能过，writeText 必抛
            // （Windows 上只读文件写会抛 FileNotFoundException；Linux 上是 Permission denied）。
            // 若平台允许写（有些 JVM/文件系统忽略只读位），则退化为"本条只验结构"——
            // 所以下面用"失败要么发生、要么平台确实可写"两选一的方式断言，不留假绿。
            val filler = StringBuilder()
            while (filler.length < 1_100_000) filler.append("x".repeat(999)).append('\n')
            target.writeText(filler.toString())
            val readOnly = target.setWritable(false)
            try {
                DshLogBus.attachFile(target)
                // attachFile 走 DshAppScope 的异步协程：等它跑完
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline &&
                    DshLogBus.logFileWriteFailure.value == null
                ) {
                    Thread.sleep(20)
                }
                if (readOnly && !target.canWrite()) {
                    val err = DshLogBus.logFileWriteFailure.value
                    assertNotNull("★ B-1：只读文件上的截断失败必须出现在可订阅的失败流上", err)
                    assertTrue("★ B-1：描述必须带路径与动作: $err", err!!.contains("runtime.log"))
                    assertTrue("★ B-1：描述必须带 'truncate failed': $err", err.contains("truncate failed"))
                    // 粘性字段同样要有（排障入口）
                    assertNotNull("★ B-1：lastFileWriteError 也必须记下", DshLogBus.lastFileWriteError)
                }
                // 平台可写（只读位无效）时本条只验结构（上面 ① 已钉死），不在此假绿
            } finally {
                runCatching { target.setWritable(true) }
            }
        } finally {
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            DshLogBus.resetFileWriteErrorForTest()
            previous?.let { DshLogBus.attachFile(File(it)) }
            runCatching { dir.deleteRecursively() }
        }
    }

    // ─────────────────────────── B-2：lastFileWriteError 必须有 UI 消费 ───────────────────────────

    /**
     * **B-2 缺陷本体**：`lastFileWriteError` 在 main 侧**零消费**（`grep -rn` 只有定义与内部赋值，
     * 其余命中全在测试）⇒ 磁盘满导致日志写不进时，用户不看日志页就完全不知道。
     *
     * ## 本条只钉"总线侧的消费前提"，**不是** UI 接线断言 —— 原因写在这里，别当成遗漏
     *
     * B-2 的修法本来是两层：① 总线开出可订阅的失败状态；② UI 订阅它画横幅 + 一条**接线断言**。
     * 第 ② 层必须落在 UI 文件里（`DshLogsUI.kt` / `DshInstancesUI.kt`），而本轮编队把
     * `DshLogBus.kt` 单独划给 W1-B（"只改这一个文件"）。所以：
     * - ① 已做并在此钉住（见 [fileWriteFailureFlowIsSetOnFailureAndClearedOnRecovery]）；
     * - ② 的完整补丁与配套接线断言写在 `docs/reports/round49/fixes/W1-B.md`，交主编排落位。
     *
     * ⚠️ **刻意不在这里写 `assertTrue(已接线)`**：那条断言现在必然红（UI 还没改），
     *   而为了让整批"TOTAL 只增不减、FAILED=0"而把它删掉/放宽，正是 BL-245/246 的复现路径
     *   （纯函数测全、调用点撤销仍全绿）。宁可**明说缺一层**，也不留一条自我满足的假护栏。
     *
     * 本条实际钉的是：**总线那侧必须存在可订阅的失败状态**（接线断言的前提条件）。
     * 没有它，UI 侧无论怎么写都只能去轮询 [DshLogBus.lastFileWriteError] 那个无订阅性的字段。
     */
    @Test
    fun busExposesSubscribableFileWriteFailureStateForUi() {
        // 用"可订阅 + 订阅即回放当前值"这两条性质钉住它 —— 这正是 UI 写横幅所需的最小契约。
        val flow: StateFlow<String?> = DshLogBus.logFileWriteFailure
        assertNull("起点（未失败）必须是 null —— 横幅据此判可见性", flow.value)

        // 真正可断言的性质：同一实例反复读拿到同一个对象（UI 在 onCreate 订阅一次即可）。
        // 这同时钉住"它是 StateFlow"这件事 —— 普通冷 Flow 每次访问都会是新对象，
        // 而 UI 订阅的那个收不到后续发射（该坑在 DshLogBus.snapshotFor 的 KDoc 里有先例）。
        assertSame(
            "★ B-2 前提：两次取必须是同一个流实例（否则 UI 订阅的那个收不到后续发射）",
            flow, DshLogBus.logFileWriteFailure
        )
    }

    /**
     * B-2 的**总线侧**行为断言：失败时置值、同路径写成功时清空（横幅随恢复消失）。
     *
     * 为什么"写成功要清空"必须单独钉：粘性字段 `lastFileWriteError` 是**故意不消失**的，
     * 如果订阅流也照抄粘性语义，横幅会永久挂在屏幕上（"已经修好了但用户还以为坏了"）——
     * 那比不显示更糟。
     */
    @Test
    fun fileWriteFailureFlowIsSetOnFailureAndClearedOnRecovery() {
        val dir = Files.createTempDirectory("dsh-r49-b2").toFile()
        val bad = File(dir, "bad.log")
        val good = File(dir, "good.log")
        assertTrue("前置：bad 必须是目录才会写失败（同 W3CTest 手法）", bad.mkdirs())

        val previous = DshLogBus.attachedFilePathForTest
        try {
            DshLogBus.detachFileForTest()
            DshLogBus.resetFileWriteErrorForTest()
            assertNull("起点：没有失败", DshLogBus.logFileWriteFailure.value)

            DshLogBus.attachFile(bad)
            DshLogBus.append("r49-b2-fail-${System.nanoTime()}")
            DshLogBus.flushNow() // attempt 1 → 失败 → 置状态

            val failing = DshLogBus.logFileWriteFailure.value
            assertNotNull("★ B-2：落盘失败必须出现在可订阅的流上（UI 才有东西可画）", failing)
            assertTrue("★ B-2：描述里要有文件路径: $failing", failing!!.contains("bad.log"))
            // 粘性字段同一时刻也置位（两个都答"发生过"）
            assertEquals(
                "两个字段必须说同一件事（同一次失败的描述）",
                DshLogBus.lastFileWriteError, failing
            )

            // 恢复：换一个可写路径并写成功 → 流必须回 null（横幅消失）
            bad.delete()
            DshLogBus.detachFileForTest()
            DshLogBus.resetFileWriteErrorForTest() // 解绑后状态已清；这里再显式一次保证起点一致
            DshLogBus.attachFile(good)
            val m = "r49-b2-recover-${System.nanoTime()}"
            DshLogBus.append(m)
            DshLogBus.flushNow()
            assertTrue("前置：这次写入必须真的成功（否则下面断言无意义）", good.readText().contains(m))

            // 重新走一遍完整循环：失败 → 恢复 → 流回 null
            DshLogBus.detachFileForTest()
            DshLogBus.resetFileWriteErrorForTest()
            val bad2 = File(dir, "bad2.log")
            assertTrue(bad2.mkdirs())
            DshLogBus.attachFile(bad2)
            DshLogBus.append("r49-b2-fail2-${System.nanoTime()}")
            DshLogBus.flushNow()
            assertNotNull("前置：第二轮失败必须再次置位", DshLogBus.logFileWriteFailure.value)
            // 同一条"文件流"换成可写目标：把 bad2 目录删掉、在同路径上建普通文件，
            // 于是下一次 flush 的写入对**同一路径**成功 → 清空判据（按路径）应成立。
            assertTrue(bad2.delete()) // 空目录可删
            assertTrue(bad2.createNewFile())
            DshLogBus.append("r49-b2-recover2-${System.nanoTime()}")
            DshLogBus.flushNow()
            assertNull(
                "★ B-2：同一路径恢复可写后，失败状态必须清空（横幅不能永久挂着）",
                DshLogBus.logFileWriteFailure.value
            )
            // 粘性字段**保持**（这是它与流的刻意分工）
            assertNotNull(
                "粘性字段 lastFileWriteError 必须保持（排障要问'这个进程失败过没有'）",
                DshLogBus.lastFileWriteError
            )
        } finally {
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            DshLogBus.resetFileWriteErrorForTest()
            previous?.let { DshLogBus.attachFile(File(it)) }
            runCatching { dir.deleteRecursively() }
        }
    }

    /**
     * B-2 的另一半：**别的路径**写成功不许清掉**这条路径**的失败
     * （A 文件坏了不代表 B 文件修好了 A）。
     */
    @Test
    fun recoveryOnOnePathDoesNotClearAnotherPathsFailure() {
        val dir = Files.createTempDirectory("dsh-r49-b2b").toFile()
        val bad = File(dir, "bad.log")
        val other = File(dir, "other.log")
        assertTrue(bad.mkdirs())

        val previous = DshLogBus.attachedFilePathForTest
        try {
            DshLogBus.detachFileForTest()
            DshLogBus.resetFileWriteErrorForTest()

            // 失败挂在 bad 上
            DshLogBus.attachFile(bad)
            DshLogBus.append("r49-b2b-a-${System.nanoTime()}")
            DshLogBus.flushNow()
            val badErr = DshLogBus.logFileWriteFailure.value
            assertNotNull("前置：bad 上的失败要置位", badErr)

            // 从别的路径写成功（用实例流：实例日志文件 = 另一个文件）
            val id = newId("other")
            DshLogBus.appendFor(id, "r49-b2b-other-${System.nanoTime()}")
            DshLogBus.flushNow()
            // 实例文件写成功（它自己的路径）——不该清掉 bad 那条
            assertEquals(
                "★ B-2：别的路径成功后，bad 的失败必须仍在（失败是按文件的）",
                badErr, DshLogBus.logFileWriteFailure.value
            )
        } finally {
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            DshLogBus.resetFileWriteErrorForTest()
            previous?.let { DshLogBus.attachFile(File(it)) }
            runCatching { dir.deleteRecursively() }
        }
    }

    // ─────────────────────────── B-3：sandbox-probe 降级证据必须是 ERROR ───────────────────────────

    /**
     * **B-3 缺陷本体**：`start-dsh.sh` 里最关键的沙箱两行**不含** `ERROR_RE` 的任何关键词，
     * 被判 INFO ⇒ 日志页「警告及以上」/「仅错误」两档完全看不见。
     * 而本机（内核 4.14 < Landlock 要求的 5.13）是**必现**的 —— 沙箱挂了在日志层与 UI 层
     * 同时不可见。
     *
     * 本用例用的是 **`start-dsh.sh` 里逐字复制出来的真实行**（含 `[start-dsh]` 前缀，
     * 因为真实入总线的就是那种形态），不是编出来的简化串。
     */
    @Test
    fun sandboxProbeDegradedLinesClassifyAsError() {
        val realLines = listOf(
            // start-dsh.sh:247 —— 一级证据（bwrap 根本不存在）
            "[start-dsh] sandbox-probe bwrap=absent",
            // start-dsh.sh:277 —— 二级证据（内核太老，Landlock 用不了）
            "[start-dsh] sandbox-probe landlock=UNUSABLE (kernel 4.14.190; Landlock landed in 5.13, cannot be backported)",
            // start-dsh.sh:244 —— userns 被禁（这条本来含 DENIED，改前就碰巧是 ERROR）
            "[start-dsh] sandbox-probe userns=DENIED (CONFIG_USER_NS missing or blocked -> bwrap cannot work)",
            // start-dsh.sh:289 —— unshare 被拒
            "[start-dsh] sandbox-probe unshare-u=DENIED"
        )
        realLines.forEach { line ->
            assertEquals(
                "★ B-3：沙箱降级证据必须判 ERROR（否则「仅错误」档位看不见沙箱挂了）: $line",
                LogLevel.ERROR, DshLogBus.classifyLevel(line)
            )
        }
    }

    /**
     * B-3 的**反例**（这条是修复的关键约束）：不许把 `absent` 这类通用词一股脑升成 ERROR ——
     * 那会把一大批正常行染红，假错误的代价比没有错误更高。
     *
     * 判据是"**前缀约束**"：只有带了 `sandbox-probe` 标签的行才享有例外。
     */
    @Test
    fun absentElsewhereStillClassifiesAsInfo() {
        val normalLines = listOf(
            "[bootstrap] file absent, using default",
            "[setup] optional asset absent: rootfs.sha256",
            "asset absent -> skipped",
            // 探测器自身的**细节诊断**行：带标签但不是"已断"断言，仍应是 INFO
            // （把它们也升 ERROR 会把真正的 bwrap=absent 稀释掉）
            "[start-dsh] sandbox-probe tracerpid=0",
            "[start-dsh] sandbox-probe seccomp=0",
            "[start-dsh] sandbox-probe landlock=launcher-not-found"
        )
        normalLines.forEach { line ->
            assertEquals(
                "★ B-3 反例：不含 sandbox-probe 标签的 absent（或探测细节行）必须仍是 INFO: $line",
                LogLevel.INFO, DshLogBus.classifyLevel(line)
            )
        }
    }

    /**
     * B-3 的第二个反例：**词表本身不许被改**。
     *
     * `ERROR_RE` 是从 DshLogsUI 逐字迁来的口径（11 个关键词），修 B-3 时最省事的错做法
     * 就是往里塞 `absent`/`UNUSABLE` —— 那会单方面改了 UI 的过滤语义。
     * 这里直接钉住"11 个关键词照旧命中 ERROR、且 absent/UNUSABLE 单独出现**不**判 ERROR"。
     */
    @Test
    fun errorVocabularyIsUnchangedAndAdditiveWordsDoNotLeakIn() {
        listOf("error", "failed", "failure", "fatal", "exception", "denied", "错误", "失败", "异常", "拒绝", "不可用")
            .forEach { kw ->
                assertEquals(
                    "迁入的 11 个关键词必须照旧判 ERROR（B-3 不许改词表）: $kw",
                    LogLevel.ERROR, DshLogBus.classifyLevel("12:34:56  sample $kw line")
                )
            }
        listOf("absent", "UNUSABLE").forEach { kw ->
            assertEquals(
                "★ B-3：'$kw' 是通用词，单独出现**不许**判 ERROR（只许在 sandbox-probe 行内生效）",
                LogLevel.INFO, DshLogBus.classifyLevel("12:34:56  sample $kw line")
            )
        }
        // 大小写不敏感：脚本若改成小写，修复不许静默失效
        assertEquals(
            "★ B-3：判据必须大小写不敏感（脚本改写成 landlock=unusable 时不许失效）",
            LogLevel.ERROR, DshLogBus.classifyLevel("[start-dsh] sandbox-probe landlock=unusable")
        )
    }

    // ─────────────────────────── B-4：flush 异常必须有 CoroutineExceptionHandler ───────────────────────────

    /**
     * **B-4 缺陷本体**：`armFlushJob` 用 `scope.launch` 起的根协程里，`flush()` 抛的 Throwable
     * 进线程默认 handler —— 不落状态流、不落日志，"日志页不刷新/落盘悄悄停了"在 App 侧完全静默。
     *
     * 本用例**真的把一次失败投进那个 scope**（生产路径，不是只断言"字段里有个 handler"），
     * 然后断言它出现在日志总线里。
     */
    @Test
    fun flushScopeExceptionIsRecordedInBus() {
        DshLogBus.detachFileForTest() // 本用例纯内存，不落盘
        DshLogBus.clear()
        DshLogBus.flushNow()
        val marker = "r49-b4-marker-${System.nanoTime()}"

        DshLogBus.launchFailingJobForTest(IllegalStateException(marker))

        // 协程调度是异步的：给一个充足的窗口（handler 里只做一次 append）
        val deadline = System.currentTimeMillis() + 15_000
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            found = DshLogBus.export().contains("log flush job failed")
            if (!found) Thread.sleep(20)
        }
        assertTrue(
            "★ B-4：flush 协程抛出的 Throwable 必须记进日志总线（不许进线程默认 handler 后消失）",
            found
        )
        val line = DshLogBus.export().lines().first { it.contains("log flush job failed") }
        assertTrue("★ B-4：记录里必须带异常类名: $line", line.contains("IllegalStateException"))
        assertTrue("★ B-4：记录里必须带异常 message: $line", line.contains(marker))

        DshLogBus.clear()
        DshLogBus.flushNow()
    }

    /**
     * B-4 的结构断言：`scope` 的上下文里必须**真有**一个 [CoroutineExceptionHandler]。
     *
     * 为什么行为用例之外还要这一条：行为用例走的是 [DshLogBus.launchFailingJobForTest]，
     * 而 armFlushJob 也可以被改成 `try/catch` —— 那样行为用例会绿，但"scope 上真的挂了
     * handler"这个**设计事实**就没了（以后任何人往 scope 上再加一个 launch 都不会被兜住）。
     */
    @Test
    fun flushScopeCarriesCoroutineExceptionHandler() {
        assertNotNull(
            "★ B-4：日志总线 scope 的上下文必须注册 CoroutineExceptionHandler",
            DshLogBus.scopeExceptionHandlerForTest()
        )
    }

    /**
     * B-4 的**防刷屏**断言：同一条失败反复发生不许把环形缓冲刷爆。
     *
     * 为什么必须有：handler 记的那一行自己会 `append` ⇒ `dirty = true` ⇒ 又排一轮 flush
     * ⇒ 若 flush 是稳定复现地抛，每 200ms 一行失败行，2000 行的环约 7 分钟就被整圈挤掉 ——
     * 那正好是排障最需要的现场。规则见 `FLUSH_FAILURE_LOG_EVERY`：首现记、之后每 100 次记心跳。
     */
    @Test
    fun repeatedFlushFailuresDoNotFloodTheRing() {
        DshLogBus.detachFileForTest()
        DshLogBus.clear()
        DshLogBus.flushNow()
        val marker = "r49-b4-flood-${System.nanoTime()}"

        repeat(30) { DshLogBus.launchFailingJobForTest(IllegalThrowable(marker)) }

        val deadline = System.currentTimeMillis() + 20_000
        var first = false
        while (System.currentTimeMillis() < deadline && !first) {
            first = DshLogBus.export().contains("log flush job failed")
            if (!first) Thread.sleep(20)
        }
        assertTrue("前置：首现必须被记下", first)
        // 再给它一点时间把剩下 29 次也跑完（调度器按 IO 线程池，几毫秒内就完）
        Thread.sleep(300)

        val count = DshLogBus.export().lines().count { it.contains("log flush job failed") }
        assertTrue(
            "★ B-4：30 次同一条失败不许记 30 行（防刷屏；实际 $count 行）",
            count <= 3
        )
        assertTrue("★ B-4：首现那一行必须在（不许因为去重把第一次也吞掉）", count >= 1)

        DshLogBus.clear()
        DshLogBus.flushNow()
    }

    /** 让"同一条失败"可重复：类名与 message 都固定（`Throwable(message)` 会带类名 IllegalThrowable） */
    private class IllegalThrowable(message: String) : Throwable(message)

    // ─────────────────────────── 源码定位助手 ───────────────────────────

    /**
     * 从 [src] 里取出 `fun <signature>` 起、到**下一个同缩进层级的成员**为止的文本。
     *
     * 够用就行：本文件只用它取 `attachFile` 的函数体（该函数后面紧跟着 KDoc + 别的成员）。
     * 判据是"遇到下一个成员的 KDoc 开头，或成员的注解（`@`）就停" —— attachFile 的函数体
     * 以 `    }` 收尾，下一个成员前必有 KDoc 或注解。
     */
    private fun sourceOfFunction(src: String, signature: String): String {
        val start = src.indexOf(signature)
        if (start < 0) return ""
        val rest = src.substring(start)
        val end = listOf(
            rest.indexOf("\n    /**", 8),
            rest.indexOf("\n    @", 8),
            rest.indexOf("\n    private fun ", 8),
            rest.indexOf("\n    fun ", 8)
        ).filter { it > 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    /**
     * 剥掉 Kotlin 注释（行注释与块注释，**支持嵌套块注释** —— Kotlin 允许），
     * 保留字符串字面量原样。
     *
     * 为什么必须剥：本仓 KDoc 里成段讨论这些标识符（消费点写在注释里也会命中），
     * 不剥的话结构断言会被自己的文档骗过 —— 那正是 BL-245/246 那类假绿。
     */
    private fun blankComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    // Kotlin 的块注释可嵌套：按深度匹配，别在第一个 */ 就收工
                    var depth = 0
                    while (i < src.length) {
                        if (i + 1 < src.length && src[i] == '/' && src[i + 1] == '*') {
                            depth++; out.append("  "); i += 2
                        } else if (i + 1 < src.length && src[i] == '*' && src[i + 1] == '/') {
                            depth--; out.append("  "); i += 2
                            if (depth == 0) break
                        } else {
                            out.append(if (src[i] == '\n') '\n' else ' '); i++
                        }
                    }
                }
                c == '"' -> {
                    // 字符串字面量：原样拷贝（内部可能含 // 或 /*，不许被当成注释）
                    out.append(c)
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\' && i + 1 < src.length) { out.append(src[i]); i++ }
                        out.append(src[i]); i++
                    }
                    if (i < src.length) { out.append(src[i]); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("找不到仓库根（cwd=${System.getProperty("user.dir")}）")
    }

    // ==================================================================
    // B-2 的**接线断言**（W1-B 明说它没法做、留给编排的那一层）
    // ==================================================================

    /**
     * UI 层必须真的订阅 `DshLogBus.logFileWriteFailure` 并据此切横幅可见性。
     *
     * ## 为什么这条必须存在
     *
     * 本批里 `busExposesSubscribableFileWriteFailureStateForUi` 只钉住"总线**提供了**可订阅的
     * 状态"，那**不是**接线。项目历史上就这么吃过亏（BL-245/246）：纯函数测全、调用点被撤销
     * 之后全部仍然绿。所以这里读 UI 源码，确认三件事：
     *   1. UI 引用了 `DshLogBus.logFileWriteFailure`（订阅发生了）
     *   2. 订阅到的值真的被用来切 `writeFailureBanner.visibility`（没有"读了却不用"）
     *   3. null 与非 null 两个分支都存在（只处理一半 = 横幅永不消失或永不出现）
     *
     * ## 这条断言的弱点（写清楚，免得被当成强测试）
     *
     * 源码文本断言无法证明"运行时真的收得到发射"。它挡住的是"接线被整体删掉"这一整类回退。
     * 判别力由变异验证保证：把 UI 里那段 `collect { }` 删掉，本条必须变红（已实测）。
     */
    @Test
    fun writeFailureBannerIsWiredInLogsUi() {
        val root = findRepoRoot()
        val ui = File(root, "FCL/src/main/java/com/dsh/ui/shell/DshLogsUI.kt")
        assertTrue("找不到 DshLogsUI.kt：${ui.absolutePath}", ui.isFile)
        val code = blankComments(ui.readText())

        assertTrue(
            "★ B-2 接线缺失：DshLogsUI 必须订阅 DshLogBus.logFileWriteFailure —— " +
                "没有订阅，横幅永远不会出现，用户仍然不知道日志写不进",
            Regex("""DshLogBus\s*\.\s*logFileWriteFailure""").containsMatchIn(code)
        )
        assertTrue(
            "★ B-2：必须 `.collect` 订阅（读一次 `.value` 不行 —— 恢复后横幅不会消失）",
            Regex("""logFileWriteFailure\s*\.\s*collect""").containsMatchIn(code)
        )
        // 兼容"先取局部别名再用"的写法（实现里是 `val banner = binding.writeFailureBanner`
        // 然后 `banner.visibility = ...`）—— 判据钉的是"订阅值最后作用到了那个 View 上"，
        // 不是"必须逐字写成 writeFailureBanner.visibility"。
        assertTrue(
            "★ B-2：订阅到的值必须真的切换横幅可见性（不许「读了却不用」）",
            Regex("""(?:writeFailureBanner|banner)\s*\.\s*visibility\s*=""").containsMatchIn(code)
        )
        // 并且必须真的从 binding 取那个 View（否则随便一个叫 banner 的变量就能骗过本条）
        assertTrue(
            "★ B-2：横幅必须取自 binding.writeFailureBanner（不能是别的 View）",
            Regex("""binding\s*\.\s*writeFailureBanner""").containsMatchIn(code)
        )
        assertTrue(
            "★ B-2：必须有 VISIBLE 分支（失败时显示）",
            Regex("""View\s*\.\s*VISIBLE""").containsMatchIn(code)
        )
        assertTrue(
            "★ B-2：必须有 GONE 分支（恢复后消失 —— 否则横幅永久挂着，比不显示更糟）",
            Regex("""View\s*\.\s*GONE""").containsMatchIn(code)
        )

        val layout = File(root, "FCL/src/main/res/layout/activity_dsh_logs.xml")
        assertTrue("找不到 activity_dsh_logs.xml", layout.isFile)
        val xml = layout.readText()
        assertTrue(
            "★ B-2：布局里必须有 @+id/write_failure_banner（UI 代码靠 binding 取它）",
            xml.contains("@+id/write_failure_banner")
        )
        assertTrue(
            "★ B-2：横幅默认必须是 gone（不失败时不许占位，否则页面高度与改造前不一致）",
            Regex("""android:id="@\+id/write_failure_banner"[\s\S]{0,800}?android:visibility="gone""")
                .containsMatchIn(xml)
        )
        assertTrue(
            "★ B-2：log_scroll 的 top 必须锚到横幅下面（否则横幅会盖住日志正文）",
            Regex("""android:id="@\+id/log_scroll"[\s\S]{0,800}?constraintTop_toBottomOf="@id/write_failure_banner"""")
                .containsMatchIn(xml)
        )

        listOf("values", "values-zh", "values-zh-rTW").forEach { loc ->
            val f = File(root, "FCL/src/main/res/$loc/strings.xml")
            assertTrue("找不到 $loc/strings.xml", f.isFile)
            assertTrue(
                "★ B-2：$loc 缺 dsh_logs_write_failed（三语必须齐，否则该语系下横幅会崩）",
                f.readText().contains("name=\"dsh_logs_write_failed\"")
            )
        }
    }
}
