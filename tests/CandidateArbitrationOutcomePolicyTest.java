package com.crewpocket.helper;

public final class CandidateArbitrationOutcomePolicyTest {
    public static void main(String[] args) {
        expectTrue(
                CandidateArbitrationOutcomePolicy
                        .interactionVerified(
                                true,
                                "STEP_PENDING",
                                "PENDING"),
                "accepted tap can be technically verified while semantics are pending");

        expectFalse(
                CandidateArbitrationOutcomePolicy
                        .semanticEffectVerified(
                                false,
                                "TAP_EFFECT_OBSERVED",
                                "AUTO_AFTER_ACTION"),
                "generic screen change must not count as semantic effect");

        expectTrue(
                CandidateArbitrationOutcomePolicy
                        .semanticEffectVerified(
                                false,
                                "RUNTIME_VERIFIED",
                                ""),
                "domain/runtime verification counts as semantic effect");

        expectTrue(
                CandidateArbitrationOutcomePolicy
                        .semanticEffectVerified(
                                true,
                                "TAP_EFFECT_OBSERVED",
                                "MAPS_NAVIGATION_ACTIVE_VERIFIED"),
                "explicit domain verification counts");

        expectFalse(
                CandidateArbitrationOutcomePolicy
                        .interactionVerified(
                                false,
                                "STEP_FAILED",
                                "FAILED"),
                "failed action is not interaction verified");

        System.out.println(
                "CandidateArbitrationOutcomePolicyTest passed");
    }

    private static void expectTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectFalse(boolean value, String message) {
        if (value) throw new AssertionError(message);
    }
}
