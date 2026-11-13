package com.dsh

import com.tungsten.fclcore.fakefx.beans.InvalidationListener
import com.tungsten.fclcore.fakefx.beans.binding.StringBinding
import com.tungsten.fclcore.fakefx.beans.property.SimpleObjectProperty
import com.tungsten.fclcore.fakefx.beans.value.ObservableValue
import com.tungsten.fclcore.fakefx.binding.SelectBinding
import com.tungsten.fclcore.fakefx.binding.StringFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IllegalFormatException
import java.util.Locale

/**
 * R31 / `slice-35-C1` + `slice-35-C3` + `slice-35-C4` + `slice-35-C5`（fix-r31-213）
 *
 * ## 为什么用「真实 property + 计数的 add/remove」当探针
 *
 * 本轮教训：结构断言/源码文本匹配形同虚设（`fix-210` 的 M2、`verify-210` 的六种逃脱）。
 * 这里**不读源码、不做反射字段检查**，而是在**真实的 `SimpleObjectProperty` 子类**上
 * 数 `addListener`/`removeListener` 的**实际调用次数**。注册/注销配对是可数的行为事实：
 * 改前 `adds=1, removes=0`，改后 `adds=1, removes=1`。
 *
 * 反射也能数 `ExpressionHelper` 里的 listener，但那依赖私有字段名；计数探针依赖的是
 * **公开接口上的真实调用**，实现改内部结构也照样成立。
 *
 * ## 四条 finding 各自钉什么
 *
 * - **C1**：`SelectBinding` 构造期在根上注册 `WeakInvalidationListener`，`dispose()` 从
 *   `i=1` 起步从不注销根；`getDependencies()` 又从 `i=0` 起步，释放后仍报根为依赖。
 *   期望：`dispose()` 后**根上的这次注册被移除**、`getDependencies()` 为空；
 *   但 `dispose()` **之前**根变化必须仍能失效（防「修成死绑定」）。
 * - **C3**：`concat` 单参 `null` 抛 NPE，多参渲染 `"null"`。期望：单参 `null` → `"null"`，
 *   非 null / Observable 路径不变。
 * - **C4**：三个匿名子类 bind/unbind 两侧各自现算 `extractDependencies(args)`。
 *   期望：`dispose()` 注销的是 **bind 时的快照**，不随调用方复用的数组漂移。
 * - **C5**：`format` 构造期 `formatter.get()` 抛非法格式异常时，已 bind 但无注销入口。
 *   期望：异常**照旧抛出**（不得吞掉），但抛出前注销本次注册。
 */
class R31Fix213Test {

    /**
     * 真实 `SimpleObjectProperty` 的计数子类。
     *
     * 只拦 `InvalidationListener` 那条重载 —— `fakefx` 的绑定实现（`BindingHelperObserver`、
     * `WeakInvalidationListener`）都是 `InvalidationListener`，`SelectBinding` 与
     * `StringBinding.bind` 都走这条路径。`ChangeListener` 路径本单不涉及，保持父类行为。
     */
    private class CountingProperty<T>(initial: T) : SimpleObjectProperty<T>(initial) {
        var invalidationAdds = 0
        var invalidationRemoves = 0

        override fun addListener(listener: InvalidationListener) {
            invalidationAdds++
            super.addListener(listener)
        }

        override fun removeListener(listener: InvalidationListener) {
            invalidationRemoves++
            super.removeListener(listener)
        }

        /** 净存活注册数：>0 表示有监听器没被注销。 */
        val liveInvalidationListeners: Int get() = invalidationAdds - invalidationRemoves
    }

    // ------------------------------------------------------------------
    // C1 —— SelectBinding 根依赖注册/注销不配对
    // ------------------------------------------------------------------

    /**
     * C1 主断言：`dispose()` 之后根上不得残留注册，且依赖列表必须收敛为空。
     *
     * 改前红点：`root.invalidationRemoves` 是 0（`unregisterListener()` 从 `i=1` 起步），
     * 且 `getDependencies()` 仍返回 `[root]`（`updateDependencies()` 从 `i=0` 起步）。
     *
     * 最后一段是**反向守卫**：dispose 之后根变化不得再让绑定失效 —— 它证明「根监听真的没了」，
     * 而不只是「计数变了」。两条一起成立，才排除「只改了计数没改行为」。
     */
    @Test
    fun c1DisposeRemovesRootListenerAndClearsDependencies() {
        val root = CountingProperty("a")
        val binding = SelectBinding.AsObject<String>(root as ObservableValue<*>)

        assertEquals("构造期应在根上注册一次监听", 1, root.invalidationAdds)
        assertEquals("构造期不应注销任何监听", 0, root.invalidationRemoves)
        assertEquals("零 step 的 SelectBinding 应直接返回根的值", "a", binding.get())

        binding.dispose()

        assertEquals(
            "C1：dispose() 必须注销根上的那次注册（当前 unregisterListener 从 i=1 起步，根永不注销）",
            1,
            root.invalidationRemoves
        )
        assertEquals(
            "C1：dispose() 后 getDependencies() 必须为空（不得仍把根报为依赖）",
            0,
            binding.dependencies.size
        )

        // 反向守卫：监听器真的没了 —— 根变化不再使绑定失效。
        root.setValue("b")
        assertTrue(
            "C1：dispose() 后根变化不得再使绑定失效（说明根监听器已真正注销）",
            binding.isValid
        )
    }

    /**
     * C1 反向守卫（防过度修复）：**dispose 之前**根监听必须仍然有效，
     * `getDependencies()` 必须仍包含根。
     *
     * 如果有人把修复写成「构造后立刻不注册根」或「updateDependencies 永远不含根」，
     * 这条会红 —— 那样 `SelectBinding` 就再也不会随根变化重算，是把缺陷换个方向。
     */
    @Test
    fun c1RootListenerStaysLiveBeforeDispose() {
        val root = CountingProperty("a")
        val binding = SelectBinding.AsObject<String>(root as ObservableValue<*>)

        assertEquals("a", binding.get())
        assertEquals("dispose 之前 getDependencies() 必须包含根", 1, binding.dependencies.size)

        root.setValue("b")

        assertFalse("dispose 之前根变化必须使绑定失效", binding.isValid)
        assertEquals("失效后重算必须拿到新值", "b", binding.get())
    }

    /**
     * C1：`dispose()` 必须幂等 —— 重复调用不得重复 `removeListener`。
     *
     * 加 `disposed` 标志后最容易出的事故就是「第二次 dispose 又减一次」，
     * 或「第二次 dispose 抛异常」。这条同时钉住「不抛」与「不重复注销」。
     */
    @Test
    fun c1DisposeIsIdempotent() {
        val root = CountingProperty("a")
        val binding = SelectBinding.AsObject<String>(root as ObservableValue<*>)
        assertEquals("a", binding.get())

        binding.dispose()
        binding.dispose()

        assertEquals("C1：重复 dispose 不得重复注销根监听", 1, root.invalidationRemoves)
        assertEquals("C1：重复 dispose 后依赖列表仍须为空", 0, binding.dependencies.size)
    }

    /**
     * C1 反向守卫（防「把缺陷换个方向」）：`dispose()` 只负责解除注册与依赖收敛，
     * **不得**破坏之后读取值的能力。
     *
     * 本单曾在简化修复时把根置 null，那样 `get()` 会在 `getObservableValue()` 里 NPE ——
     * 这是审计没主张过的行为改变。这条钉住 `dispose()` 后 `get()` 仍能返回最后的值。
     */
    @Test
    fun c1GetStillWorksAfterDispose() {
        val root = CountingProperty("a")
        val binding = SelectBinding.AsObject<String>(root as ObservableValue<*>)
        assertEquals("a", binding.get())

        binding.dispose()

        assertEquals("C1：dispose() 之后 get() 仍必须能返回最后一次的值", "a", binding.get())
    }

    // ------------------------------------------------------------------
    // C3 —— concat 单参 null 的渲染
    // ------------------------------------------------------------------

    /**
     * C3 主断言：单参数 `null` 必须与同方法的多参数分支一致，渲染成 `"null"`。
     *
     * 必须用 **spread**（`*arrayOf<Any?>(null)`）才能压到「单参数值 = null」这条路径；
     * 直接写 `concat(null)` 在 Kotlin 里会被当成 `args == null`（那是另一条分支，返回 `""`）。
     *
     * 改前红点：`cur.toString()` 对 null 抛 NPE。
     */
    @Test
    fun c3SingleNullArgumentRendersLikeMultiArg() {
        val single = StringFormatter.concat(*arrayOf<Any?>(null))
        assertEquals(
            "C3：concat 的单参数 null 必须与多参数分支一致渲染为 \"null\"（原实现抛 NPE）",
            "null",
            single.get()
        )

        // 同一契约的多参数对照：这是「期望值从哪来」的可执行依据，不是拍脑袋。
        val multi = StringFormatter.concat(*arrayOf<Any?>(null, "-suffix"))
        assertEquals("多参数无依赖分支的 null 渲染是 \"null\"", "null-suffix", multi.get())
    }

    /**
     * C3 反向守卫：把 `cur.toString()` 换成 `String.valueOf(cur)` 后，
     * 非 null 单参与 Observable 单参的行为必须**逐字不变**。
     */
    @Test
    fun c3SingleNonNullAndObservableUnchanged() {
        val literal = StringFormatter.concat(*arrayOf<Any?>("x"))
        assertEquals("单参非 null 必须原样返回", "x", literal.get())

        val dep = CountingProperty("p")
        val observable = StringFormatter.concat(*arrayOf<Any?>(dep))
        assertEquals("单参 Observable 应取当前值", "p", observable.get())
        assertEquals("单参 Observable 必须注册依赖", 1, dep.invalidationAdds)

        dep.setValue("q")
        assertEquals("Observable 单参必须随依赖更新", "q", observable.get())
    }

    // ------------------------------------------------------------------
    // C4 —— bind/unbind 两侧快照不一致
    // ------------------------------------------------------------------

    /**
     * 用**同一个数组实例**调 `StringFormatter` 的 varargs 方法。
     *
     * 为什么必须走反射：Kotlin 的 spread（`f(*args)`）在把 `Array<Any?>` 传给 Java 的
     * `Object...` 时会**复制数组**（平台类型的变型安全），于是调用方之后的修改到不了被捕获的数组，
     * C4 就测不出来 —— 本单第一版正是这样被静默逃脱的（9 条里 C4 两条假绿）。
     * 反射调用只改变「怎么把数组递进去」，**执行的仍是真实方法**，不是复刻实现。
     */
    private fun callWithExactArray(methodName: String, fixedArgs: List<Any?>, shared: Array<Any?>): Any? {
        val method = StringFormatter::class.java.methods.first {
            it.name == methodName && it.isVarArgs &&
                it.parameterTypes.size == fixedArgs.size + 1
        }
        // 参数顺序：concat(shared) / format(String, shared) / format(Locale, String, shared)
        val callArgs = (fixedArgs + listOf(shared)).toTypedArray()
        return method.invoke(null, *callArgs)
    }

    /**
     * C4 主断言（`concat` 路径）：调用方显式复用一个 `Object[]`，在 bind 与 dispose 之间替换元素，
     * `dispose()` 注销的必须是 **bind 时那一份**。
     *
     * 改前红点：`dispose()` 现算 `extractDependencies(args)` 得到 `[b]`，于是
     * `a` 上残留注册（`a.invalidationRemoves == 0`），`b` 被做一次多余 remove。
     *
     * 为什么这条必须用显式数组：`concat(Object...)` 的普通调用由编译器新建数组、正常代码改不到；
     * 但 `Object[]` 是公开 API 的合法入参（Java 侧直接传、Kotlin 侧走反射），缺陷只在真实可写的入口上显形。
     */
    @Test
    fun c4DisposeUnbindsOriginallyBoundDependencies_concat() {
        val a = CountingProperty("a")
        val b = CountingProperty("b")
        val shared = arrayOf<Any?>(a, "-tail")

        val binding = callWithExactArray("concat", emptyList(), shared) as StringBinding
        assertEquals("构造期应绑定数组里的 Observable", 1, a.invalidationAdds)
        assertEquals("构造期不得绑定第二个 Observable", 0, b.invalidationAdds)
        assertEquals("a-tail", binding.get())

        // 调用方复用同一个数组并替换元素 —— 这正是缺陷的触发条件。
        shared[0] = b

        binding.dispose()

        assertEquals(
            "C4：dispose() 必须注销 bind 时的那个依赖（当前实现现算 args，注销的是替换后的 b）",
            1,
            a.invalidationRemoves
        )
        assertEquals(
            "C4：不得对替换后的依赖做多余注销",
            0,
            b.invalidationRemoves
        )
        assertTrue(
            "C4：getDependencies() 必须报告 bind 时的快照，而不是被改过的 args",
            binding.dependencies.contains(a)
        )
    }

    /**
     * C4（`format` 路径）：与 `concat` 同构，钉住 `format` 的两个重载共享的同一处模式。
     * 这条同时经过 C5 的 `formatter.get()` 强制求值路径，覆盖两个缺陷叠加的情形。
     */
    @Test
    fun c4DisposeUnbindsOriginallyBoundDependencies_format() {
        val a = CountingProperty("A")
        val b = CountingProperty("B")
        val shared = arrayOf<Any?>(a, "!")

        val binding = callWithExactArray("format", listOf("%s%s"), shared) as StringBinding
        assertEquals("A!", binding.get())
        assertEquals("构造期应绑定数组里的 Observable", 1, a.invalidationAdds)

        shared[0] = b

        binding.dispose()

        assertEquals(
            "C4(format)：dispose() 必须注销 bind 时的依赖",
            1,
            a.invalidationRemoves
        )
        assertEquals(
            "C4(format)：不得对替换后的依赖做多余注销",
            0,
            b.invalidationRemoves
        )
    }

    // ------------------------------------------------------------------
    // C5 —— 构造期强制求值失败后没有注销入口
    // ------------------------------------------------------------------

    /**
     * C5 主断言：非法格式串**必须继续抛出** `IllegalFormatException`（修的是注销，不是吞异常），
     * 且抛出之前必须把构造期注册的依赖注销掉。
     *
     * 改前红点：异常正常抛出，但 `a.invalidationRemoves == 0` —— 匿名实例已 bind、永不返回、
     * `dispose()` 是唯一注销入口且无人调用，于是 property 上残留 `BindingHelperObserver`。
     *
     * 两个重载都要跑：它们各自有一份 `formatter.get()`。
     * `%d` 配字符串值抛 `IllegalFormatConversionException`（`IllegalFormatException` 子类）。
     */
    @Test
    fun c5IllegalFormatThrowsAndUnregistersDependencies() {
        val noLocale = CountingProperty("not-a-number")
        var noLocaleThrown: Throwable? = null
        try {
            StringFormatter.format("%d", *arrayOf<Any?>(noLocale))
        } catch (t: Throwable) {
            noLocaleThrown = t
        }
        assertTrue(
            "C5：非法格式串必须继续抛出 IllegalFormatException（不得被吞掉），实际=${noLocaleThrown}",
            noLocaleThrown is IllegalFormatException
        )
        assertEquals(
            "C5：format(String,...) 构造失败后必须在抛出前注销依赖（当前无注销入口）",
            1,
            noLocale.invalidationRemoves
        )
        assertEquals("C5：构造失败后不得残留监听器", 0, noLocale.liveInvalidationListeners)

        val withLocale = CountingProperty("not-a-number")
        var withLocaleThrown: Throwable? = null
        try {
            StringFormatter.format(Locale.US, "%d", *arrayOf<Any?>(withLocale))
        } catch (t: Throwable) {
            withLocaleThrown = t
        }
        assertTrue(
            "C5：format(Locale,...) 同样必须抛出 IllegalFormatException，实际=${withLocaleThrown}",
            withLocaleThrown is IllegalFormatException
        )
        assertEquals(
            "C5：format(Locale,...) 构造失败后必须在抛出前注销依赖",
            1,
            withLocale.invalidationRemoves
        )
        assertEquals("C5：format(Locale,...) 构造失败后不得残留监听器", 0, withLocale.liveInvalidationListeners)
    }

    /**
     * C5 反向守卫：合法格式串的返回值必须仍是**活绑定** —— 不能为了加 try/catch
     * 而把 `formatter.get()` 删掉、或把异常路径的 dispose 变成对成功路径也 dispose。
     */
    @Test
    fun c5ValidFormatKeepsLiveBinding() {
        val dep = CountingProperty("zzz")
        val binding = StringFormatter.format("%s", *arrayOf<Any?>(dep)) as StringBinding

        assertEquals("合法格式串应直接求值", "zzz", binding.get())
        assertEquals("合法格式串必须注册一次依赖", 1, dep.invalidationAdds)
        assertEquals("合法格式串不得被 dispose", 0, dep.invalidationRemoves)

        dep.setValue("yyy")
        assertFalse("合法格式串返回的绑定必须仍随依赖失效", binding.isValid)
        assertEquals("失效后重算必须拿到新值", "yyy", binding.get())
    }
}
