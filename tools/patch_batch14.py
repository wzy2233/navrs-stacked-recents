#!/usr/bin/env python3
"""batch14: own the nav-bar swipe, keep the desktop backdrop honest, colour the keys.

A. TouchInteractionService -- while NavRsRecentsActivity is the top window, do not create
   an input consumer for a swipe that starts in the nav bar. Starting a window animation
   there is exactly what turned the whole recents surface into a card. With no consumer
   the gesture stays with the activity, whose own edge-swipe closes the deck.

B. QuickstepLauncher -- the "desktop" backdrop was captured 500 ms after NORMAL with only
   a state check. If an app came up in that window, the capture stored that app as the
   desktop and the next backdrop was a live app screenshot instead of the blurred home
   screen. Wait 900 ms, require window focus, and retry once.

C. NavBarView -- the keys were painted with whatever colour the framework's dark-intensity
   pipeline produced, which leaves white keys on a white wallpaper. The bar now paints its
   own background sampled from the wallpaper's bottom band, and the keys are forced to a
   colour that contrasts with it (light bar -> dark keys, dark bar -> light keys).

Java only. Idempotent; first run keeps *.bak.batch14.
"""
import os
import shutil

BASE = "/root/android/lineage/packages/apps/Launcher3"
TIS = BASE + "/quickstep/src/com/android/quickstep/TouchInteractionService.java"
QL = BASE + "/quickstep/src/com/android/launcher3/uioverrides/QuickstepLauncher.java"
NB = BASE + "/quickstep/src/com/android/launcher3/navrs/NavBarView.java"
STAMP = ".bak.batch14"
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
                             % (tag, os.path.basename(path), old[:600]))
        s = s.replace(old, new, 1)
        DONE.append("  ok   %-34s -> %s" % (tag, os.path.basename(path)))
    if s != orig:
        bak = path + STAMP
        if not os.path.exists(bak):
            shutil.copy2(path, bak)
        open(path, "w", encoding="utf-8").write(s)


# ------------------------------------------------ A) our activity keeps its own swipe
edit(TIS, "no consumer under recents", [(
    "NavRS recents is showing",
    """            } else if ((!isOneHandedModeActive && isInSwipeUpTouchRegion)
                    || isHoverActionWithoutConsumer || isOnBubbles) {
""",
    """            } else if (isInSwipeUpTouchRegion
                    && com.wzy.navrs.NavRsRecentsActivity.current() != null) {
                // NavRS recents is the top window and closes itself on an upward swipe.
                // Creating a consumer here starts a window animation that turns the whole
                // surface into a card; leaving the gesture alone lets the deck slide away.
                reasonString.append("NavRS recents is showing, leaving the swipe alone");
                mUncheckedConsumer = InputConsumer.NO_OP;
            } else if ((!isOneHandedModeActive && isInSwipeUpTouchRegion)
                    || isHoverActionWithoutConsumer || isOnBubbles) {
""")])

# ------------------------------------------------ B) only capture a real desktop
edit(QL, "desktop shot: focus + retry", [(
    "scheduleNavRsDesktopShot",
    """        if (state == LauncherState.NORMAL) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (getStateManager().getState() == LauncherState.NORMAL) {
                    captureNavRsDesktopShot();
                }
            }, 500);
        }
""",
    """        if (state == LauncherState.NORMAL) {
            scheduleNavRsDesktopShot(900, true);
        }
"""), (
    "Captures the desktop once the launcher really owns the screen",
    """    private void captureNavRsDesktopShot() {
        if (getStateManager() == null
                || getStateManager().getState() != LauncherState.NORMAL) {
            return;
        }
""",
    """    /**
     * Captures the desktop once the launcher really owns the screen again. Capturing while
     * an app is coming up stored that app as the "desktop", which is why the drag backdrop
     * sometimes showed a live app screenshot instead of the blurred home screen.
     */
    private void scheduleNavRsDesktopShot(long delayMs, boolean retry) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (getStateManager() == null
                    || getStateManager().getState() != LauncherState.NORMAL) {
                return;
            }
            if (!hasWindowFocus()) {
                if (retry) {
                    scheduleNavRsDesktopShot(800, false);
                }
                return;
            }
            captureNavRsDesktopShot();
        }, delayMs);
    }

    private void captureNavRsDesktopShot() {
        if (getStateManager() == null
                || getStateManager().getState() != LauncherState.NORMAL
                || !hasWindowFocus()) {
            return;
        }
""")])

# ------------------------------------------------ C) bar background + contrast keys
edit(NB, "bar: fields", [(
    "sSystemIconColor",
    """    private static volatile int sIconColor = 0xFFFFFFFF;
""",
    """    private static volatile int sIconColor = 0xFFFFFFFF;
    /** Colour the framework asked for; only used until the bar background is known. */
    private static volatile int sSystemIconColor = 0xFFFFFFFF;
    /** Contrast colour derived from the bar background (0 = not computed yet). */
    private static volatile int sAutoIconColor;
    /** Painted behind the keys so they never vanish into the bar underneath. */
    private static volatile int sBarColor;
"""), (
    "sSystemIconColor = argb;",
    """    public static void setIconColor(int argb) {
        sIconColor = argb;
        NavBarView view = sInstance.get();
        if (view != null) {
            view.applyColor(argb);
        }
    }
""",
    """    public static void setIconColor(int argb) {
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
        sBarColor = argb;
        NavBarView view = sInstance.get();
        if (view != null) {
            view.applyBarColor(argb);
        }
    }

    private void applyBarColor(int argb) {
        if (handle != 0) {
            nativeSetBarColor(handle, argb);
            scheduleFrame();
        }
    }
"""), (
    "nativeSetBarColor(handle, sBarColor)",
    """            nativeSetColor(handle, sIconColor);
            nativeSetKeyguard(handle, lastKeyguard);
""",
    """            nativeSetColor(handle, sIconColor);
            nativeSetBarColor(handle, sBarColor);
            nativeSetKeyguard(handle, lastKeyguard);
"""), (
    "nativeSetBarColor(long handle",
    """    private static native void nativeSetColor(long handle, int argb);
""",
    """    private static native void nativeSetColor(long handle, int argb);

    private static native void nativeSetBarColor(long handle, int argb);
"""), (
    "updateBarColorFromWallpaper",
    """    private void scheduleFrame() {
""",
    """    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (sBarColor != 0) {
            applyBarColor(sBarColor);
            return;
        }
        updateBarColorFromWallpaper();
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
                sAutoIconColor = icon;
                setBarColor(bar);
                setIconColor(sSystemIconColor);
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
""")])

print("\n".join(DONE))
print("\nbatch14 applied. Rebuild Launcher3QuickStep and push the new libnav_rs.so.")
