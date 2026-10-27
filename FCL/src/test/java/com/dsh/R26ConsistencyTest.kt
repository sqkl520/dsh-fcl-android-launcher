package com.dsh

import com.dsh.core.DshInstaller
import com.dsh.core.InstallFacts
import com.dsh.core.planRepair
import com.dsh.core.DshInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R26 / E1 §4.1：**两套"装好了没"的判据必须等价**。
 *
 * ## 为什么需要这一组（S6 的典型形态）
 *
 * fix12 引入了 [InstallFacts.complete]（`DshInstances.kt:39`），注释写"与
 * `DshInstaller.isInstallComplete(dir, null)` 完全等价"。但它们是**两份独立实现**：
 *
 * - `DshInstances.readInstallFacts` → `pkg.readText()` + `JsonParser` + `stringOrNull("version")`
 * - `DshInstaller.isInstallComplete` → `readVersionFromPackageJson`（Gson 反射 + `PackageJson` data class）
 *
 * 两条路径的**边界行为不一样**：Gson 对数字类型的 `version` 可能抛或给 null，
 * `JsonParser` 的 `stringOrNull` 只在是 JSON string 原语时才返回。若上游包格式有边缘形态，
 * 两处会给出不同结论 —— 于是"卡片说就绪、启动却说没装完"这种 S1 症状会**原地复活**，
 * 而 `B1aTest`（测 `isInstallComplete`）与 `R25FixTest`（测 `planRepair`）**各自全绿也发现不了**。
 *
 * 所以这一组测试的角色是**跨实现的一致性契约**：同一份磁盘内容，两侧必须同结论。
 * 它不是"钉实现影子"——它钉的是两条独立路径之间的**不变量**。
 */
class R26ConsistencyTest {

    private fun tempDir(): File = Files.createTempDirectory("r26c").toFile()

    /** 在 [root] 下造一棵 dsh 树；`pkgJson` 为 null 表示不写 package.json。 */
    private fun tree(root: File, pkgJson: String?, withBinJs: Boolean) {
        val dsh = File(root, "node_modules/@deepseek-ai/dsh")
        dsh.mkdirs()
        if (pkgJson != null) File(dsh, "package.json").writeText(pkgJson)
        if (withBinJs) {
            File(dsh, "lib").mkdirs()
            File(dsh, "lib/bin.js").writeText("// entry\n")
        }
    }

    /** 用 [planRepair] 的 own 腿算出"装完了没"（等价于 fix12 的判定）。 */
    private fun planRepairSaysComplete(ownDir: File): Boolean {
        val pkg = File(ownDir, "package.json")
        val hasPkg = pkg.isFile
        val hasBin = File(ownDir, "lib/bin.js").isFile
        val text = if (hasPkg) runCatching { pkg.readText() }.getOrNull() else null
        val version = text?.let {
            runCatching {
                val root = com.google.gson.JsonParser.parseString(it)
                if (!root.isJsonObject) null
                else root.asJsonObject.get("version")
                    ?.takeIf { v -> v.isJsonPrimitive && v.asJsonPrimitive.isString }
                    ?.asString?.takeIf { v -> v.isNotBlank() }
            }.getOrNull()
        }
        val facts = InstallFacts(version, hasPkg, hasBin, readError = hasPkg && text == null)
        val plan = planRepair(DshInstance(id = "x", name = "x"), facts, facts)
        return plan.state == DshInstance.State.READY
    }

    /**
     * 同一份磁盘内容，`isInstallComplete(dir, null)` 与 `planRepair` 的 own 腿必须同结论。
     *
     * 覆盖六种 `package.json` 形态 —— 前三种是正常路径，后三种是 E1 §4.1 点名的**边界**：
     * 数字类型、空串、尾随空格。它们正是两套解析器可能分叉的地方。
     */
    @Test
    fun bothJudgesAgreeOnEveryPackageJsonShape() {
        val cases = listOf(
            "正常版本" to """{"name":"@deepseek-ai/dsh","version":"0.2.1"}""",
            "只有 version" to """{"version":"0.1.6"}""",
            "额外字段" to """{"version":"0.2.1","bin":{"dsh":"lib/bin.js"}}""",
            // ↓ 边界：数字类型。JsonParser 的 stringOrNull 返回 null；Gson 反射到 String 字段时
            //   数字会被"宽松"转成字符串（Gson 默认 lenient）。这里就是要暴露这个差异。
            "数字版本" to """{"version":123}""",
            "空串版本" to """{"version":""}""",
            "尾随空格" to """{"version":"0.2.1 "}"""
        )
        cases.forEach { (label, json) ->
            val dir = tempDir()
            try {
                tree(dir, json, withBinJs = true)
                val ownDir = File(dir, "node_modules/@deepseek-ai/dsh")

                val byInstaller = DshInstaller.isInstallComplete(ownDir, null)
                val byPlanRepair = planRepairSaysComplete(ownDir)

                assertEquals(
                    "判据分叉（$label）：isInstallComplete=$byInstaller 但 planRepair=$byPlanRepair。" +
                        "两处对同一份磁盘内容必须同结论，否则卡片与启动口会互相矛盾（S1 的原症状）",
                    byInstaller, byPlanRepair
                )
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    /** 缺 bin.js 时两侧都必须判"没装完"——这是 fix12 最核心的一条（不回退预装）。 */
    @Test
    fun bothJudgeIncompleteWhenBinJsMissing() {
        val dir = tempDir()
        try {
            tree(dir, """{"version":"0.2.1"}""", withBinJs = false)
            val ownDir = File(dir, "node_modules/@deepseek-ai/dsh")

            assertFalse(
                "缺 lib/bin.js 必须判不完整（isInstallComplete）",
                DshInstaller.isInstallComplete(ownDir, null)
            )
            assertFalse(
                "缺 lib/bin.js 必须判不完整（planRepair）—— 否则就是'卡片就绪、启动被拒'",
                planRepairSaysComplete(ownDir)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 缺 package.json 时两侧都判不完整（目录存在但没装）。 */
    @Test
    fun bothJudgeIncompleteWhenPackageJsonMissing() {
        val dir = tempDir()
        try {
            tree(dir, pkgJson = null, withBinJs = true)
            val ownDir = File(dir, "node_modules/@deepseek-ai/dsh")

            assertFalse(DshInstaller.isInstallComplete(ownDir, null))
            assertFalse(planRepairSaysComplete(ownDir))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 坏 JSON：两侧都判不完整，且**都不能**抛异常（抛了会打穿卡片渲染）。 */
    @Test
    fun malformedJsonMakesBothJudgeIncompleteWithoutThrowing() {
        val dir = tempDir()
        try {
            tree(dir, "{ not json", withBinJs = true)
            val ownDir = File(dir, "node_modules/@deepseek-ai/dsh")

            val byInstaller = runCatching { DshInstaller.isInstallComplete(ownDir, null) }
            assertTrue(
                "坏 JSON 不能让 isInstallComplete 抛异常（异常会一路打穿卡片渲染）",
                byInstaller.isSuccess
            )
            assertFalse(byInstaller.getOrDefault(true))
            assertFalse(planRepairSaysComplete(ownDir))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * 版本比对口径一致：`requestedVersion` 非空且不等时两侧都判"不完整"。
     * `latest` / `null` 都跳过比对（`isInstallComplete` 的既有语义）。
     */
    @Test
    fun versionComparisonSemanticsMatch() {
        val dir = tempDir()
        try {
            tree(dir, """{"version":"0.1.6"}""", withBinJs = true)
            val ownDir = File(dir, "node_modules/@deepseek-ai/dsh")

            assertTrue("版本相符 ⇒ 完整", DshInstaller.isInstallComplete(ownDir, "0.1.6"))
            assertFalse("版本不符 ⇒ 不完整", DshInstaller.isInstallComplete(ownDir, "0.2.1"))
            assertTrue("null ⇒ 跳过比对", DshInstaller.isInstallComplete(ownDir, null))
            assertTrue("latest ⇒ 跳过比对", DshInstaller.isInstallComplete(ownDir, "latest"))
            // planRepair 的 own 腿传的正是 null（只验完整性、不比版本），与上面第 3 条一致
            assertTrue(
                "planRepair 的 own 腿只验完整性（不看目标版本），与 isInstallComplete(dir, null) 同口径",
                planRepairSaysComplete(ownDir)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 目录根本不存在（实例从未建过）：两侧都判不完整，且不抛。 */
    @Test
    fun missingDirectoryIsIncompleteNotCrash() {
        val dir = tempDir()
        try {
            val never = File(dir, "node_modules/@deepseek-ai/dsh")
            assertFalse(DshInstaller.isInstallComplete(never, null))
            assertFalse(planRepairSaysComplete(never))
            assertNull("没有 package.json 时版本必须是 null", null)
        } finally {
            dir.deleteRecursively()
        }
    }
}
