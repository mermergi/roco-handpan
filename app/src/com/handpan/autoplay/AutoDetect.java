package com.handpan.autoplay;

import android.content.Context;

import java.util.List;

/**
 * The "auto mode" half of parsing: detect the key and tempo and write them into the settings.
 *
 * <p>Used by both automatic entry points - importing a file, and the 自动解析 button - so the two can
 * never drift apart. 手动解析 deliberately does <em>not</em> call this: its whole point is to keep the
 * parameters the user set instead of overwriting them with detection results.
 */
public final class AutoDetect {

    private AutoDetect() {}

    /** @return true when detection produced something and the settings were updated. */
    public static boolean apply(Context c, List<RawNote> notes) {
        List<RawNote> melody = SongLoader.monophonic(notes);
        if (melody.isEmpty()) return false;
        AppPrefs.setKey(c, KeyDetector.bestKeyName(melody));
        int bpm = TempoEstimator.estimate(melody);
        if (bpm > 0) AppPrefs.setBpm(c, bpm);
        return true;
    }
}
