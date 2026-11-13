package com.dsh

import com.tungsten.fclcore.event.Event
import com.tungsten.fclcore.event.EventManager
import com.tungsten.fclcore.event.EventPriority
import com.tungsten.fclcore.util.Logging
import org.junit.Assert
import org.junit.Test
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * round31 / slice-11 data-consistency 回归测试（fix-r31-215）。
 *
 * 三条缺陷的共同形态是**静默的数据不一致**：注册两次就投递两次、一个 handler 抛异常就吞掉后续
 * 投递、负数 dp 偏移一格。因此这里一律断言**运行时可观测值**（投递次数、调用次数、方法返回的
 * 具体整数），不做源码文本断言、不拿结构反射当唯一证据 —— 本轮已有多个 Agent 证明后者形同虚设
 * （fix-210 的 M2、verify-210 的 6 种逃脱、verify-211 的 M6/M7、verify-213 的 OFA/OFB）。
 *
 * ## F2 的判别力设计
 *
 * 陷阱：`EventManager` 的容器是 `CopyOnWriteArraySet`，**Set 天然按 equals 去重**。所以
 * "同一 consumer 注册两次只投递一次"这个断言，在**容器装了两个相等包装对象**时也成立 ——
 * 只数最终次数会混淆「守卫生效」与「Set 吞掉了重复」。真正的判别靠**成对断言**：
 *
 * | 输入 | 修复前 | 修复后 | 断言 |
 * |---|---|---|---|
 * | 同一个 consumer 注册 2 次 | 2 次投递 | 1 次 | = 1 |
 * | 两个不同 consumer 各注册 1 次 | 2 次投递 | 2 次 | = 2（防过度合并）|
 *
 * 两行必须同时绿：只测第一行，把 `register` 改成空实现也能绿；只测第二行，改前本来就绿。
 * 计数用 `AtomicInteger`，保证"真被调用了几次"可数。
 *
 * ## F3 的判别力设计
 *
 * 失败模式是**控制流提前离开**，判据是"抛异常的那个之后的 handler 有没有被执行"：
 *  - HIGHEST 抛 → NORMAL 与 LOW 是否仍被调用（跨优先级）
 *  - NORMAL 的第一个抛 → NORMAL 的第二个是否仍被调用（同优先级 peer）
 *  - 抛异常时 `fireEvent` 是否仍返回 handler 设置过的 `Result`（返回值契约）
 *  - 异常是否**可见**（`Logging.LOG` 上有 SEVERE 记录），不是静默吞掉
 *
 * 每条失败用例都用 `thrown` 标志位**证明"抛的那个真的抛了"**，否则"后续 handler 被调用"
 * 在"异常根本没发生"时也成立 —— 那是假绿。
 *
 * ## F5 的判别力设计
 *
 * harness 的 classpath 上有真 `android.jar`，但其中**所有方法体都是
 * `throw new RuntimeException("Stub!")`**；`android.content.Context` 又是抽象类、`getResources()`
 * 在其子类中通常 final，Proxy/匿名子类都造不出来。因此这里复用本轮 `R31Fix204Test` 已证明可行的
 * 夹具：把**工作区原样的 ConvertUtils.java** 连同本地 Android 桩一起用 `javax.tools.JavaCompiler`
 * 编到临时目录，再用父加载器为 `null` 的 `URLClassLoader` 加载。
 *
 * 这样断言的是**生产源码本身返回的整数**（改生产代码这些用例会真的变色），而不是测试里抄的一份公式。
 * android.jar 的位置由 harness 通过 `-Ddsh.android.jar` 传入（`run-tests.sh` 已经这么做了），
 * 只用于解析 `ConvertUtils.java` 里那几个与 dp/px 无关的类型（Bitmap/Base64/TypedValue）。
 */
class R31Fix215Test {

    // ============================================================ F2 / F3 helpers

    /** Event with a settable result, mirroring a real `Event` subclass. */
    private class TestEvent(source: Any) : Event(source) {
        override fun hasResult(): Boolean = true
    }

    /** Counts deliveries. One instance per handler so "which one ran" is observable. */
    private class Counter : java.util.function.Consumer<TestEvent> {
        val calls = AtomicInteger(0)
        override fun accept(t: TestEvent) {
            calls.incrementAndGet()
        }
    }

    /** Records that it really was invoked, then throws. */
    private class ThrowingConsumer(private val thrown: BooleanArray) :
        java.util.function.Consumer<TestEvent> {
        override fun accept(t: TestEvent) {
            thrown[0] = true
            throw IllegalStateException("handler blew up on purpose")
        }
    }

    private fun manager() = EventManager<TestEvent>()

    // ============================================================ F2

    /**
     * Core defect: `registerWeak` wraps in a fresh `WeakListener` every call, and the pre-fix
     * `WeakListener` had no `equals`/`hashCode` — so `contains` was always false and the same
     * consumer got installed twice, delivering the event twice.
     */
    @Test
    fun f2RegisterWeakTwiceDeliversOnce() {
        val m = manager()
        val c = Counter()
        m.registerWeak(c)
        m.registerWeak(c)          // second registration of the SAME consumer
        m.fireEvent(TestEvent(this))
        Assert.assertEquals(
            "registerWeak(same consumer) twice must deliver exactly once; " +
                "pre-fix WeakListener had no equals/hashCode so both wrappers stayed in the set",
            1, c.calls.get()
        )
    }

    /**
     * The dispatch notes `registerWeak` **returns the original consumer**, inviting callers to
     * treat it as an idempotent handle. Verify that reading: re-registering the returned handle
     * must not double-deliver.
     */
    @Test
    fun f2RegisterWeakReturnedHandleIsIdempotent() {
        val m = manager()
        val c = Counter()
        val handle = m.registerWeak(c)
        Assert.assertSame("registerWeak must return the original consumer", c, handle)
        m.registerWeak(handle)     // handle used as an idempotent re-registration
        m.fireEvent(TestEvent(this))
        Assert.assertEquals("returned handle must be idempotent", 1, c.calls.get())
    }

    /** `register(Runnable)` used to create a fresh lambda each call — same defect, same fix. */
    @Test
    fun f2RegisterRunnableTwiceDeliversOnce() {
        val m = manager()
        val calls = AtomicInteger(0)
        val r = Runnable { calls.incrementAndGet() }
        m.register(r)
        m.register(r)
        m.fireEvent(TestEvent(this))
        Assert.assertEquals(
            "register(same Runnable) twice must run once; pre-fix each call made a new lambda",
            1, calls.get()
        )
    }

    /**
     * Over-merge guard. If the fix collapsed *different* consumers into one (constant hashCode +
     * permissive equals), this goes to 1 and the fix is broken in the opposite direction.
     */
    @Test
    fun f2DistinctConsumersBothDelivered() {
        val m = manager()
        val a = Counter()
        val b = Counter()
        m.registerWeak(a)
        m.registerWeak(b)
        m.fireEvent(TestEvent(this))
        Assert.assertEquals("distinct consumer A must be delivered", 1, a.calls.get())
        Assert.assertEquals("distinct consumer B must be delivered", 1, b.calls.get())
    }

    /**
     * Over-merge guard, cross-priority. Priority is a separate bucket in the multimap, so the same
     * consumer registered at two priorities legitimately gets two deliveries. A fix that deduped
     * globally (ignoring priority) drops one.
     */
    @Test
    fun f2SameConsumerDifferentPrioritiesBothDelivered() {
        val m = manager()
        val c = Counter()
        m.registerWeak(c, EventPriority.HIGHEST)
        m.registerWeak(c, EventPriority.LOWEST)
        m.fireEvent(TestEvent(this))
        Assert.assertEquals(
            "same consumer at two DIFFERENT priorities must be delivered twice (buckets are per priority)",
            2, c.calls.get()
        )
    }

    /**
     * Mixed registration paths must not be conflated: a weak registration and a plain one of the
     * same consumer are two distinct subscriptions (the plain one is a strong reference). Both
     * must fire. This pins the equality domain — `WeakListener` must not equal a bare consumer.
     */
    @Test
    fun f2WeakAndStrongRegistrationOfSameConsumerBothFire() {
        val m = manager()
        val c = Counter()
        m.registerWeak(c)
        m.register(c)
        m.fireEvent(TestEvent(this))
        Assert.assertEquals(
            "weak + strong registration of the same consumer are two subscriptions; both must fire",
            2, c.calls.get()
        )
    }

    /**
     * `unregister` after the equals/hashCode change — **strong** registration path.
     *
     * Why not the weak path: `unregister(consumer)` goes through `SimpleMultimap.removeValue`,
     * which calls `set.remove(consumer)`. For a weak registration the set holds a `WeakListener`,
     * so the probe is `weakListener.equals(consumer)` — and a wrapper can never equal a bare
     * consumer. That is a **pre-existing, separate defect** (verified: this exact assertion is red
     * on both the pre-fix and post-fix tree, i.e. not a regression introduced here), and it has no
     * in-tree callers — `unregister` is only invoked from `WeakListener.accept` for GC cleanup.
     * It is out of scope for F2/F3/F5 and is logged as a residual in the report instead of being
     * silently "fixed" by widening `WeakListener.equals` to accept bare consumers (which would
     * make two different wrappers of different consumers compare equal in pathological cases).
     */
    @Test
    fun f2UnregisterStillRemovesStrongConsumer() {
        val m = manager()
        val c = Counter()
        val other = Counter()
        m.register(c)
        m.registerWeak(other)
        m.unregister(c)
        m.fireEvent(TestEvent(this))
        Assert.assertEquals("unregister(strong) must stop that subscription", 0, c.calls.get())
        Assert.assertEquals("unregister must not touch other subscribers", 1, other.calls.get())
    }

    // ============================================================ F3

    /**
     * A throwing HIGHEST handler must not stop lower priorities. Pre-fix the exception left
     * `fireEvent` immediately and NORMAL/LOW never ran.
     */
    @Test
    fun f3ThrowingHighestDoesNotBlockLower() {
        val m = manager()
        val thrown = BooleanArray(1)
        val normal = Counter()
        val low = Counter()
        m.register(ThrowingConsumer(thrown), EventPriority.HIGHEST)
        m.registerWeak(normal, EventPriority.NORMAL)
        m.registerWeak(low, EventPriority.LOW)

        try {
            m.fireEvent(TestEvent(this))
        } catch (ignored: IllegalStateException) {
            // Chosen semantics is isolate-and-log; if a future change rethrows, delivery still holds.
        }

        Assert.assertTrue(
            "the throwing handler must actually have thrown (else this is a false green)", thrown[0]
        )
        Assert.assertEquals("NORMAL handler must run after a HIGHEST handler throws", 1, normal.calls.get())
        Assert.assertEquals("LOW handler must run after a HIGHEST handler throws", 1, low.calls.get())
    }

    /**
     * Peer isolation *within* one priority: the first handler throws, the second must still run.
     * A naive "wrap the whole priority loop in try/catch" fix would still miss this one.
     */
    @Test
    fun f3ThrowingSiblingDoesNotBlockPeer() {
        val m = manager()
        val thrown = BooleanArray(1)
        val peer = Counter()
        m.register(ThrowingConsumer(thrown), EventPriority.NORMAL)
        m.registerWeak(peer, EventPriority.NORMAL)

        try {
            m.fireEvent(TestEvent(this))
        } catch (ignored: IllegalStateException) {
        }

        Assert.assertTrue("the throwing handler must actually have thrown", thrown[0])
        Assert.assertEquals("same-priority peer must run after its sibling throws", 1, peer.calls.get())
    }

    /**
     * Return-value contract: `fireEvent` must still hand back the result a handler set, even when
     * another handler threw. Pre-fix the throw bypassed the `return` entirely.
     */
    @Test
    fun f3ResultStillReturnedWhenHandlerThrows() {
        val m = manager()
        val thrown = BooleanArray(1)
        m.register(ThrowingConsumer(thrown), EventPriority.HIGHEST)
        m.register({ e: TestEvent -> e.setResult(Event.Result.DENY) }, EventPriority.LOWEST)

        val result = m.fireEvent(TestEvent(this))

        Assert.assertTrue("the throwing handler must actually have thrown", thrown[0])
        Assert.assertEquals("result set by a later handler must still be returned", Event.Result.DENY, result)
    }

    /**
     * Chosen semantics is (b) isolate + log. Verify the log actually receives the failure:
     * isolation with no record would be "silently swallowing", a defect of its own.
     */
    @Test
    fun f3HandlerFailureIsLoggedNotSwallowed() {
        val m = manager()
        val thrown = BooleanArray(1)
        m.register(ThrowingConsumer(thrown), EventPriority.NORMAL)

        val seen = ArrayList<LogRecord>()
        val capture = object : Handler() {
            override fun publish(record: LogRecord) { seen.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logging.LOG
        logger.addHandler(capture)
        try {
            m.fireEvent(TestEvent(this))
        } catch (ignored: IllegalStateException) {
        } finally {
            logger.removeHandler(capture)
        }

        Assert.assertTrue("the throwing handler must actually have thrown", thrown[0])
        val severe = seen.filter { it.level == Level.SEVERE }
        Assert.assertTrue(
            "handler failure must be recorded at SEVERE (otherwise it is silently swallowed); " +
                "saw=${seen.map { it.level }}",
            severe.isNotEmpty()
        )
        Assert.assertTrue(
            "the SEVERE record must carry the original exception (thrown or parameter)",
            severe.any {
                it.thrown != null || it.parameters?.any { p -> p is IllegalStateException } == true
            }
        )
    }

    /**
     * Regression guard in the other direction: the ordinary path is untouched — every handler runs
     * exactly once, HIGHEST before NORMAL, and the result is returned.
     */
    @Test
    fun f3NormalPathUnchanged() {
        val m = manager()
        val order = ArrayList<String>()
        m.register(object : java.util.function.Consumer<TestEvent> {
            override fun accept(t: TestEvent) { order.add("H") }
        }, EventPriority.HIGHEST)
        m.register(object : java.util.function.Consumer<TestEvent> {
            override fun accept(t: TestEvent) { order.add("N") }
        }, EventPriority.NORMAL)
        val result = m.fireEvent(TestEvent(this))
        Assert.assertEquals("all handlers run once, HIGHEST before NORMAL", listOf("H", "N"), order)
        Assert.assertEquals("no handler set a result -> DEFAULT", Event.Result.DEFAULT, result)
    }

    /**
     * `Error` must NOT be swallowed: it signals the process should stop (OOM, StackOverflow,
     * AssertionError). This pins the boundary of the "isolate Exception only" semantics.
     */
    @Test
    fun f3ErrorStillPropagates() {
        val m = manager()
        m.register(object : java.util.function.Consumer<TestEvent> {
            override fun accept(t: TestEvent) {
                throw AssertionError("boom as an Error")
            }
        }, EventPriority.NORMAL)
        var propagated = false
        try {
            m.fireEvent(TestEvent(this))
        } catch (e: AssertionError) {
            propagated = true
        }
        Assert.assertTrue("Error must propagate out of fireEvent, not be isolated", propagated)
    }

    // ============================================================ F5 fixture

    private class ConvertFixture(
        val dir: File,
        val loader: URLClassLoader,
        val convertClass: Class<*>,
        val contextClass: Class<*>
    ) : AutoCloseable {
        fun dip2px(density: Float, dp: Float): Int =
            convertClass.getMethod("dip2px", contextClass, Float::class.javaPrimitiveType)
                .invoke(null, newContext(density), dp) as Int

        fun px2dip(density: Float, px: Float): Int =
            convertClass.getMethod("px2dip", contextClass, Float::class.javaPrimitiveType)
                .invoke(null, newContext(density), px) as Int

        private fun newContext(density: Float): Any {
            val ctx = contextClass.getDeclaredConstructor().newInstance()
            contextClass.getMethod("__setDensity", Float::class.javaPrimitiveType).invoke(ctx, density)
            return ctx
        }

        /**
         * [r31-vi-16 同型] 先关 loader 再删目录。
         *
         * `URLClassLoader` 会把 `out/` 与 `android.jar` 的句柄拿到 `close()` 为止；Windows 上
         * 未关闭的句柄让 `deleteRecursively()` **静默失败**（Kotlin 该 API 返回 Boolean、不抛异常），
         * 于是每跑一条 F5 用例就永久残留一个约 1MB 的 `r31-215-convert*` 目录。
         * 实测：只跑 `f5NegativeRoundsSymmetric` 一条，跑完 `/tmp` 就多一个目录。
         */
        override fun close() {
            try {
                loader.close()
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    private fun newConvertFixture(): ConvertFixture {
        val dir = java.nio.file.Files.createTempDirectory("r31-215-convert").toFile()
        val src = File(dir, "src")
        val out = File(dir, "out")
        out.mkdirs()

        val prod = resolveUnder("FCL/src/main/java/com/tungsten/fcllibrary/util/ConvertUtils.java")
        Assert.assertNotNull("找不到 ConvertUtils.java —— F5 夹具必须读工作区原样源码", prod)
        val prodCopy = File(src, "ConvertUtils.java")
        writeSource(prodCopy, prod!!.readText())

        writeSource(File(src, "android/content/Context.java"), STUB_CONTEXT)
        writeSource(File(src, "android/content/res/Resources.java"), STUB_RESOURCES)
        writeSource(File(src, "android/util/DisplayMetrics.java"), STUB_DISPLAY_METRICS)

        val androidJar = androidJar()
        Assert.assertNotNull(
            "找不到 android.jar —— harness 应通过 -Ddsh.android.jar 传入（run-tests.sh 已设置）" +
                "；缺失会导致 ConvertUtils.java 里 Bitmap/Base64/TypedValue 的引用无法解析",
            androidJar
        )
        val jar = androidJar!!

        val compiler = systemJavaCompiler()
        Assert.assertNotNull("本测试需要 JDK（javax.tools.JavaCompiler）", compiler)
        val files = listOf(
            prodCopy,
            File(src, "android/content/Context.java"),
            File(src, "android/content/res/Resources.java"),
            File(src, "android/util/DisplayMetrics.java")
        )
        val compiled = compileWithReflection(
            compiler!!, files,
            listOf("-d", out.absolutePath, "-encoding", "UTF-8", "-nowarn", "-cp", jar.absolutePath)
        )
        Assert.assertTrue(
            "ConvertUtils 夹具编译失败（被测生产源码一个字节都没改）：" + compiled.diagnostics,
            compiled.ok
        )
        // 桩目录必须排在 android.jar 前面：android.jar 里的方法体全是 throw new RuntimeException("Stub!")。
        val loader = URLClassLoader(arrayOf(out.toURI().toURL(), jar.toURI().toURL()), null)
        val convertClass = Class.forName("com.tungsten.fcllibrary.util.ConvertUtils", true, loader)
        val contextClass = Class.forName("android.content.Context", true, loader)
        return ConvertFixture(dir, loader, convertClass, contextClass)
    }

    private fun androidJar(): File? {
        System.getProperty("dsh.android.jar")?.let { p ->
            File(p).takeIf { it.isFile }?.let { return it }
        }
        val sdk = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
        if (sdk != null) {
            File(sdk, "platforms/android-35/android.jar").takeIf { it.isFile }?.let { return it }
            val platforms = File(sdk, "platforms")
            platforms.listFiles()?.sortedByDescending { it.name }?.forEach { dir ->
                File(dir, "android.jar").takeIf { it.isFile }?.let { return it }
            }
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
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    // ============================================================ javax.tools 反射外壳

    /**
     * 编译结果：成功标志 + 诊断文本（格式与改造前逐字一致）。
     */
    private class CompileResult(val ok: Boolean, val diagnostics: String)

    /**
     * [r31-vi-15] 取 `ToolProvider.getSystemJavaCompiler()`，但**不写静态 import**。
     *
     * 本模块是 Android 模块，`compileDebugUnitTestKotlin` 的 classpath 上 `android.jar` 排在
     * `java.base` 之前，而 `android.jar` 里 `javax/tools` 的类计数为 0 —— 静态 import 在**编译期**
     * Unresolved，运行时（Gradle 测试 JVM 是完整 JDK 17）却可用。这会让整个模块 644 条单测
     * 一条都编译不过（fix-209 / fix-215 各贡献 16 条错误）。
     *
     * 所以这里只用 `Class.forName` 的**字符串**引用，编译期不需要这些类型存在。
     * 找不到 `ToolProvider`（纯 JRE）时返回 null，由调用方的 `assertNotNull` 明确失败 ——
     * 与改造前 `getSystemJavaCompiler()` 返回 null 的行为一致，不静默跳过。
     */
    private fun systemJavaCompiler(): Any? = try {
        Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
    } catch (e: ClassNotFoundException) {
        null
    }

    /**
     * 反射跑完 javac 完整链路：
     * `getStandardFileManager` → `getJavaFileObjectsFromFiles` → 6 参 `getTask` → `call()`。
     *
     * 三个坑（都踩过）：
     * 1. **走接口类取方法**（`javax.tools.JavaCompiler` / `StandardJavaFileManager`），不用实现类
     *    `com.sun.tools.javac.api.JavacTool` —— 后者在部分 JDK 上不可见。
     * 2. `getTask` 只用 **6 参重载**；带 `JavaFileManager$Location` 的 7 参重载是 javac 内部 API。
     * 3. `JavaFileManager` 必须显式 `close()`（`manager.use { }` 的等价语义）。
     */
    private fun compileWithReflection(compiler: Any, files: List<File>, options: List<String>): CompileResult {
        val compilerIface = Class.forName("javax.tools.JavaCompiler")
        val listenerIface = Class.forName("javax.tools.DiagnosticListener")
        val fileManagerIface = Class.forName("javax.tools.JavaFileManager")
        val standardFileManagerIface = Class.forName("javax.tools.StandardJavaFileManager")
        val taskIface = Class.forName("javax.tools.JavaCompiler\$CompilationTask")
        val diagnosticCollectorClass = Class.forName("javax.tools.DiagnosticCollector")

        val diagnostics = diagnosticCollectorClass.getDeclaredConstructor().newInstance()
        val fileManager = compilerIface.getMethod(
            "getStandardFileManager",
            listenerIface,
            java.util.Locale::class.java,
            java.nio.charset.Charset::class.java
        ).invoke(compiler, diagnostics, null, Charsets.UTF_8)
        try {
            val fileObjects = standardFileManagerIface
                .getMethod("getJavaFileObjectsFromFiles", Iterable::class.java)
                .invoke(fileManager, files)
            val task = compilerIface.getMethod(
                "getTask",
                java.io.Writer::class.java,
                fileManagerIface,
                listenerIface,
                Iterable::class.java,
                Iterable::class.java,
                Iterable::class.java
            ).invoke(compiler, null, fileManager, diagnostics, options, null, fileObjects)
            val ok = taskIface.getMethod("call").invoke(task) as Boolean
            return CompileResult(
                ok,
                (diagnosticCollectorClass.getMethod("getDiagnostics").invoke(diagnostics) as List<*>)
                    .joinToString("; ") { it.toString() }
            )
        } finally {
            try {
                fileManagerIface.getMethod("close").invoke(fileManager)
            } catch (e: Throwable) {
                System.err.println("R31Fix215Test: StandardJavaFileManager.close() 失败：$e")
            }
        }
    }

    /** Same lookup as the other round31 tests: walk up from cwd, tolerating three layouts. */
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

    // ============================================================ F5

    /**
     * 审计原文的算例，在生产源码上重跑。
     * `dip2px(2.0, -3)` 改前 -5（期望 -6）；`px2dip(2.0, -3)` 改前 -1（期望 -2）。
     */
    @Test
    fun f5NegativeRoundsSymmetric() {
        newConvertFixture().use { f ->
            Assert.assertEquals("dip2px(2.0, -3)", -6, f.dip2px(2.0f, -3f))
            Assert.assertEquals("px2dip(2.0, -3)", -2, f.px2dip(2.0f, -3f))
            Assert.assertEquals("dip2px(3.0, -3)", -9, f.dip2px(3.0f, -3f))
            Assert.assertEquals("dip2px(3.0, -1)", -3, f.dip2px(3.0f, -1f))
            Assert.assertEquals("dip2px(0.75, -3)", -2, f.dip2px(0.75f, -3f))
            Assert.assertEquals("px2dip(0.75, -3)", -4, f.px2dip(0.75f, -3f))
            Assert.assertEquals("px2dip(0.75, -2)", -3, f.px2dip(0.75f, -2f))
            // |-1|/3 = 0.333 -> 0，不得偏成 -1
            Assert.assertEquals("px2dip(3.0, -1)", 0, f.px2dip(3.0f, -1f))
            // 正数域照旧
            Assert.assertEquals("dip2px(2.0, 3)", 6, f.dip2px(2.0f, 3f))
        }
    }

    /**
     * 全 density × 大量负实参扫描：结果必须等于 `-round(|x| * d)`（绝对值四舍五入、符号保留）。
     * 任何不对称实现（含改前的原实现）都会在这里掉。
     */
    @Test
    fun f5NegativeMatchesAbsRoundingAcrossAllDensities() {
        newConvertFixture().use { f ->
            val densities = floatArrayOf(0.75f, 1.0f, 1.5f, 2.0f, 2.625f, 2.75f, 3.0f, 3.5f, 4.0f)
            var checked = 0
            for (density in densities) {
                for (i in 1..200) {
                    val dp = -(i * 0.37f)
                    val expected = -Math.round(-dp * density)
                    Assert.assertEquals("dip2px($density, $dp)", expected.toInt(), f.dip2px(density, dp))
                    checked++

                    val px = -(i * 2.13f)
                    val expectedPx = -Math.round(-px / density)
                    Assert.assertEquals("px2dip($density, $px)", expectedPx.toInt(), f.px2dip(density, px))
                    checked++
                }
            }
            Assert.assertTrue("扫描必须真的跑过（否则是空转假绿）", checked > 3000)
        }
    }

    /**
     * 过度修复守卫：正数域必须**逐位不变**。
     *
     * 旧实现 `(int)(v + 0.5f)` 在 v >= 0 时就是 `floor(v + 0.5)`。任何正确的修法都必须与之
     * 逐位一致。这里覆盖「全部生产调用点用到的 dp 值」（grep 所得，见报告 §2）与一段密集扫描。
     */
    @Test
    fun f5PositiveDomainUnchanged() {
        newConvertFixture().use { f ->
            val densities = floatArrayOf(0.75f, 1.0f, 1.5f, 2.0f, 2.625f, 2.75f, 3.0f, 3.5f, 4.0f)
            // 生产调用点用到的 dp 值（grep -rn "dip2px" FCL/src 去重所得）
            val productionDps = floatArrayOf(
                1f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f, 10f, 12f, 13f, 14f, 16f, 20f, 30f, 33f,
                40f, 48f, 200f, 320f, 400f, 560f
            )
            var checked = 0
            for (density in densities) {
                for (dp in productionDps) {
                    val legacy = (dp * density + 0.5f).toInt()   // 改前公式，逐字
                    Assert.assertEquals(
                        "dip2px($density, $dp) 正数域必须与改前逐位相同", legacy, f.dip2px(density, dp)
                    )
                    checked++
                }
            }
            for (density in densities) {
                for (i in 0..2000) {
                    val dp = i * 0.01f
                    val legacy = (dp * density + 0.5f).toInt()
                    Assert.assertEquals("dense dip2px($density, $dp)", legacy, f.dip2px(density, dp))
                    checked++
                }
            }
            // px2dip 同理（生产侧无 px2dip 调用点，故只做密集扫描）
            for (density in densities) {
                for (i in 0..2000) {
                    val px = i * 0.5f
                    val legacy = (px / density + 0.5f).toInt()
                    Assert.assertEquals("dense px2dip($density, $px)", legacy, f.px2dip(density, px))
                    checked++
                }
            }
            Assert.assertTrue("正数扫描必须真的跑过", checked > 10000)
        }
    }

    private companion object {
        private const val STUB_CONTEXT =
            "package android.content; " +
                "public class Context { " +
                "  private android.content.res.Resources res = new android.content.res.Resources(); " +
                "  public Context() {} " +
                "  public android.content.res.Resources getResources() { return res; } " +
                "  public void __setDensity(float d) { res.metrics.density = d; } " +
                "}"

        private const val STUB_RESOURCES =
            "package android.content.res; " +
                "public class Resources { " +
                "  public final android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); " +
                "  public Resources() {} " +
                "  public android.util.DisplayMetrics getDisplayMetrics() { return metrics; } " +
                "}"

        private const val STUB_DISPLAY_METRICS =
            "package android.util; " +
                "public class DisplayMetrics { " +
                "  public float density = 1.0f; " +
                "  public DisplayMetrics() {} " +
                "}"
    }
}
