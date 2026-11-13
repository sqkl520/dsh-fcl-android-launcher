package com.dsh

import com.dsh.ui.shell.DshPageUI
import com.dsh.ui.shell.DshUIManager
import com.tungsten.fcllibrary.component.ui.FCLCommonUI
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.lang.reflect.Field

/**
 * round31 / `slice-07-logic-f1`（`fix-r31-221`）：外壳切页时「先问标题、后建页面」的顺序倒置。
 *
 * ## 缺陷（评审 UPHELD / MEDIUM）
 * `DshUIManager.onPageSelected` 原来先 `pageSelectedListener?.invoke(position)`，后
 * `playEnterTransition(position)`。而**创建页面的唯一一处**就在 `playEnterTransition` 内部的
 * `getUI(position)`（`registry` 命中则复用，否则 `factories[position]()` + `onCreate()`）。
 * 外壳在监听方里调 `titleOf(pos)`，而 `titleOf` 走**只读**的 `uiAt`（`registry[position]`，
 * 未创建即 `null`），兜底是 `titles[position]` 的 tab 标题。
 * ⇒ 页面尚未创建时取到的必然是 tab 标题，拿不到页面自己声明的 `pageTitle()`。
 *
 * ## 断言口径：**行为**，不是源码文本
 * 本文件不 `grep` 源码、不断言语句顺序。它做的是：
 * 1. 造一个 **覆写 `pageTitle()`** 的页面（返回常量 [PAGE_TITLE]），
 * 2. 让 `registry` 处于「该页还没被创建」的初始态，
 * 3. 通过**生产代码里真实的那个 `OnPageChangeCallback` 匿名类**（`DshUIManager.init()` 注册的
 *    `DshUIManager$init$1`）触发 `onPageSelected(position)`，
 * 4. 在**监听方被调用的那一刻**读 `titleOf(position)`，并要求它等于 [PAGE_TITLE]。
 *
 * 判据是「监听方看到的标题字符串」与「页面被创建的时刻是否早于通知」这两个运行时事实。
 * 把两条语句调回旧顺序，`create` 就会排在 `notify` 之后、`titleOf` 返回 tab 标题 ⇒ 红。
 *
 * ## 为什么直接实例化 `DshUIManager$init$1` 而不是调 `init()`
 * `init()` 需要真实的 `ViewPager2`（会 `requireContext()`、装 adapter、调度布局），
 * 而这套单测 harness 的 classpath **没有 androidx 运行时 jar**（见 `run-tests.sh` 的 `CP`），
 * 连 `ViewPager2$OnPageChangeCallback` 都无法加载。回调类本身是 `OnPageChangeCallback` 的子类，
 * 它只在 `onPageSelected` 里碰 `this$0` 的两个成员（`pageSelectedListener` / `playEnterTransition`），
 * 不碰 `pager`，所以用反射直接构造它即可驱动**同一份生产字节码**，不重写任何逻辑。
 *
 * ## 为什么字段用反射填
 * `DshUIManager` 的构造器要求 `DshShellHost` + `ViewPager2`，且 `factories` 是私有字段。
 * 本用例只消费 `titleOf` / `getUI` / `registry` 这条链，用 `sun.misc.Unsafe.allocateInstance`
 * 跳过构造、反射填字段，可以精确构造「registry 全空」这个缺陷现场
 * （同款手法见 `R31DateTimeFormatterLocaleTest` / `R26TerminalEventTest`）。
 *
 * 跑法：`sh run-tests.sh`
 */
class R31Fix221Test {

    companion object {
        /** 页面自己声明的标题。必须与任何 tab 标题字符串都不同，否则无法区分拿到了哪一个。 */
        private const val PAGE_TITLE = "PAGE_TITLE_FROM_pageTitle"

        /** 工厂被调用（= 页面被创建）与监听方被调用的事件序列 —— 判别「先问标题还是先建页」的核心 */
        private val events = mutableListOf<String>()

        /** 目标页下标：故意取 1（不是首页），排掉「0 有特殊处理」的可能 */
        private const val TARGET = 1

        @JvmStatic
        private fun allocate(cls: Class<*>): Any {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = theUnsafe.get(null)
            return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, cls)!!
        }

        /**
         * 沿父类链给字段赋值。
         *
         * `host` / `pager`  / `titles` / `factories` 都声明在 `DshUIManager` 自己身上，
         * 但 `registry` 与它都在同一类里；沿链找是为了不因为将来字段上移到基类就失效。
         * 找不到字段直接抛（**不做**「找不到就跳过」的兜底 —— 那会把夹具写错洗成绿）。
         */
        @JvmStatic
        private fun setField(target: Any, name: String, value: Any?) {
            var c: Class<*>? = target.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    f.set(target, value)
                    return
                } catch (_: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            throw AssertionError("夹具失效：$name 字段不存在（${target.javaClass.name}）")
        }
    }

    /**
     * 覆写 [DshPageUI.pageTitle] 的假页面 —— 这就是「页面自己声明标题」的页面形态。
     *
     * 用 `Unsafe` 造 `Context`（`android.jar` 里 `Context` 是抽象类的桩），因为本路径
     * （`pageTitle()` → 常量）不碰 context。
     */
    private class StubPage : DshPageUI(allocate(android.content.Context::class.java) as android.content.Context, 0) {
        override fun pageTitle(): CharSequence = PAGE_TITLE
    }

    /**
     * 造一个 `registry` 全空的 `DshUIManager`。
     *
     * `host` 置 null 是**刻意的**：本用例只走「页面已创建 ⇒ `pageTitle()` 命中」这条分支，
     * 不会走到 `context.getString(titles[position])` 的回落（那条会解引用 `host.activity`）。
     * 一旦实现退化到回落路径，用例会因为拿到 tab 标题（或抛 NPE）而**红**，正是我们想要的。
     *
     * `pager` 也置 null：`onPageSelected`、`playEnterTransition`、`titleOf` 都不读它。
     */
    private fun newManager(): DshUIManager {
        events.clear()
        val manager = allocate(DshUIManager::class.java) as DshUIManager
        setField(manager, "host", null)
        setField(manager, "pager", null)
        setField(manager, "titles", listOf(1, 2, 3))
        val factories = ArrayList<() -> FCLCommonUI>(3)
        for (i in 0..2) {
            val position = i
            factories.add {
                events += "create($position)"
                allocate(StubPage::class.java) as FCLCommonUI
            }
        }
        setField(manager, "factories", factories)
        setField(manager, "registry", arrayOfNulls<FCLCommonUI>(3))
        setField(manager, "lastSelectedPosition", -1)
        return manager
    }

    /**
     * 实例化生产代码在 `init()` 里注册的那个匿名回调类，并触发一次 `onPageSelected`。
     *
     * 为什么不用 `object : OnPageChangeCallback()` 代替：那样测的是本文件的写法，不是生产代码。
     * `DshUIManager$init$1` 是**编译产物里真实存在**的那个类，直接实例化它 = 驱动真实实现。
     */
    private fun fireOnPageSelected(manager: DshUIManager, position: Int) {
        val cbClass = Class.forName("com.dsh.ui.shell.DshUIManager\$init\$1")
        val ctor = cbClass.declaredConstructors.first { it.parameterCount == 1 }
        ctor.isAccessible = true
        val callback = ctor.newInstance(manager)
        cbClass.getMethod("onPageSelected", Int::class.javaPrimitiveType)
            .invoke(callback, position)
    }

    /**
     * **缺陷本体**：首次进入一个尚未创建的 tab 时，监听方拿到的标题必须是 `pageTitle()`，
     * 不能因为「页面还没建」而回落成 tab 标题。
     *
     * 改前（先 invoke 后 playEnterTransition）：监听方被调用时 `registry[1]` 仍是 null ⇒
     * `titleOf(1)` 走回落 ⇒ 拿到 tab 标题（本夹具里 `host == null`，实际表现为 NPE / 不等），红。
     * 改后（先 playEnterTransition 后 invoke）：`getUI(1)` 已把页面建好 ⇒ 命中 `pageTitle()` ⇒ 绿。
     */
    @Test
    fun listenerSeesPageTitleOnFirstEntryBeforePageCreated() {
        val manager = newManager()
        var titleSeenByListener = "<listener never ran>"
        manager.pageSelectedListener = { pos: Int ->
            events += "notify($pos)"
            // 用 runCatching 把「回落路径」的后果收成一个**值**再断言，而不是让它以异常逸出：
            // 异常逸出会被 harness 报成 InvocationTargetException，与「类加载失败」长得一样，
            // 达不到「失败原因必须是断言」的取证要求。这里把 NPE 也变成断言的输入。
            titleSeenByListener = runCatching { manager.titleOf(pos).toString() }
                .getOrElse { "FALLBACK_TAKEN: " + it.javaClass.simpleName + " (registry 仍空 ⇒ 走了 tab 标题回落)" }
        }

        fireOnPageSelected(manager, TARGET)

        assertEquals(
            "首次进入未创建的 tab 时，监听方必须拿到页面声明的标题。" +
                "拿到 FALLBACK_TAKEN / tab 标题 = 页面还没建就被问了标题 = 缺陷仍在",
            PAGE_TITLE,
            titleSeenByListener
        )
    }

    /**
     * **机制断言（后果）**：页面必须在监听方被调用**之前**就建好。
     *
     * 这条与上面那条互补，不是重复：
     * - 上面那条测「监听方看到的字符串对不对」；
     * - 这条测「创建事件是否排在通知事件之前」。
     *
     * 如果将来有人为了修标题而在 `titleOf` 里惰性建页（修法 b），上面那条会绿、这条会红 ——
     * 那正是需要人看一眼的设计变化（`titleOf` 变成有副作用的函数）。两条一起看，
     * 才能区分「顺序修好了」与「绕过了顺序」。
     */
    @Test
    fun pageIsCreatedBeforeListenerIsNotified() {
        val manager = newManager()
        manager.pageSelectedListener = { pos: Int ->
            events += "notify($pos)"
        }

        fireOnPageSelected(manager, TARGET)

        assertEquals(
            "事件序列必须是「先建页、后通知」；旧顺序（先通知）会得到 [notify, create]",
            listOf("create($TARGET)", "notify($TARGET)"),
            events
        )
    }

    /**
     * **一致性断言**：同一个 tab 两次进入，标题必须一致。
     *
     * 这是缺陷报告里最直白的用户可见后果（「同一个 tab 两次进入标题不一致」）。
     * 第一次进入时页面未创建、第二次已创建 —— 修复后两次都必须是 `pageTitle()`。
     */
    @Test
    fun repeatedEntryToSameTabKeepsSameTitle() {
        val manager = newManager()
        val titles = mutableListOf<String>()
        manager.pageSelectedListener = { pos: Int ->
            events += "notify($pos)"
            // 同样把回落后果收成字符串而非异常（理由见上一条用例）
            titles += runCatching { manager.titleOf(pos).toString() }
                .getOrElse { "FALLBACK_TAKEN: " + it.javaClass.simpleName }
        }

        fireOnPageSelected(manager, TARGET)   // 首次：修复前这里回落成 tab 标题
        fireOnPageSelected(manager, TARGET)   // 再次：页面已存在，修复前这里才是 pageTitle()

        assertEquals(
            "同一个 tab 两次进入必须给出相同标题（第一次回落、第二次命中 = 缺陷仍在）",
            listOf(PAGE_TITLE, PAGE_TITLE),
            titles
        )
    }

    /**
     * **反例守卫**：确认本夹具真的能区分「页面已创建」与「未创建」两种状态。
     *
     * 若不覆写 `pageTitle()`（基类默认返回 null），`titleOf` 必须回落到 tab 标题 ——
     * 这条保证上面的绿不是「无论建没建都拿到 PAGE_TITLE」的假绿。
     *
     * 同时它把「回落路径确实会走到 `context.getString`」这一事实钉住：本用例**故意**让
     * `host == null` 且页面未创建，因此回落必然抛 NPE（`host.activity`）——
     * 抛异常本身就证明「没拿到 `pageTitle()`」。用 `runCatching` 捕获并断言失败类型，
     * 避免把夹具的限制误当成生产缺陷。
     */
    @Test
    fun uncreatedPageWithoutOverrideFallsBackToTabTitle() {
        val manager = newManager()
        // registry 全空 + 页面不覆写 ⇒ 走回落；此时 host == null 会 NPE（回落路径需要 context）
        val outcome = runCatching { manager.titleOf(TARGET).toString() }

        assertTrue(
            "页面未创建时必须走回落路径（本夹具里回落因 host==null 而抛 NPE）——" +
                "若这里拿到了 pageTitle()，说明 titleOf 暗中建了页面（修法 b 的副作用）",
            outcome.isFailure
        )
        assertNotEquals(
            "回落不可能是页面声明的标题",
            PAGE_TITLE,
            outcome.getOrNull()
        )
    }

    /**
     * **副作用守卫**：`titleOf` 必须是纯查询 —— 取标题不许建页面。
     *
     * 这是 `uiAt` 那段长注释里写死的设计约束（「纯查询不许有副作用」），也是修法 (b) 被否掉的理由。
     * 若有人把 `titleOf` 改成 `getUI`，本用例红（registry 不再全空）。
     */
    @Test
    fun titleOfDoesNotCreatePage() {
        val manager = newManager()
        val registryField = DshUIManager::class.java.getDeclaredField("registry").apply { isAccessible = true }

        runCatching { manager.titleOf(TARGET) }
        runCatching { manager.titleOf(0) }
        runCatching { manager.titleOf(2) }

        val registry = registryField.get(manager) as Array<*>
        assertEquals(
            "titleOf 是纯查询，不得创建任何页面；registry 必须保持全空，实际=${registry.toList()}",
            listOf(null, null, null),
            registry.toList()
        )
        assertTrue("取标题不得触发任何工厂调用，实际事件=$events", events.isEmpty())
    }
}
