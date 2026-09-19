package com.crewpocket.helper;

public final class SendAuthorizationTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        SendAuthorization current = new SendAuthorization();
        current.updateFromUserText("幫我送出");
        check(current.canAttempt(), "standalone send authorizes one attempt");
        check(!current.requiresRecipientVerification(), "current chat has no recipient gate");
        current.markTransactionHandled();
        check(current.shouldBlockFurtherMessageMutation(), "transaction blocks duplicate mutation");
        current.consume();
        check(!current.canAttempt(), "authorization is one-shot");

        SendAuthorization named = new SendAuthorization();
        named.updateFromUserText("跟小明說我晚點到");
        check(named.canAttempt(), "named recipient authorizes intent");
        check(named.requiresRecipientVerification(), "named recipient requires target evidence");
        check("小明".equals(named.recipient()), "recipient extracted");

        SendAuthorization ambiguous = new SendAuthorization();
        ambiguous.updateFromUserText("告訴店員我晚一點退房");
        check(!ambiguous.canAttempt(), "ambiguous recipient cannot send");
        check(ambiguous.hasAmbiguousNamedRecipient(), "ambiguous recipient remains fail closed");

        check(SendAuthorization.isStandaloneCurrentScreenSendCommand("幫我送出"),
                "natural standalone send");
        check(!SendAuthorization.isStandaloneCurrentScreenSendCommand("輸入晚點到並送出"),
                "message plus send is not standalone");

        SendAuthorization typeAndSend = new SendAuthorization();
        typeAndSend.updateFromUserText("幫我輸入測試測試123並送出");
        check(typeAndSend.canAttempt(),
                "type plus send explicitly authorizes send_text");
        check(!typeAndSend.requiresRecipientVerification(),
                "current-chat type plus send needs no recipient gate");

        SendAuthorization discussion = new SendAuthorization();
        discussion.updateFromUserText("怎麼送出");
        check(!discussion.canAttempt(), "how-to discussion grants no send");
        discussion.updateFromUserText("不要送出");
        check(!discussion.canAttempt(), "negated send grants no send");

        System.out.println("PASS SendAuthorizationTest: " + assertions + " checks");
    }
}
