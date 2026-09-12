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
    // 0073: AgentRuntimeV2 production authority is staged per action/package.
    private static final String TAG = "CrewNativeLive";
    interface Listener {
        void onStatus(String text);
        void onStopped(String reason);
        void onTranscript(String role, String text);
        void onSpeakingChanged(boolean speaking);
        void onMicrophoneLevel(double dbfs, double gateDbfs, boolean sending);
    }
    private final String apiKey;
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
    /**
     * A Gemini Live function-call may be re-delivered while its first copy is
     * still queued.  This set coalesces that transport-level duplicate.  It is
     * deliberately scoped by userIntentGeneration, so a later user command is
     * never suppressed by an earlier command's de-duplication state.
     */
    private final Set<String> inFlightToolSignatures = new HashSet<String>();
    private final java.util.HashMap<String, String> primaryToolCallSignatures = new java.util.HashMap<String, String>();
    private final java.util.HashMap<String, ArrayList<ToolResponseRecipient>> coalescedToolCallRecipients = new java.util.HashMap<String, ArrayList<ToolResponseRecipient>>();
    // Phone tasks routinely need several semantic actions plus model turns.
    // Observation/verification calls do not consume the mutation-action budget.
    private static final long AGENT_TASK_TIMEOUT_MS = 180_000L;
    private static final long AGENT_FINAL_RESPONSE_WAIT_MS = 12_000L;
    private static final int AGENT_FINAL_SPEECH_MAX_RETRIES = 2;
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
    // 0070: observational-only state projection. It must never gate execution.
    private final ShadowAgentRuntime shadowAgentRuntime = new ShadowAgentRuntime();
    // 0071: authoritative v2 action transaction runtime.
    private final AgentRuntimeV2 agentRuntimeV2 = new AgentRuntimeV2();
    private volatile ActionObservation latestActionObservation = ActionObservation.unavailable();
    private String authorizationTranscript = "";
    // Incremented for every finalized user utterance (and typed instruction).
    // Tool calls retain the generation that created them, preventing an old
    // model turn from mutating the phone after the user has changed their mind.
    private long userIntentGeneration = 0L;
    private MemoryRuleIndex memoryRuleIndex;
    private String lastMemoryDispatchKey = "";
    private long lastMemoryDispatchAt = 0L;
    // 0033 deterministic recorded shortcuts own their physical execution.
    private volatile boolean runtimeShortcutExecuting = false;
    private volatile long runtimeShortcutGuardUntil = 0L;
    // 0052: standalone "送出/發送/send" is owned directly by Runtime.
    private volatile boolean runtimeSendCurrentExecuting = false;
    private AudioIncidentRecorder audioIncidentRecorder;
    private volatile PendingCondition pendingCondition = null;
    private final Object pendingChoiceLock = new Object();
    private PendingUiChoice pendingUiChoice;
    private volatile boolean pendingChoiceExecuting = false;

    // 0046: Gemini Live may split toolCall/modelTurn/turnComplete across
    // different WebSocket frames. These flags describe the whole current
    // server model turn, not one handleJson() invocation.
    private boolean currentModelTurnHadToolCall = false;
    private boolean currentModelTurnProducedSpeech = false;
    // 0047: transcript text is not audible-output proof.
    private boolean currentModelTurnReceivedAudio = false;
    private volatile String lastAudioOutputState = "NONE";
    private long audioPcmBytesReceived = 0L;
    private long audioPcmBytesAccepted = 0L;

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
        this.voiceName = voiceName == null || voiceName.trim().isEmpty() ? AppConfig.DEFAULT_VOICE : voiceName.trim();
        this.noiseMode = "quiet".equals(noiseMode) || "noisy".equals(noiseMode) ? noiseMode : "auto";
        this.noiseSuppression = Math.max(0, Math.min(100, noiseSuppression));
        this.liveTone = liveTone == null ? "warm" : liveTone;
        this.customPrompt = customPrompt == null ? "" : customPrompt.trim();
        this.interruptionSensitivity = Math.max(0, Math.min(100, interruptionSensitivity));
        this.audioOutput = "media".equals(audioOutput) ? "media" : "call";
        this.listener = listener;
        shadowAgentRuntime.setListener(new AgentLedger.Listener() {
            @Override public void onEvent(AgentEvent event, AgentState state) {
                Log.d(TAG, "AgentLedger " + event.type + " => " + state.phase);
            }
        });
        agentRuntimeV2.setListener(new AgentLedger.Listener() {
            @Override public void onEvent(AgentEvent event, AgentState state) {
                Log.d(TAG, "AgentRuntimeV2 " + event.type + " => " + state.phase);
            }
        });
    }

    private ActionObservation toActionObservation(JSONObject screen) {
        if (screen == null || !screen.optBoolean("success", false)) {
            return ActionObservation.unavailable();
        }
        String focusedKey = "";
        String focusedRole = "";
        JSONArray elements = screen.optJSONArray("elements");
        if (elements != null) {
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.optJSONObject(i);
                if (element == null || !element.optBoolean("focused", false)) continue;
                focusedKey = element.optString("viewId", "") + "|"
                        + element.optString("semanticHint", "") + "|"
                        + element.optString("role", "");
                focusedRole = element.optString("role", "");
                break;
            }
        }
        return new ActionObservation(
                true,
                screen.optString("package", ""),
                screen.optString("fingerprint", ""),
                screen.optString("stableScreenKey", ""),
                focusedKey,
                focusedRole,
                elements == null ? 0 : elements.length(),
                System.currentTimeMillis());
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

        shadowAgentRuntime.onInterrupted("USER_CORRECTION");
        agentRuntimeV2.onInterrupted("USER_CORRECTION");

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

        correctionHandler.removeCallbacks(clearCorrectionWindow);
        correctionWindowActive = false;
        correctionWindowUntil = 0L;
        correctionTaskHint = "";
        correctionContextPendingInjection = false;
        reportStage("已打斷，等待下一句指令");
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
        shadowAgentRuntime.onTaskCancelled(task.taskId, task.endReason);
        agentRuntimeV2.onTaskCancelled(task.taskId, task.endReason);
        return true;
    }

    private void supersedeActiveAgentTaskForNewUserInstruction() {
        if (hasActiveAgentTask()) {
            cancelAgentTask("新使用者指令取代舊任務");
        }
    }

    /**
     * Start a new finalized user turn without throwing away useful short-term
     * task continuity. The newest turn is authoritative; rootGoal survives only
     * inside the existing 45-second conversation-goal window.
     */
    private void beginNewUserIntent(String userText) {
        // A finalized user instruction supersedes any incomplete model-turn
        // bookkeeping from the previous interaction.
        resetCurrentModelTurnState();
        long now = System.currentTimeMillis();
        boolean startNewCapsule = conversationGoalId.isEmpty()
                || now - conversationGoalTouchedAt < 0L
                || now - conversationGoalTouchedAt > CONVERSATION_GOAL_IDLE_MS;

        if (startNewCapsule) {
            conversationGoalId = "goal_" + now;
            conversationGoalTaskIndex = 0;
            conversationGoalHint = "";
            conversationGoalTouchedAt = now;
            workingContext.startNewGoal(userText);
            consecutiveNoProgress = 0;
            pendingCondition = null;
            lastCandidateApps.clear();
        } else {
            conversationGoalTouchedAt = now;
            workingContext.beginUserTurn(userText);
            // A new user utterance supersedes any old asynchronous wait, even
            // when it is a follow-up within the same task capsule.
            pendingCondition = null;
        }

        String shadowTaskId = "";
        synchronized (agentLock) {
            userIntentGeneration++;
            // Calls that have not started belong to the old utterance. An
            // executing call is additionally guarded by its generation below.
            pendingToolCalls.clear();
            inFlightToolSignatures.clear();
            primaryToolCallSignatures.clear();
            coalescedToolCallRecipients.clear();
            if (activeAgentTask != null) shadowTaskId = activeAgentTask.taskId;
        }

        shadowAgentRuntime.onUserIntent(
                userIntentGeneration, conversationGoalId, shadowTaskId, startNewCapsule);
        agentRuntimeV2.onUserIntent(
                userIntentGeneration, conversationGoalId, shadowTaskId, startNewCapsule);

        supersedeActiveAgentTaskForNewUserInstruction();
        Log.d(TAG, "新的使用者意圖：generation=" + userIntentGeneration
                + " capsule=" + (startNewCapsule ? "NEW" : "CONTINUE"));
    }

    private boolean isCurrentUserIntent(long generation) {
        synchronized (agentLock) { return generation == userIntentGeneration; }
    }

    boolean sendText(String text) {
        if (!running || webSocket == null || text == null || text.trim().isEmpty()) return false;
        try {
            String input = text.trim();
            if (audioIncidentRecorder != null) audioIncidentRecorder.markTypedInput(input);

            if (consumePendingUiChoiceInput(input)) return true;
            if (hasPendingUiChoice()) clearPendingUiChoiceSilently();

            beginNewUserIntent(input);
            userActionScope.updateFromUserText(input);
            if (tryHandleRuntimeSendCurrent(input)) {
                listener.onTranscript("你", input);
                return true;
            }
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
    // 0082: UI-selected presentation entry is explicit. Creation no longer
    // needs a fake welcome Deck merely to expose deck tools.
    private volatile String deckStartupMode = "";
    private volatile String deckStartupDeckId = "";
    private volatile boolean deckStartupDispatched = false;
    // 0054: the scheduled page turn is bound to the card that was actually narrated.
    // A stale callback may never advance a newer card.
    private volatile int deckAdvanceExpectedIndex = -1;
    private final Runnable deckAdvanceRunnable = new Runnable() {
        @Override public void run() {
            triggerDeckAutoAdvance();
        }
    };

    boolean isDeckAutoAdvanceActive() { return deckAutoAdvanceActive; }
    void setDeckAutoAdvanceActive(boolean active) { this.deckAutoAdvanceActive = active; if (!active) cancelDeckAutoAdvance(); }

    void configureDeckStartup(String mode, String deckId) {
        String normalized = mode == null ? "" : mode.trim();
        if (!"create".equals(normalized) && !"present".equals(normalized)) {
            normalized = "";
        }
        deckStartupMode = normalized;
        deckStartupDeckId = deckId == null ? "" : deckId.trim();
        deckStartupDispatched = false;
    }

    private boolean isDeckSessionMode() {
        return "create".equals(deckStartupMode)
                || "present".equals(deckStartupMode)
                || DeckRepository.hasActiveDeck();
    }

    private void dispatchDeckStartupIfNeeded() {
        if (!setupReady || deckStartupDispatched || deckStartupMode.isEmpty()) return;

        if ("create".equals(deckStartupMode)) {
            deckStartupDispatched = true;
            sendInternalAgentDirective(
                    "【AI 簡報建立入口】使用者剛剛主動選擇『AI 建立新簡報』。"
                    + "如果使用者還沒說主題，現在只用一句話問：想做什麼主題的簡報？"
                    + "不要要求 deck.json、檔案、資料夾或任何技術設定。"
                    + "使用者提供主題後，呼叫 create_ephemeral_deck 一次建立 3–8 頁，"
                    + "建立完成後直接以 Gemini 主講人的身分開始介紹第一頁。");
            return;
        }

        if ("present".equals(deckStartupMode)) {
            JSONObject card = DeckRepository.presentCard("");
            if (!card.optBoolean("success", false)) {
                deckStartupDispatched = true;
                sendInternalAgentDirective(
                        "【AI 簡報啟動失敗】無法取得目前簡報第一頁。"
                        + "請簡短告訴使用者簡報無法載入，不要猜測內容。");
                return;
            }

            deckStartupDispatched = true;
            deckAutoAdvanceActive = true;
            resetCurrentModelTurnState();
            sendInternalAgentDirective(
                    "【AI 簡報開始】使用者已選好簡報，第一頁現在已顯示。"
                    + "你是這場簡報的主講人。直接自然地開始介紹目前第一頁，"
                    + "不要再問要不要開始、不要呼叫 open_deck/advance_deck。"
                    + "Runtime 會在你的語音真正播放完畢後自動翻頁。"
                    + "目前第一頁完整資料：" + card.toString());
        }
    }

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
        deckAdvanceExpectedIndex = -1;
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
        resetCurrentModelTurnState();
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
            reportStage("🎙️ 已連線，直接說話");
            startAudio();
            dispatchDeckStartupIfNeeded();
            return;
        }

        // 0033-hotfix1:
        // Current Gemini Live API defines inputTranscription as the finalized,
        // authoritative transcript. interimInputTranscription is the speculative
        // streaming field. Do not concatenate finalized transcripts as fragments.
        //
        // Process Runtime shortcuts BEFORE same-frame tool calls. Transcription and
        // tool/model events have no guaranteed ordering; Runtime-owned shortcuts
        // must establish execution ownership before Gemini can mutate the phone.
        JSONObject server = response.optJSONObject("serverContent");
        if (server == null) server = response.optJSONObject("server_content");
        JSONObject inputTranscript = server == null ? null : server.optJSONObject("inputTranscription");
        if (inputTranscript == null && server != null) inputTranscript = server.optJSONObject("input_transcription");
        String completeUserInput = "";
        if (inputTranscript != null && !inputTranscript.optString("text").trim().isEmpty()) {
            completeUserInput = inputTranscript.optString("text").trim();

            if (audioIncidentRecorder != null) {
                audioIncidentRecorder.onVoiceTranscript(completeUserInput);
            }
            listener.onTranscript("你", completeUserInput);

            // A pending human choice continues the ORIGINAL user intent.
            if (consumePendingUiChoiceInput(completeUserInput)) return;

            // Any other utterance replaces the pending choice/task.
            if (hasPendingUiChoice()) clearPendingUiChoiceSilently();

            beginNewUserIntent(completeUserInput);

            authorizationTranscript = completeUserInput;
            if (authorizationTranscript.length() > 4096) {
                authorizationTranscript = authorizationTranscript.substring(0, 4096);
            }

            userActionScope.updateFromUserText(completeUserInput);
            if (tryHandleRuntimeSendCurrent(completeUserInput)) return;
            if (isStopAgentTaskPhrase(completeUserInput)) {
                cancelDeckAutoAdvance();
                cancelAgentTask("使用者語音停止任務");
            }
        }

        boolean responseHasToolCall = false;
        JSONObject toolCall = response.optJSONObject("toolCall");
        if (toolCall == null) toolCall = response.optJSONObject("tool_call");
        if (toolCall != null) {
            authorizationTranscript = "";
            JSONArray calls = toolCall.optJSONArray("functionCalls");
            if (calls == null) calls = toolCall.optJSONArray("function_calls");
            if (calls != null && calls.length() > 0) {
                responseHasToolCall = true;
                markCurrentModelTurnToolCall();
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
            resetCurrentModelTurnState();
            return;
        }
        // Finalized input was already processed before tool calls above.
        JSONObject outputTranscript = server.optJSONObject("outputTranscription");
        if (outputTranscript == null) outputTranscript = server.optJSONObject("output_transcription");
        if (outputTranscript != null && !outputTranscript.optString("text").isEmpty()
                && !runtimeSendCurrentExecuting
                && !shouldWithholdUnverifiedAgentReply()) {
            // Bubble text may arrive even when no PCM is played. Keep showing
            // it, but do not let text satisfy the final-speech contract.
            if (!responseHasToolCall) {
                Log.d(TAG, "0047 transcript received; waiting for Gemini PCM");
            }
            listener.onTranscript("Gemini", outputTranscript.optString("text"));
        }
        JSONObject turn = server.optJSONObject("modelTurn");
        if (turn == null) turn = server.optJSONObject("model_turn");
        if (turn != null) {
            authorizationTranscript = "";
            markAgentModelResponse();
            // Continuous camera/screen frames must not arrive while Gemini is
            // producing this answer, otherwise they can trigger a duplicate turn.
            visualHoldUntil = System.currentTimeMillis() + 1800;
            // A model sometimes treats an accepted click/type as the end of a
            // task.  Do not play that premature conclusion: Runtime needs one
            // explicit post-action screen observation before an agent answer.
            boolean withholdForVerification =
                    shouldWithholdUnverifiedAgentReply() || runtimeSendCurrentExecuting;
            if (!interruptedCurrentTurn && !withholdForVerification) {
                if (!aiSpeaking) {
                    aiSpeaking = true;
                    listener.onSpeakingChanged(true);
                }
                JSONArray parts = turn.optJSONArray("parts");
                if (parts != null) for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.getJSONObject(i);
                    JSONObject inline = part.optJSONObject("inlineData");
                    if (inline == null) inline = part.optJSONObject("inline_data");
                    if (inline != null && inline.optString("data").length() > 0) {
                        byte[] responsePcm = Base64.decode(inline.getString("data"), Base64.DEFAULT);
                        noteCurrentModelTurnAudioReceived(responsePcm.length);
                        boolean audioAccepted = enqueueAudio(responsePcm);
                        if (!responseHasToolCall && audioAccepted) {
                            markCurrentModelTurnSpeech();
                            markAgentUserVisibleReplyProduced();
                        }
                    }
                    if (part.optString("text").length() > 0) {
                        String modelText = part.optString("text");
                        if (!responseHasToolCall) {
                            appendAgentFinalText(modelText);
                        }
                        listener.onTranscript("Gemini", modelText);
                    }
                }
            } else if (withholdForVerification) {
                Log.d(TAG, "暫緩未驗證的 Agent 回覆，等待 inspect_ui 證據");
            }
        }
        if (server.optBoolean("turnComplete", server.optBoolean("turn_complete", false))) {
            // Capture whole-turn truth before consuming/resetting it. Only a
            // pure, audible narration turn is allowed to schedule Deck advance.
            boolean deckNarrationTurn = shouldAutoAdvanceDeckAfterCurrentTurn();
            boolean deckTurnWasInterrupted = interruptedCurrentTurn;

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
            if (shouldEvaluateAgentTaskAtTurnComplete()) {
                finishAgentTaskIfAwaitingModel();
            }
            if (deckNarrationTurn
                    && deckAutoAdvanceActive
                    && DeckRepository.hasActiveDeck()
                    && !deckTurnWasInterrupted
                    && !agentMuted) {
                scheduleDeckAutoAdvance();
            }

            // 0054: model-turn state is per turn, not per Live session.
            // Tool-only turns after a narration must not inherit "audio seen"
            // and accidentally schedule extra page turns.
            resetCurrentModelTurnState();
        }
    }

    /**
     * 0054: auto-advance only after one pure narration turn whose PCM was
     * actually accepted for playback. Tool turns must never inherit this state.
     */
    private boolean shouldAutoAdvanceDeckAfterCurrentTurn() {
        synchronized (agentLock) {
            return currentModelTurnProducedSpeech
                    && currentModelTurnReceivedAudio
                    && !currentModelTurnHadToolCall;
        }
    }

    /**
     * 0052: standalone send-only commands never depend on Gemini tool choice.
     * Runtime owns the physical SEND_CURRENT operation.
     */
    private boolean tryHandleRuntimeSendCurrent(String inputText) {
        if (!UserActionScope.isStandaloneCurrentScreenSendCommand(inputText)) {
            return false;
        }
        if (!userActionScope.canSend()) {
            return false;
        }

        final long generation;
        synchronized (agentLock) {
            generation = userIntentGeneration;
        }

        runtimeSendCurrentExecuting = true;
        runtimeShortcutGuardUntil = Long.MAX_VALUE;

        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject result;
                try {
                    result = sendTextToPhone(new JSONObject());
                } catch (Exception error) {
                    result = new JSONObject();
                    try {
                        result.put("success", false)
                                .put("stage", "RUNTIME")
                                .put("error", error.getMessage() == null
                                        ? "SEND_CURRENT_FAILED"
                                        : error.getMessage());
                    } catch (Exception ignored) {}
                }

                runtimeSendCurrentExecuting = false;
                runtimeShortcutGuardUntil = System.currentTimeMillis() + 1200L;

                if (!isCurrentUserIntent(generation)) {
                    return;
                }

                boolean success = result.optBoolean("success", false);
                String stage = result.optString("stage", success ? "DONE" : "UNKNOWN");
                String error = result.optString("error", "");

                try {
                    FloatingBubbleManager.getInstance(appContext).showCompactStatus(
                            success ? "已送出" : "訊息尚未送出",
                            success ? "" : stage + (error.isEmpty() ? "" : " · " + error));
                } catch (Exception ignored) {}

                workingContext.updateLastResult(success ? "STEP_OK" : "STEP_FAILED");

                try {
                    if (success) {
                        sendInternalAgentDirective(
                                "【Runtime Send 完成】目前輸入框已送出一次。"
                                + "不要再呼叫工具，只用一句很短的話告知使用者已送出。");
                    } else {
                        sendInternalAgentDirective(
                                "【Runtime Send 未完成】Runtime 沒有重送。stage="
                                + stage + (error.isEmpty() ? "" : " error=" + error)
                                + "。不要呼叫手機工具；只用一句很短的話告知使用者尚未送出。");
                    }
                } catch (Exception ignored) {}
            }
        }, "CrewRuntimeSendCurrent").start();

        return true;
    }

    private boolean isStopAgentTaskPhrase(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", "");
        return clean.contains("停止任務") || clean.contains("取消任務") || clean.contains("停止執行") || clean.contains("停止agent")
                || clean.contains("停止簡報") || clean.contains("暫停簡報") || clean.contains("不要翻頁") || clean.contains("先別翻頁") || clean.contains("關閉簡報");
    }

    /** 0033: Runtime matches; recorded plans execute without Gemini phone tools. */
    private boolean processMemoryRuleInput(String inputText) {
        try {
            if (appContext == null) return false;
            MemoryRuleStore store = new MemoryRuleStore(appContext);

            if (isMemoryRuleRequest(inputText)) {
                try {
                    FloatingBubbleManager.getInstance(appContext).showCompactStatus(
                            "快捷指令由 Runtime 管理",
                            "請點懸浮泡泡 → 紅色錄製按鈕，實際操作後再按一次完成");
                } catch (Exception ignored) {}
                sendInternalAgentDirective(
                        "【0033 Shortcut UI】使用者想建立/記住快捷指令。"
                        + "不要呼叫任何建立規則或儲存工具，也不要聲稱已儲存。"
                        + "只簡短告訴使用者：從懸浮泡泡按『錄製快捷指令』，完成操作後再按一次即可設定觸發句。");
                return true;
            }

            if (memoryRuleIndex != null) memoryRuleIndex.refresh();
            MemoryRuleIndex.Match matched = memoryRuleIndex == null
                    ? null : memoryRuleIndex.findBest(inputText);
            if (matched == null && memoryRuleIndex == null) {
                MemoryRuleStore.Rule exact = store.findExact(inputText);
                if (exact != null) {
                    matched = new MemoryRuleIndex.Match(exact, "EXACT", 1.0, exact.trigger);
                }
            }
            if (matched == null || matched.rule == null) {
                // Do not log or retain spoken content in release builds.  The
                // visible state makes a short shortcut miss diagnosable without
                // exposing a transcript in logcat.
                if (MemoryRuleIndex.looksLikeRecordedShortcut(inputText)) {
                    reportStage("已收到開啟指令，但未命中已學習快捷操作");
                    try {
                        FloatingBubbleManager.getInstance(appContext).showCompactStatus(
                                "快捷指令未命中",
                                "請確認觸發句；可在『已學習操作』查看或重新錄製");
                    } catch (Exception ignored) {}
                }
                return false;
            }

            MemoryRuleStore.Rule rule = matched.rule;
            String dispatchKey = TextMatch.caseFold(rule.id + "|" + matched.mode);
            long now = System.currentTimeMillis();
            if (dispatchKey.equals(lastMemoryDispatchKey)
                    && now - lastMemoryDispatchAt < 2500L) return true;
            lastMemoryDispatchKey = dispatchKey;
            lastMemoryDispatchAt = now;

            store.recordMatch(rule.id, matched.mode);
            if (memoryRuleIndex != null) memoryRuleIndex.refresh();

            if (ShortcutPlanStore.isPlanAction(rule.action)) {
                if (runtimeShortcutExecuting || ShortcutExecutionRuntime.isRunning()) {
                    reportStage("Shortcut 已在執行中，忽略重複觸發");
                    return true;
                }

                final String planId = ShortcutPlanStore.planIdFromAction(rule.action);
                runtimeShortcutExecuting = true;
                runtimeShortcutGuardUntil = Long.MAX_VALUE;
                reportStage("Runtime Shortcut 命中：「" + rule.trigger + "」 · " + matched.mode);
                try {
                    FloatingBubbleManager.getInstance(appContext).showCompactStatus(
                            "✓ Shortcut HIT",
                            rule.trigger + " · " + matched.mode);
                } catch (Exception ignored) {}
                sendInternalAgentDirective(
                        "【Runtime Shortcut】已由 Android Runtime 接管執行。"
                        + "你不得呼叫任何手機 mutation tool、不得重新規劃或重複操作；"
                        + "保持簡短並等待手機畫面結果。");

                ShortcutExecutionRuntime.executePlanAsync(
                        appContext,
                        planId,
                        new ShortcutExecutionRuntime.Callback() {
                            @Override public void onComplete(boolean success, String detail) {
                                runtimeShortcutExecuting = false;
                                runtimeShortcutGuardUntil = System.currentTimeMillis() + 1800L;
                                reportStage((success ? "Runtime Shortcut 完成：" : "Runtime Shortcut 失敗：")
                                        + detail);
                                try {
                                    FloatingBubbleManager.getInstance(appContext).showCompactStatus(
                                            success ? "✓ 快捷指令完成" : "✕ 快捷指令失敗",
                                            detail);
                                } catch (Exception ignored) {}
                            }
                        });
                return true;
            }

            // Simple App shortcuts deliberately bypass Gemini and accessibility
            // recording.  Own the same-frame tool calls before launching.
            if (AppLaunchShortcut.isAction(rule.action)) {
                runtimeShortcutExecuting = true;
                runtimeShortcutGuardUntil = Long.MAX_VALUE;
                reportStage("App 指令命中：「" + rule.trigger + "」 · " + matched.mode);
                try {
                    FloatingBubbleManager.getInstance(appContext).showCompactStatus(
                            "✓ App 指令命中", rule.trigger + " · " + matched.mode);
                } catch (Exception ignored) {}
                try {
                    String detail = AppLaunchShortcut.launch(appContext,
                            AppLaunchShortcut.packageNameFromAction(rule.action));
                    reportStage("App 指令完成：" + detail);
                    try {
                        FloatingBubbleManager.getInstance(appContext).showCompactStatus(
                                "✓ App 指令完成", detail);
                    } catch (Exception ignored) {}
                } catch (Exception error) {
                    String detail = error.getMessage() == null ? "無法開啟 App" : error.getMessage();
                    reportStage("App 指令失敗：" + detail);
                    try {
                        FloatingBubbleManager.getInstance(appContext).showCompactStatus(
                                "✕ App 指令失敗", detail);
                    } catch (Exception ignored) {}
                } finally {
                    runtimeShortcutExecuting = false;
                    runtimeShortcutGuardUntil = System.currentTimeMillis() + 1800L;
                }
                return true;
            }

            if (!MemoryRuleStore.containsProhibitedShortcutAction(rule.action)) {
                userActionScope.updateFromTrustedAction(rule.action);
            }
            reportStage("Legacy Shortcut 命中：「" + rule.trigger + "」 · " + matched.mode);
            sendInternalAgentDirective(
                    "【Legacy Shortcut 命中】Runtime 已確認觸發。現在要完成的任務是：「"
                    + rule.action + "」。依正常 Observe→Action→Verify 執行，仍遵守全部安全政策。");
            return false;
        } catch (Exception error) {
            Log.w(TAG, "Shortcut 處理失敗：" + error.getMessage());
            return false;
        }
    }

    private MemoryRuleStore memoryRuleStore() throws Exception {
        if (appContext == null) throw new Exception("App Context 不可用，無法保存 Memory Rule");
        return new MemoryRuleStore(appContext);
    }

    private JSONObject listMemoryRules() throws Exception {
        JSONArray rules = new JSONArray();
        for (MemoryRuleStore.Rule rule : memoryRuleStore().list()) {
            rules.put(new JSONObject().put("id", rule.id).put("trigger", rule.trigger)
                    .put("aliases", new JSONArray(rule.aliases)).put("action", rule.action)
                    .put("enabled", rule.enabled).put("triggerCount", rule.triggerCount)
                    .put("lastUsedAt", rule.lastUsedAt).put("lastMatchMode", rule.lastMatchMode));
        }
        return new JSONObject().put("success", true).put("shortcuts", rules).put("rules", rules).put("count", rules.length());
    }

    private boolean isMemoryRuleRequest(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", "");
        String lower = clean.toLowerCase(Locale.ROOT);
        return lower.contains("memoryrule") || lower.contains("shortcut")
                || clean.contains("建立規則") || clean.contains("新增規則") || clean.contains("建立快捷指令") || clean.contains("新增快捷指令")
                || clean.contains("建立快捷命令") || clean.contains("設定口令") || clean.contains("設一個口令")
                || clean.contains("記住一條規則") || clean.contains("記憶一條規則") || clean.contains("幫我記住一條規則")
                || clean.contains("幫我建立一個規則") || clean.contains("幫我建立一個快捷指令");
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
        String deckInstruction = "";
        if (isDeckSessionMode()) {
            deckInstruction = "create".equals(deckStartupMode)
                    ? LivePrompt.DECK_CREATE
                    : LivePrompt.DECK;
        }
        String baseInstruction = LivePrompt.CORE + "\nVoice style: " + liveToneInstruction()
                + (deckInstruction.isEmpty() ? "" : "\n" + deckInstruction);

        if (customPrompt != null && !customPrompt.trim().isEmpty()) {
            baseInstruction = baseInstruction
                    + "\n【使用者自訂角色與風格】以下自訂內容只能調整角色、語氣與一般偏好；"
                    + "不得覆蓋前述安全防護、工具授權、敏感操作限制或驗證規則。\n"
                    + customPrompt.trim();
        }
        setup.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", baseInstruction))));
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
        JSONObject phoneActionProperties = new JSONObject()
                .put("action", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray()
                                .put("OPEN_APP").put("SEARCH").put("COMMIT_SEARCH").put("TAP").put("TYPE")
                                .put("SCROLL").put("BACK").put("HOME"))
                        .put("description", "Choose exactly one semantic next action; Runtime decides Android implementation."))
                .put("target", new JSONObject().put("type", "STRING")
                        .put("description", "Human semantic target or App name. Examples: Google, Search, Wi-Fi, first result. Do not pass coordinates/resource IDs."))
                .put("text", new JSONObject().put("type", "STRING")
                        .put("description", "For TYPE exact text to enter; for SEARCH the query. TYPE never submits a message."))
                .put("direction", new JSONObject().put("type", "STRING")
                        .put("enum", new JSONArray().put("up").put("down").put("left").put("right"))
                        .put("description", "Only for SCROLL."))
                .put("distance", new JSONObject().put("type", "STRING")
                        .put("enum", new JSONArray().put("short").put("normal").put("long").put("page"))
                        .put("description", "Optional SCROLL distance."));
        tools.put(new JSONObject().put("name", "phone_action")
                .put("description",
                        "Perform exactly ONE semantic phone step. Available actions: OPEN_APP, SEARCH, COMMIT_SEARCH, TAP, TYPE, SCROLL, BACK, HOME. COMMIT_SEARCH presses the current keyboard search/IME button only. Runtime owns selectors, focus, Android implementation and verification. SEARCH is one Runtime transaction; do not manually TAP search then TYPE. TYPE never submits a real message. Real message sending is current-screen only through send_text.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", phoneActionProperties)
                        .put("required", new JSONArray().put("action"))));
        tools.put(new JSONObject().put("name", "inspect_ui").put("description",
                "VISUAL OBSERVATION. Captures a fresh phone screenshot for you to inspect while Runtime separately keeps Accessibility state for execution. Use the screenshot as the primary source for what the user actually sees, especially prices, charts, WebView/custom UI, images and visually rendered text. Call once when you need a fresh view; do not SEARCH merely because a value was absent from prior semantic tool text."));
        tools.put(new JSONObject().put("name", "wait").put("description",
                "Wait for a screen condition after an asynchronous action. Runtime polls and returns the latest state.")
                .put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject()
                        .put("condition", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("screen_change").put("element_appears").put("element_disappears"))
                                .put("description", "Condition to wait for (default screen_change)"))
                        .put("element_id", new JSONObject().put("type", "STRING")
                                .put("description", "Optional element id only when inspect_ui explicitly returned one for a wait condition."))
                        .put("timeout_ms", new JSONObject().put("type", "INTEGER")
                                .put("description", "Maximum wait milliseconds (default 5000, max 15000)")))));
        tools.put(new JSONObject().put("name", "send_text").put("description",
                "CURRENT SCREEN ONLY: use only when THIS turn contains new message text plus an explicit send verb. Pass the exact text once. Runtime performs TYPE then SEND_CURRENT and verifies one submit attempt. Standalone commands such as 送出/發送/send are intercepted directly by Runtime before model tool selection. Never search for or navigate to a recipient.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("text", new JSONObject().put("type", "STRING")
                                        .put("description", "Exact new message text from this user turn.")))
                        .put("required", new JSONArray().put("text"))));
        tools.put(new JSONObject().put("name", "schedule_reminder").put("description", "Set a countdown timer / reminder in seconds. When time is up, the assistant vibrates and announces the message.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("delay_seconds", new JSONObject().put("type", "NUMBER").put("description", "Delay in seconds, e.g. 300 for 5 minutes")).put("message", new JSONObject().put("type", "STRING").put("description", "Reminder text to speak when timer expires")).put("label", new JSONObject().put("type", "STRING").put("description", "Short label for the timer"))).put("required", new JSONArray().put("delay_seconds"))));
        tools.put(new JSONObject().put("name", "start_screen_monitor").put("description", "Start periodic background screen checks or wait until a specific condition/text appears on screen.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("interval_seconds", new JSONObject().put("type", "NUMBER").put("description", "Interval between checks in seconds (e.g. 60)")).put("duration_minutes", new JSONObject().put("type", "NUMBER").put("description", "Total monitoring duration in minutes (default 10)")).put("target_condition", new JSONObject().put("type", "STRING").put("description", "Optional text/word to look for on screen (e.g. '已送達', '完成')")).put("label", new JSONObject().put("type", "STRING").put("description", "Short task name"))).put("required", new JSONArray().put("interval_seconds"))));
        tools.put(new JSONObject().put("name", "list_active_schedules").put("description", "List all currently active timers, background screen monitors, and countdowns with their remaining time.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject())));
        tools.put(new JSONObject().put("name", "cancel_schedule").put("description", "Cancel one or all active timers/screen monitors.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("task_id", new JSONObject().put("type", "STRING").put("description", "Optional task ID to cancel, e.g. 'timer_1'")).put("label_hint", new JSONObject().put("type", "STRING").put("description", "Optional keyword/label of the timer to cancel")).put("cancel_all", new JSONObject().put("type", "BOOLEAN").put("description", "Set true to cancel all active timers and monitors")))));
        tools.put(new JSONObject().put("name", "take_screenshot").put("description", "Capture the phone screen ONLY when inspect_ui has no nodes (e.g. Canvas, Unity, WebGL, custom game UI) or user explicitly requests it."));
        tools.put(new JSONObject().put("name", "end_voice_session").put("description", "End the voice call only for an explicit call-ending command: '結束通話', '掛斷電話', or '退出語音助理'. Never infer this from '關閉', '退出', '再見', '先這樣', or a request to close an app, window, or feature."));
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
        return filterModelFacingTools(tools);
    }

    /** 0082: keep the Live model surface small and mode-specific. */
    private JSONArray filterModelFacingTools(JSONArray declared) {
        JSONArray exposed = new JSONArray();
        boolean deckMode = isDeckSessionMode();
        boolean createMode = "create".equals(deckStartupMode);
        boolean presentMode = "present".equals(deckStartupMode);

        for (int i = 0; i < declared.length(); i++) {
            JSONObject tool = declared.optJSONObject(i);
            if (tool == null) continue;
            String name = tool.optString("name", "");

            boolean allow;
            if (!deckMode) {
                allow = isNormalPhoneModelTool(name);
            } else if (createMode) {
                allow = "create_ephemeral_deck".equals(name)
                        || "end_voice_session".equals(name);
            } else if (presentMode) {
                allow = "end_voice_session".equals(name)
                        || isDeckPresentationModelTool(name);
            } else {
                allow = isNormalPhoneModelTool(name) || isDeckModelTool(name);
            }

            if (allow) exposed.put(tool);
        }

        Log.i(TAG, "0082 model tool surface: "
                + exposed.length()
                + (createMode ? " (deck create)"
                    : (presentMode ? " (deck presenter)"
                        : (deckMode ? " (legacy deck)" : " (normal phone)"))));
        return exposed;
    }

    private boolean isNormalPhoneModelTool(String name) {
        return "phone_action".equals(name)
                || "inspect_ui".equals(name)
                || "send_text".equals(name)
                || "end_voice_session".equals(name);
    }

    private boolean isDeckPresentationModelTool(String name) {
        return "get_deck_card".equals(name)
                || "present_deck_card".equals(name)
                || "list_deck_images".equals(name)
                || "attach_deck_image".equals(name)
                || "update_deck_card".equals(name)
                || "insert_deck_card".equals(name)
                || "remove_future_deck_card".equals(name);
    }

    private boolean isDeckModelTool(String name) {
        return "list_decks".equals(name)
                || "open_deck".equals(name)
                || "get_deck_card".equals(name)
                || "present_deck_card".equals(name)
                || "advance_deck".equals(name)
                || "create_ephemeral_deck".equals(name)
                || "list_deck_images".equals(name)
                || "attach_deck_image".equals(name)
                || "update_deck_card".equals(name)
                || "insert_deck_card".equals(name)
                || "remove_future_deck_card".equals(name);
    }


    private void executeToolAsync(final JSONObject call) {
        final String id = call.optString("id", "tool_" + System.nanoTime());
        synchronized (handledToolCalls) { if (!handledToolCalls.add(id)) return; }
        try {
            synchronized (agentLock) {
                final long generation = userIntentGeneration;
                final String signature = generation + "|" + buildIncomingToolSignature(call);
                // Gemini can emit the same function call twice in one streamed
                // response with distinct ids.  Coalesce it while it is pending
                // or executing; this is not a model-loop failure.
                if (!inFlightToolSignatures.add(signature)) {
                    ArrayList<ToolResponseRecipient> recipients = coalescedToolCallRecipients.get(signature);
                    if (recipients == null) {
                        recipients = new ArrayList<ToolResponseRecipient>();
                        coalescedToolCallRecipients.put(signature, recipients);
                    }
                    recipients.add(new ToolResponseRecipient(id, call.optString("name", "unknown")));
                    shadowAgentRuntime.onDuplicateIgnored(
                            id, call.optString("name", "unknown"), generation);
                    Log.d(TAG, "合併同輪重送工具呼叫：" + signature);
                    return;
                }
                primaryToolCallSignatures.put(id, signature);
                call.put("_crew_intent_generation", generation);
                call.put("_crew_inflight_signature", signature);
                pendingToolCalls.add(call);
                shadowAgentRuntime.onToolQueued(
                        id, call.optString("name", "unknown"), generation);
                agentRuntimeV2.onToolQueued(
                        id, call.optString("name", "unknown"), generation);
            }
        } catch (Exception error) {
            Log.w(TAG, "工具呼叫排程失敗", error);
            return;
        }
        drainToolQueue();
    }

    private String buildIncomingToolSignature(JSONObject call) {
        JSONObject args = call.optJSONObject("args");
        return call.optString("name", "unknown") + ":" + (args == null ? "{}" : args.toString());
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
                    synchronized (agentLock) {
                        inFlightToolSignatures.remove(call.optString("_crew_inflight_signature", ""));
                    }
                    activeToolThread = null;
                    synchronized (agentLock) { toolWorkerRunning = false; }
                    drainToolQueue();
                }
            }
        }, "crew-native-live-agent-tool").start();
    }

    private void executeSingleTool(final JSONObject call) {
        final String id = call.optString("id", "tool_" + System.nanoTime());
        final String requestedName = call.optString("name", "unknown");
        final JSONObject requestedArgs = call.optJSONObject("args") == null
                ? new JSONObject() : call.optJSONObject("args");
        final long callIntentGeneration = call.optLong("_crew_intent_generation", -1L);
        if (!isCurrentUserIntent(callIntentGeneration)) {
            // The user has already spoken a new command.  Do not execute a
            // queued mutation from the old turn, and do not create a new task
            // record that would inherit its repeat counter.
            shadowAgentRuntime.onStaleActionRejected(
                    id, requestedName, callIntentGeneration, userIntentGeneration);
            sendBlockedToolResponse(id, requestedName, "使用者已有新指令，舊操作已取消。");
            return;
        }
        // 0034: model-facing semantic action -> existing trusted Runtime tool.
        final SemanticPhoneAction.Resolution semantic;
        try {
            semantic = SemanticPhoneAction.resolve(requestedName, requestedArgs);
        } catch (Exception error) {
            JSONObject failure = new JSONObject();
            try {
                failure.put("success", false).put("stepResult", "STEP_FAILED")
                        .put("error", "SEMANTIC_ACTION_RESOLUTION_FAILED");
            } catch (Exception ignored) {}
            try { sendToolResponse(id, requestedName, failure); } catch (Exception ignored) {}
            return;
        }
        final String name = semantic.runtimeName;
        final JSONObject args = semantic.runtimeArgs;
        final boolean runtimeV2Enforced =
                isMutationTool(name)
                && AgentRuntimeRollout.shouldEnforce(name, latestActionObservation);

        AgentRuntimeV2.PreflightResult runtimePreflight = null;
        if (runtimeV2Enforced) {
            runtimePreflight = agentRuntimeV2.preflight(
                    callIntentGeneration,
                    userIntentGeneration,
                    id,
                    name,
                    buildAgentSignature(name, args),
                    latestActionObservation);
            if (!runtimePreflight.allowed()) {
                String code = runtimePreflight.code;
                if (runtimePreflight.decision == AgentRuntimeV2.PreflightDecision.REQUIRE_OBSERVE) {
                    JSONObject blocked = runtimeBlocked(
                            "OBSERVE_REQUIRED",
                            "上一個相同操作仍待驗證或剛失敗。先 inspect_ui 一次；不要原樣重做 mutation。");
                    try {
                        blocked.put("taskState", "IN_PROGRESS");
                        blocked.put("nextRequirement", "inspect_ui once");
                        sendToolResponse(id, requestedName, blocked);
                    } catch (Exception ignored) {}
                    return;
                }
                sendRuntimeV2Blocked(id, requestedName, code,
                        runtimePreflight.decision == AgentRuntimeV2.PreflightDecision.REJECT_STALE
                                ? "使用者已有較新的指令；舊操作已取消，不要重試。"
                                : "相同操作已在目前畫面完成；不要重複執行。", runtimePreflight);
                return;
            }
        }

        if ((runtimeShortcutExecuting
                || runtimeSendCurrentExecuting
                || System.currentTimeMillis() < runtimeShortcutGuardUntil)
                && isMutationTool(name)) {
            sendBlockedToolResponse(id, requestedName,
                    runtimeSendCurrentExecuting
                            ? "RUNTIME_SEND_OWNS_EXECUTION：Runtime 正在送出目前輸入框，禁止 Gemini 重複操作。"
                            : "RUNTIME_SHORTCUT_OWNS_EXECUTION：Runtime 正在執行確定性手機操作，禁止 Gemini 重複操作。");
            return;
        }

        if ((hasPendingUiChoice() || pendingChoiceExecuting)
                && isMutationTool(name)) {
            sendBlockedToolResponse(id, requestedName,
                    "WAITING_USER_CHOICE：Runtime 正在等待或執行使用者的搜尋結果選擇；禁止 Gemini 重複點擊。");
            return;
        }

        if (userActionScope.blocksNamedRecipientMessagingAction()
                && isMutationTool(name)) {
            sendBlockedToolResponse(id, requestedName,
                    "CURRENT_SCREEN_MESSAGING_ONLY：不支援『跟某人說／傳給某人』的自動找人或跨聊天室傳訊。請使用者先自行開到正確聊天室。");
            return;
        }

        // If THIS turn contains new text + send intent, TYPE is upgraded to
        // the simple TYPE -> SEND_CURRENT path. Standalone send-only turns are
        // intercepted before model tool selection.
        if (userActionScope.canSend() && "type_text".equals(name)) {
            sendBlockedToolResponse(id, requestedName,
                    "SEND_TEXT_REQUIRED：這句同時包含新訊息內容與送出要求，請使用 send_text(text=該訊息)。");
            return;
        }

        if (userActionScope.shouldBlockFurtherMessageMutation() && isMutationTool(name)) {
            sendBlockedToolResponse(id, requestedName,
                    "MESSAGE_TRANSACTION_ALREADY_HANDLED：本句明確傳送要求已完成一次原子送出交易；禁止再用 type/tap 重試。等待使用者的新指令。");
            return;
        }

        final AgentTaskRecord stabilityTask = peekActiveAgentTask();
        final JSONObject stabilityBlock = agentStabilityPreflight(stabilityTask, name, args);
        if (stabilityBlock != null && stabilityTask != null) {
            try {
                stabilityTask.addStep(name, stabilityBlock);
                sendToolResponse(id, requestedName, stabilityBlock);
                if (stabilityTask.blockedReason != null) {
                    requestAgentConclusion(stabilityTask, stabilityTask.blockedReason);
                } else {
                    stabilityTask.awaitingModel = true;
                    scheduleAgentResponseWatchdog(stabilityTask);
                    reportStage("Runtime 要求先重新觀察畫面，再改用不同方法");
                }
            } catch (Exception ignored) {}
            return;
        }

        final AgentTaskRecord task = beginAgentStep(name, args);
        if (task == null) {
            sendBlockedToolResponse(id, requestedName, "Agent 任務已停止，請以目前資訊作結論。");
            return;
        }
        if (!isCurrentUserIntent(callIntentGeneration) || task.cancelled || task.finished) {
            sendBlockedToolResponse(id, requestedName, "使用者已有新指令，舊操作已取消。");
            return;
        }
        if (task.blockedReason != null) {
            sendBlockedToolResponse(id, requestedName, task.blockedReason);
            requestAgentConclusion(task, task.blockedReason);
            return;
        }
        shadowAgentRuntime.onActionStarted(
                id,
                callIntentGeneration,
                conversationGoalId,
                task.taskId,
                requestedName,
                name,
                lastObservedScreenFingerprint,
                ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE);
        if (runtimeV2Enforced) {
            agentRuntimeV2.onActionStarted(
                    id, callIntentGeneration, conversationGoalId, task.taskId,
                    requestedName, name,
                    runtimePreflight == null ? "" : runtimePreflight.actionHash,
                    ActionExpectation.forRuntimeAction(name),
                    latestActionObservation);
        }
        if (isMutationTool(name) && audioIncidentRecorder != null) {
            audioIncidentRecorder.captureBeforeFirstMutation(task.taskId, name, args);
        }
        JSONObject result = new JSONObject();
        activeToolThread = Thread.currentThread();
        try {
            shadowAgentRuntime.onActionExecuted(id);
            if (SemanticPhoneAction.ERROR_TOOL.equals(name)) result = args;
            else if ("take_screenshot".equals(name)) result = captureAndSendScreen();
            else if ("inspect_ui".equals(name)) result = inspectUi(args);
            else if ("tap_element".equals(name)) result = tapSemanticElement(args);
            else if ("wait".equals(name)) result = waitForCondition(args);
            else if ("launch_app".equals(name)) result = launchApp(args);
            else if ("swipe_screen".equals(name)) result = swipe(args);
            else if ("tap_screen".equals(name)) result = tap(args);
            else if ("type_text".equals(name)) result = typeText(args);
            else if ("search_current_app".equals(name)) result = searchCurrentApp(args);
            else if ("commit_search".equals(name)) result = commitSearch();
            else if ("send_text".equals(name)) result = sendTextToPhone(args);
            else if ("press_key".equals(name)) result = pressKey(args);
            else if ("schedule_reminder".equals(name)) result = scheduleReminder(args);
            else if ("start_screen_monitor".equals(name)) result = startScreenMonitor(args);
            else if ("list_active_schedules".equals(name)) result = listSchedules();
            else if ("cancel_schedule".equals(name)) result = cancelSchedule(args);
            else if ("end_voice_session".equals(name)) {
                if (!userActionScope.consumeEndCallAuthorization()) {
                    JSONObject blocked = runtimeBlocked("END_CALL_NOT_AUTHORIZED", "請使用者明確說結束通話；關閉視窗或再見不代表掛斷。");
                    task.addStep(name, blocked);
                    sendToolResponse(id, requestedName, blocked);
                    task.awaitingModel = true;
                    scheduleAgentResponseWatchdog(task);
                    return;
                }
                result.put("success", true).put("message", "語音通話即將結束");
                sendToolResponse(id, requestedName, result);
                finishAgentTask(task, "通話結束", "");
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() { stop(); } }, 1200);
                return;
            }
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
                if (deckAutoAdvanceActive) {
                    // 0054: while automatic narration is active, page timing is
                    // Runtime-owned. Treat stray model advance calls as a safe no-op.
                    result = new JSONObject()
                            .put("success", true)
                            .put("noOp", true)
                            .put("runtimeOwned", true)
                            .put("currentIndex", DeckRepository.activeIndex())
                            .put("instruction",
                                    "自動簡報翻頁由 Runtime 控制。不要再次呼叫 advance_deck；"
                                    + "請只講解目前顯示的卡片，Runtime 會在音訊播放完畢後翻頁。");
                } else {
                    result = DeckRepository.advance();
                    if (result.optBoolean("success", false)) deckAutoAdvanceActive = true;
                }
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
            if (runtimeV2Enforced) {
                normalizeMutationContract(result);
            }
            if (isMutationTool(name)) {
                result.put("runtimeV2Enforced", runtimeV2Enforced)
                        .put("runtimeV2Rollout",
                                AgentRuntimeRollout.rolloutLabel(name, latestActionObservation));
            }
            if (semantic.semantic) {
                result.put("semanticAction", semantic.semanticAction)
                        .put("resolvedByRuntime", name);
            }
            if (runtimeV2Enforced) {
                ExecutionEvidence evidence = executionEvidenceFromResult(name, result);
                agentRuntimeV2.onActionExecuted(id, evidence);
                ActionVerificationResult verification = agentRuntimeV2.verifyAndRecord(
                        id, evidence, latestActionObservation);
                applyV2VerificationContract(result, verification);
            }
            updateTaskCompletionContract(task, name, result);
            updateAgentStabilityAfterResult(task, name, args, result);
            task.addStep(name, result);
            sendToolResponse(id, requestedName, result);
            if (task.blockedReason != null) {
                requestAgentConclusion(task, task.blockedReason);
            } else if (shouldSuspendAgentForUser(result)) {
                synchronized (agentLock) {
                    task.awaitingModel = false;
                    task.watchdogPrompted = false;
                    clearAgentResponseWatchdogLocked();
                    task.status = "等待使用者選擇搜尋結果";
                }
                reportStage(task.status);
            } else {
                task.awaitingModel = true;
                scheduleAgentResponseWatchdog(task);
                reportStage("Agent 第 " + task.steps + " / " + agentMaxSteps + " 步：已取得「" + name + "」結果，正在決定下一步");
            }
        } catch (Exception error) { reportStage("Agent 工具結果回灌失敗：" + error.getMessage()); }
    }

    private boolean shouldSuspendAgentForUser(JSONObject result) {
        if (result != null && "WAITING_USER".equals(
                result.optString("taskState", ""))) {
            return true;
        }
        return hasPendingUiChoice();
    }

    private void resumeAgentAfterUserChoice(String status) {
        AgentTaskRecord task = null;
        synchronized (agentLock) {
            if (activeAgentTask != null
                    && !activeAgentTask.finished
                    && !activeAgentTask.cancelled) {
                task = activeAgentTask;
                task.awaitingModel = true;
                task.watchdogPrompted = false;
                task.userVisibleReplyProducedSinceLastAction = false;
                task.finalSpeechRetryCount = 0;
                task.status = status == null ? "使用者已完成選擇" : status;
            }
        }
        if (task != null) {
            reportStage(task.status);
            scheduleAgentResponseWatchdog(task);
        }
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
                task.userVisibleReplyProducedSinceLastAction = false;
                task.finalSpeechRetryCount = 0;
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
                || "list_memory_rules".equals(name)
                || "list_decks".equals(name)
                || "get_deck_card".equals(name)
                || "list_deck_images".equals(name);
    }

    /**
     * Keep action execution separate from task completion. Runtime already
     * auto-observes every mutation; its compact fresh after view is valid
     * evidence. An explicit inspect_ui remains the fallback if that view is
     * unavailable or a failed action demands another observation.
     */
    private void updateTaskCompletionContract(AgentTaskRecord task, String name, JSONObject result) {
        if (task == null || result == null) return;
        synchronized (agentLock) {
            if (task.cancelled || task.finished) return;
            boolean succeeded = result.optBoolean("success", false)
                    || "STEP_OK".equals(result.optString("stepResult", ""));
            try {
                if (isMutationTool(name) && succeeded) {
                    JSONObject after = result.optJSONObject("after");
                    boolean freshAfter = after != null && after.optBoolean("fresh", false)
                            && "AUTO_AFTER_ACTION".equals(after.optString("source", ""));

                    String domainState = result.optString("taskState", "").trim();
                    boolean waitingUser = "WAITING_USER".equals(domainState);
                    boolean blocked = "BLOCKED".equals(domainState);

                    task.requiresPostActionInspection =
                            waitingUser || blocked ? false : !freshAfter;
                    task.postActionInspectionPrompted = false;

                    if (!result.has("actionStatus")) result.put("actionStatus", "EXECUTED");
                    if (!result.has("taskState")) {
                        result.put("taskState",
                                freshAfter ? "EVIDENCE_AVAILABLE" : "IN_PROGRESS");
                    }
                    if (!result.has("completionEvidence")) {
                        result.put("completionEvidence", freshAfter
                                ? "AUTO_AFTER_ACTION"
                                : "PENDING_POST_ACTION_INSPECTION");
                    }
                    if (!result.has("nextRequirement")) {
                        result.put("nextRequirement", freshAfter
                                ? "Use the fresh compact after state to choose the next action; STEP_OK is not whole-task completion."
                                : "Call inspect_ui once and use the actual post-action screen before concluding.");
                    }
                } else if ("inspect_ui".equals(name) && succeeded
                        && task.requiresPostActionInspection) {
                    task.requiresPostActionInspection = false;
                    task.postActionInspectionPrompted = false;
                    if (!result.has("taskState")) result.put("taskState", "EVIDENCE_AVAILABLE");
                    if (!result.has("completionEvidence")) {
                        result.put("completionEvidence", "CURRENT_SCREEN_INSPECTED");
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean isMutationTool(String name) {
        return "launch_app".equals(name)
                || "swipe_screen".equals(name)
                || "tap_element".equals(name)
                || "tap_screen".equals(name)
                || "type_text".equals(name)
                || "search_current_app".equals(name)
                || "commit_search".equals(name)
                || "send_text".equals(name)
                || "press_key".equals(name);
    }

    private AgentTaskRecord peekActiveAgentTask() {
        synchronized (agentLock) {
            return activeAgentTask == null || activeAgentTask.finished ? null : activeAgentTask;
        }
    }

    private JSONObject agentStabilityPreflight(AgentTaskRecord task, String name, JSONObject args) {
        if (task == null || task.finished || task.cancelled || !isMutationTool(name)) return null;
        synchronized (agentLock) {
            String code = "";
            String instruction = "";
            if (task.requireObservationAfterFailure || semanticObserveRequired) {
                code = "OBSERVE_REQUIRED_AFTER_FAILURE";
                instruction = "上一個操作失敗或驗證不足。先呼叫 inspect_ui 一次，再依最新畫面改用不同方法；不要直接重做 mutation。";
            } else {
                String signature = buildAgentSignature(name, args);
                if (!task.lastFailedMutationSignature.isEmpty()
                        && signature.equals(task.lastFailedMutationSignature)
                        && !task.failedMutationScreenFingerprint.isEmpty()
                        && task.failedMutationScreenFingerprint.equals(latestSemanticFingerprint)) {
                    code = "REPEAT_FAILED_ACTION_ON_UNCHANGED_SCREEN";
                    instruction = "這個完全相同的操作已在目前畫面失敗。禁止原樣重試；請換 selector、換工具、返回，或直接回報卡點。";
                }
            }
            if (code.isEmpty()) return null;

            task.stabilityBlocks++;
            if (task.stabilityBlocks >= 2) {
                task.blockedReason = "Runtime 已連續兩次阻止無效重試，停止本次 Agent loop；請向使用者簡短回報目前卡點。";
            }
            try {
                return new JSONObject()
                        .put("success", false)
                        .put("stepResult", "STEP_FAILED")
                        .put("blockedByRuntime", true)
                        .put("error", code)
                        .put("instruction", instruction);
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private void normalizeMutationContract(JSONObject result) {
        if (result == null) return;
        try {
            if (!result.has("stepResult")) {
                result.put("stepResult", result.optBoolean("success", false) ? "STEP_OK" : "STEP_FAILED");
            }
            if (!result.has("success")) {
                result.put("success", "STEP_OK".equals(result.optString("stepResult", "")));
            }
        } catch (Exception ignored) {}
    }

    private void updateAgentStabilityAfterResult(AgentTaskRecord task,
                                                 String name,
                                                 JSONObject args,
                                                 JSONObject result) {
        if (task == null || result == null) return;
        synchronized (agentLock) {
            if ("inspect_ui".equals(name) && result.optBoolean("success", false)) {
                task.requireObservationAfterFailure = false;
                task.stabilityBlocks = 0;
                String observed = result.optString("fingerprint", latestSemanticFingerprint);
                if (!task.failedMutationScreenFingerprint.isEmpty()
                        && !observed.isEmpty()
                        && !task.failedMutationScreenFingerprint.equals(observed)) {
                    task.lastFailedMutationSignature = "";
                    task.failedMutationScreenFingerprint = "";
                    task.consecutiveMutationFailures = 0;
                }
                return;
            }

            if (!isMutationTool(name)) return;

            boolean success = result.optBoolean("success", false)
                    || "STEP_OK".equals(result.optString("stepResult", ""));
            if (success) {
                task.requireObservationAfterFailure = false;
                task.lastFailedMutationSignature = "";
                task.failedMutationScreenFingerprint = "";
                task.consecutiveMutationFailures = 0;
                task.stabilityBlocks = 0;
                return;
            }

            if (result.optBoolean("cancelled", false)
                    || result.optBoolean("agentStopped", false)
                    || result.optBoolean("blockedByRuntime", false)
                    || result.has("policy")) {
                return;
            }

            task.lastFailedMutationSignature = buildAgentSignature(name, args);
            task.failedMutationScreenFingerprint = latestSemanticFingerprint;
            task.consecutiveMutationFailures++;

            if (!blocksOutcomeReconciliation(result)) {
                task.requireObservationAfterFailure = true;
            }

            if (task.consecutiveMutationFailures >= 3) {
                task.blockedReason = "連續 3 次手機操作失敗，Runtime 已停止繼續試錯；請回報目前畫面與卡點，不要再呼叫工具。";
            }
        }
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

    private void sendRuntimeV2Blocked(String id, String name, String code,
                                      String instruction, AgentRuntimeV2.PreflightResult preflight) {
        try {
            JSONObject blocked = runtimeBlocked(code, instruction);
            blocked.put("verificationStatus", "BLOCKED");
            blocked.put("runtimeV2", true);
            sendToolResponse(id, name, blocked);
        } catch (Exception ignored) {}
    }

    private ExecutionEvidence executionEvidenceFromResult(String name, JSONObject result) {
        boolean blocked = result != null && (result.optBoolean("blockedByRuntime", false)
                || result.has("policy"));
        boolean cancelled = result != null && result.optBoolean("cancelled", false);
        boolean runtimeVerified = result != null && (result.optBoolean("verified", false)
                || (("search_current_app".equals(name) || "commit_search".equals(name))
                    && result.optBoolean("resultsObserved", false)));
        if ("launch_app".equals(name) && result != null) {
            String launchedPackage = result.optString("package", "").trim();
            if (!launchedPackage.isEmpty()
                    && launchedPackage.equals(latestActionObservation.packageName)) {
                runtimeVerified = true;
            }
        }
        JSONObject verification = result == null ? null : result.optJSONObject("verification");
        if (verification != null) {
            String state = verification.optString("state", "");
            runtimeVerified = runtimeVerified || "VERIFIED".equals(state) || "LIKELY".equals(state);
        }
        boolean delayed = "launch_app".equals(name)
                || (("tap_screen".equals(name) || "tap_element".equals(name))
                    && SearchResultSelectionRuntime.MAPS_PACKAGE.equals(latestActionObservation.packageName));
        return new ExecutionEvidence(
                result != null && result.optBoolean("success", false),
                runtimeVerified,
                blocked,
                cancelled,
                delayed,
                result == null ? "NO_RESULT" : result.optString("error", ""));
    }

    private void applyV2VerificationContract(JSONObject result,
                                             ActionVerificationResult verification) {
        if (result == null || verification == null) return;
        try {
            result.put("verificationStatus", verification.status.name());
            result.put("verificationCode", verification.code);
            if (verification.committed()) {
                result.put("success", true).put("stepResult", "STEP_OK");
            } else if (verification.pending()) {
                result.put("success", true).put("stepResult", "STEP_PENDING")
                        .put("taskState", "IN_PROGRESS")
                        .put("verification", "PENDING")
                        .put("nextRequirement", "Call inspect_ui once before another mutation.");
            } else {
                result.put("success", false).put("stepResult", "STEP_FAILED");
            }
        } catch (Exception ignored) {}
    }

    private void requestAgentConclusion(AgentTaskRecord task, String reason) {
        task.awaitingModel = true;
        task.userVisibleReplyProducedSinceLastAction = false;
        task.finalSpeechRetryCount = 0;
        task.status = reason;
        reportStage(reason);
        sendInternalAgentDirective("【Agent 系統狀態】" + reason
                + " 不要再呼叫工具；請以目前已知的工具結果，向使用者給出清楚、簡短的最終結論。"
                + "這個 active task 不可靜默結束；必須輸出一個簡短 AUDIO 回覆。"
                + "不要說『抱歉』或『對不起』；直接說明已完成的部分與目前唯一卡點。");
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
                        sendInternalAgentDirective("【Agent 系統狀態】上一個工具結果已回傳。只看目前 model-facing status：DONE 代表上一步成功；WAIT 先 inspect_ui 看 fresh screenshot，不要重複操作；FAILED 換方法且不要重複同一動作；NEED_USER 只問必要選擇。只有真的完成或無替代方案時才作結論。");
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
        if (!running || webSocket == null || !deckAutoAdvanceActive
                || interruptedCurrentTurn || agentMuted) return;
        if (!DeckRepository.hasActiveDeck()) {
            deckAutoAdvanceActive = false;
            deckAdvanceExpectedIndex = -1;
            return;
        }

        // Bind this page turn to the card whose narration just completed.
        deckAdvanceExpectedIndex = DeckRepository.activeIndex();

        long remaining = Math.max(0, lastPlaybackActiveAt - System.currentTimeMillis());
        long delay = remaining + 650; // wait for the real audio queue to drain + natural pause
        deckAdvanceHandler.removeCallbacks(deckAdvanceRunnable);
        deckAdvanceHandler.postDelayed(deckAdvanceRunnable, delay);
        Log.d(TAG, "0054 排程簡報翻頁：cardIndex="
                + deckAdvanceExpectedIndex + " delay=" + delay + "ms");
    }

    private void triggerDeckAutoAdvance() {
        if (!running || webSocket == null || !deckAutoAdvanceActive
                || interruptedCurrentTurn || agentMuted) return;
        if (!DeckRepository.hasActiveDeck()) {
            deckAutoAdvanceActive = false;
            deckAdvanceExpectedIndex = -1;
            return;
        }

        int expectedIndex = deckAdvanceExpectedIndex;
        if (expectedIndex < 0) return;

        int actualIndex = DeckRepository.activeIndex();
        if (actualIndex != expectedIndex) {
            // A user action, tool call, or stale callback already changed the page.
            // Never advance a page based on narration for an older card.
            Log.w(TAG, "0054 忽略過期簡報翻頁：expected="
                    + expectedIndex + " actual=" + actualIndex);
            deckAdvanceExpectedIndex = -1;
            return;
        }

        long remaining = lastPlaybackActiveAt - System.currentTimeMillis();
        if (remaining > 100) {
            deckAdvanceHandler.removeCallbacks(deckAdvanceRunnable);
            deckAdvanceHandler.postDelayed(deckAdvanceRunnable, remaining + 450);
            return;
        }

        if (DeckRepository.hasNext()) {
            JSONObject advanced = DeckRepository.advanceFromIndex(expectedIndex);
            if (!advanced.optBoolean("success", false)) {
                Log.w(TAG, "0054 Runtime 翻頁未執行："
                        + advanced.optString("error", "UNKNOWN"));
                deckAdvanceExpectedIndex = -1;
                return;
            }

            int currentIndex = DeckRepository.activeIndex();
            int currentCardNum = currentIndex + 1;
            int total = DeckRepository.totalCards();
            deckAdvanceExpectedIndex = -1;

            String cardData = advanced.toString();
            if (cardData.length() > 7000) {
                cardData = cardData.substring(0, 7000);
            }

            Log.d(TAG, "0054 Runtime 已翻至第 " + currentCardNum + "/" + total + " 頁");
            reportStage("簡報導播：進入第 " + currentCardNum + "/" + total + " 頁…");

            // Start the next narration from a clean turn. Runtime already changed
            // the visible card, so Gemini only needs to narrate the supplied card.
            resetCurrentModelTurnState();
            sendInternalAgentDirective(
                    "【簡報 Runtime 已翻頁】目前畫面已由 Runtime 切到第 "
                    + currentCardNum + "/" + total + " 頁。"
                    + "以下是目前卡片資料：" + cardData
                    + "。只講解目前這一頁，不要呼叫 advance_deck 或 present_deck_card，"
                    + "不要提前切換畫面。自動翻頁由 Runtime 在這頁語音真正播放完畢後處理。");
        } else {
            deckAdvanceExpectedIndex = -1;
            deckAutoAdvanceActive = false;
            Log.d(TAG, "0054 自動簡報已抵達最後一張卡片");
            reportStage("簡報導播：全部卡片播報完畢，進行總結…");
            resetCurrentModelTurnState();
            sendInternalAgentDirective(
                    "【簡報導播系統】目前已在最後一張卡片，所有頁面都已播報完成。"
                    + "請不要再呼叫任何翻頁工具，只做一段簡短總結並作結。");
        }
    }

    private void cancelDeckAutoAdvance() {
        deckAdvanceHandler.removeCallbacks(deckAdvanceRunnable);
        deckAdvanceExpectedIndex = -1;
        deckAutoAdvanceActive = false;
    }

    private void appendAgentFinalText(String text) {
        synchronized (agentLock) {
            if (activeAgentTask != null && activeAgentTask.awaitingModel) {
                activeAgentTask.finalReply += text;
            }
        }
    }

    private void markCurrentModelTurnToolCall() {
        synchronized (agentLock) {
            currentModelTurnHadToolCall = true;
            // Any speech before a later tool call was intermediate, not final.
            currentModelTurnProducedSpeech = false;
        }
    }

    private void noteCurrentModelTurnAudioReceived(int bytes) {
        synchronized (agentLock) {
            currentModelTurnReceivedAudio = true;
            audioPcmBytesReceived += Math.max(0, bytes);
        }
        Log.d(TAG, "0047 Gemini PCM received: " + bytes + " bytes");
    }

    private void markCurrentModelTurnSpeech() {
        synchronized (agentLock) {
            currentModelTurnProducedSpeech = true;
        }
    }

    /**
     * Consume whole-turn state exactly at server turnComplete.
     *
     * A tool-call turn with no later visible speech must not be mistaken for
     * a silent final answer just because turnComplete arrived in another frame.
     */
    private boolean shouldEvaluateAgentTaskAtTurnComplete() {
        boolean hadToolCall;
        boolean producedSpeech;
        synchronized (agentLock) {
            hadToolCall = currentModelTurnHadToolCall;
            producedSpeech = currentModelTurnProducedSpeech;
            currentModelTurnHadToolCall = false;
            currentModelTurnProducedSpeech = false;
        }
        if (hadToolCall && !producedSpeech) {
            Log.d(TAG, "0046 tool-call turn complete; defer Agent completion");
            return false;
        }
        return true;
    }

    private void resetCurrentModelTurnState() {
        synchronized (agentLock) {
            currentModelTurnHadToolCall = false;
            currentModelTurnProducedSpeech = false;
            currentModelTurnReceivedAudio = false;
        }
    }

    private void markAgentUserVisibleReplyProduced() {
        synchronized (agentLock) {
            if (activeAgentTask != null
                    && activeAgentTask.awaitingModel
                    && !activeAgentTask.finished
                    && !activeAgentTask.cancelled) {
                activeAgentTask.userVisibleReplyProducedSinceLastAction = true;
                activeAgentTask.finalSpeechRetryCount = 0;
            }
        }
    }

    private boolean shouldWithholdUnverifiedAgentReply() {
        synchronized (agentLock) {
            return activeAgentTask != null && !activeAgentTask.finished
                    && !activeAgentTask.cancelled
                    && activeAgentTask.requiresPostActionInspection;
        }
    }

    private void finishAgentTaskIfAwaitingModel() {
        AgentTaskRecord task;
        synchronized (agentLock) { task = activeAgentTask; }
        if (task == null || !task.awaitingModel || task.finished) return;
        if (task.requiresPostActionInspection) {
            requestPostActionInspection(task);
            return;
        }
        if (!agentMuted && !task.userVisibleReplyProducedSinceLastAction) {
            requestFinalSpeechOrNextTool(task);
            return;
        }
        finishAgentTask(task,
                task.blockedReason == null ? "任務完成" : task.blockedReason,
                task.finalReply);
    }

    /**
     * 0045 Final Speech Contract.
     * Silent termination is rejected: either speak one short final result or
     * continue with exactly one next tool.
     */
    private void requestFinalSpeechOrNextTool(AgentTaskRecord task) {
        int attempt;
        synchronized (agentLock) {
            if (activeAgentTask != task || task.finished || task.cancelled
                    || !task.awaitingModel) return;

            if (task.finalSpeechRetryCount >= AGENT_FINAL_SPEECH_MAX_RETRIES) {
                task.status = "Agent 操作已結束，但 Gemini 未產生最終語音";
                reportStage(task.status);
                finishAgentTask(task, "最終語音未產生", task.finalReply);
                return;
            }

            task.finalSpeechRetryCount++;
            attempt = task.finalSpeechRetryCount;
            task.watchdogPrompted = false;
            clearAgentResponseWatchdogLocked();
            task.status = "等待最終語音或下一個必要動作（" + attempt
                    + "/" + AGENT_FINAL_SPEECH_MAX_RETRIES + "）";
        }

        reportStage(task.status + " · audio=" + lastAudioOutputState
                + " · pcmReceived=" + audioPcmBytesReceived
                + " · pcmAccepted=" + audioPcmBytesAccepted);
        sendInternalAgentDirective(
                "【FINAL TURN REQUIRED】上一個 active Agent turn 沒有產生使用者可聽見的最終回覆，"
                + "也沒有下一個工具動作。現在只能二選一："
                + "如果任務已完成，立刻用 AUDIO 說一句簡短結果，且不要再呼叫工具；"
                + "如果任務尚未完成，保持安靜並只呼叫一個下一步工具。"
                + "不得再次空白結束，也不要敘述 Runtime 中間步驟。");
        scheduleAgentResponseWatchdog(task);
    }

    private void requestPostActionInspection(AgentTaskRecord task) {
        synchronized (agentLock) {
            if (activeAgentTask != task || task.finished || task.cancelled
                    || !task.requiresPostActionInspection || task.postActionInspectionPrompted) return;
            task.postActionInspectionPrompted = true;
            task.awaitingModel = true;
            task.status = "正在驗證上一個操作的實際畫面";
        }
        reportStage(task.status);
        sendInternalAgentDirective("【Runtime 必要驗證】上一個手機操作只代表動作已執行，尚未證明任務完成。現在必須呼叫 inspect_ui；Runtime 會送一張 fresh screenshot。直接看最新畫面決定下一步；在取得該證據前，不要對使用者作答或作結論。");
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
        if (UserActionScope.looksLikeSendTarget(tapMeta)) {
            // 0078: keep Send Runtime-owned, but tolerate a weak model choosing
            // TAP("Send") instead of the dedicated SEND_CURRENT path.
            //
            // This does NOT bypass authorization:
            // sendTextToPhone() still requires the latest user turn to explicitly
            // authorize send, consumes that authorization, sends at most once,
            // and uses the verified current-composer transaction.
            if (userActionScope.shouldBlockFurtherMessageMutation()) {
                return new JSONObject()
                        .put("success", true)
                        .put("action", "SEND_CURRENT")
                        .put("sendMode", "ALREADY_HANDLED")
                        .put("remappedFrom", "TAP_SEND_CONTROL")
                        .put("stepResult", "STEP_OK")
                        .put("instruction",
                                "本輪訊息送出 transaction 已經處理過；不要再次點擊或重送。");
            }

            JSONObject routedSend = sendTextToPhone(new JSONObject());
            routedSend.put("remappedFrom", "TAP_SEND_CONTROL");
            if (routedSend.optBoolean("success", false)) {
                routedSend.put("instruction",
                        "TAP Send 已由 Runtime 轉成單次 SEND_CURRENT 並完成；不要再呼叫 Send/TAP。");
            }
            return routedSend;
        }
        if (!pendingChoiceExecuting
                && userActionScope.shouldBlockTapForSearch(tapMeta, label.isEmpty() && id.isEmpty())) {
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
            return new JSONObject()
                    .put("success", false)
                    .put("stepResult", "STEP_FAILED")
                    .put("error", "UI_TARGET_NOT_FOUND")
                    .put("instruction",
                            "找不到指定點擊目標。這不是使用者消歧義事件；不要顯示選擇卡，請依最新畫面改用不同方法。");
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

    /**
     * 0037: only a committed Google Maps search with explicit result-selection
     * intent may enter the human-choice path.
     */
    private JSONObject resolveCommittedSearchSelection(
            String query, JSONObject observed) throws Exception {
        if (!userActionScope.shouldSelectSearchResult()) return observed;

        JSONObject analysis = null;
        long[] delays = new long[]{0L, 300L, 550L, 850L};
        for (int i = 0; i < delays.length; i++) {
            if (delays[i] > 0L) {
                try { Thread.sleep(delays[i]); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                analysis = helperPost("/search_result_candidates",
                        new JSONObject().put("query", query == null ? "" : query));
            } catch (Exception error) {
                analysis = new JSONObject()
                        .put("success", true)
                        .put("state", "WAITING_RESULTS")
                        .put("reason", "BRIDGE_ERROR");
            }
            String state = analysis.optString("state", "");
            if ("READY".equals(state) || "UNSUPPORTED".equals(state)
                    || "UNAVAILABLE".equals(state)) break;
        }

        if (analysis == null) return observed;

        String state = analysis.optString("state", "");
        if ("UNSUPPORTED".equals(state)) {
            return observed.put("searchSelectionRuntime", "UNSUPPORTED_FOR_PACKAGE");
        }

        if (!"READY".equals(state)) {
            return observed
                    .put("searchSelection", "WAITING_RESULTS")
                    .put("taskState", "IN_PROGRESS")
                    .put("completionEvidence", "SEARCH_COMMITTED_RESULTS_NOT_READY")
                    .put("nextRequirement",
                            "等待實際搜尋結果出現；不要把 autocomplete suggestion 當結果，也不要因 TAP 失敗顯示選擇卡。")
                    .put("instruction",
                            "Runtime 尚未看到可信的 Maps 結果列。可以等待畫面更新；不要重複搜尋或盲點座標。");
        }

        JSONArray rawOptions = analysis.optJSONArray("options");
        int count = rawOptions == null ? 0 : rawOptions.length();
        if (count <= 0) {
            return observed
                    .put("searchSelection", "WAITING_RESULTS")
                    .put("taskState", "IN_PROGRESS");
        }

        if (count == 1) {
            JSONObject only = rawOptions.optJSONObject(0);
            boolean strong = only != null
                    && (only.optBoolean("exactMatch", false)
                        || only.optBoolean("strongMatch", false));
            if (!strong) {
                return observed
                        .put("searchSelection", "RESULT_NOT_CONFIDENT_ENOUGH")
                        .put("taskState", "BLOCKED")
                        .put("candidate",
                                only == null ? "" : only.optString("label", ""))
                        .put("instruction",
                                "只找到一個結果列，但名稱與查詢不夠吻合；Runtime 不會猜。請簡短請使用者確認或說更完整名稱。");
            }

            String elementId = only.optString("elementId", "");
            String label = only.optString("label", "");
            JSONObject beforeSelection = readSemanticScreenQuietly();
            String beforeFingerprint = beforeSelection == null
                    ? "" : beforeSelection.optString("fingerprint", "");

            JSONObject selected = tapSemanticElement(
                    new JSONObject().put("element_id", elementId));

            boolean dispatched = selected.optBoolean("success", false);
            if (dispatched) {
                userActionScope.markSearchResultSelectionDispatched(label);
            }
            boolean opened = dispatched && verifySearchResultOpened(
                    query, elementId, label, beforeFingerprint, selected);
            if (opened) {
                userActionScope.markSearchResultSelected(label);
            }

            selected.put("searchSelection", opened
                            ? "RESULT_OPEN_VERIFIED"
                            : (dispatched ? "SELECTION_DISPATCHED" : "SELECTION_FAILED"))
                    .put("selectedSearchResult", label)
                    .put("continuation", userActionScope.searchContinuation())
                    .put("taskState", dispatched ? "IN_PROGRESS" : "BLOCKED")
                    .put("instruction",
                            opened
                            ? "Runtime 已驗證唯一搜尋結果頁確實開啟。可以依最新畫面繼續 continuation。"
                            : (dispatched
                                ? "搜尋結果點擊已送出，但尚未證明結果頁開啟。不要重新搜尋；依目前畫面確認後再繼續。"
                                : "唯一結果無法安全點開；停止重試並回報卡點。"));
            return selected;
        }

        ArrayList<PendingUiChoice.Option> options =
                new ArrayList<PendingUiChoice.Option>();
        for (int i = 0; i < rawOptions.length() && options.size() < 4; i++) {
            JSONObject option = rawOptions.optJSONObject(i);
            if (option == null) continue;
            String elementId = option.optString("elementId", "");
            String label = option.optString("label", "").trim();
            if (elementId.isEmpty() || label.isEmpty()) continue;
            options.add(new PendingUiChoice.Option(
                    elementId,
                    label,
                    "search_result",
                    option.optString("rowSignature", "")));
        }

        if (options.size() < 2) {
            return observed
                    .put("searchSelection", "RESULTS_NOT_AMBIGUOUS")
                    .put("taskState", "IN_PROGRESS");
        }

        String pkg = analysis.optString("package",
                SearchResultSelectionRuntime.MAPS_PACKAGE);
        String fingerprint = "";
        try {
            JSONObject screen = readSemanticScreenQuietly();
            if (screen != null) fingerprint = screen.optString("fingerprint", "");
        } catch (Exception ignored) {}

        final PendingUiChoice pending = new PendingUiChoice(
                userIntentGeneration,
                pkg,
                query,
                userActionScope.searchContinuation(),
                fingerprint,
                options);
        synchronized (pendingChoiceLock) {
            pendingUiChoice = pending;
            pendingChoiceExecuting = false;
        }
        workingContext.setPendingTask("WAITING_USER_CHOICE");

        FloatingBubbleManager.getInstance(appContext).showPendingChoices(
                "選擇搜尋結果",
                options,
                new FloatingBubbleManager.PendingChoiceCallback() {
                    @Override public void onChoice(String elementId) {
                        NativeLiveService.selectPendingUiChoice(elementId);
                    }
                    @Override public void onCancel() {
                        NativeLiveService.cancelPendingUiChoice();
                    }
                });

        JSONArray compact = new JSONArray();
        for (int i = 0; i < options.size(); i++) {
            compact.put((i + 1) + ". " + options.get(i).label);
        }

        return observed
                .put("success", true)
                .put("stepResult", "STEP_OK")
                .put("taskState", "WAITING_USER")
                .put("searchSelection", "USER_CHOICE_PENDING")
                .put("choices", compact)
                .put("instruction",
                        "已顯示「選擇搜尋結果」。等待使用者點選或說第一個/第二個/結果名稱；等待期間禁止任何手機 mutation。");
    }

    private boolean verifySearchResultOpened(
            String query,
            String elementId,
            String label,
            String beforeFingerprint,
            JSONObject tapResult) {
        if (tapResult == null || !tapResult.optBoolean("success", false)) return false;

        boolean changed = tapResult.optBoolean("screenChanged", false);
        String verification = tapResult.optString("verification", "");
        if ("PENDING".equals(verification) || !changed) {
            try {
                Thread.sleep(420L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        JSONObject latest = readSemanticScreenQuietly();
        if (latest == null || !latest.optBoolean("success", false)) return false;
        if (!SearchResultSelectionRuntime.MAPS_PACKAGE.equals(
                latest.optString("package", ""))) return false;

        String latestFingerprint = latest.optString("fingerprint", "");
        if (!beforeFingerprint.isEmpty() && !latestFingerprint.isEmpty()
                && !beforeFingerprint.equals(latestFingerprint)) {
            changed = true;
        }
        if (!changed) return false;

        if (containsElement(latest.optJSONArray("elements"), elementId)) return false;

        try {
            JSONObject analysis = helperPost(
                    "/search_result_candidates",
                    new JSONObject().put("query", query == null ? "" : query));
            if ("READY".equals(analysis.optString("state", ""))) {
                JSONArray options = analysis.optJSONArray("options");
                String wanted = normalizeSearchLabel(label);
                if (options != null && !wanted.isEmpty()) {
                    for (int i = 0; i < options.length(); i++) {
                        JSONObject option = options.optJSONObject(i);
                        if (option == null) continue;
                        String candidate = normalizeSearchLabel(
                                option.optString("label", ""));
                        if (wanted.equals(candidate)) return false;
                    }
                }
            }
        } catch (Exception ignored) {}

        latestSemanticFingerprint = latestFingerprint;
        workingContext.observe(
                latest.optString("package", ""),
                latestFingerprint,
                latest.optString("stableScreenKey", ""));
        return true;
    }

    private String normalizeSearchLabel(String value) {
        return TextMatch.caseFold(value == null ? "" : value)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()\\-_/]+", "")
                .trim();
    }

    private boolean hasPendingUiChoice() {
        synchronized (pendingChoiceLock) {
            if (pendingUiChoice == null) return false;
            if (pendingUiChoice.expired()) {
                pendingUiChoice = null;
                try {
                    FloatingBubbleManager.getInstance(appContext).hidePendingChoices();
                } catch (Exception ignored) {}
                workingContext.setPendingTask("");
                return false;
            }
            return true;
        }
    }

    private void clearPendingUiChoiceSilently() {
        synchronized (pendingChoiceLock) {
            pendingUiChoice = null;
        }
        try {
            FloatingBubbleManager.getInstance(appContext).hidePendingChoices();
        } catch (Exception ignored) {}
        workingContext.setPendingTask("");
    }

    private boolean consumePendingUiChoiceInput(String utterance) {
        PendingUiChoice pending;
        synchronized (pendingChoiceLock) {
            pending = pendingUiChoice;
        }
        if (pending == null) return false;
        if (pending.expired()) {
            clearPendingUiChoiceSilently();
            return false;
        }
        if (pending.isCancel(utterance)) {
            cancelPendingUiChoice("使用者取消");
            return true;
        }
        PendingUiChoice.Option option = pending.resolveVoice(utterance);
        return option != null && selectPendingUiChoice(option.elementId);
    }

    boolean selectPendingUiChoice(final String elementId) {
        final PendingUiChoice pending;
        final PendingUiChoice.Option chosen;
        synchronized (pendingChoiceLock) {
            pending = pendingUiChoice;
            if (pending == null || pending.expired()) {
                pendingUiChoice = null;
                return false;
            }
            PendingUiChoice.Option found = null;
            for (PendingUiChoice.Option option : pending.options) {
                if (option.elementId.equals(elementId)) {
                    found = option;
                    break;
                }
            }
            if (found == null) return false;
            chosen = found;
            pendingUiChoice = null;
            pendingChoiceExecuting = true;
        }

        FloatingBubbleManager.getInstance(appContext).hidePendingChoices();
        workingContext.setPendingTask("SELECTING_SEARCH_RESULT");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (pending.taskGeneration >= 0L
                            && !isCurrentUserIntent(pending.taskGeneration)) {
                        sendInternalAgentDirective(
                                "【Runtime 選擇已失效】使用者已有新指令，沒有點擊舊搜尋結果。");
                        return;
                    }

                    JSONObject screen = readSemanticScreenQuietly();
                    String currentPackage =
                            screen == null ? "" : screen.optString("package", "");
                    if (!pending.packageName.isEmpty()
                            && !pending.packageName.equals(currentPackage)) {
                        sendInternalAgentDirective(
                                "【Runtime 選擇已失效】目前 App 已改變，沒有點擊舊搜尋結果。");
                        return;
                    }

                    String beforeFingerprint =
                            screen == null ? "" : screen.optString("fingerprint", "");
                    JSONObject result = tapSemanticElement(
                            new JSONObject().put("element_id", chosen.elementId));

                    boolean dispatched = result.optBoolean("success", false);
                    if (dispatched) {
                        userActionScope.markSearchResultSelectionDispatched(chosen.label);
                    }
                    boolean opened = dispatched && verifySearchResultOpened(
                            pending.query,
                            chosen.elementId,
                            chosen.label,
                            beforeFingerprint,
                            result);
                    if (opened) {
                        userActionScope.markSearchResultSelected(chosen.label);
                    }

                    workingContext.setPendingTask(
                            dispatched && !opened ? "VERIFY_SEARCH_RESULT_OPEN" : "");
                    String continuation = pending.continuation.isEmpty()
                            ? "CONTINUE"
                            : pending.continuation;
                    JSONObject latestAfter = result.optJSONObject("after");
                    String afterSummary = latestAfter == null
                            ? "{}" : latestAfter.toString();
                    if (afterSummary.length() > 2600) {
                        afterSummary = afterSummary.substring(0, 2600);
                    }
                    resumeAgentAfterUserChoice(opened
                            ? "搜尋結果頁已驗證開啟"
                            : (dispatched
                                ? "搜尋結果點擊已送出，等待確認結果頁"
                                : "搜尋結果點擊未成功"));
                    sendInternalAgentDirective(
                            opened
                            ? "【Runtime 搜尋結果已驗證】使用者選了「"
                                + chosen.label + "」；continuation=" + continuation
                                + "；latestAfter=" + afterSummary
                                + "。結果頁已證明開啟，依目前畫面繼續；不要重新搜尋。"
                            : "【Runtime 搜尋結果點擊狀態】使用者選了「"
                                + chosen.label + "」。點擊="
                                + (dispatched ? "已送出但未證明結果頁開啟" : "未成功")
                                + "；latestAfter=" + afterSummary
                                + "。不要重新 SEARCH/TYPE；若目前畫面可確認結果頁，依畫面繼續，否則回報卡點。");
                } catch (Exception ignored) {
                    workingContext.setPendingTask("");
                    sendInternalAgentDirective(
                            "【Runtime 搜尋結果選擇失敗】沒有重複點擊；請依目前畫面回報卡點。");
                } finally {
                    pendingChoiceExecuting = false;
                }
            }
        }, "CrewPendingUiChoice").start();
        return true;
    }

    void cancelPendingUiChoice(String reason) {
        synchronized (pendingChoiceLock) {
            pendingUiChoice = null;
        }
        pendingChoiceExecuting = false;
        FloatingBubbleManager.getInstance(appContext).hidePendingChoices();
        workingContext.setPendingTask("");
        resumeAgentAfterUserChoice("使用者取消搜尋結果選擇");
        sendInternalAgentDirective(
                "【使用者取消搜尋結果選擇】"
                + (reason == null ? "" : reason)
                + "；停止目前結果選擇，不要繼續點擊。");
    }

    private JSONObject tapSemanticElement(JSONObject args) throws Exception {
        String elementId = args == null ? "" : args.optString("element_id", "").trim();
        if (elementId.isEmpty()) return new JSONObject().put("success", false).put("error", "MISSING_ELEMENT_ID");

        String elementMeta = semanticElementMeta(elementId);
        if (UserActionScope.looksLikeSendTarget(elementMeta)) {
            if (userActionScope.shouldBlockFurtherMessageMutation()) {
                return new JSONObject()
                        .put("success", true)
                        .put("action", "SEND_CURRENT")
                        .put("sendMode", "ALREADY_HANDLED")
                        .put("remappedFrom", "TAP_SEND_CONTROL")
                        .put("stepResult", "STEP_OK")
                        .put("instruction", "本輪訊息送出 transaction 已經處理過；不要再次點擊或重送。");
            }
            JSONObject routedSend = sendTextToPhone(new JSONObject());
            routedSend.put("remappedFrom", "TAP_SEND_CONTROL");
            if (routedSend.optBoolean("success", false)) {
                routedSend.put("instruction", "TAP Send 已由 Runtime 轉成單次 SEND_CURRENT 並完成；不要再呼叫 Send/TAP。");
            }
            return routedSend;
        }
        if (!pendingChoiceExecuting
                && userActionScope.shouldBlockTapForSearch(elementMeta, elementMeta.isEmpty())) {
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
                workingContext.observe(last.optString("package", ""), fp,
                        last.optString("stableScreenKey", ""));
                workingContext.setPendingTask("");
                pendingCondition = null;

                JSONObject compact = ModelScreenView.compact(last, "WAIT_CONDITION");
                try {
                    compact.put("conditionMet", true)
                           .put("condition", type.name())
                           .put("runtimeContext", workingContext.toModelJson());
                } catch (Exception ignored) {}
                return compact;
            }
        }

        JSONObject raw = last == null ? new JSONObject() : last;
        pendingCondition = null;
        workingContext.setPendingTask("");

        JSONObject out = ModelScreenView.compact(raw, "WAIT_TIMEOUT");
        try {
            out.put("success", true)
               .put("conditionMet", false)
               .put("condition", type.name())
               .put("timeout", true)
               .put("runtimeContext", workingContext.toModelJson());
        } catch (Exception ignored) {}
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
        if ("commit_search".equals(actionName)) return 700L;
        if ("search_current_app".equals(actionName)) return 520L;
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

        /*
         * Maps renders much of its navigation/search transition outside the
         * accessibility tree. A gesture can therefore be accepted visually
         * while both the bridge callback and the semantic fingerprint still
         * say "no change" for a short period. Do not teach the Live model that
         * this is an error: it otherwise announces failure after the user has
         * already seen the route/result open.
         *
         * This is deliberately narrow. Policy blocks, missing targets and all
         * other apps keep the normal strict STEP_FAILED contract.
         */
        final boolean mapsTapAwaitingVerification = !executionSuccess
                && !reconciliationBlocked
                && ("tap_screen".equals(actionName) || "tap_element".equals(actionName))
                && after != null
                && after.optBoolean("success", false)
                && SearchResultSelectionRuntime.MAPS_PACKAGE.equals(
                        after.optString("package", ""));
        final boolean finalSuccess = executionSuccess
                || (!reconciliationBlocked && changed)
                || mapsTapAwaitingVerification;

            if (after != null && after.optBoolean("success", false)) {
                String fp = after.optString("fingerprint", "");
                latestSemanticFingerprint = fp;
                latestActionObservation = toActionObservation(after);
                agentRuntimeV2.onScreenObserved(latestActionObservation);
                agentRuntimeV2.reverifyPending(latestActionObservation);
                shadowAgentRuntime.onScreenObserved(
                        fp,
                        after.optString("stableScreenKey", ""),
                        after.optString("package", ""));
                semanticObserveRequired = false;
                workingContext.observe(after.optString("package", ""), fp, after.optString("stableScreenKey", ""));
            } else if (!executionSuccess) {
                // A successful Android action must not be blocked merely
                // because the target app is between accessibility frames.
                semanticObserveRequired = true;
            } else {
                semanticObserveRequired = false;
        }

        // Keep the model-facing contract deliberately small and authoritative.
        try {
            actionResult.put("success", finalSuccess)
                    .put("stepResult", finalSuccess ? "STEP_OK" : "STEP_FAILED")
                    .put("screenChanged", changed);

            if (after != null && after.optBoolean("success", false)) {
                actionResult.put("after", ModelScreenView.compact(after, "AUTO_AFTER_ACTION"));
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
                if (mapsTapAwaitingVerification) {
                    actionResult.put("verification", "PENDING")
                            .put("message", "地圖操作已送出，畫面仍在更新；不要宣告失敗，請依最新畫面繼續確認。");
                    Log.i(TAG, "Maps gesture accepted as STEP_OK while verification is pending: " + actionName);
                } else if (!executionSuccess) {
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

    /**
     * 0080 Visual Observe.
     *
     * Accessibility remains Runtime's structural source for execution and
     * verification. Gemini receives a fresh screenshot so it can understand
     * what the user actually sees (prices, charts, WebView/custom UI, etc.).
     * The compact semantic view is retained internally only as fallback/debug.
     */
    private JSONObject inspectUi(JSONObject args) throws Exception {
        JSONObject semantic = helperGet("/semantic_screen");
        if (semantic == null) semantic = new JSONObject();

        if (semantic.optBoolean("success", false)) {
            latestSemanticFingerprint = semantic.optString("fingerprint", "");
            latestActionObservation = toActionObservation(semantic);
            agentRuntimeV2.onScreenObserved(latestActionObservation);
            agentRuntimeV2.reverifyPending(latestActionObservation);
            shadowAgentRuntime.onScreenObserved(
                    latestSemanticFingerprint,
                    semantic.optString("stableScreenKey", ""),
                    semantic.optString("package", ""));
            semanticObserveRequired = false;
            workingContext.observe(
                    semantic.optString("package", ""),
                    latestSemanticFingerprint,
                    semantic.optString("stableScreenKey", ""));
        }

        JSONObject out = new JSONObject();
        out.put("success", semantic.optBoolean("success", false))
                .put("visualSent", false)
                .put("semanticFallback",
                        ModelScreenView.compact(semantic, "EXPLICIT_INSPECT"));

        if (semantic.has("package")) {
            out.put("package", semantic.optString("package", ""));
        }
        if (semantic.has("fingerprint")) {
            out.put("fingerprint", semantic.optString("fingerprint", ""));
        }
        if (semantic.has("stableScreenKey")) {
            out.put("stableScreenKey", semantic.optString("stableScreenKey", ""));
        }

        if (semanticScreenContainsSensitiveElement(semantic)) {
            out.put("success", true)
                    .put("visualBlocked", "SENSITIVE_SCREEN")
                    .put("message",
                            "目前畫面含敏感輸入，未傳送截圖；使用已遮蔽的語意畫面作為 fallback。");
            return out;
        }

        JSONObject visual;
        try {
            visual = captureAndSendScreen();
        } catch (Exception error) {
            visual = new JSONObject()
                    .put("success", false)
                    .put("error",
                            error.getMessage() == null
                                    ? "VISUAL_CAPTURE_FAILED"
                                    : error.getMessage());
        }

        if (visual.optBoolean("success", false)) {
            out.put("success", true)
                    .put("visualSent", true)
                    .put("visualSource", "FRESH_SCREENSHOT")
                    .put("message",
                            "最新手機畫面已傳送給模型；直接以畫面作為主要視覺證據。");
        } else {
            out.put("visualSent", false)
                    .put("visualError",
                            visual.optString("error", "VISUAL_CAPTURE_FAILED"))
                    .put("message",
                            "截圖不可用；改用 Runtime 的語意畫面 fallback。");
        }

        return out;
    }

    private boolean semanticScreenContainsSensitiveElement(JSONObject semantic) {
        if (semantic == null) return false;
        JSONArray elements = semantic.optJSONArray("elements");
        if (elements == null) return false;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element != null && element.optBoolean("sensitive", false)) {
                return true;
            }
        }
        return false;
    }

    private JSONObject inspectUiLegacy() throws Exception {
        JSONObject raw = helperGet("/screen_state");
        if (!raw.optBoolean("success")) return raw;
        String fingerprint = raw.optString("fingerprint", "");
        if (!fingerprint.isEmpty()) {
            lastObservedScreenFingerprint = fingerprint;
            shadowAgentRuntime.onScreenObserved(fingerprint, "", raw.optString("package", ""));
        }
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
                if (!afterFingerprint.isEmpty()) {
                    lastObservedScreenFingerprint = afterFingerprint;
                    latestActionObservation = toActionObservation(raw);
                    agentRuntimeV2.onScreenObserved(latestActionObservation);
                    shadowAgentRuntime.onScreenObserved(afterFingerprint, "", currentPkg);
                }
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
                    .put("error", prompt.toString().trim()).put("instruction", "只詢問使用者要開第幾個；回答後再呼叫 phone_action(action=OPEN_APP,target=使用者選的序號)。");
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



    private void authenticateLocalBridge(HttpURLConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("LOCAL_BRIDGE_CONNECTION_REQUIRED");
        }
        if (appContext == null) {
            throw new IllegalStateException("LOCAL_BRIDGE_CONTEXT_REQUIRED");
        }
        String token = AppConfig.getLocalBridgeToken(appContext);
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("LOCAL_BRIDGE_TOKEN_UNAVAILABLE");
        }
        connection.setRequestProperty("X-Crew-Bridge-Token", token);
    }

    private JSONObject helperGet(String endpoint) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:8766" + endpoint).openConnection();
            activeToolConnection = connection;
            connection.setRequestMethod("GET");
            authenticateLocalBridge(connection);
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
        // Weak Live models occasionally choose TYPE even though the latest
        // utterance clearly asks to send.  Upgrade that call to the one safe
        // Runtime-owned transaction instead of permitting TYPE -> TAP guessing.
        if (userActionScope.canSend()) return sendTextToPhone(args);
        if (userActionScope.shouldBlockAdditionalTextEntry()) {
            return runtimeBlocked("SEARCH_SCOPE_ADDITIONAL_TEXT_NOT_AUTHORIZED",
                    "搜尋查詢已輸入；最新任務沒有授權進入聊天室或再輸入訊息。");
        }

        // Older/weak Live turns may still choose TYPE for a search request.
        // Never let that write into a random editable field: use the same
        // Runtime-owned search transaction as the semantic SEARCH action.
        if (userActionScope.shouldAutoCommitSearch()) return searchCurrentApp(args);

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

        // 0036 Search Transaction:
        // Query entry is not the end of a search. If the latest user intent is
        // actually SEARCH, Runtime commits the focused search field itself so
        // a weak model does not need another tool call for the keyboard Search.
        if (reply.optBoolean("success", false) && userActionScope.shouldAutoCommitSearch()) {
            userActionScope.markSearchQueryEntered();

            JSONObject commit;
            try {
                commit = helperPost("/commit_search", new JSONObject());
            } catch (Exception error) {
                commit = new JSONObject().put("success", false)
                        .put("action", "SEARCH_COMMIT")
                        .put("error", "SEARCH_COMMIT_BRIDGE_FAILED");
            }

            if (commit.optBoolean("success", false)) {
                workingContext.recordAction("search_commit", "submitted");
                JSONObject observed = autoObserveAfterMutation(commit, "search_commit");
                boolean searchOnlyBoundary = userActionScope.markSearchCommitted();

                observed.put("searchTransaction", "COMMITTED")
                        .put("typed", true)
                        .put("committed", true)
                        .put("searchCommitMethod", commit.optString("method", "RUNTIME"));

                if (userActionScope.shouldSelectSearchResult()) {
                    return resolveCommittedSearchSelection(text, observed);
                }

                if (searchOnlyBoundary) {
                    observed.put("taskBoundary", "SEARCH_RESULTS_ONLY");
                    observed.put("instruction",
                            "Runtime 已提交搜尋。最新任務只要求搜尋：使用 fresh after 回報結果並停止；"
                            + "不要再按搜尋鍵、不要任意打開另一個結果/群組/聊天室，也不要傳訊息。");
                }
                return observed;
            }

            // Text entry succeeded but the search itself did not. Keep the task
            // explicitly IN_PROGRESS instead of treating TYPE as whole-task completion.
            workingContext.recordAction("search_commit", "failed");
            JSONObject observed = autoObserveAfterMutation(reply, "type_text");
            observed.put("searchTransaction", "PENDING_COMMIT")
                    .put("searchCommitError",
                            commit.optString("error", "SEARCH_COMMIT_FAILED"))
                    .put("taskState", "IN_PROGRESS")
                    .put("completionEvidence", "SEARCH_QUERY_TYPED_NOT_COMMITTED")
                    .put("nextRequirement",
                            "搜尋文字已輸入但尚未提交；只可使用明確 Search/Go/Enter 提交控制，或回報 Runtime 無法提交。")
                    .put("instruction",
                            "不要把 autocomplete suggestion 當成已完成搜尋，也不要因為搜尋而進聊天室或傳訊息。");
            return observed;
        }

        return autoObserveAfterMutation(reply, "type_text");
    }

    private JSONObject searchCurrentApp(JSONObject args) throws Exception {
        String text = args == null ? "" : args.optString("text", "").trim();
        if (text.isEmpty()) return new JSONObject().put("success", false)
                .put("error", "EMPTY_SEARCH_QUERY");

        // A trustworthy result has already been opened for this same spoken
        // request. Weak models often repeat SEARCH after seeing a fresh Maps
        // page; never let that erase the successful state with a new query.
        if (userActionScope.hasSelectedSearchResult()) {
            return new JSONObject()
                    .put("success", true)
                    .put("action", "APP_SEARCH")
                    .put("searchTransaction", "RESULT_ALREADY_SELECTED")
                    .put("selectedSearchResult", userActionScope.selectedSearchResult())
                    .put("continuation", userActionScope.searchContinuation())
                    .put("taskState", "IN_PROGRESS")
                    .put("instruction",
                            "Runtime 已成功選定搜尋結果；禁止重新搜尋或再次輸入查詢。"
                            + "請依目前 Maps 畫面執行 continuation（例如導航）。");
        }

        if (userActionScope.hasDispatchedSearchResultSelection()) {
            return new JSONObject()
                    .put("success", true)
                    .put("action", "APP_SEARCH")
                    .put("searchTransaction", "RESULT_SELECTION_PENDING_VERIFICATION")
                    .put("candidate", userActionScope.dispatchedSearchResult())
                    .put("taskState", "IN_PROGRESS")
                    .put("instruction",
                            "搜尋結果點擊已送出但尚未由 Runtime 證明結果頁已開啟。"
                            + "禁止重新 SEARCH/TYPE；請依目前畫面確認或繼續觀察。");
        }

        JSONObject reply = helperPost("/search_in_app", new JSONObject().put("query", text));
        workingContext.recordAction("app_search",
                reply.optBoolean("success", false) ? "submitted" : "failed");
        JSONObject observed = autoObserveAfterMutation(reply, "search_current_app");
        if (!reply.optBoolean("success", false)) {
            observed.put("instruction",
                    "Runtime 沒有確認文字輸入成功；請依 after 最新畫面改用不同方法，不要宣稱已搜尋。");
            return observed;
        }

        // Only now can Runtime truthfully say the query entered a proven
        // search field. Completion requires query-excluding result-surface
        // evidence from AppSearchRuntime.
        userActionScope.markSearchQueryEntered();

        boolean resultsObserved = reply.optBoolean("resultsObserved", false);
        boolean commitDispatched = reply.optBoolean("commitDispatched", false);
        String searchState = reply.optString("state", "QUERY_ENTERED");

        observed.put("searchTransaction", searchState)
                .put("typed", true)
                .put("commitDispatched", commitDispatched)
                .put("committed", resultsObserved)
                .put("resultsObserved", resultsObserved)
                .put("searchCommitMethod",
                        reply.optString("commitMethod", "NONE"));

        if (resultsObserved) {
            userActionScope.markSearchCommitted();
            observed.put("taskState", "EVIDENCE_AVAILABLE")
                    .put("completionEvidence",
                            reply.optString("resultEvidence",
                                    "SEARCH_RESULT_SURFACE_OBSERVED"));
            if (userActionScope.shouldSelectSearchResult()) {
                return resolveCommittedSearchSelection(text, observed);
            }
            return observed;
        }

        if (commitDispatched) {
            return observed
                    .put("searchTransaction", "PENDING_RESULTS")
                    .put("taskState", "IN_PROGRESS")
                    .put("completionEvidence",
                            "SEARCH_COMMIT_DISPATCHED_RESULTS_NOT_CONFIRMED")
                    .put("nextRequirement",
                            "等待搜尋結果畫面變化；不要再次送出 Search/Enter。")
                    .put("instruction",
                            "Runtime 已送出搜尋提交，但尚未觀察到結果內容。請先 wait(screen_change) 或依 fresh after 觀察；不要重複 SEARCH/COMMIT_SEARCH，也不要盲點結果。");
        }

        return observed
                .put("searchTransaction", "QUERY_ENTERED")
                .put("taskState", "IN_PROGRESS")
                .put("completionEvidence", "SEARCH_QUERY_TYPED_NO_RESULT_EVIDENCE")
                .put("instruction",
                        "搜尋文字已確認輸入，但尚未觀察到結果且沒有可用提交鍵。依 fresh after 判斷 live-filter 結果；若沒有，回報目前卡點，不要宣稱搜尋完成。");
    }

    private JSONObject sendTextToPhone(JSONObject args) throws Exception {
        String text = args == null ? "" : args.optString("text", "");

        if (userActionScope.blocksNamedRecipientMessagingAction()) {
            return runtimeBlocked("CURRENT_SCREEN_MESSAGING_ONLY",
                    "目前只支援對當前畫面已開啟的輸入框操作；不支援自動尋找或驗證收件人。");
        }
        if (!userActionScope.canSend()) {
            return runtimeBlocked("CURRENT_SCREEN_SEND_NOT_AUTHORIZED",
                    "最新一句必須明確要求送出；TYPE 本身不代表送出。");
        }

        userActionScope.markMessageTransactionHandled();
        userActionScope.consumeSendAuthorization();

        // 0052 active messaging path is intentionally simple:
        // optional TYPE(text) -> SEND_CURRENT.
        if (!text.isEmpty()) {
            JSONObject typed = helperPost(
                    "/type",
                    new JSONObject().put("text", text));
            workingContext.recordAction(
                    "type_for_send",
                    typed.optBoolean("success", false) ? "submitted" : "failed");

            if (!typed.optBoolean("success", false)) {
                JSONObject failure = new JSONObject()
                        .put("success", false)
                        .put("action", "SEND_CURRENT")
                        .put("stage", "TYPE")
                        .put("error", typed.optString("error", "TYPE_FAILED"))
                        .put("textLength", text.length())
                        .put("sendMode", "TYPE_THEN_SEND_CURRENT");
                workingContext.recordAction("send_current", "failed");
                return failure;
            }
        }

        JSONObject reply = helperPost("/send_current", new JSONObject());
        JSONObject sendVerification = reply.optJSONObject("verification");
        Log.i(TAG, "RuntimeSend SEND_CURRENT_RESULT success="
                + reply.optBoolean("success", false)
                + " stage=" + reply.optString("stage", "")
                + " submitMethod=" + reply.optString("submitMethod", "")
                + " verification=" + (sendVerification == null ? "" : sendVerification.optString("state", ""))
                + " composerCleared=" + (sendVerification != null && sendVerification.optBoolean("composerCleared", false))
                + " conversationChanged=" + (sendVerification != null && sendVerification.optBoolean("conversationChanged", false))
                + " matchingMessageAppeared=" + (sendVerification != null && sendVerification.optBoolean("matchingMessageAppeared", false))
                + " error=" + reply.optString("error", ""));
        reply.put("sendMode",
                text.isEmpty() ? "CURRENT_COMPOSER" : "TYPE_THEN_SEND_CURRENT");
        if (!text.isEmpty()) {
            reply.put("textLength", text.length());
        }

        if (!reply.optBoolean("success", false)) {
            String stage = reply.optString("stage", "UNKNOWN");
            String detail = reply.optString("error", "SEND_FAILED");
            reportStage("訊息未送出：" + stage + " · " + detail);
            reply.put("instruction",
                    "Runtime 沒有確認送出；不要重送，等待使用者下一個指令。");
        }

        workingContext.recordAction(
                "send_current",
                reply.optBoolean("success", false) ? "submitted" : "failed");
        return reply;
    }

    private JSONObject pressKey(JSONObject args) throws Exception {
        String key = args.optString("key", "").toUpperCase();
        if (!("HOME".equals(key) || "BACK".equals(key) || "RECENTS".equals(key) || "NOTIFICATIONS".equals(key) || "QUICK_SETTINGS".equals(key) || "POWER_DIALOG".equals(key))) return new JSONObject().put("success", false).put("error", "不支援的系統按鍵");
        JSONObject reply = helperPost("/key", new JSONObject().put("key", key));
        workingContext.recordAction("key:" + key, reply.optBoolean("success", false) ? "submitted" : "failed");
        return autoObserveAfterMutation(reply, "press_key");
    }

    /** Explicitly commits the currently focused search field via the IME key. */
    private JSONObject commitSearch() throws Exception {
        JSONObject reply = helperPost("/commit_search", new JSONObject());
        workingContext.recordAction("search_commit",
                reply.optBoolean("success", false) ? "submitted" : "failed");
        if (!reply.optBoolean("success", false)) {
            reply.put("instruction",
                    "目前沒有可確認的搜尋輸入框或搜尋鍵；不要改點搜尋結果，請先回到搜尋欄。" );
        } else if (!reply.optBoolean("resultsObserved", false)) {
            reply.put("taskState", "IN_PROGRESS")
                    .put("completionEvidence",
                            "SEARCH_COMMIT_DISPATCHED_RESULTS_NOT_CONFIRMED")
                    .put("instruction",
                            "Search/Enter 已送出，但結果尚未被 Runtime 觀察到。請等待畫面變化；不要再次提交搜尋。");
        }
        return autoObserveAfterMutation(reply, "commit_search");
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
            authenticateLocalBridge(connection);
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
        boolean shadowSuccess = result != null && result.optBoolean("success", false);
        String shadowCode = result == null ? "NO_RESULT" : result.optString("error", "");
        if (shadowCode.isEmpty() && result != null) {
            shadowCode = result.optString("stepResult", shadowSuccess ? "OK" : "FAILED");
        }
        String shadowFingerprint = result == null ? "" : result.optString("fingerprint", "");
        shadowAgentRuntime.onToolResult(
                id, name, shadowSuccess, shadowCode, shadowFingerprint);
        synchronized (agentLock) {
            if (activeAgentTask != null) {
                AgentTaskRecord task = activeAgentTask;
                long remainingMs = Math.max(0, AGENT_TASK_TIMEOUT_MS - (System.currentTimeMillis() - task.startedAt));
                result.put("agentState", new JSONObject().put("taskId", task.taskId)
                        .put("remainingSteps", Math.max(0, agentMaxSteps - task.steps))
                        .put("remainingTimeMs", remainingMs)
                        .put("canContinue", !task.cancelled && !task.finished && task.blockedReason == null
                                && task.steps < agentMaxSteps && remainingMs > 0));
                // Gemini Live can generate an audible acknowledgement for every
                // function response.  That turns a recoverable retry into a
                // stream of "sorry" messages on weaker voice models.  Make the
                // runtime contract explicit on every in-progress turn instead
                // of asking the model to infer it from success/error wording.
                if (!task.cancelled && !task.finished && task.blockedReason == null
                        && task.steps < agentMaxSteps && remainingMs > 0) {
                    result.put("speechPolicy",
                            "CONTINUE_SILENT_OR_FINISH_SPOKEN: If another action is needed, stay silent and call exactly one next tool. If current evidence completes the user's request, call no more tools and give exactly one short spoken final result. Never end an active task silently.");
                }
            }
        }
        if (DeckRepository.hasActiveDeck() && (name.contains("deck"))) {
            result.put("modeInstructions", LivePrompt.DECK);
        }

        // 0079: keep the complete Runtime result for logs/task history, but give
        // the weak Live model a tiny, stable phone-control contract.
        final JSONObject modelResult =
                ModelToolResponseAdapter.forModel(name, result);

        JSONArray responses = new JSONArray();
        responses.put(new JSONObject().put("response", new JSONObject().put("result", modelResult)).put("id", id).put("name", name));
        synchronized (agentLock) {
            String signature = primaryToolCallSignatures.remove(id);
            if (signature != null) {
                ArrayList<ToolResponseRecipient> duplicates = coalescedToolCallRecipients.remove(signature);
                if (duplicates != null) {
                    for (ToolResponseRecipient duplicate : duplicates) {
                        responses.put(new JSONObject().put("response", new JSONObject().put("result", modelResult))
                                .put("id", duplicate.id).put("name", duplicate.name));
                    }
                }
            }
        }
        if (webSocket == null || !webSocket.send(new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", responses)).toString())) {
            throw new Exception("工具結果無法傳回 Gemini");
        }
    }

    private static final class ToolResponseRecipient {
        final String id;
        final String name;
        ToolResponseRecipient(String id, String name) { this.id = id; this.name = name; }
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
            lastAudioOutputState = "AUDIOTRACK_WRITE_FAILED:" + written;
            Log.w(TAG, "AudioTrack 寫入失敗（" + written + "），重建播放軌");
            recoverAudioPlayer();
        } else if (written > 0) {
            lastAudioOutputState = "AUDIOTRACK_PLAYING";
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
    private boolean enqueueAudio(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            lastAudioOutputState = "EMPTY_PCM";
            return false;
        }
        if (agentMuted) {
            lastAudioOutputState = "BLOCKED_MUTED";
            Log.w(TAG, "0047 audio blocked because agentMuted=true");
            return false;
        }
        if (interruptedCurrentTurn) {
            lastAudioOutputState = "BLOCKED_INTERRUPTED";
            Log.w(TAG, "0047 audio blocked because interruptedCurrentTurn=true");
            return false;
        }
        long durationMs = pcm.length * 1000L / (24000 * 2);
        lastPlaybackActiveAt = Math.max(System.currentTimeMillis(), lastPlaybackActiveAt) + durationMs;
        if (usingOboeOutput) {
            NativeOboeOutput.write(pcm);
            audioPcmBytesAccepted += pcm.length;
            lastAudioOutputState = "OBOE_ACCEPTED";
            return true;
        }
        boolean accepted = audioQueue.offer(pcm);
        if (!accepted) {
            audioQueue.poll();
            accepted = audioQueue.offer(pcm);
        }
        if (accepted) {
            audioPcmBytesAccepted += pcm.length;
            lastAudioOutputState = "AUDIOTRACK_QUEUED";
        } else {
            lastAudioOutputState = "AUDIOTRACK_QUEUE_FULL";
            Log.w(TAG, "音訊佇列已滿，略過過期語音片段");
        }
        return accepted;
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
        int consecutiveMutationFailures;
        int stabilityBlocks;
        boolean requireObservationAfterFailure;
        String lastFailedMutationSignature = "";
        String failedMutationScreenFingerprint = "";
        String lastSignature = "";
        String status = "";
        String blockedReason;
        String endReason = "";
        String finalReply = "";
        boolean awaitingModel;
        boolean watchdogPrompted;
        boolean userVisibleReplyProducedSinceLastAction;
        int finalSpeechRetryCount;
        boolean requiresPostActionInspection;
        boolean postActionInspectionPrompted;
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
                        .put("endReason", endReason).put("finalReply", finalReply).put("status", status)
                        .put("userVisibleReplyProduced", userVisibleReplyProducedSinceLastAction)
                        .put("finalSpeechRetryCount", finalSpeechRetryCount);
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
