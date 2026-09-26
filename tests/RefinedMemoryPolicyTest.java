package com.crewpocket.helper;

import java.util.Arrays;

public final class RefinedMemoryPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        String deng = RefinedMemoryPolicy.patternFor(
                "MEDIA:PLAY",
                "播放鄧紫棋",
                Arrays.asList(
                        step("search_current_app", ""),
                        step("tap_screen", "鄧紫棋"),
                        step("tap_screen", "播放")));
        String jay = RefinedMemoryPolicy.patternFor(
                "MEDIA:PLAY",
                "播放周杰倫",
                Arrays.asList(
                        step("search_current_app", ""),
                        step("tap_screen", "周杰倫"),
                        step("tap_screen", "Play")));

        check("SEARCH_QUERY > TAP_GOAL_ENTITY > TAP_PLAY_CONTROL"
                        .equals(deng),
                "media procedure is abstracted");
        check(deng.equals(jay),
                "different artist values merge into the same pattern");
        check(!deng.contains("鄧紫棋") && !jay.contains("周杰倫"),
                "raw goal entity never survives persisted pattern");

        String navigation = RefinedMemoryPolicy.patternFor(
                "NAVIGATION:START",
                "導航到曼谷大皇宮",
                Arrays.asList(
                        step("search_current_app", ""),
                        step("tap_screen", "曼谷大皇宮"),
                        step("tap_screen", "開始")));
        check("SEARCH_QUERY > TAP_GOAL_ENTITY > TAP_TERMINAL_CONTROL"
                        .equals(navigation),
                "navigation procedure is abstracted generically");

        check(RefinedMemoryPolicy.STATE_CANDIDATE.equals(
                        RefinedMemoryPolicy.stateFor(1, 0)),
                "single success stays candidate");
        check(RefinedMemoryPolicy.STATE_VERIFIED.equals(
                        RefinedMemoryPolicy.stateFor(3, 0)),
                "three compatible successes verify");
        check(RefinedMemoryPolicy.STATE_TRUSTED.equals(
                        RefinedMemoryPolicy.stateFor(6, 0)),
                "six clean successes become trusted");
        check(RefinedMemoryPolicy.STATE_STALE.equals(
                        RefinedMemoryPolicy.stateFor(2, 3)),
                "repeated failures stale a weak memory");
        check(!RefinedMemoryPolicy.isInjectable(
                        RefinedMemoryPolicy.STATE_CANDIDATE),
                "candidate memory is not model context");
        check(RefinedMemoryPolicy.isInjectable(
                        RefinedMemoryPolicy.STATE_VERIFIED),
                "verified memory can enter model context");

        check(!RefinedMemoryPolicy.isInjectable(
                        RefinedMemoryPolicy.STATE_SUSPECT),
                "suspect memory is quarantined from model context");

        double early = RefinedMemoryPolicy.confidenceFor(1, 0);
        double mature = RefinedMemoryPolicy.confidenceFor(6, 0);
        check(mature > early,
                "confidence increases with repeated success");

        int exact = RefinedMemoryPolicy.relevanceScore(
                RefinedMemoryPolicy.STATE_VERIFIED,
                0.90d,
                true,
                true,
                System.currentTimeMillis(),
                System.currentTimeMillis());
        int wrongScope = RefinedMemoryPolicy.relevanceScore(
                RefinedMemoryPolicy.STATE_VERIFIED,
                0.99d,
                false,
                true,
                System.currentTimeMillis(),
                System.currentTimeMillis());
        check(exact > 0 && wrongScope == Integer.MIN_VALUE,
                "only exact goal scope is injected");

        check(!RefinedMemoryPolicy.isEligibleScope("MESSAGE:SEND"),
                "message send is excluded from automatic procedure memory");

        System.out.println(
                "RefinedMemoryPolicyTest passed "
                        + checks + " checks");
    }

    private static RefinedMemoryPolicy.Step step(
            String tool,
            String target) {
        return new RefinedMemoryPolicy.Step(tool, target);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
