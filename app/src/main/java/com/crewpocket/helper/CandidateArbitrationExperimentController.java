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
 * Candidate Arbitration experiment coordinator.
 *
 * CONTROL remains Phase-0-style shadow: baseline never waits for the advisor.
 * TREATMENT waits at most HARD_TIMEOUT_MS for bounded advice, but execution is
 * still owned by PhoneRuntimeExecutor + deterministic Runtime revalidation.
 */
final class CandidateArbitrationExperimentController {
    static final long HARD_TIMEOUT_MS = 2_000L;

    static final class Decision {
        final String eventId;
        final CandidateArbitrationPolicy.Bucket bucket;
        final long generation;
        final String packageName;
        final CandidateArbitrator.Candidate selectedCandidate;
        final boolean advisorDecisive;
        final boolean timeout;
        final long advisorLatencyMs;

        Decision(
                String eventId,
                CandidateArbitrationPolicy.Bucket bucket,
                long generation,
                String packageName,
                CandidateArbitrator.Candidate selectedCandidate,
                boolean advisorDecisive,
                boolean timeout,
                long advisorLatencyMs) {
            this.eventId = eventId == null ? "" : eventId;
            this.bucket = bucket == null
                    ? CandidateArbitrationPolicy.Bucket.CONTROL
                    : bucket;
            this.generation = generation;
            this.packageName = packageName == null ? "" : packageName;
            this.selectedCandidate = selectedCandidate;
            this.advisorDecisive = advisorDecisive;
            this.timeout = timeout;
            this.advisorLatencyMs = Math.max(0L, advisorLatencyMs);
        }

        static Decision none() {
            return new Decision(
                    "",
                    CandidateArbitrationPolicy.Bucket.CONTROL,
                    -1L,
                    "",
                    null,
                    false,
                    false,
                    0L);
        }

        boolean treatment() {
            return bucket == CandidateArbitrationPolicy.Bucket.TREATMENT;
        }

        boolean hasSelectedCandidate() {
            return treatment()
                    && advisorDecisive
                    && selectedCandidate != null
                    && !selectedCandidate.candidateId.isEmpty();
        }
    }

    private static final class BoundedAdvice {
        final CandidateArbitrator.Advice advice;
        final long latencyMs;
        final boolean timeout;
        final String reasonOverride;

        BoundedAdvice(
                CandidateArbitrator.Advice advice,
                long latencyMs,
                boolean timeout,
                String reasonOverride) {
            this.advice = advice == null
                    ? CandidateArbitrator.Advice.abstain("MODEL_ERROR")
                    : advice;
            this.latencyMs = Math.max(0L, latencyMs);
            this.timeout = timeout;
            this.reasonOverride =
                    reasonOverride == null ? "" : reasonOverride;
        }
    }

    private final Context appContext;
    private final CandidateArbitrator controlArbitrator;
    private final CandidateArbitrator treatmentArbitrator;
    private final ExecutorService controlCoordinator;
    private final ExecutorService controlModelExecutor;
    private final ExecutorService treatmentModelExecutor;

    CandidateArbitrationExperimentController(
            Context context,
            CandidateArbitrator controlArbitrator,
            CandidateArbitrator treatmentArbitrator) {
        this.appContext =
                context == null
                        ? null
                        : context.getApplicationContext();
        this.controlArbitrator = controlArbitrator;
        this.treatmentArbitrator = treatmentArbitrator;
        this.controlCoordinator =
                Executors.newCachedThreadPool(
                        daemonThreadFactory(
                                "candidate-arbitration-control"));
        this.controlModelExecutor =
                Executors.newSingleThreadExecutor(
                        daemonThreadFactory(
                                "candidate-arbitration-control-model"));
        this.treatmentModelExecutor =
                Executors.newSingleThreadExecutor(
                        daemonThreadFactory(
                                "candidate-arbitration-treatment-model"));
    }

    Decision evaluate(
            String taskId,
            long generation,
            String taskGoal,
            String goalIntent,
            String packageName,
            JSONObject locatorDecision) {
        if (locatorDecision == null
                || controlArbitrator == null
                || treatmentArbitrator == null) {
            return Decision.none();
        }

        final List<CandidateArbitrator.Candidate> candidates =
                parseCandidates(
                        locatorDecision.optJSONArray(
                                "_shadowCandidates"));
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
            return Decision.none();
        }

        final String observedPackage =
                locatorDecision.optString(
                        "_shadowPackage",
                        packageName == null ? "" : packageName);
        final CandidateArbitrationPolicy.Bucket bucket =
                CandidateArbitrationPolicy.deterministicBucket(
                        taskId,
                        generation,
                        observedPackage,
                        goalIntent);
        final String eventId =
                CandidateArbitrationTelemetryStore.recordStart(
                        appContext,
                        taskId,
                        generation,
                        observedPackage,
                        goalIntent,
                        trigger,
                        bucket,
                        candidates);
        final CandidateArbitrator.Request request =
                new CandidateArbitrator.Request(
                        taskGoal,
                        goalIntent,
                        generation,
                        observedPackage,
                        candidates);

        if (bucket == CandidateArbitrationPolicy.Bucket.CONTROL) {
            controlCoordinator.execute(new Runnable() {
                @Override public void run() {
                    BoundedAdvice bounded =
                            runBounded(
                                    controlArbitrator,
                                    controlModelExecutor,
                                    request);
                    CandidateArbitrationTelemetryStore.recordAdvice(
                            appContext,
                            eventId,
                            bounded.advice,
                            bounded.latencyMs,
                            bounded.timeout,
                            bounded.reasonOverride,
                            false);
                }
            });
            return new Decision(
                    eventId,
                    bucket,
                    generation,
                    observedPackage,
                    null,
                    false,
                    false,
                    0L);
        }

        BoundedAdvice bounded =
                runBounded(
                        treatmentArbitrator,
                        treatmentModelExecutor,
                        request);
        CandidateArbitrationTelemetryStore.recordAdvice(
                appContext,
                eventId,
                bounded.advice,
                bounded.latencyMs,
                bounded.timeout,
                bounded.reasonOverride,
                true);

        CandidateArbitrator.Candidate selected =
                bounded.advice.abstain
                        ? null
                        : findCandidate(
                                candidates,
                                bounded.advice.selectedCandidateId);
        return new Decision(
                eventId,
                bucket,
                generation,
                observedPackage,
                selected,
                !bounded.advice.abstain && selected != null,
                bounded.timeout,
                bounded.latencyMs);
    }

    void recordBaselineCandidate(
            String eventId,
            String candidateId) {
        CandidateArbitrationTelemetryStore.recordBaselineCandidate(
                appContext,
                eventId,
                candidateId);
    }

    void recordTreatmentRevalidation(
            String eventId,
            CandidateArbitrationExecutionPolicy.Verdict verdict,
            String executedCandidateId,
            boolean executed) {
        CandidateArbitrationTelemetryStore.recordTreatmentExecution(
                appContext,
                eventId,
                verdict == null ? "UNKNOWN" : verdict.name(),
                executedCandidateId,
                executed);
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

    private static BoundedAdvice runBounded(
            CandidateArbitrator arbitrator,
            ExecutorService modelExecutor,
            final CandidateArbitrator.Request request) {
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
        try {
            CandidateArbitrator.Advice advice =
                    future.get(
                            HARD_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS);
            return new BoundedAdvice(
                    advice,
                    System.currentTimeMillis() - startedAt,
                    false,
                    "");
        } catch (TimeoutException error) {
            future.cancel(true);
            arbitrator.cancelActiveRequest();
            return new BoundedAdvice(
                    CandidateArbitrator.Advice.abstain(
                            "TIMEOUT"),
                    System.currentTimeMillis() - startedAt,
                    true,
                    "TIMEOUT");
        } catch (Exception error) {
            future.cancel(true);
            arbitrator.cancelActiveRequest();
            return new BoundedAdvice(
                    CandidateArbitrator.Advice.abstain(
                            "MODEL_ERROR"),
                    System.currentTimeMillis() - startedAt,
                    false,
                    "MODEL_ERROR");
        }
    }

    private static CandidateArbitrator.Candidate findCandidate(
            List<CandidateArbitrator.Candidate> candidates,
            String selectedId) {
        String id = selectedId == null ? "" : selectedId.trim();
        if (id.isEmpty() || candidates == null) return null;
        for (CandidateArbitrator.Candidate candidate : candidates) {
            if (candidate != null
                    && id.equals(candidate.candidateId)) {
                return candidate;
            }
        }
        return null;
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
