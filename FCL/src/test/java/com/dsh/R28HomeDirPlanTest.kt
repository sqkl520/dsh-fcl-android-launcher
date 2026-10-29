package com.dsh

import com.dsh.core.HomeDirPlan
import com.dsh.core.planHomeDirCreation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R28 / C2：**自定义 HOME 的目录创建策略** —— 尤其是"绝不在 `instances/` 下建新目录"。
 *
 * ## 为什么这条是必须的（C2-B-1，本次锐评最严重的一条）
 *
 * `DshInstances.adoptOrphanInstanceDirs()` 的判据是"`instances/` 下存在但清单里没有的目录"
 * （`rebuildMissingInstances`），而它会在**每个冷启成功出口**被调用（4 处）。
 *
 * 于是：用户在「启动目录」里填 `instances/` 下的某个名字 —— 而 `DshInstance.homeDir` 的 KDoc
 * 恰恰在推荐"整个 `instances/` 根"这个用法 —— 如果启动时 mkdir 出来，
 * **下次冷启它就被收编成一张 NOT_INSTALLED 幽灵实例卡片并写进清单**。
 * 用户只是改了个"起始目录"，却凭空多出一个实例，而且零线索。
 *
 * ## 为什么这组测试必须存在
 *
 * 原来的判断写在 `DshRuntime.startLocked` 里，需要 Context / DshPaths / 锁 —— JVM 碰不到。
 * 后果是"把这段整体删掉"或"改回 workspace"这类回归**全绿通过**（C2 实测确认）。
 * 抽成纯函数后才有地方钉。
 */
class R28HomeDirPlanTest {

    private val hostRoot = File("/host/root")
    private val instancesDir = File("/host/root/instances")
    private val guestRoot = "/opt/dsh"

    /** 全部按"路径不存在"来算，这样能走完三个分支。 */
    private val noneExists: (File) -> Boolean = { false }
    private val allExist: (File) -> Boolean = { true }

    /**
     * ★ 核心用例：`instances/` 下不存在的目录必须**拒绝创建**。
     *
     * 这是唯一会"改一个设置项、凭空多出一个实例"的路径，定级最高。
     */
    @Test
    fun refusesToCreateUnknownDirUnderInstances() {
        val plan = planHomeDirCreation(
            homeGuest = "/opt/dsh/instances/my-new-folder",
            hostRootDir = hostRoot,
            guestRoot = guestRoot,
            instancesHostDir = instancesDir,
            exists = noneExists
        )
        assertTrue(
            "在 instances/ 下建新目录会被 adoptOrphanInstanceDirs 收编成幽灵实例，必须拒绝",
            plan is HomeDirPlan.RefusePhantom
        )
    }

    /** 但 `instances/` 下**已存在**的目录（= 真实实例）必须放行 —— 那是默认值的形态。 */
    @Test
    fun allowsExistingDirUnderInstances() {
        val plan = planHomeDirCreation(
            homeGuest = "/opt/dsh/instances/inst-x",
            hostRootDir = hostRoot,
            guestRoot = guestRoot,
            instancesHostDir = instancesDir,
            exists = allExist
        )
        assertEquals(
            "已存在的实例目录是默认值，必须放行（否则默认配置本身就用不了）",
            HomeDirPlan.AlreadyExists,
            plan
        )
    }

    /** `instances/` 之外的路径照常创建（例如用户想有个独立的工作目录）。 */
    @Test
    fun createsDirOutsideInstances() {
        val plan = planHomeDirCreation(
            homeGuest = "/opt/dsh/my-work",
            hostRootDir = hostRoot,
            guestRoot = guestRoot,
            instancesHostDir = instancesDir,
            exists = noneExists
        )
        assertEquals(
            "instances/ 之外的路径可以创建（这是 fix18 想要的功能）",
            HomeDirPlan.Create(File("/host/root/my-work")),
            plan
        )
    }

    /** rootfs 内部的路径不归我们管 —— 不建目录（不往解压出来的 rootfs 里写东西）。 */
    @Test
    fun doesNotCreateInsideRootfs() {
        val plan = planHomeDirCreation(
            homeGuest = "/etc/custom-home",
            hostRootDir = hostRoot,
            guestRoot = guestRoot,
            instancesHostDir = instancesDir,
            exists = noneExists
        )
        assertEquals("rootfs 内部路径不负责创建", HomeDirPlan.OutsideGuestRoot, plan)
    }

    /** `instances` 目录本身（用户按 KDoc 推荐填的那条）在不存在时也要拒绝创建。 */
    @Test
    fun refusesInstancesRootItselfWhenMissing() {
        val plan = planHomeDirCreation(
            homeGuest = "/opt/dsh/instances",
            hostRootDir = hostRoot,
            guestRoot = guestRoot,
            instancesHostDir = instancesDir,
            exists = noneExists
        )
        assertTrue(
            "instances 根不存在时也不能建（建了之后它本身会被当成内容扫描）",
            plan is HomeDirPlan.RefusePhantom
        )
    }

    /**
     * 前缀相似但不是子路径（`/opt/dshx`）必须判 OutsideGuestRoot。
     *
     * 这条在 `hostPathForGuestDir` 里已经用 `startsWith("$root/")` 判对，
     * 但这里是端到端再确认一次（因为是否"安全"取决于两层都对）。
     */
    @Test
    fun similarPrefixIsNotTreatedAsInside() {
        val plan = planHomeDirCreation(
            homeGuest = "/opt/dshx/foo",
            hostRootDir = hostRoot,
            guestRoot = guestRoot,
            instancesHostDir = instancesDir,
            exists = noneExists
        )
        assertEquals("前缀相似不是子路径", HomeDirPlan.OutsideGuestRoot, plan)
    }
}

/**
 * R28 / C2-C-1：**调用点契约** —— 钉住 `DshRuntime` 真的用了 [resolveHomeRootfs] 的结果。
 *
 * ## 为什么需要这组（C2 实测出来的假绿）
 *
 * `R27HomeDirTest` 与 `R28HomeDirPlanTest` 都只测**纯函数**。C2 实测确认：
 * 把 `DshRuntime` 里 `homeRootfs = homeGuest` 改回 `"$instancePathInRootfs/workspace"`
 * （**两次事故的真实形状**），499 条测试**全绿**。
 *
 * 也就是说：只抽纯函数、不钉调用点，等于没修 —— 下一个人"顺手简化"就能把 bug 带回来。
 *
 * ## 为什么用源码级断言而不是运行时断言
 *
 * 真正的运行时断言需要构造 `Context` + 初始化 `DshPaths` + 走过 `preflight`，
 * 在 JVM 单测里做不到（见 `R26StartPlanTest` 的说明）。源码级断言是本仓已有的先例
 * （`DshTaskStateTest.taskStageStringsAreLocalizedNotHardcoded`），弱但**能挡住这类回归**。
 */
class R28CallSiteContractTest {

    @Test
    fun dshRuntimeUsesResolveHomeRootfsNotAHardcodedSubdir() {
        val file = resolveMainSource("core/DshRuntime.kt")
        assertTrue("找不到 DshRuntime.kt 源码：请在仓库根或 FCL/ 目录下运行测试", file != null)
        val text = file!!.readText()

        assertTrue(
            "DshRuntime 必须调用 resolveHomeRootfs 来决定 HOME（否则「启动目录」设置项是死开关）",
            text.contains("homeRootfs = homeGuest")
        )
        assertFalse(
            "★ 不许出现 homeRootfs = \".../workspace\" 这种写死子目录 —— " +
                "workspace 是空目录，会让「选择工作区目录」重新变成空框（fix15 的真实事故）",
            Regex("""homeRootfs\s*=\s*"[^"]*workspace"""").containsMatchIn(text)
        )
        assertFalse(
            "不许把 homeRootfs 写死成常量 /root（最初的事故形状）",
            Regex("""homeRootfs\s*=\s*"/root"""").containsMatchIn(text)
        )
    }

    /** `instances/` 下的幽灵实例保护必须留在调用链上（不能只有纯函数、忘了接线）。 */
    @Test
    fun dshRuntimeActuallyConsultsThePhantomGuard() {
        val file = resolveMainSource("core/DshRuntime.kt")
        assertTrue("找不到 DshRuntime.kt", file != null)
        val text = file!!.readText()

        assertTrue(
            "startLocked 必须调用 planHomeDirCreation（幽灵实例保护在它里面）",
            text.contains("planHomeDirCreation(")
        )
        assertTrue(
            "拒绝创建时必须留日志 —— 否则用户只会看到一个无法解释的空列表",
            text.contains("RefusePhantom ->")
        )
    }

    /** 与其他测试同款：从 cwd 向上找 `FCL/src/main/java/com/dsh/<rel>`。 */
    private fun resolveMainSource(rel: String): File? {
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
        return null
    }
}
