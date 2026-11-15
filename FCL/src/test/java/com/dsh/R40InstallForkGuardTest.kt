package com.dsh

import android.content.Context
import android.content.ContextWrapper
import com.dsh.core.DshAppContextHolder
import com.dsh.core.DshInstance
import com.dsh.core.DshInstaller
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.ProotExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

/**
 * round40 / BL-244：**"cancel 之后仍然 fork npm"** 的修复（fork 前取消检查点 + 删除侧 job join）。
 *
 * ## 修的是什么（先说实话，否则下面的用例看不懂在防什么）
 *
 * 删除一个**正在安装**的实例时，`DshInstances.delete` 先 `installer.cancel(id)` 再轮询"安装是否
 * 收尾"。那个 cancel 只做三件事：标 `cancelled`、`job.cancel()`、放掉单飞闸门。而安装任务体在
 * fork proot 之前有一整段**没有挂起点**的耗时活（`mkdirs` / `cleanupStaleInstallProcess` 最长 2s
 * 宽限 / `checkInstallSpace` / wake lock / `ProotProcessExecutor.run` 内的 preflight 与快照）。
 * 协程取消是协作式的 —— 这几段一行都不检查，于是 cancel 之后 body 照旧往下走、**仍然 fork**，
 * 而删除侧的等待判据此时已经全假（闸门被 cancel 放掉；`dsh-install.pid` 还没写，在 pid 解析
 * 失败的机型上整场安装都没有这个文件），于是 `deleteRecursively()` 与刚起来的 npm 抢盘。
 *
 * 修法两层：
 * 1. `DshInstaller.runInstall` 在 fork 前的三段（入口 / 清理残留之后 / fork 紧前面）各设一个
 *    [DshInstaller] 内部检查点：被取消或被接管就**不 fork、不写 pid** 直接返回 false；
 * 2. `DshInstances.delete` 在 `cancel()` **之前**抓住那一次安装的 Job，`cancel()` 之后在
 *    安装器的 `ownershipLock` **之外**用**同一条** 8s deadline 做 `join`。
 *
 * ## 覆盖层次（越往下越接近真实路径）
 * | 层 | 用例 | 证明了什么 |
 * |---|---|---|
 * | 纯判据 | [shouldWaitForInstallTask*] | `delete` 该不该为一次安装付等待 |
 * | 只读访问器 | [installJobOf*] | 没任务时必须是 null（不能拿 `containsKey` 之类的近似糊过去） |
 * | **端到端（真跑生产代码）** | [cancelledInstallNeverForksProot] | 反射真调 `runInstall`：替身的 `run` **一次都没被调用**、日志出现检查点行 |
 * | 结构钉子 | [checkpointPrecedesFork] / [deleteJoinsOutsideTheInstallerLock] 等 | 防"哪天有人把检查点挪到 fork 之后 / 把 join 挪进锁里" |
 *
 * ## 为什么端到端那条**不需要任何时序运气**
 *
 * 夹具在进函数**之前**就把"已登记 + 已 `cancelled` + job 已取消"这个态造好了（照抄
 * `R34InstallErrorTest` 的 `ownJob` 手法）。检查点在 fork 之前必被走到 ⇒ 每一次运行结论都相同。
 * 修复前（变异验证里把检查点短路掉）它**必然变红**：替身的 `run` 被调用一次。
 *
 * 运行：`sh run-tests.sh`（自动扫描本类），或
 * `./gradlew :FCL:testDebugUnitTest --tests "com.dsh.R40InstallForkGuardTest"`。
 */
class R40InstallForkGuardTest {

    private val tmpRoot: File = Files.createTempDirectory("r40-fork-guard").toFile()
    private val savedPaths = HashMap<String, Any?>()
    private var savedAppContext: Any? = null
    private var appContextSaved = false

    @After
    fun restoreGlobalState() {
        savedPaths.forEach { (name, value) ->
            if (value === null) return@forEach
            runCatching { pathField(name).set(DshPaths, value) }
        }
        if (appContextSaved) {
            runCatching {
                DshAppContextHolder::class.java.getDeclaredField("context").apply { isAccessible = true }
                    .set(null, savedAppContext)
            }
        }
        runCatching { DshLogBus.removeStream(INSTANCE_ID) }
    }

    // ==================================================================
    // 一、纯判据：delete 要不要为一次安装付等待
    // ==================================================================

    /** 没有任务 ⇒ 不等待（`installJobOf` 返回 null 的常态路径） */
    @Test
    fun shouldWaitForInstallTaskIsFalseWithoutAJob() {
        assertFalse(
            "没有登记任务时不该等：等待的对象不存在",
            DshInstaller.shouldWaitForInstallTask(null)
        )
    }

    /**
     * 活着的任务 ⇒ 等待。
     *
     * ★ 注意 `isActive` **不参与**判据：被 cancel 之后仍在收尾的 job 恰恰是最该等的那一种 ——
     * 它正在 fork、正在等 proot 退出、正在删 pid 文件。用 `isActive` 当判据会把这批全部排除，
     * 等于没修。
     *
     * 夹具说明：裸 `Job()` 是叶子 job，`cancel()` 会**立刻**把它带到已完成，造不出"已取消但
     * 未完成"。这里用 `CompletableJob` + 一个挂住它的子 job 来表达真实形状（真实任务体的
     * 收尾要跑 finally，父 job 在子收尾前不会完成），这样 `shouldWaitForInstallTask` 才是
     * 在"活着的任务"上被检验，而不是只在"还没被取消的叶子 job"上。
     */
    @Test
    fun shouldWaitForInstallTaskIsTrueForALiveJob() {
        val parent = Job()
        val child = Job(parent)
        try {
            assertFalse("前置：新造的 Job 不该已完成", parent.isCompleted)
            assertTrue("未完成的任务必须等", DshInstaller.shouldWaitForInstallTask(parent))
        } finally {
            child.cancel()
            parent.cancel()
        }
    }

    /**
     * ★ BL-244 的关键判据：**`isActive` 不参与**。
     *
     * 一个"已被 cancel、但仍在收尾"的 job（`isActive == false` 且尚未完成）正是删除事务必须等
     * 的那一个 —— 它就是"cancel 之后仍在 fork / 等 proot 退出"的那次安装。若判据写成
     * `job.isActive`，这批会被全部排除，修了等于没修。
     *
     * 造法：父 job 带一个**未结束的子 job**，父 cancel 之后不会立刻完成。
     * ⚠️ 实测（kotlinx-coroutines 1.9.0）：裸 `Job()` + `Job(parent)` 这种父子关系在
     * `parent.cancel()` 之后**仍会立刻 completed**（父并不等子）。所以"已取消但未完成"这个
     * 现场必须靠 [CoroutineScope] + `launch` 造：协程 job 的完成要等它的 body 真的收尾。
     */
    @Test
    fun shouldWaitForInstallTaskIgnoresIsActive() {
        val outer = Job()
        val scope = CoroutineScope(outer + Dispatchers.Default)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val running = scope.launch {
                started.countDown()
                // 无挂起点的阻塞：cancel 不会打断它 ⇒ job 处于 "cancelling 但未完成"
                release.await()
            }
            assertTrue("前置：协程必须真的开始跑", started.await(5, TimeUnit.SECONDS))
            running.cancel()
            assertFalse("前置：cancel 之后 isActive 必须为假", running.isActive)
            assertFalse(
                "前置：body 还卡在 release.await() 里，job 不该完成（否则本用例测的是别的东西）",
                running.isCompleted
            )
            assertTrue(
                "★ 已取消但仍在收尾的任务必须等 —— 若判据用 isActive，这个窗口就被漏掉了",
                DshInstaller.shouldWaitForInstallTask(running)
            )
        } finally {
            release.countDown()
            outer.cancel()
        }
    }

    /** 已完成的任务 ⇒ 不等待（`join()` 会立即返回，等它等价于不等） */
    @Test
    fun shouldWaitForInstallTaskIsFalseForACompletedJob() {
        val job = Job()
        job.complete()
        assertTrue("前置：complete() 之后必须已完成", job.isCompleted)
        assertFalse(
            "已完成的任务不必等（未调度即取消的 LAZY job 也会落到这里）",
            DshInstaller.shouldWaitForInstallTask(job)
        )
    }

    // ==================================================================
    // 二、只读访问器：installJobOf
    // ==================================================================

    /** 没有任何安装时必须是 null（返回 null 才让 delete 走"不等"的分支） */
    @Test
    fun installJobOfReturnsNullWhenNoTaskIsRegistered() {
        val installer = newInstaller(RecordingProot())
        assertNull(
            "★ 没登记过任务时必须返回 null（删除事务据此跳过等待）",
            installer.installJobOf(INSTANCE_ID)
        )
    }

    /** 登记之后返回的必须是**同一个** Job（引用相等 —— delete 要 join 的正是它） */
    @Test
    fun installJobOfReturnsTheRegisteredJob() {
        val installer = newInstaller(RecordingProot())
        val job = registerJob(installer, INSTANCE_ID)
        try {
            assertNotNull("登记之后必须能取到", installer.installJobOf(INSTANCE_ID))
            assertTrue(
                "★ 必须是同一个实例（delete 要 join 的正是它，不是「当时等价的另一个」）",
                installer.installJobOf(INSTANCE_ID) === job
            )
        } finally {
            job.cancel()
        }
    }

    // ==================================================================
    // 三、端到端（核心，确定性）：被取消的安装**一个进程都不许起**
    // ==================================================================

    /**
     * ★ BL-244 的核心回归：`已登记 + 已 cancelled + job 已取消` 的态下，`runInstall` 必须
     * **不 fork**、**不写 pid 文件**、并把原因写进实例日志。
     *
     * 为什么这条不需要时序运气：夹具在进函数之前就把态造好了，检查点在 fork 必被走到。
     * 修复前（检查点被短路的那次变异）这条**必然红**：替身的 `run` 会被调用一次（npm 就起来了）。
     *
     * 为什么断言"日志出现检查点行"：只有"没 fork"这一个事实，排障时无法区分"被取消所以没起"
     * 与"预检失败所以没起" —— 那句日志是唯一能区分的证据，所以它本身也要被钉住。
     */
    @Test
    fun cancelledInstallNeverForksProot() {
        val proot = RecordingProot()
        val installer = newInstaller(proot)
        // 完整路径系统：即使检查点被挪/被删，本用的也能走到"真的 fork"那一步，
        // 从而让"run 一次都没被调用"这条断言真的有判别力（而不是被一个 lateinit 异常蒙过去）。
        val root = withFullPaths()
        // 预 flip 状态：登记 + 取消标记 + job 已取消（照抄 R34InstallErrorTest 的 ownJob 手法）
        val job = registerJob(installer, INSTANCE_ID)
        markCancelled(installer, INSTANCE_ID)
        job.cancel()

        val ok = invokeRunInstall(installer, newInstance(INSTANCE_ID), job)

        assertEquals(
            "★ 被取消的安装必须返回 false（交给 installJobBody 的既有取消支路收口）",
            false,
            ok
        )
        assertEquals(
            "★ 取消之后**绝不许 fork**：ProotExecutor.run 一次都不该被调用（BL-244 的现场就是这里多了一次）",
            0,
            proot.calls.get()
        )
        assertFalse(
            "★ 不许留下 dsh-install.pid（没 fork 就没有 pid，写假 pid 会让下次清理误杀）",
            File(DshPaths.instanceDir(INSTANCE_ID), DshInstaller.INSTALL_PID_FILE_NAME).isFile
        )
        val lines = instanceLogLines(INSTANCE_ID)
        assertTrue(
            "★ 实例日志里必须能看出「这次没 fork 是因为被取消」（排障唯一入口），实际：$lines",
            lines.any { it.contains("cancelled before forking proot") }
        )
        // 未初始化路径系统的兜底：确认上面确实用的是临时目录（防止用了宿主真实路径）
        assertTrue("前置：临时根目录必须真的被用上", root.isDirectory)
    }

    // ==================================================================
    // 四、结构钉子（防"以后有人挪位置"）
    // ==================================================================

    /**
     * 检查点必须在 **fork 之前**。
     *
     * 为什么只能做源码形状断言：一旦进了 `ProotProcessExecutor.run`，`preflight` / 子进程表快照 /
     * `ProcessBuilder.start()` 都不可打断（协程取消打不断阻塞调用）。所以"检查点晚于 fork"等价于
     * "检查点不存在"，而这件事在运行期看不出来（进程已经起来了）。
     */
    @Test
    fun checkpointPrecedesFork() {
        val body = sliceFun(installerSource(), "private suspend fun runInstall(", "private fun onInstallLine(")
        val checkpointAt = body.lastIndexOf("abortIfInstallCancelled(")
        assertTrue("★ runInstall 里必须至少调用一次取消检查点", checkpointAt >= 0)

        val directAt = body.indexOf("(proot as? ProotProcessExecutor)?.run(")
        val fallbackAt = body.indexOf("proot.run(")
        val forkAt = listOf(directAt, fallbackAt).filter { it >= 0 }.minOrNull() ?: -1
        assertTrue("前置：runInstall 里必须能定位到 proot 调用点", forkAt >= 0)

        assertTrue(
            "★ 取消检查点必须出现在**所有** proot 调用点之前（检查点晚于 fork = 没检查点）" +
                "：checkpointAt=$checkpointAt forkAt=$forkAt",
            checkpointAt < forkAt
        )
        // wake lock 段落里就是 fork —— 检查点也必须在它之前，否则"起了进程才判断"没有意义
        val wakeLockAt = body.indexOf("withRuntimeBusyWakeLock(")
        assertTrue("前置：runInstall 里必须能定位到 wake lock 段", wakeLockAt >= 0)
        assertTrue(
            "★ 检查点必须在 wake lock（= npm 执行段）之前：checkpointAt=$checkpointAt wakeLockAt=$wakeLockAt",
            checkpointAt < wakeLockAt
        )
    }

    /**
     * 删除侧的 join 必须在 `ownershipLock` **之外**，且与三条件循环**共用同一条 deadline**。
     *
     * ## 为什么"锁内 join"必须被钉死（不是洁癖）
     *
     * `ownershipLock` 是 [DshInstaller] 的 `private val` 对象监视器，任务体的 `finally`
     * 要靠它才能 `running.remove` + 放闸。若删除事务持锁等 job ⇒ job 永远收不了尾 ⇒ 白等满
     * deadline；更糟的是 `install()` 在**主线程**被调用（实例页的"重试" / 设置页的"重新安装"），
     * 主线程阻塞在对象监视器上就是 **ANR**。
     *
     * 结构保证：`DshInstances.kt` 里**一个字都不许提** `ownershipLock`（它是 private 的，
     * 想提也编不过）。这条断言把"哪天有人为了方便给 installer 加个持锁等待的公开方法"挡住。
     *
     * ★ 断言一律锚**可执行代码**而不是裸的词：`join()` / `ownershipLock` 在 KDoc 里大量出现
     * （本函数的注释就在讲它们），裸 `contains` 会让"删掉 join 但保留注释"的变异照样变绿
     * （实测：变异 (d) 把 join 整行删掉，裸 contains 仍然命中注释 → GREEN，假绿）。
     * 所以这里先剥掉行注释与块注释，再只看真正的调用。
     */
    @Test
    fun deleteJoinsOutsideTheInstallerLock() {
        val deleteBody = stripComments(
            sliceFun(instancesSource(), "fun delete(id: String", "fun diskUsageAsync(id: String")
        )
        assertFalse(
            "★ DshInstances.delete 不许碰安装器的 ownershipLock（持锁等 job ⇒ 任务体 finally " +
                "拿不到锁 ⇒ 白等满 deadline；且 install() 在主线程会 ANR）",
            deleteBody.contains("ownershipLock")
        )
        // 真调用点：`{ installJob.join() }`（withTimeoutOrNull 的 lambda 里那一次）
        val joinAt = deleteBody.indexOf(".join()")
        assertTrue(
            "★ 删除事务必须真的等那一次安装（可执行代码里找不到 .join()）—— " +
                "只留注释不算，注释不会等任何东西",
            joinAt >= 0
        )
        assertTrue(
            "★ 必须是 `installJob` 那一次（不是别的对象的 join）",
            deleteBody.contains("installJob?.join()") || deleteBody.contains("installJob.join()")
        )
        // 锚**真实调用行**而不是裸的 `deleteRecursively()`：delete 的 KDoc 里在讲抢盘风险时
        // 多处出现过这个词（`indexOf` 会命中注释，断言于是落在完全无关的位置上）。
        val removeDirAt = deleteBody.indexOf("DshPaths.instanceDir(id).deleteRecursively()")
        assertTrue("前置：必须能定位到删目录那一行", removeDirAt >= 0)
        assertTrue(
            "★ join 必须在 deleteRecursively() 之前（先等安装收尾，再删目录）：" +
                "joinAt=$joinAt removeDirAt=$removeDirAt",
            joinAt in 0 until removeDirAt
        )
        // 共用同一条 deadline：delete 里"now + DELETE_WAIT_TIMEOUT_MS"只能出现一次（不新增预算）
        val budgetDeclarations = Regex("System\\.currentTimeMillis\\(\\) \\+ DELETE_WAIT_TIMEOUT_MS")
            .findAll(deleteBody).count()
        assertEquals(
            "★ join 与三条件循环必须共用同一条 waitDeadline，不许新增第二个 8s 预算",
            1,
            budgetDeclarations
        )
        assertTrue(
            "★ join 的剩余时间必须从同一条 waitDeadline 现算（remaining = waitDeadline - now）",
            deleteBody.contains("waitDeadline - System.currentTimeMillis()")
        )
    }

    /**
     * 剥掉行注释（双斜杠）与块注释（斜杠星号 … 星号斜杠），只留可执行代码的骨架。
     *
     * 为什么需要：本文件的断言是"某段代码里有没有/在不在某处"，而 KDoc 里会**成段**讨论这些
     * 标识符（`join()` / `ownershipLock` / `DELETE_WAIT_TIMEOUT_MS`）。不剥注释的话，
     * "把调用删掉、注释留着"这种变异会让断言照样命中注释 → 用例 GREEN（实测踩到一次）。
     * 剥注释只动内容长度与换行，不影响调用点之间的**相对顺序**（本文件最强的那些断言用的就是
     * 顺序，剥完仍然成立）。
     */
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

    /**
     * 安装器内部**一个 `join` 都不许有**：等待永远由调用方（`DshInstances.delete`）在锁外做。
     *
     * 为什么这条是必要的补充：`ownershipLock` 是 `private` 的，`DshInstances` 想碰也编不过，
     * 所以上一条"deleteBody 不含 ownershipLock"只能挡住最直接的那种写法。真正容易发生的退化是
     * 反向的：有人为了"封装"在安装器里加一个 `joinInstallOnLock(id)` 之类的方法（锁内 join），
     * delete 一调就回到死锁形状。本断言把这条路堵死 —— 锁在安装器里，join 就不许进安装器。
     *
     * 死锁形状（不是理论风险）：任务体的 `finally` 要拿 `ownershipLock` 才能
     * `running.remove` + `gate.release`；持锁 join ⇒ 那个 finally 永远等不到锁 ⇒ `join` 等不到
     * 完成 ⇒ 白等满 8s deadline；而 `install()` 在**主线程**调用时，同一把对象监视器被占住就是 ANR。
     */
    @Test
    fun installerNeverJoinsUnderItsOwnershipLock() {
        assertFalse(
            "★ DshInstaller 里不许出现 join：锁在安装器内，join 一旦进来就可能落在临界区里 " +
                "（任务体 finally 拿不到锁 ⇒ 白等满 deadline；主线程调 install() ⇒ ANR）",
            stripComments(installerSource()).contains(".join(")
        )
    }

    /**
     * `installJobOf` 必须在 `cancel()` **之前**取快照。
     *
     * 为什么：cancel 之后可能立刻有"取消后秒重装"接管登记表（`running[id] = jobB`），
     * 那时再读抓到的是**别人**的 job —— 删除事务会去等一次与它无关的安装（等到 deadline），
     * 而它自己刚取消的那一次却没人等。
     */
    @Test
    fun jobSnapshotIsTakenBeforeCancel() {
        val deleteBody = sliceFun(instancesSource(), "fun delete(id: String", "fun diskUsageAsync(id: String")
        val snapshotAt = deleteBody.indexOf("installJobOf(")
        val cancelAt = deleteBody.indexOf("installer.cancel(")
        assertTrue("前置：delete 里必须取过 installJobOf", snapshotAt >= 0)
        assertTrue("前置：delete 里必须调过 installer.cancel", cancelAt >= 0)
        assertTrue(
            "★ installJobOf 必须在 cancel() 之前（cancel 之后登记表可能已被新安装接管）：" +
                "snapshotAt=$snapshotAt cancelAt=$cancelAt",
            snapshotAt < cancelAt
        )
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 一个只**记录调用次数**的 [ProotExecutor] 替身。
     *
     * 本文件里它最重要的用途是"证明没被调用"：BL-244 的现场就是 cancel 之后这里被调了一次
     * （于是 npm 起来了）。所以它必须**真的可被调用**（返回 0，不抛），否则"没被调用"会因为
     * "反正调用就会炸"而失去意义 —— 变异验证时也看不出差别。
     */
    private class RecordingProot : ProotExecutor {
        val calls = AtomicInteger(0)
        override fun run(script: String, env: Map<String, String>, onLine: (String) -> Unit): Int {
            calls.incrementAndGet()
            return 0
        }
    }

    /**
     * 造一个"**本次就是它的活跃安装者**"的 Job 并登记进 `running`（照抄 R34 的 ownJob）。
     *
     * 不登记的话 `isActiveInstallOwner` 的第一道判据就为假 —— 检查点会"因为没人登记"而 abort，
     * 测试绿得莫名其妙（它测的就不是"取消"了）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun registerJob(installer: DshInstaller, instanceId: String): Job {
        val job = Job()
        val f = DshInstaller::class.java.getDeclaredField("running")
        f.isAccessible = true
        (f.get(installer) as MutableMap<String, Job>)[instanceId] = job
        return job
    }

    @Suppress("UNCHECKED_CAST")
    private fun markCancelled(installer: DshInstaller, instanceId: String) {
        val f = DshInstaller::class.java.getDeclaredField("cancelled")
        f.isAccessible = true
        (f.get(installer) as MutableSet<String>).add(instanceId)
    }

    /**
     * 反射调 `private suspend fun runInstall(instance, version, myJob)`。
     *
     * 编译后签名是 `runInstall(DshInstance, String, Job, Continuation)`：不挂起时**直接返回**
     * `Boolean`，挂起才返回 `COROUTINE_SUSPENDED`。本用例断言的那条路径（检查点早退）必然不挂起，
     * 所以这里直接读返回值；万一拿到的不是 Boolean，用例会明确报出来而不是静默绿。
     */
    private fun invokeRunInstall(installer: DshInstaller, inst: DshInstance, job: Job): Boolean {
        val m: Method = DshInstaller::class.java.getDeclaredMethod(
            "runInstall",
            DshInstance::class.java,
            String::class.java,
            Job::class.java,
            Continuation::class.java
        )
        m.isAccessible = true
        val cont = CapturingContinuation()
        val raw = m.invoke(installer, inst, VERSION, job, cont)
        return when {
            raw is Boolean -> raw
            cont.result is Boolean -> cont.result as Boolean
            else -> throw AssertionError(
                "runInstall 没有同步返回 Boolean（raw=$raw，continuation=${cont.result}）" +
                    " —— 检查点早退路径不该挂起，用例前提被破坏了"
            )
        }
    }

    private class CapturingContinuation : Continuation<Boolean> {
        override val context = EmptyCoroutineContext
        var result: Any? = null
        override fun resumeWith(result: Result<Boolean>) {
            this.result = result.getOrNull()
        }
    }

    private fun newInstaller(proot: ProotExecutor): DshInstaller = DshInstaller(fakeContext(), proot)

    private fun newInstance(id: String) = DshInstance(id = id, name = "R40 $id")

    /**
     * 把 [DshPaths] 指到临时目录，并**造出让 preflight 能通过**的最小现场
     * （proot 二进制 / rootfs 的 `bin/sh` / 脚本），返回临时根目录。
     *
     * 为什么必须做到这一步：这条用例的判别力完全来自"**run 一次都没被调用**"。若路径没就绪，
     * `runInstall` 会因为 `lateinit` 异常 / 预检失败而不调 run —— 检查点被删掉也照样绿，
     * 于是变异验证什么也证明不了（假绿）。所以这里把"能真的 fork"的前置全部摆好。
     */
    private fun withFullPaths(): File {
        val root = File(tmpRoot, "dsh-${System.nanoTime()}")
        val rootfs = File(root, "rootfs").apply { mkdirs() }
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("")
        val scripts = File(root, "scripts").apply { mkdirs() }
        File(scripts, "setup-node-dsh.sh").writeText("")
        File(root, "proot").mkdirs()
        File(File(root, "proot"), "libproot.so").writeText("")
        File(root, "instances").mkdirs()
        File(root, "npm-cache").mkdirs()
        File(root, "logs").mkdirs()
        File(root, "tmp").mkdirs()
        set("ROOT_DIR", root.absolutePath)
        set("ROOTFS_DIR", rootfs.absolutePath)
        set("INSTANCES_DIR", File(root, "instances").absolutePath)
        set("INSTANCES_MANIFEST", File(root, "instances.json").absolutePath)
        set("SCRIPTS_DIR", scripts.absolutePath)
        set("NPM_CACHE_DIR", File(root, "npm-cache").absolutePath)
        set("LOG_FILE", File(File(root, "logs"), "runtime.log").absolutePath)
        set("TARBALL_CACHE_DIR", File(root, "tarballs").absolutePath)
        set("TMP_DIR", File(root, "tmp").absolutePath)
        set("isLoaded", true)
        // DshUiText / DshDns 那条链会优先取进程级 App Context：留着别的用例塞进去的假 Context
        // 会让 preflight 在 `applicationContext` 上抛 Stub!，于是"没 fork"可能只是因为炸了 ——
        // 那正是本用例要排除的假绿来源。这里显式钉成 null（@After 复原）。
        if (!appContextSaved) {
            val f = DshAppContextHolder::class.java.getDeclaredField("context").apply { isAccessible = true }
            savedAppContext = f.get(null)
            appContextSaved = true
            f.set(null, null)
        }
        return root
    }

    private fun pathField(name: String) = DshPaths::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun set(name: String, value: Any?) {
        val f = pathField(name)
        if (name !in savedPaths) savedPaths[name] = runCatching { f.get(DshPaths) }.getOrNull()
        f.set(DshPaths, value)
    }

    /** 取某个实例日志流里当前的内存行（[DshLogBus.flushNow] 同步推进快照，不 sleep） */
    private fun instanceLogLines(instanceId: String): List<String> {
        DshLogBus.flushNow()
        return DshLogBus.snapshotFor(instanceId).value.lines
    }

    /**
     * 造一个**不碰构造**的 Context（`ContextWrapper` 的构造会调 android.jar 的 `Stub!`）。
     * 与 R34InstallErrorTest / R39InstallPidAtomicTest 同一手法。
     */
    private fun fakeContext(): Context {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val allocate: Method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ContextWrapper::class.java) as Context
    }

    // --- 源码形状工具（与 R39InstallPidAtomicTest 同口径） ----------------------

    private fun installerSource(): String = mainSourceFile("core/DshInstaller.kt").readText()

    private fun instancesSource(): String = mainSourceFile("core/DshInstances.kt").readText()

    /**
     * 从源码里切出一个**函数体**：取 [header] 之后、[nextHeader] 之前的那一段。
     *
     * 为什么不用"配对大括号"（R39 的做法）：KDoc 注释里会出现**单个**撇号（本仓有
     * `'请检查网络'` / `'详见日志'` 这类中文引号写成 ASCII 撇号的注释），一个撇号就会让
     * "跳过字符字面量"的扫描器误吞掉后面所有的括号 —— 切出来的片段会翻倍，断言于是落在
     * 完全无关的代码上（实测：`runInstall` 被切到文件尾，与 `onInstallLine` 里的
     * `(proot as? ProotProcessExecutor)?.run(` 撞车，红得莫名其妙）。
     * 用**下一个成员声明的行首锚点**切片没有这个问题：它只看行首，不看括号。
     *
     * 前提由调用方断言（找不到任一头都直接 AssertionError，不会静默切成空串）。
     */
    private fun sliceFun(src: String, header: String, nextHeader: String): String {
        val start = src.indexOf(header)
        if (start < 0) throw AssertionError("找不到 $header")
        val end = src.indexOf(nextHeader, start + header.length)
        if (end < 0) throw AssertionError("找不到 $header 之后的下一个成员声明：$nextHeader")
        return src.substring(start, end)
    }

    /** 定位主源码文件（与 R39 的 `mainSourceFile()` 同口径：从 cwd 往上找几层） */
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

    private companion object {
        const val INSTANCE_ID = "r40-fork-guard"
        const val VERSION = "0.2.1-alpha.1"
    }
}
