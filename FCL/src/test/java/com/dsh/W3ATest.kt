package com.dsh

import com.dsh.core.DshDownloadViewModel
import com.dsh.core.RefreshSeqGuard
import com.dsh.core.RefreshTick
import com.dsh.fcl.androidlauncher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix2-W3A：刷新状态机与空态判定的纯逻辑单测（BL-116 / BL-117 / BL-052）。
 *
 * 不碰 Android：只测从 VM 抽出的可测内核 —— [RefreshSeqGuard] / [RefreshTick]
 * （刷新纪元守卫与完成信号）与 [DshDownloadViewModel.emptyHintRes]（空态选择纯函数）。
 *
 * BL-116 的根治点 = "终局**无条件**发 done tick（不依赖值是否变化）"；本组用例把它
 * 与"旧纪元的数据/信号必须被拒"（BL-052/A1-2）一并钉死，防止回退成
 * "看值是否变化决定发不发信号"的旧形态。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.W3ATest"
 *   （或仓库 run-tests.sh，自动扫描本类）
 */
class W3ATest {

    // --- BL-052 / R12-A1-2：旧纪元的数据与信号都必须被拒 -----------------------------

    /** 弱网连点：旧请求后返回时，不许写数据、不许发完成信号（否则会提前结束新请求的转圈）。 */
    @Test
    fun staleEpochDataAndSignalAreRejected() {
        val guard = RefreshSeqGuard()
        val first = guard.next()   // 第 1 次刷新（响应还在飞）
        val second = guard.next()  // 用户又点了一次，把第 1 次顶掉
        assertTrue("旧序号不许写数据（BL-052 纪元守卫）", !guard.isCurrent(first))
        assertTrue("最新序号才是当前纪元", guard.isCurrent(second))
        assertNull(
            "旧纪元（成功）不许发完成信号",
            guard.doneTick(first, ok = true)
        )
        assertNull(
            "旧纪元（失败）同样不许发",
            guard.doneTick(first, ok = false)
        )
    }

    // --- BL-116：最新纪元的终局必须无条件发 done tick --------------------------------

    /**
     * "刷新成功但数据一字未变"（BL-116 主路径，健康网必现）与"连续两次同型失败"（V4a-2）
     * 在旧实现里都因终态与现值结构相等而被 StateFlow 抑制、endRefresh 不执行。
     * 新内核的 doneTick **没有**数据参数可比 —— 只认纪元，必然非 null。
     */
    @Test
    fun latestEpochAlwaysEmitsDoneTickEvenWhenValuesUnchanged() {
        val guard = RefreshSeqGuard()
        val seq = guard.next()
        val okTick: RefreshTick? = guard.doneTick(seq, ok = true)
        assertNotNull("成功（哪怕数据未变）必须发 done tick", okTick)
        assertTrue(okTick!!.done)
        // 同一纪元的重复终局（防御性）也必发，不会因内容相等被吞
        assertNotNull(guard.doneTick(seq, ok = true))
    }

    /** seq 严格递增；两次"内容完全相同"的失败 → 两条 tick 因 seq 不同而不相等（相等抑制打不着）。 */
    @Test
    fun repeatedIdenticalFailuresProduceDistinctTicks() {
        val guard = RefreshSeqGuard()
        assertTrue("尚未发起任何请求时纪元为 0", guard.latest == 0L)
        assertTrue("0 号不是有效纪元", !guard.isCurrent(0L))

        val s1 = guard.next()
        val t1: RefreshTick = guard.doneTick(s1, ok = false, warning = "Refresh failed; showing cached data")!!
        val s2 = guard.next() // 弱网：第一次失败清圈后用户立刻又点
        val t2: RefreshTick = guard.doneTick(s2, ok = false, warning = "Refresh failed; showing cached data")!!

        assertTrue("seq 递增", s1 < s2)
        assertEquals(s2, guard.latest)
        assertTrue("ok/warning 全同、仅 seq 不同 → 两条 tick 不相等", t1 != t2)
        assertEquals(s1, t1.seq)
        assertEquals(s2, t2.seq)
        assertTrue(t1.done && t2.done)
    }

    /** tick 是信封不是转换：ok / warning 原样透传（warning 取词在 VM/调用方，tick 不改写）。 */
    @Test
    fun tickCarriesOkAndWarningThrough() {
        val guard = RefreshSeqGuard()
        val seq = guard.next()
        val t = guard.doneTick(seq, ok = false, warning = "w")!!
        assertTrue(!t.ok)
        assertEquals("w", t.warning)
        assertTrue(t.done)
        assertNull("未传 warning 时为 null（成功路径）", guard.doneTick(seq, ok = true)!!.warning)
    }

    // --- BL-117：空态文案选择（items 空 × 开关态 → 哪个 key） -------------------------

    /** 开关 OFF + 列表空 = "仅显示稳定版"过滤全灭（dsh 全是预发布）→ 专属空态（引导开预览）。 */
    @Test
    fun emptyHintPicksPrereleaseKeyWhenFilteredEmpty() {
        assertEquals(
            R.string.dsh_download_empty_prerelease,
            DshDownloadViewModel.emptyHintRes(itemsEmpty = true, showPrerelease = false)
        )
    }

    /** 开关 ON 仍为空 = 目录本来就没有可显示版本（含"装载成功但结果为空"的兜底）→ 通用空态。 */
    @Test
    fun emptyHintFallsBackToGenericWhenNothingToFilter() {
        assertEquals(
            R.string.dsh_download_empty_all,
            DshDownloadViewModel.emptyHintRes(itemsEmpty = true, showPrerelease = true)
        )
    }

    /** 非空列表：两个开关态都不显示空态（返回 null）。 */
    @Test
    fun emptyHintIsNullForNonEmptyList() {
        assertNull(DshDownloadViewModel.emptyHintRes(itemsEmpty = false, showPrerelease = true))
        assertNull(DshDownloadViewModel.emptyHintRes(itemsEmpty = false, showPrerelease = false))
    }
}
