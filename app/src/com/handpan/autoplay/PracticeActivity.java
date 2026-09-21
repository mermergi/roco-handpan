package com.handpan.autoplay;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Control panel for 练习模式 and 录制模式.
 *
 * <p>The playing itself happens <em>in the game</em>, because that is where the sound comes from.
 * This screen only picks the mode and the chart, then raises {@link GameOverlay} over the game and
 * steps aside. While a run is active the overlay shows the timing rings and the score; the phone can
 * stay in the game the whole time.
 */
public class PracticeActivity extends Activity {

    private TextView tvStatus;
    private TextView tvHelp;
    private LinearLayout rowSource;
    private Spinner spSource;
    private View btnStart;
    private CheckBox cbTakeover;
    private Spinner spSpeed;

    private boolean recordingMode;
    private List<SongLibrary.Entry> sources = new ArrayList<SongLibrary.Entry>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_practice);
        setTitle("练习 / 录制");

        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvHelp = (TextView) findViewById(R.id.tv_help);
        rowSource = (LinearLayout) findViewById(R.id.row_source);
        spSource = (Spinner) findViewById(R.id.sp_source);
        btnStart = findViewById(R.id.btn_start);
        cbTakeover = (CheckBox) findViewById(R.id.cb_takeover);
        spSpeed = (Spinner) findViewById(R.id.sp_speed);
        setupSpeedSpinner();

        findViewById(R.id.btn_mode_practice).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setRecordingMode(false);
            }
        });
        findViewById(R.id.btn_mode_record).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setRecordingMode(true);
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startRun();
            }
        });
        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (GameOverlay.isRunning()) {
                    GameOverlay.stop();
                    status("已结束。");
                } else {
                    status("当前没有正在进行。");
                }
            }
        });

        setRecordingMode(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSources();
    }

    private void setRecordingMode(boolean record) {
        recordingMode = record;
        rowSource.setVisibility(record ? View.GONE : View.VISIBLE);
        findViewById(R.id.row_speed).setVisibility(record ? View.GONE : View.VISIBLE);
        btnStart.setEnabled(true);
        // Recording has no alternative: without intercepting touches there is no way to know which
        // pad was pressed. Practice can run as guidance only.
        cbTakeover.setVisibility(record ? View.GONE : View.VISIBLE);
        if (!record) {
            // On by default: without it the app cannot see a single press, so neither the score nor
            // the note lane can consume anything - items would only ever time out.
            cbTakeover.setChecked(true);
        }
        selectCurrentSpeed();
        tvHelp.setText(record
                ? "录制模式\n\n"
                + "1. 点【开始（然后切到游戏）】\n"
                + "2. 手机会切回游戏，你在游戏里正常弹\n"
                + "3. 屏幕角落有【结束】按钮，弹完点它\n"
                + "4. 这次弹奏会存成录音，之后能在曲目库里直接弹进游戏\n\n"
                + "只记录你按了哪个琴键、什么时候按的，不录声音。"
                : "练习模式\n\n"
                + "1. 选一首曲子，点【开始（然后切到游戏）】\n"
                + "2. 手机会切回游戏，要按的琴键上会出现**收拢的计时圈**\n"
                + "3. 圈收到底的那一下按下去\n"
                + "4. 角落显示实时分数，点【结束】看总评\n"
                + "5. 练太快要放慢？回来把【练习倍速】调到 0.5× 再开始\n\n"
                + "【接管触摸以判分】的取舍：\n"
                + "· 勾上：能判分。你的每一下由悬浮层转发给游戏（声音会晚几十毫秒），\n"
                + "  转发的一瞬间悬浮层要让开，那一瞬间的按键记不到。\n"
                + "· 不勾：只画提示，游戏收到的触摸和你没装 APP 时完全一样，但不算分。\n\n"
                + "两种都要先在【设置】里校准 9 个琴键；勾选时还要开无障碍服务。");
    }

    /**
     * Practice tempo, chosen here rather than on the floating bar.
     *
     * <p>Shared with the play screen's 速度 setting, so there is one tempo in the app instead of two
     * competing ones. A spinner picks any value directly - the overlay's cycling button could only
     * step upward, which made slowing down awkward.
     */
    private void setupSpeedSpinner() {
        List<String> labels = new ArrayList<String>();
        for (int i = 0; i < SpeedClock.SPEEDS.length; i++) {
            labels.add(formatSpeed(SpeedClock.SPEEDS[i]));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSpeed.setAdapter(adapter);
        selectCurrentSpeed();
        spSpeed.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position,
                                       long id) {
                if (position >= 0 && position < SpeedClock.SPEEDS.length) {
                    AppPrefs.setSpeed(PracticeActivity.this, SpeedClock.SPEEDS[position]);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void selectCurrentSpeed() {
        float current = AppPrefs.getSpeed(this);
        int best = 2; // 1.0x
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < SpeedClock.SPEEDS.length; i++) {
            float delta = Math.abs(SpeedClock.SPEEDS[i] - current);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        spSpeed.setSelection(best);
    }

    private static String formatSpeed(float value) {
        return (value == Math.round(value) ? String.valueOf((int) value) : String.valueOf(value)) + "×";
    }

    private float chosenSpeed() {
        int position = spSpeed.getSelectedItemPosition();
        if (position < 0 || position >= SpeedClock.SPEEDS.length) return AppPrefs.getSpeed(this);
        return SpeedClock.SPEEDS[position];
    }

    private void refreshSources() {
        sources = SongLibrary.list(this);
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < sources.size(); i++) {
            SongLibrary.Entry entry = sources.get(i);
            names.add((RecordingStore.isRecording(entry.uri) ? "【录音】" : "") + entry.name);
        }
        if (names.isEmpty()) names.add("（曲目库是空的）");
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSource.setAdapter(adapter);
    }

    // ------------------------------------------------------------------ starting a run

    private void startRun() {
        if (!OverlayController.canDraw(this)) {
            status("需要先授予悬浮窗权限，练习和录制都要把悬浮层盖在游戏上。");
            OverlayController.requestPermission(this);
            return;
        }
        if (!HandpanAccessibilityService.isReady()) {
            status("需要先开启无障碍服务——你的每一下都要先被它转发给游戏，游戏才会出声。");
            return;
        }
        if (!AppPrefs.hasCalibration(this)) {
            status("琴键还没校准完整（" + AppPrefs.calibratedCount(this) + "/" + AppPrefs.SLOTS
                    + "）。到【设置】里点【校准琴键】。");
            return;
        }

        if (recordingMode) {
            status("录制中…切到游戏开始弹吧。");
            GameOverlay.start(this, GameOverlay.MODE_RECORD, null, callback, true, 1.0f);
            goToGame();
            return;
        }

        if (sources.isEmpty()) {
            status("曲目库是空的。先到【曲目库】导入一首曲子，或先录一段。");
            return;
        }
        int index = spSource.getSelectedItemPosition();
        if (index < 0 || index >= sources.size()) index = 0;
        SongLibrary.Entry entry = sources.get(index);

        if (RecordingStore.isRecording(entry.uri)) {
            RecordingCodec.Data data = RecordingStore.load(this, RecordingStore.idOf(entry.uri));
            if (data == null) {
                status("这条录音读不出来了。");
                return;
            }
            beginPractice(chartOf(data.hits), entry.name);
            return;
        }
        SongCache.Snapshot snapshot = SongCache.load(this, entry.uri);
        if (snapshot != null) {
            beginPractice(chartOfNotes(snapshot.song), entry.name);
            return;
        }
        status("正在解析 " + entry.name + " …（未存档，需要几秒）");
        Loader.load(this, Uri.parse(entry.uri), new Loader.Callback() {
            @Override
            public void onLoaded(Uri uri, SongLoader.Song song) {
                beginPractice(chartOfNotes(song), song.name);
            }

            @Override
            public void onError(Uri uri, Throwable error) {
                status("解析失败：" + Loader.describe(error));
            }
        });
    }

    private void beginPractice(List<RecordingCodec.Hit> chart, String name) {
        if (chart.isEmpty()) {
            status("这首谱面是空的，没什么可练的。");
            return;
        }
        long[] times = new long[chart.size()];
        int[] slots = new int[chart.size()];
        for (int i = 0; i < chart.size(); i++) {
            times[i] = chart.get(i).atMs;
            slots[i] = chart.get(i).slot;
        }
        PracticeSession session = new PracticeSession(times, slots);
        boolean takeOver = cbTakeover.isChecked();
        status("练习中：" + name + "（共 " + chart.size() + " 次按键）"
                + (takeOver ? "" : "　仅提示，不判分"));
        GameOverlay.start(this, GameOverlay.MODE_PRACTICE, session, callback, takeOver,
                chosenSpeed());
        goToGame();
    }

    private void goToGame() {
        Toast.makeText(getApplicationContext(),
                "切到游戏开始吧。角落有【结束】按钮。", Toast.LENGTH_LONG).show();
        // Send this task to the back so the user lands back in the game with one tap.
        moveTaskToBack(true);
    }

    private final GameOverlay.Callback callback = new GameOverlay.Callback() {
        @Override
        public void onFinished(String summary, int recordedHits) {
            if (recordingMode && recordedHits > 0) {
                long savedAt = System.currentTimeMillis();
                String name = GameOverlay.defaultRecordingName();
                String id = RecordingStore.save(PracticeActivity.this, name,
                        GameOverlay.recordedHits(), savedAt);
                if (id != null) {
                    SongLibrary.add(PracticeActivity.this, RecordingStore.SCHEME + id, name, "录音",
                            AppPrefs.getKey(PracticeActivity.this), recordedHits,
                            Math.max(1, estimateSeconds()));
                    refreshSources();
                    status(summary + "\n已保存为「" + name + "」，在曲目库里可以直接弹进游戏。");
                    return;
                }
            } else if (recordingMode) {
                status("这次没有记录到任何按键（是不是没点到琴键上？）。");
                return;
            }
            status(summary);
        }
    };

    private long estimateSeconds() {
        List<RecordingCodec.Hit> hits = GameOverlay.recordedHits();
        long end = 0;
        for (int i = 0; i < hits.size(); i++) end = Math.max(end, hits.get(i).atMs);
        return end / 1000;
    }

    // ------------------------------------------------------------------ charts

    /** Flattens a song into individual pad presses, the same way playback would. */
    private List<RecordingCodec.Hit> chartOfNotes(SongLoader.Song song) {
        List<RawNote> mono = SongLoader.monophonic(song.notes);
        int root = AppPrefs.getRootPitchClass(this);
        int tonic = PadMapper.tonicFor(lowestMidi(mono), root);
        List<TapPlanner.Hit> hits = TapPlanner.plan(song.notes, root, tonic,
                AppPrefs.getUseZeroPad(this), 1.0f, AppPrefs.getChordLimit(this));
        List<RecordingCodec.Hit> out = new ArrayList<RecordingCodec.Hit>();
        for (int i = 0; i < hits.size(); i++) {
            TapPlanner.Hit hit = hits.get(i);
            long at = Math.max(0, hit.atMs - TapPlanner.LEAD_IN_MS);
            for (int s = 0; s < hit.slots.length; s++) {
                out.add(new RecordingCodec.Hit(hit.slots[s], at));
            }
        }
        return out;
    }

    private List<RecordingCodec.Hit> chartOf(List<RecordingCodec.Hit> hits) {
        return new ArrayList<RecordingCodec.Hit>(hits);
    }

    private static int lowestMidi(List<RawNote> notes) {
        int low = 127;
        for (int i = 0; i < notes.size(); i++) low = Math.min(low, notes.get(i).midi);
        return low;
    }

    private void status(String message) {
        tvStatus.setText(message);
    }
}
