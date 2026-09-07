package com.crewpocket.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * 0026 Correction Learning Runtime.
 *
 * Learning signal:
 * recent structural mutation -> explicit Interrupt/Correction ->
 * next successful structural mutation from the SAME original app/screen.
 *
 * No LLM is used to decide what becomes long-term memory.
 */
final class CorrectionLearningRuntime {
    private static final long RECENT_MUTATION_MS = 45_000L;
    private static final long PENDING_CORRECTION_MS = 90_000L;

    static final class Decision {
        final boolean applied;
        final String toolName;
        final JSONObject args;
        final String ruleId;

        Decision(boolean applied, String toolName, JSONObject args, String ruleId) {
            this.applied = applied;
            this.toolName = toolName == null ? "" : toolName;
            this.args = copy(args);
            this.ruleId = ruleId == null ? "" : ruleId;
        }

        static Decision none(String requestedTool, JSONObject requestedArgs) {
            return new Decision(false, requestedTool, requestedArgs, "");
        }
    }

    private static final class MutationSnapshot {
        String packageName = "";
        String screenFingerprint = "";
        String requestedTool = "";
        JSONObject requestedArgs = new JSONObject();
        long atMs;
    }

    private static CorrectionRuleStore store;
    private static MutationSnapshot lastMutation;
    private static MutationSnapshot pendingWrong;
    private static long pendingStartedAt;

    private CorrectionLearningRuntime() {}

    static synchronized void init(Context context) {
        if (context == null) return;
        store = new CorrectionRuleStore(context.getApplicationContext());
    }

    private static synchronized boolean ensureInitialized() {
        if (store != null) return true;
        CrewAccessibilityService service = CrewAccessibilityService.getInstance();
        if (service != null) init(service);
        return store != null;
    }

    static synchronized boolean beginCorrection() {
        expirePendingIfNeeded();
        if (!ensureInitialized()) return false;
        if (lastMutation == null) return false;

        long age = System.currentTimeMillis() - lastMutation.atMs;
        if (age < 0 || age > RECENT_MUTATION_MS) return false;
        if (lastMutation.packageName.isEmpty()
                || lastMutation.screenFingerprint.isEmpty()) {
            return false;
        }

        pendingWrong = cloneSnapshot(lastMutation);
        pendingStartedAt = System.currentTimeMillis();
        showStatus(
                "修正學習",
                "等待正確操作；Runtime 驗證成功後會記住");
        return true;
    }

    static synchronized void cancelPendingCorrection() {
        pendingWrong = null;
        pendingStartedAt = 0L;
    }

    static synchronized Decision beforeMutation(String requestedTool,
                                                JSONObject requestedArgs,
                                                JSONObject beforeContext) {
        expirePendingIfNeeded();
        if (!CorrectionRuleStore.isLearnableTool(requestedTool)) {
            return Decision.none(requestedTool, requestedArgs);
        }
        if (!ensureInitialized()) {
            return Decision.none(requestedTool, requestedArgs);
        }

        // While capturing a fresh correction, do not let an old rule hide the
        // user's new teaching signal.
        if (pendingWrong != null) {
            return Decision.none(requestedTool, requestedArgs);
        }

        String pkg = contextString(beforeContext, "currentApp");
        String screen = screenMatchKey(beforeContext);
        String legacyScreen = contextString(beforeContext, "currentScreen");
        CorrectionRuleStore.Rule rule =
                store.findBest(pkg, screen, requestedTool, requestedArgs);
        if (rule == null && !legacyScreen.isEmpty() && !legacyScreen.equals(screen)) rule = store.findBest(pkg, legacyScreen, requestedTool, requestedArgs);
        if (rule == null) {
            return Decision.none(requestedTool, requestedArgs);
        }

        try {
            return new Decision(
                    true,
                    rule.correctTool,
                    new JSONObject(rule.correctArgs),
                    rule.id);
        } catch (Exception ignored) {
            return Decision.none(requestedTool, requestedArgs);
        }
    }

    static synchronized void afterMutation(String requestedTool,
                                           JSONObject requestedArgs,
                                           JSONObject result,
                                           JSONObject beforeContext,
                                           Decision decision) {
        expirePendingIfNeeded();
        if (!ensureInitialized()) return;

        // type_text / send_text are intentionally never long-term correction
        // candidates. Clear the "recent wrong action" pointer so interrupting a
        // text mutation cannot accidentally attach to an older tap.
        if (!CorrectionRuleStore.isLearnableTool(requestedTool)) {
            lastMutation = null;
            return;
        }

        boolean success = isSuccess(result);

        if (decision != null && decision.applied && !decision.ruleId.isEmpty()) {
            store.recordResult(decision.ruleId, success);
        }

        MutationSnapshot current = new MutationSnapshot();
        current.packageName = contextString(beforeContext, "currentApp");
        current.screenFingerprint = screenMatchKey(beforeContext);
        current.requestedTool = requestedTool == null ? "" : requestedTool;
        current.requestedArgs =
                CorrectionRuleStore.sanitizeArgs(requestedTool, requestedArgs);
        current.atMs = System.currentTimeMillis();

        if (pendingWrong != null && success) {
            boolean sameOriginalScreen =
                    pendingWrong.packageName.equals(current.packageName)
                    && pendingWrong.screenFingerprint.equals(
                            current.screenFingerprint);

            String effectiveTool =
                    decision != null && decision.applied
                            ? decision.toolName
                            : requestedTool;
            JSONObject effectiveArgs =
                    decision != null && decision.applied
                            ? decision.args
                            : requestedArgs;

            String wrongSignature =
                    CorrectionRuleStore.signature(
                            pendingWrong.requestedTool,
                            pendingWrong.requestedArgs);
            String correctSignature =
                    CorrectionRuleStore.signature(
                            effectiveTool,
                            effectiveArgs);
            boolean different =
                    !pendingWrong.requestedTool.equals(effectiveTool)
                    || !wrongSignature.equals(correctSignature);

            if (sameOriginalScreen
                    && different
                    && CorrectionRuleStore.isLearnableTool(effectiveTool)) {
                CorrectionRuleStore.Rule learned =
                        store.upsertCorrection(
                                pendingWrong.packageName,
                                pendingWrong.screenFingerprint,
                                pendingWrong.requestedTool,
                                pendingWrong.requestedArgs,
                                effectiveTool,
                                effectiveArgs);
                if (learned != null) {
                    showStatus(
                            "已學會修正",
                            CorrectionRuleStore.describeAction(
                                    learned.correctTool,
                                    parse(learned.correctArgs)));
                }
                pendingWrong = null;
                pendingStartedAt = 0L;
            }
            // Example: wrong tap entered another page. BACK is executed from that
            // other page, so it is NOT learned. Pending stays alive. Once the
            // original screen is restored, the actual corrected tap is captured.
        }

        lastMutation = current;
    }

    private static boolean isSuccess(JSONObject result) {
        if (result == null) return false;
        if ("STEP_OK".equals(result.optString("stepResult", ""))) return true;
        return result.optBoolean("success", false)
                && !result.optBoolean("cancelled", false)
                && !result.optBoolean("agentStopped", false);
    }

    private static void expirePendingIfNeeded() {
        if (pendingWrong == null) return;
        long age = System.currentTimeMillis() - pendingStartedAt;
        if (age < 0 || age > PENDING_CORRECTION_MS) {
            pendingWrong = null;
            pendingStartedAt = 0L;
        }
    }

    private static String screenMatchKey(JSONObject context) { String stable=contextString(context, "stableScreen"); return stable.isEmpty()?contextString(context,"currentScreen"):stable; }
    private static String contextString(JSONObject context, String key) {
        return context == null ? "" : context.optString(key, "").trim();
    }

    private static JSONObject copy(JSONObject input) {
        if (input == null) return new JSONObject();
        try {
            return new JSONObject(input.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static JSONObject parse(String raw) {
        try {
            return new JSONObject(raw == null ? "{}" : raw);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static MutationSnapshot cloneSnapshot(MutationSnapshot source) {
        MutationSnapshot out = new MutationSnapshot();
        out.packageName = source.packageName;
        out.screenFingerprint = source.screenFingerprint;
        out.requestedTool = source.requestedTool;
        out.requestedArgs = copy(source.requestedArgs);
        out.atMs = source.atMs;
        return out;
    }

    private static void showStatus(final String title, final String detail) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                FloatingBubbleManager manager = FloatingBubbleManager.getInstance();
                if (manager != null) {
                    manager.showCompactStatus(title, detail);
                }
            }
        });
    }
}
