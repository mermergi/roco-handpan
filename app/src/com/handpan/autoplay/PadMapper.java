package com.handpan.autoplay;

/**
 * The instrument's fixed nine-pad layout, and the mapping from a parsed pitch onto a pad.
 *
 * <p>Layout confirmed against the in-game instrument:
 * <pre>
 *   top row    (3 pads) : 1 2 3   with a dot ABOVE  -> upper octave
 *   middle row (5 pads) : 3 4 5 6 7 with no dot     -> base octave
 *   bottom     (1 pad)  : 6 with a dot BELOW        -> lower octave
 * </pre>
 *
 * <p>Two consequences drive this class:
 * <ul>
 *   <li>The base octave has <em>no</em> degrees 1 and 2, so a melody's do/re can only be played on
 *       the upper-octave pads. Degree 3 exists in two octaves, and degree 6 in two as well.</li>
 *   <li>Picking a pad therefore needs the note's <em>octave</em>, not just its scale degree - a
 *       pitch-class-only mapping would put every "do" on the same pad and destroy the contour.</li>
 * </ul>
 */
public final class PadMapper {

    /** Number of pads to calibrate. */
    public static final int SLOTS = 9;

    /** Slot order follows the on-screen layout, top row first, so calibration reads naturally. */
    private static final int[] DEGREE = {1, 2, 3, 3, 4, 5, 6, 7, 6};

    /** Octave offset per slot: -1 lower, 0 base, +1 upper. */
    private static final int[] OCTAVE = {1, 1, 1, 0, 0, 0, 0, 0, -1};

    /** Human readable name shown during calibration. */
    public static final String[] LABEL = {
            "高音 1", "高音 2", "高音 3",
            "中音 3", "中音 4", "中音 5", "中音 6", "中音 7",
            "低音 6",
    };

    /**
     * Compact display form, used in previews and the practice note list.
     *
     * <p>Circled digits mark the pads that sit outside the middle octave (high 1/2/3 and the low 6);
     * the middle octave stays plain. That matches how the game labels them - a dot above or below
     * the numeral - while staying readable in one character.
     */
    public static final String[] SHORT = {
            "①", "②", "③", "3", "4", "5", "6", "7", "⑥",
    };

    /** Semitone offsets of a major scale. */
    private static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11};

    /** Scale steps searched above and below the tonic when snapping a pitch. */
    private static final int MIN_STEP = -7;
    private static final int MAX_STEP = 21;

    private PadMapper() {}

    public static String labelOf(int slot) {
        return slot >= 0 && slot < LABEL.length ? LABEL[slot] : "?";
    }

    public static String shortOf(int slot) {
        return slot >= 0 && slot < SHORT.length ? SHORT[slot] : "?";
    }

    /** Pitch of diatonic step {@code n}: 0 is the tonic, 7 the tonic an octave up, -1 the leading tone below. */
    private static int scaleMidi(int tonicMidi, int n) {
        int octave = Math.floorDiv(n, 7);
        int index = n - octave * 7;
        return tonicMidi + 12 * octave + MAJOR[index];
    }

    /**
     * Reference tonic: the degree-1 pitch at or immediately below the melody's lowest note.
     *
     * <p>Anchoring on the lowest note (rather than the median) keeps a normal one-octave melody
     * inside the instrument's single-octave window instead of splitting it across octaves.
     */
    public static int tonicFor(int lowestMidi, int rootPc) {
        int above = ((lowestMidi - rootPc) % 12 + 12) % 12;
        return lowestMidi - above;
    }

    /**
     * The pitch a pad stands for, in the octave layout relative to {@code tonicMidi}.
     *
     * <p>Inverse of {@link #slotFor}: used when a performance recorded on the pad board has to be
     * stored as an ordinary note list, so playback, saving and the song library all keep working
     * without a second code path.
     */
    public static int midiForSlot(int slot, int rootPc, int tonicMidi) {
        if (slot < 0 || slot >= DEGREE.length) return -1;
        return scaleMidi(tonicMidi, (DEGREE[slot] - 1) + 7 * OCTAVE[slot]);
    }

    /**
     * Chooses a pad for one pitch.
     *
     * @param octaveAware when false, the octave is ignored and every degree uses its base-octave pad
     *                    (degrees 1 and 2 then fall back to the upper pads, which are all that exist).
     * @return pad slot 0..{@link #SLOTS}-1, or -1 when the pitch cannot be placed.
     */
    public static int slotFor(int midi, int rootPc, int tonicMidi, boolean octaveAware) {
        if (midi < 0 || midi > 127) return -1;

        int bestStep = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int n = MIN_STEP; n <= MAX_STEP; n++) {
            int dist = Math.abs(scaleMidi(tonicMidi, n) - midi);
            if (dist < bestDist) {
                bestDist = dist;
                bestStep = n;
            }
        }
        int octave = Math.floorDiv(bestStep, 7);
        int degree = bestStep - octave * 7 + 1;
        if (!octaveAware) octave = 0;

        int bestSlot = -1;
        int bestCost = Integer.MAX_VALUE;
        for (int slot = 0; slot < DEGREE.length; slot++) {
            if (DEGREE[slot] != degree) continue;
            int cost = Math.abs(OCTAVE[slot] - octave);
            if (cost < bestCost) {
                bestCost = cost;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }
}
