package com.dsh

import com.dsh.core.ProotRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ProotRunner.findChildPidFromProc` / `snapshotChildPids` 的回归测试 —— 钉住 B10 的**第二层**。
 *
 * ## 为什么光有 `toPid` 的测试不够
 * `ProcessPidConversionTest` 证明的是"反射拿到装箱 Integer 时别把它当拿不到"。
 * 但 `api-versions.xml`（android-35）里 `java/lang/Process` 根本没有 `pid()` 方法 ——
 * `android.jar` 里能编过只是编译期假象，**真机上 `getMethod("pid")` 抛 NoSuchMethodException**，
 * 那条反射路径一次都不会成功。真机上唯一能拿到 pid 的是 `/proc` 兜底，所以它必须有自己的测试。
 *
 * ## fix2 追加（BL-027 方案 A）
 * R13 真机确诊该设备内核无 `CONFIG_PROC_CHILDREN`（`childrenFilesPresent=0`，children 差集恒空），
 * 于是新增第三路径：`/proc` 全表扫描（ppid==本 App pid + cmdline 含 proot 与 INSTANCE_DIR 标记）。
 * 本文件下半部分钉它的纯函数（`parsePpidFromStat` / `selectProotPid` / `prootMatchCount` /
 * `identityMarkerFromArgv` / `scanProcChildren`）与 `snapshotChildPids` 的全扫回退。
 *
 * ## 怎么测：假 `/proc` 根目录
 * `/proc` 没法在测试进程里伪造（内核文件系统），所以这些函数都把 `procRoot` 做成了参数。
 * 测试里造一棵 `tmp/proc/self/task/<tid>/children` 目录树（或 `tmp/proc/<pid>/stat|cmdline`
 * 全扫形状），直接喂进去。
 *
 * 跑法：`sh run-tests.sh`
 */
class ProotChildPidTest {

    /** 造一棵假 /proc：`tids` 是"线程 id → 该线程的 children 文件内容" */
    private fun fakeProc(root: File, tids: Map<String, String>) {
        val task = File(root, "self/task")
        task.mkdirs()
        tids.forEach { (tid, children) ->
            val dir = File(task, tid)
            dir.mkdirs()
            File(dir, "children").writeText(children)
        }
    }

    private fun tempRoot(): File =
        File.createTempFile("fakeproc", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }

    /**
     * ★ 核心用例：从多个线程的 children 里挑出**新出现**的那个子进程。
     *
     * 真实形状：App 主进程有几十个线程，其中只有几个有子进程（我们的 proot、可能还有自检）。
     * children 文件里的 pid 用空格分隔；内核还可能在末尾放一个换行。
     */
    @Test
    fun picksNewChildAcrossThreads() {
        val root = tempRoot()
        fakeProc(
            root, mapOf(
                "100" to "5555 6666\n",     // 既有子进程（启动前就在）
                "101" to "",                 // 没子进程的线程
                "102" to "7777\n",           // 我们刚 start 的 proot
            )
        )
        assertEquals(7777L, ProotRunner.findChildPidFromProc(root.absolutePath, setOf(5555L, 6666L)))
    }

    /** 快照在 start **之后**拍就会这样：唯一的新 pid 是 proot，排除集里没有它 → 选中 */
    @Test
    fun picksSoleChildWhenNothingExcluded() {
        val root = tempRoot()
        fakeProc(root, mapOf("100" to "4242\n"))
        assertEquals(4242L, ProotRunner.findChildPidFromProc(root.absolutePath))
    }

    /**
     * **不排除就会认错进程** —— 这条钉住"必须传快照"。
     * 场景：启动器同时有别的子进程（自检 `runOnce` 的 proot）。若拿"第一个子进程"，
     * 会把自检那个当成刚启动的实例，写出假 pid，下次清理时误杀（`killBlocking` 的 cmdline
     * 校验是最后防线，但不该指望它兜住"一开始就选错"）。
     */
    @Test
    fun excludesPreExistingChildren() {
        val root = tempRoot()
        fakeProc(root, mapOf("100" to "1111\n", "101" to "2222\n"))
        // 不排除 → 拿到先遇到的 1111（错的那个）
        assertEquals(1111L, ProotRunner.findChildPidFromProc(root.absolutePath))
        // 排除掉自检的 1111 → 才是我们真正要的 2222
        assertEquals(2222L, ProotRunner.findChildPidFromProc(root.absolutePath, setOf(1111L)))
    }

    /** 排除光了 → -1（调用方据此打"无法取得 pid"的警告并退化为按 pid 文件缺失处理） */
    @Test
    fun returnsMinusOneWhenAllExcluded() {
        val root = tempRoot()
        fakeProc(root, mapOf("100" to "1111 2222\n"))
        assertEquals(-1L, ProotRunner.findChildPidFromProc(root.absolutePath, setOf(1111L, 2222L)))
    }

    /** 路径不存在（非 Linux / 沙箱里没有 /proc）→ -1 / 空集，**且不抛异常** */
    @Test
    fun returnsMinusOneWhenProcMissing() {
        assertEquals(-1L, ProotRunner.findChildPidFromProc("/definitely/not/here"))
        assertEquals(emptySet<Long>(), ProotRunner.snapshotChildPids("/definitely/not/here"))
    }

    /** children 文件缺失（线程刚死、或内核没开 CONFIG_PROC_CHILDREN）→ 跳过该 tid，不炸 */
    @Test
    fun skipsThreadsWithoutChildrenFile() {
        val root = tempRoot()
        val task = File(root, "self/task")
        File(task, "100").mkdirs()          // 有目录、没有 children 文件
        File(task, "101").mkdirs()
        File(task, "101/children").writeText("3333\n")
        assertEquals(3333L, ProotRunner.findChildPidFromProc(root.absolutePath))
    }

    /** 非数字的 task 目录名（内核不会有，但 `listFiles` 结果不该被信任）→ 跳过 */
    @Test
    fun skipsNonNumericTaskDirs() {
        val root = tempRoot()
        fakeProc(root, mapOf("100" to "4444\n"))
        File(File(root, "self/task"), "not-a-tid").mkdirs()
        assertEquals(4444L, ProotRunner.findChildPidFromProc(root.absolutePath))
    }

    /**
     * children 里的脏数据（多余空格 / 制表符 / 换行 / 非数字）→ 只取数字，且取到**第一个正整数**。
     * 内核正常输出是 `"1234 5678 \n"`，但解析不该假定得那么死。
     */
    @Test
    fun parsesDirtyChildrenContent() {
        val root = tempRoot()
        fakeProc(root, mapOf("100" to "  0\t9999\n  abc  \n"))
        // 0 不是合法 pid（`it > 0` 过滤掉），9999 才是
        assertEquals(9999L, ProotRunner.findChildPidFromProc(root.absolutePath))
    }

    /** 0 和负数一律不算 pid —— 否则会写出假 pid 去 kill(0)（那是"整个进程组"） */
    @Test
    fun rejectsZeroAndNegativePids() {
        val root = tempRoot()
        fakeProc(root, mapOf("100" to "0 -5\n"))
        assertEquals(-1L, ProotRunner.findChildPidFromProc(root.absolutePath))
    }

    /**
     * `snapshotChildPids` 取**所有线程的并集**（这是"排除集"的来源，漏一个就可能认错）。
     * 与 `findChildPidFromProc` 的关键差别：这里**不过滤 0**——它是纯粹的快照，
     * 过滤逻辑属于"挑一个"的那一步。
     */
    @Test
    fun snapshotTakesUnionOfAllThreads() {
        val root = tempRoot()
        fakeProc(root, mapOf("100" to "1111 2222\n", "101" to "2222 3333\n", "102" to ""))
        assertEquals(setOf(1111L, 2222L, 3333L), ProotRunner.snapshotChildPids(root.absolutePath))
    }

    /** 快照与挑选**配对使用**：先拍快照，起进程，再挑 —— 拿到的就是新来的那个 */
    @Test
    fun snapshotThenPickFindsTheNewProcess() {
        val root = tempRoot()
        fakeProc(root, mapOf("100" to "1111\n"))       // 启动前：只有一个既有子进程
        val before = ProotRunner.snapshotChildPids(root.absolutePath)
        assertEquals(setOf(1111L), before)

        fakeProc(root, mapOf("100" to "1111 9999\n"))  // 启动后：proot(9999) 出现
        assertEquals(9999L, ProotRunner.findChildPidFromProc(root.absolutePath, before))
    }

    // ---------------------------------------------------------------------------------------------
    // fix2 / BL-027 方案 A：/proc 全表扫描（children 文件缺失机型 = 真机确诊形状的第三条路）
    //
    // 真机确诊（R13-V7）：该设备内核 `CONFIG_PROC_CHILDREN` 未开（`childrenFilesPresent=0`），
    // 上面的 children 差集恒空；而反射路径在任意 Android 上结构必死（java.lang.Process 无 pid()）。
    // 全扫 = ppid==本 App pid 的候选 + cmdline 身份匹配（含 proot + INSTANCE_DIR 标记）。
    // 这一段钉住它的纯函数与假目录集成。
    // ---------------------------------------------------------------------------------------------

    /** 造一条内核格式的假 `stat` 行：`pid (comm) state ppid …` */
    private fun statLine(pid: Long, comm: String, ppid: Int): String =
        "$pid ($comm) S $ppid 4242 4242 0 -1 4194560 0 0 0 0 0 0 0 0 20 0 1 0 123 456 789 0 0 0 0 0 0 0"

    /** 造一条假进程目录：`<root>/<pid>/stat` + `<root>/<pid>/cmdline`（argv 用 NUL 分隔，同内核）。 */
    private fun fakeProcEntry(root: File, pid: Long, ppid: Int, comm: String, argv: List<String>) {
        val dir = File(root, pid.toString())
        dir.mkdirs()
        File(dir, "stat").writeText(statLine(pid, comm, ppid))
        // 内核的 cmdline 是 argv 逐项 NUL 分隔（末尾通常还有一个 NUL）——测试按同一形状写。
        // NUL 用运行时构造（0.toChar()），不写转义字面量 —— 与主代码 scanProcChildren 的写法对称。
        val nul = 0.toChar().toString()
        File(dir, "cmdline").writeText(argv.joinToString(nul) + nul)
    }

    /** 正常行：`)` 后第 2 个字段就是 ppid */
    @Test
    fun parsesPpidFromNormalStatLine() {
        assertEquals(5000, ProotRunner.parsePpidFromStat("1234 (proot) S 5000 1234 1234 0 -1"))
    }

    /** comm 含空格：不能按第一个空格切分，必须找**最后一个** `)` */
    @Test
    fun parsesPpidWhenCommContainsSpaces() {
        assertEquals(777, ProotRunner.parsePpidFromStat("1234 (my proot task) R 777 1 2 3"))
    }

    /** comm 含 `)`：最后一个 `)` 才是收尾括号（内核原样输出、不转义） */
    @Test
    fun parsesPpidWhenCommContainsParen() {
        assertEquals(42, ProotRunner.parsePpidFromStat("1234 (a)b) S 42 0 0"))
    }

    /** 垃圾行 → null（绝不抛异常：扫描循环里一行垃圾不该中断整轮扫描） */
    @Test
    fun rejectsGarbageStatLines() {
        assertNull(ProotRunner.parsePpidFromStat(""))
        assertNull(ProotRunner.parsePpidFromStat("no parens at all"))
        assertNull(ProotRunner.parsePpidFromStat("1234 (comm) S"))          // `)` 后不足 2 个字段
        assertNull(ProotRunner.parsePpidFromStat("1234 (comm) S notanumber"))
    }

    /** 全扫集成：假 /proc 下只有 ppid==myPid 的目录被收为候选；CMDline 的 NUL 转成空格。 */
    @Test
    fun scanFindsOnlyDirectChildren() {
        val root = tempRoot()
        fakeProcEntry(root, 4242, 5000, "proot", listOf("/data/app/libproot.so", "-r", "/rootfs"))
        fakeProcEntry(root, 4343, 5000, "proot", listOf("/data/app/libproot.so"))
        fakeProcEntry(root, 4444, 1, "init", listOf("/system/bin/init"))     // 别人的子进程
        File(root, "self").mkdirs()                                          // 非数字目录名
        val got = ProotRunner.scanProcChildren(root.absolutePath, 5000)
        assertEquals(setOf(4242L, 4343L), got.map { it.pid }.toSet())
        val c = got.first { it.pid == 4242L }
        assertTrue("cmdline 的 NUL 应转成空格", c.cmdline.contains("/data/app/libproot.so -r /rootfs"))
    }

    /** 端到端：全扫 + 身份匹配从假 /proc 里挑出带本实例标记的那条 proot，跳过兄弟（别的实例）与无关进程 */
    @Test
    fun fullScanPicksMarkedProotAmongSiblings() {
        val root = tempRoot()
        val marker = "INSTANCE_DIR=/opt/dsh/instances/abc"
        fakeProcEntry(root, 4242, 9000, "proot",
            listOf("/data/libproot.so", "INSTANCE_DIR=/opt/dsh/instances/other", "/bin/sh", "start-dsh.sh"))
        fakeProcEntry(root, 4343, 9000, "proot",
            listOf("/data/libproot.so", marker, "/bin/sh", "start-dsh.sh"))
        fakeProcEntry(root, 4444, 9000, "sh", listOf("/system/bin/sh", "ls"))
        val candidates = ProotRunner.scanProcChildren(root.absolutePath, 9000)
        assertEquals(4343L, ProotRunner.selectProotPid(candidates, 9000, marker, emptySet()))
    }

    /**
     * 多候选（同标记重复）→ 取 **pid 最大者**（= 最新 start 的那条）。
     * 设计决策见 `selectProotPid` 的注释：同标记重复只发生在秒启竞态，pid 单调递增。
     */
    @Test
    fun selectTakesHighestPidOnDuplicates() {
        val marker = "INSTANCE_DIR=/opt/dsh/instances/abc"
        val cands = listOf(
            ProotRunner.Candidate(1000L, "/x/libproot.so $marker"),
            ProotRunner.Candidate(2000L, "/x/libproot.so $marker")
        )
        assertEquals(2000L, ProotRunner.selectProotPid(cands, 9000, marker, emptySet()))
    }

    /** 无匹配 / 被排除 / 候选即自己（防自杀）/ 空列表 → null */
    @Test
    fun selectReturnsNullWithoutMatch() {
        val marker = "INSTANCE_DIR=/opt/dsh/instances/abc"
        assertNull(ProotRunner.selectProotPid(
            listOf(ProotRunner.Candidate(1000L, "/x/libproot.so INSTANCE_DIR=/opt/dsh/instances/other")),
            9000, marker, emptySet()))
        assertNull(ProotRunner.selectProotPid(
            listOf(ProotRunner.Candidate(1000L, "/x/libproot.so $marker")),
            9000, marker, setOf(1000L)))
        assertNull(ProotRunner.selectProotPid(
            listOf(ProotRunner.Candidate(9000L, "/x/libproot.so $marker")),
            9000, marker, emptySet()))
        assertNull(ProotRunner.selectProotPid(emptyList(), 9000, marker, emptySet()))
    }

    /** marker 为空串（自检 probe.sh 的 argv 没有 INSTANCE_DIR）→ 退化为只按 proot 名匹配 */
    @Test
    fun blankMarkerFallsBackToProotNameOnly() {
        val cands = listOf(ProotRunner.Candidate(1000L, "/x/libproot.so /bin/sh probe.sh"))
        assertEquals(1000L, ProotRunner.selectProotPid(cands, 9000, "", emptySet()))
    }

    /** cmdline 读不到（权限）→ 空串候选，永远不匹配；扫描不炸、该项直接出局 */
    @Test
    fun candidateWithoutCmdlineNeverMatches() {
        val cands = listOf(ProotRunner.Candidate(1000L, ""))
        assertNull(ProotRunner.selectProotPid(cands, 9000, "m", emptySet()))
        assertEquals(0, ProotRunner.prootMatchCount(cands, 9000, "m", emptySet()))
    }

    /** 诊断计数与选择器同判据（prootMatchCount 必须和 selectProotPid 的过滤结果一致） */
    @Test
    fun prootMatchCountSharesPredicate() {
        val marker = "INSTANCE_DIR=/opt/dsh/instances/abc"
        val cands = listOf(
            ProotRunner.Candidate(1000L, "/x/libproot.so $marker"),
            ProotRunner.Candidate(2000L, "/x/libsh.so nothing")
        )
        assertEquals(1, ProotRunner.prootMatchCount(cands, 9000, marker, emptySet()))
    }

    /** identityMarkerFromArgv：取 argv 里现成的 INSTANCE_DIR **整项**；没有 → 空串 */
    @Test
    fun extractsIdentityMarkerFromArgv() {
        val argv = listOf("/x/libproot.so", "-r", "/rootfs", "PATH=/bin",
            "INSTANCE_DIR=/opt/dsh/instances/abc", "/bin/sh", "start-dsh.sh")
        assertEquals("INSTANCE_DIR=/opt/dsh/instances/abc", ProotRunner.identityMarkerFromArgv(argv))
        assertEquals("", ProotRunner.identityMarkerFromArgv(listOf("/x/libproot.so", "/bin/sh", "probe.sh")))
    }

    /**
     * `snapshotChildPids` 回退（fix2 / BL-027 第 2 条）：children 文件全缺失（真机确诊形状：
     * tid 目录在、children 文件不在）时，用全扫按 ppid 收快照 —— 不能再返回空集
     * （空排除集会让差集把启动前就在的老子进程也当"新出现的"）。
     */
    @Test
    fun snapshotFallsBackToFullScanWhenChildrenMissing() {
        val root = tempRoot()
        val task = File(root, "self/task")
        File(task, "100").mkdirs()
        File(task, "101").mkdirs()
        fakeProcEntry(root, 5555, 5000, "proot", listOf("/x/libproot.so"))
        fakeProcEntry(root, 6666, 1, "init", listOf("/init"))
        assertEquals(setOf(5555L), ProotRunner.snapshotChildPids(root.absolutePath, 5000))
    }

    /** children 文件能读时**不**回退全扫：children 是权威口径（两条口径混用会把排除集算错） */
    @Test
    fun snapshotPrefersChildrenWhenPresent() {
        val root = tempRoot()
        fakeProc(root, mapOf("100" to "1111\n"))
        fakeProcEntry(root, 5555, 5000, "proot", listOf("/x/libproot.so"))
        assertEquals(setOf(1111L), ProotRunner.snapshotChildPids(root.absolutePath, 5000))
    }

    /**
     * **真机证据（不是"我声称"）**：`android.jar` 里 `java.lang.Process` 确实没有 `pid()`。
     *
     * 这条是**前提校验**：整套 `/proc` 兜底的必要性建立在"真机反射必失败"上。
     * 如果哪天 android.jar 加上了 `pid()`，说明前提变了，兜底该重新评估 —— 那时这条会红，
     * 逼人回来看。
     *
     * **为什么直接解析字节码、而不是 Class.forName**：`java.lang.*` 由 bootstrap 加载器独占，
     * 自定义 `URLClassLoader` 无论父加载器设成什么，`loadClass("java.lang.Process")` 都会
     * 委派给 bootstrap → 拿到的是 **JDK 的** Process（Java 9+ 有 `pid()`），
     * 测的就不是 android.jar 了。所以只能把 class 文件抠出来自己读方法表。
     *
     * 找不到 android.jar 就跳过（只 clone 源码仓、没配 SDK 的环境不该因此变红）。
     */
    @Test
    fun androidProcessHasNoPidMethod() {
        val jarPath = System.getProperty("dsh.android.jar")
            ?: System.getenv("ANDROID_HOME")?.let { "$it/platforms/android-35/android.jar" }
            ?: System.getenv("ANDROID_SDK_ROOT")?.let { "$it/platforms/android-35/android.jar" }
            ?: return
        val jar = File(jarPath)
        if (!jar.isFile) return

        val classBytes = java.util.zip.ZipFile(jar).use { zip ->
            val e = zip.getEntry("java/lang/Process.class") ?: return
            zip.getInputStream(e).use { it.readBytes() }
        }

        val methods = parseMethodNames(classBytes)
        assertTrue("解析出的方法表不该为空（解析器坏了的话这条先红）", methods.isNotEmpty())
        assertTrue(
            "android.jar 的 java.lang.Process 不该有 pid() —— 若有了，说明" +
                "「真机反射必失败」的前提变了，/proc 兜底该重新评估。实际方法表=$methods",
            !methods.contains("pid")
        )
        // 反向确认解析器真的在读这个方法表：这几个方法 android.jar 里**一定**有
        assertTrue("java.lang.Process 应当有 destroy() —— 没有就说明解析器读错了地方", methods.contains("destroy"))
        assertTrue("java.lang.Process 应当有 waitFor()", methods.contains("waitFor"))
    }

    /**
     * 极简 class 文件解析器：只取**方法名**集合。
     *
     * 为什么值得写这 40 行：上面那条测试要的是一份**能复现的机器证据**，
     * 而不是我在注释里写一句"我看过了"。Class 文件格式固定（JVMS §4），
     * 这里只走 constant_pool → fields → methods 三段，跳属性靠长度字段。
     */
    private fun parseMethodNames(b: ByteArray): Set<String> {
        var p = 0
        fun u1(): Int = b[p++].toInt() and 0xFF
        fun u2(): Int = (u1() shl 8) or u1()
        fun u4(): Int = (u2() shl 16) or u2()
        fun skip(n: Int) { p += n }

        require(u4() == 0xCAFEBABE.toInt()) { "不是 class 文件" }
        skip(4)                                  // minor + major

        // --- constant pool ---
        val cpCount = u2()
        val utf8 = HashMap<Int, String>(cpCount)
        var i = 1
        while (i < cpCount) {
            when (u1()) {
                1 -> { val len = u2(); utf8[i] = String(b, p, len, Charsets.UTF_8); skip(len) }
                3, 4 -> skip(4)                  // Integer / Float
                5, 6 -> { skip(8); i++ }         // Long / Double 占两个槽位
                7, 8, 16, 19, 20 -> skip(2)      // Class / String / MethodType / Module / Package
                15 -> skip(3)                    // MethodHandle
                else -> skip(4)                  // Fieldref / Methodref / InterfaceMethodref / NameAndType / Dynamic / InvokeDynamic
            }
            i++
        }

        skip(6)                                  // access_flags, this_class, super_class
        repeat(u2()) { skip(2) }                 // interfaces

        /** 跳过若干个成员（字段/方法）：access u2 + name u2 + descriptor u2 + attributes */
        fun skipMembers(count: Int, collect: Boolean): Set<String> {
            val names = LinkedHashSet<String>()
            repeat(count) {
                skip(2)                          // access_flags
                val nameIdx = u2()
                skip(2)                          // descriptor_index
                if (collect) utf8[nameIdx]?.let { names.add(it) }
                repeat(u2()) { skip(2); skip(u4()) }   // attributes: name_index + length + body
            }
            return names
        }

        skipMembers(u2(), collect = false)       // fields
        return skipMembers(u2(), collect = true) // methods
    }
}
