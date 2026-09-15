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
        check(hasRule(rules, "UI_TARGET", "UI_TARGET_NOT_FOUND", "INSPECT_UI"),
                "successful observation recovery should yield UI target rule");

        List<ReflectionRuleEvidence.Step> failedRecovery = new ArrayList<ReflectionRuleEvidence.Step>();
        failedRecovery.add(step("tap_screen", "FAILED", "UI_TARGET_NOT_FOUND", "", "TAP"));
        failedRecovery.add(step("inspect_ui", "SUCCESS", "", "", ""));
        failedRecovery.add(step("tap_screen", "FAILED", "UI_TARGET_NOT_FOUND", "", "TAP"));
        check(!hasRule(ReflectionRuleEvidence.derive(null, failedRecovery),
                        "UI_TARGET", "UI_TARGET_NOT_FOUND", "INSPECT_UI"),
                "observation followed by another failed mutation must not be learned as recovery");

        List<ReflectionRuleEvidence.Step> previous = new ArrayList<ReflectionRuleEvidence.Step>();
        previous.add(step("tap_screen", "SUCCESS", "", "route_mode:TRANSIT", "TAP"));
        List<ReflectionRuleEvidence.Step> current = new ArrayList<ReflectionRuleEvidence.Step>();
        current.add(step("tap_screen", "SUCCESS", "", "route_mode:WALKING", "TAP"));
        current.add(step("tap_screen", "SUCCESS", "", "navigation:START", "TAP"));
        List<ReflectionRuleEvidence.Candidate> navigation = ReflectionRuleEvidence.derive(previous, current);
        check(hasRule(navigation, "navigation:START", "AFTER:route_mode:*", "TAP"),
                "semantic transition should derive start-navigation rule");
        check(!hasScope(navigation, "route_mode:WALKING"),
                "same semantic family changes should not create reflection rules");

        List<ReflectionRuleEvidence.Step> interrupted = new ArrayList<ReflectionRuleEvidence.Step>();
        interrupted.add(step("tap_screen", "SUCCESS", "", "route_mode:WALKING", "TAP"));
        interrupted.add(step("tap_screen", "FAILED", "UI_TARGET_NOT_FOUND", "", "TAP"));
        interrupted.add(step("tap_screen", "SUCCESS", "", "navigation:START", "TAP"));
        check(!hasRule(ReflectionRuleEvidence.derive(null, interrupted),
                        "navigation:START", "AFTER:route_mode:*", "TAP"),
                "failed mutation must break semantic transition evidence");

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
                                   String scope,
                                   String condition,
                                   String response) {
        if (rules == null) return false;
        for (ReflectionRuleEvidence.Candidate rule : rules) {
            if (rule != null
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
