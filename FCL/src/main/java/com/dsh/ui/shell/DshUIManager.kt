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
 * 页面经 [DshShellHost] 回调实现跨 tab 跳转，无需再 startActivity。
 */
class DshUIManager(
    private val host: DshShellHost,
    private val pager: ViewPager2
) {

    private val context: Context get() = host.activity

    /** 页面位置 → 标题资源（供动态岛显示） */
    val titles: List<Int> = listOf(
        R.string.dsh_tab_instances,
        R.string.dsh_tab_manage,
        R.string.dsh_tab_download,
        R.string.dsh_tab_logs,
        R.string.dsh_tab_settings,
    )

    /** 页面工厂。顺序必须与 [titles] 及外壳菜单一致 */
    private val factories: List<() -> FCLCommonUI> = listOf(
        { DshInstancesUI(context, host) },
        { DshPlaceholderUI(context, R.string.dsh_tab_manage, R.string.dsh_manage_placeholder) },
        { DshDownloadUI(context, host) },
        { DshLogsUI(context) },
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

    private fun getUI(position: Int): FCLCommonUI =
        registry[position] ?: factories[position]().also {
            registry[position] = it
            it.onCreate()
        }

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
