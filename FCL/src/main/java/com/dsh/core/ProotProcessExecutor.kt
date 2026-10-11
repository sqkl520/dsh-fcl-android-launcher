package com.dsh.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * [ProotExecutor] 的真机实现：用打包在 App 里的 proot + rootfs 执行 rootfs 内的脚本
 * （"跑到结束"的语义；长驻进程见 [ProotRunner]）。
 *
 * 与 FCL 的运行时差异：FCL 用 native dlopen libjvm 在同进程跑；dsh 是外部进程，走 ProcessBuilder + proot。
 * proot 二进制随 APK 打包在 nativeLibraryDir（以 lib*.so 命名规避安卓对可执行文件的限制）。
 *
 * 命令构造已抽到 [ProotCommand]（原来安装/启动两处各写一份，已经漂移）。
 * 超时：给"跑到结束"的任务加看门狗，避免 npm 卡住导致状态永远停在 INSTALLING。
 */
class ProotProcessExecutor(
    private val context: Context,
    /** rootfs 在安卓文件系统里的宿主目录（解压后的 Ubuntu/Alpine arm64） */
    private val rootfsDir: String = DshPaths.ROOTFS_DIR
) : ProotExecutor {

    companion object {
        /** 看门狗超时后的约定退出码（调用方拿它翻译成\"安装超时\"等可读文案） */
        const val TIMEOUT_EXIT_CODE = -2
    }

    override fun run(
        script: String,
        env: Map<String, String>,
        onLine: (String) -> Unit
    ): Int = run(
        script = script,
        argvEnv = env,
        secretEnv = emptyMap<String, String>(),
        onLine = onLine
    )

    /**
     * @param argvEnv 非敏感变量（进 argv）
     * @param secretEnv 敏感变量（只进进程环境，不进 argv）
     * @param timeoutMs 超时毫秒；<=0 表示不超时
     * @param tag 任务归属标记（用 instanceId）：[destroyActive] 只杀 tag 匹配的进程，
     *            避免同时装两个实例时"取消 A 把 B 一起杀了"
     */
    fun run(
        script: String,
        argvEnv: Map<String, String>,
        secretEnv: Map<String, String>,
        timeoutMs: Long = 0,
        tag: String? = null,
        onLine: (String) -> Unit
    ): Int {
        val pre = ProotCommand.preflight(context, rootfsDir, script)
        if (!pre.ok) {
            onLine("[proot] 预检失败: ${pre.reason}")
            return -1
        }
        val spec = ProotCommand.build(
            context = context,
            script = script,
            argvEnv = argvEnv,
            procEnv = secretEnv,
            rootfsDir = rootfsDir
        )
        // process 提到 try 外，便于 finally 里做\"登记表\"清理（见下）
        var process: Process? = null
        return try {
            // ★ K01 pid 捕获：先拍子进程快照、再 start —— start 后"新出现的子进程"就是我们这条 proot
            //   （复用 ProotRunner 做法）。拿到 pid 才能发 SIGQUIT；proot 对 SIGTERM 装死，唯 SIGQUIT
            //   会触发它的 kill_all_tracees 把 npm/node 一起带走。
            val preExisting = ProotRunner.snapshotChildPids()
            val p = spec.toProcessBuilder().start()
            process = p
            // BL-001：从不往子进程 stdin 写东西，start 后立即关掉写端 —— 否则 FD 泄漏到整个任务生命周期；
            //   且子进程若读 stdin 会看到 EOF 而不是永久阻塞。
            runCatching { p.outputStream.close() }
            // pid 解析复用 ProotRunner.Handle（反射 → /proc 兜底，真机走 /proc）；拿不到返回 -1。
            val pid = runCatching { ProotRunner.Handle(p, preExisting).pid() }.getOrDefault(-1L)
            val active = Active(tag, p, pid)
            synchronized(this) { actives += active }
            // 跨线程可见：看门狗线程写、当前线程读，必须用 Atomic/volatile，否则可能读到旧值把超时误判为正常退出。
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = if (timeoutMs > 0) {
                Thread {
                    try {
                        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                            timedOut.set(true)
                            // R-LOG：英文诊断行（原为中文）。
                            runCatching { onLine("[proot] timeout after ${timeoutMs / 1000}s, terminating task") }
                            // K01：先 SIGQUIT（proot 忽略 SIGTERM）让 proot 收掉 npm/node，再强杀兜底，超时不留孤儿。
                            terminate(active)
                        }
                    } catch (_: InterruptedException) {
                    }
                }.apply { isDaemon = true; name = "dsh-proot-watchdog"; start() }
            } else null

            var callbackFailed = false
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // BL-002：单行回调抛异常不能毁掉整个读循环（那会停止抽干管道、让进程卡住变孤儿）。
                    //         吞掉单行异常、继续读，把进程读到自然退出。
                    if (runCatching { onLine(line!!) }.isFailure) callbackFailed = true
                }
            }
            if (callbackFailed) {
                // best-effort、英文（R-LOG）；再套 runCatching，免得还在抛的回调把收尾也带崩。
                runCatching { onLine("[proot] output callback threw; drained remaining output and continued") }
            }
            val code = p.waitFor()
            // 让看门狗线程结束（进程已退出时它自然返回）
            watchdog?.interrupt()
            if (timedOut.get()) TIMEOUT_EXIT_CODE else code
        } catch (e: Exception) {
            onLine("[proot] 执行失败: ${e.message}")
            -1
        } finally {
            // ★ 原来只在正常路径清理 actives：读输出时抛异常（例如进程被外部杀掉、管道断裂）
            // 会在 actives 里永久留下一条死进程记录（后续 destroyActive 遍历它纯属空转）。
            // 这里在 finally 里补一次清理，但**只清已经退出的** —— 还活着的进程必须留在表里，
            // 否则\"取消安装\"就再也找不到它（那才是真正的孤儿 npm）。
            val p = process
            if (p != null) {
                // 只清已退出的（见 [ProotTermination.shouldReapOnFinally]）：还活着的要留在表里，
                // 否则后续 destroyActive 再也找不到它去杀，那才是真正的孤儿 npm。
                val alive = runCatching { p.isAlive }.getOrDefault(false)
                if (ProotTermination.shouldReapOnFinally(alive)) {
                    synchronized(this) { actives.removeAll { it.process === p } }
                }
            }
        }
    }

    /**
     * 终止正在跑的子进程（安装取消 / App 退出清理用）。
     * @param tag 传 instanceId 时只杀属于该实例的进程；传 null 表示"不管是谁，全部杀掉"。
     *
     * ★ 第五轮修复：原来只记一个 `active` 槽位——同时装两个实例时，后启动的那个会把前一个覆盖掉，
     * 于是"取消 A"根本找不到 A 的进程（什么都不杀，A 的 npm 继续写 node_modules），
     * 日志里还会出现"取消了但还在装"的怪象。现在按 tag 记录**所有**在跑的子进程。
     */
    fun destroyActive(tag: String? = null) {
        runCatching {
            val snapshot = synchronized(this) { actives.toList() }
            snapshot.forEach { a ->
                // tag 过滤语义不变（见 [ProotTermination.tagMatches]）：传 null 杀全部，传 instanceId 只杀它。
                if (ProotTermination.tagMatches(tag, a.tag)) terminate(a)
            }
        }
    }

    /**
     * 真正终止一条 proot：**SIGQUIT → 等 → destroyForcibly 兜底**。
     *
     * ★ K01：原来只发 `destroy()`（=SIGTERM），而 proot 的事件循环把 SIGTERM 装成 SIG_IGN —— 根本
     * 杀不动它，npm/node 变孤儿继续占端口（EADDRINUSE 根因之一）。有 pid 就先 SIGQUIT（proot 唯一会
     * 响应、且会顺手 kill 掉所有 tracee 的信号），等一会儿还活着再 destroyForcibly；拿不到 pid 时退化
     * 为 `destroy()` → 等 → forcibly（与改动前同量级，只是少了 SIGQUIT 的收尾）。
     *
     * 非阻塞：首个信号就地发；"等待 + 强杀"的升级放到短命守护线程，避免阻塞调用方（取消/超时可能在主线程）。
     */
    private fun terminate(active: Active) {
        val p = active.process
        when (ProotTermination.firstStopSignal(active.pid)) {
            // SIGQUIT 让 proot 走 kill_all_tracees：先把 npm/node 全 SIGKILL，再自己退出。
            ProotTermination.StopSignal.SIGQUIT ->
                runCatching { android.os.Process.sendSignal(active.pid.toInt(), ProotTermination.SIGQUIT) }
            // 拿不到 pid：只剩 Process.destroy() 这个把手（对 proot 无效，但没有别的办法了）。
            ProotTermination.StopSignal.DESTROY -> runCatching { p.destroy() }
        }
        Thread {
            try {
                val exited = runCatching { p.waitFor(ProotTermination.TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS) }
                    .getOrDefault(false)
                if (!exited && ProotTermination.shouldForceKill(runCatching { p.isAlive }.getOrDefault(false))) {
                    // 最后手段 destroyForcibly()=SIGKILL。proot 被 SIGKILL 时来不及 kill_all_tracees，
                    // rootfs 内的 node 可能存活 —— 那条按 pid 文件收 node 的兜底在 DshRuntime（本文件管不到）。
                    runCatching { p.destroyForcibly() }
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; name = "dsh-proot-kill"; start() }
    }

    /** 当前活跃子进程（含归属标记与 pid；pid<=0 表示没解析到，[terminate] 会退化为 destroy()） */
    private class Active(val tag: String?, val process: Process, val pid: Long)

    /** 所有在跑的子进程（CopyOnWriteArrayList：读多写少，且要能在并发下安全遍历） */
    private val actives = java.util.concurrent.CopyOnWriteArrayList<Active>()
}

/**
 * 纯逻辑的终止决策（无副作用），单独抽出来是为了**能单测**：真机信号
 * (`android.os.Process.sendSignal`) 与 `Process.destroy()` 在 JVM 单测里没有真进程、或只是 android.jar
 * 的桩，没法直接验；但"有 pid 该不该 SIGQUIT""tag 该不该匹配""finally 该不该清表""还活着才强杀"
 * 这些**判断**是纯函数，可以钉成断言。见 ProotTerminationTest。
 */
object ProotTermination {
    /**
     * SIGQUIT (3)。proot 的事件循环对除 SIGQUIT/SIGILL/SIGABRT/SIGFPE/SIGSEGV 以外的信号全部
     * SIG_IGN，所以 `Process.destroy()`（SIGTERM）对它完全无效。SIGQUIT 会驱动 proot 的
     * kill_all_tracees()：先把每个 tracee（npm/node 整棵树）SIGKILL，再退出 —— 唯一能"父进程死时
     * 把子进程一起带走"的信号。
     */
    const val SIGQUIT = 3

    /** 首个停止信号发出后、升级到 destroyForcibly() 之前，留给它生效的宽限时间（毫秒）。 */
    const val TERMINATION_GRACE_MS = 3000L

    /** 首次停止用的机制。 */
    enum class StopSignal { SIGQUIT, DESTROY }

    /**
     * 首个停止机制：解析到 proot pid 就发 SIGQUIT（proot 唯一会响应的信号）；拿不到 pid
     * （pid<=0：桌面反射不可用、且 /proc 兜底也失败）就退化为 `Process.destroy()` —— 对 proot 无效，
     * 但那是仅剩的把手。
     */
    @JvmStatic
    fun firstStopSignal(pid: Long): StopSignal =
        if (pid > 0) StopSignal.SIGQUIT else StopSignal.DESTROY

    /**
     * 终止请求的归属（tag 过滤契约）：请求 tag 为 null 杀所有在跑的子进程；指定 tag 只匹配登记在同一
     * instanceId 下的那条（"取消 A 绝不能杀 B"）。
     */
    @JvmStatic
    fun tagMatches(requested: String?, activeTag: String?): Boolean =
        requested == null || activeTag == requested

    /**
     * finally 清表规则：只有真正退出的进程才从登记表摘掉。还活着的必须留着，否则后续 destroyActive
     * 再也找不到它去杀 —— 那正是 npm/node 孤儿逃逸的方式。
     */
    @JvmStatic
    fun shouldReapOnFinally(stillAlive: Boolean): Boolean = !stillAlive

    /** 首个信号 + 宽限后仍存活，才升级到 destroyForcibly()。 */
    @JvmStatic
    fun shouldForceKill(stillAlive: Boolean): Boolean = stillAlive
}

/**
 * 长驻进程工具：启动一个 proot 进程，把 stdout 逐行喂给回调，并在退出时回调。
 * 安装器与运行时都复用它，避免各写一套 readLine 循环。
 */
object ProotRunner {

    /**
     * 把反射/`Number` 里拿到的 pid 统一成 `Long`，拿不到返回 **-1**。
     *
     * ★ 这就是 B10 的根因所在，所以单独抽成纯函数、单独上单测：
     * 反射调用返回的是**装箱对象**。`Process.pid()` 若声明为 `int`，`Method.invoke` 交回来的是
     * `java.lang.Integer`，而 `Integer as Long` 在 Kotlin 里**不是**"转成 Long"，是**类型断言**——
     * 必然抛 `ClassCastException`。旧写法 `m.invoke(process) as Long` 正是这样：
     * 异常被外层 `runCatching` 吞掉 → **永远返回 -1** → `dsh.pid` 从来没写出来过 →
     * 孤儿认领/清理这条路径**从未生效**（真机日志里那条"无法取得 proot 进程 pid"警告）。
     *
     * 为什么必须用 `Number` 而不是逐个 `is Int`/`is Long`：Kotlin 的 `Number` 覆盖了
     * `Integer`/`Long`/`Short`/`Byte`，一次收全，不会再漏掉某种装箱类型。
     * 负值/0 **原样透出**，不"修正"成正数：调用方是 `if (pid > 0) 写文件 else 打警告`，
     * 悄悄改成 1 会写出假 pid，下次清理时误杀别人的进程。
     */
    @JvmStatic
    fun toPid(raw: Any?): Long = (raw as? Number)?.toLong() ?: -1L

    /**
     * 列出"当前进程此刻的所有直接子进程 pid"（所有线程的并集）。
     *
     * 用途：`start()` 之前先拍一张快照，作为 [findChildPidFromProc] 的排除集。
     * 为什么不直接用"第一个子进程"：启动器可以**同时**有别的子进程 ——
     * 例如正在跑自检的 `runOnce`、或另一个实例的 proot。不排除就会认错进程，
     * 进而写出假 pid，下次清理时误杀无辜（`killBlocking` 的 cmdline 校验是最后一道防线，
     * 但不该指望它兜住"一开始就选错"）。
     */
    @JvmStatic
    fun snapshotChildPids(procRoot: String = "/proc"): Set<Long> {
        val selfTask = File(procRoot, "self/task")
        val tids = selfTask.listFiles()?.mapNotNull { it.name.toLongOrNull() } ?: return emptySet()
        val out = HashSet<Long>()
        for (tid in tids) {
            val text = runCatching { File(selfTask, "$tid/children").readText() }.getOrNull() ?: continue
            text.split(' ', '\n', '\t').mapNotNullTo(out) { it.trim().toLongOrNull() }
        }
        return out
    }

    /**
     * 从 `/proc` 里找一个进程的子进程 pid（**B10 的第二层兜底**）。
     *
     * 为什么需要它：`api-versions.xml`（android-35）里 `java/lang/Process` 只有
     * `destroy`/`exitValue`/`isAlive`/`waitFor`…，**没有 `pid()`** —— 那是 Java 9+ 的 API。
     * `android.jar` 里能编过只是编译期假象（android.jar 是全量 API 存根），
     * 运行期 `getMethod("pid")` 抛 `NoSuchMethodException`。所以光修装箱 bug 还不够：
     * 真机上那条反射路径**根本不会成功**。
     *
     * 原理：Linux 的 `/proc/<pid>/task/<tid>/children` 是该线程的**直接子进程**列表
     * （空格分隔，内核 `CONFIG_PROC_CHILDREN` 打开时才有；Android 内核有）。
     * 我们刚 start 的 proot 就是我们进程的子进程 → 与启动前快照求差集即得。
     *
     * @param procRoot    `/proc` 根目录。做成参数是为了**能用假目录喂单测**（/proc 没法在测试里造）
     * @param excludePids 启动前已存在的子进程 pid（见 [snapshotChildPids]）
     * @return 新出现的子进程 pid；取不到返回 -1
     */
    @JvmStatic
    fun findChildPidFromProc(procRoot: String = "/proc", excludePids: Set<Long> = emptySet()): Long {
        val selfTask = File(procRoot, "self/task")
        val tids = selfTask.listFiles()?.mapNotNull { it.name.toLongOrNull() } ?: return -1L
        // LinkedHashSet：保持 tid 顺序稳定（同一 tid 多次出现的 pid 不重排），让结果可复现
        val children = LinkedHashSet<Long>()
        for (tid in tids) {
            val text = runCatching { File(selfTask, "$tid/children").readText() }.getOrNull() ?: continue
            text.split(' ', '\n', '\t')
                .mapNotNull { it.trim().toLongOrNull() }
                .forEach { children.add(it) }
        }
        return children.firstOrNull { it > 0 && it !in excludePids } ?: -1L
    }

    /** 启动后的句柄 */
    class Handle(
        val process: Process,
        /** 启动前的子进程快照 —— `/proc` 兜底要拿它做差集 */
        private val preExistingChildren: Set<Long> = emptySet()
    ) {
        /**
         * 进程 pid。**两条路径**：先反射（桌面 JVM 有 `pid()`），失败再走 `/proc` 兜底（真机走这条）。
         *
         * 缓存成功值：pid 不会变，而 `/proc` 兜底要扫 task 目录，不该每次调用都扫。
         * **不缓存 -1**：进程刚 start 时 `/proc` 目录可能还没建好，缓存了 -1 就永远拿不到了。
         *
         * BL-027：返回 -1 表示两条路都失败 —— 此时打一条英文诊断行（见 [logPidFailureOnce]），
         * 每个 Handle 只打一次；返回值与解析逻辑不变。
         */
        @Volatile
        private var cachedPid: Long = 0

        fun pid(): Long {
            val c = cachedPid
            if (c > 0) return c
            val resolved = pidViaReflection() ?: pidViaProc()
            if (resolved > 0) cachedPid = resolved
            // ★ 只加诊断、不改逻辑：-1 意味着反射与 /proc 两条路都死了（真机两次启动全 -1，
            //   光看返回值分不清是 tids 扫不到、children 读不到、还是差集为空）。
            if (resolved <= 0) logPidFailureOnce()
            return resolved
        }

        /** 路径一：反射 `Process.pid()`（桌面 JVM 有；真机大概率 `NoSuchMethodException`） */
        private fun pidViaReflection(): Long? = runCatching {
            Process::class.java.getMethod("pid").invoke(process)
        }.getOrNull()?.let { raw -> toPid(raw).takeIf { it > 0 } }

        /** 路径二：`/proc` 兜底（真机唯一可靠的路径） */
        private fun pidViaProc(): Long = runCatching {
            findChildPidFromProc(excludePids = preExistingChildren)
        }.getOrDefault(-1L)

        /**
         * BL-027：pid 解析失败的诊断开关（每个 Handle 只打一次）。
         *
         * 为什么要"只一次"而不是"每次 -1 都打"：[pid] 不缓存 -1（见上），调用方（DshRuntime 的启动/
         * 停止/巡检）在 pid 拿不到期间会反复调它；每次都打会把一次启动的失败刷成几十上百行。
         * 直接子进程集合在两次失败之间也基本不变 —— 第一条诊断已足够定位，后面的全是噪音。
         *
         * 本次打点是拍"失败当时"的现场：tids 数、children 是否存在、可读几个、
         * 差集结果。长驻路径的 `prootPid<=0 → 跳过 verify`（见 DshRuntime.verifyProotGone）
         * 逻辑不变。
         */
        private val pidFailureLogged = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * 在 pid 解析失败时打一条英文诊断行（R-LOG），包在 runCatching 里：诊断本身不许抛异常，
         * 否则"为失败加的诊断"反而把调用方（启动/停止路径）带崩。
         */
        private fun logPidFailureOnce() {
            if (!pidFailureLogged.compareAndSet(false, true)) return
            runCatching { DshLogBus.append("[proot] " + describePidFailure()) }
        }

        /**
         * 拼出 pid 解析失败的现场：tids 数、`children` 文件是否存在、可读几个、读到什么、
         * 当前子进程集合与启动前快照的差集结果 —— 正好对应"三个死因"（见 BL-027）。
         *
         * ★ 只读诊断，不参与解析：真正的解析仍是 [pidViaReflection]/[pidViaProc] 那两条路，
         * 这里只是把"失败当时"的 /proc 现场拍下来。输出英文（R-LOG），写进全局流。
         *
         * 输出形状示例（成功时根本不会打）：
         * `pid resolution failed (reflection + /proc both failed); tids=12,
         *  childrenFilesPresent=12, readable=12; rawChildren=[1234 1235]; currentChildren=2,
         *  preExisting=1, newAfterDiff=0`
         *
         * 怎么读它：
         * - `cannot list …/self/task` → task 目录本身扫不到（SELinux 挡或内核无该节点）；
         * - `childrenFilesPresent=0` → 内核无 CONFIG_PROC_CHILDREN（"Android 内核有"只是旧注释的假设）；
         * - `readable=0` → 文件在但读不到（权限）；
         * - `rawChildren=[]` → 读到了但全是空（刚 start 的 proot 还没成为我们的子进程，或已退出）；
         * - `newAfterDiff=0` → 差集为空（快照拍晚了把 proot 自己含进去，或它已经死了）。
         *
         * @param procRoot `/proc` 根。默认真机路径；做成参数只是为了将来单测可喂假目录。
         */
        private fun describePidFailure(procRoot: String = "/proc"): String {
            val selfTask = File(procRoot, "self/task")
            val entries = runCatching { selfTask.listFiles() }.getOrNull()
            if (entries == null) {
                val exists = runCatching { selfTask.exists() }.getOrDefault(false)
                return "pid resolution failed (reflection + /proc both failed); " +
                    "cannot list $procRoot/self/task (exists=$exists); " +
                    "preExistingSnapshotSize=${preExistingChildren.size}"
            }
            val tids = entries.mapNotNull { it.name.toLongOrNull() }
            var childrenFiles = 0
            var readable = 0
            // 各 tid 的 children 内容通常相同（LinkedHashSet 去重），只留前几个样本，行长可控。
            val raws = LinkedHashSet<String>()
            val children = LinkedHashSet<Long>()
            for (tid in tids) {
                val f = File(selfTask, "$tid/children")
                if (runCatching { f.exists() }.getOrDefault(false)) childrenFiles++
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                readable++
                val trimmed = text.trim()
                if (trimmed.isNotEmpty() && raws.size < 4) raws.add(trimmed.take(48))
                text.split(' ', '\n', '\t').mapNotNullTo(children) { it.trim().toLongOrNull() }
            }
            // 差集口径与 [findChildPidFromProc] 一致（>0 且不在启动前快照里），只计数、不返回 pid。
            val fresh = children.count { it > 0 && it !in preExistingChildren }
            return "pid resolution failed (reflection + /proc both failed); " +
                "tids=${tids.size}, childrenFilesPresent=$childrenFiles, readable=$readable; " +
                "rawChildren=[${raws.joinToString("|")}]; " +
                "currentChildren=${children.size}, " +
                "preExistingSnapshotSize=${preExistingChildren.size}, newAfterDiff=$fresh"
        }

        fun isAlive(): Boolean = runCatching { process.isAlive }.getOrDefault(false)

        /** 优雅停止：TERM → 等 5s → KILL */
        suspend fun terminate(graceMillis: Long = 5000) = withContext(Dispatchers.IO) {
            if (!isAlive()) return@withContext
            runCatching { process.destroy() }
            val exited = runCatching { process.waitFor(graceMillis, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            if (!exited && isAlive()) {
                runCatching { process.destroyForcibly() }
            }
        }
    }

    /**
     * 启动并开始读取输出（读取在 IO 线程）。
     * @param onLine 每行输出
     * @param onExit 进程退出时回调（退出码；-1 表示异常）
     */
    fun start(
        spec: ProotCommand.Spec,
        scope: CoroutineScope,
        onLine: (String) -> Unit,
        onExit: (Int) -> Unit
    ): Handle {
        // ★ 顺序很重要：**先拍子进程快照，再 start**。
        //   `/proc` 兜底靠"新出现的子进程 = 我们刚启动的 proot"来认人；快照在 start 之后拍的话，
        //   proot 自己已经在集合里了，差集永远为空 → 又回到拿不到 pid 的老问题。
        val preExisting = snapshotChildPids()
        val process = spec.toProcessBuilder().start()
        val handle = Handle(process, preExisting)
        // 立刻解析一次 pid 并缓存：此刻进程一定还活着、/proc 目录一定在。
        // 拖到调用方（DshRuntime 写 dsh.pid）再解析也行，但那时进程可能已经退出，白扫一遍。
        runCatching { handle.pid() }
        scope.launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        onLine(line!!)
                    }
                }
            } catch (e: Exception) {
                onLine("[proot] 读取输出失败: ${e.message}")
            }
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            onExit(code)
        }
        return handle
    }

    /** 在 rootfs 内跑一条短命令并返回输出行（自检用） */
    suspend fun runOnce(
        context: Context,
        script: String,
        argvEnv: Map<String, String> = emptyMap(),
        secretEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000
    ): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val code = ProotProcessExecutor(context).run(
            script = script,
            argvEnv = argvEnv,
            secretEnv = secretEnv,
            timeoutMs = timeoutMs
        ) { lines += it }
        code to lines.toList()
    }
}
