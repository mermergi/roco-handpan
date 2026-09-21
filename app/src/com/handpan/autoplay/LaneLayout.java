package com.handpan.autoplay;

import java.util.ArrayList;
import java.util.List;

/**
 * Places the note-lane tiles so they never overlap.
 *
 * <p>Two rules, because a strip this short needs both:
 *
 * <ul>
 *   <li><b>never overlap</b> - two notes 250ms apart would map only ~14dp apart while a tile is 30dp
 *       wide, so each tile is pushed clear of the one before it;</li>
 *   <li><b>never sprawl</b> - placing purely by time leaves a big empty gap as soon as the notes are
 *       more than a moment apart, which wastes a lane that is not long to begin with. The spacing is
 *       therefore capped, so tiles read as one compact queue.</li>
 * </ul>
 *
 * <p>The consequence is that horizontal position no longer strictly encodes time; the rings on the
 * pads are what carry the timing. Anything with no room left is simply not drawn - the nearest note
 * is the one that matters.
 *
 * <p>Pure arithmetic, so the non-overlap guarantee is asserted in the tests rather than eyeballed.
 */
public final class LaneLayout {

    /** One drawn tile. */
    public static final class Placement {
        /** Index into the input array. */
        public final int index;
        public final float x;

        Placement(int index, float x) {
            this.index = index;
            this.x = x;
        }
    }

    private LaneLayout() {}

    /**
     * @param times   press times, ascending
     * @param leadMs  how long the lane looks ahead
     * @param hitX    where "now" sits
     * @param usable  horizontal travel available to a tile before it needs pushing
     * @param itemW    tile width
     * @param gap      minimum space between tiles
     * @param maxGap   largest space allowed between tiles, so they stay a tight queue
     * @param limit    right edge of the lane; tiles past it are dropped
     * @return placements in input order, never overlapping, possibly shorter than {@code times}
     */
    public static List<Placement> place(long[] times, long nowMs, long leadMs, float hitX,
                                        float usable, float itemW, float gap, float maxGap,
                                        float limit) {
        List<Placement> out = new ArrayList<Placement>();
        if (times == null || itemW <= 0f) return out;
        float travel = usable < 0f ? 0f : usable;
        float widest = Math.max(gap, maxGap);
        float lastRight = hitX - gap;

        for (int i = 0; i < times.length; i++) {
            float ahead = leadMs <= 0 ? 0f : (times[i] - nowMs) / (float) leadMs;
            if (ahead < 0f) ahead = 0f;
            if (ahead > 1f) ahead = 1f;

            float x = hitX + ahead * travel;
            float floor = lastRight + gap;
            float ceiling = lastRight + widest;
            if (x < floor) x = floor;
            if (x > ceiling) x = ceiling; // keep the queue tight instead of sprawling
            if (x + itemW > limit) break; // no room for this one or anything after it

            out.add(new Placement(i, x));
            lastRight = x + itemW;
        }
        return out;
    }
}
