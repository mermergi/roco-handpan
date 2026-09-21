package com.handpan.autoplay;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
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
        btnStart.setEnabled(true);
        tvHelp.setText(record
                ? "录制模式\n\n"
                + "1. 点【开始（然后切到游戏）】\n"
                + "2. 手机会切回游戏，你在游戏里正常弹\n"
                + "3. 屏幕角落有【结束】按钮，弹完点它\n"
                + "4. 这次弹奏会存成录音，之后能在曲目库里直接弹进游戏\n\n"
                + "只记录你按了哪个琴键、什么时候按的，不录声音。"
                : "练习模式\n\n"
                + "1. 选一首曲子，点【开始（然后切到游戏）】\n"
                + "2. 手机会切回游戏，你要按的琴键上会出现**收拢的计时圈**\n"
                + "3. 圈收到底的那一下按下去，游戏照常出声，同时给你计分\n"
                + "4. 屏幕角落显示实时分数，点【结束】看总评\n\n"
                + "需要先在【设置】里校准过 9 个琴键，并开启无障碍服务。");
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
            GameOverlay.start(this, GameOverlay.MODE_RECORD, null, callback);
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
        status("练习中：" + name + "（共 " + chart.size() + " 次按键）");
        GameOverlay.start(this, GameOverlay.MODE_PRACTICE, session, callback);
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
