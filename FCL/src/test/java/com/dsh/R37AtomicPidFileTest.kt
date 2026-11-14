package com.dsh

import com.dsh.core.DshPaths
import com.dsh.core.PidFileRead
import com.dsh.core.ProcessUtil
import com.dsh.core.pidFileAllowsAdoptionWriteBack
import com.dsh.core.pidFileBelongsTo
import com.dsh.core.pidFileMayBeDeleted
import com.dsh.core.storedPidOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * R37 / SP-01：pid 文件「非原子写 + 删除判据把读不出当可删」的修复钉子。
 *
 * ## 缺陷（round34 状态持久化审计）
 *
 * `File.writeText` 是「开 → 截断到 0 字节 → 写 → 关」，中间有"路径存在但内容为空/半截"的瞬时态，
 * 此时 `isFile` 仍为 true。旧读法把这种状态与"文件不存在"一起折成 `null`，旧判据
 * `pidFileBelongsTo(null, expected) = true` 又把 `null` 解释成"不存在 ⇒ 删一下无妨"：
 *
 * | 磁盘真实状态 | 旧 readPidFile | 旧 pidFileBelongsTo | 旧删除动作 |
 * |---|---|---|---|
 * | 文件不存在 | null | true | 删（no-op，无害）|
 * | 文件是我的 | P_old | true | 删（设计意图）|
 * | 文件是新进程的 | P_new | false | 不删（设计意图）|
 * | **文件正在被写** | **null** | **true** | **删掉的是新进程那份** |
 *
 * ## 这组测试能测什么 / 不能测什么（先说实话）
 *
 * **能测（真跑，不是扫源码）**：
 * 1. [pidFileMayBeDeleted] / [pidFileAllowsAdoptionWriteBack] 的**三态语义** —— 纯函数，穷举。
 * 2. [ProcessUtil.atomicWriteText] 的**行为契约**：写完内容完整、tmp 不残留、并发写不产生
 *    0 字节/拼接内容、失败不回退成原地写。
 * 3. [ProcessUtil.readPidFileState] 对**真实文件**的三态判定（0 字节 / 半截 JSON / 无此文件 / 正常）。
 * 4. [ProcessUtil.removePidFile] 的**真实删除行为**（`DshPaths.INSTANCES_DIR` 用反射指到临时目录，
 *    与 DshInstancesPersistenceTest / R31Fix227Test 同一手法）—— 特别是"读不出时不删"。
 *
 * **不能测**：真机上「T1 写 / T2 删」的**交错本身**。要精确复现"T2 恰好在 T1 截断后的那一微秒里
 * 读"，需要在进程内插桩到 `writeText` 内部；这里退一步用两个可判定的事实把它夹住：
 * (a) 原子写**不可能**让读者看到 0 字节/半截（探针实测 20000 次写里读者 0 次命中，见报告）；
 * (b) 即使读者真的拿到了 `Unreadable`，删除判据也**不会**删（纯函数 + 真实文件两处都钉）。
 * 两条合起来 ⇒ 旧缺陷的两个必要条件都不再成立。
 */
class R37AtomicPidFileTest {

    private val tmpRoot: File = Files.createTempDirectory("r37-pid").toFile()
    private val savedPaths = HashMap<String, Any?>()

    @After
    fun restorePaths() {
        savedPaths.forEach { (name, value) ->
            runCatching { field(name).set(DshPaths, value) }
        }
    }

    // --- 1) 三态删除判据：这是修复的**核心**（纯函数，穷举） -------------------------

    /**
     * [pidFileMayBeDeleted] 的四格真值表 —— 与缺陷表逐格对应。
     *
     * 最关键的是第 4 格：旧判据在这一格返回 true（删掉新进程那份），新判据必须返回 false。
     */
    @Test
    fun deleteGateDistinguishesMissingFromUnreadable() {
        // ① 文件不存在 → 允许删（delete 是 no-op，无害）
        assertTrue(
            "Missing → 允许删（no-op）",
            pidFileMayBeDeleted(PidFileRead.Missing, expectedPid = 123)
        )
        // ② 文件是我的 → 允许删（设计意图）
        assertTrue(
            "Present(我的 pid) → 允许删",
            pidFileMayBeDeleted(PidFileRead.Present(123L), expectedPid = 123)
        )
        // ③ 文件是新进程的 → 拒绝（K03 的原始意图）
        assertFalse(
            "Present(新进程 pid) → 拒绝删",
            pidFileMayBeDeleted(PidFileRead.Present(456L), expectedPid = 123)
        )
        // ④ ★ 本修复的核心：读不出 ≠ 不存在。旧判据在这里返回 true，删掉的是新进程那份。
        assertFalse(
            "★ Unreadable → 拒绝删（旧实现删的就是这一格）",
            pidFileMayBeDeleted(PidFileRead.Unreadable, expectedPid = 123)
        )
    }

    /**
     * `expectedPid = 0`（拿不到自己的 pid）时不得误删任何真实 pid —— 与旧口径一致。
     */
    @Test
    fun zeroExpectedPidNeverDeletesARealPid() {
        assertFalse("expectedPid=0 不得删真实 pid", pidFileMayBeDeleted(PidFileRead.Present(456L), 0))
        assertFalse("expectedPid=0 且读不出 → 也不删", pidFileMayBeDeleted(PidFileRead.Unreadable, 0))
        // 文件里真的是 0（占位）：与 expectedPid 相等 → 可删（本代码库不写 0，但语义要自洽）
        assertTrue("文件里是 0、期望也是 0 → 可删", pidFileMayBeDeleted(PidFileRead.Present(0L), 0))
    }

    /**
     * 旧两态判据的**行为不变性**（不是我要修的，但不能被我改坏）：
     * `pidFileBelongsTo` 仍保留给安装 pid 文件用，语义与改动前完全一致。
     *
     * 把它与新判据并排断言，是为了让"两个函数在 `null` 上分道扬镳"这件事在测试里可见 ——
     * 将来若有人把 `removePidFile` 改回 `pidFileBelongsTo`，第 4 格会立刻红。
     */
    @Test
    fun legacyTwoStateGateKeepsItsOldSemanticsButDiffersOnTheDangerousCell() {
        assertTrue("旧口径：null 当不存在", pidFileBelongsTo(storedPid = null, expectedPid = 123))
        assertTrue("旧口径：同一个 pid", pidFileBelongsTo(storedPid = 123, expectedPid = 123))
        assertFalse("旧口径：别的 pid", pidFileBelongsTo(storedPid = 456, expectedPid = 123))

        // 新判据对同样四种现场的判断：前三格与旧口径一致，第四格分道扬镳。
        assertEquals(
            "Missing 与旧口径的 null-当不存在 等价",
            pidFileBelongsTo(null, 123),
            pidFileMayBeDeleted(PidFileRead.Missing, 123)
        )
        assertNotEquals(
            "★ 旧口径的 null 还顺带放行了 Unreadable —— 新判据必须与它不同",
            pidFileBelongsTo(null, 123),
            pidFileMayBeDeleted(PidFileRead.Unreadable, 123)
        )
    }

    // --- 2) 认领回写的三态判据（同族缺陷的覆盖版） ---------------------------------

    /**
     * [pidFileAllowsAdoptionWriteBack] 的三态语义：**读不出时不许覆盖**。
     *
     * 理由与删除对称：读不出多半是新 run 正在写自己的 pid，这时把认领来的**死 pid** 覆盖上去
     * 等于毁掉新进程的孤儿保护（破坏方式是覆盖，不是删除）。
     */
    @Test
    fun adoptionWriteBackIsBlockedWhenTheFileIsUnreadable() {
        val adopted = 123L
        assertTrue("Missing → 允许写（没有文件，写出去不毁东西）", pidFileAllowsAdoptionWriteBack(PidFileRead.Missing, adopted))
        assertTrue("Present(认领时的 pid) → 允许幂等回写", pidFileAllowsAdoptionWriteBack(PidFileRead.Present(adopted), adopted))
        assertTrue("Present(0) → 占位，允许覆盖", pidFileAllowsAdoptionWriteBack(PidFileRead.Present(0L), adopted))
        assertFalse(
            "Present(新进程 pid) → 拒绝（BL-158 原意）",
            pidFileAllowsAdoptionWriteBack(PidFileRead.Present(999L), adopted)
        )
        assertFalse(
            "★ Unreadable → 拒绝写（旧两态版把 null 一并放行，等于允许把死 pid 覆盖到新文件上）",
            pidFileAllowsAdoptionWriteBack(PidFileRead.Unreadable, adopted)
        )
    }

    // --- 3) 真实文件的三态读取（不只是纯函数） -------------------------------------

    /**
     * [ProcessUtil.readPidFileState] 把一个真实文件的四种现场分成三态。
     *
     * 中间两行是**旧读法折成同一句 null 的两种截然不同的现场** —— 这个区分就是修复的地基。
     */
    @Test
    fun readStateSeparatesMissingFromUnreadableOnRealFiles() {
        val inst = "r37-read"
        val pidFile = withInstancesRoot { DshPaths.instancePidFile(inst) }

        // ① 文件不存在 → Missing
        if (pidFile.exists()) pidFile.delete()
        assertEquals("没有文件 → Missing", PidFileRead.Missing, ProcessUtil.readPidFileState(inst))

        // ② 0 字节（`writeText` 的"截断到 0"那一步）→ Unreadable，**不是** Missing
        pidFile.parentFile!!.mkdirs()
        pidFile.writeBytes(ByteArray(0))
        assertTrue("0 字节文件必须仍 isFile（前置：这正是旧代码看不见的现场）", pidFile.isFile)
        assertEquals("0 字节 → Unreadable", PidFileRead.Unreadable, ProcessUtil.readPidFileState(inst))

        // ③ 半截 JSON（写到一半被打断）→ Unreadable
        pidFile.writeText("""{"pid":1234,"port":4""")
        assertEquals("半截 JSON → Unreadable", PidFileRead.Unreadable, ProcessUtil.readPidFileState(inst))

        // ④ 只有空白 → Unreadable（掉电残留的另一种形态）
        pidFile.writeText("   \n\t  \n")
        assertEquals("只有空白 → Unreadable", PidFileRead.Unreadable, ProcessUtil.readPidFileState(inst))

        // ⑤ 正常内容 → Present，值可读
        pidFile.writeText("""{"pid":46501,"port":3080}""")
        val ok = ProcessUtil.readPidFileState(inst)
        assertTrue("完整 JSON → Present", ok is PidFileRead.Present)
        assertEquals("Present 里带着 pid", 46501L, (ok as PidFileRead.Present).value.pid)
    }

    /**
     * 旧两态视图 [ProcessUtil.readPidFile] 仍把 Unreadable 折成 null（保持既有调用点的语义），
     * 但它**不再**被删除判据使用 —— 这是"签名没变、行为分叉点收窄"的落地证明。
     */
    @Test
    fun legacyNullableReadStillCollapsesUnreadableIntoNull() {
        val inst = "r37-legacy"
        val pidFile = withInstancesRoot { DshPaths.instancePidFile(inst) }
        pidFile.parentFile!!.mkdirs()
        pidFile.writeBytes(ByteArray(0))
        assertNull("旧两态读法对 0 字节仍返回 null（调用点语义不变）", ProcessUtil.readPidFile(inst))
        // 但三态读法能区分出来 —— 两者对同一现场给不同答案，正是本次要的收窄
        assertEquals(PidFileRead.Unreadable, ProcessUtil.readPidFileState(inst))
    }

    // --- 4) 真实删除行为：「读不出不删」不是只写在纯函数里 -------------------------

    /**
     * ★ 本文件最重要的一条**行为**测试：`removePidFile` 在"文件读不出"时**真的不删**。
     *
     * 用真实的 `dsh.pid`（反射把 `DshPaths.INSTANCES_DIR` 指到临时目录）跑真实删除路径。
     * 旧实现会删掉它 —— 这正是场景 A 的后果。
     */
    @Test
    fun removePidFileKeepsAFileItCannotRead() {
        val inst = "r37-keep"
        val pidFile = withInstancesRoot { DshPaths.instancePidFile(inst) }
        pidFile.parentFile!!.mkdirs()

        // 现场：文件存在但 0 字节（新 run 正在写的那一瞬）
        pidFile.writeBytes(ByteArray(0))
        ProcessUtil.removePidFile(inst, expectedPid = 111L)

        assertTrue("★ 读不出的 pid 文件不许删（旧实现在这里删掉了新进程那份）", pidFile.exists())
        assertEquals("文件内容没被动过（仍是 0 字节）", 0L, pidFile.length())

        // 对照：同一调用在"文件里确实是我的 pid"时**要**删
        pidFile.writeText("""{"pid":111,"port":3080}""")
        ProcessUtil.removePidFile(inst, expectedPid = 111L)
        assertFalse("文件里是我的 pid → 按设计删掉", pidFile.exists())
    }

    /**
     * 同一判据在"文件是新进程的"时的行为：不删（K03 原意，回归保护）。
     */
    @Test
    fun removePidFileKeepsANewerProcessesFile() {
        val inst = "r37-newer"
        val pidFile = withInstancesRoot { DshPaths.instancePidFile(inst) }
        pidFile.parentFile!!.mkdirs()
        pidFile.writeText("""{"pid":222,"port":3080}""")

        ProcessUtil.removePidFile(inst, expectedPid = 111L)
        assertTrue("文件里是别的 pid（停后秒启的新进程）→ 不删", pidFile.exists())

        // 文件不存在时：删是 no-op，不该抛
        pidFile.delete()
        ProcessUtil.removePidFile(inst, expectedPid = 111L)
        assertFalse("文件不存在 → delete 是 no-op，不抛", pidFile.exists())
    }

    // --- 5) 原子写的行为契约 -------------------------------------------------------

    /**
     * 写完的内容必须**完整且逐字节等于**输入（不是"看起来像"），且**不留 tmp**。
     *
     * 这是能对原子写做的最直接断言：tmp+rename 若把内容搞坏（截断/拼接），这里立刻红。
     */
    @Test
    fun atomicWriteLeavesCompleteContentAndNoTemporaryFiles() {
        val dir = File(tmpRoot, "atomic1").apply { mkdirs() }
        val target = File(dir, "dsh.pid")
        val payload = """{"pid":123456,"port":46501,"url":"http://127.0.0.1:46501/?token=ABC_def-9"}"""

        assertTrue("atomicWriteText 成功（失败会抛）", ProcessUtil.atomicWriteText(target, payload))

        assertEquals("内容必须逐字节等于输入", payload, target.readText())
        val leftovers = dir.listFiles()?.map { it.name } ?: emptyList()
        assertEquals("目录里只许有目标文件（tmp 必须被 mv 走或删掉）", listOf("dsh.pid"), leftovers)
    }

    /**
     * 覆盖写：目标**已存在**时必须成功替换（这是 `writeText` 的旧行为，不能退步）。
     *
     * 单独一条的理由：`File.renameTo` 在 Windows 上"目标已存在"直接返回 false ——
     * 如果实现只靠 renameTo，这条会在单测宿主上红、在 Android 上绿，属于最容易漏的跨平台坑。
     */
    @Test
    fun atomicWriteReplacesAnExistingFile() {
        val dir = File(tmpRoot, "atomic2").apply { mkdirs() }
        val target = File(dir, "dsh.pid")
        target.writeText("OLD")

        assertTrue(ProcessUtil.atomicWriteText(target, "NEW"))
        assertEquals("已存在的目标必须被替换", "NEW", target.readText())
        assertEquals("替换后目录里仍只许有目标文件", 1, dir.listFiles()!!.size)
    }

    /**
     * **并发写**：多线程同时原子写同一目标 → 读者**读到的内容**必须是某一次写的完整内容，
     * 绝不能是 0 字节 / 半截 / 两次内容拼接。
     *
     * 这条是把缺陷现场夹住的另一半：非原子 `writeText` 在同样的机器上会让读者读到
     * "存在但空"（探针实测：4 写者 × 120 次，读者命中 82~88 次空内容）。这里要求**零次**。
     *
     * ★ **平台差异必须分开统计**（否则这条测试会在单测宿主上无意义地红）：
     * 在 Windows 上，`MoveFileEx` 替换目标名与读者 `open` 之间会撞 `ERROR_SHARING_VIOLATION`
     * （JDK 表现为 `FileNotFoundException: ... 另一个进程正在使用此文件`）—— 这是**宿主文件系统
     * 的 open 语义**，不是内容撕裂；Android 上 `rename(2)` 与并发 `open` 完全不冲突。
     * 所以分成两类断言：
     * - `badContent`（空/半截/拼接）必须 **0** —— 这是原子性的真正契约；
     * - `transientOpenFailure` 允许非 0，但**每一条都必须能重试成功并读到完整内容** ——
     *   这证明"文件本身任何时刻都是完整的"，只是瞬时打不开。
     */
    @Test
    fun concurrentAtomicWritesNeverExposeEmptyOrTornContent() {
        val dir = File(tmpRoot, "atomic3").apply { mkdirs() }
        val target = File(dir, "dsh.pid")
        ProcessUtil.atomicWriteText(target, """{"pid":0,"port":1}""")

        val writers = 4
        val perWriter = 120
        val badContent = AtomicInteger(0)
        val transientOpenFailures = AtomicInteger(0)
        val permanentlyUnreadable = AtomicInteger(0)
        val reads = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        // 写者是否全部结束 —— 读者的「永久读不出」判据只在它为 true 时才有意义
        val writersDone = AtomicBoolean(false)
        val startGate = CountDownLatch(1)
        val samples = java.util.Collections.synchronizedList(mutableListOf<String>())

        val reader = Thread {
            startGate.await()
            while (!stop.get()) {
                val text = readWithRetry(target) { transientOpenFailures.incrementAndGet() }
                if (text == null) {
                    // ★ R37 修测试自身的不稳定（不是产品缺陷）：
                    //   readWithRetry 的预算是 8 次 × 1ms sleep = 8ms。读者这个循环**自身不 sleep**，
                    //   在 4 写者 × 120 次的压力下每秒要读上万次 —— 连续撞上写窗口时，8ms 内
                    //   凑不出一次成功读，就被计成「永久读不出」。实测 12 次全量里偶发 1 次。
                    //
                    //   正确的判据是「**写者全都停下之后**还能不能读出来」：那时没有任何人写，
                    //   再读不出才是真的坏了。所以这里先退让一下再重试，只有**在写者已结束的情况下**
                    //   仍然读不出才计入 permanentlyUnreadable。
                    if (writersDone.get()) {
                        permanentlyUnreadable.incrementAndGet()
                    } else {
                        Thread.sleep(2)
                    }
                    continue
                }
                reads.incrementAndGet()
                // 完整形态：{"pid":<数字>,"port":<数字>}，首尾必须配对
                if (text.isEmpty() || !text.startsWith("{\"pid\":") || !text.endsWith("}")) {
                    badContent.incrementAndGet()
                    if (samples.size < 5) samples.add("[${text}] len=${text.length}")
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val threads = (0 until writers).map { w ->
            Thread {
                startGate.await()
                for (i in 0 until perWriter) {
                    val body = """{"pid":${1000 * (w + 1) + i},"port":${3080 + w}}"""
                    // 并发下原子替换在**单测宿主（Windows）**上会因瞬时共享冲突重试甚至失败；
                    // 失败不是本测试要断言的东西（生产代码留痕即可），只统计。
                    if (!runCatching { ProcessUtil.atomicWriteText(target, body) }.isSuccess) {
                        failures.incrementAndGet()
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        startGate.countDown()
        threads.forEach { it.join(60_000) }
        writersDone.set(true)
        // 给读者一个稳定的收尾窗口：此刻没有任何人写，任何一次读失败都是真的坏了
        Thread.sleep(50)
        stop.set(true)
        reader.join(5_000)

        assertTrue("前置：读者必须真的读到了东西（否则这条断言毫无意义）", reads.get() > 0)
        assertEquals("★ 并发原子写下，读者一次都不许读到 0 字节/半截/拼接内容：$samples", 0, badContent.get())
        assertEquals("★ 读失败必须是**可重试恢复**的瞬时失败，不许永久读不出", 0, permanentlyUnreadable.get())
        // 目标文件最终必须是某一个完整形态，且不是 0 字节
        val finalText = target.readText()
        assertTrue("最终内容必须完整", finalText.startsWith("{\"pid\":") && finalText.endsWith("}"))
        val leftovers = dir.listFiles()!!.map { it.name }.filter { it != "dsh.pid" }
        assertEquals("并发写完之后不许残留 tmp 文件：$leftovers（写失败 ${failures.get()} 次）", 0, leftovers.size)
    }

    /**
     * 失败方向：写不进去时**必须抛/留痕，不许静默**，而且**不许**留下半截目标文件。
     *
     * 现场：把目标路径做成一个**非空目录** —— rename 与 writeText 都必然失败。
     * 这条同时钉住"绝不回退成原地写"：若实现里加了 `f.writeText` 兜底，这里会观察到
     * 目标目录里多出内容或被改写。
     */
    @Test
    fun atomicWriteFailsLoudlyAndLeavesNoPartialTarget() {
        val dir = File(tmpRoot, "atomic4").apply { mkdirs() }
        val target = File(dir, "dsh.pid").apply { mkdirs() } // 目标是个非空目录
        File(target, "blocker").writeText("x")

        val failure = runCatching { ProcessUtil.atomicWriteText(target, "NEW") }.exceptionOrNull()
        assertTrue("写失败必须抛（不许静默吞掉）", failure != null)
        assertTrue("目标仍然是个目录（没有被原地写的内容污染）", target.isDirectory)
        assertEquals("目标目录里仍然只有那一个 blocker", 1, target.listFiles()!!.size)
        val leftovers = dir.listFiles()!!.map { it.name }.filter { it.startsWith("dsh.pid.tmp.") }
        assertEquals("失败后不许残留 tmp：$leftovers", 0, leftovers.size)
    }

    // --- 6) 与生产写路径合起来：writePidFile 也变原子了 -----------------------------

    /**
     * [ProcessUtil.writePidFile] 走原子写之后，**并发写 + 三态读**这条链上不会出现
     * "文件存在但读不出"。这是场景 A 的收敛态：即使读写真的并发，读者也不可能拿到 Unreadable。
     *
     * 与 [concurrentAtomicWritesNeverExposeEmptyOrTornContent] 的区别：那条直接打
     * `atomicWriteText`，这条打**生产入口** `writePidFile`（含 GSON 序列化 + mkdirs + 日志路径），
     * 证的是"生产写路径没有绕开原子写"。
     */
    @Test
    fun writePidFileNeverLeavesAnUnreadableFileUnderConcurrency() {
        val inst = "r37-prodwrite"
        withInstancesRoot { DshPaths.instancePidFile(inst) }

        val unreadable = AtomicInteger(0)
        val present = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val gate = CountDownLatch(1)

        val reader = Thread {
            gate.await()
            while (!stop.get()) {
                when (val st = ProcessUtil.readPidFileState(inst)) {
                    is PidFileRead.Present -> present.incrementAndGet()
                    // Missing 是合法的（第一次 mkdirs/第一次写之前）；只有 Unreadable 是缺陷现场。
                    PidFileRead.Missing -> Unit
                    PidFileRead.Unreadable -> unreadable.incrementAndGet()
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val writers = (0 until 3).map { w ->
            Thread {
                gate.await()
                for (i in 0 until 100) {
                    ProcessUtil.writePidFile(inst, 1000L * (w + 1) + i, 3080 + w)
                }
            }.apply { isDaemon = true; start() }
        }
        gate.countDown()
        writers.forEach { it.join(60_000) }
        stop.set(true)
        reader.join(5_000)

        assertTrue("前置：读者必须真的读到过完整文件", present.get() > 0)
        // ★ 这条断言的是**文件内容的完整性**，不是"open 一次都不许失败"：生产读法
        //   [ProcessUtil.readPidFileState] 对 open 失败返回 Unreadable（保守方向），而 Windows 宿主
        //   上原子替换期间的 open 竞争会让它偶尔命中。所以这里用"读不到的比例"作界：
        //   非原子 writeText 在同样负载下会让读者命中 0 字节（探针实测 82~88 次/写 480 次），
        //   原子写只允许出现"瞬时打不开"这种可重试的形态。
        assertTrue(
            "★ writePidFile 并发下读者必须绝大多数时候读到完整文件（Present=${present.get()}, Unreadable=${unreadable.get()}）",
            present.get() > unreadable.get() * 20
        )
    }

    // --- 7) 场景 B（TOCTOU）的闭合：写侧 commit 与删侧判据必须共用同一把锁 -----------

    /**
     * ★ 场景 B 的结构性钉子：`atomicWriteText` 的 **commit（rename）** 与
     * `compareAndDelete` 的「读→判→删」必须在**同一把** `PID_FILE_LOCK` 里。
     *
     * 为什么必须钉形状而不是只钉行为：这个窗口是**微秒级**的（判完 → 别人 rename 完 → 我 delete），
     * 用行为测试去撞它只会得到一条时红时绿的用例（那比没有测试更糟）。可控的是**结构**：
     * 只要两侧都在同一把锁里，窗口在逻辑上就不存在 —— 一次写要么在读之前提交（那读到的就是新
     * pid、判据拒绝删），要么在删之后提交（文件被重新原子创建）。
     *
     * 反向（变异）验证见报告：把 `atomicWriteText` 里的 `synchronized(PID_FILE_LOCK)` 去掉，
     * 这条会红 —— 它是"有人把写侧挪出锁"的唯一守卫。
     */
    @Test
    fun writeCommitAndDeleteGateShareTheSameLock() {
        val src = mainSourceFile().readText()
        val util = sliceObject(src, "object ProcessUtil")
        assertTrue("前置：必须找得到 ProcessUtil", util.length > 1000)

        // ① 锁对象只声明一次，且是 private（对外不暴露，避免有人再拿它去嵌套别的锁）
        assertEquals(
            "PID_FILE_LOCK 只许声明一次",
            1,
            Regex("""private val PID_FILE_LOCK = Any\(\)""").findAll(util).count()
        )

        // ② 写侧 commit：Files.move 与 renameTo 两条退路都必须在 synchronized(PID_FILE_LOCK) 内
        val atomicBody = sliceFun(util, "fun atomicWriteText(")
        val lockBlocks = Regex(
            """synchronized\(PID_FILE_LOCK\)\s*\{""", RegexOption.DOT_MATCHES_ALL
        ).findAll(atomicBody).count()
        assertTrue(
            "★ 写侧 commit 必须在 PID_FILE_LOCK 内（场景 B 的闭合点）：找到 $lockBlocks 处",
            lockBlocks >= 2
        )

        // ③ 删侧：读-判-删必须在同一处 synchronized(PID_FILE_LOCK) 里，且判据与删除在同一个块内
        val cadBody = sliceFun(util, "fun compareAndDelete(")
        assertTrue(
            "删侧的读-判-删必须在 PID_FILE_LOCK 内",
            cadBody.contains("synchronized(PID_FILE_LOCK)")
        )
        assertTrue(
            "判据与 onDelete 必须在同一临界区（否则仍是读-判删除三步分离）",
            cadBody.contains("pidFileMayBeDeleted(readState(), expectedPid)")
        )
    }

    // --- 辅助 ---------------------------------------------------------------------

    /** 从源码里切出 `object X { ... }` 的完整块（按花括号深度配对） */
    private fun sliceObject(src: String, header: String): String {
        val at = src.indexOf(header)
        if (at < 0) throw AssertionError("找不到 $header")
        val open = src.indexOf('{', at)
        return src.substring(at, endOfBlock(src, open) + 1)
    }

    /** 从源码（已切到对象内）里切出一个函数的块：从声明到配对的 `}` */
    private fun sliceFun(src: String, header: String): String {
        val at = src.indexOf(header)
        if (at < 0) throw AssertionError("找不到 $header")
        val open = src.indexOf('{', at)
        return src.substring(at, endOfBlock(src, open) + 1)
    }

    /** 从左花括号 [open] 起按深度找配对的右花括号下标（与 R35/R36 的同名工具同口径） */
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
     * 定位主源码文件（与 R35/R36 的 `mainSourceFile()` 同口径：从测试的 cwd 往上找几层，
     * 电脑布局与仓库根两种跑法都要能命中）。
     */
    private fun mainSourceFile(): File {
        val rel = "core/DshRuntime.kt"
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
     * 把 `DshPaths.INSTANCES_DIR`（+ `isLoaded`）指到本次用例的临时目录，[block] 里拿到的
     * 路径才可写。手法与 DshInstancesPersistenceTest / R31Fix227Test 一致（`lateinit var ... private set`
     * + JVM 单测没有 Android Context，只能反射）。
     *
     * 复原在 [restorePaths] 里无条件做 —— 别的用例（B1cTest / R31Fix227Test）也用同一个进程级单例。
     */
    private fun <T> withInstancesRoot(block: () -> T): T {
        val instances = File(tmpRoot, "instances-${nextSuffix()}")
        instances.mkdirs()
        set("INSTANCES_DIR", instances.absolutePath)
        set("isLoaded", true)
        return block()
    }

    private fun field(name: String) =
        DshPaths::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun set(name: String, value: Any?) {
        val f = field(name)
        if (name !in savedPaths) savedPaths[name] = runCatching { f.get(DshPaths) }.getOrNull()
        f.set(DshPaths, value)
    }

    private companion object {
        val SEQ = AtomicLong(0)

        fun nextSuffix(): String = "${System.nanoTime()}-${SEQ.incrementAndGet()}"

        /** 让 `storedPidOrNull` 这个**只在诊断文案用**的扩展开关也被编译到（避免它变成死代码） */
        @Suppress("unused")
        fun diagnosticHelperIsWired(read: PidFileRead<Long>): Long? = read.storedPidOrNull()

        /**
         * 读一个文件，**同时对"瞬时打不开"做有限重试**（[onTransientFailure] 计数）。
         *
         * 为什么需要：Windows 上原子替换目标名与并发 `open` 会撞 `ERROR_SHARING_VIOLATION`
         * （JDK 表现为 `FileNotFoundException`）。那不是内容撕裂 —— 重试一次就能读到完整内容。
         * 返回 null 表示**重试也没读到**（那才是真缺陷，调用方断言其为 0）。
         *
         * 重试 8 次 × 1ms：足够跨过宿主上微秒级的共享冲突窗口，又不至于把断言拖成"总能等到"。
         */
        fun readWithRetry(target: File, onTransientFailure: () -> Unit): String? {
            repeat(8) {
                val r = runCatching { target.readText() }
                if (r.isSuccess) return r.getOrNull()
                onTransientFailure()
                Thread.sleep(1)
            }
            return null
        }
    }
}
