package com.dsh

import com.dsh.core.DshAppScope
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Round 15 批 B（B1c）实例集合硬化单测：BL-099 / BL-100 / BL-101。
 *
 * 覆盖三组：
 * 1. **BL-099**：[DshInstances.buildId] 纯函数（id 组装规则 = 毫秒 + seq + 16 位随机 hex，
 *    与旧内联拼接逐字节一致）。
 * 2. **BL-100**：`update(instance)` 先 copy 再入表 —— 即使传入的**就是列表内对象**（别名），
 *    列表项引用也必须变化并发射；传入对象自身不被偷偷改 updatedAt。
 * 3. **BL-101**：`rebuildMissingInstances` 恢复判据（目录即事实、id 去重、排序稳定、非目录忽略）；
 *    `listCorruptBackups` 备份发现与排序；`loadFrom` 空清单/损坏路径的孤儿目录收编集成；
 *    corrupt 日志带完整备份路径。
 *
 * 可测合作条件：这些用例走 [DshInstances.loadFrom] / [DshInstances.rebuildMissingInstances] /
 * [DshInstances.listCorruptBackups] 的可测种子（接受参数的重载）；生产重载用的是
 * `DshPaths.INSTANCES_MANIFEST` / `INSTANCES_DIR`（JVM 单测没有 Android Context）。
 * [DshInstances.loadFrom] 内的收编逻辑有 `DshPaths.isLoaded` 守卫：未初始化路径系统时
 * （单测环境）no-op —— 集成用例因此改测"扫描判据 + loadFrom 不崩且不误动清单"两半，
 * 收编集成本身由真机门验证（见报告"未验证项"）。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.B1cTest"
 */
class B1cTest {

    // --- BL-099：buildId 纯函数 ---------------------------------------------

    /** 组装规则与旧内联拼接逐字节一致：inst-<ms>-<seq>-<hex(rand)>（小写、无零填充）。 */
    @Test
    fun buildIdMatchesLegacyInlineFormat() {
        assertEquals(
            "inst-1700000000000-3-7f2a",
            DshInstances.buildId(1_700_000_000_000L, 3, 0x7f2a)
        )
        // 无零填充：252 = 0xfc，不是 "00fc"
        assertEquals("inst-1-1-fc", DshInstances.buildId(1L, 1, 252))
        // 0 的十六进制是 "0"（toHexString 语义）
        assertEquals("inst-42-7-0", DshInstances.buildId(42L, 7, 0))
    }

    /** 负 rand（Math.random()*0xffff 的边界不会出负，但函数按 Int 全域定义）与 toHexString 一致。 */
    @Test
    fun buildIdDelegatesToIntegerToHexString() {
        assertEquals(
            "inst-5-9-" + Integer.toHexString(-1),
            DshInstances.buildId(5L, 9, -1)
        )
    }

    // --- BL-100：update 先 copy 再入表 --------------------------------------

    /**
     * 传入列表内**同一对象**（别名）：入表后列表项不应再是原引用 —— 旧实现 map 回同一引用，
     * 新列表与旧列表结构相等（除了 updatedAt 的原地改动），MutableStateFlow 可能静默不发射。
     * 这里用"发射计数"直接钉住修复目标。
     */
    @Test
    fun updateCopiesInstanceSoAliasedEntryStillEmits() {
        val dir = Files.createTempDirectory("dsh-update").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText("""{"instances":[${entry("inst-a")}],"selectedId":"inst-a"}""")
            DshInstances.loadFrom(f)
            val aliased = DshInstances.instances.value.single()

            val emissions = AtomicInteger(0)
            // collect 挂在进程级 IO 作用域上；收集计数用原子变量跨线程读。
            // 直接 collect{} 是无限挂起，包在 launch 里由 finally cancel 收尾。
            val handle = DshAppScope.scope.launch {
                DshInstances.instances.collect { emissions.incrementAndGet() }
            }
            try {
                // collect 是懒启动：轮询等它收到初始值（StateFlow 起始必发一次当前值）
                val baseDeadline = System.currentTimeMillis() + 2_000
                while (emissions.get() < 1 && System.currentTimeMillis() < baseDeadline) {
                    Thread.sleep(20)
                }
                val baseline = emissions.get()
                assertTrue("前置：collector 应至少收到一次初始值", baseline >= 1)

                aliased.name = "renamed-by-caller"
                DshInstances.update(aliased)

                val deadline = System.currentTimeMillis() + 2_000
                while (emissions.get() == baseline && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                assertTrue(
                    "update 后必须发射新值（旧实现的别名入表可能不发射）",
                    emissions.get() > baseline
                )
                val entry = DshInstances.instances.value.single()
                assertEquals("调用方的字段改动应被 copy 带入", "renamed-by-caller", entry.name)
                assertFalse(
                    "入表项不再是传入的同一引用（copy 入表）",
                    entry === aliased
                )
            } finally {
                handle.cancel()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 传入对象自身不再被 update 偷偷改 updatedAt（旧实现原地赋值）。 */
    @Test
    fun updateDoesNotMutateCallersInstanceInPlace() {
        val dir = Files.createTempDirectory("dsh-update2").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText("""{"instances":[${entry("inst-b")}],"selectedId":"inst-b"}""")
            DshInstances.loadFrom(f)
            val outer = DshInstance(id = "inst-b", name = "outside")
            outer.updatedAt = 111L

            DshInstances.update(outer)

            assertEquals("传入对象的 updatedAt 不应被原地改写", 111L, outer.updatedAt)
            // 入表项拿到新的 updatedAt；name 以传入对象为准
            val entry = DshInstances.instances.value.first { it.id == "inst-b" }
            assertEquals("outside", entry.name)
            assertFalse(
                "入表项的 updatedAt 应为 update() 赋的新时间戳",
                entry.updatedAt == 111L
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** update 未知 id：列表保持不变（map 无命中），不崩、不新增。 */
    @Test
    fun updateUnknownIdKeepsListUnchanged() {
        val dir = Files.createTempDirectory("dsh-update3").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText("""{"instances":[${entry("inst-c")}],"selectedId":"inst-c"}""")
            DshInstances.loadFrom(f)
            val before = DshInstances.instances.value

            DshInstances.update(DshInstance(id = "inst-ghost", name = "ghost"))

            assertEquals("未知 id 不应新增或删除任何实例", before.map { it.id }, DshInstances.instances.value.map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- BL-101b：rebuildMissingInstances 恢复判据 ---------------------------

    /** 目录即事实：instances 根下是目录且不在 knownIds 里 → 收编（NOT_INSTALLED，name=id）。 */
    @Test
    fun rebuildAdoptsUnknownDirectoriesOnly() {
        val root = Files.createTempDirectory("dsh-instances-root").toFile()
        try {
            File(root, "inst-known").mkdirs()
            File(root, "inst-orphan-1").mkdirs()
            File(root, "inst-orphan-2").mkdirs()
            File(root, "loose-file.txt").writeText("x") // 非目录：忽略

            val out = DshInstances.rebuildMissingInstances(root, setOf("inst-known"))

            assertEquals("只收编不在清单里的目录", listOf("inst-orphan-1", "inst-orphan-2"), out.map { it.id })
            out.forEach {
                assertEquals("恢复项按 id 稳定排序", it.id, it.name)
                assertEquals(
                    "恢复项状态为 NOT_INSTALLED（真实状态交异步 repair 校准）",
                    DshInstance.State.NOT_INSTALLED, it.state
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** 幂等：收编后的 id 进入 knownIds，再扫一次不再出结果。 */
    @Test
    fun rebuildIsIdempotentWithKnownIds() {
        val root = Files.createTempDirectory("dsh-instances-idem").toFile()
        try {
            File(root, "inst-a").mkdirs()
            File(root, "inst-b").mkdirs()

            val first = DshInstances.rebuildMissingInstances(root, emptySet())
            assertEquals(2, first.size)
            val second = DshInstances.rebuildMissingInstances(root, first.mapTo(HashSet()) { it.id })
            assertTrue("已收编的 id 不应重复收编", second.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    /** 根不存在 / 不是目录：空表，不抛。 */
    @Test
    fun rebuildToleratesMissingRoot() {
        val root = Files.createTempDirectory("dsh-instances-none").toFile()
        try {
            assertTrue(
                "不存在的根 → 空表",
                DshInstances.rebuildMissingInstances(File(root, "nope"), emptySet()).isEmpty()
            )
            val fileRoot = File(root, "a-file")
            fileRoot.writeText("x")
            assertTrue(
                "不是目录的根 → 空表",
                DshInstances.rebuildMissingInstances(fileRoot, emptySet()).isEmpty()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // --- BL-101a：listCorruptBackups ----------------------------------------

    /** 备份发现：同目录下 ${manifest}.corrupt-* 的**文件**，最新（时间戳最大）在前；目录不算。 */
    @Test
    fun listCorruptBackupsFindsFilesNewestFirst() {
        val dir = Files.createTempDirectory("dsh-corrupt-list").toFile()
        try {
            val manifest = File(dir, "instances.json")
            File(dir, "instances.json.corrupt-1000").writeText("old")
            File(dir, "instances.json.corrupt-2000").writeText("new")
            File(dir, "instances.json.corrupt-3000").mkdirs() // 目录：不算备份
            File(dir, "other.json.corrupt-4000").writeText("x") // 前缀不符：不算

            val out = DshInstances.listCorruptBackups(manifest)

            assertEquals(
                "只认同名 manifest 的 .corrupt-* 文件，最新在前",
                listOf("instances.json.corrupt-2000", "instances.json.corrupt-1000"),
                out.map { it.name }
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 无备份 / 无父目录：空表。 */
    @Test
    fun listCorruptBackupsEmptyWhenNone() {
        val dir = Files.createTempDirectory("dsh-corrupt-none").toFile()
        try {
            val manifest = File(dir, "instances.json")
            File(dir, "instances.json").writeText("{}") // 本体存在但无备份
            assertTrue(DshInstances.listCorruptBackups(manifest).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- BL-101 集成：loadFrom 空路径不误伤 + 损坏留痕 -----------------------

    /**
     * 空文件 → 空清单（K12 语义不变），且 [DshInstances.rebuildMissingInstances] 判据内核
     * 在同场景下确实能找到"应该收编"的目录（收编动作本身由 DshPaths.isLoaded 守卫，见类注释）。
     */
    @Test
    fun emptyManifestKeepsListEmptyAndRecoveryCoreSeesOrphans() {
        val dir = Files.createTempDirectory("dsh-load-empty").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText("")
            DshInstances.loadFrom(f)
            assertTrue("空文件仍是空清单", DshInstances.instances.value.isEmpty())

            // 判据内核：同场景下孤儿目录可被识别（真机收编的前半程）
            val instancesRoot = File(dir, "instances")
            File(instancesRoot, "inst-leftover").mkdirs()
            val adoptable = DshInstances.rebuildMissingInstances(instancesRoot, emptySet())
            assertEquals(listOf("inst-leftover"), adoptable.map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 损坏清单：仍清空 + 产生 .corrupt 备份（语义不变），备份可被 [listCorruptBackups] 发现。 */
    @Test
    fun corruptManifestStillBacksUpAndIsDiscoverable() {
        val dir = Files.createTempDirectory("dsh-load-corrupt").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText("{ definitely not json :::")
            DshInstances.loadFrom(f)

            assertTrue("损坏清单应被重置为空", DshInstances.instances.value.isEmpty())
            val backups = DshInstances.listCorruptBackups(f)
            assertEquals("应有一个可发现的 .corrupt 备份", 1, backups.size)
            assertEquals("备份内容应与原损坏内容一致", "{ definitely not json :::", backups[0].readText())
            // BL-101a：全局日志必须带**完整路径**（无 adb 的用户至少能从日志里抄出备份文件名）
            val log = com.dsh.core.DshLogBus.export()
            assertTrue(
                "corrupt 日志应包含备份的完整绝对路径",
                log.contains(backups[0].absolutePath)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 有效清单不受影响：不做任何收编、不产生备份（防止 BL-101 改动带坏既有路径）。 */
    @Test
    fun validManifestIsNotTouchedByRecoveryPath() {
        val dir = Files.createTempDirectory("dsh-load-valid").toFile()
        try {
            val f = File(dir, "instances.json")
            f.writeText("""{"instances":[${entry("inst-only")}],"selectedId":"inst-only"}""")
            DshInstances.loadFrom(f)

            val list = DshInstances.instances.value
            assertEquals("有效清单按原样载入", listOf("inst-only"), list.map { it.id })
            assertEquals("选中项保留", "inst-only", DshInstances.selectedId.value)
            assertTrue("有效清单不产生备份", DshInstances.listCorruptBackups(f).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 缺失文件 = 空清单（K12）：loadFrom 不崩、列表置空、选中清空。 */
    @Test
    fun missingManifestLoadsAsEmpty() {
        val dir = Files.createTempDirectory("dsh-load-missing").toFile()
        try {
            val f = File(dir, "instances.json") // 不创建

            DshInstances.loadFrom(f)

            assertTrue("缺失文件应是空清单", DshInstances.instances.value.isEmpty())
            assertNull("选中项应清空", DshInstances.selectedId.value)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- 工具 ---------------------------------------------------------------

    /** 一个字段齐全的合法元素（与 DshInstancesPersistenceTest.entry 同款模板）。 */
    private fun entry(id: String): String =
        """{"id":"$id","name":"$id","dshVersion":"0.1.6","profile":"web",""" +
            """"port":0,"model":"deepseek-flash","createdAt":1,"updatedAt":2,"state":"READY","lastError":null}"""
}
