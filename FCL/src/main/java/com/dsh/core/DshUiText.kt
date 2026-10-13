package com.dsh.core

import android.content.Context
import com.tungsten.fcllibrary.util.LocaleUtils

/**
 * 「给用户看的文案」该用哪个 Context 取。
 *
 * ## 为什么不能直接用调用方传进来的那个
 * Android 的资源级语言是**按 Context 解析**的，而启动器的语言是**用户自己选的**
 * （存在 `launcher.lang`，由 `FCLActivity.attachBaseContext` 在 Activity 上应用）。
 * 于是：
 * - **Activity 的 Context** → 认用户选的语言 ✅
 * - **Application 的 Context** → 只跟随系统语言 ❌
 *
 * 而本项目里大量长驻逻辑（安装器、运行时、底座引导、任务聚合）拿到的都是
 * `applicationContext`（它们不该持有 Activity）。**结果是：把中文换成 `R.string` 之后，
 * 英文界面下取到的仍然是中文** —— 换资源本身解决不了这件事，必须连"用哪个 Context 取"一起换。
 *
 * ## 做法
 * 优先取当前前台 Activity 的 Context（拿不到再退回 Application 的），并显式套一次
 * [LocaleUtils.setLanguage] —— 后者返回的是 `createConfigurationContext(...)` 出来的新 Context，
 * 语言与用户在设置页选的一致。
 *
 * ⚠️ **不要缓存返回值**：这条链是进程级的，用户可以在 App 存活期间切语言，
 * 缓存会把它钉死在旧语言上。（`createConfigurationContext` 只解析一份资源表，
 * 不做 IO，每次现取的代价可以接受。）
 *
 * ⚠️ **只在要取"给用户看的字符串"时用**。取路径、比对标识、读配置这些**不涉及语言**的场合，
 * 用调用方原本的 Context 就对 —— 别为了统一而统一。
 */
object DshUiText {

    /**
     * 取一个"语言正确"的 Context。
     * @param fallback 连前台 Activity 都拿不到时用的（通常是调用方自己持有的 Context）
     */
    @JvmStatic
    fun context(fallback: Context): Context =
        (com.tungsten.fcl.FCLApp.getActivity() ?: DshAppContextHolder.context)
            ?.applicationContext
            ?.let { LocaleUtils.setLanguage(it) }
            ?: fallback

    /** 同上，但调用方**没有**任何 Context 可用（拿不到就返回 null，调用方自己兜底英文） */
    @JvmStatic
    fun contextOrNull(): Context? =
        (com.tungsten.fcl.FCLApp.getActivity() ?: DshAppContextHolder.context)
            ?.applicationContext
            ?.let { LocaleUtils.setLanguage(it) }

    /**
     * 取字符串；连 Context 都拿不到时用 [fallback]。
     *
     * 这个重载存在是为了让调用点保持一行 —— "拿不到 Context"是个**不该发生**的分支
     * （后台逻辑在 App 起来之后才有意义），但真要发生时给一句英文比给空串好。
     */
    @JvmStatic
    fun getString(resId: Int, fallback: String, vararg args: Any): String =
        contextOrNull()?.getString(resId, *args) ?: fallback

    /**
     * 把**任意 base Context** 套上启动器语言设置，返回按用户所选语言取词的 Context。
     *
     * ## 为什么要有这个入口（BL-107）
     * [context] / [contextOrNull] 是"**自己去拿**"：前台 Activity 优先、拿不到退回 Application。
     * 而 core 层有一批**已经持有 applicationContext** 的长驻链路（`DshRuntime` / `DshInstances` /
     * `DshRuntimeService`），它们的取词缺的不是"去拿 Activity"，而是"把手里这个 base 变对"：
     * ```
     * val ui = DshUiText.wrap(appContext ?: return)
     * ui.getString(R.string.dsh_reason_exited_early, code)
     * ```
     *
     * ## 实现
     * 直接复用 [LocaleUtils.setLanguage] —— 与 `FCLActivity.attachBaseContext` 完全同一条链
     * （读 `launcher.lang` → 旧下标归一 → `createConfigurationContext`），**不另造 wrapper、
     * 不复制第二份语言判定**（两处判定必然漂移）。`setLanguage` 只读偏好不写盘；
     * `createConfigurationContext` 只解析一份资源表，无 IO。
     *
     * ⚠️ 与 [contextOrNull] 一样**不要缓存返回值**：用户可在 App 存活期间切语言。
     *
     * ⚠️ 只在取"给用户看的字符串"时用。取路径、比对标识、读配置这些**不涉及语言**的场合，
     * 继续用调用方原本的 Context —— 别为了统一而统一（同上文告诫）。
     */
    @JvmStatic
    fun wrap(base: Context): Context = LocaleUtils.setLanguage(base)
}
