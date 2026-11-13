package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.Files

/**
 * R31 / `s10-eh-01-fclpath-init-mkdirs-discarded` + `slice-04-eh-08` + `s10-eh-02-sdcard-paths-fail-silently`
 * （评审 `pat-16` 已把前两条合并为一个修复项）。
 *
 * ## 这组钉的是什么
 *
 * 两条缺陷的共同点：**目录没建成，世界里却看不出任何异常**。
 *
 * - **A**：`FCLPath.init` 丢弃 `mkdirs()` 的 boolean，且用 `exists()` 当判据 ——
 *   路径上蹲着一个同名普通文件时 `exists()` 为真，连 `mkdirs()` 都不调，方法静默返回。
 *   `loadPaths` 返回 void，`FCLApp.onCreate` 无从区分「目录就绪」与「创建失败」，
 *   错误码在这一层永久丢失，故障推迟到第一次写文件（那时只剩一个没有根因的
 *   `FileNotFoundException`）。
 * - **C**：`CONTROLLER_DIR` / `SHARE_DIR` / `SHARED_COMMON_DIR` 仍取
 *   `Environment.getExternalStorageDirectory()` 根目录，而本 App 已无任何存储权限
 *   （`AndroidManifest.xml` 实测，`targetSdk=34`，无 `requestLegacyExternalStorage`）
 *   ⇒ 这三条 `init` 在发布配置下确定性失败，且没有任何输出。
 *
 * ## 为什么断言落在「可观察的失败记录」上
 *
 * 本单明令**不许改成抛异常**（`loadPaths` 在 `FCLApp.onCreate` 冷启动路径上，抛 = 闪退循环）。
 * 所以"修好"的可观察形态只能是：失败被登记进一个调用方拿得到的地方
 * （`FCLPath.FAILED_DIRS` / `loadPaths` 的返回值 / logcat WARNING），**且 `init` 依然不抛**。
 * 下面每条断言都对应其中一种可观察形态。
 *
 * ## 为什么这些用例在修复前连"红"都红得很奇怪
 *
 * 修复前 `SHARED_COMMON_DIR` 是**字段初始化器**，类一加载就调
 * `Environment.getExternalStorageDirectory()` —— 在本项目的单测 harness（`android.jar` 桩）里
 * 直接抛 `RuntimeException: Stub!`，`Class.forName("...FCLPath")` 拿到的是
 * `ExceptionInInitializerError`。所以修复前这些用例是**整类加载失败**，不是断言失败。
 * 这一点本身就是 C 的证据之一（一个纯路径常量表却把 Android 运行时写死在类初始化里），
 * 修复后类能正常加载、断言才有意义。为了不把"类加载失败"当成"缺陷已复现"的证据，
 * §变异测试 里另做了一次**只回退 `init` 方法体**的定点变异（保留类可加载），
 * 用它证明 A 的断言真的有判别力。
 *
 * 跑法：
 * ```
 * mkdir -p /tmp/r31-208-ts && cp R31FclPathTest.kt /tmp/r31-208-ts/
 * TEST_SRC=/tmp/r31-208-ts sh run-tests.sh
 * ```
 */
class R31FclPathTest {

    /**
     * 假 Context 的"存储根"。放在 companion 里（编译成静态字段）而不是实例字段 ——
     * `FakeContext` 是 `Unsafe.allocateInstance` 分配的（跳过构造函数），被跳过的也包括
     * 内部类那个指向外部实例的 `this$0` 引用；若 ctxRoot 是实例字段，`this$0` 为 null 就 NPE。
     * R31TraditionalChineseIndexTest 踩过同一坑（它靠"分配后反射补种字段"绕开）。
     */
    companion object {
        @Volatile
        private var ctxRoot: File? = null

        /**
         * 跳过构造函数分配实例。`android.jar` 里 `ContextWrapper(Context)` / `ApplicationInfo()`
         * 的构造器体都是 `throw new RuntimeException("Stub!")`（本机实测），正常 `new` 必炸。
         * 放在 companion（静态）里是必须的：[FakeContext] 是 `Unsafe` 分配的，它的 `this$0`
         * 为 null，调用**任何**外部实例方法都会 NPE。
         */
        private fun allocate(c: Class<*>): Any {
            val uc = Class.forName("sun.misc.Unsafe")
            val theUnsafe: Field = uc.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = theUnsafe.get(null)
            return uc.getMethod("allocateInstance", Class::class.java).invoke(unsafe, c)
        }
    }

    /**
     * `FAILED_DIRS` 是静态状态，跨用例存活（MiniRunner 每个用例 new 一次测试类，但类的静态字段不重置）。
     * 不清就会让"成功路径不应记失败"那条被前一条用例的残留污染 —— 实测踩过（expected 0 but was 1）。
     */
    @org.junit.Before
    fun clearFailureRecord() {
        failedDirsRaw().clear()
    }

    // ------------------------------------------------------------------
    // 1. A：init 失败必须留下痕迹（且不得抛异常）
    // ------------------------------------------------------------------

    /**
     * 对照用例：正常可建的目录 —— `init` 必须真的建出来，且**不被记成失败**。
     *
     * 没有这条，一个"永远报告失败"的实现也能让下面两条过。这条防的就是那种假修复。
     */
    @Test
    fun initCreatesMissingDirectoryWithoutRecordingFailure() {
        val root = Files.createTempDirectory("r31-208-ok").toFile()
        try {
            val target = File(root, "a/b/c")
            assertFalse("前置：目录此时不应存在", target.exists())

            initInvoke(target.absolutePath)

            assertTrue("init 必须真的把目录建出来", target.isDirectory)
            assertEquals("成功路径不得被记成失败", 0, failedDirs().size)
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * A 的主用例：父路径是**普通文件** ⇒ `mkdirs()` 恒 `false`（已在 JVM 上实测：本机
     * `File.mkdirs()` 返回 `false`，目标不存在）。修复前这个失败**没有任何痕迹**；
     * 修复后必须出现在 `FAILED_DIRS` 里，且 `init` 仍然不抛。
     */
    @Test
    fun initRecordsFailureWhenParentIsARegularFile() {
        val root = Files.createTempDirectory("r31-208-blocked").toFile()
        try {
            val blocker = File(root, "blocker")
            assertTrue("前置：需要先造出一个普通文件当父路径", blocker.createNewFile())
            val target = File(File(blocker, "sub"), "dir")
            assertFalse("前置：目标目录不可能已存在", target.isDirectory)

            initInvoke(target.absolutePath)

            val failed = failedDirs()
            assertTrue(
                "mkdirs() 失败必须被登记 —— 修复前这个失败被完全丢弃，调用方无从区分" +
                    "「目录已就绪」与「创建失败」。实际 FAILED_DIRS=$failed",
                failed.any { it == target.absolutePath }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * A 的第二形态（`exists()` 判据的漏洞）：目标路径上蹲着一个**同名普通文件**。
     * 旧实现 `if (!exists()) mkdirs();` 在这里直接跳过 —— 目录根本没建成，方法却静默返回。
     * 修复后必须判为失败。
     */
    @Test
    fun initRecordsFailureWhenARegularFileSitsAtThePath() {
        val root = Files.createTempDirectory("r31-208-shadow").toFile()
        try {
            val target = File(root, "shadowed")
            assertTrue("前置：造一个与目标同名的普通文件", target.createNewFile())
            assertTrue("前置：exists() 为真（正是旧判据误判的原因）", target.exists())
            assertFalse("前置：但它不是目录", target.isDirectory)

            initInvoke(target.absolutePath)

            assertTrue(
                "同名普通文件占位时目录并未就绪，必须登记为失败 —— 旧实现的 exists() 判据会静默放过。实际 FAILED_DIRS=${failedDirs()}",
                failedDirs().any { it == target.absolutePath }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * `init` 在失败路径上**不得抛**：它在 `FCLApp.onCreate` 的冷启动链上，
     * 抛异常 = 每次冷启动确定性闪退（这正是本单禁止的修法）。
     */
    @Test
    fun initNeverThrowsEvenWhenItCannotCreateTheDirectory() {
        val root = Files.createTempDirectory("r31-208-nothrow").toFile()
        try {
            val blocker = File(root, "blocker")
            blocker.createNewFile()
            val target = File(File(blocker, "sub"), "dir")
            // 任何 Throwable 都会让这条用例失败 —— 反过来就是"坏天气下不许炸启动路径"。
            initInvoke(target.absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 2. A：返回值与形状守卫（防回退）
    // ------------------------------------------------------------------

    /**
     * `loadPaths` 必须返回可读诊断（null = 全部就绪），返回值不得再是 void。
     * 这是"调用方能区分两种状态"的机械判据 —— 评审 `s10-eh-03` 要的「启动层失败分支」
     * 也得先有这个返回值才可能写出来（那条不在本单范围，此处只兑现本单的出口）。
     */
    @Test
    fun loadPathsExposesAFailureChannel() {
        val src = requireSource()
        val text = src.readText()

        assertTrue(
            "loadPaths 必须返回 String（失败原因，null=全部就绪）——修复前返回 void，错误码在此永久丢失",
            text.contains("public static String loadPaths(Context context)")
        )
        assertFalse(
            "不得再有「mkdirs() 返回值无接收者」的写法（原 :112 的 new File(path).mkdirs();）",
            Regex("""^\s*new File\([^)]*\)\.mkdirs\(\);\s*$""", RegexOption.MULTILINE).containsMatchIn(text)
        )
        assertTrue(
            "失败必须登记进 FAILED_DIRS（loadPaths 的返回值就由它产生）",
            text.contains("FAILED_DIRS.add(")
        )
        assertFalse(
            "init 不得用 exists() 当「已就绪」判据 —— 同名普通文件会让它误判",
            Regex("""if\s*\(\s*!\s*new File\([^)]*\)\.exists\(\)\s*\)""").containsMatchIn(text)
        )
    }

    // ------------------------------------------------------------------
    // 3. C：外部存储根目录的死路径必须清零
    // ------------------------------------------------------------------

    /**
     * C 的机械判据：`...getExternalStorageDirectory().getAbsolutePath() + "/FCL/..."`
     * 这种写法在本文件里必须彻底消失（它是 `/sdcard/FCL/{control,share,.minecraft}` 三处
     * 确定性失败来源）。注意**不**禁止 `new File(Environment.getExternalStorageDirectory(), …)`
     * —— 那是 `getExternalFilesDir(null)` 为 null 时的兜底，与 C 无关。
     */
    @Test
    fun externalStorageRootDeadPathsAreGone() {
        val text = requireSource().readText()

        assertFalse(
            "不得再用 getExternalStorageDirectory().getAbsolutePath() 拼 /FCL/... 目录：" +
                "本 App 无存储权限且 targetSdk=34，这些路径确定性不可写，对应的 init 必然静默失败",
            text.contains("getExternalStorageDirectory().getAbsolutePath()")
        )
        assertTrue(
            "CONTROLLER_DIR / SHARE_DIR 必须落在 App 私有目录（按定义直接挂在 FILES_DIR 下）",
            text.contains("CONTROLLER_DIR = FILES_DIR + \"/control\"") &&
                text.contains("SHARE_DIR = FILES_DIR + \"/share\"")
        )
        assertTrue(
            "SHARED_COMMON_DIR 不得再是类初始化器里的外部存储路径（会让整个类在没有 Android 运行时处" +
                "（单测）连加载都失败），应与 PRIVATE_COMMON_DIR 同源",
            text.contains("SHARED_COMMON_DIR = PRIVATE_COMMON_DIR")
        )
        assertFalse(
            "SHARED_COMMON_DIR 不得再有 \"= Environment.getExternalStorageDirectory()\" 形态的字段初始化器",
            Regex("""SHARED_COMMON_DIR\s*=\s*Environment""").containsMatchIn(text)
        )
    }

    // ------------------------------------------------------------------
    // 4. 端到端：真的调一次 loadPaths（唯一生产入口的形态），看返回值能不能区分两种状态
    // ------------------------------------------------------------------

    /**
     * 健康环境：所有目录都建得出来 ⇒ `loadPaths` 必须返回 **null**（无失败），且目录真的在盘上。
     *
     * 这条是上面那些反射用例的"分母"：证明修复后的路径常量表**不是**把所有目录都记成失败
     * （那样 `loadPaths` 永远报错，等于把启动层变成永久告警）。同时它是 C 的直接观测点 ——
     * 只要还有一条 `init` 指向 `Environment.getExternalStorageDirectory()`，在本 harness
     * （android.jar 桩）里就会以 `Stub!` 抛出来，这条立刻红。
     */
    @Test
    fun loadPathsReturnsNullWhenEveryDirectoryIsCreatable() {
        val root = Files.createTempDirectory("r31-208-load-ok").toFile()
        try {
            ctxRoot = root
            val ctx = newFakeContext()

            val result = loadPathsInvoke(ctx)

            assertEquals("所有目录都建得出来时，loadPaths 必须返回 null（无失败原因）", null, result)
            for ((name, path) in dirFields()) {
                if (path == null) continue
                assertTrue("$name 指向的目录必须真的建出来了：$path", File(path).isDirectory)
            }
            assertEquals("成功路径不得留下失败记录", 0, failedDirs().size)
        } finally {
            ctxRoot = null
            root.deleteRecursively()
        }
    }

    /**
     * 坏天气：`filesDir` 落在一个**普通文件**下面 ⇒ 所有挂在它下面的目录都建不出来。
     *
     * 断言两件事：
     * 1. `loadPaths` **不抛**（它在 `FCLApp.onCreate` 的冷启动链上，抛 = 闪退循环）；
     * 2. 返回值里点名了失败路径 —— 这就是"调用方能区分「目录已就绪」与「创建失败」"的兑现。
     *
     * 修复前 `loadPaths` 返回 void，这条用例连编译都过不了（见类注释）；
     * 只回退 `init` 方法体的变异体则是**返回 null 而目录不存在** —— 正好被这条打红。
     */
    @Test
    fun loadPathsReportsThePathsItCouldNotCreate() {
        val root = Files.createTempDirectory("r31-208-load-bad").toFile()
        try {
            val blocker = File(root, "blocker")
            assertTrue(blocker.createNewFile())
            ctxRoot = blocker // 让 filesDir = <普通文件>/files
            val ctx = newFakeContext()

            val result = loadPathsInvoke(ctx)

            assertNotNull(
                "目录建不出来时 loadPaths 必须返回可读失败原因，而不是静默返回 null —— " +
                    "否则调用方无从区分「目录已就绪」与「创建失败」（本单 A 的 claim 原文）",
                result
            )
            assertTrue(
                "失败原因里必须点名具体路径，只给一句「失败了」等于把错误码再丢一次。实际：$result",
                result!!.contains(File(blocker, "files").absolutePath)
            )
        } finally {
            ctxRoot = null
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * 只覆写 `loadPaths` 会用到的那些方法，路径全部落在 [ctxRoot] 下。
     *
     * 为什么用 `Unsafe.allocateInstance` 分配：`android.jar` 里 `ContextWrapper(Context)` 的
     * 构造函数体是 `throw new RuntimeException("Stub!")`（本机实测），`new FakeContext()` 根本
     * 构造不出来。跳过构造函数后我们**覆写**的方法仍按虚分派生效（R26/R31 两条既有测试同款）。
     * 同理 `ApplicationInfo` 也必须跳过构造函数 —— 它的构造器也抛 `Stub!`。
     * 用 `inner class`（而非嵌套类）+ companion 里的 ctxRoot：instance 方法里能直接读静态字段，
     * 而 `this$0` 因为是 `Unsafe` 分配，为 null —— 所以**不能**碰任何外部实例成员。
     */
    private inner class FakeContext : android.content.ContextWrapper(null) {
        @Suppress("UNUSED_PARAMETER")
        override fun getFilesDir(): File = File(ctxRoot!!, "files")

        override fun getCacheDir(): File = File(ctxRoot!!, "cache")

        override fun getDir(name: String?, mode: Int): File = File(ctxRoot!!, "app_$name")

        override fun getExternalFilesDir(type: String?): File = File(ctxRoot!!, "ext_files")

        override fun getPackageName(): String = "com.dsh.fcl.androidlauncher"

        override fun getApplicationInfo(): android.content.pm.ApplicationInfo {
            val ai = allocate(android.content.pm.ApplicationInfo::class.java)
                as android.content.pm.ApplicationInfo
            android.content.pm.ApplicationInfo::class.java
                .getField("nativeLibraryDir").set(ai, File(ctxRoot!!, "nativelib").absolutePath)
            return ai
        }
    }

    private fun newFakeContext(): android.content.Context =
        allocate(FakeContext::class.java) as android.content.Context

    private fun fclPathClass(): Class<*> =
        Class.forName("com.tungsten.fclauncher.utils.FCLPath")

    private fun loadPathsInvoke(ctx: android.content.Context): String? {
        val m: Method = fclPathClass().getMethod("loadPaths", android.content.Context::class.java)
        try {
            return m.invoke(null, ctx) as String?
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException
            throw AssertionError(
                "loadPaths 绝不能抛（它在 FCLApp.onCreate 的冷启动链上，抛 = 每次冷启动闪退循环）",
                cause
            )
        }
    }

    /** 读回 `loadPaths` 赋过值的目录字段，逐个核对"确实建出来了"。 */
    private fun dirFields(): List<Pair<String, String?>> {
        val names = listOf(
            "LOG_DIR", "CACHE_DIR", "RUNTIME_DIR", "MOD_RUNTIME_DIR",
            "JAVA_8_PATH", "JAVA_17_PATH", "JAVA_21_PATH", "JAVA_25_PATH",
            "LWJGL_DIR", "CACIOCAVALLO_8_DIR", "CACIOCAVALLO_17_DIR",
            "FILES_DIR", "PLUGIN_DIR", "BACKGROUND_DIR", "SKIN_DIR",
            "CONTROLLER_DIR", "SHARE_DIR", "PRIVATE_COMMON_DIR", "SHARED_COMMON_DIR"
        )
        val cls = fclPathClass()
        return names.map { n -> n to (cls.getField(n).get(null) as String?) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun failedDirsRaw(): MutableList<String> {
        val f: Field = fclPathClass().getDeclaredField("FAILED_DIRS")
        f.isAccessible = true
        return f.get(null) as MutableList<String>
    }

    private fun failedDirs(): List<String> = ArrayList(failedDirsRaw())

    /** 反射调 `private static void init(String)`，把它抛的任何异常原样逃出来（用例靠"不抛"断言）。 */
    private fun initInvoke(path: String) {
        val m: Method = fclPathClass().getDeclaredMethod("init", String::class.java)
        m.isAccessible = true
        try {
            m.invoke(null, path)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw RuntimeException(cause)
            }
        }
    }

    /**
     * 读 `FCLPath.java` 源码：形状守卫这类断言只能在源码层做（例如"返回值不得是 void"）。
     * 从 cwd 向上找，兼容 `FCL/`、裸 `src/` 两种布局（与其他 R3x 测试同款）。
     */
    private fun requireSource(): File {
        val rel = "FCL/src/main/java/com/tungsten/fclauncher/utils/FCLPath.java"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            File(dir, rel).takeIf { it.isFile }?.let { return it }
            File(dir, rel.removePrefix("FCL/")).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        assertNotNull("找不到 $rel —— 请在仓库根或 FCL/ 目录下运行测试", null)
        throw IllegalStateException("unreachable")
    }
}
