package com.dsh

import com.tungsten.fclcore.fakefx.beans.InvalidationListener
import com.tungsten.fclcore.fakefx.beans.Observable
import com.tungsten.fclcore.fakefx.beans.property.BooleanPropertyBase
import com.tungsten.fclcore.fakefx.beans.property.ListPropertyBase
import com.tungsten.fclcore.fakefx.beans.property.LongProperty
import com.tungsten.fclcore.fakefx.beans.property.ObjectProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyBooleanProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyIntegerProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleBooleanProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleDoubleProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleFloatProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleIntegerProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleListProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleLongProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleObjectProperty
import com.tungsten.fclcore.fakefx.beans.value.ChangeListener
import com.tungsten.fclcore.fakefx.beans.value.ObservableValue
import com.tungsten.fclcore.fakefx.beans.value.ObservableValueBase
import com.tungsten.fclcore.fakefx.collections.FXCollections
import com.tungsten.fclcore.fakefx.collections.ObservableList
import org.junit.Assert
import org.junit.Test
import java.util.Arrays

/**
 * round31 / 波次四 / `fix-r31-222` —— `slice-17` dc-01~03 与 `slice-18` contract-01~03 的判别用例。
 *
 * ## 断言原则
 *
 * 全部断言**运行时值**（通知计数、返回值、引用身份、异常消息），没有一条断言源码文本。
 * 本轮 `fix-210` M2 / `verify-210` 的 6 种逃脱 / `verify-211` M6-M7 / `verify-213` OFA-OFB
 * 都证明了字面串断言形同虚设 —— 只要把断言写成「源码里含某字符串」，把实现改坏而保留字符串即可逃逸。
 *
 * ## 每条用例的口径
 *
 * - `r31-s17-dc-01`（`G1-a`/`G1-b` 硬约束）：验的是「**无监听器路径的通知计数保持不变**」，
 *   不是「连续两次 set 都应发出通知」。`InvalidationListener` 与 `ChangeListener` **两条路径分开断言**，
 *   因为 `ExpressionHelper.Generic.fireValueChangedEvent()` 里 change 分支由 `changed` 门控
 *   （`:321-323`），两条路径的期望次数本就不同。
 * - `r31-s17-dc-02`：见 [dc02] 的方法注释 —— 本条的修法被 `G1-a`（只动 `BooleanPropertyBase`，
 *   与同族 9 个基类同形）与「必须真的修好」两条约束架在中间，用例记录**当前的**可判定边界。
 * - `r31-s17-dc-03`：六格归一化映射 + 通知计数，把 `WritableValue.setValue` 契约的**实现侧**钉住。
 * - `r31-s18-contract-01`：`getBean()` 的**引用身份**（`assertSame`，不是 `equals`）。
 * - `r31-s18-contract-02`：用一个**值等价 `equals`** 的 `ObservableValue` 构造分歧形态，
 *   断言属性是否真的跟随第二个源（`List` 侧作为不回退守卫）。
 * - `r31-s18-contract-03`：`@Override` 是编译期事实，行为侧只断言「size/empty 通知不回归」。
 */
class R31Fix222Test {

    // ------------------------------------------------------------------ 工具

    /** 只数通知次数；用于 `InvalidationListener` 路径。 */
    private class CountInvalidation : InvalidationListener {
        var n = 0
        override fun invalidated(observable: Observable) {
            n++
        }
    }

    /**
     * 一个重写 `equals` 为**值等价**的 `ObservableValue`。
     *
     * 存在的理由：`contract-02` 的分歧只有在「传入与当前源 `equals` 相等但实例不同」时才可观察。
     * 用默认 identity `equals` 的 observable 测不出任何差异 —— 那正是审计 alternatives #2 说的
     * 「两套判据运行时不可区分」。要让断言有判别力，必须自己造出这个形态。
     */
    private class KeyedValue(private val key: String, private var v: Any?) : ObservableValueBase<Any?>() {
        override fun getValue(): Any? = v
        fun push(newValue: Any?) {
            v = newValue
            fireValueChangedEvent()
        }

        override fun equals(other: Any?): Boolean =
            other is R31Fix222Test.KeyedValue && other.key == key

        override fun hashCode(): Int = key.hashCode()
    }

    /**
     * `BooleanPropertyBase` 的最小具体子类，带一个可数的 `invalidated()` 钩子。
     *
     * 为什么不用 `SimpleBooleanProperty`：`invalidated()` 在基类里是空实现，`SimpleBooleanProperty`
     * 也不重写它，因此无法从外部观察「钩子被调了几次」。`dc-01` 的缺陷正是「`invalidated()` 与
     * `fireValueChangedEvent()` 一起被门吞掉」，两条出口必须分别可数。
     */
    private class CountingBooleanProperty(initial: Boolean) : BooleanPropertyBase(initial) {
        var invalidatedCount = 0
            private set

        override fun invalidated() {
            invalidatedCount++
        }

        override fun getBean(): Any = BEAN
        override fun getName(): String = "flag"
    }

    private companion object {
        const val BEAN = "bean"
    }

    // ============================================================ r31-s17-dc-01

    /**
     * `dc-01` / `G1-b` 路径一：**无监听器**时，通知计数必须保持改前实测值。
     *
     * `G1` 的验收标准原文是「无监听器路径的通知计数保持不变（验证『不引入重复触发』），
     * 而非『连续两次 set 都应发出通知』」。因此这里断的是 **1 次**（两次 `set` 合并成一次），
     * 不是 2 次 —— 写成 2 次会让本用例在合规实现上变红，正是 Arbiter 禁止的那种写法。
     *
     * 第二段断言 `get()` 会重新武装门（这是 `get()` 里 `valid = true` 的唯一职责），
     * 于是随后的 `set` 必须再通知一次。两段合起来才能区分「门在起作用」与「门被删掉」：
     * 门被删掉时第一段变成 2（红），把 `valid = true` 从 `get()` 里删掉时第二段变成 1（红）。
     */
    @Test
    fun dc01_zeroListener_notificationCountIsCoalesced() {
        val p = CountingBooleanProperty(false)
        p.set(true)
        p.set(false)
        Assert.assertEquals(
            "dc-01: two consecutive set() with no get() between must coalesce into exactly 1 " +
                "notification on the zero-listener path (G1 acceptance: count unchanged, not per-set)",
            1, p.invalidatedCount
        )

        p.get()
        p.set(true)
        Assert.assertEquals(
            "dc-01: get() must re-arm the gate, so the next set() notifies again (2 total)",
            2, p.invalidatedCount
        )
    }

    /**
     * `dc-01` / `G1-b` 路径二：**仅 `InvalidationListener`** 时，同样必须 1 次（合并）。
     *
     * 审计的原主张是「第二次 set 完全不可见」；这里如实断言**合并**而非**丢失**：
     * 合并是 `if (valid)` 门的既定性质（且 `dc-01` 的修复被明令禁止改动该门），
     * 丢掉「合并」这层就等于给一个不存在的行为变化背书。
     */
    @Test
    fun dc01_invalidationListener_notificationCountIsCoalesced() {
        val p = CountingBooleanProperty(false)
        val listener = CountInvalidation()
        p.addListener(listener)

        p.set(true)
        p.set(false)
        Assert.assertEquals(
            "dc-01: invalidation-only path must also coalesce two set() into 1 notification",
            1, listener.n
        )

        p.get()
        p.set(true)
        Assert.assertEquals(
            "dc-01: after get() re-arms the gate the next set() notifies (2 total on invalidation path)",
            2, listener.n
        )
    }

    /**
     * `dc-01` / `G1-b` 路径三：**挂 `ChangeListener`** 时计数是 2，不是 1 —— 这条路径**不受**该门控。
     *
     * 依据 `ExpressionHelper.java:319-333`：change 分支先取 `oldValue`，再 `currentValue = observable.getValue()`
     * 并做 `changed` 比较。`observable.getValue()` 会走 `get()`，**把 `valid` 复位**，于是第二次 `set`
     * 进门时门是开的。`pat-08` 已经用这条闭合事实把 `dc-01` 的影响面压到「零监听器或仅 InvalidationListener」，
     * `G1-b` 要求把这条差异**单独验收**。
     *
     * 判别力：如果谁把 `get()` 里的 `valid = true` 挪走或去掉，这条会从 2 掉到 1（红）；
     * 如果谁把 `fireValueChangedEvent()` 挪到门外面，这条仍会是 2（不会红），但零监听器那条会变红。
     */
    @Test
    fun dc01_changeListener_pathIsNotGated_byTheValidFlag() {
        val p = CountingBooleanProperty(false)
        var changes = 0
        p.addListener(ChangeListener<Boolean> { _, _, _ -> changes++ })

        p.set(true)
        p.set(false)
        Assert.assertEquals(
            "dc-01: the change-listener path bypasses the valid gate because ExpressionHelper reads " +
                "getValue() before comparing, so two set() yield 2 change events",
            2, changes
        )

        p.set(true)
        Assert.assertEquals("dc-01: the third set() must also notify on the change path", 3, changes)
    }

    // ============================================================ r31-s17-dc-02

    /**
     * `dc-02` 的**第二个失败链**：`bind() → unbind() → set(不同值)` 的通知必须到达。
     *
     * 这条链的根因在 `unbind()`：它把 `value` 从源刷新了，却不复位 `valid`。于是 `unbind()` 之后
     * 属性停在 invalid 态，随后的 `set()` 进门时被 `if (valid)` 挡住。
     *
     * > **本轮状态：`NOT_FIXED`，见 `docs/reports/round31/round31-04-fix-222.md` §3。**
     * > 修 `unbind()` 要在**同族 10 个基类**里各加一行（`Boolean/Double/Float/Integer/Long/Object/
     * > String/List/Map/Set`），因为 `node` 实测这 10 个类的 `unbind()` **全部**缺 `valid` 复位、
     * > 且全部有 `if (valid)` 门 —— 只改 `BooleanPropertyBase` 就是 `G1-a` 明令禁止的「单点偏离」。
     * > 本用例因此断言**当前（未修）行为**且标记为已知缺陷，不让它假装绿：
     * > 一旦有人按 §3 的 family 修法改好，本断言会变红并提醒把期望值改成 1。
     */
    @Test
    fun dc02_knownDefect_setAfterUnbindIsSilentlySwallowed() {
        val p = CountingBooleanProperty(false)
        var notifications = 0
        p.addListener(InvalidationListener { notifications++ })

        p.bind(SimpleBooleanProperty(true))
        Assert.assertEquals("precondition: bind() itself must notify once", 1, notifications)

        p.unbind()
        // unbind() 不改 valid（缺陷本体），所以下面的 set 仍被门挡住
        p.set(false)

        Assert.assertEquals(
            "KNOWN DEFECT (r31-s17-dc-02, NOT_FIXED in fix-222): set() after unbind() is swallowed " +
                "because unbind() refreshes `value` without re-arming `valid`. When the family-wide fix " +
                "lands (see report §3), this expectation must be raised from 1 to 2.",
            1, notifications
        )
        Assert.assertFalse("precondition: property must be unbound here", p.isBound)
    }

    /**
     * `dc-02` 的**第一个失败链**：`set()`（门关）→ `bind(新源)` 时 `markInvalid()` 空转。
     *
     * `bind()` 末尾**有**一次 `markInvalid()` 调用，它是正确的；问题只在门的当前状态。
     * 无监听器时，门的开关不影响 `invalidated()` 的**可观察**次数（没人听），所以这条链
     * 唯一能判定的后果是：换源之后 `isBound()` 为真、`get()` 返回**新源**的值。
     *
     * 这两条断言在改前改后都成立，它们的作用是**钉住「换源确实发生」这个前提** ——
     * 如果将来有人用「`bind()` 直接早退」来规避缺陷，这两条会红。
     */
    @Test
    fun dc02_bindAfterSet_actuallySwitchesSource_evenWhenGateIsClosed() {
        val p = CountingBooleanProperty(false)
        p.set(true)                       // 门关，且此后不调 get()
        Assert.assertEquals("precondition: first set() notifies exactly once", 1, p.invalidatedCount)

        p.bind(SimpleBooleanProperty(true))
        Assert.assertTrue("bind() must leave the property bound when the gate is closed", p.isBound)
        Assert.assertTrue("the bound value must come from the new source", p.get())

        // 判据：bind() 的通知能否到达，取决于「加监听」与「那次把 valid 关掉的 set」的先后。
        // ExpressionHelper.addListener() 第二行就调 observable.getValue()，会复位 valid。
        //   · 加监听**之后**再 set：valid 在 bind 时是 false -> 被门挡住（缺陷形态）；
        //   · set **之后**再加监听：addListener 复位 valid -> bind 能通知（正常形态）。
        // 这两条一起把缺口钉在「监听器已在场、且中间那次 set 之后无人 get()」的形态上。
        val gated = CountingBooleanProperty(false)
        val gatedListener = CountInvalidation()
        gated.addListener(gatedListener)
        gated.set(true)
        Assert.assertEquals("precondition: set() after addListener notifies once", 1, gatedListener.n)
        gated.bind(SimpleBooleanProperty(true))
        Assert.assertEquals(
            "KNOWN DEFECT (r31-s17-dc-02, NOT_FIXED in fix-222): with the listener already registered, " +
                "bind() is gated and produces no notification. When the family-wide fix lands " +
                "(report §3), this expectation must be raised from 1 to 2.",
            1, gatedListener.n
        )

        val reArmed = CountingBooleanProperty(false)
        reArmed.set(true)
        val reArmedListener = CountInvalidation()
        reArmed.addListener(reArmedListener)
        Assert.assertEquals("precondition: adding a listener must not by itself notify", 0, reArmedListener.n)
        reArmed.bind(SimpleBooleanProperty(true))
        Assert.assertEquals(
            "when the listener is added after the invalidating set(), addListener re-arms valid " +
                "and bind() does notify",
            1, reArmedListener.n
        )
    }

    // ============================================================ r31-s17-dc-03

    /**
     * `dc-03` 的**归一化映射**：五个原始类型属性 `setValue(null)` 落在各自的类型默认值上。
     *
     * 这是 `WritableValue.setValue` 契约的**实现侧**验证。审计抱怨的正是「null 被静默折算」——
     * 本条修复不去改这个折算（改了会打断 `BidirectionalBinding` 的回退路径，见报告 §4.2），
     * 而是把这个映射钉成契约：五格都必须**不抛异常**且落在默认值上。
     *
     * 判别力：把任意一个 `setValue` 的 null 分支改成抛 NPE，对应那一格会带上异常类名（红）。
     */
    @Test
    fun dc03_primitiveBackedSetValue_nullNormalisesToTypeDefault() {
        val b = SimpleBooleanProperty(true)
        val d = SimpleDoubleProperty(3.5)
        val f = SimpleFloatProperty(1.5f)
        val i = SimpleIntegerProperty(7)
        val l = SimpleLongProperty(9L)

        b.value = null
        d.value = null
        f.value = null
        i.value = null
        l.value = null

        Assert.assertTrue(
            "setValue(null) on boolean property must normalise to false, was <" + b.value + ">",
            b.value == false
        )
        Assert.assertTrue(
            "setValue(null) on double property must normalise to 0.0, was <" + d.value + ">",
            d.value == 0.0
        )
        Assert.assertTrue(
            "setValue(null) on float property must normalise to 0.0f, was <" + f.value + ">",
            f.value == 0.0f
        )
        Assert.assertTrue(
            "setValue(null) on integer property must normalise to 0, was <" + i.value + ">",
            i.value == 0
        )
        Assert.assertTrue(
            "setValue(null) on long property must normalise to 0L, was <" + l.value + ">",
            l.value == 0L
        )
    }

    /**
     * `dc-03` 的另一半：**引用类型**属性必须原样保留 `null`。
     *
     * 这是审计主张里真正有价值的一条 —— 同一个 `WritableValue.setValue` 契约在
     * 「原始类型链」与「引用类型链」上语义相反。本条修复把这条差异写进契约文档（报告 §4.3），
     * 并用本用例把实现侧钉住：谁把 `ObjectProperty.setValue(null)` 也折算成某个默认对象，
     * 这条会红。
     */
    @Test
    fun dc03_referenceBackedSetValue_nullIsRetained() {
        val o = SimpleObjectProperty("x")
        o.value = null
        Assert.assertNull("setValue(null) on a reference-typed property must store null", o.value)
    }

    /**
     * `dc-03` 的**通知计数**：写到「已是默认值」的属性上必须 0 次通知，写到「非默认值」上必须 1 次。
     *
     * 这条是审计 claim 里唯一有运行时后果的部分（「null 写入与显式零值写入不可区分」的直接表现）：
     * `set()` 的 `value != newValue` 判等短路让两次调用在通知面上完全一样。
     * 断言的期望值就是**改前实测值**，因此它是契约钉住用例，不是缺陷修复用例。
     */
    @Test
    fun dc03_nullWrite_toDefaultValue_producesNoNotification() {
        val alreadyDefault = SimpleBooleanProperty(false)
        val l1 = CountInvalidation()
        alreadyDefault.addListener(l1)
        alreadyDefault.setValue(null)
        Assert.assertEquals(
            "dc-03: setValue(null) onto an already-default property must not notify (set() short-circuits)",
            0, l1.n
        )

        val notDefault = SimpleBooleanProperty(true)
        val l2 = CountInvalidation()
        notDefault.addListener(l2)
        notDefault.setValue(null)
        Assert.assertEquals(
            "dc-03: setValue(null) onto a non-default property must notify exactly once",
            1, l2.n
        )
        Assert.assertTrue(
            "dc-03: and the value must end up at the type default, was <" + notDefault.value + ">",
            notDefault.value == false
        )
    }

    // ============================================================ r31-s18-contract-01

    /**
     * `contract-01`：`LongProperty.longProperty(host)` 的虚拟属性，`getBean()` 必须**引用等于**宿主。
     *
     * 用 `assertSame` 而不是 `assertEquals` —— 契约要的是「同一个对象」，`equals` 相等会被
     * 任意重写糊弄过去（`ReadOnlyListProperty` 就重写了值等价 `equals`）。
     */
    @Test
    fun contract01_longPropertyWrapper_getBeanIsTheHostProperty() {
        val host = SimpleObjectProperty<Long>(Any(), "host", 5L)
        val virtual: LongProperty = LongProperty.longProperty(host)

        Assert.assertSame(
            "contract-01: getBean() of the longProperty() wrapper must be the host property itself",
            host, virtual.bean
        )
        Assert.assertEquals(
            "contract-01: getName() must keep delegating to the host's name",
            "host", virtual.name
        )
    }

    /**
     * `contract-01`：`asObject()` 的虚拟属性，`getBean()` 必须**引用等于**被包装的那个 `LongProperty`。
     */
    @Test
    fun contract01_asObjectWrapper_getBeanIsTheWrappedLongProperty() {
        val real = SimpleLongProperty(Any(), "real", 5L)
        val asObj: ObjectProperty<Long> = real.asObject()

        Assert.assertSame(
            "contract-01: getBean() of asObject() must be the LongProperty it wraps",
            real, asObj.bean
        )
        Assert.assertEquals("contract-01: getName() must keep delegating", "real", asObj.name)
    }

    /**
     * `contract-01` 的**可观察后果**：绑定态 `set()` 的异常消息必须带上属性定位。
     *
     * 依据十个 `*PropertyBase.set()` 里的 `getBean() != null && getName() != null` 前置条件。
     * 改前这两个虚拟属性的 `getBean()` 是 `null`，消息退化成裸串 `"A bound value cannot be set."`；
     * 改后应包含 `<BeanSimpleName>.<name> : `。
     *
     * 注意这里断的是**消息包含 `getName()`** 这一行为，而不是比对整串字面量：
     * bean 的简单类名取决于匿名/局部类，抄成字面量会把用例锁死在实现细节上。
     */
    @Test
    fun contract01_boundSetExceptionCarriesPropertyIdentity() {
        val host = SimpleObjectProperty<Long>(Any(), "host", 5L)
        val virtual: LongProperty = LongProperty.longProperty(host)
        virtual.bind(SimpleLongProperty(1L))

        var message: String? = null
        try {
            virtual.set(2L)
            Assert.fail("contract-01: setting a bound property must throw")
        } catch (e: RuntimeException) {
            message = e.message
        }
        Assert.assertNotNull("contract-01: expected a RuntimeException message", message)
        Assert.assertTrue(
            "contract-01: the bound-set message must carry the property name (was a bare string " +
                "before the fix, because getBean() returned null). Actual: <$message>",
            message!!.contains("host")
        )
    }

    // ============================================================ r31-s18-contract-02

    /**
     * `contract-02`：`ObjectPropertyBase.bind()` 必须用**引用**判据，不能因为「值相等」而跳过换源。
     *
     * 构造：`a` 与 `b` 是不同实例但 `a.equals(b) == true`（见 [KeyedValue]）。
     * `bind(a)` 再 `bind(b)` 后让 `b` 推一次值 —— 属性必须跟着 `b` 走。
     *
     * 改前（`equals` 判据）：第二次 `bind` 判定「无变化」直接返回，`b` 的推送到不了属性，
     * 属性还在跟 `a`，断言红。改后（`!=` 判据）：换源成功，断言绿。
     */
    @Test
    fun contract02_objectBind_usesIdentitySoEqualButDistinctSourceTakesEffect() {
        val a = KeyedValue("k", "from-a")
        val b = KeyedValue("k", "from-b")
        Assert.assertEquals("precondition: the two sources must be value-equal", a, b)
        Assert.assertNotSame("precondition: but they must be distinct instances", a, b)

        val property = SimpleObjectProperty<Any?>()
        property.bind(a)
        property.bind(b)
        b.push("pushed-by-b")

        Assert.assertEquals(
            "contract-02: after bind(b) the property must follow b; an equals-based criterion " +
                "silently keeps it bound to a",
            "pushed-by-b", property.value
        )
    }

    /**
     * `contract-02` 的**不回退守卫**：`ListPropertyBase.bind()` 本来就是引用判据，改后仍必须换源。
     *
     * 这条防的是「把七个类统一成 `equals` 而不是 `!=`」这种反向修法 —— 那样这条会红。
     */
    @Test
    fun contract02_listBind_keepsIdentityCriterion() {
        val l1: ObservableList<String> = FXCollections.observableArrayList(Arrays.asList("a", "b"))
        val l2: ObservableList<String> = FXCollections.observableArrayList(Arrays.asList("a", "b"))
        Assert.assertEquals("precondition: the two lists must be value-equal", l1, l2)
        Assert.assertNotSame("precondition: but distinct instances", l1, l2)

        val holder = SimpleObjectProperty<ObservableList<String>>(l2)
        val property = SimpleListProperty(l1)
        property.bind(holder)
        l2.add("c")

        Assert.assertEquals(
            "contract-02: ListPropertyBase.bind must switch to the new source (identity criterion)",
            3, property.value.size
        )
    }

    // ============================================================ r31-s18-contract-03

    /**
     * `contract-03`：`sizeProperty()` / `emptyProperty()` 的通知行为不得回归。
     *
     * `@Override` 本身是**编译期**事实（补错位置会直接编译失败，那是比断言更强的证据），
     * 所以行为侧只做「不回退」：`set(新 list)` 时两个派生属性各通知一次，随后在新 list 上
     * 追加元素时 `sizeProperty` 继续通知。
     *
     * 判别力：把内层类的 `super.fireValueChangedEvent()` 删掉（mutation M-03），
     * 计数会掉到 0，而 `@Override` 仍在 —— 这正是「注解在、行为没了」的逃脱形态，
     * 只有行为断言能抓住。
     */
    @Test
    fun contract03_sizeAndEmptyPropertiesKeepNotifying() {
        val l1: ObservableList<String> = FXCollections.observableArrayList(Arrays.asList("a"))
        val l2: ObservableList<String> = FXCollections.observableArrayList(Arrays.asList("a", "b"))
        val property: ListPropertyBase<String> = SimpleListProperty(l1)

        val sizeProperty: ReadOnlyIntegerProperty = property.sizeProperty()
        val emptyProperty: ReadOnlyBooleanProperty = property.emptyProperty()
        val sizeListener = CountInvalidation()
        val emptyListener = CountInvalidation()
        sizeProperty.addListener(sizeListener)
        emptyProperty.addListener(emptyListener)

        property.set(l2)
        Assert.assertEquals("contract-03: sizeProperty must notify once on set(newList)", 1, sizeListener.n)
        Assert.assertEquals("contract-03: sizeProperty must read the new size", 2, sizeProperty.get())
        Assert.assertEquals("contract-03: emptyProperty must notify once on set(newList)", 1, emptyListener.n)
        Assert.assertFalse("contract-03: emptyProperty must read the new emptiness", emptyProperty.get())

        l2.add("c")
        Assert.assertEquals(
            "contract-03: sizeProperty must keep notifying on later content changes",
            2, sizeListener.n
        )
        Assert.assertEquals("contract-03: and must track the size", 3, sizeProperty.get())
    }
}
