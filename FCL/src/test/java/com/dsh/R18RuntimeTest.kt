package com.dsh

import com.dsh.core.ProcessUtil
import com.dsh.core.pidFileAllowsAdoptionWriteBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R18/W1-D 纯逻辑单测（BL-149a 轮转清理 / BL-158 CAS 回写判据 的可在 JVM 上钉死的核心）。
 *
 * 能测与不能测（同 DshRuntimeRaceTest / Fix4Test 的口径）：
 * - **能测**：`pruneStartLogs` 是纯文件逻辑（保 keep 份、7 天规则只管 keep 之外、recent 永不删、
 *   双目录合并、容错不抛）；`pidFileAllowsAdoptionWriteBack` 是回写 CAS 的判定核心（错一位就是
 *   "死 pid 覆盖新进程" 或 "该写的不写"）。二者 JVM 上可精确断言。
 * - **测不了**：`adoptOrphan` 的完整认领流程（需要真 proot 进程 + /proc + 端口 + Android Context）；
 *   BL-152 的任务终态上报（`startLocked` 的 catch 路径需要 Context/proot）；BL-149b 早退分支的
 *   CAS 删除是内联一行（`latest?.pid == meta.pid`）——上真机验证。
 *
 * ⚠️ 与并行 agent 的测试文件名隔离：本文件独立（R18RuntimeTest），不碰 Fix4Test 等既有文件。
 */
class R18RuntimeTest {

    // --- BL-149a：pruneStartLogs 轮转清理（明文 token 日志无上限残留的修复）---------

    /**
     * 主用例：合并两目录按 mtime 降序，**最新 keep 份永不删**；`drop(keep)` 的候选中
     * 只有 mtime 早于 7 天的才删（"keep 内永不按 7 天规则删除"）。
     *
     * 现场：tmp 5 份（都在 10 分钟内，模拟运行/近期输出）+ 实例目录 3 份（1 份最新 + 2 份 8/9 天前
     * —— 脚本 tmp 不可写时的回落落点）。keep=6 → 最新 6 份 = [实例目录最新 + tmp 5 份]，
     * drop 出的 2 份（8d/9d）都过 7 天线 → 删 2；keep 内那份 10 分钟文件不得因任何规则被删。
     */
    @Test
    fun pruneKeepsNewestAndDeletesOnlyOldBeyondKeep() {
        val tmp = Files.createTempDirectory("r18-tmp").toFile()
        val inst = Files.createTempDirectory("r18-inst").toFile()
        try {
            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000
            // tmp：5 份近期日志（10 分钟内），mtime 递减保证全序确定
            val tmpLogs = (0..4).map { i ->
                File(tmp, "dsh-start-90$i.log").apply {
                    writeText("dsh web: http://127.0.0.1:46501/?token=T$i\n")
                    setLastModified(now - i * 60_000L)
                }
            }
            // 实例目录：1 份最新（认领场景里"正在写"的那份）+ 2 份超 7 天的残留
            val newest = File(inst, "dsh-start-9999.log").apply {
                writeText("dsh web: http://127.0.0.1:46501/?token=LIVE\n")
                setLastModified(now)
            }
            val old8d = File(inst, "dsh-start-8888.log").apply {
                writeText("token=T_OLD8\n"); setLastModified(now - 8 * day)
            }
            val old9d = File(tmp, "dsh-start-7777.log").apply {
                writeText("token=T_OLD9\n"); setLastModified(now - 9 * day)
            }
            // 不匹配命名的文件：一根毫毛都不许动（30 天前也不动）
            val notMatching = listOf(
                File(tmp, "dsh-start-1.txt"), // 后缀不对
                File(tmp, "other.log"),       // 前缀不对
                File(inst, "dsh-start-notes") // 后缀不对
            ).onEach { it.writeText("keep me"); it.setLastModified(now - 30 * day) }

            val deleted = ProcessUtil.pruneStartLogs(tmp, inst)

            assertEquals("恰好删掉 keep 之外的 2 份超龄日志", 2, deleted)
            assertFalse("8 天前的实例目录残留应被删", old8d.exists())
            assertFalse("9 天前的 tmp 残留应被删", old9d.exists())
            assertTrue("最新（正在写）的那份必须保留", newest.exists())
            tmpLogs.forEach { assertTrue("近期日志不得被删：${it.name}", it.exists()) }
            notMatching.forEach { assertTrue("命名不匹配的文件不得被碰：${it.name}", it.exists()) }
        } finally {
            tmp.deleteRecursively()
            inst.deleteRecursively()
        }
    }

    /**
     * "recent（10 分钟内）永不删" 的直击用例：总数超 keep、且**超出 keep 的那两份本身是近期文件**
     * （例如应用短时间内多次启动留下的多份 10 分钟内日志）——7 天规则对它们不成立，一份都不删。
     */
    @Test
    fun pruneNeverDeletesRecentEvenBeyondKeep() {
        val tmp = Files.createTempDirectory("r18-tmp2").toFile()
        val inst = Files.createTempDirectory("r18-inst2").toFile()
        try {
            val now = System.currentTimeMillis()
            // 8 份近期（1 小时内）→ keep=6 时 drop 出 2 份"超 keep 但新"的文件
            (0..7).forEach { i ->
                File(tmp, "dsh-start-80$i.log").apply {
                    writeText("dsh web: http://127.0.0.1:46501/?token=R$i\n")
                    setLastModified(now - i * 5 * 60_000L) // 0/5/10/.../35 分钟前
                }
            }
            val deleted = ProcessUtil.pruneStartLogs(tmp, inst)
            assertEquals("超 keep 但未过 7 天线的近期日志一份都不能删", 0, deleted)
            assertEquals("8 份都应保留", 8, tmp.listFiles { f -> f.name.startsWith("dsh-start-") }!!.size)
        } finally {
            tmp.deleteRecursively()
            inst.deleteRecursively()
        }
    }

    /**
     * keep 的语义边界：`drop(keep)` 为空（文件数 ≤ keep）时，**再老的也不删** ——
     * "keep 防误删现场的最近 N 份"是硬保护，7 天规则只在 keep 之外生效。
     * 同批文件换更小 keep 后，超龄的才被清掉（下拉参数有效性）。
     */
    @Test
    fun pruneProtectsEverythingAtOrBelowKeepRegardlessOfAge() {
        val tmp = Files.createTempDirectory("r18-tmp3").toFile()
        val inst = Files.createTempDirectory("r18-inst3").toFile()
        try {
            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000
            (0..5).forEach { i ->
                File(tmp, "dsh-start-60$i.log").apply {
                    writeText("token=OLD$i\n"); setLastModified(now - (10 + i) * day)
                }
            }
            assertEquals("6 份 ≤ keep=6：全在 keep 内，一份不删", 0, ProcessUtil.pruneStartLogs(tmp, inst))
            assertEquals("6 份都在", 6, tmp.listFiles()!!.size)
            // keep 压到 2：最新 2 份仍受保护（哪怕 10/11 天前），其余 4 份超龄被删
            val deleted = ProcessUtil.pruneStartLogs(tmp, inst, keep = 2)
            assertEquals("keep=2 时删掉 4 份超龄的", 4, deleted)
            assertEquals("剩最新 2 份", 2, tmp.listFiles()!!.size)
        } finally {
            tmp.deleteRecursively()
            inst.deleteRecursively()
        }
    }

    /**
     * 容错：目录不存在 / 传进来的"目录"其实是文件 / 负 keep —— 一律不抛、按可判定的部分行事。
     * （"listFiles 后文件被并发删除"在进程内没法真正交错复现，由实现里的 mtime>0 守卫与
     * `delete()` 布尔值容忍覆盖 —— 见 pruneStartLogs 的实现注释。）
     */
    @Test
    fun pruneToleratesMissingDirsAndBadInput() {
        val missingTmp = File(Files.createTempDirectory("r18-tmp4").toFile(), "does-not-exist")
        val missingInst = File(Files.createTempDirectory("r18-inst4").toFile(), "also-missing")
        assertEquals("两目录都不存在 → 0，不抛", 0, ProcessUtil.pruneStartLogs(missingTmp, missingInst))

        val base = Files.createTempDirectory("r18-tmp5").toFile()
        try {
            // tmp 是文件而非目录：listFiles 返回 null → 容忍
            val notADir = File(base, "i-am-a-file").apply { writeText("x") }
            val inst = File(base, "real-inst").apply { mkdirs() }
            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000
            val recent = File(inst, "dsh-start-a.log").apply { writeText("t"); setLastModified(now) }
            val ancient = File(inst, "dsh-start-b.log").apply { writeText("t"); setLastModified(now - 20 * day) }
            // keep=0（含负数会被 coerce 到 0）：全部都是候选，只有超 7 天的被删
            assertEquals("keep=-3 → 视作 0；只删超龄的 1 份", 1, ProcessUtil.pruneStartLogs(notADir, inst, keep = -3))
            assertTrue("近期文件仍在", recent.exists())
            assertFalse("超龄文件被删", ancient.exists())
        } finally {
            base.deleteRecursively()
        }
    }

    // --- BL-158：认领恢复回写的 CAS 判据（pidFileAllowsAdoptionWriteBack）-----------

    /**
     * CAS 语义四条边：文件不存在 / 仍是认领时的 pid / 占位 0 → 允许写；
     * 已被新进程接管（别的 pid）→ 拒绝（这就是"死 pid 覆盖新进程"的守卫）。
     */
    @Test
    fun adoptionWriteBackCasGate() {
        val adopted = 12345L
        assertTrue("文件不存在（清理竞态刚删）→ 允许写", pidFileAllowsAdoptionWriteBack(null, adopted))
        assertTrue("文件里仍是认领时的 pid → 允许写", pidFileAllowsAdoptionWriteBack(adopted, adopted))
        assertTrue("占位 0 视为可安全覆盖", pidFileAllowsAdoptionWriteBack(0L, adopted))
        assertFalse(
            "文件已被新进程写走（停后秒启）→ 拒绝回写，死 pid 不许覆盖新文件",
            pidFileAllowsAdoptionWriteBack(99999L, adopted)
        )
    }
}
