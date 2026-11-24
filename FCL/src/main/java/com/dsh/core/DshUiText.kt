package com.dsh.core

import android.content.Context
import com.tungsten.fcllibrary.util.LocaleUtils

/**
 * core 从 ui / App 层要"取词用的 Context"的**最小契约**（L2 修复）。
 *
 * ## 为什么需要它
 * 改造前 [DshUiText.contextOrNull] 直接写死了 `com.tungsten.fcl.FCLApp.getActivity()` ——
 * core 层要取一句给用户看的文案，就得向 **UI 层要"当前前台 Activity"**。这是典型的
 * UI 概念漏进 core：core 的字符串正确性取决于"此刻有没有 Activity 在前台"，
 * 而这与"core 想表达什么"毫无关系。
 *
 * 现在 core 只认这个接口：由 App 组装层（本项目的 `FCLApp.onCreate` → `registerUiCapabilitiesForCore()`）
 * 注入一个实现，回答"拿哪个 Context 取词语言才正确"。
 *
 * ## 未注入时怎么办（**不能因此变差**）
 * 返回 null 不是失败，是"没有更优的 Context 可用"：调用方 [DshUiText.contextOrNull] 会退到
 * [DshAppContextHolder.context]（core 自己持有的 Application Context，只跟随系统语言）。
 * 这是**与注入失败前完全同一条退路** —— 改造前后"拿不到前台 Activity"这一态的后果一模一样，
 * 所以注入方即便整体失败也不会让现状回退。
 *
 * ## 为什么只有这一个方法
 * 语言包装（`LocaleUtils.setLanguage`）留在 [DshUiText] 里，不进接口：它是**纯静态工具**
 * （读 `launcher.lang` + `createConfigurationContext`），没有 Activity/全局 UI 状态，
 * 不属于"UI 全局"。把它也塞进接口会让"注入失败 = 全部文案退回系统语言"，
 * 那是把一个低风险重构变成本项目最忌讳的静默语言回归（BL-107 的全部意义就在于此）。
 */
fun interface DshStringProvider {
    /** 当前前台 Activity 的 Context；没有则 null（**不是**错误，见接口 KDoc 的退化说明）。 */
    fun foregroundContext(): Context?
}

/**
 * [DshStringProvider] 的**可空注册点**（L2）。
 *
 * 与 [DshNavigation] 同一套形态与理由：注册方是 App 层，读取方是 core 的长驻链路
 * （安装器 / 运行时 / 底座引导 / 任务聚合），读写不同线程 ⇒ `@Volatile` 保证可见性；
 * 写入的是不可变引用，不需要锁。
 */
object DshStrings {

    @Volatile
    private var injected: DshStringProvider? = null

    /** 当前注入取词 Context 的提供者；未注入为 null。 */
    val provider: DshStringProvider?
        get() = injected

    @JvmStatic
    fun register(provider: DshStringProvider) {
        injected = provider
    }

    /**
     * 撤销注入 —— 生产路径**永远不调**，只给单测夹具用。
     *
     * harness 在同一个 JVM 里跑全部测试类，注入过的假实现不还原就会漏给后面的用例
     * （与 `DshAppContextHolder` 在 `R31Fix233Test.clearHolder` 里被还原同一理由）。
     */
    @JvmStatic
    fun clear() {
        injected = null
    }
}

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
 * 优先取当前前台 Activity 的 Context（由 [DshStringProvider] 注入，拿不到再退回
 * [DshAppContextHolder] 的 Application Context），并显式套一次
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
    fun context(fallback: Context): Context = contextOrNull() ?: fallback

    /**
     * 同上，但调用方**没有**任何 Context 可用（拿不到就返回 null，调用方自己兜底英文）。
     *
     * ★ 覆盖边界（verify-r31-243 的 N2，2026-11-13 更正）：本方法只把
     * [LocaleUtils.setLanguage] 这一步的异常按"拿不到"处理（见 [localizedOrRaw]）。
     * `getActivity()` / `applicationContext` 抛出的异常**会原样穿给调用方** —— 实测
     * `applicationContext` 抛 `IllegalStateException` 时本方法直接抛出，而同场景
     * [getString] 因为有 [orFallback] 会返回 fallback。
     *
     * 需要"整条链都兜住"的调用方请用 [getString]，或者自己给本方法套 try。
     * `DshBootstrap` 有 6 处直接消费本方法（`:361`/`:418`/`:591`/`:691` 等），它们不受兜底保护。
     *
     * ★ L2（R49）：**不再直接引用 `FCLApp.getActivity()`**。拿前台 Activity 这件事
     * 收敛到 [DshStringProvider]，由 App 层注入（见 `FCLApp.onCreate` 的
     * `registerUiCapabilitiesForCore()`）。注入缺失/失败时走 [DshAppContextHolder.context]，
     * 与改造前"没有前台 Activity"完全同一条退路 —— 不会让现状回退。
     */
    @JvmStatic
    fun contextOrNull(): Context? =
        (DshStrings.provider?.foregroundContext() ?: DshAppContextHolder.context)
            ?.applicationContext
            ?.let { localizedOrRaw(it) }

    /**
     * 把 [base] 套上启动器语言；套不上就**原样返回 [base]**。
     *
     * 为什么不是"套不上返回 null"：调用方要的是"能取词的 Context"，语言不对只是取到系统语言，
     * 比拿不到 Context 好得多。为什么不是"让异常穿出去"：这条链是纯 UI 装饰性的
     * （`getSharedPreferences` / `createConfigurationContext` 都可能因系统异常失败），
     * 让"取一句文案"把业务逻辑带崩是本末倒置。
     */
    private fun localizedOrRaw(base: Context): Context =
        try {
            LocaleUtils.setLanguage(base)
        } catch (t: Throwable) {
            base
        }

    /**
     * 取字符串；连 Context 都拿不到、或取词本身失败时用 [fallback]。
     *
     * 这个重载存在是为了让调用点保持一行 —— "拿不到 Context"是个**不该发生**的分支
     * （后台逻辑在 App 起来之后才有意义），但真要发生时给一句英文比给空串好。
     *
     * ⚠️ [fallback] 覆盖的是**整个取词动作**，不只是"没有 Context"：资源缺失
     * （`Resources.NotFoundException`）、占位符与参数不匹配（`IllegalFormatException`）
     * 这类"改了文案才会暴露"的失败同样落 [fallback]。它们恰恰是本方法最该兜住的一类 ——
     * 一条 UI 文案不该因为文案本身写错就把调用方带崩。
     */
    @JvmStatic
    fun getString(resId: Int, fallback: String, vararg args: Any): String =
        orFallback(fallback) {
            // 走 `resources.getString` 而不是 `Context.getString`：后者在 android.jar 里是
            // `final`，JVM 单测无法伪造它（见 R31DateTimeFormatterLocaleTest:45），
            // 兜底逻辑就永远不会被真正执行过。两者语义相同（AOSP 里前者就是这一行委派）。
            contextOrNull()?.resources?.getString(resId, *args) ?: fallback
        }

    /**
     * [getString] 的兜底本体：任何 [Throwable] 一律落 [fallback]。
     *
     * 抽成独立函数是为了让"兜底面 = 整个取词动作"这件事在源码里一眼可见。
     *
     * ★ 更正（verify-r31-243 的 N3，2026-11-13）：本段原写「`R31Fix233Test` 会断言
     * [getString] 的取词表达式确实在 [orFallback] 之内」，**该断言并不存在** ——
     * `grep -rn orFallback FCL/src/test/` 零命中；`R31Fix233Test` 的源码级结构断言只有
     * 针对 `PtyNative.kt` 的 `noUnguardedNativeCallInSource`，对本文件没有对应物。
     * 一条可被机械证伪的 KDoc 比没有 KDoc 更糟：它会让后续维护者以为有防回归网。
     * 若将来要真的加上，断言应写成「`getString` 体内 `Resources.getString(` 那次调用
     * 位于 `orFallback(` 的实参 lambda 里」。
     */
    private inline fun <T> orFallback(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            fallback
        }

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
