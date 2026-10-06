package com.dsh.ui.shell

import com.tungsten.fcllibrary.component.FCLActivity

/**
 * 外壳对页面开放的能力。页面（[DshPageUI]）通过它完成跨 tab 跳转、打开详情 Activity 等，
 * 不再各自 `startActivity` 到并列的功能 Activity（那套在阶段 2 已并入外壳）。
 *
 * ## 为什么这里**没有**返回链 / 标题源的方法（有意为之，别顺手补）
 * 返回链的 ① 级在 [DshMainActivity.onKeyDown]、②③④ 级在 [DshUIManager.onBackPressed]、
 * ⑤ 级在 [DshMainActivity] 的兜底；标题源在 [DshUIManager.titleOf]。
 * 这两件事**都只由外壳自己驱动**：
 * - 页面想让返回键先退自己 → 覆写 `DshPageUI.onPageBack()`（反向调用，页面不需要拿 host）；
 * - 页内临时页 → `DshMultiPageUI` 自己持有栈，外壳只是转发调用；
 * - 标题 → 页面覆写 `DshPageUI.pageTitle()` 让外壳**来取**（pull），
 *   而不是页面 push 给外壳 —— 所以也不需要 `host.setTitle()` 这类方法。
 *
 * 也就是说，本接口是"页面 **主动要**外壳做事"的能力面；返回与标题是外壳**主动问**页面。
 * 两个方向不同，所以不往这里加方法 —— 加了反而会让"谁驱动返回链"变得含糊。
 * 将来若真有页面需要主动触发返回（例如页内自带一个返回按钮），再按那时**确实存在**的调用方
 * 加一个 `fun onBack()`，而不是现在预留。
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
