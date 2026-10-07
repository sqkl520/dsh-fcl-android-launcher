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
import com.tungsten.fcllibrary.component.theme.ThemeData
import com.tungsten.fcllibrary.component.theme.ThemeEngine

/**
 * 下载页版本列表 Adapter。已安装的版本显示"已安装"角标、隐藏安装按钮。
 *
 * ## 本次改造
 * - [DiffUtil] 替代全量重绘（列表有 20+ 项，每次刷新都重建会很跳）。
 * - 正在安装的版本按钮变成"安装中…"并禁用：原来连点会创建多个实例、并发跑多个 npm。
 * - 行内动作改为**图标按钮**（布局侧），这里只负责状态与取色。
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

    class VH(val binding: ItemDshVersionBinding) : RecyclerView.ViewHolder(binding.root) {
        /**
         * 状态角标的取色规则（每次绑定按当前 item 改写）。
         *
         * 存"规则"而不是算好的颜色值：主题切换后要按**当时**的主题重算，
         * 只存颜色值的话重算时已无从知道这一行是"安装中"还是"已安装"。
         */
        var badgeColor: (ThemeData) -> Int = { it.autoHintTint }
    }

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
                // 安装中是**过渡态**：用半透明提示色（autoHintTint）。它是"与主色对比的黑/白"再压半透明，
                // 亮暗两种模式下都跟卡片底保持对比；原先的 0xFF888888 是中性硬编码灰，与主题色无关，
                // 主题色一变（取色/换背景）它就与背景脱钩。
                holder.badgeColor = { it.autoHintTint }
                // 安装中：按钮禁用而不是仅隐藏（隐藏后若列表刷新出错会又冒出来）
                b.btnInstall.visibility = View.GONE
                b.btnInstall.isEnabled = false
            }
            item.installed -> {
                b.installedBadge.visibility = View.VISIBLE
                b.installedBadge.text = ctx.getString(R.string.dsh_installed)
                // 已安装是**终态**，给状态色：次要色 getColor2()。必须走 getColor2()（带亮暗判断的取值器）——
                // 原始 color2 亮色下是纯黑、暗色下会把文字染成与卡片底同色（看不见）。
                // 原先的 0xFF4CAF50 是 Material 绿，不属于 FCL 任何色板，切主题时也不跟着变。
                holder.badgeColor = { it.getColor2() }
                b.btnInstall.visibility = View.GONE
                b.btnInstall.isEnabled = false
            }
            else -> {
                b.installedBadge.visibility = View.GONE
                // 角标隐藏时不取色；仍给一个安全默认，避免行被复用时残留上一行的取色规则
                holder.badgeColor = { it.autoHintTint }
                b.btnInstall.visibility = View.VISIBLE
                b.btnInstall.isEnabled = true
                b.btnInstall.setOnClickListener { onInstall(item) }
            }
        }

        // ★ 角标颜色是**代码**设的（布局里没有写死颜色），所以必须注册主题刷新：
        //   否则切换主题/改主题色后，已经绑定好的行会一直保持旧颜色。
        //   registerEvent 在注册时立即执行一次，因此这里不再手动 setTextColor（否则是重复劳动）。
        ThemeEngine.getInstance().registerEvent(holder.itemView) {
            b.installedBadge.setTextColor(holder.badgeColor(ThemeEngine.getInstance().getTheme()))
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

    /**
     * 行被回收时注销主题回调。
     *
     * ThemeEngine 的回调表是 `WeakHashMap<View, Runnable>`：同一个 View 重复注册只是**替换**表项
     * （不会堆积），但表里的 Runnable 反向持有这个 View，表项并不会随弱键自动消失；
     * 行从这个池子里被丢掉（换了 adapter / 列表整体重建）后就再没人会去清它，所以这里自己注销。
     */
    override fun onViewRecycled(holder: VH) {
        ThemeEngine.getInstance().unregisterEvent(holder.itemView)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size
}
