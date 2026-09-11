package com.crewpocket.helper;

/**
 * Privacy-safe semantic observation used by the runtime verifier.
 *
 * Do not store visible text or user-entered text here.  The verifier only needs
 * structural evidence that the UI changed, not the contents of the user's data.
 */
final class ActionObservation {
    final boolean available;
    final String packageName;
    final String fingerprint;
    final String stableScreenKey;
    final String focusedElementKey;
    final String focusedRole;
    final int elementCount;
    final long capturedAtMs;

    ActionObservation(boolean available,
                      String packageName,
                      String fingerprint,
                      String stableScreenKey,
                      String focusedElementKey,
                      String focusedRole,
                      int elementCount,
                      long capturedAtMs) {
        this.available = available;
        this.packageName = safe(packageName);
        this.fingerprint = safe(fingerprint);
        this.stableScreenKey = safe(stableScreenKey);
        this.focusedElementKey = safe(focusedElementKey);
        this.focusedRole = safe(focusedRole);
        this.elementCount = Math.max(0, elementCount);
        this.capturedAtMs = capturedAtMs <= 0L ? System.currentTimeMillis() : capturedAtMs;
    }

    static ActionObservation unavailable() {
        return new ActionObservation(false, "", "", "", "", "", 0,
                System.currentTimeMillis());
    }

    static ActionObservation of(String packageName,
                                String fingerprint,
                                String stableScreenKey,
                                String focusedElementKey,
                                String focusedRole,
                                int elementCount) {
        boolean available = !safe(packageName).isEmpty()
                || !safe(fingerprint).isEmpty()
                || !safe(stableScreenKey).isEmpty();
        return new ActionObservation(available, packageName, fingerprint, stableScreenKey,
                focusedElementKey, focusedRole, elementCount, System.currentTimeMillis());
    }

    boolean fingerprintChangedFrom(ActionObservation before) {
        return before != null
                && before.available
                && available
                && !before.fingerprint.isEmpty()
                && !fingerprint.isEmpty()
                && !before.fingerprint.equals(fingerprint);
    }

    boolean stableScreenChangedFrom(ActionObservation before) {
        return before != null
                && before.available
                && available
                && !before.stableScreenKey.isEmpty()
                && !stableScreenKey.isEmpty()
                && !before.stableScreenKey.equals(stableScreenKey);
    }

    boolean packageChangedFrom(ActionObservation before) {
        return before != null
                && before.available
                && available
                && !before.packageName.isEmpty()
                && !packageName.isEmpty()
                && !before.packageName.equals(packageName);
    }

    boolean focusChangedFrom(ActionObservation before) {
        return before != null
                && before.available
                && available
                && (!focusedElementKey.isEmpty() || !before.focusedElementKey.isEmpty())
                && !focusedElementKey.equals(before.focusedElementKey);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

