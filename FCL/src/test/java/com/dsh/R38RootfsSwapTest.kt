package com.dsh

import com.dsh.core.SwapResult
import com.dsh.core.hoistEntriesInto
import com.dsh.core.swapRootfsDirs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R38 / RE-05：rootfs 原子替换的降级链**忽略每一步的返回值**。
 *
 * ## 缺陷本体
 *
 * 修前 `extractRootfs` 的替换段是四步裸调用 + 一条"rename 失败就逐个搬子项"的 fallback：
 *
 * ```kotlin
 * if (backupDir.exists()) backupDir.deleteRecursively()      // 返回值未判
 * val hadOld = destDir.exists()
 * if (hadOld && !destDir.renameTo(backupDir)) {
 *     destDir.deleteRecursively()                            // ← 新树还没上位就删旧底座
 * }
 * if (!tmpDir.renameTo(destDir)) {
 *     destDir.mkdirs()
 *     tmpDir.listFiles()?.forEach { f -> f.renameTo(...) }   // 每个子项的值未判
 *     tmpDir.deleteRecursively()                             // ← 没搬成功的新内容在这里被删
 * }
 * ```
 *
 * 后果三个，按危险程度排：
 * 1. **旧底座被提前删**（`dest→.old` 改名失败后立刻 `deleteRecursively`）—— 此时新树还没上位，
 *    一次 rename 失败就同时失去新旧两份；
 * 2. **新内容被删**（`tmp→dest` 失败后逐个搬，没搬成功的随 `deleteRecursively` 消失）——
 *    用户得重下 142MB 的 tar.xz；
 * 3. **失败不响亮**：只写一行日志，紧接着 `verifySync` 可能 PASS（混合体里 `bin/sh` 还在），
 *    用户看到"底座就绪"。
 *
 * ## 本轮的实现形态
 *
 * 替换段整段抽成 [swapRootfsDirs]（纯 `java.io`，**不碰** `Context` / `DshPaths`），四步都是
 * 强制步、每步判返回值、失败必回滚、任何失败路径都不删 `tmp`。`hoistSingleTopLevelDir` 的
 * 子项搬运抽成 [hoistEntriesInto]（同一条"失败不删"不变式）。
 *
 * ## 为什么这些用例能测（这是本包最重要的可测性杠杆）
 *
 * `extractRootfs` 是 private 且依赖 `Context`/`AssetManager`/`DshPaths` lateinit，端到端驱动要
 * 一整套 Android 桩 + 一个 148MB 的 tar.xz（`R35PartialExtractTest` 已论证过成本）。而真机上
 * 制造 `ENOTEMPTY` 需要 fs 故障注入 —— 本机做不到。
 *
 * 两个障碍用同一招绕开：**把要测的逻辑抽成收 lambda 的顶层函数**。`rename` / `deleteTree` 的
 * 默认值就是生产实现（`File::renameTo` / `File::deleteRecursively`），测试只在小部分用例里换成
 * 会失败的 lambda —— 于是每个失败分支都能在 JVM 里用**真临时目录**稳定复现，不依赖 Windows 与
 * Linux 的 rename 语义差异。
 *
 * ## 变异验证（实测，见 docs/reports/round38/FIX-RE05.md）
 *
 * - (a) 忽略 `rename(dest, backup)` 的返回值 ⇒ [backupOldFailureLeavesDestUntouched] 精确变红；
 * - (b) 把"失败不删 tmp"改成"失败就删 tmp" ⇒ [tmpSurvivesWhenPromoteFails] 等变红。
 *
 * 运行：`sh run-tests.sh`
 */
class R38RootfsSwapTest {

    // =====================================================================
    // 夹具
    // =====================================================================

    /**
     * 三层根：`base/{rootfs.tmp, rootfs, rootfs.old}` —— 与生产布局同形
     * （`DshPaths.kt:85` 只定义 `ROOTFS_DIR = "$ROOT_DIR/rootfs"`，另两个由 `DshBootstrap` 拼同前缀）。
     */
    private class Stage(val base: File) {
        val tmp: File = File(base, "rootfs.tmp")
        val dest: File = File(base, "rootfs")
        val backup: File = File(base, "rootfs.old")
    }

    private fun withStage(tag: String, body: (Stage) -> Unit) {
        val base = Files.createTempDirectory("dsh-r38-re05-$tag").toFile()
        try {
            body(Stage(base))
        } finally {
            base.deleteRecursively()
        }
    }

    /** 造一棵"旧底座"：`bin/sh` + 一个独有的标记文件（用来逐字节认身份）。 */
    private fun seedOldTree(dir: File) {
        assertTrue(File(dir, "bin").mkdirs())
        File(dir, "bin/sh").writeText("#!/bin/sh\n# OLD\n")
        File(dir, "OLD-MARKER").writeText("old-generation\n")
    }

    /** 造一棵"新底座"：`bin/sh` + 一个独有的标记文件（名字不与旧树重合，便于分辨谁在 dest）。 */
    private fun seedNewTree(dir: File) {
        assertTrue(File(dir, "bin").mkdirs())
        File(dir, "bin/sh").writeText("#!/bin/sh\n# NEW\n")
        File(dir, "NEW-MARKER").writeText("new-generation\n")
    }

    /** 递归读取一棵树的 `相对路径 -> 内容` 快照，用于断言"一个字节都没动"。 */
    private fun snapshot(dir: File): Map<String, String> {
        if (!dir.exists()) return emptyMap()
        return dir.walkTopDown()
            .filter { it.isFile }
            .associate {
                it.relativeTo(dir).path.replace('\\', '/') to it.readText()
            }
    }

    /** 生产口径的"新树可用"：`bin/sh` / `usr/bin/env` / `bin/busybox` 三选一（同 `looksUsable`）。 */
    private val usableProbe: (File) -> Boolean = { dir ->
        File(dir, "bin/sh").exists() || File(dir, "usr/bin/env").exists() ||
            File(dir, "bin/busybox").exists()
    }

    private fun assertOldTreeAt(dir: File, why: String) {
        assertTrue("$why：bin/sh 必须还在", File(dir, "bin/sh").exists())
        assertTrue("$why：OLD-MARKER 必须还在", File(dir, "OLD-MARKER").isFile)
        assertFalse("$why：不得出现新树的条目", File(dir, "NEW-MARKER").exists())
        assertTrue(
            "$why：内容必须是旧那一份",
            File(dir, "bin/sh").readText().contains("# OLD")
        )
    }

    private fun assertNewTreeAt(dir: File, why: String) {
        assertTrue("$why：bin/sh 必须还在", File(dir, "bin/sh").exists())
        assertTrue("$why：NEW-MARKER 必须还在", File(dir, "NEW-MARKER").isFile)
        assertFalse("$why：不得残留旧树的条目", File(dir, "OLD-MARKER").exists())
        assertTrue(
            "$why：内容必须是新那一份",
            File(dir, "bin/sh").readText().contains("# NEW")
        )
    }

    /** 从 `SwapResult` 里取出 `Failed`，取不到就 fail（避免 `as` 抛出无信息的 ClassCastException）。 */
    private fun failed(actual: SwapResult, why: String): SwapResult.Failed {
        assertTrue("$why：期望 SwapResult.Failed，实际 <$actual>", actual is SwapResult.Failed)
        return actual as SwapResult.Failed
    }

    // =====================================================================
    // 1) 正常路径
    // =====================================================================

    /**
     * 升级正路：旧树在 `dest`、新树在 `tmp`、新树可用 ⇒ `Replaced`，`dest` 变新树，`.old` 被删。
     *
     * 这是**对照**：失败路径写成"永远报错"也能让下面每条失败用例绿，只有这条能拦住它。
     */
    @Test
    fun promoteSucceedsAndDropsBackup() = withStage("happy") { s ->
        seedOldTree(s.dest)
        seedNewTree(s.tmp)

        val result = swapRootfsDirs(s.tmp, s.dest, s.backup, usableProbe)

        assertTrue("升级正路必须是 Replaced，实际 <$result>", result is SwapResult.Replaced)
        assertNewTreeAt(s.dest, "新树必须整体上位")
        assertFalse("新树可用 ⇒ 备份必须已删（不删会把磁盘吃满两份）", s.backup.exists())
        assertFalse("tmp 已被 rename 走，不应再存在", s.tmp.exists())
    }

    /**
     * **首装**（`dest` 不存在）：`dest` 的父目录里只有 `tmp` ⇒ 库 `Replaced`。
     *
     * 首装与升级走**同一条** promote 路径（Linux 上 `rename(2)` 到不存在的目标即成功），
     * 区别只在"没有备份可删"。若实现把首装单独写成"直接 rename + 忽略返回值"，这条会绿而
     * [backupOldFailureLeavesDestUntouched] 仍能拦住 —— 但反过来，实现若把首装也当成
     * "有旧树"去 rename(dest→backup)，rename 会失败 ⇒ 首装永远报 BACKUP_OLD。这条就是那个探针。
     */
    @Test
    fun freshInstallWithoutOldTreeIsReplaced() = withStage("fresh") { s ->
        seedNewTree(s.tmp)
        assertFalse("前置：这是首装，dest 不存在", s.dest.exists())

        val result = swapRootfsDirs(s.tmp, s.dest, s.backup, usableProbe)

        assertTrue("首装必须是 Replaced（无旧树可备份），实际 <$result>", result is SwapResult.Replaced)
        assertNewTreeAt(s.dest, "首装后新树必须就位")
        assertFalse("首装不该凭空造出 .old", s.backup.exists())
    }

    // =====================================================================
    // 2) 新树不可用 ⇒ ReplacedBackupKept（备份必须留着当回滚路径）
    // =====================================================================

    /**
     * 新树**布局不可用**（没有 `bin/sh` 等三条路径）⇒ `ReplacedBackupKept`，`.old` **必须保留**。
     *
     * 这是 R35 的第二道门的接口契约：`swapRootfsDirs` 只报告"备份还在"，由调用方用同一个
     * `shouldDropBackup` 判据决定回滚。若这里错误地返回 `Replaced`（= 备份已删），调用方就会以
     * 为"一切正常"，**可用的旧底座永久消失** —— 那正是 RE-04 的缺陷。
     */
    @Test
    fun unusableNewTreeKeepsBackup() = withStage("unusable") { s ->
        seedOldTree(s.dest)
        // "新树"存在但不像 rootfs：只剩一个标记文件。
        assertTrue(s.tmp.mkdirs())
        File(s.tmp, "NEW-MARKER").writeText("new-generation\n")

        val result = swapRootfsDirs(s.tmp, s.dest, s.backup, usableProbe)

        assertTrue(
            "新树不可用 ⇒ 必须返回 ReplacedBackupKept（不能报 Replaced，那表示备份已删），实际 <$result>",
            result is SwapResult.ReplacedBackupKept
        )
        assertTrue("★ 备份必须保留 —— 它是唯一的回滚路径", s.backup.isDirectory)
        assertOldTreeAt(s.backup, "备份里必须是完整的旧树")
    }

    /**
     * 新树可用、只是 `.old` 删不掉（`deleteTree` 返回 false 且不清空）⇒ `ReplacedBackupKept`。
     *
     * 与上一条**同一个返回值、完全不同的成因**：这条不致命（下一轮开头的预清理会再试），
     * 上一条致命（必须回滚）。压成一个值是有意的 —— 对调用方都是"替换完了、备份还在",
     * 调用方用 `shouldDropBackup` 分辨。这里钉住"删不掉不许升级成 Failed"：
     * 一次**已经成功**的替换不该因为清理失败而变成红字。
     */
    @Test
    fun undeletableBackupIsKeptQuietly() = withStage("undeletable") { s ->
        seedOldTree(s.dest)
        seedNewTree(s.tmp)

        var deleteAttempts = 0
        val result = swapRootfsDirs(
            s.tmp, s.dest, s.backup, usableProbe,
            deleteTree = { deleteAttempts++; false }   // 假装永远删不掉，且不清空
        )

        assertTrue(
            "备份删不掉 ⇒ ReplacedBackupKept（不是 Failed：新树已经成功上位了），实际 <$result>",
            result is SwapResult.ReplacedBackupKept
        )
        assertEquals("应当尝试删过一次备份", 1, deleteAttempts)
        assertTrue("备份必须还在（deleteTree 没删）", s.backup.isDirectory)
        assertNewTreeAt(s.dest, "新树仍然必须已上位（清理失败不影响替换结论）")
    }

    /** `shouldDropBackup` 为真时 `.old` 确实被删；为假时确实保留 —— 两条对照，见上面两例。 */
    @Test
    fun backupCleanupFollowsShouldDropBackupDecision() = withStage("decision") { s ->
        seedOldTree(s.dest)
        seedNewTree(s.tmp)
        var deleteCalls = 0
        val result = swapRootfsDirs(
            s.tmp, s.dest, s.backup, usableProbe,
            deleteTree = { deleteCalls++; it.deleteRecursively() }
        )
        assertTrue("新树可用 ⇒ Replaced", result is SwapResult.Replaced)
        assertEquals("新树可用时必须真的去删备份", 1, deleteCalls)
        assertFalse("且删成功了 ⇒ 备份不在", s.backup.exists())
    }

    // =====================================================================
    // 3) 第 1 步失败：遗留 .old 清不掉 ⇒ CLEAN_OLD，dest/tmp 一个字节都不动
    // =====================================================================

    /**
     * **`.old` 删不干净**（`deleteTree` 返回 false 且目录仍然非空）⇒ `Failed(CLEAN_OLD)`。
     *
     * 为什么必须在这里单独报：继续往下走的话，第 2 步 `dest→backup` 会撞 `ENOTEMPTY`
     * **必然**失败，用户拿到的是一句"cannot move .../rootfs to .../rootfs.old" —— 而真正的原因
     * 是"上一轮的备份占着名字、清不掉"。两个错误指向的处置完全不同（腾空间/重启 vs 文件系统故障）。
     *
     * 同时断言 **`dest` 与 `tmp` 都没被碰**：这一步失败时现场必须完全等于"替换前"。
     */
    @Test
    fun staleBackupThatCannotBeRemovedReportsCleanOld() = withStage("cleanold") { s ->
        seedOldTree(s.dest)
        seedNewTree(s.tmp)
        // 上一轮遗留的 .old，且注入的 deleteTree 清不掉它。
        assertTrue(s.backup.mkdirs())
        File(s.backup, "stale").writeText("previous run leftover\n")

        val destBefore = snapshot(s.dest)
        val tmpBefore = snapshot(s.tmp)

        val result = swapRootfsDirs(
            s.tmp, s.dest, s.backup, usableProbe,
            deleteTree = { false }                    // 返回 false，且什么都不删
        )

        val f = failed(result, "遗留 .old 清不掉")
        assertEquals(
            "★ 必须报 CLEAN_OLD（不是 BACKUP_OLD / PROMOTE_NEW）—— 否则用户看到的是下一步的 ENOTEMPTY，",
            SwapResult.SwapStep.CLEAN_OLD,
            f.step
        )
        assertTrue(
            "detail 必须带 .old 的绝对路径（排障要知道是哪个目录清不掉）：<${f.detail}>",
            f.detail.contains(s.backup.absolutePath)
        )
        assertEquals(
            "★ 这一步失败时 dest 必须一个字节都没动（旧底座还是那一份）",
            destBefore, snapshot(s.dest)
        )
        assertEquals(
            "★ tmp 也不许动（新内容还在，下一轮能复用）",
            tmpBefore, snapshot(s.tmp)
        )
        assertTrue("tmp 目录必须还在", s.tmp.isDirectory)
    }

    // =====================================================================
    // 4) 第 2 步失败：dest→.old 改名失败 ⇒ BACKUP_OLD，一个字节都不删
    // =====================================================================

    /**
     * **`dest→.old` 改名失败** ⇒ `Failed(BACKUP_OLD)`，且：
     * - `dest` 内容**原封不动**（逐文件快照相等）—— 这是 I-2，本缺陷最危险的一步；
     * - `tmp` **还在**—— 新内容不许丢（I-1）；
     * - 不产生半成品 `.old`。
     *
     * 修前的行为：rename 失败后**立刻** `destDir.deleteRecursively()`（"退化为直接覆盖"）——
     * 旧底座没了，而新树还没上位。一次 rename 失败同时失去新旧两份。
     */
    @Test
    fun backupOldFailureLeavesDestUntouched() = withStage("backupold") { s ->
        seedOldTree(s.dest)
        seedNewTree(s.tmp)
        val destBefore = snapshot(s.dest)
        val tmpBefore = snapshot(s.tmp)

        val result = swapRootfsDirs(
            s.tmp, s.dest, s.backup, usableProbe,
            rename = { a, b ->
                if (a == s.dest) false             // 只让 dest→.old 这一步失败
                else a.renameTo(b)
            }
        )

        val f = failed(result, "dest→.old 失败")
        assertEquals("必须报 BACKUP_OLD", SwapResult.SwapStep.BACKUP_OLD, f.step)
        assertTrue(
            "detail 必须同时带 dest 与 .old 的绝对路径：<${f.detail}>",
            f.detail.contains(s.dest.absolutePath) && f.detail.contains(s.backup.absolutePath)
        )
        assertEquals(
            "★ I-2：dest 必须原封不动（rename 失败 = 源未动，实现不许再补一刀 deleteRecursively）",
            destBefore, snapshot(s.dest)
        )
        assertEquals("★ I-1：tmp 必须还在（新内容不许丢）", tmpBefore, snapshot(s.tmp))
        assertFalse("失败时不该凭空造出 .old", s.backup.exists())
    }

    // =====================================================================
    // 5) 第 3 步失败：tmp→dest 失败
    // =====================================================================

    /**
     * **`tmp→dest` 失败但回滚成功** ⇒ `Failed(PROMOTE_NEW)`，且：
     * - `dest` 恢复成**旧内容**（`.old → dest` 搬回来了）；
     * - `tmp` **还在**（I-1：下一轮可直接复用，不用重下 142MB）；
     * - `.old` 已随回滚的 rename 消失（旧树回到了 dest，不应有两份）。
     */
    @Test
    fun promoteFailureRollsBackToOldTree() = withStage("promote-rollback") { s ->
        seedOldTree(s.dest)
        seedNewTree(s.tmp)
        val destBefore = snapshot(s.dest)
        val tmpBefore = snapshot(s.tmp)

        val result = swapRootfsDirs(
            s.tmp, s.dest, s.backup, usableProbe,
            rename = { a, b ->
                if (a == s.tmp) false              // 只让 tmp→dest 失败；dest→.old 与回滚都成功
                else a.renameTo(b)
            }
        )

        val f = failed(result, "tmp→dest 失败但回滚成功")
        assertEquals(
            "回滚成功 ⇒ 必须报 PROMOTE_NEW（不是 ROLLBACK —— 用户手上的底座是好的）",
            SwapResult.SwapStep.PROMOTE_NEW,
            f.step
        )
        assertEquals(
            "★ 回滚后 dest 必须逐字节等于替换前的旧树",
            destBefore, snapshot(s.dest)
        )
        assertEquals("★ I-1：tmp 必须还在（新内容不许丢）", tmpBefore, snapshot(s.tmp))
        assertFalse("旧树已回到 dest，.old 不该再占着名字", s.backup.exists())
        assertTrue("PROMOTE_NEW 的语义是『底座还能用』", f.destUsable)
    }

    /**
     * **`tmp→dest` 与回滚都失败** ⇒ `Failed(ROLLBACK)` —— 最坏的一种，并且：
     * - `.old` **必须还在**（它是唯一的旧底座）；
     * - `detail` 里必须有 `.old` 的**绝对路径**与"手工挪回去"的指引 —— 用户唯一自救手段；
     * - `tmp` 仍然留着。
     */
    @Test
    fun rollbackFailureKeepsBackupAndNamesItsPath() = withStage("rollback") { s ->
        seedOldTree(s.dest)
        seedNewTree(s.tmp)
        val tmpBefore = snapshot(s.tmp)
        val oldBefore = snapshot(s.dest)

        val result = swapRootfsDirs(
            s.tmp, s.dest, s.backup, usableProbe,
            rename = { a, b ->
                // dest→.old 成功；tmp→dest 与 .old→dest 都失败（模拟文件系统整体故障）
                if (a == s.dest) a.renameTo(b) else false
            }
        )

        val f = failed(result, "上位与回滚都失败")
        assertEquals(
            "★ 必须报 ROLLBACK（不是 PROMOTE_NEW —— 那个值表示底座是好的，这里不是）",
            SwapResult.SwapStep.ROLLBACK,
            f.step
        )
        assertTrue(
            "★ 最坏现场必须保留 .old —— 它是唯一的旧底座",
            s.backup.isDirectory
        )
        assertEquals(
            "★ 备份里的旧树必须完整（用户要手工把它挪回 dest）",
            oldBefore, snapshot(s.backup)
        )
        assertTrue(
            "★ detail 必须带 .old 的绝对路径（否则用户无从下手）：<${f.detail}>",
            f.detail.contains(s.backup.absolutePath)
        )
        assertTrue(
            "★ detail 必须带 dest 的绝对路径（告诉他挪到哪）：<${f.detail}>",
            f.detail.contains(s.dest.absolutePath)
        )
        assertTrue(
            "★ detail 必须给出『手工挪回去』这个唯一自救手段：<${f.detail}>",
            f.detail.contains("manually")
        )
        assertEquals("I-1：tmp 仍然不许丢", tmpBefore, snapshot(s.tmp))
        assertFalse(
            "这一步 dest 上不该有可用底座（两个 rename 都失败了）",
            f.destUsable
        )
    }

    /** 首装时 `tmp→dest` 失败（没有可回滚的东西）⇒ `PROMOTE_NEW`，`tmp` 保留、不报 ROLLBACK。 */
    @Test
    fun promoteFailureOnFreshInstallReportsPromoteWithoutRollback() = withStage("fresh-fail") { s ->
        seedNewTree(s.tmp)
        val tmpBefore = snapshot(s.tmp)

        val result = swapRootfsDirs(
            s.tmp, s.dest, s.backup, usableProbe,
            rename = { _, _ -> false }
        )

        val f = failed(result, "首装时上位失败")
        assertEquals(
            "首装没有旧树可回滚 ⇒ 必须报 PROMOTE_NEW（不是 ROLLBACK）",
            SwapResult.SwapStep.PROMOTE_NEW,
            f.step
        )
        assertEquals("I-1：tmp 必须还在", tmpBefore, snapshot(s.tmp))
        assertFalse("首装失败不该造出 .old", s.backup.exists())
    }

    // =====================================================================
    // 6) hoistEntriesInto：同一个"失败不删"不变式
    // =====================================================================

    /**
     * 上提成功：内层目录的所有条目搬进 `target`，内层目录本身被删掉 ⇒ 返回 null。
     *
     * 这是 [hoistEntriesInto] 的对照 —— 失败用例写成"永远返回错误"也能绿，只有它能拦住。
     */
    @Test
    fun hoistMovesEveryEntryAndRemovesInnerDir() {
        val base = Files.createTempDirectory("dsh-r38-re05-hoist-ok").toFile()
        try {
            val target = File(base, "rootfs").apply { mkdirs() }
            val only = File(target, "debian").apply { mkdirs() }
            File(only, "bin").mkdirs()
            File(only, "bin/sh").writeText("#!/bin/sh\n")
            File(only, "etc").mkdirs()

            val failure = hoistEntriesInto(target, only)

            assertNull("全部搬成功时必须返回 null，实际 <$failure>", failure)
            assertTrue("bin 必须已上提", File(target, "bin/sh").isFile)
            assertTrue("etc 必须已上提", File(target, "etc").isDirectory)
            assertFalse("内层目录搬空后必须被删掉", only.exists())
        } finally {
            base.deleteRecursively()
        }
    }

    /**
     * **有子项搬不动** ⇒ 返回失败详情，且**内层目录不许删**。
     *
     * 修前是 `forEach { renameTo }` + 无条件 `only.delete()`：搬失败的那几个子项随内层目录一起
     * 消失，`tmp` 变成"上提了一半"的树；而 `looksUsable(tmpDir)` 只要 `bin/sh` 恰好是搬成功的
     * 那几个之一就会判 true ⇒ 残缺树被放行进替换环节。
     */
    @Test
    fun hoistFailureKeepsInnerDirAndNamesTheFirstFailure() {
        val base = Files.createTempDirectory("dsh-r38-re05-hoist-fail").toFile()
        try {
            val target = File(base, "rootfs").apply { mkdirs() }
            val only = File(target, "debian").apply { mkdirs() }
            File(only, "bin").mkdirs()
            File(only, "etc").mkdirs()

            val failure = hoistEntriesInto(
                target, only,
                rename = { a, b -> if (a.name == "etc") false else a.renameTo(b) }
            )

            assertNotNull("有子项搬不动时必须返回失败详情", failure)
            assertTrue(
                "detail 必须点名第一个失败的条目与计数：<$failure>",
                failure!!.contains("etc") && failure.contains("1/2")
            )
            assertTrue("★ 搬成功的那个可以留在 target", File(target, "bin").isDirectory)
            assertTrue(
                "★ 内层目录一个字节都不许删 —— 里面还有没搬走的 etc",
                File(only, "etc").isDirectory
            )
            assertFalse("失败的条目当然没有被搬过去", File(target, "etc").exists())
        } finally {
            base.deleteRecursively()
        }
    }

    /**
     * 全部搬完但内层目录删不掉（并发写入 / 隐藏条目）⇒ 也要报失败，而不是当成功。
     *
     * 否则 `tmp` 里会同时存在"已上提的条目"和"内层目录"，`looksUsable` 判 true ⇒ 一个结构脏掉
     * 的树进了替换环节。
     */
    @Test
    fun hoistReportsFailureWhenInnerDirCannotBeRemoved() {
        val base = Files.createTempDirectory("dsh-r38-re05-hoist-rm").toFile()
        try {
            val target = File(base, "rootfs").apply { mkdirs() }
            val only = File(target, "debian").apply { mkdirs() }
            File(only, "bin").mkdirs()

            val failure = hoistEntriesInto(target, only, deleteDir = { false })

            assertNotNull("内层目录删不掉时必须报失败（否则是结构脏掉的树）", failure)
            assertTrue(
                "detail 必须点名那个删不掉的目录：<$failure>",
                failure!!.contains(only.absolutePath)
            )
            assertTrue("条目已经搬过去了（这是这一步的既成事实）", File(target, "bin").isDirectory)
        } finally {
            base.deleteRecursively()
        }
    }

    // =====================================================================
    // 7) 接线 + 形状守卫（行为全绿但没接上线 = 缺陷仍在）
    // =====================================================================

    /**
     * `extractRootfs` 的替换段必须**只**通过 [swapRootfsDirs] 走，且旧的四步裸调用形态已消失。
     *
     * 去注释后再切片：本轮 KDoc 为讲清缺陷必然复述 `forEach { f -> f.renameTo(...) }` 这些符号，
     * 不去注释的话，断言会命中注释而**恒绿**（`R31Fix211Test` 踩过这个坑，结论正好反过来）。
     */
    @Test
    fun fallbackChainIsGoneAndSwapIsWiredIn() {
        val code = bootstrapCode()
        assertTrue(
            "必须存在 swapRootfsDirs(...) 的调用点（替换段被接回旧实现 = 缺陷复原）",
            code.contains("swapRootfsDirs(tmpDir, destDir, backupDir")
        )
        assertTrue(
            "四个 SwapResult 分支都必须被处理（Replaced / ReplacedBackupKept / Failed）",
            code.contains("is SwapResult.Replaced ->") &&
                code.contains("is SwapResult.ReplacedBackupKept ->") &&
                code.contains("is SwapResult.Failed ->")
        )
        assertFalse(
            "★ 逐个搬子项的 fallback 必须消失：`forEach { f -> f.renameTo(` 是缺陷的骨架",
            code.contains("forEach { f -> f.renameTo(")
        )
        assertFalse(
            "★ 旧的『rename 失败 ⇒ 退化为直接覆盖』日志必须消失（那条分支删掉了）",
            code.contains("退化为直接覆盖")
        )
        assertFalse(
            "★ 必须删掉『跨目录 rename 失败』的理由 —— 三个目录同父同挂载点，EXDEV 不可能（PLAN §1.2 实测）",
            code.contains("某些文件系统上跨目录 rename 失败")
        )
    }

    /**
     * `hoistSingleTopLevelDir` 的返回值**必须被判**。
     *
     * 抽出 [hoistEntriesInto] 却不判它的返回值，等于什么都没修：搬失败时 tmp 是"上提了一半"的
     * 树，而 `looksUsable(tmpDir)` 可能侥幸通过。这条是本包发现并修掉的一个**实现缺陷**
     * （前一个 Agent 把函数改成返回 `String?`，但调用点仍是裸调用）。
     */
    @Test
    fun hoistFailureIsNotIgnored() {
        val code = bootstrapCode()
        assertTrue(
            "★ hoistSingleTopLevelDir 的返回值必须被接住（裸调用 = 上提失败被静默吞掉）",
            code.contains("val hoistFailure = hoistSingleTopLevelDir(tmpDir)")
        )
        assertTrue(
            "★ 上提失败必须 fail-fast（不碰 dest、不删 tmp）",
            code.contains("if (hoistFailure != null)")
        )
        assertTrue(
            "上提失败的 detail 必须进日志总线（排障要能对上）",
            code.contains("rootfs 包内多层目录上提失败")
        )
    }

    /** `looksUsable` 的调用点数量必须与 "../../../docs" 里记录的契约一致（形状守卫）。 */
    @Test
    fun looksUsableCallSiteCountIsUnchanged() {
        val code = bootstrapCode()
        val callSites = Regex("""(?<!fun )looksUsable\(""").findAll(code).count()
        assertEquals(
            "looksUsable 的调用点必须仍是 3 个（解压后布局门 / 删备份门 / hoist）；" +
                "RE-05 把 destDir 那一处作为 lambda 传给 swapRootfsDirs，所以计数不变。实际 $callSites",
            3,
            callSites
        )
    }

    // =====================================================================
    // 夹具：源码切片
    // =====================================================================

    private fun bootstrapCode(): String {
        val src = resolveUnder("FCL/src/main/java/com/dsh/core/DshBootstrap.kt")
        assertNotNull("找不到 DshBootstrap.kt（cwd=${System.getProperty("user.dir")}）", src)
        return stripComments(src!!.readText())
    }

    /** 去掉行尾 `//` 注释与块注释（本文件里字符串字面量不含 `//`）。 */
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
}
