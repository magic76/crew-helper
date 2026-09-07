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
import android.os.Vibrator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 0025: single-owner audio runtime.
 *
 * IDLE   -> sherpa-onnx KWS owns the microphone and only detects the wake phrase.
 * ACTIVE -> sherpa-onnx KWS is fully stopped before Gemini Live opens the microphone.
 *
 * This service deliberately stays independent of Accessibility so voice can
 * remain available even when phone-control capability is unavailable.
 */
public class NativeLiveService extends Service {
    private static final String ACTION_START = "com.crewpocket.helper.NATIVE_LIVE_START";
    private static final String ACTION_STOP = "com.crewpocket.helper.NATIVE_LIVE_STOP";
    private static final String ACTION_ENABLE_ALWAYS_ON = "com.crewpocket.helper.ALWAYS_ON_ENABLE";
    private static final String ACTION_DISABLE_ALWAYS_ON = "com.crewpocket.helper.ALWAYS_ON_DISABLE";
    private static final int NOTIFICATION_ID = 8767;
    private static final String CHANNEL_ID = "crew_native_live";

    private enum RuntimeState { IDLE, ACTIVE }

    /** Backward-compatible meaning: true only while Gemini Live is active. */
    private static volatile boolean active;
    private static volatile boolean serviceRunning;
    private static NativeLiveService instance;

    private RuntimeState runtimeState = RuntimeState.IDLE;
    private NativeGeminiLiveClient client;
    private final Handler visualHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService wakeExecutor = Executors.newSingleThreadExecutor();
    private SherpaWakeWordEngine wakeWordEngine;
    private int wakeGeneration;
    private int wakeRetryAttempts;
    private boolean externalMicSuspended;
    private boolean foregroundStarted;
    private boolean alwaysOnEnabled;
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

    private final Runnable wakeRetryRunnable = new Runnable() {
        @Override public void run() {
            if (!alwaysOnEnabled || active || externalMicSuspended) return;
            startIdleWakeWord();
        }
    };

    static boolean isActive() { return active; }
    static boolean isAlwaysOnRunning() {
        return serviceRunning && instance != null && instance.alwaysOnEnabled;
    }
    static String getRuntimeState() {
        NativeLiveService service = instance;
        if (!serviceRunning || service == null) return "STOPPED";
        if (active) return "ACTIVE";
        SherpaWakeWordEngine engine = service.wakeWordEngine;
        return engine != null && engine.isRunning() ? "IDLE_LISTENING" : "IDLE";
    }

    static void enableAlwaysOn(Context context) {
        if (context == null) return;
        AppConfig.setAlwaysOnEnabled(context, true);
        NativeLiveService running = instance;
        if (running != null) {
            running.visualHandler.post(new Runnable() {
                @Override public void run() { running.enableAlwaysOnInternal(); }
            });
            return;
        }
        startServiceAction(context, ACTION_ENABLE_ALWAYS_ON, true);
    }

    static void disableAlwaysOn(Context context) {
        if (context == null) return;
        AppConfig.setAlwaysOnEnabled(context, false);
        NativeLiveService running = instance;
        if (running != null) {
            running.visualHandler.post(new Runnable() {
                @Override public void run() { running.disableAlwaysOnInternal(); }
            });
        }
    }

    static void start(Context context) {
        NativeLiveService running = instance;
        if (running != null) {
            running.visualHandler.post(new Runnable() {
                @Override public void run() { running.enterActive("manual"); }
            });
            return;
        }
        startServiceAction(context, ACTION_START, true);
    }

    static void stop(Context context) {
        NativeLiveService running = instance;
        if (running != null) {
            running.visualHandler.post(new Runnable() {
                @Override public void run() { running.returnToIdle("已結束"); }
            });
        }
    }

    /** Compatibility bridge for page-owned Live audio. Never starts a service. */
    static void suspendIdleWakeIfRunning() {
        NativeLiveService running = instance;
        if (running == null) return;
        running.visualHandler.post(new Runnable() {
            @Override public void run() {
                running.externalMicSuspended = true;
                running.stopIdleWakeWord();
                running.updateForegroundNotification("其他 Live 畫面正在使用麥克風");
            }
        });
    }

    /** Compatibility bridge for page-owned Live audio. Never starts a service. */
    static void resumeIdleWakeIfRunning() {
        NativeLiveService running = instance;
        if (running == null) return;
        running.visualHandler.post(new Runnable() {
            @Override public void run() {
                running.externalMicSuspended = false;
                if (running.alwaysOnEnabled && !active) {
                    running.updateForegroundNotification("正在等待喚醒詞「" + AppConfig.getWakePhrase(running) + "」");
                    running.visualHandler.postDelayed(new Runnable() {
                        @Override public void run() { running.startIdleWakeWord(); }
                    }, 250L);
                }
            }
        });
    }

    private static void startServiceAction(Context context, String action, boolean foreground) {
        if (context == null) return;
        Intent intent = new Intent(context, NativeLiveService.class).setAction(action);
        try {
            if (foreground && Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception error) {
            try { context.startService(intent); } catch (Exception ignored) {}
        }
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

    static boolean interruptForCorrection() {
        return instance != null
                && instance.client != null
                && instance.client.beginCorrectionWindow();
    }

    static boolean interruptAiSpeech() { return interruptForCorrection(); }

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

    static boolean stopAgentTask() {
        return instance != null && instance.client != null && instance.client.cancelAgentTask("使用者按下停止任務");
    }

    static boolean hasActiveAgentTask() {
        return instance != null && instance.client != null && instance.client.hasActiveAgentTask();
    }

    static boolean isAiSpeaking() {
        return instance != null && instance.client != null && instance.client.isAiSpeaking();
    }

    static int getInterruptionSensitivity(Context context) {
        return instance != null && instance.client != null ? instance.client.getInterruptionSensitivity()
                : AppConfig.getInterruptionSensitivity(context);
    }

    static void setInterruptionSensitivity(Context context, int value) {
        AppConfig.setInterruptionSensitivity(context, value);
        if (instance != null && instance.client != null) instance.client.setInterruptionSensitivity(value);
    }

    static boolean isCameraSharing() { return instance != null && instance.sharingCamera; }
    static boolean isScreenSharing() { return instance != null && instance.sharingScreen; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        serviceRunning = true;
        alwaysOnEnabled = AppConfig.isAlwaysOnEnabled(this);
        DeckRepository.initialize(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_DISABLE_ALWAYS_ON.equals(action)) {
            disableAlwaysOnInternal();
            return START_NOT_STICKY;
        }

        if (ACTION_ENABLE_ALWAYS_ON.equals(action)) {
            enableAlwaysOnInternal();
            return START_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            returnToIdle("已結束");
            return alwaysOnEnabled ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            if (!ensureMicrophonePermission()) {
                stopRuntime("未取得麥克風權限，請先允許麥克風再開始通話");
                return START_NOT_STICKY;
            }
            ensureForeground("正在啟動 Gemini Live");
            enterActive("service_action");
            return alwaysOnEnabled ? START_STICKY : START_NOT_STICKY;
        }

        // START_STICKY process recreation: restore only an explicitly enabled
        // always-on session. Never start background microphone monitoring merely
        // because Accessibility exists.
        alwaysOnEnabled = AppConfig.isAlwaysOnEnabled(this);
        if (alwaysOnEnabled && ensureMicrophonePermission()) {
            ensureForeground("正在等待喚醒詞「" + AppConfig.getWakePhrase(this) + "」");
            runtimeState = RuntimeState.IDLE;
            active = false;
            startIdleWakeWord();
            return START_STICKY;
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    private void enableAlwaysOnInternal() {
        AppConfig.setAlwaysOnEnabled(this, true);
        alwaysOnEnabled = true;
        externalMicSuspended = false;
        if (!ensureMicrophonePermission()) {
            AppConfig.setAlwaysOnEnabled(this, false);
            alwaysOnEnabled = false;
            stopRuntime("全天待命需要麥克風權限");
            return;
        }
        ensureForeground("正在等待喚醒詞「" + AppConfig.getWakePhrase(this) + "」");
        if (!active) {
            stopIdleWakeWord();
            runtimeState = RuntimeState.IDLE;
            startIdleWakeWord();
        }
    }

    private void disableAlwaysOnInternal() {
        AppConfig.setAlwaysOnEnabled(this, false);
        alwaysOnEnabled = false;
        externalMicSuspended = false;
        stopIdleWakeWord();
        if (active) {
            updateForegroundNotification("Gemini Live 使用中 · 全天待命已關閉");
        } else {
            stopRuntime("全天待命已關閉");
        }
    }

    private boolean ensureMicrophonePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private synchronized void enterActive(String source) {
        if (active) return;
        if (!ensureMicrophonePermission()) {
            stopRuntime("未取得麥克風權限，請先允許麥克風再開始通話");
            return;
        }

        stopIdleWakeWord();
        externalMicSuspended = false;
        runtimeState = RuntimeState.ACTIVE;
        active = true;
        stopRequested = false;
        reconnectAttempts = 0;
        wakeRetryAttempts = 0;
        ensureForeground("Gemini Live 使用中");

        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if ("wake_word".equals(source) && vibrator != null) {
                vibrator.vibrate(new long[]{0, 40, 60, 40}, -1);
            }
        } catch (Exception ignored) {}

        FloatingBubbleManager.getInstance(this).updateNativeLiveStatus("正在連線 Gemini Live", true);
        NativeLiveActivity.releaseLocalClientForService();

        // Give sherpa KWS / page-owned AudioRecord a short deterministic release
        // window before Oboe opens the Live microphone.
        visualHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (active && !stopRequested) startLiveClient();
            }
        }, 180L);
    }

    private void startIdleWakeWord() {
        if (!alwaysOnEnabled || active || externalMicSuspended) return;
        if (!ensureMicrophonePermission()) return;
        if (wakeWordEngine != null && wakeWordEngine.isRunning()) return;

        final int generation = ++wakeGeneration;
        final String phrase = AppConfig.getWakePhrase(this);
        final float sensitivity = AppConfig.getWakeSensitivity(this) / 100f;
        updateForegroundNotification("正在準備本機喚醒詞「" + phrase + "」");

        wakeExecutor.execute(new Runnable() {
            @Override public void run() {
                final SherpaWakeWordEngine engine = new SherpaWakeWordEngine(
                        NativeLiveService.this,
                        phrase,
                        sensitivity,
                        new SherpaWakeWordEngine.Listener() {
                            @Override public void onDetected() {
                                visualHandler.post(new Runnable() {
                                    @Override public void run() {
                                        if (!alwaysOnEnabled || active || externalMicSuspended) return;
                                        enterActive("wake_word");
                                    }
                                });
                            }

                            @Override public void onStatus(final String status) {
                                visualHandler.post(new Runnable() {
                                    @Override public void run() {
                                        if (!active && alwaysOnEnabled) updateForegroundNotification(status);
                                    }
                                });
                            }

                            @Override public void onError(final String error) {
                                visualHandler.post(new Runnable() {
                                    @Override public void run() {
                                        handleWakeWordError(generation, error);
                                    }
                                });
                            }
                        });

                final boolean started = engine.start();
                visualHandler.post(new Runnable() {
                    @Override public void run() {
                        if (generation != wakeGeneration
                                || active || externalMicSuspended || !alwaysOnEnabled) {
                            engine.release();
                            return;
                        }
                        if (started) {
                            wakeWordEngine = engine;
                            wakeRetryAttempts = 0;
                            updateForegroundNotification("正在等待喚醒詞「" + phrase + "」");
                        } else {
                            engine.release();
                        }
                    }
                });
            }
        });
    }

    private void handleWakeWordError(int generation, String error) {
        if (generation != wakeGeneration
                || !alwaysOnEnabled || active || externalMicSuspended) return;

        String message = error == null ? "Wake Word 啟動失敗" : error;
        updateForegroundNotification(message);

        // Missing local runtime/model assets need a build/setup fix. Do not
        // burn battery retrying a deterministic setup failure forever.
        String upper = message.toUpperCase(java.util.Locale.ROOT);
        if ((upper.contains("SHERPA") && (upper.contains("JNI") || upper.contains("AAR")))
                || message.contains("模型資源")
                || message.contains("模型檔")
                || message.contains("目前只支援喚醒詞")) {
            return;
        }

        wakeRetryAttempts = Math.min(6, wakeRetryAttempts + 1);
        long delay = Math.min(60000L, 3000L * wakeRetryAttempts);
        visualHandler.removeCallbacks(wakeRetryRunnable);
        visualHandler.postDelayed(wakeRetryRunnable, delay);
    }

    private synchronized void stopIdleWakeWord() {
        wakeGeneration++;
        visualHandler.removeCallbacks(wakeRetryRunnable);
        SherpaWakeWordEngine closing = wakeWordEngine;
        wakeWordEngine = null;
        if (closing != null) closing.release();
    }

    private void startLiveClient() {
        if (!active || stopRequested) return;
        NativeLiveActivity.releaseLocalClientForService();
        if (client != null) {
            try { client.stop(); } catch (Exception ignored) {}
            client = null;
        }
        final String apiKey = AppConfig.getGeminiApiKey(this);
        if (apiKey.length() < 20) {
            returnToIdle("尚未設定 Gemini API Key，請至主畫面填寫");
            return;
        }
        final String serverUrl = AppConfig.getServerUrl(this);
        final String voiceName = AppConfig.getVoiceName(this);
        client = new NativeGeminiLiveClient(apiKey, serverUrl, voiceName,
                AppConfig.getNoiseMode(this), AppConfig.getNoiseSuppression(this),
                AppConfig.getLiveTone(this), AppConfig.getCustomSystemPrompt(this),
                AppConfig.getInterruptionSensitivity(this), AppConfig.getAudioOutput(this),
                new NativeGeminiLiveClient.Listener() {
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
        client.setAgentMaxSteps(AppConfig.getAgentMaxSteps(this));
        client.start();
    }

    private void handleClientStopped(String reason) {
        if (!active) return;
        if (stopRequested) {
            returnToIdle(reason);
            return;
        }
        if (isGracefulCallEnd(reason)) {
            returnToIdle(reason);
            return;
        }
        if (reconnectAttempts >= 3) {
            returnToIdle("重連 3 次仍失敗：" + reason);
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
        FloatingBubbleManager.getInstance(this).updateNativeLiveStatus(status, showOngoing);
        updateForegroundNotification(status == null || status.isEmpty() ? "Gemini Live 使用中" : status);
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
                // Never feed vision frames while Gemini is producing the answer.
            } else if (sharingCamera) {
                if (CameraPreviewOverlay.getInstance(NativeLiveService.this).isShowing()) {
                    byte[] liveFrame = CameraPreviewOverlay.getInstance(NativeLiveService.this).getLatestJpegFrame();
                    if (liveFrame != null && liveFrame.length > 0) {
                        if (active && sharingCamera && client != null) client.sendCameraBytes(liveFrame);
                    } else {
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

    private synchronized void returnToIdle(String reason) {
        visualHandler.removeCallbacks(reconnectRunnable);
        CameraPreviewOverlay.getInstance(this).hide();
        sharingCamera = false;
        sharingScreen = false;
        visualHandler.removeCallbacks(visualFrameSender);

        active = false;
        runtimeState = RuntimeState.IDLE;
        stopRequested = true;
        NativeGeminiLiveClient closing = client;
        client = null;
        if (closing != null && closing.isRunning()) {
            try { closing.stop(); } catch (Exception ignored) {}
        }

        FloatingBubbleManager.getInstance(this).updateNativeLiveStatus(reason, false);

        if (alwaysOnEnabled) {
            stopRequested = false;
            ensureForeground("正在等待喚醒詞「" + AppConfig.getWakePhrase(this) + "」");
            visualHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!active && alwaysOnEnabled && !externalMicSuspended) startIdleWakeWord();
                }
            }, 300L);
        } else {
            stopRuntime(reason);
        }
    }

    private synchronized void stopRuntime(String reason) {
        alwaysOnEnabled = false;
        stopRequested = true;
        active = false;
        runtimeState = RuntimeState.IDLE;
        stopIdleWakeWord();
        visualHandler.removeCallbacks(reconnectRunnable);
        visualHandler.removeCallbacks(visualFrameSender);
        visualHandler.removeCallbacks(wakeRetryRunnable);
        CameraPreviewOverlay.getInstance(this).hide();
        sharingCamera = false;
        sharingScreen = false;

        NativeGeminiLiveClient closing = client;
        client = null;
        if (closing != null && closing.isRunning()) {
            try { closing.stop(); } catch (Exception ignored) {}
        }

        FloatingBubbleManager.getInstance(this).updateNativeLiveStatus(reason, false);
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID); } catch (Exception ignored) {}
        if (foregroundStarted) {
            try { stopForeground(true); } catch (Exception ignored) {}
            foregroundStarted = false;
        }
        stopSelf();
    }

    private void ensureForeground(String text) {
        Notification notification = buildNotification(text);
        startForeground(NOTIFICATION_ID, notification);
        foregroundStarted = true;
    }

    private void updateForegroundNotification(String text) {
        if (!foregroundStarted) return;
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {}
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags);

        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(active ? "Crew Helper · Gemini Live" : "Crew Helper · 全天待命")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setChannelId(CHANNEL_ID);
        }
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Class<?> cls = Class.forName("android.app.NotificationChannel");
            Object channel = cls.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL_ID, "Crew Helper Always-On", NotificationManager.IMPORTANCE_LOW);
            NotificationManager.class.getMethod("createNotificationChannel", cls)
                    .invoke((NotificationManager) getSystemService(NOTIFICATION_SERVICE), channel);
        } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        serviceRunning = false;
        instance = null;
        active = false;
        alwaysOnEnabled = false;
        stopRequested = true;
        wakeGeneration++;
        visualHandler.removeCallbacks(reconnectRunnable);
        visualHandler.removeCallbacks(visualFrameSender);
        visualHandler.removeCallbacks(wakeRetryRunnable);
        SherpaWakeWordEngine wakeClosing = wakeWordEngine;
        wakeWordEngine = null;
        if (wakeClosing != null) wakeClosing.release();
        try { wakeExecutor.shutdownNow(); } catch (Exception ignored) {}
        CameraPreviewOverlay.getInstance(this).hide();
        sharingCamera = false;
        sharingScreen = false;
        NativeGeminiLiveClient liveClosing = client;
        client = null;
        if (liveClosing != null && liveClosing.isRunning()) {
            try { liveClosing.stop(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
