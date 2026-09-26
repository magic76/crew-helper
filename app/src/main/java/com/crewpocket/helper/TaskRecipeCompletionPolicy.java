package com.crewpocket.helper;

import java.util.Locale;

/**
 * Separates "recipe steps executed" from "the user's whole goal is verified".
 *
 * This is deliberately small and domain-aware. If it cannot prove the terminal
 * state, the recipe remains useful acceleration but Runtime asks for fresh
 * verification before telling the user the task is complete.
 */
final class TaskRecipeCompletionPolicy {
    private TaskRecipeCompletionPolicy() {}

    static boolean isGoalTerminalVerified(
            String goalIntent,
            String taskState,
            String completionEvidence,
            boolean verified,
            boolean mediaPlaybackActive,
            String terminalPackage,
            String visibleEvidence) {
        String scope = clean(goalIntent).toUpperCase(Locale.ROOT);
        String state = clean(taskState).toUpperCase(Locale.ROOT);
        String evidence =
                clean(completionEvidence).toUpperCase(Locale.ROOT);

        if ("MEDIA:PLAY".equals(scope)) {
            return mediaPlaybackActive
                    && ("MEDIA_UI_PLAYING".equals(evidence)
                        || "MEDIA_PLAYBACK_BECAME_ACTIVE".equals(evidence));
        }

        if ("NAVIGATION:START".equals(scope)) {
            return GoogleMapsNavigationStatePolicy
                    .isActiveNavigationScreen(
                            terminalPackage,
                            visibleEvidence);
        }

        // Until query/result affinity exists, search execution is never enough
        // to prove that the visible results answer the exact current query.
        if ("SEARCH:RESULT".equals(scope)) {
            return false;
        }

        boolean terminal =
                "DONE".equals(state)
                        || "ANSWER_READY".equals(state);
        if (!terminal || !verified || evidence.isEmpty()) {
            return false;
        }

        // These are action/transport evidence, not whole-goal evidence.
        return !"TASK_RECIPE_COMPLETED".equals(evidence)
                && !"TASK_RECIPE_EXECUTED".equals(evidence)
                && !"RUNTIME_V2_VERIFIED".equals(evidence)
                && !"AUTO_AFTER_ACTION".equals(evidence)
                && !"CURRENT_SCREEN_INSPECTED".equals(evidence)
                && !"MEDIA_PLAY_CONTROL_VERIFIED".equals(evidence)
                && !"MEDIA_PLAY_CONTROL_EFFECT".equals(evidence);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
