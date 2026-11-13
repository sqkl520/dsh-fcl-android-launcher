package com.dsh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/**
 * R31 / 启动链路三条「失败不可感知」缺陷：
 * `s10-eh-03`（`FCLApp.onCreate` 无失败分支）、`s10-eh-06`（`fcl.log` 创建失败只进 stderr）、
 * `s10-eh-07`（`SplashActivity` 协程无 handler、`enterDsh` 不在 finally）。
 *
 * ## 为什么断言长这样
 *
 * 这三条的共同点不是「功能不对」，而是**失败之后世界看起来仍然正常**：
 * - 任一初始化抛异常 ⇒ 异常穿出 `Application.onCreate` ⇒ 在
 *   `ActivityThread.handleBindApplication` 里终止进程 ⇒ 每次冷启动确定性复现（闪退循环）；
 *   而 `CrashReporter.isStackTraceLikelyConflictive()` 对带 `handleBindApplication` 的栈
 *   **明确拒绝**弹错误页（实测该分支返回 true），所以这条路上连崩溃界面都没有，
 *   只有一圈 logcat 环形缓冲 —— 进程重启后取不回。
 * - `fcl.log` 建不出来时 `catch (IOException)` 只 `System.err.println`，`start()` 返回 void，
 *   调用方无从判断「磁盘日志是否可用」。
 * - 启动页协程里抛异常 ⇒ `enterDsh` 不被调用 ⇒ 启动页永远停在原地（或闪退）。
 *
 * 所以本文件钉的是**失败路径的可观察产物**：返回值能不能区分成功/失败、失败有没有落盘、
 * 关键跳转是不是无条件执行。方向一律是「catch + 记录 + 降级」，**不是**让失败更响亮：
 * 这些方法全在冷启动路径上，抛 = 闪退循环。
 *
 * ## 为什么一部分用例走反射
 *
 * - `Logging.start` 现在是 `void`，修好后返回 `String`。测试若直接写
 *   `val r = Logging.start(p)`，在**修复前**是**编译失败**而不是断言失败 —— 而编译失败
 *   不构成「缺陷可复现」的证据（本轮已有 agent 踩过这个陷阱）。所以签名与返回值都走反射，
 *   保证同一份测试对修复前后都能编译，红是**断言红**。
 * - `SplashActivity` 继承 `AppCompatActivity`，本仓 harness 的 classpath 只有
 *   stdlib/coroutines/gson/android.jar，**没有 androidx** —— 连 `Class.forName` 都会在父类链上
 *   炸（`R31Fix204Test` 的注释记录过同一限制）。它是 Android 组件，JVM 层无法实例化，
 *   因此那三条用**源码结构断言**（机械可判），并在报告里诚实标注「结构证据，非运行时证据」。
 *
 * 跑法：
 * ```
 * mkdir -p /tmp/r31-210-ts && cp R31StartupChainTest.kt /tmp/r31-210-ts/
 * TEST_SRC=/tmp/r31-210-ts sh run-tests.sh
 * ```
 */
class R31StartupChainTest {

    companion object {
        private const val FCL_PKG = "com.tungsten.fcl."
        private const val FAILURE_LOG_NAME = "startup-failures.log"

        /** `Logging.start` 每次调用都会往 "FCL" logger 上挂 handler；用完按元素身份还原。 */
        @Volatile
        private var savedHandlers: List<java.util.logging.Handler> = emptyList()
    }

    private val logger: Logger get() = com.tungsten.fclcore.util.Logging.LOG

    @Before
    fun saveLoggerState() {
        savedHandlers = (logger.handlers ?: emptyArray()).toList()
    }

    @After
    fun restoreLoggerState() {
        val keep = savedHandlers
        // ★ `logger.handlers` 每次调用返回**新数组**，所以必须遍历一份快照、按元素身份判断。
        //   不能写成 `while (handlers.size > keep.size) { ... }`：那个 size 是数组长度，
        //   循环体摘的是 logger 上的 handler，快照长度永远不变 ⇒ **死循环**。
        //   本文件第一版就是那样写的，实测把测试进程挂死（java.exe 一直不退出）。
        for (h in (logger.handlers ?: emptyArray())) {
            if (keep.none { it === h }) {
                runCatching { logger.removeHandler(h) }
            }
        }
        savedHandlers = emptyList()
    }

    // =================================================================
    // s10-eh-06：fcl.log 创建失败必须能被调用方感知，且不能因此抛异常
    // =================================================================

    /**
     * 返回值判据：`start` 必须是 `String`（null = 磁盘日志可用，非 null = 不可用的原因）。
     *
     * 修复前是 `void` —— 调用方拿到的东西是「什么都没有」，这正是缺陷本身。
     * 这条断言对修复前是**断言红**（returnType 是 void.class），不是编译红。
     */
    @Test
    fun logStartReportsWhetherDiskLogIsAvailable() {
        val m = logStartMethod()
        assertEquals(
            "Logging.start 必须把「磁盘日志可用/不可用」交给调用方判断；void = 调用方无从感知",
            String::class.java,
            m.returnType
        )
    }

    /**
     * 失败路径的可观察产物：`fcl.log` 建不出来时必须返回**非 null 原因**，且原因要能定位。
     *
     * 构造方式：把一个普通文件当成 log 目录的父级，`Files.createDirectories` 必然
     * `FileSystemException`（Not a directory）—— 这正是真机上「磁盘满 / DataStore 损坏」时
     * 同一段代码会走到的 `IOException` 分支，不是人造的额外分支。
     */
    @Test
    fun logStartFailureIsReturnedNotJustPrinted() {
        val root = Files.createTempDirectory("r31-210-log").toFile()
        try {
            val blocker = File(root, "blocker")
            blocker.writeText("not a directory")
            val badLogDir = blocker.toPath().resolve("log")

            val reason = invokeStart(badLogDir) as String?

            assertNotNull(
                "fcl.log 建不出来时必须返回失败原因（只 System.err.println 的失败在进程重启后取不回）",
                reason
            )
            assertTrue(
                "失败原因必须能定位到 fcl.log / 目录，实际为：$reason",
                reason!!.contains("fcl.log", ignoreCase = true) ||
                    reason.contains(badLogDir.toString(), ignoreCase = true)
            )
            // 反向对照：目录可写时必须返回 null（否则「永远非 null」的实现也能骗过上面的断言）
            val goodLogDir = File(root, "good")
            assertEquals(
                "目录可写时不得报失败（永远非 null 的实现会让调用方以为磁盘日志从不可用）",
                null,
                invokeStart(goodLogDir.toPath())
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * `Logging.start(null)` 必须**不抛**：它跑在启动页协程里，抛出去就是「停在启动页 / 闪退」。
     *
     * 修复前：`Files.isRegularFile(null)` 抛 NPE，`catch (IOException)` 接不住，一路穿出
     * `start()` —— 这是真实的异常出口，不是理论风险（`FCLPath.LOG_DIR` 就是可能为 null 的静态
     * 字段，见 `FCLPath.loadPaths` 失败分支）。
     */
    @Test
    fun logStartDoesNotThrowOnNullPath() {
        val m = logStartMethod()
        val thrown = runCatching { m.invoke(null, *arrayOfNulls<Any?>(1)) }.exceptionOrNull()
        if (thrown is InvocationTargetException) {
            fail("Logging.start(null) 抛出了异常（启动链上抛 = 闪退循环）：${thrown.targetException}")
        }
        assertTrue("反射调用本身不该失败：$thrown", thrown == null)
    }

    private fun logStartMethod(): Method =
        com.tungsten.fclcore.util.Logging::class.java
            .getDeclaredMethod("start", Path::class.java)

    private fun invokeStart(p: Path?): Any? {
        val m = logStartMethod()
        val r = runCatching { m.invoke(null, p) }.getOrElse { e ->
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            fail("Logging.start 不得抛异常（冷启动路径上抛 = 闪退）：$cause")
            return null
        }
        return r
    }

    // =================================================================
    // s10-eh-03：FCLApp.onCreate 的失败必须登记 + 落盘，且不得向上抛
    // =================================================================

    /**
     * 失败登记口必须存在且是 `public static`：`FCLApp.onCreate` 里任何一个初始化步骤抛异常，
     * 都要走到「登记 + 落盘」，而不是穿出 `Application.onCreate` 变成闪退循环。
     *
     * 修复前该方法不存在 ⇒ `fail(...)`（断言红，不是编译红）。
     */
    @Test
    fun startupFailureRecorderExists() {
        assertNotNull(
            "FCLApp 必须有一个可被调用方/排障读取的启动失败登记口（recordStartupFailure）",
            recordStartupFailureMethod()
        )
        assertNotNull(
            "还必须有读取口（getFailedInits），否则「登记了」等于没人看得见",
            runCatching { com.tungsten.fcl.FCLApp::class.java.getMethod("getFailedInits") }.getOrNull()
        )
    }

    /**
     * 登记 + 落盘的可观察产物：调用后列表里有这条、`startup-failures.log` 落盘、且**带堆栈**。
     *
     * 为什么必须落盘：`System.err` 在 Android 上只进 logcat 环形缓冲，进程崩溃重启后取不回 ——
     * 而那正是要取证的时刻（`FCLPath.java:49-53` 记录的原始事故）。
     */
    @Test
    fun startupFailureIsRecordedAndPersisted() {
        val m = recordStartupFailureMethod()
        val dir = Files.createTempDirectory("r31-210-fapp").toFile()
        try {
            val before = failedInits().size
            m.invoke(null, dir, "dshPaths", RuntimeException("boom-r31-210"))

            val after = failedInits()
            assertEquals("失败必须登记进可读列表", before + 1, after.size)
            assertTrue(
                "登记项必须点出是哪一步失败的，实际为：${after.last()}",
                after.last().contains("dshPaths")
            )

            val log = File(dir, FAILURE_LOG_NAME)
            assertTrue("失败必须落盘到 $FAILURE_LOG_NAME（logcat 重启后取不回）：$log", log.isFile)
            val text = log.readText()
            assertTrue("落盘内容必须含步骤名：$text", text.contains("dshPaths"))
            assertTrue("落盘内容必须含异常信息：$text", text.contains("boom-r31-210"))
            assertTrue(
                "落盘必须带堆栈（只有一句「失败」无法定位）：$text",
                text.contains("RuntimeException")
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 记录本身失败时**不许抛**：`getFilesDir()` 不可写 / 磁盘满时，写日志这一步会再抛一次 ——
     * 若没兜住，「记录失败」就成了新的闪退源（比原缺陷更糟）。
     */
    @Test
    fun startupFailureRecorderNeverThrows() {
        val m = recordStartupFailureMethod()
        val root = Files.createTempDirectory("r31-210-fapp2").toFile()
        try {
            val regularFile = File(root, "not-a-dir")
            regularFile.writeText("x")
            val thrown = runCatching {
                m.invoke(null, regularFile, "fclPath", RuntimeException("y"))
            }.exceptionOrNull()
            if (thrown is InvocationTargetException) {
                fail("记录失败时不得再抛异常（否则记录器本身成了闪退源）：${thrown.targetException}")
            }
            assertTrue("反射调用本身不该失败：$thrown", thrown == null)
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * 六个初始化步骤**逐个**被包进 `initStep(...)`：一步失败不能跳过后面五步。
     *
     * 只包一层 try 是不够的 —— 那样 `FCLPath` 失败会连带跳过 `DshAppContextHolder.init`，
     * 于是「带病启动」变成「更残地带病启动」，而带病状态恰恰是最需要日志的那次启动。
     */
    @Test
    fun everyInitStepIsGuardedIndividually() {
        val src = readMainSource("com/tungsten/fcl/FCLApp.java")
        // onCreate 里所有「被包住的初始化步骤」行（缩进后以 initStep( 开头）
        val stepLines = src.lines()
            .filter { it.trimStart().startsWith("initStep(") }
            .toList()

        // 六个初始化步骤，一个都不能漏：漏掉的那个就是"抛异常即闪退循环"的入口
        val expected = listOf(
            "FCLPath.loadPaths",
            "DshPaths.loadPaths",
            "writeVersionBannerIfNeeded",
            "DshInstances",
            "DshAppContextHolder.init",
            "startDefault"
        )
        for (key in expected) {
            val hit = stepLines.firstOrNull { it.contains(key) }
            assertNotNull(
                "初始化步骤 $key 没有被单独包进 initStep(...)，它就还是「抛异常即闪退循环」的入口。" +
                    "实测 initStep 行：$stepLines",
                hit
            )
        }
        assertEquals(
            "initStep(...) 只应出现在 FCLApp.onCreate 的初始化步骤上；数量不符说明有步骤被加回裸调用：" +
                stepLines.joinToString("\n"),
            6,
            stepLines.size
        )
        assertTrue(
            "FCLApp.java 必须真正捕获异常（catch），而不是只加注释",
            Regex("""catch\s*\(\s*Throwable""").containsMatchIn(src)
        )
    }

    /**
     * `initStep` 必须在**运行时**吞掉异常并登记 —— 这是本组最强的一条，直接驱动生产代码。
     *
     * 为什么光看源码不够（实测教训）：本条用例是变异测试逼出来的。第一版只断言
     * 「`initStep(` 出现在六行初始化上」+「文件里有 `catch (Throwable`」，于是把 `initStep` 的
     * `try/catch` 整个删掉（M2 变异）—— 文件里别处（`recordStartupFailure` / `filesDirSafely`）
     * 仍有 `catch (Throwable`，结构断言照样绿，**变异逃脱**。只有真的传一个会抛的 Runnable
     * 进去、看它抛不抛，才咬得住这条。
     *
     * 本用例不需要 Android 运行时：`filesDirSafely()` 在 `instance == null` 时返回 null，
     * `recordStartupFailure` 于是跳过落盘、只做内存登记 —— 正是 harness 里能走通的那条路。
     */
    @Test
    fun initStepSwallowsAndRecordsFailure() {
        val method = com.tungsten.fcl.FCLApp::class.java
            .getDeclaredMethod("initStep", String::class.java, Runnable::class.java)
        method.isAccessible = true

        val before = failedInits().size
        val marker = "r31-210-mutation-probe-" + System.nanoTime()
        val thrown = runCatching {
            method.invoke(null, marker, Runnable { throw IllegalStateException("init-boom") })
        }.exceptionOrNull()
        if (thrown is InvocationTargetException) {
            fail(
                "initStep 让异常穿了出去（这就是「Application.onCreate 抛 = 闪退循环」的那条路）：" +
                    thrown.targetException
            )
        }
        assertTrue("反射调用本身不该失败：$thrown", thrown == null)

        val after = failedInits()
        assertEquals("失败的初始化步骤必须被登记", before + 1, after.size)
        assertTrue("登记项必须点出步骤名，实际为：${after.last()}", after.last().contains(marker))

        // 反向对照：不抛的步骤**不得**被登记（否则「永远登记」的实现也能骗过上面的断言）
        val beforeOk = failedInits().size
        val thrown2 = runCatching { method.invoke(null, "$marker-ok", Runnable { }) }.exceptionOrNull()
        assertTrue("成功步骤的调用不该失败：$thrown2", thrown2 == null)
        assertEquals("成功的步骤不得被登记为失败", beforeOk, failedInits().size)
    }

    /**
     * `initStep` 的方法体必须**自己**含 try + catch(Throwable) + recordStartupFailure，
     * 且 `step.run()` 在 try 之内。
     *
     * 与上一条互补：运行时用例证明"现在不抛"，这条证明"不是靠别处的 catch 侥幸不抛"，
     * 且能守住"`step.run()` 被挪到 try 外面"这种只有边界条件才触发的回退。
     */
    @Test
    fun initStepBodyGuardsTheStepCall() {
        val src = readMainSource("com/tungsten/fcl/FCLApp.java")
        val body = functionBody(src, "private static void initStep(String name, Runnable step)")
        assertNotNull("FCLApp.java 里找不到 initStep(...)（源码结构变了？）", body)

        val tryIdx = body!!.indexOf("try {")
        val runIdx = body.indexOf("step.run()")
        val catchIdx = body.indexOf("catch (Throwable")
        val recordIdx = body.indexOf("recordStartupFailure")
        assertTrue("initStep 必须有 try 块：\n$body", tryIdx >= 0)
        assertTrue("step.run() 必须在 try 之内（否则 try 形同虚设）：\n$body", runIdx > tryIdx)
        assertTrue("initStep 必须自己 catch Throwable：\n$body", catchIdx > runIdx)
        assertTrue("catch 块里必须登记失败（catch 了却不上报 = 静默）：\n$body", recordIdx > catchIdx)
    }

    private fun recordStartupFailureMethod(): Method =
        com.tungsten.fcl.FCLApp::class.java.getMethod(
            "recordStartupFailure", File::class.java, String::class.java, Throwable::class.java
        )

    @Suppress("UNCHECKED_CAST")
    private fun failedInits(): List<String> {
        val m = com.tungsten.fcl.FCLApp::class.java.getMethod("getFailedInits")
        return (m.invoke(null) as? List<String>) ?: emptyList()
    }

    // =================================================================
    // s10-eh-07：启动页协程 —— enterDsh 必须无条件执行
    // =================================================================

    /**
     * `enterDsh(...)` 必须在 `finally` 里：IO 阶段怎么炸都要进主壳。
     *
     * 「进主壳但脚本没装好」远好于「卡在启动页永远不动」—— 前者用户还能自己重试，
     * 后者是一个没有任何出口的白屏。
     */
    @Test
    fun enterDshIsCalledFromFinallyBlock() {
        val src = readMainSource("com/tungsten/fcl/activity/SplashActivity.kt")
        val initBody = functionBody(src, "private fun init()")
        assertNotNull("SplashActivity.kt 里找不到 init()（源码结构变了？）", initBody)

        val finallyIdx = initBody!!.indexOf("finally")
        val enterIdx = initBody.indexOf("enterDsh(")
        assertTrue(
            "init() 里必须有一个 finally 块来保证降级跳转；当前源码：\n$initBody",
            finallyIdx >= 0
        )
        assertTrue(
            "enterDsh( 必须出现在 finally 之后（即真的在 finally 块里），否则 withContext 抛异常就永远进不去主壳",
            enterIdx > finallyIdx
        )
        assertTrue(
            "withContext 必须在 try 之内（否则异常照样穿出协程）",
            initBody.indexOf("withContext") > initBody.indexOf("try {")
        )
        // 页面已销毁（lifecycleScope 取消 ⇒ withContext 抛 CancellationException ⇒ 落到 finally）
        // 时不得再 startActivity —— 否则用户按返回离开启动页后会被拽回 App。
        // 这与"enterDsh 必须被调用"不冲突：页面存活时该判据恒为 false，照常进主壳。
        assertTrue(
            "finally 里必须挡掉已销毁的页面（isFinishing/isDestroyed）：\n$initBody",
            initBody.contains("isFinishing") && initBody.contains("isDestroyed")
        )
    }

    /**
     * `Logging.start` 的返回值必须被**接住**：不接住就回到「只知道失败、不知道哪一步」的状态，
     * 而磁盘日志不可用正是后续崩溃取不到现场的直接原因。
     */
    @Test
    fun loggingStartResultIsCapturedAndPersisted() {
        val src = readMainSource("com/tungsten/fcl/activity/SplashActivity.kt")
        assertTrue(
            "Logging.start 的返回值必须被接住（裸调用 = 失败被丢掉）：" +
                src.lines().firstOrNull { it.contains("Logging.start") },
            src.lines().none { line ->
                // 返回值被丢弃的形态 = `Logging.start(...)` 独占一行且没有任何绑定/容器。
                // 修好后是 `val logFailure = runCatching { Logging.start(...) }` —— 行里必然有 `=`。
                line.trimStart().startsWith("Logging.start(") && !line.contains("=")
            }
        )
        assertTrue(
            "日志不可用时要落到持久化文件（System.err/logcat 重启后取不回）：" +
                "必须调用 FCLApp.recordStartupFailure",
            src.contains("recordStartupFailure")
        )
    }

    /**
     * `checkScriptsOutdated()` 里「判定过期之后」的那段（`scriptsVersionPair` + `DshLogBus.append`）
     * 也必须在 `runCatching` 之内。
     *
     * 这是缺陷原文点名的「半包」：`scriptsOutdated` 本身包了，可判定为过期后的两行没包 ——
     * 于是一条纯日志语句的失败就能把「该去准备页重解压」这个**正确结论**一起吞掉。
     * 判据取「该函数体内 runCatching 出现 ≥2 次且第二次在 scriptsVersionPair 之前」，
     * 因为修复前该函数里只有 1 次。
     */
    @Test
    fun scriptsOutdatedLoggingIsGuarded() {
        val src = readMainSource("com/tungsten/fcl/activity/SplashActivity.kt")
        val body = functionBody(src, "private fun checkScriptsOutdated(): Boolean")
        assertNotNull("找不到 checkScriptsOutdated()（源码结构变了？）", body)

        val guards = Regex("""runCatching\s*\{""").findAll(body!!).count()
        assertTrue(
            "checkScriptsOutdated() 里的 `判定过期后写日志` 那两行必须在 runCatching 之内" +
                "（修复前只有 1 处 runCatching = 半包）；当前命中 $guards 处：\n$body",
            guards >= 2
        )
        val secondGuard = Regex("""runCatching\s*\{""").findAll(body).map { it.range.first }.toList()[1]
        val pairIdx = body.indexOf("scriptsVersionPair")
        assertTrue("scriptsVersionPair 必须被第二个 runCatching 覆盖", pairIdx > secondGuard)
    }

    // =================================================================
    // 工具
    // =================================================================

    /**
     * 从 cwd 向上找 `FCL/src/main/java/<rel>`（与仓内其它测试同款的三种布局兼容）。
     *
     * ★ 两步归一，缺一不可（本文件第一版两条都漏了，各挂一条用例）：
     * 1. CRLF → LF：目标 Java 文件在 Windows 工作区是 CRLF 检出，不折行的话按 `\n` 切行的
     *    结构断言会全部落空。
     * 2. **只保留整行的注释删除**：结构断言靠 `indexOf`，而注释里大量提到被测符号
     *    （"`withContext` 里炸一下"、"scriptsVersionPair 或 DshLogBus.append"、"runCatching **之外**"），
     *    首个命中会落在注释上，于是"X 在 Y 之后"这类比较比的是注释位置 —— 假红。
     *    只删**整行**是注释的行（行首 `//`、行首 `*`、行首块注释起始标记）而不做通用的行内剥离：
     *    后者要正确处理字符串里的 `//`（本仓有 `http://127.0.0.1`），风险远大于收益。
     *    ⚠️ Kotlin 的块注释**可嵌套**，所以本段刻意不写出块注释的起始两个字面字符 ——
     *    写了就会在自己的 KDoc 里开一个嵌套注释，文件一路"未闭合"到结尾（实测踩过）。
     */
    private fun readMainSource(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/java/$rel"),
                File(dir, "FCL/src/main/java/$rel"),
                File(dir, "src/main/java/$rel")
            ).firstOrNull { it.isFile }?.let {
                return stripCommentLines(it.readText().replace("\r\n", "\n"))
            }
            dir = dir.parentFile
            depth++
        }
        fail("找不到主源码 FCL/src/main/java/$rel（cwd=${System.getProperty("user.dir")}）")
        return ""
    }

    /** 删掉"整行都是注释"的行（保留行号无关性：结构断言只用 indexOf 比较相对顺序）。 */
    private fun stripCommentLines(text: String): String =
        text.lines().filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/")
        }.joinToString("\n")

    /** 取 `signature` 起、到与之配对的 `}` 为止的函数体（简单的花括号配平）。 */
    private fun functionBody(src: String, signature: String): String? {
        val start = src.indexOf(signature)
        if (start < 0) return null
        val open = src.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return src.substring(open)
    }
}
