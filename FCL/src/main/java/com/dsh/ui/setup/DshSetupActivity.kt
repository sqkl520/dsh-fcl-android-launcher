package com.dsh.ui.setup

import android.content.Context
import android.content.Intent
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
        if (fraction == null) {
            binding.progressBar.isIndeterminate = true
            binding.progressPercent.text = ""
        } else {
            binding.progressBar.isIndeterminate = false
            binding.progressBar.progress = (fraction * 1000).toInt().coerceIn(0, 1000)
            binding.progressPercent.text =
                getString(R.string.dsh_setup_percent, (fraction * 100).toInt())
        }
    }

    /** 失败：标题变红、写明原因、露出「重试」；停在原页不自动进首页 */
    private fun showFailure(reason: String) {
        binding.stateIcon.setImageResource(R.drawable.ic_baseline_warning_24)
        binding.progressTitle.setText(R.string.dsh_setup_title_failed)
        setProgress(reason, null, null)
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
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

    private fun enterMain() {
        if (finished) return
        finished = true
        startActivity(
            Intent(this, DshMainActivity::class.java),
            ActivityOptionsCompat.makeCustomAnimation(this, 0, 0).toBundle()
        )
        finish()
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
