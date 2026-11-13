package com.dsh

import com.tungsten.fclcore.fakefx.beans.InvalidationListener
import com.tungsten.fclcore.fakefx.beans.binding.Bindings
import com.tungsten.fclcore.fakefx.beans.property.SimpleObjectProperty
import com.tungsten.fclcore.fakefx.beans.property.SimpleStringProperty
import com.tungsten.fclcore.fakefx.beans.value.ChangeListener
import com.tungsten.fclcore.fakefx.beans.value.ObservableValue
import com.tungsten.fclcore.fakefx.beans.value.ObservableValueBase
import com.tungsten.fclcore.fakefx.binding.ContentBinding
import com.tungsten.fclcore.fakefx.collections.FXCollections
import com.tungsten.fclcore.fakefx.util.StringConverter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * round31 / 第 4 波（`fix-r31-223`）：slice-33 error-handling 三条 + slice-34 data-consistency 一条。
 *
 * | 用例 | finding | 改前 | 改后 |
 * |---|---|---|---|
 * | [conversionFailureIsReportedWithItsCause] | `slice-33-err-01` | 红（0 条警告） | 绿 |
 * | [validConversionStaysSilent]（反向守卫） | `slice-33-err-01` | 绿 | 绿 |
 * | [failingReadIsIsolatedAndResetsBaseline] | `slice-33-err-02`（`Generic`） | 红（异常逸出 + 基线过期） | 绿 |
 * | [failingReadIsIsolatedInSingleChangeHelper] | `slice-33-err-02`（`SingleChange`） | 红 | 绿 |
 * | [mirrorFailureIsReportedWithContext] | `slice-33-err-04` | 红（0 条警告） | 绿 |
 * | [mapperIsAppliedToNullSource] | `slice-34-dc-03` | 红（`null` / 0 次调用） | 绿 |
 * | [mapperStillAppliedToNonNullSource]（反向守卫） | `slice-34-dc-03` | 绿 | 绿 |
 * | [mapperFailurePropagatesUnchanged]（反向守卫） | `slice-34-dc-03` | 绿 | 绿 |
 *
 * ## 断言口径：全部是运行时事实，不是源码文本
 *
 * 四组断言分别落在：**JUL 日志记录**（`level` + `LogRecord.thrown` 的对象身份）、
 * **真实监听器被调用的次数**、**异常是否从通知入口逸出**、**binding 的实际 `getValue()` 返回值**。
 * 没有任何一条读源码、`grep` 字符串或断言语句顺序 —— 本轮已多次证明字面串断言形同虚设。
 *
 * ## 日志探针为什么可用
 *
 * 被测代码写的是 `Logger.getLogger("FCL")`（与 `com.tungsten.fclcore.util.Logging.LOG` **同名同实例**，
 * 见 `FCLPath.java:14-18` 的同一手法）。所以这里 `Logger.getLogger("FCL")` 拿到的就是被测 logger，
 * 挂 `Handler` 即可观察 —— 不需要反射读私有字段，也不需要知道实现细节。
 *
 * 每条断言都要求 `LogRecord.thrown` **就是**抛出的那个对象：只数"有日志"会让
 * "打一句无异常的 WARNING"这种假修复蒙混过关，而带对象正是"留下诊断线索"的实质。
 *
 * 跑法：`sh run-tests.sh`
 */
class R31Fix223Test {

    companion object {
        /** 转换器视为"非法输入"的前缀 —— 用一个不可能与数字混淆的串，确保走的是失败分支。 */
        private const val BAD_INPUT = "not-a-number-223"

        /** `null` 源经 mapper 后的哨兵值。取一个正常业务不会返回的串。 */
        private const val NULL_SENTINEL = "MAPPER_SAW_NULL"

        /** 注入的 `getValue()` 失败标识。断言 handler 收到的**就是**这一个。 */
        private const val INJECTED_READ_FAILURE = "injected getValue() failure"

        /** 注入的目标 list 不可变故障。 */
        private const val INJECTED_MIRROR_FAILURE = "injected: target list refuses mutation"
    }

    // ============================================================ 日志探针

    private val log: Logger = Logger.getLogger("FCL")
    private val records = ArrayList<LogRecord>()

    private val collector: Handler = object : Handler() {
        override fun publish(record: LogRecord) {
            records.add(record)
        }

        override fun flush() {}
        override fun close() {}
    }

    @Before
    fun attachLogProbe() {
        log.addHandler(collector)
    }

    @After
    fun detachLogProbe() {
        log.removeHandler(collector)
    }

    /** 只挑 `>= WARNING` 且**带异常对象**的记录 —— 正是"留下诊断线索"的判据。 */
    private fun warningsWithCause(): List<LogRecord> =
        records.filter { it.level.intValue() >= Level.WARNING.intValue() && it.thrown != null }

    private fun describe(records: List<LogRecord>): String =
        if (records.isEmpty()) "[]"
        else records.joinToString(prefix = "[", postfix = "]") {
            it.level.name + ":" + it.message + " thrown=" + it.thrown
        }

    // ============================================================ 线程 handler 探针

    /**
     * 换掉当前线程的 `UncaughtExceptionHandler` 跑一段代码，返回它收到的 throwable 列表。
     *
     * 为什么要换：被测代码用 `Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(...)`
     * 转交异常。默认 handler 会把栈打到 stderr 并（在 Android 上）走 `CrashReporter` 的崩溃流程，
     * 既会污染输出、也会让"是否转交了"这件事无法计数。换掉后计数即可，`finally` 里必定还原
     * （还原放在 finally 里是硬要求：漏还原会让**后面所有**用例的异常都静默消失）。
     */
    private fun <T> capturingUncaught(block: () -> T): Pair<T?, List<Throwable>> {
        val caught = ArrayList<Throwable>()
        val thread = Thread.currentThread()
        val saved = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, t -> caught.add(t) }
        var result: T? = null
        try {
            result = block()
        } finally {
            thread.uncaughtExceptionHandler = saved
        }
        return result to caught
    }

    // ============================================================ slice-33-err-01

    /**
     * `slice-33-err-01`：`StringConversionBidirectionalBinding.invalidated` 的两个
     * `catch (Exception e)` 块体都不引用 `e` —— 转换失败被**完全吞没**，目标属性被改写成
     * `null`/`""`，且没有任何诊断线索。
     *
     * 期望行为：每次转换失败留下**一条** `>= WARNING`、**带那个异常对象**的记录。
     *
     * 改前实测：`warn_count=0`（全仓无任何记录）⇒ 红。
     */
    @Test
    fun conversionFailureIsReportedWithItsCause() {
        val text = SimpleStringProperty("0")
        val other = SimpleObjectProperty<Any>()

        val converter = object : StringConverter<Any>() {
            override fun toString(obj: Any?): String = if (obj == null) "0" else obj.toString()
            override fun fromString(str: String?): Any {
                // 这条路正是"用户可控字符串直接喂给 converter.fromString"的形态
                if (str != null && str.startsWith(BAD_INPUT)) {
                    throw IllegalArgumentException("rejected input: $str")
                }
                return str!!
            }
        }

        Bindings.bindBidirectional(text, other, converter)
        text.set(BAD_INPUT)

        val warnings = warningsWithCause()
        assertEquals(
            "a converter failure must leave exactly one warning carrying the cause; saw ${describe(records)}",
            1,
            warnings.size
        )
        val thrown = warnings[0].thrown
        assertTrue(
            "the logged cause must be the converter's own exception, got <$thrown>",
            thrown is IllegalArgumentException && thrown.message == "rejected input: $BAD_INPUT"
        )
    }

    /**
     * 反向守卫：**合法**输入不得产生任何 `>= WARNING` 记录。
     *
     * 若因为"反正都要记日志"而无条件打一行，本用例变红 —— 它挡的是
     * "把诊断补成噪音"这种过度修复。
     */
    @Test
    fun validConversionStaysSilent() {
        val text = SimpleStringProperty("42")
        val other = SimpleObjectProperty<Any>()

        val converter = object : StringConverter<Any>() {
            override fun toString(obj: Any?): String = if (obj == null) "0" else obj.toString()
            override fun fromString(str: String?): Any = str!!
        }

        Bindings.bindBidirectional(text, other, converter)
        text.set("43")

        assertEquals(
            "a successful conversion must not log a warning; saw ${describe(records)}",
            0,
            warningsWithCause().size
        )
    }

    // ============================================================ slice-33-err-02

    /**
     * 一个 `getValue()` 可以**定点抛异常**的 `ObservableValue`。
     *
     * 用真实基类 `ObservableValueBase`（它持有真的 `ExpressionHelper`），所以测的是
     * **生产通知链路**本身，不是替身实现。`poke()` 走 `fireValueChangedEvent()`，
     * 与各 `*PropertyBase.markInvalid()` 调用的是同一个入口。
     */
    private class FlakyValue(initial: String) : ObservableValueBase<String>() {
        private var current: String? = initial
        var failNextRead = false

        override fun getValue(): String? {
            if (failNextRead) {
                failNextRead = false
                throw IllegalStateException(INJECTED_READ_FAILURE)
            }
            return current
        }

        fun poke(next: String) {
            current = next
            fireValueChangedEvent()
        }
    }

    /**
     * `slice-33-err-02`（`Generic` 分支，`ExpressionHelper.java:309-334`）：
     * 外层 `try` 只有 `finally`（仅 `locked = false`），而
     * `currentValue = observable.getValue()` 无独立保护。
     *
     * 两个后果一起断言，缺一不可：
     * 1. `getValue()` 抛出的异常**逸出通知入口**（本该被隔离并转交 handler）；
     * 2. 基线停在**旧值**，导致下一次"真的变了"的事件被误抑制。
     *
     * 只断 (1) 会让"加个 catch 但不复位基线"的假修复通过；只断 (2) 会让
     * "复位了但异常照旧外抛"的假修复通过。
     *
     * 改前实测：`escaped=IllegalStateException`、`handler_calls=0`、后续通知 `0` ⇒ 红。
     */
    @Test
    fun failingReadIsIsolatedAndResetsBaseline() {
        val value = FlakyValue("A")
        val invalidations = AtomicInteger()
        val changes = AtomicInteger()

        // invalidation + change 各一个 ⇒ ExpressionHelper 走 Generic 分支
        value.addListener(InvalidationListener { invalidations.incrementAndGet() })
        value.addListener(ChangeListener<String> { _, _, _ -> changes.incrementAndGet() })

        var escaped: Throwable? = null
        val (_, handled) = capturingUncaught {
            try {
                value.failNextRead = true
                value.poke("B")
            } catch (t: Throwable) {
                escaped = t
            }
        }

        assertNull(
            "a failing getValue() must not escape the notifier (it aborts every remaining listener); escaped=<$escaped>",
            escaped
        )
        assertEquals(
            "the failing read must be handed to the thread's uncaught-exception handler",
            1,
            handled.size
        )
        assertTrue(
            "the handled throwable must be the injected one, got <${handled.firstOrNull()}>",
            handled.firstOrNull() is IllegalStateException &&
                handled[0].message == INJECTED_READ_FAILURE
        )
        assertEquals(
            "invalidation listeners run before the read, so they must still have been notified",
            1,
            invalidations.get()
        )

        val before = changes.get()
        value.poke("A") // 值回到失败前的那一个 —— 相对**过期基线**看是"没变"，实际是 B -> A
        assertEquals(
            "a real change after a failed read must not be suppressed by the stale baseline",
            before + 1,
            changes.get()
        )
    }

    /**
     * 同一个缺陷的 `SingleChange` 形态（`ExpressionHelper.java:141-142`）：读取发生在
     * `try` **之前**，连 `finally` 都没有。单监听器是最常见的注册形态（只挂一个
     * `ChangeListener`），所以这条必须单独钉。
     */
    @Test
    fun failingReadIsIsolatedInSingleChangeHelper() {
        val value = FlakyValue("A")
        val changes = AtomicInteger()

        // 只挂一个 change listener ⇒ 走 SingleChange，不是 Generic
        value.addListener(ChangeListener<String> { _, _, _ -> changes.incrementAndGet() })

        var escaped: Throwable? = null
        val (_, handled) = capturingUncaught {
            try {
                value.failNextRead = true
                value.poke("B")
            } catch (t: Throwable) {
                escaped = t
            }
        }

        assertNull("a failing getValue() must not escape SingleChange; escaped=<$escaped>", escaped)
        assertEquals("SingleChange must also hand the failure to the thread handler", 1, handled.size)

        val before = changes.get()
        value.poke("A")
        assertEquals(
            "SingleChange must reset its baseline as well, or the next change is suppressed",
            before + 1,
            changes.get()
        )
    }

    // ============================================================ slice-33-err-04

    /**
     * `ArrayList` 子类，可在**第一次镜像写入时**抛异常。用它模拟"目标 list 不可变"
     * （真实场景就是 `UnsupportedOperationException`）。
     *
     * `removeRange` 是被覆盖点：`ArrayList.subList(from, to).clear()` 最终会调到
     * 根 list 的 `removeRange`，所以覆盖它即可精确拦住 `ContentBinding.onChanged` 的
     * `subList(...).clear()` 那一步。
     */
    private class ArmedList<E>(items: Collection<E>) : ArrayList<E>(items) {
        var armed = false

        override fun removeRange(fromIndex: Int, toIndex: Int) {
            if (armed) throw UnsupportedOperationException(INJECTED_MIRROR_FAILURE)
            super.removeRange(fromIndex, toIndex)
        }
    }

    /**
     * `slice-33-err-04`：`ContentBinding.ListContentBinding.onChanged` 的
     * `clear()` 与 `addAll()` 之间无任何 `try`，失败时既无诊断也无回滚。
     *
     * 期望行为：镜像失败留下**一条** `>= WARNING`、**带那个异常对象**且**带目标 list 身份**的记录。
     * 异常照旧向外传播（不吞）。
     *
     * 改前实测：`warn_count=0` ⇒ 红。
     *
     * 附注（审计 alternatives[2] 的实测结论）：异常**本来就**会被 `ListListenerHelper`
     * 的逐监听器 `try/catch` 转交到线程 handler —— 所以"完全无痕迹"只对**行内**成立。
     * 本条补的是**上下文**：handler 报告说不出是哪个镜像目标处于半应用状态。
     */
    @Test
    fun mirrorFailureIsReportedWithContext() {
        val source = FXCollections.observableArrayList("a", "b")
        val target = ArmedList(listOf("a", "b"))

        val bound = ContentBinding.bind(target, source)
        assertNotNull("precondition: the content binding must be created", bound)

        target.armed = true
        val (_, handled) = capturingUncaught {
            // 元素替换 = wasRemoved + wasAdded，正好压到 clear() 与 addAll() 之间的缺口上
            source.set(0, "z")
        }

        assertTrue(
            "precondition: the mirror failure must actually have happened, handled=<$handled>",
            handled.any { it is UnsupportedOperationException && it.message == INJECTED_MIRROR_FAILURE }
        )

        val warnings = warningsWithCause()
        assertEquals(
            "a failed mirror must leave exactly one warning carrying the cause; saw ${describe(records)}",
            1,
            warnings.size
        )
        val thrown = warnings[0].thrown
        assertTrue(
            "the logged cause must be the injected UnsupportedOperationException, got <$thrown>",
            thrown is UnsupportedOperationException && thrown.message == INJECTED_MIRROR_FAILURE
        )
        assertTrue(
            "the warning must name the target list so a half-applied mirror is traceable; was <${warnings[0].message}>",
            warnings[0].message.contains(ArmedList::class.java.name)
        )
    }

    // ============================================================ slice-34-dc-03

    /**
     * `slice-34-dc-03`：`MappedBinding.computeValue` 对 `null` 源短路
     * （`return value == null ? null : mapper.apply(value);`），
     * 而 `mapper` 的契约（`Function<? super S, ? extends T>`）要求对任意输入都给结果。
     *
     * 改前实测：`mapped.get()=null`、`mapper_calls=0` ⇒ 红。
     */
    @Test
    fun mapperIsAppliedToNullSource() {
        val source = SimpleStringProperty()
        assertNull("precondition: SimpleStringProperty() must start out null", source.get())

        val calls = AtomicInteger()
        val mapped: ObservableValue<String> = source.map { value ->
            calls.incrementAndGet()
            if (value == null) NULL_SENTINEL else value
        }

        assertEquals(
            "the mapper must be applied to a null source value instead of being short-circuited",
            NULL_SENTINEL,
            mapped.value
        )
        assertEquals("the mapper must have been called exactly once", 1, calls.get())
    }

    /** 反向守卫：非 `null` 源照旧被映射（防"改成永远返回哨兵"这类假修复）。 */
    @Test
    fun mapperStillAppliedToNonNullSource() {
        val source = SimpleStringProperty("actual")
        val calls = AtomicInteger()
        val mapped: ObservableValue<String> = source.map { value ->
            calls.incrementAndGet()
            if (value == null) NULL_SENTINEL else value
        }

        assertEquals("a non-null source must still be mapped verbatim", "actual", mapped.value)
        assertEquals("the mapper must have been called once", 1, calls.get())
    }

    /**
     * 反向守卫：mapper 在 `null` 上抛异常时**必须原样传播**（不得被吞成 `null`）。
     *
     * 这是去掉短路后的新可达路径，也是最容易"顺手加个 catch 兜成 null"的地方 ——
     * 那样会把 `Function` 的失败重新变成静默 `null`，等于换了个姿势回到缺陷。
     */
    @Test
    fun mapperFailurePropagatesUnchanged() {
        val source = SimpleStringProperty()
        val mapped: ObservableValue<String> = source.map { _ -> throw IllegalStateException("mapper rejects null") }

        var escaped: Throwable? = null
        try {
            mapped.value
        } catch (t: Throwable) {
            escaped = t
        }
        assertTrue(
            "a mapper failure on a null source must propagate unchanged, got <$escaped>",
            escaped is IllegalStateException && escaped.message == "mapper rejects null"
        )
    }
}
