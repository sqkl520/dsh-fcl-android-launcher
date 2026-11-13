package com.dsh

import com.tungsten.fclcore.fakefx.beans.InvalidationListener
import com.tungsten.fclcore.fakefx.beans.Observable
import com.tungsten.fclcore.fakefx.beans.binding.BooleanExpression
import com.tungsten.fclcore.fakefx.beans.binding.DoubleExpression
import com.tungsten.fclcore.fakefx.beans.binding.FloatExpression
import com.tungsten.fclcore.fakefx.beans.binding.IntegerExpression
import com.tungsten.fclcore.fakefx.beans.binding.LongExpression
import com.tungsten.fclcore.fakefx.beans.binding.ObjectExpression
import com.tungsten.fclcore.fakefx.beans.binding.StringExpression
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyBooleanProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyDoubleProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyFloatProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyIntegerProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyIntegerWrapper
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyLongProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleBooleanProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleDoubleProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleFloatProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleIntegerProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleLongProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleObjectProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleStringProperty
import com.tungsten.fclcore.fakefx.beans.value.ChangeListener
import com.tungsten.fclcore.fakefx.beans.value.ObservableValue
import org.junit.Assert
import org.junit.Test

/**
 * R31 / fix-242 —— `verify-236` 的 **N1**（`f02` 覆写 `getValue()` 打破基类不变式）的长期守卫。
 *
 * ## 历史与本轮的修订（必读）
 *
 * `slice-19-logic-f02` 的 `fix-r31-228` 在五个原始类型只读适配器的静态工厂里覆写了
 * `getValue()` 让 `null` 上游透出 `null`，因而打破了本仓 `*Expression` 家族的
 * `getValue() == boxed(get())` 不变式。`fix-r31-242` 的裁决曾是：**这个破裂无法消除**，
 * 因为「让 `null` 可见」与「`getValue()` 等于 `get()`」在原始类型投影上互为排他
 * （见 [xorTheoremHoldsAcrossAllFiveAdapters]），于是只把它文档化、用测试钉死受限不变式。
 *
 * **该裁决已被 `fix-r31-248` 修订。** `verify-r31-247` 指出：排他性只在「`null` 通道必须落在
 * `getValue()` 上」这个**未证明的附加前提**下成立；而 `asObject()` 就是一个引用类型出口，
 * 让**它**直读原始上游即可同时拿到两者。`fix-r31-248` 据此实现方案 (e4)：
 *
 * ```
 * getValue()               == boxed(get())   无条件成立（不再覆写）
 * asObject().get()         == 原始上游，null 保留
 * asObject() 重复调用       == 同一实例（缓存，f03）
 * 上游变更                  -> 视图收到失效通知（弱监听器）
 * ```
 *
 * 因此本文件里原先的三条「受限不变式 / null 值域 / orElse fallback」用例**已按新契约改写**，
 * 新的主守卫是 `R31Fix248Test`。保留本文件的理由是：它是 N1 的历史记录，
 * 且 [xorTheoremHoldsAcrossAllFiveAdapters] 仍然是对"为什么不能在 `getValue()` 上放 null"的
 * 正确陈述 —— 只是在 (e4) 下它不再是**唯一**选项。
 *
 * ## 断言口径
 *
 * 全部断言**运行时行为**（返回值/通知计数/是否抛异常），没有一条断言源码文本 ——
 * 本轮已多次证明字面串断言会把「改了实现但留住字符串」放过去。
 * 这样即使将来有人把 javadoc 删了，只要行为退回 f02 之前，这里就会红。
 */
class R31Fix242Test {

    // ---------------------------------------------------------------- 工具

    /** 记录 ChangeListener 收到的 old/new（供 N1 值域断言用）。 */
    private class Recorder : ChangeListener<Number> {
        var calls = 0
        var oldValue: Number? = 999
        var newValue: Number? = 999
        var observedNull = false

        /** 消费方拆箱抛出的异常（null 时应有），以及它是不是 NullPointerException。 */
        var unboxThrew: Throwable? = null
        var unboxThrewNpe = false

        override fun changed(observable: ObservableValue<out Number>?, oldValue: Number?, newValue: Number?) {
            calls++
            this.oldValue = oldValue
            this.newValue = newValue
            if (newValue == null) observedNull = true
            try {
                // 消费方的典型写法：把 newValue 当数值直接拆箱。
                // 显式经 java.lang.Number 走 Java 语义（newValue 是 Kotlin 的 Number?，
                // 直接用 !! 抛的是 KotlinNullPointerException，会掩盖「Java 消费者」的真实形态）。
                val asJava: java.lang.Number = newValue as java.lang.Number
                asJava.intValue()
            } catch (t: Throwable) {
                unboxThrew = t
                unboxThrewNpe = t is NullPointerException
            }
        }
    }

    private class Counter : InvalidationListener {
        var n = 0
        override fun invalidated(observable: Observable) {
            n++
        }
    }

    /** 五个适配器 + 各自的上游，集中构造，避免每个用例重复。 */
    private class Fixture {
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

    // ----------------------------------------- 1. XOR 定理（本裁决的根）

    /**
     * **排他性定理**：对原始类型投影，「`getValue()` 能返回 `null`」与「`getValue()` 恒等于
     * `boxed(get())`」不可能同时成立。
     *
     * 证明是纯代数的：`get()` 的声明类型是 `int`/`double`/`float`/`long`/`boolean`，
     * **没有任何值能表示「无值」**；所以 `fetchValue()` 返回 `null` 时，
     * `boxed(get())` 必然是某个具体值（`0`/`0.0`/`false`），二者不可能相等。
     * 这条断言用「恰好一个成立」的 XOR 形式写出，正是为了说明
     * **不存在第三种方案** —— 任何一个声称「既能让 null 可见、又保持不变式」的补丁都必然在这里红。
     *
     * **它不用于区分「修前/修后」**：f02 之前是 `nullVisible=false, invariantHolds=true`，
     * f02 之后是 `nullVisible=true, invariantHolds=false`，两个状态都满足 XOR，
     * 所以这条在两种状态下都绿（实测见报告 §mutations）。它的作用是**钉住排他性本身**：
     * 若有人提交「让 getValue() 返回 null 的同时还让 boxed(get()) 等于它」的补丁，
     * 这里会红。真正区分「是否已修 f02」的是下面三条
     * （[restrictedInvariantNullUpstreamPrimitiveDefaultAndNull]、
     * [changeListenerSeesNullOnTransitionAndIsIsolated]、[orElseUsesFallbackForNullUpstream]、
     * [asObjectViewPropagatesNullForAllFiveTypes]），M1 回退实验里它们 4 条全红。
     */
    @Test
    fun xorTheoremHoldsAcrossAllFiveAdapters() {
        val x = Fixture()
        // null 上游：null 可见 XOR 不变式成立
        Assert.assertTrue(
            "int: nullVisible XOR invariantHolds",
            (x.i.value == null) xor Integer.valueOf(x.i.get()).equals(x.i.value)
        )
        Assert.assertTrue(
            "double: nullVisible XOR invariantHolds",
            (x.d.value == null) xor java.lang.Double.valueOf(x.d.get()).equals(x.d.value)
        )
        Assert.assertTrue(
            "float: nullVisible XOR invariantHolds",
            (x.f.value == null) xor java.lang.Float.valueOf(x.f.get()).equals(x.f.value)
        )
        Assert.assertTrue(
            "long: nullVisible XOR invariantHolds",
            (x.l.value == null) xor java.lang.Long.valueOf(x.l.get()).equals(x.l.value)
        )
        Assert.assertTrue(
            "boolean: nullVisible XOR invariantHolds",
            (x.b.value == null) xor java.lang.Boolean.valueOf(x.b.get()).equals(x.b.value)
        )

        // 非 null 上游：两侧必须同时成立（不变式恢复、且没有 null）
        x.upI.value = 7
        x.upD.value = 7.0
        x.upF.value = 7f
        x.upL.value = 7L
        x.upB.value = true
        Assert.assertTrue("int: non-null -> invariant holds", Integer.valueOf(x.i.get()).equals(x.i.value))
        Assert.assertTrue("double: non-null -> invariant holds", java.lang.Double.valueOf(x.d.get()).equals(x.d.value))
        Assert.assertTrue("float: non-null -> invariant holds", java.lang.Float.valueOf(x.f.get()).equals(x.f.value))
        Assert.assertTrue("long: non-null -> invariant holds", java.lang.Long.valueOf(x.l.get()).equals(x.l.value))
        Assert.assertTrue("boolean: non-null -> invariant holds", java.lang.Boolean.valueOf(x.b.get()).equals(x.b.value))
    }

    // --------------------------------- 2. 受限不变式（文档化的新契约）

    /**
     * 受限不变式的前半：**上游非 null 时，两个投影必须逐位相同**。
     *
     * 这是「不回退守卫」：它挡住了「为了修 N1 而把 `getValue()` 改回折叠」以外的
     * 另一种错误实现 —— 顺手把 `get()` 或 `longValue()` 等数值投影也改坏。
     */
    @Test
    fun restrictedInvariantNonNullUpstreamBothProjectionsAgree() {
        val x = Fixture()
        x.upI.value = 5
        x.upD.value = 5.0
        x.upF.value = 5f
        x.upL.value = 5L
        x.upB.value = true

        Assert.assertEquals("int get()", 5, x.i.get())
        Assert.assertEquals("int getValue()", Integer.valueOf(5), x.i.value)
        Assert.assertEquals("long get()", 5L, x.l.get())
        Assert.assertEquals("long getValue()", java.lang.Long.valueOf(5L), x.l.value)
        Assert.assertTrue("double get()", 5.0 == x.d.get())
        Assert.assertEquals("double getValue()", java.lang.Double.valueOf(5.0), x.d.value)
        Assert.assertTrue("float get()", 5f == x.f.get())
        Assert.assertEquals("float getValue()", java.lang.Float.valueOf(5f), x.f.value)
        Assert.assertTrue("boolean get()", x.b.get())
        Assert.assertEquals("boolean getValue()", java.lang.Boolean.TRUE, x.b.value)
    }

    /**
     * 受限不变式的后半：**上游为 `null` 时，`get()` 给类型默认值、`getValue()` 给 `null`**。
     *
     * 五个类型逐一断言 —— 这正是 `pat-12` 的「同形、不单点偏离」要求：
     * 只处理 `Integer` 一处会让契约在族内分叉，必须五族一致。
     */
    @Test
    fun restrictedInvariantNullUpstreamPrimitiveDefaultAndNull() {
        val x = Fixture()
        Assert.assertEquals("int get()", 0, x.i.get())
        // fix-248 (e4)：getValue() 回到基类的 `return get()` —— 与 get() 同值，不再为 null。
        Assert.assertEquals("int getValue()", Integer.valueOf(0), x.i.value)
        Assert.assertEquals("long get()", 0L, x.l.get())
        Assert.assertEquals("long getValue()", java.lang.Long.valueOf(0L), x.l.value)
        Assert.assertTrue("double get()", 0.0 == x.d.get())
        Assert.assertEquals("double getValue()", java.lang.Double.valueOf(0.0), x.d.value)
        Assert.assertTrue("float get()", 0f == x.f.get())
        Assert.assertEquals("float getValue()", java.lang.Float.valueOf(0f), x.f.value)
        Assert.assertFalse("boolean get()", x.b.get())
        Assert.assertEquals("boolean getValue()", java.lang.Boolean.FALSE, x.b.value)

        // ...而 null 通道搬到了 asObject()：五个类型逐一确认，pat-12 同形。
        Assert.assertNull("int asObject().get()", x.i.asObject().get())
        Assert.assertNull("long asObject().get()", x.l.asObject().get())
        Assert.assertNull("double asObject().get()", x.d.asObject().get())
        Assert.assertNull("float asObject().get()", x.f.asObject().get())
        Assert.assertNull("boolean asObject().get()", x.b.asObject().get())
    }

    // -------------------- 3. N1 曾披露的消费方后果（fix-248 后已消失，改写为回归守卫）

    /**
     * 曾经的后果 (1)：`ChangeListener` 的 `newValue` 值域含 `null`。
     *
     * `fix-r31-242` 把「上游 `5 -> null` 时监听器拿到 `newV=null`、对它拆箱会抛 NPE」写成
     * 已声明的契约。`fix-r31-248` 用 (e4) 把 `null` 从 `getValue()` 挪到 `asObject()` 之后，
     * **这条后果不存在了**：`ChangeListener` 挂在原始类型属性上时永远拿到装箱后的原始值。
     *
     * 本用例现在的职责是**反向守卫**：谁要是把 `getValue()` 覆写加回来（`null` 重新流进
     * `ChangeListener` / `orElse`），这里立刻红。断言写成"拆箱不得抛异常 + newValue 不得为 null"。
     */
    @Test
    fun changeListenerNeverSeesNullAfterTheFix() {
        val up = SimpleObjectProperty<Int?>(5)
        val p = ReadOnlyIntegerProperty.readOnlyIntegerProperty(up)
        val rec = Recorder()
        p.addListener(rec)

        up.value = null

        Assert.assertEquals("listener must be invoked exactly once for 5 -> null", 1, rec.calls)
        Assert.assertEquals("oldValue must be the previous 5", Integer.valueOf(5), rec.oldValue)
        Assert.assertNotNull("newValue must never be null after fix-248 (it is the primitive default)", rec.newValue)
        Assert.assertEquals("newValue is the collapsed primitive default", Integer.valueOf(0), rec.newValue)
        Assert.assertFalse("the recorder must not have observed a null", rec.observedNull)
        Assert.assertNull("unboxing newValue must not throw", rec.unboxThrew)

        // 隔离性 / 后续通知能力不变。
        val counter = Counter()
        p.addListener(counter)
        up.value = 9
        Assert.assertTrue("adapter still notifies after a null->value transition", counter.n > 0)
        Assert.assertEquals("and it now reports the non-null value", Integer.valueOf(9), p.value)
    }

    /**
     * 曾经的后果 (2)：`orElse(fallback)` 在上游为 `null` 时返回 **fallback**。
     *
     * (e4) 之后 `getValue()` 不再为 `null`，所以 `orElse` 回到「永不被 fallback 顶替」的
     * 常规语义。这条现在是反向守卫：若有人把 `getValue()` 覆写加回来，`orElse` 会重新开始
     * 返回 fallback，这里红。
     */
    @Test
    fun orElseNeverFallsBackAfterTheFix() {
        val i = ReadOnlyIntegerProperty.readOnlyIntegerProperty(SimpleObjectProperty<Int?>(null))
        Assert.assertEquals("orElse(42) on a null upstream must yield 0, not 42", Integer.valueOf(0), i.orElse(42).value)

        val d = ReadOnlyDoubleProperty.readOnlyDoubleProperty(SimpleObjectProperty<Double?>(null))
        Assert.assertEquals("double orElse(1.5)", java.lang.Double.valueOf(0.0), d.orElse(1.5).value)

        val b = ReadOnlyBooleanProperty.readOnlyBooleanProperty(SimpleObjectProperty<Boolean?>(null))
        Assert.assertEquals("boolean orElse(true)", java.lang.Boolean.FALSE, b.orElse(true).value)

        // 对照：非 null 上游时 orElse 也必须原样透传。
        val i2 = ReadOnlyIntegerProperty.readOnlyIntegerProperty(SimpleObjectProperty<Int>(0))
        Assert.assertEquals("orElse must not override an explicit zero", Integer.valueOf(0), i2.orElse(42).value)
    }

    /**
     * 后果 (3)：`asObject()` 的引用类型视图保留 `null` —— **这一条不仅保留，而且现在是
     * `f02` 的唯一通道**。
     *
     * `fix-r31-242` 时代它只是「`get()` 委托 `getValue()` 的连带现象」；(e4) 之后它是
     * **有意的实现选择**：视图直读原始上游（不经过 `getValue()`），所以既能带 `null`，
     * 又不让 `getValue()` 破不变式。五个类型逐一断言 = `pat-12` 同形要求。
     */
    @Test
    fun asObjectViewPropagatesNullForAllFiveTypes() {
        Assert.assertNull(
            "int asObject().get()",
            ReadOnlyIntegerProperty.readOnlyIntegerProperty(SimpleObjectProperty<Int?>(null)).asObject().get()
        )
        Assert.assertNull(
            "double asObject().get()",
            ReadOnlyDoubleProperty.readOnlyDoubleProperty(SimpleObjectProperty<Double?>(null)).asObject().get()
        )
        Assert.assertNull(
            "float asObject().get()",
            ReadOnlyFloatProperty.readOnlyFloatProperty(SimpleObjectProperty<Float?>(null)).asObject().get()
        )
        Assert.assertNull(
            "long asObject().get()",
            ReadOnlyLongProperty.readOnlyLongProperty(SimpleObjectProperty<Long?>(null)).asObject().get()
        )
        Assert.assertNull(
            "boolean asObject().get()",
            ReadOnlyBooleanProperty.readOnlyBooleanProperty(SimpleObjectProperty<Boolean?>(null)).asObject().get()
        )

        // asObject() 自己也必须满足它那一层的 getValue() == get()（引用类型，折叠无必要）
        val asObj = ReadOnlyIntegerProperty
            .readOnlyIntegerProperty(SimpleObjectProperty<Int?>(null))
            .asObject()
        Assert.assertNull("asObject(): get() is null", asObj.get())
        Assert.assertNull("asObject(): getValue() agrees (reference type, no divergence needed)", asObj.value)
    }

    // ------------------- 4. 周围家族仍保持无条件不变式（防止「修一处、坏一族」）

    /**
     * 本仓其余所有 `*Expression` / `Simple*Property` / `*Wrapper` 仍满足
     * `getValue() == boxed(get())`。这条用例的作用是反向界定：破裂**只允许**存在于
     * 五个工厂适配器的 `null` 分支，不允许扩散到家族其它成员。
     *
     * 若将来有人为了「统一」而把 `*Expression.getValue()` 一起改掉（方案 c），这里会立刻红。
     */
    @Test
    fun restOfFamilyKeepsUnconditionalInvariant() {
        Assert.assertEquals(
            "IntegerExpression",
            Integer.valueOf(IntegerExpression.integerExpression(SimpleIntegerProperty(3)).get()),
            IntegerExpression.integerExpression(SimpleIntegerProperty(3)).value
        )
        Assert.assertEquals(
            "DoubleExpression",
            java.lang.Double.valueOf(DoubleExpression.doubleExpression(SimpleDoubleProperty(3.0)).get()),
            DoubleExpression.doubleExpression(SimpleDoubleProperty(3.0)).value
        )
        Assert.assertEquals(
            "FloatExpression",
            java.lang.Float.valueOf(FloatExpression.floatExpression(SimpleFloatProperty(3f)).get()),
            FloatExpression.floatExpression(SimpleFloatProperty(3f)).value
        )
        Assert.assertEquals(
            "LongExpression",
            java.lang.Long.valueOf(LongExpression.longExpression(SimpleLongProperty(3L)).get()),
            LongExpression.longExpression(SimpleLongProperty(3L)).value
        )
        Assert.assertEquals(
            "BooleanExpression",
            java.lang.Boolean.valueOf(BooleanExpression.booleanExpression(SimpleBooleanProperty(true)).get()),
            BooleanExpression.booleanExpression(SimpleBooleanProperty(true)).value
        )
        // 引用类型家族（本来就能携带 null，因此根本不需要破裂）
        Assert.assertEquals(
            "ObjectExpression",
            ObjectExpression.objectExpression(SimpleObjectProperty<String?>(null)).get(),
            ObjectExpression.objectExpression(SimpleObjectProperty<String?>(null)).value
        )
        Assert.assertEquals(
            "StringExpression",
            StringExpression.stringExpression(SimpleStringProperty(null)).get(),
            StringExpression.stringExpression(SimpleStringProperty(null)).value
        )
        // 生产实际使用的 Wrapper 路径：永不为 null 上游，不变式必须完好
        Assert.assertEquals(
            "ReadOnlyIntegerWrapper view",
            Integer.valueOf(ReadOnlyIntegerWrapper(3).readOnlyProperty.get()),
            ReadOnlyIntegerWrapper(3).readOnlyProperty.value
        )

        // fix-248 之后，五个工厂适配器**自己**也回到这个集合里（null 上游亦然）。
        // 这是本轮最重要的一条：它把「破裂只允许存在于 null 分支」这句话彻底作废。
        val f = Fixture()
        Assert.assertEquals("factory adapter/int", Integer.valueOf(f.i.get()), f.i.value)
        Assert.assertEquals("factory adapter/long", java.lang.Long.valueOf(f.l.get()), f.l.value)
        Assert.assertEquals("factory adapter/double", java.lang.Double.valueOf(f.d.get()), f.d.value)
        Assert.assertEquals("factory adapter/float", java.lang.Float.valueOf(f.f.get()), f.f.value)
        Assert.assertEquals("factory adapter/boolean", java.lang.Boolean.valueOf(f.b.get()), f.b.value)
    }
}
