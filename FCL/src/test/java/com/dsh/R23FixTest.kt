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
 * R29 变更（[gateResumeAction] 签名与形状都变了）：
 * - 枚举新增第 4 个值 `DeferredPanel`，`copied` **重新参与**判定，但它只能把 ReloadGate
 *   降级成 DeferredPanel（= 必须摆出带出口的面板），而不能产出"什么都不做"。
 * - BL-197 的闸没有拆，只是换了形态：fix9 曾写成 `if (copied) return` —— 复制成功后什么都不做，
 *   用户停在整屏无控件的空白页。当时的修复是把 `copied` 从判定里彻底摘出去（`@Suppress("UNUSED_PARAMETER")`），
 *   于是它只进日志。但用户复制链接的**目的就是离开本页去浏览器**，复制完立刻再弹一次门禁，
 *   问的是他刚回答过的问题（真机日志 9 分钟弹 4 次，其中一次就是
 *   `23:53:17 [web] copy link finished (copied=true), gate resume action=ReloadGate`）。
 * - 真正的病根不是"copied 影响判定"，而是"copied 导致什么都不做"。新结构里 copied 只能产出
 *   `DeferredPanel`，"什么都不做"在结构上无法表达 —— 这就是为什么放宽 copied 是安全的。
 *   调用点的"必须真的摆面板"由源码级契约测试 `R29CopyDeferTest` 钉死。
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
     * ⇒ 必须摆出「链接已复制」面板（DeferredPanel），而不是静默重摆门禁。
     *
     * R29 改写：这条断言的方向变了（原先期望 ReloadGate），但 **BL-197 的闸没有拆**。
     * 当年的空白页来自 fix9 的 `if (copied) return` —— "什么都不做"；而 DeferredPanel
     * 同样是一个**必须摆出带出口面板**的动作，此刻 `loadedUrl=null`、状态面板已被 `showWeb()`
     * 收起、WebView 从未 loadUrl，任何"不摆面板"的路径都会回到整屏无控件。
     * 调用点是否真的实现了"摆面板"由源码级契约测试 `R29CopyDeferTest` 钉死。
     */
    @Test
    fun copiedDefersToPanel() {
        assertEquals(
            GateResume.DeferredPanel,
            gateResumeAction(copied = true, compatOk = false, finishing = false, canReload = true)
        )
    }

    /** 取消（copied=false）：没复制走，用户还在原地 ⇒ 回到门禁现场重新问。 */
    @Test
    fun cancelledReloadsGateToo() {
        assertEquals(
            GateResume.ReloadGate,
            gateResumeAction(copied = false, compatOk = false, finishing = false, canReload = true)
        )
    }

    /**
     * copied **只允许**把 ReloadGate 降级成 DeferredPanel，其它动作一律不得改变。
     *
     * R29 由 `copiedNeverChangesAction` 放宽而来：旧断言要求"copied 永不改变动作"，
     * 而 R29 故意让 copied 参与判定（消除"复制完再问一遍刚回答过的问题"）。放宽成允许集合断言后，
     * 仍能钉死两类回归：
     * - 把 copied 接成 Abort / StayOnPage（fix9 的 `if (copied) return` 等价于 StayOnPage）；
     * - 让 copied 去改写"实例已停/宿主销毁"这些由真实原因决定的分支。
     *
     * BL-197 的硬底线在第二条断言：`!compatOk` 时 copied **绝不许**产出 StayOnPage ——
     * 此刻 loadedUrl=null、面板已收、WebView 未加载 ⇒ 就是空白页。
     * （`compatOk=true` 时 StayOnPage 是正确结果：页面已放行，不该拿面板盖它。）
     */
    @Test
    fun copiedOnlyDowngradesReloadGateToDeferredPanel() {
        for (compatOk in listOf(false, true))
            for (finishing in listOf(false, true))
                for (canReload in listOf(false, true)) {
                    val without = gateResumeAction(false, compatOk, finishing, canReload)
                    val with = gateResumeAction(true, compatOk, finishing, canReload)
                    assertTrue(
                        "copied 只允许把 ReloadGate 降级成 DeferredPanel，实际 without=$without with=$with " +
                            "(compatOk=$compatOk finishing=$finishing canReload=$canReload)",
                        with == without || (without == GateResume.ReloadGate && with == GateResume.DeferredPanel)
                    )
                    // BL-197 的硬底线：copied 绝不许产出 StayOnPage —— 那是"什么都不做"的等价物
                    assertFalse(
                        "copied 产出了 StayOnPage：此刻 loadedUrl=null、面板已收、WebView 未加载 ⇒ 空白页",
                        with == GateResume.StayOnPage && !compatOk
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
     * 实例已停 / 宿主正在销毁时，`copied` 不许把"真实原因面板"换成"链接已复制"。
     *
     * 这两组里 DeferredPanel 是**错误归因**：用户看到"链接已复制"，而真实情况是后端实例没了
     * 或页面正在被销毁 —— 错误归因比没有文案更糟，他会对着一个不存在的页面找浏览器。
     * compatOk=true 时 StayOnPage 仍应压过 copied（页面已放行，不拿面板盖它）。
     */
    @Test
    fun copiedNeverDefersWhenSceneIsGone() {
        assertEquals(
            GateResume.Abort,
            gateResumeAction(copied = true, compatOk = false, finishing = false, canReload = false)
        )
        assertEquals(
            GateResume.Abort,
            gateResumeAction(copied = true, compatOk = false, finishing = true, canReload = true)
        )
        assertEquals(
            GateResume.StayOnPage,
            gateResumeAction(copied = true, compatOk = true, finishing = false, canReload = true)
        )
    }

    /**
     * 分支穷尽：全部 16 组参数都能落到四种动作之一，且四种动作在矩阵里都可达 ——
     * 少一种说明判定被合并/退化了（例如 Abort 被并回 ReloadGate，销毁态就会去碰 View）。
     *
     * 注意 `GateResume.values()` 是动态集合：R29 新增 DeferredPanel 后本测试无需改动即自动生效，
     * 同时升格为"新动作必须真的能到达"的守卫（哪个动作变成死分支，这里就红）。
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
