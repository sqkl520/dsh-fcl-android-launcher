package com.dsh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
                        is DshInstaller.Progress.Failed -> _installingVersions.value = emptySet()
                        else -> {}
                    }
                    rebuild()
                }
        }
        // 实例列表变化（安装完成/删除）→ 重建角标
        scope.launch { DshInstances.instances.collect { rebuild() } }
    }

    /** 拉取版本列表（force=true 时绕过缓存） */
    fun refresh(force: Boolean = false) {
        if (force) DshRegistry.invalidate()
        if (allEntries.isEmpty()) _uiState.value = UiState.Loading
        scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    val future = DshRegistry.fetchVersionsCached()
                    try {
                        // 显式给 get 一个上限。原来的 `withTimeout { future.get() }` 挡不住真正的阻塞：
                        // withTimeout 只取消协程，打断不了已经卡在 get() 里的线程，而 FCL 的 HttpRequest
                        // 本身没设 connect/read 超时，弱网下这个线程会被**永久**占住（线程泄漏，越试越少）。
                        future.get(FETCH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (t: Throwable) {
                        future.cancel(true)
                        throw t
                    }
                }
                allEntries = entries
                lastWarning = null
                rebuild()
            } catch (e: Exception) {
                val stale = DshRegistry.cached()
                if (!stale.isNullOrEmpty()) {
                    // 有旧数据：继续展示，只是提示一下
                    allEntries = stale
                    lastWarning = e.message ?: e.toString()
                    rebuild()
                } else {
                    _uiState.value = UiState.Error(e.message ?: "拉取版本列表失败")
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
        val items = allEntries
            .filter { showPrerelease || !it.isPrerelease }
            .map { DshVersionListItem.from(it, installed) }
        _uiState.value = UiState.Loaded(items, showPrerelease, lastWarning)
    }

    /**
     * 安装某版本：优先复用已有实例/空壳，避免反复点击在列表里积累一堆空实例；否则新建一个。
     * @return 用的实例（可能是已有实例；若该版本已装好则不会重复触发安装）
     */
    fun installVersion(item: DshVersionListItem, instanceName: String? = null): DshInstance {
        val existing = chooseInstanceToInstall(DshInstances.instances.value, item.version)
        // 已 READY 装过该版本：无需重装，直接复用，避免每次点击都重新下载整棵依赖树
        if (existing != null && existing.state == DshInstance.State.READY) {
            DshLogBus.append("[download] ${existing.name} 已安装 dsh ${item.version}，跳过重复安装")
            return existing
        }
        val inst = existing ?: DshInstances.create(instanceName ?: "dsh ${item.version}")
        _installingVersions.value = _installingVersions.value + item.version
        installer.install(inst, item.version)
        return inst
    }

    companion object {
        const val FETCH_TIMEOUT_MS = 20_000L

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
            // 2) 已有一个\"空壳\"（NOT_INSTALLED、从未装成，例如上次安装被取消）→ 复用它，
            //    避免反复点击在实例列表里积累一堆只有目录没有 dsh 的实例
            return instances.firstOrNull {
                it.state == DshInstance.State.NOT_INSTALLED && it.dshVersion == null
            }
        }
    }
}
