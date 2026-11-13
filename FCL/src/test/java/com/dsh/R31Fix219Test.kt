package com.dsh

import com.tungsten.fclcore.fakefx.collections.FXCollections
import com.tungsten.fclcore.fakefx.collections.ListChangeListener
import com.tungsten.fclcore.fakefx.collections.MapChangeListener
import com.tungsten.fclcore.fakefx.collections.ObservableListWrapper
import com.tungsten.fclcore.fakefx.collections.ObservableMap
import com.tungsten.fclcore.fakefx.collections.ObservableMapWrapper
import com.tungsten.fclcore.fakefx.collections.ObservableSequentialListWrapper
import com.tungsten.fclcore.fakefx.collections.transformation.FilteredList
import com.tungsten.fclcore.fakefx.collections.transformation.SortedList
import org.junit.Assert
import org.junit.Test
import java.lang.reflect.Field
import java.util.ArrayList
import java.util.Comparator
import java.util.HashMap
import java.util.LinkedList
import java.util.Map
import java.util.function.Predicate

/**
 * round31 / `slice-39` contract 五条（`fix-r31-219`）。
 *
 * ## 断言口径
 *
 * 每条断言的都是**调用之后的运行时值/状态**，不是源码文本：
 *
 * - F1：`Set.remove` 的返回值、底层 map 的 `containsKey`、监听器收到的事件数（三个值的组合就是
 *   「返回值与集合真实状态是否一致」的机器可验形式）。
 * - F2：异常逸出后 `ListChangeBuilder.changeLock` 的**实际整数**（反射读），以及「随后一次合法写操作
 *   的监听器通知数」。后者是后果断言：块没闭合时通知会被永久抑制，这一格才是"后果"而不是"内部状态"。
 * - F3：`Change` 的 `getFrom()`/`getTo()`/`getRemoved()` **实际值**，两个遍历方向各测一次。
 * - F4：`setAll` 在两个可变 wrapper 上的**实际结局**（返回值 + 内容），以及只读实现上的实际异常类型。
 * - F5：`toArray(T[])` 返回的数组内**每一格的实际内容**（`null` 还是 `ObservableEntry`）。
 *
 * ## 为什么 F2 必须同时断言 `changeLock` 与"后续通知"
 *
 * 只断言 `changeLock == 0` 会把"异常前根本没 beginChange"也当成绿（假绿）。
 * 只断言"后续通知到达"在旧实现上也能通过（如果异常发生在 next* 之后 endChange 之前但 lock 已被别的
 * 路径复位）。两条一起看才能区分「块闭合了」与「块没闭合但恰好没影响」。
 *
 * ## 为什么用反射读 `changeLock`
 *
 * `beginChange`/`endChange` 是 `protected final`，`ListChangeBuilder` 是包私有类。
 * 从 `com.dsh` 包无法直接触摸，只能反射。反射失败会抛异常（不是静默通过）——
 * 见 [changeLockOf] 里对 `null` 字段的 `Assert.assertNotNull`。
 */
class R31Fix219Test {

    // ------------------------------------------------------------------ 反射工具

    /**
     * 读任意 `ObservableListBase` 子类实例的 `ListChangeBuilder.changeLock`。
     *
     * `changeBuilder` 字段声明在 `ObservableListBase`（`ObservableListWrapper` 等隔了两层父类），
     * 所以沿父类链找；找不到就断言失败，**不做**"返回 0"这种会把反射问题洗成绿的兜底。
     */
    private fun changeLockOf(list: Any): Int {
        var c: Class<*>? = list.javaClass
        var builderField: Field? = null
        while (c != null && builderField == null) {
            builderField = try {
                c.getDeclaredField("changeBuilder")
            } catch (e: NoSuchFieldException) {
                null
            }
            c = c.superclass
        }
        Assert.assertNotNull(
            "reflection failed: no `changeBuilder` field on the superclass chain of ${list.javaClass.name}; " +
                "this test would be a false green",
            builderField
        )
        builderField!!.isAccessible = true
        val builder = builderField.get(list)
        Assert.assertNotNull("reflection failed: `changeBuilder` was null", builder)
        val lockField = builder!!.javaClass.getDeclaredField("changeLock")
        lockField.isAccessible = true
        return lockField.getInt(builder)
    }

    /** 记录每条 `Change` 的 `from/to/removed`，供 F3 断言下标语义。 */
    private class ChangeRecorder<E> : ListChangeListener<E> {
        val records = ArrayList<Triple<Int, Int, List<E>>>()
        override fun onChanged(c: ListChangeListener.Change<out E>) {
            while (c.next()) {
                @Suppress("UNCHECKED_CAST")
                records.add(Triple(c.from, c.to, c.removed as List<E>))
            }
        }
    }

    /** 只数通知次数（F2 的"后续合法写操作是否还能通知"）。 */
    private class CountingListener<E> : ListChangeListener<E> {
        var count = 0
        override fun onChanged(c: ListChangeListener.Change<out E>) {
            while (c.next()) {
                count++
            }
        }
    }

    /** `clear()` 第一次抛 `UnsupportedOperationException`，之后正常 —— 用来在"块闭合"后做一次合法写。 */
    private class FlakyClearList<E>(c: Collection<E>) : ArrayList<E>(c) {
        var failNextClear = true
        override fun clear() {
            if (failNextClear) {
                failNextClear = false
                throw UnsupportedOperationException("injected: backing list refuses clear() once")
            }
            super.clear()
        }
    }

    /**
     * 任一元素比较都抛异常 —— 用来在 `removeAll` / `retainAll` 的**元素扫描阶段**注入失败
     * （`ObservableListWrapper.removeAll` 在 `beginChange()` 之后调 `c.contains(get(i))`）。
     */
    private class PoisonCollection<E> : AbstractCollection<E>() {
        override val size: Int get() = 0
        override fun iterator(): MutableIterator<E> = throw IllegalStateException("poison")
        override fun contains(element: E): Boolean =
            throw IllegalStateException("injected: poison collection refuses contains()")
    }

    // ==================================================================== F1

    /**
     * `slice-39-F1`：`ObservableKeySet.remove(Object)` 在「键→null」映射上「删了但返回 false」。
     *
     * 期望（`java.util.Set.remove` 契约）：键原本存在 ⇒ 返回 `true`；键确实被移除；通知恰好一条；
     * 第二次删同一个键 ⇒ 返回 `false`（这次才是"原本不存在"）。
     */
    @Test
    fun keySetRemoveReportsTrueWhenMappedValueIsNull() {
        val backing = HashMap<String, String?>()
        backing["k"] = null
        // Interface-typed on purpose: Kotlin maps `java.util.Map` onto `kotlin.collections`, so the
        // view is reached through `.keys` (which compiles to `keySet()`) instead of `keySet()`.
        val map: ObservableMap<String, String?> = ObservableMapWrapper(backing)

        var removedEvents = 0
        map.addListener(MapChangeListener<String, String?> { change ->
            if (change.wasRemoved()) removedEvents++
        })

        val first = map.keys.remove("k")
        Assert.assertTrue(
            "Set.remove must report true when the key was present " +
                "(backing map held an explicit key->null mapping)",
            first
        )
        Assert.assertFalse("the key must actually be gone", map.containsKey("k"))
        Assert.assertEquals("exactly one removal notification must be fired", 1, removedEvents)

        val second = map.keys.remove("k")
        Assert.assertFalse("removing an absent key must report false", second)
        Assert.assertEquals("no second notification for an absent key", 1, removedEvents)
    }

    // ==================================================================== F5

    /**
     * `slice-39-F5`：`ObservableEntrySet.toArray(T[] a)` 用 `array.length` 当边界，
     * 把「元素之后第一格必须是 null」的哨兵位写成了包装 null 的 `ObservableEntry`。
     *
     * 期望（`Collection.toArray(T[])` 契约）：数组够大时返回**同一个数组**；`size()` 之后的每一格
     * 保持 `null`；前 `size()` 格是可读的 entry。
     */
    @Test
    fun entrySetToArrayLeavesNullSentinelAfterElements() {
        val backing = HashMap<String, String>()
        backing["a"] = "1"
        backing["b"] = "2"
        val map: ObservableMap<String, String> = ObservableMapWrapper(backing)

        val target = arrayOfNulls<Map.Entry<String, String>>(8)
        // Reached through the raw `java.util.Set` type: Kotlin's mapped `Collection` hides the
        // Java `toArray(T[])` overload behind `toTypedArray()`, which would not exercise the code
        // under test at all.
        val entries = map.entries as java.util.Set<Map.Entry<String, String>>
        val result = entries.toArray(target)

        Assert.assertSame(
            "toArray(T[]) must return the supplied array when it is large enough",
            target,
            result
        )
        Assert.assertEquals("caller array must describe exactly the 2 mappings", 2, backing.size)
        for (i in 2 until 8) {
            Assert.assertNull(
                "slot $i is after the last element and must stay null " +
                    "(Collection.toArray(T[]) sentinel contract)",
                result[i]
            )
        }
        val keys = ArrayList<String>()
        for (i in 0 until 2) {
            val e = result[i]
            Assert.assertNotNull("slot $i must hold an entry", e)
            keys.add(e!!.key)
        }
        keys.sort()
        Assert.assertEquals("every entry must be readable through getKey()", listOf("a", "b"), keys)
    }

    // ==================================================================== F3

    /**
     * `slice-39-F3`：`listIterator.remove()` 在 `previous()` 之后报告 `previousIndex()`，
     * 比被删元素的实际下标小 1（`previous()` 之后游标停在被返回元素**之前**，下标是 `nextIndex()`）。
     */
    @Test
    fun listIteratorRemoveAfterPreviousReportsRemovedIndex() {
        val list = ObservableSequentialListWrapper(LinkedList(listOf("a", "b", "c")))
        val rec = ChangeRecorder<String>()
        list.addListener(rec)

        val it = list.listIterator(3)
        Assert.assertEquals("precondition: previous() must return the last element", "c", it.previous())
        it.remove()

        Assert.assertEquals("exactly one change must be reported", 1, rec.records.size)
        val (from, to, removed) = rec.records[0]
        Assert.assertEquals(
            "remove() after previous() must report the index of the REMOVED element (2), not previousIndex()",
            2,
            from
        )
        Assert.assertEquals("a pure removal has from == to", 2, to)
        Assert.assertEquals("the removed element must be the one previous() returned", listOf("c"), removed)
        Assert.assertEquals("the element must actually be gone", listOf("a", "b"), list)
    }

    /** `slice-39-F3` 的 `set()` 分支：`previous()` 之后 `nextSet` 同样偏 1。 */
    @Test
    fun listIteratorSetAfterPreviousReportsReplacedIndex() {
        val list = ObservableSequentialListWrapper(LinkedList(listOf("a", "b", "c")))
        val rec = ChangeRecorder<String>()
        list.addListener(rec)

        val it = list.listIterator(3)
        Assert.assertEquals("precondition", "c", it.previous())
        it.set("X")

        Assert.assertEquals("exactly one change must be reported", 1, rec.records.size)
        val (from, to, removed) = rec.records[0]
        Assert.assertEquals(
            "set() after previous() must report the replaced index (2), not previousIndex()",
            2,
            from
        )
        Assert.assertEquals("a set is from..idx+1", 3, to)
        Assert.assertEquals("the old value at idx 2 must be reported", listOf("c"), removed)
        Assert.assertEquals("the replacement must have landed at idx 2", listOf("a", "b", "X"), list)
    }

    /**
     * 既有正确行为守卫：`next()` + `remove()` 下 `previousIndex()` 恰好等于被删元素下标，
     * F3 的修复**不得**把它改坏。
     */
    @Test
    fun listIteratorRemoveAfterNextKeepsReportingCorrectIndex() {
        val list = ObservableSequentialListWrapper(LinkedList(listOf("a", "b", "c")))
        val rec = ChangeRecorder<String>()
        list.addListener(rec)

        val it = list.listIterator(0)
        Assert.assertEquals("precondition", "a", it.next())
        it.remove()

        Assert.assertEquals("exactly one change must be reported", 1, rec.records.size)
        val (from, to, removed) = rec.records[0]
        Assert.assertEquals("next() + remove() must report index 0", 0, from)
        Assert.assertEquals("a pure removal has from == to", 0, to)
        Assert.assertEquals("the removed element must be the one next() returned", listOf("a"), removed)
    }

    // ==================================================================== F2

    /**
     * `slice-39-F2` 第 1 格：`ObservableSequentialListWrapper.listIterator().remove()` 在
     * 未 `next()`/`previous()` 时由 JDK 迭代器抛 `IllegalStateException`，`endChange()` 被跳过。
     */
    @Test
    fun sequentialIteratorRemoveFailureClosesChangeBlock() {
        val list = ObservableSequentialListWrapper(LinkedList(listOf("a", "b", "c")))
        val counter = CountingListener<String>()
        list.addListener(counter)

        var threw = false
        try {
            list.listIterator(0).remove()
        } catch (e: IllegalStateException) {
            threw = true
        }
        Assert.assertTrue("precondition: iterator.remove() without next()/previous() must throw ISE", threw)
        Assert.assertEquals(
            "the beginChange()/endChange() block must be closed when the exception escapes " +
                "(ListChangeBuilder.changeLock must be back to 0)",
            0,
            changeLockOf(list)
        )

        counter.count = 0
        list.add("z")
        Assert.assertEquals(
            "a later legal write must still notify listeners; a half-open change block suppresses it forever",
            1,
            counter.count
        )
    }

    /** `slice-39-F2` 第 2 格：`listIterator().set(E)` 同构。 */
    @Test
    fun sequentialIteratorSetFailureClosesChangeBlock() {
        val list = ObservableSequentialListWrapper(LinkedList(listOf("a", "b", "c")))
        val counter = CountingListener<String>()
        list.addListener(counter)

        var threw = false
        try {
            list.listIterator(0).set("q")
        } catch (e: IllegalStateException) {
            threw = true
        }
        Assert.assertTrue("precondition: iterator.set() without next()/previous() must throw ISE", threw)
        Assert.assertEquals(
            "the change block must be closed when iterator.set() throws",
            0,
            changeLockOf(list)
        )

        counter.count = 0
        list.set(0, "z")
        Assert.assertEquals("a later legal set() must still notify listeners", 1, counter.count)
    }

    /**
     * `slice-39-F2` 第 3 格：`ObservableSequentialListWrapper.addAll(int, Collection)` 的
     * `endChange()` 在 `try` 内，`catch` 只捕 `NoSuchElementException`，
     * `listIterator(index)` 抛的 `IndexOutOfBoundsException` 直接逸出。
     */
    @Test
    fun sequentialAddAllFailureClosesChangeBlock() {
        val list = ObservableSequentialListWrapper(LinkedList(listOf("a")))
        val counter = CountingListener<String>()
        list.addListener(counter)

        var threw = false
        try {
            list.addAll(5, arrayListOf("x"))
        } catch (e: IndexOutOfBoundsException) {
            threw = true
        }
        Assert.assertTrue("precondition: addAll(5, ...) on a 1-element list must throw IOOBE", threw)
        Assert.assertEquals(
            "the change block must be closed when addAll(int, Collection) throws",
            0,
            changeLockOf(list)
        )

        counter.count = 0
        list.add("z")
        Assert.assertEquals("a later legal add must still notify listeners", 1, counter.count)
    }

    /**
     * `slice-39-F2` 第 4 格：`ObservableListWrapper.clear()` 在 `backingList.clear()` 抛
     * `UnsupportedOperationException` 时不执行 `endChange()`（且 `nextRemove` 已登记）。
     */
    @Test
    fun listWrapperClearFailureClosesChangeBlock() {
        val backing = FlakyClearList(listOf("a"))
        val list = ObservableListWrapper(backing)
        val counter = CountingListener<String>()
        list.addListener(counter)

        var threw = false
        try {
            list.clear()
        } catch (e: UnsupportedOperationException) {
            threw = true
        }
        Assert.assertTrue("precondition: the injected backing list must refuse the first clear()", threw)
        Assert.assertEquals(
            "the change block must be closed when backingList.clear() throws",
            0,
            changeLockOf(list)
        )

        counter.count = 0
        list.clear()
        Assert.assertEquals(
            "a later legal clear() must still notify listeners " +
                "(a half-open block would swallow it)",
            1,
            counter.count
        )
    }

    /**
     * `slice-39-F2` 第 5 格：`SortedList.sourceChanged` 的 `beginChange()` 无 `try/finally`，
     * 比较器抛异常后 change 构建器停在半开状态。
     *
     * 异常在 `ListListenerHelper` 的逐监听器 `catch (Exception)` 里被隔离（上游既有行为，
     * 不是本缺陷的一部分），所以不从 `source.add` 的调用方观察结果，而是直接读构建器状态。
     *
     * ## 为什么这一格不沿用"后续通知必须到达"的后果断言
     *
     * `SortedList` 在比较器抛异常时**自身映射也已经失步**（`insertToMapping` 的 `++size`
     * 在其后，源列表已多一个元素而 `sorted` 数组没有），于是下一次源变更会在
     * `System.arraycopy` 处抛 `ArrayIndexOutOfBoundsException`。那是**另一个**缺陷
     * （data-consistency，不在本单），它会让"后续通知"这条断言无论修复与否都红。
     * 所以这一格改用**可累积性**作为后果断言：半开块会逐次累积，
     * 第二次失败后 `changeLock` 应当是 0 而不是 2。
     */
    @Test
    fun sortedListSourceChangeFailureClosesChangeBlock() {
        val source = FXCollections.observableArrayList<String>()
        source.addAll("a", "b")
        val comparator = Comparator<String> { x, y ->
            if (x == "boom" || y == "boom") {
                throw IllegalStateException("injected: comparator refuses the element 'boom'")
            }
            x.compareTo(y)
        }
        val sorted = SortedList(source, comparator)
        val counter = CountingListener<String>()
        sorted.addListener(counter)

        source.add("boom")

        Assert.assertEquals(
            "SortedList.sourceChanged must close its beginChange()/endChange() block when the " +
                "comparator throws inside it",
            0,
            changeLockOf(sorted)
        )

        // 第二次失败：半开块在这里会累积成 2，修复后每次都被 finally 复位
        try {
            source.add("boom2")
        } catch (e: RuntimeException) {
            // 具体异常类型取决于 SortedList 自身的映射状态（见上方注释），本格不关心
        }
        Assert.assertEquals(
            "a second failing source change must not accumulate a second half-open block",
            0,
            changeLockOf(sorted)
        )
    }

    /** `slice-39-F2` 第 6 格：`FilteredList.sourceChanged` 同构（谓词抛异常）。 */
    @Test
    fun filteredListSourceChangeFailureClosesChangeBlock() {
        val source = FXCollections.observableArrayList<String>()
        source.addAll("a", "b")
        val predicate = Predicate<String> { e ->
            if (e == "boom") throw IllegalStateException("injected: predicate refuses the element 'boom'")
            true
        }
        val filtered = FilteredList(source, predicate)
        val counter = CountingListener<String>()
        filtered.addListener(counter)

        source.add("boom")

        Assert.assertEquals(
            "FilteredList.sourceChanged must close its beginChange()/endChange() block when the " +
                "predicate throws inside it",
            0,
            changeLockOf(filtered)
        )

        counter.count = 0
        source.add("c")
        Assert.assertEquals(
            "a later source change must still reach FilteredList listeners",
            1,
            counter.count
        )
    }

    /**
     * `slice-39-F2` 第 7 格：`ObservableListWrapper.removeAll(Collection)` 的 `endChange()` 在
     * 元素扫描之后、且不在 `finally` 里 —— 扫描阶段的异常同样留下半开块。
     */
    @Test
    fun listWrapperRemoveAllFailureClosesChangeBlock() {
        val list = ObservableListWrapper(ArrayList(listOf("a", "b")))
        val counter = CountingListener<String>()
        list.addListener(counter)

        var threw = false
        try {
            list.removeAll(PoisonCollection<String>())
        } catch (e: IllegalStateException) {
            threw = true
        }
        Assert.assertTrue("precondition: the poison collection must make the scan throw", threw)
        Assert.assertEquals(
            "the change block must be closed when removeAll(Collection) fails during the scan",
            0,
            changeLockOf(list)
        )

        counter.count = 0
        list.add("z")
        Assert.assertEquals("a later legal add must still notify listeners", 1, counter.count)
    }

    /** `slice-39-F2` 第 8 格：`ObservableListWrapper.retainAll(Collection)` 同构。 */
    @Test
    fun listWrapperRetainAllFailureClosesChangeBlock() {
        val list = ObservableListWrapper(ArrayList(listOf("a", "b")))
        val counter = CountingListener<String>()
        list.addListener(counter)

        var threw = false
        try {
            list.retainAll(PoisonCollection<String>())
        } catch (e: IllegalStateException) {
            threw = true
        }
        Assert.assertTrue("precondition: the poison collection must make the scan throw", threw)
        Assert.assertEquals(
            "the change block must be closed when retainAll(Collection) fails during the scan",
            0,
            changeLockOf(list)
        )

        counter.count = 0
        list.add("z")
        Assert.assertEquals("a later legal add must still notify listeners", 1, counter.count)
    }

    /**
     * `slice-39-F2` 第 9 格：`ObservableListWrapper.remove(int, int)` 的 `endChange()` 不在
     * `finally` 里。用越界区间让 `remove(index)` 在循环中途抛 `IndexOutOfBoundsException`：
     * 第 2 次迭代时列表已被删空，`doRemove(0)` 抛 IOOBE。
     */
    @Test
    fun listWrapperRangeRemoveFailureClosesChangeBlock() {
        val list = ObservableListWrapper(ArrayList(listOf("a")))
        val counter = CountingListener<String>()
        list.addListener(counter)

        var threw = false
        try {
            list.remove(0, 3)
        } catch (e: IndexOutOfBoundsException) {
            threw = true
        }
        Assert.assertTrue("precondition: remove(0,3) on a 1-element list must throw IOOBE", threw)
        Assert.assertEquals(
            "the change block must be closed when remove(int, int) fails mid-loop",
            0,
            changeLockOf(list)
        )

        counter.count = 0
        list.add("z")
        Assert.assertEquals("a later legal add must still notify listeners", 1, counter.count)
    }

    // ==================================================================== F4

    /**
     * `slice-39-F4`：审计主张「`ObservableListBase.setAll(E...)` 转发到恒抛 `UOE` 的
     * `setAll(Collection)`，两个子类均未覆写 ⇒ `setAll` 在可变列表上恒不可用」。
     *
     * 本用例把 `setAll` 两条重载在**两个可变 wrapper** 上各跑一遍，断言实际结局是
     * 「返回 true 且内容被替换」。这一格是 F4 运行时主张的判别器：
     *
     * - 若它是 `UOE` ⇒ 审计成立（缺陷是漏实现，修法应补实现）；
     * - 若它返回 `true` ⇒ 审计的运行时主张不成立（中间类 `ModifiableObservableListBase`
     *   已经提供了实现，审计只读了 slice-39 的 7 个文件，没读那个中间类），
     *   残留的缺陷只剩「接口 javadoc 未声明只读实现会抛 UOE」这个**声明缺口**。
     */
    @Test
    fun setAllOnModifiableWrappersReplacesContent() {
        val randomAccess = FXCollections.observableList(ArrayList(listOf("a", "b")))
        Assert.assertTrue(
            "setAll(Collection) on an ObservableListWrapper must work " +
                "(ModifiableObservableListBase provides the implementation)",
            randomAccess.setAll(arrayListOf("x", "y", "z"))
        )
        Assert.assertEquals("content must be replaced by setAll(Collection)", listOf("x", "y", "z"), randomAccess)

        val sequential = FXCollections.observableList(LinkedList(listOf("a", "b")))
        Assert.assertTrue(
            "setAll(E...) on an ObservableSequentialListWrapper must work",
            sequential.setAll("x", "y", "z")
        )
        Assert.assertEquals("content must be replaced by setAll(E...)", listOf("x", "y", "z"), sequential)

        // 空表上 setAll(空集合) 必须是 false（ModifiableObservableListBase 的既有契约）
        val empty = FXCollections.observableList(ArrayList<String>())
        Assert.assertFalse(
            "setAll(empty) on an empty list must report false (no change)",
            empty.setAll(ArrayList<String>())
        )
    }

    /**
     * `slice-39-F4` 的另一半：判据的核心是「只读实现抛 UOE **是正确行为**」。
     *
     * `SortedList`（`TransformationList` → `ObservableListBase`）不覆写 `setAll`，
     * 它的写方法本就该抛 UOE —— 它是源列表的**派生视图**，`setAll` 无意义。
     * 若这一格不抛 UOE，则「UOE 是只读默认」这个判据不成立，F4 就必须按"漏实现"修。
     */
    @Test
    fun setAllOnReadOnlyTransformationListThrowsUOE() {
        val source = FXCollections.observableArrayList<String>()
        source.addAll("a")
        val sorted = SortedList(source, Comparator<String> { x, y -> x.compareTo(y) })

        var threwCollection = false
        try {
            sorted.setAll(arrayListOf("q"))
        } catch (e: UnsupportedOperationException) {
            threwCollection = true
        }
        Assert.assertTrue(
            "setAll(Collection) on a read-only derived list (SortedList) must throw UOE; " +
                "this is the behaviour the fixed javadoc must declare",
            threwCollection
        )

        var threwVararg = false
        try {
            sorted.setAll("q")
        } catch (e: UnsupportedOperationException) {
            threwVararg = true
        }
        Assert.assertTrue("setAll(E...) must forward to the same read-only path", threwVararg)
    }
}
