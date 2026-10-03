package com.dsh.ui.shell

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ItemDshSettingBinding

/**
 * FCL 风格的启动器设置行：扁平 RecyclerView + 分组间距/分割线。
 *
 * ## 与 FCL `LauncherSettingAdapter` 对齐（本轮 UI 还原）
 * FCL 的启动器设置页**没有分组标题行**：它是一个扁平列表，靠
 * [com.mio.ui.adapter.SpacingItemDecoration] 表现分组 —— 组内行之间 1dp 分割线、
 * 组与组之间 8dp 间距。行布局是 `item_launcher_setting_button.xml`
 * （`bg_item_rounded` + minHeight 48dp + 左右 12dp）。
 * 本适配器照此模型：每行带 [SettingGroup]，由 [isNextInSameGroup] 供装饰器判断。
 */
class DshLauncherSettingAdapter(
    private val onAction: (Row.Action) -> Unit
) : RecyclerView.Adapter<DshLauncherSettingAdapter.Holder>() {

    /** 设置分组（决定组内分割线与组间间距） */
    enum class SettingGroup { Common, Runtime, Dsh }

    sealed class Row {
        data class Action(
            val title: Int,
            val description: Int,
            val actionText: Int,
            val action: ActionType,
            val group: SettingGroup
        ) : Row()
    }

    enum class ActionType {
        THEME_MODE,
        RUNTIME_CHECK,
        OPEN_LOGS,
        OPEN_DOWNLOAD,
        OPEN_INSTANCE,
        ABOUT,
        EXPORT_LOGS
    }

    private var rows: List<Row> = emptyList()

    fun rebuild() {
        rows = listOf(
            Row.Action(
                R.string.dsh_setting_language, R.string.dsh_setting_language_desc,
                R.string.dsh_action_open, ActionType.THEME_MODE, SettingGroup.Common
            ),
            Row.Action(
                R.string.dsh_setting_about, R.string.dsh_about_subtitle,
                R.string.dsh_action_open, ActionType.ABOUT, SettingGroup.Common
            ),
            Row.Action(
                R.string.dsh_setting_runtime_check, R.string.dsh_setting_runtime_check_desc,
                R.string.dsh_action_verify_runtime, ActionType.RUNTIME_CHECK, SettingGroup.Runtime
            ),
            Row.Action(
                R.string.dsh_setting_logs, R.string.dsh_setting_logs_desc,
                R.string.dsh_action_open, ActionType.OPEN_LOGS, SettingGroup.Runtime
            ),
            Row.Action(
                R.string.dsh_setting_download, R.string.dsh_setting_download_desc,
                R.string.dsh_action_open, ActionType.OPEN_DOWNLOAD, SettingGroup.Runtime
            ),
            Row.Action(
                R.string.dsh_setting_instance, R.string.dsh_setting_instance_desc,
                R.string.dsh_action_open, ActionType.OPEN_INSTANCE, SettingGroup.Dsh
            ),
            Row.Action(
                R.string.dsh_setting_export_logs, R.string.dsh_setting_export_logs_desc,
                R.string.dsh_action_copy, ActionType.EXPORT_LOGS, SettingGroup.Dsh
            ),
        )
        notifyDataSetChanged()
    }

    /**
     * 第 [position] 行与下一行是否属于**同一分组**。
     * 供 [com.mio.ui.adapter.SpacingItemDecoration] 决定该行下方是 1dp 分割线（同组）
     * 还是 8dp 间距（跨组）。与 FCL `LauncherSettingAdapter.isNextInSameGroup` 同义。
     */
    fun isNextInSameGroup(position: Int): Boolean {
        val current = rows.getOrNull(position) as? Row.Action ?: return false
        val next = rows.getOrNull(position + 1) as? Row.Action ?: return false
        return current.group == next.group
    }

    class Holder(val binding: ItemDshSettingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemDshSettingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val b = holder.binding
        when (row) {
            is Row.Action -> {
                b.title.setText(row.title)
                b.description.setText(row.description)
                b.action.visibility = View.VISIBLE
                b.action.setText(row.actionText)
                b.action.setOnClickListener { onAction(row) }
            }
        }
    }
}
