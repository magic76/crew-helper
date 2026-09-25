package com.crewpocket.helper;

public final class LiveHumanTurnBoundaryTest {
    private static int checks;

    public static void main(String[] args) {
        LiveHumanTurnBoundary boundary =
                new LiveHumanTurnBoundary();

        LiveHumanTurnBoundary.Resolution first =
                boundary.resolve(
                        "搜尋大皇宮",
                        1_000L,
                        true,
                        0L,
                        0L,
                        -1L,
                        "IN_PROGRESS",
                        false,
                        false);
        check(first.decision
                        == LiveHumanTurnBoundary.Decision
                                .BIND_CURRENT_GENERATION,
                "tool-first transcript binds current generation");

        LiveHumanTurnBoundary.Resolution second =
                boundary.resolve(
                        "導航過去",
                        2_000L,
                        true,
                        0L,
                        0L,
                        0L,
                        "IN_PROGRESS",
                        false,
                        false);
        check(second.decision
                        == LiveHumanTurnBoundary.Decision
                                .MERGE_CURRENT_SEGMENT,
                "same live interaction merges next finalized segment");
        check(second.effectiveText.contains("搜尋大皇宮")
                        && second.effectiveText.contains("導航過去"),
                "merged segment keeps the whole human goal");

        boundary.observeServerState(
                true, "IDLE", false);
        boundary.noteModelSpeech();
        LiveHumanTurnBoundary.Resolution later =
                boundary.resolve(
                        "打開相機",
                        9_000L,
                        true,
                        0L,
                        0L,
                        0L,
                        "IDLE",
                        false,
                        false);
        check(later.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "new utterance after idle spoken turn supersedes");

        boundary.forceNewTurn("搜尋大皇宮", 20_000L);
        LiveHumanTurnBoundary.Resolution cumulative =
                boundary.resolve(
                        "搜尋大皇宮導航過去",
                        20_600L,
                        true,
                        5L,
                        5L,
                        5L,
                        "",
                        false,
                        false);
        check(cumulative.decision
                        == LiveHumanTurnBoundary.Decision
                                .MERGE_CURRENT_SEGMENT,
                "cumulative finalized transcript does not create new intent");
        check("搜尋大皇宮導航過去".equals(cumulative.effectiveText),
                "longer cumulative transcript replaces shorter segment");

        boundary.forceNewTurn("搜尋大皇宮", 30_000L);
        LiveHumanTurnBoundary.Resolution correction =
                boundary.resolve(
                        "不是大皇宮，改成臥佛寺",
                        31_000L,
                        true,
                        8L,
                        8L,
                        8L,
                        "IN_PROGRESS",
                        false,
                        false);
        check(correction.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "explicit correction retains human takeover authority");

        boundary.forceNewTurn("搜尋大皇宮", 35_000L);
        LiveHumanTurnBoundary.Resolution naturalCorrection =
                boundary.resolve(
                        "你做錯了，我要的是臥佛寺",
                        36_000L,
                        true,
                        8L,
                        8L,
                        8L,
                        "IN_PROGRESS",
                        false,
                        false);
        check(naturalCorrection.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "natural-language correction immediately supersedes active task");

        boundary.forceNewTurn("搜尋大皇宮", 40_000L);
        LiveHumanTurnBoundary.Resolution lateActiveSpeech =
                boundary.resolve(
                        "打開相機",
                        45_000L,
                        true,
                        9L,
                        9L,
                        9L,
                        "IN_PROGRESS",
                        false,
                        false);
        check(lateActiveSpeech.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "server IN_PROGRESS cannot merge unrelated speech forever");

        boundary.forceNewTurn("搜尋大皇宮", 50_000L);
        LiveHumanTurnBoundary.Resolution interrupted =
                boundary.resolve(
                        "停止",
                        50_500L,
                        true,
                        10L,
                        10L,
                        10L,
                        "IN_PROGRESS",
                        false,
                        true);
        check(interrupted.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "server interruption always creates a new human turn");

        System.out.println(
                "PASS LiveHumanTurnBoundaryTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
