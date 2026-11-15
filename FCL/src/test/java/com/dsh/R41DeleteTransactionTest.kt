package com.dsh

import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
import com.dsh.core.DshTask
import com.dsh.core.DshTasks
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/**
 * **SP-04 的证伪条件**：`delete()` 必须保证「一个 id 一个事务」这个不变量。
 *
 * ## 缺陷本体（HEAD `790b35f`）
 * 闸门（旧 `:1069`）只看 `pendingDeletes`，而 `pendingDeletes` 的语义是"磁盘上还没落这一笔" ——
 * 它在 `commitPendingDelete`（清单落盘成功那一刻）就把 id 摘掉了。真正耗时的收尾
 * （`deleteRecursively`、删实例日志、`_deleting` 清理、终态上报）全在**提交之后**，那段没有保护：
 *
 * 1. 第二个 `delete(id)` 能长驱直入 → 两条事务抢同一棵目录树、各上报一条 `delete:<id>` 的 DONE 行；
 * 2. 两条事务共用同一个 `_deleting` 元素，先收尾的那个会在终态上报前把它摘掉，
 *    替还没跑完的另一条宣布"删除结束"；
 * 3. `byId(id) == null`（删不在清单里的 id）时根本不进 `pendingDeletes`，闸门对该 id **恒为假**
 *    ⇒ 可无限次进入，每次都走完全流程再留一条幻影 DONE 行。
 *
 * ## 可达性（别把严重性说大）
 * **极低**。两个 UI 入口都在主线程的模态确认框之后：Android 输入派发是主线程串行的，
 * 第一次 `ACTION_UP → performClick → onClick → dismiss()` 在同一条消息里跑完、窗口同步拆除，
 * 第二次点按打不到已经摘掉的按钮；`delete()` 又已同步把实例摘出 `_instances`，卡片当帧消失。
 * ⇒ 今天**用户基本触发不到**。价值在于补上核心不变量：任何非模态入口（通知栏动作、快捷方式、
 * 自动化调用、将来的批量删除）会立刻踩中。顺带用 `finally` 堵掉一个**既有**的窄风险 ——
 * 事务体里任一处未被 `runCatching` 包住的调用抛出，`_deleting` 就永久残留（卡片永远"删除中"）。
 *
 * ## 用例与它钉住的东西
 * - [t1_secondDeleteInsideTheCommitWindowIsRejected] —— T1（**主证伪用例**）：在 `onFinished` 回调内部
 *   （提交已完成、收尾未完成）发起第二次 `delete(id)`，必须被拒；任务区只有一条删除终态行。
 * - [t1b_secondDeleteBeforeCommitIsAlsoRejected] —— T1b：提交**之前**的重复调用同样被拒
 *   （钉既有契约 fix-r31-104，**HEAD 上就绿**，不是本缺陷的证伪用例）。
 * - [t2_ghostIdDeleteIsAlsoGuarded] —— T2：`byId == null` 的 ghost id 也被闸门挡住
 *   （**HEAD 上红**：旧实现可无限次进入，这是最小、零 flaky 的证伪用例）。
 * - [t3_gateIsReleasedWhenTheTransactionThrows] —— T3：异常路径下闸门必须释放（不能永久卡住），
 *   同时 `_deleting` 也必须被兜底清掉。用 `dirFsync` seam 注入一个"写完就抛"的替身，
 *   走的是**真实生产路径**（`writeNow → writeManifest → dirFsync`），不靠调度运气。
 * - [t4_transactionGateIsInsideAffinallyBlock] —— T4：源码形状钉子（`launch` 体里有 `finally`）。
 *
 * ## 怎么把窗口钉成确定的（不靠概率、不靠高频采样）
 * `delete()` 的异步段固定在 [com.dsh.core.DshAppScope]（`SupervisorJob + Dispatchers.IO`），
 * 没有可注入的 dispatcher。所以这里用 `DshRuntime._state = Starting(id)` 把等待循环
 * （`DELETE_WAIT_TIMEOUT_MS` = 8s）钉住 —— 与 `DshInstancesPersistenceTest.secondDeleteOnSameIdStillReceivesItsCallback`
 * 同一手法，路径全是真实生产代码。
 * 选 `Starting` 而不是 `Running` 是**故意**的：`stopIf` 的判据是 `runningInstanceId()`
 * （Starting 也算），但它 `stop()` 的是"当前运行的那个"，这里唯一的副作用是状态机多走一步；
 * 本用例只在需要时用，ghost 用例甚至不需要它（见 T2 的注释）。
 */
class R41DeleteTransactionTest {

    // --- T1：提交之后、收尾未完那一段必须仍被挡住（缺陷本体） ------------------

    /**
     * **缺陷窗口**：清单已落盘（`commitPendingDelete` 已把 id 摘出 `pendingDeletes`）、但事务还**没有
     * 走完**（协程体的 `finally` 还没放闸）。此刻第二次 `delete(id)` **必须**被拒绝。
     *
     * ## 为什么第二次调用放在 `onFinished` 回调里 —— 这是**确定的**锚点，不是概率采样
     * 正常成功出口的顺序是：写盘 → `commitPendingDelete`（提交）→ 删目录 → 删日志 → `_deleting -= id`
     * → `DshTasks.report(DONE)` → `onFinished(ok)` → **`finally`（放闸）**。
     * 所以回调**内部**这一瞬：提交早已完成（`pendingDeletes` 里已无该 id）、终态已上报，
     * 但闸门尚未释放 —— 正落在"闸门覆盖范围就是事务全程"这件事的**最外侧尾巴**上。
     * 于是这一条在 HEAD（旧闸门只看 `pendingDeletes`）上会得到 `true`，修好后是 `false`。
     * 不需要造大树、不需要轮询抢窗口、不依赖调度运气。
     *
     * ⚠️ 这里**不能**用「`_deleting` 仍含该 id」当窗口判据：`_deleting` 的移除发生在终态上报
     * 与回调**之前**（既有顺序，见源码那段顺序注释），到回调时它早就空了。计划文档里写的
     * 「`pendingDeletes` 无、`deleting` 有」那个中间窗口确实存在，但它夹在协程体内部
     * （提交与 `_deleting` 移除之间），只能靠"造大树 + 高频轮询"去撞 —— 那正是本仓库明令
     * 禁止的概率型断言。本用例改用回调这个确定锚点，钉的是同一个不变量的**外沿**；
     * 闸门既然要求"事务全程占着"，它自然也覆盖了那个更靠里的窗口。
     *
     * ⚠️ 旧版 T1 把第一次事务钉在等待循环里再调第二次 —— 那种写法在 HEAD 上**也是绿的**
     * （那时 id 还在 `pendingDeletes` 里，旧闸门照样拒），钉不住本缺陷。已改成现在这样。
     *
     * 断言：
     * 1. 前置：这一刻 `pendingDeletes` 已无该 id，且终态行已上报（确认真的坐在窗口里）；
     * 2. 第二次 `delete()` 返回 false 且回调 false；
     * 3. 任务区只有**一条** `delete:<id>` 终态行（缺陷态下两条事务各报一条）；
     * 4. `deleting` 最终为空。
     */
    @Test
    fun t1_secondDeleteInsideTheCommitWindowIsRejected() {
        withInstancesDir("dsh-sp04-commit-window") { root, id ->
            setUpPaths(root)
            DshInstances.resetForTest()
            DshTasks.clearFinishedForTest()
            DshInstances.loadFrom(File(root, "instances.json"))
            assertTrue("前置：应能先移走清单文件（让这次写盘能成功）", File(root, "instances.json").delete())

            val secondAccepted = AtomicReference<Boolean?>(null)
            val secondCb = AtomicReference<Boolean?>(null)
            val sawWindow = AtomicReference<Boolean?>(null)
            val firstCb = AtomicReference<Boolean?>(null)

            assertTrue(
                "前置：第一次 delete 应受理",
                DshInstances.delete(id) { ok ->
                    firstCb.set(ok)
                    // ★ 窗口：提交已发生（待删表里没它了）+ 终态已上报，而 finally 还没跑。
                    sawWindow.set(
                        !pendingDeletesMap().containsKey(id) &&
                            DshTasks.finished.value.any { it.id == DshTasks.deleteTaskId(id) }
                    )
                    secondAccepted.set(DshInstances.delete(id) { secondCb.set(it) })
                }
            )

            assertTrue("前置：第一次 delete 的回调必须到达", awaitValue(firstCb, 30_000) != null)
            assertEquals("前置：删除目录成功时应回调 true", true, firstCb.get())
            awaitDeletingEmpty(id, 30_000)
            // 第二次调用的回调是同步触发的（拒绝路径里直接 invoke），这里再等一次以防万一。
            awaitValue(secondCb, 5_000)

            assertEquals(
                "前置：第二次调用必须发生在「已提交、已上报、但闸门未释放」的缺陷窗口里" +
                    "（否则本用例没打到缺陷，sawWindow=${sawWindow.get()}）",
                true, sawWindow.get()
            )
            assertEquals(
                "★ 证伪条件：窗口内的第二次 delete() 必须被拒绝（HEAD 上会是 true）",
                false, secondAccepted.get()
            )
            assertEquals("第二次回调必须给 false（本次没有执行删除）", false, secondCb.get())

            val rows = DshTasks.finished.value.filter { it.id == DshTasks.deleteTaskId(id) }
            assertEquals(
                "★ 任务区只允许**一条** delete:$id 终态行（实际 ${rows.size} 条：${rows.map { it.stage }}）",
                1, rows.size
            )
            assertTrue("deleting 最终必须为空", DshInstances.deleting.value.isEmpty())
        }
    }

    /**
     * 在途期（**提交之前**）的重复调用同样被拒 —— 这条钉的是既有契约 fix-r31-104，不是本缺陷的
     * 证伪用例（HEAD 上就是绿的：那时 id 还在 `pendingDeletes` 里，旧闸门也拒）。
     *
     * 保留它是因为它对**返回值 + 回调**两条契约一起把关：拒绝必须 `return false` **并且**
     * 用 `false` 回调（旧写法 `return true` 却永不回调是要防的回归）。
     */
    @Test
    fun t1b_secondDeleteBeforeCommitIsAlsoRejected() {
        withInstancesDir("dsh-sp04-inflight") { root, id ->
            setUpPaths(root)
            DshInstances.resetForTest()
            DshTasks.clearFinishedForTest()
            DshInstances.loadFrom(File(root, "instances.json"))

            // 把第一次事务钉在等待循环里：整段"提交之前"的窗口都是确定的。
            holdRuntimeInStartingState(id)
            // JVM-Windows 的 renameTo 不覆盖同名目标：先移走清单文件，让第一次事务自己的写盘能成功。
            assertTrue("前置：应能先移走清单文件", File(root, "instances.json").delete())

            val firstCb = AtomicReference<Boolean?>(null)
            val secondCb = AtomicReference<Boolean?>(null)
            assertTrue("前置：第一次 delete 应受理", DshInstances.delete(id) { firstCb.set(it) })
            assertTrue(
                "前置：第一次事务必须已把它登记进待删表（否则打得不是「提交之前」这段）",
                pendingDeletesMap().containsKey(id)
            )

            val accepted = DshInstances.delete(id) { secondCb.set(it) }

            releaseRuntimeState()
            assertTrue("前置：第一次 delete 的回调必须到达", awaitValue(firstCb, 30_000) != null)
            awaitDeletingEmpty(id, 30_000)

            assertFalse("★ 第二次 delete() 必须被拒绝（一个 id 一个事务）", accepted)
            assertEquals("第二次回调必须给 false（本次没有执行删除）", false, secondCb.get())
            assertEquals("第一次事务自己照常回调 ok", true, firstCb.get())

            val rows = DshTasks.finished.value.filter { it.id == DshTasks.deleteTaskId(id) }
            assertEquals(
                "★ 任务区只允许**一条** delete:$id 终态行（实际 ${rows.size} 条：${rows.map { it.stage }}）",
                1, rows.size
            )
            assertTrue("deleting 最终必须为空", DshInstances.deleting.value.isEmpty())
        }
    }

    // --- T2：ghost id（byId == null）也必须被闸门挡住 ------------------------

    /**
     * 删一个**不在清单里**的 id：同样只允许一个事务。
     *
     * 旧实现的洞：`inst == null` 时走 `?: _instances.update { filterNot }`，**不登记** `pendingDeletes`，
     * 于是闸门（查 `pendingDeletes`）对该 id 恒为假 —— 每次调用都完整走一遍流程，
     * 每次都留一条幻影 `delete:<id>` DONE 行。
     *
     * ## 为什么这条**不需要** `holdRuntimeInStartingState`
     * 第二次 `delete()` 是在第一次的**同步段之后、协程体之前/之间**调用的：此刻
     * `deleteInFlight` 已经含该 id（新实现），旧实现则既不占闸门也不进待删表 —— 两种实现下
     * 第二次调用的判据都不依赖协程调度。为了让用例不依赖"协程还没跑完"这个时序，这里额外用
     * `Starting` 把第一次钉住（同一手法，零 flaky），并断言 `deleting` 最终为空。
     */
    @Test
    fun t2_ghostIdDeleteIsAlsoGuarded() {
        withInstancesDir("dsh-sp04-ghost") { root, id ->
            setUpPaths(root)
            DshInstances.resetForTest()
            DshTasks.clearFinishedForTest()
            // 清单里只有 id，而我们要删的是 ghostId（不在清单里 ⇒ byId == null）。
            DshInstances.loadFrom(File(root, "instances.json"))
            val ghostId = "inst-sp04-ghost-not-in-manifest"
            assertNull("前置：ghost id 必须不在内存里", DshInstances.byId(ghostId))

            holdRuntimeInStartingState(ghostId)
            // 同 T1：JVM-Windows 的 renameTo 不覆盖同名目标，先移走清单文件，
            // 让第一次 ghost 事务自己的写盘能成功（否则它走回滚出口，收尾那一段就测不到了）。
            assertTrue("前置：应能先移走清单文件", File(root, "instances.json").delete())
            val firstCb = AtomicReference<Boolean?>(null)
            val secondCb = AtomicReference<Boolean?>(null)
            assertTrue("第一次 delete(ghost) 应受理（行为不变：不拒绝 ghost id）", DshInstances.delete(ghostId) { firstCb.set(it) })
            assertFalse(
                "前置：ghost id 不进待删表（这正是旧实现漏掉的那一段）",
                pendingDeletesMap().containsKey(ghostId)
            )

            val accepted = DshInstances.delete(ghostId) { secondCb.set(it) }

            releaseRuntimeState()
            assertTrue("前置：第一次 ghost 事务的回调必须到达", awaitValue(firstCb, 30_000) != null)
            awaitDeletingEmpty(ghostId, 30_000)

            assertFalse(
                "★ 证伪条件：ghost id 的第二次 delete 也必须被拒绝（HEAD 上会是 true —— 旧闸门对它恒为假）",
                accepted
            )
            assertEquals("ghost id 的第二次回调必须给 false", false, secondCb.get())
            assertEquals("第一次 ghost 事务照常回调 true（目录不存在时 deleteRecursively 返回 true）", true, firstCb.get())

            val rows = DshTasks.finished.value.filter { it.id == DshTasks.deleteTaskId(ghostId) }
            assertEquals("★ 只允许一条 delete:$ghostId 终态行（HEAD 上会是 2 条幻影行）", 1, rows.size)
            assertTrue("deleting 最终必须为空", DshInstances.deleting.value.isEmpty())
        }
    }

    // --- T3：异常路径下闸门必须释放（不能永久卡住） ---------------------------

    /**
     * 事务体抛出时，`finally` 必须释放闸门，且 `_deleting` 不能永久残留。
     *
     * ## 怎么让异常路径成为**确定的**
     * 用 `delete()` 自己的 `onFinished` 回调**抛出**：`onFinished?.invoke(ok)` 是事务体（`try` 内）
     * 的最后一个语句，`delete()` 的 KDoc 也明确写着回调跑在"主线程/IO 线程不定"、调用方自负其责 ——
     * 一个会抛的回调是真实存在的失败模式（界面回调里访问已销毁的 View 就会这样）。
     * 它天然打在**异常发生之前所有收尾都已完成**的位置（目录删了、日志删了、`_deleting` 减了、
     * 终态上报了），所以：
     * - 没有 `finally` 时，`deleteInFlight` 会永久残留 ⇒ 该 id 从此再也删不掉（硬死锁）；
     * - `_deleting` 由 `finally` 兜底，仍然为空。
     *
     * ⚠️ 不能用 `dirFsync` seam 注入：它被 `runCatching` 包住（只记日志），抛不出去。
     *
     * 断言：
     * 1. 回调确实被调用过（否则用例是空转，什么都不证明）；
     * 2. `deleting` 最终为空；
     * 3. 同一个 id **可以再次 delete** —— 这是闸门确实被释放的直接证据。
     *    "占闸但不 finally" 的形态下这里会永远 false。
     */
    @Test
    fun t3_gateIsReleasedWhenTheTransactionThrows() {
        withInstancesDir("dsh-sp04-finally") { root, id ->
            setUpPaths(root)
            DshInstances.resetForTest()
            DshTasks.clearFinishedForTest()
            DshInstances.loadFrom(File(root, "instances.json"))
            assertNotNull("前置：实例应在内存里", DshInstances.byId(id))
            assertTrue("前置：应能先移走清单文件（让写盘能成功，走到收尾那一段）", File(root, "instances.json").delete())

            val callbackCalls = java.util.concurrent.atomic.AtomicInteger(0)
            assertTrue(
                "第一次 delete 应受理",
                DshInstances.delete(id) {
                    callbackCalls.incrementAndGet()
                    throw IllegalStateException("SP-04 injected: onFinished throws inside the delete transaction")
                }
            )

            val returnedToEmpty = awaitDeletingEmpty(id, 30_000)
            assertTrue("前置：回调确实被调用过（否则本用例是空转）", callbackCalls.get() == 1)
            assertTrue(
                "★ 证伪条件（前半）：异常后 deleting 必须被 finally 兜底清空（否则卡片永远「删除中」）",
                returnedToEmpty
            )

            // ★ 证伪条件（后半）：闸门必须已释放 —— 同一个 id 还能再删一次。
            assertNotNull(
                "前置：能再删一次的前提是实例仍在清单里（清单文件已被本次事务删掉，重读即空清单）",
                rebuildInstance(root, id)
            )
            assertTrue(
                "★ 证伪条件（后半）：闸门必须已释放 —— 同一个 id 必须能再删一次" +
                    "（不释放的话这里会永远 false，即「该 id 永久不可再删」）",
                DshInstances.delete(id) { }
            )
            awaitDeletingEmpty(id, 30_000)
        }
    }

    /** 把实例 [id] 重新造回清单与目录，让"再删一次"有对象。返回重读后的实例。 */
    private fun rebuildInstance(root: File, id: String): DshInstance? {
        File(root, "instances").let { assertTrue("应能建出 instances 目录", it.isDirectory || it.mkdirs()) }
        val dir = File(root, "instances/$id")
        assertTrue("应能建出实例目录", dir.isDirectory || dir.mkdirs())
        File(root, "instances.json").delete()
        File(root, "instances.json").writeText("""{"instances":[${entry(id)}],"selectedId":null}""")
        DshInstances.resetForTest()
        DshInstances.loadFrom(File(root, "instances.json"))
        return DshInstances.byId(id)
    }

    // --- T4：源码形状钉子 ----------------------------------------------------

    /**
     * `delete()` 的协程体必须有 `finally`。
     *
     * 为什么值得钉：闸门只能在"所有收尾做完"之后释放，而收尾里有多处未被 `runCatching` 包住的
     * 调用（`DshLogBus.append` / `DshLogBus.removeStream` / `getString`）—— 没有 `finally` 就
     * 会变成"该 id 永久不可再删"。这是一个只在异常路径上出现、跑测试很难凑出来的形态，
     * 所以钉住源码形状：`delete()` 的 `launch {` 与 `return true` 之间必须出现 `try {` 与 `} finally {`。
     */
    @Test
    fun t4_transactionGateIsInsideAFinallyBlock() {
        val src = resolveUnder("FCL/src/main/java/com/dsh/core/DshInstances.kt")
        assertNotNull("必须能找到 DshInstances.kt（cwd=${File(".").absolutePath}）", src)
        val text = src!!.readText()

        // 先从函数签名找起（签名在文件里只出现一次），再在它**之后**找第一个 launch。
        val deleteAt = text.indexOf("fun delete(id: String, onFinished: ((Boolean) -> Unit)? = null): Boolean {")
        assertTrue("必须能定位 delete() 的定义", deleteAt >= 0)
        val launchAt = text.indexOf("DshAppScope.scope.launch {", deleteAt)
        assertTrue("debug: deleteAt=$deleteAt launchAt=$launchAt", launchAt > deleteAt)
        val body = text.substring(launchAt)

        val tryAt = body.indexOf("try {")
        val finallyAt = body.indexOf("} finally {")
        assertTrue("★ delete() 的协程体必须包 try（闸门释放点的前提）", tryAt in 0 until finallyAt)
        assertTrue("★ delete() 的协程体必须包 finally（闸门/`_deleting` 的唯一兜底释放点）", finallyAt > tryAt)

        // finally 块本身：从 `} finally {` 到它自己的 `}`（12 空格缩进那一行）。
        val finallyEnd = body.indexOf("\n            }\n", finallyAt).let {
            if (it < 0) body.length else it
        }
        val finallyBody = body.substring(finallyAt, finallyEnd)
        assertTrue(
            "★ finally 里必须调 endDeleteTransaction(id) —— 这是闸门的唯一释放点",
            finallyBody.contains("endDeleteTransaction(id)")
        )
        assertTrue(
            "★ finally 里必须先幂等移除 _deleting 再放闸（保证第二次 delete 进来时 UI 收尾痕迹已清）",
            finallyBody.indexOf("_deleting.update { it - id }") in 0 until finallyBody.indexOf("endDeleteTransaction(id)")
        )
        assertEquals(
            "★ 释放点只允许一处：一处占闸（beginDeleteTransaction）+ 一处放闸，防止有人在各出口又塞一处",
            1, text.split("endDeleteTransaction(id)").size - 1
        )
    }

    // --- 夹具 ---------------------------------------------------------------

    /** 建一个临时 root：`instances/<id>/` 存在、`instances.json` 含一条该 id 的记录。 */
    private inline fun withInstancesDir(name: String, block: (root: File, id: String) -> Unit) {
        val root = Files.createTempDirectory(name).toFile()
        val id = "inst-sp04-1"
        try {
            assertTrue("前置：应能建出 instances 目录", File(root, "instances/$id").mkdirs())
            File(root, "instances.json").writeText("""{"instances":[${entry(id, "SP-04 的实例")}],"selectedId":null}""")
            block(root, id)
        } finally {
            releaseRuntimeState()
            DshInstances.dirFsync = null
            restorePaths()
            DshInstances.resetForTest()
            DshTasks.clearFinishedForTest()
            DshInstances.dirFsync = null
            root.deleteRecursively()
        }
    }

    private fun entry(id: String, name: String = id): String =
        """{"id":"$id","name":"$name","dshVersion":"0.1.6","profile":"web",""" +
            """"port":0,"model":"deepseek-flash","createdAt":1,"updatedAt":2,"lastError":null,"state":"READY"}"""

    /** 轮询到 `deleting` 不含 [id] 为止。返回 false = 超时（无 finally 的那版会走这里）。 */
    private fun awaitDeletingEmpty(id: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!DshInstances.deleting.value.contains(id)) return true
            Thread.sleep(5)
        }
        return !DshInstances.deleting.value.contains(id)
    }

    /** 轮询到 [ref] 被赋值为止（回调到达）。返回 null = 超时。 */
    private fun <T> awaitValue(ref: AtomicReference<T?>, timeoutMs: Long): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ref.get()?.let { return it }
            Thread.sleep(5)
        }
        return ref.get()
    }

    private fun holdRuntimeInStartingState(id: String) {
        runtimeStateFlow().value = DshRuntime.State.Starting(id, "held by R41DeleteTransactionTest")
    }

    private fun releaseRuntimeState() {
        runCatching { runtimeStateFlow().value = DshRuntime.State.Idle }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runtimeStateFlow(): MutableStateFlow<DshRuntime.State> {
        val f = DshRuntime::class.java.getDeclaredField("_state")
        f.isAccessible = true
        return f.get(DshRuntime) as MutableStateFlow<DshRuntime.State>
    }

    /** `DshInstances.pendingDeletes`（id → 待删记录）。 */
    @Suppress("UNCHECKED_CAST")
    private fun pendingDeletesMap(): MutableMap<String, DshInstance> {
        val f = DshInstances::class.java.getDeclaredField("pendingDeletes")
        f.isAccessible = true
        return f.get(DshInstances) as MutableMap<String, DshInstance>
    }

    /**
     * 把 [DshPaths] 指到临时目录（反射，理由同 `DshInstancesPersistenceTest.setUpPathsForAdoptionTest`：
     * 路径字段是 `lateinit var ... private set`，只有 `loadPaths(Context)` 能写，而 JVM 单测没有 Context）。
     * 收尾必须复原 —— `DshPaths` 是进程级单例，漏了会把**后面**的用例带红。
     */
    private fun setUpPaths(root: File) {
        savedPaths = DshPaths::class.java.declaredFields.associateWith { f ->
            f.isAccessible = true
            runCatching { f.get(DshPaths) }.getOrNull()
        }
        fun set(name: String, value: Any) {
            val f = DshPaths::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.set(DshPaths, value)
        }
        set("ROOT_DIR", root.absolutePath)
        set("ROOTFS_DIR", File(root, "rootfs").absolutePath)
        set("INSTANCES_DIR", File(root, "instances").absolutePath)
        set("INSTANCES_MANIFEST", File(root, "instances.json").absolutePath)
        set("SCRIPTS_DIR", File(root, "scripts").absolutePath)
        set("NPM_CACHE_DIR", File(root, "npm-cache").absolutePath)
        set("LOG_FILE", File(root, "logs/runtime.log").absolutePath)
        set("TARBALL_CACHE_DIR", File(root, "tarballs").absolutePath)
        set("TMP_DIR", File(root, "tmp").absolutePath)
        set("isLoaded", true)
    }

    private var savedPaths: Map<Field, Any?> = emptyMap()

    private fun restorePaths() {
        savedPaths.forEach { (f, v) ->
            f.isAccessible = true
            runCatching { f.set(DshPaths, v) }
        }
        savedPaths = emptyMap()
    }

    /** 从 cwd 向上找 `FCL/src/main/java/com/dsh/<rel>`（与 R28/R31 各测试同款）。 */
    private fun resolveUnder(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return null
            listOf(File(d, rel), File(d, "FCL/$rel"), File(d, "dsh-fcl-android-launcher/$rel"))
                .firstOrNull { it.isFile }
                ?.let { return it }
            dir = d.parentFile
        }
        return null
    }

    /** 反射调私有方法（变异验证/直接观察闸门状态时用；正常断言走公开 API）。 */
    private fun invokePrivate(method: String, vararg args: Any?): Any? {
        val types = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        val m = DshInstances::class.java.declaredMethods.first {
            it.name == method && it.parameterCount == args.size
        }
        m.isAccessible = true
        return try {
            m.invoke(DshInstances, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun deleteInFlightSet(): MutableSet<String> {
        val f = DshInstances::class.java.getDeclaredField("deleteInFlight")
        f.isAccessible = true
        return f.get(DshInstances) as MutableSet<String>
    }

    /** T1 的附加观察点（不单独成用例）：公开 API 之外，闸门集合本身也该是空的。 */
    @Test
    fun t5_deleteInFlightIsEmptyAfterEveryCase() {
        withInstancesDir("dsh-sp04-gate-empty") { root, id ->
            setUpPaths(root)
            DshInstances.resetForTest()
            DshInstances.loadFrom(File(root, "instances.json"))
            assertTrue("前置：应能先移走清单文件", File(root, "instances.json").delete())

            assertTrue(DshInstances.delete(id) { })
            awaitDeletingEmpty(id, 30_000)
            assertEquals(
                "收尾之后 deleteInFlight 必须为空（否则该 id 永久不可再删）",
                emptySet<String>(), deleteInFlightSet().toSet()
            )
            // 幂等：直接调两次 endDeleteTransaction 不得抛、也不得留下东西。
            invokePrivate("endDeleteTransaction", id)
            invokePrivate("endDeleteTransaction", id)
            assertEquals("endDeleteTransaction 必须幂等", emptySet<String>(), deleteInFlightSet().toSet())
        }
    }
}
