package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * K27（Round 15 / L2）的"口径等价 + 快照不变式"钉子，全部走 JVM 单测（不需要真机/Android）：
 *
 * - **[DshLogBus.classifyLevel] 口径等价**：本类的样例行即 DshLogsUI 现有过滤器（`Filter.WARN` /
 *   `Filter.ERROR` 与 `levelOf`）在真实日志上会遇到的典型行。总线侧正则逐字迁自 DshLogsUI，
 *   这里用"行 → 期望级别"的表格把它钉住：改总线正则而不同步 UI = 本类变红（语义等价的哨兵）。
 * - **Snapshot 不变式**：`levels.size == lines.size` 恒成立 —— append / appendFor / 环形裁剪 /
 *   clear / clearFor / BL-075 告警注入（第二条入库路径）逐点覆盖。
 * - **旧构造点兼容**：`Snapshot(revision, lines)`（不传 levels）必须仍可编译且 levels 为空。
 *
 * ★ DshLogBus 是进程内单例：涉及流的用例一律用**互不重复**的实例 id（同 DshLogBusFlushTest 约定）。
 * ★ 本类多数用例不 attach 落盘文件（detach 后跑），避免 2100 行级别的洪水写盘；
 *   唯一的落盘用例（[writeFailureWarningLineCarriesErrorLevelAndKeepsInvariant]）自行管理并恢复单例状态。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.DshLogBusLevelTest"
 */
class DshLogBusLevelTest {

    private var seq = 0
    private fun newId(tag: String): String = "inst-leveltest-$tag-${seq++}-${System.nanoTime()}"

    // --- classifyLevel：三个关键字档位 ---------------------------------------

    /**
     * ERROR_RE 的全部关键词（逐字对照 DshLogsUI：
     * `error|failed|failure|fatal|exception|denied|错误|失败|异常|拒绝|不可用`，IGNORE_CASE）。
     */
    @Test
    fun errorKeywordsAllClassifyAsError() {
        val keywords = listOf(
            "error", "failed", "failure", "fatal", "exception", "denied",
            "错误", "失败", "异常", "拒绝", "不可用"
        )
        keywords.forEach { kw ->
            assertEquals(
                "行含 '$kw' 应判 ERROR（UI Filter.ERROR 可见）",
                LogLevel.ERROR, DshLogBus.classifyLevel("12:34:56  sample $kw line")
            )
        }
    }

    /** WARN_RE 的全部关键词（`warn|警告|注意`，IGNORE_CASE）。 */
    @Test
    fun warnKeywordsAllClassifyAsWarn() {
        listOf("warn", "警告", "注意").forEach { kw ->
            assertEquals(
                "行含 '$kw' 应判 WARN（UI Filter.WARN 可见、Filter.ERROR 隐藏）",
                LogLevel.WARN, DshLogBus.classifyLevel("12:34:56  sample $kw line")
            )
        }
    }

    /**
     * OK_RE 档（`\bok\b|ready|done|success|passed|通过|完成|就绪`）在**过滤语义**上就是普通行：
     * 枚举刻意不设 OK 档，命中 OK 的行判 INFO（颜色仍是 UI 的 levelOf 的私事）。
     */
    @Test
    fun okSmokeLinesClassifyAsInfo() {
        listOf("ok", "ready", "done", "success", "passed", "通过", "完成", "就绪").forEach { kw ->
            assertEquals(
                "OK 档关键词 '$kw' 在过滤集里是 INFO（不设 OK 枚举值）",
                LogLevel.INFO, DshLogBus.classifyLevel("12:34:56  sample $kw line")
            )
        }
    }

    @Test
    fun plainLinesClassifyAsInfo() {
        assertEquals(LogLevel.INFO, DshLogBus.classifyLevel("12:00:00  agent heartbeat #42"))
        assertEquals(LogLevel.INFO, DshLogBus.classifyLevel("12:00:01  [setup] beginPrepare"))
        assertEquals(LogLevel.INFO, DshLogBus.classifyLevel(""))
    }

    /** IGNORE_CASE：大小写变体必须与 UI 正则一致地命中。 */
    @Test
    fun caseInsensitiveKeywords() {
        assertEquals(LogLevel.ERROR, DshLogBus.classifyLevel("12:00:00  ErRoR happened"))
        assertEquals(LogLevel.ERROR, DshLogBus.classifyLevel("12:00:01  FAILED"))
        assertEquals(LogLevel.WARN, DshLogBus.classifyLevel("12:00:02  WaRn: low disk"))
        assertEquals(LogLevel.WARN, DshLogBus.classifyLevel("12:00:03  WARNING: low disk"))
    }

    /**
     * 判序（照抄 UI `matches()` 的 `ERROR_RE || WARN_RE` 与 Filter.ERROR 的单一判据）：
     * 同时命中的行**必须**是 ERROR —— UI 的 Filter.ERROR 会显示它，总线侧就不能给 WARN。
     */
    @Test
    fun errorBeatsWarnByJudgmentOrder() {
        assertEquals(LogLevel.ERROR, DshLogBus.classifyLevel("12:00:00  warn: connection failed"))
        assertEquals(LogLevel.ERROR, DshLogBus.classifyLevel("12:00:01  注意：启动失败"))
    }

    /** `[tag]` 前缀行与带时间戳前缀行的判级应只看内容，不受前缀影响（与 UI 逐字一致）。 */
    @Test
    fun tagPrefixedLinesClassifySame() {
        assertEquals(LogLevel.ERROR, DshLogBus.classifyLevel("12:00:00  [start-dsh] 失败：timeout"))
        assertEquals(LogLevel.INFO, DshLogBus.classifyLevel("12:00:01  [bootstrap] step 1"))
        assertEquals(LogLevel.WARN, DshLogBus.classifyLevel("12:00:02  [install] 警告：缺少 lib/bin.js"))
    }

    /**
     * 「口径等价」总表：每行都注明它在 UI 现有过滤集下的可见性。
     * 这三列（总线级别 / Filter.WARN 可见度 / Filter.ERROR 可见度）必须与 DshLogsUI 现状逐条一致：
     * ALL=全部；WARN=级别 ≠ INFO；ERROR=级别 == ERROR。
     */
    @Test
    fun uiSampleRowsEquivalenceTable() {
        // (行, 期望级别, 注释：UI 可见性)
        val rows = listOf(
            Triple("12:00:00  npm error code EACCES", LogLevel.ERROR, "WARN+ERROR 可见"),
            Triple("12:00:01  警告：磁盘空间不足", LogLevel.WARN, "WARN 可见、ERROR 隐藏"),
            Triple("12:00:02  request denied by peer", LogLevel.ERROR, "WARN+ERROR 可见"),
            Triple("12:00:03  task done", LogLevel.INFO, "仅 ALL 可见（OK 档折入 INFO）"),
            Triple("12:00:04  plain output", LogLevel.INFO, "仅 ALL 可见"),
        )
        rows.forEach { (line, expected, note) ->
            assertEquals("$line —— $note", expected, DshLogBus.classifyLevel(line))
        }
    }

    // --- Snapshot 不变式：levels 与 lines 同序等长 ---------------------------

    /** appendFor 之后：实例快照的 levels 与 lines 同长，且逐位对应分类结果。 */
    @Test
    fun snapshotLevelsAreParallelToLinesAfterAppendFor() {
        val id = newId("parallel")
        DshLogBus.appendFor(id, "error e1")
        DshLogBus.appendFor(id, "warn w2")
        DshLogBus.appendFor(id, "info i3")
        DshLogBus.flushNow()

        val snap = DshLogBus.snapshotFor(id).value
        assertEquals(3, snap.lines.size)
        assertEquals("levels 必须与 lines 同序等长", 3, snap.levels.size)
        assertEquals(listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO), snap.levels)
        snap.lines.forEachIndexed { i, line ->
            assertEquals("第 $i 行的级别应等于独立重算的结果", DshLogBus.classifyLevel(line), snap.levels[i])
        }
    }

    /**
     * 环形裁剪之后不变式仍成立：过 MAX_LINES（2000）后，lines 与 levels 一起被裁到 2000，
     * 且剩余位次仍然逐位对应。
     */
    @Test
    fun snapshotLevelsStayParallelAfterRingTrim() {
        val previous = DshLogBus.attachedFilePathForTest
        val id = newId("trim")
        try {
            DshLogBus.detachFileForTest() // 纯内存验证：2100 行不落盘
            val total = 2100 // > MAX_LINES
            repeat(total) { i ->
                val line = when (i % 3) {
                    0 -> "error e$i"
                    1 -> "warn w$i"
                    else -> "info n$i"
                }
                DshLogBus.appendFor(id, line)
            }
            DshLogBus.flushNow()

            val snap = DshLogBus.snapshotFor(id).value
            assertEquals(2000, snap.lines.size) // MAX_LINES
            assertEquals("裁剪后 levels 必须与 lines 同长", 2000, snap.levels.size)

            // 保留的是最后 2000 行（i = 100..2099）：逐位对照期望级别与行尾标记
            for (idx in 0 until 2000) {
                val i = 100 + idx
                val expected = when (i % 3) {
                    0 -> LogLevel.ERROR
                    1 -> LogLevel.WARN
                    else -> LogLevel.INFO
                }
                val marker = when (i % 3) {
                    0 -> "e$i"
                    1 -> "w$i"
                    else -> "n$i"
                }
                assertTrue("第 $idx 行应是 i=$i（$marker）: ${snap.lines[idx]}", snap.lines[idx].contains(marker))
                assertEquals("第 $idx 行（i=$i）级别", expected, snap.levels[idx])
            }

            // 全局流同样受裁剪，同样同长
            val global = DshLogBus.snapshot.value
            assertEquals(global.lines.size, global.levels.size)
        } finally {
            // 清掉本用例灌进全局单例的 2000 行残留，别让后续测试类的全局断言受尘埃影响
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            previous?.let { DshLogBus.attachFile(File(it)) }
        }
    }

    /** clear / clearFor 后：两条流一起归零（不能只清 lines 留 levels）。 */
    @Test
    fun clearResetsLevelsForGlobalAndInstance() {
        val id = newId("clear")
        DshLogBus.appendFor(id, "error to-clear")
        DshLogBus.flushNow()
        DshLogBus.clearFor(id)
        DshLogBus.flushNow()
        val inst = DshLogBus.snapshotFor(id).value
        assertEquals(0, inst.lines.size)
        assertEquals(0, inst.levels.size)

        DshLogBus.clear()
        DshLogBus.flushNow()
        val global = DshLogBus.snapshot.value
        assertEquals(0, global.lines.size)
        assertEquals(0, global.levels.size)
    }

    /**
     * 旧构造点兼容：不传 levels 的构造（含 [DshLogBus.Snapshot.EMPTY]）仍然编译通过，
     * levels 默认空列表 —— UI 侧在"总线还没刷出内容"时拿到旧形态也不会崩。
     */
    @Test
    fun oldSnapshotConstructorDefaultsLevelsEmpty() {
        val oldStyle = DshLogBus.Snapshot(7L, listOf("a", "b"))
        assertTrue("旧构造点（不传 levels）默认空列表", oldStyle.levels.isEmpty())
        assertTrue(DshLogBus.Snapshot.EMPTY.lines.isEmpty())
        assertTrue(DshLogBus.Snapshot.EMPTY.levels.isEmpty())
    }

    /**
     * BL-075 告警注入是**第二条入库路径**（不走 append/appendFor）：
     * 写失败后插进内存流的 "log file write failed …" 行也必须带级别（含 failed → ERROR），
     * 且注入后不变式不破。用"路径是目录"制造确定性写失败（同 W3CTest 手法）。
     */
    @Test
    fun writeFailureWarningLineCarriesErrorLevelAndKeepsInvariant() {
        val dir = Files.createTempDirectory("dsh-level-fail").toFile()
        val badFile = File(dir, "bad.log")
        assertTrue("前置：badFile 必须是目录才会写失败", badFile.mkdirs())
        val previous = DshLogBus.attachedFilePathForTest
        try {
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            DshLogBus.attachFile(badFile)

            DshLogBus.append("leveltest-marker-${System.nanoTime()}")
            DshLogBus.flushNow() // attempt 1：失败 → 注入 "kept for retry" 告警
            DshLogBus.flushNow() // attempt 2：把告警行发布进快照

            val snap = DshLogBus.snapshot.value
            assertEquals("告警注入后不变式必须成立", snap.lines.size, snap.levels.size)
            val warnIdx = snap.lines.indexOfFirst { it.contains("log file write failed") }
            assertTrue("写失败告警行必须在内存流里（BL-075 现状行为，本用例只钉它的级别）", warnIdx >= 0)
            assertEquals(
                "告警行含 'failed'，按迁入的口径必须判 ERROR",
                LogLevel.ERROR, snap.levels[warnIdx]
            )
            assertNotNull(DshLogBus.lastFileWriteError)
        } finally {
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            previous?.let { DshLogBus.attachFile(File(it)) }
            dir.deleteRecursively()
        }
    }

    /**
     * R15-A 锐评新问题 #3：**级别码的数值契约**。
     *
     * `LogLevel.INFO/WARN/ERROR == 0/1/2` 是跨层的隐式契约：总线侧用它做快照编码，
     * UI 侧（DshLogsUI）拿 `List<Int>` 做过滤（`>= 1` 收 WARN 及以上）与配色索引。
     * 两边的测试**各自全绿**时，枚举的 code 仍可被静默改错（谁都不红），界面却会错档。
     * 这三行就是那个契约锚：code 值一改即红。
     */
    @Test
    fun levelCodesAreTheFrozenNumericContract() {
        assertEquals("INFO 的 code 必须为 0（UI 的 minCode=0=全部）", 0, LogLevel.INFO.code)
        assertEquals("WARN 的 code 必须为 1（UI 的 minCode=1=警告及以上）", 1, LogLevel.WARN.code)
        assertEquals("ERROR 的 code 必须为 2（UI 的 minCode=2=仅错误）", 2, LogLevel.ERROR.code)
        // 枚举声明顺序也必须与之对齐（ordinal 若被当索引用，顺序错=档位错）
        assertEquals(0, LogLevel.INFO.ordinal)
        assertEquals(1, LogLevel.WARN.ordinal)
        assertEquals(2, LogLevel.ERROR.ordinal)
    }
}
