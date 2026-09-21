package com.handpan.autoplay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the on-disk format for a saved song.
 *
 * <p>Split out of {@link SongCache} on purpose: that class needs Android for file paths, which makes
 * it untestable off-device. Everything here works on plain {@link Writer}/{@link Reader}, so the
 * format is covered by ordinary JVM tests. (A format nobody can test is how the tap list once
 * shipped permanently empty.)
 *
 * <p>Layout: a {@code key=value} header terminated by a {@code notes=N} line, then one
 * {@code midi,startMs,durMs} line per note.
 */
public final class SnapshotCodec {

    static final int FORMAT_VERSION = 1;

    /** Snapshot contents, free of Android types. */
    public static final class Data {
        public final String name;
        public final String kind;
        public final String key;
        public final long lengthMs;
        public final long savedAt;
        public final int bpm;
        public final int maxChord;
        public final boolean octaveAware;
        public final List<RawNote> notes;

        Data(String name, String kind, String key, long lengthMs, long savedAt,
             int bpm, int maxChord, boolean octaveAware, List<RawNote> notes) {
            this.name = name;
            this.kind = kind;
            this.key = key;
            this.lengthMs = lengthMs;
            this.savedAt = savedAt;
            this.bpm = bpm;
            this.maxChord = maxChord;
            this.octaveAware = octaveAware;
            this.notes = notes;
        }
    }

    private SnapshotCodec() {}

    /** Writes a snapshot. Line breaks inside values are stripped, since one value is one line. */
    public static void write(Writer writer, String name, String kind, long lengthMs, String key,
                             int bpm, boolean octaveAware, int maxChord, long savedAt,
                             List<RawNote> notes) throws IOException {
        put(writer, "version", String.valueOf(FORMAT_VERSION));
        put(writer, "name", name);
        put(writer, "kind", kind);
        put(writer, "lengthMs", String.valueOf(lengthMs));
        put(writer, "key", key);
        put(writer, "bpm", String.valueOf(bpm));
        put(writer, "octave", String.valueOf(octaveAware));
        put(writer, "chord", String.valueOf(maxChord));
        put(writer, "savedAt", String.valueOf(savedAt));
        put(writer, "notes", String.valueOf(notes == null ? 0 : notes.size()));
        if (notes == null) {
            writer.flush();
            return;
        }
        for (int i = 0; i < notes.size(); i++) {
            RawNote n = notes.get(i);
            writer.write(n.midi + "," + n.startMs + "," + n.durMs + "\n");
        }
        writer.flush();
    }

    private static void put(Writer writer, String key, String value) throws IOException {
        writer.write(key);
        writer.write('=');
        writer.write(value == null ? "" : value.replace('\n', ' ').replace('\r', ' '));
        writer.write('\n');
    }

    /** @return the parsed snapshot, or null when the stream is not a usable snapshot. */
    public static Data read(BufferedReader reader) throws IOException {
        String name = "", kind = "", key = "";
        long lengthMs = 0, savedAt = 0;
        int bpm = 0, maxChord = 4, count = -1;
        boolean octaveAware = true;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("notes=")) {
                count = parseInt(line.substring(6), -1);
                break; // the header ends here; everything after it is note data
            }
            int split = line.indexOf('=');
            if (split <= 0) continue;
            String k = line.substring(0, split);
            String v = line.substring(split + 1);
            if ("version".equals(k)) {
                if (parseInt(v, -1) != FORMAT_VERSION) return null;
            } else if ("name".equals(k)) {
                name = v;
            } else if ("kind".equals(k)) {
                kind = v;
            } else if ("key".equals(k)) {
                key = v;
            } else if ("lengthMs".equals(k)) {
                lengthMs = parseLong(v, 0);
            } else if ("savedAt".equals(k)) {
                savedAt = parseLong(v, 0);
            } else if ("bpm".equals(k)) {
                bpm = parseInt(v, 0);
            } else if ("octave".equals(k)) {
                octaveAware = Boolean.parseBoolean(v);
            } else if ("chord".equals(k)) {
                maxChord = parseInt(v, 4);
            }
        }
        if (count < 0) return null; // header incomplete

        List<RawNote> notes = new ArrayList<RawNote>(Math.max(16, count));
        while ((line = reader.readLine()) != null) {
            int first = line.indexOf(',');
            if (first <= 0) continue;
            int second = line.indexOf(',', first + 1);
            if (second <= first) continue;
            try {
                notes.add(new RawNote(Integer.parseInt(line.substring(0, first)),
                        Long.parseLong(line.substring(first + 1, second)),
                        Long.parseLong(line.substring(second + 1))));
            } catch (NumberFormatException ignored) {
                // One damaged line should not throw away the whole snapshot.
            }
        }
        return new Data(name, kind, key, lengthMs, savedAt, bpm, maxChord, octaveAware, notes);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
