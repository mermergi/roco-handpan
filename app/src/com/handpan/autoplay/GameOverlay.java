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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The practice/recording layer, drawn on top of the game.
 *
 * <p>Play happens in the game - that is where the sound comes from - so the app cannot show its own
 * board. Two separate windows are used instead:
 *
 * <ul>
 *   <li>a full-screen <em>guide</em> window drawing the timing rings. It is not touchable at all
 *       unless the user asked for scoring, in which case it also captures presses;</li>
 *   <li>a small always-touchable <em>control</em> window holding the 结束 button and the score.
 *       Kept apart on purpose: putting the button in the guide window meant that turning touch off
 *       on the guide also killed the button, which is why 结束 stopped responding.</li>
 * </ul>
 *
 * <p>Forwarding a press needs one more trick: injected gestures go to the topmost window, which is
 * this layer, so it has to stop accepting touches for a moment or the game never receives them.
 */
public final class GameOverlay {

    public static final int MODE_RECORD = 0;
    public static final int MODE_PRACTICE = 1;

    /** How far from a pad centre a touch still counts as that pad. */
    private static final float REACH_DP = 34f;

    /** Ring size follows the measured pad: starts a little outside it, closes onto it. */
    private static final float RING_START_FACTOR = 1.55f;
    private static final float RING_END_FACTOR = 0.92f;
    private static final float RING_MIN_DP = 12f;
    private static final float RING_MAX_DP = 34f;

    /** Only presses this close are ringed, so a dense passage does not cover the screen. */
    private static final long RING_LEAD_MS = 1100L;

    /** At most this many rings at once. */
    private static final int MAX_RING_GROUPS = 6;

    /**
     * The note lane looks further ahead and shows more, which also makes it scroll slowly: an item
     * covers the same short distance over much more time. The first version reused the ring lead, so
     * it both raced past and showed almost nothing ahead.
     */
    private static final long LANE_LEAD_MS = 2800L;
    private static final int MAX_LANE_ITEMS = 12;

    /** One colour per upcoming press; simultaneous presses share both colour and number. */
    private static final int[] PALETTE = {
            0xFF42A5F5, 0xFFFFB300, 0xFF66BB6A, 0xFFEF5350,
            0xFFAB47BC, 0xFF26C6DA, 0xFFFF7043, 0xFFEC407A,
    };

    /**
     * How long the guide layer stops accepting touches while a forwarded tap is delivered.
     *
     * <p>Injected gestures go to the topmost window, which is this layer - so a forwarded tap would
     * otherwise be swallowed by the very layer that sent it and the game would see nothing.
     */
    private static final long FORWARD_WINDOW_MS = 90L;

    private static final long TICK_MS = 16L;

    public interface Callback {
        void onFinished(String summary, int recordedHits);
    }

    private static View sGuideRoot;
    private static BoardView sBoard;
    private static View sControlRoot;
    private static TextView sStatus;
    private static View sLaneRoot;
    private static LaneView sLane;
    private static final SpeedClock CLOCK = new SpeedClock();
    private static WindowManager sWm;
    private static WindowManager.LayoutParams sGuideParams;
    private static Callback sCallback;
    private static PracticeSession sSession;
    private static int sMode;
    private static boolean sTakeOver;
    private static long sStart;
    private static final List<RecordingCodec.Hit> sRecorded = new ArrayList<RecordingCodec.Hit>();
    private static int sStrays;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static final Runnable RESTORE_TOUCH = new Runnable() {
        @Override
        public void run() {
            setGuideTouchable(true);
        }
    };

    private GameOverlay() {}

    public static boolean isRunning() {
        return sGuideRoot != null;
    }

    /**
     * @param takeOverTouch true to intercept touches so presses can be recorded or judged; false to
     *                      draw guidance only, leaving the game's own input completely untouched
     */
    public static void start(Context context, int mode, PracticeSession session, Callback callback,
                             boolean takeOverTouch, float speed) {
        stop();
        final Context ctx = context.getApplicationContext();
        if (!OverlayController.canDraw(ctx)) {
            if (callback != null) callback.onFinished("需要先授予悬浮窗权限。", 0);
            return;
        }
        sMode = mode;
        sSession = session;
        sCallback = callback;
        sTakeOver = takeOverTouch;
        sRecorded.clear();
        sStrays = 0;
        sStart = SystemClock.uptimeMillis();
        CLOCK.start(sStart);
        CLOCK.setSpeed(speed);

        sWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (!addGuideWindow(ctx) || !addControlWindow(ctx) || !addLaneWindow(ctx)) {
            stop();
            if (callback != null) callback.onFinished("无法显示悬浮层。", 0);
            return;
        }
        updateStatus();
        matchLaneWidthToControlBar();
        HANDLER.postDelayed(TICK, TICK_MS);
    }

    /** The lane is exactly as wide as the control strip above it, per the design. */
    private static void matchLaneWidthToControlBar() {
        if (sControlRoot == null || sLaneRoot == null) return;
        sControlRoot.post(new Runnable() {
            @Override
            public void run() {
                if (sControlRoot == null || sLaneRoot == null || sWm == null) return;
                int width = sControlRoot.getWidth();
                if (width <= 0) return;
                ViewGroup.LayoutParams raw = sLaneRoot.getLayoutParams();
                if (!(raw instanceof WindowManager.LayoutParams)) return;
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) raw;
                if (params.width == width) return;
                params.width = width;
                try {
                    sWm.updateViewLayout(sLaneRoot, params);
                } catch (RuntimeException ignored) {
                }
            }
        });
    }

    private static boolean addGuideWindow(Context ctx) {
        sBoard = new BoardView(ctx);
        sGuideParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        sGuideParams.gravity = Gravity.TOP | Gravity.START;
        if (!sTakeOver) {
            // Guidance only: every touch goes straight to the game, exactly as without the app.
            sGuideParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        sGuideRoot = sBoard;
        try {
            sWm.addView(sBoard, sGuideParams);
            return true;
        } catch (RuntimeException e) {
            sGuideRoot = null;
            return false;
        }
    }

    /**
     * The always-touchable control strip.
     *
     * <p>A separate window on purpose: the guide layer has touch switched off in guidance mode and
     * briefly during forwarding, and a button sharing that window would go dead with it.
     */
    private static boolean addControlWindow(Context ctx) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.overlay_bar);
        int pad = Ui.dp(ctx, 6);
        bar.setPadding(pad, pad, pad, pad);

        sStatus = new TextView(ctx);
        sStatus.setTextColor(Color.WHITE);
        sStatus.setTextSize(13f);
        sStatus.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6));
        bar.addView(sStatus);

        TextView stop = new TextView(ctx);
        stop.setText("■ 结束");
        stop.setTextColor(0xFFFF8A80);
        stop.setTextSize(14f);
        stop.setBackgroundResource(R.drawable.overlay_button);
        stop.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 9), Ui.dp(ctx, 14), Ui.dp(ctx, 9));
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish(false);
            }
        });
        bar.addView(stop);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = Ui.dp(ctx, 10);
        params.y = Ui.dp(ctx, 36);

        sControlRoot = bar;
        try {
            sWm.addView(bar, params);
            return true;
        } catch (RuntimeException e) {
            sControlRoot = null;
            return false;
        }
    }

    private static boolean modeIsPractice() {
        return sMode == MODE_PRACTICE;
    }

    private static String speedLabel() {
        float s = CLOCK.speed();
        return (s == Math.round(s) ? String.valueOf((int) s) : String.valueOf(s)) + "×";
    }

    /**
     * The scrolling note lane, placed under the control bar.
     *
     * <p>Never touchable: it is a read-only display, and letting it accept touches would block the
     * game underneath. Upcoming presses travel towards a line on the left; the pad digits use the
     * same circled convention as the rest of the app.
     */
    private static boolean addLaneWindow(Context ctx) {
        if (!modeIsPractice()) return true;
        sLane = new LaneView(ctx);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Ui.dp(ctx, 220), // replaced by the control bar's measured width once it is laid out
                Ui.dp(ctx, 42),
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = Ui.dp(ctx, 10);
        params.y = Ui.dp(ctx, 84);
        sLaneRoot = sLane;
        try {
            sWm.addView(sLane, params);
            return true;
        } catch (RuntimeException e) {
            sLaneRoot = null;
            return false;
        }
    }

    private static int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    public static void stop() {
        HANDLER.removeCallbacks(TICK);
        HANDLER.removeCallbacks(RESTORE_TOUCH);
        removeWindow(sGuideRoot);
        removeWindow(sControlRoot);
        removeWindow(sLaneRoot);
        sGuideRoot = null;
        sControlRoot = null;
        sLaneRoot = null;
        sBoard = null;
        sLane = null;
        sStatus = null;
        sGuideParams = null;
        CLOCK.stop();
    }

    private static void removeWindow(View view) {
        if (view == null || sWm == null) return;
        try {
            sWm.removeView(view);
        } catch (RuntimeException ignored) {
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

    public static List<RecordingCodec.Hit> recordedHits() {
        return new ArrayList<RecordingCodec.Hit>(sRecorded);
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (sBoard == null) return;
            if (sMode == MODE_PRACTICE && sSession != null) {
                // Virtual time: practice speed scales the clock rather than the chart, so changing
                // tempo mid-run keeps the score and needs no rebuild.
                long now = CLOCK.advance(SystemClock.uptimeMillis());
                if (sSession.consumeMisses(now) > 0) updateStatus();
                sBoard.setGroups(sSession.upcomingGroups(now, RING_LEAD_MS, MAX_RING_GROUPS), now);
                if (sLane != null) {
                    sLane.setGroups(sSession.upcomingGroups(now, LANE_LEAD_MS, MAX_LANE_ITEMS), now);
                }
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
            sStatus.setText("录制中 " + sRecorded.size());
            return;
        }
        if (sSession == null) {
            sStatus.setText("练习中");
            return;
        }
        sStatus.setText("P" + sSession.perfect() + " G" + sSession.good()
                + " OK" + sSession.ok() + " M" + sSession.miss()
                + " " + sSession.accuracyPercent() + "%  " + speedLabel());
    }

    /**
     * Lets touches fall through for a moment so a forwarded tap reaches the game.
     *
     * <p>Without this the injected gesture lands on this layer, is consumed as if the user had
     * pressed, and the game receives nothing.
     */
    private static void forwardTouch(HandpanAccessibilityService service, float x, float y) {
        setGuideTouchable(false);
        service.tapAll(new float[]{x}, new float[]{y});
        HANDLER.removeCallbacks(RESTORE_TOUCH);
        HANDLER.postDelayed(RESTORE_TOUCH, FORWARD_WINDOW_MS);
    }

    private static void setGuideTouchable(boolean touchable) {
        if (sGuideParams == null || sWm == null || sGuideRoot == null || !sTakeOver) return;
        int flags = sGuideParams.flags;
        if (touchable) {
            flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        if (flags == sGuideParams.flags) return;
        sGuideParams.flags = flags;
        try {
            sWm.updateViewLayout(sGuideRoot, sGuideParams);
        } catch (RuntimeException ignored) {
        }
    }

    private static float[][] pads(Context ctx) {
        float[][] pads = new float[AppPrefs.SLOTS][];
        for (int slot = 0; slot < AppPrefs.SLOTS; slot++) pads[slot] = AppPrefs.getPad(ctx, slot);
        return pads;
    }

    // ------------------------------------------------------------------ the guide surface

    private static final class BoardView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flash = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final float[][] pads;
        private final float reach;
        private final float ringStart;
        private final float ringEnd;

        private List<PracticeSession.Group> groups = new ArrayList<PracticeSession.Group>();
        private long nowMs;

        private final int[] flashColor = new int[AppPrefs.SLOTS];
        private final long[] flashUntil = new long[AppPrefs.SLOTS];

        BoardView(Context ctx) {
            super(ctx);
            pads = pads(ctx);
            reach = Ui.dp(ctx, (int) REACH_DP);
            float padRadius = PadGeometry.estimatePadRadius(pads, Ui.dp(ctx, 22f),
                    Ui.dp(ctx, (int) RING_MIN_DP), Ui.dp(ctx, (int) RING_MAX_DP));
            ringStart = padRadius * RING_START_FACTOR;
            ringEnd = padRadius * RING_END_FACTOR;

            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(ctx, 4));
            flash.setStyle(Paint.Style.STROKE);
            flash.setStrokeWidth(Ui.dp(ctx, 5));
            halo.setStyle(Paint.Style.FILL);
            halo.setColor(0x66000000);
            number.setColor(Color.WHITE);
            number.setTextAlign(Paint.Align.CENTER);
            number.setTextSize(Ui.dp(ctx, 15));
            number.setTypeface(Typeface.DEFAULT_BOLD);
        }

        void setGroups(List<PracticeSession.Group> groups, long nowMs) {
            this.groups = groups == null ? new ArrayList<PracticeSession.Group>() : groups;
            this.nowMs = nowMs;
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
            // Calibration stores screen coordinates; shift them into view space so the rings land on
            // the game's pads even if the window is inset.
            final int[] origin = new int[2];
            getLocationOnScreen(origin);

            for (int i = 0; i < groups.size(); i++) {
                PracticeSession.Group group = groups.get(i);
                int color = PALETTE[(group.order - 1) % PALETTE.length];
                float remaining = group.timeMs - nowMs;
                float p = Math.max(0f, Math.min(1f, remaining / (float) RING_LEAD_MS));
                float r = ringStart + (ringEnd - ringStart) * (1f - p);

                ring.setColor(color);
                String text = String.valueOf(group.order);
                for (int s = 0; s < group.slots.length; s++) {
                    int slot = group.slots[s];
                    if (slot < 0 || slot >= pads.length || pads[slot] == null) continue;
                    float cx = pads[slot][0] - origin[0];
                    float cy = pads[slot][1] - origin[1];
                    canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 0f, 360f, false, ring);

                    // The number sits on the pad; a chord shows the same number on every pad of it.
                    float half = number.getTextSize() * 0.5f;
                    canvas.drawCircle(cx, cy + half * 0.25f, number.getTextSize() * 0.72f, halo);
                    canvas.drawText(text, cx, cy + half * 0.85f, number);
                }
            }

            long now = System.currentTimeMillis();
            for (int slot = 0; slot < pads.length; slot++) {
                if (pads[slot] == null || now >= flashUntil[slot]) continue;
                flash.setColor(flashColor[slot]);
                canvas.drawCircle(pads[slot][0] - origin[0], pads[slot][1] - origin[1],
                        ringEnd * 0.95f, flash);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) {
                return true; // consume the rest so only forwarded taps reach the game
            }
            final int index = event.getActionIndex();
            // Raw coordinates: the calibration grid lives in screen space, not view space.
            final float x = event.getRawX(index);
            final float y = event.getRawY(index);

            HandpanAccessibilityService service = HandpanAccessibilityService.get();
            if (service != null) forwardTouch(service, x, y);

            int slot = PadHitTester.slotAt(pads, x, y, reach);
            if (slot < 0) {
                if (sMode == MODE_PRACTICE) sStrays++;
                updateStatus();
                return true;
            }

            long now = sMode == MODE_RECORD
                    ? SystemClock.uptimeMillis() - sStart
                    : CLOCK.advance(SystemClock.uptimeMillis());
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

    // ------------------------------------------------------------------ the scrolling note lane

    private static final class LaneView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint item = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);

        private List<PracticeSession.Group> groups = new ArrayList<PracticeSession.Group>();
        private long nowMs;

        private final float density;

        LaneView(Context ctx) {
            super(ctx);
            density = ctx.getResources().getDisplayMetrics().density;
            bg.setColor(0x99101820);
            line.setColor(0xCCFFFFFF);
            line.setStrokeWidth(dp(1.5f));
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(Color.WHITE);
            text.setTextSize(dp(13));
            text.setTypeface(Typeface.DEFAULT_BOLD);
            small.setTextAlign(Paint.Align.CENTER);
            small.setColor(0xCCFFFFFF);
            small.setTextSize(dp(9));
        }

        private float dp(float v) {
            return v * density;
        }

        void setGroups(List<PracticeSession.Group> groups, long nowMs) {
            this.groups = groups == null ? new ArrayList<PracticeSession.Group>() : groups;
            this.nowMs = nowMs;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float h = getHeight();
            float w = getWidth();
            canvas.drawRoundRect(new RectF(0, 0, w, h), dp(10), dp(10), bg);

            final float hitX = dp(18);
            final float itemW = dp(30);
            final float itemH = dp(28);
            final float gap = dp(2);
            final float maxGap = dp(6); // tiles stay a tight queue, never spread far apart
            final float top = (h - itemH) / 2f;

            line.setAlpha(200);
            canvas.drawLine(hitX, top - dp(3), hitX, top + itemH + dp(3), line);

            // Laid out as a queue, nearest first, never overlapping.
            //
            // Positioning purely by time does not work in a strip this narrow: two notes 250ms apart
            // map only ~14dp apart while a tile is 34dp wide, so they pile up. Each tile is therefore
            // pushed clear of the previous one, and once there is no room the rest are simply not
            // drawn - the nearest note is always the one that matters.
            float usable = w - hitX - itemW - dp(4);
            if (usable < 0) usable = 0;

            long[] times = new long[groups.size()];
            for (int i = 0; i < groups.size(); i++) times[i] = groups.get(i).timeMs;
            List<LaneLayout.Placement> placed = LaneLayout.place(times, nowMs, LANE_LEAD_MS,
                    hitX, usable, itemW, gap, maxGap, w - dp(3));

            for (int i = 0; i < placed.size(); i++) {
                LaneLayout.Placement p = placed.get(i);
                PracticeSession.Group g = groups.get(p.index);
                float x = p.x;
                item.setColor(PALETTE[(g.order - 1) % PALETTE.length]);
                canvas.drawRoundRect(new RectF(x, top, x + itemW, top + itemH), dp(6), dp(6), item);

                StringBuilder pads = new StringBuilder();
                for (int s = 0; s < g.slots.length && s < 3; s++) {
                    if (s > 0) pads.append('+');
                    pads.append(PadMapper.shortOf(g.slots[s]));
                }
                canvas.drawText(String.valueOf(g.order), x + itemW / 2f, top + dp(11), small);
                canvas.drawText(pads.toString(), x + itemW / 2f, top + dp(24), text);
            }
        }
    }

    /** Timestamped name for a recording, e.g. "录音 09-21 19:04". */
    public static String defaultRecordingName() {
        return "录音 " + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date());
    }
}
