package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Privacy-bounded A/B telemetry for Jev speech review. No transcript/UI text is persisted. */
final class JevSpeechExperimentTelemetryStore {
    private static final String PREFS = "crew_jev_speech_experiment";
    private static final String KEY_EVENTS = "events_v1";
    private static final int MAX_EVENTS = 100;
    private static final Object LOCK = new Object();
    private static final AtomicLong SEQ = new AtomicLong();

    private JevSpeechExperimentTelemetryStore() {}

    static String recordStart(
            Context context,
            long generation,
            JevSpeechExperimentPolicy.Bucket bucket,
            int uiCandidateCount) {
        long now = System.currentTimeMillis();
        String eventId = "jev_" + now + "_" + SEQ.incrementAndGet();
        if (context == null) return eventId;

        JSONObject event = new JSONObject();
        try {
            event.put("eventId", eventId)
                    .put("timestamp", now)
                    .put("generation", generation)
                    .put("bucket", bucket == null ? "CONTROL" : bucket.name())
                    .put("uiCandidateCount", Math.max(0, uiCandidateCount))
                    .put("reviewCompleted", false)
                    .put("strategy", "")
                    .put("confidence", -1d)
                    .put("latencyMs", -1L)
                    .put("additionalLatencyMs", 0L)
                    .put("reason", "PENDING")
                    .put("appliedToPath", false)
                    .put("askUser", false)
                    .put("postSearchReviewed", false)
                    .put("postSearchMatched", false)
                    .put("postSearchLatencyMs", -1L)
                    .put("outcome", "")
                    .put("cancelCategory", "")
                    .put("correctionObserved", false);
        } catch (Exception ignored) {}

        synchronized (LOCK) {
            JSONArray events = read(context);
            events.put(event);
            write(context, trim(events));
        }
        return eventId;
    }

    static void recordReview(
            Context context,
            String eventId,
            JevVoiceSemanticResolver.Result result,
            boolean appliedToPath) {
        update(context, eventId, new EventUpdate() {
            @Override public void apply(JSONObject event) throws Exception {
                JevVoiceSemanticResolver.Result safe = result == null
                        ? JevVoiceSemanticResolver.Result.skipped("NO_RESULT")
                        : result;
                event.put("reviewCompleted", safe.attempted)
                        .put("strategy", clean(safe.strategy))
                        .put("confidence", safe.confidence)
                        .put("latencyMs", safe.latencyMs)
                        .put("additionalLatencyMs",
                                appliedToPath ? safe.latencyMs : 0L)
                        .put("reason", clean(safe.reason))
                        .put("appliedToPath", appliedToPath && safe.applied)
                        .put("askUser",
                                appliedToPath
                                        && safe.applied
                                        && "ASK_USER".equals(safe.strategy));
            }
        });
    }

    static void recordPostSearch(
            Context context,
            String eventId,
            JevVoiceSemanticResolver.CandidateMatch match) {
        update(context, eventId, new EventUpdate() {
            @Override public void apply(JSONObject event) throws Exception {
                JevVoiceSemanticResolver.CandidateMatch safe = match == null
                        ? JevVoiceSemanticResolver.CandidateMatch.none("NO_RESULT", 0L)
                        : match;
                event.put("postSearchReviewed", safe.attempted)
                        .put("postSearchMatched", safe.accepted)
                        .put("postSearchLatencyMs", safe.latencyMs);
                if ("TREATMENT".equals(event.optString("bucket", ""))
                        && safe.attempted) {
                    event.put("additionalLatencyMs",
                            event.optLong("additionalLatencyMs", 0L)
                                    + Math.max(0L, safe.latencyMs));
                }
            }
        });
    }

    static void recordOutcome(
            Context context,
            String eventId,
            String outcome,
            String cancelCategory) {
        update(context, eventId, new EventUpdate() {
            @Override public void apply(JSONObject event) throws Exception {
                event.put("outcome", clean(outcome))
                        .put("cancelCategory", clean(cancelCategory));
            }
        });
    }

    static void recordCorrectionForGeneration(
            Context context,
            long generation,
            long nowMs,
            long maxAgeMs) {
        if (context == null || generation < 0L) return;
        synchronized (LOCK) {
            JSONArray events = read(context);
            for (int i = events.length() - 1; i >= 0; i--) {
                JSONObject event = events.optJSONObject(i);
                if (event == null
                        || event.optLong("generation", -1L) != generation) {
                    continue;
                }
                long age = nowMs - event.optLong("timestamp", 0L);
                if (age < 0L || age > Math.max(0L, maxAgeMs)) return;
                try { event.put("correctionObserved", true); }
                catch (Exception ignored) {}
                write(context, trim(events));
                return;
            }
        }
    }

    static String buildReport(Context context) {
        if (context == null) return "";
        JSONArray events;
        synchronized (LOCK) { events = read(context); }
        if (events.length() == 0) {
            return "Jev speech A/B · no qualifying samples yet.";
        }

        int control = 0, treatment = 0;
        int controlEvaluated = 0, treatmentEvaluated = 0;
        int controlSuccess = 0, treatmentSuccess = 0;
        int controlCorrection = 0, treatmentCorrection = 0;
        int controlAsk = 0, treatmentAsk = 0;
        int treatmentApplied = 0;
        int postSearchReviewed = 0, postSearchMatched = 0;
        List<Long> modelLatency = new ArrayList<Long>();
        List<Long> addedLatency = new ArrayList<Long>();

        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            boolean isTreatment =
                    "TREATMENT".equals(event.optString("bucket", ""));
            if (isTreatment) treatment++; else control++;

            long latency = event.optLong("latencyMs", -1L);
            if (latency >= 0L) modelLatency.add(latency);
            long added = event.optLong("additionalLatencyMs", -1L);
            if (isTreatment && added >= 0L) addedLatency.add(added);

            if (event.optBoolean("appliedToPath", false)) treatmentApplied++;
            if (event.optBoolean("postSearchReviewed", false)) postSearchReviewed++;
            if (event.optBoolean("postSearchMatched", false)) postSearchMatched++;

            boolean correction = event.optBoolean("correctionObserved", false);
            if (correction) {
                if (isTreatment) treatmentCorrection++;
                else controlCorrection++;
            }

            boolean ask = event.optBoolean("askUser", false);
            if (ask) {
                if (isTreatment) treatmentAsk++;
                else controlAsk++;
            }

            String outcome = event.optString("outcome", "");
            String cancelCategory = event.optString("cancelCategory", "");
            if (outcome.isEmpty()
                    || AgentPerformanceStore.isExcludedFromSuccessRate(cancelCategory)) {
                continue;
            }
            boolean success = "SUCCESS".equals(outcome)
                    || "RECOVERED_SUCCESS".equals(outcome);
            if (isTreatment) {
                treatmentEvaluated++;
                if (success) treatmentSuccess++;
            } else {
                controlEvaluated++;
                if (success) controlSuccess++;
            }
        }

        Collections.sort(modelLatency);
        Collections.sort(addedLatency);
        StringBuilder out = new StringBuilder();
        out.append("Jev speech A/B · last ")
                .append(events.length()).append("/").append(MAX_EVENTS)
                .append(" qualifying turns\n")
                .append("Samples control/treatment: ")
                .append(control).append(" / ").append(treatment).append("\n")
                .append("Full-success control/treatment: ")
                .append(percent(controlSuccess, controlEvaluated))
                .append("% (n=").append(controlEvaluated).append(") / ")
                .append(percent(treatmentSuccess, treatmentEvaluated))
                .append("% (n=").append(treatmentEvaluated).append(")\n")
                .append("Correction control/treatment: ")
                .append(percent(controlCorrection, control))
                .append("% / ")
                .append(percent(treatmentCorrection, treatment))
                .append("%\n")
                .append("Ask-user control/treatment: ")
                .append(percent(controlAsk, control))
                .append("% / ")
                .append(percent(treatmentAsk, treatment))
                .append("%\n")
                .append("Treatment applied: ").append(treatmentApplied)
                .append(" · post-search match: ")
                .append(postSearchMatched).append("/")
                .append(postSearchReviewed).append("\n");
        if (!modelLatency.isEmpty()) {
            out.append("Jev latency P50/P95: ")
                    .append(percentile(modelLatency, .50d)).append("/")
                    .append(percentile(modelLatency, .95d)).append(" ms\n");
        }
        if (!addedLatency.isEmpty()) {
            out.append("Treatment added latency P50/P95: ")
                    .append(percentile(addedLatency, .50d)).append("/")
                    .append(percentile(addedLatency, .95d)).append(" ms\n");
        }
        out.append("Control calls Jev in shadow and never changes execution; treatment may apply Jev guidance.");
        return out.toString();
    }

    private interface EventUpdate {
        void apply(JSONObject event) throws Exception;
    }

    private static void update(
            Context context,
            String eventId,
            EventUpdate update) {
        if (context == null || clean(eventId).isEmpty() || update == null) return;
        synchronized (LOCK) {
            JSONArray events = read(context);
            for (int i = events.length() - 1; i >= 0; i--) {
                JSONObject event = events.optJSONObject(i);
                if (event == null
                        || !eventId.equals(event.optString("eventId", ""))) {
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
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONArray(prefs.getString(KEY_EVENTS, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void write(Context context, JSONArray events) {
        try {
            context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_EVENTS, events.toString()).apply();
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

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("[^A-Za-z0-9_:-]", "");
        return clean.length() <= 96 ? clean : clean.substring(0, 96);
    }
}
