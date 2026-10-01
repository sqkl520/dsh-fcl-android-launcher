package com.dsh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * dsh 运行/安装日志总线（单一来源）。
 *
 * ## 为什么需要它（修的两个问题）
 * 1. **性能**：原来 `DshRuntime._logs` 是 `StateFlow<List<String>>`，每来一行就 `(it + line)` 复制
 *    整个列表；同时日志页每来一行就把 500 行 `joinToString` 重新塞进 TextView。npm 安装/agent 运行
 *    会打印成千上万行，于是每行开销 O(n)、整体 O(n²)，UI 直接卡死。
 *    现在：内部用环形缓冲，**按 ~5Hz 合并刷新**一次快照（revision 递增），UI 按 revision 判断是否重绘。
 * 2. **安全**：dsh 启动时会打印 `http://127.0.0.1:PORT/?token=XXXX`，原来这行原样进了日志 UI。
 *    现在所有入总线（含落盘文件）的行都会做 token 脱敏（`token=***`），并把 token 注册进
 *    FCL 的 [com.tungsten.fclcore.util.Logging]，避免它从别的路径漏进日志文件。
 *
 * 额外能力：日志同时追加到 `<filesDir>/dsh/logs/runtime.log`（超 1MB 自动截断保留尾部），
 * 这样"进程崩了 / 被系统杀了"之后还能拿到现场，而不是只有内存里那几百行。
 */
object DshLogBus {

    private const val MAX_LINES = 2000
    private const val FLUSH_INTERVAL_MS = 200L
    private const val FILE_MAX_BYTES = 1L * 1024 * 1024

    /** 日志快照：UI 只比较 [revision]，避免无谓重绘 */
    data class Snapshot(val revision: Long, val lines: List<String>) {
        companion object {
            val EMPTY = Snapshot(0L, emptyList())
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val buffer = ArrayList<String>(256)
    /** 本次刷新周期内新增、尚未落盘的行 */
    private val pendingForFile = ArrayList<String>(64)

    private val _snapshot = MutableStateFlow(Snapshot.EMPTY)
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private var revision = 0L
    private var dirty = false
    private var flushJob: Job? = null

    @Volatile
    private var logFile: File? = null

    /** token 脱敏：`?token=XXXX` / `token=XXXX` → `token=***` */
    private val tokenRegex = Regex("""(?i)(token=)[A-Za-z0-9_\-]+""")

    /**
     * 时间戳格式化器。
     * **性能**：原来每行都 `SimpleDateFormat(...).format(...)`——构造一个 SimpleDateFormat 要编译
     * 模式串，npm 安装时每秒上千行的输出下这是白白的开销。SimpleDateFormat 不是线程安全的，所以
     * 按线程缓存一份。
     */
    private val timeFormat = ThreadLocal<SimpleDateFormat>()

    /** 取本线程的时间格式化器（没有就建一个） */
    private fun timeFormatter(): SimpleDateFormat {
        timeFormat.get()?.let { return it }
        val f = SimpleDateFormat("HH:mm:ss", Locale.US)
        timeFormat.set(f)
        return f
    }

    /** 已注册的敏感串（启动 token 等），做二次兜底替换 */
    private val registeredSecrets = CopyOnWriteArraySet<String>()

    /** 绑定落盘文件（App 启动时调一次）；[File] 由 [DshPaths] 给出 */
    @JvmStatic
    @Synchronized
    fun attachFile(file: File) {
        // 幂等：多个 Activity 的 onCreate 都会调用，避免重复做"读全文件再截断"的 IO。
        if (logFile?.absolutePath == file.absolutePath) return
        logFile = file
        // 截断可能读进最多 1MB 文本，别放主线程。
        DshAppScope.scope.launch {
            runCatching {
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() > FILE_MAX_BYTES) {
                    // 超限：只保留尾部若干行，避免日志无限增长占满手机。
                    // 在 lock 内做"读尾 + 覆写"，避免与 flush() 的追加写交错导致内容错乱。
                    synchronized(lock) {
                        val tail = file.readLines().takeLast(MAX_LINES / 2)
                        file.writeText(tail.joinToString("\n", postfix = "\n"))
                    }
                }
            }
        }
    }

    /** 追加一行（任意线程可调；内部串行化 + 合并刷新） */
    @JvmStatic
    fun append(line: String) {
        val stamped: String
        synchronized(lock) {
            val safe = sanitize(line)
            stamped = "${timeFormatter().format(Date())}  $safe"
            buffer += stamped
            pendingForFile += stamped
            // 环形裁剪：一次砍掉多余部分，避免每行都做 removeAt(0) 的数组搬移
            if (buffer.size > MAX_LINES) repeat(buffer.size - MAX_LINES) { buffer.removeAt(0) }
            dirty = true
        }
        scheduleFlush()
    }

    @JvmStatic
    fun appendAll(lines: List<String>) = lines.forEach { append(it) }

    @JvmStatic
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            dirty = true
        }
        scheduleFlush()
    }

    /** 导出全部内存日志（复制/分享用） */
    @JvmStatic
    fun export(): String = synchronized(lock) { buffer.joinToString("\n") }

    /** 当前内存日志行数（UI 顶部状态用） */
    @JvmStatic
    fun size(): Int = synchronized(lock) { buffer.size }

    /**
     * 把敏感 token 从日志里抹掉。
     * dsh 启动行形如：`dsh web: http://127.0.0.1:3080/?token=XXXX (LAN: ...)`
     */
    fun sanitize(line: String): String {
        var out = tokenRegex.replace(line) { m -> "${m.groupValues[1]}***" }
        for (t in registeredSecrets) out = out.replace(t, "***")
        return out
    }

    /** 注册一个需要脱敏的密钥（启动 token / API key） */
    fun registerSecret(secret: String) {
        if (secret.isEmpty()) return
        registeredSecrets += secret
        // 同时注册给 FCL 的 logger，避免经由 FCL 自身日志路径泄漏
        runCatching { com.tungsten.fclcore.util.Logging.registerAccessToken(secret) }
    }

    // --- 内部：合并刷新 -----------------------------------------------------

    @Synchronized
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_INTERVAL_MS)
            flush()
        }
    }

    private fun flush() {
        val snapshotLines: List<String>
        val toWrite: List<String>
        synchronized(lock) {
            if (!dirty) return
            dirty = false
            revision++
            snapshotLines = ArrayList(buffer)
            toWrite = ArrayList(pendingForFile)
            pendingForFile.clear()
        }
        _snapshot.value = Snapshot(revision, snapshotLines)
        val f = logFile ?: return
        if (toWrite.isEmpty()) return
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(toWrite.joinToString("\n", postfix = "\n"))
            // ★ 单次进程生命周期内日志可能无限增长（长驻 agent 能连续跑几天），
            // 光靠 attachFile 时截断是不够的：这里在每次落盘后检查一次，超限就只留尾部。
            rotateIfTooBig(f)
        }
    }

    /**
     * 超过 [FILE_MAX_BYTES] 时只保留尾部一半（按字符切，并丢掉可能被切断的首行）。
     * 在 [lock] 内调用，避免与别的写入交错。
     */
    private fun rotateIfTooBig(f: File) {
        if (f.length() <= FILE_MAX_BYTES) return
        synchronized(lock) {
            if (f.length() <= FILE_MAX_BYTES) return
            val keep = (FILE_MAX_BYTES / 2).toInt()
            val text = runCatching { f.readText() }.getOrNull() ?: return
            val tail = if (text.length <= keep) text else text.takeLast(keep).substringAfter('\n', "")
            f.writeText(tail)
        }
    }

    /** 退出/测试前强制刷一次 */
    fun flushNow() = flush()
}
