package com.handpan.autoplay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal, dependency-free Standard MIDI File (SMF) parser.
 *
 * <p>Pure Java 8, no Android or third-party types. Only java.* / java.util.*
 * are used. Supports SMF format 0, 1 and (best effort, first track only)
 * format 2, both PPQ and SMPTE time divisions, running status, sysex and the
 * relevant meta events. Produces a flat, time-sorted list of {@link RawNote}
 * merged across all tracks.
 */
public final class MidiParser {

    /** Safety valve against pathological / hostile inputs. */
    private static final int MAX_EVENTS = 2000000;

    /** MIDI default tempo: 500000 microseconds per quarter note (120 BPM). */
    private static final long DEFAULT_TEMPO_US = 500000L;

    private static final int NOTE_KEYS = 16 * 128;

    private MidiParser() {
        // no instances
    }

    /** Parsed result: all notes merged and sorted by start time, plus total length. */
    public static final class Result {
        public final List<RawNote> notes;
        public final long lengthMs;

        public Result(List<RawNote> notes, long lengthMs) {
            this.notes = notes;
            this.lengthMs = lengthMs;
        }
    }

    /**
     * Parse a complete Standard MIDI File held in memory.
     *
     * @param data raw SMF bytes
     * @return notes sorted by startMs and the piece length in milliseconds
     * @throws IOException if the stream is not a valid / complete SMF
     */
    public static Result parse(byte[] data) throws IOException {
        if (data == null) {
            throw new IOException("cannot parse MIDI: input byte array is null");
        }

        Cursor c = new Cursor(data, 0, data.length);

        if (c.remaining() < 8) {
            throw new IOException("not a Standard MIDI File: need at least 8 bytes for the "
                    + "MThd chunk, but only " + c.remaining() + " byte(s) are available");
        }
        byte[] magic = c.readMagic();
        if (!isMagic(magic, 'M', 'T', 'h', 'd')) {
            throw new IOException("not a Standard MIDI File: expected \"MThd\" at offset 0 but found "
                    + magicToString(magic));
        }
        long headerLen = c.readU32("MThd chunk length");
        if (headerLen < 6) {
            throw new IOException("invalid MThd chunk length " + headerLen + " (must be at least 6)");
        }
        if (headerLen > c.remaining()) {
            throw new IOException("invalid MThd chunk length " + headerLen + ": only "
                    + c.remaining() + " byte(s) remain in the file");
        }
        int headerEnd = c.pos + (int) headerLen;

        int format = c.readU16("SMF format");
        int ntrks = c.readU16("track count");
        int division = c.readU16("time division");
        c.pos = headerEnd; // tolerate header chunks longer than 6 bytes

        if (format != 0 && format != 1 && format != 2) {
            throw new IOException("unsupported SMF format " + format + " (expected 0, 1 or 2)");
        }
        if (ntrks <= 0) {
            throw new IOException("invalid SMF track count " + ntrks + " (must be at least 1)");
        }

        boolean smpte;
        double msPerTick = 0.0;
        final int ppq;
        if ((division & 0x8000) != 0) {
            // SMPTE: high byte is a negative frame rate, low byte ticks per frame.
            int fpsByte = (division >> 8) & 0xFF;
            int framesPerSecond = 256 - fpsByte; // e.g. 0xE7 -> -25 fps
            int ticksPerFrame = division & 0xFF;
            if (ticksPerFrame == 0) {
                throw new IOException("invalid SMPTE time division 0x" + toHex4(division)
                        + ": ticks per frame is 0");
            }
            smpte = true;
            ppq = 0;
            msPerTick = 1000.0 / ((double) framesPerSecond * (double) ticksPerFrame);
        } else {
            if (division == 0) {
                throw new IOException("invalid time division 0 (neither PPQ nor SMPTE)");
            }
            smpte = false;
            ppq = division;
        }

        List<long[]> rawNotes = new ArrayList<long[]>();
        List<long[]> tempoEvents = new ArrayList<long[]>();
        int[] eventCounter = new int[] { 0 };
        long maxTick = 0L;

        int tracksToParse = (format == 2) ? 1 : ntrks;
        for (int t = 0; t < tracksToParse; t++) {
            byte[] m = c.readMagic();
            if (!isMagic(m, 'M', 'T', 'r', 'k')) {
                throw new IOException("expected \"MTrk\" chunk for track " + t + " at offset "
                        + (c.pos - 4) + " but found " + magicToString(m));
            }
            long trackLen = c.readU32("MTrk chunk length");
            if (trackLen > c.remaining()) {
                throw new IOException("track " + t + " chunk length " + trackLen
                        + " exceeds the " + c.remaining() + " byte(s) remaining: file is truncated");
            }
            int trackStart = c.pos;
            int trackEnd = c.pos + (int) trackLen;
            c.pos = trackEnd; // the track parser gets its own bounded cursor

            long endTick = parseTrack(new Cursor(data, trackStart, trackEnd), t,
                    rawNotes, tempoEvents, eventCounter);
            if (endTick > maxTick) {
                maxTick = endTick;
            }
        }

        // ---- tempo map (ignored for SMPTE divisions) ----------------------
        Collections.sort(tempoEvents, new Comparator<long[]>() {
            public int compare(long[] a, long[] b) {
                if (a[0] < b[0]) {
                    return -1;
                }
                if (a[0] > b[0]) {
                    return 1;
                }
                return 0;
            }
        });

        int m = 0;
        long[] tTick = new long[tempoEvents.size()];
        long[] tUs = new long[tempoEvents.size()];
        for (int i = 0; i < tempoEvents.size(); i++) {
            long tk = tempoEvents.get(i)[0];
            long us = tempoEvents.get(i)[1];
            if (m > 0 && tTick[m - 1] == tk) {
                tUs[m - 1] = us; // later event at the same tick wins
            } else {
                tTick[m] = tk;
                tUs[m] = us;
                m++;
            }
        }

        int segCount = m + ((m == 0 || tTick[0] > 0) ? 1 : 0);
        long[] sTick = new long[segCount];
        double[] sMsPerTick = new double[segCount];
        double[] sCumMs = new double[segCount];
        int idx = 0;
        if (m == 0 || tTick[0] > 0) {
            sTick[idx] = 0L; // default tempo applies from tick 0
            sMsPerTick[idx] = DEFAULT_TEMPO_US / (1000.0 * (double) ppq);
            idx++;
        }
        for (int i = 0; i < m; i++) {
            sTick[idx] = tTick[i];
            sMsPerTick[idx] = tUs[i] / (1000.0 * (double) ppq);
            idx++;
        }
        double cum = 0.0;
        for (int i = 0; i < segCount; i++) {
            if (i > 0) {
                cum += (sTick[i] - sTick[i - 1]) * sMsPerTick[i - 1];
            }
            sCumMs[i] = cum;
        }

        // ---- convert ticks to milliseconds ---------------------------------
        List<RawNote> notes = new ArrayList<RawNote>(rawNotes.size());
        long maxNoteEndMs = 0L;
        for (int i = 0; i < rawNotes.size(); i++) {
            long[] r = rawNotes.get(i);
            int midi = (int) r[2];
            if (midi < 0 || midi > 127) {
                continue; // defensive: never happens with validated data bytes
            }
            long startMs = tickToMs(r[0], smpte, msPerTick, sTick, sMsPerTick, sCumMs, segCount);
            long endMs = tickToMs(r[1], smpte, msPerTick, sTick, sMsPerTick, sCumMs, segCount);
            if (startMs < 0L) {
                startMs = 0L;
            }
            if (endMs < startMs) {
                endMs = startMs;
            }
            long durMs = endMs - startMs;
            if (durMs < 1L) {
                durMs = 1L;
            }
            notes.add(new RawNote(midi, startMs, durMs));
            if (endMs > maxNoteEndMs) {
                maxNoteEndMs = endMs;
            }
        }

        Collections.sort(notes, new Comparator<RawNote>() {
            public int compare(RawNote a, RawNote b) {
                if (a.startMs < b.startMs) {
                    return -1;
                }
                if (a.startMs > b.startMs) {
                    return 1;
                }
                if (a.midi < b.midi) {
                    return -1;
                }
                if (a.midi > b.midi) {
                    return 1;
                }
                return 0;
            }
        });

        long lengthMs = tickToMs(maxTick, smpte, msPerTick, sTick, sMsPerTick, sCumMs, segCount);
        if (lengthMs < maxNoteEndMs) {
            lengthMs = maxNoteEndMs;
        }
        if (lengthMs < 0L) {
            lengthMs = 0L;
        }

        return new Result(notes, lengthMs);
    }

    // ------------------------------------------------------------------
    // Track parsing
    // ------------------------------------------------------------------

    /**
     * Parse a single bounded MTrk chunk.
     *
     * @return the final absolute tick position reached within the track
     */
    private static long parseTrack(Cursor c, int trackIndex, List<long[]> rawNotes,
            List<long[]> tempoEvents, int[] eventCounter) throws IOException {
        long absTick = 0L;
        int runningStatus = 0;
        boolean[] active = new boolean[NOTE_KEYS];
        long[] startTick = new long[NOTE_KEYS];

        while (c.pos < c.end) {
            eventCounter[0]++;
            if (eventCounter[0] > MAX_EVENTS) {
                throw new IOException("MIDI event count exceeded the safety limit of " + MAX_EVENTS);
            }

            // Every iteration consumes at least one byte (the delta time), so
            // this loop always makes progress even for zero-length deltas.
            int delta = c.readVarLen("delta time");
            absTick += delta;

            int status = c.peekU8("event status");

            if (status == 0xFF) {
                // ---- meta event ----
                c.pos++;
                runningStatus = 0; // meta events cancel running status
                int type = c.readU8("meta event type");
                long len = c.readVarLen("meta event length");
                if (len > c.remaining()) {
                    throw new IOException("meta event 0x" + toHex2(type) + " in track " + trackIndex
                            + " declares length " + len + " but only " + c.remaining()
                            + " byte(s) remain in the track");
                }
                int metaEnd = c.pos + (int) len;
                if (type == 0x51) {
                    if (len != 3L) {
                        throw new IOException("Set Tempo meta event in track " + trackIndex
                                + " must have length 3 but declares " + len);
                    }
                    int usPerQuarter = ((c.data[c.pos] & 0xFF) << 16)
                            | ((c.data[c.pos + 1] & 0xFF) << 8)
                            | (c.data[c.pos + 2] & 0xFF);
                    if (usPerQuarter <= 0) {
                        throw new IOException("invalid Set Tempo value " + usPerQuarter
                                + " in track " + trackIndex);
                    }
                    tempoEvents.add(new long[] { absTick, usPerQuarter });
                }
                // 0x03 track name / 0x04 instrument name and everything else: skipped.
                c.pos = metaEnd;
                if (type == 0x2F) {
                    break; // end of track
                }
            } else if (status == 0xF0 || status == 0xF7) {
                // ---- sysex / escape ----
                c.pos++;
                runningStatus = 0; // sysex cancels running status
                long len = c.readVarLen("sysex event length");
                if (len > c.remaining()) {
                    throw new IOException("sysex event in track " + trackIndex + " declares length "
                            + len + " but only " + c.remaining() + " byte(s) remain in the track");
                }
                c.pos += (int) len;
            } else if (status >= 0x80) {
                c.pos++;
                if (status >= 0xF0) {
                    throw new IOException("unexpected system status byte 0x" + toHex2(status)
                            + " in track " + trackIndex + " at offset " + (c.pos - 1));
                }
                runningStatus = status;
                handleChannelMessage(c, status, absTick, trackIndex, active, startTick, rawNotes);
            } else {
                if (runningStatus == 0) {
                    throw new IOException("running status used in track " + trackIndex
                            + " at offset " + c.pos + " before any status byte was set");
                }
                handleChannelMessage(c, runningStatus, absTick, trackIndex, active, startTick, rawNotes);
            }
        }

        // Close any note still sounding at the end of the track.
        for (int k = 0; k < NOTE_KEYS; k++) {
            if (active[k]) {
                rawNotes.add(new long[] { startTick[k], absTick, k & 0x7F });
                active[k] = false;
            }
        }
        return absTick;
    }

    private static void handleChannelMessage(Cursor c, int status, long absTick, int trackIndex,
            boolean[] active, long[] startTick, List<long[]> rawNotes) throws IOException {
        int type = status & 0xF0;
        int channel = status & 0x0F;
        int dataLen = (type == 0xC0 || type == 0xD0) ? 1 : 2;

        int d1 = readDataByte(c, trackIndex, status);
        int d2 = 0;
        if (dataLen == 2) {
            d2 = readDataByte(c, trackIndex, status);
        }
        if (dataLen == 1) {
            return; // program change / channel pressure: no note information
        }

        int key = (channel << 7) | (d1 & 0x7F);

        if (type == 0x90) {
            if (d2 > 0) {
                if (active[key]) {
                    // Re-trigger without an intervening note off: close the old one first.
                    rawNotes.add(new long[] { startTick[key], absTick, d1 & 0x7F });
                }
                active[key] = true;
                startTick[key] = absTick;
            } else if (active[key]) {
                // Note On with velocity 0 is a Note Off.
                rawNotes.add(new long[] { startTick[key], absTick, d1 & 0x7F });
                active[key] = false;
            }
        } else if (type == 0x80) {
            if (active[key]) {
                rawNotes.add(new long[] { startTick[key], absTick, d1 & 0x7F });
                active[key] = false;
            }
        }
        // other channel voice messages (poly/channel pressure, CC, pitch bend) carry no notes
    }

    private static int readDataByte(Cursor c, int trackIndex, int status) throws IOException {
        int b = c.peekU8("MIDI data byte");
        if (b >= 0x80) {
            throw new IOException("expected a MIDI data byte (< 0x80) in track " + trackIndex
                    + " but found status byte 0x" + toHex2(b) + " (status 0x" + toHex2(status) + ")");
        }
        c.pos++;
        return b;
    }

    // ------------------------------------------------------------------
    // Time conversion
    // ------------------------------------------------------------------

    private static long tickToMs(long tick, boolean smpte, double msPerTick, long[] sTick,
            double[] sMsPerTick, double[] sCumMs, int segCount) {
        if (smpte) {
            return Math.round(tick * msPerTick);
        }
        if (tick < 0L) {
            tick = 0L;
        }
        int lo = 0;
        int hi = segCount - 1;
        int found = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (sTick[mid] <= tick) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        double ms = sCumMs[found] + (tick - sTick[found]) * sMsPerTick[found];
        return Math.round(ms);
    }

    // ------------------------------------------------------------------
    // Byte cursor
    // ------------------------------------------------------------------

    private static final class Cursor {
        final byte[] data;
        int pos;
        final int end;

        Cursor(byte[] data, int start, int end) {
            this.data = data;
            this.pos = start;
            this.end = end;
        }

        int remaining() {
            return end - pos;
        }

        int peekU8(String what) throws IOException {
            if (pos >= end) {
                throw new IOException("unexpected end of data while reading " + what
                        + " (offset " + pos + ")");
            }
            return data[pos] & 0xFF;
        }

        int readU8(String what) throws IOException {
            int v = peekU8(what);
            pos++;
            return v;
        }

        int readU16(String what) throws IOException {
            int hi = readU8(what);
            int lo = readU8(what);
            return (hi << 8) | lo;
        }

        long readU32(String what) throws IOException {
            long v = 0L;
            for (int i = 0; i < 4; i++) {
                v = (v << 8) | (long) readU8(what);
            }
            return v;
        }

        byte[] readMagic() throws IOException {
            if (end - pos < 4) {
                throw new IOException("unexpected end of data while reading a 4-byte chunk id"
                        + " (offset " + pos + ", " + (end - pos) + " byte(s) left)");
            }
            byte[] m = new byte[] { data[pos], data[pos + 1], data[pos + 2], data[pos + 3] };
            pos += 4;
            return m;
        }

        /** Reads an SMF variable-length quantity; at most 4 bytes are legal. */
        int readVarLen(String what) throws IOException {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int b = readU8(what);
                value = (value << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("invalid variable-length quantity in " + what
                    + ": more than 4 bytes (offset " + pos + ")");
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static boolean isMagic(byte[] m, char a, char b, char c, char d) {
        return m.length == 4 && m[0] == (byte) a && m[1] == (byte) b
                && m[2] == (byte) c && m[3] == (byte) d;
    }

    private static String magicToString(byte[] m) {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < m.length; i++) {
            int v = m[i] & 0xFF;
            sb.append(v >= 0x20 && v < 0x7F ? (char) v : '?');
        }
        return "\"" + sb.toString() + "\"";
    }

    private static String toHex2(int v) {
        String s = Integer.toHexString(v & 0xFF).toUpperCase();
        return s.length() < 2 ? "0" + s : s;
    }

    private static String toHex4(int v) {
        String s = Integer.toHexString(v & 0xFFFF).toUpperCase();
        while (s.length() < 4) {
            s = "0" + s;
        }
        return s;
    }
}
