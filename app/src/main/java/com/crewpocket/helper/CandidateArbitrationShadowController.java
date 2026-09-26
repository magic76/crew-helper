package com.crewpocket.helper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase-0 only: forks qualifying locator evidence to CandidateArbitrator without
 * joining the execution path. The caller receives only an event id; model advice
 * is written to telemetry and is never returned as an executable selection.
 */
final class CandidateArbitrationShadowController {
    static final long HARD_TIMEOUT_MS = 2_000L;

    private final Context appContext;
    private final CandidateArbitrator arbitrator;
    private final ExecutorService coordinatorExecutor;
    private final ExecutorService modelExecutor;

    CandidateArbitrationShadowController(
            Context context,
            CandidateArbitrator arbitrator) {
        this.appContext =
                context == null
                        ? null
                        : context.getApplicationContext();
        this.arbitrator = arbitrator;
        this.coordinatorExecutor =
                Executors.newSingleThreadExecutor(
                        daemonThreadFactory(
                                "candidate-arbitration-shadow"));
        this.modelExecutor =
                Executors.newSingleThreadExecutor(
                        daemonThreadFactory(
                                "candidate-arbitration-model"));
    }

    String observe(
            String taskId,
            long generation,
            String taskGoal,
            String goalIntent,
            String packageName,
            JSONObject locatorDecision) {
        if (locatorDecision == null || arbitrator == null) return "";

        List<CandidateArbitrator.Candidate> candidates =
                parseCandidates(locatorDecision.optJSONArray("candidates"));
        double best = candidates.isEmpty()
                ? locatorDecision.optDouble("confidence", 0.0d)
                : candidates.get(0).confidence;
        double runner = candidates.size() < 2
                ? locatorDecision.optDouble(
                        "runnerUpConfidence", 0.0d)
                : candidates.get(1).confidence;
        boolean bestExact =
                !candidates.isEmpty()
                        && candidates.get(0).exactViewId;
        boolean runnerExact =
                candidates.size() > 1
                        && candidates.get(1).exactViewId;

        CandidateArbitrationPolicy.TriggerType trigger =
                CandidateArbitrationPolicy.qualify(
                        locatorDecision.optString("decision", ""),
                        candidates.size(),
                        best,
                        runner,
                        bestExact,
                        runnerExact);
        if (trigger == CandidateArbitrationPolicy.TriggerType.NONE) {
            return "";
        }

        final String eventId =
                CandidateArbitrationTelemetryStore.recordStart(
                        appContext,
                        taskId,
                        generation,
                        packageName,
                        goalIntent,
                        trigger,
                        candidates);
        final CandidateArbitrator.Request request =
                new CandidateArbitrator.Request(
                        taskGoal,
                        goalIntent,
                        generation,
                        packageName,
                        candidates);

        coordinatorExecutor.execute(new Runnable() {
            @Override public void run() {
                long startedAt = System.currentTimeMillis();
                Future<CandidateArbitrator.Advice> future =
                        modelExecutor.submit(
                                new Callable<CandidateArbitrator.Advice>() {
                                    @Override
                                    public CandidateArbitrator.Advice call()
                                            throws Exception {
                                        return arbitrator.arbitrate(request);
                                    }
                                });
                CandidateArbitrator.Advice advice;
                boolean timeout = false;
                String override = "";
                try {
                    advice = future.get(
                            HARD_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS);
                } catch (TimeoutException error) {
                    timeout = true;
                    override = "TIMEOUT";
                    future.cancel(true);
                    arbitrator.cancelActiveRequest();
                    advice =
                            CandidateArbitrator.Advice.abstain(
                                    "TIMEOUT");
                } catch (Exception error) {
                    future.cancel(true);
                    arbitrator.cancelActiveRequest();
                    override = "MODEL_ERROR";
                    advice =
                            CandidateArbitrator.Advice.abstain(
                                    "MODEL_ERROR");
                }
                CandidateArbitrationTelemetryStore.recordAdvice(
                        appContext,
                        eventId,
                        advice,
                        System.currentTimeMillis() - startedAt,
                        timeout,
                        override);
            }
        });

        // The actual Runtime path continues immediately with its existing
        // locator/fallback result. There is intentionally no Future.get here.
        return eventId;
    }

    void recordBaselineCandidate(
            String eventId,
            String candidateId) {
        CandidateArbitrationTelemetryStore.recordBaselineCandidate(
                appContext,
                eventId,
                candidateId);
    }

    void recordOutcome(
            String eventId,
            JSONObject result) {
        if (result == null) return;
        boolean interaction =
                CandidateArbitrationOutcomePolicy
                        .interactionVerified(
                                result.optBoolean(
                                        "success", false),
                                result.optString(
                                        "stepResult", ""),
                                result.optString(
                                        "verificationStatus", ""));
        boolean semantic =
                CandidateArbitrationOutcomePolicy
                        .semanticEffectVerified(
                                result.optBoolean(
                                        "verified", false),
                                result.optString(
                                        "verificationCode", ""),
                                result.optString(
                                        "completionEvidence", ""));
        CandidateArbitrationTelemetryStore.recordOutcome(
                appContext,
                eventId,
                interaction,
                semantic);
    }

    void recordCorrection(
            long generation,
            long nowMs,
            long maxAgeMs) {
        CandidateArbitrationTelemetryStore
                .recordCorrectionForRecent(
                        appContext,
                        generation,
                        nowMs,
                        maxAgeMs);
    }

    private static List<CandidateArbitrator.Candidate>
            parseCandidates(JSONArray raw) {
        ArrayList<CandidateArbitrator.Candidate> out =
                new ArrayList<CandidateArbitrator.Candidate>();
        if (raw == null) return out;
        for (int i = 0;
             i < raw.length()
                    && out.size()
                        < CandidateArbitrator.MAX_CANDIDATES;
             i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String candidateId =
                    item.optString("elementId", "").trim();
            if (candidateId.isEmpty()) continue;
            JSONObject bounds = item.optJSONObject("bounds");
            out.add(new CandidateArbitrator.Candidate(
                    candidateId,
                    item.optString("label", ""),
                    item.optString("role", ""),
                    item.optString("viewId", ""),
                    item.optString("semanticHint", ""),
                    item.optDouble("confidence", 0.0d),
                    item.optBoolean("exactViewId", false),
                    bounds == null ? 0 : bounds.optInt("left", 0),
                    bounds == null ? 0 : bounds.optInt("top", 0),
                    bounds == null ? 0 : bounds.optInt("right", 0),
                    bounds == null ? 0 : bounds.optInt("bottom", 0)));
        }
        return out;
    }

    private static ThreadFactory daemonThreadFactory(
            final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger sequence =
                    new AtomicInteger();
            @Override public Thread newThread(Runnable runnable) {
                Thread thread =
                        new Thread(
                                runnable,
                                prefix
                                        + "-"
                                        + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
