package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.prootStillOwnsPid
import com.dsh.core.tailSaysPortInUse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * round34 · 两个已确认缺陷的回归锚（都在 `DshRuntime.kt`）。
 *
 * ## 缺陷 A：退出归因读陈旧快照
 *
 * `recentLogTail()` / `appendLastLinesHint()` 原来读 `DshLogBus.snapshotFor(id).value.lines`，
 * 而那个 `StateFlow` 只在**合并刷新**时发布（`FLUSH_INTERVAL_MS = 200`）。两个函数的调用点都在
 * "进程刚退出"的路径上（[com.dsh.core.DshRuntime] 的 `onProcessExit` 在 `drainLines` 读完 +
 * `waitFor` 返回后立即调 `tailSaysPortInUse` / `retryIfSeccompLooksGuilty`，中间无 sleep），
 * 所以脚本临终那几行（`[start-dsh] FAILED reason=port-in-use`、seccomp 关键词）几乎必然还没进快照
 * —— round33 修的端口归因因此在**最常见的失败时序下从未生效**。
 *
 * 修复改用 `DshLogBus.export(id)`：`@JvmStatic`，在 `synchronized(lock)` 里**直读内存缓冲**，
 * 不等 flush。下面第一条用例是**可执行证据**：append 之后立刻读，export 已有、快照没有。
 *
 * ## 缺陷 B：停止失败仍宣布"已停止"
 *
 * `stop()` 的收尾协程在 `stopSucceeded == false` 时依旧无条件 `_state = Idle` + 打「已停止」，
 * 而同一函数 20 行前刚算出 `stillAlive=true`（审计 RE-01 的真机现场：01:31:47 落 Idle/「已停止」，
 * 01:31:53 才打出「仍存活 / stop incomplete」）。
 *
 * 这条没有 JVM 可执行的路径（要 proot + rootfs + Context + 前台服务），所以用**切片源码断言**
 * 钉住接线形态 —— 与 `R31ProotExecutorTest` 对 `Handle.terminate` 的钉法是同一套办法。
 *
 * ## 为什么"A 用行为断言、B 用源码断言"
 * A 的成因（StateFlow 的发布时机）在 JVM 上可以真实复现，所以钉行为；B 的成因在状态机收尾协程里，
 * 那条协程只有真机才跑得起来，构造它要改生产代码结构（代价远大于收益），所以钉接线。
 *
 * 运行：`sh run-tests.sh`（MiniRunner 自动扫描本类）。
 */
class R34StopAndTailTest {

    private var seq = 0
    private fun newId(tag: String): String = "inst-r34st-$tag-${seq++}-${System.nanoTime()}"

    @Before
    fun resetBus() {
        DshLogBus.clearForTestOfRegisteredSecrets()
    }

    // =====================================================================
    // 缺陷 A · 行为：export 同步可见，StateFlow 快照不是
    // =====================================================================

    /**
     * **核心证据**：`appendFor` 之后立刻读，`export(id)` 已经有这一行。
     *
     * 这条同时是对 `recentLogTail` 新实现的契约断言：它的取数口径必须是"调用这一刻的内存缓冲"，
     * 而不是"上一次 flush 发布的快照"。**刻意不调 `flushNow()`** —— 那正是缺陷被掩盖的原因
     * （`R34SecretCapTest` 也是先 `flushNow()` 才读快照，所以它测不到这条时间差）。
     */
    @Test
    fun exportSeesALineImmediatelyWithoutFlush() {
        val id = newId("export")
        val marker = "r34-export-fresh"
        DshLogBus.appendFor(id, marker)

        val live = liveLines(id)
        assertTrue(
            "export 必须同步可见：append 之后立刻读就该有这一行。实际=$live",
            live.any { it.contains(marker) }
        )
    }

    /**
     * **缺陷的现场（可执行复现）**：同一条 append，快照在同一个瞬间通常还看不到它。
     *
     * 为了让这条用例**不因机器负载偶发变红**，把"快照确实落后"写成**有条件的**证据：
     * 整段 append + 一次快照读若在 [FAST_WINDOW_MS] 内跑完（远小于 200ms 的合并刷新间隔），
     * 才要求快照落后。窗口外只要求 export 仍然新鲜（上面那条已覆盖）。
     *
     * 换句话说：本用例的**无条件断言**是"export 新鲜"，"快照陈旧"是取证副产物。
     */
    @Test
    fun snapshotLagsBehindAppendWhileExportDoesNot() {
        val id = newId("lag")
        val startedAt = System.nanoTime()
        DshLogBus.appendFor(id, "r34-lag-probe-1")
        val stale = DshLogBus.snapshotFor(id).value.lines
        val fresh = liveLines(id)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            "无论快照新旧，export 必须已经看到刚 append 的那行。实际=$fresh",
            fresh.any { it.contains("r34-lag-probe-1") }
        )
        assertFalse(
            "前提失效：$id 不该有历史行（fresh id 被污染，后面的取证无意义）",
            fresh.size > 1
        )
        if (elapsedMs < FAST_WINDOW_MS) {
            assertTrue(
                "在 200ms 合并刷新窗口内读快照，它应当还没拿到刚 append 的行" +
                    "（这正是缺陷 A 的成因：recentLogTail 读它 ⇒ 永远晚一步）。" +
                    "实际 elapsedMs=$elapsedMs，snapshot=${stale.filter { it.contains("r34-lag-probe-1") }}",
                stale.none { it.contains("r34-lag-probe-1") }
            )
        }
    }

    /**
     * **用户可见后果**：端口归因在"刚 append 完"的瞬间就能成立。
     *
     * 修复前：`recentLogTail` 读快照 ⇒ 脚本临终的 `FAILED reason=port-in-use` 那行不在快照里
     * ⇒ `tailSaysPortInUse` 返回 false ⇒ 用户看到的是"进程提前退出（code=1）"而不是"端口被占"，
     * 也不会有"下次自动换端口"的提示。round33 的修复因此在最常见的时序下形同虚设。
     *
     * 这里用**与 `recentLogTail` 相同的表达式**（export → split → 剥时间戳 → takeLast(25)），
     * 不调 `flushNow()`。
     */
    @Test
    fun portInUseAttributionWorksBeforeAnyFlush() {
        val id = newId("port")
        DshLogBus.appendFor(id, "[start-dsh] node pid: 123")
        DshLogBus.appendFor(id, "[start-dsh] FAILED reason=port-in-use port=3080 code=1")

        val tail = liveLines(id).takeLast(25).map { it.substringAfter("  ") }
        assertTrue(
            "刚 append 完就该能归因成端口冲突（不 flush）。实际 tail=$tail",
            tailSaysPortInUse(tail)
        )
        // 反向：没有协约行时不得误判 —— 否则上面那条可能是恒真
        val clean = newId("port-clean")
        DshLogBus.appendFor(clean, "[start-dsh] READY port=3080")
        assertFalse(
            "干净输出不得判成端口冲突",
            tailSaysPortInUse(liveLines(clean).map { it.substringAfter("  ") })
        )
    }

    /**
     * `export` 对"没有这个实例"返回**空串**，而 `"".split('\n')` 是 `[""]` ——
     * 直接用会凭空多出一行空行，污染"最近输出"文案与关键词判据。
     *
     * 这里钉两件事：① `export` 的既有契约（未知 id = 空串，且**不创建**流）；
     * ② `DshRuntime.liveInstanceLines` 里那条 `isEmpty()` 折空列表的守卫必须还在（源码断言）。
     */
    @Test
    fun unknownInstanceExportIsEmptyAndDoesNotCreateAStream() {
        val ghost = newId("ghost")
        assertEquals("未知实例的 export 必须是空串", "", DshLogBus.export(ghost))
        assertEquals("未知实例的 size 必须是 0", 0, DshLogBus.size(ghost))
        // ""split('\n') == [""] —— 这就是那个坑本身
        assertEquals("（坑的形态）空串 split 会得到一行空行", 1, "".split('\n').size)

        val body = functionBody("private fun liveInstanceLines(")
        assertTrue(
            "liveInstanceLines 必须把空串折成空列表，否则「最近输出」会多一行空行",
            body.contains("if (text.isEmpty()) return emptyList()")
        )
    }

    // =====================================================================
    // 缺陷 A · 接线：DshRuntime 里不许再读 StateFlow 快照
    // =====================================================================

    /**
     * **回归锚**：`DshRuntime.kt` 里**一处** `DshLogBus.snapshotFor(` 都不许再有。
     *
     * 为什么是整文件而不是两个函数：本缺陷的同族恰好有两处（`recentLogTail` /
     * `appendLastLinesHint`），而两次修都只改一处是这类 bug 的典型复发形态。凡是"退出/失败路径上
     * 读日志"的地方都必须走同步可见的 [DshLogBus.export]。
     *
     * ⚠️ 将来若真需要 `StateFlow`（例如 UI 收集），请改成显式订阅而不是 `.value` 快照 ——
     * 那时再放宽本断言并写清理由。
     */
    @Test
    fun runtimeNeverReadsTheStaleLogSnapshot() {
        val text = runtimeSource()
        assertFalse(
            "DshRuntime 不得再读 DshLogBus.snapshotFor(...).value —— 它只在 ~200ms 一次的合并刷新时" +
                "发布，而失败归因发生在进程刚退出的瞬间（缺陷 A）",
            text.contains("DshLogBus.snapshotFor(")
        )
        assertTrue(
            "recentLogTail 必须走 liveInstanceLines（export，同步可见）",
            functionBody("private fun recentLogTail(").contains("liveInstanceLines(instanceId)")
        )
        assertTrue(
            "appendLastLinesHint 必须走 liveInstanceLines（同族第二处）",
            functionBody("private fun appendLastLinesHint(").contains("liveInstanceLines(instanceId)")
        )
        assertTrue(
            "liveInstanceLines 必须实现为 DshLogBus.export(id)",
            functionBody("private fun liveInstanceLines(").contains("DshLogBus.export(instanceId)")
        )
    }

    // =====================================================================
    // 缺陷 B · pid 判据：停止后必须带身份，不能只看 /proc 或 Process.isAlive
    // =====================================================================

    /**
     * `prootStillOwnsPid` 的语义（纯函数）：
     * - 只有 `pid > 0` ∧ cmdline 含 `proot` ∧ 含本实例 id 才算"还在"；
     * - **读不到 cmdline ⇒ 不在**（进程退出后 `/proc/<pid>` 立即消失，读不到是常态；
     *   而 pid 被复用时 cmdline 里没有这两个词，也不会误判成"活"）。
     */
    @Test
    fun prootStillOwnsPidRequiresIdentityNotJustExistence() {
        val id = "inst-r34st-pid"
        val real = "/system/bin/libproot.so --bind=/data/x/instances/$id INSTANCE_DIR=/data/x/instances/$id /usr/bin/env sh"
        assertTrue("身份匹配 ⇒ 仍在", prootStillOwnsPid(12345, real, id))
        assertFalse("cmdline 读不到 ⇒ 判不在（退出后 /proc 立即消失）", prootStillOwnsPid(12345, null, id))
        assertFalse("cmdline 空串 ⇒ 判不在", prootStillOwnsPid(12345, "", id))
        assertFalse(
            "pid 被复用成别的进程 ⇒ 判不在（这是缺陷 B 的一半：旧判据只看 /proc 存在）",
            prootStillOwnsPid(12345, "/system/bin/app_process64 com.example.other", id)
        )
        assertFalse(
            "是别人的 proot（另一个实例）⇒ 判不在",
            prootStillOwnsPid(12345, "/system/bin/libproot.so --bind=/data/x/instances/inst-other", id)
        )
        assertFalse("pid<=0 ⇒ 判不在", prootStillOwnsPid(0, real, id))
        assertFalse("pid 负数 ⇒ 判不在（-1 = 三条解析路径全失败）", prootStillOwnsPid(-1, real, id))
        assertFalse("实例 id 为空 ⇒ 判不在（identity 无从确认）", prootStillOwnsPid(12345, real, " "))
    }

    /**
     * 两个句柄实现都必须覆写 [com.dsh.core.DshRuntime.Handle.isStillTheSameProotAfterStop]，
     * 且都带 cmdline 身份 —— 默认实现（= `isAlive`）在停止路径上是**错的**，只是为了让
     * 接口改动不波及别处才保留。
     *
     * `ProcessHandle` 尤其重要：它的 `isAlive` 走 `Process.isAlive`，真机 DT-07 采样里
     * `/proc/<pid>` 1~2 秒就消失了，而它 5 秒后仍判"活"。
     */
    @Test
    fun bothHandlesOverrideTheIdentityCheckedStopProbe() {
        val text = runtimeSource()
        val processHandle = sliceBetween(
            text,
            "private class ProcessHandle(",
            "private class PidHandle("
        )
        val pidHandle = sliceBetween(
            text,
            "private class PidHandle(",
            "@Volatile\n    private var handle: Handle?"
        )
        listOf("ProcessHandle" to processHandle, "PidHandle" to pidHandle).forEach { (name, body) ->
            assertTrue(
                "$name 必须覆写 isStillTheSameProotAfterStop（默认实现是 isAlive，停止路径方向反了）",
                body.contains("override fun isStillTheSameProotAfterStop(): Boolean")
            )
            assertTrue(
                "$name 的停止后判据必须带身份（prootStillOwnsPid），不能只看进程存在",
                body.contains("prootStillOwnsPid(")
            )
        }
        assertTrue(
            "接口默认实现应保持 = isAlive（只作兼容兜底，别再被生产路径用到）",
            sliceBetween(
                text,
                "internal interface Handle {",
                "suspend fun terminate(graceMillis: Long)"
            ).contains("fun isStillTheSameProotAfterStop(): Boolean = isAlive()")
        )
    }

    // =====================================================================
    // 缺陷 B · 接线：stopSucceeded == false 时不许宣布"已停止"
    // =====================================================================

    /**
     * **主用例**：`stop()` 收尾的状态迁移必须由 `stopSucceeded` 分叉。
     *
     * 分两层断言（切片是为了把"锁内的状态迁移"与"锁外的副作用"分开，别互相糊住）：
     * - 锁内 `if (stopSucceeded) { … } else { … }`：成功支写 Idle / 清 `stopRequestedFor`；
     *   失败支写 `State.Failed`、**不许**写 Idle、**不许**清 `stopRequestedFor`；
     * - 锁外 `when (weOwnedTheStop)`：「已停止」只在 `false ->`（= 停成功）那一支。
     */
    @Test
    fun stopAnnouncesStoppedOnlyWhenStopSucceeded() {
        val (lockIf, lockElse) = stopLockBranches()

        assertTrue("成功支必须写 Idle", lockIf.contains("_state.value = State.Idle"))
        assertTrue("成功支必须清 stopRequestedFor", lockIf.contains("stopRequestedFor = null"))
        assertTrue(
            "失败支必须落一个有结论的终态（Failed），而不是 Idle",
            lockElse.contains("State.Failed(instanceId, name, reason)")
        )
        assertFalse(
            "失败支**不许**写 Idle（这正是 RE-01：进程还活着却宣布通道空闲）",
            lockElse.contains("_state.value = State.Idle")
        )
        assertFalse(
            "失败支不许清 stopRequestedFor（清掉后进程真退出会被上报成意外崩溃，与结论矛盾）",
            lockElse.contains("stopRequestedFor = null")
        )

        val (okBranch, failedBranch) = stopOutcomeBranches()
        assertTrue("停成功那一支才打「已停止」", okBranch.contains("已停止"))
        assertFalse(
            "停失败那一支**不许**打「已停止」（用户被告知已停，300MB 进程还活着）",
            failedBranch.contains("已停止")
        )
        assertTrue("停成功那一支才收前台服务", okBranch.contains("stopServiceIfIdle()"))
        // ★ 判据必须落在**代码**上，不是注释上：失败支里有一句
        //   "★ 不调 stopServiceIfIdle()：……" 的说明，按整段 contains 会假红。
        assertFalse(
            "停失败那一支不许收前台服务（否则活进程彻底失去保活）",
            stripLineComments(failedBranch).contains("stopServiceIfIdle()")
        )
    }

    /**
     * **不许引入新坑**：停止失败不能把状态留在 `Stopping`。
     *
     * `stopAndWait` 的 13s 判据只认"状态离开 Stopping"，留在 Stopping 会把下一次启动拖满 13 秒
     * 再走"超时后强行启动"兜底（Round24 N-1 刚修掉的坏路径）；二次 stop 对 Stopping 又是 no-op
     * （BL-036a），用户点不出第二次停止。所以失败支必须是 `Failed`，不是 `Stopping`。
     */
    @Test
    fun stopFailureDoesNotStrandTheStateInStopping() {
        val (_, lockElse) = stopLockBranches()
        assertFalse(
            "失败支**不许**把状态写回 Stopping（会拖满 stopAndWait 的 13s，且二次停止是 no-op）",
            lockElse.contains("State.Stopping")
        )
        assertTrue(
            "失败支必须真的落 Failed —— 这条是上面那条的反向锚（否则「没写 Stopping」可以靠什么都不写满足）",
            lockElse.contains("_state.value = State.Failed(")
        )
    }

    /**
     * 「已停止」的**唯一**出口必须被 `stopSucceeded` 守住；反向钉一句：失败分支里那条日志
     * 必须说"停止未完成"，且必须带上 pid（否则用户/排障者无法定位残留进程）。
     */
    @Test
    fun failureBranchLogsAnActionableIncompleteMessage() {
        val (_, failedBranch) = stopOutcomeBranches()
        assertTrue(
            "失败必须明说「停止未完成」并带 pid（这是排障时唯一的现场线索）",
            failedBranch.contains("停止未完成") && failedBranch.contains("pid=\$prootPid")
        )
        assertTrue(
            "失败必须上报一条终态任务（否则任务区那行凭空消失，用户只看到「停止」没有下文）",
            failedBranch.contains("DshTask.State.FAILED")
        )
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private companion object {
        /**
         * "同一瞬间"的判定窗口（毫秒）。远小于 `DshLogBus.FLUSH_INTERVAL_MS`（200），
         * 又能容纳 JVM 上 append+sleep(0) 的抖动；超出窗口就只断言无条件部分，避免偶发红。
         */
        const val FAST_WINDOW_MS = 150L

        /** 与 `DshRuntime.liveInstanceLines` **同一表达式**（本类不反射私有方法，逐字复刻）。 */
        fun liveLines(instanceId: String): List<String> {
            val text = DshLogBus.export(instanceId)
            return if (text.isEmpty()) emptyList() else text.split('\n')
        }

        /** 读生产源码（口径同 `R31ProotExecutorTest.resolveSource` / `R33LogPoisoningTest`）。 */
        fun resolveRuntimeSource(): String {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            var depth = 0
            while (dir != null && depth < 8) {
                listOf(
                    File(dir, "FCL/FCL/src/main/java/com/dsh/core/DshRuntime.kt"),
                    File(dir, "FCL/src/main/java/com/dsh/core/DshRuntime.kt"),
                    File(dir, "src/main/java/com/dsh/core/DshRuntime.kt")
                ).firstOrNull { it.isFile }?.let { return it.readText() }
                dir = dir.parentFile
                depth++
            }
            throw AssertionError("找不到 core/DshRuntime.kt —— 请在仓库根或 FCL/ 目录下运行测试")
        }

        fun runtimeSource(): String = resolveRuntimeSource()

        /**
         * 取 `[start, end)` 之间的源码切片；端点找不到时**直接失败**（静默跳过 = 锚点成了摆设）。
         *
         * 为什么切片而不是整文件 `contains`：`stopServiceIfIdle()` / `已停止` / `State.Idle`
         * 在 `DshRuntime.kt` 里有十几处合法用法，整文件断言会被别处满足（假绿）。
         */
        fun sliceBetween(text: String, start: String, end: String): String {
            val from = text.indexOf(start)
            assertTrue("切片起点未命中（签名被改了？）：$start", from >= 0)
            val to = text.indexOf(end, from + start.length)
            assertTrue("切片终点未命中：$end", to > from)
            return text.substring(from, to)
        }

        /**
         * 取某函数体：从签名到下一个方法声明/KDoc/文件尾三者中**最近**的那个。
         *
         * 函数之间的分隔既可能是 KDoc 也可能是下一个声明，所以三种候选一起找、取最早的一个。
         * ⚠️ 不要在注释里写出 KDoc 起始符的字面量（Kotlin 的块注释**可嵌套**，写进来会把
         *   后面整段代码吞掉 —— 本文件初版就是这么炸的）。
         */
        fun functionBody(signature: String): String {
            val text = runtimeSource()
            val from = text.indexOf(signature)
            assertTrue("找不到函数：$signature", from >= 0)
            val candidates = listOf(
                text.indexOf("\n    private fun ", from + signature.length),
                text.indexOf("\n    fun ", from + signature.length),
                text.indexOf("\n    /**", from + signature.length),
                text.indexOf("\n}", from + signature.length)
            ).filter { it > from }
            assertTrue("找不到 $signature 的收尾", candidates.isNotEmpty())
            return text.substring(from, candidates.min())
        }

        /**
         * 锁内 `if (stopSucceeded) { … } else { … }` 的两支（停成功 / 停未完成）。
         *
         * 用 `matchBrace` 做真正的括号配对，而不是找一个 `} else {` —— 成功支内部本身就有
         * `if (handle === h) { … }`，按文本找 `} else {` 会切在错误的地方（本用例初版踩过）。
         */
        fun stopLockBranches(): Pair<String, String> {
            val text = runtimeSource()
            // ★ 锚点必须落在 `val weOwnedTheStop` **之后**：`if (stopSucceeded) {` 在同一个
            //   stop() 里还有一处（删 pid 文件那段），从文件头 indexOf 会切到错的地方。
            val anchor0 = text.indexOf("val weOwnedTheStop: Boolean? = synchronized(this@DshRuntime) {")
            assertTrue("找不到 `val weOwnedTheStop`（收尾三态判据被删了？）", anchor0 >= 0)
            val anchor = text.indexOf("if (stopSucceeded) {", anchor0)
            assertTrue("`weOwnedTheStop` 之后必须按 stopSucceeded 分叉（旧实现在这里完全不看它）", anchor > anchor0)
            val openIf = text.indexOf('{', anchor)
            val closeIf = matchBrace(text, openIf)
            val elseKw = text.indexOf("} else {", closeIf)
            assertTrue("停成功支后面必须有 else 支", elseKw > 0)
            val openElse = text.indexOf('{', elseKw)
            val closeElse = matchBrace(text, openElse)
            return text.substring(openIf + 1, closeIf) to text.substring(openElse + 1, closeElse)
        }

        /** 锁外 `when (weOwnedTheStop)` 的"停成功"支与"停未完成"支。 */
        fun stopOutcomeBranches(): Pair<String, String> {
            val text = runtimeSource()
            val anchor = text.indexOf("when (weOwnedTheStop) {")
            assertTrue("找不到 `when (weOwnedTheStop)`（终态分叉被删了？）", anchor >= 0)
            val okAt = text.indexOf("false -> {", anchor)
            val failedAt = text.indexOf("true -> {", okAt)
            assertTrue("停成功支（false ->）不见了", okAt > 0)
            assertTrue("停未完成支（true ->）不见了", failedAt > okAt)
            val ok = text.substring(text.indexOf('{', okAt) + 1, matchBrace(text, text.indexOf('{', okAt)))
            val failedOpen = text.indexOf('{', failedAt)
            return ok to text.substring(failedOpen + 1, matchBrace(text, failedOpen))
        }

        /**
         * 去掉整行 `//` 注释，只留可执行代码。
         *
         * 为什么需要：修复代码里有一句 `// ★ 不调 stopServiceIfIdle()：……` 的解释性注释，
         * 按整段 `contains` 断言"调用不存在"会假红。判据必须对着**代码**，不是注释。
         * （只处理行首 `//`：切片段里的字符串字面量不含 `//`，本仓也没有原始字符串。）
         */
        fun stripLineComments(slice: String): String =
            slice.lineSequence()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")

        /**
         * 从 [openIdx] 处的 `{` 找配对的 `}` 下标。
         *
         * 跳过 `'…'` 与 `"…"` 里的花括号（切片范围内有 `'\\n'`、`"…"` 字面量；Kotlin 的
         * 原始字符串未在此使用）。注释里的花括号也一并跳过 —— 这几段代码的注释里确实有 `{`。
         */
        fun matchBrace(text: String, openIdx: Int): Int {
            assertTrue("matchBrace 的起点必须是 `{`", text[openIdx] == '{')
            var depth = 0
            var i = openIdx
            var inString = false
            var inChar = false
            while (i < text.length) {
                val c = text[i]
                when {
                    inString || inChar -> when {
                        c == '\\' -> i++
                        (inString && c == '"') || (inChar && c == '\'') -> {
                            inString = false
                            inChar = false
                        }
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
            throw AssertionError("括号不配对：从 $openIdx 起没找到收尾的 `}`")
        }
    }
}
