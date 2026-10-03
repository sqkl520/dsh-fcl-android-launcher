package com.dsh.ui.shell

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
import com.dsh.ui.DshSettingsActivity
import com.mio.util.ImageUtil
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.dsh.ui.DshWebViewActivity
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshMainBinding
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.view.FCLMenuView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * dsh 启动器主外壳（阶段 3，横屏 + 右侧面板）。
 *
 * 形态照搬 FCL 的 MC 启动器：左侧 [FCLMenuView] 菜单 + 中间 ViewPager2 内容区
 * + 右侧面板（当前实例卡 + 启动/停止 + 打开界面）+ 动态岛标题。
 * 本类实现 [DshShellHost]，为页面提供跨 tab 跳转与打开详情 Activity 的能力。
 *
 * **刻意不触碰任何 MC 单例**（无 ConfigHolder / RendererManager）。
 */
class DshMainActivity : FCLActivity(), DshShellHost {

    private lateinit var binding: ActivityDshMainBinding
    private lateinit var uiManager: DshUIManager
    private lateinit var menus: List<FCLMenuView>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 右面板当前展示的实例（供启动按钮回调使用） */
    private var panelInstance: DshInstance? = null

    /** 动态壁纸（FCL 同款：filesDir 下有 live.mp4 才启用） */
    private var mediaPlayer: MediaPlayer? = null
    private var videoPosition = 0

    /** 待处理的选择图片回调（一次只允许一个选择请求） */
    private var pendingImagePick: ((android.net.Uri?) -> Unit)? = null

    /**
     * 系统图片选择器。
     * 必须在 Activity 进入 STARTED 之前注册 —— 页面是 ViewPager 懒创建的
     * （进入设置 tab 时才创建，此时 Activity 已 RESUMED），若把注册放在页面里会抛异常，
     * 所以统一在这里注册，页面通过 [pickImage] 发起请求。
     */
    private val pickImageLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            val cb = pendingImagePick
            pendingImagePick = null
            cb?.invoke(uri)
        }

    override val activity: FCLActivity get() = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDshMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // FCL 原版的核心视觉：背景图由 ThemeEngine 统一提供，跟随亮暗主题切换。
        loadBackground()
        ThemeEngine.getInstance().addRefreshListener(themeRefreshListener)
        setupLiveBackground()

        DshPaths.loadPaths(this)
        DshInstances.init()

        uiManager = DshUIManager(this, binding.uiLayout)
        uiManager.init()

        // 菜单顺序必须与 DshUIManager.titles / factories 一致：实例/管理/下载/日志/设置
        menus = listOf(
            binding.instances, binding.manage, binding.download, binding.logs, binding.setting
        )
        menus.forEachIndexed { index, menu ->
            menu.setOnSelectListener { selected ->
                uiManager.switchTo(index)
                menus.forEach { if (it !== selected && it.isSelected) it.setSelected(false) }
            }
        }
        // FCL 的返回项：交给本 Activity 的返回逻辑（非实例页 → 回实例页；否则退出）
        binding.back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        uiManager.pageSelectedListener = { pos ->
            binding.title.setTextWithAnim(getString(uiManager.titles[pos]))
            if (!menus[pos].isSelected) menus[pos].setSelected(true)
        }

        binding.instances.setSelected(true)
        binding.title.refresh(getString(uiManager.titles[0]))

        // 详情页（实例设置 / WebView）可带 EXTRA_OPEN_TAB 跳回指定 tab
        intent?.getIntExtra(EXTRA_OPEN_TAB, -1)?.takeIf { it >= 0 }?.let { openTab(it) }

        setupRightPanel()
    }

    /**
     * 按当前亮暗模式加载主界面背景（FCL 同款）。
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

    // --- 动态壁纸（照搬 FCL MainActivity 的最小实现） -------------------------

    private fun shouldPlayVideo(): Boolean = File(FCLPath.LIVE_BACKGROUND_PATH).exists()

    private fun setupLiveBackground() {
        if (shouldPlayVideo()) {
            binding.videoView.visibility = View.VISIBLE
            binding.videoView.setVideoPath(FCLPath.LIVE_BACKGROUND_PATH)
            binding.videoView.setOnPreparedListener {
                mediaPlayer = it
                it.isLooping = true
                binding.videoView.start()
            }
            binding.videoView.setOnCompletionListener {
                binding.videoView.seekTo(0)
                binding.videoView.start()
            }
            binding.videoView.setOnErrorListener { _, _, _ ->
                mediaPlayer = null
                true
            }
        } else {
            mediaPlayer = null
            binding.videoView.visibility = View.GONE
            runCatching { binding.videoView.stopPlayback() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getIntExtra(EXTRA_OPEN_TAB, -1).takeIf { it >= 0 }?.let { openTab(it) }
    }

    private fun openTab(position: Int) {
        if (position in menus.indices) {
            menus[position].setSelected(true)
        }
    }

    /** 右面板：当前实例卡 + 启动/停止 + 打开界面 */
    private fun setupRightPanel() {
        binding.rightBtnWebui.setOnClickListener { openWebView() }
        binding.rightBtnStart.setOnClickListener {
            val inst = panelInstance ?: return@setOnClickListener
            val running = DshRuntime.runningInstanceId() == inst.id
            if (running) {
                DshRuntime.stop("用户停止")
            } else {
                DshLauncher.startInstance(
                    activity = this,
                    inst = inst,
                    scope = scope,
                    onOpenSettings = { openInstanceSettings(it.id) },
                    onOpenLogs = { switchTab(DshShellHost.TAB_LOGS) },
                    onPrepareRuntime = { DshLauncher.openSetup(this) },
                    onStarted = { openWebView() }
                )
            }
        }

        scope.launch {
            combine(
                DshInstances.instances,
                DshInstances.selectedId,
                DshRuntime.state
            ) { list, selectedId, state -> Triple(list, selectedId, state) }
                .collect { (list, selectedId, state) ->
                    val inst = selectedId?.let { id -> list.firstOrNull { it.id == id } }
                        ?: list.firstOrNull()
                    panelInstance = inst
                    renderRightPanel(inst, state)
                }
        }
    }

    private fun renderRightPanel(inst: DshInstance?, state: DshRuntime.State) {
        if (inst == null) {
            binding.rightInstanceName.text = getString(R.string.dsh_right_no_instance)
            binding.rightInstanceHint.text = ""
            binding.rightBtnStart.isEnabled = false
            binding.rightBtnStart.setText(R.string.dsh_action_start)
            binding.rightBtnWebui.isEnabled = false
            return
        }
        val running = state is DshRuntime.State.Running && state.instanceId == inst.id
        binding.rightInstanceName.text = inst.name
        binding.rightInstanceHint.text = describeState(inst, state)
        binding.rightBtnStart.isEnabled = inst.state == DshInstance.State.READY || running
        binding.rightBtnStart.setText(if (running) R.string.dsh_action_stop else R.string.dsh_action_start)
        binding.rightBtnWebui.isEnabled = running
    }

    private fun describeState(inst: DshInstance, state: DshRuntime.State): String = when {
        state is DshRuntime.State.Running && state.instanceId == inst.id ->
            getString(R.string.dsh_state_running_short, inst.name, state.port)
        state is DshRuntime.State.Starting && state.instanceId == inst.id ->
            getString(R.string.dsh_state_starting, inst.name)
        state is DshRuntime.State.Stopping && state.instanceId == inst.id ->
            getString(R.string.dsh_state_stopping, inst.name)
        else -> getString(
            when (inst.state) {
                DshInstance.State.NOT_INSTALLED -> R.string.dsh_state_not_installed
                DshInstance.State.INSTALLING -> R.string.dsh_state_installing
                DshInstance.State.READY -> R.string.dsh_state_ready
                DshInstance.State.BROKEN -> R.string.dsh_state_broken
            }
        )
    }

    // ===== DshShellHost =====

    override fun switchTab(position: Int) {
        if (position in menus.indices) menus[position].setSelected(true)
    }

    override fun openInstanceSettings(instanceId: String) {
        startActivity(
            Intent(this, DshSettingsActivity::class.java)
                .putExtra(DshSettingsActivity.EXTRA_INSTANCE_ID, instanceId)
        )
    }

    override fun openWebView() {
        startActivity(Intent(this, DshWebViewActivity::class.java))
    }

    override fun pickImage(onPicked: (android.net.Uri?) -> Unit) {
        pendingImagePick = onPicked
        runCatching { pickImageLauncher.launch(arrayOf("image/*")) }
            .onFailure {
                pendingImagePick = null
                onPicked(null)
            }
    }

    override fun onBackPressed() {
        if (binding.uiLayout.currentItem != DshShellHost.TAB_INSTANCES) {
            binding.instances.setSelected(true)
        } else {
            super.onBackPressed()
        }
    }

    /** 动态壁纸跟随界面暂停/恢复（FCL 同款） */
    override fun onPause() {
        super.onPause()
        if (shouldPlayVideo() && binding.videoView.isPlaying) {
            videoPosition = binding.videoView.currentPosition
            binding.videoView.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (shouldPlayVideo() && !binding.videoView.isPlaying) {
            binding.videoView.seekTo(videoPosition)
            binding.videoView.start()
        }
    }

    override fun onDestroy() {
        ThemeEngine.getInstance().removeRefreshListener(themeRefreshListener)
        if (shouldPlayVideo()) {
            mediaPlayer = null
            runCatching { binding.videoView.stopPlayback() }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** 详情页用它请求外壳打开指定 tab（见 [DshShellHost] 的 TAB_* 常量） */
        const val EXTRA_OPEN_TAB = "dsh_open_tab"

        /** 构造"回到外壳并切到某 tab"的 Intent（singleTop，复用已有实例） */
        fun intentForTab(context: Context, tab: Int): Intent =
            Intent(context, DshMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_TAB, tab)
            }
    }
}
