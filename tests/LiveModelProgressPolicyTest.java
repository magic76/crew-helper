package com.crewpocket.helper;

public final class LiveModelProgressPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(!LiveModelProgressPolicy.hasSubstantiveProgress(
                        false, "", true, false, false),
                "empty modelTurn is not substantive progress");

        check(!LiveModelProgressPolicy.hasSubstantiveProgress(
                        false, "   ", true, false, false),
                "blank transcript is not substantive progress");

        check(LiveModelProgressPolicy.hasSubstantiveProgress(
                        true, "", false, false, false),
                "tool call is substantive progress");

        check(LiveModelProgressPolicy.hasSubstantiveProgress(
                        false, "正在處理", false, false, false),
                "output transcript is substantive progress");

        check(LiveModelProgressPolicy.hasSubstantiveProgress(
                        false, "", true, true, false),
                "model audio is substantive progress");

        check(LiveModelProgressPolicy.hasSubstantiveProgress(
                        false, "", true, false, true),
                "model text is substantive progress");

        System.out.println(
                "PASS LiveModelProgressPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
