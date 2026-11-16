package com.dsh

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * 守卫：**变异测试（mutation test）的临时改动不得留在源码里。**
 *
 * ## 为什么需要这条
 * round32 修 N3 时，我在做变异验证的过程中把 `hasRenderableScheme` 改成过"恒放行"，
 * 中间态被并行运行的规划 Agent 读到，它据此报告"并发 Agent 已落地的 scheme 门完全失效"。
 * 那次最终恢复正确了，但**这类事故的代价极高**：一个 `return true` 留在一个安全门里，
 * 全套测试可能仍然绿（因为门本来就该放行正常 URL），症状会推迟到真机上才暴露。
 *
 * 变异测试是本仓验证"测试有没有判别力"的主要手段（见 `docs/framework/`），
 * 所以不能因为危险就不做 —— 只能用机械手段保证"做了必须收干净"。
 *
 * ## 判据
 * 全树（主源码 + 测试源码）搜索变异标记。标记必须写在**被变异的那一行**上，
 * 约定用全大写的 `MUTATION`（本文件自身除外，它只是提到这个词，不构成变异）。
 *
 * ★ BL-245 补充第二道判据（见 [noConstantShortCircuitOnGuardedCalls]）：**除了显式标记**，
 * 还要抓"没写标记、但形态上就是被短路掉"的退化 —— 典型是
 * `if (false && abortIfInstallCancelled(...))`。它连标记都不留（改动只有一个词），
 * 上一道按标记搜的守卫完全抓不到，而它让防护形同虚设、编译通过、测试可能全绿。
 */
class R32NoMutationResidueTest {

    /** 允许出现该标记的唯一文件（本守卫；它只是描述这个词的含义）。 */
    private val allowlist = setOf("R32NoMutationResidueTest.kt")

    @Test
    fun noMutationMarkersLeftInSourceTree() {
        val root = findRepoRoot()
        val offenders = ArrayList<String>()
        val banned = listOf("MUTATION", "MUTATED", "XXX_MUTANT")
        for (dir in listOf("FCL/src/main/java", "FCL/src/test/java")) {
            val base = File(root, dir)
            if (!base.isDirectory) continue
            base.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
                if (f.name in allowlist) return@forEach
                f.readLines().forEachIndexed { i, line ->
                    for (word in banned) {
                        if (line.contains(word)) {
                            offenders += "${f.relativeTo(root).invariantSeparatorsPath}:${i + 1}: $word"
                        }
                    }
                }
            }
        }
        assertTrue(
            "源码树里残留了变异测试标记 —— 这些通常是变异验证没恢复的痕迹，" +
                "留在安全门里后果严重（一个恒真的 return 能让整套测试仍然绿）。" +
                "请核对每一处并恢复正确实现：\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * 反向自检：守卫的扫描逻辑必须真的能发现标记。
     *
     * 如果 `contains` 写错或目录走空，上面那条会**永远绿**（假护栏）。
     * 这条用一个真实存在的标记串自证，同时确认 allowlist 机制生效。
     */
    @Test
    fun guardItselfIsNotVacuous() {
        val root = findRepoRoot()
        val base = File(root, "FCL/src/main/java")
        assertTrue("主源码目录必须存在，否则上面的守卫扫了个空", base.isDirectory)
        val count = base.walkTopDown().count { it.isFile && it.name.endsWith(".kt") }
        assertTrue(
            "主源码目录里应当有大量 .kt 文件（实际 $count）；为 0 说明路径算错，守卫是假护栏",
            count > 50
        )
        // allowlist 里必须是本文件自己，不能顺手放宽到别的文件
        assertTrue("allowlist 只允许本守卫文件名", allowlist.size == 1)
    }

    // ==================================================================
    // 二、常量短路：把安全关键调用写成"永远不执行 / 永远执行"
    // ==================================================================
    //
    // ★★ 为什么 `if (false && guardCall(...))` 也算「变异残留」（BL-245 的现场）
    //
    // 变异测试的常规做法是"把防护删掉/短路掉，看测试会不会红"。为了验证完能干净地还原，
    // 有时会图省事只加一个 `false &&`（而不是整行删掉再加回去）—— 改动只有一个词，
    // 还原也只要删一个词。代价是：**这行代码从此形同虚设，但一切都看不出来**：
    //   · 编译通过（`false && x` 是合法表达式，`x` 甚至还会被编译器检查类型）；
    //   · 测试可能仍然全绿（BL-245 就是：那个端到端用例只 contains 日志前缀，
    //     三道检查点前缀相同，另两道把这句断言"喂"绿了）；
    //   · 代码评审也容易滑过去 —— 它长得像一句正常的前置判断，不是在"关掉"什么。
    // 于是防护"看起来在、实际不在"，这是比根本没有防护更坏的状态：review 与测试都
    // 会替它作证。`if (true || ...)` 是同一个东西的镜像（永远执行，判据不再起作用）。
    //
    // 判据刻意**只认这两种形态 + 只针对取消检查点**（不是把 `if (false` 一律禁掉）：
    // 泛化的"禁止常量条件"会误伤合法的注释/文档/极端兜底写法，而这里要钉的是
    // "**安全关键调用**被短路成常量"这一个具体形状 —— 它没有任何合法的用途。

    /** 被保护的安全关键调用（目前只有 fork 前的取消检查点；以后新增的往这里加）。 */
    private val guardedCalls = listOf("abortIfInstallCancelled")

    /** `if (false && <guardedCall>` / `if (true || <guardedCall>`（允许换行与任意空白） */
    private val constantShortCircuitPatterns: List<Pair<String, Regex>> = listOf(
        "if (false && ...)" to Regex("""if\s*\(\s*false\s*&&\s*(${guardedCalls.joinToString("|")})\b"""),
        "if (true || ...)" to Regex("""if\s*\(\s*true\s*\|\|\s*(${guardedCalls.joinToString("|")})\b""")
    )

    /**
     * 源码里**不许**把安全关键调用短路成常量：`if (false && abortIfInstallCancelled(...))` /
     * `if (true || abortIfInstallCancelled(...))`。
     *
     * 为什么这条必须存在（而不是靠"端到端测试会红"）：BL-245 的实测就是端到端测试**没有红**。
     * 那句 `false &&` 让入口取消检查点永不执行，`TOTAL=1027 FAILED=0` 依然全绿 —— 因为日志
     * 断言只匹配前缀、另两道检查点打的是同一个前缀。也就是说这个形态**能穿过当时的全部测试**，
     * 只能靠一条机械守卫来兜。
     *
     * ★ 扫描前先**清空注释**：本仓的 KDoc 里成段讨论这些标识符（连"曾写成 `false &&` 造成
     * BL-245"这件事本身都写在注释里），不剥注释的话守卫会被自己的文档命中 —— 反过来，
     * 也说明"看到这个词"与"这里真的短路了"是两回事，必须只看可执行代码。
     */
    @Test
    fun noConstantShortCircuitOnGuardedCalls() {
        val root = findRepoRoot()
        val offenders = ArrayList<String>()
        var scanned = 0
        for (path in sourceFiles(root)) {
            scanned++
            val code = blankComments(File(root, path).readText())
            for ((shape, re) in constantShortCircuitPatterns) {
                re.findAll(code).forEach { m ->
                    offenders += "$path:${lineOf(code, m.range.first)}: $shape -> ${m.value.trim()}"
                }
            }
        }
        assertTrue("前置：必须真的扫到了源码文件（实际 $scanned 个）", scanned > 50)
        assertTrue(
            "★ 源码里出现了把安全关键调用短路成常量的形态（${constantShortCircuitPatterns.joinToString { it.first }}）：\n" +
                offenders.joinToString("\n") + "\n" +
                "这类改动**编译通过、测试可能全绿、评审也容易滑过去**，但它让防护形同虚设 —— " +
                "BL-245 就是这么漏的（入口取消检查点被 `false &&` 短路，端到端用例靠另两道检查点的" +
                "同一句日志前缀蒙过去）。请删掉常量条件，恢复真正的判据。",
            offenders.isEmpty()
        )
    }

    /**
     * 反向自检：上面那条守卫的正则必须真的能抓住这两种形态。
     *
     * 如果正则写错（比如 `&&` 忘了转义、`\s*` 漏了换行），`noConstantShortCircuitOnGuardedCalls`
     * 会**永远绿** —— 那正好是它要防的事（假护栏比没有护栏更危险）。
     */
    @Test
    fun constantShortCircuitGuardIsNotVacuous() {
        // 每个形态配**自己的**样本：`false &&` 的正则不该命中 `true ||` 的样本（反之亦然），
        // 所以样本必须按形态分开给，不能拿一个样本去考两个正则。
        val samples = listOf(
            Triple(
                "if (false && ...)",
                """if (false && abortIfInstallCancelled(instance, myJob, "entry")) return false""",
                "if (false &&\n            abortIfInstallCancelled(instance, myJob, \"entry\")) return false"
            ),
            Triple(
                "if (true || ...)",
                """if (true || abortIfInstallCancelled(instance, myJob, "entry")) return false""",
                "if (true ||\n            abortIfInstallCancelled(instance, myJob, \"entry\")) return false"
            )
        )
        val goodSample = """if (abortIfInstallCancelled(instance, myJob, "entry")) return false"""
        assertEquals("形态与样本必须一一对应（守卫加了新形态，这里就得补样本）", constantShortCircuitPatterns.size, samples.size)
        samples.forEachIndexed { idx, (shape, flat, wrapped) ->
            val re = constantShortCircuitPatterns.first { it.first == shape }.second
            assertTrue("守卫正则（$shape）必须能命中退化形态：$flat", re.containsMatchIn(flat))
            assertTrue("守卫正则（$shape）必须能命中折行形态：$wrapped", re.containsMatchIn(wrapped))
            assertFalse("守卫正则（$shape）不许误伤正常写法：$goodSample", re.containsMatchIn(goodSample))
        }
        // 换行/空白容错：真实的常量短路常被写成 `if (false  &&  ...)` 或多行
        assertTrue(
            "守卫正则必须容忍任意空白（`if (false   &&`）：否则改成 `false  &&` 就绕过守卫了",
            constantShortCircuitPatterns.first().second.containsMatchIn(
                """if (false   &&   abortIfInstallCancelled(instance, myJob, "entry")) return false"""
            )
        )
    }

    /**
     * 反向自检（★ 这条防的是**漏报**，比误报更危险）：[blankComments] 不许把字符串里的
     * 行注释起始符（斜杠斜杠）/ 块注释起始符（斜杠星号）当成注释开头、把同一行/后面一大段的
     * **真实代码**一起清掉。
     *
     * 为什么必须有：本仓源码里有 `"http://"` / `"https://"` 这类字符串（`DshInstaller` 的
     * URL 前缀判定），三引号正则里也可能出现斜杠星号。扫描器若不做字面量感知，
     * `val a = line.indexOf("http://")` 会被它读成"从这里开始是注释"，于是这一行剩下的部分
     * 与块注释时后面**整段**都被清空 —— 守卫在那段里永远扫不到东西，**静默变成假护栏**。
     *
     * ★ 本 KDoc 刻意**不写出**那两个起始符的连写形态：Kotlin 的块注释可以嵌套，写出来会在这里
     * 打开一个嵌套注释，把下面整段代码一起吞掉（实测踩过一次：报 `syntax error: Unclosed comment`）。
     */
    @Test
    fun commentStripperDoesNotEatStringLiterals() {
        // 用普通字符串显式拼出样本，避免三引号互相嵌套导致"样本本身写错"（那样这条自检也会假绿）
        val q3 = "\"\"\""
        val src = listOf(
            "fun f(line: String) {",
            "    val a = line.indexOf(\"http://\")   // 真注释后面还有代码不算",
            "    val b = if (false && abortIfInstallCancelled(x)) 1 else 2",
            "    val r = Regex($q3",
            "        /\\* not a comment start",
            "    $q3)",
            "    val c = if (true || abortIfInstallCancelled(y)) 3 else 4",
            "}"
        ).joinToString("\n")
        val stripped = blankComments(src)
        // 长度与换行必须原样保留（行号才能对得上）
        assertEquals("剥注释不许改变长度", src.length, stripped.length)
        assertEquals("剥注释不许改变行数", src.count { it == '\n' }, stripped.count { it == '\n' })
        // 字符串内容本身不是我们要关心的，关键是：两处常量短路都在**可执行代码**里，必须都还在
        for (re in constantShortCircuitPatterns.map { it.second }) {
            assertTrue(
                "★ 剥注释把真实代码吃掉了（字符串里的 // 、/* 被当成注释开头）：残留可执行代码=\n$stripped\n" +
                    " —— 这会让守卫漏报，静默变成假护栏",
                re.containsMatchIn(stripped)
            )
        }
        // 行注释确实被清掉了（不是"什么都没剥"，那样守卫会被自己的文档命中）
        assertFalse("行注释必须真的被清掉", stripped.contains("真注释后面还有代码不算"))
    }

    // --- 扫描工具 ---------------------------------------------------------

    /** 要扫的源码文件（相对仓库根的路径） */
    private fun sourceFiles(root: File): List<String> {
        val out = ArrayList<String>()
        for (dir in listOf("FCL/src/main/java", "FCL/src/test/java")) {
            val base = File(root, dir)
            if (!base.isDirectory) continue
            base.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
                if (f.name in allowlist) return@forEach
                out += f.relativeTo(root).invariantSeparatorsPath
            }
        }
        return out
    }

    /**
     * 把注释内容替换成**空格**（保留换行与长度），只留可执行代码。
     *
     * ★ 不用"直接删掉注释"：删掉会把后面的行号整体前移，报出来的行号就对不上了。
     * 逐字符替换成空格则长度与换行都不变 ⇒ [lineOf] 算出的行号就是原文件的行号。
     *
     * ★ 必须**先认字符串再认注释**：源码里有大量 `"http://"` / `"https://"` 这种字符串
     * （`DshInstaller` 的 URL 前缀判定），不做字面量感知的扫描器会把它里面的行注释起始符当成
     * 注释开头，**把该行后面的真实代码一起清掉** —— 守卫于是可能漏报（更糟：任何含块注释起始符
     * 的字符串会把后面一大段代码整段"吃掉"，而此时守卫静默变绿）。三引号原始串（本仓大量用来
     * 写正则）里还可能有引号，所以它必须单独整段跳过。
     *
     * 注释分支放在字符串分支**之前**：行注释里出现奇数个撇号/引号（本仓 KDoc 里很常见，例如
     * 「请检查网络」这种被写成 ASCII 撇号的引号）时，不会被误当成字符/字符串字面量的开头。
     *
     * ⚠️ 本 KDoc 里**不许写出**块注释起始符的连写形态：Kotlin 的块注释可以嵌套，写一次就会在
     * 这里打开一个嵌套注释，把下面整段代码一起吞掉（实测踩过两次：报 `Unclosed comment` 与
     * `Expecting member declaration`）。同理，下面行注释里也不写注释**结束符**的连写。
     */
    private fun blankComments(src: String): String {
        val out = StringBuilder(src)
        fun blank(from: Int, to: Int) { for (k in from until to) if (out[k] != '\n') out[k] = ' ' }
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                // 行注释：到行尾
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    val start = i
                    while (i < src.length && src[i] != '\n') i++
                    blank(start, i)
                }
                // 块注释：跳到注释结束符为止（结束符的连写不写在这里，见上方 KDoc 的提醒）
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    val start = i
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i = minOf(i + 2, src.length)
                    blank(start, i)
                }
                // 三引号原始串：内部不认转义、不认注释，整段跳过
                c == '"' && src.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < src.length && !src.startsWith("\"\"\"", i)) i++
                    i = minOf(i + 3, src.length)
                }
                // 普通字符串：跳到收尾引号（处理转义），不跨行
                c == '"' -> {
                    i++
                    while (i < src.length && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    if (i < src.length && src[i] == '"') i++
                }
                // 字符字面量：同上
                c == '\'' -> {
                    i++
                    while (i < src.length && src[i] != '\'' && src[i] != '\n') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    if (i < src.length && src[i] == '\'') i++
                }
                else -> i++
            }
        }
        return out.toString()
    }

    /** 字符下标 → 1 起的行号 */
    private fun lineOf(src: String, index: Int): Int {
        var line = 1
        for (k in 0 until minOf(index, src.length)) if (src[k] == '\n') line++
        return line
    }

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            depth++
        }
        fail("找不到仓库根（cwd=${System.getProperty("user.dir")}）")
        return File(".")
    }
}
