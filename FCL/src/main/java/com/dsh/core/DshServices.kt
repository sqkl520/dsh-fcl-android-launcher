package com.dsh.core

import android.content.Context

/**
 * 进程级服务定位器：安装器必须是**单例**。
 *
 * 原来的写法是每个界面自己 `DshInstaller(this, executor, lifecycleScope)` 新建一个，
 * 于是"同一实例只能有一个安装任务"的单飞控制形同虚设——实例列表页和下载页可以各起一个 npm
 * 同时往同一个 `node_modules` 里写。这里用 App 级 Context 建一次，全进程共用。
 */
object DshServices {

    @Volatile
    private var installerRef: DshInstaller? = null

    fun installer(context: Context): DshInstaller {
        installerRef?.let { return it }
        return synchronized(this) {
            installerRef ?: DshInstaller(
                context.applicationContext,
                ProotProcessExecutor(context.applicationContext)
            ).also { installerRef = it }
        }
    }
}
