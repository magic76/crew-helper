package com.crewpocket.helper;

import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 0099/0100 privacy-bounded rolling performance telemetry.
 * Timings, safe tool names and counters only. Never stores transcript text,
 * screenshots, tool arguments, model replies, API keys, bridge tokens or audio.
 */
final class PerformanceMetrics {
    private static final int MAX_SAMPLES = 64;
    private static final int MAX_TRACE_STEPS = 20;

    private static final ArrayDeque<Long> liveConnectMs = new ArrayDeque<Long>();
    private static final ArrayDeque<Long> userToAiSpeechMs = new ArrayDeque<Long>();
    private static final ArrayDeque<Long> toolRuntimeMs = new ArrayDeque<Long>();
    private static final ArrayDeque<Long> inspectRuntimeMs = new ArrayDeque<Long>();
    private static final ArrayDeque<Long> screenshotRuntimeMs = new ArrayDeque<Long>();
    private static final ArrayDeque<Long> agentTaskMs = new ArrayDeque<Long>();
    private static final ArrayDeque<Long> screenFrameMs = new ArrayDeque<Long>();

    private static long liveRequestedAt;
    private static long latestUserTranscriptAt;
    private static boolean awaitingAiSpeech;

    private static String lastTool = "";
    private static long lastToolMs;
    private static long lastLiveConnectMs;
    private static long lastUserToAiSpeechMs;
    private static long lastAgentTaskMs;
    private static long lastScreenFrameMs;

    private static long screenFramesSent;
    private static long screenFramesFailed;
    private static long screenFramesSkippedClean;

    // 0100 latency trace. One active finalized user intent is enough because
    // NativeGeminiLiveClient supersedes older intents deterministically.
    private static AgentTrace activeTrace;
    private static AgentTrace lastFinishedTrace;
    private static long stalePostFinishToolsBlocked;
    private static long staleCompletionsSuppressed;
    private static String lastStaleTool = "";

    private PerformanceMetrics() {}

    static synchronized void markLiveRequested() {
        liveRequestedAt = SystemClock.elapsedRealtime();
    }

    static synchronized void markLiveConnected() {
        if (liveRequestedAt <= 0L) return;
        long elapsed = elapsedSince(liveRequestedAt);
        liveRequestedAt = 0L;
        lastLiveConnectMs = elapsed;
        add(liveConnectMs, elapsed);
    }

    static synchronized void markUserTranscript() {
        latestUserTranscriptAt = SystemClock.elapsedRealtime();
        awaitingAiSpeech = true;
    }

    static synchronized void markAiSpeechStarted() {
        if (!awaitingAiSpeech || latestUserTranscriptAt <= 0L) return;
        long elapsed = elapsedSince(latestUserTranscriptAt);
        awaitingAiSpeech = false;
        lastUserToAiSpeechMs = elapsed;
        add(userToAiSpeechMs, elapsed);
    }

    static synchronized void recordTool(String tool, long elapsedMs) {
        long safe = Math.max(0L, elapsedMs);
        lastTool = safeName(tool);
        lastToolMs = safe;
        add(toolRuntimeMs, safe);
        if ("inspect_ui".equals(tool)) add(inspectRuntimeMs, safe);
        if ("take_screenshot".equals(tool)) add(screenshotRuntimeMs, safe);
    }

    static synchronized void recordAgentTask(long elapsedMs) {
        long safe = Math.max(0L, elapsedMs);
        lastAgentTaskMs = safe;
        add(agentTaskMs, safe);
    }

    static synchronized void recordScreenFrame(long elapsedMs, boolean success) {
        long safe = Math.max(0L, elapsedMs);
        lastScreenFrameMs = safe;
        add(screenFrameMs, safe);
        if (success) screenFramesSent++;
        else screenFramesFailed++;
    }

    static synchronized void recordScreenCleanSkip() {
        screenFramesSkippedClean++;
    }

    // ── 0100 Agent latency breakdown ──

    static synchronized void markAgentUserIntent(long generation) {
        activeTrace = new AgentTrace(generation, SystemClock.elapsedRealtime());
    }

    static synchronized void markAgentToolStarted(
            String taskId, long generation, String tool) {
        AgentTrace trace = ensureTrace(taskId, generation);
        long now = SystemClock.elapsedRealtime();
        if (trace.firstToolAt <= 0L) trace.firstToolAt = now;
        if (trace.lastToolResultAt > 0L) {
            long wait = Math.max(0L, now - trace.lastToolResultAt);
            trace.geminiBetweenToolsMs += wait;
            if (!trace.steps.isEmpty()) {
                trace.steps.get(trace.steps.size() - 1).modelWaitAfterMs = wait;
            }
        }
        // Speech followed by another tool was intermediate, not final output.
        trace.finalSpeechAt = 0L;
        trace.currentStep = new ToolTrace(safeName(tool));
        trace.steps.add(trace.currentStep);
        while (trace.steps.size() > MAX_TRACE_STEPS) trace.steps.remove(0);
    }

    static synchronized void markAgentToolRuntime(
            String taskId, long generation, String tool, long runtimeMs) {
        AgentTrace trace = ensureTrace(taskId, generation);
        long safe = Math.max(0L, runtimeMs);
        trace.toolRuntimeTotalMs += safe;
        ToolTrace step = trace.currentStep;
        if (step != null && step.tool.equals(safeName(tool)) && step.runtimeMs < 0L) {
            step.runtimeMs = safe;
        }
    }

    static synchronized void markAgentToolResultSent(
            String taskId, long generation, String tool) {
        AgentTrace trace = ensureTrace(taskId, generation);
        long now = SystemClock.elapsedRealtime();
        trace.lastToolResultAt = now;
        ToolTrace step = trace.currentStep;
        if (step != null && step.tool.equals(safeName(tool))) step.resultSentAt = now;
    }

    static synchronized void markAgentFinalSpeech(
            String taskId, long generation) {
        AgentTrace trace = activeTrace;
        if (!matches(trace, taskId, generation) || trace.lastToolResultAt <= 0L) return;
        if (trace.finalSpeechAt <= 0L) trace.finalSpeechAt = SystemClock.elapsedRealtime();
    }

    static synchronized void markAgentTaskFinished(
            String taskId, long generation, String outcome) {
        AgentTrace trace = activeTrace;
        if (!matches(trace, taskId, generation)) return;
        trace.finishedAt = SystemClock.elapsedRealtime();
        trace.outcome = safeOutcome(outcome);
        if (trace.finalSpeechAt > 0L && !trace.steps.isEmpty()) {
            ToolTrace last = trace.steps.get(trace.steps.size() - 1);
            if (last.modelWaitAfterMs < 0L && trace.lastToolResultAt > 0L) {
                last.modelWaitAfterMs = Math.max(0L, trace.finalSpeechAt - trace.lastToolResultAt);
            }
        }
        lastFinishedTrace = trace.copy();
        activeTrace = null;
    }

    static synchronized void recordStalePostFinishTool(String tool) {
        stalePostFinishToolsBlocked++;
        lastStaleTool = safeName(tool);
    }

    static synchronized void recordStaleCompletion(String tool) {
        staleCompletionsSuppressed++;
        lastStaleTool = safeName(tool);
    }

    static synchronized String buildReport() {
        StringBuilder out = new StringBuilder();
        out.append("Performance (rolling up to ").append(MAX_SAMPLES).append(" samples)\n");
        appendSeries(out, "Live connect", liveConnectMs, lastLiveConnectMs);
        appendSeries(out, "User transcript -> AI speech", userToAiSpeechMs, lastUserToAiSpeechMs);
        appendSeries(out, "Tool runtime", toolRuntimeMs, lastToolMs);
        appendSeries(out, "inspect_ui", inspectRuntimeMs, lastFor(inspectRuntimeMs));
        appendSeries(out, "take_screenshot", screenshotRuntimeMs, lastFor(screenshotRuntimeMs));
        appendSeries(out, "Agent task", agentTaskMs, lastAgentTaskMs);
        appendSeries(out, "Screen frame", screenFrameMs, lastScreenFrameMs);
        if (!lastTool.isEmpty()) {
            out.append("Last tool: ").append(lastTool).append(" · ").append(lastToolMs).append(" ms\n");
        }
        out.append("Screen sharing: sent=").append(screenFramesSent)
                .append(" failed=").append(screenFramesFailed)
                .append(" clean-skipped=").append(screenFramesSkippedClean).append("\n");

        appendAgentTrace(out, lastFinishedTrace);
        out.append("Post-finish stale tools: blocked=").append(stalePostFinishToolsBlocked)
                .append(" completion-suppressed=").append(staleCompletionsSuppressed);
        if (!lastStaleTool.isEmpty()) out.append(" last=").append(lastStaleTool);
        return out.toString();
    }

    private static void appendAgentTrace(StringBuilder out, AgentTrace trace) {
        out.append("\nAgent latency breakdown (last finished task)\n");
        if (trace == null || trace.finishedAt <= 0L) {
            out.append("no finished task trace\n");
            return;
        }
        out.append("Outcome: ").append(trace.outcome).append("\n");
        out.append("User intent -> first tool: ")
                .append(duration(trace.intentAt, trace.firstToolAt)).append(" ms\n");
        out.append("Runtime tool execution total: ")
                .append(trace.toolRuntimeTotalMs).append(" ms\n");
        out.append("Gemini wait between tools: ")
                .append(trace.geminiBetweenToolsMs).append(" ms\n");
        out.append("Last tool result -> final speech: ")
                .append(duration(trace.lastToolResultAt, trace.finalSpeechAt)).append(" ms\n");
        out.append("Final speech -> task finish: ")
                .append(duration(trace.finalSpeechAt, trace.finishedAt)).append(" ms\n");
        out.append("User intent -> task finish: ")
                .append(duration(trace.intentAt, trace.finishedAt)).append(" ms\n");
        if (!trace.steps.isEmpty()) {
            out.append("Tool timeline (safe names only):\n");
            for (int i = 0; i < trace.steps.size(); i++) {
                ToolTrace step = trace.steps.get(i);
                out.append(i + 1).append(". ").append(step.tool)
                        .append(" runtime=").append(Math.max(0L, step.runtimeMs)).append(" ms");
                if (step.modelWaitAfterMs >= 0L) {
                    out.append(" model-wait-after=").append(step.modelWaitAfterMs).append(" ms");
                }
                out.append("\n");
            }
        }
    }

    static synchronized void reset() {
        liveConnectMs.clear();
        userToAiSpeechMs.clear();
        toolRuntimeMs.clear();
        inspectRuntimeMs.clear();
        screenshotRuntimeMs.clear();
        agentTaskMs.clear();
        screenFrameMs.clear();
        liveRequestedAt = 0L;
        latestUserTranscriptAt = 0L;
        awaitingAiSpeech = false;
        lastTool = "";
        lastToolMs = 0L;
        lastLiveConnectMs = 0L;
        lastUserToAiSpeechMs = 0L;
        lastAgentTaskMs = 0L;
        lastScreenFrameMs = 0L;
        screenFramesSent = 0L;
        screenFramesFailed = 0L;
        screenFramesSkippedClean = 0L;
        activeTrace = null;
        lastFinishedTrace = null;
        stalePostFinishToolsBlocked = 0L;
        staleCompletionsSuppressed = 0L;
        lastStaleTool = "";
    }

    private static AgentTrace ensureTrace(String taskId, long generation) {
        if (activeTrace == null || activeTrace.generation != generation) {
            activeTrace = new AgentTrace(generation, 0L);
        }
        if (activeTrace.taskId.isEmpty()) activeTrace.taskId = safeTaskId(taskId);
        return activeTrace;
    }

    private static boolean matches(AgentTrace trace, String taskId, long generation) {
        if (trace == null || trace.generation != generation) return false;
        String safe = safeTaskId(taskId);
        return trace.taskId.isEmpty() || safe.isEmpty() || trace.taskId.equals(safe);
    }

    private static long duration(long start, long end) {
        return start <= 0L || end <= 0L || end < start ? -1L : end - start;
    }

    private static long elapsedSince(long startedAt) {
        return Math.max(0L, SystemClock.elapsedRealtime() - startedAt);
    }

    private static void add(ArrayDeque<Long> series, long value) {
        series.addLast(value);
        while (series.size() > MAX_SAMPLES) series.removeFirst();
    }

    private static long lastFor(ArrayDeque<Long> series) {
        return series.isEmpty() ? 0L : series.peekLast();
    }

    private static void appendSeries(StringBuilder out, String label,
                                     ArrayDeque<Long> series, long last) {
        if (series.isEmpty()) {
            out.append(label).append(": no samples\n");
            return;
        }
        out.append(label).append(": last=").append(last)
                .append(" ms p50=").append(percentile(series, 0.50))
                .append(" ms p95=").append(percentile(series, 0.95))
                .append(" ms n=").append(series.size()).append("\n");
    }

    private static long percentile(ArrayDeque<Long> series, double fraction) {
        ArrayList<Long> values = new ArrayList<Long>(series);
        Collections.sort(values);
        if (values.isEmpty()) return 0L;
        int index = (int) Math.ceil(fraction * values.size()) - 1;
        index = Math.max(0, Math.min(values.size() - 1, index));
        return values.get(index);
    }

    private static String safeName(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[^A-Za-z0-9_]", "");
        return clean.length() <= 48 ? clean : clean.substring(0, 48);
    }

    private static String safeTaskId(String value) {
        if (value == null || value.isEmpty()) return "";
        String clean = value.replaceAll("[^A-Za-z0-9_]", "");
        if (clean.length() <= 12) return clean;
        return clean.substring(clean.length() - 12);
    }

    private static String safeOutcome(String value) {
        return "CANCELLED".equals(value) ? "CANCELLED" : "FINISHED";
    }

    private static final class ToolTrace {
        final String tool;
        long runtimeMs = -1L;
        long resultSentAt;
        long modelWaitAfterMs = -1L;
        ToolTrace(String tool) { this.tool = tool == null ? "" : tool; }
        ToolTrace copy() {
            ToolTrace out = new ToolTrace(tool);
            out.runtimeMs = runtimeMs;
            out.resultSentAt = resultSentAt;
            out.modelWaitAfterMs = modelWaitAfterMs;
            return out;
        }
    }

    private static final class AgentTrace {
        final long generation;
        final long intentAt;
        String taskId = "";
        long firstToolAt;
        long lastToolResultAt;
        long finalSpeechAt;
        long finishedAt;
        long toolRuntimeTotalMs;
        long geminiBetweenToolsMs;
        String outcome = "FINISHED";
        final ArrayList<ToolTrace> steps = new ArrayList<ToolTrace>();
        ToolTrace currentStep;

        AgentTrace(long generation, long intentAt) {
            this.generation = generation;
            this.intentAt = intentAt;
        }

        AgentTrace copy() {
            AgentTrace out = new AgentTrace(generation, intentAt);
            out.taskId = taskId;
            out.firstToolAt = firstToolAt;
            out.lastToolResultAt = lastToolResultAt;
            out.finalSpeechAt = finalSpeechAt;
            out.finishedAt = finishedAt;
            out.toolRuntimeTotalMs = toolRuntimeTotalMs;
            out.geminiBetweenToolsMs = geminiBetweenToolsMs;
            out.outcome = outcome;
            for (ToolTrace step : steps) out.steps.add(step.copy());
            if (!out.steps.isEmpty()) out.currentStep = out.steps.get(out.steps.size() - 1);
            return out;
        }
    }
}
