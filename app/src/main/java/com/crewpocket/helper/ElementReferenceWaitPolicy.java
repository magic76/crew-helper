package com.crewpocket.helper;

/**
 * While the numbered Accessibility overlay is active, Runtime owns the user
 * boundary. Gemini must not continue mutating or observing the phone until the
 * human selects a number or cancels the temporary assist mode.
 */
final class ElementReferenceWaitPolicy {
    private ElementReferenceWaitPolicy() {}

    static boolean shouldBlockModelTool(
            boolean elementReferenceActive,
            String requestedTool) {
        if (!elementReferenceActive) return false;

        String tool = requestedTool == null
                ? ""
                : requestedTool.trim();

        // The numbered answer is consumed before model/tool routing, so there
        // is no legitimate model-side phone tool while this lease is active.
        // Blocking every queued tool also kills stale multi-call batches.
        return !tool.isEmpty();
    }
}
