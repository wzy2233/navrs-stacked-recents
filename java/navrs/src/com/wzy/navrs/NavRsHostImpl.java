package com.wzy.navrs;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;

import com.android.launcher3.Launcher;
import com.android.launcher3.navrs.NavRsBridge;
import com.android.quickstep.RecentsActivity;

/** Binds the launcher-side facade to the real NavRS implementation. */
public final class NavRsHostImpl implements NavRsBridge.Host {

    private static final class DeckImpl implements NavRsBridge.Deck {
        private final RecentsView view;

        DeckImpl(RecentsView view) {
            this.view = view;
        }

        @Override
        public void setLongOut(float v) {
            view.setLongOut(v);
        }

        @Override
        public void ensureDragReturn(int index) {
            view.ensureDragReturn(index);
        }

        @Override
        public void setDragReturnProgress(float progress) {
            view.setDragReturnProgress(progress);
        }

        @Override
        public void settleDragReturn(Runnable onFinished) {
            view.settleDragReturn(onFinished);
        }

        @Override
        public boolean isDragReturning() {
            return view.isDragReturning();
        }

        @Override
        public void probeAbsolute(String why) {
            view.probeAbsolute(why);
        }

        @Override
        public boolean isSettled() {
            return view.isSettled();
        }
    }

    @Override
    public void captureHomeShot() {
        NavRsRecentsActivity.captureHomeShot();
    }

    @Override
    public Bitmap peekHomeShot() {
        return NavRsRecentsActivity.peekHomeShot();
    }

    @Override
    public float getDragProgress() {
        return NavRsRecentsActivity.getDragProgress();
    }

    @Override
    public void setDragProgress(float progress) {
        NavRsRecentsActivity.setDragProgress(progress);
    }

    @Override
    public boolean isHomeRelease() {
        return NavRsRecentsActivity.isHomeRelease();
    }

    @Override
    public void setHomeRelease(boolean home) {
        NavRsRecentsActivity.setHomeRelease(home);
    }

    @Override
    public float getLongOut() {
        return NavRsRecentsActivity.getLongOut();
    }

    @Override
    public void setLongOut(float v) {
        NavRsRecentsActivity.setLongOut(v);
    }

    @Override
    public void loadGateFractions(Context context) {
        NavRsRecentsActivity.loadGateFractions(context);
    }

    @Override
    public float getShortFrac() {
        return NavRsRecentsActivity.getShortFrac();
    }

    @Override
    public float getLongFrac() {
        return NavRsRecentsActivity.getLongFrac();
    }

    @Override
    public long getQuickTouchMs() {
        return NavRsRecentsActivity.getQuickTouchMs();
    }

    @Override
    public void launchFromGesture(Context context, boolean fromHome) {
        NavRsRecentsActivity.launchFromGesture(context, fromHome);
    }

    @Override
    public boolean isActivityOpen() {
        return NavRsRecentsActivity.current() != null;
    }

    @Override
    public boolean recentlyExited() {
        return NavRsRecentsActivity.recentlyExited();
    }

    @Override
    public void exitRecentsActivity() {
        NavRsRecentsActivity activity = NavRsRecentsActivity.current();
        if (activity != null) {
            activity.exitToHome();
        }
    }

    @Override
    public void toggleTuning(Context context, android.view.View anchor) {
        NavRsRecentsActivity recents = NavRsRecentsActivity.current();
        if (recents != null) {
            recents.showTuningPanel();
        } else {
            RecentsTuningPanel.toggle(context, anchor);
        }
    }

    @Override
    public NavRsBridge.Deck currentDeck() {
        RecentsView view = RecentsOverlay.current();
        return view == null ? null : new DeckImpl(view);
    }

    @Override
    public void install(Activity activity) {
        RecentsOverlay.install(activity);
    }

    @Override
    public void attachLauncher(Activity activity) {
        if (activity instanceof Launcher) {
            RecentsOverlay.attachLauncher((Launcher) activity);
        }
    }

    @Override
    public void onLauncherStateStart(Activity activity, boolean fromApp) {
        RecentsOverlay.onLauncherStateStart(activity, fromApp);
    }

    @Override
    public void onLauncherState(Activity activity, boolean keepOverview) {
        RecentsOverlay.onLauncherState(activity, keepOverview);
    }

    @Override
    public void startEnter(Activity activity) {
        RecentsOverlay.startEnter(activity);
    }

    @Override
    public void finishLiveTile(Activity activity) {
        RecentsOverlay.finishLiveTile(activity);
    }

    @Override
    public void hideCards(Activity activity) {
        RecentsOverlay.hideCards(activity);
    }

    @Override
    public boolean consumeDirectOverview() {
        return RecentsOverlay.consumeDirectOverview();
    }

    @Override
    public void requestExitToHome() {
        RecentsOverlay.requestExitToHome();
    }

    @Override
    public void offerFrontThumb(Bitmap bitmap) {
        RecentsOverlay.offerFrontThumb(bitmap);
    }

    @Override
    public boolean isExitAnimating() {
        return RecentsView.isExitAnimating();
    }

    @Override
    public long slideOutMs() {
        return RecentsView.SLIDE_OUT_MS;
    }

    @Override
    public void setRunningRect(float left, float top, float right, float bottom) {
        RecentsView.setRunningRect(left, top, right, bottom);
    }
}
