package com.wzy.navrs;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.nio.ByteBuffer;

/**
 * Recents carousel view.
 *
 * <p>Rust owns everything positional: every card's rect, its rounded-corner radius
 * and its brightness are exposed through {@code nativeCardRect} /
 * {@code nativeCardAlpha}. This class only blits the RGBA background and composites
 * card thumbnails into the rects Rust reports, so there is a single source of truth
 * for geometry across all six layouts.
 */
public class RecentsView extends View implements Choreographer.FrameCallback {

    /** A press shorter than this on the empty backdrop closes overview. */
    private static final long TAP_TIMEOUT_MS = 350;

    /** Duration of the home-screen deck slide-in. */
    public static final long SLIDE_IN_MS = 420;

    /** Duration of the empty-backdrop exit slide-out. */
    public static final long SLIDE_OUT_MS = 280;

    private static final String TAG = "NavRS";
    /** Native raster scale; the bitmap is upscaled to the view. */
    private static final float SCALE = 0.32f;
    /** Corner radius as a fraction of card width; must match recents.rs. */
    private static final float CORNER_RATIO = 0.06f;
    /** Two selectable transition feels. */
    private static final int LAUNCH_DURATION_SOFT = 480;
    private static final int LAUNCH_DURATION_FAST = 320;
    /** Draws a magenta outline on every card rect to verify layer alignment. */
    private static final boolean ALIGN_TEST = false;
    /**
     * Fraction of the launch animation spent clearing the neighbours away. They
     * must be gone before the tapped card starts to grow, otherwise the layers
     * overlap visibly.
     */
    private static final float NEIGHBOUR_FRACTION = 0.40f;

    private long handle;
    private Bitmap bitmap;
    private ByteBuffer buffer;
    private int bufW;
    private int bufH;
    private boolean frameScheduled;
    /** True while the cached native frame no longer matches the card list. */
    private boolean nativeStale;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int cardCount;
    private Bitmap[] thumbs;
    /** Live screenshot of the running app, taken when the gesture commits to overview. */
    private Bitmap frontThumb;
    private final float[] tmpRect = new float[4];
    private final float[] tmpCard = new float[6];
    private final Path clipPath = new Path();
    /** Fill used for cards whose thumbnail has not loaded yet. */
    private final Paint placeholder = new Paint();
    private final RectF rect = new RectF();
    private final RectF tmpDraw = new RectF();
    /** Scratch quad reused while animating, to keep the frame allocation-free. */
    private final float[] animQuad = new float[6];
    private OnCardTap onCardTap;
    private OnExit onExit;
    private Runnable onExitStart;
    private OnCardDismiss onCardDismiss;
    private int mode;

    // Launch animation state.
    private int launchFrom = -1;
    private float launchProgress;
    private float[] launchRect;
    private float launchCorner;
    private float[][] launchOthers;
    private Runnable launchEnd;
    private ValueAnimator launchAnimator;
    private boolean launchReturning;
    /** True while the launcher drives the deck from a live gesture (shell owns the front card). */
    private boolean dragDriven;
    /** The drag deck has settled; keep drawing our loaded thumbnails (avoid a blank native frame). */
    private boolean settledDrag;
    /** The shell's exact running-card rect for the current gesture frame (l,t,r,b). */
    private static volatile float[] sRunningRect;
    private float longOut;
    /** Smooth hand-off from the shell's last live-card rect into our settled deck. */
    private boolean handoffActive;
    private float handoffP;
    private final float[] handoffFrom = new float[4];
    private static final long HANDOFF_MS = 220;

    public void setLongOut(float v) {
        longOut = Math.max(0f, Math.min(1f, v));
        invalidate();
    }

    public static void setRunningRect(float l, float t, float r, float b) {
        sRunningRect = new float[]{l, t, r, b};
    }

    /** Directly assigns the two gesture boundaries (owned by the Rust deck). */
    public static void setGates(float shortMax, float longMin) {
        nativeSetGates(shortMax, longMin);
    }

    /** 0 = back to app, 1 = overview, 2 = home (decided in Rust). */
    public static int decideTarget(float progress) {
        return nativeDecideTarget(progress);
    }

    /** Normalizes the raw displacement into the drag shift (computed in Rust). */
    public static float updateProgress(float displacement, int transitionLen, float dragFactor) {
        return nativeUpdateProgress(displacement, transitionLen, dragFactor);
    }
    private float launchCenterX;
    /** Brightness of every card at the moment the transition started. */
    private float[] launchAlpha = new float[0];
    private final android.graphics.ColorMatrix dimMatrix = new android.graphics.ColorMatrix();
    private android.graphics.ColorMatrixColorFilter dimFilter;
    private float dimCached = -1f;
    private float cornerPx = -1f;
    /** Status/navigation bar insets; cards and launch animation use the content area. */
    private int contentTop;
    private int contentBottom;
    // Swipe-up dismissal state.
    private int dismissIndex = -1;
    // App -> overview hand-off: the front card waits on the stock overview card's
    // rect, then settles into whatever layout/style is active.
    private boolean enterHold;
    private boolean enterAnimating;
    private float[] enterFrom;
    private float enterProgress = 1f;
    private boolean slidingIn;
    private boolean slidingOut;
    private float tapDownX;
    private float tapDownY;
    /** A quick upward flick that starts in the bottom gesture area closes overview. */
    private boolean edgeSwipeUp;
    /** uptimeMillis when the current slide-out finishes (0 = not exiting). */
    private static volatile long sExitAnimEndAt;
    private static final long EXIT_GRACE_MS = 60;
    private long tapDownTime;
    private boolean tapMoved;
    private ValueAnimator enterAnimator;
    private float dismissDy;
    private float dismissStartY;
    private float dismissStartX;
    private boolean dismissing;
    private float[][] dismissFrom;
    private float[][] dismissTo;
    private float dismissCardTop;
    private float dismissCardBottom;
    private Runnable dismissEnd;
    private ValueAnimator dismissAnimator;

    /** false = soft 480ms, true = snappy 320ms. */
    private boolean fastTransition;
    /** Fired once per entry, when the front card has slid clear of the status bar. */
    private Runnable onEntryTopCleared;
    private boolean entryTopCleared;

    public interface OnCardTap {
        void onCardTap(int index);
    }

    public interface OnCardDismiss {
        void onCardDismiss(int index);
    }

    public interface OnExit {
        void onExit();
    }

    public RecentsView(Context context, int cardCount) {
        super(context);
        this.cardCount = cardCount;
        setBackgroundColor(0xFF0A0D12);
        // No picture yet: a dark card body. A transparent slot made the overview
        // look empty until the snapshot arrived.
        placeholder.setColor(0xFF151A21);
        placeholder.setStyle(Paint.Style.FILL);
        setClickable(true);
        ensureNative(context);
    }

    private void ensureNative(Context context) {
        try {
            System.loadLibrary("nav_rs");
        } catch (Throwable first) {
            try {
                android.content.pm.ApplicationInfo ai = context.getPackageManager()
                        .getApplicationInfo("com.wzy.navrs", 0);
                System.load(ai.nativeLibraryDir + "/libnav_rs.so");
            } catch (Throwable second) {
                Log.e(TAG, "recents native load failed: " + second);
            }
        }
    }

    // ---------------------------------------------------------------- native loop

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recreate(w, h);
    }

    private void recreate(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int bw = Math.max(1, (int) (w * SCALE));
        int bh = Math.max(1, (int) (h * SCALE));
        if (bitmap != null && bufW == bw && bufH == bh) {
            return;
        }
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
        bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        bufW = bw;
        bufH = bh;
        buffer = ByteBuffer.allocateDirect(bw * bh * 4);
        float density = getResources().getDisplayMetrics().density;
        handle = nativeCreate(bw, bh, density * SCALE, cardCount);
        updateContentGeometry();
        if (mode != 0) {
            nativeSetMode(handle, mode);
        }
        scheduleFrame();
    }

    /** Call with the status/navigation bar heights so cards cover only app content. */
    public void setContentInsets(int top, int bottom) {
        if (contentTop == top && contentBottom == bottom) {
            return;
        }
        contentTop = top;
        contentBottom = bottom;
        if (handle != 0 && getWidth() > 0 && getHeight() > 0) {
            updateContentGeometry();
            invalidate();
        }
    }

    private float lastAspect;

    private void updateContentGeometry() {
        if (handle == 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        int contentH = Math.max(1, getHeight() - contentTop - contentBottom);
        float aspect = lastAspect = getWidth() / (float) contentH;
        float centerY = (contentTop + contentH * 0.5f) / (float) getHeight();
        nativeSetContent(handle, aspect, centerY);
    }

    /** Re-centres the deck vertically, as a fraction of the view height. */
    public void setContentCenterY(float centerY) {
        if (handle == 0 || lastAspect <= 0f || centerY <= 0f || centerY >= 1f
                || getHeight() <= 0) {
            return;
        }
        nativeSetContent(handle, lastAspect, centerY);
        invalidate();
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
        boolean more = false;
        if (handle != 0 && buffer != null && bitmap != null) {
            more = nativeRender(handle, buffer, frameTimeNanos);
            buffer.position(0);
            try {
                bitmap.copyPixelsFromBuffer(buffer);
        nativeStale = false;
            } catch (Throwable t) {
                Log.e(TAG, "render failed", t);
            }
        }
        invalidate();
        if (more) {
            scheduleFrame();
        }
    }

    // --------------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas canvas) {
        if (enterHold) {
            drawEnterHold(canvas);
            return;
        }
        if (enterAnimating) {
            drawEnterAnim(canvas);
            return;
        }
        if (launchFrom >= 0) {
            drawLaunch(canvas);
            return;
        }
        if (dismissActive) {
            drawDismiss(canvas);
            return;
        }
        drawBackground(canvas);
        drawCards(canvas);
        if (ALIGN_TEST) {
            drawAlignmentGuides(canvas);
        }
    }

    /**
     * Debug aid for the "one geometry source" check: outlines every card with the
     * exact rect the native pass used. If a thumbnail is offset, the magenta border
     * will not sit on the card edge.
     */
    private void drawAlignmentGuides(Canvas canvas) {
        if (thumbs == null || handle == 0) {
            return;
        }
        Paint stroke = new Paint();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1f);
        stroke.setColor(0xFFFF00FF);
        int n = Math.min(cardCount, thumbs.length);
        for (int i = 0; i < n; i++) {
            if (!nativeCardQuad(handle, i, tmpCard)) {
                continue;
            }
            rect.set(tmpCard[0], tmpCard[1], tmpCard[2], tmpCard[3]);
            canvas.drawRoundRect(rect, tmpCard[4], tmpCard[4], stroke);
        }
    }

    private void drawBackground(Canvas canvas) {
        // Never blit a frame that still shows a removed card.
        if (bitmap != null && !nativeStale) {
            canvas.drawBitmap(bitmap, null,
                    new RectF(0, 0, getWidth(), getHeight()), paint);
        }
    }

    private void drawCards(Canvas canvas) {
        int n = thumbs == null ? 0 : Math.min(cardCount, thumbs.length);
        // Same stacking order as the native pass: higher index first, 0 on top.
        for (int i = n - 1; i >= 0; i--) {
            if (!nativeCardQuad(handle, i, tmpCard)) {
                continue;
            }
            // A null entry draws the placeholder colour instead of vanishing.
            drawCard(canvas, thumbAt(i), tmpCard, nativeCardAlpha(handle, i));
        }
    }

    /**
     * Draws one card. {@code quad} is [left, top, right, bottom, corner, skew] in
     * view coordinates; skew is the horizontal offset between the top and bottom
     * edges (used by the perspective layouts).
     */
    private void drawCard(Canvas canvas, Bitmap thumb, float[] quad, float alpha) {
        float l = quad[0];
        float t = quad[1];
        float r = quad[2];
        float b = quad[3];
        float rad = quad[4];
        float skew = quad[5];
        if (r - l < 1f || b - t < 1f) {
            return;
        }
        clipPath.reset();
        rect.set(l, t, r, b);
        clipPath.addRoundRect(rect, rad, rad, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clipPath);
        if (skew != 0f) {
            canvas.skew(skew, 0f);
        }
        float brightness = Math.max(0f, Math.min(1f, alpha));
        if (thumb != null) {
            // Dim with a colour matrix, never paint alpha: a dimmed card must stay
            // opaque instead of letting the background show through it.
            applyBrightness(paint, brightness);
            tmpDraw.set(l, t, r, b);
            canvas.drawBitmap(thumb, null, tmpDraw, paint);
            paint.setColorFilter(null);
        } else {
            // No snapshot yet: the placeholder matches the background, so a loading
            // task never looks like a grey ghost card.
            placeholder.setAlpha(255);
            canvas.drawRect(rect, placeholder);
        }
        canvas.restoreToCount(save);
    }

    // -------------------------------------------------------------- launch anim

    /**
     * Grow the tapped card to full screen while the neighbours are pushed away.
     * {@code onFinished} runs when the animation ends (that is where the launcher
     * starts the task).
     */
    public void startLaunch(int index, Runnable onFinished) {
        cancelLaunch();
        final int n = thumbs == null ? 0 : Math.min(cardCount, thumbs.length);
        if (index < 0 || index >= n || !nativeCardQuad(handle, index, tmpCard)) {
            onFinished.run();
            return;
        }
        launchRect = new float[]{tmpCard[0], tmpCard[1], tmpCard[2], tmpCard[3]};
        launchCorner = tmpCard[4];
        launchCenterX = (tmpCard[0] + tmpCard[2]) * 0.5f;
        launchOthers = new float[n][];
        launchAlpha = new float[n];
        for (int i = 0; i < n; i++) {
            launchAlpha[i] = nativeCardAlpha(handle, i);
            if (i == index || thumbAt(i) == null) {
                continue;
            }
            float[] q = new float[6];
            if (nativeCardQuad(handle, i, q)) {
                launchOthers[i] = q;
            }
        }
        launchFrom = index;
        launchEnd = onFinished;
        launchProgress = 0f;
        dragDriven = false;
        if (handle != 0) {
            nativeSetFrozen(handle, true);
        }
        launchAnimator = ValueAnimator.ofFloat(0f, 1f);
        launchAnimator.setDuration(transitionDuration());
        launchAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        launchAnimator.addUpdateListener(a -> {
            launchProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        launchAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                Runnable cb = launchEnd;
                launchEnd = null;
                // Keep the card full-screen until the app window covers the launcher.
                // Resetting here would flash the carousel for a few frames and read
                // as a second launch animation.
                launchReturning = false;
                launchProgress = 1f;
                invalidate();
                if (cb != null) {
                    cb.run();
                }
            }
        });
        launchAnimator.start();
    }

    /**
     * Terminates the current transition exactly once. The pending callback is always
     * invoked, so callers never get stuck in a "launching" state, and the native
     * layout is unfrozen so the static card appears at the captured position.
     */
    private void endTransition() {
        Runnable cb = launchEnd;
        launchEnd = null;
        launchReturning = false;
        dragDriven = false;
        settledDrag = false;
        launchFrom = -1;
        launchOthers = null;
        launchProgress = 0f;
        if (handle != 0) {
            nativeSetFrozen(handle, false);
        }
        invalidate();
        if (cb != null) {
            cb.run();
        }
    }

    private void cancelLaunch() {
        settledDrag = false;
        if (launchAnimator != null) {
            launchAnimator.cancel();
            launchAnimator = null;
        }
        // Cancel with the animation frozen at its current frame: the callback still
        // runs so the host can restore its own state.
        if (launchEnd != null) {
            endTransition();
        } else {
            launchReturning = false;
            launchFrom = -1;
            launchOthers = null;
            launchProgress = 0f;
            if (handle != 0) {
                nativeSetFrozen(handle, false);
            }
            invalidate();
        }
    }

    /**
     * Reverse of {@link #startLaunch}: the full-screen card shrinks back into its
     * slot while the neighbours slide in from the edges. It replays the very same
     * animation with the progress running from 1 to 0, so both directions share
     * one speed, one easing and one stacking rule.
     */
    /** Toggles the transition feel between soft and snappy. */
    public void setFastTransition(boolean fast) {
        fastTransition = fast;
    }

    private int transitionDuration() {
        return fastTransition ? LAUNCH_DURATION_FAST : LAUNCH_DURATION_SOFT;
    }

    /** Aborts an in-flight transition and unfreezes the layout. */
    public void cancelTransition() {
        cancelLaunch();
    }

    public void startReturn(int index, Runnable onFinished) {
        cancelLaunch();
        final int n = thumbs == null ? 0 : Math.min(cardCount, thumbs.length);
        if (index < 0 || index >= n || !nativeCardQuad(handle, index, tmpCard)) {
            if (onFinished != null) {
                onFinished.run();
            }
            return;
        }
        launchRect = new float[]{tmpCard[0], tmpCard[1], tmpCard[2], tmpCard[3]};
        launchCorner = tmpCard[4];
        launchCenterX = (tmpCard[0] + tmpCard[2]) * 0.5f;
        launchOthers = new float[n][];
        launchAlpha = new float[n];
        for (int i = 0; i < n; i++) {
            launchAlpha[i] = nativeCardAlpha(handle, i);
            if (i == index) {
                continue;
            }
            float[] q = new float[6];
            if (nativeCardQuad(handle, i, q)) {
                launchOthers[i] = q;
            }
        }
        launchFrom = index;
        launchEnd = onFinished;
        launchReturning = true;
        launchProgress = 1f;
        if (handle != 0) {
            nativeSetFrozen(handle, true);
        }
        launchAnimator = ValueAnimator.ofFloat(1f, 0f);
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
        launchAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                endTransition();
            }
        });
        launchAnimator.start();
    }

    /**
     * Called once per entry animation, when the growing/shrinking front card has moved
     * clear of the status bar, so the window can re-tint the status-bar icons only when
     * the app's own top bar is no longer what sits behind them.
     */
    public void setOnEntryTopCleared(Runnable callback) {
        onEntryTopCleared = callback;
    }

    /** Starts the launch-style interpolation without animating, for live gesture driving. */
    public void beginDragReturn(int index) {
        cancelLaunch();
        final int n = thumbs == null ? 0 : Math.min(cardCount, thumbs.length);
        if (index < 0 || index >= n || !nativeCardQuad(handle, index, tmpCard)) {
            return;
        }
        launchFrom = index;
        captureSettledQuads(n);
        launchReturning = true;
        dragDriven = true;
        launchProgress = 1f;
        if (handle != 0) {
            nativeSetFrozen(handle, true);
        }
        invalidate();
    }

    /** Snapshots the native settled geometry for the front card and its neighbours. */
    private void captureSettledQuads(int n) {
        if (launchFrom < 0 || !nativeCardQuad(handle, launchFrom, tmpCard)) {
            return;
        }
        launchRect = new float[]{tmpCard[0], tmpCard[1], tmpCard[2], tmpCard[3]};
        launchCorner = tmpCard[4];
        launchCenterX = (tmpCard[0] + tmpCard[2]) * 0.5f;
        launchOthers = new float[n][];
        launchAlpha = new float[n];
        for (int i = 0; i < n; i++) {
            launchAlpha[i] = nativeCardAlpha(handle, i);
            if (i == launchFrom) {
                continue;
            }
            float[] q = new float[6];
            if (nativeCardQuad(handle, i, q)) {
                launchOthers[i] = q;
            }
        }
        if (PROBE) {
            StringBuilder sb = new StringBuilder("settled front=" + quadStr(launchRect));
            for (int i = 0; i < n; i++) {
                if (launchOthers[i] != null) {
                    sb.append(" n").append(i).append('=').append(quadStr(launchOthers[i]));
                }
            }
            Log.d(PROBE_TAG, sb.toString());
        }
    }

    /** Retries {@link #beginDragReturn} until the native card layout is ready. */
    public void ensureDragReturn(int index) {
        if (launchReturning) {
            return;
        }
        beginDragReturn(index);
    }

    /** 1 = app card fullscreen, 0 = settled carousel. */
    public void setDragReturnProgress(float progress) {
        if (!launchReturning) {
            return;
        }
        launchProgress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    /** True while the slide-out exit is still animating (overlay must stay). */
    public static boolean isExitAnimating() {
        long end = sExitAnimEndAt;
        return end != 0L && android.os.SystemClock.uptimeMillis() < end + EXIT_GRACE_MS;
    }

    /** Milliseconds left in the slide-out exit (0 when not exiting). */
    public static long exitAnimRemainingMs() {
        long end = sExitAnimEndAt;
        return end == 0L ? 0L : Math.max(0L, end - android.os.SystemClock.uptimeMillis());
    }

    /** True when the deck is mounted and no transition is running. */
    public boolean isSettled() {
        return getParent() != null && launchFrom < 0 && !enterHold && !enterAnimating
                && !slidingIn && !slidingOut && !dismissActive;
    }

    /** True while the drag-driven interpolation still owns the cards. */
    public boolean isDragReturning() {
        return launchReturning && !settledDrag;
    }

    /** Animates the drag-driven carousel back to its settled layout. */
    public void settleDragReturn(Runnable onFinished) {
        if (!launchReturning) {
            if (onFinished != null) {
                onFinished.run();
            }
            return;
        }
        if (launchAnimator != null && launchAnimator.isRunning()) {
            return;
        }
        // The shell stops updating its live card at its own (stock) rect, which is
        // offset from our deck. Glide from that exact on-screen rect into the deck
        // instead of snapping, then hand drawing back to the native layout.
        dragDriven = false;
        longOut = 0f;
        launchEnd = onFinished;
        float[] run = sRunningRect;
        boolean haveRun = run != null && run[2] > run[0] && run[3] > run[1]
                && launchRect != null;
        if (haveRun && getHeight() > 0) {
            // Park the deck exactly where the window animation left the card: the
            // horizontal offset is already removed by the layout, this fixes the
            // vertical one (the window card is centred above the nav bar).
            setContentCenterY(((run[1] + run[3]) * 0.5f) / getHeight());
            final int n = thumbs == null ? 0 : Math.min(cardCount, thumbs.length);
            captureSettledQuads(n);
        }
        if (haveRun) {
            System.arraycopy(run, 0, handoffFrom, 0, 4);
            handoffActive = true;
            handoffP = 0f;
        } else {
            handoffActive = false;
        }
        launchProgress = 0f;
        launchAnimator = ValueAnimator.ofFloat(0f, 1f);
        launchAnimator.setDuration(haveRun ? HANDOFF_MS : 160);
        launchAnimator.setInterpolator(new DecelerateInterpolator(1.4f));
        launchAnimator.addUpdateListener(a -> {
            handoffP = (float) a.getAnimatedValue();
            launchProgress = 0f;
            invalidate();
        });
        launchAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                launchAnimator = null;
                handoffActive = false;
                handoffP = 0f;
                launchProgress = 0f;
                dragDriven = false;
                longOut = 0f;
                // Leave drag mode for good: normal drawing means paging and taps
                // work again and the settled geometry comes from one source.
                launchReturning = false;
                settledDrag = false;
                launchFrom = -1;
                launchRect = null;
                launchOthers = null;
                if (handle != 0) {
                    nativeSetFrozen(handle, false);
                    nativeSettle(handle);
                }
                probeAbsolute("settle-end");
                Runnable cb = launchEnd;
                launchEnd = null;
                if (cb != null) {
                    cb.run();
                }
                invalidate();
            }
        });
        launchAnimator.start();
    }

    // ---- probe instrumentation (temporary) ----
    private static final boolean PROBE = true;
    private static final String PROBE_TAG = "NavRSProbe";
    private int probeFrame;

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    /** Debug: absolute on-screen rect (px) of every settled card, not view-relative. */
    public void probeAbsolute(String why) {
        if (!PROBE) {
            return;
        }
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        StringBuilder sb = new StringBuilder("ABS " + why + " view=" + loc[0] + "," + loc[1]
                + " " + getWidth() + "x" + getHeight()
                + " dragFrom=" + launchFrom + " drv=" + dragDriven
                + " settledDrag=" + settledDrag);
        int n = thumbs == null ? 0 : Math.min(cardCount, thumbs.length);
        for (int i = 0; i < n; i++) {
            if (!nativeCardQuad(handle, i, tmpCard)) {
                continue;
            }
            sb.append(" c").append(i).append("=")
                    .append(Math.round(tmpCard[0]) + loc[0]).append(",")
                    .append(Math.round(tmpCard[1]) + loc[1]).append(",")
                    .append(Math.round(tmpCard[2]) + loc[0]).append(",")
                    .append(Math.round(tmpCard[3]) + loc[1]);
        }
        Log.d(PROBE_TAG, sb.toString());
    }

    private static String quadStr(float[] q) {
        if (q == null) {
            return "null";
        }
        return String.format(java.util.Locale.US, "%.0f,%.0f,%.0f,%.0f",
                q[0], q[1], q[2], q[3]);
    }

    private void drawLaunch(Canvas canvas) {
        float p = launchProgress;
        // Do NOT blit the frozen native frame here: it still contains every card
        // body at its old position, which would leave a static ring behind while
        // only the thumbnails animate. Paint the empty background and let each
        // card be drawn from its animated quad instead.
        canvas.drawColor(0x00000000);

        // During a live drag/return the whole deck scales and moves together: every
        // neighbour inherits the front card's live scale and translation instead of
        // staying pinned at its settled spot. The launch (tap) animation keeps the
        // old "neighbours leave first" feel.
        boolean groupMove = launchReturning;
        float pushP;
        float grow;
        if (groupMove) {
            grow = p;
            grow = grow * grow * (3f - 2f * grow);
            pushP = p;
        } else {
            pushP = Math.min(1f, p / NEIGHBOUR_FRACTION);
            pushP = pushP * pushP * (3f - 2f * pushP);
            grow = Math.max(0f, (p - NEIGHBOUR_FRACTION) / (1f - NEIGHBOUR_FRACTION));
            grow = grow * grow * (3f - 2f * grow);
        }

        float fl = 0f;
        float ft = 0f;
        float fr = 0f;
        float fb = 0f;
        if (launchRect != null) {
            fl = launchRect[0] * (1f - grow);
            ft = launchRect[1] + ((float) contentTop - launchRect[1]) * grow;
            fr = launchRect[2] + (getWidth() - launchRect[2]) * grow;
            fb = launchRect[3]
                    + (((float) getHeight() - contentBottom) - launchRect[3]) * grow;
        }
        // Use the shell's exact running-card rect while dragging, so the front card and
        // neighbours share the real transform instead of an approximation.
        if (handoffActive && launchRect != null) {
            // Interpolate the whole group from the shell's last rect to the deck rect.
            float e = handoffP * handoffP * (3f - 2f * handoffP);
            fl = handoffFrom[0] + (launchRect[0] - handoffFrom[0]) * e;
            ft = handoffFrom[1] + (launchRect[1] - handoffFrom[1]) * e;
            fr = handoffFrom[2] + (launchRect[2] - handoffFrom[2]) * e;
            fb = handoffFrom[3] + (launchRect[3] - handoffFrom[3]) * e;
        }
        boolean extFront = false;
        if (groupMove && dragDriven) {
            float[] ext = sRunningRect;
            if (ext != null && ext[2] > ext[0] && ext[3] > ext[1]) {
                fl = ext[0];
                ft = ext[1];
                fr = ext[2];
                fb = ext[3];
                extFront = true;
            }
        }

        // Draw back-to-front exactly like the static layout: the higher index is
        // underneath, so the animated frames keep the same stacking order.
        int n = thumbs == null ? 0 : Math.min(cardCount, thumbs.length);
        final boolean probe = PROBE;
        final int frame = ++probeFrame;
        if (probe) {
            Log.d(PROBE_TAG, "f" + frame + " p=" + fmt(p) + " grow=" + fmt(grow)
                    + " raw=" + fmt(com.wzy.navrs.NavRsRecentsActivity.getDragProgress())
                    + " longOut=" + fmt(longOut)
                    + " drv=" + dragDriven + " ext=" + extFront
                    + " hoff=" + fmt(handoffActive ? handoffP : -1f)
                    + " run=" + quadStr(sRunningRect)
                    + " front=" + quadStr(new float[]{fl, ft, fr, fb})
                    + " settled=" + quadStr(launchRect));
        }
        for (int i = n - 1; i >= 0; i--) {
            if (i == launchFrom) {
                if (launchRect == null || thumbAt(i) == null && placeholder == null) {
                    continue;
                }
                if (extFront && probe) {
                    // Drawn underneath the shell's live app tile, which sits in its own
                    // surface above this window: invisible while the tile is up, and
                    // already in place the frame the shell releases it. Skipping this
                    // used to leave the slot empty (the neighbour showed through).
                    Log.d(PROBE_TAG, "f" + frame + " c" + i + "F under-live run="
                            + quadStr(sRunningRect));
                }
                float[] q = animQuad;
                q[0] = fl;
                q[1] = ft;
                q[2] = fr;
                q[3] = fb;
                q[4] = launchCorner * (1f - grow);
                q[5] = 0f;
                if (probe) {
                    Log.d(PROBE_TAG, "f" + frame + " c" + i + "F q=" + quadStr(q));
                }
                drawCard(canvas, thumbAt(i), q, launchAlpha[i]);
                continue;
            }
            if (launchOthers == null || launchOthers[i] == null || launchRect == null) {
                continue;
            }
            float[] src = launchOthers[i];
            float[] q = animQuad;
            float ncx = (src[0] + src[2]) * 0.5f;
            float ncy = (src[1] + src[3]) * 0.5f;
            float fcx = (launchRect[0] + launchRect[2]) * 0.5f;
            float fcy = (launchRect[1] + launchRect[3]) * 0.5f;
            boolean left = ncx < fcx;
            float gsLog = -1f;
            if (groupMove) {
                // Group transform: same scale/translation as the front card.
                float fw = Math.max(1f, launchRect[2] - launchRect[0]);
                float gs = (fr - fl) / fw;
                gsLog = gs;
                float curcx = (fl + fr) * 0.5f;
                float curcy = (ft + fb) * 0.5f;
                float hw = (src[2] - src[0]) * 0.5f * gs;
                float hh = (src[3] - src[1]) * 0.5f * gs;
                float ccx = curcx + (ncx - fcx) * gs;
                float ccy = curcy + (ncy - fcy) * gs;
                if (longOut > 0f) {
                    // Long swipe: the settled deck slides out to the left.
                    ccx -= longOut * (getWidth() + hw);
                }
                q[0] = ccx - hw;
                q[1] = ccy - hh;
                q[2] = ccx + hw;
                q[3] = ccy + hh;
                q[4] = src[4] * gs;
                q[5] = 0f;
            } else {
                System.arraycopy(src, 0, q, 0, 6);
                float distance = left
                        ? q[2] + getWidth() * 0.1f
                        : getWidth() - q[0] + getWidth() * 0.1f;
                float push = pushP * distance * (left ? -1f : 1f);
                q[0] += push;
                q[2] += push;
                q[4] *= 1f - 0.3f * pushP;
            }
            if (probe) {
                Log.d(PROBE_TAG, "f" + frame + " c" + i + "N src=" + quadStr(src)
                        + " q=" + quadStr(q) + " gs=" + fmt(gsLog) + " left=" + left);
            }
            // Once a neighbour is completely outside the view there is nothing to
            // draw: skipping it keeps the frame cost flat.
            if (q[2] < 0f || q[0] > getWidth()) {
                continue;
            }
            drawCard(canvas, thumbAt(i), q, launchAlpha[i]);
        }
    }
    // ---------------------------------------------------------------- public api

    /** Replaces the card contents; entries may be null for "no thumbnail". */
    public void setThumbnails(Bitmap[] bitmaps) {
        thumbs = bitmaps;
        invalidate();
    }

    /** Replaces card 0 with a live screenshot of the running app. */
    public void setFrontThumb(Bitmap bitmap) {
        frontThumb = bitmap;
        Log.d(PROBE_TAG, "frontThumb set " + (bitmap == null ? "null"
                : bitmap.getWidth() + "x" + bitmap.getHeight()));
        invalidate();
    }

    /** Picture for a slot: the live front screenshot wins over the cached snapshot. */
    private Bitmap thumbAt(int index) {
        if (index == 0 && frontThumb != null) {
            return frontThumb;
        }
        return thumbs == null || index < 0 || index >= thumbs.length ? null : thumbs[index];
    }

    public void setOnCardTap(OnCardTap listener) {
        onCardTap = listener;
    }

    public void setOnCardDismiss(OnCardDismiss listener) {
        onCardDismiss = listener;
    }

    public void setOnExit(OnExit listener) {
        onExit = listener;
    }

    /** Runs as soon as the exit slide starts, so the backdrop can fade in sync. */
    public void setOnExitStart(Runnable listener) {
        onExitStart = listener;
    }

    public int getCardCount() {
        return cardCount;
    }

    public void setMode(int mode) {
        this.mode = mode;
        if (handle != 0) {
            nativeSetMode(handle, mode);
            scheduleFrame();
        }
    }

    public void setSpringFast(boolean fast) {
        if (handle != 0) {
            nativeSetSpring(handle, fast);
            scheduleFrame();
        }
    }

    /** Deck spacing: fixed 30% of screen width, or tied to the card width. */
    public void setPitchByCard(boolean on) {
        if (handle != 0) {
            nativeSetPitchByCard(handle, on);
            scheduleFrame();
        }
    }

    /** Card size: false = compact (0.58h), true = large (0.85h). */
    public void setCardLarge(boolean large) {
        if (handle != 0) {
            nativeSetCardLarge(handle, large);
            scheduleFrame();
        }
    }

    /** Rect of a card in view coordinates: [left, top, width, height]. */
    public float[] cardRect(int index) {
        float[] out = new float[4];
        if (handle != 0 && nativeCardQuad(handle, index, tmpCard)) {
            out[0] = tmpCard[0];
            out[1] = tmpCard[1];
            out[2] = tmpCard[2] - tmpCard[0];
            out[3] = tmpCard[3] - tmpCard[1];
        }
        return out;
    }

    // ------------------------------------------------------------------- input

    // ------------------------------------------------------------ swipe dismiss

    // ------------------------------------------------------------ swipe dismiss

    /** Cards must keep drawing while a swipe is in progress, never a blank frame. */
    private void captureDismissLayout() {
        int n = thumbs == null ? 0 : thumbs.length;
        dismissFrom = new float[n][];
        dismissAlpha = new float[n];
        for (int i = 0; i < n; i++) {
            float[] q = new float[6];
            // Raw geometry: a card that enters during the animation must already be
            // drawn at its real position instead of popping in at the end.
            nativeCardQuadRawView(handle, i, q);
            dismissFrom[i] = q;
            dismissAlpha[i] = nativeCardAlpha(handle, i);
        }
    }

    private void beginDismiss(int index, float y) {
        dismissIndex = index;
        dismissDy = 0f;
        dismissStartY = y;
        dismissing = true;
        dismissProgress = 0f;
        dismissTo = null;
        dismissDyNow = 0f;
        dismissClose = 0f;
        dismissActive = true;
        if (handle != 0) {
            // The gesture may have started as a horizontal drag, which the native
            // layer is still tracking. Cancel it explicitly, otherwise it would stay
            // in "dragging" state and the springs would never settle again.
            int s = Math.round(SCALE * 1000f);
            nativeTouch(handle, 3 /* ACTION_CANCEL */,
                    dismissStartX * SCALE, y * SCALE, System.nanoTime());
            nativeSetFrozen(handle, true);
            if (s == 0) {
                // never true; keeps the constant referenced without a warning
            }
        }
        captureDismissLayout();
        invalidate();
    }

    private void updateDismiss(float dy) {
        dismissDy = Math.min(0f, dy);
        invalidate();
    }

    private void endDismiss(float dy, float velocityY) {
        if (!dismissing) {
            return;
        }
        dismissing = false;
        boolean dismiss = dy < -getHeight() * 0.12f || velocityY < -900f;
        if (!dismiss) {
            animateDismissBack();
            return;
        }
        animateDismiss();
    }

    private void animateDismissBack() {
        final float start = dismissDy;
        dismissAnimator = ValueAnimator.ofFloat(0f, 1f);
        dismissAnimator.setDuration(220);
        dismissAnimator.setInterpolator(new DecelerateInterpolator());
        dismissAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            dismissDy = start * (1f - t);
            invalidate();
        });
        dismissAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                resetDismiss();
            }
        });
        dismissAnimator.start();
    }

    private void resetDismiss() {
        final int dismissed = dismissedThumbIndex;
        // The live thumbnail array is compacted only now, so the closing animation
        // still draws each remaining card with its own picture.
        if (dismissedThumbIndex >= 0 && thumbs != null && dismissedThumbIndex < thumbs.length) {
            Bitmap[] shrunk = new Bitmap[thumbs.length - 1];
            int w = 0;
            for (int i = 0; i < thumbs.length; i++) {
                if (i != dismissedThumbIndex) {
                    shrunk[w++] = thumbs[i];
                }
            }
            thumbs = shrunk;
        }
        dismissedThumbIndex = -1;
        dismissActive = false;
        dismissIndex = -1;
        dismissDy = 0f;
        dismissProgress = 0f;
        dismissFrom = null;
        dismissTo = null;
        dismissAlpha = null;
        dismissDyNow = 0f;
        dismissClose = 0f;
        if (handle != 0) {
            nativeSetFrozen(handle, false);
        }
        nativeStale = true;
        // The card list changed, so the cached frame is stale: ask the native side
        // for a fresh one, otherwise the last drawn card stays on screen forever.
        scheduleFrame();
        invalidate();
        if (dismissEnd != null) {
            Runnable cb = dismissEnd;
            dismissEnd = null;
            cb.run();
        }
        if (dismissed >= 0 && onCardDismiss != null) {
            onCardDismiss.onCardDismiss(dismissed);
        }
    }

    private void animateDismiss() {
        final int index = dismissIndex;
        final int n = dismissFrom == null ? 0 : dismissFrom.length;
        if (index < 0 || index >= n || dismissFrom[index] == null) {
            resetDismiss();
            return;
        }
        final float[] own = dismissFrom[index];
        final float startDy = dismissDy;
        // Travel far enough that the card is guaranteed to clear the screen.
        final float endDy = -(own[3] + getHeight() * 0.15f);

        // The card bottom passes the neighbours' top edge at this progress. Both
        // edges move linearly, so the crossing point is exact.
        float neighbourTop = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (i != index && dismissFrom[i] != null) {
                neighbourTop = Math.min(neighbourTop, dismissFrom[i][1]);
            }
        }
        final float passP;
        if (neighbourTop == Float.MAX_VALUE || endDy == startDy) {
            passP = 1f;
        } else {
            passP = ((neighbourTop - own[3]) - startDy) / (endDy - startDy);
        }

        // Capture where the neighbours must end up, then freeze the native layout
        // again so the static frame keeps matching.
        if (handle != 0) {
            nativeSetFrozen(handle, false);
            // Settle first: a diagonal gesture can leave the carousel mid-drag, and
            // the closing animation must target the resting layout.
            nativeSettle(handle);
            int before = nativeCardCount(handle);
            nativeRemoveCard(handle, index);
            int after = nativeCardCount(handle);
            Log.i(TAG, "dismiss idx=" + index + " before=" + before + " after=" + after
                    + " thumbs=" + (thumbs == null ? -1 : thumbs.length));
            nativeSetFrozen(handle, true);
        }
        dismissedThumbIndex = index;
        dismissTo = new float[n][];
        for (int i = 0; i < n; i++) {
            if (i == index) {
                continue;
            }
            int mapped = i > index ? i - 1 : i;
            float[] q = new float[6];
            if (mapped < nativeCardCount(handle)) {
                nativeCardQuadRawView(handle, mapped, q);
                dismissTo[i] = q;
            }
        }

        dismissAnimator = ValueAnimator.ofFloat(0f, 1f);
        dismissAnimator.setDuration(transitionDuration());
        dismissAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        dismissAnimator.addUpdateListener(a -> {
            dismissProgress = (float) a.getAnimatedValue();
            // The dismissed card keeps the finger's offset and then keeps going.
            dismissDyNow = startDy + (endDy - startDy) * dismissProgress;
            // Neighbours only start closing after the card has passed their top edge.
            float t = passP >= 1f ? 0f
                    : Math.min(1f, Math.max(0f, (dismissProgress - passP) / (1f - passP)));
            dismissClose = t * t * (3f - 2f * t);
            invalidate();
        });
        dismissAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                resetDismiss();
            }
        });
        dismissAnimator.start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** True from the moment a card starts being swiped until the layout settles. */
    private boolean dismissActive;
    /** Index of the card still to be removed from the thumbnail array. */
    private int dismissedThumbIndex = -1;
    private float dismissProgress;
    private float dismissDyNow;
    private float dismissClose;
    private float[] dismissAlpha;
    private float[] dismissQuad = new float[6];

    /**
     * Draws the carousel while a card is being swiped away. Everything is composed
     * from the captured quads, so the frame is always complete (no blank frame) and
     * the dragged card tracks the finger exactly.
     */
    private void drawDismiss(Canvas canvas) {
        canvas.drawColor(0x00000000);
        int n = dismissFrom == null ? 0 : dismissFrom.length;
        float dy = dismissing ? dismissDy : dismissDyNow;
        for (int i = n - 1; i >= 0; i--) {
            float[] from = dismissFrom[i];
            if (from == null) {
                continue;
            }
            float alpha = dismissAlpha == null || i >= dismissAlpha.length
                    ? 1f : dismissAlpha[i];
            // During the closing animation 	humbs still holds every card, so the
            // old index maps to its own picture.
            Bitmap thumb = thumbAt(i);
            if (i == dismissIndex) {
                dismissQuad[0] = from[0];
                dismissQuad[1] = from[1] + dy;
                dismissQuad[2] = from[2];
                dismissQuad[3] = from[3] + dy;
                dismissQuad[4] = from[4];
                dismissQuad[5] = 0f;
                drawCard(canvas, thumb, dismissQuad, alpha);
                continue;
            }
            float[] to = dismissTo != null ? dismissTo[i] : null;
            float t = to == null ? 0f : dismissClose;
            dismissQuad[0] = from[0] + (to == null ? 0f : (to[0] - from[0]) * t);
            dismissQuad[1] = from[1] + (to == null ? 0f : (to[1] - from[1]) * t);
            dismissQuad[2] = from[2] + (to == null ? 0f : (to[2] - from[2]) * t);
            dismissQuad[3] = from[3] + (to == null ? 0f : (to[3] - from[3]) * t);
            dismissQuad[4] = from[4] + (to == null ? 0f : (to[4] - from[4]) * t);
            dismissQuad[5] = 0f;
            // Cull by screen bounds only: a card that is fully outside right now may
            // still slide in later, and it will be drawn from its interpolated quad
            // at that moment instead of popping in at the end.
            if (dismissQuad[2] < 0f || dismissQuad[0] > getWidth()
                    || dismissQuad[3] < 0f || dismissQuad[1] > getHeight()) {
                continue;
            }
            drawCard(canvas, thumb, dismissQuad, alpha);
        }
    }

    /** Starts a swipe-dismiss; {@code onFinished} runs when the layout has closed. */
    public void startDismiss(int index, Runnable onFinished) {
        dismissEnd = onFinished;
        beginDismiss(index, 0f);
        dismissing = false;
        animateDismiss();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getAlpha() < 0.9f) {
            // A hidden deck over the stock overview must never consume touches.
            if (PROBE && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                Log.d(PROBE_TAG, "touch ignored: deck alpha=" + getAlpha());
            }
            return false;
        }
        if (PROBE && (event.getActionMasked() == MotionEvent.ACTION_DOWN
                || event.getActionMasked() == MotionEvent.ACTION_UP)) {
            Log.d(PROBE_TAG, "touch a=" + event.getActionMasked()
                    + " x=" + Math.round(event.getX()) + " y=" + Math.round(event.getY())
                    + " launchFrom=" + launchFrom + " returning=" + launchReturning
                    + " settledDrag=" + settledDrag + " hold=" + enterHold);
        }
        if (enterHold || enterAnimating || slidingIn || slidingOut) {
            return true;
        }
        if (launchFrom >= 0 && !settledDrag) {
            return true;
        }
        if (dismissActive && !dismissing) {
            return true;
        }
        // Swipe-up dismissal: a clearly vertical drag on a card lifts it away.
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // The gesture hand-off freezes the deck; a touch on the settled deck
                // must always be interactive again.
                if (handle != 0) {
                    nativeSetFrozen(handle, false);
                }
                dismissStartX = event.getX();
                dismissStartY = event.getY();
                edgeSwipeUp = dismissStartY >= getHeight() * 0.92f;
                tapDownX = dismissStartX;
                tapDownY = dismissStartY;
                tapDownTime = event.getEventTime();
                tapMoved = false;
                dismissIndex = -1;
                // Launcher's PortraitStatesTouchController intercepts vertical drags in
                // overview, which would cancel the swipe before it reaches this view.
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (PROBE) {
                    Log.d(PROBE_TAG, "move dx=" + Math.round(event.getX() - tapDownX)
                            + " dy=" + Math.round(event.getY() - tapDownY)
                            + " x=" + Math.round(event.getX()));
                }
                if (Math.abs(event.getX() - tapDownX) > dp(8)
                        || Math.abs(event.getY() - tapDownY) > dp(8)) {
                    tapMoved = true;
                }
                if (dismissIndex < 0) {
                    float dx = event.getX() - dismissStartX;
                    float dy = event.getY() - dismissStartY;
                    if (edgeSwipeUp && !slidingOut && dy < -dp(10)
                            && event.getEventTime() - tapDownTime <= 700) {
                        // React mid-gesture: the system may cancel the stream once the
                        // flick is recognised, so waiting for ACTION_UP loses the close.
                        Log.i(TAG, "edge swipe up (move): closing overview dy=" + dy);
                        edgeSwipeUp = false;
                        startSlideOutToLeft();
                        return true;
                    }
                    if (dy < -dp(12) && Math.abs(dy) > Math.abs(dx) * 1.5f && handle != 0) {
                        int hit = nativeHitAt(handle, dismissStartX * SCALE, dismissStartY * SCALE);
                        if (hit >= 0) {
                            Log.i(TAG, "swipe-up hit card " + hit);
                            beginDismiss(hit, dismissStartY);
                        } else {
                            Log.i(TAG, "swipe-up missed: dy=" + dy + " dx=" + dx);
                        }
                    }
                }
                if (dismissIndex >= 0) {
                    updateDismiss(event.getY() - dismissStartY);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (edgeSwipeUp && dismissIndex < 0 && !slidingOut
                        && event.getY() - dismissStartY < -dp(8)
                        && event.getEventTime() - tapDownTime <= 700) {
                    // Home-pill flick: every card slides off the left edge while the
                    // backdrop blur clears over the same span.
                    Log.i(TAG, "edge swipe up: closing overview");
                    edgeSwipeUp = false;
                    startSlideOutToLeft();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL && edgeSwipeUp
                        && !slidingOut) {
                    // The system took the stream over (it recognised the home gesture).
                    // That is exactly the case where nothing used to happen.
                    float dyCancel = event.getY() - dismissStartY;
                    Log.i(TAG, "edge swipe cancel: closing overview dy=" + dyCancel);
                    if (dyCancel < -dp(4)) {
                        edgeSwipeUp = false;
                        startSlideOutToLeft();
                        return true;
                    }
                }
                edgeSwipeUp = false;
                if (dismissIndex >= 0 && dismissing) {
                    float vy = 0f;
                    if (event.getHistorySize() > 0) {
                        vy = (event.getY() - event.getHistoricalY(0))
                                / Math.max(1f, event.getEventTime() - event.getHistoricalEventTime(0))
                                * 1000f;
                    }
                    endDismiss(event.getY() - dismissStartY, vy);
                    return true;
                }
                break;
            default:
                break;
        }
        int action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                action = 0;
                break;
            case MotionEvent.ACTION_UP:
                action = 1;
                break;
            case MotionEvent.ACTION_MOVE:
                action = 2;
                break;
            case MotionEvent.ACTION_CANCEL:
                action = 3;
                break;
            default:
                return false;
        }
        if (handle != 0) {
            int tapped = nativeTouch(handle, action, event.getX() * SCALE,
                    event.getY() * SCALE, System.nanoTime());
            if (PROBE && action != 2) {
                float[] q = new float[6];
                boolean has = nativeCardQuad(handle, 0, q);
                Log.d(PROBE_TAG, "nativeTouch a=" + action + " ret=" + tapped
                        + " c0=" + (has ? quadStr(q) : "none"));
            }
            if (tapped >= 0 && onCardTap != null) {
                onCardTap.onCardTap(tapped);
            } else if (tapped < 0 && action == 1 && onExit != null
                    && !tapMoved
                    && event.getEventTime() - tapDownTime <= TAP_TIMEOUT_MS) {
                // Tapping the empty backdrop closes overview (replaces swipe down).
                startSlideOutToLeft();
                return true;
            }
        }
        scheduleFrame();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelEnter();
        cancelLaunch();
        if (dismissAnimator != null) {
            // Drop the listener first: cancel() would otherwise run resetDismiss() and
            // report a removal for a card the user never dismissed.
            dismissAnimator.removeAllListeners();
            dismissAnimator.cancel();
            dismissAnimator = null;
        }
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
        super.onDetachedFromWindow();
    }

    private void applyBrightness(Paint p, float brightness) {
        if (brightness >= 0.995f) {
            p.setColorFilter(null);
            return;
        }
        if (Math.abs(brightness - dimCached) > 0.005f) {
            dimCached = brightness;
            dimMatrix.setScale(brightness, brightness, brightness, 1f);
            dimFilter = new android.graphics.ColorMatrixColorFilter(dimMatrix);
        }
        p.setColorFilter(dimFilter);
    }

    /** Same radius the stock TaskView uses, so the static card and its animations agree. */
    private float cornerPx() {
        if (cornerPx < 0f) {
            try {
                cornerPx = com.android.quickstep.util.TaskCornerRadius.get(getContext());
            } catch (Throwable t) {
                cornerPx = 0f;
            }
            if (cornerPx <= 0f) {
                cornerPx = 28f * getResources().getDisplayMetrics().density;
            }
        }
        return cornerPx;
    }

    // ----------------------------------------------------------- enter hand-off

    /** Shows only the front card on the stock overview card's rect. */
    public void holdEnter(float[] quad) {
        cancelEnter();
        enterFrom = quad;
        enterHold = true;
        enterProgress = 0f;
        invalidate();
    }

    /** Settles the held card into the active layout, revealing the neighbours. */
    public void startEnter() {
        if (enterFrom == null || enterAnimating) {
            return;
        }
        // When the held rect already matches the active layout (compact card), the
        // shell animation IS the entry animation; adding another one made the
        // app-to-overview shrink play twice.
        float[] rest = new float[6];
        if (handle != 0 && nativeCardQuad(handle, 0, rest)) {
            float dx = Math.abs(rest[0] - enterFrom[0]) + Math.abs(rest[2] - enterFrom[2]);
            float dy = Math.abs(rest[1] - enterFrom[1]) + Math.abs(rest[3] - enterFrom[3]);
            if (dx < getWidth() * 0.03f && dy < getHeight() * 0.03f) {
                enterHold = false;
                enterAnimating = false;
                enterFrom = null;
                enterProgress = 1f;
                invalidate();
                return;
            }
        }
        enterHold = false;
        enterAnimating = true;
        enterProgress = 0f;
        enterAnimator = ValueAnimator.ofFloat(0f, 1f);
        enterAnimator.setDuration(180);
        enterAnimator.setInterpolator(new DecelerateInterpolator());
        enterAnimator.addUpdateListener(a -> {
            enterProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        enterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                enterAnimating = false;
                enterFrom = null;
                enterProgress = 1f;
                invalidate();
            }
        });
        enterAnimator.start();
    }

    /** Empty-backdrop exit: the deck slides off the left edge, then closes. */
    public void startSlideOutToLeft() {
        if (slidingOut) {
            return;
        }
        slidingOut = true;
        sExitAnimEndAt = android.os.SystemClock.uptimeMillis() + SLIDE_OUT_MS;
        if (onExitStart != null) {
            onExitStart.run();
        }
        final int width = getWidth() > 0 ? getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        final long t0 = android.os.SystemClock.uptimeMillis();
        Log.d(PROBE_TAG, "slideOut start t=" + t0 + " width=" + width);
        animate().cancel();
        animate().translationX(-width)
                .setDuration(SLIDE_OUT_MS)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .setUpdateListener(a -> {
                    if (PROBE) {
                        Log.d(PROBE_TAG, "slideOut t="
                                + (android.os.SystemClock.uptimeMillis() - t0)
                                + " tx=" + Math.round(getTranslationX()));
                    }
                })
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        Log.d(PROBE_TAG, "slideOut end t="
                                + (android.os.SystemClock.uptimeMillis() - t0));
                        sExitAnimEndAt = 0L;
                        if (onExit != null) {
                            onExit.onExit();
                        }
                    }
                })
                .start();
    }

    /** Home-screen entrance: the settled deck slides in from the left edge. */
    public void startSlideInFromLeft() {
        final int width = getWidth() > 0 ? getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        if (width <= 0) {
            post(this::startSlideInFromLeft);
            return;
        }
        slidingIn = true;
        setTranslationX(-width);
        animate().translationX(0f)
                .setDuration(SLIDE_IN_MS)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        slidingIn = false;
                        setTranslationX(0f);
                        invalidate();
                    }
                })
                .start();
    }

    public void cancelEnter() {
        if (enterAnimator != null) {
            enterAnimator.cancel();
            enterAnimator = null;
        }
        enterHold = false;
        enterAnimating = false;
        enterFrom = null;
        enterProgress = 1f;
    }

    private void drawEnterHold(Canvas canvas) {
        if (enterFrom != null && thumbs != null && thumbs.length > 0) {
            drawCard(canvas, thumbAt(0), enterFrom, 1f);
        }
    }

    private void drawEnterAnim(Canvas canvas) {
        int n = thumbs == null ? 0 : Math.min(cardCount, thumbs.length);
        for (int i = n - 1; i >= 1; i--) {
            if (!nativeCardQuad(handle, i, tmpCard)) {
                continue;
            }
            drawCard(canvas, thumbAt(i), tmpCard,
                    nativeCardAlpha(handle, i) * enterProgress);
        }
        if (n == 0) {
            return;
        }
        float[] rest = new float[6];
        if (enterFrom == null || !nativeCardQuad(handle, 0, rest)) {
            if (nativeCardQuad(handle, 0, rest)) {
                drawCard(canvas, thumbAt(0), rest, 1f);
            }
            return;
        }
        float t = enterProgress * enterProgress * (3f - 2f * enterProgress);
        float[] q = new float[6];
        for (int k = 0; k < 6; k++) {
            q[k] = enterFrom[k] + (rest[k] - enterFrom[k]) * t;
        }
        drawCard(canvas, thumbAt(0), q, 1f);
    }

    // ------------------------------------------------------------ native bridge

    /** Fills {@code out} with [left, top, right, bottom, corner, skew]; false if hidden. */
    /**
     * Unculled geometry converted to view pixels, exactly like {@link #nativeCardQuad}.
     * The native side works in raster buffer pixels (SCALE smaller), so any caller
     * that draws must apply this conversion or the cards come out tiny.
     */
    private boolean nativeCardQuadRawView(long h, int index, float[] out) {
        if (h == 0 || bufW <= 0 || bufH <= 0) {
            return false;
        }
        nativeCardQuadRaw(h, index, out);
        float sx = getWidth() / (float) bufW;
        float sy = getHeight() / (float) bufH;
        out[0] *= sx;
        out[1] *= sy;
        out[2] *= sx;
        out[3] *= sy;
        out[4] = (out[2] - out[0]) * out[4];
        return true;
    }

    private boolean nativeCardQuad(long h, int index, float[] out) {
        if (h == 0 || bufW <= 0 || bufH <= 0) {
            return false;
        }
        if (!nativeCardRect(h, index, tmpRect, out)) {
            return false;
        }
        float sx = getWidth() / (float) bufW;
        float sy = getHeight() / (float) bufH;
        out[0] = out[0] * sx;
        out[1] = out[1] * sy;
        out[2] = out[2] * sx;
        out[3] = out[3] * sy;
        out[4] = (out[2] - out[0]) * out[4];
        return true;
    }

    private static native long nativeCreate(int w, int h, float density, int count);
    private static native void nativeSetGates(float shortMax, float longMin);
    private static native float nativeGetGateShort();
    private static native float nativeGetGateLong();
    private static native int nativeDecideTarget(float progress);
    private static native float nativeUpdateProgress(float displacement, int transitionLen,
            float dragFactor);
    private static native void nativeSetRunningRect(float l, float t, float r, float b);
    private static native void nativeGetRunningRect(float[] out);

    private static native void nativeSetContent(long handle, float aspect, float centerY);

    private static native void nativeSetMode(long handle, int mode);

    private static native void nativeSetSpring(long handle, boolean fast);

    private static native void nativeSetPitchByCard(long handle, boolean on);

    private static native void nativeSetCardLarge(long handle, boolean large);

    private static native void nativeSetFrozen(long handle, boolean frozen);

    private static native void nativeRemoveCard(long handle, int index);

    private static native int nativeHitAt(long handle, float x, float y);

    private static native int nativeCardCount(long handle);

    private static native void nativeSettle(long handle);

    /** Geometry without culling; transitions need the cards that are about to enter. */
    private static native void nativeCardQuadRaw(long handle, int index, float[] out);

    /**
     * Output: out[0..3] = left, top, right, bottom in buffer pixels,
     * out[4] = corner radius in card-width fractions, out[5] = skew factor.
     */
    private static native boolean nativeCardRect(long handle, int index,
                                                 float[] scratch, float[] out);

    private static native float nativeCardAlpha(long handle, int index);

    private static native boolean nativeRender(long handle, ByteBuffer buffer, long timeNanos);

    private static native int nativeTouch(long handle, int action, float x, float y,
                                          long timeNanos);

    private static native void nativeDestroy(long handle);
}
