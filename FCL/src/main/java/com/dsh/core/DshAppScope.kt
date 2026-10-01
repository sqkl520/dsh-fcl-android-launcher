package com.dsh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 进程级协程作用域。
 *
 * **修的问题**：原来安装/启动用的是 `DshInstaller(this, executor, lifecycleScope)`——作用域绑在
 * Activity 上。用户在装 300MB 依赖时装到一半旋转屏幕或按返回键，Activity 销毁 → 协程被取消 →
 * proot 里的 npm 成了孤儿，实例状态永久停在 `INSTALLING`（持久化到磁盘），下次进 App 也起不来。
 *
 * 现在所有"长任务"（安装、启停、日志落盘、镜像拉取）都跑在进程级作用域里，
 * 与任何界面生命周期解耦；界面只是订阅状态。App 进程被系统杀掉时系统会一并清理子进程，
 * 而 [DshInstances] 在下次启动时会把残留的 INSTALLING 状态修复回真实状态。
 */
object DshAppScope {

    /** 长任务用；SupervisorJob 保证一个任务失败不牵连其他任务 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
