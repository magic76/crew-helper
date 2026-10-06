package com.crewpocket.helper;

public final class RuntimeOwnedSearchVerificationPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(RuntimeOwnedSearchVerificationPolicy.shouldVerify(
                        "search_current_app",
                        "IN_PROGRESS",
                        "INSPECT_UI",
                        "PENDING",
                        "PENDING_RESULTS"),
                "pending search must be verified by Runtime");

        check(RuntimeOwnedSearchVerificationPolicy.shouldVerify(
                        "commit_search",
                        "IN_PROGRESS",
                        "INSPECT_UI",
                        "PENDING",
                        "PENDING_RESULTS"),
                "pending explicit commit must be verified by Runtime");

        check(!RuntimeOwnedSearchVerificationPolicy.shouldVerify(
                        "tap_screen",
                        "IN_PROGRESS",
                        "INSPECT_UI",
                        "PENDING",
                        ""),
                "unrelated pending mutation stays outside search verifier");

        check(!RuntimeOwnedSearchVerificationPolicy.shouldVerify(
                        "search_current_app",
                        "DONE",
                        "NONE",
                        "VERIFIED",
                        "RESULTS_OBSERVED"),
                "verified search does not enter Runtime-owned wait");

        check(RuntimeOwnedSearchVerificationPolicy.shouldOpenElementFallback(
                        "com.apple.android.music",
                        "MEDIA:PLAY",
                        false),
                "unresolved Apple Music playback may use element fallback");

        check(!RuntimeOwnedSearchVerificationPolicy.shouldOpenElementFallback(
                        "com.apple.android.music",
                        "MEDIA:PLAY",
                        true),
                "observed results never open element fallback");

        check(!RuntimeOwnedSearchVerificationPolicy.shouldOpenElementFallback(
                        "com.example.bank",
                        "MEDIA:PLAY",
                        false),
                "untrusted app cannot use media element fallback");

        System.out.println(
                "PASS RuntimeOwnedSearchVerificationPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
