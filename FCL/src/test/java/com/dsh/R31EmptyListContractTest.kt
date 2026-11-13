package com.dsh

import com.tungsten.fclcore.fakefx.collections.FXCollections
import com.tungsten.fclcore.fakefx.collections.ImmutableObservableList
import com.tungsten.fclcore.fakefx.collections.ObservableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.Collections

/**
 * R31 / `r31-s36-contract-01` + `r31-s36-contract-02`（串行组 `G3`，路线 **ii**）
 *
 * ## 这组钉的是什么
 *
 * `FXCollections.emptyObservableList()` 与 `ImmutableObservableList` 都宣称"不可变"，
 * 但实际只挡了 `ObservableList` 的 6 个 vararg/range 写方法。`AbstractList`/`AbstractCollection`
 * 继承来的写方法在**元素数使操作成为空操作**时会静默返回 `false`/`void`，调用方拿不到
 * "这表不可写"的信号。修复（路线 ii）**不改变任何运行时行为**，只把这条语义缺口写进
 * Javadoc 并补 `final`；本文件是那份 Javadoc 的**可执行版本**。
 *
 * ## 为什么断言这么写
 *
 * 本轮的教训是"注释/结构断言不构成证据"（`fix-210` M2 逃脱、`verify-210` 六种逃脱）。
 * 所以这里**不看注释、不看源码文本**，而是把 [ObservableList] 的 30 个写操作在**真实类实例**
 * 上逐个跑一遍，断言每个操作的**实际结局**（抛 UOE / 抛 ISE / 静默返回）。
 *
 * 如果将来有人把静默改成抛 UOE（或反之），矩阵立刻红。
 *
 * ## 为什么必须是真实类、不能内置"同构复刻类"
 *
 * 复刻类测的是复刻类。下面 [EMPTY_SINGLETON_MATRIX]、[IMMUTABLE_SIZE0_MATRIX]、
 * [IMMUTABLE_SIZE1_MATRIX] 三张表都是从**仓库已编译产物**上实测得到的（`javap` 可复核），
 * 测试通过 [FXCollections.emptyObservableList] 与 `new ImmutableObservableList(...)`
 * 取得真对象。特别地 [Outcome] 里 `emptyObservableList()` 与 `ImmutableObservableList`(size 0)
 * 在 **iterator().remove() 未 next()** 这一格上**不同**（UOE vs ISE）——
 * 这一格的存在本身就证明它们是两份不同实现，任何"统一成同一份"的重构都会被它抓住。
 *
 * ## 为什么 size 0 与 size 1 两张表都要跑
 *
 * Javadoc 的措辞是"当元素数使操作成为空操作时"。这句话有两种读法：
 * "空表上一切静默"（错）和"只有真的无需改动时才静默"（对）。
 * 两张表必须跑出来的东西是 **`clear()` 在 size 0 静默、在 size 1 抛 UOE** ——
 * 这一格差异就是"静默是有条件的"的机器可验版本，由
 * [immutableListSilenceIsConditionalOnNoOp] 单独再把这条钉一次。
 */
class R31EmptyListContractTest {

    /** 一次写操作的结局。用 enum 而不是字符串，是为了让断言消息里的差异一眼可读。 */
    private enum class Outcome(val label: String) {
        UOE("UnsupportedOperationException"),
        ISE("IllegalStateException"),
        SILENT("no exception");
    }

    /**
     * 30 个写操作及其结局。**顺序是契约的一部分** —— 三张表按同一顺序对齐。
     * 每个元素 = 操作名 to (执行体 -> 结局判定)。
     */
    private class Op(val name: String, val body: (ObservableList<String>) -> Unit)

    private fun ops(): List<Op> = listOf(
        Op("add(E)") { it.add("a") },
        Op("add(0,E)") { it.add(0, "a") },
        Op("addAll(Collection[a])") { it.addAll(arrayListOf("a")) },
        Op("addAll(Collection[])") { it.addAll(ArrayList<String>()) },
        Op("addAll(0,Collection[])") { it.addAll(0, ArrayList<String>()) },
        Op("addAll(0,Collection[a])") { it.addAll(0, arrayListOf("a")) },
        Op("set(0,E)") { it.set(0, "a") },
        Op("remove(int 0)") { it.removeAt(0) },
        Op("remove(Object x)") { it.remove("x") },
        Op("clear()") { it.clear() },
        Op("removeAll(Collection[])") { it.removeAll(ArrayList<String>()) },
        Op("removeAll(Collection[a])") { it.removeAll(arrayListOf("a")) },
        Op("retainAll(Collection[])") { it.retainAll(ArrayList<String>()) },
        Op("retainAll(Collection[a])") { it.retainAll(arrayListOf("a")) },
        Op("removeIf(false)") { it.removeIf { false } },
        Op("removeIf(true)") { it.removeIf { true } },
        // Collections.sort(list, null) 在 Java 8+ 上就是 list.sort(null)：这是调用方最自然的写法，
        // 因此比反射调 List.sort 更能代表"真实调用路径"。结局仍由 AbstractList.set 决定。
        Op("sort(null)") { Collections.sort(it, null) },
        Op("replaceAll(identity)") { it.replaceAll { x -> x } },
        Op("subList(0,0).clear()") { it.subList(0, 0).clear() },
        // 这 5 个是 ObservableList 的 vararg 写方法（E...），Kotlin 侧必须用 spread。
        // 若去掉 `*`，Kotlin 会解析到 Collection 重载或直接编译失败 —— 那时测的就不是 vararg 版了。
        Op("addAll(E[a])") { it.addAll(*arrayOf("a")) },
        Op("addAll(E[empty])") { it.addAll(*emptyArray<String>()) },
        Op("setAll(E[a])") { it.setAll(*arrayOf("a")) },
        Op("setAll(Collection[])") { it.setAll(ArrayList<String>()) },
        Op("removeAll(E[a])") { it.removeAll(*arrayOf("a")) },
        Op("retainAll(E[a])") { it.retainAll(*arrayOf("a")) },
        Op("remove(0,0)") { it.remove(0, 0) },
        Op("iterator().remove() [no next]") { it.iterator().remove() },
        Op("iterator() drain + remove()") { it.iterator().let { i -> while (i.hasNext()) { i.next(); i.remove() } } },
        Op("listIterator().add(E)") { it.listIterator().add("a") },
        Op("subList(0,size).clear()") { it.subList(0, it.size).clear() }
    )

    private fun runOne(list: ObservableList<String>, op: Op): Outcome = try {
        op.body(list)
        Outcome.SILENT
    } catch (e: UnsupportedOperationException) {
        Outcome.UOE
    } catch (e: IndexOutOfBoundsException) {
        // 显式区分：索引越界不是"不可变"信号，矩阵里不允许出现，出现即说明契约变了。
        throw AssertionError("${op.name} 抛了 IndexOutOfBoundsException —— 本矩阵期望 UOE/SILENT", e)
    } catch (e: IllegalStateException) {
        Outcome.ISE
    }

    /**
     * 在真实实例上跑完 30 个操作，返回实际结局表。
     * **每个操作前重置 list 不现实**（list 本身不可写），所以顺序执行 ——
     * 幸好所有静默分支都是空操作（D-7 单独断言这点），顺序无关。
     */
    private fun collect(list: ObservableList<String>): List<Outcome> = ops().map { runOne(list, it) }

    private fun assertMatrix(label: String, list: ObservableList<String>, expected: List<Outcome>) {
        val actual = collect(list)
        val all = ops()
        assertEquals("$label：操作数必须与期望表一致", expected.size, all.size)
        assertEquals("$label：操作数必须与期望表一致", expected.size, actual.size)
        for (i in all.indices) {
            assertEquals(
                "$label 第 ${i + 1} 格 [${all[i].name}]：",
                expected[i].label,
                actual[i].label
            )
        }
    }

    // ------------------------------------------------------------------
    // D-1 ~ D-3：单例身份与字段修饰符
    // ------------------------------------------------------------------

    /**
     * 路线 (ii) 的**前提**：`emptyObservableList()` 返回的是进程级共享单例。
     * 如果这条将来变成 false（有人改走路线 i：每次 new 一个），本测试会红 ——
     * 那时 Javadoc 里"进程级共享"这句话也必须跟着改，这正是我们要的联动。
     */
    @Test
    fun emptySingletonIsProcessWideSharedInstance() {
        val a: ObservableList<String> = FXCollections.emptyObservableList()
        val b: ObservableList<String> = FXCollections.emptyObservableList()
        assertNotNull("emptyObservableList() 不得返回 null", a)
        assertSame("emptyObservableList() 必须返回同一个进程级单例（路线 ii 的前提）", a, b)
    }

    /**
     * D-2：字段必须是 `static final`。
     * 改前红 —— `FXCollections.java:183` 是 `private static ObservableList EMPTY_OBSERVABLE_LIST`
     * （**缺 final**，裸类型）。补 `final` 是本单唯一的代码行改动。
     */
    @Test
    fun emptySingletonFieldIsStaticFinal() {
        val f = FXCollections::class.java.getDeclaredField("EMPTY_OBSERVABLE_LIST")
        val m = f.modifiers
        assertTrue(
            "EMPTY_OBSERVABLE_LIST 必须是 static（= 进程级共享），实际修饰符=0x${Integer.toHexString(m)}",
            Modifier.isStatic(m)
        )
        assertTrue(
            "EMPTY_OBSERVABLE_LIST 必须是 final（防止运行期被整体替换成另一个表，" +
                "那会让'进程级共享单例'的声明失效），实际修饰符=0x${Integer.toHexString(m)}",
            Modifier.isFinal(m)
        )
    }

    /**
     * D-3：声明返回类型是**可写**接口 —— 这正是契约缺口的根：
     * 类型系统宣告"这是个 List，你可以 add/clear"，实现却在部分路径上静默。
     * 断言它仍是可写接口，是为了让"补 Javadoc 说明缺口"这件事有据可依：
     * 如果哪天改成返回 `Collections.unmodifiableList` 或换类型，这条也要一起改。
     */
    @Test
    fun emptySingletonDeclaredTypeIsTheWritableInterface() {
        val rt = FXCollections::class.java
            .getDeclaredMethod("emptyObservableList").returnType
        assertEquals(
            "emptyObservableList() 的声明返回类型必须是 ObservableList（可写接口）——" +
                "契约缺口正来自'类型可写、实现不全抛'，Javadoc 就是补这个缺口",
            "com.tungsten.fclcore.fakefx.collections.ObservableList",
            rt.name
        )
        assertTrue(
            "ObservableList 必须仍是 java.util.List 的子类型（否则'可写'这个描述不成立）",
            java.util.List::class.java.isAssignableFrom(rt)
        )
    }

    // ------------------------------------------------------------------
    // D-4 ~ D-8：30 格矩阵
    // ------------------------------------------------------------------

    /**
     * D-4：`emptyObservableList()` 单例（size 0）的 30 格矩阵。
     *
     * 这张表就是 `FXCollections.emptyObservableList()` Javadoc 所说的
     * 「仅 6 个写方法抛 UOE，其余按 AbstractList 语义静默」的**精确展开**。
     * 注意 #27 这里是 **UOE**（不是 ISE）：`EmptyObservableList` 覆写了 `iterator()`
     * 返回一个手工写的共享 `ListIterator`，它的 `remove()` 直接 UOE。
     */
    @Test
    fun emptySingletonWriteMatrixMatchesDeclaration() {
        assertMatrix(
            "emptyObservableList() [size=0]",
            FXCollections.emptyObservableList<String>(),
            listOf(
                Outcome.UOE,    // 1  add(E)
                Outcome.UOE,    // 2  add(0,E)
                Outcome.UOE,    // 3  addAll(Collection[a])
                Outcome.SILENT, // 4  addAll(Collection[])      <- 空集合，AbstractCollection 直接 return false
                Outcome.SILENT, // 5  addAll(0,Collection[])    <- AbstractList 空插入，无改动
                Outcome.UOE,    // 6  addAll(0,Collection[a])
                Outcome.UOE,    // 7  set(0,E)
                Outcome.UOE,    // 8  remove(int 0)             <- get(0) 先抛 IOOBE 前的路径
                Outcome.SILENT, // 9  remove(Object x)          <- indexOf 返回 -1，无改动
                Outcome.SILENT, // 10 clear()                   <- size 已是 0，无改动
                Outcome.SILENT, // 11 removeAll(Collection[])
                Outcome.SILENT, // 12 removeAll(Collection[a]) <- 空表遍历零次
                Outcome.SILENT, // 13 retainAll(Collection[])
                Outcome.SILENT, // 14 retainAll(Collection[a]) <- 同上
                Outcome.SILENT, // 15 removeIf(false)
                Outcome.SILENT, // 16 removeIf(true)            <- 空表，谓词一次都不调
                Outcome.SILENT, // 17 sort(null)                <- 空表，sort 不碰元素
                Outcome.SILENT, // 18 replaceAll(identity)      <- 空表
                Outcome.SILENT, // 19 subList(0,0).clear()
                Outcome.UOE,    // 20 addAll(E[a])              <- 本类显式覆写
                Outcome.UOE,    // 21 addAll(E[empty])          <- 显式覆写，空 vararg 也抛
                Outcome.UOE,    // 22 setAll(E[a])              <- 显式覆写
                Outcome.UOE,    // 23 setAll(Collection[])      <- 显式覆写
                Outcome.UOE,    // 24 removeAll(E[a])           <- 显式覆写
                Outcome.UOE,    // 25 retainAll(E[a])           <- 显式覆写
                Outcome.UOE,    // 26 remove(0,0)               <- 显式覆写
                Outcome.UOE,    // 27 iterator().remove()       <- EmptyObservableList 自带 ListIterator
                Outcome.SILENT, // 28 iterator() drain + remove()
                Outcome.UOE,    // 29 listIterator().add(E)
                Outcome.SILENT  // 30 subList(0,size).clear()
            )
        )
    }

    /**
     * D-5：`new ImmutableObservableList<>()`（size 0）的 30 格矩阵。
     *
     * 与 D-4 的**唯一差异是第 27 格**（ISE 而非 UOE）：
     * `ImmutableObservableList` 没有覆写 `iterator()`，走 `AbstractList.Itr`，
     * 未 `next()` 就 `remove()` 触发的是 `lastRet < 0` 的 `IllegalStateException`。
     *
     * **这一格差异是有意保留的判别力**：如果将来有人把两个类"统一实现"，
     * 这条会立刻变红，提醒他两处的失败形态不同、不能无条件合并。
     */
    @Test
    fun immutableListSizeZeroWriteMatrixMatchesDeclaration() {
        assertMatrix(
            "new ImmutableObservableList<>() [size=0]",
            ImmutableObservableList<String>(),
            listOf(
                Outcome.UOE,    // 1  add(E)
                Outcome.UOE,    // 2  add(0,E)
                Outcome.UOE,    // 3  addAll(Collection[a])
                Outcome.SILENT, // 4  addAll(Collection[])
                Outcome.SILENT, // 5  addAll(0,Collection[])
                Outcome.UOE,    // 6  addAll(0,Collection[a])
                Outcome.UOE,    // 7  set(0,E)
                Outcome.UOE,    // 8  remove(int 0)
                Outcome.SILENT, // 9  remove(Object x)
                Outcome.SILENT, // 10 clear()
                Outcome.SILENT, // 11 removeAll(Collection[])
                Outcome.SILENT, // 12 removeAll(Collection[a])
                Outcome.SILENT, // 13 retainAll(Collection[])
                Outcome.SILENT, // 14 retainAll(Collection[a])
                Outcome.SILENT, // 15 removeIf(false)
                Outcome.SILENT, // 16 removeIf(true)
                Outcome.SILENT, // 17 sort(null)
                Outcome.SILENT, // 18 replaceAll(identity)
                Outcome.SILENT, // 19 subList(0,0).clear()
                Outcome.UOE,    // 20 addAll(E[a])
                Outcome.UOE,    // 21 addAll(E[empty])
                Outcome.UOE,    // 22 setAll(E[a])
                Outcome.UOE,    // 23 setAll(Collection[])
                Outcome.UOE,    // 24 removeAll(E[a])
                Outcome.UOE,    // 25 retainAll(E[a])
                Outcome.UOE,    // 26 remove(0,0)
                Outcome.ISE,    // 27 iterator().remove()       <- AbstractList.Itr: lastRet < 0（与 D-4 不同）
                Outcome.SILENT, // 28 iterator() drain + remove()
                Outcome.UOE,    // 29 listIterator().add(E)
                Outcome.SILENT  // 30 subList(0,size).clear()
            )
        )
    }

    /**
     * D-6：`new ImmutableObservableList<>("x")`（size 1）的 30 格矩阵。
     *
     * 这张表是"静默**只在**空操作时"的核心证据。与 size 0 相比，**9 格从 SILENT 变成 UOE**：
     * `remove(Object x)`（要真删，撞 iterator().remove()）、`clear()`、`retainAll([])`、
     * `retainAll([a])`、`removeIf(true)`、`sort(null)`、`replaceAll`、
     * `iterator() drain + remove()`、`subList(0,size).clear()`。
     *
     * 同时**仍有 6 格是 SILENT**（`addAll(Collection[])`、`addAll(0,Collection[])`、
     * `removeAll([])`、`removeAll([a])`、`removeIf(false)`、`subList(0,0).clear()`）——
     * 也就是说"非空表也不是全线抛"，Javadoc 不能写成"非空表就安全"。
     */
    @Test
    fun immutableListSizeOneWriteMatrixMatchesDeclaration() {
        assertMatrix(
            "new ImmutableObservableList<>(\"x\") [size=1]",
            ImmutableObservableList("x"),
            listOf(
                Outcome.UOE,    // 1  add(E)
                Outcome.UOE,    // 2  add(0,E)
                Outcome.UOE,    // 3  addAll(Collection[a])
                Outcome.SILENT, // 4  addAll(Collection[])      <- 仍静默：空集合无需改动
                Outcome.SILENT, // 5  addAll(0,Collection[])    <- 仍静默
                Outcome.UOE,    // 6  addAll(0,Collection[a])
                Outcome.UOE,    // 7  set(0,E)                  <- elements final，AbstractList.set 无实现
                Outcome.UOE,    // 8  remove(int 0)
                Outcome.UOE,    // 9  remove(Object x)          <- size 0 时静默，这里要真删 -> UOE
                Outcome.UOE,    // 10 clear()                   <- size 0 时静默，这里 UOE
                Outcome.SILENT, // 11 removeAll(Collection[])
                Outcome.SILENT, // 12 removeAll(Collection[a]) <- 元素 [x] 不在 [a] 里，零改动
                Outcome.UOE,    // 13 retainAll(Collection[])   <- 空表参数 = 全删 -> UOE
                Outcome.UOE,    // 14 retainAll(Collection[a])  <- [x] 不被保留 -> 要删 -> UOE
                Outcome.SILENT, // 15 removeIf(false)           <- 谓词恒 false，零删
                Outcome.UOE,    // 16 removeIf(true)            <- 要删 -> UOE
                Outcome.UOE,    // 17 sort(null)                <- 单元素仍会调 set/remove -> UOE
                Outcome.UOE,    // 18 replaceAll(identity)      <- 要写回 -> UOE
                Outcome.SILENT, // 19 subList(0,0).clear()      <- 空区间，零删
                Outcome.UOE,    // 20 addAll(E[a])
                Outcome.UOE,    // 21 addAll(E[empty])
                Outcome.UOE,    // 22 setAll(E[a])
                Outcome.UOE,    // 23 setAll(Collection[])
                Outcome.UOE,    // 24 removeAll(E[a])
                Outcome.UOE,    // 25 retainAll(E[a])
                Outcome.UOE,    // 26 remove(0,0)
                Outcome.ISE,    // 27 iterator().remove()
                Outcome.UOE,    // 28 iterator() drain + remove() <- 有元素 -> 要删 -> UOE
                Outcome.UOE,    // 29 listIterator().add(E)
                Outcome.UOE     // 30 subList(0,size).clear()   <- 区间覆盖全表 -> 要删 -> UOE
            )
        )
    }

    /**
     * D-8：把"静默是有条件的"再单独钉一次。
     *
     * 只靠三张表也能推出这条，但那张表太长、红了不好读。这里直接做最小对照：
     * **同一个 `clear()`，在空表上静默、在非空表上必须抛 UOE**。
     * 如果哪天真有人"补齐全线 UOE"，size 0 那一格会从 SILENT 变 UOE -> 红；
     * 如果有人反过来"全改成静默"，size 1 那一格会从 UOE 变 SILENT -> 红。
     *
     * 这就是评审 Arbiter 那句「静默无害的防御性 clear()」的可执行边界：
     * 它**只**在空表上无害。
     */
    @Test
    fun immutableListSilenceIsConditionalOnNoOp() {
        val empty = ImmutableObservableList<String>()
        val one = ImmutableObservableList("x")

        val onEmpty = runOne(empty, Op("clear()") { it.clear() })
        val onOne = runOne(one, Op("clear()") { it.clear() })

        assertEquals("空表的 clear() 必须是静默的（评审明令：不得改成全线 UOE）", Outcome.SILENT.label, onEmpty.label)
        assertEquals("非空表的 clear() 必须抛 UOE（静默是有条件的，不是无条件）", Outcome.UOE.label, onOne.label)
        assertEquals("clear() 是空操作时不得改变表内容", 0, empty.size)
        assertEquals("抛 UOE 时同样不得改变表内容（elements 是 final）", 1, one.size)
        assertEquals("非空表内容必须原样保留", "x", one[0])
    }

    /**
     * D-7：所有静默分支都必须是**无副作用**的。
     *
     * 这一条与矩阵分开写是有原因的：矩阵断言的是"抛没抛"，
     * 而契约里真正要保证的是"静默 = 没写进去"。
     * 如果哪天有人把 `clear()` 改成"真清空但不抛"，矩阵会绿（还是 SILENT）
     * 而这条会红 —— 因为它断言的是 `elements` 内容没变。
     *
     * 顺带钉住单例的跨调用不可污染性：跑完 30 个操作后，单例必须仍是空表。
     */
    @Test
    fun silentBranchesNeverMutateTheList() {
        val singleton = FXCollections.emptyObservableList<String>()
        collect(singleton)
        assertEquals("跑完 30 个写操作后，进程级共享单例必须仍是空表（静默分支无副作用）", 0, singleton.size)
        assertTrue("共享单例不得被污染", singleton.isEmpty())

        val size1 = ImmutableObservableList("x")
        collect(size1)
        assertEquals("跑完 30 个写操作后，ImmutableObservableList(size 1) 必须仍是 1 个元素", 1, size1.size)
        assertEquals("内容必须原样保留", "x", size1[0])
    }
}
