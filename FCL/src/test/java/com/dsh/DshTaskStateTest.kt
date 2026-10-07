package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.DshRuntime
import com.dsh.core.DshTask
import com.dsh.core.DshTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 任务模型「状态维度」的 JVM 单元测试（不需要真机/Android）。
 *
 * 覆盖的是本轮新增的那部分契约，也就是"错了会静默出问题"的地方：
 * - [DshTasks.report] 把**终态**放进 [DshTasks.finished]，且**不动** [DshTasks.tasks]（进行中投影）；
 * - 终态列表的**上限与顺序**（环形、最新在前）—— 无限增长会让首页任务区越跑越卡；
 * - **传 RUNNING 进来**的行为（被拒绝，且不抛异常）—— 这是"上报入口被用错"的唯一防线；
 * - 三种终态各自保留、`error` 字段不丢；
 * - [DshTask] 新增字段**全部有默认值**：只给旧字段也能构造（向后兼容是这次能增量改的前提）；
 * - [DshRuntime.stopIf] 对**不属于当前实例**的调用返回 false 且**什么都不做**（治"点 A 的停止停掉了 B"）。
 *
 * ★ 为什么这里要显式清 [DshTasks.finished]：它是进程内单例，测试之间共享同一份列表，
 *   而"上限裁掉最老的"这类断言要求从一个确定的起点开始（不清的话，上一条测试留下的行会把
 *   新灌的行提前挤出去，断言随执行顺序时过时不过）。同 [DshLogBusRoutingTest] 里清全局缓冲同一个道理。
 *
 * ★ 本类**不**调 `DshTasks.start(...)`：那需要一个 Application Context 才能取到安装器实例，
 *   而 `start` 只影响"进行中"那条投影（[DshTasks.tasks]），本次要验的是终态入口与它无关。
 *   也正因为没起聚合协程，[DshTasks.tasks] 会一直保持初始的空列表 —— 那正好用来证明
 *   "report 不会往进行中列表里塞东西"。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.DshTaskStateTest"
 */
class DshTaskStateTest {

    @Before
    fun clearFinished() {
        DshTasks.clearFinishedForTest()
    }

    /** 造一条终态任务（只有 id/state/error 是每条用例真正关心的，其余填结构性内容） */
    private fun finished(
        id: String,
        state: DshTask.State,
        error: String? = null
    ): DshTask = DshTask(
        id = id,
        kind = DshTask.Kind.INSTALL,
        title = "task-$id",
        stage = "stage-$id",
        action = DshTask.Action.NONE,
        state = state,
        instanceId = "inst-$id",
        error = error
    )

    // --- 1. report 进 finished，不动 tasks ---------------------------------

    @Test
    fun reportPutsTerminalTaskIntoFinished() {
        DshTasks.report(finished("a", DshTask.State.DONE))

        val list = DshTasks.finished.value
        assertEquals("终态上报应当落进 finished 列表", 1, list.size)
        assertEquals("a", list[0].id)
        assertEquals(DshTask.State.DONE, list[0].state)
        assertEquals("inst-a", list[0].instanceId)
    }

    /**
     * [DshTasks.tasks] 是各模块 StateFlow 的**只读投影**，[DshTasks.report] 绝不能往里塞东西 ——
     * 否则"已结束的任务"会同时出现在"进行中"里，界面会显示成它还在跑。
     *
     * 用引用相等断言：投影没被重算过，就应当还是同一个列表对象（这也顺带证明 report 没有触发重算）。
     */
    @Test
    fun reportDoesNotTouchRunningProjection() {
        val before = DshTasks.tasks.value
        assertEquals(0, before.size)

        DshTasks.report(finished("a", DshTask.State.DONE))
        DshTasks.report(finished("b", DshTask.State.FAILED, error = "x"))

        assertSame("report 不该替换（重算）进行中的投影", before, DshTasks.tasks.value)
        assertEquals("进行中列表必须保持为空", 0, DshTasks.tasks.value.size)
    }

    // --- 2. 上限与顺序 ------------------------------------------------------

    @Test
    fun finishedIsNewestFirst() {
        DshTasks.report(finished("first", DshTask.State.DONE))
        DshTasks.report(finished("second", DshTask.State.DONE))

        val list = DshTasks.finished.value
        assertEquals("second", list[0].id)
        assertEquals("first", list[1].id)
    }

    /**
     * 上限：超出 [DshTasks.MAX_FINISHED] 后**裁掉最老的**（保留最近的若干条）。
     *
     * 为什么这条必须有：终态列表是给首页任务区渲染的，没有上限就意味着"用得越久、列表越长、
     * 每次刷新要画的行越多"，而其中绝大多数是几天前的旧事。
     */
    @Test
    fun finishedKeepsOnlyMostRecentWithinCap() {
        val total = DshTasks.MAX_FINISHED + 5
        repeat(total) { i -> DshTasks.report(finished("t$i", DshTask.State.DONE)) }

        val list = DshTasks.finished.value
        assertEquals("超出上限后应当裁到上限条数", DshTasks.MAX_FINISHED, list.size)
        assertEquals("最新的应当仍在最前", "t${total - 1}", list[0].id)
        assertFalse("最老的应当被裁掉", list.any { it.id == "t0" })
        assertFalse("被裁掉的那几条都不该还在", list.any { it.id == "t4" })
        assertTrue("正好在上限内的那条应当保留", list.any { it.id == "t5" })
    }

    // --- 3. 三种终态 + error 字段 -------------------------------------------

    @Test
    fun allThreeTerminalStatesAreKept() {
        DshTasks.report(finished("d", DshTask.State.DONE))
        DshTasks.report(finished("f", DshTask.State.FAILED, error = "boom"))
        DshTasks.report(finished("c", DshTask.State.CANCELLED))

        val list = DshTasks.finished.value
        assertEquals(3, list.size)
        // 最快看出来的顺序是"最新的在前"，所以倒序：c(取消) → f(失败) → d(完成)
        assertEquals(DshTask.State.CANCELLED, list[0].state)
        assertEquals(DshTask.State.FAILED, list[1].state)
        assertEquals(DshTask.State.DONE, list[2].state)
        assertEquals("失败原因不能在上报过程中丢掉", "boom", list[1].error)
        assertNull("没有失败原因时不该凭空造一个", list[0].error)
    }

    /** 结束时刻由聚合器兜底补上：调用点没填，也不该让界面拿到 null */
    @Test
    fun finishedAtIsFilledByAggregator() {
        DshTasks.report(finished("a", DshTask.State.DONE))
        assertNotNull("聚合器应当补齐结束时刻", DshTasks.finished.value[0].finishedAt)
    }

    /** 调用点自己填了结束时刻就尊重它（例如安装协程里更精确的时刻），不要被覆盖成"上报那一刻" */
    @Test
    fun explicitFinishedAtIsPreserved() {
        val explicit = 1234567890L
        DshTasks.report(finished("a", DshTask.State.DONE).copy(finishedAt = explicit))
        assertEquals(explicit, DshTasks.finished.value[0].finishedAt)
    }

    // --- 4. report(RUNNING) 的行为（钉住选定的方案） -------------------------

    /**
     * 传"进行中"进来是**编程错误**：它既不是终态，也不该出现在终态列表里。
     *
     * 本实现选的是"**拒绝这条脏数据 + 落一行日志 + 正常返回**"，而不是 `require` 抛异常。
     * 理由见 [DshTasks.report] 的注释：调用点大多在协程 `finally` / 进程退出回调 / 用户点取消
     * 这三类路径上，在那里抛异常会把"上报写错"升级成"取消没生效、资源没人清理"的二次故障。
     * 这条用例把该行为钉住 —— 将来谁想改成抛异常，必须同时改这里（也就是必须显式做一次决定）。
     */
    @Test
    fun reportRunningIsIgnoredWithoutThrowing() {
        DshLogBus.clear()
        DshLogBus.flushNow()

        try {
            DshTasks.report(finished("r", DshTask.State.RUNNING))
        } catch (t: Throwable) {
            fail("上报非终态不该抛异常（会让取消/收尾路径二次故障）：$t")
        }

        assertEquals("进行中的任务不能进终态列表", 0, DshTasks.finished.value.size)
        // 但必须留下可查的痕迹，否则"上报写错了"会完全无声
        DshLogBus.flushNow()
        assertTrue(
            "应当落一行可查的日志",
            DshLogBus.snapshot.value.lines.any { it.contains("忽略非终态") }
        )
    }

    // --- 5. 向后兼容：只给旧字段也能构造 -------------------------------------

    /**
     * 新增字段**全部带默认值**：老的构造写法必须原样编得过、且默认成"进行中"。
     *
     * 这是本轮能增量改造的前提 —— 现有构造点大多在造"进行中"的任务，不该被迫逐个改。
     * 位置参数（不写参数名）一起验：默认值是加在参数表**末尾**的，位置参数的前 7 个必须没变。
     */
    @Test
    fun legacyConstructorUsesDefaults() {
        val positional = DshTask(
            "x", DshTask.Kind.BOOTSTRAP, "title", "stage", null, null, DshTask.Action.CANCEL
        )
        assertEquals(DshTask.State.RUNNING, positional.state)
        assertNull(positional.instanceId)
        assertNull(positional.error)
        assertEquals(0L, positional.startedAt)
        assertNull(positional.finishedAt)
        assertFalse("默认是进行中，不算已结束", positional.isFinished)

        // 具名参数的"老写法"（只给原来那 7 个字段）同样应当编得过
        val named = DshTask(
            id = "bootstrap",
            kind = DshTask.Kind.BOOTSTRAP,
            title = "",
            stage = "Preparing runtime",
            action = DshTask.Action.NONE
        )
        assertEquals(DshTask.State.RUNNING, named.state)
        assertFalse(named.isFinished)
    }

    // --- 6. 任务 id 的拼法 --------------------------------------------------

    /**
     * 投影与终态上报必须**用同一个 id**，否则界面上的"删除中"和"已删除"会被当成两条不同的任务。
     * 这里把三个 id 拼法钉住（它们现在是唯一的拼接点，界面/调用方都用它们）。
     */
    @Test
    fun taskIdHelpersAreStableAndDistinct() {
        assertEquals("install:i1", DshTasks.installTaskId("i1"))
        assertEquals("runtime:i1", DshTasks.runtimeTaskId("i1"))
        assertEquals("delete:i1", DshTasks.deleteTaskId("i1"))
        // 三种任务在同一实例上不能撞 id（撞了会互相覆盖/去重成一条）
        assertFalse(DshTasks.installTaskId("i1") == DshTasks.runtimeTaskId("i1"))
        assertFalse(DshTasks.runtimeTaskId("i1") == DshTasks.deleteTaskId("i1"))
    }

    // --- 7. stopIf：不属于当前实例时什么都不做 ------------------------------

    /**
     * N5 的核心断言：**当前跑的不是它 → 返回 false，且不改任何状态**。
     *
     * ★ 能在 JVM 上测的部分与不能测的部分（先说实话，免得这条用例给人"覆盖全了"的错觉）：
     *   - **能测**：在没有实例运行（`State.Idle`）时调 `stopIf("别的实例")`，必须是"什么都不做"。
     *     这条路径不碰任何 Android API（[DshRuntime.stopIf] 的判据只看内存里的状态流），
     *     所以能真的跑起来，而且它断言的正是当年踩坑的那种输入（点到了**不是当前实例**的那一行）。
     *   - **测不了**：真的起一个实例再验证"停在跑的那个"——那要 proot、rootfs、Context、
     *     前台服务，只能上真机。这里**不**为了让它可测去改 [DshRuntime] 的结构（那会把
     *     真机上已经定稿的进程管理路径重新打开，代价远大于收益）。
     */
    @Test
    fun stopIfDoesNothingWhenAnotherInstanceIsRunning() {
        // 前置：没有任何实例在跑（测试进程里不可能有）
        assertNull("测试进程里不应有实例在运行", DshRuntime.runningInstanceId())
        assertEquals(DshRuntime.State.Idle, DshRuntime.currentState())

        val handled = DshRuntime.stopIf("inst-not-running", "测试：不该动手")

        assertFalse("当前不是它 → 必须返回 false", handled)
        assertEquals("状态不该被改动", DshRuntime.State.Idle, DshRuntime.currentState())
        assertNull("仍然没有实例在运行", DshRuntime.runningInstanceId())
        // 副作用的另一面：既然什么都没做，就不该凭空报出一条"已停止"的终态任务
        assertEquals("什么都没做就不该产生终态任务", 0, DshTasks.finished.value.size)
    }
}
