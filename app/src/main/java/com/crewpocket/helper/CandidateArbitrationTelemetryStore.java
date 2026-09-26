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
 * Privacy-bounded persistent Phase-0 experiment events.
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
                    .put("triggerType",
                            triggerType == null
                                    ? "NONE"
                                    : triggerType.name())
                    .put("candidateCount", count)
                    .put("candidateIds", ids)
                    .put("candidateConfidences", confidences)
                    .put("model", CandidateArbitrator.MODEL)
                    .put("latencyMs", -1L)
                    .put("timeout", false)
                    .put("selectedCandidateId", "")
                    .put("arbitratorConfidence", 0.0d)
                    .put("abstain", true)
                    .put("reasonCode", "PENDING")
                    .put("baselineSelectedCandidateId", "")
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
            String reasonOverride) {
        update(context, eventId, new EventUpdate() {
            @Override public void apply(JSONObject event) throws Exception {
                CandidateArbitrator.Advice safeAdvice =
                        advice == null
                                ? CandidateArbitrator.Advice.abstain(
                                        reasonOverride)
                                : advice;
                event.put("latencyMs", Math.max(0L, latencyMs))
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
            return "Candidate arbitration shadow · no qualifying samples yet.";
        }

        int completed = 0;
        int decisive = 0;
        int abstain = 0;
        int timeout = 0;
        int interaction = 0;
        int semantic = 0;
        int correction = 0;
        int proxySamples = 0;
        int proxyMatches = 0;
        List<Long> latencies = new ArrayList<Long>();

        for (int i = 0; i < total; i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            String reason = event.optString("reasonCode", "");
            if (!reason.isEmpty() && !"PENDING".equals(reason)) completed++;
            boolean didAbstain = event.optBoolean("abstain", true);
            if (didAbstain) abstain++;
            else decisive++;
            if (event.optBoolean("timeout", false)) timeout++;
            if (event.optBoolean("interactionVerified", false)) interaction++;
            if (event.optBoolean("semanticEffectVerified", false)) semantic++;
            if (event.optBoolean("correctionObserved", false)) correction++;

            long latency = event.optLong("latencyMs", -1L);
            if (latency >= 0L) latencies.add(latency);

            String shadow = event.optString("selectedCandidateId", "");
            String baseline =
                    event.optString("baselineSelectedCandidateId", "");
            if (!didAbstain
                    && !shadow.isEmpty()
                    && !baseline.isEmpty()
                    && event.optBoolean("semanticEffectVerified", false)) {
                proxySamples++;
                if (shadow.equals(baseline)
                        && !event.optBoolean(
                                "correctionObserved", false)) {
                    proxyMatches++;
                }
            }
        }

        Collections.sort(latencies);
        long p50 = percentile(latencies, 0.50d);
        long p95 = percentile(latencies, 0.95d);

        StringBuilder out = new StringBuilder();
        out.append("Candidate arbitration shadow · last ")
                .append(total)
                .append("/")
                .append(MAX_EVENTS)
                .append(" qualifying events\\n")
                .append("Arbitration coverage: ")
                .append(percent(completed, total))
                .append("%\\n")
                .append("Decisive rate: ")
                .append(percent(decisive, total))
                .append("% · abstain: ")
                .append(percent(abstain, total))
                .append("% · timeout: ")
                .append(percent(timeout, total))
                .append("%\\n");
        if (!latencies.isEmpty()) {
            out.append("Latency P50/P95: ")
                    .append(p50)
                    .append("/")
                    .append(p95)
                    .append(" ms\\n");
        }
        out.append("Baseline interaction verified: ")
                .append(percent(interaction, total))
                .append("% · semantic effect verified: ")
                .append(percent(semantic, total))
                .append("%\\n")
                .append("Correction rate: ")
                .append(percent(correction, total))
                .append("%\\n")
                .append("Decisive accuracy proxy: ");
        if (proxySamples == 0) {
            out.append("n/a");
        } else {
            out.append(percent(proxyMatches, proxySamples))
                    .append("% (n=")
                    .append(proxySamples)
                    .append(")");
        }
        out.append("\\nProgress proxy is semanticEffectVerified && !correctionObserved; "
                + "it is not terminal truth. Shadow advice never executes.");
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

    private static long percentile(List<Long> values, double fraction) {
        if (values == null || values.isEmpty()) return -1L;
        int index = (int) Math.ceil(values.size() * fraction) - 1;
        index = Math.max(0, Math.min(values.size() - 1, index));
        return values.get(index);
    }

    private static int percent(int numerator, int denominator) {
        if (denominator <= 0) return 0;
        return (int) Math.round(numerator * 100.0d / denominator);
    }

    private static String safe(String value, int max) {
        String clean = value == null ? "" : value.trim();
        clean = clean.replaceAll("[\\r\\n\\t]", " ");
        if (clean.length() > max) clean = clean.substring(0, max);
        return clean;
    }
}
