package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 0079: small model-facing projection of full Runtime tool results.
 *
 * Runtime, logs, verification and task history keep the full internal result.
 * Gemini Live sees only:
 *   status = DONE | WAIT | NEED_USER | FAILED
 *   message = one short instruction/result
 *   screen = small current-screen projection when useful
 *
 * Deck tools are deliberately left untouched because their structured card data
 * is presentation content, not phone-control debug metadata.
 */
final class ModelToolResponseAdapter {
    static final String DONE = "DONE";
    static final String WAIT = "WAIT";
    static final String NEED_USER = "NEED_USER";
    static final String FAILED = "FAILED";

    private static final int MAX_SCREEN_ITEMS = 10;
    private static final int MAX_CHOICES = 6;
    private static final int MAX_MESSAGE = 180;
    private static final int MAX_LABEL = 96;

    private ModelToolResponseAdapter() {}

    static JSONObject forModel(String toolName, JSONObject internal) {
        if (!shouldCompact(toolName)) {
            return internal == null ? new JSONObject() : internal;
        }

        JSONObject source = internal == null ? new JSONObject() : internal;
        JSONObject out = new JSONObject();
        try {
            String status = status(source);
            out.put("status", status);

            String message = message(toolName, source, status);
            if (!message.isEmpty()) out.put("message", clip(message, MAX_MESSAGE));

            JSONObject screen = screen(source);
            if (screen.length() > 0) out.put("screen", screen);
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean shouldCompact(String toolName) {
        return "phone_action".equals(toolName)
                || "inspect_ui".equals(toolName)
                || "send_text".equals(toolName)
                || "end_voice_session".equals(toolName);
    }

    private static String status(JSONObject result) {
        String taskState = upper(result.optString("taskState", ""));
        String searchSelection = upper(result.optString("searchSelection", ""));
        String resultStatus = upper(result.optString("status", ""));

        if ("WAITING_USER".equals(taskState)
                || "USER_CHOICE_PENDING".equals(searchSelection)
                || "MULTIPLE_MATCHES".equals(resultStatus)) {
            return NEED_USER;
        }

        if (isVerifiedSend(result)) return DONE;

        String stepResult = upper(result.optString("stepResult", ""));
        if (!result.optBoolean("success", false)
                || "STEP_FAILED".equals(stepResult)
                || result.optBoolean("cancelled", false)
                || result.optBoolean("agentStopped", false)
                || result.optBoolean("blockedByRuntime", false)) {
            return FAILED;
        }

        String verification = upper(result.optString("verification", ""));
        String searchTransaction = upper(result.optString("searchTransaction", ""));
        if ("STEP_PENDING".equals(stepResult)
                || "PENDING".equals(verification)
                || "IN_PROGRESS".equals(taskState)
                || "PENDING_RESULTS".equals(searchTransaction)
                || (result.optBoolean("timeout", false)
                    && !result.optBoolean("conditionMet", false))) {
            return WAIT;
        }

        return DONE;
    }

    private static boolean isVerifiedSend(JSONObject result) {
        if (!result.optBoolean("success", false)) return false;
        return "SEND_CURRENT".equals(upper(result.optString("action", "")))
                || !result.optString("sendMode", "").isEmpty()
                || "TAP_SEND_CONTROL".equals(
                        upper(result.optString("remappedFrom", "")));
    }

    private static String message(String toolName, JSONObject result, String status) {
        String semantic = upper(result.optString("semanticAction", ""));
        String runtime = result.optString("resolvedByRuntime", "");
        String error = upper(result.optString("error", ""));

        if (NEED_USER.equals(status)) {
            if ("MULTIPLE_MATCHES".equals(upper(result.optString("status", "")))) {
                return "找到多個選項，需要使用者選一個。";
            }
            return "需要使用者選擇後才能繼續。";
        }

        if (WAIT.equals(status)) {
            if ("SEARCH".equals(semantic)
                    || "search_current_app".equals(runtime)
                    || "commit_search".equals(runtime)
                    || "PENDING_RESULTS".equals(
                            upper(result.optString("searchTransaction", "")))) {
                return "搜尋已送出，結果仍在更新；先重新觀察畫面，不要重複搜尋。";
            }
            return "操作已送出，畫面仍在更新；先重新觀察，不要重複同一操作。";
        }

        if (FAILED.equals(status)) {
            if (containsAny(error,
                    "CURRENT_SCREEN_MESSAGING_ONLY",
                    "NAMED_RECIPIENT")) {
                return "不支援自動尋找收件人；請使用者先開啟正確聊天室。";
            }
            if (containsAny(error,
                    "CURRENT_SCREEN_SEND_NOT_AUTHORIZED",
                    "SEND_NOT_AUTHORIZED")) {
                return "目前沒有明確送出授權；不要重試，等待使用者的新指令。";
            }
            if (containsAny(error,
                    "OBSERVE_REQUIRED",
                    "PENDING_VERIFICATION")) {
                return "需要先重新觀察目前畫面，再決定下一步。";
            }
            if (containsAny(error,
                    "UI_TARGET_NOT_FOUND",
                    "TARGET_NOT_FOUND",
                    "SEARCH_CONTROL_NOT_FOUND")) {
                return "找不到目標；請依目前畫面改用其他可見控制。";
            }
            if (containsAny(error,
                    "STALE", "CANCELLED", "使用者已有新指令")) {
                return "使用者已有新指令；停止舊操作。";
            }
            if (containsAny(error,
                    "SEARCH_SCOPE_RESULT_OPEN_NOT_AUTHORIZED")) {
                return "目前只要求搜尋；不要打開搜尋結果。";
            }
            if (containsAny(error,
                    "SENSITIVE", "POLICY", "DENIED")) {
                return "這個操作被安全規則阻擋；不要繞過限制。";
            }
            return "這一步未完成；請依目前畫面改用其他方法，不要重複相同操作。";
        }

        if (isVerifiedSend(result) || "send_text".equals(toolName)) {
            return "訊息已送出。";
        }
        if ("inspect_ui".equals(toolName)) {
            return "已讀取目前畫面。";
        }
        if ("end_voice_session".equals(toolName)) {
            return "語音通話即將結束。";
        }
        if ("SEARCH".equals(semantic) || "search_current_app".equals(runtime)) {
            return "搜尋結果已出現。";
        }
        if ("OPEN_APP".equals(semantic) || "launch_app".equals(runtime)) {
            return "App 已開啟。";
        }
        if ("TYPE".equals(semantic) || "type_text".equals(runtime)) {
            return "文字已輸入。";
        }
        if ("SCROLL".equals(semantic) || "swipe_screen".equals(runtime)) {
            return "畫面已滑動。";
        }
        if ("TAP".equals(semantic)
                || "tap_screen".equals(runtime)
                || "tap_element".equals(runtime)) {
            return "操作完成。";
        }
        if ("BACK".equals(semantic) || "HOME".equals(semantic)
                || "press_key".equals(runtime)) {
            return "操作完成。";
        }

        String existing = result.optString("message", "").trim();
        return existing.isEmpty() ? "操作完成。" : existing;
    }

    private static JSONObject screen(JSONObject result) {
        JSONObject out = new JSONObject();
        try {
            JSONObject source = result.optJSONObject("after");
            if (source == null && result.optJSONArray("important") != null) {
                source = result;
            }

            if (source != null) {
                copyString(source, out, "package");
                JSONArray important = source.optJSONArray("important");
                if (important != null && important.length() > 0) {
                    JSONArray items = new JSONArray();
                    for (int i = 0;
                            i < important.length() && items.length() < MAX_SCREEN_ITEMS;
                            i++) {
                        JSONObject item = compactItem(important.optJSONObject(i));
                        if (item.length() > 0) items.put(item);
                    }
                    if (items.length() > 0) out.put("items", items);
                }

                JSONObject focus = compactItem(source.optJSONObject("focus"));
                if (focus.length() > 0) out.put("focus", focus);

                if (source.optBoolean("visionRecommended", false)) {
                    out.put("visionRecommended", true);
                }
            }

            JSONArray choices = choices(result);
            if (choices.length() > 0) out.put("choices", choices);
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONArray choices(JSONObject result) {
        JSONArray out = new JSONArray();
        JSONArray direct = result.optJSONArray("choices");
        if (direct != null) {
            for (int i = 0; i < direct.length() && out.length() < MAX_CHOICES; i++) {
                Object raw = direct.opt(i);
                if (raw == null) continue;
                String value = clip(String.valueOf(raw), MAX_LABEL);
                if (!value.isEmpty()) out.put(value);
            }
            return out;
        }

        JSONArray candidates = result.optJSONArray("candidates");
        if (candidates != null) {
            for (int i = 0;
                    i < candidates.length() && out.length() < MAX_CHOICES;
                    i++) {
                JSONObject candidate = candidates.optJSONObject(i);
                if (candidate == null) continue;
                String label = clip(candidate.optString("label", ""), MAX_LABEL);
                if (label.isEmpty()) continue;
                int index = candidate.optInt("index", i + 1);
                out.put(index + ". " + label);
            }
        }
        return out;
    }

    private static JSONObject compactItem(JSONObject source) {
        JSONObject out = new JSONObject();
        if (source == null) return out;
        try {
            copyClippedString(source, out, "role");
            copyClippedString(source, out, "label");
            copyClippedString(source, out, "can");
        } catch (Exception ignored) {}
        return out;
    }

    private static void copyString(JSONObject from, JSONObject to, String key) {
        String value = from.optString(key, "").trim();
        if (!value.isEmpty()) {
            try { to.put(key, value); } catch (Exception ignored) {}
        }
    }

    private static void copyClippedString(
            JSONObject from, JSONObject to, String key) {
        String value = clip(from.optString(key, ""), MAX_LABEL);
        if (!value.isEmpty()) {
            try { to.put(key, value); } catch (Exception ignored) {}
        }
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            if (needle != null
                    && !needle.isEmpty()
                    && value.contains(upper(needle))) {
                return true;
            }
        }
        return false;
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String clip(String value, int max) {
        String out = value == null ? "" : value.trim();
        return out.length() <= max ? out : out.substring(0, max);
    }
}
