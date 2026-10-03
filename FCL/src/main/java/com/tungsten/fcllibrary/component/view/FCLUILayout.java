package com.tungsten.fcllibrary.component.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.RelativeLayout;

/**
 * 页内容器（FCL 原版同名类）：
 * 「页内多页容器」[com.dsh.ui.shell.DshMultiPageUI] 用它承载内层 ViewPager2；
 * 与 FCL 的 {@code FCLMultiPageUI.setupPages(container, tabLayout)} 用法一致。
 */
public class FCLUILayout extends RelativeLayout {

    public FCLUILayout(Context context) {
        super(context);
    }

    public FCLUILayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FCLUILayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public FCLUILayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
}
