package com.dsh.ui.shell

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.dialog.FCLDialog
import com.tungsten.fcllibrary.component.view.FCLTextView

/**
 * 「全屏页面对话框」——把任意 [DshPageUI] 装进一个 FCL 对话框里展示。
 *
 * ## 为什么需要它
 * FCL 的「关于页」是**设置页内的子页**（靠 `FCLMultiPageUI` + 标签栏承载）。
 * 我们还没做那套页内多页体系（见 `docs/TASKS.md` T3），但关于页本身现在就想要。
 * 与其临时写一个 Activity（等 T3 落地后还要拆掉），不如把页面做成可复用的 [DshPageUI]，
 * 现在用本对话框全屏承载；**T3 落地后同一个页面类直接作为子页复用，零返工**。
 *
 * 生命周期：创建时调用 [DshPageUI.onCreate]，关闭时调用 [DshPageUI.destroy]（取消其协程作用域）。
 */
class DshFullPageDialog(
    private val activity: FCLActivity,
    private val page: DshPageUI,
    title: String,
) : FCLDialog(activity) {

    init {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(8))
        }

        val titleView = FCLTextView(activity).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
            setAutoTint(true)
        }
        root.addView(
            titleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 页面自己负责 inflate 与订阅；这里只把它的 contentView 挂进对话框
        page.onCreate()
        val content = page.contentView
        (content.parent as? ViewGroup)?.removeView(content)
        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        setOnDismissListener { page.destroy() }

        // 占屏幕大部（横屏下留边，避免贴边不好看）
        val dm = activity.resources.displayMetrics
        window?.setLayout((dm.widthPixels * 0.72f).toInt(), (dm.heightPixels * 0.86f).toInt())
    }
}
