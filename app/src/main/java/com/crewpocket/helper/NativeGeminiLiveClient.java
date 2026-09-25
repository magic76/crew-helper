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
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Gemini Live backed by OkHttp's production WebSocket implementation. */
final class NativeGeminiLiveClient {
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
    private final AppAutonomyStore appAutonomyStore;
    private final TaskRecipeStore taskRecipeStore;
    private final ConversationLoopRecipe conversationLoopRecipe =
            new ConversationLoopRecipe();
    private final DelegatedSendLease delegatedSendLease =
            new DelegatedSendLease();
    private volatile long taskRecipeCandidateGeneration = -1L;
    private volatile String taskRecipeCandidateId = "";
    private final PhoneRuntimeExecutor phoneRuntimeExecutor;
    private final RuntimeToolExecutor runtimeToolExecutor;
    private final LiveAudioController liveAudioController;
    private final GeminiLiveTurnHandler geminiLiveTurnHandler;
    private final ObservationVerificationController observationVerificationController;
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
    private final GeminiLiveConnection liveConnection;
    private String resumptionHandle;
    private boolean reconnecting;
    private volatile long visualHoldUntil;
    private volatile boolean setupReady;
    private long screenFrameSequence;
    // Navigation is deliberately repeatable during a presentation. All other
    // tools keep the conservative 3-run default safety limit.
    // Model-turn ordering remains separate from Agent task-state ownership.
    private final Object agentLock = new Object();
    private final AgentTaskCoordinator agentTaskCoordinator =
            new AgentTaskCoordinator();
    private final AgentResponseCoordinator agentResponseCoordinator;
    private final ToolCallDispatcher toolCallDispatcher;
    private volatile Thread activeToolThread;
    // 0018: a goal can span several execution tasks/follow-ups inside one Live session.
    // Safety/tool budgets remain per AgentTaskRecord; they are NOT shared for the whole call.
    private static final long CONVERSATION_GOAL_IDLE_MS = 45_000L;
    private String conversationGoalId = "";
    private long conversationGoalTouchedAt = 0L;
    private int conversationGoalTaskIndex = 0;
    private String conversationGoalHint = "";
    private String customPrompt = "";
    private final java.util.concurrent.atomic.AtomicBoolean screenCaptureInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ContextPayloadAudit contextPayloadAudit =
            new ContextPayloadAudit();
    private final WorkingContext workingContext = new WorkingContext();
    private final UserActionScope userActionScope = new UserActionScope();
    // 0070: observational-only state projection. It must never gate execution.
    private final ShadowAgentRuntime shadowAgentRuntime = new ShadowAgentRuntime();
    // 0071: authoritative v2 action transaction runtime.
    private final AgentRuntimeV2 agentRuntimeV2 = new AgentRuntimeV2();
    private String authorizationTranscript = "";
    // Incremented for every finalized user utterance (and typed instruction).
    // Tool calls retain the generation that created them, preventing an old
    // model turn from mutating the phone after the user has changed their mind.
    private long userIntentGeneration = 0L;
    // 0100 latency trace + stale same-intent tool guard is owned by
    // AgentTaskCoordinator.
    // 0052: standalone "送出/發送/send" is owned directly by Runtime.
    private volatile boolean runtimeSendCurrentExecuting = false;
    private volatile long runtimeSendGuardUntil = 0L;
    // Finalized user turns are coordinated separately from model/tool frames.
    // Tool calls never grant authority; only finalized user input advances this.
    private final LiveTurnCoordinator liveTurnCoordinator = new LiveTurnCoordinator();
    private final LiveHumanTurnBoundary liveHumanTurnBoundary =
            new LiveHumanTurnBoundary();
    private final Object internalDirectiveTurnLock = new Object();
    private static final long INTERNAL_DIRECTIVE_TOOL_TTL_MS = 8_000L;
    private long pendingInternalDirectiveGeneration = -1L;
    private long pendingInternalDirectiveUntilMs = 0L;
    private final VoiceExecutionGuard voiceExecutionGuard =
            new VoiceExecutionGuard();
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
        this.liveConnection = new GeminiLiveConnection(
                apiKey,
                new GeminiLiveConnection.Listener() {
                    @Override public void onOpened() {
                        try {
                            NativeGeminiLiveClient.this.reconnecting = false;
                            NativeGeminiLiveClient.this.reportStage(
                                    "Gemini WebSocket 已連線，送出設定…");
                            if (!NativeGeminiLiveClient.this.liveConnection.send(
                                    NativeGeminiLiveClient.this.buildSetup())) {
                                throw new Exception("setup 傳送失敗");
                            }
                            NativeGeminiLiveClient.this.reportStage(
                                    "等待 Gemini setupComplete…");
                        } catch (Exception error) {
                            NativeGeminiLiveClient.this.fail(
                                    "設定失敗：" + error.getMessage(), error);
                        }
                    }

                    @Override public void onFrame(String text, boolean binary) {
                        NativeGeminiLiveClient.this.logInboundFrame(text, binary);
                        try {
                            NativeGeminiLiveClient.this.handleJson(text);
                        } catch (Exception error) {
                            NativeGeminiLiveClient.this.fail(
                                    binary
                                            ? "Gemini binary 回覆錯誤：" + error.getMessage()
                                            : "Gemini 回覆錯誤：" + error.getMessage(),
                                    error);
                        }
                    }

                    @Override public void onClosed(int code, String reason) {
                        if (NativeGeminiLiveClient.this.running
                                && !NativeGeminiLiveClient.this.reconnecting) {
                            NativeGeminiLiveClient.this.fail(
                                    "Gemini 已關閉連線（" + code + "）：" + reason,
                                    null);
                        }
                    }

                    @Override public void onFailure(
                            String detail,
                            Throwable error) {
                        NativeGeminiLiveClient.this.fail(
                                "Gemini WebSocket 失敗：" + detail,
                                error);
                    }
                });

        this.notebookToolHandler = new NotebookToolHandler(this.appContext);
        this.appPlaybookStore = new AppPlaybookStore(this.appContext);
        this.appAutonomyStore = new AppAutonomyStore(this.appContext);
        this.taskRecipeStore = new TaskRecipeStore(this.appContext);
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
                        return NativeGeminiLiveClient.this.liveConnection.send(payload);
                    }
                },
                this.contextPayloadAudit);
        this.phoneRuntimeExecutor = new PhoneRuntimeExecutor(
                this.appContext, this.visionController);
        this.runtimeToolExecutor = new RuntimeToolExecutor(
                this.appContext,
                this.notebookToolHandler,
                this.visionController,
                this.phoneRuntimeExecutor);
        this.geminiLiveTurnHandler = new GeminiLiveTurnHandler();
        this.observationVerificationController =
                new ObservationVerificationController(
                        this.phoneRuntimeExecutor,
                        this.agentRuntimeV2,
                        this.shadowAgentRuntime,
                        this.workingContext);
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
                        return NativeGeminiLiveClient.this.liveConnection.isAvailable();
                    }

                    @Override public boolean sendRealtime(String payload) {
                        return NativeGeminiLiveClient.this.liveConnection.send(payload);
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

        this.agentResponseCoordinator = new AgentResponseCoordinator(
                this.agentTaskCoordinator,
                new AgentResponseCoordinator.Host() {
                    @Override public void reportStage(String text) {
                        NativeGeminiLiveClient.this.reportStage(text);
                    }

                    @Override public boolean sendInternalDirective(String text) {
                        return NativeGeminiLiveClient.this
                                .sendInternalAgentDirective(text);
                    }

                    @Override public void finishTask(
                            AgentTaskRecord task,
                            String reason,
                            String finalReply) {
                        NativeGeminiLiveClient.this
                                .finishAgentTask(task, reason, finalReply);
                    }
                });

        this.deckRuntimeController = new DeckRuntimeController(
                new DeckRuntimeController.Host() {
                    @Override public boolean isRunning() {
                        return NativeGeminiLiveClient.this.running;
                    }

                    @Override public boolean hasLiveSession() {
                        return NativeGeminiLiveClient.this.liveConnection.isAvailable();
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

                    @Override public JSONObject waitThenAction(
                            JSONObject args) throws Exception {
                        return NativeGeminiLiveClient.this
                                .waitThenAction(args);
                    }

                    @Override public JSONObject startConversationLoop(
                            JSONObject args) throws Exception {
                        return NativeGeminiLiveClient.this
                                .startConversationLoop(args);
                    }

                    @Override public JSONObject continueConversationLoop(
                            JSONObject args) throws Exception {
                        return NativeGeminiLiveClient.this
                                .continueConversationLoop(args);
                    }

                    @Override public JSONObject stopConversationLoop(
                            JSONObject args) throws Exception {
                        return NativeGeminiLiveClient.this
                                .stopConversationLoop(args);
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


    boolean isRunning() { return running; }
    String getStage() { return stage; }
    String getAudioOutputBackend() {
        return liveAudioController.getAudioOutputBackend();
    }
    boolean canSendVisualFrame() { return running && System.currentTimeMillis() >= visualHoldUntil; }
    boolean isSetupReadyForSelection() {
        return running && setupReady && liveConnection.isAvailable();
    }

    void setAgentMaxSteps(int steps) {
        agentTaskCoordinator.setMaxSteps(steps);
    }
    int getAgentMaxSteps() {
        return agentTaskCoordinator.maxSteps();
    }
    boolean hasActiveAgentTask() {
        return agentTaskCoordinator.hasActive();
    }
    String getAgentTaskStatus() {
        return agentTaskCoordinator.status();
    }
    JSONArray getAgentTaskHistory() {
        return agentTaskCoordinator.historyJson();
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

        // Do not retain tool args, typed text, message bodies, or other
        // potentially sensitive content.
        String hint = agentTaskCoordinator.activeHint();
        boolean hadTask = !hint.isEmpty();

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
            observationVerificationController.resetNoProgress();
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
        return cancelAgentTask(reason, "");
    }

    private boolean cancelAgentTask(String reason, String category) {
        AgentTaskRecord task = category == null || category.trim().isEmpty()
                ? agentTaskCoordinator.cancelActive(reason)
                : agentTaskCoordinator.cancelActive(reason, category);
        if (task == null) return false;

        toolCallDispatcher.clearPending();
        agentResponseCoordinator.clear();
        phoneRuntimeExecutor.cancelActiveRequest();
        Thread worker = activeToolThread;
        if (worker != null) worker.interrupt();

        liveTurnCoordinator.closeOperationalGeneration(
                task.intentGeneration);
        PerformanceMetrics.markAgentTaskFinished(
                task.taskId, task.intentGeneration, "CANCELLED");
        PerformanceMetrics.recordAgentTask(
                Math.max(0L, System.currentTimeMillis() - task.startedAt));
        reportStage("Agent 任務已停止：" + task.endReason);
        shadowAgentRuntime.onTaskCancelled(task.taskId, task.endReason);
        agentRuntimeV2.onTaskCancelled(task.taskId, task.endReason);
        return true;
    }

    private void supersedeActiveAgentTaskForNewUserInstruction(
            String previousUserTurn,
            String newUserTurn) {
        AgentTaskRecord active = agentTaskCoordinator.activeRunning();
        if (active == null) return;

        UserRetryAfterUnconfirmedOutcomePolicy.Decision retry =
                UserRetryAfterUnconfirmedOutcomePolicy.evaluate(
                        previousUserTurn,
                        newUserTurn,
                        System.currentTimeMillis(),
                        active.lastSuccessfulMutationAtMs,
                        active.mutationActions,
                        active.lastTaskState);
        if (retry.retry) {
            synchronized (agentTaskCoordinator.monitor()) {
                if (agentTaskCoordinator.isActive(active)
                        && !active.finished
                        && !active.cancelled) {
                    active.retryIntentFamily = retry.intentFamily;
                }
            }
            cancelAgentTask(
                    "使用者重試尚未確認完成的操作",
                    UserRetryAfterUnconfirmedOutcomePolicy.CATEGORY);
            return;
        }

        cancelAgentTask("新使用者指令取代舊任務");
    }

    /**
     * Start a new finalized user turn without throwing away useful short-term
     * task continuity. The newest turn is authoritative; rootGoal survives only
     * inside the existing 45-second conversation-goal window.
     */
    private void beginNewUserIntent(String userText) {
        beginUserIntent(userText, true);
    }

    private void beginContinuationUserIntent(String userText) {
        beginUserIntent(userText, false);
    }

    private void beginUserIntent(
            String userText,
            boolean supersedeActiveTask) {
        String previousUserTurn =
                workingContext.toJson().optString("latestUserTurn", "");

        liveTurnCoordinator.closeOperationalGeneration(
                userIntentGeneration);
        clearPendingInternalDirectiveTurn();

        // Human foreground ownership always outranks a retained auto-chat lease.
        // The original start command is exempt so it can arm/reuse the loop.
        if (conversationLoopRecipe.isActive()
                && ConversationLoopPolicy.shouldYieldToUserTurn(userText)) {
            releaseConversationLoopForHumanTakeover(
                    "USER_TURN_TAKEOVER",
                    false);
        }

        // A finalized user instruction supersedes any incomplete model-turn
        // bookkeeping from the previous interaction.
        resetCurrentModelTurnState();
        synchronized (injectedAppPlaybooks) {
            injectedAppPlaybooks.clear();
        }
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
            observationVerificationController.resetNoProgress();
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
            contextPayloadAudit.beginTurn(userIntentGeneration);
            PerformanceMetrics.markAgentUserIntent(userIntentGeneration);
            // Calls that have not started belong to the old utterance. An
            // executing call is additionally guarded by its generation below.
            toolCallDispatcher.resetForNewIntent();
            shadowTaskId = agentTaskCoordinator.activeTaskId();
        }

        taskRecipeCandidateGeneration = -1L;
        taskRecipeCandidateId = "";

        shadowAgentRuntime.onUserIntent(
                userIntentGeneration, conversationGoalId, shadowTaskId, startNewCapsule);
        agentRuntimeV2.onUserIntent(
                userIntentGeneration, conversationGoalId, shadowTaskId, startNewCapsule);

        if (supersedeActiveTask) {
            supersedeActiveAgentTaskForNewUserInstruction(
                    previousUserTurn,
                    userText);
        }
        Log.d(TAG, "新的使用者意圖：generation=" + userIntentGeneration
                + " capsule=" + (startNewCapsule ? "NEW" : "CONTINUE")
                + " supersede=" + supersedeActiveTask);
    }

    private void mergeFinalizedVoiceSegmentIntoCurrentIntent(
            String effectiveText,
            String reason) {
        conversationGoalTouchedAt = System.currentTimeMillis();
        workingContext.mergeUserTurnSegment(effectiveText);
        Log.d(
                TAG,
                "Merged finalized voice segment into generation="
                        + userIntentGeneration
                        + " reason="
                        + (reason == null ? "" : reason));
    }

    private boolean isCurrentUserIntent(long generation) {
        synchronized (agentLock) { return generation == userIntentGeneration; }
    }

    private boolean isFinishedIntentGeneration(long generation) {
        return agentTaskCoordinator.isFinishedIntentGeneration(generation);
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
        if (!running || !liveConnection.isAvailable()
                || text == null || text.trim().isEmpty()) return false;
        try {
            String input = text.trim();
            if (audioIncidentRecorder != null) audioIncidentRecorder.markTypedInput(input);

            if (consumePendingUiChoiceInput(input)) return true;
            if (hasPendingUiChoice()) clearPendingUiChoiceSilently();

            beginNewUserIntent(input);
            liveHumanTurnBoundary.forceNewTurn(
                    input, System.currentTimeMillis());
            voiceExecutionGuard.onFinalizedTypedTurn(
                    userIntentGeneration, input);
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
            String payload = new JSONObject().put(
                    "clientContent",
                    new JSONObject()
                            .put("turns", new JSONArray().put(turn))
                            .put("turnComplete", true))
                    .toString();
            boolean sent = liveConnection.send(payload);
            if (sent) {
                contextPayloadAudit.logTypedTurn(
                        userIntentGeneration,
                        ContextPayloadBudget.utf8Bytes(text.trim()),
                        ContextPayloadBudget.utf8Bytes(payload));
                listener.onTranscript("你", text.trim());
            }
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
        if (!running || !setupReady || !liveConnection.isAvailable()) {
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
        liveConnection.start();
    }

    private void connect() {
        if (!running) return;
        liveConnection.connect();
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

    void configureDeckStartup(String mode, String deckId, String workspaceId) {
        deckRuntimeController.configureStartup(mode, deckId, workspaceId);
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
                if (liveConnection.isAvailable()) {
                    byte[] silence = new byte[3200];
                    JSONObject root = new JSONObject();
                    JSONObject audio = new JSONObject();
                    audio.put("mimeType", "audio/pcm;rate=16000");
                    audio.put("data", Base64.encodeToString(silence, Base64.NO_WRAP));
                    root.put("realtimeInput", new JSONObject().put("audio", audio));
                    liveConnection.send(root.toString());
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
        contextPayloadAudit.flushTurn();
        stopConversationDelegation("LIVE_SESSION_STOPPED");
        workingContext.setPendingTask("");

        boolean wasRunning = running;
        correctionHandler.removeCallbacks(clearCorrectionWindow);
        correctionWindowActive = false;
        correctionWindowUntil = 0L;
        correctionTaskHint = "";
        correctionContextPendingInjection = false;
        resetConversationGoal();
        pendingCondition = null;
        observationVerificationController.clearSemanticTransient();
        workingContext.clear();
        cancelAgentTask("通話已結束");
        deckRuntimeController.cancelAutoAdvance();
        resetCurrentModelTurnState();
        running = false;
        setupReady = false;
        interruptionHandler.removeCallbacks(clearInterruptedFallback);
        liveAudioController.stop();
        voiceExecutionGuard.clear();
        liveTurnCoordinator.reset();
        liveHumanTurnBoundary.reset();
        clearPendingInternalDirectiveTurn();
        liveConnection.stop();
        if (wasRunning) listener.onStopped("已結束");
    }

    /** Avoid logging base64 PCM: formatting those large messages can starve audio. */
    private void logInboundFrame(String text, boolean binary) {
        String kind = text.contains("setupComplete") || text.contains("setup_complete") ? "setupComplete"
                : text.contains("toolCall") || text.contains("tool_call") ? "toolCall"
                : text.contains("inlineData") || text.contains("inline_data") ? "audio/modelTurn"
                : text.contains("turnComplete") || text.contains("turn_complete") ? "turnComplete" : "server event";
        Log.d(TAG, "Gemini " + (binary ? "binary " : "") + kind + " (" + text.length() + " chars)");
    }
    private void handleJson(String raw) throws Exception {
        GeminiLiveTurnHandler.Frame frame =
                geminiLiveTurnHandler.parse(raw);

        if (!frame.interimInputText.isEmpty()) {
            liveTurnCoordinator.closeOperationalGeneration(
                    userIntentGeneration);
            clearPendingInternalDirectiveTurn();
            voiceExecutionGuard.onInterimVoice(
                    frame.interimInputText);
        }

        if (frame.resumable && !frame.resumptionHandle.isEmpty()) {
            resumptionHandle = frame.resumptionHandle;
        }

        if (frame.goAway) {
            if (resumptionHandle == null
                    || resumptionHandle.isEmpty()) {
                throw new Exception(
                        "Gemini 要求結束通話，但未提供可續接 session");
            }
            reportStage("🔄 正在延續長通話…");
            reconnecting = true;
            liveConnection.closeForReconnect(
                    "Resuming Gemini Live session");
            new Handler(Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override public void run() {
                            connect();
                        }
                    },
                    120);
            return;
        }

        if (frame.setupComplete) {
            setupReady = true;
            reportStage("🎙️ 已連線，直接說話");
            liveAudioController.start();
            deckRuntimeController.dispatchStartupIfNeeded(
                    setupReady);
            return;
        }

        // Finalized transcription text is authoritative, but one finalized
        // segment is not automatically a brand-new foreground intent. Gemini
        // Live may finalize multiple speech segments while the human experiences
        // one spoken instruction.
        String completeUserInput = frame.inputText;
        if (!completeUserInput.isEmpty()) {
            clearPendingInternalDirectiveTurn();
            if (audioIncidentRecorder != null) {
                audioIncidentRecorder.onVoiceTranscript(
                        completeUserInput);
            }
            listener.onTranscript(
                    "你", completeUserInput);

            if (consumePendingUiChoiceInput(
                    completeUserInput)) {
                return;
            }

            if (hasPendingUiChoice()) {
                clearPendingUiChoiceSilently();
            }

            String effectiveUserInput = completeUserInput;
            VoiceExecutionGuard.TurnDisposition voiceDisposition;

            // A pending sensitive-action confirmation is intentionally a
            // distinct human turn; preserve the existing confirmation lease.
            boolean pendingVoiceConfirmation =
                    !voiceExecutionGuard.pendingSummary().isEmpty();
            if (pendingVoiceConfirmation) {
                voiceDisposition =
                        voiceExecutionGuard.onFinalizedVoiceTurn(
                                userIntentGeneration + 1L,
                                completeUserInput,
                                frame.inputConfidence);
                boolean confirmationContinuation =
                        voiceDisposition
                                != VoiceExecutionGuard.TurnDisposition.NORMAL;
                if (confirmationContinuation) {
                    beginContinuationUserIntent(completeUserInput);
                } else {
                    beginNewUserIntent(completeUserInput);
                }
                liveHumanTurnBoundary.forceNewTurn(
                        completeUserInput,
                        System.currentTimeMillis());
                PerformanceMetrics.recordLiveHumanTurnNewIntent(
                        confirmationContinuation
                                ? "VOICE_CONFIRMATION"
                                : "CONFIRMATION_REPLACED");
            } else {
                AgentTaskRecord activeVoiceTask =
                        agentTaskCoordinator.activeRunning();
                LiveHumanTurnBoundary.Resolution boundary =
                        liveHumanTurnBoundary.resolve(
                                completeUserInput,
                                System.currentTimeMillis(),
                                activeVoiceTask != null,
                                activeVoiceTask == null
                                        ? -1L
                                        : activeVoiceTask.intentGeneration,
                                userIntentGeneration,
                                liveTurnCoordinator.latest().generation,
                                frame.interactionStatus,
                                frame.waitingForInput,
                                frame.interrupted);

                effectiveUserInput = boundary.effectiveText;
                if (boundary.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT) {
                    beginNewUserIntent(effectiveUserInput);
                    PerformanceMetrics.recordLiveHumanTurnNewIntent(
                            boundary.reason);
                } else {
                    mergeFinalizedVoiceSegmentIntoCurrentIntent(
                            effectiveUserInput,
                            boundary.reason);
                    if (boundary.decision
                            == LiveHumanTurnBoundary.Decision
                                    .BIND_CURRENT_GENERATION) {
                        PerformanceMetrics.recordLiveHumanTurnBoundCurrent(
                                boundary.reason);
                    } else {
                        PerformanceMetrics.recordLiveHumanTurnMergedSegment(
                                boundary.reason);
                    }
                }

                voiceDisposition =
                        voiceExecutionGuard.onFinalizedVoiceTurn(
                                userIntentGeneration,
                                effectiveUserInput,
                                frame.inputConfidence);
            }

            authorizationTranscript =
                    effectiveUserInput;
            if (authorizationTranscript.length() > 4096) {
                authorizationTranscript =
                        authorizationTranscript.substring(
                                0, 4096);
            }

            if (voiceDisposition
                    == VoiceExecutionGuard.TurnDisposition.CONFIRMATION_ACCEPTED) {
                workingContext.setPendingTask("");
                reportStage("語音確認完成，等待執行原動作");
            } else {
                userActionScope.updateFromUserText(
                        effectiveUserInput);
                if (voiceDisposition
                        == VoiceExecutionGuard.TurnDisposition.CONFIRMATION_REJECTED) {
                    workingContext.setPendingTask("");
                    reportStage("已取消上一個語音確認");
                }
            }
            recordFinalizedSendAuthorization(
                    effectiveUserInput);
            if (tryHandleRuntimeAppTeaching(
                    effectiveUserInput)) {
                return;
            }
            if (tryHandleRuntimeSendCurrent(
                    effectiveUserInput)) {
                return;
            }
            if (isStopAgentTaskPhrase(
                    effectiveUserInput)) {
                deckRuntimeController.cancelAutoAdvance();
                cancelAgentTask(
                        "使用者語音停止任務");
            } else if (memoryRuleController.processInput(
                    effectiveUserInput)) {
                return;
            }
        }

        boolean responseHasToolCall =
                frame.hasToolCalls();
        boolean responseHasModelAudio = false;
        boolean responseHasModelText = false;
        for (GeminiLiveTurnHandler.ModelPart part
                : frame.modelParts) {
            if (part.hasAudio()) responseHasModelAudio = true;
            if (part.hasText()) responseHasModelText = true;
        }
        boolean substantiveModelProgress =
                LiveModelProgressPolicy.hasSubstantiveProgress(
                        responseHasToolCall,
                        frame.outputText,
                        frame.modelTurnPresent,
                        responseHasModelAudio,
                        responseHasModelText);

        if (responseHasToolCall) {
            authorizationTranscript = "";
            boolean internalDirectiveToolFrame =
                    consumePendingInternalDirectiveToolFrame(
                            userIntentGeneration);
            if (internalDirectiveToolFrame) {
                liveTurnCoordinator.openOperationalGeneration(
                        userIntentGeneration);
            }
            markCurrentModelTurnToolCall(
                    toolCallsAllowDeckNarrationAdvance(
                            frame.toolCalls));
            agentResponseCoordinator.clear();
            for (int i = 0;
                    i < frame.toolCalls.length();
                    i++) {
                JSONObject call =
                        frame.toolCalls.optJSONObject(i);
                if (call != null) {
                    if (internalDirectiveToolFrame) {
                        try {
                            call.put(
                                    "_crew_internal_directive",
                                    true);
                        } catch (Exception ignored) {}
                    }
                    executeToolAsync(call);
                }
            }
        }

        liveHumanTurnBoundary.observeServerState(
                frame.turnComplete,
                frame.interactionStatus,
                frame.waitingForInput);

        if (!frame.serverPresent) return;

        if (frame.interrupted) {
            long interruptedIndex =
                    ++serverInterruptedCount;
            long lastBargeInAdmissionAt =
                    liveAudioController
                            .getLastBargeInAdmissionAt();
            long sinceBargeInMs =
                    lastBargeInAdmissionAt <= 0L
                            ? -1L
                            : Math.max(
                                    0L,
                                    System.currentTimeMillis()
                                            - lastBargeInAdmissionAt);
            Log.w(
                    TAG,
                    "0120 serverContent.interrupted #"
                            + interruptedIndex
                            + " aiSpeaking="
                            + aiSpeaking
                            + " allowVoiceInterruption="
                            + allowVoiceInterruption
                            + " sinceBargeInAdmitMs="
                            + sinceBargeInMs
                            + " outputState="
                            + liveAudioController
                                    .getLastAudioOutputState());
            liveAudioController.stopPlayback();
            if (aiSpeaking) {
                aiSpeaking = false;
                listener.onSpeakingChanged(false);
            }
            interruptionHandler.removeCallbacks(
                    clearInterruptedFallback);
            interruptedCurrentTurn = false;
            resetCurrentModelTurnState();
            return;
        }

        boolean agentAwaitingIncompleteAction =
                isAgentAwaitingIncompleteAction();
        boolean clearAgentWatchdog =
                LiveModelProgressPolicy.shouldClearAgentWatchdog(
                        responseHasToolCall,
                        substantiveModelProgress,
                        agentAwaitingIncompleteAction);

        if (clearAgentWatchdog
                && !responseHasToolCall) {
            // A completed/answer-ready task may progress via final text/audio.
            // An incomplete phone task must produce the next tool call instead.
            agentResponseCoordinator.onModelResponse();
        } else if (!responseHasToolCall
                && frame.modelTurnPresent) {
            Log.d(
                    TAG,
                    substantiveModelProgress
                            && agentAwaitingIncompleteAction
                            ? "Model output without next action; keep Agent action watchdog armed"
                            : "Empty modelTurn envelope; keep Agent response watchdog armed");
        }

        if (!frame.outputText.isEmpty()
                && !runtimeSendCurrentExecuting
                && !shouldWithholdUnverifiedAgentReply()) {
            if (!responseHasToolCall) {
                Log.d(
                        TAG,
                        "0047 transcript received; waiting for Gemini PCM");
            }
            listener.onTranscript(
                    "Gemini", frame.outputText);
        }

        if (frame.modelTurnPresent) {
            authorizationTranscript = "";
            if (substantiveModelProgress) {
                visualHoldUntil =
                        System.currentTimeMillis() + 1800;
            }

            boolean withholdForVerification =
                    shouldWithholdUnverifiedAgentReply()
                            || runtimeSendCurrentExecuting;
            if (!interruptedCurrentTurn
                    && !withholdForVerification) {
                if (substantiveModelProgress
                        && !aiSpeaking) {
                    aiSpeaking = true;
                    listener.onSpeakingChanged(true);
                }

                for (GeminiLiveTurnHandler.ModelPart part
                        : frame.modelParts) {
                    if (part.hasAudio()) {
                        byte[] responsePcm =
                                Base64.decode(
                                        part.audioBase64,
                                        Base64.DEFAULT);
                        noteCurrentModelTurnAudioReceived(
                                responsePcm.length);
                        boolean audioAccepted =
                                liveAudioController.enqueueAudio(
                                        responsePcm);
                        if (!responseHasToolCall
                                && audioAccepted) {
                            markCurrentModelTurnSpeech();
                            liveHumanTurnBoundary.noteModelSpeech();
                            markAgentUserVisibleReplyProduced();
                        }
                    }

                    if (part.hasText()) {
                        if (!responseHasToolCall) {
                            appendAgentFinalText(part.text);
                        }
                        listener.onTranscript(
                                "Gemini", part.text);
                    }
                }
            } else if (withholdForVerification) {
                Log.d(
                        TAG,
                        "暫緩未驗證的 Agent 回覆，等待 inspect_ui 證據");
            }
        }

        if (frame.turnComplete) {
            boolean deckNarrationTurn =
                    shouldAutoAdvanceDeckAfterCurrentTurn();
            boolean deckTurnWasInterrupted =
                    interruptedCurrentTurn;

            liveAudioController.finishTurn();
            visualHoldUntil =
                    System.currentTimeMillis() + 1000;
            interruptedCurrentTurn = false;
            interruptionHandler.removeCallbacks(
                    clearInterruptedFallback);
            if (aiSpeaking) {
                aiSpeaking = false;
                listener.onSpeakingChanged(false);
            }

            if (shouldEvaluateAgentTaskAtTurnComplete()) {
                agentResponseCoordinator.finishIfAwaitingModel();
            }
            closeOperationalGenerationIfIdle();
            clearExpiredInternalDirectiveTurn();
            deckRuntimeController.onNarrationTurnComplete(
                    deckNarrationTurn,
                    deckTurnWasInterrupted);
            resetCurrentModelTurnState();
        }

    }

    /**
     * 0054: auto-advance after audible narration. A Deck-only tool call may
     * precede that narration in the same model turn; unrelated tools still block it.
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

        taskRecipeCandidateGeneration = -1L;
        taskRecipeCandidateId = "";
        if (userActionScope.canSend()
                || userActionScope.blocksNamedRecipientMessagingAction()) {
            return;
        }

        JSONObject progress = workingContext.toProgressJson();
        String currentPackage = progress.optString("currentApp", "");
        TaskRecipeStore.Match match =
                taskRecipeStore.findMatching(text, currentPackage);
        if (match != null && !match.id.isEmpty()) {
            taskRecipeCandidateGeneration = userIntentGeneration;
            taskRecipeCandidateId = match.id;
            Log.i(TAG, "TaskRecipe candidate generation="
                    + userIntentGeneration + " steps=" + match.stepCount);
        }
    }

    /**
     * Gemini Live may emit send_text in one frame and the finalized user
     * transcription in the next. Give only SEND a short grace window so the
     * authoritative user transcript can arrive. The model tool call itself
     * never grants permission.
     */
    private long awaitFinalizedOperationalGeneration(
            String requestedName,
            JSONObject requestedArgs,
            long queuedGeneration,
            boolean internalDirective) {
        if (!requiresFinalizedOperationalTurn(
                requestedName, requestedArgs)) {
            return queuedGeneration;
        }

        LiveTurnCoordinator.FinalizedTurn latest =
                liveTurnCoordinator.latest();
        LiveTurnOrderingPolicy.Decision decision =
                LiveTurnOrderingPolicy.decide(
                        internalDirective,
                        queuedGeneration,
                        latest.generation,
                        liveTurnCoordinator
                                .isOperationalGenerationOpen(
                                        queuedGeneration));

        if (decision
                == LiveTurnOrderingPolicy.Decision
                        .BYPASS_INTERNAL) {
            PerformanceMetrics.recordLiveTurnOrderingInternalBypass();
            return queuedGeneration;
        }
        if (decision
                == LiveTurnOrderingPolicy.Decision
                        .USE_QUEUED_GENERATION) {
            return queuedGeneration;
        }
        if (decision
                == LiveTurnOrderingPolicy.Decision
                        .USE_NEXT_FINALIZED_GENERATION) {
            PerformanceMetrics.recordLiveTurnOrderingReconciled(
                    queuedGeneration,
                    latest.generation,
                    0L);
            return latest.generation;
        }

        long waitStarted = System.currentTimeMillis();
        PerformanceMetrics.recordLiveTurnOrderingWait();
        LiveTurnCoordinator.FinalizedTurn finalized =
                liveTurnCoordinator.awaitOperationalOrNext(
                        queuedGeneration, 2500L);
        long waitedMs =
                Math.max(
                        0L,
                        System.currentTimeMillis() - waitStarted);
        if (finalized.generation == queuedGeneration
                && liveTurnCoordinator
                        .isOperationalGenerationOpen(
                                queuedGeneration)) {
            PerformanceMetrics.recordLiveTurnOrderingReconciled(
                    queuedGeneration,
                    finalized.generation,
                    waitedMs);
            Log.i(
                    TAG,
                    "Operational tool bound to finalized current generation wait="
                            + waitedMs
                            + "ms generation="
                            + queuedGeneration
                            + " name="
                            + requestedName);
            return queuedGeneration;
        }

        if (finalized.generation == queuedGeneration + 1L) {
            PerformanceMetrics.recordLiveTurnOrderingReconciled(
                    queuedGeneration,
                    finalized.generation,
                    waitedMs);
            Log.i(
                    TAG,
                    "Operational tool reconciled after finalized transcript wait="
                            + waitedMs
                            + "ms queuedGeneration="
                            + queuedGeneration
                            + " finalizedGeneration="
                            + finalized.generation
                            + " name="
                            + requestedName);
            return finalized.generation;
        }

        PerformanceMetrics.recordLiveTurnOrderingTimeout(
                queuedGeneration,
                finalized.generation,
                waitedMs);
        return Long.MIN_VALUE;
    }

    private boolean requiresFinalizedOperationalTurn(
            String requestedName,
            JSONObject requestedArgs) {
        if (SemanticPhoneAction.TOOL_NAME.equals(requestedName)) {
            return true;
        }
        return "send_text".equals(requestedName)
                || "start_conversation_loop".equals(requestedName)
                || "stop_conversation_loop".equals(requestedName)
                || "end_voice_session".equals(requestedName)
                || "wait_then_action".equals(requestedName)
                || "cancel_schedule".equals(requestedName);
    }

    private long awaitFinalizedSendAuthorization(
            String requestedName,
            JSONObject requestedArgs,
            long queuedGeneration) {
        if (!"send_text".equals(requestedName)) {
            return queuedGeneration;
        }

        AgentTaskRecord delegatedTask =
                agentTaskCoordinator.active();
        if (delegatedTask != null
                && conversationLoopRecipe.canSend()
                && delegatedSendLease.canSend(
                        delegatedTask.taskId)) {
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
                || userActionScope.isMessageCommitDispatched()
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

    private void setConversationWaitingVisual(boolean waiting) {
        try {
            FloatingBubbleManager.getInstance(appContext)
                    .setConversationWaiting(waiting);
        } catch (Exception ignored) {}
    }

    private void stopConversationDelegation(String reason) {
        String stopReason =
                reason == null || reason.isEmpty()
                        ? "CONVERSATION_DELEGATION_STOPPED"
                        : reason;
        conversationLoopRecipe.stop(stopReason);
        delegatedSendLease.revoke(stopReason);
        setConversationWaitingVisual(false);
        workingContext.setPendingTask("");
    }

    private void releaseConversationLoopForHumanTakeover(
            String reason,
            boolean cancelLoopOwnedTask) {
        if (!conversationLoopRecipe.isActive()
                && !delegatedSendLease.isActive()) {
            return;
        }

        AgentTaskRecord active = agentTaskCoordinator.activeRunning();
        boolean loopOwnedTask = isConversationLoopOwnedTask(active);

        stopConversationDelegation(
                reason == null || reason.isEmpty()
                        ? "HUMAN_TAKEOVER"
                        : reason);

        if (cancelLoopOwnedTask && loopOwnedTask) {
            cancelAgentTask("使用者接管自動聊天");
        }
        reportStage("自動聊天已讓出控制");
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
        setup.put("model", "models/gemini-3.8-live");
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
        JSONArray modelTools =
                LiveToolCatalog.build(
                        deckRuntimeController.isSessionMode(),
                        deckRuntimeController.isCreateStartup(),
                        deckRuntimeController.isPresentStartup(),
                        deckRuntimeController.hasWorkspaceStartup());
        setup.put("tools", new JSONArray().put(new JSONObject().put(
                "functionDeclarations", modelTools)));
        String customPrompt = this.customPrompt;
        String deckInstruction = "";
        if (deckRuntimeController.isSessionMode()) {
            deckInstruction = deckRuntimeController.isCreateStartup()
                    ? LivePrompt.DECK_CREATE
                    : LivePrompt.DECK;
        }
        String personality =
                "Speaking personality: " + personalityInstruction();
        String baseInstruction = LivePrompt.CORE + "\n" + personality
                + (deckInstruction.isEmpty() ? "" : "\n" + deckInstruction);

        String sessionContext = SessionContextSnapshot.systemInstruction(
                appContext,
                currentForegroundPackageName());
        if (!sessionContext.isEmpty()) {
            baseInstruction = baseInstruction + "\n" + sessionContext;
        }

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
        setup.put("systemInstruction", new JSONObject().put(
                "parts",
                new JSONArray().put(
                        new JSONObject().put("text", baseInstruction))));
        root.put("setup", setup);

        String payload = root.toString();
        contextPayloadAudit.logSetupComponent(
                "core_prompt",
                ContextPayloadBudget.utf8Bytes(LivePrompt.CORE));
        contextPayloadAudit.logSetupComponent(
                "personality",
                ContextPayloadBudget.utf8Bytes(personality));
        contextPayloadAudit.logSetupComponent(
                "deck_instruction",
                ContextPayloadBudget.utf8Bytes(deckInstruction));
        contextPayloadAudit.logSetupComponent(
                "session_context",
                ContextPayloadBudget.utf8Bytes(sessionContext));
        contextPayloadAudit.logSetupComponent(
                "custom_prompt",
                ContextPayloadBudget.utf8Bytes(
                        customPrompt == null ? "" : customPrompt.trim()));
        contextPayloadAudit.logSetupComponent(
                "app_playbook",
                ContextPayloadBudget.utf8Bytes(initialAppPlaybook));
        contextPayloadAudit.logSetupComponent(
                "tool_schema",
                ContextPayloadBudget.utf8Bytes(modelTools.toString()));
        contextPayloadAudit.logSetupTotal(
                ContextPayloadBudget.utf8Bytes(baseInstruction),
                ContextPayloadBudget.utf8Bytes(modelTools.toString()),
                ContextPayloadBudget.utf8Bytes(payload));
        return payload;
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
        boolean internalDirectiveTool =
                call.optBoolean(
                        "_crew_internal_directive",
                        false);

        // Gemini Live may emit the tool frame before the authoritative
        // finalized user transcript. User-originated operational tools must
        // bind to an explicitly open finalized generation. Runtime internal
        // directives are marked separately and bypass this user-turn barrier.
        long reconciledGeneration = awaitFinalizedOperationalGeneration(
                requestedName,
                requestedArgs,
                callIntentGeneration,
                internalDirectiveTool);
        if (reconciledGeneration == Long.MIN_VALUE) {
            try {
                sendToolResponse(
                        id,
                        requestedName,
                        runtimeBlocked(
                                "OPERATIONAL_TURN_NOT_FINALIZED",
                                "工具 frame 先於 authoritative finalized user turn 到達。Runtime 已阻止沿用上一輪 generation；不要向使用者宣告功能失敗，等待目前使用者語句 finalized 後由新 turn 繼續。"));
            } catch (Exception ignored) {}
            return;
        }
        reconciledGeneration = awaitFinalizedSendAuthorization(
                requestedName, requestedArgs, reconciledGeneration);
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
        // A matching learned recipe may replace the model's first semantic
        // phone mutation with one deterministic Runtime run. The user turn
        // itself selected the recipe; the model never grants extra authority.
        if (tryExecuteTaskRecipeFastPath(
                id,
                requestedName,
                requestedArgs,
                callIntentGeneration)) {
            return;
        }

        // Manual element overlay is a human assist, not a model recovery
        // strategy. The finalized user turn must have explicitly requested it.
        if (isElementReferenceOpenToolRequest(
                requestedName, requestedArgs)
                && !userActionScope
                        .consumeElementReferenceAuthorization()) {
            try {
                sendToolResponse(
                        id,
                        requestedName,
                        runtimeBlocked(
                                "ELEMENT_REFERENCE_USER_REQUEST_REQUIRED",
                                "顯示元素只能在使用者明確要求時開啟。不要把人工元素選擇當成一般失敗 fallback；"
                                        + "先 inspect_ui，再用 semantic target / trusted app autonomy 繼續低風險操作。"));
            } catch (Exception ignored) {}
            return;
        }

        // 0034: model-facing semantic action -> existing trusted Runtime tool.
        final SemanticPhoneAction.Resolution semantic;
        try {
            SemanticPhoneAction.Resolution resolved =
                    SemanticPhoneAction.resolve(
                            requestedName,
                            requestedArgs);

            // Tool-intent correction must happen BEFORE preflight / Inspector /
            // verification so every layer agrees on what actually executed.
            // Weak Live turns sometimes choose TYPE while pursuing a search or
            // MEDIA:PLAY goal. Treat that as SEARCH instead of executing a
            // hidden late remap that still reports TYPE back to the model.
            String goalIntent = workingContext
                    .toProgressJson()
                    .optString("goalIntent", "");
            boolean typeShouldBeSearch =
                    ToolIntentRoutingPolicy.shouldRemapTypeToSearch(
                            resolved.runtimeName,
                            userActionScope.shouldAutoCommitSearch(),
                            goalIntent,
                            !resolved.runtimeArgs
                                    .optString("text", "")
                                    .trim()
                                    .isEmpty());
            if (typeShouldBeSearch) {
                String query =
                        resolved.runtimeArgs.optString("text", "").trim();
                if (!query.isEmpty()) {
                    JSONObject searchArgs = new JSONObject()
                            .put("action", "SEARCH")
                            .put("text", query);
                    resolved = SemanticPhoneAction.resolve(
                            SemanticPhoneAction.TOOL_NAME,
                            searchArgs);
                }
            }
            semantic = resolved;
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

        if (conversationLoopRecipe.isActive()
                && !conversationLoopRecipe.allowsTool(name)) {
            try {
                JSONObject blocked = runtimeBlocked(
                        "CONVERSATION_LOOP_TOOL_BLOCKED",
                        "持續對話租約仍有效，但這個工具不屬於目前聊天室的允許操作。"
                                + "工具沒有執行。不要把 guard/error code 告訴使用者；"
                                + "若確實有新訊息，只用 send_text 並直接帶回覆文字，"
                                + "不要先 TYPE、不要另外點送出；若只是 UI noise，"
                                + "用 continue_conversation_loop；需要畫面資訊時只用 inspect_ui。");
                if (conversationLoopRecipe.canSend()) {
                    blocked.put("taskState", "IN_PROGRESS")
                            .put("recoverable", true)
                            .put("nextRequirement",
                                    "SEND_TEXT_OR_CONTINUE_CONVERSATION_LOOP");
                }
                sendToolResponse(id, requestedName, blocked);
            } catch (Exception ignored) {}
            return;
        }

        AgentTaskRecord leaseOwnerTask =
                agentTaskCoordinator.active();
        String leaseOwnerTaskId =
                leaseOwnerTask == null ? "" : leaseOwnerTask.taskId;
        final boolean conversationLeaseSend =
                "send_text".equals(name)
                        && conversationLoopRecipe.canSend()
                        && delegatedSendLease.canSend(leaseOwnerTaskId);

        if ("send_text".equals(name)
                && !conversationLeaseSend
                && !userActionScope.canSend()
                && !SendAuthorization.isExplicitTypeOnlyRequest(
                        liveTurnCoordinator.latest().text)) {
            try {
                sendToolResponse(
                        id,
                        requestedName,
                        delegatedSessionRequiredResult());
            } catch (Exception ignored) {}
            return;
        }

        final String voiceTargetMetadata =
                args.optString("label", "") + " "
                        + args.optString("target", "") + " "
                        + args.optString("id", "") + " "
                        + args.optString("element_id", "") + " "
                        + args.optString("semanticHint", "");
        final boolean userBypassesMessageVoiceGate =
                VoiceExecutionPolicy.bypassesMessageVoiceGate(
                        name,
                        voiceTargetMetadata,
                        AppConfig.isMessageSendNoConfirmationEnabled(appContext));
        VoiceExecutionGuard.Preflight voicePreflight =
                conversationLeaseSend || userBypassesMessageVoiceGate
                        ? VoiceExecutionGuard.Preflight.allow()
                        : voiceExecutionGuard.preflight(
                                callIntentGeneration,
                                name,
                                args);
        if (!voicePreflight.allowed) {
            JSONObject blocked = new JSONObject();
            try {
                blocked.put("success", false)
                        .put("blockedByRuntime", true)
                        .put("stepResult", "STEP_FAILED")
                        .put("taskState", "NEED_USER")
                        .put("error", voicePreflight.code)
                        .put("instruction", voicePreflight.instruction);
                if (!voicePreflight.confirmationSummary.isEmpty()) {
                    blocked.put(
                            "confirmationSummary",
                            voicePreflight.confirmationSummary);
                    workingContext.setPendingTask("VOICE_CONFIRMATION");
                }
                sendToolResponse(id, requestedName, blocked);
            } catch (Exception ignored) {}
            return;
        }

        final boolean runtimeV2Enforced =
                isMutationTool(name)
                && AgentRuntimeRollout.shouldEnforce(name, observationVerificationController.latestObservation());

        AgentRuntimeV2.PreflightResult runtimePreflight = null;
        if (runtimeV2Enforced) {
            runtimePreflight = agentRuntimeV2.preflight(
                    callIntentGeneration,
                    userIntentGeneration,
                    id,
                    name,
                    buildAgentSignature(name, args),
                    observationVerificationController.latestObservation());
            if (!runtimePreflight.allowed()) {
                String code = runtimePreflight.code;
                if (runtimePreflight.decision == AgentRuntimeV2.PreflightDecision.REQUIRE_OBSERVE) {
                    JSONObject blocked = runtimeBlocked(
                            "OBSERVE_REQUIRED",
                            "上一個相同操作仍待驗證或剛失敗。先 inspect_ui 一次；不要原樣重做 mutation。");
                    try {
                        blocked.put("taskState", "IN_PROGRESS");
                        blocked.put("nextRequirement", "INSPECT_UI");
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

        final AgentTaskRecord stabilityTask = peekActiveAgentTask();
        final JSONObject stabilityBlock = runtimeV2Enforced
                ? null
                : agentStabilityPreflight(stabilityTask, name, args);
        if (stabilityBlock != null && stabilityTask != null) {
            try {
                stabilityTask.addStep(name, stabilityBlock);
                sendToolResponse(id, requestedName, stabilityBlock);
                if (stabilityTask.blockedReason != null) {
                    agentResponseCoordinator.requestConclusion(stabilityTask, stabilityTask.blockedReason);
                } else {
                    stabilityTask.awaitingModel = true;
                    agentResponseCoordinator.scheduleWatchdog(stabilityTask);
                    reportStage("Runtime 要求先重新觀察畫面，再改用不同方法");
                }
            } catch (Exception ignored) {}
            return;
        }

        final AgentTaskRecord visualTask =
                agentTaskCoordinator.activeRunning();
        if (visualTask != null
                && AgentTaskLifecyclePolicy
                        .shouldSuppressRepeatedVisualObservation(
                                name,
                                visualTask.consecutiveVisualObservations)) {
            try {
                JSONObject blocked = runtimeBlocked(
                        "REPEATED_VISUAL_OBSERVATION",
                        "目前 goal 已連續取得兩次 visual observation，Runtime 不再重複截同一階段的畫面。"
                                + "請使用現有 screen/context/recentSteps 選下一個不同語意動作；"
                                + "若目前證據確實無法完成 goal，簡短回報缺少的控制，不要再 inspect。");
                blocked.put("taskState", "IN_PROGRESS")
                        .put("recoverable", true)
                        .put("nextRequirement", "TRY_ALTERNATIVE");
                sendToolResponse(id, requestedName, blocked);
                synchronized (agentTaskCoordinator.monitor()) {
                    if (agentTaskCoordinator.isActive(visualTask)
                            && !visualTask.finished
                            && !visualTask.cancelled) {
                        visualTask.awaitingModel = true;
                        visualTask.watchdogPrompted = false;
                    }
                }
                agentResponseCoordinator.scheduleWatchdog(visualTask);
                reportStage("Runtime 已阻止重複看同一階段畫面，改用目前證據繼續");
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
            agentResponseCoordinator.requestConclusion(task, task.blockedReason);
            return;
        }
        shadowAgentRuntime.onActionStarted(
                id,
                callIntentGeneration,
                conversationGoalId,
                task.taskId,
                requestedName,
                name,
                observationVerificationController.lastObservedScreenFingerprint(),
                ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE);
        if (runtimeV2Enforced) {
            agentRuntimeV2.onActionStarted(
                    id, callIntentGeneration, conversationGoalId, task.taskId,
                    requestedName, name,
                    runtimePreflight == null ? "" : runtimePreflight.actionHash,
                    ActionExpectation.forRuntimeAction(name),
                    observationVerificationController.latestObservation());
        }
        PerformanceMetrics.markAgentToolStarted(
                task.taskId, task.intentGeneration, name);
        if (isMutationTool(name) && audioIncidentRecorder != null) {
            audioIncidentRecorder.captureBeforeFirstMutation(task.taskId, name, args);
        }
        final JSONObject recipeBeforeContext = workingContext.toJson();
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
                    agentResponseCoordinator.scheduleWatchdog(task);
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
                                AgentRuntimeRollout.rolloutLabel(name, observationVerificationController.latestObservation()));
            }
            if (semantic.semantic) {
                result.put("semanticAction", semantic.semanticAction)
                        .put("resolvedByRuntime", name);
                String semanticTarget =
                        semantic.runtimeArgs.optString("semantic_target", "").trim();
                if (!semanticTarget.isEmpty()) {
                    result.put("semanticTarget", semanticTarget);
                }
                String modelTarget =
                        modelTargetForSemanticStep(
                                semantic.semanticAction,
                                name,
                                semantic.runtimeArgs);
                if (!modelTarget.isEmpty()) {
                    result.put("modelTarget", modelTarget);
                }
            }
            if (runtimeV2Enforced) {
                ExecutionEvidence evidence = executionEvidenceFromResult(name, result);
                agentRuntimeV2.onActionExecuted(id, evidence);
                ActionVerificationResult verification = agentRuntimeV2.verifyAndRecord(
                        id, evidence, observationVerificationController.latestObservation());
                applyV2VerificationContract(result, verification);
            }
            if (isPhoneContextTool(name)) attachCurrentAppPlaybook(result);
            updateTaskCompletionContract(
                    task, name, result, runtimeV2Enforced);
            if (!runtimeV2Enforced) {
                updateAgentStabilityAfterResult(
                        task, name, args, result);
            }
            task.lastToolName = name == null ? "" : name;
            task.lastTaskState = result.optString("taskState", "").trim();
            task.lastCompletionEvidence =
                    result.optString("completionEvidence", "").trim();
            if (isMutationTool(name)
                    && (result.optBoolean("success", false)
                        || "STEP_OK".equals(result.optString("stepResult", "")))) {
                task.lastSuccessfulMutationAtMs = System.currentTimeMillis();
            }
            task.prematureModelReplies = 0;
            task.captureRecipeStep(name, args, result, recipeBeforeContext);
            task.addStep(name, result);
            sendToolResponse(id, requestedName, result);
            PerformanceMetrics.markAgentToolResultSent(
                    task.taskId, task.intentGeneration, name);
            if (task.blockedReason != null) {
                agentResponseCoordinator.requestConclusion(task, task.blockedReason);
            } else if (shouldSuspendAgentForUser(result)) {
                String suspendedState =
                        result.optString("taskState", "");
                if ("WAITING_BACKGROUND".equals(suspendedState)) {
                    boolean conversationExternalWait =
                            conversationLoopRecipe.isActive()
                                    && delegatedSendLease.isActive()
                                    && ("send_text".equals(name)
                                            || "continue_conversation_loop".equals(name));
                    if (conversationExternalWait) {
                        agentResponseCoordinator.clear();
                        agentTaskCoordinator.suspendForExternalWait(
                                task,
                                "WAITING_FOR_EXTERNAL_MESSAGE",
                                "對話模式：等待外部回覆");
                        liveTurnCoordinator.closeOperationalGeneration(
                                task.intentGeneration);
                        reportStage(
                                "Agent 等待外部回覆 · 同一任務暫停中");
                    } else {
                        synchronized (agentTaskCoordinator.monitor()) {
                            task.awaitingModel = false;
                            task.watchdogPrompted = false;
                            agentResponseCoordinator.clear();
                            task.status =
                                    "背景等待已交給 Runtime";
                        }
                        finishAgentTask(
                                task,
                                "背景等待交給 Runtime",
                                "");
                        reportStage(
                                "背景等待已交給 Runtime");
                    }
                } else {
                    synchronized (agentTaskCoordinator.monitor()) {
                        task.awaitingModel = false;
                        task.watchdogPrompted = false;
                        agentResponseCoordinator.clear();
                        task.status = "等待使用者選擇搜尋結果";
                    }
                    reportStage(task.status);
                }
            } else {
                task.awaitingModel = true;
                agentResponseCoordinator.scheduleWatchdog(task);
                reportStage(result.optBoolean("answerFastPath", false)
                        ? "畫面已有搜尋結果，等待 Gemini 直接回答…"
                        : "Agent 第 " + task.steps + " / " + agentTaskCoordinator.maxSteps()
                                + " 步：已取得「" + name + "」結果，正在決定下一步");
            }
        } catch (Exception error) { reportStage("Agent 工具結果回灌失敗：" + error.getMessage()); }
    }

    private boolean tryExecuteTaskRecipeFastPath(
            String id,
            String requestedName,
            JSONObject requestedArgs,
            long callIntentGeneration) {
        if (conversationLoopRecipe.isActive()
                || !SemanticPhoneAction.TOOL_NAME.equals(requestedName)
                || callIntentGeneration != taskRecipeCandidateGeneration
                || taskRecipeCandidateId == null
                || taskRecipeCandidateId.isEmpty()
                || userActionScope.canSend()
                || userActionScope.blocksNamedRecipientMessagingAction()) {
            return false;
        }

        final String recipeId = taskRecipeCandidateId;
        taskRecipeCandidateGeneration = -1L;
        taskRecipeCandidateId = "";

        final JSONObject recipe = taskRecipeStore.getRecipe(recipeId);
        if (recipe == null) return false;

        final AgentTaskRecord task = beginAgentStep(
                "task_recipe",
                new JSONObject());
        if (task == null || task.cancelled || task.finished) {
            return false;
        }

        final long generation = callIntentGeneration;
        JSONObject result;
        try {
            reportStage("⚡ Crew 熟悉流程：Runtime 連續執行");
            result = TaskRecipeRuntime.run(
                    recipe,
                    new TaskRecipeRuntime.Host() {
                        @Override public boolean isCurrentIntent() {
                            return isCurrentUserIntent(generation)
                                    && !task.cancelled
                                    && !task.finished;
                        }

                        @Override public JSONObject observeCurrentScreen()
                                throws Exception {
                            JSONObject screen =
                                    observationVerificationController
                                            .readSemanticScreenQuietly();
                            if (screen != null
                                    && screen.optBoolean("success", false)) {
                                workingContext.observe(
                                        screen.optString("package", ""),
                                        screen.optString("fingerprint", ""),
                                        screen.optString("stableScreenKey", ""));
                            }
                            return screen == null
                                    ? new JSONObject()
                                    : screen;
                        }

                        @Override public JSONObject executeStep(
                                String tool,
                                JSONObject args) throws Exception {
                            if ("launch_app".equals(tool)) return launchApp(args);
                            if ("tap_screen".equals(tool)) return tap(args);
                            if ("search_current_app".equals(tool)) {
                                return searchCurrentApp(args);
                            }
                            if ("commit_search".equals(tool)) {
                                return commitSearch();
                            }
                            if ("swipe_screen".equals(tool)) return swipe(args);
                            if ("press_key".equals(tool)) return pressKey(args);
                            return new JSONObject()
                                    .put("success", false)
                                    .put("error", "TASK_RECIPE_UNSUPPORTED_STEP");
                        }
                    });
        } catch (Exception error) {
            result = new JSONObject();
            try {
                result.put("success", false)
                        .put("fastPath", true)
                        .put("fastPathState", "FALLBACK")
                        .put("error", "TASK_RECIPE_RUNTIME_ERROR")
                        .put("message",
                                "熟悉流程執行失敗；改回一般方式繼續。");
            } catch (Exception ignored) {}
        }

        boolean success = result.optBoolean("success", false);
        taskRecipeStore.recordRun(recipeId, success);
        try {
            if (success) {
                result.put("taskState", "DONE")
                        .put("completionEvidence", "TASK_RECIPE_COMPLETED")
                        .put("nextRequirement", "NONE");
            } else if (!result.has("taskState")) {
                result.put("taskState", "IN_PROGRESS");
            }
            task.lastToolName = "task_recipe";
            task.lastTaskState = result.optString("taskState", "").trim();
            task.lastCompletionEvidence =
                    result.optString("completionEvidence", "").trim();
            task.prematureModelReplies = 0;
            task.addStep("task_recipe", result);
            sendToolResponse(id, requestedName, result);
            task.awaitingModel = true;
            agentResponseCoordinator.scheduleWatchdog(task);
            PerformanceMetrics.markAgentToolResultSent(
                    task.taskId, task.intentGeneration, "task_recipe");
            reportStage(success
                    ? "⚡ 熟悉流程完成，等待 Gemini 簡短回覆"
                    : "熟悉流程不符合目前畫面，已交回 Gemini");
        } catch (Exception error) {
            reportStage("Task Recipe 結果回灌失敗：" + error.getMessage());
        }
        return true;
    }

    private boolean shouldSuspendAgentForUser(JSONObject result) {
        if (result != null) {
            String state = result.optString("taskState", "");
            if ("WAITING_USER".equals(state)
                    || "WAITING_BACKGROUND".equals(state)) {
                return true;
            }
        }
        return hasPendingUiChoice();
    }

    private void resumeAgentAfterUserChoice(String status) {
        AgentTaskRecord task =
                agentTaskCoordinator.resumeAfterUserChoice(status);
        if (task != null) {
            reportStage(task.status);
            agentResponseCoordinator.scheduleWatchdog(task);
        }
    }

    private AgentTaskRecord beginAgentStep(String name, JSONObject args) {
        synchronized (agentTaskCoordinator.monitor()) {
            AgentTaskRecord existing = agentTaskCoordinator.active();
            if (existing == null || existing.finished) {
                touchConversationGoal("最近工具：" + name);
                conversationGoalTaskIndex++;

                JSONObject recipeContext = workingContext.toJson();
                String recipeGoal = recipeContext.optString(
                        "latestUserTurn",
                        recipeContext.optString("rootGoal", ""));
                AgentTaskCoordinator.StartResult started =
                        agentTaskCoordinator.ensureActive(
                                userIntentGeneration,
                                conversationGoalId,
                                conversationGoalTaskIndex,
                                recipeGoal,
                                recipeContext.optString("currentApp", ""));
                existing = started.task;
                if (started.created) {
                    reportStage("Agent 任務開始：" + existing.taskId);
                }
            }

            AgentTaskRecord task = existing;
            agentResponseCoordinator.clear();
            String signature = buildAgentSignature(name, args);
            if (task == null || task.cancelled) return null;

            int maxSteps = agentTaskCoordinator.maxSteps();
            boolean loopOwnedTool =
                    conversationLoopRecipe.isActive()
                            && conversationLoopRecipe.allowsTool(name);
            long nowMs = System.currentTimeMillis();
            AgentTaskLifecyclePolicy.StepDecision decision =
                    AgentTaskLifecyclePolicy.evaluateStep(
                            nowMs,
                            loopOwnedTool
                                    ? nowMs
                                    : task.effectiveStartedAt(nowMs),
                            loopOwnedTool ? 0 : task.steps,
                            maxSteps,
                            name,
                            loopOwnedTool ? 0 : task.getToolCount(name),
                            loopOwnedTool ? 0 : task.mutationActions,
                            loopOwnedTool ? 0 : task.observationActions);
            if (!decision.allowed) task.blockedReason = decision.blockedReason;

            if (task.blockedReason == null) {
                boolean observation = isObservationTool(name);
                boolean visualObservation =
                        AgentTaskLifecyclePolicy.isVisualObservationTool(name);
                if (observation) task.observationActions++;
                else task.steps++;
                if (visualObservation) {
                    task.consecutiveVisualObservations++;
                } else {
                    task.consecutiveVisualObservations = 0;
                }
                task.lastSignature = signature;
                task.incrementTool(name);
                if (isMutationTool(name)) task.mutationActions++;
                task.awaitingModel = false;
                task.userVisibleReplyProducedSinceLastAction = false;
                task.finalSpeechRetryCount = 0;
                task.status = observation
                        ? "Agent 觀察 " + task.observationActions + " / "
                                + AgentTaskLifecyclePolicy.MAX_OBSERVATION_ACTIONS
                                + "：正在執行「" + name + "」"
                        : "Agent 第 " + task.steps + " / "
                                + maxSteps + " 步：正在執行「" + name + "」";
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
    private void updateTaskCompletionContract(
            AgentTaskRecord task,
            String name,
            JSONObject result,
            boolean runtimeV2Enforced) {
        if (task == null || result == null) return;
        synchronized (agentTaskCoordinator.monitor()) {
            if (task.cancelled || task.finished) return;
            boolean succeeded = result.optBoolean("success", false)
                    || "STEP_OK".equals(result.optString("stepResult", ""));
            try {
                if (succeeded
                        && AgentTaskLifecyclePolicy.isOneShotCompletionTool(name)
                        && !result.has("taskState")) {
                    result.put("taskState", "DONE")
                            .put("completionEvidence", "ONE_SHOT_TOOL_COMPLETED")
                            .put("nextRequirement", "NONE");
                }

                if (isMutationTool(name) && succeeded) {
                    JSONObject after = result.optJSONObject("after");
                    boolean freshAfter = after != null && after.optBoolean("fresh", false)
                            && "AUTO_AFTER_ACTION".equals(after.optString("source", ""));

                    String domainState = result.optString("taskState", "").trim();
                    boolean waitingUser = "WAITING_USER".equals(domainState);
                    boolean blocked = "BLOCKED".equals(domainState);
                    String v2Verification =
                            result.optString("verificationStatus", "").trim();
                    boolean v2Pending =
                            runtimeV2Enforced
                                    && "PENDING".equals(v2Verification);

                    // RuntimeV2 owns verification when enforced. Legacy task
                    // flags only mirror its explicit PENDING decision; they no
                    // longer invent an extra inspect requirement after a V2
                    // VERIFIED/LIKELY result.
                    task.requiresPostActionInspection =
                            runtimeV2Enforced
                                    ? v2Pending
                                    : (waitingUser || blocked
                                        ? false
                                        : !freshAfter);
                    task.postActionInspectionPrompted = false;

                    if (!result.has("actionStatus")) result.put("actionStatus", "EXECUTED");

                    String semanticTarget =
                            result.optString("semanticTarget", "").trim();
                    boolean verifiedNavigationStart =
                            freshAfter
                                    && result.optBoolean("screenChanged", false)
                                    && GoogleMapsSemanticContract.START_NAVIGATION
                                            .equals(semanticTarget);

                    if (verifiedNavigationStart) {
                        result.put("taskState", "DONE")
                                .put("completionEvidence",
                                        "MAPS_START_NAVIGATION_SCREEN_CHANGED")
                                .put("nextRequirement", "NONE");
                        task.requiresPostActionInspection = false;
                    } else {
                        if (!result.has("taskState")) {
                            result.put(
                                    "taskState",
                                    runtimeV2Enforced && !v2Pending
                                            ? "EVIDENCE_AVAILABLE"
                                            : (freshAfter
                                                ? "EVIDENCE_AVAILABLE"
                                                : "IN_PROGRESS"));
                        }
                        if (!result.has("completionEvidence")) {
                            result.put(
                                    "completionEvidence",
                                    runtimeV2Enforced && !v2Pending
                                            ? "RUNTIME_V2_VERIFIED"
                                            : (freshAfter
                                                ? "AUTO_AFTER_ACTION"
                                                : "PENDING_POST_ACTION_INSPECTION"));
                        }
                        if (!result.has("nextRequirement")) {
                            result.put(
                                    "nextRequirement",
                                    runtimeV2Enforced && !v2Pending
                                            ? "CONTINUE_GOAL"
                                            : (freshAfter
                                                ? "CONTINUE_GOAL"
                                                : "INSPECT_UI"));
                        }
                    }
                }

                if (("inspect_ui".equals(name) || "take_screenshot".equals(name))
                        && succeeded) {
                    if (task.requiresPostActionInspection) {
                        task.requiresPostActionInspection = false;
                        task.postActionInspectionPrompted = false;
                    }

                    String domainState = result.optString("taskState", "").trim();
                    boolean waitingUser = "WAITING_USER".equals(domainState);
                    boolean blocked = task.blockedReason != null
                            || "BLOCKED".equals(domainState);
                    JSONObject progressForCompletion =
                            workingContext.toProgressJson();
                    boolean answerFastPath =
                            !conversationLoopRecipe.isActive()
                                    && InformationAnswerFastPathPolicy.shouldOffer(
                                    name,
                                    true,
                                    task.getToolCount("search_current_app"),
                                    task.getToolCount("commit_search"),
                                    blocked,
                                    waitingUser,
                                    progressForCompletion.optString("goal", ""),
                                    progressForCompletion.optString("rootGoal", ""));

                    if (answerFastPath) {
                        result.put("answerFastPath", true)
                                .put("taskState", "ANSWER_READY")
                                .put("completionEvidence", "SEARCH_RESULT_SCREEN_INSPECTED")
                                .put("nextRequirement", "ANSWER_IF_SUFFICIENT")
                                .put("instruction",
                                        InformationAnswerFastPathPolicy.instruction());
                        PerformanceMetrics.markAgentAnswerReady(
                                task.taskId, task.intentGeneration);
                    } else {
                        if (!result.has("taskState")) {
                            result.put("taskState", "EVIDENCE_AVAILABLE");
                        }
                        if (!result.has("completionEvidence")) {
                            result.put("completionEvidence", "CURRENT_SCREEN_INSPECTED");
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean isMutationTool(String name) {
        return AgentTaskLifecyclePolicy.isMutationTool(name);
    }

    private AgentTaskRecord peekActiveAgentTask() {
        return agentTaskCoordinator.activeRunning();
    }

    private JSONObject agentStabilityPreflight(AgentTaskRecord task, String name, JSONObject args) {
        if (task == null || task.finished || task.cancelled || !isMutationTool(name)) return null;
        synchronized (agentTaskCoordinator.monitor()) {
            JSONObject progress = workingContext.toProgressJson();
            String currentPackage =
                    progress.optString("currentApp", "");
            String recoveryMetadata =
                    name + " " + (args == null ? "" : args.toString());
            boolean trustedRecovery =
                    AppAutonomyPolicy.mayRecoverWithoutObservation(
                            appAutonomyStore.isTrusted(currentPackage),
                            name,
                            recoveryMetadata);

            AgentTaskLifecyclePolicy.StabilityDecision decision =
                    AgentTaskLifecyclePolicy.evaluateStability(
                            task.finished,
                            task.cancelled,
                            name,
                            trustedRecovery
                                    ? false
                                    : task.requireObservationAfterFailure,
                            trustedRecovery
                                    ? false
                                    : observationVerificationController
                                            .semanticObserveRequired(),
                            buildAgentSignature(name, args),
                            task.lastFailedMutationSignature,
                            task.failedMutationScreenFingerprint,
                            observationVerificationController.latestFingerprint());
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
        synchronized (agentTaskCoordinator.monitor()) {
            if ("inspect_ui".equals(name) && result.optBoolean("success", false)) {
                task.requireObservationAfterFailure = false;
                task.stabilityBlocks = 0;
                String observed = result.optString("fingerprint", observationVerificationController.latestFingerprint());
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
            task.failedMutationScreenFingerprint = observationVerificationController.latestFingerprint();
            task.consecutiveMutationFailures++;

            if (!observationVerificationController.blocksOutcomeReconciliation(result)) {
                task.requireObservationAfterFailure = true;
            }

            if (AgentTaskLifecyclePolicy.shouldStopAfterMutationFailure(
                    task.consecutiveMutationFailures)) {
                task.blockedReason = "連續 3 次手機操作失敗，Runtime 已停止繼續試錯；請回報目前畫面與卡點，不要再呼叫工具。";
            }
        }
    }

    private String modelTargetForSemanticStep(
            String semanticAction,
            String runtimeName,
            JSONObject args) {
        String action = semanticAction == null
                ? "" : semanticAction.trim().toUpperCase();
        JSONObject safe = args == null ? new JSONObject() : args;

        String semanticTarget =
                safe.optString("semantic_target", "").trim();
        if (!semanticTarget.isEmpty()) return semanticTarget;

        if ("SEARCH".equals(action)) return "QUERY";
        if ("TYPE".equals(action)) return "EDITABLE_FIELD";
        if ("SCROLL".equals(action)) {
            String direction = safe.optString("direction", "").trim();
            return direction.isEmpty() ? "SCREEN" : "SCROLL:" + direction;
        }
        if ("BACK".equals(action) || "HOME".equals(action)) {
            return action;
        }
        if ("OPEN_APP".equals(action)
                || "launch_app".equals(runtimeName)) {
            return AgentTapDiagnostic.sanitizeTarget(
                    safe.optString("app", safe.optString("target", "")));
        }
        if ("TAP".equals(action)
                || "tap_screen".equals(runtimeName)
                || "tap_element".equals(runtimeName)) {
            return AgentTapDiagnostic.sanitizeTarget(
                    safe.optString(
                            "label",
                            safe.optString("target", "")));
        }
        return "";
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
                    && launchedPackage.equals(observationVerificationController.latestObservation().packageName)) {
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
                    && GoogleMapsRuntimeAdapter.PACKAGE_NAME.equals(observationVerificationController.latestObservation().packageName));
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
                        .put("nextRequirement", "INSPECT_UI");
            } else {
                result.put("success", false).put("stepResult", "STEP_FAILED");
            }
        } catch (Exception ignored) {}
    }

    /** Internal control turn: do not pollute the user-facing live transcript. */
    private boolean sendInternalAgentDirective(String text) {
        try {
            if (!liveConnection.isAvailable()) return false;
            JSONObject part = new JSONObject().put("text", text);
            JSONObject turn = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(part));
            String payload = new JSONObject()
                    .put("clientContent", new JSONObject()
                            .put("turns", new JSONArray().put(turn))
                            .put("turnComplete", true))
                    .toString();
            int outboundBytes =
                    ContextPayloadBudget.utf8Bytes(payload);
            contextPayloadAudit.logInternalDirective(
                    userIntentGeneration,
                    ContextPayloadBudget.utf8Bytes(text),
                    outboundBytes);
            contextPayloadAudit.logBudget(
                    "internal_directive",
                    outboundBytes,
                    ContextPayloadBudget.INTERNAL_DIRECTIVE_BYTES);
            boolean sent = liveConnection.send(payload);
            if (!sent) {
                Log.w(TAG, "Internal directive send failed: websocket unavailable or closed");
            }
            return sent;
        } catch (Exception error) {
            Log.w(TAG, "Agent 結論指令傳送失敗：" + error.getMessage());
            return false;
        }
    }

    private boolean sendConversationWakeDirective(
            String text) {
        markPendingInternalDirectiveTurn(
                userIntentGeneration);
        boolean sent = sendInternalAgentDirective(text);
        if (!sent) {
            clearPendingInternalDirectiveTurn();
        }
        return sent;
    }

    private void markPendingInternalDirectiveTurn(
            long generation) {
        synchronized (internalDirectiveTurnLock) {
            pendingInternalDirectiveGeneration = generation;
            pendingInternalDirectiveUntilMs =
                    System.currentTimeMillis()
                            + INTERNAL_DIRECTIVE_TOOL_TTL_MS;
        }
    }

    private boolean consumePendingInternalDirectiveToolFrame(
            long generation) {
        synchronized (internalDirectiveTurnLock) {
            long now = System.currentTimeMillis();
            if (pendingInternalDirectiveGeneration != generation
                    || now > pendingInternalDirectiveUntilMs) {
                if (now > pendingInternalDirectiveUntilMs) {
                    pendingInternalDirectiveGeneration = -1L;
                    pendingInternalDirectiveUntilMs = 0L;
                }
                return false;
            }
            pendingInternalDirectiveGeneration = -1L;
            pendingInternalDirectiveUntilMs = 0L;
            return true;
        }
    }

    private void clearPendingInternalDirectiveTurn() {
        synchronized (internalDirectiveTurnLock) {
            pendingInternalDirectiveGeneration = -1L;
            pendingInternalDirectiveUntilMs = 0L;
        }
    }

    private void clearExpiredInternalDirectiveTurn() {
        synchronized (internalDirectiveTurnLock) {
            if (pendingInternalDirectiveUntilMs > 0L
                    && System.currentTimeMillis()
                            > pendingInternalDirectiveUntilMs) {
                pendingInternalDirectiveGeneration = -1L;
                pendingInternalDirectiveUntilMs = 0L;
            }
        }
    }

    private void closeOperationalGenerationIfIdle() {
        AgentTaskRecord active =
                agentTaskCoordinator.active();
        if (active == null
                || active.finished
                || active.cancelled
                || active.suspended) {
            liveTurnCoordinator.closeOperationalGeneration(
                    userIntentGeneration);
        }
    }







    private void appendAgentFinalText(String text) {
        agentTaskCoordinator.appendFinalText(text);
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
            currentModelTurnBlocksDeckAutoAdvance = false;
            currentModelTurnProducedSpeech = false;
            currentModelTurnReceivedAudio = false;
        }
    }

    private void markAgentUserVisibleReplyProduced() {
        AgentTaskCoordinator.TaskIdentity identity =
                agentTaskCoordinator.markUserVisibleReplyProduced();
        if (identity.available()) {
            PerformanceMetrics.markAgentFinalSpeech(
                    identity.taskId, identity.intentGeneration);
        }
    }

    private boolean shouldWithholdUnverifiedAgentReply() {
        return agentTaskCoordinator.shouldWithholdUnverifiedReply();
    }

    private boolean isAgentAwaitingIncompleteAction() {
        AgentTaskRecord task = agentTaskCoordinator.active();
        if (task == null
                || !task.awaitingModel
                || task.finished
                || task.cancelled) {
            return false;
        }
        return !AgentTaskLifecyclePolicy.canFinishAfterModelReply(
                task.lastTaskState,
                task.lastToolName,
                task.requiresPostActionInspection,
                task.blockedReason != null,
                task.mutationActions);
    }

    private boolean isElementReferenceOpenToolRequest(
            String requestedName,
            JSONObject requestedArgs) {
        if (!SemanticPhoneAction.TOOL_NAME.equals(
                requestedName)) {
            return false;
        }
        JSONObject args = requestedArgs == null
                ? new JSONObject()
                : requestedArgs;
        if (!"TAP".equalsIgnoreCase(
                args.optString("action", ""))) {
            return false;
        }
        return ElementReferenceCommand.isOpenRequest(
                args.optString("target", ""));
    }

    private JSONObject applyMediaPlaybackCompletion(
            JSONObject observed,
            String targetMetadata,
            String currentPackage,
            boolean mediaPlayCandidate,
            boolean musicActiveBefore) {
        if (observed == null || !mediaPlayCandidate) {
            return observed == null ? new JSONObject() : observed;
        }

        JSONObject after = observed.optJSONObject("after");
        String afterPackage =
                after == null
                        ? currentPackage
                        : after.optString(
                                "package",
                                currentPackage);
        boolean musicActiveAfter =
                waitForMusicActiveAfterTap(
                        musicActiveBefore);
        boolean uiPlaying =
                MediaPlaybackCompletionPolicy
                        .uiIndicatesPlaying(
                                after == null
                                        ? ""
                                        : after.toString());
        boolean actionSucceeded =
                observed.optBoolean("success", false);

        String goalIntent =
                workingContext.toProgressJson()
                        .optString("goalIntent", "");
        boolean screenChanged =
                observed.optBoolean("screenChanged", false);

        if (MediaPlaybackCompletionPolicy.shouldComplete(
                afterPackage,
                targetMetadata,
                actionSucceeded,
                musicActiveBefore,
                musicActiveAfter,
                uiPlaying,
                goalIntent,
                screenChanged)) {
            try {
                observed.put("taskState", "DONE")
                        .put(
                                "completionEvidence",
                                uiPlaying
                                        ? "MEDIA_UI_PLAYING"
                                        : "MEDIA_PLAYBACK_BECAME_ACTIVE")
                        .put("nextRequirement", "NONE")
                        .put("mediaPlaybackActive", true)
                        .put("verified", true);
            } catch (Exception ignored) {}
        }
        return observed;
    }

    private boolean isMusicActive() {
        if (appContext == null) return false;
        try {
            android.media.AudioManager audio =
                    (android.media.AudioManager)
                            appContext.getSystemService(
                                    Context.AUDIO_SERVICE);
            return audio != null && audio.isMusicActive();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean waitForMusicActiveAfterTap(
            boolean activeBefore) {
        if (activeBefore) return isMusicActive();
        long[] delays = new long[] {120L, 220L, 360L};
        for (long delay : delays) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (isMusicActive()) return true;
        }
        return false;
    }

    /**
     * 0045 Final Speech Contract.
     * Silent termination is rejected: either speak one short final result or
     * continue with exactly one next tool.
     */
    private void finishAgentTask(
            AgentTaskRecord task,
            String reason,
            String finalReply) {
        boolean shouldLearnRecipe =
                "任務完成".equals(reason)
                        && task != null
                        && task.blockedReason == null
                        && task.canSaveRecipe();

        agentResponseCoordinator.clear();
        if (!agentTaskCoordinator.finish(task, reason, finalReply)) {
            return;
        }
        liveTurnCoordinator.closeOperationalGeneration(
                task.intentGeneration);
        conversationGoalTouchedAt = System.currentTimeMillis();

        if (shouldLearnRecipe) {
            JSONObject saved = taskRecipeStore.rememberSuccessful(
                    task.recipeGoal,
                    task.recipeStartPackage,
                    task.recipeStepsJson());
            if (saved.optBoolean("success", false)) {
                Log.i(TAG, "TaskRecipe learned steps="
                        + saved.optInt("stepCount", 0)
                        + " total=" + saved.optInt("recipeCount", 0));
            }
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
        int interval = (int) args.optDouble("interval_seconds", 60);
        int duration = (int) args.optDouble("duration_minutes", 10);
        String cond = args.optString("target_condition", "");
        String lbl = args.optString("label", "畫面巡檢");
        ScheduledTaskManager mgr =
                ScheduledTaskManager.getInstance(appContext);
        ScheduledTaskManager.ScheduledTask task =
                mgr.startScreenMonitor(
                        lbl, interval, duration, cond, true);
        return new JSONObject()
                .put("success", true)
                .put("task", task.toJson())
                .put("message", "已啟動畫面監控：" + lbl);
    }

    private JSONObject startConversationLoop(JSONObject args)
            throws Exception {
        JSONObject safe = args == null ? new JSONObject() : args;
        int maxReplies = safe.optInt("max_replies", 10);
        int timeoutMinutes = safe.optInt("timeout_minutes", 15);

        JSONObject chat = verifyCurrentChatOnScreen();
        if (!chat.optBoolean("success", false)) {
            return chat.put(
                    "instruction",
                    "請先停留在要聊天的聊天室畫面，再啟動自動聊天。Runtime 不會搜尋或切換對象。");
        }

        AgentTaskRecord ownerTask =
                agentTaskCoordinator.active();
        if (ownerTask == null
                || ownerTask.finished
                || ownerTask.cancelled) {
            return runtimeBlocked(
                    "DELEGATED_TASK_MISSING",
                    "目前沒有可綁定的 Agent task；不要送訊息，重新從使用者目前目標建立任務。");
        }

        ConversationLoopRecipe.StartResult started =
                conversationLoopRecipe.start(
                        "",
                        userIntentGeneration,
                        maxReplies,
                        timeoutMinutes);
        if (!started.success) {
            return runtimeBlocked(
                    started.code,
                    "無法建立目前聊天室的持續對話模式。");
        }
        delegatedSendLease.start(
                ownerTask.taskId,
                ownerTask.intentGeneration,
                maxReplies,
                timeoutMinutes);

        workingContext.setPendingTask("CONVERSATION_LOOP");
        setConversationWaitingVisual(false);
        workingContext.recordAction(
                "conversation_loop",
                "model_started_current_chat");
        reportStage("✓ 已啟動目前聊天室自動聊天");
        try {
            FloatingBubbleManager.getInstance(appContext)
                    .showRuntimeUiState(
                            RuntimeUiState.success(
                                    "自動聊天已啟動",
                                    "目前聊天室 · "
                                            + timeoutMinutes
                                            + " 分鐘 / 最多 "
                                            + maxReplies
                                            + " 則"));
        } catch (Exception ignored) {}
        return conversationLoopStatusJson()
                .put("success", true)
                .put("taskState", "IN_PROGRESS")
                .put("nextRequirement", "CONTINUE_GOAL")
                .put("conversationLoop", "READY_TO_SEND")
                .put("instruction",
                        "持續對話已綁定目前聊天視窗。不要搜尋聯絡人或切換聊天室；ACTIVE lease 已授權在目前聊天室後續回覆。");
    }

    private JSONObject continueConversationLoop(JSONObject args)
            throws Exception {
        AgentTaskRecord ownerTask = agentTaskCoordinator.active();
        String ownerTaskId = ownerTask == null ? "" : ownerTask.taskId;
        if (!conversationLoopRecipe.isActive()
                || !delegatedSendLease.canSend(ownerTaskId)) {
            return runtimeBlocked(
                    "DELEGATED_SESSION_REQUIRED",
                    "目前沒有有效的 delegated chat lease。若使用者的當前目標仍是持續代聊，呼叫 start_conversation_loop 建立 task-scoped lease；不要要求使用者重複授權。");
        }

        JSONObject semantic =
                phoneRuntimeExecutor.get("/semantic_screen");
        String fingerprint = semantic == null
                ? "" : semantic.optString("fingerprint", "");
        if (!conversationLoopRecipe.rearmWait(fingerprint)) {
            return runtimeBlocked(
                    "CONVERSATION_LOOP_CANNOT_WAIT",
                    "對話租約已停止或到期。");
        }

        setConversationWaitingVisual(true);
        armConversationLoopWaitAsync();
        return conversationLoopStatusJson()
                .put("success", true)
                .put("taskState", "WAITING_BACKGROUND")
                .put("conversationLoop", "WAITING_FOR_MESSAGE")
                .put("instruction",
                        "Runtime 已重新掛上 Accessibility event wait。不要 poll/inspect，等 Runtime 喚醒。");
    }

    private JSONObject stopConversationLoop(JSONObject args)
            throws Exception {
        boolean wasActive =
                conversationLoopRecipe.isActive()
                        || delegatedSendLease.isActive();
        stopConversationDelegation("MODEL_OR_USER_STOP");
        return conversationLoopStatusJson()
                .put("success", true)
                .put("taskState", "DONE")
                .put("conversationLoop", "STOPPED")
                .put("message",
                        wasActive
                                ? "已停止持續對話模式"
                                : "目前沒有進行中的持續對話模式");
    }

    private JSONObject conversationLoopStatusJson() {
        JSONObject out = new JSONObject();
        try {
            out.put(
                            "state",
                            conversationLoopRecipe.state().name())
                    .put(
                            "scope",
                            "CURRENT_CHAT")
                    .put(
                            "sentReplies",
                            conversationLoopRecipe.sentReplies())
                    .put(
                            "maxReplies",
                            conversationLoopRecipe.maxReplies())
                    .put(
                            "expiresAtMs",
                            conversationLoopRecipe.expiresAtMs())
                    .put(
                            "sendLeaseActive",
                            delegatedSendLease.isActive())
                    .put(
                            "sendLeaseSent",
                            delegatedSendLease.sentSends())
                    .put(
                            "sendLeaseMax",
                            delegatedSendLease.maxSends());
        } catch (Exception ignored) {}
        return out;
    }

    private void armConversationLoopWaitAsync() {
        final long epoch = conversationLoopRecipe.epoch();
        new Thread(new Runnable() {
            @Override public void run() {
                long revision = 0L;
                try {
                    JSONObject initial =
                            phoneRuntimeExecutor.post(
                                    "/wait_ui_change",
                                    new JSONObject()
                                            .put("after_revision", -1L)
                                            .put("timeout_ms", 0L),
                                    2500);
                    revision = initial.optLong("revision", 0L);
                } catch (Exception ignored) {}

                while (running
                        && conversationLoopRecipe.isActive()
                        && conversationLoopRecipe.isWaiting()
                        && conversationLoopRecipe.epoch() == epoch) {
                    JSONObject event = null;
                    try {
                        event = phoneRuntimeExecutor.post(
                                "/wait_ui_change",
                                new JSONObject()
                                        .put("after_revision", revision)
                                        .put("timeout_ms", 5000L),
                                7000);
                    } catch (Exception ignored) {}

                    if (conversationLoopRecipe.epoch() != epoch
                            || !conversationLoopRecipe.isWaiting()) {
                        return;
                    }
                    if (event == null) continue;
                    revision = event.optLong("revision", revision);
                    if (!event.optBoolean("changed", false)) continue;

                    if (event.optBoolean("humanTextEdit", false)) {
                        releaseConversationLoopForHumanTakeover(
                                "USER_MANUAL_TEXT_EDIT",
                                true);
                        return;
                    }

                    try { Thread.sleep(350L); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    JSONObject semantic = null;
                    try {
                        semantic =
                                phoneRuntimeExecutor.get(
                                        "/semantic_screen");
                    } catch (Exception ignored) {}
                    if (semantic == null
                            || !semantic.optBoolean("success", false)) {
                        continue;
                    }

                    String fingerprint =
                            semantic.optString("fingerprint", "");
                    if (!conversationLoopRecipe
                            .markMessagePending(fingerprint)) {
                        continue;
                    }

                    setConversationWaitingVisual(false);
                    dispatchConversationLoopWakeWhenAgentAvailable();
                    return;
                }

                if (!conversationLoopRecipe.isActive()) {
                    setConversationWaitingVisual(false);
                }
            }
        }, "crew-conversation-loop-wait").start();
    }

    private void dispatchConversationLoopWakeWhenAgentAvailable() {
        while (running
                && conversationLoopRecipe.isActive()
                && conversationLoopRecipe.state()
                        == ConversationLoopRecipe.State.MESSAGE_PENDING) {
            AgentTaskRecord active =
                    agentTaskCoordinator.activeRunning();
            ConversationLoopWakePolicy.Decision wakeDecision =
                    ConversationLoopWakePolicy.decide(
                            active != null,
                            isConversationLoopOwnedTask(active));

            // Do not hijack an unrelated foreground task. The incoming-message
            // event is already retained as MESSAGE_PENDING, so simply wait for
            // that task to finish instead of dropping the event.
            if (wakeDecision
                    == ConversationLoopWakePolicy.Decision
                            .DEFER_FOR_FOREGROUND_TASK) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            AgentTaskRecord task = active;
            if (wakeDecision
                    == ConversationLoopWakePolicy.Decision
                            .CREATE_BACKGROUND_TASK) {
                if (isFinishedIntentGeneration(userIntentGeneration)) {
                    advanceRuntimeGenerationForConversationWake();
                }

                synchronized (agentTaskCoordinator.monitor()) {
                    task = agentTaskCoordinator.activeRunning();
                    if (task == null) {
                        touchConversationGoal("Conversation Loop wake");
                        conversationGoalTaskIndex++;

                        JSONObject context = workingContext.toJson();
                        AgentTaskCoordinator.StartResult started =
                                agentTaskCoordinator.ensureActive(
                                        userIntentGeneration,
                                        conversationGoalId,
                                        conversationGoalTaskIndex,
                                        "CONVERSATION_LOOP_WAKE",
                                        context.optString("currentApp", ""));
                        task = started.task;
                        if (task != null && started.created) {
                            task.recipeEligible = false;
                            task.recipeIneligibleReason =
                                    "BACKGROUND_CONVERSATION_WAKE";
                            reportStage(
                                    "對話模式：建立背景喚醒任務");
                        }
                    }
                }
            }

            if (task == null || task.finished || task.cancelled) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            AgentTaskRecord resumed =
                    agentTaskCoordinator.resumeExternalWait(
                            "對話模式：外部回覆已到，恢復同一 Agent 任務");
            if (resumed != null) {
                task = resumed;
            } else {
                synchronized (agentTaskCoordinator.monitor()) {
                    if (task.finished || task.cancelled) continue;
                    task.awaitingModel = true;
                    task.watchdogPrompted = false;
                    task.userVisibleReplyProducedSinceLastAction = false;
                    task.finalSpeechRetryCount = 0;
                    task.status =
                            "對話模式：偵測到聊天室變化，等待檢查新訊息";
                }
            }

            reportStage(task.status);
            boolean sent = sendConversationWakeDirective(
                    "【CONVERSATION LOOP WAKE】Runtime 偵測到目前聊天視窗有新的 Accessibility 變化。"
                            + "ACTIVE Conversation Loop lease 已授權在目前聊天室持續回覆；不要說無法發送，不要要求新的 user turn，也不要逐則詢問確認。"
                            + "現在只呼叫一次 inspect_ui；它已同時提供 fresh screenshot 與 semantic fallback。"
                            + "若確實有新的對方訊息，自行理解上下文、自然組一則簡短回覆並直接用 send_text 送出；"
                            + "send_text 會完成輸入與送出，不要先 TYPE、不要另外點送出按鈕。"
                            + "若只是自己的訊息、typing indicator 或其他 UI noise，呼叫 continue_conversation_loop 重新等待。"
                            + "若 Runtime 擋下一個工具，依回傳提示改用 send_text 或 continue_conversation_loop，不要把 guard code 告訴使用者。"
                            + "不要搜尋聯絡人、不要切換聊天室、不要輪詢。");
            if (sent) {
                agentResponseCoordinator.scheduleWatchdog(task);
                return;
            }

            // Keep the loop alive if Live temporarily cannot accept the wake.
            String fingerprint =
                    conversationLoopRecipe.baselineFingerprint();
            if (conversationLoopRecipe.rearmWait(fingerprint)) {
                setConversationWaitingVisual(true);
                agentTaskCoordinator.suspendForExternalWait(
                        task,
                        "WAKE_DIRECTIVE_FAILED",
                        "對話模式：喚醒失敗，重新等待外部回覆");
                reportStage("對話模式：喚醒失敗，已重新等待");
                armConversationLoopWaitAsync();
            }
            return;
        }
    }

    private boolean isConversationLoopOwnedTask(AgentTaskRecord task) {
        if (task == null || task.finished || task.cancelled) return false;
        if (task.status != null
                && task.status.contains("對話模式")) {
            return true;
        }
        return "WAITING_BACKGROUND".equals(task.lastTaskState)
                && ("send_text".equals(task.lastToolName)
                        || "continue_conversation_loop".equals(
                                task.lastToolName));
    }

    private void advanceRuntimeGenerationForConversationWake() {
        synchronized (agentLock) {
            userIntentGeneration++;
            contextPayloadAudit.beginTurn(userIntentGeneration);
            toolCallDispatcher.resetForNewIntent();
        }
        shadowAgentRuntime.onUserIntent(
                userIntentGeneration,
                conversationGoalId,
                "",
                false);
        agentRuntimeV2.onUserIntent(
                userIntentGeneration,
                conversationGoalId,
                "",
                false);
        Log.d(
                TAG,
                "Conversation Loop background wake generation="
                        + userIntentGeneration);
    }

    private JSONObject waitThenAction(JSONObject args) throws Exception {
        if (!userActionScope.canStartFutureWait()) {
            return runtimeBlocked(
                    "FUTURE_WAIT_NOT_REQUESTED",
                    "wait_then_action 只能在使用者明確要求等待未來條件、通知或後續動作時建立。"
                            + "目前是一般即時任務；不要用背景等待作為卡住時的 fallback，請直接繼續目前 goal 或回報卡點。");
        }

        String condition = args.optString("condition", "");
        String conditionText = args.optString("condition_text", "");
        String action = args.optString("action", "");
        String target = args.optString("target", "");
        String text = args.optString("text", "");
        String label = args.optString("label", "");
        int interval = args.optInt("interval_seconds", 5);
        int timeout = args.optInt("timeout_minutes", 10);

        PendingActionPolicy.Validation validation =
                PendingActionPolicy.validate(
                        condition,
                        conditionText,
                        action,
                        target,
                        text);
        if (!validation.allowed) {
            return new JSONObject()
                    .put("success", false)
                    .put("blockedByRuntime", true)
                    .put("error", validation.code)
                    .put("instruction", validation.message);
        }

        // One explicit user request creates at most one background watcher.
        userActionScope.consumeFutureWaitAuthorization();

        ScheduledTaskManager manager =
                ScheduledTaskManager.getInstance(appContext);
        final ScheduledTaskManager.ScheduledTask task;
        try {
            task = manager.startPendingAction(
                    label,
                    validation.conditionType,
                    conditionText,
                    validation.action,
                    target,
                    text,
                    interval,
                    timeout,
                    PendingActionPolicy.ACTION_NOTIFY.equals(
                            validation.action)
                            ? null
                            : new ScheduledTaskManager.PendingActionExecutor() {
                                @Override public JSONObject execute(
                                        String scheduledAction,
                                        String scheduledTarget,
                                        String scheduledText)
                                        throws Exception {
                                    return executePendingActionDeterministically(
                                            scheduledAction,
                                            scheduledTarget,
                                            scheduledText);
                                }
                            });
        } catch (Exception error) {
            return new JSONObject()
                    .put("success", false)
                    .put("error", "PENDING_WAIT_SETUP_FAILED")
                    .put(
                            "instruction",
                            error.getMessage() == null
                                    ? "無法建立等待任務"
                                    : error.getMessage());
        }

        return new JSONObject()
                .put("success", true)
                .put("task", task.toJson())
                .put("taskState", "WAITING_BACKGROUND")
                .put(
                        "message",
                        PendingActionPolicy.CONDITION_APP_OPENED.equals(
                                validation.conditionType)
                                ? "已建立 App 開啟監控；Runtime 會用 Accessibility 事件即時偵測，5 秒輪詢只作為 fallback。"
                                : "已建立等待後續操作；Runtime 會用 Accessibility 事件優先監控，不需要 Gemini 持續等待。")
                .put(
                        "instruction",
                        "等待任務已交給 Runtime。不要輪詢畫面、不要重複建立相同任務；直接告知使用者已開始等待。");
    }

    private JSONObject executePendingActionDeterministically(
            String action,
            String target,
            String text) throws Exception {
        JSONObject result;

        if (PendingActionPolicy.ACTION_TAP.equals(action)) {
            if (UserActionScope.looksLikeSendTarget(target)
                    || PendingActionPolicy.looksLikeHighRiskCommit(target)) {
                return new JSONObject()
                        .put("success", false)
                        .put("blockedByRuntime", true)
                        .put("error", "HIGH_RISK_PENDING_ACTION_BLOCKED");
            }
            result = phoneRuntimeExecutor.tap(
                    new JSONObject().put("label", target));
            return observationVerificationController
                    .autoObserveAfterMutation(
                            result,
                            "pending_tap");
        }

        if (PendingActionPolicy.ACTION_TYPE.equals(action)) {
            result = phoneRuntimeExecutor.typeText(text);
            return observationVerificationController
                    .autoObserveAfterMutation(
                            result,
                            "pending_type");
        }

        if (PendingActionPolicy.ACTION_COMMIT_SEARCH.equals(action)) {
            result = phoneRuntimeExecutor.commitSearch();
            return observationVerificationController
                    .autoObserveAfterMutation(
                            result,
                            "pending_commit_search");
        }

        if (PendingActionPolicy.ACTION_BACK.equals(action)
                || PendingActionPolicy.ACTION_HOME.equals(action)) {
            result = phoneRuntimeExecutor.pressKey(action);
            return observationVerificationController
                    .autoObserveAfterMutation(
                            result,
                            "pending_" + action.toLowerCase(Locale.ROOT));
        }

        return new JSONObject()
                .put("success", false)
                .put("error", "UNSUPPORTED_PENDING_ACTION");
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
        return observationVerificationController.autoObserveAfterMutation(reply, "swipe_screen");
    }



    private JSONObject tap(JSONObject args) throws Exception {
        String label = args.optString(
                "label",
                args.optString("text", args.optString("name", ""))).trim();
        String id = args.optString("id", "").trim();
        String tapMeta = label + " " + id;

        if (UserActionScope.looksLikeSendTarget(tapMeta)) {
            JSONObject routedSend = sendTextToPhone(new JSONObject());
            routedSend.put("remappedFrom", "TAP_SEND_CONTROL");
            if (routedSend.optBoolean("success", false)) {
                routedSend.put(
                        "instruction",
                        "TAP Send 已由 Runtime 轉成單次 SEND_CURRENT 並完成；不要再呼叫 Send/TAP。");
            }
            return routedSend;
        }

        String currentPackage =
                observationVerificationController
                        .latestObservation().packageName;
        boolean lowRiskTap =
                !ActionSafetyPolicy.blocks(tapMeta)
                        && !UserActionScope.looksLikeSendTarget(tapMeta);

        if (!pendingChoiceExecuting
                && userActionScope.shouldBlockTapForSearch(
                        tapMeta,
                        label.isEmpty() && id.isEmpty(),
                        lowRiskTap)) {
            return runtimeBlocked(
                    "SEARCH_SCOPE_RESULT_OPEN_NOT_AUTHORIZED",
                    "這個搜尋後操作碰到高風險或提交邊界；一般低風險 TAP 不受 search-only scope 限制。");
        }

        boolean mediaPlayCandidate =
                MediaPlaybackCompletionPolicy
                        .isDefaultTrustedPackage(currentPackage)
                        && MediaPlaybackCompletionPolicy
                                .isPlayControl(tapMeta);
        boolean musicActiveBefore =
                mediaPlayCandidate && isMusicActive();

        JSONObject reply = phoneRuntimeExecutor.tap(args);
        workingContext.recordAction(
                "tap_screen",
                reply.optBoolean("success", false)
                        ? "submitted" : "failed");

        JSONObject observed =
                observationVerificationController
                        .autoObserveAfterMutation(
                                reply, "tap_screen");
        return applyMediaPlaybackCompletion(
                observed,
                tapMeta,
                currentPackage,
                mediaPlayCandidate,
                musicActiveBefore);
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
                    .put("nextRequirement", "INSPECT_UI")
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

        ArrayList<SearchResultAutonomyPolicy.Candidate>
                autonomyCandidates =
                        new ArrayList<SearchResultAutonomyPolicy.Candidate>();
        for (int i = 0; i < rawOptions.length(); i++) {
            JSONObject option = rawOptions.optJSONObject(i);
            autonomyCandidates.add(
                    new SearchResultAutonomyPolicy.Candidate(
                            option == null
                                    ? ""
                                    : option.optString("label", ""),
                            option != null
                                    && option.optBoolean("exactMatch", false),
                            option != null
                                    && option.optBoolean("strongMatch", false)));
        }

        SearchResultAutonomyPolicy.Decision autonomy =
                SearchResultAutonomyPolicy.decide(
                        query,
                        userActionScope.searchContinuation(),
                        autonomyCandidates);
        if (autonomy.autoSelect
                && autonomy.index >= 0
                && autonomy.index < rawOptions.length()) {
            JSONObject candidate =
                    rawOptions.optJSONObject(autonomy.index);
            if (candidate != null) {
                return selectCommittedSearchCandidate(
                        query,
                        candidate,
                        autonomy.reason);
            }
        }

        if (count == 1) {
            JSONObject only = rawOptions.optJSONObject(0);
            return observed
                    .put("searchSelection", "RESULT_NOT_CONFIDENT_ENOUGH")
                    .put("taskState", "BLOCKED")
                    .put("candidate",
                            only == null ? "" : only.optString("label", ""))
                    .put("instruction",
                            "唯一結果與查詢缺乏足夠文字吻合；請簡短請使用者確認或說更完整名稱。");
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
            JSONObject screen = observationVerificationController.readSemanticScreenQuietly();
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
                .put("choiceReason", autonomy.reason)
                .put("instruction",
                        "只有因搜尋結果與查詢缺乏足夠文字吻合才進入人工選擇。不要顯示額外 Crew 選項 UI；用語音簡短列出 choices 並詢問使用者。等待期間禁止任何手機 mutation。");
    }

    private JSONObject selectCommittedSearchCandidate(
            String query,
            JSONObject candidate,
            String autonomyReason) throws Exception {
        String elementId =
                candidate == null
                        ? ""
                        : candidate.optString("elementId", "");
        String label =
                candidate == null
                        ? ""
                        : candidate.optString("label", "");
        if (elementId.isEmpty()) {
            return new JSONObject()
                    .put("success", false)
                    .put("searchSelection", "SELECTION_FAILED")
                    .put("taskState", "BLOCKED")
                    .put("error", "SEARCH_RESULT_ELEMENT_MISSING");
        }

        JSONObject beforeSelection =
                observationVerificationController
                        .readSemanticScreenQuietly();
        String beforeFingerprint =
                beforeSelection == null
                        ? ""
                        : beforeSelection.optString(
                                "fingerprint", "");

        JSONObject selected = tapSemanticElement(
                new JSONObject().put(
                        "element_id", elementId));

        boolean dispatched =
                selected.optBoolean("success", false);
        if (dispatched) {
            userActionScope
                    .markSearchResultSelectionDispatched(label);
        }
        boolean opened =
                dispatched
                        && verifySearchResultOpened(
                                query,
                                elementId,
                                label,
                                beforeFingerprint,
                                selected);
        if (opened) {
            userActionScope.markSearchResultSelected(label);
        }

        selected.put(
                        "searchSelection",
                        opened
                                ? "RESULT_OPEN_VERIFIED"
                                : (dispatched
                                    ? "SELECTION_DISPATCHED"
                                    : "SELECTION_FAILED"))
                .put("selectedSearchResult", label)
                .put(
                        "selectionAuthority",
                        "LOW_RISK_AUTO")
                .put(
                        "selectionReason",
                        autonomyReason == null
                                ? ""
                                : autonomyReason)
                .put(
                        "continuation",
                        userActionScope.searchContinuation())
                .put(
                        "taskState",
                        dispatched
                                ? "IN_PROGRESS"
                                : "BLOCKED")
                .put(
                        "instruction",
                        opened
                                ? "Runtime 已自動選定可信的低風險搜尋結果並驗證結果頁開啟；直接繼續原目標，不要詢問使用者或重新搜尋。"
                                : (dispatched
                                    ? "可信搜尋結果點擊已送出，但尚未證明結果頁開啟。不要重新搜尋；依目前畫面確認後繼續。"
                                    : "可信搜尋結果無法安全點開；停止重試並回報卡點。"));
        return selected;
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

        JSONObject latest = observationVerificationController.readSemanticScreenQuietly();
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

        observationVerificationController.setLatestFingerprint(
                latestFingerprint);
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

                    JSONObject screen = observationVerificationController.readSemanticScreenQuietly();
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
            JSONObject routedSend = sendTextToPhone(new JSONObject());
            routedSend.put("remappedFrom", "TAP_SEND_CONTROL");
            if (routedSend.optBoolean("success", false)) {
                routedSend.put(
                        "instruction",
                        "TAP Send 已由 Runtime 轉成單次 SEND_CURRENT 並完成；不要再呼叫 Send/TAP。");
            }
            return routedSend;
        }

        String currentPackage =
                observationVerificationController
                        .latestObservation().packageName;
        boolean lowRiskTap =
                !ActionSafetyPolicy.blocks(elementMeta)
                        && !UserActionScope.looksLikeSendTarget(elementMeta);

        if (!pendingChoiceExecuting
                && userActionScope.shouldBlockTapForSearch(
                        elementMeta,
                        false,
                        lowRiskTap)) {
            return runtimeBlocked(
                    "SEARCH_SCOPE_RESULT_OPEN_NOT_AUTHORIZED",
                    "這個搜尋後操作碰到高風險或提交邊界；一般低風險 semantic TAP 不受 search-only scope 限制。");
        }

        boolean mediaPlayCandidate =
                MediaPlaybackCompletionPolicy
                        .isDefaultTrustedPackage(currentPackage)
                        && MediaPlaybackCompletionPolicy
                                .isPlayControl(elementMeta);
        boolean musicActiveBefore =
                mediaPlayCandidate && isMusicActive();

        JSONObject reply = phoneRuntimeExecutor.semanticTap(elementId);
        workingContext.recordAction(
                "tap:" + elementId,
                reply.optBoolean("success", false)
                        ? "submitted"
                        : reply.optString("error", "failed"));
        JSONObject observed =
                observationVerificationController
                        .autoObserveAfterMutation(
                                reply, "tap_element");
        return applyMediaPlaybackCompletion(
                observed,
                elementMeta,
                currentPackage,
                mediaPlayCandidate,
                musicActiveBefore);
    }

    private JSONObject waitForCondition(JSONObject args) throws Exception {
        String condition = args == null
                ? "" : args.optString("condition", "screen_change").trim();
        String elementId = args == null
                ? "" : args.optString("element_id", "").trim();
        long timeoutMs = args == null
                ? 5000L : args.optLong("timeout_ms", 5000L);
        timeoutMs = Math.max(250L, Math.min(15000L, timeoutMs));

        PendingCondition.Type type = PendingCondition.Type.SCREEN_CHANGE;
        if ("element_appears".equals(condition)) {
            type = PendingCondition.Type.ELEMENT_APPEARS;
        } else if ("element_disappears".equals(condition)) {
            type = PendingCondition.Type.ELEMENT_DISAPPEARS;
        }

        JSONObject revisionState = phoneRuntimeExecutor.post(
                "/wait_ui_change",
                new JSONObject()
                        .put("after_revision", -1L)
                        .put("timeout_ms", 0L),
                2500);
        long revision = revisionState.optLong("revision", 0L);

        pendingCondition = new PendingCondition(
                type,
                elementId,
                observationVerificationController.latestFingerprint(),
                timeoutMs);
        workingContext.setPendingTask("WAIT_" + type.name());

        long deadline = System.currentTimeMillis()
                + pendingCondition.timeoutMs;
        JSONObject last = null;

        while (System.currentTimeMillis() < deadline
                && pendingCondition != null
                && !Thread.currentThread().isInterrupted()) {
            long remaining =
                    Math.max(1L, deadline - System.currentTimeMillis());
            long eventWaitMs = Math.min(5000L, remaining);

            JSONObject event = null;
            try {
                event = phoneRuntimeExecutor.post(
                        "/wait_ui_change",
                        new JSONObject()
                                .put("after_revision", revision)
                                .put("timeout_ms", eventWaitMs),
                        (int) Math.min(8000L, eventWaitMs + 1800L));
            } catch (Exception ignored) {}

            if (event != null) {
                revision = event.optLong("revision", revision);
            }

            // Accessibility events are timing hints only. Always perform a
            // fresh semantic verification after wake/timeout.
            try {
                last = phoneRuntimeExecutor.get("/semantic_screen");
            } catch (Exception ignored) {}
            if (last == null
                    || !last.optBoolean("success", false)) {
                continue;
            }

            String fp = last.optString("fingerprint", "");
            boolean met = false;
            if (type == PendingCondition.Type.SCREEN_CHANGE) {
                met = !fp.isEmpty()
                        && !fp.equals(
                                pendingCondition.baselineFingerprint);
            } else if (type == PendingCondition.Type.ELEMENT_APPEARS) {
                met = containsElement(
                        last.optJSONArray("elements"),
                        elementId);
            } else if (type == PendingCondition.Type.ELEMENT_DISAPPEARS) {
                met = !containsElement(
                        last.optJSONArray("elements"),
                        elementId);
            }

            if (met) {
                observationVerificationController
                        .recordFingerprintOnly(last);
                workingContext.setPendingTask("");
                pendingCondition = null;

                JSONObject compact =
                        ModelScreenView.compact(
                                last, "WAIT_CONDITION");
                compact.put("conditionMet", true)
                        .put("condition", type.name())
                        .put("wakeSource", "ACCESSIBILITY_EVENT");
                return compact;
            }
        }

        JSONObject raw = last == null
                ? new JSONObject() : last;
        pendingCondition = null;
        workingContext.setPendingTask("");

        JSONObject out =
                ModelScreenView.compact(raw, "WAIT_TIMEOUT");
        out.put("success", true)
                .put("conditionMet", false)
                .put("condition", type.name())
                .put("timeout", true)
                .put("wakeSource", "ACCESSIBILITY_EVENT_WITH_FALLBACK");
        return out;
    }

    private String semanticElementMeta(String elementId) {
        if (elementId == null || elementId.isEmpty()) return "";
        JSONObject screen = observationVerificationController.readSemanticScreenQuietly();
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
        JSONObject screen = observationVerificationController.readSemanticScreenQuietly();
        String current = screen == null ? "" : screen.optString("package", "").trim();
        if (!current.isEmpty()) return current;
        ActionObservation observation = observationVerificationController.latestObservation();
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
            String instruction = appPlaybookStore.systemInstructionForStartup(packageName);
            if (!instruction.isEmpty()) {
                synchronized (injectedAppPlaybooks) {
                    injectedAppPlaybooks.add(packageName + "|builtin");
                }
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

        JSONObject context = appPlaybookStore.modelContextForTask(
                packageName,
                workingContext.toProgressJson(),
                result);
        if (context.length() == 0) return;

        String retrievalKey = context.optString("retrievalKey", "builtin");
        String injectedKey = packageName + "|" + retrievalKey;
        synchronized (injectedAppPlaybooks) {
            if (injectedAppPlaybooks.contains(injectedKey)) return;
        }

        try {
            context.remove("retrievalKey");
            result.put("appPlaybook", context);
            synchronized (injectedAppPlaybooks) {
                injectedAppPlaybooks.add(injectedKey);
            }
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
            observationVerificationController
                    .recordSemanticObservation(semantic);
        }

        JSONObject reverification =
                observationVerificationController
                        .consumeReverificationSummary();

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

        if (reverification != null) {
            out.put("previousActionReverification", reverification);
            if (reverification.optInt("failed", 0) > 0) {
                out.put("taskState", "IN_PROGRESS")
                        .put(
                                "completionEvidence",
                                "PREVIOUS_ACTION_FAILED_AFTER_OBSERVE")
                        .put("nextRequirement", "TRY_ALTERNATIVE")
                        .put(
                                "message",
                                "上一個操作已由最新畫面確認沒有生效；不要把它當成功，也不要原樣重試。請改用不同 locator 或不同語意方法。");
            }
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
            observationVerificationController.recordLegacyScreen(
                    fingerprint,
                    raw.optString("package", ""));
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
                ? observationVerificationController.autoObserveAfterMutation(reply, "launch_app")
                : reply;
    }









    private JSONObject typeText(JSONObject args) throws Exception {
        String text = args.optString("text", "").trim();
        if (text.isEmpty()) return new JSONObject().put("success", false).put("error", "輸入文字不可為空");
        // TYPE is a reversible draft operation. Never block it merely because
        // the same user turn may later commit the message. Commit policy runs
        // only after text entry succeeds and immediately before SEND_CURRENT.

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
            String transactionPackage = observationVerificationController.latestObservation() == null
                    ? "" : observationVerificationController.latestObservation().packageName;
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
                JSONObject observed = observationVerificationController.autoObserveAfterMutation(commit, "search_commit");
                boolean searchOnlyBoundary =
                        commit.optBoolean("resultsObserved", false)
                                ? userActionScope.markSearchResultsObserved()
                                : userActionScope.markSearchCommitted();

                observed.put("searchTransaction",
                                commit.optBoolean("resultsObserved", false)
                                        ? "RESULTS_OBSERVED"
                                        : "COMMIT_DISPATCHED")
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
            JSONObject observed = observationVerificationController.autoObserveAfterMutation(reply, "type_text");
            observed.put("searchTransaction", "PENDING_COMMIT")
                    .put("searchCommitError",
                            commit.optString("error", "SEARCH_COMMIT_FAILED"))
                    .put("taskState", "IN_PROGRESS")
                    .put("completionEvidence", "SEARCH_QUERY_TYPED_NOT_COMMITTED")
                    .put("nextRequirement", "COMMIT_SEARCH")
                    .put("instruction",
                            "不要把 autocomplete suggestion 當成已完成搜尋，也不要因為搜尋而進聊天室或傳訊息。");
            return observed;
        }

        JSONObject observed =
                observationVerificationController.autoObserveAfterMutation(
                        reply, "type_text");
        if (observed.optBoolean("success", false)
                && currentTurnIsExplicitTypeOnly()) {
            observed.put("taskState", "DONE")
                    .put("completionEvidence", "TYPE_ONLY_USER_INTENT_SATISFIED")
                    .put("nextRequirement", "NONE")
                    .put("message", "已輸入文字，未送出")
                    .put("instruction",
                            "使用者只要求輸入文字；TYPE 已完成。禁止再點 Send、提交或呼叫 send_text。");
            return observed;
        }

        // If this turn also explicitly requested SEND, TYPE still happens
        // first. Only after the draft exists do we cross the guarded commit
        // boundary. Passing no text prevents a second insertion.
        if (observed.optBoolean("success", false)
                && userActionScope.canSend()) {
            PerformanceMetrics.recordTextRouteTypeRemappedToSend();
            JSONObject committed = sendTextToPhone(new JSONObject());
            committed.put("draftTypedBeforeCommit", true);
            return committed;
        }
        return observed;
    }

    private boolean currentTurnIsExplicitTypeOnly() {
        LiveTurnCoordinator.FinalizedTurn finalized =
                liveTurnCoordinator.latest();
        return finalized.generation == userIntentGeneration
                && SendAuthorization.isExplicitTypeOnlyRequest(
                        finalized.text);
    }

    private JSONObject executeTypeOnlyFromSendMisroute(
            String text) throws Exception {
        PerformanceMetrics.recordTextRouteSendRemappedToType();
        PerformanceMetrics.recordTextRouteType();

        JSONObject reply = phoneRuntimeExecutor.typeText(text);
        workingContext.recordAction(
                "type_from_send_misroute",
                reply.optBoolean("success", false)
                        ? "submitted" : "failed");

        JSONObject observed =
                observationVerificationController.autoObserveAfterMutation(
                        reply, "type_text");
        observed.put("remappedFrom", "send_text")
                .put("sendSuppressed", true);

        if (observed.optBoolean("success", false)) {
            observed.put("taskState", "DONE")
                    .put("completionEvidence",
                            "TYPE_ONLY_USER_INTENT_SATISFIED")
                    .put("nextRequirement", "NONE")
                    .put("message", "已輸入文字，未送出")
                    .put("instruction",
                            "Runtime 已把誤選的 send_text 降級為 TYPE。使用者只要求打字；禁止送出。");
        }
        return observed;
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

        String currentSearchPackage = observationVerificationController.latestObservation() == null
                ? "" : observationVerificationController.latestObservation().packageName;
        if (currentSearchPackage.isEmpty()) {
            JSONObject screen = observationVerificationController.readSemanticScreenQuietly();
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
        JSONObject observed = observationVerificationController.autoObserveAfterMutation(reply, "search_current_app");
        if (!reply.optBoolean("success", false)) {
            observed.put("instruction",
                    "Runtime 沒有確認文字輸入成功；請依 after 最新畫面改用不同方法，不要宣稱已搜尋。");
            return observed;
        }

        // Only now can Runtime truthfully say the query entered a proven
        // search field. Completion requires query-excluding result-surface
        // evidence from AppSearchRuntime.
        String observedSearchPackage = observationVerificationController.latestObservation() == null
                ? currentSearchPackage : observationVerificationController.latestObservation().packageName;
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
            userActionScope.markSearchResultsObserved();
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
                    .put("nextRequirement", "INSPECT_UI")
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
     * Current-chat SEND boundary.
     *
     * Crew never resolves or verifies a recipient name. The user chooses the
     * chat by opening it; Runtime only proves that the current screen exposes a
     * non-search message composer and a semantic Send surface.
     */
    private JSONObject verifyCurrentChatOnScreen()
            throws Exception {
        JSONObject semantic = phoneRuntimeExecutor.get("/semantic_screen");
        if (semantic == null || !semantic.optBoolean("success", false)) {
            return runtimeBlocked(
                    "CURRENT_CHAT_UNAVAILABLE",
                    "Runtime 目前無法讀取聊天畫面；這不是收件人或隱私限制。");
        }

        JSONArray elements = semantic.optJSONArray("elements");
        if (elements == null || elements.length() == 0) {
            return runtimeBlocked(
                    "CURRENT_CHAT_NOT_VERIFIED",
                    "目前沒有足夠的 Accessibility 結構證明這是聊天畫面。");
        }

        boolean composerFound = false;
        boolean composerLooksChatLike = false;
        boolean sendControlVisible = false;

        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element == null || element.optBoolean("sensitive", false)) {
                continue;
            }

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
            if (looksLikeChatComposer(element)) {
                composerLooksChatLike = true;
            }
        }

        if (!composerFound
                || (!composerLooksChatLike && !sendControlVisible)) {
            return runtimeBlocked(
                    "CURRENT_CHAT_NOT_VERIFIED",
                    "目前畫面尚未證明是可傳訊息的聊天室。Crew 不會搜尋或猜聊天對象；請停留在聊天視窗。");
        }

        return new JSONObject()
                .put("success", true)
                .put("currentChatVerified", true)
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

    private JSONObject delegatedSessionRequiredResult() {
        JSONObject required = runtimeBlocked(
                "DELEGATED_SESSION_REQUIRED",
                "目前沒有單次 SEND authorization 或 task-scoped delegated chat lease。若使用者的當前目標是持續代聊、等待對方回覆後繼續處理，立刻呼叫 start_conversation_loop 一次，成功後再重試 send_text；不要要求使用者重複說『授權』。若使用者從未要求送訊息或代聊，則不要送。");
        try {
            required.put("taskState", "IN_PROGRESS")
                    .put("recoverable", true)
                    .put(
                            "nextRequirement",
                            "START_CONVERSATION_LOOP_IF_CURRENT_GOAL_IS_DELEGATED_CHAT");
        } catch (Exception ignored) {}
        return required;
    }

    private JSONObject sendTextToPhone(JSONObject args) throws Exception {
        String text = args == null ? "" : args.optString("text", "");
        AgentTaskRecord activeTask = agentTaskCoordinator.active();
        String activeTaskId =
                activeTask == null ? "" : activeTask.taskId;
        boolean loopSend =
                conversationLoopRecipe.canSend()
                        && delegatedSendLease.canSend(activeTaskId);
        LiveTurnCoordinator.FinalizedTurn finalized =
                liveTurnCoordinator.latest();

        // Weak Live models sometimes mistake "在聊天框打字" for SEND because
        // the destination is a messaging app. Runtime owns this distinction:
        // explicit type-only intent is a reversible draft action. Preserve the
        // model-generated exact text, but suppress submission entirely.
        if (!loopSend
                && !text.isEmpty()
                && finalized.generation == userIntentGeneration
                && SendAuthorization.isExplicitTypeOnlyRequest(
                        finalized.text)) {
            return executeTypeOnlyFromSendMisroute(text);
        }

        if (!loopSend && !userActionScope.canSend()) {
            ensureSendAuthorizationFromFinalized(finalized, args);
        }

        // Duplicate suppression is a commit concern, not a TYPE concern.
        if (!loopSend && userActionScope.isMessageCommitDispatched()) {
            return new JSONObject()
                    .put("success", true)
                    .put("action", "SEND_CURRENT")
                    .put("sendMode", "ALREADY_DISPATCHED")
                    .put("stepResult", "STEP_OK")
                    .put("taskState", "DONE")
                    .put("completionEvidence", "COMMIT_ALREADY_DISPATCHED")
                    .put("instruction",
                            "這一輪 SEND 已經 dispatch；不要再次送出。草稿操作本身不受此限制。");
        }

        boolean delegatedSendAuthorized =
                ConversationLoopPolicy.hasDelegatedSendAuthority(
                        loopSend, userActionScope.canSend());
        if (!delegatedSendAuthorized) {
            return delegatedSessionRequiredResult();
        }

        // Preparation stays reversible. If send_text carries new text, insert
        // the draft first; a TYPE failure does NOT consume SEND permission.
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
                        .put("sendMode", "TYPE_THEN_SEND_CURRENT")
                        .put("taskState", "IN_PROGRESS")
                        .put("completionEvidence", "DRAFT_TYPE_FAILED")
                        .put("instruction",
                                "草稿尚未輸入成功；SEND 尚未 dispatch。可以重新聚焦或改用其他 TYPE 方法，但不要假裝已送出。");
                workingContext.recordAction("type_for_send", "failed");
                if (loopSend) {
                    stopConversationDelegation("TYPE_FAILED");
                    failure.put("conversationLoop", "STOPPED");
                }
                return failure;
            }
        }

        boolean targetVerified = false;
        boolean targetVerificationRequired = true;

        JSONObject chatVerification = verifyCurrentChatOnScreen();
        if (!chatVerification.optBoolean("success", false)) {
            if (loopSend) {
                stopConversationDelegation(
                        "CURRENT_CHAT_VERIFICATION_FAILED");
                chatVerification.put("conversationLoop", "STOPPED");
            }
            return chatVerification.put(
                    "instruction",
                    "草稿可以保留，但目前畫面不是可驗證的聊天視窗，因此不送出。");
        }
        targetVerified = true;

        CommitGuard.Result commitDecision = CommitGuard.evaluate(
                delegatedSendAuthorized,
                !loopSend && userActionScope.isMessageCommitDispatched(),
                targetVerificationRequired,
                targetVerified,
                false);
        if (!commitDecision.allowed()) {
            if (commitDecision.decision
                    == CommitGuard.Decision.SUPPRESS_DUPLICATE) {
                return new JSONObject()
                        .put("success", true)
                        .put("action", "SEND_CURRENT")
                        .put("sendMode", "ALREADY_DISPATCHED")
                        .put("stepResult", "STEP_OK")
                        .put("taskState", "DONE")
                        .put("completionEvidence", commitDecision.code);
            }
            return runtimeBlocked(
                    commitDecision.code,
                    "CommitGuard 未允許 SEND；草稿仍可編輯，但不得提交。");
        }

        PerformanceMetrics.recordTextRouteSend();

        // This is the irreversible edge. Mark it BEFORE bridge dispatch so an
        // ambiguous bridge/network result can never cause a blind duplicate.
        if (!loopSend) {
            userActionScope.markMessageCommitDispatched();
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

        if (loopSend) {
            if (!reply.optBoolean("success", false)) {
                stopConversationDelegation("SEND_FAILED");
                reply.put("conversationLoop", "STOPPED")
                        .put("instruction",
                                "持續對話送出失敗，Runtime 已停止 loop，避免自動重試造成重複訊息。");
                return reply;
            }

            String baseline = "";
            try {
                JSONObject semantic =
                        phoneRuntimeExecutor.get("/semantic_screen");
                if (semantic != null
                        && semantic.optBoolean("success", false)) {
                    baseline = semantic.optString("fingerprint", "");
                }
            } catch (Exception ignored) {}

            boolean leaseKeepWaiting =
                    delegatedSendLease.recordSend(activeTaskId);
            boolean loopKeepWaiting =
                    conversationLoopRecipe.markSent(baseline);
            boolean keepWaiting =
                    leaseKeepWaiting && loopKeepWaiting;
            if (keepWaiting) {
                workingContext.setPendingTask("CONVERSATION_LOOP_WAIT");
                setConversationWaitingVisual(true);
                reply.put("taskState", "WAITING_BACKGROUND")
                        .put("conversationLoop", "WAITING_FOR_MESSAGE")
                        .put("loopStatus", conversationLoopStatusJson())
                        .put("instruction",
                                "訊息已送出。Runtime 已接手等待下一個聊天室 Accessibility 事件；不要輪詢、不要再次 send_text，直到 Runtime 喚醒。");
                armConversationLoopWaitAsync();
            } else {
                stopConversationDelegation("DELEGATED_REPLY_LIMIT_REACHED");
                reply.put("conversationLoop", "STOPPED")
                        .put("loopStatus", conversationLoopStatusJson())
                        .put("message",
                                "已達持續對話回覆上限，Runtime 自動停止。");
            }
        }

        return reply;
    }

    private JSONObject pressKey(JSONObject args) throws Exception {
        String key = args.optString("key", "").toUpperCase();
        JSONObject reply = phoneRuntimeExecutor.pressKey(key);
        workingContext.recordAction(
                "key:" + key,
                reply.optBoolean("success", false)
                        ? "submitted" : "failed");
        return observationVerificationController.autoObserveAfterMutation(reply, "press_key");
    }

    /** Explicitly commits the currently focused search field via the IME key. */
    private JSONObject commitSearch() throws Exception {
        if (userActionScope.shouldSuppressSearchCommit()) {
            String phase = userActionScope.modelSearchPhase();
            JSONObject suppressed = new JSONObject()
                    .put("success", true)
                    .put("stepResult", "STEP_OK")
                    .put("action", "SEARCH_COMMIT")
                    .put("duplicateSuppressed", true)
                    .put("searchTransaction", phase)
                    .put("taskState", "IN_PROGRESS")
                    .put("completionEvidence",
                            "SEARCH_COMMIT_ALREADY_DISPATCHED");
            if ("RESULTS_OBSERVED".equals(phase)
                    || "RESULT_SELECTED".equals(phase)) {
                suppressed.put("nextRequirement", "CONTINUE_GOAL")
                        .put("instruction",
                                "這一輪搜尋結果已經可用；不要再次提交搜尋，直接繼續目前目標。");
            } else {
                suppressed.put("nextRequirement", "INSPECT_UI")
                        .put("instruction",
                                "這一輪搜尋提交已經送出；不要再次 COMMIT_SEARCH，先依目前畫面確認結果。");
            }
            return suppressed;
        }

        JSONObject reply = phoneRuntimeExecutor.post("/commit_search", new JSONObject());
        workingContext.recordAction("search_commit",
                reply.optBoolean("success", false) ? "submitted" : "failed");
        if (reply.optBoolean("success", false)) {
            if (reply.optBoolean("resultsObserved", false)) {
                userActionScope.markSearchResultsObserved();
            } else {
                userActionScope.markSearchCommitted();
            }
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
        return observationVerificationController.autoObserveAfterMutation(reply, "commit_search");
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

        // Keep the complete Runtime result for logs/task history, but give Live
        // only a stable phone-control contract plus a compact goal-progress
        // projection. Fingerprints, authorization state and other debug fields
        // remain Runtime-internal.
        JSONObject progressContext = workingContext.toProgressJson();
        String searchPhase = userActionScope.modelSearchPhase();
        if (!searchPhase.isEmpty()) {
            JSONObject searchProgress =
                    new JSONObject().put("phase", searchPhase);
            String continuation =
                    userActionScope.searchContinuation();
            if (continuation != null
                    && continuation.matches("[A-Z0-9:_-]{1,64}")) {
                searchProgress.put(
                        "continuation",
                        continuation);
            }
            progressContext.put("search", searchProgress);
        }
        final JSONObject modelResult =
                ModelToolResponseAdapter.forModel(
                        name, result, progressContext);
        JSONObject modelAction = modelResult.optJSONObject("action");
        if (modelAction != null) {
            workingContext.recordModelStep(
                    modelAction.optString("type", ""),
                    modelAction.optString("target", ""),
                    modelAction.optString("effect", ""));
        }
        int coreModelBytes =
                ContextPayloadBudget.utf8Bytes(modelResult.toString());

        JSONObject appPlaybook =
                result == null ? null : result.optJSONObject("appPlaybook");
        if (appPlaybook != null && appPlaybook.length() > 0) {
            modelResult.put("appPlaybook", appPlaybook);
        }

        JSONArray responses =
                toolCallDispatcher.expandResponses(id, name, modelResult);
        String payload = new JSONObject()
                .put("toolResponse", new JSONObject()
                        .put("functionResponses", responses))
                .toString();

        int rawBytes = ContextPayloadBudget.utf8Bytes(
                result == null ? "" : result.toString());
        int modelBytes =
                ContextPayloadBudget.utf8Bytes(modelResult.toString());
        int progressBytes =
                ContextPayloadBudget.utf8Bytes(progressContext.toString());
        int playbookBytes = ContextPayloadBudget.utf8Bytes(
                appPlaybook == null ? "" : appPlaybook.toString());
        int outboundBytes =
                ContextPayloadBudget.utf8Bytes(payload);

        contextPayloadAudit.logTool(
                userIntentGeneration,
                name,
                rawBytes,
                modelBytes,
                progressBytes,
                playbookBytes,
                outboundBytes);
        contextPayloadAudit.logBudget(
                "tool:" + name,
                coreModelBytes,
                ContextPayloadBudget.toolBudget(name));
        if (playbookBytes > 0) {
            contextPayloadAudit.logBudget(
                    "app_playbook",
                    playbookBytes,
                    ContextPayloadBudget.APP_PLAYBOOK_BYTES);
        }

        if (!liveConnection.isAvailable()
                || !liveConnection.send(payload)) {
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
        contextPayloadAudit.flushTurn();
        stopConversationDelegation("LIVE_SESSION_FAILED");
        if (error != null) Log.e(TAG, message, error); else Log.e(TAG, message);
        running = false;
        interruptionHandler.removeCallbacks(clearInterruptedFallback);
        liveAudioController.stop(); listener.onStopped(message);
    }

}
