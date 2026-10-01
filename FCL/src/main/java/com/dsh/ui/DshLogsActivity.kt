package com.dsh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
import com.tungsten.fcl.R
import com.tungsten.fcl.databinding.ActivityDshLogsBinding
import com.tungsten.fcllibrary.component.FCLActivity
import kotlinx.coroutines.launch

/**
 * dsh 运行/安装日志控制台。
 *
 * ## 本次改造（性能 + 可用性）
 * 1. **不再每行全量重绘**：原来订阅 `List<String>` 后每来一行就 `joinToString` + `fullScroll`，
 *    npm 装依赖那种高频输出会把界面卡死。现在订阅 [DshLogBus.snapshot]（内部按 ~5Hz 合并、
 *    带 revision），只有 revision 变化时才重绘。
 * 2. **不抢用户的滚动位置**：自动滚到底只在"用户本来就贴着底部"或开关打开时执行。
 * 3. **越界可见**：日志页现在可达（列表页工具栏 + 实例菜单 + 失败对话框都有入口）。
 * 4. **可复制/清空**，并显示落盘日志文件路径（进程崩了还能事后取）。
 */
class DshLogsActivity : FCLActivity() {

    private lateinit var binding: ActivityDshLogsBinding
    private var lastRevision = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDshLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClear.setOnClickListener {
            DshRuntime.clearLogs()
            toast(getString(R.string.dsh_logs_cleared))
        }
        binding.btnCopy.setOnClickListener { copyAll() }
        binding.logPath.text = getString(R.string.dsh_logs_path, DshPaths.LOG_FILE)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    DshLogBus.snapshot.collect { snap ->
                        if (snap.revision == lastRevision) return@collect
                        lastRevision = snap.revision
                        render(snap.lines)
                    }
                }
                launch {
                    DshRuntime.state.collect { st -> binding.runtimeStatus.text = describe(st) }
                }
            }
        }
    }

    private fun describe(st: DshRuntime.State): String = when (st) {
        is DshRuntime.State.Idle -> getString(R.string.dsh_state_idle)
        is DshRuntime.State.Starting -> getString(R.string.dsh_state_starting, st.name)
        is DshRuntime.State.Running ->
            getString(R.string.dsh_state_running_short, st.name, st.port)
        is DshRuntime.State.Stopping -> getString(R.string.dsh_state_stopping, st.name)
        is DshRuntime.State.Failed -> getString(R.string.dsh_state_failed, st.reason)
        is DshRuntime.State.Exited -> getString(R.string.dsh_runtime_exited, st.code)
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
            toast(getString(R.string.dsh_logs_empty))
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dsh log", text))
        toast(getString(R.string.dsh_logs_copied))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
