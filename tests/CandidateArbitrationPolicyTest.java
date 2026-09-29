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
                "strong competing AMBIGUOUS candidates qualify");

        expect(
                CandidateArbitrationPolicy.TriggerType.NONE,
                CandidateArbitrationPolicy.qualify(
                        "AMBIGUOUS",
                        1,
                        0.94,
                        0.0,
                        false,
                        false),
                "single-candidate ambiguity must not call the advisor");

        expect(
                CandidateArbitrationPolicy.TriggerType.NONE,
                CandidateArbitrationPolicy.qualify(
                        "AMBIGUOUS",
                        2,
                        0.64,
                        0.62,
                        false,
                        false),
                "low-signal ambiguity stays deterministic instead of paying model latency");

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

        check(
                CandidateArbitrationPolicy.hasUsefulSemanticSeparation(
                        "Play", "button", "play_button", "media play",
                        "Pause", "button", "pause_button", "media pause"),
                "different semantic candidates are worth bounded arbitration");
        check(
                !CandidateArbitrationPolicy.hasUsefulSemanticSeparation(
                        "Play", "button", "play_button", "media play",
                        "Play", "button", "play_button", "media play"),
                "semantically identical candidates must not call the advisor");
        check(
                !CandidateArbitrationPolicy.hasUsefulSemanticSeparation(
                        "", "button", "", "",
                        "", "button", "", ""),
                "role-only candidates lack enough semantic evidence for arbitration");

        CandidateArbitrationPolicy.Bucket first =
                CandidateArbitrationPolicy.deterministicBucket(
                        "task-123",
                        8L,
                        "pkg",
                        "MEDIA:PLAY");
        CandidateArbitrationPolicy.Bucket repeated =
                CandidateArbitrationPolicy.deterministicBucket(
                        "task-123",
                        999L,
                        "different.pkg",
                        "NAVIGATION:START");
        if (first != repeated) {
            throw new AssertionError(
                    "task-level bucket must remain stable");
        }

        if (CandidateArbitrationPolicy.phase0MayOverrideBaseline()) {
            throw new AssertionError(
                    "merged Phase 0 regression guard must remain false");
        }

        System.out.println("CandidateArbitrationPolicyTest passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
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
