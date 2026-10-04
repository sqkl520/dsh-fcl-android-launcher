package com.dsh.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.dsh.core.DshVersionListItem
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ItemDshVersionBinding
import com.mio.util.AnimUtil
import com.tungsten.fcllibrary.component.theme.ThemeEngine

/**
 * 下载页版本列表 Adapter。已安装的版本显示"已安装"角标、隐藏安装按钮。
 *
 * ## 本次改造
 * - [DiffUtil] 替代全量重绘（列表有 20+ 项，每次刷新都重建会很跳）。
 * - 正在安装的版本按钮变成"安装中…"并禁用：原来连点会创建多个实例、并发跑多个 npm。
 */
class DshVersionAdapter(
    private val onInstall: (DshVersionListItem) -> Unit
) : RecyclerView.Adapter<DshVersionAdapter.VH>() {

    private var items: List<DshVersionListItem> = emptyList()
    private var installingVersions: Set<String> = emptySet()

    fun submit(list: List<DshVersionListItem>) {
        val newItems = ArrayList(list)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                items[oldPos].version == newItems[newPos].version

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                items[oldPos] == newItems[newPos]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    /** 正在安装的版本集合（按钮态） */
    fun submitInstalling(versions: Set<String>) {
        installingVersions = versions
        notifyItemRangeChanged(0, itemCount)
    }

    class VH(val binding: ItemDshVersionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDshVersionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        val ctx = b.root.context
        b.version.text = item.version

        if (item.tag != null) {
            b.tag.visibility = View.VISIBLE
            b.tag.text = item.tag
        } else {
            b.tag.visibility = View.GONE
        }

        // 体积文本：tarball 大小。是否另注明"安装后占用"取决于**这个版本要不要下载**：
        // 命中 rootfs 预装版本时点安装不下载任何东西，写"安装后约 500MB"会误导。
        val sizeLine = if (item.preinstalled) {
            ctx.getString(
                R.string.dsh_version_size, item.sizeText
            ) + " · " + ctx.getString(R.string.dsh_version_bundled)
        } else {
            ctx.getString(
                R.string.dsh_version_size, item.sizeText
            ) + " · " + ctx.getString(R.string.dsh_version_after_install)
        }
        b.size.text = sizeLine

        val installing = installingVersions.contains(item.version)
        // ★ 每次绑定都先清掉旧监听：RecyclerView 复用 ViewHolder 时，若不清理，
        //   点到的可能是**上一个 item 的版本**（错位安装）
        b.btnInstall.setOnClickListener(null)
        when {
            installing -> {
                b.installedBadge.visibility = View.VISIBLE
                b.installedBadge.text = ctx.getString(R.string.dsh_state_installing)
                b.installedBadge.setTextColor(0xFF888888.toInt())
                // 安装中：按钮禁用而不是仅隐藏（隐藏后若列表刷新出错会又冒出来）
                b.btnInstall.visibility = View.GONE
                b.btnInstall.isEnabled = false
            }
            item.installed -> {
                b.installedBadge.visibility = View.VISIBLE
                b.installedBadge.text = ctx.getString(R.string.dsh_installed)
                b.installedBadge.setTextColor(0xFF4CAF50.toInt())
                b.btnInstall.visibility = View.GONE
                b.btnInstall.isEnabled = false
            }
            else -> {
                b.installedBadge.visibility = View.GONE
                b.btnInstall.visibility = View.VISIBLE
                b.btnInstall.isEnabled = true
                b.btnInstall.setOnClickListener { onInstall(item) }
            }
        }

        // 入场动画（FCL 同款：RemoteVersionListAdapter 在 onBindViewHolder 末尾调
        // AnimUtil.playTranslationX，时长随「动画速度」设置走）。
        // 注意这会带来 FCL 一样的观感：列表整体重绑时（切"显示预览版"/刷新）所有行一起滑入。
        AnimUtil.playTranslationX(
            b.root,
            ThemeEngine.getInstance().getTheme().animationSpeed * 30L,
            -100f,
            0f
        ).start()
    }

    override fun getItemCount(): Int = items.size
}
