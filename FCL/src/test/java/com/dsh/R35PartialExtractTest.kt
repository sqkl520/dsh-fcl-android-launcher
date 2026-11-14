package com.dsh

import com.dsh.core.DshPaths
import com.dsh.core.ExtractVerdict
import com.dsh.core.extractVerdict
import com.dsh.core.shouldDropBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R35 / RE-04：rootfs 解压**部分失败被当成成功**（并顺手删掉可用备份）。
 *
 * ## 缺陷本体
 *
 * `RuntimeUtils.uncompressTarXZ` 从 round31 起就返回一轮的子项失败汇总
 * （`ExtractReport`：符号链接没建出来 / 可执行位没还原 / 硬链接既建不成也复制不成），
 * 但 `DshBootstrap.extractRootfs` —— 它**唯一**的生产调用点 —— 一直丢弃返回值。
 * 于是唯一的门是 `looksUsable`：只查 `bin/sh`、`usr/bin/env`、`bin/busybox` 三个路径存在性。
 * 一棵「`bin/sh` 在、1049 条符号链接一条都没建出来」的树照样判"解压成功"，随后
 * `:720` 把**可用的旧备份删掉** —— 回滚路径永久消失，用户只剩重装一条路。
 *
 * ## 为什么改的是"新增判据"而不是加强 `looksUsable`
 *
 * `looksUsable` 有**三个**调用点（解压后布局门 / 删备份门 / `hoistSingleTopLevelDir` 里判断
 * tar 内层目录"像不像 rootfs"）。动它会连带改变 hoist 的语义 —— 那是没人预期的副作用。
 * 所以本轮的判据内核是 [extractVerdict] / [shouldDropBackup]：**与布局门并列**的新判据，
 * `looksUsable` 一个字不动（有专门的形状守卫钉住这一点）。
 *
 * ## 可测性（为什么是"纯函数 + 源码切片"而不是端到端）
 *
 * `extractRootfs` 是 private，且要 `Context` / `AssetManager` / `DshPaths` 的 lateinit 路径
 * （末尾还 `DshPaths.loadPaths(context)`）。在 JVM harness 上端到端驱动它要造一整套 Android 桩
 * 加一个 142MB 的 tar.xz，成本远高于判别力收益（`R31Fix204Test` 为驱动一个 `RuntimeUtils`
 * 函数付过这份代价）。所以拆成两半各自钉死：
 *
 * - **判据内核**（[extractVerdict] / [shouldDropBackup]）→ 真值表 + 真实 `ExtractReport` 反射驱动；
 * - **接线**（时序/去留）→ 源码切片断言，口径同 `R31Fix211Test.bootstrapClearsOwnerBeforeReleasingGuard`。
 *
 * 两条合起来才拦得住缺陷：只测纯函数时，把生产代码的接线改回"只看 looksUsable"仍然全绿；
 * 只切源码时，判据本身写错（例如把硬链接退化算进失败）也看不出来。
 *
 * 运行：`sh run-tests.sh`
 */
class R35PartialExtractTest {

    // =====================================================================
    // 1. 判据内核：extractVerdict 真值表
    // =====================================================================

    /**
     * 四格真值表。**第 2 行是本缺陷的核心**：布局门为 true（`bin/sh` 等 3 个路径都在）
     * 但这一轮有子项失败时，结论必须是 [ExtractVerdict.PARTIAL_FAILURES]，
     * 不能因为"树看起来像 rootfs"就放行。
     */
    @Test
    fun verdictTable() {
        assertEquals(
            "全成功 + 布局可用 ⇒ CLEAN（对照：正常装机不许被误判）",
            ExtractVerdict.CLEAN,
            extractVerdict(hasFailures = false, layoutUsable = true)
        )
        assertEquals(
            "★ 有子项失败 + 布局可用 ⇒ PARTIAL_FAILURES —— 3 个路径在 ≠ 解压成功（RE-04 本体）",
            ExtractVerdict.PARTIAL_FAILURES,
            extractVerdict(hasFailures = true, layoutUsable = true)
        )
        assertEquals(
            "无失败 + 布局不可用 ⇒ LAYOUT_BAD（原布局门的语义不变）",
            ExtractVerdict.LAYOUT_BAD,
            extractVerdict(hasFailures = false, layoutUsable = false)
        )
        assertEquals(
            "两者都坏 ⇒ PARTIAL_FAILURES（describe() 带首个失败条目名与异常，信息量比「找不到 bin/sh」更大）",
            ExtractVerdict.PARTIAL_FAILURES,
            extractVerdict(hasFailures = true, layoutUsable = false)
        )
    }

    /**
     * **真实的 `ExtractReport`**（反射造，不 mock）：一条符号链接失败 ⇒ `hasFailures()` 为真
     * ⇒ 判据必须给出 [ExtractVerdict.PARTIAL_FAILURES]。
     *
     * 这条把"报告 → 判据"的接线钉住：判据不是自己造布尔，而是取 `ExtractReport.hasFailures()`。
     * 同时验证 `describe()` 里带着**条目名与异常**（用户/排障唯一能定位问题的信息）。
     */
    @Test
    fun reportWithLinkFailureIsJudgedIncompleteEvenWhenLayoutLooksFine() {
        val report = newExtractReport()
        recordFailure(report, "recordLinkFailure", "symlink bad-link (RuntimeException: Stub!)")

        assertTrue("ExtractReport 必须把这条判成有失败", hasFailures(report))
        assertEquals(
            "★ 有失败项的报告必须判为「解压不完整」，即使布局门（looksUsable）会判 true",
            ExtractVerdict.PARTIAL_FAILURES,
            extractVerdict(hasFailures = hasFailures(report), layoutUsable = true)
        )
        val desc = describe(report)
        assertTrue("describe() 必须点名失败条目（实际：<$desc>）", desc.contains("bad-link"))
        assertTrue("describe() 必须给出失败计数（实际：<$desc>）", desc.contains("links=1"))
    }

    /**
     * 全部成功的报告 ⇒ `hasFailures() == false` ⇒ [ExtractVerdict.CLEAN]。
     *
     * 这是**对照**：如果只看"失败能被发现"，一个永远返回失败的实现也能过 ——
     * 而那样的实现在每次正常首装时都会把解压打断。
     */
    @Test
    fun cleanReportIsJudgedComplete() {
        val report = newExtractReport()
        assertFalse("新建的报告必须无失败", hasFailures(report))
        assertNull("无失败时首个失败摘要必须为 null", firstFailure(report))
        assertEquals(
            "干净报告 + 布局可用 ⇒ CLEAN（否则正常装机必被打断）",
            ExtractVerdict.CLEAN,
            extractVerdict(hasFailures = hasFailures(report), layoutUsable = true)
        )
    }

    /**
     * **硬链接退化不算失败**（`ExtractReport` 的刻意分级）。
     *
     * 这条专门钉住"修过头"：把 `getDegradedHardLinks() > 0` 或
     * `describe() != "all entries applied"` 当失败判据，会在不支持硬链接的文件系统上
     * 造成整轮假失败 —— 而那条路径的内容是**正确**的（只是丢了 inode 共享）。
     */
    @Test
    fun degradedHardLinkIsVisibleButStillComplete() {
        val report = newExtractReport()
        recordFailure(report, "recordHardLinkDegraded", "hard link hard.txt degraded to copy, inode sharing lost")

        assertEquals("退化不得计入失败", 0, failureCount(report))
        assertFalse("退化不得让 hasFailures() 为真", hasFailures(report))
        assertTrue(
            "退化必须能单独看见（否则「改一处不同步另一处」这条真实差异就被抹掉了）",
            describe(report).contains("degraded")
        )
        assertEquals(
            "★ 只有退化 ⇒ CLEAN。若判成 PARTIAL_FAILURES，不支持硬链接的 fs 上正常装机全红",
            ExtractVerdict.CLEAN,
            extractVerdict(hasFailures = hasFailures(report), layoutUsable = true)
        )
    }

    // =====================================================================
    // 2. 删备份的判据：部分失败时必须保留备份（缺陷本体的核心）
    // =====================================================================

    /**
     * ★ **核心断言**：新树"布局可用但解压不完整"时**不许删备份**。
     *
     * 修复前删备份的条件只有 `looksUsable(destDir)` —— 于是"`bin/sh` 在、符号链接一大片没建出来"
     * 的树会把可用的旧备份删掉，回滚路径永久消失。这条直接钉住"不删"。
     */
    @Test
    fun partialExtractMustNotDropBackup() {
        assertFalse(
            "★ 解压不完整时**必须保留** .old —— 否则回滚路径永久消失，用户只剩重装",
            shouldDropBackup(extractComplete = false, newTreeUsable = true)
        )
    }

    /** 完整 + 可用 ⇒ 删（对照：正常路径不能变成"备份永不删除"）。 */
    @Test
    fun completeExtractDropsBackup() {
        assertTrue(
            "完整且可用 ⇒ 必须删备份（否则每次升级都多留一份 300MB+ 的 rootfs）",
            shouldDropBackup(extractComplete = true, newTreeUsable = true)
        )
    }

    /** 完整但替换后布局坏了（搬运搬坏）⇒ 不删，交给回滚分支（原语义保留）。 */
    @Test
    fun unusableNewTreeKeepsBackupForRollback() {
        assertFalse(
            "新树布局不可用 ⇒ 保留备份走回滚（原 :721 的语义不得丢）",
            shouldDropBackup(extractComplete = true, newTreeUsable = false)
        )
        assertFalse(
            "两条都不成立时更不许删",
            shouldDropBackup(extractComplete = false, newTreeUsable = false)
        )
    }

    /**
     * "备份永不删除、磁盘被两份 rootfs 吃满"——反证：**只有两条都成立才删**，
     * 而"解压不完整"的情况今天根本走不到删备份那一步（解压失败在动 dest 之前就抛了）。
     *
     * 这条是给 [shouldDropBackup] 的**完备性**断言：它的输入域里没有任何一格会导致
     * "两个 300MB 目录长期并存"且用户无法自愈。具体见报告 §backup_never_deleted_risk。
     */
    @Test
    fun backupDecisionIsTotalAndNeverLeavesBothCopiesOnTheSuccessPath() {
        // 正常路径（CLEAN）唯一会走到删备份分支的输入：extractComplete 恒为 true。
        assertTrue(shouldDropBackup(true, true))
        // 其余所有输入都不删 —— 但它们对应的都是"本轮不算成功"，备份要么被回滚消耗，
        // 要么在下一轮开头被 `if (backupDir.exists()) backupDir.deleteRecursively()` 清掉。
        val nonSuccess = listOf(false to true, true to false, false to false)
        nonSuccess.forEach { (complete, usable) ->
            assertFalse(
                "非成功组合($complete,$usable) 不得删备份",
                shouldDropBackup(complete, usable)
            )
        }
    }

    // =====================================================================
    // 3. 不变式：手工放置的 rootfs 必须继续可用
    // =====================================================================

    /**
     * 手工放置的 rootfs（没有 version 文件、没有 assets 里那份目录结构）必须仍被判为可用。
     *
     * 依据：`docs/reports/round31/round31-handoff-index.md` 明文记录"给 rootfsLooksUsable 加
     * version 检查会让手工 rootfs 从可用变不可用"。本轮**没有**碰这个判据
     * （`DshPaths.rootfsLooksUsable` / `ProotCommand.preflight` 零改动，`DshBootstrap.looksUsable`
     * 的 3 路径也零改动），这条用行为把它钉住：一棵只有 `bin/sh` 的裸树（无 version）判 true。
     */
    @Test
    fun manuallyPlacedRootfsWithoutVersionIsStillUsable() {
        val root = Files.createTempDirectory("r35-04-manual").toFile()
        try {
            // 手工 rootfs 的最小形态：有 bin/sh，没有 version、没有 usr/bin/env
            File(root, "bin").mkdirs()
            File(root, "bin/sh").writeText("#!/bin/sh\n")
            assertFalse("前提：这棵手工树确实没有 version 文件", File(root, "version").exists())

            assertTrue(
                "★ 只有 bin/sh、没有 version 的手工 rootfs 必须仍判为可用 —— " +
                    "给它加 version 检查会让存量手工底座从可用变不可用（round31 的既有结论）",
                DshPaths.rootfsLooksUsable(root)
            )

            // usr/bin/env 单独存在也算（3 路径判据是"或"，不是"与"）
            val alt = Files.createTempDirectory("r35-04-manual-env").toFile()
            try {
                File(alt, "usr/bin").mkdirs()
                File(alt, "usr/bin/env").writeText("\u0000")
                assertTrue("只有 usr/bin/env 的手工树也必须可用", DshPaths.rootfsLooksUsable(alt))
            } finally {
                alt.deleteRecursively()
            }

            // 对照：什么都没有的目录不得被判可用（防止判据退化成恒真）
            val empty = Files.createTempDirectory("r35-04-empty").toFile()
            try {
                assertFalse("空目录不得判为可用（否则判据是假护栏）", DshPaths.rootfsLooksUsable(empty))
            } finally {
                empty.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // =====================================================================
    // 4. 接线：生产代码里的时序与去留（源码切片）
    // =====================================================================

    /**
     * ★ 生产代码必须**接住**返回值：`val report = … uncompressTarXZ(…)`。
     *
     * 修复前的形态是裸调用（返回值直接丢弃）。这条同时证明"判据吃的确实是这份报告"。
     */
    @Test
    fun productionAssignsTheExtractReport() {
        val code = bootstrapCode()
        assertTrue(
            "必须在 extractRootfs 里以赋值形态接住 ExtractReport（原来裸调用把返回值丢了）",
            code.contains("val report = context.assets.open")
        )
        assertTrue(
            "report 必须来自 RuntimeUtils.uncompressTarXZ",
            code.contains("RuntimeUtils.uncompressTarXZ(input, tmpDir, listener(onProgress))")
        )
        assertTrue(
            "★ 必须用 report.hasFailures() 当门禁（不能用 describe() 的字面值，也不能把退化算进失败）",
            code.contains("report.hasFailures()")
        )
    }

    /**
     * ★ 时序：失败判定必须早于**写 version** 与**删备份**。
     *
     * 这两条顺序各自堵一个后遗症：
     * - 先写 version 的话，下次 `isLatest` 可能判"已是最新"⇒ 永不重解压（死角）；
     * - 先删备份的话，回滚路径没了（正是缺陷本体）。
     *
     * 断言前先剥注释：本轮新增的 KDoc 里**必然**复述 `report.hasFailures()` 这些符号，
     * 不剥会把注释里的出现当成代码（`R31Fix211Test` 踩过同样的坑，这里照抄它的处理）。
     */
    @Test
    fun failureGateRunsBeforeVersionWriteAndBackupDelete() {
        val code = bootstrapCode()

        val gate = code.indexOf("report.hasFailures()")
        val versionWrite = code.indexOf("File(tmpDir, \"version\").writeText(")

        assertTrue("找不到 report.hasFailures() 门禁（源码结构变了？）", gate >= 0)
        assertTrue("找不到写 version 的语句（源码结构变了？）", versionWrite >= 0)
        assertTrue(
            "★ 失败判定必须早于写 version（实际 gate=$gate, version=$versionWrite）—— " +
                "否则部分失败的一轮仍会把 version 写成「已是最新」，下次永不重解压",
            gate < versionWrite
        )

        // ★ R35 红队 MUT-1 修正（两次才修对）：
        //   原来用 `code.indexOf("backupDir.deleteRecursively()")` —— 文件里有**两处**该调用：
        //     :801 遗留 `.old` 预清理（与 RE-04 无关，且在 gate 之后 ⇒ 断言恒真）
        //     :821 **成功路径删备份**（RE-04 真正关心的那一处）
        //   第一次修法用 `indexOf("shouldDropBackup(")` 当锚点也**不行**：那个符号在 :73 有
        //   **定义**，比调用点 :820 更靠前，从 :73 往后找仍然先命中 :801 的预清理
        //   （变异实测：删掉 :821 整行，断言依旧绿）。
        //   现在用**带实参的调用形态**当锚点，它只出现在 :820，不会命中定义。
        val dropCall = code.indexOf("shouldDropBackup(extractComplete =")
        assertTrue(
            "找不到 shouldDropBackup(extractComplete = ...) 的**调用**（源码结构变了？注意别匹配到定义）",
            dropCall >= 0
        )
        val successPathDelete = code.indexOf("backupDir.deleteRecursively()", dropCall)
        assertTrue(
            "★ 找不到成功路径删备份（shouldDropBackup 调用之后必须有 backupDir.deleteRecursively()）" +
                "—— 把这一行删掉会让 .old 永不回收，磁盘被两份 300MB+ 的 rootfs 吃满",
            successPathDelete >= 0
        )
        assertTrue(
            "★ 失败判定必须早于成功路径删备份（实际 gate=$gate, delete=$successPathDelete）—— " +
                "否则可用的旧备份会被部分失败的树顶掉（RE-04 本体）",
            gate < successPathDelete
        )
        assertTrue(
            "失败分支必须带上 describe()（「哪一条没解开」是排障唯一的定位信息，不能只有一句「解压失败」）",
            code.contains("report.describe()")
        )
    }

    /**
     * ★ 删备份的条件必须**走了新判据**，而不是只信布局门。
     *
     * 变异 M4（把删备份改回 `if (looksUsable(destDir))`）必须让这条红。
     */
    @Test
    fun backupDeletionUsesTheTightenedCriterion() {
        val code = bootstrapCode()
        assertTrue(
            "删备份必须经 shouldDropBackup(…)：完整性 ∧ 布局可用两条同时成立才删",
            code.contains("shouldDropBackup(")
        )
        assertTrue(
            "shouldDropBackup 的调用必须同时传入解压完整性（verdict == CLEAN）与新树可用性",
            code.contains("extractComplete = verdict == ExtractVerdict.CLEAN") &&
                code.contains("newTreeUsable = looksUsable(destDir)")
        )
        // 回滚分支必须还在（删备份的反面）
        assertTrue(
            "回滚逻辑不得被删掉（新树不可用 → 把 .old 换回 dest）",
            code.contains("backupDir.renameTo(destDir)")
        )
    }

    /**
     * `tmpDir.mkdirs()` 的返回值必须被判（原实现裸调用）。
     *
     * 不判的后果：在一个没建出来的目录里解压，第一个子项抛 `FileNotFoundException`，
     * 用户看到的是一句和 rootfs 毫无关系的路径错误。
     */
    @Test
    fun tempDirCreationFailureIsHandled() {
        val code = bootstrapCode()
        assertTrue(
            "tmpDir.mkdirs() 的返回值必须被判 —— 裸调用会把「目录建不出来」伪装成第一个子项的 FileNotFoundException",
            code.contains("if (!tmpDir.mkdirs()")
        )
        assertTrue(
            "建目录失败必须走本地化文案（dsh_bootstrap_data_dir_failed），不是硬编码中文",
            code.contains("R.string.dsh_bootstrap_data_dir_failed")
        )
    }

    /**
     * ★ 形状守卫：`looksUsable` 的 3 路径判据**一个字都没改**。
     *
     * 理由（本轮的硬约束）：它有**三个**调用点，其中 `hoistSingleTopLevelDir` 用它判断
     * tar 内层目录"像不像 rootfs"。改判据会连带改变 hoist 的行为，那是没人预期的副作用。
     *
     * 变异 M5（往 `looksUsable` 里加 version 检查 / 加可执行位检查）必须让这条红。
     */
    @Test
    fun looksUsableStaysTheThreePathLayoutProbe() {
        val code = bootstrapCode()
        val marker = "private fun looksUsable(dir: File): Boolean ="
        val at = code.indexOf(marker)
        assertTrue("找不到 looksUsable 的定义（源码结构变了？）", at >= 0)
        val body = code.substring(at, minOf(code.length, at + 400)).substringBefore("\n\n")
        assertTrue(
            "looksUsable 必须仍是 bin/sh 判据（实际：<$body>）",
            body.contains("File(dir, \"bin/sh\").exists()")
        )
        assertTrue(
            "looksUsable 必须仍是 usr/bin/env 判据（实际：<$body>）",
            body.contains("File(dir, \"usr/bin/env\").exists()")
        )
        assertTrue(
            "looksUsable 必须仍是 bin/busybox 判据（实际：<$body>）",
            body.contains("File(dir, \"bin/busybox\").exists()")
        )
        assertFalse(
            "★ looksUsable 不得被塞进 version 检查 —— 它被 hoistSingleTopLevelDir 调用，" +
                "而 hoist 判的是内层目录（解压阶段还没有 version），加检查会直接打死 hoist 分支",
            body.contains("\"version\"")
        )

        // 三个调用点必须仍是三个（第四个出现 = 有人又加了一处依赖，需重新论证）。
        // 用负向后行断言把**定义行** `fun looksUsable(` 排除掉，否则数出来恒为 4。
        val callSites = Regex("""(?<!fun )looksUsable\(""").findAll(code).count()
        assertEquals(
            "looksUsable 必须恰好保留 3 个调用点（解压后布局门 / 删备份门 / hoist）；实际 $callSites",
            3,
            callSites
        )
    }

    /**
     * `DshPaths.rootfsLooksUsable(File)` 的 3 路径判据也一个字没改 ——
     * 手工 rootfs 与 `ProotCommand.preflight` 依赖它（不变式 1）。
     */
    @Test
    fun dshPathsRootfsProbeIsUntouched() {
        val src = resolveUnder("FCL/src/main/java/com/dsh/core/DshPaths.kt")
        assertNotNull("找不到 DshPaths.kt（请在仓库根或 FCL/ 目录下运行测试）", src)
        val code = stripComments(src!!.readText())
        val at = code.indexOf("fun rootfsLooksUsable(root: File): Boolean =")
        assertTrue("找不到 rootfsLooksUsable(root: File) 的重载（源码结构变了？）", at >= 0)
        val body = code.substring(at, minOf(code.length, at + 300)).substringBefore("\n\n")
        assertTrue("必须仍查 bin/sh", body.contains("File(root, \"bin/sh\").exists()"))
        assertTrue("必须仍查 usr/bin/env", body.contains("File(root, \"usr/bin/env\").exists()"))
        assertTrue("必须仍查 bin/busybox", body.contains("File(root, \"bin/busybox\").exists()"))
        assertFalse(
            "★ 不得给 rootfsLooksUsable 加 version / 可执行位检查 —— " +
                "手工放置的 rootfs 没有 version，会被从可用变不可用（round31 的既有结论）",
            body.contains("\"version\"")
        )
    }

    // =====================================================================
    // 夹具
    // =====================================================================

    /**
     * `DshBootstrap.kt` 的**去注释**源码。
     *
     * 为什么必须去注释：本轮新增的 KDoc 为了讲清缺陷，必然复述 `report.hasFailures()`、
     * `looksUsable`、`backupDir.deleteRecursively()` 这些符号。不去注释的话，
     * 顺序断言会命中注释里的出现而非代码（`R31Fix211Test` 实测踩过，结论正好反过来）。
     */
    private fun bootstrapCode(): String {
        val src = resolveUnder("FCL/src/main/java/com/dsh/core/DshBootstrap.kt")
        assertNotNull(
            "找不到 DshBootstrap.kt（cwd=${System.getProperty("user.dir")}）",
            src
        )
        return stripComments(src!!.readText())
    }

    /** 去掉行尾 `//` 注释与块注释（字符串字面量里出现 `//` 的情形本文件没有）。 */
    private fun stripComments(raw: String): String {
        val noBlock = raw.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        return noBlock.lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
    }

    private fun resolveUnder(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            File(dir, rel).takeIf { it.isFile }?.let { return it }
            File(dir, rel.removePrefix("FCL/")).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    /**
     * 反射造一个 `ExtractReport`。
     *
     * 为什么走反射而不是直接 `RuntimeUtils.ExtractReport()`：`RuntimeUtils` 的**外层类**签名里
     * 带 `org.apache.commons.compress.*`，而 harness 的 classpath 不含 compress/xz —— 直接引用
     * 外层会 `NoClassDefFoundError`（实测：`XZCompressorInputStream`）。内嵌类的字节码只依赖
     * String/int，可以单独加载（本机实测通过），所以只反射内层类。
     */
    private fun newExtractReport(): Any {
        val cls = extractReportClass()
        val ctor = cls.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    private fun extractReportClass(): Class<*> = try {
        Class.forName("com.tungsten.fcl.util.RuntimeUtils\$ExtractReport")
    } catch (t: Throwable) {
        throw AssertionError("加载 RuntimeUtils\$ExtractReport 失败：$t", t)
    }

    private fun recordFailure(report: Any, method: String, detail: String) {
        val m = report.javaClass.getDeclaredMethod(method, String::class.java)
        m.isAccessible = true
        m.invoke(report, detail)
    }

    private fun hasFailures(report: Any): Boolean =
        report.javaClass.getMethod("hasFailures").invoke(report) as Boolean

    private fun failureCount(report: Any): Int =
        report.javaClass.getMethod("getFailureCount").invoke(report) as Int

    private fun firstFailure(report: Any): String? =
        report.javaClass.getMethod("getFirstFailure").invoke(report) as String?

    private fun describe(report: Any): String =
        report.javaClass.getMethod("describe").invoke(report) as String
}
