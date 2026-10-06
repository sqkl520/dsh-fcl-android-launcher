package com.dsh.ui.shell

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.fcl.androidlauncher.R
import kotlinx.coroutines.launch

/**
 * 实例详情页 —— 压在外壳「实例」tab 上的**临时页**（不再是一个独立 Activity）。
 *
 * ## 为什么必须搬进外壳
 * 改造前它是一个独立 Activity（`DshSettingsActivity`），那带来两条硬伤，而且都是"把页内下钻
 * 做成并列 Activity"这个形态本身造成的：
 *
 * 1. **它不实现 [DshShellHost]** → 页内做不了任何外壳内导航。想"看这个实例的日志"只能
 *    `startActivity(intentForTab(..., CLEAR_TOP))` —— 那是把整个外壳弹栈重建一次来换一个 tab，
 *    用户看到的是一次闪断重进。
 * 2. **它没有返回按钮**（亮色主题下还整页全白）。外壳左菜单那条返回项属于外壳，被详情页盖住时
 *    根本够不着；而它自己又不提供替代入口，于是只剩系统返回键可退。
 *
 * 变成临时页后两条同时消失：本页挂在「实例」页的覆盖层上，外壳的返回链天然覆盖它
 * （②③④ 级里 ③ 就是"当前页的临时页栈还能弹 → 弹一层并消费"），导航也回到外壳内部 ——
 * 要跳日志不再需要重建外壳，因为日志就是本页的一个 tab。
 *
 * ## 页内 3 tab（顺序固定：运行 / 日志 / 配置）
 * 一个实例 = 一个页面，页内再用 tab 分三段。分法来自"用户打开详情想干什么"：
 * - **运行**：它现在跑起来了吗、能不能开、端口是多少、底座/安装有没有问题；
 * - **日志**：它这次运行到底输出了什么（直接复用 [DshLogsUI]，只喂本实例的 id）；
 * - **配置**：名字/模型/profile/端口这类"改了要重启才生效"的东西，以及重装/删除。
 *
 * ## 内容视图为什么要包一层 wrapper
 * 祖父类 `FCLBaseUI` 的构造链是 `FCLCommonUI(context, layoutId)` → `setContentView(id)` →
 * `LayoutInflater.inflate(id, null)`，`getContentView()` 返回的就是那棵多页骨架。
 *
 * 本页把 `getContentView()` 换成一层 wrapper，原因有两条：
 * 1. **给外层栈一个稳定的压栈对象**：临时页栈收的是 `View`，而它需要一个"整页级别"的容器
 *    （将来要在页级加遮罩、页级 Snackbar 时，往 wrapper 里塞就行，不用去动多页骨架）；
 * 2. **`getContentView()` 的返回对象在本页生命周期内恒定**：多页骨架内部会往自己的
 *    `container` 里增删视图（pager / 覆盖层 / 临时页），直接把它当"本页的门面"暴露出去，
 *    等于把内部结构的可变性泄漏给了外层。
 *
 * 注意 wrapper **不改变**多页骨架的行为：骨架仍然是 `FCLBaseUI` 私有字段 `contentView` 指着的
 * 那棵树，`findViewById`（`final`，读的是那个私有字段）照常能找到 `tab_layout` / `container`。
 */
class DshInstanceDetailPage(
    context: Context,
    private val host: DshShellHost,
    /**
     * 本页展示哪个实例。
     *
     * **公开**（不是 `private val`）：外层栈要能回答"栈上这一层是哪个实例的详情" ——
     * 同一个实例的详情已经在栈上时不该再压一层（见 [DshInstancesUI.showInstanceDetailAt]）。
     */
    val instanceId: String,
    private val initialTab: Int = TAB_RUN,
    /**
     * 实例被删除后离开本页。参数就是本页自身 —— 外层据此确认"要退的正是这一层"。
     *
     * 本页是**外层**（[DshInstancesUI]）栈上的临时页，自己没有能力把自己弹掉：本类继承的
     * `DshMultiPageUI` 持有的那份栈是"本页自己的 3 个 tab 子页"用的，两回事。
     * 所以"离开"这件事必须由外层给一个回调。
     */
    private val onClose: (DshInstanceDetailPage) -> Unit = {}
) : DshMultiPageUI(context) {

    /**
     * 本页的真实内容视图 = 一层 wrapper，里面才是多页骨架的 root。
     *
     * ⚠️ 属性初始化顺序：Kotlin 的顺序是「基类构造 → 子类属性初始化 → 子类 init 块」，
     * 所以 [wrapper] 在下面的 `init` 之前已经可用；而 `super.getContentView()` 此刻返回的是
     * `FCLCommonUI` 构造里 inflate 出来的多页骨架 root（非 null）—— 这个顺序是安全的。
     */
    private val wrapper = FrameLayout(context)

    init {
        // 把基类 inflate 出来的多页骨架收进 wrapper，让 getContentView() 对外只有一个稳定对象
        wrapper.addView(
            super.getContentView(),
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    /**
     * ★ 覆写祖父类 `FCLBaseUI` 的 `getContentView()`。
     *
     * ⚠️ Kotlin 里覆写 Java getter 必须用**函数形式** `override fun getContentView()`；
     * 写成 `override val contentView` 会报 `'contentView' overrides nothing`（实测）。
     */
    override fun getContentView(): View = wrapper

    // ---------------------------------------------------------------- 页内 3 tab

    override val pageCount: Int = 3

    override fun tabTitle(position: Int): Int = when (position) {
        TAB_RUN -> R.string.dsh_instance_detail_tab_run
        TAB_LOGS -> R.string.dsh_instance_detail_tab_logs
        else -> R.string.dsh_instance_detail_tab_config
    }

    override fun tabIcon(position: Int): Int = when (position) {
        TAB_RUN -> R.drawable.ic_start
        TAB_LOGS -> R.drawable.ic_dsh_logs_24
        else -> R.drawable.ic_baseline_settings_24
    }

    override fun createPage(position: Int): DshPageUI = when (position) {
        // 实例日志 = 同一个日志视图，只是喂进本实例的 id（App 级全局日志在设置页里）
        TAB_LOGS -> DshLogsUI(context, instanceId)
        TAB_RUN -> settingPage(DshInstanceSettingPage.Section.RUN)
        else -> settingPage(DshInstanceSettingPage.Section.CONFIG)
    }

    /**
     * 「运行」与「配置」共用一个轻量页面类，只靠 [DshInstanceSettingPage.Section] 决定提交哪些行。
     *
     * 为什么不给它们各写一个页面类：两者除了"行清单"以外**没有任何差别** —— 同一个 RecyclerView、
     * 同一套适配器、同一套对话框与协程订阅。拆成两个类只会把这段逻辑抄两份，然后慢慢漂移。
     * 分段放在一个类里，加一行/改一行只有一处要动。
     */
    private fun settingPage(section: DshInstanceSettingPage.Section) = DshInstanceSettingPage(
        context = context,
        host = host,
        instanceId = instanceId,
        section = section,
        // 启动流程里的「去配置」「查看日志」都指向**同页的另一个 tab**：
        // 那两处原来是要 startActivity 去别处的，现在都落在本页内
        onOpenTab = { showPage(it) }
    )

    override fun onCreate() {
        DshPaths.loadPaths(context)
        DshInstances.init()
        super.onCreate()
        // 从列表的「日志」菜单进来时要直接落在日志 tab（tab 0 已在 setupPages 里选中）
        showPage(initialTab)
        observeInstanceExists()
    }

    /**
     * 实例在别处被删掉时**自动退层**。
     *
     * 为什么这件事归本页而不是归某个 tab 子页：本页是"一个实例"的载体 —— 实例没了，本页就
     * 没有存在的理由，与用户当时停在哪个 tab 无关（停在日志段时子页根本订阅不到实例列表）。
     * 让每个子页各自判断，就会出现"停在日志段时实例被删了、页面还留着"这种洞。
     *
     * 为什么是订阅而不是"删除按钮里直接关掉"：删除也可能是从别处发起的（实例列表的删除、
     * 未来别的入口），订阅是唯一能覆盖全部来源的做法。删除按钮那条路径不需要额外处理 ——
     * [com.dsh.core.DshInstances.delete] 会**同步**把实例从清单里摘掉，本订阅当帧就会收到。
     *
     * [closeRequested] 是必需的：`onClose` 会把这页销毁掉，而销毁会取消本协程；
     * 在取消生效前流若又发了一帧（例如删除后紧接着又有别的清单变更），就会第二次请求退层 ——
     * 那时外层表里已经没有本页，它的清点逻辑会挡住误弹，但多发一次请求本身就是不该有的行为。
     */
    private var closeRequested = false

    private fun observeInstanceExists() {
        scope.launch {
            DshInstances.instances.collect { list ->
                if (closeRequested) return@collect
                if (list.none { it.id == instanceId }) {
                    closeRequested = true
                    onClose(this@DshInstanceDetailPage)
                }
            }
        }
    }

    /**
     * 切到本页的某一段（传 `TAB_*` 常量）。
     *
     * 给外层栈用：同一个实例的详情已经在栈上时，用户这次点的是"看日志"而不是"再看一次详情"，
     * 那就**复用**栈上这一层、切它的段，而不是再压一层（见 [DshInstancesUI.showInstanceDetailAt]）。
     *
     * ⚠️ 必须等 [onCreate] 之后才调 —— [DshMultiPageUI.showPage] 读的是 `setupPages()` 建出来的
     * pager，未初始化时会撞上 lateinit。外层压栈前一定会调 `onCreate()`，所以这条约束成立。
     */
    fun showTab(tab: Int) = showPage(tab)

    companion object {
        /**
         * tab 下标。**公开**是必需的：调用方要能指定"打开详情并落在哪一段"
         * （实例行的「日志」菜单 → 日志 tab、「设置」菜单 → 配置 tab）。
         */
        const val TAB_RUN = 0
        const val TAB_LOGS = 1
        const val TAB_CONFIG = 2
    }
}
