package com.dsh.ui.shell

import android.content.Context
import androidx.annotation.LayoutRes
import com.tungsten.fclcore.task.Task
import com.tungsten.fcllibrary.component.ui.FCLCommonUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * dsh 外壳内所有页面的基类。
 *
 * ## 为什么需要它
 * `FCLCommonUI`（FCL 框架）**不是** `LifecycleOwner`，没有协程作用域；而 dsh 页面需要
 * 用协程订阅 `StateFlow`（实例列表、日志、运行状态）。原来这些逻辑在独立 Activity 里靠
 * `lifecycleScope` + `repeatOnLifecycle`。迁进 ViewPager 后页面随滑动创建/回收，必须有一个
 * **随页面存活、页面被回收即取消**的作用域，否则会协程泄漏或重复订阅。
 *
 * [DshUIManager] 在创建页面后不动，在 `onViewRecycled` 回收页面时调用 [destroy]。
 * 子类在 [onCreate] 里用 [scope] 启动 collect 即可，无需手动取消。
 */
abstract class DshPageUI(
    context: Context,
    @LayoutRes id: Int
) : FCLCommonUI(context, id) {

    /** 页面级协程作用域：主线程、页面回收时整体取消 */
    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 由 [DshUIManager] 在页面被 ViewPager 回收时调用 */
    open fun destroy() {
        scope.cancel()
        onDestroy()
    }

    /** 子类覆写做额外清理（可选） */
    protected open fun onDestroy() {}

    /** dsh 页面默认无需 FCL 的 Task 式刷新 */
    override fun refresh(vararg param: Any?): Task<*>? = null

    /**
     * 本页对外声明的标题；`null` = 本页不声明（外壳沿用 tab 标题）。
     *
     * ## 为什么要有这个钩子
     * 它是「宿主层三件套」（临时页栈 / 标题源 / 返回链）里的**标题源**。
     * FCL 的标题栏由 Activity 在两处手写 `switch` 驱动（点菜单时、切页时各一份），
     * 字符串散落在外壳里 —— 于是**加一个页面要同时去改外壳**，两处 switch 还会各自漂移。
     * 这里把方向反过来：**页面自己声明，外壳负责渲染**。
     * 加页面的改动因此收敛在这一个类里，外壳不需要知道有哪些页面。
     *
     * ## 为什么默认返回 `null` 而不是空串
     * `null` 的语义是"**本页不声明**"，不是"标题为空"。外壳拿到 `null` 时回落到 tab 标题
     * （见 [DshUIManager.titleOf]），所以**不覆写这个方法的页面行为完全不变** ——
     * 现有 5 个 tab 页一行都不用改。若默认成空串，外壳就分不清"没声明"和"声明了空标题"，
     * 会把标题渲染成空白。
     *
     * ## 为什么是 `CharSequence?` 而不是 `String` / `@StringRes Int`
     * - 不是 `Int` 资源 id：页面已经持有 `context`，要本地化字符串自己 `getString` 即可；
     *   强制返回资源 id 会挡住"标题里带运行时数据"的页面（例如"实例名 · 端口"），
     *   那种标题本来就不存在于 strings.xml 里。
     * - 不是 `String`：`CharSequence` 容得下 `Spannable`，给将来"标题里某段变色/加粗"留口子，
     *   而且不需要为此改签名。
     *
     * ⚠️ 与临时页无关：临时页的标题走 `DshMultiPageUI` 的**上报**通道
     * （`setOnTempPageTitleChanged`），外壳**故意不渲染**它（FCL 原味：临时页不改外壳标题）。
     * 本方法只描述**页面自己**的标题。
     */
    open fun pageTitle(): CharSequence? = null

    /**
     * 页内返回 —— 返回链的**第 ④ 级**。
     *
     * 完整链条（顺序不可变，照 FCL）：
     * ```
     * ① Activity onKeyDown(BACK) 入口
     * ② 转发给当前页（DshUIManager.onBackPressed）
     * ③ 当前页的临时页栈（canReturn → dismissCurrent）
     * ④ 页内自定义回退            ← 就是这里
     * ⑤ 全局兜底：非首页 → 回首页；首页 → 退出
     * ```
     *
     * ## 为什么返回 `Boolean` 而不是 `Unit`
     * 返回链必须知道"这一级到底消费了没有"。若返回 `Unit`，第 ② 级无从判断该不该往下走，
     * 结果就是**每一页都"消费"了返回** —— 在第 ④ 级空实现的情况下也会把 App 退出去。
     * 默认 `false`（不消费）保证不覆写本方法的页面**行为完全不变**。
     *
     * ## 为什么不叫 `onBackPressed`
     * 祖父类 `FCLBaseUI` 已有 `public void onBackPressed()`（`FCLCommonUI` 又 `@Override` 了它）。
     * Kotlin 里写同名同参、只改返回类型是**编译错误**（accidental override：`Boolean` 不是
     * `Unit` 的子类型），不是重载。
     * 而且那个方法与本方法**不是一件事**：它是第 ⑤ 级的载体，靠静态 `defaultBackEvent`
     * 与 `isShowing()` 守卫工作（见 `FCLBaseUI.onBackPressed`）。两者混用一个名字，
     * 只会让"返回键到底被哪一级吃掉了"更难查。
     */
    open fun onPageBack(): Boolean = false
}
