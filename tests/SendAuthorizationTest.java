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
        current.markCommitDispatched();
        check(current.isCommitDispatched(), "commit dispatch is remembered");
        check(!current.canAttempt(), "dispatched authorization is one-shot");

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

        check(SendAuthorization.isExplicitTypeOnlyRequest(
                        "在 WhatsApp 打字測試測試123"),
                "chat composer typing is explicit type-only");
        check(SendAuthorization.isExplicitTypeOnlyRequest(
                        "在 WhatsApp 打字測試測試123，不要送出"),
                "type with explicit no-send remains type-only");
        check(SendAuthorization.isExplicitTypeOnlyRequest(
                        "please type hello in WhatsApp"),
                "english chat typing is explicit type-only");
        check(!SendAuthorization.isExplicitTypeOnlyRequest(
                        "輸入測試測試123並送出"),
                "type plus send is not type-only");
        check(!SendAuthorization.isExplicitTypeOnlyRequest(
                        "不要打字測試測試123"),
                "negated type is not executable");
        check(!SendAuthorization.isExplicitTypeOnlyRequest(
                        "怎麼在 WhatsApp 打字"),
                "typing how-to is not executable");
        check(!SendAuthorization.isExplicitTypeOnlyRequest(
                        "輸入123然後按下一步"),
                "compound type then action is not pure type-only");

        SendAuthorization literalSendWord = new SendAuthorization();
        literalSendWord.updateFromUserText("幫我輸入 send");
        check(!literalSendWord.canAttempt(),
                "typing the literal word send does not authorize commit");
        check(SendAuthorization.isExplicitTypeOnlyRequest("幫我輸入 send"),
                "literal send word remains type-only");

        SendAuthorization literalZhSendWord = new SendAuthorization();
        literalZhSendWord.updateFromUserText("幫我輸入送出");
        check(!literalZhSendWord.canAttempt(),
                "typing the literal word 送出 does not authorize commit");

        SendAuthorization explicitTypeThenSend = new SendAuthorization();
        explicitTypeThenSend.updateFromUserText("輸入 hello 然後送出");
        check(explicitTypeThenSend.canAttempt(),
                "type then explicit send authorizes commit");

        SendAuthorization directPayload = new SendAuthorization();
        directPayload.updateFromUserText("發送你好");
        check(directPayload.canAttempt(),
                "direct current-chat send with payload authorizes commit");

        SendAuthorization englishDirectPayload = new SendAuthorization();
        englishDirectPayload.updateFromUserText("send hello");
        check(englishDirectPayload.canAttempt(),
                "english direct send with payload authorizes commit");

        SendAuthorization bodyNegation = new SendAuthorization();
        bodyNegation.updateFromUserText("跟小明說如果下雨就不要來");
        check(bodyNegation.canAttempt(),
                "message body negation/hypothetical does not cancel send command");
        check("小明".equals(bodyNegation.recipient()),
                "recipient survives message body negation");

        SendAuthorization negatedNamed = new SendAuthorization();
        negatedNamed.updateFromUserText("不要跟小明說我晚點到");
        check(!negatedNamed.canAttempt(),
                "negated named-recipient command grants no commit");

        SendAuthorization englishNegatedNamed = new SendAuthorization();
        englishNegatedNamed.updateFromUserText("don't tell John I arrived");
        check(!englishNegatedNamed.canAttempt(),
                "english negated named-recipient command grants no commit");

        check(SendAuthorization.allowsPersistentMessageTrust(
                        "幫我跟小明聊"),
                "persistent trust may support delegated chat");
        check(!SendAuthorization.allowsPersistentMessageTrust(
                        "幫我輸入 hello"),
                "persistent trust never upgrades draft-only typing");
        check(!SendAuthorization.allowsPersistentMessageTrust(
                        "輸入 hello 然後按下一步"),
                "persistent trust never turns non-send compound typing into SEND");
        check(!SendAuthorization.allowsPersistentMessageTrust(
                        "不要送出"),
                "persistent trust respects explicit no-send");
        check(!SendAuthorization.allowsPersistentMessageTrust(
                        "don't send"),
                "persistent trust respects english no-send");
        check(SendAuthorization.allowsPersistentMessageTrust(
                        "輸入 hello 然後送出"),
                "persistent trust allows explicit type-then-send");

        System.out.println("PASS SendAuthorizationTest: " + assertions + " checks");
    }
}
