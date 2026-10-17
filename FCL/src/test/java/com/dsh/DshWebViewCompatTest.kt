package com.dsh

import com.dsh.ui.DshWebViewCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1b（BL-026）：WebView 版本检测纯函数回归。
 *
 * 只钉纯逻辑 [DshWebViewCompat.webViewMajorBelow]，不碰 Android：
 * `ensureSupported` 的弹框/跳转分支是 UI 行为，按项目惯例靠真机验证。
 *
 * ★ R16/fix4（BL-137）：阈值 116 → **119**（dsh 0.2.x 的 boot 脚本用
 * `Promise.withResolvers`，119+ 才有；116~118 选"仍要继续"后实测必崩，114 真机日志实锤）。
 * 本文件的用例随之整体上移一档。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.DshWebViewCompatTest"
 */
class DshWebViewCompatTest {

    /** 典型四段式：低于 119 → 拦截（含 116~118 这批"旧阈值放行、新研究证实必崩"的版本）。 */
    @Test
    fun belowThresholdFourPart() {
        assertTrue(DshWebViewCompat.webViewMajorBelow("95.0.4638.74"))
        assertTrue("114 真机实测崩溃版本", DshWebViewCompat.webViewMajorBelow("114.0.5.302"))
        assertTrue("116 已是旧阈值 —— 缺 withResolvers，必须拦", DshWebViewCompat.webViewMajorBelow("116.0.5845.163"))
        assertTrue("118 同样缺 withResolvers", DshWebViewCompat.webViewMajorBelow("118.0.5993.111"))
    }

    /** 等于阈值与高于阈值 → 放行。 */
    @Test
    fun atOrAboveThresholdPasses() {
        assertFalse(DshWebViewCompat.webViewMajorBelow("119.0.6045.66"))
        assertFalse(DshWebViewCompat.webViewMajorBelow("131.0.6778.81"))
    }

    /** 裸大版本（无小数点）也能判。 */
    @Test
    fun bareMajorWorks() {
        assertTrue(DshWebViewCompat.webViewMajorBelow("95"))
        assertTrue(DshWebViewCompat.webViewMajorBelow("118"))
        assertFalse(DshWebViewCompat.webViewMajorBelow("119"))
        assertFalse(DshWebViewCompat.webViewMajorBelow("125"))
    }

    /** 首尾空白不影响判定。 */
    @Test
    fun surroundingWhitespaceIgnored() {
        assertTrue(DshWebViewCompat.webViewMajorBelow("  95.0.4638.74  "))
        assertFalse(DshWebViewCompat.webViewMajorBelow("\t119.0.6045.66\n"))
    }

    /** 自定义阈值：同一版本在新阈值下翻转。 */
    @Test
    fun customThresholdRespected() {
        assertTrue(DshWebViewCompat.webViewMajorBelow("119.0.6045.66", threshold = 120))
        assertFalse(DshWebViewCompat.webViewMajorBelow("119.0.6045.66", threshold = 119))
    }

    /**
     * 解析不出的一律放行（不拦截），宁可放过别误伤：
     * null / 空 / 纯空白 / 非数字首段 / 空首段 / 负数。
     */
    @Test
    fun unparseableNeverBlocks() {
        assertFalse(DshWebViewCompat.webViewMajorBelow(null))
        assertFalse(DshWebViewCompat.webViewMajorBelow(""))
        assertFalse(DshWebViewCompat.webViewMajorBelow("   "))
        assertFalse(DshWebViewCompat.webViewMajorBelow("beta"))
        assertFalse(DshWebViewCompat.webViewMajorBelow("beta.1.2.3"))
        assertFalse(DshWebViewCompat.webViewMajorBelow(".0.1"))
        assertFalse(DshWebViewCompat.webViewMajorBelow("-1.2.3.4"))
        // 大到 Int 装不下也算解析不出 → 放行
        assertFalse(DshWebViewCompat.webViewMajorBelow("99999999999999999999.0.0.0"))
    }
}
