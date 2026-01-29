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

/**
 * CarLyrix v4.0 核心引擎 - 针对 FlyAudio/Iflytek 深度定制
 * 采用了“特征识别算法”：当 mediaTitle 字段变动频率异常时，自动判定为歌词流。
 */
public class LyricsOverlayService extends NotificationListenerService implements MediaSessionManager.OnActiveSessionsChangedListener {

    private static final String TAG = "CarLyrix_Engine";
    private WindowManager windowManager;
    private TextView lyricsView;
    private WindowManager.LayoutParams params;
    private MediaSessionManager mediaSessionManager;
    private MediaController currentController;

    // 数据池
    private String currentTitle = "";
    private String currentArtist = "";
    private String streamLyric = "";
    private long lastTitleUpdateTime = 0;
    
    // 配置项
    private int currentFontSize = 34;
    private int currentTextColor = Color.GREEN;
    private boolean isLocked = false;
    private boolean isVisible = true;
    
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private static final String CHANNEL_ID = "carlyrix_v4_core";

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            handleMetadata(metadata);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        loadPrefs();
        startForeground(1, createNotification());
        createOverlay();
        initMediaManager();
        registerAdvancedReceivers();
    }

    private void loadPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentFontSize = prefs.getInt("pref_font_size", 34);
        isLocked = prefs.getBoolean("pref_is_locked", false);
        isVisible = prefs.getBoolean("pref_is_visible", true);
    }

    /**
     * 颠覆式监听：全量捕获日志中提到的所有可疑广播
     */
    private void registerAdvancedReceivers() {
        BroadcastReceiver advancedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle bundle = intent.getExtras();
                if (bundle == null) return;
                
                // 1. 深度扫描：根据日志特征抓取
                String title = getStringFromBundle(bundle, "mediaTitle", "Name", "track", "title");
                String artist = getStringFromBundle(bundle, "artistName", "Artist", "singer", "artist");
                String lrc = getStringFromBundle(bundle, "mLyruc", "lyric", "lrc", "lyrics");

                processDataFlow(title, artist, lrc);
            }
        };

        IntentFilter filter = new IntentFilter();
        // 标准 & 厂商广播 (依据 Logcat 增加)
        filter.addAction("com.android.music.metachanged");
        filter.addAction("com.iflytek.music.metachanged");
        filter.addAction("com.flyaudio.music.metachanged");
        filter.addAction("com.remote.control.metadata");
        filter.addAction("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED");
        filter.addAction("com.iflytek.xiri.action.MUSIC_METADATA_UPDATE");
        
        registerReceiver(advancedReceiver, filter);
    }

    private String getStringFromBundle(Bundle b, String... keys) {
        for (String key : keys) {
            Object v = b.get(key);
            if (v != null && !v.toString().isEmpty() && !v.toString().equals("null")) {
                return v.toString();
            }
        }
        return "";
    }

    /**
     * 核心逻辑：特征识别算法
     * 解决日志中 mediaTitle 既是歌词又是标题的问题
     */
    private void processDataFlow(String title, String artist, String lrc) {
        if (!artist.isEmpty()) currentArtist = artist;

        // 特征识别：如果 title 包含空格、长度较长，且在短时间内再次变动，判定为“流式歌词”
        long now = System.currentTimeMillis();
        boolean isLikelyLyric = title.length() > 15 || (title.contains(" ") && (now - lastTitleUpdateTime < 15000));

        if (!lrc.isEmpty()) {
            streamLyric = lrc; // 明确的歌词字段优先
        } else if (isLikelyLyric) {
            streamLyric = title; // 判定标题字段正在充当歌词
        } else if (!title.isEmpty()) {
            currentTitle = title;
            streamLyric = ""; // 可能是真的切歌了
        }

        lastTitleUpdateTime = now;
        updateUI();
    }

    private void handleMetadata(MediaMetadata metadata) {
        if (metadata == null) return;
        String t = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        processDataFlow(t != null ? t : "", a != null ? a : "", "");
    }

    private void updateUI() {
        uiHandler.post(() -> {
            if (lyricsView == null) return;
            String text = "";
            int color = currentTextColor;

            if (!streamLyric.isEmpty()) {
                text = streamLyric;
                color = currentTextColor;
            } else {
                String meta = (currentTitle + (currentArtist.isEmpty() ? "" : "\n" + currentArtist)).trim();
                text = meta;
                color = Color.LTGRAY; // 非歌词状态稍微变淡
            }

            // 如果最终内容为空，显示 "-"
            if (text == null || text.trim().isEmpty()) {
                text = "-";
                color = Color.LTGRAY;
            }

            lyricsView.setTextColor(color);
            lyricsView.setText(text);
        });
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        lyricsView = new TextView(this);
        lyricsView.setTextSize(currentFontSize);
        lyricsView.setTextColor(currentTextColor);
        lyricsView.setGravity(Gravity.CENTER);
        lyricsView.setShadowLayer(10, 0, 0, Color.BLACK);
        lyricsView.setPadding(30, 10, 30, 10);
        lyricsView.setBackgroundColor(0x33000000); // 增加一层微弱背景提高能见度

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, 
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, 
                PixelFormat.TRANSLUCENT);
        
        if (isLocked) params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        
        params.gravity = Gravity.TOP;
        params.y = 100;

        setupTouch();
        try { windowManager.addView(lyricsView, params); } catch (Exception e) { Log.e(TAG, "Overlay error: " + e.getMessage()); }
    }

    private void setupTouch() {
        lyricsView.setOnTouchListener(new View.OnTouchListener() {
            private float initialX, initialY, touchX, touchY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isLocked) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x; initialY = params.y;
                        touchX = event.getRawX(); touchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.y = (int) (initialY + (event.getRawY() - touchY));
                        windowManager.updateViewLayout(lyricsView, params);
                        return true;
                }
                return false;
            }
        });
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "CarLyrix Engine", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("CarLyrix 4.0 增强引擎")
                    .setContentText("深度协议分析中...")
                    .setSmallIcon(android.R.drawable.ic_media_play).build();
        }
        return new Notification();
    }

    private void initMediaManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(this, LyricsOverlayService.class);
            mediaSessionManager.addOnActiveSessionsChangedListener(this, cn);
            onActiveSessionsChanged(mediaSessionManager.getActiveSessions(cn));
        }
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> controllers) {
        if (controllers != null && !controllers.isEmpty()) {
            if (currentController != null) currentController.unregisterCallback(mediaCallback);
            currentController = controllers.get(0);
            currentController.registerCallback(mediaCallback);
            handleMetadata(currentController.getMetadata());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if (act.equals("ACTION_UPDATE_CONFIG")) {
                currentFontSize = intent.getIntExtra("font_size", currentFontSize);
                if (lyricsView != null) lyricsView.setTextSize(currentFontSize);
            } else if (act.equals("ACTION_UPDATE_LOCK_STATE")) {
                isLocked = intent.getBooleanExtra("locked", false);
                if (lyricsView != null) {
                    if (isLocked) params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    else params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    windowManager.updateViewLayout(lyricsView, params);
                }
            } else if (act.equals("ACTION_UPDATE_VISIBILITY")) {
                isVisible = intent.getBooleanExtra("visible", true);
                if (lyricsView != null) lyricsView.setVisibility(isVisible ? View.VISIBLE : View.GONE);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && lyricsView != null) windowManager.removeView(lyricsView);
    }
}
