package com.android.launcher3.navrs;

/** Launcher-side forwarders to NavRS deck-wide state. */
public final class NavRsView {

    private NavRsView() {
    }

    public static boolean isExitAnimating() {
        return NavRsBridge.host().isExitAnimating();
    }

    public static long slideOutMs() {
        return NavRsBridge.host().slideOutMs();
    }

    public static void setRunningRect(float left, float top, float right, float bottom) {
        NavRsBridge.host().setRunningRect(left, top, right, bottom);
    }
}
