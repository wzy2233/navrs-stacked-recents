package com.wzy.navrs;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.launcher3.Launcher;

/**
 * Floating controls for the Rust carousel, mirroring the demo activity's five
 * buttons. Opened with a long press on the recents key so the normal overview
 * stays chrome-free.
 */
public final class RecentsTuningPanel {

    private static final String TAG = "NavRS";
    private static final String TAG_PANEL = "navrs_tuning_panel";
    private static RecentsView sTarget;

    private static final String[] MODES = {
            "样式: 分开", "样式: 叠压", "样式: 扇形",
            "样式: 叠放", "样式: 堆叠", "样式: 齿轮"
    };

    private interface Cycler {
        String next();
    }

    private RecentsTuningPanel() {
    }

    public static void toggle(Context context, View anchor) {
        Launcher launcher = null;
        try {
            launcher = Launcher.getLauncher(context);
        } catch (Throwable t) {
            // The nav bar lives in the taskbar window; its context does not always
            // lead back to the Launcher activity.
            Log.w(TAG, "tuning: context has no launcher");
        }
        if (launcher == null) {
            launcher = RecentsOverlay.launcher();
        }
        if (launcher == null) {
            Log.w(TAG, "tuning: no launcher for " + context);
            return;
        }
        final ViewGroup layer = (ViewGroup) launcher.getWindow().getDecorView();
        View existing = layer.findViewWithTag(TAG_PANEL);
        if (existing != null) {
            layer.removeView(existing);
            return;
        }
        layer.addView(build(launcher, null), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        Log.i(TAG, "tuning panel shown");
    }

    /** Standalone host (recents activity): attach to its decor and target its carousel. */
    public static void toggle(Activity activity, View anchor, RecentsView target) {
        final ViewGroup layer = (ViewGroup) activity.getWindow().getDecorView();
        View existing = layer.findViewWithTag(TAG_PANEL);
        if (existing != null) {
            layer.removeView(existing);
            return;
        }
        layer.addView(build(activity, target), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        Log.i(TAG, "tuning panel shown (activity)");
    }

    private static View build(Context context, final RecentsView target) {
        sTarget = target;
        FrameLayout root = new FrameLayout(context);
        root.setTag(TAG_PANEL);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        column.setBackground(round(0xE6141820, dp(context, 14)));

        TextView title = new TextView(context);
        title.setText("NAVRS 后台调节");
        title.setTextSize(11);
        title.setTextColor(0xFF9AA0A6);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(context, 8));
        column.addView(title);

        column.addView(chip(context,
                NavRsPrefs.fastTransition(context) ? "动画: 快" : "动画: 柔", () -> {
                    boolean fast = !NavRsPrefs.fastTransition(context);
                    NavRsPrefs.setFastTransition(context, fast);
                    return fast ? "动画: 快" : "动画: 柔";
                }));

        TextView close = chip(context, "关闭面板", () -> "关闭面板");
        final View panel = root;
        close.setOnClickListener(v -> {
            ViewGroup parent = (ViewGroup) panel.getParent();
            if (parent != null) {
                parent.removeView(panel);
            }
        });
        column.addView(close);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(context, 84);
        root.addView(column, lp);
        return root;
    }

    private static TextView chip(Context context, String initial, final Cycler cycler) {
        TextView tv = new TextView(context);
        tv.setText(initial);
        tv.setTextSize(14);
        tv.setTextColor(0xFFE8EAED);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        tv.setBackground(round(0x33FFFFFF, dp(context, 10)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 6);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> {
            tv.setText(cycler.next());
            RecentsView live = sTarget != null ? sTarget : RecentsOverlay.current();
            if (live != null) {
                NavRsPrefs.apply(live.getContext(), live);
            }
        });
        return tv;
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
