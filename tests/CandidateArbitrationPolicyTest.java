package com.crewpocket.helper;

public final class CandidateArbitrationPolicyTest {
    public static void main(String[] args) {
        expect(
                CandidateArbitrationPolicy.TriggerType.AMBIGUOUS,
                CandidateArbitrationPolicy.qualify(
                        "AMBIGUOUS",
                        2,
                        0.94,
                        0.90,
                        false,
                        false),
                "AMBIGUOUS always qualifies");

        expect(
                CandidateArbitrationPolicy.TriggerType.NONE,
                CandidateArbitrationPolicy.qualify(
                        "FALLBACK",
                        1,
                        0.58,
                        0.0,
                        false,
                        false),
                "single low-confidence fallback must not escalate");

        expect(
                CandidateArbitrationPolicy.TriggerType.FALLBACK,
                CandidateArbitrationPolicy.qualify(
                        "FALLBACK",
                        2,
                        0.70,
                        0.66,
                        false,
                        false),
                "fallback may qualify only with the shared ambiguity predicate");

        expect(
                CandidateArbitrationPolicy.TriggerType.NONE,
                CandidateArbitrationPolicy.qualify(
                        "FALLBACK",
                        2,
                        0.70,
                        0.66,
                        true,
                        false),
                "unique exact view-id keeps the locator special case");

        expect(
                CandidateArbitrationPolicy.TriggerType.NONE,
                CandidateArbitrationPolicy.qualify(
                        "AUTO",
                        2,
                        0.94,
                        0.90,
                        false,
                        false),
                "AUTO never enters the experiment");

        if (CandidateArbitrationPolicy.phase0MayOverrideBaseline()) {
            throw new AssertionError(
                    "Phase 0 must never override baseline execution");
        }

        System.out.println("CandidateArbitrationPolicyTest passed");
    }

    private static void expect(
            CandidateArbitrationPolicy.TriggerType expected,
            CandidateArbitrationPolicy.TriggerType actual,
            String message) {
        if (expected != actual) {
            throw new AssertionError(
                    message
                            + ": expected="
                            + expected
                            + " actual="
                            + actual);
        }
    }
}
