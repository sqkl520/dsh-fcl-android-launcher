package com.dsh.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.core.DshBootstrap
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.fcl.androidlauncher.BuildConfig
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.UiDshLauncherSettingsBinding
import com.mio.ui.adapter.SpacingItemDecoration
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局启动器设置页：按 FCL LauncherSettingPage 的 RecyclerView 分组行组织。
 * 实例级配置（API Key / 模型 / profile / 端口）仍由 DshSettingsActivity 独立承载。
 */
class DshSettingsUI(
    context: Context,
    private val host: DshShellHost
) : DshPageUI(context, R.layout.ui_dsh_launcher_settings) {

    private val binding = UiDshLauncherSettingsBinding.bind(contentView)
    private lateinit var adapter: DshLauncherSettingAdapter

    override fun onCreate() {
        super.onCreate()
        DshPaths.loadPaths(context)
        DshInstances.init()
        adapter = DshLauncherSettingAdapter(::onAction)
        binding.settingList.layoutManager = LinearLayoutManager(context)
        // 行间距与组内分割线：照搬 FCL LauncherSettingPage 的 SpacingItemDecoration 用法 ——
        // 同组相邻行之间 1dp 分割线（用主题色绘制），跨组 8dp 间距，首行上方 10dp。
        val density = context.resources.displayMetrics.density
        val rowSpacing = (8 * density).toInt()
        val groupDivider = (1 * density).toInt()
        binding.settingList.addItemDecoration(
            SpacingItemDecoration(
                rowSpacing,
                { _, position -> if (adapter.isNextInSameGroup(position)) groupDivider else rowSpacing },
                true,
                { ThemeEngine.getInstance().getTheme().getColor() }
            )
        )
        // 主题切换时重绘分割线颜色（FCL 同款）
        ThemeEngine.getInstance().registerEvent(binding.settingList) {
            binding.settingList.invalidate()
        }
        binding.settingList.adapter = adapter
        adapter.rebuild()
    }

    private fun onAction(row: DshLauncherSettingAdapter.Row.Action) {
        when (row.action) {
            DshLauncherSettingAdapter.ActionType.THEME_MODE ->
                Toast.makeText(context, R.string.dsh_setting_theme_coming, Toast.LENGTH_SHORT).show()
            DshLauncherSettingAdapter.ActionType.RUNTIME_CHECK -> verifyRuntime()
            DshLauncherSettingAdapter.ActionType.OPEN_LOGS -> host.switchTab(DshShellHost.TAB_LOGS)
            DshLauncherSettingAdapter.ActionType.OPEN_DOWNLOAD -> host.switchTab(DshShellHost.TAB_DOWNLOAD)
            DshLauncherSettingAdapter.ActionType.OPEN_INSTANCE -> host.switchTab(DshShellHost.TAB_INSTANCES)
            DshLauncherSettingAdapter.ActionType.ABOUT -> showAbout()
            DshLauncherSettingAdapter.ActionType.EXPORT_LOGS -> exportLogs()
        }
    }

    private fun verifyRuntime() {
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

    private fun showAbout() {
        FCLAlertDialog.Builder(host.activity)
            .setAlertLevel(FCLAlertDialog.AlertLevel.INFO)
            .setTitle(context.getString(R.string.dsh_about_full_name))
            .setMessage(
                context.getString(R.string.dsh_about_subtitle) + "\n" +
                    context.getString(R.string.dsh_about_version, BuildConfig.VERSION_NAME) + "\n" +
                    "https://github.com/sqkl520/dsh-fcl-android-launcher"
            )
            .useAutoLink()
            .setNegativeButton(context.getString(R.string.dialog_positive), null)
            .create()
            .show()
    }

    private fun exportLogs() {
        val text = DshLogBus.export()
        if (text.isEmpty()) {
            Toast.makeText(context, R.string.dsh_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("dsh log", text))
        Toast.makeText(context, R.string.dsh_logs_copied, Toast.LENGTH_SHORT).show()
    }
}
