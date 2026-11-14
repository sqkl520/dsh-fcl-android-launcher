package com.dsh

import com.dsh.core.DshRuntime
import com.dsh.core.planExitSettlement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R36：两处「同型锁外 RMW」的修复钉子。
 *
 * - **D-1** `DshRuntime.stopAndWait` 的"没在跑 → 裸写 Idle 后 return true"快路径（原 `:2055-2061`）
 * - **D-2** `DshRuntime.onProcessExit` 的句柄三连写（原 `:2453-2458`）
 *
 * ## 这组测试能测什么 / 不能测什么（先说实话）
 *
 * **能测**：
 * 1. D-2 的**判决本体** [planExitSettlement] —— 纯函数，5 种状态 × 归属 × 世代 × `stopRequestedFor`
 *    全部可在 JVM 上穷举。这是"锁内重读之后该不该动全局句柄"的决策核。
 * 2. D-1 的**真实行为**：`stopAndWait` 在 JVM 上真的能跑（`object DshRuntime` 的构造与
 *    `stop()` 的 null 分支都不碰需要 Context 的路径）。所以"删掉快路径后仍返回 true、仍复位到
 *    Idle、并且**多做了**旧快路径从来没有的 leftover 句柄收尾"可以**实跑**断言，不是只扫源码。
 * 3. 两处的**接线形状**（对源码做结构化断言）：调用点到底有没有把锁外快照传进去、三连写到底在不在
 *    同一个 `synchronized(this)` 里、副作用有没有被拖进监视器。这一条有已知的判别力上限（只看
 *    形状不看语义），所以必须靠**变异验证**自我证明（见报告 FIX-LOCKED-RMW.md：
 *    把 `current = _state.value` 改回 `current = cur`、把三行赋值挪出锁、把删掉的快路径加回去，
 *    三刀各自的红/绿记录都贴在报告里）。
 *
 * **不能测**：真机上的**交错**。`onProcessExit` 是 private 且很难在 JVM 上驱动到"锁外快照时还是
 * 我的、锁内重读已易主"的那一刻 —— 那需要一个真实的 `startLocked` 与新 run 并发抢占。
 * 红队 `RaceProbe2.kt` G 组给出的 `2000/2000` 是**探针造出来的**：它在读与写之间插了
 * `goAhead.await()`，把"另一线程已经写好 Starting"变成前置保证才放行写 —— 那不是概率测量。
 * 按无人工同步点的口径重测，真实自然交错率是 D-1 `119/250006 ≈ 0.0476%`、D-2 `89/257398 ≈ 0.0346%`
 * （窗口均值 289ns / 186ns）；生产可达性还要再低一档（`stopAndWait` 两个调用点都在 `startMutex` 内）。
 * 详见 `docs/reports/round36/PLAN-LOCKED-RMW.md` §1.3 / §2.2 与 `FIX-LOCKED-RMW.md`。
 *
 * ★ **本文件不证明线程安全**。本轮只封死两个具名点：`_state` / `nodePid` 仍有别的锁外写
 *   （例如 `onProcessLine` 的 `nodePid`），不要拿这组用例当"状态机已安全"的依据。
 *
 * 运行：sh run-tests.sh，或 ./gradlew :FCL:testDebugUnitTest --tests "com.dsh.R36LockedRmwTest"
 */
class R36LockedRmwTest {

    // --- 公共夹具 -----------------------------------------------------------

    private val inst = "inst-a"
    private val other = "inst-b"

    /** 本实例的五种有归属状态（判据 [com.dsh.core.ownerOfState] 认的那五个） */
    private fun ownStates(id: String = inst): List<DshRuntime.State> = listOf(
        DshRuntime.State.Starting(id, "n-$id"),
        DshRuntime.State.Running(id, "n-$id", null, 3080),
        DshRuntime.State.Stopping(id, "n-$id"),
        DshRuntime.State.Failed(id, "n-$id", "boom"),
        DshRuntime.State.Exited(id, "n-$id", 1),
    )

    // =========================================================================
    // ① planExitSettlement 的判定（D-2 的决策本体）
    // =========================================================================

    /**
     * `Idle` 的归属是 null ⇒ 任何实例的退出回调都**不许**动全局句柄。
     *
     * 为什么这条重要：`Idle` 是"通道空闲"的断言。这时 `handle` 若还有值，那是**上一轮**的残留；
     * 退出回调把它清掉看起来"顺手"，但同一时刻可能已有新 run 在 `startLocked` 里写了它自己的
     * handle —— 判决必须是"不归我，一个字都不碰"，而不是"没归属所以我随便清"。
     */
    @Test
    fun idleHasNoOwnerSoSettlementIsRefused() {
        assertNull(
            "Idle 无归属 ⇒ 必须返回 null（不许写 handle / nodePid / _running）",
            planExitSettlement(
                current = DshRuntime.State.Idle,
                instanceId = inst,
                stopRequestedFor = inst,
                nodePid = 4242L,
                runGen = 7L,
                gen = 7L,
            )
        )
    }

    /**
     * 别的实例的每一种状态 ⇒ 都不许结算。
     *
     * 这是"停 A 起 B"的常规形态：A 的退出回调迟到时，`_state` 已是 B 的 Starting/Running。
     * 旧实现无条件写 `handle = null`，会把 **B 的** proot 句柄抹掉。
     */
    @Test
    fun foreignInstanceStatesAreNeverSettled() {
        for (st in ownStates(other)) {
            assertNull(
                "状态是别的实例的 ${st::class.simpleName} ⇒ 必须返回 null（不许抹新 run 的句柄）",
                planExitSettlement(
                    current = st,
                    instanceId = inst,
                    stopRequestedFor = null,
                    nodePid = 1L,
                    runGen = 3L,
                    gen = 3L,
                )
            )
        }
    }

    /**
     * 本实例的五种状态 ⇒ 都**必须**结算。
     *
     * 五个都要放行，尤其 `Failed` / `Exited`：那两个正是 `onProcessExit` 下游两条正常收尾分支
     * （保留失败原因 / 落 Exited）的入口。这里若改严成 [com.dsh.core.activeOwnerOf]（对
     * Failed/Exited 返回 null），这两条分支会全部变成"别人的退出"，`handle` 永远不清、进程
     * 退出后状态永远停在 Failed/Exited —— 一个过分保守的修法同样会坏。
     */
    @Test
    fun ownInstanceStatesAreAlwaysSettled() {
        for (st in ownStates()) {
            val plan = planExitSettlement(
                current = st,
                instanceId = inst,
                stopRequestedFor = null,
                nodePid = 1L,
                runGen = 3L,
                gen = 3L,
            )
            assertNotNull("本实例的 ${st::class.simpleName} ⇒ 必须结算（否则收尾分支全废）", plan)
        }
    }

    /**
     * 世代过期 ⇒ 一律不结算，**哪怕归属仍是本实例**。
     *
     * 这是 BL-038 的本体：旧 run 的退出（含 137 之类的退出码）迟到到新 run 已接管之后。
     * 归属校验拦不住它（同实例），只有世代号能拦。
     *
     * ★ 这条也是本轮"顺手多修一处"的钉子：修复前 `staleGen` 自己是**锁外**读 `runGen`，
     *   而 `++runGen` 发生在 `startLocked` 的监视器里 —— 它能读到"新 run 还没走到 ++runGen"的
     *   旧值。现在世代比较与判决在同一个临界区里，这条用例钉的是判决本身。
     */
    @Test
    fun staleGenerationIsNeverSettled() {
        for (st in ownStates()) {
            assertNull(
                "gen=6 而 runGen=7 ⇒ 旧世代的 ${st::class.simpleName} 必须返回 null",
                planExitSettlement(
                    current = st,
                    instanceId = inst,
                    stopRequestedFor = inst, // 就算它声称"是我们要它停的"也不行
                    nodePid = 1L,
                    runGen = 7L,
                    gen = 6L,
                )
            )
        }
    }

    /**
     * `wasStopRequested` 只在 `stopRequestedFor` **恰好等于本实例** 时为 true。
     *
     * 三种取值的取舍不同：
     * - `== 本实例` ⇒ true（`stop()` 要它停的，走"预期停止"分支落 Idle，不报崩溃）
     * - `== 别的实例` ⇒ false（别人让我停，跟我这次退出无关）
     * - `null` ⇒ false（没有在途停止请求 ⇒ 这次退出是意外/正常结束）
     *
     * 判错任一格的用户可见后果：把一次用户主动停止报成"意外退出"，或反过来把崩溃静默成"已停止"。
     */
    @Test
    fun wasStopRequestedTrueOnlyForThisInstance() {
        fun planFor(sr: String?) = planExitSettlement(
            current = DshRuntime.State.Running(inst, "n-$inst", null, 3080),
            instanceId = inst,
            stopRequestedFor = sr,
            nodePid = 1L,
            runGen = 1L,
            gen = 1L,
        )
        assertEquals("stopRequestedFor 是本实例 ⇒ true", true, planFor(inst)?.wasStopRequested)
        assertEquals("stopRequestedFor 是别的实例 ⇒ false", false, planFor(other)?.wasStopRequested)
        assertEquals("stopRequestedFor 为 null ⇒ false", false, planFor(null)?.wasStopRequested)
    }

    /**
     * `exitedNodePid` 必须**原样带出**结算那一刻读到的 `nodePid`。
     *
     * 它决定锁外那次 `deleteNodePidFile(instanceId, exitedNodePid)` 删不删（内部是
     * [com.dsh.core.pidFileBelongsTo] 的 compare-and-delete）。带错值的方向：
     * 带成新 run 的 nodePid ⇒ CAS 恰好通过 ⇒ 删掉新 run 的 node pid 文件 ⇒ 新进程失联。
     */
    @Test
    fun exitedNodePidIsPassedThroughUnchanged() {
        val plan = planExitSettlement(
            current = DshRuntime.State.Running(inst, "n-$inst", null, 3080),
            instanceId = inst,
            stopRequestedFor = null,
            nodePid = 1_234_567L,
            runGen = 2L,
            gen = 2L,
        )
        assertNotNull("前置：本实例的 Running 必须结算", plan)
        assertEquals("exitedNodePid 必须原样带出（它给锁外的 compare-and-delete 用）", 1_234_567L, plan!!.exitedNodePid)
    }

    /**
     * `settle.state` 必须**就是**锁内重读到的那一个实例（引用相等），不是拷贝。
     *
     * 为什么用 `assertSame` 而不是 `assertEquals`：下游四个分支（`Failed` / `Exited` /
     * `Starting` / 其它）判的是状态**种类**，调用点必须拿锁内值。若实现改成 `current.copy(...)`
     * 或"重读一次再比"，`assertEquals` 照样绿，但"分支用的是哪一份"这个契约就断了 ——
     * `Starting` 那条分支会走到 `retryIfSeccompLooksGuilty`，拿旧快照判"该重试"会把一次
     * 用户主动停止当成启动失败再多起一个进程。
     */
    @Test
    fun stateIsTheVeryInLockInstanceNotACopy() {
        for (st in ownStates()) {
            val plan = planExitSettlement(
                current = st,
                instanceId = inst,
                stopRequestedFor = inst,
                nodePid = 0L,
                runGen = 5L,
                gen = 5L,
            )
            assertNotNull("前置：本实例的 ${st::class.simpleName} 必须结算", plan)
            assertSame(
                "settle.state 必须是入参那一个引用（下游四个分支要用它，不许是拷贝/旧快照）",
                st,
                plan!!.state,
            )
        }
    }

    // =========================================================================
    // ② D-1 的真实行为（在 JVM 上真的跑 stopAndWait）
    // =========================================================================

    /**
     * D-1 的等价性之一：**没在跑**时 `stopAndWait` 仍返回 `true`、仍把状态复位成 `Idle`。
     *
     * 旧实现靠"快路径"自己做这两件事；修复后删掉了快路径，让控制流落进 `stop()` 的
     * `instanceId == null` 分支（`@Synchronized`，锁内做同一件事）。所以这条用例在**修复前后都绿**
     * —— 它的作用是钉"删掉快路径没有把返回值/复位语义弄丢"，属于等价性证明，不是缺陷判据。
     *
     * 用 `Failed(带 reason)` 而不是 `Idle` 作为起点，是有意的：这是快路径里最可疑的一格 ——
     * `:2058` 无条件写 `Idle` 会把一个**带原因**的 `Failed` 连同原因一起抹掉。修复后这个行为
     * 保留下来，但搬进了 `stop()`，且 `stop()` 的 KDoc 明确写了"对 Idle/Failed/Exited 的置 Idle
     * 语义不变"—— 从"没人写明的副作用"变成"有意的、有注释的复位"。
     */
    @Test
    fun emptyRuntimeStillReportsTrueAndResetsToIdle() {
        setState(DshRuntime.State.Failed(inst, "n-$inst", "上次启动失败：端口被占"))
        val ok = runBlocking { DshRuntime.stopAndWait("r36/test", timeoutMs = 1_000) }
        assertTrue("没在跑时 stopAndWait 必须返回 true（否则 start() 会走 13s 超时兜底）", ok)
        assertEquals(
            "必须复位成 Idle（带 reason 的 Failed 被有意复位 —— 语义在 stop() 的 KDoc 里写明）",
            DshRuntime.State.Idle,
            DshRuntime.currentState(),
        )
        assertNull("_running 也要一并清空", runningFlow().value)
    }

    /**
     * D-1 的等价性之二（**这条是真正的判别器**）：删掉快路径后，`stopAndWait` 在"没在跑"时
     * 会落到 `stop()`，而 `stop()` 的 null 分支比旧快路径**多做一件事** —— 收掉上一轮
     * "停止失败时保留下来"的 leftover 句柄（Round24 N-2）。
     *
     * 旧快路径是 `return true` 直接返回，**从不**碰 leftover 句柄 ⇒ 这条用例在修复前必红
     * （`terminate` 一次都不会被调用）。它不是源码形状断言，是真的在跑生产代码。
     *
     * 为什么这件事重要：`stop()` 里"失败后保留句柄以便重试"那句承诺，在状态已落 Idle/Failed
     * 之后唯一还有机会兑现的入口就是这里。旧快路径把那个机会吃掉了 —— 于是"保留句柄"变成
     * 一句空话，留下的 proot 没有任何入口能再拿到它。
     *
     * 时序说明：收尾是 `scope.launch` 出去的（不阻塞 `stopAndWait` 的返回），所以这里用
     * `CountDownLatch` 等 fake 的 `terminate` 被调用，最多等 10s（正常是毫秒级）。
     */
    @Test
    fun emptyRuntimeStillCleansUpLeftoverHandleThroughStop() {
        setState(DshRuntime.State.Idle)
        val called = CountDownLatch(1)
        setHandle(FakeHandle(pid = 987_654_321L, terminateLatch = called))
        try {
            val ok = runBlocking { DshRuntime.stopAndWait("r36/test", timeoutMs = 1_000) }
            assertTrue("没在跑时 stopAndWait 仍返回 true", ok)
            assertTrue(
                "stopAndWait 必须落到 stop() 的 null 分支：它收掉 leftover 句柄。" +
                    "旧快路径直接 return true，永远不会调用 terminate —— 这条用例就是 D-1 的判别器",
                called.await(10, TimeUnit.SECONDS),
            )
        } finally {
            setHandle(null)
        }
    }

    /**
     * D-1 的等价性之三：`Idle` 起点 + 没有 leftover 句柄时，`stopAndWait` 不得产生任何日志噪音。
     *
     * 修复把"没在跑"这条路径换成了 `stop()`，而 `stop()` 里有两条会写字的分支：
     * - `Stopping` 早退（会打 `stop ignored: …`）—— 不能被这里误命中；
     * - `leftover != null`（会打 terminate 诊断）—— 这里没有句柄，不该打。
     *
     * 判据：调用后该实例的日志里**没有** `stop ignored` / `retry terminate of leftover`。
     * （不去断言"日志为空"：别的用例可能往同一个实例流里写过东西，那是跨用例污染，不是缺陷。）
     */
    @Test
    fun emptyRuntimeDoesNotLogASpuriousStopIgnored() {
        setState(DshRuntime.State.Idle)
        setHandle(null)
        runBlocking { DshRuntime.stopAndWait("r36/test", timeoutMs = 1_000) }
        val log = com.dsh.core.DshLogBus.export(inst)
        assertFalse(
            "Idle 起点不该命中 stop() 的 Stopping 早退分支（activeInstanceId()==null ⇒ 状态不是 Stopping）",
            log.contains("stop ignored")
        )
        assertFalse(
            "没有 leftover 句柄时不该打 terminate 诊断",
            log.contains("retry terminate of leftover")
        )
    }

    // =========================================================================
    // ③ D-1 的接线形状（源码级；靠变异验证自证判别力）
    // =========================================================================

    /**
     * 判据 2：`stopAndWait` 体内**不许再有**裸写 `_state.value = State.Idle` / `_running.value = null`。
     *
     * 为什么这一条必须存在：D-2 那种"锁内重读"的修法可以靠纯函数单测钉住决策，但 D-1 的修法是
     * **删代码** —— 删掉的东西没法被断言"行为不同"（旧快路径与 `stop()` 的 null 分支在单线程下
     * 逐字等价）。唯一能钉住"它没被加回来"的就是形状断言。
     *
     * ★ 断言前先剥注释：修复后的 KDoc 里**引用**了这两行原文（"删掉的那段写的是 …"），
     *   不剥注释的话这条用例会被自己的文档判红。剥注释同时保证了判别力：若有人把真正的写加回
     *   代码里（哪怕配一句"只是临时"的注释），剥完注释它仍在。
     */
    @Test
    fun stopAndWaitNoLongerWritesIdleOutsideTheLock() {
        val code = stripComments(stopAndWaitBody())
        assertFalse(
            "stopAndWait 体内不许再出现 `_state.value = State.Idle` —— 那是 D-1 的锁外裸写本体",
            code.contains("_state.value = State.Idle")
        )
        assertFalse(
            "stopAndWait 体内不许再出现 `_running.value = null` —— 与上一条是同一段锁外写",
            code.contains("_running.value = null")
        )
        assertFalse(
            "「本来就没在跑」的 if 分支必须整段删掉（不是加锁、不是加判据）",
            code.contains("if (instanceId == null)")
        )
    }

    /**
     * 判据 3：`stop(reason)` 必须出现在 `activeInstanceId()` **之后**。
     *
     * 顺序就是语义（先判后落）：把 `stop()` 提到判据之前，"同实例还在 Stopping 就先等到位"
     * 这条 BL-036 的行为会变（`stop()` 对 Stopping 是 no-op，但判据用不上了 —— 等待循环的
     * instanceId 会变成"当前占用者"，与调用方要求等的实例不再是同一个前提）。
     */
    @Test
    fun stopAndWaitCallsStopAfterReadingTheOwner() {
        val code = stripComments(stopAndWaitBody())
        val owner = code.indexOf("activeInstanceId()")
        val stopCall = code.indexOf("stop(reason)")
        assertTrue("stopAndWait 体内必须仍读 activeInstanceId()", owner >= 0)
        assertTrue("stopAndWait 体内必须仍调 stop(reason)", stopCall >= 0)
        assertTrue(
            "顺序必须是「先读占用者 → 再 stop(reason)」，顺序反了 BL-036 的判据就失效",
            owner < stopCall
        )
    }

    // =========================================================================
    // ④ D-2 的接线形状（源码级）
    // =========================================================================

    /**
     * 判据 4：三连写（`handle = null` / `nodePid = 0` / `_running.value = null`）必须**全部**
     * 落在同一个 `synchronized(this)` 块内，且该块之外一处都不许有。
     *
     * 后半句（"块外一处都不许有"）是这条用例真正的力气所在：只判"块内有"的话，把三行**复制**一份
     * 到块外也能绿。D-2 的缺陷形态恰恰是"锁外无条件写"。
     */
    @Test
    fun handleTripleWriteLivesInsideTheLock() {
        val body = onProcessExitBody()
        val lock = settlementLockOf(body)
        assertTrue("锁内必须清 handle（全局句柄，D-2 危害最大的那个字段）", lock.contains("handle = null"))
        assertTrue("锁内必须清内存里的 node pid", lock.contains("nodePid = 0"))
        assertTrue("锁内必须清 _running（配对写，别留半写中间态）", lock.contains("_running.value = null"))
        // 块外不许有第二份写：切片 = 整个 onProcessExit 方法体去掉锁块
        val open = body.indexOf('{', body.indexOf(SETTLEMENT_HEADER))
        val close = endOfBlock(body, open)
        val outside = body.substring(0, open) + body.substring(close + 1)
        assertFalse(
            "synchronized 块**之外**不许再写 handle —— 那正是 D-2 的原始缺陷写法（锁外无条件清句柄）",
            stripComments(outside).contains("handle = null")
        )
    }

    /**
     * 判据 5：锁内判据必须是**重读**的 `_state.value`，不许传锁外快照。
     *
     * 这是 D-2 与 RE-02（round35）完全同款的形态：修好了纯函数、调用点却仍传旧快照 ⇒ 缺陷原样。
     * 变异验证：把 `current = _state.value` 改回 `current = earlyCur` 或 `current = cur`，
     * 这条会精确变红（报告里贴了红/绿记录）。
     *
     * 同时钉 `runGen = runGen`：修复前 `staleGen` 自己是锁外读 —— 世代判据本身就不是第二道防线。
     * 它必须和判决进同一个临界区，否则下一轮红队会再提一次"这仍是锁外读"。
     */
    @Test
    fun settlementDecisionReReadsStateInsideTheLock() {
        val lock = settlementLockOf(onProcessExitBody())
        assertTrue("锁内必须出现 planExitSettlement(", lock.contains("planExitSettlement("))
        assertTrue(
            "判据必须用锁内重读的 _state.value",
            lock.contains("current = _state.value")
        )
        assertFalse(
            "不许把锁外快照 earlyCur 传进判决（那样等于没修）",
            lock.contains("current = earlyCur")
        )
        assertFalse(
            "不许把锁外快照 cur 传进判决",
            lock.contains("current = cur")
        )
        assertTrue(
            "世代号必须在锁内读（runGen = runGen），否则 staleGen 仍是锁外读",
            lock.contains("runGen = runGen")
        )
        assertTrue(
            "stopRequestedFor 与 nodePid 也必须在锁内读（作为参数传进纯函数）",
            lock.contains("stopRequestedFor = stopRequestedFor") && lock.contains("nodePid = nodePid")
        )
    }

    /**
     * 判据 6：锁内**不许有副作用**（文件 IO、日志、上报、收服务、起协程、日志缓冲读取、重试）。
     *
     * 为什么单列一条：round35 已实测过一次"把 IO 拖进监视器"的代价（4069 µs）。D-2 的临界区与
     * `startLocked` 共用同一个 monitor，而那把锁里要做 `mkdirs` / `preflight` / 端口探测 /
     * `ProcessBuilder.start()` —— 把 IO 放进去，退出回调会被拖到毫秒级以上，`stopAndWait`
     * 跟着多等，任务区终态晚一拍。
     */
    @Test
    fun sideEffectsStayOutsideTheSettlementLock() {
        val lock = settlementLockOf(onProcessExitBody())
        val forbidden = listOf(
            "ProcessUtil.removePidFile",
            "ProcessUtil.writePidFile",
            "deleteNodePidFile",
            "DshLogBus.appendFor",
            "DshLogBus.append(",
            "reportRuntime",
            "stopServiceIfIdle",
            "scope.launch",
            "recentLogTail",
            "retryIfSeccompLooksGuilty",
            "setStateIfOwned",
        )
        for (marker in forbidden) {
            assertFalse("锁内不许有 $marker（副作用一律留锁外）", lock.contains(marker))
        }
    }

    /**
     * 判据 7：下游四个分支必须改用锁内重读值 —— 调用点要出现 `val cur = settle.state`。
     *
     * 为什么不只是洁癖：`cur is State.Starting` 那条会走到 `retryIfSeccompLooksGuilty`，
     * 拿旧快照判"是 Starting"而实际已是别的状态，会把一次用户主动停止当成启动失败再起一次进程。
     * 另外 `settle == null`（锁内发现已易主）必须有一条**不写任何字段**的出口 ——
     * 它只能做 compare-and-delete 的 pid 文件清理，与 staleGen 早退分支同款。
     */
    @Test
    fun downstreamBranchesUseTheSettledState() {
        val body = onProcessExitBody()
        val code = stripComments(body)
        assertTrue(
            "下游分支必须用锁内重读值：调用点要有 `val cur = settle.state`",
            code.contains("val cur = settle.state")
        )
        assertTrue(
            "锁内判决失败（已易主）时必须有 `if (settle == null)` 出口",
            code.contains("if (settle == null)")
        )
        assertTrue(
            "该出口必须**一个字都不写**地返回：它只能做 compare-and-delete 的 pid 文件清理",
            code.contains("exit settlement skipped")
        )
        // 且不许在这个出口里写状态/句柄
        val nullBranchStart = code.indexOf("if (settle == null)")
        val nullBranchOpen = code.indexOf('{', nullBranchStart)
        val nullBranch = code.substring(nullBranchOpen, endOfBlock(code, nullBranchOpen) + 1)
        assertFalse("settle==null 的出口不许写 handle", nullBranch.contains("handle ="))
        assertFalse("settle==null 的出口不许写 nodePid", nullBranch.contains("nodePid ="))
        assertFalse("settle==null 的出口不许写 _running", nullBranch.contains("_running.value ="))
        assertFalse("settle==null 的出口不许写 _state", nullBranch.contains("_state.value ="))
    }

    // =========================================================================
    // ⑤ 负向：不许引入新的并发惯用法
    // =========================================================================

    /**
     * 判据 8：本文件原本只用两种并发惯用法（monitor / `@Volatile`）。修 D-1 的候选方案 B 曾考虑
     * `MutableStateFlow.compareAndSet`，被否掉的理由之一就是"引入第三种惯用法，后来者判断不了
     * 这段到底靠什么保证"。这条用例把"没引入"钉住：一旦有人加了 CAS，它红。
     *
     * 判别力说明：它只防"多出一种惯用法"，不证明 monitor 用得对 —— 别把它过度解读。
     */
    @Test
    fun noThirdConcurrencyIdiomWasIntroduced() {
        val text = mainSourceFile().readText()
        val count = Regex("compareAndSet").findAll(stripComments(text)).count()
        assertEquals("DshRuntime.kt 里不许出现 compareAndSet（monitor / @Volatile 之外不引入第三种惯用法）", 0, count)
    }

    // =========================================================================
    // 夹具：状态/句柄注入（用完必须还原，DshRuntime 是进程级单例）
    // =========================================================================

    @After
    fun restoreSingleton() {
        runCatching { setHandle(null) }
        runCatching { setState(DshRuntime.State.Idle) }
        runCatching { runningFlow().value = null }
    }

    /** 假句柄：不碰任何真实进程，只用来证明 `stop()` 的 leftover 分支被走到了。 */
    private class FakeHandle(
        override val pid: Long,
        private val terminateLatch: CountDownLatch,
    ) : DshRuntime.Handle {
        override fun isAlive(): Boolean = false
        override suspend fun terminate(graceMillis: Long) {
            terminateLatch.countDown()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateFlow() =
        DshRuntime::class.java.getDeclaredField("_state").let {
            it.isAccessible = true
            it.get(DshRuntime) as kotlinx.coroutines.flow.MutableStateFlow<DshRuntime.State>
        }

    @Suppress("UNCHECKED_CAST")
    private fun runningFlow() =
        DshRuntime::class.java.getDeclaredField("_running").let {
            it.isAccessible = true
            it.get(DshRuntime) as kotlinx.coroutines.flow.MutableStateFlow<DshRuntime.Running?>
        }

    private fun setState(st: DshRuntime.State) {
        stateFlow().value = st
    }

    private fun setHandle(h: DshRuntime.Handle?) {
        val f = DshRuntime::class.java.getDeclaredField("handle")
        f.isAccessible = true
        f.set(DshRuntime, h)
    }

    // =========================================================================
    // 源码定位 / 切片工具（与 R35RunningRefreshTest、R33LoadUrlGateWiringTest 同惯例）
    // =========================================================================

    private companion object {
        /** D-2 结算临界区的头（一字不差，变了就等于有人挪过这段） */
        const val SETTLEMENT_HEADER = "val settle = synchronized(this) {"
    }

    private fun mainSourceFile(): File {
        val rel = "core/DshRuntime.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/java/com/dsh/$rel"),
                File(dir, "FCL/src/main/java/com/dsh/$rel"),
                File(dir, "src/main/java/com/dsh/$rel")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("找不到 $rel（请在仓库根或 FCL/ 目录下运行测试）")
    }

    private fun methodBody(signature: String): String {
        val text = mainSourceFile().readText()
        val start = text.indexOf(signature)
        if (start < 0) throw AssertionError("找不到 `$signature` —— 方法被改名或删了？")
        val open = text.indexOf('{', start)
        if (open < 0) throw AssertionError("`$signature` 没有方法体？")
        return text.substring(open, endOfBlock(text, open) + 1)
    }

    private fun stopAndWaitBody(): String = methodBody("suspend fun stopAndWait(")

    private fun onProcessExitBody(): String = methodBody("private fun onProcessExit(")

    private fun settlementLockOf(body: String): String {
        val start = body.indexOf(SETTLEMENT_HEADER)
        if (start < 0) {
            throw AssertionError(
                "找不到 D-2 的结算临界区头 `$SETTLEMENT_HEADER` —— " +
                    "判决与三次赋值必须仍在同一个 synchronized(this) 里"
            )
        }
        val open = body.indexOf('{', start)
        return body.substring(open, endOfBlock(body, open) + 1)
    }

    /** 剥掉行注释与块注释（只用于"不许含"类断言，避免修复文档自己把自己判红）。 */
    private fun stripComments(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        while (i < code.length) {
            when {
                code.startsWith("//", i) -> while (i < code.length && code[i] != '\n') i++
                code.startsWith("/*", i) -> {
                    val e = code.indexOf("*/", i + 2)
                    i = if (e < 0) code.length else e + 2
                }
                else -> {
                    out.append(code[i]); i++
                }
            }
        }
        return out.toString()
    }

    /** 从 [openBrace] 处的 `{` 开始，按花括号深度找配对的 `}` 的下标。 */
    private fun endOfBlock(text: String, openBrace: Int): Int {
        if (openBrace < 0 || text.getOrNull(openBrace) != '{') {
            throw AssertionError("openBrace 处不是 '{'（下标=$openBrace）")
        }
        var depth = 0
        var i = openBrace
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        throw AssertionError("花括号没有闭合 —— 解析失败")
    }
}
