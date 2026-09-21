package com.handpan.autoplay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Single screen: grant permissions, calibrate, import a song, play it. */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;
    /**
     * Key choices shown in the spinner. Taken straight from {@link KeyDetector} so the list the
     * detector can return and the list the user can pick can never drift apart - the earlier
     * duplicated 7-note array meant detected sharp/flat keys had no matching spinner entry.
     */
    private static final String[] KEYS = KeyDetector.KEYS;

    /** Most pads pressed in one chord. More than this turns to mud, and gestures cap strokes anyway. */
    private static final int MAX_CHORD = 4;

    /** Countdown before the first tap, giving the user time to switch into the game. */
    private static final int COUNTDOWN_SEC = 5;

    /** Taps closer together than this are dropped: gesture dispatch cannot resolve them. */
    private static final long MIN_GAP_MS = 60L;

    private TextView tvFile;
    private TextView tvPreview;
    private TextView tvStatus;
    private TextView tvPerms;
    private Spinner spKey;
    private EditText etBpm;
    private EditText etSpeed;
    private CheckBox cbZero;
    private Button btnPlay;
    private Button btnStop;
    private LinearLayout listSongs;
    private TextView tvLibraryEmpty;
    private TextView tvLibraryTitle;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SongLoader.Song song;
    private int countdownLeft;

    /** Last picked document; 【手动解析】 and the song list reuse it. */
    private Uri lastUri;

    /** Saved BPM to display instead of re-estimating, while a snapshot is being restored. */
    private int bpmOverride;

    /** Guards the key spinner's listener while the app sets the detected key programmatically. */
    private boolean updatingKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFile = (TextView) findViewById(R.id.tv_file);
        tvPreview = (TextView) findViewById(R.id.tv_preview);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvPerms = (TextView) findViewById(R.id.tv_perms);
        spKey = (Spinner) findViewById(R.id.sp_key);
        etBpm = (EditText) findViewById(R.id.et_bpm);
        etSpeed = (EditText) findViewById(R.id.et_speed);
        cbZero = (CheckBox) findViewById(R.id.cb_zero);
        btnPlay = (Button) findViewById(R.id.btn_play);
        btnStop = (Button) findViewById(R.id.btn_stop);
        listSongs = (LinearLayout) findViewById(R.id.list_songs);
        tvLibraryEmpty = (TextView) findViewById(R.id.tv_library_empty);
        tvLibraryTitle = (TextView) findViewById(R.id.tv_library_title);

        findViewById(R.id.btn_clear_library).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SongLibrary.clear(MainActivity.this);
                SongCache.clear(MainActivity.this);
                refreshLibrary();
                setStatus("曲目列表和存档都已清空。");
            }
        });
        refreshLibrary();

        tvPreview.setMovementMethod(new ScrollingMovementMethod());

        ArrayAdapter<String> keys = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, KEYS);
        keys.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spKey.setAdapter(keys);
        String key = AppPrefs.getKey(this);
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) spKey.setSelection(i);
        }

        etBpm.setText(String.valueOf(AppPrefs.getBpm(this)));
        etSpeed.setText(String.valueOf(AppPrefs.getSpeed(this)));
        cbZero.setChecked(AppPrefs.getUseZeroPad(this));

        // The preview must follow the key and octave switches. Without these listeners the preview
        // kept showing the auto-detected key's digits while the spinner (and therefore playback)
        // used a different key - so what the user read and what the app played disagreed.
        spKey.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!updatingKey) renderPreview();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        cbZero.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                renderPreview();
            }
        });

        findViewById(R.id.btn_overlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayController.requestPermission(MainActivity.this);
            }
        });
        findViewById(R.id.btn_access).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (RuntimeException e) {
                    setStatus("打不开无障碍设置，请手动到 设置 → 无障碍");
                }
            }
        });
        findViewById(R.id.btn_calibrate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calibrate();
            }
        });
        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testPads();
            }
        });
        findViewById(R.id.btn_pick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        findViewById(R.id.btn_reparse).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualParse();
            }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                manualSave();
            }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play();
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stop();
            }
        });

        setStatus("步骤：1) 授予悬浮窗权限  2) 开启本应用的无障碍服务  3) 校准琴键  4) 导入音乐  5) 演奏");

        if (AppPrefs.discardStaleCalibration(this)) {
            setStatus("琴键布局已从 8 键更新为 9 键（上排高音1-3／中排中音3-7／下排低音6），"
                    + "旧校准已自动作废，请重新点【校准琴键】。");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissions();
        refreshLibrary();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Deliberately no cleanup here. Playback is driven by the accessibility service's process,
        // and the user is expected to leave this activity (often with Back) to reach the game while
        // the countdown runs. Stopping on destroy would cancel exactly the run they just started.
        // The floating stop button and the fixed-length tap list are the only ways a run ends.
    }

    // ------------------------------------------------------------------ permissions

    private void refreshPermissions() {
        boolean overlay = OverlayController.canDraw(this);
        boolean access = HandpanAccessibilityService.isReady();
        int cal = AppPrefs.calibratedCount(this);
        String s = "悬浮窗 " + (overlay ? "✓" : "✗")
                + "　无障碍 " + (access ? "✓" : "✗")
                + "　已校准 " + cal + "/" + AppPrefs.SLOTS + " 键";
        tvPerms.setText(s);
        if (!access) {
            setStatus("提示：无障碍服务未开启，无法自动弹奏。点【开启无障碍服务】后"
                    + "在列表里找到「手碟自动演奏」并打开。\n"
                    + "若提示「受限设置」，请在系统弹出的菜单里选择「允许受限设置」。");
        }
    }

    // ------------------------------------------------------------------ calibration

    private void calibrate() {
        if (!OverlayController.canDraw(this)) {
            setStatus("需要先授予「悬浮窗」权限，才能把校准层盖在游戏上。点【授予悬浮窗权限】。");
            OverlayController.requestPermission(this);
            return;
        }
        if (!HandpanAccessibilityService.isReady()) {
            setStatus("建议先开启无障碍服务。校准本身不需要它，但演奏需要。");
        }
        AppPrefs.clearCalibration(this);
        setStatus("校准中：请切到游戏，按提示依次点按 9 个琴键——"
                + "上排高音 1 2 3、中排中音 3 4 5 6 7、下排低音 6 的正中心。");
        OverlayController.startCalibration(this, new OverlayController.CalibrationCallback() {
            @Override
            public void onFinished(boolean completed) {
                if (completed) {
                    setStatus("校准完成：9 个琴键坐标已保存。建议先点【试弹一遍】确认位置正确。");
                    Toast.makeText(getApplicationContext(), "校准完成", Toast.LENGTH_SHORT).show();
                } else {
                    setStatus("校准已取消。");
                }
                refreshPermissions();
            }
        });
    }

    /** Taps each calibrated pad once, so the user can verify coordinates before a real run. */
    private void testPads() {
        if (!HandpanAccessibilityService.isReady()) {
            setStatus("无障碍服务未开启，无法试弹。");
            return;
        }
        if (!AppPrefs.hasCalibration(this)) {
            setStatus("还没校准完整，先点【校准琴键】。");
            return;
        }
        List<Playback.Tap> taps = new ArrayList<Playback.Tap>();
        for (int slot = 0; slot < AppPrefs.SLOTS; slot++) {
            float[] p = AppPrefs.getPad(this, slot);
            if (p != null) {
                taps.add(new Playback.Tap(new float[]{p[0]}, new float[]{p[1]}, 800L + slot * 700L));
            }
        }
        OverlayController.showStopButton(this, new Runnable() {
            @Override
            public void run() {
                stop();
            }
        });
        setStatus("试弹中：将依次点按 上排高音1 高音2 高音3、中排中音3 4 5 6 7、下排低音6，请切到游戏观察哪个键没亮。");
        Playback.start(taps, playbackListener());
    }

    // ------------------------------------------------------------------ file

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "audio/*", "audio/midi", "audio/x-midi", "application/octet-stream", "text/plain"});
        // Persistable grant: the remembered song list must still be readable after a restart.
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (RuntimeException e) {
            setStatus("打不开文件选择器：" + e.getMessage());
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
        } catch (SecurityException e) {
            // Provider did not offer a persistable grant; the entry still works this session.
        }
        loadSong(uri, true);
    }

    /**
     * Reads and parses a document on a worker thread.
     *
     * @param autoDetect true for a freshly picked file (detect the key and overwrite the spinner);
     *                   false when re-loading from the list or 【重新解析】, where the user's own key
     *                   choice must be preserved.
     */
    private void loadSong(final Uri uri, final boolean autoDetect) {
        lastUri = uri;
        bpmOverride = 0;
        final String name = SongLoader.displayName(this, uri);
        setStatus("正在解析 " + name + " …");
        tvFile.setText("文件：" + name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final SongLoader.Song s = SongLoader.load(MainActivity.this, uri);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            song = s;
                            String remembered = autoDetect ? null : SongLibrary.keyFor(MainActivity.this, uri);
                            showSong(autoDetect, remembered);
                        }
                    });
                } catch (final Throwable e) {
                    // Throwable, not Exception: an OutOfMemoryError while decoding a multi-minute
                    // track would otherwise kill this thread silently and leave the UI stale.
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            song = null;
                            tvPreview.setText("");
                            setStatus("解析失败：" + name + " —— " + e.getClass().getSimpleName() + " "
                                    + (e.getMessage() == null ? "" : e.getMessage())
                                    + "\n（若这是从曲目列表点开的旧记录，文件可能已被移动或删除。）");
                        }
                    });
                }
            }
        }, "song-parse").start();
    }

    private void showSong(boolean autoDetect, String rememberedKey) {
        if (song == null) return;
        List<RawNote> mono = SongLoader.monophonic(song.notes);

        // Detect the key rather than making the user try twelve settings. A wrong key does not merely
        // transpose the tune, it reinterprets every scale degree, so the whole pad sequence is wrong.
        int detected = KeyDetector.bestKeyIndex(mono);
        int chosen = detected;
        if (!autoDetect && rememberedKey != null) {
            for (int i = 0; i < KEYS.length; i++) {
                if (KEYS[i].equals(rememberedKey)) {
                    chosen = i;
                    break;
                }
            }
        }
        updatingKey = true;
        spKey.setSelection(chosen);
        updatingKey = false;
        AppPrefs.setKey(this, KEYS[chosen]);

        StringBuilder note = new StringBuilder();
        note.append("解析完成：").append(song.kind).append("，原始音符 ").append(song.notes.size())
                .append("，旋律音 ").append(mono.size())
                .append("，时长 ").append(song.lengthMs / 1000).append(" 秒。");
        note.append("\n自动识别调性：").append(KEYS[detected]);
        if (chosen != detected) {
            note.append("（正在使用你选定的 ").append(KEYS[chosen]).append(" 调）");
        } else {
            note.append("（不对可在上面手动改）");
        }

        // Tempo: MIDI carries its own, but the BPM box used to be a bare number with no explanation.
        // Estimate from note onsets so every format shows a sensible value and the box stays in sync.
        int bpm = bpmOverride > 0 ? bpmOverride : TempoEstimator.estimate(mono);
        if (bpm > 0) {
            etBpm.setText(String.valueOf(bpm));
            AppPrefs.setBpm(this, bpm);
            note.append(bpmOverride > 0 ? "\n曲速（存档）：" : "\n自动识别曲速：约 ")
                    .append(bpm).append(" BPM");
        } else {
            note.append("\n曲速：音符太少识别不出，可手动填 BPM");
        }
        if (song.detail != null) {
            note.append("\n[").append(song.detail).append("]");
        }
        if (song.notes.isEmpty()) {
            note.append("\n⚠ 一首音符都没解析出来。若这是完整歌曲的音频，说明里面没有检测到稳定的单音旋律——")
                    .append("流行歌是混音，贝斯、鼓、和声会互相干扰。请改用 MIDI 文件（.mid），准确得多。");
        } else if ("音频转谱".equals(song.kind)) {
            double seconds = Math.max(1.0, song.lengthMs / 1000.0);
            double perSecond = mono.size() / seconds;
            if (perSecond < 0.8) {
                note.append("\n⚠ 转谱太稀疏：").append(mono.size()).append(" 个音摊在 ")
                        .append((int) seconds).append(" 秒里，平均每 ")
                        .append(String.format(java.util.Locale.US, "%.1f", 1 / perSecond))
                        .append(" 秒才一个音。这弹不出旋律——原曲的音绝大多数都没被识别出来。")
                        .append("这条音频路径只适合单音哼唱/独奏；完整歌曲请用 MIDI（.mid）。");
            }
        }
        setStatus(note.toString());
        rememberCurrentSong(mono, chosen);
        renderPreview();
    }

    /** Adds the just-loaded song to the remembered list shown on screen. */
    private void rememberCurrentSong(List<RawNote> mono, int keyIndex) {
        if (song == null || lastUri == null) return;
        int root = ScaleMapper.rootPitchClass(KEYS[keyIndex]);
        int tonic = PadMapper.tonicFor(lowestMidi(mono), root);
        int hits = TapPlanner.plan(song.notes, root, tonic, cbZero.isChecked(), 1.0f, MAX_CHORD).size();
        SongLibrary.add(this, lastUri.toString(), SongLoader.displayName(this, lastUri),
                song.kind, KEYS[keyIndex], hits, song.lengthMs / 1000);
        refreshLibrary();
    }

    /** Rebuilds the on-screen song list. Plain views so the rows can live inside the ScrollView. */
    private void refreshLibrary() {
        if (listSongs == null) return;
        listSongs.removeAllViews();
        final List<SongLibrary.Entry> entries = SongLibrary.list(this);
        tvLibraryEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        tvLibraryTitle.setText(entries.isEmpty()
                ? "已导入曲目"
                : "已导入曲目（" + entries.size() + "）　点击载入 · 长按删除");

        for (int i = 0; i < entries.size(); i++) {
            final SongLibrary.Entry entry = entries.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.row_song);
            int pad = dp(14);
            row.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(rowParams);

            TextView title = new TextView(this);
            title.setText(entry.name == null || entry.name.length() == 0 ? "(未命名)" : entry.name);
            title.setTextSize(16f);
            title.setTextColor(0xFF102A43);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);

            TextView meta = new TextView(this);
            meta.setText(entry.subtitle() + (SongCache.has(this, entry.uri) ? " · 已存档" : ""));
            meta.setTextSize(12.5f);
            meta.setTextColor(0xFF6B7C93);
            meta.setPadding(0, dp(5), 0, 0);

            row.addView(title);
            row.addView(meta);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadFromCache(Uri.parse(entry.uri));
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    SongLibrary.remove(MainActivity.this, entry.uri);
                    SongCache.remove(MainActivity.this, entry.uri);
                    refreshLibrary();
                    setStatus("已从列表移除（含存档）：" + entry.name);
                    return true;
                }
            });
            listSongs.addView(row);
        }
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    /**
     * Draws the note preview for the key and octave settings currently on screen.
     *
     * <p>Called after a parse and again whenever the spinner or octave checkbox changes, so the
     * digits shown always match what playback will actually do.
     */
    private void renderPreview() {
        if (song == null || tvPreview == null) return;
        List<RawNote> mono = SongLoader.monophonic(song.notes);
        String key = KEYS[spKey.getSelectedItemPosition()];
        int root = ScaleMapper.rootPitchClass(key);
        int tonic = PadMapper.tonicFor(lowestMidi(mono), root);
        boolean octaveAware = cbZero.isChecked();

        List<TapPlanner.Hit> hits = TapPlanner.plan(song.notes, root, tonic, octaveAware, 1.0f, MAX_CHORD);
        StringBuilder preview = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < hits.size() && shown < 60; i++) {
            int[] slots = hits.get(i).slots;
            for (int s = 0; s < slots.length; s++) {
                if (s > 0) preview.append('+');
                preview.append(PadMapper.shortOf(slots[s]));
            }
            preview.append(' ');
            shown++;
        }
        tvPreview.setText("【" + key + " 调】共 " + hits.size() + " 次点击，前 " + shown
                + " 次（' = 高八度，, = 低八度，+ = 同时按下）：\n" + preview
                + "\n\n改动调性或八度选项会立即刷新这里；演奏也按这里显示的来。");
    }

    // ------------------------------------------------------------------ cache

    /** Re-parses the current file with the settings on screen. */
    private void manualParse() {
        if (lastUri == null) {
            setStatus("还没导入过文件。先点【选择音乐文件】。");
            return;
        }
        loadSong(lastUri, false);
    }

    /** Stores the parsed notes so later playback never has to parse the file again. */
    private void manualSave() {
        if (song == null) {
            setStatus("还没有可保存的内容，先导入并解析一首曲子。");
            return;
        }
        if (lastUri == null) {
            setStatus("当前内容没有对应的原始文件，无法存档。");
            return;
        }
        String name = SongLoader.displayName(this, lastUri);
        boolean ok = SongCache.save(this, lastUri.toString(), song,
                KEYS[spKey.getSelectedItemPosition()],
                (int) parseFloat(etBpm, 90f, 20f, 400f),
                cbZero.isChecked(), MAX_CHORD);
        refreshLibrary();
        setStatus(ok
                ? "已存档：" + name + "（" + song.notes.size() + " 个音符）\n"
                        + "以后在曲目列表点这一首，会直接读存档播放，不再解析原文件——"
                        + "原文件删了、移走了也能弹。"
                : "保存失败，请重试。");
    }

    /** Loads a remembered song straight from its snapshot, falling back to parsing if absent. */
    private void loadFromCache(Uri uri) {
        SongCache.Snapshot snap = SongCache.load(this, uri.toString());
        if (snap == null) {
            loadSong(uri, false);
            return;
        }
        lastUri = uri;
        song = snap.song;
        bpmOverride = snap.bpm;
        cbZero.setChecked(snap.octaveAware);
        if (snap.bpm > 0) etBpm.setText(String.valueOf(snap.bpm));

        int index = 0;
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(snap.key)) {
                index = i;
                break;
            }
        }
        updatingKey = true;
        spKey.setSelection(index);
        updatingKey = false;
        AppPrefs.setKey(this, KEYS[index]);

        tvFile.setText("文件：" + snap.song.name + "（存档）");
        showSong(false, snap.key);
        bpmOverride = 0;

        setStatus("已从存档载入：" + snap.song.name + "（" + snap.song.notes.size() + " 个音符，"
                + "未重新解析）\n调性 " + KEYS[index]
                + (snap.bpm > 0 ? "　BPM " + snap.bpm : "")
                + "　存档于 " + new java.text.SimpleDateFormat("MM-dd HH:mm",
                        java.util.Locale.getDefault()).format(new java.util.Date(snap.savedAt)));
    }

    // ------------------------------------------------------------------ playback

    private List<Playback.Tap> buildTaps(List<RawNote> mono) {
        List<Playback.Tap> taps = new ArrayList<Playback.Tap>();
        if (song == null || song.notes.isEmpty()) return taps;
        int root = AppPrefs.getRootPitchClass(this);
        float speed = parseFloat(etSpeed, 1.0f, 0.25f, 4.0f);
        boolean octaveAware = cbZero.isChecked();
        int tonic = PadMapper.tonicFor(lowestMidi(mono), root);

        // Planned from the raw notes, not the monophonic reduction: the instrument is polyphonic, so
        // notes that sound together become one multi-finger chord instead of being discarded.
        List<TapPlanner.Hit> hits = TapPlanner.plan(song.notes, root, tonic, octaveAware, speed, MAX_CHORD);
        for (int i = 0; i < hits.size(); i++) {
            TapPlanner.Hit hit = hits.get(i);
            float[] xs = new float[hit.slots.length];
            float[] ys = new float[hit.slots.length];
            int n = 0;
            for (int s = 0; s < hit.slots.length; s++) {
                float[] p = AppPrefs.getPad(this, hit.slots[s]);
                if (p == null) continue;
                xs[n] = p[0];
                ys[n] = p[1];
                n++;
            }
            if (n == 0) continue;
            if (n < xs.length) {
                xs = Arrays.copyOf(xs, n);
                ys = Arrays.copyOf(ys, n);
            }
            taps.add(new Playback.Tap(xs, ys, hit.atMs));
        }
        return taps;
    }

    /** Explains exactly which stage produced nothing, instead of blaming the key. */
    private String diagnoseEmpty(List<RawNote> mono) {
        StringBuilder sb = new StringBuilder("没有可弹奏的音符。\n");
        sb.append("原始音符 ").append(song == null ? 0 : song.notes.size())
                .append("　旋律音 ").append(mono.size())
                .append("　已校准 ").append(AppPrefs.calibratedCount(this))
                .append("/").append(AppPrefs.SLOTS).append(" 键\n");
        if (song != null && song.detail != null) {
            sb.append("[").append(song.detail).append("]\n");
        }
        if (song != null && song.notes.isEmpty()) {
            sb.append("→ 文件没解析出音符。");
            if ("音频转谱".equals(song.kind)) {
                sb.append("完整歌曲的音频里贝斯/鼓/和声会互相干扰，检测不到稳定单音。请改用 MIDI（.mid）文件。");
            }
        } else if (mono.isEmpty()) {
            sb.append("→ 旋律提取为空。");
        } else {
            sb.append("→ 音符有了但取不到琴键坐标，请重新校准琴键。");
        }
        return sb.toString();
    }

    /** Lowest pitch of the melody; anchors the tonic so the tune sits in the instrument's window. */
    private static int lowestMidi(List<RawNote> notes) {
        int low = 127;
        for (int i = 0; i < notes.size(); i++) {
            low = Math.min(low, notes.get(i).midi);
        }
        return low;
    }

    private void play() {
        if (!HandpanAccessibilityService.isReady()) {
            setStatus("无障碍服务未开启，无法自动弹奏。请点【开启无障碍服务】。");
            return;
        }
        if (!AppPrefs.hasCalibration(this)) {
            setStatus("琴键还没校准完整（" + AppPrefs.calibratedCount(this) + "/" + AppPrefs.SLOTS
                    + "），请先点【校准琴键】。");
            return;
        }
        if (song == null) {
            setStatus("还没导入音乐，请先点【选择音乐文件】。");
            return;
        }
        AppPrefs.setKey(this, KEYS[spKey.getSelectedItemPosition()]);
        AppPrefs.setBpm(this, (int) parseFloat(etBpm, 90f, 20f, 400f));
        AppPrefs.setSpeed(this, parseFloat(etSpeed, 1.0f, 0.25f, 4.0f));
        AppPrefs.setUseZeroPad(this, cbZero.isChecked());

        final List<RawNote> mono = SongLoader.monophonic(song.notes);
        final List<Playback.Tap> taps = buildTaps(mono);
        if (taps.isEmpty()) {
            setStatus(diagnoseEmpty(mono));
            return;
        }

        OverlayController.showStopButton(this, new Runnable() {
            @Override
            public void run() {
                stop();
            }
        });
        countdownLeft = COUNTDOWN_SEC;
        setStatus("准备演奏 " + taps.size() + " 个音，请立刻切到游戏！");
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (countdownLeft > 0) {
                    // Application context: the user is expected to leave this activity during the
                    // countdown, so the activity context may already be destroyed.
                    Toast.makeText(getApplicationContext(), countdownLeft + " 秒后开始演奏",
                            Toast.LENGTH_SHORT).show();
                    countdownLeft--;
                    ui.postDelayed(this, 1000L);
                } else {
                    setStatus("演奏中…（共 " + taps.size() + " 个音，点悬浮的【停止】可中断）");
                    Playback.start(taps, playbackListener());
                }
            }
        });
    }

    private Playback.Listener playbackListener() {
        return new Playback.Listener() {
            @Override
            public void onProgress(final int done, final int total) {
                // Only touch the UI occasionally: the callback runs on every scheduler tick.
                if (done % 20 != 0 && done != total) return;
                setStatus("演奏中… " + done + "/" + total);
            }

            @Override
            public void onFinish(final boolean completed) {
                OverlayController.hideStopButton();
                if (completed) {
                    setStatus("演奏完成 ✓");
                    Toast.makeText(getApplicationContext(), "演奏完成", Toast.LENGTH_SHORT).show();
                } else {
                    setStatus("已停止。");
                }
            }
        };
    }

    private void stop() {
        countdownLeft = 0;
        ui.removeCallbacksAndMessages(null);
        Playback.stop();
        OverlayController.hideStopButton();
        setStatus("已停止。");
    }

    // ------------------------------------------------------------------ util

    private static float parseFloat(EditText et, float def, float min, float max) {
        try {
            float v = Float.parseFloat(et.getText().toString().trim());
            if (v < min) v = min;
            if (v > max) v = max;
            return v;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private void setStatus(String s) {
        tvStatus.setText(s);
    }
}
