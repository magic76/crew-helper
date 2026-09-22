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
                ContextPayloadBudget.PHONE_ACTION_BYTES >= 2000,
                "phone action keeps enough model context");
        check(
                ContextPayloadBudget.INSPECT_UI_BYTES >= 2400,
                "inspect keeps enough scene context");
        check(
                ContextPayloadBudget.APP_PLAYBOOK_BYTES >= 1600,
                "app playbook context is not over-compressed");
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
