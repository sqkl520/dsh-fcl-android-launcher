package com.dsh.ui.shell

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.core.DshCredentials
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
import com.dsh.core.DshServices
import com.dsh.core.DshTask
import com.dsh.core.DshTasks
import com.dsh.ui.DshInstanceAdapter
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshInstancesBinding
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「实例」tab（外壳首页）。
 *
 * ## 它现在为什么是多页容器
 * 本页要**承载实例详情这个临时页**（详情从独立 Activity 搬进来了，见 [DshInstanceDetailPage]），
 * 而临时页栈属于 [DshMultiPageUI] —— 外壳的返回链（②③④ 级里的 ③）只会问
 * "当前页是不是多页容器、栈里还有没有东西可弹"。所以本页必须是多页容器的子类，
 * 哪怕它自己**一个页内 tab 都不需要**。
 *
 * ## "1 个假 tab"的来由
 * [DshMultiPageUI] 的契约是 `pageCount` / `createPage` / `tabTitle` 三件套，最小值是 1 ——
 * 没有"零 tab 但带临时页栈"的形态。于是这里的第 0 个（也是唯一的）子页就是**实例列表本身**
 * （[InstanceListPage]），也就是本页改造前的全部内容。
 *
 * 代价是骨架里会多出一条孤零零的 tab，所以 [onCreate] 里把 `tab_layout` 藏掉：id 是公开的，
 * 按 id 找即可，不需要去改 [DshMultiPageUI]（它把 `tabLayout` 声明成 `private`，
 * 有意不让子类插手）。我们借多页容器**只为拿它的临时页栈**，页内 tab 栏对本页没有意义。
 */
class DshInstancesUI(
    context: Context,
    private val host: DshShellHost
) : DshMultiPageUI(context) {

    /**
     * 已压进临时页栈的实例详情页：`内容视图 → 页面对象`。
     *
     * 为什么需要这张表：栈收的是 `View`（它不认识 `DshPageUI`），而弹栈后必须有人去
     * `destroy()` 页面 —— 否则那个页面级协程作用域会一直活着（页面没了、订阅还在跑）。
     * 这张表就是"View ↔ 页面"的对应关系，见 [hookTempOverlay] 与 [sweepDetachedPages]。
     *
     * 本页的设计下栈深**最多 1**（每次压栈前先清空，同一实例则复用不重压），所以它是个小表。
     */
    private val detailPages = HashMap<View, DshInstanceDetailPage>()

    // ---------------------------------------------------------------- 多页容器契约（1 个假 tab）

    override val pageCount: Int = 1

    override fun createPage(position: Int): DshPageUI =
        InstanceListPage(context, host) { id, tab -> showInstanceDetailAt(id, tab) }

    override fun tabTitle(position: Int): Int = R.string.dsh_tab_instances

    override fun onCreate() {
        // 路径与实例清单要在子页建出来之前就绪（子页 onCreate 里会读它们）
        DshPaths.loadPaths(context)
        DshInstances.init()
        DshLogBus.attachFile(java.io.File(DshPaths.LOG_FILE))

        // ★ 必须在 super.onCreate() 之后：pager、覆盖层、栈都是在
        //   DshMultiPageUI.onCreate() 的 setupPages() 里才建出来的。
        super.onCreate()

        // 藏掉那条多余的 tab。用 GONE 而不是 INVISIBLE：tab 栏所在的 FCLAppBarLayout 是
        // wrap_content，子视图 GONE 之后它的高度归零（GONE 的孩子连 margin 都不参与测量），
        // 下面的 container 才真正占满整屏。
        //
        // 按 id 找而不是让 DshMultiPageUI 暴露 tabLayout：那个字段是 private，本页只借它的
        // **临时页栈**，页内 tab 栏对本页没有意义 —— 为一句 visibility 去改基类的封装不划算。
        findViewById<View>(R.id.tab_layout).visibility = View.GONE

        hookTempOverlay()
    }

    // ---------------------------------------------------------------- 临时页入口

    /**
     * 把某实例的详情压进本页的临时页栈 —— 外壳的 `openInstanceDetail` 调它
     * （见 [DshShellHost.openInstanceDetail]）。
     *
     * 落点固定为「运行」段：外壳那条入口（右面板的「去配置」、启动失败对话框的「查看日志」）
     * 只带实例 id，不带"要看哪一段"的意图，那么落在详情页的第一段是唯一说得通的选择。
     * 需要指定段落的调用方（实例行的「日志」菜单）用 [showInstanceDetailAt]。
     */
    fun showInstanceDetail(instanceId: String) {
        showInstanceDetailAt(instanceId, DshInstanceDetailPage.TAB_RUN)
    }

    /**
     * 同上，但指定落在哪一段（传 [DshInstanceDetailPage] 的 `TAB_*` 常量）。
     */
    fun showInstanceDetailAt(instanceId: String, tab: Int) {
        // ① 实例已经不存在（外壳手上可能攥着一个过期的 id：右面板缓存的选中项、通知栏、
        //    或者用户在别处刚把它删了）。**不压栈** —— 压进去只会得到一个立刻自我退层的空页，
        //    用户看到的是"点了一下、屏幕闪了一下、还在原地"，比直接说一句难查得多。
        if (DshInstances.byId(instanceId) == null) {
            Toast.makeText(context, R.string.dsh_instance_detail_empty, Toast.LENGTH_LONG).show()
            return
        }

        // ② 先收掉"已经不在栈上、却还没销毁"的层（见 [sweepDetachedPages]）。
        sweepDetachedPages()

        // ③ 这个实例的详情已经在栈上：**不再压一层**。
        //    栈里同时挂两份同一个实例的详情没有意义，而且会让返回键的语义含糊
        //    （退一层到底回到哪一份？）。用户这次点的是"看日志/看配置"，那就切它的段。
        //
        //    注意这里说的不是"同一个 View 压两次"——那种情况由栈自己挡掉
        //    （`DshTempPageStack.show` 会先摘掉重复记录、并从旧 parent 上移除）。
        //    我们每次都是新建页面对象、新建 View，所以不存在 parent 冲突；要挡的是语义重复。
        val open = detailPages.entries.firstOrNull { it.value.instanceId == instanceId }
        if (open != null) {
            open.value.showTab(tab)
            return
        }

        // ④ 换实例：先把旧的清干净，再压新的。
        //    清栈会把旧层的 View 从覆盖层摘掉，摘掉的瞬间就会触发"某一层被摘掉"的上报，
        //    对应的页面对象随之被销毁（见 [hookTempOverlay]）。
        dismissAllTempPages()

        val page = DshInstanceDetailPage(
            context = context,
            host = host,
            instanceId = instanceId,
            initialTab = tab,
            // 实例被删掉后详情页没有可显示的对象，它自己没有能力弹掉自己（它继承来的那份栈
            // 是"页内 3 个 tab"用的，两回事），所以由外层负责这一层退出。
            // 回调参数就是页面自身，[closeDetail] 靠它确认"要退的正是这一层"。
            onClose = ::closeDetail
        )

        // ★ onCreate() 必须先于压栈：页面的内部结构（页内 ViewPager2 / tab / 覆盖层）
        //   全在 DshMultiPageUI.onCreate() 的 setupPages() 里建，不调它就是个空壳。
        //   这与 DshMultiPageUI.getPage() 里"创建后立刻 onCreate()"是同一条约定。
        page.onCreate()

        // 先压栈、再登记：反过来的话，"已登记但还没挂上去"的瞬间 parent 是 null，
        // 会被 [sweepDetachedPages] 误判成"已经不在栈上"而提前销毁。
        val view = page.getContentView()
        showTempPage(view)
        detailPages[view] = page
    }

    /**
     * 详情页请求退层（实例已被删除）。
     *
     * **必须先清点**：如果这一层早就被返回键弹掉了，它的协程作用域却还活着（还没人销毁它），
     * 那么它此刻发来的这次请求就是"迟到的" —— 直接弹栈会把**后来压上去的另一层**弹掉。
     * 清点之后它在表里已经不存在，于是这里直接返回，什么都不做。
     */
    private fun closeDetail(page: DshInstanceDetailPage) {
        sweepDetachedPages()
        val view = page.getContentView()
        if (detailPages[view] !== page) return
        detailPages.remove(view)
        dismissCurrentTempPage()
        page.destroy()
    }

    // ---------------------------------------------------------------- 生命周期接线

    /**
     * 把"临时页被摘掉"这件事接上，用来销毁对应的页面对象。
     *
     * ## 为什么必须接这一条
     * 临时页**不是本类弹的** —— 外壳的返回链第 ③ 级直接调 `dismissCurrentTempPage()`，
     * 完全不经过本类。所以"页面被摘掉之后谁去 `destroy()`"只能靠栈**主动上报**，
     * 而不是"我们弹的时候顺手清理"。
     *
     * 不接的后果：页面对象还活着、它的协程作用域还在跑订阅（页面没了，日志/状态还在后台刷新）。
     *
     * 上报在 `removeView` 之后立刻触发，覆盖弹栈 / 清栈（切 tab、换实例）/ 宿主销毁三条路径，
     * 所以这一处就够，不需要再监听覆盖层的层级变化。
     */
    private fun hookTempOverlay() {
        setOnTempPageDismissed { view -> detailPages.remove(view)?.destroy() }
    }

    /**
     * 收掉"表里有、但已经不在栈上"的详情页。
     *
     * 这是 [hookTempOverlay] 的兜底，不是主路径：正常弹栈都由那条上报即时处理。它覆盖的是
     * "登记进表、却从没被挂上去"这种异常路径（例如压栈时栈已作废、`show()` 静默返回），
     * 那种页面的 parent 恒为 null，会一直挂在表里并拖着它的协程作用域。
     *
     * 判定依据是**视图是否还有父容器** —— 栈压页时一定 `addView` 进覆盖层、弹页时一定
     * `removeView`（同步、立即），所以"还在栈上 ⟺ parent != null"成立。清点的时机是
     * "每次要动栈之前"，成本是表的长度（本页设计下最多一项）。
     */
    private fun sweepDetachedPages() {
        val iter = detailPages.entries.iterator()
        while (iter.hasNext()) {
            val (view, page) = iter.next()
            if (view.parent == null) {
                iter.remove()
                page.destroy()
            }
        }
    }

    override fun destroy() {
        // 顺序有讲究：**先清栈，再补一遍销毁**。
        // `super.destroy()` 会 `tempStack.destroy()`，那是同步的 `removeView` —— 层级监听会
        // 在第一时刻把栈上的详情页销毁掉。后面那一段是兜底：万一有页面进了表却没被挂上去
        // （见 [sweepDetachedPages]），它不会收到任何通知，只能在这里收掉。
        super.destroy()
        detailPages.values.forEach { it.destroy() }
        detailPages.clear()
    }
}

/**
 * 「实例列表」子页 —— 本页唯一的 tab 子页，内容就是改造前 [DshInstancesUI] 的全部东西：
 * 进行中任务区 + 实例列表 + 空态。
 *
 * ## 为什么单独拆一个类
 * [DshInstancesUI] 现在是"多页容器 + 临时页栈 + 打开详情"的宿主，职责与列表渲染无关。
 * 把它写成同文件的第二个类（而不是另开文件），是为了让"这一页有哪些东西"一眼可见 ——
 * 外层是壳、内层是内容，读一个文件就够。
 *
 * @param onOpenDetail 打开某实例详情的指定段落（tab 常量见 [DshInstanceDetailPage]）。
 *   列表里的「日志」菜单与启动失败对话框的「查看日志」用它 —— 日志已经是详情页的一个 tab，
 *   不再是外壳的一个入口，所以这两处必须落到"详情页的日志段"，而不是去切外壳 tab。
 */
private class InstanceListPage(
    context: Context,
    private val host: DshShellHost,
    private val onOpenDetail: (String, Int) -> Unit
) : DshPageUI(context, R.layout.activity_dsh_instances) {

    private val binding = ActivityDshInstancesBinding.bind(contentView)
    private lateinit var adapter: DshInstanceAdapter
    private var lastNotifiedState: DshRuntime.State? = null

    override fun onCreate() {
        super.onCreate()

        adapter = DshInstanceAdapter(
            onStart = ::startInstance,
            onStop = { DshRuntime.stop("用户停止") },
            // 行的「更多 → 设置」与左滑菜单的「设置」：统一走外壳那条入口
            // （它先切到「实例」tab 再让那一页把详情压栈，语义是"打开这个实例的详情"）
            onOpenSettings = { host.openInstanceDetail(it.id) },
            onOpenLogs = { onOpenDetail(it.id, DshInstanceDetailPage.TAB_LOGS) },
            onReinstall = ::reinstall,
            onDelete = ::confirmDelete
        )
        binding.instanceList.layoutManager = LinearLayoutManager(context)
        binding.instanceList.adapter = adapter

        observeState()
        observeTasks()
        maybeAdoptOrphan()
    }

    /**
     * 订阅「进行中任务」并渲染任务区。
     *
     * 首页要把**所有正在跑的任务**集中显示（解压运行环境 / 安装 dsh / 启动 / 删除）——
     * 它们分散在四个模块里，统一由 [com.dsh.core.DshTasks] 聚合，这里只负责画。
     * 无任务时整块隐藏。
     */
    private fun observeTasks() {
        scope.launch {
            DshTasks.tasks.collect { renderTasks(it) }
        }
    }

    private fun renderTasks(tasks: List<DshTask>) {
        binding.taskArea.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        binding.taskList.removeAllViews()
        if (tasks.isEmpty()) return

        val inflater = android.view.LayoutInflater.from(context)
        tasks.forEach { task ->
            val row = com.dsh.fcl.androidlauncher.databinding.ViewDshTaskRowBinding
                .inflate(inflater, binding.taskList, false)

            row.taskTitle.text = taskTitle(task)

            val f = task.fraction
            if (f == null) {
                row.taskProgress.isIndeterminate = true
            } else {
                row.taskProgress.isIndeterminate = false
                row.taskProgress.progress = (f * 1000).toInt().coerceIn(0, 1000)
            }

            if (task.detail.isNullOrEmpty()) {
                row.taskDetail.visibility = View.GONE
            } else {
                row.taskDetail.visibility = View.VISIBLE
                row.taskDetail.text = task.detail
            }

            when (task.action) {
                DshTask.Action.NONE -> row.taskAction.visibility = View.GONE
                DshTask.Action.CANCEL -> {
                    row.taskAction.visibility = View.VISIBLE
                    row.taskAction.setImageResource(R.drawable.ic_baseline_close_24)
                    row.taskAction.contentDescription = context.getString(R.string.dsh_tasks_cancel)
                    row.taskAction.setOnClickListener { DshTasks.cancel(task) }
                }
                DshTask.Action.STOP -> {
                    row.taskAction.visibility = View.VISIBLE
                    row.taskAction.setImageResource(R.drawable.ic_baseline_close_24)
                    row.taskAction.contentDescription = context.getString(R.string.dsh_tasks_stop)
                    row.taskAction.setOnClickListener { DshTasks.cancel(task) }
                }
            }
            binding.taskList.addView(row.root)
        }
    }

    /** 任务标题：运行环境用固定文案，其余用「实例名 · 阶段」 */
    private fun taskTitle(task: DshTask): String = when (task.kind) {
        DshTask.Kind.BOOTSTRAP -> context.getString(R.string.dsh_task_bootstrap)
        else -> listOf(task.title, task.stage).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun observeState() {
        scope.launch {
            combine(
                DshInstances.instances,
                DshRuntime.state,
                DshInstances.deleting
            ) { list, state, deleting -> Triple(list, state, deleting) }
                .collect { (list, state, deleting) ->
                    val runningId = when (state) {
                        is DshRuntime.State.Running -> state.instanceId
                        is DshRuntime.State.Starting -> state.instanceId
                        is DshRuntime.State.Stopping -> state.instanceId
                        else -> null
                    }
                    adapter.submit(list, runningId, deleting)
                    val empty = list.isEmpty()
                    binding.emptyHint.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.instanceList.visibility = if (empty) View.GONE else View.VISIBLE
                }
        }
        scope.launch {
            DshServices.installer(context).statuses.collect { statuses ->
                adapter.submitInstallStatus(statuses)
            }
        }
        scope.launch {
            DshRuntime.state.collect { st -> notifyIfNeeded(st) }
        }
    }

    private fun notifyIfNeeded(st: DshRuntime.State) {
        if (st is DshRuntime.State.Idle ||
            st is DshRuntime.State.Starting ||
            st is DshRuntime.State.Stopping
        ) {
            lastNotifiedState = null
            return
        }
        if (st == lastNotifiedState) return
        when (st) {
            is DshRuntime.State.Failed -> {
                lastNotifiedState = st
                FCLAlertDialog.Builder(host.activity)
                    .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
                    .setTitle(context.getString(R.string.dsh_start_failed))
                    .setMessage(st.reason)
                    // 「查看日志」落到**这个实例**详情的日志段 —— 日志已按实例归属收进详情页，
                    // 外壳层面不再有一个"日志 tab"可切
                    .setPositiveButton(context.getString(R.string.dsh_action_view_logs)) {
                        onOpenDetail(st.instanceId, DshInstanceDetailPage.TAB_LOGS)
                    }
                    .setNegativeButton(context.getString(R.string.dialog_positive)) {
                        DshRuntime.resetState()
                    }
                    .create()
                    .show()
            }
            is DshRuntime.State.Exited -> {
                lastNotifiedState = st
                Toast.makeText(
                    context,
                    context.getString(R.string.dsh_runtime_exited, st.code),
                    Toast.LENGTH_LONG
                ).show()
                DshRuntime.resetState()
            }
            else -> {}
        }
    }

    private fun maybeAdoptOrphan() {
        if (DshRuntime.runningInstanceId() != null) return
        scope.launch {
            val candidates = DshInstances.instances.value.filter { it.state == DshInstance.State.READY }
            for (inst in candidates) {
                val ok = withContext(Dispatchers.IO) { DshRuntime.adoptOrphan(context, inst) }
                if (ok) {
                    Toast.makeText(context, R.string.dsh_adopted_running, Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
        }
    }

    /** 启动实例。复用 [DshLauncher]（与外壳右面板、实例详情页是**同一份**逻辑与参数） */
    private fun startInstance(inst: DshInstance) {
        DshLauncher.startInstance(
            activity = host.activity,
            inst = inst,
            scope = scope,
            onOpenSettings = { host.openInstanceDetail(it.id) },
            onOpenLogs = { onOpenDetail(inst.id, DshInstanceDetailPage.TAB_LOGS) },
            onPrepareRuntime = { DshLauncher.openSetup(host.activity) },
            onStarted = { host.openWebView() }
        )
    }

    /**
     * 重新安装。
     *
     * 没装成功过任何版本时**不直接跳走**，而是弹一个带「下载」按钮的确认框：跳走会把用户
     * 正在看的列表顶掉，而且他没得选（必须先去版本页挑一个）。
     */
    private fun reinstall(inst: DshInstance) {
        val version = inst.dshVersion
        if (version == null) {
            FCLAlertDialog.Builder(host.activity)
                .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
                .setTitle(context.getString(R.string.dsh_action_reinstall))
                .setMessage(context.getString(R.string.dsh_reinstall_pick_version))
                .setPositiveButton(context.getString(R.string.dsh_action_download)) {
                    host.switchTab(DshShellHost.TAB_VERSIONS)
                }
                .setNegativeButton(context.getString(R.string.dialog_negative), null)
                .create()
                .show()
            return
        }
        DshServices.installer(context).install(inst, version)
        Toast.makeText(context, context.getString(R.string.dsh_install_started, version), Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(inst: DshInstance) {
        FCLAlertDialog.Builder(host.activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(context.getString(R.string.dsh_delete_title))
            .setMessage(context.getString(R.string.dsh_delete_message, inst.name))
            .setPositiveButton(context.getString(R.string.dsh_action_delete)) {
                DshCredentials.clear(context, inst.id)
                DshInstances.delete(inst.id)
                Toast.makeText(context, R.string.dsh_delete_started, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(context.getString(R.string.dialog_negative), null)
            .create()
            .show()
    }
}
