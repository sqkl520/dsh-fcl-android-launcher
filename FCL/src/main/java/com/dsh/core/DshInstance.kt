package com.dsh.core

import com.google.gson.annotations.SerializedName

/**
 * 一个 dsh 实例：一份独立安装的 @deepseek-ai/dsh + 独立 DSH_HOME + 独立配置。
 * 对应 FCL 里的一个 Profile（游戏目录/选中版本），这里是"一个可运行的 harness 环境"。
 *
 * 用 kotlinx.serialization 还是 Gson？FCL 两者都在用；这里选 Gson，与
 * [com.tungsten.fcl.setting.Profile] 一致，且 [DshRegistry] 已用 Gson 注解，统一技术栈。
 */
data class DshInstance(
    /** 稳定唯一 id（生成后不变，用作目录名），如 "inst-1700000000000-3-7f2a" */
    val id: String,

    /** 用户可见名称，可改 */
    var name: String,

    /** 已安装的 dsh 版本号，如 "0.1.5-rc.2"；未安装完成时为 null */
    @SerializedName("dshVersion")
    var dshVersion: String? = null,

    /** 启动用的 profile：web(带UI) / headless(一次性)。默认 web。 */
    var profile: String = "web",

    /**
     * Web UI 监听端口。
     * **改进点**：0 表示"由系统分配"（dsh 支持 `--port 0`，会打印真实端口，[DshRuntime] 会回读并写回本字段）。
     * 新建实例默认 0，彻底避免"自选端口被别的 App 占了"导致的启动失败。
     */
    var port: Int = 0,

    /** 默认模型 id（deepseek-flash / deepseek-v4-pro） */
    var model: String = MODEL_FLASH,

    /** 创建时间戳（排序用） */
    val createdAt: Long = System.currentTimeMillis(),

    /** 最近一次变更时间戳 */
    var updatedAt: Long = System.currentTimeMillis(),

    /** 安装状态 */
    var state: State = State.NOT_INSTALLED,

    /**
     * 最近一次失败原因（安装/启动），用于在列表里直接给出可操作的提示，
     * 而不是只丢一句"详见日志"。
     */
    var lastError: String? = null
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
