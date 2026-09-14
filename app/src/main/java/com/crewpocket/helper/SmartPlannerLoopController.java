package com.crewpocket.helper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 0130 vertical slice: Crew Pocket planner -> Crew Helper semantic Runtime.
 *
 * This controller intentionally does not depend on Gemini Live. It lets us
 * measure whether a stronger planner actually raises multi-step phone-task
 * success before changing the voice experience.
 */
final class SmartPlannerLoopController {
    interface Listener {
        void onStatus(String text);
        void onFinished(Result result);
    }

    static final class Result {
        final String status;
        final String message;
        final int plannerCalls;
        final int mutations;
        final String model;
        final boolean degraded;
        final long elapsedMs;

        Result(String status, String message, int plannerCalls, int mutations,
               String model, boolean degraded, long elapsedMs) {
            this.status = status;
            this.message = message == null ? "" : message;
            this.plannerCalls = plannerCalls;
            this.mutations = mutations;
            this.model = model == null ? "" : model;
            this.degraded = degraded;
            this.elapsedMs = elapsedMs;
        }
    }

    private final CrewRuntimeBridgeClient runtime;
    private final CrewPocketPlannerClient planner;
    private final Listener listener;
    private volatile boolean cancelled;
    private volatile Thread worker;

    SmartPlannerLoopController(Context context, final Listener listener) {
        this.listener = listener;
        this.runtime = new CrewRuntimeBridgeClient(context);
        this.planner = new CrewPocketPlannerClient(new CrewPocketPlannerClient.Listener() {
            @Override public void onPlannerStatus(String text) {
                report(text);
            }
        });
    }

    synchronized boolean start(final String goal) {
        if (worker != null && worker.isAlive()) return false;
        final String cleanGoal = goal == null ? "" : goal.trim();
        if (cleanGoal.isEmpty()) return false;
        cancelled = false;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                Result result = runLoop(cleanGoal);
                if (listener != null) listener.onFinished(result);
            }
        }, "crew-smart-planner-loop");
        worker.start();
        return true;
    }

    synchronized void cancel() {
        cancelled = true;
        Thread active = worker;
        if (active != null) active.interrupt();
    }

    synchronized boolean isRunning() {
        return worker != null && worker.isAlive();
    }

    private Result runLoop(String goal) {
        long startedAt = System.currentTimeMillis();
        int plannerCalls = 0;
        int mutations = 0;
        String conversationId = "";
        String model = CrewPocketPlannerClient.DEFAULT_MODEL;
        boolean degraded = false;
        JSONArray recent = new JSONArray();
        String lastActionSignature = "";
        String lastActionScreen = "";

        try {
            if (!CrewAccessibilityService.isServiceRunning()) {
                return result("NEED_USER", "請先啟用 Crew Helper 無障礙服務。",
                        plannerCalls, mutations, model, degraded, startedAt);
            }
            report("取得目前手機語意畫面…");
            JSONObject screen = compactScreen(runtime.semanticScreen());
            if (!screen.optBoolean("success", false)) {
                return result("NEED_USER", "目前無法取得 Accessibility 畫面。",
                        plannerCalls, mutations, model, degraded, startedAt);
            }

            while (!cancelled) {
                long elapsed = System.currentTimeMillis() - startedAt;
                if (!SmartPlannerPolicy.canContinue(plannerCalls, mutations, elapsed)) {
                    return result("NEED_USER", "Smart Planner 已達本次安全執行上限。",
                            plannerCalls, mutations, model, degraded, startedAt);
                }

                plannerCalls++;
                report("Planner 思考第 " + plannerCalls + " 步…");
                CrewPocketPlannerClient.Result planned = planner.next(
                        goal, screen, recent, conversationId,
                        plannerCalls, mutations, elapsed);
                conversationId = planned.conversationId;
                model = planned.model;
                degraded = degraded || planned.degraded;
                SmartPlannerDecision decision = planned.decision;
                report("Planner → " + decision.safeSummary());

                if (decision.kind == SmartPlannerDecision.Kind.DONE) {
                    return result("DONE", emptyFallback(decision.message, "任務完成。"),
                            plannerCalls, mutations, model, degraded, startedAt);
                }
                if (decision.kind == SmartPlannerDecision.Kind.NEED_USER) {
                    return result("NEED_USER", emptyFallback(decision.message, "需要使用者協助才能繼續。"),
                            plannerCalls, mutations, model, degraded, startedAt);
                }
                if (decision.kind == SmartPlannerDecision.Kind.FAILED) {
                    return result("FAILED", emptyFallback(decision.message, "Planner 無法安全完成此任務。"),
                            plannerCalls, mutations, model, degraded, startedAt);
                }
                if (decision.kind == SmartPlannerDecision.Kind.OBSERVE) {
                    sleepChecked(300L);
                    screen = compactScreen(runtime.semanticScreen());
                    appendRecent(recent, new JSONObject()
                            .put("kind", "OBSERVE")
                            .put("success", screen.optBoolean("success", false))
                            .put("fingerprint", screen.optString("fingerprint", "")));
                    continue;
                }

                String beforeFingerprint = screen.optString("fingerprint", "");
                String signature = decision.safeSummary();
                if (!lastActionSignature.isEmpty()
                        && lastActionSignature.equals(signature)
                        && lastActionScreen.equals(beforeFingerprint)) {
                    JSONObject blocked = new JSONObject()
                            .put("kind", "ACTION")
                            .put("action", signature)
                            .put("success", false)
                            .put("error", "REPEAT_BLOCKED_UNCHANGED_SCREEN")
                            .put("screenChanged", false);
                    appendRecent(recent, blocked);
                    report("Runtime 阻止在未變畫面原樣重複操作");
                    screen = compactScreen(runtime.semanticScreen());
                    continue;
                }

                if (mutations >= SmartPlannerPolicy.MAX_MUTATIONS) {
                    return result("NEED_USER", "已達本次手機操作安全上限。",
                            plannerCalls, mutations, model, degraded, startedAt);
                }

                report("Runtime 執行 → " + signature);
                JSONObject execution = runtime.execute(decision);
                mutations++;
                lastActionSignature = signature;
                lastActionScreen = beforeFingerprint;

                sleepChecked(postActionDelay(decision.action));
                JSONObject nextScreen = compactScreen(runtime.semanticScreen());
                String afterFingerprint = nextScreen.optString("fingerprint", "");
                boolean screenChanged = !beforeFingerprint.isEmpty()
                        && !afterFingerprint.isEmpty()
                        && !beforeFingerprint.equals(afterFingerprint);

                JSONObject history = new JSONObject()
                        .put("kind", "ACTION")
                        .put("action", signature)
                        .put("success", execution.optBoolean("success", false))
                        .put("error", execution.optString("error", execution.optString("code", "")))
                        .put("screenChanged", screenChanged)
                        .put("screenFingerprint", afterFingerprint);
                appendRecent(recent, history);
                screen = nextScreen;
            }
            return result("FAILED", "Smart Planner 已取消。",
                    plannerCalls, mutations, model, degraded, startedAt);
        } catch (InterruptedException cancelledError) {
            Thread.currentThread().interrupt();
            return result("FAILED", "Smart Planner 已取消。",
                    plannerCalls, mutations, model, degraded, startedAt);
        } catch (Exception error) {
            String message = error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage();
            if (message.contains("Connection refused") || message.contains("ECONNREFUSED")) {
                message = "Crew Pocket 或 Crew Helper Runtime 尚未啟動。";
            }
            return result("FAILED", message,
                    plannerCalls, mutations, model, degraded, startedAt);
        }
    }

    private static JSONObject compactScreen(JSONObject raw) {
        JSONObject out = new JSONObject();
        try {
            if (raw == null || !raw.optBoolean("success", false)) {
                return out.put("success", false)
                        .put("error", raw == null ? "SCREEN_UNAVAILABLE" : raw.optString("error", "SCREEN_UNAVAILABLE"));
            }
            out.put("success", true)
                    .put("package", raw.optString("package", ""))
                    .put("fingerprint", raw.optString("fingerprint", ""))
                    .put("stableScreenKey", raw.optString("stableScreenKey", ""))
                    .put("visionRecommended", raw.optBoolean("visionRecommended", false))
                    .put("visionReason", raw.optString("visionReason", ""));
            JSONArray source = raw.optJSONArray("elements");
            JSONArray elements = new JSONArray();
            if (source != null) {
                for (int i = 0; i < source.length() && elements.length() < 36; i++) {
                    JSONObject element = source.optJSONObject(i);
                    if (element == null || element.optBoolean("sensitive", false)) continue;
                    JSONObject compact = new JSONObject()
                            .put("id", element.optString("id", ""))
                            .put("role", element.optString("role", ""))
                            .put("label", truncate(element.optString("label", ""), 120))
                            .put("semanticHint", element.optString("semanticHint", ""))
                            .put("clickable", element.optBoolean("clickable", false))
                            .put("editable", element.optBoolean("editable", false))
                            .put("scrollable", element.optBoolean("scrollable", false))
                            .put("enabled", element.optBoolean("enabled", true))
                            .put("selected", element.optBoolean("selected", false))
                            .put("focused", element.optBoolean("focused", false))
                            .put("confidence", element.optDouble("confidence", 0));
                    elements.put(compact);
                }
            }
            out.put("elements", elements).put("elementCount", elements.length());
        } catch (Exception ignored) {}
        return out;
    }

    private static void appendRecent(JSONArray recent, JSONObject item) {
        if (recent == null || item == null) return;
        recent.put(item);
        while (recent.length() > 6) {
            JSONArray trimmed = new JSONArray();
            for (int i = 1; i < recent.length(); i++) trimmed.put(recent.opt(i));
            while (recent.length() > 0) recent.remove(recent.length() - 1);
            for (int i = 0; i < trimmed.length(); i++) recent.put(trimmed.opt(i));
        }
    }

    private static long postActionDelay(String action) {
        if ("OPEN_APP".equals(action)) return 850L;
        if ("SEARCH".equals(action) || "COMMIT_SEARCH".equals(action)) return 650L;
        return 420L;
    }

    private static void sleepChecked(long ms) throws InterruptedException {
        Thread.sleep(ms);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");
    }

    private Result result(String status, String message, int plannerCalls,
                          int mutations, String model, boolean degraded, long startedAt) {
        return new Result(status, message, plannerCalls, mutations, model, degraded,
                Math.max(0L, System.currentTimeMillis() - startedAt));
    }

    private void report(String text) {
        if (listener != null) listener.onStatus(text == null ? "" : text);
    }

    private static String truncate(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String emptyFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
