package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **SP-05**（Round 43）：实例日志文件名必须与**目录名**用同一个折叠规则 —— 两者从此不可能再分叉。
 *
 * 注意口径：SP-05 消灭的是「日志与目录两套规则分叉」，**不是**消灭碰撞。
 * `safeInstanceDirName` 本身**不单射**（`a\b` 与 `a_b` 是两个可达的真实目录名，却折成同一个），
 * 日志这一层只能把碰撞降到与目录**同等级**，不可能单独消灭它 —— 那要改折叠规则本身。
 * 见 [backslashIsARealDirectoryNameSoThisIsAKnownCollision] 与 `docs/BUGLEDGER.md` 的
 * 「SP-05 修复中暴露的新缺陷（round43）」。
 *
 * ## 缺陷（修前）
 * `DshLogBus` 里私有的 `safeFileId` 只保留 `isLetterOrDigit` / `-` / `_`，其余字符**一律折成 `_`**；
 * 而目录名用的 `DshPaths.safeInstanceDirName` 只折 `/` `\` NUL。两套规则 ⇒ 一批**不同**的合法 id
 * 折到**同一个**日志文件名：
 *
 * ```
 * id "my app"  ┐
 * id "my.app"  ┼→ safeFileId 全是 "my_app" → 同一个 instance-my_app.log
 * id "my_app"  ┘
 * ```
 *
 * 这些 id 不是假想：`rebuildMissingInstances` 原样拿目录名当 id（零校验），而 `instances/`
 * 通过 proot 的 `--bind` 对 rootfs 可写 —— rootfs 里 `mkdir "my app"` 后冷启就会被收编成实例。
 * 后果两条：**日志串台**；`DshInstances.delete` 删一个实例会把另一个实例的日志一起删掉。
 *
 * ## 修法（方案 a）
 * `instanceLogFile` 改为 `instance-${DshPaths.safeInstanceDirName(id)}.log`，`safeFileId` 整个删除。
 *
 * ## 本类钉住的东西
 * 1. **不串台**：旧规则下会碰撞的每一对 id（且折叠后目录名不同 ⇒ 真的是两个实例），现在给出不同
 *    文件；并且**先断言它们修前确实碰撞**，否则这条测试可能是空转（一条永远绿的测试不证明任何事）。
 *    注意范围：这只覆盖 [collidingPairs] 那批语料，不是"所有字符串两两不同"（见本节末尾的反斜杠）。
 * 2. **无迁移**：`buildId` 生成的 `inst-<ms>-<seq>-<hex>` 与收编来的普通目录名，文件名**逐字节不变**。
 * 3. **口径统一**：文件名里那一段恒等于 `DshPaths.safeInstanceDirName(id)`（目录名用的同一个函数）
 *    —— 这条不变量才是根因的修复，前两条是它的推论。
 * 4. **不越界**：含 `/` `\` NUL 的 id 仍被折掉、不写出 logs 目录。
 * 5. **事实更正**：`Char.isLetterOrDigit('中') == true` ⇒ **中文从来就没被折叠过**，
 *    碰撞成因是空格/点/括号这类标点，不是中文。见 [chineseHasNeverBeenFolded]。
 *
 * ## 为什么"所有可能字符串两两不同"不是这里的判据
 * `safeInstanceDirName` 会把 `/`、`\`、NUL 折成 `_`，所以 `"a/b"`、`"a\u0000b"`、`"a_b"` 三者
 * **同为** `"a_b"`。但那不是 SP-05：`/` 与 NUL **作为目录名根本造不出来**（内核直接拒绝 ⇒ `File.name`
 * 永不含它们 ⇒ 只能来自手工编辑的 `instances.json`），即那两个是**同一个实例的别名**。
 * 修复后日志文件与目录同口径 ⇒ 别名共享日志，与"同一实例只有一份日志"一致。
 * 真正要消灭的是「不同**目录**的实例共用一份日志」，那就是 [collidingPairs] 覆盖的形态 ——
 * 那批 id 每个都是可能的真实目录名，且折叠后目录名**两两不同**（本类有用例断言这一点）。
 *
 * ⚠️ **反斜杠 `\` 不在这套"别名"里**：它在 Linux/Android 上是合法文件名字符，`a\b` 是一个**可达的
 * 真实目录名**，与 `a_b` 是两个不同目录 —— 但两者折叠后相同。这是 `safeInstanceDirName` 本身的
 * 不单射，属 R43 新登记的残留缺陷，见 [backslashIsARealDirectoryNameSoThisIsAKnownCollision]。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.R43LogFileIdTest"
 */
class R43LogFileIdTest {

    // --- 语料 ---------------------------------------------------------------

    /**
     * 修前会互相碰撞的 id 对。每一对满足两个条件（[collidingIdsNowGetDistinctLogFiles] 逐条断言）：
     * 1. 旧规则下折叠成**同一个**文件名 ⇒ 修前真的碰撞；
     * 2. 都**不含** `/` `\` NUL，且 `safeInstanceDirName` 折叠后**互不相同** ⇒ 它们真的是两个不同的
     *    目录、两个不同的实例（不是同一实例的别名）。
     *
     * 括号那对是 `"a(b)" to "a_b_"` 而不是 `"a_b"` —— 旧规则把**两个**括号各折一个 `_`，
     * 所以 `a(b)` → `a_b_`，它撞的是 `a_b_`（`a_b` 与它不撞）。这类细节必须实测，
     * 凭直觉写会得到一条永远绿（或永远红）的测试。
     */
    private val collidingPairs: List<Pair<String, String>> = listOf(
        "my app" to "my.app",
        "my app" to "my_app",
        "my.app" to "my_app",
        "inst-1.2" to "inst-1_2",
        "a(b)" to "a_b_",
        "a,b" to "a_b",
        "a·b" to "a_b",      // U+00B7 中点
        "a　b" to "a_b",      // U+3000 全角空格
        "a\tb" to "a_b",
        "a+b" to "a_b",
        "a#b" to "a_b",
        "a%b" to "a_b",
        "a:b" to "a_b",           // ':' 在安卓内部存储合法，不再被折（见 noColonFoldingOnPurpose）
        "中 文" to "中_文",        // 中文保留、空格折叠 ⇒ 旧规则下这对会撞
        "🙂" to "__",   // emoji：两个代理项各折一个 '_'
        "." to "_",               // 旧规则：两个都折成 "_"
        ".." to "__"              // 旧规则：两个点各折一个 '_'
    )

    /** 正常 id：`buildId` 的产物 + 收编来的普通目录名。它们的文件名**逐字节不许变**（防迁移）。 */
    private val normalIds: List<String> = listOf(
        "inst-1700000000000-3-7f2a",
        "inst-1700000000000-3-0",
        "inst-123-1-abc",
        "inst-orphan-01",
        "inst-test-file-a-0"
    )

    /**
     * 单射语料全集：每个 id 必须折叠到**互不相同**的目录名（即真的是不同实例）。
     * 不含 `a/b` 一类分隔符别名 —— 它们归 [separatorAliasesShareAFileBecauseTheyShareADirectory]；
     * 也不含 `""`（它折叠成 `"_"`，与 id `"_"` 是同一个目录 → 同为别名）。
     */
    private val injectiveIds: List<String> =
        (normalIds + collidingPairs.flatMap { listOf(it.first, it.second) } + listOf("中文"))
            .distinct()

    /** 只用于"不越界"断言的越界形态（它们的共同折叠结果是同一个目录，见类注释） */
    private val escapingIds: List<String> = listOf(
        "", ".", "..", "a/b", "a\\b", "a\u0000b", "../../etc/passwd", "..\\..\\etc\\passwd"
    )

    // --- 辅助 ---------------------------------------------------------------

    private fun nameOf(id: String): String = DshLogBus.instanceLogFile(id).name

    /**
     * 修前那份折叠规则，**原样复刻**。唯一用途是证明语料有效：每一对 [collidingPairs] 在它下面
     * 必须折叠成同一个名字。否则"修后不同"可能因为"修前就不同"而永远成立 —— 那是空转。
     */
    private fun legacySafeFileId(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    // --- 1. 单射（核心判据）-------------------------------------------------

    /**
     * **修复的判据本身**：每一个修前会碰撞的 id 对，修后必须落在**不同**文件上。
     *
     * 三段断言必须同时成立，测试才有鉴别力：① 修前确实碰撞；② 它们是不同目录（真的不同实例）；
     * ③ 修后确实分开。
     */
    @Test
    fun collidingIdsNowGetDistinctLogFiles() {
        for ((a, b) in collidingPairs) {
            assertEquals(
                "语料无效：[$a] 与 [$b] 在旧规则下并不碰撞（这条断言用来证明测试有鉴别力）",
                legacySafeFileId(a),
                legacySafeFileId(b)
            )
            assertNotEquals(
                "语料无效：[$a] 与 [$b] 折叠到同一个目录名，它们本来就是同一个实例（别名）",
                DshPaths.safeInstanceDirName(a),
                DshPaths.safeInstanceDirName(b)
            )
            assertNotEquals(
                "SP-05 未修：id [${a.escaped()}] 与 [${b.escaped()}] 共用同一个日志文件 ${nameOf(a)}" +
                    " ⇒ 日志串台，且删一个会删掉另一个的日志",
                nameOf(a),
                nameOf(b)
            )
        }
    }

    /**
     * 更强的形式：**整个单射语料**两两不同（比逐对枚举更能防将来漏项）。
     *
     * 断言写成"按文件名分组后没有一组的 id 数 > 1"，失败信息直接列出撞车的文件名与 id，
     * 排障时不需要再猜是哪一对。
     */
    @Test
    fun everyDistinctIdGetsADistinctLogFileName() {
        assertEquals(
            "语料自身必须去重，否则本条断言无意义",
            injectiveIds.size,
            injectiveIds.distinct().size
        )
        val byName = LinkedHashMap<String, MutableList<String>>()
        for (id in injectiveIds) byName.getOrPut(nameOf(id)) { mutableListOf() }.add(id)
        val clashes = byName.filterValues { it.size > 1 }

        assertTrue(
            "不同实例不得共用日志文件，实际撞了 ${clashes.size} 组：" +
                clashes.entries.joinToString("; ") { (f, ids) ->
                    "\"$f\" ← " + ids.joinToString(" / ") { "[" + it.escaped() + "]" }
                },
            clashes.isEmpty()
        )
    }

    // --- 2. 无迁移（保护现有用户）-------------------------------------------

    /**
     * **现有日志文件名逐字节不变**：`buildId` 的 `inst-<ms>-<seq>-<hex>` 与收编来的普通目录名
     * 都是 `safeInstanceDirName` 的不动点，所以升级后它们仍写原来那个文件 —— 不是"旧日志消失"。
     */
    @Test
    fun buildIdStyleNamesAreByteIdenticalToBefore() {
        for (id in normalIds) {
            assertEquals(
                "正常 id [$id] 的日志文件名必须与修前逐字节相同（否则是迁移，用户旧日志变孤儿）",
                "instance-$id.log",
                nameOf(id)
            )
            // 同一件事换个角度再钉一遍：旧规则对它是恒等，所以修前修后必须相等。
            assertEquals(
                "正常 id [$id] 在旧规则下也不折叠，修后必须等于它",
                "instance-${legacySafeFileId(id)}.log",
                nameOf(id)
            )
            assertEquals(
                "正常 id [$id] 必须是目录名折叠的不动点（否则它本来就不是目录名）",
                id,
                DshPaths.safeInstanceDirName(id)
            )
        }
    }

    // --- 3. 口径统一（根因）-------------------------------------------------

    /**
     * **唯一的根因不变量**：文件名中间那一段，恒等于目录名用的那个折叠函数的结果。
     *
     * `DshPaths.instanceDir(id) = File(INSTANCES_DIR, safeInstanceDirName(id))`，所以这条断言等价于
     * 「日志文件名去掉前后缀 == 该实例的**目录名**」—— 两者从此不可能再分叉。
     *
     * 为什么不直接断言 `DshPaths.instanceDir(id).name`：`INSTANCES_DIR` 是 `lateinit`，在没跑过
     * `loadPaths` 的 JVM 测试里读它会抛 `UninitializedPropertyAccessException`；而
     * `safeInstanceDirName` 只读入参、不碰任何静态状态。两者只差一层 `File(...)` 包装。
     */
    @Test
    fun logFileNameUsesExactlyTheDirectoryNameRule() {
        for (id in injectiveIds + escapingIds) {
            val folded = DshPaths.safeInstanceDirName(id)
            assertEquals(
                "id [${id.escaped()}] 的日志文件名必须由 safeInstanceDirName 生成",
                "instance-$folded.log",
                nameOf(id)
            )
            assertEquals(
                "文件名去掉 instance- 与 .log 后必须逐字节等于目录名折叠结果（口径不许分叉）",
                folded,
                nameOf(id).removePrefix("instance-").removeSuffix(".log")
            )
        }
    }

    // --- 4. 中文从来就没被折叠（钉住事实）-----------------------------------

    /**
     * ★ Round 43 的事实更正：`Char.isLetterOrDigit('中') == true`（Unicode `Lo` 类），
     * 所以旧白名单**不折叠中文**。规划稿与主 Agent 的最初核实稿都写过"中文被折成 `_`"，那是错的；
     * 碰撞的真实成因是空格、点、括号这类标点。
     *
     * 这条测试有两个作用：① 钉住事实，防止下一轮又有人据"中文会被折"做方案取舍；
     * ② 说明方案 a 没有引入"Unicode 进文件名"这个**新**风险 —— 它早就在文件名里了。
     */
    @Test
    fun chineseHasNeverBeenFolded() {
        assertEquals("中文从未被折叠过（isLetterOrDigit 对 CJK 为 true）", "中文", legacySafeFileId("中文"))
        assertEquals("instance-中文.log", nameOf("中文"))
        assertTrue("日志文件名里本来就该有中文", nameOf("中文").contains("中文"))
        assertFalse("中文不该被折成下划线", nameOf("中文").contains('_'))
    }

    // --- 5. 方案 a 的新增形状：含空格 / 含 ':' 的文件名 ----------------------

    /**
     * 方案 a 的直接后果：含空格的 id 得到**含空格的文件名**（`instance-my app.log`）。
     *
     * 这不是"炸了"，而是与目录名一致的必然结果（`instances/my app/` 本来就在同一套流程里，
     * 脚本侧 `INSTANCE_DIR` 全程带引号）。安卓内部存储（ext4/f2fs）合法；`DshLogBus` 的落盘与轮转
     * 只做 `File` 级 `appendText` / `readBytes` / `writeBytes`，**没有任何按空格切分文件名的解析**
     * （本类只断言形状；"含空格的文件名能否真的写进去"由 [idWithSpaceIsActuallyWritable] 实测）。
     * 钉住形状是为了让"运维取文件要用引号"这件事在代码里可见。
     */
    @Test
    fun idWithSpaceProducesAFileNameWithSpaceAndNoSeparators() {
        val f = DshLogBus.instanceLogFile("my app")

        assertEquals("instance-my app.log", f.name)
        assertTrue("含空格的 id 会产生含空格的文件名（与目录名一致）", f.name.contains(' '))
        assertFalse("文件名不得含 '/'", f.name.contains('/'))
        assertFalse("文件名不得含 '\\'", f.name.contains('\\'))
        assertTrue("仍应以 instance- 开头", f.name.startsWith("instance-"))
        assertTrue("仍应以 .log 结尾", f.name.endsWith(".log"))
        assertEquals(
            "含空格的 id 的日志文件必须仍在同一个 logs 目录（不因 id 形状而分家）",
            DshLogBus.instanceLogFile("inst-safe").parentFile,
            f.parentFile
        )
        // 与它修前会撞的那两个 id 已经分开（这是 SP-05 本体，不只是形状）
        assertNotEquals(f.name, nameOf("my.app"))
        assertNotEquals(f.name, nameOf("my_app"))
    }

    /**
     * **含空格的文件名真的能写、能读、能被轮转**（不是"看着合法"）。
     *
     * 用 `DshLogBus` **生产代码里同一个** `rotateIfTooBig`（经 `rotateFileForTest` 出口）对一个
     * 含空格的名字做一次实际 IO，证明方案 a 的新文件名形状在文件系统与写入逻辑上都没问题。
     * 用临时目录而不碰 `logs/`，避免在开发者机器上留垃圾。
     */
    @Test
    fun idWithSpaceIsActuallyWritable() {
        val dir = java.nio.file.Files.createTempDirectory("dsh-sp05").toFile()
        try {
            val f = java.io.File(dir, DshLogBus.instanceLogFile("my app").name)
            assertEquals("instance-my app.log", f.name)
            f.parentFile?.mkdirs()
            f.appendText("hello\n")
            assertEquals("含空格的文件名必须能正常写入", "hello\n", f.readText())
            // 轮转逻辑对它的行为与普通文件名完全一样（小文件不截断；这里只证"不炸、不误截"）
            DshLogBus.rotateFileForTest(f)
            assertEquals("未超限时轮转不得改动文件", "hello\n", f.readText())
            assertTrue("轮转后文件仍在该目录", f.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * **`:` 故意不再被折**（与旧的 `DshLogBusRoutingTest.instanceLogFileEscapesUnsafeIdCharacters`
     * 里 `assertFalse(name.contains(':'))` 那条相反 —— 那条编码的正是旧规则本身，已显式改写）。
     *
     * 理由：`:` 在安卓内部存储是合法字符。为了与目录名逐字节一致，**不能**只折日志文件名里的 `:`；
     * 若一定要折，就必须连 `DshPaths.safeInstanceDirName` 一起折 —— 那等于改**目录名**，
     * 直接打断 `id ↔ 目录` 的 identity 映射（`DshInstances` / `DshInstaller` / `DshRuntime`
     * 全按 id 拼路径，其中三处还是手拼 guest 路径、绕过 `DshPaths`）。
     * 用"文件名里多一个 `:`"换"映射不破"，这个交换是划算的。
     */
    @Test
    fun noColonFoldingOnPurpose() {
        assertEquals("instance-a:b.log", nameOf("a:b"))
        assertTrue("':' 保留（与目录名一致）", nameOf("a:b").contains(':'))
        assertNotEquals("它仍与 '_' 版本不同（单射不受影响）", nameOf("a:b"), nameOf("a_b"))
    }

    // --- 6. 不越界 ----------------------------------------------------------

    /**
     * id 里的路径分隔符仍必须被折掉：否则会写到别的目录（与 `DshPaths.instanceDir` 同一个折叠函数）。
     *
     * 主判据是"文件名里没有 `/` `\`、不是 `.`/`..`、且父目录就是 logs 目录"—— 这三条合起来**足以**
     * 证明不会越界（一个不含分隔符、又不是相对段的单个名字，拼在固定父目录下只能落在该目录里）。
     *
     * `canonicalPath` 那条只是交叉验证，且**在 Windows 宿主上会失败**：`canonicalPath` 要把路径交给
     * Win32，而 Windows 拒绝文件名里的 `:`（以及 0x01–0x1F 控制字符）。安卓（ext4/f2fs）接受它们 ——
     * 所以那是**测试宿主机**的限制、不是产品限制（同一个 `:` 也早就在 `instances/a:b/` 这种目录名里了）。
     * 因此对这批名字只跳过 `canonicalPath` 这一条交叉验证，不跳过上面的主判据，
     * 并用计数器保证"跳过"没有把交叉验证整体关掉。
     */
    @Test
    fun noIdEscapesTheLogDirectory() {
        val refDir = DshLogBus.instanceLogFile("inst-safe").parentFile
        assertNotNull("日志目录不该是 null", refDir)
        var canonicalChecked = 0
        val canonicalSkipped = mutableListOf<String>()

        for (id in injectiveIds + escapingIds) {
            val f = DshLogBus.instanceLogFile(id)
            assertEquals(
                "id [${id.escaped()}] 的日志文件必须落在同一个目录",
                refDir?.absolutePath,
                f.parentFile?.absolutePath
            )
            assertFalse("id [${id.escaped()}] 的文件名里不得有 '/'", f.name.contains('/'))
            assertFalse("id [${id.escaped()}] 的文件名里不得有 '\\'", f.name.contains('\\'))
            assertNotEquals("id [${id.escaped()}] 的文件名不得是相对段 '.'", ".", f.name)
            assertNotEquals("id [${id.escaped()}] 的文件名不得是相对段 '..'", "..", f.name)
            assertTrue(
                "id [${id.escaped()}] 的文件名不得被折成空",
                f.name.length > "instance-.log".length
            )
            // 交叉验证（宿主机能解析该名字时才做，理由见 KDoc）
            val canonical = runCatching { f.canonicalPath }.getOrNull()
            if (canonical == null) {
                canonicalSkipped += id.escaped()
            } else {
                canonicalChecked++
                assertTrue(
                    "id [${id.escaped()}] 的绝对路径不得逃出 logs 目录: $canonical",
                    canonical.startsWith(refDir!!.canonicalPath)
                )
            }
        }
        // 防止这条"跳过"把整个交叉验证悄悄关掉：非法的名字只能是少数几个。
        assertTrue(
            "交叉验证被静默关掉了：只检查了 $canonicalChecked 条，跳过了 $canonicalSkipped",
            canonicalChecked * 2 >= injectiveIds.size
        )
        // 跳过的必须**只**是宿主机文件名非法的那些（`:` 与控制字符），不能是别的意外原因。
        assertTrue(
            "意外的 canonicalPath 失败（不只是 ':'/控制字符）：$canonicalSkipped",
            canonicalSkipped.all { id -> id.contains(':') || id.contains("\\t") || id.contains("\\u00") }
        )
    }

    /**
     * **分隔符别名共享日志文件 —— 这是正确的，不是 SP-05 残留**。
     *
     * 语料只含「**作为目录名根本造不出来**」的形态：内核（Linux/Android）只禁止 `/` 与 NUL，
     * 所以 `"a/b"` / `"a\u0000b"` 折叠后与 `"a_b"` 全是 `"a_b"`，而 `DshPaths.instanceDir` 对它们
     * 给出的也是**同一个目录**（`File.name` 永不含这两个字符 ⇒ 只能来自手工编辑的 `instances.json`），
     * 即它们是**同一个实例的别名**。修复后日志与目录同口径 ⇒ 别名共享日志，
     * 与"一个实例只有一份日志"一致。
     *
     * ⚠️ 反斜杠**不在**此列：它在 Linux/Android 上是合法文件名字符，`a\b` 是一个可达的真实目录名，
     * 与 `a_b` 是两个不同目录却折成同一个 —— 那是 **`safeInstanceDirName` 自身不单射**，
     * 见 [backslashIsARealDirectoryNameSoThisIsAKnownCollision]。把它塞进"别名"语料
     * 曾把一条真缺陷伪装成"预期行为"，这里显式排除。
     *
     * 这与 SP-05 的区别在于：SP-05 是「**不同目录**的实例共用一份日志」
     * （`my app` / `my.app` / `my_app` 是三个真实目录），那才是缺陷。
     * 本类的 [collidingPairs] 全在那一侧，且有用例断言它们的目录名两两不同。
     */
    @Test
    fun separatorAliasesShareAFileBecauseTheyShareADirectory() {
        // 只含"作为目录名根本造不出来"的形态 —— 内核只禁止 '/' 和 NUL。
        // ⚠️ 反斜杠不在此列：它在 Linux/Android 上是合法字符，见
        //    backslashIsARealDirectoryNameSoThisIsAKnownCollision。
        val aliases = listOf("a/b", "a\u0000b", "a_b")
        // 前置：这是"文件名不可达 ⇒ 同一目录"的别名（不是不同实例被误判成别名）。
        // 注意 `a_b` 本身是可达目录名，但它与 `a/b` / `a\u0000b` 折到同一个目录 ——
        // "别名"关系是按折叠结果定义的，与"该名字能否由外部造出"是两件事。
        for (a in aliases) {
            assertEquals(
                "别名语料无效：[$a] 与 [a_b] 应指向同一个目录",
                "a_b",
                DshPaths.safeInstanceDirName(a)
            )
        }
        // 修复后：文件也同一份 —— 与目录同口径，正确。
        for (a in aliases) {
            assertEquals("同目录别名必须共用同一份日志（口径一致）", nameOf("a_b"), nameOf(a))
        }
        // 而"不同目录却共用日志"必须已被消灭：
        for ((a, b) in collidingPairs) {
            assertNotEquals(
                "SP-05 未修：[$a] 与 [$b] 是不同的目录，却共用日志 ${nameOf(a)}",
                nameOf(a),
                nameOf(b)
            )
        }
    }

    /**
     * ⚠️ **这条测试钉住的是一个仍然存在的缺陷 —— 它不是回归测试，修好后它会变红（那时要删掉/改写它）。**
     *
     * ## 事实
     * 在 Linux/Android 上，文件名里内核只禁止 `/` 与 NUL，**反斜杠 `\` 是合法普通字符**
     * （`mkdir 'a\b'` 真的会成功，产出名为 `a\b` 的目录）。而 `DshPaths.safeInstanceDirName`
     * 把 `\` 折成 `_` ⇒ `"a\b"` 与 `"a_b"` 是**两个不同且都可达**的目录名，却折到同一个 `"a_b"`。
     *
     * ## 为什么这是缺陷
     * 可达性：`DshInstances.rebuildMissingInstances` 零校验地把目录名当 id，而 `instances/`
     * 通过 proot 的 `--bind` 对 rootfs 可写 ⇒ 外部可造出 id `a\b`。
     * 于是 `DshPaths.instanceDir("a\b")` 返回的是 `instances/a_b` —— **另一个实例的目录**；
     * 两个逻辑实例共享同一个物理目录（`home/`、`workspace/`…），
     * 且 `DshInstances.delete` 的 `deleteRecursively()` 会连带删掉另一个实例的全部用户数据。
     *
     * ## 与 SP-05 的关系
     * SP-05 修的是「日志用一套规则、目录用另一套」的分叉，修好后日志与**目录**同口径
     * ⇒ 日志不再比目录更差。但折叠函数本身不单射，所以日志这一层**不可能**单独消灭这个碰撞；
     * 修它必须改折叠规则本身（例如不折 `\`），那会改变已存在目录名的解析结果，是破坏性迁移。
     * ⇒ 已单独登记：`docs/BUGLEDGER.md`「SP-05 修复中暴露的新缺陷（round43）——
     *    `safeInstanceDirName` 在可达目录名上**不单射**」，**不在 SP-05 范围**。
     */
    @Test
    fun backslashIsARealDirectoryNameSoThisIsAKnownCollision() {
        val withBackslash = "a\\b"
        val plain = "a_b"

        // ① 两个**不同**的名字（`\` 在 Linux/Android 上是合法文件名字符，不是分隔符语义的别名）
        assertNotEquals("前提：反斜杠版本与下划线版本是两个不同的名字", plain, withBackslash)

        // ② 但它们折到**同一个**目录名 —— 这正是缺陷
        assertEquals(
            "已知缺陷：ids [$withBackslash] 与 [$plain] 不是别名，却折叠到同一个目录名",
            "a_b",
            DshPaths.safeInstanceDirName(withBackslash)
        )
        assertEquals(
            "已知缺陷：ids [$withBackslash] 与 [$plain] 折叠结果相同",
            DshPaths.safeInstanceDirName(plain),
            DshPaths.safeInstanceDirName(withBackslash)
        )
        assertEquals("a_b", DshPaths.safeInstanceDirName(plain))

        // ③ 因此它们也共用同一份日志 —— 日志与目录**同口径**（这是 SP-05 的设计，符合预期）
        assertEquals(
            "SP-05 的设计：日志与目录同口径 ⇒ 目录折叠相同则日志文件也相同",
            nameOf(plain),
            nameOf(withBackslash)
        )
    }

    // --- 7. DshPaths 未就绪时不崩（[instanceLogFile] 的兜底分支）------------

    /**
     * `instanceLogFile` 在 `DshPaths.loadPaths` 还没跑时必须**不抛异常**并回落到临时目录
     * （`fileFor` 的注释解释了这条兜底为什么必须存在）。
     *
     * 改成委托 `DshPaths.safeInstanceDirName` 之后这一点不变：该函数只读入参、不读任何
     * `lateinit` 字段（`LOG_FILE` 的读取包在 `runCatching` 里）。本类全部用例都在"未加载"的
     * JVM 里跑 —— 它们能通过本身就是这条的证据；这里再把契约写成显式断言。
     */
    @Test
    fun worksWithoutDshPathsLoaded() {
        val f = DshLogBus.instanceLogFile("inst-no-paths")
        assertEquals("instance-inst-no-paths.log", f.name)
        assertNotNull("必须给出一个父目录（临时目录兜底）", f.parentFile)
        assertFalse("文件名不得为空", f.name.isEmpty())
    }

    // --- 工具 ---------------------------------------------------------------

    /** 把控制字符转成可读形式，让断言失败信息里的 id 不被终端吞掉 */
    private fun String.escaped(): String = buildString {
        for (c in this@escaped) {
            when {
                c == '\t' -> append("\\t")
                c == '\u0000' -> append("\\u0000")
                c == '　' -> append("\\u3000")
                c.code < 0x20 -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
    }
}
