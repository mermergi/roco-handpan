package com.handpan.autoplay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings screen: permissions, calibration, and the playback parameters.
 *
 * <p>Split out of the single original screen so the play screen only shows what is needed to start a
 * run. Parameters are written to {@link AppPrefs} when this screen pauses, which is why the play
 * screen can read them without any direct coupling.
 */
public class SettingsActivity extends Activity {

    private TextView tvPerms;
    private Spinner spKey;
    private Spinner spChord;
    private EditText etBpm;
    private EditText etSpeed;
    private CheckBox cbZero;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle("设置");

        tvPerms = (TextView) findViewById(R.id.tv_perms);
        spKey = (Spinner) findViewById(R.id.sp_key);
        spChord = (Spinner) findViewById(R.id.sp_chord);
        etBpm = (EditText) findViewById(R.id.et_bpm);
        etSpeed = (EditText) findViewById(R.id.et_speed);
        cbZero = (CheckBox) findViewById(R.id.cb_zero);

        ArrayAdapter<String> keys = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, KeyDetector.KEYS);
        keys.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spKey.setAdapter(keys);

        List<String> chords = new ArrayList<String>();
        for (int i = 0; i < AppPrefs.MAX_CHORD_OPTIONS.length; i++) {
            int n = AppPrefs.MAX_CHORD_OPTIONS[i];
            chords.add(n + " 个键" + (n == AppPrefs.DEFAULT_CHORD ? "（默认）" : ""));
        }
        ArrayAdapter<String> chordAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, chords);
        chordAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spChord.setAdapter(chordAdapter);

        loadFromPrefs();

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
        loadFromPrefs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveToPrefs();
    }

    private void loadFromPrefs() {
        String key = AppPrefs.getKey(this);
        for (int i = 0; i < KeyDetector.KEYS.length; i++) {
            if (KeyDetector.KEYS[i].equals(key)) spKey.setSelection(i);
        }
        int chord = AppPrefs.getChordLimit(this);
        for (int i = 0; i < AppPrefs.MAX_CHORD_OPTIONS.length; i++) {
            if (AppPrefs.MAX_CHORD_OPTIONS[i] == chord) spChord.setSelection(i);
        }
        etBpm.setText(String.valueOf(AppPrefs.getBpm(this)));
        etSpeed.setText(String.valueOf(AppPrefs.getSpeed(this)));
        cbZero.setChecked(AppPrefs.getUseZeroPad(this));
    }

    private void saveToPrefs() {
        int keyIndex = spKey.getSelectedItemPosition();
        if (keyIndex >= 0 && keyIndex < KeyDetector.KEYS.length) {
            AppPrefs.setKey(this, KeyDetector.KEYS[keyIndex]);
        }
        int chordIndex = spChord.getSelectedItemPosition();
        if (chordIndex >= 0 && chordIndex < AppPrefs.MAX_CHORD_OPTIONS.length) {
            AppPrefs.setChordLimit(this, AppPrefs.MAX_CHORD_OPTIONS[chordIndex]);
        }
        AppPrefs.setBpm(this, (int) number(etBpm.getText().toString(), 90f, 20f, 400f));
        AppPrefs.setSpeed(this, number(etSpeed.getText().toString(), 1.0f, 0.25f, 4.0f));
        AppPrefs.setUseZeroPad(this, cbZero.isChecked());
    }

    private static float number(String text, float fallback, float min, float max) {
        try {
            float v = Float.parseFloat(text.trim());
            if (v < min) v = min;
            if (v > max) v = max;
            return v;
        } catch (RuntimeException e) {
            return fallback;
        }
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
