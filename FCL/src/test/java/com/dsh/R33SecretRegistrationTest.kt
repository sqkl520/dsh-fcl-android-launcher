package com.dsh

import com.dsh.core.DshLogBus
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * round33 / `registerSecret` 的两个相邻缺陷（`N3-redteam.md` 的 residual_risk 里登记）。
 *
 * ## 缺陷 1：单字符 secret 会把整份日志刷成 `***`
 * `registerSecret` 只挡住空串，`sanitize` 对**每个**已注册串做无差别 `out.replace(t, "***")`。
 * 于是注册一个单字符 token（例如 `a`）之后，此后该进程里**任何含该字符的行**都会被打码 ——
 * 日志失去可读性，等于给攻击者一个"让排障失效"的开关。
 *
 * 真实触发路径：伪造一行 `dsh web: https://evil.example:443/?token=a`，
 * `onProcessLine` 会 `UrlScanner.tokenOf` 取出 `a` 并调用 `registerSecret("a")`。
 * 而 `registeredSecrets` 是进程级、**永不清理**的集合（`clear()` 只清 buffer/levels）。
 *
 * ## 缺陷 2：`clear()` 不清 `registeredSecrets`
 * `clear()` 的语义是"把日志清干净"，但已注册的 secret 仍然生效。
 * 这两个缺陷叠加的结果是：一次污染永久生效，且没有复位手段。
 *
 * ## 断言的取向
 * 本文件只钉**行为**（sanitize 的输出长什么样、clear 之后还生不生效），
 * 不钉具体实现（比如"阈值必须是 8"）—— 阈值是可调的产品决策，
 * 但"单字符不该打码整份日志"和"clear 应该给出复位手段"是不变式。
 */
class R33SecretRegistrationTest {

    /**
     * 每条用例开始前复位。
     *
     * 为什么必须有：`registeredSecrets` 是**进程级**集合，harness 把全部测试类跑在同一个
     * JVM 里 —— 上一轮跑这条类的 `realTokensStillGetMasked` 注册的真 token 会留在集合里，
     * 让"单字符不该打码"那条用例拿到一个已经被打过码的结果而**假绿**。
     *
     * 这本身就是缺陷 2 的现场演示：没有复位手段时，跨用例污染无法避免。
     */
    @Before
    fun resetSecrets() {
        DshLogBus.clearForTestOfRegisteredSecrets()
    }

    // ---------------------------------------------------------- 缺陷 1

    /**
     * **缺陷本体**：注册单字符 secret 后，普通日志被无差别打码。
     *
     * 判别力：把 `registerSecret` 的长度门槛去掉，这条立刻变红
     * （断言里的 `abc` 会被打成 `***bc` 或类似形态）。
     */
    @Test
    fun singleCharSecretMustNotMaskEveryOccurrenceInLog() {
        DshLogBus.registerSecret("a")
        val line = "01:00:00  [runtime] launch target=instance version=0.2.1-rc.2"
        val out = DshLogBus.sanitize(line)
        assertEquals(
            "注册单字符 secret 后不得改动这行普通日志。实际输出=$out\n" +
                "（整段被打码 = 攻击者可用一行伪造日志让此后所有日志失去可读性）",
            line,
            out
        )
    }

    /** 两字符、三字符同样不该被当作 secret —— 太短的串会命中大量正常文本。 */
    @Test
    fun veryShortSecretsAreRejected() {
        val samples = listOf(
            "ab" to "01:00:00  [runtime] able to start about 3 times",
            "abc" to "01:00:00  [runtime] abcdefg",
            "" to "01:00:00  [runtime] plain"
        )
        for ((secret, line) in samples) {
            DshLogBus.registerSecret(secret)
            assertEquals(
                "secret=\"$secret\" 太短，不得改写正常日志。实际=${DshLogBus.sanitize(line)}",
                line,
                DshLogBus.sanitize(line)
            )
        }
    }

    /**
     * **反向**：足够长的真 token 仍然必须被打码。
     *
     * 这条防止"为了修缺陷 1 把 registerSecret 整个架空"——那样泄漏面会重新打开。
     */
    @Test
    fun realTokensStillGetMasked() {
        val realToken = "et5w4-xSCJpP95w98yyXZOJOxkn-EOz1JAyS8T6eKqU"
        DshLogBus.registerSecret(realToken)
        val line = "prefix $realToken suffix"
        val out = DshLogBus.sanitize(line)
        assertTrue("真 token 必须被打码，实际=$out", !out.contains(realToken))
        assertEquals("只有 token 那一段被打码，前后文保留", "prefix *** suffix", out)
    }

    /** `token=` 正则那条通路**不受**本修复影响 —— 哪怕 token 很短，URL 里的也要打码。 */
    @Test
    fun tokenParamIsAlwaysMaskedRegardlessOfLength() {
        val line = "dsh web: http://127.0.0.1:3080/?token=a"
        val out = DshLogBus.sanitize(line)
        assertTrue(
            "URL 里的 token= 参数无论长短都必须打码（这是结构化的，不依赖阈值）。实际=$out",
            !out.contains("token=a")
        )
    }

    // ---------------------------------------------------------- 缺陷 2

    /**
     * `clear()` 之后必须存在一个**复位手段**：清空已注册 secret。
     *
     * 本用例不规定 API 名字，只要求"clear 之后再 sanitize 同一行，结果与从未注册时一致"。
     * 若实现选择"clear 一并清 secret"，这条过；若选择"另开 clearSecretsForTest"，
     * 测试就调那个 —— 断言对象是**语义**，不是名字。
     */
    @Test
    fun clearRestoresSanitizeToPristineState() {
        val token = "AAAAAAAAAAAAAAAAAAAAAAAA"
        val line = "prefix $token suffix"
        DshLogBus.registerSecret(token)
        assertEquals("注册后必须打码", "prefix *** suffix", DshLogBus.sanitize(line))

        DshLogBus.clearForTestOfRegisteredSecrets()

        assertEquals(
            "复位之后 sanitize 必须回到未注册状态。实际=${DshLogBus.sanitize(line)}\n" +
                "（当前实现里 registeredSecrets 是进程级且永不清理 —— 一次污染永久生效）",
            line,
            DshLogBus.sanitize(line)
        )
    }
}
