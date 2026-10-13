package com.dsh.ui.setup

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import com.dsh.core.DshAppScope
import com.dsh.core.DshBootstrap
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshSetupBinding
import com.dsh.fcl.androidlauncher.databinding.ItemDshSetupStepBinding
import com.dsh.ui.shell.DshMainActivity
import com.dsh.ui.shell.DshShellHost
import com.mio.util.ImageUtil
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首启「准备运行环境」页 —— **一次性前置页**。
 *
 * ## 为什么单独做一页（而不是在首页挂横幅）
 * 真机实测暴露两个问题：
 * 1. 首页横幅的文案会被"未就绪 / 失败"互相覆盖 —— 失败时用户看不到**真实原因**；
 * 2. 解压是分钟级任务，挂在首页时用户会到处点，触发重复操作。
 *
 * 现在：首次启动直接进本页，**准备完成前不进首页**；进度 / 当前步骤 / 明细各有固定位置；
 * 失败停在本页并显示真实原因 + 「重试」「重新开始」；成功后写标记并进首页。
 *
 * ## 与首页任务区的分工
 * 本页只管「运行环境」；日常的安装 / 启动 / 删除任务走首页任务区（[com.dsh.core.DshTasks]）。
 *
 * ## 返回键（本页有两个入口，行为必须分开）
 * 1. **首启门禁**：[com.tungsten.fcl.activity.SplashActivity] 判定未完成准备 → 启动本页后自己 `finish()`。
 *    此时任务栈里**只有本页**，没有"上一页"——早期版本没覆写返回键，按返回等于 finish 掉任务根，
 *    任务从最近任务里消失（用户以为「App 崩了」）。现在改走 [moveTaskToBack]：等同 HOME，
 *    任务与解压进度都留在最近任务里，点回来还能接着看。
 * 2. **运行中从外壳进入**：[com.dsh.ui.shell.DshLauncher.openSetup] 把自己压在外壳之上，
 *    此时任务栈**下面是外壳**，按返回就是普通 finish → 回外壳。
 *
 * 怎么区分这两者：用 [isTaskRoot]（"我下面还有没有页面可回"这个栈事实），而不是用完成标记
 * [isCompleted]。标记只回答"以前准备好过没有"，与栈状态无关；从外壳进入时标记一定是 true，
 * 但它不是**判据**——真正的判据是栈里有没有外壳。见 [onBackPressed]。
 */
class DshSetupActivity : FCLActivity() {

    private lateinit var binding: ActivityDshSetupBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val stepNames = intArrayOf(
        R.string.dsh_setup_step_proot,
        R.string.dsh_setup_step_scripts,
        R.string.dsh_setup_step_rootfs,
        R.string.dsh_setup_step_check,
    )
    private val stepBindings = arrayOfNulls<ItemDshSetupStepBinding>(4)

    private var finished = false
    private var observing = false

    /**
     * BL-074：磁盘事实刷新是否处于"进行中"阶段（[beginPrepare] 置 true，
     * [DshBootstrap.Progress.Done] / 失败置 false）。
     *
     * 解压的数分钟里 [refreshStepStates] 查到的 rootfs 仍未就绪；此时把 rootfs 行显示为
     * "进行中"而不是"等待中"（"进行中"文案 dsh_setup_state_running 已存在，三语言齐全）。
     * 只在**未就绪**且阶段进行中时垫"进行中"；磁盘事实变为就绪时 applyStep 照常落"已就绪"。
     */
    private var prepareRunning = false

    /**
     * BL-070：已收到过 [DshBootstrap.Progress.Done]（完成已到、尚未兑现进首页）。
     *
     * 解压完成时若本页不在前台，`enterMain` 的 `startActivity` 会被后台启动限制（BAL）
     * 静默拦截；由本标志配合 [onResume] / [deliverPendingDone] 补偿兑现进入。
     * 置位：进度收集器收到 Done 时；[enterMain] 因不在前台而推迟时保持。
     * 清理点：新一轮开始（[beginPrepare]）、失败（[showFailure]）、真正进入首页（[enterMain]）。
     */
    private var donePending = false

    /**
     * BL-070：本实例当前是否处于前台（`onResume` 置 true、`onPause` 置 false）。
     *
     * [enterMain] 用它区分两种时机：前台 = 正常导航（行为与原实现一致）；
     * 后台（仅暂停未销毁）= `startActivity` 会被后台启动限制（BAL）静默拦截、随后的
     * `finish()` 还会把任务清掉，于是推迟到 [onResume] 兑现。不用 `lifecycle.currentState`：
     * 其在 `super.onResume()` 返回时刻是否已到 RESUMED 与依赖版本有关，本字段没有这个时序疑问。
     */
    private var resumed = false

    /**
     * BL-070：本实例是否是当前这轮解压的**发起方**（调过 [DshBootstrap.install]，
     * 因此有一条 [afterPrepareReady] 在跑，负责完成后的自检与导航）。
     *
     * 只有两个写入点（都在 [beginPrepare]）：发起新一轮 → true；忙态接回别人跑的任务
     * （BL-067 分支，含从外壳中途进入 / 重建恢复）→ false。Done 到达时：
     * - 发起方前台 → 不抢跑，交给走上来的 [afterPrepareReady]（前台正常路径保持不变）；
     * - 非发起方（中途加入）→ 没有那条链，须由本页自行兑现（前台立即、后台等 onResume）。
     */
    private var ownsPrepare = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDshSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DshPaths.loadPaths(this)
        // 与主外壳同一套视觉：主题背景图由 ThemeEngine 提供，直接铺在根容器上
        // （根 id=background、transitionName=background，与启动页/外壳同一写法）
        loadBackground()
        // 背景图是"按当前亮暗模式挑图"的，主题一变必须重铺（onDestroy 注销，防止持有已销毁实例）
        ThemeEngine.getInstance().addRefreshListener(themeRefreshListener)

        buildStepRows()
        ThemeEngine.getInstance().registerEvent(binding.root, themeRefresh)

        binding.btnLogs.setOnClickListener { showLogs() }
        binding.btnRetry.setOnClickListener { beginPrepare() }
        binding.btnRestart.setOnClickListener { restartFromScratch() }

        if (DshBootstrap.isReady()) {
            // 已就绪（上次已装好 / 脚本与 rootfs 都在）：直接放行，不必再走一遍解压
            scope.launch { afterPrepareReady() }
        } else {
            beginPrepare()
        }
    }

    /** 按位置圆角把 4 行拼成"一组"（FCL 分组列表的做法） */
    private fun buildStepRows() {
        val inflater = LayoutInflater.from(this)
        binding.stepList.removeAllViews()
        stepNames.forEachIndexed { index, nameRes ->
            val item = ItemDshSetupStepBinding.inflate(inflater, binding.stepList, false)
            stepBindings[index] = item
            item.root.setBackgroundResource(
                when (index) {
                    0 -> R.drawable.bg_item_rounded_top
                    stepNames.lastIndex -> R.drawable.bg_item_rounded_bottom
                    else -> R.drawable.bg_item_rounded_middle
                }
            )
            // ★ 行底必须跟主题色 tint：文字用的是 auto_text_tint（按主题色算出的浅色），
            //   若行底保持 bg_item_rounded 的纯白，浅色文字落在白底上会看不见
            item.root.backgroundTintList =
                android.content.res.ColorStateList.valueOf(rowTint())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index != 0) topMargin = dp(1) }
            binding.stepList.addView(item.root, lp)
            item.stepName.setText(nameRes)
        }
        refreshStepStates()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * 清单行底色 —— **初始化与主题刷新共用这一个来源**。
     *
     * 用 `ltColor`（主色提亮后的浅色变体）而不是原始 `color`：本项目主题色偏深，行底直接用
     * 原始主色会压掉 auto_text_tint 算出的文字色；`ltColor` 同时是 FCL 自己的列表行惯例
     * （`FCLConstraintLayout` 的 `auto_tint`、`FCLTabLayout` 的 tab 底色都取它），
     * 所以行底与其余页面的卡片是同一个色系。
     *
     * ★ 两边必须是同一个来源：`ltColor` 由 `getColor()`（带亮暗判断）派生，会随亮暗切换整体变化；
     *   若初始化用 `ltColor`、刷新时换成原始的 `color`（不分模式），主题一变色行底就会突变。
     */
    private fun rowTint(): Int = ThemeEngine.getInstance().getTheme().ltColor

    /** 主题切换后重新给清单行上色（行底是 tint 出来的，不随控件自动刷新） */
    private val themeRefresh = Runnable {
        val color = android.content.res.ColorStateList.valueOf(rowTint())
        stepBindings.forEach { it?.root?.backgroundTintList = color }
    }

    /**
     * 按当前亮暗模式加载主界面背景（与外壳同款）。
     * ThemeEngine 的刷新回调是全局异步排队，Activity 销毁后仍未执行的回调无法通过 onDestroy
     * 注销取消，因此这里防 Glide 对已销毁 Activity 加载崩溃。
     */
    private fun loadBackground() {
        if (isDestroyed || isFinishing) return
        ImageUtil.loadInto(
            binding.background,
            ThemeEngine.getInstance().getTheme().getBackground(this)
        )
    }

    /** 主题刷新时重新加载背景（onDestroy 注销，防止持有已销毁实例） */
    private val themeRefreshListener = Runnable { loadBackground() }

    /**
     * 换进度卡左侧的状态图标（下载中 / 已完成 / 失败）。
     *
     * ★ 图标一律走 `setBackgroundResource` 而不是 `setImageResource`：`FCLImageView` 的主题着色
     *   只作用于 `getBackground()`，走 `src` 时 `use_theme_color` 会静默失效 —— 图标将保持
     *   vector 自带的颜色（下载/完成图标自带 `darker_gray`，在深色底上几乎看不见）。
     *
     * ★ 换完必须补一次着色：`FCLImageView` 的上色只发生在**主题刷新时**，运行期换背景图不会
     *   自动带上主题色。取色用 `getColor2()`（按当前亮暗模式取次要色）而不是原始 `.color2` ——
     *   原始值不分模式，`color2` 默认是纯黑（`color2Dark` 才是白），暗色模式下会把图标染黑。
     *   之后主题切换时控件自己的回调会继续维持这个颜色。
     */
    private fun setStateIcon(res: Int) {
        binding.stateIcon.setBackgroundResource(res)
        binding.stateIcon.background?.setTint(ThemeEngine.getInstance().getTheme().getColor2())
    }

    // ===== 准备流程 =====

    /**
     * 开始（或重新进入）准备流程。[forceExtract] = true 时强制重解压（「重新开始」用，BL-069）。
     *
     * ★ BL-067：入口先查 [DshBootstrap.isBusy]。
     *   解压已在跑时再调 [DshBootstrap.install] 会被 busyGuard 挡住只重放进度并**立即返回**，
     *   而随后那条无条件 `afterPrepareReady()` 会在 isReady()==false 的当下直接判"准备失败"
     *   —— 这就是"解压还在跑却红字说失败"的误报根源。忙时改为：只重新订阅进度
     *   （StateFlow 会重放当前进度帧）+ 标题/进度复位为"正在准备"，**绝不**调
     *   afterPrepareReady()，然后直接 return。
     *
     * ★ 顺序有意为之：先订阅、后写轮次状态。`observeProgress()` 首次订阅时 StateFlow 会
     *   **同步重放**当前帧（Main.immediate）—— 重放的旧 Failed 会走 showFailure（清掉
     *   prepareRunning/donePending）；若轮次状态先写，就会被这次重放倒灌回旧值，导致新轮
     *   rootfs 行不显示"进行中"（BL-074）。所以所有轮次状态在订阅之后写入，保证以新轮为准。
     */
    private fun beginPrepare(forceExtract: Boolean = false) {
        val busy = DshBootstrap.isBusy()
        // ownsPrepare 在订阅前归位：Done 重放要按正确语义分流（见字段注释）
        ownsPrepare = !busy
        if (!busy) {
            // 新一轮开始：复位自检行上一轮的残留态（"失败"受 BL-074 守卫保护、不会被普通
            // 磁盘刷新刷回"等待中"，只在这条唯一的启动路径上复位）。接回别人跑的轮次不动它。
            stepBindings[3]?.let { row ->
                setStepIcon(row, R.drawable.ic_baseline_download_24)
                setStepState(row, R.string.dsh_setup_state_waiting)
            }
        }
        observeProgress()

        if (busy) {
            // 已在解压（BL-067）：不发起新任务、不做就绪校验（误报根源），接回进度流即可。
            val cur = DshBootstrap.currentProgress()
            if (cur !is DshBootstrap.Progress.Done && cur !is DshBootstrap.Progress.Failed) {
                // 活帧（Stage/Detail）：重放已画上进度数值，这里把标题/图标归"正在准备"，
                // 并垫上 rootfs 行"进行中"（BL-074）。
                prepareRunning = true
                donePending = false // 进行中的帧作废任何旧完成记录（活帧必然是新一轮）
                binding.btnRetry.visibility = View.GONE
                setStateIcon(R.drawable.ic_baseline_download_24)
                binding.progressTitle.setText(R.string.dsh_setup_title)
                refreshStepStates()
            }
            // Done/Failed 终态帧与 busy==true 同真只出现在 DshBootstrap "先 emit 后清
            // busyGuard" 的极窄窗口：重放已画上正确终态，不覆写；Done 的交付由
            // onResume/立即兑现路径负责（donePending 已在 render 里置位）。
            DshLogBus.append("[setup] beginPrepare while busy: re-observe progress only")
            return
        }

        // 新一轮：supersede 一切旧状态（重放的旧 Done/Failed 都属于上一轮）
        prepareRunning = true
        donePending = false
        binding.btnRetry.visibility = View.GONE
        setStateIcon(R.drawable.ic_baseline_download_24)
        binding.progressTitle.setText(R.string.dsh_setup_title)
        setProgress(getString(R.string.dsh_setup_starting), null, null)
        refreshStepStates()

        DshAppScope.scope.launch {
            DshBootstrap.install(this@DshSetupActivity, OWNER_SETUP, forceExtract)
            // BL-067 延伸（TOCTOU 兜底）：install 返回后若 busy 仍为 true，说明本调用被
            // busyGuard 挡下（另一入口在毫秒级窗口内先起跑），此时调 afterPrepareReady
            // 就是同一个误报源。跳过；进行中的那轮自己会走到 Done 与导航。
            if (DshBootstrap.isBusy()) {
                DshLogBus.append("[setup] after install: another task is running, skip ready-check")
                return@launch
            }
            afterPrepareReady()
        }
    }

    private fun observeProgress() {
        if (observing) return
        observing = true
        scope.launch { DshBootstrap.progress.collect { render(it) } }
    }

    private fun render(p: DshBootstrap.Progress?) {
        when (p) {
            is DshBootstrap.Progress.Stage -> {
                binding.progressTitle.setText(R.string.dsh_setup_title)
                setProgress(p.text, null, p.fraction)
                // 新一轮进度到达：作废可能残留的旧 Done 记录（onResume 补偿只在完成真正
                // 到达后兑现；Done 是每轮的最后一帧，其后不会再出现 Stage/Detail）
                donePending = false
                refreshStepStates()
            }
            is DshBootstrap.Progress.Detail -> {
                setProgress(getString(R.string.dsh_setup_step_rootfs), p.detail, null)
                // 同 Stage：解压明细到达即说明新一轮在跑
                donePending = false
                refreshStepStates()
            }
            is DshBootstrap.Progress.Done -> {
                setStateIcon(R.drawable.ic_baseline_done_24)
                setProgress(getString(R.string.dsh_setup_title_done), null, 1.0)
                // 解压期结束：立即按磁盘事实重刷清单（把垫着的"进行中"落成"已就绪"）
                prepareRunning = false
                refreshStepStates()
                // ★ BL-070：记下"完成已到、尚未兑现进首页"。前台正常路径不变 —— 发起方
                //   （ownsPrepare）由走在上面的 afterPrepareReady 自检后导航；中途加入的
                //   实例没有那条链，须由本页兑现（前台立即、后台等 onResume 补走）。
                donePending = true
                if (resumed && !ownsPrepare) deliverPendingDone()
            }
            is DshBootstrap.Progress.Failed -> showFailure(p.reason)
            null -> Unit
        }
    }

    private fun setProgress(stage: String?, detail: String?, fraction: Double?) {
        binding.progressStage.text = stage.orEmpty()
        if (detail.isNullOrEmpty()) {
            binding.progressDetail.visibility = View.GONE
        } else {
            binding.progressDetail.visibility = View.VISIBLE
            binding.progressDetail.text = detail
        }
        // WaveProgressView：0..1 为确定进度，负值表示不确定（FCL 的约定）
        if (fraction == null) {
            binding.progressBar.setProgress(-1f)
            binding.progressPercent.text = ""
        } else {
            binding.progressBar.setProgress(fraction.toFloat().coerceIn(0f, 1f))
            binding.progressPercent.text =
                getString(R.string.dsh_setup_percent, (fraction * 100).toInt())
        }
    }

    /** 失败：标题变红、写明原因、露出「重试」；停在原页不自动进首页 */
    private fun showFailure(reason: String) {
        // 失败到达后 Done 已不可能再兑现：清补偿标志与进行中记号（防旧状态在下一轮误触发）
        donePending = false
        prepareRunning = false
        setStateIcon(R.drawable.ic_baseline_warning_24)
        binding.progressTitle.setText(R.string.dsh_setup_title_failed)
        setProgress(reason, null, null)
        binding.progressBar.setProgress(0f)
        binding.progressPercent.text = ""
        binding.btnRetry.visibility = View.VISIBLE
        refreshStepStates()
    }

    /** 任务体结束后：就绪 → 自检 → 进首页；否则停在原地显示失败 */
    private suspend fun afterPrepareReady() {
        val ready = withContext(Dispatchers.IO) { DshBootstrap.isReady() }
        if (!ready) {
            if (binding.btnRetry.visibility != View.VISIBLE) {
                val reason = (DshBootstrap.currentProgress() as? DshBootstrap.Progress.Failed)?.reason
                    ?: DshBootstrap.missingSummary()
                    ?: getString(R.string.dsh_bootstrap_missing_short)
                withContext(Dispatchers.Main) { showFailure(reason) }
            }
            return
        }

        // 自检：真正在 proot 里跑一遍最小链路（probe.sh 9 项），把问题拦在进首页之前
        withContext(Dispatchers.Main) {
            stepBindings[3]?.let { setStepState(it, R.string.dsh_setup_state_running) }
        }
        val report = withContext(Dispatchers.IO) { DshBootstrap.verify(this@DshSetupActivity) }
        withContext(Dispatchers.Main) {
            val ok = report.ok
            stepBindings[3]?.let { row ->
                setStepIcon(
                    row,
                    if (ok) R.drawable.ic_baseline_done_24 else R.drawable.ic_baseline_warning_24
                )
                setStepState(
                    row,
                    if (ok) R.string.dsh_setup_state_ready else R.string.dsh_setup_state_failed
                )
            }
            if (ok) {
                markCompleted()
                enterMain()
            } else {
                showFailure(report.detail)
            }
        }
    }

    /**
     * 刷新准备项状态（查磁盘事实，不依赖进度回调）。
     *
     * ★ 磁盘 stat（resolveProotBin/resolveProotLoader/rootfsLooksUsable 都是 File.isFile/exists）下沉到 IO 线程：
     *   本函数在 onCreate(buildStepRows) 与每个进度回调(render) 里都会被调用，原来直接在主线程
     *   stat —— 解压期间 5Hz 的 Detail 回调会把主线程一点点拖住。查完回到主线程（scope 为
     *   Main.immediate）再更新图标/文案。
     */
    private fun refreshStepStates() {
        scope.launch {
            val (prootOk, scriptsOk, rootfsOk) = withContext(Dispatchers.IO) {
                Triple(
                    DshPaths.resolveProotBin(FCLPath.NATIVE_LIB_DIR).isFile &&
                        DshPaths.resolveProotLoader(FCLPath.NATIVE_LIB_DIR).isFile,
                    File(DshPaths.SCRIPTS_DIR, "start-dsh.sh").isFile,
                    DshPaths.rootfsLooksUsable()
                )
            }

            applyStep(0, prootOk)
            applyStep(1, scriptsOk)
            applyStep(2, rootfsOk)
            // 自检行由 afterPrepareReady 负责置位；未开始前显示"等待中"。
            // ★ BL-074 守卫："进行中/已就绪/失败"是不可被静默覆写的既有呈现 —— 失败被
            //   覆写回"等待中"就是 R12-B4-3 清单与卡片矛盾的直接成因。判据读行上记录的状态
            //   资源 id（setStepState 打的 tag），复位只发生在 beginPrepare（新一轮唯一入口）。
            val check = stepBindings[3] ?: return@launch
            if (setupStepStateOverwritable(check.stepState.tag as? Int)) {
                setStepIcon(check, R.drawable.ic_baseline_download_24)
                setStepState(check, R.string.dsh_setup_state_waiting)
            }
        }
    }

    private fun applyStep(index: Int, ok: Boolean) {
        val b = stepBindings[index] ?: return
        setStepIcon(b, if (ok) R.drawable.ic_baseline_done_24 else R.drawable.ic_baseline_download_24)
        // BL-074 判定走纯函数（单测见 W3DaTest）：解压进行中、rootfs 行未就绪 → "进行中"，
        // 让数分钟的解压期间清单与进度卡一致（R12-B4-3）；其余保持"等待中"。
        setStepState(b, setupStepStateRes(rowOk = ok, isRootfsRow = index == 2, preparing = prepareRunning))
    }

    /**
     * 换清单行的状态图标。与 [setStateIcon] 同一条规则：走 `setBackgroundResource`（着色只作用于
     * 背景图），换完按 `getColor2()` 补一次主题色 —— 行图标是运行期反复切换的，不补就会保持
     * vector 自带色，与主题无关。
     */
    private fun setStepIcon(item: ItemDshSetupStepBinding, res: Int) {
        item.stepIcon.setBackgroundResource(res)
        item.stepIcon.background?.setTint(ThemeEngine.getInstance().getTheme().getColor2())
    }

    /**
     * 设置行状态：文案 + 把资源 id 记在 `tag` 上。
     *
     * ★ BL-074：覆写守卫（[setupStepStateOverwritable]）读 tag 而不是比对 `text` ——
     *   文本是**本地化的**，拿它当状态判据会在语言/主题变化后失配；资源 id 是稳定事实。
     */
    private fun setStepState(item: ItemDshSetupStepBinding, res: Int) {
        item.stepState.setText(res)
        item.stepState.tag = res
    }

    /**
     * 「重新开始」：删掉版本标记让下次解压重跑（比重装 App 轻，且能救"半成品"）。
     * 顺带清掉上次解压遗留的 `rootfs.tmp`/`rootfs.old`，避免半成品目录干扰重跑或白占空间。
     *
     * ★ BL-068：解压进行中禁止重开 —— 确认分支会递归删除正在写入的 `rootfs.tmp`，
     *   把进行中的解压搞成"布局异常"（R12-B4-2 的 tmp 破坏路径）。忙时只 toast 不开对话框
     *   也不删任何文件。入口与确认分支**都**查：对话框弹着的时候解压可能被别处启动。
     *
     * ★ BL-069：确认分支改为直接调 [DshBootstrap.install] 并带 `forceExtract = true`
     *   —— isLatest 在 Android 上恒 true（K20）且 rootfsLooksUsable 只查布局，"version 文件
     *   被删但布局完好"这类坏底座会被增量判定永久跳过重解压；删除 version + 强制参数双保险。
     */
    private fun restartFromScratch() {
        // BL-068 入口守卫：进行中直接拒绝（这是 BL-069 tmp 破坏路径的源头）
        if (DshBootstrap.isBusy()) {
            Toast.makeText(this, R.string.dsh_setup_restart_busy, Toast.LENGTH_SHORT).show()
            return
        }
        FCLAlertDialog.Builder(this)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(getString(R.string.dsh_setup_restart))
            .setMessage(getString(R.string.dsh_setup_restart_hint))
            .setPositiveButton(getString(R.string.dialog_positive)) {
                scope.launch {
                    // BL-068：对话框期间解压可能已被别处启动（本页重试 / 首页任务区），
                    // 确认分支二次守卫 —— 宁可少删一轮，也不删正在写入的 .tmp
                    if (DshBootstrap.isBusy()) {
                        Toast.makeText(
                            this@DshSetupActivity,
                            R.string.dsh_setup_restart_busy,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        runCatching { File(DshPaths.ROOTFS_DIR, "version").delete() }
                        runCatching { File(DshPaths.SCRIPTS_DIR, "version").delete() }
                        // 清掉上次解压留下的半成品/备份目录，让"重新开始"从干净状态重跑。
                        // （.tmp/.old 的可回滚 rename 替换语义与 .old 删除时序仍归 extractRootfs/T-B2，
                        //  这里只扫"遗留目录"，不介入那条替换逻辑。）
                        runCatching { File("${DshPaths.ROOTFS_DIR}.tmp").deleteRecursively() }
                        runCatching { File("${DshPaths.ROOTFS_DIR}.old").deleteRecursively() }
                    }
                    // BL-069：forceExtract 强制重解压 —— 不能退回 beginPrepare() 的无参形态：
                    // version 文件被删 + 布局仍完好时，增量判定可能仍跳过 rootfs 重解
                    beginPrepare(forceExtract = true)
                }
            }
            .setNegativeButton(getString(R.string.dialog_negative), null)
            .create()
            .show()
    }

    private fun showLogs() {
        val text = DshLogBus.export().takeLast(LOG_TAIL)
        if (text.isBlank()) {
            Toast.makeText(this, R.string.dsh_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        FCLAlertDialog.Builder(this)
            .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
            .setTitle(getString(R.string.dsh_setting_logs))
            .setMessage(text)
            .setNegativeButton(getString(R.string.dialog_positive), null)
            .create()
            .show()
    }

    private fun markCompleted() {
        getSharedPreferences("launcher", MODE_PRIVATE)
            .edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    /**
     * 成功后的导航：回到**已有的**外壳实例。
     *
     * ★ 修 N2：原实现 `startActivity(Intent(this, DshMainActivity::class.java))` 不带任何 flag。
     * 从外壳进入本页时（[com.dsh.ui.shell.DshLauncher.openSetup]），栈里已经有了一个外壳 ——
     * 不带 flag 的 startActivity 会**新建第二个外壳**压上去，返回栈变成 [外壳][外壳]，
     * 于是"返回一次"只退到那个多余的外壳，用户看着像返回失灵。
     *
     * 改用 [DshMainActivity.intentForTab]，与项目里其余所有"回外壳"的跳转（WebView 页
     * `DshWebViewActivity`）走同一条路径：它带 `SINGLE_TOP | CLEAR_TOP`，
     * 已有外壳会被复用（走 `onNewIntent`）而不是新建。理由：
     * 1. 一致性 —— 对外壳的所有跳转只有一种写法，以后改 flag 只改一处；
     * 2. 复用而非复制 flag 字面量 —— `intentForTab` 是外壳自己的 API，flag 语义归它负责。
     *
     * 关于"不切 tab"：[DshShellHost.TAB_INSTANCES] 是首页（外壳启动时的默认页，
     * 见 `DshMainActivity.onCreate` 里 `binding.instances.setSelected(true)`）。
     * 准备完成本来就该落在首页 —— 那里才有实例列表；若不传 tab，`onNewIntent` 收到 -1
     * 会**保持**在外壳当前所在的页，从"实例行 → 启动 → 缺底座 → 准备页"这条路回来时
     * 会停在启动时那一页，行为反而不确定。所以显式指定首页，让两条入口的结果一致。
     */
    private fun enterMain() {
        if (finished) return
        // ★ BL-070：不在前台时推迟导航。Android 10+ 的后台启动限制（BAL）会**静默拦截**
        //   后台 Activity 的 startActivity，若照旧继续走 `finish()`，首启场景（本页是任务根）
        //   连任务都会消失 —— 用户回到 App 只见"已就绪"页、无路可走（R12-B4-1）。
        //   推迟到 onResume（此刻已在前台，不受 BAL 限制）兑现；donePending 保持置位。
        if (!resumed) {
            donePending = true
            DshLogBus.append("[setup] enterMain deferred: activity not resumed")
            return
        }
        finished = true
        // BL-070：进入已兑现，清掉补偿标志（防 onResume 重复导航；幂等）
        donePending = false
        startActivity(
            DshMainActivity.intentForTab(this, DshShellHost.TAB_INSTANCES),
            ActivityOptionsCompat.makeCustomAnimation(this, 0, 0).toBundle()
        )
        finish()
    }

    /**
     * BL-070 补偿：完成发生在后台时兑现进入首页；同时维护 [resumed]（进入首页判定用）。
     *
     * 背景：解压完成后由 [afterPrepareReady] 链自动导航，但那条链的 `startActivity` 在
     * App 处于后台时会被 Android 10+ 的后台启动限制（BAL）静默拦截 —— 用户回到本页
     * 只见"运行环境已就绪"，没有任何"进入"入口（R12-B4-1）。[enterMain] 在非前台时
     * 只置 [donePending] 并返回，此处补走兑现。此刻 Activity 已在前台，不受 BAL 拦截。
     * Done 到达时若本页就在前台，同样经 [deliverPendingDone] 立即兑现（不等 afterPrepareReady）：
     * 后者只挂在"发起任务的那个实例"上，从外壳中途进入的另一个实例收不到它的导航。
     */
    override fun onResume() {
        super.onResume()
        resumed = true
        if (!finished && donePending) deliverPendingDone()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    /**
     * BL-070：兑现"完成已到、尚未进首页"。
     *
     * ★ 兑现前用 IO 线程复核 [DshBootstrap.isReady]（磁盘事实）：进度流是 StateFlow，
     *   新订阅会重放**上一轮**遗留的 Done 帧（如"重新开始"删 version 后的新实例）——
     *   只信磁盘，重放的旧 Done 在对不上事实时自然不交付。复核期间页面可能被回收
     *   （finished）或有新轮开始（beginPrepare 会清掉 donePending），回主线程后须复查。
     * 不进首页时的行为与补偿前完全一致（停在"已就绪"页，可自由操作）。
     */
    private fun deliverPendingDone() {
        if (finished || !donePending) return
        scope.launch {
            val ready = withContext(Dispatchers.IO) { DshBootstrap.isReady() }
            if (!finished && donePending && ready) {
                DshLogBus.append("[setup] pending done delivered: entering main")
                markCompleted()
                enterMain()
            }
        }
    }

    /**
     * 返回键：本页有两个入口，行为必须分开（修 N1）。
     *
     * 覆写 [onBackPressed] 而不是 `onBackPressedDispatcher.addCallback`：
     * 本页用的是 FCL 的既有做法（[DshMainActivity] 也是覆写 `onBackPressed`），
     * 且无需在 onDestroy 里反注册回调。
     *
     * 判据用 [isTaskRoot]，不用完成标记 [isCompleted]：
     * - `isTaskRoot == true` → 栈里只有本页（首启门禁，Splash 已 finish）→ 没有"上一页"可回。
     *   直接 `super.onBackPressed()` 会 finish 掉任务根，任务从最近任务列表里消失
     *   （用户看到的是"App 突然没了"）。改成 [moveTaskToBack]：等同按 HOME，
     *   任务与正在跑的解压都留着，从最近任务点回来还是本页。
     * - `isTaskRoot == false` → 下面是外壳 → 普通 finish，回外壳。
     *
     * 为什么不用 [isCompleted] 区分：它只说明"以前准备好过"，与"栈里有没有外壳"是两件事。
     * 从外壳进入时它必然为 true（门禁早就放行了），但首启门禁之外的第三条路径
     * （例如以后某处直接 startActivity 到本页）也会是 true —— 判据会跟着入口漂移。栈事实不会。
     *
     * 解压进行中按返回同样适用：任务跑在 [com.dsh.core.DshAppScope] 的进程级作用域里
     * （见 `beginPrepare` 的注释），本页销毁不影响它，回外壳后首页任务区照样显示进度。
     */
    override fun onBackPressed() {
        if (isTaskRoot) {
            // 首启门禁入口：Back ≠ 退出 App，回桌面即可（任务保留在最近任务里）
            moveTaskToBack(true)
        } else {
            // 从外壳进入：Back = 回外壳
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        ThemeEngine.getInstance().removeRefreshListener(themeRefreshListener)
        ThemeEngine.getInstance().unregisterEvent(binding.root)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val OWNER_SETUP = "setup"
        private const val LOG_TAIL = 4000
        private const val KEY_SETUP_DONE = "dsh_setup_completed"

        /** 本机是否完成过一次运行环境准备（首启门禁） */
        @JvmStatic
        fun isCompleted(context: Context): Boolean =
            context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                .getBoolean(KEY_SETUP_DONE, false)
    }
}

/**
 * BL-074（纯函数，便于单测，见 W3DaTest）：rootfs 行（清单第 3 行）未就绪时该显示的状态文案。
 *
 * 解压的数分钟里磁盘事实一直是"未就绪"，若无此垫层，rootfs 行整段都显示"等待中"，
 * 与进度卡的活跃进度自相矛盾（R12-B4-3）。preparing = 本轮解压进行中。
 *
 * @return 行状态资源 id；已就绪时返回"已就绪"
 */
fun setupStepStateRes(rowOk: Boolean, isRootfsRow: Boolean, preparing: Boolean): Int = when {
    rowOk -> R.string.dsh_setup_state_ready
    isRootfsRow && preparing -> R.string.dsh_setup_state_running
    else -> R.string.dsh_setup_state_waiting
}

/**
 * BL-074（纯函数，便于单测，见 W3DaTest）：磁盘事实刷新是否可覆写某行当前状态。
 *
 * 行上记录的状态资源 id（`tag`）为 null（尚未置位）或"等待中"时才允许覆写；
 * "进行中/已就绪/失败"都是**有意置上的呈现**，不得被普通磁盘刷新静默覆盖 ——
 * 自检中的"进行中"被刷回"等待中"、失败被刷回"等待中"（R12-B4-3 清单与卡片矛盾的
 * 直接成因）都属此类。复位这些状态只发生在 [DshSetupActivity.beginPrepare]
 * （新一轮开始的唯一入口，显式置回"等待中"）。
 */
fun setupStepStateOverwritable(currentRes: Int?): Boolean =
    currentRes == null || currentRes == R.string.dsh_setup_state_waiting
