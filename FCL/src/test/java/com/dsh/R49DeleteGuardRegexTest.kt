package com.dsh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R49 审计（R2-AUDIT）指出的判据缺陷：**源码文本判据的判别力边界**。
 *
 * ## 这组测试为什么存在
 *
 * `R43OverlayAtomicReplaceTest` 守的是"`writeLauncherPatchOverlay` 不许先删 target"。
 * 我第一版把它收紧成 `\b(?:target|targetFile|overlay|overlayFile)\s*\.\s*delete\s*\(`，
 * 并在 KDoc 里写"三种写法都要覆盖：target.delete() / targetFile.delete() / **先赋给变量再删**"。
 *
 * **审计实测：注释比实现强。** 那个正则对"先赋给变量再删"完全不命中 —— 一句
 * `val t = target; t.delete()` 就能把缺陷改回来而测试全绿。
 *
 * 这组测试把"判据库"本身钉住：给每种写法一个明确的期望，让判据的**能力边界**
 * 成为可执行的规格，而不是注释里的一句自夸。
 */
class R49DeleteGuardRegexTest {

    /**
     * 判据本体（与 `R43OverlayAtomicReplaceTest` 里用的**同一个**语义）。
     *
     * 设计目标有两条，缺一不可：
     * 1. **必须命中**删 target 的等价写法（缺陷复发要被抓到）
     * 2. **必须放行**删 tmp（S-08 之后失败路径要清残留，那是正确行为）
     *
     * 实现思路：先找出所有 `X.delete(` / `X.deleteIfExists(` 的**接收者表达式**，
     * 再看那个表达式里是否出现 target 系标识符。比"只匹配 `target.delete()`"能覆盖
     * 别名、安全调用、链式调用；又比"出现 delete( 就算缺陷"能放行 tmp。
     */
    private fun deletesTarget(body: String): Boolean {
        // 接收者表达式：允许 this. / 问号安全调用 / 链式 .toPath().toFile() 等
        val call = Regex("""([\w.$?()\[\], ]+?)\s*(?:\?\.)?\s*\.\s*delete(?:IfExists)?\s*\(""")
        return call.findAll(body).any { m ->
            val receiver = m.groupValues[1]
            // 接收者里只要出现 target 系标识符就算"删目标"
            Regex("""\b(?:target|targetFile|overlay|overlayFile)\b""").containsMatchIn(receiver) ||
                // 直接构造目标再删：File(..., LAUNCHER_PATCH_OVERLAY).delete()
                receiver.contains("LAUNCHER_PATCH_OVERLAY") ||
                // Files.deleteIfExists(target.toPath())：静态调用，接收者是 Files，靠参数判
                (receiver.endsWith("Files") && Regex("""\btarget\b""").containsMatchIn(body.substring(m.range.first, minOf(body.length, m.range.last + 60))))
        }
    }

    @Test
    fun catchesEveryWayOfDeletingTheTarget() {
        val mustCatch = listOf(
            "target.delete()",
            "targetFile.delete()",
            "this.target.delete()",
            "target?.delete()",
            "Files.deleteIfExists(target.toPath())",
            "File(hostInstance, LAUNCHER_PATCH_OVERLAY).delete()",
            "target.toPath().toFile().delete()"
        )
        mustCatch.forEach { snippet ->
            assertTrue(
                "★ 判据必须抓住删 target 的这种写法：<$snippet> —— " +
                    "抓不到就意味着「把缺陷改回来」能骗过测试",
                deletesTarget(snippet)
            )
        }
    }

    /**
     * 别名的情形单列一条并**明确标注判据的真实能力**。
     *
     * `val t = target; t.delete()` 需要数据流分析才能可靠判定。纯文本判据做不到，
     * 而"假装做到了"正是审计批评的"注释比实现强"。
     * 这里如实断言：**当前判据抓不到它**。这样后来人知道边界在哪，
     * 而不是相信一条根本不成立的 KDoc。
     */
    @Test
    fun aliasedDeleteIsAKnownGapNotAClaimedStrength() {
        val aliased = "val t = target\nt.delete()"
        assertFalse(
            "★ 如实登记：纯文本判据**抓不到**先赋别名再删（val t = target; t.delete()）—— " +
                "那需要数据流分析。这条断言存在的意义是**记录边界**，" +
                "防止下一个人按 KDoc 那句话以为它被覆盖了。" +
                "若将来有人真做了别名分析，这条会变红，那时把注释和它一起更新。",
            deletesTarget(aliased)
        )
    }

    @Test
    fun allowsDeletingTmp() {
        val allowed = listOf(
            "tmp.delete()",
            "runCatching { tmp.delete() }.onFailure { }",
            "tmp?.delete()"
        )
        allowed.forEach { snippet ->
            assertFalse(
                "★ 必须放行删 tmp：<$snippet> —— S-08 之后失败路径**必须**清 tmp，" +
                    "判成缺陷会让正确实现被误报",
                deletesTarget(snippet)
            )
        }
    }

    /**
     * 真实函数体的双向检查（这是最终落点）。
     */
    @Test
    fun shippedOverlayFunctionPassesBothDirections() {
        val src = mainSourceFile("core/DshInstances.kt").readText()
        val start = src.indexOf("fun writeLauncherPatchOverlay(")
        assertTrue("找不到 writeLauncherPatchOverlay", start >= 0)
        var depth = 0
        var end = start
        var i = src.indexOf('{', start)
        while (i < src.length) {
            if (src[i] == '{') depth++
            if (src[i] == '}') { depth--; if (depth == 0) { end = i; break } }
            i++
        }
        val body = blankComments(src.substring(start, end + 1))

        assertFalse("★ 出厂实现不许删 target，实际函数体：<$body>", deletesTarget(body))
        assertTrue(
            "前置：它必须仍然做原子替换（tmp.renameTo(target)）—— 没有 rename 就不是" +
                "「不删旧目标」，而是「不写 overlay」。实际函数体：<$body>",
            body.contains("renameTo(target)")
        )
    }

    // ===== 辅助 ==========================================================

    private fun blankComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                c == '/' && n == '/' -> { while (i < src.length && src[i] != '\n') { sb.append(' '); i++ } }
                c == '/' && n == '*' -> {
                    sb.append("  "); i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) {
                        sb.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    if (i + 1 < src.length) { sb.append("  "); i += 2 }
                }
                c == '"' -> {
                    sb.append(c); i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\' && i + 1 < src.length) { sb.append(src[i]); i++ }
                        if (i < src.length) { sb.append(src[i]); i++ }
                    }
                    if (i < src.length) { sb.append(src[i]); i++ }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun mainSourceFile(rel: String): java.io.File {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                java.io.File(dir, "FCL/FCL/src/main/java/com/dsh/$rel"),
                java.io.File(dir, "FCL/src/main/java/com/dsh/$rel"),
                java.io.File(dir, "src/main/java/com/dsh/$rel")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("找不到 com/dsh/$rel")
    }
}
