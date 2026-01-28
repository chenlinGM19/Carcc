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
import java.util.regex.Pattern;

/**
 * 深度兼容车机蓝牙协议的歌词服务
 * 针对 AVRCP 1.3+ 协议及旧款安卓系统设计
 * 核心逻辑：MediaSession 实时流 + 多源广播补偿 + AVRCP 扩展字段扫描 + 时间戳清洗
 */
public class LyricsOverlayService extends NotificationListenerService implements MediaSessionManager.OnActiveSessionsChangedListener {

    private WindowManager windowManager;
    private TextView lyricsView;
    private WindowManager.LayoutParams params;
    private MediaController currentController;
    private MediaSessionManager mediaSessionManager;

    private MediaMetadata lastMetadata;
    private String lastRawText = ""; 
    
    // 正则表达式用于清洗时间戳，例如 [01:20.30] 或 [02:11]
    private final Pattern timestampPattern = Pattern.compile("\\[\\d{2,}:\\d{2,}(\\.\\d+)?\\]");

    private int currentFontSize = 34;
    private int currentTextColor = Color.GREEN;
    private boolean isLocked = false;
    private boolean isVisible = true;
    
    // 捕获模式
    private static final int MODE_AUTO = 0;    // 智能识别 (优先找长字符串/带时间戳的)
    private static final int MODE_TITLE = 1;   // 强制标题 (部分车机把歌词放标题)
    private static final int MODE_DESC = 2;    // 强制描述 (AVRCP 1.6 歌词标准位)
    private static final int MODE_RAW = 3;     // 原始数据 (不进行任何清洗)
    private int captureMode = MODE_AUTO;
    private final String[] MODE_NAMES = {"模式: 智能识别", "模式: 强制抓取标题", "模式: 强制抓取描述", "模式: 原始数据"};

    private float startX, startY;
    private float initialTouchX, initialTouchY;
    private long lastTapTime = 0;
    private boolean isDragging = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    
    private final Runnable longPressRunnable = this::cycleCaptureMode;
    private final Runnable revertMessageRunnable = this::restoreCurrentLyrics;

    private BroadcastReceiver legacyReceiver;
    private static final String CHANNEL_ID = "carlyrix_v2_channel";

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
        
        registerLegacyReceiver();
    }

    private void startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "CarLyrix Core", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("CarLyrix 歌词引擎已就绪")
                    .setContentText("正在监测蓝牙数据流...")
                    .setSmallIcon(android.R.drawable.ic_media_play).build();
            startForeground(1, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("ACTION_UPDATE_CONFIG".equals(action)) {
                currentFontSize = intent.getIntExtra("font_size", currentFontSize);
                applyStyle();
            } else if ("ACTION_UPDATE_LOCK_STATE".equals(action)) {
                setLockState(intent.getBooleanExtra("locked", false));
            } else if ("ACTION_UPDATE_VISIBILITY".equals(action)) {
                setVisibilityState(intent.getBooleanExtra("visible", true));
            }
        }
        return START_STICKY; 
    }

    private void setLockState(boolean locked) {
        this.isLocked = locked;
        if (lyricsView == null) return;
        if (isLocked) params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        showTemporaryMessage(isLocked ? "已锁定 (触摸穿透)" : "已解锁 (可移动)");
        windowManager.updateViewLayout(lyricsView, params);
    }

    private void setVisibilityState(boolean visible) {
        this.isVisible = visible;
        if (lyricsView != null) lyricsView.setVisibility(visible ? View.VISIBLE : View.GONE);
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
            Log.e("CarLyrix", "MediaSession Error: " + e.getMessage());
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

    private void registerLegacyReceiver() {
        legacyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle bundle = intent.getExtras();
                if (bundle == null) return;

                String track = bundle.getString("track");
                String artist = bundle.getString("artist");
                String lyrics = bundle.getString("lyrics"); 
                
                if (lyrics != null) {
                    lastRawText = lyrics;
                } else if (track != null) {
                    lastRawText = track + (artist != null ? "\n" + artist : "");
                }
                
                if (currentController == null) {
                    updateOverlayText(captureMode == MODE_RAW ? lastRawText : cleanText(lastRawText));
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction("com.android.music.metachanged");
        f.addAction("com.android.music.playstatechanged");
        f.addAction("com.spotify.music.metadatachanged");
        f.addAction("com.htc.music.metachanged");
        f.addAction("com.miui.player.metachanged");
        f.addAction("com.samsung.sec.android.MusicPlayer.metachanged");
        f.addAction("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED");
        registerReceiver(legacyReceiver, f);
    }

    private void updateMetadata(MediaMetadata metadata) {
        if (metadata == null) return;
        lastMetadata = metadata;
        uiHandler.removeCallbacks(revertMessageRunnable);

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String desc = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
        String dTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);

        String result = "";
        switch (captureMode) {
            case MODE_AUTO:
                if (isLookLikeLyrics(desc)) result = desc;
                else if (isLookLikeLyrics(title)) result = title;
                else if (desc != null) result = desc;
                else if (dTitle != null) result = dTitle;
                else result = (title != null ? title : "") + (artist != null ? "\n"+artist : "");
                break;
            case MODE_TITLE: result = title != null ? title : ""; break;
            case MODE_DESC: result = desc != null ? desc : ""; break;
            case MODE_RAW: result = desc != null ? desc : (title != null ? title : ""); break;
        }
        
        if (result.isEmpty()) result = lastRawText;
        updateOverlayText(captureMode == MODE_RAW ? result : cleanText(result));
    }

    private boolean isLookLikeLyrics(String text) {
        if (text == null) return false;
        // 包含时间戳或者长度较长通常是歌词
        return timestampPattern.matcher(text).find() || text.length() > 30;
    }

    private String cleanText(String text) {
        if (text == null) return "";
        // 移除 [01:22.10] 格式的时间戳
        String cleaned = timestampPattern.matcher(text).replaceAll("");
        return cleaned.trim();
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        lyricsView = new TextView(this);
        applyStyle();
        lyricsView.setText("CarLyrix: 等待蓝牙数据...");
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
        if (lastMetadata != null) updateMetadata(lastMetadata);
        else if (!lastRawText.isEmpty()) updateOverlayText(cleanText(lastRawText));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (legacyReceiver != null) try { unregisterReceiver(legacyReceiver); } catch (Exception e) {}
        if (windowManager != null && lyricsView != null) windowManager.removeView(lyricsView);
    }
}
