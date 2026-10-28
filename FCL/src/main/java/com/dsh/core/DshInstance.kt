package com.dsh.core

import com.google.gson.annotations.SerializedName

/**
 * 一个 dsh 实例：一份独立安装的 @deepseek-ai/dsh + 独立 DSH_HOME + 独立配置。
 * 对应 FCL 里的一个 Profile（游戏目录/选中版本），这里是"一个可运行的 harness 环境"。
 *
 * 用 kotlinx.serialization 还是 Gson？FCL 两者都在用；这里选 Gson，与
 * [com.tungsten.fcl.setting.Profile] 一致，且 [DshRegistry] 已用 Gson 注解，统一技术栈。
 *
 * ## 字段可变性约定（Round 15 批 B，BL-100 —— 审计注释）
 * 本类的 `var` 字段（name / dshVersion / profile / port / model / updatedAt / state / lastError）
 * 对**外部只读**：持有 [DshInstances.instances] 里实例对象的调用方**不得**直接改写字段。
 * 直接改写既不落盘、也不发射 StateFlow（收集者看不到），重启即丢 —— 这正是审查发现 A4-4 的
 * 静默数据丢失面。**一切修改必须经 [DshInstances]**（update / updateConfig / markState /
 * rename 等 —— 内部统一"copy 后入表"并以复制品发射）。
 *
 * 为什么字段暂时是 `var` 而不是 `val`：
 * 1. Gson 反序列化需要（`gson.fromJson(element, DshInstance::class.java)`）；
 * 2. [DshInstances.parseManifest] 的字段级回填在**尚未发布**的本地对象上做防御性赋值
 *    （state/profile/model 防 null；那些对象这时没有任何外部持有者，不适用上述约定）。
 * 把整个类收成不可变是后续批次（批 C）的事；本轮先以本注释钉死"谁能写、写走哪条路"。
 */
data class DshInstance(
    /** 稳定唯一 id（生成后不变，用作目录名），如 "inst-1700000000000-3-7f2a" */
    val id: String,

    /** 用户可见名称，可改（外部只读，写经 DshInstances） */
    var name: String,

    /** 已安装的 dsh 版本号，如 "0.1.5-rc.2"；未安装完成时为 null（外部只读，写经 DshInstances） */
    @SerializedName("dshVersion")
    var dshVersion: String? = null,

    /** 启动用的 profile：web(带UI) / headless(一次性)。默认 web。（外部只读，写经 DshInstances） */
    var profile: String = "web",

    /**
     * Web UI 监听端口。
     * **改进点**：0 表示"由系统分配"（dsh 支持 `--port 0`，会打印真实端口，[DshRuntime] 会回读并写回本字段）。
     * 新建实例默认 0，彻底避免"自选端口被别的 App 占了"导致的启动失败。
     *
     * 外部只读，写经 DshInstances。
     */
    var port: Int = 0,

    /** 默认模型 id（deepseek-flash / deepseek-v4-pro）（外部只读，写经 DshInstances） */
    var model: String = MODEL_FLASH,

    /** 创建时间戳（排序用） */
    val createdAt: Long = System.currentTimeMillis(),

    /** 最近一次变更时间戳（外部只读，写经 DshInstances） */
    var updatedAt: Long = System.currentTimeMillis(),

    /** 安装状态（外部只读，写经 DshInstances） */
    var state: State = State.NOT_INSTALLED,

    /**
     * 最近一次失败原因（安装/启动），用于在列表里直接给出可操作的提示，
     * 而不是只丢一句"详见日志"。（外部只读，写经 DshInstances）
     */
    var lastError: String? = null,

    /**
     * 自定义的 `HOME`（rootfs 内路径）。`null` = 用默认值（实例目录，见 [DshRuntime]）。
     *
     * ★ R27 / P0-A2：dsh 的目录选择器用 `homedir()`（= `$HOME`）当初始目录，且**只列目录** ——
     *   所以这个值必须指向一个**有子目录的**位置，否则用户看到的还是空列表。
     *   默认值 `instances/<id>/` 天然含 `home/`、`workspace/`、`node_modules/`，列表非空。
     *
     * 提供这个字段是为了让用户能把 home 指到别处（例如整个 `instances/` 根，方便跨实例选目录）。
     * 值为 rootfs 内绝对路径（如 `/opt/dsh/instances`）。
     * **只影响 `HOME`**，不影响 `DSH_HOME`（后者固定为 `<instance>/home`，配置/凭据不会搬家）。
     *
     * 外部只读，写经 [DshInstances.updateConfig]。
     */
    var homeDir: String? = null
) {
    enum class State {
        /** 目录已建但 node_modules 未装 */
        NOT_INSTALLED,

        /** 正在下载/安装 */
        INSTALLING,

        /** 可启动 */
        READY,

        /** 安装失败或损坏 */
        BROKEN
    }

    /** 是否已处于可启动状态 */
    val isStartable: Boolean get() = state == State.READY

    companion object {
        const val MODEL_FLASH = "deepseek-flash"
        const val MODEL_PRO = "deepseek-v4-pro"

        /** 可选模型列表（设置页下拉用） */
        val MODELS = listOf(MODEL_FLASH, MODEL_PRO)

        const val PROFILE_WEB = "web"
        const val PROFILE_HEADLESS = "headless"
        val PROFILES = listOf(PROFILE_WEB, PROFILE_HEADLESS)
    }
}
