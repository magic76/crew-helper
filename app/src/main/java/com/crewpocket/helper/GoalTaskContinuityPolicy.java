package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Prevents the 45-second conversation capsule from being treated as sufficient
 * proof that two finished Agent tasks share one operational goal.
 *
 * Identity stays privacy-bounded: only sanitized tool names and semantic target
 * families are inspected. Raw user text, search queries and model replies are
 * never used here.
 */
final class GoalTaskContinuityPolicy {
    private enum Domain {
        NOTEBOOK,
        DEVICE,
        WEB,
        DECK,
        APP_GUIDANCE,
        UNKNOWN
    }

    private GoalTaskContinuityPolicy() {}

    static boolean compatible(JSONObject previous, JSONObject current) {
        if (previous == null || current == null) return false;
        Domain a = domain(previous.optJSONArray("steps"));
        Domain b = domain(current.optJSONArray("steps"));

        // An opaque goalId plus the idle window is not enough. Require runtime
        // evidence that both tasks operate in the same coarse capability domain.
        if (a == Domain.UNKNOWN || b == Domain.UNKNOWN) return false;
        return a == b;
    }

    static String domainName(JSONArray steps) {
        return domain(steps).name();
    }

    private static Domain domain(JSONArray steps) {
        if (steps == null || steps.length() == 0) return Domain.UNKNOWN;
        Domain seen = Domain.UNKNOWN;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            Domain next = domainForTool(step.optString("tool", ""));
            if (next == Domain.UNKNOWN) continue;
            if (seen == Domain.UNKNOWN) {
                seen = next;
            } else if (seen != next) {
                // Mixed-domain tasks are intentionally not used as cross-task
                // reflection evidence. They are too ambiguous to join safely.
                return Domain.UNKNOWN;
            }
        }
        return seen;
    }

    private static Domain domainForTool(String tool) {
        String t = tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return Domain.UNKNOWN;

        if (t.equals("search_notes") || t.equals("list_notes")
                || t.equals("create_note") || t.equals("update_note")) {
            return Domain.NOTEBOOK;
        }
        if (t.equals("read_web_page")) return Domain.WEB;
        if (t.contains("deck") || t.contains("presentation")) return Domain.DECK;
        if (t.equals("remember_app_guidance") || t.equals("list_app_guidance")) {
            return Domain.APP_GUIDANCE;
        }
        if (t.equals("tap_screen") || t.equals("tap_element")
                || t.equals("press_key") || t.equals("launch_app")
                || t.equals("swipe_screen") || t.equals("type_text")
                || t.equals("search_current_app") || t.equals("commit_search")
                || t.equals("inspect_ui") || t.equals("take_screenshot")
                || t.equals("semantic_action_error") || t.equals("wait")) {
            return Domain.DEVICE;
        }
        return Domain.UNKNOWN;
    }
}
