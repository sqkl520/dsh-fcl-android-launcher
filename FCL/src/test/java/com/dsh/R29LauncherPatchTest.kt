package com.dsh

import com.dsh.core.LAUNCHER_ASSETS_PLUGIN
import com.dsh.core.LAUNCHER_PATCH_OVERLAY
import com.dsh.core.jsonStringLiteral
import com.dsh.core.launcherPatchOverlayJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R29 / BL-222：启动器自带 `--patch` overlay 的内容与落盘。
 *
 * ## 这组测的是哪一半
 *
 * 判"要不要写"和"写什么"这种事，本仓已经栽过一次：只测纯函数、不钉调用点，
 * 把 `homeRootfs` 改回 `workspace` 后 493 条测试**全绿**（`R28CallSiteContractTest` 的由来）。
 * 这里同理 —— 光测 [launcherPatchOverlayJson] 拼得对，证明不了 `DshRuntime` 真的调了它。
 * 所以第 4 条钉调用点，第 5 条钉脚本侧真的不再无条件传 `--patch`。
 *
 * ## 为什么这层必须是 JSON 而不是 YAML
 *
 * 启动器没有 YAML 解析器（`FCL/build.gradle.kts` 只有 gson + kotlinx-serialization）。
 * 而 `--patch` 读的是 YAML，**JSON 是合法 YAML**，所以用 gson 风格拼 JSON 就够，
 * 一行解析库都不用引。这一点在 0.2.1-rc.2 上实测过（`--dump-config` 能把
 * `pollIntervalMs: 5000` 打进 client-hmr 那一行）。
 */
class R29LauncherPatchTest {

    // ---------------------------------------------------------------------
    // 1. 内容正确性
    // ---------------------------------------------------------------------

    /**
     * overlay 必须**同时**带两条改动，且 `launcher-assets` 的 name 是给定的 guest 绝对路径。
     *
     * 少了 assets 那一条 = 缓存头优化没生效（这是本条改动里唯一有量级收益的部分）；
     * 少了 client-hmr 那一条 = 只是没省电，不算错，但既然写了就该钉住。
     */
    @Test
    fun overlayCarriesBothRowsAndThePluginPath() {
        val json = launcherPatchOverlayJson("/opt/dsh/scripts/$LAUNCHER_ASSETS_PLUGIN")

        assertTrue(
            "overlay 必须包含 client-hmr 的 pollIntervalMs 覆盖 —— 实际内容：$json",
            json.contains("\"id\": \"client-hmr\"") && json.contains("\"pollIntervalMs\": 5000")
        )
        assertTrue(
            "overlay 必须 insert 一个 launcher-assets 行 —— 实际内容：$json",
            json.contains("\"id\": \"launcher-assets\"")
        )
        assertTrue(
            "launcher-assets 的 name 必须是给定的 guest 绝对路径 —— 实际内容：$json",
            json.contains("\"/opt/dsh/scripts/$LAUNCHER_ASSETS_PLUGIN\"")
        )
    }

    /**
     * ★ 路径里出现反斜杠/引号时必须被正确转义 —— 否则生成的 JSON 是坏的，
     * 而**坏的 overlay 会让 dsh 直接启动失败**（不是降级）。
     *
     * 生产路径是 guest 内 `/opt/dsh/scripts/...`（不会有反斜杠），但 [jsonStringLiteral]
     * 是 public 的、且这个项目在 Windows 上开发 —— 将来有人拿它拼宿主路径就会踩到。
     */
    @Test
    fun jsonStringLiteralEscapesWhatWouldOtherwiseBreakTheOverlay() {
        assertEquals("\"a\\\\b\"", jsonStringLiteral("a\\b"))
        assertEquals("\"a\\\"b\"", jsonStringLiteral("a\"b"))
        assertEquals("\"a\\nb\"", jsonStringLiteral("a\nb"))
        assertEquals("\"a\\tb\"", jsonStringLiteral("a\tb"))
        // 控制字符走 \u 形式（\f 已单独处理，这里验证通用分支）
        assertEquals("\"a\\u0001b\"", jsonStringLiteral("a\u0001b"))
        // 中文等非 ASCII 原样保留（JSON 允许 UTF-8 直接出现）
        assertEquals("\"启动器\"", jsonStringLiteral("启动器"))
    }

    /**
     * 转义后的内容必须**仍是合法 JSON**（用 gson 反解一遍）：这是"overlay 不会把 dsh 弄挂"
     * 的结构性保证，比逐字符比对更难被将来的实现改动糊过去。
     */
    @Test
    fun escapedLiteralRoundTripsAsJson() {
        val tricky = "C:\\Users\\x\"y\\scripts\\a.mjs"
        val literal = jsonStringLiteral(tricky)
        val parsed = com.google.gson.JsonParser.parseString(literal).asString
        assertEquals(
            "转义后再解析必须还原成原字符串 —— 否则生成的 overlay 是坏 JSON，dsh 会直接启动失败",
            tricky,
            parsed
        )
    }

    // ---------------------------------------------------------------------
    // 2. 落盘：写不出来时必须返回 null（调用方据此不传 --patch）
    // ---------------------------------------------------------------------

    /**
     * 插件本体不存在时**不写** overlay，返回 null。
     *
     * 理由：一条指向不存在模块的 insert 会让 dsh 每次启动都打一行
     * `launcher-assets (...): failed to import`。不致命（实测服务照常起），
     * 但那是我们自己制造的日志噪音，没必要。
     */
    @Test
    fun noOverlayWhenPluginAssetIsMissing() {
        val tmp = createTempDir("r29-no-plugin")
        try {
            val out = com.dsh.core.writeLauncherPatchOverlay(
                instancePathInRootfs = "/opt/dsh/instances/inst-1",
                scriptsHostDir = File(tmp, "scripts")   // 不存在 ⇒ 没有插件
            )
            assertEquals("插件不在时不应写出 overlay（否则每次启动都多一行 import 失败告警）", null, out)
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // 3. 常量与脚本一致
    // ---------------------------------------------------------------------

    /**
     * ★ 文件名常量必须与 `start-dsh.sh` 里的判断逐字一致。
     *
     * Kotlin 侧写 `launcher.patch.yml`、脚本侧判 `launcher.patch.yml` —— 两处各写一遍是
     * 这次改动的既有形态（脚本是 POSIX sh，引不进 Kotlin 常量）。一旦有人只改一边，
     * 症状是"overlay 写了但永远不被传"，**而且完全没有报错**。
     */
    @Test
    fun overlayFilenameMatchesTheLaunchScript() {
        assertEquals("launcher.patch.yml", LAUNCHER_PATCH_OVERLAY)

        val sh = resolveScript("start-dsh.sh")
        assertNotNull("找不到 start-dsh.sh —— 请在仓库根或 FCL/ 目录下运行测试", sh)
        val text = sh!!.readText()
        assertTrue(
            "start-dsh.sh 里必须用 \$INSTANCE_DIR/$LAUNCHER_PATCH_OVERLAY 判断文件是否存在 —— " +
                "否则 overlay 永远不被传给 dsh（而且没有任何报错）",
            text.contains("\$INSTANCE_DIR/$LAUNCHER_PATCH_OVERLAY")
        )
        assertTrue(
            "start-dsh.sh 必须真的把 --patch 传给 dsh —— 否则写了也白写",
            text.contains("--patch")
        )
    }

    /**
     * ★ `--patch` 必须在 `web` 之后、`--no-open` 之前。
     *
     * 实测：`dsh web --patch X --no-open ...` 生效；`dsh web --no-open ... --patch X`
     * 直接 `error: unknown option '--patch'` —— dsh 的命令行在第一个不认识的 token 处
     * 把后续全部交给 app 解析。这个顺序是硬约束，写错了 dsh 根本起不来。
     */
    @Test
    fun patchFlagSitsBetweenWebAndNoOpen() {
        val sh = resolveScript("start-dsh.sh")
        assertNotNull("找不到 start-dsh.sh", sh)
        val text = sh!!.readText()

        val line = text.lineSequence().firstOrNull { it.contains("--patch \"\$LAUNCHER_PATCH\"") }
        assertNotNull(
            "start-dsh.sh 里找不到 `--patch \"\$LAUNCHER_PATCH\"` 那一行 —— " +
                "要么被删了，要么写法变了（这条断言失去意义）",
            line
        )
        val l = line!!
        val iWeb = l.indexOf(" web ")
        val iPatch = l.indexOf("--patch")
        val iNoOpen = l.indexOf("--no-open")
        assertTrue("--patch 行必须带 `web` 子命令：$l", iWeb >= 0)
        assertTrue("--patch 行必须带 --no-open：$l", iNoOpen >= 0)
        assertTrue(
            "--patch 必须在 web 之后：$l（放前面 dsh 会当成 launcher 旗标解析失败）",
            iPatch > iWeb
        )
        assertTrue(
            "--patch 必须在 --no-open 之前：$l（实测放后面 dsh 报 unknown option '--patch'，" +
                "因为命令行在第一个不认识的 token 处把后续交给 app）",
            iPatch < iNoOpen
        )
    }

    /**
     * ★ 调用点：`DshRuntime` 必须在启动前真的调了 `writeLauncherPatchOverlay`。
     *
     * 这一条就是本仓"只测纯函数等于没修"那条教训的落点。没有它，
     * 把 `writeLauncherPatchOverlay(...)` 从 `startLocked` 里删掉，上面三条照样全绿。
     */
    @Test
    fun runtimeActuallyWritesTheOverlayBeforeLaunch() {
        val runtime = resolveMain("core/DshRuntime.kt")
        assertNotNull("找不到 DshRuntime.kt", runtime)
        val text = runtime!!.readText()
        assertTrue(
            "startLocked 必须调用 writeLauncherPatchOverlay( —— 否则 overlay 永远不被写出，" +
                "上面所有关于内容的断言都失去意义",
            text.contains("writeLauncherPatchOverlay(")
        )
        // overlay 是优化项：写失败必须不致命。判据是调用点前面出现 runCatching {
        // （这个仓库对"副作用不许把启动弄挂"统一用这个形态，见 onRetryClicked 附近）。
        val callIdx = text.indexOf("writeLauncherPatchOverlay(")
        val before = text.substring(maxOf(0, callIdx - 120), callIdx)
        assertTrue(
            "写 overlay 不能让启动失败：调用点必须包在 runCatching 里（overlay 是优化项，不是必需品）。" +
                "调用点前 120 字符实际是：<$before>",
            before.contains("runCatching {")
        )
    }

    // ---------------------------------------------------------------------
    // 定位
    // ---------------------------------------------------------------------

    private fun resolveMain(rel: String): File? = resolveUnder("FCL/src/main/java/com/dsh/$rel")

    private fun resolveScript(name: String): File? = resolveUnder("FCL/src/main/assets/dsh/scripts/$name")

    /** 与其他测试同款：从 cwd 向上找，兼容 `FCL/FCL/`、`FCL/`、裸 `src/` 三种布局。 */
    private fun resolveUnder(rel: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            File(dir, "FCL/$rel").takeIf { it.isFile }?.let { return it }
            File(dir, rel).takeIf { it.isFile }?.let { return it }
            File(dir, rel.removePrefix("FCL/")).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    private fun createTempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()
}
