package com.crewpocket.helper;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioAttributes;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Gemini Live backed by OkHttp's production WebSocket implementation. */
final class NativeGeminiLiveClient extends WebSocketListener {
    private static final String TAG = "CrewNativeLive";
    interface Listener {
        void onStatus(String text);
        void onStopped(String reason);
        void onTranscript(String role, String text);
        void onSpeakingChanged(boolean speaking);
        void onMicrophoneLevel(double dbfs, double gateDbfs, boolean sending);
    }
    private final String apiKey;
    private final String serverUrl;
    private final String voiceName;
    private volatile String noiseMode;
    private volatile int noiseSuppression;
    private final String liveTone;
    private volatile int interruptionSensitivity;
    private final String audioOutput;
    private final Listener listener;
    /** App-owned storage must not depend on AccessibilityService being alive. */
    private final Context appContext;
    private volatile boolean running;
    private volatile String stage = "尚未開始";
    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private AudioRecord recorder;
    private AudioTrack player;
    private volatile boolean usingOboeOutput;
    private volatile String audioOutputBackend = "尚未初始化";
    // WebSocket callbacks must stay fast: audio writes can block for a whole
    // buffer. Keep PCM on a bounded queue and feed AudioTrack from one thread.
    private final BlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<byte[]>(96);
    private final Object playerLock = new Object();
    private volatile boolean audioPlaybackRunning;
    private Thread audioPlaybackThread;
    private String resumptionHandle;
    private boolean reconnecting;
    private volatile long visualHoldUntil;
    private volatile boolean setupReady;
    private long screenFrameSequence;
    // Dimensions of the latest image actually shown to Gemini.  They can be
    // smaller than the physical 1440x3120 screen after compression.
    private volatile int lastVisionWidth = 1;
    private volatile int lastVisionHeight = 1;
    private volatile int lastScreenWidth = 1;
    private volatile int lastScreenHeight = 1;
    private final Set<String> handledToolCalls = new HashSet<String>();
    // Phone tasks routinely need several semantic actions plus model turns.
    // Observation/verification calls do not consume the mutation-action budget.
    private static final long AGENT_TASK_TIMEOUT_MS = 180_000L;
    private static final long AGENT_FINAL_RESPONSE_WAIT_MS = 12_000L;
    private static final int AGENT_MAX_TOOL_RUNS = 8;
    private static final int AGENT_MAX_MUTATION_ACTIONS = 15;
    private static final int AGENT_MAX_SCREENSHOTS = 3;
    // Navigation is deliberately repeatable during a presentation. All other
    // tools keep the conservative 3-run default safety limit.
    private static final int AGENT_DECK_NAV_MAX_RUNS = 16;
    private final Object agentLock = new Object();
    private final ArrayList<JSONObject> pendingToolCalls = new ArrayList<JSONObject>();
    private final ArrayList<AgentTaskRecord> agentHistory = new ArrayList<AgentTaskRecord>();
    private volatile boolean toolWorkerRunning;
    private volatile Thread activeToolThread;
    private volatile HttpURLConnection activeToolConnection;
    private volatile int agentMaxSteps = 30;
    private AgentTaskRecord activeAgentTask;
    // 0018: a goal can span several execution tasks/follow-ups inside one Live session.
    // Safety/tool budgets remain per AgentTaskRecord; they are NOT shared for the whole call.
    private static final long CONVERSATION_GOAL_IDLE_MS = 45_000L;
    private String conversationGoalId = "";
    private long conversationGoalTouchedAt = 0L;
    private int conversationGoalTaskIndex = 0;
    private String conversationGoalHint = "";
    private final Handler agentWatchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable agentResponseWatchdog;
    private String customPrompt = "";
    private final ArrayList<JSONObject> lastCandidateApps = new ArrayList<JSONObject>();
    private final java.util.concurrent.atomic.AtomicBoolean screenCaptureInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String lastObservedScreenFingerprint = "";
    private volatile int consecutiveNoProgress = 0;
    private volatile boolean semanticObserveRequired = false;
    private volatile String latestSemanticFingerprint = "";
    private final WorkingContext workingContext = new WorkingContext();
    private final UserActionScope userActionScope = new UserActionScope();
    private String authorizationTranscript = "";
    private MemoryRuleIndex memoryRuleIndex;
    private String lastMemoryDispatchKey = "";
    private long lastMemoryDispatchAt = 0L;
    private AudioIncidentRecorder audioIncidentRecorder;
    private volatile PendingCondition pendingCondition = null;

    NativeGeminiLiveClient(String apiKey, Listener listener) { this(apiKey, "", AppConfig.DEFAULT_VOICE, "auto", 35, "warm", "", 55, "call", listener); }
    NativeGeminiLiveClient(String apiKey, String serverUrl, Listener listener) { this(apiKey, serverUrl, AppConfig.DEFAULT_VOICE, "auto", 35, "warm", "", 55, "call", listener); }
    NativeGeminiLiveClient(String apiKey, String serverUrl, String voiceName, String noiseMode, int noiseSuppression, Listener listener) {
        this(apiKey, serverUrl, voiceName, noiseMode, noiseSuppression, "warm", "", 55, "call", listener);
    }
    NativeGeminiLiveClient(String apiKey, String serverUrl, String voiceName, String noiseMode, int noiseSuppression, String liveTone, Listener listener) {
        this(apiKey, serverUrl, voiceName, noiseMode, noiseSuppression, liveTone, "", 55, "call", listener);
    }
    NativeGeminiLiveClient(String apiKey, String serverUrl, String voiceName, String noiseMode, int noiseSuppression, String liveTone, String customPrompt, Listener listener) {
        this(apiKey, serverUrl, voiceName, noiseMode, noiseSuppression, liveTone, customPrompt, 55, "call", listener);
    }
    NativeGeminiLiveClient(String apiKey, String serverUrl, String voiceName, String noiseMode, int noiseSuppression, String liveTone, String customPrompt, int interruptionSensitivity, String audioOutput, Listener listener) {
        this(null, apiKey, serverUrl, voiceName, noiseMode, noiseSuppression, liveTone, customPrompt,
                interruptionSensitivity, audioOutput, listener);
    }
    NativeGeminiLiveClient(Context context, String apiKey, String serverUrl, String voiceName, String noiseMode, int noiseSuppression, String liveTone, String customPrompt, int interruptionSensitivity, String audioOutput, Listener listener) {
        this.appContext = context == null ? null : context.getApplicationContext();
        this.memoryRuleIndex = this.appContext == null ? null : new MemoryRuleIndex(this.appContext);
        this.audioIncidentRecorder = this.appContext == null ? null : new AudioIncidentRecorder(this.appContext);
        this.apiKey = apiKey;
        this.serverUrl = serverUrl == null ? "" : serverUrl.trim();
        this.voiceName = voiceName == null || voiceName.trim().isEmpty() ? AppConfig.DEFAULT_VOICE : voiceName.trim();
        this.noiseMode = "quiet".equals(noiseMode) || "noisy".equals(noiseMode) ? noiseMode : "auto";
        this.noiseSuppression = Math.max(0, Math.min(100, noiseSuppression));
        this.liveTone = liveTone == null ? "warm" : liveTone;
        this.customPrompt = customPrompt == null ? "" : customPrompt.trim();
        this.interruptionSensitivity = Math.max(0, Math.min(100, interruptionSensitivity));
        this.audioOutput = "media".equals(audioOutput) ? "media" : "call";
        this.listener = listener;
    }
    boolean isRunning() { return running; }
    String getStage() { return stage; }
    String getAudioOutputBackend() { return audioOutputBackend; }
    boolean canSendVisualFrame() { return running && System.currentTimeMillis() >= visualHoldUntil; }
    void setAgentMaxSteps(int steps) { agentMaxSteps = Math.max(1, Math.min(100, steps)); }
    int getAgentMaxSteps() { return agentMaxSteps; }
    boolean hasActiveAgentTask() { synchronized (agentLock) { return activeAgentTask != null && !activeAgentTask.finished; } }
    String getAgentTaskStatus() { synchronized (agentLock) { return activeAgentTask == null ? "" : activeAgentTask.status; } }
    JSONArray getAgentTaskHistory() {
        synchronized (agentLock) {
            JSONArray records = new JSONArray();
            for (AgentTaskRecord task : agentHistory) records.put(task.toJson());
            if (activeAgentTask != null) records.put(activeAgentTask.toJson());
            return records;
        }
    }

    /**
     * Interrupt + Correction.
     *
     * - stop playback immediately
     * - cancel/freeze unfinished phone-agent work
     * - retain only a non-sensitive task hint
     * - open a short window in which an elliptical utterance is interpreted
     *   as a correction to the interrupted/recent task
     */
    boolean beginCorrectionWindow() {
        if (!running) return false;

        // IMPORTANT: interrupt is a LOCAL CONTROL EVENT, not a conversation turn.
        // Do not call sendInternalAgentDirective() here. Doing so can make Gemini
        // acknowledge the interruption with "好的/了解/你繼續", which is unwanted.
        if (aiSpeaking) {
            triggerLocalInterruption();
        } else {
            stopPlayback();
        }

        String hint = "";
        boolean hadTask = false;
        synchronized (agentLock) {
            if (activeAgentTask != null && !activeAgentTask.finished) {
                hadTask = true;
                // Do not retain tool args, typed text, message bodies, or other
                // potentially sensitive content.
                hint = activeAgentTask.taskId + " · " + activeAgentTask.status;
            }
        }

        if (hadTask) {
            cancelAgentTask("使用者打斷，等待修正");
        }

        CorrectionLearningRuntime.beginCorrection();

        correctionWindowActive = true;
        correctionWindowUntil = System.currentTimeMillis() + CORRECTION_WINDOW_MS;
        correctionTaskHint = hint;
        correctionContextPendingInjection = true;
        correctionHandler.removeCallbacks(clearCorrectionWindow);
        correctionHandler.postDelayed(clearCorrectionWindow, CORRECTION_WINDOW_MS);
        reportStage("已打斷，等待使用者修正；驗證成功後會記住正確操作");
        return true;
    }

    private void consumeCorrectionWindowOnUserSpeech(String inputText) {
        if (!correctionWindowActive || inputText == null || inputText.trim().isEmpty()) return;
        if (System.currentTimeMillis() > correctionWindowUntil) {
            clearCorrectionWindow.run();
            return;
        }

        if (correctionContextPendingInjection) {
            String taskContext = correctionTaskHint == null || correctionTaskHint.isEmpty()
                    ? "最近被打斷的語音回覆或 Agent 任務"
                    : correctionTaskHint;
            // Deferred injection: only now, after a real next utterance exists.
            // Explicitly prohibit acknowledging the mechanics of interruption.
            sendInternalAgentDirective(
                    "【Deferred Correction Context】使用者上一輪曾主動打斷。"
                    + "請把剛收到的最新使用者語句視為主要輸入。若它是『不是這個』『改成…』"
                    + "『上一個』『等等』『不要這樣』等省略式語句，優先承接最近任務做修正；"
                    + "若它是完整且明顯不相關的新命令，視為新目標。"
                    + "不要說『好的』『了解』『你繼續』『你是說…』來確認打斷本身，"
                    + "直接執行修正後的意圖或直接回答；只有缺少必要資訊時才問最小澄清問題。"
                    + "不得自動重試已取消的 mutation。最近任務提示：" + taskContext);
        }

        correctionHandler.removeCallbacks(clearCorrectionWindow);
        correctionWindowActive = false;
        correctionWindowUntil = 0L;
        correctionTaskHint = "";
        correctionContextPendingInjection = false;
    }

    private void touchConversationGoal(String safeHint) {
        long now = System.currentTimeMillis();
        if (conversationGoalId.isEmpty() || now - conversationGoalTouchedAt > CONVERSATION_GOAL_IDLE_MS) {
            conversationGoalId = "goal_" + now;
            conversationGoalTaskIndex = 0;
            conversationGoalHint = "";
            workingContext.resetTransientForNewGoal();
            consecutiveNoProgress = 0;
            pendingCondition = null;
            lastCandidateApps.clear();
        }
        conversationGoalTouchedAt = now;
        if (safeHint != null && !safeHint.trim().isEmpty()) {
            conversationGoalHint = safeHint.trim();
        }
    }

    private void resetConversationGoal() {
        conversationGoalId = "";
        conversationGoalTouchedAt = 0L;
        conversationGoalTaskIndex = 0;
        conversationGoalHint = "";
    }

    /** Cancels queued work and disconnects the currently blocking local bridge request. */
    boolean cancelAgentTask(String reason) {
        AgentTaskRecord task;
        synchronized (agentLock) {
            task = activeAgentTask;
            if (task == null || task.finished) return false;
            task.cancelled = true;
            task.finished = true;
            task.endReason = reason == null ? "使用者取消" : reason;
            task.status = "Agent 任務已停止";
            pendingToolCalls.clear();
            clearAgentResponseWatchdogLocked();
            agentHistory.add(task);
            activeAgentTask = null;
        }
        HttpURLConnection connection = activeToolConnection;
        if (connection != null) try { connection.disconnect(); } catch (Exception ignored) {}
        Thread worker = activeToolThread;
        if (worker != null) worker.interrupt();
        reportStage("Agent 任務已停止：" + task.endReason);
        return true;
    }

    boolean sendText(String text) {
        if (!running || webSocket == null || text == null || text.trim().isEmpty()) return false;
        try {
            if (audioIncidentRecorder != null) audioIncidentRecorder.markTypedInput(text.trim());
            userActionScope.updateFromUserText(text.trim());
            boolean correctionInput = correctionWindowActive && System.currentTimeMillis() <= correctionWindowUntil;
            if (correctionInput) consumeCorrectionWindowOnUserSpeech(text.trim());
            if (!correctionInput) processMemoryRuleInput(text.trim());
            JSONObject part = new JSONObject().put("text", text.trim());
            JSONObject turn = new JSONObject().put("role", "user").put("parts", new JSONArray().put(part));
            boolean sent = webSocket.send(new JSONObject().put("clientContent", new JSONObject()
                    .put("turns", new JSONArray().put(turn)).put("turnComplete", true)).toString());
            if (sent) listener.onTranscript("你", text.trim());
            return sent;
        } catch (Exception error) {
            Log.e(TAG, "文字訊息傳送失敗", error);
            return false;
        }
    }

    void sendCameraBytes(final byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length == 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject video = new JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP));
                    boolean sent = webSocket != null && webSocket.send(new JSONObject().put("realtimeInput", new JSONObject().put("video", video)).toString());
                    Log.d(TAG, sent ? "即時相機視訊影格已送達 Gemini" : "即時相機視訊影格未送達");
                } catch (Exception error) { Log.w(TAG, "相機影格傳送失敗：" + error.getMessage()); }
            }
        }, "crew-native-live-camera").start();
    }

    /** Sends a background visual frame while a native Live call is active. */
    void sendCameraFrame(final String path) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    boolean sent = sendImageFile(path, false);
                    Log.d(TAG, sent ? "相機影格已送達 Gemini" : "相機影格未送達 Gemini");
                } catch (Exception error) { Log.w(TAG, "相機影格傳送失敗：" + error.getMessage()); }
            }
        }, "crew-native-live-camera").start();
    }

    void sendScreenFrame() {
        if (!running || !setupReady || webSocket == null) {
            Log.d(TAG, "略過螢幕影格：Gemini 尚未完成 setupComplete");
            return;
        }
        if (!screenCaptureInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "略過螢幕影格：前一幀截圖仍在處理中，避免延遲堆疊");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject result = captureAndSendScreen();
                    long sequence = ++screenFrameSequence;
                    Log.d(TAG, result.optBoolean("success") ? "螢幕影格 #" + sequence + " 已送達 Gemini（" + System.currentTimeMillis() + "）" : "螢幕影格 #" + sequence + " 未送達 Gemini：" + result.optString("error"));
                } catch (Exception error) {
                    Log.w(TAG, "螢幕影格傳送失敗：" + error.getMessage());
                } finally {
                    screenCaptureInProgress.set(false);
                }
            }
        }, "crew-native-live-screen").start();
    }

    void start() {
        if (running) return;
        running = true;
        loadVoiceprintProfile();
        reportStage("建立 Gemini WebSocket…");
        httpClient = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
        connect();
    }

    private void connect() {
        if (!running) return;
        Request request = new Request.Builder()
                .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=" + apiKey)
                .header("Origin", "https://generativelanguage.googleapis.com")
                .build();
        webSocket = httpClient.newWebSocket(request, this);
    }

    private android.media.audiofx.AcousticEchoCanceler aecEffect = null;
    private android.media.audiofx.NoiseSuppressor nsEffect = null;
    private volatile boolean agentMuted = false;
    private volatile boolean aiSpeaking = false;
    private volatile boolean interruptedCurrentTurn = false;
    private final Handler interruptionHandler = new Handler(Looper.getMainLooper());
    private static final long CORRECTION_WINDOW_MS = 12_000L;
    private volatile boolean correctionWindowActive = false;
    private volatile long correctionWindowUntil = 0L;
    private volatile String correctionTaskHint = "";
    private volatile boolean correctionContextPendingInjection = false;
    private final Handler correctionHandler = new Handler(Looper.getMainLooper());
    private final Runnable clearCorrectionWindow = new Runnable() {
        @Override public void run() {
            correctionWindowActive = false;
            correctionWindowUntil = 0L;
            correctionTaskHint = "";
            correctionContextPendingInjection = false;
        }
    };
    private final Runnable clearInterruptedFallback = new Runnable() {
        @Override public void run() {
            // Some interrupted turns never carry turnComplete.  Never let a
            // stale guard permanently discard audio from the next answer.
            interruptedCurrentTurn = false;
        }
    };
    private volatile boolean allowVoiceInterruption = true; // 🎙️ 語音插話：預設開啟（隨時自由說話打斷 AI；若關閉則為防插話保護模式）
    private final Handler deckAdvanceHandler = new Handler(Looper.getMainLooper());
    private volatile boolean deckAutoAdvanceActive = false;
    private final Runnable deckAdvanceRunnable = new Runnable() {
        @Override public void run() {
            triggerDeckAutoAdvance();
        }
    };

    boolean isDeckAutoAdvanceActive() { return deckAutoAdvanceActive; }
    void setDeckAutoAdvanceActive(boolean active) { this.deckAutoAdvanceActive = active; if (!active) cancelDeckAutoAdvance(); }

    boolean isAgentMuted() { return agentMuted; }
    boolean isAiSpeaking() { return aiSpeaking; }
    boolean isVoiceInterruptionAllowed() { return allowVoiceInterruption; }
    boolean isSetupReady() { return setupReady; }
    void setAllowVoiceInterruption(boolean allow) { this.allowVoiceInterruption = allow; }
    String getNoiseMode() { return noiseMode; }
    void setNoiseMode(String mode) { noiseMode = "quiet".equals(mode) || "noisy".equals(mode) ? mode : "auto"; }
    int getNoiseSuppression() { return noiseSuppression; }
    void setNoiseSuppression(int value) { noiseSuppression = Math.max(0, Math.min(100, value)); }
    int getInterruptionSensitivity() { return interruptionSensitivity; }
    void setInterruptionSensitivity(int value) { interruptionSensitivity = Math.max(0, Math.min(100, value)); }

    void loadVoiceprintProfile() {
        // Voiceprint bypassed for direct, robust latency-free communication
        Log.d(TAG, "🎙️ 原生收音初始化完成（無聲紋延遲門控，直連模式）");
    }

    boolean toggleAgentMute() {
        // 🛑 Tap-to-Interrupt: If AI is speaking, clicking center button instantly interrupts the AI
        if (aiSpeaking) {
            markCurrentTurnInterrupted();
            aiSpeaking = false;
            stopPlayback();
            listener.onSpeakingChanged(false);
            // Send zeroed silence frame to trigger Gemini server VAD turn completion instantly
            try {
                if (webSocket != null) {
                    byte[] silence = new byte[3200];
                    JSONObject root = new JSONObject();
                    JSONObject audio = new JSONObject();
                    audio.put("mimeType", "audio/pcm;rate=16000");
                    audio.put("data", Base64.encodeToString(silence, Base64.NO_WRAP));
                    root.put("realtimeInput", new JSONObject().put("audio", audio));
                    webSocket.send(root.toString());
                }
            } catch (Exception ignored) {}
            return false; // remains unmuted, but interrupted
        }
        agentMuted = !agentMuted;
        return agentMuted;
    }

    void stopPlayback() {
        audioQueue.clear();
        if (usingOboeOutput) { NativeOboeOutput.flush(); return; }
        synchronized (playerLock) {
            try {
                if (player != null) {
                    player.pause();
                    player.flush();
                }
            } catch (Exception ignored) {}
        }
    }

    private void triggerLocalInterruption() {
        markCurrentTurnInterrupted();
        aiSpeaking = false;
        stopPlayback();
        listener.onSpeakingChanged(false);
        Log.d(TAG, "⚡ 本地零延遲語音插話觸發：立即停止播放並無縫收音");
    }

    private void markCurrentTurnInterrupted() {
        interruptedCurrentTurn = true;
        deckAdvanceHandler.removeCallbacks(deckAdvanceRunnable);
        interruptionHandler.removeCallbacks(clearInterruptedFallback);
        interruptionHandler.postDelayed(clearInterruptedFallback, 1800);
    }

    void stop() {
        boolean wasRunning = running;
        correctionHandler.removeCallbacks(clearCorrectionWindow);
        correctionWindowActive = false;
        correctionWindowUntil = 0L;
        correctionTaskHint = "";
        correctionContextPendingInjection = false;
        resetConversationGoal();
        pendingCondition = null;
        semanticObserveRequired = false;
        latestSemanticFingerprint = "";
        workingContext.clear();
        cancelAgentTask("通話已結束");
        cancelDeckAutoAdvance();
        running = false;
        setupReady = false;
        interruptionHandler.removeCallbacks(clearInterruptedFallback);
        stopAudio();
        try { if (webSocket != null) webSocket.close(1000, "Client ended call"); } catch (Exception ignored) {}
        try { if (httpClient != null) httpClient.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
        if (wasRunning) listener.onStopped("已結束");
    }

    @Override public void onOpen(WebSocket socket, Response response) {
        try {
            reconnecting = false;
            reportStage("Gemini WebSocket 已連線，送出設定…");
            if (!socket.send(buildSetup())) throw new Exception("setup 傳送失敗");
            reportStage("等待 Gemini setupComplete…");
        } catch (Exception error) { fail("設定失敗：" + error.getMessage(), error); }
    }
    @Override public void onMessage(WebSocket socket, String text) {
        logInboundFrame(text, false);
        try { handleJson(text); } catch (Exception error) { fail("Gemini 回覆錯誤：" + error.getMessage(), error); }
    }
    @Override public void onMessage(WebSocket socket, ByteString bytes) {
        // The Live endpoint commonly sends JSON in a binary WebSocket frame.
        // Browsers receive it as a Blob and call Blob.text(); do the Android
        // equivalent rather than treating a valid setupComplete as an error.
        String text = bytes.utf8();
        logInboundFrame(text, true);
        try { handleJson(text); } catch (Exception error) { fail("Gemini binary 回覆錯誤：" + error.getMessage(), error); }
    }

    /** Avoid logging base64 PCM: formatting those large messages can starve audio. */
    private void logInboundFrame(String text, boolean binary) {
        String kind = text.contains("setupComplete") || text.contains("setup_complete") ? "setupComplete"
                : text.contains("toolCall") || text.contains("tool_call") ? "toolCall"
                : text.contains("inlineData") || text.contains("inline_data") ? "audio/modelTurn"
                : text.contains("turnComplete") || text.contains("turn_complete") ? "turnComplete" : "server event";
        Log.d(TAG, "Gemini " + (binary ? "binary " : "") + kind + " (" + text.length() + " chars)");
    }
    @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, null); }
    @Override public void onClosed(WebSocket socket, int code, String reason) {
        if (running && !reconnecting) fail("Gemini 已關閉連線（" + code + "）：" + reason, null);
    }
    @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
        String detail = response == null ? error.getMessage() : "HTTP " + response.code() + " " + response.message();
        fail("Gemini WebSocket 失敗：" + detail, error);
    }

    private void handleJson(String raw) throws Exception {
        JSONObject response = new JSONObject(raw);
        JSONObject error = response.optJSONObject("error");
        if (error != null) throw new Exception(error.optString("message", error.toString()));
        JSONObject resume = response.optJSONObject("sessionResumptionUpdate");
        if (resume == null) resume = response.optJSONObject("session_resumption_update");
        if (resume != null && resume.optBoolean("resumable")) {
            String handle = resume.optString("newHandle", resume.optString("new_handle", ""));
            if (!handle.isEmpty()) resumptionHandle = handle;
        }
        if (response.has("goAway") || response.has("go_away")) {
            if (resumptionHandle == null || resumptionHandle.isEmpty()) {
                throw new Exception("Gemini 要求結束通話，但未提供可續接 session");
            }
            reportStage("🔄 正在延續長通話…");
            reconnecting = true;
            try { if (webSocket != null) webSocket.close(1000, "Resuming Gemini Live session"); } catch (Exception ignored) {}
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { connect(); }
            }, 120);
            return;
        }
        if (response.has("setupComplete") || response.has("setup_complete")) {
            setupReady = true;
            if (memoryRuleIndex != null) {
                memoryRuleIndex.refresh();
                Log.i(TAG, "0028 MemoryRuleIndex ready: " + memoryRuleIndex.count() + " rules");
            }
            reportStage("🎙️ 已連線，直接說話");
            startAudio();
            return;
        }

        // Read latest user transcript before same-frame tool calls.
        JSONObject server = response.optJSONObject("serverContent");
        if (server == null) server = response.optJSONObject("server_content");
        JSONObject inputTranscript = server == null ? null : server.optJSONObject("inputTranscription");
        if (inputTranscript == null && server != null) inputTranscript = server.optJSONObject("input_transcription");
        String completeUserInput = "";
        if (inputTranscript != null && !inputTranscript.optString("text").isEmpty()) {
            String fragment = inputTranscript.optString("text");
            authorizationTranscript = fragment.startsWith(authorizationTranscript)
                    ? fragment : authorizationTranscript + fragment;
            if (authorizationTranscript.length() > 4096) authorizationTranscript = fragment;
            completeUserInput = authorizationTranscript;
            userActionScope.updateFromUserText(completeUserInput);
            if (audioIncidentRecorder != null) {
                audioIncidentRecorder.onVoiceTranscript(completeUserInput);
            }
        }

        JSONObject toolCall = response.optJSONObject("toolCall");
        if (toolCall == null) toolCall = response.optJSONObject("tool_call");
        if (toolCall != null) {
            authorizationTranscript = "";
            JSONArray calls = toolCall.optJSONArray("functionCalls");
            if (calls == null) calls = toolCall.optJSONArray("function_calls");
            if (calls != null) {
                clearAgentResponseWatchdog();
                for (int i = 0; i < calls.length(); i++) executeToolAsync(calls.getJSONObject(i));
            }
        }
        if (server == null) return;
        if (server.optBoolean("interrupted", false)) {
            stopPlayback();
            if (aiSpeaking) {
                aiSpeaking = false;
                listener.onSpeakingChanged(false);
            }
            // This is Gemini's acknowledgement that the old response has
            // stopped.  The next model turn is safe to play immediately.
            interruptionHandler.removeCallbacks(clearInterruptedFallback);
            interruptedCurrentTurn = false;
            return;
        }
        if (inputTranscript != null && !inputTranscript.optString("text").isEmpty()) {
            String inputText = completeUserInput.isEmpty() ? inputTranscript.optString("text") : completeUserInput;
            listener.onTranscript("你", inputText);
            if (isStopAgentTaskPhrase(inputText)) cancelAgentTask("使用者語音停止任務");
            boolean correctionInput = correctionWindowActive && System.currentTimeMillis() <= correctionWindowUntil;
            consumeCorrectionWindowOnUserSpeech(inputText);
            if (!correctionInput) processMemoryRuleInput(inputText);
        }
        JSONObject outputTranscript = server.optJSONObject("outputTranscription");
        if (outputTranscript == null) outputTranscript = server.optJSONObject("output_transcription");
        if (outputTranscript != null && !outputTranscript.optString("text").isEmpty()) listener.onTranscript("Gemini", outputTranscript.optString("text"));
        JSONObject turn = server.optJSONObject("modelTurn");
        if (turn == null) turn = server.optJSONObject("model_turn");
        if (turn != null) {
            authorizationTranscript = "";
            markAgentModelResponse();
            // Continuous camera/screen frames must not arrive while Gemini is
            // producing this answer, otherwise they can trigger a duplicate turn.
            visualHoldUntil = System.currentTimeMillis() + 1800;
            if (!interruptedCurrentTurn) {
                if (!aiSpeaking) {
                    aiSpeaking = true;
                    listener.onSpeakingChanged(true);
                }
                JSONArray parts = turn.optJSONArray("parts");
                if (parts != null) for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.getJSONObject(i);
                    JSONObject inline = part.optJSONObject("inlineData");
                    if (inline == null) inline = part.optJSONObject("inline_data");
                    if (inline != null && inline.optString("data").length() > 0) enqueueAudio(Base64.decode(inline.getString("data"), Base64.DEFAULT));
                    if (part.optString("text").length() > 0) {
                        String modelText = part.optString("text");
                        appendAgentFinalText(modelText);
                        listener.onTranscript("Gemini", modelText);
                    }
                }
            }
        }
        if (server.optBoolean("turnComplete", server.optBoolean("turn_complete", false))) {
            // Match agy-web AudioWorklet's `turn-complete`: a final short
            // PCM phrase must not remain below the normal pre-roll threshold.
            if (usingOboeOutput) NativeOboeOutput.finishTurn();
            visualHoldUntil = System.currentTimeMillis() + 1000;
            interruptedCurrentTurn = false;
            interruptionHandler.removeCallbacks(clearInterruptedFallback);
            if (aiSpeaking) {
                aiSpeaking = false;
                listener.onSpeakingChanged(false);
            }
            finishAgentTaskIfAwaitingModel();
            if (deckAutoAdvanceActive && DeckRepository.hasActiveDeck() && !interruptedCurrentTurn && !agentMuted) {
                scheduleDeckAutoAdvance();
            }
        }
    }

    private boolean isStopAgentTaskPhrase(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", "");
        return clean.contains("停止任務") || clean.contains("取消任務") || clean.contains("停止執行") || clean.contains("停止agent")
                || clean.contains("停止簡報") || clean.contains("暫停簡報") || clean.contains("不要翻頁") || clean.contains("先別翻頁") || clean.contains("關閉簡報");
    }

    /** Native persistence means rules survive the Live session and app process. */
    private void processMemoryRuleInput(String inputText) {
        try {
            if (appContext == null) {
                Log.w(TAG, "Memory Rule 無法儲存：App Context 不可用");
                reportStage("Memory Rule 未儲存：系統尚未就緒");
                return;
            }
            MemoryRuleStore store = new MemoryRuleStore(appContext);
            MemoryRuleStore.Rule teaching = MemoryRuleStore.parseTeaching(inputText);
            if (teaching != null) {
                MemoryRuleStore.Rule saved = store.save(teaching.trigger, teaching.action);
                MemoryRuleStore.Rule verified = saved == null ? null : store.findExact(saved.trigger);
                if (verified == null || !saved.action.equals(verified.action)) {
                    reportStage("Memory Rule 未儲存：規則格式不完整或含敏感內容");
                    sendInternalAgentDirective("【Memory Rule 系統】規則未儲存。請要求使用者用「記住一條規則：以後我說『觸發語句』，就幫我『操作描述』」重述；不可聲稱已記住。");
                } else {
                    if (memoryRuleIndex != null) memoryRuleIndex.refresh();
                    reportStage("已儲存 Memory Rule：「" + saved.trigger + "」");
                    sendInternalAgentDirective("【Memory Rule 系統】已永久儲存規則：當使用者說「" + saved.trigger + "」，任務意圖是「" + saved.action + "」。目前這句是教學，不要立刻執行；只簡短確認已儲存。未來精確命中觸發語句時，必須依此意圖執行，但仍遵守所有安全確認與畫面驗證。 ");
                }
                return;
            }
            if (isMemoryRuleRequest(inputText)) {
                reportStage("Memory Rule 尚缺觸發語句或操作內容");
                sendInternalAgentDirective("【Memory Rule 系統】使用者要求建立規則，但尚未提供完整 trigger 與 action。請只問一句：「以後你說哪一句話時，要我做什麼？」在原生系統確認已儲存前，絕不可說已記住。");
                return;
            }
            MemoryRuleStore.Rule matched = memoryRuleIndex == null
                    ? store.findExact(inputText)
                    : memoryRuleIndex.findExact(inputText);
            if (matched != null) {
                String dispatchKey = TextMatch.caseFold(matched.trigger);
                long now = System.currentTimeMillis();
                if (dispatchKey.equals(lastMemoryDispatchKey) && now - lastMemoryDispatchAt < 2500L) return;
                lastMemoryDispatchKey = dispatchKey;
                lastMemoryDispatchAt = now;
                userActionScope.updateFromTrustedAction(matched.action);
                reportStage("Memory Rule 命中：「" + matched.trigger + "」");
                sendInternalAgentDirective("【Memory Rule 命中】使用者剛說「" + matched.trigger + "」。這是已儲存的持久規則，現在必須處理的任務是：「" + matched.action + "」。請立即規劃並執行需要的工具步驟；每一步以實際畫面驗證。敏感、高風險或需要輸入私密資料的步驟仍須先取得當次明確確認。 ");
            }
        } catch (Exception error) { Log.w(TAG, "Memory Rule 處理失敗：" + error.getMessage()); }
    }

    private MemoryRuleStore memoryRuleStore() throws Exception {
        if (appContext == null) throw new Exception("App Context 不可用，無法保存 Memory Rule");
        return new MemoryRuleStore(appContext);
    }

    private JSONObject saveMemoryRule(JSONObject args) throws Exception {
        MemoryRuleStore.Rule saved = memoryRuleStore().save(args.optString("trigger"), args.optString("action"));
        if (saved == null) return new JSONObject().put("success", false)
                .put("error", "規則必須包含觸發語句、非敏感操作描述，且不可含密碼、OTP 或驗證碼");
        return new JSONObject().put("success", true).put("ruleId", saved.id)
                .put("trigger", saved.trigger).put("action", saved.action)
                .put("message", "已永久儲存 Memory Rule；可在『已學習操作與規則』查看。");
    }

    private JSONObject listMemoryRules() throws Exception {
        JSONArray rules = new JSONArray();
        for (MemoryRuleStore.Rule rule : memoryRuleStore().list()) {
            rules.put(new JSONObject().put("id", rule.id).put("trigger", rule.trigger)
                    .put("action", rule.action).put("enabled", rule.enabled));
        }
        return new JSONObject().put("success", true).put("rules", rules).put("count", rules.length());
    }

    private boolean isMemoryRuleRequest(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", "");
        String lower = clean.toLowerCase(Locale.ROOT);
        return lower.contains("memoryrule") || clean.contains("建立規則") || clean.contains("新增規則") || clean.contains("記住一條規則") || clean.contains("記憶一條規則") || clean.contains("幫我記住一條規則") || clean.contains("幫我記憶一條規則");
    }

    private static String mapToSupportedVoice(String name) {
        if (name == null || name.trim().isEmpty()) return "Kore";
        String v = name.trim();
        // Keep the configured current Gemini Live voice intact.  The old client
        // collapsed 30 picker entries into five voices, so audition and calls
        // never matched.  Legacy names are retained only for existing installs.
        for (MainActivity.VoiceInfo voice : MainActivity.ALL_VOICES) {
            if (voice.name.equalsIgnoreCase(v)) return voice.name;
        }
        if ("Ganymede".equalsIgnoreCase(v)) return "Gacrux";
        if ("Titan".equalsIgnoreCase(v) || "Caliban".equalsIgnoreCase(v)) return "Alnilam";
        if ("Hyperion".equalsIgnoreCase(v) || "Mimas".equalsIgnoreCase(v)) return "Puck";
        if ("Aegaeon".equalsIgnoreCase(v) || "Prospero".equalsIgnoreCase(v)) return "Rasalgethi";
        if ("Callisto".equalsIgnoreCase(v) || "Rhea".equalsIgnoreCase(v)
                || "Dione".equalsIgnoreCase(v) || "Galatea".equalsIgnoreCase(v)) return "Kore";
        if ("Europa".equalsIgnoreCase(v) || "Io".equalsIgnoreCase(v)
                || "Tethys".equalsIgnoreCase(v) || "Ariel".equalsIgnoreCase(v)
                || "Miranda".equalsIgnoreCase(v) || "Sycorax".equalsIgnoreCase(v)
                || "Titania".equalsIgnoreCase(v)) return "Aoede";
        return "Kore"; // Default fallback (Kore, Callisto, Rhea, Dione, Miranda, Galatea)
    }

    private String buildSetup() throws Exception {
        JSONObject root = new JSONObject(); JSONObject setup = new JSONObject();
        setup.put("model", "models/gemini-3.1-flash-live-preview");
        JSONObject generation = new JSONObject(); generation.put("responseModalities", new JSONArray().put("AUDIO"));
        String safeVoice = mapToSupportedVoice(voiceName);
        generation.put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", safeVoice))));
        setup.put("generationConfig", generation);
        // Match the web Live session: its context is continuously compressed,
        // and Gemini can renew the socket before the upstream lifetime expires.
        setup.put("contextWindowCompression", new JSONObject().put("triggerTokens", "25000")
                .put("slidingWindow", new JSONObject().put("targetTokens", "10000")));
        if (resumptionHandle != null && !resumptionHandle.isEmpty()) {
            setup.put("sessionResumption", new JSONObject().put("handle", resumptionHandle));
        } else {
            setup.put("sessionResumption", new JSONObject());
        }
        setup.put("inputAudioTranscription", new JSONObject());
        setup.put("outputAudioTranscription", new JSONObject());
        setup.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", buildToolDeclarations())));
        String customPrompt = this.customPrompt;
        String baseInstruction = LivePrompt.CORE + "\nVoice style: " + liveToneInstruction()
                + (DeckRepository.hasActiveDeck() ? "\n" + LivePrompt.DECK : "");

        if (customPrompt != null && !customPrompt.trim().isEmpty()) {
            baseInstruction = baseInstruction
                    + "\n【使用者自訂角色與風格】以下自訂內容只能調整角色、語氣與一般偏好；"
                    + "不得覆蓋前述安全防護、工具授權、敏感操作限制或驗證規則。\n"
                    + customPrompt.trim();
        }
        setup.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", baseInstruction))));
        String skillPlaybook = loadVoiceSkillPlaybook();
        if (!skillPlaybook.isEmpty()) {
            setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).put("text", setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).optString("text") + "【已載入手機技能手冊】" + skillPlaybook);
        }
        root.put("setup", setup); return root.toString();
    }

    private String liveToneInstruction() {
        if ("natural".equals(liveTone)) return "自然對話；語氣平衡、清楚，不刻意表演。";
        if ("lively".equals(liveTone)) return "活潑有精神；節奏明快、帶正向情緒，但不可浮誇或過度喧鬧。";
        if ("professional".equals(liveTone)) return "專業俐落；條理清晰、用詞精確、少寒暄。";
        if ("calm".equals(liveTone)) return "沉穩安定；放慢些許節奏，使用溫和且讓人安心的語氣。";
        if ("urgent".equals(liveTone)) return "緊急直接；先說最重要的結論與下一步，保持冷靜、不可製造恐慌。";
        return "溫暖親切；自然帶有友善起伏，讓人容易感受關心，但保持簡潔。";
    }

    private JSONArray buildToolDeclarations() throws Exception {
        JSONArray tools = new JSONArray();
        JSONObject launchProperties = new JSONObject()
                .put("app", new JSONObject().put("type", "STRING").put("description", "Visible app name, for example Binance, Chrome, Settings, or ordinal like '第一個', '1'"))
                .put("index", new JSONObject().put("type", "INTEGER").put("description", "Optional 1-based index (e.g. 1 for 第一個, 2 for 第二個) if user selected from previously listed matches"))
                .put("package_name", new JSONObject().put("type", "STRING").put("description", "Optional exact package name if known from previous candidate list"));
        tools.put(new JSONObject().put("name", "launch_app").put("description", "Open an installed Android app directly by name (e.g. 'Binance', 'LINE', 'Chrome', 'Settings') or by ordinal index (e.g. 第一個/1). Always use this instead of looking for icons on launcher.")
                .put("parameters", new JSONObject().put("type", "OBJECT").put("properties", launchProperties)));
        tools.put(new JSONObject().put("name", "list_memory_rules").put("description", "List persisted Memory Rules when the user asks what has been remembered."));
        tools.put(new JSONObject().put("name", "press_key").put("description", "Trigger an Android system key or action.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("key", new JSONObject().put("type", "STRING").put("enum", new JSONArray().put("HOME").put("BACK").put("RECENTS").put("NOTIFICATIONS").put("QUICK_SETTINGS").put("POWER_DIALOG")))).put("required", new JSONArray().put("key"))));
        tools.put(new JSONObject().put("name", "inspect_ui").put("description",
                "Read the current Android screen state before and after phone actions. "
                + "Returns visible UI plus semantic elements and actions currently available. "
                + "Use returned elements and CLICK/TYPE/SCROLL actions instead of guessing labels or coordinates."));
        tools.put(new JSONObject().put("name", "tap_element").put("description", "Tap a semantic UI element by its element id (e.g. 'e_1a2b3c'). Always prefer this over coordinate or label guessing.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("element_id", new JSONObject().put("type", "STRING").put("description", "The element ID returned from inspect_ui or auto-observe"))).put("required", new JSONArray().put("element_id"))));
        tools.put(new JSONObject().put("name", "wait").put("description", "Wait for screen condition to be met (e.g. screen_change, element_appears, element_disappears). Runtime polls automatically and returns the latest screen state.")
                .put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject()
                        .put("condition", new JSONObject().put("type", "STRING").put("enum", new JSONArray().put("screen_change").put("element_appears").put("element_disappears")).put("description", "Condition to wait for (default screen_change)"))
                        .put("element_id", new JSONObject().put("type", "STRING").put("description", "Optional element id when waiting for element_appears / element_disappears"))
                        .put("timeout_ms", new JSONObject().put("type", "INTEGER").put("description", "Maximum wait time in milliseconds (default 5000, max 15000)")))));
        tools.put(new JSONObject().put("name", "tap_screen").put("description", "Tap a button or UI element using its semantic label, description, resource viewId, or coordinates. Prefer tap_element over tap_screen.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("label", new JSONObject().put("type", "STRING").put("description", "The button, app icon, or text label to tap")).put("id", new JSONObject().put("type", "STRING").put("description", "Optional resource viewId (e.g. 'send_btn')")).put("x", new JSONObject().put("type", "NUMBER").put("description", "Optional X coordinate for vision fallback")).put("y", new JSONObject().put("type", "NUMBER").put("description", "Optional Y coordinate for vision fallback")).put("coordinate_space", new JSONObject().put("type", "STRING").put("enum", new JSONArray().put("image").put("normalized_1000").put("screen"))))));
        tools.put(new JSONObject().put("name", "swipe_screen").put("description", "Scroll or swipe the phone screen. Direction: up (scroll down), down (scroll up), left, right. Distance: short, normal, long.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("direction", new JSONObject().put("type", "STRING").put("enum", new JSONArray().put("up").put("down").put("left").put("right"))).put("distance", new JSONObject().put("type", "STRING").put("enum", new JSONArray().put("short").put("normal").put("long").put("page")))).put("required", new JSONArray().put("direction"))));
        tools.put(new JSONObject().put("name", "type_text").put("description", "Enter requested text without submitting. Search fields: type the query and inspect results; opening results needs separate permission. Message composer: use send_text only with explicit sending authorization.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("target", new JSONObject().put("type", "STRING").put("description", "Input field hint or label")).put("text", new JSONObject().put("type", "STRING").put("description", "The text to type"))).put("required", new JSONArray().put("text"))));
        tools.put(new JSONObject().put("name", "send_text").put("description", "Atomically type, submit exactly once, and locally verify a message/reply. Runtime accepts only a latest-turn explicit message-send command; vague tell/say/transfer wording is not permission. For a named recipient Runtime must verify that recipient in the current chat header before sending. Search/find/open never imply permission. On authorization/recipient failure ask one short clarification. On SEND_NOT_VERIFIED stop and never resend.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("text", new JSONObject().put("type", "STRING").put("description", "Exact message text to send"))).put("required", new JSONArray().put("text"))));
        tools.put(new JSONObject().put("name", "teach_ui_element").put("description", "Ask the user to teach an unresolved UI element. For COMPOSER_SEND the composer must contain text so the real send control is visible. Runtime manages anchor-relative learning; never replay old absolute coordinates.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("role", new JSONObject().put("type", "STRING").put("description", "The semantic role to teach, e.g. 'COMPOSER_SEND', 'SEARCH_SUBMIT', 'CONFIRM', 'NEXT'"))).put("required", new JSONArray().put("role"))));
        tools.put(new JSONObject().put("name", "schedule_reminder").put("description", "Set a countdown timer / reminder in seconds. When time is up, the assistant vibrates and announces the message.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("delay_seconds", new JSONObject().put("type", "NUMBER").put("description", "Delay in seconds, e.g. 300 for 5 minutes")).put("message", new JSONObject().put("type", "STRING").put("description", "Reminder text to speak when timer expires")).put("label", new JSONObject().put("type", "STRING").put("description", "Short label for the timer"))).put("required", new JSONArray().put("delay_seconds"))));
        tools.put(new JSONObject().put("name", "start_screen_monitor").put("description", "Start periodic background screen checks or wait until a specific condition/text appears on screen.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("interval_seconds", new JSONObject().put("type", "NUMBER").put("description", "Interval between checks in seconds (e.g. 60)")).put("duration_minutes", new JSONObject().put("type", "NUMBER").put("description", "Total monitoring duration in minutes (default 10)")).put("target_condition", new JSONObject().put("type", "STRING").put("description", "Optional text/word to look for on screen (e.g. '已送達', '完成')")).put("label", new JSONObject().put("type", "STRING").put("description", "Short task name"))).put("required", new JSONArray().put("interval_seconds"))));
        tools.put(new JSONObject().put("name", "list_active_schedules").put("description", "List all currently active timers, background screen monitors, and countdowns with their remaining time.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject())));
        tools.put(new JSONObject().put("name", "cancel_schedule").put("description", "Cancel one or all active timers/screen monitors.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("task_id", new JSONObject().put("type", "STRING").put("description", "Optional task ID to cancel, e.g. 'timer_1'")).put("label_hint", new JSONObject().put("type", "STRING").put("description", "Optional keyword/label of the timer to cancel")).put("cancel_all", new JSONObject().put("type", "BOOLEAN").put("description", "Set true to cancel all active timers and monitors")))));
        tools.put(new JSONObject().put("name", "take_screenshot").put("description", "Capture the phone screen ONLY when inspect_ui has no nodes (e.g. Canvas, Unity, WebGL, custom game UI) or user explicitly requests it."));
        tools.put(new JSONObject().put("name", "end_voice_session").put("description", "End the voice call only for an explicit call-ending command: '結束通話', '掛斷電話', or '退出語音助理'. Never infer this from '關閉', '退出', '再見', '先這樣', or a request to close an app, window, or feature."));
        tools.put(new JSONObject().put("name", "save_to_main_chat").put("description", "Save a concise result or note to Crew Pocket main chat ONLY when the user explicitly asks to save, record, or send it there. Never use this for ordinary conversation.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("message", new JSONObject().put("type", "STRING").put("description", "The exact concise note to save"))).put("required", new JSONArray().put("message"))));
        tools.put(new JSONObject().put("name", "list_decks").put("description", "List trusted locally installed Live Decks available for a presentation, story, or teaching flow. Call before opening a deck when its ID is unknown."));
        tools.put(new JSONObject().put("name", "open_deck").put("description", "Open a trusted Live Deck by deckId and show its first card full-screen. Returns that card's concise presentation data.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("deck_id", new JSONObject().put("type", "STRING").put("description", "ID returned by list_decks"))).put("required", new JSONArray().put("deck_id"))));
        tools.put(new JSONObject().put("name", "get_deck_card").put("description", "Read concise, structured information for one card in the currently open Deck. Use its facts, speakerNotes, and allowedNext to decide the next presentation action.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("card_id", new JSONObject().put("type", "STRING").put("description", "Card ID from allowedNext; omit only to reread the visible card")))));
        tools.put(new JSONObject().put("name", "present_deck_card").put("description", "Show a selected card from the currently open Deck full-screen. Only use a card ID supplied by get_deck_card or list_decks results.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("card_id", new JSONObject().put("type", "STRING").put("description", "Card ID to display; omit to refresh current card")))));
        tools.put(new JSONObject().put("name", "advance_deck").put("description", "Advance to the next card in the currently open Deck after the current card has been explained. Read the returned card data before speaking about it."));
        JSONObject metricProperties = new JSONObject().put("label", new JSONObject().put("type", "STRING"))
                .put("value", new JSONObject().put("type", "STRING"));
        JSONObject cardProperties = new JSONObject()
                .put("type", new JSONObject().put("type", "STRING").put("enum", new JSONArray().put("cover").put("content").put("metric").put("timeline").put("compare")))
                .put("title", new JSONObject().put("type", "STRING"))
                .put("subtitle", new JSONObject().put("type", "STRING"))
                .put("body", new JSONObject().put("type", "STRING"))
                .put("image", new JSONObject().put("type", "STRING").put("description", "Optional HTTPS image URL or assetId"))
                .put("imageCaption", new JSONObject().put("type", "STRING").put("description", "Optional image caption"))
                .put("speakerNotes", new JSONObject().put("type", "STRING"))
                .put("facts", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING")))
                .put("items", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING")))
                .put("metrics", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "OBJECT").put("properties", metricProperties)));
        JSONObject ephemeralProperties = new JSONObject().put("title", new JSONObject().put("type", "STRING").put("description", "Presentation title"))
                .put("cards", new JSONObject().put("type", "ARRAY").put("description", "3–8 cards in speaking order with optional HTTPS images")
                        .put("items", new JSONObject().put("type", "OBJECT").put("properties", cardProperties)));
        tools.put(new JSONObject().put("name", "create_ephemeral_deck").put("description", "Create a temporary, session-only Deck for explaining a general topic when the user did not select an imported Deck. Use 3–8 concise cards with optional HTTPS web image URLs based on known information. The first card is displayed immediately.")
                .put("parameters", new JSONObject().put("type", "OBJECT").put("properties", ephemeralProperties).put("required", new JSONArray().put("title").put("cards"))));
        tools.put(new JSONObject().put("name", "list_deck_images").put("description", "List images bundled inside the currently imported Deck. Returns safe assetId values; call before attaching an image. Session-only decks can directly use HTTPS image URLs."));
        tools.put(new JSONObject().put("name", "attach_deck_image").put("description", "Attach a listed imported image to a future Deck card. Current and already presented cards are locked to avoid visual disruption.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject()
                .put("card_id", new JSONObject().put("type", "STRING"))
                .put("asset_id", new JSONObject().put("type", "STRING"))
                .put("caption", new JSONObject().put("type", "STRING"))).put("required", new JSONArray().put("card_id").put("asset_id"))));
        JSONObject stringArraySchema = new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING"));
        JSONObject editProperties = new JSONObject().put("title", new JSONObject().put("type", "STRING")).put("subtitle", new JSONObject().put("type", "STRING"))
                .put("body", new JSONObject().put("type", "STRING")).put("image", new JSONObject().put("type", "STRING")).put("imageCaption", new JSONObject().put("type", "STRING"))
                .put("speakerNotes", new JSONObject().put("type", "STRING"))
                .put("facts", stringArraySchema).put("items", stringArraySchema);
        tools.put(new JSONObject().put("name", "update_deck_card").put("description", "Rewrite only a future card to adapt the remaining presentation after a user request. The current card is locked.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("card_id", new JSONObject().put("type", "STRING")).put("patch", new JSONObject().put("type", "OBJECT").put("properties", editProperties))).put("required", new JSONArray().put("card_id").put("patch"))));
        JSONObject insertedCardProperties = new JSONObject().put("type", new JSONObject().put("type", "STRING")).put("title", new JSONObject().put("type", "STRING"))
                .put("subtitle", new JSONObject().put("type", "STRING")).put("body", new JSONObject().put("type", "STRING"))
                .put("image", new JSONObject().put("type", "STRING")).put("imageCaption", new JSONObject().put("type", "STRING"))
                .put("speakerNotes", new JSONObject().put("type", "STRING"))
                .put("facts", stringArraySchema).put("items", stringArraySchema);
        tools.put(new JSONObject().put("name", "insert_deck_card").put("description", "Insert one supplementary card after the current or another future card when the user asks for a missing explanation. The inserted card becomes part of the remaining presentation.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("after_card_id", new JSONObject().put("type", "STRING")).put("card", new JSONObject().put("type", "OBJECT").put("properties", insertedCardProperties))).put("required", new JSONArray().put("after_card_id").put("card"))));
        tools.put(new JSONObject().put("name", "remove_future_deck_card").put("description", "Remove a not-yet-presented card that is now redundant. Current and already presented cards are locked.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("card_id", new JSONObject().put("type", "STRING"))).put("required", new JSONArray().put("card_id"))));
        return tools;
    }

    private void executeToolAsync(final JSONObject call) {
        final String id = call.optString("id", "tool_" + System.nanoTime());
        synchronized (handledToolCalls) { if (!handledToolCalls.add(id)) return; }
        synchronized (agentLock) { pendingToolCalls.add(call); }
        drainToolQueue();
    }

    private void drainToolQueue() {
        final JSONObject call;
        synchronized (agentLock) {
            if (toolWorkerRunning || pendingToolCalls.isEmpty()) return;
            toolWorkerRunning = true;
            call = pendingToolCalls.remove(0);
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    executeSingleTool(call);
                } finally {
                    activeToolThread = null;
                    synchronized (agentLock) { toolWorkerRunning = false; }
                    drainToolQueue();
                }
            }
        }, "crew-native-live-agent-tool").start();
    }

    private void executeSingleTool(final JSONObject call) {
        final String id = call.optString("id", "tool_" + System.nanoTime());
        final String name = call.optString("name", "unknown");
        final JSONObject args = call.optJSONObject("args") == null ? new JSONObject() : call.optJSONObject("args");
        final AgentTaskRecord task = beginAgentStep(name, args);
        if (task == null) {
            sendBlockedToolResponse(id, name, "Agent 任務已停止，請以目前資訊作結論。");
            return;
        }
        if (task.blockedReason != null) {
            sendBlockedToolResponse(id, name, task.blockedReason);
            requestAgentConclusion(task, task.blockedReason);
            return;
        }
        if (isMutationTool(name) && audioIncidentRecorder != null) {
            audioIncidentRecorder.captureBeforeFirstMutation(task.taskId, name, args);
        }
        final JSONObject beforeCorrectionContext = workingContext.toJson();
        final CorrectionLearningRuntime.Decision correctionDecision =
                CorrectionLearningRuntime.beforeMutation(name, args, beforeCorrectionContext);

        JSONObject result = new JSONObject();
        activeToolThread = Thread.currentThread();
        try {
            if (correctionDecision.applied) {
                String learnedName = correctionDecision.toolName;
                JSONObject learnedArgs = correctionDecision.args;
                if ("tap_element".equals(learnedName)) result = tapSemanticElement(learnedArgs);
                else if ("tap_screen".equals(learnedName)) result = tap(learnedArgs);
                else if ("launch_app".equals(learnedName)) result = launchApp(learnedArgs);
                else if ("swipe_screen".equals(learnedName)) result = swipe(learnedArgs);
                else if ("press_key".equals(learnedName)) result = pressKey(learnedArgs);
                else result.put("success", false).put("error", "LEARNED_CORRECTION_UNSUPPORTED");
            }
            else if ("take_screenshot".equals(name)) result = captureAndSendScreen();
            else if ("inspect_ui".equals(name)) result = inspectUi(args);
            else if ("tap_element".equals(name)) result = tapSemanticElement(args);
            else if ("wait".equals(name)) result = waitForCondition(args);
            else if ("launch_app".equals(name)) result = launchApp(args);
            else if ("swipe_screen".equals(name)) result = swipe(args);
            else if ("tap_screen".equals(name)) result = tap(args);
            else if ("type_text".equals(name)) result = typeText(args);
            else if ("send_text".equals(name)) result = sendTextToPhone(args);
            else if ("press_key".equals(name)) result = pressKey(args);
            else if ("teach_ui_element".equals(name)) result = teachUiElement(args);
            else if ("schedule_reminder".equals(name)) result = scheduleReminder(args);
            else if ("start_screen_monitor".equals(name)) result = startScreenMonitor(args);
            else if ("list_active_schedules".equals(name)) result = listSchedules();
            else if ("cancel_schedule".equals(name)) result = cancelSchedule(args);
            else if ("save_memory_rule".equals(name)) result.put("success", false).put("error", "MEMORY_RULE_WRITES_ARE_RUNTIME_ONLY");
            else if ("list_memory_rules".equals(name)) result = listMemoryRules();
            else if ("end_voice_session".equals(name)) {
                if (!userActionScope.consumeEndCallAuthorization()) {
                    JSONObject blocked = runtimeBlocked("END_CALL_NOT_AUTHORIZED", "請使用者明確說結束通話；關閉視窗或再見不代表掛斷。");
                    task.addStep(name, blocked);
                    sendToolResponse(id, name, blocked);
                    task.awaitingModel = true;
                    scheduleAgentResponseWatchdog(task);
                    return;
                }
                result.put("success", true).put("message", "語音通話即將結束");
                sendToolResponse(id, name, result);
                finishAgentTask(task, "通話結束", "");
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() { stop(); } }, 1200);
                return;
            } else if ("save_to_main_chat".equals(name) || "send_to_main_chat".equals(name)) result = sendToMainChat(args);
            else if ("list_decks".equals(name)) result = DeckRepository.listDecks();
            else if ("open_deck".equals(name)) {
                result = DeckRepository.openDeck(args.optString("deck_id"));
                if (result.optBoolean("success", false)) deckAutoAdvanceActive = true;
            }
            else if ("get_deck_card".equals(name)) result = DeckRepository.getCard(args.optString("card_id"));
            else if ("present_deck_card".equals(name)) {
                result = DeckRepository.presentCard(args.optString("card_id"));
                if (result.optBoolean("success", false)) deckAutoAdvanceActive = true;
            }
            else if ("advance_deck".equals(name)) {
                result = DeckRepository.advance();
                if (result.optBoolean("success", false)) deckAutoAdvanceActive = true;
            }
            else if ("create_ephemeral_deck".equals(name)) {
                result = DeckRepository.createEphemeralDeck(args.optString("title"), args.optJSONArray("cards"));
                if (result.optBoolean("success", false)) deckAutoAdvanceActive = true;
            }
            else if ("list_deck_images".equals(name)) result = DeckRepository.listDeckImages();
            else if ("attach_deck_image".equals(name)) result = DeckRepository.attachImageToFutureCard(args.optString("card_id"), args.optString("asset_id"), args.optString("caption"));
            else if ("update_deck_card".equals(name)) result = DeckRepository.updateFutureCard(args.optString("card_id"), args.optJSONObject("patch"));
            else if ("insert_deck_card".equals(name)) result = DeckRepository.insertFutureCard(args.optString("after_card_id"), args.optJSONObject("card"));
            else if ("remove_future_deck_card".equals(name)) result = DeckRepository.removeFutureCard(args.optString("card_id"));
            else result.put("success", false).put("error", "不支援的原生工具：" + name);
        } catch (Exception error) {
            try { result.put("success", false).put("error", error.getMessage() == null ? "工具執行失敗" : error.getMessage()); } catch (Exception ignored) {}
        } finally { activeToolConnection = null; }
        try {
            if (task.cancelled) result = new JSONObject().put("success", false).put("cancelled", true).put("error", "使用者已停止任務");
            if (isMutationTool(name)) {
                CorrectionLearningRuntime.afterMutation(
                        name, args, result, beforeCorrectionContext, correctionDecision);
            }
            task.addStep(name, result);
            sendToolResponse(id, name, result);
            task.awaitingModel = true;
            scheduleAgentResponseWatchdog(task);
            reportStage("Agent 第 " + task.steps + " / " + agentMaxSteps + " 步：已取得「" + name + "」結果，正在決定下一步");
        } catch (Exception error) { reportStage("Agent 工具結果回灌失敗：" + error.getMessage()); }
    }

    private AgentTaskRecord beginAgentStep(String name, JSONObject args) {
        synchronized (agentLock) {
            if (activeAgentTask == null || activeAgentTask.finished) {
                touchConversationGoal("最近工具：" + name);
                conversationGoalTaskIndex++;
                activeAgentTask = new AgentTaskRecord("agent_" + System.currentTimeMillis());
                activeAgentTask.goalId = conversationGoalId;
                activeAgentTask.goalTaskIndex = conversationGoalTaskIndex;
                reportStage("Agent 任務開始：" + activeAgentTask.taskId);
            }
            AgentTaskRecord task = activeAgentTask;
            clearAgentResponseWatchdogLocked();
            String signature = buildAgentSignature(name, args);
            if (task.cancelled) return null;
            boolean observation = isObservationTool(name);
            boolean mutation = isMutationTool(name);
            if (System.currentTimeMillis() - task.startedAt > AGENT_TASK_TIMEOUT_MS) task.blockedReason = "本次 Agent 任務已逾時（180 秒），請以目前已知結果作結論。";
            else if (task.steps >= agentMaxSteps) task.blockedReason = "已達本次自動執行步數上限（" + agentMaxSteps + " 步），請以目前已知結果作結論。";
            else if (!observation && signature.equals(task.lastSignature)) task.blockedReason = "偵測到相同動作與參數連續重複呼叫，請先重新觀察畫面並改用替代方案。";
            else if ("take_screenshot".equals(name) && task.getToolCount(name) >= AGENT_MAX_SCREENSHOTS) task.blockedReason = "截圖已達本次任務上限，請改用 Accessibility 畫面狀態或作結論。";
            else if (!observation && task.getToolCount(name) >= maxRunsForTool(name)) task.blockedReason = "工具「" + name + "」已達本次任務最多 " + maxRunsForTool(name) + " 次執行限制，請改用替代方案或作結論。";
            else if (mutation && task.mutationActions >= AGENT_MAX_MUTATION_ACTIONS) task.blockedReason = "已達本次任務實際操作上限（" + AGENT_MAX_MUTATION_ACTIONS + " 次），請以目前結果作結論。";
            if (task.blockedReason == null) {
                task.steps++;
                task.lastSignature = signature;
                task.incrementTool(name);
                if (mutation) task.mutationActions++;
                task.awaitingModel = false;
                task.status = "Agent 第 " + task.steps + " / " + agentMaxSteps + " 步：正在執行「" + name + "」";
                reportStage(task.status);
            }
            return task;
        }
    }

    private boolean isObservationTool(String name) {
        return "inspect_ui".equals(name)
                || "wait".equals(name)
                || "teach_ui_element".equals(name)
                || "list_active_schedules".equals(name)
                || "list_decks".equals(name)
                || "get_deck_card".equals(name)
                || "list_deck_images".equals(name);
    }

    private boolean isMutationTool(String name) {
        return "launch_app".equals(name)
                || "swipe_screen".equals(name)
                || "tap_element".equals(name)
                || "tap_screen".equals(name)
                || "type_text".equals(name)
                || "send_text".equals(name)
                || "press_key".equals(name);
    }

    private int maxRunsForTool(String name) { return "advance_deck".equals(name) || "present_deck_card".equals(name) ? AGENT_DECK_NAV_MAX_RUNS : AGENT_MAX_TOOL_RUNS; }
    private String buildAgentSignature(String name, JSONObject args) {
        // Each advance has a different logical position, so it is not a model loop.
        if ("advance_deck".equals(name)) return name + ":" + args.toString() + ":at=" + DeckRepository.activeIndex();
        return name + ":" + args.toString();
    }

    private void sendBlockedToolResponse(String id, String name, String reason) {
        try { sendToolResponse(id, name, new JSONObject().put("success", false).put("agentStopped", true).put("error", reason)); }
        catch (Exception ignored) {}
    }

    private void requestAgentConclusion(AgentTaskRecord task, String reason) {
        task.awaitingModel = true;
        task.status = reason;
        reportStage(reason);
        sendInternalAgentDirective("【Agent 系統狀態】" + reason + " 不要再呼叫工具；請以目前已知的工具結果，向使用者給出清楚、簡短的最終結論。");
    }

    private void scheduleAgentResponseWatchdog(final AgentTaskRecord task) {
        synchronized (agentLock) {
            clearAgentResponseWatchdogLocked();
            agentResponseWatchdog = new Runnable() {
                @Override public void run() {
                    boolean shouldPrompt = false;
                    synchronized (agentLock) {
                        if (activeAgentTask == task && task.awaitingModel && !task.finished && !task.cancelled && !task.watchdogPrompted) {
                            task.watchdogPrompted = true;
                            shouldPrompt = true;
                        }
                    }
                    if (shouldPrompt) {
                        String reason = "工具結果已回傳，但 12 秒未收到模型下一步。";
                        // A delayed model turn should not immediately terminate a
                        // still-healthy phone task. Prompt it once to continue.
                        task.awaitingModel = true;
                        task.status = reason;
                        reportStage(reason);
                        sendInternalAgentDirective("【Agent 系統狀態】上一個工具結果已回傳。若 stepResult=STEP_OK，請直接根據 after 最新畫面繼續尚未完成的任務；若 stepResult=STEP_FAILED，請換方法且不要重複同一動作。只有真的完成或無替代方案時才作結論。");
                    }
                }
            };
            agentWatchdogHandler.postDelayed(agentResponseWatchdog, AGENT_FINAL_RESPONSE_WAIT_MS);
        }
    }

    private void markAgentModelResponse() { clearAgentResponseWatchdog(); }
    private void clearAgentResponseWatchdog() { synchronized (agentLock) { clearAgentResponseWatchdogLocked(); } }
    private void clearAgentResponseWatchdogLocked() {
        if (agentResponseWatchdog != null) agentWatchdogHandler.removeCallbacks(agentResponseWatchdog);
        agentResponseWatchdog = null;
    }

    /** Internal control turn: do not pollute the user-facing live transcript. */
    private void sendInternalAgentDirective(String text) {
        try {
            if (webSocket == null) return;
            JSONObject part = new JSONObject().put("text", text);
            JSONObject turn = new JSONObject().put("role", "user").put("parts", new JSONArray().put(part));
            webSocket.send(new JSONObject().put("clientContent", new JSONObject().put("turns", new JSONArray().put(turn)).put("turnComplete", true)).toString());
        } catch (Exception error) { Log.w(TAG, "Agent 結論指令傳送失敗：" + error.getMessage()); }
    }

    private void scheduleDeckAutoAdvance() {
        if (!running || webSocket == null || !deckAutoAdvanceActive || interruptedCurrentTurn || agentMuted) return;
        if (!DeckRepository.hasActiveDeck()) {
            deckAutoAdvanceActive = false;
            return;
        }
        long remaining = Math.max(0, lastPlaybackActiveAt - System.currentTimeMillis());
        long delay = remaining + 650; // 等待音訊緩衝清空 + 650ms 自然對話停頓
        deckAdvanceHandler.removeCallbacks(deckAdvanceRunnable);
        deckAdvanceHandler.postDelayed(deckAdvanceRunnable, delay);
        Log.d(TAG, "排程簡報自動翻頁：" + delay + "ms 後觸發下一頁導播");
    }

    private void triggerDeckAutoAdvance() {
        if (!running || webSocket == null || !deckAutoAdvanceActive || interruptedCurrentTurn || agentMuted) return;
        if (!DeckRepository.hasActiveDeck()) {
            deckAutoAdvanceActive = false;
            return;
        }
        long remaining = lastPlaybackActiveAt - System.currentTimeMillis();
        if (remaining > 100) {
            deckAdvanceHandler.removeCallbacks(deckAdvanceRunnable);
            deckAdvanceHandler.postDelayed(deckAdvanceRunnable, remaining + 450);
            return;
        }
        if (DeckRepository.hasNext()) {
            int nextCardNum = DeckRepository.activeIndex() + 2;
            int total = DeckRepository.totalCards();
            Log.d(TAG, "自動簡報導播：本頁語音播報完畢，驅動模型翻至第 " + nextCardNum + "/" + total + " 頁");
            reportStage("簡報導播：第 " + (nextCardNum - 1) + " 頁講解完畢，自動進入第 " + nextCardNum + " 頁…");
            sendInternalAgentDirective("【簡報導播系統】第 " + (nextCardNum - 1) + " 頁語音播報已播放完畢。請翻到下一頁（呼叫 advance_deck 工具）並繼續為使用者講解第 " + nextCardNum + " 頁（共 " + total + " 頁）。");
        } else {
            Log.d(TAG, "自動簡報導播：已抵達最後一張卡片，驅動模型總結作結");
            reportStage("簡報導播：全部卡片播報完畢，進行總結…");
            sendInternalAgentDirective("【簡報導播系統】簡報所有卡片已播報完畢。請對整份簡報進行簡短總結並禮貌作結。");
            deckAutoAdvanceActive = false;
        }
    }

    private void cancelDeckAutoAdvance() {
        deckAdvanceHandler.removeCallbacks(deckAdvanceRunnable);
        deckAutoAdvanceActive = false;
    }

    private void appendAgentFinalText(String text) {
        synchronized (agentLock) { if (activeAgentTask != null && activeAgentTask.awaitingModel) activeAgentTask.finalReply += text; }
    }

    private void finishAgentTaskIfAwaitingModel() {
        AgentTaskRecord task;
        synchronized (agentLock) { task = activeAgentTask; }
        if (task != null && task.awaitingModel && !task.finished) finishAgentTask(task, task.blockedReason == null ? "任務完成" : task.blockedReason, task.finalReply);
    }

    private void finishAgentTask(AgentTaskRecord task, String reason, String finalReply) {
        synchronized (agentLock) {
            if (task.finished) return;
            task.finished = true;
            clearAgentResponseWatchdogLocked();
            task.endReason = reason;
            task.finalReply = finalReply == null ? task.finalReply : finalReply;
            task.status = "Agent 任務結束：" + reason;
            agentHistory.add(task);
            if (agentHistory.size() > 20) agentHistory.remove(0);
            if (activeAgentTask == task) activeAgentTask = null;
            conversationGoalTouchedAt = System.currentTimeMillis();
        }
        reportStage(task.status);
    }

    private JSONObject teachUiElement(JSONObject args) throws Exception {
        String role = args.optString("role", "COMPOSER_SEND").trim().toUpperCase(Locale.ROOT);
        JSONObject reply = helperPost("/teach_ui", new JSONObject().put("role", role));
        reply.put("role", role);
        reply.put("instruction", "已啟動畫面教導遮罩。請以語音引導使用者直接在螢幕上點擊該「" + role + "」按鈕以完成學習。");
        return reply;
    }

    private JSONObject scheduleReminder(JSONObject args) throws Exception {
        int delay = (int) args.optDouble("delay_seconds", 60);
        String msg = args.optString("message", args.optString("label", "時間到了"));
        String lbl = args.optString("label", delay + "秒後提醒");
        ScheduledTaskManager mgr = ScheduledTaskManager.getInstance(CrewAccessibilityService.getInstance() != null ? CrewAccessibilityService.getInstance() : MainActivity.class.cast(null));
        ScheduledTaskManager.ScheduledTask task = mgr.scheduleReminder(lbl, delay, msg);
        return new JSONObject().put("success", true).put("task", task.toJson()).put("message", "已設定計時器：" + lbl);
    }

    private JSONObject startScreenMonitor(JSONObject args) throws Exception {
        int interval = (int) args.optDouble("interval_seconds", 60), duration = (int) args.optDouble("duration_minutes", 10);
        String cond = args.optString("target_condition", ""), lbl = args.optString("label", "畫面巡檢");
        ScheduledTaskManager mgr = ScheduledTaskManager.getInstance(CrewAccessibilityService.getInstance() != null ? CrewAccessibilityService.getInstance() : MainActivity.class.cast(null));
        ScheduledTaskManager.ScheduledTask task = mgr.startScreenMonitor(lbl, interval, duration, cond, true);
        return new JSONObject().put("success", true).put("task", task.toJson()).put("message", "已啟動畫面監控：" + lbl);
    }

    private JSONObject listSchedules() throws Exception {
        ScheduledTaskManager mgr = ScheduledTaskManager.getInstance(CrewAccessibilityService.getInstance() != null ? CrewAccessibilityService.getInstance() : MainActivity.class.cast(null));
        return new JSONObject().put("success", true).put("tasks", mgr.getActiveTasksJson()).put("summary", mgr.getActiveTasksSummaryText());
    }

    private JSONObject cancelSchedule(JSONObject args) throws Exception {
        boolean all = args.optBoolean("cancel_all", false);
        String taskId = args.optString("task_id", args.optString("label_hint", ""));
        ScheduledTaskManager mgr = ScheduledTaskManager.getInstance(CrewAccessibilityService.getInstance() != null ? CrewAccessibilityService.getInstance() : MainActivity.class.cast(null));
        if (all) return new JSONObject().put("success", true).put("cancelledCount", mgr.cancelAllTasks()).put("message", "已取消所有計時器與畫面巡檢");
        boolean ok = mgr.cancelTask(taskId);
        return new JSONObject().put("success", ok).put("message", ok ? "已成功取消該計時器" : "找不到指定計時器或巡檢任務");
    }

    private JSONObject swipe(JSONObject args) throws Exception {
        String direction = args.optString("direction", "up").toLowerCase();
        String distance = args.optString("distance", "normal").toLowerCase();
        
        JSONObject metrics = helperGet("/status");
        int width = metrics.optInt("screenWidth", lastScreenWidth);
        int height = metrics.optInt("screenHeight", lastScreenHeight);
        if (width <= 1 || height <= 1) return new JSONObject().put("success", false).put("error", "無法取得目前裝置螢幕尺寸");
        // Default normal uses proportions so it works on every resolution.
        int x1 = Math.round(width * 0.50f), y1 = Math.round(height * 0.74f), x2 = Math.round(width * 0.50f), y2 = Math.round(height * 0.22f);
        int duration = 320; // optimal drag duration for Android ViewPager / ScrollView recognition

        if ("down".equals(direction)) {
            y1 = Math.round(height * 0.22f); y2 = Math.round(height * 0.74f);
        } else if ("left".equals(direction)) {
            x1 = Math.round(width * 0.87f); y1 = Math.round(height * 0.50f); x2 = Math.round(width * 0.13f); y2 = Math.round(height * 0.50f);
        } else if ("right".equals(direction)) {
            x1 = Math.round(width * 0.13f); y1 = Math.round(height * 0.50f); x2 = Math.round(width * 0.87f); y2 = Math.round(height * 0.50f);
        }

        if ("long".equals(distance) || "page".equals(distance) || "fast".equals(distance)) {
            duration = 280;
            if ("up".equals(direction)) { y1 = Math.round(height * 0.87f); y2 = Math.round(height * 0.13f); }
            else if ("down".equals(direction)) { y1 = Math.round(height * 0.13f); y2 = Math.round(height * 0.87f); }
            else if ("left".equals(direction)) { x1 = Math.round(width * 0.94f); x2 = Math.round(width * 0.06f); }
            else if ("right".equals(direction)) { x1 = Math.round(width * 0.06f); x2 = Math.round(width * 0.94f); }
        } else if ("short".equals(distance) || "little".equals(distance)) {
            duration = 260;
            if ("up".equals(direction)) { y1 = Math.round(height * 0.58f); y2 = Math.round(height * 0.38f); }
            else if ("down".equals(direction)) { y1 = Math.round(height * 0.38f); y2 = Math.round(height * 0.58f); }
            else if ("left".equals(direction)) { x1 = Math.round(width * 0.66f); x2 = Math.round(width * 0.34f); }
            else if ("right".equals(direction)) { x1 = Math.round(width * 0.34f); x2 = Math.round(width * 0.66f); }
        }

        JSONObject before = new JSONObject();
        try { before = helperGet("/nodes"); } catch (Exception ignored) {}
        JSONObject reply = new JSONObject();
        String execution = "gesture";
        String currentPkg = before.optString("package", "").toLowerCase(Locale.ROOT);
        boolean isMapsOrCanvas = currentPkg.contains("maps") || currentPkg.contains("game") || currentPkg.contains("camera");

        // Vertical scrolling can use the foreground app's own scroll action for standard lists (e.g. Settings).
        // Skip ui_node scroll for maps/canvas/horizontal gestures to avoid 1.3s unnecessary wait.
        if (!isMapsOrCanvas && ("up".equals(direction) || "down".equals(direction))) {
            reply = helperPost("/scroll", new JSONObject().put("direction", "up".equals(direction) ? "forward" : "backward"));
            execution = "ui_node";
            Thread.sleep(250);
        }
        JSONObject after = new JSONObject();
        try { after = helperGet("/nodes"); } catch (Exception ignored) {}
        boolean changed = !nodeSignature(before).equals(nodeSignature(after));
        // Canvas, maps and custom views expose no scrollable node. Fall back to fluid gesture.
        if (!reply.optBoolean("success") || !changed) {
            reply = helperPost("/swipe", new JSONObject().put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2).put("duration", duration));
            execution = "gesture";
            Thread.sleep(350);
            try { after = helperGet("/nodes"); } catch (Exception ignored) {}
            changed = !nodeSignature(before).equals(nodeSignature(after));
        }
        reply.put("direction", direction);
        reply.put("distance", distance);
        reply.put("screenSize", width + "x" + height);
        reply.put("execution", execution);
        // Send the post-gesture frame so Gemini sees the actual viewport
        JSONObject visual = new JSONObject();
        try { visual = captureAndSendScreen(); } catch (Exception error) { visual.put("success", false).put("error", error.getMessage()); }
        reply.put("screenChanged", changed);
        reply.put("screenFrameSent", visual.optBoolean("success"));
        reply.put("verification", changed
                ? "UI 節點位置或內容已變更；最新螢幕影格已送達，請分析新畫面"
                : (visual.optBoolean("success")
                    ? "文字節點未變，但最新螢幕影格已送達；請依畫面判斷是否已滑動"
                    : "UI 節點與最新螢幕影格皆無法確認變化，請改用另一方向或尋找按鈕"));
        workingContext.recordAction("swipe:" + direction, reply.optBoolean("success", false) ? "submitted" : "failed");
        return autoObserveAfterMutation(reply, "swipe_screen");
    }

    private String nodeSignature(JSONObject response) {
        if (response == null || !response.optBoolean("success")) return "unavailable";
        JSONArray nodes = response.optJSONArray("nodes");
        if (nodes == null) return "empty";
        StringBuilder signature = new StringBuilder();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null) {
                signature.append(node.optString("text")).append('|')
                        .append(node.optString("desc")).append('|').append(node.optString("className")).append('|');
                JSONObject bounds = node.optJSONObject("bounds");
                if (bounds != null) signature.append(bounds.optInt("left")).append(',').append(bounds.optInt("top"))
                        .append(',').append(bounds.optInt("right")).append(',').append(bounds.optInt("bottom"));
                signature.append(';');
            }
        }
        return Integer.toHexString(signature.toString().hashCode());
    }

    private JSONObject tap(JSONObject args) throws Exception {
        double targetX = args.optDouble("x", -1);
        double targetY = args.optDouble("y", -1);
        String label = args.optString("label", args.optString("text", args.optString("name", ""))).trim();
        String id = args.optString("id", "").trim();
        String coordinateSpace = args.optString("coordinate_space", "").trim().toLowerCase();
        boolean resolvedFromNode = false;

        String tapMeta = label + " " + id;
        if (UserActionScope.looksLikeSendTarget(tapMeta) && !userActionScope.canSend()) {
            return runtimeBlocked("SEND_NOT_AUTHORIZED_BY_LATEST_USER_TURN",
                    "最新一句沒有明確要求傳送/回覆訊息；禁止點擊 Send。");
        }
        if (userActionScope.shouldBlockTapForSearch(tapMeta, label.isEmpty() && id.isEmpty())) {
            return runtimeBlocked("SEARCH_SCOPE_RESULT_OPEN_NOT_AUTHORIZED",
                    "最新任務只要求搜尋。搜尋結果出現後不要打開人、群組或聊天室；直接回報結果。");
        }

        // 🎯 1. Let Android activate the matching Accessibility node directly.
        if (!label.isEmpty() || !id.isEmpty()) {
            try {
                JSONObject nodeClick = helperPost("/click", new JSONObject().put("label", label).put("id", id));
                if (nodeClick.optBoolean("success")) {
                    nodeClick.put("resolvedFrom", "ui_node_action");
                    workingContext.recordAction("tap_screen", "submitted");
                    return autoObserveAfterMutation(nodeClick, "tap_screen");
                }
                JSONObject nodesResp = helperGet("/nodes");
                if (nodesResp.optBoolean("success")) {
                    JSONArray nodes = nodesResp.optJSONArray("nodes");
                    if (nodes != null) {
                        for (int i = 0; i < nodes.length(); i++) {
                            JSONObject node = nodes.getJSONObject(i);
                            String text = node.optString("text", "");
                            String desc = node.optString("desc", "");
                            String nodeId = node.optString("id", "");
                            boolean matchId = !id.isEmpty() && nodeId.toLowerCase().contains(id.toLowerCase());
                            boolean matchLabel = !label.isEmpty() && (text.toLowerCase().contains(label.toLowerCase()) || desc.toLowerCase().contains(label.toLowerCase()));
                            if (matchId || matchLabel) {
                                JSONObject bounds = node.optJSONObject("bounds");
                                if (bounds != null) {
                                    targetX = (bounds.optDouble("left", 0) + bounds.optDouble("right", 0)) / 2.0;
                                    targetY = (bounds.optDouble("top", 0) + bounds.optDouble("bottom", 0)) / 2.0;
                                    resolvedFromNode = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (targetX < 0 || targetY < 0) {
            return new JSONObject().put("success", false).put("error", "找不到指定點擊目標或座標");
        }

        // 📐 2. Explicit coordinate conversion.  The visual frame sent to the
        // model is normally max 1280px on its long edge, not the device size.
        if (!resolvedFromNode && "image".equals(coordinateSpace)) {
            if (lastScreenWidth <= 1 || lastScreenHeight <= 1) return new JSONObject().put("success", false).put("error", "尚未取得目前螢幕尺寸，請先要求查看螢幕後再依影像座標點擊");
            targetX = (targetX / Math.max(1, lastVisionWidth)) * lastScreenWidth;
            targetY = (targetY / Math.max(1, lastVisionHeight)) * lastScreenHeight;
        } else if (!resolvedFromNode && "normalized_1000".equals(coordinateSpace)) {
            if (lastScreenWidth <= 1 || lastScreenHeight <= 1) return new JSONObject().put("success", false).put("error", "尚未取得目前螢幕尺寸，請先 inspect_ui 或查看螢幕");
            targetX = (targetX / 1000.0) * lastScreenWidth;
            targetY = (targetY / 1000.0) * lastScreenHeight;
        } else if (!resolvedFromNode && coordinateSpace.isEmpty() && targetX <= 1.0 && targetY <= 1.0 && (targetX > 0 || targetY > 0)) {
            targetX = targetX * Math.max(1, lastScreenWidth);
            targetY = targetY * Math.max(1, lastScreenHeight);
        } else if (!resolvedFromNode && coordinateSpace.isEmpty() && targetX <= 1000.0 && targetY <= 1000.0 && targetX > 0 && targetY > 0 && targetY < 1200) {
            targetX = (targetX / 1000.0) * Math.max(1, lastScreenWidth);
            targetY = (targetY / 1000.0) * Math.max(1, lastScreenHeight);
        }

        JSONObject reply = helperPost("/tap", new JSONObject().put("x", Math.round(targetX)).put("y", Math.round(targetY)));
        reply.put("resolvedFrom", resolvedFromNode ? "ui_node" : (coordinateSpace.isEmpty() ? "legacy" : coordinateSpace));
        reply.put("visionSize", lastVisionWidth + "x" + lastVisionHeight).put("screenSize", lastScreenWidth + "x" + lastScreenHeight);
        workingContext.recordAction("tap_screen", reply.optBoolean("success", false) ? "submitted" : "failed");
        return autoObserveAfterMutation(reply, "tap_screen");
    }

    private JSONObject tapSemanticElement(JSONObject args) throws Exception {
        String elementId = args == null ? "" : args.optString("element_id", "").trim();
        if (elementId.isEmpty()) return new JSONObject().put("success", false).put("error", "MISSING_ELEMENT_ID");

        String elementMeta = semanticElementMeta(elementId);
        if (UserActionScope.looksLikeSendTarget(elementMeta) && !userActionScope.canSend()) {
            return runtimeBlocked("SEND_NOT_AUTHORIZED_BY_LATEST_USER_TURN",
                    "最新一句沒有明確要求傳送/回覆訊息；禁止點擊 Send。");
        }
        if (userActionScope.shouldBlockTapForSearch(elementMeta, elementMeta.isEmpty())) {
            return runtimeBlocked("SEARCH_SCOPE_RESULT_OPEN_NOT_AUTHORIZED",
                    "最新任務只要求搜尋。搜尋結果出現後不要打開人、群組或聊天室；直接回報結果。");
        }

        JSONObject reply = null;
        try {
            reply = helperPost("/semantic_tap", new JSONObject().put("elementId", elementId));
        } catch (Exception e) {
            reply = new JSONObject().put("success", false).put("error", e.getMessage() == null ? "tap failed" : e.getMessage());
        }
        if (reply == null) reply = new JSONObject();
        workingContext.recordAction("tap:" + elementId,
                reply.optBoolean("success", false) ? "submitted"
                        : reply.optString("error", "failed"));
        return autoObserveAfterMutation(reply, "tap_element");
    }

    private JSONObject waitForCondition(JSONObject args) throws Exception {
        String condition = args == null ? "" : args.optString("condition", "screen_change").trim();
        String elementId = args == null ? "" : args.optString("element_id", "").trim();
        long timeoutMs = args == null ? 5000L : args.optLong("timeout_ms", 5000L);

        PendingCondition.Type type = PendingCondition.Type.SCREEN_CHANGE;
        if ("element_appears".equals(condition)) type = PendingCondition.Type.ELEMENT_APPEARS;
        else if ("element_disappears".equals(condition)) type = PendingCondition.Type.ELEMENT_DISAPPEARS;

        pendingCondition = new PendingCondition(type, elementId, latestSemanticFingerprint, timeoutMs);
        workingContext.setPendingTask("WAIT_" + type.name());

        long deadline = System.currentTimeMillis() + pendingCondition.timeoutMs;
        JSONObject last = null;
        while (System.currentTimeMillis() < deadline
                && pendingCondition != null
                && !Thread.currentThread().isInterrupted()) {
            try { Thread.sleep(450L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                last = helperGet("/semantic_screen");
            } catch (Exception ignored) {}
            if (last == null || !last.optBoolean("success", false)) continue;

            String fp = last.optString("fingerprint", "");
            boolean met = false;
            if (type == PendingCondition.Type.SCREEN_CHANGE) {
                met = !fp.isEmpty() && !fp.equals(pendingCondition.baselineFingerprint);
            } else if (type == PendingCondition.Type.ELEMENT_APPEARS) {
                met = containsElement(last.optJSONArray("elements"), elementId);
            } else if (type == PendingCondition.Type.ELEMENT_DISAPPEARS) {
                met = !containsElement(last.optJSONArray("elements"), elementId);
            }

            if (met) {
                latestSemanticFingerprint = fp;
                semanticObserveRequired = false;
                workingContext.observe(last.optString("package", ""), fp, last.optString("stableScreenKey", ""));
                workingContext.setPendingTask("");
                pendingCondition = null;
                try {
                    last.put("conditionMet", true)
                        .put("condition", type.name())
                        .put("runtimeContext", workingContext.toModelJson());
                } catch (Exception ignored) {}
                return last;
            }
        }

        JSONObject out = last == null ? new JSONObject() : last;
        try {
            out.put("success", true)
               .put("conditionMet", false)
               .put("condition", type.name())
               .put("timeout", true)
               .put("runtimeContext", workingContext.toModelJson());
        } catch (Exception ignored) {}
        pendingCondition = null;
        workingContext.setPendingTask("");
        return out;
    }

    private String semanticElementMeta(String elementId) {
        if (elementId == null || elementId.isEmpty()) return "";
        JSONObject screen = readSemanticScreenQuietly();
        if (screen == null) return "";
        JSONArray elements = screen.optJSONArray("elements");
        if (elements == null) return "";
        for (int i = 0; i < elements.length(); i++) {
            JSONObject e = elements.optJSONObject(i);
            if (e == null || !elementId.equals(e.optString("id", ""))) continue;
            return e.optString("label", "") + " "
                    + e.optString("viewId", "") + " "
                    + e.optString("semanticHint", "") + " "
                    + e.optString("role", "");
        }
        return "";
    }

    private JSONObject runtimeBlocked(String error, String instruction) {
        JSONObject out = new JSONObject();
        try {
            workingContext.updateLastResult("STEP_FAILED");
            out.put("success", false)
                    .put("stepResult", "STEP_FAILED")
                    .put("blockedByRuntime", true)
                    .put("error", error)
                    .put("instruction", instruction)
                    .put("runtimeContext", workingContext.toModelJson());
        } catch (Exception ignored) {}
        return out;
    }

    private boolean containsElement(JSONArray elements, String elementId) {
        if (elements == null || elementId == null || elementId.isEmpty()) return false;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject e = elements.optJSONObject(i);
            if (e != null && elementId.equals(e.optString("id", ""))) return true;
        }
        return false;
    }

    private long mutationSettleDelayMs(String actionName) {
        if ("launch_app".equals(actionName)) return 650L;
        if ("press_key".equals(actionName)) return 320L;
        if ("type_text".equals(actionName)) return 260L;
        if ("swipe_screen".equals(actionName)) return 220L;
        return 220L;
    }

    /**
     * Some Android Accessibility / gesture APIs can report false even though the
     * requested UI transition actually happened. Do not make Gemini reason about
     * that contradiction. Runtime reconciles the raw execution result against the
     * immediate semantic screen and returns one authoritative STEP_OK/STEP_FAILED.
     *
     * This is intentionally conservative:
     * - raw success is never downgraded merely because the fingerprint is unchanged;
     * - raw failure is upgraded only when a real post-action screen change is seen;
     * - validation/policy/not-found/cancelled failures are never upgraded.
     */
    private boolean blocksOutcomeReconciliation(JSONObject result) {
        if (result == null) return false;
        if (result.optBoolean("cancelled", false) || result.optBoolean("agentStopped", false)) return true;
        String error = result.optString("error", "").trim().toUpperCase(Locale.ROOT);
        if (error.isEmpty()) return false;
        return error.contains("MISSING")
                || error.contains("NOT_FOUND")
                || error.contains("INVALID")
                || error.contains("UNSUPPORTED")
                || error.contains("POLICY")
                || error.contains("BLOCKED")
                || error.contains("DENIED")
                || error.contains("SENSITIVE")
                || error.contains("找不到")
                || error.contains("不可為空")
                || error.contains("不支援")
                || error.contains("禁止")
                || error.contains("使用者已停止");
    }

    private JSONObject readSemanticScreenQuietly() {
        try {
            return helperGet("/semantic_screen");
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean semanticScreenChanged(JSONObject after, String beforeFingerprint) {
        if (after == null || !after.optBoolean("success", false)) return false;
        String afterFingerprint = after.optString("fingerprint", "");
        return !beforeFingerprint.isEmpty()
                && !afterFingerprint.isEmpty()
                && !beforeFingerprint.equals(afterFingerprint);
    }

    private JSONObject autoObserveAfterMutation(JSONObject actionResult, String actionName) {
        if (actionResult == null) actionResult = new JSONObject();

        final boolean executionSuccess = actionResult.optBoolean("success", false);
        final boolean reconciliationBlocked = blocksOutcomeReconciliation(actionResult);
        final String beforeFingerprint = latestSemanticFingerprint;

        try {
            Thread.sleep(mutationSettleDelayMs(actionName));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        JSONObject after = readSemanticScreenQuietly();
        boolean changed = semanticScreenChanged(after, beforeFingerprint);

        // A false-negative Android result can race the actual UI transition.
        // Retry observation once, but only for a potentially recoverable raw failure.
        if (!executionSuccess && !reconciliationBlocked && !changed && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(240L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            JSONObject retry = readSemanticScreenQuietly();
            if (retry != null && retry.optBoolean("success", false)) {
                after = retry;
                changed = semanticScreenChanged(after, beforeFingerprint);
            }
        }

        final boolean finalSuccess = executionSuccess || (!reconciliationBlocked && changed);

        if (after != null && after.optBoolean("success", false)) {
            String fp = after.optString("fingerprint", "");
            latestSemanticFingerprint = fp;
            semanticObserveRequired = false;
            workingContext.observe(after.optString("package", ""), fp, after.optString("stableScreenKey", ""));
        } else {
            semanticObserveRequired = true;
        }

        // Keep the model-facing contract deliberately small and authoritative.
        try {
            actionResult.put("success", finalSuccess)
                    .put("stepResult", finalSuccess ? "STEP_OK" : "STEP_FAILED")
                    .put("screenChanged", changed);

            if (after != null && after.optBoolean("success", false)) {
                actionResult.put("after", after);
            }

            // Remove legacy verification fields that can contradict the reconciled result
            // and make a weaker Live model second-guess Runtime.
            actionResult.remove("progress");
            actionResult.remove("noProgressCount");
            actionResult.remove("recoveryHint");
            actionResult.remove("autoVerification");
            actionResult.remove("verification");
            actionResult.remove("fingerprint");

            if (finalSuccess) {
                actionResult.remove("error");
                if (!executionSuccess) {
                    actionResult.put("message", "操作已生效。");
                    Log.i(TAG, "Reconciled false-negative mutation as STEP_OK: " + actionName);
                }
            } else {
                actionResult.put("message", "這一步未確認生效，請依最新畫面改用其他方法，不要重複相同操作。");
            }

            workingContext.updateLastResult(finalSuccess ? "STEP_OK" : "STEP_FAILED");
            actionResult.put("runtimeContext", workingContext.toModelJson());
        } catch (Exception ignored) {}

        return actionResult;
    }

    private JSONObject inspectUi(JSONObject args) throws Exception {
        JSONObject reply = helperGet("/semantic_screen");
        if (reply == null) reply = new JSONObject();
        if (reply.optBoolean("success", false)) {
            latestSemanticFingerprint = reply.optString("fingerprint", "");
            semanticObserveRequired = false;
            workingContext.observe(reply.optString("package", ""), latestSemanticFingerprint, reply.optString("stableScreenKey", ""));
            try { reply.put("runtimeContext", workingContext.toModelJson()); } catch (Exception ignored) {}
        }
        return reply;
    }

    private JSONObject inspectUiLegacy() throws Exception {
        JSONObject raw = helperGet("/screen_state");
        if (!raw.optBoolean("success")) return raw;
        String fingerprint = raw.optString("fingerprint", "");
        if (!fingerprint.isEmpty()) lastObservedScreenFingerprint = fingerprint;
        JSONArray nodes = raw.optJSONArray("nodes");
        JSONArray actions = raw.optJSONArray("actions");
        JSONArray visible = new JSONArray();
        if (nodes != null) {
            for (int i = 0; i < nodes.length() && visible.length() < 80; i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node == null) continue;
                String text = node.optString("text", "").trim();
                String desc = node.optString("desc", "").trim();
                if (text.isEmpty() && desc.isEmpty()) continue;
                JSONObject item = new JSONObject().put("text", text).put("desc", desc).put("clickable", node.optBoolean("clickable"));
                if (node.has("bounds")) item.put("bounds", node.optJSONObject("bounds"));
                visible.put(item);
            }
        }
        JSONObject result = new JSONObject().put("success", true).put("nodeCount", nodes == null ? 0 : nodes.length()).put("visible", visible)
                .put("message", "已讀取目前真實 UI 節點；請務必只根據此 visible 內容向使用者作答，切勿自行腦補。");
        if (actions != null) result.put("actions", actions);
        if (!fingerprint.isEmpty()) result.put("fingerprint", fingerprint);
        return result;
    }

    /**
     * 🛡️ Automatic Post-Action Verification Engine:
     * Immediately captures the real UI state after any action (launch/tap/type/press_key),
     * embedding actual visible nodes and package name into the tool response.
     * This physically eliminates premature hallucination by giving Gemini the ground-truth result.
     */
    private void autoVerifyUiSnapshot(JSONObject response, int waitDelayMs) {
        if (response == null || !response.optBoolean("success", false)) return;
        try {
            String beforeFingerprint = lastObservedScreenFingerprint;
            if (waitDelayMs > 0) Thread.sleep(waitDelayMs);
            JSONObject raw = helperGet("/screen_state");
            if (raw.optBoolean("success")) {
                String currentPkg = raw.optString("package", "");
                String afterFingerprint = raw.optString("fingerprint", "");
                String progress = "UNKNOWN";
                if (!beforeFingerprint.isEmpty() && !afterFingerprint.isEmpty()) {
                    progress = beforeFingerprint.equals(afterFingerprint) ? "UNCHANGED" : "PROGRESSED";
                    if ("UNCHANGED".equals(progress)) {
                        consecutiveNoProgress++;
                    } else {
                        consecutiveNoProgress = 0;
                    }
                }
                if (!afterFingerprint.isEmpty()) lastObservedScreenFingerprint = afterFingerprint;
                response.put("progress", progress);
                response.put("noProgressCount", consecutiveNoProgress);
                response.put("fingerprint", afterFingerprint);
                if ("UNCHANGED".equals(progress)) {
                    response.put("recoveryHint",
                            consecutiveNoProgress >= 2
                                    ? "畫面連續沒有進展：停止重複同一動作，改用返回、重新聚焦、另一個 semantic action，或向使用者說明卡點。"
                                    : "畫面沒有改變：重新 inspect_ui，下一步不可原樣重複剛才動作。");
                }
                JSONArray nodes = raw.optJSONArray("nodes");
                JSONArray actions = raw.optJSONArray("actions");
                JSONArray visible = new JSONArray();
                if (nodes != null) {
                    for (int i = 0; i < nodes.length() && visible.length() < 30; i++) {
                        JSONObject node = nodes.optJSONObject(i);
                        if (node == null) continue;
                        String text = node.optString("text", "").trim();
                        String desc = node.optString("desc", "").trim();
                        if (text.isEmpty() && desc.isEmpty()) continue;
                        visible.put(new JSONObject().put("text", text).put("desc", desc).put("clickable", node.optBoolean("clickable")));
                    }
                }
                JSONObject verification = new JSONObject();
                verification.put("currentPackage", currentPkg);
                verification.put("verifiedNodeCount", nodes == null ? 0 : nodes.length());
                verification.put("actualVisibleContent", visible);
                if (actions != null) verification.put("verifiedActions", actions);
                verification.put("instruction", "【系統真實校驗結果】以上為動作執行後的真實畫面內容。請直接根據 actualVisibleContent 向使用者報告實際看見的狀態，絕對不可捏造尚未出現的內容！");
                response.put("autoVerification", verification);
            }
        } catch (Exception ignored) {}
    }

    private JSONObject launchApp(JSONObject args) throws Exception {
        String app = args.optString("app", "").trim();
        int explicitIndex = args.optInt("index", -1);
        String explicitPkg = args.optString("package_name", "").trim();

        if (!explicitPkg.isEmpty()) {
            JSONObject reply = helperPost("/launch", new JSONObject().put("package", explicitPkg));
            if (reply.optBoolean("success")) {
                lastCandidateApps.clear();
                reply.put("app", app.isEmpty() ? explicitPkg : app).put("message", "已啟動 App，以下為啟動後的最新畫面。");
            }
            workingContext.recordAction("launch:" + explicitPkg, reply.optBoolean("success", false) ? "submitted" : "failed");
            return autoObserveAfterMutation(reply, "launch_app");
        }

        int selectedIndex = parseOrdinalIndex(app);
        if (selectedIndex < 0 && explicitIndex > 0) selectedIndex = explicitIndex - 1;
        if (selectedIndex >= 0 && !lastCandidateApps.isEmpty()) {
            if (selectedIndex >= lastCandidateApps.size()) return new JSONObject().put("success", false).put("error", "候選 App 編號超出範圍");
            JSONObject chosen = lastCandidateApps.get(selectedIndex);
            lastCandidateApps.clear();
            String pkg = chosen.optString("package", "");
            JSONObject reply = helperPost("/launch", new JSONObject().put("package", pkg));
            if (reply.optBoolean("success")) reply.put("app", chosen.optString("label", "App")).put("message", "已啟動 App，以下為啟動後的最新畫面。");
            workingContext.recordAction("launch:" + pkg, reply.optBoolean("success", false) ? "submitted" : "failed");
            return autoObserveAfterMutation(reply, "launch_app");
        }

        if (app.isEmpty()) return new JSONObject().put("success", false).put("error", "App 名稱不可為空");

        // Fast path: one localhost request. Runtime resolves from cached AppCatalog.
        JSONObject reply = helperPost("/launch", new JSONObject().put("app", app));
        if (reply.optBoolean("success", false)) {
            lastCandidateApps.clear();
            String pkg = reply.optString("package", "");
            reply.put("app", reply.optString("label", app)).put("message", "已啟動 App，以下為啟動後的最新畫面。");
            workingContext.recordAction("launch:" + (pkg.isEmpty() ? app : pkg), "submitted");
            return autoObserveAfterMutation(reply, "launch_app");
        }

        if ("MULTIPLE_MATCHES".equals(reply.optString("status", ""))) {
            JSONArray matches = reply.optJSONArray("matches");
            lastCandidateApps.clear();
            JSONArray candidates = new JSONArray();
            StringBuilder prompt = new StringBuilder("找到多個相近 App，請說第幾個：\n");
            if (matches != null) {
                for (int i = 0; i < matches.length(); i++) {
                    JSONObject c = matches.optJSONObject(i);
                    if (c == null) continue;
                    lastCandidateApps.add(c);
                    candidates.put(new JSONObject().put("index", lastCandidateApps.size()).put("label", c.optString("label", "App")).put("package", c.optString("package", "")));
                    prompt.append(lastCandidateApps.size()).append(". ").append(c.optString("label", "App")).append("\n");
                }
            }
            return new JSONObject().put("success", false).put("status", "MULTIPLE_MATCHES").put("candidates", candidates)
                    .put("error", prompt.toString().trim()).put("instruction", "只詢問使用者要開第幾個；回答後再呼叫 launch_app(index=...)。");
        }
        return reply;
    }

    private int parseOrdinalIndex(String input) {
        if (input == null) return -1;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.equals("第一個") || s.equals("第1個") || s.equals("第 1 個") || s.equals("1") || s.equals("first") || s.equals("one") || s.equals("前一個")) return 0;
        if (s.equals("第二個") || s.equals("第2個") || s.equals("第 2 個") || s.equals("2") || s.equals("second") || s.equals("two")) return 1;
        if (s.equals("第三個") || s.equals("第3個") || s.equals("第 3 個") || s.equals("3") || s.equals("third") || s.equals("three")) return 2;
        if (s.equals("第四個") || s.equals("第4個") || s.equals("第 4 個") || s.equals("4") || s.equals("fourth") || s.equals("four")) return 3;
        if (s.equals("第五個") || s.equals("第5個") || s.equals("第 5 個") || s.equals("5") || s.equals("fifth") || s.equals("five")) return 4;
        if (s.equals("最後一個") || s.equals("最後") || s.equals("last")) {
            return lastCandidateApps.isEmpty() ? -1 : lastCandidateApps.size() - 1;
        }
        return -1;
    }

    private String loadVoiceSkillPlaybook() {
        if (serverUrl.isEmpty()) return ""; // Standalone mode: no custom skills server needed
        HttpURLConnection connection = null;
        try {
            String endpoint = serverUrl.replaceAll("/+$", "") + "/api/phone/skills";
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("GET"); connection.setConnectTimeout(1500); connection.setReadTimeout(2500);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return "";
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder raw = new StringBuilder(); String line; while ((line = reader.readLine()) != null) raw.append(line); reader.close();
            JSONArray skills = new JSONObject(raw.toString()).optJSONArray("skills");
            if (skills == null) return "";
            StringBuilder playbook = new StringBuilder();
            for (int i = 0; i < skills.length() && i < 12 && playbook.length() < 12000; i++) {
                JSONObject skill = skills.optJSONObject(i);
                if (skill == null) continue;
                String name = skill.optString("name", "").trim();
                String instruction = skill.optString("instruction", "").trim();
                if (!name.isEmpty() && !instruction.isEmpty()) playbook.append("\n[技能：").append(name).append("] ").append(instruction);
            }
            return playbook.toString();
        } catch (Exception ignored) { return ""; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private JSONObject helperGet(String endpoint) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:8766" + endpoint).openConnection();
            activeToolConnection = connection;
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000); connection.setReadTimeout(5000);
            int code = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(), "UTF-8"));
            StringBuilder text = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) text.append(line);
            reader.close();
            return text.length() == 0 ? new JSONObject() : new JSONObject(text.toString());
        } finally { if (connection != null) connection.disconnect(); if (activeToolConnection == connection) activeToolConnection = null; }
    }

    private JSONObject typeText(JSONObject args) throws Exception {
        String text = args.optString("text", "").trim();
        if (text.isEmpty()) return new JSONObject().put("success", false).put("error", "輸入文字不可為空");
        if (userActionScope.shouldBlockAdditionalTextEntry()) {
            return runtimeBlocked("SEARCH_SCOPE_ADDITIONAL_TEXT_NOT_AUTHORIZED",
                    "搜尋查詢已輸入；最新任務沒有授權進入聊天室或再輸入訊息。");
        }

        // 1. If target or coordinates provided, tap to focus first
        String target = args.optString("target", args.optString("label", "")).trim();
        double x = args.optDouble("x", -1);
        double y = args.optDouble("y", -1);

        if (!target.isEmpty() || (x >= 0 && y >= 0)) {
            try {
                JSONObject tapArgs = new JSONObject();
                if (!target.isEmpty()) tapArgs.put("label", target);
                if (x >= 0) tapArgs.put("x", x);
                if (y >= 0) tapArgs.put("y", y);
                tap(tapArgs);
                Thread.sleep(300);
            } catch (Exception ignored) {}
        }

        // 2. Send text to Accessibility Service.
        // Preserve the bridge result; Runtime reconciliation below decides the final outcome.
        JSONObject reply = helperPost("/type", new JSONObject().put("text", text));
        if (reply.optBoolean("success", false)) {
            reply.put("message", "已在輸入框輸入文字");
        }
        workingContext.recordAction("type", reply.optBoolean("success", false) ? "submitted" : "failed");
        JSONObject observed = autoObserveAfterMutation(reply, "type_text");
        if (observed.optBoolean("success", false) && userActionScope.markSearchQueryEntered()) {
            observed.put("taskBoundary", "SEARCH_RESULTS_ONLY");
            observed.put("instruction",
                    "最新任務只要求搜尋；現在只觀察並回報搜尋結果，不要打開結果、群組、聊天室，也不要傳訊息。");
        }
        return observed;
    }

    private JSONObject sendTextToPhone(JSONObject args) throws Exception {
        String text = args.optString("text", "");
        if (text.isEmpty()) {
            return new JSONObject().put("success", false).put("error", "EMPTY_TEXT");
        }
        if (!userActionScope.canSend()) {
            return runtimeBlocked("SEND_NOT_AUTHORIZED_BY_LATEST_USER_TURN",
                    "最新一句沒有明確要求傳訊息、發訊息、回覆或送出目前訊息；Runtime 已阻止送出。");
        }

        if (userActionScope.requiresRecipientVerification()) {
            String recipient = userActionScope.authorizedRecipient();
            if (recipient.isEmpty()) {
                return runtimeBlocked("SEND_RECIPIENT_NOT_EXPLICIT",
                        "使用者雖然明確要求傳訊息，但沒有可驗證的收件人。請只問要傳給誰。");
            }
            JSONObject currentScreen = readSemanticScreenQuietly();
            SendRecipientVerifier.Result recipientCheck =
                    SendRecipientVerifier.verify(currentScreen, recipient);
            if (!recipientCheck.verified) {
                Log.w(TAG, "0028 send blocked: recipient not verified (" + recipientCheck.reason + ")");
                return runtimeBlocked("SEND_RECIPIENT_NOT_VERIFIED",
                        "目前畫面無法確認是指定收件人的聊天室。不要送出；重新 inspect_ui/進入正確聊天室，或請使用者確認。");
            }
        }

        JSONObject reply = helperPost(
                "/send_text",
                new JSONObject().put("text", text));
        // Runtime deliberately does not echo plaintext back to Gemini.
        reply.put("textLength", text.length());
        String sendError = reply.optString("error", "").toUpperCase(Locale.ROOT);
        if (reply.optBoolean("success", false) || sendError.contains("SEND_NOT_VERIFIED")) {
            userActionScope.consumeSendAuthorization();
        }
        workingContext.recordAction("send_text", reply.optBoolean("success", false) ? "submitted" : "failed");
        return reply;
    }

    private JSONObject pressKey(JSONObject args) throws Exception {
        String key = args.optString("key", "").toUpperCase();
        if (!("HOME".equals(key) || "BACK".equals(key) || "RECENTS".equals(key) || "NOTIFICATIONS".equals(key) || "QUICK_SETTINGS".equals(key) || "POWER_DIALOG".equals(key))) return new JSONObject().put("success", false).put("error", "不支援的系統按鍵");
        JSONObject reply = helperPost("/key", new JSONObject().put("key", key));
        workingContext.recordAction("key:" + key, reply.optBoolean("success", false) ? "submitted" : "failed");
        return autoObserveAfterMutation(reply, "press_key");
    }

    private JSONObject sendToMainChat(JSONObject args) throws Exception {
        String message = args.optString("message", args.optString("text", "")).trim();
        if (message.isEmpty()) return new JSONObject().put("success", false).put("error", "主對話訊息不可為空");
        String targetUrl = serverUrl;
        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            targetUrl = AppConfig.DEFAULT_SERVER;
        }
        HttpURLConnection connection = null;
        String endpoint = targetUrl.replaceAll("/+$", "") + "/api/inbound/messages";
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(5000);
            byte[] body = new JSONObject().put("message", message).put("source", "CrewHelper").toString().getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            out.write(body);
            out.close();
            int code = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(), "UTF-8"));
            StringBuilder raw = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) raw.append(line);
            reader.close();
            JSONObject reply = raw.length() == 0 ? new JSONObject() : new JSONObject(raw.toString());
            if (code < 200 || code >= 300) return new JSONObject().put("success", false).put("httpStatus", code)
                    .put("error", reply.optString("error", "Crew Pocket 主聊天拒絕接收訊息"));
            boolean delivered = reply.optBoolean("delivered", false);
            int pending = reply.optInt("pending", 0);
            reply.put("success", true).put("deliveryStatus", delivered ? "delivered" : "queued")
                    .put("message", delivered ? "已送到 Crew Pocket 主聊天。" : "主聊天目前未連線；訊息已排隊（待送 " + pending + " 則），開啟 Crew Pocket 主頁後才會送出。");
            return reply;
        } catch (Exception e) {
            Log.w(TAG, "sendToMainChat error: " + e.getMessage());
            return new JSONObject().put("success", false).put("error", "無法連線 Crew Pocket 主聊天橋接：" + (e.getMessage() == null ? endpoint : e.getMessage()));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private JSONObject captureAndSendScreen() throws Exception {
        JSONObject capture = helperPost("/screenshot", new JSONObject());
        if (!capture.optBoolean("success")) return capture;
        String path = capture.optString("latestPath", capture.optString("path", ""));
        if (path.isEmpty()) return new JSONObject().put("success", false).put("error", "截圖未提供檔案路徑");
        if (!sendImageFile(path, true)) return new JSONObject().put("success", false).put("error", "截圖已取得，但 Gemini 連線不可用");
        return new JSONObject().put("success", true).put("silent", capture.optBoolean("silent")).put("message", "最新手機螢幕已傳送，請只依這張畫面回答。");
    }

    private boolean sendImageFile(String path, boolean isScreenFrame) throws Exception {
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap == null) return false;
        int sourceWidth = bitmap.getWidth();
        int sourceHeight = bitmap.getHeight();
        int maxEdge = 1024;
        if (Math.max(bitmap.getWidth(), bitmap.getHeight()) > maxEdge) {
            float scale = maxEdge / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
            bitmap.recycle(); bitmap = scaled;
        }
        lastVisionWidth = bitmap.getWidth();
        lastVisionHeight = bitmap.getHeight();
        if (isScreenFrame) {
            lastScreenWidth = sourceWidth;
            lastScreenHeight = sourceHeight;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output); bitmap.recycle();
        JSONObject video = new JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
        return webSocket != null && webSocket.send(new JSONObject().put("realtimeInput", new JSONObject().put("video", video)).toString());
    }

    private JSONObject helperPost(String endpoint, JSONObject payload) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:8766" + endpoint).openConnection();
            activeToolConnection = connection;
            connection.setRequestMethod("POST"); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true); connection.setConnectTimeout(3500); connection.setReadTimeout(7000);
            byte[] body = payload.toString().getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream(); out.write(body); out.close();
            int code = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(), "UTF-8"));
            StringBuilder text = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) text.append(line);
            reader.close();
            JSONObject response = text.length() == 0 ? new JSONObject() : new JSONObject(text.toString());
            if (!response.has("success")) response.put("success", code >= 200 && code < 300);
            return response;
        } finally { if (connection != null) connection.disconnect(); if (activeToolConnection == connection) activeToolConnection = null; }
    }

    private void sendToolResponse(String id, String name, JSONObject result) throws Exception {
        synchronized (agentLock) {
            if (activeAgentTask != null) {
                AgentTaskRecord task = activeAgentTask;
                long remainingMs = Math.max(0, AGENT_TASK_TIMEOUT_MS - (System.currentTimeMillis() - task.startedAt));
                result.put("agentState", new JSONObject().put("taskId", task.taskId)
                        .put("remainingSteps", Math.max(0, agentMaxSteps - task.steps))
                        .put("remainingTimeMs", remainingMs)
                        .put("canContinue", !task.cancelled && !task.finished && task.blockedReason == null
                                && task.steps < agentMaxSteps && remainingMs > 0));
            }
        }
        if (DeckRepository.hasActiveDeck() && (name.contains("deck"))) {
            result.put("modeInstructions", LivePrompt.DECK);
        }
        JSONObject item = new JSONObject().put("response", new JSONObject().put("result", result)).put("id", id).put("name", name);
        if (webSocket == null || !webSocket.send(new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", new JSONArray().put(item))).toString())) {
            throw new Exception("工具結果無法傳回 Gemini");
        }
    }

    private void startAudio() {
        if (!running || recorder != null) return;
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(min * 4, 8192);
        try {
            // 🎙️ VOICE_COMMUNICATION engages Android's hardware DSP full-duplex AEC (Acoustic Echo Cancellation) & AGC
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
        } catch (Exception e) {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
        }

        // Attach hardware audio effects if supported by Samsung/Android
        try {
            // 🛡️ AcousticEchoCanceler (AEC): Essential to prevent AI hearing its own voice from loudspeaker!
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                aecEffect = android.media.audiofx.AcousticEchoCanceler.create(recorder.getAudioSessionId());
                if (aecEffect != null) aecEffect.setEnabled(true);
            }
            // Keep NoiseSuppressor to clean air conditioner / ambient hiss
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                nsEffect = android.media.audiofx.NoiseSuppressor.create(recorder.getAudioSessionId());
                if (nsEffect != null) nsEffect.setEnabled(true);
            }
        } catch (Exception ignored) {}

        createAudioPlayer();
        startPlaybackWorker();
        recorder.startRecording();
        new Thread(new Runnable() { @Override public void run() { sendMic(); } }, "crew-native-live-mic").start();
    }

    private void createAudioPlayer() {
        usingOboeOutput = NativeOboeOutput.start(audioOutput);
        if (usingOboeOutput) {
            String info = NativeOboeOutput.getInfo();
            audioOutputBackend = info == null ? "Oboe／AAudio 低延遲" : info;
            Log.i(TAG, "Oboe low-latency output enabled");
            return;
        }
        audioOutputBackend = "Android AudioTrack 備援";
        synchronized (playerLock) {
            try { if (player != null) { player.stop(); player.release(); } } catch (Exception ignored) {}
            int outMin = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            // One second leaves room for GC, image upload, and transient Wi-Fi jitter.
            int bufferBytes = Math.max(outMin * 8, 48000);
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage("media".equals(audioOutput) ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            AudioFormat format = new AudioFormat.Builder().setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            player = new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes).setTransferMode(AudioTrack.MODE_STREAM).build();
        }
    }

    private void startPlaybackWorker() {
        audioQueue.clear();
        if (usingOboeOutput) { audioPlaybackRunning = true; return; }
        audioPlaybackRunning = true;
        audioPlaybackThread = new Thread(new Runnable() {
            @Override public void run() { runPlaybackLoop(); }
        }, "crew-native-live-playback");
        audioPlaybackThread.start();
    }

    private void runPlaybackLoop() {
        boolean started = false;
        while (audioPlaybackRunning) {
            try {
                byte[] first = audioQueue.poll(300, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                if (!started) {
                    // Start with about 200 ms buffered. It avoids the initial
                    // AudioTrack underrun that previously disabled the track.
                    ArrayList<byte[]> initial = new ArrayList<byte[]>();
                    initial.add(first);
                    int bytes = first.length;
                    long deadline = System.currentTimeMillis() + 180;
                    while (bytes < 9600 && System.currentTimeMillis() < deadline) {
                        byte[] next = audioQueue.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                        if (next == null) break;
                        initial.add(next); bytes += next.length;
                    }
                    synchronized (playerLock) { if (player != null) player.play(); }
                    started = true;
                    for (byte[] chunk : initial) writeAudioChunk(chunk);
                } else {
                    writeAudioChunk(first);
                }
            } catch (InterruptedException ignored) {
                // stopAudio interrupts this worker; the loop condition decides exit.
            } catch (Exception error) {
                Log.w(TAG, "音訊播放工作執行失敗：" + error.getMessage());
                recoverAudioPlayer();
                started = false;
            }
        }
    }

    private void writeAudioChunk(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || interruptedCurrentTurn || agentMuted) return;
        int written;
        synchronized (playerLock) {
            // 🛡️ Ensure AudioTrack is in PLAYING state (e.g. after interruption flush/pause)
            if (player != null && player.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    player.play();
                } catch (Exception ignored) {}
            }
            written = player == null ? AudioTrack.ERROR_INVALID_OPERATION : player.write(pcm, 0, pcm.length);
        }
        if (written < 0) {
            Log.w(TAG, "AudioTrack 寫入失敗（" + written + "），重建播放軌");
            recoverAudioPlayer();
        }
    }

    private void recoverAudioPlayer() {
        if (!audioPlaybackRunning || !running) return;
        createAudioPlayer();
    }
    private double calculateRms(byte[] pcm, int count) {
        if (count < 2) return 0;
        long sum = 0;
        int samples = count / 2;
        for (int i = 0; i < count - 1; i += 2) {
            short val = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) val * val;
        }
        return Math.sqrt((double) sum / samples) / 32768.0;
    }

    private volatile long lastPlaybackActiveAt = 0;
    private volatile long lastMeterReportAt = 0;
    private double noiseFloor = 0.015;
    private static final int CALIBRATION_FRAMES = 20; // 800 ms at 40 ms/frame

    private void sendMic() {
        byte[] pcm = new byte[1280]; // 40ms @ 16kHz 16-bit mono
        int consecutiveVoiceFrames = 0;
        int calibrationFrames = 0;
        double[] calibrationSamples = new double[CALIBRATION_FRAMES];

        while (running && recorder != null && webSocket != null) {
            int count = recorder.read(pcm, 0, pcm.length); if (count <= 0) continue;
            if (agentMuted) continue;

            double rms = calculateRms(pcm, count);
            String mode = noiseMode;
            int suppression = noiseSuppression;

            // First 0.8 s establishes a local acoustic baseline and is never sent upstream.
            // It prevents the server VAD from treating connection-time background noise as speech.
            if (calibrationFrames < CALIBRATION_FRAMES) {
                calibrationSamples[calibrationFrames] = rms;
                calibrationFrames++;
                if (calibrationFrames == CALIBRATION_FRAMES) {
                    Arrays.sort(calibrationSamples);
                    double baseline = 0;
                    for (int i = 0; i < 12; i++) baseline += calibrationSamples[i];
                    noiseFloor = Math.max(0.008, baseline / 12.0);
                    // This is an audio status, not a connection stage.  Do not overwrite
                    // setupComplete in the connection watchdog with a calibration message.
                    listener.onStatus("環境降噪已校正（" + mode + "）");
                }
                // Calibration is observational only. Never silence the user's first words.
            }

            // The environment bar only guards *interruptions while Gemini speaks*.
            // It never replaces outgoing PCM with silence, so quiet user speech remains safe.
            double modeBase = "noisy".equals(mode) ? 1.45 : ("quiet".equals(mode) ? 0.65 : 0.90);
            double gateMultiplier = modeBase + suppression * 0.008;
            double minBase = "noisy".equals(mode) ? 0.022 : ("quiet".equals(mode) ? 0.002 : 0.006);
            double minGate = minBase + suppression * 0.00010;
            double gateThreshold = Math.max(minGate, noiseFloor * gateMultiplier);
            // Energy is the fail-open source of truth.  The old zero-crossing condition
            // rejected soft vowels on some Android microphones, leaving Gemini silent.
            boolean speechCandidate = rms >= gateThreshold;

            // Learn only frames rejected as speech. This lets the floor rise in a busy street
            // without slowly learning the user's own voice as "noise".
            if (!speechCandidate && !aiSpeaking) {
                noiseFloor = noiseFloor * 0.985 + Math.min(rms, 0.18) * 0.015;
            }

            if (aiSpeaking) {
                if (!allowVoiceInterruption) {
                    // 🛡️ 防插話保護模式：AI 說話時麥克風完全靜音，徹底杜絕任何環境音插話
                    consecutiveVoiceFrames = 0;
                    continue;
                }

                // Speaker echo can be continuous, particularly immediately after a
                // tool result. Hardware AEC is active, so do not require shouting:
                // normal close-range speech should interrupt within about 0.3 s.
                // The center button remains an instant interrupt.
                boolean outputAudible = System.currentTimeMillis() < lastPlaybackActiveAt;
                int requiredVoiceFrames = 5 + (suppression + 30) / 35 + (outputAudible ? 2 : 0);
                double baseInterrupt = ("noisy".equals(mode) ? 0.070 : 0.048)
                        + suppression * 0.00022 + (outputAudible ? 0.010 : 0.0);
                double floorMultiplier = ("noisy".equals(mode) ? 2.1 : 1.65) + suppression * 0.008;
                // 0 = deliberate / resistant to stray sound; 100 = quickest barge-in.
                // Keep a floor so a click or residual speaker echo cannot instantly cut speech.
                double sensitivity = interruptionSensitivity / 100.0;
                requiredVoiceFrames += Math.round((1.0 - sensitivity) * 7.0 - sensitivity * 2.0);
                requiredVoiceFrames = Math.max(2, requiredVoiceFrames);
                baseInterrupt += (1.0 - sensitivity) * 0.035 - sensitivity * 0.012;
                floorMultiplier += (1.0 - sensitivity) * 0.55 - sensitivity * 0.20;
                double interruptThreshold = Math.max(baseInterrupt, noiseFloor * floorMultiplier);
                if (speechCandidate && rms >= interruptThreshold) {
                    consecutiveVoiceFrames++;
                    if (consecutiveVoiceFrames >= requiredVoiceFrames) {
                        triggerLocalInterruption();
                        consecutiveVoiceFrames = 0;
                    }
                } else {
                    // Do not let separate bursts accumulate into a false interruption.
                    consecutiveVoiceFrames = 0;
                }

                // 若尚未確認為明確插話指令，暫緩將喇叭音訊回傳給 Gemini，避免伺服器端迴音干擾
                if (aiSpeaking) {
                    // The microphone is live; only upstream transmission is held
                    // while the assistant speaks. Keep the meter fresh so voice
                    // diagnostics never report a false missing-microphone error.
                    reportMicrophoneLevel(rms, gateThreshold, false);
                    continue;
                }
            } else {
                consecutiveVoiceFrames = 0;
            }

            byte[] chunk = (count == pcm.length) ? pcm.clone() : Arrays.copyOf(pcm, count);

            // Do not locally replace PCM with silence.  Energy-only gating is not a real VAD
            // and can suppress quiet human speech; Android's hardware NoiseSuppressor remains
            // active while all captured speech is delivered to Gemini.
            reportMicrophoneLevel(rms, gateThreshold, true);

            // 🎙️ 連續即時串流給 Gemini Live
            try {
                JSONObject root = new JSONObject(); JSONObject audio = new JSONObject();
                audio.put("mimeType", "audio/pcm;rate=16000");
                audio.put("data", Base64.encodeToString(chunk, Base64.NO_WRAP));
                root.put("realtimeInput", new JSONObject().put("audio", audio));
                if (!webSocket.send(root.toString())) throw new Exception("audio send failed");
                if (audioIncidentRecorder != null) {
                    audioIncidentRecorder.onUpstreamPcm(chunk, rms, noiseFloor, gateThreshold);
                }
            } catch (Exception error) { fail("麥克風串流失敗：" + error.getMessage(), error); }
        }
    }

    private double calculateZeroCrossingRate(byte[] pcm, int count) {
        if (count < 4) return 0;
        int crossings = 0;
        short previous = (short) ((pcm[0] & 0xFF) | (pcm[1] << 8));
        for (int i = 2; i < count - 1; i += 2) {
            short current = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if ((previous < 0 && current >= 0) || (previous >= 0 && current < 0)) crossings++;
            previous = current;
        }
        return (double) crossings / Math.max(1, count / 2);
    }

    private void reportMicrophoneLevel(double rms, double gate, boolean sending) {
        long now = System.currentTimeMillis();
        if (now - lastMeterReportAt < 180) return;
        lastMeterReportAt = now;
        double dbfs = rms <= 0.000001 ? -96.0 : Math.max(-96.0, 20.0 * Math.log10(rms));
        double gateDbfs = gate <= 0.000001 ? -96.0 : Math.max(-96.0, 20.0 * Math.log10(gate));
        listener.onMicrophoneLevel(dbfs, gateDbfs, sending);
    }
    private void enqueueAudio(byte[] pcm) {
        if (agentMuted || interruptedCurrentTurn || pcm == null || pcm.length == 0) return;
        long durationMs = pcm.length * 1000L / (24000 * 2);
        lastPlaybackActiveAt = Math.max(System.currentTimeMillis(), lastPlaybackActiveAt) + durationMs;
        if (usingOboeOutput) { NativeOboeOutput.write(pcm); return; }
        // Preserve current speech instead of blocking the WebSocket callback.
        if (!audioQueue.offer(pcm)) {
            audioQueue.poll();
            if (!audioQueue.offer(pcm)) Log.w(TAG, "音訊佇列已滿，略過過期語音片段");
        }
    }

    /** Compact in-memory audit record. Raw payloads deliberately never enter transcripts. */
    private static final class AgentTaskRecord {
        final String taskId;
        String goalId = "";
        int goalTaskIndex = 0;
        final long startedAt = System.currentTimeMillis();
        final ArrayList<String> stepsSummary = new ArrayList<String>();
        final java.util.HashMap<String, Integer> toolCounts = new java.util.HashMap<String, Integer>();
        int steps;
        int mutationActions;
        String lastSignature = "";
        String status = "";
        String blockedReason;
        String endReason = "";
        String finalReply = "";
        boolean awaitingModel;
        boolean watchdogPrompted;
        boolean cancelled;
        boolean finished;
        AgentTaskRecord(String id) { taskId = id; }
        int getToolCount(String name) { Integer value = toolCounts.get(name); return value == null ? 0 : value; }
        void incrementTool(String name) { toolCounts.put(name, getToolCount(name) + 1); }
        void addStep(String name, JSONObject result) {
            String outcome = result.optBoolean("success") ? "成功" : (result.optBoolean("cancelled") ? "已取消" : "失敗");
            String detail = result.optString("message", result.optString("error", ""));
            stepsSummary.add(name + "：" + outcome + (detail.isEmpty() ? "" : "（" + detail + "）"));
        }
        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("taskId", taskId).put("goalId", goalId).put("goalTaskIndex", goalTaskIndex)
                        .put("startedAt", startedAt).put("steps", new JSONArray(stepsSummary))
                        .put("stepCount", steps).put("mutationActions", mutationActions)
                        .put("endReason", endReason).put("finalReply", finalReply).put("status", status);
            } catch (Exception ignored) {}
            return json;
        }
    }

    private void reportStage(String text) { stage = text; listener.onStatus(text); Log.d(TAG, text); }
    private synchronized void fail(String message, Throwable error) {
        if (!running) return;
        if (error != null) Log.e(TAG, message, error); else Log.e(TAG, message);
        running = false;
        interruptionHandler.removeCallbacks(clearInterruptedFallback);
        stopAudio(); listener.onStopped(message);
    }
    private void stopAudio() {
        audioPlaybackRunning = false;
        audioQueue.clear();
        if (usingOboeOutput) { NativeOboeOutput.stop(); usingOboeOutput = false; }
        try { if (audioPlaybackThread != null) audioPlaybackThread.interrupt(); } catch (Exception ignored) {}
        audioPlaybackThread = null;
        if (aecEffect != null) { try { aecEffect.release(); } catch (Exception ignored) {} aecEffect = null; }
        if (nsEffect != null) { try { nsEffect.release(); } catch (Exception ignored) {} nsEffect = null; }
        try { if (recorder != null) { recorder.stop(); recorder.release(); recorder = null; } } catch (Exception ignored) {}
        synchronized (playerLock) {
            try { if (player != null) { player.stop(); player.release(); player = null; } } catch (Exception ignored) {}
        }
    }
}
