package com.wzy.navrs;

import android.app.Activity;
import android.app.ActivityOptions;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.ImageView;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.quickstep.RecentsActivity;
import com.android.quickstep.views.TaskContainer;
import com.android.quickstep.views.TaskThumbnailViewDeprecated;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Overlays the Rust recents carousel on Launcher3's live RecentsView (the overview state of the
 * launcher) and on the standalone RecentsActivity fallback. Uses the real TaskView thumbnails and
 * the stock task objects for launching and dismissal.
 */
public final class RecentsOverlay {

    private static final String TAG = "NavRS";
    private static final String TAG_VIEW = "navrs_recents";
    private static final String TAG_TUNING = "navrs_tuning_panel";
    private static final String TAG_BACKDROP = "navrs_backdrop";
    /** Software copy size fed to the carousel; matches the on-screen card resolution. */
    private static final int THUMB_MAX_W = 720;
    private static final int THUMB_MAX_H = 1440;
    private static final int MAX_CARDS = 8;
    private static final ExecutorService THUMB_EXECUTOR = Executors.newSingleThreadExecutor();

    /** The carousel currently on screen, so the tuning panel can update it live. */
    private static java.lang.ref.WeakReference<RecentsView> sCurrent =
            new java.lang.ref.WeakReference<>(null);
    /** Last launcher activity, so the tuning panel can attach to its drag layer. */
    private static java.lang.ref.WeakReference<Launcher> sLauncher =
            new java.lang.ref.WeakReference<>(null);
    private static boolean sInstallPending;
    private static boolean sEnterFromApp;
    private static boolean sDirectOverview;
    private static boolean sFadeInNextInstall;
    private static int sContentTop;
    private static int sContentBottom;
    private static int sInstalledFirstId = -1;

    /** Thumbnail array kept aligned with the carousel while cards get dismissed. */
    private static final class ThumbState {
        final ArrayList<TaskView> taskViews;
        final ViewGroup panel;
        Bitmap[] thumbs;
        int[] taskIds;
        int generation;

        ThumbState(ArrayList<TaskView> taskViews, ViewGroup panel) {
            this.taskViews = taskViews;
            this.panel = panel;
            this.thumbs = new Bitmap[taskViews.size()];
            this.taskIds = new int[taskViews.size()];
            for (int i = 0; i < taskViews.size(); i++) {
                taskIds[i] = taskIdOf(taskViews.get(i));
            }
        }

        void remove(int index) {
            generation++;
            if (index >= 0 && index < taskViews.size()) {
                taskViews.remove(index);
            }
            if (index >= 0 && index < thumbs.length) {
                Bitmap[] shrunk = new Bitmap[thumbs.length - 1];
                int[] ids = new int[thumbs.length - 1];
                int w = 0;
                for (int i = 0; i < thumbs.length; i++) {
                    if (i != index) {
                        shrunk[w] = thumbs[i];
                        ids[w] = taskIds[i];
                        w++;
                    }
                }
                thumbs = shrunk;
                taskIds = ids;
            }
        }
    }

    private static String taskPackage(TaskView tv) {
        try {
            com.android.systemui.shared.recents.model.Task task =
                    tv.getTaskContainers().get(0).getTask();
            android.content.ComponentName component = task.key.baseIntent == null
                    ? null : task.key.baseIntent.getComponent();
            if (component != null) {
                return component.getPackageName();
            }
            return task.key.baseIntent == null ? "?" : String.valueOf(task.key.baseIntent.getPackage());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int taskIdOf(TaskView tv) {
        try {
            return tv.getTaskContainers().get(0).getTask().key.id;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static TaskView findTaskViewById(ViewGroup panel, int taskId) {
        if (panel == null || taskId < 0) {
            return null;
        }
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child instanceof TaskView && taskIdOf((TaskView) child) == taskId) {
                return (TaskView) child;
            }
        }
        return null;
    }

    private RecentsOverlay() {
    }

    /** Skip the shell recents animation; the next overview install fades the Rust UI in. */
    public static void requestDirectOverview() {
        sDirectOverview = true;
        sFadeInNextInstall = true;
    }

    public static void cancelDirectOverview() {
        sDirectOverview = false;
        sFadeInNextInstall = false;
    }

    public static boolean consumeDirectOverview() {
        boolean value = sDirectOverview;
        sDirectOverview = false;
        return value;
    }

    public static RecentsView current() {
        return sCurrent.get();
    }

    static Launcher launcher() {
        return sLauncher.get();
    }

    public static void attachLauncher(Launcher launcher) {
        sLauncher = new java.lang.ref.WeakReference<>(launcher);
    }

    /** The carousel is full-screen, including the status bar area. */
    /** Opaque blurred wallpaper + scrim behind the carousel (gesture overview). */
    private static void installBackdrop(Activity activity, ViewGroup parent) {
        removeBackdrop(parent);
        ImageView wall = new ImageView(activity);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        boolean loaded = false;
        try {
            Drawable drawable = android.app.WallpaperManager.getInstance(activity).getDrawable();
            if (drawable != null) {
                wall.setImageDrawable(drawable);
                loaded = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "backdrop wallpaper failed", t);
        }
        if (!loaded) {
            wall.setBackgroundColor(0xFF10141A);
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                wall.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        52f, 52f, android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable t) {
                Log.w(TAG, "backdrop blur failed", t);
            }
        }
        wall.setTag(TAG_BACKDROP);
        parent.addView(wall, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(activity);
        scrim.setBackgroundColor(0x66000000);
        scrim.setTag(TAG_BACKDROP);
        parent.addView(scrim, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }


    /** Resources that live in the quickstep resource library: resolve by name. */
    private static int navRsId(android.content.Context context, String name) {
        return context.getResources().getIdentifier(name, "id", "com.android.launcher3");
    }
    private static void removeBackdrop(ViewGroup parent) {
        if (parent == null) {
            return;
        }
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);
            if (TAG_BACKDROP.equals(child.getTag())) {
                parent.removeViewAt(i);
            }
        }
    }

    private static void restoreStock(Activity activity) {
        if (activity == null) {
            return;
        }
        View panel = activity.findViewById(R.id.overview_panel);
        if (panel != null) {
            panel.setVisibility(View.VISIBLE);
        }
        View actions = activity.findViewById(navRsId(activity, "overview_actions_view"));
        if (actions != null) {
            actions.setVisibility(View.VISIBLE);
        }
    }

    private static ViewGroup overlayParent(Activity activity) {
        return (ViewGroup) activity.getWindow().getDecorView();
    }

    private static int[] contentInsets(Activity activity) {
        try {
            android.view.WindowInsets insets = activity.getWindow().getDecorView()
                    .getRootWindowInsets();
            if (insets != null) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars());
                return new int[]{bars.top, bars.bottom};
            }
        } catch (Throwable t) {
            Log.w(TAG, "content insets unavailable", t);
        }
        return new int[]{0, 0};
    }

    private static float stockCornerPx(Activity activity) {
        try {
            float radius = com.android.quickstep.util.TaskCornerRadius.get(activity);
            if (radius > 0f) {
                return radius;
            }
        } catch (Throwable t) {
            // fall through
        }
        return 28f * activity.getResources().getDisplayMetrics().density;
    }

    private static void setImmersiveBars(Activity activity, boolean on) {
        try {
            Window window = activity.getWindow();
            WindowInsetsController controller = window.getInsetsController();
            if (on) {
                window.setStatusBarColor(Color.TRANSPARENT);
                if (controller != null) {
                    controller.setSystemBarsAppearance(0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                }
            } else if (controller != null) {
                controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } catch (Throwable t) {
            Log.w(TAG, "immersive bars failed", t);
        }
    }

    /** Called from RecentsActivity.onResume (fallback recents activity). */
    public static void install(Activity activity) {
        if (!(activity instanceof RecentsActivity)) {
            return;
        }
        final ViewGroup layer = activity.findViewById(R.id.drag_layer);
        final View panel = activity.findViewById(R.id.overview_panel);
        final View actions = activity.findViewById(navRsId(activity, "overview_actions_view"));
        if (layer == null || !(panel instanceof ViewGroup)) {
            return;
        }
        if (overlayParent(activity).findViewWithTag(TAG_VIEW) != null) {
            return;
        }
        final ViewGroup panelGroup = (ViewGroup) panel;
        final int[] tries = {0};
        layer.post(new Runnable() {
            @Override
            public void run() {
                ArrayList<TaskView> taskViews = collectTaskViews(panelGroup);
                if (taskViews.isEmpty() || !allLaidOut(taskViews)) {
                    if (tries[0]++ < 40) {
                        layer.postDelayed(this, 100);
                    }
                    return;
                }
                try {
                    installNow(activity, layer, panel, actions, taskViews, true, false);
                } catch (Throwable t) {
                    Log.e(TAG, "recents overlay failed", t);
                }
            }
        });
    }

    /**
     * Called from QuickstepLauncher.onStateSetStart. BACKGROUND_APP means the shell is
     * starting the app-to-overview shrink, so the carousel can be mounted before the
     * stock cards are ever shown.
     */
    public static void onLauncherStateStart(Activity activity, boolean fromApp) {
        if (activity == null || activity.findViewById(R.id.drag_layer) == null) {
            return;
        }
        if (fromApp) {
            // The same transient state is used when leaving overview to launch a task.
            // An attached carousel means the user is on the way out, not in.
            View existing = overlayParent(activity).findViewWithTag(TAG_VIEW);
            if (existing != null) {
                return;
            }
            sEnterFromApp = true;
        }
        installWhenReady(activity);
    }

    /** Called from QuickstepLauncher.onStateSetEnd. */
    public static void onLauncherState(Activity activity, boolean keepOverview) {
        if (activity == null) {
            return;
        }
        ViewGroup layer = activity.findViewById(R.id.drag_layer);
        if (layer == null) {
            return;
        }
        if (!keepOverview) {
            // The launcher state can flip to NORMAL 40ms into our slide-out (the system
            // home gesture runs in parallel). Removing the overlay here made the cards
            // vanish instead of sliding away, so defer until the animation ends.
            if (com.wzy.navrs.RecentsView.isExitAnimating()) {
                final long remaining = Math.max(0L,
                        com.wzy.navrs.RecentsView.exitAnimRemainingMs());
                Log.d("NavRSProbe", "overlay removal deferred by " + remaining + "ms");
                overlayParent(activity).postDelayed(() -> {
                    View delayed = overlayParent(activity).findViewWithTag(TAG_VIEW);
                    if (delayed != null) {
                        overlayParent(activity).removeView(delayed);
                    }
                    removeBackdrop(overlayParent(activity));
                    if (!(activity instanceof Launcher)) {
                        restoreStock(activity);
                    }
                    sCurrent.clear();
                    sEnterFromApp = false;
                    setImmersiveBars(activity, false);
                }, remaining + 20);
                return;
            }
            View existing = overlayParent(activity).findViewWithTag(TAG_VIEW);
            if (existing != null) {
                overlayParent(activity).removeView(existing);
            }
            removeBackdrop(overlayParent(activity));
            restoreStock(activity);
            sCurrent.clear();
            sEnterFromApp = false;
            setImmersiveBars(activity, false);
            return;
        }
        View existing = overlayParent(activity).findViewWithTag(TAG_VIEW);
        if (existing == null) {
            installWhenReady(activity);
        } else {
            refreshIfStale(activity, layer, existing);
        }
    }

    private static void refreshIfStale(Activity activity, ViewGroup layer, View existing) {
        View panel = activity.findViewById(R.id.overview_panel);
        if (!(existing instanceof RecentsView) || !(panel instanceof ViewGroup)) {
            return;
        }
        int count = collectTaskViews((ViewGroup) panel).size();
        if (((RecentsView) existing).getCardCount() != count) {
            Log.i(TAG, "task count changed, rebuilding overlay");
            overlayParent(activity).removeView(existing);
            sCurrent.clear();
            installWhenReady(activity);
        }
    }

    /**
     * Live screenshot of the app that is becoming the front card. Taken from the
     * shell at the moment the gesture commits to overview, before the live tile
     * goes away, so the card never shows an empty slot.
     */
    public static void offerFrontThumb(final android.graphics.Bitmap source) {
        final com.wzy.navrs.RecentsView rust = sCurrent.get();
        Log.d("NavRSProbe", "frontThumb offered=" + (source != null)
                + " view=" + (rust != null)
                + " size=" + (source == null ? "null"
                        : source.getWidth() + "x" + source.getHeight()));
        if (rust == null || source == null) {
            return;
        }
        THUMB_EXECUTOR.execute(() -> {
            final Bitmap soft = scaleThumbnail(source);
            if (soft == null) {
                return;
            }
            rust.post(() -> {
                if (rust.getParent() != null) {
                    rust.setFrontThumb(soft);
                }
            });
        });
    }

    /** Gesture layer asked to leave overview (quick swipe up on the nav pill). */
    public static void requestExitToHome() {
        final com.wzy.navrs.RecentsView rust = sCurrent.get();
        if (rust == null) {
            return;
        }
        Log.d("NavRSProbe", "requestExitToHome");
        rust.post(rust::startSlideOutToLeft);
    }

    /** Long swipe to home: stop drawing our cards so the shell's icon flight shows. */
    public static void hideCards(Activity activity) {
        if (activity == null) {
            return;
        }
        View rv = overlayParent(activity).findViewWithTag(TAG_VIEW);
        if (rv != null) {
            rv.setAlpha(0f);
            rv.setVisibility(View.INVISIBLE);
        }
    }

    /** Runs the settle from the stock card rect into the active layout. */
    public static void startEnter(Activity activity) {
        if (activity != null) {
            ViewGroup layer = activity.findViewById(R.id.drag_layer);
            if (layer != null) {
                View rv = overlayParent(activity).findViewWithTag(TAG_VIEW);
                if (rv instanceof RecentsView) {
                    // Gesture path: settle the drag-driven interpolation. Falls back to
                    // the stock enter when the carousel is not in drag mode.
                    ((RecentsView) rv).settleDragReturn(null);
                }
            }
        }
        sEnterFromApp = false;
    }

    /** Waits for the TaskViews of the incoming overview and mounts the carousel on top of them. */
    private static void installWhenReady(final Activity activity) {
        if (sInstallPending) {
            return;
        }
        final ViewGroup layer = activity.findViewById(R.id.drag_layer);
        if (layer == null) {
            return;
        }
        final View panel = activity.findViewById(R.id.overview_panel);
        if (!(panel instanceof ViewGroup)) {
            return;
        }
        final View actions = activity.findViewById(navRsId(activity, "overview_actions_view"));
        sInstallPending = true;
        final int[] tries = {0};
        layer.post(new Runnable() {
            @Override
            public void run() {
                if (overlayParent(activity).findViewWithTag(TAG_VIEW) != null) {
                    finishPending();
                    return;
                }
                if (!stillInOverview(activity)) {
                    finishPending();
                    return;
                }
                ArrayList<TaskView> taskViews = collectTaskViews((ViewGroup) panel);
                if (taskViews.isEmpty() || !allLaidOut(taskViews)) {
                    if (tries[0]++ < 60) {
                        layer.postDelayed(this, 30);
                    } else {
                        finishPending();
                    }
                    return;
                }
                boolean enterFromApp = sEnterFromApp && !isOverview(activity);
                try {
                    sortAndInstall(activity, layer, panel, actions, taskViews, enterFromApp);
                } catch (Throwable t) {
                    Log.e(TAG, "early recents overlay failed", t);
                }
                finishPending();
            }
        });
    }

    private static void finishPending() {
        sInstallPending = false;
        sEnterFromApp = false;
    }

    /**
     * Orders the cards by real recency (newest first) before installing. The stock
     * overview's child order depends on its pager state and is not reliable when the
     * overview is opened straight from an app.
     */
    private static void sortAndInstall(final Activity activity, final ViewGroup layer,
            final View panel, final View actions, final ArrayList<TaskView> taskViews,
            final boolean enterFromApp) {
        final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable proceed = () -> {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            try {
                installNow(activity, layer, panel, actions, taskViews, false, enterFromApp);
            } catch (Throwable t) {
                Log.e(TAG, "recents overlay install failed", t);
            }
        };
        // Give the recents model one frame or two to hand back the true order, so the
        // carousel is installed once with the right list (no brief wrong mapping).
        layer.postDelayed(proceed, 120);
        try {
            com.android.quickstep.RecentsModel.INSTANCE.get(activity).getTasks(groups -> {
                try {
                    if (groups != null) {
                        final java.util.HashMap<Integer, Integer> rank = new java.util.HashMap<>();
                        final StringBuilder order = new StringBuilder();
                        int r = 0;
                        for (com.android.quickstep.util.GroupTask group : groups) {
                            for (com.android.systemui.shared.recents.model.Task task
                                    : group.getTasks()) {
                                rank.put(task.key.id, r);
                                order.append(task.key.id).append(',');
                            }
                            r++;
                        }
                        Log.i(TAG, "recents model order: " + order);
                        // The recents model lists tasks oldest -> newest.
                        taskViews.sort((a, b) -> Integer.compare(
                                rankFor(rank, taskIdOf(b)), rankFor(rank, taskIdOf(a))));
                        if (!taskViews.isEmpty()) {
                            Log.i(TAG, "first card id after sort: " + taskIdOf(taskViews.get(0)));
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "task ordering failed", t);
                }
                activity.getWindow().getDecorView().post(
                        () -> rebuildIfOrderChanged(activity, layer, panel, actions, taskViews));
            });
        } catch (Throwable t) {
            Log.w(TAG, "task list request failed", t);
        }
    }

    private static void rebuildIfOrderChanged(Activity activity, ViewGroup layer, View panel,
            View actions, ArrayList<TaskView> taskViews) {
        if (taskViews.isEmpty() || !stillInOverview(activity)
                || !(panel instanceof ViewGroup)) {
            return;
        }
        int first = taskIdOf(taskViews.get(0));
        if (first == sInstalledFirstId) {
            return;
        }
        View existing = overlayParent(activity).findViewWithTag(TAG_VIEW);
        if (existing == null) {
            return;
        }
        Log.i(TAG, "recency order changed " + sInstalledFirstId + "->" + first + ", rebuilding");
        overlayParent(activity).removeView(existing);
        sCurrent.clear();
        try {
            installNow(activity, layer, (ViewGroup) panel, actions, taskViews, false, false);
        } catch (Throwable t) {
            Log.e(TAG, "recents overlay rebuild failed", t);
        }
    }

    private static int rankFor(java.util.HashMap<Integer, Integer> rank, int taskId) {
        Integer value = rank.get(taskId);
        return value == null ? Integer.MAX_VALUE : value;
    }

    private static boolean isOverview(Activity activity) {
        return activity instanceof Launcher
                && ((Launcher) activity).isInState(LauncherState.OVERVIEW);
    }

    private static boolean stillInOverview(Activity activity) {
        if (activity instanceof Launcher) {
            Launcher launcher = (Launcher) activity;
            return launcher.isInState(LauncherState.OVERVIEW)
                    || launcher.isInState(LauncherState.BACKGROUND_APP);
        }
        return activity instanceof RecentsActivity;
    }

    /**
     * Only TaskViews that are actually bound to a task. The overview panel keeps
     * recycled, unbound TaskViews in its hierarchy; treating those as cards produced
     * grey phantom pages when only a couple of apps were open.
     */
    /** Tasks whose snapshot is ready, plus the running task the user came from. */
    private static ArrayList<TaskView> withSnapshots(ArrayList<TaskView> all, TaskView front) {
        ArrayList<TaskView> out = new ArrayList<>();
        for (TaskView tv : all) {
            if (tv == front || hasSnapshot(tv)) {
                out.add(tv);
            }
        }
        return out;
    }

    private static boolean hasSnapshot(TaskView tv) {
        View snap = tv.findViewById(navRsId(tv.getContext(), "snapshot"));
        return findThumbnailBitmap(snap != null ? snap : tv) != null;
    }

    private static TaskView currentFrontTaskView(Activity activity) {
        View panel = activity.findViewById(R.id.overview_panel);
        if (panel instanceof com.android.quickstep.views.RecentsView) {
            return ((com.android.quickstep.views.RecentsView) panel).getCurrentPageTaskView();
        }
        return null;
    }

    /** Adds cards that were skipped because their snapshot had not arrived yet. */
    private static void scheduleSnapshotRebuild(final Activity activity, final ViewGroup layer,
            final int installed) {
        final int[] tries = {0};
        layer.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!stillInOverview(activity)) {
                    return;
                }
                View existing = overlayParent(activity).findViewWithTag(TAG_VIEW);
                View panel = activity.findViewById(R.id.overview_panel);
                if (existing == null || !(panel instanceof ViewGroup)) {
                    return;
                }
                int ready = withSnapshots(collectTaskViews((ViewGroup) panel),
                        currentFrontTaskView(activity)).size();
                if (ready > installed) {
                    Log.i(TAG, "snapshots ready " + installed + "->" + ready + ", rebuilding");
                    overlayParent(activity).removeView(existing);
                    sCurrent.clear();
                    installWhenReady(activity);
                } else if (tries[0]++ < 10) {
                    layer.postDelayed(this, 400);
                }
            }
        }, 800);
    }

    /** Ends the live-tile recents animation so later taps use the static launch path. */
    public static void finishLiveTile(Activity activity) {
        View panel = activity.findViewById(R.id.overview_panel);
        if (panel instanceof com.android.quickstep.views.RecentsView) {
            try {
                ((com.android.quickstep.views.RecentsView) panel)
                        .finishRecentsAnimation(true, null);
                Log.i(TAG, "live tile finished");
            } catch (Throwable t) {
                Log.w(TAG, "finish live tile failed", t);
            }
        }
    }

    private static ArrayList<TaskView> collectTaskViews(ViewGroup group) {
        ArrayList<TaskView> taskViews = new ArrayList<>();
        for (int i = 0; i < group.getChildCount() && taskViews.size() < MAX_CARDS; i++) {
            View c = group.getChildAt(i);
            if (c instanceof TaskView && isBound((TaskView) c) && !isLauncherTask((TaskView) c)) {
                taskViews.add((TaskView) c);
            }
        }
        return taskViews;
    }

    /** The launcher's own task must never appear as a recent app card. */
    private static boolean isLauncherTask(TaskView tv) {
        try {
            com.android.systemui.shared.recents.model.Task task =
                    tv.getTaskContainers().get(0).getTask();
            if (task.key == null) {
                return false;
            }
            android.content.ComponentName component = task.key.baseIntent == null
                    ? null : task.key.baseIntent.getComponent();
            String pkg = component != null
                    ? component.getPackageName() : task.key.baseIntent == null
                    ? null : task.key.baseIntent.getPackage();
            return "com.android.launcher3".equals(pkg);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isBound(TaskView tv) {
        try {
            java.util.List<TaskContainer> containers = tv.getTaskContainers();
            return containers != null && !containers.isEmpty()
                    && containers.get(0).getTask() != null
                    && containers.get(0).getTask().key != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean allLaidOut(ArrayList<TaskView> taskViews) {
        for (TaskView tv : taskViews) {
            if (tv.getWidth() < 10 || tv.getHeight() < 10) {
                return false;
            }
        }
        return true;
    }

    private static void installNow(Activity activity, ViewGroup layer, View panel, View actions,
            ArrayList<TaskView> taskViews, boolean hideStock, boolean enterFromApp) {
        final int n = taskViews.size();
        final com.wzy.navrs.RecentsView rust = new com.wzy.navrs.RecentsView(activity, n);
        // The view defaults to an opaque dark fill; on the launcher gesture path let
        // the blurred backdrop behind it show through.
        if (activity instanceof Launcher) {
            rust.setBackgroundColor(Color.TRANSPARENT);
        }
        sCurrent = new java.lang.ref.WeakReference<>(rust);
        NavRsPrefs.apply(activity, rust);
        int[] insets = contentInsets(activity);
        sContentTop = insets[0];
        sContentBottom = insets[1];
        // Cards now keep the full window aspect (inset bands are masked, not cropped).
        rust.setContentInsets(0, 0);
        final ThumbState thumbs = new ThumbState(taskViews, (ViewGroup) panel);
        sInstalledFirstId = thumbs.taskIds.length > 0 ? thumbs.taskIds[0] : -1;
        for (int i = 0; i < Math.min(6, thumbs.taskIds.length); i++) {
            Log.i(TAG, "card " + i + " id=" + thumbs.taskIds[i]
                    + " pkg=" + taskPackage(taskViews.get(i)));
        }
        thumbs.thumbs[0] = captureThumbnail(taskViews.get(0));
        // Never leave a slot blank: the shell's live card disappears the moment the
        // overview hand-off finishes, so every card needs a picture right away.
        for (int i = 0; i < thumbs.thumbs.length; i++) {
            if (thumbs.thumbs[i] == null) {
                thumbs.thumbs[i] = captureIconCard(taskViews.get(i));
            }
        }
        rust.setThumbnails(thumbs.thumbs);
        loadSnapshots(activity, rust, thumbs);
        refreshThumbnails(rust, thumbs, 0);
        rust.setOnCardTap(index -> {
            if (index < 0 || index >= thumbs.taskIds.length) {
                return;
            }
            final int taskId = thumbs.taskIds[index];
            Log.i(TAG, "tap card " + index + " id=" + taskId);
            rust.startLaunch(index, () -> {
                // The card picture was captured for this task id; launching by id
                // keeps the destination in sync even if the stock view was recycled.
                if (taskId >= 0) {
                    launchTaskById(activity, taskId);
                } else {
                    TaskView tv = index < thumbs.taskViews.size() ? thumbs.taskViews.get(index) : null;
                    if (tv != null) {
                        launchWithoutTransition(activity, tv);
                    }
                }
            });
        });
        rust.setOnCardDismiss(index -> {
            if (index < 0 || index >= thumbs.taskIds.length) {
                return;
            }
            final int taskId = thumbs.taskIds[index];
            thumbs.remove(index);
            // The carousel has already compacted its own array; keep ours identical.
            rust.setThumbnails(thumbs.thumbs);
            refreshThumbnails(rust, thumbs, 0);
            com.android.quickstep.views.RecentsView stock = null;
            if (panel instanceof com.android.quickstep.views.RecentsView) {
                stock = (com.android.quickstep.views.RecentsView) panel;
            } else if (activity instanceof RecentsActivity) {
                stock = ((RecentsActivity) activity).getOverviewPanel();
            }
            TaskView tv = findTaskViewById((ViewGroup) panel, taskId);
            if (stock != null && tv != null) {
                stock.dismissTask(tv, false, true);
            }
            Log.i(TAG, "dismiss task id=" + taskId + " at " + index
                    + ", remaining=" + thumbs.taskViews.size());
        });
        rust.setOnExitStart(() -> {
            if (activity instanceof com.android.launcher3.uioverrides.QuickstepLauncher) {
                ((com.android.launcher3.uioverrides.QuickstepLauncher) activity)
                        .fadeNavRsDragBackdropForExit();
            }
        });
        rust.setOnExit(() -> {
            if (activity instanceof Launcher) {
                ((Launcher) activity).getStateManager().goToState(LauncherState.NORMAL, true);
            } else {
                activity.finish();
            }
        });
        rust.setTag(TAG_VIEW);
        if (hideStock) {
            panel.setVisibility(View.GONE);
            if (actions != null) {
                actions.setVisibility(View.GONE);
            }
        }
        // The drag layer is inset by the system bars; extend the carousel into the
        // bottom inset so a card growing to full screen really reaches the screen
        // edge (otherwise the app window jumps taller at the end of the launch).
        final ViewGroup parent = overlayParent(activity);
        if (activity instanceof Launcher) {
            // The launcher already installs our own blurred backdrop (mNavRsDragWall in
            // QuickstepLauncher) below this view, and it can fade out smoothly on a
            // long swipe. Do not add a second, instantly-removed backdrop here.
            panel.setVisibility(View.INVISIBLE);
            if (actions != null) {
                actions.setVisibility(View.INVISIBLE);
            }
        }
        parent.addView(rust, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (activity instanceof Launcher) {
            // Live gesture driving: begin with the app card fullscreen; the launcher
            // tick feeds the drag progress and the deck settles on release.
            // Start invisible: the tick only reveals the deck once the touch is long
            // enough to be deliberate, so a quick flick never flashes cards.
            rust.setAlpha(0f);
            rust.beginDragReturn(0);
        }
        View tuning = parent.findViewWithTag(TAG_TUNING);
        if (tuning != null) {
            tuning.bringToFront();
        }
        if (sFadeInNextInstall) {
            sFadeInNextInstall = false;
            rust.setAlpha(0f);
            rust.animate().alpha(1f).setDuration(180).start();
        }
        setImmersiveBars(activity, true);
        parent.postDelayed(() -> {
            if (parent.findViewWithTag(TAG_VIEW) != null) {
                setImmersiveBars(activity, true);
            }
        }, 150);
        if (enterFromApp && !(activity instanceof Launcher)) {
            rust.post(() -> {
                float[] start = stockFrontQuad(activity, rust, taskViews);
                if (start != null) {
                    rust.holdEnter(start);
                }
            });
        }
        Log.i(TAG, "recents overlay installed n=" + n + " hideStock=" + hideStock
                + " enter=" + enterFromApp);
        final ThumbState probeState = thumbs;
        rust.post(() -> {
            rust.probeAbsolute("installed");
            probeStockAbsolute("installed", probeState.taskViews);
        });
        rust.postDelayed(() -> {
            rust.probeAbsolute("plus900");
            probeStockAbsolute("plus900", probeState.taskViews);
        }, 900);
    }

    /** Debug: absolute on-screen rect (px) of every stock TaskView. */
    private static void probeStockAbsolute(String why, ArrayList<TaskView> taskViews) {
        StringBuilder sb = new StringBuilder("STOCK_ABS " + why);
        int[] loc = new int[2];
        for (int i = 0; i < taskViews.size(); i++) {
            TaskView tv = taskViews.get(i);
            tv.getLocationOnScreen(loc);
            sb.append(" s").append(i).append("=")
                    .append(loc[0]).append(",").append(loc[1]).append(",")
                    .append(loc[0] + tv.getWidth()).append(",")
                    .append(loc[1] + tv.getHeight());
        }
        Log.d("NavRSProbe", sb.toString());
    }

    /**
     * The rect the shell is shrinking the app window into: the stock overview's current
     * TaskView, expressed in carousel coordinates. Using it as the entry anchor keeps the
     * hand-off aligned for every style and card size.
     */
    private static float[] stockFrontQuad(Activity activity, View rust,
            ArrayList<TaskView> taskViews) {
        TaskView front = null;
        View panel = activity.findViewById(R.id.overview_panel);
        if (panel instanceof com.android.quickstep.views.RecentsView) {
            front = ((com.android.quickstep.views.RecentsView) panel).getCurrentPageTaskView();
        }
        if (front == null && !taskViews.isEmpty()) {
            front = taskViews.get(0);
        }
        if (front == null) {
            return null;
        }
        Rect b = new Rect();
        try {
            front.getThumbnailBounds(b, true);
        } catch (Throwable t) {
            return null;
        }
        if (b.width() < 10 || b.height() < 10) {
            return null;
        }
        float[] q = new float[6];
        q[0] = b.left - rust.getLeft();
        q[1] = b.top - rust.getTop();
        q[2] = b.right - rust.getLeft();
        q[3] = b.bottom - rust.getTop();
        q[4] = 0.13f * (b.right - b.left);
        q[5] = 0f;
        return q;
    }

    private static void launchTaskById(Activity activity, int taskId) {
        try {
            ActivityOptions opts = ActivityOptions.makeCustomTaskAnimation(activity, 0, 0,
                    new Handler(Looper.getMainLooper()), null, null);
            opts.setSplashScreenStyle(android.window.SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR);
            boolean ok = ActivityManagerWrapper.getInstance()
                    .startActivityFromRecents(taskId, opts);
            Log.i(TAG, "launch task " + taskId + " without transition: " + ok);
        } catch (Throwable t) {
            Log.w(TAG, "launch by id failed", t);
        }
    }

    /** Starts the task with no task transition and a solid splash, so only the card grows. */
    private static void launchWithoutTransition(Activity activity, TaskView tv) {
        try {
            TaskContainer container = tv.getTaskContainers().get(0);
            ActivityOptions opts = ActivityOptions.makeCustomTaskAnimation(activity, 0, 0,
                    new Handler(Looper.getMainLooper()), null, null);
            opts.setSplashScreenStyle(android.window.SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR);
            android.view.Display display = tv.getDisplay();
            if (display != null) {
                opts.setLaunchDisplayId(display.getDisplayId());
            }
            boolean ok = ActivityManagerWrapper.getInstance()
                    .startActivityFromRecents(container.getTask().key, opts);
            Log.i(TAG, "launch without transition: " + ok);
        } catch (Throwable t) {
            Log.w(TAG, "custom launch failed, falling back", t);
            tv.launchWithoutAnimation(false, launched -> null);
        }
    }

    // ---------------------------------------------------------------- thumbnails

    /**
     * Asks the recents model for every task's snapshot. TaskViews clear their
     * thumbnail when they are off-screen, so walking the views alone loses the
     * screenshots of pages outside the viewport.
     */
    private static void loadSnapshots(Activity activity,
            final com.wzy.navrs.RecentsView rust, final ThumbState state) {
        com.android.quickstep.TaskThumbnailCache cache;
        try {
            cache = com.android.quickstep.RecentsModel.INSTANCE.get(activity).getThumbnailCache();
        } catch (Throwable t) {
            Log.w(TAG, "thumbnail cache unavailable", t);
            return;
        }
        for (int i = 0; i < state.taskViews.size(); i++) {
            final int index = i;
            final int gen = state.generation;
            com.android.systemui.shared.recents.model.Task task;
            try {
                task = state.taskViews.get(i).getTaskContainers().get(0).getTask();
            } catch (Throwable t) {
                continue;
            }
            if (task == null) {
                continue;
            }
            try {
                cache.getThumbnailInBackground(task, thumbnailData -> {
                    if (thumbnailData == null || thumbnailData.getThumbnail() == null) {
                        return;
                    }
                    final Bitmap source = thumbnailData.getThumbnail();
                    THUMB_EXECUTOR.execute(() -> {
                        Bitmap soft = scaleThumbnail(source);
                        if (soft == null) {
                            return;
                        }
                        rust.post(() -> {
                            if (rust.getParent() == null || state.generation != gen
                                    || index >= state.thumbs.length) {
                                return;
                            }
                            state.thumbs[index] = soft;
                            rust.setThumbnails(state.thumbs);
                        });
                    });
                });
            } catch (Throwable t) {
                Log.w(TAG, "snapshot request failed", t);
            }
        }
    }

    private static void refreshThumbnails(final com.wzy.navrs.RecentsView rust,
            final ThumbState state, final int attempt) {
        if (rust.getParent() == null) {
            return;
        }
        final int gen = state.generation;
        final int n = Math.min(state.taskViews.size(), state.thumbs.length);
        // Snapshots often arrive after the overview card is bound (especially the
        // running task when coming from an app), and early ones can be blank.
        // Re-capture everything for the first ~2.5s, then only missing slots.
        final boolean recaptureAll = attempt < 10;
        boolean work = false;
        final Bitmap[] sources = new Bitmap[n];
        for (int i = 0; i < n; i++) {
            if (!recaptureAll && state.thumbs[i] != null) {
                continue;
            }
            TaskView tv = state.taskViews.get(i);
            if (taskIdOf(tv) != state.taskIds[i]) {
                TaskView match = findTaskViewById(state.panel, state.taskIds[i]);
                if (match == null) {
                    continue;
                }
                tv = match;
                state.taskViews.set(i, match);
            }
            View snap = tv.findViewById(navRsId(tv.getContext(), "snapshot"));
            sources[i] = findThumbnailBitmap(snap != null ? snap : tv);
            work = true;
        }
        if (!work) {
            return;
        }
        THUMB_EXECUTOR.execute(() -> {
            Bitmap[] target = state.thumbs;
            for (int i = 0; i < n && i < target.length; i++) {
                if (sources[i] == null) {
                    continue;
                }
                if (target[i] == null || recaptureAll) {
                    target[i] = scaleThumbnail(sources[i]);
                }
            }
            rust.post(() -> {
                if (rust.getParent() != null && state.generation == gen) {
                    rust.setThumbnails(state.thumbs);
                }
            });
        });
        if (attempt == 10) {
            // Still nothing: use the app icon so the slot never stays blank.
            for (int i = 0; i < n; i++) {
                if (state.thumbs[i] == null) {
                    state.thumbs[i] = captureIconCard(state.taskViews.get(i));
                }
            }
            if (state.generation == gen) {
                rust.setThumbnails(state.thumbs);
            }
        }
        if (attempt < 40) {
            rust.postDelayed(() -> {
                if (state.generation == gen) {
                    refreshThumbnails(rust, state, attempt + 1);
                }
            }, recaptureAll ? 250 : 400);
        }
    }

    /**
     * Snapshots are hardware bitmaps, so they cannot be drawn into the carousel's software
     * bitmap. Take the bitmap the TaskView already holds and copy it to ARGB_8888 instead.
     */
    private static Bitmap captureThumbnail(TaskView tv) {
        View snap = tv.findViewById(navRsId(tv.getContext(), "snapshot"));
        Bitmap src = findThumbnailBitmap(snap != null ? snap : tv);
        if (src == null) {
            return captureIconCard(tv);
        }
        return scaleThumbnail(src);
    }

    /** Removes the status/navigation bar bands from a full-screen snapshot. */
    /**
     * Keeps the full window aspect but erases the status bar and gesture-bar bands:
     * the bands are filled with the app's own edge colour so the card looks whole
     * without showing the system bars. The window animation's card includes those
     * bands, so cropping them here made the two rects disagree.
     */
    private static Bitmap maskInsets(Bitmap src, Rect insets) {
        if (src == null || insets == null) {
            return src;
        }
        int h = src.getHeight();
        int w = src.getWidth();
        int top = Math.max(0, Math.min(insets.top, h - 1));
        int bottom = Math.max(0, Math.min(insets.bottom, h - top - 1));
        if (top <= 0 && bottom <= 0) {
            return src;
        }
        Bitmap out = src;
        if (out.getConfig() != Bitmap.Config.ARGB_8888) {
            out = src.copy(Bitmap.Config.ARGB_8888, true);
        } else {
            out = src.copy(Bitmap.Config.ARGB_8888, true);
        }
        if (out == null) {
            return src;
        }
        Canvas canvas = new Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint();
        if (top > 0) {
            paint.setColor(out.getPixel(w / 2, Math.min(h - 1, top)));
            canvas.drawRect(0, 0, w, top, paint);
        }
        if (bottom > 0) {
            paint.setColor(out.getPixel(w / 2, Math.max(0, h - bottom - 1)));
            canvas.drawRect(0, h - bottom, w, h, paint);
        }
        return out;
    }

    /** Full-window thumbnail: hardware copy, inset bands masked, then scaled. */
    public static Bitmap windowAspectThumb(Bitmap src, int top, int bottom) {
        if (src == null) {
            return null;
        }
        Bitmap soft = src;
        if (src.getConfig() == Bitmap.Config.HARDWARE) {
            try {
                soft = src.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable t) {
                return null;
            }
        }
        if (soft == null) {
            return null;
        }
        soft = maskInsets(soft, new Rect(0, top, 0, bottom));
        int w = soft.getWidth();
        int h = soft.getHeight();
        float scale = Math.min(1f,
                Math.min(THUMB_MAX_W / (float) w, THUMB_MAX_H / (float) h));
        if (scale < 0.999f) {
            try {
                soft = Bitmap.createScaledBitmap(soft, Math.max(1, (int) (w * scale)),
                        Math.max(1, (int) (h * scale)), true);
            } catch (Throwable ignored) {
            }
        }
        return soft;
    }

    private static Bitmap cropContent(Bitmap src, Rect insets) {
        if (src == null || insets == null) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int left = Math.max(0, Math.min(insets.left, w - 1));
        int top = Math.max(0, Math.min(insets.top, h - 1));
        int right = Math.min(w, Math.max(left + 1, w - Math.max(0, insets.right)));
        int bottom = Math.min(h, Math.max(top + 1, h - Math.max(0, insets.bottom)));
        if (left == 0 && top == 0 && right == w && bottom == h) {
            return src;
        }
        try {
            return Bitmap.createBitmap(src, left, top, right - left, bottom - top);
        } catch (Throwable t) {
            Log.w(TAG, "snapshot crop failed", t);
            return src;
        }
    }

    private static Bitmap scaleThumbnail(Bitmap src) {
        Bitmap soft = src;
        if (src.getConfig() == Bitmap.Config.HARDWARE) {
            try {
                soft = src.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable t) {
                Log.w(TAG, "hardware thumbnail copy failed", t);
                return null;
            }
        }
        if (soft == null) {
            return null;
        }
        // Drop the status bar and navigation bar bands from the snapshot so the card
        // only shows app content, in the same aspect as the launch animation.
        soft = maskInsets(soft, new Rect(0, sContentTop, 0, sContentBottom));
        int w = soft.getWidth();
        int h = soft.getHeight();
        float scale = Math.min(1f,
                Math.min(THUMB_MAX_W / (float) w, THUMB_MAX_H / (float) h));
        if (scale >= 0.999f) {
            return soft;
        }
        try {
            return Bitmap.createScaledBitmap(soft, Math.max(1, (int) (w * scale)),
                    Math.max(1, (int) (h * scale)), true);
        } catch (Throwable t) {
            return soft;
        }
    }

    /**
     * Tasks without a snapshot (never rendered since boot) used to show up as bare
     * grey cards. Draw the app icon on a neutral card instead.
     */
    private static Bitmap captureIconCard(TaskView tv) {
        try {
            Drawable icon = tv.getTaskContainers().get(0).getTask().icon;
            if (icon == null) {
                return null;
            }
            int w = 360;
            int h = 720;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(b);
            canvas.drawColor(0xFF151A21);
            int size = w / 3;
            int left = (w - size) / 2;
            int top = (h - size) / 2;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap findThumbnailBitmap(View v) {
        if (v instanceof TaskThumbnailViewDeprecated) {
            Bitmap b = ((TaskThumbnailViewDeprecated) v).getThumbnail();
            if (b != null) {
                return b;
            }
        }
        if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            if (d instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) d).getBitmap();
                if (b != null) {
                    return b;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                Bitmap b = findThumbnailBitmap(group.getChildAt(i));
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }
}
