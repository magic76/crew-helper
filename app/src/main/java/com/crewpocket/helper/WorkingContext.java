package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;

/** Small short-term context only. No long-term memory and no raw sensitive text. */
final class WorkingContext {
    private static final int MAX_ACTIONS = 5;
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

    synchronized void setGoalHint(String value) { userGoal = safe(value); }
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
    synchronized JSONObject toModelJson() { JSONObject out=new JSONObject(); try { if(!currentApp.isEmpty())out.put("currentApp",currentApp); if(!currentScreenFingerprint.isEmpty())out.put("currentScreen",currentScreenFingerprint); if(!lastResult.isEmpty())out.put("lastResult",lastResult); if(!pendingTask.isEmpty())out.put("pendingTask",pendingTask); } catch(Exception ignored){} return out; }
    synchronized void resetTransientForNewGoal() { userGoal=""; previousScreenFingerprint=""; lastResult=""; pendingTask=""; lastActions.clear(); }

    synchronized void clear() {
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
