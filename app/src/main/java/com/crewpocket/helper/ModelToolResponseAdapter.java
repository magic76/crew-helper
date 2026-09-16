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

    private static final int MAX_SCREEN_ITEMS = 14;
    private static final int MAX_CHOICES = 12;
    private static final int MAX_MESSAGE = 220;
    private static final int MAX_LABEL = 96;

    private ModelToolResponseAdapter() {}

    static JSONObject forModel(String toolName, JSONObject internal) {
        if (!shouldCompact(toolName)) {
            return internal == null ? new JSONObject() : internal;
        }

        JSONObject source = internal == null ? new JSONObject() : internal;

        // Shared Visual Reference is user initiated only. A successful normal
        // TAP invalidates the short-lived remembered target; failures merely
        // remain available as context if the user later says "開方格".
        if ("phone_action".equals(toolName)
                && "TAP".equals(upper(source.optString("semanticAction", "")))
                && source.optBoolean("success", false)
                && !"WAITING_USER".equals(upper(source.optString("taskState", "")))) {
            SharedVisualReferenceRuntime.clearRememberedTarget();
        }

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
                || "end_voice_session".equals(toolName)
                || "wait".equals(toolName)
                || "take_screenshot".equals(toolName);
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

        String error = upper(result.optString("error", ""));
        String verificationStatus = upper(result.optString("verificationStatus", ""));
        if (containsAny(error, "OBSERVE_REQUIRED", "PENDING_VERIFICATION")
                || "PENDING".equals(verificationStatus)) {
            return WAIT;
        }

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
            String visualReference = upper(result.optString("visualReference", ""));
            if (!visualReference.isEmpty()) {
                if ("REFINED".equals(visualReference)) {
                    return "畫面已放大成 1–9。只問使用者第二次位置；回答後立刻用 phone_action(TAP,target=回答原文)，不要猜座標。";
                }
                return "Runtime 已依使用者要求在手機畫面標示 1–12。只問是哪一格或哪個位置；回答後立刻用 phone_action(TAP,target=回答原文)，不要猜座標。";
            }
            if ("MULTIPLE_MATCHES".equals(upper(result.optString("status", "")))) {
                return "找到多個選項；用一句話讀出 screen.choices 並問使用者要哪個，然後等待回答。";
            }
            return "有多個可信結果；用一句話讀出 screen.choices 並詢問使用者，等待第一個/第二個/名稱/取消。";
        }

        if (WAIT.equals(status)) {
            if (containsAny(error, "OBSERVE_REQUIRED", "PENDING_VERIFICATION")) {
                return "Runtime 已阻止重複操作；先重新觀察目前畫面一次，再決定下一步。";
            }
            if ("wait".equals(toolName)) {
                return "等待逾時；請重新觀察目前畫面，不要直接重複原操作。";
            }
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
                    "UI_TARGET_NOT_FOUND",
                    "TARGET_NOT_FOUND",
                    "SEARCH_CONTROL_NOT_FOUND")) {
                return "找不到目標；不要自動開位置方格。可依目前畫面改用其他控制；若使用者想自己指定位置，可提醒他說「開方格」。";
            }
            if (containsAny(error,
                    "VISUAL_REFERENCE_OVERLAY_PERMISSION_REQUIRED",
                    "VISUAL_REFERENCE_UNAVAILABLE")) {
                return result.optString("instruction", "目前無法開啟位置方格。");
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
        if ("wait".equals(toolName)) {
            return "等待的畫面已出現。";
        }
        if ("inspect_ui".equals(toolName)) {
            if (result.optBoolean("visualSent", false)) {
                return "最新手機畫面已提供；直接看畫面判斷目前內容。";
            }
            if ("SENSITIVE_SCREEN".equals(
                    upper(result.optString("visualBlocked", "")))) {
                return "目前畫面含敏感資訊，未傳送截圖；使用已遮蔽的畫面資訊。";
            }
            return "截圖不可用；使用目前語意畫面作為 fallback。";
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
            if (source == null && !result.optBoolean("visualSent", false)) {
                source = result.optJSONObject("semanticFallback");
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
