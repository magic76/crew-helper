package com.crewpocket.helper;

import org.json.JSONObject;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        String elementId = args.optString(
                "element_id",
                args.optString("elementId", "")).trim();
        String text = args.optString("text", "");
        String direction = args.optString("direction", "").trim().toLowerCase(Locale.ROOT);
        String distance = args.optString("distance", "").trim().toLowerCase(Locale.ROOT);
        String visualLeaseId =
                args.optString("visual_lease_id", "").trim();
        boolean hasVisualX = args.has("visual_x");
        boolean hasVisualY = args.has("visual_y");
        double visualX = args.optDouble("visual_x", Double.NaN);
        double visualY = args.optDouble("visual_y", Double.NaN);

        // Any non-TAP action leaves the manual element-reference mode so a stale
        // overlay never leaks into a new task.
        if (!"TAP".equals(action) && ElementReferenceRuntime.isActive()) {
            ElementReferenceRuntime.cancel();
        }

        if ("OPEN_APP".equals(action)) {
            if (target.isEmpty()) return error(action, "OPEN_APP_NEEDS_TARGET",
                    "OPEN_APP 需要 target=App 名稱。這是可重試的參數錯誤；補上 target 後立刻重試，不要結束任務。");
            return mapped(action, "launch_app", new JSONObject().put("app", target));
        }

        if ("TAP".equals(action)) {
            if (target.isEmpty() && elementId.isEmpty()) {
                return error(
                        action,
                        "TARGET_REQUIRED",
                        "TAP 需要目前畫面上的語意 target 或最新 screen.items[].id。這是可重試的參數錯誤；補上其中一個後立刻重試，不要結束任務。");
            }

            if (!elementId.isEmpty()) {
                String normalizedElementId =
                        normalizeElementId(elementId);
                if (!normalizedElementId.isEmpty()) {
                    JSONObject selected = new JSONObject()
                            .put("element_id", normalizedElementId)
                            .put("semantic_action", action);
                    if (!target.isEmpty()) {
                        selected.put("label", target);
                    }
                    return mapped(
                            action,
                            "tap_element",
                            selected);
                }

                // Some Live turns put the visible label in element_id instead
                // of target. Recover that harmless schema mistake locally.
                if (target.isEmpty()
                        && !elementId.startsWith("e_")
                        && elementId.length() <= 96) {
                    target = elementId;
                }

                // element_id is a precision hint, not a hard dependency.
                // If a semantic target exists, degrade to the normal locator.
                if (target.isEmpty()) {
                    return error(
                            action,
                            "BAD_ELEMENT_ID",
                            "element_id 無法辨識，而且沒有 target 可退回。請使用最新 screen.items[].id，或提供目前畫面上的語意 target。");
                }
            }

            // Manual visual assist: label real Accessibility clickables.
            if (ElementReferenceCommand.isOpenRequest(target)) {
                JSONObject start = ElementReferenceRuntime.startExplicit();
                return new Resolution(true, action, ERROR_TOOL, start);
            }

            // While the element overlay is active, a numbered answer resolves to
            // a stable semantic element id and reuses the existing tap_element
            // execution + verification path. No coordinate conversion occurs.
            ElementReferenceRuntime.Decision element =
                    ElementReferenceRuntime.resolveChoice(target);
            if (element.selected) {
                JSONObject selected = new JSONObject()
                        .put("element_id", element.elementId)
                        .put("element_reference", true)
                        .put("semantic_action", action);
                return mapped(action, "tap_element", selected);
            }
            if (ElementReferenceRuntime.isActive()) {
                if (ElementReferenceChoice.looksLikeChoice(target)) {
                    JSONObject waiting = new JSONObject()
                            .put("success", false)
                            .put("stepResult", "STEP_FAILED")
                            .put("blockedByRuntime", true)
                            .put("error", "ELEMENT_REFERENCE_CHOICE_REQUIRED")
                            .put("taskState", "WAITING_USER")
                            .put("visualReference", "ELEMENTS")
                            .put("instruction",
                                    "這看起來是在回答元素編號，但目前編號無效。只請使用者重新回答畫面上的有效編號；"
                                            + "不要猜座標。");
                    return new Resolution(true, action, ERROR_TOOL, waiting);
                }

                // Element labels are a temporary human assist, not a sticky
                // execution mode. A fresh semantic command supersedes it.
                ElementReferenceRuntime.cancel();
            }

            String mapsConcept = GoogleMapsSemanticContract.canonicalTarget(target);
            JSONObject out = new JSONObject()
                    .put("label", target)
                    .put("semantic_action", action);

            boolean visualTapRequested =
                    !visualLeaseId.isEmpty() || hasVisualX || hasVisualY;
            if (visualTapRequested) {
                if (target.isEmpty()
                        || visualLeaseId.isEmpty()
                        || !hasVisualX
                        || !hasVisualY
                        || !Double.isFinite(visualX)
                        || !Double.isFinite(visualY)) {
                    return error(
                            action,
                            "VISUAL_TAP_FIELDS_REQUIRED",
                            "Visual TAP 必須同時提供 target、visual_lease_id、visual_x、visual_y；"
                                    + "座標只能來自最新 inspect_ui 截圖。");
                }
                out.put("visual_tap", true)
                        .put("visual_lease_id", visualLeaseId)
                        .put("x", visualX)
                        .put("y", visualY)
                        .put("coordinate_space", "normalized_1000");
            }
            if (!mapsConcept.isEmpty()) {
                // The canonical concept is the stable WHAT shared with Live.
                // The physical selector remains Runtime-owned. Until a learned
                // selector exists, use a conservative Accessibility label fallback.
                if (GoogleMapsSemanticContract.isCanonicalId(target)) {
                    out.put("label", GoogleMapsSemanticContract.runtimeLabel(mapsConcept));
                }
                out.put("semantic_target", mapsConcept);
            }
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

    private static String normalizeElementId(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) return "";
        Matcher matcher = Pattern
                .compile("e_[0-9a-fA-F]{8,32}")
                .matcher(raw);
        return matcher.find() ? matcher.group() : "";
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
        if ("BAD_ELEMENT_ID".equals(code)) return "element_id";
        if ("VISUAL_TAP_FIELDS_REQUIRED".equals(code)) return "visual_lease_id";
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
