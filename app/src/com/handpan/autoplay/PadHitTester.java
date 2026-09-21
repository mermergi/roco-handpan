package com.handpan.autoplay;

/**
 * Maps a touch coordinate onto a calibrated pad.
 *
 * <p>Needed because play happens inside the game, not in the app: an overlay captures the finger,
 * records or judges it, and forwards the touch back. Pure arithmetic so the matching rules - the
 * reach radius, ties, a tap that hits nothing - are testable off-device.
 */
public final class PadHitTester {

    private PadHitTester() {}

    /**
     * @param pads     nine {@code {x, y}} entries, or null entries for uncalibrated pads
     * @param maxDistance how far a touch may land from a pad centre and still count
     * @return the closest pad slot within range, or -1 when the touch hits no pad
     */
    public static int slotAt(float[][] pads, float x, float y, float maxDistance) {
        if (pads == null) return -1;
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int slot = 0; slot < pads.length; slot++) {
            float[] pad = pads[slot];
            if (pad == null || pad.length < 2) continue;
            float dx = x - pad[0];
            float dy = y - pad[1];
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance <= maxDistance && distance < bestDistance) {
                bestDistance = distance;
                best = slot;
            }
        }
        return best;
    }
}
