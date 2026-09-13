package com.crewpocket.helper;

import android.content.Context;
import org.json.JSONObject;

/**
 * Ephemeral authorization/session guard for the keyboard companion.
 *
 * A focused-input session is armed only by an explicit tap on the Crew input
 * bar. Gemini can then inspect/write that exact focused field for a short TTL.
 * The session never authorizes SEND/ENTER/search submission.
 */
final class FocusedInputRuntime {
    private static final long SESSION_TTL_MS = 90_000L;

    private static Session activeSession;

    private FocusedInputRuntime() {}

    static synchronized JSONObject arm(Context context, String mode) {
        JSONObject current = CrewAccessibilityService.getFocusedInputSnapshot();
        if (!current.optBoolean("success", false)) return current;

        if (current.optBoolean("sensitive", false)) {
            return error("SENSITIVE_INPUT_BLOCKED");
        }

        String cleanMode = normalizeMode(mode);
        activeSession = new Session(
                current.optString("targetKey", ""),
                current.optString("package", ""),
                cleanMode,
                current.optString("text", ""),
                System.currentTimeMillis());

        JSONObject out = cloneJson(current);
        try {
            out.put("success", true);
            out.put("sessionMode", cleanMode);
            out.put("expiresInMs", SESSION_TTL_MS);
        } catch (Exception ignored) {}
        return out;
    }

    static synchronized JSONObject context() {
        Session session = activeSession;
        if (session == null) return error("NO_FOCUSED_INPUT_SESSION");

        if (!session.isFresh()) {
            activeSession = null;
            return error("FOCUSED_INPUT_SESSION_EXPIRED");
        }

        JSONObject current = CrewAccessibilityService.getFocusedInputSnapshot();
        if (!current.optBoolean("success", false)) return current;

        if (current.optBoolean("sensitive", false)) {
            activeSession = null;
            return error("SENSITIVE_INPUT_BLOCKED");
        }

        if (!session.targetKey.equals(current.optString("targetKey", ""))) {
            activeSession = null;
            return error("FOCUSED_INPUT_TARGET_CHANGED");
        }

        JSONObject out = cloneJson(current);
        try {
            out.put("success", true);
            out.put("sessionMode", session.mode);
            out.put("originalText", session.originalText);
            out.put("ageMs", Math.max(
                    0L,
                    System.currentTimeMillis() - session.createdAt));
            out.put(
                    "instruction",
                    "This is the focused editable field explicitly selected by "
                    + "the user through Crew Voice Input Companion. Writing here "
                    + "may change text only. It never authorizes SEND, ENTER, "
                    + "search submission, or any other phone action.");
        } catch (Exception ignored) {}
        return out;
    }

    static synchronized JSONObject write(String text, String requestedMode) {
        Session session = activeSession;
        if (session == null) return error("NO_FOCUSED_INPUT_SESSION");

        if (!session.isFresh()) {
            activeSession = null;
            return error("FOCUSED_INPUT_SESSION_EXPIRED");
        }

        JSONObject current = CrewAccessibilityService.getFocusedInputSnapshot();
        if (!current.optBoolean("success", false)) return current;

        if (current.optBoolean("sensitive", false)) {
            activeSession = null;
            return error("SENSITIVE_INPUT_BLOCKED");
        }

        if (!session.targetKey.equals(current.optString("targetKey", ""))) {
            activeSession = null;
            return error("FOCUSED_INPUT_TARGET_CHANGED");
        }

        String mode = normalizeWriteMode(
                requestedMode,
                session.mode);

        JSONObject result = CrewAccessibilityService.writeFocusedInput(
                text == null ? "" : text,
                mode,
                session.targetKey);

        if (result.optBoolean("success", false)) {
            activeSession = null;
        }
        return result;
    }

    static synchronized void clear() {
        activeSession = null;
    }

    static synchronized boolean isActive() {
        if (activeSession == null) return false;
        if (!activeSession.isFresh()) {
            activeSession = null;
            return false;
        }
        return true;
    }

    static synchronized String activeMode() {
        return activeSession == null || !activeSession.isFresh()
                ? ""
                : activeSession.mode;
    }

    private static String normalizeMode(String value) {
        if ("rewrite".equals(value)) return "rewrite";
        if ("translate".equals(value)) return "translate";
        return "dictate";
    }

    private static String normalizeWriteMode(
            String requestedMode,
            String sessionMode) {
        if ("replace_all".equals(requestedMode)) return "replace_all";
        if ("replace_selection".equals(requestedMode)) {
            return "replace_selection";
        }
        if ("insert".equals(requestedMode)) return "insert";

        return "dictate".equals(sessionMode)
                ? "insert"
                : "replace_all";
    }

    private static JSONObject error(String code) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", false);
            out.put("error", code);
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject cloneJson(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static final class Session {
        final String targetKey;
        final String packageName;
        final String mode;
        final String originalText;
        final long createdAt;

        Session(
                String targetKey,
                String packageName,
                String mode,
                String originalText,
                long createdAt) {
            this.targetKey = targetKey == null ? "" : targetKey;
            this.packageName = packageName == null ? "" : packageName;
            this.mode = mode == null ? "dictate" : mode;
            this.originalText = originalText == null ? "" : originalText;
            this.createdAt = createdAt;
        }

        boolean isFresh() {
            long age = System.currentTimeMillis() - createdAt;
            return age >= 0L && age <= SESSION_TTL_MS;
        }
    }
}
