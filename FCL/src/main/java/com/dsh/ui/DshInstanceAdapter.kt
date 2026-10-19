package com.dsh.ui

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
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
 *
 * ## fix2 改造（BL-120/081/082/085/122，V4b 设计方案）
 * - **入场动画去重（BL-120 A1）**：滑入只对"这一帧新出现的实例 id"播一次（[pendingAnimIds]，
 *   消费制，照任务区 freshIds 先例）。安装进度/体积/运行态引起的重绑不再"重开一个块"。
 * - **安装进度 payload 局部刷新 + 补间（BL-120 A2 / BL-122）**：`submitInstallStatus` 带
 *   [PAYLOAD_INSTALL_STATUS] 通知，三参 onBind 只刷阶段文案 + 进度条；进度值由
 *   [playProgress] 从当前值平滑补到目标（不再硬赋值跳变）。
 * - **BL-081**：NOT_INSTALLED（取消/降级后）的主按钮改「去安装」→ 切「版本」页（[onOpenVersions]）。
 * - **BL-082**：整卡可点挂上真实动作 —— 打开该实例详情（[onOpenDetail]）。
 * - **BL-085**：失败原因色在绑定期间挂 `registerEvent(errorText)` 主题回调（注册即执行、
 *   行回收注销）—— 亮暗切换时按当下 uiMode 重取 `@color/dsh_log_error`，不再依赖
 *   inflate 期那一次解析（与版本页 installed_badge、任务区 themeRebind 同一做法）。
 */
class DshInstanceAdapter(
    private val onStart: (DshInstance) -> Unit,
    private val onStop: (DshInstance) -> Unit,
    private val onOpenSettings: (DshInstance) -> Unit,
    private val onOpenLogs: (DshInstance) -> Unit,
    private val onReinstall: (DshInstance) -> Unit,
    private val onDelete: (DshInstance) -> Unit,
    /** 切到「版本」tab（去装一个 dsh 版本）。BL-081：NOT_INSTALLED 态主按钮的动作。
     *  调用方（DshInstancesUI）给 `{ host.switchTab(DshShellHost.TAB_VERSIONS) }`——
     *  同文件重装确认框的「下载」按钮就是这个调用模式（见其 :574 附近）。 */
    private val onOpenVersions: () -> Unit,
    /** 打开某实例的详情页（BL-082：整卡可点）。调用方给 `{ id -> onOpenDetail(id, TAB_RUN) }`
     *  （即 `showInstanceDetailAt` 的既有能力）。 */
    private val onOpenDetail: (String) -> Unit
) : RecyclerView.Adapter<DshInstanceAdapter.VH>() {

    private var items: List<DshInstance> = emptyList()
    private var runningId: String? = null
    private var deleting: Set<String> = emptySet()
    private var installStatus: Map<String, DshInstaller.InstallStatus> = emptyMap()

    /**
     * 还欠一次入场动画的实例 id（消费制，BL-120 / V4b 方案 A1）。
     *
     * DiffUtil 的 `notifyItemChanged`（安装进度、体积回调、运行/删除态变化）也会触发 onBind，
     * 但那些都是**同一行原地更新**，不该再播一次 -100f→0f 的滑入（用户原话"进度条每次一跳，
     * 块就重开一次"）。所以入场动画只在"这一帧新出现的 id"上播：`submit` 时把
     * 「新出现 − 已见」并入本集合，onBind 里命中即播并**消费掉**（remove），
     * 之后同行的任何重绑都不再命中 → 不重播。
     *
     * 与任务区 freshIds 先例（DshInstancesUI.kt `animatedTaskIds`）同构，差别只在
     * 那边每帧整体替换集合、这边是消费制：实例 id 在实例被删掉前一直"已见"，
     * 不需要每帧重算。
     */
    private val pendingAnimIds = mutableSetOf<String>()

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
        // ★ BL-120：把"这一帧新出现、且从没欠过动画"的 id 并入待播集合（消费制，见 [pendingAnimIds]）。
        //   "已见" = 提交前的旧 items ∪ 尚未消费掉的 pendingAnimIds（并集是保守写法：
        //   待播项在当前流程里恒同时出现在旧 items 里，并上它不改变结果，但判定因此不依赖
        //   这个隐含不变式 —— 将来若有人给 items 做过滤/重建，行为依然成立）。
        //   必须在 dispatchUpdatesTo **之前**算：dispatch 返回后 RecyclerView 随时会驱动绑定
        //   （下一次布局趟），届时 onBind 就要读到就位的集合。
        pendingAnimIds += computeNewIds(newItems.map { it.id }, pendingAnimIds + items.map { it.id })
        // 已经被删掉的实例不用再欠动画（它在离场前没被绑定消费的话，条目留在集合里只会白占）。
        pendingAnimIds.retainAll(newItems.map { it.id }.toSet())

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

    /** 安装状态变化：只刷新受影响的行。
     *
     *  ★ BL-120 / V4b 方案 A2：带 **payload** 通知。无 payload 的 change 在 DefaultItemAnimator
     *  下走"双视图交叉淡化"（`canReuseUpdatedViewHolder=false`），进度条 ViewHolder 被整个换掉、
     *  与入场动画叠加就是"重开一个块"；payload 非空则同 VH 复用（`canReuseUpdatedViewHolder=true`，
     *  recyclerview-1.3.1 字节码已证），走三参 `onBindViewHolder` 的局部刷新 —— 进度条对象连续，
     *  也才谈得上"从当前值补间"（BL-122）。
     *
     *  命中条件 = 新集合含 id ∪ **旧集合含 id**（状态刚被清掉的行：payload 分支自带
     *  "status 为 null → 收起进度条"的收敛，见 [applyInstallStatusText]）∪ INSTALLING 态
     *  （BL-029 空窗期兜底）。单看"新集合含 id"缺"旧集合含 id"一项，安装收尾那一帧若没有
     *  别的重绑来兜底，进度条会留在屏上。 */
    fun submitInstallStatus(statuses: Map<String, DshInstaller.InstallStatus>) {
        val prev = installStatus
        installStatus = statuses
        items.forEachIndexed { index, inst ->
            if (statuses.containsKey(inst.id) || prev.containsKey(inst.id) ||
                inst.state == DshInstance.State.INSTALLING
            ) {
                notifyItemChanged(index, PAYLOAD_INSTALL_STATUS)
            }
        }
    }

    class VH(val binding: ItemDshInstanceBinding) : RecyclerView.ViewHolder(binding.root) {
        /** 进度条补间动画（BL-122）。重绑/切 indeterminate 前必须 cancel，见 [playProgress]。 */
        var progressAnim: ObjectAnimator? = null

        /** 这个 VH 当前画的是哪个实例：行被别的实例复用后，进度条残留值不可当"当前值"（BL-122）。 */
        var boundId: String? = null
    }

    /**
     * 三参 onBind（change payload 路径，BL-120 A2）。
     *
     * `payloads` 非空 = RecyclerView 正在复用同一 ViewHolder 做局部刷新（`canReuseUpdatedViewHolder`
     * 对非空 payload 恒为 true，recyclerview-1.3.1 字节码实证），**不能**在这里做完整绑定：
     * 只认自己的 payload，认不出就走完整绑定 —— 将来新增 payload 忘了在这里登记，会得到
     * "刷了但没全刷"的静默错误，比多绑一次昂贵得多。
     */
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        val inst = items[position]
        // ★ 两个条件缺一不可：
        //   ① payload 认识 → 只做局部刷新；
        //   ② holder.boundId == inst.id —— **这一 VH 上次画的确实是这个实例**。payload 通知
        //      在滚动/插入与更新合并进同一次布局时，可能落到一个还没画过这行的回收 VH 上，
        //      那种 VH 的其余控件（名称/按钮/体积）还是上一个实例的内容 —— 只刷进度就是
        //      一行张冠李戴的旧控件配新进度，必须退回完整绑定。
        //   认不出的 payload 同样走完整绑定：新 payload 忘在这里登记时，宁可多绑一次，
        //   也不能得到"刷了但没全刷"的静默错误。
        if (payloads.any { it === PAYLOAD_INSTALL_STATUS } && holder.boundId == inst.id) {
            applyInstallStatusText(holder, inst)
            return
        }
        onBindViewHolder(holder, position)
    }

    /**
     * payload 局部刷新：安装 UI（阶段文案 + 进度条）的完整收敛（BL-120 A2 / BL-122）。
     *
     * ★ 必须**自带全部状态**（显示/隐藏/模式切换），不能依赖"稍后会有一次完整重绑来清屏"——
     *   安装收尾时 statuses 清条后，那一行可能不再收到任何通知（见 submitInstallStatus 的
     *   通知条件），进度条会留在屏幕上。所以这里与完整绑定的进度块同形态：能更新、也能收掉。
     *
     * 进度条走 [playProgress] 从**当前值**补到目标 —— payload 路径的同 VH 复用保证了
     * `b.installProgress.progress` 还是上一帧画出的那个值，补间因此从"现在屏幕上停在哪"
     * 出发；若是刚从隐藏切回显示（值已被归零），自然从 0 起。fraction 为 null（无法估算）
     * 时切 indeterminate 前先 cancel —— 带着一个未完成的 determinate 补间切过去会闪。
     */
    private fun applyInstallStatusText(holder: VH, inst: DshInstance) {
        val b = holder.binding
        val status = installStatus[inst.id]
        if (status == null || !status.running) {
            // 安装结束（statuses 清条）或状态流空窗期：收起安装 UI（同完整绑定的 else 分支）。
            holder.progressAnim?.cancel()
            b.installProgress.isIndeterminate = false
            b.installProgress.progress = 0
            b.installProgress.visibility = View.GONE
            b.installStage.visibility = View.GONE
            return
        }
        b.installProgress.visibility = View.VISIBLE
        b.installStage.visibility = View.VISIBLE
        // R20：阶段文案 + 实时活动行（"正在抓哪个包 · 74ms"）。活动行在**同一行**的尾部拼接
        // 而不是新开一行：布局零改动、卡片高度稳定（下载期每秒都在变的是这行文字本身 ——
        // 视觉上就是"在滚动"，正是要治的"死机感"）。activity 为 null 时退化为纯阶段文案。
        b.installStage.text = status.activity?.let { "${status.stage} · $it" } ?: status.stage
        val fraction = status.fraction
        if (fraction != null) {
            if (b.installProgress.isIndeterminate) {
                holder.progressAnim?.cancel()
                b.installProgress.isIndeterminate = false
            }
            val to = (fraction * 100).toInt().coerceIn(0, 100)
            playProgress(holder, b.installProgress, b.installProgress.progress, to)
        } else {
            holder.progressAnim?.cancel()
            b.installProgress.isIndeterminate = true
        }
    }

    /**
     * 进度条从 [from] 平滑补到 [to]（BL-122，V4b 方案 B2）。
     *
     * 时长沿用 FCL 的动画标量 `theme.animationSpeed * 30L`（默认 8 → 240ms，与所有入场
     * 动画同一口径）；out-of-range 的 from 不防（ProgressBar 自己会夹）。同一 VH 上重开
     * 补间前先 cancel 旧的 —— 阶段阶梯值 0.05/0.10/0.15/0.20/0.40/0.90 相邻 tick 间隔
     * 大于补间时长时不会互相打断，小于时也从"现在停在哪"续上。
     */
    private fun playProgress(holder: VH, bar: ProgressBar, from: Int, to: Int) {
        holder.progressAnim?.cancel()
        holder.progressAnim = ObjectAnimator.ofInt(bar, "progress", from, to).apply {
            duration = ThemeEngine.getInstance().getTheme().animationSpeed * 30L
            start()
        }
    }

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

        // ★ BL-122：这一 VH 上次画的是不是同一个实例 —— 决定进度补间的起点
        //   （同一行续上"现在停在哪"，换到别行的 VH 从 0 起，见下面进度块）。
        val sameRow = holder.boundId == inst.id
        holder.boundId = inst.id

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
            // R20：同 payload 路径（活动行拼在阶段文案尾部，见 applyInstallStatusText）
            b.installStage.text = status.activity?.let { "${status.stage} · $it" } ?: status.stage
            val fraction = status.fraction
            if (fraction != null) {
                if (b.installProgress.isIndeterminate) {
                    holder.progressAnim?.cancel()
                    b.installProgress.isIndeterminate = false
                }
                // BL-122：完整绑定也要补间，起点 = 屏幕上现在停在哪。同一个实例的重绑（如体积
                // 回调触发的整行刷新）从当前值续上；换到别的实例的 VH（复用）从 0 起 ——
                // 那个残留值是上一行的，不能当本行的"当前值"。
                val from = if (sameRow) b.installProgress.progress else 0
                val to = (fraction * 100).toInt().coerceIn(0, 100)
                playProgress(holder, b.installProgress, from, to)
            } else {
                holder.progressAnim?.cancel()
                b.installProgress.isIndeterminate = true
            }
        } else {
            holder.progressAnim?.cancel()
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
        // ★ BL-085：error_text 的失败原因色 @color/dsh_log_error 是 inflate 期解析的
        //   （亮/暗两套 values 各一份），而亮暗切换不重建 Activity（configChanges 含 uiMode）
        //   —— 只靠 inflate 那一次，切到暗色后旧深红就压在深底上。照版本适配器对
        //   installed_badge 的同一做法：绑定期间挂主题回调（registerEvent 注册时立即执行
        //   一次，颜色即刻就位，无需另写 setTextColor），行回收时在 onViewRecycled 注销 ——
        //   切模式时不依赖任何重绑也立刻换色，下一次绑定再重新挂上（同一 View 重复注册
        //   只是替换表项，不堆积）。errorText 没有 auto_text_tint / use_theme_color，替换
        //   FCLTextView 构造时那条空跑的刷新不损失任何行为。取色用 b.root.context
        //   （values-night 自动命中；与任务区 DshInstancesUI 的 FAILED 行同一个 R.color，同源）。
        ThemeEngine.getInstance().registerEvent(b.errorText) {
            b.errorText.setTextColor(b.root.context.getColor(R.color.dsh_log_error))
        }

        // 按钮可用性
        // BL-029：判据必须含 inst.state==INSTALLING —— 状态流空窗期 status 为 null，
        // 那时仅看 status?.running 会误判"不忙"而短暂可点（见 isRowBusy）。
        val busy = isRowBusy(isDeleting, status?.running, inst.state)
        // 安装失败的实例：主按钮变成「重试」（直接重装同版本），
        // 否则用户只能在「更多 → 重装」里找，或者再点一次下载——后者正是"堆一堆损坏实例"的来源之一
        val canRetry = !busy && inst.state == DshInstance.State.BROKEN
        // BL-081：取消安装（或启动时 repair 降级）后实例回到 NOT_INSTALLED，此时「启动」按
        // isEnabled 判据不可点 —— 而 FCL 容器无 disabled 视觉（BL-020），就是一块"活外观死按钮"。
        // 改法：这一态不再冒充可启动，而是改文案「去安装」+ 下载图标，点击切到「版本」页去挑一个
        // 版本装（onOpenVersions）。这是死按钮唯一说得通的出路 —— B1-1 的建议方向也是它。
        val notInstalled = !busy && !isRunning && inst.state == DshInstance.State.NOT_INSTALLED
        // BL-162（R18）：busy 时从"整块 GONE"改为**状态词按钮**（恒可见、位置固定）——
        // 旧实现藏掉按钮会让卡片右侧整块消失、列表左右摆动（安装中/完成的瞬间跳动一次）；
        // 详情页运行段对"安装中"早就用的是"状态词 + 点击静默"（见 DshInstanceSettingPage.runRows），
        // 这里对齐同一形态：文案「安装中/删除中」，isEnabled=false（无按压反馈），点击静默。
        // 修正 BL-020 当时的取舍：那时没有状态词，GONE 是唯一诚实选项；现在文案本身说明了
        // "正在发生什么"，留着按钮位置反而更稳。
        val busyLabel = when {
            isDeleting -> R.string.dsh_state_deleting
            status?.running == true || inst.state == DshInstance.State.INSTALLING ->
                R.string.dsh_state_installing
            else -> null
        }
        b.btnStart.visibility = View.VISIBLE
        b.btnStart.isEnabled = !busy && (isRunning || notInstalled ||
            inst.state == DshInstance.State.READY || canRetry)

        // ===== 主操作（图标 + 文字）=====
        // 布局里 btn_start 是"clickable 容器 + 图标 + 文字"（照外壳右面板的启动键形态），
        // 所以这里三样都要分别维护：容器的点击、图标、文字。
        // ★ 文案来源没变，仍是那组 R.string.dsh_action_* —— 只是落到内部的 btn_start_text 上。
        val actionRes = when {
            busyLabel != null -> busyLabel
            isRunning -> R.string.dsh_action_stop
            canRetry -> R.string.dsh_action_retry
            // BL-081：读 strings 确认过 —— dsh_action_download 的值是「下载/Download」（指向"下载"
            // 入口的词），与「去安装」语义不合；改用 dsh_action_install（「安装/Install/安裝」，
            // 三语言齐全，版本页安装按钮的 contentDescription 也是它）。精确的「去安装」措辞
            // 若编排要，建议新增独立 key，见报告 String 需求表。
            notInstalled -> R.string.dsh_action_install
            else -> R.string.dsh_action_start
        }
        b.btnStartText.setText(actionRes)
        b.btnStartIcon.setBackgroundResource(
            when {
                // BL-162：忙态图标与状态词同源（删除中用垃圾桶、安装中用下载箭头）
                busyLabel != null ->
                    if (isDeleting) R.drawable.ic_baseline_delete_24 else R.drawable.ic_baseline_download_24
                isRunning -> R.drawable.ic_baseline_close_24
                canRetry -> R.drawable.ic_baseline_refresh_24
                notInstalled -> R.drawable.ic_baseline_download_24
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
            // BL-162：忙态按钮只是"状态指示"，点击静默（与详情页安装中的口径一致）。
            // 显式挡一层：FCLButton 容器的 enabled 语义不保证与系统 View 完全一致。
            if (busy) return@setOnClickListener
            when {
                isRunning -> onStop(inst)
                canRetry -> onReinstall(inst)
                notInstalled -> onOpenVersions()
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
        // BL-020：btn_more 永不禁用 —— busy 时菜单仅留「日志」那一项（见 moreActions），
        // 安装中看日志是唯一入口；FCL 控件无 disabled 视觉，点不动还不如直接给能点的。
        b.btnMore.setOnClickListener {
            val dialogContext = findActivity(ctx) ?: return@setOnClickListener
            // 清单在**点击时**才算：它随实例状态而变（见 moreActions）；
            // 这一刻算出来的那一份，同时喂给对话框的条目和回调里的动作分发。
            val actions = moreActions(inst, busy)
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

        // ★ BL-082：根卡片是 clickable + 涟漪 + 按压缩放（布局 :37-41），此前却没有任何监听 ——
        //   用户按最大的点按目标得到完整的"按钮反馈"，然后什么都不发生（B1-2：FCL 同形态的
        //   档案行/远程版本行点卡即进对应页，用户合理地以为能打开详情）。这里把它兑现：
        //   整卡 → 打开该实例详情。用 clickable=false 的"去反馈"方案是次选 —— 卡片保留的是
        //   FCL 列表行的既有形态，改成"不可点"反而与同族行不一致。
        b.root.setOnClickListener { onOpenDetail(inst.id) }
        // 行被复用时根上没有"上一次的监听"残留问题（每次绑定都重设），点击动作引用的是
        // 本次绑定的 inst —— 与 btnStart/btnMore 同一套路。

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
        // ★ BL-120：只在"这一帧新出现"的实例上播（消费制，见 [pendingAnimIds]）。
        //   notifyItemChanged 引起的重绑（安装进度、体积回调、运行/删除态变化）不命中 —— 不再重滑。
        if (pendingAnimIds.remove(inst.id)) {
            AnimUtil.playTranslationX(
                b.root,
                ThemeEngine.getInstance().getTheme().animationSpeed * 30L,
                -100f,
                0f
            ).start()
        } else {
            // 复用/重绑的行可能停在上一次动画的中间态（新实例插在前面时，后面的行会换位重绑），
            // 归零一次，避免"没播动画的行却歪在一边"（归零写法照任务区先例 DshInstancesUI :773-774）。
            b.root.translationX = 0f
        }
    }

    override fun getItemCount(): Int = items.size

    /** 行被回收：停掉还没跑完的进度补间（BL-122）——继续跑也只是在给一个离屏 View 写属性；
     *  清掉绑定记录（复用时必须走完整绑定，payload 局部刷新不再放行）；注销 errorText 的
     *  主题回调（BL-085）。**必须以 holder 为单位清掉这三样**，漏掉哪样都是静默错误。 */
    override fun onViewRecycled(holder: VH) {
        holder.progressAnim?.cancel()
        holder.progressAnim = null
        holder.boundId = null
        ThemeEngine.getInstance().unregisterEvent(holder.binding.errorText)
        super.onViewRecycled(holder)
    }

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
     * BL-020：busy（安装中/删除中）时仅留「日志」—— 其它项（设置/重装/删除）在安装中
     * 点下去全是空操作或危险操作，而看日志是安装中唯一有意义的入口。
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
    private fun moreActions(inst: DshInstance, busy: Boolean = false): List<MoreAction> = buildList {
        // busy（安装中/删除中）时只留「日志」：安装中唯一有意义的入口
        if (busy) {
            add(MoreAction(R.string.dsh_action_logs) { onOpenLogs(inst) })
            return@buildList
        }
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

        /**
         * 安装状态 change payload（BL-120 A2）。
         *
         * payload 非空 → DefaultItemAnimator 走 `canReuseUpdatedViewHolder(vh, payloads)=true`
         * （recyclerview-1.3.1 字节码实证）→ **同一个 ViewHolder 原地刷新**，不做 change 双视图
         * 交叉淡化、也不播放入场动画 —— 这是"进度条对象连续、可从当前值补间"（BL-122）的前置条件；
         * 三参 onBind 据此只刷新 install_stage 文本 + install_progress 值。
         */
        val PAYLOAD_INSTALL_STATUS = Any()
    }
}

/**
 * 实例行是否 busy（安装中/删除中）。
 *
 * BL-029：判据必须含 `state==INSTALLING` —— 安装状态流的空窗期 `status` 为 null，
 * 那时仅看 `status?.running` 会误判"不忙"而短暂可点。
 *
 * 写成顶层函数：Adapter 自己的 companion 建 `Handler`，JVM 单测里类加载就崩；
 * 顶层函数无类初始化负担，单测可直接调。
 * `statusRunning` 只看 `InstallStatus.running`（`null` = 无安装状态条目），
 * `state` 是实例自己的状态字段 —— 两侧互相兜底，任何一侧说"在装"都算忙。
 */
fun isRowBusy(
    isDeleting: Boolean,
    statusRunning: Boolean?,
    state: DshInstance.State
): Boolean = isDeleting || statusRunning == true ||
    state == DshInstance.State.INSTALLING

/**
 * "这一帧新出现"的 id：出现在 [current]、却不在 [seen] 里的那些（BL-120 入场动画去重）。
 *
 * [seen] 由调用方拼出"已见"全集 = 提交前的旧 items ∪ 尚未消费的待播集合 —— 两个判据缺一不可：
 *   · 只减待播集合：播过并被消费掉的旧 id 每帧都会被再判成"新出现"，每帧重滑；
 *   · 只减旧 items：新行在消费前若恰逢一次 submit（旧 items 已含它），待播集合里的欠账被抹掉、
 *     动画永远不播。
 *
 * 写成顶层纯函数（返回值的顺序不影响显示，此处按 [current] 原序）：无类初始化负担，
 * JVM 单测可直接调（先例：本文件顶部的 [isRowBusy]，见其注释）。
 */
fun computeNewIds(current: List<String>, seen: Set<String>): Set<String> =
    current.filterNot { it in seen }.toSet()
