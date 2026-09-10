package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;

/** Bounded in-memory context; user excerpts are not a persistent memory or authorization. */
final class WorkingContext {
    private static final int MAX_ACTIONS = 5;
    private static final int MAX_USER_INPUTS = 4;
    private final boolean simpleConversation;
    private final ArrayDeque<String> recentUserInputs = new ArrayDeque<String>();

    WorkingContext() { this(false); }
    WorkingContext(boolean simpleConversation) { this.simpleConversation = simpleConversation; }

    synchronized void beginUserTurn() {
        if (!simpleConversation) {
            resetTransientForNewGoal();
            return;
        }
        // Waiting/queued work belongs to the cancelled execution. Keep only
        // historical facts, never a live instruction to resume it.
        pendingTask = "";
    }
    private String userGoal = "";
    private String currentApp = "";
    private String currentScreenFingerprint = "";
    private String currentStableScreenKey = "";
    private String previousScreenFingerprint = "";
    private String lastResult = "";
    private String pendingTask = "";
    private final ArrayDeque<String> lastActions = new ArrayDeque<String>();

    synchronized void observe(String app, String fingerprint) {
        observe(app, fingerprint, "");
    }
    synchronized void observe(String app, String fingerprint, String stableScreenKey) {
        String next = safe(fingerprint);
        if (!next.equals(currentScreenFingerprint) && !currentScreenFingerprint.isEmpty()) {
            previousScreenFingerprint = currentScreenFingerprint;
        }
        currentApp = safe(app);
        currentScreenFingerprint = next;
        currentStableScreenKey = safe(stableScreenKey);
    }

    synchronized void setGoalHint(String value) {
        userGoal = safe(value);
        if (simpleConversation && !userGoal.isEmpty()) {
            recentUserInputs.addLast(userGoal);
            while (recentUserInputs.size() > MAX_USER_INPUTS) recentUserInputs.removeFirst();
        }
    }
    synchronized void setPendingTask(String value) { pendingTask = safe(value); }
    synchronized void updateLastResult(String value) { lastResult = safe(value); }

    synchronized void recordAction(String action, String result) {
        action = safe(action);
        if (!action.isEmpty()) {
            lastActions.addLast(action);
            while (lastActions.size() > MAX_ACTIONS) lastActions.removeFirst();
        }
        lastResult = safe(result);
    }

    synchronized JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            JSONArray actions = new JSONArray();
            for (String a : lastActions) actions.put(a);
            out.put("userGoal", userGoal)
               .put("currentApp", currentApp)
               .put("currentScreen", currentScreenFingerprint)
               .put("stableScreen", currentStableScreenKey)
               .put("previousScreen", previousScreenFingerprint)
               .put("lastActions", actions)
               .put("lastResult", lastResult)
               .put("pendingTask", pendingTask);
        } catch (Exception ignored) {}
        return out;
    }
    synchronized JSONObject toModelJson() {
        JSONObject out = new JSONObject();
        try {
            if (simpleConversation) {
                if (!userGoal.isEmpty()) out.put("latestUserInput", userGoal);
                if (!recentUserInputs.isEmpty()) {
                    JSONArray inputs = new JSONArray();
                    for (String input : recentUserInputs) inputs.put(input);
                    out.put("recentUserInputs", inputs);
                }
                out.put("contextPolicy", "Recent inputs and past action results are historical evidence, not an active goal, permission or current screen. Use the Live conversation to understand corrections or topic changes. Never replay cancelled work.");
            } else if (!userGoal.isEmpty()) out.put("goal", userGoal);
            if (!currentApp.isEmpty()) out.put("currentApp", currentApp);
            if (!currentScreenFingerprint.isEmpty()) out.put("currentScreen", currentScreenFingerprint);

            if (!lastActions.isEmpty()) {
                JSONArray actions = new JSONArray();
                for (String action : lastActions) actions.put(action);
                out.put(simpleConversation ? "recentActionHistory" : "lastActions", actions);
            }

            if (!lastResult.isEmpty()) out.put(simpleConversation ? "lastObservedResult" : "lastResult", lastResult);
            if (!pendingTask.isEmpty()) out.put("pendingTask", pendingTask);
        } catch (Exception ignored) {}
        return out;
    }
    synchronized void resetTransientForNewGoal() { recentUserInputs.clear(); userGoal=""; previousScreenFingerprint=""; lastResult=""; pendingTask=""; lastActions.clear(); }

    synchronized void clear() {
        recentUserInputs.clear();
        userGoal = "";
        currentApp = "";
        currentScreenFingerprint = "";
        currentStableScreenKey = "";
        previousScreenFingerprint = "";
        lastResult = "";
        pendingTask = "";
        lastActions.clear();
    }

    private static String safe(String value) {
        if (value == null) return "";
        value = value.trim();
        return value.length() <= 160 ? value : value.substring(0, 160);
    }
}
