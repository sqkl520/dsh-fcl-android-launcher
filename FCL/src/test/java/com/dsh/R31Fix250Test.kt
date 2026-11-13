package com.dsh

import com.tungsten.fclcore.fakefx.beans.InvalidationListener
import com.tungsten.fclcore.fakefx.beans.Observable
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyBooleanProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyDoubleProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyFloatProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyIntegerProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyLongProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyObjectProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleObjectProperty
import org.junit.Assert
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * R31 / `fix-r31-250` —— `asObject()` 视图的 `valid` 合并门守卫。
 *
 * ## 缺陷
 *
 * `fix-r31-248` 用 (e4) 重写工厂适配器的 `asObject()` 时，把**视图原本自带的
 * 独立失效门**一起删掉了：改前视图 `$2` 有 `private boolean valid`，改后 `$1$1`
 * 只剩一个直接 `fireValueChangedEvent()` 的 lambda。后果是「监听视图、变更之间
 * 读**适配器**」这条路径的通知次数从 `1` 变成 `3`（五个类型全一样）。
 * `verify-r31-249` 的 140 格矩阵里 20 格因此偏离改前，且**从未被登记**。
 *
 * ## 本单的修法
 *
 * 在 `fix-248` 的新结构里把门加回去（不回退到 `HEAD` —— 那会丢掉 `f02` 修复）：
 *
 * ```
 * private volatile boolean valid = true;
 * private final InvalidationListener viewListener = observable -> {
 *     if (valid) { valid = false; fireValueChangedEvent(); }
 * };
 * ...
 * public T get() { valid = true; return nullableUpstream(); }
 * ```
 *
 * ## 断言口径
 *
 * 全部断言**运行时行为/加载后的类**，不碰源码文本。判别力：
 *
 * - 改前（工作区 `fix-248` 版）：[viewGateCoalescesWhenAdapterIsReadForAllFiveTypes] 五条全红
 *   （实测 `1` 变 `3`），[viewDeclaresItsOwnVolatileValidGate] 也红。
 * - 只删门、不删读数：同一组红。
 * - 只删 `get()` 里的 `valid = true`（门永远停在 `false`）：
 *   [viewGateCoalescesWhenAdapter] 仍绿，但 [viewGateRearmsAfterEachRead] 红（第 2 次变更不再通知）。
 * - 修好：两份都绿。
 *
 * ## 与其它单的关系（不回退）
 *
 * `fix-248` 的三件事在 [invariantStillHolds] / [asObjectStillCarriesNull] /
 * [asObjectStillCached] 里各留一条，本单改动后它们必须仍绿。
 */
class R31Fix250Test {

    // ---------------------------------------------------------------- 工具

    private class Counter : InvalidationListener {
        var n = 0
        override fun invalidated(observable: Observable) {
            n++
        }
    }

    private class Fixture {
        val upI = SimpleObjectProperty<Int?>(7)
        val upL = SimpleObjectProperty<Long?>(7L)
        val upD = SimpleObjectProperty<Double?>(7.5)
        val upF = SimpleObjectProperty<Float?>(7.5f)
        val upB = SimpleObjectProperty<Boolean?>(false)
        val i = ReadOnlyIntegerProperty.readOnlyIntegerProperty(upI)
        val l = ReadOnlyLongProperty.readOnlyLongProperty(upL)
        val d = ReadOnlyDoubleProperty.readOnlyDoubleProperty(upD)
        val f = ReadOnlyFloatProperty.readOnlyFloatProperty(upF)
        val b = ReadOnlyBooleanProperty.readOnlyBooleanProperty(upB)
    }

    private fun nextI(up: SimpleObjectProperty<Int?>) { up.value = (up.value ?: 0) + 1 }
    private fun nextL(up: SimpleObjectProperty<Long?>) { up.value = (up.value ?: 0L) + 1L }
    private fun nextD(up: SimpleObjectProperty<Double?>) { up.value = (up.value ?: 0.0) + 1.0 }
    private fun nextF(up: SimpleObjectProperty<Float?>) { up.value = (up.value ?: 0f) + 1f }
    private fun nextB(up: SimpleObjectProperty<Boolean?>) { up.value = !(up.value ?: false) }

    /** 类（含父类）是否声明了名为 [name]、带 `volatile` 的字段。 */
    private fun hasVolatileField(o: Any, name: String): Boolean {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try {
                val f: Field = c.getDeclaredField(name)
                return Modifier.isVolatile(f.modifiers)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return false
    }

    // ---------------------------------------- 1. 视图自己的 `valid` 门存在且 volatile

    /**
     * 五个类型的 `asObject()` 视图都必须**自己声明** `valid` 字段，且带 `volatile`。
     *
     * 改前（`fix-248` 的 `$1$1`）：五条全红 —— 那个类里根本没有 `valid`。
     * 这条同时钉住 `slice-20` 的并发要求（门必须是 `volatile`）。
     */
    @Test
    fun viewDeclaresItsOwnVolatileValidGate() {
        val x = Fixture()
        val views: List<Pair<String, ReadOnlyObjectProperty<*>>> = listOf(
            "integer" to x.i.asObject(),
            "long" to x.l.asObject(),
            "double" to x.d.asObject(),
            "float" to x.f.asObject(),
            "boolean" to x.b.asObject()
        )
        for ((name, v) in views) {
            Assert.assertTrue("$name: asObject() view must declare its own volatile `valid` gate",
                hasVolatileField(v, "valid"))
        }
    }

    // ------------------------------------------ 2. 门真的合并（改前红：1 -> 3）

    /**
     * **本单的核心断言**：监听视图、变更之间读**适配器**（不读视图），通知必须被合并成 `1`。
     *
     * 这一格正是 `fix-248` 漏测的那一格：它的 `Probe248b` 只测了「读视图」，
     * 而读视图会复位门，所以那格改前改后都是 `3`，diff 自然一致。
     *
     * 三个变更次数都测（1/2/3）—— 只测 `3` 的话，一个「只在第一次合并」的错误实现会逃逸。
     * 五个类型逐一断言（`pat-12` 同形）。
     */
    @Test
    fun viewGateCoalescesWhenAdapterIsReadForAllFiveTypes() {
        val x = Fixture()

        val ci = Counter(); x.i.asObject().addListener(ci)
        val cl = Counter(); x.l.asObject().addListener(cl)
        val cd = Counter(); x.d.asObject().addListener(cd)
        val cf = Counter(); x.f.asObject().addListener(cf)
        val cb = Counter(); x.b.asObject().addListener(cb)

        for (round in 1..3) {
            // 读的是**适配器**（原始类型属性），不是视图
            x.i.get(); x.l.get(); x.d.get(); x.f.get(); x.b.get()
            nextI(x.upI); nextL(x.upL); nextD(x.upD); nextF(x.upF); nextB(x.upB)
            Assert.assertEquals("integer: view must coalesce after $round change(s) (read adapter)",
                1, ci.n)
            Assert.assertEquals("long: view must coalesce after $round change(s)", 1, cl.n)
            Assert.assertEquals("double: view must coalesce after $round change(s)", 1, cd.n)
            Assert.assertEquals("float: view must coalesce after $round change(s)", 1, cf.n)
            Assert.assertEquals("boolean: view must coalesce after $round change(s)", 1, cb.n)
        }
    }

    /**
     * 门的**复位**方向：读一次视图后，下一次上游变更必须再通知一次。
     *
     * 这条是「只把 `valid = true;` 删掉」那种半吊子修法的守卫 ——
     * 只留门不复位时，[viewGateCoalescesWhenAdapterIsReadForAllFiveTypes] 仍然绿
     * （因为它就在测合并），但这里会永远停在 1。
     */
    @Test
    fun viewGateRearmsAfterEachRead() {
        val x = Fixture()
        val ci = Counter(); x.i.asObject().addListener(ci)
        val cl = Counter(); x.l.asObject().addListener(cl)
        val cd = Counter(); x.d.asObject().addListener(cd)
        val cf = Counter(); x.f.asObject().addListener(cf)
        val cb = Counter(); x.b.asObject().addListener(cb)

        for (round in 1..3) {
            // 读**视图**（读任何一面都该复位）
            x.i.asObject().get()
            x.l.asObject().get()
            x.d.asObject().get()
            x.f.asObject().get()
            x.b.asObject().get()
            nextI(x.upI); nextL(x.upL); nextD(x.upD); nextF(x.upF); nextB(x.upB)
            Assert.assertEquals("integer: gate must re-arm, notification #$round", round, ci.n)
            Assert.assertEquals("long: gate must re-arm, notification #$round", round, cl.n)
            Assert.assertEquals("double: gate must re-arm, notification #$round", round, cd.n)
            Assert.assertEquals("float: gate must re-arm, notification #$round", round, cf.n)
            Assert.assertEquals("boolean: gate must re-arm, notification #$round", round, cb.n)
        }
    }

    /**
     * 完全没人读时，连续 10 次变更只能通知 `1` 次（门就是为此存在的）。
     */
    @Test
    fun viewGateCoalescesTenChangesWithNoReads() {
        val x = Fixture()
        val ci = Counter(); x.i.asObject().addListener(ci)
        val cl = Counter(); x.l.asObject().addListener(cl)
        val cd = Counter(); x.d.asObject().addListener(cd)
        val cf = Counter(); x.f.asObject().addListener(cf)
        val cb = Counter(); x.b.asObject().addListener(cb)

        for (k in 1..10) {
            nextI(x.upI); nextL(x.upL); nextD(x.upD); nextF(x.upF); nextB(x.upB)
        }
        Assert.assertEquals("integer: 10 unread changes -> 1 notification", 1, ci.n)
        Assert.assertEquals("long", 1, cl.n)
        Assert.assertEquals("double", 1, cd.n)
        Assert.assertEquals("float", 1, cf.n)
        Assert.assertEquals("boolean", 1, cb.n)
    }

    // ------------------------------------ 3. fix-248 的三件事必须仍然成立（不回退）

    /**
     * `fix-248` 的成果之一：`boxed(get()).equals(getValue())` 在 `null` 上游下也成立。
     *
     * 这条会在「有人为了恢复门而把 `getValue()` 覆写加回来」时立刻红。
     */
    @Test
    fun invariantStillHolds() {
        val n = Fixture()
        n.upI.value = null; n.upL.value = null; n.upD.value = null; n.upF.value = null; n.upB.value = null
        Assert.assertEquals("int/null", Integer.valueOf(n.i.get()), n.i.value)
        Assert.assertEquals("long/null", java.lang.Long.valueOf(n.l.get()), n.l.value)
        Assert.assertEquals("double/null", java.lang.Double.valueOf(n.d.get()), n.d.value)
        Assert.assertEquals("float/null", java.lang.Float.valueOf(n.f.get()), n.f.value)
        Assert.assertEquals("boolean/null", java.lang.Boolean.valueOf(n.b.get()), n.b.value)
    }

    /**
     * `fix-248` 的成果之二（`f02`）：`asObject().get()` 在 `null` 上游返回 `null`，
     * 且与「显式零值」可区分。恢复门不得把这条变成 `0`/`false`。
     */
    @Test
    fun asObjectStillCarriesNull() {
        val n = Fixture()
        n.upI.value = null; n.upL.value = null; n.upD.value = null; n.upF.value = null; n.upB.value = null
        val vi: Int? = n.i.asObject().get()
        val vl: Long? = n.l.asObject().get()
        val vd: Double? = n.d.asObject().get()
        val vf: Float? = n.f.asObject().get()
        val vb: Boolean? = n.b.asObject().get()
        Assert.assertNull("int", vi)
        Assert.assertNull("long", vl)
        Assert.assertNull("double", vd)
        Assert.assertNull("float", vf)
        Assert.assertNull("boolean", vb)

        val zI: Int? = ReadOnlyIntegerProperty.readOnlyIntegerProperty(SimpleObjectProperty<Int>(0)).asObject().get()
        val zL: Long? = ReadOnlyLongProperty.readOnlyLongProperty(SimpleObjectProperty<Long>(0L)).asObject().get()
        val zD: Double? = ReadOnlyDoubleProperty.readOnlyDoubleProperty(SimpleObjectProperty<Double>(0.0)).asObject().get()
        val zF: Float? = ReadOnlyFloatProperty.readOnlyFloatProperty(SimpleObjectProperty<Float>(0f)).asObject().get()
        val zB: Boolean? = ReadOnlyBooleanProperty.readOnlyBooleanProperty(SimpleObjectProperty<Boolean>(false)).asObject().get()
        Assert.assertNotEquals("int: unset vs zero must stay distinguishable", vi, zI)
        Assert.assertNotEquals("long", vl, zL)
        Assert.assertNotEquals("double", vd, zD)
        Assert.assertNotEquals("float", vf, zF)
        Assert.assertNotEquals("boolean", vb, zB)
    }

    /**
     * `fix-248` 的成果之三（`f03`）：201 次 `asObject()` 返回同一实例，订阅数不增长。
     *
     * 恢复门**不能**顺手把视图做成「每次新建」。
     */
    @Test
    fun asObjectStillCached() {
        val x = Fixture()
        val firsts = listOf(x.i.asObject(), x.l.asObject(), x.d.asObject(), x.f.asObject(), x.b.asObject())
        val props: List<ReadOnlyProperty<*>> = listOf(x.i, x.l, x.d, x.f, x.b)
        for (k in 0 until 200) {
            Assert.assertSame("integer: same instance after ${k + 1} calls", firsts[0], x.i.asObject())
            Assert.assertSame("long", firsts[1], x.l.asObject())
            Assert.assertSame("double", firsts[2], x.d.asObject())
            Assert.assertSame("float", firsts[3], x.f.asObject())
            Assert.assertSame("boolean", firsts[4], x.b.asObject())
        }
        // 适配器上只有「视图 -> 适配器」这一条订阅，201 次调用不会让它增长
        for ((name, p) in listOf("integer" to props[0], "long" to props[1],
                "double" to props[2], "float" to props[3], "boolean" to props[4])) {
            Assert.assertEquals("$name: exactly one subscription on the adapter", 1, subscriptionCount(p))
        }
    }

    /** 读 `ExpressionHelper` 的失效订阅条数（运行时状态，不是源码文本）。 */
    private fun subscriptionCount(observable: Any): Int {
        var c: Class<*>? = observable.javaClass
        var helper: Any? = null
        while (c != null) {
            try {
                val f: Field = c.getDeclaredField("helper")
                f.isAccessible = true
                helper = f.get(observable)
                break
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        if (helper == null) return 0
        if (helper.javaClass.simpleName == "SingleInvalidation") return 1
        val f = helper.javaClass.getDeclaredField("invalidationSize")
        f.isAccessible = true
        return f.get(helper) as Int
    }
}
