package com.dsh

import com.dsh.core.DshLogBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 日志按实例分流的 JVM 单元测试（不需要真机/Android）。
 *
 * 覆盖的是"分错流会静默出问题"的地方：
 * - `appendFor` 必须**同时**进实例流与全局流（否则"复制全部日志"里就丢了运行输出）；
 * - `append` 只能进全局（否则 App 级事件会污染某个实例的日志）；
 * - 实例之间互相隔离（A 的日志里出现 B 的行 = 用户按实例排障时被误导）；
 * - `clearFor` / `export` / `size` 的范围；
 * - 脱敏对**两条流**都生效（实例流是新增路径，最怕新路径漏了脱敏把 token 写进 instance-*.log）。
 *
 * ★ 本类**不调** [DshLogBus.attachFile]：它会起协程做落盘/截断 IO，而这里要验证的是内存两条流
 *   与文件名推导；落盘路径由 `instanceLogFile` 的形状断言覆盖（不需要真写盘）。
 * ★ 200ms 的合并刷新用 [DshLogBus.flushNow] 同步推进，不 sleep。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.DshLogBusRoutingTest"
 */
class DshLogBusRoutingTest {

    /**
     * 每个测试用**互不重复**的实例 id：DshLogBus 是进程内单例，测试之间共享同一份缓冲，
     * 复用 id 会让"A 的行"在别的测试里冒出来（那是测试自己的锅，不是被测代码的）。
     */
    private var seq = 0
    private fun newId(tag: String): String = "inst-test-$tag-${seq++}"

    /**
     * 每个测试都从"全局缓冲为空"开始。
     *
     * ★ 为什么必须清：DshLogBus 是进程内单例，测试之间共享同一条全局流，而全局是 **2000 行的环形
     *   缓冲**（超出裁掉最老的行）。本文件里"灌 2100 行"那个测试会把之前所有测试的 marker 挤出全局，
     *   于是断言全局内容的测试会随执行顺序时过时不过。清一次让每个测试看到确定的起点。
     *   实例流不用清：每个测试用的实例 id 都是新的。
     */
    @Before
    fun clearGlobalBuffer() {
        DshLogBus.clear()
        DshLogBus.flushNow()
    }

    /** 造一行在全局里唯一可辨认的文本（用 contains 断言时不会和别的测试的行撞上） */
    private fun marker(tag: String): String = "MARK-$tag-${seq++}-${System.nanoTime()}"

    // --- 1. appendFor 同时进两条流 ------------------------------------------

    @Test
    fun appendForGoesToBothInstanceAndGlobal() {
        val id = newId("both")
        val m = marker("both")

        DshLogBus.appendFor(id, m)
        DshLogBus.flushNow()

        val inInstance = DshLogBus.snapshotFor(id).value.lines.any { it.contains(m) }
        val inGlobal = DshLogBus.snapshot.value.lines.any { it.contains(m) }
        assertTrue("实例流里应当有这一行", inInstance)
        // ★ 这条断言是本次改造的核心约束：实例行**同时**进全局，全局是"时间线全貌"，
        //   只进实例流会让"复制全部日志"丢掉最需要的运行输出。
        assertTrue("全局流里也应当有这一行（实例流 ⊂ 全局流）", inGlobal)
    }

    /** 同一行在两条流里应当是**同一个字符串**（同一时间戳），否则按时间对照时会错位 */
    @Test
    fun sameLineHasIdenticalTextInBothStreams() {
        val id = newId("identical")
        val m = marker("identical")

        DshLogBus.appendFor(id, m)
        DshLogBus.flushNow()

        val fromInstance = DshLogBus.snapshotFor(id).value.lines.first { it.contains(m) }
        val fromGlobal = DshLogBus.snapshot.value.lines.first { it.contains(m) }
        assertEquals(fromGlobal, fromInstance)
    }

    // --- 2. append 只进全局 -------------------------------------------------

    @Test
    fun appendGoesToGlobalOnly() {
        val id = newId("globalonly")
        // 先创建这个实例的流：否则 size(id)=0 只是因为"流还不存在"，证明不了 append 没写进去
        DshLogBus.snapshotFor(id)
        val m = marker("globalonly")

        DshLogBus.append(m)
        DshLogBus.flushNow()

        assertTrue(
            "全局流里应当有这一行",
            DshLogBus.snapshot.value.lines.any { it.contains(m) }
        )
        assertFalse(
            "App 级行（解压/清单/跨实例清理）不该出现在任何实例流里",
            DshLogBus.snapshotFor(id).value.lines.any { it.contains(m) }
        )
    }

    // --- 3. 两个实例互相隔离 -----------------------------------------------

    @Test
    fun twoInstanceStreamsAreIsolated() {
        val a = newId("iso-a")
        val b = newId("iso-b")
        val ma = marker("iso-a")
        val mb = marker("iso-b")

        DshLogBus.appendFor(a, ma)
        DshLogBus.appendFor(b, mb)
        DshLogBus.flushNow()

        val linesA = DshLogBus.snapshotFor(a).value.lines
        val linesB = DshLogBus.snapshotFor(b).value.lines

        assertTrue("A 的流里要有 A 的行", linesA.any { it.contains(ma) })
        assertTrue("B 的流里要有 B 的行", linesB.any { it.contains(mb) })
        assertFalse("A 的流里绝不能有 B 的行", linesA.any { it.contains(mb) })
        assertFalse("B 的流里绝不能有 A 的行", linesB.any { it.contains(ma) })
        // 全局里两条都要在：隔离只发生在实例维度，全局仍是"所有事情的时间线"
        val global = DshLogBus.snapshot.value.lines
        assertTrue(global.any { it.contains(ma) })
        assertTrue(global.any { it.contains(mb) })
    }

    // --- 4. clearFor 只清该实例 --------------------------------------------

    @Test
    fun clearForOnlyClearsThatInstance() {
        val a = newId("clear-a")
        val b = newId("clear-b")
        val ma = marker("clear-a")
        val mb = marker("clear-b")

        DshLogBus.appendFor(a, ma)
        DshLogBus.appendFor(b, mb)
        DshLogBus.flushNow()
        assertEquals(1, DshLogBus.size(a))
        assertEquals(1, DshLogBus.size(b))

        DshLogBus.clearFor(a)
        DshLogBus.flushNow()

        assertEquals("A 应当被清空", 0, DshLogBus.size(a))
        assertEquals("B 不受影响", 1, DshLogBus.size(b))
        // 全局那份也不动：清实例日志不能把"时间线全貌"里的行一起抹掉
        assertTrue(
            "全局流不该被 clearFor 影响",
            DshLogBus.snapshot.value.lines.any { it.contains(ma) }
        )
        assertTrue(DshLogBus.snapshot.value.lines.any { it.contains(mb) })
    }

    /** 反向：全局的 clear() 不能把实例流清掉（否则实例页会莫名空白） */
    @Test
    fun clearDoesNotTouchInstanceStreams() {
        val id = newId("clear-global")
        val m = marker("clear-global")

        DshLogBus.appendFor(id, m)
        DshLogBus.flushNow()

        DshLogBus.clear()
        DshLogBus.flushNow()

        assertTrue(
            "clear() 只清全局，实例流应保持原样",
            DshLogBus.snapshotFor(id).value.lines.any { it.contains(m) }
        )
        assertEquals(1, DshLogBus.size(id))
    }

    /**
     * 清一个从没写过的实例：什么都不做，也不该把它的流"创建"出来。
     *
     * ★ 怎么观察"有没有被创建"：一个刚创建的流 revision 是 0，而任何一次被标脏后的刷新都会让它
     *   变成 1（见 DshLogBus.flush 的 per-stream revision 递增）。所以"clearFor 之后 revision 仍是 0"
     *   就是"它没有因为这次 clearFor 而被创建/标脏"的证明 —— 光断言 size==0 是证明不了的，
     *   一个新建的空流也是 0 行。
     */
    @Test
    fun clearForUnknownInstanceDoesNotCreateStream() {
        val id = newId("clear-unknown")
        DshLogBus.clearFor(id)
        DshLogBus.flushNow()
        assertEquals(0, DshLogBus.size(id))
        assertEquals(
            "clearFor 不该创建/标脏一个不存在的实例流",
            0L,
            DshLogBus.snapshotFor(id).value.revision
        )
    }

    // --- 5. export 的范围 ---------------------------------------------------

    @Test
    fun exportScopesToGlobalOrInstance() {
        val a = newId("export-a")
        val b = newId("export-b")
        val ma = marker("export-a")
        val mb = marker("export-b")

        DshLogBus.appendFor(a, ma)
        DshLogBus.appendFor(b, mb)
        DshLogBus.flushNow()

        val instA = DshLogBus.export(a)
        assertTrue(instA.contains(ma))
        assertFalse("export(A) 不能含 B 的行", instA.contains(mb))

        // 无参 = 全局（默认值，向后兼容）
        val global = DshLogBus.export()
        assertTrue(global.contains(ma))
        assertTrue(global.contains(mb))

        // 不存在的实例：空串，不是全局、也不是异常
        assertEquals("", DshLogBus.export(newId("export-missing")))
    }

    // --- 6. size 的范围 -----------------------------------------------------

    @Test
    fun sizeScopesToGlobalOrInstance() {
        val a = newId("size-a")
        val b = newId("size-b")
        val ma = marker("size-a")
        val mb = marker("size-b")

        DshLogBus.appendFor(a, ma)
        DshLogBus.appendFor(a, marker("size-a"))
        DshLogBus.appendFor(b, mb)
        DshLogBus.flushNow()

        assertEquals(2, DshLogBus.size(a))
        assertEquals(1, DshLogBus.size(b))
        // 全局行数不做"等于各实例之和"的断言：全局也是 2000 行的环形缓冲，
        // 其它测试灌满之后老行会被裁掉。这里断言"实例的最新行确实在全局里"（这才是要保证的语义）。
        val globalLines = DshLogBus.snapshot.value.lines
        assertTrue("A 的行应能在全局里找到", globalLines.any { it.contains(ma) })
        assertTrue("B 的行应能在全局里找到", globalLines.any { it.contains(mb) })
        assertEquals(0, DshLogBus.size(newId("size-missing")))
    }

    // --- 7. instanceLogFile 的路径形状 -------------------------------------

    @Test
    fun instanceLogFileHasExpectedShape() {
        val a = newId("file-a")
        val b = newId("file-b")
        val fa = DshLogBus.instanceLogFile(a)
        val fb = DshLogBus.instanceLogFile(b)

        assertTrue("文件名应以 instance- 开头: ${fa.name}", fa.name.startsWith("instance-"))
        assertTrue("文件名应以 .log 结尾: ${fa.name}", fa.name.endsWith(".log"))
        assertTrue("文件名应含实例 id: ${fa.name}", fa.name.contains(a))
        // 同一目录下：全局 runtime.log 与各实例日志放在同一个 logs/ 目录，
        // 实例之间也必须在同一目录（不然"日志都在哪"要按实例分别回答）
        assertEquals(
            "不同实例的日志文件应在同一目录",
            fa.parentFile?.absolutePath,
            fb.parentFile?.absolutePath
        )
        assertFalse("全局日志文件不该被当成实例文件", fa.name == "runtime.log")
        assertFalse("不同实例不能共用一个文件", fa.absolutePath == fb.absolutePath)
    }

    /**
     * id 里若含路径分隔符/盘符字符，落盘必须被转义：否则会写到别的目录或与别的实例互相覆盖。
     * 正常 id（`inst-1700000000000-3-7f2a`）全是安全字符，这里只是把防御行为钉住。
     */
    @Test
    fun instanceLogFileEscapesUnsafeIdCharacters() {
        val weird = "inst/../x:y"
        val f = DshLogBus.instanceLogFile(weird)
        // 转义后仍落在同一个 logs 目录，且文件名只有一个路径段（不含分隔符）
        assertEquals(
            "转义后应落在同一目录",
            DshLogBus.instanceLogFile("inst-safe").parentFile,
            f.parentFile
        )
        assertFalse("文件名里不能出现 '/'", f.name.contains('/'))
        assertFalse("文件名里不能出现 ':'", f.name.contains(':'))
        assertFalse("文件名里不能出现 '\\'", f.name.contains('\\'))
        assertTrue(f.name.startsWith("instance-"))
        assertTrue(f.name.endsWith(".log"))
    }

    // --- 8. 脱敏对两条流都生效 ---------------------------------------------

    @Test
    fun sanitizesTokenInBothStreams() {
        val id = newId("sanitize")
        val m = marker("sanitize")
        val raw = "dsh web: http://127.0.0.1:3080/$m?token=SECRET_$id"

        DshLogBus.appendFor(id, raw)
        DshLogBus.flushNow()

        // 用这一行独有的 marker 定位，避免在共享的全局缓冲里抓到别的测试留下的行
        val fromInstance = DshLogBus.snapshotFor(id).value.lines.first { it.contains(m) }
        val fromGlobal = DshLogBus.snapshot.value.lines.first { it.contains(m) }

        assertFalse("实例流里的 token 必须被脱敏: $fromInstance", fromInstance.contains("SECRET_$id"))
        assertFalse("全局流里的 token 必须被脱敏: $fromGlobal", fromGlobal.contains("SECRET_$id"))
        assertTrue(fromInstance.contains("token=***"))
        assertTrue(fromGlobal.contains("token=***"))
        // 脱敏不能顺手把排查需要的信息抹掉
        assertTrue(fromInstance.contains("127.0.0.1:3080"))
    }

    /** export 出去的文本同样必须是脱敏后的（复制/分享是最容易漏的一条出口） */
    @Test
    fun exportIsSanitizedForBothScopes() {
        val id = newId("export-sanitize")
        val secret = "LEAK${System.nanoTime()}"
        val raw = "using url http://127.0.0.1:3080/?token=$secret now"

        DshLogBus.appendFor(id, raw)
        DshLogBus.flushNow()

        assertFalse("export(id) 不能带 token", DshLogBus.export(id).contains(secret))
        assertFalse("export() 不能带 token", DshLogBus.export().contains(secret))
    }

    // --- 9. snapshotFor 首次调用即创建，且返回同一个 StateFlow --------------

    @Test
    fun snapshotForCreatesOnFirstCallAndReusesInstance() {
        val id = newId("firstcall")

        // 首次调用即创建：拿到的是"空但存在"的快照（revision 0 / 无行）
        val first = DshLogBus.snapshotFor(id)
        assertEquals(DshLogBus.Snapshot.EMPTY, first.value)
        assertEquals(0, DshLogBus.size(id))

        // 同一个 id 两次调用必须返回**同一个** StateFlow：每次 new 一个的话，
        // UI 收集到的那个实例永远收不到后续新行（而且第一次的收集者会变成泄漏）
        val second = DshLogBus.snapshotFor(id)
        assertSame("同一 id 必须复用同一个 StateFlow", first, second)

        // 不同 id 必须是不同的流
        val other = DshLogBus.snapshotFor(newId("firstcall-other"))
        assertFalse("不同实例不能共用一条流", first === other)

        // 创建后往它写一行：同一条流要能看到（验证"复用同一个"是有意义的，而不是各自一份副本）
        val m = marker("firstcall")
        DshLogBus.appendFor(id, m)
        DshLogBus.flushNow()
        assertTrue(first.value.lines.any { it.contains(m) })
        assertEquals("首次写入后该实例的 revision 应为 1", 1L, first.value.revision)
    }

    /** revision 按流独立：别的实例刷了不该让这个实例的 revision 变（否则 UI 白重绘） */
    @Test
    fun revisionIsPerStream() {
        val a = newId("rev-a")
        val b = newId("rev-b")

        DshLogBus.appendFor(a, marker("rev-a"))
        DshLogBus.flushNow()
        val revA = DshLogBus.snapshotFor(a).value.revision
        val revB = DshLogBus.snapshotFor(b).value.revision

        DshLogBus.appendFor(b, marker("rev-b"))
        DshLogBus.flushNow()

        assertEquals("A 没有新行，revision 不该变", revA, DshLogBus.snapshotFor(a).value.revision)
        assertTrue("B 有新行，revision 必须变", DshLogBus.snapshotFor(b).value.revision > revB)
    }

    /** 实例缓冲与全局同一上限（[DshLogBus] 的 MAX_LINES=2000），且是"留尾部" */
    @Test
    fun instanceBufferKeepsOnlyTailAtLimit() {
        val id = newId("cap")
        repeat(2100) { DshLogBus.appendFor(id, "cap-line-$it") }
        DshLogBus.flushNow()

        val lines = DshLogBus.snapshotFor(id).value.lines
        assertEquals(2000, lines.size)
        assertTrue("超限后应保留尾部（最新的那行必须在）", lines.last().endsWith("cap-line-2099"))
        assertFalse("最老的行应被裁掉", lines.any { it.endsWith("cap-line-0") })
        assertFalse("被裁掉的老行不该还在", lines.any { it.endsWith("cap-line-99") })
    }

    /** 实例 id 本身不该被当成文件路径的一部分而越界（防御性：见 instanceLogFile 的注释） */
    @Test
    fun instanceLogFileStaysInOneDirectory() {
        val f = DshLogBus.instanceLogFile("../../etc/passwd")
        assertEquals(DshLogBus.instanceLogFile("x").parentFile, f.parentFile)
        assertFalse("文件名里不能出现分隔符", f.name.contains('/') || f.name.contains('\\'))
        assertTrue(f.name.startsWith("instance-"))
        assertTrue(f.name.endsWith(".log"))
        // 最终落点必须仍在该目录内（canonicalPath 会解析掉任何残留的相对段）
        val dir = f.parentFile!!.canonicalPath
        assertTrue("绝对路径不该逃出该目录: ${f.canonicalPath}", f.canonicalPath.startsWith(dir))
    }
}
