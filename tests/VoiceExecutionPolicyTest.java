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
                !VoiceExecutionPolicy.requiresCriticalEntityConfirmation(
                        "send_text", ""),
                "explicit one-shot send does not need redundant read-back");
        check(
                VoiceExecutionPolicy.requiresCriticalEntityConfirmation(
                        "start_conversation_loop", ""),
                "loop confirmation remains on when no-confirm preference is off");
        check(
                !VoiceExecutionPolicy.requiresCriticalEntityConfirmation(
                        "search_current_app", "4486819"),
                "search stays reversible");
        check(
                !VoiceExecutionPolicy.requiresReliableTranscript(
                        "type_text", "message composer"),
                "draft typing does not require high-confidence voice");
        check(
                !VoiceExecutionPolicy.requiresReliableTranscript(
                        "search_current_app", "search"),
                "search does not require high-confidence voice");
        check(
                VoiceExecutionPolicy.requiresReliableTranscript(
                        "send_text", ""),
                "message commit requires reliable transcript");
        check(
                VoiceExecutionPolicy.requiresReliableTranscript(
                        "tap_screen", "Send message"),
                "tap on send control requires reliable transcript");
        check(
                VoiceExecutionPolicy.requiresReliableTranscript(
                        "tap_screen", "刪除"),
                "sensitive tap requires reliable transcript");
        check(
                VoiceExecutionPolicy.bypassesMessageVoiceGate(
                        "send_text", "", true),
                "no-confirm preference bypasses send voice gate");
        check(
                VoiceExecutionPolicy.bypassesMessageVoiceGate(
                        "start_conversation_loop", "", true),
                "no-confirm preference bypasses loop-start voice gate");
        check(
                VoiceExecutionPolicy.bypassesMessageVoiceGate(
                        "tap_screen", "Send message", true),
                "no-confirm preference bypasses semantic send tap gate");
        check(
                !VoiceExecutionPolicy.bypassesMessageVoiceGate(
                        "tap_screen", "刪除", true),
                "message preference never bypasses destructive action gate");
        check(
                !VoiceExecutionPolicy.bypassesMessageVoiceGate(
                        "send_text", "", false),
                "preference off keeps normal message voice gate");

        System.out.println(
                "VoiceExecutionPolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
