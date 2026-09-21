package com.handpan.autoplay;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Loads a music file from a content URI and turns it into note events.
 *
 * <p>Three input families are supported: MIDI, audio (decoded then transcribed), and numbered
 * notation text.
 */
public final class SongLoader {

    public static final class Song {
        public final String kind;
        public final String name;
        public final List<RawNote> notes;
        public final long lengthMs;
        /** Plain-language trace of what the loader actually did, shown in the UI for diagnosis. */
        public final String detail;

        Song(String kind, String name, List<RawNote> notes, long lengthMs, String detail) {
            this.kind = kind;
            this.name = name;
            this.notes = notes;
            this.lengthMs = lengthMs;
            this.detail = detail;
        }
    }

    private SongLoader() {}

    /**
     * Melody analysis band (Hz).
     *
     * <p>The detector is monophonic, so on a full mix it locks onto whatever periodic signal is
     * strongest - in practice the bass and kick drum, an octave or two below the tune, which makes
     * the app play a bass line instead of the melody. Measured on a real Mandarin pop track at
     * 22050Hz: 65-2100Hz produced 157 notes averaging G2 (bass); 120-1000Hz produced 35 notes in
     * B2-F#4, the register a lead vocal actually occupies. 120Hz keeps the bass/kick region out
     * while still admitting low male voices.
     */
    private static final float MELODY_MIN_HZ = 120f;
    private static final float MELODY_MAX_HZ = 1000f;

    public static Song load(Context ctx, Uri uri) throws Exception {
        String name = displayName(ctx, uri);
        String lower = name == null ? "" : name.toLowerCase();

        if (lower.endsWith(".mid") || lower.endsWith(".midi") || lower.endsWith(".smf")) {
            byte[] data = AudioDecoder.readAll(ctx, uri, 32 * 1024 * 1024);
            MidiParser.Result r = MidiParser.parse(data);
            return new Song("MIDI", name, r.notes, r.lengthMs,
                    "读入 " + data.length + " 字节 MIDI");
        }

        if (lower.endsWith(".txt") || lower.endsWith(".jp") || lower.endsWith(".jianpu")) {
            byte[] data = AudioDecoder.readAll(ctx, uri, 2 * 1024 * 1024);
            String text = new String(data, "UTF-8");
            List<RawNote> notes = JianpuParser.parse(text, AppPrefs.getBpm(ctx));
            return new Song("简谱", name, notes, lengthOf(notes),
                    "读入 " + text.length() + " 字符简谱");
        }

        float[] pcm = AudioDecoder.decodeMono(ctx, uri, lower);
        String detail = "解码 " + pcm.length + " 样本（" + (pcm.length / AudioDecoder.TARGET_RATE)
                + " 秒 @" + AudioDecoder.TARGET_RATE + "Hz）" + describeLevels(pcm);
        List<RawNote> notes = PitchDetector.detect(pcm, AudioDecoder.TARGET_RATE,
                MELODY_MIN_HZ, MELODY_MAX_HZ);
        return new Song("音频转谱", name, notes, lengthOf(notes), detail);
    }

    /**
     * Peak and RMS of the decoded PCM.
     *
     * <p>This is the single most useful diagnostic when transcription comes back empty: a peak near
     * zero means the decoder produced silence, an absurd peak means the sample format was
     * misinterpreted, and ordinary levels (say 0.3-1.0 peak) mean decoding worked and the failure is
     * in pitch detection instead.
     */
    private static String describeLevels(float[] pcm) {
        if (pcm.length == 0) return "　峰值 0（无数据）";
        double peak = 0;
        double sum = 0;
        for (int i = 0; i < pcm.length; i++) {
            double v = pcm[i];
            double a = v < 0 ? -v : v;
            if (a > peak) peak = a;
            sum += v * v;
        }
        return String.format(java.util.Locale.US, "　峰值 %.3f RMS %.4f",
                peak, Math.sqrt(sum / pcm.length));
    }

    private static long lengthOf(List<RawNote> notes) {
        long end = 0;
        for (int i = 0; i < notes.size(); i++) {
            RawNote n = notes.get(i);
            end = Math.max(end, n.startMs + n.durMs);
        }
        return end;
    }

    public static String displayName(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String v = c.getString(idx);
                    if (v != null && v.length() > 0) return v;
                }
            }
        } catch (RuntimeException ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "unknown" : last;
    }

    /**
     * Reduces a polyphonic note list to a single melodic line.
     *
     * <p>Greedy sweep: notes play in order; when a new note starts before the current one ends, the
     * higher pitch wins and becomes the melody (chords therefore collapse to their top voice, which
     * is the usual melody-carrying voice for a single-line instrument).
     */
    public static List<RawNote> monophonic(List<RawNote> in) {
        List<RawNote> sorted = new ArrayList<RawNote>(in);
        Collections.sort(sorted, new Comparator<RawNote>() {
            @Override
            public int compare(RawNote a, RawNote b) {
                if (a.startMs != b.startMs) return a.startMs < b.startMs ? -1 : 1;
                if (a.midi != b.midi) return a.midi > b.midi ? -1 : 1;
                return 0;
            }
        });

        List<RawNote> out = new ArrayList<RawNote>();
        RawNote cur = null;
        for (int i = 0; i < sorted.size(); i++) {
            RawNote n = sorted.get(i);
            if (cur == null) {
                cur = n;
                continue;
            }
            long curEnd = cur.startMs + cur.durMs;
            if (n.startMs >= curEnd) {
                out.add(cur);
                cur = n;
            } else if (n.midi > cur.midi) {
                long d = n.startMs - cur.startMs;
                if (d >= 1) out.add(new RawNote(cur.midi, cur.startMs, d));
                cur = n;
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }
}
