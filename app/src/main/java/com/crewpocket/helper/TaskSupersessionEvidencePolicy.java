package com.crewpocket.helper;

/**
 * Evidence-only judgement of an active task when a genuinely new user
 * instruction replaces it. A successful tool action is NOT automatically
 * successful completion of the user's whole goal.
 */
final class TaskSupersessionEvidencePolicy {
    static final String VERIFIED_GOAL = "VERIFIED_GOAL";
    static final String VERIFIED_STEP_ONLY = "VERIFIED_STEP_ONLY";
    static final String EXECUTED_UNVERIFIED = "EXECUTED_UNVERIFIED";
    static final String NO_MUTATION = "NO_MUTATION";

    private TaskSupersessionEvidencePolicy() {}

    static boolean canFinalizePriorGoal(
            String goal, String state, String completionEvidence,
            boolean verified, boolean needsPostActionInspection,
            boolean blocked) {
        if (!verified || needsPostActionInspection || blocked) return false;
        String s = safe(state), e = safe(completionEvidence);
        if ("APP:OPEN".equals(goal)) {
            return "DONE".equals(s)
                    && "APP_OPEN_FOREGROUND_VERIFIED".equals(e);
        }
        if ("NAVIGATION:START".equals(goal)) {
            return "DONE".equals(s)
                    && "MAPS_NAVIGATION_ACTIVE_VERIFIED".equals(e);
        }
        if ("MEDIA:PLAY".equals(goal)) {
            return "DONE".equals(s)
                    && ("MEDIA_UI_PLAYING".equals(e)
                        || "MEDIA_PLAYBACK_BECAME_ACTIVE".equals(e));
        }
        if ("SEARCH:RESULT".equals(goal)) {
            return "ANSWER_READY".equals(s)
                    && "SEARCH_RESULT_SCREEN_INSPECTED".equals(e);
        }
        if ("TEXT:TYPE_ONLY".equals(goal)) {
            return "DONE".equals(s)
                    && "TYPE_ONLY_USER_INTENT_SATISFIED".equals(e);
        }
        // Do not silently complete message sends, purchases, deletions or
        // unknown goals while the user is speaking a different instruction.
        return false;
    }

    static String progressAtCancellation(
            String goal, String state, String completionEvidence,
            boolean verified, boolean needsPostActionInspection,
            boolean blocked, int mutationActions) {
        if (canFinalizePriorGoal(goal, state, completionEvidence,
                verified, needsPostActionInspection, blocked)) {
            return VERIFIED_GOAL;
        }
        if (verified && mutationActions > 0
                && ("EVIDENCE_AVAILABLE".equals(safe(state))
                    || "RUNTIME_V2_VERIFIED".equals(safe(completionEvidence))
                    || "AUTO_AFTER_ACTION".equals(safe(completionEvidence))
                    || "MEDIA_PLAY_CONTROL_VERIFIED".equals(safe(completionEvidence))
                    || "DIRECT_GESTURE_EFFECT_VERIFIED".equals(safe(completionEvidence)))) {
            return VERIFIED_STEP_ONLY;
        }
        if (mutationActions > 0) return EXECUTED_UNVERIFIED;
        return NO_MUTATION;
    }

    private static String safe(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
