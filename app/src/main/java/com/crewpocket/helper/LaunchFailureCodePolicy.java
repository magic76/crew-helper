package com.crewpocket.helper;

/**
 * Privacy-safe error classification for foreground App launch diagnostics.
 * Never copy raw backend/model messages to the persistent Inspector.
 */
final class LaunchFailureCodePolicy {
    private LaunchFailureCodePolicy() {}

    static String transportFailure(java.io.IOException error) {
        String reason = error == null ? "" : error.getMessage();
        // These values are authored only by Runtime's local readiness check,
        // never copied from an arbitrary exception or user input.
        if ("LOCAL_BRIDGE_DISABLED".equals(reason))
            return "LAUNCH_BRIDGE_DISABLED";
        if ("ACCESSIBILITY_SERVICE_NOT_RUNNING".equals(reason))
            return "LAUNCH_ACCESSIBILITY_NOT_RUNNING";
        if ("LOCAL_BRIDGE_NOT_LISTENING".equals(reason))
            return "LAUNCH_BRIDGE_NOT_LISTENING";
        if (error instanceof java.net.ConnectException)
            return "LAUNCH_BRIDGE_UNREACHABLE";
        if (error instanceof java.net.SocketTimeoutException)
            return "LAUNCH_BRIDGE_TIMEOUT";
        return "LAUNCH_BRIDGE_IO_FAILURE";
    }

    static String classify(String rawError, String verification,
                           boolean launchSubmitted) {
        String code = rawError == null ? "" : rawError.trim();
        switch (code) {
            case "APP_NAME_REQUIRED":
            case "APP_NOT_FOUND":
            case "LAUNCH_INTENT_NOT_FOUND":
            case "LAUNCH_SECURITY_DENIED":
            case "LAUNCH_TIMED_OUT":
            case "LAUNCH_MAIN_HANDLER_UNAVAILABLE":
            case "LAUNCH_RUNTIME_EXCEPTION":
            case "LAUNCH_NOT_VISIBLE":
            case "LAUNCH_BRIDGE_DISABLED":
            case "LAUNCH_ACCESSIBILITY_NOT_RUNNING":
            case "LAUNCH_BRIDGE_NOT_LISTENING":
            case "LAUNCH_BRIDGE_UNREACHABLE":
            case "LAUNCH_BRIDGE_TIMEOUT":
            case "LAUNCH_BRIDGE_IO_FAILURE":
            case "INVALID_APP_TARGET":
                return code;
            default:
                break;
        }
        if ("LAUNCH_TARGET_NOT_VISIBLE".equals(verification))
            return "LAUNCH_NOT_VISIBLE";
        return launchSubmitted
                ? "LAUNCH_SUBMITTED_UNVERIFIED"
                : "LAUNCH_FAILURE_UNCLASSIFIED";
    }
}
