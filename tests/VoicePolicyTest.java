package com.crewpocket.helper;

public final class VoicePolicyTest {
    private static int assertions;
    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        UserActionScope scope = new UserActionScope();
        scope.updateFromUserText("搜尋小明");
        check(!scope.canSend(), "search does not authorize send");
        scope.markSearchQueryEntered();
        check(scope.shouldBlockTapForSearch("小明", false), "search result boundary");
        scope.updateFromUserText("打開小明的聊天室");
        check(!scope.canSend(), "open does not authorize send");
        scope.updateFromUserText("傳給小明：明天見");
        check(!scope.canSend(), "vague sending phrase requires clarification");
        scope.updateFromUserText("傳訊息給小明：明天見");
        check(scope.canSend(), "explicit send");
        scope.consumeSendAuthorization();
        check(!scope.canSend(), "send capability consumed");
        scope.updateFromUserText("不要 send 訊息");
        check(!scope.canSend(), "negated send");
        scope.updateFromUserText("How to send a message");
        check(!scope.canSend(), "question is not authorization");
        scope.updateFromUserText("關閉這個視窗");
        check(!scope.consumeEndCallAuthorization(), "window is not call");
        scope.updateFromUserText("先這樣");
        check(!scope.consumeEndCallAuthorization(), "farewell is not call");
        scope.updateFromUserText("不要結束通話");
        check(!scope.consumeEndCallAuthorization(), "negated hangup");
        scope.updateFromUserText("請結束通話");
        check(scope.consumeEndCallAuthorization(), "explicit hangup");
        check(!scope.consumeEndCallAuthorization(), "hangup consumed");
        scope.updateFromUserText("PLEASE END THE CALL");
        check(scope.consumeEndCallAuthorization(), "case insensitive English");
        check(ActionSafetyPolicy.blocks("confirm_delete"), "delete target");
        check(ActionSafetyPolicy.blocks("付款"), "payment target");
        check(ActionSafetyPolicy.blocks("OTP"), "credential target");
        check(!ActionSafetyPolicy.blocks("搜尋"), "ordinary search control");
        check(!ActionSafetyPolicy.blocks("Send message"), "ordinary composer send");
        check(LivePrompt.CORE.contains("at most two sentences"), "speech default");
        check(!LivePrompt.CORE.contains("DECK MODE"), "deck not resident");
        System.out.println("PASS: " + assertions + " checks");
    }
}
