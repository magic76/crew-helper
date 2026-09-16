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

        // A non-TAP action means the user moved on from an active visual
        // reference. Never leave a stale numbered overlay over the next task.
        if (!"TAP".equals(action) && SharedVisualReferenceRuntime.isActive()) {
            SharedVisualReferenceRuntime.cancel();
        }

        if ("OPEN_APP".equals(action)) {
            if (target.isEmpty()) return error(action, "OPEN_APP_NEEDS_TARGET",
                    "OPEN_APP 需要 target=App 名稱。這是可重試的參數錯誤；補上 target 後立刻重試，不要結束任務。");
            return mapped(action, "launch_app", new JSONObject().put("app", target));
        }

        if ("TAP".equals(action)) {
            if (target.isEmpty()) return error(action, "TARGET_REQUIRED",
                    "TAP 需要目前畫面上的語意 target。這是可重試的參數錯誤；補上 target 後立刻重試，不要結束任務。");

            // Shared Visual Reference is USER-INITIATED ONLY. Runtime accepts a
            // deliberately small explicit vocabulary plus one model marker.
            // Ordinary labels and UI_TARGET_NOT_FOUND never arm the grid.
            if (VisualReferenceCommand.isOpenRequest(target)) {
                JSONObject start = SharedVisualReferenceRuntime.startExplicit();
                return new Resolution(true, action, ERROR_TOOL, start);
            }

            // While a numbered overlay is active, the model forwards the user's
            // number/relative-position phrase only. Runtime owns refinement and
            // final coordinate conversion.
            SharedVisualReferenceRuntime.Decision visual =
                    SharedVisualReferenceRuntime.resolveChoice(target);
            if (visual.kind == SharedVisualReferenceRuntime.Kind.REFINE) {
                return new Resolution(true, action, ERROR_TOOL, visual.response);
            }
            if (visual.kind == SharedVisualReferenceRuntime.Kind.TAP) {
                JSONObject coordinate = new JSONObject()
                        .put("x", visual.x)
                        .put("y", visual.y)
                        .put("coordinate_space", "screen")
                        .put("visual_reference", true)
                        .put("semantic_action", action);
                return mapped(action, "tap_screen", coordinate);
            }
            if (SharedVisualReferenceRuntime.isActive()) {
                JSONObject waiting = new JSONObject()
                        .put("success", false)
                        .put("stepResult", "STEP_FAILED")
                        .put("blockedByRuntime", true)
                        .put("error", "VISUAL_REFERENCE_CHOICE_REQUIRED")
                        .put("taskState", "WAITING_USER")
                        .put("instruction",
                                "Shared Visual Reference 仍在等待位置。只請使用者回答畫面上的編號或相對位置，例如「5」或「右下角」；"
                                        + "不要猜座標，也不要重做原本找不到的 TAP。");
                return new Resolution(true, action, ERROR_TOOL, waiting);
            }

            String mapsConcept = GoogleMapsSemanticContract.canonicalTarget(target);
            JSONObject out = new JSONObject()
                    .put("label", target)
                    .put("semantic_action", action);
            if (!mapsConcept.isEmpty()) {
                // The canonical concept is the stable WHAT shared with Live.
                // The physical selector remains Runtime-owned. Until a learned
                // selector exists, use a conservative Accessibility label fallback.
                if (GoogleMapsSemanticContract.isCanonicalId(target)) {
                    out.put("label", GoogleMapsSemanticContract.runtimeLabel(mapsConcept));
                }
                out.put("semantic_target", mapsConcept);
            }

            // Remember sanitized context only. A later explicit user request may
            // use it as the grid banner; this call itself never opens the grid.
            SharedVisualReferenceRuntime.noteTapTarget(target, mapsConcept);
            return mapped(action, "tap_screen", out);
        }

        if ("TYPE".equals(action)) {
            if (text.isEmpty()) return error(action, "TYPE_NEEDS_TEXT",
                    "TYPE 需要 text；TYPE 只輸入文字，不會送出訊息。這是可重試的參數錯誤；補上 text 後立刻重試，不要結束任務。");

            // 0132 Goal Outcome Evidence: action success is not enough when the
            // latest user turn explicitly requested a text length. Reject the
            // model-supplied payload before any phone mutation if it materially
            // misses that numeric constraint. Only counts are exposed; text is not.
            TextEntryGoalGuard.Validation length = TextEntryGoalGuard.validate(text);
            if (!length.allowed) {
                return error(action, length.errorCode(),
                        "文字長度未符合使用者這一輪的要求。請重新產生符合長度的內容後再 TYPE；"
                                + "不要宣稱已完成。expected=" + length.expected
                                + ", actual=" + length.actual + ".");
            }

            JSONObject out = new JSONObject().put("text", text);
            if (!target.isEmpty()) out.put("target", target);
            return mapped(action, "type_text", out);
        }

        if ("SEARCH".equals(action)) {
            String query = !text.isEmpty() ? text : target;
            if (query.isEmpty()) return error(action, "SEARCH_NEEDS_QUERY",
                    "SEARCH 需要在同一次 phone_action 呼叫帶上非空的 text（搜尋字串）。這是可重試的參數錯誤；補上 text 後立刻重試 SEARCH，不要結束任務。");
            return mapped(action, "search_current_app", new JSONObject().put("text", query));
        }

        if ("COMMIT_SEARCH".equals(action)) {
            return mapped(action, "commit_search", new JSONObject());
        }

        if ("SCROLL".equals(action)) {
            // Direction is semantic content/navigation direction, never the
            // physical finger gesture. Runtime owns the Android gesture.
            String semanticDirection = ScrollDirectionPolicy.normalizeSemantic(direction);
            if (!ScrollDirectionPolicy.isSupported(semanticDirection)) {
                return error(action, "BAD_SCROLL_DIRECTION",
                        "SCROLL direction 只能是 forward/backward/left/right；"
                        + "forward=看後面/下方/下一頁，backward=看前面/上方/上一頁，"
                        + "right=看右邊內容，left=看左邊內容。請修正 direction 後立刻重試。");
            }

            // Convert semantic content direction to the opposite physical finger
            // movement where required. Example: reveal content on the right by
            // dragging the page left.
            String runtimeDirection = ScrollDirectionPolicy.toPhysical(semanticDirection);

            JSONObject out = new JSONObject().put("direction", runtimeDirection);
            if (!distance.isEmpty()) out.put("distance", distance);
            return mapped(action, "swipe_screen", out);
        }

        if ("BACK".equals(action) || "HOME".equals(action)) {
            return mapped(action, "press_key", new JSONObject().put("key", action));
        }

        return error(action, "UNKNOWN_SEMANTIC_ACTION",
                "只支援 OPEN_APP/SEARCH/COMMIT_SEARCH/TAP/TYPE/SCROLL/BACK/HOME。請改用支援的 action 後重試。");
    }

    private static Resolution mapped(String action, String runtimeName, JSONObject args) {
        return new Resolution(true, action, runtimeName, args);
    }

    private static Resolution error(String action, String code, String message) throws Exception {
        JSONObject failure = new JSONObject()
                .put("success", false)
                .put("stepResult", "STEP_FAILED")
                .put("error", code)
                .put("instruction", message)
                .put("semanticAction", action == null ? "" : action)
                .put("contractError", true)
                .put("retryable", true);
        String requiredField = requiredFieldFor(code);
        if (!requiredField.isEmpty()) failure.put("requiredField", requiredField);
        return new Resolution(true, action, ERROR_TOOL, failure);
    }

    private static String requiredFieldFor(String code) {
        if ("OPEN_APP_NEEDS_TARGET".equals(code) || "TARGET_REQUIRED".equals(code)) {
            return "target";
        }
        if ("TYPE_NEEDS_TEXT".equals(code) || "SEARCH_NEEDS_QUERY".equals(code)) {
            return "text";
        }
        if ("BAD_SCROLL_DIRECTION".equals(code)) return "direction";
        return "";
    }

    private static JSONObject copy(JSONObject input) {
        if (input == null) return new JSONObject();
        try { return new JSONObject(input.toString()); }
        catch (Exception ignored) { return new JSONObject(); }
    }
}
