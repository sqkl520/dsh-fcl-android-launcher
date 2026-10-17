package com.dsh

import com.dsh.ui.shell.blendArgb
import com.dsh.ui.shell.filterIndicesByLevel
import com.dsh.ui.shell.levelCodeAt
import com.dsh.ui.shell.newLineRange
import com.dsh.ui.shell.newLineRangeAnchored
import com.dsh.ui.shell.normalizeLevels
import com.dsh.ui.shell.shouldFollow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R15 L1 回归：BL-077（智能跟随判据）+ K27 消费侧（级别直读闸门）+ #7（入场动画区间/混色）。
 *
 * ★ 三个口径的共同风险都是"静默"：跟随判据改错 → 只表现为"上滑被拽回/停止跟随不灵"，
 *   没有异常可抓；级别闸门退化 → 只表现为"上滑到历史发现没有配色/过滤不灵"；动画区间算错
 *   → 只表现为"闪烁/整屏变色"。全部用纯函数口径钉死（不碰 Android 运行时）。
 *
 * ★ 本文件只 import DshLogsUI.kt 里的 **public 顶层纯函数**（`com.dsh.ui.shell`），
 *   不 import DshLogBus 的 R15 新符号（L2 并行开发，独立模块编译不得依赖未落盘符号；
 *   级别码用 `List<Int>` 直接构造 —— 契约见 L1↔L2 接口冻结节）。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.L1Test"
 */
class L1Test {

    // ────────────────────────── BL-077：shouldFollow 全组合 ──────────────────────────

    /**
     * 开关**关** = 用户显式表态"永不跟随" —— 其余两个输入的任何组合都必须为 false。
     * （R15 行为变更点之一：旧实现"开关关且贴底仍跟随"，现在贴底也不跟 —— 见类注释。）
     */
    @Test
    fun followIsNeverTrueWhenSwitchOff() {
        assertFalse(shouldFollow(userPaused = false, switchOn = false, atBottom = true))
        assertFalse(shouldFollow(userPaused = false, switchOn = false, atBottom = false))
        assertFalse(shouldFollow(userPaused = true, switchOn = false, atBottom = true))
        assertFalse(shouldFollow(userPaused = true, switchOn = false, atBottom = false))
    }

    /** ★ BL-077 修复的核心行：开关开 + 用户上滑暂停 + 不在底部 ⇒ **不钉**。
     *  fix2 旧判据 `开关开 || atBottom` 在这一格为 true（上滑被每 200ms 拽回 = 真机症状 1 本尊）。 */
    @Test
    fun followIsPausedWhenUserScrolledUpWithSwitchOn() {
        assertFalse(shouldFollow(userPaused = true, switchOn = true, atBottom = false))
    }

    /** 开关开、不在底部、未暂停（意图是跟随，只是帧间位置漂移）⇒ 继续钉。 */
    @Test
    fun followContinuesWhenNotPaused() {
        assertTrue(shouldFollow(userPaused = false, switchOn = true, atBottom = false))
    }

    /** 开关开 + 贴底 ⇒ 恒钉：等待新行的默认态；也是"暂停标记还没被滚动监听清掉"的过渡帧兜底。 */
    @Test
    fun followIsTrueWhenAtBottomRegardlessOfPause() {
        assertTrue(shouldFollow(userPaused = false, switchOn = true, atBottom = true))
        assertTrue(shouldFollow(userPaused = true, switchOn = true, atBottom = true))
    }

    // ────────────────────────── #7：newLineRange 计数法 ──────────────────────────

    /** 首帧（上一帧不存在）→ null：没有"上一帧位置"可锚定，不猜（与 BL-114 同条边界哲学）。 */
    @Test
    fun newLineRangeIsNullOnFirstFrame() {
        assertNull(newLineRange(-1, 2000, 1500))
        assertNull(newLineRange(-1, 0, 1500))
    }

    /** 无新增（两帧同长，含环形稳态的"看起来不变"）→ null。 */
    @Test
    fun newLineRangeIsNullWithoutGrowth() {
        assertNull(newLineRange(0, 0, 1500))
        assertNull(newLineRange(10, 10, 1500))
        assertNull(newLineRange(2000, 2000, 1500))
    }

    /** 缩短/清空（换筛选档变窄、清空日志）→ null：不是"新行入场"，不层动画。 */
    @Test
    fun newLineRangeIsNullWhenShrinking() {
        assertNull(newLineRange(10, 5, 1500))
        assertNull(newLineRange(10, 0, 1500))
    }

    /** 帽下增长：区间 = 老长度..新长度-1（增量行都在窗口内）。 */
    @Test
    fun newLineRangeCoversAppendedTailUnderCap() {
        assertEquals(10..14, newLineRange(10, 15, 1500))
        assertEquals(0..4, newLineRange(0, 5, 1500))
    }

    /** 刚超帽：窗口起点 = trimmedAway(newCount)，区间不伸进被裁掉的老行。 */
    @Test
    fun newLineRangeStartsAtWindowTopWhenTrimming() {
        // 1500→1520：上帧没裁，本帧裁 20；新增 = 下标 1500..1519（窗户 20..1519 内）
        assertEquals(1500..1519, newLineRange(1500, 1520, 1500))
    }

    /** 一帧爆发超过一整个窗口：上帧的行全被裁出 ⇒ 区间收敛到"整窗"（调用点再截尾部 N 行）。 */
    @Test
    fun newLineRangeClampsToWholeWindowOnBurst() {
        assertEquals(500..1999, newLineRange(100, 2000, 1500))
    }

    /** 非法 maxLines（≤0）→ null（防御，与 scrollTrimmedAway 口径一致）。 */
    @Test
    fun newLineRangeIsNullOnInvalidMaxLines() {
        assertNull(newLineRange(0, 10, 0))
        assertNull(newLineRange(0, 10, -1))
    }

    // ────────────────────────── #7：newLineRangeAnchored 锚定法 ──────────────────────────

    /** 锚不在列表（调用点找不到上帧尾行）→ null（调用点回落计数法）。 */
    @Test
    fun anchoredRangeIsNullWhenAnchorMissing() {
        assertNull(newLineRangeAnchored(-1, 15, 1500))
    }

    /** 锚已是最后一行 ⇒ 无新增 → null。 */
    @Test
    fun anchoredRangeIsNullWhenAnchorIsLastLine() {
        assertNull(newLineRangeAnchored(9, 10, 1500))
        assertNull(newLineRangeAnchored(14, 15, 1500))
    }

    /** 帽下增长：新增 = 锚之后到末尾。 */
    @Test
    fun anchoredRangeCoversTailAfterAnchor() {
        assertEquals(10..14, newLineRangeAnchored(9, 15, 1500))
        // 锚是第 0 行（窗口恰有 1 行增长）
        assertEquals(1..1, newLineRangeAnchored(0, 2, 1500))
    }

    /**
     * ★ 锚定法存在的理由（环形稳态）：总线满 2000 就地挤头时，过滤后行数两帧**相同**
     * —— 计数法 [newLineRange] 差为 0 看不到新增；锚定法：上帧尾行在本帧下标 1489
     * （被挤前移 10），其后的 1490..1499 就是新行。两口径的差异在这里被钉死。
     */
    @Test
    fun anchoredRangeCoversRingBufferSteadyState() {
        assertNull(newLineRange(1500, 1500, 1500)) // 计数法的盲区
        assertEquals(1490..1499, newLineRangeAnchored(1489, 1500, 1500)) // 锚定法照常给出 10 行
    }

    /** 锚被裁出窗口（一帧爆发）：区间收敛到"整窗"（起点夹到窗口顶）。 */
    @Test
    fun anchoredRangeClampsToWindowTopWhenAnchorTrimmed() {
        assertEquals(500..1999, newLineRangeAnchored(10, 2000, 1500))
    }

    /** 非法 maxLines / newCount ≤ 0 → null（防御）。 */
    @Test
    fun anchoredRangeIsNullOnInvalidArgs() {
        assertNull(newLineRangeAnchored(0, 10, 0))
        assertNull(newLineRangeAnchored(0, 10, -1))
        assertNull(newLineRangeAnchored(0, 0, 1500))
    }

    // ────────────────────────── K27：normalizeLevels 尺寸闸门 ──────────────────────────

    /** 不变式成立（尺寸相等，含 0/0）→ **原引用**返回（不复制；调用点用 `===` 判定降级）。 */
    @Test
    fun normalizeLevelsKeepsReferenceWhenSizesMatch() {
        val levels = listOf(0, 1, 2)
        assertSame(levels, normalizeLevels(levels, 3))
        val empty = emptyList<Int>()
        assertSame(empty, normalizeLevels(empty, 0))
    }

    /**
     * 不变式不成立（老快照/未知来源）→ 全部 INFO（全 0），长度对齐 lines。
     * ⚠️ **绝不回落正则**（回落正则 = 把 K27 原样请回来，任务书明确禁止）——
     * 本用例钉死"降级不是原行为"。
     */
    @Test
    fun normalizeLevelsFallsBackToAllInfoOnMismatch() {
        assertEquals(listOf(0, 0, 0), normalizeLevels(emptyList(), 3))
        assertEquals(listOf(0, 0, 0), normalizeLevels(listOf(1), 3))
        assertEquals(listOf(0, 0), normalizeLevels(listOf(0, 1, 2), 2))
    }

    /** linesCount < 0（防御）→ 按 0 处理。 */
    @Test
    fun normalizeLevelsTreatsNegativeCountAsZero() {
        assertTrue(normalizeLevels(listOf(1, 2), -5).isEmpty())
    }

    // ────────────────────────── K27：levelCodeAt 防御读 ──────────────────────────

    /** 正常下标读值；越界（负/超长）回落到 0=INFO（不含正则，不含异常）。 */
    @Test
    fun levelCodeAtReadsWithinBoundsAndFallsBackOutside() {
        val codes = listOf(0, 1, 2)
        assertEquals(0, levelCodeAt(codes, 0))
        assertEquals(1, levelCodeAt(codes, 1))
        assertEquals(2, levelCodeAt(codes, 2))
        assertEquals(0, levelCodeAt(codes, -1))
        assertEquals(0, levelCodeAt(codes, 3))
    }

    // ────────────────────────── K27：filterIndicesByLevel 三档 ──────────────────────────

    /**
     * 三档语义与旧 `matches()` 逐档等价：
     * ALL(minCode=0) 全收 ⇒ 全长下标表；WARN(minCode=1) ⇒ 旧 ERROR_RE||WARN_RE；
     * ERROR(minCode=2) ⇒ 旧 ERROR_RE。
     */
    @Test
    fun filterIndicesMatchesLegacyThreeTierSemantics() {
        val codes = listOf(0, 1, 0, 2, 1, 0) // INFO / WARN / INFO / ERROR / WARN / INFO
        assertEquals(listOf(0, 1, 2, 3, 4, 5), filterIndicesByLevel(codes, 0).toList())
        assertEquals(listOf(1, 3, 4), filterIndicesByLevel(codes, 1).toList())
        assertEquals(listOf(3), filterIndicesByLevel(codes, 2).toList())
    }

    /** 空列表 → 空表（三档都空）；minCode ≤ 0 恒为全长表；超出档位（>2）→ 空表。 */
    @Test
    fun filterIndicesHandlesEmptyAndOutOfRangeTiers() {
        assertTrue(filterIndicesByLevel(emptyList(), 0).isEmpty())
        assertTrue(filterIndicesByLevel(emptyList(), 1).isEmpty())
        assertEquals(listOf(0, 1), filterIndicesByLevel(listOf(0, 0), -1).toList())
        assertTrue(filterIndicesByLevel(listOf(0, 1, 2), 3).isEmpty())
    }

    // ────────────────────────── #7：blendArgb 混色 ──────────────────────────

    /** 端点：fraction 0/1 精确落两端点色。 */
    @Test
    fun blendArgbHitsExactEndpoints() {
        val from = 0x10203040
        val to = 0x50607080
        assertEquals(from, blendArgb(from, to, 0f))
        assertEquals(to, blendArgb(from, to, 1f))
    }

    /** 中点：逐通道取中（0x10→0x50 的中点 = 0x30，其余通道同理）。 */
    @Test
    fun blendArgbInterpolatesChannelsAtMidpoint() {
        assertEquals(0x30405060, blendArgb(0x10203040, 0x50607080, 0.5f))
    }

    /** 越界夹紧：<0 → from；>1 → to（动画器偶尔过冲不改变端点语义）。 */
    @Test
    fun blendArgbClampsOutOfRangeFraction() {
        val from = 0x10203040
        val to = 0x50607080
        assertEquals(from, blendArgb(from, to, -0.5f))
        assertEquals(to, blendArgb(from, to, 1.5f))
    }
}
