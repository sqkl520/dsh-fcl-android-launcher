package com.dsh

import com.dsh.core.DshRuntime
import com.dsh.core.planRunningRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R35 / RE-02：「已 Running 时只刷新 url」分支的判定核心（纯函数 [planRunningRefresh]）。
 *
 * ## 缺陷本体
 * `DshRuntime.onProcessLine` 里那段原来是**锁外读快照 + 锁外写**：
 * ```
 * val cur = _state.value              // 锁外读
 * ... _state.value = cur.copy(...)    // 锁外写
 * ```
 * 而 [DshRuntime.stop] 是 `@Synchronized`、且在锁内置 `State.Stopping`。stop() 只要插在
 * 这次读写之间，旧快照就会把 **Stopping 覆盖回 Running**（`_running` 也被重新写活）。
 *
 * ## 这组测试钉的是什么
 * 修法是把决策抽成纯函数、由调用点在**锁内重读** `_state.value` 后调用它。于是：
 * - 缺陷本体在函数契约上是第①条用例：**入参是 Stopping ⇒ 返回 null**（= 一个字都不许动）。
 * - "锁内重读"这个动作本身由调用点的 `synchronized(this) { planRunningRefresh(_state.value, …) }`
 *   保证；本文件用**变异验证**证明这条用例确实能抓住"改回锁外快照"（见报告 FIX-RE02.md：
 *   把调用点改回 `planRunningRefresh(cur, …)` 后 `stoppingIsNeverRefreshedBackToRunning` 精确变红）。
 *
 * ## 测不了的部分（别把这组当成"竞态已全覆盖"）
 * 真机上的时序（stop() 恰好插在两次调用之间）没法在 JVM 上复现：`onProcessLine` 是 private、
 * 且 [DshRuntime] 的构造/日志路径要 Android Context。这里钉的是"判据错一位就出事"的那一位。
 *
 * 运行：sh run-tests.sh，或 ./gradlew :FCL:testDebugUnitTest --tests "com.dsh.R35RunningRefreshTest"
 */
class R35RunningRefreshTest {

    // --- 工具 ---------------------------------------------------------------

    private val inst = "inst-a"
    private val other = "inst-b"

    /** 本实例的 Running（url 带 token —— 真实路径里 state.url 绝大多数时候是这一形态）。 */
    private fun runningOf(
        id: String = inst,
        url: String? = "http://127.0.0.1:3080/?token=OLD",
        port: Int = 3080,
    ) = DshRuntime.State.Running(id, "name-$id", url, port)

    // --- ① 缺陷本体：Stopping 绝不能被这次刷新写回 Running -----------------------

    @Test
    fun stoppingIsNeverRefreshedBackToRunning() {
        // RE-02 的现场：stop() 已在锁内置了 Stopping，此时 dsh 又打印了一行 URL。
        // 旧实现拿锁外快照（当时还是 Running）写回 ⇒ Stopping 被覆盖回 Running。
        // 新契约：以**锁内重读到的**状态为准，Stopping ⇒ null（不写 state、不写 running、不 IO）。
        val stopping = DshRuntime.State.Stopping(inst, "name-$inst")
        val plan = planRunningRefresh(stopping, inst, "http://127.0.0.1:3080/?token=NEW", 3080)
        assertNull("Stopping 时不得返回任何刷新计划（否则会把 Stopping 写回 Running）", plan)
    }

    @Test
    fun terminalAndIdleStatesAreAlsoRejected() {
        // 同族的"不该动"状态：终态已定（Failed/Exited）或根本没有归属（Idle）。
        // 旧实现只判 `cur is State.Running` 就够挡这些；新函数要保持同样的边界，
        // 免得重构顺手放宽成"任何非 Starting 都刷新"。
        val incoming = "http://127.0.0.1:3080/?token=NEW"
        assertNull("Idle 无归属", planRunningRefresh(DshRuntime.State.Idle, inst, incoming, 3080))
        assertNull(
            "Failed 是终态，不许被一行迟到的 URL 改写",
            planRunningRefresh(DshRuntime.State.Failed(inst, "name-$inst", "boom"), inst, incoming, 3080)
        )
        assertNull(
            "Exited 是终态",
            planRunningRefresh(DshRuntime.State.Exited(inst, "name-$inst", 1), inst, incoming, 3080)
        )
        // Starting 也不该走这条分支（它的迁移在 onProcessLine 的另一段：A5 的 Starting→Running）
        assertNull(
            "Starting 不属于本函数的职责",
            planRunningRefresh(DshRuntime.State.Starting(inst, "name-$inst"), inst, incoming, 3080)
        )
    }

    // --- ② 正常路径：本实例的 Running ⇒ 配对给出 state/running -------------------

    @Test
    fun runningOfThisInstanceProducesPairedStateAndRunning() {
        val current = runningOf()
        val incoming = "http://127.0.0.1:3080/?token=NEW"
        val plan = planRunningRefresh(current, inst, incoming, 3080)
        assertNotNull("本实例的 Running 应返回刷新计划", plan)
        plan!!
        // state 与 running 必须是**同一份事实**（配对写的前提）：url/port/instanceId 全一致。
        assertEquals("state.url 应更新为新值", incoming, plan.state.url)
        assertEquals("state 端口", 3080, plan.state.port)
        assertEquals("state 归属不变", inst, plan.state.instanceId)
        assertEquals("running.url 与 state 必须一致", incoming, plan.running.url)
        assertEquals("running.port 与 state 必须一致", 3080, plan.running.port)
        assertEquals("running.instanceId 与 state 必须一致", inst, plan.running.instanceId)
        // 必须是 Running 类型（不是别的 State 子类被 copy 出来）
        assertTrue("返回的 state 必须是 State.Running", plan.state is DshRuntime.State.Running)
    }

    @Test
    fun portStaysWhenIncomingLineHasNoPort() {
        // 第二级 READY url= 有时只给 host:port，但也会有无端口/解析失败的形态 ——
        // 原实现的 `UrlScanner.portOf(url) ?: cur.port` 语义：取不到就保留旧端口。
        val plan = planRunningRefresh(runningOf(port = 3080), inst, "http://127.0.0.1/?token=NEW", null)
        assertNotNull(plan)
        assertEquals("解析不出端口时保留 current.port", 3080, plan!!.state.port)
        assertEquals("running 同步保留", 3080, plan.running.port)
    }

    @Test
    fun incomingPortWinsWhenPresent() {
        // 端口变化（dsh 换了监听端口）时必须跟着走，否则 WebView 连旧端口。
        val plan = planRunningRefresh(runningOf(port = 3080), inst, "http://127.0.0.1:50000/?token=NEW", 50000)
        assertNotNull(plan)
        assertEquals("新端口生效", 50000, plan!!.state.port)
        assertEquals("running 同步", 50000, plan.running.port)
    }

    // --- ③ 别的实例的 Running ⇒ null（第三轮已定的约定，这里钉住不许退化） -------

    @Test
    fun runningOfAnotherInstanceIsRejected() {
        // 被停掉的旧实例（终止是异步的，最长 5s）在退出前把 URL 打出来 ——
        // 若采信，就会把**新实例**的 url/port 覆盖成旧实例的，WebView 连到已关掉的端口。
        val otherRunning = runningOf(id = other, url = "http://127.0.0.1:40000/?token=X", port = 40000)
        val plan = planRunningRefresh(otherRunning, inst, "http://127.0.0.1:3080/?token=NEW", 3080)
        assertNull("别的实例的 Running 一个字都不许动", plan)
    }

    // --- ④ BL-136：合成裸 URL 不覆盖带 token 的 url，但端口仍更新 ----------------

    @Test
    fun syntheticBareUrlKeepsTokenUrlButStillUpdatesPort() {
        // fix3 缺陷现场的中间态：dsh web:（带 token）→ READY port=（合成裸）→ READY url=（带 token）。
        // 中间那步若把裸 URL 写进 state，收集器/外跳在毫秒级窗口内取到裸 URL 即 401 隐患。
        val tokenUrl = "http://127.0.0.1:3080/?token=OLD"
        val current = runningOf(url = tokenUrl, port = 3080)
        val syntheticBare = "http://127.0.0.1:3081/"
        val plan = planRunningRefresh(current, inst, syntheticBare, 3081)
        assertNotNull("BL-136 拦的是 url 覆盖，不是整条刷新（端口仍要更新）", plan)
        plan!!
        assertEquals("BL-136：url 必须保留旧值（不许降级成裸 URL）", tokenUrl, plan.state.url)
        assertEquals("running.url 同样保留旧值", tokenUrl, plan.running.url)
        assertEquals("但端口仍取合成值里的端口", 3081, plan.state.port)
        assertEquals("running.port 同步更新", 3081, plan.running.port)
        assertNull("新值无 token ⇒ BL-135 的持久化条件不成立", plan.persistUrl)
    }

    @Test
    fun bl136JudgedAgainstTheStateActuallyPassedIn() {
        // ★ 判据基准是**传进来的** current.url（调用点保证它是锁内重读的那一份）。
        //   "锁内重读 ⇒ 判据更严"的具体体现：锁外快照里 url 还带 token、锁内重读后已被清成裸的，
        //   此时同一行裸 URL 不再是"降级"（旧值本来就裸）—— 本函数按传入值判，不做任何额外放宽。
        val bareCurrent = runningOf(url = "http://127.0.0.1:3080/", port = 3080)
        val plan = planRunningRefresh(bareCurrent, inst, "http://127.0.0.1:3080/", 3080)
        assertNotNull("旧值本来裸 → 裸覆盖无损失，允许", plan)
        assertEquals("url 保持裸", "http://127.0.0.1:3080/", plan!!.state.url)
        // 反过来：传入的 current.url 带 token 而新值裸 → 必须拦（与第一条 BL-136 用例同一判据，
        // 这里显式钉"判据看的是入参而不是别的全局状态"）。
        val tokenCurrent = runningOf(url = "http://127.0.0.1:3080/?token=OLD", port = 3080)
        val blocked = planRunningRefresh(tokenCurrent, inst, "http://127.0.0.1:3080/", 3080)
        assertNotNull(blocked)
        assertEquals(
            "带 token 的旧值必须保留",
            "http://127.0.0.1:3080/?token=OLD",
            blocked!!.state.url
        )
    }

    @Test
    fun tokenUrlCanUpgradeAnAlreadyBareUrl() {
        // ③级合成裸 URL 先落 state（writePidFile 会把 url 记为 null），②级 READY url=（带 token）后到 ——
        // 这是**必须**放行的升级：BL-135 说它是 pid 文件 url 字段的刷新机会。
        val uncovered = runningOf(url = "http://127.0.0.1:3080/", port = 3080)
        val tokenUrl = "http://127.0.0.1:3080/?token=NEW"
        val plan = planRunningRefresh(uncovered, inst, tokenUrl, 3080)
        assertNotNull(plan)
        assertEquals("升级必须写进去", tokenUrl, plan!!.state.url)
        assertEquals("带 token 的升级要刷新持久化", tokenUrl, plan.persistUrl)
    }

    // --- ⑤ BL-135 / R17：持久化条件（tokenOf(url) != null && current.url != url） -

    @Test
    fun persistUrlSetWhenTokenUrlChanges() {
        // 值变了 + 带 token ⇒ pid 文件 url 字段与 token 侧车都要刷新。
        val plan = planRunningRefresh(runningOf(), inst, "http://127.0.0.1:3080/?token=NEW", 3080)
        assertNotNull(plan)
        assertEquals(
            "带 token 且值变了 ⇒ 必须带出 persistUrl（pid 文件 + 侧车的刷新来源）",
            "http://127.0.0.1:3080/?token=NEW",
            plan!!.persistUrl
        )
    }

    @Test
    fun persistUrlNullWhenTokenUrlUnchanged() {
        // 值没变（dsh 重复打印同一行）⇒ 不做 IO。这是原实现 `cur.url != url` 那一半的语义，
        // 少了它每条重复行都会重写一次 pid 文件与侧车（真机上 dsh 会反复打印）。
        val same = "http://127.0.0.1:3080/?token=OLD"
        val plan = planRunningRefresh(runningOf(url = same, port = 3080), inst, same, 3080)
        assertNotNull("状态照旧刷新（幂等），只是不落盘", plan)
        assertNull("值没变 ⇒ 不写 pid 文件 / 侧车", plan!!.persistUrl)
    }

    @Test
    fun persistUrlNullForBareIncomingUrl() {
        // 无 token 的新值不配做持久化来源（BL-135 的前提是"带 token 的最终值"）。
        // 注意这条与 BL-136 是两件事：这里旧值本来就是裸的，所以 url 会被写（无损失），
        // 但**不落盘** —— writePidFile 内部也会再校验一次，两层判断一致。
        val plan = planRunningRefresh(
            runningOf(url = "http://127.0.0.1:3080/", port = 3080),
            inst,
            "http://127.0.0.1:3081/",
            3081,
        )
        assertNotNull(plan)
        assertEquals("url 可写（旧值本来就裸）", "http://127.0.0.1:3081/", plan!!.state.url)
        assertNull("无 token 的值不做持久化", plan.persistUrl)
    }

    @Test
    fun persistUrlNullWhenOnlyPortChangesOnTokenUrl() {
        // 带 token 且 url 与旧值完全相同，只是端口维度变了 —— url 没变 ⇒ 不落盘。
        // （pid 文件里存的也是这条 url，重写只是同样的内容。）
        val same = "http://127.0.0.1:3080/?token=OLD"
        val plan = planRunningRefresh(runningOf(url = same, port = 3080), inst, same, 3099)
        assertNotNull(plan)
        assertEquals("端口更新", 3099, plan!!.state.port)
        assertNull("url 没变 ⇒ 不落盘", plan.persistUrl)
    }

    // --- ⑥ 接线：调用点必须**锁内重读**，副作用必须**留锁外** ----------------------
    //
    // ★ 为什么纯函数测试不够：RE-02 的缺陷在**调用点**（锁外读快照 + 锁外写），
    //   不在判定本身。把 planRunningRefresh 写得再对，调用点传 `cur`（锁外快照）照样是原来的 bug。
    //   `onProcessLine` 是 private 且宿主类是 Android 相关类，JVM 上调不到 ⇒ 与 R33 的
    //   [com.dsh.R33LoadUrlGateWiringTest]、R29 的 GateResume 分支体断言同一办法：
    //   **对源码做结构化断言**（断言"位置语义"，不是"某行文字"）。
    //
    // 判别力（见报告 FIX-RE02.md 的变异验证）：把调用点改回 `planRunningRefresh(cur, …)` ⇒
    // [refreshDecisionIsReReadInsideTheLock] 精确变红；把两处文件写入挪进 synchronized 块 ⇒
    // [fileSideEffectsStayOutsideTheLock] 变红。两条都只在**改动真的回退**时才红。

    @Test
    fun refreshDecisionIsReReadInsideTheLock() {
        val body = onProcessLineBody()
        val lockBody = synchronizedBlockOf(body, "val refresh = synchronized(this) {")
        assertTrue(
            "锁内必须用重读的 _state.value 调 planRunningRefresh —— 传锁外快照 cur 就是 RE-02 本体",
            lockBody.contains("planRunningRefresh(_state.value,")
        )
        assertFalse(
            "锁内不许把锁外快照 cur 传给 planRunningRefresh（RE-02 的原始缺陷写法）",
            lockBody.contains("planRunningRefresh(cur,")
        )
        // 配对写：两个 flow 必须在同一个临界区里一起写，否则中间态会被别的读者看见。
        assertTrue("锁内必须写 _state.value = plan.state", lockBody.contains("_state.value = plan.state"))
        assertTrue("锁内必须写 _running.value = plan.running", lockBody.contains("_running.value = plan.running"))
        // 判 null 的早退必须在锁外那侧（`} ?: return`）—— 锁内 null 表示"一个字都不许动"。
        assertTrue(
            "锁外必须有 `} ?: return`：plan 为 null 时连副作用都不做",
            body.contains("} ?: return")
        )
    }

    @Test
    fun fileSideEffectsStayOutsideTheLock() {
        val branch = refreshBranchBody()
        val lockBody = synchronizedBlockOf(branch, "val refresh = synchronized(this) {")
        // ① 锁内一个文件写入都不许有（否则 IO 被拖进 stop 的等待里 —— A5 段的实测前提）
        for (marker in listOf("ProcessUtil.writePidFile(", "ProcessUtil.writeTokenUrlFile(")) {
            assertFalse("锁内不许做 $marker（副作用必须留锁外）", lockBody.contains(marker))
        }
        // ② 锁外（本分支内、synchronized 块之后）两处写入都必须有。
        //    ★ 切片必须**止于本分支右花括号**：onProcessLine 下面还有 A5 段，那里也有同名调用，
        //      切到函数尾会让"把本次的写入挪进锁内"这个变异被 A5 段的同名调用掩盖成绿。
        val afterLock = branch.substring(endOfBlock(branch, branch.indexOf('{', branch.indexOf("val refresh = synchronized(this) {"))) + 1)
        for (marker in listOf("ProcessUtil.writePidFile(", "ProcessUtil.writeTokenUrlFile(")) {
            val idx = afterLock.indexOf(marker)
            assertTrue("锁外必须做 $marker（副作用留在锁外，别把 IO 拖进 stop 的等待）", idx >= 0)
            // 而且必须由 refresh.persistUrl 把关（BL-135 的条件在锁内判完，结果带出来）
            assertTrue("$marker 之前必须已取到 persistUrl", afterLock.indexOf("refresh.persistUrl") in 0 until idx)
        }
    }

    // --- 源码定位/切片工具（与 R33LoadUrlGateWiringTest 同惯例） --------------------

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

    private fun onProcessLineBody(): String {
        val text = mainSourceFile().readText()
        val start = text.indexOf("private fun onProcessLine(")
        if (start < 0) throw AssertionError("找不到 onProcessLine 的声明 —— 方法被改名或删了？")
        val braceStart = text.indexOf('{', start)
        if (braceStart < 0) throw AssertionError("onProcessLine 没有方法体？")
        return text.substring(braceStart, endOfBlock(text, braceStart) + 1)
    }

    /**
     * 切出「已 Running 时只刷新 url」那**一个分支**的方法体（含首尾花括号）。
     *
     * ★ 必须精确到分支而不是整个 `onProcessLine`：函数下面紧邻的 A5 段（Starting→Running）
     *   也调 `ProcessUtil.writePidFile` / `writeTokenUrlFile`，切宽了会把"把本次写入挪进锁内"
     *   这个变异掩盖成绿（切片之外的同名调用仍在，断言照样通过）。
     */
    private fun refreshBranchBody(): String {
        val body = onProcessLineBody()
        val header = "if (cur !is State.Starting || cur.instanceId != instance.id) {"
        val start = body.indexOf(header)
        if (start < 0) {
            throw AssertionError("找不到「已 Running 只刷新 url」分支的头：$header —— 结构被改写了？")
        }
        val open = body.indexOf('{', start)
        return body.substring(start, endOfBlock(body, open) + 1)
    }

    /** 从 [openBrace] 处的 `{` 开始，按花括号深度找配对的 `}` 的下标。 */
    private fun endOfBlock(text: String, openBrace: Int): Int {
        if (openBrace < 0 || text.getOrNull(openBrace) != '{') {
            throw AssertionError("openBrace 处不是 '{'（下标=$openBrace）")
        }
        var depth = 0
        var i = openBrace
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        throw AssertionError("花括号没有闭合 —— 解析失败")
    }

    private fun synchronizedBlockOf(text: String, header: String): String {
        val start = text.indexOf(header)
        if (start < 0) throw AssertionError("找不到 `$header` —— 刷新段被改写/挪走了？")
        val open = text.indexOf('{', start)
        return text.substring(open, endOfBlock(text, open) + 1)
    }
}
