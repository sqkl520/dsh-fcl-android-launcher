package com.dsh

import com.dsh.core.DshRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * round46 / BL-226 + BL-239：两条**互相独立**但都在"实例生命周期边界"上的缺陷。
 *
 * ## BL-226 —— 删除的等待预算比停止链短 5 秒
 *
 * `DshInstances.delete` 删一个正在运行的实例时，先发停止、再轮询"进程真的离场了吗"，
 * 超时就硬删。那个轮询上限原来是独立的 `8_000L` 字面量，而它等的停止链
 * （`DshRuntime.stopAndWait`）最坏要 ≈12s —— 注释却写"与 stopAndWait 的预算一致"。
 *
 * 后果不是理论：8 秒一到就 `deleteRecursively()`，而 proot/node 还在写 `node_modules` ⇒
 * 300MB 级残留；下次冷启 `adoptOrphanInstanceDirs` 把残留目录收养成 NOT_INSTALLED 幽灵实例。
 *
 * 修法是把两个数字收成**一个** `DshRuntime.STOP_SETTLE_BUDGET_MS`，两边都引用它。
 *
 * ## BL-239 —— 自定义启动目录落在 guest 根之外时完全静默
 *
 * 真机实测（fix22）：用户把「启动目录」填成 `/` → 保存成功、`instances.json` 里
 * `"homeDir": "/"`、设置页照原样显示 `/`（`homeDir` 非空，连"（默认）"都不标）。
 * 运行期 `planHomeDirCreation` 判 `OutsideGuestRoot`，而调用点是
 * `HomeDirPlan.AlreadyExists, HomeDirPlan.OutsideGuestRoot -> Unit` ——
 * **不建目录、不告警、日志里一个字都没有**。事后翻日志无法诊断。
 *
 * UI 层现在会拦新输入的 `/`，但拦不住**已经存进去的旧值**（升级上来的实例、手改清单）。
 * 所以运行期必须留痕。
 *
 * ## 这组用例钉的是什么（以及为什么不测行为而是测形状）
 *
 * 两条修复的"行为"都发生在真机上的秒级时序里（进程收尾、`mkdirs` 分支），JVM 单测搭不出那个现场。
 * 能确定钉住的是**结构**：预算是同一个值、静默分支不再静默。
 * 这与本项目 BL-245 的教训同源 —— 「源码里有那个标识符 ≠ 它真的在起作用」，
 * 所以下面每条都要求**可执行代码**里出现，且能通过变异（改回错误实现）精确变红。
 */
class R46SettleBudgetAndHomeLogTest {

    // ===== BL-226：预算必须同源 =============================================

    /**
     * 删除侧的等待上限必须**等于**停止链的预算，且这个值要够长（≥12s 才能覆盖最坏收尾链）。
     *
     * 变异验证：把 `DshInstances` 里那行改回 `8_000L` ⇒ 本条变红（8_000 != 13_000）。
     */
    @Test
    fun deleteWaitBudgetEqualsStopSettleBudget() {
        assertEquals(
            "★ BL-226：删除等待必须与 DshRuntime 的停止预算同源（不是又一个字面量）",
            DshRuntime.STOP_SETTLE_BUDGET_MS,
            deleteWaitTimeoutMs()
        )
        assertTrue(
            "★ 停止收尾链最坏 ≈12s（2+2.5+2.5+5），预算至少要有 12s，否则删除会对着" +
                "仍在写盘的 proot 调 deleteRecursively()；实际=${deleteWaitTimeoutMs()}",
            deleteWaitTimeoutMs() >= 12_000L
        )
    }

    /**
     * `stopAndWait` 的默认参数必须是那个常量，不能退回裸字面量。
     *
     * 这条防的是"常量加了但没人用"—— 也就是把两处字面量换成第三处字面量。
     */
    @Test
    fun stopAndWaitDefaultUsesTheSharedConstant() {
        val body = runtimeSource()
        val header = Regex("suspend fun stopAndWait\\([^)]*\\)").find(body)
            ?: throw AssertionError("找不到 stopAndWait 的声明")
        assertTrue(
            "★ stopAndWait 的默认预算必须引用 STOP_SETTLE_BUDGET_MS，实际声明：${header.value}",
            header.value.contains("STOP_SETTLE_BUDGET_MS")
        )
        assertFalse(
            "★ 不许留下 13_000 裸字面量（改一个漏一个正是 BL-226 的成因）：${header.value}",
            Regex("=\\s*1[23]_000L").containsMatchIn(header.value)
        )
    }

    /**
     * 删除侧不许再出现**任何**以 `8_000` 为等待预算的写法。
     *
     * 用"可执行代码里 grep 8_000"而不是只看那一行：如果后人又在 delete 里加一个第二预算，
     * 上面两条（只查第一个声明）抓不到。
     */
    @Test
    fun deleteSourceHasNoStaleEightSecondBudget() {
        val code = stripComments(instancesSource())
        assertFalse(
            "★ DshInstances 的可执行代码里不该再有 8_000L（旧的 DELETE_WAIT 字面量）",
            Regex("\\b8_000L\\b").containsMatchIn(code)
        )
    }

    // ===== BL-239：OutsideGuestRoot 不许静默 =================================

    /**
     * `OutsideGuestRoot` 分支必须**留一行日志**，不能再和 `AlreadyExists` 合并成 `-> Unit`。
     *
     * 变异验证：把 `when` 里那段改回
     * `HomeDirPlan.AlreadyExists, HomeDirPlan.OutsideGuestRoot -> Unit` ⇒ 本条变红。
     */
    @Test
    fun outsideGuestRootBranchLogsInsteadOfSwallowing() {
        val code = stripComments(runtimeSource())

        // ① 合并分支是旧的静默实现，必须不存在
        assertFalse(
            "★ BL-239 复现：`AlreadyExists, OutsideGuestRoot -> Unit` 是那个静默分支，" +
                "必须拆开并留日志",
            Regex("AlreadyExists\\s*,\\s*HomeDirPlan\\.OutsideGuestRoot\\s*->\\s*Unit")
                .containsMatchIn(code)
        )

        // ② OutsideGuestRoot 必须有自己的分支，且分支体里有日志
        val branchAt = code.indexOf("HomeDirPlan.OutsideGuestRoot ->")
        assertTrue(
            "★ 必须有一个独立的 `is HomeDirPlan.OutsideGuestRoot ->` 分支",
            branchAt >= 0
        )
        // 分支体到下一个 HomeDirPlan. 分支为止
        val nextBranch = code.indexOf("HomeDirPlan.AlreadyExists ->", branchAt)
        assertTrue("前置：应能定位到紧随其后的 AlreadyExists 分支", nextBranch > branchAt)
        val branchBody = code.substring(branchAt, nextBranch)
        assertTrue(
            "★ OutsideGuestRoot 分支必须调用 DshLogBus.appendFor 留痕（只告警、不改行为），" +
                "实际分支体：$branchBody",
            branchBody.contains("DshLogBus.appendFor")
        )
    }

    /**
     * 那条日志必须能定位到"哪个实例、哪个路径" —— 否则用户在日志页看到了也对不上号。
     */
    @Test
    fun outsideGuestRootLogIsDiagnosable() {
        val code = stripComments(runtimeSource())
        val branchAt = code.indexOf("HomeDirPlan.OutsideGuestRoot ->")
        assertTrue("前置：必须存在独立的 OutsideGuestRoot 分支", branchAt >= 0)
        val nextBranch = code.indexOf("HomeDirPlan.AlreadyExists ->", branchAt)
        assertTrue(
            "前置：应能定位到紧随其后的 AlreadyExists 分支（分支体切片依赖它）",
            nextBranch > branchAt
        )
        val branchBody = code.substring(branchAt, nextBranch)

        assertTrue(
            "★ 日志要带 instance.id（归到该实例的日志页）",
            branchBody.contains("instance.id")
        )
        assertTrue(
            "★ 日志要带实际生效的 guest 路径，否则用户不知道是哪个值出了问题",
            branchBody.contains("homeGuest")
        )
    }

    // ===== 工具 ==============================================================

    /**
     * 读 [`DELETE_WAIT_TIMEOUT_MS`] 的**实际取值**。
     *
     * 它在 `DshInstances` 里是 `private const`，JVM 单测拿不到；但它被编译成字节码里的
     * 常量池条目，所以这里直接从源码解析表达式：若 RHS 是 `DshRuntime.STOP_SETTLE_BUDGET_MS`
     * 就返回那个运行期值，若是字面量就返回字面量。
     *
     * 为什么不用反射：`private const` 在 Kotlin 里是编译期内联，类上根本没有这个字段
     * （反射会 NoSuchFieldException），"读不到就当通过"会让这条测试永远绿。
     */
    private fun deleteWaitTimeoutMs(): Long {
        val m = Regex(
            "private const val DELETE_WAIT_TIMEOUT_MS\\s*=\\s*([A-Za-z0-9_.]+)"
        ).find(instancesSource())
            ?: throw AssertionError("找不到 DELETE_WAIT_TIMEOUT_MS 的声明")
        val rhs = m.groupValues[1]
        if (rhs == "DshRuntime.STOP_SETTLE_BUDGET_MS") return DshRuntime.STOP_SETTLE_BUDGET_MS
        return rhs.removeSuffix("L").replace("_", "").toLongOrNull()
            ?: throw AssertionError(
                "★ DELETE_WAIT_TIMEOUT_MS 的右值是 `$rhs`，既不是共享常量也不是可解析的字面量"
            )
    }

    private fun runtimeSource(): String = mainSourceFile("core/DshRuntime.kt").readText()

    private fun instancesSource(): String = mainSourceFile("core/DshInstances.kt").readText()

    /** 定位主源码文件（与项目内其他源码形状测试同口径） */
    private fun mainSourceFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/java/com/dsh/$rel"),
                File(dir, "FCL/src/main/java/com/dsh/$rel"),
                File(dir, "src/main/java/com/dsh/$rel")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("找不到 $rel（请在仓库根或 FCL/ 目录下运行测试）")
    }

    /**
     * 剥掉行注释与块注释，只留可执行代码。
     *
     * 必需：本类断言"某段代码里有没有某调用"，而这些标识符在 KDoc 里被成段讨论
     * （BL-226/BL-239 的说明本身就写着 `OutsideGuestRoot` 和 `8_000L`）。
     * 不剥的话"删掉日志、注释留着"的变异照样命中 ⇒ 用例假绿。
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
