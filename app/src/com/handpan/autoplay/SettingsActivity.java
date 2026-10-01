package com.handpan.autoplay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings screen: permissions and calibration only.
 *
 * <p>Playback parameters (key, BPM, speed, chord limit, octave) live on the play screen, directly
 * under the current song title, because that is where they are actually adjusted - tweaking them and
 * watching the preview update in place beats bouncing to another screen. This screen keeps the
 * things you set once.
 */
public class SettingsActivity extends Activity {

    private TextView tvPerms;

    /** Progress of the wait for the accessibility service to bind. */
    private int serviceWait;
    private static final long SERVICE_WAIT_MS = 200L;
    private static final int SERVICE_WAIT_TRIES = 15;

    /** Main-looper handler, so a retry survives the user leaving the screen and coming back. */
    private static final android.os.Handler PERM =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle("设置");

        tvPerms = (TextView) findViewById(R.id.tv_perms);

        findViewById(R.id.btn_overlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayController.requestPermission(SettingsActivity.this);
            }
        });
        findViewById(R.id.btn_access).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (RuntimeException e) {
                    toast("打不开无障碍设置，请手动到 设置 → 无障碍");
                }
            }
        });
        findViewById(R.id.btn_calibrate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                calibrate();
            }
        });
        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testPads();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissions();
    }

    private void refreshPermissions() {
        boolean overlay = OverlayController.canDraw(this);
        boolean access = HandpanAccessibilityService.isReady();
        int calibrated = AppPrefs.calibratedCount(this);
        tvPerms.setText("悬浮窗 " + (overlay ? "✓" : "✗")
                + "　无障碍 " + (access ? "✓" : "✗")
                + "　已校准 " + calibrated + "/" + AppPrefs.SLOTS + " 键");
    }

    private void calibrate() {
        if (!OverlayController.canDraw(this)) {
            toast("需要先授予「悬浮窗」权限，才能把校准层盖在游戏上。");
            OverlayController.requestPermission(this);
            return;
        }
        AppPrefs.clearCalibration(this);
        Toast.makeText(getApplicationContext(),
                "请切到游戏，按提示依次点 9 个琴键的正中心", Toast.LENGTH_LONG).show();
        OverlayController.startCalibration(this, new OverlayController.CalibrationCallback() {
            @Override
            public void onFinished(boolean completed) {
                toast(completed ? "校准完成：9 个琴键坐标已保存" : "校准已取消");
                refreshPermissions();
            }
        });
    }

    /** Taps every calibrated pad once so the user can check the coordinates before a real run. */
    private void testPads() {
        if (!AppPrefs.hasCalibration(this)) {
            toast("还没校准完整，先点【校准琴键】。");
            return;
        }
        whenServiceReady(new Runnable() {
            @Override
            public void run() {
                runTestPads();
            }
        });
    }

    /**
     * Waits for the accessibility service to bind before testing.
     *
     * <p>Coming straight back from switching the service on used to fail here: the switch was on, the
     * binding had not arrived, and "not connected yet" was treated as "not enabled".
     */
    private void whenServiceReady(final Runnable action) {
        if (HandpanAccessibilityService.isReady()) {
            serviceWait = 0;
            action.run();
            return;
        }
        if (HandpanAccessibilityService.isEnabled(this) && serviceWait < SERVICE_WAIT_TRIES) {
            serviceWait++;
            toast("无障碍已开启，等它连上…");
            PERM.postDelayed(new Runnable() {
                @Override
                public void run() {
                    whenServiceReady(action);
                }
            }, SERVICE_WAIT_MS);
            return;
        }
        boolean enabled = HandpanAccessibilityService.isEnabled(this);
        serviceWait = 0;
        if (enabled) {
            tvPerms.setText("无障碍服务开着，但系统一直没把它连上。\n"
                    + "到系统设置的无障碍里把它【关掉再打开】一次，回来再点【试弹九个键】。");
            toast("无障碍没连上：去系统设置里关掉再打开一次。");
        } else {
            toast("无障碍服务未开启，无法试弹。先点上面的【开启无障碍服务】。");
        }
    }

    private void runTestPads() {
        List<Playback.Tap> taps = new ArrayList<Playback.Tap>();
        for (int slot = 0; slot < AppPrefs.SLOTS; slot++) {
            float[] p = AppPrefs.getPad(this, slot);
            if (p != null) {
                taps.add(new Playback.Tap(new float[]{p[0]}, new float[]{p[1]},
                        800L + slot * 700L));
            }
        }
        OverlayController.showStopButton(this, new Runnable() {
            @Override
            public void run() {
                Playback.stop();
                OverlayController.hideStopButton();
            }
        });
        Toast.makeText(getApplicationContext(),
                "试弹中：依次点按 9 个琴键，请切到游戏看哪个键没亮", Toast.LENGTH_LONG).show();
        Playback.start(taps, new Playback.Listener() {
            @Override
            public void onProgress(int done, int total) {
            }

            @Override
            public void onFinish(boolean completed) {
                OverlayController.hideStopButton();
                int dropped = Playback.dropped();
                if (dropped > 0) {
                    // The visible half of the silent failure: gestures the device refused outright.
                    Toast.makeText(getApplicationContext(),
                            "试弹结束，但有 " + dropped + " 下被系统拒绝了（没发到游戏）。\n"
                                    + "到系统设置里把无障碍服务关掉再打开一次。",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }
}
