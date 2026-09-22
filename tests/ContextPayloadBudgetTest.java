package com.crewpocket.helper;

public final class ContextPayloadBudgetTest {
    private static int checks;

    public static void main(String[] args) {
        check(
                ContextPayloadBudget.utf8Bytes("abc") == 3,
                "ASCII byte count");
        check(
                ContextPayloadBudget.utf8Bytes("台") == 3,
                "UTF-8 byte count");
        check(
                ContextPayloadBudget.toolBudget("inspect_ui")
                        == ContextPayloadBudget.INSPECT_UI_BYTES,
                "inspect budget");
        check(
                ContextPayloadBudget.toolBudget("send_text")
                        == ContextPayloadBudget.SEND_TEXT_BYTES,
                "send budget");
        check(
                ContextPayloadBudget.withinToolBudget(
                        "phone_action",
                        ContextPayloadBudget.PHONE_ACTION_BYTES),
                "threshold is allowed");
        check(
                !ContextPayloadBudget.withinToolBudget(
                        "phone_action",
                        ContextPayloadBudget.PHONE_ACTION_BYTES + 1),
                "threshold + 1 is over budget");

        System.out.println(
                "ContextPayloadBudgetTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
