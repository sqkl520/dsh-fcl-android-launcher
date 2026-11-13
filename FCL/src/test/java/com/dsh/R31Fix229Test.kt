package com.dsh

import com.tungsten.fclcore.fakefx.beans.InvalidationListener
import com.tungsten.fclcore.fakefx.beans.Observable
import com.tungsten.fclcore.fakefx.beans.binding.ListBinding
import com.tungsten.fclcore.fakefx.beans.binding.ObjectBinding
import com.tungsten.fclcore.fakefx.beans.property.Property
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyBooleanProperty
import com.tungsten.fclcore.fakefx.beans.property.ReadOnlyIntegerProperty
import com.tungsten.fclcore.fakefx.beans.property.StringProperty
import com.tungsten.fclcore.fakefx.beans.value.ChangeListener
import com.tungsten.fclcore.fakefx.beans.value.ObservableValue
import com.tungsten.fclcore.fakefx.binding.ListExpressionHelper
import com.tungsten.fclcore.fakefx.binding.MappedBinding
import com.tungsten.fclcore.fakefx.collections.ArrayChangeListener
import com.tungsten.fclcore.fakefx.collections.FXCollections
import com.tungsten.fclcore.fakefx.collections.ListChangeListener
import com.tungsten.fclcore.fakefx.collections.ModifiableObservableListBase
import com.tungsten.fclcore.fakefx.collections.NonIterableChange
import com.tungsten.fclcore.fakefx.collections.ObservableFloatArray
import com.tungsten.fclcore.fakefx.collections.ObservableFloatArrayImpl
import com.tungsten.fclcore.fakefx.collections.ObservableIntegerArray
import com.tungsten.fclcore.fakefx.collections.ObservableIntegerArrayImpl
import com.tungsten.fclcore.fakefx.collections.ObservableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * round31 / fix-r31-229 的回归测试 —— 每条用例对应 round31-04-fix-229.md §1.1 里的一条 `disproof_condition`。
 *
 * 被测类全在 `fakefx`（`beans/binding`、`binding`、`collections`），可直接在 JVM 上构造；
 * 跑的是 Gradle 编出来的**真实生产 class**（`run-tests.sh` 的 classpath）。
 *
 * ★ 断言性质分两类，逐个在方法注释里标注：
 *   - **行为断言**：`bind`/`unbind` 的 null 守卫、`toString` 形状、`setAll` 失败后的长度、
 *     `nextRemove` 不抛、`LazyObjectBinding` 的订阅泄漏、`ListExpressionHelper` 的基线刷新。
 *   - **结构断言**：`volatile` 字段修饰符（`conc01*`）与 `try/finally` 的字节码异常表（`s38dc01*`）。
 *     这两条没有可用的行为注入器（见产物 §7.1 / §7.2），退一步读**编译产物字节码**。
 *     结构断言只能证明"原语/结构已加"，**不能**证明"并发/异常下正确"；不拿它冒充行为证据。
 */
class R31Fix229Test {

    // ------------------------------------------------------------------ helpers

    /** 只数 add/removeListener 次数的 `Observable` —— 用来观察半绑定与订阅泄漏。 */
    private class CountingDep : Observable {
        var adds = 0
        var removes = 0
        override fun addListener(listener: InvalidationListener) { adds++ }
        override fun removeListener(listener: InvalidationListener) { removes++ }
    }

    private class TestObjectBinding : ObjectBinding<String>() {
        override fun computeValue(): String = "x"
        fun doBind(vararg deps: Observable?) = bind(*deps)
        fun doUnbind(vararg deps: Observable?) = unbind(*deps)
    }

    // ------------------------------------------------------------------ slice-14

    /**
     * `slice-14-concurrency-01`：`IntegerBinding` 的缓存双字段必须带 `ACC_VOLATILE`。
     *
     * 为什么是结构断言：无锁可见性缺陷无法用行为断言稳定复现（数据竞争本质不确定，
     * 单核调度下几乎必然"绿"）；而源码文本断言已被本轮反复证明形同虚设。
     * 读 `.class` 的修饰符位不可伪造 —— 注释、格式、换行都改不了它。
     */
    @Test
    fun conc01CachedFieldsAreVolatile() {
        val c = Class.forName("com.tungsten.fclcore.fakefx.beans.binding.IntegerBinding")
        val valueField = c.getDeclaredField("value")
        val validField = c.getDeclaredField("valid")
        assertTrue(
            "IntegerBinding.value must be volatile: get() writes `value` and then publishes it " +
                "through `valid = true`; without volatile another thread can see the new valid " +
                "flag next to a stale value. modifiers=" + Modifier.toString(valueField.modifiers),
            Modifier.isVolatile(valueField.modifiers)
        )
        assertTrue(
            "IntegerBinding.valid must be volatile (the release half of the same publication). " +
                "modifiers=" + Modifier.toString(validField.modifiers),
            Modifier.isVolatile(validField.modifiers)
        )
    }

    // ------------------------------------------------------------------ slice-16

    /**
     * `s16-eh-04`：`bind()` 遇到 null 依赖元素不得抛，且**所有**非 null 依赖都要挂上。
     *
     * 改前：`bind(d1, null, d2)` 抛 NPE —— `d1` 已挂、`d2` 没挂，半绑定且无法回滚。
     */
    @Test
    fun eh04BindSkipsNullDependency() {
        val b = TestObjectBinding()
        val d1 = CountingDep()
        val d2 = CountingDep()

        b.doBind(d1, null, d2)   // 抛了就直接把异常带出去，用例红

        assertEquals("the dependency before the null element must be registered", 1, d1.adds)
        assertEquals("the dependency after the null element must still be registered", 1, d2.adds)
    }

    /**
     * `s16-eh-05`：`unbind()` 遇到 null 依赖元素不得抛，且 `observer` 必须照常复位。
     *
     * 改前：`unbind(d1, null)` 抛 NPE，`observer = null` 永不执行 ⇒ observer 存活且再也摘不掉。
     */
    @Test
    fun eh05UnbindSkipsNullDependency() {
        val b = TestObjectBinding()
        val d1 = CountingDep()
        val d2 = CountingDep()
        b.doBind(d1, d2)
        assertEquals(1, d1.adds)
        assertEquals(1, d2.adds)

        b.doUnbind(d1, null)     // 抛了就直接红

        assertEquals("the dependency before the null element must be unregistered", 1, d1.removes)

        // 残余风险（产物 §7.3）：unbind() 在循环外无条件把 observer 置 null，所以「多 bind 后只
        // unbind 一部分」的 d2 永远摘不掉 —— 本单不修这一半（需要「已绑定集合」状态跟踪，>10 行，
        // 且与上游同形；当前调用约定下不可达：Bindings.java 的 164 处 bind/unbind 用同一数组）。
        // 这里把该行为**钉死**，防止将来有人误以为这条路径已被修好。
        b.doUnbind(d2)
        assertEquals(
            "known limitation (registered as residual risk): after the observer field was reset by the " +
                "previous unbind(), a later unbind() is a no-op, so d2 keeps its observer",
            0,
            d2.removes
        )
    }

    // ------------------------------------------------------------------ slice-24

    /**
     * `r31-s24-contract-01`：`StringProperty.toString()` 在已绑定时必须含 `bound, ` 段。
     *
     * 改前：`JavaBeanStringProperty`（`extends StringProperty`、不覆写 `toString`）在已绑定时渲染成
     * `StringProperty [value: …]`，而同状态的 `StringPropertyBase` 子类渲染成 `StringProperty [bound, …]`。
     *
     * 不加载 `JavaBeanStringProperty`（它的 `get()` 走 `MethodUtil` 蹦床，Android 专用，本机 JVM 抛
     * InternalError）；`bound,` 段在 `get()` **之前**拼接，所以用等价的 StringProperty 子类即可钉住同一处。
     */
    @Test
    fun s24BoundStringPropertyMarksBound() {
        assertEquals(
            "the unbound shape must not change (compatibility half of the fix)",
            "StringProperty [value: hello]",
            RawStringProperty("hello", bound = false).toString()
        )

        val rendered = RawStringProperty("hello", bound = true).toString()
        assertTrue(
            "a bound StringProperty must announce `bound, ` like StringPropertyBase does; got <$rendered>",
            rendered.contains("bound, ")
        )
        assertTrue("the value segment must survive; got <$rendered>", rendered.contains("value: hello"))
    }

    /** 只实现 `StringProperty` 自身抽象的属性（等价于 JavaBeanStringProperty 的 toString 路径）。 */
    private class RawStringProperty(private var v: String, private var bound: Boolean) : StringProperty() {
        override fun get(): String = v
        override fun getBean(): Any? = null
        override fun getName(): String = ""
        override fun set(newValue: String?) { v = newValue ?: "" }
        override fun isBound(): Boolean = bound
        override fun bind(newObservable: ObservableValue<out String>?) { bound = true }
        override fun unbind() { bound = false }
        override fun bindBidirectional(other: Property<String>?) {}
        override fun unbindBidirectional(other: Property<String>?) {}
        override fun addListener(listener: InvalidationListener?) {}
        override fun removeListener(listener: InvalidationListener?) {}
        override fun addListener(listener: ChangeListener<in String>?) {}
        override fun removeListener(listener: ChangeListener<in String>?) {}
    }

    // ------------------------------------------------------------------ slice-34 dc-02

    /**
     * `slice-34-dc-02`：`addListener` 期间 `get()` 抛异常后，`removeListener` 必须真的退订。
     *
     * 改前：(wasObserved, subscription) 分叉 —— `subscription != null` 但 `wasObserved == false`，
     * `updateSubscriptionAfterRemove()` 的门恒假 ⇒ 订阅永久泄漏。
     *
     * 源的 `addListener` 故意**不读**值，这样异常只能来自 `LazyObjectBinding:76` 的那次 `get()`
     * （否则会先在 `ExpressionHelper` 的构造期校验里抛出，测的就不是这条路径）。
     */
    @Test
    fun dc02FailedAddLeavesNoLeakedSubscription() {
        val src = ThrowingSource(throwOnRead = 2)
        val binding = MappedBinding(src) { v: Int -> v * 10 }
        val listener = InvalidationListener { }

        var threw = false
        try {
            binding.addListener(listener)
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue("premise: the get() inside updateSubscriptionAfterAdd must actually have thrown", threw)
        assertEquals("premise: the subscription must have been established before the throw", 1, src.adds)

        binding.removeListener(listener)

        assertEquals(
            "after a failed addListener, removeListener must still unsubscribe the source " +
                "(this is exactly the wasObserved/subscription fork)",
            1,
            src.removes
        )
        assertFalse(
            "no subscription may outlive removeListener: adds=${src.adds} removes=${src.removes}",
            src.adds > src.removes
        )
    }

    /** 源：`getValue()` 第 N 次读取才抛；`addListener` 不读值（把异常留给 LazyObjectBinding:76）。 */
    private class ThrowingSource(private val throwOnRead: Int) : ObservableValue<Int> {
        var adds = 0
        var removes = 0
        var reads = 0
        override fun addListener(listener: InvalidationListener?) { adds++ }
        override fun removeListener(listener: InvalidationListener?) { removes++ }
        override fun addListener(listener: ChangeListener<in Int>?) { adds++ }
        override fun removeListener(listener: ChangeListener<in Int>?) { removes++ }
        override fun getValue(): Int {
            reads++
            if (reads >= throwOnRead) throw IllegalStateException("source read #$reads")
            return 1
        }
    }

    // ------------------------------------------------------------------ slice-34 dc-04

    /**
     * `slice-34-dc-04`：元素级通知路径必须刷新 `currentValue` 基线。
     *
     * 改前：`fireValueChangedEvent(Change)` 把**同一个字段值**同时当 oldValue 与 newValue 传出去
     * ⇒ `ChangeListener` 收到 `old == new`，而且那个值连"当前值"都不是（停在 helper 构造时的基线）。
     *
     * 直接驱动 helper 的元素级重载：`ListPropertyBase`/`ReadOnlyListPropertyBase`/`ListBinding`
     * 转发元素变更时走的**就是**这条静态入口（三处调用点见 grep）。桩在 `mutate()` 后换新的包装
     * 实例，于是"基线是否刷新"可由 `newValue === observable.getValue()` 精确判定。
     */
    @Test
    fun dc04ElementChangeRefreshesBaseline() {
        val source = FreshWrapperListValue()
        // helper 构造期会读一次 getValue() 作为基线；先让内容变一下，再发元素级通知，
        // 这样"基线已过期"与"基线已刷新"两种结果可以区分开。
        val baselineAtConstruction = source.getValue()
        source.mutate(0, "z")
        val currentAfterMutation = source.getValue()
        assertNotSame("premise: the mutation must have produced a new instance", baselineAtConstruction, currentAfterMutation)

        var oldValue: ObservableList<String>? = null
        var newValue: ObservableList<String>? = null
        var hits = 0
        val changeListener = ChangeListener<ObservableList<String>> { _, o, n ->
            hits++
            oldValue = o
            newValue = n
        }
        // 单个 ChangeListener ⇒ helper 是 SingleChange 实现（Generic 是同一处缺陷的另一份）
        val helper = ListExpressionHelper.addListener(null, source, changeListener)
        assertNotNull("precondition: a helper must have been created", helper)

        // 再变一次，让 helper 内的基线（= currentAfterMutation）也过期
        source.mutate(1, "y")
        val currentBeforeFire = source.getValue()

        val elementChange = NonIterableChange.SimpleAddChange(0, 1, source)
        ListExpressionHelper.fireValueChangedEvent(helper, elementChange)

        assertEquals("premise: the ChangeListener must be called once", 1, hits)
        assertNotSame(
            "an element-level notification must not report oldValue === newValue",
            oldValue,
            newValue
        )
        assertTrue(
            "the reported newValue must be the observable's current value, not a stale baseline",
            newValue === source.getValue()
        )
    }

    /**
     * `ObservableListValue` 桩：`getValue()` 返回**当前状态对应的包装实例**；只有 `mutate()`
     * 改了内容才换新实例。
     *
     * 为什么缓存实例而不是每次新建：本用例要判定的正是"回调里的 newValue 是不是当前值"。
     * 若每次调用都返回新对象，`newValue === getValue()` 永远为假，断言就无法区分"刷新过"与"没刷新"。
     * 缓存形状也更接近真实属性（`ListPropertyBase` 的 `value` 字段只在换绑时才换实例）。
     */
    private class FreshWrapperListValue : com.tungsten.fclcore.fakefx.beans.value.ObservableListValue<String> {
        private val backing: ObservableList<String> = FXCollections.observableArrayList("a", "b", "c")
        private var cached: ObservableList<String> =
            FXCollections.unmodifiableObservableList(FXCollections.observableArrayList(backing))

        /** 模拟"内容变了"：换一个新包装实例（元素级变更的语义），并同步内部 backing。 */
        fun mutate(index: Int, value: String) {
            backing.set(index, value)
            cached = FXCollections.unmodifiableObservableList(FXCollections.observableArrayList(backing))
        }

        override fun getValue(): ObservableList<String> = cached
        override fun get(): ObservableList<String> = cached
        override val size: Int get() = backing.size
        override fun isEmpty(): Boolean = backing.isEmpty()
        override fun iterator(): MutableIterator<String> = backing.iterator()
        override fun contains(element: String): Boolean = backing.contains(element)
        override fun containsAll(elements: Collection<String>): Boolean = backing.containsAll(elements)
        override fun get(index: Int): String = backing[index]
        override fun indexOf(element: String): Int = backing.indexOf(element)
        override fun lastIndexOf(element: String): Int = backing.lastIndexOf(element)
        override fun listIterator(): MutableListIterator<String> = backing.listIterator()
        override fun listIterator(index: Int): MutableListIterator<String> = backing.listIterator(index)
        override fun subList(fromIndex: Int, toIndex: Int): MutableList<String> = backing.subList(fromIndex, toIndex)
        override fun add(element: String): Boolean = backing.add(element)
        override fun add(index: Int, element: String) = backing.add(index, element)
        override fun addAll(elements: Collection<String>): Boolean = backing.addAll(elements)
        override fun addAll(index: Int, elements: Collection<String>): Boolean = backing.addAll(index, elements)
        override fun addAll(vararg elements: String): Boolean = backing.addAll(elements.toList())
        override fun set(index: Int, element: String): String = backing.set(index, element)
        override fun remove(element: String): Boolean = backing.remove(element)
        override fun removeAt(index: Int): String = backing.removeAt(index)
        override fun removeAll(elements: Collection<String>): Boolean = backing.removeAll(elements)
        override fun removeAll(vararg elements: String): Boolean = backing.removeAll(elements.toList())
        override fun retainAll(elements: Collection<String>): Boolean = backing.retainAll(elements)
        override fun retainAll(vararg elements: String): Boolean = backing.retainAll(elements.toList())
        override fun clear() = backing.clear()
        override fun setAll(vararg elements: String): Boolean { backing.clear(); return backing.addAll(elements.toList()) }
        override fun setAll(col: Collection<String>): Boolean { backing.clear(); return backing.addAll(col) }
        override fun remove(from: Int, to: Int) { backing.subList(from, to).clear() }
        override fun addListener(listener: ChangeListener<in ObservableList<String>>?) {}
        override fun removeListener(listener: ChangeListener<in ObservableList<String>>?) {}
        override fun addListener(listener: ListChangeListener<in String>?) {}
        override fun removeListener(listener: ListChangeListener<in String>?) {}
        override fun addListener(listener: InvalidationListener?) {}
        override fun removeListener(listener: InvalidationListener?) {}
    }

    // ------------------------------------------------------------------ slice-37

    /**
     * `F-37-01`：同一 change 块内 `nextAdd` + `nextPermutation` 之后紧跟 `nextRemove` 不得 NPE。
     *
     * 改前：`nextPermutation` 重建 add/remove 列表时以 `null` 作 `removed` 构造 `SubChange`，
     * 随后 `nextRemove` 直接 `last.removed.add(...)` ⇒ NPE @ `ListChangeBuilder.java:178`。
     *
     * 这条序列不是硬凑的：`SortedList.update()` 就是在同一块里 `nextPermutation` 后又发 `nextUpdate`，
     * `SortedList.addRemove()` / `FilteredList` 在同一块里发 `nextRemove`。
     */
    @Test
    fun f3701NextRemoveAfterPermutationDoesNotThrow() {
        val list = TransactionProbeList()
        for (i in 0 until 6) list.backing.add("v$i")

        val changes = ArrayList<ListChangeListener.Change<out String>>()
        list.addListener(ListChangeListener<String> { c -> changes.add(c) })

        list.beginChangePublic()
        try {
            list.nextAddPublic(0, 2)
            list.nextPermutationPublic(0, 3, intArrayOf(0, 2, 1))
            list.nextRemovePublic(3, "zz")
        } finally {
            list.endChangePublic()
        }

        // 不抛就算过；再断言确实产出了可遍历的 Change —— 证明是"合并成功"而不是"被吞掉"。
        assertEquals("premise: endChange() must have fired exactly one change", 1, changes.size)
        val c = changes[0]
        assertTrue("the produced change must be iterable (c.next() must return true)", c.next())
        assertTrue(
            "the merged change must still describe the operations (nextRemove was not swallowed)",
            c.wasRemoved() || c.wasPermutated() || c.wasAdded()
        )
    }

    /** 把 `beginChange`/`next*`/`endChange` 暴露成 public，用来构造"同一块内多步变更"。 */
    private class TransactionProbeList : ModifiableObservableListBase<String>() {
        val backing = ArrayList<String>()
        override fun get(index: Int): String = backing[index]
        override val size: Int get() = backing.size
        override fun doAdd(index: Int, element: String) { backing.add(index, element) }
        override fun doSet(index: Int, element: String): String = backing.set(index, element)
        override fun doRemove(index: Int): String = backing.removeAt(index)

        fun beginChangePublic() = beginChange()
        fun endChangePublic() = endChange()
        fun nextAddPublic(from: Int, to: Int) = nextAdd(from, to)
        fun nextRemovePublic(idx: Int, e: String) = nextRemove(idx, e)
        fun nextPermutationPublic(from: Int, to: Int, perm: IntArray) = nextPermutation(from, to, perm)
    }

    // ------------------------------------------------------------------ slice-38 dc-01

    /**
     * `s38-dc-01`：三个元素级 mutator 的 `endChange()` 必须在 `finally` 里。
     *
     * 为什么是结构断言：单线程公开 API 上没有能把异常注入 `beginChange`/`endChange` 之间的手段
     * （`do*` 在 `beginChange` **之前**；索引非法的 `next*` 也不抛；唯一在块内抛的是 F-37-01，
     * 而本单把它修了）。任何"行为"用例都会证明"F-37-01 修好了"而不是"finally 加上了" ⇒ 必然假绿。
     * 所以读 `.class`：这三个方法的 `Code` 里必须至少有一条异常处理器（= 方法体含 try/catch/finally）。
     *
     * 匹配用**完整签名**：`ModifiableObservableListBase` 同时有 `remove(int)`（返回 E）与
     * `remove(Object)`（返回 boolean），只看"名字 + 参数个数"会撞到错的那个。
     */
    @Test
    fun s38dc01ElementMutatorsRestoreTransaction() {
        val owner = Class.forName("com.tungsten.fclcore.fakefx.collections.ModifiableObservableListBase")
        val targets = listOf(
            "add(int,E)" to "(ILjava/lang/Object;)V",
            "set(int,E)" to "(ILjava/lang/Object;)Ljava/lang/Object;",
            "remove(int)" to "(I)Ljava/lang/Object;"
        )
        for ((label, desc) in targets) {
            val handlers = exceptionHandlerCount(owner, desc)
            assertTrue(
                "ModifiableObservableListBase.$label must guard its change block with try/finally " +
                    "so endChange() runs even if next* throws; exception_table_length was $handlers",
                handlers > 0
            )
        }
    }

    /**
     * 读 class 文件里描述符为 `desc` 的那个方法的 `Code` 属性，返回 `exception_table_length`。
     *
     * 刻意不用 `javax.tools.*`（TC-01 硬门），也不解析 `javap -c` 的文本：按 JVM 规范直接读
     * `Code` 属性（max_stack、max_locals、code_length、code[]、exception_table_length、…）。
     * 注释 / 格式 / 重排都改不了这两个字节，只有真加 `try/finally` 才会变。
     */
    private fun exceptionHandlerCount(owner: Class<*>, desc: String): Int {
        val resource = "/" + owner.name.replace('.', '/') + ".class"
        val bytes = owner.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("cannot read class bytes for ${owner.name}")
        val code = readCodeAttribute(bytes, desc)
            ?: error("no Code attribute for ${owner.name} $desc")
        val d = java.io.DataInputStream(java.io.ByteArrayInputStream(code))
        d.readUnsignedShort()                                   // max_stack
        d.readUnsignedShort()                                   // max_locals
        repeat(d.readInt()) { d.readUnsignedByte() }             // code[]
        return d.readUnsignedShort()                             // exception_table_length
    }

    /** 在 class 文件里按**描述符**找到方法，返回它的 `Code` 属性原始字节。 */
    private fun readCodeAttribute(bytes: ByteArray, wantDesc: String): ByteArray? {
        val d = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        require(d.readInt() == -0x35014542) { "not a class file: bad magic" }    // 0xCAFEBABE
        d.readUnsignedShort()                                                    // minor
        d.readUnsignedShort()                                                    // major
        val cpCount = d.readUnsignedShort()
        val utf8 = HashMap<Int, String>()
        var i = 1
        while (i < cpCount) {
            when (val tag = d.readUnsignedByte()) {
                1 -> utf8[i] = d.readUTF()
                7, 8, 16, 19, 20 -> d.readUnsignedShort()
                15 -> { d.readUnsignedByte(); d.readUnsignedShort() }
                3, 4, 9, 10, 11, 12, 17, 18 -> d.readInt()
                5, 6 -> { d.readLong(); i += 2; continue }                       // Long/Double 占两个槽
                else -> error("unknown constant pool tag $tag at index $i")
            }
            i++
        }
        d.readUnsignedShort()                                                    // access_flags
        d.readUnsignedShort()                                                    // this_class
        d.readUnsignedShort()                                                    // super_class
        repeat(d.readUnsignedShort()) { d.readUnsignedShort() }                  // interfaces
        repeat(d.readUnsignedShort()) { skipMember(d) }                          // fields
        repeat(d.readUnsignedShort()) {                                          // methods
            d.readUnsignedShort()                                                // access_flags
            d.readUnsignedShort()                                                // name_index
            val descIdx = d.readUnsignedShort()
            var code: ByteArray? = null
            repeat(d.readUnsignedShort()) {                                      // attributes
                val attrNameIdx = d.readUnsignedShort()
                val body = ByteArray(d.readInt()).also { d.readFully(it) }
                if (utf8[attrNameIdx] == "Code") code = body
            }
            if (code != null && utf8[descIdx] == wantDesc) return code
        }
        return null
    }

    private fun skipMember(d: java.io.DataInputStream) {
        d.readUnsignedShort()                                                    // access_flags
        d.readUnsignedShort()                                                    // name_index
        d.readUnsignedShort()                                                    // descriptor_index
        repeat(d.readUnsignedShort()) {                                          // attributes
            d.readUnsignedShort()
            repeat(d.readInt()) { d.readByte() }
        }
    }

    // ------------------------------------------------------------------ slice-38 dc-02

    /**
     * `s38-dc-02`：`setAll` 复制失败时不得把 `size` 永久清零。
     *
     * 改前：`size = 0` 在 `src.copyTo(...)` **之前** ⇒ 复制抛异常后长度永久为 0、
     * 旧内容已部分覆盖、`fireChange` 不执行（观测长度丢了且没有任何通知）。
     */
    @Test
    fun s38dc02FloatSetAllKeepsSizeWhenCopyFails() {
        val src = ArmableFloatArray(floatArrayOf(1f, 2f, 3f))
        val target = ObservableFloatArrayImpl(1f, 2f, 3f, 4f, 5f)
        assertEquals(5, target.size())

        src.armed = true
        var threw = false
        try {
            target.setAll(src, 0, 3)
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue("premise: the copy must actually have failed", threw)
        assertEquals(
            "a failed setAll must not destroy the observable length (old code left it at 0)",
            5,
            target.size()
        )
    }

    /** 整数版同一条缺陷：两个实现都要修到（共用 `s38-dc-02` 契约）。 */
    @Test
    fun s38dc02IntegerSetAllKeepsSizeWhenCopyFails() {
        val src = ArmableIntArray(intArrayOf(1, 2, 3))
        val target = ObservableIntegerArrayImpl(1, 2, 3, 4, 5)
        assertEquals(5, target.size())

        src.armed = true
        var threw = false
        try {
            target.setAll(src, 0, 3)
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue("premise: the copy must actually have failed", threw)
        assertEquals("integer array must keep its length on a failed setAll", 5, target.size())
    }

    /** `copyTo` 可武装抛异常的 `ObservableFloatArray` 桩。 */
    private class ArmableFloatArray(private val data: FloatArray) : ObservableFloatArray {
        var armed = false
        override fun size(): Int = data.size
        override fun get(index: Int): Float = data[index]
        override fun copyTo(srcIndex: Int, dest: FloatArray, destIndex: Int, length: Int) {
            if (armed) throw IllegalStateException("copyTo failed")
            System.arraycopy(data, srcIndex, dest, destIndex, length)
        }
        override fun copyTo(srcIndex: Int, dest: ObservableFloatArray, destIndex: Int, length: Int) =
            throw UnsupportedOperationException()
        override fun addAll(vararg elements: Float) = throw UnsupportedOperationException()
        override fun addAll(src: ObservableFloatArray?) = throw UnsupportedOperationException()
        override fun addAll(src: FloatArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun addAll(src: ObservableFloatArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun setAll(vararg elements: Float) = throw UnsupportedOperationException()
        override fun setAll(src: FloatArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun setAll(src: ObservableFloatArray?) = throw UnsupportedOperationException()
        override fun setAll(src: ObservableFloatArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun set(destIndex: Int, src: FloatArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun set(destIndex: Int, src: ObservableFloatArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun set(index: Int, value: Float) = throw UnsupportedOperationException()
        override fun toArray(dest: FloatArray?): FloatArray = data.clone()
        override fun toArray(srcIndex: Int, dest: FloatArray?, length: Int): FloatArray = data.clone()
        override fun resize(newSize: Int) = throw UnsupportedOperationException()
        override fun ensureCapacity(capacity: Int) {}
        override fun trimToSize() {}
        override fun clear() = throw UnsupportedOperationException()
        override fun addListener(listener: ArrayChangeListener<ObservableFloatArray>?) {}
        override fun removeListener(listener: ArrayChangeListener<ObservableFloatArray>?) {}
        override fun addListener(listener: InvalidationListener?) {}
        override fun removeListener(listener: InvalidationListener?) {}
    }

    /** `copyTo` 可武装抛异常的 `ObservableIntegerArray` 桩。 */
    private class ArmableIntArray(private val data: IntArray) : ObservableIntegerArray {
        var armed = false
        override fun size(): Int = data.size
        override fun get(index: Int): Int = data[index]
        override fun copyTo(srcIndex: Int, dest: IntArray, destIndex: Int, length: Int) {
            if (armed) throw IllegalStateException("copyTo failed")
            System.arraycopy(data, srcIndex, dest, destIndex, length)
        }
        override fun copyTo(srcIndex: Int, dest: ObservableIntegerArray, destIndex: Int, length: Int) =
            throw UnsupportedOperationException()
        override fun addAll(vararg elements: Int) = throw UnsupportedOperationException()
        override fun addAll(src: ObservableIntegerArray?) = throw UnsupportedOperationException()
        override fun addAll(src: IntArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun addAll(src: ObservableIntegerArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun setAll(vararg elements: Int) = throw UnsupportedOperationException()
        override fun setAll(src: IntArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun setAll(src: ObservableIntegerArray?) = throw UnsupportedOperationException()
        override fun setAll(src: ObservableIntegerArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun set(destIndex: Int, src: IntArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun set(destIndex: Int, src: ObservableIntegerArray?, srcIndex: Int, length: Int) = throw UnsupportedOperationException()
        override fun set(index: Int, value: Int) = throw UnsupportedOperationException()
        override fun toArray(dest: IntArray?): IntArray = data.clone()
        override fun toArray(srcIndex: Int, dest: IntArray?, length: Int): IntArray = data.clone()
        override fun resize(newSize: Int) = throw UnsupportedOperationException()
        override fun ensureCapacity(capacity: Int) {}
        override fun trimToSize() {}
        override fun clear() = throw UnsupportedOperationException()
        override fun addListener(listener: ArrayChangeListener<ObservableIntegerArray>?) {}
        override fun removeListener(listener: ArrayChangeListener<ObservableIntegerArray>?) {}
        override fun addListener(listener: InvalidationListener?) {}
        override fun removeListener(listener: InvalidationListener?) {}
    }
}
