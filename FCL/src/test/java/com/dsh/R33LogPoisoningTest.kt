package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.looksLikeSeccompTrouble
import com.dsh.core.scriptFailureReason
import com.dsh.core.tailSaysPortInUse
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * round33 / `DshLogBus.appendFor` 无条件入库 → 伪造行可驱动控制流。
 *
 * ## 缺陷
 * `DshRuntime.onProcessLine` 的第一行是
 *
 * ```kotlin
 * DshLogBus.appendFor(instance.id, line)          // ← 世代校验之前
 * ...
 * if (gen != runGen) { ...; return }               // ← 世代校验在这里
 * ```
 *
 * 也就是说**任何**一行实例输出都会进实例日志流，不管它来自哪一代、内容是什么。
 * 而这个流会回流到两条控制流：
 *
 * 1. `recentLogTail()` → `looksLikeSeccompTrouble(code, tail)`：
 *    尾 25 行里出现 `seccomp` / `ptrace` / `bad system call` / `operation not permitted`
 *    任一子串，就判"疑似 seccomp 不兼容"并**自动带 `PROOT_NO_SECCOMP=1` 重启一次**。
 *    失败行里带 `seccomp` 这几个词，在真实排障日志里很常见（用户贴的错误、依赖的警告），
 *    不一定是攻击 —— 但一旦命中，这个实例的兜底预算就被烧掉了，真的不兼容时没有第二次机会。
 *
 * 2. `recentLogTail()` → 端口冲突归因：含 `EADDRINUSE` 或 `reason=port-in-use` 就判端口冲突，
 *    用户看到"端口被占，下次会自动换端口"—— 而真实原因可能是别的。
 *
 * ## 本文件钉什么
 * 这里**不**直接测 `onProcessLine`（它是 `DshRuntime` 的私有方法，依赖 Android Context）。
 * 它测的是**可被伪造内容污染的那一层**，即：
 * 给 `appendFor` 喂什么，`recentLogTail` 的等价物（`snapshotFor(...).lines`）就得到什么
 * —— 也就是"日志内容没有任何来源校验"这个事实本身，以及它如何影响两个判据函数。
 *
 * 判据函数 `looksLikeSeccompTrouble` 是顶层纯函数，可以直接测。
 */
class R33LogPoisoningTest {

    private val instanceId = "inst-log-poison-test"

    @Before
    fun resetBus() {
        DshLogBus.clearFor(instanceId)
    }

    /** 便捷：取该实例最近的日志行（与 `recentLogTail` 同一表达式）。 */
    private fun tail(count: Int = 25): List<String> =
        DshLogBus.snapshotFor(instanceId).value.lines.takeLast(count).map { it.substringAfter("  ") }

    /**
     * **缺陷本体（可污染性）**：任意字符串都能进实例流，不做任何来源或格式校验。
     *
     * 这条断言看起来平淡，但它是后面两条的前提：只要这一步成立，
     * 一个能写 `/tmp/dsh-start-*.log` 的进程（rootfs 内任意代码）就能往这个流里塞任意内容。
     */
    @Test
    fun anyStringCanEnterTheInstanceStream() {
        val forged = "fake seccomp failure injected from /tmp"
        DshLogBus.appendFor(instanceId, forged)
        DshLogBus.flushNow()
        val got = DshLogBus.snapshotFor(instanceId).value.lines
        assertTrue(
            "appendFor 不做内容校验，伪造行必然入流；实际=$got",
            got.any { it.contains(forged) }
        )
    }

    /**
     * **控制流影响 1**：伪造行能让 `looksLikeSeccompTrouble` 判真。
     *
     * 这一条把"日志内容"和"自动重启决策"连起来，是本缺陷的实质危害。
     * 注意：不判 `code > 128` 也行 —— 只靠文本子串就够。
     */
    @Test
    fun forgedLineTriggersSeccompHeuristic() {
        // 模拟：伪造 3 行看起来像依赖警告的内容（真实日志里完全可能自然出现）
        listOf(
            "warn: seccomp filter not applied on this kernel",
            "libfoo: operation not permitted",
            "ptrace attach failed"
        ).forEach { DshLogBus.appendFor(instanceId, it) }
        DshLogBus.flushNow()

        assertTrue(
            "伪造的 seccomp 关键词必须能被判据捕获（这是缺陷的下游，不是断言它正确）—— " +
                "本用例的作用是把危害链写清楚：日志内容 → 自动重启决策。实际 tail=${tail()}",
            looksLikeSeccompTrouble(code = 1, tail = tail())
        )
    }

    /** 反向：**没有**关键词时不得误判 —— 否则上面那条只是恒真。 */
    @Test
    fun cleanLineDoesNotTriggerSeccompHeuristic() {
        listOf(
            "dsh web: http://127.0.0.1:3080/?token=abc",
            "[start-dsh] READY port=3080"
        ).forEach { DshLogBus.appendFor(instanceId, it) }
        DshLogBus.flushNow()

        assertTrue(
            "干净输出不得被判成 seccomp 问题，否则「疑似 seccomp」这个判据没有区分度。实际 tail=${tail()}",
            !looksLikeSeccompTrouble(code = 1, tail = tail())
        )
    }

    /**
     * **控制流影响 2**：伪造 `EADDRINUSE` 能改变端口冲突归因。
     *
     * 归因表达式在 `DshRuntime` 里是内联的（`recentLogTail(...).any { ... }`），
     * 这里复现同一表达式，钉住"日志内容决定归因"这个事实。
     */
    @Test
    fun forgedAddrInUseChangesPortConflictAttribution() {
        DshLogBus.appendFor(instanceId, "Error: listen EADDRINUSE: address already in use 127.0.0.1:3080")
        DshLogBus.flushNow()

        val portInUse = tail().any {
            it.contains("reason=port-in-use") || it.contains("EADDRINUSE")
        }
        assertTrue(
            "伪造的 EADDRINUSE 会让归因判成端口冲突 —— 用户看到「端口被占」而真实原因可能完全不同。" +
                "实际 tail=${tail()}",
            portInUse
        )
    }

    /**
     * **本缺陷的边界**：世代校验之后的**状态变更**是安全的。
     *
     * `gen != runGen` 时 `onProcessLine` 会 return，所以伪造行**改不了** `state.url` /
     * `nodePid` / 端口（那是 round32 的 N3 门 + BL-038 在管）。
     * 本缺陷的净影响是"日志内容可被伪造 → 依赖日志的两个判据可被操纵"，
     * 不是"状态可被伪造"。把边界写清楚，避免把危害说大。
     *
     * 这里用**源码断言**验证那条 return 确实在状态变更之前 —— 而不是写一句同义反复的
     * `assertTrue(claim)`（那种断言永远为真，没有判别力）。
     */
    @Test
    fun generationCheckStillGuardsStateBeforeAnyMutation() {
        val src = resolveRuntimeSource()
        val body = src.substringAfter("private fun onProcessLine(")
            .substringBefore("\n    private fun ", "")
        val genIdx = body.indexOf("if (gen != runGen)")
        assertTrue("onProcessLine 里必须还有世代校验（BL-038）", genIdx >= 0)
        // 世代校验之前**不许**出现对状态字段的写入
        val before = body.substring(0, genIdx)
        listOf("_state.value =", "nodePid =", "_running.value =").forEach { mutation ->
            assertTrue(
                "世代校验之前不得出现 `$mutation` —— 否则 stale run 的输出会污染新 run 的状态。" +
                    "校验前的片段：\n$before",
                !before.contains(mutation)
            )
        }
        // 反向：appendFor 确实在校验之前（这就是本缺陷：日志无条件入流）
        assertTrue(
            "appendFor 应当在校验之前（这正是本缺陷的成因）；若它被挪到校验之后，本缺陷即已修复，" +
                "本测试需同步更新为「不得在校验之前」",
            before.contains("DshLogBus.appendFor(")
        )
    }

    /**
     * **修复后的判据**：只有脚本自己打的 `FAILED reason=port-in-use` 才算端口冲突。
     *
     * 与下面那条（裸匹配）成对：同样一组输入，新判据给出 false，旧做法给出 true。
     * 这个差异就是本次修复的实际收益。
     */
    @Test
    fun onlyScriptContractLineCountsAsPortInUse() {
        // 这些全部是"日志里自然会出现、但**不是**本次失败结论"的文本
        val noise = listOf(
            "warn: EADDRINUSE was seen last week, ignore",
            "Error: listen EADDRINUSE: address already in use 127.0.0.1:3080",   // node 原始 stderr
            "user pasted: EADDRINUSE"
        )
        assertTrue(
            "裸 EADDRINUSE（没有脚本协约行）不得被判成端口冲突。实际=$noise",
            !tailSaysPortInUse(noise)
        )

        // 只有脚本的结论行才算
        val contract = listOf(
            "01:00:00  [start-dsh] node pid: 123",
            "01:00:05  [start-dsh] FAILED reason=port-in-use port=3080 code=1"
        )
        assertTrue(
            "脚本打的 reason=port-in-use 必须被判成端口冲突。实际=$contract",
            tailSaysPortInUse(contract)
        )
    }

    /** `scriptFailureReason` 的解析：只认两个脚本的前缀，其它形态一律 null。 */
    @Test
    fun scriptFailureReasonOnlyMatchesScriptPrefixes() {
        assertEquals("port-in-use",
            scriptFailureReason("[start-dsh] FAILED reason=port-in-use port=3080 code=1"))
        assertEquals("package-missing",
            scriptFailureReason("[start-dsh] FAILED reason=package-missing"))
        assertEquals("verify-version",
            scriptFailureReason("[setup] FAILED reason=verify-version"))
        assertEquals("node-exit",
            scriptFailureReason("[start-dsh] FAILED reason=node-exit code=1"))

        // 前缀不对 / 形态不对 → null
        assertEquals("任意前缀不得命中（这正是收窄攻击面的关键）", null,
            scriptFailureReason("evil] FAILED reason=port-in-use"))
        assertEquals("没有前缀不得命中", null,
            scriptFailureReason("FAILED reason=port-in-use"))
        assertEquals("reason 大小写不对不得命中", null,
            scriptFailureReason("[start-dsh] FAILED reason=PORT-IN-USE"))
        assertEquals("普通日志不得命中", null,
            scriptFailureReason("[start-dsh] node pid: 123"))
    }

    /** 反向：干净输出不得被判成端口冲突（否则上面那条恒真）。 */
    @Test
    fun cleanTailIsNotPortInUse() {
        val clean = listOf(
            "01:00:00  [start-dsh] node    : v22.23.3",
            "01:00:01  [start-dsh] READY port=3080",
            "01:00:01  dsh web: http://127.0.0.1:3080/?token=abc"
        )
        assertTrue("干净输出不得判成端口冲突", !tailSaysPortInUse(clean))
    }

    private fun resolveRuntimeSource(): String {
        val rel = "core/DshRuntime.kt"
        var dir = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (depth < 8) {
            for (cand in listOf(
                java.io.File(dir, "FCL/FCL/src/main/java/com/dsh/$rel"),
                java.io.File(dir, "FCL/src/main/java/com/dsh/$rel"),
                java.io.File(dir, "src/main/java/com/dsh/$rel")
            )) {
                if (cand.isFile) return cand.readText()
            }
            dir = dir.parentFile ?: break
            depth++
        }
        throw AssertionError("找不到 core/DshRuntime.kt")
    }
}
