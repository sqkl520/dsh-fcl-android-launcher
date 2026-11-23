package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * round49 / RELIABILITY 审查：**三个"失败被静默吞掉"的点**。
 *
 * ## 这组测试在守什么
 *
 * R49 的可靠性审查（`docs/reports/round49/agents/RELIABILITY.md`）用口径 C
 * （"异常既不进日志、也不进 UI、也不进状态、也不 return/throw"）筛出 9 个可行动站点。
 * 这里钉住其中三个**最严重的**，它们的共同特征是：
 *
 * > 失败时不是"少了一条日志"，而是**上层会据此得出错误结论**或**现场完全不可诊断**。
 *
 * | 点 | 失败时的假象 |
 * |---|---|
 * | `killBlocking` 的 SIGTERM/SIGKILL | 信号没发出去，上层只看到"停止后 proot 仍存活"，没有原因 |
 * | `writeFileAtomically` 的备份 | 备份没写成，后续保存**成功**，`.bak` 里是更旧的内容，用户以为回滚点是最近的 |
 * | 死 pid / token 侧车删除 | 删除失败，日志却宣布 "removed stale pid file and token sidecar" |
 *
 * ## 为什么是源码文本断言而不是运行时
 *
 * 三个点都需要 Android `Context` / `android.os.Process.sendSignal` / 真实磁盘权限失败才能触发，
 * JVM 单测跑不到。所以这里钉**代码形态**：断言 `onFailure`/返回值判断存在，且断言反面
 * （裸 `runCatching`）不存在。
 *
 * 这类断言的弱点是"能用另一种写法绕过"（比如把 `runCatching` 换成 `try/catch{}` 空块也能骗过
 * 第一条）。所以每条都配了**反面断言**，让"最省事的回退"变成 obviously wrong。
 */
class R49SilentFailureTest {

    private val runtime by lazy { mainSourceFile("core/DshRuntime.kt") }
    private val instances by lazy { mainSourceFile("core/DshInstances.kt") }

    // ===== 1. killBlocking 的信号失败必须留痕 ==============================

    /**
     * `killBlocking` 里两条 `sendSignal` 都必须带 `onFailure`。
     *
     * ## 为什么这条重要
     *
     * 同文件的 `killStale`（`ProcessUtil` 内）在 BL-046 那一轮就补过 `onFailure`，
     * `ProotProcessExecutor` 也有 —— **唯独这条主停止链漏了**。所以这不是"没想到"，
     * 是"改的时候漏了一处"。漏的代价：停止失败时上层 `verifyProotGone` 只打印
     * "停止后 proot 仍存活（两段宽限后仍未退出）" —— 只有结果、没有原因。
     *
     * 变异验证：删掉任一条 `.onFailure { ... }` ⇒ 本条变红。
     */
    @Test
    fun killBlockingLogsBothSignalFailures() {
        val src = runtime.readText()
        val start = src.indexOf("fun killBlocking(")
        assertTrue("找不到 killBlocking 定义（源码结构变了？）", start >= 0)
        // 取函数体：到下一个同缩进的 fun 声明为止（killBlocking 后面紧跟的是 logFor）
        val body = src.substring(start, minOf(src.length, start + 4000))

        val sigTerm = Regex("""sendSignal\(pid\.toInt\(\), 15\)\s*\}""")
        val sigKill = Regex("""sendSignal\(pid\.toInt\(\), 9\)\s*\}""")
        assertTrue("找不到 SIGTERM 发送点（pid, 15）", sigTerm.containsMatchIn(body))
        assertTrue("找不到 SIGKILL 发送点（pid, 9）", sigKill.containsMatchIn(body))

        // 两条 sendSignal 都必须紧跟 onFailure（中间可能夹行尾注释 `// SIGTERM`，以及注释行）
        val withHandler = Regex(
            """sendSignal\(pid\.toInt\(\), (?:15|9)\)\s*\}.*?\n\s*\.onFailure""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).findAll(body).count()
        assertEquals(
            "★ killBlocking 的两条 sendSignal 都必须接 .onFailure 记录失败；" +
                "实际命中 $withHandler 处（应为 2）。不接的后果：停止失败时只剩「仍存活」这句，" +
                "查不出是 SecurityException 还是 SELinux 拦的",
            2, withHandler
        )
    }

    /**
     * 反面断言：`killBlocking` 内不许再出现**没有 `.onFailure` 收尾**的 sendSignal。
     *
     * ★ 注意这里**不能**只判「这一行长什么样」：修复后的写法第一行仍然是
     *   `runCatching { ...sendSignal(...) } // SIGTERM`，与修复前**逐字相同**，
     *   区别全在下一行有没有 `.onFailure`。所以判据是"这一行之后紧跟的不是 .onFailure"。
     *   （第一版就是只判单行，于是把修好的代码判成了没修 —— 假红。）
     */
    @Test
    fun killBlockingHasNoBareSendSignal() {
        val src = runtime.readText()
        val start = src.indexOf("fun killBlocking(")
        val body = src.substring(start, minOf(src.length, start + 4000))
        val lines = body.lines()

        var bareCount = 0
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.contains("sendSignal(pid.toInt(), 15)") && !line.contains("sendSignal(pid.toInt(), 9)")) continue
            // 找下一条非空、非注释行
            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].trimStart().startsWith("//"))) j++
            val next = lines.getOrNull(j)?.trimStart() ?: ""
            if (!next.startsWith(".onFailure")) bareCount++
        }
        assertEquals(
            "★ killBlocking 里每条 sendSignal 后面都必须紧跟 .onFailure；" +
                "没有收尾的有 $bareCount 条（应为 0）",
            0, bareCount
        )
    }

    // ===== 2. 写前备份失败必须留痕 ========================================

    /**
     * `writeFileAtomically` 的备份失败要记日志。
     *
     * ## 为什么这条是"注释与实现矛盾"的典型
     *
     * 那个函数里的注释原文是：
     * > 备份失败**不**中止写入：备份是保险，不是前提。
     * > 但也不能静默 —— 调用方（编辑器）会把异常抛给用户。
     *
     * 而实现是裸 `runCatching { target.copyTo(backup, overwrite = true) }` —— 一条都不成立。
     * 实际后果：备份失败后**写入仍然成功**，用户看到"保存成功"，此后 `.bak` 里是更旧的内容，
     * 真回滚时拿到的不是"上一次保存前"的版本，且没有任何线索说明为什么。
     *
     * 变异验证：把 `.onFailure {}` 去掉 ⇒ 本条变红。
     */
    @Test
    fun atomicWriteLogsBackupFailure() {
        val src = instances.readText()
        val start = src.indexOf("fun writeFileAtomically(")
        assertTrue("找不到 writeFileAtomically（源码结构变了？）", start >= 0)
        val body = src.substring(start, minOf(src.length, start + 2500))

        assertTrue(
            "★ 写前备份失败必须留痕（注释自己写着「不能静默」）；" +
                "找不到 copyTo(backup) 后面接的 .onFailure",
            Regex(
                """target\.copyTo\(backup, overwrite = true\)\s*\}.*?\n\s*\.onFailure""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            ).containsMatchIn(body)
        )
        // 反面：copyTo 那一行之后必须紧跟 .onFailure（同样不能只判单行 —— 修复前后那一行逐字相同）
        val lines = body.lines()
        var bareCopy = 0
        for (i in lines.indices) {
            if (!lines[i].contains("target.copyTo(backup, overwrite = true)")) continue
            var j = i + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].trimStart().startsWith("//"))) j++
            if (!(lines.getOrNull(j)?.trimStart() ?: "").startsWith(".onFailure")) bareCopy++
        }
        assertEquals(
            "★ 备份失败必须被观测：copyTo 之后紧跟 .onFailure 的写法只应有 1 处、裸写法应为 0；" +
                "实测裸写法 $bareCopy 处",
            0, bareCopy
        )
    }

    // ===== 3. 死 pid / 侧车删除的结果必须反映到日志 =========================

    /**
     * `deleteTokenUrlFile` 必须**返回真实结果**，不能悄悄吞掉。
     *
     * ## 为什么
     *
     * 它原来返回 `Unit` 且内部裸 `runCatching`，于是"清理失败"与"清理成功"在调用方
     * 眼里完全一样。认领跳过（adoption skip）路径紧跟着要写一行
     * "removed stale pid file and token sidecar" —— 删除失败时**那行就是在说谎**。
     *
     * 文件本来不存在算成功（幂等目标态），所以判据是 `!f.isFile || f.delete()`，
     * 不是裸 `f.delete()`（后者对不存在的文件返回 false，会被误判成失败）。
     */
    @Test
    fun deleteTokenUrlFileReportsRealOutcome() {
        val src = runtime.readText()
        val start = src.indexOf("fun deleteTokenUrlFile(")
        assertTrue("找不到 deleteTokenUrlFile", start >= 0)
        val header = src.substring(start, minOf(src.length, start + 400))

        assertTrue(
            "★ deleteTokenUrlFile 必须返回 Boolean（调用方要靠它决定日志措辞）；" +
                "签名里找不到 「: Boolean」",
            header.lineSequence().first().contains(": Boolean")
        )
        assertTrue(
            "★ 判据必须是「文件不存在也算成功」：`!f.isFile || f.delete()`；" +
                "裸 `f.delete()` 对不存在的文件返回 false，会误报清理失败",
            header.contains("!f.isFile || f.delete()")
        )
    }

    /**
     * 认领跳过路径的日志必须按**实际结果**分别措辞，不能无条件宣布"已清理"。
     *
     * 这是 BL-250 之外的另一处"日志说谎"：原来那行无条件写
     * `removed stale pid file and token sidecar`，无论删除成没成。
     */
    @Test
    fun adoptionSkipLogReflectsActualCleanupResult() {
        val src = runtime.readText()
        assertTrue(
            "★ 认领跳过路径要按实际结果分两种措辞（成功 / INCOMPLETE）",
            src.contains("but cleanup was INCOMPLETE")
        )
        // 成功那行必须是条件拼接，不能是死字符串
        assertTrue(
            "★ 成功分支的 'removed stale ...' 必须由 pidGone/sidecarGone 条件拼出来",
            Regex("""listOfNotNull\([\s\S]{0,400}?pidGone""").containsMatchIn(src)
        )
    }

    // ===== 辅助 ============================================================

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
}
