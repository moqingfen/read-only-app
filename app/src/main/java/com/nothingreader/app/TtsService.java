package com.nothingreader.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 前台朗读服务：支持息屏续播、通知栏播放控制、按句分段与进度回调。
 * 正文通过静态字段交接（同进程），避免 Intent 体积限制。
 */
public final class TtsService extends Service implements TextToSpeech.OnInitListener {
    static final String ACTION_PLAY = "com.nothingreader.app.tts.PLAY";
    static final String ACTION_PAUSE = "com.nothingreader.app.tts.PAUSE";
    static final String ACTION_TOGGLE = "com.nothingreader.app.tts.TOGGLE";
    static final String ACTION_STOP = "com.nothingreader.app.tts.STOP";
    static final String ACTION_NEXT = "com.nothingreader.app.tts.NEXT";
    static final String ACTION_PREV = "com.nothingreader.app.tts.PREV";

    private static final String CHANNEL_ID = "tts_playback";
    private static final int NOTIFICATION_ID = 1001;
    private static final int MAX_SEGMENT_CHARS = 220;

    interface Listener {
        void onTtsSegment(int startOffset, int endOffset);

        void onTtsState(boolean playing, boolean active);

        void onTtsStopped(int offset);
    }

    // 静态交接区（单进程）。
    static String pendingText;
    static int pendingOffset;
    static String pendingTitle;
    static String pendingBookId;
    static float pendingRate = 1.0f;
    static volatile Listener listener;
    static volatile TtsService active;
    static volatile String activeBookId;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean playing;
    private boolean playRequested;
    private String text = "";
    private String title = "";
    private final ArrayList<int[]> segments = new ArrayList<>();
    private int segmentIndex;
    private float rate = 1.0f;
    private AudioFocusRequest focusRequest;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            mainHandler.post(this::pausePlayback);
        }
    };

    static void start(Context context, String action) {
        Intent intent = new Intent(context, TtsService.class);
        intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    static boolean isActive() {
        return active != null;
    }

    static boolean isPlaying() {
        TtsService service = active;
        return service != null && service.playing;
    }

    static void applyRate(float newRate) {
        TtsService service = active;
        if (service != null) {
            service.rate = newRate;
            if (service.tts != null) {
                try {
                    service.tts.setSpeechRate(newRate);
                } catch (Exception ignored) {
                }
            }
            if (service.playing) {
                service.speakCurrentSegment();
            }
        }
    }

    static int currentOffsetOrDefault(int fallback) {
        TtsService service = active;
        if (service == null) {
            return fallback;
        }
        return service.currentOffset();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        active = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : nonEmpty(intent.getAction(), ACTION_STOP);
        switch (action) {
            case ACTION_PLAY: {
                if (pendingText != null) {
                    text = pendingText;
                    title = pendingTitle == null ? "" : pendingTitle;
                    activeBookId = pendingBookId;
                    rate = pendingRate;
                    int offset = Math.max(0, Math.min(pendingOffset, Math.max(0, text.length() - 1)));
                    pendingText = null;
                    pendingTitle = null;
                    pendingBookId = null;
                    computeSegments();
                    segmentIndex = segmentIndexForOffset(offset);
                    if (tts != null && ttsReady) {
                        try {
                            tts.setSpeechRate(rate);
                        } catch (Exception ignored) {
                        }
                    }
                }
                goForeground();
                playRequested = true;
                if (tts == null) {
                    tts = new TextToSpeech(this, this);
                } else if (ttsReady) {
                    startPlayback();
                }
                break;
            }
            case ACTION_TOGGLE: {
                if (playing) {
                    pausePlayback();
                } else {
                    goForeground();
                    playRequested = true;
                    if (tts == null) {
                        tts = new TextToSpeech(this, this);
                    } else if (ttsReady) {
                        startPlayback();
                    }
                }
                break;
            }
            case ACTION_PAUSE: {
                pausePlayback();
                break;
            }
            case ACTION_NEXT: {
                skipSegment(1);
                break;
            }
            case ACTION_PREV: {
                skipSegment(-1);
                break;
            }
            case ACTION_STOP:
            default: {
                stopPlaybackAndSelf();
                break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            mainHandler.post(this::stopPlaybackAndSelf);
            return;
        }
        ttsReady = true;
        try {
            tts.setLanguage(Locale.getDefault());
        } catch (Exception ignored) {
        }
        try {
            tts.setSpeechRate(rate);
        } catch (Exception ignored) {
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                int index = parseSegmentId(utteranceId);
                if (index >= 0) {
                    notifySegment(index);
                }
            }

            @Override
            public void onDone(String utteranceId) {
                int index = parseSegmentId(utteranceId);
                mainHandler.post(() -> {
                    if (!playing || index != segmentIndex) {
                        return;
                    }
                    if (segmentIndex + 1 < segments.size()) {
                        segmentIndex++;
                        speakCurrentSegment();
                    } else {
                        stopPlaybackAndSelf();
                    }
                });
            }

            @Override
            public void onError(String utteranceId) {
                mainHandler.post(() -> {
                    if (playing && segmentIndex + 1 < segments.size()) {
                        segmentIndex++;
                        speakCurrentSegment();
                    } else {
                        stopPlaybackAndSelf();
                    }
                });
            }
        });
        if (playRequested) {
            mainHandler.post(this::startPlayback);
        }
    }

    private void startPlayback() {
        if (segments.isEmpty() || tts == null || !ttsReady) {
            stopPlaybackAndSelf();
            return;
        }
        requestFocus();
        playing = true;
        playRequested = false;
        speakCurrentSegment();
        updateNotification();
        notifyState();
    }

    private void pausePlayback() {
        if (!playing) {
            return;
        }
        playing = false;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
        updateNotification();
        notifyState();
    }

    private void skipSegment(int direction) {
        if (segments.isEmpty()) {
            return;
        }
        segmentIndex = Math.max(0, Math.min(segments.size() - 1, segmentIndex + direction));
        if (playing) {
            speakCurrentSegment();
        } else {
            notifySegment(segmentIndex);
        }
    }

    private void speakCurrentSegment() {
        if (tts == null || !ttsReady || segments.isEmpty()) {
            return;
        }
        segmentIndex = Math.max(0, Math.min(segments.size() - 1, segmentIndex));
        int[] segment = segments.get(segmentIndex);
        String value = text.substring(segment[0], segment[1]);
        Bundle params = new Bundle();
        try {
            tts.speak(value, TextToSpeech.QUEUE_FLUSH, params, "seg:" + segmentIndex);
        } catch (Exception ignored) {
        }
    }

    private void stopPlaybackAndSelf() {
        int offset = currentOffset();
        playing = false;
        Listener current = listener;
        if (current != null) {
            mainHandler.post(() -> {
                Listener again = listener;
                if (again != null) {
                    again.onTtsStopped(offset);
                    again.onTtsState(false, false);
                }
            });
        }
        abandonFocus();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
        ttsReady = false;
        active = null;
        activeBookId = null;
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    int currentOffset() {
        if (segments.isEmpty()) {
            return 0;
        }
        int index = Math.max(0, Math.min(segmentIndex, segments.size() - 1));
        return segments.get(index)[0];
    }

    private void notifySegment(int index) {
        if (index < 0 || index >= segments.size()) {
            return;
        }
        int[] segment = segments.get(index);
        Listener current = listener;
        if (current != null) {
            mainHandler.post(() -> {
                Listener again = listener;
                if (again != null) {
                    again.onTtsSegment(segment[0], segment[1]);
                }
            });
        }
    }

    private void notifyState() {
        Listener current = listener;
        boolean state = playing;
        if (current != null) {
            mainHandler.post(() -> {
                Listener again = listener;
                if (again != null) {
                    again.onTtsState(state, active != null);
                }
            });
        }
    }

    private void computeSegments() {
        segments.clear();
        if (text == null || text.isEmpty()) {
            return;
        }
        int length = text.length();
        int start = 0;
        int i = 0;
        while (i < length) {
            char ch = text.charAt(i);
            boolean breakHere = ch == '\n' || "。！？!?；;…".indexOf(ch) >= 0;
            boolean tooLong = i - start + 1 >= MAX_SEGMENT_CHARS;
            if (breakHere || tooLong || i == length - 1) {
                int end = i + 1;
                String chunk = text.substring(start, end).trim();
                if (!chunk.isEmpty()) {
                    segments.add(new int[]{start, end});
                }
                start = end;
            }
            i++;
        }
        if (segments.isEmpty()) {
            segments.add(new int[]{0, length});
        }
    }

    private int segmentIndexForOffset(int offset) {
        for (int index = 0; index < segments.size(); index++) {
            int[] segment = segments.get(index);
            if (offset < segment[1]) {
                return index;
            }
        }
        return Math.max(0, segments.size() - 1);
    }

    private static int parseSegmentId(String utteranceId) {
        if (utteranceId == null || !utteranceId.startsWith("seg:")) {
            return -1;
        }
        try {
            return Integer.parseInt(utteranceId.substring(4));
        } catch (Exception exception) {
            return -1;
        }
    }

    private void requestFocus() {
        try {
            AudioManager manager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) {
                return;
            }
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            manager.requestAudioFocus(focusRequest);
        } catch (Exception ignored) {
        }
    }

    private void abandonFocus() {
        try {
            AudioManager manager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (manager != null && focusRequest != null) {
                manager.abandonAudioFocusRequest(focusRequest);
            }
        } catch (Exception ignored) {
        }
    }

    private void goForeground() {
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception ignored) {
        }
    }

    private void updateNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "朗读", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon_foreground)
                .setContentTitle(nonEmpty(title, "正在朗读"))
                .setContentText(playing ? "朗读中 · " + progressText() : "已暂停 · " + progressText())
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(activityIntent());
        builder.addAction(action("上一段", ACTION_PREV));
        builder.addAction(action(playing ? "暂停" : "播放", ACTION_TOGGLE));
        builder.addAction(action("下一段", ACTION_NEXT));
        builder.addAction(action("停止", ACTION_STOP));
        builder.setStyle(new Notification.MediaStyle().setShowActionsInCompactView(1, 3));
        return builder.build();
    }

    private String progressText() {
        if (segments.isEmpty() || text.isEmpty()) {
            return "";
        }
        int percent = Math.round(currentOffset() * 100.0f / Math.max(1, text.length()));
        return percent + "%";
    }

    private Notification.Action action(String label, String actionName) {
        Intent intent = new Intent(this, TtsService.class);
        intent.setAction(actionName);
        PendingIntent pending = PendingIntent.getService(
                this,
                actionName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.graphics.drawable.Icon icon =
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.app_icon_foreground);
        return new Notification.Action.Builder(icon, label, pending).build();
    }

    private PendingIntent activityIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 90, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onDestroy() {
        abandonFocus();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
        if (active == this) {
            active = null;
            activeBookId = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
