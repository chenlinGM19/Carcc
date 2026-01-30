package com.example.carfloatinglyrics;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
        startMyForeground();

        // 2. Initialize Window Manager
        mFloatingView = LayoutInflater.from(this).inflate(R.layout.window_floating_lyric, null);
        mLyricText = mFloatingView.findViewById(R.id.tv_floating_lyric);

        // Layout Params specific for Overlay
        // TYPE_APPLICATION_OVERLAY for Android 8.0+, TYPE_PHONE for older
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
        mWindowManager.addView(mFloatingView, params);

        // 3. Setup Drag Listener
        setupTouchListener();
        
        // 4. Start Simulation Loop (replacing actual Logcat reading)
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
                if (isLocked) return false; // If locked, don't consume event, or allow click-through

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
                        mWindowManager.updateViewLayout(mFloatingView, params);
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
                    mFloatingView.setVisibility(visible ? View.VISIBLE : View.GONE);
                    break;
                case ACTION_UPDATE_SIZE:
                    int size = intent.getIntExtra("size", 18);
                    mLyricText.setTextSize(size);
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

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Car Floating Lyrics")
                .setContentText("Lyrics overlay is active")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);
    }
    
    // Simulate reading the provided Logcat data:
    // "01-29 20:13:58.480 D/BluetoothPlayer(4844): onMediaInfoChanged! mediaTitle=The scars of your love..."
    private void startLyricsSimulation() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (mFloatingView != null && mFloatingView.getVisibility() == View.VISIBLE) {
                    mLyricText.setText(simulatedLyrics[lyricsIndex]);
                    lyricsIndex = (lyricsIndex + 1) % simulatedLyrics.length;
                }
                // Update every 3 seconds to simulate song progression
                simulationHandler.postDelayed(this, 3000);
            }
        };
        simulationHandler.post(runnable);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mFloatingView != null) {
            mWindowManager.removeView(mFloatingView);
        }
        simulationHandler.removeCallbacksAndMessages(null);
    }
}