package com.crewpocket.helper;

import org.json.JSONObject;
import java.util.Locale;

/** Model chooses WHAT; Runtime maps it to existing trusted tools. */
final class SemanticPhoneAction {
    static final String TOOL_NAME = "phone_action";
    static final String ERROR_TOOL = "semantic_action_error";

    static final class Resolution {
        final boolean semantic;
        final String semanticAction;
        final String runtimeName;
        final JSONObject runtimeArgs;

        Resolution(boolean semantic, String semanticAction,
                   String runtimeName, JSONObject runtimeArgs) {
            this.semantic = semantic;
            this.semanticAction = semanticAction == null ? "" : semanticAction;
            this.runtimeName = runtimeName == null ? "" : runtimeName;
            this.runtimeArgs = copy(runtimeArgs);
        }
    }

    private SemanticPhoneAction() {}

    static Resolution resolve(String requestedName, JSONObject requestedArgs) throws Exception {
        String name = requestedName == null ? "" : requestedName.trim();
        JSONObject args = copy(requestedArgs);
        if (!TOOL_NAME.equals(name)) return new Resolution(false, "", name, args);

        String action = args.optString("action", "").trim().toUpperCase(Locale.ROOT);
        String target = args.optString("target", "").trim();
        String text = args.optString("text", "");
        String direction = args.optString("direction", "").trim().toLowerCase(Locale.ROOT);
        String distance = args.optString("distance", "").trim().toLowerCase(Locale.ROOT);

        if ("OPEN_APP".equals(action)) {
            if (target.isEmpty()) return error(action, "OPEN_APP_NEEDS_TARGET",
                    "OPEN_APP 需要 target=App 名稱，例如 Google、Settings 或第一個。");
            return mapped(action, "launch_app", new JSONObject().put("app", target));
        }

        if ("TAP".equals(action)) {
            if (target.isEmpty()) return error(action, "TARGET_REQUIRED",
                    "TAP 需要目前畫面上的語意 target。");
            return mapped(action, "tap_screen",
                    new JSONObject().put("label", target).put("semantic_action", action));
        }

        if ("TYPE".equals(action)) {
            if (text.isEmpty()) return error(action, "TYPE_NEEDS_TEXT",
                    "TYPE 需要 text；TYPE 只輸入文字，不會送出訊息。");
            JSONObject out = new JSONObject().put("text", text);
            if (!target.isEmpty()) out.put("target", target);
            return mapped(action, "type_text", out);
        }

        if ("SEARCH".equals(action)) {
            String query = !text.isEmpty() ? text : target;
            if (query.isEmpty()) return error(action, "SEARCH_NEEDS_QUERY",
                    "SEARCH 需要 text=要搜尋的文字。");
            return mapped(action, "search_current_app", new JSONObject().put("text", query));
        }

        if ("SCROLL".equals(action)) {
            if (direction.isEmpty()) direction = "up";
            if (!("up".equals(direction) || "down".equals(direction)
                    || "left".equals(direction) || "right".equals(direction))) {
                return error(action, "BAD_SCROLL_DIRECTION",
                        "SCROLL direction 只能是 up/down/left/right。");
            }
            JSONObject out = new JSONObject().put("direction", direction);
            if (!distance.isEmpty()) out.put("distance", distance);
            return mapped(action, "swipe_screen", out);
        }

        if ("BACK".equals(action) || "HOME".equals(action)) {
            return mapped(action, "press_key", new JSONObject().put("key", action));
        }

        return error(action, "UNKNOWN_SEMANTIC_ACTION",
                "只支援 OPEN_APP/SEARCH/TAP/TYPE/SCROLL/BACK/HOME。");
    }

    private static Resolution mapped(String action, String runtimeName, JSONObject args) {
        return new Resolution(true, action, runtimeName, args);
    }

    private static Resolution error(String action, String code, String message) throws Exception {
        return new Resolution(true, action, ERROR_TOOL,
                new JSONObject().put("success", false)
                        .put("stepResult", "STEP_FAILED")
                        .put("error", code).put("instruction", message));
    }

    private static JSONObject copy(JSONObject input) {
        if (input == null) return new JSONObject();
        try { return new JSONObject(input.toString()); }
        catch (Exception ignored) { return new JSONObject(); }
    }
}
