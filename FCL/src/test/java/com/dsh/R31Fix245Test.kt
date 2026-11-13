package com.dsh

import com.tungsten.fclcore.fakefx.beans.InvalidationListener
import com.tungsten.fclcore.fakefx.beans.binding.Bindings
import com.tungsten.fclcore.fakefx.beans.binding.ObjectBinding
import com.tungsten.fclcore.fakefx.beans.property.SimpleStringProperty
import com.tungsten.fclcore.fakefx.beans.value.ChangeListener
import com.tungsten.fclcore.fakefx.beans.value.ObservableValue
import com.tungsten.fclcore.fakefx.collections.MapChangeListener
import com.tungsten.fclcore.fakefx.collections.ObservableMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R31 / fix-245 —— `Bindings.valueAt(ObservableMap, ObservableValue)` 的 bind/unbind 对称性。
 *
 * **缺陷**：这个工厂在实例初始化块里 `super.bind(op, key)` 挂了**两个**依赖，但 `dispose()` 只
 * `super.unbind(op)`。`BindingHelperObserver` 因此永久留在 `key` 上 —— `dispose()` 之后 `key`
 * 仍持有指向整个 binding 的强引用。`key` 通常是长命对象（一个 property），所以这不是理论泄漏。
 *
 * **判别器是行为级的**：只数 `addListener` / `removeListener` 的调用次数（外加"dispose 后还
 * 会不会被唤醒"），不读字节码、不靠反射看私有字段。改前 `key.removes == 0`（红），
 * 改后 `key.removes == 1`（绿）。
 *
 * 这条缺陷在 `git HEAD` 上就存在（移植遗留），不是本轮引入 —— 也就是说这条用例同时是一个
 * **防回归**守卫和一个**回归基线**。
 */
class R31Fix245Test {

    /** 计数用 ObservableValue：谁挂监听、谁摘监听，各记一笔。不发事件，只计数。 */
    private class CountingValue(var v: String = "k0") : ObservableValue<String> {
        var adds = 0
        var removes = 0
        override fun addListener(listener: ChangeListener<in String>?) { adds++ }
        override fun removeListener(listener: ChangeListener<in String>?) { removes++ }
        override fun addListener(listener: InvalidationListener?) { adds++ }
        override fun removeListener(listener: InvalidationListener?) { removes++ }
        override fun getValue(): String = v
    }

    /** 计数用 ObservableMap。用 HashMap 兜底，只需实现 Observable 的两个方法。 */
    private class CountingMap : HashMap<String, String>(), ObservableMap<String, String> {
        var adds = 0
        var removes = 0
        override fun addListener(listener: MapChangeListener<in String, in String>?) { adds++ }
        override fun removeListener(listener: MapChangeListener<in String, in String>?) { removes++ }
        override fun addListener(listener: InvalidationListener?) { adds++ }
        override fun removeListener(listener: InvalidationListener?) { removes++ }
    }

    /**
     * 既计数、又真的把一个 `SimpleStringProperty` 的监听器转接出去的 ObservableValue。
     * 用于"dispose 之后还会不会被唤醒"这条用例 —— 只有真的会发事件的对象才观察得到。
     */
    private class ForwardingValue(private val real: SimpleStringProperty) : ObservableValue<String> {
        var adds = 0
        var removes = 0
        override fun addListener(listener: ChangeListener<in String>?) { adds++; real.addListener(listener) }
        override fun removeListener(listener: ChangeListener<in String>?) { removes++; real.removeListener(listener) }
        override fun addListener(listener: InvalidationListener?) { adds++; real.addListener(listener) }
        override fun removeListener(listener: InvalidationListener?) { removes++; real.removeListener(listener) }
        override fun getValue(): String = real.get()
    }

    // ==================================================================================
    // 1) 主用例：dispose() 必须把 key 上的 observer 也摘掉
    // ==================================================================================

    /**
     * 改前红：`key.removes == 0`（`bind` 加了 1，`unbind(op)` 只摘了 op）。
     * 改后绿：`key.removes == 1`，且 `key.removes == key.adds`。
     *
     * 同时断言 `op` 侧没被改坏 —— 修法是把实参补齐，不是换一套语义。
     */
    @Test
    fun disposeRemovesObserverFromBothDependencies() {
        val map = CountingMap().apply { put("k0", "v0") }
        val key = CountingValue("k0")

        val binding: ObjectBinding<String> = Bindings.valueAt(map, key)

        // 前提自检：工厂确实挂上了两个依赖（否则下面的断言会因为"根本没绑"而假绿）
        assertEquals("valueAt(map,key) 必须给 map 挂 1 个监听器", 1, map.adds)
        assertEquals("valueAt(map,key) 必须给 key 挂 1 个监听器", 1, key.adds)

        binding.dispose()

        assertEquals("dispose() 后 map 上的监听器必须被摘掉", map.adds, map.removes)
        assertEquals(
            "dispose() 后 key 上的监听器必须被摘掉 —— bind 挂了两个依赖，unbind 也必须解两个",
            key.adds, key.removes
        )
        assertEquals("map.removes 应恰好为 1", 1, map.removes)
        assertEquals("key.removes 应恰好为 1", 1, key.removes)
    }

    /**
     * `getDependencies()` 修复前后都必须报出**两个**依赖。
     *
     * 这条是"修 unbind 不该顺手改契约"的守卫：修法只能动 `dispose()` 的实参，不能把
     * `getDependencies()` 也砍成一个 —— 那样泄漏只是从"监听器"换成"契约说谎"。
     */
    @Test
    fun dependenciesStillReportBothOperands() {
        val map = CountingMap()
        val key = CountingValue()

        val binding = Bindings.valueAt(map, key)
        val deps = binding.dependencies

        assertEquals("getDependencies() 必须报 2 个依赖", 2, deps.size)
        assertSame("第 1 个依赖是 map", map, deps[0])
        assertSame("第 2 个依赖是 key", key, deps[1])
        binding.dispose()
    }

    // ==================================================================================
    // 2) 同族回归：相邻的 valueAt 重载不能因为这次改动被带坏
    // ==================================================================================

    /**
     * `valueAt(ObservableMap, K)`（单 op、无 key 监听）本来就只 `bind(op)` / `unbind(op)`，
     * 是配对的。这条守着"只改错的那一个，别把对的改成错的"。
     */
    @Test
    fun singleDependencyValueAtStaysBalanced() {
        val map = CountingMap().apply { put("k0", "v0") }

        val binding = Bindings.valueAt(map, "k0")
        assertEquals(1, map.adds)
        assertEquals("单实参变体只该报 1 个依赖", 1, binding.dependencies.size)

        binding.dispose()
        assertEquals("单实参变体的 dispose 必须摘掉 op", map.adds, map.removes)
    }

    /**
     * `dispose()` 幂等：第二次调用必须是 no-op。
     *
     * 为什么值得测：`ObjectBinding.unbind()` 在第一次调用时就把 `observer` 置 null，所以第二次
     * 应该是空转。补上 `key` 实参之后这个性质必须仍然成立 —— 否则一个被 dispose 两次的 binding
     * 会对 `key` 发出重复 removeListener，在某些 `ObservableValue` 实现上会抛。
     */
    @Test
    fun disposeIsIdempotent() {
        val map = CountingMap()
        val key = CountingValue()

        val binding = Bindings.valueAt(map, key)
        binding.dispose()
        val keyRemovesAfterFirst = key.removes
        val mapRemovesAfterFirst = map.removes

        binding.dispose()

        assertEquals("第二次 dispose 不得再动 key", keyRemovesAfterFirst, key.removes)
        assertEquals("第二次 dispose 不得再动 map", mapRemovesAfterFirst, map.removes)
    }

    // ==================================================================================
    // 3) 泄漏的后果：dispose 之后不许再被 key 唤醒
    // ==================================================================================

    /**
     * 泄漏的后果不是"计数器不好看"，而是 dispose 之后 binding **仍然活着**：`key` 一变，
     * `BindingHelperObserver` 就把这个已废弃的 binding 标脏并触发它的监听器。
     *
     * 这条直接观察那个后果：先 `get()` 让它 valid，`dispose()`，再改 `key`，断言
     * `isValid()` 仍为 true（没有任何人把它标脏）。改前这里会变红（still invalidated）。
     */
    @Test
    fun disposedBindingIsNoLongerInvalidatedByKey() {
        val map = CountingMap().apply { put("k0", "v0") }
        val real = SimpleStringProperty("k0")
        val key = ForwardingValue(real)

        val binding = Bindings.valueAt(map, key)
        assertEquals("前提：valueAt 之后 key 上挂了监听器", 1, key.adds)

        binding.get()                       // 计算一次，valid = true
        assertTrue("前提：get() 之后 binding 应当 valid", binding.isValid)

        binding.dispose()
        assertEquals("dispose 后 key 上的监听器必须摘干净", key.adds, key.removes)

        real.set("k1")                      // 改 key；修复前会把已废弃的 binding 标脏

        assertTrue(
            "dispose 之后 key 的变更不得再把这个 binding 标脏（修复前 isValid() 会变成 false）",
            binding.isValid
        )
    }
}
