package com.crewpocket.helper;

/** Pure event/scope rules for background wait tasks. */
final class PendingWaitEventPolicy {
    private PendingWaitEventPolicy() {}

    static boolean matchesAppOpenedNotification(
            String conditionType,
            String action,
            String expectedPackage,
            String eventPackage) {
        return PendingActionPolicy.CONDITION_APP_OPENED.equals(conditionType)
                && PendingActionPolicy.ACTION_NOTIFY.equals(action)
                && nonEmpty(expectedPackage)
                && expectedPackage.equals(clean(eventPackage));
    }

    static boolean canInspectCurrentPackage(
            String conditionType,
            String action,
            String originPackage,
            String currentPackage) {
        if (PendingActionPolicy.CONDITION_APP_OPENED.equals(conditionType)
                && PendingActionPolicy.ACTION_NOTIFY.equals(action)) {
            return true;
        }
        return nonEmpty(originPackage)
                && originPackage.equals(clean(currentPackage));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean nonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
