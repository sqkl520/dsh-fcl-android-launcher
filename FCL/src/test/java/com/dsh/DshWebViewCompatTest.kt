package com.dsh

import com.dsh.ui.DshWebViewCompat
import com.dsh.ui.urlSkeleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1b（BL-026）：WebView 版本检测纯函数回归。
 *
 * 只钉纯逻辑 [DshWebViewCompat.parseWebViewMajor] 与 [DshWebViewCompat.webViewMajorBelow]，
 * 不碰 Android：`ensureSupported` 的弹框/跳转分支是 UI 行为，按项目惯例靠真机验证。
 *
 * ★ R16/fix4（BL-137）：阈值 116 → **119**（dsh 0.2.x 的 boot 脚本用
 * `Promise.withResolvers`，119+ 才有；116~118 选"仍要继续"后实测必崩，114 真机日志实锤）。
 * 本文件的用例随之整体上移一档。
 *
 * ★ R22/fix9（BL-185）：解析口径从"取第一个 `.` 前的整段再 toIntOrNull"改成
 * **取第一段连续数字**（`parseWebViewMajor`），E3 实测算出的 8 类厂商非标串
 * （V114.x / chrome119.0 / 114abc.x / 下划线 / 逗号 / ZWSP 前缀 / Int 溢出 / 负数）
 * 由此从"误放行"改为"能判/保守判"。`webViewMajorBelow` 的**旧契约不变**：
 * 解析不出仍返回 false（未知≠低于），三态拦截在 `ensureSupported` 里（弹 unknown 档）。
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

    /** 标准四段式取第一段。 */
    @Test
    fun parseStandardFourPart() {
        assertEquals(114, DshWebViewCompat.parseWebViewMajor("114.0.5.302"))
        assertEquals(119, DshWebViewCompat.parseWebViewMajor("119.0.6045.66"))
        assertEquals(131, DshWebViewCompat.parseWebViewMajor("131.0.6778.81"))
        assertEquals(118, DshWebViewCompat.parseWebViewMajor("118.0.5993.111"))
    }

    /**
     * BL-185 / E3 ⓪.2 表里的误放行族：旧实现全部返回 null（→ 放行），新口径全部能读到数字。
     * 这些串的真实来源是厂商自编译/改名的 WebView（versionName 由 OEM 自行填写）。
     */
    @Test
    fun parseVendorPrefixedAndSeparatedForms() {
        assertEquals("厂商前缀 V", 114, DshWebViewCompat.parseWebViewMajor("V114.0.5.302"))
        assertEquals("chrome 前缀", 119, DshWebViewCompat.parseWebViewMajor("chrome119.0"))
        assertEquals("android- 前缀", 114, DshWebViewCompat.parseWebViewMajor("android-114.0.5.302"))
        assertEquals("WebView 前缀", 114, DshWebViewCompat.parseWebViewMajor("WebView 114.0.5.302"))
        assertEquals("M 前缀", 119, DshWebViewCompat.parseWebViewMajor("M119.0.6045"))
        assertEquals("数字带尾字母", 114, DshWebViewCompat.parseWebViewMajor("114abc.0.5.302"))
        assertEquals("数字带尾字母（短）", 114, DshWebViewCompat.parseWebViewMajor("114a"))
        assertEquals("下划线分隔", 114, DshWebViewCompat.parseWebViewMajor("114_0_5_302"))
        assertEquals("逗号分隔", 114, DshWebViewCompat.parseWebViewMajor("114,0,5,302"))
        assertEquals("分号构建号", 114, DshWebViewCompat.parseWebViewMajor("114.0.5.302;build7"))
    }

    /**
     * ZWSP（U+200B）/ BOM（U+FEFF）等不可见字符前缀：`String.trim()` 只裁空白，裁不掉它们；
     * 正则跳过后仍能取到数字。这里用 `Char(0x200B)` 现拼 —— 字面不可见字符在编辑器/补丁里
     * 极易被吃掉，转义写法（反斜杠 u200b）又有被各层工具二次解码的风险。
     */
    @Test
    fun parseZeroWidthPrefix() {
        val zwsp = Char(0x200B).toString()
        val bom = Char(0xFEFF).toString()
        assertEquals(114, DshWebViewCompat.parseWebViewMajor(zwsp + "114.0.5.302"))
        assertEquals(119, DshWebViewCompat.parseWebViewMajor(bom + "119.0.6045.66"))
        assertTrue(DshWebViewCompat.webViewMajorBelow(zwsp + "114.0.5.302"))
        assertEquals(119, DshWebViewCompat.parseWebViewMajor(zwsp + "119.0.6045.66"))
    }

    /** 全角数字先归一再解析（部分厂商版本串混用全角）。 */
    @Test
    fun parseFullWidthDigits() {
        assertEquals(114, DshWebViewCompat.parseWebViewMajor("１１４.0.5.302"))
        assertEquals(123, DshWebViewCompat.parseWebViewMajor("１２３.0.0"))
        assertTrue(DshWebViewCompat.webViewMajorBelow("１１４.0.5.302"))
        assertFalse(DshWebViewCompat.webViewMajorBelow("１２３.0.0"))
    }

    // ===== BL-183：外跳日志的 URL 骨架（绝不出现 token 值） =====

    /**
     * 骨架只保留 scheme://host:port + 有无 token；**任何情况下都不许出现 token 值**。
     * 这条断言是安全边界：日志会落盘/进日志页，漏一次就把会话凭据写进了可分享的文件。
     */
    @Test
    fun urlSkeletonNeverLeaksToken() {
        val url = "http://127.0.0.1:38501/?token=SECRET123"
        val skeleton = urlSkeleton(url)
        assertEquals("http://127.0.0.1:38501/ token=present", skeleton)
        assertFalse("骨架里绝不能出现 token 值", skeleton.contains("SECRET123"))
    }

    /** 裸 URL（无凭据）/ 深路径 / userinfo 的口径。 */
    @Test
    fun urlSkeletonShapes() {
        assertEquals("http://127.0.0.1:3080/ token=none", urlSkeleton("http://127.0.0.1:3080/"))
        // 深路径统一记根路径（dsh 入口就是 /，深路径是异常，骨架里标记成 / 更醒目）
        assertEquals(
            "http://127.0.0.1:3080/ token=present",
            urlSkeleton("http://127.0.0.1:3080/api/x?token=abc")
        )
        // userinfo 丢弃：`user:pass@` 也是凭据，不能进日志
        assertEquals("http://127.0.0.1:3080/ token=none", urlSkeleton("http://u:p@127.0.0.1:3080/"))
        // 非 URL / 空串 → 占位符，不抛
        assertEquals("(unparseable url)", urlSkeleton(""))
        assertEquals("(unparseable url)", urlSkeleton("   "))
        assertEquals("(unparseable url)", urlSkeleton("not a url"))
    }

    /**
     * 读不出 / 溢出 / 负数：
     * - null / 空 / 纯空白 / 无数字段 → parse 返回 null，`webViewMajorBelow` **仍返回 false**
     *   （旧契约：未知不判"低于"，免得把未知当成过旧去误拦/误弹 —— 真正的三态拦截在
     *   `ensureSupported`，未知走 `dsh_webview_compat_unknown_version` 档）；
     * - Int 溢出 → null（不猜）；
     * - 负数 `-1.2.3.4`：取第一段数字 `1` → 判"低于"（保守方向，不是误放行）。
     */
    @Test
    fun unparseableNeverBlocksButIsNotNull() {
        assertNull(DshWebViewCompat.parseWebViewMajor(null))
        assertNull(DshWebViewCompat.parseWebViewMajor(""))
        assertNull(DshWebViewCompat.parseWebViewMajor("   "))
        assertNull(DshWebViewCompat.parseWebViewMajor("beta"))
        assertNull(DshWebViewCompat.parseWebViewMajor("(unknown)"))

        assertFalse(DshWebViewCompat.webViewMajorBelow(null))
        assertFalse(DshWebViewCompat.webViewMajorBelow(""))
        assertFalse(DshWebViewCompat.webViewMajorBelow("   "))
        assertFalse(DshWebViewCompat.webViewMajorBelow("beta"))
        assertFalse(DshWebViewCompat.webViewMajorBelow("(unknown)"))
        // 大到 Int 装不下也算解析不出 → webViewMajorBelow 照旧放行（未知≠低于）
        assertNull(DshWebViewCompat.parseWebViewMajor("99999999999999999999.0.0.0"))
        assertFalse(DshWebViewCompat.webViewMajorBelow("99999999999999999999.0.0.0"))
    }

    /**
     * 负数：旧实现 `:88` 显式放行，是误放行族之一。新口径取第一段数字（`-1.2.3.4` → `1`）
     * → 判"低于"，方向保守（进旧版门禁框，用户仍有复制链接/外跳两出路）。
     */
    @Test
    fun negativeVersionsAreTreatedAsOld() {
        assertEquals(1, DshWebViewCompat.parseWebViewMajor("-1.2.3.4"))
        assertTrue(DshWebViewCompat.webViewMajorBelow("-1.2.3.4"))
        // `-2147483648.0` 的第一段数字是 2147483648（超出 Int）→ 读不出（null），
        // webViewMajorBelow 保持旧契约返回 false；ensureSupported 会走 unknown 档拦一次。
        assertNull(DshWebViewCompat.parseWebViewMajor("-2147483648.0"))
        assertFalse(DshWebViewCompat.webViewMajorBelow("-2147483648.0"))
    }

    /**
     * 带字母的串里夹杂数字：`beta.1.2.3` 会取到 `1`（保守判"低于"），`(unknown)` 无数字 → null。
     * 这两条钉住"取第一段连续数字"的边界语义，别让后来人改成"整串必须纯数字"。
     */
    @Test
    fun parseFirstDigitRunSemantics() {
        assertEquals(1, DshWebViewCompat.parseWebViewMajor("beta.1.2.3"))
        assertTrue(DshWebViewCompat.webViewMajorBelow("beta.1.2.3"))
        assertEquals(0, DshWebViewCompat.parseWebViewMajor(".0.1"))
        assertTrue(DshWebViewCompat.webViewMajorBelow(".0.1"))
        // 年份主版本号（某些自研内核）：解析"成功"但值极大 → 放行（口径局限，见 BL-185 报告）
        assertEquals(2024, DshWebViewCompat.parseWebViewMajor("2024.114.0"))
        assertFalse(DshWebViewCompat.webViewMajorBelow("2024.114.0"))
    }
}
