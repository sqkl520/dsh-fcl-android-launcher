package com.dsh.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ItemDshSettingBinding
import com.dsh.fcl.androidlauncher.databinding.ItemDshSettingGroupBinding
import com.dsh.fcl.androidlauncher.databinding.ItemDshSettingValueBinding
import com.tungsten.fcllibrary.component.theme.ThemeEngine

/**
 * 实例详情行式适配器 —— 结构照搬 FCL 的 `VersionSettingAdapter`：
 * 分组 + 行级复用，行类型有 **分组 / 当前值行 / 按钮行**，
 * 位置感知圆角（`bg_item_rounded*`）+ 组内紧贴、组间 8dp + 分割线。
 *
 * 与 FCL 的差异仅两点（可读性所需）：行底按主题色 tint；组头用不显眼的小节标题。
 *
 * 行内容由页面每次 `submit(rows)` 提供（行里的"当前值"随实例状态变化）。
 *
 * 使用者只有一个：[com.dsh.ui.shell.DshInstanceSettingPage]（详情页的「运行」/「配置」两段
 * 各建一个实例，各自提交自己那段的行清单）。
 */
class DshInstanceSettingAdapter(
    private val context: Context,
    private val onValue: (Row.Value) -> Unit,
    private val onAction: (Row.Action) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_VALUE = 1
        private const val TYPE_ACTION = 2
    }

    /**
     * 行的业务标识（页面据此分发动作）。
     *
     * ## 为什么没有 API Key 那一组
     * `API_KEY` / `KEY_STATUS` / `KEY_TEST` / `KEY_CLEAR` 四条连同它们的行都已移除：
     * Key 改由 dsh 自己的页面录入 —— 那边功能更全（能测连通、能跟着模型走），启动器再维护一份
     * 独立的录入界面只会出现"两处都能改、哪处是真值"的歧义。`DshCredentials` 本身仍在使用
     * （启动实例前要读它判断有没有 key），它是否下线是另一件事，与这里无关。
     *
     * ## 为什么没有 `LOGS`
     * 原来那是一条"查看日志"的按钮行，点了跳外壳的日志页。现在日志是**实例详情页的一个 tab**
     * （同页切换，不跨页面），页面内的事不该做成一行按钮，所以这一行连同 tag 一起删掉。
     *
     * ## 新增的三个
     * [STATUS] / [START_STOP] / [OPEN_WEB] 是"运行"段的主操作：详情页原来是个独立 Activity，
     * 进了详情反而启停不了实例（那两件事只存在于实例列表行和外壳右面板），现在补齐。
     */
    enum class Tag {
        NAME, MODEL, PROFILE, PORT,
        STATUS, START_STOP, OPEN_WEB,
        VERSION, DISK, PATH, VERIFY, REINSTALL, DELETE
    }

    sealed class Row {
        /** 分组标题（小节名） */
        data class Group(val title: Int) : Row()

        /**
         * 当前值行：左侧标题、右侧当前值；[editable] 为真时右侧显示编辑图标。
         * [value] 是取值函数（页面在绑定时调用，保证总是最新）；
         * [description] 传 0 表示没有描述（该行不占描述高度）。
         */
        data class Value(
            val title: Int,
            val description: Int,
            val value: () -> String,
            val tag: Tag,
            val editable: Boolean = true
        ) : Row()

        /** 按钮行：右侧一个动作按钮（[dangerous] 用于删除这类破坏性操作） */
        data class Action(
            val title: Int,
            val description: Int,
            val actionText: Int,
            val tag: Tag,
            val dangerous: Boolean = false
        ) : Row()
    }

    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    /** 供 [com.mio.ui.adapter.SpacingItemDecoration] 判断相邻行是否同组（同组 → 1dp 细缝 + 分割线） */
    fun isNextInSameGroup(position: Int): Boolean {
        val cur = rows.getOrNull(position) ?: return false
        val next = rows.getOrNull(position + 1) ?: return false
        if (cur is Row.Group) return false
        return next !is Row.Group
    }

    private fun isPrevInSameGroup(position: Int): Boolean =
        rows.getOrNull(position - 1)?.let { it !is Row.Group } == true

    class GroupHolder(val binding: ItemDshSettingGroupBinding) : RecyclerView.ViewHolder(binding.root)
    class ValueHolder(val binding: ItemDshSettingValueBinding) : RecyclerView.ViewHolder(binding.root)
    class ActionHolder(val binding: ItemDshSettingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Group -> TYPE_GROUP
        is Row.Value -> TYPE_VALUE
        else -> TYPE_ACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GROUP -> GroupHolder(ItemDshSettingGroupBinding.inflate(inflater, parent, false))
            TYPE_VALUE -> ValueHolder(ItemDshSettingValueBinding.inflate(inflater, parent, false))
            else -> ActionHolder(ItemDshSettingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Group -> (holder as GroupHolder).binding.groupTitle.setText(row.title)

            is Row.Value -> {
                val b = (holder as ValueHolder).binding
                b.title.setText(row.title)
                if (row.description != 0) {
                    b.description.visibility = View.VISIBLE
                    b.description.setText(row.description)
                } else {
                    b.description.visibility = View.GONE
                }
                b.value.text = row.value()
                b.buttonEdit.visibility = if (row.editable) View.VISIBLE else View.GONE
                val click = View.OnClickListener { onValue(row) }
                // 整行可点（FCL 的 value 行也是点行/点图标都能改）
                b.root.setOnClickListener(if (row.editable) click else null)
                b.buttonEdit.setOnClickListener(if (row.editable) click else null)
                b.buttonEdit.isEnabled = row.editable
                applyRowBackground(holder.itemView, position)
            }

            is Row.Action -> {
                val b = (holder as ActionHolder).binding
                b.title.setText(row.title)
                if (row.description != 0) {
                    b.description.visibility = View.VISIBLE
                    b.description.setText(row.description)
                } else {
                    b.description.visibility = View.GONE
                }
                b.action.setText(row.actionText)
                b.action.setOnClickListener { onAction(row) }
                applyRowBackground(holder.itemView, position)
            }
        }
    }

    /**
     * 位置感知圆角 + 主题色 tint。
     * 与 FCL 的 ListAdapter 一致：行底用「提亮后的主题色」(ltColor)。
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
        view.backgroundTintList =
            ColorStateList.valueOf(ThemeEngine.getInstance().getTheme().ltColor)
    }
}
