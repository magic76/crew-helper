package com.crewpocket.helper;

public final class ToolIntentRoutingPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(ToolIntentRoutingPolicy.shouldRemapTypeToSearch(
                        "type_text", true, "SEARCH:RESULT", true),
                "explicit search remaps TYPE to SEARCH");
        check(ToolIntentRoutingPolicy.shouldRemapTypeToSearch(
                        "type_text", false, "MEDIA:PLAY", true),
                "media play goal remaps TYPE to SEARCH");
        check(!ToolIntentRoutingPolicy.shouldRemapTypeToSearch(
                        "type_text", false, "TEXT:TYPE_ONLY", true),
                "type-only goal stays TYPE");
        check(!ToolIntentRoutingPolicy.shouldRemapTypeToSearch(
                        "type_text", false, "APP:OPEN", true),
                "ordinary app flow stays TYPE");
        check(!ToolIntentRoutingPolicy.shouldRemapTypeToSearch(
                        "tap_screen", true, "SEARCH:RESULT", true),
                "non-TYPE tool is untouched");
        check(!ToolIntentRoutingPolicy.shouldRemapTypeToSearch(
                        "type_text", true, "SEARCH:RESULT", false),
                "empty TYPE payload is not remapped");

        check(ToolIntentRoutingPolicy.requiresOpenAppAction(
                        "APP:OPEN", "phone_action", "TAP"),
                "new Maps open goal blocks a stale media TAP");
        check(ToolIntentRoutingPolicy.requiresOpenAppAction(
                        "APP:OPEN", "phone_action", "SEARCH"),
                "app open must not search the previous foreground app");
        check(!ToolIntentRoutingPolicy.requiresOpenAppAction(
                        "APP:OPEN", "phone_action", "OPEN_APP"),
                "app open accepts correct semantic action");
        check(!ToolIntentRoutingPolicy.requiresOpenAppAction(
                        "APP:OPEN", "inspect_ui", ""),
                "observation remains available for app open");
        check(!ToolIntentRoutingPolicy.requiresOpenAppAction(
                        "MEDIA:PLAY", "phone_action", "TAP"),
                "media playback TAP remains valid for media goals");

        System.out.println(
                "ToolIntentRoutingPolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
