package com.dsh.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import android.net.Uri
import com.tungsten.fclauncher.utils.FCLPath
import java.io.File
import com.dsh.core.DshBootstrap
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.fcl.androidlauncher.BuildConfig
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.UiDshLauncherSettingsBinding
import com.mio.ui.adapter.SpacingItemDecoration
import com.mio.util.getLauncherName
import com.tungsten.fcllibrary.component.dialog.EditDialog
import androidx.core.content.edit
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.mio.dialog.ItemSelectionDialog
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
 *
 * 本页**只放设置项**，不放导航项：原先「运行日志 / dsh 版本管理 / 实例管理」三行只是
 * 跳去外壳的其他 tab，等于页内第二份导航菜单，已删除 —— 日志改由设置页内的「日志」子页承载，
 * 版本与实例本来就有主入口。
 *
 * 实例级配置（API Key / 模型 / profile / 端口）在**实例页的实例详情**里，不再是独立页面。
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

        adapter = DshLauncherSettingAdapter(
            context, { getLauncherName(context) }, ::onAction, ::onSwitch, ::onSeek, ::onIcon, ::onEdit
        )
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
            DshLauncherSettingAdapter.ActionType.THEME_COLOR ->
                pickColor(_getColor(), { ThemeEngine.getInstance().applyColor(it) }, { applyAndSaveColor(it) })
            DshLauncherSettingAdapter.ActionType.THEME_COLOR_DARK ->
                pickColor(_getColorDark(), { ThemeEngine.getInstance().applyColorDark(it) }, { applyAndSaveColorDark(it) })
            DshLauncherSettingAdapter.ActionType.THEME_COLOR2 ->
                pickColor(_getColor2(), { ThemeEngine.getInstance().applyColor2(it) }, { applyAndSaveColor2(it) })
            DshLauncherSettingAdapter.ActionType.THEME_COLOR2_DARK ->
                pickColor(_getColor2Dark(), { ThemeEngine.getInstance().applyColor2Dark(it) }, { applyAndSaveColor2Dark(it) })
            DshLauncherSettingAdapter.ActionType.RUNTIME_CHECK -> verifyRuntime()
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

    /** 编辑行：目前只有「自定义启动器名」（FCL 启动器设置里的 custom_launcher_name） */
    private fun onEdit(row: DshLauncherSettingAdapter.Row.Edit) {
        when (row.action) {
            DshLauncherSettingAdapter.ActionType.CUSTOM_NAME -> {
                EditDialog(context, getLauncherName(context)) { text ->
                    prefs.edit { putString("custom_launcher_name", text) }
                    refreshRows()
                }.apply { setTitle(context.getString(R.string.dsh_setting_custom_name)) }.show()
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
            // FCL 的主题行是三个图标：重置 / 从背景取色 / 设置
            DshLauncherSettingAdapter.ActionType.THEME_COLOR -> when (slot) {
                DshLauncherSettingAdapter.IconSlot.FIRST -> resetColor()
                DshLauncherSettingAdapter.IconSlot.SECOND ->
                    fetchColorFromBackground(false) { applyAndSaveColor(it) }
                else -> pickColor(
                    _getColor(),
                    { ThemeEngine.getInstance().applyColor(it) },
                    { applyAndSaveColor(it) }
                )
            }
            DshLauncherSettingAdapter.ActionType.THEME_COLOR_DARK -> when (slot) {
                DshLauncherSettingAdapter.IconSlot.FIRST -> resetColorDark()
                DshLauncherSettingAdapter.IconSlot.SECOND ->
                    fetchColorFromBackground(true) { applyAndSaveColorDark(it) }
                else -> pickColor(
                    _getColorDark(),
                    { ThemeEngine.getInstance().applyColorDark(it) },
                    { applyAndSaveColorDark(it) }
                )
            }
            DshLauncherSettingAdapter.ActionType.THEME_COLOR2 -> when (slot) {
                DshLauncherSettingAdapter.IconSlot.FIRST -> resetColor2()
                else -> pickColor(
                    _getColor2(),
                    { ThemeEngine.getInstance().applyColor2(it) },
                    { applyAndSaveColor2(it) }
                )
            }
            DshLauncherSettingAdapter.ActionType.THEME_COLOR2_DARK -> when (slot) {
                DshLauncherSettingAdapter.IconSlot.FIRST -> resetColor2Dark()
                else -> pickColor(
                    _getColor2Dark(),
                    { ThemeEngine.getInstance().applyColor2Dark(it) },
                    { applyAndSaveColor2Dark(it) }
                )
            }
            DshLauncherSettingAdapter.ActionType.BACKGROUND_LT -> when (slot) {
                DshLauncherSettingAdapter.IconSlot.FIRST -> resetBackground(false)
                DshLauncherSettingAdapter.IconSlot.SECOND -> pickBackground(false)
                else -> fetchColorFromBackground(false) { applyAndSaveColor(it) }
            }
            DshLauncherSettingAdapter.ActionType.BACKGROUND_DK -> when (slot) {
                DshLauncherSettingAdapter.IconSlot.FIRST -> resetBackground(true)
                DshLauncherSettingAdapter.IconSlot.SECOND -> pickBackground(true)
                else -> fetchColorFromBackground(true) { applyAndSaveColorDark(it) }
            }
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

    // ===== 背景图（G4：走系统选择器，不恢复 FCL 的 FileBrowser 整包） =====

    private fun backgroundFile(dark: Boolean): File =
        File(if (dark) FCLPath.DK_BACKGROUND_PATH else FCLPath.LT_BACKGROUND_PATH)

    /** 选择背景图：挑图 → 复制到 cache 临时文件 → 交给 ThemeEngine 应用并落盘 */
    private fun pickBackground(dark: Boolean) {
        host.pickImage { uri ->
            if (uri == null) return@pickImage
            scope.launch {
                val tmp = withContext(Dispatchers.IO) { copyToCache(uri) }
                if (tmp == null) {
                    Toast.makeText(context, R.string.dsh_background_pick_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // 亮色传 lt、暗色传 dk；另一个传 null 表示不动
                if (dark) {
                    ThemeEngine.getInstance().applyAndSave(context, null, tmp.absolutePath)
                } else {
                    ThemeEngine.getInstance().applyAndSave(context, tmp.absolutePath, null)
                }
                ThemeEngine.getInstance().refreshTheme()
                refreshRows()
                Toast.makeText(context, R.string.dsh_background_applied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 把选中的图片读到 cache（避免长期持有 content Uri 权限） */
    private fun copyToCache(uri: Uri): File? = runCatching {
        val out = File(context.cacheDir, "dsh-bg-${System.currentTimeMillis()}.img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return@runCatching null
        out
    }.getOrNull()

    /** 重置背景为内置默认图（删除自定义图后让 ThemeEngine 重新加载） */
    private fun resetBackground(dark: Boolean) {
        runCatching { backgroundFile(dark).delete() }
        ThemeEngine.getInstance().applyAndSave(context, null, null)
        ThemeEngine.getInstance().refreshTheme()
        refreshRows()
    }

    /**
     * 从当前背景图提取主色并应用（对应 FCL 主题行里的「从背景取色」图标）。
     * 用缩略图取平均色实现 —— 不引入 androidx.palette（FCL 用了，但我们只需要一个代表色）。
     */
    private fun fetchColorFromBackground(dark: Boolean, applyAndSave: (Int) -> Unit) {
        scope.launch {
            val color = withContext(Dispatchers.IO) { averageColorOf(backgroundFile(dark)) }
            if (color == null) {
                Toast.makeText(context, R.string.dsh_background_no_image, Toast.LENGTH_SHORT).show()
                return@launch
            }
            applyAndSave(color)
            ThemeEngine.getInstance().refreshTheme()
            refreshRows()
        }
    }

    private fun averageColorOf(file: File): Int? = runCatching {
        if (!file.isFile) return@runCatching null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 64 || bounds.outHeight / sample > 64) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(
            file.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return@runCatching null
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                x += 2
            }
            y += 2
        }
        bmp.recycle()
        if (n == 0L) null else android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }.getOrNull()

    /** 值变了之后让行重新绑定（滑条/开关的显示值来自 ThemeData） */
    private fun refreshRows() {
        ThemeEngine.getInstance().refreshTheme()
        binding.settingList.invalidate()
        adapter.notifyDataSetChanged()
    }


    /** 语言：与 FCL 相同 —— 写入偏好后重建界面，让 attachBaseContext 重新应用 */
    private fun pickLanguage() {
        // 顺序必须与 LocaleUtils.getLocale(index) 的 0..11 一一对应（照 FCL）
        val labels = listOf(
            context.getString(R.string.dsh_lang_system),                    // 0 跟随系统
            context.getString(R.string.dsh_lang_english),                   // 1
            context.getString(R.string.dsh_lang_simplified_chinese),        // 2
            context.getString(R.string.dsh_lang_russian),                   // 3
            context.getString(R.string.dsh_lang_brazilian_portuguese),      // 4
            context.getString(R.string.dsh_lang_persian),                   // 5
            context.getString(R.string.dsh_lang_ukrainian),                 // 6
            context.getString(R.string.dsh_lang_german),                    // 7
            context.getString(R.string.dsh_lang_traditional_chinese_hk),    // 8
            context.getString(R.string.dsh_lang_japanese),                  // 9
            context.getString(R.string.dsh_lang_turkish),                   // 10
            context.getString(R.string.dsh_lang_traditional_chinese_tw),    // 11
        )
        val current = LocaleUtils.getLanguage(context)
        // FCL 的选项对话框（条目高亮主题色、条目多时自动滚动）
        ItemSelectionDialog(
            context,
            context.getString(R.string.dsh_setting_language),
            labels,
            true,
            current
        ) { pos, _ ->
            if (pos == current) return@ItemSelectionDialog
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
        ItemSelectionDialog(
            context,
            context.getString(R.string.dsh_setting_theme_mode),
            labels,
            true,
            current
        ) { pos, _ ->
            if (pos == current) return@ItemSelectionDialog
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
