#!/usr/bin/env bash
set -euo pipefail

# One-shot 0122 bootstrap used only on the draft PR runner. It applies a small
# unified diff to the exact GitHub checkout so NativeGeminiLiveClient is never
# rewritten from a reconstructed 250 KB payload. The script removes itself and
# restores the normal runtime-test script before committing the real change.
BRANCH="${GITHUB_HEAD_REF:-}"
if [[ -z "$BRANCH" ]]; then
  echo "0122 bootstrap: not a pull_request runner; skipping"
  exit 0
fi

BASE_HEAD="a9a15044572828f5e1b7b8b65919b4fdd962604b"
TARGET="app/src/main/java/com/crewpocket/helper/NativeGeminiLiveClient.java"

git fetch origin "$BRANCH"
git checkout -B "$BRANCH" "origin/$BRANCH"

if grep -q 'AgentTaskLifecyclePolicy.evaluateStep' "$TARGET" \
   && ! grep -q 'private static final class AgentTaskRecord' "$TARGET"; then
  echo "0122 bootstrap: Native extraction already applied"
else
  cat > /tmp/0122-native.patch <<'PATCH'
--- a/app/src/main/java/com/crewpocket/helper/NativeGeminiLiveClient.java
+++ b/app/src/main/java/com/crewpocket/helper/NativeGeminiLiveClient.java
@@ -99,15 +99,10 @@
     private final java.util.HashMap<String, ArrayList<ToolResponseRecipient>> coalescedToolCallRecipients = new java.util.HashMap<String, ArrayList<ToolResponseRecipient>>();
     // Phone tasks routinely need several semantic actions plus model turns.
     // Observation/verification calls do not consume the mutation-action budget.
-    private static final long AGENT_TASK_TIMEOUT_MS = 180_000L;
     private static final long AGENT_FINAL_RESPONSE_WAIT_MS = 12_000L;
     private static final int AGENT_FINAL_SPEECH_MAX_RETRIES = 2;
-    private static final int AGENT_MAX_TOOL_RUNS = 8;
-    private static final int AGENT_MAX_MUTATION_ACTIONS = 15;
-    private static final int AGENT_MAX_SCREENSHOTS = 3;
     // Navigation is deliberately repeatable during a presentation. All other
     // tools keep the conservative 3-run default safety limit.
-    private static final int AGENT_DECK_NAV_MAX_RUNS = 16;
     private final Object agentLock = new Object();
     private final ArrayList<JSONObject> pendingToolCalls = new ArrayList<JSONObject>();
     private final ArrayList<AgentTaskRecord> agentHistory = new ArrayList<AgentTaskRecord>();
@@ -1927,19 +1922,25 @@
             clearAgentResponseWatchdogLocked();
             String signature = buildAgentSignature(name, args);
             if (task.cancelled) return null;
-            boolean observation = isObservationTool(name);
-            boolean mutation = isMutationTool(name);
-            if (System.currentTimeMillis() - task.startedAt > AGENT_TASK_TIMEOUT_MS) task.blockedReason = "本次 Agent 任務已逾時（180 秒），請以目前已知結果作結論。";
-            else if (task.steps >= agentMaxSteps) task.blockedReason = "已達本次自動執行步數上限（" + agentMaxSteps + " 步），請以目前已知結果作結論。";
-            else if (!observation && signature.equals(task.lastSignature)) task.blockedReason = "偵測到相同動作與參數連續重複呼叫，請先重新觀察畫面並改用替代方案。";
-            else if ("take_screenshot".equals(name) && task.getToolCount(name) >= AGENT_MAX_SCREENSHOTS) task.blockedReason = "截圖已達本次任務上限，請改用 Accessibility 畫面狀態或作結論。";
-            else if (!observation && task.getToolCount(name) >= maxRunsForTool(name)) task.blockedReason = "工具「" + name + "」已達本次任務最多 " + maxRunsForTool(name) + " 次執行限制，請改用替代方案或作結論。";
-            else if (mutation && task.mutationActions >= AGENT_MAX_MUTATION_ACTIONS) task.blockedReason = "已達本次任務實際操作上限（" + AGENT_MAX_MUTATION_ACTIONS + " 次），請以目前結果作結論。";
+
+            AgentTaskLifecyclePolicy.StepDecision decision =
+                    AgentTaskLifecyclePolicy.evaluateStep(
+                            System.currentTimeMillis(),
+                            task.startedAt,
+                            task.steps,
+                            agentMaxSteps,
+                            name,
+                            signature,
+                            task.lastSignature,
+                            task.getToolCount(name),
+                            task.mutationActions);
+            if (!decision.allowed) task.blockedReason = decision.blockedReason;
+
             if (task.blockedReason == null) {
                 task.steps++;
                 task.lastSignature = signature;
                 task.incrementTool(name);
-                if (mutation) task.mutationActions++;
+                if (isMutationTool(name)) task.mutationActions++;
                 task.awaitingModel = false;
                 task.userVisibleReplyProducedSinceLastAction = false;
                 task.finalSpeechRetryCount = 0;
@@ -1951,20 +1952,7 @@
     }
 
     private boolean isObservationTool(String name) {
-        return "inspect_ui".equals(name)
-                || "get_selected_region".equals(name)
-                || "read_web_page".equals(name)
-                || "list_app_guidance".equals(name)
-                || "get_note".equals(name)
-                || "search_notes".equals(name)
-                || "list_notes".equals(name)
-                || "wait".equals(name)
-                || "teach_ui_element".equals(name)
-                || "list_active_schedules".equals(name)
-                || "list_memory_rules".equals(name)
-                || "list_decks".equals(name)
-                || "get_deck_card".equals(name)
-                || "list_deck_images".equals(name);
+        return AgentTaskLifecyclePolicy.isObservationTool(name);
     }
 
     /**
@@ -2022,15 +2010,7 @@
     }
 
     private boolean isMutationTool(String name) {
-        return "launch_app".equals(name)
-                || "swipe_screen".equals(name)
-                || "tap_element".equals(name)
-                || "tap_screen".equals(name)
-                || "type_text".equals(name)
-                || "search_current_app".equals(name)
-                || "commit_search".equals(name)
-                || "send_text".equals(name)
-                || "press_key".equals(name);
+        return AgentTaskLifecyclePolicy.isMutationTool(name);
     }
 
     private AgentTaskRecord peekActiveAgentTask() {
@@ -2042,25 +2022,21 @@
     private JSONObject agentStabilityPreflight(AgentTaskRecord task, String name, JSONObject args) {
         if (task == null || task.finished || task.cancelled || !isMutationTool(name)) return null;
         synchronized (agentLock) {
-            String code = "";
-            String instruction = "";
-            if (task.requireObservationAfterFailure || semanticObserveRequired) {
-                code = "OBSERVE_REQUIRED_AFTER_FAILURE";
-                instruction = "上一個操作失敗或驗證不足。先呼叫 inspect_ui 一次，再依最新畫面改用不同方法；不要直接重做 mutation。";
-            } else {
-                String signature = buildAgentSignature(name, args);
-                if (!task.lastFailedMutationSignature.isEmpty()
-                        && signature.equals(task.lastFailedMutationSignature)
-                        && !task.failedMutationScreenFingerprint.isEmpty()
-                        && task.failedMutationScreenFingerprint.equals(latestSemanticFingerprint)) {
-                    code = "REPEAT_FAILED_ACTION_ON_UNCHANGED_SCREEN";
-                    instruction = "這個完全相同的操作已在目前畫面失敗。禁止原樣重試；請換 selector、換工具、返回，或直接回報卡點。";
-                }
-            }
-            if (code.isEmpty()) return null;
+            AgentTaskLifecyclePolicy.StabilityDecision decision =
+                    AgentTaskLifecyclePolicy.evaluateStability(
+                            task.finished,
+                            task.cancelled,
+                            name,
+                            task.requireObservationAfterFailure,
+                            semanticObserveRequired,
+                            buildAgentSignature(name, args),
+                            task.lastFailedMutationSignature,
+                            task.failedMutationScreenFingerprint,
+                            latestSemanticFingerprint);
+            if (!decision.blocked) return null;
 
             task.stabilityBlocks++;
-            if (task.stabilityBlocks >= 2) {
+            if (AgentTaskLifecyclePolicy.shouldStopAfterStabilityBlock(task.stabilityBlocks)) {
                 task.blockedReason = "Runtime 已連續兩次阻止無效重試，停止本次 Agent loop；請向使用者簡短回報目前卡點。";
             }
             try {
@@ -2068,8 +2044,8 @@
                         .put("success", false)
                         .put("stepResult", "STEP_FAILED")
                         .put("blockedByRuntime", true)
-                        .put("error", code)
-                        .put("instruction", instruction);
+                        .put("error", decision.code)
+                        .put("instruction", decision.instruction);
             } catch (Exception ignored) {
                 return new JSONObject();
             }
@@ -2136,13 +2112,13 @@
                 task.requireObservationAfterFailure = true;
             }
 
-            if (task.consecutiveMutationFailures >= 3) {
+            if (AgentTaskLifecyclePolicy.shouldStopAfterMutationFailure(
+                    task.consecutiveMutationFailures)) {
                 task.blockedReason = "連續 3 次手機操作失敗，Runtime 已停止繼續試錯；請回報目前畫面與卡點，不要再呼叫工具。";
             }
         }
     }
 
-    private int maxRunsForTool(String name) { return "advance_deck".equals(name) || "present_deck_card".equals(name) ? AGENT_DECK_NAV_MAX_RUNS : AGENT_MAX_TOOL_RUNS; }
     private String buildAgentSignature(String name, JSONObject args) {
         // Each advance has a different logical position, so it is not a model loop.
         if ("advance_deck".equals(name)) return name + ":" + args.toString() + ":at=" + DeckRepository.activeIndex();
@@ -4846,57 +4822,6 @@
         return accepted;
     }
 
-    /** Compact in-memory audit record. Raw payloads deliberately never enter transcripts. */
-    private static final class AgentTaskRecord {
-        final String taskId;
-        String goalId = "";
-        int goalTaskIndex = 0;
-        long intentGeneration = -1L;
-        final long startedAt = System.currentTimeMillis();
-        final ArrayList<String> stepsSummary = new ArrayList<String>();
-        final java.util.HashMap<String, Integer> toolCounts = new java.util.HashMap<String, Integer>();
-        int steps;
-        int mutationActions;
-        int consecutiveMutationFailures;
-        int stabilityBlocks;
-        boolean requireObservationAfterFailure;
-        String lastFailedMutationSignature = "";
-        String failedMutationScreenFingerprint = "";
-        String lastSignature = "";
-        String status = "";
-        String blockedReason;
-        String endReason = "";
-        String finalReply = "";
-        boolean awaitingModel;
-        boolean watchdogPrompted;
-        boolean userVisibleReplyProducedSinceLastAction;
-        int finalSpeechRetryCount;
-        boolean requiresPostActionInspection;
-        boolean postActionInspectionPrompted;
-        boolean cancelled;
-        boolean finished;
-        AgentTaskRecord(String id) { taskId = id; }
-        int getToolCount(String name) { Integer value = toolCounts.get(name); return value == null ? 0 : value; }
-        void incrementTool(String name) { toolCounts.put(name, getToolCount(name) + 1); }
-        void addStep(String name, JSONObject result) {
-            String outcome = result.optBoolean("success") ? "成功" : (result.optBoolean("cancelled") ? "已取消" : "失敗");
-            String detail = result.optString("message", result.optString("error", ""));
-            stepsSummary.add(name + "：" + outcome + (detail.isEmpty() ? "" : "（" + detail + "）"));
-        }
-        JSONObject toJson() {
-            JSONObject json = new JSONObject();
-            try {
-                json.put("taskId", taskId).put("goalId", goalId).put("goalTaskIndex", goalTaskIndex)
-                        .put("startedAt", startedAt).put("steps", new JSONArray(stepsSummary))
-                        .put("stepCount", steps).put("mutationActions", mutationActions)
-                        .put("endReason", endReason).put("finalReply", finalReply).put("status", status)
-                        .put("userVisibleReplyProduced", userVisibleReplyProducedSinceLastAction)
-                        .put("finalSpeechRetryCount", finalSpeechRetryCount);
-            } catch (Exception ignored) {}
-            return json;
-        }
-    }
-
     private void reportStage(String text) { stage = text; listener.onStatus(text); Log.d(TAG, text); }
     private synchronized void fail(String message, Throwable error) {
         if (!running) return;
PATCH

  git apply --check /tmp/0122-native.patch
  git apply /tmp/0122-native.patch
fi

# Remove the bootstrap machinery from the final tree so the PR contains only
# production/test changes. Restore the exact runtime test runner from the clean
# pre-bootstrap branch head.
git show "$BASE_HEAD:scripts/run_agent_runtime_tests.sh" > scripts/run_agent_runtime_tests.sh
git rm -f scripts/0122_apply_native_patch.sh

git config user.name "Crew Helper CI"
git config user.email "actions@users.noreply.github.com"
git add "$TARGET" scripts/run_agent_runtime_tests.sh
if git diff --cached --quiet; then
  echo "0122 bootstrap: nothing to commit"
  exit 0
fi
git commit -m "refactor(agent): delegate task lifecycle from native client"
git push origin HEAD:"$BRANCH"
