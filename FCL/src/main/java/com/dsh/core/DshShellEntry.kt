package com.dsh.core

import android.content.Context
import android.content.Intent

/**
 * core → 外壳（`DshMainActivity`）的**最小导航契约**。
 *
 * ## 为什么 core 里要有这么一个接口
 * core 层的前台服务 [DshRuntimeService] 需要给通知挂一个"点回 App"的 contentIntent，
 * 原来直接 `Intent(context, DshMainActivity::class.java)` —— 这是全仓**唯一**一条
 * `core → ui` 的编译期依赖。有它在，"core 可以独立于 ui 存在"这条分层前提就不成立：
 * 任何把 core 拆成独立 module / 单独跑 core 单测 harness 的动作都会在这里断。
 *
 * 修法**不是**把 Activity 换成反射类名字符串 —— 那只是把编译期依赖藏到运行时，
 * 问题原样保留（还多了一个"拼错字符串只有到真机点通知才暴露"的失败面）。
 * 正确做法是把"外壳入口"这件事收敛成一个**由 ui 层实现、core 只认签名**的接口：
 * core 说"给我一个能拉起外壳的 Intent"，ui 说"好的，是 DshMainActivity"。
 *
 * ## 注册与退化
 * ui / App 层通过 [DshNavigation.register] 注入实现（本项目在
 * `FCLApp.onCreate` 的 `initStep("dshNavigation", …)` 里注册一次，早于任何服务启动 ——
 * 服务是由 App 进程拉起的，`Application.onCreate` 必然已经跑过）。
 *
 * 未注册时 [DshNavigation.shellEntry] 为 null，[DshRuntimeService] **不得**因此崩溃：
 * 退化成 `packageManager.getLaunchIntentForPackage(packageName)`（系统给的 LAUNCHER 入口，
 * 正常就是启动页 → 主壳，与用户点桌面图标同一条路）；连它都拿不到时**不挂 contentIntent**，
 * 通知仍然展示状态与"停止"动作，只是点通知本身没有反应。
 * 三种情况都写进了 [DshRuntimeService.shellIntentSource]，那条纯函数有单测钉住。
 */
fun interface DshShellEntry {
    /** 返回一个能拉到外壳的 Intent（调用方负责补 `FLAG_ACTIVITY_NEW_TASK` 等启动标志）。 */
    fun shellIntent(context: Context): Intent
}

/**
 * [DshShellEntry] 的**可空注册点**。
 *
 * 为什么要一个独立的 object 而不是把实现塞进 [DshRuntimeService] 的 companion：
 * 服务是 core 的，注册方是 ui/App 层，注册必须**早于**服务第一次构建通知（通知可能在
 * 用户还没打开过任何 Activity 时就被 `startForeground` 出来 —— 例如认领孤儿进程后
 * 立刻拉服务）。放在服务里就得等 UI 触发，时机不可控。
 *
 * `@Volatile` + 读取走 [shellEntry] 属性：注册发生在主线程，读取发生在服务线程。
 * 写入的是**不可变引用**，无需加锁；`@Volatile` 只保证可见性。
 *
 * ⚠️ 不要在这里缓存别的状态（例如"上一次拉的 Intent"）：外壳的 tab/实例参数随现场变化，
 * 缓存会让通知永远停在第一次的样子。
 */
object DshNavigation {

    @Volatile
    private var entry: DshShellEntry? = null

    /** 当前注册的外壳入口；未注册为 null（调用方必须处理这一态，见接口 KDoc 的退化策略）。 */
    val shellEntry: DshShellEntry?
        get() = entry

    @JvmStatic
    fun register(shell: DshShellEntry) {
        entry = shell
    }

    /**
     * 撤销注册。
     *
     * 生产路径上**永远不调**（注册是进程级、一次性的）。存在只为单测夹具：
     * harness 在同一个 JVM 里跑全部用例，注入过的假实现不还原就会漏给后面的用例。
     */
    @JvmStatic
    fun clear() {
        entry = null
    }
}
