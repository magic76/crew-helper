package com.crewpocket.helper;

public final class SendOnlyPolicyTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("送出"), "send out");
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("發送"), "send zh");
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("幫我按一下送出"), "press send");
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("幫我點送出按鈕"), "tap send button");
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("send"), "send en");
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("please send this"), "natural english send");

        check(UserActionScope.looksLikeSendTarget("傳送訊息"), "send target zh");
        check(UserActionScope.looksLikeSendTarget("Send message"), "send target en");

        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("不要送出"), "negated send");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("先不要送出"), "deferred send");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("輸入送出"), "typed word send");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("輸入晚點到並送出"), "new text plus send");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("傳給小明"), "recipient routing");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("怎麼送出"), "how-to question");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("如果要送出怎麼辦"), "hypothetical");

        UserActionScope currentChat = new UserActionScope();
        currentChat.updateFromUserText("輸入晚點到並送出");
        check(currentChat.canSend(), "current chat explicit send authorized");
        check(!currentChat.requiresRecipientVerification(), "current chat needs no recipient verification");

        UserActionScope named = new UserActionScope();
        named.updateFromUserText("跟小明說我晚點到");
        check(named.canSend(), "named recipient send authorized");
        check(!named.requiresRecipientVerification(), "named wording does not trigger recipient verification");
        check(named.authorizedRecipient().isEmpty(), "recipient identity is not stored");
        check(!named.blocksNamedRecipientMessagingAction(), "named wording never blocks current-chat send");

        UserActionScope quoted = new UserActionScope();
        quoted.updateFromUserText("傳給老婆：「我到了」");
        check(quoted.canSend(), "quoted recipient send authorized");
        check(!quoted.requiresRecipientVerification(), "quoted wording stays current-chat scoped");
        check(quoted.authorizedRecipient().isEmpty(), "quoted recipient is not stored as a gate");

        UserActionScope ambiguous = new UserActionScope();
        ambiguous.updateFromUserText("告訴店員我晚一點退房");
        check(ambiguous.canSend(), "recipient ambiguity does not block current-chat send");
        check(!ambiguous.blocksNamedRecipientMessagingAction(), "recipient ambiguity is not a SEND gate");

        check("john".equals(UserActionScope.extractNamedRecipient("send a message to John saying I arrived")),
                "english recipient extracted");
        UserActionScope englishNamed = new UserActionScope();
        englishNamed.updateFromUserText("send a message to John saying I arrived");
        check(englishNamed.canSend(), "english named recipient send authorized");
        check(!englishNamed.requiresRecipientVerification(), "english named wording stays current-chat scoped");
        check(englishNamed.authorizedRecipient().isEmpty(), "english recipient is not stored as a gate");

        UserActionScope bodyWords = new UserActionScope();
        bodyWords.updateFromUserText("跟小明說如果下雨就不要來");
        check(bodyWords.canSend(),
                "message body words do not clear send authorization");
        check(bodyWords.authorizedRecipient().isEmpty(),
                "message body does not create recipient identity state");

        UserActionScope literalSend = new UserActionScope();
        literalSend.updateFromUserText("幫我輸入 send");
        check(!literalSend.canSend(),
                "typing literal send does not grant commit");

        System.out.println("PASS SendOnlyPolicyTest: " + assertions + " checks");
    }
}
