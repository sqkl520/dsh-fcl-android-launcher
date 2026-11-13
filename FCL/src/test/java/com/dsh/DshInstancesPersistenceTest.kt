package com.dsh

import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.core.DshRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    // --- r31-s02-conc-02：并发收编去重 + 重入保护 ---------------------------

    /**
     * **r31-s02-conc-02 的证伪条件**（Round 31 / Arbiter `G2-a`，`severity: HIGH`）。
     *
     * 缺陷行为：`loadFrom` 两条并发入口（`init()` 的 IO 协程 + UI 直调 `retryManifestLoad`）
     * 各自基于 `_instances.value` 快照算出同一批孤儿目录，随后 `_instances.update { it + recovered }`
     * 追加且**不去重** ⇒ 同一 id 在 `_instances` 里出现两份（`List` 不是 `Set`）。
     *
     * 证伪条件（= 验收标准）：
     * 1. 任意两次并发 `loadFrom` 之后，`_instances` 中**不存在相同 id 的重复条目**；
     * 2. 且「收编后的实例经 `DshPaths.instanceDir(id)` 仍能定位到其原目录」保持成立。
     *
     * 为什么用 `CyclicBarrier` 而不是 `Thread.sleep`：要真的把两条路径推到"快照已算、还没写回"
     * 的窗口里重叠。单靠起两个线程跑，绝大多数时候一条已经跑完另一条才开始，缺陷不会出现。
     * 这里放 4 条线程同时起跑（缺陷只需两条就能触发，多两条让"重入保护"不是只对成对成立），
     * 并把 `instances/` 下的目录**数量放大到 40** 以拉长 `listFiles` + `sorted` 的窗口，
     * 让重叠稳定可复现（HEAD 上实测必现，见报告 baseline）。
     */
    @Test
    fun concurrentLoadFromDoesNotDuplicateAdoptedInstanceIds() {
        val root = Files.createTempDirectory("dsh-adopt-race").toFile()
        val instancesRoot = File(root, "instances")
        try {
            assertTrue(instancesRoot.mkdirs())
            // 40 个孤儿目录：任何一个都不在清单里 ⇒ 两次 loadFrom 都会算进 recovered
            val orphanIds = (1..40).map { "inst-orphan-%02d".format(it) }
            orphanIds.forEach { assertTrue(File(instancesRoot, it).mkdirs()) }

            val json = File(root, "instances.json")
            json.writeText("""{"instances":[],"selectedId":null}""")

            setUpPathsForAdoptionTest(instancesRoot)
            DshInstances.resetForTest()

            val barrier = CyclicBarrier(4)
            val errors = CopyOnWriteArrayList<Throwable>()
            val threads = (1..4).map {
                Thread {
                    try {
                        barrier.await(10, TimeUnit.SECONDS)
                        DshInstances.loadFrom(json)
                    } catch (t: Throwable) {
                        errors += t
                    }
                }.apply { start() }
            }
            threads.forEach { it.join(30_000) }

            assertTrue("并发 loadFrom 不应抛异常：$errors", errors.isEmpty())

            val ids = DshInstances.instances.value.map { it.id }
            val duplicated = ids.groupBy { it }.filterValues { it.size > 1 }.keys
            assertTrue(
                "两次并发 loadFrom 后每个 id 只允许出现一次：总条目=${ids.size} 唯一=${ids.toSet().size} " +
                    "重复=${duplicated.size} 个（${duplicated.take(3).sorted()}…）",
                duplicated.isEmpty()
            )
            assertTrue(
                "40 个孤儿目录都应被收编（否则是收编本身失效，'不重复'会假绿）：实际 ${ids.size}",
                ids.toSet().containsAll(orphanIds)
            )
            // 证伪条件第 2 条：收编后的实例必须仍能映射回它自己的目录（pat-13 的互斥点）。
            orphanIds.forEach { id ->
                assertTrue(
                    "收编后的实例 $id 经 instanceDir(id) 必须定位到原目录",
                    DshPaths.instanceDir(id).isDirectory
                )
            }
        } finally {
            restorePathsAfterAdoptionTest()
            DshInstances.resetForTest()
            root.deleteRecursively()
        }
    }

    /**
     * 单线程重复收编也必须幂等（并发保护之外的兜底）：同一份目录连续跑两次 `loadFrom`，
     * 结果里每个 id 仍只出现一次。
     */
    @Test
    fun repeatedLoadFromKeepsAdoptionIdempotent() {
        val root = Files.createTempDirectory("dsh-adopt-idem").toFile()
        val instancesRoot = File(root, "instances")
        try {
            assertTrue(instancesRoot.mkdirs())
            val orphanIds = (1..5).map { "inst-idem-$it" }
            orphanIds.forEach { assertTrue(File(instancesRoot, it).mkdirs()) }

            val json = File(root, "instances.json")
            json.writeText("""{"instances":[],"selectedId":null}""")

            setUpPathsForAdoptionTest(instancesRoot)
            DshInstances.resetForTest()

            DshInstances.loadFrom(json)
            DshInstances.loadFrom(json)

            val ids = DshInstances.instances.value.map { it.id }
            assertEquals("两次 loadFrom 后 id 不得重复", ids.size, ids.toSet().size)
            assertTrue("5 个孤儿目录都应被收编", ids.toSet().containsAll(orphanIds))
        } finally {
            restorePathsAfterAdoptionTest()
            DshInstances.resetForTest()
            root.deleteRecursively()
        }
    }

    /**
     * **r31-s02-conc-03 的证伪条件**（Round 31 / Arbiter `G2-b`，`severity: MEDIUM`）。
     *
     * 缺陷行为：`delete()` 在**同步摘内存**之后、**自己的同步写盘**之前，要隔着 cancel +
     * `stopIf` + 最长 `DELETE_WAIT_TIMEOUT_MS`（8s）的轮询。这段窗口里任何别的改动触发的
     * `saveAsync()` 都会把"已摘除"的 `_instances.value` 写进磁盘清单。于是 delete 自己的写一旦
     * 失败（回滚内存、`onFinished(false)`），磁盘清单已经不含该实例 —— 下次冷启它从清单消失、
     * 目录还在，被 `adoptOrphanInstanceDirs` 收编成 `name = id` 的 NOT_INSTALLED 空壳，
     * 用户丢掉名称/版本/端口。
     *
     * 证伪条件（= 验收标准）：窗口内的那次并发写盘**不得**丢掉该实例；且 delete 回滚后
     * 内存里该实例必须回来（`onFinished(false)`）。
     *
     * ## 怎么把窗口钉成确定的
     * 1. 用反射把 `DshRuntime` 的状态置为 `Starting(待删 id)`：`activeInstanceId()`（含 Stopping
     *    的 [activeOwnerOf]）于是等于该 id，`delete()` 的等待循环会一直转到超时为止；
     *    用 `Starting` 而不是 `Running` 是**故意**的 —— `stopIf` 的判据是 `runningInstanceId()`
     *    （只认 Running），所以它不会真的去停进程，等待循环是唯一被卡住的东西，没有副作用。
     * 2. 在这个被卡住的窗口里，走**生产触发路径**（`markState` → `saveAsync`）写一次盘。
     * 3. 把清单路径换成"非空目录"（既有用例 [writeManifestThrowsAndKeepsTmpWhenRenameFails]
     *    用的同一招），让 delete 自己的写**确定地**失败 → 走回滚分支。
     *
     * ## 为什么读清单要容 .tmp
     * 本仓库的 `writeManifest` 在 rename 失败时**保留 `.tmp`**（里面是完整的新清单，可人工找回），
     * 而 JVM-Windows 的 `renameTo` 不覆盖同名目标。于是"磁盘上的清单内容"必须按
     * `target 或 target.tmp` 读 —— 两个平台读到的都是同一次写的内容。
     */
    @Test
    fun deleteRollbackDoesNotLetConcurrentSaveDropTheInstanceFromDisk() {
        val root = Files.createTempDirectory("dsh-delete-race").toFile()
        val instancesRoot = File(root, "instances")
        val doomedId = "inst-doomed-1"
        val otherId = "inst-other-2"
        try {
            assertTrue(instancesRoot.mkdirs())
            assertTrue(File(instancesRoot, doomedId).mkdirs())
            assertTrue(File(instancesRoot, otherId).mkdirs())
            val json = File(root, "instances.json")
            json.writeText(
                """{"instances":[${entry(doomedId, name = "我的实例", state = "\"READY\"")},
                    ${entry(otherId, name = "别的实例", state = "\"READY\"")}],"selectedId":"$doomedId"}"""
            )

            setUpPathsForAdoptionTest(instancesRoot)
            DshInstances.resetForTest()
            DshInstances.loadFrom(json)
            assertEquals("前置：两个实例都应在内存里", listOf(doomedId, otherId), DshInstances.instances.value.map { it.id })

            // 1) 把等待循环钉住（见 KDoc 第 1 条）
            holdRuntimeInStartingState(doomedId)

            val finished = AtomicReference<Boolean?>(null)
            val latch = CountDownLatch(1)
            assertTrue(
                "前置：清单读取正常时 delete 应受理",
                DshInstances.delete(doomedId) {
                    finished.set(it)
                    latch.countDown()
                }
            )
            assertTrue(
                "前置：delete 必须**同步**摘掉内存（这是本缺陷的起点）",
                DshInstances.instances.value.none { it.id == doomedId }
            )

            // 2) 窗口内走生产触发路径写一次盘：markState → saveAsync → writeNow
            //    先移走目标文件：JVM-Windows 的 `renameTo` 不覆盖同名目标，留着它会让这次写**必然失败**，
            //    那样"磁盘上还是旧内容"就成了一种假绿（打完窗口却什么也没提交）。删掉后这次写落到
            //    一个全新目标上，与真机（POSIX rename 覆盖）上的行为一致。
            assertTrue("前置：应能移走清单文件以模拟一次成功的写", json.delete())
            DshInstances.markState(otherId, DshInstance.State.READY)
            val windowText = awaitManifestText(root, 20_000) { it.contains(otherId) }
            assertTrue("并发 saveAsync 应在窗口内落盘（否则这条检查没打到缺陷）：$windowText", windowText.isNotEmpty())

            // 3) 让 delete 自己的写确定失败，然后放行等待循环
            clobberManifestIntoUnrenamableDir(json)
            releaseRuntimeState()
            assertTrue("delete 应在回滚后回调（false = 删除没有发生）", latch.await(20, TimeUnit.SECONDS))
            assertEquals("写盘失败必须回调 false", false, finished.get())

            assertTrue(
                "delete 回滚后实例必须回到内存",
                DshInstances.instances.value.any { it.id == doomedId }
            )
            assertTrue(
                "★ 证伪条件（前半）：窗口内那次并发写盘不得丢掉待删实例（磁盘清单原文：$windowText）",
                windowText.contains(doomedId)
            )
            assertTrue(
                "证伪条件（前半·命名）：清单里必须仍是它的用户命名（丢成 name=id 就是本缺陷的终态）",
                windowText.contains("我的实例")
            )

            // 4) 直接观察后半句：**把它当冷启**重读磁盘，看它会不会被收编成 name=id 的空壳。
            //    目录一直在（本用例从不删它），所以这里只需要让清单回到"回滚那次写失败之后"的磁盘状态。
            restoreManifestFromText(json, windowText)
            DshInstances.resetForTest()
            DshInstances.loadFrom(json)
            val afterColdStart = DshInstances.instances.value.firstOrNull { it.id == doomedId }
            assertTrue("冷启后该实例必须还在清单里（否则就是'从清单消失'）", afterColdStart != null)
            assertEquals(
                "★ 证伪条件（后半）：冷启后不得变成 name=id 的收编空壳（实际 name=${afterColdStart?.name}）",
                "我的实例", afterColdStart?.name
            )
            assertEquals(
                "冷启后状态不得被收编成 NOT_INSTALLED 空壳",
                DshInstance.State.READY, afterColdStart?.state
            )
        } finally {
            releaseRuntimeState()
            restorePathsAfterAdoptionTest()
            DshInstances.resetForTest()
            root.deleteRecursively()
        }
    }

    /**
     * 删除**已提交**之后，任何后续写盘都不得把该实例写回来（否则"界面说删了、重启复活"）。
     * 这条钉的是本次修复的另一半：待删登记必须在落盘的同一临界区内撤销。
     */
    @Test
    fun committedDeleteIsNotResurrectedByLaterWrites() {
        val root = Files.createTempDirectory("dsh-delete-commit").toFile()
        val instancesRoot = File(root, "instances")
        val id = "inst-gone-1"
        try {
            assertTrue(instancesRoot.mkdirs())
            assertTrue(File(instancesRoot, id).mkdirs())
            val json = File(root, "instances.json")
            json.writeText("""{"instances":[${entry(id, name = "要删掉的", state = "\"READY\"")}],"selectedId":null}""")

            setUpPathsForAdoptionTest(instancesRoot)
            DshInstances.resetForTest()
            DshInstances.loadFrom(json)

            // JVM-Windows 的 `renameTo` 不覆盖同名目标：留着目标文件会让 delete 自己的写**必然失败**
            // （那不是本用例要测的东西）。删掉它，等价于真机 POSIX 上"rename 原子覆盖"那一步。
            assertTrue("前置：应能先移走清单文件", json.delete())

            val finished = AtomicReference<Boolean?>(null)
            val latch = CountDownLatch(1)
            assertTrue(DshInstances.delete(id) { finished.set(it); latch.countDown() })
            assertTrue("delete 应完成（回调 ok）", latch.await(30, TimeUnit.SECONDS))
            assertEquals("目录删净时应回调 true", true, finished.get())

            // 之后再写一次盘：已提交的删除不得被待删快照"补回来"
            val laterText = try {
                DshInstances.writeNow()
                awaitManifestText(root, 5_000) { it.isNotEmpty() }
            } catch (e: Exception) {
                // Windows：目标已存在时 rename 必然失败，但 .tmp 里就是本次写的完整内容
                File(root, "instances.json.tmp").takeIf { it.isFile }?.readText().orEmpty()
            }
            assertFalse("已提交的删除不得被后续写盘复活（清单原文：$laterText）", laterText.contains(id))
            assertTrue("前置：删除后内存里也不该再有它", DshInstances.instances.value.none { it.id == id })
        } finally {
            restorePathsAfterAdoptionTest()
            DshInstances.resetForTest()
            root.deleteRecursively()
        }
    }

    /**
     * **r31-s02-conc-03-NEW 的证伪条件**（Round 31 / 补修单 fix-r31-103）。
     *
     * 缺陷行为：`manifestSnapshot()` 若在 `synchronized(pendingDeleteLock)` **之外**读
     * `_instances.value`，会与 [delete] 的回滚路径交错 —— 回滚在**同一把锁内**先 `restore()`
     * 把实例写回内存、再撤销待删登记，而它跑在 `writeMutex` 之外。于是写线程可以：
     * ① 锁外读到"实例只在待删表里"的旧内存；② 回滚线程拿锁完成 restore + 撤销登记；
     * ③ 写线程随后拿到锁时待删表已空 ⇒ 直接 `return current`（① 的陈旧快照）⇒
     * 磁盘清单**不含**该实例 —— 正是 conc-03 要消灭的形态，只是窗口从 8 秒缩到两次取锁之间。
     *
     * ## 怎么把交错钉成确定的（不靠概率）
     * 主线程先**自己持有** `pendingDeleteLock`，再让工作线程调用真实的 `manifestSnapshot()`：
     * - 锁外读的那一版：工作线程会先读完 `_instances.value`、**然后**才卡在监视器上（`BLOCKED`）；
     * - 锁内读的那一版：工作线程一进门就卡在监视器上，什么都还没读。
     * 主线程观察到 `worker.state == BLOCKED` 后，在**仍是自己持锁**的情况下调 [rollbackPendingDelete]
     * （`synchronized` 对同线程可重入），放锁，工作线程随即拿到锁。两版的分歧完全由"读在锁内还是锁外"
     * 决定，所以这条检查每次都会同一结论，不是高频采样。
     *
     * ## 为什么直接调 `holdPendingDelete` / `rollbackPendingDelete`
     * 走真实 [delete] 事务会引入 cancel/stopIf/最长 8s 轮询与后台写盘协程，反而让"回滚那一刻"不可控；
     * 这两个私有函数正是 delete 摘内存与回滚的**全部**动作（[delete] 只是把它们按事务顺序串起来），
     * 直接调用等价于把那一刻单独取出来，且不改变任何生产语义。
     */
    @Test
    fun snapshotMustNotDropInstanceRolledBackDuringTheRead() {
        val root = Files.createTempDirectory("dsh-snapshot-lock").toFile()
        val instancesRoot = File(root, "instances")
        val doomedId = "inst-lock-1"
        val otherId = "inst-lock-2"
        try {
            assertTrue(instancesRoot.mkdirs())
            assertTrue(File(instancesRoot, doomedId).mkdirs())
            assertTrue(File(instancesRoot, otherId).mkdirs())
            val json = File(root, "instances.json")
            json.writeText(
                """{"instances":[${entry(doomedId, name = "回滚中的实例", state = "\"READY\"")},
                    ${entry(otherId, name = "陪跑实例", state = "\"READY\"")}],"selectedId":null}"""
            )

            setUpPathsForAdoptionTest(instancesRoot)
            DshInstances.resetForTest()
            DshInstances.loadFrom(json)

            val doomed = DshInstances.instances.value.first { it.id == doomedId }
            // 复刻 delete() 的"同步摘内存 + 登记待删"（生产里就是这一步，见 :979）
            holdPendingDeleteViaReflection(doomedId, doomed)
            assertTrue(
                "前置：内存里必须已经摘掉它（否则本用例打不到缺陷）",
                DshInstances.instances.value.none { it.id == doomedId }
            )
            assertTrue(
                "前置：待删表里必须有它",
                pendingDeletesMap().containsKey(doomedId)
            )

            val snapshotMethod = DshInstances::class.java
                .getDeclaredMethod("manifestSnapshot", String::class.java)
                .apply { isAccessible = true }
            val rollbackMethod = DshInstances::class.java
                .getDeclaredMethod("rollbackPendingDelete", String::class.java, Function0::class.java)
                .apply { isAccessible = true }

            val snapshot = AtomicReference<List<DshInstance>?>(null)
            val workerError = AtomicReference<Throwable?>(null)
            var observedBlocked = false
            val worker = Thread {
                try {
                    @Suppress("UNCHECKED_CAST")
                    snapshot.set(snapshotMethod.invoke(DshInstances, *arrayOf<Any?>(null)) as List<DshInstance>)
                } catch (t: Throwable) {
                    workerError.set(t)
                }
            }.apply { isDaemon = true; name = "snapshot-worker" }

            synchronized(pendingDeleteLockOf()) {
                worker.start()

                // 等它真正卡在这把锁上：锁外读的那版此时**已经**把旧内存读走了，
                // 锁内读的那版则什么都没读到 —— 这一步就是两版的唯一分歧点。
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline) {
                    if (worker.state == Thread.State.BLOCKED) { observedBlocked = true; break }
                    Thread.sleep(1)
                }

                // 仍是本线程持锁（同线程可重入）：按生产完全相同的两步完成回滚 —— 先 restore 再撤销登记。
                val restore: Function0<Unit> = {
                    memoryFlow().value = memoryFlow().value + doomed
                }
                rollbackMethod.invoke(DshInstances, doomedId, restore)
            }
            // ★ join 必须在**放锁之后**：持锁 join 会让工作线程永远拿不到监视器，本用例只能靠
            //   join 超时收场（那会把"读在锁外还是锁内"这个分歧点整个抹掉，得到一条永远失败的空转用例）。
            worker.join(10_000)

            assertTrue("前置：工作线程确实被这把锁挡住过（否则本用例是空转）", observedBlocked)
            assertFalse("前置：工作线程不得抛异常（${workerError.get()}）", workerError.get() != null)
            val snap = snapshot.get()
            assertNotNull("前置：manifestSnapshot 必须返回结果", snap)
            assertTrue(
                "前置：回滚后内存里该实例已回来",
                DshInstances.instances.value.any { it.id == doomedId }
            )
            assertTrue(
                "★ 证伪条件：回滚瞬间取到的快照不得漏掉该实例" +
                    "（实际 ids=${snap!!.map { it.id }}）",
                snap!!.any { it.id == doomedId }
            )
        } finally {
            restorePathsAfterAdoptionTest()
            DshInstances.resetForTest()
            root.deleteRecursively()
        }
    }

    /**
     * **r31-s02-conc-03-972 的证伪条件**（Round 31 / 补修单 fix-r31-104）。
     *
     * 缺陷行为：`delete()` 顶部的"同一 id 已在途"守卫直接 `return true`（KDoc :956 承诺
     * "true = 已受理，真正的成败走 onFinished"），却**不回调** `onFinished` —— 第二次调用的
     * 调用方被告知"受理了"，然后永远等不到结果回调（HEAD 上没有这个守卫，两次调用各回调一次）。
     *
     * 证伪条件：对同一 id 连续调用两次 `delete()`，**两次必须各自收到恰好一次 `onFinished`**；
     * 不允许出现"返回 true 但回调永不到达"。
     *
     * ## 怎么把"在途"钉成确定的
     * 第一次 `delete()` 会**同步**完成"摘内存 + 登记待删"（`holdPendingDelete`），然后在协程里
     * 进等待循环。用 [holdRuntimeInStartingState] 把 `activeInstanceId()` 钉成该 id，等待循环
     * 会一直转到 `DELETE_WAIT_TIMEOUT_MS`（8s）—— 这段窗口就是第二次调用必然落在"在途"里的原因，
     * 不靠调度运气。
     */
    @Test
    fun secondDeleteOnSameIdStillReceivesItsCallback() {
        val root = Files.createTempDirectory("dsh-delete-dup").toFile()
        val instancesRoot = File(root, "instances")
        val id = "inst-dup-1"
        try {
            assertTrue(instancesRoot.mkdirs())
            assertTrue(File(instancesRoot, id).mkdirs())
            val json = File(root, "instances.json")
            json.writeText(
                """{"instances":[${entry(id, name = "重复删除的实例", state = "\"READY\"")}],"selectedId":null}"""
            )

            setUpPathsForAdoptionTest(instancesRoot)
            DshInstances.resetForTest()
            DshInstances.loadFrom(json)
            assertEquals("前置：实例应在内存里", listOf(id), DshInstances.instances.value.map { it.id })

            // 把第一次事务钉在等待循环里，第二次调用才有确定的"在途"窗口。
            holdRuntimeInStartingState(id)
            // JVM-Windows 的 renameTo 不覆盖同名目标：先移走，让第一次事务自己的写盘能成功
            // （否则它走回滚分支，本用例就测不到"在途"这一段了）。
            assertTrue("前置：应能先移走清单文件", json.delete())

            val firstCb = AtomicReference<Boolean?>(null)
            val secondCb = AtomicReference<Boolean?>(null)
            val firstLatch = CountDownLatch(1)
            val secondLatch = CountDownLatch(1)

            assertTrue(
                "前置：第一次 delete 应受理",
                DshInstances.delete(id) { firstCb.set(it); firstLatch.countDown() }
            )
            assertTrue(
                "前置：第一次事务必须已把它登记进待删表（否则第二次打得不是在途守卫）",
                pendingDeletesMap().containsKey(id)
            )
            val secondAccepted = DshInstances.delete(id) { secondCb.set(it); secondLatch.countDown() }

            releaseRuntimeState()

            // ★ 先等第一次事务收尾（它必须照常回调）。先等它再等第二次，是为了让缺陷态下的
            //   失败断言发生在**事务已经落定**之后：否则收尾会在 finally 清理之后才跑，污染下一条用例。
            assertTrue("前置：第一次 delete 的回调必须到达", firstLatch.await(30, TimeUnit.SECONDS))
            assertEquals("第一次事务自己的结果照常回调", true, firstCb.get())

            // ★ 证伪条件（主体）：第二次调用的回调必须到达。缺陷态下这里等 5s 也等不到。
            assertTrue(
                "★ 证伪条件：第二次 delete() 的 onFinished 必须到达（缺陷态：返回 true 但永不回调）",
                secondLatch.await(5, TimeUnit.SECONDS)
            )
            assertFalse("第二次是重复提交，应明确返回 false（拒绝）", secondAccepted)
            assertEquals("第二次回调必须给出 false（本次没有执行删除）", false, secondCb.get())
        } finally {
            releaseRuntimeState()
            restorePathsAfterAdoptionTest()
            DshInstances.resetForTest()
            root.deleteRecursively()
        }
    }

    /** 把 `DshRuntime` 钉在 `Starting(id)`：让 `activeInstanceId()` == id（卡住 delete 的等待循环）。 */
    private fun holdRuntimeInStartingState(id: String) {
        runtimeStateFlow().value = DshRuntime.State.Starting(id, "held")
    }

    private fun releaseRuntimeState() {
        runCatching { runtimeStateFlow().value = DshRuntime.State.Idle }
    }

    // --- fix-r31-103：直接摸 pendingDeleteLock / pendingDeletes / _instances 的 seam ----------
    //
    // 这三个都是 `private`，而本用例要做的正是"由测试自己持锁，把 manifestSnapshot 的读取
    // 卡在锁外/锁内这一分歧点上"——没有公开 API 能表达"我持着这把锁"。反射读的是**字段本身**
    // （不是另建一把锁），因此和生产走的是同一个监视器。

    /** `DshInstances.pendingDeleteLock`（与 [DshInstances.delete] 用的是同一个对象）。 */
    private fun pendingDeleteLockOf(): Any {
        val f = DshInstances::class.java.getDeclaredField("pendingDeleteLock")
        f.isAccessible = true
        return f.get(DshInstances)
    }

    /** `DshInstances.pendingDeletes`（id → 待删记录）。 */
    @Suppress("UNCHECKED_CAST")
    private fun pendingDeletesMap(): MutableMap<String, DshInstance> {
        val f = DshInstances::class.java.getDeclaredField("pendingDeletes")
        f.isAccessible = true
        return f.get(DshInstances) as MutableMap<String, DshInstance>
    }

    /** `DshInstances._instances`（内存列表的 StateFlow）。 */
    @Suppress("UNCHECKED_CAST")
    private fun memoryFlow(): MutableStateFlow<List<DshInstance>> {
        val f = DshInstances::class.java.getDeclaredField("_instances")
        f.isAccessible = true
        return f.get(DshInstances) as MutableStateFlow<List<DshInstance>>
    }

    /** 调 [DshInstances.delete] 的私有 `holdPendingDelete`：生产里"摘内存 + 登记待删"就是它。 */
    private fun holdPendingDeleteViaReflection(id: String, inst: DshInstance) {
        val m = DshInstances::class.java
            .getDeclaredMethod("holdPendingDelete", String::class.java, DshInstance::class.java)
        m.isAccessible = true
        m.invoke(DshInstances, id, inst)
    }

    @Suppress("UNCHECKED_CAST")
    private fun runtimeStateFlow(): MutableStateFlow<DshRuntime.State> {
        val f = DshRuntime::class.java.getDeclaredField("_state")
        f.isAccessible = true
        return f.get(DshRuntime) as MutableStateFlow<DshRuntime.State>
    }

    /** 把清单路径换成一个**非空目录**：`tmp.renameTo(目标)` 在 POSIX/Windows 上都必然失败。 */
    private fun clobberManifestIntoUnrenamableDir(json: File) {
        json.delete()
        assertTrue("前置：应能建出同名目录", json.mkdirs())
        File(json, "blocker").writeText("x")
    }

    /** 把清单恢复成 [text]（先拆掉 [clobberManifestIntoUnrenamableDir] 造出来的同名目录）。 */
    private fun restoreManifestFromText(json: File, text: String) {
        if (json.isDirectory) json.deleteRecursively()
        json.writeText(text)
        File(json.parentFile, "${json.name}.tmp").delete()
    }

    /** 读"磁盘上的清单内容"：目标文件优先，rename 失败时退到同目录的 `.tmp`（见用例 KDoc）。 */
    private fun manifestTextOf(root: File): String {
        val target = File(root, "instances.json")
        if (target.isFile) return target.readText()
        val tmp = File(root, "instances.json.tmp")
        return if (tmp.isFile) tmp.readText() else ""
    }

    private fun awaitManifestText(
        root: File,
        timeoutMs: Long,
        predicate: (String) -> Boolean
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = runCatching { manifestTextOf(root) }.getOrDefault("")
            if (last.isNotEmpty() && predicate(last)) return last
            Thread.sleep(50)
        }
        return last
    }

    /**
     * 把 [DshPaths] 指到临时目录，并**恢复**原来的加载态。
     *
     * 为什么必须反射：[DshPaths] 的路径字段是 `lateinit var ... private set`，只有 `loadPaths(Context)`
     * 能写它们，而 JVM 单测没有 Android Context。`adoptOrphanInstanceDirs` 又有 `DshPaths.isLoaded`
     * 守卫（未加载时整段跳过），不打开这道守卫就测不到收编路径 —— 而收编路径正是本缺陷的落点。
     * 收尾必须复原：`DshPaths` 是进程级单例，漏了会把**后面**的用例带红（最难查的那种）。
     */
    private fun setUpPathsForAdoptionTest(instancesRoot: File) {
        val fields = DshPaths::class.java.declaredFields
        savedPaths = fields.associateWith { f ->
            f.isAccessible = true
            runCatching { f.get(DshPaths) }.getOrNull()
        }
        fun set(name: String, value: Any) {
            val f = DshPaths::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.set(DshPaths, value)
        }
        val root = instancesRoot.parentFile!!
        set("ROOT_DIR", root.absolutePath)
        set("ROOTFS_DIR", File(root, "rootfs").absolutePath)
        set("INSTANCES_DIR", instancesRoot.absolutePath)
        set("INSTANCES_MANIFEST", File(root, "instances.json").absolutePath)
        set("SCRIPTS_DIR", File(root, "scripts").absolutePath)
        set("NPM_CACHE_DIR", File(root, "npm-cache").absolutePath)
        set("LOG_FILE", File(root, "logs/runtime.log").absolutePath)
        set("TARBALL_CACHE_DIR", File(root, "tarballs").absolutePath)
        set("TMP_DIR", File(root, "tmp").absolutePath)
        set("isLoaded", true)
    }

    /** [setUpPathsForAdoptionTest] 之前的字段快照（值 → 原样写回）。 */
    private var savedPaths: Map<java.lang.reflect.Field, Any?> = emptyMap()

    private fun restorePathsAfterAdoptionTest() {
        savedPaths.forEach { (f, v) ->
            f.isAccessible = true
            runCatching { f.set(DshPaths, v) }
        }
        savedPaths = emptyMap()
    }

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
