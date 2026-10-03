package com.dsh.ui.shell

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcllibrary.component.dialog.FCLDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.component.view.FCLTextView
import com.tungsten.fcllibrary.util.ConvertUtils

/**
 * 选项选择对话框 —— 供设置页的「语言 / 主题模式 / 动画速度」等单选项使用。
 *
 * 为什么自建：fcllibrary 里没有通用的单选对话框（FCL 上游的
 * `dialog_item_selection` 随 MC 一起删掉了），而设置页需要它。
 * 这里只用 fcllibrary 的 [FCLDialog] + [FCLTextView] 拼装，保持与 FCL 一致的观感
 * （对话框圆角背景来自 FCLDialog，文字随主题着色，选中项加粗并高亮主题色）。
 */
class DshOptionDialog(
    context: Context,
    title: String,
    private val options: List<String>,
    private val selectedIndex: Int,
    private val onPick: (Int) -> Unit,
) : FCLDialog(context) {

    init {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(8))
        }

        val titleView = FCLTextView(context).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(12))
            setAutoTint(true)
        }
        root.addView(titleView)

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { index, label ->
            val item = FCLTextView(context).apply {
                text = label
                textSize = 15f
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setAutoTint(true)
                if (index == selectedIndex) {
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ThemeEngine.getInstance().getTheme().autoTint)
                    setBackgroundResource(R.drawable.bg_container_transparent_clickable)
                    backgroundTintList =
                        ColorStateList.valueOf(ThemeEngine.getInstance().getTheme().color)
                } else {
                    setBackgroundResource(R.drawable.clickable_parent)
                }
                setOnClickListener {
                    dismiss()
                    onPick(index)
                }
            }
            list.addView(
                item,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            list,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val scroll = ScrollView(context).apply { addView(root) }
        setContentView(scroll)

        // 让对话框宽度接近 FCL 的样式（占屏宽大部，但不至于全宽）
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.42f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
