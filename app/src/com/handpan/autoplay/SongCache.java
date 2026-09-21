package com.handpan.autoplay;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores a parsed song so it never has to be parsed again.
 *
 * <p>Parsing is the slow part of importing - a four minute track spends seconds in the audio decoder
 * and in pitch detection - and it can fail outright if the original file was later moved, deleted, or
 * lived behind a cloud provider whose grant expired. Saving the notes sidesteps both problems:
 * playback reads the snapshot and never touches the source document.
 *
 * <p>The on-disk format lives in {@link SnapshotCodec}, which works on a plain
 * {@link java.io.Writer}/{@link java.io.Reader} so it is covered by ordinary JVM tests. This class
 * only resolves the file path, hashes the source URI, and converts to and from
 * {@link SongLoader.Song}.
 */
public final class SongCache {

    private static final String DIR = "songs";
    private static final int FORMAT_VERSION = 1;

    /** A restored song together with the settings it was saved under. */
    public static final class Snapshot {
        public final SongLoader.Song song;
        public final String key;
        public final int bpm;
        public final boolean octaveAware;
        public final int maxChord;
        public final long savedAt;

        Snapshot(SongLoader.Song song, String key, int bpm, boolean octaveAware,
                 int maxChord, long savedAt) {
            this.song = song;
            this.key = key;
            this.bpm = bpm;
            this.octaveAware = octaveAware;
            this.maxChord = maxChord;
            this.savedAt = savedAt;
        }
    }

    private SongCache() {}

    // ---------------------------------------------------------------- storage (Android)

    private static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File fileFor(Context c, String uri) {
        return new File(dir(c), sha1(uri == null ? "" : uri) + ".notes");
    }

    public static boolean has(Context c, String uri) {
        return uri != null && fileFor(c, uri).isFile();
    }

    /** Saves the parsed notes plus the settings they were played with. */
    public static boolean save(Context c, String uri, SongLoader.Song song, String key,
                               int bpm, boolean octaveAware, int maxChord) {
        if (uri == null || song == null) return false;
        File target = fileFor(c, uri);
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(target), "UTF-8");
            SnapshotCodec.write(writer, song.name, song.kind, song.lengthMs, key, bpm, octaveAware,
                    maxChord, System.currentTimeMillis(), song.notes);
            return true;
        } catch (Exception e) {
            target.delete();
            return false;
        } finally {
            close(writer);
        }
    }

    /** @return the saved song, or null when there is no usable snapshot. */
    public static Snapshot load(Context c, String uri) {
        if (uri == null) return null;
        File source = fileFor(c, uri);
        if (!source.isFile()) return null;

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(source), "UTF-8"));
            SnapshotCodec.Data data = SnapshotCodec.read(reader);
            if (data == null) return null;
            SongLoader.Song song = new SongLoader.Song(
                    data.kind == null || data.kind.length() == 0 ? "存档" : data.kind,
                    data.name, data.notes, data.lengthMs, "来自存档，未重新解析原始文件");
            return new Snapshot(song, data.key, data.bpm, data.octaveAware, data.maxChord,
                    data.savedAt);
        } catch (Exception e) {
            return null;
        } finally {
            close(reader);
        }
    }

    public static void remove(Context c, String uri) {
        if (uri != null) fileFor(c, uri).delete();
    }

    public static void clear(Context c) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) files[i].delete();
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
