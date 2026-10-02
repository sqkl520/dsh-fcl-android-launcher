package com.dsh.ui.shell

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ItemDshSettingBinding

/** FCL 风格的启动器设置行：扁平 RecyclerView + 分组标题行。 */
class DshLauncherSettingAdapter(
    private val onAction: (Row.Action) -> Unit
) : RecyclerView.Adapter<DshLauncherSettingAdapter.Holder>() {

    sealed class Row {
        data class Group(val title: Int) : Row()
        data class Action(
            val title: Int,
            val description: Int,
            val actionText: Int,
            val action: ActionType
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
            Row.Group(R.string.dsh_setting_group_common),
            Row.Action(R.string.dsh_setting_language, R.string.dsh_setting_language_desc,
                R.string.dsh_action_open, ActionType.THEME_MODE),
            Row.Action(R.string.dsh_setting_about, R.string.dsh_about_subtitle,
                R.string.dsh_action_open, ActionType.ABOUT),

            Row.Group(R.string.dsh_setting_group_runtime),
            Row.Action(R.string.dsh_setting_runtime_check, R.string.dsh_setting_runtime_check_desc,
                R.string.dsh_action_verify_runtime, ActionType.RUNTIME_CHECK),
            Row.Action(R.string.dsh_setting_logs, R.string.dsh_setting_logs_desc,
                R.string.dsh_action_open, ActionType.OPEN_LOGS),
            Row.Action(R.string.dsh_setting_download, R.string.dsh_setting_download_desc,
                R.string.dsh_action_open, ActionType.OPEN_DOWNLOAD),

            Row.Group(R.string.dsh_setting_group_dsh),
            Row.Action(R.string.dsh_setting_instance, R.string.dsh_setting_instance_desc,
                R.string.dsh_action_open, ActionType.OPEN_INSTANCE),
            Row.Action(R.string.dsh_setting_export_logs, R.string.dsh_setting_export_logs_desc,
                R.string.dsh_action_copy, ActionType.EXPORT_LOGS),
        )
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemDshSettingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemDshSettingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val b = holder.binding
        when (row) {
            is Row.Group -> {
                b.title.setText(row.title)
                b.description.setText(R.string.dsh_setting_group_hint)
                b.action.visibility = android.view.View.GONE
            }
            is Row.Action -> {
                b.title.setText(row.title)
                b.description.setText(row.description)
                b.action.visibility = android.view.View.VISIBLE
                b.action.setText(row.actionText)
                b.action.setOnClickListener { onAction(row) }
            }
        }
    }
}
