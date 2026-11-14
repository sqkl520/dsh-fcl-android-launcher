package com.dsh

import com.dsh.core.DshBootstrap
import com.dsh.core.isReplacingExistingRootfs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ★ R38 / RE-07：空间门槛的 `replacing` 判据 —— 必须是「旧 rootfs **存在**」，
 * 不是「旧 rootfs 看起来**可用**」。
 *
 * ## 缺陷
 *
 * `DshBootstrap.install()` 原来的 `val replacing = DshPaths.rootfsLooksUsable()` 把
 * 「存在」偷换成了「可用」（`rootfsLooksUsable` 只查 `bin/sh` / `usr/bin/env` / `bin/busybox`
 * 三条路径存不存在）。于是口径反了：
 *
 * | 现场 | 旧 replacing | 旧 need | 真机 ~1.1GB 可用 |
 * |---|---|---|---|
 * | 旧 rootfs 完好 | true | 960 MiB | 放行（对） |
 * | **旧 rootfs 存在但损坏** | **false** | **1560 MiB** | **永久卡住**（该放行） |
 *
 * 「存在但损坏」恰恰是**最需要重新解压**的那一次，门槛反而最高 —— 而事实是删掉盘上那
 * ~900 MB 就够，界面却没有任何路径告诉他。
 *
 * ## 断言分两层
 *
 * 1. **行为层**（[isReplacingExistingRootfs] 的真 temp 目录）：五种输入逐一钉死，其中
 *    [emptyDirIsFreshInstall] 这条最关键 —— 它是「不能写成 `exists()`」的守卫，
 *    [damagedRootfsCountsAsReplacing] 是缺陷本体。
 * 2. **接线层**（源码切片）：确认 `requiredFreeBytes` 的实参真的来自新判据，
 *    没有被悄悄换回 `rootfsLooksUsable`。行为对但没接上线 = 缺陷仍在。
 *
 * ## 为什么「空目录」这条不能省
 *
 * `DshPaths.loadPaths()`（`DshPaths.kt:95` 的 dirs 列表含 `ROOTFS_DIR`）在启动时就把
 * `ROOTFS_DIR` mkdirs 出来了 ⇒ **首装时它也存在，只是空的**。照字面写 `exists()` 会把首装
 * 误判成升级，1560 MiB 门槛被砍成 960 MiB ⇒ 首装放行、npm install 时才撞空间墙，把一次清楚
 * 的「首装空间不足」推迟成一次安装期失败（另一种假失败）。
 *
 * ## 变异验证（实测，见 docs/reports/round38/FIX-RE07.md）
 *
 * 把 `isReplacingExistingRootfs` 的判据改成 `rootfsDir.exists()` ⇒
 * [emptyDirIsFreshInstall] 精确变红，其余用例（不存在 / 非空 / 损坏 / 普通文件 / 子目录）
 * 全部保持绿 —— 说明这条用例正是「exists() 写错」的唯一探针。
 */
class R38ReplacingCheckTest {

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("dsh-r38-re07-$tag").toFile()

    private fun withDir(tag: String, body: (File) -> Unit) {
        val dir = tempDir(tag)
        try {
            body(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    // =====================================================================
    // 1) 行为层：isReplacingExistingRootfs 的五种输入
    // =====================================================================

    /**
     * 目录**不存在** ⇒ false（真·首装：连 `loadPaths` 都还没跑）。
     *
     * 主路径上 `loadPaths` 会先 mkdirs，所以这一格在生产里少见；但它把「不存在 ≠ 升级」这条
     * 语义钉住，让函数不依赖调用顺序。
     */
    @Test
    fun missingDirIsNotReplacing() = withDir("missing") { base ->
        val rootfs = File(base, "rootfs-never-created")
        assertFalse("目录不存在时不得判为升级", rootfs.exists())
        assertFalse(
            "目录不存在 ⇒ replacing=false（走 1560MB 首装门槛）",
            isReplacingExistingRootfs(rootfs)
        )
    }

    /**
     * **★ 最关键的一条**：目录存在但**空** ⇒ false。
     *
     * 这正是首装在 `DshPaths.loadPaths()`（`DshPaths.kt:95`）mkdirs 之后的现场。判据若写成
     * `rootfsDir.exists()`，这里会变 true ⇒ 首装被当成升级 ⇒ 门槛从 1560MB 掉到 960MB ⇒
     * 首装空间不足却放行，错误被推迟到 npm install。
     *
     * 变异验证：把判据改成 `exists()`，本条精确变红（其余四条仍绿）。
     */
    @Test
    fun emptyDirIsFreshInstall() = withDir("empty") { rootfs ->
        assertTrue("前置：目录必须真实存在（模拟 loadPaths 已 mkdirs）", rootfs.isDirectory)
        assertEquals("前置：目录必须为空", 0, rootfs.list()?.size ?: -1)
        assertFalse(
            "★ 空目录 = 首装（loadPaths 已经把 ROOTFS_DIR mkdirs 出来），" +
                "不得判为升级 —— 否则 1560MB 首装门槛被砍成 960MB",
            isReplacingExistingRootfs(rootfs)
        )
    }

    /** 目录存在且**非空** ⇒ true（真·升级：盘上已有一份旧 rootfs，只需一份可用空间）。 */
    @Test
    fun nonEmptyDirIsReplacing() = withDir("nonempty") { rootfs ->
        assertTrue(File(rootfs, "etc").mkdirs())
        File(rootfs, "etc/os-release").writeText("ID=debian\n")
        assertTrue(
            "目录非空 ⇒ replacing=true（走 960MB 升级门槛）",
            isReplacingExistingRootfs(rootfs)
        )
    }

    /**
     * **缺陷本体**：旧 rootfs **存在但损坏**（有内容、但没有 `bin/sh`）⇒ true。
     *
     * 旧口径 `rootfsLooksUsable()` 对这种树返回 **false**（三条路径一条都没有）⇒ 门槛跳到
     * 1560MB。真机 ~1.1GB 可用时：完好能升级、一坏就永久卡死。修后与 [healthyRootfsIsReplacing]
     * 给出**同一个**门槛 —— 这是本缺陷的核心断言，见 [damagedAndHealthyGetSameThreshold]。
     */
    @Test
    fun damagedRootfsCountsAsReplacing() = withDir("damaged") { rootfs ->
        // 损坏现场：目录里还剩一大堆文件（真机上约 900MB 的残骸），但 bin/sh 没了。
        assertTrue(File(rootfs, "etc").mkdirs())
        File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/sh\n")
        File(rootfs, "etc/os-release").writeText("ID=debian\n")
        File(rootfs, "var").mkdirs()
        assertFalse(
            "前置：这棵树必须被判为**不可用**（否则本用例没验证损坏现场）",
            com.dsh.core.DshPaths.rootfsLooksUsable(rootfs)
        )
        assertTrue(
            "★ 损坏 ≠ 不存在：有内容就必须判为升级 ⇒ 门槛 960MB，" +
                "否则 1.1GB 可用的真机永远过不去空间门（RE-07 本体）",
            isReplacingExistingRootfs(rootfs)
        )
    }

    /** 完好的旧 rootfs（有 `bin/sh`）⇒ true。与损坏现场同结论，门槛一致。 */
    @Test
    fun healthyRootfsIsReplacing() = withDir("healthy") { rootfs ->
        assertTrue(File(rootfs, "bin").mkdirs())
        File(rootfs, "bin/sh").writeText("#!/bin/sh\n")
        assertTrue(
            "前置：这棵树必须被判为可用",
            com.dsh.core.DshPaths.rootfsLooksUsable(rootfs)
        )
        assertTrue("完好 ⇒ replacing=true", isReplacingExistingRootfs(rootfs))
    }

    /** 传进来的是**普通文件**而不是目录 ⇒ false（防御：路径被人误配时不要静默当成有旧底座）。 */
    @Test
    fun regularFileIsNotReplacing() = withDir("file") { base ->
        val notADir = File(base, "rootfs")
        notADir.writeText("not a directory\n")
        assertTrue("前置：这是一个普通文件", notADir.isFile)
        assertFalse(
            "不是目录 ⇒ 不能当成「盘上有旧 rootfs」",
            isReplacingExistingRootfs(notADir)
        )
    }

    /**
     * 只有一个**空子目录**也算「非空」⇒ true。
     *
     * 为什么单独钉：判据若被误写成「有没有实质内容」（比如递归看文件数、或查 `bin/sh`），
     * 「上一次解压只建出了骨架就中断」这种现场就会漏判。`list()` 非空才是对的口径 ——
     * 目录里已经躺着一个条目，说明盘上确实有前一次留下的东西。
     */
    @Test
    fun singleEmptySubdirIsReplacing() = withDir("subdir") { rootfs ->
        assertTrue(File(rootfs, "usr").mkdirs())
        assertTrue(
            "只有一个空子目录 ⇒ 非空 ⇒ replacing=true（list() 口径，不是「有没有实质内容」）",
            isReplacingExistingRootfs(rootfs)
        )
    }

    // =====================================================================
    // 2) 门槛落点：损坏与完好必须给出**同一档**空间门槛
    // =====================================================================

    /**
     * **缺陷本体的数值断言**：完好树与损坏树走同一条分支 ⇒ `requiredFreeBytes` 同为 960 MiB。
     *
     * 修前：完好 ⇒ true ⇒ 960 MiB；损坏 ⇒ false ⇒ 1560 MiB。真机 ~1.1 GB 可用的机器上，
     * 「960 MiB ≤ 1.1 GB < 1560 MiB」⇒ 一坏就永久卡住。修后两者相同，1.1 GB 都放行。
     */
    @Test
    fun damagedAndHealthyGetSameThreshold() = withDir("threshold") { base ->
        val healthy = File(base, "rootfs-healthy")
        assertTrue(File(healthy, "bin").mkdirs())
        File(healthy, "bin/sh").writeText("#!/bin/sh\n")

        val damaged = File(base, "rootfs-damaged")
        assertTrue(File(damaged, "etc").mkdirs())
        File(damaged, "etc/passwd").writeText("root\n")

        val healthyNeed = DshBootstrap.requiredFreeBytes(isReplacingExistingRootfs(healthy))
        val damagedNeed = DshBootstrap.requiredFreeBytes(isReplacingExistingRootfs(damaged))
        assertEquals(
            "★ 完好的旧底座与损坏的旧底座必须要求同样的可用空间（都是 960 MiB）—— " +
                "修前损坏现场要多一份 node_modules，正是 1.1GB 真机死锁的成因",
            healthyNeed,
            damagedNeed
        )
        assertEquals("两者都应落在升级档 960 MiB", 960L * 1024 * 1024, damagedNeed)
    }

    /** 首装（空目录）仍要 1560 MiB —— 修 RE-07 不得顺手把首装门槛也砍掉。 */
    @Test
    fun freshInstallStillNeedsRootfsPlusNodeModules() = withDir("fresh-need") { rootfs ->
        assertTrue(rootfs.isDirectory)
        val need = DshBootstrap.requiredFreeBytes(isReplacingExistingRootfs(rootfs))
        assertEquals(
            "空目录（首装）必须仍要 rootfs + node_modules = 1560 MiB",
            960L * 1024 * 1024 + 600L * 1024 * 1024,
            need
        )
    }

    // =====================================================================
    // 3) 接线层：install() 真的用了新判据（源码切片，去注释）
    // =====================================================================

    /**
     * `requiredFreeBytes(...)` 的实参必须来自 [isReplacingExistingRootfs]，且安装路径上
     * **不再**把 `DshPaths.rootfsLooksUsable()` 当 `replacing`。
     *
     * 行为层全绿但没接上线 = 缺陷原样存在，所以这一条不能省。
     */
    @Test
    fun installWiresRequiredFreeBytesToTheNewJudgement() {
        val code = bootstrapCode()
        val assign = "val replacing = isReplacingExistingRootfs(File(DshPaths.ROOTFS_DIR))"
        val use = "requiredFreeBytes(replacing)"
        val assignAt = code.indexOf(assign)
        val useAt = code.indexOf(use)
        assertTrue(
            "找不到 `$assign`（源码结构变了，或接线被换掉了？）",
            assignAt >= 0
        )
        assertTrue("找不到 install() 里 `$use` 的调用", useAt >= 0)
        assertTrue(
            "replacing 的赋值必须在 requiredFreeBytes 之前（顺序被换了？）",
            assignAt < useAt
        )
        assertFalse(
            "★ 不得再有 `val replacing = DshPaths.rootfsLooksUsable()` —— 那是「可用性」判据，" +
                "把「存在但损坏」误判成首装（RE-07 本体）",
            code.contains("val replacing = DshPaths.rootfsLooksUsable()")
        )
    }

    /** 诊断日志必须在：`[bootstrap] space check replacing=… need=…MB free=…MB`（放行也要留痕）。 */
    @Test
    fun spaceCheckLogsReplacingAndBothNumbers() {
        val code = bootstrapCode()
        assertTrue(
            "空间检查必须打一行 [bootstrap] space check replacing=… need=…MB free=…MB —— " +
                "真机上「这次为什么按 960MB 算」只能靠它复现",
            code.contains("[bootstrap] space check replacing=")
        )
        assertTrue("日志里必须带 need", code.contains("need="))
        assertTrue("日志里必须带 free", code.contains("free="))
    }

    /**
     * `rootfsLooksUsable` 仍是「要不要重解压」的判据 —— 修 RE-07 **只换门槛的实参**，
     * 不动那条门（`!DshPaths.rootfsLooksUsable()` 负责"布局看起来坏 ⇒ 重解压"）。
     */
    @Test
    fun reExtractGateStillUsesRootfsLooksUsable() {
        val code = bootstrapCode()
        assertTrue(
            "`!DshPaths.rootfsLooksUsable()` 必须仍在（那是『要不要重解压』的判据，与空间门槛无关）",
            code.contains("!DshPaths.rootfsLooksUsable()")
        )
    }

    // =====================================================================
    // 夹具
    // =====================================================================

    /**
     * `DshBootstrap.kt` 的**去注释**源码。
     *
     * 必须去注释：本轮的 KDoc 为讲清缺陷必然复述 `rootfsLooksUsable()` / `exists()` 这些符号，
     * 不去注释的话切片断言会命中注释而非代码（`R31Fix211Test` 踩过，结论正好反过来）。
     */
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
