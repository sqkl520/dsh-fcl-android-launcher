package com.dsh.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshRuntime
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshLogsBinding
import com.mio.dialog.ItemSelectionDialog
import kotlinx.coroutines.launch

/**
 * 日志视图 —— 控制台式，**两种视角共用同一个页面类**。
 *
 * | 视角 | 谁用 | 看什么 |
 * |---|---|---|
 * | **App 级**（[instanceId] 为 null） | 设置页 → 「日志」子页 | 启动器自己打的全部行：解压底座、安装 dsh、凭据、运行时归因…… |
 * | **实例级**（给了 [instanceId]） | 实例详情页 → 「日志」标签 | **只有这个实例**发出的行（从启动到停止） |
 *
 * ## 为什么是一个类而不是两个
 * 两者的**视图、渲染、筛选、复制、滚动逻辑完全一样**，唯一差别是"订阅哪条流"。
 * 拆成两个类等于把这 150 行抄两份，然后慢慢漂移（一份改了筛选、另一份没改）。
 * 所以差异收敛成构造函数里的一个可空参数，在 [onCreate] 里一次性选择订阅源。
 *
 * ## 订阅源怎么选
 * - `instanceId == null` → [DshLogBus.snapshot]（全局）
 * - 否则 → [DshLogBus.snapshotFor]（该实例；**首次调用即创建**那条流）
 *
 * ⚠️ **不要在 collect 里判断 `instanceId`** —— 订阅哪条流在 `onCreate` 时就要定下来，
 * 中途换流会让 `lastRevision` 的语义错乱（两条流的 revision 各自独立递增，
 * 换个实例后 revision 可能"看起来没变"，于是新实例的日志不刷新）。
 *
 * ## 设计（参考常见日志查看器）
 * - **按级别着色**：ERROR 红 / WARN 琥珀 / OK 绿 / 普通浅灰，`[tag]` 前缀用青色，一眼能扫出问题行；
 * - **级别筛选**：全部 / 警告及以上 / 仅错误（[ItemSelectionDialog] 选择）；
 * - **自动滚动**：贴底时自动跟随；向上翻看时不被抢走滚动位置，并出现「回到底部」；
 * - **信息行**：显示当前视角、展示行数 / 总行数与**该视角自己的落盘路径**。
 *
 * 渲染策略沿用之前的性能处理：只在快照的 revision 变化时重建文本。
 */
class DshLogsUI(
    context: Context,
    /**
     * 要看的实例；`null` = App 级全局日志。
     *
     * 默认值 `null` 是给设置页那个子页用的（它只想看 App 级），
     * 实例详情页则显式传实例 id。默认值让"App 级"成为不写参数时的自然选择 ——
     * 毕竟绝大多数日志页都只是想看看出什么事了。
     */
    private val instanceId: String? = null
) : DshPageUI(context, R.layout.activity_dsh_logs) {

    private val binding = ActivityDshLogsBinding.bind(contentView)
    private var lastRevision = -1L
    private var lines: List<String> = emptyList()
    private var filter: Filter = Filter.ALL

    private enum class Filter { ALL, WARN, ERROR }

    /** 单次渲染的最大行数（超出只显示末尾，避免超长日志把界面拖垮） */
    private val maxLines = 1500

    override fun onCreate() {
        super.onCreate()

        binding.btnClear.setOnClickListener {
            // 清哪个范围要跟视角一致：实例页里点"清空"只该清掉这个实例的日志，
            // 顺手把全局也清了会让人以为"别的东西的日志也丢了"（反之亦然）。
            if (instanceId == null) DshRuntime.clearLogs() else DshLogBus.clearFor(instanceId)
            toast(context.getString(R.string.dsh_logs_cleared))
        }
        binding.btnCopy.setOnClickListener { copyAll() }
        binding.btnFilter.setOnClickListener { pickFilter() }
        binding.btnJumpBottom.setOnClickListener {
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        // 向上翻看时显示「回到底部」，贴底时隐藏
        binding.logScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val atBottom = !binding.logScroll.canScrollVertically(1)
            binding.btnJumpBottom.visibility = if (atBottom) View.GONE else View.VISIBLE
        }

        binding.runtimeStatus.text = scopeLabel()

        // ★ 订阅源在**这里**定下来（理由见类注释：中途换流会让 lastRevision 错乱）
        val source = instanceId?.let { DshLogBus.snapshotFor(it) } ?: DshLogBus.snapshot
        scope.launch {
            source.collect { snap ->
                if (snap.revision == lastRevision) return@collect
                lastRevision = snap.revision
                lines = snap.lines
                render()
            }
        }
        // 运行状态只对实例视角有意义：App 级日志页显示"某个实例在跑"是噪音
        if (instanceId == null) {
            scope.launch {
                DshRuntime.state.collect { st -> binding.runtimeStatus.text = describe(st) }
            }
        }
        render()
    }

    /** 顶部那行"现在看的是谁的日志"。实例名取不到时回落到 id —— 至少能对上落盘文件名 */
    private fun scopeLabel(): String = if (instanceId == null) {
        context.getString(R.string.dsh_logs_scope_app)
    } else {
        val name = DshInstances.byId(instanceId)?.name ?: instanceId
        context.getString(R.string.dsh_logs_scope_instance, name)
    }

    private fun describe(st: DshRuntime.State): String = when (st) {
        is DshRuntime.State.Idle -> context.getString(R.string.dsh_state_idle)
        is DshRuntime.State.Starting -> context.getString(R.string.dsh_state_starting, st.name)
        is DshRuntime.State.Running -> context.getString(R.string.dsh_state_running_short, st.name, st.port)
        is DshRuntime.State.Stopping -> context.getString(R.string.dsh_state_stopping, st.name)
        is DshRuntime.State.Failed -> context.getString(R.string.dsh_state_failed, st.reason)
        is DshRuntime.State.Exited -> context.getString(R.string.dsh_runtime_exited, st.code)
    }

    private fun pickFilter() {
        val labels = listOf(
            context.getString(R.string.dsh_logs_filter_all),
            context.getString(R.string.dsh_logs_filter_warn),
            context.getString(R.string.dsh_logs_filter_error),
        )
        ItemSelectionDialog(
            context, context.getString(R.string.dsh_logs_filter_title), labels, true, filter.ordinal
        ) { pos, _ ->
            filter = Filter.entries[pos]
            render(forceScroll = true)
            // 筛选按钮是图标（FCL 规范），无法在按钮上显示当前级别 → 用一次轻提示反馈
            Toast.makeText(context, labels[pos], Toast.LENGTH_SHORT).show()
        }.show()
    }

    /** 按级别决定整行颜色 */
    private fun levelOf(line: String): Int = when {
        ERROR_RE.containsMatchIn(line) -> COLOR_ERROR
        WARN_RE.containsMatchIn(line) -> COLOR_WARN
        OK_RE.containsMatchIn(line) -> COLOR_OK
        else -> COLOR_INFO
    }

    private fun matches(line: String): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.WARN -> ERROR_RE.containsMatchIn(line) || WARN_RE.containsMatchIn(line)
        Filter.ERROR -> ERROR_RE.containsMatchIn(line)
    }

    private fun render(forceScroll: Boolean = false) {
        val atBottom = !binding.logScroll.canScrollVertically(1)
        val filtered = lines.filter(::matches)
        val shown = if (filtered.size > maxLines) filtered.takeLast(maxLines) else filtered

        val sb = SpannableStringBuilder()
        shown.forEachIndexed { i, line ->
            val start = sb.length
            sb.append(line)
            sb.setSpan(
                ForegroundColorSpan(levelOf(line)), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            // `[tag]` 前缀用青色，便于区分来源（setup / start-dsh / bootstrap / proot…）
            val tag = TAG_RE.find(line)
            if (tag != null && tag.range.first == 0) {
                sb.setSpan(
                    ForegroundColorSpan(COLOR_TAG), start, start + tag.value.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (i != shown.lastIndex) sb.append('\n')
        }
        binding.logText.text = sb

        val hidden = lines.size - filtered.size
        val truncated = filtered.size - shown.size
        binding.logInfo.text = buildString {
            append(context.getString(R.string.dsh_logs_info, shown.size, lines.size))
            if (hidden > 0) append(" · ").append(context.getString(R.string.dsh_logs_hidden, hidden))
            if (truncated > 0) append(" · ").append(context.getString(R.string.dsh_logs_truncated, truncated))
            // 落盘路径也按视角给：排障时"这个实例的日志写到哪了"是最先要问的问题，
            // 指到全局那个文件等于把人引到错的地方
            append(" · ").append(
                instanceId?.let { DshLogBus.instanceLogFile(it).absolutePath }
                    ?: com.dsh.core.DshPaths.LOG_FILE
            )
        }

        if (forceScroll || atBottom || binding.switchAutoScroll.isChecked) {
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyAll() {
        // 同"清空"：复制范围与视角一致（实例页里复制出来的是这个实例的日志）
        val text = DshLogBus.export(instanceId)
        if (text.isEmpty()) {
            toast(context.getString(R.string.dsh_logs_empty))
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dsh log", text))
        toast(context.getString(R.string.dsh_logs_copied))
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val COLOR_ERROR = 0xFFFF6B6B.toInt()
        private const val COLOR_WARN = 0xFFFFD166.toInt()
        private const val COLOR_OK = 0xFF4ADE80.toInt()
        private const val COLOR_INFO = 0xFFD0D6DD.toInt()
        private const val COLOR_TAG = 0xFF7DD3FC.toInt()

        private val ERROR_RE =
            Regex("""error|failed|failure|fatal|exception|denied|错误|失败|异常|拒绝|不可用""", RegexOption.IGNORE_CASE)
        private val WARN_RE = Regex("""warn|警告|注意""", RegexOption.IGNORE_CASE)
        private val OK_RE = Regex("""\bok\b|ready|done|success|passed|通过|完成|就绪""", RegexOption.IGNORE_CASE)
        private val TAG_RE = Regex("""^\[[^\]]+\]""")
    }
}
