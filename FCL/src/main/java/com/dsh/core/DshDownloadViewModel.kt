package com.dsh.core

import com.dsh.fcl.androidlauncher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 刷新完成的独立信号（BL-116）：与数据状态 [DshDownloadViewModel.UiState] **解耦** ——
 * 界面"结束转圈"只认它，不再依赖 StateFlow 是否发射（成功但数据未变时 Loaded 结构相等
 * 会被 StateFlow 抑制，那正是转圈永置的根因）。
 *
 * [seq] 每次请求递增、每条 tick 互不相同 → 即使 ok/warning 完全一致也不会被"结构相等"吞掉。
 * [done] 当前恒为 true（UI 只处理 done=true；字段留给将来可能的前置信号）。
 */
data class RefreshTick(
    val seq: Long,
    val done: Boolean,
    val ok: Boolean,
    val warning: String? = null
)

/**
 * 刷新请求的纪元守卫（BL-052/BL-116 的纯逻辑部分，public 便于单测）。
 *
 * 每次 [DshDownloadViewModel.refresh] 入口取一个递增序号（[next]）：
 * - **数据写入**：只有最新序号的响应可以写（[isCurrent]）——慢的旧响应到此为止；
 * - **完成信号**：每个未被顶掉的请求在终局都**无条件**产出一条 done tick（[doneTick]），
 *   不比较任何数据值；被顶掉的旧纪元返回 null（旧信号不许结束界面转圈）。
 */
class RefreshSeqGuard {
    private var latestSeq = 0L

    /** 当前最新纪元的序号（0 = 还没发起过任何刷新） */
    val latest: Long get() = latestSeq

    /** 取下一个纪元号：每次 refresh() 入口调用一次。线程约定：与 VM 相同，只在主线程访问。 */
    fun next(): Long = ++latestSeq

    /** 该纪元的响应是否仍是最新的（false = 已被顶掉，不许写数据、不许发完成信号） */
    fun isCurrent(seq: Long): Boolean = seq > 0L && seq == latestSeq

    /**
     * 把「一个纪元的终局结果」裁定为一条完成信号。
     * - 最新纪元：**无条件**返回 done tick（不依赖值是否变化——BL-116）；
     * - 已被更新的纪元：返回 null。
     */
    fun doneTick(seq: Long, ok: Boolean, warning: String? = null): RefreshTick? =
        if (isCurrent(seq)) RefreshTick(seq, done = true, ok = ok, warning = warning) else null
}

/**
 * 给定当前"安装中"集合与一条进度事件，返回新的集合。
 *
 * 这是 [DshDownloadViewModel.onProgress] 的**状态迁移内核**（纯函数，JVM 直测）。
 * 与 `versionRowStates` 同理：抽出来是因为"哪些事件会清标记"是本模块最容易被改错的地方
 * （E6-9 的 `Failed(version=null)` 就曾让按钮永久卡在"安装中"）。
 *
 * 规则（**与 R26 之前的 `init` 内 `when` 逐条一致**）：
 * - [DshInstaller.Progress.Done] ⇒ 移除该版本；
 * - [DshInstaller.Progress.Failed] / [DshInstaller.Progress.Cancelled] ⇒ `version != null` 时移除该版本，
 *   **null 时什么都不清**（同步安装两个版本时，A 的 `null` 不能顺带把 B 的标记清掉）；
 * - 其它（[DshInstaller.Progress.Stage] / [DshInstaller.Progress.Log] / 未来新增的事件）⇒ 原样返回。
 *
 * ★ 这里**不**碰 `rebuild()`：重建发生在 [DshDownloadViewModel.onProgress]，且在事件分支**之外**
 *   —— `Log` / `Stage` 事件同样会触发重建（这是 R26 之前就有的行为，不是本函数能改的）。
 */
fun reconcileInstalling(current: Set<String>, event: DshInstaller.Progress): Set<String> = when (event) {
    is DshInstaller.Progress.Done -> current - event.version
    // ★ 只移除**本次失败的那个版本**：原来清空整个集合，会让其它仍在跑的
    //   安装也"解除安装中"，按钮重新可点 → 用户连点 → 并发 npm + 堆实例
    is DshInstaller.Progress.Failed -> event.version?.let { v -> current - v } ?: current
    // ★ 取消也是终态：**必须**和 Done/Failed 一样解除"安装中"标记。
    //   这条事件是本次新加的（[DshInstaller.cancel] 原来什么都不发）—— 少了它，
    //   用户取消后按钮永远显示"安装中"、而且再也点不动，只能重启 App 才恢复。
    is DshInstaller.Progress.Cancelled -> event.version?.let { v -> current - v } ?: current
    // ★ 含 Log/Stage：这些事件不清任何标记，但**仍然**要让调用方走 rebuild()（行为与 R26 前一致）
    else -> current
}

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
 *
 * ## fix2（BL-116）新增：刷新状态机与数据状态解耦
 * 6. **转圈结束不再依赖数据发射**：每个请求终局**无条件**发一条 [RefreshTick]（seq 递增，
 *    不会被 StateFlow 的结构相等抑制）——这是"刷新成功但数据未变 → 转圈永置"的根治；
 *    数据写入另配 [RefreshSeqGuard] 纪元守卫（BL-052：旧响应不许覆盖新数据）。
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

    /**
     * 刷新完成信号流（BL-116）：UI 的"转圈结束"只认它。
     * 不是 StateFlow —— tick 带单调递增的 seq，天然不会被相等抑制；replay=0：迟到订阅不
     * 倒灌旧 tick（本页的 UI 在发起任何请求**之前**订阅，不会漏）。
     */
    private val _refreshTicks = MutableSharedFlow<RefreshTick>(replay = 0, extraBufferCapacity = 8)
    val refreshTicks: SharedFlow<RefreshTick> = _refreshTicks.asSharedFlow()

    /** 请求纪元守卫（BL-052 + BL-116）：见类注释 [RefreshSeqGuard] */
    private val refreshGuard = RefreshSeqGuard()

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
                .collect { p -> onProgress(p) }
        }
        // 实例列表变化（安装完成/删除）→ 重建角标
        scope.launch { DshInstances.instances.collect { rebuild() } }
    }

    /**
     * 处理一条安装进度事件（**从 init 的 collect 体抽出，供 JVM 单测直接调用**）。
     *
     * ★ R26 / E6 §2.3：抽出来的理由是"接线本身要可测"。只把 `when` 抽成纯函数的话，
     *   生产路径忘了调用它、或调用时传错了集合，单测仍然全绿（438 条里零条碰 `_installingVersions`）。
     *   现在测试可以直接 `vm.onProgress(Progress.Done(v))` 并断言 `installingVersions.value`。
     *
     * 本函数**不启动任何协程、不读 dispatcher**：构造 VM 时 `scope` 传一个不跑的假作用域即可，
     * 所以它能在没有 `Dispatchers.Main` 的 JVM 单测里直接调用（E6 陷阱 3）。
     *
     * ★ 参数是可空的：`installer.progress` 的类型是 `StateFlow<Progress?>`（初始值 `null`）。
     *   `drop(1)` 丢掉的是**订阅瞬间**那个初始 `null`，但类型上后续仍可能是 `null`；原码的
     *   `else -> {}` 对 `null` 同样只走 `rebuild()`。这里保持**逐字等价**：null 不清集合、仍重建。
     *
     * @param p 一条进度事件；状态迁移交给纯函数 [reconcileInstalling]。
     */
    internal fun onProgress(p: DshInstaller.Progress?) {
        if (p != null) {
            _installingVersions.value = reconcileInstalling(_installingVersions.value, p)
        }
        // ★ rebuild() 仍在 when 之外（原行为）：Log 事件也会触发重建（性能问题，登记为遗留，不在本轮改）
        rebuild()
    }

    /**
     * 拉取版本列表（force=true 时绕过缓存）。
     *
     * stale-while-revalidate（BL-017）：force 刷新只清 registry 缓存，不清界面手里的旧列表。
     * - 有旧列表时不发 Loading（列表不消失；转圈由下载页自己的刷新态负责）；
     * - 后台拿到新数据再替换；失败/超时则回退旧列表 + warning（Loaded 态），
     *   只有"手里完全没数据"时才进 Error。
     *
     * ## fix2 状态机（BL-116/BL-052/BL-119）
     * - 入口取纪元号 [RefreshSeqGuard.next]，老响应（`!isCurrent`）一律**不写数据、不发信号**；
     * - 数据路径照旧写 [_uiState]（可能被 StateFlow 相等抑制，无妨——它只是数据）；
     * - 终局（成功/失败/超时）**无条件**回 [_refreshTicks] 发 done tick——这才是 UI 结束转圈的
     *   唯一依据；成功但数据未变也必发（这是 BL-116 的根治点）。
     */
    fun refresh(force: Boolean = false) {
        val mySeq = refreshGuard.next()
        // 先记下界面手里有没有旧列表，再 invalidate：invalidate 后 DshRegistry.cached()
        // 就没了，回退只能靠 allEntries（BL-017 的根因：原来失败时只看 registry 缓存，
        // force 刷新失败必变空白 Error 页）。
        val hadEntries = allEntries.isNotEmpty()
        if (force) DshRegistry.invalidate()
        if (!hadEntries) _uiState.value = UiState.Loading
        scope.launch {
            var tickSent = false
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
                // 纪元守卫（BL-052）：慢的旧响应到此为止，不许覆盖更新的数据/信号
                if (!refreshGuard.isCurrent(mySeq)) {
                    DshLogBus.append("[download] dropping stale fetch result (seq=$mySeq)")
                    return@launch
                }
                allEntries = entries
                lastWarning = null
                if (entries.isEmpty()) {
                    // 成功但 registry 给了空列表（BL-116 家族第三路径 + BL-117）：
                    // rebuild() 对空 allEntries 早退（"没数据别动现有状态"的守卫，保留），
                    // 若这里也早退，uiState 会永远停在 Loading、列表区空白无提示。
                    // 显式落一个空 Loaded：装载完成、零条目——空态文案由 UI 按 emptyHintRes 显示。
                    _uiState.value = UiState.Loaded(emptyList(), showPrerelease, null)
                } else {
                    rebuild()
                }
                emitDoneTick(mySeq, ok = true)
                tickSent = true
            } catch (e: Exception) {
                // 纪元守卫（BL-052）：失败回退同样不许把旧代快照当"新数据"写回
                if (!refreshGuard.isCurrent(mySeq)) {
                    DshLogBus.append("[download] dropping stale fetch failure (seq=$mySeq)")
                    return@launch
                }
                // 回退优先级：界面手里的旧列表（force 后唯一存活的）> registry 残留缓存。
                val fallback = staleFallback(allEntries, DshRegistry.cached())
                if (fallback != null) {
                    // 有旧数据：继续展示，只提示一下。BL-092：warning 是本地化短句，
                    // 异常细节截断进日志（不再把 TimeoutException 类名抬到界面上）。
                    allEntries = fallback
                    lastWarning = refreshedWarning()
                    DshLogBus.append("[download] refresh failed (seq=$mySeq): ${describeFailure(e)}")
                    rebuild()
                    emitDoneTick(mySeq, ok = false, warning = lastWarning)
                } else {
                    // BL-154（照 BL-092 对 warning 分支的同款口径）：Error 态只上**本地化短句**。
                    // 原来直接把 e.message 抬上屏，用户看到的是「加载失败：timeout」
                    // 「加载失败：Connection refused」这类英文原文。异常细节只进日志（下一行）。
                    _uiState.value = UiState.Error(
                        DshUiText.getString(R.string.dsh_download_fetch_failed, "Failed to load the version list")
                    )
                    // Error 路径原来没有日志行（fallback 路径的日志在上面 :211 处），补一行：
                    // 异常详情（describeFailure 截断后的 e.message）唯一的上屏替代去处就是这里。
                    DshLogBus.append("[download] refresh failed, no fallback (seq=$mySeq): ${describeFailure(e)}")
                    emitDoneTick(mySeq, ok = false)
                }
                tickSent = true
            } finally {
                // 无条件兜底（BL-116 不变量：当前纪元的请求终局必须结束界面转圈）：
                // 上面两条路径都已发过 tick 时 no-op；若 rebuild()/日志等意外抛出导致没发成，
                // 这里补一条。被顶掉的旧纪元经守卫为 null，不会误发。
                if (!tickSent) emitDoneTick(mySeq, ok = false)
            }
        }
    }

    /** 终局发射：把 (seq, 结果) 经纪元守卫裁定后写入 tick 流（守卫拒收被顶掉的旧纪元） */
    private fun emitDoneTick(seq: Long, ok: Boolean, warning: String? = null) {
        refreshGuard.doneTick(seq, ok, warning)?.let { _refreshTicks.tryEmit(it) }
    }

    /** BL-092：warning 的短句（用户可见文案走资源；拼装照 [DshUiText]，不缓存 Context） */
    private fun refreshedWarning(): String =
        DshUiText.getString(R.string.dsh_download_warning_stale, "Refresh failed; showing cached data")

    /** 异常 → 一行日志文本（BL-092：细节截断进日志，不上屏） */
    private fun describeFailure(e: Exception): String {
        val text = e.message ?: e.toString()
        return if (text.length > 200) text.take(200) + "…" else text
    }

    fun togglePrerelease(show: Boolean) {
        showPrerelease = show
        rebuild()
        // 目录为空时 rebuild 早退（守卫"没数据别动状态"，保留）；但如果界面已装载为 Loaded(空)，
        // 仍要把开关态同步进去 —— 空态文案（BL-117）按开关区分，否则此时拨开关毫无反馈。
        if (allEntries.isEmpty()) {
            val current = _uiState.value
            if (current is UiState.Loaded && current.showPrerelease != show) {
                _uiState.value = current.copy(showPrerelease = show)
            }
        }
    }

    /** 结合已安装版本重建列表项 */
    private fun rebuild() {
        if (allEntries.isEmpty()) return
        // ★ fix12（D-2 判据统一）：三态**只从 [versionRowStates] 派生**，不再各自算一套。
        //   原来这里是把每个实例的 `dshVersion` 直接收成集合 —— 只要实例写过版本号就算"已安装"，
        //   于是 BROKEN（装失败）和 NOT_INSTALLED（取消后 dshVersion 仍保留）都会显示"已安装"，
        //   与实例卡片说反话。现在 installed/broken/installing 出自同一份真相，页面重建后也不漂。
        val states = versionRowStates(DshInstances.instances.value, _installingVersions.value)
        val installed = states.filterValues { it == VersionRowState.INSTALLED }.keys
        val broken = states.filterValues { it == VersionRowState.BROKEN }.keys
        // ★ C-2：安装中也要进 item —— adapter 侧的集合只覆盖"本页在途"，页面重建后它是空的，
        //   而实例仍是 INSTALLING（[versionRowStates] 已经把这两种来源并起来了，这里只是搬运）。
        val installing = states.filterValues { it == VersionRowState.INSTALLING }.keys
        // rootfs 里预装的版本：命中它时安装不会下载任何东西（脚本直接 DONE source=preinstalled）
        val preinstalled = DshInstaller.readVersionFromPackageJson(
            File(File(DshPaths.ROOTFS_DIR, DshPaths.PREINSTALLED_DSH_REL), "package.json")
        )
        val items = allEntries
            .filter { showPrerelease || !it.isPrerelease }
            .map { DshVersionListItem.from(it, installed, preinstalled, broken, installing) }
        _uiState.value = UiState.Loaded(items, showPrerelease, lastWarning)
    }

    /**
     * 安装派发结果。
     * [started] = false 表示**没有**发起安装，界面据此选择文案，避免「明明没开始安装却说安装已开始」（R10-16）。
     *
     * fix12（C-03）：只靠 [started] 一个布尔分不清"已装好，直接复用"与"已有安装在跑，本次被忽略"——
     * 两句事实不同、可操作性也不同（前者去启动，后者等一等）。[reason] 把它们分开，
     * 也顺带取代了原先两处误用的 `dsh_install_skipped`（它的文案只说"已安装"，对在途情况是说谎）。
     */
    enum class DispatchReason {
        /** 本次真的发起了安装 */
        STARTED,
        /** 该版本已装好（有 READY 实例），无需重装，直接复用 */
        ALREADY_INSTALLED,
        /** 该版本已有安装在跑（版本级或实例级单飞闸门拒绝），本次什么都没做 */
        IN_FLIGHT
    }

    data class InstallDispatch(
        val instance: DshInstance?,
        val started: Boolean,
        val reason: DispatchReason = DispatchReason.STARTED
    )

    /**
     * 安装某版本：优先复用已有实例/空壳，避免反复点击在列表里积累一堆空实例；否则新建一个。
     * @return 派发结果（实例 + 是否真的发起了安装 + 原因）
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
            return InstallDispatch(running, started = false, reason = DispatchReason.IN_FLIGHT)
        }
        val existing = chooseInstanceToInstall(DshInstances.instances.value, item.version)
        // 已 READY 装过该版本：无需重装，直接复用，避免每次点击都重新下载整棵依赖树
        if (existing != null && existing.state == DshInstance.State.READY) {
            // 实例级：这里 `existing` 是明确解析出来的实例 —— "你点安装但跳过了，因为它已经装好了"
            // 是那个实例的状态结论，用户点开它的日志页应该能看到这次点击为什么没产生安装。
            DshLogBus.appendFor(existing.id, "[download] ${existing.name} 已安装 dsh ${item.version}，跳过重复安装")
            return InstallDispatch(existing, started = false, reason = DispatchReason.ALREADY_INSTALLED)
        }
        val inst = existing ?: DshInstances.create(instanceName ?: "dsh ${item.version}")
        // ★ E6-9：置位在 install() **之前**（这一瞬只有这里知道"正在装"，见 E5 §2.2 B7：
        //   install() 内部才写 state=INSTALLING），但因此"被闸门拒绝"时必须自己回滚 ——
        //   拒绝路径不产生任何 Progress 事件，否则这一行会永久卡在"安装中"且没有按钮
        //   （只能靠别人的终态顺带清掉，而 Failed(version=null) 那支根本清不到）。
        _installingVersions.value = _installingVersions.value + item.version
        // installer.install 返回 false = 该实例已有安装在跑（单飞拒绝），同样不算「本次发起」
        val started = installer.install(inst, item.version)
        if (!started) _installingVersions.value = _installingVersions.value - item.version
        return InstallDispatch(
            inst,
            started,
            reason = if (started) DispatchReason.STARTED else DispatchReason.IN_FLIGHT
        )
    }

    companion object {
        /**
         * UI 侧等待 registry 的上限（BL-017：从 20s 降到 10s，"界面不再傻等"的上限）。
         * 不是网络超时本身：后台线程走自己的 connect/read 超时 + retry(3)，最长约 60s；
         * 这里一到点就回退旧列表 + warning，不再阻塞界面。
         */
        const val FETCH_TIMEOUT_MS = 10_000L

        /**
         * 空态判定（BL-117，纯函数，便于单测）：Loaded 且列表为空时显示哪个空态文案。
         * - 开关 OFF 且过滤后为空 → "仅显示稳定版"下的空态（提示去开预览开关）；
         * - 其余空列表 → 列表本来就是空的。
         * @return 文案资源 id；非空列表返回 null（不显示空态）
         */
        @JvmStatic
        fun emptyHintRes(itemsEmpty: Boolean, showPrerelease: Boolean): Int? = when {
            !itemsEmpty -> null
            !showPrerelease -> R.string.dsh_download_empty_prerelease
            else -> R.string.dsh_download_empty_all
        }

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
