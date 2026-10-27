package com.dsh

import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * instances.json 持久化的 JVM 单元测试（不需要真机/Android），对应 T-A3 的 **K12** 与 fix2 的 **BL-098**。
 *
 * 覆盖的都是"会静默丢数据"的两处：
 * - **原子写**：rename 失败时**绝不**降级为直接覆盖写（那会在最危险时亲手打破原子性，
 *   写一半被杀就损坏整份清单）。失败必须抛错并**保留 .tmp**（里面是完整的新清单，可人工找回）。
 * - **空文件 ≠ 损坏**：空/全空白的 instances.json（全新安装 / 首次写被打断）必须当成**空清单**，
 *   而不是被误判损坏 → 另存 .corrupt → 清空列表。
 *
 * BL-098 追加覆盖"字段级容错"（parseManifest 纯函数 + loadFrom 集成）：
 * - 未知 state 枚举名的元素**降级保留为 NOT_INSTALLED**（R14-V2A F2 策略修正；初版曾丢弃，
 *   会让用户在不知情下永久丢实例），其余保留、**不**产生 .corrupt（此前整份清单被清空）；
 * - 缺 id/name 必填字段、非对象元素同样元素级丢弃；
 * - state 缺失 / 显式 null / 未知枚举名在 Gson 下都是 null（2.10.1 实测，真类验证）——
 *   三者一律回填 NOT_INSTALLED（repair 会按磁盘 package.json 自愈）；
 * - 整体损坏（非 JSON / 根不是对象 / instances 不是数组）仍走 .corrupt 备份路径。
 *
 * ★ 这些测试走 [DshInstances.loadFrom] / [DshInstances.writeManifest] / [DshInstances.parseManifest]
 *   这三个"接受参数"的可测种子（生产路径 [DshInstances.saveAsync]/`load` 用的是
 *   `DshPaths.INSTANCES_MANIFEST`，在没有 Android Context 的单测里取不到）。
 *   每个用例用自己的临时目录，互不影响。
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
            // fix12 / C-05：0 字节**仍然**按空清单继续（不是损坏），但必须先留一份取证备份 ——
            // 0 字节最可能的成因是"上一次非原子写被中断"，此时清单里原来的名字/端口/版本都已丢失，
            // 不留备份就再也没有任何线索。判据在 loadFrom 里是 readText 成功后的 text.isEmpty()，
            // **不是** File.length()==0（同名目录的 length() 也是 0，那会把真读失败误判成空文件）。
            assertTrue("0 字节应被改名成 .empty- 备份（而不是留在原地）", !f.exists())
            assertEquals("空文件不应产生 .corrupt 备份", 0, corruptBackups(dir).size)
            val emptyBackups = dir.listFiles { file -> file.name.startsWith("instances.json.empty-") }
            assertEquals("应恰好产生一份 .empty- 备份", 1, emptyBackups?.size ?: 0)
            assertEquals("备份必须是 0 字节的原始内容", 0L, emptyBackups!!.first().length())
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

    // --- BL-098：字段级容错（parseManifest 纯函数 + loadFrom 集成）----------

    /** 一个字段齐全的合法元素（可改 state / id 拼场景），复用同一份 JSON 模板。 */
    private fun entry(
        id: String,
        name: String = id,
        state: String? = "\"READY\""
    ): String {
        val statePart = if (state == null) "" else ""","state":$state"""
        return """{"id":"$id","name":"$name","dshVersion":"0.1.6","profile":"web",""" +
            """"port":0,"model":"deepseek-flash","createdAt":1,"updatedAt":2,"lastError":null$statePart}"""
    }

    /**
     * 未知 state 枚举名的元素：**降级保留为 NOT_INSTALLED**（R14-V2A F2 策略修正），
     * 其余元素原样保留、**不**产生 .corrupt（BL-098 核心）。
     * 旧实现整份清单解析抛异常 → 清空 + .corrupt，用户看到"实例全没了"；
     * W1D 初版曾"丢弃坏元素"—— 那会让用户在不知情下**永久丢失实例**（此后任何一次落盘固化）。
     * 回填是自愈的：repair/启动校验以磁盘 package.json 为准，会把它纠正回真实状态。
     */
    @Test
    fun loadKeepsEntryWithUnknownStateAsNotInstalled() {
        val dir = Files.createTempDirectory("dsh-load").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText(
                """{"instances":[${
                    entry("inst-ok1")
                },${entry("inst-bad", state = "\"UNKNOWN_STATE_NAME\"")},${entry("inst-ok2")}],
                    "selectedId":"inst-ok2"}"""
            )

            DshInstances.loadFrom(f)

            val list = DshInstances.instances.value
            assertEquals("未知 state 元素应降级保留，三个都在", 3, list.size)
            assertEquals(listOf("inst-ok1", "inst-bad", "inst-ok2"), list.map { it.id })
            // 保留的元素 state 正常解析（不是被整体丢弃后重建的默认值）
            assertEquals(DshInstance.State.READY, list[0].state)
            assertEquals("未知 state 应回填 NOT_INSTALLED（而非 null / 丢弃）", DshInstance.State.NOT_INSTALLED, list[1].state)
            assertEquals("存活元素的 selectedId 应生效", "inst-ok2", DshInstances.selectedId.value)
            assertEquals("元素级容错不应产生 .corrupt 备份", 0, corruptBackups(dir).size)
            assertTrue("原文件应保持原位（不迁移成备份）", f.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 缺 state 字段：元素保留、回填 NOT_INSTALLED（Gson unsafe 会给 null，不能让 null 漏进列表）。
     * 显式 null 同路径。
     */
    @Test
    fun loadBackfillsMissingStateToNotInstalled() {
        val dir = Files.createTempDirectory("dsh-load").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText(
                """{"instances":[${entry("inst-no-state", state = null)},${
                    entry("inst-null-state", state = "null")
                }],"selectedId":null}"""
            )

            DshInstances.loadFrom(f)

            val list = DshInstances.instances.value
            assertEquals("缺 state 与显式 null 都应保留元素", 2, list.size)
            assertEquals(
                "缺 state 应回填 NOT_INSTALLED",
                DshInstance.State.NOT_INSTALLED, list[0].state
            )
            assertEquals(
                "显式 null 应回填 NOT_INSTALLED",
                DshInstance.State.NOT_INSTALLED, list[1].state
            )
            assertEquals("回填路径不应产生 .corrupt 备份", 0, corruptBackups(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 缺必填 id / name、以及非对象元素：元素级丢弃，其余保留。 */
    @Test
    fun loadDropsInvalidEntriesWithoutWipingManifest() {
        val dir = Files.createTempDirectory("dsh-load").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText(
                """{"instances":[${entry("inst-ok")},
                    {"name":"no-id"},
                    {"id":"inst-no-name"},
                    "just a string",
                    {"id":"inst-wrong-port","name":"n","port":"not-a-number","state":"READY"}],
                    "selectedId":"inst-ok"}"""
            )

            DshInstances.loadFrom(f)

            val list = DshInstances.instances.value
            assertEquals("只有合法元素应存活", 1, list.size)
            assertEquals("inst-ok", list[0].id)
            assertEquals("不应产生 .corrupt 备份", 0, corruptBackups(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * parseManifest 纯函数边界：
     * - 非 JSON / 根不是对象 / instances 不是数组 → Corrupt（整体损坏，保留 .corrupt 语义）；
     * - instances 缺失/null → Parsed 空清单，dropped=0；
     * - state 为数字/对象原语（非字符串）：不当作"未知枚举名"，走回填/元素级失败，不整份清空。
     */
    @Test
    fun parseManifestDistinguishesCorruptFromElementLevelProblems() {
        // 整体损坏三连
        assertTrue(
            "非 JSON 文本应判 Corrupt",
            DshInstances.parseManifest("{ not json :::") is DshInstances.ParsedManifest.Corrupt
        )
        assertTrue(
            "根为数组应判 Corrupt",
            DshInstances.parseManifest("""[1,2,3]""") is DshInstances.ParsedManifest.Corrupt
        )
        assertTrue(
            "instances 非数组应判 Corrupt",
            DshInstances.parseManifest("""{"instances":{}}""") is DshInstances.ParsedManifest.Corrupt
        )
        assertTrue(
            "字面量 null 应判 Corrupt（旧的 parsed to null 语义保留）",
            DshInstances.parseManifest("null") is DshInstances.ParsedManifest.Corrupt
        )

        // instances 缺失 / null = 空清单（与旧语义一致 m.instances ?: emptyList()）
        val none = DshInstances.parseManifest("""{}""")
        assertTrue("缺失 instances 应是 Parsed", none is DshInstances.ParsedManifest.Parsed)
        assertEquals(0, (none as DshInstances.ParsedManifest.Parsed).instances.size)
        assertEquals(0, none.dropped)

        // state 是数字原语：不按"未知枚举名"丢（它压根不是字符串），走 Gson（实测得 null）→ 回填
        val numeric = DshInstances.parseManifest(
            """{"instances":[{"id":"x","name":"n","state":3}]}"""
        ) as DshInstances.ParsedManifest.Parsed
        assertEquals("数字 state 应回填而非丢弃", 1, numeric.instances.size)
        assertEquals(DshInstance.State.NOT_INSTALLED, numeric.instances[0].state)
    }

    /** 整体损坏时 parseManifest 给出可读原因（进全局日志的 reason），且 loadFrom 仍走 .corrupt。 */
    @Test
    fun parseManifestReportsReasonForCorrupt() {
        val c = DshInstances.parseManifest("""{"instances":"nope"}""")
        assertTrue(c is DshInstances.ParsedManifest.Corrupt)
        assertTrue(
            "Corrupt 应带出可读原因",
            (c as DshInstances.ParsedManifest.Corrupt).reason.contains("not a JSON array")
        )
    }

    /** 该目录下所有 `instances.json.corrupt-*` 备份文件。 */
    private fun corruptBackups(dir: File): List<File> =
        dir.listFiles { file -> file.name.startsWith("instances.json.corrupt-") }?.toList() ?: emptyList()

    // --- BL-151（R18）：删除等待循环的"安装 proot 是否仍活着"纯判据 --------------

    /**
     * [DshInstances.isLiveInstallProot] 必须与 [com.dsh.core.DshInstaller] 孤儿清理
     * （cleanupStaleInstallProcess）的判据逐条一致：pid>0、存活、cmdline 同时含 `proot`
     * 与本实例 id。任何放宽都会让删除等在"别人的进程"上（每次删除平白多等 8s），
     * 任何收紧都会让 npm 仍在写盘时 deleteRecursively 开删（BL-151 要修的正是它）。
     */
    @Test
    fun isLiveInstallProotRequiresAllCriteria() {
        val id = "inst-1700000000000-3-7f2a"
        val good = "proot --link2symlink -r /data/x/rootfs INSTANCE_DIR=/data/x/instances/$id /bin/sh ..."
        assertTrue("全判据满足 → 视为仍在跑（继续等待）", DshInstances.isLiveInstallProot(321, true, good, id))
        assertFalse("pid<=0（没解析到 pid）→ 无等待对象", DshInstances.isLiveInstallProot(0, true, good, id))
        assertFalse("进程已死 → 收尾完成", DshInstances.isLiveInstallProot(321, false, good, id))
        assertFalse("cmdline 读不到（进程已消失/受限）→ 不算我们的", DshInstances.isLiveInstallProot(321, true, null, id))
        assertFalse("不是 proot（pid 被复用）→ 不算", DshInstances.isLiveInstallProot(321, true, "node server.js", id))
        assertFalse("是别的实例的 proot → 不算", DshInstances.isLiveInstallProot(321, true, good, "inst-other"))
    }
}
