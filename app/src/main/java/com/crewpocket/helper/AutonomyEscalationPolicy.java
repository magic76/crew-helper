package com.crewpocket.helper;

import java.util.Locale;

/**
 * Bounded autonomy policy for low-risk uncertain UI actions.
 *
 * Runtime may recover from locator uncertainty once. If uncertainty survives a
 * fresh retry, or Runtime detects a no-progress observation loop, control goes
 * back to the human instead of spending more tool turns guessing.
 */
final class AutonomyEscalationPolicy {
    static final int MAX_AUTONOMOUS_UNCERTAIN_RECOVERIES = 1;

    enum Decision {
        NONE,
        RECOVER_ONCE,
        ASK_USER
    }

    private AutonomyEscalationPolicy() {}

    static Decision evaluate(
            int priorRecoveries,
            String locatorDecision,
            String errorCode,
            String nextRequirement,
            String completionEvidence) {
        String decision = upper(locatorDecision);
        String error = upper(errorCode);
        String next = upper(nextRequirement);
        String evidence = upper(completionEvidence);

        if (ObservationLoopPolicy.BLOCK_CODE.equals(error)
                || "UNCHANGED_SCREEN_OBSERVED_TWICE".equals(evidence)) {
            return Decision.ASK_USER;
        }

        boolean locatorUncertain =
                "RETRY_OBSERVE".equals(decision);

        boolean staleCandidateNeedsFreshObservation =
                error.startsWith("CANDIDATE_ARBITRATION_")
                        && (error.contains("STALE_GENERATION")
                            || error.contains("PACKAGE_CHANGED")
                            || error.contains("CANDIDATE_NOT_FOUND")
                            || error.contains("CANDIDATE_NOT_CLICKABLE"))
                        && next.contains("INSPECT_UI");

        if (!locatorUncertain && !staleCandidateNeedsFreshObservation) {
            return Decision.NONE;
        }

        return Math.max(0, priorRecoveries)
                        >= MAX_AUTONOMOUS_UNCERTAIN_RECOVERIES
                ? Decision.ASK_USER
                : Decision.RECOVER_ONCE;
    }

    static String userInstruction() {
        return "Runtime 已自動嘗試一次低風險 recovery，但目前仍無法有把握地決定下一個 UI 動作。"
                + "請只問使用者一個簡短問題；若 tool result 內有 candidates，最多列出前兩個讓使用者選。"
                + "在使用者回答前不要再 inspect、tap、swipe、back 或改用座標猜測。";
    }

    private static String upper(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
    }
}
