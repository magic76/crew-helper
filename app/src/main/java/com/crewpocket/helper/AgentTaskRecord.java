package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;

/** Compact in-memory audit record. Raw payloads deliberately never enter transcripts. */
final class AgentTaskRecord {
    final String taskId;
    String goalId = "";
    int goalTaskIndex = 0;
    long intentGeneration = -1L;
    final long startedAt = System.currentTimeMillis();
    final ArrayList<String> stepsSummary = new ArrayList<String>();
    final HashMap<String, Integer> toolCounts = new HashMap<String, Integer>();
    int steps;
    int mutationActions;
    int consecutiveMutationFailures;
    int stabilityBlocks;
    boolean requireObservationAfterFailure;
    String lastFailedMutationSignature = "";
    String failedMutationScreenFingerprint = "";
    String lastSignature = "";
    String status = "";
    String blockedReason;
    String endReason = "";
    String finalReply = "";
    boolean awaitingModel;
    boolean watchdogPrompted;
    boolean userVisibleReplyProducedSinceLastAction;
    int finalSpeechRetryCount;
    boolean requiresPostActionInspection;
    boolean postActionInspectionPrompted;
    boolean cancelled;
    boolean finished;

    AgentTaskRecord(String id) { taskId = id; }

    int getToolCount(String name) {
        Integer value = toolCounts.get(name);
        return value == null ? 0 : value;
    }

    void incrementTool(String name) {
        toolCounts.put(name, getToolCount(name) + 1);
    }

    void addStep(String name, JSONObject result) {
        String outcome = result.optBoolean("success")
                ? "成功"
                : (result.optBoolean("cancelled") ? "已取消" : "失敗");
        String detail = result.optString("message", result.optString("error", ""));
        stepsSummary.add(name + "：" + outcome
                + (detail.isEmpty() ? "" : "（" + detail + "）"));
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("taskId", taskId)
                    .put("goalId", goalId)
                    .put("goalTaskIndex", goalTaskIndex)
                    .put("startedAt", startedAt)
                    .put("steps", new JSONArray(stepsSummary))
                    .put("stepCount", steps)
                    .put("mutationActions", mutationActions)
                    .put("endReason", endReason)
                    .put("finalReply", finalReply)
                    .put("status", status)
                    .put("userVisibleReplyProduced", userVisibleReplyProducedSinceLastAction)
                    .put("finalSpeechRetryCount", finalSpeechRetryCount);
        } catch (Exception ignored) {}
        return json;
    }
}
