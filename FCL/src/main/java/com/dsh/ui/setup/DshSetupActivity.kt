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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDshSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DshPaths.loadPaths(this)
        // 与主外壳同一套视觉：主题背景图 + 控件取色由 ThemeEngine 驱动
        ImageUtil.loadInto(
            binding.bgCover,
            ThemeEngine.getInstance().getTheme().getBackground(this)
        )

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
                android.content.res.ColorStateList.valueOf(ThemeEngine.getInstance().getTheme().ltColor)
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

    /** 主题切换后重新给清单行上色（行底是 tint 出来的，不随控件自动刷新） */
    private val themeRefresh = Runnable {
        val color = android.content.res.ColorStateList.valueOf(
            ThemeEngine.getInstance().getTheme().color
        )
        stepBindings.forEach { it?.root?.backgroundTintList = color }
    }

    // ===== 准备流程 =====

    private fun beginPrepare() {
        binding.btnRetry.visibility = View.GONE
        binding.stateIcon.setImageResource(R.drawable.ic_baseline_download_24)
        binding.progressTitle.setText(R.string.dsh_setup_title)
        setProgress(getString(R.string.dsh_setup_starting), null, null)
        refreshStepStates()
        observeProgress()

        DshAppScope.scope.launch {
            DshBootstrap.install(this@DshSetupActivity, OWNER_SETUP)
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
                refreshStepStates()
            }
            is DshBootstrap.Progress.Detail -> {
                setProgress(getString(R.string.dsh_setup_step_rootfs), p.detail, null)
                refreshStepStates()
            }
            is DshBootstrap.Progress.Done -> {
                binding.stateIcon.setImageResource(R.drawable.ic_baseline_done_24)
                setProgress(getString(R.string.dsh_setup_title_done), null, 1.0)
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
        binding.stateIcon.setImageResource(R.drawable.ic_baseline_warning_24)
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
            stepBindings[3]?.stepState?.setText(R.string.dsh_setup_state_running)
        }
        val report = withContext(Dispatchers.IO) { DshBootstrap.verify(this@DshSetupActivity) }
        withContext(Dispatchers.Main) {
            val ok = report.ok
            stepBindings[3]?.stepIcon?.setImageResource(
                if (ok) R.drawable.ic_baseline_done_24 else R.drawable.ic_baseline_warning_24
            )
            stepBindings[3]?.stepState?.text =
                getString(if (ok) R.string.dsh_setup_state_ready else R.string.dsh_setup_state_failed)
            if (ok) {
                markCompleted()
                enterMain()
            } else {
                showFailure(report.detail)
            }
        }
    }

    /** 刷新准备项状态（查磁盘事实，不依赖进度回调） */
    private fun refreshStepStates() {
        val prootOk = DshPaths.resolveProotBin(FCLPath.NATIVE_LIB_DIR).isFile &&
            DshPaths.resolveProotLoader(FCLPath.NATIVE_LIB_DIR).isFile
        val scriptsOk = File(DshPaths.SCRIPTS_DIR, "start-dsh.sh").isFile
        val rootfsOk = DshPaths.rootfsLooksUsable()

        applyStep(0, prootOk)
        applyStep(1, scriptsOk)
        applyStep(2, rootfsOk)
        // 自检行由 afterPrepareReady 负责置位；未开始前显示"等待中"
        val check = stepBindings[3] ?: return
        if (check.stepState.text != getString(R.string.dsh_setup_state_running) &&
            check.stepState.text != getString(R.string.dsh_setup_state_ready)
        ) {
            check.stepIcon.setImageResource(R.drawable.ic_baseline_download_24)
            check.stepState.setText(R.string.dsh_setup_state_waiting)
        }
    }

    private fun applyStep(index: Int, ok: Boolean) {
        val b = stepBindings[index] ?: return
        b.stepIcon.setImageResource(
            if (ok) R.drawable.ic_baseline_done_24 else R.drawable.ic_baseline_download_24
        )
        b.stepState.setText(if (ok) R.string.dsh_setup_state_ready else R.string.dsh_setup_state_waiting)
    }

    /** 「重新开始」：删掉版本标记让下次解压重跑（比重装 App 轻，且能救"半成品"） */
    private fun restartFromScratch() {
        FCLAlertDialog.Builder(this)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(getString(R.string.dsh_setup_restart))
            .setMessage(getString(R.string.dsh_setup_restart_hint))
            .setPositiveButton(getString(R.string.dialog_positive)) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { File(DshPaths.ROOTFS_DIR, "version").delete() }
                        runCatching { File(DshPaths.SCRIPTS_DIR, "version").delete() }
                    }
                    beginPrepare()
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
        finished = true
        startActivity(
            DshMainActivity.intentForTab(this, DshShellHost.TAB_INSTANCES),
            ActivityOptionsCompat.makeCustomAnimation(this, 0, 0).toBundle()
        )
        finish()
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
