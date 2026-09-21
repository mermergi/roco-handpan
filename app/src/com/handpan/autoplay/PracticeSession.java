package com.handpan.autoplay;

/**
 * Scores a practice run: the player taps pads on the beat and each tap is judged against the nearest
 * scheduled press for that pad.
 *
 * <p>Pure logic on purpose. Timing and judgement are exactly where a rhythm feature silently goes
 * wrong - an off-by-one cursor, a window compared the wrong way round, a missed note counted twice -
 * and none of that can be checked by ear.
 *
 * <p>Windows are generous compared with a commercial rhythm game: the instrument is nine pads under a
 * thumb, not a keyboard, and half the exercise is learning where the pads are.
 */
public final class PracticeSession {

    public static final int PERFECT = 0;
    public static final int GOOD = 1;
    public static final int OK = 2;
    public static final int MISS = 3;

    /** A tap that matched nothing. Costs nothing, but is not a hit either. */
    public static final int STRAY = -1;

    public static final long PERFECT_MS = 90L;
    public static final long GOOD_MS = 200L;
    public static final long OK_MS = 350L;

    private final long[] times;
    private final int[] slots;
    private final boolean[] judged;

    private int perfect;
    private int good;
    private int ok;
    private int miss;

    /**
     * @param times press times in milliseconds, ascending
     * @param slots the pad for each press, same length as {@code times}
     */
    public PracticeSession(long[] times, int[] slots) {
        if (times == null || slots == null || times.length != slots.length) {
            this.times = new long[0];
            this.slots = new int[0];
        } else {
            this.times = times.clone();
            this.slots = slots.clone();
        }
        this.judged = new boolean[this.times.length];
        for (int i = 0; i < this.judged.length; i++) {
            if (this.times[i] < 0 || this.slots[i] < 0 || this.slots[i] >= PadMapper.SLOTS) {
                this.judged[i] = true; // ignore malformed entries rather than scoring them
            }
        }
    }

    public int total() {
        return times.length;
    }

    public int perfect() {
        return perfect;
    }

    public int good() {
        return good;
    }

    public int ok() {
        return ok;
    }

    public int miss() {
        return miss;
    }

    public int judgedCount() {
        return perfect + good + ok + miss;
    }

    public boolean isFinished() {
        return judgedCount() >= total();
    }

    /**
     * Judges a tap.
     *
     * @return {@link #PERFECT}, {@link #GOOD}, {@link #OK}, or {@link #STRAY} when nothing was close
     */
    public int tap(int slot, long nowMs) {
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < times.length; i++) {
            if (judged[i] || slots[i] != slot) continue;
            long distance = Math.abs(times[i] - nowMs);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best < 0 || bestDistance > OK_MS) return STRAY;

        judged[best] = true;
        if (bestDistance <= PERFECT_MS) {
            perfect++;
            return PERFECT;
        }
        if (bestDistance <= GOOD_MS) {
            good++;
            return GOOD;
        }
        ok++;
        return OK;
    }

    /**
     * Marks every pending press whose window has closed as a miss.
     *
     * @return how many were newly marked
     */
    public int consumeMisses(long nowMs) {
        int newlyMissed = 0;
        for (int i = 0; i < times.length; i++) {
            if (judged[i]) continue;
            if (nowMs - times[i] > OK_MS) {
                judged[i] = true;
                miss++;
                newlyMissed++;
            }
        }
        return newlyMissed;
    }

    /** Index of the next press still to be judged, or -1 when none remain. */
    public int nextPendingIndex() {
        for (int i = 0; i < times.length; i++) {
            if (!judged[i]) return i;
        }
        return -1;
    }

    public long nextPendingTime() {
        int i = nextPendingIndex();
        return i < 0 ? -1 : times[i];
    }

    public int nextPendingSlot() {
        int i = nextPendingIndex();
        return i < 0 ? -1 : slots[i];
    }

    public int slotAt(int index) {
        return index < 0 || index >= slots.length ? -1 : slots[index];
    }

    public long timeAt(int index) {
        return index < 0 || index >= times.length ? -1 : times[index];
    }

    /** Pads due within the given lead time, so the board can draw their timing rings. */
    public int[] dueSlots(long nowMs, long leadMs) {
        int count = 0;
        for (int i = 0; i < times.length; i++) {
            if (judged[i]) continue;
            long delta = times[i] - nowMs;
            if (delta <= leadMs) count++;
        }
        int[] out = new int[count];
        int n = 0;
        for (int i = 0; i < times.length && n < count; i++) {
            if (judged[i]) continue;
            if (times[i] - nowMs <= leadMs) out[n++] = slots[i];
        }
        return out;
    }

    /** 0..100, weighted: perfect 100, good 70, ok 40, miss 0. */
    public int accuracyPercent() {
        if (total() == 0) return 0;
        int score = perfect * 100 + good * 70 + ok * 40;
        return Math.round(100f * score / (total() * 100f));
    }
}
