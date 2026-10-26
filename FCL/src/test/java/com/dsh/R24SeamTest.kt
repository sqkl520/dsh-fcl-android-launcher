package com.dsh

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * R24 / A8：**测试基础设施冒烟**（不测业务逻辑）。
 *
 * ## 为什么需要这条
 * A8 的 seam 测试要注入 fake handle / fake preflight，每条用例后必须把注入值撤掉，
 * 否则下一条用例读到上一条留下的全局状态，红了也找不到原因。原 `run-tests.sh` 的 JUnit 桩
 * 只有 `@Before`（E5-9 实测：测试里写 `@After` 直接编译失败），所以先证明桩本身可用。
 *
 * ## 钉的是什么
 * 1. `org.junit.Before` / `org.junit.After` 在桩里都存在 —— **能编译通过本身就是一半证据**；
 * 2. `MiniRunner` 真的**逐条**成对调用 Before → 用例体 → After（不是整类一次）；
 * 3. `@After` 里的断言失败会让用例判红（否则"清理没跑"会静默通过）。
 *
 * ## 为什么判据写成"不变式"而不是"顺序脚本"
 * `MiniRunner` 用 `getDeclaredMethods()` 取方法，**顺序未定义**，所以任何"A 先跑 B 后跑"的
 * 断言都会随机失败。这里改用与顺序无关的两个不变式：
 * - `@Before` 里：`afterRuns == beforeRuns - 1`（此前每个跑完的用例都恰好执行过一次 @After）；
 * - `@After` 里：事件序列恰好是 `[before, test]`（@Before 与用例体都执行过之后才轮到我）。
 *
 * 三种错法都会被抓住：完全不调 `@After`（下一条用例的 `@Before` 里 0 ≠ 1）、
 * `@After` 挪到用例体之前（序列变 `[before]` 或 `[before, after, test]`）、
 * `@After` 只在类结束时跑一次（同样在第二条用例的 `@Before` 里对不上）。
 *
 * ## 边界
 * 只描述 harness 自己，与 Android 无关 ⇒ `sh run-tests.sh` 与 Gradle 两套行为一致
 * （不存在 E5-6 的 mockable jar 差异）。"用例体失败后 @After 仍执行"无法在这里验证 ——
 * 需要一条**故意失败**的用例，那会污染 380 全绿的基线；该语义由桩的代码审阅保证。
 */
class R24SeamTest {

    companion object {
        /** 每个用例都是新实例，所以计数与事件表只能放 static（companion） */
        private var beforeRuns = 0
        private var afterRuns = 0
        private val events = mutableListOf<String>()
    }

    @Before
    fun setUp() {
        beforeRuns++
        // ★ 逐条执行的核心证据：此前每一条用例都必须已经跑过一次 @After。
        //   若桩不调 @After，第二个用例在这里看到 0 != 1 ⇒ 红。
        assertEquals("此前每个用例都必须执行过一次 @After", beforeRuns - 1, afterRuns)
        // 上一条用例的 @After 必须已清空事件表（清理真的发生了）
        assertTrue("上一条用例的 @After 未清理（残留=$events）", events.isEmpty())
        events += "before"
    }

    @After
    fun tearDown() {
        // ★ 能走到这里 = 用例体已返回；序列里必须有 @Before 与用例体各留下的一个标记。
        assertEquals("Before → 用例体 → After 的顺序", listOf("before", "test"), events.toList())
        events.clear()
        afterRuns++
    }

    @Test
    fun afterRunsStrictlyAfterTestBody() {
        events += "test"
    }

    /**
     * 第二条用例：与上一条同形，但它的价值在于让"逐条"这件事可证 ——
     * 若 @After 只在类级别跑一次，本条的 `@Before` 不变式必然失败。
     */
    @Test
    fun afterRunsForEveryTestCase() {
        events += "test"
    }
}
