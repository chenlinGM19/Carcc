package com.carlyrix.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView sizeDisplay;
    private int currentTextSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentTextSize = prefs.getInt("pref_font_size", 34); // Default 34

        // Dark theme background
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF121212);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        // Header
        TextView title = new TextView(this);
        title.setText("CarLyrix");
        title.setTextSize(32);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF00E5FF); // Cyan Accent
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Bluetooth Music Overlay");
        subtitle.setTextSize(16);
        subtitle.setTextColor(0xFFB0BEC5);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, 30);
        layout.addView(subtitle);

        // --- Service Power Control ---
        TextView powerLabel = new TextView(this);
        powerLabel.setText("Service Power");
        powerLabel.setTextColor(Color.WHITE);
        powerLabel.setTextSize(18);
        powerLabel.setPadding(0, 10, 0, 10);
        powerLabel.setGravity(Gravity.CENTER);
        layout.addView(powerLabel);

        LinearLayout powerLayout = new LinearLayout(this);
        powerLayout.setOrientation(LinearLayout.HORIZONTAL);
        powerLayout.setGravity(Gravity.CENTER);
        powerLayout.setPadding(0, 0, 0, 40);

        // Use weight to split width 50/50
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, 140, 1.0f
        );
        btnParams.setMargins(10, 0, 10, 0);

        Button btnStop = new Button(this);
        btnStop.setText("STOP / OFF");
        btnStop.setTextColor(Color.WHITE);
        btnStop.setBackgroundColor(0xFFD32F2F); // Red
        btnStop.setLayoutParams(btnParams);
        btnStop.setOnClickListener(v -> stopLyricsService());

        Button btnStart = new Button(this);
        btnStart.setText("START / ON");
        btnStart.setTextColor(Color.WHITE);
        btnStart.setBackgroundColor(0xFF388E3C); // Green
        btnStart.setLayoutParams(btnParams);
        btnStart.setOnClickListener(v -> startLyricsService());

        powerLayout.addView(btnStop);
        powerLayout.addView(btnStart);
        layout.addView(powerLayout);
        // -----------------------------

        // --- Font Size Control Section ---
        TextView sizeLabel = new TextView(this);
        sizeLabel.setText("Adjust Text Size");
        sizeLabel.setTextColor(Color.WHITE);
        sizeLabel.setTextSize(18);
        sizeLabel.setPadding(0, 10, 0, 10);
        sizeLabel.setGravity(Gravity.CENTER);
        layout.addView(sizeLabel);

        LinearLayout sizeControlLayout = new LinearLayout(this);
        sizeControlLayout.setOrientation(LinearLayout.HORIZONTAL);
        sizeControlLayout.setGravity(Gravity.CENTER);
        sizeControlLayout.setPadding(0, 0, 0, 40);

        Button btnMinus = createSquareButton("-", 0xFF424242, v -> updateFontSize(-2));
        Button btnPlus = createSquareButton("+", 0xFF424242, v -> updateFontSize(2));
        
        sizeDisplay = new TextView(this);
        sizeDisplay.setText(String.valueOf(currentTextSize));
        sizeDisplay.setTextColor(Color.WHITE);
        sizeDisplay.setTextSize(24);
        sizeDisplay.setTypeface(null, Typeface.BOLD);
        sizeDisplay.setGravity(Gravity.CENTER);
        sizeDisplay.setLayoutParams(new LinearLayout.LayoutParams(150, ViewGroup.LayoutParams.MATCH_PARENT));

        sizeControlLayout.addView(btnMinus);
        sizeControlLayout.addView(sizeDisplay);
        sizeControlLayout.addView(btnPlus);
        layout.addView(sizeControlLayout);
        // ---------------------------------

        // Instructions
        TextView instructions = new TextView(this);
        instructions.setText("Grant permissions below. Lyrics will float over maps/launcher.");
        instructions.setTextColor(Color.LTGRAY);
        instructions.setTextSize(14);
        instructions.setGravity(Gravity.CENTER);
        instructions.setPadding(20, 0, 20, 20);
        layout.addView(instructions);

        // Main Action Buttons
        layout.addView(createButton("1. ALLOW OVERLAY", 0xFF1565C0, v -> requestOverlayPermission()));
        
        layout.addView(new View(this), new LinearLayout.LayoutParams(1, 30));

        layout.addView(createButton("2. ALLOW MEDIA ACCESS", 0xFF1565C0, v -> requestNotificationAccess()));

        // Status / Footer
        TextView footer = new TextView(this);
        footer.setText("\nGestures:\nDouble Tap: Change Color\nDrag: Move Position\nLong Press: Change Mode");
        footer.setTextColor(0xFF757575);
        footer.setTextSize(14);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, 20, 0, 0);
        layout.addView(footer);

        scroll.addView(layout);
        setContentView(scroll);
    }

    private void startLyricsService() {
        Intent intent = new Intent(this, LyricsOverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Service Started", Toast.LENGTH_SHORT).show();
    }

    private void stopLyricsService() {
        stopService(new Intent(this, LyricsOverlayService.class));
        Toast.makeText(this, "Service Stopped", Toast.LENGTH_SHORT).show();
    }

    private void updateFontSize(int delta) {
        currentTextSize += delta;
        if (currentTextSize < 12) currentTextSize = 12;
        if (currentTextSize > 90) currentTextSize = 90;

        sizeDisplay.setText(String.valueOf(currentTextSize));
        
        // Save to Prefs
        prefs.edit().putInt("pref_font_size", currentTextSize).apply();

        // Notify Service immediately
        Intent intent = new Intent(this, LyricsOverlayService.class);
        intent.setAction("ACTION_UPDATE_CONFIG");
        intent.putExtra("font_size", currentTextSize);
        startService(intent);
    }

    private Button createButton(String text, int color, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(18);
        btn.setBackgroundColor(color);
        btn.setPadding(30, 20, 30, 20);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                140 
        );
        btn.setLayoutParams(params);
        btn.setOnClickListener(listener);
        return btn;
    }

    private Button createSquareButton(String text, int color, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(24);
        btn.setBackgroundColor(color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(120, 120);
        btn.setLayoutParams(params);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "Overlay Allowed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        } else {
            Toast.makeText(this, "Not supported on this Android version", Toast.LENGTH_SHORT).show();
        }
    }
}
