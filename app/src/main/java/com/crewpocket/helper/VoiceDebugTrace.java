package com.crewpocket.helper;

import android.content.Context;
import java.util.Locale;

/**
 * User-opted-in, process-memory-only Gemini Live transcription diagnostics.
 * Only the enabled flag is stored; speech never goes to disk or Logcat here.
 * Disabling clears the last sample. No audio or screenshots are retained.
 */
final class VoiceDebugTrace {
    private static final String PREF = "crew_voice_debug";
    private static volatile String latest = "";
    private static volatile String latestProtocol = "";
    private static int receivedToolCalls;
    private static int modelSpeechFrames;

    private VoiceDebugTrace() {}

    static boolean enabled(Context context) {
        return context != null && context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
    }

    static void setEnabled(Context context, boolean on) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", on).apply();
        clear();
    }

    static synchronized void clear() {
        latest = "";
        latestProtocol = "";
        receivedToolCalls = 0;
        modelSpeechFrames = 0;
    }

    /** Debug-only metadata; no tool arguments, prompts, transcript or audio. */
    static synchronized void recordProtocol(
            Context context, String model, String status,
            boolean turnComplete, int calls, boolean modelOutput) {
        if (!enabled(context)) return;
        receivedToolCalls += Math.max(0, calls);
        if (modelOutput) modelSpeechFrames++;
        String safeStatus = "IDLE".equals(status) || "IN_PROGRESS".equals(status)
                ? status : "not provided";
        latestProtocol = "Live model: " + (LiveExtendedThinkingPolicy.isExtended(model)
                ? "Extended Thinking" : "Standard")
                + "\nLast interaction status: " + safeStatus
                + "\nLast speech boundary: " + (turnComplete ? "turnComplete" : "none")
                + "\nTool calls received: " + receivedToolCalls
                + "\nModel output frames: " + modelSpeechFrames
                + (receivedToolCalls == 0 && modelSpeechFrames > 0
                        ? "\nNo tool call arrived; a model-side failure cannot be ruled out."
                        : "");
    }

    static void record(Context context, String rawTranscript,
            String boundaryDecision, String boundaryReason,
            double confidence, long handlingMs) {
        if (!enabled(context)) {
            clear();
            return;
        }
        // Keep exactly one latest input, not the merged (possibly older) text.
        String text = rawTranscript == null ? "" : rawTranscript.trim();
        if (text.length() > 300) text = text.substring(0, 300) + "…";
        String conf = confidence < 0.0d
                ? "not provided by Live"
                : String.format(Locale.US, "%.2f", confidence);
        latest = "Latest finalized speech: " + text
                + "\nTurn decision: " + boundaryDecision
                + "\nReason: " + boundaryReason
                + "\nTranscript confidence: " + conf
                + "\nLocal routing: " + Math.max(0L, handlingMs) + " ms"
                + "\nAudio / ASR network latency: not available";
    }

    static String report(Context context) {
        if (!enabled(context)) return "Voice Debug: OFF";
        String sample = latest;
        return "Voice Debug: ON — latest speech in RAM only (may contain private text)\n"
                + (sample.isEmpty()
                        ? "No finalized voice turn captured yet." : sample)
                + (latestProtocol.isEmpty() ? "" : "\n\n" + latestProtocol);
    }
}
