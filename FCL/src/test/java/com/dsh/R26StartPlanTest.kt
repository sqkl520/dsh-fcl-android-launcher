package com.dsh

import com.dsh.core.LaunchTarget
import com.dsh.core.StartDecision
import com.dsh.core.looksLikeSeccompTrouble
import com.dsh.core.planStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 26 / P1（DESIGN-A §1.4 + §1.5）的纯 JVM 单测。
 *
 * ## 为什么这组测试存在（而不是"注入假 starter 数 launchCount == 2"）
 * `startLocked` 第一步是 [com.dsh.core.ProotCommand.preflight]，它要求 proot 二进制、rootfs、
 * 脚本三样都在 —— JVM 单测里 `DshPaths` 的 `lateinit` 字段没有值、`FCLPath.NATIVE_LIB_DIR` 也拿不到
 * 真值，于是 **preflight 恒为 false，`ProotRunner.start` 一次都不会被调用**。所以"真 fork 两次"
 * 在当前结构下根本测不出来（DESIGN-A §1.1）。
 *
 * 退而求其次的**等价断言**是：把 fork 之前那串早退判据抽成纯函数 [planStart]，断言
 * "seccomp 重试现场返回 [StartDecision.Proceed]" —— 只要 `startLocked` 里 `Proceed -> Unit`
 * 的下一行就是 fork（结构约束，可 grep：`StartDecision.Proceed -> Unit` 紧接 `ProotRunner.start(`），
 * 那么"返回 Proceed"与"真的 fork 了"就是同一件事。
 *
 * ## A 组 [planStart]
 * 七个判定输入各一条，外加"预检失败 + 可复用"这条**顺序**断言 —— 顺序是契约的一部分：
 * 预检不过时不许因为"状态看着在跑"就返回 Reuse（那会把一个坏环境报成"启动成功"）。
 *
 * ## B 组 [looksLikeSeccompTrouble]
 * 钉的是**现状**，不是修 bug：`code > 128` 一刀切意味着 SIGTERM(143) 也会命中，而 143 正是
 * 用户点「停止」的退出码。这条依赖调用点先判 `wasStopRequested` 排除 —— 把隐形依赖写成测试。
 *
 * 纪律：全部是纯函数调用，没有 Context / 文件 / 全局状态，所以不需要 @Before/@After 复位。
 *
 * 运行：sh run-tests.sh，或 ./gradlew :FCL:testDebugUnitTest --tests "com.dsh.R26StartPlanTest"
 */
class R26StartPlanTest {

    // --- 工具 ---------------------------------------------------------------

    /** 四条本地化文案的哨兵值：断言"传进来的那一条被选中了"，而不是"某条非空字符串"。 */
    private val degradedMsg = "DEGRADED(expected,actual)"
    private val missingEntryMsg = "MISSING_ENTRY"
    private val notInstalledMsg = "NOT_INSTALLED"
    private val unreadableMsg = "UNREADABLE"

    /** [planStart] 的唯一调用形式：除被测维度外全部取"健康"值，让每条用例只翻一个开关。 */
    private fun plan(
        preflightOk: Boolean = true,
        preflightReason: String? = null,
        launchTarget: LaunchTarget = LaunchTarget.Instance(version = "0.2.1"),
        canJoinExisting: Boolean = false
    ): StartDecision = planStart(
        preflightOk = preflightOk,
        preflightReason = preflightReason,
        launchTarget = launchTarget,
        canJoinExisting = canJoinExisting,
        degradedMessage = degradedMsg,
        missingEntryMessage = missingEntryMsg,
        notInstalledMessage = notInstalledMsg,
        unreadableMessage = unreadableMsg
    )

    /** 断言"是 Refuse 且原因恰好是这条文案"（类型不对时给出可读的失败信息，不用 assertThrows）。 */
    private fun assertRefuses(expectedReason: String, decision: StartDecision, why: String) {
        assertTrue("$why —— 期望 Refuse，实际 $decision", decision is StartDecision.Refuse)
        assertEquals(why, expectedReason, (decision as StartDecision.Refuse).reason)
    }

    // ======================================================================
    // A 组：planStart（判定顺序 = 契约）
    // ======================================================================

    /**
     * A1：预检不过 ⇒ Refuse，且原因**原样透传** preflightReason。
     *
     * 为什么重要：这是 `startLocked` 的第一道闸。预检原因由 [com.dsh.core.ProotCommand.preflight]
     * 给出的人话，压缩/替换都会让用户看不到"到底缺什么"。
     */
    @Test
    fun preflightFailRefuses() {
        val d = plan(preflightOk = false, preflightReason = "no proot binary")
        assertRefuses("no proot binary", d, "预检失败必须原样透传 reason")
    }

    /**
     * A1b：预检失败但 reason 为 null ⇒ 落到字面量 "runtime not ready"。
     *
     * 为什么重要：旧代码是三层兜底（pre.reason → 本地化文案 → 字面量）。前两层在调用点取，
     * 纯函数这层的字面量是最后一层 —— 少了它 `NotReady(null)` 会让界面显示空原因。
     */
    @Test
    fun preflightFailWithoutReasonFallsBackToLiteral() {
        assertRefuses("runtime not ready", plan(preflightOk = false), "reason 为空时必须有字面量兜底")
    }

    /**
     * A2：Degraded ⇒ Refuse，且用的是**带两个版本号**的那条文案。
     *
     * 为什么重要：C-07 的现场是"用户要 0.2.1、实际会跑预装 0.1.6"。旧实现静默放行，
     * 用户只看到"起来了"。这条断言保证拒绝路径一定拿到 degradedMessage（不是 missingEntry 等）。
     */
    @Test
    fun degradedRefusesWithVersionMessage() {
        val d = plan(
            launchTarget = LaunchTarget.Degraded(
                expected = "0.2.1", actual = "0.1.6", preinstalled = true
            )
        )
        assertRefuses(degradedMsg, d, "Degraded 必须用带两个版本号的文案拒绝")
    }

    /**
     * A3：MissingEntry(hasPackageJson = true) ⇒ Refuse(missingEntryMessage)（"装了一半，请重装"）。
     */
    @Test
    fun missingEntryWithPackageJsonUsesReinstallMessage() {
        val d = plan(launchTarget = LaunchTarget.MissingEntry(hasPackageJson = true))
        assertRefuses(missingEntryMsg, d, "有 package.json 缺入口 = 半棵树 ⇒ 提示重装")
    }

    /**
     * A4：MissingEntry(hasPackageJson = false) ⇒ Refuse(notInstalledMessage)（"还没装"）。
     *
     * 为什么两条要分开：用户该做的事不同（重装 vs 先装）。合成一条会让"从没装过"的人
     * 去点一个不存在的"重装"。
     */
    @Test
    fun missingEntryWithoutPackageJsonUsesInstallMessage() {
        val d = plan(launchTarget = LaunchTarget.MissingEntry(hasPackageJson = false))
        assertRefuses(notInstalledMsg, d, "连 package.json 都没有 = 未安装 ⇒ 提示先安装")
    }

    /**
     * A5：Unreadable ⇒ Refuse(unreadableMessage)，**不得**复用"请重装"。
     *
     * 为什么重要（R2b-A1-1）：磁盘一时读不出来是可恢复故障（EIO / SELinux / 文件正被替换），
     * 建议重装等于让用户重下 500MB 去修一个 5 秒后就自愈的问题。
     */
    @Test
    fun unreadableRefuses() {
        val d = plan(launchTarget = LaunchTarget.Unreadable(preinstalled = false))
        assertRefuses(unreadableMsg, d, "读不出来必须给\"稍后重试\"的文案，不能建议重装")
    }

    /**
     * A6（本组最关键）：健康实例 + 已有活 run ⇒ Reuse（**不是** Proceed，即不 fork）。
     *
     * 为什么重要：Reuse 若被压成 Proceed，同实例连点两次启动就会起两个 proot（端口冲突 + 孤儿）；
     * 若被压成 Refuse，正常的"已在运行"会被报成失败。
     */
    @Test
    fun canJoinReusesWithoutForking() {
        val d = plan(canJoinExisting = true)
        assertEquals("可复用现有 run 时必须返回 Reuse（不 fork）", StartDecision.Reuse, d)
        assertFalse("Reuse 绝不能被当成 Proceed", d is StartDecision.Proceed)
    }

    /**
     * A7：健康实例 + 无活 run ⇒ Proceed（= 调用点紧接着 fork）。
     *
     * 这是"seccomp 重试必须真 fork"的核心断言：重试现场状态仍是 `Starting(A)` 但 handle 已被
     * `onProcessExit` 清空 ⇒ [com.dsh.core.canJoinExistingRun] 为 false ⇒ 必须 Proceed。
     */
    @Test
    fun healthyProceedsToFork() {
        val d = plan(canJoinExisting = false)
        assertEquals("健康且无可复用 run 必须返回 Proceed（调用点其后就是 fork）", StartDecision.Proceed, d)
    }

    /**
     * A8：预检失败 **且** canJoin = true ⇒ 必须 Refuse，不是 Reuse。
     *
     * 为什么重要：这是判定**顺序**的守卫。预检不过意味着环境根本不能用；此时若先看"可复用"
     * 就返回 Started，用户会看到一个假的"启动成功"，而实际什么都没起来。
     * （现码顺序：preflight → launchTarget 三段 → canJoin，见 [planStart] 文档第 1~6 条。）
     */
    @Test
    fun refuseBeatsReuseWhenBothTrue() {
        val d = plan(preflightOk = false, preflightReason = "rootfs missing", canJoinExisting = true)
        assertRefuses("rootfs missing", d, "预检失败必须压过\"可复用\"（顺序契约）")
        assertFalse("预检失败时绝不能落到 Reuse", d is StartDecision.Reuse)
    }

    /**
     * A9：launchTarget 不可信 **且** canJoin = true ⇒ 仍必须 Refuse。
     *
     * 与 A8 同理但针对另一条边：现码里 `canJoinExistingRunLocked` 在两段 launchTarget 判定
     * **之后**。若顺序被调换，"包已经没了但状态还停在 Running"的实例会静默复用死进程。
     */
    @Test
    fun launchTargetRefusalBeatsReuse() {
        val d = plan(
            launchTarget = LaunchTarget.MissingEntry(hasPackageJson = false),
            canJoinExisting = true
        )
        assertRefuses(notInstalledMsg, d, "启动目标不可信时必须压过\"可复用\"（顺序契约）")
    }

    // ======================================================================
    // B 组：looksLikeSeccompTrouble（钉现状，不改行为）
    // ======================================================================

    /**
     * B1：code = 137（SIGKILL ⇒ 128+9）⇒ true。
     * 这是 `code > 128` 一刀切最基础的一支：被信号杀死就可能是不兼容信号。
     */
    @Test
    fun sigkillLooksLikeSeccompTrouble() {
        assertTrue("137(SIGKILL) 必须命中（code > 128）", looksLikeSeccompTrouble(137, emptyList()))
    }

    /**
     * B2：code = 139（SIGSEGV ⇒ 128+11）⇒ true。
     * 真机上 proot 被内核 SIGSEGV 杀掉是 seccomp/ptrace 不兼容的典型现场。
     */
    @Test
    fun sigsegvLooksLikeSeccompTrouble() {
        assertTrue("139(SIGSEGV) 必须命中 —— 这是真机出现过的 seccomp 现场", looksLikeSeccompTrouble(139, emptyList()))
    }

    /**
     * B3（**已知脆弱点**）：code = 143（SIGTERM ⇒ 128+15）⇒ **true**。
     *
     * 143 正是用户点「停止」时 proot 的退出码。本函数判 true 本身没错（它拿不到"这次退出是不
     * 是用户要求的"这个事实），**正确性完全依赖调用点**：`onProcessExit` 先用 `wasStopRequested`
     * 排除主动停止，才轮到 [com.dsh.core.DshRuntime.retryIfSeccompLooksGuilty]。
     *
     * 这条测试的用途是**把这个隐形依赖钉住**：谁把重试挪出 `Starting` 分支、或加了新的调用点
     * 却没排除 143，用户点停止就会看到进程自动重启 —— 而 `launchCount == 2` 那类断言不会红。
     * 如果将来有人"顺手"把 143 改成 false，本条会红：那是**行为变更**，必须先改调用点与文档。
     */
    @Test
    fun sigtermAlsoMatches_trapIsMovedToCallSite() {
        assertTrue(
            "143(SIGTERM) 目前命中 —— 这是已知脆弱点，由调用点先判 wasStopRequested 排除；" +
                "改这里等于改行为，必须同步改调用点",
            looksLikeSeccompTrouble(143, emptyList())
        )
    }

    /** B4：code = 1（普通失败，非信号）+ tail 含 "seccomp" ⇒ true（关键词这一支）。 */
    @Test
    fun keywordSeccompInTail() {
        assertTrue(
            "退出码不高但日志里有 seccomp ⇒ 必须命中（关键词判据）",
            looksLikeSeccompTrouble(1, listOf("[proot] seccomp acceleration disabled"))
        )
    }

    /**
     * B5：code = 1 + tail 含 "Operation Not Permitted"（**大小写混合**）⇒ true。
     *
     * 验证 `lowercase()` 真的生效：内核/工具链这条文案的大小写不统一（"Operation not permitted" /
     * "OPERATION NOT PERMITTED" 都出现过），少了下转换就会漏判。
     */
    @Test
    fun keywordIsCaseInsensitive() {
        assertTrue(
            "关键词判据必须大小写无关（实现里有 lowercase()）",
            looksLikeSeccompTrouble(1, listOf("Operation Not Permitted"))
        )
    }

    /** B6：code = 1 + tail 与 seccomp/ptrace 无关 ⇒ false（不许把所有失败都当 seccomp）。 */
    @Test
    fun unrelatedTailAndLowCodeIsFalse() {
        assertFalse(
            "普通业务失败（EADDRINUSE）不能被当成 seccomp 问题 —— 否则会白白重试一次",
            looksLikeSeccompTrouble(1, listOf("Error: listen EADDRINUSE: address already in use"))
        )
    }

    /** B7：code = 0 + 空 tail ⇒ false（正常退出绝不重试 —— 否则"起来又正常退出"会无限重试）。 */
    @Test
    fun cleanExitIsFalse() {
        assertFalse("正常退出（code=0）必须 false", looksLikeSeccompTrouble(0, emptyList()))
    }
}
