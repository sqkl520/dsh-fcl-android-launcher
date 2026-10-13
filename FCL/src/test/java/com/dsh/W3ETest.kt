package com.dsh

import android.content.Context
import com.dsh.core.DshUiText
import com.tungsten.fcllibrary.util.LocaleUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * W3E（fix2）：BL-123 滑条实时读取 + BL-107 取词机制（`DshUiText.wrap`）的契约测试。
 *
 * ## 为什么测这些（而不是 `wrap` 本身的运行时行为）
 * `wrap(base)` 的实现是**一行委派** `LocaleUtils.setLanguage(base)` —— 语言判定的本体
 * （`launcher.lang` 下标 → Locale → `createConfigurationContext`）在 [LocaleUtils]，
 * 不在本 agent 的文件集里，规格禁止改动、也禁止在 DshUiText 里复制第二份判定
 * （两处判定必然漂移）。所以这里钉的是**wrap 机制所依赖的两层契约**：
 *
 * 1. **存储契约**：`LANG_*` 常量值必须稳定（它们按整数存进 `launcher.lang`；
 *    改值 = 老用户的语言选择静默错位，仅 `migrateIndex` 兜旧 8/11→3 是显式白名单）；
 * 2. **映射契约**：三套维护中的文案语言各自映射到正确的 `java.util.Locale`
 *    （资源解析 `values-zh` / `values-zh-rTW` 全系于此）；
 * 3. **入口契约**：`DshUiText.wrap(Context): Context` 公开且静态 —— core 侧替换
 *    （BL-107 后续任务）按这个签名接入，改名/改形必须先动这里。
 *
 * ## 边界说明
 * - `getLocale(LANG_SYSTEM)` 走 `LocaleList.getDefault()`（android.os），JVM 单测
 *   环境是 mockable android.jar，执行即抛 `Stub!`，故**不测**系统语言分支（真机在案）。
 * - `wrap` 的运行时语言坐标需要 Robolectric/真机才能测 —— 项目未引入 Robolectric
 *   且本任务禁新增依赖，故以签名契约 + LocaleUtils 映射契约覆盖。
 *
 * 运行：`sh run-tests.sh`（或 `./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.W3ETest"`）
 */
class W3ETest {

    /**
     * 存储契约：四个语言下标的值被持久化在 `launcher.lang` 里，值一变老用户语言全错位。
     * （`migrateIndex` 只白名单了旧 8/11→3；把这里改成别的值没有任何迁移兜底。）
     */
    @Test
    fun languageConstantsStayStable() {
        assertEquals(0, LocaleUtils.LANG_SYSTEM)
        assertEquals(1, LocaleUtils.LANG_ENGLISH)
        assertEquals(2, LocaleUtils.LANG_CHINESE_SIMPLIFIED)
        assertEquals(3, LocaleUtils.LANG_CHINESE_TRADITIONAL)
    }

    /**
     * 映射契约：三套维护中的资源语言（values / values-zh / values-zh-rTW）与 Locale 一一对应。
     * 这是 `wrap` 与 `attachBaseContext` 共用的同一条判定链（`setLanguage` → `getLocale`）。
     */
    @Test
    fun getLocaleMapsMaintainedLanguages() {
        assertEquals(Locale.ENGLISH, LocaleUtils.getLocale(LocaleUtils.LANG_ENGLISH))
        assertEquals(Locale.SIMPLIFIED_CHINESE, LocaleUtils.getLocale(LocaleUtils.LANG_CHINESE_SIMPLIFIED))
        assertEquals(Locale.TRADITIONAL_CHINESE, LocaleUtils.getLocale(LocaleUtils.LANG_CHINESE_TRADITIONAL))
        // 资源后缀直取（values-<language>）：zh 一系简繁必须同语言不同地区
        assertEquals("en", LocaleUtils.getLocale(LocaleUtils.LANG_ENGLISH).language)
        assertEquals("zh", LocaleUtils.getLocale(LocaleUtils.LANG_CHINESE_SIMPLIFIED).language)
        assertEquals("zh", LocaleUtils.getLocale(LocaleUtils.LANG_CHINESE_TRADITIONAL).language)
        assertEquals("TW", LocaleUtils.getLocale(LocaleUtils.LANG_CHINESE_TRADITIONAL).country)
    }

    /** 繁中字段（非 final，可能被外部改写）初始值必须就是 JDK 常量，`getLocale(3)` 以它为返回源。 */
    @Test
    fun traditionalChineseFieldIsJdkConstant() {
        assertEquals(Locale.TRADITIONAL_CHINESE, LocaleUtils.TRADITIONAL_CHINESE)
        assertEquals(LocaleUtils.TRADITIONAL_CHINESE, LocaleUtils.getLocale(LocaleUtils.LANG_CHINESE_TRADITIONAL))
    }

    /**
     * 入口契约：BL-107 core 侧替换按 `DshUiText.wrap(base)` 接入（见 DshUiText 的 KDoc 示例），
     * 必须是 public static、参数与返回都是 [Context]。改名/去掉 @JvmStatic 时这条先红。
     *
     * 用 `declaredMethods` 过滤静态方法而不是 `getMethod`：Kotlin `object` + `@JvmStatic`
     * 会同时生成实例方法与静态方法（同名同签名），`getMethod` 返回哪一个未定义 ——
     * 直接 `getMethod("wrap")` 可能拿到实例方法让这条假红。
     */
    @Test
    fun wrapIsPublicStaticTakingAndReturningContext() {
        val statics = DshUiText::class.java.declaredMethods
            .filter { it.name == "wrap" && Modifier.isStatic(it.modifiers) }
        assertEquals("必须有且只有一个静态 wrap(Context) 入口", 1, statics.size)
        val m = statics.single()
        assertTrue("wrap 必须是 public", Modifier.isPublic(m.modifiers))
        assertEquals("wrap 的参数必须是 Context", Context::class.java, m.parameterTypes.single())
        assertEquals("wrap 必须原样返回 Context", Context::class.java, m.returnType)
    }
}
