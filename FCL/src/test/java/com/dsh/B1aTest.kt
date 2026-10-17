package com.dsh

import com.dsh.core.DshInstaller
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Round 15 批 B / B1a 的纯 JVM 单测（不需要真机/Android）。
 *
 * 覆盖 [DshInstaller] 本轮新增的三个 public 纯判断内核：
 * - `isInstallComplete`（BL-054：晚取消"其实已装完"的否定判据 —— package.json + lib/bin.js
 *   + 版本相符）；
 * - `shouldEmitLog` / `isImportantInstallLine`（BL-053：npm 行节流内核 —— 频控与重要行豁免）；
 * - `canCancelInstall`（BL-043①：cancel 守卫判据的真值表）。
 *
 * 说明（任务单要求注明）：`ownershipLock` 临界区（BL-043② 的"占闸+登记"与"摘登记+放闸"
 * 互斥）挂在进程级单例的协程任务体上（需要 DshAppScope + Context + proot），无法纯测，
 * 跳过；其正确性靠代码审查（[DshInstaller.install] 方法头的窗口推演）+ 真机日志门验证。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.B1aTest"
 *   （或仓库的 run-tests.sh，自动扫描本类）
 */
class B1aTest {

    /** 造一个完整的假 dsh 包（package.json + lib/bin.js） */
    private fun fakeDshDir(root: File, version: String, withBinJs: Boolean = true): File {
        val dir = File(root, "node_modules/@deepseek-ai/dsh")
        dir.mkdirs()
        File(dir, "package.json").writeText("{\"name\":\"@deepseek-ai/dsh\",\"version\":\"$version\"}")
        if (withBinJs) {
            File(dir, "lib").mkdirs()
            File(dir, "lib/bin.js").writeText("// fake entry\n")
        }
        return dir
    }

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("b1a").toFile()

    // --- BL-054：isInstallComplete --------------------------------------------------

    /** 完整包 + 版本相符（含 requestedVersion == null 的跳过比对路径）→ true */
    @Test
    fun completePackageWithMatchingVersionIsComplete() {
        val tmp = tempDir()
        try {
            val dir = fakeDshDir(tmp, "0.2.1-alpha.1")
            assertTrue(DshInstaller.isInstallComplete(dir, "0.2.1-alpha.1"))
            // null = 只验完整性、跳过版本比对（旧调用点语义）
            assertTrue(DshInstaller.isInstallComplete(dir, null))
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * `latest` 跳过版本比对（防御口径，与 nodeModulesBudgetBytes 一致）：
     * latest 是"最新版"，装到哪个精确版本都算完成。
     */
    @Test
    fun latestSkipsVersionComparison() {
        val tmp = tempDir()
        try {
            val dir = fakeDshDir(tmp, "0.1.6-alpha.2")
            assertTrue(DshInstaller.isInstallComplete(dir, "latest"))
            // 但 bin.js 缺失照样否定（latest 只豁免版本比对这一条）
            val noBin = fakeDshDir(File(tmp, "other"), "0.1.6-alpha.2", withBinJs = false)
            assertFalse(DshInstaller.isInstallComplete(noBin, "latest"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** lib/bin.js 缺失 = 否定判据（BL-054 的直接靶点：旧版只警告不否定） */
    @Test
    fun missingBinJsIsNotComplete() {
        val tmp = tempDir()
        try {
            val dir = fakeDshDir(tmp, "0.2.1-alpha.1", withBinJs = false)
            assertFalse(DshInstaller.isInstallComplete(dir, "0.2.1-alpha.1"))
            // 连"不比对版本"也必须被 bin 缺失否定
            assertFalse(DshInstaller.isInstallComplete(dir, null))
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 版本不符 = 不完整：装的不是要的那个版本（晚取消的"其实已装完"必须装的是请求版本） */
    @Test
    fun versionMismatchIsNotComplete() {
        val tmp = tempDir()
        try {
            val dir = fakeDshDir(tmp, "0.1.5")
            assertFalse(DshInstaller.isInstallComplete(dir, "0.2.1-alpha.1"))
            assertTrue(DshInstaller.isInstallComplete(dir, "0.1.5"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** package.json 不存在 / 读不出 / version 为空 → 全部 false（最低证据都缺） */
    @Test
    fun unreadablePackageJsonIsNotComplete() {
        val tmp = tempDir()
        try {
            val empty = File(tmp, "empty")
            empty.mkdirs()
            assertFalse("空目录", DshInstaller.isInstallComplete(empty, "0.1.5"))

            val broken = File(tmp, "broken")
            broken.mkdirs()
            File(broken, "package.json").writeText("{ not json")
            File(broken, "lib").mkdirs()
            File(broken, "lib/bin.js").writeText("// fake\n")
            assertFalse("坏 JSON", DshInstaller.isInstallComplete(broken, "0.1.5"))

            val blank = File(tmp, "blank")
            blank.mkdirs()
            File(blank, "package.json").writeText("{\"name\":\"@deepseek-ai/dsh\",\"version\":\"  \"}")
            assertFalse("空白版本号", DshInstaller.isInstallComplete(blank, null))
        } finally {
            tmp.deleteRecursively()
        }
    }

    // --- BL-053：shouldEmitLog / isImportantInstallLine -----------------------------

    /** 首次发射（lastEmitMs == 0）立即放行 —— 安装开头的第一行必须看得见 */
    @Test
    fun firstLogLineAlwaysEmits() {
        assertTrue(DshInstaller.shouldEmitLog(nowMs = 1000L, lastEmitMs = 0L, important = false))
    }

    /** 普通行在窗口内被抑制、达到窗口才放行（边界：恰好等于窗口 → 放行） */
    @Test
    fun normalLineIsThrottledWithinWindow() {
        val last = 10_000L
        val win = DshInstaller.LOG_THROTTLE_MIN_INTERVAL_MS
        assertFalse("窗口内 - 1ms", DshInstaller.shouldEmitLog(last + win - 1, last, important = false))
        assertTrue("恰好窗口", DshInstaller.shouldEmitLog(last + win, last, important = false))
        assertTrue("超过窗口", DshInstaller.shouldEmitLog(last + win * 5, last, important = false))
    }

    /** 重要行永远放行（无视窗口与 lastEmit 状态） */
    @Test
    fun importantLineAlwaysEmits() {
        val last = 10_000L
        assertTrue(DshInstaller.shouldEmitLog(last, last, important = true))
        assertTrue(DshInstaller.shouldEmitLog(last + 1, last, important = true))
    }

    /** 重要行分类：STAGE / FAILED / DONE / npm 错误（两种前缀）/ 预检失败前缀 → true；普通输出 → false */
    @Test
    fun importantLineClassification() {
        assertTrue(DshInstaller.isImportantInstallLine("[setup] STAGE=installing @deepseek-ai/dsh@0.2.1-alpha.1"))
        assertTrue(DshInstaller.isImportantInstallLine("[setup] FAILED reason=node-version"))
        assertTrue(DshInstaller.isImportantInstallLine("[setup] DONE version=0.2.1-alpha.1 source=instance"))
        assertTrue(DshInstaller.isImportantInstallLine("npm error code EAI_AGAIN"))
        assertTrue(DshInstaller.isImportantInstallLine("npm ERR! code ETIMEDOUT"))
        assertTrue(DshInstaller.isImportantInstallLine("[proot] 预检失败: some reason"))
        assertFalse(DshInstaller.isImportantInstallLine("added 500 packages in 42s"))
        assertFalse(DshInstaller.isImportantInstallLine("npm warn ERESOLVE overriding peer dependency"))
    }

    // --- BL-043①：canCancelInstall 真值表 -------------------------------------------

    /** 守卫判据 = statuses 在 AND running 登记在；四条真值表全钉住 */
    @Test
    fun cancelGuardTruthTable() {
        assertTrue(DshInstaller.canCancelInstall(statusRunning = true, taskRegistered = true))
        assertFalse("statuses 已清（终态帧）", DshInstaller.canCancelInstall(statusRunning = false, taskRegistered = true))
        assertFalse("登记已摘（无任务）", DshInstaller.canCancelInstall(statusRunning = true, taskRegistered = false))
        assertFalse("双缺", DshInstaller.canCancelInstall(statusRunning = false, taskRegistered = false))
    }

    /** 节流参数契约：窗口与缓冲上限是公开常量，防无声漂移（10Hz 是设计值） */
    @Test
    fun throttleConstantsAreStable() {
        assertEquals(100L, DshInstaller.LOG_THROTTLE_MIN_INTERVAL_MS)
        assertEquals(50, DshInstaller.PENDING_LOG_MAX_LINES)
    }
}
