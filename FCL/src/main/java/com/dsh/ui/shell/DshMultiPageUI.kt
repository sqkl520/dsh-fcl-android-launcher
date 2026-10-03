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
 * - 切换时**清空临时页**（本项目暂未使用临时页栈，保留该钩子）；
 * - 过渡动画：目标页 `alpha 0→1` + `translationY 30dp→0`、250ms，**仅在位置真变时播放**；
 * - tab 高亮与页面位置双向同步。
 *
 * 与 FCL 的差异（有意）：
 * - 子页类型是 [DshPageUI]（FCL 用 `FCLPage`），这样**全项目只有一个页面基类**，
 *   子页同样拥有随页面存活的协程作用域；回收时调用 [DshPageUI.destroy] 取消作用域；
 * - 暂未实现 FCL 的「临时页覆盖层（导航栈）」—— 本项目当前没有该需求（见 `docs/TASKS.md`）。
 */
abstract class DshMultiPageUI(
    context: Context,
    @LayoutRes layoutId: Int = R.layout.ui_dsh_multipage
) : DshPageUI(context, layoutId) {

    private lateinit var tabLayout: FCLTabLayout
    private lateinit var container: FCLUILayout
    private lateinit var pager: ViewPager2

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

    override fun destroy() {
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
