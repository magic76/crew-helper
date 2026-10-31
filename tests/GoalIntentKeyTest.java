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
        expect("APP:OPEN", GoalIntentKey.derive("幫我開地圖"));
        expect("APP:OPEN", GoalIntentKey.derive("幫我開 Google Maps"));
        expect("APP:OPEN", GoalIntentKey.derive("把 Chrome 開起來"));
        expect("APP:OPEN", GoalIntentKey.derive("啟動 Apple Music"));
        expect("APP:OPEN", GoalIntentKey.derive("請開一下 YouTube"));
        expect("APP:OPEN", GoalIntentKey.derive("開 Google Maps"));
        expect("NAVIGATION:START", GoalIntentKey.derive("幫我開地圖開始導航"));
        expect("MEDIA:PLAY", GoalIntentKey.derive("幫我開 Apple Music 播放鄧紫棋"));
        expect("SEARCH:RESULT", GoalIntentKey.derive("開地圖搜尋大皇宮"));
        expect("", GoalIntentKey.derive("幫我開會"));
        expect("", GoalIntentKey.derive("Google Maps 開發計畫"));
        expect("", GoalIntentKey.derive("今天天氣如何"));
        check(GoalIntentKey.isStandaloneAppOpen("幫我開地圖"),
                "simple app-open request can finish");
        check(!GoalIntentKey.isStandaloneAppOpen("幫我開地圖然後看看附近"),
                "open followed by another task cannot finish early");
        check(!GoalIntentKey.isStandaloneAppOpen("開 Google Maps 順便幫我找停車位"),
                "open with secondary goal cannot finish early");
        check(!GoalIntentKey.isStandaloneAppOpen("啟動 Apple Music 再找我收藏的歌"),
                "open followed by music-library task cannot finish early");
        check(!GoalIntentKey.isStandaloneAppOpen("導航去大皇宮"),
                "navigation request is not standalone open");
        System.out.println("GoalIntentKeyTest passed " + checks + " checks");
    }

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }

    private static void expect(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
