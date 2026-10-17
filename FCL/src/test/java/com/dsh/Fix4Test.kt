package com.dsh

import com.dsh.core.Probe
import com.dsh.core.ProcessUtil
import com.dsh.core.UrlScanner
import com.dsh.core.syntheticUrlWouldOverwriteToken
import com.dsh.ui.isOldWebViewSpaCrash
import com.tungsten.fclcore.util.gson.JsonUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R16/fix4 纯逻辑单测（BL-135 / BL-136 / BL-138 / BL-140 的可在 JVM 上钉死的核心）。
 *
 * 能测与不能测（同 DshRuntimeRaceTest 的口径）：
 * - **能测**：三级 URL 解析的覆盖判据（BL-136 纯函数）、pid 文件 url 字段的持久化形态
 *   （BL-135 的存储契约）、崩溃消息判据（BL-140 纯函数）。这些是"错一位就出事"的判定核心。
 * - **测不了**：认领流程整体（需要真 proot 进程 + /proc + 端口）、弹框与阈值接线（UI 行为）、
 *   两段式宽限的时序（需要真进程 + 协程调度）—— 上真机验证。
 */
class Fix4Test {

    // --- BL-136：合成裸 URL 不得覆盖已带 token 的 url（syntheticUrlWouldOverwriteToken）---

    @Test
    fun syntheticBareUrlNeverOverwritesTokenUrl() {
        // fix3 缺陷现场：dsh web:（带token）先到 → READY port=（合成裸）随后到 —— 必须拦
        val tokenUrl = "http://127.0.0.1:46501/?token=abc123"
        val syntheticBare = "http://127.0.0.1:46501/"
        assertTrue(
            "无 token 合成值不得覆盖已带 token 的 url（BL-136 主用例）",
            syntheticUrlWouldOverwriteToken(tokenUrl, syntheticBare)
        )
    }

    @Test
    fun tokenUrlCanAlwaysUpdate() {
        // 新值带 token（换代/恢复）始终允许写 —— 这是正常的三级解析最终态
        val old = "http://127.0.0.1:46501/?token=OLD"
        val new = "http://127.0.0.1:46501/?token=NEW"
        assertFalse("带 token 的新值可以覆盖旧值", syntheticUrlWouldOverwriteToken(old, new))
        // 旧值带 token，新值带不同端口的 token —— 也允许（端口变化场景）
        assertFalse(
            "端口变化 + 新 token 可以覆盖",
            syntheticUrlWouldOverwriteToken(old, "http://127.0.0.1:50000/?token=NEW")
        )
    }

    @Test
    fun bareOverBareAndFirstWriteAllowed() {
        // 旧值本来就是裸的 → 裸 URL 覆盖无损失（认领场景回落、启动初始态等），允许
        assertFalse(
            "旧值裸 → 新值裸 = 无降级损失，允许",
            syntheticUrlWouldOverwriteToken("http://127.0.0.1:46501/", "http://127.0.0.1:46501/")
        )
        // 旧值 null（首写）→ 任何新值（含裸）都允许
        assertFalse("首写（旧值 null）允许任何值", syntheticUrlWouldOverwriteToken(null, "http://127.0.0.1:46501/"))
    }

    @Test
    fun tokenOfParsesBothMarkers() {
        // 纯函数依赖的 UrlScanner.tokenOf 基本行为锚定
        assertEquals("abc", UrlScanner.tokenOf("http://127.0.0.1:46501/?token=abc"))
        assertEquals("X-Y_z", UrlScanner.tokenOf("http://h/p?a=1&token=X-Y_z"))
        assertNull(UrlScanner.tokenOf("http://127.0.0.1:46501/"))
        assertNull(UrlScanner.tokenOf("http://127.0.0.1:46501/?port=1"))
    }

    // --- BL-135：pid 文件 url 字段的存储契约（PidMeta 序列化兼容性）----------------

    @Test
    fun pidMetaCarriesTokenUrlAndDefaultsNull() {
        // 新写法：url 随 pid/port 一起存
        val withUrl = ProcessUtil.PidMeta(1234, 46501, "http://127.0.0.1:46501/?token=T")
        assertEquals("http://127.0.0.1:46501/?token=T", withUrl.url)
        // 旧写法（无 url 参数）默认 null —— 源码兼容（旧调用点不用改），
        // 也代表"旧格式 pid 文件反序列化后的形态"（GSON 对缺字段置 null）
        val withoutUrl = ProcessUtil.PidMeta(1234, 46501)
        assertNull("不带 url 构造时默认 null（旧文件兼容）", withoutUrl.url)
    }

    /**
     * R17-X2（H1 升级迁移窗口的存储契约）：**真 GSON 往返**。
     *
     * H1 链条成立的前提 = "fix2/fix3 写的旧格式 pid 文件（`{pid, port}` 无 url 字段）
     * 被 fix4 读出时 url 必须为 null（而不是抛异常/回退失败）"—— 这条契约此前只在注释里，
     * 本测试用与生产同源的 [JsonUtils.GSON] 实打实钉住三件事：
     * 1. 旧格式 JSON（无 url 键）→ 反序列化 url == null（H1 认定成立）；
     * 2. 新格式（url=null 序列化）→ **JSON 里不出现 "url" 键**（GSON 默认不写 null 字段，
     *    文件形态与旧格式逐字兼容，不会让 fix2/fix3 因为多一个 null 键而出问题）；
     * 3. 带 url 的往返 → url 值原样保留（认领读回 token 的数据保真）。
     */
    @Test
    fun pidMetaGsonRoundTripContract() {
        // 1) 旧格式（fix2/fix3 写出的）→ url 为 null
        val oldJson = """{"pid":1234,"port":46501}"""
        val oldMeta = JsonUtils.fromNonNullJson(oldJson, ProcessUtil.PidMeta::class.java)
        assertEquals(1234L, oldMeta.pid)
        assertEquals(46501, oldMeta.port)
        assertNull("旧格式 JSON 无 url 键 → 读出 null（H1 迁移窗口的认定前提）", oldMeta.url)

        // 2) url=null 序列化 → 不写 "url" 键（与旧格式文件逐字兼容）
        val nullUrlJson = JsonUtils.GSON.toJson(ProcessUtil.PidMeta(1234, 46501, null))
        assertFalse(
            "url=null 不应序列化出 \"url\" 键（GSON 默认省略 null；文件形态与旧版兼容）: $nullUrlJson",
            nullUrlJson.contains("\"url\"")
        )

        // 3) 带 url 往返：值保真（认领读回 token 的数据契约）
        val tokenUrl = "http://127.0.0.1:46501/?token=abc-DEF_123"
        val round = JsonUtils.fromNonNullJson(
            JsonUtils.GSON.toJson(ProcessUtil.PidMeta(1234, 46501, tokenUrl)),
            ProcessUtil.PidMeta::class.java
        )
        assertEquals("带 url 往返值保真", tokenUrl, round.url)
    }

    // --- BL-140：老 WebView 崩溃消息判据（isOldWebViewSpaCrash）-------------------

    @Test
    fun spaCrashMessagesDetected() {
        // bebug (2) 实测的三条
        assertTrue(
            "boot 脚本崩溃（:47）",
            isOldWebViewSpaCrash("Uncaught TypeError: Promise.withResolvers is not a function")
        )
        assertTrue(
            "plugin 装载崩溃",
            isOldWebViewSpaCrash("Uncaught (in promise) TypeError: AbortSignal.any is not a function")
        )
        assertTrue(
            "slot entry 崩溃",
            isOldWebViewSpaCrash("slot entry crashed in 'settings.section': TypeError: Promise.withResolvers is not a function")
        )
    }

    @Test
    fun benignMessagesIgnored() {
        // 普通 JS 报错/日志不该触发"升级 WebView"指引
        assertFalse(isOldWebViewSpaCrash("Uncaught TypeError: Cannot read properties of null"))
        assertFalse(isOldWebViewSpaCrash("connection lost, retry #3"))
        assertFalse(isOldWebViewSpaCrash("Failed to load resource: net::ERR_CONNECTION_REFUSED"))
        assertFalse(isOldWebViewSpaCrash(""))
    }

    /**
     * R17（H1/H2 根治的恢复函数）：从 start-dsh.sh 的实时日志文件恢复 token URL 候选。
     *
     * 纯文件逻辑，JVM 可测。钉住四件事：
     * 1. 基本恢复：日志里的 `dsh web: http://…?token=X` 行被提取；
     * 2. **端口过滤**（跨实例串台防护）：只收端口等于本实例的候选（/tmp 共享，别的实例日志
     *    也在那里——串台会把别的实例 token 写进本实例）；
     * 3. 多文件与排序：新的（mtime 大）在前；
     * 4. 非 token 行/坏文件不炸（静默跳过），空目录返回空。
     */
    @Test
    fun startLogTokenRecoveryRespectsPortAndOrder() {
        val tmp = Files.createTempDirectory("r17-tmp").toFile()
        val inst = Files.createTempDirectory("r17-inst").toFile()
        try {
            // 旧文件：本实例端口 46501 的 token（A）
            val older = File(tmp, "dsh-start-111.log")
            older.writeText("noise\ndsh web: http://127.0.0.1:46501/?token=AAA\nmore noise\n")
            older.setLastModified(1_000_000L)
            // 新文件：本实例端口的 token（B） + 别的实例端口（9999）的 token（应被过滤）
            val newer = File(tmp, "dsh-start-222.log")
            newer.writeText(
                "dsh web: http://127.0.0.1:9999/?token=OTHER_INSTANCE\n" +
                    "dsh web: http://127.0.0.1:46501/?token=BBB (LAN: http://192.168.1.5:46501/?token=BBB)\n"
            )
            newer.setLastModified(2_000_000L)

            val got = ProcessUtil.recoverTokenUrlsFromStartLogs(tmp, inst, 46501)
            // 新的在前；别的实例端口被过滤掉
            assertEquals(
                listOf("http://127.0.0.1:46501/?token=BBB", "http://127.0.0.1:46501/?token=AAA"),
                got
            )
            assertTrue("别的实例端口的 token 不得出现", got.none { it.contains("OTHER_INSTANCE") })

            // 空目录：返回空（不炸）
            val empty = Files.createTempDirectory("r17-empty").toFile()
            assertTrue(ProcessUtil.recoverTokenUrlsFromStartLogs(empty, empty, 46501).isEmpty())
        } finally {
            tmp.deleteRecursively()
            inst.deleteRecursively()
        }
    }

    /** 恢复函数的实例目录 fallback 落点（脚本 /tmp 不可写时写这里）。 */
    @Test
    fun startLogTokenRecoveryReadsInstanceDirFallback() {
        val tmp = Files.createTempDirectory("r17-tmp2").toFile()
        val inst = Files.createTempDirectory("r17-inst2").toFile()
        try {
            File(inst, "dsh-start-333.log")
                .writeText("dsh web: http://127.0.0.1:46502/?token=FALLBACK\n")
            val got = ProcessUtil.recoverTokenUrlsFromStartLogs(tmp, inst, 46502)
            assertEquals(listOf("http://127.0.0.1:46502/?token=FALLBACK"), got)
        } finally {
            tmp.deleteRecursively()
            inst.deleteRecursively()
        }
    }

    /**
     * R17（X4 红队 A5 的测试锚）：三态探活 —— 起本地透传 socket 分别返回 303/401/连接拒绝，
     * 验证 `Probe.probeTokenUrl` 的归类（3xx=live / 4xx=dead / 异常=null）。
     *
     * 为什么必须钉住"超时/异常 ≠ 死"：早期实现把两者同码（都 false），dsh 忙时的慢响应会把活
     * token 误判为死。本测试针对的就是这个判据本身。
     *
     * 实现用裸 `ServerSocket` 手写最小 HTTP 响应（android 单测 classpath 没有
     * com.sun.net.httpserver；ServerSocket 在 android.jar 里，可用）。
     */
    @Test
    fun probeTokenUrlThreeStates() {
        assertEquals(
            "3xx → 状态码原样返回（live 归类由调用方做）",
            303, Probe.probeTokenUrl("http://127.0.0.1:${serveOnce(303)}/?token=X", 2000)
        )
        assertEquals(
            "4xx → 状态码原样返回（dead 归类由调用方做）",
            401, Probe.probeTokenUrl("http://127.0.0.1:${serveOnce(401)}/?token=X", 2000)
        )
        // 连接拒绝 → null（unknown），绝不与 401 同码
        val closedPort = java.net.ServerSocket(0).use { it.localPort } // 立即关闭 → 该端口无监听
        assertNull(
            "端口关闭 → null（unknown，X4-A5 的核心判据）",
            Probe.probeTokenUrl("http://127.0.0.1:$closedPort/?token=X", 1500)
        )
    }

    /** 起一次性 HTTP 桩：接受一个连接、回 [status]（无 body），返回端口。 */
    private fun serveOnce(status: Int): Int {
        val server = java.net.ServerSocket(0)
        val port = server.localPort
        Thread {
            runCatching {
                server.accept().use { s ->
                    // 尽力读走请求头（不等读完；响应先发也无妨，HttpURLConnection 只看状态行）
                    runCatching { s.getInputStream().read(ByteArray(2048)) }
                    val resp = "HTTP/1.1 $status Status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().write(resp.toByteArray())
                    s.getOutputStream().flush()
                }
            }
            runCatching { server.close() }
        }.apply { isDaemon = true }.start()
        return port
    }
}
