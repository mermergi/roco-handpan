package com.handpan.autoplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * The nine pads, laid out the way they sit in the game, drawn on the app's own screen.
 *
 * <p>Both new modes need this: recording captures presses on it, practice shows where and when to
 * press. A pad about to be due grows a ring that contracts onto it, so the timing is readable at a
 * glance without any scrolling note lane - which would not fit a 3/5/1 pad layout anyway.
 */
public class PadBoardView extends View {

    public interface OnPadListener {
        void onPad(int slot);
    }

    /** Pad centres as fractions of the view, matching the in-game arrangement. */
    private static final float[][] CENTRES = {
            {0.32f, 0.20f}, {0.50f, 0.20f}, {0.68f, 0.20f},
            {0.10f, 0.52f}, {0.30f, 0.52f}, {0.50f, 0.52f}, {0.70f, 0.52f}, {0.90f, 0.52f},
            {0.50f, 0.84f},
    };

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private OnPadListener listener;

    /** Slots with a timing ring, and how much of the lead time is left (1 = just appeared). */
    private int[] dueSlots = new int[0];
    private float[] dueProgress = new float[0];

    /** Per-slot flash colour and the time it should stop showing. */
    private final int[] flashColor = new int[PadMapper.SLOTS];
    private final long[] flashUntil = new long[PadMapper.SLOTS];

    private long flashMs = 220L;

    public PadBoardView(Context context) {
        super(context);
        init();
    }

    public PadBoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PadBoardView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFF3EAD3);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Ui.dp(getContext(), 1.5f));
        edge.setColor(0x33000000);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(getContext(), 3f));
        ring.setColor(0xFF1565C0);
        text.setColor(0xFF263238);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Ui.dp(getContext(), 13f));
    }

    public void setOnPadListener(OnPadListener listener) {
        this.listener = listener;
    }

    /**
     * @param slots    pads that are due
     * @param progress matching 1..0, where 1 means the lead time has just started and 0 means now
     */
    public void setDue(int[] slots, float[] progress) {
        dueSlots = slots == null ? new int[0] : slots;
        dueProgress = progress == null ? new float[0] : progress;
        invalidate();
    }

    /** Lights a pad briefly, green for a hit and red for a miss. */
    public void flash(int slot, boolean hit) {
        flash(slot, hit ? 0xFF43A047 : 0xFFE53935);
    }

    public void flash(int slot, int color) {
        if (slot < 0 || slot >= PadMapper.SLOTS) return;
        flashColor[slot] = color;
        flashUntil[slot] = System.currentTimeMillis() + flashMs;
        invalidate();
    }

    private float radius() {
        return Math.min(getWidth(), getHeight()) * 0.095f;
    }

    private float cx(int slot) {
        return CENTRES[slot][0] * getWidth();
    }

    private float cy(int slot) {
        return CENTRES[slot][1] * getHeight();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float r = radius();
        long now = System.currentTimeMillis();

        for (int slot = 0; slot < PadMapper.SLOTS; slot++) {
            float x = cx(slot);
            float y = cy(slot);

            boolean flashing = now < flashUntil[slot];
            fill.setColor(flashing ? flashColor[slot] : 0xFFF3EAD3);
            canvas.drawCircle(x, y, r, fill);
            canvas.drawCircle(x, y, r, edge);

            String label = PadMapper.shortOf(slot);
            text.setColor(flashing ? Color.WHITE : 0xFF263238);
            canvas.drawText(label, x, y + text.getTextSize() * 0.35f, text);
        }

        // Timing rings on top, so they are never hidden behind a neighbouring pad.
        for (int i = 0; i < dueSlots.length; i++) {
            int slot = dueSlots[i];
            if (slot < 0 || slot >= PadMapper.SLOTS) continue;
            float progress = i < dueProgress.length ? dueProgress[i] : 0f;
            if (progress < 0f) progress = 0f;
            if (progress > 1f) progress = 1f;
            float ringRadius = r * (1f + 0.9f * progress);
            ring.setColor(progress < 0.25f ? 0xFFE53935 : 0xFF1565C0);
            canvas.drawArc(new RectF(cx(slot) - ringRadius, cy(slot) - ringRadius,
                    cx(slot) + ringRadius, cy(slot) + ringRadius), 0f, 360f, false, ring);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
        float r = radius();
        float reach = r * 1.35f; // a little slack: thumbs are not precise
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int slot = 0; slot < PadMapper.SLOTS; slot++) {
            float dx = event.getX() - cx(slot);
            float dy = event.getY() - cy(slot);
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance <= reach && distance < bestDistance) {
                bestDistance = distance;
                best = slot;
            }
        }
        if (best >= 0 && listener != null) {
            listener.onPad(best);
            return true;
        }
        return true;
    }
}
