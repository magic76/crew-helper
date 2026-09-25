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

        System.out.println(
                "ToolIntentRoutingPolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
