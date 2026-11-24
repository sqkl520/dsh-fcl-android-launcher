package com.dsh.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.locks.ReentrantLock

/**
 * 日志行级别（K27）。
 *
 * 三档与 DshLogsUI 的三个过滤档位一一对应：
 * - [INFO]  = `Filter.ALL` 可见（含命中 OK_RE 的"ok 档"行 —— 枚举刻意不设 OK 档，
 *   UI 的第四种颜色由 DshLogsUI 自己保留口径，见其 `levelOf`）；
 * - [WARN]  = `Filter.WARN` 及以上可见；
 * - [ERROR] = 仅 `Filter.ERROR` 可见。
 *
 * 判定口径由 [DshLogBus.classifyLevel] 给出（正则逐字迁自 DshLogsUI，等价性由单测钉住）。
 */
enum class LogLevel(val code: Int) {
    /** 其余行与 OK 档（`ok`/`ready`/`done`… 命中 OK_RE 的行）—— Filter.ALL 下可见、WARN 档起隐藏 */
    INFO(0),

    /** WARN_RE 命中且未命中 ERROR_RE 的行—— Filter.WARN / Filter.ERROR 都显示 */
    WARN(1),

    /** ERROR_RE 命中——仅 Filter.ERROR 显示 */
    ERROR(2),
}

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
 *
 * ## Round 15 追加（K27 / BL-079）
 * - **K27**：每行入总线时预计算级别（[classifyLevel]，口径迁自 DshLogsUI），快照携带与
 *   [Snapshot.lines] 同序等长的 [Snapshot.levels] —— UI 渲染热路径不再跑正则。
 * - **BL-079**：落盘批次带序号（[FileWrite.seq]），锁内按序进单一 FIFO、出锁后由唯一的
 *   排空者逐批落盘（见 [drainFileWrites]）——多路 flush 并发交错时，同一文件的行序也不会写出倒置。
 */
object DshLogBus {

    private const val MAX_LINES = 2000
    private const val FLUSH_INTERVAL_MS = 200L
    private const val FILE_MAX_BYTES = 1L * 1024 * 1024

    /**
     * BL-075: consecutive-failure limit per file before a failed batch is dropped instead of being
     * requeued. A disk-full phone would otherwise retry a batch forever every ~200ms (hot loop);
     * after this many consecutive failures we give up on the batch, leave one warning line and keep
     * the failure visible via [lastFileWriteError]. A later successful write resets the counter.
     */
    private const val FILE_WRITE_FAIL_LIMIT = 3

    /**
     * Upper bound on how many per-instance streams stay resident in memory (K10 backstop).
     * Each stream can hold up to [MAX_LINES] lines plus a StateFlow, so an unbounded map is a
     * real leak under create/delete churn. The primary cleanup is [removeStream] (wired into
     * instance deletion by T-B1); this cap only evicts the least-recently-used stream if we ever
     * exceed it. Generous on purpose — real installs have a handful of instances, not dozens.
     */
    private const val MAX_STREAMS = 32

    /**
     * ★ SP-07：被淘汰流的最后 revision 记忆表的容量上限（见 [evictedRevisions]）。
     * 取 [MAX_STREAMS] 的 2 倍 —— 淘汰/重建来回一轮也不会把"刚被淘汰那条"挤掉，
     * 同时保证这张表本身**有界**（它按 id 记，id 由实例创建/npm 安装churn 产生，可以无限多）。
     */
    private const val MAX_EVICTED_REVISIONS = MAX_STREAMS * 2

    /**
     * 日志快照：UI 只比较 [revision]，避免无谓重绘。
     *
     * **不变式（K27）**：[levels] 与 [lines] **同序等长**，`levels[i]` 即 `lines[i]` 的级别。
     * 级别在行**入总线那一刻**预计算（append/stamp 路径逐行 [classifyLevel]，BL-075 告警注入路径
     * 同样补齐），快照发布时与 [lines] 一起防御性拷贝 —— 所以发布后的 Snapshot 是自洽只读的，
     * UI 侧可直接按 [levels] 过滤，无需再跑任何正则。
     * [EMPTY]（以及任何用默认参数构造的旧式 Snapshot）两者都为空，满足不变式。
     */
    data class Snapshot(
        val revision: Long,
        val lines: List<String>,
        /** 与 [lines] 同序等长；默认空列表保持旧构造点（含 [EMPTY]）兼容 */
        val levels: List<LogLevel> = emptyList()
    ) {
        companion object {
            val EMPTY = Snapshot(0L, emptyList())
        }
    }

    /**
     * ★ R49 / B-4：同一个失败（同一条描述）只在**首现**与每 [FLUSH_FAILURE_LOG_EVERY] 次时
     * 各记一行 —— 见 [flushExceptionHandler]。
     *
     * 为什么必须防刷屏：handler 记的那一行本身会 `append` 进总线 ⇒ `dirty = true` ⇒
     * [scheduleFlush] 排下一轮 ⇒ 若 [flush] 是**稳定复现**地抛（如 OOM 后内存仍紧张），
     * 每 200ms 就多一行失败行。2000 行的环形缓冲约 7 分钟就会被失败行整圈挤掉 ——
     * 那正好是排障最需要的那段现场。首现必记（用户/开发者要看见），之后只留心跳。
     */
    private const val FLUSH_FAILURE_LOG_EVERY = 100

    /**
     * ★ R49 / B-4：[flush] 抛出的 Throwable 原来落到线程默认 handler，**进程级无痕迹**。
     *
     * 为什么这不是小事：`armFlushJob` 用 `scope.launch` 起的是**根协程**（不是某个请求的子任务），
     * `SupervisorJob` 只会让"别的任务不受牵连"，异常本身既不进状态流也不进日志 ——
     * 于是"日志页不刷新 / 落盘悄悄停了"在 App 侧表现为**完全静默**：用户看不到任何东西，
     * 开发者拿到的日志文件也停在崩溃前那一行。
     *
     * 记进总线（而不是只写 stderr）的理由：真机上 stderr 没人收；总线是唯一"用户能看见
     * + 能复制导出"的出口。措辞带 `flush job` 前缀，便于在日志里直接检索。
     */
    private val flushExceptionHandler = CoroutineExceptionHandler { _, t ->
        // handler 自己绝不能再抛（协程框架会把它当 uncaught 再走一遍默认 handler）
        runCatching { recordFlushFailure(t) }
    }

    private val flushFailureLock = Any()

    /** 上一条已记录的失败描述（同描述 = 同一条失败在重复），见 [FLUSH_FAILURE_LOG_EVERY] */
    private var flushFailureDesc: String? = null

    /** 当前这条失败描述已重复的次数（首现后从 0 起累加；描述变了就重置） */
    private var flushFailureRepeat = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + flushExceptionHandler)
    private val lock = Any()

    /**
     * ★ R49 / B-4：[flushExceptionHandler] 的记录体（拆成独立函数便于单测直接钉措辞）。
     *
     * 去重规则（见 [FLUSH_FAILURE_LOG_EVERY]）：同一条描述首现记一行原文，之后每第
     * [FLUSH_FAILURE_LOG_EVERY] 次记一行带重复计数的"心跳"；描述变了则重新按"首现"记。
     * [flush] 正常跑完会清空这条记忆（见函数尾），于是"故障消失后再犯"仍会立刻记一行。
     */
    private fun recordFlushFailure(t: Throwable) {
        val desc = "log flush job failed: ${t.javaClass.simpleName}" +
            (t.message?.let { ": $it" } ?: "")
        val repeat = synchronized(flushFailureLock) {
            if (desc == flushFailureDesc) {
                flushFailureRepeat++
                flushFailureRepeat
            } else {
                flushFailureDesc = desc
                flushFailureRepeat = 0
                0
            }
        }
        if (repeat == 0) append(desc)
        else if (repeat % FLUSH_FAILURE_LOG_EVERY == 0) {
            append("$desc (repeated $repeat time(s))")
        }
    }

    /**
     * 测试 seam（R49/B-4）：把一次失败**真的**投进总线 scope —— 走的正是生产路径
     * （`SupervisorJob + Dispatchers.IO + flushExceptionHandler` 的根协程），
     * 从而证明 handler 真的接上了，而不是只断言"字段里有个 handler 对象"。
     */
    fun launchFailingJobForTest(t: Throwable) {
        scope.launch { throw t }
    }

    /**
     * 测试 seam（R49/B-4）：scope 上下文中注册的 [CoroutineExceptionHandler]（结构性断言用）。
     * 生产代码不要调。
     */
    fun scopeExceptionHandlerForTest(): CoroutineExceptionHandler? =
        scope.coroutineContext[CoroutineExceptionHandler]

    /**
     * Separate monitor for **file** mutations (append + rotate + the attach-time truncation).
     *
     * K11: rotation used to re-acquire [lock] — the very monitor every append()/appendFor() holds
     * to touch the in-memory buffers — while it read up to [FILE_MAX_BYTES] of a file and rewrote
     * it. A single oversized log therefore stalled *all* producers. File IO already runs off [lock]
     * (in flush()'s tail); serializing it on its own monitor keeps concurrent writers from
     * interleaving without ever blocking the in-memory hot path.
     */
    private val fileIoLock = Any()

    // --- BL-079：落盘批次按序排队 -------------------------------------------

    /**
     * BL-079: FIFO of drained batches awaiting a file write, drained by [drainFileWrites].
     *
     * A [FileWrite] is inserted here **inside the same [lock] critical section that drained it**
     * from the in-memory rings (see [flush]) — so queue order is exactly the order the lines
     * entered the buffers, globally. [drainFileWrites] then pops and writes one batch at a time
     * (each write still under [fileIoLock]), which makes the on-disk line order the queue order:
     * two racing flushes can no longer write their batches to the same file out of order (the old
     * code serialized only the `appendText` call, not *which* batch got to append first).
     *
     * On the drain side the guarantee is deliberately as strong as the old inline writes: a
     * flush/flushNow caller returns only once the queue is empty **and** no other drainer is mid
     * batch — i.e. every batch enqueued before the call is on disk. Callers that find the queue
     * already being drained park on [fileQueueIdle] (the drainer drains their batch too, FIFO)
     * instead of writing concurrently.
     *
     * Why one global queue instead of one per file: every file write already serializes on
     * [fileIoLock] (different files included), so per-file queues would buy no extra concurrency —
     * only extra machinery. One queue keeps "insertion order == write order" trivially true.
     *
     * Guards / lock order (no cycles):
     * - insertion ([enqueueFileWrite], caller holds [lock]): [lock] → [fileQueueLock];
     * - [drainFileWrites]: [fileQueueLock] alone (claim/await/poll), then [fileIoLock] alone
     *   (write); [fileQueueLock] is never held while acquiring [lock] or [fileIoLock].
     */
    private val fileQueueLock = ReentrantLock()

    /**
     * BL-079: signalled (signalAll) when [fileDraining] flips back to false — queue drained
     * empty, or a drainer escaped with an exception and released its claim. Waiters in
     * [drainFileWrites] re-check the flag on wake.
     */
    private val fileQueueIdle = fileQueueLock.newCondition()

    private val fileWriteQueue = ArrayDeque<FileWrite>()

    /**
     * BL-079: true while some thread is draining [fileWriteQueue]. Claimed and released under
     * [fileQueueLock]; the release-on-empty happens inside the same critical section as the
     * empty poll **and after every polled batch has been written**, so a caller parked on
     * [fileQueueIdle] can only proceed after all batches it could care about are on disk (no
     * lost wake-up: the releaser signals in that same critical section).
     */
    private var fileDraining = false

    /**
     * BL-079: batch sequence — assigned under [lock] when a batch is enqueued, so sequence order
     * == queue order == write order per file. Recorded on [FileWrite.seq] as the inspectable
     * ordering token; the guarantee itself comes from the queue discipline above.
     */
    private var fileWriteSeq = 0L

    /**
     * Test-only (BL-079): batch seqs handed to the file writer, in write order, while
     * [recordingDrainedSeqs] is on. Default off — production pays only a field read per batch,
     * and the list stays empty (guarded by [fileQueueLock]; enabled via
     * [startRecordingDrainedSeqsForTest]).
     */
    private var recordingDrainedSeqs = false
    private val drainedSeqs = ArrayList<Long>()

    // --- 全局流（语义与改造前一致） -----------------------------------------

    private val buffer = ArrayList<String>(256)

    /**
     * K27: 与 [buffer] **同序等长的级别并行数组** —— 每条行进 [buffer] 的那一刻同步进这里
     * （见 [appendGlobalLocked] / [handleFileWriteFailure] 的告警注入），[trimLocked] 同步裁剪。
     * 不变式 levels.size == buffer.size 恒成立；快照发布时一起拷贝进 [Snapshot.levels]。
     */
    private val levels = ArrayList<LogLevel>(256)

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

        /** K27: 与 [buffer] 同序等长的级别并行数组（不变式同全局流，见 DshLogBus.levels） */
        val levels = ArrayList<LogLevel>(256)

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

    /**
     * 实例 id → 它的流。**首次调用 [snapshotFor] / [appendFor] 时才创建**，之后复用同一个对象。
     *
     * K10: access-ordered (`accessOrder = true`) so [streamFor] can evict the least-recently-used
     * entry once the map exceeds [MAX_STREAMS]. `get`/`put` bump an entry to the most-recent end;
     * plain iteration (flush, eviction scan) does not reorder.
     */
    private val streams = LinkedHashMap<String, InstanceStream>(16, 0.75f, true)

    /** 实例 id → 它的落盘文件（惰性推导并缓存，见 [fileFor]） */
    private val instanceFiles = LinkedHashMap<String, File>()

    /**
     * ★ SP-07：被 LRU 淘汰的流**最后一次发布过的 revision**（id → revision），让重建出来的流
     * 从那里**接着数**而不是从 0 重来。有界（[MAX_EVICTED_REVISIONS]），FIFO 挤掉最老的。
     *
     * ## 为什么必须记住（缺陷本体）
     * 消费侧用 `snap.revision == lastRevision` 做「同帧早退」（`DshLogsUI:417`、
     * `DshWebViewActivity:706`）。revision 只在 [flush] 里随快照发布而递增，**从不回退** ——
     * 这是那些早退判据成立的前提。淘汰把 `InstanceStream` 整个丢掉后，重建的新流 revision
     * 从 0 开始：1200 → 0 是**回退**，早退判据虽然因此"不成立"（会重绘），但重绘的内容是
     * **新流里那几行**（`Snapshot` 只有 buffer 的副本）⇒ 用户眼前的上千行历史一次性塌成 1 行。
     * 更糟的是下一帧若又从 1 数到 2……只要还低于 `lastRevision`（1200）就会反复重绘，
     * 而界面内容始终是残缺的。
     *
     * ## 为什么是"记住 revision"而不是"不淘汰"
     * 不淘汰就回到内存无界（[MAX_STREAMS] 存在的理由）。记住一个 Long 是常数代价，
     * 且只在淘汰时写一次。**它不恢复丢掉的 buffer** —— buffer 就是内存本身，恢复它等于不淘汰。
     * 所以本表的职责收窄成一句：**让 revision 单调，UI 的早退判据不被打破**。
     * （buffer 的丢失对 UI 的伤害因此从"历史塌成 1 行"降级为"重建后从空开始，与旧行为同级
     *   的内存回收语义一致"—— 这正是 [MAX_STREAMS] 当初就接受的代价。）
     *
     * 调用方必须持有 [lock]。
     */
    private val evictedRevisions = LinkedHashMap<String, Long>()

    /**
     * Guarded by [lock]. True from the moment a merged flush is armed until that flush actually
     * begins draining (where it is cleared). While true, [scheduleFlush] will not arm a second
     * delayed job. See [scheduleFlush] / [flush] for the K09 reasoning.
     */
    private var flushScheduled = false

    @Volatile
    private var logFile: File? = null

    /**
     * BL-075: last observed file-write failure, for diagnostics (public read-only). Null until a
     * write fails; deliberately sticky once set — the in-memory warning lines (see [appendToFile])
     * tell the chronological story, this just answers "did anything ever fail, and where".
     * Format: `log file write failed: <absolute path> (<ExceptionClass>: <message>)`.
     *
     * ★ R49/B-2：**粘性**是它与 [logFileWriteFailure] 的分工所在 —— 本字段回答"这个进程里
     *   有没有失败过（以及在哪）"，永不自动消失；那条流回答"**此刻**是不是还在失败"，
     *   写成功即清空。UI 要的是后者（横幅该随恢复而消失），排障要的是前者。
     */
    @Volatile
    var lastFileWriteError: String? = null
        private set

    /**
     * ★ R49 / B-2：**当前是否正处在落盘失败状态** —— 给 UI 订阅的那条流。
     *
     * ## 缺陷本体
     * 改前 [lastFileWriteError] 只有定义和内部赋值，`grep -rn 'lastFileWriteError' FCL/src/main/java/`
     * 在 main 侧**零消费**（只有测试读）。后果：磁盘满导致日志写不进时，**除了打开日志页
     * 并恰好翻到失败那一刻，用户完全不知情** —— 而日志写不进恰恰是"排障现场正在丢"的那一类
     * 故障，越是不知情越无从下手。
     *
     * ## 为什么单独开一条流而不是让 UI 读那个字段
     * 1. 那个字段是 [@Volatile] 的普通字段，**没有可订阅性**：UI 只能在某个时刻轮询一次，
     *    既看不到"刚刚失败"，也看不到"已经恢复"（横幅会永远挂在屏幕上或是永远不出现）；
     * 2. 粘性语义与横幅语义**相反**：字段记"发生过"，横幅要"现在还在发生"。
     *
     * ## 语义（与 [lastFileWriteError] 的分工）
     * - 落盘/轮转/启动截断任一失败 → 置为该次失败的描述（与 [lastFileWriteError] 同串）；
     * - **同一路径**上任何一次写成功 → 清回 `null`（横幅随之消失）；
     * - 别的路径成功不清它（A 文件坏了不代表 B 文件修好了 A）。
     *
     * ⚠️ 它是 [MutableStateFlow]，**订阅时立刻回放当前值** —— UI 在故障期间才打开日志页
     * 也能看到横幅（不需要"恰好撞上失败那一帧"）。
     */
    private val _logFileWriteFailure = MutableStateFlow<String?>(null)
    val logFileWriteFailure: StateFlow<String?> = _logFileWriteFailure.asStateFlow()

    /** [logFileWriteFailure] 当前值对应的路径（清空判据用；与流同锁更新，见 [noteFileWriteError]） */
    @Volatile
    private var fileWriteFailurePath: String? = null

    /**
     * ★ R49 / B-2：**记一次落盘失败**的唯一入口（[lastFileWriteError] + [logFileWriteFailure]
     * 一起置位，两者永远说同一件事）。
     *
     * 为什么收敛成一个函数：本文件有三条失败路径（[appendToFile] 的批写失败、
     * [rotateIfTooBig] 的轮转读失败、[attachFile] 的启动截断失败）。改前只有两条会置
     * [lastFileWriteError]，第三条（B-1）根本是裸 `runCatching` —— 同一个概念三套口径。
     * 现在三条都走这里，新增失败路径也只需要记一次。
     */
    private fun noteFileWriteError(path: String, description: String) {
        synchronized(fileIoLock) {
            lastFileWriteError = description
            fileWriteFailurePath = path
            _logFileWriteFailure.value = description
        }
    }

    /**
     * ★ R49 / B-2：某路径**写成功**了 —— 若当前挂着的失败正是这条路径，清掉它（横幅消失）。
     *
     * 只在"同一个文件"上清：日志写失败是**按文件**的（全局 `runtime.log` 与各实例文件各自
     * 独立失败），A 文件恢复不该让 B 文件的横幅消失。
     */
    private fun clearFileWriteErrorIfPath(path: String) {
        synchronized(fileIoLock) {
            if (fileWriteFailurePath == path) {
                fileWriteFailurePath = null
                _logFileWriteFailure.value = null
            }
        }
    }

    /** 测试 seam（R49/B-2）：把失败状态清回"从未失败"，避免状态泄漏给别的用例。 */
    fun resetFileWriteErrorForTest() {
        synchronized(fileIoLock) {
            lastFileWriteError = null
            fileWriteFailurePath = null
            _logFileWriteFailure.value = null
        }
    }

    /**
     * BL-075: consecutive write-failure counter per file path (guarded by [fileIoLock]). A failed
     * batch is requeued for retry only while this counter is below [FILE_WRITE_FAIL_LIMIT]; once
     * the limit is hit the batch is dropped (warning line only) and later batches are not retried
     * either, until some write to that file succeeds (which resets the counter). This bounds a
     * disk-full hot loop to ~[FILE_WRITE_FAIL_LIMIT] attempts per failure streak instead of one
     * futile write every [FLUSH_INTERVAL_MS] forever.
     */
    private val fileWriteFailCounts = HashMap<String, Int>()

    /**
     * BL-075: paths already warned about *dropping* batches in the current failure streak, so the
     * "giving up" warning is emitted once per streak instead of once per batch (a disk-full phone
     * with steady output would otherwise add one warning line every ~[FLUSH_INTERVAL_MS] forever).
     * Cleared together with the counter on the next successful write. Guarded by [fileIoLock].
     */
    private val fileWriteDropWarned = HashSet<String>()

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

    /**
     * ★ R34：可被注册为 secret 的最长长度。
     *
     * 与 [MIN_SECRET_LENGTH] 对称的下界/上界：[sanitize] 对每条 secret 都要跑一次
     * `String.replace`，代价与 secret 长度成正比。红队实测（`SecretProbe2`）注册一条
     * **20 万字符**的串可以成功 —— 此后每行日志的 `replace` 都要扫 20 万字符的 needle。
     * 真 token 是 43 字符 base64url，[MAX_SECRET_LENGTH] 留了远超真实需求的余量，
     * 但把"一条输出就能让整个进程的每条日志变慢"这条路堵上。
     */
    private const val MAX_SECRET_LENGTH = 256

    /**
     * ★ R34：已注册 secret 的**条数**上限。
     *
     * ## 为什么需要（缺陷本体）
     * [sanitize] 对**每一个**已注册串跑一次 `out.replace(t, "***")`，而 [registeredSecrets]
     * 原来是**无上限**的 `CopyOnWriteArraySet`。触发路径完全在 rootfs 手里：
     * 往可写的 `/tmp` 里追加 5 万行 `x?token=Q0001…`（每行都过 8 字符门槛），
     * `onProcessLine` 就每行调一次 [registerSecret] —— 集合无限增长，而
     * [MAX_LINES] 裁的是**日志**，从来不裁 secret 集合。
     *
     * 红队实测（round34 `D:\tmp\redteam34\SecretProbe2`，JDK 17）：
     * 5 万条 → `sanitize` 1.668 ms/行、注册累计 60.7 s；10 万条 → 3.429 ms/行
     * （≈500 行/s 时要 171% 单核，即**日志热路径被永久拖垮**）。
     *
     * ## 上限取多少：16
     * 真 token 每实例 1 个（启动 token），兜底重试可能换 1 个，进程内同时活着的实例量级是
     * 个位数 —— 16 已经给了很大余量。代价上界：16 × 256 字符 = 4 KB，每行固定最多 16 次
     * `String.replace`，与攻击者写入多少行**彻底无关**。
     *
     * ## 超限策略：拒绝新增（**不是** LRU 淘汰）
     * 两种选择的安全含义完全不同，这一点是本节最重要的部分：
     * - **LRU 淘汰**会把一条**已经漏进日志的** secret 从集合里踢出去 —— 此后含该 token 的
     *   新行（或重新出现的同一行）就**变回明文**。也就是说"淘汰"在安全上是**撤销脱敏**：
     *   攻击者只要在真 token 打印之后灌满 16 条伪造 secret，就能把真 token 挤出去。
     * - **拒绝新增**（本实现）方向相反：集合只增不减，已登记的秘密**永远**继续被脱敏；
     *   被拒绝的只是**攻击者的伪造串**不再被登记。代价是伪造串可能以明文留在日志里 ——
     *   但它们本来就是攻击者自己写进 `/tmp` 的公开内容，**不是秘密**。
     *
     * 一句话：宁可少脱敏几个攻击者自己写的假串，也绝不把真 token 重新变回明文。
     *
     * ## 为什么不是"每实例"而是"全局"
     * [registeredSecrets] 是进程级的，[sanitize] 是**无上下文**的（不知道这行属于谁）——
     * 按实例分别设上限只会让"总条数 = 实例数 × 上限"重新变成无界（实例数上限 [MAX_STREAMS]
     * 是 32，32 × 16 = 512 条仍可接受，但收益为零而复杂度翻倍）。进程级单一是零成本的正确形状。
     */
    private const val MAX_SECRETS = 16

    /**
     * 已注册的敏感串（启动 token 等），做二次兜底替换。
     *
     * ★ R34：**有上限**（[MAX_SECRETS]），超限**拒绝新增**而不是淘汰 —— 理由见 [MAX_SECRETS]。
     * 仍需并发安全（`onProcessLine` 在 IO 线程，[sanitize] 在任意生产者线程）：
     * 继续用 `CopyOnWriteArraySet`，但上限把它的复制代价也一并钉死。
     *
     * 注意：**必须是 `Set`**，不能用 `List` —— 真 token 会被 `onProcessLine` 的
     * "重复打印/迟到 URL"路径反复注册（同一 token 每次刷新都走一遍），`List` 会让
     * 同一 token 占满整个上限。
     */
    private val registeredSecrets = CopyOnWriteArraySet<String>()

    /** [registerSecret] 被上限挡掉的次数（诊断用；只增不减的计数器，无锁读写的近似值足够） */
    private val rejectedSecretCount = java.util.concurrent.atomic.AtomicInteger()

    /**
     * ★ R34：注册的**串行化**监视器。
     *
     * `CopyOnWriteArraySet` 自身线程安全，但"先看 size 再 add"不是原子的 —— 两个线程同时
     * 看到 `size == MAX_SECRETS - 1` 就会双双写入，把上限顶破一格。registration 不在热路径
     * （真 token 一辈子一两次），用一把独立的小锁换"上限是硬上限"很划算。
     *
     * **不能**复用 [lock]：那样会把 `sanitize` 的生产者线程和总线缓冲锁搅在一起，
     * 而 [sanitize] 完全不需要 [lock]（它只读 COW 集合，快照语义天然安全）。
     */
    private val secretLock = Any()

    /**
     * ★ R34：**禁止被脱敏**的日志词表（缺陷 2 的修法）。
     *
     * ## 问题
     * [sanitize] 对每个已注册串做无差别 `out.replace(t, "***")`。只要注册了一个恰好是
     * 协约词片段的"secret"，协约行就被打成马赛克，`scriptFailureReason` 返回 null，
     * **R33 刚修好的端口归因被静默废掉**。红队实测（round34 `PortAttr2`）：
     * - 注册 `port-in-use` → `[start-dsh] FAILED reason=*** port=3080 code=1`（reason=null）
     * - 注册 `start-dsh`  → `[***] FAILED reason=port-in-use port=3080 code=1`（reason=null）
     *
     * 攻击成本极低：rootfs 往 `/tmp` 写一行 `x?token=port-in-use`（11 字符，过 R33 的 8 字符门槛）。
     *
     * ## 为什么"再加个门槛"不行
     * `port-in-use` 有 11 字符，比门槛长；把门槛提到 52 才够，而真 token 是 43 字符 —— 那样
     * 连真 token 都注册不进来（等于把脱敏整个关掉）。
     *
     * ## 本实现的判据：**内容**，不是长度
     * 拒绝注册"任何是下列词表片段（substring）的串"。为什么这一个检查就够：
     *
     * 1. 能进入 [registeredSecrets] 的串，经 [registerSecret] 的形状门后必然形如
     *    `[A-Za-z0-9_-]+`（见 [TOKEN_SHAPE_RE]）；两个真实调用点都走
     *    `UrlScanner.tokenOf(url)`，其正则就是 `[A-Za-z0-9_\-]+`。
     * 2. 于是 secret **不可能**含 `[`/`]`/`=`/`:`/空格/换行 —— 它永远无法改写
     *    `[start-dsh]`、`FAILED`、`reason=` 这些**结构骨架**，只能落在骨架之间的
     *    "值"上：前缀名（`start-dsh`）与原因码（`port-in-use` 等）。
     * 3. 因此"能破坏归因的 secret"必然满足：是某个**协约词**的连续子串。
     *    词表有限且固定，逐个 `contains` 即可完整覆盖 —— 这不是打地鼠式的黑名单，
     *    而是由第 2 点的字符集约束推出的**完备判据**。
     *
     * 注意方向是 `词表词.contains(secret)`（secret 是词的片段），**不是**反过来：
     * 43 字符的真 token 长于任何词，不可能出现在协约行里，不该被误拒。
     *
     * ⚠️ **维护约定**：本词表必须与 `FCL/src/main/assets/dsh/scripts/` 下的脚本打印的
     *    `FAILED reason=<code>` 取值、以及 `[<prefix>]` 前缀名保持同步。
     *    `R34SecretCapTest.contractVocabularyCoversEveryScriptReasonCode` 会扫描脚本目录
     *    做交叉校验 —— 脚本新增一个原因码而这里没跟上时，那条用例会红。
     */
    private val protectedLogVocabulary = listOf(
        // --- 影响脚本协约行（scriptFailureReason / tailSaysPortInUse）---
        "start-dsh",          // 前缀名
        "port-in-use",        // ← 红队实测的武器
        "node-exit",
        "package-missing",
        "node-missing",
        "no-writable-logdir",
        "ready-timeout",
        "unexpected-exit",    // setup 的 trap 兜底
        "node-version",
        "npm-missing",
        "verify-version",
        "smoke-env",
        "smoke-test",
        // --- 影响 seccomp 兜底判定（looksLikeSeccompTrouble）---
        // 该判据查 "seccomp" / "ptrace" / "bad system call" / "operation not permitted"。
        // 前两个都短于 MIN_SECRET_LENGTH，注册不进来；后两个含空格，token 形状的串同样
        // 拼不出整句 —— 但可以打掉其中一个**单词**（"operation" / "permitted" 都是 9 字符），
        // 于是整句不再匹配。保险起见把单词也列上。
        "operation",
        "permitted"
    )

    /**
     * ★ R34：token 形状门（缺陷 2 完备性论证的第 2 点）。
     *
     * 只有形如 `[A-Za-z0-9_-]+` 的串才允许进入 [registeredSecrets]。理由有两条：
     * - **安全**：这保证 secret 永远无法含 `[`/`]`/`=`/`:`/空白/换行，因而无法改写协约行的
     *   结构骨架，也无法借 `\n` 制造伪行（[stamp] 里的 [escapeLineBreaks] 只处理了进入总线的
     *   行，注册路径是另一条独立入口）。
     * - **正确性**：两个真实调用点（`DshRuntime` 的 `UrlScanner.tokenOf(url)`）产出的就是
     *   这个字符集；非 token 形状的串本来就不该被当成"token 值"去全文替换。
     */
    private val TOKEN_SHAPE_RE = Regex("""^[A-Za-z0-9_\-]+$""")

    /**
     * ★ R33：可被注册为 secret 的最短长度。
     *
     * 低于此长度的串不做 `replace` 脱敏 —— 否则单字符 secret 会把整份日志打成马赛克
     * （详见 [registerSecret] 的 KDoc）。dsh 真实 token 是 43 字符 base64url，
     * 8 这个阈值离它很远，不会漏掉任何真 token。
     */
    private const val MIN_SECRET_LENGTH = 8

    /**
     * BL-075 / test-only seam: global file path as [attachFile] bound it (null when unbound).
     * Production code uses [logFile] directly; exposed so the failure-path test can restore the
     * singleton exactly (see [detachFileForTest]).
     */
    val attachedFilePathForTest: String? get() = logFile?.absolutePath

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
                    // 在 [fileIoLock] 内做"读尾 + 覆写"，与 flush() 的追加写（也走 fileIoLock）串行，
                    // 避免内容错乱；不占生产者的 [lock]（见 K11）。
                    synchronized(fileIoLock) {
                        val tail = file.readLines().takeLast(MAX_LINES / 2)
                        file.writeText(tail.joinToString("\n", postfix = "\n"))
                    }
                }
            }.onFailure { e ->
                // ★ R49/B-1：启动截断失败**不许静默**。
                //
                // 缺陷本体：同一份文件里 [rotateIfTooBig] 用 `.getOrElse` 把读失败记进
                // [lastFileWriteError]，而这里（唯一的另一处"读全文件再覆写"）是裸 `runCatching`
                // —— 同一个概念两套口径。失败场景都是真实的：1MB 文件读进 `readLines()` 时 OOM、
                // 文件被并发删掉（`FileNotFoundException`）、覆写时 ENOSPC。
                //
                // 静默的代价：截断没做成 ⇒ 文件继续超限 ⇒ **每一轮** flush 的尾部都会触发
                // [rotateIfTooBig] 的全量读 + 重写（`appendToFile` 每次落盘后都调它），在
                // `fileIoLock` 上把**所有实例**的日志写盘拖慢 —— 而现场唯一的痕迹是"日志页卡"，
                // 没有任何一行说明为什么。
                //
                // 措辞与 [rotateIfTooBig] 对齐（`log file rotate failed: <path> (<Class>: <msg>)`），
                // 只把动作名换成 `truncate` 以区分"启动时的一次性截断"与"落盘后的轮转"。
                noteFileWriteError(
                    file.absolutePath,
                    "log file truncate failed: ${file.absolutePath} (${e.javaClass.simpleName}" +
                        (e.message?.let { ": $it" } ?: "") + ")"
                )
            }
        }
    }

    /**
     * R17（编排）：把"本进程是谁"写进日志流首 —— 每次进程启动调一次（幂等：同一进程重复调用
     * 只写一次；同一条横幅重复出现即为"进程重启过"的天然标记）。
     *
     * 动因：R16/R17 两轮真机排查都卡在同一个问题——**不知道用户装的是哪个包**
     * （fix3/fix4 均未发布，用户却测过 fix3 行为；排查时只能靠猜）。现在每次启动的第一行
     * 就会写清：`launcher=0.1.6-SNAPSHOT-fix4 (code=110)`。以后任何一份日志贴出来，
     * 第一行就能定位包与代次。
     *
     * 版本串与「关于页/系统应用信息」同源（[BuildConfig.VERSION_NAME]，含 fix 标签）。
     * 写成 [append]（全局流）——它是 App 级事实，不属于任何实例。
     */
    private var versionBannerWritten = false

    @JvmStatic
    fun writeVersionBannerIfNeeded() {
        synchronized(DshLogBus) {
            if (versionBannerWritten) return
            versionBannerWritten = true
        }
        val version = runCatching { com.dsh.fcl.androidlauncher.BuildConfig.VERSION_NAME }.getOrNull() ?: "?"
        val code = runCatching { com.dsh.fcl.androidlauncher.BuildConfig.VERSION_CODE }.getOrDefault(0)
        append("[app] launcher=$version (code=$code)")
    }

    /** 追加一行到**全局**流（任意线程可调；内部串行化 + 合并刷新） */
    @JvmStatic
    fun append(line: String) {
        // 加工（脱敏 + 时间戳）在锁外做、入缓冲在锁内做 —— 与改造前一致，只是现在两处入口
        // 共用同一个 [stamp]，脱敏不可能被某条流绕过。
        val stamped = stamp(line)
        // K27: 级别在"入总线那一刻"预计算一次（每条消息一次）。必须在 [stamp] **之后**算：
        // 脱敏可能改写命中内容（脱敏前后文本不同），UI 现有口径跑的就是入库后的最终文本。
        val level = classifyLevel(stamped)
        synchronized(lock) { appendGlobalLocked(stamped, level) }
        scheduleFlush()
    }

    @JvmStatic
    fun appendAll(lines: List<String>) = lines.forEach { append(it) }

    /**
     * 追加一行**进度事件**到全局流，级别恒 [LogLevel.INFO]，不走 [classifyLevel] 的文本判级。
     *
     * 为什么需要这个窄口（fix8 / BL-170 的配套，R21-W1B 上报）：解包阶段逐文件写
     * `[extract] <相对路径>`，而 rootfs 里 **464/35101（1.3%）** 条路径本身含
     * `error|failed|exception` 字样（`libgpg-error.so.0`、`npm/lib/utils/audit-error.js`
     * 这类真实包名）—— [classifyLevel] 对整行跑正则，会把它们判成 ERROR，在日志页刷成红字、
     * 并被 Filter.ERROR 档收进"只看错误"的视图。那是**假错误**，排障现场里假错误的代价
     * 比没有错误更高（用户/开发者会去查一个不存在的问题）。
     *
     * 判据不是"这行恰好不含敏感词"，而是**它根本没有语义级别可言**：一条"正在解哪个文件"
     * 是进度事件，不是消息文本，[classifyLevel] 对它不适用而非判错。所以豁免是对的。
     *
     * ★ 用途受限：只给进度/明细这类无级别含义的行用。任何"来自进程输出/错误分支"的行
     *   仍须走 [append] 让判级器工作 —— 别拿这个口去躲一段本该显红的真错误。
     * 脱敏与时间戳照旧（同一 [stamp]），本方法只跳过级别判定。
     */
    @JvmStatic
    fun appendProgress(line: String) {
        val stamped = stamp(line)
        synchronized(lock) { appendGlobalLocked(stamped, LogLevel.INFO) }
        scheduleFlush()
    }

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
        // K27: 同一行只算一次级别 —— 行字符串两条流共用（见上），级别同理共用，绝不分叉
        val level = classifyLevel(stamped)
        synchronized(lock) {
            // 两条流都写：实例流是"这个实例的视角"，全局流是"所有事情的时间线"，缺一不可
            appendGlobalLocked(stamped, level)
            val st = streamFor(instanceId)
            st.buffer += stamped
            st.levels += level
            st.pendingForFile += stamped
            trimLocked(st.buffer, st.levels)
            st.dirty = true
        }
        scheduleFlush()
    }

    /** 清**全局**缓冲（实例缓冲不受影响） */
    @JvmStatic
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            // K27: 级别并行数组与行缓冲**同生共死** —— 任何只清一边的写法都会破坏不变式
            levels.clear()
            dirty = true
        }
        scheduleFlush()
    }

    /**
     * ★ R33：清空已注册的 secret（测试 seam）。
     *
     * 为什么需要它：[registeredSecrets] 是进程级 `CopyOnWriteArraySet`，[clear] 只清
     * buffer/levels、**不清它** —— 而它又会无差别参与 [sanitize] 的 `replace`。
     * 于是"某次注册了错误的/被伪造的 token"这件事在整个进程生命周期内无法撤销，
     * 且会跨测试用例互相污染（前一个用例注册的串让后一个用例的断言莫名其妙地红）。
     *
     * 名字带 `ForTest` 是刻意的：生产代码不该在运行期调用它 —— 撤销脱敏等于把已经
     * 决定要隐藏的串重新暴露出来，那是安全上的倒退。测试用完即弃，没有这个顾虑。
     */
    @JvmStatic
    fun clearForTestOfRegisteredSecrets() {
        synchronized(secretLock) { registeredSecrets.clear() }
        rejectedSecretCount.set(0)
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
            st.levels.clear() // K27: 与 buffer.clear() 成对（不变式）
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
        synchronized(lock) { streamFor(instanceId).state }

    /**
     * Drop an instance's in-memory stream — its buffer, StateFlow and cached log-file handle — so
     * a deleted instance stops occupying memory (K10). Idempotent; an unknown id is a no-op.
     *
     * ⚠️ NOT yet wired in. Instance deletion lives in [DshInstances.delete], which this task must
     * not touch; **T-B1 owns that file and must call `DshLogBus.removeStream(id)` there** (right
     * after the instance directory is removed). Until then this is exercised only by the
     * [MAX_STREAMS] backstop in [streamFor] and by tests. It deliberately does NOT delete the
     * on-disk `instance-<id>.log` — removing the instance directory (DshInstances.delete's job)
     * already takes that file with it.
     */
    @JvmStatic
    fun removeStream(instanceId: String) {
        synchronized(lock) {
            streams.remove(instanceId)
            instanceFiles.remove(instanceId)
            // ★ SP-07：显式删除是"这个 id 彻底不存在了"，与 LRU 淘汰**语义不同** ——
            //   不记 revision（重建就当全新流，revision 从 0 起），也不把 pendingForFile 入队：
            //   调用方（DshInstances.delete）紧接着就删 `instance-<id>.log`，入队只会把文件
            //   原地写回来（那条注释见 DshInstances.delete）。丢掉的行本来也随文件一起消失。
            evictedRevisions.remove(instanceId)
        }
    }

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
     * ## ★ SP-05：文件名折叠必须与**目录名**用的是同一个规则
     *
     * 这里委托 [DshPaths.safeInstanceDirName]（只折 `/`、`\`、NUL），**不再**用私有白名单。
     *
     * 旧实现的 `safeFileId` 把除 `isLetterOrDigit` / `-` / `_` 之外的一切字符折成 `_`，于是一批
     * **不同**的合法 id 折到**同一个**文件名：
     *
     * ```
     * instances/my app   → id "my app"  ┐
     * instances/my.app   → id "my.app"  ┼→ instance-my_app.log   ← 三条流写同一个文件
     * instances/my_app   → id "my_app"  ┘
     * ```
     *
     * 这些 id 是真实存在的：`rebuildMissingInstances` 原样用目录名当 id（零校验），而
     * `instances/` 通过 `--bind=<ROOT_DIR>:$GUEST_ROOT` 对 rootfs 可写 —— rootfs 里 `mkdir` 一个
     * 含空格/点的目录，冷启就会被收编成实例。后果是**日志串台**，且 `DshInstances.delete` 删一个
     * 实例会把另一个实例的日志文件一起 `delete()` 掉。
     *
     * ## 为什么"与目录名同规则"就够了（与目录名同口径，但折叠函数本身**不单射**）
     *
     * 先说清 SP-05 到底修了什么、没修什么 —— **别把这里读成"碰撞已被消灭"**：
     *
     * **修掉的**：日志文件名与实例目录名现在用**同一个**折叠函数
     * （[DshPaths.safeInstanceDirName]），所以两者**不可能再分叉**。
     * 可断言的不变量：`instanceLogFile(id).name` 去掉 `instance-` / `.log`
     * **恒等于** `DshPaths.instanceDir(id).name`。
     *
     * **没修的**：那个函数本身**不单射**。`a\b` 与 `a_b` 是两个不同且**都可达**的目录名
     * （Android/Linux 内核只禁止文件名里的 `/` 与 NUL，反斜杠是合法普通字符），
     * 但都折成 `a_b`。所以 SP-05 的效果是**把日志的碰撞降到与目录同等级**，
     * 而不是消灭碰撞 —— 日志这一层**不可能**单独消灭它，那要改折叠规则本身
     * （改它等于改所有已存在目录名的解析结果，是破坏性迁移）。
     * ⇒ 残留缺陷已单独登记：`docs/BUGLEDGER.md` 的「SP-05 修复中暴露的新缺陷（round43）
     *   —— `safeInstanceDirName` 在可达目录名上**不单射**」，**不在 SP-05 范围**。
     *
     * 收编来的正常 id **逐字节等于目录名**（`File.name` 永不含 `/`、NUL ⇒ `safeInstanceDirName`
     * 对其恒等），`buildId` 生成的 `inst-<ms>-<seq>-<hex>` 也是该函数的不动点 ——
     * 这两类 id 不受上述缺陷影响。
     *
     * ## 代价（已评估，接受）
     *
     * 含空格/点/括号的 id 会得到含这些字符的文件名（如 `instance-my app.log`）。这是**与目录名一致**
     * 的必然结果：想折掉它们就必须连目录名一起折，而那会打断 `id ↔ 目录` 的 identity 映射
     * （`DshInstances` / `DshInstaller` / `DshRuntime` 全按 id 拼路径）。中文本来就在文件名里
     * （`Char.isLetterOrDigit('中') == true`，旧白名单也不折叠它），不是新增风险。
     * 另：日志文件名**没有读取方**（只有 [appendToFile] / [rotateIfTooBig] 两处写 IO），
     * 所以改名的实际影响仅是 `logs/` 里多一个旧名字的孤儿文件。
     *
     * ⚠️ 同族既存问题（不在 SP-05 范围）：文件名长度不设上界，≥243 字节的目录名会撞 `NAME_MAX`。
     */
    fun instanceLogFile(instanceId: String): File {
        // 未初始化（[DshPaths.loadPaths] 还没跑）时不能崩：正常流程下 Application 启动时就已就绪，
        // 走到兜底分支只可能是单测或异常时序，此时落到临时目录比抛异常好。
        // 注意 [DshPaths.safeInstanceDirName] 只读入参、不碰任何 lateinit 字段，两个分支都不会抛。
        val logDir = runCatching { File(DshPaths.LOG_FILE).parentFile }.getOrNull()
            ?: File(System.getProperty("java.io.tmpdir") ?: ".", "dsh/logs")
        return File(logDir, "instance-${DshPaths.safeInstanceDirName(instanceId)}.log")
    }

    /**
     * 把敏感 token 从日志里抹掉。
     * dsh 启动行形如：`dsh web: http://127.0.0.1:3080/?token=XXXX (LAN: ...)`
     *
     * 两条通路：
     * 1. **结构化**：[tokenRegex] 抹掉任何 `token=<值>` —— 与是否注册过无关、与长度无关，
     *    这条是主力（URL 里的 token 一律打码）。
     * 2. **兜底**：已注册串的全文替换，管住"token 以别的形态出现"（例如 dsh 把 token
     *    单独打成一行、或写进 cookie 调试输出）。
     *
     * ★ R34：第 2 条的**代价现在是常数上界**，不再是攻击者可控的 O(注册条数)。
     *   [registeredSecrets] 被 [MAX_SECRETS] 钉死（且每串不超过 [MAX_SECRET_LENGTH]），
     *   所以每行的 `replace` 次数 ≤ 16、扫描总长 ≤ 4 KB，与 rootfs 往 `/tmp` 灌多少行无关。
     *   修复前红队实测 10 万条注册后是 3.429 ms/行 —— 那是**永久**的 CPU 税。
     */
    fun sanitize(line: String): String {
        var out = tokenRegex.replace(line) { m -> "${m.groupValues[1]}***" }
        for (t in registeredSecrets) out = out.replace(t, "***")
        return out
    }

    /**
     * 注册一个需要脱敏的密钥（启动 token / API key）。
     *
     * ★ R33：**必须挡掉过短的串**。
     *
     * [sanitize] 对每个已注册串做无差别 `out.replace(t, "***")` —— 注册一个单字符
     * （例如 `a`）之后，此后该进程里**任何**含 `a` 的行都会被打码：日志彻底失去可读性。
     *
     * 这条路径是真实可达的：`onProcessLine` 从**任意一行输出**里取 `?token=` 的值并直接调本函数。
     * rootfs 内可写的 `/tmp` 里追加一行 `dsh web: https://evil.example:443/?token=a`，
     * 就能让此后整个进程的日志被打成马赛克 —— 一个"让排障失效"的开关。
     * 而 [registeredSecrets] 是进程级的，`clear()` 不清它，所以**一次污染永久生效**。
     *
     * 阈值取 [MIN_SECRET_LENGTH]：dsh 的真实 token 是 base64url 的 43 字符
     * （实测 `et5w4-xSCJpP95w98yyXZOJOxkn-EOz1JAyS8T6eKqU`），
     * 取 8 既能挡住单字符/双字符的误伤，又给"短 token 的平台"留足余量。
     *
     * 注意这**不影响** `token=` 那条正则通路（[sanitize] 第一行）——
     * URL 里的 token 是结构化的，无论长短一律打码，不依赖本阈值。
     *
     * ## ★ R34：四道门（顺序有讲究）
     * 1. **长度下界** [MIN_SECRET_LENGTH]（R33）—— 挡单字符把日志变马赛克；
     * 2. **长度上界** [MAX_SECRET_LENGTH] —— 挡"一条 20 万字串让每行都变慢"；
     * 3. **形状** [TOKEN_SHAPE_RE] —— 只收 token 形状的串（缺陷 2 完备性论证的基础）；
     * 4. **保护词表** [protectedLogVocabulary] —— 挡掉会摧毁协约行/归因判据的串（缺陷 2 本体）。
     *
     * 之后才进**容量**检查（[MAX_SECRETS]，超限**拒绝新增**，不淘汰）。
     *
     * 有一件事**刻意不做**：不论登记成败，都**不**往日志总线写任何东西。
     * 本函数会被攻击者可控的输出以每秒上千次的频率触发，若每次拒绝都记一行，
     * 拒绝日志本身就变成新的放大器（红队第二轮就能拿它做 DoS）。诊断信息只经
     * [rejectedSecretCountForTest] 暴露给测试。
     */
    fun registerSecret(secret: String) {
        if (secret.length < MIN_SECRET_LENGTH) return
        if (secret.length > MAX_SECRET_LENGTH) {
            rejectedSecretCount.incrementAndGet()
            return
        }
        if (!TOKEN_SHAPE_RE.matches(secret)) {
            rejectedSecretCount.incrementAndGet()
            return
        }
        // 缺陷 2：secret 是某个协约词的片段 → 注册它会让协约行被打码、归因静默失效
        if (protectedLogVocabulary.any { it.contains(secret) }) {
            rejectedSecretCount.incrementAndGet()
            return
        }
        // 容量门：copy-add 必须原子，否则并发注册能顶破上限（见 [secretLock]）
        synchronized(secretLock) {
            if (secret in registeredSecrets) return // 幂等：同一 token 反复注册不占额度
            if (registeredSecrets.size >= MAX_SECRETS) {
                rejectedSecretCount.incrementAndGet()
                // ★ 不淘汰（见 [MAX_SECRETS]）：踢掉旧的 = 把已经漏过的真 token 变回明文
                return
            }
            registeredSecrets += secret
        }
        // 同时注册给 FCL 的 logger，避免经由 FCL 自身日志路径泄漏。
        // ★ R34：**放在容量门之后** —— 那个 Java 侧数组是 `Arrays.copyOf` 的 O(n²) 追加
        //   （红队 `RegisterCost`：5 万条 13.1 s），不设防就等于把 DoS 从本类搬到了隔壁。
        //   放进本类的上限之后，它最多也只收 [MAX_SECRETS] 条。
        runCatching { com.tungsten.fclcore.util.Logging.registerAccessToken(secret) }
    }

    /** 测试 seam：当前已注册的 secret 条数（验证上限真的生效）。 */
    @JvmStatic
    fun registeredSecretCountForTest(): Int = registeredSecrets.size

    /** 测试 seam：被任一道门（含容量门）挡掉的注册次数。 */
    @JvmStatic
    fun rejectedSecretCountForTest(): Int = rejectedSecretCount.get()

    /** 测试 seam：某个串当前是否在脱敏集合里（不暴露集合本身，避免调用方绕过 [secretLock]）。 */
    @JvmStatic
    fun isSecretRegisteredForTest(secret: String): Boolean = secret in registeredSecrets

    // --- 内部：入总线的唯一加工口 -------------------------------------------

    /**
     * 行进入两条流的**唯一加工口**：先脱敏、再打时间戳。
     *
     * ★ 为什么收敛成一个函数：脱敏必须"进总线时做一次"才可靠 —— 只要 [append] / [appendFor]
     *   是仅有的两个入口，且都走这里，就不存在"某条流漏了脱敏"的可能（实例流是新增的，
     *   最怕的就是新路径忘了脱敏，把 token 写进 instance-*.log）。
     *   顺带保证同一行在两条流里是**同一个字符串**（含同一个时间戳），对不上号就没法排障。
     */
    private fun stamp(line: String): String =
        "${timeFormatter().format(Date())}  ${escapeLineBreaks(sanitize(line))}"

    /**
     * ★ F4（R31 / `slice-03-security-F4`）：把行内换行折成可见的 `\n` / `\r` **字面量**。
     *
     * 为什么必须在 [stamp]（唯一加工口）做、且不能用"删掉"：落盘是
     * `joinToString("\n", postfix = "\n")`（[appendToFile]），**行边界由 `\n` 决定**。一条被采信的
     * 进程输出里带 `\n` 时，原样入库会让它变成多条独立行、各自带自己的时间戳 —— 伪造行随后被
     * [com.dsh.core.DshRuntime] 的失败归因当真（`recentLogTail` 的 `reason=port-in-use` /
     * `EADDRINUSE` 判定、[looksLikeSeccompTrouble] 的 seccomp/ptrace 判定）。折成字面量而不是删掉：
     * 排障时要能看出"这行原本带换行"，删掉会静默改变内容。
     *
     * ⚠️ 与 `R31/BL-238`（[rotateIfTooBig] 的按字节裁剪）无交集：本函数只处理**字符串内容**，
     *   不涉及文件字节/轮转/`fileIoLock`。
     */
    private fun escapeLineBreaks(line: String): String =
        if (line.indexOf('\n') < 0 && line.indexOf('\r') < 0) line
        else line.replace("\r\n", "\\n").replace('\r', ' ').replace("\n", "\\n")

    // --- K27：级别判定（口径迁自 DshLogsUI）-----------------------------------

    /**
     * K27: log level of one line — **口径迁自 DshLogsUI，语义等价由测试钉住**
     * （`DshLogBusLevelTest` 的三个样例行逐条对照 UI 正则）。
     *
     * 与 DshLogsUI 现状的对照（逐字照抄其 companion object 的 `ERROR_RE` / `WARN_RE`，
     * 判序照抄其 `matches()`；⚠️ 这些正则**禁止**在此处"顺手优化"——一改口径就分叉了）：
     * - `ERROR_RE.containsMatchIn(line)` → [LogLevel.ERROR]（UI: `matches` 的 Filter.ERROR 判据）；
     * - 否则 `WARN_RE.containsMatchIn(line)` → [LogLevel.WARN]（UI: Filter.WARN = ERROR || WARN）；
     * - 其余 → [LogLevel.INFO]。
     *
     * 注意 UI 的 `levelOf` 还有一档 OK_RE（纯颜色用途），本枚举**刻意不设 OK 档**：
     * 过滤集（ALL/WARN/ERROR 三个档位）不依赖 OK_RE，命中 OK 的行在过滤语义上就是普通行（INFO）；
     * 行颜色仍由 UI 自己决定，本函数不接管颜色判定。
     */
    fun classifyLevel(line: String): LogLevel = when {
        // ★ R49/B-3：沙箱探测的**降级证据**显式提升为 ERROR（见 [isSandboxProbeDegraded]）。
        //   放在最前：它必须压过 ERROR_RE / WARN_RE 的文本判级，而不是"碰巧命中某个词"。
        isSandboxProbeDegraded(line) -> LogLevel.ERROR
        ERROR_RE.containsMatchIn(line) -> LogLevel.ERROR
        WARN_RE.containsMatchIn(line) -> LogLevel.WARN
        else -> LogLevel.INFO
    }

    /**
     * ★ R49 / B-3：沙箱探测里"沙箱不可用"的证据行必须判 ERROR。
     *
     * ## 缺陷本体（实测，见 `docs/reports/round49/agents/RELIABILITY.md` §4.3 / P1-7）
     * `start-dsh.sh` 的探测输出形如 `[start-dsh] sandbox-probe …`，其中**最关键的两行**是：
     * - `sandbox-probe bwrap=absent` —— 一级证据（bwrap 根本不存在）；
     * - `sandbox-probe landlock=UNUSABLE (kernel 4.14.190; …)` —— 二级证据。
     * 两行都**不含** [ERROR_RE] 的任何关键词（`absent`/`UNUSABLE` 都不在词表里）⇒ 判 INFO ⇒
     * 日志页的「警告及以上」/「仅错误」两个档位**完全看不见**。而本机（Android 内核 4.14 <
     * Landlock 要求的 5.13）是**必现**的：dsh 的沙箱链 `bwrap → landlock` 两级全挂，
     * `workspace-write` 模式下所有命令被拒。用户看到的是"每条命令都报沙箱错误"，
     * 而**唯一能解释这件事的日志行被过滤档位藏掉了**。
     *
     * ## 为什么是"前缀约束的例外"而不是"往 [ERROR_RE] 里加词"
     * `absent` 是个通用词（`file absent, using default` 这类正常行满地都是），加进 [ERROR_RE]
     * 会把一大批正常行染红 —— 假错误比没有错误更贵（排障时会被引去查一个不存在的问题）。
     * 所以判据是**两个条件同时成立**：
     * 1. 行里出现探测器的固定标签 [SANDBOX_PROBE_TAG]（`sandbox-probe`，脚本每条探测行都带）；
     * 2. 行内命中 [SANDBOX_PROBE_BAD_RE]（`absent` / `UNUSABLE` / `DENIED`，大小写不敏感）。
     *
     * ## 为什么这三个词而不是全部
     * [SANDBOX_PROBE_BAD_RE] 只收"这条路**已经断了**"的三种断言。同组里
     * `landlock=launcher-not-found` / `stat-FDCWD=BROKEN` / `tracerpid=…` 之类是**细节诊断**，
     * 保留 INFO —— 把它们也升成 ERROR 会让"仅错误"档位里一半是探测细节，真正的
     * `bwrap=absent` 反而被稀释（本修复要的正是"一眼看见沙箱挂了"）。
     *
     * ⚠️ 这条例外**不改变** [ERROR_RE] 本体：词表仍是 DshLogsUI 迁来的那一份，语义等价
     * 测试照旧成立（UI 已改为只消费快照预计算的级别，`grep ERROR_RE` 全仓只有本文件一处）。
     */
    private fun isSandboxProbeDegraded(line: String): Boolean =
        line.contains(SANDBOX_PROBE_TAG) && SANDBOX_PROBE_BAD_RE.containsMatchIn(line)

    /** 探测器标签（`start-dsh.sh` 每条探测行都以 `[start-dsh] sandbox-probe ` 起头） */
    private const val SANDBOX_PROBE_TAG = "sandbox-probe"

    /**
     * ★ R49/B-3：探测行里的"已断"断言。大小写不敏感（脚本现在写 `UNUSABLE`/`DENIED`，
     * 未来改成小写不该让这条修复静默失效）。
     */
    private val SANDBOX_PROBE_BAD_RE = Regex("""absent|UNUSABLE|DENIED""", RegexOption.IGNORE_CASE)

    /**
     * K27: 以下两个正则**逐字照抄** DshLogsUI 的 `ERROR_RE` / `WARN_RE`（含 IGNORE_CASE），
     * 是"口径等价"的物理载体 —— 修改它们等于单方面改变 UI 的过滤语义。
     *
     * ★ R49/B-3 补充：上面那条 `sandbox-probe` 例外**刻意不写进本正则**（理由见
     * [isSandboxProbeDegraded]）。本词表仍与迁入时逐字一致 —— `DshLogBusLevelTest.errorKeywordsAllClassifyAsError`
     * 的 11 个关键词断言照旧钉着它。
     */
    private val ERROR_RE =
        Regex("""error|failed|failure|fatal|exception|denied|错误|失败|异常|拒绝|不可用""", RegexOption.IGNORE_CASE)
    private val WARN_RE = Regex("""warn|警告|注意""", RegexOption.IGNORE_CASE)

    /** 写全局缓冲（调用方必须持有 [lock]） */
    private fun appendGlobalLocked(stamped: String, level: LogLevel) {
        buffer += stamped
        levels += level
        pendingForFile += stamped
        trimLocked(buffer, levels)
        dirty = true
    }

    /**
     * 环形裁剪：一次砍掉超出部分。
     * （`subList().clear()` 是单次数组搬移 O(移除数)，不是逐个 `removeAt(0)` 的 O(n²)；
     * 2000 行规模下也不会每行都搬移整表。）
     *
     * K27: 同步裁剪 [levels] —— 行缓冲与级别数组**只允许一起动**，这是不变式的维护点之一。
     * 两者长度恒相等（每个入缓冲点都成对 append），所以按同一区间 `subList().clear()` 即可。
     */
    private fun trimLocked(buf: ArrayList<String>, lv: ArrayList<LogLevel>) {
        if (buf.size > MAX_LINES) {
            val remove = buf.size - MAX_LINES
            buf.subList(0, remove).clear()
            lv.subList(0, remove).clear()
        }
    }

    /**
     * Get or create an instance's stream, enforcing the [MAX_STREAMS] cap (K10).
     *
     * Reading an existing entry via `get` also bumps its recency (the map is access-ordered), so
     * actively-written streams stay; only idle ones are candidates for eviction. The caller must
     * hold [lock].
     */
    private fun streamFor(instanceId: String): InstanceStream {
        streams[instanceId]?.let { return it } // access touch (LRU bump) + fast path
        val created = InstanceStream()
        // ★ SP-07：这条 id 若之前被 LRU 淘汰过，**接着它最后一次发布的 revision 数下去** ——
        //   而不是从 0 重来。消费侧用 `snap.revision == lastRevision` 做同帧早退
        //   （DshLogsUI:417 / DshWebViewActivity:706），revision 回退会让判据失效、界面塌成
        //   重建后那几行（详见 [evictedRevisions]）。
        //   同时把初值直接设成 `Snapshot(rev, empty)`：早退判据成立 ⇒ 已经显示着旧内容的
        //   UI **保持**旧内容不动（宁可是"陈旧但完整"，也不要"新但残缺"）；
        //   万一让 UI 拿到 `Snapshot(0, empty)`，它反而会立刻把整页刷成空白。
        val remembered = evictedRevisions.remove(instanceId)
        if (remembered != null && remembered > 0) {
            created.revision = remembered
            created.state.value = Snapshot(remembered, emptyList())
        }
        streams[instanceId] = created
        if (streams.size > MAX_STREAMS) evictLruStreamsLocked(keep = instanceId)
        return created
    }

    /**
     * Evict least-recently-used streams until the map is back within [MAX_STREAMS]. Never evicts
     * [keep] (the entry we just created/touched). Caller must hold [lock].
     */
    private fun evictLruStreamsLocked(keep: String) {
        val it = streams.entries.iterator()
        while (streams.size > MAX_STREAMS && it.hasNext()) {
            val e = it.next()
            if (e.key == keep) continue
            // ★ SP-07：**先收尾，再移除**。淘汰绝不能"顺手把还没落盘的行一起丢掉"，
            //   收尾内容见 [evictStreamLocked]（待落盘行入队 + 记住 revision）。
            //   顺序很关键：它要读 `st.pendingForFile` 和 `fileFor(id)`，两者都挂在
            //   即将被删的对象/缓存上。
            evictStreamLocked(e.key, e.value)
            it.remove()
            instanceFiles.remove(e.key)
        }
    }

    /**
     * ★ SP-07：淘汰一条流之前的收尾 —— **把攒在 `pendingForFile` 里、尚未成批入队的行接进
     * [fileWriteQueue]**，并记下它的 revision。
     *
     * ## 为什么必须做（缺陷本体）
     * `pendingForFile` 是"这一轮新增、还没落盘"的缓冲（全局与每实例各一份）：行先攒在这里，
     * 等 [flush] 才成批搬进 [fileWriteQueue]（最坏要等一个 [FLUSH_INTERVAL_MS] = 200ms）。
     * 而淘汰是由**另一个 id 的 append** 触发的（[streamFor] 在 append 热路径上），完全可能
     * 落在这 200ms 窗口里。`InstanceStream` 对象被 `it.remove()` 之后，那些行：
     * - 不在内存缓冲里（对象没了），也不
     * - 不在文件里（没有任何人写它）
     * ⇒ **静默丢失**。原来 [FileWrite.stream] 的 KDoc 只讨论了"已入队批次"被淘汰（文件副本丢了、
     * 行还在内存环里），**这一支没被覆盖，而它后果更重：行本身没了，连内存里都没有。**
     *
     * ## 为什么是"入队"而不是"同步写盘"
     * 本函数在 [appendFor] / [snapshotFor] 的调用栈上（每行日志都走），**不能有同步磁盘 IO**：
     * 慢 eMMC / 磁盘满时那会把所有实例的日志生产一起卡住，而 `fileIoLock` 本来就是全实例共用
     * 的一把锁。入队只取一次 [fileQueueLock] 的极短临界区（纯内存 addLast，无 IO），真正的写
     * 仍由 [drainFileWrites] 的唯一排空者按 FIFO 做 —— 与既有落盘路径同一形状，行序仍由
     * seq/FIFO 保证（这批的 seq 比后续批小，所以它一定先写）。
     *
     * ## revision
     * 见 [evictedRevisions]：记下最后一次发布的 revision，重建时接着数，不让它回退。
     * 有界（[MAX_EVICTED_REVISIONS] 条，FIFO 挤掉最老的）—— 不能因为"记住"而重新引入一个
     * 随实例 churn 无限增长的 map，那正是 [MAX_STREAMS] 要防的东西。
     *
     * 调用方必须持有 [lock]。
     */
    private fun evictStreamLocked(id: String, st: InstanceStream) {
        // 1) 记住 revision（先做：即使没有待落盘行，这条也要记）
        if (st.revision > 0) {
            evictedRevisions[id] = st.revision
            while (evictedRevisions.size > MAX_EVICTED_REVISIONS) {
                val oldest = evictedRevisions.keys.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                } else {
                    break
                }
            }
        }
        // 2) 待落盘行：接到队列尾部（不写盘，见上面的理由）
        if (st.pendingForFile.isEmpty()) return
        // 与 [flush] 完全同一条判据：全局日志未绑定 = 路径系统未就绪，此时实例路径只能落到
        // 临时目录兜底，写它没有意义（真机上两者同一时刻就绪）。
        if (logFile == null) return
        val batch = ArrayList(st.pendingForFile)
        st.pendingForFile.clear()
        // fileFor(id) 必须在 instanceFiles.remove 之前调用（惰性推导 + 缓存）
        enqueueFileWrite(FileWrite(fileFor(id), batch, st, id, ++fileWriteSeq))
        // 队列里现在有东西了，得保证有人来排空：appendFor 的调用方随后会自己 scheduleFlush，
        // 但 snapshotFor 那条路径不会 —— 漏了这一下，这批行会一直躺在内存队列里（不丢，
        // 但要等下一次任意 flush 才落盘）。scheduleFlush 自身幂等（flushScheduled 门），
        // 且 synchronized 可重入，这里在锁内调用是安全的。
        scheduleFlush()
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

    // --- 内部：合并刷新 -----------------------------------------------------

    /**
     * Arm a merged flush ~[FLUSH_INTERVAL_MS] later, unless one is already pending.
     *
     * K09: the old guard was `flushJob?.isActive`, which stays true while the job is *running*
     * flush() — including its off-lock file IO. A line appended during that window set `dirty` but
     * armed no new job, so if no further append ever came it was never flushed to the snapshot or
     * to disk (silent log loss). We now gate on [flushScheduled], which [flush] clears the instant
     * it begins draining, and [flush] re-arms at its tail whenever anything is still dirty.
     */
    private fun scheduleFlush() {
        synchronized(lock) {
            if (flushScheduled) return
            flushScheduled = true
        }
        armFlushJob()
    }

    private fun armFlushJob() {
        scope.launch {
            delay(FLUSH_INTERVAL_MS)
            flush()
        }
    }

    /** 一次刷新里要追加到某个文件的一批行（把锁内算好的东西带出来，锁外只做 IO） */
    private class FileWrite(
        val file: File,
        val lines: List<String>,
        /**
         * BL-075: the per-instance stream this batch came from, or `null` for the global stream —
         * kept so a *failed* write can be requeued into that stream's own `pendingForFile` head
         * (retry must land in the stream the batch belongs to, not the other one). If the stream
         * was evicted (LRU) or removed in the meantime the requeue is skipped and only the file
         * copy of this batch is lost; the lines themselves still live in the in-memory ring.
         *
         * ★ SP-07 更正：上面"行还在内存环里"只对**已成批入队**的批次成立。淘汰发生在
         * `pendingForFile` **还没被 flush 搬进队列**之前时，行根本不在这个 `FileWrite` 里 ——
         * 它只活在即将被丢弃的 `InstanceStream` 对象上，`it.remove()` 之后**行本身就没了**。
         * 修法见 [evictStreamLocked]：淘汰前先把这批 `pendingForFile` 入队。
         */
        val stream: InstanceStream?,
        /** BL-075: instance id matching [stream] (null for the global stream) — requeue residency check */
        val instanceId: String?,
        /**
         * BL-079: batch sequence — assigned under [lock] at enqueue time (strictly increasing in
         * queue order). The write order guarantee comes from the [fileWriteQueue] FIFO; this field
         * is the *inspectable* ordering token (diagnostics + the FLUSH test asserts it is monotone
         * inside a file's written batches).
         */
        val seq: Long
    )

    /**
     * BL-079: enqueue one drained batch for the [drainFileWrites] single-writer path.
     * **Caller must hold [lock]** — that is what makes "insertion order == drain order == write
     * order" hold even when two flushes race (see the [fileWriteQueue] field docs).
     */
    private fun enqueueFileWrite(w: FileWrite) {
        fileQueueLock.lock()
        try {
            fileWriteQueue.addLast(w)
        } finally {
            fileQueueLock.unlock()
        }
    }

    /**
     * BL-079: drain [fileWriteQueue] — one batch at a time, authored by exactly one thread.
     *
     * Every batch write still serializes on [fileIoLock] (K11); the queue adds the missing piece:
     * *which* batch goes first when concurrent flushes drained overlapping-time ranges. With the
     * FIFO, per file the on-disk line order is the queue order — no more interleaved inversion of
     * "later flush's batch written before earlier flush's batch" (BL-079).
     *
     * Strength: on return, every batch enqueued before the call is on disk. If another thread is
     * already draining, this caller parks on [fileQueueIdle] (the active drainer drains our batch
     * too — FIFO) instead of returning with the batch unwritten; when it wakes, it claims the
     * (usually empty) queue, drains, and returns. The claim/release/signal all happen under
     * [fileQueueLock] — no window where an enqueue could slip past a departing drainer unnoticed.
     */
    private fun drainFileWrites() {
        // Phase 1: claim the drainer slot. If another thread is draining, park on [fileQueueIdle]
        // (it drains our batch too — FIFO) and re-check when woken; the lock is released in every
        // exit path (including an interrupt from `await`) and is NOT held while claiming is done.
        fileQueueLock.lock()
        try {
            while (fileDraining) {
                try {
                    fileQueueIdle.await()
                } catch (e: InterruptedException) {
                    // Restore the flag and give up this drain pass: our batches were enqueued
                    // before this call, and the active drainer (the only reason we are waiting)
                    // still writes them; flush()'s tail re-arm keeps K09's no-strand guarantee.
                    Thread.currentThread().interrupt()
                    return
                }
            }
            fileDraining = true
        } finally {
            fileQueueLock.unlock()
        }
        // Phase 2: we own the drainer slot ([fileDraining] == true). The lock is only held while
        // touching the queue — never during a write (enqueuers must not stall on disk IO).
        try {
            while (true) {
                fileQueueLock.lock()
                val w = fileWriteQueue.pollFirst()
                if (w == null) {
                    // Release + wake inside the same critical section as the empty poll.
                    // signalAll (not signal): a waiter can consume a lone signal via the
                    // interrupt race in Phase 1 and leave the rest parked; waking all waiters
                    // is cheap here (concurrent flushes are a handful at most) and closes that
                    // lost-wake-up corner completely. Each woken waiter re-checks `fileDraining`
                    // and either claims (usually finding the queue empty) or waits again.
                    fileDraining = false
                    fileQueueIdle.signalAll()
                    fileQueueLock.unlock()
                    return
                }
                // Test-only record, under the same lock as the poll (see [drainedSeqs]).
                if (recordingDrainedSeqs) drainedSeqs.add(w.seq)
                fileQueueLock.unlock()
                appendToFile(w)
            }
        } catch (t: Throwable) {
            // Unexpected escape while claimed (e.g. OOM inside appendToFile's array copies; the
            // write path itself swallows IO failures). Release the claim + wake waiters so the
            // queue can't wedge; guard against still holding the lock in the worst case.
            if (fileQueueLock.isHeldByCurrentThread) fileQueueLock.unlock()
            fileQueueLock.lock()
            fileDraining = false
            fileQueueIdle.signalAll()
            fileQueueLock.unlock()
            throw t
        }
    }

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
     *   递增顺序就一致了。文件 IO 仍在锁外（appendToFile / rotateIfTooBig 走独立的 [fileIoLock]，
     *   不占生产者的 [lock]，见 K11）。
     *
     * ★ K09: `flushScheduled` is cleared at the top of the drain (so a concurrent append re-arms a
     *   fresh flush), and the tail re-checks dirtiness and re-arms if a line slipped in after the
     *   drain / during the off-lock IO — otherwise a trailing append with no successor would never
     *   be flushed.
     */
    private fun flush() {
        synchronized(lock) {
            // K09: we are draining now — let a subsequent append arm a fresh flush.
            flushScheduled = false
            val anyInstanceDirty = streams.values.any { it.dirty }
            // 两条流都没新内容：不发布任何快照、不入队任何批次（保持"无变化不刷"的语义），
            // 但仍执行下面的排空 —— 正常情况下队列为空、是一次 lock+空轮询的开销；
            // 万一上一轮排空被意外异常中断（批还留在队列里），这里就是它的自愈点
            // （flushNow 的"到这为止全落盘"语义也因此更强）。
            if (dirty || anyInstanceDirty) {
                // ★ 实例文件只在**全局日志已绑定**时才写。理由：全局日志所在的目录就是实例日志的
                //   目录（见 [instanceLogFile] 从 `DshPaths.LOG_FILE` 推导）；没绑定 = 路径系统还没
                //   就绪，这时实例路径只能落到临时目录兜底，往那儿写日志没有用处（不是 App 私有目录，
                //   UI 显示的"日志路径"也不指向它），只会在单测里凭空产生文件。
                //   真机上两者是同一个时刻就绪的（`DshPaths.loadPaths` 结束前就 [attachFile]），
                //   所以不影响行为。BL-079 后这行读取搬进 [lock]：只有锁内才能把"绑定状态 + 入队"
                //   做成一个原子动作（见 [enqueueFileWrite] 的注释）。
                val globalFile = logFile

                if (dirty) {
                    dirty = false
                    revision++
                    // K27: levels 与 lines 在同一把 [lock] 下一起防御性拷贝，快照两列表永远成对
                    _snapshot.value = Snapshot(revision, ArrayList(buffer), ArrayList(levels))
                    val batch = ArrayList(pendingForFile)
                    pendingForFile.clear()
                    // BL-079: 全局批先入队（与旧实现的落盘调用顺序一致），实例批随后
                    if (globalFile != null && batch.isNotEmpty()) {
                        enqueueFileWrite(FileWrite(globalFile, batch, null, null, ++fileWriteSeq))
                    }
                }

                if (anyInstanceDirty) {
                    for ((id, st) in streams) {
                        if (!st.dirty) continue
                        st.dirty = false
                        st.revision++
                        st.state.value = Snapshot(st.revision, ArrayList(st.buffer), ArrayList(st.levels))
                        val batch = ArrayList(st.pendingForFile)
                        st.pendingForFile.clear()
                        if (globalFile != null && batch.isNotEmpty()) {
                            enqueueFileWrite(FileWrite(fileFor(id), batch, st, id, ++fileWriteSeq))
                        }
                    }
                }
            }
        }

        // 出锁后串行排空：写仍在 [fileIoLock] 内逐批进行，队列保证同文件行序单调（BL-079）
        drainFileWrites()

        // K09: lines appended after we cleared `flushScheduled` above (during the drain tail or the
        // off-lock IO just done) only set `dirty`. With no further append to kick a new job they
        // would be stranded — never reaching the snapshot or the log file. Re-arm if still dirty.
        val rearm = synchronized(lock) {
            if ((dirty || streams.values.any { it.dirty }) && !flushScheduled) {
                flushScheduled = true
                true
            } else {
                false
            }
        }
        if (rearm) armFlushJob()

        // ★ R49/B-4：这一轮完整跑完了 ⇒ 清掉失败记忆。于是"故障消失后再犯"仍会**立刻**
        //   记一行（而不是被当成上一次那条的第 N 次重复、静默吞掉）。
        synchronized(flushFailureLock) {
            flushFailureDesc = null
            flushFailureRepeat = 0
        }
    }

    /**
     * 追加落盘 + 超限轮转。全局与实例文件共用这一份实现
     * （两条流各写各的文件，但"怎么写、怎么截断"必须完全一样，否则排障时两边行为不一致）。
     *
     * ★ BL-075：失败不再静默。`appendText` 抛异常（典型：磁盘满 ENOSPC）时：
     *   1. [lastFileWriteError] 记下文件与异常类（排障入口）；
     *   2. 往**内存流**插一条英文警告行（不经过 append/appendFor —— 那会把警告也排进
     *      pendingForFile 又落回失败的文件，见 [handleFileWriteFailure]）；
     *   3. 该批行**放回**对应流的 `pendingForFile` 头部，最多重试到 [FILE_WRITE_FAIL_LIMIT]
     *      次（回队会置脏，K09 的尾补排会以 ~200ms 间隔排下一次尝试；到上限后丢弃批次、
     *      只留警告，防止磁盘满时无限热循环）。
     *
     * @param stream 该批行所属的实例流；`null` = 全局流（重试要回对应流，不能串流）
     * @param instanceId [stream] 对应的实例 id（回队前校验流仍驻留用；全局流传 null）
     *
     * R15-A/BL-079 修正（2026-10-15 锐评）：失败重试改经 [requeueFileWrite] 从**队首**重入
     * [fileWriteQueue]（保留原 seq），不再回 `pendingForFile` —— 后者绕过 FIFO 序号，
     * 在"磁盘满 + 并发 flush"时会让重试批落在更新批之后，磁盘行序倒置。队列重入后：
     * 同文件的写入顺序恒等于 seq 顺序。重试在同一 drain 单里立即进行（队首被重新取到），
     * 由 [FILE_WRITE_FAIL_LIMIT] 封顶（3 次后丢弃），不会热循环。
     */
    private fun appendToFile(w: FileWrite) {
        val f = w.file
        // ★ R49 验收修正：rotateIfTooBig 自己吞异常，所以"append 成功"不等于"本轮完全成功"。
        //   单独拎出来，见下方 if 的判据与 rotateIfTooBig 的 KDoc。
        var rotatedButFailed = false
        val failure = runCatching {
            // K11: serialize file mutations on [fileIoLock], NOT the producer [lock]. flush() calls
            // this off-lock, but two overlapping flushes (e.g. flushNow() racing the scheduled job)
            // could otherwise interleave appends or append-vs-rotate on the same file. Producers
            // (append/appendFor) never touch this monitor, so an oversized log can't stall them.
            synchronized(fileIoLock) {
                f.parentFile?.mkdirs()
                f.appendText(w.lines.joinToString("\n", postfix = "\n"))
                // ★ 单次进程生命周期内日志可能无限增长（长驻 agent 能连续跑几天），
                // 光靠 attachFile 时截断是不够的：这里在每次落盘后检查一次，超限就只留尾部。
                // 实例文件同理：历史遗留的超大文件会在它第一次被写入时被裁到尾部。
                //
                // ★★ R49 验收修正（R2-RELIABILITY §3a，**真 bug**）：
                //   rotateIfTooBig **自己吞异常**（内部 `.getOrElse` 记进 lastFileWriteError），
                //   所以它失败时这块的 `exceptionOrNull()` 仍是 null ⇒ 下面走"写成功"分支
                //   ⇒ clearFileWriteErrorIfPath 把刚置的失败**在同一个调用栈里立刻清掉**。
                //   实测发射序列 `SET → NULL`。
                //
                //   而"日志文件超限 + 轮转读失败"恰恰是**最需要横幅**的场景
                //   （设备磁盘快满）。所以必须把 rotate 的结果单独拎出来：
                //   append 成功 ≠ 这一轮完全成功。
                val rotated = rotateIfTooBig(f)
                if (!rotated) rotatedButFailed = true
            }
        }.exceptionOrNull()

        if (failure == null && !rotatedButFailed) {
            // 写成功：该文件的连续失败计数清零（下一轮失败重新从 1 计；丢弃警告也可再发）
            synchronized(fileIoLock) {
                fileWriteFailCounts.remove(f.absolutePath)
                fileWriteDropWarned.remove(f.absolutePath)
            }
            // ★ R49/B-2：这次写成功就是"这个文件恢复可写了"的**证据** —— 清掉它挂着的
            //   失败状态（[logFileWriteFailure] 回 null），UI 横幅随之消失。
            //   放在计数清零**之后**且用同一个判据（按路径），两条语义一致：
            //   "同一路径的成功把这一轮失败收尾"。
            clearFileWriteErrorIfPath(f.absolutePath)
            return
        }
        // ★ rotatedButFailed 时 failure 通常是 null（append 成功了，是轮转自己吞了异常
        //   并已 noteFileWriteError 置位）。这里**不能再置一次**失败状态 —— 那会重复计数、
        //   让重试封顶提前触发。只需要**不清**：即跳过上面的 clearFileWriteErrorIfPath 分支。
        //   所以这一支直接 return，不调 handleFileWriteFailure。
        if (rotatedButFailed) return
        handleFileWriteFailure(w, failure ?: return)
    }

    /**
     * R15-A/BL-079：把失败批**从队首**放回文件写入队列（保序重试）。
     * 新一轮 drain 会最先取到它 —— 与"它后面还没写的新批"的相对顺序被保住（seq 仍最小）。
     * 由 [handleFileWriteFailure] 在 [lock] 内调用（与 enqueue 同一把生产者锁，见队列文档）。
     */
    private fun requeueFileWrite(w: FileWrite) {
        fileQueueLock.lock()
        try {
            fileWriteQueue.addFirst(w)
        } finally {
            fileQueueLock.unlock()
        }
    }

    /**
     * BL-075: react to a failed batch write — record it, surface it in the in-memory streams, and
     * requeue the batch for a **bounded** number of retries.
     *
     * - Recording: [lastFileWriteError] (sticky) always; plus one English warning line into the
     *   in-memory stream(s) on the first failure of a streak (and one more when the batch is
     *   finally dropped). Warnings are inserted directly into the buffers — NOT via
     *   [append]/[appendFor] — because those would queue the warning into `pendingForFile` and
     *   ship it right back into the failing file (and recurse on every retry).
     * - Retry: the batch goes back to the **head** of its stream's `pendingForFile` (head keeps
     *   the file's line order) and the stream is marked dirty, so the K09 tail re-arm schedules
     *   another attempt ~[FLUSH_INTERVAL_MS] away. After [FILE_WRITE_FAIL_LIMIT] consecutive
     *   failures the batch is dropped (warning only) — a disk-full phone must not retry forever.
     *   Any successful write to the file resets the streak; new batches are then attempted again.
     * - The requeue is skipped if the instance stream was evicted/removed meanwhile (no object to
     *   requeue into); the lines themselves stay in the in-memory ring regardless.
     */
    private fun handleFileWriteFailure(
        w: FileWrite,
        failure: Throwable
    ) {
        val f = w.file
        val lines = w.lines
        val stream = w.stream
        val instanceId = w.instanceId
        val path = f.absolutePath
        val desc = "log file write failed: $path (${failure.javaClass.simpleName}" +
            (failure.message?.let { ": $it" } ?: "") + ")"
        // ★ R49/B-2：走统一入口（同时置 [lastFileWriteError] 与 UI 订阅的那条流）
        noteFileWriteError(path, desc)

        val attempt = synchronized(fileIoLock) {
            val c = (fileWriteFailCounts[path] ?: 0) + 1
            fileWriteFailCounts[path] = c
            c
        }
        val drop = attempt >= FILE_WRITE_FAIL_LIMIT

        val warning = when {
            attempt == 1 -> "$desc; ${lines.size} line(s) kept for retry"
            // "giving up" is emitted once per failure streak, not once per dropped batch —
            // steady output on a full disk would otherwise add a warning every ~200ms (BL-075)
            drop && synchronized(fileIoLock) { fileWriteDropWarned.add(path) } ->
                "$desc; giving up on ${lines.size} buffered line(s) after $attempt failed attempts"
            else -> null
        }

        synchronized(lock) {
            // Residency check under the producer lock: the stream may have been evicted (LRU) or
            // removed since flush() drained it — then there is nothing to requeue/warn into.
            val st = stream?.takeIf { instanceId != null && streams[instanceId] === it }
            if (warning != null) {
                val stamped = stamp(warning)
                // K27: 告警行是**第二条入库路径**（不走 append/appendFor），级别同样在此预计算，
                // 并与行一起进、一起 trim —— 否则不变式在此破洞（这正是本任务的清单点 4）。
                val level = classifyLevel(stamped)
                if (st != null) {
                    // instance files: the instance view and the global timeline stay consistent
                    // (same contract as appendFor — the instance stream is a subset of global)
                    st.buffer += stamped
                    st.levels += level
                    trimLocked(st.buffer, st.levels)
                    st.dirty = true
                }
                buffer += stamped
                levels += level
                trimLocked(buffer, levels)
                dirty = true
            }
            if (!drop) {
                // R15-A/BL-079：保序重试 —— 从**队首**重入 FIFO（原 seq 保留），不再回
                // pendingForFile（那条路绕过序号，磁盘满+并发 flush 时会把行序写反）。
                // 流已淘汰（evicted）时文件副本仍应重试（它是"已发布快照"的落盘兜底），
                // 只是没有内存流可回 —— 与旧实现"evicted 则丢弃"相比更保守（保文件序不丢内容）。
                requeueFileWrite(w)
            }
        }
    }

    /**
     * 超过 [FILE_MAX_BYTES] 时只保留尾部一半（**按字节**切，并丢掉可能被切断的首行）。
     *
     * K11: serialized on [fileIoLock] (the file-IO monitor), **not** the producer [lock], so a
     * rotation never blocks append()/appendFor(). [appendToFile] already holds [fileIoLock] when it
     * calls this, so the nested `synchronized` is a reentrant no-op there; direct test callers take
     * it themselves.
     *
     * ★ BL-075：读失败不再静默 —— 记进 [lastFileWriteError] 后返回（文件保持超限，下次落盘
     *   再试轮转），而不是无声无息地让文件一直超限。
     *
     * ## ★ R31/BL-238：判据与裁剪必须同一个口径（都是字节）
     *
     * 旧实现用 `f.length()`（**字节**）判断超限，却用 `text.takeLast(keep)`（**UTF-16 字符**）裁剪。
     * 本项目的日志主体是中文（`[runtime] 启动 dsh …`），一个汉字 UTF-8 占 3 字节：
     * 保留 524288 **字符** 写出去是 **1.5 MiB**，仍然超过 1 MiB 上限。
     *
     * 更糟的是旧实现的第一个分支 `if (text.length <= keep) text`：
     * 文件若 1.4 MiB 但只有 47 万汉字（字符数 < keep），会**原样写回，一个字都不裁**。
     *
     * 后果不是"文件大一点"：轮转后仍 > 1 MiB ⇒ 下一次 flush 又触发全量 read+write，
     * 而 flush 间隔 200ms、整段都在 `fileIoLock` 内，**所有实例的日志共用这一把锁**。
     *
     * 现有测试 `rotateTruncatesOversizedFileToTail` 全程用 `"line-$n"` 纯 ASCII，
     * 1 字符 = 1 字节，两个口径恰好相等，把这个 bug 掩盖了。
     */
    /**
     * 超限就把文件裁到尾部一半。
     *
     * ★ R49 验收修正（R2-RELIABILITY §3a）：**返回是否真的成功**（含"不需要轮转"= true）。
     *
     * 为什么必须返回：这个函数**自己吞异常**（`getOrElse` 里 noteFileWriteError + return），
     * 于是调用方 [appendToFile] 看到的外层 `exceptionOrNull()` 是 null，
     * 会把"append 成功但轮转失败"当成完全成功，进而调用
     * [clearFileWriteErrorIfPath] 把刚置的失败**在同一个调用栈里立刻清掉**
     * （实测发射序列 SET → NULL）。
     *
     * 而"日志文件超限 + 轮转读失败"恰恰是最需要横幅的场景（磁盘快满）。
     * 所以把"轮转失败"与"append 失败"分开报：append 成功 ≠ 本轮完全成功。
     */
    private fun rotateIfTooBig(f: File): Boolean {
        if (f.length() <= FILE_MAX_BYTES) return true
        synchronized(fileIoLock) {
            if (f.length() <= FILE_MAX_BYTES) return true
            val keepBytes = (FILE_MAX_BYTES / 2).toInt()
            val bytes = runCatching { f.readBytes() }.getOrElse { e ->
                // ★ R49/B-2：走统一入口（同时置 UI 订阅的那条流）
                noteFileWriteError(
                    f.absolutePath,
                    "log file rotate failed: ${f.absolutePath} " +
                        "(${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""})"
                )
                return false
            }
            // 按**字节**取尾部。从 keepBytes 处对齐到一个 UTF-8 字符的起始字节
            // （continuation byte 形如 10xxxxxx），否则会切出半个汉字。
            var start = (bytes.size - keepBytes).coerceAtLeast(0)
            while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
            var tail = if (start == 0) bytes else bytes.copyOfRange(start, bytes.size)
            // 丢掉可能被切断的首行：从第一个 '\n' 之后开始（全是中文且无换行时保留原样）
            val newline = tail.indexOf('\n'.code.toByte())
            if (newline >= 0 && newline + 1 < tail.size) {
                tail = tail.copyOfRange(newline + 1, tail.size)
            }
            // 写回失败也算轮转失败（磁盘满时最可能在这里失败）。
            return runCatching { f.writeBytes(tail) }.isSuccess
        }
    }

    /** 退出/测试前强制刷一次 */
    fun flushNow() = flush()

    // --- 测试钩子（test-only seams；生产代码不要调用）---------------------------

    /**
     * Test hook: run the oversize rotation on [f] directly (no in-memory state, no attached file),
     * so the byte-based truncation can be asserted deterministically. Mirrors what [appendToFile]
     * does after each write (K11 regression anchor).
     */
    fun rotateFileForTest(f: File) = rotateIfTooBig(f)

    /**
     * Test hook: unbind the global log file and reset the BL-075 per-path failure state, restoring
     * the "nothing attached" singleton state so a failure-path test's bad file (and its stuck
     * failure counters) cannot leak into later tests in the same JVM. Mirrors [attachFile];
     * production code must not call this.
     */
    fun detachFileForTest() {
        logFile = null
        synchronized(fileIoLock) {
            fileWriteFailCounts.clear()
            fileWriteDropWarned.clear()
        }
        // ★ R49/B-2：失败状态也是"单例状态"，同样不许泄漏给下一个用例（否则下个用例
        //   一开始就看见一条本不属于它的横幅，接线断言会假绿）。
        resetFileWriteErrorForTest()
    }

    /** Test hook: current number of resident per-instance streams (K10 cap regression anchor). */
    fun streamCountForTest(): Int = synchronized(lock) { streams.size }

    /**
     * Test hook (BL-079): start recording the [FileWrite.seq] of every batch the writer actually
     * drains (in drain order). The recorded list is the inspectable proof that the queue is
     * drained in seq order (strictly increasing) — and thus that a file's written batch order is
     * the buffer order. Production code must not call this; read via [drainedSeqsForTest].
     */
    fun startRecordingDrainedSeqsForTest() {
        fileQueueLock.lock()
        try {
            drainedSeqs.clear()
            recordingDrainedSeqs = true
        } finally {
            fileQueueLock.unlock()
        }
    }

    /** Test hook (BL-079): seqs drained since [startRecordingDrainedSeqsForTest], in drain order. */
    fun drainedSeqsForTest(): List<Long> {
        fileQueueLock.lock()
        try {
            return ArrayList(drainedSeqs)
        } finally {
            fileQueueLock.unlock()
        }
    }

    /** Test hook (BL-079): stop recording drained seqs (recording is off by default). */
    fun stopRecordingDrainedSeqsForTest() {
        fileQueueLock.lock()
        try {
            recordingDrainedSeqs = false
        } finally {
            fileQueueLock.unlock()
        }
    }
}
