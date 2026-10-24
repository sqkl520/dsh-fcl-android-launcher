package com.dsh

import com.dsh.core.scriptsVersionSame
import com.dsh.ui.GateResume
import com.dsh.ui.gateResumeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R23 / fix10 —— 脚本版本比较（BL-214，W3-C）与门禁恢复动作（BL-197，W3-B）的纯函数单测。
 *
 * 为什么是这两个：它们是本轮唯一"错误会导致用户可见严重后果"的判定内核。
 * - [scriptsVersionSame]：判错的后果是**每次启动都进准备页重解压**（误报过期），
 *   或者**升级永不生效**（漏报过期，正是 BL-214 的原始病）。
 * - [gateResumeAction]：判错的后果就是 BL-197 —— 复制成功后留在整屏无控件的空白页。
 *
 * 两者都写成顶层纯函数（理由同 `trimDeltaFor`）：本 harness 的 classpath 没有 androidx，
 * 经 Activity/object 访问会拉起父类链直接崩。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.R23FixTest"
 */
class R23FixTest {

    // ===== scriptsVersionSame（BL-214） =====

    @Test
    fun identicalVersionsAreSame() {
        assertTrue(scriptsVersionSame("10", "10"))
        assertTrue(scriptsVersionSame("abc", "abc"))
        // 注意：**比较函数本身不 trim** —— trim 由调用方（scriptsOutdated / scriptsVersionPair）
        // 在读文件后做。这里钉住这个边界，防止有人误以为函数会自己容错：
        assertFalse(scriptsVersionSame("10\n", "10"))
    }

    @Test
    fun numericVersionsCompareByValue() {
        // 历史兼容口径：前导零不算不同版本（曾手改过 version 文件）
        assertTrue(scriptsVersionSame("007", "7"))
        assertTrue(scriptsVersionSame("09", "9"))
    }

    @Test
    fun differentVersionsAreDifferent() {
        assertFalse(scriptsVersionSame("9", "10"))              // BL-214 的原始场景
        assertFalse(scriptsVersionSame("10", "11"))
        assertFalse(scriptsVersionSame("v1", "1"))              // 一边非数字 → 只能字面比
    }

    @Test
    fun nonNumericNeverEqualsNumeric() {
        assertFalse(scriptsVersionSame("abc", "10"))
        assertFalse(scriptsVersionSame("", "10"))
        assertFalse(scriptsVersionSame("10", ""))
    }

    /**
     * 溢出边界：超长数字串 `parseLong` 抛 NumberFormatException，必须**安全降级为"不同"**
     * （返回 false = 认为过期 = 多解压一次幂等脚本，而不是崩在启动链上）。
     */
    @Test
    fun overflowFallsBackToDifferent() {
        val huge = "9".repeat(30)
        assertFalse(scriptsVersionSame(huge, "10"))
        assertTrue(scriptsVersionSame(huge, huge))              // 字面相等仍成立
    }

    // ===== gateResumeAction（BL-197，W3-B） =====

    /**
     * **最重要的一条**：复制成功（copied=true）、门禁未放行、宿主健在、还能加载
     * ⇒ 必须重摆门禁（ReloadGate）。
     *
     * fix9 在这里返回的是"什么都不做"，而此刻 `loadedUrl=null`、状态面板已被 `showWeb()`
     * 收起、WebView 从未 loadUrl —— 屏上没有任何可点对象。这条断言就是 BL-197 的回归闸。
     */
    @Test
    fun copiedStillReloadsGate() {
        assertEquals(
            GateResume.ReloadGate,
            gateResumeAction(copied = true, compatOk = false, finishing = false, canReload = true)
        )
    }

    /** 取消（copied=false）与复制成功走**同一条**：回到可决策现场。 */
    @Test
    fun cancelledReloadsGateToo() {
        assertEquals(
            GateResume.ReloadGate,
            gateResumeAction(copied = false, compatOk = false, finishing = false, canReload = true)
        )
    }

    /**
     * copied 只能影响日志口径，**不得改变动作**。
     *
     * 四组参数两两配对，把"把 copied 接进判定"的任何改动都钉红 ——
     * fix9 的错误形态正是"copied 单独分叉出一支 return"。
     */
    @Test
    fun copiedNeverChangesAction() {
        val matrix = listOf(
            Triple(true, true, false),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, false),
            Triple(true, true, true),
            Triple(false, false, true),
        )
        for ((compatOk, canReload, finishing) in matrix) {
            assertEquals(
                "copied 改变了动作：compatOk=$compatOk canReload=$canReload finishing=$finishing",
                gateResumeAction(true, compatOk, finishing, canReload),
                gateResumeAction(false, compatOk, finishing, canReload),
            )
        }
    }

    /** 已放行过门禁（compatOk）：页面在或即将在，不再干预（避免白弹一次门禁）。 */
    @Test
    fun alreadyApprovedStaysOnPage() {
        assertEquals(
            GateResume.StayOnPage,
            gateResumeAction(copied = false, compatOk = true, finishing = false, canReload = true)
        )
        assertEquals(
            GateResume.StayOnPage,
            gateResumeAction(copied = true, compatOk = true, finishing = false, canReload = true)
        )
    }

    /**
     * 宿主正在 finish/销毁：绝不能碰 View（会崩），交给调用方的"不碰 View"兜底。
     * 优先级压过 canReload —— 实例还在跑也不能在这个时刻重摆框。
     */
    @Test
    fun finishingAbortsEvenIfReloadable() {
        assertEquals(
            GateResume.Abort,
            gateResumeAction(copied = false, compatOk = false, finishing = true, canReload = true)
        )
        assertEquals(
            GateResume.Abort,
            gateResumeAction(copied = true, compatOk = false, finishing = true, canReload = true)
        )
    }

    /**
     * 实例已停/退出（拿不到可加载 URL）：重摆门禁没有意义 ⇒ Abort，
     * 由调用方摆出带「重试/日志/停止/关闭」的面板 —— 同样不允许留在空白页。
     */
    @Test
    fun noReloadableUrlAborts() {
        assertEquals(
            GateResume.Abort,
            gateResumeAction(copied = false, compatOk = false, finishing = false, canReload = false)
        )
        assertEquals(
            GateResume.Abort,
            gateResumeAction(copied = true, compatOk = false, finishing = false, canReload = false)
        )
    }

    /**
     * 分支穷尽：全部 16 组参数都能落到三种动作之一，且三种动作在矩阵里都可达 ——
     * 少一种说明判定被合并/退化了（例如 Abort 被并回 ReloadGate，销毁态就会去碰 View）。
     */
    @Test
    fun actionIsAlwaysDefined() {
        val actions = mutableSetOf<GateResume>()
        for (copied in listOf(false, true)) {
            for (compatOk in listOf(false, true)) {
                for (finishing in listOf(false, true)) {
                    for (canReload in listOf(false, true)) {
                        actions += gateResumeAction(copied, compatOk, finishing, canReload)
                    }
                }
            }
        }
        assertEquals(GateResume.values().toSet(), actions)
    }
}
