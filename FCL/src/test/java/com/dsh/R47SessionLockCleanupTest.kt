package com.dsh

import com.dsh.core.StaleSessionLockPlan
import com.dsh.core.planStaleSessionLockCleanup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * round47 / BL-247：**无主会话锁的清理**。
 *
 * ## 这条修的是什么
 *
 * 真机（KKG-AN00 / v0.1.6-SNAPSHOT-fix26）发消息报：
 * ```
 * session "session-c203b2d6-…" is already owned by an active write handle
 * ```
 * 且**停止 → 重启实例不恢复**（同一个 session id、同一条错）。
 *
 * 上游 `session-persistence-jsonl/src/lease.ts` 的 POSIX 路径：flock 成功之后还要校验
 * `handle.stat()` 与 `stat(path)` 的 `ino`/`dev` 是否一致，最多重试 3 次，全不匹配就抛
 * `SessionAlreadyOwnedError` —— proot 虚拟化 inode/dev 时两次 stat 可能对不上，于是
 * **误报"已被占用"**，而实际上没有任何持有者。
 *
 * 用户唯一的出路是删掉那个 `session.lock`，而非 debuggable 包连 `files/` 都读不到
 * （实测：`run-as` 报 "package not debuggable"，`adb shell` 报 Permission denied）。
 * 所以启动器在启动前代劳。
 *
 * ## 安全前提（本组用例的核心）
 *
 * `DshRuntime.start()` 是单实例策略：进入 `startLocked` 之前必然已经 stopAndWait 过
 * 别的实例 ⇒ 清理发生时本 App 没有任何 dsh 进程 ⇒ 那些锁不可能有活的持有者。
 *
 * 上游注释原话：*Removing a live session's lock file therefore forfeits exclusion on POSIX*。
 * 所以 `hasLiveDshProcess = true` 时必须**一个都不删** —— 那是本组用例里最重要的一条。
 */
class R47SessionLockCleanupTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("r47-sessions").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 按 dsh 的真实布局造一个会话目录：`<root>/<projectKey>/<session-id>/session.lock` */
    private fun makeLock(project: String, session: String): File {
        val dir = File(File(root, project), session)
        dir.mkdirs()
        return File(dir, "session.lock").apply { writeText("") }
    }

    // ===== 主路径 ==========================================================

    /**
     * 没有 dsh 进程时，所有 session.lock 都是无主的 ⇒ 全部可删。
     *
     * 变异验证：把 `if (hasLiveDshProcess)` 那段删掉、或把 `removable` 改成 emptyList()
     * ⇒ 本条变红。
     */
    @Test
    fun allLocksAreRemovableWhenNoProcessIsAlive() {
        val a = makeLock("--opt-dsh-inst-workspace--", "session-aaa")
        val b = makeLock("--opt-dsh-inst-workspace--", "session-bbb")
        val c = makeLock("--no-cwd--", "session-ccc")

        val plan = planStaleSessionLockCleanup(root, hasLiveDshProcess = false)

        assertEquals(
            "★ 无活进程时，三个锁都应可删",
            listOf(a.absolutePath, b.absolutePath, c.absolutePath).sorted(),
            plan.removable.map { it.absolutePath }
        )
        assertTrue("★ 没有理由跳过任何一个", plan.skipped.isEmpty())
    }

    /**
     * **本组最重要的一条**：有 dsh 进程活着时，一个都不许删。
     *
     * 为什么这么重要：删掉活会话的锁 = 放弃互斥 = 两次 append 撕裂同一份日志。
     * 上游注释把这条写成了明确警告。宁可这次不修，也不能误删。
     *
     * 变异验证：把 `if (hasLiveDshProcess)` 那个早退删掉 ⇒ 本条变红
     * （removable 会变成 3 个，而断言要求 0 个）。
     */
    @Test
    fun noLockIsRemovedWhileADshProcessIsAlive() {
        makeLock("--p--", "session-1")
        makeLock("--p--", "session-2")

        val plan = planStaleSessionLockCleanup(root, hasLiveDshProcess = true)

        assertTrue(
            "★ 有活进程时必须一个都不删（否则会放弃互斥、撕裂日志）；实际可删 ${plan.removable.size} 个",
            plan.removable.isEmpty()
        )
        assertEquals("★ 两个锁都应带原因记进 skipped", 2, plan.skipped.size)
        assertTrue(
            "★ skipped 的原因要说明是\"有进程活着\"",
            plan.skipped.values.all { it.contains("alive") }
        )
    }

    /**
     * `hasLiveDshProcess` 必须是**唯一**的否决条件 —— 不许有"看起来旧就删"这类时间判据。
     *
     * 为什么专门钉这个：`session.lock` 的 mtime 与"有没有人持有"完全无关（内核 flock
     * 不改 mtime）。如果哪天有人加了 `lastModified` 判断，就会在活会话上误删。
     * 这条用两个 mtime 差很大的锁来验证：老的那个也不会被特殊对待。
     */
    @Test
    fun lockAgeDoesNotAffectTheDecision() {
        val old = makeLock("--p--", "session-old")
        old.setLastModified(1_000_000_000_000L) // 2001 年
        val fresh = makeLock("--p--", "session-fresh")

        val plan = planStaleSessionLockCleanup(root, hasLiveDshProcess = false)

        assertEquals(
            "★ 年龄不参与判定：两个都应可删（判断只依赖\"有没有活进程\"）",
            2,
            plan.removable.size
        )
        assertTrue(plan.removable.map { it.name }.all { it == "session.lock" })
        assertTrue(fresh.isFile)
    }

    // ===== 边界 ============================================================

    /** 目录不存在（新实例、还没跑过会话）⇒ 空计划，不抛异常。 */
    @Test
    fun missingSessionsRootYieldsEmptyPlan() {
        val plan = planStaleSessionLockCleanup(File(root, "nope"), hasLiveDshProcess = false)
        assertTrue(plan.removable.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }

    /** 有 sessions 目录但一个锁都没有 ⇒ 空计划。 */
    @Test
    fun sessionsRootWithoutLocksYieldsEmptyPlan() {
        File(root, "--p--/session-x").mkdirs()
        val plan = planStaleSessionLockCleanup(root, hasLiveDshProcess = false)
        assertTrue(plan.removable.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }

    /**
     * 只认**文件名叫 `session.lock` 的文件**，不碰别的东西。
     *
     * 为什么重要：这个函数会拿到真实用户目录上跑。误删 `.jsonl` 日志就是数据丢失。
     */
    @Test
    fun onlySessionLockFilesAreConsidered() {
        val dir = File(root, "--p--/session-1")
        dir.mkdirs()
        File(dir, "session.jsonl").writeText("log data")
        File(dir, "session.lock").writeText("")
        File(dir, "notes.txt").writeText("x")

        val plan = planStaleSessionLockCleanup(root, hasLiveDshProcess = false)

        assertEquals("★ 只应命中 session.lock 一个", 1, plan.removable.size)
        assertEquals("session.lock", plan.removable.single().name)
        // 并且真的没动别的文件
        assertTrue("日志必须还在", File(dir, "session.jsonl").isFile)
        assertTrue("不相关文件必须还在", File(dir, "notes.txt").isFile)
    }

    /**
     * 名字只是**包含** session.lock 的不算（`session.lock.bak` / `my-session.lock`）。
     * 用"等于"而不是"包含"，避免误伤备份文件或别人的命名。
     */
    @Test
    fun similarlyNamedFilesAreNotMatched() {
        val dir = File(root, "--p--/session-1")
        dir.mkdirs()
        File(dir, "session.lock.bak").writeText("")
        File(dir, "my-session.lock").writeText("")

        val plan = planStaleSessionLockCleanup(root, hasLiveDshProcess = false)

        assertTrue(
            "★ 只按文件名全等匹配；实际命中：${plan.removable.map { it.name }}",
            plan.removable.isEmpty()
        )
    }

    /** 嵌套两层以上的会话目录也能扫到（dsh 的项目分组不是固定深度）。 */
    @Test
    fun nestedSessionDirsAreScannedRecursively() {
        val deep = File(root, "--p--/session-1/artifacts/old")
        deep.mkdirs()
        val lock = File(deep, "session.lock").apply { writeText("") }

        val plan = planStaleSessionLockCleanup(root, hasLiveDshProcess = false)

        assertEquals("★ 深层目录里的锁也要扫到", 1, plan.removable.size)
        assertEquals(lock.absolutePath, plan.removable.single().absolutePath)
    }

    // ===== 接线断言（防"函数写好了但没人调"）================================

    /**
     * ★ 接线断言：`DshRuntime` 的启动路径必须真的调用 [planStaleSessionLockCleanup]，
     * 而且必须在 fork 之前。
     *
     * 为什么要这一条：本项目 BL-245 / BL-246 的教训 —— **纯函数测得再全，调用点改回去
     * 仍然全绿**。BL-246 第一版 8 条测试全是纯函数，把调用点撤销后 8 条依旧绿（实测）。
     * 这里用源码形状断言补上那一环。
     */
    @Test
    fun productionStartPathActuallyCallsTheCleanup() {
        val src = mainSourceFile("core/DshRuntime.kt").readText()
        val code = stripComments(src)

        val callAt = code.indexOf("planStaleSessionLockCleanup(")
        assertTrue(
            "★ 启动路径里必须真的调用 planStaleSessionLockCleanup（只写函数不算）",
            callAt >= 0
        )

        // 必须在 fork 之前：用 --patch overlay 的写入点做锚（它明确在 fork 之前）
        val overlayAt = code.indexOf("writeLauncherPatchOverlay(instancePathInRootfs)")
        assertTrue("前置：应能定位到 overlay 写入点", overlayAt >= 0)
        assertTrue(
            "★ 清理必须在写 overlay 之后、fork 之前（此时旧进程确已停干净）；" +
                "callAt=$callAt overlayAt=$overlayAt",
            callAt > overlayAt
        )

        // 必须把"有没有活进程"作为显式输入传进去，而不是写死 false
        val callSnippet = code.substring(callAt, minOf(callAt + 400, code.length))
        assertTrue(
            "★ 必须传 hasLiveDshProcess = activeInstanceId() != null（不能写死 false，" +
                "那会在实例运行中误删活会话的锁）；实际片段：$callSnippet",
            callSnippet.contains("hasLiveDshProcess")
        )
        assertTrue(
            "★ 判据必须来自 activeInstanceId()，不能是常量",
            callSnippet.contains("activeInstanceId()")
        )
    }

    /** 清理失败不能让启动失败 —— 整段必须包在 runCatching 里。 */
    @Test
    fun cleanupFailureCannotBreakStartup() {
        val code = stripComments(mainSourceFile("core/DshRuntime.kt").readText())
        val callAt = code.indexOf("planStaleSessionLockCleanup(")
        assertTrue("前置：调用点存在", callAt >= 0)
        // 往前找最近的 runCatching
        val before = code.substring(0, callAt)
        val lastRunCatching = before.lastIndexOf("runCatching")
        assertTrue(
            "★ 清理必须包在 runCatching 里（坏文件/权限问题不能拖垮启动）",
            lastRunCatching >= 0 && callAt - lastRunCatching < 800
        )
    }

    // ===== 工具 ============================================================

    private fun mainSourceFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/java/com/dsh/$rel"),
                File(dir, "FCL/src/main/java/com/dsh/$rel"),
                File(dir, "src/main/java/com/dsh/$rel")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("找不到 $rel（请在仓库根或 FCL/ 目录下运行测试）")
    }

    /** 剥掉注释，只留可执行代码（KDoc 里成段讨论这些标识符，不剥会假绿）。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
