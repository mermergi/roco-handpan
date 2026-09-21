package com.handpan.autoplay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Floating windows used for calibration and for stopping a run.
 *
 * <p>Calibration happens <em>on top of the game</em> rather than in a separate activity: the user
 * taps the real pads where they actually are, so there is no orientation or inset mismatch between
 * calibration and playback.
 */
public final class OverlayController {

    public interface CalibrationCallback {
        void onFinished(boolean completed);
    }

    private static View sCalView;
    private static View sStopView;
    private static TextView sPauseView;
    private static WindowManager sWm;
    private static int sSlot;

    private OverlayController() {}

    public static boolean canDraw(Context c) {
        return Settings.canDrawOverlays(c);
    }

    public static void requestPermission(Activity a) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + a.getPackageName()));
        try {
            a.startActivity(i);
        } catch (RuntimeException e) {
            // Some ROMs lack the dedicated screen; fall back to app details.
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static WindowManager wm(Context c) {
        if (sWm == null) {
            sWm = (WindowManager) c.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        }
        return sWm;
    }

    private static WindowManager.LayoutParams fullScreenParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    // ---------------------------------------------------------------- calibration

    /**
     * Shows the calibration overlay. Caller must have overlay permission.
     * The user taps the eight pads in order 1..7 then 0.
     */
    public static void startCalibration(final Activity activity, final CalibrationCallback callback) {
        cancelCalibration();
        final Context ctx = activity.getApplicationContext();

        final FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(0x55000000);

        final TextView title = new TextView(ctx);
        title.setTextColor(Color.WHITE);
        title.setBackgroundColor(0xCC101820);
        title.setTextSize(18f);
        title.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.TOP;
        root.addView(title, tp);

        final TextView cancel = new TextView(ctx);
        cancel.setText("取消");
        cancel.setTextColor(Color.WHITE);
        cancel.setBackgroundColor(0xCC802020);
        cancel.setTextSize(16f);
        cancel.setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.BOTTOM | Gravity.END;
        cp.setMargins(0, 0, dp(ctx, 20), dp(ctx, 24));
        root.addView(cancel, cp);

        sSlot = 0;
        updateCalibrationTitle(title, 0);

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelCalibration();
                callback.onFinished(false);
            }
        });

        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
                float x = event.getRawX();
                float y = event.getRawY();
                if (sSlot >= AppPrefs.SLOTS) return true;
                AppPrefs.setPad(ctx, sSlot, x, y);
                sSlot++;
                if (sSlot >= AppPrefs.SLOTS) {
                    AppPrefs.setCalibrationDisplay(ctx,
                            activity.getResources().getDisplayMetrics().widthPixels,
                            activity.getResources().getDisplayMetrics().heightPixels);
                    cancelCalibration();
                    callback.onFinished(true);
                } else {
                    updateCalibrationTitle(title, sSlot);
                }
                return true;
            }
        });

        sCalView = root;
        try {
            wm(ctx).addView(root, fullScreenParams());
        } catch (RuntimeException e) {
            sCalView = null;
            callback.onFinished(false);
        }
    }

    private static void updateCalibrationTitle(TextView title, int slot) {
        String digit = AppPrefs.digitOf(slot);
        title.setText("校准 " + (slot + 1) + "/" + AppPrefs.SLOTS
                + "：请点按琴键【" + digit + "】的正中心\n"
                + "（可切到游戏界面再点，点完自动退出；右下角可取消）");
    }

    public static void cancelCalibration() {
        View v = sCalView;
        sCalView = null;
        if (v != null && sWm != null) {
            try {
                sWm.removeView(v);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static boolean isCalibrating() {
        return sCalView != null;
    }

    // ---------------------------------------------------------------- floating transport bar

    /** What the floating bar's buttons do. Implemented by the play screen. */
    public interface Transport {
        void onPrevious();

        /** Pause if playing, resume if paused. */
        void onPauseResume();

        void onNext();

        void onStop();
    }

    /** Shows a draggable bar with previous / pause / next / stop, on top of the game. */
    public static void showTransport(Context context, final Transport transport) {
        final Context ctx = context.getApplicationContext();
        if (!canDraw(ctx)) return;

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.overlay_bar);
        int pad = dp(ctx, 6);
        bar.setPadding(pad, pad, pad, pad);

        // A dedicated handle, because a button would swallow the drag gesture.
        TextView handle = new TextView(ctx);
        handle.setText("≡");
        handle.setTextColor(0xFFB0BEC5);
        handle.setTextSize(18f);
        handle.setGravity(Gravity.CENTER);
        handle.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        bar.addView(handle);

        bar.addView(barButton(ctx, "上一首", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                transport.onPrevious();
            }
        }));

        sPauseView = barButton(ctx, "暂停", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                transport.onPauseResume();
            }
        });
        bar.addView(sPauseView);

        bar.addView(barButton(ctx, "下一首", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                transport.onNext();
            }
        }));

        TextView stop = barButton(ctx, "停止", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                transport.onStop();
            }
        });
        stop.setTextColor(0xFFFF8A80);
        bar.addView(stop);

        showBar(ctx, bar, handle, sPauseView);
    }

    /** Single draggable stop button, for screens that only need to interrupt something. */
    public static void showStopButton(Context context, final Runnable onStop) {
        final Context ctx = context.getApplicationContext();
        if (!canDraw(ctx)) return;

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.overlay_bar);
        int pad = dp(ctx, 6);
        bar.setPadding(pad, pad, pad, pad);

        TextView handle = new TextView(ctx);
        handle.setText("≡");
        handle.setTextColor(0xFFB0BEC5);
        handle.setTextSize(18f);
        handle.setGravity(Gravity.CENTER);
        handle.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        bar.addView(handle);

        TextView stop = barButton(ctx, "停止", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onStop != null) onStop.run();
            }
        });
        stop.setTextColor(0xFFFF8A80);
        bar.addView(stop);

        showBar(ctx, bar, handle, null);
    }

    /** Updates the middle button's label to match the play/pause state. */
    public static void setPaused(boolean paused) {
        if (sPauseView != null) sPauseView.setText(paused ? "继续" : "暂停");
    }

    private static TextView barButton(Context ctx, String text, View.OnClickListener click) {
        TextView view = new TextView(ctx);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(14f);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(R.drawable.overlay_button);
        view.setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9));
        view.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
        view.setLayoutParams(params);
        return view;
    }

    /** Adds the bar as an overlay window; dragging happens on the handle. */
    private static void showBar(final Context ctx, final View bar, final View handle,
                                final TextView pauseView) {
        hideStopButton();

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(ctx, 10);
        lp.y = dp(ctx, 36);

        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (e.getRawX() - downX);
                        lp.y = startY + (int) (e.getRawY() - downY);
                        try {
                            sWm.updateViewLayout(bar, lp);
                        } catch (RuntimeException ignored) {
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        sStopView = bar;
        sPauseView = pauseView;
        try {
            wm(ctx).addView(bar, lp);
        } catch (RuntimeException e) {
            sStopView = null;
        }
    }

    public static void hideStopButton() {
        View v = sStopView;
        sStopView = null;
        sPauseView = null;
        if (v != null && sWm != null) {
            try {
                sWm.removeView(v);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
