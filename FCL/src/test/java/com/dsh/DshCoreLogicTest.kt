package com.dsh

import com.dsh.core.DshLogBus
import com.dsh.core.DshRegistry
import com.dsh.core.DshVersionListItem
import com.dsh.core.UrlScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dsh 启动器核心逻辑的 JVM 单元测试（不需要真机/Android）。
 *
 * 覆盖的都是"错了会静默出问题"的地方：
 * - registry 解析与版本排序（下载页列表顺序、latest 角标）
 * - 启动输出里的 token URL 解析（决定 WebView 能不能进得去）
 * - 日志脱敏（token 不能泄漏到日志/日志文件）
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.*"
 */
class DshCoreLogicTest {

    // --- 版本排序 / 解析 ---------------------------------------------------

    @Test
    fun semverOrdersReleaseAbovePrerelease() {
        assertTrue(DshRegistry.compareSemver("1.0.0", "1.0.0-rc.1") > 0)
        assertTrue(DshRegistry.compareSemver("1.0.0-rc.2", "1.0.0-rc.1") > 0)
        assertTrue(DshRegistry.compareSemver("0.1.6-alpha.2", "0.1.5-rc.2") > 0)
        assertTrue(DshRegistry.compareSemver("0.2.0", "0.10.0") < 0)
        assertEquals(0, DshRegistry.compareSemver("0.1.5-rc.2", "0.1.5-rc.2"))
    }

    /** 预发布按段比较：rc.10 应大于 rc.9（字典序会算错） */
    @Test
    fun semverComparesPrereleaseNumerically() {
        assertTrue(DshRegistry.compareSemver("1.0.0-rc.10", "1.0.0-rc.9") > 0)
        assertTrue(DshRegistry.compareSemver("1.0.0-alpha.2", "1.0.0-alpha.10") < 0)
    }

    /** build metadata（+xxx）不参与比较 */
    @Test
    fun semverIgnoresBuildMetadata() {
        assertEquals(0, DshRegistry.compareSemver("1.0.0+build.5", "1.0.0+build.9"))
    }

    /**
     * 用真实的 abbreviated metadata 夹具（dsh-registry-sample.json 的结构子集）验证：
     * - 解析出的版本从新到旧
     * - dist-tag 只打在对应的版本上
     * - version 字段缺失时用 map 的 key 兜底（原来会整条丢掉）
     */
    @Test
    fun parsesRegistryAndSortsNewestFirst() {
        val json = """
        {
          "dist-tags": { "latest": "0.1.5-rc.2", "next": "0.1.6-alpha.2" },
          "versions": {
            "0.1.5-rc.1": { "version": "0.1.5-rc.1", "dist": { "tarball": "https://t/0.1.5-rc.1.tgz", "integrity": "sha512-a", "unpackedSize": 1000 } },
            "0.1.5-rc.2": { "version": "0.1.5-rc.2", "dist": { "tarball": "https://t/0.1.5-rc.2.tgz", "integrity": "sha512-b" } },
            "0.1.6-alpha.2": { "version": "0.1.6-alpha.2", "dist": { "tarball": "https://t/0.1.6-alpha.2.tgz" } },
            "0.1.4": { "dist": { "tarball": "https://t/0.1.4.tgz" } }
          }
        }
        """.trimIndent()

        val entries = DshRegistry.parseVersions(json)
        assertEquals(4, entries.size)

        // 从新到旧：0.1.6-alpha.2 是预发布但主体更高，排在 0.1.5-rc.2 之前
        assertEquals(
            listOf("0.1.6-alpha.2", "0.1.5-rc.2", "0.1.5-rc.1", "0.1.4"),
            entries.map { it.version }
        )

        // dist-tag 只打在对应版本
        assertEquals(listOf("next"), entries.first { it.version == "0.1.6-alpha.2" }.tags)
        assertEquals(listOf("latest"), entries.first { it.version == "0.1.5-rc.2" }.tags)
        assertTrue(entries.first { it.version == "0.1.4" }.tags.isEmpty())

        // version 字段缺失时用 key 兜底
        assertEquals("0.1.4", entries.first { it.tarball.endsWith("0.1.4.tgz") }.version)

        // 预发布判定
        assertTrue(entries.first { it.version == "0.1.5-rc.2" }.isPrerelease)
        assertTrue(!entries.first { it.version == "0.1.4" }.isPrerelease)
    }

    /** 一个版本同时命中 latest 和 next 时，两个 tag 都要保留 */
    @Test
    fun keepsAllTagsOfSameVersion() {
        val json = """
        {
          "dist-tags": { "latest": "0.1.5-rc.2", "next": "0.1.5-rc.2" },
          "versions": { "0.1.5-rc.2": { "version": "0.1.5-rc.2", "dist": { "tarball": "https://t/x.tgz" } } }
        }
        """.trimIndent()
        assertEquals(listOf("latest", "next"), DshRegistry.parseVersions(json).single().tags.sorted())
    }

    /** 列表项展示的 tag 应优先 latest（排序后取第一个会显示成 alpha） */
    @Test
    fun versionListItemPrefersLatestTag() {
        val entry = DshRegistry.VersionEntry(
            version = "0.1.5-rc.2",
            tags = listOf("alpha", "latest"),
            tarball = "https://t/x.tgz",
            unpackedSize = 2048,
            integrity = null
        )
        val item = DshVersionListItem.from(entry, emptySet())
        assertEquals("latest", item.tag)
        assertEquals("2 KB", item.sizeText)
    }

    // --- 启动输出解析（决定 WebView 能否进入） -----------------------------

    @Test
    fun extractsAuthenticatedUrlFromDshOutput() {
        val line = "dsh web: http://127.0.0.1:3080/?token=_Njyw6jc9b6IRyPvWOfHu8WHsSjfLlC_lsOetmwH1oQ"
        val url = UrlScanner.findAuthenticatedUrl(line)
        assertEquals("http://127.0.0.1:3080/?token=_Njyw6jc9b6IRyPvWOfHu8WHsSjfLlC_lsOetmwH1oQ", url)
        assertEquals(3080, UrlScanner.portOf(url!!))
        assertEquals("_Njyw6jc9b6IRyPvWOfHu8WHsSjfLlC_lsOetmwH1oQ", UrlScanner.tokenOf(url))
    }

    /** 带 LAN 变体的行（绑 0.0.0.0 时会出现）不能被连成一串 */
    @Test
    fun stopsAtWhitespaceOnLanVariant() {
        val line = "dsh web: http://127.0.0.1:4567/?token=test-token (LAN: http://192.168.1.5:4567/?token=test-token)"
        assertEquals("http://127.0.0.1:4567/?token=test-token", UrlScanner.findAuthenticatedUrl(line))
    }

    /** start-dsh.sh 打印的机器可读就绪标记 */
    @Test
    fun readsMarkerUrlFromStartScript() {
        val line = "[start-dsh] READY url=http://127.0.0.1:40233/?token=et5w4-xSCJpP95w98yyXZOJOxkn-EOz1JAyS8T6eKqU"
        val url = UrlScanner.findMarkerUrl(line)
        assertEquals(
            "http://127.0.0.1:40233/?token=et5w4-xSCJpP95w98yyXZOJOxkn-EOz1JAyS8T6eKqU",
            url
        )
        assertEquals(40233, UrlScanner.portOf(url!!))
    }

    /** PORT=0 时要能从 URL 里回读真实端口 */
    @Test
    fun readsAutoAssignedPort() {
        assertEquals(38885, UrlScanner.portOf("http://127.0.0.1:38885/?token=abc"))
        assertEquals(8080, UrlScanner.portOf("http://127.0.0.1:8080"))
    }

    @Test
    fun ignoresUnrelatedLines() {
        assertNull(UrlScanner.findAuthenticatedUrl("[runtime] 启动 dsh 完成"))
        assertNull(UrlScanner.findMarkerUrl("[start-dsh] READY port=3080"))
        assertNull(UrlScanner.portOf("no url here"))
    }

    // --- 日志脱敏（token 不能进日志/日志文件） -----------------------------

    @Test
    fun sanitizesTokensInLogLines() {
        val raw = "dsh web: http://127.0.0.1:3080/?token=SECRET123 (LAN: http://192.168.1.5:3080/?token=SECRET123)"
        val safe = DshLogBus.sanitize(raw)
        assertTrue("token 未被脱敏: $safe", !safe.contains("SECRET123"))
        assertTrue(safe.contains("token=***"))
        // 其余内容保留，便于排查
        assertTrue(safe.contains("127.0.0.1:3080"))
    }

    @Test
    fun sanitizeKeepsOrdinaryLinesIntact() {
        val line = "npm WARN deprecated foo@1.0.0"
        assertEquals(line, DshLogBus.sanitize(line))
    }

    /** 注册过 API key 后，即使它出现在其它格式的输出里也要被抹掉 */
    @Test
    fun sanitizesRegisteredSecrets() {
        DshLogBus.registerSecret("sk-abcdef1234567890")
        val safe = DshLogBus.sanitize("using key sk-abcdef1234567890 now")
        assertTrue("注册的密钥未被脱敏: $safe", !safe.contains("sk-abcdef1234567890"))
    }

    /** `&token=`（非首个查询参数）也要脱敏——LAN 变体就是这种形态 */
    @Test
    fun sanitizesNonLeadingTokenParam() {
        val safe = DshLogBus.sanitize("http://127.0.0.1:3080/?a=1&token=SECRET9 zz")
        assertTrue("&token= 未被脱敏: $safe", !safe.contains("SECRET9"))
        assertTrue(safe.contains("token=***"))
    }

    /** 大小写混写的 TOKEN= 同样要脱敏（(?i) 分支） */
    @Test
    fun sanitizesTokenCaseInsensitively() {
        val safe = DshLogBus.sanitize("URL http://x/?TOKEN=AbCdEf123")
        assertTrue("TOKEN= 未被脱敏: $safe", !safe.contains("AbCdEf123"))
    }

    // --- 体积文本（设置页/列表页展示） ------------------------------------

    @Test
    fun formatsSizesWithCorrectUnits() {
        assertEquals("512 B", com.dsh.core.DshPaths.formatSize(512))
        assertEquals("1 KB", com.dsh.core.DshPaths.formatSize(1024))
        assertEquals("1.5 MB", com.dsh.core.DshPaths.formatSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.00 GB", com.dsh.core.DshPaths.formatSize(2L * 1024 * 1024 * 1024))
        // 未初始化/未知时不能抛异常，也不能显示成负数
        assertEquals("—", com.dsh.core.DshPaths.formatSize(-1))
    }

    // --- URL 解析的边界（决定 WebView 会不会用错地址） --------------------

    /** 没有 token 的普通 URL 也要能解析出端口（用于 cookie 模式访问） */
    @Test
    fun parsesPortFromUrlWithoutToken() {
        assertEquals(3080, UrlScanner.portOf("http://127.0.0.1:3080/"))
        assertNull(UrlScanner.findAuthenticatedUrl("http://127.0.0.1:3080/"))
    }

    /** token 为空时不应被当成"已认证 URL"（否则会加载一个必然 401 的地址） */
    @Test
    fun ignoresEmptyToken() {
        assertNull(UrlScanner.findAuthenticatedUrl("http://127.0.0.1:3080/?token="))
    }

    // --- 安装单飞闸门（决定"两个 npm 会不会同时写一个 node_modules"） ------

    /**
     * 同一个 key：占坑成功 → 第二次占坑必须失败 → 释放后又能占。
     * 最后一步尤其重要：如果释放逻辑写错（或占坑被登记在任务结束之后），
     * 该实例会**永久**卡在"已有安装任务在进行"，只能杀进程重启 App。
     */
    @Test
    fun singleFlightAcquireReleaseAcquire() {
        val gate = com.dsh.core.SingleFlight()
        assertTrue("首次占坑应当成功", gate.tryAcquire("inst-1"))
        assertTrue("重复占坑必须被拒", !gate.tryAcquire("inst-1"))
        assertEquals(1, gate.runningKeys().size)
        gate.release("inst-1")
        assertTrue("释放后应可再次占坑", gate.tryAcquire("inst-1"))
        assertTrue("不同 key 互不影响（多实例可并行安装）", gate.tryAcquire("inst-2"))
        assertTrue(gate.isRunning("inst-2"))
    }

    /**
     * 并发抢同一个 key：无论多少线程同时进入，**只能有一个**拿到。
     * 这直接对应原来的 check-then-act 缺陷（`containsKey` + `put` 之间可被插入）。
     */
    @Test
    fun singleFlightIsAtomicUnderConcurrency() {
        val gate = com.dsh.core.SingleFlight()
        val threads = 16
        val start = java.util.concurrent.CountDownLatch(1)
        val winners = java.util.concurrent.atomic.AtomicInteger(0)
        val workers = (0 until threads).map {
            Thread {
                start.await()
                if (gate.tryAcquire("inst-shared")) winners.incrementAndGet()
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals("并发占坑只应有一个赢家", 1, winners.get())
    }
}
