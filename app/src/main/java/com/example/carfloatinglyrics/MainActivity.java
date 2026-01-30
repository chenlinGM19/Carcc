package com.example.carfloatinglyrics;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private static final String PREFS_NAME = "CarLyricsPrefs";
    
    private TextView tvStatus;
    private RadioGroup rgMode;
    private TextView tvAdbCommand;
    private Button btnCopyCommand;
    
    private SharedPreferences prefs;
    private boolean isRestoring = false;

    // Modes: 0 = Notification, 1 = Logcat Standard, 2 = Broadcast (Car Kit)
    private int selectedMode = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

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
        SeekBar seekBarTextOpacity = findViewById(R.id.seekBarTextOpacity);
        SeekBar seekBarBgOpacity = findViewById(R.id.seekBarBgOpacity);
        
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
                
                if (!isRestoring) {
                    prefs.edit().putInt("style_id", position).apply();
                    sendCommandToService(FloatingLyricService.ACTION_UPDATE_STYLE, "style_id", position);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // --- Listeners ---

        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbNotification) {
                selectedMode = 0;
            } else if (checkedId == R.id.rbLogcat) {
                selectedMode = 1;
            } else if (checkedId == R.id.rbBroadcast) {
                selectedMode = 2;
            }
            
            if (!isRestoring) {
                prefs.edit().putInt("mode", selectedMode).apply();
            }

            boolean needsLogcat = (selectedMode == 1);

            if (needsLogcat) {
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

            boolean needsLogcat = (selectedMode == 1);
            if (needsLogcat) {
                if (checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
                    tvAdbCommand.setVisibility(View.VISIBLE);
                    btnCopyCommand.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "Logcat permission missing. See command below.", Toast.LENGTH_LONG).show();
                    return;
                }
            } else {
                // Mode 0 requires Notification Listener
                // Mode 2 (Broadcast) does NOT require it (Lower Level Intent Listening)
                if (selectedMode == 0 && !isNotificationListenerEnabled()) {
                    Toast.makeText(this, "Please grant Notification Access to capture lyrics.", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    startActivity(intent);
                    return;
                }
            }
            startFloatingService(selectedMode);
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(MainActivity.this, FloatingLyricService.class));
            tvStatus.setText("Status: Stopped");
        });

        switchLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isRestoring) {
                prefs.edit().putBoolean("locked", isChecked).apply();
                sendCommandToService(FloatingLyricService.ACTION_TOGGLE_LOCK, "locked", isChecked);
            }
        });

        switchShow.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isRestoring) {
                prefs.edit().putBoolean("visible", isChecked).apply();
                sendCommandToService(FloatingLyricService.ACTION_TOGGLE_VISIBILITY, "visible", isChecked);
            }
        });

        switchClickThrough.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                switchLock.setChecked(true);
            }
            if (!isRestoring) {
                prefs.edit().putBoolean("click_through", isChecked).apply();
                sendCommandToService(FloatingLyricService.ACTION_TOGGLE_CLICK_THROUGH, "click_through", isChecked);
            }
        });

        seekBarSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!isRestoring) {
                    prefs.edit().putInt("size", progress).apply();
                    sendCommandToService(FloatingLyricService.ACTION_UPDATE_SIZE, "size", progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekBarTextOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!isRestoring) {
                    prefs.edit().putInt("text_opacity", progress).apply();
                    sendCommandToService(FloatingLyricService.ACTION_UPDATE_TEXT_OPACITY, "opacity", progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekBarBgOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!isRestoring) {
                    prefs.edit().putInt("bg_opacity", progress).apply();
                    sendCommandToService(FloatingLyricService.ACTION_UPDATE_BG_OPACITY, "opacity", progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Restore State
        restoreState(switchLock, switchShow, switchClickThrough, seekBarSize, seekBarTextOpacity, seekBarBgOpacity, spinnerStyle);
    }

    private void restoreState(Switch sLock, Switch sShow, Switch sClick, SeekBar sbSize, SeekBar sbTextOp, SeekBar sbBgOp, Spinner spinStyle) {
        isRestoring = true;

        sLock.setChecked(prefs.getBoolean("locked", false));
        sShow.setChecked(prefs.getBoolean("visible", true));
        sClick.setChecked(prefs.getBoolean("click_through", false));
        sbSize.setProgress(prefs.getInt("size", 18));
        sbTextOp.setProgress(prefs.getInt("text_opacity", 100));
        sbBgOp.setProgress(prefs.getInt("bg_opacity", 100));
        spinStyle.setSelection(prefs.getInt("style_id", 0));
        
        int mode = prefs.getInt("mode", 0);
        selectedMode = mode;
        if (mode == 2) {
            rgMode.check(R.id.rbBroadcast);
        } else if (mode == 1) {
            rgMode.check(R.id.rbLogcat);
        } else {
            rgMode.check(R.id.rbNotification);
        }

        isRestoring = false;

        // Check permission after restore
        if ((selectedMode == 1) && 
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

    private void startFloatingService(int mode) {
        Intent intent = new Intent(MainActivity.this, FloatingLyricService.class);
        intent.putExtra(FloatingLyricService.EXTRA_CAPTURE_MODE, mode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        
        String statusMode = "Notification";
        if (mode == 1) statusMode = "Logcat (LRC)";
        if (mode == 2) statusMode = "Broadcast (Car Kit)";
        tvStatus.setText("Status: Running (" + statusMode + ")");
    }

    private void sendCommandToService(String action, String extraKey, boolean value) {
        if (isRestoring) return;
        Intent intent = new Intent(this, FloatingLyricService.class);
        intent.setAction(action);
        intent.putExtra(extraKey, value);
        startService(intent);
    }

    private void sendCommandToService(String action, String extraKey, int value) {
        if (isRestoring) return;
        Intent intent = new Intent(this, FloatingLyricService.class);
        intent.setAction(action);
        intent.putExtra(extraKey, value);
        startService(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CODE_DRAW_OVER_OTHER_APP_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                boolean needsLogcat = (selectedMode == 1);
                if (needsLogcat) {
                     if (checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
                         startFloatingService(selectedMode);
                     }
                } else {
                    if (selectedMode == 0 && !isNotificationListenerEnabled()) {
                        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                        startActivity(intent);
                    } else {
                        startFloatingService(selectedMode);
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