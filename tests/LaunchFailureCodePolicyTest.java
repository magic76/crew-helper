package com.crewpocket.helper;

public final class LaunchFailureCodePolicyTest {
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        check("LAUNCH_NOT_VISIBLE".equals(
                LaunchFailureCodePolicy.classify("LAUNCH_NOT_VISIBLE",
                        "LAUNCH_TARGET_NOT_VISIBLE", true)),
                "verification failure beats launch submitted success message");
        check("LAUNCH_SECURITY_DENIED".equals(
                LaunchFailureCodePolicy.classify("LAUNCH_SECURITY_DENIED", "", false)),
                "Android denied launch");
        check("APP_NOT_FOUND".equals(
                LaunchFailureCodePolicy.classify("APP_NOT_FOUND", "", false)),
                "unknown installed app");
        check("LAUNCH_SUBMITTED_UNVERIFIED".equals(
                LaunchFailureCodePolicy.classify("", "", true)),
                "submitted yet no confirmation");
        check("LAUNCH_FAILURE_UNCLASSIFIED".equals(
                LaunchFailureCodePolicy.classify("private user text; secret=123", "", false)),
                "arbitrary string never copied into Inspector");
        check("LAUNCH_NOT_VISIBLE".equals(
                LaunchFailureCodePolicy.classify("oops", "LAUNCH_TARGET_NOT_VISIBLE", false)),
                "known verification code takes priority");
        System.out.println("LaunchFailureCodePolicyTest OK");
    }
}
