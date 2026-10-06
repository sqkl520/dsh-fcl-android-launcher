package com.dsh.ui.shell

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcllibrary.component.view.FCLTabLayout
import com.tungsten.fcllibrary.component.view.FCLUILayout

/**
 * 页内「多页容器」基类 —— 结构照搬 FCL 的 `FCLMultiPageUI`：
 * 页内再放一个 ViewPager2 承载若干子页，顶部 `FCLTabLayout` 联动切换。
 *
 * FCL 的用法示例：`SettingUI` = [版本设置 | 启动器设置 | 插件管理 | 关于]。
 * 我们用它承载设置页 = [启动器设置 | 关于]。
 *
 * 与 FCL 一致的要点：
 * - 内层 pager **禁用滑动**（页内内容与滑动冲突），只由 tab 切换；
 * - **不预加载**相邻页、**不保留状态**：页面随 ViewPager 创建/回收；
 * - 切换时**清空临时页**（临时页属于当前 tab 的上下文，见下）；
 * - 过渡动画：目标页 `alpha 0→1` + `translationY 30dp→0`、250ms，**仅在位置真变时播放**；
 * - tab 高亮与页面位置双向同步。
 *
 * ## 临时页栈（页内导航栈）
 * 照 FCL `FCLMultiPageUI.setupPages()` 的做法，页面容器里放**两层同级 View**：
 * 内层 `pager`（tab 子页）+ **覆盖层 `FrameLayout`**（初始 `GONE`，承载临时页）。
 * 覆盖层**加在 pager 之后**，在同一父容器里后加者绘制更靠上，这就是"覆盖"的全部机制；
 * 具体行为（压栈显隐、内容区 `GONE`、200ms 淡入、弹栈立即 `removeView`）全部归
 * [DshTempPageStack]，本类只**建 View、转发调用、上报标题**，不掺和栈的内部语义 ——
 * 这样"栈是否正确"在全项目只有一处实现，避免像 FCL 的 `DownloadUI` 那样抄出第二份走样的。
 *
 * 三条既定语义（FCL 原味，照搬）：
 * 1. **切 tab 清空临时页** —— `onPageSelected` 的第一个动作就是清栈；
 * 2. **临时页不覆盖 tab 栏、也不改外壳标题** —— tab 栏在 `container` 之外，天然盖不住；
 *    标题只由栈**上报**给外壳（[setOnTempPageTitleChanged]），外壳默认不渲染，
 *    外壳标题仍按当前 tab 决定；
 * 3. **临时页随宿主页面销毁** —— [destroy] 里先清栈、再销毁 tab 子页。
 *
 * 与 FCL 的差异（有意）：
 * - 子页类型是 [DshPageUI]（FCL 用 `FCLPage`），这样**全项目只有一个页面基类**，
 *   子页同样拥有随页面存活的协程作用域；回收时调用 [DshPageUI.destroy] 取消作用域。
 */
abstract class DshMultiPageUI(
    context: Context,
    @LayoutRes layoutId: Int = R.layout.ui_dsh_multipage
) : DshPageUI(context, layoutId) {

    private lateinit var tabLayout: FCLTabLayout
    private lateinit var container: FCLUILayout
    private lateinit var pager: ViewPager2

    /** 临时页覆盖层：与 [pager] 同级、压在它上方，初始 `GONE`；显隐由 [tempStack] 控制 */
    private lateinit var tempOverlay: FrameLayout

    /** 页内临时页栈：per-UI 一份（FCL 原味），本类只转发调用 */
    private lateinit var tempStack: DshTempPageStack

    private lateinit var registry: Array<DshPageUI?>

    private var lastSelectedPosition = -1

    /** 子页数量（= tab 数） */
    abstract val pageCount: Int

    /** 按位置创建子页 */
    abstract fun createPage(position: Int): DshPageUI

    /** 第 position 个 tab 的标题 */
    abstract fun tabTitle(position: Int): Int

    /** 第 position 个 tab 的图标（返回 0 表示不带图标） */
    open fun tabIcon(position: Int): Int = 0

    override fun onCreate() {
        super.onCreate()
        tabLayout = findViewById(R.id.tab_layout)
        container = findViewById(R.id.container)
        setupPages()
    }

    private fun setupPages() {
        // 在 onCreate 阶段才读 pageCount：子类的属性初始化晚于基类构造，
        // 若在基类属性初始化时就 arrayOfNulls(pageCount) 会拿到 0（经典初始化顺序坑）
        registry = arrayOfNulls(pageCount)

        pager = ViewPager2(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 不预加载相邻页、不保留状态（与 FCL 一致：切换时才创建）
            offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
            isSaveEnabled = false
            // 只由 tab 切换：页内滚动内容与滑动切换会冲突
            isUserInputEnabled = false
            adapter = PageAdapter()
        }
        // 禁用鼠标滚轮翻页（触摸已禁用，但滚轮走另一条事件通道）
        pager.getChildAt(0)?.setOnGenericMotionListener { _, _ -> true }
        container.addView(pager)

        // 临时页覆盖层。三点说明：
        // 1. **纯代码构造、不写进 ui_dsh_multipage.xml** —— FCL 的 FCLMultiPageUI.setupPages()
        //    就是这么干的，好处是外壳布局保持干净、覆盖层天然"跟着这个 UI 走"；
        // 2. **加在 pager 之后**：同一父容器里后加的孩子绘制在上层，这就是"覆盖"的全部机制
        //    （不需要 bringToFront、不需要改 XML 顺序）；
        // 3. **加进 `container` 而不是外层 CoordinatorLayout**：tab 栏（tab_layout）属于
        //    container 之外的 FCLAppBarLayout，所以临时页展开时**盖不住 tab 栏**，
        //    用户始终能切 tab —— 这是 FCL 的既定行为，我们照搬。
        tempOverlay = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 空栈时彻底不参与绘制、也不吃触摸事件；有临时页时由栈置 VISIBLE
            visibility = View.GONE
        }
        container.addView(tempOverlay)

        // 栈把 overlay 当"舞台"、把 pager 当"内容区"：压栈时把内容区 GONE，弹空时恢复。
        // content 必须传 pager（而不是 container），否则栈会把覆盖层自己一起隐藏。
        tempStack = DshTempPageStack(overlay = tempOverlay, content = pager)

        // tab：按子类给的标题/图标动态添加（FCL 是在 XML 里静态写 TabItem）
        tabLayout.removeAllTabs()
        for (i in 0 until pageCount) {
            val tab = tabLayout.newTab().setText(tabTitle(i))
            val icon = tabIcon(i)
            if (icon != 0) tab.setIcon(icon)
            tabLayout.addTab(tab, false)
        }
        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                // 瞬时切换（不创建中间页），过渡动画交给 onPageSelected
                pager.setCurrentItem(tab.position, false)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // ★ 第一个动作：清空临时页 —— "临时页属于当前 tab 的上下文"（FCL 原味语义）。
                //   所以"压进实例 tab 的实例详情，切到别的 tab 再切回来就没了"是**预期行为**，
                //   不是丢状态；换 tab 等于换了上下文，临时页跟着上下文一起走。
                //
                //   为什么下面初始那次 `setCurrentItem(0, false)` 触发到这里也是安全的：
                //   - 这一刻栈是刚构造的，depth() == 0，dismissAll() 内部的 while 一次都不进，
                //     等价于纯空操作，没有"清掉不该清的东西"的可能；
                //   - setupPages() 跑在 onCreate() 里，此时本页面实例还没交到任何外部调用者手上
                //     （子页视图甚至还没 attach），不可能已经有人压过临时页。
                //   所以这里**不加**"位置是否真的变了"的判断 —— 加了反而会让真实切 tab 时的
                //   清理被 lastSelectedPosition 的语义（那是给过渡动画用的）意外吞掉。
                //
                //   补充：ViewPager2 在布局变化后会重新 dispatch 当前页，那种回调也会走到这里；
                //   但临时页打开时 content（pager）已被栈置为 GONE，GONE 的 View 不参与布局，
                //   因此"重排把正在看的临时页清掉"这条路径在栈非空时基本是关闭的。
                dismissAllTempPages()

                // tab 高亮同步
                val tab = tabLayout.getTabAt(position)
                if (tab != null && tabLayout.selectedTabPosition != position) tab.select()
                playEnterTransition(position)
            }
        })

        // 初始页：让第 0 个 tab 处于选中态（addTab(..., false) 不会触发回调）
        if (pageCount > 0) {
            tabLayout.getTabAt(0)?.select()
            pager.setCurrentItem(0, false)
        }
    }

    /** 过渡动画：与 FCL `FCLMultiPageUI` / `UIManager` 同一套参数 */
    private fun playEnterTransition(position: Int) {
        if (position == lastSelectedPosition) return
        lastSelectedPosition = position
        val page = getPage(position) ?: return
        val content = page.contentView
        content.animate().cancel()
        content.alpha = 0f
        content.translationY = content.resources.displayMetrics.density * 30f
        content.animate().alpha(1f).translationY(0f).setDuration(250).start()
    }

    /** 取子页（不存在则创建并初始化） */
    fun getPage(position: Int): DshPageUI? {
        if (position !in 0 until pageCount) return null
        registry[position]?.let { return it }
        val page = createPage(position)
        registry[position] = page
        page.onCreate()
        return page
    }

    /** 切到某个子页（供子页之间互跳，例如设置列表里的「关于」） */
    fun showPage(position: Int) {
        if (position in 0 until pageCount && pager.currentItem != position) {
            pager.setCurrentItem(position, false)
        }
    }

    // ---------------------------------------------------------------------
    // 临时页栈 —— 对外沿用 FCL 的方法名，内部一律转发给 [DshTempPageStack]。
    //
    // 本类**不做任何额外判断**（不判空、不改返回值、不自己动 overlay 的 visibility）：
    // 语义的唯一实现处是栈本身。谁想改栈的行为，改 DshTempPageStack；子类**禁止**
    // 自己再抄一份 —— FCL 的 DownloadUI 就抄出了累积泄漏：它用 withEndAction 移除 View，
    // 而动画一旦被取消回调就不执行，临时页整棵 View 树残留在 overlay 上，越点越漏。
    // ---------------------------------------------------------------------

    /** 压入一个临时页（可叠多层）；[title] 只上报给外壳，本类与外壳默认都不渲染 */
    fun showTempPage(page: View, title: CharSequence? = null) = tempStack.show(page, title)

    /** 弹出栈顶临时页；返回是否真的弹掉了一层（空栈返回 false） */
    fun dismissCurrentTempPage(): Boolean = tempStack.dismissCurrent()

    /** 清空整个临时页栈（切 tab 时调它；空栈时是空操作） */
    fun dismissAllTempPages() = tempStack.dismissAll()

    /** 连续弹栈到只剩 [depth] 层；返回是否发生了弹栈（FCL 没有这个能力，是我们补的） */
    fun popToTempPage(depth: Int): Boolean = tempStack.popTo(depth)

    /** 弹到栈底 = 回到 tab 子页（FCL 靠连调 N 次返回兜底，我们补一个直达） */
    fun popToRootTempPage() = tempStack.popToRoot()

    /** 当前是否还有临时页可弹（外壳返回链的第三级判断用） */
    fun canReturnTempPage(): Boolean = tempStack.canReturn()

    /** 当前临时页层数（0 = 只剩 tab 子页） */
    fun tempPageDepth(): Int = tempStack.depth()

    /** 注册标题回调：栈只**上报**标题，不改外壳标题（外壳默认不渲染，FCL 原味） */
    fun setOnTempPageTitleChanged(l: (CharSequence?) -> Unit) = tempStack.setOnTitleChanged(l)

    override fun destroy() {
        // 顺序有讲究：**先清临时页，再销毁 tab 子页**。
        // 临时页是压在子页之上的视图，且栈内部持有对 pager（内容区）的引用；先清栈能保证
        // 临时页整棵 View 树先被摘掉、overlay 回到 GONE，之后再去拆子页时不会出现
        // "子页已 destroy、临时页还挂在这棵已死的宿主上"的悬空状态；
        // 反过来做的话，栈随后去恢复一个已经 destroy 的内容区，恢复动作会落在废视图上。
        tempStack.destroy()

        for (i in 0 until pageCount) {
            registry[i]?.destroy()
            registry[i] = null
        }
        super.destroy()
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {

        inner class Holder(val box: FrameLayout) : RecyclerView.ViewHolder(box) {
            var bound = -1
        }

        override fun getItemCount(): Int = pageCount

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                FrameLayout(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bound = position
            holder.box.removeAllViews()
            val content: View = getPage(position)?.contentView ?: return
            // GapWorker 预取可能把同一页面挂到别的容器，先解除旧 parent 再 add
            (content.parent as? ViewGroup)?.removeView(content)
            holder.box.addView(
                content,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        override fun onViewRecycled(holder: Holder) {
            // 不保留状态：回收即销毁子页（同时取消其协程作用域）
            val pos = holder.bound
            if (pos in 0 until pageCount) {
                registry[pos]?.destroy()
                registry[pos] = null
            }
        }
    }
}
