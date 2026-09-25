package com.handpan.autoplay;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted settings: the nine calibrated pad positions, the key, playback speed and BPM.
 *
 * <p>Pads are addressed by {@code slot} 0..8, in the instrument's on-screen order
 * (see {@link PadMapper}).
 */
public final class AppPrefs {

    private static final String FILE = "handpan_prefs";
    public static final int SLOTS = PadMapper.SLOTS;

    private static final String K_X = "pad_x_";
    private static final String K_Y = "pad_y_";
    private static final String K_CAL_W = "cal_display_w";
    private static final String K_CAL_H = "cal_display_h";
    private static final String K_KEY = "key_name";
    private static final String K_SPEED = "speed";
    private static final String K_BPM = "bpm";
    private static final String K_USE_ZERO = "use_zero";
    private static final String K_LAYOUT = "layout_version";
    private static final String K_MODE = "play_mode";
    private static final String K_CHORD = "chord_limit";

    /** How many pads may be pressed together. The instrument has nine; more than that is moot. */
    public static final int[] MAX_CHORD_OPTIONS = {1, 2, 3, 4, 5, 6, 9};

    public static final int DEFAULT_CHORD = 4;

    /**
     * Bumped whenever the meaning of a calibration slot changes. The 9-pad octave layout replaced
     * the earlier 8-pad digits 1-7+0 layout, so slot 7 no longer means the same key; a stored
     * calibration from the old layout must be discarded instead of silently mis-tapped.
     */
    public static final int LAYOUT_VERSION = 2;

    private AppPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Name of a slot as shown to the user, e.g. slot 0 -> "高音 1". */
    public static String digitOf(int slot) {
        return PadMapper.labelOf(slot);
    }

    public static void setPad(Context c, int slot, float x, float y) {
        sp(c).edit().putFloat(K_X + slot, x).putFloat(K_Y + slot, y).apply();
    }

    /** @return {x, y} or null when this slot has not been calibrated. */
    public static float[] getPad(Context c, int slot) {
        SharedPreferences p = sp(c);
        if (!p.contains(K_X + slot)) return null;
        return new float[]{p.getFloat(K_X + slot, 0f), p.getFloat(K_Y + slot, 0f)};
    }

    public static boolean hasCalibration(Context c) {
        for (int i = 0; i < SLOTS; i++) {
            if (getPad(c, i) == null) return false;
        }
        return true;
    }

    public static int calibratedCount(Context c) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (getPad(c, i) != null) n++;
        }
        return n;
    }

    public static void clearCalibration(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        for (int i = 0; i < SLOTS; i++) {
            e.remove(K_X + i);
            e.remove(K_Y + i);
        }
        e.apply();
    }

    public static void setCalibrationDisplay(Context c, int w, int h) {
        sp(c).edit().putInt(K_CAL_W, w).putInt(K_CAL_H, h).apply();
    }

    public static int calWidth(Context c) {
        return sp(c).getInt(K_CAL_W, 0);
    }

    public static int calHeight(Context c) {
        return sp(c).getInt(K_CAL_H, 0);
    }

    public static String getKey(Context c) {
        return sp(c).getString(K_KEY, "C");
    }

    public static void setKey(Context c, String key) {
        sp(c).edit().putString(K_KEY, key).apply();
    }

    public static int getRootPitchClass(Context c) {
        int pc = ScaleMapper.rootPitchClass(getKey(c));
        return pc < 0 ? 0 : pc;
    }

    /** Playback speed multiplier. 1.0 = original tempo. */
    public static float getSpeed(Context c) {
        return sp(c).getFloat(K_SPEED, 1.0f);
    }

    public static void setSpeed(Context c, float v) {
        sp(c).edit().putFloat(K_SPEED, v).apply();
    }

    /** Beats per minute used by the numbered-notation text parser. */
    public static int getBpm(Context c) {
        return sp(c).getInt(K_BPM, 90);
    }

    public static void setBpm(Context c, int v) {
        sp(c).edit().putInt(K_BPM, v).apply();
    }

    public static boolean getUseZeroPad(Context c) {
        return sp(c).getBoolean(K_USE_ZERO, false);
    }

    public static void setUseZeroPad(Context c, boolean v) {
        sp(c).edit().putBoolean(K_USE_ZERO, v).apply();
    }

    public static int getLayoutVersion(Context c) {
        return sp(c).getInt(K_LAYOUT, 0);
    }

    public static void setLayoutVersion(Context c, int v) {
        sp(c).edit().putInt(K_LAYOUT, v).apply();
    }

    /**
     * Drops a calibration recorded under an older pad layout.
     *
     * @return true when a stale calibration was discarded and the user must recalibrate.
     */
    public static boolean discardStaleCalibration(Context c) {
        if (getLayoutVersion(c) == LAYOUT_VERSION) return false;
        boolean had = calibratedCount(c) > 0;
        clearCalibration(c);
        setLayoutVersion(c, LAYOUT_VERSION);
        return had;
    }

    // ---------------------------------------------------------------- play modes

    /** Play just the selected song, then stop. */
    public static final int MODE_SINGLE = 0;
    /** After each song, continue with the next entry in the song list. */
    public static final int MODE_SEQUENCE = 1;
    /** After each song, continue with a random other entry. */
    public static final int MODE_RANDOM = 2;

    /** Labels for the mode spinner, indexed by the MODE_* constants. */
    public static final String[] MODE_LABELS = {"单曲循环", "顺序演奏（接下一首）", "随机演奏"};

    public static int getPlayMode(Context c) {
        return sp(c).getInt(K_MODE, MODE_SINGLE);
    }

    public static void setPlayMode(Context c, int mode) {
        sp(c).edit().putInt(K_MODE, mode).apply();
    }

    /**
     * Most pads pressed in one chord.
     *
     * <p>Raising this lets more of a chord through, at the cost of muddiness and of leaning on the
     * gesture stroke limit. Bounded by {@link #MAX_CHORD_OPTIONS} and, at dispatch time, by
     * {@code GestureDescription.getMaxStrokeCount()}.
     */
    public static int getChordLimit(Context c) {
        return sp(c).getInt(K_CHORD, DEFAULT_CHORD);
    }

    public static void setChordLimit(Context c, int v) {
        sp(c).edit().putInt(K_CHORD, v).apply();
    }
}
