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
 * dsh 运行/安装日志总线。
 *
 * ## 两条流（本次改造）
 * - **全局流**（[snapshot] / [append] / [clear] / `export()` / `size()`）：App 级视角。
 *   启动器自己的动作（解压底座、安装、凭据、进程生命周期、清单读写）都进这里。
 *   语义与改造前**完全一致** —— 设置页的"日志"子页与"复制全部日志"要的就是这个全貌。
 * - **按实例流**（[snapshotFor] / [appendFor] / [clearFor] / `export(id)` / `size(id)`）：
 *   某个实例自己的日志。实例详情页的日志 tab 只订阅它，于是"这个实例从启动到停止发了什么"
 *   不再被别的实例、别的安装、底座解压的输出淹没。
 *
 * ★ [appendFor] **同时**写入两条流（即"实例流 ⊂ 全局流"），**不是**互斥的两份。
 *   理由：全局流是排障用的"时间线全貌"，用户点"复制全部日志"时最需要的东西恰恰是运行输出；
 *   若实例行只进实例流，全局里就丢了这段现场，等于把最该看的行藏起来。
 *   ⚠️ 所以别把 [appendFor] 改成只写实例流 —— 那是"两条流并存"这个设计的反面。
 *   反之 [append] 只进全局：它是 App 级行，本来就不属于任何实例。
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
 * 每个实例另有自己的 `<filesDir>/dsh/logs/instance-<id>.log`（同一套轮转机制），
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

    // --- 全局流（语义与改造前一致） -----------------------------------------

    private val buffer = ArrayList<String>(256)

    /** 本次刷新周期内新增、尚未落盘的行（全局） */
    private val pendingForFile = ArrayList<String>(64)

    private val _snapshot = MutableStateFlow(Snapshot.EMPTY)
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private var revision = 0L
    private var dirty = false

    // --- 按实例的流 ---------------------------------------------------------

    /**
     * 一个实例的日志状态。
     * **上限与全局一致**（[MAX_LINES]），但**按需创建**：实例可能很多，全部预先建会白白占内存；
     * 而"没人看的实例日志"没有保留价值 —— 长期存档靠落盘文件，内存里只留最近 [MAX_LINES] 行。
     */
    private class InstanceStream {
        val buffer = ArrayList<String>(256)
        val pendingForFile = ArrayList<String>(64)

        /**
         * 该实例的快照流。
         * ★ 这里**故意**不调 `asStateFlow()`：那会返回一个新的只读包装对象，于是
         *   `snapshotFor(id)` 两次调用拿到的是两个不同对象，而 UI 收集到的那个收不到后续新行
         *   （契约是"同一个 id 返回同一个 StateFlow"）。直接把这个 MutableStateFlow 以
         *   `StateFlow` 类型暴露即可 —— 对象是同一个，对外也只有只读能力。
         */
        val state = MutableStateFlow(Snapshot.EMPTY)

        /** 每个实例有**自己的** revision：别的实例刷了不该让这个实例的 UI 重绘 */
        var revision = 0L
        var dirty = false
    }

    /** 实例 id → 它的流。**首次调用 [snapshotFor] / [appendFor] 时才创建**，之后复用同一个对象 */
    private val streams = LinkedHashMap<String, InstanceStream>()

    /** 实例 id → 它的落盘文件（惰性推导并缓存，见 [fileFor]） */
    private val instanceFiles = LinkedHashMap<String, File>()

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

    /**
     * 绑定**全局**日志的落盘文件（App 启动时调一次）；[File] 由 [DshPaths] 给出。
     *
     * 实例日志文件不在这里绑定：实例是"用的时候才知道有哪些"，所以由 [fileFor] 在第一次往该实例
     * 写日志时惰性推导路径。两者共用同一套落盘与轮转逻辑（见 [flush] / [appendToFile]）。
     */
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

    /** 追加一行到**全局**流（任意线程可调；内部串行化 + 合并刷新） */
    @JvmStatic
    fun append(line: String) {
        // 加工（脱敏 + 时间戳）在锁外做、入缓冲在锁内做 —— 与改造前一致，只是现在两处入口
        // 共用同一个 [stamp]，脱敏不可能被某条流绕过。
        val stamped = stamp(line)
        synchronized(lock) { appendGlobalLocked(stamped) }
        scheduleFlush()
    }

    @JvmStatic
    fun appendAll(lines: List<String>) = lines.forEach { append(it) }

    /**
     * 追加一行到某实例的流，**并同时进全局流**。
     *
     * 适用判据：这一行是"该实例自己产生/关于该实例的"输出（它的 stdout/stderr、它的安装进度、
     * 它的启动/停止/失败归因）。App 级动作（底座解压、清单读写）不要走这里，见类注释。
     */
    @JvmStatic
    fun appendFor(instanceId: String, line: String) {
        // ★ 同一行只加工一次，两条流拿到的是**同一个字符串**：分别加工的话两条流的时间戳会差
        //   几毫秒，按时间对照两边就对不上了。
        val stamped = stamp(line)
        synchronized(lock) {
            // 两条流都写：实例流是"这个实例的视角"，全局流是"所有事情的时间线"，缺一不可
            appendGlobalLocked(stamped)
            val st = streams.getOrPut(instanceId) { InstanceStream() }
            st.buffer += stamped
            st.pendingForFile += stamped
            trimLocked(st.buffer)
            st.dirty = true
        }
        scheduleFlush()
    }

    /** 清**全局**缓冲（实例缓冲不受影响） */
    @JvmStatic
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            dirty = true
        }
        scheduleFlush()
    }

    /**
     * 只清某实例的缓冲，全局那份不动。
     * 实例不存在时**什么都不做，也不创建** —— "清空一个没人写过的日志"没有意义，
     * 顺手创建反而会让调用方以为这个实例有日志。
     */
    @JvmStatic
    fun clearFor(instanceId: String) {
        synchronized(lock) {
            val st = streams[instanceId] ?: return
            st.buffer.clear()
            st.dirty = true
        }
        scheduleFlush()
    }

    /**
     * 某实例的日志快照。**首次调用即创建**该实例的缓冲与流，之后返回同一个 StateFlow
     * （UI 收集的就是它，所以不能每次 new 一个 —— 那样收集者永远收不到新行）。
     */
    @JvmStatic
    fun snapshotFor(instanceId: String): StateFlow<Snapshot> =
        synchronized(lock) { streams.getOrPut(instanceId) { InstanceStream() }.state }

    /**
     * 导出内存日志（复制/分享用）。
     * @param instanceId null = 全局（**默认值，保持向后兼容**）；给 id = 该实例（没有该实例时返回空串）
     */
    @JvmStatic
    fun export(instanceId: String? = null): String = synchronized(lock) {
        if (instanceId == null) buffer.joinToString("\n")
        else streams[instanceId]?.buffer?.joinToString("\n") ?: ""
    }

    /** 当前内存日志行数（UI 顶部状态用）。参数含义同 [export] */
    @JvmStatic
    fun size(instanceId: String? = null): Int = synchronized(lock) {
        if (instanceId == null) buffer.size else streams[instanceId]?.buffer?.size ?: 0
    }

    /**
     * 某实例的落盘文件：`<filesDir>/dsh/logs/instance-<id>.log`。
     *
     * ★ 为什么这么绕地从 `DshPaths.LOG_FILE` 推导目录，而不是在 [DshPaths] 里加一个常量：
     *   [DshPaths] 是路径定义的唯一权威（含目录布局的文档注释），本轮改动不打算动它；
     *   而"实例日志与全局日志放在同一个 logs/ 目录下"是这里唯一需要的约束，
     *   `LOG_FILE.parentFile` 就是那个目录 —— 全局日志文件改路径时，实例日志跟着走，不会分叉。
     *   （代价：读起来多一层推导，所以在这里写清楚。）
     *
     * ⚠️ 实例 id 目前由 `DshInstances.newId()` 生成，形如 `inst-1700000000000-3-7f2a`，
     *   全是文件安全字符；但这里仍做一次白名单转义（见 [safeFileId]）作为防御，
     *   万一将来 id 生成方式变了（含 `/`、`\`、`:`），落盘会写到别的目录或互相覆盖，
     *   那种 bug 极难查。
     */
    fun instanceLogFile(instanceId: String): File {
        // 未初始化（[DshPaths.loadPaths] 还没跑）时不能崩：正常流程下 Application 启动时就已就绪，
        // 走到兜底分支只可能是单测或异常时序，此时落到临时目录比抛异常好。
        val logDir = runCatching { File(DshPaths.LOG_FILE).parentFile }.getOrNull()
            ?: File(System.getProperty("java.io.tmpdir") ?: ".", "dsh/logs")
        return File(logDir, "instance-${safeFileId(instanceId)}.log")
    }

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

    // --- 内部：入总线的唯一加工口 -------------------------------------------

    /**
     * 行进入两条流的**唯一加工口**：先脱敏、再打时间戳。
     *
     * ★ 为什么收敛成一个函数：脱敏必须"进总线时做一次"才可靠 —— 只要 [append] / [appendFor]
     *   是仅有的两个入口，且都走这里，就不存在"某条流漏了脱敏"的可能（实例流是新增的，
     *   最怕的就是新路径忘了脱敏，把 token 写进 instance-*.log）。
     *   顺带保证同一行在两条流里是**同一个字符串**（含同一个时间戳），对不上号就没法排障。
     */
    private fun stamp(line: String): String = "${timeFormatter().format(Date())}  ${sanitize(line)}"

    /** 写全局缓冲（调用方必须持有 [lock]） */
    private fun appendGlobalLocked(stamped: String) {
        buffer += stamped
        pendingForFile += stamped
        trimLocked(buffer)
        dirty = true
    }

    /**
     * 环形裁剪：一次砍掉超出部分。
     * （`subList().clear()` 是单次数组搬移 O(移除数)，不是逐个 `removeAt(0)` 的 O(n²)；
     * 2000 行规模下也不会每行都搬移整表。）
     */
    private fun trimLocked(buf: ArrayList<String>) {
        if (buf.size > MAX_LINES) {
            buf.subList(0, buf.size - MAX_LINES).clear()
        }
    }

    /**
     * 实例 id → 落盘文件。
     *
     * ★ 只在 [DshPaths] 已就绪时**缓存**：路径未就绪时 [instanceLogFile] 会落到临时目录兜底，
     *   若把那个兜底路径缓存下来，等 [DshPaths.loadPaths] 跑完之后仍然会往临时目录写 —— 实例日志
     *   就永远不出现在 `<filesDir>/dsh/logs/` 里，而且这个错误一旦发生就不可自愈（缓存不失效）。
     *   调用方必须持有 [lock]。
     */
    private fun fileFor(instanceId: String): File {
        if (!DshPaths.isLoaded) return instanceLogFile(instanceId)
        return instanceFiles.getOrPut(instanceId) { instanceLogFile(instanceId) }
    }

    /** 文件名白名单：只允许字母/数字/`-`/`_`，其余一律换成 `_`（理由见 [instanceLogFile]） */
    private fun safeFileId(instanceId: String): String {
        val sb = StringBuilder(instanceId.length)
        for (c in instanceId) {
            sb.append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_')
        }
        return sb.toString()
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

    /** 一次刷新里要追加到某个文件的一批行（把锁内算好的东西带出来，锁外只做 IO） */
    private class FileWrite(val file: File, val lines: List<String>)

    /**
     * 一次刷新同时处理两条流：
     * - 全局：`dirty` → revision 递增 + 快照 + 待落盘行 → 写 `runtime.log`
     * - 各实例：各自 `dirty` → **各自的** revision 递增 + 各自的快照 + 各自的待落盘行 → 写各自的文件
     *
     * revision 按流独立递增：只有"这条流有新行"时才变，于是别的实例刷了不会让本实例的 UI 重绘
     * （反之亦然）。全局与实例的 revision 数值互不相关，UI 只比较自己订阅的那条。
     *
     * ★ 快照赋值也放在 [lock] 内：`scheduleFlush` 排的那次刷新（200ms 后）可能与 [flushNow]
     *   排的那次撞在一起。若赋值在锁外，两次刷新算出的快照会竞态写入，晚算的那次可能把**更旧**
     *   的快照盖上（UI 少一段日志且 revision 回退，直到下一行才恢复）。放进锁里，赋值顺序与
     *   递增顺序就一致了。文件 IO 仍在锁外（`rotateIfTooBig` 会自己再取一次锁，是可重入的）。
     */
    private fun flush() {
        var globalWrite: List<String> = emptyList()
        var instanceWrites: List<FileWrite> = emptyList()

        synchronized(lock) {
            val anyInstanceDirty = streams.values.any { it.dirty }
            // 两条流都没新内容：什么都不做（保持原来"无变化不刷"的语义，避免空转重绘）
            if (!dirty && !anyInstanceDirty) return

            if (dirty) {
                dirty = false
                revision++
                _snapshot.value = Snapshot(revision, ArrayList(buffer))
                globalWrite = ArrayList(pendingForFile)
                pendingForFile.clear()
            }

            if (anyInstanceDirty) {
                val writes = ArrayList<FileWrite>(streams.size)
                for ((id, st) in streams) {
                    if (!st.dirty) continue
                    st.dirty = false
                    st.revision++
                    st.state.value = Snapshot(st.revision, ArrayList(st.buffer))
                    val lines = ArrayList(st.pendingForFile)
                    st.pendingForFile.clear()
                    if (lines.isNotEmpty()) writes += FileWrite(fileFor(id), lines)
                }
                instanceWrites = writes
            }
        }

        val globalFile = logFile
        if (globalFile != null && globalWrite.isNotEmpty()) appendToFile(globalFile, globalWrite)
        // ★ 实例文件只在**全局日志已绑定**时才写。理由：全局日志所在的目录就是实例日志的目录
        //   （见 [instanceLogFile] 从 `DshPaths.LOG_FILE` 推导）；没绑定 = 路径系统还没就绪，
        //   这时实例路径只能落到临时目录兜底，往那儿写日志没有用处（不是 App 私有目录，
        //   UI 显示的"日志路径"也不指向它），只会在单测里凭空产生文件。
        //   真机上两者是同一个时刻就绪的（`DshPaths.loadPaths` 结束前就 [attachFile]），所以不影响行为。
        if (globalFile != null) {
            for (w in instanceWrites) appendToFile(w.file, w.lines)
        }
    }

    /**
     * 追加落盘 + 超限轮转。全局与实例文件共用这一份实现
     * （两条流各写各的文件，但"怎么写、怎么截断"必须完全一样，否则排障时两边行为不一致）。
     */
    private fun appendToFile(f: File, lines: List<String>) {
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(lines.joinToString("\n", postfix = "\n"))
            // ★ 单次进程生命周期内日志可能无限增长（长驻 agent 能连续跑几天），
            // 光靠 attachFile 时截断是不够的：这里在每次落盘后检查一次，超限就只留尾部。
            // 实例文件同理：历史遗留的超大文件会在它第一次被写入时被裁到尾部。
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
