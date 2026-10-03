package com.dsh.core

/**
 * 下载 UI 里的一行：一个可安装的 dsh 版本。对应 FCL 的 VersionListItem，但这里的数据
 * 来自 npm registry（[DshRegistry.VersionEntry]）而非本地游戏目录扫描。
 */
data class DshVersionListItem(
    val version: String,
    /** 展示用标签文本，如 "latest" / "alpha"；无标签为 null */
    val tag: String?,
    /** 体积文本，如 "48 KB"（npm tarball 的 unpacked 大小） */
    val sizeText: String,
    val isPrerelease: Boolean,
    /** 是否已作为某个实例安装（UI 显示"已安装"角标用） */
    val installed: Boolean,
    /**
     * 该版本**恰好就是 rootfs 里预装的版本**。
     * 此时点"安装"不会下载任何东西（`setup-node-dsh.sh` 会直接 `DONE source=preinstalled`），
     * 因此界面不该再显示"安装后约 500MB"这种会误导的估算。
     */
    val preinstalled: Boolean,
    val entry: DshRegistry.VersionEntry
) {
    companion object {
        /** 标签展示优先级：latest 比 alpha/next 更值得展示（原实现取排序后第一个，可能显示成 alpha） */
        private val TAG_PRIORITY = listOf("latest", "next", "alpha", "beta", "rc")

        fun from(
            entry: DshRegistry.VersionEntry,
            installedVersions: Set<String>,
            preinstalledVersion: String? = null
        ): DshVersionListItem = DshVersionListItem(
            version = entry.version,
            tag = pickTag(entry.tags),
            sizeText = formatSize(entry.unpackedSize),
            isPrerelease = entry.isPrerelease,
            installed = installedVersions.contains(entry.version),
            preinstalled = preinstalledVersion != null && preinstalledVersion == entry.version,
            entry = entry
        )

        private fun pickTag(tags: List<String>): String? {
            if (tags.isEmpty()) return null
            TAG_PRIORITY.forEach { p -> if (tags.contains(p)) return p }
            return tags.first()
        }

        private fun formatSize(bytes: Long): String = when {
            bytes <= 0 -> "—"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        }
    }
}
