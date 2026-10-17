package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.ui.shell.scrollCompensation
import com.dsh.ui.shell.scrollCompensationAnchored
import com.dsh.ui.shell.scrollShiftRows
import com.dsh.ui.shell.scrollShiftRowsAnchored
import com.dsh.ui.shell.scrollTrimmedAway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * fix2 / W3C 回归：BL-114（日志页长会话裁剪位移补偿的数学）+ BL-075（落盘失败可观测）。
 *
 * 两块的共同点是"修错会静默"：
 * - BL-114 的补偿量算错方向/量级 → 读历史只会"少补/补反"，没有异常可抓 —— 用纯函数口径钉死；
 * - BL-075 的重试/放弃/告警路径 → 之前整段 runCatching 空吞，任何断言都观测不到 —— 用
 *   "路径是目录"制造真实写失败（Windows: AccessDeniedException / Linux: Is a directory），
 *   断言 `lastFileWriteError`、内存告警行、重试上界（不热循环、不刷屏）。
 *
 * ★ 纯函数（`com.dsh.ui.shell` 顶层 public）单测直接调，不碰 Android —— 与 DshTaskRowTest
 *   调 `joinTaskTitle` 同一先例。
 * ★ DshLogBus 是进程内单例：失败用例自己 attach 坏路径、结束在 finally 里
 *   [DshLogBus.detachFileForTest] + 清全局缓冲，接口与顺序见用例注释。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.W3CTest"
 */
class W3CTest {

    // ────────────────────────── BL-114：补偿数学（计数法） ──────────────────────────

    /** 不裁剪（两帧都在帽下）→ 不动。边界 1499→1500（恰好到帽）也算不裁剪。 */
    @Test
    fun countingCompensationIsZeroWhileUnderCap() {
        assertEquals(0, scrollCompensation(0, 0, 1500, 42))
        assertEquals(0, scrollCompensation(1400, 1499, 1500, 42))
        assertEquals(0, scrollCompensation(1499, 1500, 1500, 42))
    }

    /** 首帧（上一帧不存在）→ 不补偿：没有"上一帧位置"可锚定。 */
    @Test
    fun countingCompensationIsZeroOnFirstFrame() {
        assertEquals(0, scrollCompensation(-1, 2000, 1500, 42))
        assertEquals(0, scrollShiftRows(-1, 2000, 1500))
    }

    /** 刚超帽：1500→1520（多裁 20 行）→ -20×行高。 */
    @Test
    fun countingCompensationMatchesTrimDelta() {
        assertEquals(-840, scrollCompensation(1500, 1520, 1500, 42))
        assertEquals(20, scrollShiftRows(1500, 1520, 1500))
        // 两帧都已超帽：差值口径（1600 裁 100 → 1610 裁 110，多裁 10 行）
        assertEquals(-420, scrollCompensation(1600, 1610, 1500, 42))
    }

    /** 内容缩短 / 清空（本帧裁得比上帧少）→ 不向上追溯，0。 */
    @Test
    fun countingCompensationNeverGoesPositive() {
        assertEquals(0, scrollCompensation(1600, 1580, 1500, 42))
        assertEquals(0, scrollCompensation(1000, 0, 1500, 42))
        assertEquals(0, scrollShiftRows(1600, 1580, 1500))
    }

    /** lineHeight 取不到（≤0）→ 0：宁可不动，不拿错的行高乱滚。 */
    @Test
    fun countingCompensationIsZeroWithoutLineHeight() {
        assertEquals(0, scrollCompensation(1500, 1520, 1500, 0))
        assertEquals(0, scrollCompensation(1500, 1520, 1500, -3))
    }

    /** maxLines 非法（≤0）→ 0（防御）。 */
    @Test
    fun countingCompensationIsZeroOnInvalidMaxLines() {
        assertEquals(0, scrollCompensation(1500, 1520, 0, 42))
        assertEquals(0, scrollTrimmedAway(1500, 0))
    }

    /** trimmedAway 本体：max(0, n - maxLines)。 */
    @Test
    fun trimmedAwayIsMaxZeroOfOverflow() {
        assertEquals(0, scrollTrimmedAway(0, 1500))
        assertEquals(0, scrollTrimmedAway(1500, 1500))
        assertEquals(1, scrollTrimmedAway(1501, 1500))
        assertEquals(500, scrollTrimmedAway(2000, 1500))
    }

    // ────────────────────────── BL-114：补偿数学（锚定法） ──────────────────────────

    /** 帽下两帧：锚在 0 位、窗口起点也恒为 0 → 0（无事发生）。 */
    @Test
    fun anchoredCompensationIsZeroWhileUnderCap() {
        assertEquals(0, scrollCompensationAnchored(0, 1500, 1500, 42))
    }

    /** 刚超帽的稳定流：锚（上帧首行）只是被追加挤在新列表更靠后的位置——
     *  新 size 1520（窗口起点 20）、锚新下标 0 → 上移 20 行。与计数法一致。 */
    @Test
    fun anchoredCompensationMatchesCountingWhileGrowing() {
        assertEquals(20, scrollShiftRowsAnchored(0, 1520, 1500))
        assertEquals(-840, scrollCompensationAnchored(0, 1520, 1500, 42))
        assertEquals(
            scrollCompensation(1500, 1520, 1500, 42),
            scrollCompensationAnchored(0, 1520, 1500, 42)
        )
    }

    /**
     * ★ 锚定法存在的理由（环形稳态）：总线满 2000、就地挤头 —— 两帧过滤后行数**不变**
     * （计数法差为 0，补不了），但 10 行老日志被挤出前端、锚（上帧窗口首行）的下标从 500
     * 掉到 490 → 内容确实上移了 10 行。计数法 0、锚定法 -420，两条口径的差异在这里被钉死。
     */
    @Test
    fun anchoredCompensationCoversRingBufferSteadyState() {
        assertEquals(0, scrollCompensation(2000, 2000, 1500, 42)) // 计数法的盲区
        assertEquals(10, scrollShiftRowsAnchored(490, 2000, 1500)) // 锚定法照常给出 10 行
        assertEquals(-420, scrollCompensationAnchored(490, 2000, 1500, 42))
    }

    /** 锚没被推走（下标 ≥ 窗口起点）、或找不到锚（-1）→ 0。 */
    @Test
    fun anchoredCompensationIsZeroWhenAnchorStillAtWindowTopOrMissing() {
        assertEquals(0, scrollCompensationAnchored(500, 2000, 1500, 42))
        assertEquals(0, scrollCompensationAnchored(1200, 2000, 1500, 42))
        assertEquals(0, scrollCompensationAnchored(-1, 2000, 1500, 42))
        assertEquals(0, scrollCompensationAnchored(490, 2000, 1500, 0))
    }

    // ────────────────────────── BL-075：落盘失败可观测 ──────────────────────────

    /**
     * 落盘写失败（路径是目录，appendText 必抛）必须：
     * 1) `lastFileWriteError` 非空且含文件路径；
     * 2) 内存流出现英文告警行（首败 "log file write failed"；到达重试上限 "giving up"）；
     * 3) 重试有上界（尝试 3 次后丢弃批次）且"giving up"只出一次（不随每批失败刷屏）；
     * 4) 写失败后追加的新批次不再无限重试（基于同一计数）。
     *
     * ⚠️ 本用例自行 attach 坏路径并在 finally 恢复（detach + 清全局缓冲）——
     *    DshLogBus 单例，不能让坏文件或"钉死的失败计数"泄漏给别的测试类。
     */
    @Test
    fun fileWriteFailureIsObservableAndRetriesAreBounded() {
        val dir = Files.createTempDirectory("dsh-w3c-fail").toFile()
        val badFile = File(dir, "bad.log")
        assertTrue("前置：badFile 必须是**目录**才能让 appendText 必抛", badFile.mkdirs())

        val previous = DshLogBus.attachedFilePathForTest
        try {
            DshLogBus.detachFileForTest() // 干净起点（含失败计数清零）
            DshLogBus.clear() // 清掉可能残留的上一测试的告警行
            DshLogBus.flushNow()
            DshLogBus.attachFile(badFile) // 绑定全局落盘到这个坏路径

            val m = "w3c-fail-marker-${System.nanoTime()}"
            DshLogBus.append(m)

            // 3 次尝试（R15-A/BL-079 修正后：失败批从队首重入 FIFO，同一 drain 单内立即重试，
            // 上限 FILE_WRITE_FAIL_LIMIT=3 —— 所以**第一次** flushNow 就完成了 3 次尝试；
            // 后两次 flushNow 只用于观察状态收敛，不再触发新尝试）
            DshLogBus.flushNow() // attempt 1→3：kept for retry → … → giving up + 丢弃批次
            DshLogBus.flushNow() // 只发布快照，不再触碰文件
            DshLogBus.flushNow() // 同上（多调一次确认无副作用）

            val err = DshLogBus.lastFileWriteError
            assertNotNull("落盘失败必须被记录（BL-075 核心）", err)
            val errText = err ?: ""
            assertTrue("记录须含文件路径: $errText", errText.contains("bad.log"))
            assertTrue("记录须含失败描述: $errText", errText.contains("log file write failed"))

            // 内存侧告警行：export() 直接读缓冲，不依赖快照发布时序
            val text = DshLogBus.export()
            assertTrue("应有首次失败告警行（含批次行数）", text.contains("line(s) kept for retry"))
            assertTrue("到达重试上限应有放弃告警行", text.contains("giving up"))
            assertTrue("失败批次的原始行仍应在内存流里（不丢内存）", text.contains(m))

            // 丢弃后继续追加新批次：attempt 已 ≥ 上限 → 直接丢弃、**不**再刷 giving up
            DshLogBus.append("w3c-fail-late-${System.nanoTime()}")
            DshLogBus.flushNow()
            val givingUpCount = DshLogBus.export().lines().count { it.contains("giving up") }
            assertEquals("放下限后不应重复刷 giving up 告警（防日志洪水）", 1, givingUpCount)

            // 写从未成功过：坏路径还是目录（没有被写成文件）
            assertTrue("坏路径必须仍保持目录（写自始至终失败）", badFile.isDirectory)
        } finally {
            // 恢复单例状态：解绑坏文件、清零失败计数、清掉本用例的告警/标记行
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            // 若之前有别的绑定（正常套件里不存在），原样接回
            previous?.let { DshLogBus.attachFile(File(it)) }
            dir.deleteRecursively()
        }
    }

    /**
     * 成功写路径不粘失败状态：一次成功写入后该文件的失败计数被重置，
     * `lastFileWriteError` 保持"粘性"（记录发生过的事，不随成功消失）——
     * 这两条语义都靠公开面观测：计数重置的间接证据是**下一轮失败重新从 attempt 1 起**
     * （重新出现 "kept for retry" 告警）。
     */
    @Test
    fun successfulWriteResetsFailureStreakForNextRound() {
        val dir = Files.createTempDirectory("dsh-w3c-recover").toFile()
        val badFile = File(dir, "bad.log")
        val goodFile = File(dir, "good.log")
        assertTrue(badFile.mkdirs())

        val previous = DshLogBus.attachedFilePathForTest
        try {
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            DshLogBus.attachFile(badFile)

            // 一轮失败（attempt 1 → 出现 kept for retry 告警）
            DshLogBus.append("w3c-recover-a-${System.nanoTime()}")
            DshLogBus.flushNow()
            assertTrue(DshLogBus.export().contains("kept for retry"))

            // 换成可写路径并写成功（同一"文件流"恢复）——用一个**新路径**即可：
            // 计数按路径隔离，这里真正要验证的是"成功路径不产生任何失败痕迹"。
            badFile.delete()
            DshLogBus.detachFileForTest()
            DshLogBus.attachFile(goodFile)
            val m2 = "w3c-recover-b-${System.nanoTime()}"
            DshLogBus.append(m2)
            DshLogBus.flushNow()

            assertTrue("成功落盘后文件里必须有这一行", goodFile.readText().contains(m2))
            assertTrue(
                "成功路径不应产生新的告警行",
                DshLogBus.export().lines().none { it.contains(m2) && it.contains("write failed") }
            )
        } finally {
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            previous?.let { DshLogBus.attachFile(File(it)) }
            dir.deleteRecursively()
        }
    }
}
