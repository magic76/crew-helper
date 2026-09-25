package com.crewpocket.helper;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Owns Runtime observation state and post-mutation verification/reconciliation.
 *
 * Execution policy stays outside this controller. It records real screen
 * evidence, updates Runtime ledgers, and projects one authoritative mutation
 * result for the model.
 */
final class ObservationVerificationController {
    private static final String TAG = "CrewNativeLive";

    private final PhoneRuntimeExecutor phoneRuntimeExecutor;
    private final AgentRuntimeV2 agentRuntimeV2;
    private final ShadowAgentRuntime shadowAgentRuntime;
    private final WorkingContext workingContext;

    private volatile String latestSemanticFingerprint = "";
    private volatile ActionObservation latestActionObservation =
            ActionObservation.unavailable();
    private volatile boolean semanticObserveRequired;
    private volatile AgentRuntimeV2.ReverificationSummary lastReverification =
            AgentRuntimeV2.ReverificationSummary.empty();

    // Legacy /screen_state verification state retained for compatibility.
    private volatile String lastObservedScreenFingerprint = "";
    private volatile int consecutiveNoProgress;

    ObservationVerificationController(
            PhoneRuntimeExecutor phoneRuntimeExecutor,
            AgentRuntimeV2 agentRuntimeV2,
            ShadowAgentRuntime shadowAgentRuntime,
            WorkingContext workingContext) {
        if (phoneRuntimeExecutor == null) {
            throw new IllegalArgumentException(
                    "phoneRuntimeExecutor required");
        }
        if (agentRuntimeV2 == null) {
            throw new IllegalArgumentException(
                    "agentRuntimeV2 required");
        }
        if (shadowAgentRuntime == null) {
            throw new IllegalArgumentException(
                    "shadowAgentRuntime required");
        }
        if (workingContext == null) {
            throw new IllegalArgumentException(
                    "workingContext required");
        }
        this.phoneRuntimeExecutor = phoneRuntimeExecutor;
        this.agentRuntimeV2 = agentRuntimeV2;
        this.shadowAgentRuntime = shadowAgentRuntime;
        this.workingContext = workingContext;
    }

    String latestFingerprint() {
        return latestSemanticFingerprint;
    }

    ActionObservation latestObservation() {
        return latestActionObservation;
    }

    boolean semanticObserveRequired() {
        return semanticObserveRequired;
    }

    void setSemanticObserveRequired(boolean required) {
        semanticObserveRequired = required;
    }

    void setLatestFingerprint(String fingerprint) {
        latestSemanticFingerprint =
                fingerprint == null ? "" : fingerprint;
    }

    String lastObservedScreenFingerprint() {
        return lastObservedScreenFingerprint;
    }

    void clearSemanticTransient() {
        semanticObserveRequired = false;
        latestSemanticFingerprint = "";
        lastReverification = AgentRuntimeV2.ReverificationSummary.empty();
    }

    void resetNoProgress() {
        consecutiveNoProgress = 0;
    }

    JSONObject readSemanticScreenQuietly() {
        try {
            return phoneRuntimeExecutor.get("/semantic_screen");
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Record a normal semantic observation and feed it to both Runtime ledgers.
     */
    ActionObservation recordSemanticObservation(JSONObject screen) {
        if (screen == null || !screen.optBoolean("success", false)) {
            return latestActionObservation;
        }

        String fp = screen.optString("fingerprint", "");
        latestSemanticFingerprint = fp;
        latestActionObservation = toActionObservation(screen);
        agentRuntimeV2.onScreenObserved(latestActionObservation);
        lastReverification =
                agentRuntimeV2.reverifyPendingDetailed(
                        latestActionObservation);
        shadowAgentRuntime.onScreenObserved(
                fp,
                screen.optString("stableScreenKey", ""),
                screen.optString("package", ""));
        semanticObserveRequired = false;
        workingContext.observe(
                screen.optString("package", ""),
                fp,
                screen.optString("stableScreenKey", ""));
        return latestActionObservation;
    }

    synchronized JSONObject consumeReverificationSummary() {
        AgentRuntimeV2.ReverificationSummary summary = lastReverification;
        lastReverification = AgentRuntimeV2.ReverificationSummary.empty();
        if (summary == null || !summary.hasAny()) return null;

        JSONObject out = new JSONObject();
        try {
            out.put("committed", summary.committed)
                    .put("failed", summary.failed)
                    .put("pending", summary.pending);
            if (!summary.failedRuntimeName.isEmpty()) {
                out.put("failedRuntime", summary.failedRuntimeName);
            }
            if (!summary.failedCode.isEmpty()) {
                out.put("failedCode", summary.failedCode);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Condition/search helpers historically updated only the compact semantic
     * fingerprint + WorkingContext. Preserve that narrower behavior.
     */
    void recordFingerprintOnly(JSONObject screen) {
        if (screen == null || !screen.optBoolean("success", false)) return;
        String fp = screen.optString("fingerprint", "");
        latestSemanticFingerprint = fp;
        semanticObserveRequired = false;
        workingContext.observe(
                screen.optString("package", ""),
                fp,
                screen.optString("stableScreenKey", ""));
    }

    void recordLegacyScreen(String fingerprint, String packageName) {
        String fp = fingerprint == null ? "" : fingerprint;
        if (fp.isEmpty()) return;
        lastObservedScreenFingerprint = fp;
        shadowAgentRuntime.onScreenObserved(
                fp, "", packageName == null ? "" : packageName);
    }

    JSONObject autoObserveAfterMutation(
            JSONObject actionResult,
            String actionName) {
        if (actionResult == null) actionResult = new JSONObject();

        final boolean executionSuccess =
                actionResult.optBoolean("success", false);
        final boolean reconciliationBlocked =
                blocksOutcomeReconciliation(actionResult);
        final String beforeFingerprint =
                latestSemanticFingerprint;

        try {
            Thread.sleep(mutationSettleDelayMs(actionName));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        JSONObject after = readSemanticScreenQuietly();
        boolean changed =
                semanticScreenChanged(after, beforeFingerprint);

        // A false-negative Android result can race the actual UI transition.
        // Retry once only for potentially recoverable raw failures.
        if (!executionSuccess
                && !reconciliationBlocked
                && !changed
                && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(240L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            JSONObject retry = readSemanticScreenQuietly();
            if (retry != null
                    && retry.optBoolean("success", false)) {
                after = retry;
                changed = semanticScreenChanged(
                        after, beforeFingerprint);
            }
        }

        final boolean mapsTapAwaitingVerification =
                !executionSuccess
                        && !reconciliationBlocked
                        && ("tap_screen".equals(actionName)
                            || "tap_element".equals(actionName))
                        && after != null
                        && after.optBoolean("success", false)
                        && GoogleMapsRuntimeAdapter.PACKAGE_NAME.equals(
                                after.optString("package", ""));

        final boolean finalSuccess =
                executionSuccess
                        || (!reconciliationBlocked && changed)
                        || mapsTapAwaitingVerification;

        if (after != null && after.optBoolean("success", false)) {
            recordSemanticObservation(after);
        } else if (!executionSuccess) {
            // A successful Android action must not be blocked merely because
            // the target app is between Accessibility frames.
            semanticObserveRequired = true;
        } else {
            semanticObserveRequired = false;
        }

        try {
            actionResult.put("success", finalSuccess)
                    .put(
                            "stepResult",
                            finalSuccess
                                    ? "STEP_OK"
                                    : "STEP_FAILED")
                    .put("screenChanged", changed);

            if (after != null
                    && after.optBoolean("success", false)) {
                actionResult.put(
                        "after",
                        ModelScreenView.compact(
                                after,
                                "AUTO_AFTER_ACTION"));
            }

            // Remove legacy fields that may contradict the reconciled result.
            actionResult.remove("progress");
            actionResult.remove("noProgressCount");
            actionResult.remove("recoveryHint");
            actionResult.remove("autoVerification");
            actionResult.remove("verification");
            actionResult.remove("fingerprint");

            if (finalSuccess) {
                actionResult.remove("error");
                if (mapsTapAwaitingVerification) {
                    actionResult.put("verification", "PENDING")
                            .put(
                                    "message",
                                    "地圖操作已送出，畫面仍在更新；不要宣告失敗，請依最新畫面繼續確認。");
                    Log.i(
                            TAG,
                            "Maps gesture accepted as STEP_OK while verification is pending: "
                                    + actionName);
                } else if (!executionSuccess) {
                    actionResult.put("message", "操作已生效。");
                    Log.i(
                            TAG,
                            "Reconciled false-negative mutation as STEP_OK: "
                                    + actionName);
                }
            } else {
                actionResult.put(
                        "message",
                        "這一步未確認生效，請依最新畫面改用其他方法，不要重複相同操作。");
            }

            workingContext.updateLastResult(
                    finalSuccess ? "STEP_OK" : "STEP_FAILED");
            actionResult.put(
                    "runtimeContext",
                    workingContext.toModelJson());
        } catch (Exception ignored) {}

        return actionResult;
    }

    boolean blocksOutcomeReconciliation(JSONObject result) {
        if (result == null) return false;
        if (result.optBoolean("cancelled", false)
                || result.optBoolean("agentStopped", false)) {
            return true;
        }

        String error = result.optString("error", "")
                .trim()
                .toUpperCase(Locale.ROOT);
        if (error.isEmpty()) return false;

        return error.contains("MISSING")
                || error.contains("NOT_FOUND")
                || error.contains("INVALID")
                || error.contains("UNSUPPORTED")
                || error.contains("POLICY")
                || error.contains("BLOCKED")
                || error.contains("DENIED")
                || error.contains("SENSITIVE")
                || error.contains("找不到")
                || error.contains("不可為空")
                || error.contains("不支援")
                || error.contains("禁止")
                || error.contains("使用者已停止");
    }

    /**
     * Legacy screen-state verifier. Kept here so all observation state has one
     * owner even while older callers are being retired.
     */
    void autoVerifyUiSnapshot(
            JSONObject response,
            int waitDelayMs) {
        if (response == null
                || !response.optBoolean("success", false)) {
            return;
        }

        try {
            String beforeFingerprint =
                    lastObservedScreenFingerprint;
            if (waitDelayMs > 0) {
                Thread.sleep(waitDelayMs);
            }

            JSONObject raw =
                    phoneRuntimeExecutor.get("/screen_state");
            if (!raw.optBoolean("success")) return;

            String currentPkg =
                    raw.optString("package", "");
            String afterFingerprint =
                    raw.optString("fingerprint", "");
            String progress = "UNKNOWN";

            if (!beforeFingerprint.isEmpty()
                    && !afterFingerprint.isEmpty()) {
                progress = beforeFingerprint.equals(
                        afterFingerprint)
                        ? "UNCHANGED"
                        : "PROGRESSED";
                if ("UNCHANGED".equals(progress)) {
                    consecutiveNoProgress++;
                } else {
                    consecutiveNoProgress = 0;
                }
            }

            if (!afterFingerprint.isEmpty()) {
                lastObservedScreenFingerprint =
                        afterFingerprint;
                latestActionObservation =
                        toActionObservation(raw);
                agentRuntimeV2.onScreenObserved(
                        latestActionObservation);
                shadowAgentRuntime.onScreenObserved(
                        afterFingerprint,
                        "",
                        currentPkg);
            }

            response.put("progress", progress);
            response.put(
                    "noProgressCount",
                    consecutiveNoProgress);
            response.put(
                    "fingerprint",
                    afterFingerprint);

            if ("UNCHANGED".equals(progress)) {
                response.put(
                        "recoveryHint",
                        consecutiveNoProgress >= 2
                                ? "畫面連續沒有進展：停止重複同一動作，改用返回、重新聚焦、另一個 semantic action，或向使用者說明卡點。"
                                : "畫面沒有改變：重新 inspect_ui，下一步不可原樣重複剛才動作。");
            }

            JSONArray nodes = raw.optJSONArray("nodes");
            JSONArray actions = raw.optJSONArray("actions");
            JSONArray visible = new JSONArray();

            if (nodes != null) {
                for (int i = 0;
                        i < nodes.length()
                                && visible.length() < 30;
                        i++) {
                    JSONObject node =
                            nodes.optJSONObject(i);
                    if (node == null) continue;

                    String text =
                            node.optString("text", "").trim();
                    String desc =
                            node.optString("desc", "").trim();
                    if (text.isEmpty() && desc.isEmpty()) {
                        continue;
                    }

                    visible.put(
                            new JSONObject()
                                    .put("text", text)
                                    .put("desc", desc)
                                    .put(
                                            "clickable",
                                            node.optBoolean(
                                                    "clickable")));
                }
            }

            JSONObject verification =
                    new JSONObject();
            verification.put(
                    "currentPackage",
                    currentPkg);
            verification.put(
                    "verifiedNodeCount",
                    nodes == null ? 0 : nodes.length());
            verification.put(
                    "actualVisibleContent",
                    visible);
            if (actions != null) {
                verification.put(
                        "verifiedActions",
                        actions);
            }
            verification.put(
                    "instruction",
                    "【系統真實校驗結果】以上為動作執行後的真實畫面內容。請直接根據 actualVisibleContent 向使用者報告實際看見的狀態，絕對不可捏造尚未出現的內容！");
            response.put(
                    "autoVerification",
                    verification);
        } catch (Exception ignored) {}
    }

    private ActionObservation toActionObservation(
            JSONObject screen) {
        if (screen == null
                || !screen.optBoolean("success", false)) {
            return ActionObservation.unavailable();
        }

        String focusedKey = "";
        String focusedRole = "";
        JSONArray elements =
                screen.optJSONArray("elements");

        if (elements != null) {
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element =
                        elements.optJSONObject(i);
                if (element == null
                        || !element.optBoolean(
                                "focused", false)) {
                    continue;
                }

                focusedKey =
                        element.optString("viewId", "")
                                + "|"
                                + element.optString(
                                        "semanticHint", "")
                                + "|"
                                + element.optString(
                                        "role", "");
                focusedRole =
                        element.optString("role", "");
                break;
            }
        }

        return new ActionObservation(
                true,
                screen.optString("package", ""),
                screen.optString("fingerprint", ""),
                screen.optString(
                        "stableScreenKey", ""),
                focusedKey,
                focusedRole,
                elements == null ? 0 : elements.length(),
                System.currentTimeMillis());
    }

    private boolean semanticScreenChanged(
            JSONObject after,
            String beforeFingerprint) {
        if (after == null
                || !after.optBoolean("success", false)) {
            return false;
        }

        String afterFingerprint =
                after.optString("fingerprint", "");
        return !beforeFingerprint.isEmpty()
                && !afterFingerprint.isEmpty()
                && !beforeFingerprint.equals(
                        afterFingerprint);
    }

    private long mutationSettleDelayMs(
            String actionName) {
        if ("launch_app".equals(actionName)) return 650L;
        if ("commit_search".equals(actionName)) return 700L;
        if ("search_current_app".equals(actionName)) return 520L;
        if ("press_key".equals(actionName)) return 320L;
        if ("type_text".equals(actionName)) return 260L;
        if ("swipe_screen".equals(actionName)) return 220L;
        return 220L;
    }
}
