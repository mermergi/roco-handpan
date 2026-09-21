package com.handpan.autoplay;

/**
 * Works out how big a pad is from the calibrated layout.
 *
 * <p>Beats a hardcoded size: screens differ, and the pads' on-screen radius is not something the app
 * can query. The closest pair of calibrated pads bounds it - the game spaces them so they do not
 * overlap, so half that distance is an upper bound on the radius. Pure arithmetic, so the clamping
 * and the degenerate cases are testable.
 */
public final class PadGeometry {

    private PadGeometry() {}

    /**
     * @param pads     nine {@code {x, y}} entries, null entries allowed
     * @param minDp    lower bound, so a tiny result on a dense layout is still visible
     * @param maxDp    upper bound, so a sparse layout does not produce a huge ring
     * @return an estimated pad radius in pixels
     */
    public static float estimatePadRadius(float[][] pads, float fallbackPx, float minPx, float maxPx) {
        if (pads == null) return fallbackPx;
        float closest = Float.MAX_VALUE;
        for (int i = 0; i < pads.length; i++) {
            if (pads[i] == null || pads[i].length < 2) continue;
            for (int j = i + 1; j < pads.length; j++) {
                if (pads[j] == null || pads[j].length < 2) continue;
                float dx = pads[i][0] - pads[j][0];
                float dy = pads[i][1] - pads[j][1];
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance > 0 && distance < closest) closest = distance;
            }
        }
        if (closest == Float.MAX_VALUE) return fallbackPx; // fewer than two pads calibrated

        // Pads are laid out not to touch, so a bit under half the closest spacing fits inside one.
        float radius = closest / 2.6f;
        if (radius < minPx) radius = minPx;
        if (radius > maxPx) radius = maxPx;
        return radius;
    }
}
