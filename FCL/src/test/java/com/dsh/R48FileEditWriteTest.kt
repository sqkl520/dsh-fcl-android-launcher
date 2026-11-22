package com.dsh

import com.dsh.core.needsEmptyFileConfirmation
import com.dsh.core.writeFileAtomically
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * round48 / BL-249：配置文件编辑器的**写入内核**。
 *
 * ## 为什么这些用例是重点
 *
 * 编辑器的 UI（对话框、EditText）只能靠真机点，而 ADB 的 `input text` 在 Android 10
 * 上对已激活的 EditText 不起作用（实测：点了编辑框、发了 `input text`，内容一个字符都没变）。
 * 所以"用户改完点保存"这一步没法自动化。
 *
 * 但**真正会毁数据的部分不是 UI，是写入**：备份、原子替换、失败时不留垃圾。
 * 这些是纯文件操作，可以完整覆盖 —— 本文件就是干这个。
 *
 * ## 钉住的不变量
 *
 * 1. 写之前必须留 `.bak`（否则写坏了没得回滚）；
 * 2. 写必须是**原子**的（先 `.tmp` 再 rename），中途失败不能留下半截目标文件；
 * 3. rename 失败时 `.tmp` 必须被清掉（否则实例目录里越堆越多）；
 * 4. 目标不存在时也能正常创建（首次编辑）。
 */
class R48FileEditWriteTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("r48-edit").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ===== 原子写 ==========================================================

    /**
     * 覆盖已存在的文件：内容更新 + 留下 `.bak` 保存**旧**内容。
     *
     * 变异验证：把 `writeFileAtomically` 里备份那一段删掉 ⇒ 本条变红（.bak 不存在）。
     */
    @Test
    fun overwriteKeepsBackupOfPreviousContents() {
        val target = File(dir, "launcher.patch.yml")
        target.writeText("OLD", Charsets.UTF_8)

        writeFileAtomically(target, "NEW")

        assertEquals("目标被更新", "NEW", target.readText(Charsets.UTF_8))
        val backup = File(dir, "launcher.patch.yml.bak")
        assertTrue("★ 必须留下 .bak", backup.isFile)
        assertEquals("★ 备份里必须是**旧**内容（写之前拷的）", "OLD", backup.readText(Charsets.UTF_8))
    }

    /**
     * 连续写两次，`.bak` 只保留**一份**且是"上一次"的内容 —— 不能无限增长。
     *
     * 为什么专门测：备份用 `copyTo(overwrite = true)` 是覆盖式，如果哪天改成
     * "带时间戳的文件名"，实例目录会堆积历史副本。这条把它钉住。
     */
    @Test
    fun backupIsOverwrittenNotAccumulated() {
        val target = File(dir, "config.yml")
        target.writeText("v1", Charsets.UTF_8)
        writeFileAtomically(target, "v2")
        writeFileAtomically(target, "v3")

        val baks = dir.listFiles()?.filter { it.name.endsWith(".bak") } ?: emptyList()
        assertEquals("★ 只应有一个 .bak", 1, baks.size)
        assertEquals("★ 备份内容是上上次的值（v2），不是最早的 v1", "v2", baks.single().readText(Charsets.UTF_8))
        assertEquals("v3", target.readText(Charsets.UTF_8))
    }

    /**
     * 目标**不存在**时也能创建（首次编辑一个新文件）。
     *
     * 此时不该造出 `.bak` —— 没有"旧内容"可备份，凭空一个空备份文件只会让人困惑。
     */
    @Test
    fun createsMissingFileWithoutBackup() {
        val target = File(dir, "brand-new.yml")
        assertFalse("前置：文件本来不存在", target.exists())

        writeFileAtomically(target, "hello")

        assertTrue(target.isFile)
        assertEquals("hello", target.readText(Charsets.UTF_8))
        assertFalse(
            "★ 原本没有文件时不该产生 .bak",
            File(dir, "brand-new.yml.bak").exists()
        )
    }

    /**
     * 成功后**不留下** `.tmp`。
     *
     * 这是"原子写"最容易被忽略的一半：写了 tmp 但忘记它成功时应该消失，
     * 实例目录就会每编辑一次多一个残留文件。
     */
    @Test
    fun temporaryFileIsGoneAfterSuccess() {
        val target = File(dir, "x.yml")
        writeFileAtomically(target, "content")

        val leftovers = dir.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue("★ 成功后不该留下 .tmp；实际：${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    /**
     * 空内容可以正常写入（用户确实想清空）—— 语义上不允许"拒绝写空"。
     *
     * 这里只测写入层：**要不要二次确认**是 UI 层的事（见下面 [needsEmptyFileConfirmation] 的用例）。
     * 写入层如果擅自拒绝空内容，用户就永远清不掉这个文件。
     */
    @Test
    fun emptyContentIsWritten() {
        val target = File(dir, "clear-me.yml")
        target.writeText("something", Charsets.UTF_8)

        writeFileAtomically(target, "")

        assertTrue("文件仍在（不是被删掉）", target.isFile)
        assertEquals(0, target.length())
        assertEquals(
            "备份里有旧内容",
            "something",
            File(dir, "clear-me.yml.bak").readText(Charsets.UTF_8)
        )
    }

    /**
     * 目录不存在时自动创建（实例刚建、还没写过这个文件）。
     */
    @Test
    fun createsParentDirectories() {
        val nested = File(dir, "a/b/c/file.yml")
        writeFileAtomically(nested, "deep")
        assertEquals("deep", nested.readText(Charsets.UTF_8))
    }

    /**
     * 中文/多行内容必须原样保留 —— 用 UTF-8 写，不能用平台默认编码。
     *
     * 为什么专门测：`File.writeText(text)` 的默认参数是 UTF-8，但如果哪天有人
     * 把它改成 `FileOutputStream` 之类的裸写，Android 上的默认编码不一定还是 UTF-8。
     * provider 的 displayName 里出现中文是常态。
     */
    @Test
    fun utf8ContentRoundTrips() {
        val target = File(dir, "cn.yml")
        val content = "id: mygateway\ndisplayName: 我的网关\nmodels:\n  - id: opus\n    name: 巨无霸\n"
        writeFileAtomically(target, content)

        val readBack = target.readBytes()
        // 自制 JUnit 桩没有 assertArrayEquals，用相等性断言代替（语义一样：
        // 字节序列必须逐位相同）。
        assertEquals(
            "★ 必须是 UTF-8 字节，不能走平台默认编码",
            content.toByteArray(Charsets.UTF_8).toList(),
            readBack.toList()
        )
    }

    // ===== 空内容确认的判据 ================================================

    /**
     * 只有 `launcher.patch.yml` 清空时才需要确认。
     *
     * 理由：`start-dsh.sh` 用 `[ -f "$LAUNCHER_PATCH" ]` 判断要不要传 `--patch`；
     * 文件空着但存在时仍会传，dsh 读到空 overlay 等于没有那一层 ——
     * 于是 `/assets` 缓存头与 HMR 轮询 5000ms 两条优化静默失效。
     * 用户可能确实想这样，但必须知道代价。
     *
     * 变异验证：把函数体改成 `newText.isBlank()`（不看文件名）⇒
     * [otherFilesDoNotPromptOnEmpty] 变红。
     */
    @Test
    fun launcherPatchPromptsWhenEmptied() {
        assertTrue(
            "★ 清空 launcher.patch.yml 要确认",
            needsEmptyFileConfirmation("launcher.patch.yml", "")
        )
        assertTrue(
            "★ 只有空白字符也算清空",
            needsEmptyFileConfirmation("launcher.patch.yml", "  \n\t ")
        )
        assertFalse(
            "有内容就不用问",
            needsEmptyFileConfirmation("launcher.patch.yml", "[]")
        )
    }

    /**
     * 其余文件清空**不弹**确认 —— 多一次无意义的弹框只会让人烦。
     */
    @Test
    fun otherFilesDoNotPromptOnEmpty() {
        assertFalse(
            "credentials 清空没有额外机制受影响",
            needsEmptyFileConfirmation(".credentials.yaml", "")
        )
        assertFalse(
            "profile patch 清空同上",
            needsEmptyFileConfirmation("cordis.patch.yml", "")
        )
    }

    // ===== 失败路径 ========================================================

    /**
     * rename 失败时：抛异常、且 `.tmp` 被清掉。
     *
     * 怎么把 rename 弄失败：把目标做成一**个非空目录**。
     * POSIX 的 rename(2) 在"目标是目录、源是文件"时返回 EISDIR/ENOTDIR，Java 侧
     * `renameTo` 返回 false。这是真实可达的失败（实例目录里若有个同名目录）。
     *
     * 变异验证：把 `runCatching { tmp.delete() }` 删掉 ⇒ 本条的后半段变红。
     */
    @Test
    fun renameFailureThrowsAndCleansUpTemporaryFile() {
        val target = File(dir, "blocked.yml")
        // 目标是个非空目录 ⇒ rename 到它必然失败
        assertTrue(target.mkdirs())
        File(target, "keep.txt").writeText("x")

        var threw = false
        try {
            writeFileAtomically(target, "will-not-land")
        } catch (_: IOException) {
            threw = true
        }

        assertTrue("★ rename 失败必须抛 IOException（不能静默当成功）", threw)
        val tmp = File(dir, "blocked.yml.tmp")
        assertFalse(
            "★ 失败后 .tmp 必须被清掉，否则每试一次就多一个残留",
            tmp.exists()
        )
        // 目标仍然是那个目录，里面的东西没被动
        assertTrue(target.isDirectory)
        assertTrue(File(target, "keep.txt").isFile)
    }
}
