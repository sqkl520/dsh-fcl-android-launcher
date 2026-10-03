package com.dsh.ui.shell

import android.content.Context
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.fcl.androidlauncher.R

/**
 * 设置 tab（外壳 tab 4）—— **页内多页容器**（G1）。
 *
 * 结构照搬 FCL 的 `SettingUI`：页内 `FCLTabLayout` + 内层 ViewPager2 承载子页。
 * FCL 的设置页是 [版本设置 | 启动器设置 | 插件管理 | 关于]；dsh 对应为：
 *
 * | FCL 子页 | dsh 子页 |
 * |---|---|
 * | 启动器设置 | [DshLauncherSettingsPage]（通用/外观/运行环境/DeepSeek Harness） |
 * | 关于 | [DshAboutUI] |
 * | 插件管理 | 待插件体系确定后补（见 `docs/TASKS.md`） |
 *
 * 「关于」在 FCL 里就是设置页的子页，因此这里也把它做成子页，而不是弹窗。
 */
class DshSettingsUI(
    context: Context,
    private val host: DshShellHost
) : DshMultiPageUI(context) {

    private companion object {
        const val TAB_LAUNCHER = 0
        const val TAB_ABOUT = 1
    }

    override val pageCount: Int = 2

    override fun tabTitle(position: Int): Int = when (position) {
        TAB_LAUNCHER -> R.string.dsh_settings_tab_launcher
        else -> R.string.dsh_about_tab
    }

    override fun tabIcon(position: Int): Int = when (position) {
        TAB_LAUNCHER -> R.drawable.ic_baseline_settings_24
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
            else -> DshAboutUI(context)
        }
    }
}
