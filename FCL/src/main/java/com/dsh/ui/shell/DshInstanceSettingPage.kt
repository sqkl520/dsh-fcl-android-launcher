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
 * 文本输入用 FCL 的 [EditDialog]（fix2 / BL-089 删掉 Profile 行后，本页已不用单选框）。
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
        // 免得出现"标题写启动、按钮写停止"这种自相矛盾的行（FCL 的动作行标题与按钮同文案）。
        // ★ fix2 / BL-127（F2-15c 同族）：文案判据统一用 DshRuntime.activeInstanceId() ——
        //   "在跑" = Starting / Running / Stopping 且归属本实例，与 onAction 的点击判据同一表达。
        //   旧判据只看 Running：Starting 期文案显示「启动」，点击被 isBusy 静默吞掉 ——
        //   "显示能启动、实际什么都不会发生"，正是 V8-3 的名实两张皮。
        val active = DshRuntime.activeInstanceId() == instanceId
        val actionText = when {
            active -> R.string.dsh_action_stop
            // 安装中：没有可启动的东西（与实例列表页的 busy 语义一致）。按钮切成状态词
            // 「安装中」，点击静默不做（见 onAction）—— 不出现"写着启动、点不动"的形态。
            inst?.state == DshInstance.State.INSTALLING -> R.string.dsh_state_installing
            else -> R.string.dsh_action_start
        }
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
            // 打开界面：**运行中或启动/停止中可点**。用 `editable` 表达可点性 —— 值行布局只有
            // 这一个可点提示（右侧图标），不可点时整行不响应、图标也不显示，语义正好。
            // ★ fix2 / BL-125 半（#11 的详情页侧）：旧判据只有 running，Starting 期用户一旦离开
            //   WebView 页就再也进不去（唯一入口是 onStarted 的一次性跳转，V8-1）；而 WebView 页
            //   自身对 Starting 渲染完好（显示「启动中」面板，含停止/日志按钮）—— 门禁拦在入口
            //   是疏漏、不是设计。Stopping 一并放开：点进去看到「停止中」面板，无害。
            //   安装中不放开：没有界面可开。
            DshInstanceSettingAdapter.Row.Value(
                R.string.dsh_action_open_web, 0,
                { webUiText() }, DshInstanceSettingAdapter.Tag.OPEN_WEB,
                editable = running || isBusy()
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
        //
        // ★ 同样地，这里没有 Profile 行（fix2 / BL-089 删除 —— 这是本文件 set* 行里最后
        //   一个死开关）：启动脚本对 $PROFILE **零引用**（start-dsh.sh 里只有注释提到它，
        //   实际启动命令硬编码 `dsh web`），改 Profile 不产生任何效果。等哪天脚本真的按
        //   $PROFILE 分派（web / headless），再从 dsh 自己的设置面或这里重新接。
        //   `DshInstance.profile` 字段**保留不动**：instances.json 里已有数据不能丢，
        //   且 DshRuntime 仍把它注进启动环境（见 DshRuntime 的 env 组装）—— 是"列而不注"
        //   的反面例子之后再接线时可以复用这条通路。
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

    /**
     * 本实例是否处于**启动中 / 停止中**。
     *
     * fix2 起这只剩一个用途：决定「打开界面」行可不可点（Starting 期也要能点进去看
     * 「启动中」面板，修 V8-1；Stopping 顺带放开，无害）。行动按钮的判据已改走
     * `DshRuntime.activeInstanceId()` —— 那里的口径是"在跑"（Starting/Running/Stopping），
     * 与这里"过渡态"（Starting/Stopping）不是同一件事，别混用。
     */
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

            DshInstanceSettingAdapter.Tag.PORT -> editText(
                context.getString(R.string.dsh_settings_row_port),
                if (i.port > 0) i.port.toString() else "",
                allowEmpty = true
            ) { text ->
                // 空串是对这把输入框**合法**的输入（= 自动分配端口），所以这里必须
                // 走 allowEmpty 通路收下它，再按 blank→0 解释（parsePortInput，fix2 / BL-090）。
                val port = parsePortInput(text)
                if (port == null) {
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

            // 打开界面：运行中/启动中/停止中才会走到这里（`editable` 按 running || isBusy() 设置）
            DshInstanceSettingAdapter.Tag.OPEN_WEB -> host.openWebView()

            else -> Unit
        }
    }

    // ===== 动作行动作 =====

    private fun onAction(row: DshInstanceSettingAdapter.Row.Action) {
        val i = inst ?: return
        when (row.tag) {
            DshInstanceSettingAdapter.Tag.START_STOP -> {
                // fix2 / BL-127：判据与文案、点击行为**同源**（DshRuntime.activeInstanceId()）。
                // 旧实现"文案看 Running、点击看 isBusy + Running"两张皮：Starting 期写着「启动」，
                // 点下去被 busy 静默吞掉（V8-3）。现在 Starting/Stopping 也计入"在跑"（文案「停止」），
                // 点击落到同一个停止动作上。
                if (DshRuntime.activeInstanceId() == i.id) {
                    // 在跑（含 Starting）：停。★ 用 stopIf 而不是 stop()：stop() 停的是"当前在跑的
                    // 那个"，从行渲染到用户点击之间状态可能已换实例（TOCTOU），stopIf 在运行时
                    // 同一把锁里重新判定"跑的是不是它"，不会误停别的实例。
                    // Stopping 期点击会在 stopIf 内部判 false（已在停、无物可停）——此刻文案也是
                    // 「停止」，重复下发只会让运行时收到自相矛盾的指令，静默忽略是对的。
                    DshRuntime.stopIf(i.id)
                } else if (i.state == DshInstance.State.INSTALLING) {
                    // 安装中：与列表页 busy 语义一致（安装中本来就没有可启动的东西），点击静默不做。
                    // 文案已切成「安装中」（runRows），不存在"写着启动、点击无效"的干扰。
                    return
                } else {
                    startInstance(i)
                }
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

    /**
     * 文本输入：用 FCL 的 EditDialog（与 FCL 设置页的编辑行同款）。
     *
     * @param allowEmpty 空白确认是否有效（fix2 / BL-090）：端口行传 true（空串 = 自动分配，
     *   由 onOk 收到 "" 后走 [parsePortInput] 的 blank→0 分支）；其余行保持默认 false
     *   （空白确认时 EditDialog 弹提示、不关闭，见 [EditDialog] 的 allowEmpty 参数）。
     */
    private fun editText(
        title: String,
        initial: String,
        allowEmpty: Boolean = false,
        onOk: (String) -> Unit
    ) {
        EditDialog(context, initial, allowEmpty) { text -> onOk(text) }
            .apply { setTitle(title) }
            .show()
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

/**
 * 端口输入框的解析口径（fix2 / BL-090 从端口行点击处理里抽出的纯逻辑，唯一调用方便是它）。
 *
 * - **空串/纯空白 = 自动分配**（约定值 0）。这是端口行 `allowEmpty = true` 通路的意义：
 *   用户清空输入框再点确认 → EditDialog 回传 "" → 这里给出 0 → `updateConfig(port = 0)` →
 *   显示回「自动」。BL-090 修的就是这条路径此前不可达（空白确认被 EditDialog 静默吞掉）。
 * - 非空白：必须解析成 0..65535 的整数，否则 `null`（调用方弹 [R.string.dsh_port_invalid]）。
 *   `"0"` 本身也合法 —— 显式写 0 与留空同义（dsh 侧 `--port 0` 由系统挑端口）。
 *
 * 写成**顶层函数**：与 `isRowBusy`（DshInstanceAdapter.kt）/ `trimDeltaFor`（DshWebViewActivity.kt）
 * 同一处理 —— 单测 harness 无 androidx，经页面类访问会被 AppCompat 父类链加载拖崩；
 * 顶层函数无类初始化负担，`W2CTest` 可直接调。
 */
fun parsePortInput(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    val port = trimmed.toIntOrNull() ?: return null
    return if (port in 0..65535) port else null
}
