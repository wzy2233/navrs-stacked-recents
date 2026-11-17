package com.wzy.navrs;

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
