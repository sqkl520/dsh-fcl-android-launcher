package com.dsh.ui.shell

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ItemDshSettingBinding
import com.dsh.fcl.androidlauncher.databinding.ItemDshSettingGroupBinding
import com.dsh.fcl.androidlauncher.databinding.ItemDshSettingIconsBinding
import com.dsh.fcl.androidlauncher.databinding.ItemDshSettingSeekbarBinding
import com.dsh.fcl.androidlauncher.databinding.ItemDshSettingSwitchBinding
import com.tungsten.fcllibrary.component.theme.ThemeEngine

/**
 * 启动器设置页适配器 —— 行类型与结构与 FCL 的 `LauncherSettingAdapter` 对齐：
 * 分组 + **按钮行 / 开关行 / 滑条行 / 多图标行**，行高 48dp，
 * 位置感知圆角（`bg_item_rounded*`），组内紧贴、组间 8dp。
 *
 * 与 FCL 的差异仅两点（可读性所需）：行背景按主题色 tint；组头用不显眼的小节标题。
 */
class DshLauncherSettingAdapter(
    private val context: Context,
    /** 当前启动器名（自定义名或默认值），供「自定义启动器名」行显示 */
    private val launcherName: () -> String,
    private val onAction: (Row.Action) -> Unit,
    private val onSwitch: (Row.Switch, Boolean) -> Unit,
    private val onSeek: (Row.SeekBar, Int) -> Unit,
    private val onIcon: (Row.Icons, Int) -> Unit,
    private val onEdit: (Row.Edit) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_ACTION = 1
        private const val TYPE_SWITCH = 2
        private const val TYPE_SEEKBAR = 3
        private const val TYPE_ICONS = 4
        private const val TYPE_EDIT = 5
    }

    sealed class Row {
        /** 分组标题（小节名） */
        data class Group(val title: Int) : Row()

        /** 按钮行：右侧一个动作按钮 */
        data class Action(
            val title: Int,
            val description: Int,
            val actionText: Int,
            val action: ActionType
        ) : Row()

        /** 开关行：整行为一个 FCLSwitch */
        data class Switch(
            val title: Int,
            val description: Int,
            val checked: () -> Boolean,
            val action: ActionType
        ) : Row()

        /** 滑条行：FCLNumberSeekBar（FCL 原版用于动画速度、颜色透明度等） */
        data class SeekBar(
            val title: Int,
            val description: Int,
            val max: Int,
            val value: () -> Int,
            val suffix: String = "",
            val action: ActionType
        ) : Row()

        /**
         * 编辑行（FCL 的 `Row.EditRow`）：右侧显示当前值，点击弹输入对话框。
         * 复用 [ItemDshSettingValueBinding]（与实例详情页同一个布局）。
         */
        data class Edit(
            val title: Int,
            val description: Int,
            val value: () -> String,
            val action: ActionType
        ) : Row()

        /** 多图标行：最多 3 个图标动作（FCL 的「重置 / 从背景取色 / 设置」） */
        data class Icons(
            val title: Int,
            val description: Int,
            val icons: List<Int>,
            val action: ActionType
        ) : Row()
    }

    enum class ActionType {
        // —— 按钮行 ——
        LANGUAGE,
        THEME_MODE,
        RUNTIME_CHECK,
        OPEN_LOGS,
        OPEN_DOWNLOAD,
        OPEN_INSTANCE,
        EXPORT_LOGS,
        ABOUT,
        // —— 开关行 ——
        FULLSCREEN,
        // —— 滑条行 ——
        ANIMATION_SPEED,
        COLOR_ALPHA,
        // —— 多图标行 ——
        THEME_COLOR,
        THEME_COLOR_DARK,
        THEME_COLOR2,
        THEME_COLOR2_DARK,
        BACKGROUND_LT,
        BACKGROUND_DK,
        // —— 编辑行 ——
        CUSTOM_NAME,
    }

    /** 多图标行的动作编号（0/1/2 = 第几个图标） */
    object IconSlot {
        const val FIRST = 0
        const val SECOND = 1
        const val THIRD = 2
    }

    private var rows: List<Row> = emptyList()

    /** 重新构建列表（设置项集中在这里，便于与 FCL 的设置页逐项对照） */
    fun rebuild() {
        val theme = ThemeEngine.getInstance().getTheme()
        rows = listOf(
            Row.Group(R.string.dsh_setting_group_common),
            Row.Action(
                R.string.dsh_setting_language, R.string.dsh_setting_language_desc,
                R.string.dsh_action_open, ActionType.LANGUAGE
            ),
            Row.Action(
                R.string.dsh_setting_about, R.string.dsh_about_subtitle,
                R.string.dsh_action_open, ActionType.ABOUT
            ),

            Row.Edit(
                R.string.dsh_setting_custom_name, R.string.dsh_setting_custom_name_desc,
                launcherName, ActionType.CUSTOM_NAME
            ),

            Row.Group(R.string.dsh_setting_group_appearance),
            Row.Action(
                R.string.dsh_setting_theme_mode, R.string.dsh_setting_theme_mode_desc,
                R.string.dsh_action_open, ActionType.THEME_MODE
            ),
            // FCL 的「主题色 / 暗色主题色 / 次要色 / 次要暗色」：一行三个图标动作
            Row.Icons(
                R.string.dsh_setting_theme, R.string.dsh_setting_theme_desc,
                listOf(
                    R.drawable.ic_baseline_restore_24,
                    R.drawable.ic_baseline_palette_24,
                    R.drawable.ic_baseline_edit_24
                ),
                ActionType.THEME_COLOR
            ),
            Row.Icons(
                R.string.dsh_setting_theme_dark, R.string.dsh_setting_theme_dark_desc,
                listOf(
                    R.drawable.ic_baseline_restore_24,
                    R.drawable.ic_baseline_palette_24,
                    R.drawable.ic_baseline_edit_24
                ),
                ActionType.THEME_COLOR_DARK
            ),
            Row.Icons(
                R.string.dsh_setting_theme2, R.string.dsh_setting_theme2_desc,
                listOf(
                    R.drawable.ic_baseline_restore_24,
                    R.drawable.ic_baseline_edit_24
                ),
                ActionType.THEME_COLOR2
            ),
            Row.Icons(
                R.string.dsh_setting_theme2_dark, R.string.dsh_setting_theme2_dark_desc,
                listOf(
                    R.drawable.ic_baseline_restore_24,
                    R.drawable.ic_baseline_edit_24
                ),
                ActionType.THEME_COLOR2_DARK
            ),
            // FCL 的「颜色透明度」：0~255 滑条
            Row.SeekBar(
                R.string.dsh_setting_color_alpha, R.string.dsh_setting_color_alpha_desc,
                255, { theme.colorAlpha }, "", ActionType.COLOR_ALPHA
            ),
            Row.Icons(
                R.string.dsh_setting_background_lt, R.string.dsh_setting_background_lt_desc,
                listOf(
                    R.drawable.ic_baseline_restore_24,
                    R.drawable.ic_baseline_edit_24,
                    R.drawable.ic_baseline_palette_24
                ),
                ActionType.BACKGROUND_LT
            ),
            Row.Icons(
                R.string.dsh_setting_background_dk, R.string.dsh_setting_background_dk_desc,
                listOf(
                    R.drawable.ic_baseline_restore_24,
                    R.drawable.ic_baseline_edit_24,
                    R.drawable.ic_baseline_palette_24
                ),
                ActionType.BACKGROUND_DK
            ),
            // FCL 用 0~10 档 SeekBar；这里保持同一字段与范围
            Row.SeekBar(
                R.string.dsh_setting_animation_speed, R.string.dsh_setting_animation_speed_desc,
                10, { theme.animationSpeed }, "", ActionType.ANIMATION_SPEED
            ),
            Row.Switch(
                R.string.dsh_setting_fullscreen, R.string.dsh_setting_fullscreen_desc,
                { ThemeEngine.getInstance().getTheme().fullscreen },
                ActionType.FULLSCREEN
            ),

            Row.Group(R.string.dsh_setting_group_runtime),
            Row.Action(
                R.string.dsh_setting_runtime_check, R.string.dsh_setting_runtime_check_desc,
                R.string.dsh_action_verify_runtime, ActionType.RUNTIME_CHECK
            ),
            Row.Action(
                R.string.dsh_setting_logs, R.string.dsh_setting_logs_desc,
                R.string.dsh_action_open, ActionType.OPEN_LOGS
            ),

            Row.Group(R.string.dsh_setting_group_dsh),
            Row.Action(
                R.string.dsh_setting_download, R.string.dsh_setting_download_desc,
                R.string.dsh_action_open, ActionType.OPEN_DOWNLOAD
            ),
            Row.Action(
                R.string.dsh_setting_instance, R.string.dsh_setting_instance_desc,
                R.string.dsh_action_open, ActionType.OPEN_INSTANCE
            ),
            Row.Action(
                R.string.dsh_setting_export_logs, R.string.dsh_setting_export_logs_desc,
                R.string.dsh_action_copy, ActionType.EXPORT_LOGS
            ),
        )
        notifyDataSetChanged()
    }

    /** 供 [com.mio.ui.adapter.SpacingItemDecoration] 判断相邻行是否同组（同组 → 1dp 细缝 + 分割线） */
    fun isNextInSameGroup(position: Int): Boolean {
        val cur = rows.getOrNull(position) ?: return false
        val next = rows.getOrNull(position + 1) ?: return false
        if (cur is Row.Group) return false
        return next !is Row.Group
    }

    private fun isPrevInSameGroup(position: Int): Boolean {
        val prev = rows.getOrNull(position - 1) ?: return false
        return prev !is Row.Group
    }

    class GroupHolder(val binding: ItemDshSettingGroupBinding) : RecyclerView.ViewHolder(binding.root)
    class ActionHolder(val binding: ItemDshSettingBinding) : RecyclerView.ViewHolder(binding.root)
    class SwitchHolder(val binding: ItemDshSettingSwitchBinding) : RecyclerView.ViewHolder(binding.root)
    class SeekBarHolder(val binding: ItemDshSettingSeekbarBinding) : RecyclerView.ViewHolder(binding.root)
    class IconsHolder(val binding: ItemDshSettingIconsBinding) : RecyclerView.ViewHolder(binding.root)
    class EditHolder(val binding: com.dsh.fcl.androidlauncher.databinding.ItemDshSettingValueBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Group -> TYPE_GROUP
        is Row.Switch -> TYPE_SWITCH
        is Row.SeekBar -> TYPE_SEEKBAR
        is Row.Icons -> TYPE_ICONS
        is Row.Edit -> TYPE_EDIT
        else -> TYPE_ACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GROUP -> GroupHolder(ItemDshSettingGroupBinding.inflate(inflater, parent, false))
            TYPE_SWITCH -> SwitchHolder(ItemDshSettingSwitchBinding.inflate(inflater, parent, false))
            TYPE_SEEKBAR -> SeekBarHolder(ItemDshSettingSeekbarBinding.inflate(inflater, parent, false))
            TYPE_ICONS -> IconsHolder(ItemDshSettingIconsBinding.inflate(inflater, parent, false))
            TYPE_EDIT -> EditHolder(
                com.dsh.fcl.androidlauncher.databinding.ItemDshSettingValueBinding
                    .inflate(inflater, parent, false)
            )
            else -> ActionHolder(ItemDshSettingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Group -> (holder as GroupHolder).binding.groupTitle.setText(row.title)

            is Row.Action -> {
                val b = (holder as ActionHolder).binding
                b.title.setText(row.title)
                b.description.setText(row.description)
                b.action.setText(row.actionText)
                b.action.setOnClickListener { onAction(row) }
                applyRowBackground(holder.itemView, position)
            }

            is Row.Switch -> {
                val b = (holder as SwitchHolder).binding
                b.switchView.setText(row.title)
                b.description.setText(row.description)
                b.switchView.setOnCheckedChangeListener(null)
                b.switchView.isChecked = row.checked()
                b.switchView.setOnCheckedChangeListener { _, checked -> onSwitch(row, checked) }
                applyRowBackground(holder.itemView, position)
            }

            is Row.SeekBar -> {
                val b = (holder as SeekBarHolder).binding
                b.title.setText(row.title)
                b.description.setText(row.description)
                b.seekBar.max = row.max
                b.seekBar.setSuffix(row.suffix)
                b.seekBar.setOnSeekBarChangeListener(null)
                b.seekBar.progress = row.value()
                b.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) onSeek(row, progress)
                    }

                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) = Unit
                })
                applyRowBackground(holder.itemView, position)
            }

            is Row.Edit -> {
                val b = (holder as EditHolder).binding
                b.title.setText(row.title)
                if (row.description != 0) {
                    b.description.visibility = android.view.View.VISIBLE
                    b.description.setText(row.description)
                } else {
                    b.description.visibility = android.view.View.GONE
                }
                b.value.text = row.value()
                b.buttonEdit.visibility = android.view.View.VISIBLE
                val click = android.view.View.OnClickListener { onEdit(row) }
                b.root.setOnClickListener(click)
                b.buttonEdit.setOnClickListener(click)
                applyRowBackground(holder.itemView, position)
            }

            is Row.Icons -> {
                val b = (holder as IconsHolder).binding
                b.title.setText(row.title)
                b.description.setText(row.description)
                val views = listOf(b.icon1, b.icon2, b.icon3)
                views.forEachIndexed { slot, view ->
                    val iconRes = row.icons.getOrNull(slot)
                    if (iconRes == null) {
                        view.visibility = View.GONE
                    } else {
                        view.visibility = View.VISIBLE
                        view.setImageResource(iconRes)
                        view.setOnClickListener { onIcon(row, slot) }
                    }
                }
                applyRowBackground(holder.itemView, position)
            }
        }
    }

    /** 位置感知圆角 + 主题色 tint（与 FCL `LauncherSettingAdapter` 同款） */
    private fun applyRowBackground(view: View, position: Int) {
        val prevSame = isPrevInSameGroup(position)
        val nextSame = isNextInSameGroup(position)
        val bg = when {
            prevSame && nextSame -> R.drawable.bg_item_rounded_middle
            prevSame -> R.drawable.bg_item_rounded_bottom
            nextSame -> R.drawable.bg_item_rounded_top
            else -> R.drawable.bg_item_rounded
        }
        view.setBackgroundResource(bg)
        // 与 FCL 的 LauncherSettingAdapter 一致：行底用「提亮后的主题色」(ltColor)
        view.backgroundTintList = ColorStateList.valueOf(ThemeEngine.getInstance().getTheme().ltColor)
    }
}
