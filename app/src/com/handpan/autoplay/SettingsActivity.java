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
        if (!HandpanAccessibilityService.isReady()) {
            toast("无障碍服务未开启，无法试弹。");
            return;
        }
        if (!AppPrefs.hasCalibration(this)) {
            toast("还没校准完整，先点【校准琴键】。");
            return;
        }
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
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }
}
