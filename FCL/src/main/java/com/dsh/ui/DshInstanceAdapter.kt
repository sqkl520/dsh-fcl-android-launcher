package com.dsh.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.dsh.core.DshInstance
import com.dsh.core.DshInstaller
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.tungsten.fcl.R
import com.tungsten.fcl.databinding.ItemDshInstanceBinding

/**
 * 实例列表 Adapter。用 viewBinding（FCL 已开启 buildFeatures.viewBinding）。
 *
 * ## 本次改造
 * - 用 [DiffUtil] 替代 `notifyDataSetChanged()`：安装过程中状态每秒都在变，全量重绘会让
 *   列表闪烁、按钮点击被吞。
 * - 每行新增：安装进度条/失败原因、以及"更多"菜单（设置 / 日志 / 重新安装 / 删除）。
 *   原来实例一旦装坏就只剩"删除"一条路。
 * - 删除中的行显示"删除中"并禁用所有操作，避免用户在异步删除过程中重复点击。
 */
class DshInstanceAdapter(
    private val onStart: (DshInstance) -> Unit,
    private val onStop: (DshInstance) -> Unit,
    private val onOpenSettings: (DshInstance) -> Unit,
    private val onOpenLogs: (DshInstance) -> Unit,
    private val onReinstall: (DshInstance) -> Unit,
    private val onDelete: (DshInstance) -> Unit
) : RecyclerView.Adapter<DshInstanceAdapter.VH>() {

    private var items: List<DshInstance> = emptyList()
    private var runningId: String? = null
    private var deleting: Set<String> = emptySet()
    private var installStatus: Map<String, DshInstaller.InstallStatus> = emptyMap()

    /** 每行的体积缓存（key = id:版本:状态）。做成实例字段：Adapter 随界面销毁时一起被回收，
     *  不再像原来那样存在 companion 里当作全局静态缓存（删掉的实例会永久占着条目）。 */
    private val sizeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val sizePending = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun submit(list: List<DshInstance>, runningInstanceId: String?, deletingIds: Set<String> = emptySet()) {
        val newItems = ArrayList(list)
        val prevRunning = runningId
        val prevDeleting = deleting
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                items[oldPos].id == newItems[newPos].id

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                items[oldPos] == newItems[newPos]
        })
        items = newItems
        runningId = runningInstanceId
        deleting = deletingIds
        diff.dispatchUpdatesTo(this)

        // ★ DiffUtil 只比较"实例对象的内容"，而"是不是正在运行""是不是正在删除"是 Adapter 自己的
        // 字段（挂在 DshInstance 之外）。运行状态一变，实例列表本身没有任何变化，DiffUtil 就不会
        // 发出任何更新 —— 表现是：点了启动，行里的按钮还是"启动"（点不动，也无法从这里停止），
        // 删除中的行也不显示"删除中"。所以这两类字段变化必须显式重绑受影响的行。
        val affected = mutableSetOf<Int>()
        if (prevRunning != runningId) {
            addIndex(items, prevRunning, affected)
            addIndex(items, runningId, affected)
        }
        if (prevDeleting != deleting) {
            (prevDeleting + deleting).forEach { id -> addIndex(items, id, affected) }
        }
        affected.forEach { notifyItemChanged(it) }
    }

    private fun addIndex(list: List<DshInstance>, id: String?, out: MutableSet<Int>) {
        if (id == null) return
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) out += idx
    }

    /** 安装状态变化：只刷新受影响的行 */
    fun submitInstallStatus(statuses: Map<String, DshInstaller.InstallStatus>) {
        installStatus = statuses
        items.forEachIndexed { index, inst ->
            if (statuses.containsKey(inst.id) || inst.state == DshInstance.State.INSTALLING) {
                notifyItemChanged(index)
            }
        }
    }

    class VH(val binding: ItemDshInstanceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDshInstanceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val inst = items[position]
        val b = holder.binding
        val ctx = b.root.context
        b.name.text = inst.name

        val isRunning = inst.id == runningId
        val isDeleting = deleting.contains(inst.id)
        val status = installStatus[inst.id]

        val stateText = when (inst.state) {
            DshInstance.State.NOT_INSTALLED -> ctx.getString(R.string.dsh_state_not_installed)
            DshInstance.State.INSTALLING -> ctx.getString(R.string.dsh_state_installing)
            DshInstance.State.READY -> ctx.getString(R.string.dsh_state_ready)
            DshInstance.State.BROKEN -> ctx.getString(R.string.dsh_state_broken)
        }
        val portText = if (inst.port > 0) inst.port.toString()
        else ctx.getString(R.string.dsh_port_auto)
        b.subtitle.text = ctx.getString(
            R.string.dsh_instance_subtitle,
            inst.dshVersion ?: "-", portText, stateText
        )

        // 安装进度：有阶段文案就显示进度条 + 文字
        if (status != null && status.running) {
            b.installProgress.visibility = View.VISIBLE
            b.installStage.visibility = View.VISIBLE
            b.installStage.text = status.stage
            val fraction = status.fraction
            if (fraction != null) {
                b.installProgress.isIndeterminate = false
                b.installProgress.progress = (fraction * 100).toInt()
            } else {
                b.installProgress.isIndeterminate = true
            }
        } else {
            b.installProgress.visibility = View.GONE
            b.installStage.visibility = View.GONE
        }

        // 失败原因（BROKEN 的实例，或未安装但留有原因，如"已取消"）
        val error = inst.lastError ?: status?.error
        val showError = !isDeleting && error != null &&
            (inst.state == DshInstance.State.BROKEN ||
                inst.state == DshInstance.State.NOT_INSTALLED)
        if (showError) {
            b.errorText.visibility = View.VISIBLE
            b.errorText.text = error
        } else {
            b.errorText.visibility = View.GONE
        }

        // 按钮可用性
        val busy = isDeleting || (status?.running == true)
        b.btnStart.isEnabled = !busy && (isRunning || inst.state == DshInstance.State.READY)
        b.btnStart.text = ctx.getString(
            if (isRunning) R.string.dsh_action_stop else R.string.dsh_action_start
        )
        b.btnStart.setOnClickListener { if (isRunning) onStop(inst) else onStart(inst) }

        b.btnMore.isEnabled = !busy
        b.btnMore.setOnClickListener { anchor ->
            PopupMenu(ctx, anchor).apply {
                menu.add(0, MENU_SETTINGS, 0, R.string.dsh_action_settings)
                menu.add(0, MENU_LOGS, 1, R.string.dsh_action_logs)
                if (inst.state != DshInstance.State.READY) {
                    menu.add(0, MENU_REINSTALL, 2, R.string.dsh_action_reinstall)
                }
                menu.add(0, MENU_DELETE, 3, R.string.dsh_action_delete)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        MENU_SETTINGS -> onOpenSettings(inst)
                        MENU_LOGS -> onOpenLogs(inst)
                        MENU_REINSTALL -> onReinstall(inst)
                        MENU_DELETE -> onDelete(inst)
                    }
                    true
                }
            }.show()
        }

        if (isDeleting) {
            b.subtitle.text = ctx.getString(R.string.dsh_state_deleting)
        }

        // 体积：带缓存、按 (版本,状态) 失效；绝不每次绑定都去 walk 300MB 目录
        val sizeKey = "${inst.id}:${inst.dshVersion}:${inst.state}"
        val cachedSize = sizeCache[sizeKey]
        if (cachedSize != null) {
            b.size.text = DshPaths.formatSize(cachedSize)
        } else {
            b.size.text = ctx.getString(R.string.dsh_size_calculating)
            if (sizePending.add(sizeKey)) {
                DshInstances.diskUsageAsync(inst.id) { bytes ->
                    sizeCache[sizeKey] = bytes
                    sizePending.remove(sizeKey)
                    mainHandler.post {
                        val idx = items.indexOfFirst { it.id == inst.id }
                        if (idx >= 0) notifyItemChanged(idx)
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        private const val MENU_SETTINGS = 1
        private const val MENU_LOGS = 2
        private const val MENU_REINSTALL = 3
        private const val MENU_DELETE = 4

        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    }
}
