package com.handpan.autoplay;

/** A single pitched note parsed from any input format. Pure data, no Android dependency. */
public final class RawNote {
    /** MIDI note number, 0-127, 69 = A4 = 440Hz. */
    public final int midi;
    /** Absolute start time in ms from the beginning of the song. */
    public final long startMs;
    /** Duration in ms, always at least 1. */
    public final long durMs;

    public RawNote(int midi, long startMs, long durMs) {
        this.midi = midi;
        this.startMs = startMs < 0 ? 0 : startMs;
        this.durMs = durMs < 1 ? 1 : durMs;
    }

    @Override
    public String toString() {
        return "Note[" + midi + " @" + startMs + "ms +" + durMs + "ms]";
    }
}
