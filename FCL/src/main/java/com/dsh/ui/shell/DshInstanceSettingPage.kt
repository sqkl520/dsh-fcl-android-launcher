package com.dsh.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.core.DshBootstrap
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
import com.dsh.core.DshServices
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.UiDshInstanceDetailBinding
import com.dsh.ui.DshInstanceSettingAdapter
import com.mio.dialog.ItemSelectionDialog
import com.mio.ui.adapter.SpacingItemDecoration
import com.tungsten.fcllibrary.component.dialog.EditDialog
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 实例详情里「运行」/「配置」两段的**行式设置列表**（[DshInstanceDetailPage] 的 tab 0 与 tab 2）。
 *
 * ## 为什么两段共用一个类
 * 它们除了"提交哪些行"以外**没有任何差别**：同一个 RecyclerView、同一套
 * [DshInstanceSettingAdapter]、同一套 FCL 对话框、同一条 `DshRuntime.state` 订阅。
 * 拆成两个类只会把这段逻辑抄两份，然后慢慢漂移；分段用一个枚举参数表达，加一行/改一行只有一处要动。
 * （「日志」段不在这里 —— 它整段就是 [DshLogsUI]，没有可配置的东西。）
 *
 * ## 结构（照 FCL 的版本设置页）
 * `RecyclerView` + [DshInstanceSettingAdapter]（分组 / 当前值行 / 按钮行）+
 * [SpacingItemDecoration]（组内 1dp 细缝并绘制分割线、组间 8dp）+ 位置感知圆角。
 * 文本输入用 FCL 的 [EditDialog]，单选用 FCL 的 [ItemSelectionDialog]。
 *
 * ## 相对改造前那页的差异（逐条都是有意为之）
 * - **API Key 整段已移除**：Key 改由 dsh 自己的页面录入（那边功能更全：能测连通、能换模型）。
 *   启动器不再持有这条录入路径，也没有任何持有方式了（DshCredentials 已随本批删除）——
 *   注进子进程环境会让 dsh 的写入被判为"被环境遮蔽"而直接报错，见 [DshLauncher.startInstance]。
 * - **没有"查看日志"行**：日志不再是"跳去别的页面的动作"，它就是详情页的一个 tab ——
 *   页面内的事不该做成一行按钮。
 * - **多了状态 / 启动停止 / 打开界面三行**：改造前这三件事只存在于实例列表行和外壳右面板，
 *   进了详情页反而做不了（详情页是孤岛）。现在它们是这一页的主操作。
 *
 * @param onOpenTab 切到详情页的另一个 tab（传 [DshInstanceDetailPage] 的 TAB_* 常量）：
 *   启动失败时的「查看日志」就落在**同页的另一个 tab**（原本是要 `startActivity` 去别处的）。
 *
 * 注意这里**没有**"实例没了就关掉整页"的回调：那是 [DshInstanceDetailPage] 的职责（它订阅
 * 实例清单，实例消失就退层）。本页只管自己这一段的行；实例被删后本页会随详情页一起被销毁。
 */
class DshInstanceSettingPage(
    context: Context,
    private val host: DshShellHost,
    private val instanceId: String,
    private val section: Section,
    private val onOpenTab: (Int) -> Unit = {}
) : DshPageUI(context, R.layout.ui_dsh_instance_detail) {

    /** 本页负责详情页里的哪一段 */
    enum class Section { RUN, CONFIG }

    private val binding = UiDshInstanceDetailBinding.bind(contentView)
    private lateinit var adapter: DshInstanceSettingAdapter

    /** 当前实例。订阅 [DshInstances.instances] 保持最新 —— 行里的值通过 lambda 现取，所以总是当次绑定时的真值 */
    private var inst: DshInstance? = null

    /** 当前运行状态。同样由订阅维护，供状态行 / 启动停止行 / 打开界面行取值 */
    private var runtimeState: DshRuntime.State = DshRuntime.State.Idle

    /** 体积是异步算出来的，缓存在这里供行绑定取值 */
    private var diskText: String = ""

    /** 主题变化时重绘分割线（FCL 同款做法） */
    private val themeInvalidate = Runnable { binding.settingList.invalidate() }

    override fun onCreate() {
        super.onCreate()
        DshPaths.loadPaths(context)
        DshInstances.init()
        inst = DshInstances.byId(instanceId)

        adapter = DshInstanceSettingAdapter(context, ::onValue, ::onAction)
        binding.settingList.layoutManager = LinearLayoutManager(context)

        // FCL 的分组间距：组间 8dp；同组相邻行只留 1dp 细缝并绘制主题色分割线
        val rowSpacing = dp(8)
        binding.settingList.addItemDecoration(
            SpacingItemDecoration(
                rowSpacing,
                { parent, position ->
                    val a = parent.adapter as? DshInstanceSettingAdapter
                    if (a?.isNextInSameGroup(position) == true) dp(1) else rowSpacing
                },
                true,
                // ★ 取色走 getColor()（按当前亮暗模式取主色），不能用原始 .color：
                //   原始值只跟着「设置里改了什么」变、不跟亮暗模式变（getColor() 才会按模式在
                //   color / colorDark 之间取）。用原始值画分割线，暗色模式下拿到的还是亮色那一套，
                //   在深色底上会过亮甚至看不见。（FCL 的 VersionSettingPage 也是 getColor()。）
                { ThemeEngine.getInstance().getTheme().getColor() }
            )
        )
        ThemeEngine.getInstance().registerEvent(binding.settingList, themeInvalidate)
        binding.settingList.adapter = adapter

        diskText = context.getString(R.string.dsh_size_calculating)
        submitRows()

        observeInstance()
        observeRuntime()
        showDiskUsage()
    }

    override fun onDestroy() {
        ThemeEngine.getInstance().unregisterEvent(binding.settingList)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    // ===== 订阅 =====

    /**
     * 实例列表。用途是**外部改动后刷新本页**：改名/换模型/端口被运行时回写，都会从这条流进来。
     *
     * 实例被删掉（`inst == null`）时**什么都不做** —— 那是详情页整层的事（它订阅同一条流并退层），
     * 本页跟着一起被销毁。在这里自己收摊会引出"一个 tab 子页把整页弹掉"这种越权路径，
     * 而且停在别的 tab 时根本走不到这段代码。
     */
    private fun observeInstance() {
        scope.launch {
            DshInstances.instances.collect { list ->
                inst = list.firstOrNull { it.id == instanceId }
                submitRows()
            }
        }
    }

    /** 运行状态：状态行、启动/停止行的文案、打开界面行能不能点，都由它决定 */
    private fun observeRuntime() {
        scope.launch {
            DshRuntime.state.collect { st ->
                runtimeState = st
                submitRows()
            }
        }
    }

    // ===== 行构建 =====

    private fun submitRows() {
        if (inst == null) return
        adapter.submit(
            when (section) {
                Section.RUN -> runRows()
                Section.CONFIG -> configRows()
            }
        )
    }

    /** 「运行」段：它现在跑没跑起来、能不能开、底座/安装有没有问题 */
    private fun runRows(): List<DshInstanceSettingAdapter.Row> {
        val running = isRunning()
        // 启动/停止是同一行：按钮文案随状态翻转，行的标题也跟着翻，
        // 免得出现"标题写启动、按钮写停止"这种自相矛盾的行（FCL 的动作行标题与按钮同文案）
        val actionText = if (running) R.string.dsh_action_stop else R.string.dsh_action_start
        return listOf(
            // 组头用 `dsh_settings_section_*` 这一族（它们就是为分组标题准备的），
            // 而不是拿 tab 文案 `dsh_instance_detail_tab_run` 顶替 —— 后者是 tab 标签，
            // 放在 tab 正下方当小标题是同一句话连说两遍
            DshInstanceSettingAdapter.Row.Group(R.string.dsh_settings_section_instance),
            DshInstanceSettingAdapter.Row.Value(
                R.string.dsh_instance_status_row, 0,
                { statusText() }, DshInstanceSettingAdapter.Tag.STATUS, editable = false
            ),
            DshInstanceSettingAdapter.Row.Action(
                actionText, 0, actionText, DshInstanceSettingAdapter.Tag.START_STOP
            ),
            // 打开界面：**仅运行中可点**。用 `editable` 表达可点性 —— 值行布局只有这一个
            // 可点提示（右侧图标），不可点时整行不响应、图标也不显示，语义正好。
            DshInstanceSettingAdapter.Row.Value(
                R.string.dsh_action_open_web, 0,
                { webUiText() }, DshInstanceSettingAdapter.Tag.OPEN_WEB, editable = running
            ),

            DshInstanceSettingAdapter.Row.Group(R.string.dsh_settings_section_env),
            DshInstanceSettingAdapter.Row.Value(
                R.string.dsh_settings_row_version, 0,
                { inst?.dshVersion ?: "-" }, DshInstanceSettingAdapter.Tag.VERSION, editable = false
            ),
            DshInstanceSettingAdapter.Row.Value(
                R.string.dsh_settings_row_disk, 0,
                { diskText }, DshInstanceSettingAdapter.Tag.DISK, editable = false
            ),
            DshInstanceSettingAdapter.Row.Value(
                R.string.dsh_settings_row_path, R.string.dsh_settings_desc_path,
                { DshPaths.instanceDir(instanceId).absolutePath },
                DshInstanceSettingAdapter.Tag.PATH
            ),
            DshInstanceSettingAdapter.Row.Action(
                R.string.dsh_action_verify_runtime, R.string.dsh_settings_desc_verify,
                R.string.dsh_action_verify_runtime, DshInstanceSettingAdapter.Tag.VERIFY
            ),
        )
    }

    /** 「配置」段：改了要重启才生效的东西 + 维护动作 */
    private fun configRows(): List<DshInstanceSettingAdapter.Row> = listOf(
        DshInstanceSettingAdapter.Row.Group(R.string.dsh_settings_section_instance),
        DshInstanceSettingAdapter.Row.Value(
            R.string.dsh_settings_row_name, R.string.dsh_settings_desc_name,
            { inst?.name ?: "" }, DshInstanceSettingAdapter.Tag.NAME
        ),
        // ★ 这里没有"模型"行（批次 5 删除）：启动器**从来没能**决定 dsh 用哪个模型。
        // 它原来只是把实例配置里的 model 注成环境变量 DEEPSEEK_DEFAULT_MODEL，而上游根本没有
        // 读这个名字（它只是 web-search 插件里的一个导出常量，见
        // dsh/packages/web/web-search-deepseek/src/provider.ts），于是这一行是个死开关：
        // 改完看不出任何效果，用户还会以为自己已经换过模型了。
        // 模型现在只由 dsh 自己的「设置 → 模型」决定，两处都能设不如只留一处。
        // `DshInstance.model` 字段本身保留（删字段要写数据迁移，不值得），它现在只是遗留字段。
        DshInstanceSettingAdapter.Row.Value(
            R.string.dsh_settings_profile, R.string.dsh_settings_desc_profile,
            { inst?.profile ?: "" }, DshInstanceSettingAdapter.Tag.PROFILE
        ),
        DshInstanceSettingAdapter.Row.Value(
            R.string.dsh_settings_row_port, R.string.dsh_settings_desc_port,
            {
                val p = inst?.port ?: 0
                if (p > 0) p.toString() else context.getString(R.string.dsh_port_auto)
            },
            DshInstanceSettingAdapter.Tag.PORT
        ),
        DshInstanceSettingAdapter.Row.Group(R.string.dsh_settings_section_maintain),
        DshInstanceSettingAdapter.Row.Action(
            R.string.dsh_action_reinstall, R.string.dsh_settings_desc_reinstall,
            R.string.dsh_action_reinstall, DshInstanceSettingAdapter.Tag.REINSTALL
        ),
        DshInstanceSettingAdapter.Row.Action(
            R.string.dsh_action_delete, R.string.dsh_settings_desc_delete,
            R.string.dsh_action_delete, DshInstanceSettingAdapter.Tag.DELETE
        ),
    )

    // ===== 状态文案 =====

    /** 本实例是否正在运行（运行中的实例只可能有一个，所以要连 id 一起判） */
    private fun isRunning(): Boolean =
        runtimeState.let { it is DshRuntime.State.Running && it.instanceId == instanceId }

    /** 启动/停止进行中的实例：此时按钮不该再响应（页面没有"禁用行"的能力，所以按下去直接忽略） */
    private fun isBusy(): Boolean = when (val st = runtimeState) {
        is DshRuntime.State.Starting -> st.instanceId == instanceId
        is DshRuntime.State.Stopping -> st.instanceId == instanceId
        else -> false
    }

    /**
     * 状态行文案：**运行态优先**（那是用户此刻最关心的），没有运行态时才回落到安装状态。
     * 两者必须分开看：一个实例可以"已就绪"但当前没跑，也可以"跑着但安装记录是坏的"。
     *
     * 注意每个运行态都要**连 instanceId 一起判**：`DshRuntime` 是单实例策略，它当前持有的是
     * 某个实例的状态，不一定是我们这一个。不判的话，打开实例 B 的详情会看到实例 A 的运行状态。
     */
    private fun statusText(): String {
        val live: String? = when (val st = runtimeState) {
            is DshRuntime.State.Running ->
                if (st.instanceId == instanceId)
                    context.getString(R.string.dsh_state_running_short, st.name, st.port) else null

            is DshRuntime.State.Starting ->
                if (st.instanceId == instanceId)
                    context.getString(R.string.dsh_state_starting, st.name) else null

            is DshRuntime.State.Stopping ->
                if (st.instanceId == instanceId)
                    context.getString(R.string.dsh_state_stopping, st.name) else null

            is DshRuntime.State.Failed ->
                if (st.instanceId == instanceId)
                    context.getString(R.string.dsh_state_failed, st.reason) else null

            is DshRuntime.State.Exited ->
                if (st.instanceId == instanceId)
                    context.getString(R.string.dsh_runtime_exited, st.code) else null

            DshRuntime.State.Idle -> null
        }
        if (live != null) return live

        return when (inst?.state) {
            DshInstance.State.NOT_INSTALLED -> context.getString(R.string.dsh_state_not_installed)
            DshInstance.State.INSTALLING -> context.getString(R.string.dsh_state_installing)
            DshInstance.State.READY -> context.getString(R.string.dsh_state_ready)
            DshInstance.State.BROKEN -> context.getString(R.string.dsh_state_broken)
            null -> context.getString(R.string.dsh_state_not_installed)
        }
    }

    /**
     * 打开界面行的右侧值。
     *
     * 运行中显示**去掉 query 的**地址：`DshRuntime` 给的就绪 URL 形如
     * `http://127.0.0.1:3080/?token=XXX`，token 是本次会话的凭据，不该铺在列表里被截图带走。
     * 没跑起来时给一个破折号（与"已安装 dsh"行未安装时的占位同一写法），而不是"未运行" ——
     * 这一行是"打开界面"，值位该说的是**打开什么**；状态已经由上面那一行负责，
     * 同一件事写两遍只会让列表更长。
     */
    private fun webUiText(): String {
        val st = runtimeState
        return if (st is DshRuntime.State.Running && st.instanceId == instanceId) {
            st.url?.substringBefore('?') ?: st.port.toString()
        } else {
            "-"
        }
    }

    // ===== 值行动作 =====

    private fun onValue(row: DshInstanceSettingAdapter.Row.Value) {
        val i = inst ?: return
        when (row.tag) {
            DshInstanceSettingAdapter.Tag.NAME -> editText(
                context.getString(R.string.dsh_settings_row_name), i.name
            ) { text ->
                if (text.isBlank()) {
                    toast(context.getString(R.string.dsh_name_required))
                } else {
                    DshInstances.updateConfig(i.id, name = text.trim())
                    reload()
                }
            }

            DshInstanceSettingAdapter.Tag.PROFILE -> pick(
                context.getString(R.string.dsh_settings_profile), DshInstance.PROFILES, i.profile
            ) { value ->
                DshInstances.updateConfig(i.id, profile = value)
                reload()
            }

            DshInstanceSettingAdapter.Tag.PORT -> editText(
                context.getString(R.string.dsh_settings_row_port),
                if (i.port > 0) i.port.toString() else ""
            ) { text ->
                val port = if (text.isBlank()) 0 else text.trim().toIntOrNull()
                if (port == null || port < 0 || port > 65535) {
                    toast(context.getString(R.string.dsh_port_invalid))
                } else {
                    DshInstances.updateConfig(i.id, port = port)
                    reload()
                }
            }

            DshInstanceSettingAdapter.Tag.PATH -> {
                val path = DshPaths.instanceDir(i.id).absolutePath
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dsh instance path", path))
                toast(context.getString(R.string.dsh_settings_desc_path))
            }

            // 打开界面：只有运行中才会走到这里（`editable` 已按运行态设置）
            DshInstanceSettingAdapter.Tag.OPEN_WEB -> host.openWebView()

            else -> Unit
        }
    }

    // ===== 动作行动作 =====

    private fun onAction(row: DshInstanceSettingAdapter.Row.Action) {
        val i = inst ?: return
        when (row.tag) {
            DshInstanceSettingAdapter.Tag.START_STOP -> {
                // 启动中/停止中不接受第二次点击：状态行已经把这件事说清楚了，重复下发只会
                // 让运行时收到自相矛盾的指令
                if (isBusy()) return
                if (isRunning()) DshRuntime.stop() else startInstance(i)
            }
            DshInstanceSettingAdapter.Tag.VERIFY -> verifyRuntime()
            DshInstanceSettingAdapter.Tag.REINSTALL -> reinstall(i)
            DshInstanceSettingAdapter.Tag.DELETE -> confirmDelete(i)
            else -> Unit
        }
    }

    /**
     * 启动实例 —— 走的是与实例列表、外壳右面板**同一份** [DshLauncher.startInstance]，
     * 参数也保持一致（否则同一件事会出现三种行为）。
     *
     * 与列表页唯一不同的接线是 `onOpenLogs`：它是本页的一个 tab，而不是另一个页面。
     */
    private fun startInstance(i: DshInstance) {
        DshLauncher.startInstance(
            activity = host.activity,
            inst = i,
            scope = scope,
            onOpenLogs = { onOpenTab(DshInstanceDetailPage.TAB_LOGS) },
            onPrepareRuntime = { DshLauncher.openSetup(host.activity) },
            onStarted = { host.openWebView() }
        )
    }

    // ===== 各动作的具体实现（与改造前一致）=====

    /** 文本输入：用 FCL 的 EditDialog（与 FCL 设置页的编辑行同款） */
    private fun editText(title: String, initial: String, onOk: (String) -> Unit) {
        EditDialog(context, initial) { text -> onOk(text) }.apply { setTitle(title) }.show()
    }

    /** 单选：用 FCL 的 ItemSelectionDialog */
    private fun pick(title: String, options: List<String>, current: String, onPick: (String) -> Unit) {
        ItemSelectionDialog(context, title, options, true, options.indexOf(current)) { _, value ->
            onPick(value)
        }.show()
    }

    private fun verifyRuntime() {
        toast(context.getString(R.string.dsh_verify_running))
        scope.launch {
            val report = withContext(Dispatchers.IO) { DshBootstrap.verify(context) }
            FCLAlertDialog.Builder(host.activity)
                .setAlertLevel(
                    if (report.ok) FCLAlertDialog.AlertLevel.INFO else FCLAlertDialog.AlertLevel.ALERT
                )
                .setTitle(context.getString(R.string.dsh_verify_title))
                .setMessage(report.detail)
                .setNegativeButton(context.getString(R.string.dialog_positive), null)
                .create()
                .show()
        }
    }

    /**
     * 重新安装。
     *
     * 没装成功过任何版本时**不直接跳走**，而是弹一个带「下载」按钮的确认框 —— 与实例列表页
     * 同一处理：跳走会把用户正在看的详情页顶掉，而且他没得选（必须先去版本页挑一个）。
     */
    private fun reinstall(i: DshInstance) {
        val version = i.dshVersion
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
        DshServices.installer(context).install(i, version)
        toast(context.getString(R.string.dsh_install_started, version))
    }

    private fun confirmDelete(i: DshInstance) {
        FCLAlertDialog.Builder(host.activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(context.getString(R.string.dsh_delete_title))
            .setMessage(context.getString(R.string.dsh_delete_message, i.name))
            .setPositiveButton(context.getString(R.string.dsh_action_delete)) {
                // ★ 这里**故意**不再单独清 API Key：Key 归 dsh 管，存在
                // $DSH_HOME/.credentials.yaml（即 <实例目录>/home/.credentials.yaml），
                // 而删除会把整个实例目录 deleteRecursively()，它自然一起消失。
                // 删除是**同步**摘清单 + 异步删目录：实例一从清单消失，详情页订阅到就会退层，
                // 本页随之被销毁 —— 所以这里不需要、也不该自己去关页面（那是详情页整层的事）。
                DshInstances.delete(i.id)
            }
            .setNegativeButton(context.getString(R.string.dialog_negative), null)
            .create()
            .show()
    }

    // ===== 杂项 =====

    /** 重新读取实例并刷新（配置改动后立即反馈；订阅也会刷新，这里是双保险） */
    private fun reload() {
        inst = DshInstances.byId(instanceId)
        submitRows()
    }

    /**
     * 统计体积。回调在 IO 线程上，所以借页面自己的 [scope] 切回主线程 ——
     * 顺带拿到一个好处：页面被回收后作用域已取消，迟到的结果不会再往一个死页面上写。
     */
    private fun showDiskUsage() {
        diskText = context.getString(R.string.dsh_size_calculating)
        DshInstances.diskUsageAsync(instanceId) { bytes ->
            scope.launch {
                diskText = DshPaths.formatSize(bytes)
                submitRows()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
