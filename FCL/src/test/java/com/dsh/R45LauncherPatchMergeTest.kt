package com.dsh

import com.dsh.core.launcherPatchOverlayJson
import com.dsh.core.mergeLauncherPatchOverlay
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **BL-246**（r45）：`launcher.patch.yml` 是 dsh 的用户可见配置入口（`--patch` 指向它），
 * 启动器每次启动都整份覆盖，会把用户写的 provider / 插件条目无声抹掉。
 *
 * ## 真机现场（2026-11-19）
 *
 * 按 dsh 文档把 `llm-pi-ai` 的 `providers.mygateway`（baseURL 指向自建网关、模型 `virtual/opus`）
 * 写进该文件，重启 dsh 后文件被覆盖回两条内置条目 —— provider 消失，前端只能看到内置 DeepSeek。
 *
 * ## 本类钉住的不变量
 *
 * 1. **用户条目必须活过重启**：合并后仍在，且排在启动器条目之后（后者写的同 id 配置赢）。
 * 2. **启动器条目不能被用户文件复制一份**：同 id 的条目只留启动器那份（否则 dsh 报 id 冲突）。
 * 3. **坏文件不许拖垮启动**：解析失败 / 不是数组 / 不存在 ⇒ 退回纯启动器条目。
 *
 * 运行：`sh run-tests.sh`
 */
class R45LauncherPatchMergeTest {

    private val plugin = "/opt/dsh/scripts/launcher-assets.mjs"

    private fun parse(json: String) = JsonParser.parseString(json).asJsonArray

    /** 取出所有条目里出现过的 id（含 insert 子项），用于断言"某 id 只出现一次"。 */
    private fun idsOf(json: String): List<String> =
        parse(json).flatMap { e ->
            val o = e.asJsonObject
            buildList {
                o.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.let { add(it) }
                o.get("insert")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { sub ->
                    sub.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.let { add(it) }
                }
            }
        }

    // --- 1. 用户条目必须活下来（核心判据）--------------------------------------

    @Test
    fun userProviderSurvivesTheMerge() {
        // 用户在文件里写的 provider 配置（真机里就是这么写的）
        val userFile = """
        [
          { "id": "client-hmr", "config": { "pollIntervalMs": 5000 } },
          { "insert": [ { "id": "launcher-assets", "name": "$plugin" } ] },
          {
            "id": "llm-pi-ai",
            "config": {
              "providers": {
                "mygateway": {
                  "baseURL": "http://47.108.197.93:33000",
                  "apiKeyEnv": "MYGATEWAY_API_KEY",
                  "api": "openai-completions",
                  "models": [ { "id": "virtual/opus" } ]
                }
              }
            }
          }
        ]
        """.trimIndent()

        val merged = mergeLauncherPatchOverlay(plugin, userFile)

        assertTrue(
            "★ BL-246 未修：用户写的 llm-pi-ai provider 在合并后消失了 —— " +
                "重启 dsh 就会把它覆盖掉，前端只能看到内置 DeepSeek。实际：$merged",
            merged.contains("llm-pi-ai")
        )
        assertTrue("provider 的具体配置必须原样保留：$merged", merged.contains("mygateway"))
        assertTrue("baseURL 不能丢：$merged", merged.contains("47.108.197.93:33000"))
        assertTrue("模型 id 不能丢：$merged", merged.contains("virtual/opus"))
    }

    /**
     * 启动器那两条**不能**因为用户文件里也有一份就变成两条。
     *
     * 用户文件里的同 id 条目会被丢弃（启动器自己那份更权威，且重复注入同一插件 dsh 会报 id 冲突）。
     */
    @Test
    fun launcherOwnedEntriesAreNotDuplicated() {
        val userFile = """
        [
          { "id": "client-hmr", "config": { "pollIntervalMs": 999 } },
          { "insert": [ { "id": "launcher-assets", "name": "/wrong/path.mjs" } ] }
        ]
        """.trimIndent()

        val merged = mergeLauncherPatchOverlay(plugin, userFile)
        val ids = idsOf(merged)

        assertEquals("client-hmr 只许出现一次（否则重复配置）", 1, ids.count { it == "client-hmr" })
        assertEquals(
            "launcher-assets 只许出现一次（重复 insert 同一插件，dsh 会报 id 冲突）",
            1, ids.count { it == "launcher-assets" }
        )
        assertTrue(
            "留下的必须是**启动器**那份（路径来自参数，不是用户文件里的 /wrong/path.mjs）：$merged",
            merged.contains(plugin) && !merged.contains("/wrong/path.mjs")
        )
    }

    // --- 2. 坏输入不许拖垮启动 ------------------------------------------------

    @Test
    fun missingFileFallsBackToLauncherEntries() {
        val merged = mergeLauncherPatchOverlay(plugin, null)
        assertEquals(
            "文件不存在时就是启动器那两条，不该多也不该少",
            launcherPatchOverlayJson(plugin),
            merged
        )
    }

    @Test
    fun corruptFileFallsBackToLauncherEntries() {
        val merged = mergeLauncherPatchOverlay(plugin, "{ this is not json")
        assertEquals(
            "解析失败必须退回启动器条目 —— 坏文件不能让 overlay 整个失效（那条原则见 writeLauncherPatchOverlay）",
            launcherPatchOverlayJson(plugin),
            merged
        )
    }

    @Test
    fun nonArrayRootFallsBackToLauncherEntries() {
        val merged = mergeLauncherPatchOverlay(plugin, """{"id":"client-hmr"}""")
        assertEquals(
            "根不是数组时同样退回（dsh 的 patch 文件必须是数组）",
            launcherPatchOverlayJson(plugin),
            merged
        )
    }

    @Test
    fun emptyArrayKeepsLauncherEntries() {
        val merged = mergeLauncherPatchOverlay(plugin, "[]")
        val ids = idsOf(merged)
        assertEquals("空用户文件不该吞掉启动器条目", 2, parse(merged).size())
        assertTrue(ids.contains("client-hmr"))
        assertTrue(ids.contains("launcher-assets"))
    }

    // --- 3. 条目顺序：用户在后（后写的赢）--------------------------------------

    /**
     * dsh 的 patch 语义是"后写的条目覆盖先写的同 id 配置"，所以用户条目必须排在启动器之后
     * —— 这样用户想调 `client-hmr` 的间隔时，把错的那份丢掉、自己那份排在后面对不上；
     * 实际做法是**丢掉用户的同 id 条目**（见上一条），但其它条目的相对顺序仍要让用户靠后。
     */
    @Test
    fun userEntriesComeAfterLauncherEntries() {
        val userFile = """[ { "id": "my-own-plugin", "config": {} } ]"""
        val merged = mergeLauncherPatchOverlay(plugin, userFile)
        val ids = idsOf(merged)

        val userAt = ids.indexOf("my-own-plugin")
        val launcherAt = ids.indexOf("launcher-assets")
        assertNotNull("用户条目必须还在", if (userAt >= 0) ids[userAt] else null)
        assertTrue(
            "用户条目必须排在启动器条目**之后**（后写的覆盖先写的）：ids=$ids",
            userAt > launcherAt
        )
    }

    // --- 4. 启动器模板本身仍是合法的 JSON 数组 --------------------------------

    @Test
    fun launcherTemplateItselfIsValidJsonArray() {
        val arr = parse(launcherPatchOverlayJson(plugin))
        assertEquals("启动器条目恰好两条", 2, arr.size())
        assertTrue("第一条是 client-hmr", arr[0].asJsonObject.get("id").asString == "client-hmr")
        assertTrue(
            "第二条是 launcher-assets 的 insert",
            arr[1].asJsonObject.getAsJsonArray("insert")[0].asJsonObject.get("id").asString == "launcher-assets"
        )
    }

    // --- 5. 接线：生产代码必须**真的调用**合并函数 ----------------------------

    /**
     * ★ 本类最重要的一条，也是第一版漏掉的一条。
     *
     * 上面那些断言全都在测 [mergeLauncherPatchOverlay] 这个**纯函数**。把
     * `writeLauncherPatchOverlay` 里的调用改回 `launcherPatchOverlayJson(pluginGuest)`（整份覆盖）
     * —— 也就是把 BL-246 的修复**整个撤销** —— 上面 8 条**全部仍然是绿的**。
     *
     * 这是本项目反复踩过的"只钉形状不钉接线"陷阱（同 BL-245 的教训：
     * 源码里有那个标识符 ≠ 它真的在起作用）。所以必须单独断言**调用点**。
     *
     * 做法：从源码里切出 `writeLauncherPatchOverlay` 的函数体、剥掉注释，
     * 要求它包含 `mergeLauncherPatchOverlay(`，并且**不再**直接调
     * `launcherPatchOverlayJson(`（那次调用只该发生在合并函数内部）。
     */
    @Test
    fun productionCallSiteActuallyMerges() {
        val src = mainSourceFile("core/DshInstances.kt").readText()
        val body = stripComments(
            sliceFun(src, "fun writeLauncherPatchOverlay(", "/** 启动器自带的 `/assets` 缓存插件")
        )
        assertTrue("前置：必须能切出 writeLauncherPatchOverlay 的函数体", body.length > 200)

        assertTrue(
            "★ BL-246 接线断了：writeLauncherPatchOverlay 必须调用 mergeLauncherPatchOverlay —— " +
                "否则每次启动都会把用户写进 launcher.patch.yml 的 provider 整份覆盖掉" +
                "（纯函数测试全绿也发现不了这件事）。实际函数体：${body.take(600)}",
            body.contains("mergeLauncherPatchOverlay(")
        )
        assertFalse(
            "★ 调用点不许直接调 launcherPatchOverlayJson（那等于整份覆盖）—— " +
                "它只该在 mergeLauncherPatchOverlay 内部被用作模板。实际：${body.take(600)}",
            body.contains("launcherPatchOverlayJson(")
        )
        assertTrue(
            "★ 必须先把现有文件读出来再合并（否则合并函数拿到的永远是 null、等于覆盖）：" +
                "函数体里应出现对 target 的 readText。实际：${body.take(600)}",
            body.contains("readText")
        )
    }

    // --- 夹具 ---------------------------------------------------------------

    /** 取仓库里的主源码文件（与 R40/R43/R44 同一手法，从 cwd 往上找）。 */
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
        throw AssertionError("找不到 $rel（请在仓库根或 FCL/ 目录下运行测试）")
    }

    /** 切出一个函数的源码段（从 header 到 nextHeader 之前）。 */
    private fun sliceFun(src: String, header: String, nextHeader: String): String {
        val start = src.indexOf(header)
        if (start < 0) throw AssertionError("找不到 $header")
        val end = src.indexOf(nextHeader, start + header.length)
        if (end < 0) throw AssertionError("找不到 $header 之后的下一个标记：$nextHeader")
        return src.substring(start, end)
    }

    /**
     * 剥掉行注释与块注释（逐字照抄 R40 的实现）。
     *
     * ⚠️ 本 KDoc **不许写出块注释的起始符与结束符连写**：Kotlin 块注释可嵌套，
     * 写一次就会在这里打开/闭合注释，把下面整段代码吞掉（实测踩过）。
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
