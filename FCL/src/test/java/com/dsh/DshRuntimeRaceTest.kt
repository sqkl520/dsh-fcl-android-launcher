package com.dsh

import com.dsh.core.DshRuntime
import com.dsh.core.ownerOfState
import com.dsh.core.pidFileBelongsTo
import com.dsh.core.shouldPersistPort
import com.dsh.core.stateOwnedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-A2「停启竞态第一批」里**能在 JVM 上钉死的纯逻辑**单测（不碰 proot/rootfs/Android）。
 *
 * ★ 先说清能测与不能测（免得这组用例给人"竞态覆盖全了"的错觉）：
 *   - **能测**：把每个修复的"判定核心"抽成了无状态纯函数——是否回写端口（K04）、pid 文件能否删
 *     （K03）、状态写回归属（K05）。它们是这几个 bug"错一位就出事"的地方，JVM 上可直接断言。
 *   - **测不了**：真机上的时序本身——停到位再启（K02）、把阻塞杀进程移出同步块（BL-007）需要
 *     proot、真实 pid、协程调度与前台服务，只能上真机验证（见批 A 真机门）。这里**不**为了可测把
 *     [DshRuntime] 的进程管理结构重新打开（代价远大于收益）。
 */
class DshRuntimeRaceTest {

    // --- K04：端口是否回写（仅自动端口才回写，别覆盖用户固定的端口） --------------

    @Test
    fun autoPortIsPersistedOnceResolved() {
        // 实例本来是自动端口（0），dsh 挑了 3080 → 应回写，方便界面显示与下次复用
        assertTrue("自动端口解析出真实端口后应回写", shouldPersistPort(configuredPort = 0, actualPort = 3080))
        assertTrue("负数也按自动端口对待", shouldPersistPort(configuredPort = -1, actualPort = 3080))
    }

    @Test
    fun fixedUserPortIsNeverOverwritten() {
        // K04 的核心：用户手工固定了 3080，端口被占后自动避让到 4000 —— 决不能把 4000 写回配置，
        // 否则用户设的固定端口被永久覆盖（下次又从 4000 起）。
        assertFalse("用户固定端口被自动避让后不得回写", shouldPersistPort(configuredPort = 3080, actualPort = 4000))
        // 固定端口且实际相同，自然也不写
        assertFalse("固定端口未变不需要回写", shouldPersistPort(configuredPort = 3080, actualPort = 3080))
    }

    @Test
    fun noPersistWhenNoRealPort() {
        // 自动端口但没解析出真实端口（0）：没有可写的东西
        assertFalse("没有真实端口就不写", shouldPersistPort(configuredPort = 0, actualPort = 0))
    }

    // --- K03：pid 文件 compare-and-delete（只删仍是"我的 pid"的那份） --------------

    @Test
    fun pidFileDeletableWhenItIsMineOrGone() {
        assertTrue("文件已不存在/读不出 → 删是无害 no-op", pidFileBelongsTo(storedPid = null, expectedPid = 123))
        assertTrue("文件里仍是我这个 pid → 可以删", pidFileBelongsTo(storedPid = 123, expectedPid = 123))
    }

    @Test
    fun pidFileNotDeletedWhenItBelongsToANewProcess() {
        // K03 的核心：停后秒启——新进程把自己的 pid（456）写进了文件，收尾协程手里是旧 pid（123），
        // 不许删（删了新进程就变失联孤儿）。
        assertFalse("文件里是别的（新）pid → 不许删", pidFileBelongsTo(storedPid = 456, expectedPid = 123))
        // 拿不到自己的 pid（0）时也天然不会误删一个真实的新 pid
        assertFalse("expectedPid=0 时不得误删真实 pid", pidFileBelongsTo(storedPid = 456, expectedPid = 0))
    }

    // --- K05：状态写回归属判定（迟到的 Failed 不许覆盖已启动的新实例） --------------

    @Test
    fun ownerOfStateMapsEachStateToItsInstance() {
        assertNull("Idle 无归属", ownerOfState(DshRuntime.State.Idle))
        assertEquals("A", ownerOfState(DshRuntime.State.Starting("A", "nameA")))
        assertEquals("A", ownerOfState(DshRuntime.State.Running("A", "nameA", null, 3080)))
        assertEquals("A", ownerOfState(DshRuntime.State.Stopping("A", "nameA")))
        assertEquals("A", ownerOfState(DshRuntime.State.Failed("A", "nameA", "boom")))
        assertEquals("A", ownerOfState(DshRuntime.State.Exited("A", "nameA", 1)))
    }

    @Test
    fun staleFailedWriteIsRejectedWhenAnotherInstanceOwnsState() {
        // K05 的核心：A 的看门狗/超时迟到触发 failAndCleanup(A) 的那一刻，用户已经起了 B
        // （状态已是 Starting(B)）。只有"当前状态仍属于 A"时才允许写 Failed(A)。
        val nowOwnedByB = DshRuntime.State.Starting("B", "nameB")
        assertFalse("状态已归 B，A 的 Failed 不许写", stateOwnedBy(nowOwnedByB, "A"))
        assertTrue("状态仍归 A 时才允许写", stateOwnedBy(DshRuntime.State.Starting("A", "nameA"), "A"))
        assertFalse("Idle 不属于任何实例", stateOwnedBy(DshRuntime.State.Idle, "A"))
    }
}
