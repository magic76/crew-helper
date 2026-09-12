package com.crewpocket.helper;

public final class AgentRuntimeRolloutTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    private static ActionObservation obs(String pkg, int nodes) {
        return ActionObservation.of(pkg, "fp", "stable", "", "", nodes);
    }

    public static void main(String[] args) {
        ActionObservation normal = obs("com.example.app", 20);
        ActionObservation maps = obs("com.google.android.apps.maps", 20);
        ActionObservation systemUi = obs("com.android.systemui", 20);
        ActionObservation canvas = obs("com.example.game", 0);

        check(AgentRuntimeRollout.shouldEnforce("launch_app", normal), "launch staged");
        check(AgentRuntimeRollout.shouldEnforce("search_current_app", normal), "search staged");
        check(AgentRuntimeRollout.shouldEnforce("commit_search", normal), "commit search staged");
        check(AgentRuntimeRollout.shouldEnforce("type_text", normal), "type staged");
        check(AgentRuntimeRollout.shouldEnforce("swipe_screen", normal), "semantic scroll staged");
        check(AgentRuntimeRollout.shouldEnforce("press_key", normal), "back/home staged");
        check(AgentRuntimeRollout.shouldEnforce("tap_screen", normal), "normal semantic tap staged");

        check(!AgentRuntimeRollout.shouldEnforce("tap_screen", maps), "maps tap legacy");
        check(!AgentRuntimeRollout.shouldEnforce("tap_screen", systemUi), "system ui tap legacy");
        check(!AgentRuntimeRollout.shouldEnforce("tap_screen", canvas), "node-less tap legacy");
        check(!AgentRuntimeRollout.shouldEnforce("swipe_screen", canvas), "node-less scroll legacy");
        check(!AgentRuntimeRollout.shouldEnforce("send_text", normal), "send remains domain owned");
        check(!AgentRuntimeRollout.shouldEnforce("unknown_mutation", normal), "unknown stays legacy");

        check("LEGACY_MAPS_TAP".equals(AgentRuntimeRollout.rolloutLabel("tap_screen", maps)),
                "maps label");
        check("V2_ENFORCED".equals(AgentRuntimeRollout.rolloutLabel("type_text", normal)),
                "enforced label");

        System.out.println("PASS AgentRuntimeRolloutTest: " + assertions + " checks");
    }
}
