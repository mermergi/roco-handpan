package com.handpan.autoplay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Play screen: what will be played, how, and the start/stop controls.
 *
 * <p>Deliberately small. Importing, saving and the song list live in {@link SongsActivity};
 * permissions, calibration and playback parameters live in {@link SettingsActivity}. This screen
 * reads {@link Session} for the loaded song and {@link AppPrefs} for the parameters, so the three
 * screens share state without holding references to each other.
 */
public class MainActivity extends Activity {

    /** Countdown before the first tap, giving the user time to switch into the game. */
    private static final int COUNTDOWN_SEC = 5;

    private TextView tvSong;
    private TextView tvStatus;
    private TextView tvPreview;
    private Spinner spMode;
    private Spinner spKey;
    private Spinner spChord;
    private EditText etBpm;
    private EditText etSpeed;
    private CheckBox cbZero;
    private Button btnPlay;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    /** Session version this screen is showing, so onResume can notice changes made elsewhere. */
    private long shownVersion = -1;
    private int countdownLeft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle("手碟自动演奏");

        tvSong = (TextView) findViewById(R.id.tv_song);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvPreview = (TextView) findViewById(R.id.tv_preview);
        spMode = (Spinner) findViewById(R.id.sp_mode);
        btnPlay = (Button) findViewById(R.id.btn_play);

        ArrayAdapter<String> modes = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, AppPrefs.MODE_LABELS);
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMode.setAdapter(modes);
        spMode.setSelection(AppPrefs.getPlayMode(this));
        spMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                AppPrefs.setPlayMode(MainActivity.this, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        setupParams();

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

        findViewById(R.id.btn_songs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SongsActivity.class));
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play();
            }
        });
        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stop();
            }
        });

        if (AppPrefs.discardStaleCalibration(this)) {
            setStatus("琴键布局已更新，旧校准已作废，请到【设置】重新校准。");
        } else {
            setStatus("到【曲目库】选一首曲子，在【设置】里完成权限和校准，然后回这里开始演奏。");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Session.version() != shownVersion) {
            shownVersion = Session.version();
            syncParamsFromPrefs();
            refreshSong();
        }
    }

    // ------------------------------------------------------------------ display

    private void refreshSong() {
        if (Session.song() == null) {
            tvSong.setText("还没有选择曲目");
            tvPreview.setText("");
            return;
        }
        refreshSongTitle();
        renderPreview();
    }

    private void refreshSongTitle() {
        SongLoader.Song song = Session.song();
        if (song == null) return;
        tvSong.setText(song.name + "\n" + song.notes.size() + " 个音符 · "
                + song.lengthMs / 1000 + " 秒 · " + AppPrefs.getKey(this) + " 调 · 和弦上限 "
                + AppPrefs.getChordLimit(this));
    }

    /** Draws the pad sequence for the settings currently stored in {@link AppPrefs}. */
    private void renderPreview() {
        SongLoader.Song song = Session.song();
        if (song == null) return;
        List<RawNote> mono = SongLoader.monophonic(song.notes);
        int root = AppPrefs.getRootPitchClass(this);
        int tonic = PadMapper.tonicFor(lowestMidi(mono), root);
        int chord = AppPrefs.getChordLimit(this);

        List<TapPlanner.Hit> hits = TapPlanner.plan(song.notes, root, tonic,
                AppPrefs.getUseZeroPad(this), 1.0f, chord);
        StringBuilder preview = new StringBuilder();
        int maxVoices = 0;
        int shown = 0;
        for (int i = 0; i < hits.size() && shown < 60; i++) {
            int[] slots = hits.get(i).slots;
            if (slots.length > maxVoices) maxVoices = slots.length;
            for (int s = 0; s < slots.length; s++) {
                if (s > 0) preview.append('+');
                preview.append(PadMapper.shortOf(slots[s]));
            }
            preview.append(' ');
            shown++;
        }
        tvPreview.setText("【" + AppPrefs.getKey(this) + " 调】共 " + hits.size() + " 次点击，最多同时 "
                + maxVoices + " 个键（上限 " + chord + "）\n前 " + shown
                + " 次（' = 高八度，, = 低八度，+ = 同时按下）：\n" + preview
                + "\n\n改【设置】里的参数后回到这里会自动刷新。");
    }

    private static int lowestMidi(List<RawNote> notes) {
        int low = 127;
        for (int i = 0; i < notes.size(); i++) low = Math.min(low, notes.get(i).midi);
        return low;
    }

    // ------------------------------------------------------------------ playback

    private List<Playback.Tap> buildTaps() {
        List<Playback.Tap> taps = new ArrayList<Playback.Tap>();
        SongLoader.Song song = Session.song();
        if (song == null || song.notes.isEmpty()) return taps;
        int root = AppPrefs.getRootPitchClass(this);
        float speed = AppPrefs.getSpeed(this);
        if (speed <= 0f) speed = 1f;
        List<RawNote> mono = SongLoader.monophonic(song.notes);
        int tonic = PadMapper.tonicFor(lowestMidi(mono), root);

        List<TapPlanner.Hit> hits = TapPlanner.plan(song.notes, root, tonic,
                AppPrefs.getUseZeroPad(this), speed, AppPrefs.getChordLimit(this));
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
                xs = java.util.Arrays.copyOf(xs, n);
                ys = java.util.Arrays.copyOf(ys, n);
            }
            taps.add(new Playback.Tap(xs, ys, hit.atMs));
        }
        return taps;
    }

    private void play() {
        if (Session.song() == null) {
            setStatus("还没有选曲目。到【曲目库】选一首，或直接导入一个文件。");
            return;
        }
        if (!HandpanAccessibilityService.isReady()) {
            setStatus("无障碍服务未开启，无法自动弹奏。到【设置】里开启。");
            return;
        }
        if (!AppPrefs.hasCalibration(this)) {
            setStatus("琴键还没校准完整（" + AppPrefs.calibratedCount(this) + "/" + AppPrefs.SLOTS
                    + "）。到【设置】里点【校准琴键】。");
            return;
        }
        if (buildTaps().isEmpty()) {
            setStatus("没有可弹奏的音符：这首曲子解析出来是空的，换一首或重新解析。");
            return;
        }
        startCountdown();
    }

    /** Runs the countdown, then starts playback. Used both by 【开始演奏】 and chained playback. */
    private void startCountdown() {
        OverlayController.showStopButton(this, new Runnable() {
            @Override
            public void run() {
                stop();
            }
        });
        countdownLeft = COUNTDOWN_SEC;
        setStatus("准备演奏：" + currentName() + "　请切到游戏！");
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (countdownLeft > 0) {
                    Toast.makeText(getApplicationContext(), countdownLeft + " 秒后开始演奏",
                            Toast.LENGTH_SHORT).show();
                    countdownLeft--;
                    ui.postDelayed(this, 1000L);
                } else {
                    List<Playback.Tap> taps = buildTaps();
                    if (taps.isEmpty()) {
                        OverlayController.hideStopButton();
                        setStatus("没有可弹奏的音符。");
                        return;
                    }
                    setStatus("演奏中…（" + taps.size() + " 次点击，" + modeDescription()
                            + "，点悬浮【停止】可中断）");
                    Playback.start(taps, playbackListener());
                }
            }
        });
    }

    private Playback.Listener playbackListener() {
        return new Playback.Listener() {
            @Override
            public void onProgress(final int done, final int total) {
                if (done % 20 != 0 && done != total) return;
                setStatus("演奏中… " + done + "/" + total + "　" + modeDescription());
            }

            @Override
            public void onFinish(final boolean completed) {
                if (completed && AppPrefs.getPlayMode(MainActivity.this) != AppPrefs.MODE_SINGLE) {
                    advance();
                    return;
                }
                OverlayController.hideStopButton();
                setStatus(completed ? "演奏完成 ✓" : "已停止。");
                if (completed) {
                    Toast.makeText(getApplicationContext(), "演奏完成", Toast.LENGTH_SHORT).show();
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


    // ------------------------------------------------------------------ playback parameters

    /** Wires the parameter controls that sit right under the song title. */
    private void setupParams() {
        spKey = (Spinner) findViewById(R.id.sp_key);
        spChord = (Spinner) findViewById(R.id.sp_chord);
        etBpm = (EditText) findViewById(R.id.et_bpm);
        etSpeed = (EditText) findViewById(R.id.et_speed);
        cbZero = (CheckBox) findViewById(R.id.cb_zero);

        ArrayAdapter<String> keys = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, KeyDetector.KEYS);
        keys.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spKey.setAdapter(keys);

        List<String> chords = new ArrayList<String>();
        for (int i = 0; i < AppPrefs.MAX_CHORD_OPTIONS.length; i++) {
            int n = AppPrefs.MAX_CHORD_OPTIONS[i];
            chords.add(n + " 个键" + (n == AppPrefs.DEFAULT_CHORD ? "（默认）" : ""));
        }
        ArrayAdapter<String> chordAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, chords);
        chordAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spChord.setAdapter(chordAdapter);

        spKey.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                AppPrefs.setKey(MainActivity.this, KeyDetector.KEYS[position]);
                onParamsChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        spChord.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                AppPrefs.setChordLimit(MainActivity.this, AppPrefs.MAX_CHORD_OPTIONS[position]);
                onParamsChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        cbZero.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                AppPrefs.setUseZeroPad(MainActivity.this, isChecked);
                onParamsChanged();
            }
        });
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                AppPrefs.setBpm(MainActivity.this,
                        (int) number(etBpm.getText().toString(), 90f, 20f, 400f));
                AppPrefs.setSpeed(MainActivity.this,
                        number(etSpeed.getText().toString(), 1.0f, 0.25f, 4.0f));
                onParamsChanged();
            }
        };
        etBpm.addTextChangedListener(watcher);
        etSpeed.addTextChangedListener(watcher);

        syncParamsFromPrefs();
    }

    /** Pushes stored parameters into the controls (called on load, including snapshot restores). */
    private void syncParamsFromPrefs() {
        String key = AppPrefs.getKey(this);
        for (int i = 0; i < KeyDetector.KEYS.length; i++) {
            if (KeyDetector.KEYS[i].equals(key)) spKey.setSelection(i);
        }
        int chord = AppPrefs.getChordLimit(this);
        for (int i = 0; i < AppPrefs.MAX_CHORD_OPTIONS.length; i++) {
            if (AppPrefs.MAX_CHORD_OPTIONS[i] == chord) spChord.setSelection(i);
        }
        String bpm = String.valueOf(AppPrefs.getBpm(this));
        if (!bpm.equals(etBpm.getText().toString())) etBpm.setText(bpm);
        String speed = String.valueOf(AppPrefs.getSpeed(this));
        if (!speed.equals(etSpeed.getText().toString())) etSpeed.setText(speed);
        cbZero.setChecked(AppPrefs.getUseZeroPad(this));
    }

    /** The preview is the point of editing these in place, so redraw it on every change. */
    private void onParamsChanged() {
        refreshSongTitle();
        renderPreview();
    }

    private static float number(String text, float fallback, float min, float max) {
        try {
            float v = Float.parseFloat(text.trim());
            if (v < min) v = min;
            if (v > max) v = max;
            return v;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ parse and save

    /** Re-parses the current song's source file with the parameters now on screen. */
    private void manualParse() {
        final String uri = Session.uri();
        if (uri == null) {
            setStatus("当前曲目没有对应的原始文件。到【曲目库】导入一个文件。");
            return;
        }
        setStatus("正在重新解析 " + currentName() + " …");
        Loader.load(this, android.net.Uri.parse(uri), new Loader.Callback() {
            @Override
            public void onLoaded(android.net.Uri loaded, SongLoader.Song song) {
                Session.set(loaded.toString(), song);
                shownVersion = Session.version();
                refreshSong();
                setStatus("已按当前参数重新解析：" + song.notes.size() + " 个音符。");
            }

            @Override
            public void onError(android.net.Uri failed, Throwable error) {
                setStatus("重新解析失败：" + Loader.describe(error));
            }
        });
    }

    /** Stores the parsed notes plus the settings on screen, so this song never parses again. */
    private void manualSave() {
        SongLoader.Song song = Session.song();
        String uri = Session.uri();
        if (song == null || uri == null) {
            setStatus("还没有可保存的内容。到【曲目库】导入并解析一首曲子。");
            return;
        }
        boolean ok = SongCache.save(this, uri, song, AppPrefs.getKey(this),
                AppPrefs.getBpm(this), AppPrefs.getUseZeroPad(this), AppPrefs.getChordLimit(this));
        setStatus(ok
                ? "已存档：" + song.name + "（" + song.notes.size() + " 个音符）\n"
                        + "以后选这一首会直接读存档播放，不再解析原文件。"
                : "保存失败，请重试。");
    }

    // ------------------------------------------------------------------ sequence / random

    /** Picks the next song after a finished run, when the mode asks for it. */
    private void advance() {
        int mode = AppPrefs.getPlayMode(this);
        OverlayController.hideStopButton();
        List<SongLibrary.Entry> entries = SongLibrary.list(this);
        if (entries.size() < 2) {
            setStatus("演奏完成。曲目库里只有 " + entries.size() + " 首，无法继续"
                    + (mode == AppPrefs.MODE_RANDOM ? "随机" : "顺序") + "演奏。");
            return;
        }
        String current = Session.uri();
        int index = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).uri.equals(current)) {
                index = i;
                break;
            }
        }
        int next;
        if (mode == AppPrefs.MODE_RANDOM) {
            next = index;
            while (next == index) next = random.nextInt(entries.size());
        } else {
            next = (index + 1) % entries.size();
        }
        SongLibrary.Entry entry = entries.get(next);
        setStatus((mode == AppPrefs.MODE_RANDOM ? "随机" : "顺序")
                + "演奏下一首：" + entry.name + " …");
        loadForChain(entry);
    }

    /** Loads a song for chained playback, preferring its snapshot, then starts the countdown. */
    private void loadForChain(final SongLibrary.Entry entry) {
        SongCache.Snapshot snapshot = SongCache.load(this, entry.uri);
        if (snapshot != null) {
            applySnapshot(entry.uri, snapshot);
            shownVersion = Session.version();
            refreshSong();
            setStatus("下一首（来自存档）：" + snapshot.song.name + "　准备演奏…");
            startCountdown();
            return;
        }
        setStatus("下一首：" + entry.name + " 还没存档，正在解析…（在【曲目库】按【手动保存】可免去这一步）");
        Loader.load(this, android.net.Uri.parse(entry.uri), new Loader.Callback() {
            @Override
            public void onLoaded(android.net.Uri uri, SongLoader.Song song) {
                Session.set(uri.toString(), song);
                shownVersion = Session.version();
                refreshSong();
                setStatus("下一首：" + song.name + "　准备演奏…");
                startCountdown();
            }

            @Override
            public void onError(android.net.Uri uri, Throwable error) {
                OverlayController.hideStopButton();
                setStatus("下一首载入失败：" + entry.name + " —— " + Loader.describe(error));
            }
        });
    }

    private void applySnapshot(String uri, SongCache.Snapshot snapshot) {
        Session.set(uri, snapshot.song);
        AppPrefs.setKey(this, snapshot.key);
        if (snapshot.bpm > 0) AppPrefs.setBpm(this, snapshot.bpm);
        AppPrefs.setUseZeroPad(this, snapshot.octaveAware);
        AppPrefs.setChordLimit(this, snapshot.maxChord);
    }

    // ------------------------------------------------------------------ util

    private String currentName() {
        SongLoader.Song song = Session.song();
        return song == null ? "(未命名)" : song.name;
    }

    private String modeDescription() {
        int mode = AppPrefs.getPlayMode(this);
        if (mode == AppPrefs.MODE_RANDOM) return "随机演奏";
        if (mode == AppPrefs.MODE_SEQUENCE) return "顺序演奏";
        return "单曲演奏";
    }

    private void setStatus(String message) {
        tvStatus.setText(message);
    }
}
