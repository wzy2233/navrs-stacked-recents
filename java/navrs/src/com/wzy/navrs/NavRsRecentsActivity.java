package com.wzy.navrs;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import android.view.animation.LinearInterpolator;
import android.window.ScreenCapture;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.quickstep.RecentsModel;
import com.android.quickstep.TaskThumbnailCache;
import com.android.quickstep.util.GroupTask;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Standalone Rust recents surface: the 3-button recents key opens this activity
 * directly, so no Quickstep overview, no shell recents animation and no stock
 * TaskView list are involved.
 *
 * The window is transparent and shows nothing until the front thumbnail is
 * ready; the app underneath stays visible for that moment. Then the wallpaper
 * layer, the carousel and its first full-screen frame appear together, so the
 * app is never replaced by an empty background.
 */
public class NavRsRecentsActivity extends Activity {

    private static final String TAG = "NavRS";
    private static final int THUMB_MAX_W = 720;
    private static final int THUMB_MAX_H = 1440;
    private static final long ENTRY_FALLBACK_MS = 700;
    /** Backdrop dissolve after the deck slides off-screen. */
    private static final long BACKDROP_FADE_MS = 160;
    /** Peak Gaussian blur radius for the home-screen backdrop, in pixels. */
    private static final float BLUR_MAX = 52f;
    /** Set by OverviewCommandHelper when the key was pressed on the home screen. */
    public static final String EXTRA_FROM_HOME = "com.wzy.navrs.FROM_HOME";
    private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static WeakReference<NavRsRecentsActivity> sCurrent = new WeakReference<>(null);

    /** Sharp display snapshot captured while the home screen is still visible. */
    private static volatile Bitmap sHomeShot;

    /**
     * Brightness of the last app snapshot's top band. A new window starts out as the
     * status-bar appearance owner, so it has to declare the same light/dark icons the
     * app had or SystemUI cross-fades them over the app's own bar -- which reads as the
     * top bar fading out before the animation even starts.
     */
    private static volatile int sLastTopLuma = 160;

    /**
     * Grabs the display synchronously. Called from OverviewCommandHelper right
     * before the recents window is launched, so the shot shows the desktop.
     */
    public static void captureHomeShot() {
        try {
            IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            ScreenCapture.CaptureArgs args = new ScreenCapture.CaptureArgs.Builder().build();
            ScreenCapture.SynchronousScreenCaptureListener listener =
                    ScreenCapture.createSyncCaptureListener();
            wm.captureDisplay(Display.DEFAULT_DISPLAY, args, listener);
            ScreenCapture.ScreenshotHardwareBuffer buffer = listener.getBuffer();
            Bitmap bitmap = buffer == null ? null : buffer.asBitmap();
            if (bitmap != null) {
                Bitmap software = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                sHomeShot = software != null ? software : bitmap;
                Log.d(TAG, "home shot " + sHomeShot.getWidth() + "x" + sHomeShot.getHeight());
            } else {
                Log.w(TAG, "home shot null");
            }
        } catch (Throwable t) {
            Log.w(TAG, "home shot failed", t);
        }
    }

    /** Non-consuming access to the last display capture (desktop with icons). */
    public static Bitmap peekHomeShot() {
        Bitmap b = sHomeShot;
        return b == null || b.isRecycled() ? null : b;
    }

    private static Bitmap takeHomeShot() {
        Bitmap bitmap = sHomeShot;
        sHomeShot = null;
        return bitmap;
    }

    private static volatile float sDragProgress;
    /** Swipes shorter than this (ms) go straight home instead of short/medium. */
    private static volatile long sQuickTouchMs = 200L;
    private static volatile float sShortFrac = 0.1f;   // fraction of screen height from bottom
    private static volatile float sLongFrac = 0.5f;
    private static volatile float sLongOut;

    /** Two gesture boundaries as fractions of the screen height. Directly assignable. */
    public static void setGateFractions(float shortFrac, float longFrac) {
        sShortFrac = shortFrac;
        sLongFrac = longFrac;
        Log.d("NavRSProbe", "gateFrac set " + shortFrac + "/" + longFrac);
    }

    public static void loadGateFractions(android.content.Context context) {
        if (context == null) {
            return;
        }
        // Read every release so `settings put secure navrs_short_frac/long_frac`
        // applies immediately without restarting the launcher.
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            sShortFrac = android.provider.Settings.Secure.getFloat(
                    cr, "navrs_short_frac", sShortFrac);
            sLongFrac = android.provider.Settings.Secure.getFloat(
                    cr, "navrs_long_frac", sLongFrac);
            sQuickTouchMs = android.provider.Settings.Secure.getLong(
                    cr, "navrs_quick_ms", sQuickTouchMs);
        } catch (Throwable ignored) {
        }
    }

    /** Directly assignable quick-touch cutoff (ms). */
    public static void setQuickTouchMs(long ms) {
        sQuickTouchMs = Math.max(60L, ms);
    }

    public static long getQuickTouchMs() {
        return sQuickTouchMs;
    }

    public static float getShortFrac() {
        return sShortFrac;
    }

    public static float getLongFrac() {
        return sLongFrac;
    }

    public static void setLongOut(float v) {
        sLongOut = Math.max(0f, Math.min(1f, v));
    }

    public static float getLongOut() {
        return sLongOut;
    }
    private static volatile boolean sHomeRelease;
    private static volatile long sLastExitAt;

    /** True for a moment after the deck slid away, to absorb the same gesture. */
    public static boolean recentlyExited() {
        long at = sLastExitAt;
        return at != 0L && android.os.SystemClock.uptimeMillis() - at < 600L;
    }

    /** Live app-gesture drag progress (0 = app, 1 = overview). */
    public static void setDragProgress(float progress) {
        sDragProgress = progress;
        Log.d("NavRSProbe", "drag=" + progress);
    }

    public static float getDragProgress() {
        return sDragProgress;
    }

    /** True when the gesture was released toward HOME (long swipe). */
    public static void setHomeRelease(boolean home) {
        sHomeRelease = home;
    }

    public static boolean isHomeRelease() {
        return sHomeRelease;
    }

    /** Opens the standalone recents surface for a gesture that would show overview. */
    public static void launchFromGesture(Context context, boolean fromHome) {
        if (current() != null) {
            return;
        }
        try {
            if (fromHome) {
                captureHomeShot();
            }
            Intent intent = new Intent(context, NavRsRecentsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_FROM_HOME, fromHome);
            Bundle options = ActivityOptions.makeCustomTaskAnimation(context, 0, 0,
                    new Handler(Looper.getMainLooper()), null, null).toBundle();
            context.startActivity(intent, options);
            Log.d(TAG, "launch from gesture fromHome=" + fromHome);
        } catch (Throwable t) {
            Log.w(TAG, "gesture launch failed", t);
        }
    }

    /** The recents surface currently on screen, or null. */
    public static NavRsRecentsActivity current() {
        NavRsRecentsActivity activity = sCurrent.get();
        return activity == null || activity.isFinishing() || activity.isDestroyed()
                ? null : activity;
    }

    private final ArrayList<Task> tasks = new ArrayList<>();
    private FrameLayout root;
    private RecentsView view;
    private Bitmap[] thumbArray;
    private ImageView sharpLayer;
    private ImageView blurLayer;
    private View scrimLayer;
    /** Mean brightness of the backdrop's top band; picks the status-bar icon colour. */
    private int backdropLuma = -1;
    private int contentTop;
    private int contentBottom;
    private boolean reloading;
    private boolean thumbsFrozen;
    private boolean entryPlayed;
    private boolean fromHome;
    private boolean chromeShown;
    private long createdAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createdAt = SystemClock.uptimeMillis();
        fromHome = getIntent().getBooleanExtra(EXTRA_FROM_HOME, false);
        sCurrent = new WeakReference<>(this);
        pinWindow();
        applyInitialStatusBarAppearance(fromHome);
        getWindow().setDecorFitsSystemWindows(false);

        root = new FrameLayout(this);
        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setupWindow();
        prepareWallpaper();
        setupBars();
        loadTasks();
    }

    private void setupWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    /**
     * Detach the window from system-bar inset animation, so the window bounds do
     * not move when the bar state changes. The navigation bar itself is the
     * Launcher Taskbar (the Rust three-button bar) and stays visible.
     */
    private void pinWindow() {
        Window window = getWindow();
        try {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.setFitInsetsTypes(0);
            window.setAttributes(attrs);
        } catch (Throwable t) {
            Log.w(TAG, "fit insets failed", t);
        }
    }

    /**
     * Builds the backdrop as three layers: a sharp desktop, a pre-blurred copy of it and
     * a dim scrim. Everything stays invisible until the entry, because showing it before
     * the first card frame would blank the app for a moment.
     *
     * <p>The blur is baked once instead of running a 52 px RenderEffect: that effect was
     * the single most expensive thing on the entry path.
     */
    private void prepareWallpaper() {
        ImageView sharp = new ImageView(this);
        sharp.setScaleType(ImageView.ScaleType.CENTER_CROP);
        sharp.setVisibility(View.INVISIBLE);
        root.addView(sharp, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sharpLayer = sharp;

        ImageView blurred = new ImageView(this);
        blurred.setScaleType(ImageView.ScaleType.CENTER_CROP);
        blurred.setVisibility(View.INVISIBLE);
        root.addView(blurred, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        blurLayer = blurred;

        View scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setVisibility(View.INVISIBLE);
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrimLayer = scrim;

        // Same backdrop as the gesture path: a screenshot of the desktop, so the soft
        // band covers the app icons and widgets, not only the wallpaper.
        Bitmap desktop = null;
        try {
            desktop = com.android.launcher3.uioverrides.QuickstepLauncher.navRsDesktopShotCached();
        } catch (Throwable t) {
            Log.w(TAG, "desktop shot failed", t);
        }
        if (desktop != null) {
            Bitmap soft = null;
            try {
                soft = com.android.launcher3.uioverrides.QuickstepLauncher.navRsDesktopBlurCached();
            } catch (Throwable ignored) {
            }
            if (soft == null) {
                soft = NavRsBlur.blur(desktop, BLUR_MAX);
            }
            sharp.setImageBitmap(desktop);
            if (soft != null) {
                blurred.setImageBitmap(soft);
                backdropLuma = topBandLuma(soft);
            } else {
                backdropLuma = topBandLuma(desktop);
            }
            Log.d(TAG, "backdrop: desktop " + desktop.getWidth() + "x" + desktop.getHeight()
                    + " soft=" + (soft != null) + " luma=" + backdropLuma);
        } else {
            // Nothing cached yet: a flat surface stays up until the wallpaper is
            // decoded and blurred on a worker thread.
            sharp.setBackgroundColor(0xFF10141A);
            blurred.setBackgroundColor(0xFF10141A);
            loadWallpaperBackdrop();
        }
    }

    /** Decodes and blurs the wallpaper off the UI thread; only used without a snapshot. */
    private void loadWallpaperBackdrop() {
        final Context ctx = getApplicationContext();
        EXEC.execute(() -> {
            Bitmap soft = null;
            int luma = -1;
            try {
                Drawable drawable = WallpaperManager.getInstance(ctx).getDrawable();
                if (drawable != null) {
                    int w = Math.max(1, drawable.getIntrinsicWidth());
                    int h = Math.max(1, drawable.getIntrinsicHeight());
                    int sw = Math.min(w, 720);
                    int sh = Math.max(1, h * sw / w);
                    Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(small);
                    drawable.setBounds(0, 0, sw, sh);
                    drawable.draw(canvas);
                    soft = NavRsBlur.blur(small, BLUR_MAX * sw / (float) w);
                    small.recycle();
                    if (soft != null) {
                        luma = topBandLuma(soft);
                    }
                } else {
                    Log.w(TAG, "wallpaper drawable null");
                }
            } catch (Throwable t) {
                Log.w(TAG, "wallpaper drawable failed", t);
            }
            final Bitmap ready = soft;
            final int readyLuma = luma;
            runOnUiThread(() -> {
                if (ready == null || blurLayer == null || isFinishing()) {
                    return;
                }
                backdropLuma = readyLuma;
                blurLayer.setImageBitmap(ready);
                if (chromeShown) {
                    blurLayer.setAlpha(1f);
                    blurLayer.setVisibility(View.VISIBLE);
                }
                Log.d(TAG, "backdrop: wallpaper " + ready.getWidth() + "x" + ready.getHeight()
                        + " luma=" + readyLuma);
            });
        });
    }

    /** Mean brightness of the top eighth of a bitmap, 0-255 (-1 when unreadable). */
    private static int topBandLuma(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return -1;
        }
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) {
            return -1;
        }
        final int band = Math.max(2, h / 8);
        final int stepX = Math.max(1, w / 24);
        final int stepY = Math.max(1, band / 4);
        long sum = 0;
        int n = 0;
        for (int y = 0; y < band; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int px = bitmap.getPixel(x, y);
                sum += ((px >> 16) & 0xFF) * 77 + ((px >> 8) & 0xFF) * 150 + (px & 0xFF) * 29;
                n++;
            }
        }
        return n == 0 ? -1 : (int) (sum / (n * 256));
    }

    /**
     * Declares, before the window is added, the status-bar icon colour that matches what
     * is currently behind the bar (the app that is about to shrink). Without this the new
     * window starts out as light-icons and SystemUI cross-fades the icons on top of the
     * app's own light bar, which is the "top bar fades out first" the user sees.
     */
    private void applyInitialStatusBarAppearance(boolean fromHome) {
        int luma = sLastTopLuma;
        if (fromHome) {
            Bitmap desktop = null;
            try {
                desktop = com.android.launcher3.uioverrides.QuickstepLauncher
                        .navRsDesktopShotCached();
            } catch (Throwable ignored) {
            }
            if (desktop != null) {
                luma = topBandLuma(desktop);
            }
        }
        final boolean darkIcons = luma < 0 || luma > 140;
        try {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            final int flag = WindowManager.LayoutParams.APPEARANCE_LIGHT_STATUS_BARS;
            attrs.appearance = darkIcons
                    ? (attrs.appearance | flag) : (attrs.appearance & ~flag);
            getWindow().setAttributes(attrs);
            Log.d(TAG, "initial status bar icons " + (darkIcons ? "dark" : "light")
                    + " luma=" + luma);
        } catch (Throwable t) {
            Log.w(TAG, "initial status bar appearance failed", t);
        }
    }

    /**
     * Switches the status-bar icons to the colour that contrasts with the backdrop.
     *
     * <p>Called only once the front card has slid clear of the status bar, so the icons
     * stay glued to the top of the screen while the app shrinks underneath them.
     */
    private void applyStatusBarIconAppearance() {
        try {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) {
                return;
            }
            final boolean darkIcons = backdropLuma >= 0 && backdropLuma > 140;
            controller.setSystemBarsAppearance(
                    darkIcons ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            Log.d(TAG, "status bar icons " + (darkIcons ? "dark" : "light")
                    + " luma=" + backdropLuma);
        } catch (Throwable t) {
            Log.w(TAG, "status bar appearance failed", t);
        }
    }

    private int navBarHeight() {
        try {
            int id = getResources().getIdentifier(
                    "navigation_bar_height", "dimen", "android");
            if (id > 0) {
                int value = getResources().getDimensionPixelSize(id);
                if (value > 0) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return (int) (48 * getResources().getDisplayMetrics().density);
    }

    private void setupBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            // Nothing is forced here: the window opens with the icon colour the
            // app already had (see applyInitialStatusBarAppearance) and only re-tints
            // once the card is clear of the status bar.
            // Do not hide navigationBars(): that surface is the Launcher Taskbar,
            // and hiding it makes WindowManager animate the Rust bar itself away.
        }
        int[] insets = contentInsets();
        contentTop = Math.max(insets[0], 0);
        contentBottom = Math.max(insets[1], navBarHeight());

        root.post(() -> {
            int[] real = contentInsets();
            int top = Math.max(real[0], contentTop);
            int bottom = Math.max(real[1], navBarHeight());
            if (top == contentTop && bottom == contentBottom) {
                return;
            }
            contentTop = top;
            contentBottom = bottom;
            if (view != null) {
                view.setContentInsets(0, 0);
            }
        });
    }

    private int[] contentInsets() {
        try {
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                return new int[]{bars.top, bars.bottom};
            }
        } catch (Throwable t) {
            Log.w(TAG, "insets unavailable", t);
        }
        return new int[]{0, 0};
    }

    private void loadTasks() {
        if (reloading) {
            return;
        }
        reloading = true;
        try {
            RecentsModel.INSTANCE.get(this).getTasks(groups -> {
                final ArrayList<Task> list = new ArrayList<>();
                if (groups != null) {
                    for (GroupTask group : groups) {
                        for (Task task : group.getTasks()) {
                            android.content.ComponentName component = task.key.baseIntent == null
                                    ? null : task.key.baseIntent.getComponent();
                            String pkg = component != null
                                    ? component.getPackageName()
                                    : task.key.baseIntent == null
                                    ? null : task.key.baseIntent.getPackage();
                            if (!"com.android.launcher3".equals(pkg)) {
                                list.add(task);
                            }
                        }
                    }
                }
                // The recents model lists tasks oldest -> newest.
                Collections.reverse(list);
                runOnUiThread(() -> showTasks(list));
            });
        } catch (Throwable t) {
            Log.w(TAG, "task list unavailable", t);
            finish();
        }
    }

    private void showTasks(ArrayList<Task> list) {
        reloading = false;
        tasks.clear();
        tasks.addAll(list);
        if (tasks.isEmpty()) {
            finish();
            return;
        }
        if (view != null) {
            root.removeView(view);
            view = null;
        }
        final RecentsView rust = new RecentsView(this, tasks.size());
        view = rust;
        rust.setBackgroundColor(Color.TRANSPARENT);
        NavRsPrefs.apply(this, rust);
        // Full window aspect: the thumbnails mask the status/nav bands instead of
        // cropping them, so cards match the window animation's rect.
        rust.setContentInsets(0, 0);
        final Bitmap[] thumbs = new Bitmap[tasks.size()];
        thumbArray = thumbs;
        rust.setThumbnails(thumbs);
        rust.setOnCardTap(index -> {
            if (index < 0 || index >= tasks.size()) {
                return;
            }
            final int taskId = tasks.get(index).key.id;
            rust.startLaunch(index, () -> launchTask(taskId));
        });
        rust.setOnCardDismiss(index -> {
            if (index < 0 || index >= tasks.size()) {
                return;
            }
            thumbsFrozen = true;
            final int taskId = tasks.remove(index).key.id;
            try {
                ActivityManagerWrapper.getInstance().removeTask(taskId);
            } catch (Throwable t) {
                Log.w(TAG, "remove task failed", t);
            }
            if (tasks.isEmpty()) {
                finish();
            }
        });
        rust.setOnExitStart(this::startExitBackdrop);
        rust.setOnExit(this::onDeckGone);
        rust.setAlpha(0f);
        root.addView(rust, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadThumbnails(rust, thumbs);
        rust.postDelayed(() -> startEntry(rust, "fallback"), ENTRY_FALLBACK_MS);
    }

    /**
     * First visible frame of the recents surface. Everything appears in one
     * frame: backdrop, carousel and the front card already at full-screen size.
     */
    private void startEntry(RecentsView rust, String reason) {
        if (entryPlayed || view != rust || isFinishing()) {
            return;
        }
        entryPlayed = true;
        final Bitmap[] thumbs = thumbArray;
        Log.d(TAG, "entry start reason=" + reason + " fromHome=" + fromHome
                + " t=" + (SystemClock.uptimeMillis() - createdAt)
                + "ms frontThumb=" + (thumbs != null && thumbs.length > 0 && thumbs[0] != null));
        if (chromeShown) {
            return;
        }
        chromeShown = true;
        Bitmap shot = fromHome ? takeHomeShot() : null;
        boolean sharpStart = shot != null && sharpLayer != null;
        rust.setAlpha(1f);
        if (sharpStart) {
            // Opened from the home screen: the desktop starts sharp and turns into the
            // pre-blurred copy while the cards slide in. Two static layers cross-fading
            // look identical to an animated blur radius and cost nothing per frame.
            sharpLayer.setImageBitmap(shot);
            sharpLayer.setAlpha(1f);
            sharpLayer.setVisibility(View.VISIBLE);
            if (blurLayer != null) {
                if (blurLayer.getDrawable() == null) {
                    Bitmap soft = NavRsBlur.blur(shot, BLUR_MAX);
                    if (soft != null) {
                        blurLayer.setImageBitmap(soft);
                    }
                }
                blurLayer.setAlpha(0f);
                blurLayer.setVisibility(View.VISIBLE);
                blurLayer.animate().alpha(1f).setDuration(RecentsView.SLIDE_IN_MS)
                        .setInterpolator(new LinearInterpolator()).start();
            }
            if (scrimLayer != null) {
                scrimLayer.setAlpha(0f);
                scrimLayer.setVisibility(View.VISIBLE);
                scrimLayer.animate().alpha(1f).setDuration(RecentsView.SLIDE_IN_MS)
                        .setInterpolator(new LinearInterpolator()).start();
            }
            rust.startSlideInFromLeft();
            rust.postDelayed(this::applyStatusBarIconAppearance,
                    RecentsView.SLIDE_IN_MS * 45 / 100);
        } else {
            if (blurLayer != null) {
                blurLayer.setAlpha(1f);
                blurLayer.setVisibility(View.VISIBLE);
            }
            if (scrimLayer != null) {
                scrimLayer.setAlpha(1f);
                scrimLayer.setVisibility(View.VISIBLE);
            }
            rust.setOnEntryTopCleared(this::applyStatusBarIconAppearance);
            rust.startReturn(0, null);
        }
    }

    /**
     * Called the moment the deck starts sliding off the left edge. The blurred layer
     * fades out over exactly the same span as the cards, so the backdrop sharpens and
     * dissolves in one pass.
     */
    private void startExitBackdrop() {
        sLastExitAt = android.os.SystemClock.uptimeMillis();
        fadeOut(blurLayer, RecentsView.SLIDE_OUT_MS, new LinearInterpolator());
        fadeOut(sharpLayer, RecentsView.SLIDE_OUT_MS,
                new android.view.animation.AccelerateInterpolator(1.2f));
        fadeOut(scrimLayer, RecentsView.SLIDE_OUT_MS, null);
    }

    private static void fadeOut(View view, long durationMs,
            android.animation.TimeInterpolator interpolator) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return;
        }
        android.view.ViewPropertyAnimator animator = view.animate().alpha(0f)
                .setDuration(durationMs);
        if (interpolator != null) {
            animator.setInterpolator(interpolator);
        }
        animator.start();
    }

    private void onDeckGone() {
        finishOver();
    }

    /** After the deck slips off the left edge, dissolve the backdrop onto home. */
    private void fadeBackdropThenFinish() {
        final View layer = blurLayer;
        if (layer == null || layer.getVisibility() != View.VISIBLE) {
            finishOver();
            return;
        }
        layer.animate().alpha(0f).setDuration(BACKDROP_FADE_MS)
                .withEndAction(this::finishOver).start();
        if (scrimLayer != null) {
            scrimLayer.animate().alpha(0f).setDuration(BACKDROP_FADE_MS).start();
        }
    }

    private void finishOver() {
        if (isFinishing()) {
            return;
        }
        finish();
        overridePendingTransition(0, 0);
    }

    /** Starts the card slide-out that ends in the home screen. */
    public void exitToHome() {
        sLastExitAt = android.os.SystemClock.uptimeMillis();
        Log.d("NavRSProbe", "activity exitToHome view=" + (view != null)
                + " finishing=" + isFinishing());
        if (view != null && !isFinishing()) {
            view.startSlideOutToLeft();
        }
    }

    private void launchTask(int taskId) {
        try {
            ActivityOptions options = ActivityOptions.makeCustomTaskAnimation(this, 0, 0,
                    new Handler(Looper.getMainLooper()), null, null);
            options.setSplashScreenStyle(
                    android.window.SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR);
            ActivityManagerWrapper.getInstance().startActivityFromRecents(taskId, options);
            Log.i(TAG, "recents activity launched task " + taskId);
        } catch (Throwable t) {
            Log.w(TAG, "launch failed", t);
        }
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    private void loadThumbnails(final RecentsView rust, final Bitmap[] thumbs) {
        try {
            final TaskThumbnailCache cache =
                    RecentsModel.INSTANCE.get(this).getThumbnailCache();
            for (int i = 0; i < tasks.size(); i++) {
                final int index = i;
                final Task task = tasks.get(i);
                if (task.thumbnail != null && task.thumbnail.getThumbnail() != null) {
                    applyThumb(rust, thumbs, index, task.thumbnail, false);
                }
                try {
                    cache.getThumbnailInBackground(task, thumbnailData ->
                            applyThumb(rust, thumbs, index, thumbnailData, true));
                } catch (Throwable t) {
                    Log.w(TAG, "thumbnail request failed", t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "thumbnail cache unavailable", t);
        }
    }

    private void applyThumb(final RecentsView rust, final Bitmap[] thumbs, final int index,
            final ThumbnailData data, final boolean fromCache) {
        if (thumbsFrozen || data == null || data.getThumbnail() == null) {
            return;
        }
        final Bitmap source = data.getThumbnail();
        EXEC.execute(() -> {
            // Same rule as the gesture path: keep the full window aspect and mask the
            // status/gesture-bar bands instead of cropping them.
            final Bitmap ready = RecentsOverlay.windowAspectThumb(
                    source, contentTop, contentBottom);
            if (ready == null) {
                return;
            }
            final int luma = index == 0 ? luma(ready) : -1;
            runOnUiThread(() -> {
                if (view != rust || index >= thumbs.length) {
                    return;
                }
                if (index == 0) {
                    Log.d(TAG, "front thumb src=" + (fromCache ? "cache" : "model")
                            + " " + ready.getWidth() + "x" + ready.getHeight()
                            + " luma=" + luma);
                }
                thumbs[index] = ready;
                rust.setThumbnails(thumbs);
                if (index == 0) {
                    // The bar takes the app's own colour, sampled just above the
                    // navigation bar, so the keys sit on the app instead of the wallpaper.
                    NavBarView.sampleAppBarColor(ready);
                    // Remember how bright the app's top bar is: the next recents window
                    // has to open with matching status-bar icons so they never fade.
                    sLastTopLuma = topBandLuma(ready);
                }
                // Model thumbnails can be a stale black placeholder; only a cache
                // result is trusted to be the app's real last frame.
                if (index == 0 && fromCache && !entryPlayed) {
                    rust.post(() -> startEntry(rust, "thumb"));
                }
            });
        });
    }

    /** Coarse brightness of a bitmap, to spot placeholder snapshots. */
    private static int luma(Bitmap bitmap) {
        long sum = 0;
        long count = 0;
        try {
            int stepX = Math.max(1, bitmap.getWidth() / 16);
            int stepY = Math.max(1, bitmap.getHeight() / 16);
            for (int y = 0; y < bitmap.getHeight(); y += stepY) {
                for (int x = 0; x < bitmap.getWidth(); x += stepX) {
                    int c = bitmap.getPixel(x, y);
                    sum += ((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587
                            + (c & 0xFF) * 114;
                    count++;
                }
            }
        } catch (Throwable ignored) {
            return -1;
        }
        return count == 0 ? -1 : (int) (sum / (count * 1000));
    }

    private static Bitmap crop(Bitmap src, Rect insets) {
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
            return src;
        }
    }

    /** Host callback for the taskbar's long-press on the recents key. */
    public void showTuningPanel() {
        RecentsTuningPanel.toggle(this, getWindow().getDecorView(), view);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // The recents key toggles. From home it replays the leftward exit; from an
        // app it zooms the current app's card back to full screen and launches it.
        if (view == null) {
            finishOver();
            return;
        }
        if (fromHome) {
            view.startSlideOutToLeft();
        } else if (!tasks.isEmpty()) {
            final int taskId = tasks.get(0).key.id;
            view.startLaunch(0, () -> launchTask(taskId));
        } else {
            finishOver();
        }
    }

    @Override
    public void onBackPressed() {
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Home, or any other task taking over, closes the surface so the next
        // recents press always builds a fresh one.
        if (!isFinishing() && !isChangingConfigurations()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (sCurrent.get() == this) {
            sCurrent = new WeakReference<>(null);
        }
        if (view != null) {
            root.removeView(view);
            view = null;
        }
        super.onDestroy();
    }
}
