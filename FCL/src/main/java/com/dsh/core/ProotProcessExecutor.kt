package com.dsh.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
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
        return try {
            val process = spec.toProcessBuilder().start()
            synchronized(this) { actives += Active(tag, process) }
            // 跨线程可见：看门狗线程写、当前线程读，必须用 Atomic/volatile，否则可能读到旧值把超时误判为正常退出。
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = if (timeoutMs > 0) {
                Thread {
                    try {
                        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                            timedOut.set(true)
                            onLine("[proot] 超时 ${timeoutMs / 1000}s，终止任务")
                            process.destroy()
                            Thread.sleep(3000)
                            if (process.isAlive) process.destroyForcibly()
                        }
                    } catch (_: InterruptedException) {
                    }
                }.apply { isDaemon = true; name = "dsh-proot-watchdog"; start() }
            } else null

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    onLine(line!!)
                }
            }
            val code = process.waitFor()
            // 让看门狗线程结束（进程已退出时它自然返回）
            watchdog?.interrupt()
            synchronized(this) { actives.removeAll { it.process === process } }
            if (timedOut.get()) TIMEOUT_EXIT_CODE else code
        } catch (e: Exception) {
            onLine("[proot] 执行失败: ${e.message}")
            -1
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

    /** 启动后的句柄 */
    class Handle(val process: Process) {
        /**
         * 进程 pid。
         * 注意：**Android Runtime 的 java.lang.Process 没有 pid()**（那是 Java 9+ 的 API），
         * 所以这里用反射，拿不到就返回 -1（此时孤儿清理会退化为按实例 pid 文件缺失处理）。
         */
        fun pid(): Long = runCatching {
            val m = Process::class.java.getMethod("pid")
            m.invoke(process) as Long
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
        val process = spec.toProcessBuilder().start()
        val handle = Handle(process)
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
