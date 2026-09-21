package com.handpan.autoplay;

/**
 * Maps a MIDI pitch to one of the game handpan's numbered pads.
 *
 * <p>The in-game instrument has pads labelled with the numbered-notation digits 1-7 (plus a 0 pad).
 * Digit 1-7 are the seven degrees of a major scale, so a parsed melody is reduced to scale degrees
 * in a chosen key.
 *
 * <p>Every pitch inside the MIDI range is snapped to the <em>nearest</em> degree, including
 * chromatic notes: a major scale's widest gap is a whole tone, so any pitch class is at most one
 * semitone away from some degree. That is deliberate - an arrangement for a diatonic instrument
 * simplifies accidentals to the closest scale tone (F# becomes F in C major) instead of leaving a
 * hole in the rhythm. Only an out-of-range MIDI number yields -1.
 */
public final class ScaleMapper {

    /** Semitone offsets of a major scale from the tonic. */
    private static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11};

    /**
     * Largest distance (in semitones, wrapped) accepted when snapping a note onto a degree.
     * A major scale's widest gap is two semitones, so 1 covers every pitch class: nothing is lost.
     */
    private static final int MAX_SNAP = 1;

    private ScaleMapper() {}

    /** Pitch class of a key name such as "C", "F#", "Bb" or "A#", or -1 when unknown. */
    public static int rootPitchClass(String key) {
        if (key == null) return 0;
        String k = key.trim().toUpperCase();
        if (k.length() == 0) return 0;
        int base;
        switch (k.charAt(0)) {
            case 'C': base = 0; break;
            case 'D': base = 2; break;
            case 'E': base = 4; break;
            case 'F': base = 5; break;
            case 'G': base = 7; break;
            case 'A': base = 9; break;
            case 'B': base = 11; break;
            default: return -1;
        }
        if (k.length() > 1) {
            char accidental = k.charAt(1);
            if (accidental == '#') base = (base + 1) % 12;
            else if (accidental == 'B') base = (base + 11) % 12; // "Bb" spelling
        }
        return base;
    }

    /**
     * Snaps a pitch to its nearest scale degree.
     *
     * @return pad digit 1-7, or -1 when the MIDI number is outside 0..127.
     */
    public static int degreeFor(int midi, int rootPc) {
        if (midi < 0 || midi > 127) return -1;
        int pc = ((midi % 12) + 12) % 12;
        int rel = ((pc - rootPc) % 12 + 12) % 12;
        int best = -1;
        int bestDist = 99;
        for (int i = 0; i < MAJOR.length; i++) {
            int d = Math.abs(rel - MAJOR[i]);
            if (d > 6) d = 12 - d;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        if (best < 0 || bestDist > MAX_SNAP) return -1;
        return best + 1; // digits are 1-based
    }

    /** Inverse of {@link #degreeFor}: MIDI pitch for scale degree 1-7 near the given octave. */
    public static int midiForDegree(int degree, int rootPc, int octaveMidi) {
        if (degree < 1 || degree > 7) return -1;
        return octaveMidi + ((rootPc + MAJOR[degree - 1]) % 12);
    }

    /** Human readable note name for a MIDI pitch, e.g. 69 -> "A4". */
    public static String nameOf(int midi) {
        final String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        if (midi < 0 || midi > 127) return "?";
        return names[midi % 12] + (midi / 12 - 1);
    }
}
