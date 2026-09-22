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
 *   step = authoritative effect/reason/next hint for this Runtime step
 *   progress = compact goal continuity without debug/authorization state
 *
 * Deck tools are deliberately left untouched because their structured card data
 * is presentation content, not phone-control debug metadata.
 */
final class ModelToolResponseAdapter {
    static final String DONE = "DONE";
    static final String WAIT = "WAIT";
    static final String NEED_USER = "NEED_USER";
    static final String FAILED = "FAILED";

    private static final int MAX_SCREEN_ITEMS = 8;
    private static final int MAX_CHOICES = 10;
    private static final int MAX_MESSAGE = 180;
    private static final int MAX_LABEL = 80;
    private static final int MAX_GOAL = 240;
    private static final int MAX_PROGRESS_ACTIONS = 3;

    private ModelToolResponseAdapter() {}

    static JSONObject forModel(String toolName, JSONObject internal) {
        return forModel(toolName, internal, null);
    }

    static JSONObject forModel(
            String toolName,
            JSONObject internal,
            JSONObject progressContext) {
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

            ModelStepGuidance.Guidance guidance = ModelStepGuidance.from(
                    toolName,
                    status,
                    source.optString("semanticAction", ""),
                    source.optString("resolvedByRuntime", ""),
                    source.optString("error", ""),
                    source.optString("taskState", ""),
                    source.optString("searchTransaction", ""),
                    isVerifiedSend(source));
            JSONObject step = step(guidance);
            if (step.length() > 0) out.put("step", step);

            JSONObject progress = progress(progressContext, status);
            if (progress.length() > 0) out.put("progress", progress);
        } catch (Exception ignored) {}
        return enforceBudget(toolName, out);
    }

    private static JSONObject enforceBudget(
            String toolName,
            JSONObject out) {
        if (out == null) return new JSONObject();
        int budget = ContextPayloadBudget.toolBudget(toolName);
        if (ContextPayloadBudget.utf8Bytes(out.toString()) <= budget) {
            return out;
        }

        JSONObject screen = out.optJSONObject("screen");
        JSONObject progress = out.optJSONObject("progress");

        trimArray(screen, "items", 6);
        trimArray(screen, "choices", 6);
        trimArray(progress, "recentActions", 2);
        clipInPlace(out, "message", 140);
        clipInPlace(progress, "goal", 180);
        clipInPlace(progress, "rootGoal", 180);

        if (ContextPayloadBudget.utf8Bytes(out.toString()) <= budget) {
            return out;
        }

        if (progress != null) {
            progress.remove("rootGoal");
            trimArray(progress, "recentActions", 1);
        }
        trimArray(screen, "items", 4);
        trimArray(screen, "choices", 4);

        if (ContextPayloadBudget.utf8Bytes(out.toString()) <= budget) {
            return out;
        }

        // Preserve authoritative status/step/next first. Screen details are
        // lowest priority once the model-facing envelope is already oversized.
        if (screen != null) {
            screen.remove("items");
            JSONObject focus = screen.optJSONObject("focus");
            if (focus != null) {
                clipInPlace(focus, "label", 56);
                clipInPlace(focus, "role", 40);
                clipInPlace(focus, "can", 56);
            }
        }
        if (progress != null) {
            progress.remove("recentActions");
            clipInPlace(progress, "goal", 120);
            clipInPlace(progress, "currentApp", 56);
            clipInPlace(progress, "pendingTask", 56);
        }
        clipInPlace(out, "message", 110);
        trimArray(screen, "choices", 3);
        return out;
    }

    private static void trimArray(
            JSONObject owner,
            String key,
            int maxItems) {
        if (owner == null) return;
        JSONArray source = owner.optJSONArray(key);
        if (source == null || source.length() <= maxItems) return;
        JSONArray trimmed = new JSONArray();
        for (int i = 0; i < source.length() && i < maxItems; i++) {
            trimmed.put(source.opt(i));
        }
        try { owner.put(key, trimmed); } catch (Exception ignored) {}
    }

    private static void clipInPlace(
            JSONObject owner,
            String key,
            int maxChars) {
        if (owner == null) return;
        String value = owner.optString(key, "");
        if (value.isEmpty()) return;
        try { owner.put(key, clip(value, maxChars)); }
        catch (Exception ignored) {}
    }

    private static JSONObject step(ModelStepGuidance.Guidance guidance) {
        JSONObject out = new JSONObject();
        if (guidance == null) return out;
        try {
            if (!guidance.action.isEmpty()) out.put("action", guidance.action);
            out.put("outcome", guidance.effect.isEmpty() ? "UNKNOWN" : guidance.effect);
            if (!guidance.reason.isEmpty()) out.put("reason", guidance.reason);
            if (!guidance.next.isEmpty()) out.put("next", guidance.next);
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject progress(JSONObject source, String status) {
        JSONObject out = new JSONObject();
        try {
            out.put("state", ModelStepGuidance.progressState(status));
            if (source == null) return out;

            copyClipped(source, out, "goal", MAX_GOAL);
            copyClipped(source, out, "rootGoal", MAX_GOAL);
            copyClipped(source, out, "currentApp", MAX_LABEL);
            copyClipped(source, out, "pendingTask", MAX_LABEL);

            JSONArray sourceActions = source.optJSONArray("recentActions");
            if (sourceActions == null) sourceActions = source.optJSONArray("lastActions");
            if (sourceActions != null && sourceActions.length() > 0) {
                JSONArray actions = new JSONArray();
                int start = Math.max(0, sourceActions.length() - MAX_PROGRESS_ACTIONS);
                for (int i = start; i < sourceActions.length(); i++) {
                    String value = clip(sourceActions.optString(i, ""), MAX_LABEL);
                    if (!value.isEmpty()) actions.put(value);
                }
                if (actions.length() > 0) out.put("recentActions", actions);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean shouldCompact(String toolName) {
        return "phone_action".equals(toolName)
                || "inspect_ui".equals(toolName)
                || "send_text".equals(toolName)
                || "end_voice_session".equals(toolName)
                || "wait".equals(toolName)
                || "wait_then_action".equals(toolName)
                || "start_conversation_loop".equals(toolName)
                || "continue_conversation_loop".equals(toolName)
                || "stop_conversation_loop".equals(toolName)
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

        if ("WAITING_BACKGROUND".equals(taskState)) return WAIT;
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
            if ("ELEMENTS".equals(visualReference)) {
                return "Runtime 已依真實可點擊元素標號。只問使用者要哪個元素編號；回答後立刻用 phone_action(TAP,target=回答原文)，不要猜座標。";
            }
            if ("MULTIPLE_MATCHES".equals(upper(result.optString("status", "")))) {
                return "找到多個選項；用一句話讀出 screen.choices 並問使用者要哪個，然後等待回答。";
            }
            return "有多個可信結果；用一句話讀出 screen.choices 並詢問使用者，等待第一個/第二個/名稱/取消。";
        }

        if (WAIT.equals(status)) {
            if ("WAITING_BACKGROUND".equals(
                    upper(result.optString("taskState", "")))) {
                return "Runtime 已掛上背景 Accessibility event wait；不要輪詢或重複操作，等 Runtime 喚醒。";
            }
            if ("start_conversation_loop".equals(toolName)) {
                return "持續對話租約已啟用；繼續完成指定收件人的聊天室定位與第一則送出，不要提前作結論。";
            }
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
            if (containsAny(error, "PENDING_WAIT_SETUP_FAILED")) {
                return result.optString(
                        "instruction",
                        "無法建立等待任務；請確認要監控的 App 或條件。");
            }
            if (containsAny(error,
                    "UI_TARGET_NOT_FOUND",
                    "TARGET_NOT_FOUND",
                    "SEARCH_CONTROL_NOT_FOUND")) {
                return "找不到目標；不要自動開元素。可依目前畫面改用其他控制；使用者也可以主動說「顯示元素」。";
            }
            if (containsAny(error,
                    "ELEMENT_REFERENCE_OVERLAY_PERMISSION_REQUIRED",
                    "ELEMENT_REFERENCE_UNAVAILABLE",
                    "ELEMENT_REFERENCE_SENSITIVE_SCREEN",
                    "NO_CLICKABLE_ELEMENTS")) {
                return result.optString("instruction", "目前無法顯示可點擊元素。");
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

        if ("stop_conversation_loop".equals(toolName)) {
            return "持續對話模式已停止。";
        }
        if (isVerifiedSend(result) || "send_text".equals(toolName)) {
            return "訊息已送出。";
        }
        if ("wait".equals(toolName)) {
            return "等待的畫面已出現。";
        }
        if ("wait_then_action".equals(toolName)) {
            return "等待任務已交給 Runtime；不需要持續輪詢或保持 Gemini 在線。";
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
                    java.util.ArrayList<JSONObject> ranked =
                            new java.util.ArrayList<JSONObject>();
                    for (int i = 0; i < important.length(); i++) {
                        JSONObject raw = important.optJSONObject(i);
                        if (raw != null) ranked.add(raw);
                    }
                    java.util.Collections.sort(
                            ranked,
                            new java.util.Comparator<JSONObject>() {
                                @Override public int compare(
                                        JSONObject left,
                                        JSONObject right) {
                                    int leftScore =
                                            ScreenItemPriorityPolicy.score(
                                                    left.optString("role", ""),
                                                    left.optString("label", ""),
                                                    left.optString("semanticHint", ""),
                                                    left.optString("can", ""));
                                    int rightScore =
                                            ScreenItemPriorityPolicy.score(
                                                    right.optString("role", ""),
                                                    right.optString("label", ""),
                                                    right.optString("semanticHint", ""),
                                                    right.optString("can", ""));
                                    return Integer.compare(
                                            rightScore,
                                            leftScore);
                                }
                            });

                    JSONArray items = new JSONArray();
                    for (int i = 0;
                            i < ranked.size() && items.length() < MAX_SCREEN_ITEMS;
                            i++) {
                        JSONObject item = compactItem(ranked.get(i));
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
            copyClippedString(source, out, "semanticHint");
            copyClippedString(source, out, "can");
        } catch (Exception ignored) {}
        return out;
    }

    private static void copyClipped(
            JSONObject from, JSONObject to, String key, int max) {
        String value = clip(from.optString(key, ""), max);
        if (!value.isEmpty()) {
            try { to.put(key, value); } catch (Exception ignored) {}
        }
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
