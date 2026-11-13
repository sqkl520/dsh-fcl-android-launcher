package com.dsh

import com.tungsten.fclcore.fakefx.beans.InvalidationListener
import com.tungsten.fclcore.fakefx.beans.Observable
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
import com.tungsten.fclcore.fakefx.beans.property.SimpleBooleanProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleDoubleProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleFloatProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleIntegerProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleLongProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleObjectProperty
import com.tungsten.fclcore.fakefx.beans.value.ChangeListener
import com.tungsten.fclcore.fakefx.beans.value.ObservableValue
import org.junit.Assert
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * round31 / 波次四 / `fix-r31-228` —— `slice-19` f02/f03 与 `slice-20-concurrency-valid-flag` 的判别用例。
 *
 * ## 断言口径
 *
 * 全部断言**运行时状态**（返回值、引用身份、通知计数、加载后的类/字段修饰符），
 * 没有一条断言源码文本。理由：本轮 `verify-210` / `verify-211` / `verify-213` 已四次证明
 * 字面串断言形同虚设 —— 把实现改坏而保留字符串即可逃逸。
 *
 * ## 三条 finding 各自的判别策略
 *
 * - `slice-19-logic-f02`：`get()` 的返回类型是原始类型，**物理上无法**携带「无值」，所以审计要求的
 *   「可区分」只能在 `getValue()`（`ObservableValue<Number>` / `ObservableValue<Boolean>`，
 *   引用类型）上成立。断言写成「上游 null 与上游零值的 `getValue()` 必须不同」，而不是
 *   「`get()` 不该返回 0」—— 后者改不动（签名限制），改签名的代价远超本条 LOW/MEDIUM 后果。
 * - `slice-19-logic-f03`：**主断言是引用身份**（重复调用必须拿到同一实例），这是修复后
 *   用户可观察的契约本身；**副断言**是父属性上注册的失效监听器条数不随调用次数增长
 *   （运行时反射读活对象内部的 `invalidationSize`，不是源码文本断言）。
 * - `slice-20-concurrency-valid-flag`：`volatile` 是 JMM 保证，没有确定性的「跑一次就红」的
 *   行为判别（这也是本条只能 LOW 的原因）。因此判别写成「**加载后的类**里 `valid` 字段带
 *   VOLATILE 修饰符」——这是编译产物的运行时属性，不是源码串；「改前红」由还原备份重编后
 *   重新加载类取得。
 *
 * ## `G1` 精神的适用（验收标准写成「通知计数保持不变」）
 *
 * `G1` 的三条硬约束点名的是 `BooleanPropertyBase`（`r31-s17-dc-01`），本单不碰那个文件。
 * 但 `slice-20` 的 `valid` 是**同一设计模式的另一处实例**，所以按同样的口径验收：
 * 下面 [notificationCountsOnAdapterAreUnchangedByThisFix] 把「无监听器 / 仅 InvalidationListener /
 * 挂 ChangeListener」三条路径的通知计数逐一钉在**改前实测值**上（`1/1/2` 与 `1/1/3` 的两条路径），
 * 而不是写成「连续两次 set 都应通知」。`valid` 门**未被移除、未被移位**。
 */
class R31Fix228Test {

    // ------------------------------------------------------------------ 工具

    /** 只数通知次数。 */
    private class Counter : InvalidationListener {
        var n = 0
        override fun invalidated(observable: Observable) {
            n++
        }
    }

    private class ChangeCounter : ChangeListener<Number> {
        var n = 0
        override fun changed(observable: ObservableValue<out Number>?, oldValue: Number?, newValue: Number?) {
            n++
        }
    }

    /**
     * 读 `ReadOnly*PropertyBase.helper` 上注册的**失效监听器**条数。
     *
     * 这是**运行时状态**断言（读活对象的字段），不是源码文本断言。名字依赖
     * `ExpressionHelper` 的内部字段（`SingleInvalidation` 特例 + `invalidationSize`），
     * 若上游改名会在此处**编译期/运行期显式失败**，不会静默变绿。
     */
    private fun invalidationSubscriptionCount(observable: Any): Int {
        var c: Class<*>? = observable.javaClass
        var found = false
        var helper: Any? = null
        // 注意：不能用 "helper == null" 作循环条件 —— 字段存在但值仍为 null（尚未注册监听器）时
        // 会原地死循环。必须用独立的 found 标记来区分「没找到字段」与「找到了但值是 null」。
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

    /** 读加载后的类里名为 [name] 的字段是否带 VOLATILE 修饰符。 */
    private fun isFieldVolatile(observable: Any, name: String): Boolean {
        var c: Class<*>? = observable.javaClass
        while (c != null) {
            try {
                val f: Field = c.getDeclaredField(name)
                return Modifier.isVolatile(f.modifiers)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw AssertionError("field '$name' not found on ${observable.javaClass.name} or its superclasses")
    }

    // ---------------------------------------------------- f02：null 与零值可区分

    /**
     * `slice-19-logic-f02` —— 五个原始类型适配器必须让「上游未设置」与「上游是零值」可区分。
     *
     * ## 通道在 `asObject()`

     * `fix-r31-228` 最初把这条可区分性放在 `getValue()` 上（返回 `null`），代价是打破基类不变式
     * `boxed(get()).equals(getValue())`；`fix-r31-242` 判定二者互斥并只做了文档化。
     * `fix-r31-248` 用方案 (e4) 同时拿到了两者：
     *
     * - `getValue()` 恢复为基类的 `return get()`，不变式**无条件**成立（`getValue()` 不再为 `null`）；
     * - `null` 通道搬到引用类型出口 `asObject()`，它的 `get()` 直读原始上游，`null` 保留。
     *
     * 所以本用例现在断言的是 **(a) `getValue()` 与零值不可区分（这是 e4 的已知代价，明写出来）
     * + (b) `asObject().get()` 可区分**。改名与 `R31Fix248Test` 一起保证任何"只改一半"的实现都会红。
     *
     * 注意 `get()` 仍返回原始默认值 —— 这是**签名决定的**，不是遗漏：
     * `boolean get()` / `double get()` 无处存放 `null`。
     */
    @Test
    fun nullUpstreamIsDistinguishableFromZeroThroughAsObject() {
        // --- Integer
        val iNull = ReadOnlyIntegerProperty.readOnlyIntegerProperty(SimpleObjectProperty<Int?>(null))
        val iZero = ReadOnlyIntegerProperty.readOnlyIntegerProperty(SimpleObjectProperty<Int>(0))
        Assert.assertNull("f02/int: unset upstream must surface as null via asObject().get()", iNull.asObject().get())
        Assert.assertEquals("f02/int: explicit zero must still read 0", Integer.valueOf(0), iZero.asObject().get())
        Assert.assertEquals("f02/int: get() keeps the primitive default", 0, iNull.get())
        // e4 的已知代价：原始类型投影上「未设置」与「零值」不可区分
        Assert.assertEquals("f02/int: getValue() is the primitive default (e4 trade-off)", Integer.valueOf(0), iNull.value)

        // --- Long
        val lNull = ReadOnlyLongProperty.readOnlyLongProperty(SimpleObjectProperty<Long?>(null))
        val lZero = ReadOnlyLongProperty.readOnlyLongProperty(SimpleObjectProperty<Long>(0L))
        Assert.assertNull("f02/long: unset upstream must surface as null via asObject().get()", lNull.asObject().get())
        Assert.assertEquals("f02/long: explicit zero must still read 0", java.lang.Long.valueOf(0L), lZero.asObject().get())

        // --- Double
        val dNull = ReadOnlyDoubleProperty.readOnlyDoubleProperty(SimpleObjectProperty<Double?>(null))
        val dZero = ReadOnlyDoubleProperty.readOnlyDoubleProperty(SimpleObjectProperty<Double>(0.0))
        Assert.assertNull("f02/double: unset upstream must surface as null via asObject().get()", dNull.asObject().get())
        Assert.assertEquals("f02/double: explicit zero must still read 0.0", java.lang.Double.valueOf(0.0), dZero.asObject().get())

        // --- Float
        val fNull = ReadOnlyFloatProperty.readOnlyFloatProperty(SimpleObjectProperty<Float?>(null))
        val fZero = ReadOnlyFloatProperty.readOnlyFloatProperty(SimpleObjectProperty<Float>(0f))
        Assert.assertNull("f02/float: unset upstream must surface as null via asObject().get()", fNull.asObject().get())
        Assert.assertEquals("f02/float: explicit zero must still read 0f", java.lang.Float.valueOf(0f), fZero.asObject().get())

        // --- Boolean
        val bNull = ReadOnlyBooleanProperty.readOnlyBooleanProperty(SimpleObjectProperty<Boolean?>(null))
        val bFalse = ReadOnlyBooleanProperty.readOnlyBooleanProperty(SimpleObjectProperty<Boolean>(false))
        Assert.assertNull("f02/boolean: unset upstream must surface as null via asObject().get()", bNull.asObject().get())
        Assert.assertEquals("f02/boolean: explicit false must still read false", java.lang.Boolean.FALSE, bFalse.asObject().get())
        Assert.assertEquals("f02/boolean: get() keeps the primitive default", false, bNull.get())

        // 反过来：两个上游各自的 asObject() 必须可区分（这是 f02 的原始主张本身）
        Assert.assertNotEquals(
            "f02: unset and explicit-zero must be distinguishable through asObject()",
            iNull.asObject().get(), iZero.asObject().get()
        )
    }

    /**
     * `slice-19-logic-f02` 的不回退守卫：非 null 上游的**数值投影**必须与改前逐位相同。
     *
     * 为什么需要这条：f02 的修法是在 `getValue()` 上保留 `null`，一个错误的实现（例如顺手
     * 把 `get()` 也改成装箱返回、或把 `intValue()` 路径改掉）会破坏所有算术/格式化调用点。
     * 这里用 `get()` / `getValue()` / `longValue()` 三个投影交叉钉住同一个上游值。
     */
    @Test
    fun nonNullUpstreamProjectionsAreUnchanged() {
        val i = ReadOnlyIntegerProperty.readOnlyIntegerProperty(SimpleObjectProperty<Int>(7))
        Assert.assertEquals("int get()", 7, i.get())
        Assert.assertEquals("int getValue()", Integer.valueOf(7), i.value)
        Assert.assertEquals("int longValue()", java.lang.Long.valueOf(7L), java.lang.Long.valueOf(i.longValue()))
        Assert.assertTrue("int doubleValue()", 7.0 == i.doubleValue())

        val d = ReadOnlyDoubleProperty.readOnlyDoubleProperty(SimpleObjectProperty<Double>(2.5))
        Assert.assertTrue("double get()", 2.5 == d.get())
        Assert.assertEquals("double getValue()", java.lang.Double.valueOf(2.5), d.value)

        val b = ReadOnlyBooleanProperty.readOnlyBooleanProperty(SimpleObjectProperty<Boolean>(true))
        Assert.assertEquals("boolean get()", true, b.get())
        Assert.assertEquals("boolean getValue()", java.lang.Boolean.TRUE, b.value)

        // ★ 必须显式披露的一处连带行为：`asObject()` 的 get() 委托 `getValue()`，
        //   所以引用类型的 asObject() 视图现在也保留 null（改前它是 0/false）。
        //   这不是意外，而是同一缺陷在下一层的同形表现：`ReadOnlyObjectProperty<Integer>`
        //   的返回类型本来就能携带 null，若在这里collapse，f02 就只是被推进了一层。
        //   可达性：`.asObject()` 全仓生产调用点 = 0（仅本测试目录 1 处），故影响面为零。
        val asObjNull = ReadOnlyIntegerProperty
            .readOnlyIntegerProperty(SimpleObjectProperty<Int?>(null))
            .asObject()
        Assert.assertNull("f02/asObject: reference-typed view preserves null too", asObjNull.get())
        Assert.assertNull("f02/asObject: and its getValue() agrees", asObjNull.value)

        // 对照：非 null 上游的 asObject() 视图仍返回数值
        val asObj7 = ReadOnlyIntegerProperty
            .readOnlyIntegerProperty(SimpleObjectProperty<Int>(7))
            .asObject()
        Assert.assertEquals("f02/asObject: non-null upstream still reads through", 7, asObj7.get())
    }

    // ------------------------------------------------ f03：asObject() 幂等

    /**
     * `slice-19-logic-f03` —— 同一属性上重复调用 `asObject()` 必须返回**同一实例**。
     *
     * 改前实测：`false`（每次 `new` 一个匿名子类）。改后：`true`。
     * 这条是用户可观察的契约，且与同族 `ReadOnly*Wrapper.getReadOnlyProperty()` 的
     * 惰性单例写法一致（`ReadOnlyDoubleWrapper.java:64-69` 等）。
     */
    @Test
    fun asObjectIsIdempotentAcrossAllFivePrimitiveAdapters() {
        val wb = ReadOnlyBooleanWrapper(true)
        val o1 = wb.readOnlyProperty.asObject()
        val o2 = wb.readOnlyProperty.asObject()
        Assert.assertSame("f03/boolean: repeated asObject() must return the same instance", o1, o2)

        val wi = ReadOnlyIntegerWrapper(1)
        Assert.assertSame("f03/int", wi.readOnlyProperty.asObject(), wi.readOnlyProperty.asObject())

        val wl = ReadOnlyLongWrapper(1L)
        Assert.assertSame("f03/long", wl.readOnlyProperty.asObject(), wl.readOnlyProperty.asObject())

        val wd = ReadOnlyDoubleWrapper(1.0)
        Assert.assertSame("f03/double", wd.readOnlyProperty.asObject(), wd.readOnlyProperty.asObject())

        val wf = ReadOnlyFloatWrapper(1.0f)
        Assert.assertSame("f03/float", wf.readOnlyProperty.asObject(), wf.readOnlyProperty.asObject())

        // 静态工厂产物也必须是惰性单例（它走的是同一套 asObject()）
        val fa = ReadOnlyIntegerProperty.readOnlyIntegerProperty(SimpleObjectProperty<Int>(1))
        Assert.assertSame("f03/factory-adapter", fa.asObject(), fa.asObject())
    }

    /**
     * `slice-19-logic-f03` 的泄漏面 —— 父属性上注册的失效订阅**条数有界**。
     *
     * 改前实测：每调一次 `asObject()` 父属性上多 1 条订阅（102 次调用后 = 102 条，无上限）。
     * 改后：恒定 1 条。
     *
     * 断言读的是活对象内部的 `invalidationSize`（运行时状态），不是源码文本。
     */
    @Test
    fun repeatedAsObjectDoesNotAccumulateSubscriptionsOnParent() {
        val w = ReadOnlyIntegerWrapper(1)
        val p = w.readOnlyProperty
        Assert.assertEquals("f03: no subscription before the first call", 0, invalidationSubscriptionCount(p))
        p.asObject()
        Assert.assertEquals("f03: exactly one subscription after the first call", 1, invalidationSubscriptionCount(p))
        p.asObject()
        Assert.assertEquals("f03: second call must not add a second subscription", 1, invalidationSubscriptionCount(p))
        for (k in 0 until 100) {
            p.asObject()
        }
        Assert.assertEquals(
            "f03: 102 calls must still leave exactly one subscription (was 102 before the fix)",
            1,
            invalidationSubscriptionCount(p)
        )
    }

    /**
     * `slice-19-logic-f03` 的不回退守卫：缓存后返回的包装对象**仍然**逐次转发失效通知，
     * 且只转发一次（不是「缓存了所以不通知了」这种把缺陷换成死对象的最坏修法）。
     */
    @Test
    fun cachedAsObjectStillForwardsInvalidationsExactlyOnce() {
        val w = ReadOnlyIntegerWrapper(1)
        val wrapper: ReadOnlyObjectProperty<Int> = w.readOnlyProperty.asObject()

        val inv = Counter()
        wrapper.addListener(inv)
        w.set(2)
        Assert.assertEquals("f03: cached wrapper must forward the first invalidation", 1, inv.n)

        w.readOnlyProperty.asObject() // 再调一次，不应改变任何计数
        w.set(3)
        // 包装器自己的 valid 门把它合并成 1 次（直到有人读），与改前同形
        Assert.assertEquals("f03: re-reading asObject() must not duplicate notifications", 1, inv.n)

        wrapper.get()
        w.set(4)
        Assert.assertEquals("f03: after a read the gate re-arms, so the next set notifies", 2, inv.n)
    }

    // ------------------------------- slice-20：valid 门跨线程可见性 + 计数不变守卫

    /**
     * `slice-20-concurrency-valid-flag` —— 两处 `valid` 门控标记必须带 `volatile`。
     *
     * 断言对象是**加载后的类**里该字段的修饰符（运行时产物属性），不是源码文本。
     * 「改前红」的取得方式：还原备份 → 重新编译 → 重新加载类，该断言失败。
     *
     * 覆盖两处实例：静态工厂里的匿名子类，与 `asObject()` 里的匿名子类。
     */
    @Test
    fun validGateFlagIsVolatileAtBothSitesInEveryAdapter() {
        // 工厂适配器
        val fi = ReadOnlyIntegerProperty.readOnlyIntegerProperty(SimpleObjectProperty<Int>(1))
        Assert.assertTrue(
            "slice-20/int: factory adapter's valid flag must be volatile",
            isFieldVolatile(fi, "valid")
        )
        val fb = ReadOnlyBooleanProperty.readOnlyBooleanProperty(SimpleObjectProperty<Boolean>(true))
        Assert.assertTrue("slice-20/boolean: factory adapter", isFieldVolatile(fb, "valid"))
        val fd = ReadOnlyDoubleProperty.readOnlyDoubleProperty(SimpleObjectProperty<Double>(1.0))
        Assert.assertTrue("slice-20/double: factory adapter", isFieldVolatile(fd, "valid"))
        val ff = ReadOnlyFloatProperty.readOnlyFloatProperty(SimpleObjectProperty<Float>(1f))
        Assert.assertTrue("slice-20/float: factory adapter", isFieldVolatile(ff, "valid"))
        val fl = ReadOnlyLongProperty.readOnlyLongProperty(SimpleObjectProperty<Long>(1L))
        Assert.assertTrue("slice-20/long: factory adapter", isFieldVolatile(fl, "valid"))

        // asObject() 适配器
        val wi = ReadOnlyIntegerWrapper(1)
        Assert.assertTrue("slice-20/int: asObject adapter", isFieldVolatile(wi.readOnlyProperty.asObject(), "valid"))
        val wb = ReadOnlyBooleanWrapper(true)
        Assert.assertTrue("slice-20/boolean: asObject adapter", isFieldVolatile(wb.readOnlyProperty.asObject(), "valid"))
        val wd = ReadOnlyDoubleWrapper(1.0)
        Assert.assertTrue("slice-20/double: asObject adapter", isFieldVolatile(wd.readOnlyProperty.asObject(), "valid"))
        val wf = ReadOnlyFloatWrapper(1f)
        Assert.assertTrue("slice-20/float: asObject adapter", isFieldVolatile(wf.readOnlyProperty.asObject(), "valid"))
        val wl = ReadOnlyLongWrapper(1L)
        Assert.assertTrue("slice-20/long: asObject adapter", isFieldVolatile(wl.readOnlyProperty.asObject(), "valid"))
    }

    /**
     * `G1` 精神（验收标准写成「通知计数保持不变」）—— 本单对 `valid` 门**只加 `volatile`，
     * 不移除门、不移位**，所以三条路径的通知计数必须与**改前实测值**逐格相同。
     *
     * 改前实测（`Baseline` 探针，本机）：
     * - 仅 `InvalidationListener`：第一次 `set` = 1，第二次 `set`（未读）= 1，`get()` 后再 `set` = 2
     * - `InvalidationListener` + `ChangeListener` 同挂：1/1 → 2/2 → 3/3
     * - 零监听器：属性从不通知，`get()` 后 `valid` 复位（下面用「先读再 set」间接钉住）
     *
     * 任一格偏离即失败。**注意口径**：验的是「计数不变」，不是「两次 set 都应通知」。
     */
    @Test
    fun notificationCountsOnAdapterAreUnchangedByThisFix() {
        // ---- 路径 A：仅 InvalidationListener（G1-b 第一条路径）
        //
        // ★ 每次 set 之后都读一次**上游源**（srcA.get()），这是本用例的关键：
        //   上游 SimpleObjectProperty 自己也有同形的 valid 门。若不读源，第二次 set 会在**源**那里
        //   就被吞掉，适配器的门根本没被触及 —— 那样这条用例对「适配器的门是否被移除」毫无判别力
        //   （实测：M4 变异移除适配器的门后，旧写法仍然全绿）。读源之后，源的门复位，
        //   适配器成为唯一的合并点，计数才真正钉住适配器的门。
        val srcA = SimpleObjectProperty<Int>(1)
        val a = ReadOnlyIntegerProperty.readOnlyIntegerProperty(srcA)
        val invA = Counter()
        a.addListener(invA)
        srcA.set(2); srcA.get()
        Assert.assertEquals("G1-b/invalidation: first set", 1, invA.n)
        srcA.set(3); srcA.get()
        Assert.assertEquals("G1-b/invalidation: second set is coalesced by the adapter valid gate", 1, invA.n)
        a.get()
        srcA.set(4); srcA.get()
        Assert.assertEquals("G1-b/invalidation: reading the adapter re-arms its gate", 2, invA.n)

        // ---- 路径 B：InvalidationListener + ChangeListener 同挂（G1-b 第二条路径）
        //
        // 同样读源。注意这条路径的计数与路径 A **不同**（1/1 → 2/2 → 3/3 而不是 1/1 → 1 → 2）：
        // 挂了 ChangeListener 后，ExpressionHelper.Generic 在派发时自己调 observable.getValue()
        // 来算 old/new，这个 get() 会在**同一次派发内**把适配器的 valid 复位。
        // 这正是 G1-b 要求两条路径分开验收的原因 —— 同一个门在两条路径上表现不同。
        val srcB = SimpleObjectProperty<Int>(1)
        val b = ReadOnlyIntegerProperty.readOnlyIntegerProperty(srcB)
        val invB = Counter()
        val chgB = ChangeCounter()
        b.addListener(invB)
        b.addListener(chgB)
        srcB.set(2); srcB.get()
        Assert.assertEquals("G1-b/both: inv after first set", 1, invB.n)
        Assert.assertEquals("G1-b/both: chg after first set", 1, chgB.n)
        srcB.set(3); srcB.get()
        Assert.assertEquals("G1-b/both: inv after second set", 2, invB.n)
        Assert.assertEquals("G1-b/both: chg after second set", 2, chgB.n)
        b.get()
        srcB.set(4); srcB.get()
        Assert.assertEquals("G1-b/both: inv after read+set", 3, invB.n)
        Assert.assertEquals("G1-b/both: chg after read+set", 3, chgB.n)

        // ---- 路径 C：零监听器。无可观察的通知计数，只能钉住两件可观察的事：
        // ①多次 set 期间不抛异常、②最终的读数仍是最后一次写入的值（门只影响通知，不影响取值）。
        // 这样本轮若有人把门改成会「锁死取值」的形态，这条会红。
        val srcC = SimpleObjectProperty<Int>(1)
        val c = ReadOnlyIntegerProperty.readOnlyIntegerProperty(srcC)
        for (k in 0 until 5) {
            srcC.set(k + 10); srcC.get()
        }
        Assert.assertEquals("G1-b/zero-listener: value still tracks the last write", 14, c.get())

        // 挂上监听器后第一次 set 必须通知（门在 get() 处被复位过）
        val invC = Counter()
        c.addListener(invC)
        srcC.set(9); srcC.get()
        Assert.assertEquals("G1-b/zero-listener-then-subscribe: gate re-armed by the read", 1, invC.n)
    }
}
