package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;

/**
 * Small short-term task capsule only.
 *
 * It deliberately keeps a little continuity across short follow-up turns:
 * newest user turn is authoritative, while rootGoal is only a recent anchor.
 * No long-term memory and no raw sensitive history.
 */
final class WorkingContext {
    private static final int MAX_ACTIONS = 5;
    private static final int MAX_FIELD_CHARS = 160;
    private static final int MAX_GOAL_CHARS = 480;
    private static final int MAX_TURN_CHARS = 320;

    private String rootGoal = "";
    private String latestUserTurn = "";
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
        String next = clip(fingerprint, MAX_FIELD_CHARS);
        if (!next.equals(currentScreenFingerprint) && !currentScreenFingerprint.isEmpty()) {
            previousScreenFingerprint = currentScreenFingerprint;
        }
        currentApp = clip(app, MAX_FIELD_CHARS);
        currentScreenFingerprint = next;
        currentStableScreenKey = clip(stableScreenKey, MAX_FIELD_CHARS);
    }

    /**
     * Start a truly new short-lived task capsule.
     * Called when the existing conversation goal expired or does not exist.
     */
    synchronized void startNewGoal(String value) {
        rootGoal = clip(value, MAX_GOAL_CHARS);
        latestUserTurn = clip(value, MAX_TURN_CHARS);
        previousScreenFingerprint = "";
        lastResult = "";
        pendingTask = "";
        lastActions.clear();
    }

    /**
     * Continue an existing short-lived task capsule.
     *
     * Keep recent execution evidence, but the newest user turn is always the
     * authoritative instruction. Any old pending state is invalidated because
     * the user has just spoken again.
     */
    synchronized void beginUserTurn(String value) {
        latestUserTurn = clip(value, MAX_TURN_CHARS);
        if (rootGoal.isEmpty()) rootGoal = clip(value, MAX_GOAL_CHARS);
        pendingTask = "";
    }

    /** Backward-compatible caller: treat as the newest user turn, not a hard reset. */
    synchronized void setGoalHint(String value) {
        beginUserTurn(value);
    }

    synchronized void setPendingTask(String value) {
        pendingTask = clip(value, MAX_FIELD_CHARS);
    }

    synchronized void updateLastResult(String value) {
        lastResult = clip(value, MAX_FIELD_CHARS);
    }

    synchronized void recordAction(String action, String result) {
        action = clip(action, MAX_FIELD_CHARS);
        if (!action.isEmpty()) {
            lastActions.addLast(action);
            while (lastActions.size() > MAX_ACTIONS) lastActions.removeFirst();
        }
        lastResult = clip(result, MAX_FIELD_CHARS);
    }

    synchronized JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            JSONArray actions = new JSONArray();
            for (String a : lastActions) actions.put(a);
            out.put("rootGoal", rootGoal)
               .put("latestUserTurn", latestUserTurn)
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
            // Keep the old "goal" key, but redefine it safely as the newest
            // user instruction. rootGoal is only an optional continuity anchor.
            if (!latestUserTurn.isEmpty()) out.put("goal", latestUserTurn);
            if (!rootGoal.isEmpty() && !rootGoal.equals(latestUserTurn)) {
                out.put("rootGoal", rootGoal);
            }
            if (!currentApp.isEmpty()) out.put("currentApp", currentApp);
            if (!currentScreenFingerprint.isEmpty()) {
                out.put("currentScreen", currentScreenFingerprint);
            }

            if (!lastActions.isEmpty()) {
                JSONArray actions = new JSONArray();
                for (String action : lastActions) actions.put(action);
                out.put("lastActions", actions);
            }

            if (!lastResult.isEmpty()) out.put("lastResult", lastResult);
            if (!pendingTask.isEmpty()) out.put("pendingTask", pendingTask);
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Hard reset only for a truly new/expired conversation goal.
     * Do not call this for every finalized utterance.
     */
    synchronized void resetTransientForNewGoal() {
        rootGoal = "";
        latestUserTurn = "";
        previousScreenFingerprint = "";
        lastResult = "";
        pendingTask = "";
        lastActions.clear();
    }

    synchronized void clear() {
        rootGoal = "";
        latestUserTurn = "";
        currentApp = "";
        currentScreenFingerprint = "";
        currentStableScreenKey = "";
        previousScreenFingerprint = "";
        lastResult = "";
        pendingTask = "";
        lastActions.clear();
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        String out = value.trim();
        return out.length() <= max ? out : out.substring(0, max);
    }
}
