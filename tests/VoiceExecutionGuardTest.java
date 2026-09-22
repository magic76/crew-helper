package com.crewpocket.helper;

import org.json.JSONObject;

public final class VoiceExecutionGuardTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        VoiceExecutionGuard guard = new VoiceExecutionGuard();

        guard.onFinalizedVoiceTurn(1L, "幫我傳給", 0.95d);
        VoiceExecutionGuard.Preflight incomplete =
                guard.preflight(
                        1L,
                        "tap_screen",
                        new JSONObject().put("label", "搜尋"));
        check(!incomplete.allowed
                        && "VOICE_TRANSCRIPT_INCOMPLETE".equals(
                                incomplete.code),
                "incomplete finalized voice must fail closed");

        guard.clear();
        guard.onFinalizedVoiceTurn(
                2L, "刪除第 4486819 筆資料", 0.60d);
        VoiceExecutionGuard.Preflight lowConfidence =
                guard.preflight(
                        2L,
                        "tap_screen",
                        new JSONObject().put("label", "刪除"));
        check(!lowConfidence.allowed
                        && "VOICE_TRANSCRIPT_LOW_CONFIDENCE".equals(
                                lowConfidence.code),
                "low-confidence mutation must fail closed");

        guard.clear();
        guard.onFinalizedVoiceTurn(
                3L, "刪除第 4486819 筆資料", 0.96d);
        VoiceExecutionGuard.Preflight irreversible =
                guard.preflight(
                        3L,
                        "tap_screen",
                        new JSONObject().put("label", "刪除"));
        check(!irreversible.allowed
                        && "VOICE_CRITICAL_ENTITY_CONFIRMATION_REQUIRED".equals(
                                irreversible.code)
                        && irreversible.confirmationSummary.contains("4486819"),
                "irreversible target with critical entity must read back");

        guard.clear();
        guard.onFinalizedVoiceTurn(
                4L, "搜尋 4486819", 0.96d);
        VoiceExecutionGuard.Preflight search =
                guard.preflight(
                        4L,
                        "search_current_app",
                        new JSONObject().put("text", "4486819"));
        check(search.allowed,
                "reversible search stays fluid");

        System.out.println(
                "VoiceExecutionGuardTest passed " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
