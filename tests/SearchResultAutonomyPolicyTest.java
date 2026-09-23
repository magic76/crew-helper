package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.List;

public final class SearchResultAutonomyPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        List<SearchResultAutonomyPolicy.Candidate> palace =
                new ArrayList<SearchResultAutonomyPolicy.Candidate>();
        palace.add(new SearchResultAutonomyPolicy.Candidate(
                "曼谷大皇宮", false, false));
        palace.add(new SearchResultAutonomyPolicy.Candidate(
                "大皇宮附近餐廳", false, false));

        SearchResultAutonomyPolicy.Decision d =
                SearchResultAutonomyPolicy.decide(
                        "大皇宮", "NAVIGATE", palace);
        check(d.autoSelect, "contained Maps result should auto-select");
        check(d.index == 0, "Maps rank should break equal-confidence ties");

        List<SearchResultAutonomyPolicy.Candidate> exact =
                new ArrayList<SearchResultAutonomyPolicy.Candidate>();
        exact.add(new SearchResultAutonomyPolicy.Candidate(
                "random place", false, false));
        exact.add(new SearchResultAutonomyPolicy.Candidate(
                "Grand Palace", true, true));
        d = SearchResultAutonomyPolicy.decide(
                "Grand Palace", "NAVIGATE", exact);
        check(d.autoSelect && d.index == 1,
                "unique exact candidate may outrank first visual result");

        List<SearchResultAutonomyPolicy.Candidate> weak =
                new ArrayList<SearchResultAutonomyPolicy.Candidate>();
        weak.add(new SearchResultAutonomyPolicy.Candidate(
                "Central Hotel", false, false));
        weak.add(new SearchResultAutonomyPolicy.Candidate(
                "Central Cafe", false, false));
        d = SearchResultAutonomyPolicy.decide(
                "中央公園", "NAVIGATE", weak);
        check(!d.autoSelect, "unmatched results still require user choice");

        d = SearchResultAutonomyPolicy.decide(
                "大皇宮", "PAYMENT", palace);
        check(!d.autoSelect,
                "high-risk continuation never inherits search autonomy");

        System.out.println(
                "PASS SearchResultAutonomyPolicyTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
