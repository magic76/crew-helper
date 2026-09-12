package com.crewpocket.helper;

/**
 * 0073 production rollout gate for AgentRuntimeV2.
 *
 * Keep this separate from the runtime itself so a device regression can be
 * disabled without deleting the transaction/ledger implementation.  The
 * first production rollout intentionally leaves domain-owned SEND_CURRENT,
 * Maps taps, SystemUI taps, and node-less/custom-canvas taps on the legacy
 * path while normal semantic phone actions use v2 verification.
 */
final class AgentRuntimeRollout {
    private AgentRuntimeRollout() {}

    // Emergency compile-time kill switch. Flip only this value to return every
    // mutation to the existing legacy production path.
    static final boolean MASTER_ENABLED = true;

    static final boolean OPEN_APP_ENABLED = true;
    static final boolean SEARCH_ENABLED = true;
    static final boolean COMMIT_SEARCH_ENABLED = true;
    static final boolean TYPE_ENABLED = true;
    static final boolean SCROLL_ENABLED = true;
    static final boolean BACK_HOME_ENABLED = true;
    static final boolean TAP_ENABLED = true;

    private static final String MAPS_PACKAGE = "com.google.android.apps.maps";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    static boolean shouldEnforce(String runtimeName, ActionObservation observation) {
        if (!MASTER_ENABLED) return false;
        String name = safe(runtimeName);

        if ("launch_app".equals(name)) return OPEN_APP_ENABLED;
        if ("search_current_app".equals(name)) return SEARCH_ENABLED;
        if ("commit_search".equals(name)) return COMMIT_SEARCH_ENABLED;
        if ("type_text".equals(name)) return TYPE_ENABLED;
        if ("press_key".equals(name)) return BACK_HOME_ENABLED;

        if ("swipe_screen".equals(name)) {
            // Accessibility cannot prove progress on many games/canvas surfaces.
            return SCROLL_ENABLED && hasSemanticObservation(observation);
        }

        if ("tap_screen".equals(name) || "tap_element".equals(name)) {
            if (!TAP_ENABLED || !hasSemanticObservation(observation)) return false;
            String pkg = observation == null ? "" : safe(observation.packageName);
            // Maps has a dedicated delayed-result recovery path. SystemUI also
            // has highly transient surfaces; both remain on the proven legacy path.
            return !MAPS_PACKAGE.equals(pkg) && !SYSTEM_UI_PACKAGE.equals(pkg);
        }

        // send_text / SEND_CURRENT remains domain-owned and already verifies one
        // submit attempt. Unknown mutations stay legacy until explicitly staged.
        return false;
    }

    static String rolloutLabel(String runtimeName, ActionObservation observation) {
        if (!MASTER_ENABLED) return "V2_MASTER_OFF";
        String name = safe(runtimeName);
        if (("tap_screen".equals(name) || "tap_element".equals(name))) {
            String pkg = observation == null ? "" : safe(observation.packageName);
            if (MAPS_PACKAGE.equals(pkg)) return "LEGACY_MAPS_TAP";
            if (SYSTEM_UI_PACKAGE.equals(pkg)) return "LEGACY_SYSTEM_UI_TAP";
            if (!hasSemanticObservation(observation)) return "LEGACY_NODELESS_TAP";
        }
        if ("swipe_screen".equals(name) && !hasSemanticObservation(observation)) {
            return "LEGACY_NODELESS_SCROLL";
        }
        if ("send_text".equals(name)) return "DOMAIN_SEND_RUNTIME";
        return shouldEnforce(name, observation) ? "V2_ENFORCED" : "LEGACY_NOT_STAGED";
    }

    private static boolean hasSemanticObservation(ActionObservation observation) {
        return observation != null && observation.available && observation.elementCount > 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
