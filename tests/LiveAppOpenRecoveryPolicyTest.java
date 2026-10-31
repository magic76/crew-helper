package com.crewpocket.helper;

public final class LiveAppOpenRecoveryPolicyTest {
    public static void main(String[] args) {
        check(LiveAppOpenRecoveryPolicy.shouldRecover(
                "APP:OPEN", "打開 Google Maps", "com.apple.android.music",
                "IDLE", false, 0, false), "idle without maps call nudges once");
        check(!LiveAppOpenRecoveryPolicy.shouldRecover(
                "APP:OPEN", "打開 Google Maps", "com.apple.android.music",
                "IN_PROGRESS", false, 0, false), "thinking in progress never nudged");
        check(!LiveAppOpenRecoveryPolicy.shouldRecover(
                "APP:OPEN", "打開 Google Maps", "com.apple.android.music",
                "IDLE", false, 1, false), "existing tool prevents double operation");
        check(!LiveAppOpenRecoveryPolicy.shouldRecover(
                "APP:OPEN", "打開 Google Maps", "com.apple.android.music",
                "IDLE", false, 0, true), "only one nudge per intent");
        check(!LiveAppOpenRecoveryPolicy.shouldRecover(
                "APP:OPEN", "打開 Google Maps", "com.google.android.apps.maps",
                "IDLE", false, 0, false), "maps already open needs no action");
        check(!LiveAppOpenRecoveryPolicy.shouldRecover(
                "APP:OPEN", "打開這份文件", "com.apple.android.music",
                "IDLE", false, 0, false), "document open is not an app");
        check(!LiveAppOpenRecoveryPolicy.shouldRecover(
                "MEDIA:PLAY", "播放鄧紫棋", "com.apple.android.music",
                "IDLE", false, 0, false), "media command is not an app-open nudge");
        check(!LiveAppOpenRecoveryPolicy.shouldRecover(
                "APP:OPEN", "打開 Google Maps", "com.apple.android.music",
                "IDLE", true, 0, false), "same-frame fresh input stays untouched");
        check("com.google.android.apps.maps".equals(
                LiveAppOpenRecoveryPolicy.requestedPackage("幫我開地圖")),
                "spoken Maps open resolves to the safe known package");
        check("com.apple.android.music".equals(
                LiveAppOpenRecoveryPolicy.requestedPackage("啟動 Apple Music")),
                "spoken Apple Music open resolves to the safe known package");
        check(LiveAppOpenRecoveryPolicy.requestedPackage(
                "開地圖導航去大皇宮").isEmpty(),
                "navigation goal cannot be degraded into app launch");
        check(LiveAppOpenRecoveryPolicy.requestedPackage(
                "開 Apple Music 播放歌曲").isEmpty(),
                "media playback goal cannot be degraded into app launch");
        System.out.println("PASS LiveAppOpenRecoveryPolicyTest");
    }
    static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
