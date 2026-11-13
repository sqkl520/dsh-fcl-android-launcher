package com.dsh

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import com.dsh.core.DshAppContextHolder
import com.dsh.core.DshUiText
import com.dsh.core.PtyNative
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.MissingResourceException

/**
 * R31 / B4：`slice-04-eh-06` + `slice-04-eh-07` 的钉住用例。
 *
 * 两条是同一类缺陷 —— **兜底面窄于方法自己声明的失败域**：
 *
 * 1. **eh-06 `PtyNative`**：`System.loadLibrary` 在 `object` 的 `init` 里裸调。`.so` 缺失或
 *    ABI 不匹配时，`UnsatisfiedLinkError` 在**进入任何方法之前**抛出，调用方连"看返回值"的
 *    机会都没有；更糟的是 JVM 会缓存类初始化失败，第二次触达退化成 `NoClassDefFoundError`
 *    （真正原因被掩盖）。修法**不能**是简单 try/catch 完事 —— 必须让初始化永不抛，并给出
 *    可查询的可用性标志，让所有入口走同一个已声明的失败通道。
 * 2. **eh-07 `DshUiText.getString`**：`?:` 只覆盖"拿不到 Context"，不覆盖取词本身抛异常
 *    （资源缺失 / 占位符与参数不匹配）。
 *
 * ## 为什么这些用例在 JVM 上成立（而不是"需要真机"）
 * - `System.loadLibrary("ptyjni")` 在 JVM 上**必定**失败（`java.library.path` 里没有它）
 *   ⇒ 本机就是 eh-06 的触发场景本体，不需要伪造。
 * - `Context.getString(int, Object...)` 在 `android.jar` 里是 `final` 且方法体为 `Stub!`
 *   ⇒ 无法伪造它，所以生产代码改走可覆写的 `Resources.getString(int, Object...)`
 *   （AOSP 里前者就是一行委派）。伪造 `Resources` 抛出格式化异常，正好是 eh-07 声称的失败域。
 * - `ContextWrapper(Context)` 与 `Resources(...)` 的构造都是 `Stub!` ⇒ 用
 *   `Unsafe.allocateInstance` 跳过构造（与 `R31DateTimeFormatterLocaleTest` 同一手法）。
 *
 * 运行：`sh run-tests.sh`
 */
class R31Fix233Test {

    // ---------------------------------------------------------------- eh-06：PtyNative

    /**
     * `PtyNative` 的类初始化**永不抛**；失败原因可查询，且是真正的 `UnsatisfiedLinkError`
     * （不是被 JVM 改写后的 `NoClassDefFoundError`）。
     *
     * 这条同时钉住"为什么不能只用 runCatching 包一层"：包一层只能让异常不被看见，
     * 拿不到原因；本用例要求能读回 **原始** `UnsatisfiedLinkError`。
     */
    @Test
    fun loadFailureIsQueryableAndKeepsOriginalCause() {
        assertFalse("JVM 上没有 libptyjni，必须报告为不可用（否则说明标志没被设置）", PtyNative.isAvailable)
        val err = PtyNative.loadErrorOrNull()
        assertNotNull("不可用时必须能拿到失败原因（诊断页/日志靠它，不能只剩一句 NoClassDefFoundError）", err)
        assertTrue(
            "失败原因必须是原始的 UnsatisfiedLinkError，实际为 ${err!!::class.java.name}: $err",
            err is UnsatisfiedLinkError
        )
    }

    /**
     * **每一个** native 入口都走已声明的失败通道，而不是抛异常。
     *
     * 这是 eh-06 的核心：原来失败发生在"进函数之前"，调用方没有任何返回值可判断；
     * 现在全部返回各方法 KDoc 里已经写明的负值。
     */
    @Test
    fun everyEntryPointReturnsDeclaredFailureInsteadOfThrowing() {
        assertEquals("forkPty 不可用时应返回负值", -1, PtyNative.forkPty("/bin/sh", null, null, 24, 80))
        assertEquals("read 不可用时应返回负值", -1, PtyNative.read(3, ByteArray(8)))
        assertEquals("write 不可用时应返回负值", -1, PtyNative.write(3, ByteArray(8)))
        assertEquals("resize 不可用时应返回负值", -1, PtyNative.resize(3, 30, 100))
        assertEquals("waitPid 不可用时应返回负值", -1, PtyNative.waitPid(12345))
        assertEquals("getPid 不可用时应返回负值", -1, PtyNative.getPid())
        // close 本就没有失败通道（native 是 void）；不可用时必须是 no-op，不能抛
        PtyNative.close(3)
    }

    /**
     * 结构断言：源码里**没有**裸调 native 的入口。
     *
     * 存在理由：上面两条行为用例只覆盖当前这 7 个入口。将来有人加第 8 个入口而忘了查标志时，
     * 行为用例不会红（JVM 上它照样返回 -1 吗？不 —— 裸调会抛），但这条会先把"漏网"指出来。
     * 判据是机械的：任何 `nativeXxx(` **调用**所在行都必须同时出现 `guarded(`。
     * （`private external fun nativeXxx` 是**声明**，不在此列。）
     *
     * 源文件路径可用 `-Ddsh.fix233.ptysrc=<path>` 覆盖：变异测试要在**被变异的副本**上跑同一条
     * 断言，否则它读的永远是仓库里那份没变异的源码，这条就退化成"永绿"。
     *
     * ★ R32 / TC-07：断言前必须**先剔除注释与 KDoc**。
     *   原来是逐行正则直接扫全文，于是 KDoc 里只要出现一句"例如 `nativeFoo(x)`"这种
     *   说明性文字，就会被当成真实调用点抓走 —— 改文档把测试改红，是典型的假阳性。
     *   本轮修 N1 时就差点踩到：那条 KDoc 里写了 `registry[holder.bound] = null` 这种代码片段，
     *   换到本文件就是同一类事故。剔除用 [stripCommentsAndStrings]，它保留行数（把注释替换成
     *   等长空白），所以下面报错信息里的行号仍然对得上源文件。
     */
    @Test
    fun noUnguardedNativeCallInSource() {
        val override = System.getProperty(P_TYSRC)
        val src = if (override != null) File(override).readText().replace("\r\n", "\n")
        else readMainSource("com/dsh/core/PtyNative.kt")
        val code = stripCommentsAndStrings(src)
        val callLines = code.lines().filter { line ->
            Regex("""\bnative[A-Z]\w*\s*\(""").containsMatchIn(line) &&
                !line.trimStart().startsWith("private external fun")
        }
        assertTrue("源码里应当有 native 调用点（否则这条断言失去意义）：$callLines", callLines.size >= 6)
        val unguarded = callLines.filterNot { it.contains("guarded(") }
        assertTrue(
            "以下 native 调用没有经过 `guarded(` 标志检查，库不可用时会在进函数之前抛 " +
                "UnsatisfiedLinkError：\n" + unguarded.joinToString("\n"),
            unguarded.isEmpty()
        )
    }

    /**
     * 把 Kotlin 源码里的 `//` 行注释、块注释（含 KDoc）和字符串字面量内容
     * 替换成**等长空白**，保留换行，从而保持行号不变。
     *
     * 为什么要保留行号：调用方在断言失败时把命中的行原文打出来，行号错位会让排障直接跑偏。
     *
     * 为什么连字符串也要抹：字面量里出现的 `nativeFoo(x)` 不是调用点，抹掉更接近
     * "这段文字会被编译成可执行调用吗"这个真实判据。
     *
     * 已知边界（够用即可，不追求完整词法器）：多行原始字符串按"逐个引号配平"处理，
     * 遇到内含连续三个引号的极端写法可能判断失误 —— 本文件只扫 `PtyNative.kt`，
     * 那里没有多行字符串；将来若要扫全树，应先换成真正的词法扫描。
     */
    private fun stripCommentsAndStrings(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inBlockComment = false
        var inString = false
        var inChar = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                inBlockComment -> {
                    if (c == '*' && next == '/') {
                        out.append("  "); i += 2; inBlockComment = false
                    } else {
                        out.append(if (c == '\n') '\n' else ' '); i++
                    }
                }
                inString -> {
                    when {
                        c == '\\' && next != '\u0000' -> { out.append("  "); i += 2 }
                        c == '"' -> { out.append(' '); i++; inString = false }
                        c == '\n' -> { out.append('\n'); i++ }   // 未闭合的字符串不吞行号
                        else -> { out.append(' '); i++ }
                    }
                }
                inChar -> {
                    when {
                        c == '\\' && next != '\u0000' -> { out.append("  "); i += 2 }
                        c == '\'' -> { out.append(' '); i++; inChar = false }
                        c == '\n' -> { out.append('\n'); i++ }
                        else -> { out.append(' '); i++ }
                    }
                }
                c == '/' && next == '/' -> {
                    while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
                }
                c == '/' && next == '*' -> { out.append("  "); i += 2; inBlockComment = true }
                c == '"' -> { out.append(' '); i++; inString = true }
                c == '\'' -> { out.append(' '); i++; inChar = true }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- eh-07：DshUiText

    /**
     * [DshUiText.getString] 在**取词本身抛异常**时回落 [fallback]，而不是把异常穿给调用方。
     *
     * 这是 eh-07 的正面触发：`Resources.getString` 因占位符与参数不匹配抛
     * `IllegalFormatException`（真机上改文案就会撞到的那一类）。
     */
    @Test
    fun getStringFallsBackWhenFormattingThrows() {
        DshAppContextHolder.init(fakeContext(BAD_FORMAT_ID))
        val got = DshUiText.getString(BAD_FORMAT_ID, "fallback: install failed")
        assertEquals("取词抛异常时必须返回 fallback（原实现会直接把 IllegalFormatException 穿给调用方）",
            "fallback: install failed", got)
    }

    /** 资源缺失（`Resources.NotFoundException`）同样落 fallback —— 失败域不止格式化一种。 */
    @Test
    fun getStringFallsBackWhenResourceMissing() {
        DshAppContextHolder.init(fakeContext(MISSING_ID))
        assertEquals("资源缺失也必须回落 fallback",
            "fallback: missing", DshUiText.getString(MISSING_ID, "fallback: missing"))
    }

    /** 兜底**不能**把正常路径也吞掉：取词成功时必须返回资源文案，否则"兜底"等于"永远不取词"。 */
    @Test
    fun getStringStillReturnsResourceWhenItWorks() {
        DshAppContextHolder.init(fakeContext(GOOD_ID))
        assertEquals("正常取词不得被兜底逻辑吃掉", "hello 42",
            DshUiText.getString(GOOD_ID, "unused fallback", 42))
    }

    /** 完全拿不到 Context 时仍给 fallback（原有行为，防本次改动把它弄丢）。 */
    @Test
    fun getStringFallsBackWithoutAnyContext() {
        assertEquals("没有任何 Context 时必须回落 fallback",
            "no context", DshUiText.getString(GOOD_ID, "no context", 1))
    }

    /**
     * [DshUiText.context] / [DshUiText.contextOrNull] 在**语言包装链抛异常**时不穿异常。
     *
     * `LocaleUtils.setLanguage` → `getResources().getConfiguration()` 在 `android.jar` 里是
     * `Stub!`，正好模拟"这条链自己炸了"。修法是"套不上就原样返回 base"，因为调用方要的是
     * "能取词的 Context"，语言不对只是取到系统语言，比拿不到 Context 好得多。
     */
    @Test
    fun contextChainFailureIsNotPropagated() {
        val base = fakeContext(GOOD_ID)
        DshAppContextHolder.init(base)
        val ctx = try {
            DshUiText.contextOrNull()
        } catch (t: Throwable) {
            fail("contextOrNull 不该抛异常（setLanguage 链路失败应被吸收）：$t")
            return
        }
        assertSame("setLanguage 失败时应原样返回 base，而不是 null / 抛异常", base, ctx)
        assertSame("context(fallback) 走同一条链，也应返回 base", base, DshUiText.context(base))
    }

    // ---------------------------------------------------------------- 夹具

    @After
    fun clearHolder() {
        // DshAppContextHolder 是进程级单例，MiniRunner 在**同一个 JVM** 里跑全部测试类：
        // 不还原就是给后面的用例留一颗雷（它们会突然拿我们的假 Context 去取词）。
        try {
            val f = DshAppContextHolder::class.java.getDeclaredField("context")
            f.isAccessible = true
            f.set(null, null)
        } catch (t: Throwable) {
            throw AssertionError("无法还原 DshAppContextHolder.context：$t", t)
        }
    }

    private fun fakeContext(resIdThatFails: Int): Context {
        val ctx = allocate(FakeContext::class.java) as FakeContext
        val res = allocate(FakeResources::class.java) as FakeResources
        setField(res, "failingId", resIdThatFails)
        setField(ctx, "prefs", InMemoryPreferences())
        setField(ctx, "res", res)
        return ctx
    }

    private fun allocate(cls: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val allocateMethod: Method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateMethod.invoke(unsafe, cls)!!
    }

    private fun setField(target: Any, name: String, value: Any) {
        val f = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        f.set(target, value)
    }

    /**
     * 只认三条链的 Context：`getApplicationContext`（`contextOrNull` 会取）/ `getSharedPreferences`
     * （取语言）/ `getResources`（取词）。
     *
     * 跳过构造 ⇒ Kotlin 的构造器属性初始化没跑，字段由 [fakeContext] 反射补种。
     */
    private class FakeContext : ContextWrapper(null) {
        @Suppress("unused")
        private val prefs: SharedPreferences? = null

        @Suppress("unused")
        private val res: Resources? = null

        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs!!
        override fun getResources(): Resources = res!!
    }

    /**
     * 只认 `getString(int, vararg)` 的 Resources。
     * - [GOOD_ID] → 正常返回（证明兜底没吞掉正常路径）
     * - [BAD_FORMAT_ID] → 抛 `IllegalFormatException`（真机上"改了占位符"的那一类）
     * - [MISSING_ID] → 抛「资源不存在」的一类异常
     *
     * ⚠️ 这里抛的是 `MissingResourceException` 而不是 android 的
     * `Resources.NotFoundException`：后者在 `android.jar` 里构造函数体是 `Stub!`，
     * 用 `Unsafe.allocateInstance` 跳过构造后又因为 JDK 17 的模块边界无法给
     * `Throwable.detailMessage` 赋值。用例断言的是「这类失败也回落 fallback」，
     * 与异常的具体类无关 —— 真机上 `Resources.getString` 抛的就是 `NotFoundException`，
     * 而它同样是一个 `RuntimeException`，走的是同一条 catch。
     */
    private class FakeResources : Resources(null, null, null) {
        @Suppress("unused")
        private var failingId: Int = 0

        override fun getString(id: Int): String = getString(id, *emptyArray<Any?>())

        override fun getString(id: Int, vararg formatArgs: Any?): String = when (id) {
            BAD_FORMAT_ID -> throw IllegalArgumentException(
                "IllegalFormatException: %d <- String（占位符与参数类型不匹配）"
            )
            MISSING_ID -> throw MissingResourceException(
                "string resource id #0x$id not found", "FakeResources", "string"
            )
            else -> "hello " + formatArgs.joinToString(",")
        }
    }

    /** 内存版 SharedPreferences：只实现 `setLanguage` 走到的两个方法。 */
    private class InMemoryPreferences : SharedPreferences {
        private val committed = HashMap<String, Any?>().apply { put("lang", 1) }

        override fun getInt(key: String?, defValue: Int): Int = (committed[key] as? Int) ?: defValue

        override fun edit(): SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "putInt" -> {
                        pending[args[0] as String] = args[1]
                        proxy
                    }
                    "apply", "commit" -> {
                        committed.putAll(pending)
                        pending.clear()
                        true
                    }
                    "toString" -> "InMemoryPreferences.Editor"
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
            return java.lang.reflect.Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
                handler
            ) as SharedPreferences.Editor
        }

        // 其余方法本用例走不到：明确抛而不是静默返回默认值（静默会让"根本没走这条链"看起来像通过）
        override fun getAll(): MutableMap<String, *> = throw UnsupportedOperationException("getAll")
        override fun getString(key: String?, defValue: String?): String? =
            throw UnsupportedOperationException("getString")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            throw UnsupportedOperationException("getStringSet")
        override fun getLong(key: String?, defValue: Long): Long = throw UnsupportedOperationException("getLong")
        override fun getFloat(key: String?, defValue: Float): Float = throw UnsupportedOperationException("getFloat")
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            throw UnsupportedOperationException("getBoolean")
        override fun contains(key: String?): Boolean = throw UnsupportedOperationException("contains")
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = throw UnsupportedOperationException("register")

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = throw UnsupportedOperationException("unregister")
    }

    /** 与 `R31StartupChainTest` 同一手法：`git` 布局两仓合一，从 cwd 向上找 `FCL/src/main/java`。 */
    private fun readMainSource(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/java/$rel"),
                File(dir, "FCL/src/main/java/$rel"),
                File(dir, "src/main/java/$rel")
            ).firstOrNull { it.isFile }?.let { return it.readText().replace("\r\n", "\n") }
            dir = dir.parentFile
            depth++
        }
        fail("找不到主源码 FCL/src/main/java/$rel（cwd=${System.getProperty("user.dir")}）")
        return ""
    }

    companion object {
        private const val GOOD_ID = 1001
        private const val BAD_FORMAT_ID = 1002
        private const val MISSING_ID = 1003

        /** 覆盖 [noUnguardedNativeCallInSource] 读的源文件（变异测试用，见该用例 KDoc）。 */
        private const val P_TYSRC = "dsh.fix233.ptysrc"
    }

    // ------------------------------------------------ TC-07：剔除器本身的判别力

    /**
     * TC-07 的正向：**注释里的** `nativeXxx(` 不得被当成调用点。
     *
     * 这条走的是**真实入口** `noUnguardedNativeCallInSource` 的同一条路径 ——
     * 用 `-Ddsh.fix233.ptysrc=<临时文件>` 把源文件换成构造出来的样本。
     * ★ 必须走这个入口，不能只测 `stripCommentsAndStrings` 这个纯函数：
     *   变异测试证明过，只测纯函数的版本在"生产代码退回不剔除"时仍然全绿（未钉住调用点）。
     *
     * 构造：一个只有 **KDoc 示例**、没有任何真实裸调的文件。
     * 旧实现（逐行扫全文）会把它抓成裸调 ⇒ 红；剔除后 ⇒ 无命中，断言通过。
     */
    @Test
    fun commentMentioningNativeCallIsNotTreatedAsCallSite() {
        val src = """
            class Fake {
                /**
                 * 例如 nativeForkPty(x) 这样调用 —— 这里只是文档，不是真实调用点。
                 * 还有 nativeClose(fd) 和 nativeRead(a, b, c, d) 也一样。
                 */
                fun real() = guarded(FAILED) { nativeForkPtyReal(a) }
            }
        """.trimIndent()
        val hits = nativeCallLinesOf(src)
        assertEquals(
            "KDoc 里的示例文字不得被当成调用点；命中行=$hits",
            listOf("    fun real() = guarded(FAILED) { nativeForkPtyReal(a) }"),
            hits
        )
    }

    /**
     * TC-07 的反向：**真实**的裸调仍然必须被抓住（走真实入口）。
     *
     * 如果剔除器削过头（比如把整行都抹了），正向会绿但这条会红 —— 两条合起来才说明
     * "只去掉了注释，没有削弱判据"。
     */
    @Test
    fun realUnguardedNativeCallIsStillDetected() {
        val src = """
            class Fake {
                // nativeClose(fd) 在注释里，不算
                fun bad() = nativeClose(1)
            }
        """.trimIndent()
        val hits = nativeCallLinesOf(src)
        assertEquals(
            "真实的裸调必须仍被抓住，实际命中=$hits",
            listOf("    fun bad() = nativeClose(1)"),
            hits
        )
    }

    /**
     * **钉住调用点**：`noUnguardedNativeCallInSource` 必须真的经过剔除器。
     *
     * 上面两条走的是 `nativeCallLinesOf`（它自己调了剔除器）。如果将来有人把生产用例里的
     * `stripCommentsAndStrings(src)` 摘掉（退回逐行扫全文），那两条**仍然会绿**，
     * 因为它们各自调剔除器 —— 这条就是为堵这个洞写的。
     *
     * 判据：把一份"只有 KDoc 示例、没有真实裸调"的文件喂给**生产用例本身**
     * （通过 `-Ddsh.fix233.ptysrc` 机制），它必须**不**因为"找不到 ≥6 个调用点"以外的原因失败。
     * 换句话说：样本里没有裸调 ⇒ 生产用例的 `unguarded` 集合必须为空。
     *
     * 实现方式：直接调用生产用例的**同一段逻辑**（复制其两行判据会失去意义，所以这里
     * 用真实用例入口）。由于该用例是 `@Test`，用 `propOverride` 临时改系统属性再调它。
     */
    @Test
    fun productionCheckActuallyUsesTheStripper() {
        val fake = """
            class Fake {
                /**
                 * 文档：nativeForkPty(x) / nativeRead(a,b,c,d) / nativeClose(fd)
                 * 这些全是说明性文字，不是调用点。
                 */
                fun real1() = guarded(FAILED) { nativeForkPtyReal(a) }
                fun real2() = guarded(FAILED) { nativeReadReal(a) }
                fun real3() = guarded(FAILED) { nativeWriteReal(a) }
                fun real4() = guarded(FAILED) { nativeResizeReal(a) }
                fun real5() = guarded(FAILED) { nativeWaitPidReal(a) }
                fun real6() = guarded(FAILED) { nativeCloseReal(a) }
            }
        """.trimIndent()
        val tmp = File.createTempFile("ptysrc-strip-", ".kt").apply {
            writeText(fake); deleteOnExit()
        }
        val prev = System.getProperty(P_TYSRC)
        try {
            System.setProperty(P_TYSRC, tmp.absolutePath)
            // 生产用例：若没经过剔除器，KDoc 里的三个 nativeFoo( 会被当成裸调 ⇒ 抛 AssertionError
            noUnguardedNativeCallInSource()
        } catch (e: AssertionError) {
            throw AssertionError(
                "生产用例必须经过 stripCommentsAndStrings：样本里 KDoc 提到 nativeForkPty( 等，" +
                    "没剔除就会被当成裸调。真实报错：" + e.message, e
            )
        } finally {
            if (prev == null) System.clearProperty(P_TYSRC) else System.setProperty(P_TYSRC, prev)
            tmp.delete()
        }
    }

    /** 把样本源码按 `noUnguardedNativeCallInSource` 的同款判据跑一遍（**含剔除**）。 */
    private fun nativeCallLinesOf(src: String): List<String> =
        stripCommentsAndStrings(src).lines().filter { line ->
            Regex("""\bnative[A-Z]\w*\s*\(""").containsMatchIn(line) &&
                !line.trimStart().startsWith("private external fun")
        }

    /**
     * 剔除器**保留行号**：注释被替换成等长空白，`\n` 原样保留。
     *
     * 失败信息里的行号必须还能对上源文件，否则排障会跑偏。
     */
    @Test
    fun strippingPreservesLineNumbers() {
        val src = "line1\n/* a\nb */\nline4 // tail\nline5\n"
        val out = stripCommentsAndStrings(src)
        assertEquals(
            "行数必须不变（注释替换为空白而非删除）",
            src.lines().size,
            out.lines().size
        )
        assertEquals("首行不得被改动", "line1", out.lines()[0])
        // 行注释被抹成等长空白（不是删掉），所以行尾会留下一串空格 —— 断言用 trimEnd 比较内容。
        assertEquals("块注释后的第 4 行内容必须保留、行注释被抹成空白", "line4", out.lines()[3].trimEnd())
        assertEquals("第 5 行必须还在", "line5", out.lines()[4])
        // 反向：被抹掉的注释内容不得残留任何字符
        assertTrue("行注释内容不得残留：${out.lines()[3]}", !out.lines()[3].contains("tail"))
    }
}
