package com.dsh

import com.dsh.core.DshLogBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ★ R38 / SP-07：`MAX_STREAMS` LRU 淘汰**丢掉未落盘行**的回归守卫。
 *
 * ## 缺陷
 *
 * `appendFor` 把行攒进 `InstanceStream.pendingForFile`，等一次 flush（最坏
 * `FLUSH_INTERVAL_MS = 200ms` 之后）才成批搬进 `fileWriteQueue`。而淘汰是由**另一个 id 的
 * append** 触发的（[DshLogBus] 的 `streamFor` 在 append 热路径上，见 `DshLogBus.kt` 的
 * `evictLruStreamsLocked`），完全可能落在这 200ms 窗口里。淘汰把整个 `InstanceStream` 对象
 * `remove` 掉之后，攒着的那些行：
 * - 不在内存缓冲里（对象没了），
 * - 也不在文件里（没有任何人写它），
 * ⇒ **静默丢失**。
 *
 * 原有的 `FileWrite.stream` KDoc 只讨论了"**已成批入队**的批次被淘汰"（文件副本丢了、行还在
 * 内存环里），**这一支（淘汰早于 flush）没被覆盖，而它后果更重：行连内存里都没有了。**
 *
 * ## 修法（见 `DshLogBus.evictStreamLocked`）
 *
 * 淘汰前先把 `pendingForFile` 接到 `fileWriteQueue` 尾部（复用既有入队路径，**不做同步磁盘
 * IO** —— 这个方法在 append 热路径上），再 `remove`。revision 另见
 * [evictionKeepsRevisionMonotonic]。
 *
 * ## 断言口径
 *
 * 用公开 API 构造（33 个不同 id，各 append 一行）—— 不需要知道内部字段。落盘路径由
 * `DshLogBus.instanceLogFile(id)` 给出（与生产代码同一个推导），所以**不依赖** `DshPaths` 是否
 * 已初始化（JVM harness 里它通常是未初始化态，落到临时目录）。
 *
 * ⚠️ 每个用例用互不重复的实例 id（`DshLogBus` 是进程内单例，流在各测试间共享）。
 */
class R38EvictFlushTest {

    private var seq = 0
    private fun newId(tag: String): String = "inst-r38-$tag-${seq++}-${System.nanoTime()}"

    /**
     * **核心回归**：被 LRU 淘汰的实例，它攒在 `pendingForFile` 里（尚未 flush）的行必须落盘。
     *
     * ## 时序为什么要这么构造
     *
     * 缺陷只在"**淘汰早于 flush**"时发生。若淘汰和 flush 的先后由调度器决定，测试就会时好时坏。
     * 所以这里把两件事都钉死：
     * 1. 先 `appendFor(victim, …)` —— 行进 victim 的 `pendingForFile`（这是唯一被观察的行）；
     * 2. 再用 **`snapshotFor`**（不是 `appendFor`）陆续建 33 个填充流 —— `snapshotFor` 也走
     *    `streamFor` ⇒ 会触发淘汰，但它**不调 `scheduleFlush`**、也不标脏。
     *    于是"淘汰"由**我的线程同步完成**，而 flush 最早也要 victim 那次 append 排的
     *    ~200ms 任务才来 —— 淘汰必定先发生，窗口被消掉。
     *    （填充流给 33 个：既有流最多 32 条（别的用例留下的），victim 之后再加 33 条 ⇒
     *     survivor 是最后 32 条，victim 必然被挤出去，与残留状态无关。）
     * 3. 最后 `flushNow()` 强制排空队列 —— 它无条件排空 [DshLogBus] 的文件写入队列
     *    （不受 dirty 门控制），所以"队列里有没有那批行"就是本测试要断言的东西。
     *
     * 变异验证（去掉 `evictStreamLocked` 的入队）：本条变红 —— 见报告。
     */
    @Test
    fun evictedStreamsPendingLinesAreStillWrittenToFile() {
        val dir = Files.createTempDirectory("dsh-r38-evict").toFile()
        val globalFile = File(dir, "runtime.log")
        val previous = DshLogBus.attachedFilePathForTest
        try {
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            // 实例日志只在**全局日志已绑定**时才写（见 DshLogBus.flush：路径系统未就绪时实例
            // 路径只是临时目录兜底）。绑定一个临时全局文件，才算走生产同款路径。
            DshLogBus.attachFile(globalFile)

            val tag = "evict-${System.nanoTime()}"
            val victim = "inst-r38-$tag-victim"
            DshLogBus.appendFor(victim, "$tag-line-victim")

            // 33 个填充流：建流即触发淘汰，且不排 flush（见上面的时序说明）。
            repeat(33) { i -> DshLogBus.snapshotFor("inst-r38-$tag-fill-$i") }

            // 前置：淘汰真的发生了（否则这条测试什么都没验证）。
            assertEquals("上限必须压住驻留流数", 32, DshLogBus.streamCountForTest())
            assertEquals("victim 的流应已从内存消失", 0, DshLogBus.size(victim))
            assertEquals(
                "victim 的快照应是重建后的空流",
                0,
                DshLogBus.snapshotFor(victim).value.lines.size
            )

            DshLogBus.flushNow()

            val victimFile = DshLogBus.instanceLogFile(victim)
            assertTrue(
                "被淘汰实例那行必须仍在落盘文件里（淘汰前要把 pendingForFile 入队，不能静默丢行）: " +
                    "$victimFile",
                victimFile.exists() && victimFile.readText().contains("$tag-line-victim")
            )
        } finally {
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            previous?.let { DshLogBus.attachFile(File(it)) }
            dir.deleteRecursively()
        }
    }

    /**
     * 端到端（生产节奏）：33 个实例各 append 一行、**不手动 flush**，200ms 的合并刷新必须让
     * 全部 33 行都落盘 —— 包括被淘汰的那条。
     *
     * 这条不区分"淘汰在前还是 flush 在前"，只守住最终不丢行；核心时序由上面那条钉。
     */
    @Test
    fun allLinesReachDiskThroughMergedFlushEvenWhenStreamsAreEvicted() {
        val dir = Files.createTempDirectory("dsh-r38-evict-async").toFile()
        val globalFile = File(dir, "runtime.log")
        val previous = DshLogBus.attachedFilePathForTest
        try {
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            DshLogBus.attachFile(globalFile)

            val tag = "evictasync-${System.nanoTime()}"
            val ids = (0 until 33).map { "inst-r38-$tag-$it" }
            ids.forEachIndexed { i, id -> DshLogBus.appendFor(id, "$tag-line-$i") }

            val victimFile = DshLogBus.instanceLogFile(ids.first())
            assertTrue(
                "被淘汰流的行必须在合并刷新里自己落盘（不需要外部 flushNow）",
                awaitTrue(8000) {
                    victimFile.exists() && victimFile.readText().contains("$tag-line-0")
                }
            )
            // 其余实例也不丢行
            ids.drop(1).forEachIndexed { i, id ->
                val n = i + 1
                val f = DshLogBus.instanceLogFile(id)
                assertTrue(
                    "第 $n 个实例（$id）的行必须在它的文件里",
                    awaitTrue(8000) { f.exists() && f.readText().contains("$tag-line-$n") }
                )
            }
        } finally {
            DshLogBus.detachFileForTest()
            DshLogBus.clear()
            DshLogBus.flushNow()
            previous?.let { DshLogBus.attachFile(File(it)) }
            dir.deleteRecursively()
        }
    }

    /**
     * 淘汰重建后 **revision 不得回退**（消费侧 `snap.revision == lastRevision` 的单帧早退判据
     * 以此为前提，见 `DshLogsUI:417` / `DshWebViewActivity:706`）。
     *
     * 缺陷后果：revision 从 N 掉回 0 ⇒ 早退判据失效 ⇒ 用**重建后那条空流**重绘 ⇒ 用户眼前的
     * 历史一次性塌成"只有新流那几行"。修法：淘汰时记住最后一次发布的 revision，重建时接着数
     * （`DshLogBus.evictedRevisions`），并且重建瞬间发布的快照也**不带更低的 revision**。
     *
     * 断言只看**单调性**（不钉具体数值）：两次 flush ⇒ >= 2，重建后的快照与后续都不小于它。
     */
    @Test
    fun evictionKeepsRevisionMonotonic() {
        val tag = "rev-${System.nanoTime()}"
        val id = "inst-r38-$tag-keep"
        // 先把在途的合并刷新排干净，让下面的 append 序列落在一个全新的 200ms 窗口里 ——
        // 否则"别的用例排的那次 flush"可能在填充循环中途落地，把一批填充流的 revision 抬到 1，
        // 它们随后被淘汰时会占掉 [DshLogBus] 的有界 revision 记忆表（与本次断言无关）。
        DshLogBus.flushNow()
        DshLogBus.appendFor(id, "$tag-line-0")
        DshLogBus.flushNow()
        val rev1 = DshLogBus.snapshotFor(id).value.revision
        DshLogBus.appendFor(id, "$tag-line-1")
        DshLogBus.flushNow()
        val rev2 = DshLogBus.snapshotFor(id).value.revision
        assertTrue("前置：两次刷新后 revision 应递增（$rev1 -> $rev2）", rev2 > rev1)

        // 把它挤出容量：100 个更新鲜的 id。
        // 为什么是 100 而不是 33：`DshLogBus` 是进程内单例，跑全量时别的用例会留下最多 32 条
        // 驻留流，它们在 LRU 次序上排在本用例的 id **之前**。要保证"轮到"本用例的 id，淘汰次数
        // 必须超过残留条数 —— 100 条给足了余量（上限 32，恒成立），且全程不 poll
        // `size(id)`（那会 `get` 到本 id 并把它顶回 MRU 端，反而永远淘汰不到）。
        val fillers = (0 until 100).map { "inst-r38-$tag-fill-$it" }
        fillers.forEachIndexed { i, fid -> DshLogBus.appendFor(fid, "$tag-fill-$i") }
        DshLogBus.flushNow()
        assertEquals("前置：被淘汰", 0, DshLogBus.size(id))

        // 重建：不得低于淘汰前最后的 revision
        val rebuilt = DshLogBus.snapshotFor(id).value.revision
        assertTrue(
            "重建流的 revision（$rebuilt）不得低于淘汰前的 $rev2 —— 否则 UI 的单帧早退判据被打破",
            rebuilt >= rev2
        )

        // 再写一行、刷新：revision 继续严格递增，不回退
        DshLogBus.appendFor(id, "$tag-line-2")
        DshLogBus.flushNow()
        val rev3 = DshLogBus.snapshotFor(id).value.revision
        assertTrue("重建后第一次刷新必须严格大于重建时的 revision（$rebuilt -> $rev3）", rev3 > rebuilt)
        assertFalse(
            "重建后不应把 revision 归零",
            DshLogBus.snapshotFor(id).value.revision == 0L
        )
    }

    /** 有上限的轮询等待（异步合并刷新 ~200ms 一次）。 */
    private fun awaitTrue(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }
}
