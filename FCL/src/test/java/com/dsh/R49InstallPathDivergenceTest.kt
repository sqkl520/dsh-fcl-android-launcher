package com.dsh

import com.dsh.core.DshPaths
import com.dsh.core.ProotCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * round49 / BL-250 + BL-251：两个**跨文件**的真 bug（W2-G 因文件范围只能报告，由编排落地）。
 *
 * ## 为什么这两条必须单独有一组测试
 *
 * 它们都不是"某一行写错了"，而是**两份实现悄悄分叉**：
 * - BL-250：判"装好了没"时，一份实现会回退到 rootfs 预装目录，另一份不会
 * - BL-251：宿主路径过字符折叠，guest 路径不过
 *
 * 分叉型缺陷的特点：**正常输入下两者结果相同**，所以永远测不出来；只有在特定输入
 * （实例没装过东西 / id 含 `\`）下才露头，而那时现场已经很难看了。
 * 所以测试要**专挑那个特定输入**，并断言两边给出同一个答案。
 */
class R49InstallPathDivergenceTest {

    // ===== BL-250：判"装好了没"的目录来源不许回退预装 ==================

    /**
     * `readInstalledVersion` 必须用 `ownDshDir`，不许用 `effectiveDshDir`。
     *
     * ## 缺陷本体
     *
     * `DshPaths.effectiveDshDir` 在实例目录没有 `package.json` 时**回退到
     * `rootfs/opt/dsh-preinstalled/`**。用它判"这个实例装好了没"的后果：
     * 一个从没装过东西的实例，只要 rootfs 自带预装 dsh，就被判"安装完整"。
     *
     * 触发点：`DshInstaller` 的取消后复查（实例目录可以是空的，但用户取消时不该被标 READY）
     * 与 npm 退出码 0 后的复查（产物残缺时会被预装包兜成"装完"，掩盖真实失败）。
     *
     * ## 为什么用源码断言
     *
     * `readInstalledVersion` 是 private，且它的两个调用点都在 `installJobBody` 的
     * 收尾逻辑里（需要 DshInstance + 真实磁盘布局）。JVM 上能做的、有判别力的检查是
     * "它到底读哪一棵树"——那正是缺陷本身。
     */
    @Test
    fun readInstalledVersionReadsOwnTreeNotPreinstalledFallback() {
        val src = installerSource()
        val start = src.indexOf("private fun readInstalledVersion(")
        assertTrue("找不到 readInstalledVersion —— 被改名或删了？", start >= 0)
        val rawBody = src.substring(start, minOf(src.length, start + 3000))
        // ★ 必须剥注释：修复的注释里**本来就写着** effectiveDshDir 这几个字
        //   （解释"为什么不能用它"）。不剥就会把说明文字判成缺陷 —— 这类误报会让人
        //   开始怀疑测试本身，比没有测试更糟。
        val body = blankComments(rawBody)

        assertFalse(
            "★ BL-250 复发：readInstalledVersion 的**可执行代码**里又在用 effectiveDshDir —— " +
                "那个函数会回退到 rootfs 预装目录，于是**没装过任何东西的实例**会被判" +
                "「安装完整」（目录空也算装完）。必须用 ownDshDir（只看实例自己的树）。" +
                "实际函数体（已剥注释）：<$body>",
            Regex("""effectiveDshDir""").containsMatchIn(body)
        )
        assertTrue(
            "★ BL-250：readInstalledVersion 必须显式用 DshPaths.ownDshDir(instance.id)",
            Regex("""DshPaths\s*\.\s*ownDshDir\s*\(\s*instance\s*\.\s*id\s*\)""").containsMatchIn(body)
        )
    }

    /**
     * 行为级对照：`ownDshDir` 与 `effectiveDshDir` 在"实例目录为空 + rootfs 有预装"时
     * **必须指向不同目录** —— 这正是 BL-250 的成因，钉住它以免有人"统一"两个函数时
     * 顺手把回退行为塞回 ownDshDir。
     */
    @Test
    fun ownDshDirNeverFallsBackToPreinstalled() {
        val id = "inst-bl250-probe-1"
        val own = DshPaths.ownDshDir(id)
        // ownDshDir 必须落在实例目录之内（instanceDir/<INSTANCE_DSH_REL>），
        // 绝不能是指向 rootfs 的路径。
        val instanceDir = DshPaths.instanceDir(id)
        assertTrue(
            "★ ownDshDir($id) 必须位于实例目录之内，实际=${own.absolutePath}，" +
                "实例目录=${instanceDir.absolutePath}",
            own.absolutePath.startsWith(instanceDir.absolutePath)
        )
        assertFalse(
            "★ ownDshDir 不许指向 rootfs 预装目录（那是 effectiveDshDir 的行为）",
            own.absolutePath.contains("dsh-preinstalled")
        )
    }

    // ===== BL-251：宿主 / guest 路径必须共用同一个折叠规则 ==============

    /**
     * `instanceGuestDir` 对任何 id 都必须与宿主目录名**逐字符一致**。
     *
     * ## 缺陷本体
     *
     * guest 侧两处用裸 `instance.id` 拼路径，宿主侧过 `safeInstanceDirName`（把
     * `/` `\` `NUL` 折成 `_`）。正常 id 下两者相同，但收编孤儿目录时 `id = it.name`
     * 会接受含 `\` 的目录名（Linux 上 `\` 是合法文件名字符）。
     *
     * 实测形态：`id = "weird\name"` ⇒ 宿主找 `weird_name`、guest 找 `weird\name` ⇒
     * 实例永远起不来，且用户在文件管理器里看到的名字与错误信息对不上。
     */
    @Test
    fun guestDirUsesTheSameFoldingAsHostDir() {
        val ids = listOf(
            "inst-123-1-ab",          // 正常形态
            "weird\\name",            // 含反斜杠（Linux 上是合法文件名字符）
            "a\\b\\c",                // 多个反斜杠
            "with/slash",             // 含斜杠（不该出现在 File.name 里，但防御）
            ".", "..",                // 特殊分量
            "含中文的名字"             // 非 ASCII
        )
        ids.forEach { id ->
            val hostName = DshPaths.instanceDir(id).name
            val guestPath = DshPaths.instanceGuestDir(id)
            val guestName = guestPath.removePrefix("${ProotCommand.GUEST_ROOT}/instances/")

            assertEquals(
                "★ BL-251：id=<$id> 的宿主目录名与 guest 路径分量必须**逐字符一致**；" +
                    "不一致 ⇒ proot 里找不到宿主那个目录，实例永远起不来。" +
                    "宿主=<$hostName> guest=<$guestName>",
                hostName, guestName
            )
        }
    }

    /**
     * 反面：含 `\` 的 id，裸拼与折叠后**确实不同** —— 证明上一条测试不是空转。
     *
     * 如果哪天 `safeInstanceDirName` 不再折叠 `\`（或 File.name 的行为变了），
     * 这条会变红，提醒重新评估"路径漂移"这个缺陷是否还存在。
     */
    @Test
    fun rawIdConcatWouldDriftForBackslashIds() {
        val id = "weird\\name"
        val folded = DshPaths.safeInstanceDirName(id)
        assertNotEquals(
            "前提：这个 id 的折叠结果必须与原文不同（否则本组测试对该输入无区分力）",
            id, folded
        )
        assertEquals("折叠规则：\\ 应变成 _", "weird_name", folded)

        // guest 侧现在也用折叠值 ⇒ 与"裸拼"不同
        val guestName = DshPaths.instanceGuestDir(id)
            .removePrefix("${ProotCommand.GUEST_ROOT}/instances/")
        assertNotEquals(
            "★ 若 guest 侧改回裸拼 instance.id，这个断言会红 —— 那正是要防的回退",
            id, guestName
        )
    }

    /**
     * 两处调用点都必须走 `DshPaths.instanceGuestDir`。
     *
     * 这是**接线断言**：函数写对了但调用点没改，等于没修。项目历史上吃过这个亏
     * （BL-245/246：纯函数测全、调用点撤销仍全绿）。
     */
    @Test
    fun bothGuestPathCallSitesUseTheSharedFunction() {
        listOf("core/DshRuntime.kt", "core/DshInstaller.kt").forEach { rel ->
            val src = mainSourceFile(rel).readText()
            assertTrue(
                "★ $rel 必须用 DshPaths.instanceGuestDir(...) 拼 guest 实例路径",
                Regex("""DshPaths\s*\.\s*instanceGuestDir\s*\(""").containsMatchIn(src)
            )
            // 反面：不许再有裸拼 GUEST_ROOT/instances/${instance.id}
            assertFalse(
                "★ $rel 里不许再有裸拼 \"\${ProotCommand.GUEST_ROOT}/instances/\${...id}\" —— " +
                    "那正是 BL-251 的缺陷形态（宿主折叠了、guest 没折叠）",
                Regex("""GUEST_ROOT\}/instances/\$\{[^}]*\.id\}""").containsMatchIn(src)
            )
        }
    }

    // ===== 辅助 ==========================================================

    private fun installerSource(): String = mainSourceFile("core/DshInstaller.kt").readText()

    /**
     * 剥掉 `//` 行注释与 `/* */` 块注释（保留换行，便于报错信息里定位）。
     *
     * 为什么各测试类各写一份而不是共享：MiniRunner 是自研 harness，测试源码按文件编译，
     * 跨文件引 helper 要么建 utils 类（那会让"测试工具"也成为一个要维护的东西），
     * 要么就得保证每份实现一致。这个函数只有十几行、逻辑稳定，复制比建依赖便宜。
     * （本项目已有先例：`R49LogBusFixTest.blankComments` / `R43.stripComments` 同形态。）
     */
    private fun blankComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                c == '/' && n == '/' -> {
                    while (i < src.length && src[i] != '\n') { sb.append(' '); i++ }
                }
                c == '/' && n == '*' -> {
                    sb.append("  "); i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) {
                        sb.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    if (i + 1 < src.length) { sb.append("  "); i += 2 }
                }
                c == '"' -> {           // 字符串字面量要原样保留（里面有中文提示，可能含 //）
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
