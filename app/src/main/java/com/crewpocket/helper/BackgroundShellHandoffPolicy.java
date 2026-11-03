package com.crewpocket.helper;

/** Pure handoff gate, executable in JVM unit tests without Android. */
final class BackgroundShellHandoffPolicy {
    private BackgroundShellHandoffPolicy() {}

    static boolean check(int actualUid, int actualPid, int claimedUid, int claimedPid,
                         String currentApk, String shellApk, String expectedNonce,
                         String providedNonce, long nowMs, long issuedAtMs, long ttlMs) {
        if (actualUid != 2000 || actualUid != claimedUid
                || actualPid <= 0 || actualPid != claimedPid)
            return false;
        if (currentApk == null || !currentApk.startsWith("/data/app/")
                || !currentApk.endsWith("/base.apk")
                || !currentApk.equals(shellApk))
            return false;
        if (expectedNonce == null || expectedNonce.length() < 30
                || providedNonce == null || !expectedNonce.equals(providedNonce))
            return false;
        long age = nowMs - issuedAtMs;
        return issuedAtMs > 0L && age >= 0L && age <= ttlMs;
    }
}
