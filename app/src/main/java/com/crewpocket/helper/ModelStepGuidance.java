package com.crewpocket.helper;

import java.util.Locale;

/**
 * Pure model-facing interpretation of one Runtime step.
 *
 * This class never grants authority or changes Runtime behavior. It only turns
 * already-decided Runtime evidence into a small, stable hint for Gemini Live.
 */
final class ModelStepGuidance {
    static final String DONE = "DONE";
    static final String WAIT = "WAIT";
    static final String NEED_USER = "NEED_USER";
    static final String FAILED = "FAILED";

    static final class Guidance {
        final String action;
        final String effect;
        final String reason;
        final String next;

        Guidance(String action, String effect, String reason, String next) {
            this.action = safe(action);
            this.effect = safe(effect);
            this.reason = safe(reason);
            this.next = safe(next);
        }
    }

    private ModelStepGuidance() {}

    static Guidance from(
            String toolName,
            String status,
            String semanticAction,
            String runtimeName,
            String error,
            String taskState,
            String searchTransaction,
            boolean verifiedSend) {
        String cleanStatus = upper(status);
        String action = action(toolName, semanticAction, runtimeName);
        String reason = reason(cleanStatus, error);
        String effect = effect(
                toolName,
                cleanStatus,
                semanticAction,
                runtimeName,
                taskState,
                searchTransaction,
                verifiedSend);
        String next = "WAITING_BACKGROUND".equals(upper(taskState))
                ? "WAIT_FOR_RUNTIME"
                : next(cleanStatus, reason);
        return new Guidance(action, effect, reason, next);
    }

    static String progressState(String status) {
        String value = upper(status);
        if (NEED_USER.equals(value)) return "NEED_USER";
        if (WAIT.equals(value)) return "WAITING";
        if (FAILED.equals(value)) return "RECOVER";
        return "EVALUATE_GOAL";
    }

    private static String action(
            String toolName,
            String semanticAction,
            String runtimeName) {
        String semantic = upper(semanticAction);
        if (!semantic.isEmpty()) return semantic;
        String runtime = safe(runtimeName);
        if (!runtime.isEmpty()) return runtime;
        return safe(toolName);
    }

    private static String effect(
            String toolName,
            String status,
            String semanticAction,
            String runtimeName,
            String taskState,
            String searchTransaction,
            boolean verifiedSend) {
        if ("WAITING_BACKGROUND".equals(upper(taskState))) {
            return "BACKGROUND_WAIT_ARMED";
        }
        if (verifiedSend) return "MESSAGE_SENT";
        if (NEED_USER.equals(status)) return "USER_CHOICE_REQUIRED";
        if (WAIT.equals(status)) return "AWAITING_UI";
        if (FAILED.equals(status)) return "STEP_FAILED";

        String semantic = upper(semanticAction);
        String runtime = safe(runtimeName);
        String search = upper(searchTransaction);
        if ("OPEN_APP".equals(semantic) || "launch_app".equals(runtime)) {
            return "APP_OPENED";
        }
        if ("TYPE".equals(semantic) || "type_text".equals(runtime)) {
            return "TEXT_ENTERED";
        }
        if ("SEARCH".equals(semantic)
                || "search_current_app".equals(runtime)
                || search.contains("COMMITTED")
                || search.contains("RESULT")) {
            return "SEARCH_UPDATED";
        }
        if ("SCROLL".equals(semantic) || "swipe_screen".equals(runtime)) {
            return "SCREEN_SCROLLED";
        }
        if ("TAP".equals(semantic)
                || "tap_screen".equals(runtime)
                || "tap_element".equals(runtime)) {
            return "UI_ACTIVATED";
        }
        if ("BACK".equals(semantic)
                || "HOME".equals(semantic)
                || "press_key".equals(runtime)) {
            return "NAVIGATION";
        }
        if ("inspect_ui".equals(toolName) || "take_screenshot".equals(toolName)) {
            return "SCREEN_OBSERVED";
        }
        if ("wait".equals(toolName)) return "CONDITION_MET";
        if ("wait_then_action".equals(toolName)) return "BACKGROUND_WAIT_ARMED";
        if ("end_voice_session".equals(toolName)) return "VOICE_SESSION_ENDING";
        if ("IN_PROGRESS".equals(upper(taskState))) return "STEP_IN_PROGRESS";
        return "STEP_COMPLETED";
    }

    private static String reason(String status, String error) {
        String value = upper(error);
        if (value.contains("SEND_NOT_AUTHORIZED")
                || value.contains("CURRENT_SCREEN_SEND_NOT_AUTHORIZED")) {
            return "SEND_NOT_AUTHORIZED";
        }
        if (value.contains("UI_TARGET_NOT_FOUND")
                || value.contains("TARGET_NOT_FOUND")
                || value.contains("SEARCH_CONTROL_NOT_FOUND")) {
            return "TARGET_NOT_FOUND";
        }
        if (value.contains("OBSERVE_REQUIRED")
                || value.contains("PENDING_VERIFICATION")) {
            return "OBSERVE_REQUIRED";
        }
        if (value.contains("SEARCH_SCOPE")) return "SCOPE_BLOCKED";
        if (value.contains("SENSITIVE")
                || value.contains("POLICY")
                || value.contains("DENIED")) {
            return "POLICY_BLOCKED";
        }
        if (value.contains("STALE")
                || value.contains("CANCELLED")
                || value.contains("使用者已有新指令")) {
            return "STALE_OR_CANCELLED";
        }
        if (value.contains("PENDING_WAIT_SETUP_FAILED")) {
            return "WAIT_SETUP_FAILED";
        }
        if (value.contains("ELEMENT_REFERENCE")
                || value.contains("NO_CLICKABLE_ELEMENTS")) {
            return "VISUAL_REFERENCE_UNAVAILABLE";
        }

        // Preserve machine-readable Runtime error codes so stronger models can
        // distinguish causes such as INPUT_NOT_FOCUSED without exposing arbitrary
        // free-form Runtime strings.
        if (value.matches("[A-Z][A-Z0-9_]{2,63}")) return value;

        if (FAILED.equals(status)) return "UNCLASSIFIED_STEP_FAILURE";
        if (WAIT.equals(status)) return "ASYNC_UI_PENDING";
        if (NEED_USER.equals(status)) return "USER_CHOICE_REQUIRED";
        return "";
    }

    private static String next(String status, String reason) {
        if (NEED_USER.equals(status)) return "ASK_USER";
        if (WAIT.equals(status)) return "OBSERVE";
        if (FAILED.equals(status)) {
            if ("SEND_NOT_AUTHORIZED".equals(reason)) return "STOP_AND_WAIT_FOR_USER";
            if ("POLICY_BLOCKED".equals(reason)
                    || "SCOPE_BLOCKED".equals(reason)
                    || "STALE_OR_CANCELLED".equals(reason)) {
                return "STOP";
            }
            if ("WAIT_SETUP_FAILED".equals(reason)) return "ASK_USER";
            return "TRY_DIFFERENT_METHOD";
        }
        return "EVALUATE_GOAL";
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
