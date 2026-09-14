package com.crewpocket.helper;

import org.json.JSONObject;

/** Strict model-output boundary for Smart Planner. */
final class SmartPlannerDecision {
    enum Kind { ACTION, OBSERVE, NEED_USER, DONE, FAILED }

    final Kind kind;
    final String action;
    final String target;
    final String elementId;
    final String text;
    final String direction;
    final String distance;
    final String message;

    private SmartPlannerDecision(Kind kind, String action, String target,
                                 String elementId, String text, String direction,
                                 String distance, String message) {
        this.kind = kind;
        this.action = clean(action).toUpperCase();
        this.target = clean(target);
        this.elementId = clean(elementId);
        this.text = text == null ? "" : text;
        this.direction = SmartPlannerPolicy.normalizedDirection(direction);
        this.distance = clean(distance).toLowerCase();
        this.message = clean(message);
    }

    static SmartPlannerDecision parse(String raw) throws Exception {
        String jsonText = extractJson(raw);
        JSONObject json = new JSONObject(jsonText);
        Kind kind;
        try {
            kind = Kind.valueOf(clean(json.optString("decision", "")).toUpperCase());
        } catch (Exception error) {
            throw new IllegalArgumentException("PLANNER_DECISION_INVALID");
        }
        SmartPlannerDecision decision = new SmartPlannerDecision(
                kind,
                json.optString("action", ""),
                json.optString("target", ""),
                json.optString("elementId", json.optString("element_id", "")),
                json.optString("text", ""),
                json.optString("direction", ""),
                json.optString("distance", "normal"),
                json.optString("message", ""));
        decision.validate();
        return decision;
    }

    private void validate() {
        if (kind != Kind.ACTION) return;
        if (SmartPlannerPolicy.isNeverAllowed(action)
                || !SmartPlannerPolicy.isAllowedAction(action)) {
            throw new IllegalArgumentException("PLANNER_ACTION_NOT_ALLOWED:" + action);
        }
        if ("TAP".equals(action) && elementId.isEmpty()) {
            throw new IllegalArgumentException("PLANNER_TAP_REQUIRES_ELEMENT_ID");
        }
        if ("OPEN_APP".equals(action) && target.isEmpty()) {
            throw new IllegalArgumentException("PLANNER_OPEN_APP_REQUIRES_TARGET");
        }
        if (("TYPE".equals(action) || "SEARCH".equals(action)) && text.isEmpty()) {
            throw new IllegalArgumentException("PLANNER_TEXT_REQUIRED:" + action);
        }
        if ("SCROLL".equals(action) && direction.isEmpty()) {
            throw new IllegalArgumentException("PLANNER_SCROLL_DIRECTION_INVALID");
        }
    }

    String safeSummary() {
        if (kind != Kind.ACTION) return kind.name();
        if ("TAP".equals(action)) return action + ":" + elementId;
        if ("OPEN_APP".equals(action)) return action + ":" + target;
        if ("SCROLL".equals(action)) return action + ":" + direction + ":" + distance;
        if ("TYPE".equals(action) || "SEARCH".equals(action)) return action + ":len=" + text.length();
        return action;
    }

    private static String extractJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("PLANNER_JSON_MISSING");
        return text.substring(start, end + 1);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
