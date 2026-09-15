package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Privacy-safe persistent history for recent post-task reflection attempts.
 *
 * Stores only timestamp, model, terminal result, bounded status code and latency.
 * It never stores task text, lesson text, package/app content, screenshots,
 * tool arguments, API keys or model replies.
 */
final class ReflectionHistoryStore {
    private static final String PREFS = "crew_reflection_history";
    private static final String KEY_EVENTS = "events_v1";
    private static final int MAX_EVENTS = 20;
    private static final Object LOCK = new Object();

    private ReflectionHistoryStore() {}

    static void record(Context context,
                       String result,
                       String status,
                       long latencyMs) {
        record(context, result, status, latencyMs, GeminiTaskReflector.MODEL);
    }

    static void record(Context context,
                       String result,
                       String status,
                       long latencyMs,
                       String model) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray events = readArray(prefs.getString(KEY_EVENTS, "[]"));
                JSONObject event = new JSONObject()
                        .put("at", System.currentTimeMillis())
                        .put("model", safeModel(model))
                        .put("result", safeCode(result, "UNKNOWN"))
                        .put("status", safeCode(status, "UNKNOWN"));
                if (latencyMs >= 0L) event.put("latencyMs", latencyMs);
                events.put(event);

                JSONArray trimmed = new JSONArray();
                for (int i = Math.max(0, events.length() - MAX_EVENTS);
                     i < events.length(); i++) {
                    Object value = events.opt(i);
                    if (value != null) trimmed.put(value);
                }
                prefs.edit().putString(KEY_EVENTS, trimmed.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    static String buildReport(Context context) {
        JSONArray events = new JSONArray();
        if (context != null) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                events = readArray(prefs.getString(KEY_EVENTS, "[]"));
            } catch (Exception ignored) {}
        }

        StringBuilder out = new StringBuilder();
        out.append("Reflection history (latest 20)\n");
        if (events.length() == 0) {
            out.append("No reflection attempts recorded yet.");
            return out.toString();
        }

        for (int i = events.length() - 1; i >= 0; i--) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            out.append(formatTime(event.optLong("at", 0L)))
                    .append("  ")
                    .append(event.optString("result", "UNKNOWN"))
                    .append(" · ")
                    .append(event.optString("status", "UNKNOWN"))
                    .append(" · ")
                    .append(event.optString("model", GeminiTaskReflector.MODEL));
            if (event.has("latencyMs")) {
                out.append(" · ").append(event.optLong("latencyMs", 0L)).append("ms");
            }
            out.append("\n");
        }
        return out.toString().trim();
    }

    static void clear(Context context) {
        if (context == null) return;
        try {
            context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply();
        } catch (Exception ignored) {}
    }

    private static JSONArray readArray(String raw) {
        try { return new JSONArray(raw == null ? "[]" : raw); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static String safeModel(String value) {
        String text = value == null ? "" : value.trim();
        text = text.replaceAll("[^A-Za-z0-9._-]", "_");
        return text.isEmpty() ? GeminiTaskReflector.MODEL : text;
    }

    private static String safeCode(String value, String fallback) {
        String text = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        text = text.replaceAll("[^A-Z0-9_:-]", "_");
        while (text.contains("__")) text = text.replace("__", "_");
        if (text.length() > 48) text = text.substring(0, 48);
        return text.isEmpty() ? fallback : text;
    }

    private static String formatTime(long at) {
        if (at <= 0L) return "--:--:--";
        try {
            return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(at));
        } catch (Exception ignored) {
            return String.valueOf(at);
        }
    }
}
