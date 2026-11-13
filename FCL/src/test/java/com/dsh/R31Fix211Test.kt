package com.dsh

import android.content.Context
import android.content.ContextWrapper
import com.dsh.core.DshDownloadViewModel
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshInstaller
import com.dsh.core.ProotExecutor
import com.dsh.core.RepairPlan
import com.dsh.core.applyRepairPlans
import com.dsh.core.staleInstallingMarkers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 31 / 第 4 波 fix：`r31-s02-conc-01` / `-04` / `-05` / `-06` 的证伪条件。
 *
 * ## 为什么每条都测"能红的那一面"
 *
 * 本仓已经栽过两次同类跟头（`R28CallSiteContractTest` 的由来、E6 §2.2 的"抽了函数却没接线"）：
 * 只测纯函数、不钉缺陷本身，把修复点删掉测试照样全绿。所以这里每条用例都按
 * 「**把修复点变异掉，这条必须变红**」设计 —— 具体变异见各用例 KDoc 的 "变异" 一行，
 * 实测结果见报告 §5。
 *
 * ## 为什么不用 DshPaths / 真磁盘
 *
 * 四条缺陷里只有 `conc-01` 涉及磁盘事实，而它的缺陷**不在取事实那一半**（`planRepair` 早已
 * 被钉过），在"写回时没有期望前态校验"那一半 —— 那是一段纯内存逻辑，抽成
 * [applyRepairPlans] 之后可以直接喂"快照"和"当前列表"两个入参，把并发交错**钉成确定的**
 * （不需要线程、不需要 sleep、每次同一结论）。`conc-04/05` 同理；`conc-06` 是语句顺序，
 * 只能源码级断言（理由见该用例）。
 */
class R31Fix211Test {

    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
        restoreInstances(emptyList())
    }

    // =====================================================================
    // r31-s02-conc-01：repair 的写回必须带"期望前态"校验
    // =====================================================================

    /**
     * 缺陷形态（审计 F-01 / 裁决 UPHELD·HIGH）：`repair()` 在 IO 线程按**快照**算裁决，
     * 写回时只用"当前项 vs plan 结论"比较、`else` 分支无条件覆盖 —— 于是安装线程在快照之后
     * 写的 `INSTALLING`/`READY` 会被改回快照时刻的 `NOT_INSTALLED` 并 `saveAsync()` 落盘，
     * 用户看到"未安装"按钮，最自然的动作是重下 500MB 覆盖好树。
     *
     * 证伪条件：快照项 `{NOT_INSTALLED, version=null}`、plan 判 `NOT_INSTALLED`，而**当前项**
     * 已被并发改成 `{INSTALLING, version="0.2.0"}` ⇒ 写回结果必须仍是 `INSTALLING` + `0.2.0`。
     *
     * 变异（compile-ok）：把 [applyRepairPlans] 里的期望前态判据改成恒真
     * （`if (false)`）⇒ 本条红，且只有本条红（见报告 §5.1）。
     */
    @Test
    fun repairDropsVerdictWhenStateChangedAfterSnapshot() {
        val snapshot = instance("inst-a", "A", DshInstance.State.NOT_INSTALLED, dshVersion = null)
        // 快照之后：安装线程在调用线程写了 markState(INSTALLING, version = 0.2.0)
        val concurrent = snapshot.copy(state = DshInstance.State.INSTALLING, dshVersion = "0.2.0")
        val plan = RepairPlan(version = null, state = DshInstance.State.NOT_INSTALLED, reason = "not installed")

        val out = applyRepairPlans(
            current = listOf(concurrent),
            planned = mapOf(snapshot.id to plan),
            baseline = mapOf(snapshot.id to snapshot),
            now = 1L
        )

        assertEquals(
            "★ 证伪条件：快照之后被并发写过的实例，repair 的裁决必须丢弃（实际 ${out.instances}），" +
                "否则就是把 INSTALLING/READY 改回 NOT_INSTALLED 并落盘",
            DshInstance.State.INSTALLING,
            out.instances.single().state
        )
        assertEquals("并发写入的版本不得被 plan 的 null 覆盖", "0.2.0", out.instances.single().dshVersion)
        assertEquals("丢弃的 id 必须被报告（供日志留痕）", listOf(snapshot.id), out.skipped)
        assertTrue("丢弃裁决不算一次改动（不应触发 saveAsync）", !out.changed)
    }

    /**
     * 反向守卫（防"一律跳过"的过修）：快照与当前项一致时，plan 必须照常生效。
     *
     * 只写"该跳过"那一半的话，把实现改成"永远跳过"也能全绿 —— 而那是把 repair 整个废掉。
     *
     * 变异：把 [applyRepairPlans] 改成直接 `return RepairApplyResult(current, false, …)` ⇒ 本条红。
     */
    @Test
    fun repairStillAppliesWhenSnapshotIsUnchanged() {
        val snapshot = instance("inst-b", "B", DshInstance.State.NOT_INSTALLED, dshVersion = null)
        // 磁盘事实：树完整、版本 0.3.0
        val plan = RepairPlan(version = "0.3.0", state = DshInstance.State.READY, reason = "own tree complete")

        val out = applyRepairPlans(
            current = listOf(snapshot),
            planned = mapOf(snapshot.id to plan),
            baseline = mapOf(snapshot.id to snapshot),
            now = 2L
        )

        assertEquals(
            "快照未被并发改动时，plan 必须生效（否则 repair 等于被废掉）",
            DshInstance.State.READY,
            out.instances.single().state
        )
        assertEquals("dshVersion 必须纠正为磁盘真实版本", "0.3.0", out.instances.single().dshVersion)
        assertTrue("生效时必须报告 changed（否则 saveAsync 不会跑、磁盘永远不更新）", out.changed)
        assertTrue("生效时不应有 skipped", out.skipped.isEmpty())
    }

    /**
     * 期望前态只比 `state` / `dshVersion`：**改名字**（rename 与 repair 撞上是常态）不该让裁决作废。
     *
     * 这一条防的是"比 updatedAt / 整个对象"的过修：那会让一条无关的改名把本轮校准全部丢掉。
     */
    @Test
    fun repairVerdictSurvivesUnrelatedRename() {
        val snapshot = instance("inst-c", "旧名字", DshInstance.State.NOT_INSTALLED, dshVersion = null)
        val renamed = snapshot.copy(name = "新名字")
        val plan = RepairPlan(version = "0.3.0", state = DshInstance.State.READY, reason = "own tree complete")

        val out = applyRepairPlans(
            current = listOf(renamed),
            planned = mapOf(snapshot.id to plan),
            baseline = mapOf(snapshot.id to snapshot),
            now = 3L
        )

        assertEquals(
            "并发改名不影响 repair 的结论（state/version 才是它的前提）",
            DshInstance.State.READY,
            out.instances.single().state
        )
        assertEquals("改名结果必须保留（不能被 repair 用快照项覆盖回去）", "新名字", out.instances.single().name)
    }

    /**
     * 空计划/期间新建的实例：原样返回、不算改动（旧实现的 `planned[inst.id] ?: return@map inst` 语义）。
     */
    @Test
    fun repairLeavesInstancesWithoutPlanAlone() {
        val fresh = instance("inst-new", "期间新建", DshInstance.State.NOT_INSTALLED, dshVersion = null)
        val out = applyRepairPlans(
            current = listOf(fresh),
            planned = emptyMap(),
            baseline = emptyMap(),
            now = 4L
        )
        assertEquals("没有裁决的实例必须原样不动", listOf(fresh), out.instances)
        assertTrue("没有裁决时不得报告 changed", !out.changed)
    }

    // =====================================================================
    // r31-s02-conc-04：改动计数必须原子
    // =====================================================================

    /**
     * 缺陷形态（审计 F-04 / 裁决 UPHELD·MEDIUM）：`mutationsWhileLoadFailed` 是裸 `Int`，
     * `++` 是读-改-写；写方跨线程（UI 线程 + IO 线程的 repair），读方在 IO 线程。
     * 丢计数或读到陈旧值都会让 `replaced` 判成 true ⇒ 内存被 `parsed.instances` 整份覆盖 ⇒
     * 读失败窗口里用户的新建/改名**无声消失**（E2-N-5）。
     *
     * 证伪条件：`manifestLoadFailed=true` 时 N 个线程各调一次计数入口、全部 join 后，
     * 计数值必须**恰好等于 N**。
     *
     * 为什么这样取数：`countMutationWhileLoadFailed` 是 private。这里反射调用**生产实现本身**
     * （不是抄一份逻辑），并用 `Any` 读字段 —— 裸 `Int` 与 `AtomicInteger` 两种形态都读得出来，
     * 所以同一份测试源码在**改前也能编译**，红是断言红而不是编译红。
     *
     * 变异（compile-ok）：把 `incrementAndGet()` 换成 `set(get() + 1)` ⇒ 本条红（见报告 §5.2）。
     */
    @Test
    fun concurrentMutationCountIsNotLost() {
        val loadFailed = loadFailedFlow()
        loadFailed.value = true
        resetCounter()
        val increment = countMethod()

        val threads = 8
        val perThread = 5_000
        val barrier = CyclicBarrier(threads)
        val done = CountDownLatch(threads)
        val errors = AtomicInteger(0)
        repeat(threads) {
            Thread {
                try {
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(perThread) { increment.invoke(DshInstances) }
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }.start()
        }
        assertTrue("前置：所有工作线程都必须跑完", done.await(60, TimeUnit.SECONDS))
        assertEquals("前置：工作线程不得抛异常", 0, errors.get())

        val expected = threads * perThread
        val actual = counterValue()
        loadFailed.value = false
        assertEquals(
            "★ 证伪条件：并发自增不得丢计数（期望 $expected，实际 $actual）" +
                " —— 丢计数会让 replaced 判 true，把读失败窗口里的用户改动整份冲掉",
            expected,
            actual
        )
    }

    // =====================================================================
    // r31-s02-conc-05：终态被合帧丢掉后，"安装中"标记必须能自愈
    // =====================================================================

    /**
     * 纯函数层：`READY`/`BROKEN` 的同版本实例 + 无在途安装 ⇒ 标记过时。
     *
     * 缺陷形态（审计 F-05 / 裁决 UPHELD·MEDIUM）：`progress` 是单字段 StateFlow（合帧），
     * 两实例并行安装时 A 的 `Done(vA)` 会被 B 的 `Log` 覆盖，收集器永远收不到终态 ⇒
     * 标记永久残留。判据必须来自权威事实（实例状态 + 在途表），不能只靠事件。
     *
     * 变异：把 [staleInstallingMarkers] 的返回改成 `emptySet()` ⇒ 本条红。
     */
    @Test
    fun markersAreStaleOnlyWhenInstallationAlreadyLanded() {
        val instances = listOf(
            instance("i-ready", "已装好", DshInstance.State.READY, dshVersion = "1.0.0"),
            instance("i-broken", "装失败", DshInstance.State.BROKEN, dshVersion = "2.0.0"),
            instance("i-installing", "在装", DshInstance.State.INSTALLING, dshVersion = "3.0.0"),
            instance("i-empty", "空壳", DshInstance.State.NOT_INSTALLED, dshVersion = null)
        )
        val stale = staleInstallingMarkers(
            current = setOf("1.0.0", "2.0.0", "3.0.0", "4.0.0"),
            instances = instances,
            inFlightVersions = emptySet()
        )
        assertEquals("READY/BROKEN 的同版本标记必须判过时；INSTALLING 与无主标记不得清", setOf("1.0.0", "2.0.0"), stale)

        val stillInFlight = staleInstallingMarkers(
            current = setOf("1.0.0"),
            instances = instances,
            inFlightVersions = setOf("1.0.0")
        )
        assertTrue("在途安装指向的版本绝不能被清（并行重装会被误放行 → 连点 → 并发 npm）", stillInFlight.isEmpty())
    }

    /**
     * 接线层：VM 收到**任意**进度事件（这里是 `Log`，不含任何终态）时，过期标记必须被清掉；
     * 且另一个仍在安装的版本必须留着。
     *
     * 这就是"合帧丢了 Done(vA)"的等价情形：vA 的实例已经 READY（终态已落地），但 VM 的集合里
     * 还留着它 —— 唯一的修复路径是不看事件、看状态。
     *
     * 变异：把 `onProgress` 里的 `healInstallingMarkers()` 删掉 ⇒ 本条红（见报告 §5.3；
     * R26TerminalEventTest 的 `onProgressLogDoesNotClear` 仍绿 —— 那正是"只测纯函数会假绿"的证明）。
     */
    @Test
    fun healClearsCoalescedMarkerAndKeepsLiveOne() {
        seedInstances(
            listOf(
                instance("i-a", "A", DshInstance.State.READY, dshVersion = "vA"),
                instance("i-b", "B", DshInstance.State.INSTALLING, dshVersion = "vB")
            )
        )
        val (vm, _) = newVm()
        seedInstalling(vm, setOf("vA", "vB"))

        vm.onProgress(DshInstaller.Progress.Log("npm http fetch GET 200 .../a.tgz"))

        assertEquals(
            "★ 证伪条件：终态被合帧丢掉后，标记必须由权威状态自愈（vA 已 READY）；" +
                "同时 vB 的实例仍是 INSTALLING ⇒ 标记必须留着",
            setOf("vB"),
            vm.installingVersions.value
        )
    }

    /**
     * 反向守卫：实例仍在 `INSTALLING` 时，即使收到 Log 事件也不得清（否则按钮提前变可点 → 连点）。
     */
    @Test
    fun healKeepsMarkerWhileInstanceStillInstalling() {
        seedInstances(listOf(instance("i-c", "C", DshInstance.State.INSTALLING, dshVersion = "vC")))
        val (vm, _) = newVm()
        seedInstalling(vm, setOf("vC"))

        vm.onProgress(DshInstaller.Progress.Log("installing dependencies"))

        assertEquals("仍在安装的实例，其标记不得被自愈清掉", setOf("vC"), vm.installingVersions.value)
    }

    // =====================================================================
    // r31-s02-conc-06：清 owner 必须早于放互斥
    // =====================================================================

    /**
     * 缺陷形态（审计 F-06 / 裁决 UPHELD·LOW）：`finally { busyGuard.set(false); busyOwner.set(null) }`
     * 先放互斥、后清 owner；两句之间另一线程可 CAS 成功并写入自己的 owner，随后被上一个任务清成
     * null ⇒ `cur == owner` 幂等判据失效（重复点击变成"底座忙"错误）、`currentOwner()` 全程 null。
     *
     * 证伪条件：`install()` 的 `finally` 块内，清 `busyOwner` 的语句索引必须**小于**
     * `busyGuard.set(false)` 的索引。
     *
     * ## 为什么这条是源码级断言（而不是并发交错）
     * 这条缺陷的判据就是**语句顺序**，而 JVM 里没有可注入的阻塞点能把那个窗口钉成确定的：
     * `install()` 从 CAS 到 `finally` 之间没有挂起点，`Context.getFilesDir()` 在 android.jar 里
     * 直接 `throw new RuntimeException("Stub!")`（第一步 `DshPaths.loadPaths` 就抛），
     * 所以整段跑完只需微秒级、窗口无法用 sleep 稳定命中。用概率采样去"证明"一条顺序缺陷，
     * 只会得到一条时绿时红的用例 —— 那比没有更糟。顺序本身是可静态判定的，就静态判。
     *
     * 变异：把 `busyOwner.compareAndSet(owner, null)` 挪到 `busyGuard.set(false)` 之后 ⇒ 本条红
     * （见报告 §5.4；这等价于回到 HEAD 的旧顺序）。
     */
    @Test
    fun bootstrapClearsOwnerBeforeReleasingGuard() {
        val src = resolveMain("core/DshBootstrap.kt")
        assertTrue("找不到 DshBootstrap.kt（cwd=${System.getProperty("user.dir")}）", src != null)
        val text = src!!.readText().replace("\r\n", "\n")

        val finallyIdx = text.indexOf("} finally {\n            _busy.value = false")
        assertTrue("在 DshBootstrap.kt 里找不到 install() 的 finally 块（源码结构变了？）", finallyIdx >= 0)
        val block = text.substring(finallyIdx, minOf(text.length, finallyIdx + 1_200))
        val endIdx = block.indexOf("\n        }")
        val rawBody = if (endIdx >= 0) block.substring(0, endIdx) else block

        // ★ 必须先剥注释：修复点的 KDoc 里就写着 "`busyGuard.set(false)` → `busyOwner.set(null)`"
        //   这句复述（注释解释缺陷时必然会提两边的名字），不剥的话断言会命中注释而不是代码 ——
        //   本轮实测踩到过（owner=147 落在注释里、guard=122 落在真调用上，结论正好反过来）。
        val body = rawBody.lineSequence()
            .map { line -> line.substringBefore("//") }
            .joinToString("\n")

        val ownerIdx = listOf(
            "busyOwner.compareAndSet(owner, null)",
            "busyOwner.set(null)"
        ).map { body.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        val guardIdx = body.indexOf("busyGuard.set(false)")

        assertTrue("finally 里必须清 busyOwner（否则 currentOwner() 永远不回收）", ownerIdx >= 0)
        assertTrue("finally 里必须放 busyGuard（否则后续解压永远被拒）", guardIdx >= 0)
        assertTrue(
            "★ 证伪条件：清 busyOwner 必须**早于** busyGuard.set(false)（实际 owner=$ownerIdx, guard=$guardIdx）" +
                " —— 反序会让另一个线程刚写入的 owner 被上一个任务清成 null",
            ownerIdx < guardIdx
        )
    }

    // =====================================================================
    // 夹具
    // =====================================================================

    private fun instance(
        id: String,
        name: String,
        state: DshInstance.State,
        dshVersion: String?
    ): DshInstance = DshInstance(id = id, name = name, dshVersion = dshVersion, state = state)

    private fun newVm(): Pair<DshDownloadViewModel, DshInstaller> {
        val s = CoroutineScope(Dispatchers.Unconfined)
        scope = s
        val installer = DshInstaller(fakeContext(), NoopProot)
        return DshDownloadViewModel(s, installer) to installer
    }

    /** 见 R26TerminalEventTest 的同一夹具：`ContextWrapper` 的构造会调 `super()` → `Stub!`，只能跳过构造。 */
    private fun fakeContext(): Context {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val allocate: Method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ContextWrapper::class.java) as Context
    }

    private object NoopProot : ProotExecutor {
        override fun run(script: String, env: Map<String, String>, onLine: (String) -> Unit): Int =
            throw AssertionError("R31Fix211Test 不跑 proot")
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedInstalling(vm: DshDownloadViewModel, values: Set<String>) {
        val f = DshDownloadViewModel::class.java.getDeclaredField("_installingVersions")
        f.isAccessible = true
        (f.get(vm) as MutableStateFlow<Set<String>>).value = values
    }

    /** 种 `DshInstances._instances`（进程级单例；[tearDown] 负责恢复成空表）。 */
    @Suppress("UNCHECKED_CAST")
    private fun seedInstances(list: List<DshInstance>) {
        val f = DshInstances::class.java.getDeclaredField("_instances")
        f.isAccessible = true
        (f.get(DshInstances) as MutableStateFlow<List<DshInstance>>).value = list
    }

    private fun restoreInstances(list: List<DshInstance>) = seedInstances(list)

    @Suppress("UNCHECKED_CAST")
    private fun loadFailedFlow(): MutableStateFlow<Boolean> {
        val f = DshInstances::class.java.getDeclaredField("_manifestLoadFailed")
        f.isAccessible = true
        return f.get(DshInstances) as MutableStateFlow<Boolean>
    }

    private fun countMethod(): Method =
        DshInstances::class.java.getDeclaredMethod("countMutationWhileLoadFailed").apply { isAccessible = true }

    private fun counterField(): Field =
        DshInstances::class.java.getDeclaredField("mutationsWhileLoadFailed").apply { isAccessible = true }

    /**
     * 把计数清零：**兼容裸 `Int` 与 `AtomicInteger` 两种形态**。
     *
     * `AtomicInteger` 是 `static final`，反射 `set` 到 `Int` 会抛
     * `IllegalAccessException: Can not set static final …`（本轮实测踩到过）；对它的正确清法是
     * 反射调它的 `set(0)`。这样同一份测试源码在**改前（裸 Int）也能编译并跑**，红是断言红。
     */
    private fun resetCounter() {
        val f = counterField()
        when (val v = f.get(DshInstances)) {
            is Int -> f.set(null, 0)
            is AtomicInteger -> AtomicInteger::class.java.getMethod("set", Int::class.javaPrimitiveType)
                .invoke(v, 0)
            else -> throw AssertionError("未知的计数字段类型：${v?.javaClass}")
        }
    }

    /**
     * 读计数值：**兼容裸 `Int` 与 `AtomicInteger` 两种形态** —— 这是本用例能在"改前也能编译、
     * 改前断言红"的关键（否则基线红会变成编译红，按本轮纪律不算证据）。
     */
    private fun counterValue(): Int {
        val v = counterField().get(DshInstances)
        return when (v) {
            is Int -> v
            is AtomicInteger -> v.get()
            else -> throw AssertionError("未知的计数字段类型：${v?.javaClass}")
        }
    }

    /** 与其他测试同款：从 cwd 向上找，兼容 `FCL/FCL/`、`FCL/`、裸 `src/` 三种布局。 */
    private fun resolveMain(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(File(dir, "FCL/src/main/java/com/dsh/$rel"), File(dir, "src/main/java/com/dsh/$rel"))
                .firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }
}
