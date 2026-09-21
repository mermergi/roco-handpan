package com.handpan.autoplay;

import java.util.List;

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
 * <p>The nine pads are <em>not</em> one ascending scale. Their real pitches are
 * A2 E3 F3 G3 A3 B3 C4 D4 E4, so with do on 高音1 = C4 the three high pads sit at octave 0, the five
 * middle pads an octave <em>below</em> that, and 低音6 two octaves below. Two consequences drive this
 * class:
 * <ul>
 *   <li>The octave where do lives has no degrees 1 or 2 except on the high pads, and no fa/sol/la/ti
 *       at all - those live an octave down. A one-octave do-to-ti tune therefore cannot avoid being
 *       folded, whatever transposition is chosen.</li>
 *   <li>Picking a pad needs the note's <em>octave</em>, not just its scale degree - a pitch-class-only
 *       mapping would put every "do" on the same pad and destroy the contour.</li>
 * </ul>
 */
public final class PadMapper {

    /** Number of pads to calibrate. */
    public static final int SLOTS = 9;

    /** Slot order follows the on-screen layout, top row first, so calibration reads naturally. */
    private static final int[] DEGREE = {1, 2, 3, 3, 4, 5, 6, 7, 6};

    /**
     * Octave offset per slot, measured from the pad that carries do (高音1).
     *
     * <p>An earlier version had this one octave out - 高音1 at +1 and the middle row at 0 - which is
     * the same nine pads shifted up an octave. That is not a harmless relabelling: the mapper picks a
     * pad by comparing this offset against the note's octave, so the whole arrangement came out an
     * octave above where the instrument actually plays, and the octave-folding that follows landed
     * almost every note on the three high pads.
     */
    private static final int[] OCTAVE = {0, 0, 0, -1, -1, -1, -1, -1, -2};

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

    /**
     * Scale steps searched above and below the tonic when snapping a pitch.
     *
     * <p>Wide on purpose: the placement search below deliberately puts the tonic far from some notes,
     * and a narrow window would then snap a note to the wrong degree instead of folding it.
     */
    private static final int MIN_STEP = -84;
    private static final int MAX_STEP = 84;

    /**
     * Range of do the instrument can actually be tuned to: 低音6 reaches 15 semitones below do and
     * 高音3 four above, so both ends must stay inside MIDI 0..127.
     */
    private static final int TONIC_MIN = 15;
    private static final int TONIC_MAX = 123;

    /** Semitone distance of each pad from do, i.e. {@code midiForSlot(slot, any, x) - x}. */
    private static final int[] OFFSET = new int[SLOTS];

    static {
        for (int slot = 0; slot < SLOTS; slot++) {
            OFFSET[slot] = scaleMidi(0, (DEGREE[slot] - 1) + 7 * OCTAVE[slot]);
        }
    }

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

    /** The diatonic step nearest a pitch; ties go to the lower step, as before. */
    static int nearestStep(int midi, int tonicMidi) {
        // A major scale repeats every 12 semitones / 7 steps, so the nearest step is within one
        // octave of the step sitting in the note's own octave. Fifteen candidates is then exact.
        int rough = Math.floorDiv(midi - tonicMidi, 12) * 7;
        int bestStep = rough;
        int bestDist = Integer.MAX_VALUE;
        for (int n = rough - 7; n <= rough + 7; n++) {
            int dist = Math.abs(scaleMidi(tonicMidi, n) - midi);
            if (dist < bestDist) {
                bestDist = dist;
                bestStep = n;
            }
        }
        return bestStep;
    }

    /** Brute-force search over the full window, used by tests to check {@link #nearestStep}. */
    static int nearestStepByScan(int midi, int tonicMidi) {
        int bestStep = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int n = MIN_STEP; n <= MAX_STEP; n++) {
            int dist = Math.abs(scaleMidi(tonicMidi, n) - midi);
            if (dist < bestDist) {
                bestDist = dist;
                bestStep = n;
            }
        }
        return bestStep;
    }

    /** The pad that carries one degree at one octave, or -1 when no pad does. */
    private static int slotForDegree(int degree, int octave) {
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

    /**
     * Reference tonic used to break ties: the degree-1 pitch at or immediately below the melody's
     * lowest note.
     *
     * <p>On its own this is a poor placement for anything wider than an octave - see
     * {@link #tonicFor(List, int)} - but it is the placement a plain one-octave tune has always used,
     * so it stays as the tie-break that keeps those tunes unchanged.
     */
    public static int anchorTonicFor(int lowestMidi, int rootPc) {
        int above = ((lowestMidi - rootPc) % 12 + 12) % 12;
        return lowestMidi - above;
    }

    /**
     * Chooses the octave the instrument plays a melody in, by moving as few notes as possible.
     *
     * <p>Anchoring do at the melody's lowest note seems natural and is wrong for real material. The
     * instrument covers about an octave and a half; a MIDI written for piano commonly spans three, so
     * under that anchor nearly the whole tune sits above the instrument and gets folded back down -
     * and because the folding is uniform, it collapses onto the three high pads.
     *
     * <p>Measured on a real three-octave handpan score (715 notes, B major, C3-B5):
     * <pre>
     *   anchor on lowest note   average 21.45 semitones off   8.5% of notes at pitch   低音6 0%
     *   best octave             average  3.30 semitones off  73.4% of notes at pitch   低音6 1%
     * </pre>
     * The second row places do on B4, where the nine pads spell the tune's own scale exactly
     * (G#3 D#4 E4 F#4 G#4 A#4 B4 C#5 D#5) instead of an arbitrary octave of it.
     *
     * <p>Ties keep {@link #anchorTonicFor}, so tunes narrow enough not to care are placed where they
     * have always been.
     *
     * @param melody the notes defining the register - the melody line, not the whole arrangement
     */
    public static int tonicFor(List<RawNote> melody, int rootPc) {
        if (melody == null || melody.isEmpty()) return 60;

        int lowest = 127;
        for (int i = 0; i < melody.size(); i++) lowest = Math.min(lowest, melody.get(i).midi);
        int anchor = anchorTonicFor(lowest, rootPc);

        int best = anchor;
        long bestCost = Long.MAX_VALUE;
        for (int tonic = TONIC_MIN; tonic <= TONIC_MAX; tonic++) {
            if (((tonic - rootPc) % 12 + 12) % 12 != 0) continue;
            long cost = displacement(melody, tonic);
            if (cost < bestCost
                    || (cost == bestCost && Math.abs(tonic - anchor) < Math.abs(best - anchor))) {
                bestCost = cost;
                best = tonic;
            }
        }
        return best;
    }

    /**
     * Total distance, in semitones, between the melody's pitches and the pitches the pads will
     * actually sound for them. Lower is better; zero means every note is played at its written pitch.
     */
    static long displacement(List<RawNote> notes, int tonicMidi) {
        long total = 0;
        for (int i = 0; i < notes.size(); i++) {
            int midi = notes.get(i).midi;
            if (midi < 0 || midi > 127) continue;
            int step = nearestStep(midi, tonicMidi);
            int octave = Math.floorDiv(step, 7);
            int degree = step - octave * 7 + 1;
            int slot = slotForDegree(degree, octave);
            if (slot < 0) continue;
            total += Math.abs(tonicMidi + OFFSET[slot] - midi);
        }
        return total;
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

        int step = nearestStep(midi, tonicMidi);
        int octave = Math.floorDiv(step, 7);
        int degree = step - octave * 7 + 1;
        if (!octaveAware) octave = 0;
        return slotForDegree(degree, octave);
    }
}
