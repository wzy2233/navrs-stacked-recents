package com.android.launcher3.navrs;

import android.content.Context;
import android.graphics.Bitmap;

/** Launcher-side forwarders to the NavRS recents activity state. */
public final class NavRsActivity {

    private NavRsActivity() {
    }

    public static void captureHomeShot() {
        NavRsBridge.host().captureHomeShot();
    }

    public static Bitmap peekHomeShot() {
        return NavRsBridge.host().peekHomeShot();
    }

    public static float getDragProgress() {
        return NavRsBridge.host().getDragProgress();
    }

    public static void setDragProgress(float progress) {
        NavRsBridge.host().setDragProgress(progress);
    }

    public static boolean isHomeRelease() {
        return NavRsBridge.host().isHomeRelease();
    }

    public static void setHomeRelease(boolean home) {
        NavRsBridge.host().setHomeRelease(home);
    }

    public static float getLongOut() {
        return NavRsBridge.host().getLongOut();
    }

    public static void setLongOut(float v) {
        NavRsBridge.host().setLongOut(v);
    }

    public static void loadGateFractions(Context context) {
        NavRsBridge.host().loadGateFractions(context);
    }

    public static float getShortFrac() {
        return NavRsBridge.host().getShortFrac();
    }

    public static float getLongFrac() {
        return NavRsBridge.host().getLongFrac();
    }

    public static long getQuickTouchMs() {
        return NavRsBridge.host().getQuickTouchMs();
    }

    public static void launchFromGesture(Context context, boolean fromHome) {
        NavRsBridge.host().launchFromGesture(context, fromHome);
    }

    public static boolean isOpen() {
        return NavRsBridge.host().isActivityOpen();
    }

    public static boolean recentlyExited() {
        return NavRsBridge.host().recentlyExited();
    }

    public static void requestExit() {
        NavRsBridge.host().exitRecentsActivity();
    }
}
