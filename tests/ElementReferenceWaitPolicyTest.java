package com.crewpocket.helper;

public final class ElementReferenceWaitPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(ElementReferenceWaitPolicy.shouldBlockModelTool(
                        true, "phone_action"),
                "queued phone action is blocked while element choice waits");
        check(ElementReferenceWaitPolicy.shouldBlockModelTool(
                        true, "inspect_ui"),
                "queued inspect is blocked while element choice waits");
        check(ElementReferenceWaitPolicy.shouldBlockModelTool(
                        true, "search_current_app"),
                "queued search is blocked while element choice waits");
        check(!ElementReferenceWaitPolicy.shouldBlockModelTool(
                        false, "phone_action"),
                "normal model tools resume after element mode closes");
        check(!ElementReferenceWaitPolicy.shouldBlockModelTool(
                        true, ""),
                "empty tool name is ignored");

        check(ElementReferenceWaitPolicy.isCancelUtterance("取消"),
                "Chinese cancel closes element choice");
        check(ElementReferenceWaitPolicy.isCancelUtterance("cancel"),
                "English cancel closes element choice");
        check(!ElementReferenceWaitPolicy.isCancelUtterance("3"),
                "numbered choice is not treated as cancel");

        System.out.println(
                "PASS ElementReferenceWaitPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
