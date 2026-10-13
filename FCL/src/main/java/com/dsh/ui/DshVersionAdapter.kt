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

    /**
     * 还欠一次入场动画的版本号（消费制，BL-121 / V4b 方案 A1"两 Adapter 动画去重"的版本页一份）。
     *
     * [submitInstalling] 的单行通知会让"安装中/已安装"翻转的那一行重绑 —— 重绑命中本集合
     * 以外的 id 时不再播入场动画（见 onBind 末尾），否则点一下安装、那一行自己又滑一次。
     * [submit] 时把「新出现 − 已见」并入（首次加载=全部新 id → 全列表滑入，现观感保留）。
     */
    private val pendingAnimIds = mutableSetOf<String>()

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
        // ★ BL-121：与实例卡同款的新 id 判定（消费制，见 [pendingAnimIds]）：
        //   "已见" = 提交前的旧 items ∪ 尚未消费的待播集合。必须在 items 替换**之前**算。
        pendingAnimIds += computeNewIds(newItems.map { it.version }, pendingAnimIds + items.map { it.version })
        pendingAnimIds.retainAll(newItems.map { it.version }.toSet())
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    /**
     * 正在安装的版本集合（按钮态）。
     *
     * ★ BL-121：不再 `notifyItemRangeChanged(0, itemCount)` —— 那条通知让**整页可见行**重绑、
     * 每行各自从 -100f 滑入（装开始/结束各一次全列表重滑，V4b-2 实证）。改为只通知
     * "归属发生变化"的行：[changedVersions] 对称差里的每个版本 → 求当前 index → 单行
     * notifyItemChanged。归属没变的行（如装 0.1.5-rc.2 时旁边十几行）一像素都不动。
     *
     * 边界说明（有意为之）：
     * - 差集里的版本若已不在当前列表（列表刷新把它过滤掉了，如关掉"预览版"的瞬间），
     *   `indexOfFirst` 返回 -1 → 跳过，不发多余通知；
     * - 这里逐版本求 index 而非按位置：列表顺序由 [submit] 的 DiffUtil 维护，位置随时可能变，
     *   版本号才是稳定标识（与 [submit] 用 version 做 diff key 同一口径）。
     */
    fun submitInstalling(versions: Set<String>) {
        val prev = installingVersions
        installingVersions = versions
        changedVersions(prev, versions).forEach { version ->
            val index = items.indexOfFirst { it.version == version }
            if (index >= 0) notifyItemChanged(index)
        }
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

        // 体积文本（BL-019 口径说明）：前半是 registry 包体解压体积（口径词"包体/package"
        // 由 dsh_version_size 文案承载），后半是"含依赖"的安装后估算（dsh_version_after_install）。
        // 命中 rootfs 预装版本时点安装不下载任何东西，后半改"已内置"分支（保留）。
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
        // ★ BL-121：只在"这一帧新出现"的版本上播（消费制，见 [pendingAnimIds]）——
        //   切"显示预览版"/刷新时列表整体重绑、全部是新 id，所有行一起滑入（FCL 同款观感保留）；
        //   而安装开始/结束时那一次单行重绑不再让该行重滑。
        if (pendingAnimIds.remove(item.version)) {
            AnimUtil.playTranslationX(
                b.root,
                ThemeEngine.getInstance().getTheme().animationSpeed * 30L,
                -100f,
                0f
            ).start()
        } else {
            // 复用/重绑的行可能停在上一次动画的中间态，归零一次（照任务区先例 DshInstancesUI :773-774）
            b.root.translationX = 0f
        }
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

/**
 * 两个安装集合之间"归属发生变化"的版本：对称差（[next]−[prev] 与 [prev]−[next] 的并）。
 *
 * BL-121：`submitInstalling` 据此逐版本定位行再发通知 —— 不再全范围 notifyItemRangeChanged。
 * 写成顶层纯函数（先例：DshInstanceAdapter.kt 顶部的 `isRowBusy`/`computeNewIds`，
 * 见其注释）：无类初始化负担，JVM 单测可直接调。
 */
fun changedVersions(prev: Set<String>, next: Set<String>): Set<String> =
    (prev + next) - (prev intersect next)
