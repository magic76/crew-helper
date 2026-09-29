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
        LiveHumanTurnBoundary.Resolution distinctAfterPause =
                boundary.resolve(
                        "打開相機",
                        53_000L,
                        true,
                        10L,
                        10L,
                        10L,
                        "IN_PROGRESS",
                        false,
                        false);
        check(distinctAfterPause.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "distinct command after short pause must not be swallowed by old live turn");

        boundary.forceNewTurn("搜尋大皇宮", 60_000L);
        LiveHumanTurnBoundary.Resolution continuationAfterPause =
                boundary.resolve(
                        "然後導航過去",
                        63_000L,
                        true,
                        11L,
                        11L,
                        11L,
                        "IN_PROGRESS",
                        false,
                        false);
        check(continuationAfterPause.decision
                        == LiveHumanTurnBoundary.Decision.MERGE_CURRENT_SEGMENT,
                "explicit continuation may extend the active task after a longer pause");
        check(continuationAfterPause.effectiveText.contains("搜尋大皇宮")
                        && continuationAfterPause.effectiveText.contains("然後導航過去"),
                "continuation keeps prior and new goal text");

        boundary.forceNewTurn("播放鄧紫棋", 65_000L);
        LiveHumanTurnBoundary.Resolution operationDebounced =
                boundary.resolve(
                        "唯一",
                        68_000L,
                        true,
                        11L,
                        11L,
                        11L,
                        "IN_PROGRESS",
                        false,
                        false,
                        true);
        check(operationDebounced.decision
                        == LiveHumanTurnBoundary.Decision
                                .BIND_CURRENT_GENERATION,
                "active foreground operation debounces a nearby finalized fragment");
        check(operationDebounced.effectiveText.contains("播放鄧紫棋")
                        && operationDebounced.effectiveText.contains("唯一"),
                "operation debounce preserves both parts of the spoken goal");

        boundary.forceNewTurn("播放鄧紫棋", 68_500L);
        LiveHumanTurnBoundary.Resolution thinkingDebounced =
                boundary.resolve(
                        "唯一",
                        71_300L,
                        true,
                        11L,
                        11L,
                        11L,
                        "IN_PROGRESS",
                        false,
                        false,
                        false,
                        true);
        check(thinkingDebounced.decision
                        == LiveHumanTurnBoundary.Decision
                                .BIND_CURRENT_GENERATION,
                "active Agent thinking debounces a nearby finalized fragment after the tool has returned");
        check("ACTIVE_AGENT_THINK_GRACE".equals(
                        thinkingDebounced.reason),
                "Agent thinking grace exposes a deterministic diagnostic reason");

        boundary.forceNewTurn("播放鄧紫棋", 69_000L);
        LiveHumanTurnBoundary.Resolution operationTakeover =
                boundary.resolve(
                        "停止",
                        72_000L,
                        true,
                        11L,
                        11L,
                        11L,
                        "IN_PROGRESS",
                        false,
                        false,
                        true);
        check(operationTakeover.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "explicit takeover bypasses active-operation debounce");

        boundary.forceNewTurn("播放鄧紫棋", 72_500L);
        LiveHumanTurnBoundary.Resolution thinkingTakeover =
                boundary.resolve(
                        "不對，停止",
                        75_000L,
                        true,
                        11L,
                        11L,
                        11L,
                        "IN_PROGRESS",
                        false,
                        false,
                        false,
                        true);
        check(thinkingTakeover.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "explicit takeover bypasses Agent thinking grace");

        boundary.forceNewTurn("播放鄧紫棋", 73_000L);
        LiveHumanTurnBoundary.Resolution operationGraceExpired =
                boundary.resolve(
                        "打開相機",
                        77_000L,
                        true,
                        11L,
                        11L,
                        11L,
                        "IN_PROGRESS",
                        false,
                        false,
                        true);
        check(operationGraceExpired.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "active-operation debounce expires quickly for a genuine new command");

        boundary.forceNewTurn("播放鄧紫棋", 78_000L);
        LiveHumanTurnBoundary.Resolution thinkingGraceExpired =
                boundary.resolve(
                        "打開相機",
                        82_100L,
                        true,
                        11L,
                        11L,
                        11L,
                        "IN_PROGRESS",
                        false,
                        false,
                        false,
                        true);
        check(thinkingGraceExpired.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "Agent thinking grace expires after four seconds");

        boundary.forceNewTurn("搜尋大皇宮", 83_000L);
        LiveHumanTurnBoundary.Resolution interrupted =
                boundary.resolve(
                        "停止",
                        83_500L,
                        true,
                        12L,
                        12L,
                        12L,
                        "IN_PROGRESS",
                        false,
                        true);
        check(interrupted.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "server interruption always creates a new human turn");

        // 0134: Runtime may finish the first tool before Gemini emits the
        // cumulative finalized transcript. If the model has not spoken yet,
        // the related text is still the same human turn even without an active
        // task and must not reset the search transaction.
        boundary.forceNewTurn("搜尋林家花園", 100_000L);
        LiveHumanTurnBoundary.Resolution lateCumulative =
                boundary.resolve(
                        "搜尋林家花園",
                        103_200L,
                        false,
                        -1L,
                        13L,
                        13L,
                        "IDLE",
                        false,
                        false);
        check(lateCumulative.decision
                        == LiveHumanTurnBoundary.Decision.MERGE_CURRENT_SEGMENT,
                "late cumulative transcript before model speech stays same human turn");

        boundary.forceNewTurn("搜尋林家花園", 90_000L);
        boundary.noteModelSpeech();
        LiveHumanTurnBoundary.Resolution repeatedAfterReply =
                boundary.resolve(
                        "搜尋林家花園",
                        93_200L,
                        false,
                        -1L,
                        14L,
                        14L,
                        "IDLE",
                        false,
                        false);
        check(repeatedAfterReply.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "same words after model speech remain a genuine new human command when no pre-completion interim proves it was late");

        // A late final may arrive after Runtime has already completed the
        // tool-driven task. The pre-completion interim proves this final belongs
        // to the already-finished utterance, even if model speech happened.
        boundary.forceNewTurn("播放周杰倫", 110_000L);
        boundary.noteInterim("播放周杰倫", 110_200L);
        boundary.noteTaskCompleted(20L, 111_000L);
        boundary.noteModelSpeech();
        LiveHumanTurnBoundary.Resolution delayedFinalAfterDone =
                boundary.resolve(
                        "播放周杰倫",
                        112_300L,
                        false,
                        -1L,
                        20L,
                        20L,
                        "IDLE",
                        false,
                        false);
        check(delayedFinalAfterDone.decision
                        == LiveHumanTurnBoundary.Decision.MERGE_CURRENT_SEGMENT,
                "final derived from pre-completion interim must not start a ghost task after DONE");
        check("FINAL_FROM_PRE_COMPLETION_INTERIM".equals(
                        delayedFinalAfterDone.reason),
                "late final after DONE exposes a deterministic diagnostic reason");

        // If the human genuinely repeats the same command after completion,
        // the new interim timestamp is newer than completion and must remain a
        // new intent rather than being swallowed by dedupe.
        boundary.forceNewTurn("播放周杰倫", 120_000L);
        boundary.noteInterim("播放周杰倫", 120_200L);
        boundary.noteTaskCompleted(21L, 121_000L);
        boundary.noteModelSpeech();
        boundary.noteInterim("播放周杰倫", 122_000L);
        LiveHumanTurnBoundary.Resolution genuineRetryAfterDone =
                boundary.resolve(
                        "播放周杰倫",
                        122_200L,
                        false,
                        -1L,
                        21L,
                        21L,
                        "IDLE",
                        false,
                        false);
        check(genuineRetryAfterDone.decision
                        == LiveHumanTurnBoundary.Decision.NEW_INTENT,
                "fresh interim after DONE preserves a genuine immediate repeat command");

        System.out.println(
                "PASS LiveHumanTurnBoundaryTest: "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
