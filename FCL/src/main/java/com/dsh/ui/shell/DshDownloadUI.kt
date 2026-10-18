package com.dsh.ui.shell

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.core.DshBootstrap
import com.dsh.core.DshDownloadViewModel
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.core.DshServices
import com.dsh.core.DshVersionListItem
import com.dsh.core.RefreshTick
import com.dsh.ui.DshVersionAdapter
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshDownloadBinding
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 版本下载页（外壳 tab 2）。由原 `DshDownloadActivity` 迁移为 [DshPageUI]。
 *
 * 迁移要点：
 * - `DshDownloadViewModel` 原来用 `lifecycleScope`，现在用页面级 [scope]。
 * - 装完的"去实例页"从 `finish()` 改为 [host].switchTab(实例)。
 */
class DshDownloadUI(
    context: Context,
    private val host: DshShellHost
) : DshPageUI(context, R.layout.activity_dsh_download) {

    private val binding = ActivityDshDownloadBinding.bind(contentView)
    private lateinit var adapter: DshVersionAdapter
    private lateinit var viewModel: DshDownloadViewModel

    override fun onCreate() {
        super.onCreate()
        DshPaths.loadPaths(context)
        DshInstances.init()

        val installer = DshServices.installer(context)
        viewModel = DshDownloadViewModel(scope, installer)

        adapter = DshVersionAdapter(onInstall = ::onInstall)
        binding.versionList.layoutManager = LinearLayoutManager(context)
        binding.versionList.adapter = adapter

        binding.switchPrerelease.setOnCheckedChangeListener { _, checked ->
            viewModel.togglePrerelease(checked)
        }
        // ★ 刷新反馈（fix2/BL-116）：转圈的**开始**由点击/首载置位，**结束只认 refreshTicks**
        //   的 done tick（seq 守卫见 onRefreshTick）——不再把 endRefresh 挂在 render 上。
        //   根因：刷新成功但数据未变时 UiState.Loaded 与当前值 data-class 相等 →
        //   StateFlow 抑制发射 → render 不执行 → 转圈永置、按钮永久静默禁用（健康网也必现）。
        binding.btnRefresh.setOnClickListener {
            if (refreshing) return@setOnClickListener
            startRefresh()
            viewModel.refresh(force = true)
        }

        scope.launch { viewModel.uiState.collect { render(it) } }
        // 刷新完成信号：seq 单调递增才处理。VM 侧纪元守卫拦一层（被顶掉的旧请求不发 tick），
        // 这里再按 seq 过滤一次——旧代信号不许结束新一代的转圈（R12-A1-2 的双保险）。
        // 订阅声明在 refresh() 调用之前：scope 是 Main.immediate，在主线程上 launch 体会
        // 内联执行——collect 在 onCreate 内同步完成订阅，随后 refresh() 的 tick 不会漏。
        scope.launch { viewModel.refreshTicks.collect { onRefreshTick(it) } }
        scope.launch { viewModel.installingVersions.collect { adapter.submitInstalling(it) } }
        scope.launch {
            // BL-155：drop(1) 跳过**订阅瞬间的当前值** —— 与 VM 侧 :118 同款口径。
            // progress 是 StateFlow：页面重建（ViewPager 滑动回收再创建）时重订阅会**回放**
            // 最后一条 Done/Failed，导致重复弹「安装完成/安装失败」。这是 BL-116/RefreshTick
            // 的同族问题（状态流回放旧值被当成新事件），drop(1) 正好消掉这条回放。
            // 有意的取舍（P4-3 口径）：drop(1) 只丢"订阅时已在流里的值"（含重建前刚完成的安装），
            // 本页面会话存活期间**新到达**的终态（无论由本页还是实例页触发安装）照常弹出。
            // 订阅在 onCreate 内同步完成（scope 是 Main.immediate，主线程 launch 内联执行），
            // 本页自己发起的安装不可能赶在订阅之前完成。
            installer.progress.drop(1).collect { p ->
                when (p) {
                    is com.dsh.core.DshInstaller.Progress.Done ->
                        toast(context.getString(R.string.dsh_install_done, p.version))
                    is com.dsh.core.DshInstaller.Progress.Failed ->
                        toast(context.getString(R.string.dsh_install_failed, p.reason))
                    else -> {}
                }
            }
        }

        // 初次加载也走 refresh()（seq 机制统一，BL-119）：首载期间按钮按同一风格置灰，
        // 且数据侧有纪元守卫兜底 —— 首载转圈期即便有第二路请求也互不覆盖。
        startRefresh()
        viewModel.refresh()
    }

    /** 是否正在刷新（防重复点击；真实 in-flight 由 VM 的纪元守卫裁决） */
    private var refreshing = false

    /** 已处理的最大 tick 序号：更旧（或相等）的 tick 直接忽略——只有最新纪元的信号能结束转圈 */
    private var lastHandledTickSeq = 0L

    /** 进入刷新中：转圈 + 按钮禁用置灰（BL-118：FCLImageButton 无 enabled 视觉，用 alpha 补） */
    private fun startRefresh() {
        refreshing = true
        binding.loading.visibility = View.VISIBLE
        setRefreshBusy(true)
    }

    /**
     * 结束刷新中：**只由 [onRefreshTick] 的 done tick 调用**（BL-116）。
     * 注意这里必须自己收起转圈：刷新成功但数据未变时 render 不会执行（StateFlow 抑制），
     * 若等 render(Loaded) 去收，就回到了转圈永置的老路。
     */
    private fun endRefresh() {
        refreshing = false
        binding.loading.visibility = View.GONE
        setRefreshBusy(false)
    }

    private fun setRefreshBusy(busy: Boolean) {
        binding.btnRefresh.isEnabled = !busy
        binding.btnRefresh.alpha = if (busy) 0.4f else 1f
    }

    /** 刷新完成信号 → 结束转圈；失败细节的上屏仍走 render 的 warning/error 分支（照旧） */
    private fun onRefreshTick(tick: RefreshTick) {
        if (!tick.done) return
        // seq 匹配才算"自己这一代"：旧请求的 tick（seq 小于等于已处理的）忽略。
        // VM 侧纪元守卫已拦一层（被顶掉的旧请求根本不发 tick），这里是双保险。
        if (tick.seq <= lastHandledTickSeq) return
        lastHandledTickSeq = tick.seq
        endRefresh()
    }

    private fun render(state: DshDownloadViewModel.UiState) {
        // ⚠️ render 只画**数据**（列表/空态/提示），不碰 loading 转圈与刷新按钮——
        // 转圈生命周期完全归 startRefresh/endRefresh（由 refreshTicks 驱动，见 onRefreshTick）。
        // 历史上转圈结束挂在 render(Loaded/Error) 上，正是 BL-116 卡死的来源之一。
        when (state) {
            is DshDownloadViewModel.UiState.Loading -> {
                binding.loading.visibility = View.VISIBLE
                binding.errorHint.visibility = View.GONE
                binding.emptyHint.visibility = View.GONE
                binding.versionList.visibility = View.GONE
            }
            is DshDownloadViewModel.UiState.Loaded -> {
                adapter.submit(state.items)
                // 空态（BL-117）：OFF 过滤全灭 / 目录本来就空，分别给两种提示，不再无声空白
                val hintRes = if (state.items.isEmpty())
                    DshDownloadViewModel.emptyHintRes(itemsEmpty = true, showPrerelease = state.showPrerelease)
                else null
                if (hintRes != null) {
                    binding.emptyHint.visibility = View.VISIBLE
                    binding.emptyHint.text = context.getString(hintRes)
                    binding.versionList.visibility = View.GONE
                } else {
                    binding.emptyHint.visibility = View.GONE
                    binding.versionList.visibility = View.VISIBLE
                }
                if (state.warning != null) {
                    binding.errorHint.visibility = View.VISIBLE
                    // BL-092：warning 已是 VM 侧生成的本地化短句（如"刷新失败，正在显示缓存数据"），
                    // 不再套 dsh_download_warning 的 %1$s 包装、更不再把 TimeoutException 类名上屏
                    binding.errorHint.text = state.warning
                } else {
                    binding.errorHint.visibility = View.GONE
                }
            }
            is DshDownloadViewModel.UiState.Error -> {
                binding.emptyHint.visibility = View.GONE
                binding.versionList.visibility = View.GONE
                binding.errorHint.visibility = View.VISIBLE
                // R18/BL-154 追加修正：message 已是 VM 侧生成的完整本地化句
                // （与 warning 分支 BL-092 同口径），再套 dsh_download_error 的
                // 「加载失败：%1$s」会变成"加载失败：拉取版本列表失败"式双包装 —— 直显。
                binding.errorHint.text = state.message
            }
        }
    }

    private fun onInstall(item: DshVersionListItem) {
        scope.launch {
            // 运行环境没就绪：跳「准备运行环境」页（不在下载页就地解压 + 弹对话框 —— 那次
            // 真机实测的进度丢失与失败原因覆盖就是这么来的），本次安装中止
            if (!DshBootstrap.isReady()) {
                DshLauncher.openSetup(host.activity)
                return@launch
            }
            val dispatch = viewModel.installVersion(item)
            if (dispatch.started) {
                // ★ 只有**真的发起**了安装才弹「已开始安装」。
                //   原来无条件弹：连点几次就排队弹出 N 个对话框（真机表现为"疯狂跳窗"）。
                // ★ BL-155：这里原来还有一句「正在安装 xxx…」Toast（dsh_install_started）——
                //   与对话框重复提示"开始了"，已删除。对话框文案已含版本名（%1$s = 实例名/版本），
                //   信息不丢；"开始了"三处变一处（VM 的 installingVersions 置位 + 对话框）。
                askOpenInstances(dispatch.instance?.name ?: item.version)
            } else {
                // 没有真的发起安装（该版本已装好 / 已有安装在跑）：只提示，不弹窗
                toast(context.getString(R.string.dsh_install_skipped, item.version))
            }
        }
    }

    /** 已有一个"已开始安装"对话框时不重复弹（连点场景下避免叠窗） */
    private var installDialogShowing = false

    private fun askOpenInstances(instanceName: String) {
        if (installDialogShowing) return
        installDialogShowing = true
        FCLAlertDialog.Builder(host.activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
            .setTitle(context.getString(R.string.dsh_install_started_title))
            .setMessage(context.getString(R.string.dsh_install_started_message, instanceName))
            .setPositiveButton(context.getString(R.string.dsh_action_go_instances)) {
                installDialogShowing = false
                host.switchTab(DshShellHost.TAB_INSTANCES)
            }
            .setNegativeButton(context.getString(R.string.dsh_action_stay)) {
                installDialogShowing = false
            }
            .setCancelable(false)
            .create()
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
