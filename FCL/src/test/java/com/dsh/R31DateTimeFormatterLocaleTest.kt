package com.dsh

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import com.tungsten.fcllibrary.util.LocaleUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * R31 / `slice-11-dc-f4-static-datetimeformatter-stale`：`formatDateTime` 的输出必须随语言切换更新。
 *
 * ## 钉的是什么（验收标准原文）
 * `getDateTimeFormatter(Context)` 的静态缓存原来**只判 `null`**，于是进程内切换语言后
 * （设置页 `changeLanguage` + `recreate()`，**不重启进程**）继续用切换前的 `world_time` 模式串。
 * 本文件钉的是：**同一 JVM 进程内**换语言后，`formatDateTime` 的输出必须等于「当前语言那份
 * `world_time` 在**当前语言的 locale** 下格式化」的结果，且与切换前的输出不同。
 *
 * ## 为什么断言"输出相等"而不是断言源码里有某个字段
 * 断言 `dateTimeFormatterKey != null`、或 `grep` 到 `locale` 出现在缓存条件里，都能被
 * 一个「写法正确、语义错误」的改动绕过（例如键里塞了 locale 却仍用 `ofPattern(String)` 解析）。
 * 这里的期望值由**仓库里真实的 `strings.xml`** + 文档化语义算出，比较的是**格式化后的字符串**：
 * 缓存键漏了 locale、漏了 pattern、或解析时没传 locale，三种错法都会让字符串不相等。
 *
 * ## 为什么用假 Context + 假 Resources（而不是 Robolectric / 真机）
 * 本项目单测 harness 是 `android.jar` 桩 + MiniRunner，没有 Robolectric。本方法只依赖
 * `getSharedPreferences`（取语言）与 `getResources().getIdentifier/getString`（取模式串），
 * 三条链都能在纯 JVM 里补齐 —— 于是用例打的是**真实生产字节码**，不是重写的替身逻辑。
 *
 * ★ 两个桩约束（实测，勿"优化"掉）：
 *  1. `ContextWrapper(Context)` 与 `Resources(...)` 的构造函数体都是 `throw new RuntimeException("Stub!")`，
 *     所以两者都用 `Unsafe.allocateInstance` 跳过构造（R26TerminalEventTest / R31TraditionalChineseIndexTest
 *     同款手法）。
 *  2. **不能覆写 `Context.getString(int)`** —— 它在 `android.jar` 里是 `final`，且方法体是 `Stub!`。
 *     生产代码因此走 `getResources().getString(resId)`（AOSP 里 `Context.getString(int)` 就是这一行委派）。
 *
 * ## 与 `R31TraditionalChineseIndexTest` 的分工
 * 那个文件钉 `slice-11-dc-f1`（繁中下标往返）。本文件**不碰** `migrateIndex` 的语义，
 * 只在语言下标已正确读回之后，钉"格式化器是否跟着语言走" —— 两个缺陷的判据互不覆盖。
 *
 * 跑法：`sh run-tests.sh`
 */
class R31DateTimeFormatterLocaleTest {

    // ------------------------------------------------------------------
    // 断言
    // ------------------------------------------------------------------

    /**
     * 缺陷本体：**同一进程内**英文 → 简体中文，第二次的格式化结果必须是中文模式串的形状。
     *
     * 修复前：第二次输出 == 第一次（静态缓存只判 null，永不重建），本用例红。
     * 修复后：第二次输出 == 中文期望值，且 != 第一次。
     */
    @Test
    fun formatUsesCurrentLanguagePatternAfterInProcessLanguageSwitch() {
        val instant = Instant.parse("2024-11-01T13:05:09Z")

        val english = format(instant, LocaleUtils.LANG_ENGLISH)
        assertEquals(
            "英文语境下 formatDateTime 必须等于「values/strings.xml 的 world_time @ en」的格式化结果",
            expected(EN_PATTERN, Locale.ENGLISH, instant),
            english
        )

        // 关键：**同一个进程**里换语言。静态缓存是类级字段，跨 Context 实例存活 —— 这正是缺陷现场。
        val simplified = format(instant, LocaleUtils.LANG_CHINESE_SIMPLIFIED)

        assertEquals(
            "切到简体中文后 formatDateTime 必须改用 values-zh 的 world_time（缓存未随语言失效就是这里红）",
            expected(ZH_PATTERN, Locale.SIMPLIFIED_CHINESE, instant),
            simplified
        )
        assertNotEquals(
            "中英文两种 world_time 的格式化结果必须不同，否则本用例区分不出缓存是否陈旧",
            english,
            simplified
        )
    }

    /**
     * 往返：英 → 中 → 英，第三次必须回到第一次的输出。
     *
     * 这条抓的是"只处理一次切换"的假修复（例如只在缓存里记了首次语言、或只在第一次比较时判断）：
     * 那种实现在第二次切换上仍然陈旧，本用例红。
     */
    @Test
    fun switchingBackAndForthRebuildsFormatterEachTime() {
        val instant = Instant.parse("2024-11-01T13:05:09Z")

        val firstEnglish = format(instant, LocaleUtils.LANG_ENGLISH)
        val chinese = format(instant, LocaleUtils.LANG_CHINESE_SIMPLIFIED)
        val backToEnglish = format(instant, LocaleUtils.LANG_ENGLISH)

        assertEquals("切回英文后必须回到英文的格式化结果", firstEnglish, backToEnglish)
        assertNotEquals("中文输出不能与英文相同（否则无法区分陈旧缓存）", firstEnglish, chinese)
    }

    /**
     * **模式串相同、locale 不同**时输出仍必须不同 —— 钉住"缓存键必须同时含 locale"。
     *
     * 现实依据：`values-zh-rTW/strings.xml` **没有** `world_time`（round18/P3 已登记），
     * 繁体中文因此回落到 `values/` 的英文模式串；但 `EEE`/`MMM` 这类**文本字段按 locale 输出**，
     * 所以 `zh-TW` 下是「週五, 11月 1, …」而英文下是「Fri, Nov 1, …」。
     *
     * 若缓存键只拿模式串（不拿 locale），或者 `ofPattern` 不显式传 locale，本用例红：
     * 两种语言会共用同一个 formatter，输出不会变。
     */
    @Test
    fun samePatternStringWithDifferentLocaleStillProducesDifferentOutput() {
        val instant = Instant.parse("2024-11-01T13:05:09Z")

        val english = format(instant, LocaleUtils.LANG_ENGLISH)
        val traditional = format(instant, LocaleUtils.LANG_CHINESE_TRADITIONAL)

        // 夹具自证：两种语言拿到的**模式串必须相同**（否则本用例退化成上一条）
        assertEquals(
            "夹具前提：繁中（rTW 无 world_time）与英文必须回落到同一个模式串",
            EN_PATTERN,
            patternFor(LocaleUtils.LANG_CHINESE_TRADITIONAL)
        )
        assertEquals(
            "同一模式串在 zh-TW locale 下的输出",
            expected(EN_PATTERN, Locale.TRADITIONAL_CHINESE, instant),
            traditional
        )
        assertNotEquals(
            "模式串相同但 locale 不同时，文本字段（EEE/MMM）必须按语言变化 —— 缓存键漏了 locale 就会相同",
            english,
            traditional
        )
    }

    /**
     * 缓存键必须**同时**含 pattern 与 locale —— 本用例专门钉 pattern 那一维。
     *
     * 场景：语言不变（都是英文），但资源里的 `world_time` 变了（同一语言的资源改版、
     * 或设备配置导致取到另一份资源）。此时 locale 相同而 pattern 不同，缓存必须重建。
     *
     * 为什么需要这条：前面几条用例里，pattern 与 locale **总是同时变化**（切语言时两者一起换），
     * 于是"键只含 locale、不含 pattern"的实现也能全绿 —— 那个变异体（M1）实测逃掉了。
     * 本用例把两个维度拆开，单独验证 pattern 这一维。
     */
    @Test
    fun cachedFormatterIsRebuiltWhenPatternChangesEvenIfLocaleIsTheSame() {
        val instant = Instant.parse("2024-11-01T13:05:09Z")

        // 第一次：英文 + 仓库里的英文 world_time
        val first = LocaleUtils.formatDateTime(
            newContextWithPattern(LocaleUtils.LANG_ENGLISH, EN_PATTERN), instant
        )
        assertEquals(expected(EN_PATTERN, Locale.ENGLISH, instant), first)

        // 第二次：**语言不变**，只把资源里的 world_time 换成另一种写法
        val swapped = "dd/MM/yyyy HH:mm:ss"
        val second = LocaleUtils.formatDateTime(
            newContextWithPattern(LocaleUtils.LANG_ENGLISH, swapped), instant
        )
        assertEquals(
            "locale 不变而 world_time 变了时，缓存必须重建（键里少了 pattern 就会返回旧格式）",
            expected(swapped, Locale.ENGLISH, instant),
            second
        )
        assertNotEquals("两种 pattern 的输出必须不同，否则本用例区分不出缓存是否重建", first, second)
    }

    /**
     * 回归守卫（`slice-11-dc-f1`，fix-205 的成果）：本次改动不得影响语言下标的读写。
     * 本波 `fix-205` 在 `migrateIndex` 里加的 `case LANG_CHINESE_TRADITIONAL: return LANG_CHINESE_TRADITIONAL;`
     * 是已验收通过的修复，任何对本文件的改动都必须保留它。这里用行为断言兜住：
     * 写 3 必须读回 3，并且 `getLocale(3)` 必须是繁体中文 locale。
     */
    @Test
    fun traditionalChineseIndexRoundTripStillWorks() {
        val ctx = newContext(LocaleUtils.LANG_ENGLISH)
        LocaleUtils.changeLanguage(ctx, LocaleUtils.LANG_CHINESE_TRADITIONAL)
        val readBack = LocaleUtils.getLanguage(ctx)
        assertEquals(
            "fix-205 的繁中下标修复必须保留：写 3 必须读回 3（修复前会塌回 0）",
            LocaleUtils.LANG_CHINESE_TRADITIONAL,
            readBack
        )
        assertEquals(Locale.TRADITIONAL_CHINESE, LocaleUtils.getLocale(readBack))
        // 旧下标迁移同样不得被碰坏
        LocaleUtils.changeLanguage(ctx, 8)
        assertEquals(LocaleUtils.LANG_CHINESE_TRADITIONAL, LocaleUtils.getLanguage(ctx))
        LocaleUtils.changeLanguage(ctx, 11)
        assertEquals(LocaleUtils.LANG_CHINESE_TRADITIONAL, LocaleUtils.getLanguage(ctx))
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 每个用例前后把 JVM 默认 locale 压成"第三个语言"，让"忘了显式传 locale"必然可观察。 */
    @Before
    fun pinJvmDefaultLocale() {
        savedDefault = Locale.getDefault()
        savedFormat = Locale.getDefault(Locale.Category.FORMAT)
        // fr 的 EEE/MMM 与 en / zh 都不同（ven. / nov.），三种目标语言下输出各不相同
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun restoreJvmDefaultLocale() {
        Locale.setDefault(savedFormat)
        Locale.setDefault(savedDefault)
    }

    private lateinit var savedDefault: Locale
    private lateinit var savedFormat: Locale

    /** 造一个当前语言为 [lang] 的进程内 Context（每次调用都是**新实例**，但共用类级缓存）。 */
    private fun newContext(lang: Int): Context = fakeContext(lang, patternFor(lang))

    /** 造一个「语言 = [lang]、但资源里的 `world_time` 是 [pattern]」的 Context（用于钉缓存键的 pattern 维度）。 */
    private fun newContextWithPattern(lang: Int, pattern: String): Context = fakeContext(lang, pattern)

    /** 用指定语言格式化 —— 每次走 `newContext` 模拟 `recreate()` 后的新 context。 */
    private fun format(instant: Instant, lang: Int): String =
        LocaleUtils.formatDateTime(newContext(lang), instant)

    /** 该语言对应的 `world_time` 原文（模拟 Resources 的回落：rTW 无 world_time ⇒ 英文串）。 */
    private fun patternFor(lang: Int): String = when (lang) {
        LocaleUtils.LANG_CHINESE_SIMPLIFIED -> ZH_PATTERN
        else -> EN_PATTERN
    }

    /** 期望值：**当前语言的**模式串在**当前语言的** locale 下格式化。 */
    private fun expected(pattern: String, locale: Locale, instant: Instant): String =
        DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(instant)

    private fun fakeContext(lang: Int, worldTime: String): Context {
        // 必须分配 **FakeContext**（不是 ContextWrapper）：覆写的方法要按虚分派生效，
        // 分配到父类会拿到一堆 `Stub!` 方法体。构造函数被跳过 ⇒ 两个字段要反射补种。
        val ctx = allocate(FakeContext::class.java) as Context
        val res = allocate(FakeResources::class.java)
        setField(res, "worldTime", worldTime)
        setField(ctx, "prefs", InMemoryPreferences(lang))
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
        // 字段声明在**私有的嵌套类**里，`getDeclaredField` 只看本类声明的字段，所以从
        // `target.javaClass` 直接取即可（FakeContext / FakeResources 就是最具体的类）。
        val f = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        f.set(target, value)
    }

    /**
     * 只认三条链的 Context：`getSharedPreferences`（取语言）/ `getResources` / `getPackageName`。
     *
     * 跳过构造 ⇒ Kotlin 的构造器属性初始化没跑，`prefs` / `res` 由 [fakeContext] 反射补种。
     */
    private class FakeContext : ContextWrapper(null) {
        @Suppress("unused")
        private val prefs: SharedPreferences? = null
        @Suppress("unused")
        private val res: Resources? = null

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs!!
        override fun getResources(): Resources = res!!
        override fun getPackageName(): String = "com.tungsten.fcl"
    }

    /**
     * 只认 `getIdentifier` / `getString(int)` 的 Resources。
     * `world_time` 固定返回 [WORLD_TIME_ID]；`getString(WORLD_TIME_ID)` 返回本 Fake 语言对应的模式串。
     *
     * ★ 同一手法：`Resources` 唯一的构造函数是 `(AssetManager, DisplayMetrics, Configuration)`，
     *   方法体为 `throw new RuntimeException("Stub!")` —— 用 `Unsafe.allocateInstance` 跳过它，
     *   因此 `worldTime` 字段（Kotlin 构造器属性）不会初始化，由 [fakeContext] 反射补种。
     */
    @Suppress("DEPRECATION")
    private class FakeResources : Resources(null, null, null) {
        @Suppress("unused")
        private val worldTime: String? = null

        override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int =
            if (name == "world_time" && defType == "string") WORLD_TIME_ID else 0

        override fun getString(id: Int): String {
            assertEquals("只应查询 world_time 的 id", WORLD_TIME_ID, id)
            return worldTime ?: throw AssertionError("FakeResources.worldTime 未补种")
        }
    }

    /**
     * 内存版 SharedPreferences：只实现 `getLanguage` / `changeLanguage` 走到的四个方法。
     * 写入推迟到 `apply()`/`commit()`，否则"忘了 apply"会假通过。
     */
    private class InMemoryPreferences(initialLang: Int) : SharedPreferences {
        private val committed = HashMap<String, Any?>().apply { put("lang", initialLang) }

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
                    else -> throw UnsupportedOperationException("Editor.${method.name} 未实现")
                }
            }
            @Suppress("UNCHECKED_CAST")
            return java.lang.reflect.Proxy.newProxyInstance(
                InMemoryPreferences::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
                handler
            ) as SharedPreferences.Editor
        }

        override fun getAll(): MutableMap<String, *> = HashMap(committed)
        override fun getString(key: String?, defValue: String?): String? =
            throw UnsupportedOperationException("getString 未实现")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            throw UnsupportedOperationException("getStringSet 未实现")
        override fun getLong(key: String?, defValue: Long): Long =
            throw UnsupportedOperationException("getLong 未实现")
        override fun getFloat(key: String?, defValue: Float): Float =
            throw UnsupportedOperationException("getFloat 未实现")
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            throw UnsupportedOperationException("getBoolean 未实现")
        override fun contains(key: String?): Boolean = committed.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = throw UnsupportedOperationException("registerOnSharedPreferenceChangeListener 未实现")
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = throw UnsupportedOperationException("unregisterOnSharedPreferenceChangeListener 未实现")
    }

    private companion object {
        const val WORLD_TIME_ID = 1001

        /** `world_time` 的匹配式。**必须声明在下面两个 val 之前**：companion 字段按源码顺序初始化。 */
        val WORLD_TIME_RE = Regex("""<string name="world_time">(.*?)</string>""")

        /**
         * `world_time` 的**真实值**，从仓库的 `strings.xml` 读出来（读不到就直接红，
         * 不用硬编码兜底 —— 硬编码会让"资源被改坏了"这件事在测试里消失）。
         */
        val EN_PATTERN: String = worldTimeFrom("values/strings.xml")
        val ZH_PATTERN: String = worldTimeFrom("values-zh/strings.xml")

        fun worldTimeFrom(rel: String): String {
            val file = resolveRes(rel)
                ?: throw AssertionError("找不到 $rel（请在仓库根或 FCL/ 目录下运行；用例不能靠硬编码兜底）")
            val m = WORLD_TIME_RE.find(file.readText())
                ?: throw AssertionError("$rel 里没有 world_time —— 缺陷的前提（按语言不同的模式串）消失了")
            return m.groupValues[1].replace("\\'", "'")
        }

        /** 从测试运行目录向上找 `[FCL/[FCL/]]src/main/res/<rel>`（与 DshResourceFormatTest 同款） */
        fun resolveRes(rel: String): File? {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            var depth = 0
            while (dir != null && depth < 8) {
                listOf(
                    File(dir, "FCL/FCL/src/main/res/$rel"),
                    File(dir, "FCL/src/main/res/$rel"),
                    File(dir, "src/main/res/$rel")
                ).firstOrNull { it.isFile }?.let { return it }
                dir = dir.parentFile
                depth++
            }
            return null
        }
    }
}
