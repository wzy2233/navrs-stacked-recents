package com.wzy.navrs;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

import android.util.Log;

/**
 * A three-button navigation bar drawn entirely by nav-rs.
 *
 * The bar is transparent: only icons and ripples are painted, so the system's
 * own nav bar background (application colour / scrim) shows through.
 */
public class NavBarView extends View implements Choreographer.FrameCallback {

    private static final String TAG = "NavRS";

    private static void log(String msg) { Log.d(TAG, msg); }

    private static void log(Throwable t) { Log.d(TAG, "err", t); }
    private static final String DEEPSEEK_PKG = "com.deepseek.chat";

    private static boolean sNativeLoaded;
    private static boolean sNativeTried;
    private static Throwable sNativeError;
    private static volatile int sIconColor = 0xFFFFFFFF;
    /** Colour the framework asked for; only used until the bar background is known. */
    private static volatile int sSystemIconColor = 0xFFFFFFFF;
    /** Contrast colour derived from the bar background (0 = not computed yet). */
    private static volatile int sAutoIconColor;
    /** Painted behind the keys so they never vanish into the bar underneath. */
    private static volatile int sBarColor;
    /** Wallpaper sample: the bar colour on the home screen. */
    private static volatile int sWallBarColor;
    private static volatile int sWallIconColor;
    /** Foreground-app sample: the bar colour while an app owns the screen. */
    private static volatile int sAppBarColor;
    private static volatile int sAppIconColor;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<NavBarView> sInstance = new WeakReference<>(null);

    private long handle;
    private Bitmap bitmap;
    private ByteBuffer buffer;
    private int bufW;
    private int bufH;
    private boolean frameScheduled;
    private boolean taskbarMode;
    private boolean lastKeyguard;
    private final Paint paint = new Paint();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final android.database.ContentObserver navModeObserver =
            new android.database.ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    applyNavigationMode();
                }
            };

    public NavBarView(Context context) {
        this(context, false);
    }

    /** Host callback for standalone windows (recents activity). */
    public interface Callback {
        boolean onNavButton(int action);

        boolean onNavLongPress(int action);
    }

    private Callback callback;

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public NavBarView(Context context, boolean taskbarMode) {
        super(context);
        this.taskbarMode = taskbarMode;
        setClickable(true);
        setFocusable(false);
        setBackgroundColor(0);
        setWillNotDraw(false);
        ensureNative(context);
        sInstance = new WeakReference<>(this);
        log(TAG + ": NavBarView created, native=" + sNativeLoaded
                + " taskbar=" + taskbarMode
                + (sNativeError != null ? " err=" + sNativeError : ""));
    }

    public static void setIconColor(int argb) {
        sSystemIconColor = argb;
        // The framework's dark-intensity pipeline is what left white keys on a white
        // wallpaper; once the bar background is known, contrast against it wins.
        int applied = sAutoIconColor != 0 ? sAutoIconColor : argb;
        sIconColor = applied;
        NavBarView view = sInstance.get();
        if (view != null) {
            view.applyColor(applied);
        }
    }

    /** Background painted behind the keys. Alpha 0 keeps the bar transparent. */
    public static void setBarColor(int argb) {
        sWallBarColor = argb;
        applyEffective();
    }

    /**
     * Paints the bar with the foreground app's own colour, sampled from the app's
     * snapshot just above the navigation bar. The keys get the contrasting colour.
     */
    public static void setAppBarColor(int rgb) {
        final int colour = rgb & 0xFFFFFF;
        final float lum = 0.2126f * ((colour >> 16) & 0xFF)
                + 0.7152f * ((colour >> 8) & 0xFF)
                + 0.0722f * (colour & 0xFF);
        sAppBarColor = (0xE0 << 24) | colour;
        sAppIconColor = lum > 140f ? 0xFF121212 : 0xFFFFFFFF;
        log("app bar #" + Integer.toHexString(colour) + " lum=" + Math.round(lum)
                + " icon=" + (lum > 140f ? "dark" : "light"));
        applyEffective();
    }

    /** Back to the wallpaper sample: the home screen has no app to follow. */
    public static void clearAppBarColor() {
        if (sAppBarColor == 0) {
            return;
        }
        sAppBarColor = 0;
        log("app bar cleared");
        applyEffective();
    }

    /** Resolves which sample wins and pushes one consistent bar + key colour. */
    private static void applyEffective() {
        final int bar;
        final int icon;
        if (sAppBarColor != 0) {
            bar = sAppBarColor;
            icon = sAppIconColor;
        } else if (sWallBarColor != 0) {
            bar = sWallBarColor;
            icon = sWallIconColor;
        } else {
            bar = 0xE6101216;
            icon = 0xFFFFFFFF;
        }
        sBarColor = bar;
        sAutoIconColor = icon;
        sIconColor = icon;
        NavBarView view = sInstance.get();
        if (view != null) {
            view.applyBarColor(bar);
            view.applyColor(icon);
        }
    }

    /**
     * Averages the band just above the navigation bar of an app snapshot. Runs on its
     * own thread: the snapshot can be 720x1440 and this is called per recents entry.
     */
    public static void sampleAppBarColor(final Bitmap thumb) {
        if (thumb == null || thumb.isRecycled()) {
            return;
        }
        final int w = thumb.getWidth();
        final int h = thumb.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        new Thread(() -> {
            int rgb = 0;
            try {
                final int y0 = Math.max(0, (int) (h * 0.74f));
                final int y1 = Math.max(y0 + 1, (int) (h * 0.88f));
                final int stepX = Math.max(1, w / 48);
                final int stepY = Math.max(1, (y1 - y0) / 6);
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                for (int y = y0; y < y1; y += stepY) {
                    for (int x = w / 10; x < w * 9 / 10; x += stepX) {
                        int px = thumb.getPixel(x, y);
                        r += (px >> 16) & 0xFF;
                        g += (px >> 8) & 0xFF;
                        b += px & 0xFF;
                        n++;
                    }
                }
                if (n > 0) {
                    rgb = (int) (((r / n) << 16) | ((g / n) << 8) | (b / n));
                }
            } catch (Throwable t) {
                log(t);
            }
            if (rgb != 0) {
                final int value = rgb;
                MAIN.post(() -> setAppBarColor(value));
            }
        }, "navrs-app-colour").start();
    }

    private void applyBarColor(int argb) {
        if (handle != 0) {
            nativeSetBarColor(handle, argb);
            scheduleFrame();
        }
    }

    private void applyColor(int argb) {
        if (handle != 0) {
            nativeSetColor(handle, argb);
            scheduleFrame();
        }
    }

    private static synchronized void ensureNative(Context context) {
        if (sNativeTried) {
            return;
        }
        sNativeTried = true;
        try {
            System.loadLibrary("nav_rs");
            sNativeLoaded = true;
            return;
        } catch (Throwable first) {
            log(TAG + ": loadLibrary failed: " + first);
        }
        try {
            ApplicationInfo ai = context.getPackageManager()
                    .getApplicationInfo("com.wzy.navrs", 0);
            System.load(ai.nativeLibraryDir + "/libnav_rs.so");
            sNativeLoaded = true;
        } catch (Throwable second) {
            sNativeError = second;
            log(TAG + ": native load failed: " + second);
        }
    }

    public static String version() {
        return sNativeLoaded ? nativeVersion() : "native-unavailable";
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncKeyguard();
        applyNavigationMode();
        getContext().getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor("navigation_mode"), false, navModeObserver);
        if (sBarColor != 0) {
            applyBarColor(sBarColor);
        } else {
            updateBarColorFromWallpaper();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            getContext().getContentResolver().unregisterContentObserver(navModeObserver);
        } catch (Throwable ignored) {
        }
        handler.removeCallbacks(longPressRunnable);
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
        super.onDetachedFromWindow();
    }

    private void applyNavigationMode() {
        try {
            int mode = Settings.Secure.getInt(
                    getContext().getContentResolver(), "navigation_mode", 0);
            setVisibility(mode == 0 ? VISIBLE : GONE);
        } catch (Throwable t) {
            log(t);
        }
    }

    private void syncKeyguard() {
        try {
            KeyguardManager km = (KeyguardManager) getContext()
                    .getSystemService(Context.KEYGUARD_SERVICE);
            boolean locked = km != null && km.isKeyguardLocked();
            if (locked != lastKeyguard) {
                lastKeyguard = locked;
                if (handle != 0) {
                    nativeSetKeyguard(handle, locked);
                }
                scheduleFrame();
            }
        } catch (Throwable t) {
            log(t);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recreate(w, h);
    }

    private void recreate(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (bitmap != null && bufW == w && bufH == h) {
            return;
        }
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
        try {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            log(t);
            return;
        }
        bufW = w;
        bufH = h;
        buffer = ByteBuffer.allocateDirect(w * h * 4);
        if (sNativeLoaded) {
            float density = getResources().getDisplayMetrics().density;
            handle = nativeCreate(w, h, density);
            nativeSetColor(handle, sIconColor);
            nativeSetBarColor(handle, sBarColor);
            nativeSetKeyguard(handle, lastKeyguard);
            log(TAG + ": nativeCreate " + w + "x" + h
                    + " d=" + density + " handle=" + handle);
        }
        scheduleFrame();
    }

    /**
     * Samples the wallpaper's bottom band, paints the bar with it and forces the keys to a
     * contrasting colour. The bar used to be fully transparent, so the keys took whatever
     * colour the framework chose and disappeared on light wallpapers.
     */
    private void updateBarColorFromWallpaper() {
        final Context ctx = getContext().getApplicationContext();
        new Thread(() -> {
            int sampled = 0;
            try {
                android.app.WallpaperManager wm =
                        android.app.WallpaperManager.getInstance(ctx);
                android.graphics.drawable.Drawable d = wm.getDrawable();
                if (d != null) {
                    int w = Math.max(1, d.getIntrinsicWidth());
                    int h = Math.max(1, d.getIntrinsicHeight());
                    int sw = Math.min(w, 360);
                    int sh = Math.max(1, h * sw / w);
                    Bitmap bm = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bm);
                    d.setBounds(0, 0, sw, sh);
                    d.draw(c);
                    sampled = sampleBottomBand(bm);
                    bm.recycle();
                }
            } catch (Throwable t) {
                log(t);
            }
            final int value = sampled;
            handler.post(() -> {
                int bar;
                int icon;
                if (value == 0) {
                    // No wallpaper readable: a neutral dark bar keeps the keys visible.
                    bar = 0xE6101216;
                    icon = 0xFFFFFFFF;
                    log("bar colour: fallback dark");
                } else {
                    float lum = 0.2126f * ((value >> 16) & 0xFF)
                            + 0.7152f * ((value >> 8) & 0xFF)
                            + 0.0722f * (value & 0xFF);
                    bar = (0xE0 << 24) | (value & 0xFFFFFF);
                    icon = lum > 140f ? 0xFF121212 : 0xFFFFFFFF;
                    log("bar colour #" + Integer.toHexString(value)
                            + " lum=" + Math.round(lum)
                            + " icon=" + (lum > 140f ? "dark" : "light"));
                }
                sWallBarColor = bar;
                sWallIconColor = icon;
                applyEffective();
            });
        }, "navrs-bar-colour").start();
    }

    /** Average colour of the bottom eighth of the wallpaper, middle 60% horizontally. */
    private static int sampleBottomBand(Bitmap bm) {
        int w = bm.getWidth();
        int h = bm.getHeight();
        if (w <= 0 || h <= 0) {
            return 0;
        }
        int y0 = Math.max(0, h - Math.max(2, h / 8));
        long r = 0;
        long g = 0;
        long b = 0;
        int n = 0;
        for (int y = y0; y < h; y += 2) {
            for (int x = w / 5; x < w * 4 / 5; x += 2) {
                int px = bm.getPixel(x, y);
                r += (px >> 16) & 0xFF;
                g += (px >> 8) & 0xFF;
                b += px & 0xFF;
                n++;
            }
        }
        if (n == 0) {
            return 0;
        }
        return (int) (((r / n) << 16) | ((g / n) << 8) | (b / n));
    }

    private void scheduleFrame() {
        if (!frameScheduled) {
            frameScheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        frameScheduled = false;
        syncKeyguard();
        boolean more = false;
        if (handle != 0 && buffer != null && bitmap != null) {
            more = nativeRender(handle, buffer, frameTimeNanos);
            buffer.position(0);
            try {
                bitmap.copyPixelsFromBuffer(buffer);
            } catch (Throwable t) {
                log(t);
            }
        }
        invalidate();
        if (more) {
            scheduleFrame();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, 0f, 0f, paint);
        }
    }

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (handle != 0) {
                int action = nativeLongPress(handle, System.nanoTime());
                if (action != 0) {
                    onLongPress(action);
                    scheduleFrame();
                }
            }
        }
    };

    private void onLongPress(int action) {
        if (callback != null && callback.onNavLongPress(action)) {
            return;
        }
        if (action == 2) {
            launchDeepSeek();
        } else if (action == 3) {
            // While the standalone recents surface is up, its bar is this taskbar
            // bar, so the panel has to attach to the recents window instead.
            com.android.launcher3.navrs.NavRsBridge.host()
                    .toggleTuning(getContext(), this);
        }
    }

    private void launchDeepSeek() {
        try {
            Intent intent = getContext().getPackageManager()
                    .getLaunchIntentForPackage(DEEPSEEK_PKG);
            if (intent == null) {
                log(TAG + ": DeepSeek not installed");
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            getContext().startActivity(intent);
        } catch (Throwable t) {
            log(TAG + ": launch DeepSeek failed");
            log(t);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                action = 0;
                handler.postDelayed(longPressRunnable, 470);
                break;
            case MotionEvent.ACTION_UP:
                action = 1;
                handler.removeCallbacks(longPressRunnable);
                break;
            case MotionEvent.ACTION_MOVE:
                action = 2;
                break;
            case MotionEvent.ACTION_CANCEL:
                action = 3;
                handler.removeCallbacks(longPressRunnable);
                break;
            default:
                return false;
        }
        int result = 0;
        if (handle != 0) {
            result = nativeTouch(handle, action, event.getX(), event.getY(),
                    System.nanoTime());
        }
        scheduleFrame();
        if (result != 0) {
            perform(result);
        }
        return true;
    }

    private void perform(int action) {
        if (callback != null && callback.onNavButton(action)) {
            return;
        }
        if (taskbarMode) {
            switch (action) {
                case 1:
                    TaskbarBridge.click(TaskbarBridge.BUTTON_BACK, this);
                    break;
                case 2:
                    TaskbarBridge.click(TaskbarBridge.BUTTON_HOME, this);
                    break;
                case 3:
                    TaskbarBridge.click(TaskbarBridge.BUTTON_RECENTS, this);
                    break;
                default:
                    break;
            }
        }
    }

    private static native long nativeCreate(int w, int h, float density);

    private static native void nativeResize(long handle, int w, int h);

    private static native void nativeSetColor(long handle, int argb);

    private static native void nativeSetBarColor(long handle, int argb);

    private static native void nativeSetKeyguard(long handle, boolean keyguard);

    private static native int nativeLongPress(long handle, long timeNanos);

    private static native boolean nativeRender(long handle, ByteBuffer buffer, long timeNanos);

    private static native int nativeTouch(long handle, int action, float x, float y, long timeNanos);

    private static native void nativeDestroy(long handle);

    private static native String nativeVersion();
}
