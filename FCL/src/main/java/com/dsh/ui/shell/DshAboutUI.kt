package com.dsh.ui.shell

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dsh.fcl.androidlauncher.BuildConfig
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ItemDshAboutBinding
import com.dsh.fcl.androidlauncher.databinding.ItemDshAboutDescBinding
import com.dsh.fcl.androidlauncher.databinding.UiDshAboutBinding
import com.mio.ui.adapter.SpacingItemDecoration
import com.tungsten.fcllibrary.component.theme.ThemeEngine

/**
 * 关于页 —— 结构照搬 FCL 的 `ui/setting/AboutPage.kt`：
 * 顶部一行**说明文字**，下面若干**链接行**合成一组；
 * 行背景圆角按位置（组首上圆角 / 组尾下圆角 / 中间无圆角），
 * 组内行间 1dp 缝隙并绘制主题色分割线，说明行与链接组之间 8dp。
 *
 * 目前由 [DshFullPageDialog] 全屏承载（设置页「关于本启动器」进入）；
 * 待「页内多页 + 标签栏」（TASKS T3）落地后，本类可直接作为设置页的子页复用，无需改动。
 */
class DshAboutUI(context: Context) : DshPageUI(context, R.layout.ui_dsh_about) {

    private val binding = UiDshAboutBinding.bind(contentView)
    private lateinit var adapter: AboutAdapter

    /** 链接行：标题资源 + 跳转地址（顺序即显示顺序） */
    private data class LinkItem(val titleRes: Int, val url: String)

    private val links = listOf(
        LinkItem(R.string.dsh_about_link_repo, "https://github.com/sqkl520/dsh-fcl-android-launcher"),
        LinkItem(R.string.dsh_about_link_fcl, "https://github.com/FCL-Team/FoldCraftLauncher"),
        LinkItem(R.string.dsh_about_link_proot, "https://github.com/oonid/pr"),
        LinkItem(R.string.dsh_about_link_license, "https://www.gnu.org/licenses/gpl-3.0.html"),
    )

    private val themeInvalidate = Runnable { binding.aboutList.invalidate() }

    override fun onCreate() {
        super.onCreate()
        adapter = AboutAdapter(::openLink)
        binding.aboutList.layoutManager = LinearLayoutManager(context)
        val rowSpacing = dp(8)
        binding.aboutList.addItemDecoration(
            SpacingItemDecoration(
                rowSpacing,
                { parent, position ->
                    val a = parent.adapter as? AboutAdapter
                    if (a?.isNextInSameGroup(position) == true) dp(1) else rowSpacing
                },
                true,
                { ThemeEngine.getInstance().getTheme().color }
            )
        )
        ThemeEngine.getInstance().registerEvent(binding.aboutList, themeInvalidate)
        binding.aboutList.adapter = adapter
    }

    override fun onDestroy() {
        ThemeEngine.getInstance().unregisterEvent(binding.aboutList)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun openLink(index: Int) {
        val url = links.getOrNull(index)?.url ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    /**
     * 说明行 + 链接行。链接行合成一组：组首上圆角、组尾下圆角、中间无圆角；
     * 行背景 tint 用主题的**浅色**（`ltColor`）——与 FCL 的 AboutPage 一致。
     */
    private inner class AboutAdapter(
        private val onLinkClick: (Int) -> Unit
    ) : RecyclerView.Adapter<AboutAdapter.Holder>() {

        private val itemCountInternal: Int get() = links.size + 1

        override fun getItemCount(): Int = itemCountInternal

        override fun getItemViewType(position: Int): Int =
            if (position == 0) TYPE_DESC else TYPE_LINK

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val inflater = LayoutInflater.from(parent.context)
            val view = if (viewType == TYPE_DESC) {
                ItemDshAboutDescBinding.inflate(inflater, parent, false).root
            } else {
                ItemDshAboutBinding.inflate(inflater, parent, false).root
            }
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.itemView.setBackgroundResource(
                when (position) {
                    0 -> R.drawable.bg_item_rounded
                    1 -> R.drawable.bg_item_rounded_top
                    itemCountInternal - 1 -> R.drawable.bg_item_rounded_bottom
                    else -> R.drawable.bg_item_rounded_middle
                }
            )
            ThemeEngine.getInstance().unregisterEvent(holder.itemView)
            ThemeEngine.getInstance().registerEvent(holder.itemView) {
                holder.itemView.backgroundTintList =
                    ColorStateList.valueOf(ThemeEngine.getInstance().getTheme().ltColor)
            }

            if (position == 0) {
                holder.itemView.setOnClickListener(null)
                ItemDshAboutDescBinding.bind(holder.itemView)
                    .title.setText(context.getString(R.string.dsh_about_desc, BuildConfig.VERSION_NAME))
            } else {
                holder.itemView.setOnClickListener { onLinkClick(position - 1) }
                ItemDshAboutBinding.bind(holder.itemView)
                    .title.setText(links[position - 1].titleRes)
            }
        }

        /** 供间距装饰器判断：链接行之间留 1dp 缝（绘制分割线）；说明行与链接组之间用默认间距 */
        fun isNextInSameGroup(position: Int): Boolean =
            position > 0 && position + 1 < itemCountInternal

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView)
    }

    companion object {
        private const val TYPE_DESC = 0
        private const val TYPE_LINK = 1
    }
}
