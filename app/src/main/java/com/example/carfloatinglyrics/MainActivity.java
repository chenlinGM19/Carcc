package com.example.carfloatinglyrics;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int CODE_DRAW_OVER_OTHER_APP_PERMISSION = 2084;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        Button btnStart = findViewById(R.id.btnStartService);
        Button btnStop = findViewById(R.id.btnStopService);
        Switch switchLock = findViewById(R.id.switchLockPosition);
        Switch switchShow = findViewById(R.id.switchShowLyrics);
        SeekBar seekBarSize = findViewById(R.id.seekBarSize);

        btnStart.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, CODE_DRAW_OVER_OTHER_APP_PERMISSION);
            } else {
                startFloatingService();
            }
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

        seekBarSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sendCommandToService(FloatingLyricService.ACTION_UPDATE_SIZE, "size", progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void startFloatingService() {
        Intent intent = new Intent(MainActivity.this, FloatingLyricService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        tvStatus.setText("Status: Running");
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
                startFloatingService();
            } else {
                Toast.makeText(this, "Permission denied. App cannot display overlay.", Toast.LENGTH_SHORT).show();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}