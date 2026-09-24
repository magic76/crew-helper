package com.crewpocket.helper;

public final class GoalIntentKeyTest {
    private static int checks;

    public static void main(String[] args) {
        expect("MEDIA:PLAY", GoalIntentKey.derive("去 Apple Music 搜尋鄧紫棋播放"));
        expect("NAVIGATION:START", GoalIntentKey.derive("搜尋大皇宮然後導航過去"));
        expect("MEDIA:PAUSE", GoalIntentKey.derive("pause"));
        expect("MEDIA:NEXT", GoalIntentKey.derive("下一首"));
        expect("SEARCH:RESULT", GoalIntentKey.derive("幫我搜尋大皇宮"));
        expect("MESSAGE:SEND", GoalIntentKey.derive("把這段訊息送出"));
        expect("TEXT:TYPE_ONLY", GoalIntentKey.derive("只輸入 123 不要送"));
        expect("", GoalIntentKey.derive("今天天氣如何"));
        System.out.println("GoalIntentKeyTest passed " + checks + " checks");
    }

    private static void expect(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
