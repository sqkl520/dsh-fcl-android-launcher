package com.dsh.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshLogsBinding
import kotlinx.coroutines.launch

/**
 * 运行/安装日志页（外壳 tab 3）。由原 `DshLogsActivity` 迁移为 [DshPageUI]。
 * 订阅 [DshLogBus.snapshot]（~5Hz 合并、带 revision），只在 revision 变化时重绘。
 */
class DshLogsUI(context: Context) : DshPageUI(context, R.layout.activity_dsh_logs) {

    private val binding = ActivityDshLogsBinding.bind(contentView)
    private var lastRevision = -1L

    override fun onCreate() {
        super.onCreate()
        binding.btnClear.setOnClickListener {
            DshRuntime.clearLogs()
            toast(context.getString(R.string.dsh_logs_cleared))
        }
        binding.btnCopy.setOnClickListener { copyAll() }
        binding.logPath.text = context.getString(R.string.dsh_logs_path, DshPaths.LOG_FILE)

        scope.launch {
            DshLogBus.snapshot.collect { snap ->
                if (snap.revision == lastRevision) return@collect
                lastRevision = snap.revision
                render(snap.lines)
            }
        }
        scope.launch {
            DshRuntime.state.collect { st -> binding.runtimeStatus.text = describe(st) }
        }
    }

    private fun describe(st: DshRuntime.State): String = when (st) {
        is DshRuntime.State.Idle -> context.getString(R.string.dsh_state_idle)
        is DshRuntime.State.Starting -> context.getString(R.string.dsh_state_starting, st.name)
        is DshRuntime.State.Running ->
            context.getString(R.string.dsh_state_running_short, st.name, st.port)
        is DshRuntime.State.Stopping -> context.getString(R.string.dsh_state_stopping, st.name)
        is DshRuntime.State.Failed -> context.getString(R.string.dsh_state_failed, st.reason)
        is DshRuntime.State.Exited -> context.getString(R.string.dsh_runtime_exited, st.code)
    }

    private fun render(lines: List<String>) {
        val atBottom = binding.logScroll.canScrollVertically(1).not()
        binding.logText.text = lines.joinToString("\n")
        if (atBottom || binding.switchAutoScroll.isChecked) {
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyAll() {
        val text = DshLogBus.export()
        if (text.isEmpty()) {
            toast(context.getString(R.string.dsh_logs_empty))
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dsh log", text))
        toast(context.getString(R.string.dsh_logs_copied))
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
