package com.dsh.core

import com.dsh.fcl.androidlauncher.R

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一份 dsh 树的磁盘事实（只读快照）。
 *
 * ★ fix12（Round 25 §2.2）：把"装好了没"从五套互相矛盾的口径收成一个纯函数的输入。
 *   [readError] 必须是"读 package.json 时抛异常"（EIO / 权限 / 同名目录），
 *   **不是**"文件不存在"（后者是 [hasPackageJson] == false）；两者混同会让"磁盘一时读不了"
 *   被当成"没装"，进而把状态改错、把目标版本擦掉。
 */
data class InstallFacts(
    // package.json 里的 version；解析不出 = null
    val version: String?,
    val hasPackageJson: Boolean,
    val hasBinJs: Boolean,
    // 读 package.json 时抛异常（EIO/权限）——区别于"文件不存在"
    val readError: Boolean = false
) {
    /** 与 DshInstaller.isInstallComplete(dir, null) 完全等价 */
    val complete: Boolean get() = version != null && hasBinJs
}

/** repair 对单个实例的裁决（纯数据，日志/写盘由调用方做） */
data class RepairPlan(
    // 写入 dshVersion 的值；null = 保持原值（不覆盖）
    val version: String?,
    val state: DshInstance.State,
    // true = 本次不动它（磁盘读失败，事实不明；宁可保留旧结论也不猜）
    val keepAsIs: Boolean = false,
    // 日志用，英文
    val reason: String = ""
)

/**
 * requested = null / 空白 / "latest" / 与 actual 相等 ⇒ 视为匹配（与 isInstallComplete 的 null 语义一致）。
 *
 * "latest" 之所以算匹配：它是界面上的"最新版"这个 tag，不是精确版本号；预装的是什么就是什么。
 * null 之所以算匹配：旧清单/新建实例没有目标版本，预装完整时它本来就"能跑"（收编孤儿目录的口径）。
 *
 * ★ R2b-A1-2（fix12 收尾）：**空白串也算匹配**。原来只有 null 算，于是
 *   `dshVersion = ""`（手工编辑 / 回滚留下的脏值）在这一侧判"不匹配 ⇒ NOT_INSTALLED"，
 *   而 `resolveLaunchTarget` 的 `takeIf { isNotBlank() }` 判"没有具体请求 ⇒ 放行预装" ——
 *   同一台实例，卡片说未安装、点启动却能跑。两套判据必须逐序等价，
 *   所以这里把"空/纯空白"与 null 归成同一档（语义上都是"没有目标版本"）。
 */
fun versionMatches(requested: String?, actual: String?): Boolean =
    requested == null || requested.isBlank() || requested == "latest" ||
        (actual != null && requested == actual)

/**
 * repair 的判定内核（顶层纯函数，JVM 可测）。
 *
 * 判定表（顺序即优先级，**不得调换**）：
 * 0. 任一侧磁盘读取抛异常 ⇒ 不动它（事实不明时改状态 = 把可恢复的读故障变成永久误判）；
 * 1. 自己的树完整 ⇒ READY，版本取磁盘真实值（BROKEN 也随之自愈 —— 条件 sticky 的逃生门）；
 * 2. 自己有 package.json 但没有 lib/bin.js ⇒ **不回退预装**，按不完整处理（与 effectiveDshDir
 *    的"看到 package.json 就认为是它"同口径；否则卡片说就绪、启动却跑另一份包）；
 * 3. 自己没有 package.json、预装完整且版本相符（含目标版本为 null/latest）⇒ READY + 预装版本；
 * 4. 其余 ⇒ 保留 BROKEN（保住失败原因），否则 NOT_INSTALLED。
 *
 * BROKEN 保留时 `version = null`，即**不覆盖** dshVersion —— 否则"重试"的目标版本会被磁盘上的
 * 半棵树/预装版本带跑（修 E1-6 的重试目标漂移）。
 */
fun planRepair(inst: DshInstance, own: InstallFacts, pre: InstallFacts): RepairPlan {
    // ★ R3a-A1-1 / A1-2（fix12 收尾）：读失败**只在自己那份树**上才值得"什么都不动"。
    //   原来 `own.readError || pre.readError` 一起短路，代价有两条：
    //   ① 自己的树完整、但预装那份读不出来 ⇒ 一个好实例被留在旧状态（NOT_INSTALLED/损坏），
    //      用户看到「安装」按钮，最自然的动作是重装 500MB 把好树覆盖掉；
    //      而 `pre` 是**循环外只读一次**的共享输入 ⇒ 一次读失败让全部实例本轮都不被校准。
    //   ② 上次安装被杀留下的持久化 INSTALLING 会被永久跳过清理（keepAsIs 早于兜底），
    //      卡片画成「安装中」且不可点，连「重装/删除」都藏在详情页 —— 没有"再校准一次"的出口。
    //   正确的边界：自己树的事实足够裁决时（own 完整 / own 有 package.json 但缺入口），
    //   根本不需要 pre；只有"要靠预装兜底"的那一支才受 pre 读失败影响。
    if (own.readError) {
        return RepairPlan(version = null, state = inst.state, keepAsIs = true, reason = "own tree read failed")
    }
    if (own.complete) {
        return RepairPlan(version = own.version, state = DshInstance.State.READY, reason = "own tree complete")
    }
    if (own.hasPackageJson) {
        // 半棵树（缺 bin.js）：结论不依赖 pre，所以 pre 读失败也不该改写它。
        return RepairPlan(
            version = null,
            state = if (inst.state == DshInstance.State.BROKEN) DshInstance.State.BROKEN
            else DshInstance.State.NOT_INSTALLED,
            reason = "own tree incomplete (missing lib/bin.js)"
        )
    }
    // 到这里只剩"自己没有包、要靠预装兜底"——这一支才真正需要 pre 的事实。
    if (pre.readError) {
        return RepairPlan(version = null, state = inst.state, keepAsIs = true, reason = "preinstalled tree read failed")
    }
    if (pre.complete && versionMatches(inst.dshVersion, pre.version)) {
        return RepairPlan(version = pre.version, state = DshInstance.State.READY, reason = "preinstalled")
    }
    return RepairPlan(
        version = null,
        state = if (inst.state == DshInstance.State.BROKEN) DshInstance.State.BROKEN
        else DshInstance.State.NOT_INSTALLED,
        reason = "not installed"
    )
}

/**
 * 目录 fsync（落点 2，best-effort）：rename 之后把父目录的目录项刷到盘上，
 * 否则断电时可能"rename 丢了 = 旧文件还在、新文件不见了"。
 *
 * ★ 只能用 `O_RDONLY`：`OsConstants.O_DIRECTORY` 在本项目用的 android.jar 里不存在（E2 实测），
 *   而 `O_RDONLY` 是 0，在 Linux 上以只读打开目录再 fsync 是合法做法。
 * ★ JVM 单测下 `android.system.Os` 是 `Stub!`，所以调用方必须经 [DshInstances.dirFsync] 注入，
 *   且**任何失败都只记日志**（E5-04：把"写成功但目录 fsync 失败"报成写失败会触发无谓的重试）。
 */
private fun platformFsyncDir(dir: File) {
    val fd = android.system.Os.open(dir.absolutePath, android.system.OsConstants.O_RDONLY, 0)
    try {
        android.system.Os.fsync(fd)
    } finally {
        android.system.Os.close(fd)
    }
}

/**
 * dsh 实例的仓库单例，仿 [com.tungsten.fcl.setting.Profiles]：
 * - 用 StateFlow 暴露实例列表与当前选中项，UI 层订阅即可自动刷新
 * - 增删改统一走本对象的方法，内部负责持久化到 [DshPaths.INSTANCES_MANIFEST]
 * - 选中项在列表变化时自动校正（删掉当前选中时回落到第一个）
 *
 * ## 本次加固（对应审查发现的问题）
 * 1. **卡死状态自愈**：原来 `INSTALLING` 会被持久化，装到一半退出（Activity 销毁 / 进程被杀）后
 *    该实例永远停在"安装中"，界面既不能启动也不能重装。现在 [init] 之后会异步按磁盘真实内容
 *    校准状态（[repair]）：装好了→READY，没装完→NOT_INSTALLED/BROKEN。
 * 2. **主线程磁盘 IO**：原来 `delete()` 在主线程 `deleteRecursively()` 删 ~300MB（必 ANR），
 *    `save()` 也在调用线程写盘。现在删除和写盘都在 [DshAppScope] 的 IO 上，写盘用"临时文件+rename"
 *    原子替换，且在 `Mutex` 里串行化（避免并发写坏清单）。
 * 3. **id 冲突**：原来 id = `inst-<毫秒>`，同一毫秒创建两个实例会撞 id（目录互踩、清单出现重复项）。
 *    现在加进程序列号 + 随机后缀，并保证与现有 id 不重复。
 * 4. **清单损坏**：原来 JSON 解析失败会被静默吞掉（用户看到"实例全没了"）。现在把坏文件另存为
 *    `.corrupt-<时间>`，并往日志总线写一行，便于事后找回。BL-098 起分两级：单个坏元素（未知
 *    state 枚举名 / 缺 id、name 必填字段）只丢该元素并逐条警告，只有**整份**解析不了才走
 *    `.corrupt`；读文件失败（EIO）不再直穿 [init] / FCLApp.onCreate。
 * 5. 新增 [rename]/[updateConfig]/[recheck]，配合新的设置页与"修复"入口。
 *
 * ## Round 15 批 B 加固（BL-099/100/101）
 * 6. **id 生成原子化**（BL-099）：[newId] 的"查列表 + 查目录"判据与目录占位现在在同一临界区
 *    （[createLock]）内完成，生成与占位之间不再有 TOCTOU 窗口；id 组装规则抽成公开纯函数 [buildId]。
 * 7. **update 先 copy 再入表**（BL-100）：不再原地改传入对象再别名进列表（传列表内同一对象时
 *    列表结构相等、可能静默不发射）。[DshInstance] 的字段可变性约定见其类 KDoc。
 * 8. **空清单孤儿回收 + corrupt 恢复就绪**（BL-101）：[listCorruptBackups] 供未来"从备份恢复"的
 *    UI 使用；corrupt 备份日志现在带**完整路径**；清单最终为空（缺失文件 / 空白 / 整体损坏 /
 *    显式空数组）时，[loadFrom] 会扫描 instances 目录（判据内核 [rebuildMissingInstances]，
 *    纯函数可单测），把"存在但不在清单里"的目录收编为 NOT_INSTALLED 实例，不再永久隐形占盘。
 */
object DshInstances {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val writeMutex = Mutex()
    private val seq = AtomicInteger(0)

    /**
     * BL-099：实例 id 生成与目录占位的临界区锁。
     * [newId] 的判据是"列表里没有 且 instances 目录里没有"，而"把 id 落到目录（mkdirs 占位）"
     * 发生在判据返回之后 —— 两步之间若无锁，并发的 [create] 理论上可以拿到同一个 id（先查后建）。
     * 用独立锁对象而不是 this 的监视器：`@Synchronized` 那套（[init]/[resetForTest]）的语义
     * 与实例创建流程无关，不混用。
     */
    private val createLock = Any()

    /** [delete] 等实例离开 Starting/Running/Stopping 的上限 —— 与 [DshRuntime.stopAndWait] 的默认预算一致 */
    private const val DELETE_WAIT_TIMEOUT_MS = 8_000L

    /** [delete] 轮询 [DshRuntime.activeInstanceId] 的间隔（停止收尾在秒级，100ms 足够跟手且不费） */
    private const val DELETE_WAIT_POLL_MS = 100L

    // 清单备份的后缀前缀（`.corrupt-` / `.empty-` / `.dropped-`），见 [listCorruptBackups]。
    // private const 不能用于 List，用 val（只读引用，不随实例状态变化）。
    private val BACKUP_PREFIXES = listOf(".corrupt-", ".empty-", ".dropped-")

    private val _instances = MutableStateFlow<List<DshInstance>>(emptyList())
    val instances: StateFlow<List<DshInstance>> = _instances.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** 正在删除中的实例 id（UI 显示"删除中"并禁用交互） */
    private val _deleting = MutableStateFlow<Set<String>>(emptySet())

    /**
     * **墓碑**：删除已经摘了清单、但目录 `deleteRecursively()` 没删干净（文件被占用/权限）的实例 id。
     *
     * ★ R3b-B3-6（fix12）：没有它的话，下次冷启的 [adoptOrphanInstanceDirs] 会把这个残留目录
     *   原样收养回来（它只按"目录存在且不在清单里"判），用户看到"删掉的实例又回来了"（空壳）。
     *
     * 只在**进程内**有效（内存 Set）：重启后墓碑消失、残留目录会被再收编一次 —— 这是刻意的取舍，
     * 因为持久化墓碑要往清单里加字段（Gson 双向兼容代价，见 E1 §5 对新增持久字段的论证），
     * 而"删不干净"本身是极少数情况（文件系统层面失败）。真机上若反复出现，说明该目录的
     * 删除需要用户介入（adb / 重装 App），届时再考虑持久化。
     */
    private val tombstonedInstanceIds = MutableStateFlow<Set<String>>(emptySet())
    val deleting: StateFlow<Set<String>> = _deleting.asStateFlow()

    /**
     * 清单**读取**失败（拒写中）。UI 横幅读它。
     *
     * ★ 为什么读失败要拒写（Round 25 §2.3 / E2-C-04）：读失败时内存里的事实是"空的"
     *   （init 路径）或"上一次的"（重试路径），此时任何一次 [saveAsync] 都会把内存当成真相
     *   写回磁盘 —— 原清单里那些没被读进来的实例就**永久消失**了（E2-F10 已实测复现）。
     *   写成功**永不清**这个位：否则就是"读到的还是空清单、但因为写成功了所以认为好了"的自证清白。
     *   清位只有两条：重试读取成功且本次会话无未保存改动；进程重启。
     */
    private val _manifestLoadFailed = MutableStateFlow(false)
    val manifestLoadFailed: StateFlow<Boolean> = _manifestLoadFailed.asStateFlow()

    /** 最近一次写盘失败（下次写成功自动清）。UI 横幅读它。 */
    private val _manifestWriteFailed = MutableStateFlow(false)
    val manifestWriteFailed: StateFlow<Boolean> = _manifestWriteFailed.asStateFlow()

    /**
     * 读失败期间发生的内存改动次数（[create]/[update]/[updateConfig]/[markState]/[delete]/[select]）。
     *
     * ★ 为什么需要它：拒写期间用户仍能新建/改名/删除（界面层不禁用），这些改动只活在内存里。
     *   若"重试读取"直接整份替换 `_instances`，这些改动会被**无声冲掉**；
     *   所以重试只在计数为 0 时才允许覆盖内存（E2-N-5）。
     */
    private var mutationsWhileLoadFailed = 0

    /**
     * 目录 fsync 的注入点（测试用）。null = 走平台实现 [platformFsyncDir]。
     * ★ 必须留这个 seam：`android.system.Os` 在 JVM 单测里是 `Stub!`，否则"rename 后刷目录"
     *   这段只能靠"跑了不崩"来假装测过（E2 实测）。
     */
    internal var dirFsync: ((File) -> Unit)? = null

    private var initialized = false

    /** App 启动时调用一次：从磁盘载入清单 + 异步校准状态 */
    @JvmStatic
    @Synchronized
    fun init() {
        if (initialized) return
        initialized = true
        load()
        // 状态校准放到 IO，别阻塞启动
        DshAppScope.scope.launch { repair() }
        // ★ 读失败时给一次"重试读取"的机会（Round 25 §2.3 规则 6）：启动瞬间的 EIO 常常是
        //   系统还在忙（开机解锁 / 存储挂载收尾），过一次 IO 队列往往就好了。只试一次，
        //   失败就保持横幅，由用户点「重试」或重启。
        if (_manifestLoadFailed.value) {
            DshAppScope.scope.launch { retryManifestLoad() }
        }
    }

    /** 测试/重置用 */
    @Synchronized
    internal fun resetForTest() {
        initialized = false
        _instances.value = emptyList()
        _selectedId.value = null
        _manifestLoadFailed.value = false
        _manifestWriteFailed.value = false
        mutationsWhileLoadFailed = 0
        // 墓碑也要清：否则上一条用例留下的 id 会让下一条的收养扫描静默跳过同名目录，
        // 症状是"测试单独跑绿、整批跑红"这种最难查的一类。
        tombstonedInstanceIds.value = emptySet()
    }

    /** 读失败期间每改一次内存调一次（调用方先确认 [manifestLoadFailed] 为真） */
    private fun countMutationWhileLoadFailed() {
        if (_manifestLoadFailed.value) mutationsWhileLoadFailed++
    }

    val selected: DshInstance?
        get() = _instances.value.firstOrNull { it.id == _selectedId.value }

    fun byId(id: String): DshInstance? = _instances.value.firstOrNull { it.id == id }

    fun select(id: String) {
        if (_instances.value.any { it.id == id }) {
            _selectedId.value = id
            countMutationWhileLoadFailed()
            saveAsync()
        }
    }

    /** 新建一个实例（仅建目录与元数据，尚未安装 dsh），返回该实例 */
    fun create(name: String, model: String = DshInstance.MODEL_FLASH): DshInstance {
        // BL-099：id 的"生成 → 判重 → 占位"收进同一临界区（[createLock]）。
        // 旧实现 newId() 判重后返回、由本函数在锁外 mkdirs —— 两步之间无锁，并发的 create
        // 理论上能拿到同一个 id（先查后建）。
        // 占位即"建出实例目录"：newId 的 dir.exists() 判据查的就是它，目录落盘 = 占用生效；
        // 清单（_instances）入表晚一步没关系 —— 判据里的目录检查会拦住后来者。
        val id = synchronized(createLock) {
            val fresh = newId()
            DshPaths.instanceDir(fresh).mkdirs()
            DshPaths.instanceHome(fresh).mkdirs()
            DshPaths.instanceWorkspace(fresh).mkdirs()
            fresh
        }
        val inst = DshInstance(
            id = id,
            name = name,
            model = model,
            port = 0 // 0 = 交给系统分配，见 DshRuntime
        )
        _instances.update { it + inst }
        if (_selectedId.value == null) _selectedId.value = id
        countMutationWhileLoadFailed()
        saveAsync()
        return inst
    }

    /**
     * 实例 id 的组装规则（public 纯函数，便于单测；BL-099 从 [newId] 的内联拼接抽出）。
     * 形如 `inst-1700000000000-3-7f2a`：毫秒时间戳 + 进程序列号 + 16 位随机值的十六进制
     * （[Integer.toHexString]，小写、无零填充 —— 与旧拼接逐字节一致）。
     * 唯一性保证（与清单、instances 目录互查重）在 [newId] 的循环里，不归本函数。
     */
    fun buildId(ms: Long, seq: Int, rand: Int): String = "inst-$ms-$seq-${Integer.toHexString(rand)}"

    /**
     * 用不可重复的 id：毫秒 + 进程序列号 + 随机后缀（拼接规则见 [buildId]）。
     * ★ 必须在 [createLock] 临界区内调用：判重（清单 + 目录）与调用方的"目录占位"之间
     *   一旦可插入别的创建者，两个并发 [create] 就能拿到同一个 id（BL-099）。
     */
    private fun newId(): String {
        while (true) {
            val id = buildId(
                System.currentTimeMillis(),
                seq.incrementAndGet(),
                (Math.random() * 0xffff).toInt()
            )
            if (_instances.value.none { it.id == id } && !DshPaths.instanceDir(id).exists()) return id
        }
    }

    /**
     * 更新某实例（传入已修改的副本），并持久化。
     *
     * BL-100：**先 copy 再入表**。旧实现把传入对象的 `updatedAt` 原地改掉、再把**同一引用**
     * 放进列表 —— 传入的就是列表内对象（别名）时，新列表与旧列表结构相等，
     * MutableStateFlow 的相等性抑制会让这次变更**静默不发射**（收集者看不到，且无任何征兆）。
     * 现在统一以"复制品"入表：调用方的所有字段改动都被带着走，列表项引用必然变化 ⇒ 可靠发射；
     * 传入对象自身也不再被本方法偷偷改动。
     * （字段可变性与写入路径的整体约定见 [DshInstance] 类 KDoc。）
     */
    fun update(instance: DshInstance) {
        val next = instance.copy(updatedAt = System.currentTimeMillis())
        _instances.update { list -> list.map { if (it.id == next.id) next else it } }
        countMutationWhileLoadFailed()
        saveAsync()
    }

    /** 只改配置字段（设置页用） */
    fun updateConfig(
        id: String,
        name: String? = null,
        model: String? = null,
        profile: String? = null,
        port: Int? = null
    ) {
        _instances.update { list ->
            list.map {
                if (it.id != id) it else it.copy(
                    name = name ?: it.name,
                    model = model ?: it.model,
                    profile = profile ?: it.profile,
                    port = port ?: it.port,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        countMutationWhileLoadFailed()
        saveAsync()
    }

    fun rename(id: String, newName: String) = updateConfig(id, name = newName)

    /** 标记安装状态并记录版本 / 失败原因 */
    fun markState(id: String, state: DshInstance.State, version: String? = null, error: String? = null) {
        _instances.update { list ->
            list.map {
                if (it.id == id) it.copy(
                    state = state,
                    dshVersion = version ?: it.dshVersion,
                    lastError = if (state == DshInstance.State.BROKEN || state == DshInstance.State.NOT_INSTALLED) {
                        error ?: it.lastError
                    } else {
                        null
                    },
                    updatedAt = System.currentTimeMillis()
                ) else it
            }
        }
        countMutationWhileLoadFailed()
        saveAsync()
    }

    /**
     * 依据磁盘真实内容校准所有实例状态（IO 线程调用）。
     *
     * fix12（Round 25 §2.2）：判定内核改为顶层纯函数 [planRepair]，这里只负责"取事实 + 落裁决"：
     * - 自己的树完整（有 version 且有 `lib/bin.js`）→ READY，版本纠正为磁盘真实值；
     *   此时 **BROKEN 会自愈**（BROKEN 是"条件 sticky"：只在树不完整时保留）。
     * - 自己的树不完整（包括"有 package.json、缺 lib/bin.js"的半棵树）→ NOT_INSTALLED（BROKEN 保留）。
     * - 自己没有树、预装完整且版本相符（目标为 null / latest 也算）→ READY + 预装版本。
     * - 任一侧读盘抛异常 → 这个实例原样不动（事实不明时改结论 = 把可恢复的故障变成永久误判）。
     * - 只清掉 INSTALLING 这种"卡死"的瞬态（它落到 NOT_INSTALLED/READY 的兜底里）。
     */
    fun repair() {
        // 0) 顺带清掉历史遗留的凭据文件（见该函数的注释）。
        //    放在 repair 里是因为这里本来就在 IO 线程上、本来就要遍历全部实例 ——
        //    这是"就地升级上来的旧安装"唯一会被统一扫一遍的时机。
        cleanupLegacyCredentialFiles()
        // ★ 先在 `_instances.update {}` 之外算好"磁盘事实"：update 的 lambda 在并发修改时会
        // 被重试，把文件 IO 放在里面（旧实现）会有两个坏处：① 同样的 readText 被重复执行；
        // ② 在原子更新的重试循环里持有 IO 时间。现在只把结果带进去做纯内存替换。
        val planned = HashMap<String, RepairPlan>()
        // 预装 dsh 的事实：**循环外只读一次**（它是所有实例共享的底座，每个实例各读一遍纯属浪费 IO）。
        // 判定顺序与 `effectiveDshDir` 的解析顺序同口径 —— 见 [planRepair] 序 2/序 3。
        val pre = readInstallFacts(File(DshPaths.ROOTFS_DIR, DshPaths.PREINSTALLED_DSH_REL))
        _instances.value.forEach { inst ->
            // ★ 判定"这个实例装好了没"必须区分两件事：
            //   ① 实例**自己**的 node_modules（真实安装结果）
            //   ② rootfs 里的**预装** dsh（共享底座）
            // 旧实现用 effectiveDshPackageJson（会回退到预装）判"有没有包"，于是"请求 0.2.1、
            // 安装失败"的实例会因为预装有 0.1.6 而被判成 READY —— 卡片出现"标题 0.2.1 /
            // 副标题 0.1.6 / 就绪"这种自相矛盾的状态。现在两份事实分别取、由 [planRepair] 裁决。
            //
            // ★ 必须用 [DshPaths.ownDshDir] 而不是 effectiveDsh*：后者"看到 package.json 就认为
            //   是它"，而这正是本轮要收口的错位（宿主按 package.json 判、脚本按 lib/bin.js 判）。
            val own = readInstallFacts(DshPaths.ownDshDir(inst.id))
            val plan = planRepair(inst, own, pre)
            if (plan.keepAsIs) {
                // 事实不明：状态原样留着，但必须留痕 —— 否则"这次启动为什么没校准"无从解释。
                DshLogBus.append("[instances] ${inst.id}: ${plan.reason}; state left unchanged")
                return@forEach
            }
            planned[inst.id] = plan
        }

        var changed = false
        _instances.update { list ->
            list.map { inst ->
                val plan = planned[inst.id] ?: return@map inst // 期间新建的 / keepAsIs 的：不动它
                if (inst.state == plan.state && (plan.version == null || inst.dshVersion == plan.version)) {
                    inst
                } else {
                    changed = true
                    inst.copy(
                        state = plan.state,
                        // 树完整/命中预装 → 用磁盘真实版本；否则 plan.version == null ⇒ 保留
                        // "目标版本"（安装开始时写入），这样卡片能显示"本来要装什么"，
                        // 也才能被"同版本重试"复用（修 E1-6 的重试目标漂移）。
                        dshVersion = plan.version ?: inst.dshVersion,
                        lastError = if (plan.state == DshInstance.State.READY) null else inst.lastError,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
        }
        if (changed) {
            // ★ R3b-B4（fix12 收尾）：repair 也是**改内存**的路径，必须计入"读失败窗口内的改动"，
            //   否则重试读成功时会把它整份覆盖（`_instances.value = parsed.instances`），
            //   用户看到的"已按磁盘校准"会在一秒后变回磁盘上的旧结论。
            countMutationWhileLoadFailed()
            saveAsync()
        }
    }

    /**
     * 读一份 dsh 树的三件事实（[InstallFacts]）：version / 有无 `package.json` / 有无 `lib/bin.js`。
     *
     * ★ 只认 `package.json` 是**文件**（[File.isFile]）：同名目录不算（`readText` 会 EISDIR，
     *   但那就该走 readError 而不是"有 package.json"）。
     * ★ "读 package.json 抛异常" 与 "文件不存在" 必须分开：前者是 EIO/权限（事实不明，不能动状态），
     *   后者是"确实没有"（可判 NOT_INSTALLED）。这是 [planRepair] 序 0 的输入。
     *
     * ★ 从 `private` 提到 `internal`（R26 / E6 §5.3-17）：这是 `planRepair` 的**唯一**输入入口，
     *   而 `R26ConsistencyTest` 要拿它和 `DshInstaller.isInstallComplete` 做跨实现一致性对照。
     *   若保持私有，测试只能**重抄**一遍解析逻辑（我第一版就是这么写的）—— 那是"镜像实现"型假绿：
     *   生产侧解析改了，测试的抄本不跟着改，断言仍然绿，等于没测。
     */
    internal fun readInstallFacts(dir: File): InstallFacts {
        val pkg = File(dir, "package.json")
        val hasPkg = pkg.isFile
        val hasBin = File(dir, "lib/bin.js").isFile
        if (!hasPkg) return InstallFacts(version = null, hasPackageJson = false, hasBinJs = hasBin)
        val reading = runCatching { pkg.readText() }
        val text = reading.getOrNull()
            ?: return InstallFacts(version = null, hasPackageJson = true, hasBinJs = hasBin, readError = true)
        // 解析失败（坏 JSON）不算"读失败"：文件读到了，只是内容认不出 ⇒ 按"没有版本"处理，
        // 与 DshInstaller.readVersionFromPackageJson 的 runCatching 口径一致。
        val version = installFactsVersionOf(text)
        return InstallFacts(version = version, hasPackageJson = true, hasBinJs = hasBin)
    }

    /**
     * 从 package.json 文本取 `version`：解析不了 / 不是对象 / 缺失 / 非字符串 / 空串 ⇒ null。
     *
     * 语义与 `DshInstaller.readVersionFromPackageJson` 的 `pkg.version?.takeIf { it.isNotBlank() }` 一致；
     * 分开实现是为了让"读文件"与"解析文本"两步各自可判（前者失败才是 readError）。
     *
     * ★ 同 [readInstallFacts]：提到 `internal` 供跨实现一致性测试直接用（避免测试重抄逻辑）。
     */
    internal fun installFactsVersionOf(text: String): String? = runCatching {
        val root = JsonParser.parseString(text)
        if (!root.isJsonObject) return@runCatching null
        root.asJsonObject.stringOrNull("version")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 清掉**历史版本**留下的凭据文件：旧的 `credentials.env`（明文！）与 `credentials.enc`
     * （启动器自己那套 Keystore 密文）。
     *
     * ## 为什么现在才清、为什么必须清
     * 这两份文件是"启动器自己管 API Key"那个时期的产物。改造后 Key 归 dsh 自己管
     * （存在实例 home 里的 `.credentials.yaml`），启动器**再也不会读它们** ——
     * 也就是说，旧安装升级上来之后，一个**明文 API Key 会一直躺在实例目录里**，
     * 而没有任何功能需要它。删除是纯粹的卫生问题，但它涉及密钥，不能拖。
     *
     * ## 为什么放在 repair（而不是一次性迁移）
     * 不需要额外的版本标记：这两份文件的存在本身就是"旧安装"的判据，
     * 而 repair 在每次启动时都会跑一遍全部实例。删一个不存在的文件是空操作，
     * 幂等、无状态、不会因为"迁移标记丢了"而漏清。
     *
     * 只删**文件**，不动目录。失败静默忽略（下次启动还会再试）。
     */
    private fun cleanupLegacyCredentialFiles() {
        _instances.value.forEach { inst ->
            listOf("credentials.env", "credentials.enc").forEach { name ->
                runCatching {
                    val f = java.io.File(DshPaths.instanceDir(inst.id), name)
                    if (f.exists() && f.delete()) {
                        DshLogBus.appendFor(inst.id, "[instances] 已清理遗留凭据文件 $name（启动器不再管理 API Key）")
                    }
                }
            }
        }
    }

    /**
     * 删除实例：从清单移除并异步删除磁盘目录（约 300MB，不能放主线程）。
     *
     * ★ fix12（Round 25 §2.6）：**写盘成功才删目录**。旧实现是"先摘内存、异步写盘、无条件删目录"，
     *   那次写盘一旦失败（磁盘满 / rename 失败 / 读失败期间被拒写），磁盘上的清单**没变**而目录
     *   已经删掉 ⇒ 下次冷启该实例"原样复活"、点进去是一个空目录（E2-N-1 / N-8 实测复现）。
     *   现在把"写清单"提到删目录之前、并且同步等它出结果；写不进去就把内存**回滚**（实例回到
     *   列表）并让调用方知道删除没发生。
     *
     * @param onFinished 目录删完后回调（主线程/IO 线程不定，调用方自行切线程）。
     *                   写盘失败/拒绝时以 `false` 回调（且什么都没删）。
     * @return false = 拒绝（清单读取失败中），**什么都没做**（内存没摘、目录没删）。
     *         true = 已受理（真正的成败走 [onFinished]）。
     */
    fun delete(id: String, onFinished: ((Boolean) -> Unit)? = null): Boolean {
        if (_manifestLoadFailed.value) {
            // 拒写期间删目录是最坏的一种"看起来成功"：目录没了、清单没变 ⇒ 重启就复活。
            // 所以这里**一步都不做**（连内存都不摘），让调用方去提示"当前不可删除"。
            DshLogBus.append(
                "[instances] delete $id refused: manifest load failed (its record cannot be rewritten safely)"
            )
            onFinished?.invoke(false)
            return false
        }
        val inst = byId(id)
        // 记住原位置：写盘失败回滚时按原位放回去，别把用户熟悉的排序打乱。
        val removedIndex = _instances.value.indexOfFirst { it.id == id }
        val wasSelected = _selectedId.value == id
        _instances.update { list -> list.filterNot { it.id == id } }
        if (wasSelected) _selectedId.value = _instances.value.firstOrNull()?.id
        _deleting.update { it + id }
        // 只有"确实改了内存"才计改动（读失败期间的删除会被上面的闸门直接拒掉，走不到这里）。
        countMutationWhileLoadFailed()
        // ★ 这里**不再** saveAsync()（老代码在摘内存后立刻异步写一次）：那次写的失败没人接，
        //   而真正的顺序要求是"清单落盘成功之后才允许删目录"——见协程里的同步写。
        DshAppScope.scope.launch {
            // 先停掉可能还在跑的进程（不然删目录会留下活着的 node）。
            // ★ 必须等进程真正退出再删：stop() 是异步的（TERM→5s→KILL），
            // 若不等它，node 可能还在往 node_modules 写文件，deleteRecursively 就会留下
            // 一堆 300MB 级残留且无提示（界面早已把实例从列表里移除）。
            // 等待上限 [DELETE_WAIT_TIMEOUT_MS]（与 DshRuntime.stopAndWait 的预算一致），
            // 超时也硬删并靠日志提示。
            //
            // ★ 归属校验（BL-036 重写；旧判据 `runningInstanceId() == id` 漏掉了 Stopping）：
            // DshRuntime 是单实例策略，stop() 停的是**当前正在运行的那个实例**，与传入的 id 无关。
            // 无条件调用会"删除没在跑的 B 却杀掉正在跑的 A"（用户只点了删除 B，A 的会话却断了）。
            // 所以先只对**本实例**发 [DshRuntime.stopIf]（幂等：Starting/Running 拉进 Stopping；
            // 已在 Stopping 或没在跑则 no-op、返回 false —— 返回 false 不代表"停完了"，所以
            // 不看返回值，统一由下面的轮询做"是否真的离场"的唯一判据），
            // 再轮询 [DshRuntime.activeInstanceId]（含 Stopping）直到 != id。
            // 旧实现跳过"已经在 Stopping"的实例不等待，于是停止窗口内删实例会与 node 收尾
            // 抢写盘 —— 这正是要修的 BL-036。
            // 若 activeInstanceId() 返回的是**别的实例**（单实例策略下罕见）：不等它、也不停它，
            // 直接删本实例（保留原有归属校验思想）。
            // ★ BL-151（R18 = K13②/P5-6/P1-4）：删除实例必须先取消**进行中的安装**。
            // 旧实现只停运行时（下面的 stopIf + 轮询），安装（npm 在 DshAppScope 里跑）完全没被
            // 通知：npm 继续写 node_modules，随后 deleteRecursively() 与它抢盘 → 目录残留；
            // 更糟的是残留目录会在"清单为空"的启动路径上被 adoptOrphanInstanceDirs 收编——
            // 已删实例"复活"。cancel 与 stopIf 相互独立（cancel 杀的是 tag=id 的安装 proot，
            // 见 DshInstaller.killActive→ProotProcessExecutor.destroyActive；运行期 proot 不在
            // ProotProcessExecutor 的 actives 表里，不会被误杀），所以先 cancel 再 stopIf。
            //
            // ★ 实读结论（写这段代码前核对过 DshInstaller.cancel()，:528-577）：它**同步**发完
            // 取消信号（标 cancelled + 取消 job + 放掉单飞闸门 + SIGQUIT 安装 proot 并挂一个
            // 非阻塞的强杀升级线程）就返回 —— **不等待 npm 退出**。因此下面的等待循环必须在
            // "运行时离场"之外再加"安装收尾"判据（见循环处注释）。
            // cancel 可能抛（守卫竞态等）——runCatching 包住；失败也不中止删除。
            //
            // ★ 任务区关联影响（已核实：可接受，且不改 DshTask.kt）：cancel 收尾会向
            // DshTasks.finished 上报一条 CANCELLED 行（DshInstaller.cancel :566-576），而此刻实例
            // 已被摘出清单（byId == null → 标题回落到 version/id）——即"删除一个正在安装的实例"
            // 会在任务区留下一条指向**已删实例**的"已取消"历史行。这在任务语义上是对的：
            // finished 是**事件**历史而不是状态表，"这次删除顺带取消了安装"本身就是该留下的事件；
            // 且它不会失控：上限 MAX_FINISHED=20 自动淘汰最老的，终态行在任务区一律无动作按钮、
            // 整行无点击监听（TaskAdapter 对终态强制 Action.NONE 并清监听），点不出指向已删实例的界面。
            val appCtx = DshAppContextHolder.context
            val installer = appCtx?.let { DshServices.installer(it) }
            val cancelNote = if (installer == null) {
                "skipped (no app context)"
            } else {
                // "请求时有没有可取消的任务"只进日志：cancel 自身有守卫（无任务时 no-op 并记
                // "[install] cancel ignored …"），这里先记下请求时的状态，便于排障时区分
                // "本来就没有安装"与"cancel 没生效"。
                val hadActiveInstall = installer.isInstalling(id) || installer.statusOf(id) != null
                runCatching { installer.cancel(id) }.fold(
                    onSuccess = {
                        if (hadActiveInstall) "requested (in-flight install signalled)"
                        else "no active install"
                    },
                    onFailure = { e -> "failed: ${e.message}" }
                )
            }
            // ★ 保持全局（与 stopTimedOut 同一理由：该实例正在被删除，写进它的实例流没人能再看）。
            DshLogBus.append("[instances] install cancel for ${inst?.name ?: id}: $cancelNote")
            val reason = DshAppContextHolder.context?.getString(R.string.dsh_reason_deleted)
                ?: "Instance deleted"
            runCatching { DshRuntime.stopIf(id, reason) }.onFailure { e ->
                // stopIf 设计上不该抛；万一抛了也不能让删除协程死掉（那样 _deleting 永远清不掉），
                // 继续走下面的等待循环 —— 进程若真在跑，还会被等到超时才硬删。
                DshLogBus.append("[instances] stopIf failed for $id: ${e.message}")
            }
            var stopTimedOut = false
            // ★ BL-151：等待条件 = "运行时离场" **或** "安装收尾完成"（两者共用同一个 deadline，
            // 总预算与旧实现完全相同，不叠加）。两条安装侧判据的分工：
            // · [DshInstaller.isInstalling]（= 单飞闸门）在 cancel() 返回那一刻就被放掉 ——
            //   拿它做唯一判据等于没等（cancel 不等 npm 退出，见上）。保留它只覆盖"取消没生效 /
            //   删除期间又有任务占闸"这类窗口，是廉价的第一道检查；
            // · "npm 真退了吗"由 [installProotStillAlive] 回答：读安装的 dsh-install.pid，
            //   pid 存活且 cmdline 里同时有 proot 与本实例 id（与 BL-041 的孤儿清理
            //   cleanupStaleInstallProcess 同一判据）。该文件在"pid 解析成功"时写入、
            //   在 runInstall 的 finally（一切终态）compare-and-delete —— 文件还在且进程
            //   活着 ⇔ npm 还在写盘；SIGQUIT→proot 退出→finally 删文件后循环自然退出。
            //   连"App 上次被杀、留了活着的孤儿 npm"这种跨进程残留也一并挡住（它同样在写盘，
            //   且不在本进程的 actives 里、cancel 杀不到它）。
            // 超时处理沿用既有口径（硬删 + 全局日志），不是新语义。
            val waitDeadline = System.currentTimeMillis() + DELETE_WAIT_TIMEOUT_MS
            while (DshRuntime.activeInstanceId() == id ||
                installer?.isInstalling(id) == true || installProotStillAlive(id)
            ) {
                if (System.currentTimeMillis() >= waitDeadline) {
                    stopTimedOut = true
                    break
                }
                delay(DELETE_WAIT_POLL_MS) // 协程里轮询：让出线程，别阻塞
            }
            if (stopTimedOut) {
                // ★ 保持全局（不进该实例自己的日志）：这个实例正在被**删除**，写进它的实例流
                // 等于刚写完就要被丢弃/重建，谁也看不到。而"删除时进程没停干净"恰恰是排障
                // 要的（残留 300MB），所以必须留在全局流里。
                DshLogBus.append(
                    "[instances] stop timed out for ${inst?.name ?: id}, deleting directory anyway"
                )
            }
            // ★ fix12（E2-N-8）：**先让清单落盘，成功之后才允许删目录**。
            //   顺序不能反：目录一旦删掉就无法回滚（300MB 的用户 home/workspace 也在里面），
            //   而清单写失败是**可回滚**的（把内存里的实例放回去即可）。
            //   写盘必须同步等结果（writeMutex 内 writeNow），不能用 saveAsync 那种 fire-and-forget
            //   —— 旧实现正是"异步写 + 无条件删"，写失败时实例在下次冷启原样复活、点进去是空目录。
            val manifestWritten = writeMutex.withLock {
                runCatching { writeNow() }.fold(
                    onSuccess = {
                        _manifestWriteFailed.value = false
                        true
                    },
                    onFailure = { e ->
                        _manifestWriteFailed.value = true
                        // 全局日志：与 saveAsync 的失败同一口径（影响所有实例的索引，不是某个实例的运行问题）。
                        DshLogBus.append("[instances] failed to write instances.json before delete: ${e.message}")
                        false
                    }
                )
            }
            if (!manifestWritten) {
                // 回滚内存：实例回到列表（按原位），否则界面会显示"已删除"而磁盘上它还在。
                //
                // ★ R3b-B3-5（fix12 收尾）：回滚必须**幂等**。等待写盘的这段时间里
                //   `retryManifestLoad()` 可能已经把磁盘真相整份替换进来（`_instances.value =
                //   parsed.instances`），而磁盘上这条记录**还在**（本次写盘正是失败的）——
                //   无脑插回就会得到"同一 id 两份"，`_instances` 是 List 不是 Set，
                //   `byId`/`firstOrNull` 只取第一份，`updateConfig` 的 map 却会同时改两份。
                //   所以插回前先查：已经在列表里（说明重读已经把它放回来了）就不重复插。
                _instances.update { list ->
                    if (list.any { it.id == id }) list
                    else {
                        val back = mutableListOf<DshInstance>()
                        var inserted = false
                        val at = removedIndex.coerceIn(0, list.size)
                        list.forEachIndexed { i, it ->
                            if (!inserted && i == at) {
                                inst?.let { back += it }
                                inserted = true
                            }
                            back += it
                        }
                        if (!inserted && inst != null) back += inst
                        back
                    }
                }
                if (wasSelected) _selectedId.value = id
                _deleting.update { it - id }
                // 任务区不留终态行：这次删除**没有发生**（连目录都没动），留一条 DONE/FAILED
                // 反而像是在说"删除执行过了"，与实例仍在列表里的事实冲突；`_deleting` 移除后
                // 投影里那条"删除中"自然消失。
                onFinished?.invoke(false)
                return@launch
            }
            val ok = runCatching { DshPaths.instanceDir(id).deleteRecursively() }.getOrDefault(false)
            // ★ R3b-B3-6（fix12 收尾）：目录删不干净时留下墓碑。
            //   实例记录已经从清单里摘掉，但残留目录会让下次冷启的 adoptOrphanInstanceDirs
            //   **原样收养回来**（它只按"目录存在且不在清单里"判）——用户看到"删掉的实例又回来了"，
            //   而且是个空壳（state=NOT_INSTALLED）。这里记下 id，收养时跳过。
            //   只影响按目录扫描的收养路径：如果用户之后真的新建了一个同名 id（几乎不可能，
            //   id 带时间戳+序号+随机后缀），也不会被这条挡住（`create` 走的是 newId，不经过收养）。
            if (!ok) {
                tombstonedInstanceIds.update { it + id }
                DshLogBus.append(
                    "[instances] delete $id dir left files behind; marked as tombstoned so the " +
                        "orphan adoption will not resurrect it"
                )
            }
            // ★ 保持全局：同上，删除结果是"实例管理"这件事的结论，不是该实例运行期间的输出。
            //   实例页此刻正在被关掉，写实例流没有接收方（实例日志文件也不在实例目录里，
            //   见下方删除块 —— 旧注释"日志文件也已被删"的表述一并更正）。
            DshLogBus.append(
                "[instances] delete ${inst?.name ?: id} dir: ${if (ok) "done" else "leftover files"}"
            )
            // 实例已删：回收它的内存流（buffer / StateFlow / 文件句柄缓存）。
            // ⚠️ 残余窗口（BL-080 复核）：DshLogBus.appendFor 对未知 id 会**按需重建**内存流，
            //    删除收尾阶段唯一还可能迟到的写方是停止协程的 "[runtime] … 已停止" 收尾行
            //    （见 DshRuntime.stop 的 synchronized 块，Idle 落状态与 appendFor 只隔几微秒）。
            //    最坏后果：重建一条内存流（后续被 MAX_STREAMS=32 的 LRU 自然淘汰）+ 在极窄竞态下
            //    重写一份 ≤1MB 实例日志文件 —— 与 BL-076 原症状同级、有界，故接受；彻底闭环需要
            //    DshLogBus 侧加删除墓碑（不在本文件职责内，登记于报告）。
            DshLogBus.removeStream(id)
            // BL-080/BL-076：实例日志文件是 `<filesDir>/dsh/logs/instance-<id>.log`（由
            // DshPaths.LOG_FILE 的父目录推导，见 DshLogBus.instanceLogFile），**不在实例目录里**，
            // 上面 deleteRecursively() 带不走它 —— 全仓原先没有别的删除方（旧注释"它的目录与
            // 日志文件紧接着就要 deleteRecursively()"是错的：日志文件不在实例目录内），
            // 不删就每删一个实例留一份 ≤1MB 孤儿。放在 removeStream **之后**：removeStream 清掉
            // 内存流与文件句柄缓存后，之后即使再有迟到的 appendFor(id, …) 也只会重建内存流、落盘
            // 是惰性的（再凑满一次 flush 才会重写文件，删除收尾阶段没有这种写入方）；
            // 若反过来先删文件再 removeStream，还挂在 pendingForFile 里的行下一次 flush
            // 就会把文件原地写回来。delete() 返回 false（文件本就不存在）时不打日志，避免噪音。
            runCatching {
                if (DshLogBus.instanceLogFile(id).delete()) {
                    DshLogBus.append("[instances] removed instance log file for $id")
                }
            }.onFailure { e ->
                // 删不掉也只是残留一个日志文件，不该拖垮删除收尾 —— 但得留痕（否则是静默孤儿）。
                DshLogBus.append("[instances] failed to remove instance log file for $id: ${e.message}")
            }
            _deleting.update { it - id }
            // ★ 终态上报要在 `_deleting` 移除**之后**，但要注意它不能改写"删除是否成功"的判定 ——
            //   失败（有文件残留）在**任务语义**里算 DONE 还是 FAILED？这里选 DONE + 把残留写进 stage：
            //   "删除"这个动作本身确实执行完了（目录删到不能再删），失败的是文件系统层面的残留，
            //   实例记录已经从清单里摘掉、用户也不会再看到它 —— 把它标成 FAILED 反而像"删除没发生"。
            //   真正需要追残留的人看的是上面那行日志（全局流）与实例卡片。
            //   还有一处**必须**这么做：`_deleting` 已移除 → 投影（DshTasks.tasks）里那条"删除中"
            //   已经消失，若终态上报还标 CANCELLED/FAILED，界面会出现"删着删着突然说被取消"的怪状态。
            DshTasks.report(
                DshTask(
                    id = DshTasks.deleteTaskId(id),
                    kind = DshTask.Kind.DELETE,
                    title = inst?.name ?: id,
                    stage = if (ok) {
                        DshAppContextHolder.context?.getString(R.string.dsh_task_deleted) ?: "Deleted"
                    } else {
                        DshAppContextHolder.context?.getString(R.string.dsh_task_deleted_with_leftover)
                            ?: "Deleted (some files remain)"
                    },
                    action = DshTask.Action.NONE,
                    state = DshTask.State.DONE,
                    instanceId = id
                )
            )
            onFinished?.invoke(ok)
        }
        return true
    }

    /** 异步统计某实例占用体积（列表里显示，IO 线程） */
    fun diskUsageAsync(id: String, callback: (Long) -> Unit) {
        DshAppScope.scope.launch { callback(DshPaths.dirSize(DshPaths.instanceDir(id))) }
    }

    /**
     * ★ BL-151（R18）：等待循环的安装侧判据 ——"这个 pid 是否仍是本实例的安装 proot"。
     *
     * 判据逐条镜像 [DshInstaller] 的孤儿清理（cleanupStaleInstallProcess）与 [ProcessUtil.killStale]
     * 的身份核验：pid>0、进程存活、cmdline 同时含 `proot` 与本实例 id（防 pid 复用误认）。
     * 抽成纯函数是为了能在 JVM 单测里钉住判据本身（真实判活/读 /proc 需要设备）。
     */
    fun isLiveInstallProot(pid: Long, alive: Boolean, cmdline: String?, instanceId: String): Boolean =
        pid > 0 && alive && cmdline != null && cmdline.contains("proot") && cmdline.contains(instanceId)

    /**
     * 读本实例的 `dsh-install.pid`（BL-041 三闭环的同一文件），回答"安装 proot 是否还在跑"。
     * 文件缺失/读不出/pid 不合法 → false（没有等待对象）；路径系统未就绪（JVM 单测等）→ false。
     */
    private fun installProotStillAlive(instanceId: String): Boolean {
        if (!DshPaths.isLoaded) return false
        val f = File(DshPaths.instanceDir(instanceId), DshInstaller.INSTALL_PID_FILE_NAME)
        if (!f.isFile) return false
        val pid = runCatching {
            gson.fromJson(f.readText(), ProcessUtil.PidMeta::class.java)?.pid ?: 0L
        }.getOrDefault(0L)
        return isLiveInstallProot(pid, ProcessUtil.isAlive(pid), ProcessUtil.cmdline(pid), instanceId)
    }

    // --- 持久化（解析） -----------------------------------------------------

    /**
     * 清单解析结果。语义与 [loadFrom] 的三种出路一一对应：
     * - [Corrupt]：**整体**没法解析（不是合法 JSON / 根不是对象 / `instances` 不是数组）→
     *   调用方照旧另存 .corrupt + 清空；
     * - [Parsed]：解析完成。单个坏元素已被丢弃且计在 [Parsed.dropped]（逐条英文警告），
     *   `state` 缺失/null 的元素已被回填 [DshInstance.State.NOT_INSTALLED]。
     */
    sealed class ParsedManifest {
        /** 解析成功后剩下的实例（顺序保持） */
        data class Parsed(
            val instances: List<DshInstance>,
            val selectedId: String?,
            val dropped: Int
        ) : ParsedManifest()

        /** 整体损坏：交给 [loadFrom] 的 .corrupt 分支（[reason] 进全局日志，便于排障） */
        data class Corrupt(val reason: String) : ParsedManifest()
    }

    /** 取一个"字符串形态"的字段：缺失 / 显式 null / 类型不对都返回 null（调用方决定丢弃或回填） */
    private fun JsonObject.stringOrNull(key: String): String? {
        val e = get(key) ?: return null
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) return null
        return e.asString
    }

    /**
     * 把 instances.json 文本解析成 [ParsedManifest]（BL-098：字段级容错，**纯函数**、可单测）。
     *
     * 与旧实现（整份 `gson.fromJson` + 一个 try）的区别：
     * 1. **逐元素解析**：用 [JsonParser] 先读成 JSON 树，再对 `instances` 里**每个元素**单独
     *    `fromJson` 包 try。单个坏元素（非对象 / 缺 `id`、`name` 必填字段 / 未知 state 枚举名 /
     *    字段抛错）只丢弃它自己 + 一条英文警告，其余实例全部保留 —— 旧实现只要有一个元素抛异常，
     *    整份清单被当"损坏"清空、用户看到"实例全没了"。
     * 2. **state 回填**：Gson 反序列化缺字段 / 未知枚举名的 `var state` 时不会走 Kotlin 默认值，
     *    而是给 null。此前 null 会漏进列表，`when(state)` 在列表页直接崩（BL-098 的崩溃面）。
     *    这里把 null 的 state 显式回填成 NOT_INSTALLED —— 注意本轮实测（Gson 2.10.1，真类）：
     *    未知枚举名**不抛异常**、也是 null，所以"未知枚举名"与"缺字段"必须从**原始 JSON**
     *    上区分（前者丢弃、后者回填）。profile / model 同族防 null（同一条 Gson 语义）。
     * 3. **还是"整体损坏"的才走 Corrupt**：文本不是合法 JSON、根不是对象、`instances` 字段
     *    存在但不是数组 —— 这些是"整份文件坏了"，保留 .corrupt 备份语义不变。
     *
     * 空/纯空白文本约定由 [loadFrom] 处理（视作空清单，不进这里）；本函数对空文本防御性
     * 返回 Corrupt（不会经生产路径到达，纯函数边界自洽）。
     */
    fun parseManifest(json: String): ParsedManifest {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            return ParsedManifest.Corrupt("not valid JSON: ${e.message}")
        }
        // 非对象（字面量 "null" / 数组 / 数字…）：整份文件是坏的
        if (!root.isJsonObject) return ParsedManifest.Corrupt("root is not a JSON object")
        val obj = root.asJsonObject
        val arr = obj.get("instances")
        // 字段缺失 = 没有实例（与旧语义一致：m.instances ?: emptyList()）
        if (arr == null || arr.isJsonNull) {
            return ParsedManifest.Parsed(emptyList(), null, 0)
        }
        // 存在但不是数组：旧实现会在这抛 → 走 .corrupt；保持
        if (!arr.isJsonArray) return ParsedManifest.Corrupt("\"instances\" is not a JSON array")

        val kept = ArrayList<DshInstance>(arr.asJsonArray.size())
        var dropped = 0
        for (element in arr.asJsonArray) {
            if (!element.isJsonObject) {
                dropped++
                DshLogBus.append(
                    "[instances] dropped a manifest entry (not a JSON object): " +
                        element.toString().take(200)
                )
                continue
            }
            val eo = element.asJsonObject
            // 必填字段：id / name（无 Kotlin 默认值；缺了实例不可用）。从**原始 JSON** 上判，
            // 比"解析后再判 null"更准 —— Gson 对缺失的 String 字段一律给 null，不会抛。
            if (eo.stringOrNull("id") == null || eo.stringOrNull("name") == null) {
                dropped++
                DshLogBus.append(
                    "[instances] dropped a manifest entry (missing required id/name): " +
                        element.toString().take(200)
                )
                continue
            }
            // state 是字符串但不在枚举里 = 未知枚举名 → **降级回填 NOT_INSTALLED**，不丢弃（R14-V2A F2）。
            // ⚠️ 必须在 Gson 之前从**原始 JSON** 上判：实测（Gson 2.10.1，真类）未知枚举名
            //    不抛异常、直接给 null —— 只看解析结果无法区分"未知枚举名"与"缺字段"。
            // 为什么不丢：版本回退/手工编辑产生合法但未知的 state 时，丢弃 = 用户实例**无声
            //    永久消失**（此后任何一次落盘即固化），剩余目录成孤儿；而回填是自愈的——启动
            //    校验以磁盘 package.json 为准（repair/recheck 会把它纠正回真实状态）。缺 state
            //    字段本来就走同款回填（见下方字段级回填），两种"认不出"的策略应当一致。
            // 缺失 / 显式 null / 非字符串原语 → 不算未知，同样交给下面的回填。
            val rawState = eo.get("state")
            var unknownStateSeen: String? = null
            if (rawState != null && rawState.isJsonPrimitive && rawState.asJsonPrimitive.isString) {
                val stateName = rawState.asString
                if (DshInstance.State.entries.none { it.name == stateName }) {
                    unknownStateSeen = stateName
                    DshLogBus.append(
                        "[instances] manifest entry with unknown state '$stateName' kept as NOT_INSTALLED " +
                            "(will be re-checked against disk): " + element.toString().take(200)
                    )
                }
            }
            val inst = runCatching { gson.fromJson(element, DshInstance::class.java) }.getOrNull()
            if (inst == null) {
                dropped++
                DshLogBus.append(
                    "[instances] dropped a manifest entry (failed to deserialize): " +
                        element.toString().take(200)
                )
                continue
            }
            // 字段级回填：Gson unsafe 跳过 Kotlin 默认值，缺失/无法映射的非空字段是 null，
            // 不能让 null 漏进 StateFlow 列表（列表页 when(state) 必崩）。经局部可空变量读取：
            // 字段声明是非空类型，直接判空编译器会当成恒真。
            // 未知枚举名（R14-V2A F2）走同款降级：Gson 对未知枚举名给 null → 落回 NOT_INSTALLED，
            // 上方日志已记录原始 name 便于排障。
            val rawStateField: DshInstance.State? = inst.state
            inst.state = rawStateField ?: DshInstance.State.NOT_INSTALLED
            if (unknownStateSeen != null && inst.state != DshInstance.State.NOT_INSTALLED) {
                // 防御分支：极端 Gson 行为下未知枚举若映射成了别的值，也强制回填，保证策略一致
                inst.state = DshInstance.State.NOT_INSTALLED
            }
            val rawProfile: String? = inst.profile
            inst.profile = rawProfile ?: DshInstance.PROFILE_WEB
            val rawModel: String? = inst.model
            inst.model = rawModel ?: DshInstance.MODEL_FLASH
            kept += inst
        }

        // selectedId：类型不对/缺失按 null；仍只在"它确实在保留列表里"时生效（旧语义）。
        // 即使 selectedId 无效也不影响元素保留 —— 那是选中项校正，不是清单损坏。
        val selected = obj.stringOrNull("selectedId")
        return ParsedManifest.Parsed(
            instances = kept,
            selectedId = selected?.takeIf { sid -> kept.any { it.id == sid } },
            dropped = dropped
        )
    }

    // --- 持久化 ------------------------------------------------------------

    private fun manifestFile() = File(DshPaths.INSTANCES_MANIFEST)

    private data class Manifest(
        val instances: List<DshInstance>?,
        val selectedId: String?
    )

    private fun load() = loadFrom(manifestFile())

    /**
     * Load the manifest from [f]. The production entry point is the no-arg [load] (which uses
     * [manifestFile]); this file-parameterized form is the testable seam.
     *
     * K12: an **empty or whitespace-only** file is treated as an empty manifest, NOT corruption.
     * The old code fed "" straight to Gson, got back null, dereferenced it (NPE), and that NPE was
     * caught by the corrupt branch below — so a brand-new / half-written instances.json was
     * mis-flagged "corrupt", renamed to .corrupt-*, and the whole list was wiped. A *missing* file
     * was already treated as empty; an empty file must behave the same.
     *
     * BL-101b: every path that leaves the manifest **empty** (missing file / blank text / corrupt
     * wipe / explicit empty array) then scans the instances directory and adopts directories that
     * are not in the manifest as NOT_INSTALLED instances ([adoptOrphanInstanceDirs]) — otherwise
     * those directories would stay invisible and occupy disk forever.
     *
     * fix12（Round 25 §2.4）：顺序**必须是** 读 → 空判 → 解析，且每条"读到东西"的出口都要先留证：
     * - 读失败 ⇒ 置 [manifestLoadFailed]（此后 [saveAsync]/[delete] 一律拒写/拒绝），**不动内存**；
     * - 0 字节（[String.isEmpty]，**不是** `f.length() == 0L`）⇒ 备份 `.empty-<ts>` 再按空清单继续；
     *   ⚠️ 判据必须建立在"读成功之后"：`length()` 对同名目录 / stat 失败也返回 0，用它会把一次
     *   真读失败误判成"0 字节清单"，从而绕过上面的拒写、把原清单覆盖掉（E2 C-05 的要害）；
     * - 非 0 空白 ⇒ 与旧语义一致（不备份，防 K12 回归）；
     * - Corrupt ⇒ 备份 `.corrupt-<ts>`；**改名与复制都失败时拒写**（不覆盖原文，E2-N-4）；
     * - Parsed 但有元素被丢 ⇒ 追加 `.dropped-<ts>` 原始文本备份（下一次落盘就会把坏条目永久抹掉）；
     * - 每个"成功"出口统一清 [manifestLoadFailed] 与改动计数（清位只有这里与重启两条）。
     */
    fun loadFrom(f: File) {
        if (!f.exists()) {
            // 缺失文件 = 空清单（K12）。恢复扫描照走：目录存在但清单缺失时，残留实例目录同样会"永久隐形"。
            _instances.value = emptyList()
            _selectedId.value = null
            markManifestLoadSucceeded()
            adoptOrphanInstanceDirs()
            return
        }
        // BL-098: readText 必须包 runCatching —— 读失败（EIO 等）此前直穿 init() 一路抛进
        // FCLApp.onCreate，启动即崩。读不到内容不是"清单损坏"（文件可能好端端的，只是这次读不了），
        // 所以既不清空列表也不做 .corrupt 备份，只记一条全局英文警告。
        // ★ fix12：这一次失败要变成**拒绝写盘**的状态位 —— 否则后续任意一次 create/markState
        //   都会把"内存里的空表"写成磁盘事实，原清单里的实例永久消失（E2-F10 实测）。
        val text = runCatching { f.readText() }.getOrElse { e ->
            DshLogBus.append("[instances] failed to read instances.json: ${e.message}")
            _manifestLoadFailed.value = true
            return
        }
        if (text.isEmpty()) {
            // 真 0 字节（全新安装 / 首次写被打断）：先留证再按空清单继续。
            // 备份必须**在**后面的收编（adopt → saveAsync）之前完成 —— 那次写盘会覆盖原文，
            // 覆盖之后就没有"原来的名字/端口/版本"可挽回了（E2 C-05 §4.3）。
            // 顺序：renameTo（原子、不留副本）→ 失败则 copyTo（父目录只读时 rename 会失败，
            // 但目标可写时 copy 仍可能成功）。都失败 ⇒ 不能覆盖原文，直接拒写。
            val backup = backupFileFor(f, "empty")
            val backedUp = runCatching { f.renameTo(backup) }.getOrDefault(false) ||
                runCatching {
                    if (f.isFile) {
                        f.copyTo(backup, overwrite = true)
                        // copyTo 成功但原文件还在 ⇒ 删掉原名，否则后面的写盘要 rename 覆盖它
                        // （Android/Linux 上可以，但保留"原文已移走"的语义更清楚）。删不掉不算失败：
                        // 备份已到手，写盘时 rename 会覆盖。
                        runCatching { f.delete() }
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
            if (!backedUp) {
                // 一个字节都没动过：留着原文总比覆盖掉好（用户/开发者还能 adb pull 出来）。
                DshLogBus.append(
                    "[instances] instances.json is 0 bytes; could not back it up, left in place at " +
                        f.absolutePath + " (refusing to overwrite)"
                )
                _manifestLoadFailed.value = true
                return
            }
            DshLogBus.append("[instances] instances.json is 0 bytes; backed up to ${backup.absolutePath}")
            _instances.value = emptyList()
            _selectedId.value = null
            // ★ 清位必须在 adopt 之前：adopt 内部会 saveAsync，而它此刻看到的内存就是"空清单 +
            //   收编项"——这正是这次读成功得出的结论，写它是对的。顺序反了（先 adopt 再清位）
            //   会让那次写被"上一次"的拒写位拦住，收编结果落不到盘上。
            markManifestLoadSucceeded()
            adoptOrphanInstanceDirs()
            return
        }
        if (text.isBlank()) {
            // Empty / whitespace-only = empty manifest. Nothing to parse, nothing to back up.
            // ★ 非 0 空白**不备份**：它不是"被截断的写"，而是内容本身无意义（K12 语义不变）。
            _instances.value = emptyList()
            _selectedId.value = null
            markManifestLoadSucceeded()
            adoptOrphanInstanceDirs()
            return
        }
        when (val parsed = parseManifest(text)) {
            is ParsedManifest.Corrupt -> {
                // 整体损坏（不是合法 JSON / 根不是对象 / instances 不是数组）才走这里：
                // 另存一份便于人工找回，并把清单重置为空。
                // 单个元素的坏字段不会到达这里 —— parseManifest 已逐元素容错（BL-098）。
                // ★ 保持全局：清单（instances.json）是**所有**实例的索引，不是任何一个实例的日志。
                //   而且此刻 _instances 已被清空，"这条属于哪个实例"根本无从判断（也不该猜）。
                // BL-101a：日志必须带**完整路径**（旧实现只写 "kept a backup" 连文件名都没有，
                // 无 adb 的用户根本无从把它抄出来）；[listCorruptBackups] 是恢复 UI 的代码侧入口。
                // ★ fix12（E2-N-4）：改名失败要**再试一次复制**；两者都失败 ⇒ 拒写。
                //   旧实现"left in place"之后同一调用栈里就会 adopt → saveAsync 把坏文件覆盖掉，
                //   那句"留证"实际上一秒都留不住。
                val backup = backupFileFor(f, "corrupt")
                val moved = runCatching { f.renameTo(backup) }.getOrDefault(false)
                val copied = moved || runCatching {
                    if (f.isFile) {
                        f.copyTo(backup, overwrite = true)
                        runCatching { f.delete() }
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
                if (!copied) {
                    DshLogBus.append(
                        "[instances] failed to parse instances.json (${parsed.reason}); " +
                            "could not move or copy it to a backup, left in place at ${f.absolutePath} " +
                            "(refusing to overwrite)"
                    )
                    _manifestLoadFailed.value = true
                    return
                }
                DshLogBus.append(
                    "[instances] failed to parse instances.json (${parsed.reason}); " +
                        "kept a backup at ${backup.absolutePath}"
                )
                _instances.value = emptyList()
                _selectedId.value = null
                markManifestLoadSucceeded()
                adoptOrphanInstanceDirs()
            }

            is ParsedManifest.Parsed -> {
                // ★ fix12（E2-N-3）：元素级丢弃（K-07）时，磁盘上那份"坏条目还在"的原文必须留一份 ——
                //   否则用户下一次任意操作（改名 / 装完 / 删除）就会用"已丢元素"的内存整份覆盖磁盘，
                //   坏条目永久消失且无备份可查。备份失败只记日志，**不**因此拒写（内容本身是能用的）。
                if (parsed.dropped > 0) {
                    backupDroppedManifest(f, parsed.dropped)
                }
                val replaced = mutationsWhileLoadFailed == 0
                if (replaced) {
                    // 只有"本次会话在读失败期间没有改动"时才允许整份替换内存 —— 否则会把用户
                    // 在拒写窗口里新建/改名/删除的实例**无声冲掉**（它们本来就没落盘，冲掉即丢）。
                    _instances.value = parsed.instances
                } else {
                    DshLogBus.append(
                        "[instances] manifest is readable again, but this session has " +
                            "$mutationsWhileLoadFailed unsaved change(s); keeping the in-memory list"
                    )
                }
                // E2-N-2：**无论清单空不空**都跑一次收编。旧实现只在"清单为空"时跑，于是
                // "读失败窗口里 create 出来、但没落盘"的实例目录既不在清单、也不会被收编 ——
                // 永久占盘且永不显示（E2-N-2 实测）。
                // ★ 清位在 adopt **之前**：adopt 内部会 saveAsync，而它看到的内存（磁盘解析结果 +
                //   收编项）正是这次读成功得出的结论，写它是对的；顺序反了会被上一次的拒写位拦住。
                // ★ 只有"内存已被磁盘真相替换"时才清位（§2.3 清位规则 A：读成功**且**无改动）。
                //   保留内存的分支不清位 —— 否则随后的任意一次 saveAsync 会拿这份"混着未保存改动"
                //   的内存去覆盖刚刚读通的清单，等于把 §2.4 的整句"保留内存现状"变成空话。
                if (replaced) markManifestLoadSucceeded()
                adoptOrphanInstanceDirs()
                if (replaced) {
                    // 选中项校正语义与旧实现一致：清单里的 selectedId 有效则用它，否则回落第一个。
                    // （收编可能往列表尾部追加了实例，所以这一步放在 adopt 之后取值。）
                    // ★ 内存被保护（replaced == false）时**不动** selectedId：那是用户在失败窗口里
                    //   自己选的，覆盖它同样是"无声冲掉一次改动"。
                    val current = _instances.value
                    _selectedId.value = parsed.selectedId?.takeIf { sid -> current.any { it.id == sid } }
                        ?: current.firstOrNull()?.id
                }
            }
        }
    }

    /** 所有"读成功"出口的统一收尾：清拒写位 + 清改动计数（E2 §3.3 清位规则 A）。 */
    private fun markManifestLoadSucceeded() {
        _manifestLoadFailed.value = false
        mutationsWhileLoadFailed = 0
    }

    /**
     * Parsed 但 [ParsedManifest.Parsed.dropped] > 0：把**原始文本**另存一份 `.dropped-<ts>`。
     *
     * 为什么要副本而不是"重写一份干净的"：目标是"别丢证据"，不是"帮用户修文件"。原文里那条
     * 坏条目的字段恰恰是排障要看的（是 port 类型错了还是 state 拼错了），重写会把它们洗掉。
     * 失败只记日志：清单本身可用，不该因为备份失败就让整个启动路径变成拒写（与 `.empty-` /
     * `.corrupt-` 不同 —— 那两处的原文**紧接着就要被覆盖**，这里不会）。
     */
    /**
     * 备份文件名：`<manifest>.<kind>-<毫秒>`，**并保证不与已有文件重名**。
     *
     * ★ R3b-B2（fix12 收尾）：原来直接用 `System.currentTimeMillis()` 拼名，而三处备份全都
     *   `copyTo(..., overwrite = true)`（`renameTo` 在 Windows 上已存在即失败、在 Linux 上是
     *   原子覆盖）—— 同一毫秒内跑两次 `loadFrom`（`loadFrom` 没有串行化，`init()` 与
     *   `retryManifestLoad()` 可以撞上）就会**静默覆盖掉前一份证据**，日志里还写着两条
     *   "kept a backup at <同一路径>"，看起来像留了两份。
     *   这条分支存在的唯一目的就是留证，所以名字必须唯一：撞了就在后面追加 `-2`、`-3`…
     *   （最多试 100 次，仍撞则退回原名字——此时宁可覆盖，也不要让备份动作本身失败。）
     */
    private fun backupFileFor(manifest: File, kind: String): File {
        val parent = manifest.parentFile ?: return File(manifest.absolutePath + ".$kind")
        val stamp = System.currentTimeMillis()
        val first = File(parent, "${manifest.name}.$kind-$stamp")
        if (!first.exists()) return first
        for (n in 2..100) {
            val candidate = File(parent, "${manifest.name}.$kind-$stamp-$n")
            if (!candidate.exists()) return candidate
        }
        return first
    }

    private fun backupDroppedManifest(f: File, dropped: Int) {
        val backup = backupFileFor(f, "dropped")
                runCatching { f.copyTo(backup, overwrite = true) }
                    .onSuccess {
                        // ⚠️ 只复制、**不删原名**：这份清单是"能用但少了几个元素"的，它必须留在
                        // 原位继续当当前清单（下一次写盘会把它换成干净版本，那是预期行为）。
                        DshLogBus.append(
                            "[instances] manifest had $dropped dropped entr(y/ies); " +
                                "kept the original text at ${backup.absolutePath}"
                        )
                    }
                    .onFailure { e ->
                        DshLogBus.append("[instances] failed to back up the original manifest text: ${e.message}")
                    }
    }

    /**
     * BL-101b：清单为空（缺失 / 空白 / 整体损坏 / 显式空数组）时，把 instances 目录里
     * "存在但不在清单"的目录收编为实例（NOT_INSTALLED，交给随后的异步 [repair] 按磁盘事实校准）。
     *
     * 为什么整个收编、为什么收编而不是删除：
     * - 判据与 [DshRuntime] 的 removePidFileByPid 同款 —— `INSTANCES_DIR` 下每个子目录就是
     *   "一个实例的现场"（"目录即事实"的既有口径）；
     * - 目录里可能有用户的 workspace / home（可能几百 MB 的用户数据），自动删是不可逆的破坏；
     *   收编是自愈的：用户看得见它、可重命名、也可再删除。
     * 状态取 NOT_INSTALLED 与 [repair] 的兜底口径一致：本函数只做浅扫描（读目录项，与 loadFrom
     * 本来就要读清单文件同量级、且只在"清单为空"的少见路径发生），**不**深入读各实例的
     * package.json —— 真实状态（装了没有 / 版本）由随后的异步 [repair] 在 IO 线程上校准。
     *
     * 只由 [loadFrom] 在"清单最终为空"的分支调用；判据内核 [rebuildMissingInstances] 是
     * public 纯函数（可单测）。
     */
    private fun adoptOrphanInstanceDirs() {
        if (!DshPaths.isLoaded) return // 路径系统未就绪（如 JVM 单测）：没有可扫的目录
        // ★ R3b-B3-6：跳过墓碑 —— 那些是"删除已生效但目录没删干净"的残留，收养它等于复活。
        val known = _instances.value.mapTo(HashSet()) { it.id }
        known += tombstonedInstanceIds.value
        val recovered = rebuildMissingInstances(File(DshPaths.INSTANCES_DIR), known)
        if (recovered.isEmpty()) return
        _instances.update { it + recovered }
        if (_selectedId.value == null) _selectedId.value = recovered.first().id
        // ★ 这行 saveAsync 只在"读成功"的出口被执行（loadFrom 的每个出口都先清拒写位再调本函数），
        //   所以它不会被自己刚恢复出来的"拒写"拦住 —— 顺序见 loadFrom 各出口的注释。
        saveAsync()
        // R-LOG（英文）：恢复了几条 + 完整名单，用户/排障者可据此辨认这些实例的来历。
        DshLogBus.append(
            "[instances] adopted ${recovered.size} orphan instance dir(s) missing from the manifest " +
                "(rebuilt as NOT_INSTALLED): ${recovered.joinToString(", ") { it.id }}"
        )
    }

    /**
     * 恢复扫描的判据内核（public 纯函数，便于单测；BL-101b）：
     * 列出 [instancesRoot] 下**是目录**且 id 不在 [knownIds] 里的子目录，映射为 NOT_INSTALLED 实例。
     *
     * - 目录名即实例 id，也作初始 name —— 清单丢失时没有任何可还原的用户命名，用 id 最诚实
     *   （用户可在界面重命名；恢复名不引入新用户文案，故无需 String 资源）；
     * - 按 id 排序，保证多次扫描结果稳定（listFiles 顺序不保证）；
     * - 只读目录项、不深入内容（状态校准交 [repair]）；非目录项（散落文件）忽略；
     * - [instancesRoot] 不存在 / 不是目录 → 空表（幂等、无异常）。
     */
    fun rebuildMissingInstances(instancesRoot: File, knownIds: Set<String>): List<DshInstance> {
        if (!instancesRoot.isDirectory) return emptyList()
        val dirs = instancesRoot.listFiles() ?: return emptyList()
        return dirs
            .filter { it.isDirectory && it.name !in knownIds }
            .map { DshInstance(id = it.name, name = it.name) }
            .sortedBy { it.id }
    }

    /**
     * BL-101a：instances.json 的备份清单（`instances.json.<kind>-<毫秒>`，与 manifest 同目录）。
     * **public，供未来"从备份恢复"的 UI 使用**（本轮只做代码侧就绪，UI 入口登记为后续任务）；
     * 无备份 / 路径系统未就绪时返回空表。
     *
     * fix12（Round 25 §2.4）：前缀集合扩为 `.corrupt-` / `.empty-` / `.dropped-` —— 三种证据都来自
     * 同一条"先留证再继续"的策略，只列 `.corrupt-` 等于把另外两种藏起来（无 adb 的用户根本抄不到）。
     * 排序改为按**后缀时间戳数值**倒序：三种前缀的字典序互不相交（`c` < `d` < `e`），
     * 整名排序会让"所有 .corrupt 永远排在所有 .empty 之前"，混排后"最新在前"就不再成立（E2-N-7）。
     */
    fun listCorruptBackups(): List<File> =
        if (DshPaths.isLoaded) listCorruptBackups(manifestFile()) else emptyList()

    /**
     * [listCorruptBackups] 的可测种子：给定 manifest 文件，列出**同一父目录**下的全部
     * `${manifest.name}.{corrupt|empty|dropped}-*` **文件**（不是目录），最新在前；
     * 无父目录 / 无备份返回空表。
     *
     * 时间戳解析不出来（手工改过名）的条目排在最后，且彼此之间保持文件名倒序（稳定、可预期）。
     */
    fun listCorruptBackups(manifest: File): List<File> {
        val dir = manifest.parentFile ?: return emptyList()
        val prefixes = BACKUP_PREFIXES.map { "${manifest.name}$it" }
        return dir.listFiles { f -> f.isFile && prefixes.any { f.name.startsWith(it) } }
            ?.sortedWith(
                compareByDescending<File> { backupTimestampOf(it.name) ?: Long.MIN_VALUE }
                    .thenByDescending { it.name }
            )
            ?: emptyList()
    }

    /** 备份后缀的时间戳（`.corrupt-<ms>` / `.empty-<ms>` / `.dropped-<ms>`）；认不出返回 null */
    /**
     * 从备份文件名里取出毫秒时间戳，供"最新在前"排序。
     *
     * ★ R3b-B2：备份名现在可能带唯一化后缀（`….corrupt-1700000000000-2`），
     *   原来 `substringAfterLast('-')` 会把那个 `2` 当成时间戳（于是那份备份永远排在最后）。
     *   改为取第一个形如 `<kind>-<数字段>` 的值。`\d{4,}` 的下界要够低以容测试里的小时间戳，
     *   又要够高以排除唯一化后缀（那个序号最多 3 位，因为 `backupFileFor` 只试到 100）。
     */
    private fun backupTimestampOf(name: String): Long? =
        Regex("""(?:corrupt|empty|dropped)-([0-9]{4,})""").find(name)
            ?.groupValues?.get(1)?.toLongOrNull()

    /**
     * 异步 + 串行 + 原子写盘。UI 线程只更新内存状态，不做磁盘 IO。
     *
     * fix12（Round 25 §2.3 规则 2）：读失败期间**同步拒绝**。
     * - 入口就判一次：调用方立刻知道"这次没写"，可以做 UI 提示（[Boolean] 返回值就是为这个）；
     * - 协程里 `withLock` **之前再判一次**：置位之前已经排队的任务（例如启动瞬间连发的几次
     *   saveAsync）在拿到锁时会被拦下 —— 只判入口会漏掉它们。
     * - 写成功永不清 [manifestLoadFailed]（否则就是"读到的还是空表、但写成功了所以认为好了"
     *   的自证清白）；写失败/成功只动 [manifestWriteFailed]。
     *
     * @return false = 同步拒绝（清单读取失败中，什么都没写）；true = 已排队
     */
    fun saveAsync(): Boolean {
        if (_manifestLoadFailed.value) {
            DshLogBus.append("[instances] save refused: manifest load failed (not overwriting a manifest we could not read)")
            return false
        }
        DshAppScope.scope.launch {
            // 拿到锁**之前**再判一次：入口那次判不到的，是"置位前已经排队"的任务 ——
            // 它们在队列里等锁的时候位被置上了，等它们真正开跑时这里就会拦下。
            if (_manifestLoadFailed.value) {
                DshLogBus.append("[instances] save dropped: manifest load failed before write")
                return@launch
            }
            writeMutex.withLock {
                if (_manifestLoadFailed.value) {
                    // 排队等锁期间又被置位（读到一半失败了）：这次写同样不能落盘。
                    DshLogBus.append("[instances] save dropped: manifest load failed before write")
                    return@withLock
                }
                runCatching { writeNow() }.fold(
                    onSuccess = { _manifestWriteFailed.value = false },
                    onFailure = {
                        _manifestWriteFailed.value = true
                        // ★ 保持全局：写清单失败影响**所有**实例（下次启动整份清单可能读不出来），
                        //   不是某个实例的运行问题 —— 归到任一实例都会让别处看不到这个 App 级故障。
                        DshLogBus.append("[instances] failed to write instances.json: ${it.message}")
                    }
                )
            }
        }
        return true
    }

    /**
     * 手动重试读取（UI 横幅的「重试」按钮）。
     *
     * 语义（Round 25 §2.3 规则 4）：读成功 **且** 本次会话没有未保存改动 ⇒ 走 [loadFrom] 的成功路径
     * （内部清位）+ 补跑一次 [repair]；否则返回 false 并留一条日志。
     *
     * ★ 为什么要补跑 repair：`init()` 的 repair 是**一次性**的，如果它在读失败时就跑过了（按空表
     *   跑，等于空转），重试成功后磁盘上的 INSTALLING 僵尸态 / "清单说 READY 但树已空"就不会再被
     *   校准，只能等下次冷启（E2 §6）。所以"清位之后必须立刻补跑一次"。
     * ★ 末段的判定不看 [loadFrom] 的"内存是否被替换"：重试的前提是没有未保存改动，而改动计数在
     *   这里必然为 0，所以 `loadFrom` 一定会整份替换内存并清位。
     *
     * @return true = 已恢复（可写）；false = 仍不可用（读取仍失败 / 存在未保存改动 / 路径未就绪）
     */
    fun retryManifestLoad(): Boolean {
        if (!DshPaths.isLoaded) return false
        val f = manifestFile()
        // 先做一次"能不能读"的探测：读不到就保持拒写，别白跑 loadFrom（它也是只读不写，但
        // 会在日志里再刷一遍失败行，横幅点几次就刷几遍）。
        val readable = runCatching { f.readText() }.isSuccess
        if (!readable) {
            // 探测都失败：位保持（loadFrom 里的置位路径这次没走到，这里自己兜住），不发多余日志。
            _manifestLoadFailed.value = true
            return false
        }
        if (mutationsWhileLoadFailed > 0) {
            // ★ 覆盖内存会把这些改动无声冲掉，而它们本来就没落盘 —— 所以这里**不吞掉**这个位，
            //   而是明确保留拒写：让用户看见横幅（"本次会话的改动不会保存"）比假装恢复更诚实。
            //   （顺带纠正 §2.3 规则 4 的括号措辞：这是"保留拒写 + 日志"，不是"重新置位"。）
            DshLogBus.append(
                "[instances] manifest is readable, but this session has " +
                    "$mutationsWhileLoadFailed unsaved change(s); keeping the in-memory list " +
                    "and write protection"
            )
            return false
        }
        loadFrom(f)
        if (_manifestLoadFailed.value) return false
        // 读成功了：补跑一次状态校准（理由见 KDoc）。
        repair()
        return true
    }

    /** 同步写（测试用；生产路径请用 [saveAsync]） */
    internal fun writeNow() {
        writeManifest(manifestFile(), gson.toJson(Manifest(_instances.value, _selectedId.value)))
    }

    /**
     * Atomically replace [f] with [json]: write a sibling `.tmp` first, then rename it over [f]
     * (an atomic replace on the Android/Linux filesystem the app runs on). Testable seam.
     *
     * K12: if the rename fails we **must not** fall back to a direct in-place overwrite of [f] (the
     * old behavior). A crash midway through a non-atomic overwrite leaves instances.json
     * half-written and unreadable — losing every instance record — which is exactly what this
     * tmp+rename dance exists to prevent, and it would strike precisely when the system is already
     * under stress (why the rename failed). Instead we keep the `.tmp` file (it holds the complete,
     * valid new manifest, recoverable by hand) and throw so the caller surfaces the failure
     * ([saveAsync] logs it in English).
     */
    fun writeManifest(f: File, json: String) {
        val tmp = File(f.parentFile, "${f.name}.tmp")
        // ★ fix12（C-06 落点 1）：必须用 FileOutputStream + fd.sync() 而不是 writeText ——
        //   writeText 只 close 不 fsync，掉电时"rename 成功但内容还是空的/旧的"就是这种漏法。
        FileOutputStream(tmp).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            throw IOException(
                "atomic rename failed: ${tmp.name} -> ${f.name}; kept ${tmp.name} for recovery " +
                    "(refusing to overwrite ${f.name} non-atomically)"
            )
        }
        // ★ fix12（C-06 落点 2）：rename 之后把父目录的目录项刷一遍 —— 否则断电时"rename 丢了 =
        //   旧文件还在、新文件不见了"。这一步 **best-effort**：数据此刻已经完整地落在目标路径上，
        //   把目录 fsync 失败当写失败会触发无谓的重试/报错（E5-04），所以只记日志。
        val fsync: (File) -> Unit = dirFsync ?: ::platformFsyncDir
        val parent = f.parentFile
        if (parent != null) {
            runCatching { fsync(parent) }
                .onFailure { DshLogBus.append("[instances] dir fsync failed: ${it.message}") }
        }
    }
}
