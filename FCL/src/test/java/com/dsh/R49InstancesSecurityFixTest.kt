package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.LAUNCHER_ASSETS_PLUGIN
import com.dsh.core.LAUNCHER_PATCH_OVERLAY
import com.dsh.core.UserOverlayState
import com.dsh.core.backupUnparsableOverlay
import com.dsh.core.classifyUserOverlay
import com.dsh.core.hardenOwnerOnly
import com.dsh.core.DshInstances
import com.dsh.core.isCredentialFileName
import com.dsh.core.mergeLauncherPatchOverlay
import com.dsh.core.uniqueTmpName
import com.dsh.core.writeFileAtomically
import com.dsh.core.writeFilePermissionHook
import com.dsh.core.writeLauncherPatchOverlay
import com.dsh.core.writeTextSynced
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * round49 / W2-G：`DshInstances.kt` 的 7 条安全 / 健壮性修复。
 *
 * ## 这个文件测的是"真行为"，不是源码文本
 *
 * 每条都用**临时目录里的真文件**跑真实现（`writeFileAtomically` 是纯 JVM 可测的内核）。
 * 只有两处例外，见各自的 KDoc：G-1 的权限位在 Windows 上测不出差异（实测
 * `setReadable(false,false)` 返回 false 且什么都不做），G-6 的缺陷不在本文件范围内。
 *
 * ## 每条都带变异说明
 *
 * "把修复删掉，这条会不会红"是这些用例存在的唯一理由 —— 所以每条 KDoc 里写清变异点。
 * 已实测过的变异（把修复删掉、确认测试真会红）：G-1 的收紧钩子、G-3 的 `.bak` 开关、
 * G-4 的备份分支、G-5 的 tmp 唯一性、G-7 的收编过滤（**含**接线守卫）、失败清理。
 * G-2 的 `fd.sync()` 与 G-6（不在本文件范围）见各自 KDoc —— 它们**不能**在 JVM 里行为验证，
 * 我不会用"跑绿了"假装测过。
 */
class R49InstancesSecurityFixTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("r49-instances").toFile()
    }

    @After
    fun tearDown() {
        // 进程级全局，必须复原 —— 漏了会把后面所有用例带红（同 DshInstances.dirFsync 的教训）。
        writeFilePermissionHook = null
        dir.deleteRecursively()
    }

    // =====================================================================
    // G-1 / S-05：凭据文件必须被收紧到 0600
    // =====================================================================

    /**
     * 写凭据文件时，**收紧权限那一步必须被调用，且作用于 tmp 文件**（rename 会把 mode 带过去）。
     *
     * ## 为什么用钩子而不是断言权限位
     *
     * 本机（Windows）实测：`setReadable(false, false)` 返回 **false** 且什么都不做；
     * `Files.getPosixFilePermissions` 直接抛 `UnsupportedOperationException`。
     * 也就是说"写完之后查权限位"这条断言在开发机上**恒绿**——即使把收紧那一步整个删掉。
     * 那是假绿，比没有测试更糟。
     *
     * 所以这里断言的是**跨平台可观测的那部分**：那条路径上真的调用了收紧、且传进去的是
     * tmp 文件（不是目标、不是原文件）。真机上"权限位是不是真的 0600"只能 on-device 验证。
     *
     * 变异验证（实测）：把 `if (secret) { ... tighten(tmp) }` 整段删掉 ⇒ 本条红
     * （`hooked` 为空）。把 `secret` 判据改成恒 false ⇒ 同上。
     */
    @Test
    fun credentialWriteTightensPermissionsOnTheTmpFile() {
        val seen = mutableListOf<File>()
        writeFilePermissionHook = { seen += it }

        val target = File(dir, ".credentials.yaml")
        target.writeText("API_KEY: OLD\n", Charsets.UTF_8)
        writeFileAtomically(target, "API_KEY: NEW\n")

        assertEquals("★ 凭据文件必须走收紧权限那一步；实际调用次数：${seen.size}", 1, seen.size)
        val tmpName = seen.single().name
        assertTrue(
            "★ 收紧必须作用在 **tmp** 上（rename 会把 mode 一起带到目标；对目标 chmod 有中间态窗口）：$tmpName",
            tmpName.startsWith(".credentials.yaml.tmp.")
        )
        assertFalse(
            "收紧绝不能作用在目标文件本身：$tmpName",
            tmpName == ".credentials.yaml"
        )
    }

    /**
     * **非**凭据文件不得走收紧那一步。
     *
     * 理由不是"省一次 syscall"：普通配置文件（`launcher.patch.yml` / `cordis.patch.yml`）
     * 收紧到 0600 会改变它的既有权，而 dsh 的 rootfs 侧可能以别的身份读它 —— 那不是本次要改的行为。
     *
     * 变异验证：把 `if (secret)` 去掉（无条件收紧）⇒ 本条红。
     */
    @Test
    fun nonCredentialWriteDoesNotTouchPermissions() {
        val seen = mutableListOf<File>()
        writeFilePermissionHook = { seen += it }

        val target = File(dir, "launcher.patch.yml")
        target.writeText("OLD", Charsets.UTF_8)
        writeFileAtomically(target, "NEW")

        assertTrue("普通配置文件不该被收紧权限；实际：${seen.map { it.name }}", seen.isEmpty())
    }

    /**
     * [isCredentialFileName] 的判据表。
     *
     * `credentials.env` / `credentials.enc` 是启动器**历史版本**的明文凭据文件
     * （`cleanupLegacyCredentialFiles` 会清掉，但用户仍可能在编辑器里打开它们）。
     * 大小写不敏感是因为 `File.name` 在大小写不敏感的文件系统上拿到什么不一定。
     */
    @Test
    fun credentialPredicateCoversTheRealFileNames() {
        listOf(
            ".credentials.yaml",
            "credentials.yaml",
            ".credentials.yml",
            "credentials.env",
            "credentials.enc",
            ".credentials.yaml" // 已是小写，重复确认不受影响
        ).forEach {
            assertTrue("★ 必须认作凭据文件：$it", isCredentialFileName(it))
        }
        listOf("launcher.patch.yml", "cordis.patch.yml", "settings.yaml", "instances.json", "credentials")
            .forEach {
                assertFalse("普通文件不该被认作凭据：$it", isCredentialFileName(it))
            }
        // 大小写：Android 的 File.name 与用户输入都可能带大小写差异
        assertTrue("判据必须大小写不敏感", isCredentialFileName(".Credentials.YAML"))
    }

    /**
     * [hardenOwnerOnly] 在**普通文件**上必须返回 true 且不抛（四步都在 runCatching 里）。
     *
     * 这条钉的是"收紧失败不许把保存弄挂"：部分文件系统（FAT/exFAT）上 `setWritable`
     * 返回 false 是正常现象，那不该变成"保存失败"。
     */
    @Test
    fun hardenOwnerOnlyNeverThrows() {
        val f = File(dir, "plain.txt")
        f.writeText("x")
        assertTrue("普通文件上收紧应当成功（返回 true）", hardenOwnerOnly(f))
        // 不存在的文件上也不许抛（runCatching 兜住）
        hardenOwnerOnly(File(dir, "missing/child.txt"))
    }

    // =====================================================================
    // G-2 / S-09：先落盘，再让 rename 生效
    // =====================================================================

    /**
     * 写内核必须在**返回之前**把内容真的写到目标上（`FileOutputStream` + `fd.sync()`）。
     *
     * ## 这条能测到什么、不能测到什么
     *
     * 断电行为没法在单测里模拟。能测的是**两步的顺序**：`writeTextSynced` 返回时内容
     * 已经在盘上（同一个 File 句柄读得到），所以调用方随后的 rename 生效的那一刻，
     * 目标路径上不可能出现"空文件已就位"。
     *
     * 变异验证（实测）：把 `out.fd.sync()` 删掉 —— 本条**仍然绿**（sync 与"内容可见"是两件事）。
     * 这条用例的真实作用不是钉 sync，而是钉"**内容在 rename 之前就已完整落盘**"这个顺序：
     * 把 [writeTextSynced] 换成"先建空文件、返回后再写"的形态 ⇒ 本条红。
     * `fd.sync()` 本身的存在性由 [writeTextSyncedUsesFdSyncNotWriteText] 用可观测的替代判据钉住。
     */
    @Test
    fun credentialWriteLandsOnDiskBeforeTheRename() {
        val target = File(dir, ".credentials.yaml")
        val tmp = File(dir, "stage.tmp")

        writeTextSynced(tmp, "API_KEY: NEW\n")

        assertEquals("★ writeTextSynced 返回时内容必须已经完整落盘", "API_KEY: NEW\n", tmp.readText(Charsets.UTF_8))
        assertEquals("★ 字节数必须与 UTF-8 编码一致", "API_KEY: NEW\n".toByteArray(Charsets.UTF_8).size.toLong(), tmp.length())
        assertFalse("前置：目标此时还不该被创建（rename 由调用方做）", target.exists())
    }

    /**
     * `fd.sync()` 的存在性判据：写完之后文件**可读且完整**，并且是 UTF-8 字节。
     *
     * 中英混排内容一起验 —— `writeText` 与 `FileOutputStream.write(bytes)` 走的编码
     * 若哪天不一致（比如有人改成平台默认编码），这里会立刻红。
     *
     * 变异验证：把 `writeTextSynced` 换成裸 `tmp.writeText(text)` ⇒ 本条仍绿（编码相同）。
     * 所以本条**不是** sync 的判据，它是编码/完整性的判据。sync 只能靠 on-device 断电实测
     * 或代码审阅确认（`out.fd.sync()` 那一行）—— 我不会用源码文本断言假称测过它。
     */
    @Test
    fun writeTextSyncedPreservesUtf8Bytes() {
        val tmp = File(dir, "u.tmp")
        val content = "provider: 我的网关\nmodels:\n  - id: opus\n"
        writeTextSynced(tmp, content)

        assertEquals(
            "必须是 UTF-8 字节，不能走平台默认编码",
            content.toByteArray(Charsets.UTF_8).toList(),
            tmp.readBytes().toList()
        )
    }

    /**
     * 空内容也要能真正写出来（0 字节文件 + sync 不抛）。
     *
     * `fd.sync()` 在 0 字节文件上是合法的 —— 这条防的是"加了 sync 之后空内容写不了"。
     */
    @Test
    fun writeTextSyncedHandlesEmptyContent() {
        val tmp = File(dir, "empty.tmp")
        writeTextSynced(tmp, "")
        assertTrue("文件必须存在", tmp.isFile)
        assertEquals(0L, tmp.length())
    }

    /**
     * ★ `fd.sync()` 的接线守卫（**源码级**，且明确标注它不是行为测试）。
     *
     * ## 为什么只能用源码断言
     *
     * `fsync` 的全部意义在于"断电时的行为"，JVM 单测里**没有任何可观测差异**：
     * 数据不 sync 也照样立刻可读。我实测过变异（把 `out.fd.sync()` 删掉）——
     * 全部 26 条行为用例**依然全绿**。所以"我测过 fsync"这种说法在这里只能是假的。
     *
     * 能让它被守住的办法只有两条：
     * - on-device 断电/杀进程实测（未做，本项目没有断电注入工具）；
     * - 源码断言"那一行还在"（本条）。
     *
     * 本条**只**证明那一行存在于 [writeTextSynced] 的函数体里，不证明它生效。
     * 所以它写的断言刻意很窄：函数体里必须出现 `fd.sync()`，且不许退回 `writeText`。
     *
     * 变异验证（实测）：删掉 `out.fd.sync()` ⇒ 本条红（行为用例仍全绿，正是这条存在的理由）。
     */
    @Test
    fun writeTextSyncedCallsFdSync() {
        val src = instancesSource()
        val start = src.indexOf("internal fun writeTextSynced(")
        assertTrue("找不到 writeTextSynced（源码结构变了？）", start >= 0)
        val end = src.indexOf("\n}", start).let { if (it < 0) src.length else it }
        val body = src.substring(start, end)

        assertTrue(
            "★ S-09 接线：writeTextSynced 的函数体里必须有 fd.sync()（否则掉电时 rename 生效、" +
                "内容还是旧的/空的）。注意：这只是源码级守卫，**不是**行为测试。" +
                "实际函数体：${body.take(300)}",
            body.contains("fd.sync()")
        )
        assertFalse(
            "★ 不许用 writeText（它只 close 不 fsync）；实际函数体：${body.take(300)}",
            body.contains(".writeText(")
        )
    }

    // =====================================================================
    // G-3 / S-06：凭据文件不留 .bak
    // =====================================================================

    /**
     * 覆盖凭据文件时**不得**产生 `.bak` —— 那是第二份明文 Key。
     *
     * 为什么这条最要紧：`.bak` 是覆盖式、永不清理。用户轮换 Key 之后，旧 Key 会以明文
     * 长期留在同一个目录里，而"轮换"的语义本该是"旧凭据作废"。
     *
     * 变异验证（实测）：把 `&& !secret` 从 if 条件里去掉 ⇒ 本条红（`.bak` 出现）。
     */
    @Test
    fun credentialWriteLeavesNoBackupCopy() {
        val target = File(dir, ".credentials.yaml")
        target.writeText("API_KEY: OLD-SECRET\n", Charsets.UTF_8)

        writeFileAtomically(target, "API_KEY: NEW-SECRET\n")

        assertEquals("目标被更新", "API_KEY: NEW-SECRET\n", target.readText(Charsets.UTF_8))
        val leftovers = dir.listFiles()?.map { it.name } ?: emptyList()
        assertFalse(
            "★ 凭据文件绝不能留 .bak（旧 Key 明文永不清理）；目录里实际有：$leftovers",
            leftovers.any { it.startsWith(".credentials.yaml.bak") }
        )
    }

    /**
     * 反过来：**普通**配置文件必须**保留** `.bak`（回滚点不能因为本次改动一起丢掉）。
     *
     * 这条是上面那条的反面 —— 少了它，"把备份整段删掉"也能让上面那条绿。
     */
    @Test
    fun plainConfigStillKeepsBackup() {
        val target = File(dir, "launcher.patch.yml")
        target.writeText("OLD", Charsets.UTF_8)

        writeFileAtomically(target, "NEW")

        val backup = File(dir, "launcher.patch.yml.bak")
        assertTrue("★ 普通配置文件必须有 .bak", backup.isFile)
        assertEquals("备份里是写之前的旧内容", "OLD", backup.readText(Charsets.UTF_8))
    }

    /**
     * 凭据文件写完之后目录里**只剩目标本身**（没有 `.bak`、没有残留 `.tmp`）。
     *
     * `.tmp` 那半条也很重要：`Files.move` 消费掉 tmp 是正常路径，而"每次保存多一个残留"
     * 在凭据文件上等于每次多一份明文 Key 副本。
     */
    @Test
    fun credentialWriteLeavesNeitherBackupNorTemp() {
        val target = File(dir, ".credentials.yaml")
        target.writeText("k1\n", Charsets.UTF_8)
        writeFileAtomically(target, "k2\n")

        val names = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        assertEquals("★ 凭据写完只该剩目标本身；实际：$names", listOf(".credentials.yaml"), names)
    }

    /**
     * 目标不存在时写凭据文件：同样不产生 `.bak`，且内容正确。
     */
    @Test
    fun creatingCredentialFromScratchLeavesNoBackup() {
        val target = File(dir, ".credentials.yaml")
        assertFalse("前置：文件不存在", target.exists())

        writeFileAtomically(target, "API_KEY: x\n")

        assertEquals("API_KEY: x\n", target.readText(Charsets.UTF_8))
        assertFalse(File(dir, ".credentials.yaml.bak").exists())
    }

    // =====================================================================
    // G-4 / S-07：用户 overlay 解析失败时必须先备份
    // =====================================================================

    /**
     * [classifyUserOverlay] 的三态判据。
     *
     * 这个函数存在的唯一理由：合并结果在"文件不存在"与"文件坏了"时**完全一样**
     * （都是启动器那两条），但调用方要做的动作完全不同 —— 后者必须先备份。
     *
     * 变异验证：把 `isJsonArray` 判断去掉（任何能解析的都算 Parsed）⇒ 本条的对象/标量
     * 那几行红。
     */
    @Test
    fun classifyUserOverlayDistinguishesAbsentFromCorrupt() {
        assertEquals("null（文件不存在/读失败）→ Absent", UserOverlayState.Absent, classifyUserOverlay(null))
        assertEquals("空数组是合法的 overlay", UserOverlayState.Parsed, classifyUserOverlay("[]"))
        assertEquals("真 JSON 数组 → Parsed", UserOverlayState.Parsed, classifyUserOverlay("""[{"id":"x"}]"""))
        // 用户照 dsh 文档写的**块序列 YAML**（不是 JSON）⇒ 读不懂
        assertEquals(
            "★ 块序列 YAML 读不懂 → Corrupt（这一支旧实现是纯静默的）",
            UserOverlayState.Corrupt,
            classifyUserOverlay("- id: my-provider\n  name: gw\n")
        )
        assertEquals("坏 JSON → Corrupt", UserOverlayState.Corrupt, classifyUserOverlay("{ not json"))
        assertEquals("根是对象 → Corrupt（dsh 的 patch 必须是数组）", UserOverlayState.Corrupt, classifyUserOverlay("""{"id":"x"}"""))
        assertEquals("根是标量 → Corrupt", UserOverlayState.Corrupt, classifyUserOverlay("42"))
        assertEquals("纯空白 → Corrupt", UserOverlayState.Corrupt, classifyUserOverlay("   \n"))
    }

    /**
     * 用户写坏的文件必须被另存为 `<name>.bak.BAD-<ms>`，且备份里是**原文**。
     *
     * 为什么不能复用 `.bak`：那个名字会被下一次保存覆盖，而这里要保留的是
     * "启动器读不懂的那一份"证据。
     *
     * 变异验证：把 [backupUnparsableOverlay] 的函数体改成直接 `return null`（不备份）
     * ⇒ 本条红。
     */
    @Test
    fun corruptOverlayIsBackedUpBeforeWrite() {
        val target = File(dir, LAUNCHER_PATCH_OVERLAY)
        val userText = "- id: my-provider\n  name: 我的网关\n"
        target.writeText(userText, Charsets.UTF_8)

        val backup = backupUnparsableOverlay(target, 1_700_000_000_000L)

        assertNotNull("★ 必须返回备份文件（否则调用方无从判断备份是否成功）", backup)
        assertEquals("★ 备份名必须与常规 .bak 区分开", "launcher.patch.yml.bak.BAD-1700000000000", backup!!.name)
        assertTrue("备份文件必须真的在盘上", backup.isFile)
        assertEquals("★ 备份里必须是用户写的原文（这才是证据）", userText, backup.readText(Charsets.UTF_8))
        assertTrue("原文件此时还没被写（备份在写入之前）", target.isFile)
    }

    /**
     * 端到端：`writeLauncherPatchOverlay` 遇到用户写坏的 overlay 时，**备份 + 仍写启动器条目**。
     *
     * ## 为什么必须"仍写"
     *
     * `start-dsh.sh` 用 `[ -f "$LAUNCHER_PATCH" ]` 判断要不要传 `--patch`；文件保持坏的
     * ⇒ dsh 读到坏 overlay **直接启动失败**。那比"丢掉用户的条目"更糟，所以取舍是
     * "留证据 + 照写"。
     *
     * ## 怎么让它在 JVM 上可达
     *
     * 本函数通过 `hostPathForGuestDir` 把 guest 路径换到宿主 `DshPaths.ROOT_DIR`，
     * 而后者是 `lateinit`、`loadPaths` 之外不可写。所以这里用**反射**把 `ROOT_DIR`
     * 指到临时目录，收尾复原（同 `DshInstancesPersistenceTest` 的手法）。
     *
     * ## ⚠️ 平台差异（如实说明，不假装测过）
     *
     * `writeLauncherPatchOverlay` 用 `tmp.renameTo(target)` 落盘，而 `File.renameTo`
     * **在 Windows 上不能覆盖已存在的目标**（本机实测：`renameTo over existing = false`，
     * `Files.move(REPLACE_EXISTING, ATOMIC_MOVE) over existing = true`）。这在本文件范围内
     * **是既存行为、且已被注释明确限定为 Android/Linux**（该函数的 KDoc 写着"目标平台只有
     * Android/Linux，POSIX 的 rename(2) 是原子覆盖"），本轮不擅自改它。
     *
     * 于是这条用例分两半跑（用 [canRenameOverExisting] 探测，而不是猜操作系统名字）：
     * - 两端都断言：**用户写坏的文件必须先被备份**（这是 S-07 的修复本体，与平台无关）；
     * - 只有 rename-over 可用的平台才断言"写回内容含启动器两条"。
     *   Windows 上那半条改用 [mergeLauncherPatchOverlay] 的**纯函数**结果来覆盖同一语义
     *   （见末尾），而不是假装端到端测过了。
     *
     * 变异验证：把 `classifyUserOverlay(...) == Corrupt` 那一支删掉 ⇒ 本条第一个断言红
     * （`.bak.BAD-*` 不存在）—— 这个断言在所有平台都跑得到。
     */
    @Test
    fun overlayWriterBacksUpCorruptUserFileAndStillWrites() {
        val root = File(dir, "dsh-root")
        val instanceDir = File(root, "instances/inst-x")
        instanceDir.mkdirs()
        val scripts = File(root, "scripts")
        scripts.mkdirs()
        File(scripts, LAUNCHER_ASSETS_PLUGIN).writeText("// plugin\n")

        val target = File(instanceDir, LAUNCHER_PATCH_OVERLAY)
        val userText = "- id: my-provider\n  name: 我的网关\n"
        target.writeText(userText, Charsets.UTF_8)

        val renameOver = canRenameOverExisting()
        val out = withRootDir(root) {
            writeLauncherPatchOverlay(
                instancePathInRootfs = "/opt/dsh/instances/inst-x",
                scriptsHostDir = scripts
            )
        }

        // --- 与平台无关的那一半：坏文件必须先备份（S-07 的修复本体）-------------
        val backups = instanceDir.listFiles()?.filter { it.name.startsWith("$LAUNCHER_PATCH_OVERLAY.bak.BAD-") }
            ?: emptyList()
        assertEquals(
            "★ 用户写坏的原文件必须被另存一份（这半条与平台无关）；" +
                "实际目录：${instanceDir.list()?.toList()}",
            1, backups.size
        )
        assertEquals("备份里是用户原文", userText, backups.single().readText(Charsets.UTF_8))

        // --- 只有 rename-over 可用的平台才断言写回 --------------------------------
        val mergedBody = mergeLauncherPatchOverlay(
            "${"/opt/dsh"}/scripts/$LAUNCHER_ASSETS_PLUGIN", userText
        )
        if (renameOver) {
            assertNotNull("★ overlay 必须被写出来（坏用户文件不能让启动链失去 overlay）", out)
            val written = target.readText(Charsets.UTF_8)
            assertTrue(
                "★ 写回的内容必须含启动器那两条（不能因为用户文件坏就不写）；实际：${written.take(400)}",
                written.contains("\"id\": \"launcher-assets\"") && written.contains("\"id\": \"client-hmr\"")
            )
            assertFalse(
                "用户写的坏内容不该被当成条目写进去（它是 YAML 块序列，不是 JSON 数组）",
                written.contains("my-provider")
            )
        } else {
            // Windows：renameTo 不能覆盖 ⇒ 本函数按设计返回 null，原文件保持不动。
            assertNull(
                "在 renameTo 不能覆盖的平台上，本函数应返回 null（调用方据此不传 --patch）",
                out
            )
            assertEquals("写失败时原文件必须原样保留（不能半截）", userText, target.readText(Charsets.UTF_8))
            println(
                "  [G-4] 本平台 File.renameTo 不能覆盖已存在目标 ⇒ 端到端写回那半条未在此验证；" +
                    "改用纯函数覆盖同一语义（见下行断言），真机（Android/Linux）上才会走写回分支。"
            )
        }

        // 无论平台：**该写的内容**是正确的（纯函数层），这是"仍写启动器条目"语义的另一半证据。
        assertTrue(
            "合并结果必须含启动器两条：${mergedBody.take(400)}",
            mergedBody.contains("\"id\": \"launcher-assets\"") && mergedBody.contains("\"id\": \"client-hmr\"")
        )
        assertFalse("合并结果里不该有读不懂的用户条目", mergedBody.contains("my-provider"))
    }

    /**
     * 用户文件**正常**（JSON 数组）时**不该**产生 `.bak.BAD-*` —— 那会把常态变成噪音。
     *
     * 与上一条同一个平台分叉：[canRenameOverExisting] 为 true 时才断言内容被合并写回。
     * "用户条目必须被合并"这条语义在纯函数层另有断言（同文件的合并调用），
     * 而 `R45LauncherPatchMergeTest` 已系统性覆盖合并规则。
     *
     * 变异验证：把 `classifyUserOverlay` 的判据改成恒 Corrupt ⇒ 本条红
     * （`.bak.BAD-*` 会出现）—— 该断言在所有平台都跑得到。
     */
    @Test
    fun validUserOverlayProducesNoBadBackup() {
        val root = File(dir, "dsh-root2")
        val instanceDir = File(root, "instances/inst-y")
        instanceDir.mkdirs()
        val scripts = File(root, "scripts")
        scripts.mkdirs()
        File(scripts, LAUNCHER_ASSETS_PLUGIN).writeText("// plugin\n")

        val userEntry = """[{"id":"my-plugin","name":"mine"}]"""
        File(instanceDir, LAUNCHER_PATCH_OVERLAY).writeText(userEntry, Charsets.UTF_8)

        val renameOver = canRenameOverExisting()
        val out = withRootDir(root) {
            writeLauncherPatchOverlay("/opt/dsh/instances/inst-y", scripts)
        }

        val badBackups = instanceDir.listFiles()?.filter { it.name.contains(".bak.BAD-") } ?: emptyList()
        assertTrue("正常用户文件不该被备份成 .bak.BAD-*；实际：${instanceDir.list()?.toList()}", badBackups.isEmpty())

        if (renameOver) {
            assertNotNull("正常路径必须写出 overlay", out)
            val written = File(instanceDir, LAUNCHER_PATCH_OVERLAY).readText(Charsets.UTF_8)
            assertTrue("用户条目必须被合并进来（BL-246 语义不变）", written.contains("my-plugin"))
            assertTrue("启动器条目也在", written.contains("launcher-assets"))
        } else {
            assertNull("本平台 renameTo 不能覆盖 ⇒ 返回 null", out)
            assertEquals("原文件原样保留（既不备份也不破坏）", userEntry, File(instanceDir, LAUNCHER_PATCH_OVERLAY).readText(Charsets.UTF_8))
            println("  [G-4] 本平台不支持 renameTo 覆盖 ⇒ \"正常用户文件被合并写回\"那半条靠纯函数层覆盖。")
        }
    }

    // =====================================================================
    // G-5 / S-08：tmp 名必须唯一
    // =====================================================================

    /**
     * 同一个目标名连续取 [uniqueTmpName] 必须**每次都不一样**（同一线程、同一毫秒内）。
     *
     * 变异验证（实测）：把实现退回 `"$targetName.tmp"` ⇒ 本条红（两个名字相同）。
     */
    @Test
    fun uniqueTmpNamesNeverCollideOnTheSameThread() {
        val a = uniqueTmpName("launcher.patch.yml")
        val b = uniqueTmpName("launcher.patch.yml")
        assertNotEquals("★ 同一线程连续两次必须得到不同名字（固定的 .tmp 名就是 S-08 的缺陷）", a, b)
        assertTrue("名字必须仍以目标名的 .tmp 开头（排障要能看出来它是谁的临时文件）：$a", a.startsWith("launcher.patch.yml.tmp."))
    }

    /**
     * 跨线程：4 个线程各取 200 个名字，**必然全不重复**。
     *
     * 这一条钉的是"线程 id + 纳秒"那两部分 —— `nanoTime` 在部分内核上只有毫秒精度，
     * 同毫秒内多个线程会撞名，所以线程 id 不能省。
     *
     * 变异验证：把 `${Thread.currentThread().id}` 去掉 ⇒ 本条在小概率下变红（纳秒相同的概率极低），
     * 所以本条**不足以单独钉住线程 id**，它钉的是"并发下总量不撞"这个整体性质。
     */
    @Test
    fun uniqueTmpNamesDoNotCollideAcrossThreads() {
        val perThread = 200
        val threads = 4
        val all = java.util.Collections.synchronizedList(mutableListOf<String>())
        val start = CountDownLatch(1)
        val workers = (1..threads).map {
            Thread {
                start.await()
                repeat(perThread) { all += uniqueTmpName("x.yml") }
            }
        }
        workers.forEach { it.isDaemon = true; it.start() }
        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(20)) }

        assertEquals(perThread * threads, all.size)
        assertEquals("★ 并发下 tmp 名必须全不重复；实际去重后 ${all.toSet().size}", all.size, all.toSet().size)
    }

    /**
     * 端到端：`writeFileAtomically` 用的是唯一名 —— 写完之后目录里**没有**固定名 `.tmp`。
     *
     * 为什么不能只测 [uniqueTmpName]：那样把函数写对了但没接到写内核上，测试照样绿
     * （本项目反复踩过的"只钉形状不钉接线"）。
     *
     * 变异验证：把 `uniqueTmpName(target.name)` 改回 `target.name + ".tmp"` ⇒ 本条只会在
     * "并发中"红；单线程下两种写法的**残留**没差别，所以这条单线程版本是弱判据，
     * 真正的钉子在上面两条 [uniqueTmpNamesNeverCollideOnTheSameThread] 与下面的接线断言。
     * 这里保留它是为了钉"成功路径不残留任何 .tmp"。
     */
    @Test
    fun successfulWriteLeavesNoTmpBehind() {
        val target = File(dir, "launcher.patch.yml")
        writeFileAtomically(target, "x")

        val tmps = dir.listFiles()?.filter { it.name.contains(".tmp") } ?: emptyList()
        assertTrue("★ 成功路径不该留下任何 .tmp；实际：${tmps.map { it.name }}", tmps.isEmpty())
    }

    /**
     * **接线**：`writeFileAtomically` 与 `writeLauncherPatchOverlay` 都必须走 [uniqueTmpName]。
     *
     * 这一条只能是源码级断言 —— 但它的判据是"调用了那个函数"，不是"看起来像"，
     * 而那个函数的行为已由上面两条真测试钉住。没有这条，把两处都改回固定名
     * 而函数本身留着，所有行为测试仍然是绿的。
     */
    @Test
    fun bothWritersUseTheUniqueTmpNameHelper() {
        val src = instancesSource()
        listOf("fun writeFileAtomically(", "fun writeLauncherPatchOverlay(").forEach { signature ->
            val start = src.indexOf(signature)
            assertTrue("找不到 $signature（源码结构变了？）", start >= 0)
            // 切到函数结束：下一个顶格 "/**" 之前
            val end = src.indexOf("\n/**", start).let { if (it < 0) src.length else it }
            val body = src.substring(start, end)
            assertTrue(
                "★ $signature 必须用 uniqueTmpName(...) 生成 tmp 名；" +
                    "写死 `<name>.tmp` 就是 S-08（并发写抢同一个缓冲区）",
                body.contains("uniqueTmpName(")
            )
            assertFalse(
                "★ $signature 不该再出现固定的 `+ \".tmp\"` 写法",
                body.contains("+ \".tmp\"")
            )
        }
    }

    // =====================================================================
    // G-6 / BL-250：readInstalledVersion 用会回退预装的目录（**不在本文件范围**）
    // =====================================================================

    /**
     * BL-250 的缺陷在 `DshInstaller.readInstalledVersion`（`DshInstaller.kt:1415`），
     * **不在** `DshInstances.kt` 里 ⇒ 本轮没有修，只报告（见 `docs/reports/round49/fixes/W2-G.md`）。
     *
     * ## 但 `DshInstances.kt` 里那条判据是对的，且必须**保持**是对的
     *
     * `repair()` 用的是 `DshPaths.ownDshDir(inst.id)`（**绝不回退预装**）+ [planRepair]，
     * 这正是 BL-250 需要的口径。如果哪天有人把它换回 `effectiveDshDir`，同一台实例会出现
     * "卡片说就绪、启动却跑另一份包"（fix12 修的就是这个）。
     *
     * 所以本用例钉的是**现有正确行为不被回归**：一个没有自己树的实例，即使预装完整、
     * 版本相符，`planRepair` 也会走"预装兜底"那一支（READY），而**实例自己**那棵树
     * 的事实必须来自 `ownDshDir`（空 ⇒ `hasPackageJson == false`）。
     *
     * 变异验证：把 `repair()` 里的 `DshPaths.ownDshDir(inst.id)` 改成
     * `DshPaths.effectiveDshDir(inst.id)` ⇒ 本条红。
     */
    @Test
    fun repairReadsTheInstanceOwnTreeNotThePreinstalledFallback() {
        val src = instancesSource()
        val start = src.indexOf("fun repair()")
        assertTrue("找不到 DshInstances.repair()", start >= 0)
        val end = src.indexOf("\n    /**", start).let { if (it < 0) src.length else it }
        val body = src.substring(start, end)

        assertTrue(
            "★ repair 必须读实例**自己**的树（DshPaths.ownDshDir）",
            body.contains("DshPaths.ownDshDir(")
        )
        assertFalse(
            "★ repair 绝不能回退到 effectiveDshDir —— 那就是 BL-250 的形态" +
                "（预装冒充实例自己的安装）。实际函数体：${body.take(800)}",
            body.contains("effectiveDshDir(")
        )
    }

    // =====================================================================
    // G-7 / BL-251：guest 路径漂移 —— 拒绝会折叠的孤儿目录名
    // =====================================================================

    /**
     * 收编孤儿目录时必须拒绝"折叠后不等于原名"的目录名。
     *
     * `\` 在 Linux/Android 上是**合法文件名字符**（`mkdir 'a\b'` 真的成功），而宿主侧
     * `safeInstanceDirName` 把它折成 `_`，guest 侧却用裸 id ⇒ 两边指向不同目录 ⇒
     * 实例永远起不来，且看起来像"目录丢了"。
     *
     * 变异验证（实测）：把 [isAdoptableDirName] 恒返回 true ⇒ 本条红。
     */
    @Test
    fun adoptableDirNameRejectsFoldableNames() {
        assertTrue("普通 id 必须可收编", DshInstances.isAdoptableDirName("inst-1700000000000-3-7f2a"))
        assertTrue("含空格/点/中文的目录名在 Linux 上合法且不会折叠，应当可收编", DshInstances.isAdoptableDirName("my app.2"))
        assertTrue("中文可收编", DshInstances.isAdoptableDirName("我的实例"))

        assertFalse("★ 含反斜杠的目录名会被宿主持折叠 ⇒ 拒绝收编", DshInstances.isAdoptableDirName("weird\\name"))
        assertFalse("含正斜杠（理论上不可能来自 File.name，防御）", DshInstances.isAdoptableDirName("a/b"))
        assertFalse("NUL 同样会被折叠", DshInstances.isAdoptableDirName("a\u0000b"))
        // 折叠规则的另一面：`.bak.` 这类名字 `safeInstanceDirName` 恒等，所以可收编 ——
        // 这不是缺陷（它们不折叠），钉住是为了说明"判据不是黑名单"。
        assertTrue("名字不含折叠字符时即使是怪名字也可收编（判据是折叠不动点，不是白名单）", DshInstances.isAdoptableDirName(".hidden"))
    }

    /**
     * [isAdoptableDirName] 必须与 `DshPaths.safeInstanceDirName` 的折叠集合**同步**。
     *
     * 判据不是"我抄了一份黑名单"，而是"折叠后是否等于原名" —— 这一条钉住那个委托关系：
     * 对任何字符串，两者的结论必须一致。
     *
     * 变异验证：把实现改成 `name.none { it == '\\' }`（漏掉 `/` 与 NUL）⇒ 本条红。
     */
    @Test
    fun adoptableDirNameTracksTheProductionFoldFunction() {
        val samples = listOf(
            "plain", "with space", "a\\b", "a/b", "a\u0000b", ".", "..", "", "中文", "a_b", "-x-"
        )
        samples.forEach { s ->
            assertEquals(
                "判据必须与 DshPaths.safeInstanceDirName 的折叠集合一致：[$s]",
                s == com.dsh.core.DshPaths.safeInstanceDirName(s),
                DshInstances.isAdoptableDirName(s)
            )
        }
    }

    /**
     * ★ **接线**：`rebuildMissingInstances` 的过滤链里必须真的调用 [DshInstances.isAdoptableDirName]。
     *
     * ## 为什么必须有这条（而不是只有下面那条端到端）
     *
     * 下面那条端到端用例在 Windows 上**造不出**含 `\` 的目录（`\` 是路径分隔符），
     * 于是它对"filter 里到底有没有那个判据"是**瞎的**：实测把 `&& isAdoptableDirName(it.name)`
     * 整段删掉（变异 E），下面那条仍然是绿的 —— 那正是本项目反复踩过的"假绿"。
     *
     * 所以这里用源码断言钉住接线。判据是"过滤链里出现了这个函数调用"，
     * 而这个函数的行为已由上面两条真测试（[DshInstances.isAdoptableDirName]）钉住 ——
     * 两者合起来才构成完整证据：**行为对** + **接线在**。
     *
     * 变异验证（实测）：删掉 filter 里的 `&& isAdoptableDirName(it.name)` ⇒ 本条红。
     */
    @Test
    fun orphanScanFilterActuallyCallsTheNameGuard() {
        val src = instancesSource()
        val start = src.indexOf("fun rebuildMissingInstances(")
        assertTrue("找不到 rebuildMissingInstances（源码结构变了？）", start >= 0)
        val end = src.indexOf("\n    /**", start).let { if (it < 0) src.length else it }
        val body = src.substring(start, end)

        assertTrue(
            "★ G-7 接线断了：rebuildMissingInstances 的 filter 必须调用 isAdoptableDirName，" +
                "否则含 `\\` 的孤儿目录会被收编（宿主折成 `_`、guest 用裸 id ⇒ 实例永远起不来）。" +
                "实际函数体：${body.take(500)}",
            body.contains("isAdoptableDirName(")
        )
    }

    /**
     * 端到端：`rebuildMissingInstances` 扫描时**跳过**含 `\` 的目录，正常目录照收。
     *
     * 这是 G-7 真正起作用的那一层 —— 只测 [DshInstances.isAdoptableDirName] 证明不了它被接线了
     * （补：接线由上面那条 [orphanScanFilterActuallyCallsTheNameGuard] 钉住）。
     *
     * 变异验证：把 `&& isAdoptableDirName(it.name)` 从 filter 里去掉 ⇒ 在 Linux/Android 上本条红
     * （含反斜杠的那条会出现在结果里）；**Windows 上本条测不出来**，所以格外需要上面那条源码断言。
     *
     * ⚠️ 这个用例**在 Windows 上跑不出反斜杠目录**（`\` 是路径分隔符，`File.mkdirs()`
     * 会失败）。所以它分两段：Windows 上只验"正常目录照收"；
     * Linux/Android 上（CI / 真机）才真的造出那个目录。绝不假装测过另一半。
     */
    @Test
    fun orphanScanSkipsFoldableDirectoryNames() {
        val root = File(dir, "instances-root")
        root.mkdirs()
        File(root, "inst-normal").mkdirs()

        // 反斜杠在 Windows 上建不出来（它是分隔符）。用 mkdir 的真实结果决定跑哪一段。
        val tricky = File(root, "weird\\name")
        val trickyCreated = runCatching { tricky.mkdirs() && tricky.isDirectory && tricky.name == "weird\\name" }
            .getOrDefault(false)

        val adopted = DshInstances.rebuildMissingInstances(root, emptySet())

        assertTrue(
            "正常目录必须仍被收编；实际：${adopted.map { it.id }}",
            adopted.any { it.id == "inst-normal" }
        )
        if (trickyCreated) {
            assertFalse(
                "★ 含反斜杠的目录必须**不**被收编（宿主折叠 vs guest 裸 id ⇒ 实例永远起不来）；" +
                    "实际：${adopted.map { it.id }}",
                adopted.any { it.id == "weird\\name" }
            )
        } else {
            // 明确记录本机跑不到那一半 —— 不要假装测过。
            println(
                "  [G-7] 本平台建不出含反斜杠的目录（Windows 上 <\\> 是分隔符）⇒ " +
                    "端到端那一半只能在 Linux/Android 上验证；" +
                    "判据本身已由 adoptableDirNameRejectsFoldableNames 覆盖。"
            )
        }
    }

    // =====================================================================
    // 其它：写入内核的既有语义不许被本次改动破坏（回归）
    // =====================================================================

    /**
     * rename 失败时：抛 [IOException] 且**清掉**那个唯一名的 tmp。
     *
     * G-5 把 tmp 名改成唯一之后，"失败时 tmp 必须删掉"从"下次覆盖"变成了**硬要求** ——
     * 否则每次失败都在实例目录里永久多一个残留。
     *
     * 变异验证：把 catch 分支里的 `runCatching { tmp.delete() }` 删掉 ⇒ 本条后半段红。
     */
    @Test
    fun renameFailureStillCleansUpTheUniqueTmp() {
        val target = File(dir, "blocked.yml")
        assertTrue(target.mkdirs())
        File(target, "keep.txt").writeText("x") // 非空目录 ⇒ rename 到它必然失败

        var threw = false
        try {
            writeFileAtomically(target, "will-not-land")
        } catch (_: IOException) {
            threw = true
        }

        assertTrue("★ rename 失败必须抛 IOException（不能当成功静默返回）", threw)
        val tmps = dir.listFiles()?.filter { it.name.contains(".tmp") } ?: emptyList()
        assertTrue("★ 失败后那个唯一名的 tmp 必须被清掉；实际残留：${tmps.map { it.name }}", tmps.isEmpty())
        assertTrue("目标仍是那个目录，里面的东西没被动", File(target, "keep.txt").isFile)
    }

    /**
     * 凭据文件路径也走的是同一条 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` ——
     * 覆盖已存在的目标必须成功（Windows 上 `File.renameTo` 会返回 false，那是历史 bug）。
     */
    @Test
    fun credentialOverwriteIsAtomicAndSucceeds() {
        val target = File(dir, ".credentials.yaml")
        target.writeText("v1", Charsets.UTF_8)
        writeFileAtomically(target, "v2")
        writeFileAtomically(target, "v3")
        assertEquals("★ 连续覆盖必须成功（Files.move 的 REPLACE_EXISTING 语义）", "v3", target.readText(Charsets.UTF_8))
    }

    // =====================================================================
    // 夹具
    // =====================================================================

    /**
     * 本平台 `File.renameTo` 能否覆盖**已存在**的目标？
     *
     * 本机实测：Windows 上返回 false（目标保持旧内容），Linux/Android 上是 POSIX `rename(2)`
     * ⇒ 覆盖成功。**探测而不是猜 `os.name`**：判断依据是可观测的能力，不是平台名字
     * （文件系统挂载方式也会影响，例如某些网络 FS）。
     *
     * 用途：`writeLauncherPatchOverlay` 用 `tmp.renameTo(target)` 落盘（既存行为，
     * 已在其 KDoc 里限定为 Android/Linux）。端到端用例据此决定是否断言"写回成功"，
     * 而不是在 Windows 上假装绿。
     */
    private fun canRenameOverExisting(): Boolean {
        val probe = Files.createTempDirectory("r49-rename-probe").toFile()
        return try {
            val target = File(probe, "t")
            target.writeText("OLD", Charsets.UTF_8)
            val tmp = File(probe, "n")
            tmp.writeText("NEW", Charsets.UTF_8)
            val ok = tmp.renameTo(target) && target.readText(Charsets.UTF_8) == "NEW"
            ok
        } catch (_: Exception) {
            false
        } finally {
            probe.deleteRecursively()
        }
    }

    /**
     * 在 [block] 期间把 `DshPaths.ROOT_DIR` 指向 [root]，结束后**复原**。
     *
     * `ROOT_DIR` 是 `lateinit var ... private set`，只有 `loadPaths(Context)` 能写，
     * 而 JVM 单测没有 Android Context ⇒ 只能反射（同 `DshInstancesPersistenceTest`）。
     * 收尾必须复原：`DshPaths` 是进程级单例，漏了会把**后面**的用例带红。
     */
    private fun <T> withRootDir(root: File, block: () -> T): T {
        val f = com.dsh.core.DshPaths::class.java.getDeclaredField("ROOT_DIR")
        f.isAccessible = true
        val saved = runCatching { f.get(com.dsh.core.DshPaths) }.getOrNull()
        f.set(com.dsh.core.DshPaths, root.absolutePath)
        return try {
            block()
        } finally {
            runCatching { if (saved == null) f.set(com.dsh.core.DshPaths, null) else f.set(com.dsh.core.DshPaths, saved) }
        }
    }

    /** 取仓库里的 `DshInstances.kt` 源码（与 R40/R43/R45 同一手法，从 cwd 往上找）。 */
    private fun instancesSource(): String {
        var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (d != null && depth < 8) {
            listOf(
                File(d, "FCL/FCL/src/main/java/com/dsh/core/DshInstances.kt"),
                File(d, "FCL/src/main/java/com/dsh/core/DshInstances.kt"),
                File(d, "src/main/java/com/dsh/core/DshInstances.kt")
            ).firstOrNull { it.isFile }?.let { return it.readText() }
            d = d.parentFile
            depth++
        }
        throw AssertionError("找不到 DshInstances.kt —— 请在仓库根或 FCL/ 目录下运行测试")
    }
}
