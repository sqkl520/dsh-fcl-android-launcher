package com.dsh.ui.shell

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcllibrary.component.ui.FCLCommonUI

/**
 * dsh 外壳的页面管理器。仿 FCL 原 `com.tungsten.fcl.ui.UIManager`（已随 MC 删除）的做法：
 * 用 ViewPager2 承载 N 个 [FCLCommonUI] 页面，页面随 ViewPager 生命周期创建/销毁、不保留状态。
 *
 * 阶段 2：五个 tab 迁为真实页面（实例/管理/下载/日志/设置）。
 * 阶段 3：tab 收到三个（实例/版本/设置）—— 「管理」是零引用的空占位，「日志」拆成
 * 实例日志（进实例详情）与 App 日志（进设置的子页），理由见 [DshShellHost] 的 TAB_* 常量。
 * 页面经 [DshShellHost] 回调实现跨 tab 跳转，无需再 startActivity。
 */
class DshUIManager(
    private val host: DshShellHost,
    private val pager: ViewPager2
) {

    private val context: Context get() = host.activity

    /** 页面位置 → 标题资源（供动态岛显示）。顺序必须与外壳菜单一致 */
    val titles: List<Int> = listOf(
        R.string.dsh_tab_instances,
        R.string.dsh_tab_versions,
        R.string.dsh_tab_settings,
    )

    /** 页面工厂。顺序必须与 [titles] 及外壳菜单一致 */
    private val factories: List<() -> FCLCommonUI> = listOf(
        { DshInstancesUI(context, host) },
        { DshDownloadUI(context, host) },
        { DshSettingsUI(context, host) },
    )

    private val registry = arrayOfNulls<FCLCommonUI>(factories.size)

    /** 页面切换回调：外壳用来同步菜单高亮与动态岛标题 */
    var pageSelectedListener: ((Int) -> Unit)? = null

    val count: Int get() = factories.size

    /** 上一次真正选中的页（用于判断"位置是否真的变了"） */
    private var lastSelectedPosition = -1

    fun init() {
        pager.adapter = Adapter()
        pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        pager.isUserInputEnabled = false       // 只由菜单切换，禁手势滑动
        pager.isSaveEnabled = false            // 重建后从首页开始，不恢复途经页
        pager.offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageSelectedListener?.invoke(position)
                playEnterTransition(position)
            }
        })
    }

    /**
     * 切页过渡动画 —— 与 FCL 的 `UIManager.pageChangeCallback` 完全一致：
     * 目标页 **淡入（alpha 0→1）+ 上滑（translationY 30dp→0）**，时长 250ms。
     *
     * 两个关键细节（照抄 FCL 的注释与做法）：
     * 1. **同步执行、不 post**：`onPageSelected` 时页面已挂载但尚未绘制，此时置透明不会出现
     *    "先显示再消失"的闪烁；
     * 2. **只在位置真的变化时播放**：ViewPager2 在布局变化（软键盘弹出、页面内容刷新）后
     *    会重新 dispatch 当前页，那种情况不播动画，否则页面会莫名闪一下。
     */
    private fun playEnterTransition(position: Int) {
        if (position == lastSelectedPosition) return
        lastSelectedPosition = position
        val content = runCatching { getUI(position).contentView }.getOrNull() ?: return
        content.animate().cancel()
        content.alpha = 0f
        content.translationY = content.resources.displayMetrics.density * 30f
        content.animate().alpha(1f).translationY(0f).setDuration(250).start()
    }

    /** 切到指定页（瞬时，不滑动，避免途经页被创建） */
    fun switchTo(position: Int) {
        if (position in 0 until count && pager.currentItem != position) {
            pager.setCurrentItem(position, false)
        }
    }

    /**
     * 取页面，**不存在就创建**。
     *
     * ★ 与只读的 [uiAt] 的区别：`uiAt` 是"取标题"用的纯查询，绝不能有副作用；
     *   而本方法专供"调用方马上要切到这一页、并且立刻要对它做点什么"的场景
     *   （外壳收到"打开实例详情"的请求时，需要拿到 [DshInstancesUI] 的实例来压栈）。
     *   创建页面是这里的**预期副作用**，所以单独开一个方法，不去污染 `uiAt` 的语义。
     *
     * 实现直接复用 [getUI]：那一份"registry 命中就复用、否则建好并 `onCreate()`"的逻辑
     * 与 Adapter bind 时用的是同一条路径，页面只会被创建一次（`registry` 是唯一缓存）。
     *
     * 这里**不加下标守卫**：调用方传的就是 [DshShellHost] 的 TAB_* 常量，越界属于编程错误，
     * 让它按 [getUI] 原样抛出来（越界即崩）比悄悄返回 null 更容易发现 ——
     * 返回类型可空只是为了让调用方能安全转换，不是给越界留后路。
     */
    fun ensurePage(position: Int): FCLCommonUI? = getUI(position)

    private fun getUI(position: Int): FCLCommonUI =
        registry[position] ?: factories[position]().also {
            registry[position] = it
            it.onCreate()
        }

    /**
     * **只读**访问器：只返回**已经创建过**的页面实例，没有就返回 null。
     *
     * ★ 为什么不复用上面的 [getUI]：`getUI` 在实例不存在时会**创建并 `onCreate()`** 一个页面。
     *   而 [titleOf] 是外壳**每次切页都要调**的查询 —— 若走 `getUI`，就变成
     *   "为了拿一个标题把页面建出来"：
     *   1. 页面的 `onCreate` 不是廉价的（建列表/起协程订阅 StateFlow/拉数据），
     *      一次切页的标题查询不该有这种副作用；
     *   2. 更糟的是它会**打乱"页面随 ViewPager 生命周期创建/回收"**这条既定模型 ——
     *      提前建出来的 contentView 会被塞进 [registry]，而它并没有被 Adapter bind 过，
     *      随后 [Adapter.onViewRecycled] 会把它当成"已绑定页面"销毁，出现"建了又立刻回收"的空转。
     *   取标题是纯查询，纯查询不许有副作用，所以这里另开一个只读入口。
     */
    private fun uiAt(position: Int): FCLCommonUI? =
        if (position in registry.indices) registry[position] else null

    /**
     * 当前页。**未创建过则返回 null —— 不为了取标题而创建页面**（理由见 [uiAt]）。
     *
     * 返回类型是 [DshPageUI] 而不是 `FCLCommonUI`：返回链的第 ③④ 级只对 dsh 页面有意义。
     * 转换仍写成 `as?`（而不是 `as`）：[registry] 的静态类型是 `FCLCommonUI`，是否所有页面都
     * 继承 [DshPageUI] 是**页面工厂的实现细节**，不是这里的类型约束 —— 将来若有人往
     * [factories] 里放一个直接继承 `FCLCommonUI` 的页，用 `as` 会在切到那一页时直接抛
     * `ClassCastException`，而返回链本该只是"这一级不消费"。安全转换把这个失败模式变成
     * "返回键不归这一页管"，代价是一次 null 判断。
     */
    fun currentPage(): DshPageUI? = uiAt(pager.currentItem) as? DshPageUI

    /** 当前页在 [titles] 里的下标（= ViewPager2 的当前项，与外壳菜单高亮同源） */
    val currentPosition: Int get() = pager.currentItem

    /**
     * 返回链的 **②③④ 级**（① 在 Activity 的 `onKeyDown`，⑤ 在 Activity 的兜底）。
     * 顺序照搬 FCL，不可调换：
     *
     * 1. **守卫**：当前页不是 [DshPageUI]、或 `isShowing()` 为 false → **不消费**。
     *    `isShowing()` 的实现是 `contentView.isShown()`（见 `FCLCommonUI`）—— 不显示的 UI
     *    不该消费返回键；首帧还没布局完成时它也是 false，此时落到第 ⑤ 级兜底是正确行为。
     * 2. **临时页栈**：当前页是 [DshMultiPageUI] 且还有可弹的临时页 → 弹一层并消费。
     * 3. **页内自定义回退**：交给 [DshPageUI.onPageBack]（默认 false）。
     *
     * @return true = 已消费（调用方不要再往下走）；false = 不消费，交给第 ⑤ 级兜底
     */
    fun onBackPressed(): Boolean {
        val page = currentPage() ?: return false
        if (!page.isShowing()) return false
        if (page is DshMultiPageUI && page.canReturnTempPage()) {
            page.dismissCurrentTempPage()
            return true
        }
        return page.onPageBack()
    }

    /**
     * 第 [position] 页的标题：**页面自己声明优先，否则回落到 tab 标题**。
     *
     * 用 [uiAt] 而不是 [getUI] —— 理由见 [uiAt] 的注释（取标题不能有创建页面的副作用）。
     * 所以未创建过的页会直接回落到 tab 标题，这正是我们想要的：外壳标题不因"页面还没建"而空缺。
     */
    fun titleOf(position: Int): CharSequence =
        (uiAt(position) as? DshPageUI)?.pageTitle() ?: context.getString(titles[position])

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
            var bound = 0
        }

        override fun getItemCount(): Int = count
        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return Holder(container)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bound = position
            holder.container.removeAllViews()
            val content = getUI(position).contentView
            (content.parent as? ViewGroup)?.removeView(content)
            holder.container.addView(
                content,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        override fun onViewRecycled(holder: Holder) {
            // 页面被回收：若是 DshPageUI 则取消其协程作用域，避免泄漏
            (registry[holder.bound] as? DshPageUI)?.destroy()
            registry[holder.bound] = null      // 不保留状态，随视图回收销毁
        }
    }
}
