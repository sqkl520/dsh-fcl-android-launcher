package com.dsh

import android.content.Context
import android.content.ContextWrapper
import com.dsh.core.DshInstance
import com.dsh.core.DshInstaller
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.ProotExecutor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

/**
 * R44 / RE-08：`DshInstaller.cleanupStaleInstallProcess` 的**日志如实性**与 **pid 文件生命周期**。
 *
 * ## 缺陷（round44 审计 RE-08，复验后确认仍在）
 *
 * 1. `runCatching { sendSignal(pid, 3) }.isSuccess` 把 `SecurityException` 这类失败**折成布尔** ——
 *    信号没送出去时 `quitSent = false`，跳过等待，若进程恰好已死就打
 *    `exited within SIGQUIT grace`。**信号根本发不出去**，这句"证据"是假的。
 *    同文件的 `writeInstallPidFile` 早就用 `exceptionOrNull()` 分开成/败各记一行，这里不一致。
 * 2. SIGKILL 兜底**没有 `onFailure`** —— 兜底也失败时完全静默（"以为杀过了"）。
 * 3. 函数末尾 `runCatching { f.delete() }` **无条件**删 pid 文件，不看进程是否真的死了。
 *    两个信号都失败时，"防双写"的唯一线索被删掉（`DshRuntime.stop` 对 `dsh.pid` 是**保留**的）。
 *
 * ## 覆盖层次（说清哪条是行为、哪条是形状）
 *
 * | 层 | 用例 | 证明了什么 |
 * |---|---|---|
 * | 行为（真跑生产代码） | [notAlivePidFileIsRemoved] | pid 指向不存在的进程 → 走 `!isAlive` 分支 → 文件被删、日志说 `is not alive` |
 * | 行为 | [unreadablePidFileIsRemoved] | 文件存在但读不出 → `pid == null` 分支 → 文件被删 |
 * | 行为 | [pidFileLifecycleEndsInADeletedFileForAPidThatIsNotAlive] | 完整函数跑完后目标文件消失、目录里不留 tmp |
 * | **结构钉子** | [sendSignalFailuresAreNotFlattenedToBoolean] / [sigkillHasAnOnFailureLogLine] / [pidFileDeletionIsGuardedByLiveness] | 上面三条缺陷各自"改回去就红" |
 *
 * ## 行为测试**做不到**的那部分（如实说明）
 *
 * `SIGQUIT`/`SIGKILL` 那两支需要"一个 cmdline 里同时含 `proot` 与实例 id、且**真的活着**的进程"。
 * 那意味着测试要 fork 真 proot 并对自己发信号 —— 既不可跨平台（Windows 单测宿主上 `isAlive`
 * 恒为 false，因为没有 `/proc`，且 `android.os.Process.sendSignal` 是 stub），也不安全（真的杀进程）。
 * 所以：
 * - 信号分支只做**源码形状钉**（变异验证里把 `exceptionOrNull` 改回 `.isSuccess` 会精确变红）；
 * - 行为测试只覆盖**不需要活进程**的两支（`pid == null` 与 `!isAlive`），它们在两种宿主上语义一致。
 *
 * 运行：`sh run-tests.sh`（自动扫描本类），或
 * `./gradlew :FCL:testDebugUnitTest --tests "com.dsh.R44StaleInstallCleanupTest"`。
 */
class R44StaleInstallCleanupTest {

    private val tmpRoot: File = Files.createTempDirectory("r44-stale-cleanup").toFile()
    private val savedPaths = HashMap<String, Any?>()

    /** 每个用例开始前重建日志流（LRU 淘汰会让常驻 id 的流被别的用例挤掉，同 R40 的做法） */
    @Before
    fun resetInstanceLogStream() {
        runCatching { DshLogBus.removeStream(INSTANCE_ID) }
    }

    @After
    fun restoreGlobalState() {
        savedPaths.forEach { (name, value) ->
            if (value === null) return@forEach
            runCatching { pathField(name).set(DshPaths, value) }
        }
        runCatching { DshLogBus.removeStream(INSTANCE_ID) }
    }

    // ==================================================================
    // 一、行为：不需要活进程的两支
    // ==================================================================

    /**
     * pid 文件里记着一个**不存在**的 pid → `!isAlive(pid)` 分支 → 文件被删、日志如实说 `is not alive`。
     *
     * 这是整个函数里唯一"不杀任何东西"的清理分支，也是最常见的那一支（上次安装正常结束后
     * App 被杀留下的文件）。它原来就已经正确，本用例把它钉住，防止 RE-08 的改动把它带偏。
     */
    @Test
    fun notAlivePidFileIsRemoved() {
        val inst = newInstance()
        val installer = newInstaller()
        val dir = instanceDir()
        writeInstallPidFile(installer, inst, DEAD_PID)
        val f = File(dir, DshInstaller.INSTALL_PID_FILE_NAME)
        assertTrue("前置：pid 文件必须真的写出来了", f.isFile)

        invokeCleanup(installer, inst)

        assertFalse(
            "★ pid 指向已不存在的进程时必须删掉 pid 文件（否则下次安装永远重复检查同一个死 pid）",
            f.exists()
        )
        val lines = instanceLogLines()
        val hit = "stale installer pid=$DEAD_PID is not alive; removed stale pid file"
        assertTrue("★ 日志必须如实说明「进程已不在、文件已删」：期望含「$hit」，实际：$lines",
            lines.any { it.contains(hit) })
    }

    /**
     * 文件存在但**读不出**（坏 JSON）→ `pid == null` 分支 → 文件被删，且不误杀任何人。
     *
     * 为什么单独一条：RE-08 只改了"进程还活着"那条路径的删文件条件，`pid == null` 这条
     * **必须保持无条件删**（它就是坏文件的收容所）。这里把"别动它"变成可执行的守卫。
     */
    @Test
    fun unreadablePidFileIsRemoved() {
        val inst = newInstance()
        val installer = newInstaller()
        val dir = instanceDir()
        val f = File(dir, DshInstaller.INSTALL_PID_FILE_NAME)
        f.writeText("{not json")
        assertTrue("前置：坏文件必须存在", f.isFile)

        invokeCleanup(installer, inst)

        assertFalse("★ 「存在但读不出」的坏 pid 文件必须被删（不删等于每次安装都白检查一遍）", f.exists())
        val lines = instanceLogLines()
        assertTrue(
            "★ pid==null 分支不许打「is not alive」这类假证据（它压根没拿到 pid）：$lines",
            lines.none { it.contains("is not alive") }
        )
    }

    /**
     * 完整跑完之后目录里只剩实例目录本身：目标文件消失、`atomicWriteText` 的 tmp 也没残留。
     *
     * 这条同时守"别把 tmp 文件当成 pid 文件留下来"（`atomicWriteText` 的 tmp 与目标同目录）。
     */
    @Test
    fun pidFileLifecycleEndsInADeletedFileForAPidThatIsNotAlive() {
        val inst = newInstance()
        val installer = newInstaller()
        val dir = instanceDir()
        writeInstallPidFile(installer, inst, DEAD_PID)

        invokeCleanup(installer, inst)

        val leftovers = dir.listFiles()?.map { it.name } ?: emptyList()
        assertEquals("★ 目录里不该留下任何 pid 相关文件（含 tmp）：$leftovers", 0, leftovers.size)
    }

    // ==================================================================
    // 二、结构钉子（变异靶子：改回去必须精确变红）
    // ==================================================================

    /**
     * `sendSignal(pid, 3)` 的成败**不许折成布尔**。
     *
     * 靶子是 `.isSuccess`：它把 `SecurityException` / `IllegalArgumentException` 一起压成 `false`，
     * 而调用方只能读到"没成功"，于是日志里那句 `exited within SIGQUIT grace` 会在"信号从没发出去"
     * 的情况下照打（审计 RE-08 问题 1）。本仓的正确写法就在同文件的 `writeInstallPidFile`
     * （`runCatching { ... }.exceptionOrNull()` + 两行分开的日志），这里必须一致。
     *
     * ⚠️ 必须**剥注释**再找：本函数的 KDoc 正在讨论 `.isSuccess` 这个写法（上面刚写着），
     * 裸 `contains` 会命中注释 ⇒ 把代码改回去也照样绿（本仓 R40 实测踩过这个假绿）。
     */
    @Test
    fun sendSignalFailuresAreNotFlattenedToBoolean() {
        val body = cleanupBody()
        assertFalse(
            "★ cleanupStaleInstallProcess 的可执行代码里不许出现 `.isSuccess` —— " +
                "它把「异常」与「信号发出去了」压成同一个布尔，日志于是会说出没有证据的话（RE-08 问题 1）",
            body.contains(".isSuccess")
        )
        assertTrue(
            "★ 必须用 exceptionOrNull() 拿到真实异常（与同文件 writeInstallPidFile 同一口径），" +
                "才能把「压根没发出去」与「发出去了但没退」分成两行日志",
            body.contains("exceptionOrNull(")
        )
        // 失败时不许进等待循环：没发出去等 2 秒没有任何意义（也不许引入新预算）
        assertTrue(
            "★ 「SIGQUIT 没发出去」必须有一条自己的日志（措辞里要能看出信号没送达）",
            body.contains("NOT delivered")
        )
        assertEquals(
            "★ SIGQUIT 宽限预算必须仍然是唯一一处 2s（不新增等待）：" +
                "System.currentTimeMillis() + STALE_INSTALL_QUIT_GRACE_MS 应恰好出现一次",
            1,
            Regex("System\\.currentTimeMillis\\(\\) \\+ STALE_INSTALL_QUIT_GRACE_MS").findAll(body).count()
        )
    }

    /**
     * SIGKILL 兜底必须有 `onFailure` 留痕（RE-08 问题 2）。
     *
     * 靶子：`runCatching { sendSignal(pid, 9) }` 后面跟 `.onFailure { ... }`。把它删成光秃秃的
     * `runCatching { ... }`，"兜底也失败"就重新变成完全静默 —— 排障者会以为进程已经死透。
     */
    @Test
    fun sigkillHasAnOnFailureLogLine() {
        val body = cleanupBody()
        val killAt = body.indexOf("sendSignal(pid.toInt(), 9)")
        assertTrue("前置：必须能定位到 SIGKILL 那一行", killAt >= 0)
        val tail = body.substring(killAt)
        val onFailureAt = tail.indexOf(".onFailure")
        val onFailureBlock = if (onFailureAt >= 0) tail.substring(onFailureAt) else ""
        assertTrue(
            "★ onFailure 里必须真的写日志（DshLogBus.appendFor），不能是空 lambda",
            onFailureBlock.contains("DshLogBus.appendFor(")
        )
    }

    /**
     * **末尾删 pid 文件必须有存活判据参与**（RE-08 问题 3）。
     *
     * 靶子：把末尾 `if (!ProcessUtil.isAlive(pid)) { f.delete() } else { ... }` 改回
     * 无条件的 `runCatching { f.delete() }`。那会在"两个信号都没发出去、进程还在写 node_modules"
     * 时删掉唯一的线索，下一次安装再也认领不到它（双写保护失效）。
     *
     * ## 为什么断言"最后一处 SIGKILL 之后"的那一段（而不是整个函数体）
     *
     * 函数体里 `isAlive` 出现多次（入口判死、等待循环、SIGKILL 前后），只对整段做 contains
     * 的话，**把末尾那道判据删掉、前面几处还在**时照样绿（典型假绿）。所以把切片起点钉在
     * `sendSignal(pid.toInt(), 9)`（全函数唯一一处）之后，再要求这一段里
     * `!ProcessUtil.isAlive(pid)` 存在、且**紧跟着**的那个 `f.delete()` 在它之后
     * （而不是取这一段里第一个 `f.delete()` —— 那是 cmdline 不匹配分支里那个，顺序会对不上）。
     */
    @Test
    fun pidFileDeletionIsGuardedByLiveness() {
        val body = cleanupBody()
        val killAt = body.indexOf("sendSignal(pid.toInt(), 9)")
        assertTrue("前置：必须能定位到 SIGKILL 那一行", killAt >= 0)
        val tail = body.substring(killAt)
        val guardAt = tail.indexOf("!ProcessUtil.isAlive(pid)")
        assertTrue(
            "★ SIGKILL 之后删 pid 文件必须由 `!ProcessUtil.isAlive(pid)` 把关 —— " +
                "无条件删会在「两个信号都没发出去」时丢掉防双写的唯一线索（RE-08 问题 3）。" +
                "实际片段：${tail.take(400)}",
            guardAt >= 0
        )
        val deleteAt = tail.indexOf("f.delete()", guardAt)
        assertTrue(
            "★ 存活判据后面必须真的跟着一次删 pid 文件（否则判了也不删 = 文件永远留不下来清理）：" +
                "guardAt=$guardAt",
            deleteAt > guardAt
        )
        assertTrue(
            "★ 进程仍在时必须明确**保留**文件并留痕（说明「留着给下次认领」），不能静默跳过",
            tail.contains("keeping") || tail.contains("STILL alive")
        )
    }

    // ==================================================================
    // 三、夹具
    // ==================================================================

    private fun newInstance() = DshInstance(id = INSTANCE_ID, name = "R44 stale cleanup")

    private fun newInstaller(): DshInstaller = DshInstaller(fakeContext(), NoopProot)

    /**
     * 把 `DshPaths` 指到临时目录并返回实例目录（已 mkdirs）。
     *
     * 只动 `<INSTANCES_DIR>` / `<LOG_FILE>` / `isLoaded`：本用例不跑 proot，不需要 rootfs 等路径。
     */
    private fun instanceDir(): File {
        if ("INSTANCES_DIR" !in savedPaths) {
            val root = File(tmpRoot, "dsh-${System.nanoTime()}")
            set("INSTANCES_DIR", File(root, "instances").apply { mkdirs() }.absolutePath)
            set("LOG_FILE", File(File(root, "logs").apply { mkdirs() }, "runtime.log").absolutePath)
            set("isLoaded", true)
        }
        return DshPaths.instanceDir(INSTANCE_ID).apply { mkdirs() }
    }

    private fun pathField(name: String) =
        DshPaths::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun set(name: String, value: Any?) {
        val f = pathField(name)
        if (name !in savedPaths) savedPaths[name] = runCatching { f.get(DshPaths) }.getOrNull()
        f.set(DshPaths, value)
    }

    /** 用生产写口写 pid 文件（不自己拼 JSON：格式漂了就是认领契约漂了） */
    private fun writeInstallPidFile(installer: DshInstaller, inst: DshInstance, pid: Long) {
        val m: Method = DshInstaller::class.java.getDeclaredMethod(
            "writeInstallPidFile", DshInstance::class.java, java.lang.Long.TYPE
        )
        m.isAccessible = true
        m.invoke(installer, inst, pid)
    }

    /**
     * 反射调 `private suspend fun cleanupStaleInstallProcess(instance)`。
     *
     * 编译后签名是 `cleanupStaleInstallProcess(DshInstance, Continuation)`。本文件覆盖的两条路径
     * （`pid == null` / `!isAlive`）**没有挂起点**，会同步返回；若哪天它们挂了起，会明确报错
     * 而不是静默绿。
     */
    private fun invokeCleanup(installer: DshInstaller, inst: DshInstance) {
        val m: Method = DshInstaller::class.java.getDeclaredMethod(
            "cleanupStaleInstallProcess", DshInstance::class.java, Continuation::class.java
        )
        m.isAccessible = true
        val cont = CapturingContinuation()
        val raw = m.invoke(installer, inst, cont)
        // suspend fun 返回 Unit 时编译成 `Object`：不挂起就返回 Unit.INSTANCE，挂起则返回
        // COROUTINE_SUSPENDED（std库的 internal 单例，这里不去引用它 —— 只判断"是不是 Unit"，
        // 不是就明确报错，而不是静默绿）。本用例覆盖的两支都没有挂起点。
        if (raw !== kotlin.Unit) {
            throw AssertionError(
                "cleanupStaleInstallProcess 在这条路径上挂起了或返回了非 Unit（raw=$raw, resumed=${cont.resumed}）" +
                    " —— 本用例只覆盖无挂起点的两支，前提被破坏了"
            )
        }
    }

    private class CapturingContinuation : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        var resumed = false
        override fun resumeWith(result: Result<Unit>) {
            resumed = true
            result.getOrNull()
        }
    }

    /** 取某个实例日志流里当前的内存行（[DshLogBus.flushNow] 同步推进快照，不 sleep） */
    private fun instanceLogLines(): List<String> {
        DshLogBus.flushNow()
        return DshLogBus.snapshotFor(INSTANCE_ID).value.lines
    }

    /** 不碰构造的 Context（`ContextWrapper` 的构造会调 android.jar 的 `Stub!`），同 R39/R40 手法 */
    private fun fakeContext(): Context {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val allocate: Method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ContextWrapper::class.java) as Context
    }

    /** 本文件不跑 proot；真被调到就是用例写错了，直接炸出来 */
    private object NoopProot : ProotExecutor {
        override fun run(script: String, env: Map<String, String>, onLine: (String) -> Unit): Int =
            throw AssertionError("R44StaleInstallCleanupTest 不跑 proot")
    }

    // --- 源码形状工具（与 R40InstallForkGuardTest / R39InstallPidAtomicTest 同口径） ---

    private fun installerSource(): String = mainSourceFile("core/DshInstaller.kt").readText()

    /** `cleanupStaleInstallProcess` 的源码切片（含 KDoc 尾巴），已剥注释 */
    private fun cleanupBody(): String {
        val body = sliceFun(
            installerSource(),
            "private suspend fun cleanupStaleInstallProcess(",
            "private fun deleteInstallPidFile("
        )
        return stripComments(body)
    }

    /**
     * 剥掉行注释与块注释，只留可执行代码骨架。
     *
     * ⚠️ 本文件的断言全部锚在**标识符**上（`.isSuccess` / `exceptionOrNull(` / `onFailure` /
     * `isAlive`），而本仓正好有"裸 contains 命中 KDoc 里的同名词 ⇒ 假绿"的实测教训
     * （`R40InstallForkGuardTest.deleteJoinsOutsideTheInstallerLock`）。这里的函数 KDoc
     * **就在讨论** `.isSuccess`，不剥注释的话把代码改回去也照样绿。
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
     * 从源码里切出一个函数：取 [header] 之后、[nextHeader] 之前的那一段。
     *
     * 不用配对大括号（R39 的 `endOfBlock`）的理由见 R40：KDoc 里的单个 ASCII 撇号会让
     * "跳过字符字面量"的扫描器误吞后续括号。行首锚点切片没有这个问题。
     */
    private fun sliceFun(src: String, header: String, nextHeader: String): String {
        val start = src.indexOf(header)
        if (start < 0) throw AssertionError("找不到 $header")
        val end = src.indexOf(nextHeader, start + header.length)
        if (end < 0) throw AssertionError("找不到 $header 之后的下一个成员声明：$nextHeader")
        return src.substring(start, end)
    }

    /** 定位主源码文件（从 cwd 往上找，兼容电脑两仓布局与手机单仓布局） */
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
        const val INSTANCE_ID = "r44-stale-cleanup"

        /**
         * 一个**确定不存在**的 pid。
         *
         * 选 `Int.MAX_VALUE - 3`（2147483644）：Linux 的 pid 上限是 4194304（`/proc/sys/kernel/pid_max`），
         * 所以 `/proc/2147483644` 必然不存在；而 Windows 单测宿主上根本没有 `/proc`，
         * `android.os.Process.sendSignal` 又是 stub（抛 `Stub!`，被 `runCatching` 吃掉）——
         * 两种宿主上 `ProcessUtil.isAlive` 都返回 false，用例语义一致。
         */
        const val DEAD_PID = 2147483644L
    }
}
