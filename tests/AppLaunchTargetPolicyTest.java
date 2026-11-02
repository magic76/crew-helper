package com.crewpocket.helper;

public final class AppLaunchTargetPolicyTest {
    private static void eq(String expected, String actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": " + actual);
    }
    private static void ok(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        eq("com.google.android.apps.maps",
                AppLaunchTargetPolicy.knownPackage("Google Maps"), "maps");
        eq("com.google.android.apps.maps",
                AppLaunchTargetPolicy.knownPackage("地圖"), "localized maps");
        eq("com.google.android.apps.maps",
                AppLaunchTargetPolicy.knownPackage("Google Maps。"), "maps punctuation");
        eq("com.google.android.apps.maps",
                AppLaunchTargetPolicy.knownPackage("com.google.android.apps.maps"), "package");
        eq("com.apple.android.music",
                AppLaunchTargetPolicy.knownPackage("Apple Music"), "music");
        eq("com.android.chrome",
                AppLaunchTargetPolicy.knownPackage("Chrome"), "chrome");
        eq("", AppLaunchTargetPolicy.knownPackage("Google"), "ambiguous google");
        eq("", AppLaunchTargetPolicy.knownPackage("打開 Google Maps"), "sentence not an app");
        eq("", AppLaunchTargetPolicy.knownPackage("Maps and then pay"), "compound");
        eq("", AppLaunchTargetPolicy.knownPackage(""), "empty");
        ok(AppLaunchTargetPolicy.observedOnExpectedPackage("com.android.chrome",
                "com.android.chrome"), "matching package verification");
        ok(!AppLaunchTargetPolicy.observedOnExpectedPackage("com.android.chrome",
                "com.google.android.apps.maps"), "unrelated UI change not verification");
        ok(!AppLaunchTargetPolicy.observedOnExpectedPackage("com.android.chrome",
                ""), "no evidence not verification");
        ok(AppLaunchTargetPolicy.failureInstruction("APP_NOT_FOUND").contains("已安裝"),
                "not found instruction");
        ok(AppLaunchTargetPolicy.failureInstruction("LAUNCH_SECURITY_DENIED").contains("Android"),
                "denied instruction");
        System.out.println("PASS AppLaunchTargetPolicyTest");
    }
}
