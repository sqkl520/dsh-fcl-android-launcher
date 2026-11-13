package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.Base64
import java.util.Locale

/**
 * R31 / s10-eh-04 + s10-eh-05：`RuntimeUtils` 的两条「失败被降级成日志」缺陷。
 *
 * ## 这组钉的是「可判」，不是「能跑」
 *
 * 两条缺陷的共同点不是"代码跑不起来"，而是**失败之后世界看起来仍然正常**：
 *
 * - **s10-eh-04**：`copyAssetsInternal` 的读/写循环没有 try-with-resources，也没有 `.tmp`。
 *   写盘失败（首启解压 rootfs 时 ENOSPC 最现实）时两条流不关、目标路径上留一个 0 字节或半截
 *   文件，而 `isLatest` 只看 `version` 文件 —— 半成品既不报错也不会被下次重解压识别。
 * - **s10-eh-05**：符号链接 / 可执行位 / 硬链接的逐条 `catch (Throwable) { WARNING }` 之后
 *   循环继续、方法正常返回，调用方看到的是"解压成功"。作者自己在 `:245-251` 的注释里写着
 *   这类失败的后果是"proot 第一次 execve 就 EACCES，底座永远不就绪"。
 *
 * 所以断言全部落在**失败路径的可观察产物**上：流有没有关、目录里剩了什么、返回值能不能
 * 区分「全部成功」与「部分失败」。
 *
 * ## 为什么要绕一层类加载器
 *
 * `RuntimeUtils` 的方法签名里带 `org.apache.commons.compress.*`，而本仓的测试 harness
 * （`run-tests.sh`）的 classpath 只有 stdlib/coroutines/gson/android.jar —— **不包含**
 * commons-compress 与 xz。在 harness 里 `Class.forName("...RuntimeUtils")` 会直接
 * `NoClassDefFoundError`（实测：`XZCompressorInputStream` 找不到）。
 *
 * 于是这里不直接引用 `RuntimeUtils`，而是用 `java.class.path` + Gradle 缓存里的
 * commons-compress/xz/commons-io 拼一个子类加载器，全部走反射。这样同一份测试在
 * 「harness（无 compress 依赖）」和「Gradle 单测（有依赖）」两种环境下都能跑。
 *
 * [r31-vi-11 / r31-vi-12] 原来的收集器递归扫整个缓存、只认 `commons-compress` 与 `xz-`、
 * 且不排序、把所有版本都塞进 URL 列表，造成两处环境地雷：
 * 1. 声明版本 `commons-compress 1.26.0` 的 `TarArchiveInputStream` 引用
 *    `org.apache.commons.io.*`，而 commons-io 没被收进来；只有声明版本的机器上 3/8 必然
 *    `NoClassDefFoundError`（本机原来绿，只是因为缓存里 1.21 恰好排在 1.26.0 前面）。
 * 2. 命中哪个版本取决于 `File.listFiles()` 顺序，不同机器上不同。
 *
 * 现在按 `gradle/libs.versions.toml` 的声明版本**逐个 artifact 精确取件**（每个只取一个 jar），
 * 排序规则固定为「声明版本 > 其它版本（数值降序） > 绝对路径升序」。缺件时**明确失败**
 * 并列出缺哪个、去哪找，绝不静默跳过。
 *
 * ## 生产路径不是"抽出来的辅助方法"
 *
 * [r31-vi-13] 只驱动 `copyAssetStream` 时，`copyAssetsInternal` 改回裸循环仍然全绿（M3）。
 * 下面的 `copyAssetsProductionPath*` 两条通过 `javax.tools.JavaCompiler` 在**临时目录**里编译
 * 最小 Android 桩（`Context` / `AssetManager` / `Os`）+ **工作区原样的 RuntimeUtils.java**，
 * 用子类加载器优先加载这份桩，再反射驱动真正的公开入口 `copyAssets(Context,String,String)`。
 * 被测生产源码一个字节都不改，驱动的是生产路径本身，不是形状匹配。
 */
class R31Fix204Test {

    // -----------------------------------------------------------------
    // 1. s10-eh-05：返回值必须能区分「全部成功」与「部分失败」
    // -----------------------------------------------------------------

    /**
     * 干净的 tar（只有一条普通文件）⇒ `hasFailures() == false`。
     *
     * 这条是**对照**：如果只看"失败能报出来"，一个永远返回失败的实现也能过。必须同时证明
     * 全成功路径不会被误报 —— 否则下一步 `DshBootstrap` 一旦据此判失败，正常装机也会被打断。
     */
    @Test
    fun cleanArchiveReportsNoFailures() {
        val runtime = runtimeUtilsClass()
        val dest = Files.createTempDirectory("r31-204-clean").toFile()
        try {
            val report = extract(runtime, decodeFixture(CLEAN_TAR_XZ), dest)

            assertEquals(
                "干净 tar 不得报失败（否则下游会把正常解压判成坏包）：" + describe(report),
                0,
                failureCount(report)
            )
            assertFalse("hasFailures() 必须与 failureCount()==0 一致", hasFailures(report))
            assertNull("无失败时首个失败摘要必须为 null", firstFailure(report))
            assertTrue("普通文件必须真的被解出来", File(dest, GOOD_TXT).isFile)
            assertEquals(
                "解出来的内容必须与包内一致",
                "hello-r31",
                File(dest, GOOD_TXT).readText()
            )
        } finally {
            dest.deleteRecursively()
        }
    }

    /**
     * ★ 核心断言：符号链接条目建不出来时，整轮**必须**能被判成"部分失败"。
     *
     * 在 harness 上这条链接必然失败（`android.system.Os` 是 android.jar 的桩，`symlink`
     * 直接抛 `RuntimeException: Stub!`），恰好就是"子项失败"这个场景的确定性构造。
     *
     * 修复前：`uncompressTarXZ` 返回 void，调用方只能看到"跑完了"——本断言无从表达。
     * 修复后：`hasFailures()` 为真、`getFailedLinks() >= 1`、摘要里带条目名。
     */
    @Test
    fun symlinkFailureMakesTheRoundPartiallyFailed() {
        val runtime = runtimeUtilsClass()
        val dest = Files.createTempDirectory("r31-204-sym").toFile()
        try {
            val report = extract(runtime, decodeFixture(SYMLINK_TAR_XZ), dest)

            assertTrue(
                "符号链接建不出来必须让整轮成为「部分失败」，不能只写一行 WARNING 就当成功：" + describe(report),
                hasFailures(report)
            )
            assertTrue(
                "失败计数必须 >= 1（实际 describe: " + describe(report) + "）",
                failureCount(report) >= 1
            )
            assertEquals(
                "这条包里的链接失败恰好 1 条（实际 describe: " + describe(report) + "）",
                1,
                failedLinks(report)
            )
            assertEquals(
                "这条包里没有可执行位失败（实际 describe: " + describe(report) + "）",
                0,
                failedExecutableBits(report)
            )

            val first = firstFailure(report)
            assertNotNull("必须留下首个失败摘要，否则排障时只知道「有失败」不知道是哪条", first)
            assertTrue(
                "首个失败摘要必须点名条目（实际：<$first>）",
                first!!.contains(BAD_LINK)
            )

            // 容错策略不得改变：一条链接失败**不许**中断整轮，其余条目必须照常解出来。
            assertTrue(
                "单个链接失败不得中断整轮解压 —— 同包里的普通文件仍必须被解出来",
                File(dest, GOOD_TXT).isFile && File(dest, GOOD_TXT).readText() == "hello-r31"
            )
        } finally {
            dest.deleteRecursively()
        }
    }

    /**
     * 硬链接退化（建不出来但复制成功）**不算失败**，但要单独可见。
     *
     * 这条防的是"修过度"：把退化也计进失败会让不支持硬链接的文件系统上整轮恒红，
     * 而那是一个内容正确、可以继续用的底座。所以两件事必须分开：
     * `getFailureCount()` 不带退化，`getDegradedHardLinks()` 单独计。
     */
    @Test
    fun hardLinkDegradationIsVisibleButNotAFailure() {
        val runtime = runtimeUtilsClass()
        val dest = Files.createTempDirectory("r31-204-hard").toFile()
        try {
            val report = extract(runtime, decodeFixture(HARDLINK_TAR_XZ), dest)

            assertEquals(
                "硬链接退化复制不得计入失败（否则不支持硬链接的 fs 上会假失败）：" + describe(report),
                0,
                failureCount(report)
            )
            assertFalse("hasFailures() 不得因退化而为真：" + describe(report), hasFailures(report))
            assertEquals(
                "这条包里没有可执行位失败（实际 describe: " + describe(report) + "）",
                0,
                failedExecutableBits(report)
            )

            val degraded = degradedHardLinks(report)
            if (degraded > 0) {
                // harness 上 Os.link 是桩，必然走退化分支；此时退化必须被"看见"
                assertTrue(
                    "退化发生了就必须在 describe() 里说出来（实际：<" + describe(report) + ">）",
                    describe(report).contains("degraded")
                )
                assertTrue(
                    "退化之后内容必须仍然正确（这是它不算失败的前提）",
                    File(dest, HARD_TXT).isFile && File(dest, HARD_TXT).readText() == "hello-r31"
                )
            } else {
                // 真机/有 Os.link 实现的环境：真硬链接成立，退化计数为 0 也是对的
                assertTrue("硬链接直接建成时也必须把内容落到目标路径", File(dest, HARD_TXT).isFile)
            }
        } finally {
            dest.deleteRecursively()
        }
    }

    // -----------------------------------------------------------------
    // 1b. r31-vi-14：可执行位计数的下游链路（部分覆盖，见下方说明）
    // -----------------------------------------------------------------

    /**
     * [r31-vi-14] `failedExecutableBits` 必须喂给 `getFailureCount()` / `hasFailures()` /
     * `describe()`。这里直接反射驱动 `ExtractReport.recordExecutableBitFailure` 把计数链路闭合。
     *
     * ★ 必须说清边界：这条**不能**抓住 M4（把 `:415` 的
     * `if (!restoreExecutableBit(...)) report.recordExecutableBitFailure(...)` 退回单调用）。
     * 在本机（JDK 17 / Windows）走真实 `uncompressTarXZ` 让 `restoreExecutableBit` 返回 false
     * 需要改 JVM 私有状态或依赖平台偶然行为，已按派发方指令明确排除。故 `r31-vi-14` 在
     * 本轮报告中判 **NOT_FIXED**，这条只保证计数链路本身不被改动，见报告 §5。
     */
    @Test
    fun executableBitFailureCountFeedsHasFailures() {
        val runtime = runtimeUtilsClass()
        val reportClass = runtime.declaredClasses.firstOrNull { it.simpleName == "ExtractReport" }
        assertNotNull("RuntimeUtils 必须内嵌 ExtractReport", reportClass)
        val ctor = reportClass!!.getDeclaredConstructor()
        ctor.isAccessible = true
        val report = ctor.newInstance()

        assertEquals("干净报告的可执行位失败数必须是 0", 0, failedExecutableBits(report))
        assertFalse("干净报告不得判失败", hasFailures(report))

        val record = reportClass.getDeclaredMethod("recordExecutableBitFailure", String::class.java)
        record.isAccessible = true
        record.invoke(report, "exec-bit bin/sh")

        assertEquals("记一次可执行位失败后 failedExecutableBits 必须是 1", 1, failedExecutableBits(report))
        assertEquals("它必须计入 failureCount", 1, failureCount(report))
        assertTrue("它必须让 hasFailures() 为真", hasFailures(report))
        assertTrue(
            "describe() 必须点名 executable-bit=1（实际：<" + describe(report) + ">）",
            describe(report).contains("executable-bit=1")
        )
        assertTrue("首个失败摘要必须点名条目", (firstFailure(report) ?: "").contains("bin/sh"))
    }

    // -----------------------------------------------------------------
    // 2. s10-eh-04：拷贝失败必须关流、且不留无标记的半成品
    // -----------------------------------------------------------------

    /**
     * 成功路径：内容完整、目录里**不留** `.tmp`。
     *
     * `.tmp` 残留会让下次 `renameTo` 覆盖一个来路不明的中间文件；更要紧的是它会混进
     * 根目录清单，让"目录里有什么"这个判据不再等于"应该有什么"。
     */
    @Test
    fun copyWritesWholeContentAndLeavesNoTmp() {
        val runtime = runtimeUtilsClass()
        val dir = Files.createTempDirectory("r31-204-copy-ok").toFile()
        try {
            val dest = File(dir, "payload.bin")
            copyStream(runtime, ByteArrayInputStream("hello-r31".toByteArray()), dest)

            assertEquals("拷贝后的内容必须与源一致", "hello-r31", dest.readText())
            assertFalse("成功路径不得留下 .tmp 半成品", File(dir, "payload.bin.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * ★ 核心断言一：读流在中途抛 `IOException`（`ENOSPC` 的等价形态）时
     *
     * 1. 异常必须抛出去（失败可观测 —— `copyAssets`/`install` 都声明 `throws IOException`）；
     * 2. 目标路径**不得**留下半成品：原来 `FileOutputStream` 一构造就把目标截断成 0 字节，
     *    抛错后那个 0 字节文件就留在那儿，`isLatest` 只凭 `version` 判版本，永远不会重解压它；
     * 3. 不得留下 `.tmp`。
     */
    @Test
    fun copyFailureLeavesNoHalfFileBehind() {
        val runtime = runtimeUtilsClass()
        val dir = Files.createTempDirectory("r31-204-copy-fail").toFile()
        try {
            val dest = File(dir, "payload.bin")
            val boom = FailingInputStream(afterBytes = 4, message = "simulated ENOSPC")

            var thrown: Throwable? = null
            try {
                copyStream(runtime, boom, dest)
            } catch (t: Throwable) {
                thrown = t
            }

            assertNotNull("写盘失败必须抛出来，不能被吞成一行日志", thrown)
            assertTrue(
                "抛出的应是 IOException（调用链上 copyAssets/install 都是 throws IOException）" +
                    "，实际：<$thrown>",
                thrown is IOException
            )
            assertFalse(
                "失败后目标路径上不得存在文件 —— 0 字节或半截文件会被当成\"看起来完整\"，" +
                    "而 isLatest 只凭 version 判版本，永远不会重解压它",
                dest.exists()
            )
            assertFalse("失败路径必须清掉 .tmp，不留无标记的中间物", File(dir, "payload.bin.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * ★ 核心断言二：失败时**输入流必须被关闭**。
     *
     * 这是缺陷 A 的原始形态：`is.close()` / `fos.close()` 写在循环之后，异常路径上一条都不执行。
     * rootfs 首解要读几万个小文件，ENOSPC 一旦发生，泄漏的就是这些句柄，直到 GC 才回收；
     * 而 `FileOutputStream.close()` 才是刷盘保证。
     *
     * 用"抛错后 close 有没有被调用"直接测，比看代码里有没有写 try-with-resources 更硬 ——
     * 后者是形状，前者是行为。
     */
    @Test
    fun copyFailureStillClosesTheInputStream() {
        val runtime = runtimeUtilsClass()
        val dir = Files.createTempDirectory("r31-204-copy-close").toFile()
        try {
            val dest = File(dir, "payload.bin")
            val boom = FailingInputStream(afterBytes = 3, message = "simulated ENOSPC")

            runCatching { copyStream(runtime, boom, dest) }

            assertTrue(
                "读/写抛异常后输入流必须已关闭 —— 原实现把 close() 写在循环之后，异常路径上不执行，" +
                    "首解 rootfs 时一次 ENOSPC 就会泄漏掉已打开的全部句柄",
                boom.closed
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 覆盖已有文件时失败：目标是"上一次的完整文件"，必须**保持原样**。
     *
     * 这条限定了第 3 条断言的边界：不是"失败就要让目标消失"，而是"失败不许让目标变成
     * 一个看起来完整的坏文件"。已有好文件时改写失败，保留旧内容比留半截新内容安全。
     */
    @Test
    fun copyFailureKeepsExistingFileIntact() {
        val runtime = runtimeUtilsClass()
        val dir = Files.createTempDirectory("r31-204-copy-keep").toFile()
        try {
            val dest = File(dir, "payload.bin")
            dest.writeText("PREVIOUS-CONTENT")

            var thrown: Throwable? = null
            try {
                copyStream(runtime, FailingInputStream(afterBytes = 2, message = "simulated ENOSPC"), dest)
            } catch (t: Throwable) {
                thrown = t
            }

            // 证明这条断言不是空转：假输入流必然抛 IOException，所以"没抛"就等于没真的走拷贝路径
            // （例如 copyAssetStream 被改名/删掉时反射会抛 NoSuchMethodException —— 那不算通过）。
            assertNotNull("写盘失败必须抛出来（否则说明根本没走到拷贝路径）", thrown)
            assertTrue(
                "抛出的应是 IOException 而非反射错误 —— 实际：<$thrown>",
                thrown is IOException
            )
            assertTrue("失败后目标文件必须还在（上一次的完整副本）", dest.isFile)
            assertEquals(
                "失败后目标文件内容必须仍是上一次的完整内容 —— 不得被截断成半截新内容",
                "PREVIOUS-CONTENT",
                dest.readText()
            )
            assertFalse("失败路径必须清掉 .tmp", File(dir, "payload.bin.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // -----------------------------------------------------------------
    // 2b. r31-vi-13：驱动**生产路径** copyAssetsInternal，而不只是辅助方法
    // -----------------------------------------------------------------

    /**
     * ★ [r31-vi-13] 生产入口 `copyAssets(Context,String,String)` 在写盘中途失败时：
     * 异常必须冒出来、目标路径不得留半成品、不得留 `.tmp`、输入流必须已关闭。
     *
     * 这些断言打在 `copyAssetsInternal` 真正跑起来的那条路径上。把该路径改回 HEAD 的裸循环
     * （`copyAssetStream` 方法保留）时，宿主 JVM 里的 M3 变异会让子进程输出
     * `thrown=false destExists=true tmpExists=false closed=false` → 本用例红。
     */
    @Test
    fun copyAssetsProductionPathClosesStreamAndLeavesNoHalfFile() {
        val line = runInChildJvm(PRODUCTION_PROBE_MARKER)
        assertTrue("生产路径探针未跑完（输出：<$line>）", line.startsWith("R31-COPY-PROBE "))
        assertEquals(
            "copyAssets 生产路径必须：抛 IOException / 目标不留半成品 / 不留 .tmp / 输入流已关闭",
            "thrown=IOException destExists=false tmpExists=false closed=true",
            line.removePrefix("R31-COPY-PROBE ")
        )
    }

    /** 同一条生产路径的"已有好文件"边界：改写失败时旧文件必须逐字节保持原样。 */
    @Test
    fun copyAssetsProductionPathKeepsPreviousFileIntact() {
        val line = runInChildJvm(PRODUCTION_KEEP_PROBE_MARKER)
        assertTrue("生产路径（保留旧文件）探针未跑完（输出：<$line>）", line.startsWith("R31-COPY-KEEP-PROBE "))
        assertEquals(
            "copyAssets 生产路径改写失败时，旧文件必须保持原样且不留 .tmp",
            "thrown=IOException content=PREVIOUS-CONTENT tmpExists=false",
            line.removePrefix("R31-COPY-KEEP-PROBE ")
        )
    }

    // -----------------------------------------------------------------
    // 3. 形状守卫（辅助，不是唯一拦截）
    // -----------------------------------------------------------------

    /**
     * `RuntimeUtils.java` 里不得再出现"循环后两条裸 close"的老形态，且解压必须返回报告。
     *
     * [r31-vi-13] 这条**不再是唯一拦截**：生产路径的行为断言见 §2b。留着它是因为它便宜且能
     * 把最常见的回退变成 obviously wrong；但字面串匹配本身不算判别力证据。
     */
    @Test
    fun sourceNoLongerHasTheUnguardedCopyLoop() {
        val src = resolveMain("util/RuntimeUtils.java")
        assertNotNull(
            "找不到 RuntimeUtils.java —— 请在仓库根或 FCL/ 目录下运行测试",
            src
        )
        val text = src!!.readText()

        assertFalse(
            "不得再有「循环结束后裸调 is.close(); fos.close();」的写法 —— 异常路径上一条都不会执行",
            text.contains("is.close();") || text.contains("fos.close();")
        )
        assertTrue(
            "拷贝必须走独立的 copyAssetStream（try-with-resources + .tmp + 成功后原子替换）",
            text.contains("copyAssetStream(")
        )
        assertTrue(
            "解压返回值必须是 ExtractReport —— 否则调用方无从区分「全部成功」与「部分失败」",
            text.contains("public static ExtractReport uncompressTarXZ(")
        )
        assertFalse(
            "解压的子项失败不得再只写一行 e.getMessage()（getMessage() 为 null 时日志退化成一行 \"null\"）",
            text.contains("Logging.LOG.log(Level.WARNING, e.getMessage());")
        )
    }

    // -----------------------------------------------------------------
    // 3b. r31-vi-11 / r31-vi-12：类加载器的取件规则本身也要被测
    // -----------------------------------------------------------------

    /**
     * [r31-vi-11] 只放声明版本依赖（compress 1.26.0 + xz 1.9 + commons-io 2.15.1）的
     * Gradle home 里，`TarArchiveInputStream` 必须能从**该 home** 加载、且不再缺 commons-io。
     *
     * 这一条直接复现验收方的干净机器实验，但把结论反过来钉住：fake home 下必须绿。
     */
    @Test
    fun loadsWithOnlyTheDeclaredDependencyVersions() {
        val root = Files.createTempDirectory("r31-209-cleanhome").toFile()
        try {
            val cache = File(root, ".gradle/caches/modules-2/files-2.1")
            copyCachedJar(cache, "org.apache.commons/commons-compress/1.26.0", "commons-compress-1.26.0.jar")
            copyCachedJar(cache, "org.tukaani/xz/1.9", "xz-1.9.jar")
            copyCachedJar(cache, "commons-io/commons-io/2.15.1", "commons-io-2.15.1.jar")

            val found = resolveDependencyJars(cache)
            assertEquals("三个 artifact 各取一个 jar", 3, found.size)
            assertTrue(
                "commons-io 必须进 classpath（1.26.0 的 TarArchiveInputStream 引用 org.apache.commons.io.*）：" +
                    found.map { it.absolutePath },
                found.any { it.name.startsWith("commons-io-") }
            )
            assertTrue(
                "所有 jar 必须来自这个 fake gradle home（不得借本机缓存兜底）：" + found.map { it.absolutePath },
                found.all { it.absolutePath.startsWith(root.absolutePath) }
            )

            val loader = buildChildLoader(found)
            try {
                val tarClass = Class.forName("org.apache.commons.compress.archivers.tar.TarArchiveInputStream", true, loader)
                assertTrue(
                    "TarArchiveInputStream 必须从 fake home 的 1.26.0 加载",
                    tarClass.protectionDomain.codeSource.location.toString().contains(root.name)
                )
                // 关键：1.26.0 里那条 commons-io 引用必须真的能被解析（不是只看 jar 在不在列表里）
                Class.forName("org.apache.commons.io.input.NullInputStream", true, loader)
                val runtime = Class.forName("com.tungsten.fcl.util.RuntimeUtils", true, loader)
                val dest = Files.createTempDirectory("r31-209-clean-extract").toFile()
                try {
                    val report = extract(runtime, decodeFixture(CLEAN_TAR_XZ), dest)
                    assertFalse("干净 tar 在只有声明版本的 fake home 下不得报失败", hasFailures(report))
                    assertTrue("干净 tar 在 fake home 下必须真的解出文件", File(dest, GOOD_TXT).isFile)
                } finally {
                    dest.deleteRecursively()
                }
            } finally {
                // [r31-vi-16] 必须先关 loader 再删目录：URLClassLoader 会把 jar 文件句柄一直拿到
                // close() 为止，Windows 上未关闭的句柄让下面的 deleteRecursively() **静默失败**
                // （Kotlin 的 deleteRecursively 返回 Boolean、不抛异常），每次运行泄漏约 1.6MB。
                loader.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * [r31-vi-12] 缓存里同时存在声明版本与干扰版本时，必须**只取声明版本**，且两次解析结果
     * 完全一致（不依赖 `File.listFiles()` 顺序）。
     */
    @Test
    fun picksTheDeclaredVersionWhenAnOlderOneIsAlsoPresent() {
        val root = Files.createTempDirectory("r31-209-mixedhome").toFile()
        try {
            val cache = File(root, ".gradle/caches/modules-2/files-2.1")
            copyCachedJar(cache, "org.apache.commons/commons-compress/1.26.0", "commons-compress-1.26.0.jar")
            // 干扰版本：优先用本机真有的 1.21（验收方实测的那个），没有就用一个合成的更旧版本号。
            // 内容用 1.26.0 的 jar 只是为了"文件名/版本目录不同、字节可以随便"，
            // 排序规则只看目录名里的版本号 —— 选中 1.26.0 才是断言的目标。
            val realOld = findCachedJar("org.apache.commons/commons-compress/1.21")
            val decoyVersion = if (realOld != null) "1.21" else "0.9"
            val decoy = File(
                cache,
                "org.apache.commons/commons-compress/$decoyVersion/aaaaaaaaaaaa/commons-compress-$decoyVersion.jar"
            )
            decoy.parentFile.mkdirs()
            (realOld ?: requireCachedJar("org.apache.commons/commons-compress/1.26.0"))
                .copyTo(decoy, overwrite = true)
            copyCachedJar(cache, "org.tukaani/xz/1.9", "xz-1.9.jar")
            copyCachedJar(cache, "commons-io/commons-io/2.15.1", "commons-io-2.15.1.jar")

            val first = resolveDependencyJars(cache)
            val second = resolveDependencyJars(cache)
            assertEquals("两次解析的结果必须完全一致", first.map { it.absolutePath }, second.map { it.absolutePath })
            assertEquals("三个 artifact 各取一个 jar（不得把多个版本一起塞进 classpath）", 3, first.size)
            assertEquals(
                "必须选中声明版本 1.26.0 而不是 1.21",
                "commons-compress-1.26.0.jar",
                first.first { it.name.startsWith("commons-compress-") }.name
            )
            val loader = buildChildLoader(first)
            try {
                val tarClass = Class.forName("org.apache.commons.compress.archivers.tar.TarArchiveInputStream", true, loader)
                assertTrue(
                    "实际加载到的必须是 1.26.0 的 class，不是 1.21",
                    tarClass.protectionDomain.codeSource.location.toString().contains("1.26.0")
                )
            } finally {
                // [r31-vi-16] 同 loadsWithOnlyTheDeclaredDependencyVersions：不 close 则 Windows 上
                // 下面的 deleteRecursively() 静默失败，目录永久残留。
                loader.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // -----------------------------------------------------------------
    // 夹具与反射
    // -----------------------------------------------------------------

    /** 声明版本从 `gradle/libs.versions.toml` 读，不硬编码到取件规则里。 */
    private fun declaredVersions(): Map<String, String> {
        val toml = resolveUnder("gradle/libs.versions.toml")
        assertNotNull("找不到 gradle/libs.versions.toml（请在仓库根运行测试）", toml)
        val text = toml!!.readText()
        fun v(key: String): String {
            val m = Regex("""(?m)^\s*$key\s*=\s*"([^"]+)"""").find(text)
            assertNotNull("libs.versions.toml 缺少 [versions] $key", m)
            return m!!.groupValues[1]
        }
        return mapOf(
            "commons-compress" to v("commonsCompress"),
            "commons-io" to v("commonsIo"),
            "xz" to v("xz")
        )
    }

    private fun defaultGradleHome(): File = File(
        System.getProperty("r31.gradle.home") ?: (System.getProperty("user.home") ?: "."),
        ".gradle/caches/modules-2/files-2.1"
    )

    private data class Dep(val label: String, val groupPath: String, val artifact: String, val version: String)

    private fun dependencies(): List<Dep> {
        val v = declaredVersions()
        return listOf(
            Dep("commons-compress", "org.apache.commons/commons-compress", "commons-compress", v.getValue("commons-compress")),
            Dep("commons-io", "commons-io/commons-io", "commons-io", v.getValue("commons-io")),
            Dep("xz", "org.tukaani/xz", "xz", v.getValue("xz"))
        )
    }

    /**
     * 每个 artifact 只取一个 jar。排序与 `File.listFiles()` 的顺序无关：
     * ① 声明版本优先；② 版本号按点分段数值降序（非字典序，1.9 > 1.21 这种不会错）；
     * ③ 同版本内按绝对路径升序。
     */
    private fun resolveDependencyJars(cache: File): List<File> {
        val selected = ArrayList<File>()
        val missing = ArrayList<String>()
        for (dep in dependencies()) {
            val base = File(cache, dep.groupPath)
            val candidates = ArrayList<File>()
            val versions = (base.listFiles() ?: emptyArray()).filter { it.isDirectory }
            for (versionDir in versions) {
                val hashes = (versionDir.listFiles() ?: emptyArray()).filter { it.isDirectory }
                for (hashDir in hashes) {
                    for (jar in (hashDir.listFiles() ?: emptyArray())) {
                        val n = jar.name.lowercase(Locale.ROOT)
                        if (!jar.isFile || !n.endsWith(".jar")) continue
                        if (n.contains("sources") || n.contains("javadoc")) continue
                        if (!n.startsWith(dep.artifact + "-")) continue
                        candidates.add(jar)
                    }
                }
            }
            val versionOf = { f: File -> f.parentFile.parentFile.name }
            val sorted = candidates.sortedWith(
                compareByDescending<File> { if (versionOf(it) == dep.version) 1 else 0 }
                    .thenByDescending { VersionKey(versionOf(it)) }
                    .thenBy { it.absolutePath }
            )
            val first = sorted.firstOrNull()
            if (first == null) {
                missing.add("${dep.label} ${dep.version}（找过 $base，候选 ${candidates.size} 个）")
            } else {
                selected.add(first)
                if (versionOf(first) != dep.version) {
                    // 不静默降级：明确说出来用的是哪个版本，便于跨机排障。
                    println(
                        "R31-209 WARN: ${dep.label} 声明版本 ${dep.version} 不在缓存里，" +
                            "改用 ${versionOf(first)}（$first）"
                    )
                }
            }
        }
        assertTrue(
            "找不到必需 jar：${missing.joinToString("; ")}。先跑一次 Gradle 构建把依赖拉进缓存" +
                "（例如 sh run-compile.sh）。缓存目录：$cache",
            missing.isEmpty()
        )
        return selected.sortedBy { it.absolutePath }
    }

    /** 数值版本键：点分段逐段按整数比，非数字段按字典序兜底。 */
    private class VersionKey(raw: String) : Comparable<VersionKey> {
        private val parts: List<Pair<Long, String>> = raw.split('.', '-', '_').map {
            val n = it.toLongOrNull()
            (n ?: -1L) to it
        }
        override fun compareTo(other: VersionKey): Int {
            for (i in 0 until maxOf(parts.size, other.parts.size)) {
                val a = parts.getOrNull(i) ?: (0L to "")
                val b = other.parts.getOrNull(i) ?: (0L to "")
                val c = a.first.compareTo(b.first)
                if (c != 0) return c
                val s = a.second.compareTo(b.second)
                if (s != 0) return s
            }
            return 0
        }
    }

    private fun runtimeUrls(vararg jars: File): Array<URL> {
        val urls = ArrayList<URL>()
        for (jar in jars) urls.add(jar.toURI().toURL())
        // 父加载器传 null（bootstrap）：依赖全部来自上面的 URL，不需要从应用 classpath 再借。
        // 不用 ClassLoader.getPlatformClassLoader() —— 那是 Java 9+ API，本仓测试按 jvm-target 1.8 编译。
        return urls.toTypedArray()
    }

    private fun buildChildLoader(selected: List<File>): URLClassLoader {
        // java.class.path 里含本仓主代码产物和 android.jar（桩），必须保留；
        // 但 commons-compress/xz/commons-io 只能来自**选中**的那一份 —— 否则宿主 classpath 上的
        // 其它版本会先被命中（r31-vi-12 那个"版本不确定"就是这样长出来的）。
        // 做法：逐条过滤掉这三个 artifact 的 jar，再把选中的 jar 放到列表最前面。
        val urls = ArrayList<URL>()
        for (entry in (System.getProperty("java.class.path") ?: "").split(File.pathSeparator)) {
            if (entry.isEmpty()) continue
            val n = File(entry).name.lowercase(Locale.ROOT)
            if (n.endsWith(".jar") &&
                (n.startsWith("commons-compress-") || n.startsWith("commons-io-") || n.startsWith("xz-"))
            ) {
                continue
            }
            urls.add(File(entry).toURI().toURL())
        }
        val selectedUrls = selected.sortedBy { it.absolutePath }.map { it.toURI().toURL() }
        for (i in selectedUrls.size - 1 downTo 0) urls.add(0, selectedUrls[i])
        // 父加载器传 null（bootstrap）：依赖全部来自上面的 URL。
        // 不用 ClassLoader.getPlatformClassLoader() —— 那是 Java 9+ API，本仓测试按 jvm-target 1.8 编译。
        return URLClassLoader(urls.toTypedArray(), null)
    }

    private fun runtimeUtilsClass(): Class<*> {
        val loader = buildChildLoader(resolveDependencyJars(defaultGradleHome()))
        return Class.forName("com.tungsten.fcl.util.RuntimeUtils", true, loader)
    }

    private fun findCachedJar(rel: String): File? =
        resolveDependencyJars(defaultGradleHome())
            .firstOrNull { it.absolutePath.replace('\\', '/').contains("/$rel/") }

    private fun requireCachedJar(rel: String): File {
        val jar = findCachedJar(rel)
        assertNotNull("本机缓存里找不到 $rel 的 jar，无法搭 fake gradle home", jar)
        return jar!!
    }

    private fun copyCachedJar(cache: File, rel: String, name: String) {
        val dir = File(cache, "$rel/r31fixture")
        dir.mkdirs()
        requireCachedJar(rel).copyTo(File(dir, name), overwrite = true)
    }

    private fun extract(runtime: Class<*>, archive: ByteArray, dest: File): Any {
        val method = runtime.getMethod("uncompressTarXZ", InputStream::class.java, File::class.java)
        ByteArrayInputStream(archive).use { input ->
            return method.invoke(null, input, dest)!!
        }
    }

    private fun copyStream(runtime: Class<*>, source: InputStream, dest: File) {
        val method = runtime.getDeclaredMethod("copyAssetStream", InputStream::class.java, File::class.java)
        method.isAccessible = true
        try {
            method.invoke(null, source, dest)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // 反射调用会把被测方法抛的异常包一层。断言要看的是**被测方法**抛了什么
            // （copyAssets/install 都声明 throws IOException），所以把原始异常原样抛出去。
            val cause = e.targetException
            when (cause) {
                is IOException -> throw cause
                is RuntimeException -> throw cause
                else -> throw RuntimeException(cause)
            }
        }
    }

    private fun hasFailures(report: Any): Boolean =
        report.javaClass.getMethod("hasFailures").invoke(report) as Boolean

    private fun failureCount(report: Any): Int =
        report.javaClass.getMethod("getFailureCount").invoke(report) as Int

    private fun failedLinks(report: Any): Int =
        report.javaClass.getMethod("getFailedLinks").invoke(report) as Int

    private fun failedExecutableBits(report: Any): Int =
        report.javaClass.getMethod("getFailedExecutableBits").invoke(report) as Int

    private fun degradedHardLinks(report: Any): Int =
        report.javaClass.getMethod("getDegradedHardLinks").invoke(report) as Int

    private fun firstFailure(report: Any): String? =
        report.javaClass.getMethod("getFirstFailure").invoke(report) as String?

    private fun describe(report: Any): String =
        report.javaClass.getMethod("describe").invoke(report) as String

    private fun decodeFixture(base64: String): ByteArray =
        Base64.getMimeDecoder().decode(base64)

    // -----------------------------------------------------------------
    // 生产路径夹具：临时目录里编译 Android 桩 + 工作区原样的 RuntimeUtils.java
    // -----------------------------------------------------------------

    private class Fixture(
        val dir: File,
        val loader: URLClassLoader,
        val contextClass: Class<*>,
        val amClass: Class<*>,
        val runtimeClass: Class<*>,
        val osClass: Class<*>
    )

    private fun newProductionFixture(jarsOverride: List<File>? = null): Fixture {
        val dir = Files.createTempDirectory("r31-209-prodfixture").toFile()
        val src = File(dir, "src")
        val out = File(dir, "out")
        out.mkdirs()

        val prod = resolveMain("util/RuntimeUtils.java")
        assertNotNull("找不到 RuntimeUtils.java —— 生产路径夹具必须读工作区原样源码", prod)
        val prodCopy = File(src, "RuntimeUtils.java")
        writeSource(prodCopy, prod!!.readText())

        writeSource(File(src, "android/content/Context.java"), STUB_CONTEXT)
        writeSource(File(src, "android/content/res/AssetManager.java"), STUB_ASSET_MANAGER)
        writeSource(File(src, "android/system/Os.java"), STUB_OS)
        writeSource(File(src, "com/tungsten/fclcore/util/Logging.java"), STUB_LOGGING)
        writeSource(File(src, "com/tungsten/fclcore/util/io/FileUtils.java"), STUB_FILE_UTILS)
        writeSource(File(src, "com/tungsten/fclcore/util/io/IOUtils.java"), STUB_IO_UTILS)
        writeSource(File(src, "com/dsh/core/TarLinkPolicy.java"), STUB_TAR_LINK_POLICY)

        val files = listOf(
            prodCopy,
            File(src, "android/content/Context.java"),
            File(src, "android/content/res/AssetManager.java"),
            File(src, "android/system/Os.java"),
            File(src, "com/tungsten/fclcore/util/Logging.java"),
            File(src, "com/tungsten/fclcore/util/io/FileUtils.java"),
            File(src, "com/tungsten/fclcore/util/io/IOUtils.java"),
            File(src, "com/dsh/core/TarLinkPolicy.java")
        )
        val jars = jarsOverride ?: resolveDependencyJars(defaultGradleHome())
        val cp = jars.joinToString(File.pathSeparator) { it.absolutePath }
        val compiler = systemJavaCompiler()
        assertNotNull(
            "本测试需要 JDK（javax.tools.JavaCompiler），JRE 上无法编译生产路径夹具",
            compiler
        )
        val compiled = compileWithReflection(
            compiler!!, files,
            listOf("-d", out.absolutePath, "-encoding", "UTF-8", "-nowarn", "-cp", cp)
        )
        assertTrue(
            "生产路径夹具编译失败（被测生产源码一个字节都没改）：" + compiled.diagnostics,
            compiled.ok
        )
        val urls = ArrayList<URL>()
        urls.add(out.toURI().toURL())
        for (jar in jars) urls.add(jar.toURI().toURL())
        // 桩目录必须排在 android.jar 前面：android.jar 里的方法体全是 `throw new RuntimeException("Stub!")`。
        val loader = URLClassLoader(urls.toTypedArray(), null)
        val contextClass = Class.forName("android.content.Context", true, loader)
        val amClass = Class.forName("android.content.res.AssetManager", true, loader)
        val osClass = Class.forName("android.system.Os", true, loader)
        val runtimeClass = Class.forName("com.tungsten.fcl.util.RuntimeUtils", true, loader)
        return Fixture(dir, loader, contextClass, amClass, runtimeClass, osClass)
    }

    private fun writeSource(file: File, text: String) {
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    // -----------------------------------------------------------------
    // javax.tools 的反射外壳（r31-vi-15）
    // -----------------------------------------------------------------

    /**
     * 编译结果：成功标志 + 诊断文本。诊断文本的格式与改造前**逐字一致**
     * （`joinToString("; ") { it.toString() }`，无诊断时为空串）。
     */
    private class CompileResult(val ok: Boolean, val diagnostics: String)

    /**
     * 取 `ToolProvider.getSystemJavaCompiler()` 的结果，但**不写静态 import**。
     *
     * [r31-vi-15] 本模块是 Android 模块，`compileDebugUnitTestKotlin` 的 classpath 上
     * `android.jar` 排在 `java.base` 之前，而 `android.jar` 里 `javax/tools` 的类计数为 **0**
     * （实测 `unzip -l android.jar | grep -c 'javax/tools'` → `0`）。于是
     * `import javax.tools.ToolProvider` 在编译期 Unresolved，而运行时（Gradle 测试 JVM 是完整
     * JDK 17）反而可用 —— 一个只在 Gradle 路径上、且在 IDEA / 私有 harness 里都看不见的地雷。
     * 全模块 644 条单测因此一条都编译不过。
     *
     * 所以这里只用 `Class.forName` **字符串**引用这些类型，编译期不需要它们存在。
     * JRE 上找不到 `ToolProvider` 时返回 null，由调用方的 `assertNotNull` 给出**明确**失败，
     * 与改造前的行为一致（原来 `getSystemJavaCompiler()` 在 JRE 上也返回 null）。
     */
    private fun systemJavaCompiler(): Any? = try {
        Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
    } catch (e: ClassNotFoundException) {
        null
    }

    /**
     * 用反射跑完 `javac` 的完整链路：
     * `getStandardFileManager` → `getJavaFileObjectsFromFiles` → 6 参 `getTask` → `call()`。
     *
     * 三个关键点（都是踩过的坑）：
     * 1. **走接口类取方法**（`javax.tools.JavaCompiler` / `StandardJavaFileManager`），
     *    而不是实现类 —— 实现类是 `com.sun.tools.javac.api.JavacTool`，在部分 JDK 上不可见。
     * 2. **`getTask` 只能用 6 参重载**。带 `JavaFileManager$Location` 的 7 参重载是 javac
     *    内部 API，反射拿不到 / 行为不稳定。
     * 3. `JavaFileManager` 必须显式 `close()` —— 原来写的是 `manager.use { }`，那是
     *    `Closeable.use` 的等价语义，漏掉会在 Windows 上留住文件句柄。
     */
    private fun compileWithReflection(compiler: Any, files: List<File>, options: List<String>): CompileResult {
        val compilerIface = Class.forName("javax.tools.JavaCompiler")
        val listenerIface = Class.forName("javax.tools.DiagnosticListener")
        val fileManagerIface = Class.forName("javax.tools.JavaFileManager")
        val standardFileManagerIface = Class.forName("javax.tools.StandardJavaFileManager")
        val taskIface = Class.forName("javax.tools.JavaCompiler\$CompilationTask")
        val diagnosticCollectorClass = Class.forName("javax.tools.DiagnosticCollector")

        val diagnostics = diagnosticCollectorClass.getDeclaredConstructor().newInstance()
        val getStandardFileManager = compilerIface.getMethod(
            "getStandardFileManager",
            listenerIface,
            Locale::class.java,
            java.nio.charset.Charset::class.java
        )
        val fileManager = getStandardFileManager.invoke(compiler, diagnostics, null, Charsets.UTF_8)
        try {
            val fileObjects = standardFileManagerIface
                .getMethod("getJavaFileObjectsFromFiles", Iterable::class.java)
                .invoke(fileManager, files)
            // ★ 6 参重载：Writer / JavaFileManager / DiagnosticListener / options / classes / units
            val getTask = compilerIface.getMethod(
                "getTask",
                java.io.Writer::class.java,
                fileManagerIface,
                listenerIface,
                Iterable::class.java,
                Iterable::class.java,
                Iterable::class.java
            )
            val task = getTask.invoke(compiler, null, fileManager, diagnostics, options, null, fileObjects)
            val ok = taskIface.getMethod("call").invoke(task) as Boolean
            return CompileResult(ok, describeDiagnostics(diagnosticCollectorClass, diagnostics))
        } finally {
            // `manager.use { }` 的等价物。close 失败不该盖掉编译结论，但也不该静默 —— 交给人看。
            try {
                fileManagerIface.getMethod("close").invoke(fileManager)
            } catch (e: Throwable) {
                System.err.println("R31Fix204Test: StandardJavaFileManager.close() 失败：$e")
            }
        }
    }

    /** `DiagnosticCollector.getDiagnostics()` 的反射版，输出格式与改造前一致。 */
    private fun describeDiagnostics(collectorClass: Class<*>, collector: Any): String =
        (collectorClass.getMethod("getDiagnostics").invoke(collector) as List<*>)
            .joinToString("; ") { it.toString() }

    private fun setStatic(loader: ClassLoader, className: String, field: String, value: Any?) {
        Class.forName(className, true, loader).getField(field).set(null, value)
    }

    /**
     * 在**独立 JVM 子进程**里跑生产路径探针。
     *
     * 为什么必须是子进程：
     * - `java.io.WinNTFileSystem.ENABLE_ADS` 是 `static final`，由 JVM 启动时的
     *   `-Djdk.io.File.enableADS` 决定，进程内改不了；
     * - `java.class.path` 在 harness 里很长（含 android.jar 等），子进程只带本测试产出的
     *   最小 classpath，避免把宿主的类加载顺序带进夹具。
     */
    private fun runInChildJvm(marker: String): String {
        val java = File(System.getProperty("java.home"), "bin/java.exe").takeIf { it.isFile }
            ?: File(System.getProperty("java.home"), "bin/java")
        assertTrue("找不到 java 可执行文件：$java", java.isFile)
        val repo = repoRoot()
        val cmd = ArrayList<String>()
        cmd.add(java.absolutePath)
        // 子进程只带**不含 commons-compress/xz/commons-io** 的 classpath：这三个必须由夹具自己
        // 编译/选中的那份提供，否则宿主 classpath 上的其它版本会混进夹具，把"版本不确定"重新引进来。
        val cp = childClasspath()
        cmd.add("-Dfile.encoding=UTF-8")
        cmd.add("-Duser.dir=${repo.absolutePath}")
        cmd.add("-cp")
        cmd.add(cp)
        cmd.add(ProbeMain::class.java.name)
        cmd.add(marker)
        cmd.add(repo.absolutePath)
        // 夹具自己选中的依赖通过独立属性传进去（不在 -cp 上，避免被宿主版本覆盖）。
        cmd.add(resolveDependencyJars(defaultGradleHome()).joinToString(File.pathSeparator) { it.absolutePath })
        val proc = ProcessBuilder(cmd)
            .directory(repo)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        val line = output.lineSequence().firstOrNull { it.startsWith("R31-") } ?: ""
        assertTrue(
            "探针子进程失败（exit=$code, marker=$marker）：\n$output",
            code == 0 && line.isNotEmpty()
        )
        return line
    }

    /** 宿主 classpath 去掉 commons-compress / commons-io / xz 的 jar 之后剩下的部分。 */
    private fun childClasspath(): String =
        (System.getProperty("java.class.path") ?: "").split(File.pathSeparator)
            .filter { entry ->
                if (entry.isEmpty()) return@filter false
                val n = File(entry).name.lowercase(Locale.ROOT)
                !(n.endsWith(".jar") && (
                    n.startsWith("commons-compress-") || n.startsWith("commons-io-") || n.startsWith("xz-")
                    ))
            }
            .joinToString(File.pathSeparator)

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "FCL").isDirectory) return dir
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("找不到仓库根（gradle/libs.versions.toml + FCL/）")
    }

    /** 供 [runInChildJvm] 反射调用的静态方法：编译桩 → 驱动真实生产路径 → 打一行可机械判定的结果。 */
    object ProbeMain {
        @JvmStatic
        fun main(args: Array<String>) {
            val marker = args[0]
            val repo = File(args[1])
            val jars = args.getOrNull(2)?.takeIf { it.isNotEmpty() }
                ?.split(File.pathSeparator)?.map { File(it) } ?: emptyList()
            val t = R31Fix204Test()
            try {
                when (marker) {
                    PRODUCTION_PROBE_MARKER, PRODUCTION_KEEP_PROBE_MARKER -> t.copyAssetsProbe(marker, repo, jars)
                    else -> throw AssertionError("unknown marker: $marker")
                }
            } catch (e: Throwable) {
                println("R31-PROBE-CRASH $marker ${e.javaClass.name}: ${e.message}")
                e.printStackTrace(System.out)
            }
        }
    }

    private fun copyAssetsProbe(marker: String, repo: File, jars: List<File>) {
        val f = newProductionFixture(jars)
        try {
            val dir = Files.createTempDirectory("r31-209-copyprod").toFile()
            try {
                val dest = File(dir, "payload.bin")
                if (marker == PRODUCTION_KEEP_PROBE_MARKER) dest.writeText("PREVIOUS-CONTENT")
                val boom = FailingInputStream(afterBytes = 4, message = "simulated ENOSPC")
                // 桩由独立的 URLClassLoader 加载：Kotlin lambda 实现的是宿主加载器的
                // Function 接口，跨加载器不能直接赋给桩里的字段。用 JDK 的
                // java.lang.reflect.InvocationHandler（bootstrap 加载器，双方共见）传回调。
                setStatic(
                    f.loader, "android.content.res.AssetManager", "LIST_HANDLER",
                    java.lang.reflect.InvocationHandler { _, _, args ->
                        if (args[0] == "src") arrayOf("payload.bin") else emptyArray<String>()
                    }
                )
                setStatic(
                    f.loader, "android.content.res.AssetManager", "OPEN_HANDLER",
                    java.lang.reflect.InvocationHandler { _, _, _ -> boom }
                )
                val context = f.contextClass.getDeclaredConstructor().newInstance()
                val copyAssets = f.runtimeClass.getMethod(
                    "copyAssets", f.contextClass, String::class.java, String::class.java
                )
                var thrown: String = "false"
                try {
                    copyAssets.invoke(null, context, "src", dir.absolutePath)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    thrown = e.targetException.javaClass.simpleName
                }
                val tmpExists = File(dir, "payload.bin.tmp").exists()
                if (marker == PRODUCTION_KEEP_PROBE_MARKER) {
                    println(
                        "R31-COPY-KEEP-PROBE thrown=$thrown content=${if (dest.isFile) dest.readText() else "<missing>"}" +
                            " tmpExists=$tmpExists"
                    )
                } else {
                    println("R31-COPY-PROBE thrown=$thrown destExists=${dest.exists()} tmpExists=$tmpExists closed=${boom.closed}")
                }
            } finally {
                dir.deleteRecursively()
            }
        } finally {
            f.loader.close()
            f.dir.deleteRecursively()
        }
    }

    /**
     * 读够 [afterBytes] 字节后抛 [IOException] 的输入流 —— 模拟 `/data` 写满（ENOSPC）时
     * 已经读了几块、正要继续读的那一刻。同时记录 [closed]，用于断言异常路径上流有没有关。
     */
    private class FailingInputStream(
        private val afterBytes: Int,
        private val message: String
    ) : InputStream() {
        private var served = 0
        var closed = false
            private set

        override fun read(): Int {
            if (served >= afterBytes) throw IOException(message)
            served++
            return 'x'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= afterBytes) throw IOException(message)
            val n = minOf(len, afterBytes - served)
            for (i in 0 until n) b[off + i] = 'x'.code.toByte()
            served += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    private fun resolveMain(rel: String): File? = resolveUnder("FCL/src/main/java/com/tungsten/fcl/$rel")

    /** 与其他测试同款：从 cwd 向上找，兼容 `FCL/FCL/`、`FCL/`、裸 `src/` 三种布局。 */
    private fun resolveUnder(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            File(dir, "FCL/$rel").takeIf { it.isFile }?.let { return it }
            File(dir, rel).takeIf { it.isFile }?.let { return it }
            File(dir, rel.removePrefix("FCL/")).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    companion object {
        private const val GOOD_TXT = "good.txt"
        private const val BAD_LINK = "bad-link"
        private const val HARD_TXT = "hard.txt"

        private const val PRODUCTION_PROBE_MARKER = "copy-prod"
        private const val PRODUCTION_KEEP_PROBE_MARKER = "copy-prod-keep"

        /** 单条普通文件 `good.txt`（内容 `hello-r31`）的 tar.xz，mtime 固定，字节级可复现。 */
        private const val CLEAN_TAR_XZ =
            "/Td6WFoAAATm1rRGAgAhARYAAAB0L+Wj4Af/AFVdADOb7MguLpBjEMqPhQzqvsxqJBQSeC2o95mP" +
                "YSMmiYTxfoOjNFcLGRT62WTXGc2QZuaWbxQKalCgZg3rfQ1U7LGyIcLv7AKs6+Km/anTI8IIRfKYO" +
                "QAAAAAAMVOfbRvregcAAXGAEAAAALTt1OKxxGf7AgAAAAAEWVo="

        /** 上一个包 + 一条指向 `good.txt` 的符号链接 `bad-link`。 */
        private const val SYMLINK_TAR_XZ =
            "/Td6WFoAAATm1rRGAgAhARYAAAB0L+Wj4An/AHRdADOb7MguLpBjEMqPhQzqvsxqJBQSeC2o95mP" +
                "YSMmiYTxfoOjNFcLGRT62WTXGc2QZuaWbxQKalCgZg3rfQ1U7LGyIcLv7AKs6+Km9k1DQi2WF3Vy" +
                "1Zs6alaImEQ700QiaBvNNxTpAAr5STnZ/92pYfc2oCAAABUwpzU7KXsSAAGQAYAUAAC5ViUVscRn+w" +
                "IAAAAABFla"

        /** 第一个包 + 一条指向 `good.txt` 的硬链接 `hard.txt`。 */
        private const val HARDLINK_TAR_XZ =
            "/Td6WFoAAATm1rRGAgAhARYAAAB0L+Wj4An/AGldADOb7MguLpBjEMqPhQzqvsxqJBQSeC2o95mP" +
                "YSMmiYTxfoOjNFcLGRT62WTXGc2QZuaWbxQKalCgZg3rfQ1U7LGyIcLv7AKs6+Km9k111fS4nSd/1" +
                "tzBkXPn4Mhny5LdwahvVFnilsEaAAAAAAD5mZInUJ3qTgABhQGAFAAAkcU+RrHEZ/sCAAAAAARZWg=="

        // ---- 最小 Android / FCL 桩源码（只在临时目录里编译，不进仓库、不改生产代码） ----

        private const val STUB_CONTEXT =
            "package android.content; " +
                "public class Context { " +
                "  public android.content.res.AssetManager getAssets() { return android.content.res.AssetManager.HOLDER; } " +
                "}"

        private const val STUB_ASSET_MANAGER =
            "package android.content.res; " +
                "public class AssetManager { " +
                "  public static AssetManager HOLDER = new AssetManager(); " +
                "  public static java.lang.reflect.InvocationHandler LIST_HANDLER; " +
                "  public static java.lang.reflect.InvocationHandler OPEN_HANDLER; " +
                "  public static java.lang.reflect.Proxy proxy(java.lang.Class<?> iface, java.lang.reflect.InvocationHandler h) { " +
                "    return (java.lang.reflect.Proxy) java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), new java.lang.Class<?>[]{iface}, h); " +
                "  } " +
                "  @SuppressWarnings(\"unchecked\") " +
                "  public String[] list(String p) throws java.io.IOException { " +
                "    if (LIST_HANDLER == null) return new String[0]; " +
                "    try { return (String[]) LIST_HANDLER.invoke(this, null, new Object[]{p}); } " +
                "    catch (java.io.IOException e) { throw e; } " +
                "    catch (Throwable t) { throw new java.io.IOException(t); } " +
                "  } " +
                "  public java.io.InputStream open(String n) throws java.io.IOException { " +
                "    if (OPEN_HANDLER == null) return null; " +
                "    try { return (java.io.InputStream) OPEN_HANDLER.invoke(this, null, new Object[]{n}); } " +
                "    catch (java.io.IOException e) { throw e; } " +
                "    catch (Throwable t) { throw new java.io.IOException(t); } " +
                "  } " +
                "}"

        private const val STUB_OS =
            "package android.system; " +
                "public class Os { " +
                "  public static void chmod(String p, int m) { throw new RuntimeException(\"Stub!\"); } " +
                "  public static void symlink(String a, String b) { throw new RuntimeException(\"Stub!\"); } " +
                "  public static void link(String a, String b) { throw new RuntimeException(\"Stub!\"); } " +
                "}"

        private const val STUB_LOGGING =
            "package com.tungsten.fclcore.util; " +
                "public final class Logging { " +
                "  public static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(\"FCL\"); " +
                "}"

        private const val STUB_FILE_UTILS =
            "package com.tungsten.fclcore.util.io; " +
                "public class FileUtils { " +
                "  public static void deleteDirectory(java.io.File f) {} " +
                "  public static String readText(java.io.File f) throws java.io.IOException { return \"\"; } " +
                "}"

        private const val STUB_IO_UTILS =
            "package com.tungsten.fclcore.util.io; " +
                "public class IOUtils { " +
                "  public static String readFullyAsString(java.io.InputStream i) throws java.io.IOException { return \"\"; } " +
                "}"

        private const val STUB_TAR_LINK_POLICY =
            "package com.dsh.core; " +
                "public class TarLinkPolicy { " +
                "  public static String symlinkTarget(String s) { return s; } " +
                "}"
    }
}
