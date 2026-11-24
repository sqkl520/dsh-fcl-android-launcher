package com.dsh

import com.dsh.core.DshPaths
import com.dsh.core.LAUNCHER_ASSETS_PLUGIN
import com.dsh.core.LAUNCHER_PATCH_OVERLAY
import com.dsh.core.writeLauncherPatchOverlay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.nio.file.Files

/**
 * round43 / SP-08：**写 overlay 时不许先删旧目标**。
 *
 * ## 修的是什么（先说实话，否则下面的用例看不懂在防什么）
 *
 * `writeLauncherPatchOverlay`（`DshInstances.kt:332`）原先是三步：
 * ① `tmp.writeText(body)` → ② `if (target.exists() && !target.delete()) return null`
 * → ③ `if (!tmp.renameTo(target)) return null`。
 *
 * ②③ 之间是一个**毁数据窗口**：删掉旧 overlay 成功、紧接着 rename 失败 ⇒ 目标位置
 * **什么都没有**，只剩一个 `.tmp`。原来那份可用的 overlay（两条优化：`/assets` 的
 * `cache-control: immutable`、`client-hmr` 的 poll 500→5000）就没了，而且调用方
 * （`DshRuntime.kt:1038` 的 `runCatching{}`）只看返回值是否为 null，会把它当成
 * "这次没写成功，不影响启动" —— 代价（丢上一份）完全不可见。
 *
 * ## 为什么删掉 ② 就够了
 *
 * 目标平台**只有 Android/Linux**（这是 Android launcher，跑 proot Debian）。POSIX 的
 * `rename(2)` **覆盖已存在目标是合法的、且是原子的**。② 的存在理由本来是绕开 Windows 上
 * `File.renameTo` 不覆盖 —— 而 Windows 不是目标平台，所以这一步在真机上纯属多余，
 * 却正好是那个窗口的唯一成因。
 *
 * 同文件 `writeManifest`（`:2219`）本来就是正确的一侧（纯 `renameTo`，没有先删）。
 * 同一个代码库里两处对 `renameTo` 的假设不一致，这本身就是缺陷的一部分 —— 所以
 * [overlayAndManifestUseTheSameRenameAssumption] 把两边钉成同一个口径。
 *
 * ## 三条用例各自防什么（不是同一件事的三种写法）
 * | 用例 | 层 | 防的退化 |
 * |---|---|---|
 * | [overlayWriteDoesNotDeleteTargetBeforeRename] | 源码级 | 有人把 `target.delete()` 那一步加回来（= 缺陷复发）—— **这是 SP-08 唯一有判别力的判据** |
 * | [overlayWritePreservesTheOldFileWhenRenameWouldFail] | 行为级 | 失败出口把旧 overlay 清空/截断；返回了 target 却没写进去；失败时新 body 两边都没有 |
 * | [overlayAndManifestUseTheSameRenameAssumption] | 源码级（**对称**） | 两侧任一侧退化：overlay 又先删目标、或 manifest 被"顺手加个先删" |
 *
 * ★ 关于第 2 条：它**没有**复现"rename 失败而旧文件幸存"这个窗口，也做不到 —— 详见它自己的
 * KDoc（里面有本机实测的四种构造与结论：`delete()` 一旦成功，紧接着的 rename 在本机必然成功，
 * 所以那个窗口要求的"删成功 + rename 失败"在本机文件系统语义下自相矛盾）。真正兜住 SP-08 的
 * 是第 1 条源码断言；第 2 条守的是失败出口上的可观察不变量。
 *
 * ## ★ 为什么要剥注释再断言（本仓实测教训）
 *
 * 裸 `contains("target.delete()")` 会命中**注释里的同名词**，于是缺陷状态也能绿 —— 本仓
 * 已有先例：`R40InstallForkGuardTest.deleteJoinsOutsideTheInstallerLock` 就是被注释里的
 * `proot.run(` 坑过一次（它的 KDoc 里记着那次假绿）。SP-08 的修法恰好是"删掉一行 + 把
 * 那行写进注释解释为什么不能加回来"，所以**不剥注释的断言在这里必然假绿**。下面两条
 * 源码断言一律先 [stripComments]。
 *
 * 运行：`sh run-tests.sh`（自动扫描本类）。
 */
class R43OverlayAtomicReplaceTest {

    private val tmpRoot: File = Files.createTempDirectory("r43-overlay-atomic").toFile()
    private var savedPaths: Map<Field, Any?> = emptyMap()
    private var pathsSaved = false

    @After
    fun restoreGlobalState() {
        if (pathsSaved) {
            savedPaths.forEach { (f, v) ->
                f.isAccessible = true
                runCatching { f.set(DshPaths, v) }
            }
            savedPaths = emptyMap()
            pathsSaved = false
        }
        runCatching { tmpRoot.deleteRecursively() }
    }

    // ==================================================================
    // 一、核心判据（源码级）：函数体里不许出现 target.delete()
    // ==================================================================

    /**
     * `writeLauncherPatchOverlay`（剥注释后）的**可执行代码**里不许出现 `delete(`。
     *
     * 这是 SP-08 的精确判据：缺陷就是一行 `target.delete()`，修法就是删掉它。加回来 ⇒
     * "②成功③失败"的毁数据窗口同时回来（旧 overlay 被删掉、rename 又没成）。
     *
     * ★ 断言的是 `delete(` 而不是 `target.delete()`：只要函数体里出现任何一次删除旧目标的
     * 动作，无论写成 `target.delete()` / `File(...).delete()` / 先赋给变量再删，都属于
     * 同一个缺陷。这条函数体本来**一个 delete 都不需要**（正确形态只有 writeText + rename）。
     *
     * ★ 同时钉住"rename 还在"：避免有人为了让这条绿而把整段原子替换删掉（那样 overlay
     * 根本不会被写出来，这条断言就变成了在守一个空函数）。
     */
    @Test
    fun overlayWriteDoesNotDeleteTargetBeforeRename() {
        val body = stripComments(
            sliceFun(
                instancesSource(),
                "fun writeLauncherPatchOverlay(",
                "/** 启动器自带的 `/assets` 缓存插件"
            )
        )
        // ★ R49 收紧判据：原来断言的是 `body.contains("delete(")` —— 那会把
        //   **清 tmp 的合法删除**也判成缺陷。这个函数在失败路径上必须删 tmp（S-08：tmp 名
        //   现在唯一了，失败不删就会每次启动在实例目录里多一个残留），所以宽判据从
        //   "近似正确" 变成了 "误伤正确实现"。
        //
        //   SP-08 要守的其实只有一件事：**不许删 target**（删了再 rename，rename 一失败
        //   目标位置就空了，上一份可用 overlay 也没了）。所以判据改成"对 target 的删除"。
        //
        // ★ R49 审计补正：我第一版写成 `\b(?:target|targetFile|overlay|overlayFile)\s*\.\s*delete\s*\(`，
        //   并在注释里宣称"三种写法都要覆盖，包括先赋给变量再删"。**审计实测：注释比实现强。**
        //   那个正则对 `target?.delete()`、`target.toPath().toFile().delete()`、
        //   `Files.deleteIfExists(target.toPath())`、`File(..., LAUNCHER_PATCH_OVERLAY).delete()`
        //   全部不命中 —— 也就是说"删 target"至少有四种写法能骗过它。
        //
        //   现在改成"先取 delete 的**接收者表达式**，再看接收者里有没有 target 系标识符"，
        //   覆盖别名之外的链式/安全调用/静态调用/构造再删。判据库本身的规格见
        //   [R49DeleteGuardRegexTest]（那边逐写法钉了期望，含一条**如实标注的能力边界**：
        //   `val t = target; t.delete()` 纯文本判据抓不到，需要数据流分析）。
        val callSites = Regex("""([\w.$?()\[\], ]+?)\s*(?:\?\.)?\s*\.\s*delete(?:IfExists)?\s*\(""")
            .findAll(body).toList()
        val deletesTarget = callSites.any { m ->
            val receiver = m.groupValues[1]
            val tail = body.substring(m.range.first, minOf(body.length, m.range.last + 80))
            Regex("""\b(?:target|targetFile|overlay|overlayFile)\b""").containsMatchIn(receiver) ||
                receiver.contains("LAUNCHER_PATCH_OVERLAY") ||
                (receiver.endsWith("Files") && Regex("""\btarget\b""").containsMatchIn(tail))
        }
        assertFalse(
            "★ SP-08 复发：writeLauncherPatchOverlay 的可执行代码里删了 **target** —— " +
                "先删旧 overlay 再 rename 会制造毁数据窗口（删成功、rename 失败 ⇒ 目标位置" +
                "什么都没有，上一份可用的 overlay 也丢了）。目标平台只有 Android/Linux，" +
                "POSIX rename(2) 本身就是原子覆盖，删除这一步完全多余。" +
                "（删 `tmp` 是允许且必需的：失败路径要清残留。）" +
                "实际函数体：<$body>",
            deletesTarget
        )
        assertTrue(
            "前置：writeLauncherPatchOverlay 必须仍然做原子替换（tmp.renameTo(target)）—— " +
                "没有 rename 就不是「不删旧目标」，而是「不写 overlay」。实际函数体：<$body>",
            body.contains("tmp.renameTo(target)")
        )
        assertTrue(
            "前置：必须先写 tmp（writeText）再 rename，否则就退化成非原子直写。实际函数体：<$body>",
            body.indexOf("writeText(") in 0 until body.indexOf("tmp.renameTo(target)")
        )
    }

    // ==================================================================
    // 二、行为级：rename 失败时旧 overlay 必须还在（最重要）
    // ==================================================================

    /**
     * 两次写入之间，旧 overlay **不许消失**。
     *
     * ## ★ 诚实说明：这条**没有**复现"rename 失败"，也**做不到**
     *
     * SP-08 的毁数据窗口是「②`target.delete()` 成功 → ③`renameTo` 失败」。要在 JVM 单测里
     * 稳定构造它，必须同时满足**两个**条件：目标真能被删掉，而紧接着的 rename 真会失败。
     * 本机（Windows 开发机）实测四种构造全部失败：
     *
     * | 构造 | 结果 | 为什么区分不了 |
     * |---|---|---|
     * | `target` 预置成**非空目录** | `delete()`=false、`rename`=false | 旧实现提前 `return null`，新旧**都**返回 null |
     * | `target` 预置成**空目录** | `delete()`=true、`rename`=**true** | 删掉后 rename 就成功了 —— 窗口没出现 |
     * | `target` 设只读 `setWritable(false)` | `delete()`=true、`rename`=true | NTFS 上只读挡不住 rename/delete |
     * | `hostInstance` 目录设只读 | `setWritable(false)` 本身返回 false（Windows） | `tmp.writeText` 会先失败，测不到 ②③ 之间 |
     *
     * 更关键的一条：**`delete()` 一旦真的成功，紧接着的 rename 在本机就必然会成功**
     * （目标位置已经空了，rename 只需移动，没有"覆盖"这件事）。也就是说 ②③ 那个窗口要求的
     * "删成功 + rename 失败"在本机的文件系统语义下自相矛盾 —— 它能发生只可能靠随机故障
     * （ENOSPC / EIO / 拔盘），那在单测里不是"稳定复现"，是"碰运气"。
     *
     * ⇒ 所以本用例**不假装复现**，它断言的是**可观察的不变量**：无论 rename 成不成，
     * 两次写之间 target 都不许消失。它在生产平台（POSIX）上守的是不变量本身；在开发机上
     * 顺带还走一次"rename 失败"的真实出口（见下），因此能钉住"失败时也不许删旧文件"。
     *
     * ## 本机实际走到的分支（[probe] 实测，已记录）
     *
     * Windows 上 `File.renameTo` **不覆盖已存在文件**（探针：`renameTo` 返回 false、旧内容
     * 原样保留）。所以第二次写在本机走的是**失败分支**：`second == null`、target 内容仍是
     * 哨兵、`.tmp` 里留着新 body。这条路径下"旧 overlay 幸存"是真被检查到的。
     * 但请注意：**它检查的是新实现的行为**；把它变回旧实现时，旧代码会先 `delete()` 掉
     * target，然后 rename 因为目标已空而**成功** ⇒ 旧实现同样"没有丢文件"，本用例不会红。
     * （变异验证实测确认了这一点：加回 `target.delete()` 后只有第 1、3 条变红，本条仍绿。）
     *
     * ## 那这条用例的价值是什么
     *
     * 1. 它是**真实调用**（不是源码形状）：证明 `writeLauncherPatchOverlay` 在失败出口上
     *    返回 null、并且**没有**把上一份可用内容清空（截断 / 清空是另一类可能的实现错误）；
     * 2. 它钉住"非 null ⇒ 内容一定是新 body"（防止将来有人返回 target 却根本没写进去）；
     * 3. 它钉住"新 body 不许两边都没有"（失败时 `.tmp` 必须留存，可人工恢复）。
     *
     * **跨平台、且对 SP-08 本身有判别力的兜底是
     * [overlayWriteDoesNotDeleteTargetBeforeRename] 那条源码断言** —— 它不依赖任何
     * 文件系统语义，`target.delete()` 一被加回来就必然变红（已由变异验证确认）。
     */
    @Test
    fun overlayWritePreservesTheOldFileWhenRenameWouldFail() {
        val instanceId = "inst-sp08"
        val root = setupPathsForOverlay()
        val scripts = File(root, "scripts")
        val instanceDir = File(File(root, "instances"), instanceId)
        val target = File(instanceDir, LAUNCHER_PATCH_OVERLAY)

        // --- 第一次写：正常路径 ---
        val first = writeLauncherPatchOverlay("/opt/dsh/instances/$instanceId", scripts)
        assertNotNull(
            "前置：第一次写必须成功（否则下面的「旧 overlay 是否幸存」无从谈起）",
            first
        )
        assertTrue("前置：第一次写之后 target 必须是文件", target.isFile)

        // --- 造出"上一份可用的 overlay"：用哨兵内容替换掉它 ---
        val sentinel = "SENTINEL-OLD-OVERLAY:上一份可用的 overlay\n"
        target.writeText(sentinel, Charsets.UTF_8)

        // --- 第二次写：Windows 上 renameTo 不覆盖已存在文件 ⇒ 走失败分支；POSIX 上会覆盖成功 ---
        val second = writeLauncherPatchOverlay("/opt/dsh/instances/$instanceId", scripts)

        // ★ 不变量（不是"窗口"的判别式，理由见本用例 KDoc）：无论 rename 成不成，
        //   **旧 overlay 都不许消失** —— 失败出口也不许把它清空/截断。
        assertTrue(
            "★ SP-08：第二次写之后 target **不存在** —— 这正是「先删旧目标、再 rename」制造的" +
                "毁数据窗口（删成功 + rename 失败）。上一份可用 overlay 被丢掉了，" +
                "而调用方只看返回值是否 null，会当成「这次没写成功，不影响启动」，代价完全不可见。" +
                "（rename 失败时应该保留旧 overlay，而不是先把它删掉）",
            target.exists()
        )

        val content = target.readText()
        // 不变量 1：非 null ⇒ 内容一定是新 body（不许返回指向旧内容的 File）
        if (second != null) {
            assertFalse(
                "★ 返回值非 null 表示这次写成功了 —— 那 target 的内容必须已经不是哨兵。" +
                    "实际内容：<$content>",
                content == sentinel
            )
            assertTrue(
                "★ 返回值非 null 时 target 必须包含新 body（launcher-assets 那一行）。实际内容：<$content>",
                content.contains("launcher-assets")
            )
        } else {
            // rename 失败的出口（Windows 开发机的常态）：旧 overlay 原样保留，新 body 留在 .tmp
            assertEquals(
                "★ rename 失败时旧 overlay 必须**原样**保留（不是被截断、不是被清空）。" +
                    "实际内容：<$content>",
                sentinel,
                content
            )
            val tmp = File(instanceDir, "$LAUNCHER_PATCH_OVERLAY.tmp")
            assertNull(
                "前置：rename 失败的出口必须返回 null（调用方据此不传 --patch）",
                second
            )
            assertTrue(
                "不变量 2：rename 失败时新 body 不许两边都没有 —— 必须仍在 ${tmp.name} 里（可人工恢复）。" +
                    "target 内容已是旧哨兵，tmp 不存在 ⇒ 新 body 丢了",
                tmp.isFile && tmp.readText().contains("launcher-assets")
            )
        }
    }

    // ==================================================================
    // 三、同构性钉子：overlay 与 manifest 必须是同一个 rename 假设
    // ==================================================================

    /**
     * `writeManifest`（剥注释后）同样**不许** `delete()` 目标文件。
     *
     * 这两处是同一个代码库里对 `File.renameTo` 的同一个假设：**在同一文件系统内 rename
     * 覆盖已存在目标是原子的**。SP-08 的成因就是两处假设不一致（`writeManifest` 走纯
     * rename、`writeLauncherPatchOverlay` 先删再 rename），于是后者独家拥有毁数据窗口。
     *
     * 如果将来 `writeManifest` 也被人"顺手加个先删"（例如为某个平台兼容），这条会立刻变红，
     * 提醒：要么两边一起改、要么先重新设计整段（备份 + 回滚），不能再靠"删了再赌 rename"。
     */
    @Test
    fun overlayAndManifestUseTheSameRenameAssumption() {
        val manifestBody = stripComments(
            sliceToEnd(instancesSource(), "fun writeManifest(f: File, json: String)")
        )
        val overlayBody = stripComments(
            sliceFun(
                instancesSource(),
                "fun writeLauncherPatchOverlay(",
                "/** 启动器自带的 `/assets` 缓存插件"
            )
        )

        // ★ 这条是**对称**断言，两侧都要查 —— 理由（变异验证实测）：
        //   如果只查 writeManifest，那么"把 target.delete() 加回 writeLauncherPatchOverlay"
        //   的变异会让它照旧全绿（被变异的不是我查的那一侧），这条钉子等于没有任何判别力。
        //   反过来只查 overlay 也不行：writeManifest 那边哪天被"顺手加个先删"同样要有人喊。
        //   所以两侧都断言"有 rename、无 delete"，任一侧退化都会红。
        // ★ R49 审计补正：这里原来也是宽判据 `manifestBody.contains("delete(")`。
        //   当前 writeManifest 恰好零 delete()，所以它是绿的 —— 但那只是"还没人加"。
        //   一旦有人为失败清理加 `tmp.delete()`（与 overlay 那边 S-08 之后必须做的一样），
        //   这条会误报。判据与 overlay 两侧统一：只禁删 target，允许删 tmp。
        val manifestDeletesTarget = Regex("""([\w.$?()\[\], ]+?)\s*(?:\?\.)?\s*\.\s*delete(?:IfExists)?\s*\(""")
            .findAll(manifestBody).any { m ->
                val receiver = m.groupValues[1]
                val tail = manifestBody.substring(m.range.first, minOf(manifestBody.length, m.range.last + 80))
                Regex("""\b(?:target|targetFile|overlay|overlayFile)\b""").containsMatchIn(receiver) ||
                    receiver.contains("LAUNCHER_PATCH_OVERLAY") ||
                    (receiver.endsWith("Files") && Regex("""\btarget\b""").containsMatchIn(tail))
            }
        assertFalse(
            "★ writeManifest 的可执行代码里删了**目标文件** —— 它与 writeLauncherPatchOverlay 必须" +
                "共用同一个假设（rename 覆盖已存在目标是原子的），先删再 rename 会重新引入" +
                "「删成功 + rename 失败 ⇒ 目标没了」的窗口。若确实需要变（例如支持 Windows 宿主），" +
                "必须整段重新设计（备份 + 回滚），并同步改本文件的三条用例。" +
                "（删 `tmp` 是允许的：失败路径要清残留。）" +
                "实际函数体：<$manifestBody>",
            manifestDeletesTarget
        )
        assertTrue(
            "前置：writeManifest 必须仍然走 renameTo(f)（否则这条断言守的不是同一个假设）。" +
                "实际函数体：<$manifestBody>",
            manifestBody.contains("renameTo(f)")
        )

        // ★ R49 审计补正：这里原来也是 `overlayBody.contains("delete(")` —— 与本文件另一条
        //   `overlayWriteDoesNotDeleteTargetBeforeRename` **切的是同一个函数体**（都用 sliceFun
        //   取 writeLauncherPatchOverlay）。我只收紧了那一条、留了这条宽判据，于是：
        //   只要有人加回**合法**的 `tmp.delete()`（S-08 之后失败路径必须删 tmp，否则每次启动
        //   多一个残留），断言 #1 绿、这条红 ⇒ **照样误报 SP-08 复发**。
        //   改了一半比不改更糟：把一个自洽的宽判据拆成互相矛盾的两条。
        //   现在两条用**同一个**判据（与 #1 一致）：只禁"删 target"，允许删 tmp。
        assertFalse(
            "★ 同构性破裂：writeLauncherPatchOverlay 又在 rename 之前删**目标**了（writeManifest 不删）。" +
                "两处对 File.renameTo 的假设必须一致：都做原子 rename、都不先删。" +
                "（删 `tmp` 是允许且必需的：失败路径要清残留。）实际函数体：<$overlayBody>",
            Regex("""\b(?:target|targetFile|overlay|overlayFile)\s*\.\s*delete\s*\(""")
                .containsMatchIn(overlayBody)
        )
        assertTrue(
            "前置：writeLauncherPatchOverlay 必须仍然走 renameTo（否则这条断言守的不是同一个假设）。" +
                "实际函数体：<$overlayBody>",
            overlayBody.contains("renameTo(")
        )
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 把 [DshPaths] 指到临时目录，并造出 `writeLauncherPatchOverlay` 需要的现场。
     *
     * 只有 `ROOT_DIR` 是必需的（`hostPathForGuestDir` 用它把 guest 路径折成宿主目录），
     * 其余字段一并写全是为了不让 `DshPaths` 处于"半初始化"状态 —— 万一将来函数体里
     * 多读一个路径，这里不会因为 lateinit 抛异常而把用例的失败原因变成环境问题。
     *
     * 反射的理由与 `R41DeleteTransactionTest.setUpPaths` 相同：路径字段是
     * `lateinit var ... private set`，只有 `loadPaths(Context)` 能写，而 JVM 单测没有 Context。
     * 收尾必须复原（[restoreGlobalState]）：`DshPaths` 是进程级单例，漏了会把**后面**的用例带红。
     */
    private fun setupPathsForOverlay(): File {
        val root = File(tmpRoot, "dsh-sp08").apply { mkdirs() }
        File(root, "instances").mkdirs()
        val scripts = File(root, "scripts").apply { mkdirs() }
        // 插件本体必须在：它是 writeLauncherPatchOverlay 的入口条件（不存在就直接返回 null）
        File(scripts, LAUNCHER_ASSETS_PLUGIN).writeText("// stub\n")
        val saved = LinkedHashMap<Field, Any?>()
        fun set(name: String, value: Any?) {
            val f = DshPaths::class.java.getDeclaredField(name).apply { isAccessible = true }
            saved[f] = runCatching { f.get(DshPaths) }.getOrNull()
            f.set(DshPaths, value)
        }
        set("ROOT_DIR", root.absolutePath)
        set("ROOTFS_DIR", File(root, "rootfs").absolutePath)
        set("INSTANCES_DIR", File(root, "instances").absolutePath)
        set("INSTANCES_MANIFEST", File(root, "instances.json").absolutePath)
        set("SCRIPTS_DIR", File(root, "scripts").absolutePath)
        set("NPM_CACHE_DIR", File(root, "npm-cache").absolutePath)
        set("LOG_FILE", File(root, "logs/runtime.log").absolutePath)
        set("TARBALL_CACHE_DIR", File(root, "tarballs").absolutePath)
        set("TMP_DIR", File(root, "tmp").absolutePath)
        set("isLoaded", true)
        savedPaths = saved
        pathsSaved = true
        return root
    }

    // ==================================================================
    // 源码形状工具（与 R40InstallForkGuardTest 同口径）
    // ==================================================================

    private fun instancesSource(): String = mainSourceFile("core/DshInstances.kt").readText()

    /**
     * 从源码里切出一个**函数体**：取 [header] 之后、[nextHeader] 之前的那一段。
     *
     * 不用"配对大括号"：KDoc 注释里会出现**单个**撇号 / 括号，会让扫描器误吞后面的括号，
     * 切出来的片段翻倍、断言落在完全无关的代码上（R40 的 `sliceFun` KDoc 里记着那次实测）。
     * 用**下一个成员声明的行首锚点**切片没有这个问题 —— 它只看子串位置，不看括号。
     *
     * 前提由调用方断言（找不到任一头都直接 AssertionError，不会静默切成空串）。
     */
    private fun sliceFun(src: String, header: String, nextHeader: String): String {
        val start = src.indexOf(header)
        if (start < 0) throw AssertionError("找不到 $header")
        val end = src.indexOf(nextHeader, start + header.length)
        if (end < 0) throw AssertionError("找不到 $header 之后的下一个成员声明：$nextHeader")
        return src.substring(start, end)
    }

    /** 切到文件末尾（用于文件里**最后一个**成员：`writeManifest`）。 */
    private fun sliceToEnd(src: String, header: String): String {
        val start = src.indexOf(header)
        if (start < 0) throw AssertionError("找不到 $header")
        return src.substring(start)
    }

    /**
     * 剥掉行注释与块注释（**保留换行**，尽量保持行号可读）。
     *
     * ★ 这一步是这两条源码断言的**有效性前提**：SP-08 的修复把 "不要先 delete" 的理由写进了
     * 注释（而注释里必然出现 `target.delete()` 这个词），不剥注释就必然假绿。
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

    /** 定位主源码文件（与 R29/R39/R40 的 `mainSourceFile` 同口径：从 cwd 往上找几层） */
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
        throw AssertionError("找不到 $rel（请在仓库根或 FCL/ 目录下运行测试）")
    }
}
