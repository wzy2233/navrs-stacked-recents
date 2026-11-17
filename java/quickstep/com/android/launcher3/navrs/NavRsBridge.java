package com.android.launcher3.navrs;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.ViewGroup;

/**
 * Thin launcher-side facade for the standalone NavRS recents module.
 *
 * <p>The implementation lives in its own build module ({@code navrs}) so that editing
 * the animation code no longer recompiles the whole launcher library. The launcher only
 * depends on this file; the implementation is loaded reflectively and registers itself.
 */
public final class NavRsBridge {

    /** The live deck instance, so the launcher can feed gesture progress. */
    public interface Deck {
        void setLongOut(float v);

        void ensureDragReturn(int index);

        void setDragReturnProgress(float progress);

        void settleDragReturn(Runnable onFinished);

        boolean isDragReturning();

        void probeAbsolute(String why);

        boolean isSettled();
    }

    public interface Host {
        // Activity statics.
        void captureHomeShot();

        Bitmap peekHomeShot();

        float getDragProgress();

        void setDragProgress(float progress);

        boolean isHomeRelease();

        void setHomeRelease(boolean home);

        float getLongOut();

        void setLongOut(float v);

        void loadGateFractions(Context context);

        float getShortFrac();

        float getLongFrac();

        long getQuickTouchMs();

        void launchFromGesture(Context context, boolean fromHome);

        boolean isActivityOpen();

        void exitRecentsActivity();

        boolean recentlyExited();

        void toggleTuning(Context context, android.view.View anchor);

        // Overlay statics.
        Deck currentDeck();

        void install(Activity activity);

        void attachLauncher(Activity activity);

        void onLauncherStateStart(Activity activity, boolean fromApp);

        void onLauncherState(Activity activity, boolean keepOverview);

        void startEnter(Activity activity);

        void finishLiveTile(Activity activity);

        void hideCards(Activity activity);

        boolean consumeDirectOverview();

        void requestExitToHome();

        void offerFrontThumb(Bitmap bitmap);

        // Deck statics.
        boolean isExitAnimating();

        long slideOutMs();

        void setRunningRect(float left, float top, float right, float bottom);
    }

    private static final Host NOOP = new Host() {
        @Override public void captureHomeShot() { }
        @Override public Bitmap peekHomeShot() { return null; }
        @Override public float getDragProgress() { return 0f; }
        @Override public void setDragProgress(float progress) { }
        @Override public boolean isHomeRelease() { return false; }
        @Override public void setHomeRelease(boolean home) { }
        @Override public float getLongOut() { return 0f; }
        @Override public void setLongOut(float v) { }
        @Override public void loadGateFractions(Context context) { }
        @Override public float getShortFrac() { return 0.1f; }
        @Override public float getLongFrac() { return 0.5f; }
        @Override public long getQuickTouchMs() { return 200L; }
        @Override public void launchFromGesture(Context context, boolean fromHome) { }
        @Override public boolean isActivityOpen() { return false; }
        @Override public void exitRecentsActivity() { }
        @Override public boolean recentlyExited() { return false; }
        @Override public void toggleTuning(Context c, android.view.View v) { }
        @Override public Deck currentDeck() { return null; }
        @Override public void install(Activity activity) { }
        @Override public void attachLauncher(Activity activity) { }
        @Override public void onLauncherStateStart(Activity activity, boolean fromApp) { }
        @Override public void onLauncherState(Activity activity, boolean keepOverview) { }
        @Override public void startEnter(Activity activity) { }
        @Override public void finishLiveTile(Activity activity) { }
        @Override public void hideCards(Activity activity) { }
        @Override public boolean consumeDirectOverview() { return false; }
        @Override public void requestExitToHome() { }
        @Override public void offerFrontThumb(Bitmap bitmap) { }
        @Override public boolean isExitAnimating() { return false; }
        @Override public long slideOutMs() { return 280L; }
        @Override public void setRunningRect(float l, float t, float r, float b) { }
    };

    private static volatile Host sHost;

    private NavRsBridge() {
    }

    public static Host host() {
        Host host = sHost;
        if (host != null) {
            return host;
        }
        synchronized (NavRsBridge.class) {
            host = sHost;
            if (host == null) {
                try {
                    host = (Host) Class.forName("com.wzy.navrs.NavRsHostImpl")
                            .getDeclaredConstructor().newInstance();
                } catch (Throwable t) {
                    host = NOOP;
                }
                sHost = host;
            }
        }
        return host;
    }
}
