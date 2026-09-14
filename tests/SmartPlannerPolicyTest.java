package com.crewpocket.helper;

public final class SmartPlannerPolicyTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        check(SmartPlannerPolicy.isAllowedAction("OPEN_APP"), "open app allowed");
        check(SmartPlannerPolicy.isAllowedAction("TAP"), "semantic tap allowed");
        check(SmartPlannerPolicy.isAllowedAction("TYPE"), "type allowed");
        check(SmartPlannerPolicy.isAllowedAction("SEARCH"), "search allowed");
        check(SmartPlannerPolicy.isAllowedAction("COMMIT_SEARCH"), "search commit allowed");
        check(SmartPlannerPolicy.isAllowedAction("SCROLL"), "scroll allowed");
        check(SmartPlannerPolicy.isAllowedAction("BACK"), "back allowed");
        check(SmartPlannerPolicy.isAllowedAction("HOME"), "home allowed");

        check(!SmartPlannerPolicy.isAllowedAction("SEND"), "send not allowed");
        check(SmartPlannerPolicy.isNeverAllowed("SEND_TEXT"), "send explicitly blocked");
        check(SmartPlannerPolicy.isNeverAllowed("PAY_NOW"), "payment explicitly blocked");
        check(SmartPlannerPolicy.isNeverAllowed("ENTER_OTP"), "otp explicitly blocked");
        check(SmartPlannerPolicy.isNeverAllowed("PASSWORD"), "password explicitly blocked");
        check(SmartPlannerPolicy.isNeverAllowed("RAW_TAP_COORDINATE"), "raw coordinates blocked");

        check(SmartPlannerPolicy.canContinue(0, 0, 0), "fresh task allowed");
        check(!SmartPlannerPolicy.canContinue(SmartPlannerPolicy.MAX_PLANNER_CALLS, 0, 0),
                "planner budget bounded");
        check(!SmartPlannerPolicy.canContinue(0, SmartPlannerPolicy.MAX_MUTATIONS, 0),
                "mutation budget bounded");
        check(!SmartPlannerPolicy.canContinue(0, 0, SmartPlannerPolicy.MAX_TASK_MS),
                "time budget bounded");

        check("forward".equals(SmartPlannerPolicy.normalizedDirection("FORWARD")),
                "direction normalized");
        check("".equals(SmartPlannerPolicy.normalizedDirection("up")),
                "physical finger direction rejected");

        System.out.println("PASS SmartPlannerPolicyTest: " + assertions + " checks");
    }
}
