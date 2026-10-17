package com.dsh.core

import java.util.concurrent.ConcurrentHashMap

/**
 * 按 key 的"单飞闸门"：同一 key 同一时刻只允许一个任务持有。
 *
 * ## 修的问题：check-then-act
 * 原来的写法是两处入口各自
 *
 * ```kotlin
 * if (running.containsKey(id)) return false     // ① 查
 * running[id] = job                             // ② 登记（在 launch 之后！）
 * ```
 *
 * ①②之间不是原子的，而且登记发生在**协程已经启动之后**，于是有两个洞：
 * - 两个调用点（下载页 / 列表页）同时进来，两边都看到"没人在跑"，两个 npm 同时写同一个
 *   `node_modules`；
 * - 任务如果很快失败（预检不过 → 几百毫秒就结束），`finally` 里的 `running.remove()` 可能先于
 *   ② 执行，随后 ② 把一个**已经结束的** job 写回表里 —— `isInstalling()` 从此永远为真，该实例
 *   之后的所有安装请求都会被"已有安装任务在进行"挡掉，除非杀进程重启 App。
 *
 * 现在把①②合成一次原子 CAS（[tryAcquire] 内部用 `ConcurrentHashMap.putIfAbsent`），
 * 任务的启动顺序则由调用方用 `CoroutineStart.LAZY` 保证"先登记、后运行"。
 *
 * 纯逻辑、无 Android 依赖，可直接单测（见 `DshCoreLogicTest`）。
 */
class SingleFlight {

    private val slots = ConcurrentHashMap<String, Any>()
    private val token = Any()

    /** 尝试占坑：成功返回 true（调用方负责在结束时 [release]）；已被占返回 false。 */
    fun tryAcquire(key: String): Boolean = slots.putIfAbsent(key, token) == null

    /** 释放占坑（只释放自己占的那个，避免把别的 key 的新任务误删）。 */
    fun release(key: String) {
        slots.remove(key, token)
    }

    /** 当前是否有任务在跑 */
    fun isRunning(key: String): Boolean = slots.containsKey(key)

    /** 正在跑的 key 快照（诊断/测试用） */
    fun runningKeys(): Set<String> = slots.keys.toSet()

    // ------------------------------------------------------------------
    // K14（R15）：owner 版 API —— 无归属校验的旧 API 的加固替代。
    //
    // 旧 API 的洞：`token` 是**全类共享的一个对象**，任何持有 key 的调用方都能 release ——
    // 旧任务的收尾代码在"新任务已接管同名 key"之后释放，会把**新任务的坑**误删
    // （R12-A4 完整推演：K14 活体路径）。owner 版让每次占坑返回一个**独占凭据**，
    // 释放时必须持凭据，过期凭据对当前坑无效（compare-and-remove 语义）。
    //
    // 旧 API 保留（DshInstaller 等既有调用方不改）；新代码一律用 Owner 版。
    // ------------------------------------------------------------------

    /** [tryAcquireOwner] 的返回凭据：不透明对象，只有持有者能释放对应的坑 */
    class Owner internal constructor(internal val key: String, internal val credential: Any)

    /**
     * 尝试占坑（owner 版）：成功返回凭据（结束用 [releaseOwner] 释放）；已被占返回 null。
     * 与 [tryAcquire] 共享同一张 slots —— 两种 API 可以混用且互斥语义一致。
     */
    fun tryAcquireOwner(key: String): Owner? {
        val cred = Any()
        return if (slots.putIfAbsent(key, cred) == null) Owner(key, cred) else null
    }

    /**
     * 释放占坑（owner 版）：仅当坑里仍是**这个凭据**时才移除。
     * @return true = 确实释放了；false = 坑已被别人接管（凭据过期）或本就空着 —— 都是 no-op
     */
    fun releaseOwner(owner: Owner): Boolean = slots.remove(owner.key, owner.credential)

    /** 清空（测试用） */
    internal fun clear() {
        slots.clear()
    }
}
