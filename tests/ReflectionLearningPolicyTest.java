package com.crewpocket.helper;

public final class ReflectionLearningPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(!ReflectionLearningPolicy.shouldReflect(2, 0, false, false, false,
                "com.android.settings"), "short successful task should not reflect");
        check(ReflectionLearningPolicy.shouldReflect(3, 0, false, false, false,
                "com.android.settings"), "3-mutation task should reflect");
        check(ReflectionLearningPolicy.shouldReflect(1, 1, false, false, false,
                "com.android.settings"), "failed task should reflect");
        check(!ReflectionLearningPolicy.shouldReflect(5, 1, false, false, true,
                "com.android.settings"), "send_text task must not reflect");
        check(!ReflectionLearningPolicy.shouldReflect(5, 1, false, true, false,
                "com.android.settings"), "cancelled task must not reflect");
        check(!ReflectionLearningPolicy.shouldReflect(5, 1, false, false, false,
                "com.example.wallet"), "sensitive package must not reflect");

        check(ReflectionLearningPolicy.isSafeCandidate(
                "open bluetooth settings",
                "After opening device settings, inspect the current screen before choosing the next navigation step.",
                0.86), "safe operational lesson should pass");
        check(!ReflectionLearningPolicy.isSafeCandidate(
                "send message",
                "Open the recipient and send the message automatically.",
                0.95), "SEND lesson must be blocked");
        check(!ReflectionLearningPolicy.isSafeCandidate(
                "open account 12345678",
                "Use the account number shown on screen.",
                0.95), "numeric sensitive lesson must be blocked");
        check(!ReflectionLearningPolicy.isSafeCandidate(
                "open settings",
                "Navigate to https://example.com for the next step.",
                0.95), "URL lesson must be blocked");

        check(ReflectionLearningPolicy.lessonsCompatible(
                "Inspect the fresh screen before retrying a failed tap.",
                "Inspect the fresh screen before retrying the failed tap."),
                "similar lessons should be compatible");
        check(!ReflectionLearningPolicy.lessonsCompatible(
                "Inspect the fresh screen before retrying a failed tap.",
                "Open the settings menu before scrolling the device list."),
                "different lessons should not be compatible");

        check("open bluetooth settings".equals(
                ReflectionLearningPolicy.normalizeGoalPattern("  Open Bluetooth Settings  ")),
                "goal pattern normalization");

        System.out.println("ReflectionLearningPolicyTest passed " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
