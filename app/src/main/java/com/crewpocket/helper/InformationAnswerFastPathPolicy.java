package com.crewpocket.helper;

/**
 * Pure policy for surfacing an "answer now if sufficient" hint after an
 * informational lookup has produced a fresh visual observation.
 *
 * This never blocks navigation or declares that the answer is present. It only
 * tells Gemini that the current screenshot is the first place to decide whether
 * more phone work is actually necessary.
 */
final class InformationAnswerFastPathPolicy {
    private InformationAnswerFastPathPolicy() {}

    static boolean shouldOffer(String toolName,
                               boolean succeeded,
                               int searchAttempts,
                               int committedSearches,
                               boolean blocked,
                               boolean waitingUser) {
        if (!succeeded || blocked || waitingUser) return false;
        if (!"inspect_ui".equals(toolName) && !"take_screenshot".equals(toolName)) {
            return false;
        }
        return searchAttempts > 0 || committedSearches > 0;
    }

    static String instruction() {
        return "ANSWER FAST PATH: this is a fresh post-search screen. "
                + "If the screenshot already contains the information the user asked for, "
                + "answer immediately and do not call another tool merely to confirm it. "
                + "Only continue when the requested value is genuinely missing, truncated, "
                + "off-screen, or ambiguous.";
    }
}
