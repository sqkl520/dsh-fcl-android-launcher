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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

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

    private val _instances = MutableStateFlow<List<DshInstance>>(emptyList())
    val instances: StateFlow<List<DshInstance>> = _instances.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** 正在删除中的实例 id（UI 显示"删除中"并禁用交互） */
    private val _deleting = MutableStateFlow<Set<String>>(emptySet())
    val deleting: StateFlow<Set<String>> = _deleting.asStateFlow()

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
    }

    /** 测试/重置用 */
    @Synchronized
    internal fun resetForTest() {
        initialized = false
        _instances.value = emptyList()
        _selectedId.value = null
    }

    val selected: DshInstance?
        get() = _instances.value.firstOrNull { it.id == _selectedId.value }

    fun byId(id: String): DshInstance? = _instances.value.firstOrNull { it.id == id }

    fun select(id: String) {
        if (_instances.value.any { it.id == id }) {
            _selectedId.value = id
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
        saveAsync()
    }

    /**
     * 依据磁盘真实内容校准所有实例状态（IO 线程调用）。
     * - 磁盘上有 package.json 且能解析出版本 → READY（并把版本号纠正为真实值）
     * - 否则 → NOT_INSTALLED；但 **BROKEN 保持 BROKEN**（保住失败原因，重启后仍可见）
     * - 只清掉 INSTALLING 这种"卡死"的瞬态
     */
    fun repair() {
        // 0) 顺带清掉历史遗留的凭据文件（见该函数的注释）。
        //    放在 repair 里是因为这里本来就在 IO 线程上、本来就要遍历全部实例 ——
        //    这是"就地升级上来的旧安装"唯一会被统一扫一遍的时机。
        cleanupLegacyCredentialFiles()
        // ★ 先在 `_instances.update {}` 之外算好"磁盘事实"：update 的 lambda 在并发修改时会
        // 被重试，把文件 IO 放在里面（旧实现）会有两个坏处：① 同样的 readText 被重复执行；
        // ② 在原子更新的重试循环里持有 IO 时间。现在只把结果带进去做纯内存替换。
        val planned = HashMap<String, Pair<String?, DshInstance.State>>()
        // 预装 dsh 的版本（只读一次，供下面判断"能不能把预装当成这个实例的 dsh"）
        val preinstalledVersion = DshInstaller.readVersionFromPackageJson(
            File(File(DshPaths.ROOTFS_DIR, DshPaths.PREINSTALLED_DSH_REL), "package.json")
        )
        _instances.value.forEach { inst ->
            // ★ 判定"这个实例装好了没"必须区分两件事：
            //   ① 实例**自己**的 node_modules（真实安装结果）
            //   ② rootfs 里的**预装** dsh（共享底座）
            //   原来直接用 effectiveDshPackageJson（会回退到预装），于是"请求 0.2.1、安装失败"
            //   的实例会因为预装有 0.1.6 而被判成 READY —— 卡片出现"标题 0.2.1 / 副标题 0.1.6 /
            //   就绪 / 239 B"这种自相矛盾的状态。
            val ownVersion = DshInstaller.readVersionFromPackageJson(
                File(File(DshPaths.instanceDir(inst.id), "node_modules/@deepseek-ai/dsh"), "package.json")
            )
            // 只有当实例没有自己的包、且**预装版本恰好就是它请求的版本**时，预装才算它的 dsh
            val effectiveVersion = ownVersion
                ?: preinstalledVersion?.takeIf { inst.dshVersion != null && it == inst.dshVersion }
            val state = when {
                effectiveVersion != null -> DshInstance.State.READY
                // 没装好：把"安装中"这种卡死状态降级。
                // 注意：**BROKEN 要保持 BROKEN**——否则 App 一重启，失败原因虽然还在 lastError 里，
                // 但状态变成 NOT_INSTALLED 之后界面就不再展示它了（等于诊断信息凭空消失）。
                inst.state == DshInstance.State.BROKEN -> DshInstance.State.BROKEN
                else -> DshInstance.State.NOT_INSTALLED
            }
            planned[inst.id] = effectiveVersion to state
        }

        var changed = false
        _instances.update { list ->
            list.map { inst ->
                val p = planned[inst.id] ?: return@map inst // 期间新建出来的实例：不动它
                val (effectiveVersion, state) = p
                if (inst.state == state && (effectiveVersion == null || inst.dshVersion == effectiveVersion)) {
                    inst
                } else {
                    changed = true
                    inst.copy(
                        state = state,
                        // 装好了 → 用磁盘真实版本；没装好 → 保留"目标版本"（安装开始时写入），
                        // 这样卡片能显示"本来要装什么"，也才能被"同版本重试"复用
                        dshVersion = effectiveVersion ?: inst.dshVersion,
                        lastError = if (state == DshInstance.State.READY) null else inst.lastError,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
        }
        if (changed) saveAsync()
    }

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
     * @param onFinished 目录删完后回调（主线程/IO 线程不定，调用方自行切线程）
     */
    fun delete(id: String, onFinished: ((Boolean) -> Unit)? = null) {
        val inst = byId(id)
        _instances.update { list -> list.filterNot { it.id == id } }
        if (_selectedId.value == id) _selectedId.value = _instances.value.firstOrNull()?.id
        _deleting.update { it + id }
        saveAsync()
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
            val ok = runCatching { DshPaths.instanceDir(id).deleteRecursively() }.getOrDefault(false)
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
     */
    fun loadFrom(f: File) {
        if (!f.exists()) {
            // 缺失文件 = 空清单（K12）。恢复扫描照走：目录存在但清单缺失时，残留实例目录同样会"永久隐形"。
            _instances.value = emptyList()
            _selectedId.value = null
            adoptOrphanInstanceDirs()
            return
        }
        // BL-098: readText 必须包 runCatching —— 读失败（EIO 等）此前直穿 init() 一路抛进
        // FCLApp.onCreate，启动即崩。读不到内容不是"清单损坏"（文件可能好端端的，只是这次读不了），
        // 所以既不清空列表也不做 .corrupt 备份，只记一条全局英文警告。
        val text = runCatching { f.readText() }.getOrElse { e ->
            DshLogBus.append("[instances] failed to read instances.json: ${e.message}")
            return
        }
        if (text.isBlank()) {
            // Empty / whitespace-only = empty manifest. Nothing to parse, nothing to back up.
            _instances.value = emptyList()
            _selectedId.value = null
            adoptOrphanInstanceDirs()
            return
        }
        when (val parsed = parseManifest(text)) {
            is ParsedManifest.Corrupt -> {
                // 整体损坏（不是合法 JSON / 根不是对象 / instances 不是数组）才走这里：
                // 另存一份便于人工找回，并把清单重置为空。
                // 单个元素的坏字段不会到达这里 —— parseManifest 已逐元素容错（BL-098）。
                // ★ 保持全局：清单（instances.json）是**所有实例**的索引，不是任何一个实例的日志。
                //   而且此刻 _instances 已被清空，"这条属于哪个实例"根本无从判断（也不该猜）。
                // BL-101a：日志必须带**完整路径**（旧实现只写 "kept a backup" 连文件名都没有，
                // 无 adb 的用户根本无从把它抄出来）；[listCorruptBackups] 是恢复 UI 的代码侧入口。
                val backup = File(f.parentFile, "${f.name}.corrupt-${System.currentTimeMillis()}")
                val backedUp = runCatching { f.renameTo(backup) }.getOrDefault(false)
                if (backedUp) {
                    DshLogBus.append(
                        "[instances] failed to parse instances.json (${parsed.reason}); " +
                            "kept a backup at ${backup.absolutePath}"
                    )
                } else {
                    // 改名失败（少见）：原文件留在原地（后续写盘会覆盖它），但要留痕，别静默。
                    DshLogBus.append(
                        "[instances] failed to parse instances.json (${parsed.reason}); " +
                            "could not move it to a backup, left in place at ${f.absolutePath}"
                    )
                }
                _instances.value = emptyList()
                _selectedId.value = null
                adoptOrphanInstanceDirs()
            }

            is ParsedManifest.Parsed -> {
                _instances.value = parsed.instances
                if (parsed.instances.isEmpty()) {
                    // 显式空数组：同款恢复扫描（清单说"没有实例"、磁盘上却有目录 = 同一种隐形）。
                    _selectedId.value = null
                    adoptOrphanInstanceDirs()
                } else {
                    // 选中项校正语义与旧实现一致：清单里的 selectedId 有效则用它，否则回落第一个
                    _selectedId.value = parsed.selectedId ?: parsed.instances.firstOrNull()?.id
                }
            }
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
        val recovered = rebuildMissingInstances(
            File(DshPaths.INSTANCES_DIR),
            _instances.value.mapTo(HashSet()) { it.id }
        )
        if (recovered.isEmpty()) return
        _instances.update { it + recovered }
        if (_selectedId.value == null) _selectedId.value = recovered.first().id
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
     * BL-101a：instances.json 的损坏备份清单（`instances.json.corrupt-<毫秒>`，与 manifest 同目录）。
     * **public，供未来"从备份恢复"的 UI 使用**（本轮只做代码侧就绪，UI 入口登记为后续任务）；
     * 无备份 / 路径系统未就绪时返回空表。
     * 排序：按文件名倒序 —— 后缀是毫秒时间戳（等宽十进制），字典序即时间序，最新在前。
     */
    fun listCorruptBackups(): List<File> =
        if (DshPaths.isLoaded) listCorruptBackups(manifestFile()) else emptyList()

    /**
     * [listCorruptBackups] 的可测种子：给定 manifest 文件，列出**同一父目录**下的全部
     * `${manifest.name}.corrupt-*` **文件**（不是目录），最新在前；无父目录 / 无备份返回空表。
     */
    fun listCorruptBackups(manifest: File): List<File> {
        val dir = manifest.parentFile ?: return emptyList()
        val prefix = "${manifest.name}.corrupt-"
        return dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * 异步 + 串行 + 原子写盘。UI 线程只更新内存状态，不做磁盘 IO。
     */
    fun saveAsync() {
        DshAppScope.scope.launch {
            writeMutex.withLock {
                runCatching { writeNow() }.onFailure {
                    // ★ 保持全局：写清单失败影响**所有**实例（下次启动整份清单可能读不出来），
                    //   不是某个实例的运行问题 —— 归到任一实例都会让别处看不到这个 App 级故障。
                    DshLogBus.append("[instances] failed to write instances.json: ${it.message}")
                }
            }
        }
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
        tmp.writeText(json)
        if (!tmp.renameTo(f)) {
            throw IOException(
                "atomic rename failed: ${tmp.name} -> ${f.name}; kept ${tmp.name} for recovery " +
                    "(refusing to overwrite ${f.name} non-atomically)"
            )
        }
    }
}
