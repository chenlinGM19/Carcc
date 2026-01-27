package com.carlyrix.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
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

        // Instructions
        TextView instructions = new TextView(this);
        instructions.setText("Grant permissions below. Once active, this app will show lyrics floating over your map or launcher when you play music from your phone.");
        instructions.setTextColor(Color.WHITE);
        instructions.setTextSize(16);
        instructions.setGravity(Gravity.CENTER);
        instructions.setPadding(20, 0, 20, 40);
        layout.addView(instructions);

        // Buttons
        layout.addView(createButton("1. ALLOW OVERLAY", 0xFF2E7D32, v -> requestOverlayPermission()));
        
        // Spacer
        layout.addView(new View(this), new LinearLayout.LayoutParams(1, 30));

        layout.addView(createButton("2. ALLOW MEDIA ACCESS", 0xFF1565C0, v -> requestNotificationAccess()));

        // Status / Footer
        TextView footer = new TextView(this);
        footer.setText("\nGestures:\nDouble Tap: Change Style\nDrag: Move Position\nLong Press: Change Mode");
        footer.setTextColor(0xFF757575);
        footer.setTextSize(14);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, 40, 0, 0);
        layout.addView(footer);

        scroll.addView(layout);
        setContentView(scroll);
    }

    private Button createButton(String text, int color, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(18);
        btn.setBackgroundColor(color);
        btn.setPadding(30, 20, 30, 20);
        
        // Layout params for big easy-to-hit buttons
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                140 // Fixed height for ease of use in car
        );
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