package com.crewpocket.helper;

import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 0099 privacy-bounded rolling performance telemetry.
 * Stores timings/counters only. Never stores transcript text, screenshots,
 * tool arguments, model replies, API keys, bridge tokens, or audio content.
 */
final class PerformanceMetrics {
    private static final int MAX_SAMPLES = 64;

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
                .append(" clean-skipped=").append(screenFramesSkippedClean);
        return out.toString();
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
}
