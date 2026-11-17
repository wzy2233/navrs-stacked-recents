package com.wzy.navrs;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.launcher3.taskbar.TaskbarNavButtonController;

/**
 * Installs the Rust-drawn navigation bar on top of Launcher3's three-button Taskbar.
 */
public final class NavRsIntegration {

    private static final String TAG = "NavRS";

    private static ViewGroup sStockContainer;

    private NavRsIntegration() {
    }

    public static void install(ViewGroup navButtonsView, ViewGroup stockContainer,
            TaskbarNavButtonController controller) {
        if (navButtonsView.findViewWithTag("navrs") != null) {
            hideStockButtons();
            return;
        }
        TaskbarBridge.setController(controller);
        sStockContainer = stockContainer;
        NavBarView view = new NavBarView(navButtonsView.getContext(), true);
        view.setTag("navrs");
        navButtonsView.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        hideStockButtons();
        Log.d(TAG, "installed on " + navButtonsView.getClass().getSimpleName());
    }

    public static void hideStockButtons() {
        if (sStockContainer != null) {
            // Only alpha: the stock drawables are animated by LauncherAnimUtils and must stay
            // non-null or the process crashes on IME changes.
            sStockContainer.setAlpha(0f);
            sStockContainer.setVisibility(View.INVISIBLE);
        }
    }
}
