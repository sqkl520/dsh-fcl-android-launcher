package com.tungsten.fcllibrary.component.dialog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Point;
import android.text.Spanned;
import android.text.util.Linkify;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.utils.widget.ImageFilterView;
import androidx.core.text.method.LinkMovementMethodCompat;

import com.dsh.fcl.androidlauncher.R;
import com.tungsten.fcllibrary.component.theme.ThemeEngine;
import com.tungsten.fcllibrary.component.view.FCLButton;
import com.tungsten.fcllibrary.component.view.FCLTextView;
import com.tungsten.fcllibrary.util.ConvertUtils;

public class FCLAlertDialog extends FCLDialog implements View.OnClickListener {

    /**
     * ★ BL-231：对话框里"正文以外"的固定开销（dp）—— 父容器 padding 15×2、正文上下 margin 15×2、
     * 标题行（24dp 图标 / 18sp 单行）、按钮行（约 40dp）。取一个略保守的上界，
     * 让长正文在极端字号下仍能滚到底，而不是又被裁掉最后一行。
     */
    private static final int DIALOG_CHROME_DP = 150;

    private String titleString;

    private ButtonListener positiveListener;
    private ButtonListener negativeListener;
    private ButtonListener neutralListener;
    private ButtonListener extraListener;

    private View parent;
    private ImageFilterView icon;
    private FCLTextView title;
    private ScrollView scrollView;
    private FCLTextView message;
    private FCLButton positive;
    private FCLButton negative;
    private FCLButton neutral;
    private FCLButton extra;

    @SuppressLint("UseCompatLoadingForDrawables")
    public FCLAlertDialog(@NonNull Context context) {
        super(context);

        setContentView(R.layout.dialog_alert);

        parent = findViewById(R.id.parent);

        icon = findViewById(R.id.image);
        title = findViewById(R.id.title);
        scrollView = findViewById(R.id.text_scroll);
        message = findViewById(R.id.text);
        positive = findViewById(R.id.positive);
        negative = findViewById(R.id.negative);
        neutral = findViewById(R.id.neutral);
        extra = findViewById(R.id.extra);

        checkHeight();

        positive.setVisibility(View.GONE);
        negative.setVisibility(View.GONE);
        neutral.setVisibility(View.GONE);
        extra.setVisibility(View.GONE);

        positive.setOnClickListener(this);
        negative.setOnClickListener(this);
        neutral.setOnClickListener(this);
        extra.setOnClickListener(this);

        icon.setImageDrawable(getContext().getDrawable(R.drawable.ic_baseline_info_24));
        title.setText(getContext().getString(R.string.dialog_info));

        positive.setSelected(true);
        negative.setSelected(true);
        neutral.setSelected(true);
        extra.setSelected(true);
    }

    private void checkHeight() {
        parent.post(() -> message.post(() -> {
            WindowManager wm = getWindow().getWindowManager();
            Point point = new Point();
            wm.getDefaultDisplay().getSize(point);
            int maxHeight = point.y - ConvertUtils.dip2px(getContext(), 30);

            // ★ BL-231：判断"放不放得下"必须用**内容所需的完整高度**，不能用 parent 的测量值。
            //   parent 是 wrap_content，它的 measuredHeight 就是"内容全放下的高度"——
            //   但窗口本身可能已经被限制到屏幕内，于是 parent 的测量值恒等于可用高度，
            //   `parent.getMeasuredHeight() < maxHeight` 这条判据**几乎总是成立**，
            //   永远走"放得下"那一支，长正文因此被静默裁掉。
            //
            //   正确算法：内容需要的高度 = 正文完整高度 + 其它部件开销。
            //   正文没有高度限制时的自然高度就是 message.getMeasuredHeight()（它在 ScrollView 里
            //   已经按宽度折行测量过一遍）。
            int chrome = ConvertUtils.dip2px(getContext(), DIALOG_CHROME_DP);
            int neededHeight = message.getMeasuredHeight() + chrome;

            if (neededHeight <= maxHeight) {
                // 放得下：正常按内容撑开（与旧视觉一致）
                ViewGroup.LayoutParams layoutParams = scrollView.getLayoutParams();
                layoutParams.height = message.getMeasuredHeight();
                scrollView.setLayoutParams(layoutParams);
                getWindow().setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
                return;
            }

            // ★ 放不下：把 ScrollView 收到"屏幕可用高度 − 其它部件"，于是它**有滚动余量**。
            //   旧实现只限制窗口高度、ScrollView 仍是 0dp+weight（在 wrap_content 容器里不成立），
            //   结果正文被裁、且滚不动。实测（1.3 倍字号 / 1080x2280）：门禁框正文断在
            //   「也可以点「复制链接」自己」，被切掉的正是最关键那句
            //   「在这台设备上点「仍要继续」不会让这些功能恢复。」—— 那句是 BL-229 特意加的，
            //   字号一调大就看不见，等于没修。
            int available = maxHeight - chrome;
            if (available > 0) {
                ViewGroup.LayoutParams layoutParams = scrollView.getLayoutParams();
                layoutParams.height = available;
                scrollView.setLayoutParams(layoutParams);
            }
            // 窗口跟着 ScrollView 走 WRAP_CONTENT：容器会重新撑到 available + chrome，
            // 既不溢出屏幕，也不会在按钮和正文之间留白（旧实现写死 maxHeight 会留白）。
            getWindow().setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }));
    }

    @Override
    public void onClick(View view) {
        ButtonListener listener = null;
        if (view == positive) listener = positiveListener;
        else if (view == negative) listener = negativeListener;
        else if (view == neutral) listener = neutralListener;
        else if (view == extra) listener = extraListener;

        if (listener != null) {
            listener.onClick();
        }
        dismiss();
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    public void setAlertLevel(AlertLevel alertLevel) {
        switch (alertLevel) {
            case ALERT:
                icon.setImageDrawable(getContext().getDrawable(R.drawable.ic_baseline_warning_24));
                if (titleString == null) {
                    title.setText(getContext().getString(R.string.dialog_alert));
                }
                break;
            default:
                icon.setImageDrawable(getContext().getDrawable(R.drawable.ic_baseline_info_24));
                if (titleString == null) {
                    title.setText(getContext().getString(R.string.dialog_info));
                }
                break;
        }
    }

    public void setTitle(String title) {
        titleString = title;
        this.title.setText(title);
    }

    public void setMessage(String message) {
        this.message.setText(message);
        checkHeight();
    }

    public void setMessage(CharSequence message) {
        this.message.setText(message);
        checkHeight();
    }

    public void setMessage(Spanned message) {
        this.message.setText(message);
        checkHeight();
    }

    public void setPositiveButton(String text, ButtonListener listener) {
        positive.setVisibility(View.VISIBLE);
        positive.setText(text);
        positiveListener = listener;
    }

    public void setNegativeButton(String text, ButtonListener listener) {
        negative.setVisibility(View.VISIBLE);
        negative.setText(text);
        negativeListener = listener;
    }

    public void setNeutralButton(String text, ButtonListener listener) {
        neutral.setVisibility(View.VISIBLE);
        neutral.setText(text);
        neutralListener = listener;
    }

    public void setExtraButton(String text, ButtonListener listener) {
        extra.setVisibility(View.VISIBLE);
        extra.setText(text);
        extraListener = listener;
    }

    public void useAutoLink() {
        Linkify.addLinks(this.message, Linkify.WEB_URLS);
        this.message.setMovementMethod(LinkMovementMethodCompat.getInstance());
        this.message.setLinkTextColor(ThemeEngine.getInstance().getTheme().getColor());
    }

    public static class Builder {

        private Context context;
        private FCLAlertDialog dialog;

        public Builder(Context context) {
            this.context = context;
            dialog = new FCLAlertDialog(context);
        }

        public FCLAlertDialog create() {
            return dialog;
        }

        public Builder setAlertLevel(AlertLevel alertLevel) {
            dialog.setAlertLevel(alertLevel);
            return this;
        }

        public Builder setCancelable(boolean cancelable) {
            dialog.setCancelable(cancelable);
            return this;
        }

        public Builder setTitle(String title) {
            dialog.setTitle(title);
            return this;
        }

        public Builder setMessage(String message) {
            dialog.setMessage(message);
            return this;
        }

        public Builder setMessage(CharSequence message) {
            dialog.setMessage(message);
            return this;
        }

        public Builder setPositiveButton(ButtonListener listener) {
            dialog.setPositiveButton(context.getString(R.string.dialog_positive), listener);
            return this;
        }

        public Builder setPositiveButton(String text, ButtonListener listener) {
            dialog.setPositiveButton(text, listener);
            return this;
        }

        public Builder setNegativeButton(ButtonListener listener) {
            dialog.setNegativeButton(context.getString(R.string.dialog_negative), listener);
            return this;
        }

        public Builder setNegativeButton(String text, ButtonListener listener) {
            dialog.setNegativeButton(text, listener);
            return this;
        }

        public Builder setNeutralButton(String text, ButtonListener listener) {
            dialog.setNeutralButton(text, listener);
            return this;
        }

        public Builder setExtraButton(String text, ButtonListener listener) {
            dialog.setExtraButton(text, listener);
            return this;
        }

        public Builder useAutoLink() {
            dialog.useAutoLink();
            return this;
        }
    }

    public enum AlertLevel {
        ALERT,
        INFO
    }

    public interface ButtonListener {
        void onClick();
    }

}
