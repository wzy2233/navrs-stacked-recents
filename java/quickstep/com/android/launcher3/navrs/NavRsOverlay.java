package com.android.launcher3.navrs;

import android.app.Activity;
import android.graphics.Bitmap;

/** Launcher-side forwarders to the NavRS overlay implementation. */
public final class NavRsOverlay {

    private NavRsOverlay() {
    }

    public static NavRsBridge.Deck current() {
        return NavRsBridge.host().currentDeck();
    }

    public static void install(Activity activity) {
        NavRsBridge.host().install(activity);
    }

    public static void attachLauncher(Activity activity) {
        NavRsBridge.host().attachLauncher(activity);
    }

    public static void onLauncherStateStart(Activity activity, boolean fromApp) {
        NavRsBridge.host().onLauncherStateStart(activity, fromApp);
    }

    public static void onLauncherState(Activity activity, boolean keepOverview) {
        NavRsBridge.host().onLauncherState(activity, keepOverview);
    }

    public static void startEnter(Activity activity) {
        NavRsBridge.host().startEnter(activity);
    }

    public static void finishLiveTile(Activity activity) {
        NavRsBridge.host().finishLiveTile(activity);
    }

    public static void hideCards(Activity activity) {
        NavRsBridge.host().hideCards(activity);
    }

    public static boolean consumeDirectOverview() {
        return NavRsBridge.host().consumeDirectOverview();
    }

    public static void requestExitToHome() {
        NavRsBridge.host().requestExitToHome();
    }

    public static void offerFrontThumb(Bitmap bitmap) {
        NavRsBridge.host().offerFrontThumb(bitmap);
    }
}
