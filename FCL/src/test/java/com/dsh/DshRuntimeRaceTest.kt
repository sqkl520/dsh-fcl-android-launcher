package com.dsh

import com.dsh.core.DshRuntime
import com.dsh.core.activeOwnerOf
import com.dsh.core.canSendQuitSignal
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

    // --- BL-036：含 Stopping 的"运行时通道占用者"（activeInstanceId 的纯函数核） --------

    /**
     * 五态覆盖：Starting/Running/Stopping 返回归属，Idle/Failed/Exited 返回 null。
     *
     * 这条钉住的是 BL-036 的判据核心：`runningInstanceId()`（不含 Stopping）回答"还能做停止/开界面
     * 吗"，它把 Stopping 判成"没在跑"是合理的；但**删除实例/等进程退干净**的场景必须用
     * `activeInstanceId()`——Stopping 窗口里进程还活着，若判成 null 就会去秒置 Idle 或直接删
     * 进程还在写的实例目录。两者取舍不同，所以各自钉各自的映射，防止将来有人把某一个"顺手统一"掉。
     */
    @Test
    fun activeOwnerOfMapsEachState() {
        val stopping = DshRuntime.State.Stopping("A", "nameA")
        assertEquals("Stopping 仍占用运行时通道（进程还没退干净）", "A", activeOwnerOf(stopping))
        assertEquals("Starting 占用", "A", activeOwnerOf(DshRuntime.State.Starting("A", "nameA")))
        assertEquals("Running 占用", "A", activeOwnerOf(DshRuntime.State.Running("A", "nameA", null, 3080)))
    }

    @Test
    fun activeOwnerOfIsNullForIdleFailedExited() {
        // Idle/Failed/Exited：进程收尾已有明确结论（或从未启动），运行时通道空闲 —— 返回 null。
        // 与 ownerOfState 的关键差别：后面对 Failed/Exited 仍给归属（迟到回调校验用），
        // 本函数对它们给 null（通道空闲）。两条映射并存是**有意**的，不是不一致。
        assertNull("Idle 无占用", activeOwnerOf(DshRuntime.State.Idle))
        assertNull("Failed 不占用（失败结论已落）", activeOwnerOf(DshRuntime.State.Failed("A", "nameA", "boom")))
        assertNull("Exited 不占用", activeOwnerOf(DshRuntime.State.Exited("A", "nameA", 1)))
    }

    @Test
    fun activeOwnerOfKeepsStoppingOwnedButReleasesFailedAndExited() {
        // 把"两个函数分工"本身钉成断言：ownerOfState 与 activeOwnerOf 在 Idle/Starting/Running/
        // Stopping 上一致，差别只在 Failed/Exited（owner 仍归属供状态覆写校验；active 已空闲供
        // 删除/等待判据）。最有价值的一条：Stopping 在两边都保留归属 —— 把 Stopping 判成"无主"
        // 正是 BL-036（二次 stop 秒置 Idle / 删除跳过等待）的根源。
        val stopping = DshRuntime.State.Stopping("A", "nameA")
        assertEquals("ownerOfState 对 Stopping 的归属", "A", ownerOfState(stopping))
        assertEquals("activeOwnerOf 对 Stopping 的归属", "A", activeOwnerOf(stopping))
        // 而 Failed：owner 仍归属（供状态覆写校验），active 已空闲（供删除/等待判据）—— 两条映射分工不同
        val failed = DshRuntime.State.Failed("A", "nameA", "boom")
        assertEquals("Failed 的 owner 仍是 A", "A", ownerOfState(failed))
        assertNull("Failed 的 active 已空闲", activeOwnerOf(failed))
    }

    // --- BL-040：SIGQUIT 前的身份复核（canSendQuitSignal） -------------------------

    /**
     * 放行一侧：pid 有效 + cmdline 同时含 proot 与本实例 instanceId。
     * 形状取自 [com.dsh.core.ProotCommand.build] 的真实 argv（`--bind=.../instances/<id>` 与
     * `INSTANCE_DIR=/opt/dsh/instances/<id>` 都在 cmdline 里）。
     */
    @Test
    fun quitSignalAllowedOnlyForVerifiedInstanceProot() {
        val cmdline = "/data/app/~~x/lib/arm64/libproot.so -r /files/rootfs -0 -w /opt/dsh/instances/" +
            "inst-123-1-abc/workspace --link2symlink --bind=/files/dsh:/opt/dsh /usr/bin/env " +
            "INSTANCE_DIR=/opt/dsh/instances/inst-123-1-abc /bin/sh /opt/dsh/scripts/start-dsh.sh"
        assertTrue(
            "cmdline 含 proot 且含本实例 id → 允许 SIGQUIT",
            canSendQuitSignal(pid = 4321, cmdline = cmdline, instanceId = "inst-123-1-abc")
        )
    }

    @Test
    fun quitSignalRejectedForMismatchedOrUnknownIdentity() {
        val otherInstanceCmdline = "libproot.so -r /rootfs --bind=/dsh:/opt/dsh " +
            "INSTANCE_DIR=/opt/dsh/instances/inst-999-9-zzz /bin/sh start-dsh.sh"
        // 不匹配：目标是**另一个实例**的 proot（差集选错进程的现场）→ 不许发
        assertFalse(
            "cmdline 含 proot 但不含本实例 id → 拒绝",
            canSendQuitSignal(pid = 4321, cmdline = otherInstanceCmdline, instanceId = "inst-123-1-abc")
        )
        // 空 cmdline：读不到 → "不确定"不允许被当作"通过"
        assertFalse(
            "cmdline 为空（读不到）→ 拒绝",
            canSendQuitSignal(pid = 4321, cmdline = "", instanceId = "inst-123-1-abc")
        )
        // 完全不含 proot 的进程（pid 被系统复用给别的进程）→ 拒绝
        assertFalse(
            "非 proot 进程 → 拒绝",
            canSendQuitSignal(pid = 4321, cmdline = "/system/bin/someapp --serve", instanceId = "inst-123-1-abc")
        )
    }

    @Test
    fun quitSignalRejectedForInvalidPidOrBlankInstanceId() {
        val cmdline = "libproot.so INSTANCE_DIR=/opt/dsh/instances/inst-123-1-abc"
        // pid<=0：拿不到 pid（真机常态）—— 原有路径本来就不发 SIGQUIT，判据同样拒绝
        assertFalse("pid=0 → 拒绝", canSendQuitSignal(pid = 0, cmdline = cmdline, instanceId = "inst-123-1-abc"))
        assertFalse("pid<0 → 拒绝", canSendQuitSignal(pid = -1, cmdline = cmdline, instanceId = "inst-123-1-abc"))
        // 空 instanceId：contains("") 恒真，会把判据退化成"只要含 proot 就发"——必须显式拒绝
        assertFalse(
            "空 instanceId → 拒绝（防 contains(“”) 退化）",
            canSendQuitSignal(pid = 4321, cmdline = "libproot.so anything", instanceId = "")
        )
    }
}
