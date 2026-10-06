package com.dsh

import com.dsh.core.ProotRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * `ProotRunner.toPid` 的回归测试 —— 钉住 B10：
 * **反射拿到的 int 会被装箱成 Integer，`as Long` 必然抛 ClassCastException → pid 永远 -1**。
 *
 * 为什么必须单测这条纯函数（而不是直接测 `Handle.pid()`）：
 * - Android 的 `java.lang.Process` **没有 pid()**（api-versions.xml 里 android-35 的
 *   java.lang.Process 只有 destroy/exitValue/isAlive/waitFor…），所以 `Handle.pid()` 在
 *   真机上是"反射失败 → -1"，而在电脑 JVM 上却是"反射成功且返回 Long" —— **两头都测不出这个 bug**。
 * - 真正出问题的只有"反射成功、拿到的是装箱 Integer"这一种中间态，只能靠直接喂原始值来覆盖。
 *
 * 跑法：`sh run-tests.sh`（或 `./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.*"`）
 */
class ProcessPidConversionTest {

    /**
     * ★ 本文件的核心用例：**这就是原 bug 的形状**。
     *
     * 用一个真的返回 `int` 的方法 + 真反射（不是手搓 `Integer` 常量），完整重演
     * `Method.invoke` 的装箱行为；再证明旧写法 `as Long` 会炸、新写法返回正确 pid。
     * 只要有人把 `toPid` 改回 `raw as Long`（或只处理 Long），这条立刻红。
     */
    @Test
    fun boxedIntegerFromReflectionIsNotTreatedAsMissingPid() {
        val m: Method = IntReturningPidMethod::class.java.getMethod("pid")
        assertEquals("前置条件：被反射的方法必须返回 int（Android Process.pid() 的形状）", Int::class.javaPrimitiveType, m.getReturnType())

        val raw = m.invoke(IntReturningPidMethod())
        assertEquals("前置条件：反射把 int 装箱成 Integer", "java.lang.Integer", raw!!.javaClass.name)

        // 旧写法（B10 的根因）：Integer 不是 Long 的子类，as 只认真正的 Long → 必抛
        var oldStyleThrew = false
        val oldStyleValue = runCatching { raw as Long }.getOrElse { oldStyleThrew = true; -1L }
        assertTrue("旧写法 `as Long` 必须抛异常——不抛就说明这个 bug 的前提不成立了", oldStyleThrew)
        assertEquals("旧写法被 runCatching 吞掉后就是 -1（真机上 pid 永远 -1 的来源）", -1L, oldStyleValue)

        // 新写法：不能因为它被装箱成 Integer 就当"拿不到 pid"（旧实现返回 -1，此处必红）
        assertEquals("装箱成 Integer 的 pid 不能被当成拿不到 pid", 1234L, ProotRunner.toPid(raw))
    }

    /** `Long`（桌面 JDK 的 `Process.pid()` 就是 long → 装箱 Long）要继续正常 */
    @Test
    fun acceptsBoxedLong() {
        assertEquals(4321L, ProotRunner.toPid(4321L))
        assertEquals(Long.MAX_VALUE, ProotRunner.toPid(Long.MAX_VALUE))
        // 32 位以外的高位不能被截断（截成 Int 会得到 -1 之类的假 pid）
        assertEquals(0x1_0000_0001L, ProotRunner.toPid(0x1_0000_0001L))
    }

    /** `Int`：**原 bug 的直接受害者**（`Process.pid()` 返回 int 时就是这条路） */
    @Test
    fun acceptsBoxedInt() {
        assertEquals(1234L, ProotRunner.toPid(1234))
        assertEquals(0L, ProotRunner.toPid(0))
    }

    /** `Short` / `Byte` 同为 Number 子类，不能漏（否则又变回"只认某一种装箱类型"） */
    @Test
    fun acceptsBoxedShortAndByte() {
        assertEquals(7L, ProotRunner.toPid(7.toShort()))
        assertEquals(9L, ProotRunner.toPid(9.toByte()))
    }

    /** `null` → -1（反射返回 null 时不抛异常） */
    @Test
    fun returnsMinusOneForNull() {
        assertEquals(-1L, ProotRunner.toPid(null))
    }

    /** 非数字对象（如 `String`）→ -1，且**不得抛异常** */
    @Test
    fun returnsMinusOneForNonNumeric() {
        assertEquals(-1L, ProotRunner.toPid("1234"))
        assertEquals(-1L, ProotRunner.toPid(Any()))
    }

    /**
     * pid 取值边界：调用方是 `if (pid > 0) 写 dsh.pid else 打警告`，
     * 所以负数/0 必须原样透出（**不能被悄悄改成 1**，否则会写出假 pid 去杀别的进程）。
     */
    @Test
    fun passesThroughNonPositiveValues() {
        assertEquals(0L, ProotRunner.toPid(0L))
        assertEquals(-1L, ProotRunner.toPid(-1L))
    }

    /** 反射目标：一个"签名与 Android `Process.pid()` 一致"的 int 方法（见类注释） */
    class IntReturningPidMethod {
        fun pid(): Int = 1234
    }
}
