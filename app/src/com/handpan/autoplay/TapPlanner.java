package com.handpan.autoplay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Turns notes into the pad hits to perform, grouping notes that sound together into one chord.
 *
 * <p>The instrument is polyphonic - the game accepts several pads pressed at once - so collapsing
 * everything to a single melodic line throws away most of the arrangement and leaves pads unused.
 * Notes whose onsets fall inside {@link #CHORD_WINDOW_MS} become one {@link Hit} carrying several
 * pads, which the service then presses together in a single multi-stroke gesture.
 *
 * <p>Extracted from the Activity so it can be tested. That matters: the previous version kept this
 * loop inside {@code MainActivity} where no unit test could reach it, and it shipped with an
 * integer-overflow bug ({@code long lastAt = Long.MIN_VALUE} plus {@code at - lastAt < MIN_GAP_MS})
 * that made the tap list empty for every input.
 */
public final class TapPlanner {

    /** Hits closer together than this are dropped; gesture dispatch cannot resolve them. */
    public static final long MIN_GAP_MS = 60L;

    /** Notes starting within this window are treated as one simultaneous chord. */
    public static final long CHORD_WINDOW_MS = 50L;

    /** Delay before the first hit, giving the user time to switch to the game. */
    public static final long LEAD_IN_MS = 600L;

    /** One scheduled hit: one or more pads pressed at the same instant. */
    public static final class Hit {
        /** Pad slots, in priority order (melody first). Never empty. */
        public final int[] slots;
        public final long atMs;

        public Hit(int[] slots, long atMs) {
            this.slots = slots;
            this.atMs = atMs;
        }

        public int voices() {
            return slots.length;
        }
    }

    private TapPlanner() {}

    /**
     * @param maxChord    maximum pads pressed together, capped again by the gesture stroke limit.
     * @param speed       playback speed multiplier; 1.0 keeps the original tempo.
     * @param octaveAware when false every degree uses its base-octave pad.
     * @return hits in ascending time order, possibly empty when there is nothing playable.
     */
    public static List<Hit> plan(List<RawNote> notes, int rootPc, int tonicMidi,
                                 boolean octaveAware, float speed, int maxChord) {
        List<Hit> out = new ArrayList<Hit>();
        if (notes == null || notes.isEmpty()) return out;
        float tempo = speed <= 0f ? 1.0f : speed;
        int cap = maxChord < 1 ? 1 : maxChord;

        List<RawNote> sorted = new ArrayList<RawNote>(notes);
        Collections.sort(sorted, new Comparator<RawNote>() {
            @Override
            public int compare(RawNote a, RawNote b) {
                if (a.startMs != b.startMs) return a.startMs < b.startMs ? -1 : 1;
                if (a.midi != b.midi) return a.midi > b.midi ? -1 : 1;
                return 0;
            }
        });

        // Guards against the "no previous hit" sentinel overflowing the gap comparison below.
        boolean hasLast = false;
        long lastAt = 0L;

        int index = 0;
        while (index < sorted.size()) {
            long onset = sorted.get(index).startMs;

            List<RawNote> group = new ArrayList<RawNote>();
            while (index < sorted.size() && sorted.get(index).startMs - onset <= CHORD_WINDOW_MS) {
                group.add(sorted.get(index));
                index++;
            }
            if (group.isEmpty()) continue;

            int[] slots = pickSlots(group, rootPc, tonicMidi, octaveAware, cap);
            if (slots.length == 0) continue;

            long at = LEAD_IN_MS + Math.round(onset / tempo);
            if (hasLast && at - lastAt < MIN_GAP_MS) continue;
            out.add(new Hit(slots, at));
            hasLast = true;
            lastAt = at;
        }
        return out;
    }

    /**
     * Chooses which pads one chord presses.
     *
     * <p>Order of preference: the highest note (the melody carries the tune), then the lowest (bass
     * supplies the root), then the remaining voices from the top down. Duplicates are folded, since
     * two notes an octave apart still share a single pad on this instrument.
     */
    private static int[] pickSlots(List<RawNote> group, int rootPc, int tonicMidi,
                                   boolean octaveAware, int cap) {
        List<RawNote> byPitch = new ArrayList<RawNote>(group);
        Collections.sort(byPitch, new Comparator<RawNote>() {
            @Override
            public int compare(RawNote a, RawNote b) {
                if (a.midi != b.midi) return a.midi > b.midi ? -1 : 1;
                return 0;
            }
        });

        List<Integer> chosen = new ArrayList<Integer>();
        addSlot(chosen, byPitch.get(0), rootPc, tonicMidi, octaveAware, cap);
        if (byPitch.size() > 1) {
            addSlot(chosen, byPitch.get(byPitch.size() - 1), rootPc, tonicMidi, octaveAware, cap);
        }
        for (int i = 0; i < byPitch.size() && chosen.size() < cap; i++) {
            addSlot(chosen, byPitch.get(i), rootPc, tonicMidi, octaveAware, cap);
        }

        int[] slots = new int[chosen.size()];
        for (int i = 0; i < slots.length; i++) slots[i] = chosen.get(i);
        return slots;
    }

    private static void addSlot(List<Integer> chosen, RawNote note, int rootPc, int tonicMidi,
                                boolean octaveAware, int cap) {
        if (chosen.size() >= cap) return;
        int slot = PadMapper.slotFor(note.midi, rootPc, tonicMidi, octaveAware);
        if (slot < 0) return;
        if (!chosen.contains(slot)) chosen.add(slot);
    }
}
