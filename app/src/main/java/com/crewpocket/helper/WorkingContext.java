package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;

/**
 * Bounded Runtime task ledger. It keeps a current task coherent across short
 * follow-up turns without retaining raw transcripts or sensitive payloads.
 */
final class WorkingContext {
    private static final int MAX_ACTIONS = 8;
    private static final int MAX_FACTS = 4;
    private static final int MAX_HISTORY = 3;
    private String userGoal = "";
    private String currentApp = "";
    private String currentScreenFingerprint = "";
    private String currentStableScreenKey = "";
    private String previousScreenFingerprint = "";
    private String lastResult = "";
    private String pendingTask = "";
    private final ArrayDeque<String> lastActions = new ArrayDeque<String>();
    private final ArrayDeque<String> verifiedFacts = new ArrayDeque<String>();
    private final ArrayDeque<String> recentGoals = new ArrayDeque<String>();

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

    synchronized void setGoalHint(String value) { userGoal = safe(value); }
    synchronized void setPendingTask(String value) { pendingTask = safe(value); }
    synchronized void updateLastResult(String value) { lastResult = safe(value); }

    synchronized void beginNewGoal(String nextGoal) {
        String previous = safe(userGoal);
        if (!previous.isEmpty() && !previous.equals(safe(nextGoal))) {
            recentGoals.addLast(previous);
            while (recentGoals.size() > MAX_HISTORY) recentGoals.removeFirst();
        }
        userGoal = safe(nextGoal);
        previousScreenFingerprint = "";
        lastResult = "";
        pendingTask = "";
        lastActions.clear();
        verifiedFacts.clear();
    }

    synchronized void recordVerifiedFact(String value) {
        value = safe(value);
        if (value.isEmpty()) return;
        if (!verifiedFacts.isEmpty() && value.equals(verifiedFacts.peekLast())) return;
        verifiedFacts.addLast(value);
        while (verifiedFacts.size() > MAX_FACTS) verifiedFacts.removeFirst();
    }

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
               .put("pendingTask", pendingTask)
               .put("verifiedFacts", new JSONArray(verifiedFacts))
               .put("recentGoals", new JSONArray(recentGoals));
        } catch (Exception ignored) {}
        return out;
    }
    synchronized JSONObject toModelJson() {
        JSONObject out = new JSONObject();
        try {
            if (!userGoal.isEmpty()) out.put("goal", userGoal);
            if (!currentApp.isEmpty()) out.put("currentApp", currentApp);
            if (!currentScreenFingerprint.isEmpty()) out.put("currentScreen", currentScreenFingerprint);

            if (!lastActions.isEmpty()) {
                JSONArray actions = new JSONArray();
                for (String action : lastActions) actions.put(action);
                out.put("lastActions", actions);
            }

            if (!lastResult.isEmpty()) out.put("lastResult", lastResult);
            if (!pendingTask.isEmpty()) out.put("pendingTask", pendingTask);
            if (!verifiedFacts.isEmpty()) out.put("verifiedFacts", new JSONArray(verifiedFacts));
            if (!recentGoals.isEmpty()) out.put("recentGoals", new JSONArray(recentGoals));
        } catch (Exception ignored) {}
        return out;
    }
    synchronized void resetTransientForNewGoal() { previousScreenFingerprint=""; lastResult=""; pendingTask=""; lastActions.clear(); verifiedFacts.clear(); }

    synchronized void clear() {
        userGoal = "";
        currentApp = "";
        currentScreenFingerprint = "";
        currentStableScreenKey = "";
        previousScreenFingerprint = "";
        lastResult = "";
        pendingTask = "";
        lastActions.clear();
        verifiedFacts.clear();
        recentGoals.clear();
    }

    private static String safe(String value) {
        if (value == null) return "";
        value = value.trim();
        return value.length() <= 160 ? value : value.substring(0, 160);
    }
}
