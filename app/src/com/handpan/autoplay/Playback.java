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
    private static Listener sListener;

    /** Elapsed-time bookkeeping, including pauses. Pure logic so it can be unit tested. */
    private static final ScheduleClock CLOCK = new ScheduleClock();

    private Playback() {}

    public static boolean isPlaying() {
        return CLOCK.isRunning();
    }

    /** True while playback is held: the schedule stops advancing until {@link #resume()}. */
    public static synchronized boolean isPaused() {
        return CLOCK.isPaused();
    }

    /** Freezes playback, remembering how far it had got. */
    public static synchronized void pause() {
        if (!CLOCK.isRunning() || CLOCK.isPaused()) return;
        CLOCK.pause(SystemClock.uptimeMillis());
        HANDLER.removeCallbacks(TICK);
    }

    public static synchronized void resume() {
        if (!CLOCK.isPaused()) return;
        CLOCK.resume(SystemClock.uptimeMillis());
        HANDLER.postDelayed(TICK, TICK_MS);
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
            if (!CLOCK.isRunning()) return;
            if (CLOCK.isPaused()) return; // paused: do not reschedule

            HandpanAccessibilityService service = HandpanAccessibilityService.get();
            if (service == null) {
                finish(false);
                return;
            }

            long elapsed = CLOCK.elapsed(SystemClock.uptimeMillis());
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
        CLOCK.start(SystemClock.uptimeMillis());
        if (sListener != null) sListener.onProgress(0, taps.size());
        HANDLER.postDelayed(TICK, TICK_MS);
    }

    public static synchronized void stop() {
        if (CLOCK.isRunning()) finish(false);
    }

    private static void finish(boolean completed) {
        CLOCK.reset();
        HANDLER.removeCallbacks(TICK);
        Listener l = sListener;
        sListener = null;
        if (l != null) l.onFinish(completed);
    }
}
