package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Privacy-bounded Candidate Arbitration experiment telemetry.
 *
 * Never stores task goal text, candidate labels/view ids, screenshots, tool
 * payloads, model prompts, or user utterances.
 */
final class CandidateArbitrationTelemetryStore {
    private static final String PREFS = "crew_candidate_arbitration";
    private static final String KEY_EVENTS = "events_v1";
    private static final int MAX_EVENTS = 100;
    private static final Object LOCK = new Object();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private CandidateArbitrationTelemetryStore() {}

    static String recordStart(
            Context context,
            String taskId,
            long generation,
            String packageName,
            String goalIntent,
            CandidateArbitrationPolicy.TriggerType triggerType,
            CandidateArbitrationPolicy.Bucket bucket,
            List<CandidateArbitrator.Candidate> candidates) {
        long now = System.currentTimeMillis();
        String eventId = "arb_" + now + "_" + SEQUENCE.incrementAndGet();
        if (context == null) return eventId;

        JSONObject event = new JSONObject();
        try {
            JSONArray ids = new JSONArray();
            JSONArray confidences = new JSONArray();
            int count = 0;
            if (candidates != null) {
                for (CandidateArbitrator.Candidate candidate : candidates) {
                    if (candidate == null) continue;
                    ids.put(candidate.candidateId);
                    confidences.put(candidate.confidence);
                    count++;
                }
            }
            event.put("eventId", eventId)
                    .put("timestamp", now)
                    .put("taskId", safe(taskId, 96))
                    .put("generation", generation)
                    .put("package", safe(packageName, 120))
                    .put("goalIntent", safe(goalIntent, 96))
                    .put("experimentPhase", "PHASE_1")
                    .put(
                            "bucket",
                            bucket == null
                                    ? "CONTROL"
                                    : bucket.name())
                    .put("triggerType",
                            triggerType == null
                                    ? "NONE"
                                    : triggerType.name())
                    .put("candidateCount", count)
                    .put("candidateIds", ids)
                    .put("candidateConfidences", confidences)
                    .put("model", CandidateArbitrator.MODEL)
                    .put("latencyMs", -1L)
                    .put("additionalLatencyMs", 0L)
                    .put("timeout", false)
                    .put("selectedCandidateId", "")
                    .put("arbitratorConfidence", 0.0d)
                    .put("abstain", true)
                    .put("reasonCode", "PENDING")
                    .put("baselineSelectedCandidateId", "")
                    .put("executedCandidateId", "")
                    .put("treatmentExecuted", false)
                    .put("revalidationCode", "NOT_APPLICABLE")
                    .put("interactionVerified", false)
                    .put("semanticEffectVerified", false)
                    .put("correctionObserved", false);
        } catch (Exception ignored) {}

        synchronized (LOCK) {
            JSONArray events = read(context);
            events.put(event);
            write(context, trim(events));
        }
        return eventId;
    }

    static void recordAdvice(
            Context context,
            String eventId,
            CandidateArbitrator.Advice advice,
            long latencyMs,
            boolean timeout,
            String reasonOverride,
            boolean addedToUserPath) {
        update(context, eventId, new EventUpdate() {
            @Override public void apply(JSONObject event) throws Exception {
                CandidateArbitrator.Advice safeAdvice =
                        advice == null
                                ? CandidateArbitrator.Advice.abstain(
                                        reasonOverride)
                                : advice;
                long boundedLatency = Math.max(0L, latencyMs);
                event.put("latencyMs", boundedLatency)
                        .put(
                                "additionalLatencyMs",
                                addedToUserPath
                                        ? boundedLatency
                                        : 0L)
                        .put("timeout", timeout)
                        .put(
                                "selectedCandidateId",
                                safe(safeAdvice.selectedCandidateId, 96))
                        .put(
                                "arbitratorConfidence",
                                safeAdvice.confidence)
                        .put("abstain", safeAdvice.abstain)
                        .put(
                                "reasonCode",
                                safe(
                                        reasonOverride == null
                                                || reasonOverride.trim().isEmpty()
                                                        ? safeAdvice.reasonCode
                                                        : reasonOverride,
                                        64));
            }
        });
    }

    static void recordBaselineCandidate(
            Context context,
            String eventId,
            String candidateId) {
        update(context, eventId, new EventUpdate() {
            @Override public void apply(JSONObject event) throws Exception {
                event.put(
                        "baselineSelectedCandidateId",
                        safe(candidateId, 96));
            }
        });
    }

    static void recordTreatmentExecution(
            Context context,
            String eventId,
            String revalidationCode,
            String executedCandidateId,
            boolean executed) {
        update(context, eventId, new EventUpdate() {
            @Override public void apply(JSONObject event) throws Exception {
                event.put(
                                "revalidationCode",
                                safe(revalidationCode, 64))
                        .put(
                                "executedCandidateId",
                                safe(executedCandidateId, 96))
                        .put("treatmentExecuted", executed);
            }
        });
    }

    static void recordOutcome(
            Context context,
            String eventId,
            boolean interactionVerified,
            boolean semanticEffectVerified) {
        update(context, eventId, new EventUpdate() {
            @Override public void apply(JSONObject event) throws Exception {
                event.put(
                                "interactionVerified",
                                interactionVerified)
                        .put(
                                "semanticEffectVerified",
                                semanticEffectVerified);
            }
        });
    }

    static void recordCorrectionForRecent(
            Context context,
            long generation,
            long nowMs,
            long maxAgeMs) {
        if (context == null || generation < 0L) return;
        synchronized (LOCK) {
            JSONArray events = read(context);
            for (int i = events.length() - 1; i >= 0; i--) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                if (event.optLong("generation", -1L) != generation) continue;
                long age = nowMs - event.optLong("timestamp", 0L);
                if (age < 0L || age > Math.max(0L, maxAgeMs)) return;
                try {
                    event.put("correctionObserved", true);
                } catch (Exception ignored) {}
                write(context, trim(events));
                return;
            }
        }
    }

    static String buildReport(Context context) {
        if (context == null) return "";
        JSONArray events;
        synchronized (LOCK) {
            events = read(context);
        }
        int total = events.length();
        if (total == 0) {
            return "Candidate arbitration · no qualifying samples yet.";
        }

        int legacy = 0;
        int control = 0;
        int treatment = 0;
        int completed = 0;
        int decisive = 0;
        int abstain = 0;
        int timeout = 0;
        int treatmentExecuted = 0;
        int treatmentRejected = 0;

        int controlInteraction = 0;
        int treatmentInteraction = 0;
        int controlSemantic = 0;
        int treatmentSemantic = 0;
        int controlCorrection = 0;
        int treatmentCorrection = 0;
        int controlProgress = 0;
        int treatmentProgress = 0;

        int controlAccuracySamples = 0;
        int controlAccuracyMatches = 0;

        List<Long> allModelLatencies = new ArrayList<Long>();
        List<Long> treatmentAddedLatencies = new ArrayList<Long>();

        for (int i = 0; i < total; i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;

            String bucket = event.optString("bucket", "");
            boolean isControl = "CONTROL".equals(bucket);
            boolean isTreatment = "TREATMENT".equals(bucket);
            if (!isControl && !isTreatment) {
                legacy++;
                continue;
            }

            if (isControl) control++;
            if (isTreatment) treatment++;

            String reason = event.optString("reasonCode", "");
            if (!reason.isEmpty() && !"PENDING".equals(reason)) completed++;

            boolean didAbstain = event.optBoolean("abstain", true);
            if (didAbstain) abstain++;
            else decisive++;
            if (event.optBoolean("timeout", false)) timeout++;

            long latency = event.optLong("latencyMs", -1L);
            if (latency >= 0L) allModelLatencies.add(latency);
            long added = event.optLong("additionalLatencyMs", -1L);
            if (isTreatment && added >= 0L) {
                treatmentAddedLatencies.add(added);
            }

            boolean interaction =
                    event.optBoolean("interactionVerified", false);
            boolean semantic =
                    event.optBoolean("semanticEffectVerified", false);
            boolean correction =
                    event.optBoolean("correctionObserved", false);
            boolean progress = semantic && !correction;

            if (isControl) {
                if (interaction) controlInteraction++;
                if (semantic) controlSemantic++;
                if (correction) controlCorrection++;
                if (progress) controlProgress++;
            } else {
                if (interaction) treatmentInteraction++;
                if (semantic) treatmentSemantic++;
                if (correction) treatmentCorrection++;
                if (progress) treatmentProgress++;
                if (event.optBoolean("treatmentExecuted", false)) {
                    treatmentExecuted++;
                } else {
                    String revalidation =
                            event.optString("revalidationCode", "");
                    if (!revalidation.isEmpty()
                            && !"NOT_APPLICABLE".equals(revalidation)) {
                        treatmentRejected++;
                    }
                }
            }

            if (isControl
                    && !didAbstain
                    && semantic
                    && !correction) {
                String shadow =
                        event.optString("selectedCandidateId", "");
                String baseline =
                        event.optString(
                                "baselineSelectedCandidateId", "");
                if (!shadow.isEmpty() && !baseline.isEmpty()) {
                    controlAccuracySamples++;
                    if (shadow.equals(baseline)) {
                        controlAccuracyMatches++;
                    }
                }
            }
        }

        Collections.sort(allModelLatencies);
        Collections.sort(treatmentAddedLatencies);

        StringBuilder out = new StringBuilder();
        out.append("Candidate arbitration Phase 1 A/B · last ")
                .append(total)
                .append("/")
                .append(MAX_EVENTS)
                .append(" stored events\n")
                .append("A/B samples: control=")
                .append(control)
                .append(" · treatment=")
                .append(treatment);
        if (legacy > 0) {
            out.append(" · legacy shadow excluded=")
                    .append(legacy);
        }
        out.append("\n")
                .append("Arbitration coverage: ")
                .append(percent(completed, control + treatment))
                .append("% · decisive: ")
                .append(percent(decisive, control + treatment))
                .append("% · abstain: ")
                .append(percent(abstain, control + treatment))
                .append("% · timeout: ")
                .append(percent(timeout, control + treatment))
                .append("%\n");

        if (!allModelLatencies.isEmpty()) {
            out.append("Model latency P50/P95: ")
                    .append(percentile(allModelLatencies, 0.50d))
                    .append("/")
                    .append(percentile(allModelLatencies, 0.95d))
                    .append(" ms\n");
        }
        if (!treatmentAddedLatencies.isEmpty()) {
            out.append("Treatment added latency P50/P95: ")
                    .append(percentile(
                            treatmentAddedLatencies, 0.50d))
                    .append("/")
                    .append(percentile(
                            treatmentAddedLatencies, 0.95d))
                    .append(" ms\n");
        }

        out.append("Control interaction verified: ")
                .append(percent(controlInteraction, control))
                .append("% · treatment: ")
                .append(percent(treatmentInteraction, treatment))
                .append("%\n")
                .append("Control semantic effect verified: ")
                .append(percent(controlSemantic, control))
                .append("% · treatment: ")
                .append(percent(treatmentSemantic, treatment))
                .append("%\n")
                .append("Control correction: ")
                .append(percent(controlCorrection, control))
                .append("% · treatment: ")
                .append(percent(treatmentCorrection, treatment))
                .append("%\n")
                .append("Progress proxy control/treatment: ")
                .append(percent(controlProgress, control))
                .append("% / ")
                .append(percent(treatmentProgress, treatment))
                .append("%\n")
                .append("Treatment executed: ")
                .append(treatmentExecuted)
                .append(" · revalidation rejected: ")
                .append(treatmentRejected)
                .append("\n")
                .append("Control decisive accuracy proxy: ");
        if (controlAccuracySamples == 0) {
            out.append("n/a");
        } else {
            out.append(percent(
                            controlAccuracyMatches,
                            controlAccuracySamples))
                    .append("% (n=")
                    .append(controlAccuracySamples)
                    .append(")");
        }

        out.append("\nProgress proxy is semanticEffectVerified && !correctionObserved; "
                + "it is not terminal truth. Treatment execution still requires fresh Runtime revalidation.");
        return out.toString();
    }

    private interface EventUpdate {
        void apply(JSONObject event) throws Exception;
    }

    private static void update(
            Context context,
            String eventId,
            EventUpdate update) {
        if (context == null
                || eventId == null
                || eventId.trim().isEmpty()
                || update == null) {
            return;
        }
        synchronized (LOCK) {
            JSONArray events = read(context);
            for (int i = events.length() - 1; i >= 0; i--) {
                JSONObject event = events.optJSONObject(i);
                if (event == null
                        || !eventId.equals(
                                event.optString("eventId", ""))) {
                    continue;
                }
                try { update.apply(event); } catch (Exception ignored) {}
                write(context, trim(events));
                return;
            }
        }
    }

    private static JSONArray read(Context context) {
        try {
            SharedPreferences prefs =
                    context.getApplicationContext()
                            .getSharedPreferences(
                                    PREFS,
                                    Context.MODE_PRIVATE);
            return new JSONArray(
                    prefs.getString(KEY_EVENTS, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void write(Context context, JSONArray events) {
        try {
            context.getApplicationContext()
                    .getSharedPreferences(
                            PREFS,
                            Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_EVENTS, events.toString())
                    .apply();
        } catch (Exception ignored) {}
    }

    private static JSONArray trim(JSONArray events) {
        JSONArray out = new JSONArray();
        int start = Math.max(0, events.length() - MAX_EVENTS);
        for (int i = start; i < events.length(); i++) {
            Object value = events.opt(i);
            if (value != null) out.put(value);
        }
        return out;
    }

    private static long percentile(
            List<Long> values,
            double fraction) {
        if (values == null || values.isEmpty()) return -1L;
        int index = (int) Math.ceil(
                values.size() * fraction) - 1;
        index = Math.max(
                0,
                Math.min(values.size() - 1, index));
        return values.get(index);
    }

    private static int percent(
            int numerator,
            int denominator) {
        if (denominator <= 0) return 0;
        return (int) Math.round(
                numerator * 100.0d / denominator);
    }

    private static String safe(
            String value,
            int max) {
        String clean = value == null ? "" : value.trim();
        clean = clean.replaceAll("[\\r\\n\\t]", " ");
        if (clean.length() > max) {
            clean = clean.substring(0, max);
        }
        return clean;
    }
}
