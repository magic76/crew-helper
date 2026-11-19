package com.crewpocket.helper;

public final class HelperCoachTaskOutcomePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        verified("MEDIA:PLAY", "DONE", "MEDIA_PLAYBACK_BECAME_ACTIVE",
                "MEDIA_PLAYBACK_BECAME_ACTIVE");
        verified("MEDIA:PLAY", "DONE", "MEDIA_UI_PLAYING",
                "MEDIA_PLAYBACK_BECAME_ACTIVE");
        verified("NAVIGATION:START", "DONE", "MAPS_NAVIGATION_ACTIVE_VERIFIED",
                "MAPS_NAVIGATION_ACTIVE_VERIFIED");
        verified("APP:OPEN", "DONE", "APP_OPEN_FOREGROUND_VERIFIED",
                "APP_PACKAGE_TRANSITION_OBSERVED");

        unverified("MEDIA:PLAY", "DONE", "RUNTIME_VERIFIED", true,
                "generic Runtime verification cannot verify media playback");
        unverified("NAVIGATION:START", "DONE", "NAVIGATION_TRANSITION_OBSERVED", true,
                "a navigation screen transition is not active guidance");
        unverified("APP:OPEN", "DONE", "RUNTIME_VERIFIED", true,
                "generic Runtime verification cannot verify target app open");
        unverified("MEDIA:PAUSE", "DONE", "RUNTIME_VERIFIED", true,
                "pause stays unverified without paused-state evidence");
        unverified("MEDIA:NEXT", "DONE", "TAP_EFFECT_OBSERVED", true,
                "next track stays unverified without item-change evidence");
        unverified("MEDIA:PREVIOUS", "DONE", "TAP_EFFECT_OBSERVED", true,
                "previous track stays unverified without item-change evidence");
        unverified("SEARCH:RESULT", "DONE", "SEARCH_RESULTS_OBSERVED", true,
                "search surface change does not establish matching results");
        unverified("TEXT:TYPE_ONLY", "DONE", "RUNTIME_VERIFIED", true,
                "typing stays unverified without a private local value match");
        unverified("MESSAGE:SEND", "DONE", "TAP_EFFECT_OBSERVED", true,
                "send stays unverified without confirmed sent-state evidence");
        unverified("", "DONE", "RUNTIME_VERIFIED", true,
                "unclassified goal remains unverified");
        unverified("MEDIA:PLAY", "IN_PROGRESS", "MEDIA_PLAYBACK_BECAME_ACTIVE", true,
                "nonterminal task state remains unverified");
        unverified("MEDIA:PLAY", "DONE", "MEDIA_PLAYBACK_BECAME_ACTIVE", false,
                "cancelled task cannot be verified");

        System.out.println("HelperCoachTaskOutcomePolicyTest passed "
                + checks + " checks");
    }

    private static void verified(
            String goal, String state, String evidence, String expectedMethod) {
        HelperCoachTaskOutcomePolicy.Outcome outcome =
                HelperCoachTaskOutcomePolicy.evaluate(goal, state, evidence, true);
        check(outcome.verified, goal + " should verify for its exact evidence");
        check(expectedMethod.equals(outcome.verificationMethod),
                goal + " should use its contract method");
        check(expectedMethod.equals(outcome.evidenceRef),
                goal + " should expose only the contract evidence ref");
    }

    private static void unverified(
            String goal,
            String state,
            String evidence,
            boolean completed,
            String description) {
        HelperCoachTaskOutcomePolicy.Outcome outcome =
                HelperCoachTaskOutcomePolicy.evaluate(
                        goal, state, evidence, completed);
        check(!outcome.verified, description);
        check("RUNTIME_UNVERIFIED".equals(outcome.verificationMethod),
                description + " uses the unverified method");
        check("TASK_NOT_VERIFIED".equals(outcome.evidenceRef),
                description + " has no success evidence ref");
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
