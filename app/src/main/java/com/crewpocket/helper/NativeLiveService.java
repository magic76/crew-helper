package com.crewpocket.helper;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

/**
 * Owns Gemini Live independently of any Activity.  Android keeps a microphone
 * foreground service alive while the user uses another app; the floating bubble
 * is only a compact start/stop control.
 */
public class NativeLiveService extends Service {
    private static final String ACTION_START = "com.crewpocket.helper.NATIVE_LIVE_START";
    private static final String ACTION_STOP = "com.crewpocket.helper.NATIVE_LIVE_STOP";
    private static final int NOTIFICATION_ID = 8767;
    private static final String CHANNEL_ID = "crew_native_live";
    private static volatile boolean active;
    private static NativeLiveService instance;
    private NativeGeminiLiveClient client;
    private final Handler visualHandler = new Handler(Looper.getMainLooper());
    private boolean sharingCamera;
    private boolean sharingScreen;
    private int reconnectAttempts;
    private boolean stopRequested;
    private final Runnable reconnectRunnable = new Runnable() {
        @Override public void run() {
            if (!active || stopRequested) return;
            startLiveClient();
        }
    };

    static boolean isActive() { return active; }

    static void start(Context context) {
        Intent intent = new Intent(context, NativeLiveService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Context.class.getMethod("startForegroundService", Intent.class).invoke(context, intent);
            } else context.startService(intent);
        } catch (Exception error) {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.startService(new Intent(context, NativeLiveService.class).setAction(ACTION_STOP));
    }

    static boolean toggleCameraSharing() {
        return instance != null && instance.toggleVisualSharing(true);
    }

    static boolean toggleScreenSharing() {
        return instance != null && instance.toggleVisualSharing(false);
    }

    static boolean toggleAgentMute() {
        return instance != null && instance.client != null && instance.client.toggleAgentMute();
    }

    static boolean toggleVoiceInterruption() {
        if (instance != null && instance.client != null) {
            boolean current = instance.client.isVoiceInterruptionAllowed();
            instance.client.setAllowVoiceInterruption(!current);
            return !current;
        }
        return true;
    }

    static boolean isVoiceInterruptionAllowed() {
        return instance != null && instance.client != null && instance.client.isVoiceInterruptionAllowed();
    }

    static boolean isAgentMuted() {
        return instance != null && instance.client != null && instance.client.isAgentMuted();
    }

    static boolean isAiSpeaking() {
        return instance != null && instance.client != null && instance.client.isAiSpeaking();
    }

    static boolean isCameraSharing() { return instance != null && instance.sharingCamera; }
    static boolean isScreenSharing() { return instance != null && instance.sharingScreen; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            end("已結束");
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            end("未取得麥克風權限，請先允許麥克風再開始通話");
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("正在連線 Gemini Live"));
        String key = AppConfig.getGeminiApiKey(this);
        if (key.length() < 20) {
            end("尚未設定 Gemini API Key，請至主畫面填寫");
            return START_NOT_STICKY;
        }
        active = true;
        stopRequested = false;
        reconnectAttempts = 0;
        if (CrewAccessibilityService.getInstance() != null) {
            CrewAccessibilityService.getInstance().stopNativeWakeWordListener();
        }
        FloatingBubbleManager.getInstance(this).updateNativeLiveStatus("正在連線 Gemini Live", true);
        startLiveClient();
        return START_STICKY;
    }

    private void startLiveClient() {
        if (!active || stopRequested) return;
        final String apiKey = AppConfig.getGeminiApiKey(this);
        if (apiKey.length() < 20) {
            end("尚未設定 Gemini API Key，請至主畫面填寫");
            return;
        }
        final String serverUrl = AppConfig.getServerUrl(this);
        final String voiceName = AppConfig.getVoiceName(this);
        client = new NativeGeminiLiveClient(apiKey, serverUrl, voiceName, AppConfig.getNoiseMode(this), AppConfig.getNoiseSuppression(this), AppConfig.getLiveTone(this), AppConfig.getCustomSystemPrompt(this), new NativeGeminiLiveClient.Listener() {
            @Override public void onStatus(String text) {
                if (text != null && text.contains("已連線")) reconnectAttempts = 0;
                updateStatus(text, true);
            }
            @Override public void onStopped(String reason) {
                handleClientStopped(reason);
            }
            @Override public void onTranscript(String role, String text) {
                FloatingBubbleManager.getInstance(NativeLiveService.this).updateLiveTranscript(role, text);
            }
            @Override public void onSpeakingChanged(boolean speaking) {
                FloatingBubbleManager.getInstance(NativeLiveService.this).refreshVoiceControls();
            }
            @Override public void onMicrophoneLevel(double dbfs, double gateDbfs, boolean sending) {
                FloatingBubbleManager.getInstance(NativeLiveService.this).updateLiveMicrophoneLevel(dbfs, sending);
            }
        });
        client.start();
    }

    private void handleClientStopped(String reason) {
        if (!active || stopRequested) {
            end(reason);
            return;
        }
        // end_voice_session and a user hang-up both reach NativeGeminiLiveClient.stop(),
        // which reports "已結束".  That is a deliberate end, never a reconnect case.
        if (isGracefulCallEnd(reason)) {
            end(reason);
            return;
        }
        if (reconnectAttempts >= 3) {
            end("重連 3 次仍失敗：" + reason);
            return;
        }
        reconnectAttempts++;
        client = null;
        long delayMs = 900L * reconnectAttempts;
        updateStatus("連線中斷，正在重新連線（" + reconnectAttempts + "/3）…", true);
        visualHandler.removeCallbacks(reconnectRunnable);
        visualHandler.postDelayed(reconnectRunnable, delayMs);
    }

    private boolean isGracefulCallEnd(String reason) {
        String text = reason == null ? "" : reason.trim();
        return "已結束".equals(text) || text.contains("使用者結束") || text.contains("掛斷");
    }

    private void updateStatus(String status, boolean showOngoing) {
        if (!active) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(status));
        FloatingBubbleManager.getInstance(this).updateNativeLiveStatus(status, showOngoing);
    }

    private boolean toggleVisualSharing(boolean camera) {
        if (!active || client == null) return false;
        if (camera) {
            sharingCamera = !sharingCamera;
            if (sharingCamera) {
                sharingScreen = false;
                CameraPreviewOverlay.getInstance(this).show();
            } else {
                CameraPreviewOverlay.getInstance(this).hide();
            }
        } else {
            sharingScreen = !sharingScreen;
            if (sharingScreen) {
                sharingCamera = false;
                CameraPreviewOverlay.getInstance(this).hide();
            }
        }
        visualHandler.removeCallbacks(visualFrameSender);
        if (sharingCamera || sharingScreen) visualHandler.post(visualFrameSender);
        if (sharingScreen) updateStatus("螢幕分享已啟用，等待 Gemini 連線後傳送最新畫面", true);
        return camera ? sharingCamera : sharingScreen;
    }

    private final Runnable visualFrameSender = new Runnable() {
        @Override public void run() {
            if (!active || client == null) return;
            if (!client.canSendVisualFrame()) {
                // Match the web Live client: never feed vision frames back
                // into Gemini while it is still producing the current answer.
            } else if (sharingCamera) {
                if (CameraPreviewOverlay.getInstance(NativeLiveService.this).isShowing()) {
                    byte[] liveFrame = CameraPreviewOverlay.getInstance(NativeLiveService.this).getLatestJpegFrame();
                    if (liveFrame != null && liveFrame.length > 0) {
                        if (active && sharingCamera && client != null) client.sendCameraBytes(liveFrame);
                    } else {
                        // If preview frame not ready yet, fallback to single frame capture
                        CameraCaptureManager.capturePhoto(NativeLiveService.this, false, new CameraCaptureManager.CaptureCallback() {
                            @Override public void onSuccess(String path) { if (active && sharingCamera && client != null) client.sendCameraFrame(path); }
                            @Override public void onError(String error) { updateStatus("相機影格失敗：" + error, true); }
                        });
                    }
                } else {
                    CameraCaptureManager.capturePhoto(NativeLiveService.this, false, new CameraCaptureManager.CaptureCallback() {
                        @Override public void onSuccess(String path) { if (active && sharingCamera && client != null) client.sendCameraFrame(path); }
                        @Override public void onError(String error) { updateStatus("相機影格失敗：" + error, true); }
                    });
                }
            } else if (sharingScreen) {
                client.sendScreenFrame();
            }
            if (sharingCamera || sharingScreen) visualHandler.postDelayed(this, 2000);
        }
    };

    private synchronized void end(String reason) {
        stopRequested = true;
        visualHandler.removeCallbacks(reconnectRunnable);
        CameraPreviewOverlay.getInstance(this).hide();
        if (!active && client == null) {
            FloatingBubbleManager.getInstance(this).updateNativeLiveStatus(reason, false);
            stopForeground(true); stopSelf(); return;
        }
        active = false;
        sharingCamera = false;
        sharingScreen = false;
        visualHandler.removeCallbacks(visualFrameSender);
        NativeGeminiLiveClient closing = client;
        client = null;
        if (closing != null && closing.isRunning()) closing.stop();
        FloatingBubbleManager.getInstance(this).updateNativeLiveStatus(reason, false);
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID); } catch (Exception ignored) {}
        if (CrewAccessibilityService.getInstance() != null) {
            CrewAccessibilityService.getInstance().startNativeWakeWordListener();
        }
        stopForeground(true);
        stopSelf();
    }

    private Notification buildNotification(String status) {
        Intent open = new Intent(this, NativeLiveActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= 0x04000000; // FLAG_IMMUTABLE
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);
        Intent stopIntent = new Intent(this, NativeLiveService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent, flags);
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Crew Pocket 語音通話中")
                .setContentText(status)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "結束", stop).build());
        if (Build.VERSION.SDK_INT >= 26) {
            try { Notification.Builder.class.getMethod("setChannelId", String.class).invoke(builder, CHANNEL_ID); } catch (Exception ignored) {}
        }
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Class<?> cls = Class.forName("android.app.NotificationChannel");
            Object channel = cls.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL_ID, "Crew Pocket 即時語音", NotificationManager.IMPORTANCE_LOW);
            NotificationManager.class.getMethod("createNotificationChannel", cls)
                    .invoke((NotificationManager) getSystemService(NOTIFICATION_SERVICE), channel);
        } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        stopRequested = true;
        visualHandler.removeCallbacks(reconnectRunnable);
        active = false;
        sharingCamera = false;
        sharingScreen = false;
        visualHandler.removeCallbacks(visualFrameSender);
        instance = null;
        NativeGeminiLiveClient closing = client;
        client = null;
        if (closing != null && closing.isRunning()) closing.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
