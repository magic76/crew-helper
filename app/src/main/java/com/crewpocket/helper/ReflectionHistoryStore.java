package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Privacy-safe persistent history for Experience model attempts.
 *
 * Stores only timestamp, model, terminal result, bounded status code and latency.
 * Ordinary tasks that do not qualify for Crew Experience are intentionally not
 * recorded here.
 */
final class ReflectionHistoryStore {
    private static final String PREFS = "crew_reflection_history";
    private static final String KEY_EVENTS = "events_v1";
    private static final String KEY_DIAGNOSTIC = "diagnostic_v1";
    private static final int MAX_EVENTS = 20;
    private static final String NOT_CALLED = "NOT_CALLED";
    private static final Object LOCK = new Object();

    private ReflectionHistoryStore() {}

    static void recordNoModelCall(Context context,
                                  String result,
                                  String status,
                                  long latencyMs) {
        record(context, result, status, latencyMs, NOT_CALLED, false, false);
    }

    static void recordModelCall(Context context,
                                String result,
                                String status,
                                long latencyMs,
                                String model,
                                boolean fallbackUsed) {
        record(context, result, status, latencyMs, model, true, fallbackUsed);
    }

    static void recordDiagnostic(Context context,
                                 GeminiTaskReflector.DiagnosticResult result) {
        if (context == null || result == null) return;
        synchronized (LOCK) {
            try {
                JSONObject diagnostic = new JSONObject()
                        .put("at", System.currentTimeMillis())
                        .put("success", result.success)
                        .put("primary", GeminiTaskReflector.MODEL)
                        .put("primaryStatus", safeCode(result.primaryStatus, "UNKNOWN"))
                        .put("selectedModel", safeModel(result.selectedModel, true))
                        .put("selectedStatus", safeCode(result.selectedStatus, "UNKNOWN"))
                        .put("fallback", result.fallbackUsed)
                        .put("latencyMs", Math.max(0L, result.latencyMs));
                context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_DIAGNOSTIC, diagnostic.toString())
                        .apply();
            } catch (Exception ignored) {}
        }
    }

    private static void record(Context context,
                               String result,
                               String status,
                               long latencyMs,
                               String model,
                               boolean actualCall,
                               boolean fallbackUsed) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray events = readArray(prefs.getString(KEY_EVENTS, "[]"));
                JSONObject event = new JSONObject()
                        .put("at", System.currentTimeMillis())
                        .put("model", safeModel(model, actualCall))
                        .put("result", safeCode(result, "UNKNOWN"))
                        .put("status", safeCode(status, "UNKNOWN"))
                        .put("actualCall", actualCall)
                        .put("fallback", fallbackUsed);
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

    static String buildSummary(Context context) {
        JSONArray events = new JSONArray();
        JSONObject diagnostic = null;
        if (context != null) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                events = readArray(prefs.getString(KEY_EVENTS, "[]"));
                diagnostic = readObject(prefs.getString(KEY_DIAGNOSTIC, ""));
            } catch (Exception ignored) {}
        }

        int actualCalls = 0;
        JSONObject latestActual = null;
        for (int i = events.length() - 1; i >= 0; i--) {
            JSONObject event = events.optJSONObject(i);
            if (event == null || !isActualCall(event)) continue;
            actualCalls++;
            if (latestActual == null) latestActual = event;
        }

        String health = diagnostic == null || diagnostic.length() == 0
                ? "Not tested"
                : (diagnostic.optBoolean("success", false) ? "Healthy" : "Needs attention");
        StringBuilder out = new StringBuilder();
        out.append("Model health: ").append(health)
                .append("\nModel calls: ").append(actualCalls);
        if (latestActual != null) {
            out.append("\nLast call: ")
                    .append(latestActual.optString("result", "UNKNOWN"))
                    .append(" · ")
                    .append(formatTime(latestActual.optLong("at", 0L)));
        } else {
            out.append("\nLast call: never");
        }
        return out.toString();
    }

    static String buildReport(Context context) {
        JSONArray events = new JSONArray();
        JSONObject diagnostic = null;
        if (context != null) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                events = readArray(prefs.getString(KEY_EVENTS, "[]"));
                diagnostic = readObject(prefs.getString(KEY_DIAGNOSTIC, ""));
            } catch (Exception ignored) {}
        }

        StringBuilder out = new StringBuilder();
        appendHealth(out, events, diagnostic);
        out.append("\n\nExperience model history (latest 20)\n");
        out.append("Ordinary successful tasks stay quiet and do not create skip records.\n");
        if (events.length() == 0) {
            out.append("No Experience model attempts recorded yet.");
            return out.toString();
        }

        for (int i = events.length() - 1; i >= 0; i--) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            boolean actualCall = isActualCall(event);
            out.append(formatTime(event.optLong("at", 0L)))
                    .append("  ")
                    .append(event.optString("result", "UNKNOWN"))
                    .append(" · ")
                    .append(event.optString("status", "UNKNOWN"))
                    .append(" · ")
                    .append(actualCall
                            ? event.optString("model", GeminiTaskReflector.MODEL)
                            : NOT_CALLED);
            if (actualCall && event.optBoolean("fallback", false)) {
                out.append(" · FALLBACK");
            }
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

    private static void appendHealth(StringBuilder out,
                                     JSONArray events,
                                     JSONObject diagnostic) {
        out.append("Experience model health\n")
                .append("Primary: ").append(GeminiTaskReflector.MODEL).append("\n");

        if (diagnostic == null || diagnostic.length() == 0) {
            out.append("Last diagnostic: NEVER\n");
        } else {
            out.append("Last diagnostic: ")
                    .append(diagnostic.optBoolean("success", false) ? "SUCCESS" : "ERROR")
                    .append(" · ")
                    .append(formatTime(diagnostic.optLong("at", 0L)))
                    .append("\n")
                    .append("Primary test: ")
                    .append(diagnostic.optString("primaryStatus", "UNKNOWN"))
                    .append("\n");
            if (diagnostic.optBoolean("fallback", false)) {
                out.append("Fallback: ")
                        .append(diagnostic.optString("selectedModel", "UNKNOWN"))
                        .append(" · ")
                        .append(diagnostic.optString("selectedStatus", "UNKNOWN"))
                        .append("\n");
            } else {
                out.append("Fallback: not needed\n");
            }
            out.append("Diagnostic latency: ")
                    .append(diagnostic.optLong("latencyMs", 0L))
                    .append("ms\n");
        }

        JSONObject latestActual = null;
        for (int i = events.length() - 1; i >= 0; i--) {
            JSONObject event = events.optJSONObject(i);
            if (event != null && isActualCall(event)) {
                latestActual = event;
                break;
            }
        }

        if (latestActual == null) {
            out.append("Last actual Experience model call: NEVER\n")
                    .append("Experience fallback used: no");
            return;
        }

        out.append("Last actual Experience model call: ")
                .append(latestActual.optString("result", "UNKNOWN"))
                .append(" · ")
                .append(latestActual.optString("status", "UNKNOWN"))
                .append(" · ")
                .append(latestActual.optString("model", GeminiTaskReflector.MODEL))
                .append(" · ")
                .append(formatTime(latestActual.optLong("at", 0L)))
                .append("\n")
                .append("Experience fallback used: ")
                .append(latestActual.optBoolean("fallback", false) ? "yes" : "no");
    }

    private static boolean isActualCall(JSONObject event) {
        if (event == null) return false;
        if (event.has("actualCall")) return event.optBoolean("actualCall", false);
        // Backward compatibility for old reflection records.
        return !"SKIPPED".equals(event.optString("result", ""));
    }

    private static JSONArray readArray(String raw) {
        try { return new JSONArray(raw == null ? "[]" : raw); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static JSONObject readObject(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try { return new JSONObject(raw); }
        catch (Exception ignored) { return null; }
    }

    private static String safeModel(String value, boolean actualCall) {
        if (!actualCall) return NOT_CALLED;
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
