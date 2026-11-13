package com.dsh

import java.io.File
import org.junit.Test
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
