package com.example.carfloatinglyrics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

public class FloatingLyricService extends Service {

    public static final String ACTION_TOGGLE_LOCK = "com.example.carfloatinglyrics.TOGGLE_LOCK";
    public static final String ACTION_TOGGLE_VISIBILITY = "com.example.carfloatinglyrics.TOGGLE_VISIBILITY";
    public static final String ACTION_UPDATE_SIZE = "com.example.carfloatinglyrics.UPDATE_SIZE";

    private WindowManager mWindowManager;
    private View mFloatingView;
    private TextView mLyricText;
    private WindowManager.LayoutParams params;
    
    // State
    private boolean isLocked = false;
    private boolean isViewAdded = false;
    
    // Simulation
    private Handler simulationHandler = new Handler(Looper.getMainLooper());
    private int lyricsIndex = 0;
    private final String[] simulatedLyrics = {
        "The scars of your love",
        "They leave me breathless",
        "I can't help feeling",
        "We could have had it all",
        "Rolling in the deep",
        "You had my heart inside of your hand",
        "And you played it to the beat"
    };

    public FloatingLyricService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 1. Create Notification for Foreground Service
        // Must be called immediately to prevent ANR/Crash on Android 14
        startMyForeground();

        // 2. Initialize Window Manager
        try {
            mFloatingView = LayoutInflater.from(this).inflate(R.layout.window_floating_lyric, null);
            mLyricText = mFloatingView.findViewById(R.id.tv_floating_lyric);

            // Layout Params specific for Overlay
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
                    // FLAG_NOT_FOCUSABLE: pass touch events to windows behind if not clicking view
                    // FLAG_LAYOUT_NO_LIMITS: allow drawing over status bar/nav bar
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);

            // Initial Position
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 100;
            params.y = 100;

            mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            // Check permission before adding view to prevent crash
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                // If permission missing, just don't add view, service will run in bg (or user needs to grant)
            } else {
                if (mWindowManager != null) {
                    mWindowManager.addView(mFloatingView, params);
                    isViewAdded = true;
                    // 3. Setup Drag Listener only if view added
                    setupTouchListener();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            // Prevent app crash if WindowManager fails
        }
        
        // 4. Start Simulation Loop
        startLyricsSimulation();
    }

    private void setupTouchListener() {
        mFloatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isLocked) return false;

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_UP:
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
        if (intent != null && intent.getAction() != null) {
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
                    }
                    break;
            }
        }
        return START_STICKY;
    }

    private void startMyForeground() {
        String channelId = "floating_lyric_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Floating Lyrics Service",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Uses R.drawable.x (Make sure x.png exists in res/drawable and x.xml is deleted)
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Car Floating Lyrics")
                .setContentText("Lyrics overlay is active")
                .setSmallIcon(R.drawable.x)
                .setContentIntent(pendingIntent);
        
        Notification notification = builder.build();

        // Android 14 (API 34) requires specifying foreground service type.
        // Using SPECIAL_USE for overlays.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } catch (Exception e) {
                // Fallback or log error if system doesn't like the type, though Manifest declares it.
                startForeground(1, notification);
            }
        } else {
            startForeground(1, notification);
        }
    }
    
    private void startLyricsSimulation() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (isViewAdded && mFloatingView != null && mFloatingView.getVisibility() == View.VISIBLE) {
                    mLyricText.setText(simulatedLyrics[lyricsIndex]);
                    lyricsIndex = (lyricsIndex + 1) % simulatedLyrics.length;
                }
                simulationHandler.postDelayed(this, 3000);
            }
        };
        simulationHandler.post(runnable);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (isViewAdded && mFloatingView != null && mWindowManager != null) {
                mWindowManager.removeView(mFloatingView);
                isViewAdded = false;
            }
        } catch (IllegalArgumentException e) {
            // View not attached
        } catch (Exception e) {
            e.printStackTrace();
        }
        simulationHandler.removeCallbacksAndMessages(null);
    }
}