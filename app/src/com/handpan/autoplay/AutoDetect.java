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
        if (notes == null || notes.isEmpty()) return false;

        // Key comes from every note: the accompaniment carries the harmony that disambiguates the
        // key, and a melody-only list often fits two major keys at once (see
        // KeyDetector.bestKeyIndexForSong). Tempo stays on the melody line, where the note onsets
        // are the tune's rhythm rather than the accompaniment's.
        AppPrefs.setKey(c, KeyDetector.bestKeyNameForSong(notes));
        int bpm = TempoEstimator.estimate(SongLoader.monophonic(notes));
        if (bpm > 0) AppPrefs.setBpm(c, bpm);
        return true;
    }
}
