package com.dsh.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mio.dialog.ItemSelectionDialog
import com.mio.util.AnimUtil
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.dsh.core.DshInstance
import com.dsh.core.DshInstaller
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ItemDshInstanceBinding

/**
 * 实例列表 Adapter。用 viewBinding（FCL 已开启 buildFeatures.viewBinding）。
 *
 * ## 本次改造
 * - 用 [DiffUtil] 替代 `notifyDataSetChanged()`：安装过程中状态每秒都在变，全量重绘会让
 *   列表闪烁、按钮点击被吞。
 * - 每行新增：安装进度条/失败原因、以及「更多」菜单（设置 / 日志 / 重新安装 / 删除）。
 *   原来实例一旦装坏就只剩"删除"一条路。
 * - 删除中的行显示"删除中"并禁用所有操作，避免用户在异步删除过程中重复点击。
 * - **行的左滑菜单整层删掉**（连同行布局里那层 `com.mio.ui.widget.SwipeMenuLayout`）：
 *   卡片的按压缩放（`anim_scale` 缩到 0.9）会把垫在它下面的菜单层露出一角，看起来像
 *   "卡片破了"；而菜单里那两件事（设置 / 删除）「更多」里都有 —— 同一件事两个入口。
 *   控件类本身仍留在 FCL 基座里，这里只是不再使用它。
 * - **「更多」从系统 `PopupMenu` 换成 FCL 自己的 [ItemSelectionDialog]**：`PopupMenu` 走
 *   AppCompat 的 theme overlay，与 FCL 的 `dialog_background` / 主题色体系毫无关系，
 *   弹出来的东西和整个界面不是一套（真机反馈："风格割裂非常严重"）。选项对话框
 *   （`com.mio.dialog.ItemSelectionDialog`）是设置页「语言 / 主题模式」用的同一个，
 *   风格天然一致：条目高亮主题色、条目多时窗口自己滚动。
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
        // 安装失败的实例：主按钮变成「重试」（直接重装同版本），
        // 否则用户只能在「更多 → 重装」里找，或者再点一次下载——后者正是"堆一堆损坏实例"的来源之一
        val canRetry = inst.state == DshInstance.State.BROKEN
        b.btnStart.isEnabled = !busy && (isRunning || inst.state == DshInstance.State.READY || canRetry)

        // ===== 主操作（图标 + 文字）=====
        // 布局里 btn_start 是"clickable 容器 + 图标 + 文字"（照外壳右面板的启动键形态），
        // 所以这里三样都要分别维护：容器的点击、图标、文字。
        // ★ 文案来源没变，仍是那三个 R.string.dsh_action_* —— 只是落到内部的 btn_start_text 上。
        val actionRes = when {
            isRunning -> R.string.dsh_action_stop
            canRetry -> R.string.dsh_action_retry
            else -> R.string.dsh_action_start
        }
        b.btnStartText.setText(actionRes)
        b.btnStartIcon.setBackgroundResource(
            when {
                isRunning -> R.drawable.ic_baseline_close_24
                canRetry -> R.drawable.ic_baseline_refresh_24
                else -> R.drawable.ic_start
            }
        )
        // ★ FCLImageView 的着色发生在**主题刷新时**（use_theme_color → getBackground().setTint(color2)），
        //   运行期换背景图不会自动带上主题色 —— 新图标会保持 vector 自带的静态 tint（这几个 vector
        //   自带 darker_gray），在列表里就是一块不属于主题的灰。
        //   所以换完立刻补一次上色；之后主题切换时控件自己的回调会继续维持。
        //   用 getColor2()（带亮暗判断）而不是原始 .color2：后者不分模式，暗色下会把图标染成黑的。
        b.btnStartIcon.background?.setTint(ThemeEngine.getInstance().getTheme().getColor2())

        b.btnStart.setOnClickListener {
            when {
                isRunning -> onStop(inst)
                canRetry -> onReinstall(inst)
                else -> onStart(inst)
            }
        }

        // ===== 「更多」：FCL 自己的选项对话框 =====
        // ★ 不再用 android.widget.PopupMenu：它走 AppCompat 的 theme overlay，弹出来的是一套
        //   系统菜单（背景、圆角、条目样式、高亮色）—— 与 FCL 的 dialog_background / 主题色
        //   体系毫无关系。真机反馈"浮窗风格和整个 FCL 割裂非常严重"说的就是它。
        //   改用 com.mio.dialog.ItemSelectionDialog：设置页的「语言 / 主题模式」、日志页的
        //   「级别筛选」用的都是它，条目高亮主题色、条目多时窗口自己滚动，风格天然一致。
        //   也不用 FCLAlertDialog：那是"确认 / 警告"型（图标 + 一段正文 + 正负按钮），
        //   用来列 3~4 个并列选项会多出一段没话找话的正文，而且它没有"选中项"的概念。
        //
        // ★ 弹窗的 Context 必须是 Activity：ItemSelectionDialog 继承 FCLDialog
        //   （AppCompatDialog），要拿宿主的主题去解析自己的 window（dialog_background、
        //   全屏沉浸标志都在 FCLDialog 里设置）。本项目的列表行 context 本来就是 FCLActivity
        //   —— 页面布局由 FCLBaseUI 用 Activity 的 LayoutInflater 展开，卡片根部（RecyclerView
        //   的 itemView）拿到的 context 就是那个 Activity，所以**不需要**给 Adapter 多要一个
        //   activity 参数。findActivity() 再沿 ContextWrapper 解包一层只是兜底：将来若有人
        //   给列表套一层 ContextThemeWrapper，弹窗仍能落到 Activity 上，而不是静默弹不出来。
        b.btnMore.isEnabled = !busy
        b.btnMore.setOnClickListener {
            val dialogContext = findActivity(ctx) ?: return@setOnClickListener
            // 清单在**点击时**才算：它随实例状态而变（见 moreActions）；
            // 这一刻算出来的那一份，同时喂给对话框的条目和回调里的动作分发。
            val actions = moreActions(inst)
            ItemSelectionDialog(
                dialogContext,
                // 标题用实例名：这是"这组选项作用在谁身上"的唯一上下文 ——
                // 列表里可能有好几个实例，不写标题就只能靠行位置去猜。
                inst.name,
                actions.map { dialogContext.getString(it.label) },
                // small = true：这里只有 3~4 条，条目少时窗口自动放大到屏幕 1/3 以上，
                // 免得出现一个又小又飘的框（设置页那两个选择器传的也是 true）
                true,
                // -1 = 不高亮任何一项：这是"挑一个动作"，不是"选当前的值"——
                // 高亮某一项会让人以为它已经是选中态（设置页那两个选择器才需要高亮）
                -1
            ) { index, _ ->
                actions[index].run()
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

        // ===== 入场动画（FCL 同款：AnimationUtil.playTranslationX，时长随"动画速度"设置）=====
        AnimUtil.playTranslationX(
            b.root,
            ThemeEngine.getInstance().getTheme().animationSpeed * 30L,
            -100f,
            0f
        ).start()
    }

    override fun getItemCount(): Int = items.size

    /**
     * 「更多」里的一项：文案资源 + 点击后要做的事。
     *
     * 用一个小类把"文案"和"动作"绑在一起，而不是两张平行的列表（`List<Int>` 配
     * `List<() -> Unit>`）：两张表的长度与顺序一旦对不上（条件项插到中间时最容易发生），
     * 点「日志」会去执行「删除」—— 编译期不报错，运行期也不崩，只是删错了东西。
     * 绑在一起后，条目、文案、动作是同一个对象，不存在错位的可能。
     */
    private class MoreAction(@StringRes val label: Int, val run: () -> Unit)

    /**
     * 这一帧「更多」实际有哪几项（顺序：主操作在上、破坏性动作在下，照 FCL 的行内动作排法）。
     *
     * ★ 为什么要单独建这张表，而不是在回调里 `when (index)`：
     *   清单是**动态**的 —— 实例是 READY 时没有「重新安装」那一项（见下），
     *   于是同一张菜单里的索引含义随实例状态而变：
     *     非 READY：(0 设置, 1 日志, 2 重装, 3 删除)
     *     READY    ：(0 设置, 1 日志,        2 删除)
     *   写死 `when (index)` 的话，READY 的行点第 2 项会落到「重新安装」分支 —— 而用户
     *   点的是「删除」。所以这里先把清单算出来，回调再按**同一个列表**取项：
     *   对话框里画出来的条目和这里取到的动作出自同一次调用，索引天然对齐。
     */
    private fun moreActions(inst: DshInstance): List<MoreAction> = buildList {
        add(MoreAction(R.string.dsh_action_settings) { onOpenSettings(inst) })
        add(MoreAction(R.string.dsh_action_logs) { onOpenLogs(inst) })
        // 出现条件沿用改造前的 PopupMenu：READY 的实例不提供「重新安装」——
        // 它的主按钮已经能启动它，重装等于让用户把好端端的环境推倒重来。
        if (inst.state != DshInstance.State.READY) {
            add(MoreAction(R.string.dsh_action_reinstall) { onReinstall(inst) })
        }
        add(MoreAction(R.string.dsh_action_delete) { onDelete(inst) })
    }

    /**
     * 取行 context 所在的 Activity；解包不到时返回 null（调用方直接不弹）。
     *
     * ItemSelectionDialog 是 FCLDialog（AppCompatDialog）的子类，弹窗要按宿主主题解析自己的
     * window（dialog_background、全屏沉浸标志都在 FCLDialog 里设）。本项目的列表行 context
     * 就是 FCLActivity —— 页面布局由 FCLBaseUI 用 Activity 的 LayoutInflater 展开，所以
     * `binding.root.context` 拿到的本来就是 Activity，**这里不需要**去契约外多要一个参数。
     * 再沿 ContextWrapper 解包一层只是兜底：将来若有人给列表套一层 ContextThemeWrapper，
     * 弹窗仍能落到 Activity 上，而不是静默地弹不出来。
     */
    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    companion object {
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    }
}
