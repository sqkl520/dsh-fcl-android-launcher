package com.dsh.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
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
import com.tungsten.fcllibrary.component.dialog.FCLColorPickerDialog
import com.tungsten.fcllibrary.component.theme.ThemeData
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.util.LocaleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「启动器设置」子页 —— 结构与 FCL 的 `LauncherSettingPage` 一致：
 * RecyclerView + [SpacingItemDecoration]（组内 1dp 细缝并绘制分割线、组间 8dp 间距），
 * 行间用位置感知圆角拼成"块"；主题切换时重绘分割线。
 *
 * 设置项与 FCL 的对应关系：
 * | FCL 设置 | 本项目 |
 * |---|---|
 * | 语言 | [LocaleUtils.changeLanguage] + 重建界面（与 FCL 同款） |
 * | 主题模式（跟随/亮/暗） | `launcher.themeMode` + `AppCompatDelegate` + ThemeEngine 刷新 |
 * | 主题色 / 背景图 | 背景图选择器待接入（见任务清单）；主题色随主题包 |
 * | 动画速度 | `ThemeEngine.setAnimationSpeed` + 持久化 |
 * | 忽略刘海（全屏） | `ThemeEngine.applyAndSave(context, window, checked)` |
 * | 日志导出 | 复制 dsh 日志到剪贴板 |
 * 实例级配置（API Key / 模型 / profile / 端口）由 `DshSettingsActivity` 独立承载。
 */
class DshLauncherSettingsPage(
    context: Context,
    private val host: DshShellHost,
    /** 「关于」不再是弹窗，而是切到设置页内的「关于」子页（见 DshSettingsUI） */
    private val onOpenAbout: () -> Unit = {}
) : DshPageUI(context, R.layout.ui_dsh_launcher_settings) {

    private val binding = UiDshLauncherSettingsBinding.bind(contentView)
    private lateinit var adapter: DshLauncherSettingAdapter

    /** FCL 的设置页用同一个 SharedPreferences 存界面选项 */
    private val prefs get() = context.getSharedPreferences("launcher", MODE_PRIVATE)

    /** 主题变化时重绘分割线（FCL 同款） */
    private val themeInvalidate = Runnable { binding.settingList.invalidate() }

    override fun onCreate() {
        super.onCreate()
        DshPaths.loadPaths(context)
        DshInstances.init()

        adapter = DshLauncherSettingAdapter(context, ::onAction, ::onSwitch, ::onSeek, ::onIcon)
        binding.settingList.layoutManager = LinearLayoutManager(context)

        // FCL 的分组间距：组间 8dp；同组相邻行只留 1dp 细缝并绘制主题色分割线
        val rowSpacing = dp(8)
        binding.settingList.addItemDecoration(
            SpacingItemDecoration(
                rowSpacing,
                { parent, position ->
                    val a = parent.adapter as? DshLauncherSettingAdapter
                    if (a?.isNextInSameGroup(position) == true) dp(1) else rowSpacing
                },
                true,
                { ThemeEngine.getInstance().getTheme().color }
            )
        )
        ThemeEngine.getInstance().registerEvent(binding.settingList, themeInvalidate)
        binding.settingList.adapter = adapter
        adapter.rebuild()
    }

    override fun onDestroy() {
        ThemeEngine.getInstance().unregisterEvent(binding.settingList)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    // ===== 交互 =====

    private fun onAction(row: DshLauncherSettingAdapter.Row.Action) {
        when (row.action) {
            DshLauncherSettingAdapter.ActionType.LANGUAGE -> pickLanguage()
            DshLauncherSettingAdapter.ActionType.THEME_MODE -> pickThemeMode()
            DshLauncherSettingAdapter.ActionType.BACKGROUND -> Toast.makeText(
                context, R.string.dsh_setting_background_pending, Toast.LENGTH_SHORT
            ).show()
            DshLauncherSettingAdapter.ActionType.THEME_COLOR ->
                pickColor(_getColor(), { ThemeEngine.getInstance().applyColor(it) }, { applyAndSaveColor(it) })
            DshLauncherSettingAdapter.ActionType.THEME_COLOR_DARK ->
                pickColor(_getColorDark(), { ThemeEngine.getInstance().applyColorDark(it) }, { applyAndSaveColorDark(it) })
            DshLauncherSettingAdapter.ActionType.THEME_COLOR2 ->
                pickColor(_getColor2(), { ThemeEngine.getInstance().applyColor2(it) }, { applyAndSaveColor2(it) })
            DshLauncherSettingAdapter.ActionType.THEME_COLOR2_DARK ->
                pickColor(_getColor2Dark(), { ThemeEngine.getInstance().applyColor2Dark(it) }, { applyAndSaveColor2Dark(it) })
            DshLauncherSettingAdapter.ActionType.RUNTIME_CHECK -> verifyRuntime()
            DshLauncherSettingAdapter.ActionType.OPEN_LOGS -> host.switchTab(DshShellHost.TAB_LOGS)
            DshLauncherSettingAdapter.ActionType.OPEN_DOWNLOAD -> host.switchTab(DshShellHost.TAB_DOWNLOAD)
            DshLauncherSettingAdapter.ActionType.OPEN_INSTANCE -> host.switchTab(DshShellHost.TAB_INSTANCES)
            DshLauncherSettingAdapter.ActionType.ABOUT -> showAbout()
            DshLauncherSettingAdapter.ActionType.EXPORT_LOGS -> exportLogs()
            else -> Unit
        }
    }
    private fun onSwitch(row: DshLauncherSettingAdapter.Row.Switch, checked: Boolean) {
        when (row.action) {
            // FCL：SWITCH_IGNORE_NOTCH → applyAndSave + 重新应用 FLAG_LAYOUT_IN_SCREEN
            DshLauncherSettingAdapter.ActionType.FULLSCREEN -> {
                ThemeEngine.getInstance().applyAndSave(context, host.activity.window, checked)
                ThemeEngine.getInstance().refreshTheme()
            }
            else -> Unit
        }
    }

    /** 滑条行：动画速度（0~10，FCL 同字段）/ 颜色透明度（0~255） */
    private fun onSeek(row: DshLauncherSettingAdapter.Row.SeekBar, progress: Int) {
        when (row.action) {
            DshLauncherSettingAdapter.ActionType.ANIMATION_SPEED -> {
                ThemeEngine.getInstance().setAnimationSpeed(progress)
                ThemeData.saveTheme(context, ThemeEngine.getInstance().getTheme())
            }
            DshLauncherSettingAdapter.ActionType.COLOR_ALPHA -> {
                ThemeEngine.getInstance().applyColorAlpha(progress)
                ThemeData.saveTheme(context, ThemeEngine.getInstance().getTheme())
                ThemeEngine.getInstance().refreshTheme()
            }
            else -> Unit
        }
    }

    /**
     * 多图标行（主题色 / 背景图）的动作。
     *
     * FCL 的图标次序是「重置 / 从背景取色 / 设置」；本项目**从背景取色**留待 G4（图片选择）
     * 之后实现，先按「重置 / 设置」两项（`Row.Icons.icons` 也只给两个图标）。
     */
    private fun onIcon(row: DshLauncherSettingAdapter.Row.Icons, slot: Int) {
        val isReset = slot == DshLauncherSettingAdapter.IconSlot.FIRST
        when (row.action) {
            DshLauncherSettingAdapter.ActionType.THEME_COLOR ->
                if (isReset) resetColor() else pickColor(
                    _getColor(),
                    { ThemeEngine.getInstance().applyColor(it) },
                    { applyAndSaveColor(it) }
                )
            DshLauncherSettingAdapter.ActionType.THEME_COLOR_DARK ->
                if (isReset) resetColorDark() else pickColor(
                    _getColorDark(),
                    { ThemeEngine.getInstance().applyColorDark(it) },
                    { applyAndSaveColorDark(it) }
                )
            DshLauncherSettingAdapter.ActionType.THEME_COLOR2 ->
                if (isReset) resetColor2() else pickColor(
                    _getColor2(),
                    { ThemeEngine.getInstance().applyColor2(it) },
                    { applyAndSaveColor2(it) }
                )
            DshLauncherSettingAdapter.ActionType.THEME_COLOR2_DARK ->
                if (isReset) resetColor2Dark() else pickColor(
                    _getColor2Dark(),
                    { ThemeEngine.getInstance().applyColor2Dark(it) },
                    { applyAndSaveColor2Dark(it) }
                )
            DshLauncherSettingAdapter.ActionType.BACKGROUND_LT,
            DshLauncherSettingAdapter.ActionType.BACKGROUND_DK -> Toast.makeText(
                context, R.string.dsh_setting_background_pending, Toast.LENGTH_SHORT
            ).show()
            else -> Unit
        }
    }

    // ===== 主题色（行为与 FCL 的 showColorPicker 一致：拖动实时预览，确定才落盘） =====

    private fun pickColor(initColor: Int, apply: (Int) -> Unit, applyAndSave: (Int) -> Unit) {
        var latest = initColor
        FCLColorPickerDialog(context, initColor, object : FCLColorPickerDialog.Listener {
            override fun onColorChanged(color: Int) {
                latest = color
                apply(color)
            }

            override fun onPositive(destColor: Int) {
                applyAndSave(destColor)
                refreshRows()
            }

            override fun onNegative(initColor: Int) {
                apply(initColor)
                refreshRows()
            }
        }).show()
        // 记录一次，便于后续（当前无需额外处理）
        @Suppress("UNUSED_EXPRESSION") latest
    }

    private fun _getColor(): Int = ThemeEngine.getInstance().getTheme().color
    private fun _getColorDark(): Int = ThemeEngine.getInstance().getTheme().colorDark
    private fun _getColor2(): Int = ThemeEngine.getInstance().getTheme().color2
    private fun _getColor2Dark(): Int = ThemeEngine.getInstance().getTheme().color2Dark

    private fun applyAndSaveColor(color: Int) = ThemeEngine.getInstance().applyAndSave(context, color)
    private fun applyAndSaveColorDark(color: Int) = ThemeEngine.getInstance().applyAndSaveDark(context, color)
    private fun applyAndSaveColor2(color: Int) = ThemeEngine.getInstance().applyAndSave2(context, color)
    private fun applyAndSaveColor2Dark(color: Int) = ThemeEngine.getInstance().applyAndSave2Dark(context, color)

    private fun resetColor() = ThemeEngine.getInstance()
        .applyAndSave(context, context.getColor(R.color.default_theme_color))

    private fun resetColorDark() = ThemeEngine.getInstance()
        .applyAndSaveDark(context, context.getColor(R.color.default_theme_color_dark))

    private fun resetColor2() = ThemeEngine.getInstance()
        .applyAndSave2(context, android.graphics.Color.parseColor("#000000"))

    private fun resetColor2Dark() = ThemeEngine.getInstance()
        .applyAndSave2Dark(context, android.graphics.Color.parseColor("#FFFFFF"))

    /** 值变了之后让行重新绑定（滑条/开关的显示值来自 ThemeData） */
    private fun refreshRows() {
        ThemeEngine.getInstance().refreshTheme()
        binding.settingList.invalidate()
        adapter.notifyDataSetChanged()
    }


    /** 语言：与 FCL 相同 —— 写入偏好后重建界面，让 attachBaseContext 重新应用 */
    private fun pickLanguage() {
        val labels = listOf(
            context.getString(R.string.dsh_lang_system),
            context.getString(R.string.dsh_lang_zh_cn),
            context.getString(R.string.dsh_lang_en),
        )
        val current = LocaleUtils.getLanguage(context)
        DshOptionDialog(context, context.getString(R.string.dsh_setting_language), labels, current) { pos ->
            if (pos == current) return@DshOptionDialog
            LocaleUtils.changeLanguage(context, pos)
            host.activity.recreate()
        }.show()
    }

    /** 主题模式：与 FCL 的 SPINNER_THEME_MODE 完全一致（pref + AppCompatDelegate + 刷新主题） */
    private fun pickThemeMode() {
        val labels = listOf(
            context.getString(R.string.dsh_theme_mode_follow),
            context.getString(R.string.dsh_theme_mode_light),
            context.getString(R.string.dsh_theme_mode_dark),
        )
        val current = prefs.getInt("themeMode", 0)
        DshOptionDialog(context, context.getString(R.string.dsh_setting_theme_mode), labels, current) { pos ->
            if (pos == current) return@DshOptionDialog
            prefs.edit().putInt("themeMode", pos).apply()
            AppCompatDelegate.setDefaultNightMode(
                when (pos) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
            // configChanges 含 uiMode 时不会重建 Activity，需显式刷新主题控件与背景
            ThemeEngine.getInstance().refreshTheme()
            refreshRows()
        }.show()
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

    /** 关于：切到设置页内的「关于」子页（FCL 的关于页是设置页的子页） */
    private fun showAbout() {
        onOpenAbout()
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
