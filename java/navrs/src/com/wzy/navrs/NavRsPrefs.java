package com.wzy.navrs;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted carousel tuning, mirroring the demo's five controls. */
final class NavRsPrefs {

    private static final String FILE = "navrs_recents";
    private static final String KEY_MODE = "mode";
    private static final String KEY_SPRING = "spring_fast";
    private static final String KEY_PITCH = "pitch_by_card";
    private static final String KEY_SIZE = "card_large";
    private static final String KEY_ANIM = "fast_transition";

    private NavRsPrefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int mode(Context c) {
        // Only the gear deck is supported now; the other layouts were removed.
        return 5;
    }

    static boolean springFast(Context c) {
        return prefs(c).getBoolean(KEY_SPRING, false);
    }

    static boolean pitchByCard(Context c) {
        return prefs(c).getBoolean(KEY_PITCH, false);
    }

    static boolean cardLarge(Context c) {
        return prefs(c).getBoolean(KEY_SIZE, false);
    }

    static boolean fastTransition(Context c) {
        return prefs(c).getBoolean(KEY_ANIM, false);
    }

    static void setMode(Context c, int mode) {
        prefs(c).edit().putInt(KEY_MODE, Math.abs(mode) % 6).apply();
    }

    static void setSpringFast(Context c, boolean fast) {
        prefs(c).edit().putBoolean(KEY_SPRING, fast).apply();
    }

    static void setPitchByCard(Context c, boolean byCard) {
        prefs(c).edit().putBoolean(KEY_PITCH, byCard).apply();
    }

    static void setCardLarge(Context c, boolean large) {
        prefs(c).edit().putBoolean(KEY_SIZE, large).apply();
    }

    static void setFastTransition(Context c, boolean fast) {
        prefs(c).edit().putBoolean(KEY_ANIM, fast).apply();
    }

    static void apply(Context c, RecentsView v) {
        v.setMode(mode(c));
        v.setSpringFast(springFast(c));
        v.setPitchByCard(pitchByCard(c));
        v.setCardLarge(cardLarge(c));
        v.setFastTransition(fastTransition(c));
    }
}
