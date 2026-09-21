package com.handpan.autoplay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * File format for a recorded performance.
 *
 * <p>A recording is only what it sounds like: which pad was pressed and when. No audio is captured -
 * the app cannot hear the game, and the point is to replay the presses later.
 *
 * <p>Plain {@code key=value} header followed by {@code slot,atMs} lines, working on a generic
 * {@link Writer}/{@link BufferedReader} so the format is covered by ordinary JVM tests.
 */
public final class RecordingCodec {

    static final int FORMAT_VERSION = 1;

    /** One recorded press. */
    public static final class Hit {
        public final int slot;
        public final long atMs;

        public Hit(int slot, long atMs) {
            this.slot = slot;
            this.atMs = atMs;
        }
    }

    /** Recording contents, free of Android types. */
    public static final class Data {
        public final String name;
        public final long savedAt;
        public final List<Hit> hits;

        Data(String name, long savedAt, List<Hit> hits) {
            this.name = name;
            this.savedAt = savedAt;
            this.hits = hits;
        }

        public long lengthMs() {
            long end = 0;
            for (int i = 0; i < hits.size(); i++) end = Math.max(end, hits.get(i).atMs);
            return end;
        }
    }

    private RecordingCodec() {}

    public static void write(Writer writer, String name, long savedAt, List<Hit> hits)
            throws IOException {
        put(writer, "version", String.valueOf(FORMAT_VERSION));
        put(writer, "name", name);
        put(writer, "savedAt", String.valueOf(savedAt));
        put(writer, "hits", String.valueOf(hits == null ? 0 : hits.size()));
        if (hits == null) {
            writer.flush();
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            writer.write(hit.slot + "," + hit.atMs + "\n");
        }
        writer.flush();
    }

    private static void put(Writer writer, String key, String value) throws IOException {
        writer.write(key);
        writer.write('=');
        writer.write(value == null ? "" : value.replace('\n', ' ').replace('\r', ' '));
        writer.write('\n');
    }

    /** @return the parsed recording, or null when the stream is not a usable recording. */
    public static Data read(BufferedReader reader) throws IOException {
        String name = "";
        long savedAt = 0;
        int count = -1;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("hits=")) {
                count = parse(line.substring(5), -1);
                break; // header ends here; the rest is press data
            }
            int split = line.indexOf('=');
            if (split <= 0) continue;
            String key = line.substring(0, split);
            String value = line.substring(split + 1);
            if ("version".equals(key)) {
                if (parse(value, -1) != FORMAT_VERSION) return null;
            } else if ("name".equals(key)) {
                name = value;
            } else if ("savedAt".equals(key)) {
                savedAt = parseLong(value, 0);
            }
        }
        if (count < 0) return null;

        List<Hit> hits = new ArrayList<Hit>(Math.max(16, count));
        while ((line = reader.readLine()) != null) {
            int comma = line.indexOf(',');
            if (comma <= 0) continue;
            try {
                int slot = Integer.parseInt(line.substring(0, comma).trim());
                long at = Long.parseLong(line.substring(comma + 1).trim());
                if (slot >= 0 && slot < PadMapper.SLOTS && at >= 0) {
                    hits.add(new Hit(slot, at));
                }
            } catch (NumberFormatException ignored) {
                // One damaged line should not discard the whole recording.
            }
        }
        return new Data(name, savedAt, hits);
    }

    private static int parse(String value, int fallback) {
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
