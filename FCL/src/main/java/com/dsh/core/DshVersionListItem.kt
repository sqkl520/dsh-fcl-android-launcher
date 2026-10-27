package com.dsh.core

/**
 * 版本行在下载页上的状态（fix12 / D-2 判据统一）。
 *
 * 这是**派生结果**，不是持久状态：来源只有两处 —— [DshInstances.instances] 里各实例的
 * `state`，以及本页在途请求集合（[DshDownloadViewModel.installingVersions]）。因此它天然
 * 与实例卡片同源，页面被回收重建后也不会再凭"上次写过什么"显示错的角标。
 */
enum class VersionRowState { INSTALLING, INSTALLED, BROKEN, NONE }

/**
 * 纯函数：版本号 → 行状态（fix12 / D-2 的唯一判据来源）。
 *
 * 优先级**严格**如下（`installing` 最优先，`installed` 优先于 `broken`）：
 * 1. 该版本**正在装** ⇒ [VersionRowState.INSTALLING]。两个来源取并集：
 *    - 本页在途请求 [installingVersions]（`installVersion` 已置位、`installer` 还没写 state 的那一瞬，
 *      只有这里有值 —— E5 §2.2 B7 钉的就是这条）；
 *    - 实例 `state == INSTALLING`（页面被回收重建后，本页的在途集合已经没了，只能从 instances 派生 ——
 *      E6 §2.2 行 B："重建后不再显示已安装"就是 D-2 的核心收益）。
 * 2. 该版本有**任一** `READY` 实例 ⇒ [VersionRowState.INSTALLED]；
 * 3. 没有 READY、但有**任一** `BROKEN` 实例 ⇒ [VersionRowState.BROKEN]；
 * 4. 其余（只有 NOT_INSTALLED 实例，或安装被取消后 `dshVersion` 仍保留）⇒ [VersionRowState.NONE]。
 *
 * 为什么 2 优先于 3（裁决，见 IMPL-PLAN §一.17 与 E6 §2.4）：同版本"一台 READY + 一台 BROKEN"
 * 时，该版本**确实装上了**，损坏的是某一台实例 —— 卡片会继续说"损坏"。若版本行改说"安装失败"，
 * 等于用一台坏实例否定了一台好实例。
 *
 * 表里会出现哪些 key：只统计 `dshVersion != null` 的实例（版本号为空的空壳不属于任何版本行），
 * 外加 [installingVersions] 里的版本（"没有实例、正在装"也是合法输入，见 B7）。
 *
 * 写成顶层函数（不放 companion）：无类初始化负担，JVM 单测可直接调。
 */
fun versionRowStates(
    instances: List<com.dsh.core.DshInstance>,
    installingVersions: Set<String>
): Map<String, VersionRowState> {
    // 先收集每个版本的磁盘事实，再统一按优先级出结论 —— 顺序不可倒，否则"先看到的那个实例"会决定整行。
    val versions = LinkedHashSet<String>()
    val ready = HashSet<String>()
    val broken = HashSet<String>()
    val installing = HashSet<String>()
    instances.forEach { inst ->
        val version = inst.dshVersion ?: return@forEach
        versions += version
        when (inst.state) {
            DshInstance.State.READY -> ready += version
            DshInstance.State.BROKEN -> broken += version
            // 实例自己说"正在装"：页面重建后本页的在途集合已丢，这是唯一能还原"安装中"的事实
            DshInstance.State.INSTALLING -> installing += version
            else -> {}
        }
    }
    // 在途请求可能早于实例落 state（E5 §2.2 B7），也可能对应一个还没有版本号的空壳
    versions += installingVersions
    installing += installingVersions
    return versions.associateWith { version ->
        when {
            version in installing -> VersionRowState.INSTALLING
            version in ready -> VersionRowState.INSTALLED
            version in broken -> VersionRowState.BROKEN
            else -> VersionRowState.NONE
        }
    }
}

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
    val entry: DshRegistry.VersionEntry,
    /**
     * 该版本**装过但没成功**（有 BROKEN 实例、且没有任何 READY 实例）。
     *
     * 与 [installed] 互斥的语义由构造方保证（见 [versionRowStates] 的优先级）：两者不会同时为真。
     * 与 [installing] 同在构造参数的**末段且都带默认值**：既有调用点（`DshCoreLogicTest` 1 处 +
     * `DshDownloadRefreshTest` 的 `sizeTextOf` 1 处）关心的是 tag/sizeText，与安装态无关，
     * 这样它们逐字不动、4 条用例保持全绿（E5 §3.2）。
     */
    val broken: Boolean = false,
    /**
     * 该版本**正在装**（派生自 [versionRowStates]，见那里的优先级）。
     *
     * 为什么需要它（E6 §2.2 行 B 的最后一公里）：页面被回收重建后，本页的
     * `installingVersions` 已经空了，实例 `state == INSTALLING` 才是唯一事实。
     * 没有这个字段时，重建后的行虽然被 [versionRowStates] 判成 INSTALLING、adapter 却画不出来
     * （它只看 `installed`/`broken`），于是掉回"未安装 + 可点按钮"—— 正是 D-2 要根治的漂移。
     *
     * 与 [installed] / [broken] 三者互斥，由 [versionRowStates] 的优先级保证（installing 最高）。
     */
    val installing: Boolean = false
) {
    companion object {
        /** 标签展示优先级：latest 比 alpha/next 更值得展示（原实现取排序后第一个，可能显示成 alpha） */
        private val TAG_PRIORITY = listOf("latest", "next", "alpha", "beta", "rc")

        /**
         * 组装一行。
         *
         * 后四个参数**全部带默认值**：既有调用点（`DshCoreLogicTest:117` 的
         * `from(entry, emptySet())`、`DshDownloadRefreshTest:76` 的 `sizeTextOf`）只关心
         * tag/sizeText，与安装态无关，因此逐字不动也能编译、4 条用例保持全绿（E5 §3.2）。
         */
        fun from(
            entry: DshRegistry.VersionEntry,
            installedVersions: Set<String>,
            preinstalledVersion: String? = null,
            /** 破损版本集合（见 [versionRowStates]）；默认空集，既有调用点无需改签名 */
            brokenVersions: Set<String> = emptySet(),
            /** 正在安装的版本集合（见 [versionRowStates]）；默认空集，既有调用点无需改签名 */
            installingVersions: Set<String> = emptySet()
        ): DshVersionListItem = DshVersionListItem(
            version = entry.version,
            tag = pickTag(entry.tags),
            sizeText = formatSize(entry.unpackedSize),
            isPrerelease = entry.isPrerelease,
            installed = installedVersions.contains(entry.version),
            preinstalled = preinstalledVersion != null && preinstalledVersion == entry.version,
            entry = entry,
            broken = brokenVersions.contains(entry.version),
            installing = installingVersions.contains(entry.version)
        )

        private fun pickTag(tags: List<String>): String? {
            if (tags.isEmpty()) return null
            TAG_PRIORITY.forEach { p -> if (tags.contains(p)) return p }
            return tags.first()
        }

        /** KB 档改一位小数（BL-031③）：整数除法 1500B 会显示成"1 KB"，截断近半。 */
        // ★ BL-034（R15 编排收口）：显式钉 Locale.US —— 默认 locale 下小数点是逗号（1,5 MB），
        //   体积数字会被本地化得不可读/不可复制。与 DshPaths.formatSize 同一修法。
        private fun formatSize(bytes: Long): String = when {
            bytes <= 0 -> "—"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
            else -> "%.1f MB".format(java.util.Locale.US, bytes / 1024.0 / 1024.0)
        }
    }
}
