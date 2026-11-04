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
        check("LAUNCH_ACCESSIBILITY_NOT_RUNNING".equals(
                LaunchFailureCodePolicy.transportFailure(
                        new java.io.IOException("ACCESSIBILITY_SERVICE_NOT_RUNNING"))),
                "readiness explains missing Accessibility rather than App target");
        check("LAUNCH_BRIDGE_DISABLED".equals(
                LaunchFailureCodePolicy.transportFailure(
                        new java.io.IOException("LOCAL_BRIDGE_DISABLED"))),
                "respects explicit bridge disable");
        check("LAUNCH_BRIDGE_NOT_LISTENING".equals(
                LaunchFailureCodePolicy.transportFailure(
                        new java.io.IOException("LOCAL_BRIDGE_NOT_LISTENING"))),
                "distinguishes server died from Android app launch failure");
        check("LAUNCH_BRIDGE_UNREACHABLE".equals(
                LaunchFailureCodePolicy.transportFailure(
                        new java.net.ConnectException("sensitive endpoint info"))),
                "loopback bridge connection failed");
        check("LAUNCH_BRIDGE_TIMEOUT".equals(
                LaunchFailureCodePolicy.transportFailure(
                        new java.net.SocketTimeoutException("secret"))),
                "bridge timed out");
        check("LAUNCH_BRIDGE_IO_FAILURE".equals(
                LaunchFailureCodePolicy.transportFailure(
                        new java.io.IOException("don't disclose this message"))),
                "generic IO error sanitized");
        System.out.println("LaunchFailureCodePolicyTest OK");
    }
}
