package com.dsh

import android.content.Context
import android.content.ContextWrapper
import com.dsh.core.DshInstance
import com.dsh.core.DshInstaller
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.ProcessUtil
import com.dsh.core.ProotExecutor
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * R39 / SP-02：`dsh-install.pid` 的**写侧**原子化（`DshInstaller.writeInstallPidFile`）。
 *
 * ## 缺陷（round34 状态持久化审计 §SP-02）
 *
 * `writeInstallPidFile` 原来用 `f.writeText(...)`，即"开 → 截断到 0 字节 → 写 → 关"。
 * 中间那一瞬路径**存在但内容为空/半截**，而两个读者都不认这个状态：
 *
 * | 读者 | 现场 | 读到的结果 | 后果 |
 * |---|---|---|---|
 * | `DshInstances.installProotStillAlive`（删除前的等待判据）| 0 字节/半截 JSON | `false` = 没在装 | 删除事务提前放行，与 npm 抢盘 |
 * | `DshInstaller.readInstallPidFile` → `cleanupStaleInstallProcess`（孤儿认领）| 0 字节 | `null` | 上次安装的孤儿 proot 认领不到 |
 *
 * ## 本轮怎么修（**只修写侧**，读侧一字未改 —— 这是主 Agent 的裁决，不是漏改）
 *
 * 写侧改成 `ProcessUtil.atomicWriteText`（tmp + `fd.sync` + `rename`），于是目标路径上只可能
 * 出现"旧的完整内容"或"新的完整内容" —— `Unreadable` 这个现场**由本 App 写出时物理上不可达**。
 * 读侧因此不需要在"读不出该当 `true` 还是 `false`"上选边站（理由与完整推演见
 * `DshInstances.installProotStillAlive` 的 KDoc）。
 *
 * ## 这组测试能测什么 / 不能测什么（先说实话）
 *
 * **能测（真跑，不是扫源码）**：
 * 1. 单次写的**行为契约**：内容逐字节等于 GSON 序列化结果、目录里不留 tmp。
 * 2. 并发写 + 并发读：读者**绝不**读到 0 字节/半截/拼接内容（这是本缺陷的直接现场）。
 * 3. 生产读法（`readInstallPidFile`）在并发下**不会返回错的 pid**（撕裂内容解析不成合法 pid）。
 * 4. 失败路径：写不进去时**留痕**（`[install] failed to write dsh-install.pid: ...`），不静默；
 *    且**不留下**半截目标（`atomicWriteText` 绝不回退成原地写）。
 *
 * **不能测**：真机上"删除循环那一 tick 恰好撞上那一次写"的**真实交错概率**。
 * 本文件把写压成"4 线程 × 120 次"是为了把窗口放大到可观测 —— 生产上这个文件**每次安装只写一次**
 * （`onPidResolved` 里被 `installPid.compareAndSet(0L, pid)` 挡住重放），31 字节，
 * 而删除循环每 100ms 才采一次样，命中量级约 1e-7。**所以这是低频缺陷，不是常态。**
 *
 * 运行：`sh run-tests.sh`（自动扫描本类），或
 * `./gradlew :FCL:testDebugUnitTest --tests "com.dsh.R39InstallPidAtomicTest"`。
 */
class R39InstallPidAtomicTest {

    private val tmpRoot: File = Files.createTempDirectory("r39-install-pid").toFile()
    private val savedPaths = HashMap<String, Any?>()

    @After
    fun restorePaths() {
        savedPaths.forEach { (name, value) ->
            runCatching { field(name).set(DshPaths, value) }
        }
    }

    // --- 1) 单次写：内容完整、不留 tmp ------------------------------------------

    /**
     * 写完的文件必须能被生产读法解析回**同一个 pid**，且目录里不残留 tmp。
     *
     * 这条钉的是"换了写实现但格式没变"：`PidMeta(pid, 0)` 的 JSON 形状（安装期没有端口概念，
     * port 恒 0）是跨进程认领的唯一契约，格式漂了就等于孤儿认领静默失效。
     */
    @Test
    fun writeInstallPidFileLeavesTheFullPidMetaAndNoTemporaryFile() {
        val inst = newInstance("r39-single")
        val installer = newInstaller()
        val dir = instanceDir(inst.id)
        assertTrue("前置：实例目录必须建出来", dir.isDirectory)

        writeInstallPidFile(installer, inst, 4242L)

        val f = File(dir, DshInstaller.INSTALL_PID_FILE_NAME)
        assertTrue("写完之后文件必须存在", f.isFile)

        val text = f.readText()
        assertFalse("★ 内容不许是 0 字节", text.isEmpty())
        val obj = JsonParser.parseString(text).asJsonObject
        assertEquals("pid 必须是刚写进去的那个", 4242L, obj.get("pid").asLong)
        assertEquals("安装期 port 恒 0", 0, obj.get("port").asInt)
        assertEquals("安装 pid 不写 url/token（与 dsh.pid 同一隐私口径）", 2, obj.size())

        // 生产读法必须读得回来（同一份 31 字节的契约）
        assertEquals("生产读法必须解析回同一个 pid", 4242L, readInstallPidFile(installer, inst.id))

        val leftovers = dir.listFiles()?.map { it.name }?.filter { it != DshInstaller.INSTALL_PID_FILE_NAME }
            ?: emptyList()
        assertEquals("★ 目录里只许有目标文件（tmp 必须被 rename 走或删掉）：$leftovers", 0, leftovers.size)
    }

    /**
     * 覆盖写：目标**已存在**时必须成功替换（这是 `writeText` 的旧行为，不能退步）。
     *
     * 单独一条的理由：Windows 上 `File.renameTo` 对"目标已存在"直接返回 false，
     * 只靠它的实现会"单测红、真机绿"，是最容易漏的跨平台坑（R37 已踩过一次）。
     */
    @Test
    fun writeInstallPidFileReplacesAnExistingFile() {
        val inst = newInstance("r39-replace")
        val installer = newInstaller()
        val dir = instanceDir(inst.id)
        val f = File(dir, DshInstaller.INSTALL_PID_FILE_NAME)
        f.writeText("""{"pid":1,"port":0}""")

        writeInstallPidFile(installer, inst, 7777L)

        assertEquals("已存在的目标必须被替换", 7777L, readInstallPidFile(installer, inst.id))
        assertEquals("替换后目录里仍只许有目标文件", 1, dir.listFiles()!!.size)
    }

    // --- 2) 并发写 + 并发读：不许出现 0 字节/半截 --------------------------------

    /**
     * ★ 本文件的**核心**用例（也是变异验证的靶子）。
     *
     * 4 个写者各 120 次 + 1 个读者不间断读，读者**一次都不许**读到 0 字节/半截/拼接内容。
     * `f.writeText` 在同样负载下会让读者命中"存在但空"（R37 的探针实测：4×120 次写里命中 82~88 次）；
     * 换成原子写后这条断言才是 0。
     *
     * ★ **R37 那次偶发失败的教训，这里逐条吸收**：
     * 1. 读者的"永久读不出"判据只在**写者全部停止之后**才成立（`writersDone`），
     *    否则读循环自身不 sleep、每秒上万次读，连续撞写窗口就会被计成"永久读不出"。
     * 2. 写者停下后**额外给 50ms 稳定窗口**再判，避免把收尾瞬间的共享冲突算成缺陷。
     * 3. Windows 上 `MoveFileEx` 替换目标名与读者 `open` 会撞 `ERROR_SHARING_VIOLATION`
     *    （JDK 表现为 `FileNotFoundException`）—— 那是**宿主 open 语义**，不是内容撕裂，
     *    所以单独统计 `transientOpenFailures` 且不做断言；真正断言的是 `badContent == 0`。
     */
    @Test
    fun concurrentWritesNeverExposeEmptyOrTornContent() {
        val inst = newInstance("r39-concurrent")
        val installer = newInstaller()
        val dir = instanceDir(inst.id)
        val target = File(dir, DshInstaller.INSTALL_PID_FILE_NAME)
        // 预写一份完整内容：读者看到的"空"就一定是**写窗口**，不是"第一次写之前本来没有"
        writeInstallPidFile(installer, inst, 1000L)

        val writers = 4
        val perWriter = 120
        val badContent = AtomicInteger(0)
        val transientOpenFailures = AtomicInteger(0)
        val permanentlyUnreadable = AtomicInteger(0)
        val reads = AtomicInteger(0)
        val writeFailures = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val writersDone = AtomicBoolean(false)
        val startGate = CountDownLatch(1)
        val samples = java.util.Collections.synchronizedList(mutableListOf<String>())

        val reader = Thread {
            startGate.await()
            while (!stop.get()) {
                val text = readWithRetry(target) { transientOpenFailures.incrementAndGet() }
                if (text == null) {
                    // 判据必须是"写者都停了之后还读不出" —— 见类头 R37 教训第 1 条
                    if (writersDone.get()) {
                        permanentlyUnreadable.incrementAndGet()
                    } else {
                        Thread.sleep(2)
                    }
                    continue
                }
                reads.incrementAndGet()
                // 完整形态：GSON 的 PidMeta JSON，首尾必须配对（半截内容过不了这两关）
                if (text.isEmpty() || !text.trimStart().startsWith("{") || !text.trimEnd().endsWith("}")) {
                    badContent.incrementAndGet()
                    if (samples.size < 5) samples.add("[$text] len=${text.length}")
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val threads = (0 until writers).map { w ->
            Thread {
                startGate.await()
                for (i in 0 until perWriter) {
                    val pid = 1000L * (w + 1) + i
                    // 并发下原子替换在**单测宿主（Windows）**上会因瞬时共享冲突重试甚至失败；
                    // 失败不是本测试要断言的（生产代码会留痕），只统计。
                    if (!runCatching { writeInstallPidFile(installer, inst, pid) }.isSuccess) {
                        writeFailures.incrementAndGet()
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        startGate.countDown()
        threads.forEach { it.join(60_000) }
        writersDone.set(true)
        Thread.sleep(50)
        stop.set(true)
        reader.join(5_000)

        assertTrue("前置：读者必须真的读到了东西（否则这条断言毫无意义）", reads.get() > 0)
        assertEquals(
            "★ 并发写下读者一次都不许读到 0 字节/半截/拼接内容：$samples",
            0, badContent.get()
        )
        assertEquals(
            "★ 读失败必须都是**可重试恢复**的瞬时失败，不许永久读不出",
            0, permanentlyUnreadable.get()
        )
        // 收尾：最后一个写者的内容必须完整可解析（不是半截），且目录里没有 tmp 残留
        val finalText = target.readText()
        assertTrue("最终内容必须完整", finalText.trimStart().startsWith("{") && finalText.trimEnd().endsWith("}"))
        assertNotNull(
            "最终内容必须能被生产读法解析（写失败 ${writeFailures.get()} 次）",
            readInstallPidFile(installer, inst.id)
        )
        val leftovers = dir.listFiles()!!.map { it.name }.filter { it != DshInstaller.INSTALL_PID_FILE_NAME }
        assertEquals("并发写完之后不许残留 tmp 文件：$leftovers（写失败 ${writeFailures.get()} 次）", 0, leftovers.size)
    }

    /**
     * 并发下**生产读法**不许返回"错的 pid"：它要么读到某一次写的完整内容，要么读不出（null）。
     *
     * 为什么单独一条：撕裂内容（半截 JSON）在两个读者身上后果不同 ——
     * 这里走的是 `readInstallPidFile` 的容错分支（`runCatching{}.getOrNull()`），
     * 半截 JSON 会抛、被吞成 null，**不会**变成一个错误的 pid 去杀别人。
     * 这条钉住"撕裂不会变成误杀/误认领"这个方向性保证。
     */
    @Test
    fun productionReaderNeverReturnsAValueThatWasNeverWritten() {
        val inst = newInstance("r39-reader")
        val installer = newInstaller()
        instanceDir(inst.id)
        writeInstallPidFile(installer, inst, 1L)

        val written = java.util.Collections.synchronizedSet(HashSet<Long>())
        written.add(1L)
        val wrongValue = AtomicInteger(0)
        val okReads = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val gate = CountDownLatch(1)

        val reader = Thread {
            gate.await()
            while (!stop.get()) {
                val pid = readInstallPidFile(installer, inst.id)
                if (pid == null) {
                    // null 是合法的容错出口（瞬时打不开/正在被替换）；不是本用例的断言对象
                    Thread.sleep(1)
                } else {
                    okReads.incrementAndGet()
                    if (pid !in written) wrongValue.incrementAndGet()
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val writers = (0 until 3).map { w ->
            Thread {
                gate.await()
                for (i in 0 until 100) {
                    val pid = 5000L + 100 * w + i
                    written.add(pid)
                    runCatching { writeInstallPidFile(installer, inst, pid) }
                }
            }.apply { isDaemon = true; start() }
        }
        gate.countDown()
        writers.forEach { it.join(60_000) }
        stop.set(true)
        reader.join(5_000)

        assertTrue("前置：读者必须真的读到过 pid", okReads.get() > 0)
        assertEquals(
            "★ 生产读法不许返回从没被写过的 pid（撕裂内容只会变成 null，不会变成别人的 pid）",
            0, wrongValue.get()
        )
    }

    // --- 3) 失败路径：留痕，且不留下半截目标 --------------------------------------

    /**
     * ★ 写不进去时必须**留痕**（BL-046 口径：这条文件是"孤儿认领/清理"的唯一凭证，
     * 静默失败 = 该实例的后台残留从此无人能认领）。
     *
     * 现场：把 `dsh-install.pid` 这个**路径**做成一个非空目录 —— `rename` 与 `writeText` 都必然失败。
     * 同时钉住"绝不回退成原地写"：若实现里加了 `f.writeText` 兜底，目标目录里会多出内容。
     */
    @Test
    fun failedWriteIsLoggedAndLeavesNoPartialTarget() {
        val inst = newInstance("r39-fail")
        val installer = newInstaller()
        val dir = instanceDir(inst.id)
        val target = File(dir, DshInstaller.INSTALL_PID_FILE_NAME).apply { mkdirs() }
        File(target, "blocker").writeText("x")

        // 清掉历史行，让断言只看这一次写
        DshLogBus.flushNow()
        val before = instanceLogLines(inst.id).size

        // 不抛给调用方（控制流与 BL-046 一致：失败照旧吞掉）
        writeInstallPidFile(installer, inst, 9001L)

        val lines = instanceLogLines(inst.id).drop(before)
        val failureLine = lines.firstOrNull { it.contains("failed to write ${DshInstaller.INSTALL_PID_FILE_NAME}") }
        assertNotNull(
            "★ 写失败必须留痕（不许静默）：实际新增 ${lines.size} 行，$lines",
            failureLine
        )
        assertTrue(
            "留痕里必须带真实原因（不是空消息）：$failureLine",
            failureLine!!.substringAfter("dsh-install.pid", "").isNotBlank()
        )
        assertTrue("目标仍然是个目录（没有被原地写的内容污染）", target.isDirectory)
        assertEquals("目标目录里仍然只有那一个 blocker", 1, target.listFiles()!!.size)
        val leftovers = dir.listFiles()!!.map { it.name }.filter { it.startsWith("${DshInstaller.INSTALL_PID_FILE_NAME}.tmp.") }
        assertEquals("失败后不许残留 tmp：$leftovers", 0, leftovers.size)
    }

    /**
     * 成功路径的日志仍在（改实现不能顺手把原有的 `written to` 那行弄丢）。
     */
    @Test
    fun successfulWriteStillLogsThePid() {
        val inst = newInstance("r39-logok")
        val installer = newInstaller()
        instanceDir(inst.id)
        DshLogBus.flushNow()
        val before = instanceLogLines(inst.id).size

        writeInstallPidFile(installer, inst, 31337L)

        val lines = instanceLogLines(inst.id).drop(before)
        assertTrue(
            "成功路径必须仍留下 'installer pid=... written to ...' 那行：$lines",
            lines.any { it.contains("installer pid=31337 written to ${DshInstaller.INSTALL_PID_FILE_NAME}") }
        )
        assertTrue(
            "成功路径不许出现失败行：$lines",
            lines.none { it.contains("failed to write") }
        )
    }

    // --- 4) 结构钉子：写侧必须走 ProcessUtil.atomicWriteText ----------------------

    /**
     * 变异靶子的**确定性**版本（并发用例是概率靶子，这条是结构靶子）。
     *
     * 断言 `DshInstaller.writeInstallPidFile` 的函数体里出现 `ProcessUtil.atomicWriteText(`，
     * 且**不出现** `writeText(`/`writeBytes(`（即没有任何"原地写"退路）。
     *
     * 为什么值得一条源码形状测试：生产上这个文件每次安装只写一次，并发窗口 1e-7 ——
     * 行为测试只能靠放大负载撞它；而"哪天有人为了图快改回 writeText"是**必然**会发生的回归，
     * 这条让它一改就红，不必等到真机上删目录撞 npm。
     */
    @Test
    fun writeSideGoesThroughAtomicWriteText() {
        val src = mainSourceFile().readText()
        val body = sliceFun(src, "private fun writeInstallPidFile(")

        assertTrue(
            "★ writeInstallPidFile 必须调用 ProcessUtil.atomicWriteText（SP-02 的写侧闭环）",
            body.contains("ProcessUtil.atomicWriteText(")
        )
        assertFalse(
            "★ 不许有任何原地写（非原子的 writeText/writeBytes 会重新引入 0 字节瞬时态）",
            Regex("""\bwriteText\(|\bwriteBytes\(|\.write\(|\bwriteString\(""").containsMatchIn(body)
        )
    }

    // --- 辅助 ---------------------------------------------------------------------

    private fun newInstance(id: String) = DshInstance(id = id, name = "R39 $id")

    /**
     * 把 `DshPaths.INSTANCES_DIR`（+ `isLoaded`）指到本次用例的临时目录。
     * 手法与 R37AtomicPidFileTest / DshInstancesPersistenceTest 一致：`lateinit var ... private set`
     * + JVM 单测没有 Android Context，只能反射。复原在 [restorePaths] 里无条件做。
     */
    private fun <T> withInstancesRoot(block: () -> T): T {
        val instances = File(tmpRoot, "instances-${nextSuffix()}")
        instances.mkdirs()
        set("INSTANCES_DIR", instances.absolutePath)
        set("isLoaded", true)
        return block()
    }

    /**
     * 建出**实例目录**并返回它。
     *
     * 为什么必须显式建：生产上实例目录由 `DshInstances.create` 建好，`writeInstallPidFile` 只
     * `parentFile?.mkdirs()` 兜底；而本文件里有几条断言要**直接**读写该目录里的文件
     * （预写"OLD"、检查 tmp 残留），目录不存在就会得到 `FileNotFoundException`（首次跑实测踩到）。
     */
    private fun instanceDir(instanceId: String): File = withInstancesRoot {
        DshPaths.instanceDir(instanceId).apply { mkdirs() }
    }

    private fun field(name: String) =
        DshPaths::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun set(name: String, value: Any?) {
        val f = field(name)
        if (name !in savedPaths) savedPaths[name] = runCatching { f.get(DshPaths) }.getOrNull()
        f.set(DshPaths, value)
    }

    /**
     * 反射调 `private fun writeInstallPidFile(instance: DshInstance, pid: Long)`。
     *
     * 为什么必须反射：它是 `private`（全仓唯一调用点是 `onPidResolved` 回调，需要真 proot +
     * 真 Context 才能触发），而本用例要测的恰恰是**这个写动作本身**。退到"只测
     * `ProcessUtil.atomicWriteText`"是不行的 —— 那就测不到"生产入口有没有绕开原子写"，
     * 而 SP-02 的缺陷正是**调用点**用了非原子写。
     */
    private fun writeInstallPidFile(installer: DshInstaller, inst: DshInstance, pid: Long) {
        val m: Method = DshInstaller::class.java.getDeclaredMethod(
            "writeInstallPidFile", DshInstance::class.java, java.lang.Long.TYPE
        )
        m.isAccessible = true
        m.invoke(installer, inst, pid)
    }

    /** 反射调 `private fun readInstallPidFile(instanceId: String): Long?`（生产读法，非测试自己造一个） */
    private fun readInstallPidFile(installer: DshInstaller, instanceId: String): Long? {
        val m: Method = DshInstaller::class.java.getDeclaredMethod(
            "readInstallPidFile", String::class.java
        )
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(installer, instanceId) as Long?
    }

    /** 取某个实例日志流里当前的内存行（[DshLogBus.flushNow] 同步推进快照，不 sleep） */
    private fun instanceLogLines(instanceId: String): List<String> {
        DshLogBus.flushNow()
        return DshLogBus.snapshotFor(instanceId).value.lines
    }

    /**
     * 造一个**不碰任何方法**的 Context（`DshInstaller` 的构造只存字段，写 pid 文件也不需要它）。
     * 与 R34InstallErrorTest 同一手法：JVM 单测没有 Android 运行时，`Unsafe.allocateInstance`
     * 能拿到一个未初始化的 `ContextWrapper` 实例而不触发其构造/静态初始化。
     */
    private fun newInstaller(): DshInstaller = DshInstaller(fakeContext(), NoopProot)

    private fun fakeContext(): Context {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val allocate: Method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ContextWrapper::class.java) as Context
    }

    /** `writeInstallPidFile` 不会碰 proot；真被调到就是用例写错了，直接炸出来 */
    private object NoopProot : ProotExecutor {
        override fun run(script: String, env: Map<String, String>, onLine: (String) -> Unit): Int =
            throw AssertionError("R39InstallPidAtomicTest 不跑 proot（writeInstallPidFile 不依赖它）")
    }

    /** 从源码里切出一个函数的块：从声明到配对的 `}`（与 R37 的同名工具同口径） */
    private fun sliceFun(src: String, header: String): String {
        val at = src.indexOf(header)
        if (at < 0) throw AssertionError("找不到 $header")
        val open = src.indexOf('{', at)
        return src.substring(at, endOfBlock(src, open) + 1)
    }

    /** 从左花括号 [open] 起按深度找配对的右花括号下标（跳过字符串/字符字面量里的花括号） */
    private fun endOfBlock(text: String, open: Int): Int {
        if (open < 0 || text.getOrNull(open) != '{') throw AssertionError("不是左花括号：$open")
        var depth = 0
        var i = open
        var inString = false
        var inChar = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> when {
                    c == '\\' -> i++
                    c == '"' -> inString = false
                }
                inChar -> when {
                    c == '\\' -> i++
                    c == '\'' -> inChar = false
                }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        throw AssertionError("花括号不配对（从 $open 起）")
    }

    /**
     * 定位 `DshInstaller.kt`（与 R37 的 `mainSourceFile()` 同口径：从 cwd 往上找几层，
     * 电脑布局 `/d/Projects/<repo>` 与仓库根的两种跑法都要能命中）。
     */
    private fun mainSourceFile(): File {
        val rel = "core/DshInstaller.kt"
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

    /**
     * 读一个文件，对"瞬时打不开"做有限重试（[onTransientFailure] 计数）。
     *
     * Windows 上原子替换与并发 `open` 会撞 `ERROR_SHARING_VIOLATION`（JDK 表现 `FileNotFoundException`），
     * 那不是内容撕裂 —— 重试一次就能读到完整内容。返回 null 表示重试也没读到。
     */
    private fun readWithRetry(target: File, onTransientFailure: () -> Unit): String? {
        repeat(8) {
            val r = runCatching { target.readText() }
            if (r.isSuccess) return r.getOrNull()
            onTransientFailure()
            Thread.sleep(1)
        }
        return null
    }

    private companion object {
        val SEQ = AtomicLong(0)

        fun nextSuffix(): String = "${System.nanoTime()}-${SEQ.incrementAndGet()}"
    }
}
