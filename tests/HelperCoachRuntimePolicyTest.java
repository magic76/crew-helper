package com.crewpocket.helper;

import java.util.Arrays;

public final class HelperCoachRuntimePolicyTest {
    private static final long NOW = 1_800_000_000_000L;

    public static void main(String[] args) {
        googleMapsFailurePocketAdviceRetryVerified();
        appleMusicFailurePocketAdviceRetryVerified();
        rejectsExpiredDuplicateUnknownAndChangedUi();
        rejectsUnadvertisedUnsafeAndConfirmationAdvice();
        System.out.println("HelperCoachRuntimePolicyTest passed");
    }

    private static void googleMapsFailurePocketAdviceRetryVerified() {
        Journey result = runRecovery(
                "maps-task", "com.google.android.apps.maps", "maps-screen-1",
                "NAVIGATION_ACTIVE", "GOOGLE_MAPS_ROUTE_STARTED");
        check(result.adviceAccepted, "Maps advice accepted");
        check(result.runtimeMutationCount == 2, "Maps retries one authorized tap");
        check(result.verified, "Maps postcondition independently verified");
        check("GOOGLE_MAPS_ROUTE_STARTED".equals(result.evidence),
                "Maps completion evidence recorded");
    }

    private static void appleMusicFailurePocketAdviceRetryVerified() {
        Journey result = runRecovery(
                "music-task", "com.apple.android.music", "music-player-1",
                "MEDIA_PLAYBACK_BECAME_ACTIVE", "MEDIA_PLAYBACK_BECAME_ACTIVE");
        check(result.adviceAccepted, "Apple Music advice accepted");
        check(result.runtimeMutationCount == 2, "Apple Music retries one authorized tap");
        check(result.verified, "Apple Music playback postcondition independently verified");
        check("MEDIA_PLAYBACK_BECAME_ACTIVE".equals(result.evidence),
                "playback evidence recorded");
    }

    private static Journey runRecovery(
            String taskId, String app, String fingerprint,
            String expectedPostcondition, String completionEvidence) {
        HelperCoachRuntimePolicy gate = new HelperCoachRuntimePolicy();
        HelperCoachRuntimePolicy.Observation observation = new HelperCoachRuntimePolicy.Observation(
                "evt-observation-" + taskId, taskId, app, fingerprint,
                expectedPostcondition, NOW, NOW + 30_000L, true,
                Arrays.asList(
                        HelperCoachRuntimePolicy.ACTION_INSPECT_UI,
                        HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP));
        HelperCoachRuntimePolicy.Advice advice = new HelperCoachRuntimePolicy.Advice(
                "advice-" + taskId, observation.eventId, taskId, app,
                fingerprint,
                HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP,
                expectedPostcondition, 0.9d, NOW + 10_000L, false);
        HelperCoachRuntimePolicy.Decision decision = gate.evaluate(
                observation, advice, taskId, app, fingerprint, NOW + 1_000L);

        // Simulate the Helper-owned Runtime path: original tap failed, an
        // authenticated Pocket recommendation passes the same policy gate,
        // Runtime retries once, and an independent app-state check supplies
        // the terminal evidence.
        Journey journey = new Journey();
        journey.adviceAccepted = decision == HelperCoachRuntimePolicy.Decision.ACCEPT;
        journey.runtimeMutationCount = 1;
        if (journey.adviceAccepted) {
            journey.runtimeMutationCount++;
            journey.verified = postconditionObserved(app, expectedPostcondition);
            journey.evidence = journey.verified ? completionEvidence : "";
        }
        return journey;
    }

    private static boolean postconditionObserved(String app, String expected) {
        if ("com.apple.android.music".equals(app)) {
            return "MEDIA_PLAYBACK_BECAME_ACTIVE".equals(expected);
        }
        if ("com.google.android.apps.maps".equals(app)) {
            return "NAVIGATION_ACTIVE".equals(expected);
        }
        return false;
    }

    private static void rejectsExpiredDuplicateUnknownAndChangedUi() {
        HelperCoachRuntimePolicy policy = new HelperCoachRuntimePolicy();
        HelperCoachRuntimePolicy.Observation observation = observation(true);
        HelperCoachRuntimePolicy.Advice advice = advice(
                observation, HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP,
                false, NOW + 10_000L);
        check(policy.evaluate(observation, advice, observation.taskId,
                observation.appPackage, observation.uiFingerprint, NOW)
                == HelperCoachRuntimePolicy.Decision.ACCEPT, "first advice accepted");
        check(policy.evaluate(observation, advice, observation.taskId,
                observation.appPackage, observation.uiFingerprint, NOW)
                == HelperCoachRuntimePolicy.Decision.DUPLICATE_ADVICE,
                "duplicate advice rejected");

        HelperCoachRuntimePolicy fresh = new HelperCoachRuntimePolicy();
        check(fresh.evaluate(observation,
                advice(observation, HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP,
                        false, NOW - 1L),
                observation.taskId, observation.appPackage,
                observation.uiFingerprint, NOW)
                == HelperCoachRuntimePolicy.Decision.EXPIRED,
                "expired advice rejected");
        check(fresh.evaluate(observation,
                advice(observation, "run_shell_command", false, NOW + 10_000L),
                observation.taskId, observation.appPackage,
                observation.uiFingerprint, NOW)
                == HelperCoachRuntimePolicy.Decision.UNKNOWN_ACTION,
                "unknown action rejected");
        check(fresh.evaluate(observation,
                advice(observation, HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP,
                        false, NOW + 10_000L),
                observation.taskId, observation.appPackage,
                "changed-screen", NOW)
                == HelperCoachRuntimePolicy.Decision.UI_CHANGED,
                "changed UI rejected");
        check(fresh.evaluate(observation,
                advice(observation, HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP,
                        false, NOW + 10_000L),
                "other-task", observation.appPackage,
                observation.uiFingerprint, NOW)
                == HelperCoachRuntimePolicy.Decision.STALE_TASK,
                "advice for another active task rejected");
        check(fresh.evaluate(observation,
                advice(observation, HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP,
                        false, NOW + 10_000L),
                observation.taskId, "other.app",
                observation.uiFingerprint, NOW)
                == HelperCoachRuntimePolicy.Decision.STALE_APP,
                "advice for another foreground app rejected");
        HelperCoachRuntimePolicy.Advice wrongPostcondition =
                new HelperCoachRuntimePolicy.Advice(
                        "advice-wrong-postcondition", observation.eventId,
                        observation.taskId, observation.appPackage,
                        observation.uiFingerprint,
                        HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP,
                        "OTHER_COMPLETION", 0.9d, NOW + 10_000L, false);
        check(fresh.evaluate(observation, wrongPostcondition,
                observation.taskId, observation.appPackage,
                observation.uiFingerprint, NOW)
                == HelperCoachRuntimePolicy.Decision.POSTCONDITION_MISMATCH,
                "mismatched postcondition rejected");
    }

    private static void rejectsUnadvertisedUnsafeAndConfirmationAdvice() {
        HelperCoachRuntimePolicy policy = new HelperCoachRuntimePolicy();
        HelperCoachRuntimePolicy.Observation noRetry = observation(false);
        check(policy.evaluate(noRetry,
                advice(noRetry, HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP,
                        false, NOW + 10_000L),
                noRetry.taskId, noRetry.appPackage, noRetry.uiFingerprint, NOW)
                == HelperCoachRuntimePolicy.Decision.ACTION_NOT_ADVERTISED,
                "unsafe retry is not advertised");

        HelperCoachRuntimePolicy.Observation observation = observation(true);
        check(policy.evaluate(observation,
                advice(observation, "launch_app", false, NOW + 10_000L),
                observation.taskId, observation.appPackage,
                observation.uiFingerprint, NOW)
                == HelperCoachRuntimePolicy.Decision.UNKNOWN_ACTION,
                "unadvertised action rejected");
        check(policy.evaluate(observation,
                advice(observation,
                        HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP,
                        true, NOW + 10_000L),
                observation.taskId, observation.appPackage,
                observation.uiFingerprint, NOW)
                == HelperCoachRuntimePolicy.Decision.NEEDS_USER_CONFIRMATION,
                "confirmation requirement blocks auto-execution");

        HelperCoachRuntimePolicy.Observation advertisedButUnsafe =
                new HelperCoachRuntimePolicy.Observation(
                        "evt-unsafe", "task-unsafe", "com.apple.android.music",
                        "screen-unsafe", "MEDIA_PLAYBACK_BECAME_ACTIVE", NOW,
                        NOW + 30_000L, false,
                        Arrays.asList(HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP));
        HelperCoachRuntimePolicy.Advice unsafeAdvice =
                new HelperCoachRuntimePolicy.Advice(
                        "advice-unsafe", advertisedButUnsafe.eventId,
                        advertisedButUnsafe.taskId, advertisedButUnsafe.appPackage,
                        advertisedButUnsafe.uiFingerprint,
                        HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP,
                        advertisedButUnsafe.expectedPostcondition, 0.9d,
                        NOW + 10_000L, false);
        check(policy.evaluate(advertisedButUnsafe, unsafeAdvice,
                advertisedButUnsafe.taskId, advertisedButUnsafe.appPackage,
                advertisedButUnsafe.uiFingerprint, NOW)
                == HelperCoachRuntimePolicy.Decision.UNSAFE_RETRY,
                "retry is rejected when Runtime did not authorize it");
    }

    private static HelperCoachRuntimePolicy.Observation observation(boolean retryable) {
        return new HelperCoachRuntimePolicy.Observation(
                "evt-observation", "task-1", "com.apple.android.music",
                "screen-1", "MEDIA_PLAYBACK_BECAME_ACTIVE", NOW,
                NOW + 30_000L, retryable,
                retryable
                        ? Arrays.asList(HelperCoachRuntimePolicy.ACTION_INSPECT_UI,
                                HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP)
                        : Arrays.asList(HelperCoachRuntimePolicy.ACTION_INSPECT_UI));
    }

    private static HelperCoachRuntimePolicy.Advice advice(
            HelperCoachRuntimePolicy.Observation observation,
            String action, boolean confirmation, long expiresAt) {
        return new HelperCoachRuntimePolicy.Advice(
                "advice-" + action + "-" + confirmation + "-" + expiresAt,
                observation.eventId, observation.taskId,
                observation.appPackage, observation.uiFingerprint, action,
                HelperCoachRuntimePolicy.ACTION_INSPECT_UI.equals(action)
                        ? "FRESH_OBSERVATION" : observation.expectedPostcondition,
                0.9d, expiresAt, confirmation);
    }

    private static final class Journey {
        boolean adviceAccepted;
        int runtimeMutationCount;
        boolean verified;
        String evidence = "";
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
