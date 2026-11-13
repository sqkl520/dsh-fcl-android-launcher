package com.dsh

import com.tungsten.fclcore.fakefx.collections.SortHelper
import org.junit.Assert
import org.junit.Test
import java.lang.reflect.Field

/**
 * round31 / slice-40 error-handling regression tests.
 *
 * Evidence is always a **runtime value after the call** (never a source-text assertion):
 *
 * - `slice-40-eh-01-sorthelper-cause-loss`: when `sort(List)` translates an `ArrayStoreException`
 *   into `ClassCastException`, the new exception must (1) still be a `ClassCastException`,
 *   (2) carry the original `ArrayStoreException` as its cause, (3) carry a readable message.
 * - `slice-40-eh-02-sorthelper-perm-not-reset`: when any `sort` overload throws (including `Error`),
 *   `permutation` and `reversePermutation` must already be `null`, and the original throwable must
 *   propagate unchanged.
 *
 * ## Why each failure test seeds sentinel arrays first
 *
 * Checking only "fields are null after failure" cannot distinguish:
 *   (a) the fields were never touched (exception happened before `initPermutation`) -- also null,
 *       would be a false green;
 *   (b) `initPermutation` replaced them with fresh arrays -- the defect;
 *   (c) a `finally` reset them to null -- the fix.
 * Each failure test therefore seeds both fields with length-3 sentinels (101/102/103 and
 * 201/202/203). After the failure the value can only be: sentinel (sort never ran, test invalid),
 * a fresh length-n array (defect), or null (fixed). `assertSeeded` guards against a silent
 * reflection failure that would fake a green.
 *
 * ## Public overload coverage
 *
 * | overload | how it is covered |
 * |---|---|
 * | `sort(List)` | F1 translation + native `compareTo` throwing (null Comparator branch) |
 * | `sort(List, Comparator)` | Comparator throws `RuntimeException` / `Error` |
 * | `sort(T[])` | direct call, native `Comparable` throws (null Comparator branch) |
 * | `sort(T[], Comparator)` | Comparator throws `RuntimeException` / `Error`; non-Comparable CCE; success |
 * | `sort(T[], int, int, Comparator)` | Comparator throws `RuntimeException` / `Error` |
 * | `sort(int[], int, int)` | success path only (see `intArrayRangeSortSortsAndLeavesNoState`: this
 *   overload's `mergeSort` touches only `int` and the two `int[]` fields; it calls no external
 *   code, so no deterministic exception can be injected without bytecode instrumentation). |
 *
 * The four exception shapes required by the dispatch are all exercised on the five overloads
 * that accept caller-controlled elements/comparators:
 *   Comparator -> RuntimeException, Comparator -> Error, non-Comparable -> CCE,
 *   null Comparator + native Comparable -> throw.
 */
class R31SortHelperTest {

    // ------------------------------------------------------------------ reflection helpers

    private fun fieldOf(name: String): Field =
        SortHelper::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun permOf(h: SortHelper): Any? = fieldOf("permutation").get(h)
    private fun rpermOf(h: SortHelper): Any? = fieldOf("reversePermutation").get(h)

    private fun desc(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> if (v.isEmpty()) "int[0]" else "int[${v.size}] first=${v[0]}"
        else -> v.toString()
    }

    /** Seed "leftover state from a previous call" so "never touched" differs from "reset". */
    private fun seedStale(h: SortHelper) {
        fieldOf("permutation").set(h, intArrayOf(101, 102, 103))
        fieldOf("reversePermutation").set(h, intArrayOf(201, 202, 203))
    }

    private fun assertSeeded(h: SortHelper, ctx: String) {
        Assert.assertNotNull(
            "$ctx: sentinel was not planted (reflection failed); this test would be a false green",
            permOf(h)
        )
        Assert.assertNotNull(
            "$ctx: sentinel was not planted (reflection failed); this test would be a false green",
            rpermOf(h)
        )
    }

    /** F2 disproof condition: both fields must be null, with the actual value in the failure text. */
    private fun assertStateReset(h: SortHelper, ctx: String) {
        Assert.assertNull(
            "$ctx: permutation must be reset to null (actual ${desc(permOf(h))});" +
                " non-null means the exception path skipped the reset",
            permOf(h)
        )
        Assert.assertNull(
            "$ctx: reversePermutation must be reset to null (actual ${desc(rpermOf(h))});" +
                " non-null means the exception path skipped the reset",
            rpermOf(h)
        )
    }

    // ------------------------------------------------------------------ throwable fixtures

    private class BoomRuntime(val tag: String) : RuntimeException("boom-runtime:$tag")

    private class BoomError(val tag: String) : Error("boom-error:$tag")

    /** A real `Comparable` (no ArrayStoreException), but `compareTo` throws. */
    private class ThrowingComparable(val tag: String) : Comparable<ThrowingComparable> {
        override fun compareTo(other: ThrowingComparable): Int = throw BoomRuntime(tag)
    }

    /** Return the thrown `Throwable` (including `Error`), or null when nothing was thrown. */
    private fun capture(block: () -> Unit): Throwable? {
        var t: Throwable? = null
        try {
            block()
        } catch (x: Throwable) {
            t = x
        }
        return t
    }

    private fun assertThrows(tag: String, block: () -> Unit) {
        Assert.assertNotNull("$tag: expected an exception but none was thrown", capture(block))
    }

    // ================================================================== F1
    // slice-40-eh-01-sorthelper-cause-loss

    /**
     * F1 disproof, three assertions at once:
     * 1. still `ClassCastException` (javadoc contract in `SortableList.java:39-44`);
     * 2. `cause` is the original `ArrayStoreException`;
     * 3. `message` is non-null and names "Comparable".
     *
     * Before the fix `throw new ClassCastException()` fails (2) and (3).
     */
    @Test
    fun nonComparableListThrowsClassCastExceptionWithCauseAndMessage() {
        // Emulates the raw list at `ObservableListWrapper.java:188`
        // (`(List<? extends Comparable>)backingList`): the static type says Comparable,
        // but the runtime element is not.
        val list = ArrayList<Comparable<Any>>()
        @Suppress("UNCHECKED_CAST")
        val sneaky = list as MutableList<Any?>
        sneaky.add(Any())

        val h = SortHelper()
        val thrown = capture { h.sort(list) }

        Assert.assertNotNull(
            "a non-Comparable element must throw (null here would mean the toArray branch is unreachable" +
                " and the F1 premise needs re-checking)",
            thrown
        )
        Assert.assertTrue(
            "contract requires ClassCastException (SortableList.java:39-44), actual ${thrown!!::class.java.name}",
            thrown is ClassCastException
        )
        val cause = thrown.cause
        Assert.assertNotNull(
            "the original ArrayStoreException must be kept as cause (cause=null = F1 unfixed)",
            cause
        )
        Assert.assertTrue(
            "cause must be the original ArrayStoreException, actual ${cause!!::class.java.name}",
            cause is ArrayStoreException
        )
        val msg = thrown.message
        Assert.assertNotNull("ClassCastException must carry a readable message (null = F1 unfixed)", msg)
        Assert.assertTrue(
            "message must name the non-Comparable cause, actual <$msg>",
            msg!!.contains("Comparable")
        )
    }

    /**
     * Over-fix guard: an element that IS Comparable but whose `compareTo` throws must propagate
     * its own exception unchanged; it must not be wrapped into a `ClassCastException`.
     * Two elements are required: the insertion-sort loop does not call `compareTo` for a 1-element array.
     */
    @Test
    fun throwingComparableListPropagatesOriginalExceptionUntouched() {
        val h = SortHelper()
        val thrown = capture {
            h.sort(arrayListOf(ThrowingComparable("list-native"), ThrowingComparable("list-native-2")))
        }

        Assert.assertTrue(
            "BoomRuntime must propagate untouched (not translated to CCE, not wrapped), actual $thrown",
            thrown is BoomRuntime
        )
        Assert.assertEquals("list-native", (thrown as BoomRuntime).tag)
        Assert.assertNull(
            "this branch is not a toArray type translation; the original exception must not gain a cause",
            thrown.cause
        )
    }

    /** Regression anchor: with an explicit Comparator, non-Comparable elements still sort (`Object[]`). */
    @Test
    fun listWithExplicitComparatorStillSortsNonComparableElements() {
        val list = arrayListOf<Any?>(3, 1, 2)
        val h = SortHelper()
        h.sort(list, Comparator { a, b -> (a as Int) - (b as Int) })
        Assert.assertEquals(listOf<Any?>(1, 2, 3), list)
    }

    // ================================================================== F2
    // slice-40-eh-02-sorthelper-perm-not-reset
    //
    // Every failure test: seed sentinels -> really throw once -> assert both fields are null
    // and the original throwable propagates. Exception shapes:
    //   Comparator RuntimeException / Comparator Error / non-Comparable CCE /
    //   null Comparator + native Comparable throwing.

    /** Shape 1: `sort(T[], Comparator)` + `RuntimeException`. */
    @Test
    fun arraySortWithThrowingComparatorResetsState() {
        val h = SortHelper()
        seedStale(h)
        assertSeeded(h, "sort(T[],Comparator)/RuntimeException")

        val marker = BoomRuntime("array-cmp")
        val thrown = capture {
            h.sort(arrayOf(5, 4, 3, 2, 1), Comparator { _, _ -> throw marker })
        }

        Assert.assertSame("exception must propagate unchanged (not swallowed or replaced)", marker, thrown)
        assertStateReset(h, "sort(T[],Comparator)/RuntimeException")
    }

    /** Shape 2: `sort(T[], Comparator)` + `Error` (Error must also reset, and must not be replaced). */
    @Test
    fun arraySortWithThrowingComparatorPropagatesErrorAndResetsState() {
        val h = SortHelper()
        seedStale(h)
        assertSeeded(h, "sort(T[],Comparator)/Error")

        val marker = BoomError("array-cmp-error")
        val thrown = capture {
            h.sort(arrayOf(5, 4, 3, 2, 1), Comparator { _, _ -> throw marker })
        }

        Assert.assertSame("Error must propagate unchanged (finally resets only, never swallows)", marker, thrown)
        assertStateReset(h, "sort(T[],Comparator)/Error")
    }

    /**
     * Shape 3: non-mutually-comparable elements + `null` comparator => the raw
     * `((Comparable) dest[j-1])` cast inside `mergeSort(Object[])` throws CCE.
     */
    @Test
    fun arraySortWithNonComparableElementsResetsState() {
        val h = SortHelper()
        seedStale(h)
        assertSeeded(h, "sort(T[],null)/CCE")

        val thrown = capture { h.sort(arrayOf<Any>(Any(), Any()), null) }

        Assert.assertNotNull("non-comparable elements must throw (otherwise the test is invalid)", thrown)
        Assert.assertTrue(
            "expected ClassCastException, actual ${thrown!!::class.java.name}",
            thrown is ClassCastException
        )
        assertStateReset(h, "sort(T[],null)/CCE")
    }

    /** Range overload + shape 1. */
    @Test
    fun arrayRangeSortWithThrowingComparatorResetsState() {
        val h = SortHelper()
        seedStale(h)
        assertSeeded(h, "sort(T[],int,int,Comparator)/RuntimeException")

        val marker = BoomRuntime("range-cmp")
        val thrown = capture {
            h.sort(arrayOf(5, 4, 3, 2, 1), 0, 5, Comparator { _, _ -> throw marker })
        }

        Assert.assertSame("exception must propagate unchanged", marker, thrown)
        assertStateReset(h, "sort(T[],int,int,Comparator)/RuntimeException")
    }

    /** Range overload + shape 2. */
    @Test
    fun arrayRangeSortWithThrowingComparatorPropagatesErrorAndResetsState() {
        val h = SortHelper()
        seedStale(h)
        assertSeeded(h, "sort(T[],int,int,Comparator)/Error")

        val marker = BoomError("range-cmp-error")
        val thrown = capture {
            h.sort(arrayOf(5, 4, 3, 2, 1), 0, 5, Comparator { _, _ -> throw marker })
        }

        Assert.assertSame("Error must propagate unchanged", marker, thrown)
        assertStateReset(h, "sort(T[],int,int,Comparator)/Error")
    }

    /** Shape 4: `sort(List)` uses the `null`-comparator branch; native `Comparable.compareTo` throws. */
    @Test
    fun listSortWithThrowingCompareToResetsState() {
        val h = SortHelper()
        seedStale(h)
        assertSeeded(h, "sort(List)/native-throw")

        val marker = capture {
            h.sort(arrayListOf(ThrowingComparable("list-cmp"), ThrowingComparable("list-cmp-2")))
        }

        Assert.assertTrue("expected the original BoomRuntime, actual $marker", marker is BoomRuntime)
        assertStateReset(h, "sort(List)/native-throw")
    }

    /** Shape 4 through the `sort(T[])` overload directly (it delegates to `sort(T[], null)`). */
    @Test
    fun arrayNoComparatorSortWithThrowingCompareToResetsState() {
        val h = SortHelper()
        seedStale(h)
        assertSeeded(h, "sort(T[])/native-throw")

        val marker = capture {
            h.sort(arrayOf(ThrowingComparable("array-native"), ThrowingComparable("array-native-2")))
        }

        Assert.assertTrue("expected the original BoomRuntime, actual $marker", marker is BoomRuntime)
        assertStateReset(h, "sort(T[])/native-throw")
    }

    /**
     * Shape 1 through `sort(List, Comparator)` (delegates to `sort(Object[], Comparator)`).
     * This proves the delegating overload is covered too, not only the array-core overload.
     */
    @Test
    fun listSortWithComparatorResetsState() {
        val h = SortHelper()
        seedStale(h)
        assertSeeded(h, "sort(List,Comparator)/RuntimeException")

        val marker = BoomRuntime("list-with-cmp")
        val thrown = capture {
            h.sort(arrayListOf(3, 1, 2), Comparator { _, _ -> throw marker })
        }

        Assert.assertSame("exception must propagate unchanged", marker, thrown)
        assertStateReset(h, "sort(List,Comparator)/RuntimeException")
    }

    /** Shape 2 through the same delegating list overload. */
    @Test
    fun listSortWithComparatorPropagatesErrorAndResetsState() {
        val h = SortHelper()
        seedStale(h)
        assertSeeded(h, "sort(List,Comparator)/Error")

        val marker = BoomError("list-with-cmp-error")
        val thrown = capture {
            h.sort(arrayListOf(3, 1, 2), Comparator { _, _ -> throw marker })
        }

        Assert.assertSame("Error must propagate unchanged", marker, thrown)
        assertStateReset(h, "sort(List,Comparator)/Error")
    }

    /**
     * `sort(int[], int, int)` success path: even after seeding sentinels, a successful sort must
     * leave both fields null.
     *
     * This overload's exception path cannot be constructed from a test: `mergeSort(int[], ...)`
     * only reads `int` values and the two `int[]` fields; it calls no `Comparator`, performs no raw
     * `Comparable` cast, and `rangeCheck` has already bounded every index. Its exception-path
     * reset therefore shares the same `finally` shape as the other three reset-bearing overloads;
     * the report states this limitation explicitly rather than faking it with a source-text assertion.
     */
    @Test
    fun intArrayRangeSortSortsAndLeavesNoState() {
        val h = SortHelper()
        seedStale(h)
        assertSeeded(h, "sort(int[],int,int)/success")

        val arr = intArrayOf(3, 1, 2)
        val perm = h.sort(arr, 0, 3)

        Assert.assertEquals("sorted result", listOf(1, 2, 3), arr.toList())
        Assert.assertEquals("permutation length must equal the range length", 3, perm.size)
        perm.forEach { p ->
            Assert.assertTrue("permutation value out of range: $p", p >= 0 && p < 3)
        }
        assertStateReset(h, "sort(int[],int,int)/success")
    }

    /** Success-path counterpart: normal `sort(T[], Comparator)` also ends with null fields. */
    @Test
    fun successfulArraySortResetsStateAfterSeeding() {
        val h = SortHelper()
        seedStale(h)
        assertSeeded(h, "sort(T[],Comparator)/success")

        val arr = arrayOf(5, 4, 3, 2, 1)
        h.sort(arr, Comparator { x, y -> x - y })

        Assert.assertEquals("sorted result", listOf(1, 2, 3, 4, 5), arr.toList())
        assertStateReset(h, "sort(T[],Comparator)/success")
    }

    /**
     * Range validation runs before `initPermutation`: an illegal range must keep
     * `IllegalArgumentException` and must not leave freshly allocated state. This guards against
     * moving the validation inside the new `try` in a way that changes the failure shape.
     */
    @Test
    fun badRangeFailsBeforeTouchingState() {
        val h = SortHelper()
        val thrown = capture { h.sort(intArrayOf(1, 2, 3), 2, 1) }

        Assert.assertTrue(
            "illegal range must still throw IllegalArgumentException, actual $thrown",
            thrown is IllegalArgumentException
        )
        Assert.assertNull("failed validation must not leave permutation", permOf(h))
        Assert.assertNull("failed validation must not leave reversePermutation", rpermOf(h))
    }

    /** Reverse anchor: `sort(List)` still returns a valid permutation after the fix. */
    @Test
    fun listSortReturnsValidPermutation() {
        val h = SortHelper()
        val list = arrayListOf("b", "a", "c")
        val perm = h.sort(list)

        Assert.assertEquals("list must be sorted in place", listOf("a", "b", "c"), list)
        Assert.assertEquals("permutation length must equal list length", 3, perm.size)
        val seen = BooleanArray(3)
        perm.forEach { p ->
            Assert.assertTrue("permutation value out of range: $p", p in 0..2)
            Assert.assertFalse("duplicate permutation value: $p", seen[p])
            seen[p] = true
        }
    }
}
