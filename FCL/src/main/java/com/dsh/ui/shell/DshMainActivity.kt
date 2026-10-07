package com.dsh.ui.shell

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
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
 * 本类实现 [DshShellHost]，为页面提供跨 tab 跳转与打开实例详情的能力。
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

        // 菜单顺序必须与 DshUIManager.titles / factories 一致：实例/版本/设置
        // （下标就是 DshShellHost.TAB_* 的值，所以这个列表的顺序=接口顺序，改一处必须三处一起改）
        menus = listOf(
            binding.instances, binding.versions, binding.setting
        )
        menus.forEachIndexed { index, menu ->
            menu.setOnSelectListener { selected ->
                uiManager.switchTo(index)
                menus.forEach { if (it !== selected && it.isSelected) it.setSelected(false) }
            }
        }
        // FCL 的返回项：**复用外壳同一条返回链**（见 [handleBack]）。
        //
        // ★ 为什么不再用 `onBackPressedDispatcher.onBackPressed()`：那条路会**绕开** [onKeyDown]，
        //   于是"系统返回键"和"点左菜单返回"走的是两条不同的入口，等于返回链有两套入口。
        //   两边一旦漂移（比如只在一处加了临时页弹栈），表现就是"按返回键能退、点按钮退不了"
        //   这类极难定位的问题。这里统一成：按钮 → handleBack()，与按键走完全相同的 ②③④⑤ 级。
        //   handleBack() 返回 false = 第 ⑤ 级判定"已在首页、没有可退的" → 这才 finish。
        binding.back.setOnClickListener { if (!handleBack()) finish() }

        uiManager.pageSelectedListener = { pos ->
            // 标题走 uiManager.titleOf：页面自己声明优先（pageTitle()），否则回落到 tab 标题。
            // 所以这里不用再 getString —— 回落已经发生在 titleOf 内部。
            binding.title.setTextWithAnim(uiManager.titleOf(pos).toString())
            if (!menus[pos].isSelected) menus[pos].setSelected(true)
        }

        binding.instances.setSelected(true)
        binding.title.refresh(uiManager.titleOf(0).toString())

        // ⚠️ **刻意不接** `DshMultiPageUI.setOnTempPageTitleChanged`。
        // 临时页打开/关闭时，页面会把它的标题**上报**出来，但外壳**故意不渲染**：
        // 这是 FCL 原味 —— 临时页不覆盖 tab 栏、也不改 Activity 标题。
        // 外壳标题永远按"当前 tab"决定。后人若"顺手补上"这条回调，会让标题随临时页跳动，
        // 偏离 FCL 行为 —— 这是**有意为之**，不是漏接。

        // 从别处跳回外壳时，可以带一个"要落在哪"的意图：
        //   · EXTRA_OPEN_INSTANCE_ID → 直接压出那个实例的详情页（WebView 的「查看日志」用它）
        //   · EXTRA_OPEN_TAB        → 只切到某个 tab（不带实例上下文时用）
        // 两者同时给时**实例优先**：实例详情本身就要先切到「实例」tab，再切别的 tab 会把刚压的栈清掉。
        val pendingInstance = intent?.getStringExtra(EXTRA_OPEN_INSTANCE_ID)
        if (!pendingInstance.isNullOrEmpty()) {
            openInstanceDetailAt(pendingInstance, intent?.getIntExtra(EXTRA_OPEN_INSTANCE_TAB, -1) ?: -1)
        } else {
            intent?.getIntExtra(EXTRA_OPEN_TAB, -1)?.takeIf { it >= 0 }?.let { openTab(it) }
        }

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
        // 与 onCreate 同一套判定：实例优先于 tab（理由见那边）
        val pendingInstance = intent.getStringExtra(EXTRA_OPEN_INSTANCE_ID)
        if (!pendingInstance.isNullOrEmpty()) {
            openInstanceDetailAt(pendingInstance, intent.getIntExtra(EXTRA_OPEN_INSTANCE_TAB, -1))
        } else {
            intent.getIntExtra(EXTRA_OPEN_TAB, -1).takeIf { it >= 0 }?.let { openTab(it) }
        }
    }

    private fun openTab(position: Int) {
        if (position in menus.indices) {
            menus[position].setSelected(true)
        }
    }

    /** 右面板：当前实例卡 + 启动/停止 + 打开界面 */
    private fun setupRightPanel() {
        binding.rightWebui.setOnClickListener { openWebView() }
        binding.rightStart.setOnClickListener {
            val inst = panelInstance ?: return@setOnClickListener
            val running = DshRuntime.runningInstanceId() == inst.id
            if (running) {
                DshRuntime.stop("用户停止")
            } else {
                DshLauncher.startInstance(
                    activity = this,
                    inst = inst,
                    scope = scope,
                    onOpenSettings = { openInstanceDetail(it.id) },
                    // 启动失败的"查看日志"落到该实例的详情页：日志已按实例归属收进详情
                    // （App 级日志才在「设置」里），所以这里不能再去切一个日志 tab —— 它已经不存在了。
                    onOpenLogs = { openInstanceDetail(inst.id) },
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
            binding.rightStart.isEnabled = false
            binding.rightStartText.setText(R.string.dsh_action_start)
            setStartIcon(R.drawable.ic_start)
            binding.rightWebui.isEnabled = false
            return
        }
        val running = state is DshRuntime.State.Running && state.instanceId == inst.id
        binding.rightInstanceName.text = inst.name
        binding.rightInstanceHint.text = describeState(inst, state)
        binding.rightStart.isEnabled = inst.state == DshInstance.State.READY || running
        // 图标 + 文字都随状态切换（FCL 的按钮就是"图标 + 文字"的组合形态）
        binding.rightStartText.setText(if (running) R.string.dsh_action_stop else R.string.dsh_action_start)
        setStartIcon(if (running) R.drawable.ic_baseline_close_24 else R.drawable.ic_start)
        binding.rightWebui.isEnabled = running
    }

    /**
     * 换启动/停止图标。
     *
     * ★ 取色必须走 `ThemeData.getColor2()`（**按当前亮暗模式**取次要色），不能用原始 `.color2`：
     *   原始值不分模式，而 `color2` 的默认值是纯黑（`color2Dark` 默认白）—— 暗色模式下就会把图标
     *   染成黑色，在深色底上等于看不见。控件自己的主题回调（`FCLImageView` 的 `use_theme_color`
     *   分支）用的也是 `getColor2()`，两处取同一个来源，才不会出现"运行期换图标"与"主题刷新"
     *   给出两种颜色。
     *
     * ★ 为什么还要在这里手动补一次着色：`FCLImageView` 的上色只发生在**主题刷新时**，
     *   运行期换背景图不会自动带上主题色 —— 新图标会保持 vector 自带的颜色。
     *   换完立刻补一次；之后主题切换时控件自己的回调会继续维持。
     */
    private fun setStartIcon(res: Int) {
        binding.rightStartIcon.setBackgroundResource(res)
        binding.rightStartIcon.background?.setTint(ThemeEngine.getInstance().getTheme().getColor2())
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

    override fun openInstanceDetail(instanceId: String) {
        // 两步，顺序不能反：
        // 1. 先切到「实例」tab —— 临时页是**某个 tab 的上下文**（切 tab 会清栈，见
        //    DshMultiPageUI.onPageSelected）。不先切过去就压栈，用户会停在别的 tab 上，
        //    压进去的详情页根本不可见，看起来就像"点了没反应"。
        // 2. 再拿那一页压栈。这里必须用 ensurePage 而不是 uiAt：页面可能还没被 ViewPager 创建过
        //    （比如冷启动后直接点右面板的"去配置"），而这次调用**就是要立刻操作它**，
        //    创建它是预期副作用 —— 见 DshUIManager.ensurePage 的注释。
        switchTab(DshShellHost.TAB_INSTANCES)
        (uiManager.ensurePage(DshShellHost.TAB_INSTANCES) as? DshInstancesUI)
            ?.showInstanceDetail(instanceId)
    }

    /**
     * 打开某实例详情并**落在指定段落**。
     *
     * 与 [openInstanceDetail] 的唯一区别是带上了"要看哪一段"的意图 —— 从别处跳进来的场景
     * （WebView 的「查看日志」）用户想看的是日志，不是默认那一段。
     *
     * `tab < 0` 时退化为 [openInstanceDetail]：调用方不知道段落就不该猜。
     */
    private fun openInstanceDetailAt(instanceId: String, tab: Int) {
        if (tab < 0) {
            openInstanceDetail(instanceId)
            return
        }
        switchTab(DshShellHost.TAB_INSTANCES)
        (uiManager.ensurePage(DshShellHost.TAB_INSTANCES) as? DshInstancesUI)
            ?.showInstanceDetailAt(instanceId, tab)
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

    /**
     * 返回链第 **①** 级：Activity 的返回入口。
     *
     * ★ 为什么是 `onKeyDown` 而不是覆写 `onBackPressed`：
     * `onBackPressed` 在 API 33+ 由 `onBackPressedDispatcher` 接管（`ComponentActivity` 的实现就是
     * 直接转发给它），覆写它会让"系统返回"和"我们自己调的返回"落到两条不同的路径上。
     * FCL 用的是 `onKeyDown`，我们照搬：**按键先到 `onKeyDown`**，只有我们没消费时才落到
     * `super` → 框架的 `onBackPressed()` → dispatcher，链路始终只有一条。
     *
     * 能这么做的前提（已核对，不是想当然）：本 App 的 manifest **没有**开
     * `android:enableOnBackInvokedCallback`，而 targetSdk 是 34（该属性到 targetSdk 35 才默认 true），
     * 所以系统**把手势返回也当成 KEYCODE_BACK 键事件**送进来，硬件键与手势都进得了这个方法。
     * 哪天要开预测式返回（或把 targetSdk 提到 35），就必须改走 `onBackInvokedDispatcher`
     * 并把这条链挂上去，否则手势返回会绕过整个返回链。
     *
     * ## 为什么"消费了就不调 `super`"是必须的（不是随手写的）
     * `Activity.onKeyDown` 对 BACK 会调 `event.startTracking()`，作用是**登记"这个键我要看抬起"**；
     * 被登记过的键，它的**抬起**会让 `Activity.onKeyUp` 再触发一次 `onBackPressed()`
     * （`onKeyUp` 只对 `isTracking()` 的抬起放行）。而登记的前提是这次按下**走到了 `super`**。
     *
     * 由此得出两条实现约束：
     * 1. **消费了就 `return true` 且不调 `super`** —— 没走 super 就不会被登记，
     *    抬起时不会再触发一次返回，**一次按键只消费一次**。
     * 2. **`repeatCount == 0` 这道闸门同时管住了长按** —— 长按产生的重复按下事件
     *    （`repeatCount > 0`）我们不处理、落到 `super`，而框架登记"跟踪"时会检查
     *    "这是首次按下（`repeatCount == 0`）**且** 按键处理方置了跟踪标记"，重复事件两条都不满足，
     *    所以**登记不会补上**，松手时不会多出一次 `onBackPressed()`。
     *    （否则表现会是"在非首页长按返回：先回首页，松手时整个外壳被退掉"。）
     *
     * 换句话说：`repeatCount == 0` 既是"长按不重复触发"，也是"抬起不重复消费"的同一道闸门 ——
     * 这也是为什么这里不需要额外判断 `repeatCount > 0` 后再单独吃掉事件。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.repeatCount == 0) {
            if (handleBack()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 返回链的第 **②③④⑤** 级（第 ① 级是上面的 [onKeyDown]，两者共用这一份实现，
     * 所以左菜单的返回按钮也走这里 —— 一个入口，不会漂移）。
     *
     * @return true = 已消费，调用方不要继续；false = 没得退了（已在首页），
     *         由调用方决定退出方式（按键 → `super.onKeyDown` 交给系统；按钮 → `finish()`）
     */
    private fun handleBack(): Boolean {
        // ②③④：交给当前页 —— 页内临时页栈 → 子类自定义回退。
        // 不显示的页、或不是 DshPageUI 的页不会消费（守卫在 DshUIManager.onBackPressed 里）。
        if (uiManager.onBackPressed()) return true

        // ⑤a：全局兜底 —— 不在首页就回首页（FCL 原味：外壳永远有一个可退回的"根 tab"）
        if (uiManager.currentPosition != DshShellHost.TAB_INSTANCES) {
            switchTab(DshShellHost.TAB_INSTANCES)
            return true
        }

        // ⑤b：已在首页，没有可退的 —— 不消费，交给调用方
        return false
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

        /**
         * 请求外壳**直接压出某个实例的详情页**（实例详情的日志/配置都在这一个入口下）。
         * 与 [EXTRA_OPEN_TAB] 同时给出时以本项为准 —— 理由见 `onCreate`。
         */
        const val EXTRA_OPEN_INSTANCE_ID = "dsh_open_instance_id"

        /** 构造"回到外壳并切到某 tab"的 Intent（singleTop，复用已有实例） */
        fun intentForTab(context: Context, tab: Int): Intent =
            Intent(context, DshMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_TAB, tab)
            }

        /**
         * 构造"回到外壳并打开某实例详情"的 Intent。
         *
         * 为什么需要它（而不是继续用 `intentForTab(TAB_LOGS)`）：日志已按实例归属收进实例详情页，
         * 外壳**不再有**"日志"这个 tab。想从 WebView 跳到"这个实例的日志"，只能压出它的详情页
         * 并落在「日志」那一段。
         *
         * @param tab 详情页内的段落（[DshInstanceDetailPage] 的 `TAB_*` 常量）；-1 = 默认（运行）
         */
        fun intentForInstance(context: Context, instanceId: String, tab: Int = -1): Intent =
            Intent(context, DshMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_INSTANCE_ID, instanceId)
                if (tab >= 0) putExtra(EXTRA_OPEN_INSTANCE_TAB, tab)
            }

        /** 与 [EXTRA_OPEN_INSTANCE_ID] 搭配：详情页内落在哪一段 */
        const val EXTRA_OPEN_INSTANCE_TAB = "dsh_open_instance_tab"
    }
}
