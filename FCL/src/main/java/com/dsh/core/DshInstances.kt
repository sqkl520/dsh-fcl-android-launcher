package com.dsh.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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
 *    `.corrupt-<时间>`，并往日志总线写一行，便于事后找回。
 * 5. 新增 [rename]/[updateConfig]/[recheck]，配合新的设置页与"修复"入口。
 */
object DshInstances {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val writeMutex = Mutex()
    private val seq = AtomicInteger(0)

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
        val id = newId()
        DshPaths.instanceDir(id).mkdirs()
        DshPaths.instanceHome(id).mkdirs()
        DshPaths.instanceWorkspace(id).mkdirs()
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

    /** 用不可重复的 id：毫秒 + 进程序列号 + 随机后缀 */
    private fun newId(): String {
        while (true) {
            val id = "inst-${System.currentTimeMillis()}-${seq.incrementAndGet()}-" +
                Integer.toHexString((Math.random() * 0xffff).toInt())
            if (_instances.value.none { it.id == id } && !DshPaths.instanceDir(id).exists()) return id
        }
    }

    /** 更新某实例（传入已修改的副本），并持久化 */
    fun update(instance: DshInstance) {
        instance.updatedAt = System.currentTimeMillis()
        _instances.update { list -> list.map { if (it.id == instance.id) instance else it } }
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
        // ★ 先在 `_instances.update {}` 之外算好"磁盘事实"：update 的 lambda 在并发修改时会
        // 被重试，把文件 IO 放在里面（旧实现）会有两个坏处：① 同样的 readText 被重复执行；
        // ② 在原子更新的重试循环里持有 IO 时间。现在只把结果带进去做纯内存替换。
        val planned = HashMap<String, Pair<String?, DshInstance.State>>()
        _instances.value.forEach { inst ->
            val diskVersion = DshInstaller.readVersionFromPackageJson(DshPaths.instanceDshPackageJson(inst.id))
            val state = when {
                diskVersion != null -> DshInstance.State.READY
                // 没装好：把"安装中"这种卡死状态降级。
                // 注意：**BROKEN 要保持 BROKEN**——否则 App 一重启，失败原因虽然还在 lastError 里，
                // 但状态变成 NOT_INSTALLED 之后界面就不再展示它了（等于诊断信息凭空消失）。
                inst.state == DshInstance.State.BROKEN -> DshInstance.State.BROKEN
                else -> DshInstance.State.NOT_INSTALLED
            }
            planned[inst.id] = diskVersion to state
        }

        var changed = false
        _instances.update { list ->
            list.map { inst ->
                val p = planned[inst.id] ?: return@map inst // 期间新建出来的实例：不动它
                val (diskVersion, state) = p
                if (inst.state == state && (diskVersion == null || inst.dshVersion == diskVersion)) {
                    inst
                } else {
                    changed = true
                    inst.copy(
                        state = state,
                        dshVersion = diskVersion ?: inst.dshVersion,
                        lastError = if (state == DshInstance.State.READY) null else inst.lastError,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
        }
        if (changed) saveAsync()
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
            // 先停掉可能还在跑的进程（不然删目录会留下活着的 node）
            if (DshRuntime.runningInstanceId() == id) DshRuntime.stop("实例被删除")
            val ok = runCatching { DshPaths.instanceDir(id).deleteRecursively() }.getOrDefault(false)
            DshLogBus.append("[instances] 删除实例 ${inst?.name ?: id} 目录: ${if (ok) "完成" else "有文件残留"}")
            _deleting.update { it - id }
            onFinished?.invoke(ok)
        }
    }

    /** 异步统计某实例占用体积（列表里显示，IO 线程） */
    fun diskUsageAsync(id: String, callback: (Long) -> Unit) {
        DshAppScope.scope.launch { callback(DshPaths.dirSize(DshPaths.instanceDir(id))) }
    }

    // --- 持久化 ------------------------------------------------------------

    private fun manifestFile() = File(DshPaths.INSTANCES_MANIFEST)

    private data class Manifest(
        val instances: List<DshInstance>?,
        val selectedId: String?
    )

    private fun load() {
        val f = manifestFile()
        if (!f.exists()) return
        runCatching {
            val type = object : TypeToken<Manifest>() {}.type
            val m: Manifest = gson.fromJson(f.readText(), type)
            val list = m.instances ?: emptyList()
            _instances.value = list
            _selectedId.value = m.selectedId?.takeIf { id -> list.any { it.id == id } }
                ?: list.firstOrNull()?.id
        }.onFailure { e ->
            // 清单坏了不要静默丢：另存一份便于人工找回，并把清单重置为空
            DshLogBus.append("[instances] instances.json 解析失败，已另存备份：${e.message}")
            runCatching { f.renameTo(File(f.parentFile, "${f.name}.corrupt-${System.currentTimeMillis()}")) }
            _instances.value = emptyList()
            _selectedId.value = null
        }
    }

    /**
     * 异步 + 串行 + 原子写盘。UI 线程只更新内存状态，不做磁盘 IO。
     */
    fun saveAsync() {
        DshAppScope.scope.launch {
            writeMutex.withLock {
                runCatching { writeNow() }.onFailure {
                    DshLogBus.append("[instances] 写入 instances.json 失败：${it.message}")
                }
            }
        }
    }

    /** 同步写（测试用；生产路径请用 [saveAsync]） */
    internal fun writeNow() {
        val f = manifestFile()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(gson.toJson(Manifest(_instances.value, _selectedId.value)))
        if (!tmp.renameTo(f)) {
            // rename 失败（少见）：退化为直接覆盖写
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
