package com.crewpocket.helper;

import java.nio.charset.StandardCharsets;

/** Stable byte budgets for model-facing text/JSON payloads. */
final class ContextPayloadBudget {
    static final int PHONE_ACTION_BYTES = 2200;
    static final int INSPECT_UI_BYTES = 2800;
    static final int SEND_TEXT_BYTES = 900;
    static final int WAIT_BYTES = 1200;
    static final int CONTROL_BYTES = 1200;
    static final int DEFAULT_TOOL_BYTES = 2000;
    static final int INTERNAL_DIRECTIVE_BYTES = 1100;
    static final int APP_PLAYBOOK_BYTES = 1800;

    private ContextPayloadBudget() {}

    static int utf8Bytes(String value) {
        if (value == null || value.isEmpty()) return 0;
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    static int toolBudget(String toolName) {
        String name = toolName == null ? "" : toolName;
        if ("inspect_ui".equals(name)) return INSPECT_UI_BYTES;
        if ("send_text".equals(name)) return SEND_TEXT_BYTES;
        if ("wait".equals(name) || "wait_then_action".equals(name)) {
            return WAIT_BYTES;
        }
        if ("start_conversation_loop".equals(name)
                || "continue_conversation_loop".equals(name)
                || "stop_conversation_loop".equals(name)
                || "end_voice_session".equals(name)) {
            return CONTROL_BYTES;
        }
        if ("phone_action".equals(name)) return PHONE_ACTION_BYTES;
        return DEFAULT_TOOL_BYTES;
    }

    static boolean withinToolBudget(String toolName, int bytes) {
        return bytes <= toolBudget(toolName);
    }
}
