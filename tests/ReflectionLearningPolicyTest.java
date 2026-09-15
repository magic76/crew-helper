package com.crewpocket.helper;

public final class ReflectionLearningPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(!ReflectionLearningPolicy.shouldReflect(2, 0, false, false, false, false,
                "com.android.settings"), "short successful task without evidence should not reflect");
        check(ReflectionLearningPolicy.shouldReflect(3, 0, false, false, false, false,
                "com.android.settings"), "3-mutation task should reflect");
        check(ReflectionLearningPolicy.shouldReflect(1, 1, false, false, false, false,
                "com.android.settings"), "failed task should reflect");
        check(ReflectionLearningPolicy.shouldReflect(2, 0, true, false, false, false,
                "com.android.settings"), "deterministic rule evidence should trigger reflection");
        check(!ReflectionLearningPolicy.shouldReflect(5, 1, true, false, false, true,
                "com.android.settings"), "send_text task must not reflect");
        check(!ReflectionLearningPolicy.shouldReflect(5, 1, true, false, true, false,
                "com.android.settings"), "cancelled task must not reflect");
        check(!ReflectionLearningPolicy.shouldReflect(5, 1, true, false, false, false,
                "com.example.wallet"), "sensitive package must not reflect");

        check(ReflectionLearningPolicy.isSafeRuleLesson(
                "Inspect the current screen before retrying an unresolved UI target.",
                0.86), "safe operational lesson should pass");
        check(!ReflectionLearningPolicy.isSafeRuleLesson(
                "Open the recipient and send the message automatically.",
                0.95), "SEND lesson must be blocked");
        check(!ReflectionLearningPolicy.isSafeRuleLesson(
                "Use account 12345678 shown on screen.",
                0.95), "numeric sensitive lesson must be blocked");
        check(!ReflectionLearningPolicy.isSafeRuleLesson(
                "Navigate to https://example.com for the next step.",
                0.95), "URL lesson must be blocked");
        check(!ReflectionLearningPolicy.isSafeRuleLesson(
                "Inspect the current screen before retrying.",
                0.69), "low-confidence lesson must be blocked");

        check(ReflectionLearningPolicy.lessonsCompatible(
                "Inspect the fresh screen before retrying a failed tap.",
                "Inspect the fresh screen before retrying the failed tap."),
                "similar lessons should be compatible");
        check(!ReflectionLearningPolicy.lessonsCompatible(
                "Inspect the fresh screen before retrying a failed tap.",
                "Open the settings menu before scrolling the device list."),
                "different lessons should not be compatible");

        System.out.println("ReflectionLearningPolicyTest passed " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
