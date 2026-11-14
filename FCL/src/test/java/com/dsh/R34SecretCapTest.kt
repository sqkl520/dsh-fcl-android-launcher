package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.scriptFailureReason
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * round34 / [DshLogBus.registerSecret] 的两个缺陷。
 *
 * 来源：`D:\tmp\redteam34\` 的 `SecretProbe2` / `PortAttr2` / `RegisterCost` 三支探针
 * （本轮已在本机重跑确认，数据抄在下面各用例的注释里）。
 *
 * ## 缺陷 1：`registeredSecrets` 无上限 —— 永久 CPU 税
 * `sanitize` 对**每个**已注册串跑一次 `out.replace(t, "***")`，而集合本身无上限。
 * rootfs 往可写的 `/tmp` 灌 5 万行 `x?token=Q0001…` 就能让它无限增长
 * （`MAX_LINES` 裁的是日志，从不裁 secret 集合）。
 *
 * ## 缺陷 2：假 token 把协约行打成马赛克，端口归因静默失效
 * 注册 `port-in-use` 后 `[start-dsh] FAILED reason=port-in-use` → `reason=***`，
 * [scriptFailureReason] 返回 null —— R33 刚修好的端口归因被一行 11 字符的伪造输出废掉。
 *
 * ## 断言的取向
 * 钉**行为与不变式**（条数有上界、已注册的真 token 永不变回明文、协约行永不被 secret 打码），
 * 不钉具体常数（16 / 256 都是可调的产品决策）。凡是"上界"类的断言都从
 * [DshLogBus.registeredSecretCountForTest] 观测**实测值**，而不是把 16 抄进断言 ——
 * 这样调整上限不会误红，而"上限被删掉"必然红。
 */
class R34SecretCapTest {

    /**
     * 每条用例前复位。
     *
     * 必须有：`registeredSecrets` 是**进程级**的，harness 把全部测试类跑在同一个 JVM 里。
     * 上一个用例注册的串若残留，会让本用例的计数断言拿到偏大的起点而**假红**；
     * 反之容量门已满会让"真 token 仍被脱敏"那条**假绿**（注册根本没成功）。
     */
    @Before
    fun resetSecrets() {
        DshLogBus.clearForTestOfRegisteredSecrets()
    }

    @After
    fun cleanup() {
        DshLogBus.clearForTestOfRegisteredSecrets()
    }

    /** 造一个合法的 43 字符 base64url 形状 token（与 dsh 真实 token 同形状同长度）。 */
    private fun token(seed: Int): String = "tok" + seed.toString().padStart(40, '0')

    // ==================================================== 缺陷 1：条数上限

    /**
     * **缺陷本体**：灌 5 万条伪造 secret 之后，注册集合**不得**无限增长。
     *
     * 红队实测（修复前）：`registeredSecrets final size = 100000`，`sanitize` 3.429 ms/行。
     *
     * 判别力：把 [DshLogBus.registerSecret] 里的容量门删掉，`count` 会变成 50000，本条立刻红。
     *
     * 断言用"实测上界 + 远小于灌注量"两个口径，而不是硬编码 16：
     * - `count < 500`：离 16 有 30 倍余量（调上限不会误红），但离 50000 差两个数量级；
     * - `count <= 灌注量 / 100`：同样的意思，用相对量表达"与攻击者写入量无关"。
     */
    @Test
    fun forgedSecretFloodMustNotGrowTheRegistryUnbounded() {
        val flood = 50_000
        for (i in 0 until flood) DshLogBus.registerSecret(token(i))

        val count = DshLogBus.registeredSecretCountForTest()
        assertTrue(
            "灌 $flood 条伪造 secret 之后集合仍是 $count 条 —— 必须有硬上界（修复前是 $flood 条）。\n" +
                "无上界的后果是 sanitize 永久变慢：红队实测 10 万条时 3.429 ms/行，即整个日志热路径被拖垮。",
            count < 500
        )
        assertTrue(
            "集合大小 $count 应与灌注量（$flood）无关，实际却占了 ${count * 100 / flood}%",
            count <= flood / 100
        )
    }

    /**
     * **淘汰策略的安全性**：容量满了之后继续注册，**已登记的 token 必须仍然被打码**。
     *
     * 这是"拒绝新增 vs LRU 淘汰"的分水岭。若实现改成 LRU 淘汰，先注册的真 token 会被后到的
     * 伪造串挤出去，此后含它的行**变回明文** —— 那等于攻击者灌满额度就能撤销脱敏。
     *
     * 判别力：把容量门换成 `if (size >= MAX) remove(first); add(new)`，本条立刻红。
     */
    @Test
    fun realTokenSurvivesFloodAndStaysMasked() {
        val real = "et5w4-xSCJpP95w98yyXZOJOxkn-EOz1JAyS8T6eKqU"
        DshLogBus.registerSecret(real)
        assertTrue("前置条件：真 token 应当注册成功", DshLogBus.isSecretRegisteredForTest(real))

        // 灌满并远超容量
        for (i in 0 until 1000) DshLogBus.registerSecret(token(i))

        val line = "dsh web: token value is $real (leaked in a non-URL position)"
        val out = DshLogBus.sanitize(line)
        assertFalse(
            "容量满了以后真 token 变回了明文：$out\n" +
                "（淘汰策略 = 撤销脱敏：攻击者灌满额度即可让真 token 重新暴露，这比'少脱敏几条假串'严重得多）",
            out.contains(real)
        )
    }

    /** 同一 token 重复注册（真实路径：迟到的 URL 每刷新一次就注册一次）不得占满额度。 */
    @Test
    fun repeatedRegistrationOfSameTokenIsIdempotent() {
        val real = "et5w4-xSCJpP95w98yyXZOJOxkn-EOz1JAyS8T6eKqU"
        repeat(200) { DshLogBus.registerSecret(real) }
        assertEquals("同一 token 重复注册 200 次后集合里只应有 1 条", 1, DshLogBus.registeredSecretCountForTest())
        assertFalse(
            "重复注册后真 token 仍必须打码",
            DshLogBus.sanitize("x $real y").contains(real)
        )
    }

    /**
     * 单条 secret 的长度也必须封顶。
     *
     * 红队实测（修复前）：`SecretProbe2` G 段注册一条 **20 万字符**的串成功 ——
     * `sanitize` 的代价与 secret 长度成正比，一条输出就能让整个进程每行都变慢。
     *
     * 判别力：删掉 `MAX_SECRET_LENGTH` 那道门，本条立刻红。
     */
    @Test
    fun absurdlyLongSecretIsRejected() {
        val huge = "a".repeat(200_000)
        DshLogBus.registerSecret(huge)
        assertEquals("20 万字符的串不该被当作 secret 收下", 0, DshLogBus.registeredSecretCountForTest())
        // 反向守卫：正常的 43 字符 token 不受影响
        val real = "et5w4-xSCJpP95w98yyXZOJOxkn-EOz1JAyS8T6eKqU"
        DshLogBus.registerSecret(real)
        assertTrue("正常长度的 token 必须仍能注册", DshLogBus.isSecretRegisteredForTest(real))
    }

    /**
     * **性能回归**：注册集合被灌满之后，`sanitize` 的单行耗时必须有界。
     *
     * 这条是"上限真的转化成了性能保证"的端到端证据 —— 只断言"条数有界"是不充分的：
     * 集合有界但上限定得过大同样会慢。
     *
     * 口径（与红队 `SecretProbe2` H 段一致，便于直接对比）：受害者行是一条 ~70 字符的普通
     * npm 日志；灌 2 万条伪造 secret（远超容量），warm 50 次、计时 2000 次取均值。
     *
     * **阈值 0.1 ms 的来历**（本机 JDK 17 实测，同一台机器同一支测试）：
     * - 上限生效时：16 条 → 0.0057 ms/行；0 条 → 0.0069 ms/行（差异在噪声内）；
     * - 把容量门设成恒 false（`if (false && size >= MAX)`）后：2 万条 → **0.5611 ms/行**。
     *
     * 0.1 距修复后约 **17 倍**、距缺陷版约 **5.6 倍** —— 两边都有余量，且这条确实会红
     * （变异测试已验，见 `docs/reports/round34/FIX-SECRETS.md`）。
     */
    @Test
    fun sanitizeCostStaysBoundedAfterFlood() {
        for (i in 0 until 20_000) DshLogBus.registerSecret(token(i))
        val victim = "npm WARN deprecated fsevents@1.2.13: The v1 API is deprecated."

        repeat(50) { DshLogBus.sanitize(victim) } // warm：JIT + COW 快照
        val t0 = System.nanoTime()
        val iters = 2000
        repeat(iters) { DshLogBus.sanitize(victim) }
        val msPerLine = (System.nanoTime() - t0) / 1e6 / iters

        assertTrue(
            "灌 2 万条伪造 secret 后 sanitize 是 $msPerLine ms/行 —— 热路径被拖慢了。\n" +
                "本机基线：修复后 0.0057 ms/行；缺陷版 0.5611 ms/行" +
                "（红队另一口径：10 万条 3.429 ms/行 ≈ 171% 单核）。",
            msPerLine < 0.1
        )
        // 同一条用例里钉住"变慢的根因是集合变大了"，让失败时的诊断更直接
        assertTrue(
            "灌 2 万条后集合仍是 ${DshLogBus.registeredSecretCountForTest()} 条 —— 上限没生效？",
            DshLogBus.registeredSecretCountForTest() < 500
        )
    }

    /**
     * **诊断不出声**：拒绝注册时不得往日志总线写任何东西。
     *
     * 这条防的是"修复本身变成新的放大器"：`registerSecret` 会被攻击者可控的输出以每秒上千次
     * 的频率触发，若每次拒绝都 `append` 一行，日志缓冲（2000 行）会被拒绝记录瞬间冲干净，
     * 真正的排障线索全部丢失 —— DoS 换了个形态而已。
     *
     * 判别力：在任一道门里加一句 `DshLogBus.append(...)`，本条立刻红。
     */
    @Test
    fun rejectionsAreSilentOnTheLogBus() {
        val line = "01:00:00  [runtime] sentinel line that must survive"
        DshLogBus.append(line)
        val before = DshLogBus.size()

        // 触发全部四道门：过短 / 过长 / 非 token 形状 / 协约词
        DshLogBus.registerSecret("a")
        DshLogBus.registerSecret("z".repeat(500_000))
        DshLogBus.registerSecret("has space in it")
        DshLogBus.registerSecret("port-in-use")
        // 触发容量门
        for (i in 0 until 5000) DshLogBus.registerSecret(token(i))

        assertEquals(
            "拒绝注册不得往日志总线写任何行（拒绝日志本身就是新的放大器）。" +
                "修复前 $before 行，现在 ${DshLogBus.size()} 行",
            before,
            DshLogBus.size()
        )
        assertTrue(
            "被拒绝的次数应当可观测（诊断走测试 seam，不走日志）",
            DshLogBus.rejectedSecretCountForTest() > 0
        )
    }

    // ==================================================== 缺陷 2：协约行保护

    /**
     * **缺陷本体**：注册协约词不得改写脚本的失败协约行。
     *
     * 红队实测（修复前）：
     * - 注册 `port-in-use`（11 字符，过 R33 的 8 字符门槛）→ `reason=***`、`scriptFailureReason` 返回 null；
     * - 注册 `start-dsh` → 前缀变 `[***]`、同样返回 null。
     *
     * 判别力：删掉 `protectedLogVocabulary` 那道门，本条立刻红。
     */
    @Test
    fun contractLineSurvivesHostileSecrets() {
        val line = "[start-dsh] FAILED reason=port-in-use port=3080 code=1"
        val weapons = listOf(
            "port-in-use",   // 打掉原因码
            "start-dsh",     // 打掉前缀
            "port-in",       // 片段
            "in-use",        // 片段
            "start",         // 片段
            "node-exit",
            "ready-timeout"
        )
        for (w in weapons) {
            DshLogBus.clearForTestOfRegisteredSecrets()
            DshLogBus.registerSecret(w)
            val out = DshLogBus.sanitize(line)
            assertEquals("注册 \"$w\" 后协约行被改写成：$out", line, out)
            assertEquals(
                "脚本归因必须仍然解析出 port-in-use（注册过 \"$w\"）。" +
                    "实际=${scriptFailureReason(out)}，out=$out",
                "port-in-use",
                scriptFailureReason(out)
            )
        }
    }

    /** 端到端：`PortAttr2` 的 B1/B2 两个 case，走**真实管线**（sanitize → 去时间戳 → 解析）。 */
    @Test
    fun portInUseAttributionSurvivesAfterHostileSecretRegistration() {
        DshLogBus.registerSecret("port-in-use")
        DshLogBus.registerSecret("start-dsh")

        // 走真实入口，连时间戳一起验证（DshRuntime.recentLogTail 用 substringAfter("  ") 剥时间戳）
        DshLogBus.clear()
        DshLogBus.appendFor("inst-r34", "[start-dsh] FAILED reason=port-in-use port=3080 code=1")
        // 快照是 ~5Hz 合并发布的（[DshLogBus] 类注释），读之前必须显式刷一次
        DshLogBus.flushNow()
        val tail = DshLogBus.snapshotFor("inst-r34").value.lines
            .takeLast(25)
            .map { it.substringAfter("  ") }

        assertTrue(
            "实例日志尾必须是那条协约行（不得被 secret 打码）。实际=$tail",
            tail.any { scriptFailureReason(it) == "port-in-use" }
        )
    }

    /**
     * **反向**：真 token 仍然必须被打码 —— 别为了保住协约行把脱敏整个架空。
     *
     * 与 [contractLineSurvivesHostileSecrets] 成对：只保协约行不保脱敏 = 泄漏面重开；
     * 只保脱敏不管协约行 = 缺陷 2 原样。两条必须同时绿。
     */
    @Test
    fun realTokensStillMaskedAfterTheFix() {
        val real = "et5w4-xSCJpP95w98yyXZOJOxkn-EOz1JAyS8T6eKqU"
        DshLogBus.registerSecret(real)
        assertEquals(
            "真 token 必须仍被全文替换成 ***（协约词表不得把正常 token 一起拒掉）",
            "prefix *** suffix",
            DshLogBus.sanitize("prefix $real suffix")
        )
        // 且形状门/词表门不得误伤：它必须真的进了集合
        assertTrue("43 字符 base64url token 必须通过全部四道门", DshLogBus.isSecretRegisteredForTest(real))
    }

    /**
     * 形状门：带空格/等号/方括号的串不是 token，不收。
     *
     * 这条同时是缺陷 2 完备性论证的基础：secret 无法含 `[`/`]`/`=`/空白，
     * 所以它改不了协约行的**结构骨架**，只能落在骨架之间的"值"上 ——
     * 而值集合（前缀名 + 原因码）有限，[DshLogBus.protectedLogVocabulary] 由是得以完备。
     */
    @Test
    fun nonTokenShapedStringsAreRejected() {
        val notTokens = listOf(
            "has space in it",
            "[start-dsh]",
            "reason=port",
            "a\nb\tc",              // 换行/制表符：能制造伪行
            "a=1&token=2"
        )
        for (s in notTokens) {
            DshLogBus.registerSecret(s)
            assertFalse("非 token 形状的 \"$s\" 不该进脱敏集合", DshLogBus.isSecretRegisteredForTest(s))
        }
    }

    /** R33 的两个门槛必须原样保留（回归守卫：别在修 R34 时把它们改坏）。 */
    @Test
    fun r33ShortSecretGateStillHolds() {
        DshLogBus.registerSecret("a")
        DshLogBus.registerSecret("abc")
        DshLogBus.registerSecret("")
        val line = "01:00:00  [runtime] able to start about 3 times"
        assertEquals("R33 的短串门槛不得回退", line, DshLogBus.sanitize(line))
        assertEquals(0, DshLogBus.registeredSecretCountForTest())
    }

    /**
     * **词表完备性交叉校验**：脚本里打印的每个 `FAILED reason=<code>` 取值都必须被词表覆盖。
     *
     * 为什么要有这条：缺陷 2 的修法依赖 [DshLogBus.protectedLogVocabulary] 穷举"能出现在协约行
     * 里的值"。脚本新增一个原因码而词表没跟上时，那个码就能被 `x?token=<code>` 打掉 ——
     * 缺陷 2 以**新形态**复活，而且没人会注意到。本用例把脚本目录当**唯一事实来源**扫一遍。
     *
     * 扫的是 `FCL/src/main/assets/dsh/scripts/`（相对本模块的工作目录，Gradle 单测的 cwd 是
     * 模块根）。目录不存在时跳过而不是失败 —— 手机快照仓/只 clone 部分目录的场景下不该假红。
     */
    @Test
    fun contractVocabularyCoversEveryScriptReasonCode() {
        val scriptDir = listOf(
            File("src/main/assets/dsh/scripts"),
            File("FCL/src/main/assets/dsh/scripts")
        ).firstOrNull { it.isDirectory }
        assertNotNull(
            "找不到脚本目录（不影响功能，但会让本守卫失效）：" +
                "已试 src/main/assets/dsh/scripts 与 FCL/src/main/assets/dsh/scripts",
            scriptDir
        )
        val dir = scriptDir!!

        val reasonRe = Regex("""FAILED\s+reason=([a-z][a-z0-9-]*)""")
        val prefixes = HashSet<String>()
        val reasons = HashSet<String>()
        for (f in dir.listFiles { it -> it.isFile && it.name.endsWith(".sh") } ?: emptyArray()) {
            val text = f.readText()
            for (m in reasonRe.findAll(text)) reasons += m.groupValues[1]
            // 协约行前缀名：`[start-dsh]` / `[setup]`（行首出现的方括号标识）
            for (m in Regex("""^echo\s+"\[([a-z][a-z0-9-]*)]""", RegexOption.MULTILINE).findAll(text)) {
                prefixes += m.groupValues[1]
            }
        }
        assertTrue("脚本里应当扫到至少一个 reason=（否则本守卫是空转）", reasons.isNotEmpty())

        // 用"能不能被打掉"来验证：注册该词后，含它的协约行必须原样存活
        val missed = ArrayList<String>()
        for (word in prefixes + reasons) {
            DshLogBus.clearForTestOfRegisteredSecrets()
            DshLogBus.registerSecret(word)
            val probe = "[start-dsh] FAILED reason=port-in-use $word"
            if (DshLogBus.sanitize(probe) != probe) missed += word
        }
        assertTrue(
            "下列脚本协约词可以被注册成 secret 并打掉归因行（缺陷 2 未覆盖）：$missed\n" +
                "修法：把它们加进 DshLogBus.protectedLogVocabulary。" +
                "扫到的前缀=$prefixes 原因码=$reasons",
            missed.isEmpty()
        )
    }
}
