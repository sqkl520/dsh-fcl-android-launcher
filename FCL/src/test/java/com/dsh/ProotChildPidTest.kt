package com.dsh

import com.dsh.core.ProotRunner
import org.junit.Assert.assertEquals
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
 * ## 怎么测：假 `/proc` 根目录
 * `/proc` 没法在测试进程里伪造（内核文件系统），所以两个函数都把 `procRoot` 做成了参数。
 * 测试里造一棵 `tmp/proc/self/task/<tid>/children` 目录树，直接喂进去。
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
