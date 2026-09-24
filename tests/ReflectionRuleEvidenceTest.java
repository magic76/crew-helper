package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.List;

public final class ReflectionRuleEvidenceTest {
    private static int checks;

    public static void main(String[] args) {
        List<ReflectionRuleEvidence.Step> recovery = new ArrayList<ReflectionRuleEvidence.Step>();
        recovery.add(step("tap_screen", "FAILED", "UI_TARGET_NOT_FOUND", "", "TAP"));
        recovery.add(step("inspect_ui", "SUCCESS", "", "", ""));
        recovery.add(step("tap_screen", "SUCCESS", "", "navigation:DIRECTIONS", "TAP"));
        List<ReflectionRuleEvidence.Candidate> rules = ReflectionRuleEvidence.derive(null, recovery);
        check(rules.size() == 1, "clean recovery sequence should yield one evidence rule");
        check(hasRule(rules, ReflectionRuleEvidence.KIND_FRICTION,
                        "UI_TARGET", "UI_TARGET_NOT_FOUND", "INSPECT_UI"),
                "successful observation recovery should be immediate recovery evidence");

        List<ReflectionRuleEvidence.Step> failedRecovery = new ArrayList<ReflectionRuleEvidence.Step>();
        failedRecovery.add(step("tap_screen", "FAILED", "UI_TARGET_NOT_FOUND", "", "TAP"));
        failedRecovery.add(step("inspect_ui", "SUCCESS", "", "", ""));
        failedRecovery.add(step("tap_screen", "FAILED", "UI_TARGET_NOT_FOUND", "", "TAP"));
        check(!hasRule(ReflectionRuleEvidence.derive(null, failedRecovery),
                        ReflectionRuleEvidence.KIND_FRICTION,
                        "UI_TARGET", "UI_TARGET_NOT_FOUND", "INSPECT_UI"),
                "observation followed by another failed mutation must not be learned as recovery");

        List<ReflectionRuleEvidence.Step> directRetry = new ArrayList<ReflectionRuleEvidence.Step>();
        directRetry.add(step("tap_screen", "FAILED", "UI_TARGET_NOT_FOUND", "", "TAP"));
        directRetry.add(step("tap_screen", "SUCCESS", "", "navigation:START", "TAP"));
        List<ReflectionRuleEvidence.Candidate> directRules =
                ReflectionRuleEvidence.derive(null, directRetry);
        check(hasRule(directRules, ReflectionRuleEvidence.KIND_FRICTION,
                        "navigation:START", "UI_TARGET_NOT_FOUND", "TAP"),
                "direct successful retry should be recovery evidence");

        List<ReflectionRuleEvidence.Step> genericRetry = new ArrayList<ReflectionRuleEvidence.Step>();
        genericRetry.add(step("tap_screen", "FAILED", "", "", "TAP"));
        genericRetry.add(step("tap_screen", "SUCCESS", "", "navigation:START", "TAP"));
        check(hasRule(ReflectionRuleEvidence.derive(null, genericRetry),
                        ReflectionRuleEvidence.KIND_FRICTION,
                        "navigation:START", "PREVIOUS_ATTEMPT_FAILED", "TAP"),
                "retry without a failure code should still have bounded recovery evidence");

        List<ReflectionRuleEvidence.Step> previousFailure = new ArrayList<ReflectionRuleEvidence.Step>();
        previousFailure.add(step("launch_app", "FAILED", "APP_NOT_FOUND", "", "OPEN_APP"));
        List<ReflectionRuleEvidence.Step> recoveredGoal = new ArrayList<ReflectionRuleEvidence.Step>();
        recoveredGoal.add(step("launch_app", "SUCCESS", "", "app:MAPS", "OPEN_APP"));
        check(hasRule(ReflectionRuleEvidence.derive(previousFailure, recoveredGoal),
                        ReflectionRuleEvidence.KIND_FRICTION,
                        "app:MAPS", "APP_NOT_FOUND", "OPEN_APP"),
                "same-goal next-task recovery should be immediate recovery evidence");

        List<ReflectionRuleEvidence.Step> previousAlreadyRecovered = new ArrayList<ReflectionRuleEvidence.Step>();
        previousAlreadyRecovered.add(step("tap_screen", "FAILED", "UI_TARGET_NOT_FOUND", "", "TAP"));
        previousAlreadyRecovered.add(step("tap_screen", "SUCCESS", "", "navigation:START", "TAP"));
        List<ReflectionRuleEvidence.Step> laterTask = new ArrayList<ReflectionRuleEvidence.Step>();
        laterTask.add(step("tap_screen", "SUCCESS", "", "navigation:STOP", "TAP"));
        check(!hasRule(ReflectionRuleEvidence.derive(previousAlreadyRecovered, laterTask),
                        ReflectionRuleEvidence.KIND_FRICTION,
                        "navigation:START", "UI_TARGET_NOT_FOUND", "TAP"),
                "a recovery completed in the previous task must not be counted again");

        List<ReflectionRuleEvidence.Step> unrelatedRecovery = new ArrayList<ReflectionRuleEvidence.Step>();
        unrelatedRecovery.add(step("tap_screen", "FAILED", "UI_TARGET_NOT_FOUND", "", "TAP"));
        unrelatedRecovery.add(step("launch_app", "SUCCESS", "", "app:MAPS", "OPEN_APP"));
        check(!hasRule(ReflectionRuleEvidence.derive(null, unrelatedRecovery),
                        ReflectionRuleEvidence.KIND_FRICTION,
                        "app:MAPS", "UI_TARGET_NOT_FOUND", "OPEN_APP"),
                "unrelated successful mutation must not be treated as retry evidence");

        List<ReflectionRuleEvidence.Step> runtimeGuard =
                new ArrayList<ReflectionRuleEvidence.Step>();
        runtimeGuard.add(step(
                "tap_screen", "FAILED",
                "OBSERVE_REQUIRED_AFTER_FAILURE", "", "TAP"));
        runtimeGuard.add(step("inspect_ui", "SUCCESS", "", "", ""));
        runtimeGuard.add(step(
                "tap_screen", "SUCCESS", "", "navigation:START", "TAP"));
        check(ReflectionRuleEvidence.derive(null, runtimeGuard).isEmpty(),
                "Runtime observe/stability guards must never become Experience friction");

        List<ReflectionRuleEvidence.Step> staleGuard =
                new ArrayList<ReflectionRuleEvidence.Step>();
        staleGuard.add(step(
                "tap_screen", "FAILED",
                "STALE_ACTION_REJECTED", "", "TAP"));
        staleGuard.add(step(
                "tap_screen", "SUCCESS", "", "navigation:START", "TAP"));
        check(ReflectionRuleEvidence.derive(null, staleGuard).isEmpty(),
                "stale Runtime guard failures must not become retry evidence");

        check(ReflectionRuleEvidence.isRuntimeInternalFailure(
                        "TASK_ALREADY_FINISHED"),
                "finished-task Runtime guard should be internal");
        check(!ReflectionRuleEvidence.isRuntimeInternalFailure(
                        "UI_TARGET_NOT_FOUND"),
                "real UI target failure must remain learnable friction");

        List<ReflectionRuleEvidence.Step> plainSuccess =
                new ArrayList<ReflectionRuleEvidence.Step>();
        plainSuccess.add(step(
                "tap_screen", "SUCCESS", "", "route_mode:WALKING", "TAP"));
        plainSuccess.add(step(
                "tap_screen", "SUCCESS", "", "navigation:START", "TAP"));
        check(ReflectionRuleEvidence.derive(null, plainSuccess).isEmpty(),
                "plain successful transitions must never create Experience evidence");

        List<ReflectionRuleEvidence.Candidate> strongFriction =
                ReflectionRuleEvidence.derive(null, recovery);
        check(!strongFriction.isEmpty()
                        && strongFriction.get(0).frictionScore >= 5,
                "inspect-based recovery should carry strong friction score");

        List<ReflectionRuleEvidence.Candidate> mediumFriction =
                ReflectionRuleEvidence.derive(null, directRetry);
        check(!mediumFriction.isEmpty()
                        && mediumFriction.get(0).frictionScore == 4,
                "direct retry should carry medium friction score");

        List<ReflectionRuleEvidence.Step> userRetry =
                new ArrayList<ReflectionRuleEvidence.Step>();
        userRetry.add(step(
                "tap_screen", "SUCCESS", "", "", "TAP"));
        ReflectionRuleEvidence.Candidate implicitRetry =
                ReflectionRuleEvidence.implicitUserRetry(
                        "CONTROL:PAUSE",
                        userRetry);
        check(implicitRetry != null
                        && ReflectionRuleEvidence.KIND_FRICTION.equals(implicitRetry.kind)
                        && "CONTROL:PAUSE".equals(implicitRetry.scope)
                        && UserRetryAfterUnconfirmedOutcomePolicy.CONDITION.equals(
                                implicitRetry.condition)
                        && "VERIFY_GOAL_OUTCOME".equals(implicitRetry.response)
                        && implicitRetry.frictionScore == 4,
                "human retry after unconfirmed success should be medium friction");

        check("route_mode:*".equals(
                ReflectionRuleEvidence.semanticFamily("route_mode:WALKING")),
                "semantic family should normalize concept values");

        System.out.println("ReflectionRuleEvidenceTest passed " + checks + " checks");
    }

    private static ReflectionRuleEvidence.Step step(String tool,
                                                    String outcome,
                                                    String failure,
                                                    String semanticTarget,
                                                    String semanticAction) {
        return new ReflectionRuleEvidence.Step(
                tool, outcome, failure, semanticTarget, semanticAction);
    }

    private static boolean hasRule(List<ReflectionRuleEvidence.Candidate> rules,
                                   String kind,
                                   String scope,
                                   String condition,
                                   String response) {
        if (rules == null) return false;
        for (ReflectionRuleEvidence.Candidate rule : rules) {
            if (rule != null
                    && kind.equals(rule.kind)
                    && scope.equals(rule.scope)
                    && condition.equals(rule.condition)
                    && response.equals(rule.response)) return true;
        }
        return false;
    }

    private static boolean hasScope(List<ReflectionRuleEvidence.Candidate> rules,
                                    String scope) {
        if (rules == null) return false;
        for (ReflectionRuleEvidence.Candidate rule : rules) {
            if (rule != null && scope.equals(rule.scope)) return true;
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
