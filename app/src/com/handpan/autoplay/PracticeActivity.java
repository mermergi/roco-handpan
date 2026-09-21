package com.handpan.autoplay;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Practice and recording, both on the app's own nine-pad board.
 *
 * <p>练习模式 turns a song (or a previous recording) into a chart of pad presses and scores the
 * player's taps against it. 录制模式 just captures what the player presses. Neither produces sound:
 * the instrument's audio lives in the game, and the point here is timing.
 */
public class PracticeActivity extends Activity {

    /** How long before a press its timing ring appears. */
    private static final long LEAD_MS = 1400L;

    /** Board refresh interval; ~60fps is enough for a contracting ring. */
    private static final long TICK_MS = 16L;

    private PadBoardView board;
    private TextView tvScore;
    private TextView tvStatus;
    private LinearLayout rowSource;
    private Spinner spSource;
    private Button btnStart;
    private Button btnStop;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean recordingMode;
    private boolean running;

    private List<SongLibrary.Entry> sources = new ArrayList<SongLibrary.Entry>();
    private PracticeSession session;
    private long runStart;
    private int strays;

    private final List<RecordingCodec.Hit> recorded = new ArrayList<RecordingCodec.Hit>();
    private long recordStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_practice);
        setTitle("练习 / 录制");

        board = (PadBoardView) findViewById(R.id.board);
        tvScore = (TextView) findViewById(R.id.tv_score);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        rowSource = (LinearLayout) findViewById(R.id.row_source);
        spSource = (Spinner) findViewById(R.id.sp_source);
        btnStart = (Button) findViewById(R.id.btn_start);
        btnStop = (Button) findViewById(R.id.btn_stop);

        board.setOnPadListener(new PadBoardView.OnPadListener() {
            @Override
            public void onPad(int slot) {
                onPadPressed(slot);
            }
        });

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
                start();
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopAndSave();
            }
        });

        setRecordingMode(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSources();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------ setup

    private void setRecordingMode(boolean record) {
        if (running) stopAndSave();
        recordingMode = record;
        rowSource.setVisibility(record ? View.GONE : View.VISIBLE);
        btnStart.setText(record ? "开始录制" : "开始练习");
        btnStop.setText(record ? "停止并保存" : "停止");
        updateScore();
        tvStatus.setText(record
                ? "录制模式：点【开始录制】，然后按你自己的节奏点这 9 个键。"
                        + "按【停止并保存】会把这次弹奏存成录音，之后可以在曲目库里直接弹进游戏。"
                : "练习模式：选一首曲子，按【开始练习】。琴键上会出现收拢的计时圈，"
                        + "圈收到底的时候按下去就是准的。");
    }

    private void refreshSources() {
        sources = SongLibrary.list(this);
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < sources.size(); i++) {
            SongLibrary.Entry entry = sources.get(i);
            boolean isRecording = RecordingStore.isRecording(entry.uri);
            names.add((isRecording ? "【录音】" : "") + entry.name);
        }
        if (names.isEmpty()) {
            names.add("（曲目库是空的）");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSource.setAdapter(adapter);
    }

    // ------------------------------------------------------------------ run control

    private void start() {
        if (recordingMode) {
            recorded.clear();
            recordStart = SystemClock.uptimeMillis();
            running = true;
            updateScore();
            tvStatus.setText("录制中…按你的节奏点琴键，按【停止并保存】结束。");
            return;
        }
        if (sources.isEmpty()) {
            tvStatus.setText("曲目库是空的。先到【曲目库】导入一首曲子，或先录一段。");
            return;
        }
        int index = spSource.getSelectedItemPosition();
        if (index < 0 || index >= sources.size()) index = 0;
        SongLibrary.Entry entry = sources.get(index);

        if (RecordingStore.isRecording(entry.uri)) {
            RecordingCodec.Data data = RecordingStore.load(this, RecordingStore.idOf(entry.uri));
            if (data == null) {
                tvStatus.setText("这条录音读不出来了。");
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
        tvStatus.setText("正在解析 " + entry.name + " …（未存档，需要几秒）");
        Loader.load(this, Uri.parse(entry.uri), new Loader.Callback() {
            @Override
            public void onLoaded(Uri uri, SongLoader.Song song) {
                beginPractice(chartOfNotes(song), song.name);
            }

            @Override
            public void onError(Uri uri, Throwable error) {
                tvStatus.setText("解析失败：" + Loader.describe(error));
            }
        });
    }

    private void beginPractice(List<RecordingCodec.Hit> chart, String name) {
        if (chart.isEmpty()) {
            tvStatus.setText("这首谱面是空的，没什么可练的。");
            return;
        }
        long[] times = new long[chart.size()];
        int[] slots = new int[chart.size()];
        for (int i = 0; i < chart.size(); i++) {
            times[i] = chart.get(i).atMs;
            slots[i] = chart.get(i).slot;
        }
        session = new PracticeSession(times, slots);
        strays = 0;
        runStart = SystemClock.uptimeMillis();
        running = true;
        updateScore();
        tvStatus.setText("练习中：" + name + "（共 " + chart.size() + " 次按键）");
        ui.postDelayed(tick, TICK_MS);
    }

    private void stopAndSave() {
        running = false;
        ui.removeCallbacks(tick);
        board.setDue(new int[0], new float[0]);

        if (recordingMode) {
            if (recorded.isEmpty()) {
                tvStatus.setText("这次没有记录到任何按键。");
                return;
            }
            long savedAt = System.currentTimeMillis();
            String name = "录音 " + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(savedAt));
            String id = RecordingStore.save(this, name, recorded, savedAt);
            if (id == null) {
                tvStatus.setText("保存失败。");
                return;
            }
            String uri = RecordingStore.SCHEME + id;
            SongLibrary.add(this, uri, name, "录音", AppPrefs.getKey(this), recorded.size(),
                    Math.max(1, recorded.get(recorded.size() - 1).atMs) / 1000);
            tvStatus.setText("已保存：" + name + "（" + recorded.size() + " 次按键）\n"
                    + "它已经进了曲目库，回首页选中它就能弹进游戏。");
            recorded.clear();
            updateScore();
            return;
        }

        if (session != null) {
            session.consumeMisses(Long.MAX_VALUE / 2);
            updateScore();
            tvStatus.setText(summary());
            session = null;
        }
    }

    private String summary() {
        return "练习结束\n"
                + "PERFECT " + session.perfect() + "　GOOD " + session.good()
                + "　OK " + session.ok() + "　MISS " + session.miss()
                + "　空按 " + strays + "\n准确率 " + session.accuracyPercent() + "%";
    }

    // ------------------------------------------------------------------ runtime

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || session == null) return;
            long now = SystemClock.uptimeMillis() - runStart;

            int missed = session.consumeMisses(now);
            if (missed > 0) updateScore();

            int[] due = session.dueSlots(now, LEAD_MS);
            float[] progress = new float[due.length];
            for (int i = 0; i < due.length; i++) {
                // Find this pad's next press time to size its ring.
                long best = Long.MAX_VALUE;
                for (int k = 0; k < session.total(); k++) {
                    long time = session.timeAt(k);
                    if (time >= now && session.slotAt(k) == due[i] && time < best) best = time;
                }
                float left = best == Long.MAX_VALUE ? 1f : (best - now) / (float) LEAD_MS;
                progress[i] = Math.max(0f, Math.min(1f, left));
            }
            board.setDue(due, progress);

            if (session.isFinished()) {
                running = false;
                board.setDue(new int[0], new float[0]);
                updateScore();
                tvStatus.setText(summary());
                session = null;
                return;
            }
            ui.postDelayed(this, TICK_MS);
        }
    };

    private void onPadPressed(int slot) {
        long now = recordingMode ? SystemClock.uptimeMillis() - recordStart
                : SystemClock.uptimeMillis() - runStart;

        if (recordingMode) {
            if (!running) {
                tvStatus.setText("先按【开始录制】。");
                return;
            }
            recorded.add(new RecordingCodec.Hit(slot, Math.max(0, now)));
            board.flash(slot, true);
            updateScore();
            return;
        }

        if (!running || session == null) return;
        int grade = session.tap(slot, now);
        board.flash(slot, grade != PracticeSession.STRAY);
        if (grade == PracticeSession.STRAY) strays++;
        updateScore();
    }

    private void updateScore() {
        if (recordingMode) {
            tvScore.setText("已记录 " + recorded.size() + " 次按键");
            return;
        }
        if (session == null) {
            tvScore.setText("准备开始");
            return;
        }
        tvScore.setText("PERFECT " + session.perfect() + "　GOOD " + session.good()
                + "　OK " + session.ok() + "　MISS " + session.miss()
                + "　空按 " + strays + "　准确率 " + session.accuracyPercent() + "%");
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
            for (int s = 0; s < hit.slots.length; s++) out.add(new RecordingCodec.Hit(hit.slots[s], at));
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
}
