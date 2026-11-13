package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.net.URLClassLoader
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.zip.ZipFile

/**
 * round31 / slice-10 error-handling 回归测试（fix-r31-217）。
 *
 * 覆盖三条：
 * - **F-08** `Architecture`：未知 ABI 被当成「确认是 ARM64」。
 * - **F-09** `RuntimeUtils.isLatest`：资源读不到 = 「已是最新」（**未修复**，见文件末尾 §F-09）。
 * - **F-10** `ImageUtil.loadInto`：`onLoadFailed` 空实现吞掉失败。
 *
 * ## 判别力设计（先说清楚"红在哪"）
 *
 * | 用例 | 改前 | 改后 | 变异能否逃脱 |
 * |---|---|---|---|
 * | `f8UnknownAbiIsNotReportedAsArm64` | 红（dev=1 isArm=true） | 绿 | M1 回退 `isArmDevice` ⇒ 红 |
 * | `f8KnownAbisUnchanged` | 绿（反向守卫） | 绿 | M4 一律返回 -1 ⇒ 红 |
 * | `f10LoadFailureIsLoggedAtWarning` | 红（0 条日志） | 绿 | M2 降级成 FINE ⇒ 红 |
 * | `f10ReadyPathStillSetsBackground` | 绿（反向守卫） | 绿 | M3 删掉 `view.background =` ⇒ 红 |
 *
 * 全部断言落在**运行时可观测值**上：被测类的真实返回值 / 真实 `Logging.LOG` 记录 / 真实
 * `View.setBackground` 收到的对象。没有任何源码文本断言、没有结构反射当唯一证据 ——
 * 本轮已多次证明后者形同虚设（fix-210 的 M2、verify-210 的 6 种逃脱、verify-213 的 OFA/OFB）。
 *
 * ## 为什么不直接 `import` 被测类
 *
 * 两个原因，都不是洁癖：
 * 1. **F-10 需要 Glide**。`ImageUtil$Companion$loadInto$1` 继承 `CustomViewTarget`，而
 *    `run-tests.sh` 的 harness classpath（stdlib/coroutines/gson/android.jar）**没有 Glide**。
 *    直接 `Class.forName` 会 `NoClassDefFoundError`。所以从 Gradle 缓存里的
 *    `glide-4.16.0.aar` 解出 `classes.jar`，连同主代码产物一起塞进一个 `parent=null` 的子加载器。
 * 2. **F-08 需要改 `android.os.Build` 的静态字段**。`SUPPORTED_64_BIT_ABIS` 是
 *    `static final String[]`，`Field.set` 会抛 `IllegalAccessException`（实测），只能用
 *    `sun.misc.Unsafe.putObject` 越过 final。**不写静态 import**（`sun.misc.*` 在 Android
 *    单测编译期同样可能 Unresolved），全部走 `Class.forName` 字符串引用 —— 与本轮
 *    `R31Fix204Test` / `R31Fix215Test` 已验证过的反射外壳同一口径（TC-01 的 HIGH 回归就是
 *    静态 import JDK-only 包让整个模块 660 条单测编译失败）。
 *
 * ## F-10 为什么要运行时编译 stub 的 `View` / `Drawable`
 *
 * `android.jar` 里所有方法体都是 `throw new RuntimeException("Stub!")`，其中包括
 * `View.setBackground`。若用 `android.jar` 的那个 `View`，`onResourceReady` 一进
 * `view.background = resource` 就抛 `Stub!`，**"成功路径仍然设了背景"这条反向守卫根本观察不到**。
 * 所以临时目录里编一份真会干活的 `android.view.View` / `android.graphics.drawable.Drawable`，
 * 放在子加载器 URL 列表**最前面**遮蔽 `android.jar` 的同名类。这样两条路径都是行为断言。
 */
class R31Fix217Test {

    // ====================================================== 共用：classpath / 子加载器

    /** 从 `java.class.path` 里找**目录**形式、且含该相对路径的条目（主代码产物在 Gradle/harness 下都是目录）。 */
    private fun classpathEntryContaining(rel: String): File? {
        val cp = System.getProperty("java.class.path") ?: return null
        for (entry in cp.split(File.pathSeparator)) {
            if (entry.isEmpty()) continue
            val f = File(entry)
            if (f.isDirectory && File(f, rel).isFile) return f
        }
        return null
    }

    private fun classpathUrls(): List<URL> {
        val cp = System.getProperty("java.class.path") ?: ""
        return cp.split(File.pathSeparator).filter { it.isNotEmpty() }.map { File(it).toURI().toURL() }
    }

    /**
     * `parent = null` 的子加载器：`extra` 排在最前（让 stub 遮蔽 `android.jar`），
     * 其后是宿主 classpath 全量（主代码产物 + android.jar + stdlib 都在里面）。
     */
    private fun childLoader(extra: List<URL>): URLClassLoader =
        URLClassLoader((extra + classpathUrls()).toTypedArray(), null)

    // ====================================================== 共用：Unsafe

    /**
     * 取 `sun.misc.Unsafe.theUnsafe`。走字符串反射，**不写 `import sun.misc.Unsafe`**。
     * 拿不到时明确失败（不静默跳过 —— 跳过等于这条用例永远绿）。
     */
    private fun unsafeInstance(): Any {
        val cls = Class.forName("sun.misc.Unsafe")
        val field = cls.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return field.get(null) as Any
    }

    private fun readStaticObject(unsafe: Any, owner: Class<*>, name: String): Any? {
        val f = owner.getField(name)
        val uc = unsafe.javaClass
        val base = uc.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, f)
        val off = uc.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, f) as Long
        return uc.getMethod("getObject", Any::class.java, Long::class.javaPrimitiveType)
            .invoke(unsafe, base, off)
    }

    /** 越过 `static final` 写引用字段。`Field.set` 对 final 字段抛 `IllegalAccessException`（实测）。 */
    private fun writeStaticObject(unsafe: Any, owner: Class<*>, name: String, value: Any?) {
        val f = owner.getField(name)
        val uc = unsafe.javaClass
        val base = uc.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, f)
        val off = uc.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, f) as Long
        uc.getMethod(
            "putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java
        ).invoke(unsafe, base, off, value)
    }

    // ====================================================== F-08

    /**
     * 设备 ABI 的"夹具"：改 `android.os.Build` 的两个静态数组，跑完还原。
     *
     * 为什么要还原：Gradle 单测默认同一个 JVM 跑完整模块（`forkEvery = 0`），不还原会把
     * "arm64 设备"泄漏给后面所有用例 —— 那类跨用例污染正是最难查的假绿/假红。
     */
    private fun withAbis(a64: Array<String>, a32: Array<String>, block: (Class<*>) -> Unit) {
        val mainJava = classpathEntryContaining("com/tungsten/fclauncher/utils/Architecture.class")
        assertNotNull(
            "找不到主代码 Java 产物目录（Architecture.class）—— 请先编译 debug 产物",
            mainJava
        )
        val loader = childLoader(emptyList())
        try {
            val build = Class.forName("android.os.Build", true, loader)
            val arch = Class.forName("com.tungsten.fclauncher.utils.Architecture", true, loader)
            val unsafe = unsafeInstance()
            val old64 = readStaticObject(unsafe, build, "SUPPORTED_64_BIT_ABIS")
            val old32 = readStaticObject(unsafe, build, "SUPPORTED_32_BIT_ABIS")
            try {
                writeStaticObject(unsafe, build, "SUPPORTED_64_BIT_ABIS", a64)
                writeStaticObject(unsafe, build, "SUPPORTED_32_BIT_ABIS", a32)
                block(arch)
            } finally {
                writeStaticObject(unsafe, build, "SUPPORTED_64_BIT_ABIS", old64)
                writeStaticObject(unsafe, build, "SUPPORTED_32_BIT_ABIS", old32)
                loader.close()
            }
        } catch (e: Throwable) {
            runCatching { loader.close() }
            throw e
        }
    }

    private fun deviceArch(arch: Class<*>): Int =
        arch.getMethod("getDeviceArchitecture").invoke(null) as Int

    private fun isArm(arch: Class<*>): Boolean =
        arch.getMethod("isArmDevice").invoke(null) as Boolean

    private fun archConst(arch: Class<*>, name: String): Int = arch.getField(name).getInt(null)

    /**
     * F-08 的**主判据**：一个都认不出来的 ABI 必须是「未知」，不能是「确认 ARM64」。
     *
     * 改前实测：`dev=1 (=ARCH_ARM64) isArm=true` —— 两条断言都会红。
     */
    @Test
    fun f8UnknownAbiIsNotReportedAsArm64() {
        withAbis(arrayOf("mips64"), arrayOf()) { arch ->
            val unsupported = archConst(arch, "UNSUPPORTED_ARCH")
            val arm64 = archConst(arch, "ARCH_ARM64")
            // 前提校验：哨兵值不能和"确认 ARM64"撞值，否则下面的断言就没有意义了
            assertTrue("UNSUPPORTED_ARCH 必须与 ARCH_ARM64 不同值", unsupported != arm64)
            assertEquals(
                "未知 ABI（mips64）必须报 UNSUPPORTED_ARCH，而不是被默认成 ARM64",
                unsupported, deviceArch(arch)
            )
            assertFalse(
                "未知 ABI 不得被 isArmDevice() 判成 arm",
                isArm(arch)
            )
        }
    }

    /**
     * F-08 的第二个未知形态：`SUPPORTED_*_BIT_ABIS` 全空（模拟系统没报任何 ABI）。
     * 这条与上一条是**不同入口**：上一条走到的是 `archAsInt` 的兜底分支，这一条连循环都不进。
     */
    @Test
    fun f8NoAbiAtAllIsNotReportedAsArm64() {
        withAbis(arrayOf(), arrayOf()) { arch ->
            assertEquals(
                "ABI 列表全空 = 探测失败，必须报 UNSUPPORTED_ARCH",
                archConst(arch, "UNSUPPORTED_ARCH"), deviceArch(arch)
            )
            assertFalse("ABI 列表全空时 isArmDevice() 不得为 true", isArm(arch))
        }
    }

    /**
     * F-08 的**反向守卫**：已知 ABI 的返回值必须与改前逐位一致。
     * 少了这条，"一律返回 UNSUPPORTED_ARCH"这种过度修复也会全绿。
     */
    @Test
    fun f8KnownAbisUnchanged() {
        withAbis(arrayOf("arm64-v8a"), arrayOf()) { arch ->
            assertEquals("arm64 设备", archConst(arch, "ARCH_ARM64"), deviceArch(arch))
            assertTrue("arm64 设备必须是 arm", isArm(arch))
        }
        withAbis(arrayOf("aarch64"), arrayOf()) { arch ->
            assertEquals("aarch64 别名同样映射到 ARCH_ARM64", archConst(arch, "ARCH_ARM64"), deviceArch(arch))
        }
        withAbis(arrayOf("x86_64"), arrayOf()) { arch ->
            assertEquals("x86_64 设备", archConst(arch, "ARCH_X86_64"), deviceArch(arch))
            assertFalse("x86_64 设备不是 arm", isArm(arch))
        }
        // 32 位设备：64 位列表为空 → 走 SUPPORTED_32_BIT_ABIS
        withAbis(arrayOf(), arrayOf("armeabi-v7a")) { arch ->
            assertEquals("32 位 arm 设备", archConst(arch, "ARCH_ARM"), deviceArch(arch))
            assertTrue("32 位 arm 设备必须是 arm", isArm(arch))
        }
        withAbis(arrayOf(), arrayOf("x86")) { arch ->
            assertEquals("32 位 x86 设备", archConst(arch, "ARCH_X86"), deviceArch(arch))
        }
    }

    /**
     * F-08 的既有语义（asus zenfone：arm 排在原生指令集之前）不能被我改坏：
     * 同时报 arm 与 x86 的**设备**，`getDeviceArchitecture()` 仍取 x86 支。
     */
    @Test
    fun f8X86WinsWhenMixedWithArm() {
        withAbis(arrayOf("arm64-v8a", "x86_64"), arrayOf()) { arch ->
            assertEquals(
                "混合 ABI 设备仍按 x86 优先（改前语义）",
                archConst(arch, "ARCH_X86_64"), deviceArch(arch)
            )
        }
    }

    // ====================================================== F-10

    /**
     * 运行时编译的 stub 目录 + glide classes.jar。`stubDir` 必须排在子加载器 URL 最前，
     * 才能遮蔽 `android.jar` 里 `throw new RuntimeException("Stub!")` 的那份。
     */
    private class ImageFixture(
        val dir: File,
        val loader: URLClassLoader,
        val anon: Class<*>,
        val target: Any,
        val view: Any,
        val viewClass: Class<*>,
        val drawableClass: Class<*>,
        val transitionClass: Class<*>,
        val log: Logger
    ) {
        fun fail(): List<Pair<Level, String>> {
            val seen = ArrayList<Pair<Level, String>>()
            val h = collect(seen)
            log.addHandler(h)
            try {
                anon.getMethod("onLoadFailed", drawableClass).invoke(target, null)
            } finally {
                log.removeHandler(h)
            }
            return seen
        }

        fun ready(drawable: Any) {
            anon.getMethod("onResourceReady", drawableClass, transitionClass)
                .invoke(target, drawable, null)
        }

        fun newDrawable(): Any = drawableClass.getConstructor().newInstance()

        fun background(): Any? = viewClass.getField("background").get(view)

        private fun collect(sink: MutableList<Pair<Level, String>>): Handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    sink.add(record.level to record.message)
                }

                override fun flush() {}
                override fun close() {}
            }
    }

    private fun newImageFixture(): ImageFixture {
        val mainKt = classpathEntryContaining("com/mio/util/ImageUtil\$Companion.class")
        assertNotNull(
            "找不到主代码 Kotlin 产物目录（ImageUtil）—— 请先编译 debug 产物",
            mainKt
        )
        val glideJar = extractGlideClasses()
        val dir = File(System.getProperty("java.io.tmpdir"), "r31-217-img-" + System.nanoTime())
        val src = File(dir, "src")
        val stubOut = File(dir, "stub")
        stubOut.mkdirs()
        writeSource(File(src, "android/view/View.java"), STUB_VIEW)
        writeSource(File(src, "android/content/Context.java"), STUB_CONTEXT)
        writeSource(File(src, "android/graphics/drawable/Drawable.java"), STUB_DRAWABLE)

        val androidJar = androidJar()
        assertNotNull(
            "找不到 android.jar（stub View 需要它解析 Context 等类型）；" +
                "harness 通过 -Ddsh.android.jar 传入，Gradle 单测走 SDK 默认路径",
            androidJar
        )
        val compiler = systemJavaCompiler()
        assertNotNull("本测试需要 JDK（javax.tools.JavaCompiler），JRE 上无法编译 stub", compiler)
        val compiled = compileWithReflection(
            compiler!!,
            listOf(
                File(src, "android/view/View.java"),
                File(src, "android/content/Context.java"),
                File(src, "android/graphics/drawable/Drawable.java")
            ),
            listOf(
                "-d", stubOut.absolutePath, "-encoding", "UTF-8", "-nowarn",
                "-cp", androidJar!!.absolutePath
            )
        )
        assertTrue("stub View 编译失败：" + compiled.diagnostics, compiled.ok)

        // 顺序有意义：stub 目录在最前 ⇒ 遮蔽 android.jar 的同名类
        val loader = childLoader(
            listOf(stubOut.toURI().toURL(), glideJar.toURI().toURL())
        )
        val targetSuper = Class.forName("com.bumptech.glide.request.target.CustomViewTarget", true, loader)
        val viewClass = Class.forName("android.view.View", true, loader)
        val maskedFrom = viewClass.protectionDomain?.codeSource?.location?.path ?: ""
        assertTrue(
            "stub View 没有被遮蔽成功（还在用 android.jar 里那份，setBackground 会抛 Stub!）；实际来源=$maskedFrom",
            maskedFrom.contains("r31-217-img")
        )
        val view = viewClass.getDeclaredConstructor().newInstance()

        // 匿名 target 类名带编译期序号，按"实现了 CustomViewTarget"扫包目录更稳
        val pkgDir = File(mainKt, "com/mio/util")
        var anon: Class<*>? = null
        for (f in pkgDir.listFiles() ?: emptyArray()) {
            val n = f.name
            if (!n.startsWith("ImageUtil") || !n.endsWith(".class")) continue
            val c = Class.forName("com.mio.util." + n.substring(0, n.length - ".class".length), false, loader)
            if (targetSuper.isAssignableFrom(c)) {
                anon = c
                break
            }
        }
        assertNotNull("com.mio.util.ImageUtil\$Companion\$loadInto\$N 未被找到", anon)
        val ctor = anon!!.getDeclaredConstructors()[0]
        ctor.isAccessible = true
        val target = ctor.newInstance(view)

        val logging = Class.forName("com.tungsten.fclcore.util.Logging", true, loader)
        val log = logging.getField("LOG").get(null) as Logger
        return ImageFixture(
            dir, loader, anon, target, view, viewClass,
            Class.forName("android.graphics.drawable.Drawable", true, loader),
            Class.forName("com.bumptech.glide.request.transition.Transition", true, loader),
            log
        )
    }

    private fun withImage(block: (ImageFixture) -> Unit) {
        val fixture = newImageFixture()
        try {
            block(fixture)
        } finally {
            runCatching { fixture.loader.close() }
            fixture.dir.deleteRecursively()
        }
    }

    /**
     * F-10 的**主判据**：加载失败必须在 `Logging.LOG` 上留下一条可观测的 WARNING。
     *
     * 改前实测 `records=[]`（空实现，零日志）—— 断言红。
     */
    @Test
    fun f10LoadFailureIsLoggedAtWarning() {
        withImage { fx ->
            val records = fx.fail()
            assertTrue(
                "onLoadFailed 必须留下日志，否则'启动页背景空白'在排障时零线索；实际记录=$records",
                records.isNotEmpty()
            )
            assertTrue(
                "失败必须记在 WARNING（或更高）级别，否则默认 INFO 级别下不可见；实际=$records",
                records.any { it.first.intValue() >= Level.WARNING.intValue() }
            )
        }
    }

    /**
     * 判别力的**抗逃脱**设计：断言"日志级别够高"，而不只是"有一次日志调用"。
     * 把 `Level.WARNING` 降成 `Level.FINE`（调用仍在、但默认级别下不可观测）必须变红。
     * 上面那条已覆盖此点；这一条额外钉住"多次失败都要记"（不是只记第一次）。
     */
    @Test
    fun f10EveryFailureIsLogged() {
        withImage { fx ->
            val first = fx.fail().size
            val second = fx.fail().size
            assertEquals("第二次失败也必须留下 1 条记录", first, second)
            assertTrue("两次失败都应各留 1 条 WARNING，实际 first=$first second=$second", first >= 1 && second >= 1)
        }
    }

    /**
     * F-10 的**反向守卫**：成功路径照旧 —— `onResourceReady` 仍然把 resource 设成背景，
     * 且不因为这次改动多写日志。没有这条，"把 onLoadFailed 改成什么都不做/顺手删掉
     * onResourceReady"也能全绿。
     */
    @Test
    fun f10ReadyPathStillSetsBackground() {
        withImage { fx ->
            val drawable = fx.newDrawable()
            fx.ready(drawable)
            assertTrue("onResourceReady 必须把 drawable 设成背景", fx.background() != null)
            assertTrue("背景必须是传进来的那个 drawable", fx.background() === drawable)
        }
    }

    // ====================================================== 夹具：glide / android.jar / javac

    /**
     * 从 Gradle 缓存里取 `glide-<ver>.aar` 并解出 `classes.jar`。
     *
     * 为什么不直接用 `~/.gradle/caches/<ver>/transforms/...` 的产物：那里一份是 `-api.jar`
     * （只有签名）一份是 `-runtime.jar`（缺 `com.bumptech.glide.R$id`，实测
     * `NoClassDefFoundError: com/bumptech/glide/R$id`）。AAR 里的 `classes.jar` 是唯一自洽的来源。
     * 缺件时**明确失败**并给出补救方式，不静默跳过（跳过 = 用例永远绿）。
     */
    private fun extractGlideClasses(): File {
        val home = File(
            System.getProperty("r31.gradle.home") ?: (System.getProperty("user.home") ?: "."),
            ".gradle/caches/modules-2/files-2.1/com.github.bumptech.glide/glide"
        )
        val aars = (home.listFiles() ?: emptyArray())
            .flatMap { ver -> (ver.listFiles() ?: emptyArray()).map { hash -> File(hash, "glide-${ver.name}.aar") } }
            .filter { it.isFile }
            .sortedBy { it.absolutePath }
        assertNotNull(
            "Gradle 缓存里找不到 glide-*.aar（找过 $home）。先跑一次 ./gradlew :FCL:compileDebugKotlin 把依赖拉进缓存",
            aars.firstOrNull()
        )
        val aar = aars.first()
        val out = File(System.getProperty("java.io.tmpdir"), "r31-217-glide-" + System.nanoTime() + ".jar")
        ZipFile(aar).use { zf ->
            val entry = zf.getEntry("classes.jar")
            assertNotNull("$aar 里没有 classes.jar", entry)
            zf.getInputStream(entry).use { input: InputStream ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
        }
        out.deleteOnExit()
        return out
    }

    private fun androidJar(): File? {
        System.getProperty("dsh.android.jar")?.let { p ->
            File(p).takeIf { it.isFile }?.let { return it }
        }
        val sdk = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
        if (sdk != null) {
            File(sdk, "platforms/android-35/android.jar").takeIf { it.isFile }?.let { return it }
            (File(sdk, "platforms").listFiles() ?: emptyArray())
                .sortedByDescending { it.name }
                .forEach { d -> File(d, "android.jar").takeIf { it.isFile }?.let { return it } }
        }
        val local = resolveUnder("local.properties")
        if (local != null) {
            val line = local.readLines().firstOrNull { it.trimStart().startsWith("sdk.dir=") }
            if (line != null) {
                val p = line.substringAfter("sdk.dir=").trim().replace("\\\\", "\\").replace('\\', '/')
                File("$p/platforms/android-35/android.jar").takeIf { it.isFile }?.let { return it }
            }
        }
        return null
    }

    private fun writeSource(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

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

    // 与 R31Fix204Test / R31Fix215Test 同一套 javax.tools 反射外壳：**不写静态 import**。
    private class CompileResult(val ok: Boolean, val diagnostics: String)

    private fun systemJavaCompiler(): Any? = try {
        Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
    } catch (e: ClassNotFoundException) {
        null
    }

    private fun compileWithReflection(compiler: Any, files: List<File>, options: List<String>): CompileResult {
        val compilerIface = Class.forName("javax.tools.JavaCompiler")
        val listenerIface = Class.forName("javax.tools.DiagnosticListener")
        val fileManagerIface = Class.forName("javax.tools.JavaFileManager")
        val standardFileManagerIface = Class.forName("javax.tools.StandardJavaFileManager")
        val taskIface = Class.forName("javax.tools.JavaCompiler\$CompilationTask")
        val diagnosticCollectorClass = Class.forName("javax.tools.DiagnosticCollector")

        val diagnostics = diagnosticCollectorClass.getDeclaredConstructor().newInstance()
        val fileManager = compilerIface.getMethod(
            "getStandardFileManager", listenerIface, java.util.Locale::class.java,
            java.nio.charset.Charset::class.java
        ).invoke(compiler, diagnostics, null, Charsets.UTF_8)
        try {
            val fileObjects = standardFileManagerIface
                .getMethod("getJavaFileObjectsFromFiles", Iterable::class.java)
                .invoke(fileManager, files)
            val task = compilerIface.getMethod(
                "getTask", java.io.Writer::class.java, fileManagerIface, listenerIface,
                Iterable::class.java, Iterable::class.java, Iterable::class.java
            ).invoke(compiler, null, fileManager, diagnostics, options, null, fileObjects)
            val ok = taskIface.getMethod("call").invoke(task) as Boolean
            return CompileResult(
                ok,
                (diagnosticCollectorClass.getMethod("getDiagnostics").invoke(diagnostics) as List<*>)
                    .joinToString("; ") { it.toString() }
            )
        } finally {
            runCatching { fileManagerIface.getMethod("close").invoke(fileManager) }
        }
    }

    // ====================================================== F-09（未修复，只钉调用侧兜底）

    /**
     * F-09 的结论：`RuntimeUtils.isLatest` 本身**修不了**（禁改文件），所以这里钉的是
     * **调用侧兜底**——即使 `isLatest` 说谎（资源读不到就 `return true`），
     * `readinessGaps` 仍必须报出缺口。
     *
     * 这是 F-09-ALT-1 的可执行版本。`collectGaps()` 在 `runtimeUtils` 之外还有三条独立的
     * "目标在不在"判据：
     * - `scriptsCurrent = true`（= `isLatest` 说「已是最新」）但 `startScriptExists = false`
     *   ⇒ 必须报 `SCRIPTS_MISSING`；
     * - `rootfsCurrent = true` 但 `rootfsUsable = false`（目标目录里连 `bin/sh`/`usr/bin/env`/
     *   `bin/busybox` 都没有）⇒ 必须报 `ROOTFS_MISSING`。
     *
     * 这两条正是"最该重解压的场景返回无需解压"被二次拦截的地方（见 `DshBootstrap.kt:432-436`
     * 与 `:480-484` 的注释）。注意：`rootfsCurrent=true` 时 `OUTDATED` **不会**出现 ——
     * `readinessGaps` 把 `!rootfsCurrent` 单独归到 `OUTDATED`，兜底走的是另一条 key，这条
     * 用例因此必须按 `ROOTFS_MISSING` 断言（写错过一次，`实际=[SCRIPTS_MISSING, ROOTFS_MISSING]`）。
     *
     * 这条用例**改前改后都绿**（它守护的是既有兜底，不是本单的新行为）。写它是因为这个缺口
     * 一旦被未来某轮去除兜底就会静默复发，而 F-09 本体已被判 NOT_FIXED。
     */
    @Test
    fun f9CallerBackstopStillReportsGapWhenIsLatestLies() {
        assertNotNull(
            "找不到 DshBootstrapKt.class（readinessGaps 的宿主类）",
            classpathEntryContaining("com/dsh/core/DshBootstrapKt.class")
        )
        val loader = childLoader(emptyList())
        try {
            val kt = Class.forName("com.dsh.core.DshBootstrapKt", true, loader)
            @Suppress("UNCHECKED_CAST")
            val gaps = kt.getMethod(
                "readinessGaps",
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            ).invoke(
                null,
                // rootfsCurrent / scriptsCurrent 都为 true —— 模拟 isLatest 说谎
                true, true, false,
                // start-dsh.sh 不在
                false,
                true, true, true,
                // rootfs 里连 bin/sh 都没有（"目标缺失"最该重解压的场景）
                false
            ) as List<*>
            val names = gaps.map { (it as Enum<*>).name }
            assertTrue(
                "isLatest 谎称最新但 rootfs 目录不可用时，必须报 ROOTFS_MISSING（调用侧兜底）；实际=$names",
                names.contains("ROOTFS_MISSING")
            )
            assertTrue(
                "isLatest 谎称最新但 start-dsh.sh 缺失时，必须报 SCRIPTS_MISSING；实际=$names",
                names.contains("SCRIPTS_MISSING")
            )
        } finally {
            runCatching { loader.close() }
        }
    }

    // ====================================================== stub 源码

    private companion object {
        private const val STUB_CONTEXT = """
package android.content;
/** 测试 stub：只满足类型解析，不被真正调用。 */
public class Context {
}
"""

        private const val STUB_VIEW = """
package android.view;
import android.content.Context;
import android.graphics.drawable.Drawable;
/**
 * 测试 stub：android.jar 里 View 的方法体全是 `throw new RuntimeException("Stub!")`，
 * 那样 onResourceReady 里的 `view.background = resource` 会直接抛，成功路径无从观察。
 * 这份把 background 存进字段，让两条路径都变成行为断言。
 */
public class View {
    public static final int NO_ID = -1;
    public Drawable background;
    public void setBackground(Drawable d) { this.background = d; }
    public Drawable getBackground() { return background; }
    public Object getTag(int key) { return null; }
    public void setTag(int key, Object tag) { }
    public void addOnAttachStateChangeListener(OnAttachStateChangeListener l) { }
    public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l) { }
    public boolean isAttachedToWindow() { return true; }
    public int getWidth() { return 100; }
    public int getHeight() { return 100; }
    public Context getContext() { return null; }
    public interface OnAttachStateChangeListener {
        void onViewAttachedToWindow(View v);
        void onViewDetachedFromWindow(View v);
    }
}
"""

        private const val STUB_DRAWABLE = """
package android.graphics.drawable;
/** 测试 stub：让 onResourceReady 收到的对象可被 identity 比较。 */
public class Drawable {
    @Override public String toString() { return "stub-drawable"; }
}
"""
    }
}
