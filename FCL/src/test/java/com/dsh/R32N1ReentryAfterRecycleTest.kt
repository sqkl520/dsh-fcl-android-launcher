package com.dsh

import com.dsh.ui.shell.DshPageUI
import com.dsh.ui.shell.DshUIManager
import com.tungsten.fcllibrary.component.ui.FCLCommonUI
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * round32 / `verify-239` 的 N1：「页面被回收后再回到同一 tab，页面不重建」。
 *
 * ## 缺陷
 * `DshUIManager.playEnterTransition(position)` 原来第一行是
 * `if (position == lastSelectedPosition) return`。
 * 而 `Adapter.onViewRecycled` 会把 `registry[holder.bound]` 置 `null`（页面随视图回收销毁）。
 *
 * 于是「离开 tab → ViewPager2 回收它 → 回来」时：
 * - `position == lastSelectedPosition` 成立（`lastSelectedPosition` 没被重置），
 * - 方法在 `getUI(position)` **之前**就返回了，
 * - `getUI` 是"registry 命中就复用、否则建好并 onCreate()"的唯一入口，
 *   它不被调用 ⇒ 页面永不重建 ⇒ `uiAt(position)` 恒为 null ⇒ `titleOf` 回落成 tab 标题。
 *
 * 症状与 `slice-07-logic-f1`（`R31Fix221Test`）**完全相同**，但成因完全不同：
 * 那条是「先问标题、后建页面」的顺序问题，这条是「压根不去建」。
 * **修顺序修不好这条** —— 这正是 `verify-239` 把它单列成 N1 的理由。
 *
 * ## 断言口径
 * 照抄 `R31Fix221Test`：不 grep 源码、不断言语句，只驱动
 * **生产代码里真实的那个 `OnPageChangeCallback` 匿名类**（`DshUIManager$init$1`），
 * 观察 `registry` 与工厂调用事件这两个运行时事实。
 *
 * 判别力：把 `playEnterTransition` 的第一行改回 `if (position == lastSelectedPosition) return`，
 * [reentryAfterRecycleRebuildsThePage] 会红（`registry[1]` 保持 null、`events` 无第二个 `create(1)`）。
 */
class R32N1ReentryAfterRecycleTest {

    companion object {
        /** 页面自己声明的标题 —— 必须与任何 tab 标题都不同，否则无法区分拿到了哪一个。 */
        private const val PAGE_TITLE = "PAGE_TITLE_FROM_pageTitle"

        /** 目标页下标：故意取 1（不是首页），排掉「0 有特殊处理」的可能。 */
        private const val TARGET = 1

        /** 工厂被调用（= 页面被创建）的事件序列 —— 判别「重入时到底建没建」的核心。 */
        private val events = mutableListOf<String>()

        private fun allocate(cls: Class<*>): Any {
            val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null) as sun.misc.Unsafe
            return unsafe.allocateInstance(cls)
        }

        /**
         * 覆写 [DshPageUI.pageTitle] 的假页面 —— 与 `R31Fix221Test.StubPage` 同款。
         *
         * 用 `Unsafe` 造 `Context`（`android.jar` 里 `Context` 是抽象类的桩），
         * 因为本路径（`pageTitle()` → 常量）不碰 context。
         */
        private class StubPage :
            DshPageUI(allocate(android.content.Context::class.java) as android.content.Context, 0) {
            override fun pageTitle(): CharSequence = PAGE_TITLE
        }

        /** 反射写字段，穿透到父类。 */
        private fun setField(target: Any, name: String, value: Any?) {
            var c: Class<*>? = target.javaClass
            while (c != null) {
                try {
                    c.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
                    return
                } catch (_: NoSuchFieldException) {
                    c = c.superclass
                }
            }
            throw AssertionError("夹具失效：$name 字段不存在（${target.javaClass.name}）")
        }

        /** 造一个 `registry` 全空、`lastSelectedPosition = -1` 的 manager。 */
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
         * 为什么不用 `object : OnPageChangeCallback()` 代替：那样测的是本文件的写法，
         * 不是生产代码。`DshUIManager$init$1` 是**编译产物里真实存在**的那个类，
         * 直接实例化它 = 驱动真实实现。
         */
        private fun fireOnPageSelected(manager: DshUIManager, position: Int) {
            val cbClass = Class.forName("com.dsh.ui.shell.DshUIManager\$init\$1")
            val ctor = cbClass.declaredConstructors.first { it.parameterCount == 1 }
            ctor.isAccessible = true
            val callback = ctor.newInstance(manager)
            cbClass.getMethod("onPageSelected", Int::class.javaPrimitiveType)
                .invoke(callback, position)
        }

        private fun registryOf(manager: DshUIManager): Array<Any?> {
            val f = DshUIManager::class.java.getDeclaredField("registry").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            return f.get(manager) as Array<Any?>
        }
    }

    /**
     * **缺陷本体**：进入 tab → 模拟回收 → 回到同一 tab，页面必须被重建。
     *
     * 这里把「回收」直接写成对 `registry[TARGET]` 置 null —— 这正是
     * `Adapter.onViewRecycled` 唯一做的事（`registry[holder.bound] = null`）。
     * 走真实 `onViewRecycled` 需要真实 `Holder`（要 androidx、要 View），
     * 而它对本缺陷的贡献只有"把槽位置空"这一条语义，所以直接置空更精确、更少噪音。
     */
    @Test
    fun reentryAfterRecycleRebuildsThePage() {
        val manager = newManager()

        // 1) 首次进入：页面被创建
        fireOnPageSelected(manager, TARGET)
        assertTrue(
            "首次进入 tab 后页面必须已创建，实际 registry[$TARGET]=${registryOf(manager)[TARGET]}",
            registryOf(manager)[TARGET] != null
        )
        assertEquals(
            "首次进入只应创建一次，实际事件=$events",
            listOf("create($TARGET)"),
            events.toList()
        )

        // 2) 模拟 ViewPager2 回收该页（= Adapter.onViewRecycled 的 registry 置空语义）
        registryOf(manager)[TARGET] = null

        // 3) 回到同一 tab —— 位置没变，但页面已被回收
        fireOnPageSelected(manager, TARGET)

        assertTrue(
            "离开再回到同一 tab 后页面必须重建。registry[$TARGET] 仍为 null = " +
                "playEnterTransition 在 getUI 之前就早退了 = N1 缺陷仍在。实际事件=$events",
            registryOf(manager)[TARGET] != null
        )
        assertEquals(
            "重入时必须再走一次工厂（create 事件共两次：首次 + 重入），实际事件=$events",
            listOf("create($TARGET)", "create($TARGET)"),
            events.toList()
        )
    }

    /**
     * **反向断言**：位置没变 **且页面还活着** 时，仍然不许重建。
     *
     * 这条守住 `playEnterTransition` 原注释里的第 2 条语义：ViewPager2 在布局变化
     * （软键盘弹出、页面内容刷新）后会重新 dispatch 当前页，那种情况**不能**再建一次页面，
     * 否则会出现"建了又立刻回收"的空转（见 `uiAt` 的 KDoc 里描述的那种事故）。
     *
     * 如果修 N1 时把判据改成"只看位置、恒往下走"，本用例会红。
     */
    @Test
    fun repeatedDispatchWithoutRecycleDoesNotRecreateThePage() {
        val manager = newManager()

        fireOnPageSelected(manager, TARGET)
        val eventsAfterFirst = events.toList()

        // 页面没被回收，再 dispatch 两次同一位置（模拟布局变化）
        fireOnPageSelected(manager, TARGET)
        fireOnPageSelected(manager, TARGET)

        assertEquals(
            "页面活着时重复 dispatch 同一位置不得重建页面（否则空转）。" +
                "首次后事件=$eventsAfterFirst，两次重复后事件=$events",
            eventsAfterFirst,
            events.toList()
        )
    }

    /**
     * **只读入口不得被误用**：修 N1 不许顺手把 `uiAt` 改成有副作用。
     *
     * 这条不是重复上面两条 —— 上面测的是 `playEnterTransition`，这条测的是
     * 「有人为了绕过早退而在 `uiAt` 里惰性建页」这种修法（`R31Fix221Test` 的 KDoc 叫它"修法 b"）。
     * 那样两条 N1 用例都会绿，但 `titleOf` 就从纯查询变成有副作用了，会破坏
     * 「页面随 ViewPager 生命周期创建/回收」这条既定模型。
     */
    @Test
    fun uiAtStaysReadOnlyAfterTheFix() {
        val manager = newManager()

        // 不走 onPageSelected，直接问标题（外壳每次切页都会这么问）
        runCatching { manager.titleOf(TARGET) }
        runCatching { manager.titleOf(0) }
        runCatching { manager.titleOf(2) }

        val registry = registryOf(manager).toList()
        assertEquals(
            "titleOf 是纯查询，不得创建页面；registry 必须保持全空，实际=$registry",
            listOf(null, null, null),
            registry
        )
        assertTrue("取标题不得触发任何工厂调用，实际事件=$events", events.isEmpty())
        assertNull("registry 里不该有任何实例", registryOf(manager)[TARGET])
    }
}
