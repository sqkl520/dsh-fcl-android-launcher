package com.dsh

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tungsten.fcllibrary.util.LocaleUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * R31 / `slice-11-dc-f1-traditional-chinese-index-collapse`：繁中下标往返一致性。
 *
 * ## 钉的是什么（验收标准原文）
 * `changeLanguage(ctx, LANG_CHINESE_TRADITIONAL)` 之后，`getLanguage(ctx)` 必须返回
 * `LANG_CHINESE_TRADITIONAL`（而不是 `LANG_SYSTEM`），且 `getLocale(getLanguage(ctx))`
 * 必须得到繁体中文 locale —— 即**写入值与读出值往返一致**。
 *
 * 修复前的实际行为：写入侧 `changeLanguage` 把原始 3 落盘（不做迁移），读取侧
 * `getLanguage` 把落盘值过 `migrateIndex`，而 `migrateIndex` 的 switch 没有 `case 3`，
 * 于是 3 落入 `default: return LANG_SYSTEM`(0) —— 用户选繁中，下次读出来变成"跟随系统"。
 * 这条用例在修复前**必红**，是本缺陷的定点复现。
 *
 * ## 为什么用假 Context（而不是真机/Robolectric）
 * 本项目的单测 harness 是"android.jar 桩 + MiniRunner"，项目也未引入 Robolectric。
 * `getLanguage` 只依赖 `context.getSharedPreferences("launcher", MODE_PRIVATE).getInt("lang", …)`
 * 这一条链，所以这里只补这条链：一个只认 `getSharedPreferences` 的 Context 子类 +
 * 一个内存版 SharedPreferences。`setLanguage`（会走 `getResources()` → `Stub!`）不在本文件测。
 *
 * ★ 为什么 `FakeContext` 要用 `Unsafe.allocateInstance` 分配：`android.jar` 里
 *   `ContextWrapper(Context)` 的构造函数体是 `throw new RuntimeException("Stub!")`（本机实测），
 *   正常 `new` 会直接抛。跳过构造函数分配后，我们覆写的 `getSharedPreferences` 仍然按虚分派生效
 *   （R26TerminalEventTest 用的是同一种手法，见那边的 §"为什么不能 ContextWrapper(null)"）。
 *   分配后 `prefs` 字段要**反射补种**：跳过构造 = Kotlin 的字段初始化器也没跑。
 *
 * ## 一个必须写明的历史碰撞（本用例故意不覆盖）
 * 旧 12 项列表（见 `v0.1.0` 的 `DshLauncherSettingsPage.pickLanguage`）里下标 **3 是俄语**，
 * 而新 4 项列表里 **3 是繁體中文**。同一个整数在两代语义下指向不同语言，无法只靠整数区分。
 * 本轮的取向是"让新语义自洽"（3 = 繁中，往返一致），代价是老版本里主动选过俄语的用户升级后
 * 会被读成繁中 —— 该取舍已登记在 `docs/reports/round31/round31-04-fix-205.md` 的残余风险中，
 * 因此这里**不断言**"3 → 跟随系统"。
 *
 * 跑法：`sh run-tests.sh`
 */
class R31TraditionalChineseIndexTest {

    // ------------------------------------------------------------------
    // 断言
    // ------------------------------------------------------------------

    /**
     * 验收标准本体：繁中写入后读回仍是繁中，且 `getLocale` 落到繁体中文 locale。
     * 这是**修复前必红**的那条（修复前实际返回 0）。
     */
    @Test
    fun traditionalChineseIndexSurvivesRoundTrip() {
        val ctx = freshContext()

        LocaleUtils.changeLanguage(ctx, LocaleUtils.LANG_CHINESE_TRADITIONAL)

        val readBack = LocaleUtils.getLanguage(ctx)
        assertEquals(
            "写入 LANG_CHINESE_TRADITIONAL 后 getLanguage 必须原样读回（修复前会塌回 LANG_SYSTEM）",
            LocaleUtils.LANG_CHINESE_TRADITIONAL,
            readBack
        )
        assertEquals(
            "getLocale(getLanguage(ctx)) 必须是繁体中文 locale",
            Locale.TRADITIONAL_CHINESE,
            LocaleUtils.getLocale(readBack)
        )
        // 资源后缀直取（values-<language>-r<REGION>）：繁中必须落到 zh-TW，不能只是"某个 zh"
        assertEquals("zh", LocaleUtils.getLocale(readBack).language)
        assertEquals("TW", LocaleUtils.getLocale(readBack).country)
    }

    /**
     * 幂等性：重复读取必须返回同一个值。
     *
     * `migrateIndex` 的 KDoc（`LocaleUtils.java:80`）声明"新下标传进来原样返回"，
     * 也就是 `migrateIndex(migrateIndex(x)) == migrateIndex(x)`。修复前 `migrateIndex(3) == 0`，
     * 一次就偏离了原值 —— 这条把"读两次"的稳定性和"读值与写值相同"分开钉住。
     */
    @Test
    fun readingTraditionalChineseTwiceIsStable() {
        val ctx = freshContext()
        LocaleUtils.changeLanguage(ctx, LocaleUtils.LANG_CHINESE_TRADITIONAL)

        val first = LocaleUtils.getLanguage(ctx)
        val second = LocaleUtils.getLanguage(ctx)
        assertEquals("同一份偏好连读两次必须一致", first, second)
        assertEquals("读值必须等于写值（migrateIndex 对该值幂等）", LocaleUtils.LANG_CHINESE_TRADITIONAL, second)
    }

    /**
     * 回归守卫：旧 8（繁中-香港）/ 11（繁中-台湾）→ 新 3 的迁移**不得**被本次修复破坏。
     * 这是既有 `migrateIndex` 唯一被显式白名单的兼容路径，动 switch 时最容易顺手改坏。
     */
    @Test
    fun legacyTraditionalChineseIndicesStillMigrateToNewIndex() {
        assertEquals(
            "旧下标 8（繁中-HK）必须迁移到新的 LANG_CHINESE_TRADITIONAL",
            LocaleUtils.LANG_CHINESE_TRADITIONAL,
            readAfterWriting(8)
        )
        assertEquals(
            "旧下标 11（繁中-TW）必须迁移到新的 LANG_CHINESE_TRADITIONAL",
            LocaleUtils.LANG_CHINESE_TRADITIONAL,
            readAfterWriting(11)
        )
    }

    /** 回归守卫：英文/简中两个新下标往返一致（不动 switch 其余分支）。 */
    @Test
    fun englishAndSimplifiedChineseIndicesStillRoundTrip() {
        assertEquals(
            "LANG_ENGLISH 必须原样读回",
            LocaleUtils.LANG_ENGLISH,
            readAfterWriting(LocaleUtils.LANG_ENGLISH)
        )
        assertEquals(
            "LANG_CHINESE_SIMPLIFIED 必须原样读回",
            LocaleUtils.LANG_CHINESE_SIMPLIFIED,
            readAfterWriting(LocaleUtils.LANG_CHINESE_SIMPLIFIED)
        )
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            LocaleUtils.getLocale(readAfterWriting(LocaleUtils.LANG_CHINESE_SIMPLIFIED))
        )
    }

    /**
     * 回归守卫：已下线语言的旧下标仍然归到"跟随系统"。
     * 逐个枚举旧 12 项里被下线的那 7 个（3=俄语除外，见类注释的历史碰撞）：
     * 4 葡 / 5 波斯 / 6 乌 / 7 德 / 9 日 / 10 土。
     */
    @Test
    fun retiredLegacyIndicesStillFallBackToSystem() {
        for (old in listOf(4, 5, 6, 7, 9, 10)) {
            assertEquals(
                "旧下标 $old 属已下线语言，必须归到 LANG_SYSTEM",
                LocaleUtils.LANG_SYSTEM,
                readAfterWriting(old)
            )
        }
    }

    /** 未写入时（没有任何 lang 键）必须读成跟随系统。 */
    @Test
    fun missingPreferenceReadsAsSystem() {
        val ctx = freshContext()
        assertEquals(
            "偏好里没有 lang 键时必须读成 LANG_SYSTEM",
            LocaleUtils.LANG_SYSTEM,
            LocaleUtils.getLanguage(ctx)
        )
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 写一个原始下标进偏好，再用生产读函数读回来（保留原始写入，模拟老版本落盘的值）。 */
    private fun readAfterWriting(rawIndex: Int): Int {
        val ctx = freshContext()
        LocaleUtils.changeLanguage(ctx, rawIndex)
        return LocaleUtils.getLanguage(ctx)
    }

    private fun freshContext(): Context = fakeContext(InMemoryPreferences())

    /**
     * 只认 `getSharedPreferences` 的 Context。
     * 跳过构造函数分配（见类注释），因此覆写的方法体必须**不依赖任何未初始化字段**以外的状态 ——
     * 唯一依赖的 `prefs` 由 [fakeContext] 反射补种。
     */
    private class FakeContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private fun fakeContext(prefs: SharedPreferences): Context {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val allocate: Method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val ctx = allocate.invoke(unsafe, FakeContext::class.java) as Context
        // 跳过构造函数 ⇒ Kotlin 的 constructor-property 初始化没跑，字段还是 null，补种。
        val prefsField = FakeContext::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        prefsField.set(ctx, prefs)
        return ctx
    }

    /**
     * 内存版 SharedPreferences。
     *
     * 只实现 `getLanguage` / `changeLanguage` 真正走到的四个方法（`getInt` / `edit` /
     * `putInt` / `apply`），其余一律显式抛 `UnsupportedOperationException` —— 让"以后有人在
     * 这条链上加了新调用"立刻暴露，而不是被一个宽容的空实现悄悄吞掉。
     *
     * ★ 写入刻意**推迟到 `apply()`/`commit()`**（不直接在 `putInt` 落表）：这样如果哪天
     *   `changeLanguage` 忘了调 `apply()`，用例会红，而不是拿到一个"假通过"的值。
     */
    private class InMemoryPreferences : SharedPreferences {
        private val committed = HashMap<String, Any?>()

        override fun getInt(key: String?, defValue: Int): Int =
            (committed[key] as? Int) ?: defValue

        override fun edit(): SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            val handler = InvocationHandler { proxy, method, args ->
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
            return Proxy.newProxyInstance(
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
}
