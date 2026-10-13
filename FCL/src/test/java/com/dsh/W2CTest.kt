package com.dsh

import com.dsh.ui.shell.parsePortInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W2C（fix2）纯逻辑回归：BL-090 端口输入解析口径 [parsePortInput]。
 *
 * 只钉这一个函数 —— EditDialog 本身是 UI 类（FCLDialog 子类）不可测；
 * 端口行"空白=自动 / 非法提示"的判定内核全部收在 [parsePortInput] 里，这是本任务可测的全部。
 * "空白确认被 EditDialog 静默吞掉"（BL-090 病根）的修复在 EditDialog 的 allowEmpty 分支，
 * 是 UI 行为，靠真机验证（与项目惯例一致：弹窗分支不进 JVM 单测）。
 *
 * 函数刻意写成**顶层**（不走页面类）：单测 harness 无 androidx，经 [com.dsh.ui.shell.DshInstanceSettingPage]
 * 访问会连带加载 AppCompat 父类链直接崩（先例：isRowBusy / trimDeltaFor / joinTaskTitle）。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.W2CTest"
 *   （或仓库的 run-tests.sh，自动扫描本类）
 */
class W2CTest {

    /** 空串 / 纯空白 = 自动分配（约定值 0）——BL-090 修复后这条路径才真正可达。 */
    @Test
    fun blankMeansAutoPort() {
        assertEquals(0, parsePortInput(""))
        assertEquals(0, parsePortInput("   "))
        assertEquals(0, parsePortInput("\t\n"))
    }

    /** 显式写 0 与留空同义（dsh 侧 `--port 0` 由系统挑端口）。 */
    @Test
    fun explicitZeroEqualsAuto() {
        assertEquals(0, parsePortInput("0"))
        assertEquals(0, parsePortInput(" 0 "))
    }

    /** 常规端口与两端边界（0..65535 全闭区间）。 */
    @Test
    fun plainPortParsed() {
        assertEquals(3080, parsePortInput("3080"))
        assertEquals(1, parsePortInput("1"))
        assertEquals(65535, parsePortInput("65535"))
    }

    /** 首尾空白先裁再解析（与旧内联逻辑一致：trim 后 toIntOrNull）。 */
    @Test
    fun surroundingWhitespaceTrimmed() {
        assertEquals(8080, parsePortInput(" 8080 "))
        assertEquals(40233, parsePortInput("\t40233"))
    }

    /** 越界一律 null（调用方据此弹 dsh_port_invalid，不写配置）。 */
    @Test
    fun outOfRangeRejected() {
        assertNull(parsePortInput("65536"))
        assertNull(parsePortInput("-1"))
        assertNull(parsePortInput("70000"))
    }

    /** 非数字与溢出（超出 Int）一律 null —— toIntOrNull 的失败面全部收口到同一出口。 */
    @Test
    fun nonNumericAndOverflowRejected() {
        assertNull(parsePortInput("abc"))
        assertNull(parsePortInput("3080x"))
        assertNull(parsePortInput("30 80"))
        assertNull(parsePortInput("3.5"))
        assertNull(parsePortInput("99999999999"))
        assertNull(parsePortInput("2147483648"))
    }
}
