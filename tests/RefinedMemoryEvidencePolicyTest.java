package com.crewpocket.helper;

public final class RefinedMemoryEvidencePolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(
                RefinedMemoryEvidencePolicy.normalTaskVerdict(
                        "MEDIA:PLAY",
                        "DONE",
                        "MEDIA_PLAY_CONTROL_VERIFIED",
                        true,
                        false)
                        == RefinedMemoryEvidencePolicy.Verdict.NONE,
                "verified play tap without playback is not learning evidence");

        check(
                RefinedMemoryEvidencePolicy.normalTaskVerdict(
                        "MEDIA:PLAY",
                        "DONE",
                        "MEDIA_PLAYBACK_BECAME_ACTIVE",
                        true,
                        true)
                        == RefinedMemoryEvidencePolicy.Verdict.INDEPENDENT_SUCCESS,
                "actual playback is independent evidence");

        int independentVotes = 0;
        int supportingVotes = 0;
        for (int i = 0; i < 6; i++) {
            RefinedMemoryEvidencePolicy.Verdict verdict =
                    RefinedMemoryEvidencePolicy.recipeReplayVerdict(
                            true,
                            "MEDIA:PLAY",
                            "DONE",
                            "MEDIA_PLAYBACK_BECAME_ACTIVE",
                            true,
                            true);
            if (verdict
                    == RefinedMemoryEvidencePolicy.Verdict
                            .INDEPENDENT_SUCCESS) {
                independentVotes++;
            }
            if (verdict
                    == RefinedMemoryEvidencePolicy.Verdict
                            .SUPPORTING_SUCCESS) {
                supportingVotes++;
            }
        }
        check(independentVotes == 0,
                "six recipe replays cannot self-promote");
        check(supportingVotes == 6,
                "verified recipe replays remain supporting evidence");

        check(
                RefinedMemoryEvidencePolicy.recipeReplayVerdict(
                        false,
                        "NAVIGATION:START",
                        "",
                        "",
                        false,
                        false)
                        == RefinedMemoryEvidencePolicy.Verdict.NEGATIVE,
                "failed replay is negative evidence");

        check(
                RefinedMemoryEvidencePolicy.normalTaskVerdict(
                        "NAVIGATION:START",
                        "DONE",
                        "MAPS_START_NAVIGATION_SCREEN_CHANGED",
                        true,
                        false)
                        == RefinedMemoryEvidencePolicy.Verdict.NONE,
                "screen change after Start is not navigation learning evidence");
        check(
                RefinedMemoryEvidencePolicy.normalTaskVerdict(
                        "NAVIGATION:START",
                        "DONE",
                        "MAPS_NAVIGATION_ACTIVE_VERIFIED",
                        true,
                        false)
                        == RefinedMemoryEvidencePolicy.Verdict.INDEPENDENT_SUCCESS,
                "active navigation is strong learning evidence");
        check(
                RefinedMemoryEvidencePolicy.normalTaskVerdict(
                        "SEARCH:RESULT",
                        "ANSWER_READY",
                        "SEARCH_RESULT_SCREEN_INSPECTED",
                        true,
                        false)
                        == RefinedMemoryEvidencePolicy.Verdict.NONE,
                "search learning waits for query-result affinity");


        check(
                RefinedMemoryEvidencePolicy.looksLikeReliableCorrection(
                        "不是這首，我是說鄧紫棋",
                        0.95d),
                "explicit correction is recognized");
        check(
                !RefinedMemoryEvidencePolicy.looksLikeReliableCorrection(
                        "不是這首",
                        0.51d),
                "low-confidence ASR does not poison memory");
        check(
                RefinedMemoryEvidencePolicy.looksLikeReliableCorrection(
                        "not that, I meant the other one",
                        -1.0d),
                "unknown-confidence typed-like correction is accepted");
        check(
                !RefinedMemoryEvidencePolicy.looksLikeReliableCorrection(
                        "取消",
                        0.99d),
                "generic cancellation is not negative memory evidence");

        System.out.println(
                "RefinedMemoryEvidencePolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
