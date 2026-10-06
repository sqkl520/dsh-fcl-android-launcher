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
            val p = spec.toProcessBuilder().start()
            process = p
            synchronized(this) { actives += Active(tag, p) }
            // 跨线程可见：看门狗线程写、当前线程读，必须用 Atomic/volatile，否则可能读到旧值把超时误判为正常退出。
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = if (timeoutMs > 0) {
                Thread {
                    try {
                        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                            timedOut.set(true)
                            onLine("[proot] 超时 ${timeoutMs / 1000}s，终止任务")
                            p.destroy()
                            Thread.sleep(3000)
                            if (p.isAlive) p.destroyForcibly()
                        }
                    } catch (_: InterruptedException) {
                    }
                }.apply { isDaemon = true; name = "dsh-proot-watchdog"; start() }
            } else null

            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    onLine(line!!)
                }
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
            if (p != null && !runCatching { p.isAlive }.getOrDefault(false)) {
                synchronized(this) { actives.removeAll { it.process === p } }
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
                val mine = tag == null || a.tag == tag
                if (mine) runCatching { a.process.destroy() }
            }
        }
    }

    /** 当前活跃子进程（含归属标记） */
    private class Active(val tag: String?, val process: Process)

    /** 所有在跑的子进程（CopyOnWriteArrayList：读多写少，且要能在并发下安全遍历） */
    private val actives = java.util.concurrent.CopyOnWriteArrayList<Active>()
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
         */
        @Volatile
        private var cachedPid: Long = 0

        fun pid(): Long {
            val c = cachedPid
            if (c > 0) return c
            val resolved = pidViaReflection() ?: pidViaProc()
            if (resolved > 0) cachedPid = resolved
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
