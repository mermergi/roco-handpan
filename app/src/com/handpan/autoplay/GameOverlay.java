package com.handpan.autoplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The practice/recording overlay, drawn on top of the game.
 *
 * <p>Play happens in the game - that is where the sound comes from - so the app cannot simply show
 * its own board. Instead this covers the screen with a transparent layer that:
 *
 * <ol>
 *   <li>catches the finger down, instantly forwarding the touch back through the accessibility
 *       service so the game plays its note as usual;</li>
 *   <li>turns that touch into a recorded press, or judges it against the chart;</li>
 *   <li>draws the timing rings and hit feedback over the game's own pads.</li>
 * </ol>
 *
 * <p>Forwarding means the game still sounds, at the cost of the gesture round trip (tens of
 * milliseconds). Judging uses the touch's own timestamp, so the score is not affected by that delay.
 * The trade-off is that this layer consumes gestures it forwards: fine for a fixed pad board, not
 * appropriate for a screen you also need to drag around.
 */
public final class GameOverlay {

    public static final int MODE_RECORD = 0;
    public static final int MODE_PRACTICE = 1;

    /** How far from a pad centre a touch still counts as that pad. */
    private static final float REACH_DP = 46f;

    private static final long LEAD_MS = 1500L;
    private static final long TICK_MS = 16L;

    public interface Callback {
        void onFinished(String summary, int recordedHits);
    }

    private static View sRoot;
    private static BoardView sBoard;
    private static TextView sStatus;
    private static WindowManager sWm;
    private static Callback sCallback;
    private static PracticeSession sSession;
    private static int sMode;
    private static long sStart;
    private static final List<RecordingCodec.Hit> sRecorded = new ArrayList<RecordingCodec.Hit>();
    private static int sStrays;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private GameOverlay() {}

    public static boolean isRunning() {
        return sRoot != null;
    }

    public static void start(Context context, int mode, PracticeSession session, Callback callback) {
        stop();
        final Context ctx = context.getApplicationContext();
        if (!OverlayController.canDraw(ctx)) {
            if (callback != null) callback.onFinished("需要先授予悬浮窗权限。", 0);
            return;
        }
        sMode = mode;
        sSession = session;
        sCallback = callback;
        sRecorded.clear();
        sStrays = 0;
        sStart = SystemClock.uptimeMillis();

        FrameLayout root = new FrameLayout(ctx);
        sBoard = new BoardView(ctx);
        root.addView(sBoard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // A single small stop control, out of the way of the pads.
        TextView stop = new TextView(ctx);
        stop.setText("■ 结束");
        stop.setTextColor(Color.WHITE);
        stop.setTextSize(14f);
        stop.setBackgroundResource(R.drawable.overlay_button);
        stop.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 9), Ui.dp(ctx, 14), Ui.dp(ctx, 9));
        FrameLayout.LayoutParams stopParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        stopParams.gravity = Gravity.TOP | Gravity.END;
        stopParams.setMargins(0, Ui.dp(ctx, 36), Ui.dp(ctx, 10), 0);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish(false);
            }
        });
        root.addView(stop, stopParams);

        sStatus = new TextView(ctx);
        sStatus.setTextColor(Color.WHITE);
        sStatus.setTextSize(14f);
        sStatus.setBackgroundColor(0xAA000000);
        sStatus.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        statusParams.gravity = Gravity.TOP | Gravity.START;
        statusParams.setMargins(Ui.dp(ctx, 10), Ui.dp(ctx, 36), 0, 0);
        root.addView(sStatus, statusParams);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        sRoot = root;
        sWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        try {
            sWm.addView(root, params);
        } catch (RuntimeException e) {
            sRoot = null;
            if (callback != null) callback.onFinished("无法显示悬浮层：" + e.getMessage(), 0);
            return;
        }
        updateStatus();
        HANDLER.postDelayed(TICK, TICK_MS);
    }

    public static void stop() {
        HANDLER.removeCallbacks(TICK);
        View root = sRoot;
        sRoot = null;
        sBoard = null;
        sStatus = null;
        if (root != null && sWm != null) {
            try {
                sWm.removeView(root);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void finish(boolean completed) {
        int recorded = sRecorded.size();
        String summary = summaryText();
        Callback callback = sCallback;
        sCallback = null;
        stop();
        if (callback != null) callback.onFinished(summary, recorded);
    }

    private static String summaryText() {
        if (sMode == MODE_RECORD) {
            return "录制结束，共记录 " + sRecorded.size() + " 次按键。";
        }
        if (sSession == null) return "练习结束。";
        return "PERFECT " + sSession.perfect() + "　GOOD " + sSession.good()
                + "　OK " + sSession.ok() + "　MISS " + sSession.miss()
                + "　空按 " + sStrays + "　准确率 " + sSession.accuracyPercent() + "%";
    }

    /** Pads currently in the store, ready for saving a recording. */
    public static List<RecordingCodec.Hit> recordedHits() {
        return new ArrayList<RecordingCodec.Hit>(sRecorded);
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (sBoard == null) return;
            if (sMode == MODE_PRACTICE && sSession != null) {
                long now = SystemClock.uptimeMillis() - sStart;
                if (sSession.consumeMisses(now) > 0) updateStatus();
                int[] due = sSession.dueSlots(now, LEAD_MS);
                float[] progress = new float[due.length];
                for (int i = 0; i < due.length; i++) {
                    long best = Long.MAX_VALUE;
                    for (int k = 0; k < sSession.total(); k++) {
                        long time = sSession.timeAt(k);
                        if (time >= now && sSession.slotAt(k) == due[i] && time < best) best = time;
                    }
                    float left = best == Long.MAX_VALUE ? 1f : (best - now) / (float) LEAD_MS;
                    progress[i] = Math.max(0f, Math.min(1f, left));
                }
                sBoard.setDue(due, progress);
                if (sSession.isFinished()) {
                    finish(true);
                    return;
                }
            }
            updateStatus();
            HANDLER.postDelayed(this, TICK_MS);
        }
    };

    private static void updateStatus() {
        if (sStatus == null) return;
        if (sMode == MODE_RECORD) {
            sStatus.setText("录制中　已记录 " + sRecorded.size() + " 次按键");
            return;
        }
        if (sSession == null) {
            sStatus.setText("练习中");
            return;
        }
        sStatus.setText("P " + sSession.perfect() + "　G " + sSession.good()
                + "　OK " + sSession.ok() + "　M " + sSession.miss() + "　" 
                + sSession.accuracyPercent() + "%");
    }

    /** Pad coordinates read from the calibration store. */
    private static float[][] pads(Context ctx) {
        float[][] pads = new float[AppPrefs.SLOTS][];
        for (int slot = 0; slot < AppPrefs.SLOTS; slot++) pads[slot] = AppPrefs.getPad(ctx, slot);
        return pads;
    }

    // ------------------------------------------------------------------ the touch surface

    private static final class BoardView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flash = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final float[][] pads;
        private final float reach;

        private int[] due = new int[0];
        private float[] progress = new float[0];
        private final int[] flashColor = new int[AppPrefs.SLOTS];
        private final long[] flashUntil = new long[AppPrefs.SLOTS];

        BoardView(Context ctx) {
            super(ctx);
            pads = pads(ctx);
            reach = Ui.dp(ctx, (int) REACH_DP);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(ctx, 4));
            flash.setStyle(Paint.Style.STROKE);
            flash.setStrokeWidth(Ui.dp(ctx, 5));
            label.setColor(Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(Ui.dp(ctx, 12));
            label.setTypeface(Typeface.DEFAULT_BOLD);
        }

        void setDue(int[] slots, float[] progressValues) {
            due = slots == null ? new int[0] : slots;
            progress = progressValues == null ? new float[0] : progressValues;
            invalidate();
        }

        void flash(int slot, boolean hit) {
            if (slot < 0 || slot >= AppPrefs.SLOTS) return;
            flashColor[slot] = hit ? 0xFF66BB6A : 0xFFEF5350;
            flashUntil[slot] = System.currentTimeMillis() + 200L;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long now = System.currentTimeMillis();
            // Draw only around the pads; the rest stays transparent so the game is visible.
            for (int i = 0; i < due.length; i++) {
                int slot = due[i];
                if (slot < 0 || slot >= pads.length || pads[slot] == null) continue;
                float p = i < progress.length ? Math.max(0f, Math.min(1f, progress[i])) : 0f;
                float r = reach * (1f + 0.9f * p);
                ring.setColor(p < 0.22f ? 0xFFEF5350 : 0xFF42A5F5);
                canvas.drawArc(new RectF(pads[slot][0] - r, pads[slot][1] - r,
                        pads[slot][0] + r, pads[slot][1] + r), 0f, 360f, false, ring);
            }
            for (int slot = 0; slot < pads.length; slot++) {
                if (pads[slot] == null || now >= flashUntil[slot]) continue;
                flash.setColor(flashColor[slot]);
                canvas.drawCircle(pads[slot][0], pads[slot][1], reach * 0.55f, flash);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) {
                return true; // consume everything so the game only sees what we forward
            }
            final int index = event.getActionIndex();
            final float x = event.getX(index);
            final float y = event.getY(index);

            // Forward first: the note should sound as close to the real touch as possible.
            HandpanAccessibilityService service = HandpanAccessibilityService.get();
            if (service != null) service.tapAll(new float[]{x}, new float[]{y});

            int slot = PadHitTester.slotAt(pads, x, y, reach);
            if (slot < 0) {
                if (sMode == MODE_PRACTICE) sStrays++;
                updateStatus();
                return true;
            }

            long now = SystemClock.uptimeMillis() - sStart;
            if (sMode == MODE_RECORD) {
                sRecorded.add(new RecordingCodec.Hit(slot, Math.max(0, now)));
                flash(slot, true);
            } else if (sSession != null) {
                int grade = sSession.tap(slot, now);
                if (grade == PracticeSession.STRAY) sStrays++;
                flash(slot, grade != PracticeSession.STRAY);
            }
            updateStatus();
            return true;
        }
    }

    /** Timestamped name for a recording, e.g. "录音 09-21 19:04". */
    public static String defaultRecordingName() {
        return "录音 " + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date());
    }
}
