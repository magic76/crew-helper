package com.crewpocket.helper;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;

import java.util.List;
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
    private static final String ACTION_RESTART_WAKE = "com.crewpocket.helper.ALWAYS_ON_RESTART_WAKE";
    private static final int NOTIFICATION_ID = 8767;
    private static final String CHANNEL_ID = "crew_native_live";

    private static final long WAKE_HEALTH_INTERVAL_MS = 60000L;
    private static final int WAKE_HEALTH_STALE_LIMIT = 2;
    private static final int WAKE_FAST_RECOVERY_MAX = 3;
    private static final long WAKE_SLOW_PROBE_MS = 300000L;
    private static final long LIVE_TO_WAKE_COOLDOWN_MS = 1800L;
    private static final long EXTERNAL_MIC_RESUME_GRACE_MS = 800L;

    private enum RuntimeState { IDLE, ACTIVE }

    /** Backward-compatible meaning: true only while Gemini Live is active. */
    private static volatile boolean active;
    private static volatile boolean serviceRunning;
    private static NativeLiveService instance;

    interface RuntimeStateListener {
        void onRuntimeStateChanged();
    }

    private static final Handler RUNTIME_STATE_HANDLER =
            new Handler(Looper.getMainLooper());
    private static java.lang.ref.WeakReference<RuntimeStateListener>
            runtimeStateListener =
                    new java.lang.ref.WeakReference<RuntimeStateListener>(null);
    private static boolean runtimeStateDispatchPosted;

    static void setRuntimeStateListener(RuntimeStateListener listener) {
        runtimeStateListener =
                new java.lang.ref.WeakReference<RuntimeStateListener>(listener);
        notifyRuntimeStateChanged();
    }

    static void clearRuntimeStateListener(RuntimeStateListener listener) {
        RuntimeStateListener current = runtimeStateListener.get();
        if (current == listener) {
            runtimeStateListener.clear();
        }
    }

    private static void notifyRuntimeStateChanged() {
        synchronized (NativeLiveService.class) {
            if (runtimeStateDispatchPosted) return;
            runtimeStateDispatchPosted = true;
        }

        RUNTIME_STATE_HANDLER.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (NativeLiveService.class) {
                    runtimeStateDispatchPosted = false;
                }

                RuntimeStateListener listener = runtimeStateListener.get();
                if (listener != null) {
                    listener.onRuntimeStateChanged();
                }
            }
        }, 60L);
    }

    private RuntimeState runtimeState = RuntimeState.IDLE;
    private NativeGeminiLiveClient client;
    private final Handler visualHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService wakeExecutor = Executors.newSingleThreadExecutor();
    private SherpaWakeWordEngine wakeWordEngine;
    private WakeAcknowledgement wakeAcknowledgement;
    private int wakeGeneration;
    private int wakeRetryAttempts;
    private boolean externalMicSuspended;
    /** True only for automatic Android recording-contention suspension. */
    private boolean externalMicAutoYield;
    private int externalRecordingCount;
    private String externalMicReason = "";
    private AudioManager audioManager;
    private AudioManager.AudioRecordingCallback audioRecordingCallback;
    private boolean foregroundStarted;
    private boolean alwaysOnEnabled;
    private boolean sharingCamera;
    private boolean sharingScreen;
    private int reconnectAttempts;
    private boolean stopRequested;
    /** 0031: wall-clock of the latest real user transcript/typed instruction. */
    private long lastUserInstructionAtMs;

    // 0025-hotfix3 diagnostics. No audio content is retained.
    private volatile String lastWakeStatus = "not started";
    private volatile String lastWakeError = "";
    private volatile String lastWakeEvent = "service created";

    private volatile String wakeHealthState = "IDLE";
    private volatile String wakeBlockKind = "";
    private volatile String wakeBlockReason = "";
    private long wakeHealthLastCheckAtMs;
    private long wakeHealthLastReadCount = -1L;
    private long wakeHealthLastDecodeCount = -1L;
    private int wakeHealthStaleChecks;
    private int wakeHealthRestartCount;
    private int wakeFastRecoveryAttempts;
    private int wakeSlowProbeCount;
    private String wakeHealthLastReason = "not checked";

    private final Runnable externalMicResumeRunnable = new Runnable() {
        @Override public void run() {
            if (!externalMicSuspended || !externalMicAutoYield
                    || active || !alwaysOnEnabled) return;
            if (hasExternalRecordingNow()) {
                visualHandler.postDelayed(this, EXTERNAL_MIC_RESUME_GRACE_MS);
                return;
            }
            externalMicSuspended = false;
            externalMicAutoYield = false;
            externalRecordingCount = 0;
            externalMicReason = "";
            wakeHealthState = "STARTING";
            clearWakeBlock();
            resetWakeHealthBaseline("external mic released");
            lastWakeEvent = "external mic released";
            lastWakeStatus = "STARTING";
            updateForegroundNotification("其他 App 已釋放麥克風，正在恢復喚醒詞");
            startIdleWakeWord();
            armWakeHealthWatchdog();
        }
    };

    private final Runnable reconnectRunnable = new Runnable() {
        @Override public void run() {
            if (!active || stopRequested) return;
            startLiveClient();
        }
    };

    private final Runnable liveIdleTimeoutRunnable = new Runnable() {
        @Override public void run() {
            if (!active || stopRequested) return;

            int minutes = AppConfig.getLiveIdleTimeoutMinutes(NativeLiveService.this);
            if (minutes <= 0) return;

            long timeoutMs = minutes * 60_000L;
            long now = System.currentTimeMillis();
            if (lastUserInstructionAtMs <= 0L) lastUserInstructionAtMs = now;
            long ageMs = Math.max(0L, now - lastUserInstructionAtMs);

            if (ageMs < timeoutMs) {
                visualHandler.postDelayed(this, Math.max(1000L, timeoutMs - ageMs));
                return;
            }

            NativeGeminiLiveClient live = client;
            if (live != null && (live.hasActiveAgentTask() || live.isAiSpeaking())) {
                visualHandler.postDelayed(this, 15_000L);
                return;
            }

            returnToIdle("閒置 " + minutes + " 分鐘，自動結束語音");
        }
    };

    private void noteLiveUserInstruction() {
        if (!active) return;
        lastUserInstructionAtMs = System.currentTimeMillis();
        armLiveIdleTimeout();
    }

    private void armLiveIdleTimeout() {
        visualHandler.removeCallbacks(liveIdleTimeoutRunnable);
        if (!active || stopRequested) return;
        int minutes = AppConfig.getLiveIdleTimeoutMinutes(this);
        if (minutes <= 0) return;
        if (lastUserInstructionAtMs <= 0L) {
            lastUserInstructionAtMs = System.currentTimeMillis();
        }
        long timeoutMs = minutes * 60_000L;
        long ageMs = Math.max(0L, System.currentTimeMillis() - lastUserInstructionAtMs);
        visualHandler.postDelayed(liveIdleTimeoutRunnable, Math.max(1000L, timeoutMs - ageMs));
    }

    static void refreshLiveIdleTimeout() {
        NativeLiveService service = instance;
        if (service != null) service.armLiveIdleTimeout();
    }

    private final Runnable wakeRetryRunnable = new Runnable() {
        @Override public void run() {
            if (!alwaysOnEnabled || active || externalMicSuspended) return;
            startIdleWakeWord();
            armWakeHealthWatchdog();
        }
    };

    private final Runnable wakeHealthRunnable = new Runnable() {
        @Override public void run() {
            runWakeHealthCheck();
        }
    };

    private final Runnable wakeSlowProbeRunnable = new Runnable() {
        @Override public void run() {
            if (!alwaysOnEnabled || active || externalMicSuspended) return;
            wakeSlowProbeCount++;
            wakeHealthState = "RECOVERING";
            wakeHealthLastReason = "slow probe #" + wakeSlowProbeCount;
            stopIdleWakeWord();
            resetWakeHealthBaseline("slow probe");
            startIdleWakeWord();
            armWakeHealthWatchdog();
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
        if (service.externalMicSuspended) return "IDLE_MIC_YIELDED";
        SherpaWakeWordEngine engine = service.wakeWordEngine;
        if (engine != null && engine.isRunning()) return "IDLE_LISTENING";
        if (!service.wakeBlockKind.isEmpty()) return "BLOCKED";
        if ("DEGRADED".equals(service.wakeHealthState)) return "DEGRADED";
        return "IDLE";
    }

    static String getWakeDiagnostics(Context context) {
        NativeLiveService service = instance;
        boolean prefEnabled = context != null && AppConfig.isAlwaysOnEnabled(context);
        boolean micPermission = context != null
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                   == PackageManager.PERMISSION_GRANTED);
        boolean notificationPermission = context != null
                && (Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                   == PackageManager.PERMISSION_GRANTED);
        boolean notificationsEnabled = true;
        try {
            NotificationManager manager = context == null ? null
                    : (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationsEnabled = manager == null || manager.areNotificationsEnabled();
        } catch (Throwable ignored) {}

        StringBuilder out = new StringBuilder();
        out.append("runtime=").append(getRuntimeState());
        out.append("\nservice.running=").append(serviceRunning);
        out.append("\npref.alwaysOn=").append(prefEnabled);
        out.append("\npermission.mic=").append(micPermission);
        out.append("\nnotification.permission=").append(notificationPermission);
        out.append("\nnotification.enabled=").append(notificationsEnabled);

        if (service == null) {
            out.append("\nservice.instance=null");
            out.append("\nHINT=service 尚未啟動；先啟用全天待命");
            return out.toString();
        }

        out.append("\nservice.instance=READY");
        out.append("\nservice.alwaysOn=").append(service.alwaysOnEnabled);
        out.append("\nservice.active=").append(active);
        out.append("\nservice.foreground=").append(service.foregroundStarted);
        out.append("\nservice.externalMicSuspended=").append(service.externalMicSuspended);
        out.append("\nservice.externalMicAutoYield=").append(service.externalMicAutoYield);
        out.append("\nservice.externalRecordingCount=").append(service.externalRecordingCount);
        out.append("\nservice.externalMicReason=").append(blankAsDash(service.externalMicReason));
        out.append("\nservice.externalMicResumeGraceMs=").append(EXTERNAL_MIC_RESUME_GRACE_MS);
        out.append("\nwake.generation=").append(service.wakeGeneration);
        out.append("\nwake.retryAttempts=").append(service.wakeRetryAttempts);
        out.append("\nwake.lastEvent=").append(service.lastWakeEvent);
        out.append("\nwake.lastStatus=").append(blankAsDash(service.lastWakeStatus));
        out.append("\nwake.lastError=").append(blankAsDash(service.lastWakeError));
        out.append("\nhealth.state=").append(service.wakeHealthState);
        out.append("\nhealth.blockKind=").append(blankAsDash(service.wakeBlockKind));
        out.append("\nhealth.blockReason=").append(blankAsDash(service.wakeBlockReason));
        out.append("\nhealth.intervalMs=").append(WAKE_HEALTH_INTERVAL_MS);
        out.append("\nhealth.lastCheckAgoMs=")
                .append(ageMs(SystemClock.elapsedRealtime(), service.wakeHealthLastCheckAtMs));
        out.append("\nhealth.staleChecks=").append(service.wakeHealthStaleChecks);
        out.append("\nhealth.restarts=").append(service.wakeHealthRestartCount);
        out.append("\nhealth.fastRecoveryAttempts=").append(service.wakeFastRecoveryAttempts);
        out.append("\nhealth.slowProbeCount=").append(service.wakeSlowProbeCount);
        out.append("\nhealth.lastReason=").append(blankAsDash(service.wakeHealthLastReason));
        out.append("\nhealth.liveToWakeCooldownMs=").append(LIVE_TO_WAKE_COOLDOWN_MS);

        SherpaWakeWordEngine engine = service.wakeWordEngine;
        out.append("\nengine.instance=").append(engine != null ? "READY" : "null");
        if (engine != null) {
            out.append("\n--- engine ---\n").append(engine.getDiagnostics());
        } else if (service.alwaysOnEnabled && !active) {
            out.append("\nHINT=Always-On 已開，但 engine.instance=null；查看 lastError/lastStatus");
        }
        return out.toString();
    }

    private static String blankAsDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    private static String ageMs(long now, long timestamp) {
        return timestamp <= 0 ? "-" : String.valueOf(Math.max(0L, now - timestamp));
    }

    private void registerExternalMicMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || audioRecordingCallback != null) return;
        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) return;
            audioRecordingCallback = new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(
                        List<AudioRecordingConfiguration> configs) {
                    handleRecordingConfigChanged(configs);
                }
            };
            audioManager.registerAudioRecordingCallback(audioRecordingCallback, visualHandler);
        } catch (Throwable error) {
            audioRecordingCallback = null;
            android.util.Log.w("CrewNativeLive", "External mic monitor unavailable: "
                    + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private void unregisterExternalMicMonitor() {
        visualHandler.removeCallbacks(externalMicResumeRunnable);
        AudioManager manager = audioManager;
        AudioManager.AudioRecordingCallback callback = audioRecordingCallback;
        audioRecordingCallback = null;
        audioManager = null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                || manager == null || callback == null) return;
        try { manager.unregisterAudioRecordingCallback(callback); }
        catch (Throwable ignored) {}
    }

    private void handleRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !alwaysOnEnabled || active) return;

        if (externalMicSuspended) {
            if (!externalMicAutoYield) return;
            externalRecordingCount = configs == null ? 0 : configs.size();
            visualHandler.removeCallbacks(externalMicResumeRunnable);
            visualHandler.postDelayed(externalMicResumeRunnable, EXTERNAL_MIC_RESUME_GRACE_MS);
            return;
        }

        SherpaWakeWordEngine engine = wakeWordEngine;
        int ownSession = engine == null ? -1 : engine.getAudioSessionId();
        // Avoid startup races where our own just-created AudioRecord appears
        // before wakeWordEngine has been published on the main thread.
        if (ownSession <= 0 || configs == null) return;

        int external = 0;
        for (AudioRecordingConfiguration config : configs) {
            if (config == null) continue;
            int session = -1;
            try { session = config.getClientAudioSessionId(); }
            catch (Throwable ignored) {}
            if (session != ownSession) external++;
        }
        if (external > 0) {
            suspendIdleWakeForExternalMic("AudioManager detected another recorder", external);
        }
    }

    private void suspendIdleWakeForExternalMic(String reason, int count) {
        if (active || !alwaysOnEnabled) return;
        externalMicSuspended = true;
        externalMicAutoYield = true;
        externalRecordingCount = Math.max(1, count);
        externalMicReason = reason == null ? "external recording" : reason;
        wakeHealthState = "SUSPENDED_EXTERNAL_MIC";
        lastWakeEvent = "external mic auto-yield";
        lastWakeStatus = "SUSPENDED_EXTERNAL_MIC";
        visualHandler.removeCallbacks(wakeRetryRunnable);
        visualHandler.removeCallbacks(wakeHealthRunnable);
        visualHandler.removeCallbacks(wakeSlowProbeRunnable);
        visualHandler.removeCallbacks(externalMicResumeRunnable);
        stopIdleWakeWord();
        updateForegroundNotification("其他 App 正在使用麥克風；喚醒詞已暫停");
        visualHandler.postDelayed(externalMicResumeRunnable, EXTERNAL_MIC_RESUME_GRACE_MS);
    }

    private boolean hasExternalRecordingNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        AudioManager manager = audioManager;
        if (manager == null) return false;
        try {
            List<AudioRecordingConfiguration> configs =
                    manager.getActiveRecordingConfigurations();
            externalRecordingCount = configs == null ? 0 : configs.size();
            return externalRecordingCount > 0;
        } catch (Throwable error) {
            android.util.Log.w("CrewNativeLive", "Cannot inspect active recordings: "
                    + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            return false;
        }
    }

    static void reconcileAlwaysOn(Context context) {
        if (context == null || !AppConfig.isAlwaysOnEnabled(context)) return;

        NativeLiveService running = instance;
        if (!serviceRunning || running == null) {
            startServiceAction(context, ACTION_ENABLE_ALWAYS_ON, true);
            return;
        }

        running.visualHandler.post(new Runnable() {
            @Override public void run() {
                if (!AppConfig.isAlwaysOnEnabled(running)) return;
                running.alwaysOnEnabled = true;
                if (active || running.externalMicSuspended) return;

                SherpaWakeWordEngine engine = running.wakeWordEngine;
                if (engine != null && engine.isRunning()) {
                    running.armWakeHealthWatchdog();
                    return;
                }

                if ("SETUP".equals(running.wakeBlockKind)) return;
                running.restartWakeInternal("app-open reconcile", true);
            }
        });
    }

    private void resetWakeHealthBaseline(String reason) {
        wakeHealthLastReadCount = -1L;
        wakeHealthLastDecodeCount = -1L;
        wakeHealthStaleChecks = 0;
        wakeHealthLastReason = reason;
    }

    private void clearWakeBlock() {
        wakeBlockKind = "";
        wakeBlockReason = "";
    }

    private void setWakeBlock(String kind, String reason) {
        wakeBlockKind = kind == null ? "" : kind;
        wakeBlockReason = reason == null ? "" : reason;
        wakeHealthState = "BLOCKED";
        wakeHealthLastReason = "blocked " + wakeBlockKind + ": " + wakeBlockReason;
    }

    private void armWakeHealthWatchdog() {
        visualHandler.removeCallbacks(wakeHealthRunnable);
        if (!alwaysOnEnabled || active || externalMicSuspended) return;
        if ("DEGRADED".equals(wakeHealthState) || "BLOCKED".equals(wakeHealthState)) return;
        visualHandler.postDelayed(wakeHealthRunnable, WAKE_HEALTH_INTERVAL_MS);
    }

    private void scheduleSlowProbe(String reason) {
        if (!alwaysOnEnabled || active || externalMicSuspended) return;
        visualHandler.removeCallbacks(wakeSlowProbeRunnable);
        wakeHealthState = "DEGRADED";
        wakeHealthLastReason = "slow recovery: " + reason;
        updateForegroundNotification("喚醒監聽暫時異常，稍後自動重試");
        visualHandler.postDelayed(wakeSlowProbeRunnable, WAKE_SLOW_PROBE_MS);
    }

    private void scheduleWakeRecovery(String reason) {
        if (!alwaysOnEnabled || active || externalMicSuspended) return;

        visualHandler.removeCallbacks(wakeRetryRunnable);
        visualHandler.removeCallbacks(wakeHealthRunnable);

        if (wakeFastRecoveryAttempts >= WAKE_FAST_RECOVERY_MAX) {
            scheduleSlowProbe(reason);
            return;
        }

        wakeFastRecoveryAttempts++;
        wakeRetryAttempts = wakeFastRecoveryAttempts;
        wakeHealthRestartCount++;
        wakeHealthState = "RECOVERING";
        wakeHealthLastReason = "fast recovery "
                + wakeFastRecoveryAttempts + "/" + WAKE_FAST_RECOVERY_MAX
                + ": " + reason;
        lastWakeEvent = "health fast recovery";
        lastWakeStatus = "RECOVERING";
        updateForegroundNotification("喚醒監聽自動恢復中");

        stopIdleWakeWord();
        resetWakeHealthBaseline("fast recovery");
        long delay = 1000L << (wakeFastRecoveryAttempts - 1);
        visualHandler.postDelayed(wakeRetryRunnable, delay);
    }

    private void runWakeHealthCheck() {
        if (!alwaysOnEnabled || active || externalMicSuspended) return;

        wakeHealthLastCheckAtMs = SystemClock.elapsedRealtime();

        if (!ensureMicrophonePermission()) {
            setWakeBlock("PERMISSION", "RECORD_AUDIO missing");
            visualHandler.removeCallbacks(wakeHealthRunnable);
            scheduleSlowProbe("microphone permission missing");
            return;
        }

        SherpaWakeWordEngine engine = wakeWordEngine;
        if (engine == null) {
            scheduleWakeRecovery("engine instance missing");
            return;
        }
        if (!engine.isRunning()) {
            scheduleWakeRecovery("engine not running; phase=" + engine.getPhase());
            return;
        }
        if (!engine.isWorkerAlive()) {
            scheduleWakeRecovery("worker thread dead; phase=" + engine.getPhase());
            return;
        }
        if (!engine.isMicRecording()) {
            setWakeBlock("MIC", "AudioRecord not recording");
            scheduleWakeRecovery("AudioRecord not recording; phase=" + engine.getPhase());
            return;
        }

        long reads = engine.getAudioReadCount();
        long decodes = engine.getDecodeCount();

        if (wakeHealthLastReadCount < 0L || wakeHealthLastDecodeCount < 0L) {
            wakeHealthLastReadCount = reads;
            wakeHealthLastDecodeCount = decodes;
            wakeHealthStaleChecks = 0;
            wakeHealthState = "LISTENING";
            wakeHealthLastReason = "healthy baseline";
            armWakeHealthWatchdog();
            return;
        }

        boolean audioAdvanced = reads > wakeHealthLastReadCount;
        boolean decodeAdvanced = decodes > wakeHealthLastDecodeCount;
        wakeHealthLastReadCount = reads;
        wakeHealthLastDecodeCount = decodes;

        if (audioAdvanced && decodeAdvanced) {
            wakeHealthStaleChecks = 0;
            wakeFastRecoveryAttempts = 0;
            wakeRetryAttempts = 0;
            wakeHealthState = "HEALTHY";
            clearWakeBlock();
            wakeHealthLastReason = "healthy: audio+decode advancing";
            armWakeHealthWatchdog();
            return;
        }

        wakeHealthStaleChecks++;
        wakeHealthLastReason = "stale "
                + wakeHealthStaleChecks + "/" + WAKE_HEALTH_STALE_LIMIT
                + ": audioAdvanced=" + audioAdvanced
                + ", decodeAdvanced=" + decodeAdvanced;

        if (wakeHealthStaleChecks >= WAKE_HEALTH_STALE_LIMIT) {
            scheduleWakeRecovery(wakeHealthLastReason);
        } else {
            armWakeHealthWatchdog();
        }
    }

    private void restartWakeInternal(String reason, boolean resetRecoveryBudget) {
        if (!alwaysOnEnabled || active || externalMicSuspended) return;

        visualHandler.removeCallbacks(wakeRetryRunnable);
        visualHandler.removeCallbacks(wakeHealthRunnable);
        visualHandler.removeCallbacks(wakeSlowProbeRunnable);

        if (resetRecoveryBudget) {
            wakeFastRecoveryAttempts = 0;
            wakeRetryAttempts = 0;
            clearWakeBlock();
        }

        wakeHealthState = "RECOVERING";
        wakeHealthLastReason = reason;
        lastWakeEvent = "manual/reconcile restart";
        lastWakeStatus = "RECOVERING";
        lastWakeError = "";
        stopIdleWakeWord();
        resetWakeHealthBaseline(reason);

        visualHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!alwaysOnEnabled || active || externalMicSuspended) return;
                startIdleWakeWord();
                armWakeHealthWatchdog();
            }
        }, 400L);
    }

    private void restartWakeFromNotification() {
        if (!alwaysOnEnabled) return;
        if (!ensureMicrophonePermission()) {
            setWakeBlock("PERMISSION", "RECORD_AUDIO missing");
            updateForegroundNotification("缺少麥克風權限，請開啟 Crew Helper");
            return;
        }
        restartWakeInternal("notification restart", true);
    }

    static void enableAlwaysOn(Context context) {
        if (context == null) return;
        AppConfig.setAlwaysOnEnabled(context, true);
        notifyRuntimeStateChanged();
        NativeLiveService running = instance;
        if (running != null) {
            running.visualHandler.post(new Runnable() {
                @Override public void run() { running.enableAlwaysOnInternal(); }
            });
            return;
        }
        startServiceAction(context, ACTION_ENABLE_ALWAYS_ON, true);
    }

    static void refreshAlwaysOnNotification() {
        NativeLiveService running = instance;
        if (running == null || !running.foregroundStarted) return;
        running.visualHandler.post(new Runnable() {
            @Override public void run() {
                if (active) {
                    running.updateForegroundNotification("Gemini Live 使用中");
                } else if (running.alwaysOnEnabled) {
                    running.updateForegroundNotification(
                        "正在等待喚醒詞「" + AppConfig.getWakePhrase(running) + "」");
                }
            }
        });
    }

    static void disableAlwaysOn(Context context) {
        if (context == null) return;
        AppConfig.setAlwaysOnEnabled(context, false);
        notifyRuntimeStateChanged();
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
                running.externalMicAutoYield = false;
                running.externalRecordingCount = 0;
                running.externalMicReason = "Crew Helper page-owned Live";
                running.visualHandler.removeCallbacks(running.externalMicResumeRunnable);
                running.wakeHealthState = "SUSPENDED";
                running.visualHandler.removeCallbacks(running.wakeHealthRunnable);
                running.visualHandler.removeCallbacks(running.wakeSlowProbeRunnable);
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
                running.visualHandler.removeCallbacks(running.externalMicResumeRunnable);
                running.externalMicSuspended = false;
                running.externalMicAutoYield = false;
                running.externalRecordingCount = 0;
                running.externalMicReason = "";
                if (running.alwaysOnEnabled && !active) {
                    running.updateForegroundNotification("正在等待喚醒詞「" + AppConfig.getWakePhrase(running) + "」");
                    running.wakeHealthState = "STARTING";
                    running.resetWakeHealthBaseline("external mic resumed");
                    running.visualHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            running.startIdleWakeWord();
                            running.armWakeHealthWatchdog();
                        }
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

    /** 0085: one-shot screen observation for the Bubble Mini Console. */
    static boolean sendScreenSnapshot() {
        NativeLiveService running = instance;
        if (running == null || !active || running.client == null) return false;
        try {
            running.client.sendScreenFrame();
            return true;
        } catch (Exception error) {
            running.updateStatus("畫面擷取失敗", true);
            return false;
        }
    }

    /** 0085: one-shot back-camera capture for the Bubble Mini Console. */
    static boolean sendCameraSnapshot() {
        final NativeLiveService running = instance;
        if (running == null || !active || running.client == null) return false;
        try {
            CameraCaptureManager.capturePhoto(
                    running,
                    false,
                    new CameraCaptureManager.CaptureCallback() {
                        @Override public void onSuccess(String path) {
                            if (active && running.client != null) {
                                running.client.sendCameraFrame(path);
                            }
                        }
                        @Override public void onError(String error) {
                            running.updateStatus(
                                    "相機失敗：" + (error == null ? "unknown" : error),
                                    true);
                        }
                    });
            return true;
        } catch (Exception error) {
            running.updateStatus("相機啟動失敗", true);
            return false;
        }
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

    static boolean selectPendingUiChoice(String elementId) {
        return instance != null && instance.client != null
                && instance.client.selectPendingUiChoice(elementId);
    }

    static void cancelPendingUiChoice() {
        if (instance != null && instance.client != null) instance.client.cancelPendingUiChoice("使用者取消選擇");
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
        notifyRuntimeStateChanged();
        CorrectionLearningRuntime.init(this);
        wakeAcknowledgement = new WakeAcknowledgement(this);
        alwaysOnEnabled = AppConfig.isAlwaysOnEnabled(this);
        DeckRepository.initialize(this);
        createChannel();
        registerExternalMicMonitor();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_DISABLE_ALWAYS_ON.equals(action)) {
            disableAlwaysOnInternal();
            return START_NOT_STICKY;
        }

        if (ACTION_RESTART_WAKE.equals(action)) {
            restartWakeFromNotification();
            return alwaysOnEnabled ? START_STICKY : START_NOT_STICKY;
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
            wakeHealthState = "STARTING";
            clearWakeBlock();
            resetWakeHealthBaseline("service restored");
            startIdleWakeWord();
            armWakeHealthWatchdog();
            return START_STICKY;
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    private void enableAlwaysOnInternal() {
        AppConfig.setAlwaysOnEnabled(this, true);
        alwaysOnEnabled = true;
        externalMicSuspended = false;
        externalMicAutoYield = false;
        externalRecordingCount = 0;
        externalMicReason = "";
        visualHandler.removeCallbacks(externalMicResumeRunnable);
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
            wakeHealthState = "STARTING";
            wakeFastRecoveryAttempts = 0;
            wakeRetryAttempts = 0;
            clearWakeBlock();
            resetWakeHealthBaseline("always-on enabled");
            startIdleWakeWord();
            armWakeHealthWatchdog();
        }
    }

    private void disableAlwaysOnInternal() {
        AppConfig.setAlwaysOnEnabled(this, false);
        alwaysOnEnabled = false;
        externalMicSuspended = false;
        externalMicAutoYield = false;
        externalRecordingCount = 0;
        externalMicReason = "";
        visualHandler.removeCallbacks(externalMicResumeRunnable);
        wakeHealthState = "OFF";
        clearWakeBlock();
        visualHandler.removeCallbacks(wakeHealthRunnable);
        visualHandler.removeCallbacks(wakeSlowProbeRunnable);
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

        visualHandler.removeCallbacks(wakeHealthRunnable);
        visualHandler.removeCallbacks(wakeSlowProbeRunnable);
        wakeHealthState = "ACTIVE";
        resetWakeHealthBaseline("Gemini ACTIVE");
        stopIdleWakeWord();
        visualHandler.removeCallbacks(externalMicResumeRunnable);
        externalMicSuspended = false;
        externalMicAutoYield = false;
        externalRecordingCount = 0;
        externalMicReason = "";
        runtimeState = RuntimeState.ACTIVE;
        active = true;
        stopRequested = false;
        lastUserInstructionAtMs = System.currentTimeMillis();
        armLiveIdleTimeout();
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
        if ("wake_word".equals(source) && wakeAcknowledgement != null) { wakeAcknowledgement.speak("在呢", new WakeAcknowledgement.Callback() { @Override public void onDone() { visualHandler.postDelayed(new Runnable() { @Override public void run() { if (active && !stopRequested) startLiveClient(); } }, 80L); } }); } else visualHandler.postDelayed(new Runnable() {
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
        lastWakeEvent = "startIdleWakeWord queued";
        lastWakeStatus = "queued";
        lastWakeError = "";
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
                                        // A wake-word activation must leave an obvious, reachable
                                        // on-screen control.  Do this before the Live client takes
                                        // over the microphone; showBubble() is a safe no-op when
                                        // overlay permission has not been granted.
                                        FloatingBubbleManager.getInstance(NativeLiveService.this)
                                                .showBubble();
                                        enterActive("wake_word");
                                    }
                                });
                            }

                            @Override public void onStatus(final String status) {
                                visualHandler.post(new Runnable() {
                                    @Override public void run() {
                                        lastWakeEvent = "engine status";
                                        lastWakeStatus = status == null ? "" : status;
                                        if (!active && alwaysOnEnabled) updateForegroundNotification(status);
                                    }
                                });
                            }

                            @Override public void onCaptureSilenced(final boolean silenced) {
                                if (!silenced) return;
                                visualHandler.post(new Runnable() {
                                    @Override public void run() {
                                        if (generation != wakeGeneration
                                                || active || !alwaysOnEnabled) return;
                                        suspendIdleWakeForExternalMic(
                                                "Android silenced wake-word capture", 1);
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
                            wakeHealthState = "LISTENING";
                            clearWakeBlock();
                            resetWakeHealthBaseline("engine listening");
                            lastWakeEvent = "engine start returned true";
                            lastWakeStatus = "LISTENING";
                            updateForegroundNotification("正在等待喚醒詞「" + phrase + "」");
                            armWakeHealthWatchdog();
                        } else {
                            lastWakeEvent = "engine start returned false";
                            if (lastWakeError == null || lastWakeError.isEmpty()) {
                                lastWakeError = "engine.start() returned false";
                            }
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
        lastWakeEvent = "engine error";
        lastWakeError = message;
        lastWakeStatus = "ERROR";
        updateForegroundNotification(message);

        String upper = message.toUpperCase(java.util.Locale.ROOT);

        if (message.contains("麥克風權限")
                || upper.contains("RECORD_AUDIO")
                || upper.contains("PERMISSION")) {
            setWakeBlock("PERMISSION", message);
            scheduleSlowProbe("permission blocked");
            return;
        }

        if (upper.contains("AUDIORECORD")
                || upper.contains("MICROPHONE")
                || message.contains("麥克風")
                || message.contains("錄音")) {
            setWakeBlock("MIC", message);
            scheduleWakeRecovery("microphone blocked");
            return;
        }

        if ((upper.contains("SHERPA")
                && (upper.contains("JNI") || upper.contains("AAR")
                    || upper.contains("NOCLASSDEFFOUNDERROR")))
                || message.contains("模型資源")
                || message.contains("模型檔")
                || message.contains("目前只支援喚醒詞")) {
            setWakeBlock("SETUP", message);
            visualHandler.removeCallbacks(wakeHealthRunnable);
            visualHandler.removeCallbacks(wakeSlowProbeRunnable);
            updateForegroundNotification("喚醒引擎設定異常，請開啟 Crew Helper 查看診斷");
            return;
        }

        scheduleWakeRecovery(message);
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
        final String voiceName = AppConfig.getVoiceName(this);
        client = new NativeGeminiLiveClient(this, apiKey, "", voiceName,
                AppConfig.getNoiseMode(this), AppConfig.getNoiseSuppression(this),
                AppConfig.getLiveTone(this), AppConfig.getCustomSystemPrompt(this),
                AppConfig.getInterruptionSensitivity(this), AppConfig.getAudioOutput(this),
                new NativeGeminiLiveClient.Listener() {
                    @Override public void onStatus(String text) {
                        if (text != null && text.contains("已連線")) reconnectAttempts = 0;
                        updateStatus(text, true);

                        NativeGeminiLiveClient live = client;
                        boolean taskActive =
                                live != null && live.hasActiveAgentTask();
                        org.json.JSONArray history =
                                live == null ? null : live.getAgentTaskHistory();

                        AgentInspectorStore.record(
                                NativeLiveService.this,
                                text,
                                history,
                                taskActive);
                        FloatingBubbleManager.getInstance(
                                NativeLiveService.this)
                                .updateAgentTaskStatus(text, taskActive);
                        notifyRuntimeStateChanged();
                    }
                    @Override public void onStopped(String reason) {
                        NativeGeminiLiveClient live = client;
                        AgentInspectorStore.record(
                                NativeLiveService.this,
                                reason,
                                live == null ? null : live.getAgentTaskHistory(),
                                live != null && live.hasActiveAgentTask());
                        FloatingBubbleManager.getInstance(
                                NativeLiveService.this)
                                .updateAgentTaskStatus(reason, false);
                        handleClientStopped(reason);
                        notifyRuntimeStateChanged();
                    }
                    @Override public void onTranscript(String role, String text) {
                        if ("你".equals(role) && text != null && !text.trim().isEmpty()) {
                            noteLiveUserInstruction();
                        }
                        FloatingBubbleManager.getInstance(NativeLiveService.this).updateLiveTranscript(role, text);
                    }
                    @Override public void onSpeakingChanged(boolean speaking) {
                        FloatingBubbleManager.getInstance(
                                NativeLiveService.this)
                                .refreshVoiceControls();
                        notifyRuntimeStateChanged();
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
        visualHandler.removeCallbacks(liveIdleTimeoutRunnable);
        lastUserInstructionAtMs = 0L;
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
            wakeHealthState = "COOLDOWN";
            clearWakeBlock();
            resetWakeHealthBaseline("Live -> IDLE cooldown");
            ensureForeground("對話已結束，稍後恢復喚醒監聽");
            visualHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!active && alwaysOnEnabled && !externalMicSuspended) {
                        wakeHealthState = "STARTING";
                        startIdleWakeWord();
                        armWakeHealthWatchdog();
                    }
                }
            }, LIVE_TO_WAKE_COOLDOWN_MS);
        } else {
            stopRuntime(reason);
        }
    }

    private synchronized void stopRuntime(String reason) {
        alwaysOnEnabled = false;
        stopRequested = true;
        notifyRuntimeStateChanged();
        active = false;
        runtimeState = RuntimeState.IDLE;
        stopIdleWakeWord();
        visualHandler.removeCallbacks(reconnectRunnable);
        visualHandler.removeCallbacks(visualFrameSender);
        visualHandler.removeCallbacks(wakeRetryRunnable);
        visualHandler.removeCallbacks(wakeHealthRunnable);
        visualHandler.removeCallbacks(wakeSlowProbeRunnable);
        visualHandler.removeCallbacks(externalMicResumeRunnable);
        visualHandler.removeCallbacks(liveIdleTimeoutRunnable);
        lastUserInstructionAtMs = 0L;
        externalMicSuspended = false;
        externalMicAutoYield = false;
        externalRecordingCount = 0;
        externalMicReason = "";
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
        notifyRuntimeStateChanged();
    }

    private void updateForegroundNotification(String text) {
        if (!foregroundStarted) return;
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(text));
            }
        } catch (Exception ignored) {}
        notifyRuntimeStateChanged();
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

        if (!active && alwaysOnEnabled) {
            int actionFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) actionFlags |= PendingIntent.FLAG_IMMUTABLE;

            Intent restartIntent = new Intent(this, NativeLiveService.class)
                    .setAction(ACTION_RESTART_WAKE);
            PendingIntent restartPending = PendingIntent.getService(
                    this, 87671, restartIntent, actionFlags);

            Intent disableIntent = new Intent(this, NativeLiveService.class)
                    .setAction(ACTION_DISABLE_ALWAYS_ON);
            PendingIntent disablePending = PendingIntent.getService(
                    this, 87672, disableIntent, actionFlags);

            builder.addAction(
                    android.R.drawable.ic_popup_sync,
                    "重新啟動待命",
                    restartPending);
            builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "關閉全天待命",
                    disablePending);
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
        visualHandler.removeCallbacks(wakeHealthRunnable);
        visualHandler.removeCallbacks(wakeSlowProbeRunnable);
        visualHandler.removeCallbacks(externalMicResumeRunnable);
        visualHandler.removeCallbacks(liveIdleTimeoutRunnable);
        lastUserInstructionAtMs = 0L;
        unregisterExternalMicMonitor();
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
        WakeAcknowledgement ackClosing = wakeAcknowledgement; wakeAcknowledgement = null;
        if (ackClosing != null) try { ackClosing.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
