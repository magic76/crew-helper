package com.crewpocket.helper;

public final class JevSpeechExperimentPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        JevSpeechExperimentPolicy.Bucket first =
                JevSpeechExperimentPolicy.bucket(
                        42L, "com.google.android.apps.maps");
        check(first == JevSpeechExperimentPolicy.bucket(
                        42L, "com.google.android.apps.maps"),
                "bucket must be deterministic");

        int control = 0;
        int treatment = 0;
        for (long generation = 1L; generation <= 100L; generation++) {
            JevSpeechExperimentPolicy.Bucket bucket =
                    JevSpeechExperimentPolicy.bucket(
                            generation,
                            "com.google.android.apps.maps");
            if (bucket == JevSpeechExperimentPolicy.Bucket.CONTROL) {
                control++;
                check(!JevSpeechExperimentPolicy
                                .appliesToUserPath(bucket),
                        "control must remain shadow-only");
            } else {
                treatment++;
                check(JevSpeechExperimentPolicy
                                .appliesToUserPath(bucket),
                        "treatment may affect user path");
            }
        }

        check(control >= 40 && control <= 60,
                "control allocation should be roughly balanced");
        check(treatment >= 40 && treatment <= 60,
                "treatment allocation should be roughly balanced");

        System.out.println(
                "PASS JevSpeechExperimentPolicyTest: "
                        + checks + " checks control="
                        + control + " treatment=" + treatment);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
