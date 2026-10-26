package com.dsh

import com.dsh.core.DshRuntime
import com.dsh.core.adoptionAllowed
import com.dsh.core.canJoinExistingRun
import com.dsh.core.shouldClearStopRequested
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round24（S2 状态机收口 A 版）单测：把三个"改一位就出事"的判据抽成纯函数后在 JVM 上钉死。
 *
 * ★ 能测与不能测（免得这组用例给人"H1 已被完整回归"的错觉）：
 * - **能测**：复用判据的三元组（状态归属 / 状态种类 / 句柄活体）、stopRequestedFor 的归属清理、
 *   认领闸门的"无归属"语义。它们就是 A1a / A3 / A4 的判定核心，且全部与 android 无关。
 * - **测不了**：T1 的终极形态"seccomp 重试必须真的 fork 第二个进程（launchCount=2）"—— 那需要把
 *   `ProotRunner.start`/`ProotCommand.build`/`DshPaths` 全部做成可注入 seam + fake Context
 *   （IMPL-PLAN A8 明确把 fake Context 留到 B 版）。本文件钉的是那条链路的**判定**：
 *   重试时 handle 必然已被 onProcessExit 清成 null ⇒ [canJoinExistingRun] 返回 false ⇒ 不早退 ⇒
 *   一定走到 fork。H1 的端到端跑红证据见 docs/reports/round24/E4-redteam.md 的独立模型。
 *
 * 为什么这些函数是 top-level：单测 harness 用 MiniRunner 直接反射调用，不加载 android 依赖；
 * top-level 函数落在 `DshRuntimeKt` 的静态方法上（与 ownerOfState / pidFileBelongsTo 同惯例）。
 */
class R24StateMachineTest {

    private val idle = DshRuntime.State.Idle
    private fun starting(id: String) = DshRuntime.State.Starting(id, "name-$id")
    private fun running(id: String) = DshRuntime.State.Running(id, "name-$id", "http://127.0.0.1:3080/", 3080)
    private fun stopping(id: String) = DshRuntime.State.Stopping(id, "name-$id")
    private fun failed(id: String) = DshRuntime.State.Failed(id, "name-$id", "boom")
    private fun exited(id: String) = DshRuntime.State.Exited(id, "name-$id", 1)

    // --- T1：A1a 复用判据三元组 ---------------------------------------------------

    /**
     * T1 ①（H1 的核心）：seccomp 重试现场 —— 状态仍是 Starting(A)，但 onProcessExit 已把 handle 清成
     * null（纯函数收到 null），或句柄指向的进程已死（false）。两种都必须返回 false ⇒ 不早退 ⇒ fork。
     *
     * 旧判据 `runningInstanceId() == instance.id` 在这里返回 true（Starting 也算"在跑"），
     * 直接"复用现有进程"返回 —— 这正是 E4 在 fix10 上跑红的 launchCount=1 / 永远停在 Starting。
     */
    @Test
    fun retryPathWithDeadOrMissingHandleMustFork() {
        assertFalse(
            "H1：状态 Starting(A) + handle 已死 ⇒ 必须 fork（旧判据这里会误判复用）",
            canJoinExistingRun("A", starting("A"), handleAlive = false)
        )
        assertFalse(
            "H1：状态 Starting(A) + 没有句柄（onProcessExit 已清）⇒ 必须 fork",
            canJoinExistingRun("A", starting("A"), handleAlive = null)
        )
    }

    /** T1 ②：正常"双击启动"的复用路径 —— 同实例、Running、句柄活着 ⇒ 复用（不许 fork 第二个）。 */
    @Test
    fun liveRunOfSameInstanceIsReused() {
        assertTrue(
            "Running(A) + 活句柄 ⇒ 复用",
            canJoinExistingRun("A", running("A"), handleAlive = true)
        )
        assertTrue(
            "Starting(A) + 活句柄 ⇒ 复用（双击启动的第二下不该 fork）",
            canJoinExistingRun("A", starting("A"), handleAlive = true)
        )
    }

    /** T1 ③：Idle 没有可复用的 run（哪怕句柄字段里还残留一个"活"值，也应拒绝）。 */
    @Test
    fun idleIsNeverReusable() {
        assertFalse("Idle + 无句柄 ⇒ 不复用", canJoinExistingRun("A", idle, handleAlive = null))
        assertFalse("Idle + 残留活句柄 ⇒ 也不复用", canJoinExistingRun("A", idle, handleAlive = true))
    }

    /** T1 ④：状态属于**别的实例** ⇒ 不复用（单实例策略下要先停掉它再起本实例）。 */
    @Test
    fun otherInstancesRunIsNotReusable() {
        assertFalse("Running(B) 不属于 A", canJoinExistingRun("A", running("B"), handleAlive = true))
        assertFalse("Starting(B) 不属于 A", canJoinExistingRun("A", starting("B"), handleAlive = true))
        assertFalse("Failed(B) 不属于 A", canJoinExistingRun("A", failed("B"), handleAlive = true))
    }

    /** T1 ⑤：句柄为 null ⇒ 不复用（与"句柄死了"分开钉，两者是不同现场、同一条结论）。 */
    @Test
    fun missingHandleIsNeverReusable() {
        assertFalse("Running(A) 但没有句柄 ⇒ 不复用", canJoinExistingRun("A", running("A"), handleAlive = null))
        assertFalse("Starting(A) 但没有句柄 ⇒ 不复用", canJoinExistingRun("A", starting("A"), handleAlive = null))
    }

    /**
     * T1 附加：Stopping / Failed / Exited 即使"句柄还活着"也不许复用。
     * Stopping 的句柄在本轮 N-2 之后**故意保留**在字段里（终止失败可重试），如果不在这里拒绝，
     * 用户点停止后立刻点启动就会复用一个正在被 TERM/KILL 的进程。
     */
    @Test
    fun terminalAndStoppingStatesAreNotReusable() {
        assertFalse("Stopping(A) + 活句柄 ⇒ 不复用（正在拆的房子不能住）",
            canJoinExistingRun("A", stopping("A"), handleAlive = true))
        assertFalse("Failed(A) + 活句柄 ⇒ 不复用",
            canJoinExistingRun("A", failed("A"), handleAlive = true))
        assertFalse("Exited(A) + 活句柄 ⇒ 不复用",
            canJoinExistingRun("A", exited("A"), handleAlive = true))
    }

    // --- T2：A3 stopRequestedFor 的归属清理 ---------------------------------------

    /** T2：本实例的旧"主动停止"标记必须清（否则新 run 自己崩掉会被静默成"已停止"）。 */
    @Test
    fun ownStopMarkerIsCleared() {
        assertTrue("A 的标记在起 A 时清掉", shouldClearStopRequested("A", "A"))
    }

    /** T2：别的实例的标记**不许**清（"停 A → 立刻起 B"时 A 的 143 退出不能被误报成崩溃）。 */
    @Test
    fun otherInstancesStopMarkerIsKept() {
        assertFalse("A 的标记在起 B 时不许清", shouldClearStopRequested("A", "B"))
        assertFalse("没有标记时什么都不做", shouldClearStopRequested(null, "A"))
    }

    // --- T3：A4 认领闸门"无归属" ---------------------------------------------------

    /** T3 放行侧：只有 Idle（完全无归属）才允许认领。 */
    @Test
    fun adoptionAllowedOnlyWhenIdle() {
        assertTrue("Idle = 通道空闲，允许认领", adoptionAllowed(idle))
    }

    /**
     * T3 拒绝侧（五态各一条）：Starting/Running/Stopping 占着在途进程；Failed/Exited 的归属仍在
     * （失败结论还没被用户复位）—— 任何一条放行都会覆盖现场。
     *
     * 重点是 Failed/Exited 两条：旧判据 `runningInstanceId() != null` 对它们返回 null ⇒ 慢探活期间
     * 实例启动失败落了 Failed，认领提交段照样把失败态覆盖成 Running(adopted) 并换上死 pid 的句柄（F12）。
     */
    @Test
    fun adoptionRejectedForEveryOwnedState() {
        assertFalse("Starting 占用通道，拒绝认领", adoptionAllowed(starting("A")))
        assertFalse("Running 占用通道，拒绝认领", adoptionAllowed(running("A")))
        assertFalse("Stopping 占用通道，拒绝认领", adoptionAllowed(stopping("A")))
        assertFalse("Failed 归属未复位，拒绝认领（旧判据这里会误放行）", adoptionAllowed(failed("A")))
        assertFalse("Exited 归属未复位，拒绝认领（旧判据这里会误放行）", adoptionAllowed(exited("A")))
    }
}
