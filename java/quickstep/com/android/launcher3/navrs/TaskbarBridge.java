package com.wzy.navrs;

import android.util.Log;
import android.view.View;

import com.android.launcher3.taskbar.TaskbarNavButtonController;

/**
 * Bridges clicks from the Rust bar to Launcher3's own TaskbarNavButtonController so we reuse the
 * stock behaviour (predictive back, overview toggle, assistant long press, screen pinning).
 */
public final class TaskbarBridge {

    private static final String TAG = "NavRS";

    static final int BUTTON_BACK = 1;
    static final int BUTTON_HOME = 2;
    static final int BUTTON_RECENTS = 4;

    private static volatile TaskbarNavButtonController sController;

    private TaskbarBridge() {
    }

    static void setController(TaskbarNavButtonController controller) {
        sController = controller;
        Log.d(TAG, "TaskbarNavButtonController captured");
    }

    static void click(int button, View source) {
        TaskbarNavButtonController controller = sController;
        if (controller == null) {
            Log.d(TAG, "no TaskbarNavButtonController yet, button=" + button);
            return;
        }
        try {
            controller.onButtonClick(button, source);
        } catch (Throwable t) {
            Log.d(TAG, "onButtonClick failed", t);
        }
    }
}
