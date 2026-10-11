package com.dsh

import com.dsh.core.DshInstances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * instances.json 持久化的 JVM 单元测试（不需要真机/Android），对应 T-A3 的 **K12**。
 *
 * 覆盖的都是"会静默丢数据"的两处：
 * - **原子写**：rename 失败时**绝不**降级为直接覆盖写（那会在最危险时亲手打破原子性，
 *   写一半被杀就损坏整份清单）。失败必须抛错并**保留 .tmp**（里面是完整的新清单，可人工找回）。
 * - **空文件 ≠ 损坏**：空/全空白的 instances.json（全新安装 / 首次写被打断）必须当成**空清单**，
 *   而不是被误判损坏 → 另存 .corrupt → 清空列表。
 *
 * ★ 这些测试走 [DshInstances.loadFrom] / [DshInstances.writeManifest] 这两个"接受 File 参数"的
 *   可测种子（生产路径 [DshInstances.saveAsync]/`load` 用的是 `DshPaths.INSTANCES_MANIFEST`，
 *   在没有 Android Context 的单测里取不到）。每个用例用自己的临时目录，互不影响。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.DshInstancesPersistenceTest"
 */
class DshInstancesPersistenceTest {

    // --- K12①：原子写，rename 失败不降级覆盖 --------------------------------

    /** 正常情形：写到一个还不存在的目标，内容正确，且 .tmp 被 rename 消费掉（不残留）。 */
    @Test
    fun writeManifestWritesAtomicallyToFreshTarget() {
        val dir = Files.createTempDirectory("dsh-manifest").toFile()
        try {
            val target = File(dir, "instances.json")
            val json = """{"instances":[],"selectedId":null}"""

            DshInstances.writeManifest(target, json)

            assertTrue("目标文件应被写出", target.isFile)
            assertEquals("内容应与写入完全一致", json, target.readText())
            assertFalse(".tmp 应已被 rename 消费（不残留）", File(dir, "instances.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * rename 失败：必须抛错、保留 .tmp、且**不**把目标非原子覆盖掉。
     *
     * 让 rename 稳定失败的跨平台做法：把目标做成一个**非空目录**。POSIX 下
     * `rename(file -> dir)` 会 EISDIR/ENOTEMPTY，Windows 的 MoveFile 遇到已存在目标也失败 ——
     * 两边都返回 false，于是 [DshInstances.writeManifest] 必须抛错。
     */
    @Test
    fun writeManifestThrowsAndKeepsTmpWhenRenameFails() {
        val dir = Files.createTempDirectory("dsh-manifest").toFile()
        try {
            val target = File(dir, "instances.json")
            assertTrue("前置：目标需是一个已存在的目录", target.mkdirs())
            File(target, "blocker").writeText("x") // 非空，确保 rename 必然失败

            val json = """{"instances":[],"selectedId":"inst-keep"}"""
            var threw = false
            try {
                DshInstances.writeManifest(target, json)
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("rename 失败必须抛错（K12：不得降级为非原子覆盖）", threw)

            // .tmp 必须保留，且是完整的新清单（人工找回的依据）
            val tmp = File(dir, "instances.json.tmp")
            assertTrue("rename 失败后 .tmp 必须保留", tmp.isFile)
            assertEquals("保留的 .tmp 必须是完整的新清单", json, tmp.readText())

            // 关键：绝不能把目标非原子覆盖掉（它仍应是原来的目录，而不是被写成一个文件）
            assertTrue("目标不得被非原子覆盖（应仍是原目录）", target.isDirectory)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- K12②：load 把空/全空白文件当成空清单，不误判损坏 --------------------

    @Test
    fun loadTreatsEmptyFileAsEmptyManifestNotCorrupt() {
        val dir = Files.createTempDirectory("dsh-load").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText("") // 0 字节（全新安装 / 首次写被打断）

            DshInstances.loadFrom(f)

            assertTrue("空文件应被当作空清单", DshInstances.instances.value.isEmpty())
            // 不得被判损坏：原文件不该被改名走，也不该产生 .corrupt 备份
            assertTrue("空文件不应被重命名为损坏备份", f.exists())
            assertEquals("空文件不应产生 .corrupt 备份", 0, corruptBackups(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun loadTreatsWhitespaceOnlyFileAsEmptyManifest() {
        val dir = Files.createTempDirectory("dsh-load").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText("   \n\t  \n")

            DshInstances.loadFrom(f)

            assertTrue("全空白文件应被当作空清单", DshInstances.instances.value.isEmpty())
            assertEquals("全空白文件不应产生 .corrupt 备份", 0, corruptBackups(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 真正损坏的清单：仍要另存 .corrupt 备份并重置为空（这条行为不能被 K12 的空文件修复带坏）。 */
    @Test
    fun loadBacksUpAndResetsOnGenuinelyCorruptFile() {
        val dir = Files.createTempDirectory("dsh-load").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText("{ this is not valid json ::: ")

            DshInstances.loadFrom(f)

            assertTrue("损坏清单应被重置为空", DshInstances.instances.value.isEmpty())
            assertEquals("损坏文件应另存为 .corrupt 备份", 1, corruptBackups(dir).size)
            assertFalse("损坏文件应被改名走（不再占用原名）", f.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 有效清单：正常解析出实例与选中项，且不产生任何 .corrupt 备份。 */
    @Test
    fun loadParsesValidManifest() {
        val dir = Files.createTempDirectory("dsh-load").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText(
                """
                {
                  "instances": [
                    {"id":"inst-aaa","name":"first","dshVersion":"0.1.6-alpha.2","profile":"web",
                     "port":0,"model":"deepseek-flash","createdAt":1,"updatedAt":2,
                     "state":"READY","lastError":null}
                  ],
                  "selectedId": "inst-aaa"
                }
                """.trimIndent()
            )

            DshInstances.loadFrom(f)

            val list = DshInstances.instances.value
            assertEquals("应解析出 1 个实例", 1, list.size)
            assertEquals("inst-aaa", list[0].id)
            assertEquals("first", list[0].name)
            assertEquals("选中项应落在清单里的实例上", "inst-aaa", DshInstances.selectedId.value)
            assertEquals("有效清单不应产生 .corrupt 备份", 0, corruptBackups(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 该目录下所有 `instances.json.corrupt-*` 备份文件。 */
    private fun corruptBackups(dir: File): List<File> =
        dir.listFiles { file -> file.name.startsWith("instances.json.corrupt-") }?.toList() ?: emptyList()
}
