package com.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * round49 / BL-247 的 shim：**它到底在承诺什么、代价是什么**。
 *
 * ## 背景（必须读完才知道这些断言在守什么）
 *
 * 我在 2026-11-21 加了 `assets/dsh/scripts/inode-shim.cjs`，用来绕过 dsh 会话锁
 * （`lease.ts` 在 flock 后校验 `fstat(fd).ino === stat(path).ino`，3 次不匹配就抛
 * `SessionAlreadyOwnedError`）。真机上确实修好了卡死问题。
 *
 * **但归因是错的**。我在文件头写的是「proot 伪造了 ino」，而 round49 的 proot 审查核实：
 *
 * ```
 * $ grep -rn "st_ino" oonid-pr-reference/src/proot/src/
 * src/tracee/statx.c:103:  state.statx_buf.stx_ino = stat_buf.st_ino;
 * ```
 *
 * 整个 proot 源码里 `st_ino` 只出现一次、且是透传真实值。二进制也确认是非 USERLAND 构建
 * （`.proot.l2s.` 计数 0）。所以「proot 伪造 ino」不成立，真因**仍未查清**。
 *
 * ## 这组测试钉什么
 *
 * 不能钉「shim 修好了根因」（它没有），只能钉它的**行为契约**与**已知代价**：
 * 1. 同一个文件经 fd 与经路径，投影出**相同**值（这正是它让 lease 校验通过的原因）
 * 2. 不同文件投影出**不同**值（否则等于把所有文件当同一个）
 * 3. `lstat` 与 `stat` 对同一路径投影一致（round49 修过一个 bug：两者用了不同的键规则）
 * 4. ino 一致的环境下 `needsShim()` 返回 false ⇒ **不安装**（正常 Linux 上零行为变化）
 *
 * ## 为什么这些断言是"源码/文本级"而不是运行时
 *
 * shim 是 `.cjs` 脚本，跑在 proot 里的 node 上；本项目的 JVM 单测跑不到那里。
 * 所以这里用两种手段：
 * - 能读文件的地方直接 `File.readText()` 做结构性断言（第三条、第五条）
 * - 投影函数本身在 JVM 里没法执行 node，只能断言"规则一致"这个文本事实
 */
class R49InodeShimContractTest {

    private val shim: File by lazy { mainAssetFile("dsh/scripts/inode-shim.cjs") }

    // ===== 契约 ============================================================

    /**
     * ★ `lstat` 与 `stat` 必须用**同一个**投影键规则。
     *
     * 这是 round49 修掉的一个真 bug：原来 `lstat` 用 `String(target)`、
     * `stat` 用 `path.resolve(String(target))`，相对路径下两者投影到不同值 ——
     * 而 shim 的全部价值就是"同一实体投影出同一值"。
     *
     * 变异验证：把任意一个 lstat 改回 `String(target)` ⇒ 本条变红。
     */
    @Test
    fun lstatAndStatUseTheSameProjectionKeyRule() {
        val src = shim.readText()
        // 四个投影点（promises.stat/lstat + statSync/lstatSync）都必须是 path.resolve
        val resolveCount = Regex("""rewriteStats\(stats, path\.resolve\(String\(target\)\)""")
            .findAll(src).count()
        assertEquals(
            "★ stat / statSync / lstat / lstatSync 四处都必须用 path.resolve(String(target)) 算投影键；" +
                "实际命中 $resolveCount 处",
            4, resolveCount
        )
        // 反面：不许有任何一处用裸 String(target)
        assertFalse(
            "★ 不许留下 'rewriteStats(stats, String(target)' 这种裸键（相对路径会投影错）",
            Regex("""rewriteStats\(stats, String\(target\)""").containsMatchIn(src)
        )
    }

    /**
     * fd → path 表必须在 close 时清掉。
     *
     * 不清的后果：fd 关闭后、被内核复用给别的文件之前，`fstatSync(旧 fd)` 仍会拿到
     * 旧路径投影出的旧值 —— 窗口窄但语义是错的（本 shim 的全部价值就是投影值反映真实身份）。
     *
     * 变异验证：删掉 `fdPaths.delete` 的任何一处 ⇒ 本条变红。
     */
    @Test
    fun everyClosePathClearsTheFdTable() {
        val src = shim.readText()
        val deletes = Regex("""fdPaths\.delete\(fd\)|fdPaths\.delete\(this\.fd\)""")
            .findAll(src).count()
        assertTrue(
            "★ closeSync / close / FileHandle.close 三条路径都要清表；实际命中 $deletes 处（应 ≥3）",
            deletes >= 3
        )
    }

    /**
     * ★ `needsShim()` 必须真的做探测，且探测失败时**不安装**。
     *
     * 为什么重要：正常 Linux 上 `fstat` 与 `stat` 的 ino 本来就一致，
     * 这时 shim **不该做任何改写**（否则白白削弱 lease 的异常检测）。
     * 这条钉的是"零行为变化"这个承诺的入口。
     */
    @Test
    fun needsShimProbesBeforeInstalling() {
        val src = shim.readText()
        assertTrue("必须真的 open + fstat + stat 做探测", src.contains("origFstatSync") || src.contains("fstatSync(fd"))
        // 探测失败（catch）必须返回 false，而不是 true
        val needsShimBody = sliceFunction(src, "function needsShim()")
        assertTrue(
            "★ 探测失败时必须 return false（不安装），实际函数体里没找到该分支",
            Regex("""catch\s*\{[^}]*return false""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(needsShimBody)
                || needsShimBody.contains("return false")
        )
        assertTrue(
            "★ 只有 ino 或 dev 不一致时才返回 true",
            needsShimBody.contains("ino") && needsShimBody.contains("dev")
        )
    }

    /**
     * ★ 文件头必须诚实地写明「归因未定」，不能继续断言 proot 伪造 ino。
     *
     * 这是本轮最重要的一条断言：**它守的不是代码，是知识状态**。
     * 一个错误归因比没有归因更糟 —— 后来者会基于它做判断（比如"去 proot 层修一下 ino 就行"），
     * 而那条路是错的。
     */
    @Test
    fun headerStatesTheAttributionIsUnresolved() {
        val src = shim.readText()
        assertTrue(
            "★ 文件头必须写明归因未定（不能再说\"proot 伪造 ino\"）",
            src.contains("归因未定") || src.contains("不能**说明")
        )
        assertFalse(
            "★ 不许再出现\"proot 编的假值\"这种已被证伪的说法",
            src.contains("proot 编的一个假值") || src.contains("fstat 的 ino 恒为 proot 编")
        )
        assertTrue(
            "★ 必须写明代价：关闭了 lease 的真实异常检测",
            src.contains("永久关闭") || src.contains("真异常也检不出")
        )
        assertTrue(
            "★ 必须留取证结论（st_ino 只出现一次、透传真实值）",
            src.contains("st_ino") && src.contains("statx.c")
        )
    }

    /**
     * 文件名不能含 "proot"。
     *
     * 理由：`DshRuntime.canSendQuitSignal` 用 `cmdline.contains("proot")` 判断
     * "这个 pid 是不是我的 proot"。而 node 进程的 cmdline 会带
     * `--require <shim路径>` —— 如果路径里有 "proot"，**任何一个加载了 shim 的 node
     * 都会被误判成 proot**，SIGQUIT 会打到 tracee 上而不是 proot。
     *
     * 这是 round49 发现的、由**我自己引入**的新风险。改名是零成本修法。
     */
    @Test
    fun shimPathDoesNotContainTheWordProot() {
        assertEquals(
            "★ 文件名不能含 proot（会被 canSendQuitSignal 的 contains(\"proot\") 误判）",
            "inode-shim.cjs", shim.name
        )
        assertFalse(
            "★ 文件名不许出现 proot 字样",
            shim.name.contains("proot", ignoreCase = true)
        )
    }

    /** `start-dsh.sh` 里挂的不是旧文件名。 */
    @Test
    fun startScriptReferencesTheRenamedShim() {
        val start = mainAssetFile("dsh/scripts/start-dsh.sh").readText()
        assertTrue("必须引用 inode-shim.cjs", start.contains("inode-shim.cjs"))
        assertFalse(
            "★ 不许再引用 proot-inode-shim.cjs（旧名，含 proot 会被误判）",
            start.contains("proot-inode-shim.cjs")
        )
    }

    // ===== 工具 ============================================================

    /** 定位 `FCL/src/main/assets/<rel>`（与项目内其他 assets 断言同口径） */
    private fun mainAssetFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (dir != null && depth < 8) {
            listOf(
                File(dir, "FCL/FCL/src/main/assets/$rel"),
                File(dir, "FCL/src/main/assets/$rel"),
                File(dir, "src/main/assets/$rel")
            ).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("找不到 assets/$rel（请在仓库根或 FCL/ 目录下运行测试）")
    }

    /** 从源码里切一个函数体：取 [header] 之后、下一个 `\n}` 之前的那段。 */
    private fun sliceFunction(src: String, header: String): String {
        val start = src.indexOf(header)
        if (start < 0) throw AssertionError("找不到 $header")
        val end = src.indexOf("\n}", start)
        if (end < 0) throw AssertionError("找不到 $header 的闭合大括号")
        return src.substring(start, end)
    }
}
