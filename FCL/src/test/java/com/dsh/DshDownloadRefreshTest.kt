package com.dsh

import com.dsh.core.DshDownloadViewModel
import com.dsh.core.DshRegistry
import com.dsh.core.DshVersionListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * R2（下载页刷新体验 + 体积文案）回归：BL-017 / BL-019 / BL-031③。
 *
 * 全是纯逻辑，不碰 Android：
 * - BL-017：UI 超时上限值 + stale-while-revalidate 的回退选择规则
 * - BL-031③：KB 档一位小数（走 from() 的 sizeText 断言，不碰 private 的 formatSize）
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.DshDownloadRefreshTest"
 */
class DshDownloadRefreshTest {

    private fun entry(version: String, bytes: Long = 1024L) = DshRegistry.VersionEntry(
        version = version,
        tags = emptyList(),
        tarball = "https://t/x.tgz",
        unpackedSize = bytes,
        integrity = null
    )

    // --- BL-017：UI 超时上限 -----------------------------------------------

    /** BL-017：UI 只等 10s，超时立刻回退旧列表（不是网络超时本身，见 FETCH_TIMEOUT_MS 注释）。 */
    @Test
    fun uiTimeoutIsTenSeconds() {
        assertEquals(10_000L, DshDownloadViewModel.FETCH_TIMEOUT_MS)
    }

    // --- BL-017：stale-while-revalidate 回退规则 ----------------------------

    /**
     * BL-017 核心：force 刷新会 invalidate registry 缓存，所以失败时
     * "界面手里正展示的旧列表"必须被保留并优先回退，不能只看 registry 缓存。
     */
    @Test
    fun fallbackPrefersCurrentlyDisplayedList() {
        val current = listOf(entry("0.1.5-rc.2"))
        val registryCache = listOf(entry("0.2.0-rc.2"))
        assertSame(current, DshDownloadViewModel.staleFallback(current, registryCache))
    }

    /** force 刷新后 registry 缓存是空的：此时唯一存活的就是界面手里的旧列表。 */
    @Test
    fun fallbackKeepsCurrentListWhenRegistryCacheIsGone() {
        val current = listOf(entry("0.1.5-rc.2"))
        assertSame(current, DshDownloadViewModel.staleFallback(current, null))
        assertSame(current, DshDownloadViewModel.staleFallback(current, emptyList()))
    }

    /** 非 force 的普通加载失败、且手里没数据时：仍可用 registry 残留缓存回退（旧行为保留）。 */
    @Test
    fun fallbackUsesRegistryCacheWhenNothingDisplayed() {
        val registryCache = listOf(entry("0.2.0-rc.2"))
        assertSame(registryCache, DshDownloadViewModel.staleFallback(emptyList(), registryCache))
    }

    /** 两边都空：返回 null，调用方进 Error（"手里完全没数据"是唯一进 Error 的路径）。 */
    @Test
    fun fallbackIsNullWhenBothEmpty() {
        assertNull(DshDownloadViewModel.staleFallback(emptyList(), null))
        assertNull(DshDownloadViewModel.staleFallback(emptyList(), emptyList()))
    }

    // --- BL-031③：formatSize 一位小数 ---------------------------------------

    private fun sizeTextOf(bytes: Long): String =
        DshVersionListItem.from(entry("0.1.5-rc.2", bytes), emptySet()).sizeText

    /** BL-031③：KB 档一位小数——1500B 原来显示"1 KB"（截断近半），现在"1.5 KB"。 */
    @Test
    fun kbShowsOneDecimal() {
        assertEquals("1.5 KB", sizeTextOf(1500))
    }

    /** 2048B 精确 2.0：与 MB 档 "%.1f" 风格对齐（现有 DshPaths.formatSize 也是 "1.5 MB" 式）。 */
    @Test
    fun kbExactValueKeepsDecimalStyle() {
        assertEquals("2.0 KB", sizeTextOf(2048))
    }

    /** 其它档位不受影响：B 档整数、MB 档一位小数、0/负数占位符。 */
    @Test
    fun otherSizeRangesUnchanged() {
        assertEquals("512 B", sizeTextOf(512))
        assertEquals("1.5 MB", sizeTextOf((1.5 * 1024 * 1024).toLong()))
        assertEquals("—", sizeTextOf(0))
        assertEquals("—", sizeTextOf(-1))
    }
}
