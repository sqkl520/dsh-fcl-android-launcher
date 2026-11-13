package com.dsh

import com.tungsten.fclcore.fakefx.collections.ListChangeListener
import com.tungsten.fclcore.fakefx.collections.ObservableListWrapper
import org.junit.Assert
import org.junit.Test
import java.lang.reflect.Field
import java.util.ArrayList
import java.util.Collection

/**
 * round31 / `r31-vi-35`（`fix-r31-220` 回派单）。
 *
 * ## 缺陷
 *
 * `ObservableListWrapper.removeAll(Collection)` / `retainAll(Collection)` 在
 * `beginChange()` **之后**、`try` **之外**执行 `new BitSet(c.size())`。`c == null` 时
 * `c.size()` 抛 NPE，`finally { endChange() }` 因此**永远不会执行** ⇒
 * `ListChangeBuilder.changeLock` 永久停在 1 ⇒ 此后**所有**变更通知被 `commit()` 的
 * `if (changeLock == 0 …)` 静默吞掉。
 *
 * 这是 `fix-219`（`slice-39-F2`）的漏改：它给这两个方法加了 `try/finally`，但把
 * `new BitSet(c.size())` 那一行留在了 `beginChange()` 与 `try` 之间。
 *
 * ## 断言口径（全部是运行时值，不是源码文本）
 *
 * 每个用例三个值一起看，缺一不可：
 * 1. `c == null` 时**确实抛了** `NullPointerException`（前提断言 —— 不抛的话后面的断言无意义）；
 * 2. 反射读到的 `changeLock` **实际整数** 必须是 0；
 * 3. 随后一次**合法**写操作的监听通知数必须是 1（后果断言）。
 *
 * 只断 (2) 会把"异常前根本没 beginChange"当成绿；只断 (3) 在"块没闭合但恰好没影响"的实现上也会绿。
 * (2)+(3) 一起才能区分「块闭合了」与「块没闭合但恰好没影响」。
 *
 * ## 为什么用反射读 `changeLock`
 *
 * `beginChange`/`endChange` 是 `protected final`，`ListChangeBuilder` 是包私有类，从 `com.dsh`
 * 无法直接触摸。反射失败会**断言失败**（见 [changeLockOf] 的 `assertNotNull`），不做"返回 0"的兜底。
 */
class R31Fix220Test {

    // ------------------------------------------------------------------ 反射工具

    /**
     * 读任意 `ObservableListBase` 子类实例的 `ListChangeBuilder.changeLock`。
     *
     * `changeBuilder` 字段声明在 `ObservableListBase`（`ObservableListWrapper` 隔了两层父类），
     * 所以沿父类链找；找不到就断言失败 —— 反射问题绝不能洗成绿。
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

    /** 只数通知次数。 */
    private class CountingListener<E> : ListChangeListener<E> {
        var count = 0
        override fun onChanged(c: ListChangeListener.Change<out E>) {
            while (c.next()) {
                count++
            }
        }
    }

    /**
     * 以 `null` 实参调用 `Collection.removeAll` / `Collection.retainAll`，返回**从目标方法逸出**的异常。
     *
     * 为什么必须走反射：Kotlin 把 `ObservableListWrapper<String>.removeAll(Collection<?>)` 的参数
     * 收窄成非空的 `Collection<String!>`，“传 null”在 **Kotlin 源码层面编译不过**（这不是缺陷不可复现，
     * 只是语言层的空安全挡住了调用）。而 Java 调用方（`Collection#removeAll` 的契约里明确要求
     * 「指定集合为 null 则抛 NPE」）可以合法传 null —— 反射正是以 Java 的入参规则去调它。
     *
     * 断言口径：`InvocationTargetException` 不是失败，它的 **cause** 才是被测方法抛出的异常；
     * 没有异常时返回 `null`，由调用方断言失败（前提不成立就不能给出绿的结论）。
     */
    private fun invokeWithNullArgument(list: Any, methodName: String): Throwable? {
        val method = java.util.Collection::class.java.getMethod(methodName, java.util.Collection::class.java)
        return try {
            method.invoke(list, null)
            null
        } catch (e: java.lang.reflect.InvocationTargetException) {
            e.targetException
        }
    }

    /**
     * 任一元素比较都抛异常 —— 在 `removeAll` / `retainAll` 的**元素扫描阶段**注入失败
     * （即 `fix-219` 已经兜住的那条路径）。本文件用它做 `fix-219` 不回退的守卫。
     */
    private class PoisonCollection<E> : AbstractCollection<E>() {
        override val size: Int get() = 0
        override fun iterator(): MutableIterator<E> = throw IllegalStateException("poison")
        override fun contains(element: E): Boolean =
            throw IllegalStateException("injected: poison collection refuses contains()")
    }

    // ============================================================ r31-vi-35 主用例

    /**
     * `r31-vi-35`：`removeAll(null)` 必须**不开**变更块、且后续通知不受影响。
     *
     * 改前（`new BitSet(c.size())` 在 `beginChange()` 之后）实测：`changeLock=1`、后续通知 `0`。
     */
    @Test
    fun removeAllNullLeavesChangeLockAtZero() {
        val list = ObservableListWrapper(ArrayList(listOf("a", "b")))
        val counter = CountingListener<String>()
        list.addListener(counter)

        val failure = invokeWithNullArgument(list, "removeAll")
        Assert.assertTrue(
            "precondition: removeAll(null) must throw NullPointerException; actual=$failure",
            failure is NullPointerException
        )
        Assert.assertEquals(
            "removeAll(null) must not leave the change block open " +
                "(ListChangeBuilder.changeLock must be 0)",
            0,
            changeLockOf(list)
        )

        counter.count = 0
        list.add("z")
        Assert.assertEquals(
            "a later legal add must still reach listeners after removeAll(null)",
            1,
            counter.count
        )
    }

    /** `r31-vi-35`：`retainAll(null)` 同构。 */
    @Test
    fun retainAllNullLeavesChangeLockAtZero() {
        val list = ObservableListWrapper(ArrayList(listOf("a", "b")))
        val counter = CountingListener<String>()
        list.addListener(counter)

        val failure = invokeWithNullArgument(list, "retainAll")
        Assert.assertTrue(
            "precondition: retainAll(null) must throw NullPointerException; actual=$failure",
            failure is NullPointerException
        )
        Assert.assertEquals(
            "retainAll(null) must not leave the change block open " +
                "(ListChangeBuilder.changeLock must be 0)",
            0,
            changeLockOf(list)
        )

        counter.count = 0
        list.add("z")
        Assert.assertEquals(
            "a later legal add must still reach listeners after retainAll(null)",
            1,
            counter.count
        )
    }

    // ==================================================== fix-219 不回退守卫

    /**
     * `fix-219` 已兜住的路径不许回退：**扫描阶段**抛异常时，块仍必须闭合。
     *
     * 与上面两条构成一对：`null` 在 `beginChange()` **之前**被挡（本单的修法），
     * `contains()` 在扫描**之中**抛（`fix-219` 的 `try/finally`）。
     * 本单只挪了 `BitSet` 构造的位置，`try/finally` 一字未动 —— 这条用例就是那句话的可执行形式。
     */
    @Test
    fun scanFailureStillClosesChangeBlock() {
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
            "the change block must stay closed when removeAll fails during the scan " +
                "(fix-219 must not regress)",
            0,
            changeLockOf(list)
        )

        counter.count = 0
        list.add("z")
        Assert.assertEquals("a later legal add must still notify listeners", 1, counter.count)
    }
}
