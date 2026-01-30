package com.example.carfloatinglyrics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FloatingLyricService extends Service {

    public static final String ACTION_TOGGLE_LOCK = "com.example.carfloatinglyrics.TOGGLE_LOCK";
    public static final String ACTION_TOGGLE_VISIBILITY = "com.example.carfloatinglyrics.TOGGLE_VISIBILITY";
    public static final String ACTION_UPDATE_SIZE = "com.example.carfloatinglyrics.UPDATE_SIZE";
    
    // New Actions
    public static final String ACTION_UPDATE_STYLE = "com.example.carfloatinglyrics.UPDATE_STYLE";
    public static final String ACTION_UPDATE_BG_OPACITY = "com.example.carfloatinglyrics.UPDATE_BG_OPACITY";
    public static final String ACTION_UPDATE_TEXT_OPACITY = "com.example.carfloatinglyrics.UPDATE_TEXT_OPACITY";
    public static final String ACTION_TOGGLE_CLICK_THROUGH = "com.example.carfloatinglyrics.TOGGLE_CLICK_THROUGH";
    
    public static final String EXTRA_CAPTURE_MODE = "capture_mode";
    public static final String EXTRA_MODE_LOGCAT = "mode_logcat"; // Legacy support

    public static final int MODE_NOTIFICATION = 0;
    public static final int MODE_LOGCAT_STANDARD = 1;
    public static final int MODE_BROADCAST_CARKIT = 2; // Was Bluetooth Logcat, now Broadcast

    private static final String PREFS_NAME = "CarLyricsPrefs";

    private WindowManager mWindowManager;
    private View mFloatingView;
    private View mRootContainer;
    private TextView mLyricText;
    private ImageView mIcon;
    private WindowManager.LayoutParams params;
    private SharedPreferences prefs;
    
    // State
    private boolean isLocked = false;
    private boolean isViewAdded = false;
    private boolean isClickThrough = false;
    private int currentCaptureMode = 0;
    
    // Independent Opacity States
    private int currentBgAlpha = 255; // 0-255
    private float currentTextAlpha = 1.0f; // 0.0 - 1.0
    private int currentStyleId = 0;
    
    private LyricUpdateReceiver lyricReceiver;
    private CarBroadcastReceiver carBroadcastReceiver;
    private boolean isReceiverRegistered = false;
    private boolean isCarBroadcastRegistered = false;

    private Thread logcatThread;
    private volatile boolean isLogcatRunning = false;
    private Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        startMyForeground();

        try {
            mFloatingView = LayoutInflater.from(this).inflate(R.layout.window_floating_lyric, null);
            mRootContainer = mFloatingView.findViewById(R.id.root_container);
            mLyricText = mFloatingView.findViewById(R.id.tv_floating_lyric);
            mIcon = mFloatingView.findViewById(R.id.iv_icon);

            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutType = WindowManager.LayoutParams.TYPE_PHONE;
            }

            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);

            params.gravity = Gravity.TOP | Gravity.START;
            
            // Load saved position
            params.x = prefs.getInt("win_x", 100);
            params.y = prefs.getInt("win_y", 100);

            mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                // Permission missing
            } else {
                if (mWindowManager != null) {
                    mWindowManager.addView(mFloatingView, params);
                    isViewAdded = true;
                    
                    // Initialize States from Prefs
                    initServiceState();
                    
                    setupTouchListener();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void initServiceState() {
        // Size
        int size = prefs.getInt("size", 18);
        mLyricText.setTextSize(size);

        // Lock & ClickThrough
        isLocked = prefs.getBoolean("locked", false);
        boolean clickThrough = prefs.getBoolean("click_through", false);
        updateClickThrough(clickThrough);

        // Visibility
        boolean visible = prefs.getBoolean("visible", true);
        mFloatingView.setVisibility(visible ? View.VISIBLE : View.GONE);

        // Style & Opacity
        currentStyleId = prefs.getInt("style_id", 0);
        currentBgAlpha = (int) (prefs.getInt("bg_opacity", 100) * 2.55);
        currentTextAlpha = prefs.getInt("text_opacity", 100) / 100.0f;
        
        // Apply Style (this also applies opacity)
        updateStyle(currentStyleId);
    }

    private void setupTouchListener() {
        mFloatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // If locked or click-through is enabled, ignore move logic
                if (isLocked || isClickThrough) return false;

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_UP:
                        // Save position
                        if (isViewAdded) {
                            prefs.edit()
                                .putInt("win_x", params.x)
                                .putInt("win_y", params.y)
                                .apply();
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        try {
                            if (isViewAdded && mWindowManager != null) {
                                mWindowManager.updateViewLayout(mFloatingView, params);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            
            // Handle Mode Switching
            if (intent.hasExtra(EXTRA_CAPTURE_MODE)) {
                int mode = intent.getIntExtra(EXTRA_CAPTURE_MODE, MODE_NOTIFICATION);
                switchCaptureMode(mode);
            } else if (intent.hasExtra(EXTRA_MODE_LOGCAT)) {
                // Legacy boolean support
                boolean useLogcat = intent.getBooleanExtra(EXTRA_MODE_LOGCAT, false);
                switchCaptureMode(useLogcat ? MODE_LOGCAT_STANDARD : MODE_NOTIFICATION);
            }
            
            if (intent.getAction() != null) {
                switch (intent.getAction()) {
                    case ACTION_TOGGLE_LOCK:
                        isLocked = intent.getBooleanExtra("locked", false);
                        break;
                    case ACTION_TOGGLE_VISIBILITY:
                        boolean visible = intent.getBooleanExtra("visible", true);
                        if (isViewAdded && mFloatingView != null) {
                            mFloatingView.setVisibility(visible ? View.VISIBLE : View.GONE);
                        }
                        break;
                    case ACTION_UPDATE_SIZE:
                        int size = intent.getIntExtra("size", 18);
                        if (isViewAdded && mLyricText != null) {
                            mLyricText.setTextSize(size);
                            // Ensure the window resizes to wrap the content
                            if (mWindowManager != null) {
                                mWindowManager.updateViewLayout(mFloatingView, params);
                            }
                        }
                        break;
                        
                    case ACTION_UPDATE_STYLE:
                        currentStyleId = intent.getIntExtra("style_id", 0);
                        updateStyle(currentStyleId);
                        break;

                    case ACTION_UPDATE_BG_OPACITY:
                        // Seekbar 0-100 -> Alpha 0-255
                        int bgProgress = intent.getIntExtra("opacity", 100);
                        currentBgAlpha = (int) (bgProgress * 2.55);
                        updateBackgroundOpacity();
                        break;

                    case ACTION_UPDATE_TEXT_OPACITY:
                        // Seekbar 0-100 -> Alpha 0.0-1.0
                        int textProgress = intent.getIntExtra("opacity", 100);
                        currentTextAlpha = textProgress / 100.0f;
                        updateTextOpacity();
                        break;

                    case ACTION_TOGGLE_CLICK_THROUGH:
                        boolean enable = intent.getBooleanExtra("click_through", false);
                        updateClickThrough(enable);
                        break;
                }
            }
        }
        return START_STICKY;
    }

    private void updateClickThrough(boolean enable) {
        if (!isViewAdded || mWindowManager == null) return;
        
        isClickThrough = enable;
        if (enable) {
            // Add FLAG_NOT_TOUCHABLE to let events pass through
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            // Remove FLAG_NOT_TOUCHABLE to intercept events
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        
        try {
            mWindowManager.updateViewLayout(mFloatingView, params);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateBackgroundOpacity() {
        if (mRootContainer != null && mRootContainer.getBackground() != null) {
            mRootContainer.getBackground().setAlpha(currentBgAlpha);
        }
    }

    private void updateTextOpacity() {
        if (mLyricText != null) {
            mLyricText.setAlpha(currentTextAlpha);
        }
        if (mIcon != null) {
            mIcon.setAlpha(currentTextAlpha);
        }
    }

    private void updateStyle(int styleId) {
        if (!isViewAdded || mRootContainer == null) return;

        // Reset basic properties
        mLyricText.setShadowLayer(0, 0, 0, 0);
        mLyricText.setTypeface(Typeface.DEFAULT);
        mIcon.setVisibility(View.VISIBLE);
        mIcon.setColorFilter(Color.parseColor("#00E5FF"));

        switch (styleId) {
            case 0: // Classic (Default)
                mRootContainer.setBackgroundResource(R.drawable.bg_floating_window);
                mLyricText.setTextColor(Color.WHITE);
                mLyricText.setShadowLayer(4, 2, 2, Color.BLACK);
                break;

            case 1: // Minimal (No BG)
                mRootContainer.setBackground(null);
                mLyricText.setTextColor(Color.WHITE);
                // Strong shadow for visibility
                mLyricText.setShadowLayer(8, 0, 0, Color.BLACK);
                break;

            case 2: // Neon (Cyberpunk)
                mRootContainer.setBackgroundResource(R.drawable.bg_style_neon);
                mLyricText.setTextColor(Color.parseColor("#00E5FF"));
                mLyricText.setShadowLayer(10, 0, 0, Color.parseColor("#FF00FF"));
                mIcon.setColorFilter(Color.parseColor("#FF00FF"));
                break;

            case 3: // Retro (Terminal)
                mRootContainer.setBackgroundResource(R.drawable.bg_style_retro);
                mLyricText.setTextColor(Color.GREEN);
                mLyricText.setTypeface(Typeface.MONOSPACE);
                mIcon.setColorFilter(Color.GREEN);
                break;

            case 4: // Day Mode (High Contrast)
                mRootContainer.setBackgroundResource(R.drawable.bg_style_bubble);
                mLyricText.setTextColor(Color.BLACK);
                mIcon.setColorFilter(Color.BLACK);
                break;
            
            case 5: // Karaoke (Gold, No BG)
                mRootContainer.setBackground(null);
                mLyricText.setTextColor(Color.parseColor("#FFD700")); // Gold
                mLyricText.setShadowLayer(12, 0, 0, Color.RED); // Red Glow
                mIcon.setColorFilter(Color.parseColor("#FFD700"));
                break;
        }
        
        // IMPORTANT: Re-apply user preferred opacities after style change (which resets drawables)
        updateBackgroundOpacity();
        updateTextOpacity();
        
        // Ensure layout updates
        if (mWindowManager != null) {
            mWindowManager.updateViewLayout(mFloatingView, params);
        }
    }

    private void switchCaptureMode(int mode) {
        this.currentCaptureMode = mode;
        stopLogcatReader();
        stopReceiver(); // Stop Notification Receiver
        stopCarBroadcastReceiver();

        if (mode == MODE_NOTIFICATION) {
            startReceiver(); // Start Notification Receiver
            updateLyricUI("Mode: Notification");
        } else if (mode == MODE_LOGCAT_STANDARD) {
            startLogcatReader();
            updateLyricUI("Mode: LRC Logcat");
        } else if (mode == MODE_BROADCAST_CARKIT) {
            startCarBroadcastReceiver();
            updateLyricUI("Mode: Broadcast (Car Kit)");
        }
    }

    private void startReceiver() {
        if (isReceiverRegistered) return;
        lyricReceiver = new LyricUpdateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(LyricListenerService.ACTION_LYRIC_UPDATE);
        if (Build.VERSION.SDK_INT >= 34) {
            registerReceiver(lyricReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(lyricReceiver, filter);
        }
        isReceiverRegistered = true;
    }

    private void stopReceiver() {
        if (isReceiverRegistered && lyricReceiver != null) {
            unregisterReceiver(lyricReceiver);
            isReceiverRegistered = false;
        }
    }

    private void startCarBroadcastReceiver() {
        if (isCarBroadcastRegistered) return;
        carBroadcastReceiver = new CarBroadcastReceiver();
        IntentFilter filter = new IntentFilter();
        
        // Standard Android Music
        filter.addAction("com.android.music.metachanged");
        filter.addAction("com.android.music.playstatechanged");
        filter.addAction("com.android.music.playbackcomplete");
        filter.addAction("com.android.music.queuechanged");
        filter.addAction("com.android.bluetooth.metachanged");

        // Vendor Specific (Chinese Head Units & Common Apps)
        filter.addAction("com.htc.music.metachanged");
        filter.addAction("fm.last.android.metachanged");
        filter.addAction("com.sec.android.music.metachanged");
        filter.addAction("com.nullsoft.winamp.metachanged");
        filter.addAction("com.amazon.mp3.metachanged");
        filter.addAction("com.miui.player.metachanged");
        filter.addAction("com.real.IMP.metachanged");
        filter.addAction("com.sonyericsson.music.metachanged");
        filter.addAction("com.rdio.android.metachanged");
        filter.addAction("com.samsung.sec.android.MusicPlayer.metachanged");
        filter.addAction("com.andrew.apollo.metachanged");
        
        // Common Chinese Head Unit Bluetooth actions
        filter.addAction("com.tw.bt.metachanged");
        filter.addAction("com.yzx.bt.metachanged");
        filter.addAction("autochips.intent.action.BT_INFO");
        filter.addAction("com.microntek.bt.metachanged");
        filter.addAction("com.suding.bt.metachanged");
        filter.addAction("com.fyl.bt.metachanged");
        filter.addAction("com.zjinnova.bluetooth.metachanged");
        filter.addAction("com.xyauto.bt.metachanged");

        if (Build.VERSION.SDK_INT >= 34) {
            registerReceiver(carBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(carBroadcastReceiver, filter);
        }
        isCarBroadcastRegistered = true;
    }

    private void stopCarBroadcastReceiver() {
        if (isCarBroadcastRegistered && carBroadcastReceiver != null) {
            unregisterReceiver(carBroadcastReceiver);
            isCarBroadcastRegistered = false;
        }
    }

    private void startLogcatReader() {
        if (isLogcatRunning) return;
        isLogcatRunning = true;
        
        logcatThread = new Thread(() -> {
            Process process = null;
            BufferedReader reader = null;
            try {
                if (checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
                    updateLyricUI("Missing READ_LOGS permission.");
                    return;
                }
                Runtime.getRuntime().exec("logcat -c");
                process = Runtime.getRuntime().exec("logcat -v threadtime");
                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                
                Pattern patternStandard = Pattern.compile("\\[(\\d{2}:\\d{2}\\.\\d{2,3})\\](.*)");

                while (isLogcatRunning && (line = reader.readLine()) != null) {
                     Matcher matcher = patternStandard.matcher(line);
                     if (matcher.find()) {
                         String lyric = matcher.group(2).trim();
                         if (!lyric.isEmpty()) updateLyricUI(lyric);
                     }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (process != null) process.destroy();
                try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            }
        });
        logcatThread.start();
    }

    private void stopLogcatReader() {
        isLogcatRunning = false;
        if (logcatThread != null) {
            logcatThread.interrupt();
            logcatThread = null;
        }
    }

    private void updateLyricUI(String text) {
        uiHandler.post(() -> {
            if (isViewAdded && mLyricText != null) {
                mLyricText.setText(text);
                // Force window manager to recalculate width if needed
                if (mWindowManager != null) {
                    try {
                        mWindowManager.updateViewLayout(mFloatingView, params);
                    } catch (Exception e) { e.printStackTrace(); }
                }
            }
        });
    }

    private void startMyForeground() {
        String channelId = "floating_lyric_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Floating Lyrics Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Car Floating Lyrics")
                .setContentText("Overlay Active")
                .setSmallIcon(R.drawable.x)
                .setContentIntent(pendingIntent);
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } catch (Exception e) {
                startForeground(1, notification);
            }
        } else {
            startForeground(1, notification);
        }
    }

    private class LyricUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LyricListenerService.ACTION_LYRIC_UPDATE.equals(intent.getAction())) {
                String text = intent.getStringExtra(LyricListenerService.EXTRA_LYRIC_TEXT);
                if (text != null && isViewAdded && mLyricText != null) {
                    mLyricText.setText(text);
                }
            }
        }
    }

    private class CarBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Low-level intent capture based on provided log:
            // "mediaTitle" (Lyric/Title) and "artistName" (Artist)
            
            String title = intent.getStringExtra("mediaTitle");
            String artist = intent.getStringExtra("artistName");
            
            // Fallback to standard Android keys if custom ones are missing
            if (title == null) {
                title = intent.getStringExtra("track");
            }
            if (artist == null) {
                artist = intent.getStringExtra("artist");
            }

            StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(title)) {
                sb.append(title);
            }
            if (!TextUtils.isEmpty(artist)) {
                if (sb.length() > 0) sb.append(" - ");
                sb.append(artist);
            }
            
            if (sb.length() > 0) {
                updateLyricUI(sb.toString());
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopReceiver();
        stopLogcatReader();
        stopCarBroadcastReceiver();
        try {
            if (isViewAdded && mFloatingView != null && mWindowManager != null) {
                mWindowManager.removeView(mFloatingView);
                isViewAdded = false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}