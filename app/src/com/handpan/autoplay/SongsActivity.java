package com.handpan.autoplay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Songs screen: import a file, parse or save it, and pick from everything imported before.
 *
 * <p>Selecting a song loads it into {@link Session} and finishes, returning to the play screen. That
 * keeps this screen free of playback concerns and the play screen free of file handling.
 *
 * <p>Loading prefers a saved snapshot over parsing: that is the entire point of 【手动保存】, and it is
 * also what makes sequence and random playback practical, since switching songs happens while the
 * user is already inside the game.
 */
public class SongsActivity extends Activity {

    private static final int REQ_PICK = 2001;

    /** The play screen looks at this to decide whether to auto-start when we come back. */
    public static final String EXTRA_AUTO_PLAY = "auto_play";

    private TextView tvStatus;
    private TextView tvLibraryTitle;
    private TextView tvLibraryEmpty;
    private LinearLayout listSongs;

    private Uri currentUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_songs);
        setTitle("曲目库");

        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvLibraryTitle = (TextView) findViewById(R.id.tv_library_title);
        tvLibraryEmpty = (TextView) findViewById(R.id.tv_library_empty);
        listSongs = (LinearLayout) findViewById(R.id.list_songs);

        findViewById(R.id.btn_pick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        findViewById(R.id.btn_clear_library).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SongLibrary.clear(SongsActivity.this);
                SongCache.clear(SongsActivity.this);
                refreshLibrary();
                status("曲目列表和存档都已清空。");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLibrary();
    }

    private Uri sessionUri() {
        String uri = Session.uri();
        return uri == null ? null : Uri.parse(uri);
    }

    // ------------------------------------------------------------------ import and parse

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "audio/*", "audio/midi", "audio/x-midi", "application/octet-stream", "text/plain"});
        // Persistable grant: entries in this list must stay readable after a restart.
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (RuntimeException e) {
            status("打不开文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Provider offered no persistable grant; the entry still works this session.
        }
        currentUri = uri;
        parse(uri, true);
    }

    /**
     * @param autoDetect true for a freshly picked file (detect the key); false when re-parsing on
     *                   request, where the user's own key choice must be preserved.
     */
    private void parse(final Uri uri, final boolean autoDetect) {
        status("正在解析 " + SongLoader.displayName(this, uri) + " …");
        Loader.load(this, uri, new Loader.Callback() {
            @Override
            public void onLoaded(Uri loaded, SongLoader.Song song) {
                currentUri = loaded;
                if (autoDetect) {
                    List<RawNote> mono = SongLoader.monophonic(song.notes);
                    AppPrefs.setKey(SongsActivity.this, KeyDetector.bestKeyName(mono));
                    int bpm = TempoEstimator.estimate(mono);
                    if (bpm > 0) AppPrefs.setBpm(SongsActivity.this, bpm);
                }
                Session.set(loaded.toString(), song);
                remember(loaded, song);
                refreshLibrary();
                        status("解析完成：" + song.notes.size() + " 个音符，"
                        + (song.lengthMs / 1000) + " 秒。已选为当前曲目，返回即可演奏。");
            }

            @Override
            public void onError(Uri failed, Throwable error) {
                status("解析失败：" + SongLoader.displayName(SongsActivity.this, failed)
                        + " —— " + Loader.describe(error));
            }
        });
    }

    private void remember(Uri uri, SongLoader.Song song) {
        int root = AppPrefs.getRootPitchClass(this);
        List<RawNote> mono = SongLoader.monophonic(song.notes);
        int tonic = PadMapper.tonicFor(lowestMidi(mono), root);
        int hits = TapPlanner.plan(song.notes, root, tonic, AppPrefs.getUseZeroPad(this), 1.0f,
                AppPrefs.getChordLimit(this)).size();
        SongLibrary.add(this, uri.toString(), SongLoader.displayName(this, uri), song.kind,
                AppPrefs.getKey(this), hits, song.lengthMs / 1000);
    }

    private static int lowestMidi(List<RawNote> notes) {
        int low = 127;
        for (int i = 0; i < notes.size(); i++) low = Math.min(low, notes.get(i).midi);
        return low;
    }

    // ------------------------------------------------------------------ list

    /** Rebuilds the song list. Plain views so rows can live inside the ScrollView. */
    private void refreshLibrary() {
        listSongs.removeAllViews();
        final List<SongLibrary.Entry> entries = SongLibrary.list(this);
        tvLibraryEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        tvLibraryTitle.setText(entries.isEmpty()
                ? "已导入曲目"
                : "已导入曲目（" + entries.size() + "）　点击载入 · 长按删除");

        for (int i = 0; i < entries.size(); i++) {
            final SongLibrary.Entry entry = entries.get(i);
            final boolean current = entry.uri.equals(Session.uri());

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.row_song);
            int pad = Ui.dp(this, 14);
            row.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, Ui.dp(this, 8));
            row.setLayoutParams(params);

            TextView title = new TextView(this);
            title.setText((current ? "▶ " : "")
                    + (entry.name == null || entry.name.length() == 0 ? "(未命名)" : entry.name));
            title.setTextSize(16f);
            title.setTextColor(current ? 0xFF1565C0 : 0xFF102A43);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);

            TextView meta = new TextView(this);
            meta.setText(entry.subtitle() + (SongCache.has(this, entry.uri) ? " · 已存档" : ""));
            meta.setTextSize(12.5f);
            meta.setTextColor(0xFF6B7C93);
            meta.setPadding(0, Ui.dp(this, 5), 0, 0);

            row.addView(title);
            row.addView(meta);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(entry);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    SongLibrary.remove(SongsActivity.this, entry.uri);
                    SongCache.remove(SongsActivity.this, entry.uri);
                    if (entry.uri.equals(Session.uri())) Session.clear();
                    refreshLibrary();
                                status("已从列表移除（含存档）：" + entry.name);
                    return true;
                }
            });
            listSongs.addView(row);
        }
    }

    /** Loads a remembered song, preferring its snapshot so nothing is parsed again. */
    private void select(SongLibrary.Entry entry) {
        SongCache.Snapshot snapshot = SongCache.load(this, entry.uri);
        if (snapshot != null) {
            Session.set(entry.uri, snapshot.song);
            AppPrefs.setKey(this, snapshot.key);
            if (snapshot.bpm > 0) AppPrefs.setBpm(this, snapshot.bpm);
            AppPrefs.setUseZeroPad(this, snapshot.octaveAware);
            AppPrefs.setChordLimit(this, snapshot.maxChord);
            Toast.makeText(getApplicationContext(),
                    "已从存档载入 " + snapshot.song.name, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final Uri uri = Uri.parse(entry.uri);
        status("正在解析 " + entry.name + " …（这首还没存档）");
        Loader.load(this, uri, new Loader.Callback() {
            @Override
            public void onLoaded(Uri loaded, SongLoader.Song song) {
                Session.set(loaded.toString(), song);
                finish();
            }

            @Override
            public void onError(Uri failed, Throwable error) {
                status("载入失败：" + entry.name + " —— " + Loader.describe(error)
                        + "\n（文件可能已被移动或删除；已存档的曲目不依赖原文件。）");
            }
        });
    }

    private void status(String message) {
        tvStatus.setText(message);
    }
}
