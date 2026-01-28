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
    private Button btnLock, btnVisibility;
    private int currentTextSize;
    private boolean isLocked;
    private boolean isVisible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentTextSize = prefs.getInt("pref_font_size", 34);
        isLocked = prefs.getBoolean("pref_is_locked", false);
        isVisible = prefs.getBoolean("pref_is_visible", true);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF121212);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("CarLyrix v4.0 极速版");
        title.setTextSize(30);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF00E5FF);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("全协议兼容 · 蓝牙歌词助手");
        subtitle.setTextColor(0xFF888888);
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        layout.addView(subtitle);

        TextView tip = new TextView(this);
        tip.setText("提示：如果没歌词，长按悬浮窗切换捕获模式");
        tip.setTextColor(Color.YELLOW);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, 30, 0, 30);
        layout.addView(tip);

        btnLock = new Button(this);
        updateLockButton();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150);
        lp.setMargins(0, 10, 0, 20);
        btnLock.setLayoutParams(lp);
        btnLock.setOnClickListener(v -> toggleLock());
        layout.addView(btnLock);

        btnVisibility = new Button(this);
        updateVisibilityButton();
        btnVisibility.setLayoutParams(lp);
        btnVisibility.setOnClickListener(v -> toggleVisibility());
        layout.addView(btnVisibility);

        TextView sl = new TextView(this);
        sl.setText("歌词字号调整");
        sl.setTextColor(Color.WHITE);
        sl.setPadding(0, 40, 0, 10);
        layout.addView(sl);

        LinearLayout sc = new LinearLayout(this);
        sc.setOrientation(LinearLayout.HORIZONTAL);
        sc.setGravity(Gravity.CENTER);

        Button bm = createSquareButton("-", v -> updateFontSize(-2));
        Button bp = createSquareButton("+", v -> updateFontSize(2));
        sizeDisplay = new TextView(this);
        sizeDisplay.setText(String.valueOf(currentTextSize));
        sizeDisplay.setTextColor(Color.WHITE);
        sizeDisplay.setTextSize(24);
        sizeDisplay.setGravity(Gravity.CENTER);
        sizeDisplay.setWidth(150);

        sc.addView(bm);
        sc.addView(sizeDisplay);
        sc.addView(bp);
        layout.addView(sc);

        layout.addView(createPermButton("1. 授予悬浮窗权限", v -> requestOverlayPermission()));
        layout.addView(createPermButton("2. 授予通知抓取权限", v -> requestNotificationAccess()));

        TextView footer = new TextView(this);
        footer.setText("Version 4.0.0 Stable\n针对 AVRCP 1.3/1.4/1.6 优化");
        footer.setTextColor(0xFF555555);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, 60, 0, 20);
        layout.addView(footer);

        scroll.addView(layout);
        setContentView(scroll);
    }

    private void toggleLock() {
        isLocked = !isLocked;
        prefs.edit().putBoolean("pref_is_locked", isLocked).apply();
        updateLockButton();
        sendToService("ACTION_UPDATE_LOCK_STATE", "locked", isLocked);
    }

    private void toggleVisibility() {
        isVisible = !isVisible;
        prefs.edit().putBoolean("pref_is_visible", isVisible).apply();
        updateVisibilityButton();
        sendToService("ACTION_UPDATE_VISIBILITY", "visible", isVisible);
    }

    private void updateFontSize(int delta) {
        currentTextSize = Math.max(12, Math.min(90, currentTextSize + delta));
        sizeDisplay.setText(String.valueOf(currentTextSize));
        prefs.edit().putInt("pref_font_size", currentTextSize).apply();
        sendToService("ACTION_UPDATE_CONFIG", "font_size", currentTextSize);
    }

    private void sendToService(String action, String key, Object value) {
        Intent i = new Intent(this, LyricsOverlayService.class);
        i.setAction(action);
        if (value instanceof Boolean) i.putExtra(key, (Boolean) value);
        else if (value instanceof Integer) i.putExtra(key, (Integer) value);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            Log.e("CarLyrix", "Service Error: " + e.getMessage());
        }
    }

    private void updateLockButton() {
        btnLock.setText(isLocked ? "已锁定 (穿透模式: 可点击地图)" : "已解锁 (移动模式: 可拖动)");
        btnLock.setBackgroundColor(isLocked ? 0xFF388E3C : 0xFF1976D2);
        btnLock.setTextColor(Color.WHITE);
    }

    private void updateVisibilityButton() {
        btnVisibility.setText(isVisible ? "隐藏悬浮窗" : "显示悬浮窗");
        btnVisibility.setBackgroundColor(0xFF455A64);
        btnVisibility.setTextColor(Color.WHITE);
    }

    private Button createSquareButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(140, 140));
        return b;
    }

    private Button createPermButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120);
        lp.setMargins(0, 40, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } else Toast.makeText(this, "权限已获得", Toast.LENGTH_SHORT).show();
    }

    private void requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }
}
