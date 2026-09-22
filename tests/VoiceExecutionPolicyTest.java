package com.crewpocket.helper;

public final class VoiceExecutionPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(
                VoiceExecutionPolicy.shouldRepeat(
                        "幫我傳給", 0.95d),
                "incomplete finalized voice must repeat");
        check(
                VoiceExecutionPolicy.shouldRepeat(
                        "刪除第 4486819 筆資料", 0.60d),
                "low-confidence voice must repeat");
        check(
                !VoiceExecutionPolicy.shouldRepeat(
                        "搜尋 4486819", 0.96d),
                "complete high-confidence voice may proceed");
        check(
                VoiceExecutionPolicy.requiresCriticalEntityConfirmation(
                        "tap_screen", "刪除"),
                "irreversible tap requires read-back");
        check(
                VoiceExecutionPolicy.requiresCriticalEntityConfirmation(
                        "send_text", ""),
                "send always requires critical-entity policy");
        check(
                !VoiceExecutionPolicy.requiresCriticalEntityConfirmation(
                        "search_current_app", "4486819"),
                "search stays reversible");

        System.out.println(
                "VoiceExecutionPolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
