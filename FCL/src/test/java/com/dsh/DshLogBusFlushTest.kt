package com.dsh

import com.dsh.core.DshLogBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

/**
 * DshLogBus 的"刷新 / 内存回收 / 轮转"JVM 单元测试（不需要真机/Android），对应 T-A3 的
 * **K09 / K10 / K11**。分流语义已由 [DshLogBusRoutingTest] 覆盖，这里只钉三条"会静默出问题"的：
 *
 * - **K09 flush 补刷**：append 的行最终必须进快照，即使它落在一次 flush 的尾部、后面再没有日志
 *   来触发新一轮刷新（否则这些行永不进快照、永不落盘 —— 静默丢日志）。
 * - **K10 内存回收**：[DshLogBus.removeStream] 能把某实例的驻留流删掉；流的数量有上限（LRU 淘汰），
 *   不会随"多建多删"无限增长。
 * - **K11 轮转**：超限文件被正确轮转（截断到尾部），轮转逻辑在搬出全局锁后仍然正确。
 *
 * ★ 每个用例用**互不重复**的实例 id（DshLogBus 是进程内单例，流在测试间共享）。
 * ★ K09 的两条"异步刷新"用例**故意不调** [DshLogBus.flushNow]，就是要验证"没有外部触发时也会自刷"；
 *   它们按真实的 ~200ms 合并刷新节奏，用有上限的轮询等待（在修好的代码上稳定通过）。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.DshLogBusFlushTest"
 */
class DshLogBusFlushTest {

    private var seq = 0
    private fun newId(tag: String): String = "inst-flushtest-$tag-${seq++}-${System.nanoTime()}"

    // --- K09：flush 补刷，行不被漏掉 ---------------------------------------

    /** 基线（守住本次重构）：flushNow 必须把到此为止 append 的所有行都排进快照。 */
    @Test
    fun flushNowDrainsAllAppendedLines() {
        val id = newId("drain")
        repeat(50) { DshLogBus.appendFor(id, "d-$it") }
        DshLogBus.flushNow()

        val lines = DshLogBus.snapshotFor(id).value.lines
        assertEquals("flushNow 后快照应含全部 50 行", 50, lines.size)
        assertTrue("最新的行必须在", lines.last().endsWith("d-49"))
    }

    /**
     * 单条 append 后**不手动 flush**，~200ms 的合并刷新也必须把它推进快照。
     * 守住重构后的异步调度链（scheduleFlush → delay → flush）没有断。
     */
    @Test
    fun scheduledFlushFiresWithoutManualFlush() {
        val id = newId("sched")
        DshLogBus.appendFor(id, "async-line")

        assertTrue(
            "append 后无需手动 flush，合并刷新也应在 ~200ms 内把行推进快照",
            awaitSnapshot(id) { lines -> lines.any { it.endsWith("async-line") } }
        )
    }

    /**
     * K09 核心回归：多线程并发 append，**不**手动 flush，所有行最终都必须进快照。
     *
     * 旧代码里 `scheduleFlush` 在 flush 正在跑（job active）时直接 return，于是"在一次 flush 执行
     * 期间 append、且其后再无 append"的行只置了 dirty、没有补排任务 —— 快照永远少这几行。
     * 修好后 flush 末尾会按 dirty 补排，最终计数必然追平。480 行 < 2000 行上限，不会被环形裁掉。
     */
    @Test
    fun concurrentAppendsAreAllEventuallyFlushedWithoutExtraKick() {
        val id = newId("k09")
        val threads = 8
        val perThread = 60
        val expected = threads * perThread
        val gate = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            Thread {
                gate.await()
                repeat(perThread) { i -> DshLogBus.appendFor(id, "k09-$t-$i") }
            }
        }
        workers.forEach { it.start() }
        gate.countDown()
        workers.forEach { it.join() }

        // 故意不 flushNow：K09 讲的就是"末尾的行没有后续触发也要被自刷"。
        assertTrue(
            "并发 append 的所有行最终都必须被自动刷新（K09：末尾不能漏刷）",
            awaitSnapshot(id) { it.size >= expected }
        )
        assertEquals(expected, DshLogBus.snapshotFor(id).value.lines.size)
    }

    // --- K10：内存回收（removeStream + 流数量上限 LRU 淘汰）------------------

    @Test
    fun removeStreamDropsInstanceStream() {
        val id = newId("remove")
        DshLogBus.appendFor(id, "line-before-remove")
        DshLogBus.flushNow()
        val before = DshLogBus.snapshotFor(id)
        assertEquals(1, DshLogBus.size(id))
        assertTrue(before.value.lines.any { it.contains("line-before-remove") })

        DshLogBus.removeStream(id)

        // 流被真正丢弃：行数归 0，且再次 snapshotFor 得到的是一条**全新**的空流（证明旧 buffer/
        // StateFlow 被释放，而不是藏起来）。
        assertEquals("removeStream 后该实例应无驻留行", 0, DshLogBus.size(id))
        val after = DshLogBus.snapshotFor(id)
        assertNotSame("removeStream 后应是一条全新的流", before, after)
        assertEquals(DshLogBus.Snapshot.EMPTY, after.value)
    }

    @Test
    fun removeStreamIsNoOpForUnknownId() {
        DshLogBus.removeStream(newId("never-created")) // 不得抛异常
    }

    /**
     * 流数量有上限：建远超上限（[DshLogBus] 内 MAX_STREAMS=32）的实例流后，驻留数量必须被压回上限，
     * 且淘汰的是**最久未用**的（刚写过的留，最早建的被淘汰）。证明"多建多删不无限涨"。
     */
    @Test
    fun streamCountIsCappedByEvictingLeastRecentlyUsed() {
        val tag = "cap-${System.nanoTime()}"
        val ids = (0 until 40).map { "inst-$tag-$it" } // 40 > 32（上限）
        ids.forEach { DshLogBus.appendFor(it, "x") }

        // 上限兜底：驻留流数被压回 ≤ 32，不随新建无限增长（K10 的核心）。
        assertTrue("流数量必须被上限压住", DshLogBus.streamCountForTest() <= 32)
        // LRU：最早建的那个（此后一直没再碰）必然被淘汰；最后建的那个还在。
        assertEquals("最久未用的流应被淘汰", 0, DshLogBus.size(ids.first()))
        assertEquals("最近建的流应保留", 1, DshLogBus.size(ids.last()))
    }

    // --- K11：超限文件轮转（搬出全局锁后仍正确截断到尾部）------------------

    @Test
    fun rotateTruncatesOversizedFileToTail() {
        val dir = Files.createTempDirectory("dsh-rotate").toFile()
        try {
            val f = File(dir, "instance-rotate.log")
            val sb = StringBuilder()
            var n = 0
            while (sb.length <= 1_300_000) { sb.append("line-").append(n++).append('\n') } // > 1MB
            f.writeText(sb.toString())
            assertTrue("前置：文件需超过 1MB", f.length() > 1L * 1024 * 1024)
            val newest = "line-${n - 1}"

            DshLogBus.rotateFileForTest(f)

            assertTrue("轮转后应截断到 1MB 以内", f.length() <= 1L * 1024 * 1024)
            assertTrue("轮转后仍需有内容", f.length() > 0)
            val tail = f.readText()
            assertTrue("应保留最新的行: $newest", tail.contains(newest))
            assertFalse("最老的行应被裁掉", tail.contains("line-0\n"))
            assertTrue("截断后应以完整行开头（丢掉被切断的半行）", tail.startsWith("line-"))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 有上限的轮询等待：异步合并刷新把快照推到满足 [predicate] 为止（在修好的代码上稳定满足）。 */
    private fun awaitSnapshot(
        id: String,
        timeoutMs: Long = 5000,
        predicate: (List<String>) -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(DshLogBus.snapshotFor(id).value.lines)) return true
            Thread.sleep(20)
        }
        return predicate(DshLogBus.snapshotFor(id).value.lines)
    }
}
