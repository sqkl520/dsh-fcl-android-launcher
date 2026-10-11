package com.dsh.core

import com.dsh.fcl.androidlauncher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载页的粘合层：拉 registry 版本列表 → 结合已安装版本 → 输出可渲染的列表项。
 *
 * ## 本次加固（对应审查发现的问题）
 * 1. **不再每次进页面都打网络**：走 [DshRegistry.fetchVersionsCached]（10 分钟内存缓存）。
 * 2. **请求有超时**：原来 `future.get()` 无限等待，弱网下页面永远转圈；现在 [FETCH_TIMEOUT_MS]。
 * 3. **断网可回退**：拉取失败但手里有上次结果时，仍展示旧列表 + 顶部错误提示，而不是只剩空白错误页。
 * 4. **不泄漏 collector**：原来每点一次"安装"就 `scope.launch { progress.collect { ... } }` 且永不结束，
 *    装 N 次就多 N 个收集器（还各自触发一次 rebuild）。现在只在 init 里挂一个。
 * 5. **"已安装"角标实时**：改成订阅 [DshInstances.instances]，安装完成后角标自动更新。
 */
class DshDownloadViewModel(
    private val scope: CoroutineScope,
    private val installer: DshInstaller
) {
    sealed class UiState {
        object Loading : UiState()
        data class Loaded(
            val items: List<DshVersionListItem>,
            val showPrerelease: Boolean,
            /** 非致命错误提示（例如"用的是缓存数据"）；null 表示一切正常 */
            val warning: String? = null
        ) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 正在安装的版本号集合（按钮据此禁用，避免重复创建实例） */
    private val _installingVersions = MutableStateFlow<Set<String>>(emptySet())
    val installingVersions: StateFlow<Set<String>> = _installingVersions.asStateFlow()

    private var allEntries: List<DshRegistry.VersionEntry> = emptyList()
    private var showPrerelease = true // dsh 目前全是预发布版，默认显示，否则列表为空
    private var lastWarning: String? = null

    init {
        // 唯一一个安装进度收集器：安装结束刷新列表
        scope.launch {
            installer.progress
                .drop(1) // 跳过订阅瞬间的当前值，避免刚进页面就触发无意义刷新
                .collect { p ->
                    when (p) {
                        is DshInstaller.Progress.Done -> _installingVersions.value -= p.version
                        // ★ 只移除**本次失败的那个版本**：原来清空整个集合，会让其它仍在跑的
                        // 安装也"解除安装中"，按钮重新可点 → 用户连点 → 并发 npm + 堆实例
                        is DshInstaller.Progress.Failed -> p.version?.let { v -> _installingVersions.value -= v }
                        // ★ 取消也是终态：**必须**和 Done/Failed 一样解除"安装中"标记。
                        //   这条事件是本次新加的（[DshInstaller.cancel] 原来什么都不发）—— 少了它，
                        //   用户取消后按钮永远显示"安装中"、而且再也点不动，只能重启 App 才恢复。
                        is DshInstaller.Progress.Cancelled -> p.version?.let { v -> _installingVersions.value -= v }
                        else -> {}
                    }
                    rebuild()
                }
        }
        // 实例列表变化（安装完成/删除）→ 重建角标
        scope.launch { DshInstances.instances.collect { rebuild() } }
    }

    /**
     * 拉取版本列表（force=true 时绕过缓存）。
     *
     * stale-while-revalidate（BL-017）：force 刷新只清 registry 缓存，不清界面手里的旧列表。
     * - 有旧列表时不发 Loading（列表不消失；转圈由下载页自己的刷新态负责）；
     * - 后台拿到新数据再替换；失败/超时则回退旧列表 + warning（Loaded 态），
     *   只有"手里完全没数据"时才进 Error。
     */
    fun refresh(force: Boolean = false) {
        // 先记下界面手里有没有旧列表，再 invalidate：invalidate 后 DshRegistry.cached()
        // 就没了，回退只能靠 allEntries（BL-017 的根因：原来失败时只看 registry 缓存，
        // force 刷新失败必变空白 Error 页）。
        val hadEntries = allEntries.isNotEmpty()
        if (force) DshRegistry.invalidate()
        if (!hadEntries) _uiState.value = UiState.Loading
        scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    val future = DshRegistry.fetchVersionsCached()
                    try {
                        // UI 侧等待上限（BL-017）：超过 FETCH_TIMEOUT_MS 就不再傻等，
                        // 立刻回退旧列表。注意这只是"界面不等了"，后台线程该跑还跑：
                        // HttpRequest 经 NetworkUtils 建连接，connect/read 各 10s 超时，
                        // 加上 registry 侧 retry(3)，后台一次拉取最长约 60s 才真正结束。
                        future.get(FETCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (t: Throwable) {
                        // 局限说明（BL-031②，本批只注释、不动机制）：future.cancel(true)
                        // 中断不掉正卡在 socket 读里的线程（HttpURLConnection 的读不是
                        // 可中断阻塞），cancel 只是让 future 不再向调用方交付结果，
                        // 后台线程要等自己的 connect/read 超时或重试耗尽才退出。
                        future.cancel(true)
                        throw t
                    }
                }
                allEntries = entries
                lastWarning = null
                rebuild()
            } catch (e: Exception) {
                // 回退优先级：界面手里的旧列表（force 后唯一存活的）> registry 残留缓存。
                val fallback = staleFallback(allEntries, DshRegistry.cached())
                if (fallback != null) {
                    // 有旧数据：继续展示，只是提示一下
                    allEntries = fallback
                    lastWarning = e.message ?: e.toString()
                    rebuild()
                } else {
                    _uiState.value = UiState.Error(
                        e.message ?: DshAppContextHolder.context?.getString(R.string.dsh_download_fetch_failed)
                            ?: "Failed to load the version list"
                    )
                }
            }
        }
    }

    fun togglePrerelease(show: Boolean) {
        showPrerelease = show
        rebuild()
    }

    /** 结合已安装版本重建列表项 */
    private fun rebuild() {
        if (allEntries.isEmpty()) return
        val installed = DshInstances.instances.value.mapNotNull { it.dshVersion }.toSet()
        // rootfs 里预装的版本：命中它时安装不会下载任何东西（脚本直接 DONE source=preinstalled）
        val preinstalled = DshInstaller.readVersionFromPackageJson(
            File(File(DshPaths.ROOTFS_DIR, DshPaths.PREINSTALLED_DSH_REL), "package.json")
        )
        val items = allEntries
            .filter { showPrerelease || !it.isPrerelease }
            .map { DshVersionListItem.from(it, installed, preinstalled) }
        _uiState.value = UiState.Loaded(items, showPrerelease, lastWarning)
    }

    /**
     * 安装派发结果。
     * [started] = false 表示**没有**发起安装（该版本已装好，直接复用），
     * 界面据此选择文案，避免「明明没开始安装却说安装已开始」（R10-16）。
     */
    data class InstallDispatch(val instance: DshInstance?, val started: Boolean)

    /**
     * 安装某版本：优先复用已有实例/空壳，避免反复点击在列表里积累一堆空实例；否则新建一个。
     * @return 派发结果（实例 + 是否真的发起了安装）
     */
    fun installVersion(item: DshVersionListItem, instanceName: String? = null): InstallDispatch {
        // ★ 同一版本已在安装：直接拒绝。
        //   原来不检查，用户连点同一版本会走完整流程 N 次 → 每次都可能新建实例 + 弹一次
        //   「已开始安装」对话框（真机表现为"疯狂跳窗 + 一排损坏实例"）。
        if (_installingVersions.value.contains(item.version)) {
            // ★ 保持全局：判据是**版本号**（`_installingVersions` 是版本级集合），此刻还不知道
            //   这次请求会落到哪个实例上（`chooseInstanceToInstall` 还没跑）。凭"版本已在装"就
            //   把行塞进某个实例，等于猜 —— 猜错就会让 A 的日志里出现"某次无关请求被忽略"。
            DshLogBus.append("[download] ${item.version} 正在安装中，忽略重复请求")
            val running = DshInstances.instances.value.firstOrNull { it.dshVersion == item.version }
            return InstallDispatch(running, started = false)
        }
        val existing = chooseInstanceToInstall(DshInstances.instances.value, item.version)
        // 已 READY 装过该版本：无需重装，直接复用，避免每次点击都重新下载整棵依赖树
        if (existing != null && existing.state == DshInstance.State.READY) {
            // 实例级：这里 `existing` 是明确解析出来的实例 —— "你点安装但跳过了，因为它已经装好了"
            // 是那个实例的状态结论，用户点开它的日志页应该能看到这次点击为什么没产生安装。
            DshLogBus.appendFor(existing.id, "[download] ${existing.name} 已安装 dsh ${item.version}，跳过重复安装")
            return InstallDispatch(existing, started = false)
        }
        val inst = existing ?: DshInstances.create(instanceName ?: "dsh ${item.version}")
        _installingVersions.value = _installingVersions.value + item.version
        // installer.install 返回 false = 该实例已有安装在跑（单飞拒绝），同样不算「本次发起」
        val started = installer.install(inst, item.version)
        return InstallDispatch(inst, started)
    }

    companion object {
        /**
         * UI 侧等待 registry 的上限（BL-017：从 20s 降到 10s，"界面不再傻等"的上限）。
         * 不是网络超时本身：后台线程走自己的 connect/read 超时 + retry(3)，最长约 60s；
         * 这里一到点就回退旧列表 + warning，不再阻塞界面。
         */
        const val FETCH_TIMEOUT_MS = 10_000L

        /**
         * stale-while-revalidate 的回退选择（纯函数，便于单测）。
         * @param current 界面手里当前展示着的列表（force 刷新也不清，始终优先）
         * @param registryCache registry 侧残留的缓存（非 force 失败时可能还有）
         * @return 应回退展示的列表；两边都空返回 null（此时只能进 Error）
         */
        @JvmStatic
        fun staleFallback(
            current: List<DshRegistry.VersionEntry>,
            registryCache: List<DshRegistry.VersionEntry>?
        ): List<DshRegistry.VersionEntry>? = when {
            current.isNotEmpty() -> current
            !registryCache.isNullOrEmpty() -> registryCache
            else -> null
        }

        /**
         * 决定\"装某版本时该用哪个现有实例\"。抽出为纯函数便于单测。
         * @return 应复用的实例；null 表示应新建
         */
        fun chooseInstanceToInstall(
            instances: List<DshInstance>,
            version: String
        ): DshInstance? {
            // 1) 已经 READY 装过这个精确版本 → 复用（避免同一版本装出 N 个实例）
            instances.firstOrNull {
                it.state == DshInstance.State.READY && it.dshVersion == version
            }?.let { return it }
            // 2) 同版本但**没装成功**（BROKEN / INSTALLING / 中断）→ 复用同一个实例**重试**。
            //    ★ 真机实测：安装失败（例如 DNS 不通）后实例会变成 BROKEN，而原来只认 READY 与
            //    空壳，于是"再点一次安装"会**新建实例** → 连点几次就堆出一排"损坏"实例。
            //    现在只要目标版本相同就复用，重试是幂等的。
            instances.firstOrNull { it.dshVersion == version }?.let { return it }
            // 3) 空壳（NOT_INSTALLED、从未装过任何版本，例如上次被取消）→ 复用它
            return instances.firstOrNull {
                it.state == DshInstance.State.NOT_INSTALLED && it.dshVersion == null
            }
        }
    }
}
