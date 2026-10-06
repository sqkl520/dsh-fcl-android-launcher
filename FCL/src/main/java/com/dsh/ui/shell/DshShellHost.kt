package com.dsh.ui.shell

import com.tungsten.fcllibrary.component.FCLActivity

/**
 * 外壳对页面开放的能力。页面（[DshPageUI]）通过它完成跨 tab 跳转、下钻到实例详情等，
 * 不再各自 `startActivity` 到并列的功能 Activity（那套在阶段 2 已并入外壳；
 * 最后残留的实例详情也已收进外壳，见 [openInstanceDetail]）。
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

    /** 承载外壳的 Activity（页面拿它做 context / 弹窗 / 打开 WebUI） */
    val activity: FCLActivity

    /** 切到指定 tab（见 [DshUIManager.titles] 的顺序：0 实例 /1 版本 /2 设置） */
    fun switchTab(position: Int)

    /**
     * 打开某实例的详情页。★ **不再是 `startActivity` —— 它压进「实例」页的临时页栈**。
     *
     * ## 为什么从独立 Activity 改成页内临时页
     * 实例详情原来是独立全屏 Activity（`DshSettingsActivity`）。它有两条硬伤，都是"把页内下钻
     * 做成并列 Activity"这个形态本身带来的：
     *
     * 1. **它不实现 [DshShellHost]**，所以页内做不了任何外壳内导航。详情页里想"看这个实例的日志"
     *    就只能 `startActivity(intentForTab(..., CLEAR_TOP))` —— 那是把**整个外壳弹栈重建**一次
     *    来换一个 tab。用户看到的是一次闪断重进，而不是"从详情退一层回到日志"。
     * 2. **它没有返回按钮**（亮色主题下还整页全白，用户报的就是这一条）。外壳左菜单那条返回项
     *    属于外壳，详情页盖在上面时根本够不着；而它自己又不提供替代入口，于是只剩系统返回键可退。
     *
     * 改成临时页后这两条同时消失：详情页挂在「实例」页的覆盖层上，外壳的返回链天然覆盖它
     * （②③④ 级里 ③ 就是弹临时页栈），导航也重新回到外壳内部 —— 详情里要跳日志页，
     * 走 [switchTab] 即可，不再需要 `CLEAR_TOP` 重建外壳。
     *
     * 实现侧见 `DshMainActivity.openInstanceDetail`：先切到「实例」tab，再让那一页把详情压栈。
     */
    fun openInstanceDetail(instanceId: String)

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
        /**
         * 菜单项常量。**顺序即菜单顺序**，必须与 `activity_dsh_main.xml` 里从上到下的
         * `FCLMenuView`、以及 [DshUIManager.titles] / `factories` 三者一一对应 ——
         * 外壳靠下标（`menus[position]`）把菜单项、ViewPager 页、标题绑在一起，错一位就整体错位。
         *
         * ## 为什么从 5 项收到 3 项
         * - **删「管理」**：它是死代码 —— 那一页只是一句"即将推出"的空占位，`TAB_MANAGE`
         *   全项目零引用。它当初预留的职责其实已经被分掉了：进程管理归实例详情，版本管理归版本页。
         *   留一个永远打不开任何东西的入口，只会让用户点进去看到一句占位文案。
         * - **删「日志」**：日志分成两份更贴用户的心智模型 —— **实例日志**跟着实例走，
         *   放进实例详情页（谁出问题看谁的日志）；**App 级日志**降级成「设置」里的一个子页
         *   （那是排查启动器自身问题时才看的东西，不值得占一个一级入口）。
         *
         * 名字与顺序是冻结接口：`TAB_VERSIONS = 1` 顶的是原来 `TAB_DOWNLOAD` 的位置，
         * 页面类 `DshDownloadUI` 不改名（改名是纯噪音，改的是显示文案与菜单名）。
         */
        const val TAB_INSTANCES = 0
        const val TAB_VERSIONS = 1
        const val TAB_SETTINGS = 2
    }
}
