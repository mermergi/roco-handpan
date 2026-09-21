package com.handpan.autoplay;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * Schedules pad taps in real time and hands each one to the accessibility service.
 *
 * <p>Playback runs on the process main looper. That process is kept alive by the accessibility
 * service, so the user is free to switch into the game once the countdown ends.
 */
public final class Playback {

    /** One scheduled pad hit. */
    /** One scheduled hit: one pad, or several pads pressed together as a chord. */
    public static final class Tap {
        public final float[] xs;
        public final float[] ys;
        public final long atMs;

        public Tap(float[] xs, float[] ys, long atMs) {
            this.xs = xs;
            this.ys = ys;
            this.atMs = atMs;
        }

        public int voices() {
            return xs == null ? 0 : xs.length;
        }
    }

    public interface Listener {
        void onProgress(int done, int total);

        void onFinish(boolean completed);
    }

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    /** How often the scheduler wakes up. Small enough for musical timing, cheap while idle. */
    private static final long TICK_MS = 4L;

    /** Upper bound on taps dispatched in one tick, so a huge backlog cannot stall the UI thread. */
    private static final int MAX_TAPS_PER_TICK = 32;

    private static List<Tap> sTaps;
    private static int sIndex;
    private static boolean sRunning;
    private static long sStartedAtUptime;
    private static Listener sListener;

    private Playback() {}

    public static boolean isPlaying() {
        return sRunning;
    }

    public static int progressIndex() {
        return sIndex;
    }

    public static int total() {
        return sTaps == null ? 0 : sTaps.size();
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (!sRunning) return;

            HandpanAccessibilityService service = HandpanAccessibilityService.get();
            if (service == null) {
                finish(false);
                return;
            }

            long elapsed = SystemClock.uptimeMillis() - sStartedAtUptime;
            int dispatched = 0;
            while (sIndex < sTaps.size()
                    && sTaps.get(sIndex).atMs <= elapsed
                    && dispatched < MAX_TAPS_PER_TICK) {
                Tap tap = sTaps.get(sIndex);
                service.tapAll(tap.xs, tap.ys);
                sIndex++;
                dispatched++;
            }

            if (sIndex >= sTaps.size()) {
                finish(true);
                return;
            }
            if (sListener != null) sListener.onProgress(sIndex, sTaps.size());
            HANDLER.postDelayed(this, TICK_MS);
        }
    };

    /** Starts playback of a pre-sorted tap list whose {@code atMs} are relative to start. */
    public static synchronized void start(List<Tap> taps, Listener listener) {
        stop();
        sTaps = taps;
        sIndex = 0;
        sListener = listener;
        sStartedAtUptime = SystemClock.uptimeMillis();
        sRunning = true;
        if (sListener != null) sListener.onProgress(0, taps.size());
        HANDLER.postDelayed(TICK, TICK_MS);
    }

    public static synchronized void stop() {
        if (sRunning) finish(false);
    }

    private static void finish(boolean completed) {
        sRunning = false;
        HANDLER.removeCallbacks(TICK);
        Listener l = sListener;
        sListener = null;
        if (l != null) l.onFinish(completed);
    }
}
