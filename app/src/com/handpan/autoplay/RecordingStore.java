package com.handpan.autoplay;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Persists recorded performances (which pad was pressed, when) under the app's private directory.
 *
 * <p>One file per recording, named by a generated id. This is what makes a recording playable later:
 * the main screen turns the stored presses straight into taps, without going through note parsing or
 * the note-to-pad mapping - which would risk changing the octave of a pad on the way back out.
 */
public final class RecordingStore {

    /** Synthetic URI prefix used for recordings listed in the song library. */
    public static final String SCHEME = "recording://";

    private static final String DIR = "recordings";

    /** Summary of one stored recording. */
    public static final class Entry {
        public final String id;
        public final String name;
        public final long savedAt;
        public final int hits;
        public final long lengthMs;

        Entry(String id, String name, long savedAt, int hits, long lengthMs) {
            this.id = id;
            this.name = name;
            this.savedAt = savedAt;
            this.hits = hits;
            this.lengthMs = lengthMs;
        }

        public String uri() {
            return SCHEME + id;
        }
    }

    private RecordingStore() {}

    private static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File fileFor(Context c, String id) {
        return new File(dir(c), id.replaceAll("[^A-Za-z0-9_-]", "_") + ".rec");
    }

    public static boolean isRecording(String uri) {
        return uri != null && uri.startsWith(SCHEME);
    }

    public static String idOf(String uri) {
        return isRecording(uri) ? uri.substring(SCHEME.length()) : null;
    }

    /** @return the new recording's id, or null when saving failed. */
    public static String save(Context c, String name, List<RecordingCodec.Hit> hits, long savedAt) {
        if (hits == null || hits.isEmpty()) return null;
        String id = "r" + savedAt;
        File target = fileFor(c, id);
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(target), "UTF-8");
            RecordingCodec.write(writer, name, savedAt, hits);
            return id;
        } catch (Exception e) {
            target.delete();
            return null;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** @return the stored recording, or null when it is missing or unreadable. */
    public static RecordingCodec.Data load(Context c, String id) {
        if (id == null) return null;
        File source = fileFor(c, id);
        if (!source.isFile()) return null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(source), "UTF-8"));
            return RecordingCodec.read(reader);
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** All recordings, newest first, with their header read for the summary line. */
    public static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<Entry>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        for (int i = 0; i < files.length; i++) {
            String fileName = files[i].getName();
            if (!fileName.endsWith(".rec")) continue;
            String id = fileName.substring(0, fileName.length() - 4);
            RecordingCodec.Data data = load(c, id);
            if (data == null) continue;
            out.add(new Entry(id, data.name, data.savedAt, data.hits.size(), data.lengthMs()));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return b.savedAt > a.savedAt ? 1 : (b.savedAt == a.savedAt ? 0 : -1);
            }
        });
        return out;
    }

    public static void delete(Context c, String id) {
        if (id != null) fileFor(c, id).delete();
    }

    public static void clear(Context c) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) files[i].delete();
    }
}
