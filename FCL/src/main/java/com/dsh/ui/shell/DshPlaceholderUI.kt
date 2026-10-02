package com.dsh.ui.shell

import android.content.Context
import androidx.annotation.StringRes
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.UiDshPlaceholderBinding
import com.tungsten.fclcore.task.Task
import com.tungsten.fcllibrary.component.ui.FCLCommonUI

/**
 * 阶段 1 外壳的通用占位页。阶段 2 会把每个占位页替换成真正的 dsh 页面
 * （实例/管理/下载/日志/设置，由现有 Activity 迁移为 FCLCommonUI）。
 *
 * 现在它只显示页名与一句说明，用来验证外壳骨架（菜单切换 + 动态岛标题联动）能跑通。
 */
class DshPlaceholderUI(
    context: Context,
    @StringRes private val titleRes: Int,
    @StringRes private val descRes: Int
) : FCLCommonUI(context, R.layout.ui_dsh_placeholder) {

    override fun onCreate() {
        super.onCreate()
        val binding = UiDshPlaceholderBinding.bind(contentView)
        binding.placeholderTitle.setText(titleRes)
        binding.placeholderDesc.setText(descRes)
    }

    override fun refresh(vararg param: Any?): Task<*>? = null
}
