package com.handpan.autoplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

/**
 * Injects taps into whatever is on screen, using the accessibility gesture API.
 *
 * <p>This is the only way to synthesise touches into another app (here: a game) without root or adb.
 * The service deliberately requests almost no accessibility events - it does not read the screen at
 * all, it only dispatches gestures at coordinates the user calibrated.
 */
public class HandpanAccessibilityService extends AccessibilityService {

    private static volatile HandpanAccessibilityService sInstance;

    /** Duration of one pad tap. Long enough to register, short enough to feel instant. */
    private static final long TAP_MS = 24L;

    /** @return the connected service, or null when the user has not enabled it. */
    public static HandpanAccessibilityService get() {
        return sInstance;
    }

    public static boolean isReady() {
        return sInstance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intentionally unused: we never inspect other apps' content.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt: gestures are one-shot and playback is driven by Playback.
    }

    /**
     * Synthesises a single tap at screen coordinates.
     *
     * @return true when the gesture was accepted by the system.
     */
    public boolean tap(float x, float y) {
        return tapAll(new float[]{x}, new float[]{y});
    }

    /**
     * Presses several pads at the same instant.
     *
     * <p>A {@link GestureDescription} may carry multiple strokes, and they run concurrently, so one
     * dispatch is a genuine multi-finger chord rather than a fast sequence. That matters for a
     * handpan: harmony and bass notes belong on top of the melody, not after it.
     *
     * @return true when the gesture was accepted by the system.
     */
    public boolean tapAll(float[] xs, float[] ys) {
        if (xs == null || ys == null) return false;
        int count = Math.min(xs.length, ys.length);
        int limit = GestureDescription.getMaxStrokeCount();
        if (count > limit) count = limit;

        GestureDescription.Builder builder = new GestureDescription.Builder();
        int added = 0;
        for (int i = 0; i < count; i++) {
            if (xs[i] <= 0f || ys[i] <= 0f) continue; // uncalibrated pad
            Path path = new Path();
            path.moveTo(xs[i], ys[i]);
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0L, TAP_MS));
            added++;
        }
        if (added == 0) return false; // a gesture with no strokes is rejected by the platform
        try {
            return dispatchGesture(builder.build(), null, null);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
