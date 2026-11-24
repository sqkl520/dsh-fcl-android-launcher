package com.dsh

import com.dsh.core.DshLogBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R49 验收（R2-RELIABILITY §3a）：**轮转失败被同一调用栈里的"写成功"立刻清掉**。
 *
 * ## 缺陷本体
 *
 * `appendToFile` 的结构是：
 * ```
 * val failure = runCatching {
 *     synchronized(fileIoLock) {
 *         f.appendText(...)
 *         rotateIfTooBig(f)        // ← 它自己吞异常，内部 noteFileWriteError 置位
 *     }
 * }.exceptionOrNull()          // ← rotate 失败时这里仍是 null
 * if (failure == null) {
 *     clearFileWriteErrorIfPath(...)   // ← 把刚置的失败清掉了
 * }
 * ```
 * 实测发射序列是 `SET → NULL`：横幅**永远不会出现**。
 *
 * 而"日志文件超过 1 MiB + 轮转读失败"恰恰是**最需要横幅**的场景 ——
 * 设备磁盘快满时，读一个 1 MiB 文件 OOM/失败的概率最高，而那正是"日志正在丢"的时刻。
 *
 * ## 修复
 *
 * `rotateIfTooBig` 改成返回 Boolean（含"不需要轮转"= true），调用方把它单独拎出来：
 * `if (failure == null && !rotatedButFailed) { 清状态 }`。
 * 并且旋转失败时**不再调** `handleFileWriteFailure` —— 那会重复计数、让重试封顶提前触发。
 */
class R49RotateFailureVisibilityTest {

    /**
     * ★ 核心：轮转失败时，可订阅的失败状态必须**留在那里**，不许被同一次调用清掉。
     *
     * 手法：造一个超过 [DshLogBus.FILE_MAX_BYTES] 的文件，让 `rotateIfTooBig` 真正进入，
     * 然后让它读失败 —— 用"文件存在但是目录"做不到（length() 会是 0），
     * 所以改用**削权**：Windows 上 `setReadable(false)` 对已关闭的文件有效（实测），
     * Linux 上等同理。若跑在无法削权的环境（root / Windows 某些配置），这条会**跳过前置**
     * 并如实说明 —— 不假装测过。
     */
    @Test
    fun rotateFailureIsNotClearedByTheSameSuccessfulAppend() {
        val dir = Files.createTempDirectory("dsh-r49-rotate").toFile()
        val f = File(dir, "big.log")
        val previous = DshLogBus.attachedFilePathForTest
        try {
            DshLogBus.detachFileForTest()
            DshLogBus.resetFileWriteErrorForTest()

            // 造一个超限文件：全 'a'，这样按字节裁也不会切坏 UTF-8
            // 1 MiB 是 DshLogBus.FILE_MAX_BYTES 的值（private，这里按字面量写并注明来源）
            val big = ByteArray((1L * 1024 * 1024 + 1024).toInt()) { (97).toByte() }
            f.writeBytes(big)

            // 削权让它读不了。若削不掉（root / 平台不支持），如实跳过而不是假装通过。
            val madeUnreadable = f.setReadable(false, false) && !f.canRead()
            if (!madeUnreadable) {
                println(
                    "[R49RotateFailureVisibilityTest] 跳过：本环境无法把文件削成不可读 " +
                        "(canRead=${f.canRead()})，无法构造轮转读失败。"
                )
                return
            }

            DshLogBus.attachFile(f)
            DshLogBus.append("r49-rotate-probe-${System.nanoTime()}")
            DshLogBus.flushNow()

            val afterFlush = DshLogBus.logFileWriteFailure.value
            assertNotNull(
                "★ 轮转读失败必须出现在可订阅的流上。若这里是 null，说明" +
                    "「append 成功」把轮转失败清掉了 —— 实测发射序列会是 SET → NULL，" +
                    "横幅永不出现，而「磁盘快满导致日志正在丢」正是最该让用户知道的场景。",
                afterFlush
            )
            assertTrue2(
                "描述里应带 rotate 或路径线索，便于排障。实际：<$afterFlush>",
                afterFlush!!.contains("rotate") || afterFlush.contains("big.log")
            )
        } finally {
            runCatching { f.setReadable(true, false) }
            DshLogBus.detachFileForTest()
            DshLogBus.resetFileWriteErrorForTest()
            runCatching { f.delete() }
            runCatching { dir.deleteRecursively() }
            // 恢复原附着点（别的用例可能依赖）
            if (previous != null) runCatching { DshLogBus.attachFile(File(previous)) }
        }
    }

    /**
     * 反面：**普通**写入（不触发轮转）成功后，失败状态必须仍然回 null。
     *
     * 这是防止修复过度：`rotatedButFailed` 的引入不能把"正常的恢复"也挡住。
     */
    @Test
    fun ordinarySuccessfulWriteStillClearsTheState() {
        val dir = Files.createTempDirectory("dsh-r49-rotate2").toFile()
        val small = File(dir, "small.log")
        val bad = File(dir, "bad.log")
        try {
            DshLogBus.detachFileForTest()
            DshLogBus.resetFileWriteErrorForTest()
            assertTrue("前置：bad 必须是目录才会写失败", bad.mkdirs())

            DshLogBus.attachFile(bad)
            DshLogBus.append("r49-rotate-fail-${System.nanoTime()}")
            DshLogBus.flushNow()
            assertNotNull("前置：这次必须先真的失败一次", DshLogBus.logFileWriteFailure.value)

            DshLogBus.detachFileForTest()
            DshLogBus.attachFile(small)
            DshLogBus.append("r49-rotate-ok-${System.nanoTime()}")
            DshLogBus.flushNow()

            assertEquals(
                "★ 普通写成功必须清掉失败状态（横幅随恢复消失）—— " +
                    "修复不能把这条也挡住",
                null, DshLogBus.logFileWriteFailure.value
            )
            assertFalse("前置：文件该被写出来", small.length() == 0L)
        } finally {
            DshLogBus.detachFileForTest()
            DshLogBus.resetFileWriteErrorForTest()
            runCatching { dir.deleteRecursively() }
        }
    }

    private fun assertTrue2(msg: String, cond: Boolean) =
        org.junit.Assert.assertTrue(msg, cond)
}
