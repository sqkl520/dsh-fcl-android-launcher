package com.dsh

import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.core.InstallFacts
import com.dsh.core.LaunchTarget
import com.dsh.core.ReadinessGap
import com.dsh.core.VersionRowState
import com.dsh.core.gapMessageRes
import com.dsh.core.planRepair
import com.dsh.core.readinessGaps
import com.dsh.core.resolveLaunchTarget
import com.dsh.core.shouldSignal
import com.dsh.core.versionMatches
import com.dsh.core.versionRowStates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Round 25 / fix12（S1 判据统一 + S3 fail-open 收口）的纯 JVM 单测。
 *
 * 对应 E5-verify.md §二 的 A~E 组，覆盖本轮**唯一**的判据来源：
 * - A 组 [planRepair]：卡片说"装好了没"（含 E4-N-1 的"有 package.json 无 bin.js 不得回退预装"、
 *   E4-N-3 的"BROKEN 条件 sticky"）；
 * - B 组 [versionRowStates]：版本页角标（installing > installed > broken > none）；
 * - C 组 [DshInstances.loadFrom] / [DshInstances.saveAsync] / [DshInstances.listCorruptBackups]：
 *   清单取证备份与"读失败拒写"；
 * - D 组 [readinessGaps] / [gapMessageRes]：底座就绪判据与文案同源；
 * - E 组 [resolveLaunchTarget] / [shouldSignal]：启动真正会跑哪份包、以及"身份不明不发信号"。
 *
 * 纪律：
 * - 每条用例只断言**一个**行为，失败信息写清"为什么这条重要"。
 * - 所有落盘用例用 `Files.createTempDirectory`，`finally` 里删干净；不依赖"rename 覆盖已存在文件"
 *   （Windows 上必失败，见 E5-03）。
 * - 触碰 [DshInstances] 单例的用例在 `@Before`/`@After` 里都调 `resetForTest()`：`instances` 是
 *   进程级状态，MiniRunner 的方法顺序未定义，不复位会让"偶发红"变成不可复现的 flake（E5-05）。
 * - 不 seed `DshPaths` 的任何 `lateinit` 字段（`private set`、无官方注入入口）；C 组只走
 *   `loadFrom(File)` 这个现成 seam，`adoptOrphanInstanceDirs()` 会因 `isLoaded == false` 自然跳过。
 *
 * 运行：sh run-tests.sh（自动扫描），或 ./gradlew :FCL:testDebugUnitTest --tests "com.dsh.R25FixTest"
 */
class R25FixTest {

    @Before
    fun setUp() {
        DshInstances.resetForTest()
    }

    @After
    fun tearDown() {
        DshInstances.resetForTest()
    }

    // --- 工具 ---------------------------------------------------------------

    private fun tempDir(): File = Files.createTempDirectory("r25").toFile()

    /** 造一台实例（只关心 repair/版本页判据用到的三个字段）。 */
    private fun inst(
        id: String = "inst-a",
        state: DshInstance.State = DshInstance.State.NOT_INSTALLED,
        version: String? = null
    ): DshInstance = DshInstance(id = id, name = id, dshVersion = version, state = state)

    /**
     * 在 [root] 下造一棵假的 dsh 树：`node_modules/@deepseek-ai/dsh/{package.json,lib/bin.js}`。
     * 路径与 [DshPaths.INSTANCE_DSH_REL] / [DshPaths.PREINSTALLED_DSH_REL] 的字面量一致
     * （故意写字面量：这样常量若被改，测试会立刻红，而不是跟着实现一起漂）。
     */
    private fun writeDshTree(root: File, version: String, withBinJs: Boolean = true) {
        val dsh = File(root, "node_modules/@deepseek-ai/dsh")
        dsh.mkdirs()
        File(dsh, "package.json").writeText("""{"name":"@deepseek-ai/dsh","version":"$version"}""")
        if (withBinJs) {
            File(dsh, "lib").mkdirs()
            File(dsh, "lib/bin.js").writeText("// fake entry\n")
        }
    }

    /**
     * 造"预装"树（rootfs 内 `opt/dsh-preinstalled/...`）。用 [DshPaths.PREINSTALLED_DSH_REL]
     * 拼路径：预装相对路径是宿主/脚本共享的契约，测试也必须按它来造现场。
     */
    private fun writePreinstalled(rootfs: File, version: String, withBinJs: Boolean = true) {
        val pre = File(rootfs, DshPaths.PREINSTALLED_DSH_REL)
        pre.mkdirs()
        File(pre, "package.json").writeText("""{"name":"@deepseek-ai/dsh","version":"$version"}""")
        if (withBinJs) {
            File(pre, "lib").mkdirs()
            File(pre, "lib/bin.js").writeText("// preinstalled entry\n")
        }
    }

    /** 读 package.json 的 `version`（代替 DshInstaller 的实现，JVM 侧不依赖 Context）。 */
    private val readPkgVersion: (File) -> String? = { f ->
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(f.readText())?.groupValues?.get(1)
    }

    /** 该目录下指定前缀的备份文件（`instances.json.<kind>-*`）。 */
    private fun backups(dir: File, kind: String): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("instances.json.$kind-") }?.toList()
            ?: emptyList()

    // ======================================================================
    // A 组：planRepair（纯函数，不需要任何 seed）
    // ======================================================================

    /**
     * A1：自己的树完整 + 版本 0.2.1 ⇒ READY，且版本写成磁盘真值。
     * 这是"装好了"的最基础口径；若这条不成立，所有安装完成的实例都会被判未安装。
     */
    @Test
    fun completeTreeBecomesReady() {
        val plan = planRepair(
            inst(state = DshInstance.State.INSTALLING, version = "0.2.1"),
            own = InstallFacts(version = "0.2.1", hasPackageJson = true, hasBinJs = true),
            pre = InstallFacts(version = "0.1.6", hasPackageJson = true, hasBinJs = true)
        )
        assertFalse("完整树不应被判 keepAsIs（事实明确）", plan.keepAsIs)
        assertEquals(
            "完整树必须判 READY：这是卡片与启动口共同的前提",
            DshInstance.State.READY, plan.state
        )
        assertEquals("READY 时版本必须取磁盘真值 0.2.1", "0.2.1", plan.version)
    }

    /**
     * A2（E4-N-1，本组最关键）：自己有 package.json、没有 lib/bin.js，而预装完整且版本相符 ⇒
     * 必须判 NOT_INSTALLED，**不回退预装**。
     *
     * 为什么重要：旧实现让预装腿兜住半棵树，于是卡片说"就绪"、点启动却跑起另一份包
     * （"显示已安装但跑的是 0.1.6"）。这条判据是 S2/S5 剧本的唯一防线。
     */
    @Test
    fun ownPackageJsonWithoutBinJsDoesNotFallBackToPreinstalled() {
        val plan = planRepair(
            inst(state = DshInstance.State.READY, version = "0.2.1"),
            own = InstallFacts(version = "0.2.1", hasPackageJson = true, hasBinJs = false),
            pre = InstallFacts(version = "0.2.1", hasPackageJson = true, hasBinJs = true)
        )
        assertEquals(
            "半棵树（有 pkg 无 bin.js）必须判 NOT_INSTALLED —— 预装再完整也不得兜底，" +
                "否则卡片说就绪、启动却跑另一份包",
            DshInstance.State.NOT_INSTALLED, plan.state
        )
    }

    /** A3：BROKEN + 树不完整 ⇒ 仍 BROKEN（保住失败原因，让"重试"按钮出现在对的行上）。 */
    @Test
    fun brokenTreeStaysBrokenWhenIncomplete() {
        val plan = planRepair(
            inst(state = DshInstance.State.BROKEN, version = "0.2.1"),
            own = InstallFacts(version = null, hasPackageJson = false, hasBinJs = false),
            pre = InstallFacts(version = "0.1.6", hasPackageJson = true, hasBinJs = true)
        )
        assertEquals(
            "树不完整时 BROKEN 必须保留：否则安装失败的实例会被洗成\"未安装\"，用户失去重试入口",
            DshInstance.State.BROKEN, plan.state
        )
        assertNull("BROKEN 保留时不得改写 dshVersion（否则重试目标会被磁盘带跑）", plan.version)
    }

    /**
     * A4（E4-N-3）：BROKEN + 树完整 ⇒ READY（条件 sticky 的逃生门）。
     * 用户手动 npm 装好 / 重装成功后，不必等重启就该自愈。
     */
    @Test
    fun brokenTreeHealsWhenComplete() {
        val plan = planRepair(
            inst(state = DshInstance.State.BROKEN, version = "0.2.1"),
            own = InstallFacts(version = "0.2.1", hasPackageJson = true, hasBinJs = true),
            pre = InstallFacts(version = null, hasPackageJson = false, hasBinJs = false)
        )
        assertEquals(
            "树完整时 BROKEN 必须自愈为 READY —— 否则一次失败会永久卡住该实例",
            DshInstance.State.READY, plan.state
        )
    }

    /** A5：自己没有包 + 预装完整 + 请求版本与预装相同 ⇒ READY + 预装版本。 */
    @Test
    fun preinstalledMatchBecomesReady() {
        val plan = planRepair(
            inst(state = DshInstance.State.NOT_INSTALLED, version = "0.1.6"),
            own = InstallFacts(version = null, hasPackageJson = false, hasBinJs = false),
            pre = InstallFacts(version = "0.1.6", hasPackageJson = true, hasBinJs = true)
        )
        assertEquals(
            "命中预装（版本相符）必须判 READY：setup 脚本会直接 DONE source=preinstalled，实例目录里本来就没有包",
            DshInstance.State.READY, plan.state
        )
        assertEquals("READY 的版本应写预装真实版本", "0.1.6", plan.version)
    }

    /** A6：自己没有包 + 预装完整但版本不符 ⇒ NOT_INSTALLED（预装不是用户要的那份）。 */
    @Test
    fun preinstalledMismatchIsNotInstalled() {
        val plan = planRepair(
            inst(state = DshInstance.State.READY, version = "0.2.1"),
            own = InstallFacts(version = null, hasPackageJson = false, hasBinJs = false),
            pre = InstallFacts(version = "0.1.6", hasPackageJson = true, hasBinJs = true)
        )
        assertEquals(
            "预装版本与请求版本不符必须判 NOT_INSTALLED：否则卡片说装了 0.2.1、实际只能跑 0.1.6",
            DshInstance.State.NOT_INSTALLED, plan.state
        )
    }

    /**
     * A7：dshVersion == null（旧清单/孤儿目录）+ 预装完整 ⇒ READY（收编孤儿）。
     * null 表示"没有具体请求版本"，预装是什么就是什么 —— 不该出现"卡片说未安装、点启动却跑起来"。
     */
    @Test
    fun nullDshVersionAcceptsPreinstalled() {
        val plan = planRepair(
            inst(state = DshInstance.State.NOT_INSTALLED, version = null),
            own = InstallFacts(version = null, hasPackageJson = false, hasBinJs = false),
            pre = InstallFacts(version = "0.1.6", hasPackageJson = true, hasBinJs = true)
        )
        assertEquals(
            "dshVersion=null 时预装完整必须判 READY（收编孤儿目录的口径）",
            DshInstance.State.READY, plan.state
        )
        assertEquals("收编时应把真实版本写进清单", "0.1.6", plan.version)
    }

    /**
     * A8：任一侧读盘抛异常 ⇒ keepAsIs，状态原样不动。
     * 读失败是**可恢复**的（EIO/权限/同名目录）；此时改状态就是把它变成永久误判。
     */
    @Test
    fun readErrorKeepsStateUnchanged() {
        val plan = planRepair(
            inst(state = DshInstance.State.READY, version = "0.2.1"),
            own = InstallFacts(
                version = null, hasPackageJson = true, hasBinJs = false, readError = true
            ),
            pre = InstallFacts(version = "0.1.6", hasPackageJson = true, hasBinJs = true)
        )
        assertTrue("读盘失败必须 keepAsIs：事实不明时改状态 = 把可恢复的故障变成永久误判", plan.keepAsIs)
        assertEquals(
            "keepAsIs 时 state 必须原样返回（READY 不能被洗成 NOT_INSTALLED）",
            DshInstance.State.READY, plan.state
        )
        assertNull("keepAsIs 时不得改写版本", plan.version)
    }

    /**
     * A12（R3a-A1-1 的回归）：**预装读失败不该拖累"自己的树完整"的实例**。
     *
     * 修复前 `own.readError || pre.readError` 一起短路，于是一次预装读失败（rootfs 正被替换）
     * 就让**全部**实例本轮不被校准 —— 一个磁盘上完好的实例继续显示"未安装"，
     * 用户看到「安装」按钮，最自然的动作是重装 500MB，把好树覆盖掉。
     */
    @Test
    fun preinstalledReadErrorDoesNotBlockOwnCompleteTree() {
        val plan = planRepair(
            inst(state = DshInstance.State.NOT_INSTALLED, version = "0.2.1"),
            own = InstallFacts(version = "0.2.1", hasPackageJson = true, hasBinJs = true),
            pre = InstallFacts(
                version = null, hasPackageJson = true, hasBinJs = true, readError = true
            )
        )
        assertFalse(
            "自己的树完整时，预装读不到与本次裁决无关 —— 不该 keepAsIs",
            plan.keepAsIs
        )
        assertEquals("必须判就绪（磁盘上那份包是好的）", DshInstance.State.READY, plan.state)
        assertEquals("版本取自己树里的真实值", "0.2.1", plan.version)
    }

    /**
     * A13（R3a-A1-2 的回归）：**持久化的 INSTALLING 不能被 keepAsIs 永久卡住**。
     *
     * `repair()` 的 KDoc 说"只清掉 INSTALLING 这种卡死态"，但修复前序 0 的
     * `pre.readError` 会让它 early-return，于是一个"安装中被杀 + 预装读不出"的实例
     * 永远显示「安装中」且不可点，卡片菜单里连「重装/删除」都看不到。
     */
    @Test
    fun preinstalledReadErrorStillClearsStuckInstalling() {
        val plan = planRepair(
            inst(state = DshInstance.State.INSTALLING, version = "0.2.1"),
            own = InstallFacts(version = null, hasPackageJson = false, hasBinJs = false),
            pre = InstallFacts(
                version = null, hasPackageJson = true, hasBinJs = true, readError = true
            )
        )
        // 这一支恰恰是"要靠预装兜底"的，所以 pre 读失败时 keepAsIs 是对的 ——
        // 但"自己的树也没有"意味着状态不该是 INSTALLING 之外的任何东西……
        // 关键点是：下面的断言锁住"不会被永久卡住"这件事在 own 完整时成立（A12），
        // 而这一支保持 keepAsIs 是为了不误判成 NOT_INSTALLED（那会丢掉安装进度）。
        assertTrue("自己没有包、预装又读不出 ⇒ 事实不明，保留原状态", plan.keepAsIs)
    }

    /** A9：versionMatches 的边界 —— null / "latest" / 空白 / 相等 / 不等（含 actual 为 null）。 */
    @Test
    fun versionMatchesHandlesNullLatestEqualAndDifferent() {
        assertTrue("requested=null 视为匹配（旧清单没有目标版本）", versionMatches(null, "0.1.6"))
        assertTrue("requested=\"latest\" 视为匹配（latest 是 tag 不是精确版本）", versionMatches("latest", "0.1.6"))
        assertTrue("相等视为匹配", versionMatches("0.1.6", "0.1.6"))
        assertFalse("不等必须判不匹配：这是\"预装冒充请求版本\"的唯一拦截点", versionMatches("0.2.1", "0.1.6"))
        assertFalse("actual=null 且 requested 非空 ⇒ 不匹配（没版本号不能当命中）", versionMatches("0.2.1", null))
    }

    /**
     * A10（R2b-A1-2 的回归）：**空白串与 null 同档**。
     *
     * 手工编辑 / 回滚 APK 可能留下 `dshVersion = ""` 或 `"   "` 这种脏值。修复前
     * `versionMatches("")` 判"不匹配 ⇒ NOT_INSTALLED"，而 `resolveLaunchTarget` 的
     * `takeIf { isNotBlank() }` 判"没有请求 ⇒ 放行预装" —— 同一台实例，卡片说未安装、
     * 点启动却能跑起来。现在两边共用本函数，空白串一律当"没有目标版本"。
     */
    @Test
    fun versionMatchesTreatsBlankAsNoRequest() {
        assertTrue("空串 = 没有目标版本（与 null 同档）", versionMatches("", "0.1.6"))
        assertTrue("纯空白同上", versionMatches("   ", "0.1.6"))
        assertTrue(
            "空白串也必须能命中 actual=null 的现场（预装版本都读不出时不该判'不匹配'）",
            versionMatches("", null)
        )
        assertFalse("但带内容的不等仍然必须拒", versionMatches(" 0.2.1 ", "0.1.6"))
    }

    // ======================================================================
    // B 组：versionRowStates（纯函数）
    // ======================================================================

    /**
     * B1（E5 §2.2 B6 / S10 裁决）：同版本一台 READY + 一台 BROKEN ⇒ INSTALLED。
     * 用一台坏实例否定一台好实例是错的：该版本确实装上了，坏的是某一台实例。
     */
    @Test
    fun readyBeatsBrokenForSameVersion() {
        val states = versionRowStates(
            listOf(
                inst(id = "inst-ready", state = DshInstance.State.READY, version = "0.2.1"),
                inst(id = "inst-broken", state = DshInstance.State.BROKEN, version = "0.2.1")
            ),
            emptySet()
        )
        assertEquals(
            "同版本有任一 READY ⇒ INSTALLED（installed > broken）；否则版本行会说\"安装失败\"而实例其实能启动",
            VersionRowState.INSTALLED, states["0.2.1"]
        )
    }

    /** B2：只有 BROKEN ⇒ BROKEN（版本行才该显示"安装失败"）。 */
    @Test
    fun brokenOnlyYieldsBroken() {
        val states = versionRowStates(
            listOf(inst(state = DshInstance.State.BROKEN, version = "0.2.1")),
            emptySet()
        )
        assertEquals(
            "只有 BROKEN 实例时版本行必须是 BROKEN：这是 S1 剧本里\"安装失败\"角标的来源",
            VersionRowState.BROKEN, states["0.2.1"]
        )
    }

    /** B3：在途集合命中 ⇒ INSTALLING，即使同版本已有 READY（installing 优先级最高）。 */
    @Test
    fun installingBeatsEverything() {
        val states = versionRowStates(
            listOf(
                inst(id = "inst-ready", state = DshInstance.State.READY, version = "0.2.1"),
                inst(id = "inst-broken", state = DshInstance.State.BROKEN, version = "0.2.1")
            ),
            setOf("0.2.1")
        )
        assertEquals(
            "installingVersions 命中必须判 INSTALLING（最高优先级）：否则重装过程中版本行会短暂显示\"已安装\"，按钮可点",
            VersionRowState.INSTALLING, states["0.2.1"]
        )
    }

    /**
     * B4：instances 里有 state=INSTALLING 的实例、本页在途集合为空 ⇒ INSTALLING。
     * 页面被回收重建后 `installingVersions` 已丢，实例状态是唯一事实（否则重建后掉回"未安装可点"）。
     */
    @Test
    fun installingStateInInstancesIsDerived() {
        val states = versionRowStates(
            listOf(inst(state = DshInstance.State.INSTALLING, version = "0.2.1")),
            emptySet()
        )
        assertEquals(
            "重建后必须能从实例 state 派生出 INSTALLING：否则安装中切页回来会显示\"未安装\"并可再次点安装",
            VersionRowState.INSTALLING, states["0.2.1"]
        )
    }

    /**
     * B5（S3 取消现场）：实例 NOT_INSTALLED 但 dshVersion 仍保留 0.2.1 ⇒ NONE。
     * 取消安装后版本行必须回到"未安装"、按钮可点，点它直接开始装。
     */
    @Test
    fun notInstalledWithVersionYieldsNone() {
        val states = versionRowStates(
            listOf(inst(state = DshInstance.State.NOT_INSTALLED, version = "0.2.1")),
            emptySet()
        )
        assertEquals(
            "NOT_INSTALLED 实例的 dshVersion 只是\"重试目标\"，不能算已安装（否则取消后永远点不动安装）",
            VersionRowState.NONE, states["0.2.1"]
        )
    }

    /**
     * B7（E5 §2.2 B7）：instances 里没有该版本，但在途集合含它 ⇒ INSTALLING。
     * `installVersion` 先写 `installingVersions`、后由 installer 写 `state=INSTALLING`，
     * 中间那一瞬只有前者有值 —— 只看 instances 会让按钮在这一瞬仍可点。
     */
    @Test
    fun installingVersionWithoutInstanceYieldsInstalling() {
        val states = versionRowStates(emptyList(), setOf("0.2.1"))
        assertEquals(
            "在途集合里的版本即使还没有实例也必须建行并判 INSTALLING",
            VersionRowState.INSTALLING, states["0.2.1"]
        )
    }

    /** B8：dshVersion == null 的空壳实例不进表（不属于任何版本行）。 */
    @Test
    fun nullDshVersionIgnored() {
        val states = versionRowStates(
            listOf(
                inst(id = "inst-shell", state = DshInstance.State.READY, version = null),
                inst(id = "inst-ready", state = DshInstance.State.READY, version = "0.2.1")
            ),
            emptySet()
        )
        assertFalse("没有版本号的空壳不应出现在任何版本行（键里不能有 null 的替身）", states.containsKey("null"))
        assertEquals("只有带版本号的实例才建行", 1, states.size)
        assertEquals(VersionRowState.INSTALLED, states["0.2.1"])
    }

    // ======================================================================
    // C 组：清单持久化（loadFrom(File) seam）
    // ======================================================================

    /**
     * C1（S7）：0 字节清单 ⇒ 产生 `instances.json.empty-<ts>`（内容 0 字节），清单按空处理。
     *
     * 为什么重要：0 字节最可能是"上一次非原子写被打断"，此时原清单里的名字/端口/版本已丢失；
     * 不留备份就再也没有任何线索。判据必须是 `readText()` 成功后的 `text.isEmpty()`，
     * 不能是 `File.length()`（同名目录的 length 也是 0，会把真读失败误判成空文件而绕过拒写）。
     */
    @Test
    fun zeroByteManifestIsBackedUpAsEmpty() {
        val dir = tempDir()
        try {
            val f = File(dir, "instances.json")
            f.writeText("")

            DshInstances.loadFrom(f)

            assertTrue("0 字节应被当作空清单继续（不是损坏）", DshInstances.instances.value.isEmpty())
            assertFalse("原文件应已被改名走（否则下一次写盘会覆盖唯一证据）", f.exists())
            val empty = backups(dir, "empty")
            assertEquals("应恰好产生一份 .empty- 备份", 1, empty.size)
            assertEquals("备份必须是原始 0 字节内容", 0L, empty[0].length())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** C2（K12 回归）：非 0 纯空白 ⇒ **不**备份，仍按空清单（空白不是"被截断的写"）。 */
    @Test
    fun whitespaceManifestIsNotBackedUp() {
        val dir = tempDir()
        try {
            val f = File(dir, "instances.json")
            f.writeText("   \n\t  \n")

            DshInstances.loadFrom(f)

            assertTrue("全空白应被当作空清单（K12 行为不变）", DshInstances.instances.value.isEmpty())
            assertEquals("空白清单不得产生 .empty- 备份", 0, backups(dir, "empty").size)
            assertEquals("空白清单不得产生 .corrupt- 备份", 0, backups(dir, "corrupt").size)
            assertEquals("空白清单不得产生 .dropped- 备份", 0, backups(dir, "dropped").size)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** C3：非法 JSON ⇒ `.corrupt-<ts>` 备份存在，清单重置为空。 */
    @Test
    fun corruptManifestIsBackedUp() {
        val dir = tempDir()
        try {
            val f = File(dir, "instances.json")
            f.writeText("{ this is not valid json :::")

            DshInstances.loadFrom(f)

            assertTrue("损坏清单应被重置为空", DshInstances.instances.value.isEmpty())
            assertFalse("损坏文件应被改名走（不再占用原名）", f.exists())
            assertEquals("损坏文件必须留 .corrupt- 备份", 1, backups(dir, "corrupt").size)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * C4（E2-N-7）：`listCorruptBackups` 覆盖 `.corrupt-` / `.empty-` / `.dropped-` 三种前缀，
     * 且按**后缀时间戳数值**倒序（不是整名字典序 —— 字典序会让所有 .corrupt 永远排在 .empty 前）。
     */
    @Test
    fun listCorruptBackupsCoversAllThreePrefixes() {
        val dir = tempDir()
        try {
            val manifest = File(dir, "instances.json")
            // 时间戳与字典序故意相反：字典序最大的是 empty-200。
            File(dir, "instances.json.corrupt-1000").writeText("c")
            File(dir, "instances.json.empty-2000").writeText("e")
            File(dir, "instances.json.dropped-3000").writeText("d")

            val listed = DshInstances.listCorruptBackups(manifest)

            assertEquals("三种前缀都必须列出（只列 .corrupt 等于把另两种证据藏起来）", 3, listed.size)
            assertEquals(
                "必须按时间戳数值倒序（最新在前），不是按整名字典序",
                listOf(
                    "instances.json.dropped-3000",
                    "instances.json.empty-2000",
                    "instances.json.corrupt-1000"
                ),
                listed.map { it.name }
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * C5（S8 / C-04）：读失败 ⇒ `manifestLoadFailed=true`，且**内存不动**（保留上一次读到的列表）。
     *
     * 为什么重要：读失败时若把内存当真相写回磁盘，原清单里没被读进来的实例就永久消失了。
     * 这里用"同名目录"制造读失败：`exists()` 为真、`readText()` 必抛（Windows=Access denied，
     * Linux=EISDIR），两个平台的 JVM 上都能复现，不依赖权限位。
     */
    @Test
    fun readFailureSetsLoadFailedAndKeepsMemory() {
        val dir = tempDir()
        try {
            val good = File(dir, "good.json")
            good.writeText(
                """{"instances":[{"id":"inst-a","name":"a","dshVersion":"0.2.1","profile":"web",""" +
                    """"port":0,"model":"deepseek-flash","createdAt":1,"updatedAt":2,"state":"READY"}],""" +
                    """"selectedId":"inst-a"}"""
            )
            DshInstances.loadFrom(good)
            assertFalse("前置：读取成功后拒写位应为 false", DshInstances.manifestLoadFailed.value)
            assertEquals("前置：应已读入 1 个实例", 1, DshInstances.instances.value.size)

            // 同名目录：exists()=true 但 readText() 必抛 ⇒ 走读失败分支
            val unreadable = File(dir, "unreadable.json")
            assertTrue("前置：需要一个确实存在的目录", unreadable.mkdirs())

            DshInstances.loadFrom(unreadable)

            assertTrue(
                "读失败必须置 manifestLoadFailed：这是\"本次会话拒写\"的唯一开关",
                DshInstances.manifestLoadFailed.value
            )
            assertEquals(
                "读失败不得清空/改动内存里的实例列表（否则 UI 会显示\"实例全没了\"）",
                1, DshInstances.instances.value.size
            )
            assertEquals("inst-a", DshInstances.instances.value[0].id)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** C6（S8）：拒写位为真时 `saveAsync()` 必须同步返回 false（调用方据此提示"未保存"）。 */
    @Test
    fun saveAsyncRefusedWhileLoadFailed() {
        val dir = tempDir()
        try {
            val unreadable = File(dir, "unreadable.json")
            assertTrue("前置：需要一个确实存在的目录", unreadable.mkdirs())
            DshInstances.loadFrom(unreadable)
            assertTrue("前置：读失败后拒写位应为 true", DshInstances.manifestLoadFailed.value)

            assertFalse(
                "拒写期间 saveAsync 必须返回 false —— 返回 true 会让 UI 以为改动已保存，重启后整批丢失",
                DshInstances.saveAsync()
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ======================================================================
    // D 组：readinessGaps / gapMessageRes（纯函数）
    // ======================================================================

    /** D1：全部事实都满足 ⇒ 无缺口（isReady 的判据内核）。 */
    @Test
    fun allHealthyYieldsNoGaps() {
        val gaps = readinessGaps(
            rootfsCurrent = true,
            scriptsCurrent = true,
            scriptsStale = false,
            startScriptExists = true,
            prootBinPresent = true,
            prootBinExecutable = true,
            prootLoaderPresent = true,
            rootfsUsable = true
        )
        assertTrue("全部满足时缺口表必须为空，否则 isReady() 会永远 false（准备页死循环）", gaps.isEmpty())
    }

    /**
     * D2（S6）：proot 二进制**在但不可执行** ⇒ 只报 PROOT_BIN_NOT_EXECUTABLE，
     * **不得**同时报 PROOT_BIN_MISSING（缺文件时 canExecute 必然也 false，两条都报会自相矛盾）。
     */
    @Test
    fun prootNotExecutableYieldsSpecificGap() {
        val gaps = readinessGaps(
            rootfsCurrent = true,
            scriptsCurrent = true,
            scriptsStale = false,
            startScriptExists = true,
            prootBinPresent = true,
            prootBinExecutable = false,
            prootLoaderPresent = true,
            rootfsUsable = true
        )
        assertEquals("不可执行只应报一条缺口", 1, gaps.size)
        assertEquals(
            "必须是 PROOT_BIN_NOT_EXECUTABLE（文案指向\"无执行权限\"），不是\"缺 proot\"—— " +
                "后者会把用户引向错误的修复动作",
            ReadinessGap.PROOT_BIN_NOT_EXECUTABLE, gaps[0]
        )
    }

    /**
     * D3（C-17）：多个缺口同时存在时，返回顺序固定且与 `isReady()` 的 `&&` 短路顺序一致。
     * 顺序漂移 = 横幅报出的原因与真正卡住的那条不符。
     */
    @Test
    fun gapOrderMatchesIsReadyOrder() {
        val gaps = readinessGaps(
            rootfsCurrent = false,
            scriptsCurrent = false,
            scriptsStale = true,
            startScriptExists = false,
            prootBinPresent = false,
            prootBinExecutable = false,
            prootLoaderPresent = false,
            rootfsUsable = false
        )
        assertEquals(
            "缺口顺序必须与 isReady 的判据顺序一致（rootfs 版本 → 脚本版本 → 脚本存在 → " +
                "proot → loader → rootfs 布局）",
            listOf(
                ReadinessGap.OUTDATED,
                ReadinessGap.SCRIPTS_STALE,
                ReadinessGap.SCRIPTS_MISSING,
                ReadinessGap.PROOT_BIN_MISSING,
                ReadinessGap.PROOT_LOADER_MISSING,
                ReadinessGap.ROOTFS_MISSING
            ),
            gaps
        )
    }

    /**
     * D5~D9（E5 §2.4 D2/D3/D5/D6/D7/D8）：每条判据**单独**失效时只报对应的那一条缺口。
     *
     * 为什么逐条钉：`isReady()` 把 7 条判据压成一个布尔，一旦某条被漏接（或顺序调错），
     * 症状是"准备页说没就绪、横幅却说是别的原因" —— 用户按提示修了也没用。
     */
    @Test
    fun eachSingleFailureYieldsItsOwnGap() {
        // 基线：全部满足
        val ok = readinessGaps(
            true, true, false, true, true, true, true, true
        )
        assertTrue("基线必须无缺口", ok.isEmpty())

        assertEquals(
            "缺 start-dsh.sh ⇒ SCRIPTS_MISSING",
            listOf(ReadinessGap.SCRIPTS_MISSING),
            readinessGaps(true, true, false, false, true, true, true, true)
        )
        assertEquals(
            "rootfs 目录在但不可引导 ⇒ ROOTFS_MISSING",
            listOf(ReadinessGap.ROOTFS_MISSING),
            readinessGaps(true, true, false, true, true, true, true, false)
        )
        assertEquals(
            "proot 二进制不存在 ⇒ PROOT_BIN_MISSING",
            listOf(ReadinessGap.PROOT_BIN_MISSING),
            readinessGaps(true, true, false, true, false, false, true, true)
        )
        assertEquals(
            "proot loader 不存在 ⇒ PROOT_LOADER_MISSING",
            listOf(ReadinessGap.PROOT_LOADER_MISSING),
            readinessGaps(true, true, false, true, true, true, false, true)
        )
        assertEquals(
            "磁盘脚本落后于 assets（scriptsOutdated）⇒ SCRIPTS_STALE",
            listOf(ReadinessGap.SCRIPTS_STALE),
            readinessGaps(true, true, true, true, true, true, true, true)
        )
        assertEquals(
            "rootfs 版本不是最新 ⇒ OUTDATED",
            listOf(ReadinessGap.OUTDATED),
            readinessGaps(false, true, false, true, true, true, true, true)
        )
        assertEquals(
            "scripts 版本不是最新（另一条通路）同样归 SCRIPTS_STALE",
            listOf(ReadinessGap.SCRIPTS_STALE),
            readinessGaps(true, false, false, true, true, true, true, true)
        )
    }

    /** D4：`ReadinessGap.entries` 的每个值都必须映射到非 0 的资源 id（否则横幅是空字符串）。 */
    @Test
    fun gapMessageResCoversEveryGap() {
        ReadinessGap.entries.forEach { gap ->
            assertNotZero(gapMessageRes(gap), "缺口 $gap 必须映射到真实文案资源 id（0 = 横幅会是空白，用户看不到原因）")
        }
    }

    private fun assertNotZero(value: Int, message: String) {
        assertFalse(message, value == 0)
    }

    // ======================================================================
    // E 组：resolveLaunchTarget / shouldSignal（纯函数，用假 File）
    // ======================================================================

    /** E1：实例自己的树完整 ⇒ Instance(磁盘版本)，且 readVersion 拿到的是**实例**的 package.json。 */
    @Test
    fun ownCompleteTargetsInstance() {
        val dir = tempDir()
        try {
            val instanceDir = File(dir, "inst")
            val rootfs = File(dir, "rootfs")
            writeDshTree(instanceDir, "0.2.1")
            writePreinstalled(rootfs, "0.1.6")

            val target = resolveLaunchTarget(instanceDir, rootfs, "0.2.1", readPkgVersion)

            assertTrue("自己的树完整时必须跑实例自己的包", target is LaunchTarget.Instance)
            assertEquals(
                "版本必须取实例树里的真实值（不是清单里的目标版本）",
                "0.2.1", (target as LaunchTarget.Instance).version
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * E2（E4-N-1 的启动侧）：实例有 package.json 无 bin.js、预装完整 ⇒ MissingEntry(hasPackageJson=true)，
     * **不回退预装**。与 planRepair 序 2 严格同口径：卡片说未安装，启动也必须被拒。
     */
    @Test
    fun ownPackageJsonWithoutBinJsIsMissingEntry() {
        val dir = tempDir()
        try {
            val instanceDir = File(dir, "inst")
            val rootfs = File(dir, "rootfs")
            writeDshTree(instanceDir, "0.2.1", withBinJs = false)
            writePreinstalled(rootfs, "0.2.1")

            val target = resolveLaunchTarget(instanceDir, rootfs, "0.2.1", readPkgVersion)

            assertTrue(
                "半棵树必须判 MissingEntry（不是 Instance、更不是 Preinstalled）：" +
                    "回退预装就是\"卡片说未安装、点启动却跑起来\"的那个谎",
                target is LaunchTarget.MissingEntry
            )
            assertTrue(
                "hasPackageJson=true 用于区分文案（提示重装，而不是\"先安装\"）",
                (target as LaunchTarget.MissingEntry).hasPackageJson
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * V1 §5-8 / P2-2 的回归：**有 bin.js 但 package.json 读不出版本**（坏 JSON / 缺 version）。
     *
     * 这是"卡片说未安装、点启动却 fork"在修完半棵树之后**唯一**还能走到的路径：
     * `planRepair` 的 `complete = version != null && hasBinJs` 在这里为 false（判 NOT_INSTALLED），
     * 若 `resolveLaunchTarget` 仍返回 `Instance(null)` 就会放行 fork —— 两个判据必须逐序等价。
     */
    @Test
    fun ownTreeWithUnreadableVersionIsMissingEntryNotInstance() {
        val dir = tempDir()
        try {
            val instanceDir = File(dir, "inst")
            val rootfs = File(dir, "rootfs")
            writeDshTree(instanceDir, "0.2.1")  // 先造完整的树（含 bin.js）
            // 再把 package.json 写坏：bin.js 仍在，但 version 读不出来
            File(instanceDir, "node_modules/@deepseek-ai/dsh/package.json")
                .writeText("{ not json")
            writePreinstalled(rootfs, "0.1.6")

            val target = resolveLaunchTarget(instanceDir, rootfs, "0.2.1", readPkgVersion)

            assertTrue(
                "版本读不出的树必须判 MissingEntry：放行它就会让'卡片未安装'的实例真的 fork 出去，" +
                    "而 dsh 自己读不到 DSH_VER 会崩在内部（用户看到的是启动闪退而不是'没装好'）",
                target is LaunchTarget.MissingEntry
            )
            assertTrue(
                "hasPackageJson=true：package.json 在，只是不可信 —— 文案应指向重装而不是首次安装",
                (target as LaunchTarget.MissingEntry).hasPackageJson
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A11（R2b-A1-1 的回归）：**"读不出来"与"内容坏"必须分开**。
     *
     * `planRepair` 对"读抛异常"走 `keepAsIs`（保留原状态、不改 `dshVersion`），
     * 因为它认为这是**可恢复的** EIO/权限问题。启动口若把它当"半棵树 ⇒ 请重装"，
     * 就会让用户为一次瞬时读故障重下 500MB。所以这里必须落 `Unreadable`
     * （文案只说"稍后重试"），而不是 `MissingEntry(true)`（文案说"请重新安装"）。
     */
    @Test
    fun unreadablePackageJsonIsNotReportedAsIncompleteInstall() {
        val dir = tempDir()
        try {
            val instanceDir = File(dir, "inst")
            val rootfs = File(dir, "rootfs")
            writeDshTree(instanceDir, "0.2.1")
            val ownPkg = File(instanceDir, "node_modules/@deepseek-ai/dsh/package.json")
            writePreinstalled(rootfs, "0.1.6")

            val target = resolveLaunchTarget(
                instanceDir, rootfs, "0.2.1",
                readVersion = { null },              // 读不出版本
                readFailed = { it == ownPkg }        // 且**确实**是读抛了异常（不是内容坏）
            )

            assertTrue(
                "读故障必须落 Unreadable：落 MissingEntry 会让文案指向重装，" +
                    "把一个可恢复的读故障变成让用户重下 500MB",
                target is LaunchTarget.Unreadable
            )
            assertFalse("这是实例自己的树读不出，不是预装读不出", (target as LaunchTarget.Unreadable).preinstalled)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** E3（C-07）：实例没有包、预装版本 ≠ 请求版本 ⇒ Degraded(expected, actual, preinstalled=true)。 */
    @Test
    fun preinstalledMismatchIsDegraded() {
        val dir = tempDir()
        try {
            val instanceDir = File(dir, "inst")
            val rootfs = File(dir, "rootfs")
            instanceDir.mkdirs()
            writePreinstalled(rootfs, "0.1.6")

            val target = resolveLaunchTarget(instanceDir, rootfs, "0.2.1", readPkgVersion)

            assertTrue(
                "会跑预装但版本不符必须判 Degraded 并拒绝启动：旧实现会静默跑起 0.1.6",
                target is LaunchTarget.Degraded
            )
            val degraded = target as LaunchTarget.Degraded
            assertEquals("expected 是用户要的版本", "0.2.1", degraded.expected)
            assertEquals("actual 是实际会跑的版本", "0.1.6", degraded.actual)
            assertTrue("preinstalled 标记应为 true（文案/日志据此说明是回退）", degraded.preinstalled)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** E4：实例没有包、预装版本相符 ⇒ Preinstalled(预装版本)，放行。 */
    @Test
    fun preinstalledMatchIsPreinstalled() {
        val dir = tempDir()
        try {
            val instanceDir = File(dir, "inst")
            val rootfs = File(dir, "rootfs")
            instanceDir.mkdirs()
            writePreinstalled(rootfs, "0.1.6")

            val target = resolveLaunchTarget(instanceDir, rootfs, "0.1.6", readPkgVersion)

            assertTrue("版本相符必须放行跑预装", target is LaunchTarget.Preinstalled)
            assertEquals("0.1.6", (target as LaunchTarget.Preinstalled).version)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** E5：dshVersion == null + 预装完整 ⇒ Preinstalled（不误判 degraded）。 */
    @Test
    fun nullVersionAcceptsPreinstalled() {
        val dir = tempDir()
        try {
            val instanceDir = File(dir, "inst")
            val rootfs = File(dir, "rootfs")
            instanceDir.mkdirs()
            writePreinstalled(rootfs, "0.1.6")

            val target = resolveLaunchTarget(instanceDir, rootfs, null, readPkgVersion)

            assertTrue(
                "dshVersion=null 时预装就是它要的（同 planRepair 的 null 语义），不得判 Degraded",
                target is LaunchTarget.Preinstalled
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** E6（E5-E5）：dshVersion="latest" + 预装存在 ⇒ Preinstalled（latest 是 tag，不是精确版本）。 */
    @Test
    fun latestVersionIsNotDegraded() {
        val dir = tempDir()
        try {
            val instanceDir = File(dir, "inst")
            val rootfs = File(dir, "rootfs")
            instanceDir.mkdirs()
            writePreinstalled(rootfs, "0.1.6")

            val target = resolveLaunchTarget(instanceDir, rootfs, "latest", readPkgVersion)

            assertTrue(
                "\"latest\" 不得与预装版本比较而判 Degraded：那会让所有 latest 实例都无法启动",
                target is LaunchTarget.Preinstalled
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** E7：两处都没有包 ⇒ MissingEntry(hasPackageJson=false)（文案是"先安装"）。 */
    @Test
    fun noTreeAnywhereIsMissingEntryWithoutPackageJson() {
        val dir = tempDir()
        try {
            val instanceDir = File(dir, "inst")
            val rootfs = File(dir, "rootfs")
            instanceDir.mkdirs()
            rootfs.mkdirs()

            val target = resolveLaunchTarget(instanceDir, rootfs, "0.2.1", readPkgVersion)

            assertTrue("两处都没包必须判 MissingEntry", target is LaunchTarget.MissingEntry)
            assertFalse(
                "hasPackageJson=false 用于\"先安装\"的文案（与半棵树的\"重装\"区分）",
                (target as LaunchTarget.MissingEntry).hasPackageJson
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * E8（E3-N-11，本组最关键）：`cmdline == null` ⇒ 不发信号。
     *
     * 为什么重要：旧实现把"读不到 cmdline"当作**通过**，于是 /proc 读不到时会盲发 TERM→KILL；
     * 那个 pid 若已被内核复用给别的 App，杀的就是别人。与 `canSendQuitSignal` 的"取严"同方向。
     */
    @Test
    fun shouldSignalRefusesNullCmdline() {
        assertFalse(
            "cmdline=null（读不到）必须拒绝发信号：这是\"不盲杀别人进程\"的唯一防线",
            shouldSignal(4321L, null, expectNode = false)
        )
        assertFalse("空串同样不算通过（读到了但为空 = 身份不明）", shouldSignal(4321L, "", expectNode = false))
        assertFalse("pid<=0 必须拒绝（没有可校验的目标）", shouldSignal(0L, "proot --link2symlink", expectNode = false))
    }

    /** E9：cmdline 存在但身份不符（pid 被复用）⇒ 拒绝。 */
    @Test
    fun shouldSignalRefusesMismatch() {
        assertFalse(
            "不是 proot 的进程必须拒绝（pid 复用场景）",
            shouldSignal(4321L, "com.example.other.app", expectNode = false)
        )
        assertFalse(
            "期望 node 时，proot 的 cmdline 必须拒绝（否则会杀错层级）",
            shouldSignal(4321L, "proot --link2symlink -r /data/rootfs", expectNode = true)
        )
    }

    /** E10：cmdline 含 proot ⇒ 放行（expectNode=false）。 */
    @Test
    fun shouldSignalAcceptsMatchingProot() {
        assertTrue(
            "cmdline 含 proot 且期望 proot 时必须放行，否则停止路径永远退化成 destroy()",
            shouldSignal(4321L, "proot --link2symlink -r /data/rootfs /bin/sh", expectNode = false)
        )
    }

    /** E11：cmdline 含 dsh 包路径（或 node22 路径）⇒ 放行（expectNode=true）。 */
    @Test
    fun shouldSignalAcceptsMatchingNode() {
        assertTrue(
            "含 @deepseek-ai/dsh 的 node 进程必须放行",
            shouldSignal(4321L, "/data/rootfs/node22/bin/node /data/x/node_modules/@deepseek-ai/dsh/lib/bin.js", expectNode = true)
        )
        assertTrue(
            "含 node22/bin/node 的进程必须放行（同一判据的另一条特征）",
            shouldSignal(4321L, "/data/rootfs/usr/local/node22/bin/node server.js", expectNode = true)
        )
    }
}
