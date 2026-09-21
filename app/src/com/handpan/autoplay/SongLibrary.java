package com.handpan.autoplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers the songs that have been imported, so they can be re-opened without hunting for the
 * file again.
 *
 * <p>Entries are stored as a JSON array in SharedPreferences. The URI is kept alongside the display
 * name, key and size so the list is still informative when a file has since been moved (re-opening
 * then fails with a clear message instead of silently doing nothing).
 *
 * <p>Re-opening only works while the URI grant is alive, which is why the picker requests a
 * persistable grant and {@code MainActivity} calls
 * {@code takePersistableUriPermission} - a plain {@code ACTION_OPEN_DOCUMENT} grant dies with the
 * activity, and every entry in this list would break on the next launch.
 */
public final class SongLibrary {

    /** One remembered import. */
    public static final class Entry {
        public final String uri;
        public final String name;
        public final String kind;
        public final String key;
        public final int hits;
        public final long seconds;
        public final long addedAt;

        Entry(String uri, String name, String kind, String key, int hits, long seconds, long addedAt) {
            this.uri = uri;
            this.name = name;
            this.kind = kind;
            this.key = key;
            this.hits = hits;
            this.seconds = seconds;
            this.addedAt = addedAt;
        }

        /** Detail line under the song name: format, key, size, and when it was imported. */
        public String subtitle() {
            StringBuilder sb = new StringBuilder();
            if (kind != null && kind.length() > 0) sb.append(kind).append(" · ");
            sb.append(key == null || key.length() == 0 ? "?" : key + "调");
            if (hits > 0) sb.append(" · ").append(hits).append(" 次点击");
            if (seconds > 0) sb.append(" · ").append(seconds).append(" 秒");
            if (addedAt > 0) {
                sb.append(" · ").append(new java.text.SimpleDateFormat("MM-dd HH:mm",
                        java.util.Locale.getDefault()).format(new java.util.Date(addedAt)));
            }
            return sb.toString();
        }
    }

    private static final String FILE = "handpan_prefs";
    private static final String KEY_LIB = "song_library";
    private static final int MAX_ENTRIES = 50;

    private SongLibrary() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Most recently imported first. */
    public static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<Entry>();
        String raw = sp(c).getString(KEY_LIB, null);
        if (raw == null || raw.length() == 0) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                out.add(new Entry(o.optString("uri", ""), o.optString("name", ""),
                        o.optString("kind", ""), o.optString("key", ""), o.optInt("hits", 0),
                        o.optLong("seconds", 0), o.optLong("addedAt", 0)));
            }
        } catch (JSONException e) {
            // Corrupt store is not worth a crash: start over rather than lose the app.
            return new ArrayList<Entry>();
        }
        return out;
    }

    /** Adds or refreshes an entry, moving it to the top. */
    public static void add(Context c, String uri, String name, String kind, String key,
                           int hits, long seconds) {
        if (uri == null || uri.length() == 0) return;
        List<Entry> entries = list(c);
        List<Entry> next = new ArrayList<Entry>();
        next.add(new Entry(uri, name, kind, key, hits, seconds, System.currentTimeMillis()));
        for (int i = 0; i < entries.size() && next.size() < MAX_ENTRIES; i++) {
            if (!uri.equals(entries.get(i).uri)) next.add(entries.get(i));
        }
        save(c, next);
    }

    public static void remove(Context c, String uri) {
        List<Entry> entries = list(c);
        List<Entry> next = new ArrayList<Entry>();
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).uri.equals(uri)) next.add(entries.get(i));
        }
        save(c, next);
    }

    public static void clear(Context c) {
        sp(c).edit().remove(KEY_LIB).apply();
    }

    /** Finds a remembered key for a URI, or null. Used to restore the user's own key choice. */
    public static String keyFor(Context c, Uri uri) {
        if (uri == null) return null;
        String s = uri.toString();
        List<Entry> entries = list(c);
        for (int i = 0; i < entries.size(); i++) {
            if (s.equals(entries.get(i).uri)) return entries.get(i).key;
        }
        return null;
    }

    private static void save(Context c, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            JSONObject o = new JSONObject();
            try {
                o.put("uri", e.uri);
                o.put("name", e.name);
                o.put("kind", e.kind);
                o.put("key", e.key);
                o.put("hits", e.hits);
                o.put("seconds", e.seconds);
                o.put("addedAt", e.addedAt);
            } catch (JSONException ignored) {
            }
            array.put(o);
        }
        sp(c).edit().putString(KEY_LIB, array.toString()).apply();
    }
}
