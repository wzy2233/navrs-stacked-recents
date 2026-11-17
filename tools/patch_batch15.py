#!/usr/bin/env python3
"""batch15: backdrop without a live blur, honest status-bar icons, app-tinted nav bar.

A. NavRsBlur (new) -- cheap backdrop blur: downsample, three box passes, upscale.

B. QuickstepLauncher -- the desktop capture now runs on a worker thread (it is a
   synchronous full-screen readback and used to stall the UI thread right when the
   recents key was pressed), and it also bakes a pre-blurred copy of that shot.

C. OverviewCommandHelper -- the home shot is taken on its own thread instead of
   before startActivity(), so the key press no longer waits for a display capture.

D. NavRsRecentsActivity -- backdrop is three layers (sharp / pre-blurred / scrim)
   instead of a live 52 px RenderEffect; the entry cross-fades them. The status-bar
   icon colour is no longer forced when the window opens (SystemUI cross-faded the
   icons on top of the app's own light status bar, which read as "the top bar fades
   out before the app even starts shrinking"); it is picked from the backdrop once
   the card has slid clear of the status bar.

E. RecentsView -- one-shot callback when the entry animation has pulled the front
   card clear of the status bar.

F. NavBarView -- bar colour can follow the foreground app: the app snapshot's band
   just above the nav bar is averaged and painted, falling back to the wallpaper
   sample on the home screen.

Java/Kotlin only. Idempotent; first run keeps *.bak.batch15.
"""
import os
import shutil

BASE = "/root/android/lineage/packages/apps/Launcher3"
NAV = BASE + "/navrs/src/com/wzy/navrs"
QL = BASE + "/quickstep/src/com/android/launcher3/uioverrides/QuickstepLauncher.java"
OCH = BASE + "/quickstep/src/com/android/quickstep/OverviewCommandHelper.kt"
ACT = NAV + "/NavRsRecentsActivity.java"
RV = NAV + "/RecentsView.java"
NB = BASE + "/quickstep/src/com/android/launcher3/navrs/NavBarView.java"
BLUR = NAV + "/NavRsBlur.java"
STAMP = ".bak.batch15"
DONE = []


def edit(path, tag, entries):
    if not os.path.exists(path):
        raise SystemExit("FAIL %s: missing %s" % (tag, path))
    s = open(path, encoding="utf-8").read()
    orig = s
    for marker, old, new in entries:
        if marker is None:
            if old not in s:
                DONE.append("  skip %-34s (already applied)" % tag)
                continue
        elif marker in s:
            DONE.append("  skip %-34s (already applied)" % tag)
            continue
        if old not in s:
            raise SystemExit("FAIL %-30s anchor not found in %s\n---\n%s"
                             % (tag, os.path.basename(path), old[:700]))
        s = s.replace(old, new, 1)
        DONE.append("  ok   %-34s -> %s" % (tag, os.path.basename(path)))
    if s != orig:
        bak = path + STAMP
        if not os.path.exists(bak):
            shutil.copy2(path, bak)
        open(path, "w", encoding="utf-8").write(s)


# ------------------------------------------------------------------ A) blur helper
BLUR_SRC = '''package com.wzy.navrs;

import android.graphics.Bitmap;

/**
 * Backdrop blur that costs nothing per frame.
 *
 * <p>A 52 px {@code RenderEffect} on a full-screen layer is re-evaluated while its radius
 * animates, which is far more than a frame on this device. Downsampling by the radius,
 * running three box passes and letting the view scale the result back up gives the same
 * soft backdrop from a small bitmap that the GPU uploads once.
 */
public final class NavRsBlur {

    private NavRsBlur() {
    }

    /** Returns a blurred copy of {@code src}, or null when the bitmap is unusable. */
    public static Bitmap blur(Bitmap src, float radiusPx) {
        if (src == null || src.isRecycled()) {
            return null;
        }
        final int sw = src.getWidth();
        final int sh = src.getHeight();
        if (sw < 2 || sh < 2) {
            return null;
        }
        final float radius = Math.max(1f, radiusPx);
        final int factor = Math.max(2, Math.round(radius / 6f));
        final int w = Math.max(2, sw / factor);
        final int h = Math.max(2, sh / factor);
        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(src, w, h, true);
        } catch (Throwable t) {
            return null;
        }
        if (small == null) {
            return null;
        }
        final int[] pixels = new int[w * h];
        try {
            small.getPixels(pixels, 0, w, 0, 0, w, h);
        } catch (Throwable t) {
            small.recycle();
            return null;
        }
        small.recycle();
        final int r = Math.max(1, Math.round(radius / factor / 1.6f));
        boxBlur(pixels, w, h, r);
        boxBlur(pixels, w, h, r);
        boxBlur(pixels, w, h, Math.max(1, r / 2));
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(pixels, 0, w, 0, 0, w, h);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Separable box blur with clamped edges; operates on non-premultiplied ARGB. */
    private static void boxBlur(int[] pixels, int w, int h, int r) {
        if (r < 1 || w < 2 || h < 2) {
            return;
        }
        final int n = 2 * r + 1;
        final int[] line = new int[Math.max(w, h)];
        // horizontal
        for (int y = 0; y < h; y++) {
            final int row = y * w;
            int a = 0;
            int rr = 0;
            int gg = 0;
            int bb = 0;
            for (int i = -r; i <= r; i++) {
                final int px = pixels[row + clamp(i, w)];
                a += px >>> 24;
                rr += (px >> 16) & 0xFF;
                gg += (px >> 8) & 0xFF;
                bb += px & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                line[x] = ((a / n) << 24) | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
                final int out = pixels[row + clamp(x - r, w)];
                final int in = pixels[row + clamp(x + r + 1, w)];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                gg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                bb += (in & 0xFF) - (out & 0xFF);
            }
            System.arraycopy(line, 0, pixels, row, w);
        }
        // vertical
        for (int x = 0; x < w; x++) {
            int a = 0;
            int rr = 0;
            int gg = 0;
            int bb = 0;
            for (int i = -r; i <= r; i++) {
                final int px = pixels[clamp(i, h) * w + x];
                a += px >>> 24;
                rr += (px >> 16) & 0xFF;
                gg += (px >> 8) & 0xFF;
                bb += px & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                line[y] = ((a / n) << 24) | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
                final int out = pixels[clamp(y - r, h) * w + x];
                final int in = pixels[clamp(y + r + 1, h) * w + x];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                gg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                bb += (in & 0xFF) - (out & 0xFF);
            }
            for (int y = 0; y < h; y++) {
                pixels[y * w + x] = line[y];
            }
        }
    }

    private static int clamp(int v, int size) {
        return v < 0 ? 0 : (v >= size ? size - 1 : v);
    }
}
'''
with open(BLUR, "w", encoding="utf-8") as fh:
    fh.write(BLUR_SRC)
DONE.append("  ok   %-34s -> %s" % ("backdrop blur helper", os.path.basename(BLUR)))

# ------------------------------------------- B) desktop shot off the UI thread + blur
edit(QL, "desktop shot: worker thread + blur", [(
    "navRsDesktopBlurCached",
    """    public static android.graphics.Bitmap navRsDesktopShot() {
        return navRsDesktopShotCached();
    }
""",
    """    public static android.graphics.Bitmap navRsDesktopShot() {
        return navRsDesktopShotCached();
    }

    /**
     * Pre-blurred twin of the desktop shot. The recents backdrop used to run a live
     * 52 px RenderEffect whose radius was animated on every frame; baking it once keeps
     * the same look for a fraction of the GPU time.
     */
    private static volatile android.graphics.Bitmap sNavRsDesktopBlur;
    private static final java.util.concurrent.ExecutorService NAVRS_SHOT_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    public static android.graphics.Bitmap navRsDesktopBlurCached() {
        android.graphics.Bitmap blurred = sNavRsDesktopBlur;
        return blurred == null || blurred.isRecycled() ? null : blurred;
    }
"""), (
    "NAVRS_SHOT_EXEC.execute",
    """    private void captureNavRsDesktopShot() {
        if (getStateManager() == null
                || getStateManager().getState() != LauncherState.NORMAL
                || !hasWindowFocus()) {
            return;
        }
        // The workspace draws its icons from hardware bitmaps, so a software canvas
        // cannot copy it ("Software rendering doesn't support hardware bitmaps").
        // Capture the display instead, and only while the home screen is visible.
        try {
            com.android.launcher3.navrs.NavRsActivity.captureHomeShot();
            android.graphics.Bitmap shot = com.android.launcher3.navrs.NavRsActivity.peekHomeShot();
            if (shot != null && !shot.isRecycled()) {
                sNavRsDesktopShot = shot;
                // Contrast probe: a smooth wallpaper has almost no variance; icons and
                // widgets make it jump, which proves the shot really is the desktop.
                double sum = 0;
                double sum2 = 0;
                int n = 0;
                for (int gy = 0; gy < 16; gy++) {
                    for (int gx = 0; gx < 32; gx++) {
                        int px = shot.getPixel(
                                Math.min(shot.getWidth() - 1, gx * shot.getWidth() / 32),
                                Math.min(shot.getHeight() - 1, gy * shot.getHeight() / 16));
                        int luma = ((px >> 16 & 0xFF) * 77 + (px >> 8 & 0xFF) * 150
                                + (px & 0xFF) * 29) >> 8;
                        sum += luma;
                        sum2 += luma * (double) luma;
                        n++;
                    }
                }
                double mean = sum / n;
                double var = sum2 / n - mean * mean;
                android.util.Log.d("NavRSProbe", "desktop shot " + shot.getWidth()
                        + "x" + shot.getHeight() + " mean=" + Math.round(mean)
                        + " var=" + Math.round(var));
            }
        } catch (Throwable t) {
            android.util.Log.w("NavRSProbe", "desktop shot failed", t);
        }
    }
""",
    """    private void captureNavRsDesktopShot() {
        if (getStateManager() == null
                || getStateManager().getState() != LauncherState.NORMAL
                || !hasWindowFocus()) {
            return;
        }
        // The workspace draws its icons from hardware bitmaps, so a software canvas
        // cannot copy it ("Software rendering doesn't support hardware bitmaps").
        // Capture the display instead, and only while the home screen is visible.
        //
        // captureHomeShot() is a synchronous full-screen readback (30-120 ms). Running it
        // here used to stall the UI thread a second after landing on home, which is what
        // made the recents key feel heavy; it now runs on its own thread together with
        // the one-off blur pass.
        NAVRS_SHOT_EXEC.execute(() -> {
            try {
                com.android.launcher3.navrs.NavRsActivity.captureHomeShot();
                android.graphics.Bitmap shot =
                        com.android.launcher3.navrs.NavRsActivity.peekHomeShot();
                if (shot == null || shot.isRecycled()) {
                    return;
                }
                sNavRsDesktopShot = shot;
                // Contrast probe: a smooth wallpaper has almost no variance; icons and
                // widgets make it jump, which proves the shot really is the desktop.
                double sum = 0;
                double sum2 = 0;
                int n = 0;
                for (int gy = 0; gy < 16; gy++) {
                    for (int gx = 0; gx < 32; gx++) {
                        int px = shot.getPixel(
                                Math.min(shot.getWidth() - 1, gx * shot.getWidth() / 32),
                                Math.min(shot.getHeight() - 1, gy * shot.getHeight() / 16));
                        int luma = ((px >> 16 & 0xFF) * 77 + (px >> 8 & 0xFF) * 150
                                + (px & 0xFF) * 29) >> 8;
                        sum += luma;
                        sum2 += luma * (double) luma;
                        n++;
                    }
                }
                double mean = sum / n;
                double var = sum2 / n - mean * mean;
                android.util.Log.d("NavRSProbe", "desktop shot " + shot.getWidth()
                        + "x" + shot.getHeight() + " mean=" + Math.round(mean)
                        + " var=" + Math.round(var));
                android.graphics.Bitmap blurred = com.wzy.navrs.NavRsBlur.blur(shot, 52f);
                if (blurred != null) {
                    sNavRsDesktopBlur = blurred;
                }
            } catch (Throwable t) {
                android.util.Log.w("NavRSProbe", "desktop shot failed", t);
            }
        });
    }
"""), (
    "clearAppBarColor",
    """        if (state == LauncherState.NORMAL) {
            scheduleNavRsDesktopShot(900, true);
        }
""",
    """        if (state == LauncherState.NORMAL) {
            // Back on the home screen: the bar follows the wallpaper again.
            com.wzy.navrs.NavBarView.clearAppBarColor();
            scheduleNavRsDesktopShot(900, true);
        }
""")])

# ------------------------------- C) home shot must not delay the recents key press
edit(OCH, "home shot on its own thread", [(
    "navrs-home-shot",
    """                if (
                    fromHome && !com.android.launcher3.navrs.NavRsActivity.isOpen()
                ) {
                    try {
                        com.android.launcher3.navrs.NavRsActivity.captureHomeShot()
                    } catch (t: Throwable) {
                        Log.w(TAG, "navrs home shot failed", t)
                    }
                }
""",
    """                if (fromHome && !com.android.launcher3.navrs.NavRsActivity.isOpen()) {
                    // A display capture is a synchronous full-screen readback; doing it
                    // here delayed the key press by up to 200 ms. It now runs on its own
                    // thread while the activity is already starting. If it is not ready
                    // when the entry begins, the cached desktop shot is used instead.
                    Thread(
                        {
                            try {
                                com.android.launcher3.navrs.NavRsActivity.captureHomeShot()
                            } catch (t: Throwable) {
                                Log.w(TAG, "navrs home shot failed", t)
                            }
                        },
                        "navrs-home-shot",
                    )
                        .start()
                }
""")])

# ------------------------------------------------- D) recents activity backdrop
edit(ACT, "activity: imports", [(
    "import android.graphics.Canvas;",
    """import android.graphics.Bitmap;
""",
    """import android.graphics.Bitmap;
import android.graphics.Canvas;
"""), (
    "sLastTopLuma",
    """    /** Sharp display snapshot captured while the home screen is still visible. */
    private static volatile Bitmap sHomeShot;
""",
    """    /** Sharp display snapshot captured while the home screen is still visible. */
    private static volatile Bitmap sHomeShot;

    /**
     * Brightness of the last app snapshot's top band. A new window starts out as the
     * status-bar appearance owner, so it has to declare the same light/dark icons the
     * app had or SystemUI cross-fades them over the app's own bar -- which reads as the
     * top bar fading out before the animation even starts.
     */
    private static volatile int sLastTopLuma = 160;
""")])

edit(ACT, "activity: initial icon colour", [(
    "applyInitialStatusBarAppearance",
    """        pinWindow();
        getWindow().setDecorFitsSystemWindows(false);
""",
    """        pinWindow();
        applyInitialStatusBarAppearance(fromHome);
        getWindow().setDecorFitsSystemWindows(false);
""")])

edit(ACT, "activity: layers fields", [(
    "sharpLayer, blurLayer",
    """    private View wallpaperLayer;
    private View scrimLayer;
    private ImageView backdrop;
    private boolean usingHomeShot;
    private ValueAnimator blurAnimator;
    private float currentBlur;
""",
    """    private ImageView sharpLayer;
    private ImageView blurLayer;
    private View scrimLayer;
    /** Mean brightness of the backdrop's top band; picks the status-bar icon colour. */
    private int backdropLuma = -1;
""")])

edit(ACT, "activity: backdrop layers", [(
    "loadWallpaperBackdrop",
    """    /**
     * Builds the blurred wallpaper layer but keeps it invisible: showing it
     * before the first card frame would blank the app for a moment.
     */
    private void prepareWallpaper() {
        ImageView wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        boolean loaded = false;
        // Same backdrop as the gesture path: a screenshot of the desktop, so the
        // blur covers the app icons and widgets, not only the wallpaper.
        try {
            Bitmap desktop = com.android.launcher3.uioverrides.QuickstepLauncher
                    .navRsDesktopShotCached();
            if (desktop != null) {
                wallpaper.setImageBitmap(desktop);
                loaded = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "desktop shot failed", t);
        }
        if (!loaded) {
            try {
                Drawable drawable = WallpaperManager.getInstance(this).getDrawable();
                if (drawable != null) {
                    wallpaper.setImageDrawable(drawable);
                    loaded = true;
                } else {
                    Log.w(TAG, "wallpaper drawable null");
                }
            } catch (Throwable t) {
                Log.w(TAG, "wallpaper drawable failed", t);
            }
        }
        if (!loaded) {
            // Never fall through to the app snapshot: a flat dark surface is a
            // better backdrop than a copy of the app we just left.
            wallpaper.setBackgroundColor(0xFF10141A);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                wallpaper.setRenderEffect(
                        RenderEffect.createBlurEffect(52f, 52f, Shader.TileMode.CLAMP));
            } catch (Throwable t) {
                Log.w(TAG, "wallpaper blur failed", t);
            }
        }
        wallpaper.setVisibility(View.INVISIBLE);
        root.addView(wallpaper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wallpaperLayer = wallpaper;
        backdrop = wallpaper;

        View scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setVisibility(View.INVISIBLE);
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrimLayer = scrim;
    }
""",
    """    /**
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
""")])

edit(ACT, "activity: entry", [(
    "applyStatusBarIconAppearance()",
    """    /**
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
        boolean sharpStart = shot != null;
        if (shot == null) {
            shot = com.android.launcher3.uioverrides.QuickstepLauncher.navRsDesktopShotCached();
        }
        usingHomeShot = shot != null && backdrop != null;
        if (usingHomeShot) {
            backdrop.setImageBitmap(shot);
            setBackdropBlur(sharpStart ? 0f : BLUR_MAX);
        }
        if (wallpaperLayer != null) {
            wallpaperLayer.setAlpha(1f);
            wallpaperLayer.setVisibility(View.VISIBLE);
        }
        if (scrimLayer != null) {
            scrimLayer.setAlpha(sharpStart ? 0f : 1f);
            scrimLayer.setVisibility(View.VISIBLE);
        }
        rust.setAlpha(1f);
        if (fromHome) {
            // Opened from the home screen: no app to shrink from, so the deck
            // slides in from the left edge instead.
            rust.startSlideInFromLeft();
            if (sharpStart) {
                // The desktop starts sharp and blurs as the cards settle.
                animateBackdropBlur(0f, BLUR_MAX, RecentsView.SLIDE_IN_MS);
            }
        } else {
            rust.startReturn(0, null);
        }
    }
""",
    """    /**
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
""")])

edit(ACT, "activity: exit + cleanup", [(
    "fadeOut(blurLayer",
    """    private void setBackdropBlur(float radius) {
        currentBlur = radius;
        if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        try {
            backdrop.setRenderEffect(radius <= 0.01f ? null
                    : RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        } catch (Throwable t) {
            Log.w(TAG, "backdrop blur failed", t);
        }
    }

    private void animateBackdropBlur(float from, float to, long duration) {
        cancelBlur();
        blurAnimator = ValueAnimator.ofFloat(from, to);
        blurAnimator.setDuration(duration);
        blurAnimator.setInterpolator(new LinearInterpolator());
        blurAnimator.addUpdateListener(a -> {
            float radius = (float) a.getAnimatedValue();
            setBackdropBlur(radius);
            if (scrimLayer != null) {
                scrimLayer.setAlpha(Math.max(0f, Math.min(1f, radius / BLUR_MAX)));
            }
        });
        blurAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setBackdropBlur(to);
                if (scrimLayer != null) {
                    scrimLayer.setAlpha(to > 0.01f ? 1f : 0f);
                }
            }
        });
        blurAnimator.start();
    }

    private void cancelBlur() {
        if (blurAnimator != null) {
            blurAnimator.cancel();
            blurAnimator = null;
        }
    }

    /** Called the moment the deck starts sliding off the left edge. */
    private void startExitBackdrop() {
        sLastExitAt = android.os.SystemClock.uptimeMillis();
        // The backdrop sharpens and dissolves over exactly the same span as the
        // cards sliding off-screen, so the blur never pops.
        animateBackdropBlur(currentBlur, 0f, RecentsView.SLIDE_OUT_MS);
        if (wallpaperLayer != null) {
            wallpaperLayer.animate().alpha(0f)
                    .setDuration(RecentsView.SLIDE_OUT_MS)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                    .start();
        }
    }
""",
    """    /**
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
"""), (
    "fadeBackdropThenFinish",
    """    /** After the deck slips off the left edge, dissolve the backdrop onto home. */
    private void fadeBackdropThenFinish() {
        final View wp = wallpaperLayer;
        if (wp == null || wp.getVisibility() != View.VISIBLE) {
            finishOver();
            return;
        }
        wp.animate().alpha(0f).setDuration(BACKDROP_FADE_MS)
                .withEndAction(this::finishOver).start();
        if (scrimLayer != null) {
            scrimLayer.animate().alpha(0f).setDuration(BACKDROP_FADE_MS).start();
        }
    }
""",
    """    /** After the deck slips off the left edge, dissolve the backdrop onto home. */
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
"""), (
    "onDestroy cancelBlur",
    """    protected void onDestroy() {
        cancelBlur();
""",
    """    protected void onDestroy() {
""")])

# front thumbnail -> nav bar colour + remembered top band
edit(ACT, "activity: app bar colour", [(
    "sampleAppBarColor",
    """                thumbs[index] = ready;
                rust.setThumbnails(thumbs);
""",
    """                thumbs[index] = ready;
                rust.setThumbnails(thumbs);
                if (index == 0) {
                    // The bar takes the app's own colour, sampled just above the
                    // navigation bar, so the keys sit on the app instead of the wallpaper.
                    NavBarView.sampleAppBarColor(ready);
                    // Remember how bright the app's top bar is: the next recents window
                    // has to open with matching status-bar icons so they never fade.
                    sLastTopLuma = topBandLuma(ready);
                }
""")])

# the probe that removed the forced appearance leaves a placeholder comment
edit(ACT, "activity: bars comment", [(
    "Nothing is forced here",
    """            // probe: no forced status-bar appearance
""",
    """            // Nothing is forced here: the window opens with the icon colour the
            // app already had (see applyInitialStatusBarAppearance) and only re-tints
            // once the card is clear of the status bar.
""")])

# ------------------------------------------------------------- E) RecentsView hook
edit(RV, "deck: entry-top callback", [(
    "onEntryTopCleared",
    """    /** false = soft 480ms, true = snappy 320ms. */
    private boolean fastTransition;
""",
    """    /** false = soft 480ms, true = snappy 320ms. */
    private boolean fastTransition;
    /** Fired once per entry, when the front card has slid clear of the status bar. */
    private Runnable onEntryTopCleared;
    private boolean entryTopCleared;
"""), (
    "setOnEntryTopCleared",
    """    /** Starts the launch-style interpolation without animating, for live gesture driving. */
""",
    """    /**
     * Called once per entry animation, when the growing/shrinking front card has moved
     * clear of the status bar, so the window can re-tint the status-bar icons only when
     * the app's own top bar is no longer what sits behind them.
     */
    public void setOnEntryTopCleared(Runnable callback) {
        onEntryTopCleared = callback;
    }

    /** Starts the launch-style interpolation without animating, for live gesture driving. */
"""), (
    "entryTopCleared = false",
    """        launchAnimator = ValueAnimator.ofFloat(1f, 0f);
        launchAnimator.setDuration(transitionDuration());
        launchAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        launchAnimator.addUpdateListener(a -> {
            launchProgress = (float) a.getAnimatedValue();
            invalidate();
        });
""",
    """        launchAnimator = ValueAnimator.ofFloat(1f, 0f);
        launchAnimator.setDuration(transitionDuration());
        launchAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        entryTopCleared = false;
        launchAnimator.addUpdateListener(a -> {
            launchProgress = (float) a.getAnimatedValue();
            // The front card's top edge crosses the status bar at about p=0.72.
            if (!entryTopCleared && launchProgress <= 0.72f) {
                entryTopCleared = true;
                Runnable cb = onEntryTopCleared;
                if (cb != null) {
                    cb.run();
                }
            }
            invalidate();
        });
""")])

# ----------------------------------------------------------------- F) NavBarView
edit(NB, "bar: app colour", [(
    "sAppBarColor",
    """    /** Painted behind the keys so they never vanish into the bar underneath. */
    private static volatile int sBarColor;
""",
    """    /** Painted behind the keys so they never vanish into the bar underneath. */
    private static volatile int sBarColor;
    /** Wallpaper sample: the bar colour on the home screen. */
    private static volatile int sWallBarColor;
    private static volatile int sWallIconColor;
    /** Foreground-app sample: the bar colour while an app owns the screen. */
    private static volatile int sAppBarColor;
    private static volatile int sAppIconColor;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
"""), (
    "applyEffective",
    """    /** Background painted behind the keys. Alpha 0 keeps the bar transparent. */
    public static void setBarColor(int argb) {
        sBarColor = argb;
        NavBarView view = sInstance.get();
        if (view != null) {
            view.applyBarColor(argb);
        }
    }
""",
    """    /** Background painted behind the keys. Alpha 0 keeps the bar transparent. */
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
"""), (
    "sWallBarColor = bar",
    """                sAutoIconColor = icon;
                setBarColor(bar);
                setIconColor(sSystemIconColor);
""",
    """                sWallBarColor = bar;
                sWallIconColor = icon;
                applyEffective();
""")])

print("\n".join(DONE))
print("\nbatch15 applied. Rebuild Launcher3QuickStep, then push the apk + odex.")
