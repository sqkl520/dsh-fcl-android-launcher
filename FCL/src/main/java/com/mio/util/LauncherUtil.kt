package com.mio.util

import android.content.Context
import com.dsh.fcl.androidlauncher.R

/**
 * 启动器名称（FCL 的 `com/mio/util/LauncherUtil.kt`，包名已改到本项目）。
 *
 * 读 `launcher` 偏好里的 `custom_launcher_name`：
 * - 未设置（默认值 = `app_name`）→ 返回 `应用名/版本号`
 * - 已设置 → 返回自定义名；其中的字面量 `${launcher_version}` 会被替换成版本号
 *   （FCL 的约定：用户可写 `MyLauncher/${launcher_version}` 这种模板）
 *
 * FCL 里它用于游戏窗口标题与崩溃报告；本项目用于「关于页」与「自定义启动器名」设置项。
 */
fun getLauncherName(context: Context): String {
    val appName = context.getString(R.string.app_name)
    val appVersion = context.getString(R.string.app_version)
    val custom = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        .getString("custom_launcher_name", appName)
        .orEmpty()
    if (custom.isEmpty()) return "$appName/$appVersion"
    return custom.replace("\${launcher_version}", appVersion)
}
