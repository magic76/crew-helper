package com.crewpocket.helper;

/** Maps Runtime-owned tool names to the evidence expected after mutation. */
final class ActionExpectation {
    private ActionExpectation() {}

    static ActionTransaction.ExpectedEffect forRuntimeAction(String runtimeName) {
        String name = runtimeName == null ? "" : runtimeName.trim();
        if ("launch_app".equals(name)) return ActionTransaction.ExpectedEffect.APP_CHANGE;

        // TYPE and same-turn SEND have their own bridge/domain verification.
        // Generic structural evidence may reconcile a false-negative TYPE, but
        // it must not replace a domain verifier for a real message send.
        if ("type_text".equals(name)) return ActionTransaction.ExpectedEffect.TEXT_CHANGE;
        if ("send_text".equals(name)) return ActionTransaction.ExpectedEffect.DOMAIN_VERIFIED;

        if ("search_current_app".equals(name)) {
            return ActionTransaction.ExpectedEffect.SCREEN_CHANGE;
        }
        if ("commit_search".equals(name)) {
            return ActionTransaction.ExpectedEffect.SCREEN_CHANGE;
        }
        if ("swipe_screen".equals(name)) return ActionTransaction.ExpectedEffect.SCROLL_CHANGE;
        if ("tap_screen".equals(name) || "tap_element".equals(name)) {
            return ActionTransaction.ExpectedEffect.ANY_OBSERVABLE_CHANGE;
        }
        if ("press_key".equals(name)) return ActionTransaction.ExpectedEffect.SCREEN_CHANGE;
        return ActionTransaction.ExpectedEffect.UNKNOWN;
    }
}
