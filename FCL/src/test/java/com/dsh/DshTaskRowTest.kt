package com.dsh

import com.dsh.core.DshInstance
import com.dsh.ui.isRowBusy
import com.dsh.ui.shell.joinTaskTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3a（实例行按钮态 + 任务区文案/配色）回归：BL-020 / BL-021 / BL-029。
 *
 * 全是纯逻辑，不碰 Android：
 * - BL-029：实例行 busy 判据（[isRowBusy]）——状态流空窗期只看 status 会误判"不忙"，
 *   所以判据必须同时看实例自己的 `state==INSTALLING`。
 * - BL-021：任务标题「标题 · 阶段」拼接（[joinTaskTitle]，[DshTask.titleText] 的纯逻辑部分）——
 *   空段过滤 + 「 · 」连接；终态 stage 本身的去重（改 doneTask 的 stage 为纯结论
 *   `dsh_task_install_done`）在代码侧是资源串，单测只钉拼接规则，防悬空分隔符。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.DshTaskRowTest"
 */
class DshTaskRowTest {

    // --- BL-029：busy 判据 ----------------------------------------------------

    /** BL-029 核心：status 空窗期（null）但实例是 INSTALLING → 必须算忙（防按钮短暂可点）。 */
    @Test
    fun busyWhenStateInstallingButNoStatus() {
        assertTrue(isRowBusy(false, null, DshInstance.State.INSTALLING))
    }

    /** status 说在装 → 忙（原判据保留）。 */
    @Test
    fun busyWhenStatusRunning() {
        assertTrue(isRowBusy(false, true, DshInstance.State.INSTALLING))
        assertTrue(isRowBusy(false, true, DshInstance.State.READY))
    }

    /** 删除中 → 忙（原判据保留）。 */
    @Test
    fun busyWhenDeleting() {
        assertTrue(isRowBusy(true, null, DshInstance.State.READY))
        assertTrue(isRowBusy(true, false, DshInstance.State.READY))
    }

    /** 空闲行：不删、无状态、状态非 INSTALLING → 不忙。 */
    @Test
    fun notBusyWhenIdle() {
        assertFalse(isRowBusy(false, null, DshInstance.State.READY))
        assertFalse(isRowBusy(false, null, DshInstance.State.NOT_INSTALLED))
        assertFalse(isRowBusy(false, null, DshInstance.State.BROKEN))
        assertFalse(isRowBusy(false, false, DshInstance.State.READY))
    }

    // --- BL-021：任务标题拼接 -------------------------------------------------

    /** 正常两段拼成「实例名 · 阶段」。 */
    @Test
    fun joinsTitleAndStage() {
        assertEquals("dsh 0.2.0-rc.2 · 安装完成", joinTaskTitle("dsh 0.2.0-rc.2", "安装完成"))
    }

    /** 空阶段 → 只有标题，不留悬空分隔符。 */
    @Test
    fun blankStageLeavesNoSeparator() {
        assertEquals("dsh 0.2.0-rc.2", joinTaskTitle("dsh 0.2.0-rc.2", ""))
        assertEquals("dsh 0.2.0-rc.2", joinTaskTitle("dsh 0.2.0-rc.2", "   "))
    }

    /** 空标题 → 只有阶段。 */
    @Test
    fun blankTitleLeavesNoSeparator() {
        assertEquals("正在准备运行环境", joinTaskTitle("", "正在准备运行环境"))
    }

    /** 两段都空 → 空串。 */
    @Test
    fun bothBlankIsEmpty() {
        assertEquals("", joinTaskTitle("", ""))
    }
}
