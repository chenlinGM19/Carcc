package com.example.carfloatinglyrics;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

public class LyricListenerService extends NotificationListenerService {

    public static final String ACTION_LYRIC_UPDATE = "com.example.carfloatinglyrics.LYRIC_UPDATE";
    public static final String EXTRA_LYRIC_TEXT = "lyric_text";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        processNotification(sbn);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        processNotification(sbn);
    }

    private void processNotification(StatusBarNotification sbn) {
        if (sbn == null) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        // Extract standard notification fields used by music players
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);

        // Logic to determine which part is the "lyric".
        // Many apps put the song name in Title and Artist/Lyric in Text.
        // Some put the lyric in Title.
        
        StringBuilder combinedText = new StringBuilder();

        // Priority logic (Adjust based on specific car head unit behavior)
        if (!TextUtils.isEmpty(text)) {
            combinedText.append(text);
        } else if (!TextUtils.isEmpty(title)) {
            combinedText.append(title);
        }

        if (combinedText.length() > 0) {
            sendLyricBroadcast(combinedText.toString());
        }
    }

    private void sendLyricBroadcast(String text) {
        Intent intent = new Intent(ACTION_LYRIC_UPDATE);
        intent.putExtra(EXTRA_LYRIC_TEXT, text);
        // Using sendBroadcast to communicate with the Floating Service
        sendBroadcast(intent);
    }
}