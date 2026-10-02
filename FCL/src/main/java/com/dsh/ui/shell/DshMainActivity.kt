package com.dsh.ui.shell

import android.content.Intent
import android.os.Bundle
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
import com.dsh.ui.DshSettingsActivity
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

    override val activity: FCLActivity get() = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDshMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        uiManager.pageSelectedListener = { pos ->
            binding.title.setTextWithAnim(getString(uiManager.titles[pos]))
            if (!menus[pos].isSelected) menus[pos].setSelected(true)
        }

        binding.instances.setSelected(true)
        binding.title.refresh(getString(uiManager.titles[0]))

        setupRightPanel()
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
                    onPrepareRuntime = {
                        DshLauncher.prepareRuntime(this, scope) { }
                    },
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

    override fun onBackPressed() {
        if (binding.uiLayout.currentItem != DshShellHost.TAB_INSTANCES) {
            binding.instances.setSelected(true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
