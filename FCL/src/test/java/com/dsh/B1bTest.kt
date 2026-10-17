package com.dsh

import com.dsh.core.ProotRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * Round 15 批 B / B1b 的纯 JVM 单测。
 *
 * 覆盖 BL-045 的读循环内核 [ProotRunner.drainLines]：单行回调抛异常必须**吞掉该行、继续排空
 * 管道**（跳出循环会让 child 写满 stdout 憋住、onExit 永不到达 —— R12-A2-5）。真机路径
 * （`ProotRunner.start` 挂真进程）在 JVM 上无法复现（没有 proot、`android.jar` 是桩），
 * 但判定内核是纯函数：喂 `StringReader` + 会抛的回调即可精确钉住"后续行仍被读到"。
 * 一次性路径（BL-002 已修）语义同款，本组用例的期望对两条路径都成立。
 *
 * 未覆盖（任务单要求注明）：
 * - BL-047 的"清理段持锁/挪出"是**结构性**取舍（R15 判定为保留现状，论证见 [DshRuntime.start]
 *   的 BL-047 注释段），没有可抽的纯判据、也没有行为变化可钉；
 * - BL-046 改动全部是"补日志、不改控制流"，日志面由 `DshLogBus` 既有测试与真机日志门覆盖。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.B1bTest"（或仓库 run-tests.sh）。
 */
class B1bTest {

    /** 每行一条、以换行结尾的读流（`readLine` 语义：末行不带换行也能读出）。 */
    private fun readerOf(vararg lines: String): BufferedReader =
        BufferedReader(StringReader(lines.joinToString(separator = "\n", postfix = "\n")))

    @Test
    fun drainDeliversAllLinesInOrderWhenCallbackHealthy() {
        val seen = mutableListOf<String>()
        val failed = ProotRunner.drainLines(readerOf("alpha", "beta", "gamma")) { seen += it }
        assertEquals("健康回调：每一行按序送达", listOf("alpha", "beta", "gamma"), seen)
        assertFalse("没有任何回调抛异常 → 返回 false", failed)
    }

    @Test
    fun drainContinuesAfterSingleCallbackThrow() {
        // BL-045 的核心：中间一行炸了，后面的行必须照常送到（旧实现在这里会因异常跳出循环，
        // "three"/"four" 永远读不到、管道停止排空 → child 可能被写满 stdout 憋住）。
        val attempted = mutableListOf<String>()
        val failed = ProotRunner.drainLines(readerOf("one", "boom", "three", "four")) { line ->
            attempted += line
            if (line == "boom") throw IllegalStateException("callback blew up")
        }
        assertEquals(
            "抛异常的行之后的输出仍被读到（管道继续排空）",
            listOf("one", "boom", "three", "four"),
            attempted
        )
        assertTrue("发生过回调异常 → 返回 true（调用方据此补诊断行）", failed)
    }

    @Test
    fun drainSurvivesCallbackThrowingOnEveryLine() {
        val lines = listOf("l1", "l2", "l3", "l4", "l5")
        val attempted = mutableListOf<String>()
        val failed = ProotRunner.drainLines(readerOf(*lines.toTypedArray())) { line ->
            attempted += line
            throw IllegalStateException("always throws")
        }
        assertEquals("每行都抛：也不能提前跳出（全部行都要读到）", lines, attempted)
        assertTrue("每行都失败同样返回 true", failed)
    }

    @Test
    fun drainTreatsErrorLikeException() {
        // runCatching 吞的是 Throwable（与一次性路径 BL-002 同款）：回调抛 Error
        // （如断言错误）同样不许毁掉读循环。
        val attempted = mutableListOf<String>()
        val failed = ProotRunner.drainLines(readerOf("x", "y")) { line ->
            attempted += line
            throw AssertionError("not an Exception but an Error")
        }
        assertEquals("Error 也要吞掉并继续排空", listOf("x", "y"), attempted)
        assertTrue(failed)
    }

    @Test
    fun drainDeliversBlankLinesToo() {
        // 空行不能被当成"流结束"（readLine 返回 "" 而非 null）—— 与一次性路径同款语义。
        val seen = mutableListOf<String>()
        val failed = ProotRunner.drainLines(readerOf("head", "", "tail")) { seen += it }
        assertEquals(listOf("head", "", "tail"), seen)
        assertFalse(failed)
    }

    @Test
    fun drainOfEmptyStreamIsFalseAndDeliversNothing() {
        val seen = mutableListOf<String>()
        val failed = ProotRunner.drainLines(BufferedReader(StringReader(""))) { seen += it }
        assertTrue("空流：一行都没有", seen.isEmpty())
        assertFalse("空流没有回调异常 → false", failed)
    }
}
