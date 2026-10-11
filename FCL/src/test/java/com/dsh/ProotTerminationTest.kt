package com.dsh

import com.dsh.core.ProotTermination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ProotTermination` 的回归测试 —— 钉住 T-A1 / K01 的**纯逻辑判断**。
 *
 * ## 为什么只能测这些纯函数（而不是真的发信号 / 杀进程）
 * 真正"杀 proot"那一步靠的是 `android.os.Process.sendSignal(pid, 3)` 与 `Process.destroy()`：
 * - `android.os.Process` 在单测里是 **android.jar 的桩**（真调用抛 "Stub!"）；
 * - 没有真进程可供 start/kill，`/proc` 也造不出来。
 * 所以"真机到底有没有把 npm/node 杀干净"只能靠真机门 A 验（见 DEV-PLAN §6）。
 * 这里把**可判定的决策**抽成纯函数后钉成断言：有 pid 该不该 SIGQUIT、tag 该不该匹配、
 * finally 该不该清表、还活着才升级强杀 —— 这些一旦被改回旧行为（例如又只发 destroy()），立刻变红。
 *
 * 跑法：`sh run-tests.sh`
 */
class ProotTerminationTest {

    /**
     * ★ 前提常量：proot 的事件循环只响应 SIGQUIT/SIGILL/SIGABRT/SIGFPE/SIGSEGV，
     * 清理路径（kill_all_tracees）绑的就是 **SIGQUIT=3**。谁把它改成别的数字，
     * 真机上 proot 又会对信号装死、npm/node 变孤儿 —— 这条逼人回来看。
     */
    @Test
    fun sigquitConstantIsThree() {
        assertEquals("SIGQUIT 必须是 3（proot 唯一会走清理路径的终止信号）", 3L, ProotTermination.SIGQUIT.toLong())
    }

    /**
     * ★ K01 的核心决策：**拿得到 pid 就必须先 SIGQUIT**（而不是旧的 destroy()=SIGTERM）。
     * 任意正数 pid 都走 SIGQUIT。
     */
    @Test
    fun firstStopSignalUsesSigquitWhenPidResolved() {
        assertEquals(ProotTermination.StopSignal.SIGQUIT, ProotTermination.firstStopSignal(1L))
        assertEquals(ProotTermination.StopSignal.SIGQUIT, ProotTermination.firstStopSignal(1234L))
        assertEquals(ProotTermination.StopSignal.SIGQUIT, ProotTermination.firstStopSignal(Long.MAX_VALUE))
    }

    /**
     * 拿不到 pid（pid<=0：桌面反射不可用且 `/proc` 兜底也失败）才退化为 `destroy()`。
     * 0 和负数都算"没解析到"，不能被当成合法 pid 去 `sendSignal`（那会误伤：kill 0 = 整个进程组）。
     */
    @Test
    fun firstStopSignalFallsBackToDestroyWhenPidUnknown() {
        assertEquals(ProotTermination.StopSignal.DESTROY, ProotTermination.firstStopSignal(0L))
        assertEquals(ProotTermination.StopSignal.DESTROY, ProotTermination.firstStopSignal(-1L))
        assertEquals(ProotTermination.StopSignal.DESTROY, ProotTermination.firstStopSignal(-999L))
    }

    /**
     * ★ 行为契约：请求 tag 为 null = "全部杀"。无论登记项的 tag 是具体实例还是 null，都要匹配。
     * 这是 App 退出清理 / `destroyActive()` 不带参数时的"杀光"语义。
     */
    @Test
    fun tagMatchesNullRequestKillsEverything() {
        assertTrue(ProotTermination.tagMatches(null, "instance-A"))
        assertTrue(ProotTermination.tagMatches(null, "instance-B"))
        assertTrue(ProotTermination.tagMatches(null, null))
    }

    /**
     * ★ 行为契约（"取消 A 绝不能杀 B"）：指定 tag 时**只**匹配登记在同一 instanceId 下的那条。
     * 别的实例、以及没有归属（null）的登记项都不能被这次取消误杀。
     */
    @Test
    fun tagMatchesSpecificRequestOnlyHitsSameTag() {
        assertTrue("取消 A 必须命中 A 自己", ProotTermination.tagMatches("instance-A", "instance-A"))
        assertFalse("取消 A 不能命中 B", ProotTermination.tagMatches("instance-A", "instance-B"))
        assertFalse("取消 A 不能命中无归属进程", ProotTermination.tagMatches("instance-A", null))
    }

    /**
     * ★ 行为契约：finally 只摘**已退出**的登记项。还活着的必须留在表里 ——
     * 否则后续 `destroyActive` 再也找不到它去杀，那正是孤儿 npm/node 逃逸的方式。
     */
    @Test
    fun shouldReapOnFinallyOnlyReapsExited() {
        assertTrue("已退出 → 从登记表摘掉", ProotTermination.shouldReapOnFinally(false))
        assertFalse("还活着 → 必须留在表里（否则取消就再也找不到它）", ProotTermination.shouldReapOnFinally(true))
    }

    /**
     * 升级到 `destroyForcibly()`（SIGKILL）只在"首个信号 + 宽限后仍存活"时发生；
     * 已经退出的进程不该再被强杀（无意义，且会在日志里制造假象）。
     */
    @Test
    fun shouldForceKillOnlyWhenStillAlive() {
        assertTrue("宽限后还活着 → 升级强杀", ProotTermination.shouldForceKill(true))
        assertFalse("已经退出 → 不再强杀", ProotTermination.shouldForceKill(false))
    }
}
