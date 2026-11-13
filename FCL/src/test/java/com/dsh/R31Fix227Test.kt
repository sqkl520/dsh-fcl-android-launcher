package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.ProcessUtil
import com.dsh.core.UrlScanner
import com.dsh.core.adoptionCandidateAllowed
import com.dsh.core.adoptionHostIsLoopback
import com.dsh.core.adoptionPortMatches
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R31 / 第 4 波 / fix-227（security 维度）的三条修复的行为契约。
 *
 * | finding | 修法 | 用例 |
 * |---|---|---|
 * | `slice-03-security-F1` | `DshPaths.instanceDir` 只接受**单个合法路径分量**（`isSafeInstanceIdComponent`） | `f1_*` |
 * | `slice-03-security-F2` | 认领候选 = 端口一致 **且** 主机回环（`adoptionCandidateAllowed`），③级收集端同口径 | `f2_*` |
 * | `slice-03-security-F4` | `stamp()` 把行内换行折成字面量（`escapeLineBreaks`） | `f4_*` |
 *
 * ★ 全部断言都是**行为**断言（返回值 / 快照行数 / 目录身份），不断言源码文本 —— 否则"把校验
 *   注释掉但仍留一行同形代码"也能骗过测试。
 * ★ `DshPaths` 是进程级单例，`INSTANCES_DIR` 只有 `loadPaths(Context)` 能写、JVM 单测没有 Context
 *   ⇒ 与 `DshInstancesPersistenceTest` 同法用反射注入 + `@After` 原样复原（漏了会污染后续用例）。
 */
class R31Fix227Test {

    // =====================================================================
    // F1：实例作用域路径 — instanceId 只能是单个合法路径分量
    // =====================================================================

    /** 真实 id、收编来的目录名、以及带 `.`/中文/空格的合法目录名都必须在**通过侧**（恒等映射）。 */
    @Test
    fun f1_realAndAdoptedIdsStayOnTheIdentityMapping() {
        val accepted = listOf(
            "inst-1700000000000-3-7f2a",   // DshInstances.buildId 的实际形态
            "inst-orphan-01",              // 收编来的目录名（rebuildMissingInstances 用目录名当 id）
            "inst.legacy",
            "inst 带 中文",
            "inst_underscore",
            "UPPER-Case-01"
        )
        accepted.forEach {
            assertTrue("合法路径分量必须通过（否则会把收编/历史目录挡在门外）: <$it>", DshPaths.isSafeInstanceIdComponent(it))
        }
    }

    /** 越界形态一律在**拒绝侧**。 */
    @Test
    fun f1_traversalAndSeparatorsAreRejected() {
        val rejected = listOf(
            "..",                   // 直接越到 eventsDir/instances 的父目录
            "../escaped",           // 越界 + 子路径
            "a/../../b",            // 绕路越界
            ".",
            "",                     // 空分量 ⇒ File(parent,"") 就是 parent 本身
            "a/b",                  // 分隔符（会在 instances/ 下造嵌套目录）
            "a\\b",                 // Windows 分隔符（同族）
            "a\u0000b"              // NUL 截断
        )
        rejected.forEach {
            val folded = DshPaths.safeInstanceDirName(it)
            assertFalse("必须被折叠（不得原样当路径分量）: <${it.replace("\u0000", "\\0")}> -> <$folded>",
                DshPaths.isSafeInstanceIdComponent(it))
            assertTrue("折叠结果必须非空且不含分隔符: <$folded>",
                folded.isNotEmpty() && folded.none { c -> c == '/' || c == '\\' || c == '\u0000' })
        }
    }

    /**
     * **核心行为**：`instanceDir(任意) `的规范路径必须仍在 `INSTANCES_DIR` 内。
     *
     * 这条是 F1 claim 的直接证伪条件 —— `java.io.File` 不解析 `..`，改前
     * `instanceDir("../escaped").canonicalPath` 是 `filesDir/dsh/escaped`（越出 instances/）。
     */
    @Test
    fun f1_instanceDirNeverEscapesTheInstancesRoot() {
        val root = Files.createTempDirectory("r31-f1-root").toFile()
        WITH_PATHS(root) {
            val rootCanonical = File(root, "instances").canonicalFile
            assertTrue("前置：instances 目录需存在", rootCanonical.isDirectory)
            val hostile = listOf("../escaped", "..", "a/../../b", "a/b")
            hostile.forEach { id ->
                val target = DshPaths.instanceDir(id).canonicalFile
                assertTrue(
                    "instanceDir(<$id>) 越出了 instances/：$target （root=$rootCanonical）",
                    target.path == rootCanonical.path || target.path.startsWith(rootCanonical.path + File.separator)
                )
            }
            // 反向：合法 id 必须**精确**落在 instances/<id>（不得被转义掉，否则收编会指向空目录）
            val ok = DshPaths.instanceDir("inst-orphan-01").canonicalFile
            assertEquals(File(rootCanonical, "inst-orphan-01").canonicalFile, ok)
        }
    }

    /**
     * `G2-a` 互斥点（arbiter `pat-13` 的硬约束）：40 个收编来的 id 经 `instanceDir(id)` 仍定位到
     * **各自的原始目录**。
     *
     * 为什么这条必须与 F1 同批：先加白名单却打断收编时，被收编实例的路径指向空目录**不会报错**，
     * `conc-02` 的"同一 id 只出现一次"甚至可能因收编失败而**假绿**。这里用真实目录名做端到端校验。
     */
    @Test
    fun f1_adoptedDirectoryNamesStillMapToThemselves() {
        val root = Files.createTempDirectory("r31-f1-adopt").toFile()
        WITH_PATHS(root) {
            val instancesRoot = File(root, "instances")
            val ids = (1..40).map { "inst-orphan-%02d".format(it) }
            ids.forEach { assertTrue("前置：建目录 $it", File(instancesRoot, it).mkdirs()) }
            ids.forEach { id ->
                val mapped = DshPaths.instanceDir(id)
                assertTrue("收编后的实例 $id 经 instanceDir(id) 必须指向原目录（实际 ${mapped.absolutePath}）",
                    mapped.isDirectory)
                assertEquals("$id 的映射必须逐字节恒等", File(instancesRoot, id).canonicalFile, mapped.canonicalFile)
            }
        }
    }

    // =====================================================================
    // F2：认领候选必须端口一致 **且** 主机回环
    // =====================================================================

    /** 非回环主机（含"看起来像回环"的前缀后缀）一律拒绝 —— 这是 SSRF 的阻断点。 */
    @Test
    fun f2_nonLoopbackCandidatesAreRejected() {
        val rejected = listOf(
            "http://evil.example:3080/?token=x",
            "http://192.168.1.5:3080/?token=x",
            "http://10.0.0.1:3080/?token=x",
            "http://127.0.0.1.evil.example:3080/?token=x",  // 回环前缀伪装
            "http://evil.example#@127.0.0.1:3080/?token=x", // 反向伪装
            "http://[2001:db8::1]:3080/?token=x"
        )
        rejected.forEach {
            assertFalse("非回环候选必须拒绝（否则宿主会向它发 GET）: $it", adoptionCandidateAllowed(it, 3080))
        }
    }

    /** 回环三种写法必须仍被采信（不许把"不安全"改成"不可用"）。 */
    @Test
    fun f2_loopbackCandidatesAreStillAccepted() {
        listOf(
            "http://127.0.0.1:3080/?token=x",
            "http://localhost:3080/?token=x",
            "http://[::1]:3080/?token=x"
        ).forEach {
            assertTrue("回环候选必须采信（否则正常认领全废）: $it", adoptionCandidateAllowed(it, 3080))
        }
        // 端口门不得被削弱：回环但端口不符 ⇒ 拒绝
        assertFalse("回环 + 端口不符仍须拒绝", adoptionCandidateAllowed("http://127.0.0.1:3081/?token=x", 3080))
    }

    /** 取严边界：解析不出主机 ⇒ 不采信（宁可漏认一次，也不向不确定目标发请求）。 */
    @Test
    fun f2_unparsableHostIsRejected() {
        assertFalse("空/空白", adoptionCandidateAllowed("   ", 3080))
        assertFalse("null", adoptionCandidateAllowed(null, 3080))
        assertFalse("无 :port", adoptionCandidateAllowed("http://127.0.0.1/?token=x", 3080))
        assertFalse("非 URL 文本", adoptionCandidateAllowed("not a url at all", 3080))
        // java.net.URI 对含 '_' 的主机名给 host=null（实测 JDK17）—— 取不到就是 null
        assertFalse("主机解析不出（非法主机名）", adoptionHostIsLoopback("http://my_host:3080/?token=x"))
        assertEquals("hostOf 与本判据同口径", null, UrlScanner.hostOf("http://my_host:3080/?token=x"))
    }

    /**
     * **端到端（无网络）**：rootfs 可写的 `dsh-start-*.log` 里混入非回环候选时，③级收集端
     * 不得把它交出去。
     *
     * 这是 F2 的攻击链第一环 —— 该函数是唯一把"rootfs 内可写文件"翻译成"候选 URL 列表"的地方；
     * 它拒了，后面的 `Probe.probeTokenUrl` 就根本不会被调用（拦截必须在网络调用**之前**：
     * 若改成"探活时失败"，null 会被当 tentative 采信，比不拦更糟）。
     * 同时钉住"不许改不可用"：同一文件里的回环候选仍必须被收集到。
     */
    @Test
    fun f2_startLogPoisonCandidateIsNotCollected() {
        val tmp = Files.createTempDirectory("r31-f2-tmp").toFile()
        val inst = Files.createTempDirectory("r31-f2-inst").toFile()
        try {
            File(tmp, "dsh-start-4242.log").writeText(
                "dsh web: http://evil.example:46501/?token=POISON\n" +
                    "dsh web: http://127.0.0.1:46501/?token=REAL\n"
            )
            val got = ProcessUtil.recoverTokenUrlsFromStartLogs(tmp, inst, 46501)
            assertTrue("被污染的非回环候选不得出现在候选列表里：$got",
                got.none { it.contains("evil.example") || it.contains("POISON") })
            assertTrue("同文件里的回环候选仍须收集（不许为了修安全把功能弄坏）：$got",
                got.any { it.contains("127.0.0.1:46501") && it.contains("REAL") })
        } finally {
            tmp.deleteRecursively()
            inst.deleteRecursively()
        }
    }

    /** 端口门的老契约不能被本次改动改写（`R21FixETest` 的同口径复核）。 */
    @Test
    fun f2_portGateSemanticsUnchanged() {
        assertTrue(adoptionPortMatches("http://127.0.0.1:46501/?token=abc", 46501))
        assertFalse(adoptionPortMatches("http://127.0.0.1:3080/?token=abc", 46501))
        assertFalse(adoptionPortMatches(null, 46501))
        assertFalse(adoptionPortMatches("http://127.0.0.1:46501/?token=abc", 0))
    }

    // =====================================================================
    // F4：一行入库 = 一行（换行折成字面量，不再伪造出多行）
    // =====================================================================

    /**
     * 主用例：一条含 `\n` 的进程输出只允许占**一行**。
     *
     * 改前：`stamp` 原样保留 `\n`，落盘用 `joinToString("\n")` ⇒ 快照/文件里变成两行，且第二行
     * 会被 `recentLogTail` 当作独立现场事实去驱动 `EADDRINUSE` / seccomp 归因。
     */
    @Test
    fun f4_newlineDoesNotSplitOneLineIntoTwo() {
        val id = "inst-f4-nl-${System.nanoTime()}"
        val before = DshLogBus.snapshotFor(id).value.lines.size
        DshLogBus.appendFor(id, MARKER + "a\nb")
        DshLogBus.flushNow()

        val lines = DshLogBus.snapshotFor(id).value.lines
        assertEquals("一条 append 必须仍然只有一行", before + 1, lines.size)
        val added = lines.drop(before)
        assertEquals("只应新增那一行", 1, added.size)
        assertFalse("行内不得残留真实换行（否则落盘会变成两行）", added[0].contains('\n'))
        assertTrue("换行必须折成可见字面量（排障要能看出原始内容带换行）", added[0].contains("\\n"))
        assertTrue("原文两段都必须保留", added[0].contains("a") && added[0].contains("b"))
    }

    /** CRLF 与裸 CR 同判据（Windows 侧输出/粘贴内容里很常见）。 */
    @Test
    fun f4_crlfAndBareCrAreAlsoFolded() {
        val id = "inst-f4-cr-${System.nanoTime()}"
        val before = DshLogBus.snapshotFor(id).value.lines.size
        DshLogBus.appendFor(id, MARKER + "x\r\ny")
        DshLogBus.appendFor(id, MARKER + "p\rq")
        DshLogBus.flushNow()

        val added = DshLogBus.snapshotFor(id).value.lines.drop(before)
        assertEquals("两次 append 仍应是两行", 2, added.size)
        added.forEach {
            assertFalse("不得残留 \\r / \\n", it.contains('\n') || it.contains('\r'))
        }
    }

    /** 全局流（`append`）与进度流（`appendProgress`）走**同一个**加工口，判据不得分叉。 */
    @Test
    fun f4_globalAndProgressStreamsUseTheSameCriterion() {
        val marker = "r31f4global${System.nanoTime()}"
        DshLogBus.append("$marker-a\nb")
        DshLogBus.appendProgress("$marker-c\nd")
        DshLogBus.flushNow()

        val hits = DshLogBus.snapshot.value.lines.filter { it.contains(marker) }
        assertEquals("两个入口各只许产生一行（改前各两行）", 2, hits.size)
        hits.forEach {
            assertFalse("不得残留真实换行: $it", it.contains('\n'))
            assertTrue("换行折成字面量: $it", it.contains("\\n"))
        }
    }

    /** 多行输入只折成一行 —— 行数与输入里的换行数量无关。 */
    @Test
    fun f4_multiLineInputStillYieldsExactlyOneLine() {
        val id = "inst-f4-multi-${System.nanoTime()}"
        val before = DshLogBus.snapshotFor(id).value.lines.size
        DshLogBus.appendFor(id, MARKER + "l1\nl2\nl3\nl4")
        DshLogBus.flushNow()
        val added = DshLogBus.snapshotFor(id).value.lines.drop(before)
        assertEquals("4 段输入仍只有 1 行", 1, added.size)
        assertFalse("行内不得残留真实换行（落盘时才不会裂成 4 行）", added[0].contains('\n'))
        listOf("l1", "l2", "l3", "l4").forEach {
            assertTrue("各段内容都要保留: $it", added[0].contains(it))
        }
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private companion object {
        /** 唯一标记：日志总线是进程级单例，靠它把"本次用例产生的行"与别的用例区分开 */
        const val MARKER = "<<r31f4>>"

        /**
         * 把 [DshPaths] 指到临时目录，跑 [block]，**无条件**复原。
         *
         * 只写 `DshPaths.instanceDir` 用得到的 `INSTANCES_DIR` 与 `isLoaded`（其余字段保持原值，
         * 改动面越小越好）。反射的原因见类注释：`lateinit var ... private set` + 无 Android Context。
         */
        fun WITH_PATHS(root: File, block: () -> Unit) {
            val instances = File(root, "instances")
            if (!instances.isDirectory) assertTrue("前置：建 instances/", instances.mkdirs())
            val saved = HashMap<String, Any?>()
            fun field(name: String) = DshPaths::class.java.getDeclaredField(name).apply { isAccessible = true }
            fun set(name: String, value: Any?) {
                val f = field(name)
                if (name !in saved) saved[name] = runCatching { f.get(DshPaths) }.getOrNull()
                f.set(DshPaths, value)
            }
            set("INSTANCES_DIR", instances.absolutePath)
            set("isLoaded", true)
            try {
                block()
            } finally {
                saved.forEach { (name, value) ->
                    runCatching { field(name).set(DshPaths, value) }
                }
            }
        }
    }
}
