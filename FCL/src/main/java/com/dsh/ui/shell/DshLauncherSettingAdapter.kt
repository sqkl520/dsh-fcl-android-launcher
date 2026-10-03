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
import com.dsh.fcl.androidlauncher.databinding.ItemDshSettingSwitchBinding
import com.tungsten.fcllibrary.component.theme.ThemeEngine

/**
 * 启动器设置页适配器 —— 结构与 FCL 的 `LauncherSettingAdapter` 一致：
 * 扁平列表 + 分组（组内行紧贴成一块、组间 8dp 间距）、行高 48dp、
 * 位置感知圆角（top / middle / bottom，见 `bg_item_rounded*`）、
 * 行类型按设置项分派（按钮行 / 开关行）。
 *
 * 与 FCL 的差异仅为可读性所需：行背景按主题色 tint（FCL 原版为纯白卡片）。
 */
class DshLauncherSettingAdapter(
    private val context: Context,
    private val onAction: (Row.Action) -> Unit,
    private val onSwitch: (Row.Switch, Boolean) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_ACTION = 1
        private const val TYPE_SWITCH = 2
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
    }

    enum class ActionType {
        LANGUAGE,
        THEME_MODE,
        BACKGROUND,
        ANIMATION_SPEED,
        FULLSCREEN,
        RUNTIME_CHECK,
        OPEN_LOGS,
        OPEN_DOWNLOAD,
        OPEN_INSTANCE,
        EXPORT_LOGS,
        ABOUT
    }

    private var rows: List<Row> = emptyList()

    /** 重新构建列表（设置项定义集中在这里，便于与 FCL 的设置页逐项对照） */
    fun rebuild() {
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

            Row.Group(R.string.dsh_setting_group_appearance),
            Row.Action(
                R.string.dsh_setting_theme_mode, R.string.dsh_setting_theme_mode_desc,
                R.string.dsh_action_open, ActionType.THEME_MODE
            ),
            Row.Action(
                R.string.dsh_setting_background, R.string.dsh_setting_background_desc,
                R.string.dsh_action_open, ActionType.BACKGROUND
            ),
            Row.Action(
                R.string.dsh_setting_animation_speed, R.string.dsh_setting_animation_speed_desc,
                R.string.dsh_action_open, ActionType.ANIMATION_SPEED
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
        if (cur is Row.Group) return false      // 组头与其后的行之间用默认间距
        return next !is Row.Group
    }

    private fun isPrevInSameGroup(position: Int): Boolean {
        val prev = rows.getOrNull(position - 1) ?: return false
        return prev !is Row.Group
    }

    class GroupHolder(val binding: ItemDshSettingGroupBinding) : RecyclerView.ViewHolder(binding.root)
    class ActionHolder(val binding: ItemDshSettingBinding) : RecyclerView.ViewHolder(binding.root)
    class SwitchHolder(val binding: ItemDshSettingSwitchBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Group -> TYPE_GROUP
        is Row.Switch -> TYPE_SWITCH
        else -> TYPE_ACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GROUP -> GroupHolder(ItemDshSettingGroupBinding.inflate(inflater, parent, false))
            TYPE_SWITCH -> SwitchHolder(ItemDshSettingSwitchBinding.inflate(inflater, parent, false))
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
                // 先解绑再设值，避免复用时的伪回调
                b.switchView.setOnCheckedChangeListener(null)
                b.switchView.isChecked = row.checked()
                b.switchView.setOnCheckedChangeListener { _, checked -> onSwitch(row, checked) }
                applyRowBackground(holder.itemView, position)
            }
        }
    }

    /**
     * 位置感知圆角 + 主题色 tint —— 与 FCL `LauncherSettingAdapter` 同款：
     * 组内首行仅上圆角、末行仅下圆角、中间无圆角；单独一行则四角圆角。
     */
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
        view.backgroundTintList = ColorStateList.valueOf(ThemeEngine.getInstance().getTheme().color)
    }
}
