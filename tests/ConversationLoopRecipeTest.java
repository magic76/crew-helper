package com.crewpocket.helper;

public final class ConversationLoopRecipeTest {
    private static int checks;

    public static void main(String[] args) {
        check(
                ConversationLoopPolicy.isStopIntent(
                        "停止跟他聊天"),
                "explicit stop should stop loop");
        check(
                ConversationLoopPolicy.shouldYieldToUserTurn(
                        "我自己回"),
                "fresh human turn should take foreground ownership");
        check(
                ConversationLoopPolicy.shouldYieldToUserTurn(
                        "幫我輸入晚點到"),
                "manual typing request should take foreground ownership");
        check(
                ConversationLoopPolicy.shouldYieldToUserTurn(
                        "導航回家"),
                "unrelated foreground task should take ownership over loop");
        check(
                ConversationLoopPolicy.shouldYieldToUserTurn(
                        "幫我跟他持續聊天"),
                "even a continue-chat user turn first yields the old lease");
        check(
                !ConversationLoopPolicy.shouldYieldToUserTurn(
                        "   "),
                "empty input does not change foreground ownership");
        check(
                ConversationLoopPolicy.hasDelegatedSendAuthority(
                        true, false),
                "active loop authorizes a reply without fresh user turn");
        check(
                ConversationLoopPolicy.hasDelegatedSendAuthority(
                        false, true),
                "fresh explicit send still authorizes one-shot reply");
        check(
                !ConversationLoopPolicy.hasDelegatedSendAuthority(
                        false, false),
                "no loop and no fresh send has no send authority");

        ConversationLoopRecipe recipe =
                new ConversationLoopRecipe();
        ConversationLoopRecipe.StartResult started =
                recipe.start("", 7L, 3, 10);
        check(started.success, "recipe should start");
        check(recipe.canSend(), "initial state may send");
        check(!recipe.allowsTool("search_current_app"),
                "current-chat loop must not search or switch recipients");
        check(recipe.allowsTool("send_text"),
                "current-chat loop may send in the visible chat");

        check(recipe.markSent("fp1"),
                "first send should arm background wait");
        check(recipe.isWaiting(), "after send must wait");
        check(!recipe.allowsTool("tap_screen"),
                "waiting state must block unrelated mutation");

        check(!recipe.markMessagePending("fp1"),
                "same fingerprint is not a new message");
        check(recipe.markMessagePending("fp2"),
                "changed fingerprint may wake inspection");
        check(recipe.canSend(),
                "message-pending state may send one reply");

        check(recipe.markSent("fp3"),
                "second send should re-arm wait");
        check(recipe.markMessagePending("fp4"),
                "second incoming message should wake");
        check(!recipe.markSent("fp5"),
                "third send hits bounded reply limit");
        check(!recipe.isActive(),
                "bounded loop stops at reply limit");

        System.out.println(
                "ConversationLoopRecipeTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
