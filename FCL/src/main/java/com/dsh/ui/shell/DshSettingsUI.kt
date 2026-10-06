package com.dsh.ui.shell

import android.content.Context
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.fcl.androidlauncher.R

/**
 * 设置 tab —— **页内多页容器**（G1）。
 *
 * 结构照搬 FCL 的 `SettingUI`：页内 `FCLTabLayout` + 内层 ViewPager2 承载子页。
 * FCL 的设置页是 4 个子页「版本设置 / 启动器设置 / 插件管理 / 关于」；dsh 取 3 个：
 *
 * | FCL 子页 | dsh 子页 |
 * |---|---|
 * | 启动器设置 | [DshLauncherSettingsPage]（通用/外观/运行环境/DeepSeek Harness） |
 * | —（FCL 无对应页） | [DshLogsUI]（**App 级**日志） |
 * | 关于 | [DshAboutUI] |
 * | 版本设置 | 不设：版本浏览已由外壳的「版本」tab 承载，页内再放一份就是重复导航 |
 * | 插件管理 | 不设：待插件体系确定后补（见 `docs/TASKS.md`） |
 *
 * ## 为什么「日志」是这里的子页
 * 外壳左菜单只有「实例 / 版本 / 设置」三项，运行日志不再是主 tab。日志是**读**的功能、
 * 不是**干活**的功能，挂在设置下比占一个主入口更合适 —— 这也是 FCL 把「关于」塞进设置页的同一逻辑。
 *
 * ## 这一页的日志是 App 级，不是实例级
 * 本页只承载**启动器自己打的行**：解压 / 安装、运行时启停、凭据读写、rootfs 引导……
 * 这些行不属于任何一个实例，所以构造 [DshLogsUI] 时**不传 instanceId**（即 `null`）。
 * 而**某个实例自己的输出**（那个 dsh 进程的 stdout/stderr）在实例详情页的「日志」tab 里 ——
 * 同一个 [DshLogsUI]，只是传了 `instanceId`。视图共用，数据源按实例 id 切分，
 * 于是"启动器怎么了"和"这个实例怎么了"是两个入口、一套实现。
 *
 * 「关于」在 FCL 里就是设置页的子页，因此这里也把它做成子页，而不是弹窗。
 */
class DshSettingsUI(
    context: Context,
    private val host: DshShellHost
) : DshMultiPageUI(context) {

    private companion object {
        const val TAB_LAUNCHER = 0
        const val TAB_LOGS = 1
        const val TAB_ABOUT = 2
    }

    override val pageCount: Int = 3

    override fun tabTitle(position: Int): Int = when (position) {
        TAB_LAUNCHER -> R.string.dsh_settings_tab_launcher
        TAB_LOGS -> R.string.dsh_settings_tab_logs
        else -> R.string.dsh_about_tab
    }

    override fun tabIcon(position: Int): Int = when (position) {
        TAB_LAUNCHER -> R.drawable.ic_baseline_settings_24
        TAB_LOGS -> R.drawable.ic_dsh_logs_24
        else -> R.drawable.ic_baseline_info_24
    }

    override fun createPage(position: Int): DshPageUI {
        DshPaths.loadPaths(context)
        DshInstances.init()
        return when (position) {
            TAB_LAUNCHER -> DshLauncherSettingsPage(context, host) {
                // 设置列表里的「关于本启动器」→ 切到「关于」子页
                showPage(TAB_ABOUT)
            }
            // 不传 instanceId：本页是 App 级全局日志（见类注释）
            TAB_LOGS -> DshLogsUI(context)
            else -> DshAboutUI(context)
        }
    }
}
