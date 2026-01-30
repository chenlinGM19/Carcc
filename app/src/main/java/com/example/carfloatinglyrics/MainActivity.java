package com.example.carfloatinglyrics;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int CODE_DRAW_OVER_OTHER_APP_PERMISSION = 2084;
    private TextView tvStatus;
    private RadioGroup rgMode;
    private TextView tvAdbCommand;
    private Button btnCopyCommand;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvAdbCommand = findViewById(R.id.tvAdbCommand);
        btnCopyCommand = findViewById(R.id.btnCopyCommand);
        rgMode = findViewById(R.id.rgMode);
        
        Button btnStart = findViewById(R.id.btnStartService);
        Button btnStop = findViewById(R.id.btnStopService);
        Switch switchLock = findViewById(R.id.switchLockPosition);
        Switch switchShow = findViewById(R.id.switchShowLyrics);
        Switch switchClickThrough = findViewById(R.id.switchClickThrough);
        SeekBar seekBarSize = findViewById(R.id.seekBarSize);
        SeekBar seekBarOpacity = findViewById(R.id.seekBarOpacity);
        Spinner spinnerStyle = findViewById(R.id.spinnerStyle);

        // --- Style Spinner Setup ---
        String[] styles = {
            "0. Classic (Dark Translucent)", 
            "1. Minimal (No BG, White)", 
            "2. Neon (Cyberpunk)", 
            "3. Retro (Terminal Green)", 
            "4. Day Mode (Black on White)",
            "5. Karaoke (Gold, No BG)"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, styles);
        spinnerStyle.setAdapter(adapter);
        spinnerStyle.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Ensure text is white so it's visible on dark background
                if(parent.getChildAt(0) != null) {
                    ((TextView) parent.getChildAt(0)).setTextColor(0xFFFFFFFF);
                }
                sendCommandToService(FloatingLyricService.ACTION_UPDATE_STYLE, "style_id", position);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // --- Listeners ---

        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbLogcat) {
                if (checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
                    tvAdbCommand.setVisibility(View.VISIBLE);
                    btnCopyCommand.setVisibility(View.VISIBLE);
                } else {
                    tvAdbCommand.setVisibility(View.GONE);
                    btnCopyCommand.setVisibility(View.GONE);
                }
            } else {
                tvAdbCommand.setVisibility(View.GONE);
                btnCopyCommand.setVisibility(View.GONE);
            }
        });

        btnCopyCommand.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("ADB Command", "adb shell pm grant com.example.carfloatinglyrics android.permission.READ_LOGS");
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Command copied to clipboard", Toast.LENGTH_SHORT).show();
        });

        btnStart.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, CODE_DRAW_OVER_OTHER_APP_PERMISSION);
                return;
            }

            boolean isLogcatMode = (rgMode.getCheckedRadioButtonId() == R.id.rbLogcat);
            if (isLogcatMode) {
                if (checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
                    tvAdbCommand.setVisibility(View.VISIBLE);
                    btnCopyCommand.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "Logcat permission missing. See command below.", Toast.LENGTH_LONG).show();
                    return;
                }
            } else {
                if (!isNotificationListenerEnabled()) {
                    Toast.makeText(this, "Please grant Notification Access to capture lyrics.", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    startActivity(intent);
                    return;
                }
            }
            startFloatingService(isLogcatMode);
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(MainActivity.this, FloatingLyricService.class));
            tvStatus.setText("Status: Stopped");
        });

        switchLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sendCommandToService(FloatingLyricService.ACTION_TOGGLE_LOCK, "locked", isChecked);
        });

        switchShow.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sendCommandToService(FloatingLyricService.ACTION_TOGGLE_VISIBILITY, "visible", isChecked);
        });

        switchClickThrough.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Note: Enabling Click-Through usually implies Locking position too
            if (isChecked) {
                switchLock.setChecked(true);
            }
            sendCommandToService(FloatingLyricService.ACTION_TOGGLE_CLICK_THROUGH, "click_through", isChecked);
        });

        seekBarSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sendCommandToService(FloatingLyricService.ACTION_UPDATE_SIZE, "size", progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekBarOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sendCommandToService(FloatingLyricService.ACTION_UPDATE_OPACITY, "opacity", progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Initial permission check
        if (rgMode.getCheckedRadioButtonId() == R.id.rbLogcat && 
            checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            tvAdbCommand.setVisibility(View.VISIBLE);
            btnCopyCommand.setVisibility(View.VISIBLE);
        } else {
            btnCopyCommand.setVisibility(View.GONE);
        }
    }

    private boolean isNotificationListenerEnabled() {
        ComponentName cn = new ComponentName(this, LyricListenerService.class);
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(cn.flattenToString());
    }

    private void startFloatingService(boolean useLogcat) {
        Intent intent = new Intent(MainActivity.this, FloatingLyricService.class);
        intent.putExtra(FloatingLyricService.EXTRA_MODE_LOGCAT, useLogcat);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        tvStatus.setText("Status: Running (" + (useLogcat ? "Logcat" : "Notification") + ")");
    }

    private void sendCommandToService(String action, String extraKey, boolean value) {
        Intent intent = new Intent(this, FloatingLyricService.class);
        intent.setAction(action);
        intent.putExtra(extraKey, value);
        startService(intent);
    }

    private void sendCommandToService(String action, String extraKey, int value) {
        Intent intent = new Intent(this, FloatingLyricService.class);
        intent.setAction(action);
        intent.putExtra(extraKey, value);
        startService(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CODE_DRAW_OVER_OTHER_APP_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                boolean isLogcatMode = (rgMode.getCheckedRadioButtonId() == R.id.rbLogcat);
                if (isLogcatMode) {
                     if (checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
                         startFloatingService(true);
                     }
                } else {
                    if (!isNotificationListenerEnabled()) {
                        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                        startActivity(intent);
                    } else {
                        startFloatingService(false);
                    }
                }
            } else {
                Toast.makeText(this, "Overlay permission denied.", Toast.LENGTH_SHORT).show();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}