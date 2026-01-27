package com.carlyrix.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.List;

/**
 * Pure Native Service for Car Head Units.
 * Features:
 * - Listens to MediaSession (Bluetooth AVRCP).
 * - Fallback to Broadcast Intents for older music apps.
 * - Overlay Lyrics.
 * - Touch Gestures: Drag (Move), Double Tap (Cycle Color), Long Press (Capture Mode).
 */
public class LyricsOverlayService extends NotificationListenerService implements MediaSessionManager.OnActiveSessionsChangedListener {

    private WindowManager windowManager;
    private TextView lyricsView;
    private WindowManager.LayoutParams params;
    private MediaController currentController;
    private MediaSessionManager mediaSessionManager;

    // Data Storage
    private MediaMetadata lastMetadata;
    private String lastLegacyText = ""; // Fallback text from Broadcasts

    // UI Configuration
    private int currentFontSize = 34;
    private int currentTextColor = Color.GREEN;
    
    // Capture Modes
    private static final int MODE_AUTO = 0;
    private static final int MODE_TITLE = 1;
    private static final int MODE_SUBTITLE = 2;
    private static final int MODE_DESC = 3;
    private int captureMode = MODE_AUTO;
    private final String[] MODE_NAMES = {"Mode: Smart Auto", "Mode: Force Title", "Mode: Force Artist/Sub", "Mode: Force Desc"};

    // Touch Handling Variables
    private float startX, startY;
    private float initialTouchX, initialTouchY;
    private long lastTapTime = 0;
    private boolean isDragging = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    
    // Runnables
    private final Runnable longPressRunnable = this::cycleCaptureMode;
    // Reverts temporary messages (like "Style Changed") to the song lyrics
    private final Runnable revertMessageRunnable = this::restoreCurrentLyrics;

    private BroadcastReceiver legacyReceiver;
    private static final String CHANNEL_ID = "carlyrix_service_channel";
    private static final String ACTION_UPDATE_CONFIG = "ACTION_UPDATE_CONFIG";

    // Internal Callback for MediaController events
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateMetadata(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                if (lyricsView != null && lyricsView.getAlpha() < 1.0f) {
                    lyricsView.setAlpha(1.0f);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        
        // Load saved preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentFontSize = prefs.getInt("pref_font_size", 34);

        startForegroundServiceNotification();
        createOverlay();
        
        // API 21+ support for MediaSession
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        }
        
        registerLegacyReceiver();
    }

    private void startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Lyrics Overlay Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
            
            Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("CarLyrix Running")
                    .setContentText("Listening for music...")
                    .setSmallIcon(android.R.drawable.ic_media_play);
            
            startForeground(1, builder.build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle dynamic updates from MainActivity
        if (intent != null && ACTION_UPDATE_CONFIG.equals(intent.getAction())) {
            int newSize = intent.getIntExtra("font_size", -1);
            if (newSize > 0) {
                currentFontSize = newSize;
                applyStyle();
            }
        }
        return START_STICKY; 
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i("CarLyrix", "Service Connected");
        scanForActiveSessions();
        showTemporaryMessage("Ready\nPlay Music");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (currentController != null) {
            currentController.unregisterCallback(mediaCallback);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (legacyReceiver != null) {
            unregisterReceiver(legacyReceiver);
        }
        if (windowManager != null && lyricsView != null) {
            try {
                windowManager.removeView(lyricsView);
            } catch (Exception e) {}
        }
    }

    // --- Data Sources ---

    private void scanForActiveSessions() {
        if (mediaSessionManager == null) return;
        try {
            ComponentName componentName = new ComponentName(this, LyricsOverlayService.class);
            // Register for updates
            mediaSessionManager.addOnActiveSessionsChangedListener(this, componentName);
            // Get current sessions
            List<MediaController> controllers = mediaSessionManager.getActiveSessions(componentName);
            processControllers(controllers);
        } catch (SecurityException e) {
            // If permission missing, we might still get data via BroadcastReceiver
            showTemporaryMessage("Notice: Media Permission Check");
        }
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> controllers) {
        processControllers(controllers);
    }

    private void processControllers(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) return;
        
        MediaController target = controllers.get(0);
        for (MediaController mc : controllers) {
            if (mc.getPlaybackState() != null && 
                mc.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) {
                target = mc;
                break;
            }
        }
        switchController(target);
    }

    private void switchController(MediaController newController) {
        if (newController == null) return;
        
        // Prevent re-registering the same controller unnecessarily unless null
        if (currentController != null && 
            currentController.getPackageName().equals(newController.getPackageName()) &&
            currentController.getSessionToken().equals(newController.getSessionToken())) {
             // Just update data
             updateMetadata(newController.getMetadata());
             return;
        }

        if (currentController != null) {
            currentController.unregisterCallback(mediaCallback);
        }

        currentController = newController;
        currentController.registerCallback(mediaCallback);
        updateMetadata(currentController.getMetadata());
    }

    // --- Legacy Broadcast Support ---
    
    private void registerLegacyReceiver() {
        legacyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    String track = intent.getStringExtra("track");
                    String artist = intent.getStringExtra("artist");
                    if (track != null) {
                        StringBuilder sb = new StringBuilder(track);
                        if (artist != null && !artist.isEmpty()) sb.append("\n").append(artist);
                        
                        lastLegacyText = sb.toString();
                        
                        // If no active MediaSession is controlling the UI, use this
                        if (currentController == null && lastMetadata == null) {
                            uiHandler.removeCallbacks(revertMessageRunnable);
                            updateOverlayText(lastLegacyText);
                        }
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.android.music.metachanged");
        filter.addAction("com.android.music.playstatechanged");
        filter.addAction("com.htc.music.metachanged");
        filter.addAction("fm.last.android.metachanged");
        filter.addAction("com.sec.android.app.music.metachanged");
        filter.addAction("com.nullsoft.winamp.metachanged");
        filter.addAction("com.amazon.mp3.metachanged");
        filter.addAction("com.miui.player.metachanged");
        filter.addAction("com.real.AMP.metachanged");
        filter.addAction("com.sonyericsson.music.metachanged");
        filter.addAction("com.rdio.android.metachanged");
        filter.addAction("com.samsung.sec.android.MusicPlayer.metachanged");
        filter.addAction("com.andrew.apollo.metachanged");
        
        registerReceiver(legacyReceiver, filter);
    }

    // --- Core Logic ---

    private void updateMetadata(MediaMetadata metadata) {
        if (metadata == null) return;
        lastMetadata = metadata; 
        
        // Cancel temporary message revert since we have fresh data
        uiHandler.removeCallbacks(revertMessageRunnable);

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String displayTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String displaySubtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String description = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);

        StringBuilder sb = new StringBuilder();

        switch (captureMode) {
            case MODE_AUTO:
                if (isValid(description)) {
                    sb.append(description);
                } else if (isValid(displayTitle)) {
                    sb.append(displayTitle);
                    if (isValid(displaySubtitle)) sb.append("\n").append(displaySubtitle);
                } else if (isValid(title)) {
                    sb.append(title);
                    if (isValid(artist)) sb.append("\n").append(artist);
                }
                break;
            case MODE_TITLE:
                if (isValid(title)) sb.append(title);
                else if (isValid(displayTitle)) sb.append(displayTitle);
                else sb.append("(No Title)");
                break;
            case MODE_SUBTITLE:
                if (isValid(displaySubtitle)) sb.append(displaySubtitle);
                else if (isValid(artist)) sb.append(artist);
                else sb.append("(No Artist/Sub)");
                break;
            case MODE_DESC:
                if (isValid(description)) sb.append(description);
                else sb.append("(No Desc)");
                break;
        }

        String finalString = sb.toString();
        if (finalString.isEmpty() && !lastLegacyText.isEmpty()) {
            finalString = lastLegacyText;
        }
        
        if (!finalString.isEmpty()) {
            updateOverlayText(finalString);
        }
    }

    private void restoreCurrentLyrics() {
        if (lastMetadata != null) {
            updateMetadata(lastMetadata);
        } else if (!lastLegacyText.isEmpty()) {
            updateOverlayText(lastLegacyText);
        }
    }

    private boolean isValid(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // --- UI & Gesture Logic ---

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        lyricsView = new TextView(this);
        
        applyStyle(); 
        
        lyricsView.setShadowLayer(8, 0, 0, Color.BLACK);
        lyricsView.setTypeface(null, android.graphics.Typeface.BOLD);
        lyricsView.setGravity(Gravity.CENTER);
        lyricsView.setBackgroundColor(Color.TRANSPARENT); 
        lyricsView.setPadding(20, 10, 20, 10);

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 80;

        setupTouchListener();

        try {
            windowManager.addView(lyricsView, params);
        } catch (Exception e) {
            Log.e("CarLyrix", "Overlay Error: " + e.getMessage());
        }
    }

    private void setupTouchListener() {
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        lyricsView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        
                        uiHandler.postDelayed(longPressRunnable, 800); 
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;

                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                            isDragging = true;
                            uiHandler.removeCallbacks(longPressRunnable); 
                        }

                        if (isDragging) {
                            params.x = (int) (startX + dx);
                            params.y = (int) (startY + dy);
                            windowManager.updateViewLayout(lyricsView, params);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        uiHandler.removeCallbacks(longPressRunnable); 
                        
                        if (!isDragging) {
                            long now = System.currentTimeMillis();
                            if (now - lastTapTime < 300) {
                                cycleColors(); // Double Tap -> Color Only
                            }
                            lastTapTime = now;
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void cycleColors() {
        if (currentTextColor == Color.GREEN) {
            currentTextColor = Color.CYAN;
        } else if (currentTextColor == Color.CYAN) {
            currentTextColor = Color.YELLOW;
        } else if (currentTextColor == Color.YELLOW) {
            currentTextColor = Color.WHITE;
        } else {
            currentTextColor = Color.GREEN;
        }
        applyStyle();
        showTemporaryMessage("Color Changed");
    }
    
    private void applyStyle() {
        if (lyricsView != null) {
            lyricsView.setTextColor(currentTextColor);
            lyricsView.setTextSize(currentFontSize);
        }
    }

    private void cycleCaptureMode() {
        captureMode++;
        if (captureMode > MODE_DESC) captureMode = MODE_AUTO;
        
        String modeName = MODE_NAMES[captureMode];
        showTemporaryMessage(modeName);
    }

    private void updateOverlayText(String text) {
        if (lyricsView != null) {
            lyricsView.post(() -> lyricsView.setText(text));
        }
    }

    private void showTemporaryMessage(String msg) {
        uiHandler.removeCallbacks(revertMessageRunnable);
        updateOverlayText(msg);
        uiHandler.postDelayed(revertMessageRunnable, 1500);
    }
}
