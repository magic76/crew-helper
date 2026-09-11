package com.crewpocket.helper;

/** Maps Runtime-owned tool names to the kind of evidence expected after mutation. */
final class ActionExpectation {
    private ActionExpectation() {}

    static ActionTransaction.ExpectedEffect forRuntimeAction(String runtimeName) {
        String name = runtimeName == null ? "" : runtimeName.trim();
        if ("launch_app".equals(name)) return ActionTransaction.ExpectedEffect.APP_CHANGE;
        if ("type_text".equals(name)) return ActionTransaction.ExpectedEffect.TEXT_CHANGE;
        if ("search_current_app".equals(name) || "search_commit".equals(name)) {
            return ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE;
        }
        if ("swipe_screen".equals(name)) return ActionTransaction.ExpectedEffect.SCROLL_CHANGE;
        if ("tap_screen".equals(name) || "tap_element".equals(name)) {
            return ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE;
        }
        if ("press_key".equals(name)) return ActionTransaction.ExpectedEffect.SCREEN_CHANGE;
        if ("send_text".equals(name)) return ActionTransaction.ExpectedEffect.DOMAIN_VERIFIED;
        return ActionTransaction.ExpectedEffect.UNKNOWN;
    }
}

