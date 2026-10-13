package com.dsh

import com.dsh.fcl.androidlauncher.R
import com.dsh.ui.setup.setupStepStateOverwritable
import com.dsh.ui.setup.setupStepStateRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix2-W3Da：首启准备页的两条纯逻辑内核单测（BL-074）。
 *
 * 不碰 Android：只测从 [com.dsh.ui.setup.DshSetupActivity] 抽出的顶层纯函数 ——
 * [setupStepStateRes]（rootfs 行未就绪时的状态选择）与
 * [setupStepStateOverwritable]（磁盘刷新覆写守卫）。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.W3DaTest"
 *   （或仓库 run-tests.sh，自动扫描本类）
 */
class W3DaTest {

    // --- BL-074：rootfs 行"进行中"垫层 ------------------------------------------

    /** 解压进行中、rootfs 行未就绪 → "进行中"（R12-B4-3：清单不再全程"等待中"）。 */
    @Test
    fun rootfsRowShowsRunningWhilePreparing() {
        assertEquals(
            R.string.dsh_setup_state_running,
            setupStepStateRes(rowOk = false, isRootfsRow = true, preparing = true)
        )
    }

    /** 未在准备（初始态/已停下）→ 维持"等待中"，不能把静止页面画成进行中。 */
    @Test
    fun rootfsRowStaysWaitingWhenIdle() {
        assertEquals(
            R.string.dsh_setup_state_waiting,
            setupStepStateRes(rowOk = false, isRootfsRow = true, preparing = false)
        )
    }

    /** "进行中"只垫 rootfs 行：proot/scripts 行照旧"等待中"（别扩大 BL-074 范围）。 */
    @Test
    fun nonRootfsRowsNeverShowRunning() {
        assertEquals(
            R.string.dsh_setup_state_waiting,
            setupStepStateRes(rowOk = false, isRootfsRow = false, preparing = true)
        )
    }

    /** 磁盘事实已就绪 → 一律"已就绪"，压过 preparing（解压完成帧先落盘后收尾的窗口）。 */
    @Test
    fun readyBeatsPreparing() {
        assertEquals(
            R.string.dsh_setup_state_ready,
            setupStepStateRes(rowOk = true, isRootfsRow = true, preparing = true)
        )
    }

    // --- BL-074：覆写守卫 --------------------------------------------------------

    /** "失败"是终态呈现：普通磁盘刷新不得覆写回"等待中"（R12-B4-3 矛盾的直接成因）。 */
    @Test
    fun failedStateIsProtectedFromDiskRefresh() {
        assertFalse(
            "失败态必须挡住磁盘刷新的覆写",
            setupStepStateOverwritable(R.string.dsh_setup_state_failed)
        )
    }

    /** "已就绪"与"进行中"同样是有意置上的呈现，不允许被静默覆写。 */
    @Test
    fun readyAndRunningAreProtected() {
        assertFalse(setupStepStateOverwritable(R.string.dsh_setup_state_ready))
        assertFalse(setupStepStateOverwritable(R.string.dsh_setup_state_running))
    }

    /** 未置位（tag null）与"等待中"可被覆写 —— 守卫不能把自己挡死。 */
    @Test
    fun unsetAndWaitingRemainOverwritable() {
        assertTrue(setupStepStateOverwritable(null))
        assertTrue(setupStepStateOverwritable(R.string.dsh_setup_state_waiting))
    }

    /**
     * 守卫的复位出口存在且恰是"等待中"：beginPrepare 显式置回等待中后，
     * 守卫必须放行后续刷新（否则"失败后重试"会把自检行永久锁死）。
     */
    @Test
    fun resetViaWaitingReopensTheGate() {
        val afterReset = setupStepStateRes(rowOk = false, isRootfsRow = false, preparing = false)
        assertEquals(R.string.dsh_setup_state_waiting, afterReset)
        assertTrue("置回等待中后必须重新可覆写", setupStepStateOverwritable(afterReset))
    }
}
