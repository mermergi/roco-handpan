package com.handpan.autoplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estimates a song's tempo from note onsets.
 *
 * <p>Needed because the BPM field used to be purely manual, which made no sense for inputs that
 * already carry a tempo (MIDI tempo events) and left a meaningless number in the box otherwise.
 * Rather than parse tempo out of each format separately, this works on the note list every format
 * already produces.
 *
 * <p>Method: histogram the intervals between consecutive onsets to find the smallest common pulse
 * (the tatum), then test that pulse multiplied by 1..4. A run of eighth notes at 120 BPM has a tatum
 * of 250 ms, which on its own reads as an implausible 240 BPM; taking the multiple that lands in a
 * musically ordinary range recovers 120. When no multiple is ordinary, the candidate closest to 120
 * wins, since unusual tempos are more often a wrong octave than a genuinely extreme tempo.
 */
public final class TempoEstimator {

    private static final long BIN_MS = 10L;

    /** Tatum range: 30..500 BPM. Anything outside is noise or a sustained note. */
    private static final long MIN_TATUM_MS = 120L;
    private static final long MAX_TATUM_MS = 2000L;

    /** Beats per minute considered musically ordinary. */
    private static final int ORDINARY_MIN = 70;
    private static final int ORDINARY_MAX = 160;
    private static final int ORDINARY_CENTER = 120;

    /** Fewer notes than this and the interval histogram is meaningless. */
    private static final int MIN_NOTES = 4;

    private TempoEstimator() {}

    /** @return estimated BPM, or 0 when there is not enough rhythmic information. */
    public static int estimate(List<RawNote> notes) {
        if (notes == null || notes.size() < MIN_NOTES) return 0;

        List<Long> onsets = new ArrayList<Long>();
        for (int i = 0; i < notes.size(); i++) onsets.add(notes.get(i).startMs);
        Collections.sort(onsets);

        Map<Long, Integer> histogram = new HashMap<Long, Integer>();
        for (int i = 1; i < onsets.size(); i++) {
            long delta = onsets.get(i) - onsets.get(i - 1);
            if (delta < MIN_TATUM_MS || delta > MAX_TATUM_MS) continue;
            long bin = Math.round((double) delta / BIN_MS) * BIN_MS;
            Integer count = histogram.get(bin);
            histogram.put(bin, count == null ? 1 : count + 1);
        }
        if (histogram.isEmpty()) return 0;

        // Most common interval, then refine with its neighbours for sub-bin accuracy.
        long bestBin = 0;
        int bestCount = -1;
        for (Map.Entry<Long, Integer> e : histogram.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && e.getKey() < bestBin)) {
                bestCount = e.getValue();
                bestBin = e.getKey();
            }
        }
        long weighted = 0;
        int weight = 0;
        for (Map.Entry<Long, Integer> e : histogram.entrySet()) {
            if (Math.abs(e.getKey() - bestBin) <= BIN_MS * 2) {
                weighted += e.getKey() * e.getValue();
                weight += e.getValue();
            }
        }
        long tatum = weight > 0 ? weighted / weight : bestBin;
        if (tatum <= 0) return 0;

        // The tatum is usually a subdivision; pick the multiple that gives an ordinary tempo.
        int bestBpm = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int multiple = 1; multiple <= 4; multiple++) {
            long period = tatum * multiple;
            if (period <= 0) continue;
            int bpm = (int) Math.round(60000.0 / period);
            if (bpm < 20 || bpm > 400) continue;
            int distance;
            if (bpm >= ORDINARY_MIN && bpm <= ORDINARY_MAX) {
                distance = 0;
            } else {
                distance = Math.abs(bpm - ORDINARY_CENTER);
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                bestBpm = bpm;
            }
        }
        return bestBpm;
    }
}
