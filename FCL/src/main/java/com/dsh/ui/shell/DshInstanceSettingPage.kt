package com.dsh.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.core.DshBootstrap
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
import com.dsh.core.DshServices
import com.dsh.core.ProotCommand
import com.dsh.core.UrlScanner
import com.dsh.core.resolveHomeRootfs
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.UiDshInstanceDetailBinding
import com.dsh.ui.DshInstanceSettingAdapter
import com.mio.ui.adapter.SpacingItemDecoration
import com.tungsten.fcllibrary.component.dialog.EditDialog
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 重装（BL-147）前等待旧进程离场的预算：上限与轮询间隔**照抄** [DshInstances.delete] 的
 * `DELETE_WAIT_TIMEOUT_MS`（8s，与 `DshRuntime.stopAndWait` 的默认预算一致）/ `DELETE_WAIT_POLL_MS`
 * （100ms）—— 那两个常量是 DshInstances 的 private，这里只复制数值与"轮询 + 超时"的姿态。
 */
private const val REINSTALL_STOP_WAIT_TIMEOUT_MS = 8_000L
private const val REINSTALL_STOP_WAIT_POLL_MS = 100L

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
 *   （fix8 / BL-168 又加了第四行「复制访问链接」：达标 WebView 的设备上没有任何出口能把**带 token**
 *   的地址交给外部浏览器，而本页能看到的又是剥掉 token 的裸地址 —— 缺口只能在这里补。）
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

    /**
     * 主线程 Handler（BL-148）：[DshInstances.delete] 的 onFinished 回调在 IO 线程（DshAppScope），
     * toast 必须切回主线程。用独立 Handler 而**不是**页面 [scope]：删除一开始实例就同步摘出清单、
     * 详情页随之退层被回收 —— 页面 scope 届时已被取消，挂着它的迟到反馈永远送不到用户眼前。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

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
        //   ★ A7（R24）：Stopping 从这条判据里**单独摘出**（见下面 stopping 分支）——
        //   它不再是"可以再点一次的停止"，而是一个不可点的过渡态。
        val active = DshRuntime.activeInstanceId() == instanceId
        // ★ A7（R24 / E6 §2.3）：停止中单独成一支 —— 文案「正在停止…」、按钮不可点。
        //   改前这一态落进 `active` 分支显示「停止」且可点，而 onAction 的 stopIf 在 Stopping 期
        //   返回 false、静默忽略 ⇒ 卡片（本轮起显示「正在停止…」）与详情页同屏两种说法，
        //   且详情页那种是"亮着可点、点了没反应"。两处必须同源。
        //   判据刻意用本页订阅的帧 [runtimeState]（不新读 activeInstanceId()）：这一支要判断的是
        //   "这一帧画出来的状态行说的是不是停止中"，与 statusText 同一份输入才不会自相矛盾。
        //   `active` 保持 BL-127 的原判据（activeInstanceId()）不动 —— 那是与点击同源的既有约定。
        val stopping = (runtimeState as? DshRuntime.State.Stopping)?.instanceId == instanceId
        // ★ fix12（E6-2 / §2.15）：动作行与**实例卡片同源**。改前这里只分四支
        //   （stopping / active / INSTALLING / 其余一律「启动」），而卡片是三分的
        //   （BROKEN→「重试」、NOT_INSTALLED→「安装」、其余→「启动」）—— 同一台实例，
        //   卡片说「未安装」+「安装」，点进详情却说「未安装」+ 满色「启动」，按下去才弹失败
        //   （E6 §2.3① 的原话就是"三处对不上"）。C-01 把一批实例从 READY 降成 NOT_INSTALLED 之后
        //   这条矛盾会按被降级实例数线性放大，所以必须同轮改。
        //
        // ★ 三分叉的判据用 [inst] 的持久状态、而不是 [runtimeState]：卡片读的也是它
        //   （DshInstanceAdapter :392-443），两处同源才不会漂移。
        //   「在跑」优先于持久状态：一个 READY 的实例被启动后 state 仍是 READY，但按钮必须是「停止」。
        //
        // ★ enabled 的口径（E6-6）：只有真正**可执行**的两支（停止 / 启动）可点；
        //   安装中 / 损坏 / 未安装都是"此刻点了没用"的分支，一律压暗 —— R24 A7 只做了停止中，
        //   安装中这一支当时漏了（满色可点、点了静默）。三个不可点分支点了给 toast 说明该去哪，
        //   而不是静默（见 [onAction]）。
        val state = inst?.state
        val actionText = when {
            stopping -> R.string.dsh_task_stopping
            active -> R.string.dsh_action_stop
            // 安装中：没有可启动的东西（与实例列表页的 busy 语义一致）。按钮切成状态词
            // 「安装中」，点击给一句说明（见 onAction）—— 不再是"写着启动、点不动/点了没反应"。
            state == DshInstance.State.INSTALLING -> R.string.dsh_state_installing
            // 损坏 → 与卡片同一句「重试」：真正的重试入口在卡片上（那里有确认框与停旧进程的
            // 完整流程），详情页只负责把话术对齐，点了指路而不是另开一条第二语义的重装路径。
            state == DshInstance.State.BROKEN -> R.string.dsh_action_retry
            // 未安装 → 与卡片同一句「安装」：卡片的「安装」是切到版本页挑一个版本装上，
            // 这里照抄同一个落点。
            state == DshInstance.State.NOT_INSTALLED -> R.string.dsh_action_install
            else -> R.string.dsh_action_start
        }
        // 可点性**与文案同一条 when 的分支**，不是另一套判据：两支可执行（停止 / 启动）给 true，
        // 其余三支（停止中 / 安装中 / 损坏 / 未安装）给 false。写成 `!stopping && state != …`
        // 的合取式在"进程在跑、但持久状态已被改成 INSTALLING"（重装窗口里的那一帧）会画出
        // 「停止」却点不动 —— 文案与可点性必须描述同一件事（E6-6 的教训正是这一条）。
        val actionEnabled = when {
            stopping -> false
            active -> true
            state == DshInstance.State.INSTALLING -> false
            state == DshInstance.State.BROKEN -> false
            state == DshInstance.State.NOT_INSTALLED -> false
            else -> true
        }
        // 「复制访问链接」这一行能不能点：以"此刻手里有没有带 token 的 URL"为准（fix8 / BL-168）。
        // 与 OPEN_WEB 的 editable 一样在提交行清单时求值（值位的文本才是绑定时现取）。
        val copyUrlReady = tokenUrl() != null
        // listOfNotNull 而不是 listOf：下面那条 lastError 行是**可选的**（有原因才出现），
        // 让"有没有这一行"由值本身（null）表达，调用处就不需要再写一次"什么时候该显示"的判断。
        return listOfNotNull(
            // 组头用 `dsh_settings_section_*` 这一族（它们就是为分组标题准备的），
            // 而不是拿 tab 文案 `dsh_instance_detail_tab_run` 顶替 —— 后者是 tab 标签，
            // 放在 tab 正下方当小标题是同一句话连说两遍
            DshInstanceSettingAdapter.Row.Group(R.string.dsh_settings_section_instance),
            DshInstanceSettingAdapter.Row.Value(
                R.string.dsh_instance_status_row, 0,
                { statusText() }, DshInstanceSettingAdapter.Tag.STATUS, editable = false
            ),
            // ★ fix12（E6 §2.3②）：状态行下面补一条**只读**的最近失败原因。
            //   卡片有这一行（DshInstanceAdapter 的 error_text，:357-367），详情页此前**没有任何
            //   一行**承载 `lastError` —— 用户点开一张写着「损坏」的卡片，进到本该最详细的这一页
            //   反而只剩状态词与一个按钮，"为什么坏"只能回列表看小字或翻日志。
            //   用**现成的** Value 行（不新增 Row 子类）：`editable = false` 就是纯展示，
            //   整行不响应、右侧没有箭头。有原因才插这一行（[lastErrorRow] 返回 null 时滤掉），
            //   否则 READY 实例会多出一条空行。
            lastErrorRow(),
            DshInstanceSettingAdapter.Row.Action(
                actionText, 0, actionText, DshInstanceSettingAdapter.Tag.START_STOP,
                // ★ A7：停止中不可点（按钮本身也压暗，见适配器 Row.Action 的绑定点）。
                // ★ fix12（E6-6）：安装中 / 损坏 / 未安装同样不可点 —— 口径见 runRows 顶部的注释。
                enabled = actionEnabled
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
            // 复制访问链接（fix8 / BL-168）：达标设备（WebView ≥119）上"用外部浏览器打开"的两个
            // 出口都以"版本过旧"为前置，代码上不可达，而本页给用户的地址又是**刻意剥掉 token** 的
            // 裸地址（见 webUiText）—— 于是"看到地址 → 敲进浏览器 → 必 401"没有第二条路。这一行就是
            // 那个缺失的合法出口：只在拿得到**带 token** 的 URL 时可点，点下去先弹确认再复制。
            // 值位仍显示裸地址（与 webUiText 同款口径，不把凭据铺在界面上被截图带走）。
            // ★ 描述行跟着可点性走（0 = 不显示）：拿不到凭据时写着一句「点击复制…」却点不动，
            //   正是 BL-127/BL-179 反复登记的那种名实两张皮；值位的 "-" 已经说明现在没有链接可给。
            DshInstanceSettingAdapter.Row.Value(
                R.string.dsh_settings_row_copy_url,
                if (copyUrlReady) R.string.dsh_settings_desc_copy_url else 0,
                { copyUrlText() }, DshInstanceSettingAdapter.Tag.COPY_URL,
                editable = copyUrlReady
            ),

            // ★ R27 / P0-B：「在浏览器中打开」—— 与上面的「复制访问链接」是**两条不同的路**，
            //   缺一不可：
            //
            //   · 复制：给用户一条可粘贴的地址，但**那条地址会过期** ——
            //     dsh 的 launch token 每次进程启动都重新生成（`browser-auth.ts:52-58` 的
            //     `randomBytes`），用户过一会儿再粘，token 已经作废；若端口也变过
            //     （cookie 名编进了 authority，`:106-107`），浏览器里那条 30 天 cookie 同样作废。
            //     于是"复制的链接"只对**当下这几分钟**有效。
            //   · 打开：用 `ACTION_VIEW` **当场**把 `DshRuntime` 手里那个 url 交给浏览器，
            //     端口与 token 都是此刻的真实值，零过期风险。
            //
            //   两个入口共用同一份"拿得到凭据才可点"的判据（copyUrlReady），
            //   拿不到时都灰掉 —— 不在界面上摆一个点了必然失败的按钮。
            DshInstanceSettingAdapter.Row.Value(
                R.string.dsh_settings_row_open_browser,
                if (copyUrlReady) R.string.dsh_settings_desc_open_browser else 0,
                { "" }, DshInstanceSettingAdapter.Tag.OPEN_BROWSER,
                editable = copyUrlReady
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

    /**
     * 「运行」段状态行下面那条**只读**的失败原因（fix12 / E6 §2.3②）。
     *
     * ## 为什么需要它
     * 卡片的 `error_text`（`DshInstanceAdapter`，:357-367）会显示 `lastError`，而详情页
     * `runRows()` 的整张表此前**没有任何一行**承载它：用户点开一张写着「损坏」的卡片，进到
     * 本该最详细的这一页只剩状态词 + 一个按钮，"为什么坏"只能回列表看一行小字，或翻日志页。
     * D-1 把 BROKEN 定为 sticky 之后这个缺口更明显 —— 坏因会长期存在而详情页长期不显示。
     *
     * ## 为什么复用 Value 行、不加新 Row 子类
     * `Row.Value(…, editable = false)` 的绑定路径已经是"纯展示行"：整行不响应点击、右侧不出现
     * 编辑图标（`DshInstanceSettingAdapter` :191-201 两个方向都显式赋值）。这正是需要的形态，
     * 加一个 `Row.Note` 只会多一条要维护的绑定分支。
     *
     * ## 标题为什么按状态取两个**已有的** key
     * 本轮不加 string（字符串归 W6），所以只能复用。这一行只在 `lastError` 非空时出现，而
     * `lastError` 只在 BROKEN / NOT_INSTALLED 两态被 `markState` 写入（`DshInstances` :213-215，
     * READY 会清空），于是取值只有两种：
     * - BROKEN ⇒ `dsh_installed_broken`（"安装失败"）：这正是"这次安装没成"的语义，值位是
     *   `DshInstaller.fail` 写下的原因（网络/DNS/npm 摘要），读起来是"安装失败 | 网络异常…"。
     *   注意 W6 给这条 key 钉的注释是"只允许 state == BROKEN 使用" —— 这里满足。
     * - NOT_INSTALLED ⇒ `dsh_state_not_installed`（"未安装"）：这条路径的 `lastError` 是**取消**
     *   （`DshInstaller` :554/575 写 `dsh_install_cancelled`），用"安装失败"去描述一次用户主动
     *   取消是不实的（E6 §3.0a 第 2 条专门点过这个坑），所以退回状态词本身。
     * 两个都是"这个实例现在是什么"的既有说法，与上面 status 行**同一族 key**，不会在页内造出
     * 第三套术语。
     *
     * ## 已知限制
     * 值位所在的布局（`item_dsh_setting_value.xml` 的 `value`）是 `singleLine + ellipsize=end`，
     * 较长的原因（例如 `dsh_install_error_network` 那整句）在这里会被尾省略；卡片那侧是
     * `maxLines=4`，完整原因在卡片上仍可见。要放宽这一行得动布局 XML 或给 `Row.Value` 加
     * `maxLines`（两者都不在本波次的文件表内），登记为遗留。
     */
    private fun lastErrorRow(): DshInstanceSettingAdapter.Row.Value? {
        val state = inst?.state
        val error = inst?.lastError?.takeIf { it.isNotBlank() } ?: return null
        val title = when (state) {
            DshInstance.State.BROKEN -> R.string.dsh_installed_broken
            else -> R.string.dsh_state_not_installed
        }
        return DshInstanceSettingAdapter.Row.Value(
            title, 0,
            { error }, DshInstanceSettingAdapter.Tag.STATUS, editable = false
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
        // ★ R27 / P0-A2：「启动目录（HOME）」—— dsh 的目录选择器用它当初始目录。
        //
        // 为什么这一行值得给用户：默认值（实例目录）只含 `home`/`workspace`/`node_modules` 三项，
        // 若用户想跨实例选目录、或想让 agent 直接落在家目录下的别处，就只能改这个值。
        // 值位显示**当前生效的**路径（含默认值），不是只显示"已自定义"——用户要能一眼看到
        // dsh 到底从哪里起步，否则"为什么列表里是这些"永远说不清。
        DshInstanceSettingAdapter.Row.Value(
            R.string.dsh_settings_row_home,
            R.string.dsh_settings_desc_home,
            { homeDirText() },
            DshInstanceSettingAdapter.Tag.HOME_DIR
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

    /**
     * 本实例**当前带凭据**的访问 URL（fix8 / BL-168），拿不到就返回 null。
     *
     * 判据不是"Running 且 url 非空"，而是 `UrlScanner.tokenOf(url) != null`：认领孤儿在旧 pid
     * 文件迁移期、以及任何回落路径，state.url 都可能是**裸 URL**（BL-135 的成因）。那时候这一行
     * 必须不可点 —— 复制一个点开必 401 的地址，比不给出口更糟：用户会以为"链接是对的、是 dsh 坏了"，
     * 而真正的解法是回应用内「打开界面」（那里会自动带新 token 走 303）。
     * 非运行态同样返回 null：没有会话，就没有凭据。
     */
    private fun tokenUrl(): String? {
        val st = runtimeState
        if (st !is DshRuntime.State.Running || st.instanceId != instanceId) return null
        val url = st.url ?: return null
        return url.takeIf { UrlScanner.tokenOf(it) != null }
    }

    /** 「复制访问链接」行的值位：裸地址（与 [webUiText] 同款口径，凭据不铺在界面上），不可点时为 "-" */
    private fun copyUrlText(): String = tokenUrl()?.substringBefore('?') ?: "-"

    /**
     * 「启动目录（HOME）」行的值位：**当前生效的** guest 内路径。
     *
     * 显示的是"实际会用哪个"，而不是只显示"是否自定义过" —— 用户要能一眼看出 dsh 从哪儿起步，
     * 否则"列表里为什么是这几项"永远说不清。自定义值与默认值都显示，默认值额外标注一下。
     */
    private fun homeDirText(): String {
        val i = inst ?: return "-"
        val guestPath = "${ProotCommand.GUEST_ROOT}/instances/${i.id}"
        val effective = resolveHomeRootfs(guestPath, i.homeDir)
        return if (i.homeDir.isNullOrBlank()) {
            context.getString(R.string.dsh_home_default_value, effective)
        } else {
            effective
        }
    }

    /**
     * 「在浏览器中打开」（R27 / P0-B）：把**此刻**带 token 的 URL 交给系统浏览器。
     *
     * 与 [confirmCopyUrl] 的分工：
     * - 复制是"给你一条地址，等会儿自己粘"—— 而 dsh 的 token 每次进程启动都换新
     *   （`browser-auth.ts:52-58`），所以复制出来的链接只在**当下几分钟**内有效；
     * - 这里是"现在就打开"，端口与 token 都是刚从 `DshRuntime` 取的真实值，零过期风险。
     *
     * 不弹确认框：复制要确认是因为剪贴板里的凭据会留下来（可能被别的 App 读到），
     * 而 `ACTION_VIEW` 是把地址直接交给浏览器，不会在别处留副本。
     *
     * 失败时（设备没有浏览器 / 系统拒绝）不静默：提示改用「复制访问链接」。
     */
    private fun openInBrowser() {
        val url = tokenUrl()
        if (url == null) {
            // 行已按 copyUrlReady 灰掉，理论上到不了这里；到了也只是什么都不做并说明。
            toast(context.getString(R.string.dsh_settings_desc_open_browser_unavailable))
            return
        }
        val launched = runCatching {
            host.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        if (launched.isFailure) {
            // 实例级：这条属于该实例的"怎么把界面交给外部"的失败，用户到它的日志页能对上
            DshLogBus.appendFor(
                inst?.id ?: return,
                "[setting] ACTION_VIEW failed: ${launched.exceptionOrNull()?.message}; " +
                    "no browser can handle the url"
            )
            toast(context.getString(R.string.dsh_settings_desc_open_browser_unavailable))
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
                    toastSaveOutcome(i.id)
                }
            }

            DshInstanceSettingAdapter.Tag.PORT -> editText(
                context.getString(R.string.dsh_settings_row_port),
                if (i.port > 0) i.port.toString() else "",
                allowEmpty = true
            ) { text -> commitPortValue(i, text) }

            // ★ R27 / P0-A2：启动目录（HOME）。空 = 恢复默认（实例目录）。
            //
            // ★ R28 / C2-B-3：**保存前必须归一化**，否则会出现"存的值 ≠ 显示的值"：
            //   UI 原来只拦 `startsWith("/")`，放行 `/opt/dsh/../etc` 与带尾斜杠的写法；
            //   而 `resolveHomeRootfs` 在运行时会拒绝/归一化它们 —— 于是界面显示默认路径，
            //   既不是用户填的、也没有任何提示（`homeDirText()` 因 homeDir 非空不标"(默认)"）。
            //   现在用同一个函数先算一遍：归一化后若与传入值不同且仍然合法，就存归一化的；
            //   若归一化后等于默认值（说明被拒了），就不保存并提示。
            DshInstanceSettingAdapter.Tag.HOME_DIR -> editText(
                context.getString(R.string.dsh_settings_row_home),
                i.homeDir ?: "",
                allowEmpty = true
            ) { text ->
                val trimmed = text.trim()
                if (trimmed.isEmpty()) {
                    DshInstances.updateConfig(i.id, clearHomeDir = true)
                    reload()
                    toastSaveOutcome(i.id)
                    return@editText
                }
                val guestInstancePath = "${ProotCommand.GUEST_ROOT}/instances/${i.id}"
                val normalized = resolveHomeRootfs(guestInstancePath, trimmed)
                when {
                    // 被清洗规则拒了（含 .. / . 或非绝对路径）—— 归一化结果等于默认值。
                    normalized == guestInstancePath && trimmed != guestInstancePath ->
                        toast(context.getString(R.string.dsh_home_must_be_absolute))
                    else -> {
                        DshInstances.updateConfig(i.id, homeDir = normalized)
                        reload()
                        toastSaveOutcome(i.id)
                    }
                }
            }

            DshInstanceSettingAdapter.Tag.PATH -> {
                val path = DshPaths.instanceDir(i.id).absolutePath
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dsh instance path", path))
                // BL-159b：旧文案是行的说明文字（"点击复制到剪贴板"）——复制**做完了**还提示
                // "点击复制"是答非所问；改成明确的完成反馈。
                toast(context.getString(R.string.dsh_path_copied))
            }

            // 打开界面：运行中/启动中/停止中才会走到这里（`editable` 按 running || isBusy() 设置）
            DshInstanceSettingAdapter.Tag.OPEN_WEB -> host.openWebView()

            // 复制访问链接：只有拿得到带 token 的 URL 才会走到这里（editable = tokenUrl() != null）。
            // 点击不直接复制，先过一道确认 —— 见 [confirmCopyUrl]。
            DshInstanceSettingAdapter.Tag.COPY_URL -> confirmCopyUrl()
            DshInstanceSettingAdapter.Tag.OPEN_BROWSER -> openInBrowser()

            else -> Unit
        }
    }

    /**
     * 端口输入落地（fix2 / BL-090 的解析口径不动；BL-159a 在此加"失败重开预填"）。
     *
     * 旧行为的断点：`parsePortInput` 返回 null 时只 toast 一句非法提示 —— 而 EditDialog 在回调前
     * 已经 dismiss，用户的输入随之蒸发，想改只能从头再敲一遍。
     * 现在失败后**重新打开编辑框并预填原输入**（[retry] 防重入：重开的那一次再失败就只 toast，
     * 不会再开第三个框 —— 递归上限为 1，不存在无限套娃）。
     */
    private fun commitPortValue(i: DshInstance, text: String, retry: Boolean = true) {
        // 空串是对这把输入框**合法**的输入（= 自动分配端口），所以这里必须
        // 走 allowEmpty 通路收下它，再按 blank→0 解释（parsePortInput，fix2 / BL-090）。
        val port = parsePortInput(text)
        if (port == null) {
            toast(context.getString(R.string.dsh_port_invalid))
            if (retry) {
                editText(
                    context.getString(R.string.dsh_settings_row_port),
                    text,
                    allowEmpty = true
                ) { again -> commitPortValue(i, again, retry = false) }
            }
        } else {
            DshInstances.updateConfig(i.id, port = port)
            reload()
            toastSaveOutcome(i.id)
        }
    }

    /**
     * 配置改动后的反馈（fix12 / §2.15 B-4）。
     *
     * ## 为什么不直接判 `saveAsync()` 的返回值
     * `updateConfig(...)` **内部**已经调过一次 `saveAsync()`，而它返回的 Boolean 被丢掉了
     * （`DshInstances.updateConfig` 是 `Unit`）。本轮 P/W 分工不允许改 `DshInstances.kt` 的签名，
     * 所以这里退一步读**它留下的两个健康位**：
     * - `manifestLoadFailed` 为真 ⇒ `saveAsync()` 刚刚必然同步拒绝过（W1 的实现是入口处判位后
     *   直接 `return false`），这次改动**只改了内存**；
     * - `manifestWriteFailed` 为真 ⇒ 写盘那一步失败了（也可能是之前某次留下的 —— 同样意味着
     *   "盘上没有"）。
     *
     * 两个位里任意一个为真，就说明"用户看到的这份新值是内存里的、盘上没有"，必须说
     * `dsh_change_not_saved`，而不是原来那句"重启后生效"（那句话在这里是双重误导：既假设
     * 改动已经落盘，又把用户的注意力引到重启上）。
     *
     * ## 理想的契约（本轮不改，登记在报告里）
     * `updateConfig` 应当自己返回 `saveAsync()` 的 Boolean，调用方按返回值分叉。现在这条
     * "读全局位"的写法有一个已知窗口：写失败是**异步**置位（协程里写盘），本次读位时可能
     * 还没翻转 —— 最坏情况下这一次的 toast 仍是"重启后生效"，下一次进来才看见横幅。
     * 横幅（实例页常驻）与写失败置位同源，所以这个窗口不会让用户永久失去知情。
     */
    private fun toastSaveOutcome(id: String) {
        if (DshInstances.manifestLoadFailed.value || DshInstances.manifestWriteFailed.value) {
            toast(context.getString(R.string.dsh_change_not_saved))
            return
        }
        // BL-163：实例在跑时改配置不会影响已启动的进程（运行复用启动时的快照），
        // 追加一句"重启后生效"，免得用户改完对着旧配置的界面以为没保存。
        toastRestartToApplyIfRunning(id)
    }

    /**
     * BL-163 / BL-153-UI：实例**在跑**时改配置（改名/改端口）后追加"重启该实例后生效"。
     * 判据与启动停止行/停止动作同源（[DshRuntime.activeInstanceId]，含 Starting/Stopping）——
     * 只有运行时才会真的有"旧进程还按旧配置在跑"的分歧；没跑时改动即刻是下一场启动的全部事实。
     */
    private fun toastRestartToApplyIfRunning(id: String) {
        if (DshRuntime.activeInstanceId() == id) {
            toast(context.getString(R.string.dsh_restart_to_apply))
        }
    }

    /**
     * 「复制访问链接」的确认 + 复制（fix8 / BL-168）。
     *
     * 为什么要点确认：复制的是**本次会话的凭据**，进剪贴板后任何应用都能读走（Android 10+ 还
     * 会把它同步到云）。这与 [com.dsh.ui.DshWebViewCompat] 里"用户在对话框里主动点的选择，可接受"
     * 是同一口径 —— 凭据外流必须让用户知情并点头，而不是点一行就悄悄发生。
     * 确认正文同时交代两件事：拿到链接的人能打开界面（这是用途）、实例重启后链接失效（这是代价），
     * 免得用户把它当成长期有效的地址分享出去。
     *
     * 不做的事：不把这个 URL 写进日志总线（写了就是自己把 registerSecret 防的泄漏又打开一个口子）。
     */
    private fun confirmCopyUrl() {
        val url = tokenUrl() ?: return // 行不可点时理论上到不了这里；到得了也只是什么都不做
        FCLAlertDialog.Builder(host.activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
            .setTitle(context.getString(R.string.dsh_settings_row_copy_url))
            .setMessage(context.getString(R.string.dsh_copy_url_confirm_message))
            .setPositiveButton(context.getString(R.string.dsh_action_copy)) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dsh access url", url))
                toast(context.getString(R.string.dsh_url_copied))
            }
            .setNegativeButton(context.getString(R.string.dialog_negative), null)
            .create()
            .show()
    }

    // ===== 动作行动作 =====

    private fun onAction(row: DshInstanceSettingAdapter.Row.Action) {
        val i = inst ?: return
        when (row.tag) {
            DshInstanceSettingAdapter.Tag.START_STOP -> {
                // ★ A7（R24）：停止中行已设 enabled=false（点不到），这里再挡一层 ——
                //   与卡片主按钮的 `if (locked) return` 同一态度：FCL 控件的 enabled 语义
                //   不保证与系统 View 完全一致，静默的 no-op 点击是这个项目反复登记过的形态。
                if ((runtimeState as? DshRuntime.State.Stopping)?.instanceId == i.id) return
                // fix2 / BL-127：判据与文案、点击行为**同源**（DshRuntime.activeInstanceId()）。
                // 旧实现"文案看 Running、点击看 isBusy + Running"两张皮：Starting 期写着「启动」，
                // 点下去被 busy 静默吞掉（V8-3）。现在 Starting 也计入"在跑"（文案「停止」），
                // 点击落到同一个停止动作上；Stopping 已在上面提前返回（A7：文案「正在停止…」）。
                if (DshRuntime.activeInstanceId() == i.id) {
                    // 在跑（含 Starting）：停。★ 用 stopIf 而不是 stop()：stop() 停的是"当前在跑的
                    // 那个"，从行渲染到用户点击之间状态可能已换实例（TOCTOU），stopIf 在运行时
                    // 同一把锁里重新判定"跑的是不是它"，不会误停别的实例。
                    DshRuntime.stopIf(i.id)
                    return
                }
                // ★ fix12（E6-2 / E6-6）：三个"此刻不该在这里动手"的态。行已经压暗
                //   （runRows 的 actionEnabled），这里再挡一层并**给一句 toast 说明该去哪** ——
                //   改前 INSTALLING 是静默 no-op（满色可点、点了没反应，E6-6 的原话），
                //   BROKEN / NOT_INSTALLED 则是直接落到 startInstance，等运行时才弹失败。
                //   判据与 runRows 的文案分支**逐条对齐**，两处不同步就会又变成"写着重试、点下去启动"。
                //   文案都是**已有的** key（本轮不加 string）：安装中用它自己的事实句；
                //   另外两态共用一个"回列表去操作"的指路句 —— 真正的按钮在那里
                //   （BROKEN 是卡片的「重试」，NOT_INSTALLED 是卡片的「安装」）。
                when (i.state) {
                    DshInstance.State.INSTALLING -> {
                        // 同一句即可：安装中就是"这个版本正在装"，与卡片「安装中」同一事实。
                        toast(context.getString(R.string.dsh_install_in_flight))
                        return
                    }
                    DshInstance.State.BROKEN -> {
                        // 重试的完整流程（确认框 + 停旧进程 + 发起安装那套）只在卡片上，
                        // 这里只指路，不在详情页另开一条语义不同的第二入口。
                        // 不用 dsh_reinstall_pick_version：它的原文断言"还没成功装过任何版本"，
                        // 而 BROKEN 实例恰恰装过（失败），那句话在这里是假话。
                        toast(context.getString(R.string.dsh_action_go_instances))
                        return
                    }
                    DshInstance.State.NOT_INSTALLED -> {
                        // ★ R2c-1（fix12 收尾）：NOT_INSTALLED 与 BROKEN 分开指路。
                        //   "去实例列表"是**错的方向**：卡片上的「安装」本身也只是切到版本页
                        //   （`DshInstanceAdapter` 的 notInstalled → onOpenVersions），用户要跳两次
                        //   才到真正的安装入口。详情页自己就有 switchTab 的能力，一步到位。
                        //   零新增 string：复用 `dsh_action_download` 那句"去下载"。
                        toast(context.getString(R.string.dsh_action_download))
                        host.switchTab(DshShellHost.TAB_VERSIONS)
                        return
                    }
                    DshInstance.State.READY -> Unit // 落到下面的启动
                }
                startInstance(i)
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

    /**
     * 运行时自检（BL-096，R15 补防重）。
     *
     * 现状（修前）：只弹一次"正在自检…"toast，随后最长 [DshBootstrap] 的 60s 超时期间无进度、
     * 按钮不禁用；连点会串多个协程、叠多个结果对话框。
     * 修后：一个 `verifying` 标志守卫——进行中再点直接忽略（并复用既有"正在自检"文案给反馈），
     * 结果对话框弹出后清标志。标志只在主线程读写（本页 UI 协程都在 Main）。
     *
     * 为什么不做"按钮置灰/行忙态"：FCL 控件无 disabled 视觉（BL-020 家族），置灰反而回到
     * "看着能点、点了没反应"；用 toast 明确回应更诚实，且零布局改动。
     */
    private var verifying = false

    private fun verifyRuntime() {
        if (verifying) {
            // 进行中：明确告知而不是静默吞掉（用户连点说明在等反馈）
            toast(context.getString(R.string.dsh_verify_running))
            return
        }
        verifying = true
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
            verifying = false
        }
    }

    /**
     * 重新安装。
     *
     * 两道把关（BL-147）：
     * 1. **安装中重复触发在确认框之前拦下**：判据用 installer 的单飞闸门真值（`isInstalling`），
     *    **不用** `inst.state` —— 持久化状态可能滞后。提示口径与版本页的"已跳过"一致
     *    （旧实现放行到 installer.install 才发现闸门被占：用户点了没反应，返回值被丢弃）。
     * 2. **先确认、再处理运行中的旧进程、最后才发起安装**：见 [reinstallConfirmed]。
     *
     * 没装成功过任何版本时**不直接跳走**，而是弹一个带「下载」按钮的确认框 —— 与实例列表页
     * 同一处理：跳走会把用户正在看的详情页顶掉，而且他没得选（必须先去版本页挑一个）。
     */
    private fun reinstall(i: DshInstance) {
        // 安装中重复触发。★ fix12（E6-4）：原来这里说的是
        // `dsh_install_skipped` = "%1$s 已安装，可直接启动" —— 那句话在**说谎**：
        // 触发条件是"这台实例正有一个安装在跑"，与"装好了"无关；用户被劝去启动一个
        // 半个身子还在 npm 里的实例。`dsh_install_in_flight`（"该版本正在安装中"）才是事实，
        // 且它不带占位符 —— 原来那处还传了 `i.dshVersion ?: i.name`，新 key 用不上（§2.15 明文）。
        if (DshServices.installer(context).isInstalling(i.id)) {
            toast(context.getString(R.string.dsh_install_in_flight))
            return
        }
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
        // 确认姿态照抄 [confirmDelete]：ALERT 级、正键=动作名、负键取消。
        // 正文（dsh_reinstall_confirm_message）已声明"运行中会先停止它" —— 停止的真实发生
        // 在 [reinstallConfirmed]，文案与实际行为必须保持这一致。
        FCLAlertDialog.Builder(host.activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(context.getString(R.string.dsh_action_reinstall))
            .setMessage(context.getString(R.string.dsh_reinstall_confirm_message, i.name))
            .setPositiveButton(context.getString(R.string.dsh_action_reinstall)) {
                reinstallConfirmed(i, version)
            }
            .setNegativeButton(context.getString(R.string.dialog_negative), null)
            .create()
            .show()
    }

    /**
     * 确认后的重装流程（BL-147）：
     *
     * 1. 实例在跑（[DshRuntime.activeInstanceId]，含 Starting/Stopping）→ 先 [DshRuntime.stopIf]，
     *    再**等它真的离场**才发起安装：npm 与活 node 并发写同一份 node_modules 会互相踩碎数据
     *    （installer 的单飞闸门只防"安装 vs 安装"，防不了"安装 vs 运行"—— 这条边补在这里）。
     *    等待姿态逐条照抄 [DshInstances.delete]：stopIf 的返回值**不作判据**（Stopping 期是
     *    no-op、返回 false 不代表停完了），统一由轮询 `activeInstanceId()` 判"是否真的离场"；
     *    上限 8s、间隔 100ms；超时也照常发起安装，只在实例日志里写明"进程未停干净"——
     *    与 delete"停不动也硬删"同哲学：用户的意图已被确认，拦不动就把真相留在日志里。
     * 2. 等待全在协程里做（本页 scope + delay 轮询，不阻塞 UI）；页面销毁时 scope 取消、
     *    等待随之中止 —— 确认之后、发起安装之前用户退页，则这次重装作废（不会误发安装）。
     */
    private fun reinstallConfirmed(i: DshInstance, version: String) {
        scope.launch {
            if (DshRuntime.activeInstanceId() == i.id) {
                // 停止原因用「重新安装」这句动作文案：没有专用键（也不新增），而它会显示在
                // 任务区那条被取消的运行记录上 —— 比默认的「用户停止」更能说清这一停是为什么。
                runCatching { DshRuntime.stopIf(i.id, context.getString(R.string.dsh_action_reinstall)) }
                    .onFailure { e ->
                        // stopIf 设计上不该抛；万一抛了也不能让这个协程死掉 —— 继续走下面的
                        // 等待循环（与 DshInstances.delete 同款兜底）。
                        DshLogBus.appendFor(i.id, "[setting] stopIf before reinstall failed: ${e.message}")
                    }
                var stopTimedOut = false
                val waitDeadline = System.currentTimeMillis() + REINSTALL_STOP_WAIT_TIMEOUT_MS
                while (DshRuntime.activeInstanceId() == i.id) {
                    if (System.currentTimeMillis() >= waitDeadline) {
                        stopTimedOut = true
                        break
                    }
                    delay(REINSTALL_STOP_WAIT_POLL_MS) // 协程里轮询：让出线程，别忙等
                }
                if (stopTimedOut) {
                    // 实例级日志：这条属于该实例的安装史，用户到它的日志页排障
                    DshLogBus.appendFor(
                        i.id,
                        "[setting] stop timed out before reinstall for ${i.name}, installing anyway"
                    )
                }
            }
            val started = DshServices.installer(context).install(i, version)
            if (started) {
                toast(context.getString(R.string.dsh_install_started, version))
            } else {
                // 极窄竞态：等停止的窗口里别的入口（版本页等）先发起了同实例安装 ——
                // installer 已把忽略原因写进实例日志。
                // ★ fix12（E6-4）：同一处谎言，同样改口 —— 这里的语义是"单飞闸门被占"
                //   （installer.install 返回 false ⇒ 本次**没有**安装开始），
                //   不是 dsh_install_skipped 那句"%1$s 已安装，可直接启动"。
                toast(context.getString(R.string.dsh_install_in_flight))
            }
        }
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
                //
                // BL-148：列表页删除有 Toast、这里全程静默 —— 摘清单虽即时，目录删除在后台
                // 要数秒，"点了删除什么都没发生"的观感就来自这段空窗。补上与列表页同源的
                // dsh_delete_started；收尾再按结果补一条（见下）。
                // ■ V1 §5-2（fix12 收尾）：必须接住返回值。清单读取失败期间 `delete()` 会**拒绝**
                //   并 return false（内存没摘、目录没删），而旧写法无条件 toast「正在删除…」——
                //   用户以为删了，重启后实例原样复活（正是本轮 E2-N-1 要消灭的形态，只是入口不同）。
                val accepted = DshInstances.delete(i.id) { leftover ->
                    // onFinished 不在主线程（DshAppScope 的 IO）→ 切主线程再 toast。
                    // 用独立 [mainHandler] 而不是页面 scope：删除一开始实例就摘清单、详情页
                    // 退层、本页 scope 被取消 —— 而收尾最快也在"等进程离场 + 删 ~300MB"之后，
                    // 几乎必然晚于页面销毁，挂页面 scope 的迟到反馈永远到不了用户眼前。
                    mainHandler.post {
                        // 有文件残留才补：复用删除任务行的终态文案（与任务区同一句话，不新增键）。
                        // 全删干净则不补 —— 开始那条 Toast + 任务区「已删除」已够。
                        if (leftover) {
                            toast(context.getString(R.string.dsh_task_deleted_with_leftover))
                        }
                    }
                }
                toast(
                    context.getString(
                        if (accepted) R.string.dsh_delete_started else R.string.dsh_delete_refused
                    )
                )
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
