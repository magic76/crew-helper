package com.crewpocket.helper;

/** State check only. No Android permission bypass or silent foreground fallback. */
final class LocalBridgeReadinessPolicy {
    private LocalBridgeReadinessPolicy() {}

    static String code(boolean enabled, boolean accessibilityRunning,
                       boolean listenerReady) {
        if (!enabled) return "LOCAL_BRIDGE_DISABLED";
        if (!accessibilityRunning) return "ACCESSIBILITY_SERVICE_NOT_RUNNING";
        return listenerReady ? "READY" : "LOCAL_BRIDGE_NOT_LISTENING";
    }
}
