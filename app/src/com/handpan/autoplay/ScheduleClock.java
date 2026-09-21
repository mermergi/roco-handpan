package com.handpan.autoplay;

/**
 * Tracks how far playback has progressed, including across pauses.
 *
 * <p>Split out of {@link Playback} so the arithmetic is testable: that class needs Android's Handler
 * and SystemClock, and a pause that silently drifts would be very hard to notice by ear. The rule is
 * that "elapsed" means time actually spent playing, so pausing freezes it and resuming back-dates
 * the start by the frozen amount - the remaining schedule stays exact however long the pause lasted.
 */
public final class ScheduleClock {

    private long startedAt;
    private long pausedElapsed = -1L;
    private boolean running;

    /** Begins a fresh run at {@code now}. */
    public void start(long now) {
        startedAt = now;
        pausedElapsed = -1L;
        running = true;
    }

    /** Clears the run entirely. */
    public void reset() {
        running = false;
        pausedElapsed = -1L;
    }

    /** Freezes progress. A second call, or a call while stopped, does nothing. */
    public void pause(long now) {
        if (!running || pausedElapsed >= 0) return;
        pausedElapsed = now - startedAt;
    }

    /** Resumes progress. A call while not paused, or while stopped, does nothing. */
    public void resume(long now) {
        if (!running || pausedElapsed < 0) return;
        startedAt = now - pausedElapsed;
        pausedElapsed = -1L;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return running && pausedElapsed >= 0;
    }

    /** Milliseconds actually spent playing, frozen while paused. */
    public long elapsed(long now) {
        if (!running) return 0;
        return pausedElapsed >= 0 ? pausedElapsed : now - startedAt;
    }
}
