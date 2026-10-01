package com.dsh.core

import com.google.gson.annotations.SerializedName
import com.tungsten.fclcore.util.io.HttpRequest
import java.util.concurrent.CompletableFuture

/**
 * npm registry 客户端：拉取 @deepseek-ai/dsh 的可用版本，作为下载 UI 的数据源。
 *
 * 复用 FCL 现成的 [HttpRequest]（HttpURLConnection + Gson + 异步/重试），不引入新依赖。
 * 关键实测点（见 dsh-registry-sample.json 夹具）：
 * - 用 abbreviated metadata（Accept: application/vnd.npm.install-v1+json），
 *   112KB vs full 142KB，字段够用（version/dist/dependencies），省流量。
 * - 包名带 scope，URL 里 `/` 需转义为 `%2F`：`@deepseek-ai%2Fdsh`。
 * - dist-tags 当前：latest / next / alpha；版本总数约 22。
 * - 单版本的 engines 字段在 abbreviated 里【缺失】，Node 版本要求（^22.19||>=24）
 *   不能依赖这里，改由 rootfs 内的 setup 脚本校验（见任务1）。
 */
object DshRegistry {

    const val PACKAGE_NAME = "@deepseek-ai/dsh"
    private const val ENCODED_NAME = "@deepseek-ai%2Fdsh"
    private const val REGISTRY_BASE = "https://registry.npmjs.org"
    private const val ABBREVIATED_ACCEPT = "application/vnd.npm.install-v1+json"

    /** 一个可下载的 dsh 版本条目 */
    data class VersionEntry(
        val version: String,
        /** dist-tags 命中的标签，如 latest/next/alpha；无则为空 */
        val tags: List<String>,
        /** tarball 下载地址 */
        val tarball: String,
        /** 解压后体积（字节），可能为 0（老版本 registry 无此字段） */
        val unpackedSize: Long,
        /** 完整性校验值（sha512-...），下载后校验用 */
        val integrity: String?
    ) {
        val isPrerelease: Boolean get() = version.contains('-')
    }

    /** registry 返回的顶层结构（abbreviated） */
    private data class PackageDoc(
        @SerializedName("dist-tags") val distTags: Map<String, String>?,
        val versions: Map<String, VersionDoc>?
    )

    private data class VersionDoc(
        val version: String?,
        val dist: DistDoc?
    )

    private data class DistDoc(
        val tarball: String?,
        val integrity: String?,
        val unpackedSize: Long?
    )

    /**
     * 异步拉取全部版本，按语义版本从新到旧排序。
     * 结果里每个版本标注命中的 dist-tag（latest/next/alpha）。
     */
    fun fetchVersions(): CompletableFuture<List<VersionEntry>> =
        HttpRequest.GET("$REGISTRY_BASE/$ENCODED_NAME")
            .header("Accept", ABBREVIATED_ACCEPT)
            .retry(3)
            .getStringAsync()
            .thenApply { parseVersions(it) }

    // --- 缓存（本次新增） ---------------------------------------------------
    // 原实现每次进下载页都打一次 registry；断网/切后台回来就只剩一个错误页。
    // 现在内存缓存 10 分钟，并支持断网时回退到上一次成功的结果。

    private data class CacheEntry(val at: Long, val entries: List<VersionEntry>)

    @Volatile
    private var cache: CacheEntry? = null

    const val DEFAULT_CACHE_TTL_MS = 10 * 60 * 1000L

    /** 最近一次成功的列表（可能为空），可用于"离线回退" */
    fun cached(): List<VersionEntry>? = cache?.entries

    fun invalidate() {
        cache = null
    }

    /**
     * 带缓存的拉取：命中且未过期直接返回缓存；否则请求；
     * 请求失败但手里有旧缓存时，抛出的异常会附带旧值（调用方用 [cached] 回退）。
     */
    fun fetchVersionsCached(maxAgeMs: Long = DEFAULT_CACHE_TTL_MS): CompletableFuture<List<VersionEntry>> {
        val c = cache
        if (c != null && System.currentTimeMillis() - c.at <= maxAgeMs) {
            return CompletableFuture.completedFuture(c.entries)
        }
        return fetchVersions().thenApply { entries ->
            cache = CacheEntry(System.currentTimeMillis(), entries)
            entries
        }
    }

    /** 解析 registry JSON 为版本列表（抽出以便离线用夹具测试） */
    fun parseVersions(json: String): List<VersionEntry> {
        val doc = com.tungsten.fclcore.util.gson.JsonUtils.fromNonNullJson(json, PackageDoc::class.java)
        val distTags = doc.distTags ?: emptyMap()
        // version -> 命中的 tag 列表（一个版本可能同时是 latest 和 next）
        val tagsByVersion = HashMap<String, MutableList<String>>()
        distTags.forEach { (tag, ver) ->
            tagsByVersion.getOrPut(ver) { mutableListOf() }.add(tag)
        }
        val versions = doc.versions ?: emptyMap()
        return versions.entries
            .mapNotNull { (key, v) ->
                val dist = v.dist ?: return@mapNotNull null
                val tarball = dist.tarball ?: return@mapNotNull null
                // abbreviated metadata 里 version 字段可能缺失，用 map 的 key 兜底
                val version = v.version?.takeIf { it.isNotBlank() } ?: key
                VersionEntry(
                    version = version,
                    tags = tagsByVersion[version]?.sorted() ?: emptyList(),
                    tarball = tarball,
                    unpackedSize = dist.unpackedSize ?: 0L,
                    integrity = dist.integrity
                )
            }
            .sortedWith { a, b -> compareSemver(b.version, a.version) } // 从新到旧
    }

    /** dist-tags 里 latest 指向的版本（下载 UI 默认高亮项） */
    fun latestTag(json: String): String? =
        com.tungsten.fclcore.util.gson.JsonUtils
            .fromNonNullJson(json, PackageDoc::class.java)
            .distTags?.get("latest")

    /**
     * 语义版本比较（够用即可，支持 major.minor.patch 和 -prerelease 后缀）。
     * 正数表示 a > b。稳定版视为高于同主干的预发布（1.0.0 > 1.0.0-rc.1）。
     */
    fun compareSemver(a: String, b: String): Int {
        val (aCore, aPre) = splitPre(a)
        val (bCore, bPre) = splitPre(b)
        val aNums = aCore.split('.').map { it.toIntOrNull() ?: 0 }
        val bNums = bCore.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aNums.size, bNums.size)) {
            val cmp = (aNums.getOrElse(i) { 0 }).compareTo(bNums.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        // core 相等：无预发布 > 有预发布
        return when {
            aPre == null && bPre == null -> 0
            aPre == null -> 1
            bPre == null -> -1
            else -> comparePreRelease(aPre, bPre)
        }
    }

    private fun splitPre(v: String): Pair<String, String?> {
        // 先剥掉 build metadata（+build.1），它不是版本优先级的一部分
        val noBuild = v.substringBefore('+')
        val idx = noBuild.indexOf('-')
        return if (idx < 0) noBuild to null else noBuild.substring(0, idx) to noBuild.substring(idx + 1)
    }

    /** 预发布标识按点分段逐段比较：数字段按数值，其余按字典序（近似 semver 规则） */
    private fun comparePreRelease(left: String, right: String): Int {
        val aParts = left.split('.')
        val bParts = right.split('.')
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val x = aParts.getOrNull(i) ?: return -1
            val y = bParts.getOrNull(i) ?: return 1
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val cmp = when {
                xn != null && yn != null -> xn.compareTo(yn)
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}
