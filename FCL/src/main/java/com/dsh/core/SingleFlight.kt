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

    /** 清空（测试用） */
    internal fun clear() {
        slots.clear()
    }
}
