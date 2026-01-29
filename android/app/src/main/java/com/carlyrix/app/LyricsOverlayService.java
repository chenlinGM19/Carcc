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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CarLyrix v4.0 - 深度适配车机引擎
 * 针对日志中出现的 Iflytek/FlyAudio/RemoteControl 组件进行深度字段扫描
 */
public class LyricsOverlayService extends NotificationListenerService implements MediaSessionManager.OnActiveSessionsChangedListener {

    private static final String TAG = "CarLyrix_Service";
    private WindowManager windowManager;
    private TextView lyricsView;
    private WindowManager.LayoutParams params;
    private MediaController currentController;
    private MediaSessionManager mediaSessionManager;

    private MediaMetadata lastMetadata;
    private String lastCapturedLyrics = "";
    private String lastCapturedTitle = "";
    private String lastCapturedArtist = "";
    
    private final Pattern timestampPattern = Pattern.compile("\\[\\d{2,}:\\d{2,}(\\.\\d+)?\\]");

    private int currentFontSize = 34;
    private int currentTextColor = Color.GREEN;
    private boolean isLocked = false;
    private boolean isVisible = true;
    
    private static final int MODE_AUTO = 0;
    private static final int MODE_RAW = 1;
    private int captureMode = MODE_AUTO;
    private final String[] MODE_NAMES = {"模式: 智能识别", "模式: 原始数据"};

    private float startX, startY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    
    private final Runnable longPressRunnable = this::cycleCaptureMode;
    private final Runnable revertMessageRunnable = this::restoreCurrentLyrics;

    private BroadcastReceiver carExtraReceiver;
    private static final String CHANNEL_ID = "carlyrix_v4_channel";

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateMetadata(metadata);
        }
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                if (lyricsView != null && isVisible) lyricsView.setVisibility(View.VISIBLE);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentFontSize = prefs.getInt("pref_font_size", 34);
        isLocked = prefs.getBoolean("pref_is_locked", false);
        isVisible = prefs.getBoolean("pref_is_visible", true);

        startForegroundServiceNotification();
        createOverlay();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        }
        
        registerCarSpecificReceivers();
    }

    private void startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "CarLyrix Engine", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("CarLyrix 4.0 运行中")
                    .setContentText("正在深度监测车机蓝牙流...")
                    .setSmallIcon(android.R.drawable.ic_media_play).build();
            startForeground(1, notification);
        }
    }

    /**
     * 核心改进：针对日志中出现的 Iflytek, FlyAudio 等组件进行全协议监听
     */
    private void registerCarSpecificReceivers() {
        carExtraReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle bundle = intent.getExtras();
                if (bundle == null) return;

                // 深度扫描 Bundle 中所有字段 (针对日志中的 mediaTitle, artistName, mLyruc 等)
                deepScanBundle(bundle);
                
                if (currentController == null) {
                    refreshDisplay();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        // 标准协议
        filter.addAction("com.android.music.metachanged");
        filter.addAction("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED");
        
        // 日志中体现的讯飞/飞歌车机组件常见广播
        filter.addAction("com.iflytek.music.metachanged");
        filter.addAction("com.iflytek.xiri.action.MUSIC_METADATA_UPDATE");
        filter.addAction("com.flyaudio.music.metachanged");
        filter.addAction("com.syu.music.metachanged");
        filter.addAction("cn.colink.music.metachanged");
        filter.addAction("com.mxnav.music.metachanged");
        filter.addAction("com.remote.control.metadata"); // 对应日志中的 RemoteControl
        
        registerReceiver(carExtraReceiver, filter);
    }

    /**
     * 深度扫描引擎：模糊匹配日志中可能出现的任何字段名
     */
    private void deepScanBundle(Bundle bundle) {
        Set<String> keys = bundle.keySet();
        for (String key : keys) {
            Object value = bundle.get(key);
            if (value == null) continue;
            String valStr = value.toString();
            String lowerKey = key.toLowerCase();

            // 抓取歌词类字段 (包含日志中的 mLyruc 异常拼写)
            if (lowerKey.contains("lyric") || lowerKey.contains("lrc") || lowerKey.contains("lyruc")) {
                if (valStr.length() > 2) lastCapturedLyrics = valStr;
            } 
            // 抓取标题类字段 (包含日志中的 mediaTitle, musicname)
            else if (lowerKey.contains("title") || lowerKey.contains("track") || lowerKey.contains("musicname")) {
                lastCapturedTitle = valStr;
            }
            // 抓取歌手类字段 (包含日志中的 artistname, singer)
            else if (lowerKey.contains("artist") || lowerKey.contains("singer")) {
                lastCapturedArtist = valStr;
            }
        }
    }

    private void refreshDisplay() {
        String display;
        if (captureMode == MODE_RAW) {
            display = !lastCapturedLyrics.isEmpty() ? lastCapturedLyrics : (lastCapturedTitle + "\n" + lastCapturedArtist);
        } else {
            String cleanLrc = cleanText(lastCapturedLyrics);
            if (!cleanLrc.isEmpty()) {
                display = cleanLrc;
            } else {
                display = lastCapturedTitle + (lastCapturedArtist.isEmpty() ? "" : "\n" + lastCapturedArtist);
            }
        }
        updateOverlayText(display);
    }

    private void updateMetadata(MediaMetadata metadata) {
        if (metadata == null) return;
        lastMetadata = metadata;
        
        // 尝试从标准 MediaMetadata 抓取，如果失败则保留深度扫描的数据
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String desc = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
        
        if (title != null) lastCapturedTitle = title;
        if (desc != null && desc.length() > 5) lastCapturedLyrics = desc;

        refreshDisplay();
    }

    private String cleanText(String text) {
        if (text == null || text.equals("null")) return "";
        return timestampPattern.matcher(text).replaceAll("").trim();
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        lyricsView = new TextView(this);
        applyStyle();
        lyricsView.setText("CarLyrix 4.0: 扫描车机信号...");
        lyricsView.setShadowLayer(8, 0, 0, Color.BLACK);
        lyricsView.setTypeface(null, android.graphics.Typeface.BOLD);
        lyricsView.setGravity(Gravity.CENTER);
        lyricsView.setPadding(40, 20, 40, 20);
        if (!isVisible) lyricsView.setVisibility(View.GONE);

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (isLocked) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 150;

        setupTouchListener();
        try { windowManager.addView(lyricsView, params); } catch (Exception e) {}
    }

    private void setupTouchListener() {
        lyricsView.setOnTouchListener(new View.OnTouchListener() {
            private long lastTapTime = 0;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isLocked) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x; startY = params.y;
                        initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                        isDragging = false;
                        uiHandler.postDelayed(longPressRunnable, 800);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true; uiHandler.removeCallbacks(longPressRunnable);
                        }
                        if (isDragging) {
                            params.x = (int) (startX + dx); params.y = (int) (startY + dy);
                            windowManager.updateViewLayout(lyricsView, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        uiHandler.removeCallbacks(longPressRunnable);
                        if (!isDragging) {
                            long now = System.currentTimeMillis();
                            if (now - lastTapTime < 300) cycleColors();
                            lastTapTime = now;
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void cycleColors() {
        int[] colors = {Color.GREEN, Color.CYAN, Color.WHITE, Color.YELLOW, Color.RED, Color.MAGENTA};
        for(int i=0; i<colors.length; i++) {
            if (currentTextColor == colors[i]) {
                currentTextColor = colors[(i + 1) % colors.length];
                break;
            }
        }
        applyStyle();
        showTemporaryMessage("颜色已切换");
    }

    private void cycleCaptureMode() {
        captureMode = (captureMode + 1) % MODE_NAMES.length;
        showTemporaryMessage(MODE_NAMES[captureMode]);
    }

    private void applyStyle() {
        if (lyricsView != null) {
            lyricsView.setTextColor(currentTextColor);
            lyricsView.setTextSize(currentFontSize);
        }
    }

    private void updateOverlayText(String text) {
        if (lyricsView != null) lyricsView.post(() -> lyricsView.setText(text));
    }

    private void showTemporaryMessage(String msg) {
        uiHandler.removeCallbacks(revertMessageRunnable);
        updateOverlayText(msg);
        uiHandler.postDelayed(revertMessageRunnable, 2000);
    }

    private void restoreCurrentLyrics() {
        refreshDisplay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("ACTION_UPDATE_CONFIG".equals(action)) {
                currentFontSize = intent.getIntExtra("font_size", currentFontSize);
                applyStyle();
            } else if ("ACTION_UPDATE_LOCK_STATE".equals(action)) {
                isLocked = intent.getBooleanExtra("locked", false);
                if (lyricsView != null) {
                    if (isLocked) params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    else params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    windowManager.updateViewLayout(lyricsView, params);
                }
            } else if ("ACTION_UPDATE_VISIBILITY".equals(action)) {
                isVisible = intent.getBooleanExtra("visible", true);
                if (lyricsView != null) lyricsView.setVisibility(isVisible ? View.VISIBLE : View.GONE);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        scanForActiveSessions();
    }

    private void scanForActiveSessions() {
        if (mediaSessionManager == null) return;
        try {
            ComponentName cn = new ComponentName(this, LyricsOverlayService.class);
            mediaSessionManager.addOnActiveSessionsChangedListener(this, cn);
            processControllers(mediaSessionManager.getActiveSessions(cn));
        } catch (Exception e) {
            Log.e(TAG, "MediaSession Error: " + e.getMessage());
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
            if (mc.getPlaybackState() != null && mc.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) {
                target = mc; break;
            }
        }
        if (currentController != null) currentController.unregisterCallback(mediaCallback);
        currentController = target;
        currentController.registerCallback(mediaCallback);
        updateMetadata(currentController.getMetadata());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (carExtraReceiver != null) try { unregisterReceiver(carExtraReceiver); } catch (Exception e) {}
        if (windowManager != null && lyricsView != null) windowManager.removeView(lyricsView);
    }
}
