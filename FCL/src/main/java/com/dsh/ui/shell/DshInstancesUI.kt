package com.dsh.ui.shell

import android.content.Context
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mio.ui.adapter.SpacingItemDecoration
import com.mio.util.AnimUtil
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
import com.dsh.fcl.androidlauncher.databinding.ViewDshTaskRowBinding
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
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
     * 落点固定为「运行」段：外壳那条入口（实例行的「更多 → 设置」）只带实例 id，
     * 不带"要看哪一段"的意图，那么落在详情页的第一段是唯一说得通的选择。
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
 * 任务区（进行中 + 已结束）+ 实例列表 + 空态。
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
    private lateinit var taskAdapter: TaskAdapter

    /**
     * 上一帧已经**在任务区里出现过**的进行中任务 id。
     *
     * ## 为什么需要它
     * 任务列表是 `StateFlow<List<DshTask>>`，安装进度一变就整份重新发射（每秒好几次）。
     * FCL 的列表行在绑定时无条件播入场动画（版本列表就是这么写的），但那一套的前提是
     * DiffUtil 增量更新 —— 只有真正的新行才会被绑。任务区若照抄"绑定即播"，
     * **每一次进度更新都会让整个任务区重新滑入一遍**，表现就是"动画在诡异地重播"。
     *
     * 所以这里自己记住上一帧的 id 集合，只让**这一帧新出现**的任务播：比对 id，差集里的才动。
     * 用 id 而不是位置/对象：`DshTask` 是 data class，进度一变整个对象就不相等了，
     * 按对象或按 index 比对都会把老任务误判成新任务；而 id 在 [com.dsh.core.DshTasks] 里
     * 本来就是"同一种任务同 id"的稳定标识。
     *
     * ★ 集合里只放**进行中**那一半的 id（见 [mergeTasks]），这是动画语义的直接要求：
     *   一条任务跑完 / 失败 / 被取消时，**它的 id 一个字符都没变** ——
     *   [com.dsh.core.DshTasks.report] 用的就是投影里那个 id（两处共用 `installTaskId` /
     *   `runtimeTaskId` / `deleteTaskId` 的拼法），它只是从列表上半区挪到了下半区。
     *   于是"要不要给终态行补一次入场动画"这个问题，可以有两种写法：
     *   ① 按"这个 id 出现过没有"判 —— 终态行因为 id 已存在而不播（结果正确，但结论依赖
     *      "两条流恰好同一帧到齐"这个前提：`tasks` 与 `finished` 是两条独立 StateFlow，
     *      合并后**哪条到了都会渲染一次**，投影先摘掉 id 的那一帧会让它短暂进不了集合，
     *      下一帧它就又成了"新出现"，播放与否取决于帧序）；
     *   ② 按"只有进行中的 id 才进集合"判 —— 终态行的 id 压根进不了集合，也就无所谓摘不摘，
     *      结论变成与帧序无关的：**终态行永远不播**。
     *   这里取 ②。顺带，这也正是想要的观感：终态行是"原地变色"的，补一次滑入会让用户
     *   以为列表在跳，而它其实一直就在那儿。
     *
     * 集合每帧整体替换，所以任务消失后它的 id 自然被摘掉：同一个实例再次安装会得到
     * **同一条 id**（`install:<实例id>`），不摘的话第二次安装就不会有入场动画了。
     */
    private var animatedTaskIds: Set<String> = emptySet()

    private var lastNotifiedState: DshRuntime.State? = null

    /**
     * 亮暗切换时把任务区重绑一遍。
     *
     * ## 为什么需要它
     * 终态行的颜色是**在 `onBindViewHolder` 里现算的**（失败用 @color/dsh_log_error、
     * 取消用 autoHintTint）：FCL 的 `app:auto_text_tint` 只处理"随主色自动对比"那一档，
     * 表达不了"这一行是失败"这种按数据的语义色。代价是这些颜色不再是控件自己的主题回调能刷新的
     * —— 而亮暗切换**不会重建 Activity**（外壳的 configChanges 含 uiMode），所以不加这一句的话，
     * 切到暗色后任务区还停在亮色那一套配色上（错误的深红压在深底上，基本看不见）。
     *
     * 与 [DshAboutUI] / 实例设置页里 `registerEvent(list, invalidate)` 是同一个做法：
     * 控件自己刷新不了的东西，由页面在主题回调里重画一次。任务区最多二十来行，重绑的代价可以忽略。
     */
    private val themeRebind = Runnable { taskAdapter.notifyDataSetChanged() }

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

        // 任务区与设置页 / 关于页那几处列表同构：RecyclerView + LinearLayoutManager
        // + SpacingItemDecoration（同一套间距规则，不必每处各写一遍）。
        // 行布局里因此**不再**写行间距；装饰器自己会跳过最后一行，不需要"末行不留间距"的特判。
        taskAdapter = TaskAdapter()
        binding.taskList.layoutManager = LinearLayoutManager(context)
        binding.taskList.addItemDecoration(SpacingItemDecoration(dp(8)))
        binding.taskList.adapter = taskAdapter

        // 「清空已完成」：终态行是**只增不减**的历史（`finished` 只在内存里留最近 20 条），
        // 不手动清的话，任务区会长期占着首页顶部那一块，把实例列表往下挤。
        // 放在头部行右端（与日志页那排工具图标同款：FCLImageButton + src + auto_tint + anim_scale_large），
        // 不占单独一行 —— 任务区本身就不该为一个次要动作长高。
        binding.taskClearFinished.setOnClickListener { DshTasks.clearFinished() }

        // 任务区那几行的颜色是按数据现算的（见 [themeRebind]），控件自己的主题回调刷新不了它们
        ThemeEngine.getInstance().registerEvent(binding.taskList, themeRebind)

        observeState()
        observeTasks()
        maybeAdoptOrphan()
    }

    /**
     * 页面被回收时注销主题回调。
     *
     * `registerEvent` 内部虽然是 WeakHashMap（页面没了、View 不再被引用时条目会随 GC 消失），
     * 但那要等到下一次 GC —— 而这里的关联对象是**页面级 Adapter**，它比 View 活得久一点，
     * 显式注销是"谁注册谁注销"的对称写法（[DshAboutUI] / 实例设置页都是这么写的）。
     */
    override fun onDestroy() {
        ThemeEngine.getInstance().unregisterEvent(binding.taskList)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    /**
     * 订阅任务区，把**进行中**与**已结束**两条流合起来渲染。
     *
     * ## 为什么要合两条流（这是任务区第一次能说"结果"）
     * [com.dsh.core.DshTasks.tasks] 是从各模块 StateFlow **每帧重算**的投影，只含"现在还在跑"的；
     * 一件事一旦结束（装完 / 失败 / 被取消），它就从那条流里消失 —— 所以改造前用户点下"取消"，
     * 那一行是**瞬间不见**的：界面既不承认"你取消了"，也不说"它装完了"。
     * 这个项目里补上的 [com.dsh.core.DshTasks.finished] 是一条**事件**流（由知道结局的那段代码
     * push 进来），才是"结果"的载体。两条合起来，任务区才既能说"正在发生什么"、
     * 又能说"刚才怎么样了"。
     *
     * ## 为什么是 `combine` 而不是两个 `collect` 各渲染各的
     * 两个 collect 会各自独立触发渲染，而每次渲染都要重算"哪些 id 是新出现的" —— 那个差集只有
     * 建立在**同一帧的两份数据**上才有意义，否则两条流一前一后到达时，中间那一帧看到的永远是
     * 残缺的列表，去重与动画都会跟着抖。`combine` 保证每次回调拿到的是两条流**当前值的快照**
     * （哪条更新都会重算一次），代价只是一次列表拼接 —— 这个列表最多 3 + 20 条。
     *
     * ## 顺序：进行中在上，已结束在下
     * 用户关心的是"正在发生什么"，历史只是背景。两条流各自内部次序都是确定的
     * （`tasks` 按模块拼装的固定顺序，`finished` 是**最新在前**），这里只把两段接起来。
     * 为此**不做**跨两段的统一排序：按时间戳排会让一条刚失败的历史行插到仍在跑的安装之上，
     * 而那正是用户此刻最该看见的一行。
     */
    private fun observeTasks() {
        scope.launch {
            combine(DshTasks.tasks, DshTasks.finished) { running, finished ->
                mergeTasks(running, finished)
            }.collect { merged ->
                val rows = merged.rows
                // 头部标题与「清空已完成」按钮跟着**这一帧实际画出来的行**走：
                //   · 标题：有进行中的行 → "进行中"，只剩历史 → "已完成"。
                //     两种都不对的情况（一行都没有）由下面的 taskArea 整体隐藏兜住，
                //     所以这里不需要第三个分支。
                //   · 按钮：只在真有历史行时才出现。用 historyCount（去重之后的数量）而不是
                //     finished 的原始长度 —— 刚点完取消的那几帧里，同一件事同时存在于两条流，
                //     去重后一行历史都没有，此时露出"清空已完成"会让人以为点了个空。
                binding.taskHeader.text = context.getString(
                    if (merged.runningCount > 0) R.string.dsh_tasks_header
                    else R.string.dsh_tasks_header_finished
                )
                binding.taskClearFinished.visibility =
                    if (merged.historyCount > 0) View.VISIBLE else View.GONE
                binding.taskArea.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                taskAdapter.submit(rows, merged.freshIds)
            }
        }
    }

    /**
     * 合并"进行中 + 已结束"，并算出这一帧该播入场动画的 id。
     *
     * ## 同一条任务会不会同时出现在两条流里？—— **会**，所以必须去重
     * `tasks` 是投影（"那些模块的 StateFlow 现在还说它在跑吗"），`finished` 是事件列表，
     * 两者之间没有任何互斥机制。最典型的两段窗口：
     * - **取消安装**：[com.dsh.core.DshInstaller.cancel] 先 `_statuses.update { it - id }`
     *   再 `DshTasks.report(CANCELLED)`，而投影是 `combine` 重算出来的、要等它那一轮收集才更新 ——
     *   中间那几帧里"进行中"与"已取消"是同时在的；
     * - **停止实例**：投影里的"停止中"最长要挂 5 秒（[com.dsh.core.DshRuntime] 的终止是异步的），
     *   而 CANCELLED 在用户点下"停止"的那一刻就已经上报了。
     * 不去重的话，同一次操作会显示成两行（上面"安装中…"、下面"已取消"），用户只会更困惑。
     *
     * ## 去重时留哪一条：留**进行中**那条
     * 直觉上该"终态更新"，但在这里是反的。上面两段窗口里，进行中那条并不是陈旧数据 ——
     * 它描述的正是"现在还在收尾"（安装协程还在跑、proot 还没死）。留终态那条会得到最糟的画面：
     * 任务区显示"已停止"，可实例其实还在跑，用户以为停干净了，几秒后才看到它凭空消失。
     * 留进行中那条相反：它继续显示"停止中…"，等投影真的把它摘掉的那一刻，终态行才在下半区出现 ——
     * 那一帧的事实也刚好如此。
     *
     * ## 终态行不播入场动画
     * 见 [animatedTaskIds] —— 差集只在**进行中**的 id 上算。
     */
    private fun mergeTasks(running: List<DshTask>, finished: List<DshTask>): TaskRows {
        val runningIds = running.mapTo(HashSet()) { it.id }
        // 只渲染**不在进行中**的那些历史：进行中那条已经在上面占了它的位置
        val history = finished.filterNot { it.id in runningIds }

        // 差集要在**替换** animatedTaskIds 之前算：先算差集、再整份替换成这一帧的集合。
        // 集合只装进行中的 id，理由见 [animatedTaskIds]。
        val fresh = runningIds - animatedTaskIds
        animatedTaskIds = runningIds

        return TaskRows(running + history, fresh, running.size, history.size)
    }

    /**
     * 一帧任务区的全部渲染输入。
     *
     * 用一个具名的小类而不是 `Triple`/`Pair`：这里的几个值在调用处是**一起**被读的
     * （`rows` 画列表、两个计数决定头部标题与清空按钮显不显示），而解构三元组时数字顺序
     * 一旦写反（`runningCount` 与 `historyCount` 都是 Int，编译期不会报错），
     * 症状是"标题说已完成、按钮却不见了"这种查起来极费劲的错位。
     *
     * @param freshIds 这一帧该播入场动画的 id（只有进行中的任务会进来，见 [animatedTaskIds]）
     * @param runningCount 去重**之后**仍在进行中的行数（决定头部标题说"进行中"还是"已完成"）
     * @param historyCount 去重之后真正会画出来的历史行数（决定清空按钮显不显示）
     */
    private class TaskRows(
        val rows: List<DshTask>,
        val freshIds: Set<String>,
        val runningCount: Int,
        val historyCount: Int
    )

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
                // ★ 这里**故意**不再单独清 API Key：Key 现在归 dsh 管，存在
                // $DSH_HOME/.credentials.yaml（即 <实例目录>/home/.credentials.yaml），
                // 而 DshInstances.delete() 会把整个实例目录 deleteRecursively() ——
                // 它自然跟着一起没了。原来那步 DshCredentials.clear() 是启动器自管密钥时代的
                // 残留，Key 既然不再由启动器持有，留在这只会让人以为它去了别的地方。
                DshInstances.delete(inst.id)
                Toast.makeText(context, R.string.dsh_delete_started, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(context.getString(R.string.dialog_negative), null)
            .create()
            .show()
    }
}

/**
 * 任务区 Adapter —— 本文件私有，不另开文件：它只服务 [InstanceListPage] 这一处，
 * 和"任务区怎么画"的其余逻辑（新任务判定、标题文案）放在一起读才完整。
 *
 * ## 为什么不用 DiffUtil
 * 任务只有几条（进行中 0~3 + 最近的历史），区分"哪条变了"的复杂度（DiffUtil 回调、稳定 id）
 * 换不来收益。直接 `notifyDataSetChanged()` 在这里是**正确**的：RecyclerView 只重绑可见的那几行，
 * 不会像原来 `removeAllViews() + addView` 那样把行整个重建 —— 行重建正是
 * "进度每跳一次、任务区就闪一下"的来源。
 *
 * ## 它和 RecyclerView 的关系
 * 任务区换成 RecyclerView 不是为了复用，而是为了和本项目其它列表**同构**：
 * 设置页 / 关于页那几处都是 RecyclerView + [SpacingItemDecoration] 管间距，
 * 任务区跟着走，"间距"这类规则就只需要定义一遍，而不是在这里再手搓一套行内 margin。
 * 顺带，加了历史行之后"复用"这件事真的会发生（一条终态行被回收去画一条新的进行中行），
 * 所以下面每一处**双分支**的可见性赋值都不是多余的写法：只设一半会让复用的行留住上一帧的状态。
 */
private class TaskAdapter : RecyclerView.Adapter<TaskAdapter.VH>() {

    private var items: List<DshTask> = emptyList()

    /**
     * 这一帧**新出现**的任务 id（差集由调用方算，理由见调用处注释）。
     * `onBindViewHolder` 拿不到"哪些是新增"，所以在 [submit] 时记下来带过去。
     */
    private var freshIds: Set<String> = emptySet()

    fun submit(tasks: List<DshTask>, freshIds: Set<String>) {
        items = tasks
        this.freshIds = freshIds
        notifyDataSetChanged()
    }

    class VH(val binding: ViewDshTaskRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ViewDshTaskRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val task = items[position]
        val b = holder.binding
        val context = b.root.context
        val theme = ThemeEngine.getInstance().getTheme()

        // 标题：「进行中」与「终态」的差别只在终态后缀（见 titleText），颜色另外给
        b.taskTitle.text = task.titleText(context)

        // ── 三种终态各自长什么样（改造前它们完全一样，用户分不出"装完了"和"失败了"）──
        //
        // 颜色来源只用**已有的**资源与主题取值器，不新增颜色资源：
        //   · 失败用它自己的语义色：@color/dsh_log_error —— 它本来就是"错误"这个语义的色，
        //     已在 values/ 与 values-night/ 各配了一套（亮色深红压在浅底、暗色提亮降饱和），
        //     跟着亮暗模式自动换，不需要再复制一份；
        //   · 取消/完成走主题：取消的**标题**用 autoHintTint（半透明的自动对比色，由主色亮度
        //     算出黑/白），天然"弱化但不消失"；完成的标题与其余一切照旧（getColor2()，次要主题色）。
        // 用一个 when 一次定完标题色、明细行、进度条、后缀与动作，避免五处各判一遍 state 而漂移。
        var detailText = task.detail
        var titleColor = theme.getColor2()
        // 明细行的默认色 = autoTint，也就是布局里 app:auto_text_tint 本来会给的那个色 ——
        // 行里另外那条 android:alpha="0.7" 仍在管"次要"这件事，两者叠加即改造前的观感。
        var detailColor = theme.autoTint
        var showProgress = true
        when (task.state) {
            DshTask.State.RUNNING -> Unit // 进行中：维持原样（进度条在位、颜色为主题次要色）

            DshTask.State.DONE -> {
                // 装完了 / 跑完了：结果已经在 stage 里说清楚了（"dsh 0.1.5-rc.2"、"已删除"），
                // 不需要额外解释。进度条收起 —— 一根停在半截的进度条挂在"完成"下面，看起来像还没跑完。
                showProgress = false
            }

            DshTask.State.FAILED -> {
                // 失败：把 error 当明细行（它是**为什么失败**的权威字段，安装器与运行时都往里写）；
                // 标题与明细都走错误色，让它在一片行里第一眼就被看见。
                showProgress = false
                titleColor = context.getColor(R.color.dsh_log_error)
                detailColor = context.getColor(R.color.dsh_log_error)
                if (!task.error.isNullOrEmpty()) detailText = task.error
            }

            DshTask.State.CANCELLED -> {
                // 取消：文案后缀在 titleText 里加（"（已取消）"），这里做整体的弱化 ——
                // 标题走 autoHintTint 那一档（半透明的黑/白，由主色亮度算出），
                // 它比正文弱、但不会像置灰那样在暗色底上直接看不见。
                // 不隐藏整行：用户点了取消，正是要确认"它真的停了"。
                showProgress = false
                titleColor = theme.autoHintTint
            }
        }
        b.taskTitle.setTextColor(titleColor)

        if (showProgress) {
            b.taskProgress.visibility = View.VISIBLE
            val f = task.fraction
            if (f == null) {
                b.taskProgress.isIndeterminate = true
            } else {
                b.taskProgress.isIndeterminate = false
                b.taskProgress.progress = (f * 1000).toInt().coerceIn(0, 1000)
            }
        } else {
            // ★ 双向赋值而不是"只在终态时 GONE 一次"：RecyclerView 会复用行 ——
            //   一个被"失败行"用过的坑位，下一帧可能要拿去画一条"进行中行"，
            //   那时必须是 VISIBLE 且能重新显示进度。只写一半就会让复用的行留住上一帧的隐藏状态。
            b.taskProgress.visibility = View.GONE
        }

        if (detailText.isNullOrEmpty()) {
            b.taskDetail.visibility = View.GONE
        } else {
            b.taskDetail.visibility = View.VISIBLE
            b.taskDetail.text = detailText
            b.taskDetail.setTextColor(detailColor)
            // 失败行放宽到两行、尾省略：error 是"为什么失败"这句话本身，中间省略会把结论吃掉
            // （"安装失败：npm ERR! code ENETUNREACH" 单行放不下时，中间省略正好砍掉后半句）。
            // 进行中/完成/取消仍是单行 + 中间省略：那些内容（正在解压的文件名、版本号）是可对照的标识，
            // 省略中段反而更好认。行布局用 maxLines 而不是 singleLine，就是为了能在这里放宽。
            b.taskDetail.maxLines = if (task.state == DshTask.State.FAILED) 2 else 1
            b.taskDetail.ellipsize = if (task.state == DshTask.State.FAILED) {
                TextUtils.TruncateAt.END
            } else {
                TextUtils.TruncateAt.MIDDLE
            }
        }

        // 右侧动作图标：★ 用 setBackgroundResource 而不是 setImageResource ——
        // view_dsh_task_row.xml 里的 task_action 是 FCLImageView，它的 use_theme_color
        // 只给 getBackground() 上色，走 src 会既没有主题色、又保留 vector 自带的静态 tint。
        //
        // ★ 终态行一律没有动作：finished 里的任务 action 本来就被上报方填成 NONE，但界面不依赖
        //   那个约定 —— 万一将来有人上报终态时忘了改 action，这里会把"取消"按钮画给一个已经结束的任务，
        //   点下去是空操作。所以先按 state 判一次，终态直接按 NONE 画。
        val action = if (task.isFinished) DshTask.Action.NONE else task.action
        when (action) {
            DshTask.Action.NONE -> {
                b.taskAction.visibility = View.GONE
                // 清掉点击监听：这一行可能刚从"进行中"复用到"已完成"，留着上一帧的监听意味着
                // 用户点一个已完成的行还能触发一次取消（对象虽然已结束，但那是白挨一下）。
                b.taskAction.setOnClickListener(null)
            }

            DshTask.Action.CANCEL -> setTaskAction(
                b, context,
                R.drawable.ic_baseline_close_24, R.string.dsh_tasks_cancel, task
            )

            DshTask.Action.STOP -> setTaskAction(
                b, context,
                R.drawable.ic_baseline_stop_24, R.string.dsh_tasks_stop, task
            )
        }

        // 入场动画（FCL 同款：AnimUtil.playTranslationX，时长随「动画速度」设置）——
        // 只在**这一帧新出现**的进行中任务上播，见 [animatedTaskIds]（终态行是原地变色的，不播）。
        if (task.id in freshIds) {
            AnimUtil.playTranslationX(
                b.root,
                theme.animationSpeed * 30L,
                -100f,
                0f
            ).start()
        } else {
            // 复用/重绑的行可能停在上一次动画的中间态（新任务插在前面时，后面的行会换位重绑），
            // 归零一次，避免"没播动画的行却歪在一边"。
            b.root.translationX = 0f
        }
    }

    /**
     * 画右侧那个动作按钮（图标 + 无障碍文案 + 点击）。
     *
     * 抽出来是因为"取消"和"停止"只差一个 drawable 与一个字符串，而这两处必须成对地改 ——
     * 原来它们挤在同一个分支里共用 `ic_baseline_close_24`，结果是**两个语义完全不同的动作
     * 长得一模一样**：用户看到"取消安装"和"停止实例"是同一个叉，只能靠读文字（而图标按钮没有文字）。
     */
    private fun setTaskAction(
        b: ViewDshTaskRowBinding,
        context: Context,
        @DrawableRes icon: Int,
        @StringRes description: Int,
        task: DshTask
    ) {
        b.taskAction.visibility = View.VISIBLE
        b.taskAction.setBackgroundResource(icon)
        // 运行期换背景图不会自动带上主题色（FCLImageView 的着色只在主题刷新时跑），
        // 换完补一次；之后主题切换由控件自己的回调维持。用 getColor2()（带亮暗判断），
        // 原始 .color2 不分模式，暗色下会把图标染成黑的。
        b.taskAction.background?.setTint(ThemeEngine.getInstance().getTheme().getColor2())
        b.taskAction.contentDescription = context.getString(description)
        b.taskAction.setOnClickListener { DshTasks.cancel(task) }
    }

    override fun getItemCount(): Int = items.size
}

/**
 * 任务标题：「运行环境」那条用固定文案（它的 title 是空的，只有阶段），其余用「实例名 · 阶段」。
 *
 * 写成 [DshTask] 的扩展而不是页面里的私有方法：这是"任务怎么显示"的规则，
 * 归渲染它的 [TaskAdapter] 管，页面不必知道。函数是文件私有的，不外泄。
 *
 * ## 终态的后缀为什么加在**标题**上，而不是替换掉 stage
 * `stage` 是产生这件事的那段代码写下的**事实描述**（"安装中…"、"端口被占用（EADDRINUSE）"、
 * "进程已退出（code=143）"）—— 它是给用户看的解释，不该被"已取消"三个字挤掉。
 * 而"这件事已经结束了"是**界面的**补充信息，加在末尾既保留了原话，又一眼能分出
 * "还在装"和"已经停了"。所以取消态是「实例名 · 阶段（已取消）」。
 *
 * 完成态**不加**后缀：那一行的 stage 本身就是结论（安装的是 `dsh <版本>`、删除的是"已删除"），
 * 再挂一个"已完成"是同一句话说两遍。
 */
private fun DshTask.titleText(context: Context): String {
    val base = when (kind) {
        DshTask.Kind.BOOTSTRAP -> context.getString(R.string.dsh_task_bootstrap)
        else -> listOf(title, stage).filter { it.isNotBlank() }.joinToString(" · ")
    }
    return if (state == DshTask.State.CANCELLED) {
        context.getString(R.string.dsh_task_cancelled_suffix, base)
    } else {
        base
    }
}
