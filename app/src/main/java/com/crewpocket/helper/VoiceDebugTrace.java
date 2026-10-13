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

    static void clear() { latest = ""; }

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
                        ? "No finalized voice turn captured yet." : sample);
    }
}
