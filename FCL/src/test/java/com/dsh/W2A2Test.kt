package com.dsh

import com.dsh.ui.trimDeltaFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W2A2（fix2 WebView 页整体修复）纯逻辑回归：BL-115 覆盖层顶部裁剪位移增量。
 *
 * 只钉 [trimDeltaFor] —— 覆盖层 `takeLast(500)` 裁行后"该回补多少行高的滚动位移"的
 * 纯计算（补偿动作、行高取 Paint 字体度量等 UI 部分靠真机验证）。
 * 函数刻意写成顶层（不走 Activity 的 companion）：单测 harness 无 androidx，
 * 经 companion 访问会连带加载 AppCompatActivity 父类链直接崩。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.W2A2Test"
 */
class W2A2Test {

    private val MAX = 500

    /** 未超窗口：没有任何裁剪，增量恒 0（覆盖层 <500 行的常态）。 */
    @Test
    fun belowCapNeverTrims() {
        assertEquals(0, trimDeltaFor(0, MAX, 0))
        assertEquals(0, trimDeltaFor(100, MAX, 0))
        assertEquals(0, trimDeltaFor(MAX, MAX, 0)) // 恰好等于窗口
    }

    /** 首次越过窗口（或覆盖层刚打开、基线为 0）：这一帧新增的裁剪量 = 超出部分全部。 */
    @Test
    fun firstOverflowCountsFullExcess() {
        assertEquals(1, trimDeltaFor(MAX + 1, MAX, 0))
        assertEquals(3, trimDeltaFor(MAX + 3, MAX, 0))
        assertEquals(120, trimDeltaFor(MAX + 120, MAX, 0))
    }

    /** 稳态增长：lastTrimmed 传上一帧存下的裁剪量（= 上帧超出量），增量 = 本帧新增行数。 */
    @Test
    fun steadyGrowthCountsDeltaOnly() {
        // 上帧 510 行 → 裁剪 10；本帧 520 行 → 新增 10
        assertEquals(10, trimDeltaFor(520, MAX, 10))
        // 上帧 520 → 本帧 521（每来一行）→ 增量 1
        assertEquals(1, trimDeltaFor(MAX + 21, MAX, 20))
    }

    /** 无新行（同 revision 被去重前的等长快照）：增量 0，不补偿。 */
    @Test
    fun sameSizeNoDelta() {
        assertEquals(0, trimDeltaFor(600, MAX, 100))
    }

    /** 快照变短（清空/换流）：裁剪量回落，增量按 0 处理，绝不为负（不做反向补偿）。 */
    @Test
    fun shrinkingSnapshotNeverNegative() {
        assertEquals(0, trimDeltaFor(0, MAX, 100))
        assertEquals(0, trimDeltaFor(MAX - 1, MAX, 100))
        assertEquals(0, trimDeltaFor(300, MAX, 100))
    }

    /** 防御：lastTrimmed 为负（调用方脏数据）按 0 计，不做错误放大。 */
    @Test
    fun negativeBaselineTreatedAsZero() {
        assertEquals(10, trimDeltaFor(MAX + 10, MAX, -5))
        assertEquals(0, trimDeltaFor(MAX, MAX, -5))
    }

    /** 防御：totalLines 为负（理论不可达）按 0 计。 */
    @Test
    fun negativeTotalTreatedAsEmpty() {
        assertEquals(0, trimDeltaFor(-1, MAX, 0))
        assertEquals(0, trimDeltaFor(-1, MAX, 100))
    }
}
