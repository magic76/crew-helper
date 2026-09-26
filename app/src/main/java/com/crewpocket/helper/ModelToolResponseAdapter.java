package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 0079: small model-facing projection of full Runtime tool results.
 *
 * Runtime, logs, verification and task history keep the full internal result.
 * Gemini Live sees one canonical contract:
 *   action = what just happened, whether that action is verified/pending/failed
 *   goal = whole-user-goal state plus exactly one next directive
 *   screen = small current-screen projection when useful
 *   context = compact causal continuity (goal + recent semantic steps)
 *
 * Internal taskState / verificationStatus / completionEvidence remain Runtime
 * implementation details and are never exposed as competing state languages.
 *
 * Deck tools are deliberately left untouched because their structured card data
 * is presentation content, not phone-control debug metadata.
 */
final class ModelToolResponseAdapter {
    static final String DONE = "DONE";
    static final String WAIT = "WAIT";
    static final String NEED_USER = "NEED_USER";
    static final String FAILED = "FAILED";

    private static final int MAX_SCREEN_ITEMS = 12;
    private static final int MAX_CHOICES = 18;
    private static final int MAX_MESSAGE = 200;
    private static final int MAX_LABEL = 96;
    private static final int MAX_GOAL = 320;
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
            ModelStepGuidance.Guidance guidance = ModelStepGuidance.from(
                    toolName,
                    status,
                    source.optString("semanticAction", ""),
                    source.optString("resolvedByRuntime", ""),
                    source.optString("error", ""),
                    source.optString("taskState", ""),
                    source.optString("searchTransaction", ""),
                    isVerifiedSend(source));

            ModelRuntimeContract.Goal goal =
                    ModelRuntimeContract.deriveGoal(
                            source.optString("taskState", ""),
                            source.optString("nextRequirement", ""),
                            status,
                            guidance.reason,
                            guidance.next);

            JSONObject action = action(source, guidance, status);
            if (action.length() > 0) out.put("action", action);

            JSONObject goalJson = goal(goal);
            String goalIntent = progressContext == null
                    ? "" : progressContext.optString("goalIntent", "").trim();
            if (goalIntent.matches("[A-Z0-9:_-]{1,64}")) {
                goalJson.put("intent", goalIntent);
            }
            if (goalJson.length() > 0) out.put("goal", goalJson);

            String message = message(toolName, source, status);
            if (!message.isEmpty()) {
                out.put("message", clip(message, MAX_MESSAGE));
            }

            String goalText = progressContext == null
                    ? ""
                    : progressContext.optString(
                            "goal",
                            progressContext.optString("rootGoal", ""));
            String completedTarget =
                    latestActivatedTarget(
                            progressContext,
                            action,
                            status);
            JSONObject screen = screen(
                    source,
                    goalIntent,
                    goalText,
                    completedTarget);
            if (screen.length() > 0) out.put("screen", screen);

            JSONObject context = context(progressContext, action);
            if (context.length() > 0) out.put("context", context);
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
        JSONObject context = out.optJSONObject("context");

        trimArray(context, "refinedMemory", 2);
        trimArray(screen, "items", 10);
        trimArray(screen, "choices", 12);
        trimTailArray(context, "recentSteps", 2);
        clipInPlace(out, "message", 170);
        clipInPlace(context, "goal", 260);
        clipInPlace(context, "rootGoal", 260);

        if (ContextPayloadBudget.utf8Bytes(out.toString()) <= budget) {
            return out;
        }

        if (context != null) {
            trimArray(context, "refinedMemory", 1);
            trimTailArray(context, "recentSteps", 1);
        }
        trimArray(screen, "items", 8);
        trimArray(screen, "choices", 8);

        if (ContextPayloadBudget.utf8Bytes(out.toString()) <= budget) {
            return out;
        }

        // Last-resort compaction still preserves a mixed current-screen view.
        // Losing every screen item made the model more rule-bound but less aware
        // of what the user was actually looking at.
        trimArray(screen, "items", 6);
        trimArray(screen, "choices", 4);
        if (context != null) {
            context.remove("refinedMemory");
            context.remove("recentSteps");
            String goal = context.optString("goal", "");
            String rootGoal = context.optString("rootGoal", "");
            if (!goal.isEmpty() && goal.equals(rootGoal)) {
                context.remove("rootGoal");
            }
            clipInPlace(context, "goal", 180);
            clipInPlace(context, "rootGoal", 180);
            clipInPlace(context, "currentApp", 64);
            clipInPlace(context, "pendingTask", 64);
        }
        clipInPlace(out, "message", 120);
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

    private static void trimTailArray(
            JSONObject owner,
            String key,
            int maxItems) {
        if (owner == null) return;
        JSONArray source = owner.optJSONArray(key);
        if (source == null || source.length() <= maxItems) return;
        JSONArray trimmed = new JSONArray();
        int start = Math.max(0, source.length() - maxItems);
        for (int i = start; i < source.length(); i++) {
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

    private static JSONObject action(
            JSONObject source,
            ModelStepGuidance.Guidance guidance,
            String status) {
        JSONObject out = new JSONObject();
        if (guidance == null) return out;
        try {
            if (!guidance.action.isEmpty()) {
                out.put("type", guidance.action);
            }
            boolean pendingVerification =
                    "PENDING".equals(upper(
                            source.optString("verificationStatus", "")))
                    || containsAny(
                            upper(source.optString("error", "")),
                            "OBSERVE_REQUIRED",
                            "PENDING_VERIFICATION");
            out.put(
                    "state",
                    ModelRuntimeContract.actionState(
                            status,
                            source.optBoolean("success", false),
                            isVerifiedSend(source),
                            pendingVerification,
                            source.optBoolean("blockedByRuntime", false)));
            if (!guidance.effect.isEmpty()) {
                out.put("effect", guidance.effect);
            }
            String target = modelTarget(source);
            if (!target.isEmpty()) out.put("target", target);
            if (!guidance.reason.isEmpty()) {
                out.put("reason", guidance.reason);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject goal(ModelRuntimeContract.Goal goal) {
        JSONObject out = new JSONObject();
        if (goal == null) return out;
        try {
            out.put("state", goal.state);
            out.put("next", goal.next);
            if (!goal.requiredTool.isEmpty()) {
                out.put("requiredTool", goal.requiredTool);
            }
            if (!goal.requiredAction.isEmpty()) {
                out.put("requiredAction", goal.requiredAction);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject context(
            JSONObject source,
            JSONObject currentAction) {
        JSONObject out = new JSONObject();
        try {
            if (source != null) {
                copyClipped(source, out, "goal", MAX_GOAL);
                copyClipped(source, out, "rootGoal", MAX_GOAL);
                copyClipped(source, out, "currentApp", MAX_LABEL);
                copyClipped(source, out, "pendingTask", MAX_LABEL);

                JSONArray refinedMemory =
                        source.optJSONArray("refinedMemory");
                if (refinedMemory != null) {
                    JSONArray compactMemory = new JSONArray();
                    for (int i = 0;
                            i < refinedMemory.length()
                                    && compactMemory.length() < 2;
                            i++) {
                        String value =
                                refinedMemory.optString(i, "").trim();
                        if (!value.isEmpty()) {
                            compactMemory.put(clip(value, 180));
                        }
                    }
                    if (compactMemory.length() > 0) {
                        out.put("refinedMemory", compactMemory);
                    }
                }

                JSONObject search = source.optJSONObject("search");
                if (search != null) {
                    JSONObject compactSearch = new JSONObject();
                    String phase = search.optString("phase", "").trim();
                    String continuation =
                            search.optString("continuation", "").trim();
                    if (phase.matches("[A-Z0-9:_-]{1,64}")) {
                        compactSearch.put("phase", phase);
                    }
                    if (continuation.matches("[A-Z0-9:_-]{1,64}")) {
                        compactSearch.put("continuation", continuation);
                    }
                    if (compactSearch.length() > 0) {
                        out.put("search", compactSearch);
                    }
                }
            }

            JSONArray steps = new JSONArray();
            JSONArray previous =
                    source == null ? null : source.optJSONArray("recentSteps");
            if (previous != null) {
                int start = Math.max(
                        0,
                        previous.length() - (MAX_PROGRESS_ACTIONS - 1));
                for (int i = start; i < previous.length(); i++) {
                    JSONObject item = compactRecentStep(
                            previous.optJSONObject(i));
                    if (item.length() > 0) steps.put(item);
                }
            }

            JSONObject current = compactRecentStep(currentAction);
            if (current.length() > 0) steps.put(current);
            if (steps.length() > 0) out.put("recentSteps", steps);
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject compactRecentStep(JSONObject source) {
        JSONObject out = new JSONObject();
        if (source == null) return out;
        try {
            String action = source.optString(
                    "action",
                    source.optString("type", "")).trim();
            String target = source.optString("target", "").trim();
            String effect = source.optString("effect", "").trim();
            if (!action.isEmpty()) {
                out.put("action", clip(action, 48));
            }
            if (!target.isEmpty()) {
                out.put("target", clip(target, 80));
            }
            if (!effect.isEmpty()) {
                out.put("effect", clip(effect, 64));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String modelTarget(JSONObject result) {
        if (result == null) return "";
        String target = result.optString("modelTarget", "").trim();
        if (target.isEmpty()) {
            target = result.optString("semanticTarget", "").trim();
        }
        if (target.length() > 80) return "";
        return target.matches("[A-Za-z0-9:_*\\-\\p{L} ]{1,80}")
                ? target
                : "";
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
        if ("IN_PROGRESS".equals(taskState)
                && containsAny(error, "CONVERSATION_LOOP_TOOL_BLOCKED")) {
            return WAIT;
        }
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
            if (containsAny(error, "CONVERSATION_LOOP_TOOL_BLOCKED")) {
                return result.optString(
                        "instruction",
                        "conversation loop 仍有效；若有新訊息用 send_text，若只是 UI noise 用 continue_conversation_loop。");
            }
            if ("start_conversation_loop".equals(toolName)) {
                return "持續對話租約已啟用；繼續目前聊天室任務，不要提前作結論。";
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
            return "這一步已驗證。";
        }
        if ("BACK".equals(semantic) || "HOME".equals(semantic)
                || "press_key".equals(runtime)) {
            return "這一步已驗證。";
        }

        String existing = result.optString("message", "").trim();
        return existing.isEmpty() ? "這一步已驗證。" : existing;
    }

    private static JSONObject screen(
            JSONObject result,
            String goalIntent,
            String goalText,
            String completedTarget) {
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
                if (source.optBoolean("fresh", false)) {
                    out.put("fresh", true);
                }
                JSONArray important = source.optJSONArray("important");
                if (important != null && important.length() > 0) {
                    JSONArray items = balancedImportantItems(
                            important,
                            goalIntent,
                            goalText,
                            completedTarget);
                    if (items.length() > 0) out.put("items", items);
                }

                JSONObject focus = compactItem(source.optJSONObject("focus"));
                if (focus.length() > 0) out.put("focus", focus);

                if (source.optBoolean("visionRecommended", false)) {
                    out.put("visionRecommended", true);
                }
            }

            JSONObject visualLease =
                    result.optJSONObject("visualTapLease");
            if (visualLease != null) {
                String leaseId =
                        visualLease.optString("id", "").trim();
                if (leaseId.matches("vt_[0-9a-f]+_[0-9a-f]+")) {
                    JSONObject compactLease = new JSONObject()
                            .put("id", leaseId)
                            .put(
                                    "coordinateSpace",
                                    "normalized_1000");
                    int expiresInMs =
                            visualLease.optInt("expiresInMs", 0);
                    if (expiresInMs > 0) {
                        compactLease.put(
                                "expiresInMs", expiresInMs);
                    }
                    out.put("visualTapLease", compactLease);
                }
            }

            JSONArray choices = choices(result);
            if (choices.length() > 0) out.put("choices", choices);
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONArray balancedImportantItems(
            JSONArray important,
            final String goalIntent,
            final String goalText,
            final String completedTarget) {
        java.util.ArrayList<JSONObject> original =
                new java.util.ArrayList<JSONObject>();
        java.util.ArrayList<JSONObject> actions =
                new java.util.ArrayList<JSONObject>();
        java.util.ArrayList<JSONObject> scenes =
                new java.util.ArrayList<JSONObject>();
        java.util.ArrayList<JSONObject> ranked =
                new java.util.ArrayList<JSONObject>();

        for (int i = 0; i < important.length(); i++) {
            JSONObject item = important.optJSONObject(i);
            if (item == null) continue;
            original.add(item);
            ranked.add(item);
            if (ScreenItemPriorityPolicy.isActionable(
                    item.optString("role", ""),
                    item.optString("label", ""),
                    item.optString("semanticHint", ""),
                    item.optString("can", ""))) {
                actions.add(item);
            } else if (ScreenItemPriorityPolicy.isSceneContext(
                    item.optString("role", ""),
                    item.optString("label", ""),
                    item.optString("semanticHint", ""),
                    item.optString("can", ""))) {
                scenes.add(item);
            }
        }

        java.util.Comparator<JSONObject> byPriority =
                new java.util.Comparator<JSONObject>() {
                    @Override public int compare(JSONObject left, JSONObject right) {
                        return Integer.compare(
                                screenScore(
                                        right,
                                        goalIntent,
                                        goalText,
                                        completedTarget),
                                screenScore(
                                        left,
                                        goalIntent,
                                        goalText,
                                        completedTarget));
                    }
                };
        java.util.Collections.sort(actions, byPriority);
        java.util.Collections.sort(ranked, byPriority);

        if (MediaGoalUiPolicy.isMediaPlayGoal(goalIntent)) {
            JSONArray mediaOrdered = new JSONArray();
            for (JSONObject item : ranked) {
                if (mediaOrdered.length() >= MAX_SCREEN_ITEMS) break;
                JSONObject compact = compactItem(item);
                if (compact.length() > 0) mediaOrdered.put(compact);
            }
            return mediaOrdered;
        }

        int actionLimit = Math.min(6, actions.size());
        int sceneLimit = Math.min(6, scenes.size());
        java.util.ArrayList<JSONObject> selected =
                new java.util.ArrayList<JSONObject>();

        int actionIndex = 0;
        int sceneIndex = 0;
        while (selected.size() < MAX_SCREEN_ITEMS
                && (actionIndex < actionLimit || sceneIndex < sceneLimit)) {
            if (actionIndex < actionLimit) {
                selected.add(actions.get(actionIndex++));
            }
            if (selected.size() >= MAX_SCREEN_ITEMS) break;
            if (sceneIndex < sceneLimit) {
                JSONObject scene = scenes.get(sceneIndex++);
                if (!selected.contains(scene)) selected.add(scene);
            }
        }

        for (JSONObject item : ranked) {
            if (selected.size() >= MAX_SCREEN_ITEMS) break;
            if (!selected.contains(item)) selected.add(item);
        }

        JSONArray out = new JSONArray();
        for (JSONObject item : selected) {
            JSONObject compact = compactItem(item);
            if (compact.length() > 0) out.put(compact);
        }
        return out;
    }

    private static int screenScore(
            JSONObject item,
            String goalIntent,
            String goalText,
            String completedTarget) {
        if (item == null) return 0;
        return MediaGoalUiPolicy.scoreItem(
                item.optString("role", ""),
                item.optString("label", ""),
                item.optString("semanticHint", ""),
                item.optString("can", ""),
                goalIntent,
                goalText,
                completedTarget);
    }

    private static String latestActivatedTarget(
            JSONObject progressContext,
            JSONObject currentAction,
            String status) {
        if (currentAction != null
                && DONE.equals(status)
                && "UI_ACTIVATED".equals(
                        currentAction.optString("effect", ""))) {
            String target =
                    currentAction.optString("target", "").trim();
            if (!target.isEmpty()) return target;
        }

        JSONArray steps =
                progressContext == null
                        ? null
                        : progressContext.optJSONArray("recentSteps");
        if (steps == null) return "";
        for (int i = steps.length() - 1; i >= 0; i--) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null
                    || !"UI_ACTIVATED".equals(
                            step.optString("effect", ""))) {
                continue;
            }
            String target =
                    step.optString("target", "").trim();
            if (!target.isEmpty()) return target;
        }
        return "";
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
            String id = source.optString("id", "").trim();
            if (id.matches("e_[0-9a-fA-F]{8,32}")) {
                out.put("id", id);
            }
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
