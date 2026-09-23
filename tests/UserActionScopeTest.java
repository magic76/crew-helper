package com.crewpocket.helper;

public final class UserActionScopeTest {
    private static int checks;

    public static void main(String[] args) {
        UserActionScope scope = new UserActionScope();

        scope.updateFromUserText("顯示元素");
        check(scope.consumeElementReferenceAuthorization(),
                "explicit user command grants one overlay open");
        check(!scope.consumeElementReferenceAuthorization(),
                "overlay authorization is one-shot");

        scope.updateFromUserText("播放 Apple Music 音樂");
        check(!scope.consumeElementReferenceAuthorization(),
                "ordinary task never grants element overlay");

        scope.updateFromUserText("element_reference:open");
        check(!scope.consumeElementReferenceAuthorization(),
                "model marker text is not user authorization");

        scope.updateFromUserText("Show clickable elements");
        check(scope.consumeElementReferenceAuthorization(),
                "English explicit user request grants overlay");

        scope.updateFromUserText("顯示元素");
        scope.updateFromUserText("打開地圖");
        check(!scope.consumeElementReferenceAuthorization(),
                "new user turn replaces stale overlay authorization");

        System.out.println(
                "PASS UserActionScopeTest: " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
