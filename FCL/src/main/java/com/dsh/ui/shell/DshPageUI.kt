package com.dsh.ui.shell

import android.content.Context
import androidx.annotation.LayoutRes
import com.tungsten.fclcore.task.Task
import com.tungsten.fcllibrary.component.ui.FCLCommonUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * dsh 外壳内所有页面的基类。
 *
 * ## 为什么需要它
 * `FCLCommonUI`（FCL 框架）**不是** `LifecycleOwner`，没有协程作用域；而 dsh 页面需要
 * 用协程订阅 `StateFlow`（实例列表、日志、运行状态）。原来这些逻辑在独立 Activity 里靠
 * `lifecycleScope` + `repeatOnLifecycle`。迁进 ViewPager 后页面随滑动创建/回收，必须有一个
 * **随页面存活、页面被回收即取消**的作用域，否则会协程泄漏或重复订阅。
 *
 * [DshUIManager] 在创建页面后不动，在 `onViewRecycled` 回收页面时调用 [destroy]。
 * 子类在 [onCreate] 里用 [scope] 启动 collect 即可，无需手动取消。
 */
abstract class DshPageUI(
    context: Context,
    @LayoutRes id: Int
) : FCLCommonUI(context, id) {

    /** 页面级协程作用域：主线程、页面回收时整体取消 */
    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 由 [DshUIManager] 在页面被 ViewPager 回收时调用 */
    open fun destroy() {
        scope.cancel()
        onDestroy()
    }

    /** 子类覆写做额外清理（可选） */
    protected open fun onDestroy() {}

    /** dsh 页面默认无需 FCL 的 Task 式刷新 */
    override fun refresh(vararg param: Any?): Task<*>? = null
}
