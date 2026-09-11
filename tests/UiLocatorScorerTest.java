package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.List;

public final class UiLocatorScorerTest {
    private static int assertions;
    private static void check(boolean v, String name) {
        assertions++;
        if (!v) throw new AssertionError(name);
    }

    private static UiNodeSnapshot node(String id, String actionKey, String elementId,
                                       String label, String viewId, String role,
                                       String hint, boolean clickable, boolean editable,
                                       boolean sensitive, int l, int t, int r, int b) {
        return new UiNodeSnapshot(id, actionKey, elementId,
                label, label, label, viewId, "android.widget.Button", role, hint, "",
                clickable, editable, true, true, sensitive,
                3, 0, l, t, r, b, 1080, 2400);
    }

    public static void main(String[] args) {
        List<UiNodeSnapshot> nodes = new ArrayList<UiNodeSnapshot>();
        nodes.add(node("a", "a", "e_a", "搜尋紀錄", "", "text", "search",
                false, false, false, 80, 500, 500, 600));
        nodes.add(node("b", "b", "e_b", "搜尋", "com.app:id/search", "icon_button", "search",
                true, false, false, 900, 40, 1040, 180));
        UiLocatorScorer.Ranking search = UiLocatorScorer.rank(nodes,
                UiTargetSpec.builder().actionKind(UiTargetSpec.ActionKind.TAP)
                        .label("搜尋").semanticHint("search").build(), null);
        check(search.decision == UiLocatorScorer.Decision.AUTO, "search auto");
        check(search.best != null && "b".equals(search.best.node.instanceId), "search icon wins");
        check(search.best.confidence >= 0.90, "search confidence");

        List<UiNodeSnapshot> stale = new ArrayList<UiNodeSnapshot>();
        stale.add(node("next", "next", "new_element_id", "下一步", "com.app:id/next", "button", "next",
                true, false, false, 700, 2100, 1000, 2260));
        UiLocatorScorer.Ranking staleResult = UiLocatorScorer.rank(stale,
                UiTargetSpec.builder().actionKind(UiTargetSpec.ActionKind.TAP)
                        .elementId("old_element_id").label("下一步").semanticHint("next").build(), null);
        check(staleResult.decision == UiLocatorScorer.Decision.AUTO, "stale element falls back structurally");
        check(staleResult.best != null && staleResult.best.confidence >= 0.86, "stale structural confidence");

        List<UiNodeSnapshot> ambiguous = new ArrayList<UiNodeSnapshot>();
        ambiguous.add(node("n1", "n1", "e1", "下一步", "", "button", "next",
                true, false, false, 50, 2000, 450, 2200));
        ambiguous.add(node("n2", "n2", "e2", "下一步", "", "button", "next",
                true, false, false, 550, 2000, 1030, 2200));
        UiLocatorScorer.Ranking ambiguousResult = UiLocatorScorer.rank(ambiguous,
                UiTargetSpec.builder().actionKind(UiTargetSpec.ActionKind.TAP)
                        .label("下一步").semanticHint("next").build(), null);
        check(ambiguousResult.decision == UiLocatorScorer.Decision.AMBIGUOUS,
                "same-strength buttons ambiguous");

        UiNodeSnapshot composer = node("composer", "composer", "ec", "訊息", "composer", "text_field", "",
                false, true, false, 80, 2100, 850, 2260);
        List<UiNodeSnapshot> anchored = new ArrayList<UiNodeSnapshot>();
        anchored.add(node("left", "left", "el", "", "left_icon", "icon_button", "",
                true, false, false, 10, 2110, 70, 2240));
        anchored.add(node("right", "right", "er", "", "send_icon", "icon_button", "send",
                true, false, false, 880, 2110, 1040, 2260));
        UiLocatorScorer.Ranking anchoredResult = UiLocatorScorer.rank(anchored,
                UiTargetSpec.builder().actionKind(UiTargetSpec.ActionKind.TAP)
                        .semanticHint("send").relation("RIGHT_OF").build(), composer);
        check(anchoredResult.best != null && "right".equals(anchoredResult.best.node.instanceId),
                "anchor relation selects right control");

        List<UiNodeSnapshot> sensitive = new ArrayList<UiNodeSnapshot>();
        sensitive.add(node("pay", "pay", "ep", "確認付款", "confirm", "button", "confirm",
                true, false, true, 300, 1800, 800, 2050));
        UiLocatorScorer.Ranking sensitiveResult = UiLocatorScorer.rank(sensitive,
                UiTargetSpec.builder().actionKind(UiTargetSpec.ActionKind.TAP)
                        .label("確認付款").build(), null);
        check(sensitiveResult.decision == UiLocatorScorer.Decision.BLOCKED, "sensitive target blocked");

        System.out.println("PASS UiLocatorScorerTest: " + assertions + " checks");
    }
}
