package com.crewpocket.helper;

public final class ScreenActionCapabilityPolicyTest {
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        check(ScreenActionCapabilityPolicy.requiresAccessibilityRequest(
                "phone_action", "OPEN_APP"), "semantic App launch requires service");
        check(ScreenActionCapabilityPolicy.requiresAccessibilityRequest(
                "phone_action", "TAP"), "semantic tap requires service");
        check(ScreenActionCapabilityPolicy.requiresAccessibilityRequest(
                "phone_action", "TAKE_PHOTO"), "camera uses current bridge");
        check(ScreenActionCapabilityPolicy.requiresAccessibilityRequest(
                "launch_app", ""), "legacy foreground launch also gated");
        check(ScreenActionCapabilityPolicy.requiresAccessibilityTool(
                "inspect_ui"), "structural inspect gated");
        check(ScreenActionCapabilityPolicy.requiresAccessibilityTool(
                "send_text"), "phone text send gated");
        check(!ScreenActionCapabilityPolicy.requiresAccessibilityRequest(
                "phone_action", "UNRECOGNIZED"), "semantic schema errors preserved");
        check(!ScreenActionCapabilityPolicy.requiresAccessibilityTool(
                "inspect_runtime_trace"), "diagnostics remain available");
        check(!ScreenActionCapabilityPolicy.requiresAccessibilityTool(
                "end_voice_session"), "call can always end");
        check(!ScreenActionCapabilityPolicy.requiresAccessibilityTool(
                "create_note"), "notes remain usable without screen actions");
        check(!ScreenActionCapabilityPolicy.requiresAccessibilityTool(
                "list_app_capabilities"), "local guidance can be read");
        check(ScreenActionCapabilityPolicy.UNAVAILABLE.equals(
                ScreenActionCapabilityPolicy.launchTransportError(
                        false, new java.net.ConnectException("sensitive endpoint"))),
                "disabled service is a capability error");
        check("LAUNCH_BRIDGE_UNREACHABLE".equals(
                ScreenActionCapabilityPolicy.launchTransportError(
                        true, new java.net.ConnectException("sensitive endpoint"))),
                "enabled service preserves real bridge failures");
        System.out.println("ScreenActionCapabilityPolicyTest OK");
    }
}
