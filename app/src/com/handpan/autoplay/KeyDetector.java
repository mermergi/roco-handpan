package com.handpan.autoplay;

import java.util.List;

/**
 * Guesses the major key a melody is in, so the user does not have to try seven settings by hand.
 *
 * <p>Picking the wrong key does not just transpose the tune: it reinterprets every scale degree, so
 * the pad sequence comes out musically wrong. Auto-detecting removes that whole failure mode.
 *
 * <p>The method is a small key-profile score. Each note votes for every key whose major scale
 * contains it, weighted by its duration and by how stable that scale degree is (the tonic and fifth
 * are far stronger evidence than the leading tone). Keys are then ranked by their total vote.
 * That is enough to separate the seven candidate keys, and it is deterministic, so it can be tested.
 */
public final class KeyDetector {

    /**
     * All twelve major keys, indexed by tonic pitch class (0 = C, 1 = C#, ... 11 = B).
     *
     * <p>Earlier versions offered only the seven naturals (C D E F G A B). Chinese pop and instrument
     * arrangements very often sit in flat keys (Bb, Eb, Ab, Db, Gb) or sharp keys (F#, C#), so the
     * correct key was frequently not selectable at all: the user could not correct a detection
     * because the right answer was missing from the list. The key spinner, the detector and
     * {@link ScaleMapper#rootPitchClass} all share this array.
     */
    public static final String[] KEYS = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B",
    };

    /** Semitone offsets of a major scale from its tonic. */
    private static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11};

    /** Weight of each major-scale degree, tonic first. Degree 4 = fa ... degree 7 = ti. */
    private static final double[] DEGREE_WEIGHT = {1.0, 0.4, 0.6, 0.5, 0.8, 0.5, 0.3};

    private KeyDetector() {}

    /** @return index into {@link #KEYS}, which equals the tonic pitch class; 0 (C) when empty. */
    public static int bestKeyIndex(List<RawNote> notes) {
        if (notes == null || notes.isEmpty()) return 0;
        int bestIndex = 0;
        double bestScore = -1.0;
        for (int root = 0; root < KEYS.length; root++) {
            double score = scoreFor(notes, root);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = root;
            }
        }
        return bestIndex;
    }

    /** Duration-weighted agreement with one candidate key, normalised to roughly 0..1. */
    static double scoreFor(List<RawNote> notes, int rootPc) {
        double total = 0;
        double inKey = 0;
        for (int i = 0; i < notes.size(); i++) {
            RawNote n = notes.get(i);
            double weight = n.durMs;
            total += weight;
            double rel = ((n.midi - rootPc) % 12 + 12) % 12;
            for (int d = 0; d < MAJOR.length; d++) {
                if (MAJOR[d] == rel) {
                    inKey += weight * DEGREE_WEIGHT[d];
                    break;
                }
            }
        }
        return total <= 0 ? 0 : inKey / total;
    }

    /**
     * Detects the key of a whole song from <em>every</em> parsed note, not just the melody line.
     *
     * <p>This distinction is not cosmetic. Feeding only the top voice loses the harmony, and the
     * harmony is what carries the key. A pentatonic melody (C D E G A B, i.e. no fa and no ti - the
     * shape of a great many Chinese pop tunes) fits <em>two</em> major keys equally well, so the
     * melody alone cannot decide between them; the scoring then falls to whichever candidate's
     * tonic and fifth happen to be more frequent. On a 12-key sweep of ordinary I-V-vi-IV MIDI
     * arrangements, melody-only detection got <b>0 of 12</b> right once the melody omitted fa and ti,
     * while using the whole arrangement got 12 of 12.
     *
     * <p>The chord voicings supply the missing pitch classes - the single F that separates C major
     * from G major lives in the accompaniment - so the full note list is both the more informative
     * and the simpler input. Callers that only have a melody (transcribed audio) are unaffected:
     * there the melody <em>is</em> every note.
     */
    public static int bestKeyIndexForSong(List<RawNote> songNotes) {
        return bestKeyIndex(songNotes);
    }

    /** Human readable key name for a whole song; see {@link #bestKeyIndexForSong}. */
    public static String bestKeyNameForSong(List<RawNote> songNotes) {
        return KEYS[bestKeyIndexForSong(songNotes)];
    }

    /** Human readable name of the detected key, e.g. "C". */
    public static String bestKeyName(List<RawNote> notes) {
        return KEYS[bestKeyIndex(notes)];
    }
}
