package com.crewpocket.helper;

public final class LocalBridgeReadinessPolicyTest {
    static void check(String expect, boolean enabled, boolean service,
                      boolean listener, String name) {
        String actual = LocalBridgeReadinessPolicy.code(enabled, service, listener);
        if (!expect.equals(actual)) throw new AssertionError(name + ": " + actual);
    }
    public static void main(String[] args) {
        check("READY", true, true, true, "normal App control");
        check("LOCAL_BRIDGE_DISABLED", false, true, false,
                "user disabled bridge must not be reenabled by Runtime");
        check("ACCESSIBILITY_SERVICE_NOT_RUNNING", true, false, false,
                "OS Accessibility permission / service missing");
        check("LOCAL_BRIDGE_NOT_LISTENING", true, true, false,
                "active service but listener lost");
        check("LOCAL_BRIDGE_DISABLED", false, false, false,
                "explicit setting takes precedence");
        System.out.println("LocalBridgeReadinessPolicyTest OK");
    }
}
