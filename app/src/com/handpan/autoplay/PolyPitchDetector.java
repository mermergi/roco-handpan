package com.handpan.autoplay;

import java.util.ArrayList;
import java.util.List;

/**
 * Polyphonic transcription: finds several notes sounding at once, from a spectrogram.
 *
 * <p>{@link PitchDetector} works in the time domain and can only report one pitch per frame - the
 * period of two notes sounding together is not the period of either, so it either drops the frame or
 * reports a note that is not there. That is fine for a hummed melody and useless for a piano, where
 * almost every frame has several strings ringing.
 *
 * <p>This works in the frequency domain instead:
 * <ol>
 *   <li>STFT, 4096-point window, 512-sample hop. The window is long on purpose: it resolves 5.4 Hz,
 *       under a semitone even down at C3. At 2048 points a bin is 10.8 Hz, the bass smears into a
 *       cluster of neighbouring notes, and those ghosts crowd the melody out of the frame.</li>
 *   <li>Subtract a per-bin running background, which removes the notes still ringing from earlier
 *       and leaves the onsets.</li>
 *   <li>Divide by a smoothed version of the same frame, which flattens the timbre so a note's
 *       harmonics weigh comparably to its fundamental.</li>
 *   <li>Score every candidate note by summing its harmonic series, take the best, then
 *       <em>subtract</em> that harmonic series from the frame and score again. Without this step a
 *       low note's own harmonics are re-detected as separate notes an octave and a twelfth up, and
 *       a piano piece comes back with several times too many notes.</li>
 *   <li>Link the same pitch across consecutive frames into notes.</li>
 * </ol>
 *
 * <p>Pure Java, no Android types: the whole thing is exercised by the JVM test suite, and the same
 * code runs on the phone.
 */
public final class PolyPitchDetector {

    private static final int WINDOW = 4096;
    private static final int HOP = 512;

    /**
     * Low end of the search: A2, the lowest pad the instrument has.
     *
     * <p>Not only a convenience. Even with the 4096-point window, one bin is 5.4 Hz, which is still
     * over a semitone down at C2 - anything lower comes back as a smear of neighbouring notes, and
     * the instrument has no pad for it anyway.
     */
    private static final int MIN_MIDI = 45;

    /** High end. Above C7 there is mostly brilliance, not notes. */
    private static final int MAX_MIDI = 96;

    /** Harmonics summed per candidate note. */
    private static final int HARMONICS = 10;

    /** Weight of harmonic {@code h}: 0.85^(h-1), so the fundamental dominates but not absolutely. */
    private static final float HARMONIC_DECAY = 0.85f;

    /** Most notes reported for one frame; the app can only press so many pads anyway. */
    private static final int MAX_SIMULTANEOUS = 5;

    /**
     * A candidate's whitened harmonic sum must reach this to be reported at all.
     *
     * <p>The whitened spectrum of a real note has narrow spikes against a smoothed envelope, so it
     * scores several times unity; broadband noise whitens to roughly a flat 1.0. Without an absolute
     * gate like this, a quiet hiss transcribes into a fistful of notes.
     */
    private static final float ABSOLUTE_THRESHOLD = 2.4f;

    /** Each further note of a chord must still reach this fraction of the frame's best score. */
    private static final float SUBSEQUENT_THRESHOLD = 0.12f;

    /**
     * How much of its loudest harmonic a candidate's fundamental must carry.
     *
     * <p>Stops subharmonic ghosts. C4+E4+G4 has no energy at C3, but C3's 2nd and 3rd harmonics are
     * C4 and G4, so a plain harmonic sum ranks C3 above either real note and the whole chord comes
     * back as a single low note. A real fundamental is never this weak relative to its overtones.
     */
    private static final float FUNDAMENTAL_FLOOR = 0.35f;

    /** Frames quieter than this fraction of the running level are treated as silence. */
    private static final float SILENCE_FRACTION = 0.02f;

    /** Shorter than this and it is a transient, not a note. */
    private static final long MIN_NOTE_MS = 110L;

    private PolyPitchDetector() {}

    /**
     * Transcribes polyphonic audio.
     *
     * @param pcm        mono samples in [-1, 1]
     * @param sampleRate samples per second
     * @return notes in time order; simultaneous notes share a start time, which is what
     *         {@link TapPlanner} turns into a chord
     */
    public static List<RawNote> detect(float[] pcm, int sampleRate) {
        List<RawNote> out = new ArrayList<RawNote>();
        if (pcm == null || pcm.length < WINDOW || sampleRate <= 0) return out;

        int frameCount = (pcm.length - WINDOW) / HOP + 1;
        if (frameCount < 1) return out;

        float[] window = hann(WINDOW);
        float[] re = new float[WINDOW];
        float[] im = new float[WINDOW];
        int bins = WINDOW / 2 + 1;
        float[] mag = new float[bins];
        float[] background = new float[bins];
        float[] work = new float[bins];

        int candidates = MAX_MIDI - MIN_MIDI + 1;
        int[][] harmonicBin = new int[candidates][HARMONICS];
        boolean[][] harmonicValid = new boolean[candidates][HARMONICS];
        float[] harmonicWeight = new float[HARMONICS];
        float weightSum = 0f;
        for (int h = 0; h < HARMONICS; h++) {
            harmonicWeight[h] = (float) Math.pow(HARMONIC_DECAY, h);
            weightSum += harmonicWeight[h];
        }
        for (int i = 0; i < candidates; i++) {
            double hz = 440.0 * Math.pow(2.0, (MIN_MIDI + i - 69) / 12.0);
            for (int h = 1; h <= HARMONICS; h++) {
                int bin = (int) Math.round(hz * h * WINDOW / sampleRate);
                harmonicBin[i][h - 1] = bin;
                harmonicValid[i][h - 1] = bin >= 1 && bin < bins;
            }
        }

        int[] frameNote = new int[MAX_SIMULTANEOUS];
        int[] frameCounts = new int[frameCount];
        int[][] framePicks = new int[frameCount][];

        float level = 0f;
        float best = 0f;
        float[] score = new float[candidates];

        for (int frame = 0; frame < frameCount; frame++) {
            int offset = frame * HOP;
            for (int i = 0; i < WINDOW; i++) {
                re[i] = pcm[offset + i] * window[i];
                im[i] = 0f;
            }
            fft(re, im);
            float energy = 0f;
            for (int b = 0; b < bins; b++) {
                float value = (float) Math.hypot(re[b], im[b]);
                mag[b] = value;
                energy += value;
            }
            // Running level, so silence can be told apart from a quiet passage.
            best = Math.max(best, energy);
            level = level == 0f ? energy : level * 0.995f + energy * 0.005f;
            if (energy < best * SILENCE_FRACTION) {
                frameCounts[frame] = 0;
                framePicks[frame] = new int[0];
                continue;
            }

            // Background subtraction: what was already ringing stays in `background`.
            for (int b = 0; b < bins; b++) {
                background[b] = background[b] * 0.90f + mag[b] * 0.10f;
                work[b] = Math.max(0f, mag[b] - background[b]);
            }
            whiten(work, bins);

            int found = 0;
            float frameBest = 0f;
            for (int pick = 0; pick < MAX_SIMULTANEOUS; pick++) {
                float max = 0f;
                for (int i = 0; i < candidates; i++) {
                    float sum = 0f;
                    float used = 0f;
                    float loudest = 0f;
                    int[] bin = harmonicBin[i];
                    boolean[] ok = harmonicValid[i];
                    for (int h = 0; h < HARMONICS; h++) {
                        if (!ok[h]) continue;
                        float value = work[bin[h]];
                        sum += harmonicWeight[h] * value;
                        used += harmonicWeight[h];
                        if (value > loudest) loudest = value;
                    }
                    float value = used > 0f ? sum / used * weightSum : 0f;
                    // Discount candidates whose fundamental is not really there.
                    if (loudest > 0f && ok[0]) {
                        float fundamental = work[bin[0]];
                        if (fundamental < FUNDAMENTAL_FLOOR * loudest) {
                            value *= fundamental / (FUNDAMENTAL_FLOOR * loudest);
                        }
                    }
                    score[i] = value;
                    if (value > max) max = value;
                }
                if (pick == 0) {
                    frameBest = max;
                    if (frameBest < ABSOLUTE_THRESHOLD) break;
                } else if (max < frameBest * SUBSEQUENT_THRESHOLD) {
                    break;
                }

                int chosen = -1;
                for (int i = 0; i < candidates; i++) {
                    if (score[i] != max) continue;
                    boolean already = false;
                    for (int k = 0; k < found; k++) if (frameNote[k] == MIN_MIDI + i) already = true;
                    if (!already) {
                        chosen = i;
                        break;
                    }
                }
                if (chosen < 0) break;

                frameNote[found] = MIN_MIDI + chosen;
                found++;
                int pickedMidi = MIN_MIDI + chosen;

                // Mute the neighbouring semitones. One window bin is 10.8 Hz, which is barely half a
                // semitone at the top of the range, so every note leaks into its neighbours and a
                // single C4 otherwise transcribes as C4 plus B3.
                for (int i = 0; i < candidates; i++) {
                    if (Math.abs(MIN_MIDI + i - pickedMidi) <= 1) score[i] = 0f;
                }

                // Remove this note's harmonic series so its overtones are not found again.
                float fundamental = work[harmonicBin[chosen][0]];
                for (int h = 0; h < HARMONICS; h++) {
                    if (!harmonicValid[chosen][h]) continue;
                    int bin = harmonicBin[chosen][h];
                    work[bin] = Math.max(0f, work[bin] - harmonicWeight[h] * fundamental);
                }
            }

            int[] picks = new int[found];
            System.arraycopy(frameNote, 0, picks, 0, found);
            framePicks[frame] = picks;
            frameCounts[frame] = found;
        }

        return track(framePicks, frameCounts, sampleRate);
    }

    /** Flattens the spectrum so a note's harmonics count comparably to its fundamental. */
    private static void whiten(float[] work, int bins) {
        final int half = 8;
        float running = 0f;
        for (int b = 0; b < Math.min(half, bins); b++) running += work[b];
        int count = Math.min(half, bins);
        for (int b = 0; b < bins; b++) {
            int add = b + half;
            int drop = b - half - 1;
            if (add < bins) {
                running += work[add];
                count++;
            }
            if (drop >= 0) {
                running -= work[drop];
                count--;
            }
            float envelope = count > 0 ? running / count : 0f;
            work[b] = envelope > 1e-6f ? Math.min(work[b] / envelope, 8f) : 0f;
        }
    }

    /** Links the same pitch across consecutive frames into notes. */
    private static List<RawNote> track(int[][] framePicks, int[] frameCounts, int sampleRate) {
        List<RawNote> out = new ArrayList<RawNote>();
        int frames = framePicks.length;
        int[] start = new int[128];
        int[] active = new int[128];
        java.util.Arrays.fill(start, -1);

        for (int frame = 0; frame < frames; frame++) {
            int[] picks = framePicks[frame];
            for (int i = 0; i < active.length; i++) {
                if (active[i] == 0) continue;
                boolean stillOn = false;
                for (int p = 0; p < picks.length; p++) if (picks[p] == i) stillOn = true;
                if (!stillOn) {
                    emit(out, i, start[i], frame, sampleRate);
                    active[i] = 0;
                    start[i] = -1;
                }
            }
            for (int p = 0; p < picks.length; p++) {
                int midi = picks[p];
                if (midi < 0 || midi > 127) continue;
                if (active[midi] == 0) {
                    active[midi] = 1;
                    start[midi] = frame;
                }
            }
        }
        for (int i = 0; i < active.length; i++) {
            if (active[i] != 0) emit(out, i, start[i], frames, sampleRate);
        }

        java.util.Collections.sort(out, new java.util.Comparator<RawNote>() {
            @Override
            public int compare(RawNote a, RawNote b) {
                if (a.startMs != b.startMs) return a.startMs < b.startMs ? -1 : 1;
                return a.midi == b.midi ? 0 : (a.midi < b.midi ? -1 : 1);
            }
        });
        return out;
    }

    private static void emit(List<RawNote> out, int midi, int startFrame, int endFrame, int sampleRate) {
        long startMs = (long) startFrame * HOP * 1000L / sampleRate;
        long endMs = (long) endFrame * HOP * 1000L / sampleRate;
        long durMs = endMs - startMs;
        if (durMs < MIN_NOTE_MS) return;
        out.add(new RawNote(midi, startMs, durMs));
    }

    private static float[] hann(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) w[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
        return w;
    }

    /** In-place iterative radix-2 FFT. */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            float wr = (float) Math.cos(angle);
            float wi = (float) Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    float xr = re[b] * cr - im[b] * ci;
                    float xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }
}
