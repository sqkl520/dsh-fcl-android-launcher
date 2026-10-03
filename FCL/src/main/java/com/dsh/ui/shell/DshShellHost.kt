package com.dsh.ui.shell

import com.tungsten.fcllibrary.component.FCLActivity

/**
 * 外壳对页面开放的能力。页面（[DshPageUI]）通过它完成跨 tab 跳转、打开详情 Activity 等，
 * 不再各自 `startActivity` 到并列的功能 Activity（那套在阶段 2 已并入外壳）。
 */
interface DshShellHost {

    /** 承载外壳的 Activity（页面拿它做 context / 弹窗 / startActivity 详情页） */
    val activity: FCLActivity

    /** 切到指定 tab（见 [DshUIManager.titles] 的顺序：0 实例 /1 管理 /2 下载 /3 日志 /4 设置） */
    fun switchTab(position: Int)

    /** 打开某实例的详情设置页（保持为独立全屏 Activity） */
    fun openInstanceSettings(instanceId: String)

    /** 打开 dsh WebUI（独立全屏 Activity） */
    fun openWebView()

    /**
     * 让用户挑一张图片（走系统文件选择器 `ACTION_OPEN_DOCUMENT`）。
     *
     * 为什么不用 FCL 的 `FileBrowser`：那套整包在阶段 4 被裁掉，而我们只需要"选一张图片"，
     * 系统选择器零权限、零额外代码，也更符合 Android 规范（见 `docs/TASKS.md` T5）。
     *
     * @param onPicked 用户取消或出错时回传 null
     */
    fun pickImage(onPicked: (android.net.Uri?) -> Unit)

    companion object {
        const val TAB_INSTANCES = 0
        const val TAB_MANAGE = 1
        const val TAB_DOWNLOAD = 2
        const val TAB_LOGS = 3
        const val TAB_SETTINGS = 4
    }
}
