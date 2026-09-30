package com.crewpocket.helper;

public final class ExploreGestureLeaseTest {
    private static int checks;

    public static void main(String[] args) {
        ExploreGestureLease lease = new ExploreGestureLease();

        check(lease.interpret("往下", 1_000L).kind
                        == ExploreGestureLease.Kind.NONE,
                "direction does not bypass Gemini before a lease exists");

        lease.armFromSuccessfulGesture("forward", 2_000L);
        ExploreGestureLease.Command down =
                lease.interpret("再下", 3_000L);
        check(down.kind == ExploreGestureLease.Kind.SCROLL,
                "nearby direction uses Runtime fast path");
        check("forward".equals(down.semanticDirection),
                "down means reveal later/below content");
        check("normal".equals(down.distance),
                "plain direction uses normal distance");

        ExploreGestureLease.Command little =
                lease.interpret("再一點", 4_000L);
        check(little.kind == ExploreGestureLease.Kind.SCROLL,
                "little-more continuation reuses last direction");
        check("short".equals(little.distance),
                "little-more uses short distance");

        ExploreGestureLease.Command repeatLittle =
                lease.interpret("再下一點", 4_500L);
        check(repeatLittle.kind == ExploreGestureLease.Kind.SCROLL,
                "repeat-prefix direction stays inside the lease");
        check("forward".equals(repeatLittle.semanticDirection),
                "repeat-prefix direction preserves semantic content direction");
        check("short".equals(repeatLittle.distance),
                "repeat-prefix little command uses short distance");

        ExploreGestureLease.Command page =
                lease.interpret("上一頁", 5_000L);
        check("backward".equals(page.semanticDirection),
                "previous page reveals earlier/above content");
        check("page".equals(page.distance),
                "page command requests page distance");

        ExploreGestureLease.Command preview =
                lease.previewActive("再右一點", 5_500L);
        check(preview.kind == ExploreGestureLease.Kind.SCROLL,
                "active lease can preview strict interim direction commands");
        check("right".equals(preview.semanticDirection),
                "preview keeps semantic direction");
        check("short".equals(preview.distance),
                "preview keeps distance without consuming");
        lease.acceptPreviewed(preview, 5_600L);
        check("right".equals(lease.lastSemanticDirection()),
                "accepted interim preview updates continuation direction");

        check(lease.isExplicitMode(5_601L),
                "interim preview must preserve explicit explore mode");

        ExploreGestureLease.Command unrelatedPreview =
                lease.previewActive("播放周杰倫", 5_700L);
        check(unrelatedPreview.kind == ExploreGestureLease.Kind.NONE,
                "non-explore interim preview does not become a gesture");
        check(lease.isActive(5_701L),
                "non-explore interim preview must not clear the lease before final intent");

        ExploreGestureLease.Command inspect =
                lease.interpret("看一下", 6_000L);
        check(inspect.kind == ExploreGestureLease.Kind.PASS_TO_MODEL,
                "inspect request hands control back to Gemini");
        check(lease.isActive(6_001L),
                "inspect handoff keeps exploration lease alive");

        ExploreGestureLease.Command unrelated =
                lease.interpret("播放周杰倫", 7_000L);
        check(unrelated.kind == ExploreGestureLease.Kind.NONE,
                "unrelated task falls through to normal Agent handling");
        check(!lease.isActive(7_001L),
                "unrelated task closes exploration lease");

        ExploreGestureLease.Command start =
                lease.interpret("進入探索模式", 8_000L);
        check(start.kind == ExploreGestureLease.Kind.START,
                "explicit start arms exploration mode");
        check(lease.isActive(8_001L),
                "explicit start has active lease");

        check(lease.isActive(
                        8_000L + ExploreGestureLease.TTL_MS + 1L),
                "explicit explore mode outlives the short implicit lease");
        check(lease.isExplicitMode(
                        8_000L + ExploreGestureLease.TTL_MS + 1L),
                "explicit mode stays identifiable for listening policy");
        check(!lease.isActive(
                        8_000L
                                + ExploreGestureLease.EXPLICIT_IDLE_TTL_MS
                                + 1L),
                "explicit mode still expires after a long idle window");

        lease.interpret("進入探索模式", 8_500L);
        ExploreGestureLease.Command right =
                lease.interpret("右一點", 9_000L);
        check(right.kind == ExploreGestureLease.Kind.SCROLL,
                "horizontal short command is supported");
        check("right".equals(right.semanticDirection),
                "right means reveal content on the right");
        check("short".equals(right.distance),
                "horizontal little command uses short distance");

        ExploreGestureLease.Command stop =
                lease.interpret("結束探索", 10_000L);
        check(stop.kind == ExploreGestureLease.Kind.STOP,
                "explicit stop is Runtime-owned");
        check(!lease.isActive(10_001L),
                "stop clears the lease");

        lease.armFromSuccessfulGesture("left", 20_000L);
        check(!lease.isActive(
                        20_000L + ExploreGestureLease.TTL_MS + 1L),
                "lease expires after fifteen seconds");

        System.out.println(
                "PASS ExploreGestureLeaseTest: " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
