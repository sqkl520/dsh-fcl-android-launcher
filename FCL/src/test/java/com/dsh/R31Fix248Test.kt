package com.dsh

import com.tungsten.fclcore.fakefx.beans.InvalidationListener
import com.tungsten.fclcore.fakefx.beans.Observable
import com.tungsten.fclcore.fakefx.beans.value.ChangeListener
import com.tungsten.fclcore.fakefx.beans.value.ObservableValue
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyBooleanProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyBooleanWrapper
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyDoubleProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyDoubleWrapper
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyFloatProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyFloatWrapper
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyIntegerProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyIntegerWrapper
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyLongProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyLongWrapper
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyObjectProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleObjectProperty
import org.junit.Assert
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * R31 / fix-r31-248 —— `f02` 的基类不变式破坏（`verify-236` N1）的**真修复**守卫。
 *
 * ## 契约（方案 e4）
 *
 * `fix-r31-242` 判定「`getValue()` 能返回 `null`」与「`getValue() == boxed(get())`」互斥，
 * 因而只把破裂文档化。`verify-r31-247` 证明那个排他性只在「`null` 通道必须落在 `getValue()`
 * 上」这个**未证明的前提**下成立：`asObject()` 本身就是引用类型出口。于是：
 *
 * ```
 * 通道           表达式                                      契约
 * 原始类型投影   getValue() == boxed(get())                  无条件相等（不再覆写 getValue）
 * 引用类型出口   asObject().get()                            == 原始上游，null 保留
 * 引用类型自身   asObject().getValue() == asObject().get()   相等（不把破裂推下一层）
 * 幂等           asObject() == asObject()                    同一实例
 * 通知           上游变更 -> 视图被失效                     必须发生
 * ```
 *
 * ## 断言口径
 *
 * 全部断言**运行时行为**（返回值、引用身份、通知计数、加载后类的修饰符），没有一条断言源码
 * 文本。本轮已多次证明字面串断言形同虚设 —— 把实现改坏而保留字符串即可逃逸。
 *
 * ## 判别力（不靠自称，见报告 §三态）
 *
 * - M1（删掉合并后适配器的 `asObject()` 覆写）：[asObjectCarriesNullForAllFiveTypes] 等全红。
 * - M2（删掉缓存）：[asObjectIsCachedAndIdempotentForAllFiveTypes] 全红。
 * - M3（只改 Integer、其余四个回退）：[allFiveAdaptersAreShapedIdentically] 红。
 * - 改前（`getValue()` 覆写还在）：[invariantHoldsUnconditionallyForAllFiveAdapters] 全红。
 */
class R31Fix248Test {

    // ---------------------------------------------------------------- 工具

    private class Counter : InvalidationListener {
        var n = 0
        override fun invalidated(observable: Observable) {
            n++
        }
    }

    private class ChangeCounter : ChangeListener<Any> {
        var n = 0
        var sawNull = false
        override fun changed(observable: ObservableValue<out Any>?, oldValue: Any?, newValue: Any?) {
            n++
            if (newValue == null) sawNull = true
        }
    }

    /** 五个适配器 + 各自的上游，全部以 null 起手。 */
    private class NullFixture {
        val upI = SimpleObjectProperty<Int?>(null)
        val upD = SimpleObjectProperty<Double?>(null)
        val upF = SimpleObjectProperty<Float?>(null)
        val upL = SimpleObjectProperty<Long?>(null)
        val upB = SimpleObjectProperty<Boolean?>(null)
        val i = ReadOnlyIntegerProperty.readOnlyIntegerProperty(upI)
        val d = ReadOnlyDoubleProperty.readOnlyDoubleProperty(upD)
        val f = ReadOnlyFloatProperty.readOnlyFloatProperty(upF)
        val l = ReadOnlyLongProperty.readOnlyLongProperty(upL)
        val b = ReadOnlyBooleanProperty.readOnlyBooleanProperty(upB)
    }

    /** 读加载后的类里名为 [name] 的字段是否存在且带 VOLATILE 修饰符。 */
    private fun hasVolatileField(observable: Any, name: String): Boolean {
        var c: Class<*>? = observable.javaClass
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

    /** 该类（含父类）是否声明了名为 [name]、参数表为空的方法。 */
    private fun declaresNoArgMethod(observable: Any, name: String): Boolean {
        var c: Class<*>? = observable.javaClass
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterCount == 0) return true
            }
            c = c.superclass
        }
        return false
    }

    /** 读 `helper` 上注册的失效监听器条数（运行时状态，不是源码文本）。 */
    private fun subscriptionCount(observable: Any): Int {
        var c: Class<*>? = observable.javaClass
        var found = false
        var helper: Any? = null
        while (c != null && !found) {
            try {
                val f: Field = c.getDeclaredField("helper")
                f.isAccessible = true
                helper = f.get(observable)
                found = true
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        if (!found || helper == null) return 0
        if (helper.javaClass.simpleName == "SingleInvalidation") return 1
        val f = helper.javaClass.getDeclaredField("invalidationSize")
        f.isAccessible = true
        return f.get(helper) as Int
    }

    // ------------------------------- 1. 不变式无条件成立（M-改前 全红）

    /**
     * **本单的核心断言**：五个适配器在 `null` 上游下也必须满足
     * `boxed(get()).equals(getValue())`。
     *
     * 改前（`getValue()` 覆写还在）：五条全红，实测 `get()=0 getValue()=null` 等。
     * 改后（e4）：五条全绿，`getValue()` 就是 `boxed(get())`。
     *
     * 覆盖 `null` 上游、显式零值、非零值三种状态 —— 只测一种会让「在 `null` 分支上特判」的
     * 错误实现逃逸。
     */
    @Test
    fun invariantHoldsUnconditionallyForAllFiveAdapters() {
        val z = NullFixture()

        // (a) null 上游
        Assert.assertEquals("int/null: getValue()==boxed(get())", Integer.valueOf(z.i.get()), z.i.value)
        Assert.assertEquals("long/null", java.lang.Long.valueOf(z.l.get()), z.l.value)
        Assert.assertEquals("double/null", java.lang.Double.valueOf(z.d.get()), z.d.value)
        Assert.assertEquals("float/null", java.lang.Float.valueOf(z.f.get()), z.f.value)
        Assert.assertEquals("boolean/null", java.lang.Boolean.valueOf(z.b.get()), z.b.value)

        // (b) 显式零值（这条同时钉住「null 与零值在原始投影上不可区分」这一 e4 的已知代价）
        val zero = NullFixture().apply {
            upI.value = 0; upD.value = 0.0; upF.value = 0f; upL.value = 0L; upB.value = false
        }
        Assert.assertEquals("int/zero", Integer.valueOf(zero.i.get()), zero.i.value)
        Assert.assertEquals("long/zero", java.lang.Long.valueOf(zero.l.get()), zero.l.value)
        Assert.assertEquals("double/zero", java.lang.Double.valueOf(zero.d.get()), zero.d.value)
        Assert.assertEquals("float/zero", java.lang.Float.valueOf(zero.f.get()), zero.f.value)
        Assert.assertEquals("boolean/zero", java.lang.Boolean.valueOf(zero.b.get()), zero.b.value)

        // (c) 非零值
        val nz = NullFixture().apply {
            upI.value = 7; upD.value = 7.5; upF.value = 7.5f; upL.value = 7L; upB.value = true
        }
        Assert.assertEquals("int/value", Integer.valueOf(nz.i.get()), nz.i.value)
        Assert.assertEquals("long/value", java.lang.Long.valueOf(nz.l.get()), nz.l.value)
        Assert.assertEquals("double/value", java.lang.Double.valueOf(nz.d.get()), nz.d.value)
        Assert.assertEquals("float/value", java.lang.Float.valueOf(nz.f.get()), nz.f.value)
        Assert.assertEquals("boolean/value", java.lang.Boolean.valueOf(nz.b.get()), nz.b.value)

        // (d) 原始投影仍给默认值（签名决定的，不是遗漏 —— 别把这条当成"未修"）
        Assert.assertEquals("int primitive default", 0, z.i.get())
        Assert.assertEquals("long primitive default", 0L, z.l.get())
        Assert.assertTrue("double primitive default", 0.0 == z.d.get())
        Assert.assertTrue("float primitive default", 0f == z.f.get())
        Assert.assertFalse("boolean primitive default", z.b.get())
    }

    // --------------------------------- 2. f02 语义：null 通道在 asObject()

    /**
     * **f02 的语义目标**：上游「未设置」必须与「显式零值」可区分。
     *
     * 现在由 `asObject().get()` 承担：`null` vs `0`。五个类型逐一断言，
     * 外加「两个不同上游的视图必须给出不同结果」——后半句才是可区分性的直接陈述。
     */
    @Test
    fun asObjectCarriesNullForAllFiveTypes() {
        val nulls = NullFixture()
        Assert.assertNull("int: asObject().get() on a null upstream", nulls.i.asObject().get())
        Assert.assertNull("long", nulls.l.asObject().get())
        Assert.assertNull("double", nulls.d.asObject().get())
        Assert.assertNull("float", nulls.f.asObject().get())
        Assert.assertNull("boolean", nulls.b.asObject().get())

        // 视图自己的两个投影也必须一致（不能把破裂推下一层）
        // 显式标注为可空类型：`ReadOnlyObjectProperty<Integer>.get()` 在 Kotlin 侧是平台类型，
        // 直接进 assert 会被插 null 检查，掩盖真正要验的「两边都是 null」。
        val vi: Int? = nulls.i.asObject().get()
        val vv: Int? = nulls.i.asObject().value
        Assert.assertEquals("int: view getValue()==get()", vi, vv)
        val vl: Long? = nulls.l.asObject().get()
        val vlv: Long? = nulls.l.asObject().value
        Assert.assertEquals("long: view getValue()==get()", vl, vlv)
        val vd: Double? = nulls.d.asObject().get()
        val vdv: Double? = nulls.d.asObject().value
        Assert.assertEquals("double: view getValue()==get()", vd, vdv)
        val vf: Float? = nulls.f.asObject().get()
        val vfv: Float? = nulls.f.asObject().value
        Assert.assertEquals("float: view getValue()==get()", vf, vfv)
        val vb: Boolean? = nulls.b.asObject().get()
        val vbv: Boolean? = nulls.b.asObject().value
        Assert.assertEquals("boolean: view getValue()==get()", vb, vbv)

        // 可区分性本身：未设置 vs 显式零值
        // ★ 必须显式标注成可空**包装类型**的局部变量：直接写 `asObject().get()` 是平台类型，
        //   Kotlin 会把它解析到 `assertNotEquals(String, long, long)` 重载并插入拆箱 ——
        //   于是 null 在断言之前就抛 NPE，测不到真正要验的东西。
        val uI: Int? = nulls.i.asObject().get()
        val zI: Int? = ReadOnlyIntegerProperty.readOnlyIntegerProperty(SimpleObjectProperty<Int>(0)).asObject().get()
        Assert.assertNotEquals("int: unset and explicit-zero must differ through asObject()", uI, zI)

        val uL: Long? = nulls.l.asObject().get()
        val zL: Long? = ReadOnlyLongProperty.readOnlyLongProperty(SimpleObjectProperty<Long>(0L)).asObject().get()
        Assert.assertNotEquals("long: unset vs zero", uL, zL)

        val uD: Double? = nulls.d.asObject().get()
        val zD: Double? = ReadOnlyDoubleProperty.readOnlyDoubleProperty(SimpleObjectProperty<Double>(0.0)).asObject().get()
        Assert.assertNotEquals("double: unset vs zero", uD, zD)

        val uF: Float? = nulls.f.asObject().get()
        val zF: Float? = ReadOnlyFloatProperty.readOnlyFloatProperty(SimpleObjectProperty<Float>(0f)).asObject().get()
        Assert.assertNotEquals("float: unset vs zero", uF, zF)

        val uB: Boolean? = nulls.b.asObject().get()
        val zB: Boolean? = ReadOnlyBooleanProperty.readOnlyBooleanProperty(SimpleObjectProperty<Boolean>(false)).asObject().get()
        Assert.assertNotEquals("boolean: unset vs false", uB, zB)

        // 非 null 上游必须穿透到视图
        val nz = NullFixture().apply {
            upI.value = 7; upD.value = 7.5; upF.value = 7.5f; upL.value = 7L; upB.value = true
        }
        Assert.assertEquals("int: view reads through", Integer.valueOf(7), nz.i.asObject().get())
        Assert.assertEquals("long: view reads through", java.lang.Long.valueOf(7L), nz.l.asObject().get())
        Assert.assertEquals("double: view reads through", java.lang.Double.valueOf(7.5), nz.d.asObject().get())
        Assert.assertEquals("float: view reads through", java.lang.Float.valueOf(7.5f), nz.f.asObject().get())
        Assert.assertEquals("boolean: view reads through", java.lang.Boolean.TRUE, nz.b.asObject().get())
    }

    // ------------------------------------------------ 3. f03 幂等（M2 全红）

    /**
     * `asObject()` 必须缓存：重复调用返回**同一实例**，且父属性上的订阅条数不随之增长。
     *
     * 用 `assertSame` 而不是 `assertEquals` —— 契约是「同一个对象」，值相等会被糊弄过去。
     * 订阅条数读的是活对象内部的 `invalidationSize`（运行时状态）。
     *
     * M2（删掉缓存）下五条 `assertSame` 全红、订阅数从 1 涨到 101。
     */
    @Test
    fun asObjectIsCachedAndIdempotentForAllFiveTypes() {
        val x = NullFixture()
        Assert.assertSame("int: same instance", x.i.asObject(), x.i.asObject())
        Assert.assertSame("long: same instance", x.l.asObject(), x.l.asObject())
        Assert.assertSame("double: same instance", x.d.asObject(), x.d.asObject())
        Assert.assertSame("float: same instance", x.f.asObject(), x.f.asObject())
        Assert.assertSame("boolean: same instance", x.b.asObject(), x.b.asObject())

        // 订阅不增长（M2 下会变成 101）
        Assert.assertEquals("int: exactly one subscription on the adapter", 1, subscriptionCount(x.i))
        for (k in 0 until 100) x.i.asObject()
        Assert.assertEquals("int: 101 calls must not add subscriptions", 1, subscriptionCount(x.i))
        Assert.assertEquals("long", 1, subscriptionCount(x.l))
        Assert.assertEquals("double", 1, subscriptionCount(x.d))
        Assert.assertEquals("float", 1, subscriptionCount(x.f))
        Assert.assertEquals("boolean", 1, subscriptionCount(x.b))
    }

    // --------------------------------------------- 4. 通知（视图必须收到）

    /**
     * 缓存后的视图**仍须**转发上游变更。五个类型逐一断言，且断言「重复调用 `asObject()`
     * 不会重复通知」（缓存 + 单一订阅的联合效果）。
     */
    @Test
    fun cachedAsObjectStillReceivesUpstreamInvalidations() {
        val x = NullFixture()
        val ci = Counter(); x.i.asObject().addListener(ci)
        val cl = Counter(); x.l.asObject().addListener(cl)
        val cd = Counter(); x.d.asObject().addListener(cd)
        val cf = Counter(); x.f.asObject().addListener(cf)
        val cb = Counter(); x.b.asObject().addListener(cb)

        x.upI.value = 1; x.i.get()   // 读一次复位门，保证计数可判定
        x.upL.value = 1L; x.l.get()
        x.upD.value = 1.0; x.d.get()
        x.upF.value = 1f; x.f.get()
        x.upB.value = true; x.b.get()

        Assert.assertTrue("int: view invalidated (n=${ci.n})", ci.n >= 1)
        Assert.assertTrue("long: view invalidated (n=${cl.n})", cl.n >= 1)
        Assert.assertTrue("double: view invalidated (n=${cd.n})", cd.n >= 1)
        Assert.assertTrue("float: view invalidated (n=${cf.n})", cf.n >= 1)
        Assert.assertTrue("boolean: view invalidated (n=${cb.n})", cb.n >= 1)

        // 重复调用 asObject() 不得额外触发通知
        val before = ci.n
        for (k in 0 until 20) x.i.asObject()
        Assert.assertEquals("int: repeated asObject() must not notify", before, ci.n)

        // 视图在通知后能读到新值
        Assert.assertEquals("int: view sees the new value", Integer.valueOf(1), x.i.asObject().get())
    }

    // ------------------------------ 5. 消费方后果已消失（watchValue 通道不得含 null）

    /**
     * (e4) 之后 `ChangeListener` 挂在**原始类型**属性上时永远拿到装箱后的原始值，不再出现
     * `newValue == null`。这条同时是「谁把 `getValue()` 覆写加回来，就立刻红」的守卫。
     */
    @Test
    fun primitivePropertyChangeListenerNeverSeesNull() {
        val up = SimpleObjectProperty<Int?>(5)
        val p = ReadOnlyIntegerProperty.readOnlyIntegerProperty(up)
        val chg = ChangeCounter()
        p.addListener(chg)
        up.value = null
        Assert.assertEquals("exactly one change event for 5 -> null", 1, chg.n)
        Assert.assertFalse("newValue must never be null after the fix", chg.sawNull)
    }

    // ------------------------------- 6. pat-12 同形（M3 全红）

    /**
     * 五个适配器必须**同形**：字段名、方法名、修饰符逐项相同，只有类型不同。
     *
     * 这条是 `pat-12` 的机械守卫。M3（只改 Integer、回退其余四个）下：
     * 四个未改的适配器都没有 `nullableView` 字段、没有 `nullableUpstream()` 方法，
     * 于是四条断言红。
     */
    @Test
    fun allFiveAdaptersAreShapedIdentically() {
        val x = NullFixture()
        val adapters = listOf(
            "integer" to x.i,
            "long" to x.l,
            "double" to x.d,
            "float" to x.f,
            "boolean" to x.b
        )
        for ((name, a) in adapters) {
            Assert.assertTrue("$name: adapter must declare nullableUpstream()",
                declaresNoArgMethod(a, "nullableUpstream"))
            Assert.assertTrue("$name: adapter must declare asObject()", declaresNoArgMethod(a, "asObject"))
            Assert.assertTrue("$name: adapter's valid gate must be volatile", hasVolatileField(a, "valid"))
            // 缓存字段必须真实存在于加载后的类上（私有 + 引用类型，不靠源码文本）
            var c: Class<*>? = a.javaClass
            var found = false
            while (c != null && !found) {
                found = try { c.getDeclaredField("nullableView"); true } catch (e: NoSuchFieldException) { false }
                c = c.superclass
            }
            Assert.assertTrue("$name: 'nullableView' cache field must exist on the adapter", found)
        }
    }

    // ------------------------ 7. 结构守卫：getValue() 不得被适配器覆写

    /**
     * `getValue()` 覆写是**缺陷的物理来源**。用反射确认适配器的匿名类没有自己声明
     * `getValue()` —— 这是加载后的类属性，不是源码文本断言；把覆写加回来会立刻红。
     */
    @Test
    fun factoryAdapterDoesNotRedeclareGetValue() {
        val x = NullFixture()
        for ((name, a) in listOf(
            "integer" to x.i, "long" to x.l, "double" to x.d, "float" to x.f, "boolean" to x.b
        )) {
            val declares = a.javaClass.declaredMethods.any { it.name == "getValue" && it.parameterCount == 0 }
            Assert.assertFalse("$name: the factory adapter must NOT redeclare getValue()", declares)
        }
    }

    /**
     * `volatile` 门必须仍在两处（`slice-20-concurrency-valid-flag` 不回退）。
     *
     * 覆盖：工厂适配器自身 与 它 `asObject()` 出来的视图。
     */
    @Test
    fun validGateStaysVolatileAtBothSites() {
        val x = NullFixture()
        Assert.assertTrue("int: adapter gate", hasVolatileField(x.i, "valid"))
        Assert.assertTrue("long: adapter gate", hasVolatileField(x.l, "valid"))
        Assert.assertTrue("double: adapter gate", hasVolatileField(x.d, "valid"))
        Assert.assertTrue("float: adapter gate", hasVolatileField(x.f, "valid"))
        Assert.assertTrue("boolean: adapter gate", hasVolatileField(x.b, "valid"))

        // newAsObject() 路径（Wrapper 的只读视图）
        Assert.assertTrue("wrapper int: asObject gate", hasVolatileField(ReadOnlyIntegerWrapper(1).readOnlyProperty.asObject(), "valid"))
        Assert.assertTrue("wrapper long: asObject gate", hasVolatileField(ReadOnlyLongWrapper(1L).readOnlyProperty.asObject(), "valid"))
        Assert.assertTrue("wrapper double: asObject gate", hasVolatileField(ReadOnlyDoubleWrapper(1.0).readOnlyProperty.asObject(), "valid"))
        Assert.assertTrue("wrapper float: asObject gate", hasVolatileField(ReadOnlyFloatWrapper(1f).readOnlyProperty.asObject(), "valid"))
        Assert.assertTrue("wrapper boolean: asObject gate", hasVolatileField(ReadOnlyBooleanWrapper(true).readOnlyProperty.asObject(), "valid"))
    }

    // -------------------------- 8. 周围路径不回退（Wrapper / newAsObject 仍工作）

    /**
     * 生产实际使用的 `ReadOnly*Wrapper.getReadOnlyProperty().asObject()` 路径不受影响：
     * 幂等、取值正确、通知正常。这条是「修工厂适配器不得弄坏 Wrapper」的不回退守卫。
     */
    @Test
    fun wrapperAsObjectPathIsUnaffected() {
        val wi = ReadOnlyIntegerWrapper(3)
        val vi: ReadOnlyObjectProperty<Int> = wi.readOnlyProperty.asObject()
        Assert.assertSame("wrapper int: idempotent", vi, wi.readOnlyProperty.asObject())
        Assert.assertEquals("wrapper int: value", Integer.valueOf(3), vi.get())
        val c = Counter(); vi.addListener(c)
        wi.set(4)
        Assert.assertTrue("wrapper int: view notified", c.n >= 1)
        Assert.assertEquals("wrapper int: view reads new value", Integer.valueOf(4), vi.get())

        val wb = ReadOnlyBooleanWrapper(false)
        Assert.assertEquals("wrapper boolean: value", java.lang.Boolean.FALSE, wb.readOnlyProperty.asObject().get())
        val wd = ReadOnlyDoubleWrapper(1.5)
        Assert.assertEquals("wrapper double: value", java.lang.Double.valueOf(1.5), wd.readOnlyProperty.asObject().get())
        val wf = ReadOnlyFloatWrapper(1.5f)
        Assert.assertEquals("wrapper float: value", java.lang.Float.valueOf(1.5f), wf.readOnlyProperty.asObject().get())
        val wl = ReadOnlyLongWrapper(1L)
        Assert.assertEquals("wrapper long: value", java.lang.Long.valueOf(1L), wl.readOnlyProperty.asObject().get())
    }

    /**
     * 「上游本身就是 `ReadOnly*Property` 时直接透传」的分支不受影响（不建适配器）。
     */
    @Test
    fun passthroughBranchIsUnchanged() {
        val w = ReadOnlyIntegerWrapper(5)
        val ro = w.readOnlyProperty
        val again = ReadOnlyIntegerProperty.readOnlyIntegerProperty(ro)
        Assert.assertSame("an already-read-only property must be returned as-is", ro, again)
        Assert.assertEquals("value", Integer.valueOf(5), again.get())
        Assert.assertEquals("invariant on the passthrough branch", Integer.valueOf(again.get()), again.value)
    }
}
