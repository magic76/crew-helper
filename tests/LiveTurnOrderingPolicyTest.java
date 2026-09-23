package com.crewpocket.helper;

public final class LiveTurnOrderingPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(
                LiveTurnOrderingPolicy.decide(
                        true, 5L, 5L, false)
                        == LiveTurnOrderingPolicy.Decision.BYPASS_INTERNAL,
                "Runtime internal directive bypasses user transcript barrier");

        check(
                LiveTurnOrderingPolicy.decide(
                        false, 5L, 5L, true)
                        == LiveTurnOrderingPolicy.Decision.USE_QUEUED_GENERATION,
                "open finalized generation may execute immediately");

        check(
                LiveTurnOrderingPolicy.decide(
                        false, 5L, 6L, false)
                        == LiveTurnOrderingPolicy.Decision.USE_NEXT_FINALIZED_GENERATION,
                "already-arrived next finalized turn reconciles generation");

        check(
                LiveTurnOrderingPolicy.decide(
                        false, 5L, 5L, false)
                        == LiveTurnOrderingPolicy.Decision.WAIT_FOR_FINALIZED,
                "closed previous generation cannot authorize a new tool frame");

        check(
                LiveTurnOrderingPolicy.decide(
                        false, 5L, 4L, false)
                        == LiveTurnOrderingPolicy.Decision.WAIT_FOR_FINALIZED,
                "missing finalized input waits instead of guessing");

        System.out.println(
                "PASS LiveTurnOrderingPolicyTest: " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
