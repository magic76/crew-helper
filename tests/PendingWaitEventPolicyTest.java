package com.crewpocket.helper;

public final class PendingWaitEventPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(PendingWaitEventPolicy.matchesAppOpenedNotification(
                        PendingActionPolicy.CONDITION_APP_OPENED,
                        PendingActionPolicy.ACTION_NOTIFY,
                        "com.google.android.apps.maps",
                        "com.google.android.apps.maps"),
                "matching app-open notification should trigger");

        check(!PendingWaitEventPolicy.matchesAppOpenedNotification(
                        PendingActionPolicy.CONDITION_APP_OPENED,
                        PendingActionPolicy.ACTION_NOTIFY,
                        "com.google.android.apps.maps",
                        "com.android.systemui"),
                "unrelated package must not trigger");

        check(PendingWaitEventPolicy.canInspectCurrentPackage(
                        PendingActionPolicy.CONDITION_APP_OPENED,
                        PendingActionPolicy.ACTION_NOTIFY,
                        "com.crewpocket.helper",
                        "com.google.android.apps.maps"),
                "notification-only app-open wait may observe another app");

        check(!PendingWaitEventPolicy.canInspectCurrentPackage(
                        PendingActionPolicy.CONDITION_TEXT_APPEARS,
                        PendingActionPolicy.ACTION_TAP,
                        "com.example.origin",
                        "com.example.other"),
                "mutating pending action must stay in origin app");

        check(PendingWaitEventPolicy.canInspectCurrentPackage(
                        PendingActionPolicy.CONDITION_TEXT_APPEARS,
                        PendingActionPolicy.ACTION_TAP,
                        "com.example.origin",
                        "com.example.origin"),
                "same-app pending action should remain allowed");

        System.out.println("PendingWaitEventPolicyTest passed " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
