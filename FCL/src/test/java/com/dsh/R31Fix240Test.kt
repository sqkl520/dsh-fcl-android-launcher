package com.dsh

import com.tungsten.fclcore.fakefx.beans.binding.BooleanBinding
import com.tungsten.fclcore.fakefx.beans.binding.DoubleBinding
import com.tungsten.fclcore.fakefx.beans.binding.FloatBinding
import com.tungsten.fclcore.fakefx.beans.binding.IntegerBinding
import com.tungsten.fclcore.fakefx.beans.binding.LongBinding
import com.tungsten.fclcore.fakefx.beans.binding.ObjectBinding
import com.tungsten.fclcore.fakefx.beans.binding.StringBinding
import com.tungsten.fclcore.fakefx.collections.ListChangeListener
import com.tungsten.fclcore.fakefx.collections.ModifiableObservableListBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * R31 / fix-240 —— 三件事的长期回归守卫：
 *
 * 1. **F-37-01**：`ListChangeBuilder` 在 `removed == null` 时不得 NPE（`insertRemoved` 的两处）。
 *    穷举 `nextAdd × nextPermutation × nextRemove`，n=5 + 定向随机序列。
 * 2. **s38-dc-01**：`beginChange()`/`endChange()` 之间发生 abrupt completion 后，`changeLock`
 *    必须回到 0（后续 mutation 仍能发通知）。
 *    ★ 关键：这个行为判据原来的唯一注入器就是 F-37-01 的 NPE。F-37-01 修干净后那条路消失，
 *    所以这里改用**不依赖 F-37-01** 的注入器（见 [abruptCompletionInjectionKeepsLockBalanced] 的
 *    注释）。否则本条会随 F-37-01 的关闭而永久失去行为证据（verify-237 §3.8.3 的上报）。
 * 3. **pat-12 / slice-14**：7 个 binding 家族的缓存字段（`value` / `valid`）必须同形且都能让
 *    `isValid()` 的自旋读者终止。逐家族断言，不是只测 `IntegerBinding`。
 *
 * 为什么是行为断言而不是读 `ACC_VOLATILE`：`verify-r31-237 §3.1.2` 已证明这条能做行为断言 ——
 * JLS 17.4 的经典症状是**自旋永不终止**（不是"读到旧值"）。结构位只是必要条件。
 */
class R31Fix240Test {

    private companion object {
        /**
         * 预热次数：让自旋方法在"已 valid"的绑定上被 JIT 编译完，正式试验才测得到"提升"。
         * 20 万次实测约 0.15s/家族；少了会退化成解释执行，多花不了多少时间。
         */
        const val WARMUP = 200_000
    }

    // ==================================================================================
    // 共用：一个可以手动驱动 nextAdd / nextPermutation / nextRemove 的 ObservableList
    // ==================================================================================
    private class ProbeList(n: Int) : ModifiableObservableListBase<String>() {
        val backing = ArrayList<String>()
        val fires = AtomicInteger(0)
        @Volatile var boom = false
        @Volatile var rethrowHandler = false

        init {
            for (i in 0 until n) backing.add("v$i")
            addListener(ListChangeListener<String> { c ->
                while (c.next()) { }
                if (boom) throw IllegalStateException("listener boom")
                fires.incrementAndGet()
            })
        }

        override fun get(index: Int): String = backing[index]
        // Kotlin 把 java.util.List 的 size() 映射成属性，不能写成 `fun size()`
        override val size: Int get() = backing.size
        override fun doAdd(index: Int, element: String) { backing.add(index, element) }
        override fun doSet(index: Int, element: String): String = backing.set(index, element)
        override fun doRemove(index: Int): String = backing.removeAt(index)

        fun beginPub() = beginChange()
        fun endPub() = endChange()
        fun nextAddPub(from: Int, to: Int) = nextAdd(from, to)
        fun nextRemPub(idx: Int, e: String) = nextRemove(idx, e)
        fun nextPermPub(from: Int, to: Int, perm: IntArray) = nextPermutation(from, to, perm)
    }

    private fun allPerms(n: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        val a = IntArray(n) { it }
        fun permute(k: Int) {
            if (k == a.size) { out.add(a.clone()); return }
            for (i in k until a.size) {
                val t = a[k]; a[k] = a[i]; a[i] = t
                permute(k + 1)
                val u = a[k]; a[k] = a[i]; a[i] = u
            }
        }
        permute(0)
        return out
    }

    // ==================================================================================
    // 1) F-37-01 —— removed == null 不得 NPE
    // ==================================================================================

    /**
     * verify-237 §3.7 给的**最小复现**，逐字固化。
     * 修复前：`NullPointerException at ListChangeBuilder.insertRemoved(:80)`。
     * 修复后：不抛。
     *
     * 用**公开 mutator** `remove(0)`（而不是裸的 `nextRemove`）才是可达性论证：
     * `ModifiableObservableListBase.remove(int)` 自己会 `beginChange/endChange`，
     * 于是 nextRemove 跑在嵌套的 change 块里 —— 这正是生产代码实际走的那条路。
     */
    @Test
    fun insertRemovedToleratesNullRemovedFromPermutationRebuild() {
        val l = ProbeList(5)
        l.beginPub()
        l.nextAddPub(2, 4)
        l.nextPermPub(0, 5, intArrayOf(0, 2, 4, 1, 3))   // 重建 → SubChange.removed == null
        l.removeAt(0)                                     // 修复前在这一行 NPE
        l.endPub()
        assertEquals("公开 mutator 必须真的删掉一个元素", 4, l.size)
        assertTrue("通知必须发出", l.fires.get() >= 1)
    }

    /**
     * 穷举：全部 5! = 120 个 perm × 全部 nextAdd 区间 × 全部 remove 下标（n=5）。
     * 这是 `verify-237` 用的 6 个 perm 的子集的**超集** —— 他们的判别器只覆盖了 6 种 perm，
     * 修复单 fix-229 的判别器更是只走了一条序列。
     *
     * `IndexOutOfBoundsException` 是文档化的越界行为（`doRemove` 抛），**不算缺陷**；
     * 唯一不允许的异常是 `NullPointerException`。
     */
    @Test
    fun exhaustiveAddPermuteRemoveNeverThrowsNpe() {
        val n = 5
        val perms = allPerms(n)
        var combos = 0
        var ok = 0
        var npe = 0
        var other = 0
        val samples = ArrayList<String>()

        for (p in perms) for (af in 0..n) for (at in af + 1..n) for (ri in 0..n) {
            combos++
            val l = ProbeList(n)
            try {
                l.beginPub()
                l.nextAddPub(af, at)
                l.nextPermPub(0, n, p)
                l.nextRemPub(ri, "x")
                l.endPub()
                ok++
            } catch (e: NullPointerException) {
                npe++
                if (samples.size < 5) samples.add("add($af,$at) perm=${p.contentToString()} rm($ri) -> NPE ${e.message}")
            } catch (e: IndexOutOfBoundsException) {
                // 裸 nextRemove 不做越界检查；越界只会从末端一致性检查里冒出来，属文档化行为
            } catch (e: Throwable) {
                other++
                if (samples.size < 5) samples.add("add($af,$at) perm=${p.contentToString()} rm($ri) -> $e")
            }
        }

        assertEquals("穷举组合数必须与笛卡尔积一致（120 perm × 15 add 区间 × 6 remove 下标）", 120 * 15 * 6, combos)
        assertTrue("必须有大量组合真的跑通（否则扫描是空跑，npe=0 毫无意义）：ok=$ok", ok > 1000)
        assertEquals("纯 NPE 组合必须为 0，样本=$samples", 0, npe)
        assertEquals("不允许出现其他意外异常，样本=$samples", 0, other)
    }

    /**
     * 定向随机：任意 `nextAdd / nextRemove / nextPermutation / 公开 mutator` 序列，
     * 覆盖穷举扫描的矩形之外（多次 perm、perm 之后又 add、嵌套公开 mutator…）。
     */
    @Test
    fun randomisedSequencesNeverThrowNpe() {
        val n = 5
        val perms = allPerms(n)
        val rnd = Random(20260812L)
        var npe = 0
        var other = 0
        var combos = 0
        val samples = ArrayList<String>()

        for (iter in 0 until 3000) {
            combos++
            val seed = rnd.nextLong()
            val r = Random(seed)
            val l = ProbeList(n)
            try {
                l.beginPub()
                repeat(1 + r.nextInt(6)) {
                    when (r.nextInt(6)) {
                        0 -> { val f = r.nextInt(n + 1); val t = f + r.nextInt(n + 1 - f); if (t > f) l.nextAddPub(f, t) }
                        1 -> l.nextRemPub(r.nextInt(n + 1), "e")
                        2 -> l.nextPermPub(0, n, perms[r.nextInt(perms.size)])
                        3 -> l.add(r.nextInt(l.size + 1), "a")
                        4 -> if (l.size > 0) l.removeAt(r.nextInt(l.size))
                        5 -> if (l.size > 0) l.set(r.nextInt(l.size), "s")
                    }
                }
                l.endPub()
            } catch (e: NullPointerException) {
                npe++
                if (samples.size < 5) samples.add("seed=$seed -> NPE ${e.message}")
            } catch (e: IndexOutOfBoundsException) {
                // 文档化越界，不算缺陷
            } catch (e: Throwable) {
                other++
                if (samples.size < 5) samples.add("seed=$seed -> $e")
            }
        }

        assertEquals("随机序列数", 3000, combos)
        assertEquals("随机序列里的纯 NPE 必须为 0，样本=$samples", 0, npe)
        assertEquals("不允许出现其他意外异常，样本=$samples", 0, other)
    }

    // ==================================================================================
    // 2) s38-dc-01 —— abrupt completion 后 changeLock 必须归零
    // ==================================================================================

    /**
     * 在 `remove(int)` 的 change 块**内部**制造一次 abrupt completion，然后只看一件事：
     * 后续 mutation 还会不会发通知。
     *
     * ## 为什么不用 verify-237 的 P38h 注入器
     *
     * `P38h` 靠 `insertRemoved` 的 NPE 注入。F-37-01 修干净之后那条路没了 ——
     * 继续用它只会得到 `premise=false` 的**假绿**（注入根本没发生，测试却"通过"）。
     * `verify-237 §3.8.3` 明确要求：关闭 F-37-01 之前必须把这条行为判据换成**不依赖它**的注入器。
     *
     * ## 换用的注入器（不依赖 F-37-01）
     *
     * 两个已核实的事实组合：
     *  (a) `commit()` 先 `fireChange`、后 `clear()`，而 `finalizeSubChange` 已经把
     *      `SubChange.removed` 换成 `Collections.emptyList()`（**不可变**）——
     *      所以 `fireChange` 抛异常时，builder 手里留下的是一个 `removed` 不可变的 SubChange。
     *  (b) `ListListenerHelper` 把监听器异常交给当前线程的 `uncaughtExceptionHandler`。
     *      装一个"重抛"的 handler，异常就会真的从 `commit()/endChange()` 逃出去。
     * 于是下一次落在 `last.to` 的 `nextRemove` 会往不可变列表里 add → `UnsupportedOperationException`，
     * 位置正好在 `remove(int)` 的 change 块**内部**，与 P38h 想造的前提完全相同。
     *
     * ## 判据（三态）
     *
     * HEAD（`endChange()` 不在 finally 里）  → 锁永久 ≥1 → 后续 3 次 mutation 通知数 = 0 → WEDGED
     * CURRENT（finally）                      → 锁回 0     → 通知数 = 3                      → RECOVERED
     */
    @Test
    fun abruptCompletionInjectionKeepsLockBalanced() {
        val thread = Thread.currentThread()
        val original = thread.uncaughtExceptionHandler
        val l = ProbeList(3)
        try {
            // 注入器：监听器抛 → ListListenerHelper 交给 handler → handler 重抛 → 逃出 commit()
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                throw RuntimeException("rethrow for injector: $e")
            }

            // (1) 先造一个"已 finalize、removed 不可变"的 SubChange，并让 clear() 被跳过
            l.boom = true
            var setupEscaped = false
            try {
                l.beginPub(); l.nextAddPub(0, 2); l.endPub()
            } catch (e: RuntimeException) {
                setupEscaped = true
            }
            l.boom = false
            assertTrue("注入器前提 1：监听器异常必须真的从 endChange() 逃出（否则本用例是假绿）", setupEscaped)

            // (2) 往那个不可变 removed 里 add → 在 remove(int) 的 change 块内抛
            var injected = false
            var injectedKind = ""
            try {
                l.removeAt(2)
            } catch (e: RuntimeException) {
                injected = true
                injectedKind = e.javaClass.simpleName
            }
            assertTrue("注入器前提 2：abrupt completion 必须发生在 beginChange()/endChange() 之间（实际=$injectedKind）", injected)
            assertTrue("注入器应命中不可变列表（实际=$injectedKind）", injectedKind == "UnsupportedOperationException")

            // (3) 行为判据：锁是否归零 —— 只看后续 mutation 还发不发通知
            val before = l.fires.get()
            try { l.add(0, "probe") } catch (e: Throwable) { /* 通知计数才是判据 */ }
            val afterOne = l.fires.get() - before

            val before2 = l.fires.get()
            try { l.add(0, "q"); l.set(0, "r"); l.removeAt(0) } catch (e: Throwable) { }
            val afterThree = l.fires.get() - before2

            assertEquals("changeLock 必须回到 0：注入后的第一次 mutation 就得发通知", 1, afterOne)
            assertEquals("changeLock 必须回到 0：后续 3 次 mutation 必须全发通知", 3, afterThree)
        } finally {
            thread.uncaughtExceptionHandler = original
        }
    }

    // ==================================================================================
    // 3) pat-12 / slice-14 —— 7 个 binding 家族的缓存字段发布
    // ==================================================================================

    private class Lg : LongBinding() { override fun computeValue(): Long = 1234567890123L }
    private class Fl : FloatBinding() { override fun computeValue(): Float = 3.25f }
    private class Db : DoubleBinding() { override fun computeValue(): Double = 2.5 }
    private class Bl : BooleanBinding() { override fun computeValue(): Boolean = true }
    private class St : StringBinding() { override fun computeValue(): String = "computed" }
    private class Ob : ObjectBinding<String>() { override fun computeValue(): String = "computed" }
    private class In : IntegerBinding() { override fun computeValue(): Int = 7 }

    /**
     * 每个家族一个**独立**的紧自旋函数（`*Ready` 系列，见下）。
     *
     * ★ 这里刻意不写成 `when (b) { is Lg -> while(!b.isValid) ... }`：带类型分派的循环每轮都要
     * 走一次虚调用，JIT 无法把 `valid` 的读提到循环外，于是**缺陷版本也不会楔死**（实测：
     * 分派版本 0/8 楔死，独立函数版本 22~25/25 楔死）。也就是说分派写法会造出**假绿**。
     *
     * ★ 也不写成 `Thread { spin(b) }` 再 `runFamily(spin = { b -> spinLong(b as Lg) }, ...)` 的
     * 双层 lambda：多一层 `Any`→具体类型的 cast 之后 JIT 同样不敢提升，实测命中率从
     * 22~25/25 掉到 1~3/16（几乎全假绿）。所以循环体写在**具名函数**里，与验证方
     * `verify-r31-237` 的 `PG.java` 形状一致。
     */

    /**
     * 一个家族的行为判别器：读者自旋在 `isValid()` 上，写者 `get()`（先写 value，再写 valid）。
     * 非 volatile 时读者可以把 `valid` 的 load 提到循环外，**永不终止**（JLS 17.4 / JCIP 3.1）。
     *
     * ## 标定过程（全部是 fix-240 本机实测，不是抄来的数字）
     *
     * 这个判别器写成"起线程 → sleep 一会儿 → publish"是不稳的，两个坑各自都会造**假绿**：
     *
     * 1. **自旋方法本身还没被 JIT 编译** —— 读者在解释执行阶段就退出了，根本没跑到"提升"。
     *    解法：先用**已经 valid** 的绑定做 [WARMUP] 次预热，让自旋方法被编译完。
     * 2. **读者被调度得太晚** —— 主线程先 `get()` 再轮到读者，它第一次读就是 `true`。
     *    单纯加大 sleep 不可靠：settle=50ms 时 6 个家族的命中率只有 0~90%（同一份字节码，
     *    跑 8 次命中家族数在 2~6 之间跳）。
     *    解法：**握手** —— 读者在进循环前把自己的 `ready` 置位，主线程等到 `ready` 再 publish，
     *    不再猜时间。
     *
     * 两条都到位之后的标定（[Tune5.kt]，与本测试同形状）：
     *
     * | 树 | 每家族 20 次试验 × 6 独立进程 | 判据 |
     * |---|---|---|
     * | 去掉 volatile 的变异树（object / string / long） | **6/6 进程全部楔死**（单进程 19~20/20） | 不会假绿 |
     * | 修好的树（同 3 个家族） | **0/6 进程楔死**（0/20） | 不会假红 |
     *
     * `assertTrue(readySeen)` 是**元断言**：如果握手本身没发生（读者没能进入循环），用例必须
     * 直接失败，而不是因为"没楔死"而误判成通过。
     */
    private fun assertPublishes(name: String, warm: () -> Unit, trial: () -> Int) {
        repeat(WARMUP) { warm() }
        val trials = 20
        var wedged = 0
        var readySeen = 0
        val wedgedAt = ArrayList<Int>()
        for (i in 0 until trials) {
            val outcome = trial()
            if (outcome == 1) { wedged++; if (wedgedAt.size < 8) wedgedAt.add(i) }
            if (outcome >= 0) readySeen++
        }
        assertEquals(
            "$name: isValid() 的自旋读者必须终止（楔死 = 缓存字段没有发布边；" +
                "本家族楔死 $wedged/$trials 次，命中下标=$wedgedAt）",
            0, wedged
        )
        assertEquals("$name: 握手必须每次都发生（否则本用例是假绿）", trials, readySeen)
    }

    /**
     * 单次试验。返回 `1` = 楔死，`0` = 正常终止，`-1` = 握手没发生（读者没能进入自旋）。
     *
     * 握手：读者进循环**之前**先把 `ready` 置位，主线程自旋等它；这不依赖任何 sleep 猜测。
     * 置位之后再 `publish()`，于是"读者至少已经进过一次循环"这件事是被保证的。
     */
    private fun oneSpinTrial(ready: AtomicBoolean, spin: () -> Unit, publish: () -> Unit): Int {
        ready.set(false)
        val t = Thread { spin() }
        t.isDaemon = true
        t.start()
        val deadline = System.nanoTime() + 2_000_000_000L    // 最多等 2s 让读者进循环
        while (!ready.get() && System.nanoTime() < deadline) Thread.yield()
        if (!ready.get()) {
            t.interrupt()
            return -1
        }
        Thread.sleep(5)      // 让读者真的在循环里转几圈（此时它还没看到 valid）
        publish()            // 写 value + 写 valid
        t.join(600)
        val wedged = t.isAlive
        if (wedged) t.interrupt()
        return if (wedged) 1 else 0
    }

    /** 读者侧：先置 ready，再进紧自旋。每个家族一个具名函数（理由见上）。 */
    private fun spinLongReady(ready: AtomicBoolean, b: Lg) {
        ready.set(true); while (!b.isValid) { }
    }
    private fun spinFloatReady(ready: AtomicBoolean, b: Fl) {
        ready.set(true); while (!b.isValid) { }
    }
    private fun spinDoubleReady(ready: AtomicBoolean, b: Db) {
        ready.set(true); while (!b.isValid) { }
    }
    private fun spinBooleanReady(ready: AtomicBoolean, b: Bl) {
        ready.set(true); while (!b.isValid) { }
    }
    private fun spinStringReady(ready: AtomicBoolean, b: St) {
        ready.set(true); while (!b.isValid) { }
    }
    private fun spinObjectReady(ready: AtomicBoolean, b: Ob) {
        ready.set(true); while (!b.isValid) { }
    }
    private fun spinIntegerReady(ready: AtomicBoolean, b: In) {
        ready.set(true); while (!b.isValid) { }
    }

    @Test
    fun integerBindingPublishesCachedValue() {
        val ready = AtomicBoolean(false)
        assertPublishes(
            "IntegerBinding",
            warm = { val b = In(); b.get(); spinIntegerReady(ready, b) },
            trial = {
                val b = In()
                oneSpinTrial(ready, { spinIntegerReady(ready, b) }, { b.get() })
            })
    }

    @Test
    fun longBindingPublishesCachedValue() {
        val ready = AtomicBoolean(false)
        assertPublishes(
            "LongBinding",
            warm = { val b = Lg(); b.get(); spinLongReady(ready, b) },
            trial = {
                val b = Lg()
                oneSpinTrial(ready, { spinLongReady(ready, b) }, { b.get() })
            })
    }

    @Test
    fun floatBindingPublishesCachedValue() {
        val ready = AtomicBoolean(false)
        assertPublishes(
            "FloatBinding",
            warm = { val b = Fl(); b.get(); spinFloatReady(ready, b) },
            trial = {
                val b = Fl()
                oneSpinTrial(ready, { spinFloatReady(ready, b) }, { b.get() })
            })
    }

    @Test
    fun doubleBindingPublishesCachedValue() {
        val ready = AtomicBoolean(false)
        assertPublishes(
            "DoubleBinding",
            warm = { val b = Db(); b.get(); spinDoubleReady(ready, b) },
            trial = {
                val b = Db()
                oneSpinTrial(ready, { spinDoubleReady(ready, b) }, { b.get() })
            })
    }

    @Test
    fun booleanBindingPublishesCachedValue() {
        val ready = AtomicBoolean(false)
        assertPublishes(
            "BooleanBinding",
            warm = { val b = Bl(); b.get(); spinBooleanReady(ready, b) },
            trial = {
                val b = Bl()
                oneSpinTrial(ready, { spinBooleanReady(ready, b) }, { b.get() })
            })
    }

    @Test
    fun stringBindingPublishesCachedValue() {
        val ready = AtomicBoolean(false)
        assertPublishes(
            "StringBinding",
            warm = { val b = St(); b.get(); spinStringReady(ready, b) },
            trial = {
                val b = St()
                oneSpinTrial(ready, { spinStringReady(ready, b) }, { b.get() })
            })
    }

    @Test
    fun objectBindingPublishesCachedValue() {
        val ready = AtomicBoolean(false)
        assertPublishes(
            "ObjectBinding",
            warm = { val b = Ob(); b.get(); spinObjectReady(ready, b) },
            trial = {
                val b = Ob()
                oneSpinTrial(ready, { spinObjectReady(ready, b) }, { b.get() })
            })
    }

    /**
     * pat-12 的结构半：7 个家族的 `value` / `valid` 必须**同形**。
     * 这条钉的是"不许单点偏离"本身 —— 改前 7 个一致（都非 volatile），
     * fix-229 后变成 1 有 6 无（正是 pat-12 禁止的形态），fix-240 起 7 个一致（都有）。
     */
    @Test
    fun cachedFieldsAreUniformAcrossAllBindingFamilies() {
        val families = listOf(
            "IntegerBinding", "LongBinding", "FloatBinding", "DoubleBinding",
            "BooleanBinding", "StringBinding", "ObjectBinding"
        )
        val report = StringBuilder()
        val bad = ArrayList<String>()
        for (f in families) {
            val c = Class.forName("com.tungsten.fclcore.fakefx.beans.binding.$f")
            val v = java.lang.reflect.Modifier.isVolatile(c.getDeclaredField("value").modifiers)
            val ok = java.lang.reflect.Modifier.isVolatile(c.getDeclaredField("valid").modifiers)
            report.append("\n  $f value=$v valid=$ok")
            if (!v || !ok) bad.add(f)
        }
        assertEquals("7 个 binding 家族必须同形（缺发布边的：$bad）$report", 0, bad.size)
    }
}
