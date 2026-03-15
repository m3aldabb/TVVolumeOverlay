package com.volumeoverlay;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME  = "volume_prefs";
    private static final String KEY_VOLUME  = "volume_level";
    private static final String KEY_MAX_VOL = "max_volume";
    private static final String KEY_STYLE   = "overlay_style";
    private static final int    DEFAULT_MAX = 100;

    private TextView statusText;
    private TextView statusDot;
    private TextView currentVolumeDisplay;
    private TextView maxVolumeDisplay;
    private TextView styleDisplay;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusChecker;

    // Style options shown to user
    private static final String[] STYLE_LABELS = {
        "H1 — Strip + VOL above",
        "H2 — Strip + vol beside",
        "H3 — Strip, big number",
        "H6 — Ghost (ultra minimal)",
        "Pill — Dark box with icon",
        "Minimal — Floating number",
        "Circle — Ring progress",
        "Stacked — Right aligned",
        "Capsule — Horizontal bar",
        "Chip — Left border accent"
    };
    private static final String[] STYLE_KEYS = {
        "H1", "H2", "H3", "H6", "PILL", "MINIMAL",
        "CIRCLE", "STACKED", "CAPSULE", "CHIP"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText           = findViewById(R.id.status_text);
        statusDot            = findViewById(R.id.status_dot);
        currentVolumeDisplay = findViewById(R.id.current_volume_display);
        maxVolumeDisplay     = findViewById(R.id.max_volume_display);
        styleDisplay         = findViewById(R.id.style_display);

        findViewById(R.id.btn_enable).setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );

        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            saveVolume(0);
            currentVolumeDisplay.setText("0");
            sendBroadcast(new Intent(VolumeAccessibilityService.ACTION_RELOAD).setPackage(getPackageName()));
            Toast.makeText(this, "Volume reset to 0", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_set_volume).setOnClickListener(v -> showSetVolumeDialog());
        findViewById(R.id.btn_set_max).setOnClickListener(v -> showSetMaxVolumeDialog());
        findViewById(R.id.btn_set_style).setOnClickListener(v -> showStyleDialog());

        findViewById(R.id.github_link).setOnClickListener(v -> {
            android.net.Uri uri = android.net.Uri.parse("https://github.com/m3aldabb");
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, uri));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        updateVolumeDisplay();
        updateStyleDisplay();
        statusChecker = new Runnable() {
            @Override public void run() {
                updateVolumeDisplay();
                handler.postDelayed(this, 500);
            }
        };
        handler.post(statusChecker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (statusChecker != null) handler.removeCallbacks(statusChecker);
    }

    private void updateStatus() {
        boolean active = isServiceEnabled();
        statusText.setText(active ? "Service is active — overlay ready" : "Service not active — tap Enable");
        statusText.setTextColor(active ? 0xFF00E5FF : 0xFFAAAAAA);
        statusDot.setBackgroundResource(active ? R.drawable.dot_active : R.drawable.dot_inactive);
    }

    private void updateVolumeDisplay() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int vol = prefs.getInt(KEY_VOLUME, 0);
        int max = prefs.getInt(KEY_MAX_VOL, DEFAULT_MAX);
        currentVolumeDisplay.setText(String.valueOf(vol));
        maxVolumeDisplay.setText("/ " + max);
    }

    private void updateStyleDisplay() {
        String key = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_STYLE, "H1");
        for (int i = 0; i < STYLE_KEYS.length; i++) {
            if (STYLE_KEYS[i].equals(key)) {
                styleDisplay.setText(STYLE_LABELS[i]);
                return;
            }
        }
        styleDisplay.setText("H1 — Strip + VOL above");
    }

    private boolean isServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabled =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) return true;
        }
        return false;
    }

    private void saveVolume(int level) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(KEY_VOLUME, level).apply();
    }

    private void showSetVolumeDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int current = prefs.getInt(KEY_VOLUME, 0);
        int max     = prefs.getInt(KEY_MAX_VOL, DEFAULT_MAX);
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        input.setHint("0 – " + max);
        new AlertDialog.Builder(this)
            .setTitle("Set Current Volume")
            .setMessage("Enter your current volume (0–" + max + "):")
            .setView(input)
            .setPositiveButton("Set", (d, w) -> {
                String val = input.getText().toString().trim();
                if (!val.isEmpty()) {
                    int level = Math.max(0, Math.min(max, Integer.parseInt(val)));
                    saveVolume(level);
                    updateVolumeDisplay();
                    sendBroadcast(new Intent(VolumeAccessibilityService.ACTION_RELOAD).setPackage(getPackageName()));
                    Toast.makeText(this, "Volume set to " + level, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void showSetMaxVolumeDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int currentMax = prefs.getInt(KEY_MAX_VOL, DEFAULT_MAX);
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(currentMax));
        input.setHint("e.g. 50 or 100");
        new AlertDialog.Builder(this)
            .setTitle("Set Max Volume")
            .setMessage("How many steps does your soundbar/TV have? (e.g. 50, 40, 100)\n\nThe display will scale automatically to match.")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String val = input.getText().toString().trim();
                if (!val.isEmpty()) {
                    int newMax = Math.max(1, Math.min(300, Integer.parseInt(val)));
                    prefs.edit().putInt(KEY_MAX_VOL, newMax).apply();
                    int curVol = prefs.getInt(KEY_VOLUME, 0);
                    if (curVol > newMax) saveVolume(newMax);
                    updateVolumeDisplay();
                    sendBroadcast(new Intent(VolumeAccessibilityService.ACTION_RELOAD).setPackage(getPackageName()));
                    Toast.makeText(this, "Max set to " + newMax, Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void showStyleDialog() {
        String currentKey = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_STYLE, "H1");
        int currentIdx = 0;
        for (int i = 0; i < STYLE_KEYS.length; i++) {
            if (STYLE_KEYS[i].equals(currentKey)) { currentIdx = i; break; }
        }
        new AlertDialog.Builder(this)
            .setTitle("Overlay Style")
            .setSingleChoiceItems(STYLE_LABELS, currentIdx, (d, which) -> {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(KEY_STYLE, STYLE_KEYS[which]).apply();
                updateStyleDisplay();
                sendBroadcast(new Intent(VolumeAccessibilityService.ACTION_RESTYLE).setPackage(getPackageName()));
                Toast.makeText(this, "Style changed — press volume to preview", Toast.LENGTH_SHORT).show();
                d.dismiss();
            })
            .setNegativeButton("Cancel", null).show();
    }
}
