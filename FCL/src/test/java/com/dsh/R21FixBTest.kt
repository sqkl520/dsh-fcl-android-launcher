package com.dsh

import com.dsh.core.DshBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix8 / W1-B（R21 部署页三件套）的纯逻辑单测（纯 JVM，不需要真机/Android）。
 *
 * 覆盖 BL-170 的节流判据 [DshBootstrap.shouldEmitExtractLine] ——
 * 解包活动行"什么时候落一行进日志总线"。
 *
 * 为什么只有这一条能测（与 [DshBootstrapSpaceTest] / Fix4Test 同口径）：
 * - **能测**：节流判据本身（纯时间算术）。它是这轮唯一"错一点就整个缺陷复现或新造一个缺陷"的
 *   可判定核心 —— 判据写太密，2000 行的环形缓冲整轮被解压流水账刷满、把真正要看的安装/启动行
 *   挤掉；判据写太疏，面板又回到"只有两行、看起来像死机"（BL-170 的原症状）。
 * - **测不了**：解包进度真的进了总线（要 assets + tar.xz + 真文件系统 + DshLogBus 的 flush 线程）、
 *   面板让位全额生效（BL-171 是 fillViewport + gravity 的布局算术，只有 inflate 出来才成立）、
 *   Detail 帧沿用 fraction 后进度条停在 20%（BL-177 是 WaveProgressView 的绘制）。这三条上真机验证。
 */
class R21FixBTest {

    private companion object {
        /** 与 DshBootstrap 里同一口径的总线写入间隔（改常量必须同步改这里） */
        const val BUS_INTERVAL = 2_000L

        /** UI 明细帧的刷新间隔（比总线快 10 倍；DshBootstrap 里那条 200ms 的私有常量） */
        const val DETAIL_INTERVAL = 200L

        /** 解压全时长量级：rootfs 实测约 3 分钟 */
        const val EXTRACT_SPAN_MS = 3 * 60_000L
    }

    // --- 首行：一轮刚开始就必须有一行 ----------------------------------------

    @Test
    fun firstLineAlwaysEmitted() {
        // lastEmitMs = 0（这一轮还没写过）→ 无论间隔多久都放行；面板一打开就该有内容
        assertTrue("首行必写（lastEmit=0）", DshBootstrap.shouldEmitExtractLine(0L, 10_000L))
        // 负数同样视作"没写过"（不要拿 -1 之类的哨兵当"刚写过"）
        assertTrue(
            "lastEmit<0 也视为未写过",
            DshBootstrap.shouldEmitExtractLine(-1L, 10_000L, BUS_INTERVAL)
        )
    }

    // --- 间隔未到：拦住（这是"不冲掉别的行"的保证） --------------------------

    @Test
    fun blocksUntilIntervalElapsed() {
        val last = 100_000L
        // 差值 0 与差值 interval-1 都必须拦住（边界：>= 才放行）
        assertFalse("同一毫秒不重复写", DshBootstrap.shouldEmitExtractLine(last, last, BUS_INTERVAL))
        assertFalse(
            "差 interval-1 不放行",
            DshBootstrap.shouldEmitExtractLine(last, last + BUS_INTERVAL - 1, BUS_INTERVAL)
        )
        assertTrue("差正好 interval 放行", DshBootstrap.shouldEmitExtractLine(last, last + BUS_INTERVAL, BUS_INTERVAL))
        assertTrue(
            "超过 interval 放行",
            DshBootstrap.shouldEmitExtractLine(last, last + BUS_INTERVAL * 7, BUS_INTERVAL)
        )
    }

    // --- 默认参数：调用侧不写第三个参数时用的就是总线口径的 2 秒 -------------

    @Test
    fun defaultIntervalIsBusInterval() {
        // 默认值 = EXTRACT_BUS_LINE_INTERVAL_MS（listener 里 UI 那一路是显式传 200ms 的）
        assertFalse("默认间隔下 1999ms 不放行", DshBootstrap.shouldEmitExtractLine(10_000L, 11_999L))
        assertTrue("默认间隔下 2000ms 放行", DshBootstrap.shouldEmitExtractLine(10_000L, 12_000L))
        assertEquals("常量与测试同源", BUS_INTERVAL, DshBootstrap.EXTRACT_BUS_LINE_INTERVAL_MS)
    }

    // --- 时钟回跳：必须放行，否则整轮再也不写一行 ----------------------------

    @Test
    fun clockJumpBackwardStillEmits() {
        // 系统时钟被 NTP 校正回跳 → nowMs < lastEmitMs，差值恒为负。
        // 拦住就等于"这一轮之后一行都不写"（BL-170 的原症状以另一种形式回来），故判放行。
        assertTrue(
            "时钟回跳放行（顺手把时间戳对齐回当前时钟）",
            DshBootstrap.shouldEmitExtractLine(100_000L, 99_000L, BUS_INTERVAL)
        )
        // 回跳后调用方把 lastEmitMs 更新为 99_000 → 下一帧按正常节奏走
        assertFalse("回跳后重新计时", DshBootstrap.shouldEmitExtractLine(99_000L, 99_500L, BUS_INTERVAL))
    }

    // --- interval<=0 = 不节流 ------------------------------------------------

    @Test
    fun nonPositiveIntervalDisablesThrottling() {
        // 放行条件 `lastEmit<=0 || now<last || now-last>=interval`：interval=0 时第三个判据恒真
        assertTrue("interval=0 每行都写", DshBootstrap.shouldEmitExtractLine(100_000L, 100_000L, 0L))
        assertTrue("interval<0 每行都写", DshBootstrap.shouldEmitExtractLine(100_000L, 100_001L, -5L))
    }

    // --- 量级自查：这是选 2 秒而不是 200ms 的依据 ----------------------------

    @Test
    fun busLineVolumeFitsRingBuffer() {
        // 逐帧模拟一遍解压全程（每 25ms 一次回调 = 解压回调的常见密度），数一共写几行
        fun simulate(intervalMs: Long, spanMs: Long, tickMs: Long): Int {
            var last = 0L
            var emitted = 0
            var now = 1_000_000L
            while (now < 1_000_000L + spanMs) {
                if (DshBootstrap.shouldEmitExtractLine(last, now, intervalMs)) {
                    last = now
                    emitted++
                }
                now += tickMs
            }
            return emitted
        }

        val lines = simulate(BUS_INTERVAL, EXTRACT_SPAN_MS, 25L)
        // 3 分钟 / 2 秒 = 90 行；按"3~4 分钟 ≈ 120 行"的量级钉一个宽区间，
        // 下界保证"面板一直在动"，上界保证不冲掉 DshLogBus 的 2000 行环形缓冲。
        assertTrue("三分钟至少 60 行（面板不能看起来是死的）：实际 $lines", lines >= 60)
        assertTrue("三分钟至多 200 行（不得刷满环形缓冲）：实际 $lines", lines <= 200)
        assertTrue("总线行数远低于环形上限 2000", lines < 2000)

        // 对照：若按 UI 的 200ms 直接写总线，同样的全程会产出 10 倍行数 —— 那就是"逐文件流水账"
        val tooMany = simulate(DETAIL_INTERVAL, EXTRACT_SPAN_MS, 25L)
        assertTrue("200ms 口径会多出 10 倍行（这正是 BL-170 注释里拒绝它的原因）", tooMany > lines * 5)
    }

    // --- 前缀：与 [bootstrap] / [app] 同轨（底座是 App 级，不带实例归属） ----

    @Test
    fun extractLinePrefixMatchesBusConvention() {
        // 面板里靠方括号前缀分辨来源；写成断言是为了让"改前缀"这件事必须同时改这里
        assertEquals("[extract]", DshBootstrap.EXTRACT_BUS_LINE_PREFIX)
        // 与 listener 里同一套拼法：`[extract] <去掉 ./ 的相对路径>`
        val detail = "./opt/node22/bin/node"
        val line = DshBootstrap.EXTRACT_BUS_LINE_PREFIX + " " + detail.removePrefix("./")
        assertEquals("[extract] opt/node22/bin/node", line)
    }
}
