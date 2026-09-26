package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Bounded local store for distilled operational memory.
 *
 * It stores abstract procedure tokens only. It never stores search text,
 * recipients, message bodies, credentials, screenshots, or coordinates.
 */
final class RefinedMemoryStore {
    private static final String PREFS = "crew_refined_memory";
    private static final String KEY_DATA = "memories_v1";
    private static final String KEY_EVENTS = "events_v1";
    private static final String KEY_CORRECTED_TASKS = "corrected_tasks_v1";
    private static final int EVIDENCE_SCHEMA_VERSION = 2;
    private static final int MAX_ITEMS = 48;
    private static final int MAX_EVENTS = 96;
    private static final int MAX_CORRECTED_TASKS = 256;
    private static final long STALE_AFTER_MS =
            120L * 24L * 60L * 60L * 1000L;
    private static final Object LOCK = new Object();

    static final class Entry {
        String id = "";
        String type = RefinedMemoryPolicy.TYPE_PROCEDURE;
        String scope = "";
        String packageName = "";
        String startPackage = "";
        String pattern = "";
        String guidance = "";
        String state = RefinedMemoryPolicy.STATE_CANDIDATE;
        boolean enabled = true;
        int successCount;
        int supportCount;
        int failureCount;
        double confidence;
        String lastEvidenceSource = "";
        String lastTaskId = "";
        long createdAt;
        long updatedAt;
        long lastVerifiedAt;
        long lastFailureAt;

        JSONObject toJson() {
            JSONObject out = new JSONObject();
            put(out, "id", id);
            put(out, "evidenceSchemaVersion", EVIDENCE_SCHEMA_VERSION);
            put(out, "type", type);
            put(out, "scope", scope);
            put(out, "packageName", packageName);
            put(out, "startPackage", startPackage);
            put(out, "pattern", pattern);
            put(out, "guidance", guidance);
            put(out, "state", state);
            put(out, "enabled", enabled);
            put(out, "successCount", successCount);
            put(out, "supportCount", supportCount);
            put(out, "failureCount", failureCount);
            put(out, "confidence", confidence);
            put(out, "lastEvidenceSource", lastEvidenceSource);
            put(out, "lastTaskId", lastTaskId);
            put(out, "createdAt", createdAt);
            put(out, "updatedAt", updatedAt);
            put(out, "lastVerifiedAt", lastVerifiedAt);
            put(out, "lastFailureAt", lastFailureAt);
            return out;
        }

        static Entry fromJson(JSONObject source) {
            Entry out = new Entry();
            if (source == null) return out;
            out.id = safe(source.optString("id", ""));
            out.type = safe(source.optString(
                    "type", RefinedMemoryPolicy.TYPE_PROCEDURE));
            out.scope = safe(source.optString("scope", ""));
            out.packageName = safe(source.optString("packageName", ""));
            out.startPackage = safe(source.optString("startPackage", ""));
            out.pattern = RefinedMemoryPolicy.canonicalPattern(
                    source.optString("pattern", ""));
            out.guidance = clip(source.optString("guidance", ""), 220);
            out.state = safe(source.optString(
                    "state", RefinedMemoryPolicy.STATE_CANDIDATE));
            out.enabled = source.optBoolean("enabled", true);
            int evidenceSchemaVersion =
                    source.optInt("evidenceSchemaVersion", 1);
            int storedSuccessCount = Math.max(
                    0, source.optInt("successCount", 0));
            out.successCount = evidenceSchemaVersion
                    >= EVIDENCE_SCHEMA_VERSION
                            ? storedSuccessCount
                            : 0;
            out.supportCount = Math.max(
                    0, source.optInt("supportCount", 0))
                    + (evidenceSchemaVersion
                            >= EVIDENCE_SCHEMA_VERSION
                                    ? 0
                                    : storedSuccessCount);
            out.failureCount = Math.max(
                    0, source.optInt("failureCount", 0));
            out.confidence = source.optDouble("confidence", 0.0d);
            out.lastEvidenceSource =
                    safe(source.optString("lastEvidenceSource", ""));
            if (source.optInt("evidenceSchemaVersion", 1)
                    < EVIDENCE_SCHEMA_VERSION) {
                out.lastEvidenceSource =
                        "LEGACY_PRE_TERMINAL_GATE";
                out.state =
                        RefinedMemoryPolicy.STATE_CANDIDATE;
            }
            out.lastTaskId =
                    safe(source.optString("lastTaskId", ""));
            out.createdAt = source.optLong("createdAt", 0L);
            out.updatedAt = source.optLong("updatedAt", 0L);
            out.lastVerifiedAt =
                    source.optInt("evidenceSchemaVersion", 1)
                            >= EVIDENCE_SCHEMA_VERSION
                                    ? source.optLong(
                                            "lastVerifiedAt", 0L)
                                    : 0L;
            out.lastFailureAt =
                    source.optLong("lastFailureAt", 0L);
            return out;
        }
    }

    private final SharedPreferences prefs;

    RefinedMemoryStore(Context context) {
        Context app =
                context == null ? null : context.getApplicationContext();
        prefs = app == null ? null
                : app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Entry observeIndependentSuccess(
            String scope,
            String packageName,
            String startPackage,
            String pattern,
            String guidance,
            String taskId) {
        return applyEvidence(
                scope,
                packageName,
                startPackage,
                pattern,
                guidance,
                RefinedMemoryEvidencePolicy.Verdict.INDEPENDENT_SUCCESS,
                taskId,
                "NORMAL_TASK");
    }

    Entry observeSupportingReplay(
            String scope,
            String packageName,
            String startPackage,
            String pattern,
            String guidance,
            String taskId) {
        return applyEvidence(
                scope,
                packageName,
                startPackage,
                pattern,
                guidance,
                RefinedMemoryEvidencePolicy.Verdict.SUPPORTING_SUCCESS,
                taskId,
                "RECIPE_REPLAY");
    }

    Entry observeFailure(
            String scope,
            String packageName,
            String startPackage,
            String pattern,
            String guidance,
            String taskId,
            String source) {
        return applyEvidence(
                scope,
                packageName,
                startPackage,
                pattern,
                guidance,
                RefinedMemoryEvidencePolicy.Verdict.NEGATIVE,
                taskId,
                safe(source).isEmpty() ? "NEGATIVE" : safe(source));
    }

    int applyCorrection(
            List<String> usedIds,
            List<String> learnedIds,
            String taskId,
            String reason) {
        if (prefs == null) return 0;

        java.util.LinkedHashSet<String> allIds =
                new java.util.LinkedHashSet<String>();
        if (usedIds != null) allIds.addAll(usedIds);
        if (learnedIds != null) allIds.addAll(learnedIds);
        if (allIds.isEmpty()) return 0;

        synchronized (LOCK) {
            List<Entry> items = loadLocked();
            long now = System.currentTimeMillis();
            int changed = 0;
            for (Entry item : items) {
                if (item == null
                        || !allIds.contains(item.id)) {
                    continue;
                }

                boolean learnedByCorrectedTask =
                        learnedIds != null
                                && learnedIds.contains(item.id)
                                && safe(taskId).equals(
                                        item.lastTaskId)
                                && "NORMAL_TASK".equals(
                                        item.lastEvidenceSource);

                RefinedMemoryCorrectionPolicy.Result correction =
                        RefinedMemoryCorrectionPolicy.apply(
                                item.successCount,
                                item.failureCount,
                                learnedByCorrectedTask);
                item.successCount = correction.successCount;
                item.failureCount = correction.failureCount;
                item.state = correction.state;
                item.updatedAt = now;
                item.lastFailureAt = now;
                item.lastEvidenceSource =
                        safe(reason).isEmpty()
                                ? "USER_CORRECTION"
                                : safe(reason);
                item.lastTaskId = safe(taskId);
                item.confidence = correction.confidence;
                changed++;
            }
            if (changed > 0) {
                trimAndSaveLocked(items);
                markCorrectedTaskLocked(taskId);
                JSONObject event = baseEvent(
                        "CORRECTED",
                        taskId,
                        "");
                put(event, "usedIds", toArray(usedIds));
                put(event, "learnedIds", toArray(learnedIds));
                put(event, "changed", changed);
                appendEventLocked(event);
            }
            return changed;
        }
    }

    int markSuspectByIds(
            List<String> ids,
            String taskId,
            String reason) {
        return applyCorrection(
                ids,
                null,
                taskId,
                reason);
    }

    private Entry applyEvidence(
            String scope,
            String packageName,
            String startPackage,
            String pattern,
            String guidance,
            RefinedMemoryEvidencePolicy.Verdict verdict,
            String taskId,
            String source) {
        String cleanScope = safe(scope);
        String cleanPackage = safe(packageName);
        String cleanStartPackage = safe(startPackage);
        String cleanPattern = clip(
                RefinedMemoryPolicy.canonicalPattern(pattern), 180);
        String cleanGuidance = clip(guidance, 220);
        if (prefs == null
                || !RefinedMemoryPolicy.isEligibleScope(cleanScope)
                || cleanPattern.isEmpty()
                || cleanGuidance.isEmpty()
                || verdict == null
                || verdict == RefinedMemoryEvidencePolicy.Verdict.NONE) {
            return null;
        }

        synchronized (LOCK) {
            List<Entry> items = loadLocked();
            Entry target = null;
            for (Entry item : items) {
                if (sameIdentity(
                        item,
                        cleanScope,
                        cleanPackage,
                        cleanPattern)) {
                    target = item;
                    break;
                }
            }

            long now = System.currentTimeMillis();
            if (target == null
                    && verdict
                            != RefinedMemoryEvidencePolicy.Verdict
                                    .INDEPENDENT_SUCCESS) {
                // Replay/support/failure can update an existing memory but can
                // never create a new belief on their own.
                return null;
            }
            if (target == null) {
                target = new Entry();
                target.id = UUID.randomUUID().toString();
                target.scope = cleanScope;
                target.packageName = cleanPackage;
                target.startPackage = cleanStartPackage;
                target.pattern = cleanPattern;
                target.createdAt = now;
                target.enabled = true;
                items.add(target);
            }

            if (!cleanStartPackage.isEmpty()) {
                target.startPackage = cleanStartPackage;
            }
            target.guidance = cleanGuidance;
            target.updatedAt = now;
            target.lastEvidenceSource = safe(source);
            target.lastTaskId = safe(taskId);

            if (verdict
                    == RefinedMemoryEvidencePolicy.Verdict
                            .INDEPENDENT_SUCCESS) {
                target.successCount++;
                target.lastVerifiedAt = now;
                // A fresh independent terminal verification is the only event
                // allowed to clear an immediate user-correction quarantine.
                target.state = RefinedMemoryPolicy.stateFor(
                        target.successCount,
                        target.failureCount);
            } else if (verdict
                    == RefinedMemoryEvidencePolicy.Verdict
                            .SUPPORTING_SUCCESS) {
                target.supportCount++;
                target.lastVerifiedAt = now;
                if (!RefinedMemoryPolicy.STATE_SUSPECT.equals(
                        target.state)) {
                    target.state = RefinedMemoryPolicy.stateFor(
                            target.successCount,
                            target.failureCount);
                }
            } else if (verdict
                    == RefinedMemoryEvidencePolicy.Verdict.NEGATIVE) {
                target.failureCount++;
                target.lastFailureAt = now;
                if (!RefinedMemoryPolicy.STATE_SUSPECT.equals(
                        target.state)) {
                    target.state = RefinedMemoryPolicy.stateFor(
                            target.successCount,
                            target.failureCount);
                }
            }

            target.confidence = RefinedMemoryPolicy.confidenceFor(
                    target.successCount,
                    target.failureCount);

            trimAndSaveLocked(items);

            String eventType =
                    verdict == RefinedMemoryEvidencePolicy.Verdict.INDEPENDENT_SUCCESS
                            ? (target.successCount <= 1 ? "LEARNED" : "EVIDENCE")
                            : (verdict == RefinedMemoryEvidencePolicy.Verdict.SUPPORTING_SUCCESS
                                    ? "REPLAY_SUPPORT"
                                    : "NEGATIVE");
            JSONObject event = baseEvent(
                    eventType,
                    taskId,
                    target.scope);
            put(event, "memoryId", target.id);
            put(event, "state", target.state);
            put(event, "source", safe(source));
            put(event, "successCount", target.successCount);
            put(event, "supportCount", target.supportCount);
            put(event, "failureCount", target.failureCount);
            appendEventLocked(event);
            return copy(target);
        }
    }

    void recordUsed(
            String taskId,
            String scope,
            List<String> memoryIds) {
        if (prefs == null || memoryIds == null || memoryIds.isEmpty()) return;
        synchronized (LOCK) {
            JSONObject event = baseEvent("USED", taskId, scope);
            put(event, "memoryIds", toArray(memoryIds));
            appendEventLocked(event);
        }
    }

    void recordTaskResult(
            String taskId,
            String scope,
            List<String> usedMemoryIds,
            String actualPattern,
            int stepCount,
            long durationMs,
            boolean completed,
            boolean terminalVerified,
            boolean memoryUsageKnown) {
        if (prefs == null || safe(taskId).isEmpty()) return;
        synchronized (LOCK) {
            JSONObject event = baseEvent("TASK_RESULT", taskId, scope);
            JSONArray used = toArray(usedMemoryIds);
            JSONArray applied = new JSONArray();
            String cleanPattern = clip(
                    RefinedMemoryPolicy.canonicalPattern(actualPattern), 180);
            if (!cleanPattern.isEmpty() && usedMemoryIds != null) {
                List<Entry> items = loadLocked();
                for (String id : usedMemoryIds) {
                    Entry item = findById(items, id);
                    if (item != null
                            && RefinedMemoryDashboardPolicy
                                    .isAppliedPattern(
                                            item.pattern,
                                            cleanPattern)) {
                        applied.put(item.id);
                    }
                }
            }
            put(event, "memoryIds", used);
            put(event, "appliedIds", applied);
            put(event, "stepCount", Math.max(0, stepCount));
            put(event, "durationMs", Math.max(0L, durationMs));
            put(event, "completed", completed);
            put(event, "terminalVerified", terminalVerified);
            put(event, "memoryUsageKnown", memoryUsageKnown);
            appendEventLocked(event);
        }
    }

    void recordTraceMismatch(
            String taskId,
            String traceTaskId,
            String scope) {
        if (prefs == null) return;
        synchronized (LOCK) {
            JSONObject event = baseEvent(
                    "TRACE_MISMATCH",
                    taskId,
                    scope);
            if (!safe(traceTaskId).isEmpty()) {
                put(event, "traceTaskId", safe(traceTaskId));
            }
            appendEventLocked(event);
        }
    }

    JSONArray recentEvents(int limit) {
        JSONArray out = new JSONArray();
        if (prefs == null) return out;
        synchronized (LOCK) {
            JSONArray events = loadEventsLocked();
            int max = Math.max(0, Math.min(30, limit));
            int start = Math.max(0, events.length() - max);
            for (int i = events.length() - 1; i >= start; i--) {
                JSONObject event = events.optJSONObject(i);
                if (event != null) out.put(copyObject(event));
            }
        }
        return out;
    }

    JSONObject usageStats(String memoryId, String scope) {
        JSONObject out = new JSONObject();
        if (prefs == null) return out;
        synchronized (LOCK) {
            JSONArray events = loadEventsLocked();
            String id = safe(memoryId);
            String cleanScope = safe(scope);
            RefinedMemoryDashboardPolicy.UsageAccumulator stats =
                    new RefinedMemoryDashboardPolicy.UsageAccumulator();
            int corrected = 0;
            long lastUsedAt = 0L;

            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                String type = event.optString("type", "");
                if ("TASK_RESULT".equals(type)) {
                    JSONArray ids = event.optJSONArray("memoryIds");
                    boolean contains = containsId(ids, id);
                    boolean hasAnyMemory =
                            ids != null && ids.length() > 0;
                    boolean correctedTask =
                            isTaskCorrectedLocked(
                                    event.optString("taskId", ""));
                    stats.observe(
                            cleanScope.equals(
                                    event.optString("scope", "")),
                            event.optBoolean(
                                    "memoryUsageKnown", true),
                            contains,
                            hasAnyMemory,
                            event.optBoolean(
                                    "terminalVerified", false),
                            correctedTask,
                            containsId(
                                    event.optJSONArray("appliedIds"),
                                    id),
                            event.optInt("stepCount", 0),
                            event.optLong("durationMs", 0L));
                    if (contains) {
                        lastUsedAt = Math.max(
                                lastUsedAt,
                                event.optLong("at", 0L));
                    }
                } else if ("CORRECTED".equals(type)
                        && (containsId(event.optJSONArray("usedIds"), id)
                            || containsId(event.optJSONArray("learnedIds"), id))) {
                    corrected++;
                }
            }

            put(out, "usedTasks", stats.usedTasks);
            put(out, "verifiedTasks", stats.verifiedTasks);
            put(out, "appliedTasks", stats.appliedTasks);
            put(out, "corrections", corrected);
            put(out, "lastUsedAt", lastUsedAt);
            put(out, "avgStepsWith",
                    stats.usedTasks == 0
                            ? 0.0d
                            : stats.usedSteps / (double) stats.usedTasks);
            put(out, "avgDurationMsWith",
                    stats.usedTasks == 0
                            ? 0.0d
                            : stats.usedDuration / (double) stats.usedTasks);
            put(out, "baselineTasks", stats.baselineTasks);
            put(out, "baselineVerified", stats.baselineVerified);
            put(out, "avgStepsWithout",
                    stats.baselineTasks == 0
                            ? 0.0d
                            : stats.baselineSteps
                                    / (double) stats.baselineTasks);
            put(out, "avgDurationMsWithout",
                    stats.baselineTasks == 0
                            ? 0.0d
                            : stats.baselineDuration
                                    / (double) stats.baselineTasks);
        }
        return out;
    }

    JSONObject dashboardSummary() {
        JSONObject out = new JSONObject();
        if (prefs == null) return out;
        synchronized (LOCK) {
            JSONArray events = loadEventsLocked();
            RefinedMemoryDashboardPolicy.OverallAccumulator stats =
                    new RefinedMemoryDashboardPolicy.OverallAccumulator();
            int corrections = 0;
            int traceMismatches = 0;

            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                String type = event.optString("type", "");
                if ("CORRECTED".equals(type)) {
                    corrections++;
                    continue;
                }
                if ("TRACE_MISMATCH".equals(type)) {
                    traceMismatches++;
                    continue;
                }
                if (!"TASK_RESULT".equals(type)) continue;

                JSONArray ids = event.optJSONArray("memoryIds");
                stats.observe(
                        event.optBoolean("memoryUsageKnown", true),
                        ids != null && ids.length() > 0,
                        event.optBoolean("terminalVerified", false),
                        isTaskCorrectedLocked(
                                event.optString("taskId", "")));
            }

            put(out, "memoryTasks", stats.memoryTasks);
            put(out, "memoryVerified", stats.memoryVerified);
            put(out, "noMemoryTasks", stats.noMemoryTasks);
            put(out, "noMemoryVerified", stats.noMemoryVerified);
            put(out, "corrections", corrections);
            put(out, "traceMismatches", traceMismatches);
        }
        return out;
    }

    boolean setEnabled(String id, boolean enabled) {
        if (prefs == null || safe(id).isEmpty()) return false;
        synchronized (LOCK) {
            List<Entry> items = loadLocked();
            Entry item = findById(items, id);
            if (item == null) return false;
            item.enabled = enabled;
            item.updatedAt = System.currentTimeMillis();
            trimAndSaveLocked(items);
            JSONObject event = baseEvent(
                    enabled ? "ENABLED" : "DISABLED",
                    "",
                    item.scope);
            put(event, "memoryId", item.id);
            appendEventLocked(event);
            return true;
        }
    }

    boolean delete(String id) {
        if (prefs == null || safe(id).isEmpty()) return false;
        synchronized (LOCK) {
            List<Entry> items = loadLocked();
            for (int i = 0; i < items.size(); i++) {
                Entry item = items.get(i);
                if (item != null && safe(id).equals(item.id)) {
                    String scope = item.scope;
                    items.remove(i);
                    trimAndSaveLocked(items);
                    JSONObject event = baseEvent("DELETED", "", scope);
                    put(event, "memoryId", safe(id));
                    appendEventLocked(event);
                    return true;
                }
            }
            return false;
        }
    }

    int clearAll() {
        if (prefs == null) return 0;
        synchronized (LOCK) {
            int count = loadLocked().size();
            prefs.edit()
                    .remove(KEY_DATA)
                    .remove(KEY_EVENTS)
                    .remove(KEY_CORRECTED_TASKS)
                    .apply();
            return count;
        }
    }

    String buildInspectorReport(String taskId) {
        String id = safe(taskId);
        StringBuilder out = new StringBuilder();
        out.append("Refined Memory trace\n");
        if (prefs == null || id.isEmpty()) {
            out.append("Task memory: unavailable");
            return out.toString();
        }

        synchronized (LOCK) {
            JSONArray events = loadEventsLocked();
            JSONObject taskResult = null;
            java.util.LinkedHashSet<String> usedIds =
                    new java.util.LinkedHashSet<String>();
            int usedCount = 0;
            int learnedCount = 0;
            int correctedCount = 0;
            String learnedScope = "";
            String learnedState = "";

            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null || !id.equals(event.optString("taskId", ""))) {
                    continue;
                }
                String type = event.optString("type", "");
                if ("USED".equals(type)) {
                    JSONArray ids = event.optJSONArray("memoryIds");
                    addIds(usedIds, ids);
                    usedCount = Math.max(
                            usedCount,
                            ids == null ? 0 : ids.length());
                } else if ("TASK_RESULT".equals(type)) {
                    taskResult = event;
                    JSONArray ids = event.optJSONArray("memoryIds");
                    addIds(usedIds, ids);
                    usedCount = Math.max(
                            usedCount,
                            ids == null ? 0 : ids.length());
                } else if ("LEARNED".equals(type)
                        || "EVIDENCE".equals(type)
                        || "REPLAY_SUPPORT".equals(type)) {
                    learnedCount++;
                    learnedScope = event.optString("scope", "");
                    learnedState = event.optString("state", "");
                } else if ("CORRECTED".equals(type)) {
                    correctedCount += Math.max(1, event.optInt("changed", 1));
                }
            }

            out.append("Used memories: ").append(usedCount).append("\n");
            if (!usedIds.isEmpty()) {
                List<Entry> items = loadLocked();
                int shown = 0;
                for (String memoryId : usedIds) {
                    Entry item = findById(items, memoryId);
                    if (item == null) continue;
                    out.append("  - ")
                            .append(item.scope)
                            .append(" · ")
                            .append(effectiveState(
                                    item,
                                    System.currentTimeMillis()))
                            .append(" · ")
                            .append(item.pattern)
                            .append("\n");
                    if (++shown >= 3) break;
                }
            }
            if (taskResult != null) {
                JSONArray applied = taskResult.optJSONArray("appliedIds");
                out.append("Pattern applied: ")
                        .append(applied == null ? 0 : applied.length())
                        .append("/")
                        .append(usedCount)
                        .append("\n");
                boolean correctedTask =
                        correctedCount > 0
                                || isTaskCorrectedLocked(id);
                out.append("Terminal verified: ")
                        .append(taskResult.optBoolean("terminalVerified", false)
                                        && !correctedTask
                                ? "yes" : (correctedTask
                                        ? "retracted by correction"
                                        : "no"))
                        .append("\n");
                out.append("Steps / duration: ")
                        .append(taskResult.optInt("stepCount", 0))
                        .append(" / ")
                        .append(taskResult.optLong("durationMs", 0L))
                        .append("ms\n");
            }
            if (learnedCount > 0) {
                out.append("Learning: ")
                        .append(learnedCount)
                        .append(" update");
                if (!learnedScope.isEmpty()) {
                    out.append(" · ").append(learnedScope);
                }
                if (!learnedState.isEmpty()) {
                    out.append(" · ").append(learnedState);
                }
                out.append("\n");
            } else {
                out.append("Learning: none\n");
            }
            out.append("Corrections: ").append(correctedCount);
        }
        return out.toString();
    }

    static final class Selection {
        final JSONArray modelLines = new JSONArray();
        final ArrayList<String> ids = new ArrayList<String>();

        boolean isEmpty() {
            return ids.isEmpty();
        }
    }

    Selection selectForModel(
            String scope,
            String packageName,
            String startPackage,
            int limit) {
        int max = Math.max(0, Math.min(3, limit));
        Selection out = new Selection();
        if (prefs == null || max == 0) return out;

        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            List<ScoredEntry> scored = new ArrayList<ScoredEntry>();
            for (Entry item : loadLocked()) {
                String state = effectiveState(item, now);
                if (!RefinedMemoryPolicy.isSelectable(
                        item.enabled, state)) continue;
                boolean exactScope =
                        safe(scope).equals(item.scope);
                int packageAffinity =
                        RefinedMemoryPolicy.packageAffinityScore(
                                item.packageName,
                                item.startPackage,
                                packageName,
                                startPackage);
                if (packageAffinity == Integer.MIN_VALUE) {
                    continue;
                }
                boolean exactPrimaryPackage =
                        safe(packageName).equals(
                                item.packageName);
                int score = RefinedMemoryPolicy.relevanceScore(
                        state,
                        item.confidence,
                        exactScope,
                        exactPrimaryPackage,
                        item.lastVerifiedAt,
                        now);
                if (score != Integer.MIN_VALUE
                        && !exactPrimaryPackage) {
                    // relevanceScore gives the conservative generic-package
                    // baseline. Replace it with the explicit start-package
                    // affinity when this is a cross-App learned procedure.
                    score += Math.max(0, packageAffinity - 40);
                }
                if (score == Integer.MIN_VALUE) continue;
                scored.add(new ScoredEntry(item, score));
            }
            Collections.sort(
                    scored,
                    new Comparator<ScoredEntry>() {
                        @Override public int compare(
                                ScoredEntry a,
                                ScoredEntry b) {
                            return Integer.compare(b.score, a.score);
                        }
                    });
            for (ScoredEntry ranked : scored) {
                if (out.modelLines.length() >= max) break;
                out.modelLines.put(modelLine(ranked.entry));
                out.ids.add(ranked.entry.id);
            }
        }
        return out;
    }

    Selection selectForModel(
            String scope,
            String packageName,
            int limit) {
        return selectForModel(
                scope,
                packageName,
                "",
                limit);
    }

    JSONArray forModel(
            String scope,
            String packageName,
            int limit) {
        return selectForModel(
                scope,
                packageName,
                "",
                limit).modelLines;
    }

    JSONArray dumpForDebug() {
        JSONArray out = new JSONArray();
        synchronized (LOCK) {
            List<Entry> items = loadLocked();
            Collections.sort(
                    items,
                    new Comparator<Entry>() {
                        @Override public int compare(Entry a, Entry b) {
                            return Long.compare(b.updatedAt, a.updatedAt);
                        }
                    });
            long now = System.currentTimeMillis();
            for (Entry item : items) {
                JSONObject json = item.toJson();
                put(json, "state", effectiveState(item, now));
                put(json, "evidenceCount",
                        item.successCount
                                + item.supportCount
                                + item.failureCount);
                out.put(json);
            }
        }
        return out;
    }

    int count() {
        synchronized (LOCK) {
            return prefs == null ? 0 : loadLocked().size();
        }
    }

    int injectableCount() {
        synchronized (LOCK) {
            int count = 0;
            long now = System.currentTimeMillis();
            for (Entry item : loadLocked()) {
                if (RefinedMemoryPolicy.isSelectable(
                        item.enabled,
                        effectiveState(item, now))) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class ScoredEntry {
        final Entry entry;
        final int score;

        ScoredEntry(Entry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }

    private String effectiveState(Entry item, long now) {
        if (item == null) return RefinedMemoryPolicy.STATE_CANDIDATE;
        if (RefinedMemoryPolicy.STATE_SUSPECT.equals(item.state)) {
            return RefinedMemoryPolicy.STATE_SUSPECT;
        }
        if (item.lastVerifiedAt > 0L
                && now - item.lastVerifiedAt > STALE_AFTER_MS) {
            return RefinedMemoryPolicy.STATE_STALE;
        }
        return RefinedMemoryPolicy.stateFor(
                item.successCount,
                item.failureCount);
    }

    private List<Entry> loadLocked() {
        ArrayList<Entry> out = new ArrayList<Entry>();
        if (prefs == null) return out;
        try {
            JSONArray array = new JSONArray(
                    prefs.getString(KEY_DATA, "[]"));
            for (int i = 0; i < array.length(); i++) {
                Entry item = Entry.fromJson(
                        array.optJSONObject(i));
                if (!item.id.isEmpty()
                        && RefinedMemoryPolicy.isEligibleScope(item.scope)
                        && !item.pattern.isEmpty()) {
                    out.add(item);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void trimAndSaveLocked(List<Entry> items) {
        while (items.size() > MAX_ITEMS) {
            int remove = 0;
            long weakestAt = Long.MAX_VALUE;
            int weakestRank = Integer.MAX_VALUE;
            for (int i = 0; i < items.size(); i++) {
                Entry item = items.get(i);
                int rank = stateRank(
                        effectiveState(item, System.currentTimeMillis()));
                long at = Math.max(item.updatedAt, item.lastVerifiedAt);
                if (rank < weakestRank
                        || (rank == weakestRank && at < weakestAt)) {
                    weakestRank = rank;
                    weakestAt = at;
                    remove = i;
                }
            }
            items.remove(remove);
        }

        JSONArray data = new JSONArray();
        for (Entry item : items) data.put(item.toJson());
        prefs.edit().putString(KEY_DATA, data.toString()).apply();
    }

    private static int stateRank(String state) {
        if (RefinedMemoryPolicy.STATE_TRUSTED.equals(state)) return 4;
        if (RefinedMemoryPolicy.STATE_VERIFIED.equals(state)) return 3;
        if (RefinedMemoryPolicy.STATE_CANDIDATE.equals(state)) return 2;
        if (RefinedMemoryPolicy.STATE_SUSPECT.equals(state)) return 1;
        return 0;
    }

    private JSONArray loadEventsLocked() {
        if (prefs == null) return new JSONArray();
        try {
            return new JSONArray(
                    prefs.getString(KEY_EVENTS, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void appendEventLocked(JSONObject event) {
        if (prefs == null || event == null) return;
        JSONArray events = loadEventsLocked();
        events.put(event);
        if (events.length() > MAX_EVENTS) {
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, events.length() - MAX_EVENTS);
                    i < events.length();
                    i++) {
                trimmed.put(events.opt(i));
            }
            events = trimmed;
        }
        prefs.edit()
                .putString(KEY_EVENTS, events.toString())
                .apply();
    }

    private static JSONObject baseEvent(
            String type,
            String taskId,
            String scope) {
        JSONObject event = new JSONObject();
        put(event, "at", System.currentTimeMillis());
        put(event, "type", safe(type));
        if (!safe(taskId).isEmpty()) {
            put(event, "taskId", safe(taskId));
        }
        if (!safe(scope).isEmpty()) {
            put(event, "scope", safe(scope));
        }
        return event;
    }

    private static JSONArray toArray(List<String> values) {
        JSONArray out = new JSONArray();
        if (values == null) return out;
        for (String value : values) {
            String clean = safe(value);
            if (!clean.isEmpty()) out.put(clean);
        }
        return out;
    }

    private static boolean containsId(
            JSONArray values,
            String id) {
        String cleanId = safe(id);
        if (values == null || cleanId.isEmpty()) return false;
        for (int i = 0; i < values.length(); i++) {
            if (cleanId.equals(safe(values.optString(i, "")))) {
                return true;
            }
        }
        return false;
    }

    private static void addIds(
            java.util.Set<String> target,
            JSONArray values) {
        if (target == null || values == null) return;
        for (int i = 0; i < values.length(); i++) {
            String id = safe(values.optString(i, ""));
            if (!id.isEmpty()) target.add(id);
        }
    }

    private void markCorrectedTaskLocked(String taskId) {
        String id = safe(taskId);
        if (prefs == null || id.isEmpty()) return;

        JSONArray current = loadCorrectedTasksLocked();
        JSONArray next = new JSONArray();
        JSONObject latest = new JSONObject();
        put(latest, "taskId", id);
        put(latest, "at", System.currentTimeMillis());
        next.put(latest);

        for (int i = 0;
                i < current.length()
                        && next.length() < MAX_CORRECTED_TASKS;
                i++) {
            JSONObject item = current.optJSONObject(i);
            if (item == null) continue;
            String existing = safe(item.optString("taskId", ""));
            if (existing.isEmpty() || id.equals(existing)) continue;
            next.put(item);
        }

        prefs.edit()
                .putString(KEY_CORRECTED_TASKS, next.toString())
                .apply();
    }

    private boolean isTaskCorrectedLocked(String taskId) {
        String id = safe(taskId);
        if (prefs == null || id.isEmpty()) return false;
        JSONArray items = loadCorrectedTasksLocked();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null
                    && id.equals(safe(item.optString("taskId", "")))) {
                return true;
            }
        }
        return false;
    }

    private JSONArray loadCorrectedTasksLocked() {
        if (prefs == null) return new JSONArray();
        try {
            return new JSONArray(
                    prefs.getString(KEY_CORRECTED_TASKS, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static Entry findById(
            List<Entry> items,
            String id) {
        String cleanId = safe(id);
        if (items == null || cleanId.isEmpty()) return null;
        for (Entry item : items) {
            if (item != null && cleanId.equals(item.id)) {
                return item;
            }
        }
        return null;
    }

    private static JSONObject copyObject(JSONObject source) {
        if (source == null) return new JSONObject();
        try {
            return new JSONObject(source.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static boolean sameIdentity(
            Entry item,
            String scope,
            String packageName,
            String pattern) {
        return item != null
                && RefinedMemoryPolicy.TYPE_PROCEDURE.equals(item.type)
                && scope.equals(item.scope)
                && packageName.equals(item.packageName)
                && pattern.equals(item.pattern);
    }

    private static String modelLine(Entry item) {
        if (item == null) return "";
        int evidence =
                item.successCount
                        + item.supportCount
                        + item.failureCount;
        return clip(item.guidance, 160)
                + " ["
                + item.state
                + ", independent="
                + item.successCount
                + ", support="
                + item.supportCount
                + ", total="
                + evidence
                + "]";
    }

    private static Entry copy(Entry source) {
        return Entry.fromJson(source == null ? null : source.toJson());
    }

    private static void put(
            JSONObject target,
            String key,
            Object value) {
        try { target.put(key, value); } catch (Exception ignored) {}
    }

    private static String clip(String value, int max) {
        String text = safe(value).replaceAll("\\s+", " ");
        return text.length() <= max
                ? text
                : text.substring(0, max);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
