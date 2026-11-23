package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.ProotRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * round49 / W1-C：`ProotProcessExecutor.kt` 的两条缺陷（**只改这一个文件**）。
 *
 * | # | 位置（修复前） | 缺陷 | 本轮的改法 |
 * |---|---|---|---|
 * | C-1 🟠 | `:942` `runCatching { handle.pid() }` 裸调用 | pid 预解析失败时无现场：同文件现成的 [ProotRunner.Handle.logPidFailureOnce] 没人调 | `.onFailure { handle.logPidFailureOnce(it) }` |
 * | C-2 🟡 | `:155` / `:318` `catch (_: InterruptedException) { }` | 吞掉中断却不恢复标志，与同文件 `:62`（eh-01 已修的规范）不一致 | 各补 `Thread.currentThread().interrupt()` |
 *
 * ## 判别力说明（先说清楚，别当成强测试看）
 *
 * - **C-1 第 1、2 条（源码文本）**：没有运行时判别力。预解析那一句要跑起来得有 `Context`
 *   + 真 proot 二进制 + 真子进程，JVM 单测做不到（`ProotRunner.start` 里的 `spec.toProcessBuilder()`
 *   需要可执行的 proot）。这两条只钉**代码形态**，配了反面断言让"最省事的回退"（裸 `runCatching`）
 *   变红。
 * - **C-1 第 3、4 条（真跑）**：**有**运行时判别力 —— 直接构造 `Handle`（`Process` 用假子类）
 *   调 `logPidFailureOnce(cause?)`，断言它真的往 `DshLogBus` 追加了一行、且 `thrown=` 字段的
 *   有无取决于有没有传 cause。`logPidFailureOnce` 是 `internal`，单测在模块内可见
 *   （Gradle 单测与 `tools/run-tests.sh` 的 `-Xfriend-paths` 两条路都给了 friend 关系）。
 * - **C-2（全部源码文本）**：**没有**运行时判别力。那两个 `catch` 分别活在看门狗线程体与
 *   强杀线程体里，只能由 `run()` / `terminate()` 触发，而两者都需要真的 `Context` 与真子进程；
 *   就算能跑，"标志恢复没恢复"对**当前行为**也没有可观测差异（两个线程都跑一次就退出 ——
 *   这正是审查把它定为 🟡 规范性修正、不是 bug 的原因）。所以这里只钉形态。
 *
 * 依据：`docs/reports/round49/agents/RELIABILITY.md` 的 P1-2 与 P2-5。
 */
class R49ProotExecFixTest {

    private val src by lazy { mainSourceFile("core/ProotProcessExecutor.kt") }

    // ======================================================================
    // C-1：pid 预解析失败必须留下现场
    // ======================================================================

    /**
     * `ProotRunner.start()` 里那次"立刻解析一次 pid"必须接 `onFailure` 并调现成的诊断。
     *
     * ## 为什么这条重要（照抄审查的原话再补一句）
     *
     * 审查说：pid 为 -1 时上层**是有日志的**（`DshRuntime` 的"警告：无法取得 proot 进程 pid"），
     * 所以不算完全静默；但从那行看不出"哪条路失败的、为什么"。而本文件里
     * `describePidFailure()` / `logPidFailureOnce()` 这套诊断（`:738` 一带）**早就存在**，
     * 只是 `Handle.pid()` 内部那条路径在调、启动预解析这条路径没调。
     *
     * 补一句审查没说的：`Handle.pid()` 的三条路径各自都包了 `runCatching`，所以今天它其实
     * **抛不出来** —— 这条修复是**防御性接线**（万一将来某条路径的守卫被改坏/新增代码让异常穿出，
     * 现场仍在）。这一点在 [handlePidNeverThrowsOnHostileProcess] 里有运行时证据，别把它当成
     * "修掉了一个正在发生的 bug"。
     *
     * 变异验证：把 `.onFailure { … }` 去掉 ⇒ 本条变红。
     */
    @Test
    fun pidPreResolveWiresTheExistingDiagnostic() {
        val body = sliceBetween(src.readText(), "fun start(", "return handle")
        assertTrue(
            "找不到 `runCatching { handle.pid() }` 的预解析点（源码结构变了？）",
            body.contains("runCatching { handle.pid() }")
        )
        assertTrue(
            "★ C-1：pid 预解析失败必须调现成的 handle.logPidFailureOnce（否则只剩上层那句" +
                "「无法取得 proot 进程 pid」，看不出反射/差集/全扫哪条死、还是别的地方抛了）",
            Regex("""runCatching \{ handle\.pid\(\) \}[\s\S]{0,60}?\.onFailure""").containsMatchIn(body)
        )
        assertTrue(
            "★ C-1：onFailure 里必须真的调用 logPidFailureOnce（只写个空 lambdas 不算）",
            Regex("""\.onFailure\s*\{[^}]*logPidFailureOnce""").containsMatchIn(body)
        )
    }

    /**
     * 反面断言：这条预解析不许退回**裸** `runCatching { handle.pid() }`。
     *
     * ★ 与 `R49SilentFailureTest` 同一个坑：修复前后**这一行开头逐字相同**，区别在行尾有没有
     *   `.onFailure`（或下一行以 `.onFailure` 开头）。只判单行会假绿，所以判据是"这一行 + 紧跟的
     *   非空非注释行"里必须有 `.onFailure`。
     *
     * ★ 注释行要**排除**在扫描之外：本文件（以及修复处的 KDoc）里都引用了这句代码原文，
     *   连注释一起数会数出 2 处（实测踩到过 —— 第一版就是这么假红的）。
     */
    @Test
    fun pidPreResolveIsNotBareRunCatching() {
        val body = sliceBetween(src.readText(), "fun start(", "return handle")
        val lines = body.lines()
        var hits = 0
        var bare = 0
        for (i in lines.indices) {
            val line = lines[i]
            if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) continue
            if (!line.contains("runCatching { handle.pid() }")) continue
            hits++
            if (line.contains(".onFailure")) continue
            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].trimStart().startsWith("//"))) j++
            if (!(lines.getOrNull(j)?.trimStart() ?: "").startsWith(".onFailure")) bare++
        }
        assertEquals(
            "前置条件：预解析点应恰好 1 处代码行（实际 $hits）—— 0 处说明写法变了，2 处说明有人又加了一处",
            1, hits
        )
        assertEquals(
            "★ C-1：pid 预解析的 runCatching 不许没有 .onFailure 收尾（那就退回了缺陷原文形态）",
            0, bare
        )
    }

    /**
     * `logPidFailureOnce` 必须是**外层 object 够得到**的可见性，且仍旧"每 Handle 只打一次"。
     *
     * 为什么钉可见性：`ProotRunner.start()` 是外层 object 的函数，`Handle` 是它的嵌套类。
     * Kotlin 的外层 object **不能**访问嵌套类的 `private` 成员（实测编译器报
     * `cannot access 'fun ...': it is private in 'ProotRunner.Handle'`）—— 所以这条修复必须
     * 同时把可见性从 `private` 放到 `internal`，否则"改法"根本编译不过。
     */
    @Test
    fun pidFailureDiagnosticIsReachableAndIdempotent() {
        val text = src.readText()
        assertFalse(
            "★ C-1：logPidFailureOnce 不能是 private —— 外层 ProotRunner.start 够不到嵌套类的 private",
            Regex("""private\s+fun\s+logPidFailureOnce""").containsMatchIn(text)
        )
        assertTrue(
            "★ C-1：logPidFailureOnce 必须存在且对模块内可见",
            Regex("""(internal|public|@JvmStatic[\s\S]{0,20}public)\s+fun\s+logPidFailureOnce""").containsMatchIn(text)
        )
        // 幂等载体不变：两个线程（调用方与 pid() 内部）可能同时打，CAS 保证只有一条
        val fn = sliceBetween(text, "fun logPidFailureOnce", "fun describePidFailure")
        assertTrue(
            "★ C-1：只打一次的判据必须仍是 pidFailureLogged 的 CAS（去掉它会让每次失败都刷一行）",
            fn.contains("pidFailureLogged.compareAndSet(false, true)")
        )
        assertTrue(
            "★ C-1：诊断本身必须仍包在 runCatching 里（为失败加的诊断不许反过来把调用方带崩）",
            Regex("""runCatching\s*\{[\s\S]{0,400}?DshLogBus\.append""").containsMatchIn(fn)
        )
    }

    /**
     * 运行时：`logPidFailureOnce(cause)` 真的往日志总线追加一行**带 `thrown=` 的诊断**，
     * 且第二次调用不再追加（幂等）。
     *
     * 这是本文件里对 C-1 唯一有**运行时判别力**的断言（`thrown=` 那段是新加的：只看 /proc 现场
     * ——tids / childrenFilesPresent / newAfterDiff——根本分不清"三条路都算不出"与"某处抛了个异常"）。
     *
     * ★ 判据是"两个不同 token 里只有第一个出现在日志里"，**不是**"新增了几行"：`DshLogBus`
     *   是进程内单例且只留最近 2000 行（`MAX_LINES`），缓冲满时 append 会同时从**头部**裁掉一行，
     *   行数不变 —— 按 size 差值断言会在别的测试写满缓冲后偶发红（实测踩到过一次）。
     *   尾部行不会被裁，所以按内容找 token 是稳的。
     */
    @Test
    fun logPidFailureOnceAppendsOneLineWithCause() {
        val token1 = "dsh-r49-w1c-first-" + System.nanoTime()
        val token2 = "dsh-r49-w1c-second-" + System.nanoTime()
        val handle = ProotRunner.Handle(FakeProcess())

        handle.logPidFailureOnce(RuntimeException("boom-$token1"))
        handle.logPidFailureOnce(RuntimeException("boom-$token2"))

        val all = DshLogBus.export()
        assertTrue(
            "★ C-1：第一次调用必须真的往总线追加一条诊断（describePidFailure 的现场字段）；" +
                "日志尾部：${all.lines().takeLast(3)}",
            all.contains("pid resolution failed")
        )
        assertTrue(
            "★ C-1：传入 cause 时行尾必须带 thrown=类名: message（否则分不清「算不出」与「抛了异常」）",
            all.contains("thrown=java.lang.RuntimeException: boom-$token1")
        )
        assertFalse(
            "★ C-1：第二次调用必须被 pidFailureLogged 的 CAS 挡掉（否则 pid 拿不到期间会刷屏）",
            all.contains(token2)
        )
    }

    /** 运行时：不传 cause（`pid()` 自己那条 -1 路径）时不许凭空长出 `thrown=` 字段。 */
    @Test
    fun logPidFailureOnceWithoutCauseOmitsThrownField() {
        ProotRunner.Handle(FakeProcess()).logPidFailureOnce()
        // 取**最近一条** `pid resolution failed`（不取"末行"：别的测试类也在写同一条总线）
        val line = DshLogBus.export().lines().lastOrNull { it.contains("pid resolution failed") }
        assertNotNull(
            "★ C-1：不传 cause 也必须打 1 行（这条路径的既有行为不许被改动）",
            line
        )
        assertFalse(
            "★ C-1：没有 cause 时不该出现 thrown= 字段（-1 是「算不出」，不是「抛出」）；实际：$line",
            line!!.contains("thrown=")
        )
    }

    /**
     * 前提锚（**不是**在守 C-1 的修复）：`Handle.pid()` 今天对敌意 `Process` 也**不抛**。
     *
     * 作用是把"这条修复是防御性接线、不是修正在发生的 bug"写成可执行事实 —— 免得下一轮有人
     * 看到 `.onFailure` 就以为 pid() 真的会抛，进而去"修"一个不存在的异常路径。
     * 若将来 pid() 变成可抛，本条会变红 ⇒ 那正是 `.onFailure` 从"防御"变成"在用"的时刻。
     */
    @Test
    fun handlePidNeverThrowsOnHostileProcess() {
        val handle = ProotRunner.Handle(FakeProcess(hostile = true))
        val pid = try {
            handle.pid()
        } catch (t: Throwable) {
            throw AssertionError("pid() 不该抛（三条路径各自包了 runCatching）：$t", t)
        }
        assertEquals("拿不到 pid 时契约是 -1（不是 0、也不是抛）", -1L, pid)
    }

    // ======================================================================
    // C-2：吞掉 InterruptedException 必须恢复中断标志
    // ======================================================================

    /**
     * 全文件扫描：**每一个** `catch (… : InterruptedException)` 体里都必须恢复中断标志。
     *
     * 这条是 C-2 的主断言：它不只盯审查点名的 `:155` / `:318`，而是把"以后新加的空 catch"也一起
     * 管住 —— 缺陷的本质是**规范不一致**（同文件 `:62` 的 eh-01 修复恢复了标志，另两处没有），
     * 所以判据就该是一份全文件不变式，而不是两个孤立位置。
     *
     * ★ 数量断言写 `>= 2` 而不是 `== 3`：`:62` 那处规范范本**不是 catch 块**，而是
     *   `reportRunFailure` 里的 `if (e is InterruptedException)`（顶层 catch 抓 Exception 后
     *   在里面判类型）。本文件真正的 `catch (... InterruptedException)` 只有 2 个，就是 C-2 那两处
     *   （第一版把 `:62` 也当成 catch，于是数出 3、假红过一次）。那条 `if` 形态另由
     *   [interruptNormExistsInTopLevelCatch] 单独钉住，两种形态一起覆盖。
     *
     * 变异验证：删掉任一处 `Thread.currentThread().interrupt()` ⇒ 本条变红（实测见报告）。
     */
    @Test
    fun everyInterruptedExceptionCatchRestoresInterruptFlag() {
        val text = src.readText()
        val head = Regex("""catch\s*\(\s*\w*\s*:\s*InterruptedException\s*\)\s*\{""")
        val matches = head.findAll(text).toList()
        assertTrue(
            "前置条件：本文件应至少有 2 处 `catch (… : InterruptedException)`（实际 ${matches.size}）—— " +
                "少了说明有的 catch 被删/改写成别的形态，本条不再覆盖应有的面",
            matches.size >= 2
        )
        val bad = ArrayList<String>()
        for (m in matches) {
            val open = m.range.last // '{' 的位置
            val body = braceBody(text, open)
                ?: throw AssertionError("catch 块括号不配对（源码被改坏了？）@${m.range.first}")
            if (!body.contains("Thread.currentThread().interrupt()")) {
                val where = text.substring(0, m.range.first).count { it == '\n' } + 1
                bad.add("第 $where 行附近的 catch 体：${body.trim().take(120)}")
            }
        }
        assertEquals(
            "★ C-2：每个 `catch (… : InterruptedException)` 都必须 Thread.currentThread().interrupt()" +
                "（同文件 :62 的 eh-01 规范）；未恢复的有 ${bad.size} 处：\n" +
                bad.joinToString("\n"),
            0, bad.size
        )
    }

    /**
     * C-2 的**规范范本**仍在：顶层 catch 用的 `if (e is InterruptedException)` 形式必须继续恢复标志。
     *
     * 补充 [everyInterruptedExceptionCatchRestoresInterruptFlag] —— 后者的正则只认
     * `catch (x: InterruptedException)`，认不出 `if (e is InterruptedException)`。两条合起来才覆盖
     * 本文件里"被中断"的全部处置点（范本 + 看门狗线程 + 强杀线程）。也正因为有这个范本在先，
     * C-2 才是"规范一致性"问题而不是"新发现"。
     */
    @Test
    fun interruptNormExistsInTopLevelCatch() {
        val fn = sliceBetween(src.readText(), "fun reportRunFailure(", "return -1")
        assertTrue(
            "★ C-2 范本：reportRunFailure 里的 `if (e is InterruptedException)` 恢复标志必须仍在" +
                "（这是另两处要对齐的规范；删了它 C-2 就失去参照）",
            fn.contains("if (e is InterruptedException) Thread.currentThread().interrupt()")
        )
    }

    /** C-2 定点 1：看门狗线程（`dsh-proot-watchdog`）的 catch 必须恢复标志。 */    @Test
    fun watchdogThreadCatchRestoresInterruptFlag() {
        val body = sliceBetween(
            src.readText(),
            "\"[proot] timeout after",
            "name = \"dsh-proot-watchdog\""
        )
        assertTrue("看门狗线程里应有一个 InterruptedException 的 catch", body.contains("InterruptedException"))
        assertTrue(
            "★ C-2：看门狗线程吞掉 InterruptedException 后必须恢复中断标志",
            body.contains("Thread.currentThread().interrupt()")
        )
    }

    /** C-2 定点 2：强杀线程（`dsh-proot-kill`）的 catch 必须恢复标志。 */
    @Test
    fun killThreadCatchRestoresInterruptFlag() {
        val body = sliceBetween(
            src.readText(),
            "ProotTermination.TERMINATION_GRACE_MS",
            "name = \"dsh-proot-kill\""
        )
        assertTrue("强杀线程里应有一个 InterruptedException 的 catch", body.contains("InterruptedException"))
        assertTrue(
            "★ C-2：强杀线程吞掉 InterruptedException 后必须恢复中断标志",
            body.contains("Thread.currentThread().interrupt()")
        )
    }

    /** 反面：全文件不许再有**空体**的 `catch (… : InterruptedException) { }`。 */
    @Test
    fun noEmptyInterruptedExceptionCatch() {
        val text = src.readText()
        val empty = Regex("""catch\s*\(\s*\w*\s*:\s*InterruptedException\s*\)\s*\{\s*\}""")
        assertFalse(
            "★ C-2：不许有空体的 InterruptedException catch（它会悄悄吃掉取消信号，" +
                "让后来人以为「不恢复标志是允许的」）",
            empty.containsMatchIn(text)
        )
    }

    // ======================================================================
    // 夹具 / 辅助
    // ======================================================================

    /** 全局日志的当前行（去掉时间戳前缀不做，只按行数取新增部分）。 */
    private fun lines(): List<String> = DshLogBus.export().lines()

    /** 从 `{` 的下标开始做花括号配对，返回**体内**文本；不配对返回 null。 */
    private fun braceBody(text: String, openBraceIndex: Int): String? {
        var depth = 0
        for (i in openBraceIndex until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(openBraceIndex + 1, i)
                }
            }
        }
        return null
    }

    /**
     * `java.lang.Process` 的假子类：只覆写 `Handle.pid()` 会走到的成员。
     *
     * @param hostile true = 所有属性访问都抛（模拟"反射不可用 + /proc 读不到"的极端情形，
     *                用来验证 pid() 仍折成 -1 而不是把异常扔给启动路径）。
     */
    private class FakeProcess(private val hostile: Boolean = false) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = if (hostile) throw SecurityException("hostile") else 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            if (hostile) throw SecurityException("hostile") else true
        override fun exitValue(): Int = if (hostile) throw SecurityException("hostile") else 0
        override fun destroy() = if (hostile) throw SecurityException("hostile") else Unit
        override fun isAlive(): Boolean = if (hostile) throw SecurityException("hostile") else false
        override fun destroyForcibly(): Process = if (hostile) throw SecurityException("hostile") else this
    }

    /** 口径同 `R49SilentFailureTest.mainSourceFile`：从运行目录向上找主源码。 */
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
        throw AssertionError("找不到 com/dsh/$rel（请在仓库根或 FCL/ 目录下运行测试）")
    }

    /** 取 `[start, end)` 切片；端点找不到直接失败（口径同 R31ProotExecutorTest.sliceBetween）。 */
    private fun sliceBetween(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("切片起点未命中（函数/注释被改了？）：$start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        assertTrue("切片终点未命中：$end", to > from)
        return text.substring(from, to)
    }
}
