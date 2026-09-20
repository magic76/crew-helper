package com.crewpocket.helper;

import java.util.ArrayList;

public final class AppPlaybookRelevanceTest {
    public static void main(String[] args) {
        chineseGoalRanksSearchRule();
        englishGoalRanksMatchingRule();
        unrelatedRulesAreNotInjected();
        maxThreeRules();
        System.out.println("AppPlaybookRelevanceTest passed");
    }

    private static void chineseGoalRanksSearchRule() {
        ArrayList<AppPlaybookRelevance.Entry> rules = new ArrayList<AppPlaybookRelevance.Entry>();
        rules.add(new AppPlaybookRelevance.Entry(0, "貼圖", "聊天室底部可以開啟貼圖面板", 10));
        rules.add(new AppPlaybookRelevance.Entry(1, "搜尋結果", "搜尋聯絡人後，點人物名稱進入聊天室", 20));
        ArrayList<Integer> ranked = AppPlaybookRelevance.rank(
                rules, "找老婆聊天室 搜尋聯絡人 SEARCH", 3);
        expect(1, ranked.get(0));
    }

    private static void englishGoalRanksMatchingRule() {
        ArrayList<AppPlaybookRelevance.Entry> rules = new ArrayList<AppPlaybookRelevance.Entry>();
        rules.add(new AppPlaybookRelevance.Entry(0, "Search result", "Tap the person name after contact search", 10));
        rules.add(new AppPlaybookRelevance.Entry(1, "Gallery", "Open the photo picker from the plus menu", 20));
        ArrayList<Integer> ranked = AppPlaybookRelevance.rank(
                rules, "search contact and open chat", 3);
        expect(0, ranked.get(0));
    }

    private static void unrelatedRulesAreNotInjected() {
        ArrayList<AppPlaybookRelevance.Entry> rules = new ArrayList<AppPlaybookRelevance.Entry>();
        rules.add(new AppPlaybookRelevance.Entry(0, "貼圖", "打開貼圖面板", 10));
        ArrayList<Integer> ranked = AppPlaybookRelevance.rank(
                rules, "調整系統亮度", 3);
        if (!ranked.isEmpty()) {
            throw new AssertionError("unrelated guidance should stay out");
        }
    }

    private static void maxThreeRules() {
        ArrayList<AppPlaybookRelevance.Entry> rules = new ArrayList<AppPlaybookRelevance.Entry>();
        for (int i = 0; i < 6; i++) {
            rules.add(new AppPlaybookRelevance.Entry(
                    i, "搜尋 " + i, "搜尋聯絡人後操作結果 " + i, i));
        }
        ArrayList<Integer> ranked = AppPlaybookRelevance.rank(
                rules, "搜尋聯絡人", 3);
        if (ranked.size() > 3) throw new AssertionError("too many rules");
    }

    private static void expect(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
