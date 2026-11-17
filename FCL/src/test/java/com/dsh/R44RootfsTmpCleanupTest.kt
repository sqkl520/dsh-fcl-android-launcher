package com.dsh

import com.dsh.core.DshPaths
import com.dsh.core.clearStaleRootfsTmp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * RE-09：`rootfs.tmp` 清理失败**完全静默**，把"空间不足"的假失败留给用户。
 *
 * ## 缺陷本体（修复前）
 *
 * `DshBootstrap.install` 在评估可用空间**之前**要清掉上次中断遗留的 `rootfs.tmp`
 * （BL-072：它占的盘会被算进"已占用"，不清就误拦）。那一行是裸的
 * `runCatching { File("…/rootfs.tmp").deleteRecursively() }`：
 * 异常被吞、`Boolean` 返回值被当语句丢掉。于是 SELinux 拒删 / 目录里有 root 属主文件 /
 * 被别的进程占着 时，用户得到的仍是 **"空间不足，需要 X，现有 Y"** —— 而真实原因是
 * "rootfs.tmp 没删掉"。界面既没有"删 rootfs.tmp"按钮，日志里也没有这条线索。
 *
 * ## 修法（本轮）
 *
 * 清理抽成 [clearStaleRootfsTmp]：三种结果分开处置，**只有"抛异常 / 没删干净"才报警**，
 * 且报警行带**绝对路径**（能照着 `rm -rf`）、异常摘要、以及"仍然存在时它占了多少"。
 * 空间检查本身的判据（`requiredFreeBytes` / `isReplacingExistingRootfs`）一个字未动。
 *
 * ## 为什么分两层测
 *
 * - **源码级**（[cleanupCallSiteGoesThroughTheDiagnosingHelper] /
 *   [helperLogsFailuresInsteadOfSwallowingThem]）：断言"接线"没被改回裸 `runCatching`、
 *   断言 `onFailure` 与"仍然存在"这条支路都在。只测行为的话，把生产代码改回裸 `runCatching`
 *   仍然能全绿（因为行为测试驱动的是 helper 自己的注入点）。
 * - **行为级**：真跑 [clearStaleRootfsTmp]，四种分支逐一钉死（不存在 / 删干净 / **删不掉** /
 *   **抛异常**）。
 *
 * ## "删不掉"怎么在 JVM 上造出来（两种，都试）
 *
 * 1. **真实文件系统**（[realUndeletableDirectoryIsReported]）：把 `.tmp` 做成非空目录，并让里面的
 *    文件被本进程 `RandomAccessFile` + `FileLock` 占住 —— Windows 上 `DeleteFile` 对
 *    有活动句柄的文件直接返回失败（实测：`deleteRecursively()` 返回 false、目录仍在）。
 *    注意**只读属性**在这里靠不住：实测 Windows 上 `File.setWritable(false)` 对**目录**返回
 *    false 且不改变任何东西，非空只读目录照样删得掉；`setReadOnly()` 的文件也会被
 *    `deleteRecursively()` 删掉。所以用文件锁，不用只读位。
 *    ⚠️ 平台依赖：POSIX 允许 unlink 一个仍被打开的文件，所以这一条在 Linux/macOS 上**构造不出**
 *    失败。那时用例自己打印一行说明并退化为"只验可删的那半"，**不假装失败**（见用例内的
 *    `SUPPORTED` 判断）。真正的判定覆盖由 2 保证，与平台无关。
 * 2. **注入点**（[undeletedDirectoryKeepsSizeDiagnostic] / [throwingDeletionIsLoggedWithSummary]）：
 *    [clearStaleRootfsTmp] 的 `deleteTree` / `sizeOf` / `log` 参数（与 [com.dsh.core.swapRootfsDirs]
 *    同一惯例）。真机上要造 ENOTEMPTY 得先有 fs 故障或 SELinux 策略，注入点才能**确定地**
 *    造出每个分支 —— 这两个用例在任何平台都跑同一条代码路径。
 *
 * 运行：`sh run-tests.sh`（自动扫描本类）
 */
class R44RootfsTmpCleanupTest {

    private val tmpRoot: File = Files.createTempDirectory("r44-rootfs-tmp").toFile()

    @After
    fun cleanUp() {
        // 夹具自己造的东西自己收：只读/被占的文件先解锁再删，否则临时目录残留。
        lock?.let { runCatching { it.release() } }
        lockFile?.let { runCatching { it.close() } }
        runCatching { tmpRoot.deleteRecursively() }
    }

    // 真实文件系统夹具（[realUndeletableDirectoryIsReported] 用）
    private var lockFile: RandomAccessFile? = null
    private var lock: java.nio.channels.FileLock? = null

    // =====================================================================
    // 一、源码级：接线不许退回裸 runCatching
    // =====================================================================

    /**
     * `install()` 里那段清理必须**走** [clearStaleRootfsTmp]，且必须仍然在空间检查**之前**。
     *
     * 两条都必要：
     * - 退回裸 `runCatching { …deleteRecursively() }` ⇒ 缺陷原样复活（异常与 false 都不留痕）；
     * - 清理挪到 `freeSpaceBytes()` 之后 ⇒ 更荒谬（先按"含 .tmp 的已占用"判死，再去删 .tmp）。
     *
     * ★ 必须**剥注释**再找：本仓有实测教训（`R40InstallForkGuardTest.deleteJoinsOutsideTheInstallerLock`
     * 的 `stripComments`）—— 注释里写着 `runCatching { …deleteRecursively() }` 的说明文字时，
     * 裸 `contains`/`indexOf` 会命中**注释**，得到一条永远为绿或永远为红的假断言。
     */
    @Test
    fun cleanupCallSiteGoesThroughTheDiagnosingHelper() {
        val body = stripComments(
            sliceFun(bootstrapSource(), "fun install(context: Context,", "private fun fail(emit:")
        )

        val callAt = body.indexOf("clearStaleRootfsTmp(File(")
        assertTrue(
            "★ install() 的可执行代码里必须调用 clearStaleRootfsTmp —— 找不到就意味着清理退回成了" +
                "裸 `runCatching { …deleteRecursively() }`（异常与 false 都不留痕，用户只会看到" +
                "一句假性的'空间不足'）。body=\n$body",
            callAt >= 0
        )

        // 裸 runCatching 包 deleteRecursively 的旧写法不许再出现在可执行代码里
        val bareDelete = Regex("""runCatching\s*\{\s*File\([^)]*\)\.deleteRecursively\(\)\s*\}""")
        assertFalse(
            "★ 可执行代码里不许再有裸 `runCatching { File(...).deleteRecursively() }`：" +
                "它把异常与 false 一起吞掉（RE-09 的成因）",
            bareDelete.containsMatchIn(body)
        )

        // 顺序：清理必须在空间检查（freeSpaceBytes / requiredFreeBytes）之前
        val spaceAt = listOf("DshPaths.freeSpaceBytes()", "requiredFreeBytes(")
            .map { body.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: -1
        assertTrue("前置：install() 里必须能定位到空间检查", spaceAt >= 0)
        assertTrue(
            "★ .tmp 清理必须在空间检查**之前**（否则先按'含 .tmp 的已占用'判死、再去删 .tmp）：" +
                "callAt=$callAt spaceAt=$spaceAt",
            callAt < spaceAt
        )
    }

    /**
     * helper 的骨架：必须有 `onFailure`，且必须**真的判"删完还在不在"**（只看 `deleteRecursively()`
     * 的布尔是不够的 —— 它返回 false 时其实已经把能删的删了，决定空间门槛的是路径还在不在，
     * 与 `swapRootfsDirs` 第 1/4 步同口径）。
     */
    @Test
    fun helperLogsFailuresInsteadOfSwallowingThem() {
        val body = stripComments(
            sliceFun(bootstrapSource(), "internal fun clearStaleRootfsTmp(", "fun readinessGaps(")
        )

        assertTrue(
            "★ clearStaleRootfsTmp 必须有 onFailure 分支（异常不许静默吞掉）",
            body.contains(".onFailure")
        )
        assertTrue(
            "★ 必须判'删完还在不在'（exists()），而不是只信 deleteRecursively() 的布尔",
            body.contains("tmpDir.exists()")
        )
        assertTrue(
            "★ 报警行必须带绝对路径（排障者要能照着 `rm -rf`）：缺 absolutePath ⇒ 日志里只有一个" +
                "裸名字，对不上现场",
            body.contains("tmpDir.absolutePath")
        )
        assertTrue(
            "★ 报警行必须带'仍然存在时它占了多少'（体积诊断），否则'空间不足'与'没删掉的 .tmp'" +
                "这两个事实在日志里对不上号",
            body.contains("sizeOf(")
        )
    }

    // =====================================================================
    // 二、行为级：四种分支
    // =====================================================================

    /** 本来就不存在 ⇒ 正常路径，**一个字都不记**（大多数启动都走这条，刷日志会淹没真问题） */
    @Test
    fun missingTmpLogsNothing() {
        val lines = mutableListOf<String>()
        val ok = clearStaleRootfsTmp(File(tmpRoot, "absent.tmp"), log = { lines += it })
        assertTrue("没有 .tmp ⇒ 结论是'干净'", ok)
        assertEquals("没有 .tmp ⇒ 不许留任何日志（正常路径）", emptyList<String>(), lines)
    }

    /** 删干净了 ⇒ 结论 true，且不留报警行 */
    @Test
    fun cleanDeletionLogsNothing() {
        val dir = File(tmpRoot, "rootfs.tmp").apply { mkdirs(); File(this, "a").writeText("x") }
        val lines = mutableListOf<String>()
        val ok = clearStaleRootfsTmp(dir, log = { lines += it })
        assertTrue("删干净 ⇒ true", ok)
        assertFalse("目录必须真的消失", dir.exists())
        assertEquals("正常删干净不许刷报警行", emptyList<String>(), lines)
    }

    /**
     * **没删干净**（`deleteRecursively()` 返回 false，不是抛异常）⇒ 必须留痕，且带上
     * 绝对路径与"它占了多少" —— 这正是"空间不足"的假失败来源。
     */
    @Test
    fun undeletedDirectoryKeepsSizeDiagnostic() {
        val dir = File(tmpRoot, "rootfs.tmp").apply { mkdirs() }
        File(dir, "blob").writeBytes(ByteArray(3 * 1024 * 1024))
        val lines = mutableListOf<String>()
        val ok = clearStaleRootfsTmp(
            dir,
            log = { lines += it },
            sizeOf = { DshPaths.dirSize(it) },
            // 什么都不删、返回 false：模拟 SELinux 拒删 / root 属主文件（Windows 上难稳定构造）
            deleteTree = { false }
        )
        assertFalse("清理后仍然存在 ⇒ 必须返回 false（空间检查会把它算进已占用）", ok)
        val joined = lines.joinToString("\n")
        assertTrue("必须有报警行：$joined", lines.isNotEmpty())
        assertTrue(
            "报警行必须带**绝对路径**（照着就能 rm -rf）：$joined",
            joined.contains(dir.absolutePath)
        )
        assertTrue(
            "报警行必须报出它仍占多少（3MB 的 .tmp 就是空间不足的成因）：$joined",
            joined.contains("3.0 MB") || joined.contains("3072 KB") || joined.contains("3145728")
        )
        assertTrue(
            "报警行必须说明后果（下面的空间检查会把它算进已占用）与可操作动作（rm -rf）：$joined",
            joined.contains("space check") && joined.contains("rm -rf")
        )
    }

    /** **抛异常**（SELinux 拒删的典型形态）⇒ 报警行必须含异常摘要，不许静默 */
    @Test
    fun throwingDeletionIsLoggedWithSummary() {
        val dir = File(tmpRoot, "rootfs.tmp").apply { mkdirs() }
        val lines = mutableListOf<String>()
        val ok = clearStaleRootfsTmp(
            dir,
            log = { lines += it },
            sizeOf = { 1234L },
            deleteTree = { throw SecurityException("SELinux denied unlink") }
        )
        assertFalse("抛异常后目录仍在 ⇒ false", ok)
        val joined = lines.joinToString("\n")
        assertTrue(
            "★ 异常必须留痕（旧实现整个吞掉，排障者拿不到任何线索）：$joined",
            joined.contains("SecurityException")
        )
        assertTrue("摘要里要有异常 message：$joined", joined.contains("SELinux denied unlink"))
        assertTrue("必须有绝对路径：$joined", joined.contains(dir.absolutePath))
    }

    /** 报 false 但路径确实没了 ⇒ 不算失败（结论是"没了"），但留一行说明，免得后人对着 false 猜 */
    @Test
    fun reportedFalseButGoneIsNotAFailure() {
        val dir = File(tmpRoot, "rootfs.tmp").apply { mkdirs() }
        val lines = mutableListOf<String>()
        val ok = clearStaleRootfsTmp(
            dir,
            log = { lines += it },
            deleteTree = { dir.deleteRecursively(); false }  // 删掉了但谎报 false
        )
        assertTrue("路径已消失 ⇒ 结论是'干净'", ok)
        assertFalse("目录必须真的消失", dir.exists())
        assertTrue("要留一行说明（否则后人只能对着一个 false 猜）", lines.isNotEmpty())
        assertTrue("说明里要有绝对路径", lines.joinToString("\n").contains(dir.absolutePath))
    }

    /**
     * **真实文件系统**的"删不掉"：`.tmp` 是非空目录，里面的文件被本进程持有并加了排他锁。
     *
     * 平台说明见类 KDoc：Windows 上 `deleteRecursively()` 返回 false 且目录仍在（实测）；
     * POSIX 允许 unlink 打开中的文件 ⇒ 这一条在 Linux/macOS 上构造不出失败。此时用例**如实
     * 打印"本平台构造不出"并只验证可删的那半**，不假装红、也不假装覆盖到了 —— 分支判定由
     * [undeletedDirectoryKeepsSizeDiagnostic] / [throwingDeletionIsLoggedWithSummary] 保证。
     */
    @Test
    fun realUndeletableDirectoryIsReported() {
        val dir = File(tmpRoot, "rootfs.tmp").apply { mkdirs() }
        val victim = File(dir, "held.bin").apply { writeBytes(ByteArray(2048)) }
        lockFile = RandomAccessFile(victim, "rw")
        lock = lockFile!!.channel.lock()
        try {
            val lines = mutableListOf<String>()
            val ok = clearStaleRootfsTmp(dir, log = { lines += it })
            if (!dir.exists()) {
                // 本平台不阻塞删除（POSIX）：如实说明，不伪装成"测过了"。
                println(
                    "  [r44] 注意：本平台允许删除被打开/加锁的文件，" +
                        "realUndeletableDirectoryIsReported 的失败分支构造不出（见类 KDoc）"
                )
                assertTrue("删得掉就必须报'干净'", ok)
                return
            }
            assertFalse("删不掉就必须返回 false（空间检查会把它算进已占用）", ok)
            val joined = lines.joinToString("\n")
            assertTrue("删不掉必须留痕：$joined", lines.isNotEmpty())
            assertTrue("必须带绝对路径：$joined", joined.contains(dir.absolutePath))
            assertTrue(
                "必须在日志里说明'仍然存在'与后果：$joined",
                joined.contains("still present") && joined.contains("space check")
            )
        } finally {
            runCatching { lock?.release() }
            runCatching { lockFile?.close() }
            lock = null
            lockFile = null
            dir.setWritable(true)
            runCatching { dir.deleteRecursively() }
        }
    }

    // =====================================================================
    // 工具：源码切片 + 剥注释
    // =====================================================================

    private fun bootstrapSource(): String = mainSourceFile("core/DshBootstrap.kt").readText()

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
     * 切出一个函数体：取 [header] 之后、[nextHeader] 之前那一段。
     *
     * 与 `R40InstallForkGuardTest.sliceFun` 同一手法（行首锚点，不配对大括号）：KDoc 里有单个
     * 撇号与反引号，大括号配对扫描会被字符串/注释里的符号带偏。
     */
    private fun sliceFun(src: String, header: String, nextHeader: String): String {
        val start = src.indexOf(header)
        if (start < 0) throw AssertionError("找不到 $header")
        val end = src.indexOf(nextHeader, start + header.length)
        if (end < 0) throw AssertionError("找不到 $header 之后的下一个成员声明：$nextHeader")
        return src.substring(start, end)
    }

    /**
     * 剥掉行注释与块注释（逐字照抄 `R40InstallForkGuardTest.stripComments`）。
     *
     * ⚠️ 本 KDoc **不许写出块注释的起始符与结束符连写**：Kotlin 的块注释可嵌套，
     * 写一次就会在这里打开/闭合一个注释，把下面整段代码吞掉
     * （实测踩过：报 `Unclosed comment` 与 `syntax error: Missing '}'`）。
     * 所以下面描述时一律用文字说"斜杠星号"，不写那两个字符的连写。
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
