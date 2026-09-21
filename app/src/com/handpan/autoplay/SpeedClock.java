package com.handpan.autoplay;

/**
 * A virtual clock that runs at a chosen multiple of real time.
 *
 * <p>Practice speed is applied by advancing this clock rather than by rewriting the chart's times:
 * that way the tempo can be changed in the middle of a run without rebuilding the session and losing
 * the score so far. Pure arithmetic, so the rounding behaviour is testable.
 */
public final class SpeedClock {

    public static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    private float speed = 1.0f;
    private long virtualMs;
    private long lastRealMs;
    private boolean started;

    public void start(long realMs) {
        virtualMs = 0;
        lastRealMs = realMs;
        started = true;
    }

    public void stop() {
        started = false;
    }

    public boolean isRunning() {
        return started;
    }

    public float speed() {
        return speed;
    }

    public void setSpeed(float value) {
        if (value > 0f) speed = value;
    }

    /** Next speed in {@link #SPEEDS}, wrapping; used by the on-screen tempo button. */
    public void cycleSpeed() {
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - speed) < 0.001f) {
                speed = SPEEDS[(i + 1) % SPEEDS.length];
                return;
            }
        }
        speed = 1.0f;
    }

    /**
     * Advances the virtual clock by however much real time has passed, scaled by the speed.
     *
     * @return the new virtual time in milliseconds
     */
    public long advance(long realMs) {
        if (!started) return virtualMs;
        long delta = realMs - lastRealMs;
        lastRealMs = realMs;
        if (delta < 0) delta = 0; // clock went backwards: ignore rather than jump
        if (delta > 1000) delta = 1000; // long pause (screen off); do not swallow the whole chart
        virtualMs += Math.round(delta * speed);
        return virtualMs;
    }

    public long now() {
        return virtualMs;
    }
}
