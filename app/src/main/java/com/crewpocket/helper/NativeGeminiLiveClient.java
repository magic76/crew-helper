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
    // 0100 latency trace: diagnose model-vs-runtime wait and reject stale post-finish tools.
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
    private final NotebookToolHandler notebookToolHandler;
    private final AppPlaybookStore appPlaybookStore;
    private final PhoneRuntimeExecutor phoneRuntimeExecutor;
    private final RuntimeToolExecutor runtimeToolExecutor;
    private final LiveAudioController liveAudioController;
    private final DeckRuntimeController deckRuntimeController;
    private final MemoryRuleController memoryRuleController;
    private final ToolExecutionCoordinator toolExecutionCoordinator;
    private final java.util.HashSet<String> injectedAppPlaybooks = new java.util.HashSet<String>();
    // 0117: one-shot App teaching is Runtime-owned, never inferred from a model tool choice.
    private static final long APP_TEACH_MODE_TTL_MS = 45_000L;
    private volatile boolean appTeachModeArmed = false;
    private volatile long appTeachModeUntilMs = 0L;
    private volatile long runtimeAppTeachHandledGeneration = -1L;
    private volatile String runtimeAppTeachHandledMessage = "";
    private final LiveVisionController visionController;
    private volatile boolean running;
    private volatile String stage = "尚未開始";
    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private String resumptionHandle;
    private boolean reconnecting;
    private volatile long visualHoldUntil;
    private volatile boolean setupReady;
    private long screenFrameSequence;
    // Phone tasks routinely need several semantic actions plus model turns.
    // Observation/verification calls do not consume the mutation-action budget.
    private static final long AGENT_FINAL_RESPONSE_WAIT_MS = 12_000L;
    private static final int AGENT_FINAL_SPEECH_MAX_RETRIES = 2;
    // Navigation is deliberately repeatable during a presentation. All other
    // tools keep the conservative 3-run default safety limit.
    private final Object agentLock = new Object();
    private final ArrayList<AgentTaskRecord> agentHistory = new ArrayList<AgentTaskRecord>();
    private final ToolCallDispatcher toolCallDispatcher;
    private volatile Thread activeToolThread;
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
    // 0100 latency trace + stale same-intent tool guard.
    private long lastFinishedIntentGeneration = -1L;
    private String lastFinishedTaskId = "";
    // 0052: standalone "送出/發送/send" is owned directly by Runtime.
    private volatile boolean runtimeSendCurrentExecuting = false;
    private volatile long runtimeSendGuardUntil = 0L;
    // Finalized user turns are coordinated separately from model/tool frames.
    // Tool calls never grant authority; only finalized user input advances this.
    private final LiveTurnCoordinator liveTurnCoordinator = new LiveTurnCoordinator();
    private volatile long runtimeSendCurrentHandledGeneration = -1L;
    private AudioIncidentRecorder audioIncidentRecorder;
    private volatile PendingCondition pendingCondition = null;
    private final Object pendingChoiceLock = new Object();
    private PendingUiChoice pendingUiChoice;
    private volatile boolean pendingChoiceExecuting = false;
    private volatile SelectedRegionContext latestSelectedRegion;

    // 0046: Gemini Live may split toolCall/modelTurn/turnComplete across
    // different WebSocket frames. These flags describe the whole current
    // server model turn, not one handleJson() invocation.
    private boolean currentModelTurnHadToolCall = false;
    private boolean currentModelTurnBlocksDeckAutoAdvance = false;
    private boolean currentModelTurnProducedSpeech = false;
    // 0047: transcript text is not audible-output proof.
    private boolean currentModelTurnReceivedAudio = false;

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
        this.apiKey = apiKey;
        this.voiceName = voiceName == null || voiceName.trim().isEmpty()
                ? AppConfig.DEFAULT_VOICE : voiceName.trim();
        this.noiseMode = "quiet".equals(noiseMode) || "noisy".equals(noiseMode)
                ? noiseMode : "auto";
        this.noiseSuppression = Math.max(0, Math.min(100, noiseSuppression));
        this.liveTone = liveTone == null ? "warm" : liveTone;
        this.customPrompt = customPrompt == null ? "" : customPrompt.trim();
        this.interruptionSensitivity =
                Math.max(0, Math.min(100, interruptionSensitivity));
        this.audioOutput = "media".equals(audioOutput) ? "media" : "call";
        this.listener = listener;

        this.notebookToolHandler = new NotebookToolHandler(this.appContext);
        this.appPlaybookStore = new AppPlaybookStore(this.appContext);
        this.toolCallDispatcher = new ToolCallDispatcher(
                new ToolCallDispatcher.Host() {
                    @Override public void executeTool(JSONObject call) {
                        executeSingleTool(call);
                    }

                    @Override public void onToolQueued(
                            String id,
                            String name,
                            long generation) {
                        shadowAgentRuntime.onToolQueued(id, name, generation);
                        agentRuntimeV2.onToolQueued(id, name, generation);
                    }

                    @Override public void onDuplicateIgnored(
                            String id,
                            String name,
                            long generation) {
                        shadowAgentRuntime.onDuplicateIgnored(
                                id, name, generation);
                    }

                    @Override public void onWorkerChanged(Thread worker) {
                        activeToolThread = worker;
                    }

                    @Override public void onDispatchError(Exception error) {
                        Log.w(TAG, "工具呼叫排程失敗", error);
                    }
                });
        this.visionController = new LiveVisionController(
                new LiveVisionController.Sender() {
                    @Override public boolean send(String payload) {
                        WebSocket socket = NativeGeminiLiveClient.this.webSocket;
                        return socket != null && socket.send(payload);
                    }
                });
        this.phoneRuntimeExecutor = new PhoneRuntimeExecutor(
                this.appContext, this.visionController);
        this.runtimeToolExecutor = new RuntimeToolExecutor(
                this.notebookToolHandler,
                this.visionController,
                this.phoneRuntimeExecutor);
        this.audioIncidentRecorder = this.appContext == null
                ? null : new AudioIncidentRecorder(this.appContext);
        this.liveAudioController = new LiveAudioController(
                this.audioOutput,
                new LiveAudioController.Host() {
                    @Override public boolean isRunning() {
                        return NativeGeminiLiveClient.this.running;
                    }

                    @Override public boolean isAgentMuted() {
                        return NativeGeminiLiveClient.this.agentMuted;
                    }

                    @Override public boolean isInterruptedCurrentTurn() {
                        return NativeGeminiLiveClient.this.interruptedCurrentTurn;
                    }

                    @Override public boolean isAiSpeaking() {
                        return NativeGeminiLiveClient.this.aiSpeaking;
                    }

                    @Override public boolean isVoiceInterruptionAllowed() {
                        return NativeGeminiLiveClient.this.allowVoiceInterruption;
                    }

                    @Override public String getNoiseMode() {
                        return NativeGeminiLiveClient.this.noiseMode;
                    }

                    @Override public int getNoiseSuppression() {
                        return NativeGeminiLiveClient.this.noiseSuppression;
                    }

                    @Override public int getInterruptionSensitivity() {
                        return NativeGeminiLiveClient.this.interruptionSensitivity;
                    }

                    @Override public boolean canSendRealtime() {
                        return NativeGeminiLiveClient.this.webSocket != null;
                    }

                    @Override public boolean sendRealtime(String payload) {
                        WebSocket socket = NativeGeminiLiveClient.this.webSocket;
                        return socket != null && socket.send(payload);
                    }

                    @Override public void onStatus(String text) {
                        NativeGeminiLiveClient.this.listener.onStatus(text);
                    }

                    @Override public void onMicrophoneLevel(
                            double dbfs,
                            double gateDbfs,
                            boolean sending) {
                        NativeGeminiLiveClient.this.listener.onMicrophoneLevel(
                                dbfs, gateDbfs, sending);
                    }

                    @Override public void onUpstreamPcm(
                            byte[] pcm,
                            int count,
                            double rms,
                            double noiseFloor,
                            double gateThreshold) {
                        AudioIncidentRecorder recorder =
                                NativeGeminiLiveClient.this.audioIncidentRecorder;
                        if (recorder != null) {
                            recorder.onUpstreamPcm(
                                    pcm, count, rms, noiseFloor, gateThreshold);
                        }
                    }

                    @Override public void onFailure(
                            String message,
                            Throwable error) {
                        NativeGeminiLiveClient.this.fail(message, error);
                    }
                });

        this.deckRuntimeController = new DeckRuntimeController(
                new DeckRuntimeController.Host() {
                    @Override public boolean isRunning() {
                        return NativeGeminiLiveClient.this.running;
                    }

                    @Override public boolean hasLiveSession() {
                        return NativeGeminiLiveClient.this.webSocket != null;
                    }

                    @Override public boolean isInterruptedCurrentTurn() {
                        return NativeGeminiLiveClient.this.interruptedCurrentTurn;
                    }

                    @Override public boolean isAgentMuted() {
                        return NativeGeminiLiveClient.this.agentMuted;
                    }

                    @Override public long lastPlaybackActiveAt() {
                        return NativeGeminiLiveClient.this.liveAudioController
                                .getLastPlaybackActiveAt();
                    }

                    @Override public void resetModelTurnState() {
                        NativeGeminiLiveClient.this.resetCurrentModelTurnState();
                    }

                    @Override public void sendInternalDirective(String text) {
                        NativeGeminiLiveClient.this.sendInternalAgentDirective(text);
                    }

                    @Override public void reportStage(String text) {
                        NativeGeminiLiveClient.this.reportStage(text);
                    }
                });

        this.memoryRuleController = new MemoryRuleController(
                this.appContext,
                new MemoryRuleController.Host() {
                    @Override public void reportStage(String text) {
                        NativeGeminiLiveClient.this.reportStage(text);
                    }

                    @Override public void sendInternalDirective(String text) {
                        NativeGeminiLiveClient.this.sendInternalAgentDirective(text);
                    }

                    @Override public void updateTrustedAction(String action) {
                        NativeGeminiLiveClient.this.userActionScope
                                .updateFromTrustedAction(action);
                    }
                });

        this.toolExecutionCoordinator = new ToolExecutionCoordinator(
                this.runtimeToolExecutor,
                this.deckRuntimeController,
                new ToolExecutionCoordinator.Host() {
                    @Override public JSONObject getSelectedRegionForTool()
                            throws Exception {
                        SelectedRegionContext selected =
                                NativeGeminiLiveClient.this.latestSelectedRegion;
                        if (selected != null
                                && selected.isFresh()
                                && !selected.hardSensitive) {
                            PerformanceMetrics
                                    .recordSelectedRegionMetadataFallback();
                        }
                        return NativeGeminiLiveClient.this
                                .getSelectedRegionContext();
                    }

                    @Override public JSONObject rememberAppGuidance(
                            JSONObject args) throws Exception {
                        return NativeGeminiLiveClient.this
                                .rememberCurrentAppGuidance(args);
                    }

                    @Override public JSONObject listAppGuidance()
                            throws Exception {
                        return NativeGeminiLiveClient.this
                                .listCurrentAppGuidance();
                    }

                    @Override public JSONObject inspectUiForTool(
                            JSONObject args) throws Exception {
                        SelectedRegionContext selected =
                                NativeGeminiLiveClient.this.latestSelectedRegion;
                        if (selected != null
                                && selected.isFresh()
                                && !selected.hardSensitive) {
                            PerformanceMetrics
                                    .recordSelectedRegionFullScreenInspect();
                        }
                        return NativeGeminiLiveClient.this.inspectUi(args);
                    }

                    @Override public JSONObject tapElement(JSONObject args)
                            throws Exception {
                        return NativeGeminiLiveClient.this
                                .tapSemanticElement(args);
                    }

                    @Override public JSONObject waitForCondition(
                            JSONObject args) throws Exception {
                        return NativeGeminiLiveClient.this
                                .waitForCondition(args);
                    }

                    @Override public JSONObject launchApp(JSONObject args)
                            throws Exception {
                        return NativeGeminiLiveClient.this.launchApp(args);
                    }

                    @Override public JSONObject swipe(JSONObject args)
                            throws Exception {
                        return NativeGeminiLiveClient.this.swipe(args);
                    }

                    @Override public JSONObject tap(JSONObject args)
                            throws Exception {
                        return NativeGeminiLiveClient.this.tap(args);
                    }

                    @Override public JSONObject typeText(JSONObject args)
                            throws Exception {
                        return NativeGeminiLiveClient.this.typeText(args);
                    }

                    @Override public JSONObject searchCurrentApp(
                            JSONObject args) throws Exception {
                        return NativeGeminiLiveClient.this
                                .searchCurrentApp(args);
                    }

                    @Override public JSONObject commitSearch()
                            throws Exception {
                        return NativeGeminiLiveClient.this.commitSearch();
                    }

                    @Override public JSONObject sendText(JSONObject args)
                            throws Exception {
                        return NativeGeminiLiveClient.this
                                .sendTextToPhone(args);
                    }

                    @Override public JSONObject pressKey(JSONObject args)
                            throws Exception {
                        return NativeGeminiLiveClient.this.pressKey(args);
                    }

                    @Override public JSONObject startScreenMonitor(
                            JSONObject args) throws Exception {
                        return NativeGeminiLiveClient.this
                                .startScreenMonitor(args);
                    }
                });

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
    String getAudioOutputBackend() {
        return liveAudioController.getAudioOutputBackend();
    }
    boolean canSendVisualFrame() { return running && System.currentTimeMillis() >= visualHoldUntil; }
    boolean isSetupReadyForSelection() {
        return running && setupReady && webSocket != null;
    }

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
            liveAudioController.stopPlayback();
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
            phoneRuntimeExecutor.resetTransientState();
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
            toolCallDispatcher.clearPending();
            clearAgentResponseWatchdogLocked();
            agentHistory.add(task);
            lastFinishedIntentGeneration = task.intentGeneration;
            lastFinishedTaskId = task.taskId;
            activeAgentTask = null;
        }
        phoneRuntimeExecutor.cancelActiveRequest();
        Thread worker = activeToolThread;
        if (worker != null) worker.interrupt();
        PerformanceMetrics.markAgentTaskFinished(
                task.taskId, task.intentGeneration, "CANCELLED");
        PerformanceMetrics.recordAgentTask(
                Math.max(0L, System.currentTimeMillis() - task.startedAt));
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
            phoneRuntimeExecutor.resetTransientState();
        } else {
            conversationGoalTouchedAt = now;
            workingContext.beginUserTurn(userText);
            // A new user utterance supersedes any old asynchronous wait, even
            // when it is a follow-up within the same task capsule.
            pendingCondition = null;
        }

        SelectedRegionContext selectedReference = latestSelectedRegion;
        if (selectedReference != null
                && selectedReference.isFresh()
                && !selectedReference.hardSensitive) {
            workingContext.setSelectedReference(
                    selectedReference.sourcePackage,
                    selectedReference.semanticText);
        } else {
            if (selectedReference != null && !selectedReference.isFresh()) {
                latestSelectedRegion = null;
            }
            workingContext.clearSelectedReference();
        }

        String shadowTaskId = "";
        synchronized (agentLock) {
            userIntentGeneration++;
            PerformanceMetrics.markAgentUserIntent(userIntentGeneration);
            // Calls that have not started belong to the old utterance. An
            // executing call is additionally guarded by its generation below.
            toolCallDispatcher.resetForNewIntent();
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

    private boolean isFinishedIntentGeneration(long generation) {
        synchronized (agentLock) {
            return generation >= 0L
                    && generation == lastFinishedIntentGeneration
                    && (activeAgentTask == null || activeAgentTask.finished);
        }
    }

    private void sendFinishedIntentToolResponse(String id, String name) {
        try {
            sendToolResponse(id, name, new JSONObject()
                    .put("success", false)
                    .put("stale", true)
                    .put("blockedByRuntime", true)
                    .put("taskState", "DONE")
                    .put("error", "TASK_ALREADY_FINISHED")
                    .put("instruction",
                            "This user intent already finished. Do not call more tools; wait for a new user instruction."));
        } catch (Exception ignored) {}
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
            recordFinalizedSendAuthorization(input);
            if (tryHandleRuntimeAppTeaching(input)) {
                listener.onTranscript("你", input);
                return true;
            }
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
                    boolean sent = visionController.sendJpegBytes(jpegBytes);
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
                    boolean sent = visionController.sendImageFile(path, false);
                    Log.d(TAG, sent ? "相機影格已送達 Gemini" : "相機影格未送達 Gemini");
                } catch (Exception error) { Log.w(TAG, "相機影格傳送失敗：" + error.getMessage()); }
            }
        }, "crew-native-live-camera").start();
    }


    /**
     * Send only the user-selected crop as visual context.
     *
     * IMPORTANT: this method deliberately does NOT update visionController.lastVisionWidth(),
     * visionController.lastVisionHeight(), visionController.lastScreenWidth(), or visionController.lastScreenHeight(). A crop is never
     * a valid coordinate space for phone execution.
     */
    boolean sendSelectedRegion(final SelectedRegionContext selected) {
        if (selected == null
                || !selected.isFresh()
                || selected.hardSensitive
                || !selected.hasFrozenSnapshot()
                || !isSetupReadyForSelection()) {
            return false;
        }

        if (!screenCaptureInProgress.compareAndSet(false, true)) {
            return false;
        }

        latestSelectedRegion = selected;
        workingContext.setSelectedReference(
                selected.sourcePackage, selected.semanticText);

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    boolean sent = visionController.sendSelectedRegionSnapshot(selected);
                    if (!sent) {
                        throw new Exception("selected-region visual channel unavailable");
                    }
                    PerformanceMetrics.recordSelectedRegionCropContext();

                    reportStage("已讀取框選區域，直接說你想怎麼處理");
                    if (appContext != null) {
                        FloatingBubbleManager.getInstance(appContext)
                                .showCompactStatus(
                                        "已框選",
                                        "直接說你想怎麼處理");
                    }
                } catch (Exception error) {
                    Log.w(
                            TAG,
                            "框選區域傳送失敗："
                                    + (error.getMessage() == null
                                            ? error.getClass().getSimpleName()
                                            : error.getMessage()));
                    if (appContext != null) {
                        FloatingBubbleManager.getInstance(appContext)
                                .showCompactStatus(
                                        "框選讀取失敗",
                                        "請重新框選一次");
                    }
                } finally {
                    screenCaptureInProgress.set(false);
                }
            }
        }, "crew-selected-region").start();

        return true;
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
                long perfStarted = android.os.SystemClock.elapsedRealtime();
                boolean perfSuccess = false;
                try {
                    JSONObject result = runtimeToolExecutor.execute(
                            "take_screenshot", new JSONObject());
                    perfSuccess = result.optBoolean("success", false);
                    long sequence = ++screenFrameSequence;
                    Log.d(TAG, perfSuccess ? "螢幕影格 #" + sequence + " 已送達 Gemini（" + System.currentTimeMillis() + "）" : "螢幕影格 #" + sequence + " 未送達 Gemini：" + result.optString("error"));
                } catch (Exception error) {
                    Log.w(TAG, "螢幕影格傳送失敗：" + error.getMessage());
                } finally {
                    PerformanceMetrics.recordScreenFrame(
                            android.os.SystemClock.elapsedRealtime() - perfStarted,
                            perfSuccess);
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

    private volatile long serverInterruptedCount = 0L;
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
    boolean isDeckAutoAdvanceActive() {
        return deckRuntimeController.isAutoAdvanceActive();
    }
    void setDeckAutoAdvanceActive(boolean active) {
        deckRuntimeController.setAutoAdvanceActive(active);
    }

    void configureDeckStartup(String mode, String deckId) {
        deckRuntimeController.configureStartup(mode, deckId);
    }





    boolean isAgentMuted() { return agentMuted; }

    boolean isAppTeachModeArmed() {
        if (appTeachModeArmed && System.currentTimeMillis() > appTeachModeUntilMs) {
            clearAppTeachModeState();
            PerformanceMetrics.recordAppTeachExpired();
        }
        return appTeachModeArmed;
    }

    boolean armAppTeachMode() {
        if (!running) return false;
        appTeachModeArmed = true;
        appTeachModeUntilMs = System.currentTimeMillis() + APP_TEACH_MODE_TTL_MS;
        PerformanceMetrics.recordAppTeachArmed();
        return true;
    }

    boolean cancelAppTeachMode() {
        boolean wasArmed = isAppTeachModeArmed();
        clearAppTeachModeState();
        if (wasArmed) PerformanceMetrics.recordAppTeachCancelled();
        return wasArmed;
    }

    private void clearAppTeachModeState() {
        appTeachModeArmed = false;
        appTeachModeUntilMs = 0L;
    }

    private boolean isRuntimeAppTeachHandledGeneration(long generation) {
        return generation >= 0L && generation == runtimeAppTeachHandledGeneration;
    }

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
            liveAudioController.stopPlayback();
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



    private void triggerLocalInterruption() {
        markCurrentTurnInterrupted();
        aiSpeaking = false;
        liveAudioController.stopPlayback();
        listener.onSpeakingChanged(false);
        Log.d(TAG, "⚡ 本地零延遲語音插話觸發：立即停止播放並無縫收音");
    }

    private void markCurrentTurnInterrupted() {
        interruptedCurrentTurn = true;
        deckRuntimeController.onTurnInterrupted();
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
        deckRuntimeController.cancelAutoAdvance();
        resetCurrentModelTurnState();
        running = false;
        setupReady = false;
        interruptionHandler.removeCallbacks(clearInterruptedFallback);
        liveAudioController.stop();
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
            liveAudioController.start();
            deckRuntimeController.dispatchStartupIfNeeded(setupReady);
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
            recordFinalizedSendAuthorization(completeUserInput);
            if (tryHandleRuntimeAppTeaching(completeUserInput)) return;
            if (tryHandleRuntimeSendCurrent(completeUserInput)) return;
            if (isStopAgentTaskPhrase(completeUserInput)) {
                deckRuntimeController.cancelAutoAdvance();
                cancelAgentTask("使用者語音停止任務");
            } else if (memoryRuleController.processInput(completeUserInput)) {
                return;
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
                markCurrentModelTurnToolCall(
                        toolCallsAllowDeckNarrationAdvance(calls));
                clearAgentResponseWatchdog();
                for (int i = 0; i < calls.length(); i++) {
                    executeToolAsync(calls.getJSONObject(i));
                }
            }
        }
        if (server == null) return;
        if (server.optBoolean("interrupted", false)) {
            long interruptedIndex = ++serverInterruptedCount;
            long lastBargeInAdmissionAt =
                    liveAudioController.getLastBargeInAdmissionAt();
            long sinceBargeInMs = lastBargeInAdmissionAt <= 0L
                    ? -1L : Math.max(0L,
                        System.currentTimeMillis() - lastBargeInAdmissionAt);
            Log.w(TAG, "0120 serverContent.interrupted #" + interruptedIndex
                    + " aiSpeaking=" + aiSpeaking
                    + " allowVoiceInterruption=" + allowVoiceInterruption
                    + " sinceBargeInAdmitMs=" + sinceBargeInMs
                    + " outputState="
                    + liveAudioController.getLastAudioOutputState());
            liveAudioController.stopPlayback();
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
                        boolean audioAccepted =
                                liveAudioController.enqueueAudio(responsePcm);
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
            liveAudioController.finishTurn();
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
            deckRuntimeController.onNarrationTurnComplete(
                    deckNarrationTurn, deckTurnWasInterrupted);

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
            return DeckTurnAdvancePolicy.shouldAdvance(
                    currentModelTurnProducedSpeech,
                    currentModelTurnReceivedAudio,
                    currentModelTurnBlocksDeckAutoAdvance);
        }
    }

    /**
     * 0052: standalone send-only commands never depend on Gemini tool choice.
     * Runtime owns the physical SEND_CURRENT operation.
     */
    private void recordFinalizedSendAuthorization(String text) {
        liveTurnCoordinator.onFinalizedUserTurn(userIntentGeneration, text);
    }

    /**
     * Gemini Live may emit send_text in one frame and the finalized user
     * transcription in the next. Give only SEND a short grace window so the
     * authoritative user transcript can arrive. The model tool call itself
     * never grants permission.
     */
    private long awaitFinalizedSendAuthorization(
            String requestedName,
            JSONObject requestedArgs,
            long queuedGeneration) {
        if (!"send_text".equals(requestedName)) {
            return queuedGeneration;
        }

        long currentGeneration = userIntentGeneration;
        if (queuedGeneration == currentGeneration
                && userActionScope.canSend()) {
            return queuedGeneration;
        }

        LiveTurnCoordinator.FinalizedTurn finalized =
                liveTurnCoordinator.latest();

        if (finalized.generation != queuedGeneration + 1L) {
            finalized = liveTurnCoordinator.awaitNextAfter(
                    queuedGeneration, 1400L);
        }
        if (finalized.generation != queuedGeneration + 1L) {
            return queuedGeneration;
        }

        // A standalone send-only utterance is Runtime-owned. Never re-arm a
        // model send after Runtime has already claimed that finalized turn.
        if (runtimeSendCurrentHandledGeneration == finalized.generation
                && SendAuthorization.isStandaloneCurrentScreenSendCommand(
                        finalized.text)) {
            return finalized.generation;
        }

        if (!sendToolMatchesFinalizedUserIntent(
                requestedArgs, finalized.text)) {
            return queuedGeneration;
        }

        if (!ensureSendAuthorizationFromFinalized(
                finalized, requestedArgs)) {
            return queuedGeneration;
        }

        return finalized.generation;
    }

    private long awaitFinalizedLiteralTypeGeneration(
            String requestedName,
            JSONObject requestedArgs,
            long queuedGeneration) {
        String text = literalTypeText(requestedName, requestedArgs);
        if (text.isEmpty() || text.length() > 64) {
            return queuedGeneration;
        }

        LiveTurnCoordinator.FinalizedTurn latest =
                liveTurnCoordinator.latest();
        if (latest.generation == queuedGeneration
                && typeToolMatchesFinalizedIntent(text, latest.text)) {
            return queuedGeneration;
        }

        LiveTurnCoordinator.FinalizedTurn finalized =
                liveTurnCoordinator.awaitNextAfter(
                        queuedGeneration, 900L);
        if (finalized.generation != queuedGeneration + 1L
                || !typeToolMatchesFinalizedIntent(
                        text, finalized.text)) {
            return queuedGeneration;
        }

        Log.i(
                TAG,
                "TYPE reconciled to finalized turn generation="
                        + finalized.generation);
        return finalized.generation;
    }

    private String literalTypeText(
            String requestedName,
            JSONObject requestedArgs) {
        JSONObject args = requestedArgs == null
                ? new JSONObject() : requestedArgs;
        if ("type_text".equals(requestedName)) {
            return args.optString("text", "").trim();
        }
        if (SemanticPhoneAction.TOOL_NAME.equals(requestedName)
                && "TYPE".equalsIgnoreCase(
                        args.optString("action", "").trim())) {
            return args.optString("text", "").trim();
        }
        return "";
    }

    private boolean typeToolMatchesFinalizedIntent(
            String toolText,
            String finalizedText) {
        if (toolText == null
                || toolText.trim().isEmpty()
                || finalizedText == null
                || finalizedText.trim().isEmpty()) {
            return false;
        }
        String normalizedTool = TextMatch.caseFold(toolText)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()]", "");
        String normalizedFinal = TextMatch.caseFold(finalizedText)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()]", "");
        return !normalizedTool.isEmpty()
                && normalizedFinal.contains(normalizedTool);
    }

    private boolean sendToolMatchesFinalizedUserIntent(
            JSONObject requestedArgs,
            String finalizedText) {
        if (requestedArgs == null || finalizedText == null) return false;
        String toolText = requestedArgs.optString("text", "").trim();
        if (toolText.isEmpty()) {
            return SendAuthorization.isStandaloneCurrentScreenSendCommand(finalizedText);
        }
        String normalizedTool = TextMatch.caseFold(toolText)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()]", "");
        String normalizedFinal = TextMatch.caseFold(finalizedText)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()]", "");
        return !normalizedTool.isEmpty() && normalizedFinal.contains(normalizedTool);
    }

    private boolean ensureSendAuthorizationFromFinalized(
            LiveTurnCoordinator.FinalizedTurn finalized,
            JSONObject requestedArgs) {
        if (finalized == null
                || finalized.generation != userIntentGeneration
                || userActionScope.shouldBlockFurtherMessageMutation()
                || runtimeSendCurrentExecuting
                || runtimeSendCurrentHandledGeneration
                        == finalized.generation) {
            return false;
        }

        SendAuthorization probe = new SendAuthorization();
        probe.updateFromUserText(finalized.text);
        if (!probe.canAttempt()) {
            return false;
        }
        if (!sendToolMatchesFinalizedUserIntent(
                requestedArgs, finalized.text)) {
            return false;
        }

        userActionScope.updateFromUserText(finalized.text);
        boolean restored = userActionScope.canSend();
        if (restored) {
            Log.i(
                    TAG,
                    "RuntimeSend restored authorization from finalized turn generation="
                            + finalized.generation);
        }
        return restored;
    }

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

        runtimeSendCurrentHandledGeneration = generation;
        runtimeSendCurrentExecuting = true;
        runtimeSendGuardUntil = Long.MAX_VALUE;

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
                runtimeSendGuardUntil = System.currentTimeMillis() + 1200L;

                if (!isCurrentUserIntent(generation)) {
                    return;
                }

                boolean success = result.optBoolean("success", false);
                String stage = result.optString("stage", success ? "DONE" : "UNKNOWN");
                String error = result.optString("error", "");

                try {
                    FloatingBubbleManager.getInstance(appContext).showRuntimeUiState(
                            success
                                    ? RuntimeUiState.success("已送出", "")
                                    : RuntimeUiState.error(
                                            "訊息尚未送出",
                                            stage + (error.isEmpty()
                                                    ? ""
                                                    : " · " + error)));
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








    private static String mapToSupportedVoice(String name) {
        if (name == null || name.trim().isEmpty()) return "Kore";
        String v = name.trim();
        // Keep the configured current Gemini Live voice intact.  The old client
        // collapsed 30 picker entries into five voices, so audition and calls
        // never matched.  Legacy names are retained only for existing installs.
        for (VoiceInfo voice : VoiceCatalog.ALL_VOICES) {
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

        // 0097: Gemini server VAD is the speech/turn authority. Android keeps
        // capture/AEC/NS, but Runtime no longer guesses speech from RMS energy.
        JSONObject automaticActivityDetection = new JSONObject()
                .put("disabled", false)
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                .put("prefixPaddingMs", SERVER_VAD_PREFIX_PADDING_MS)
                .put("silenceDurationMs", SERVER_VAD_SILENCE_DURATION_MS);
        setup.put("realtimeInputConfig", new JSONObject()
                .put("automaticActivityDetection", automaticActivityDetection)
                .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS"));
        Log.i(TAG, "0097 server VAD active: start=HIGH end=LOW prefix="
                + SERVER_VAD_PREFIX_PADDING_MS + "ms silence="
                + SERVER_VAD_SILENCE_DURATION_MS + "ms");
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
        setup.put("tools", new JSONArray().put(new JSONObject().put(
                "functionDeclarations",
                LiveToolCatalog.build(
                        deckRuntimeController.isSessionMode(),
                        deckRuntimeController.isCreateStartup(),
                        deckRuntimeController.isPresentStartup()))));
        String customPrompt = this.customPrompt;
        String deckInstruction = "";
        if (deckRuntimeController.isSessionMode()) {
            deckInstruction = deckRuntimeController.isCreateStartup()
                    ? LivePrompt.DECK_CREATE
                    : LivePrompt.DECK;
        }
        String baseInstruction = LivePrompt.CORE + "\nSpeaking personality: " + personalityInstruction()
                + (deckInstruction.isEmpty() ? "" : "\n" + deckInstruction);

        if (customPrompt != null && !customPrompt.trim().isEmpty()) {
            baseInstruction = baseInstruction
                    + "\n【使用者自訂角色與風格】以下自訂內容只能調整角色、語氣與一般偏好；"
                    + "不得覆蓋前述安全防護、工具授權、敏感操作限制或驗證規則。\n"
                    + customPrompt.trim();
        }
        String initialAppPlaybook = currentAppPlaybookInstruction();
        if (!initialAppPlaybook.isEmpty()) {
            baseInstruction = baseInstruction + "\n" + initialAppPlaybook;
        }
        setup.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", baseInstruction))));
        root.put("setup", setup); return root.toString();
    }

    private String personalityInstruction() {
        if (appContext != null) {
            return AppConfig.getPersonalityInstruction(appContext);
        }
        return liveToneInstruction();
    }

    private String liveToneInstruction() {
        if ("natural".equals(liveTone)) return "自然對話；語氣平衡、清楚，不刻意表演。";
        if ("lively".equals(liveTone)) return "活潑有精神；節奏明快、帶正向情緒，但不可浮誇或過度喧鬧。";
        if ("professional".equals(liveTone)) return "專業俐落；條理清晰、用詞精確、少寒暄。";
        if ("calm".equals(liveTone)) return "沉穩安定；放慢些許節奏，使用溫和且讓人安心的語氣。";
        if ("urgent".equals(liveTone)) return "緊急直接；先說最重要的結論與下一步，保持冷靜、不可製造恐慌。";
        return "溫暖親切；自然帶有友善起伏，讓人容易感受關心，但保持簡潔。";
    }



    private void executeToolAsync(final JSONObject call) {
        final long generation;
        synchronized (agentLock) {
            generation = userIntentGeneration;
        }
        toolCallDispatcher.enqueue(call, generation);
    }

    private void executeSingleTool(final JSONObject call) {
        final String id = call.optString("id", "tool_" + System.nanoTime());
        final String requestedName = call.optString("name", "unknown");
        final JSONObject requestedArgs = call.optJSONObject("args") == null
                ? new JSONObject() : call.optJSONObject("args");
        long callIntentGeneration = call.optLong("_crew_intent_generation", -1L);

        // Gemini Live may emit the tool frame before the authoritative
        // finalized user transcript. Reconcile only operations whose payload
        // can be matched deterministically to that finalized turn.
        long reconciledGeneration = awaitFinalizedSendAuthorization(
                requestedName, requestedArgs, callIntentGeneration);
        reconciledGeneration = awaitFinalizedLiteralTypeGeneration(
                requestedName, requestedArgs, reconciledGeneration);

        if (reconciledGeneration != callIntentGeneration) {
            callIntentGeneration = reconciledGeneration;
            try {
                call.put("_crew_intent_generation", callIntentGeneration);
            } catch (Exception ignored) {}

            LiveTurnCoordinator.FinalizedTurn finalizedTurn =
                    liveTurnCoordinator.latest();
            if (runtimeSendCurrentHandledGeneration == callIntentGeneration
                    && finalizedTurn.generation == callIntentGeneration
                    && SendAuthorization.isStandaloneCurrentScreenSendCommand(
                            finalizedTurn.text)) {
                JSONObject runtimeOwned = new JSONObject();
                try {
                    runtimeOwned.put("success", true)
                            .put("taskState", "IN_PROGRESS")
                            .put("runtimeHandled", "SEND_CURRENT")
                            .put(
                                    "message",
                                    "Runtime 已接管這次送出；不要再呼叫 send_text 或其他 mutation。");
                    sendToolResponse(id, requestedName, runtimeOwned);
                } catch (Exception ignored) {}
                return;
            }
        }

        if (!isCurrentUserIntent(callIntentGeneration)) {
            // The user has already spoken a new command.  Do not execute a
            // queued mutation from the old turn, and do not create a new task
            // record that would inherit its repeat counter.
            shadowAgentRuntime.onStaleActionRejected(
                    id, requestedName, callIntentGeneration, userIntentGeneration);
            sendBlockedToolResponse(id, requestedName, "使用者已有新指令，舊操作已取消。");
            return;
        }
        if (isFinishedIntentGeneration(callIntentGeneration)) {
            PerformanceMetrics.recordStalePostFinishTool(requestedName);
            sendFinishedIntentToolResponse(id, requestedName);
            return;
        }
        if (isRuntimeAppTeachHandledGeneration(callIntentGeneration)) {
            PerformanceMetrics.recordAppTeachToolSuppressed();
            JSONObject handled = new JSONObject();
            try {
                handled.put("success", true);
                handled.put("runtimeHandled", "APP_TEACH");
                handled.put("message", runtimeAppTeachHandledMessage.isEmpty()
                        ? "Runtime 已處理這次 App 教學；不要再呼叫工具，只要簡短回覆使用者。"
                        : runtimeAppTeachHandledMessage);
                sendToolResponse(id, requestedName, handled);
            } catch (Exception ignored) {}
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


        long executionGuardNow = System.currentTimeMillis();
        boolean runtimeSendOwnsExecution =
                runtimeSendCurrentExecuting
                        || executionGuardNow < runtimeSendGuardUntil;
        boolean runtimeShortcutOwnsExecution =
                memoryRuleController.isShortcutExecuting()
                        || executionGuardNow
                                < memoryRuleController.shortcutGuardUntil();
        if ((runtimeSendOwnsExecution || runtimeShortcutOwnsExecution)
                && isMutationTool(name)) {
            sendBlockedToolResponse(
                    id,
                    requestedName,
                    runtimeSendOwnsExecution
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
        PerformanceMetrics.markAgentToolStarted(
                task.taskId, task.intentGeneration, name);
        if (isMutationTool(name) && audioIncidentRecorder != null) {
            audioIncidentRecorder.captureBeforeFirstMutation(task.taskId, name, args);
        }
        JSONObject result = new JSONObject();
        long toolStartedAt = android.os.SystemClock.elapsedRealtime();
        try {
            shadowAgentRuntime.onActionExecuted(id);
            if ("end_voice_session".equals(name)) {
                if (!userActionScope.consumeEndCallAuthorization()) {
                    JSONObject blocked = runtimeBlocked(
                            "END_CALL_NOT_AUTHORIZED",
                            "請使用者明確說結束通話；關閉視窗或再見不代表掛斷。");
                    task.addStep(name, blocked);
                    sendToolResponse(id, requestedName, blocked);
                    task.awaitingModel = true;
                    scheduleAgentResponseWatchdog(task);
                    return;
                }
                result.put("success", true)
                        .put("message", "語音通話即將結束");
                sendToolResponse(id, requestedName, result);
                finishAgentTask(task, "通話結束", "");
                new Handler(Looper.getMainLooper()).postDelayed(
                        new Runnable() {
                            @Override public void run() {
                                stop();
                            }
                        },
                        1200);
                return;
            }
            result = toolExecutionCoordinator.execute(name, args);
        } catch (Exception error) {
            try { result.put("success", false).put("error", error.getMessage() == null ? "工具執行失敗" : error.getMessage()); } catch (Exception ignored) {}
        } finally {
            long toolElapsedMs = android.os.SystemClock.elapsedRealtime() - toolStartedAt;
            PerformanceMetrics.recordTool(name, toolElapsedMs);
            PerformanceMetrics.markAgentToolRuntime(
                    task.taskId, task.intentGeneration, name, toolElapsedMs);
        }
        if (task.finished || task.cancelled || !isCurrentUserIntent(callIntentGeneration)) {
            PerformanceMetrics.recordStaleCompletion(name);
            try { sendFinishedIntentToolResponse(id, requestedName); } catch (Exception ignored) {}
            return;
        }
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
            if (isPhoneContextTool(name)) attachCurrentAppPlaybook(result);
            updateTaskCompletionContract(task, name, result);
            updateAgentStabilityAfterResult(task, name, args, result);
            task.addStep(name, result);
            sendToolResponse(id, requestedName, result);
            PerformanceMetrics.markAgentToolResultSent(
                    task.taskId, task.intentGeneration, name);
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
                activeAgentTask.intentGeneration = userIntentGeneration;
                activeAgentTask.goalId = conversationGoalId;
                activeAgentTask.goalTaskIndex = conversationGoalTaskIndex;
                reportStage("Agent 任務開始：" + activeAgentTask.taskId);
            }
            AgentTaskRecord task = activeAgentTask;
            clearAgentResponseWatchdogLocked();
            String signature = buildAgentSignature(name, args);
            if (task.cancelled) return null;

            AgentTaskLifecyclePolicy.StepDecision decision =
                    AgentTaskLifecyclePolicy.evaluateStep(
                            System.currentTimeMillis(),
                            task.startedAt,
                            task.steps,
                            agentMaxSteps,
                            name,
                            signature,
                            task.lastSignature,
                            task.getToolCount(name),
                            task.mutationActions);
            if (!decision.allowed) task.blockedReason = decision.blockedReason;

            if (task.blockedReason == null) {
                task.steps++;
                task.lastSignature = signature;
                task.incrementTool(name);
                if (isMutationTool(name)) task.mutationActions++;
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
        return AgentTaskLifecyclePolicy.isObservationTool(name);
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
        return AgentTaskLifecyclePolicy.isMutationTool(name);
    }

    private AgentTaskRecord peekActiveAgentTask() {
        synchronized (agentLock) {
            return activeAgentTask == null || activeAgentTask.finished ? null : activeAgentTask;
        }
    }

    private JSONObject agentStabilityPreflight(AgentTaskRecord task, String name, JSONObject args) {
        if (task == null || task.finished || task.cancelled || !isMutationTool(name)) return null;
        synchronized (agentLock) {
            AgentTaskLifecyclePolicy.StabilityDecision decision =
                    AgentTaskLifecyclePolicy.evaluateStability(
                            task.finished,
                            task.cancelled,
                            name,
                            task.requireObservationAfterFailure,
                            semanticObserveRequired,
                            buildAgentSignature(name, args),
                            task.lastFailedMutationSignature,
                            task.failedMutationScreenFingerprint,
                            latestSemanticFingerprint);
            if (!decision.blocked) return null;

            task.stabilityBlocks++;
            if (AgentTaskLifecyclePolicy.shouldStopAfterStabilityBlock(task.stabilityBlocks)) {
                task.blockedReason = "Runtime 已連續兩次阻止無效重試，停止本次 Agent loop；請向使用者簡短回報目前卡點。";
            }
            try {
                return new JSONObject()
                        .put("success", false)
                        .put("stepResult", "STEP_FAILED")
                        .put("blockedByRuntime", true)
                        .put("error", decision.code)
                        .put("instruction", decision.instruction);
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

            if (AgentTaskLifecyclePolicy.shouldStopAfterMutationFailure(
                    task.consecutiveMutationFailures)) {
                task.blockedReason = "連續 3 次手機操作失敗，Runtime 已停止繼續試錯；請回報目前畫面與卡點，不要再呼叫工具。";
            }
        }
    }

    private String buildAgentSignature(String name, JSONObject args) {
        // Each advance has a different logical position, so it is not a model loop.
        if ("advance_deck".equals(name)) {
            return name + ":" + args.toString()
                    + ":at=" + deckRuntimeController.activeIndex();
        }
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
                    && GoogleMapsRuntimeAdapter.PACKAGE_NAME.equals(latestActionObservation.packageName));
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







    private void appendAgentFinalText(String text) {
        synchronized (agentLock) {
            if (activeAgentTask != null && activeAgentTask.awaitingModel) {
                activeAgentTask.finalReply += text;
            }
        }
    }

    private void markCurrentModelTurnToolCall(
            boolean allowsDeckNarrationAdvance) {
        synchronized (agentLock) {
            currentModelTurnHadToolCall = true;
            if (!allowsDeckNarrationAdvance) {
                currentModelTurnBlocksDeckAutoAdvance = true;
            }
            // Any speech before a later tool call was intermediate, not final.
            currentModelTurnProducedSpeech = false;
        }
    }

    private boolean toolCallsAllowDeckNarrationAdvance(JSONArray calls) {
        if (calls == null || calls.length() == 0) return false;
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            if (call == null) return false;
            String name = call.optString("name", "").trim();
            if (!deckRuntimeController.handles(name)) {
                return false;
            }
        }
        return true;
    }

    private void noteCurrentModelTurnAudioReceived(int bytes) {
        synchronized (agentLock) {
            currentModelTurnReceivedAudio = true;
            liveAudioController.notePcmReceived(bytes);
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
            currentModelTurnBlocksDeckAutoAdvance = false;
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
        String taskId = "";
        long intentGeneration = -1L;
        synchronized (agentLock) {
            if (activeAgentTask != null
                    && activeAgentTask.awaitingModel
                    && !activeAgentTask.finished
                    && !activeAgentTask.cancelled) {
                activeAgentTask.userVisibleReplyProducedSinceLastAction = true;
                activeAgentTask.finalSpeechRetryCount = 0;
                taskId = activeAgentTask.taskId;
                intentGeneration = activeAgentTask.intentGeneration;
            }
        }
        if (!taskId.isEmpty()) {
            PerformanceMetrics.markAgentFinalSpeech(taskId, intentGeneration);
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

        reportStage(task.status + " · audio="
                + liveAudioController.getLastAudioOutputState()
                + " · pcmReceived=" + liveAudioController.getPcmBytesReceived()
                + " · pcmAccepted=" + liveAudioController.getPcmBytesAccepted());
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
            lastFinishedIntentGeneration = task.intentGeneration;
            lastFinishedTaskId = task.taskId;
            if (activeAgentTask == task) activeAgentTask = null;
            conversationGoalTouchedAt = System.currentTimeMillis();
        }
        PerformanceMetrics.markAgentTaskFinished(
                task.taskId, task.intentGeneration, "FINISHED");
        PerformanceMetrics.recordAgentTask(
                Math.max(0L, System.currentTimeMillis() - task.startedAt));
        reportStage(task.status);
    }

    private JSONObject teachUiElement(JSONObject args) throws Exception {
        String role = args.optString("role", "COMPOSER_SEND").trim().toUpperCase(Locale.ROOT);
        JSONObject reply = phoneRuntimeExecutor.post("/teach_ui", new JSONObject().put("role", role));
        reply.put("role", role);
        reply.put("instruction", "已啟動畫面教導遮罩。請以語音引導使用者直接在螢幕上點擊該「" + role + "」按鈕以完成學習。");
        return reply;
    }


    private JSONObject startScreenMonitor(JSONObject args) throws Exception {
        int interval = (int) args.optDouble("interval_seconds", 60), duration = (int) args.optDouble("duration_minutes", 10);
        String cond = args.optString("target_condition", ""), lbl = args.optString("label", "畫面巡檢");
        ScheduledTaskManager mgr = ScheduledTaskManager.getInstance(CrewAccessibilityService.getInstance() != null ? CrewAccessibilityService.getInstance() : MainActivity.class.cast(null));
        ScheduledTaskManager.ScheduledTask task = mgr.startScreenMonitor(lbl, interval, duration, cond, true);
        return new JSONObject().put("success", true).put("task", task.toJson()).put("message", "已啟動畫面監控：" + lbl);
    }



    private JSONObject swipe(JSONObject args) throws Exception {
        JSONObject reply = phoneRuntimeExecutor.swipe(args);
        if (!reply.has("direction")) return reply;

        JSONObject visual = new JSONObject();
        try {
            visual = runtimeToolExecutor.execute(
                    "take_screenshot", new JSONObject());
        } catch (Exception error) {
            visual.put("success", false).put("error", error.getMessage());
        }

        boolean changed = reply.optBoolean("screenChanged", false);
        reply.put("screenFrameSent", visual.optBoolean("success"));
        reply.put(
                "verification",
                changed
                        ? "UI 節點位置或內容已變更；最新螢幕影格已送達，請分析新畫面"
                        : (visual.optBoolean("success")
                                ? "文字節點未變，但最新螢幕影格已送達；請依畫面判斷是否已滑動"
                                : "UI 節點與最新螢幕影格皆無法確認變化，請改用另一方向或尋找按鈕"));
        workingContext.recordAction(
                "swipe:" + reply.optString("direction", "up"),
                reply.optBoolean("success", false)
                        ? "submitted" : "failed");
        return autoObserveAfterMutation(reply, "swipe_screen");
    }



    private JSONObject tap(JSONObject args) throws Exception {
        String label = args.optString(
                "label",
                args.optString("text", args.optString("name", ""))).trim();
        String id = args.optString("id", "").trim();
        String tapMeta = label + " " + id;

        if (UserActionScope.looksLikeSendTarget(tapMeta)) {
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
                routedSend.put(
                        "instruction",
                        "TAP Send 已由 Runtime 轉成單次 SEND_CURRENT 並完成；不要再呼叫 Send/TAP。");
            }
            return routedSend;
        }

        if (!pendingChoiceExecuting
                && userActionScope.shouldBlockTapForSearch(
                        tapMeta, label.isEmpty() && id.isEmpty())) {
            return runtimeBlocked(
                    "SEARCH_SCOPE_RESULT_OPEN_NOT_AUTHORIZED",
                    "最新任務只要求搜尋。搜尋結果出現後不要打開人、群組或聊天室；直接回報結果。");
        }

        JSONObject reply = phoneRuntimeExecutor.tap(args);
        workingContext.recordAction(
                "tap_screen",
                reply.optBoolean("success", false)
                        ? "submitted" : "failed");
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
                analysis = phoneRuntimeExecutor.post("/search_result_candidates",
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
                GoogleMapsRuntimeAdapter.PACKAGE_NAME);
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

        // 0104: choice state is headless. Gemini speaks the compact choices;
        // the next user utterance is resolved deterministically by PendingUiChoice.

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
                        "不要顯示額外 Crew 選項 UI。請用語音簡短列出 choices 並詢問使用者；等待第一個/第二個/結果名稱/取消。等待期間禁止任何手機 mutation。");
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
        if (!GoogleMapsRuntimeAdapter.PACKAGE_NAME.equals(
                latest.optString("package", ""))) return false;

        String latestFingerprint = latest.optString("fingerprint", "");
        if (!beforeFingerprint.isEmpty() && !latestFingerprint.isEmpty()
                && !beforeFingerprint.equals(latestFingerprint)) {
            changed = true;
        }
        if (!changed) return false;

        if (containsElement(latest.optJSONArray("elements"), elementId)) return false;

        try {
            JSONObject analysis = phoneRuntimeExecutor.post(
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
        workingContext.setPendingTask("");
        resumeAgentAfterUserChoice("使用者取消搜尋結果選擇");
        sendInternalAgentDirective(
                "【使用者取消搜尋結果選擇】"
                + (reason == null ? "" : reason)
                + "；停止目前結果選擇，不要繼續點擊。");
    }

    private JSONObject tapSemanticElement(JSONObject args) throws Exception {
        String elementId = args == null
                ? "" : args.optString("element_id", "").trim();
        if (elementId.isEmpty()) {
            return new JSONObject()
                    .put("success", false)
                    .put("error", "MISSING_ELEMENT_ID");
        }

        String elementMeta = semanticElementMeta(elementId);
        if (UserActionScope.looksLikeSendTarget(elementMeta)) {
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
                routedSend.put(
                        "instruction",
                        "TAP Send 已由 Runtime 轉成單次 SEND_CURRENT 並完成；不要再呼叫 Send/TAP。");
            }
            return routedSend;
        }

        if (!pendingChoiceExecuting
                && userActionScope.shouldBlockTapForSearch(
                        elementMeta, elementMeta.isEmpty())) {
            return runtimeBlocked(
                    "SEARCH_SCOPE_RESULT_OPEN_NOT_AUTHORIZED",
                    "最新任務只要求搜尋。搜尋結果出現後不要打開人、群組或聊天室；直接回報結果。");
        }

        JSONObject reply = phoneRuntimeExecutor.semanticTap(elementId);
        workingContext.recordAction(
                "tap:" + elementId,
                reply.optBoolean("success", false)
                        ? "submitted"
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
                last = phoneRuntimeExecutor.get("/semantic_screen");
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
                           .put("condition", type.name());
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
               .put("timeout", true);
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
            return phoneRuntimeExecutor.get("/semantic_screen");
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
                && GoogleMapsRuntimeAdapter.PACKAGE_NAME.equals(
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




    private boolean tryHandleRuntimeAppTeaching(String input) {
        boolean armedBefore = isAppTeachModeArmed();
        AppTeachIntent intent = AppTeachIntent.parse(input, armedBefore);
        if (intent.kind == AppTeachIntent.Kind.NONE) return false;

        runtimeAppTeachHandledGeneration = userIntentGeneration;
        userActionScope.consumeAppLearningAuthorization();

        if (intent.kind == AppTeachIntent.Kind.CANCEL) {
            clearAppTeachModeState();
            PerformanceMetrics.recordAppTeachCancelled();
            runtimeAppTeachHandledMessage =
                    "App 教學已取消；不要呼叫工具，只要簡短確認已取消。";
            NativeLiveService.notifyAppTeachFeedback("已取消 App 教學", "");
            reportStage("已取消 App 教學");
            return true;
        }

        if (intent.kind == AppTeachIntent.Kind.ARM) {
            appTeachModeArmed = true;
            appTeachModeUntilMs = System.currentTimeMillis() + APP_TEACH_MODE_TTL_MS;
            PerformanceMetrics.recordAppTeachArmed();
            runtimeAppTeachHandledMessage =
                    "Runtime 已進入 App 教學模式；不要呼叫工具，只要請使用者說出要記住的一條操作規則。";
            NativeLiveService.notifyAppTeachFeedback(
                    "正在學習目前 App",
                    "說一句你要 Crew 記住的操作規則");
            reportStage("等待 App 教學規則");
            return true;
        }

        String packageName = currentForegroundPackageName();
        clearAppTeachModeState();
        if (packageName.isEmpty()) {
            runtimeAppTeachHandledMessage =
                    "Runtime 無法確認目前前景 App；不要呼叫其他工具，請簡短請使用者回到 App 後再教一次。";
            NativeLiveService.notifyAppTeachFeedback(
                    "無法記住 App 經驗",
                    "目前無法確認前景 App");
            reportStage("App 教學失敗：找不到前景 App");
            return true;
        }

        JSONObject saved = appPlaybookStore.remember(
                packageName,
                AppRuntimeRegistry.displayName(appContext, packageName),
                "",
                intent.guidance,
                AppPlaybookStore.SOURCE_VOICE);
        if (saved.optBoolean("success", false)) {
            synchronized (injectedAppPlaybooks) { injectedAppPlaybooks.remove(packageName); }
            PerformanceMetrics.recordAppTeachSaved();
            runtimeAppTeachHandledMessage =
                    "App 經驗已由 Runtime 保存；不要再呼叫工具，只要簡短確認已記住。";
            NativeLiveService.notifyAppTeachFeedback(
                    "已記住 App 經驗",
                    AppRuntimeRegistry.displayName(appContext, packageName));
            reportStage("✓ 已記住 App 經驗");
        } else {
            runtimeAppTeachHandledMessage =
                    "Runtime 無法保存這條 App 經驗；不要改用 Notebook 或其他工具，請簡短告知使用者。";
            NativeLiveService.notifyAppTeachFeedback(
                    "無法記住 App 經驗",
                    saved.optString("error", "儲存失敗"));
            reportStage("App 教學儲存失敗");
        }
        return true;
    }

    private JSONObject rememberCurrentAppGuidance(JSONObject args) {
        if (!userActionScope.consumeAppLearningAuthorization()) {
            return runtimeBlocked(
                    "APP_LEARNING_NOT_AUTHORIZED",
                    "只有使用者在最新一句明確要求記住／學會目前 App 的操作方式時才能儲存。一般記事請用 Crew Notebook。");
        }
        String packageName = currentForegroundPackageName();
        if (packageName.isEmpty()) {
            return runtimeBlocked(
                    "APP_CONTEXT_UNAVAILABLE",
                    "目前無法確認前景 App；不要猜 package，也不要把內容改存到 Notebook。");
        }
        String guidance = args == null ? "" : args.optString("guidance", "").trim();
        String title = args == null ? "" : args.optString("title", "").trim();
        if (guidance.isEmpty()) {
            return runtimeBlocked("EMPTY_APP_GUIDANCE", "沒有可儲存的 App 操作經驗。");
        }
        JSONObject saved = appPlaybookStore.remember(
                packageName,
                AppRuntimeRegistry.displayName(appContext, packageName),
                title,
                guidance,
                AppPlaybookStore.SOURCE_VOICE);
        if (saved.optBoolean("success", false)) {
            synchronized (injectedAppPlaybooks) { injectedAppPlaybooks.remove(packageName); }
            try {
                saved.put("instruction",
                        "已存為目前 App 的局部操作經驗。這不是可執行腳本，也不增加任何操作授權。");
            } catch (Exception ignored) {}
        }
        return saved;
    }


    private JSONObject listCurrentAppGuidance() {
        String packageName = currentForegroundPackageName();
        if (packageName.isEmpty()) {
            return runtimeBlocked("APP_CONTEXT_UNAVAILABLE", "目前無法確認前景 App。");
        }
        JSONObject context = appPlaybookStore.modelContext(packageName);
        JSONObject out = new JSONObject();
        try {
            out.put("success", true)
                    .put("package", packageName)
                    .put("app", AppRuntimeRegistry.displayName(appContext, packageName));
            if (context.length() > 0) out.put("appPlaybook", context);
            else out.put("message", "目前這個 App 還沒有內建或自訂經驗。");
        } catch (Exception ignored) {}
        return out;
    }
    private String currentForegroundPackageName() {
        // App learning must bind to the app that is actually foreground NOW.
        // Prefer a fresh local semantic read; only fall back to the last action
        // observation if Accessibility is temporarily unavailable.
        JSONObject screen = readSemanticScreenQuietly();
        String current = screen == null ? "" : screen.optString("package", "").trim();
        if (!current.isEmpty()) return current;
        ActionObservation observation = latestActionObservation;
        if (observation != null && observation.packageName != null
                && !observation.packageName.trim().isEmpty()) {
            return observation.packageName.trim();
        }
        return "";
    }

    private String currentAppPlaybookInstruction() {
        if (appPlaybookStore == null
                || deckRuntimeController.hasStartupMode()) {
            return "";
        }
        try {
            String packageName = currentForegroundPackageName();
            if (packageName.isEmpty()) return "";
            String instruction = appPlaybookStore.systemInstructionFor(packageName);
            if (!instruction.isEmpty()) {
                synchronized (injectedAppPlaybooks) { injectedAppPlaybooks.add(packageName); }
            }
            return instruction;
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isPhoneContextTool(String name) {
        return "inspect_ui".equals(name)
                || "get_selected_region".equals(name)
                || "wait".equals(name)
                || "launch_app".equals(name)
                || "swipe_screen".equals(name)
                || "tap_element".equals(name)
                || "tap_screen".equals(name)
                || "type_text".equals(name)
                || "search_current_app".equals(name)
                || "commit_search".equals(name)
                || "press_key".equals(name)
                || "take_screenshot".equals(name);
    }

    private void attachCurrentAppPlaybook(JSONObject result) {
        if (result == null || appPlaybookStore == null) return;
        String packageName = result.optString("package", "").trim();
        JSONObject after = result.optJSONObject("after");
        if (packageName.isEmpty() && after != null) {
            packageName = after.optString("package", "").trim();
        }
        JSONObject fallback = result.optJSONObject("semanticFallback");
        if (packageName.isEmpty() && fallback != null) {
            packageName = fallback.optString("package", "").trim();
        }
        if (packageName.isEmpty()) packageName = currentForegroundPackageName();
        if (packageName.isEmpty()) return;
        synchronized (injectedAppPlaybooks) {
            if (injectedAppPlaybooks.contains(packageName)) return;
        }
        JSONObject context = appPlaybookStore.modelContext(packageName);
        if (context.length() == 0) return;
        try {
            result.put("appPlaybook", context);
            synchronized (injectedAppPlaybooks) { injectedAppPlaybooks.add(packageName); }
        } catch (Exception ignored) {}
    }




    private JSONObject getSelectedRegionContext() throws Exception {
        SelectedRegionContext selected = latestSelectedRegion;
        if (selected == null) {
            return new JSONObject()
                    .put("success", false)
                    .put("error", "NO_SELECTED_REGION")
                    .put(
                            "message",
                            "使用者目前沒有有效的框選區域。");
        }

        if (!selected.isFresh()) {
            latestSelectedRegion = null;
            workingContext.clearSelectedReference();
            return new JSONObject()
                    .put("success", false)
                    .put("error", "SELECTED_REGION_EXPIRED")
                    .put(
                            "message",
                            "先前框選已過期，請使用者重新框選。");
        }

        if (selected.hardSensitive) {
            return new JSONObject()
                    .put("success", false)
                    .put("error", "SELECTED_REGION_SENSITIVE");
        }

        return selected.toModelJson();
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
        JSONObject semantic = phoneRuntimeExecutor.get("/semantic_screen");
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
            visual = runtimeToolExecutor.execute(
                    "take_screenshot", new JSONObject());
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
        JSONObject raw = phoneRuntimeExecutor.get("/screen_state");
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
            JSONObject raw = phoneRuntimeExecutor.get("/screen_state");
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
        PhoneRuntimeExecutor.MutationResult execution =
                phoneRuntimeExecutor.launchApp(args);
        JSONObject reply = execution.result;
        if (!execution.actionKey.isEmpty()) {
            workingContext.recordAction(
                    execution.actionKey,
                    reply.optBoolean("success", false)
                            ? "submitted" : "failed");
        }
        return execution.observeAfter
                ? autoObserveAfterMutation(reply, "launch_app")
                : reply;
    }









    private JSONObject typeText(JSONObject args) throws Exception {
        String text = args.optString("text", "").trim();
        if (text.isEmpty()) return new JSONObject().put("success", false).put("error", "輸入文字不可為空");
        // Weak Live models occasionally choose TYPE even though the latest
        // utterance clearly asks to send.  Upgrade that call to the one safe
        // Runtime-owned transaction instead of permitting TYPE -> TAP guessing.
        if (userActionScope.canSend()) {
            PerformanceMetrics.recordTextRouteTypeRemappedToSend();
            return sendTextToPhone(args);
        }
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
        PerformanceMetrics.recordTextRouteType();
        JSONObject reply = phoneRuntimeExecutor.typeText(text);
        if (reply.optBoolean("success", false)) {
            reply.put("message", "已在輸入框輸入文字");
        }
        workingContext.recordAction("type", reply.optBoolean("success", false) ? "submitted" : "failed");

        // 0036 Search Transaction:
        // Query entry is not the end of a search. If the latest user intent is
        // actually SEARCH, Runtime commits the focused search field itself so
        // a weak model does not need another tool call for the keyboard Search.
        if (reply.optBoolean("success", false) && userActionScope.shouldAutoCommitSearch()) {
            String transactionPackage = latestActionObservation == null
                    ? "" : latestActionObservation.packageName;
            userActionScope.markSearchQueryEntered(
                    text, transactionPackage, userIntentGeneration);

            JSONObject commit;
            try {
                commit = phoneRuntimeExecutor.commitSearch();
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

        String currentSearchPackage = latestActionObservation == null
                ? "" : latestActionObservation.packageName;
        if (currentSearchPackage.isEmpty()) {
            JSONObject screen = readSemanticScreenQuietly();
            if (screen != null) {
                currentSearchPackage = screen.optString("package", "");
            }
        }

        if (userActionScope.shouldSuppressDuplicateSearch(
                text, currentSearchPackage, userIntentGeneration)) {
            PerformanceMetrics.recordDuplicateSearchSuppressed();
            return new JSONObject()
                    .put("success", true)
                    .put("stepResult", "STEP_OK")
                    .put("action", "APP_SEARCH")
                    .put("searchTransaction", "ALREADY_SUBMITTED")
                    .put("duplicateSuppressed", true)
                    .put("taskState", "IN_PROGRESS")
                    .put("completionEvidence", "SEARCH_DUPLICATE_SUPPRESSED")
                    .put("instruction",
                            "Runtime 已經提交同一筆搜尋；不要重新 SEARCH/TYPE/COMMIT_SEARCH。"
                            + "請先 inspect_ui 一次確認目前結果畫面，再繼續選結果或導航。");
        }

        PerformanceMetrics.recordSearchExecution();
        JSONObject reply = phoneRuntimeExecutor.post("/search_in_app", new JSONObject().put("query", text));
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
        String observedSearchPackage = latestActionObservation == null
                ? currentSearchPackage : latestActionObservation.packageName;
        if (observedSearchPackage == null || observedSearchPackage.isEmpty()) {
            observedSearchPackage = currentSearchPackage;
        }
        userActionScope.markSearchQueryEntered(
                text, observedSearchPackage, userIntentGeneration);

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
            // The physical Search/IME submit has already left Runtime. Even if
            // result evidence is one frame late, repeating the same SEARCH would
            // overwrite a successful transaction and destabilize Maps.
            userActionScope.markSearchSubmissionDispatched();
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

    /**
     * Named-recipient SEND boundary.
     *
     * Navigation is allowed after an explicit named-recipient request, but the
     * actual submit stays fail-closed until Accessibility proves both:
     * 1) a non-search chat composer is visible, and
     * 2) the requested recipient appears in the header region above it.
     *
     * This intentionally uses only the current semantic screen. No contact
     * history, prior conversation memory, screenshots, or model inference can
     * satisfy the target check.
     */
    private JSONObject verifyAuthorizedRecipientOnCurrentScreen() throws Exception {
        String recipient = userActionScope.authorizedRecipient();
        if (recipient == null || recipient.trim().isEmpty()) {
            return runtimeBlocked("RECIPIENT_TARGET_UNKNOWN",
                    "缺少可驗證的收件人；不要猜測或直接送出。");
        }

        JSONObject semantic = phoneRuntimeExecutor.get("/semantic_screen");
        if (semantic == null || !semantic.optBoolean("success", false)) {
            return runtimeBlocked("RECIPIENT_SCREEN_UNAVAILABLE",
                    "Runtime 目前無法讀取畫面來確認收件人；不要送出，先 inspect_ui 或等待畫面可讀。");
        }

        JSONArray elements = semantic.optJSONArray("elements");
        if (elements == null || elements.length() == 0) {
            return runtimeBlocked("RECIPIENT_CHAT_NOT_VERIFIED",
                    "目前沒有足夠的 Accessibility 結構來確認聊天室；不要送出。");
        }

        int composerTop = Integer.MAX_VALUE;
        boolean composerFound = false;
        boolean composerLooksChatLike = false;
        boolean sendControlVisible = false;

        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element == null || element.optBoolean("sensitive", false)) continue;

            String role = element.optString("role", "");
            String label = element.optString("label", "");
            String hint = element.optString("semanticHint", "");
            String viewId = element.optString("viewId", "");
            String metadata = label + " " + hint + " " + viewId;

            if ("send".equalsIgnoreCase(hint)
                    || UserActionScope.looksLikeSendTarget(metadata)) {
                sendControlVisible = true;
            }

            if (!element.optBoolean("editable", false)
                    && !"text_field".equals(role)) {
                continue;
            }
            if (isSearchLikeMessagingField(element)) continue;

            composerFound = true;
            if (looksLikeChatComposer(element)) composerLooksChatLike = true;
            JSONObject bounds = element.optJSONObject("bounds");
            if (bounds != null) {
                int top = bounds.optInt("top", Integer.MAX_VALUE);
                if (top >= 0 && top < composerTop) composerTop = top;
            }
        }

        // An unlabeled composer is still acceptable when the current screen
        // exposes a semantic Send control. This preserves support for apps with
        // minimal Accessibility metadata while staying stricter than "any EditText".
        if (!composerFound || (!composerLooksChatLike && !sendControlVisible)) {
            return runtimeBlocked("RECIPIENT_CHAT_NOT_VERIFIED",
                    "尚未確認目前畫面是可傳訊息的聊天室。請先搜尋/開啟指定對象，再呼叫 send_text；不要把搜尋框或一般表單當成聊天輸入框。");
        }

        if (composerTop == Integer.MAX_VALUE) {
            composerTop = 1600;
        }
        int headerBottomLimit = Math.max(360, (int) (composerTop * 0.25d));
        boolean recipientMatched = false;

        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element == null || element.optBoolean("sensitive", false)) continue;
            if (element.optBoolean("editable", false)) continue;

            String label = element.optString("label", "");
            if (!recipientLabelMatches(label, recipient)) continue;

            JSONObject bounds = element.optJSONObject("bounds");
            int bottom = bounds == null ? 0 : bounds.optInt("bottom", 0);
            if (bottom <= 0 || bottom <= headerBottomLimit) {
                recipientMatched = true;
                break;
            }
        }

        if (!recipientMatched) {
            return runtimeBlocked("RECIPIENT_TARGET_NOT_VERIFIED",
                    "目前畫面尚未證明是指定收件人的聊天室。請先用 phone_action(SEARCH) 或語意 TAP 開啟該對象；Runtime 驗證成功前不要送出，也不要改猜其他收件人。");
        }

        return new JSONObject()
                .put("success", true)
                .put("recipientVerified", true)
                .put("verificationSource", "ACCESSIBILITY_CURRENT_SCREEN");
    }

    private boolean isSearchLikeMessagingField(JSONObject element) {
        if (element == null) return false;
        String metadata = TextMatch.caseFold(
                element.optString("semanticHint", "") + " "
                        + element.optString("label", "") + " "
                        + element.optString("viewId", ""));
        return metadata.contains("search")
                || metadata.contains("query")
                || metadata.contains("filter")
                || metadata.contains("搜尋")
                || metadata.contains("搜索")
                || metadata.contains("查找");
    }

    private boolean looksLikeChatComposer(JSONObject element) {
        if (element == null) return false;
        String metadata = TextMatch.caseFold(
                element.optString("label", "") + " "
                        + element.optString("semanticHint", "") + " "
                        + element.optString("viewId", ""));
        return metadata.contains("message")
                || metadata.contains("chat")
                || metadata.contains("composer")
                || metadata.contains("reply")
                || metadata.contains("訊息")
                || metadata.contains("消息")
                || metadata.contains("回覆")
                || metadata.contains("回复")
                || metadata.contains("輸入訊息")
                || metadata.contains("输入消息");
    }

    private boolean recipientLabelMatches(String label, String recipient) {
        String left = normalizeRecipientEvidence(label);
        String right = normalizeRecipientEvidence(recipient);
        if (left.isEmpty() || right.isEmpty()) return false;
        if (left.equals(right)) return true;
        // Avoid broad one-character substring matches. Two+ characters are
        // narrow enough for common Chinese names/relationship labels.
        return right.length() >= 2 && left.contains(right);
    }

    private String normalizeRecipientEvidence(String value) {
        return TextMatch.caseFold(value == null ? "" : value)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()\\[\\]]", "")
                .trim();
    }

    private JSONObject sendTextToPhone(JSONObject args) throws Exception {
        String text = args == null ? "" : args.optString("text", "");

        if (userActionScope.blocksNamedRecipientMessagingAction()) {
            return runtimeBlocked("RECIPIENT_TARGET_UNKNOWN",
                    "這一輪看起來要傳給特定對象，但 Runtime 無法從原始指令抽出可驗證的收件人。不要猜收件人；請使用者用『跟 X 說…』或『傳給 X：「…」』明確指定。");
        }
        if (!userActionScope.canSend()) {
            LiveTurnCoordinator.FinalizedTurn finalized =
                    liveTurnCoordinator.latest();
            ensureSendAuthorizationFromFinalized(finalized, args);
        }
        if (!userActionScope.canSend()) {
            return runtimeBlocked(
                    "CURRENT_SCREEN_SEND_NOT_AUTHORIZED",
                    "send_text 只用於最新一句明確要求送出訊息。若使用者只是要在目前可見欄位打字、填入或貼上內容（包含設定、system prompt、表單或聊天輸入框），請改用 phone_action(TYPE)；不要宣稱 Crew 無法一般打字。");
        }

        if (userActionScope.requiresRecipientVerification()) {
            JSONObject recipientVerification = verifyAuthorizedRecipientOnCurrentScreen();
            if (!recipientVerification.optBoolean("success", false)) {
                return recipientVerification;
            }
        }

        PerformanceMetrics.recordTextRouteSend();
        userActionScope.markMessageTransactionHandled();
        userActionScope.consumeSendAuthorization();

        // 0052 active messaging path is intentionally simple:
        // optional TYPE(text) -> SEND_CURRENT.
        if (!text.isEmpty()) {
            JSONObject typed = phoneRuntimeExecutor.typeText(text);
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

        JSONObject reply = phoneRuntimeExecutor.post("/send_current", new JSONObject());
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
        JSONObject reply = phoneRuntimeExecutor.pressKey(key);
        workingContext.recordAction(
                "key:" + key,
                reply.optBoolean("success", false)
                        ? "submitted" : "failed");
        return autoObserveAfterMutation(reply, "press_key");
    }

    /** Explicitly commits the currently focused search field via the IME key. */
    private JSONObject commitSearch() throws Exception {
        JSONObject reply = phoneRuntimeExecutor.post("/commit_search", new JSONObject());
        workingContext.recordAction("search_commit",
                reply.optBoolean("success", false) ? "submitted" : "failed");
        if (reply.optBoolean("success", false)) {
            userActionScope.markSearchCommitted();
        }
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








    private void sendToolResponse(String id, String name, JSONObject result) throws Exception {
        boolean shadowSuccess = result != null && result.optBoolean("success", false);
        String shadowCode = result == null ? "NO_RESULT" : result.optString("error", "");
        if (shadowCode.isEmpty() && result != null) {
            shadowCode = result.optString("stepResult", shadowSuccess ? "OK" : "FAILED");
        }
        String shadowFingerprint = result == null ? "" : result.optString("fingerprint", "");
        shadowAgentRuntime.onToolResult(
                id, name, shadowSuccess, shadowCode, shadowFingerprint);
        if (deckRuntimeController.hasActiveDeck()
                && name.contains("deck")) {
            result.put("modeInstructions", LivePrompt.DECK);
        }

        // 0079: keep the complete Runtime result for logs/task history, but give
        // the weak Live model a tiny, stable phone-control contract.
        final JSONObject modelResult =
                ModelToolResponseAdapter.forModel(name, result);
        JSONObject appPlaybook = result == null ? null : result.optJSONObject("appPlaybook");
        if (appPlaybook != null && appPlaybook.length() > 0) {
            modelResult.put("appPlaybook", appPlaybook);
        }

        JSONArray responses =
                toolCallDispatcher.expandResponses(id, name, modelResult);
        if (webSocket == null || !webSocket.send(new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", responses)).toString())) {
            throw new Exception("工具結果無法傳回 Gemini");
        }
    }














    // 0097 tuning: deliberately easy to start, reluctant to cut a natural pause.
    // Change these only with recorded A/B evidence from a real device.
    private static final int SERVER_VAD_PREFIX_PADDING_MS = 80;
    private static final int SERVER_VAD_SILENCE_DURATION_MS = 450;

    // 0101: while Gemini is audibly speaking, keep a tiny Android-side admission
    // gate in front of server VAD. Runtime does NOT decide the turn and does NOT
    // call triggerLocalInterruption(); it only withholds likely speaker echo until
    // several consecutive frames look like close-range human speech. Gemini
    // remains the authoritative interruption/turn detector once audio is admitted.
    // 0121: residual playback/room noise can occasionally look speech-like for
    // one or two frames. When output is still audible, require a lower-ZCR voiced
    // component as well as persistence before allowing Gemini to interrupt.



    /** 0099 hot-path encoding retained; 0101 reuses it for buffered barge-in frames. */






    private void reportStage(String text) { stage = text; listener.onStatus(text); Log.d(TAG, text); }
    private synchronized void fail(String message, Throwable error) {
        if (!running) return;
        if (error != null) Log.e(TAG, message, error); else Log.e(TAG, message);
        running = false;
        interruptionHandler.removeCallbacks(clearInterruptedFallback);
        liveAudioController.stop(); listener.onStopped(message);
    }

}
