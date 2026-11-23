package com.dsh

import com.dsh.core.Probe
import com.dsh.core.UrlScanner
import com.dsh.core.autoPortCandidateIsLive
import com.dsh.core.webUiPortAllowed
import com.dsh.core.webUiUrlAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R49 / W2-H：`DshRuntime.kt` 的两条定点修复（审计 S-04 / P2-6）。
 *
 * 运行：`sh tools/run-tests.sh`，或 `./gradlew :FCL:testDebugUnitTest --tests "com.dsh.R49RuntimePortFixTest"`。
 *
 * ## 能测什么 / 不能测什么（先说实话）
 * **能测**：
 * 1. [autoPortCandidateIsLive] —— 纯函数（端口探测做成参数注入），端口档与"没人监听"两档可穷举。
 * 2. `webUiUrlAllowed(url, expectedPort)` 在两种基准下的差别（H-1 的**判据本体**）——
 *    这正是 "expectedPort=0 时端口维度弃权" 的直接体现。
 * 3. 三语资源 `dsh_reason_stop_incomplete` 的**存在性与非空**，以及"代码里不再有内联英文"
 *    这条源码级断言（H-2）。
 *
 * **不能测**：`onProcessLine` 里"从 `READY port=` 取锚点"这条**接线**是私有方法 + 依赖 Android
 * Context，JVM 上跑不到。它只能靠**源码结构断言**（见 [processLineFeedsScriptPortIntoTheGate]），
 * 而源码断言只看形状不看语义 —— 所以报告里附了变异验证记录（把锚点来源撤掉，测试变红）。
 */

class R49RuntimePortFixTest {

    // =====================================================================
    // H-1 · 判据本体：expectedPort 的两种取值给出不同结论
    // =====================================================================

    /**
     * **缺陷本体（S-04）**：同一个"端口不对的回环 URL"，在 `expectedPort = 0` 时被放行、
     * 在 `expectedPort = 真实端口` 时被拒。
     *
     * 这条不改生产代码就**必红**吗？不 —— 它描述的是**现状与修复后的差异**：修复前
     * `onProcessLine` 永远传 `launchPort`（自动端口档是 0），于是那一档恒走"放行"。
     * 本用例钉的是"两个基准必须给出不同结论"这个事实 —— 它是所有下游接线的语义前提：
     * 若哪天 `webUiPortAllowed` 在 `expectedPort <= 0` 改判 false，这条会红，
     * 提醒重读 [com.dsh.core.autoPortCandidateIsLive] 的存在理由（自动端口实例会起不来）。
     */
    @Test
    fun zeroBaselineAbstainsButRealBaselineRejects() {
        val hostile = "http://127.0.0.1:40404/?token=" + "B".repeat(43)
        // 自动端口档：端口基准不可知 ⇒ 端口这一维弃权（这是既定语义，不是回归）
        assertTrue("expectedPort=0 必须弃权（否则自动端口实例永远拿不到 URL）",
            webUiUrlAllowed(hostile, 0))
        // 真实端口档：同一条 URL 必须被拒 —— 这就是"补上端口维度"的实际收益
        assertFalse("expectedPort=3080 时 40404 必须被拒", webUiUrlAllowed(hostile, 3080))
        // 反向锚：端口对上了就必须放行，否则门做成了功能回归
        assertTrue("端口匹配必须放行",
            webUiUrlAllowed("http://127.0.0.1:3080/?token=x", 3080))
    }

    /**
     * 端口半边单独钉：`webUiPortAllowed` 的极性与 `adoptionPortMatches` **相反**是**故意**的。
     *
     * 为什么单列：两条判据名字只差一个词，将来"统一口径"是极自然的误改
     * （`adoptionPortMatches` 在 `<=0` 返回 false，它判的是**已知端口**的候选）。
     */
    @Test
    fun portHalfAbstainsOnZeroButCandidateHalfRejects() {
        assertTrue("webUiPortAllowed 在 <=0 必须弃权", webUiPortAllowed("http://127.0.0.1:40404/", 0))
        assertFalse("adoptionPortMatches 在 <=0 必须拒（极性相反，故意）",
            com.dsh.core.adoptionPortMatches("http://127.0.0.1:40404/", 0))
    }

    // =====================================================================
    // H-1 · 新判据：autoPortCandidateIsLive（端口基准不可知时的退让门）
    // =====================================================================

    /**
     * **缺陷本体的补丁**：端口基准不可知（自动端口档、脚本 READY 行还没到）时，
     * 候选 URL 的端口必须**真的在监听**才采信。
     *
     * 修复前的形态：这一档只判 scheme + host，于是
     * `http://127.0.0.1:40404/?token=…`（40404 没人监听）照样被写进 `state.url`。
     */
    @Test
    fun liveProbeRejectsPortNobodyListensOn() {
        val hostile = "http://127.0.0.1:40404/?token=" + "B".repeat(43)
        // 桩：40404 没人监听 ⇒ 必须拒（这条在修复前是"放行"，因为那时根本没有这道门）
        assertFalse("端口无人监听必须拒",
            autoPortCandidateIsLive(hostile) { p -> p != 40404 })
        // 桩：40404 有人在监听 ⇒ 放行（自动端口实例必须能起来）
        assertTrue("端口在监听必须放行（否则自动端口实例起不来）",
            autoPortCandidateIsLive(hostile) { p -> p == 40404 })
    }

    /**
     * 边界：端口解析不出来的候选**不能**因为"没端口可探"而被放行（取严）。
     *
     * 为什么重要：`portOf` 解析失败时若返回 true，等于给"裸 host"形态开了一个绕过口 ——
     * 而那正是 `webUiPortAllowed` 明确取严的那一档（见那里的 KDoc）。
     */
    @Test
    fun liveProbeRejectsCandidatesWithoutAPort() {
        assertFalse("空串必须拒", autoPortCandidateIsLive(""))
        assertFalse("null 必须拒", autoPortCandidateIsLive(null))
        // `http://127.0.0.1/` 没有 `:port` ⇒ portOf 返回 null ⇒ 拒
        assertFalse("无端口的裸 host 必须拒（取严，与 webUiPortAllowed 同取向）",
            autoPortCandidateIsLive("http://127.0.0.1/") { true })
        // 探测桩"恒 true"也不能救它：解析不出端口时压根不该调探测
        var probeCalls = 0
        assertFalse("无端口候选不得触发探测",
            autoPortCandidateIsLive("http://127.0.0.1/") { probeCalls++; true })
        assertEquals("解析不出端口时不应调用探测桩", 0, probeCalls)
    }

    /**
     * 与 [webUiUrlAllowed] 组合后就是 `onProcessLine` 实际用的判据
     * （`webUiUrlAllowed(it, expectedPort) && (expectedPort > 0 || autoPortCandidateIsLive(it))`）。
     *
     * 这里把组合表达式复刻一遍，钉住**两种基准下都必须挡住**同一个攻击 URL：
     * - 自动端口档（0）：靠探活挡住；
     * - 固定端口档（3080）：靠端口比对挡住。
     * 少了任何一条，`port=0` 这一档（= **默认配置**）就只剩 host + scheme 两道门。
     */
    @Test
    fun combinedGateBlocksHostileUrlInBothBaselines() {
        val hostile = "http://127.0.0.1:40404/?token=" + "B".repeat(43)
        fun gate(url: String?, expectedPort: Int, live: (Int) -> Boolean) =
            webUiUrlAllowed(url, expectedPort) && (expectedPort > 0 || autoPortCandidateIsLive(url, live))

        assertFalse("自动端口档 + 无人监听 ⇒ 必须拒",
            gate(hostile, 0) { p -> p != 40404 })
        assertFalse("固定端口档 ⇒ 必须拒（端口不匹配）",
            gate(hostile, 3080) { _ -> true })
        assertTrue("自动端口档 + 真在监听 ⇒ 必须放行",
            gate(hostile, 0) { p -> p == 40404 })
        assertTrue("固定端口档 + 端口匹配 ⇒ 必须放行",
            gate("http://127.0.0.1:3080/?token=x", 3080) { _ -> false })
    }

    /**
     * **不许踩的回退**：`expectedPort <= 0` 时**不能**直接拒（那会把安全门做成功能回归）。
     *
     * 依据是 `webUiUrlAllowed` 的既有 KDoc：自动端口实例会拿不到任何 URL，state 卡在 Starting
     * 直到 180s 看门狗判超时。这里用一个"真在监听"的合法候选钉住该路径仍能通过。
     */
    @Test
    fun autoPortRunStillGetsItsUrlWhenPortIsReallyListening() {
        val realUrl = "http://127.0.0.1:46501/?token=" + "A".repeat(43)
        assertTrue("自动端口实例的真实 URL（端口在监听）必须能被采信",
            webUiUrlAllowed(realUrl, 0) && autoPortCandidateIsLive(realUrl) { p -> p == 46501 })
        assertEquals("URL 的端口必须解析得出（否则整条路径的前提不成立）",
            46501, UrlScanner.portOf(realUrl))
    }

    /**
     * 注入的探测桩**只**被问到候选自己的端口，不漏探、不乱探。
     *
     * 为什么在乎：`autoPortCandidateIsLive` 每行输出都会跑（`onProcessLine` 的候选过滤），
     * 一次真实探测是回环 connect（~10ms 级）。若它对每个候选都探或探错端口，会把
     * stdout 密集的启动阶段拖慢，而且掩盖"探的是哪个端口"这一事实。
     */
    @Test
    fun liveProbeAsksOnlyAboutTheCandidatesOwnPort() {
        val probes = mutableListOf<Int>()
        autoPortCandidateIsLive("http://127.0.0.1:46501/?token=x") { p ->
            probes += p
            true
        }
        assertEquals("必须且只探候选自己的端口", listOf(46501), probes)
    }

    // =====================================================================
    // H-1 · 接线（源码级）：onProcessLine 的端口基准必须能来自脚本的 READY 行
    // =====================================================================

    /**
     * **接线断言（源码级）**：`onProcessLine` 的端口基准必须"自动端口档时改用脚本自述端口"，
     * 且该基准必须来自**脚本那一行**（`UrlScanner.findMarkerPort`）+ 探活确认。
     *
     * 判别力：把这几行删掉（或把 `verifiedScriptPort` 恒置 0），本用例立刻变红 —— 这正是
     * 修复前的形态（`expectedPort` 恒等于 `launchPort`，自动端口档 = 0 ⇒ 整维弃权）。
     * ⚠️ 只看形状，不看语义；"探测确实发生在写入之前"这类顺序由下面的断言分别钉。
     */
    @Test
    fun processLineFeedsScriptPortIntoTheGate() {
        val body = onProcessLineBody()
        assertTrue(
            "onProcessLine 必须把 verifiedScriptPort 作为自动端口档的基准。方法体：\n$body",
            body.contains("verifiedScriptPort")
        )
        assertTrue(
            "端口来源必须是脚本的 READY port= 行（UrlScanner.findMarkerPort）",
            body.contains("UrlScanner.findMarkerPort(line)")
        )
        assertTrue(
            "锚点必须经探活确认（Probe.isPortOpen）才写入 —— 这一行和 dsh 的 URL 走同一条输出流，" +
                "端口真在监听是这一层唯一的独立性证据",
            body.contains("Probe.isPortOpen(")
        )
        // 门本身仍必须是双参版（R34 的回归锚，别被本次改动绕过）
        assertTrue(
            "候选门必须仍是双参数版 webUiUrlAllowed(it, expectedPort)",
            body.contains("webUiUrlAllowed(it, expectedPort)")
        )
        // 自动端口档的退让门也必须在场
        assertTrue(
            "自动端口档必须再走 autoPortCandidateIsLive（否则伪造行抢在真 READY 行之前就到时，" +
                "端口维度照样弃权）",
            body.contains("autoPortCandidateIsLive(it)")
        )
    }

    /**
     * 顺序：锚点写入必须发生在**世代校验之后**（旧 run 的迟到输出不许改新 run 的基准），
     * 且必须在候选门之前（否则当行用不上新锚点）。
     *
     * 这两条是"同一事实的两次独立判断"，都必须成立：
     * - 放在世代校验之前 ⇒ `stale run` 能把新 run 的端口基准改成任意值（`nodePid` 那条纪律的反例）；
     * - 放在候选门之后 ⇒ 第一行 READY 只能走"弃权 + 探活"退让门，等于白补一个来源。
     */
    @Test
    fun scriptPortIsWrittenAfterGenerationCheckAndBeforeTheGate() {
        val body = onProcessLineBody()
        val genIdx = body.indexOf("if (gen != runGen)")
        val writeIdx = body.indexOf("verifiedScriptPort = scriptPortThisLine")
        val gateIdx = body.indexOf("val url = candidates.firstOrNull")
        assertTrue("前置：世代校验必须在（BL-038）", genIdx >= 0)
        assertTrue("前置：锚点持久化必须在（见 processLineFeedsScriptPortIntoTheGate）", writeIdx >= 0)
        assertTrue("前置：候选门必须在", gateIdx >= 0)
        assertTrue("锚点**持久化**必须在世代校验**之后**（旧 run 不许改新 run 的基准）", writeIdx > genIdx)
        // 本行自带的端口必须在**门之前**就被算出来（否则"只打 port 不打 url"的退化就绪行用不上它）
        val perLineIdx = body.indexOf("val scriptPortThisLine")
        assertTrue("本行端口必须在门之前算出", perLineIdx >= 0 && perLineIdx < gateIdx)
        // 探测必须在门之前就发生？（不必须 —— 但本行端口必须在门之前可用，见上一条）
        assertTrue("本行端口必须参与基准选择（expectedPort 的 when 里）",
            body.contains("scriptPortThisLine != null -> scriptPortThisLine"))
    }

    /**
     * **不许写成"恒 Accept"**：锚点写入必须被 `expectedPort <= 0` 与探活两道条件同时守住。
     *
     * 为什么单列一条：这两道是这条修复仅有的两处把关。如果哪天被简写成
     * "`verifiedScriptPort = reported`"（无条件下），固定端口档的基准（由宿主预检唯一决定、
     * 比任何回读值都可信）就会被脚本输出覆盖 —— 端口门当场失效。
     */
    @Test
    fun scriptPortIsAcceptedOnlyInAutoPortRunsAndOnlyAfterProbe() {
        val body = onProcessLineBody()
        // 采信条件必须同时含两道：自动端口档 + 探活通过
        val perLine = body.substringAfter("val scriptPortThisLine =")
            .substringBefore("val expectedPort")
        assertEquals("采信条件找不到（`val scriptPortThisLine = …` 与 `val expectedPort` 之间的片段）",
            true, perLine.isNotEmpty())
        assertTrue(
            "采信条件里必须有 launchBaselinePort <= 0（固定端口档的基准不许被回读值覆盖）。实际：\n$perLine",
            perLine.contains("launchBaselinePort <= 0")
        )
        assertTrue(
            "采信条件里必须有 Probe.isPortOpen（未探活的一律不采信）。实际：\n$perLine",
            perLine.contains("Probe.isPortOpen(")
        )
        // 持久化那一段也必须挂着本行算出的值（不是重新解析一遍）
        assertTrue(
            "持久化必须复用本行算出的 scriptPortThisLine（一行只问一次网络）",
            body.contains("if (scriptPortThisLine != null && verifiedScriptPort != scriptPortThisLine)")
        )
    }

    /**
     * 生命周期：锚点必须**每次真正 fork 新进程**时归零（与 `++runGen` 同一条纪律）。
     *
     * 为什么：`verifiedScriptPort` 是**实例级**（`DshRuntime` 是单例）状态。不清的话，
     * "停 A 起 B"之后 B 在拿到自己的 READY 行之前，端口门会拿 A 的端口当基准 ——
     * 既可能误拒 B 的真实 URL，也可能放行"端口恰好等于 A 的那个"伪造行。
     */
    @Test
    fun scriptPortIsResetWhenANewRunStarts() {
        val src = runtimeSourceText()
        // `++runGen` 是"真正 fork 新进程"的唯一发生点（见 runGen 的 KDoc）
        val genAdvance = src.indexOf("val gen = ++runGen")
        assertTrue("前置：++runGen 必须在（世代隔离的唯一发生点）", genAdvance >= 0)
        val window = src.substring(genAdvance, minOf(src.length, genAdvance + 900))
        assertTrue(
            "紧邻 ++runGen 处必须把 verifiedScriptPort 归零（否则旧 run 的端口会带进新 run）。" +
                "实际窗口：\n$window",
            window.contains("verifiedScriptPort = 0")
        )
    }

    /**
     * 对照锚：`R34PortGateTest` 按源码文本钉住 `onProcessLine(instance, gen, line, launchPort)`
     * 与 `expectedPort: Int` 形参 —— 本次改动**故意**保住这两处形状（用局部遮蔽而非改名）。
     *
     * 这条是给未来的人的：若有人"清理"掉那个遮蔽，这里会红，并指出被牺牲的回归锚是谁的。
     */
    @Test
    fun shadowedBaselineKeepsTheR34CallSiteShape() {
        val src = runtimeSourceText()
        assertTrue(
            "onLine 回调必须仍以 launchPort 作为 expectedPort 传进去（R34PortGateTest 的锚）",
            src.contains("onProcessLine(instance, gen, line, launchPort)")
        )
        val decl = src.substringAfter("private fun onProcessLine(").substringBefore(") {")
        assertTrue("onProcessLine 必须仍有 expectedPort: Int 形参（R34PortGateTest 的锚）",
            decl.contains("expectedPort: Int"))
        // 遮掩必须真的存在：自动端口档时基准要能被脚本端口替换
        val body = onProcessLineBody()
        assertTrue(
            "方法体里必须出现 `launchBaselinePort`（形参 expectedPort 的原值）",
            body.contains("val launchBaselinePort = expectedPort")
        )
        assertTrue(
            "自动端口档必须回落到 scriptPortThisLine / verifiedScriptPort（否则端口维度仍弃权）",
            body.contains("scriptPortThisLine != null -> scriptPortThisLine") &&
                body.contains("else -> verifiedScriptPort")
        )
    }

    // =====================================================================
    // H-2 · 停止失败文案：三语资源 + 不再内联英文
    // =====================================================================

    /**
     * **H-2 主用例**：`dsh_reason_stop_incomplete` 三语齐全、非空、且各自是**本语言**。
     *
     * 修复前：这句是 `val reason = "Stop incomplete: the dsh process may still be running…"`
     * 内联英文字面量（原 `:1857` 附近，注释自己写着"⚠️ 待补：`dsh_reason_stop_incomplete`（三语）"），
     * 中/繁界面下照样显示英文。
     */
    @Test
    fun stopIncompleteCopyExistsInAllThreeLocales() {
        val locales = listOf("values/strings.xml", "values-zh/strings.xml", "values-zh-rTW/strings.xml")
        val bodies = locales.map { rel ->
            val m = parseStrings(requireRes(rel))
            val body = m["dsh_reason_stop_incomplete"]
                ?: throw AssertionError("$rel 缺 dsh_reason_stop_incomplete（该语种会回落英文）")
            assertTrue("$rel 的 dsh_reason_stop_incomplete 是空的", body.isNotBlank())
            rel to body
        }.toMap()

        // 中文两语必须真的翻成中文（否则"加了三语资源"只是形式）
        assertTrue(
            "values-zh 的文案仍是英文：${bodies["values-zh/strings.xml"]}",
            bodies.getValue("values-zh/strings.xml").contains("停止")
        )
        assertTrue(
            "values-zh-rTW 的文案仍是英文：${bodies["values-zh-rTW/strings.xml"]}",
            bodies.getValue("values-zh-rTW/strings.xml").contains("停止")
        )
        // 简体与繁体不能是同一句（本项目 zh 与 zh-rTW 逐条分开维护）
        assertTrue(
            "简体与繁体必须是两条不同的文案（不能拿简体顶替繁体）",
            bodies.getValue("values-zh/strings.xml") != bodies.getValue("values-zh-rTW/strings.xml")
        )
        // 英文那条必须仍是可读英文（不能被误改成中文去"凑三语"）
        assertTrue(
            "values 的文案必须仍是英文：${bodies["values/strings.xml"]}",
            bodies.getValue("values/strings.xml").contains("Stop incomplete")
        )
    }

    /**
     * `dsh_*` 带 `%` 的文案必须登记进 `DshResourceFormatTest.callSites`（那是**别的 agent** 的文件）。
     *
     * 所以这条资源**故意不带占位符** —— pid 由代码拼成语言中立的 `(pid=N)` 后缀。
     * 若有人为了"顺手"把 pid 写进资源，`everyDshStringWithPlaceholdersIsDeclared` 会当场变红，
     * 而修复它就得改别人的文件。这条断言把那个越界挡在前面。
     */
    @Test
    fun stopIncompleteCopyHasNoPlaceholdersOnPurpose() {
        val m = parseStrings(requireRes("values/strings.xml"))
        val body = m["dsh_reason_stop_incomplete"]
            ?: throw AssertionError("values/strings.xml 缺 dsh_reason_stop_incomplete")
        assertFalse(
            "该文案不许带占位符（带 %d/%s 就必须登记进 DshResourceFormatTest 的 callSites，" +
                "那是另一个 agent 的文件）。实际：$body",
            body.contains('%')
        )
    }

    /**
     * **接线（源码级）**：原来的内联英文不许再出现在 `DshRuntime.kt` 里，改为取资源。
     *
     * 判别力：把 `val reason = "$stopIncompleteReason (pid=$prootPid)"` 改回内联英文 ⇒ 本用例变红。
     */
    @Test
    fun stopIncompleteReasonUsesTheResourceNotAnInlineEnglishLiteral() {
        val src = runtimeSourceText()
        assertFalse(
            "DshRuntime.kt 里不许再有那句内联英文（H-2 的缺陷本体）",
            src.contains("Stop incomplete: the dsh process may still be running (pid=")
        )
        assertTrue(
            "停止未完成的 reason 必须取 R.string.dsh_reason_stop_incomplete",
            src.contains("R.string.dsh_reason_stop_incomplete")
        )
        // pid 仍必须带上（失败分支的排障线索，R34StopAndTailTest 也钉着这一点）
        assertTrue(
            "reason 必须仍带 pid（排障时唯一定位残留进程的线索）",
            src.contains("\"\$stopIncompleteReason (pid=\$prootPid)\"")
        )
    }

    /**
     * 取词必须在**锁外**：`stopIncompleteReason` 的定义要出现在
     * `val weOwnedTheStop: Boolean? = synchronized(this@DshRuntime)` **之前**。
     *
     * 为什么：那个临界区与 `startLocked` / `stop` 共用同一把对象监视器，纪律是
     * "监视器里只做状态与句柄的配对写"（同段既有注释）。取词走 `LocaleUtils.setLanguage` →
     * `createConfigurationContext`（解析一份资源表），不该被拖进去。
     */
    @Test
    fun stopIncompleteCopyIsResolvedOutsideTheMonitor() {
        val src = runtimeSourceText()
        val defIdx = src.indexOf("val stopIncompleteReason = DshUiText.getString(")
        val lockIdx = src.indexOf("val weOwnedTheStop: Boolean? = synchronized(this@DshRuntime) {")
        assertTrue("前置：stopIncompleteReason 的定义必须在", defIdx >= 0)
        assertTrue("前置：weOwnedTheStop 的临界区必须在", lockIdx >= 0)
        assertTrue("取词必须在临界区**之前**（锁内只做配对写）", defIdx < lockIdx)
        // 而且不许在锁内再取一次（那就是把 IO 拖进监视器）
        val lockBody = src.substring(lockIdx, minOf(src.length, lockIdx + 4000))
        assertFalse("临界区内不许再出现取词", lockBody.contains("DshUiText.getString("))
    }

    /**
     * 取词必须走 [com.dsh.core.DshUiText]（跟随用户在启动器里选的语言），
     * **不是** `appContext.getString`（只跟随系统语言）。
     *
     * 这正是 `appendLastLinesHint`（R33/B1）踩过并修好的那一条 —— 同一类病，同一个药。
     */
    @Test
    fun stopIncompleteCopyResolvesWithDshUiTextNotAppContext() {
        val src = runtimeSourceText()
        val defIdx = src.indexOf("val stopIncompleteReason = DshUiText.getString(")
        assertTrue("必须用 DshUiText.getString 取词（跟随用户选的界面语言）", defIdx >= 0)
        val window = src.substring(defIdx, minOf(src.length, defIdx + 400))
        assertFalse(
            "这一句不许退回 appContext.getString（只跟随系统语言，正是本缺陷的病灶）",
            window.contains("appContext?.getString")
        )
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private fun runtimeSourceText(): String = resolveMainSource("core/DshRuntime.kt")
        ?: throw AssertionError(
            "找不到 core/DshRuntime.kt —— 请在仓库根或 FCL/ 目录下运行测试"
        )

    private fun onProcessLineBody(): String = methodBody("private fun onProcessLine(")

    private fun methodBody(signature: String): String {
        val text = runtimeSourceText()
        val start = text.indexOf(signature)
        if (start < 0) throw AssertionError("找不到 `$signature` —— 方法被改名或删了？")
        val braceStart = text.indexOf('{', start)
        if (braceStart < 0) throw AssertionError("`$signature` 没有方法体？")
        var depth = 0
        var i = braceStart
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(braceStart, i + 1)
                }
            }
            i++
        }
        throw AssertionError("`$signature` 的花括号不配对")
    }

    /** 读资源文件：优先 `dsh.res` 系统属性，其次从运行目录向上找（兼容三种仓库布局）。 */
    private fun requireRes(rel: String): File =
        resolveRes(rel) ?: throw AssertionError("找不到 res/$rel —— 请在仓库根或 FCL/ 目录下运行测试")

    private fun resolveRes(rel: String): File? {
        System.getProperty("dsh.res")?.let { base ->
            val f = File(base, rel)
            if (f.isFile) return f
        }
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            val candidates = listOf(
                File(dir, "FCL/FCL/src/main/res/$rel"),
                File(dir, "FCL/src/main/res/$rel"),
                File(dir, "src/main/res/$rel")
            )
            candidates.firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    private fun resolveMainSource(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            val candidates = listOf(
                File(dir, "FCL/FCL/src/main/java/com/dsh/$rel"),
                File(dir, "FCL/src/main/java/com/dsh/$rel"),
                File(dir, "src/main/java/com/dsh/$rel")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    private companion object {
        // 用普通字符串而不是原始字符串：模式里有 `"""`，原始字符串写法容易少一个引号
        // （本轮初版就是这么炸的，报错还是级联的、指不到真凶 —— 见报告里那条编译诊断记录）。
        val STRING_RE = Regex(
            "<string name=\"([^\"]+)\"[^>]*>(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL
        )

        fun parseStrings(file: File): Map<String, String> =
            STRING_RE.findAll(file.readText()).associate { m ->
                m.groupValues[1] to m.groupValues[2].replace("\\'", "'")
            }
    }
}
