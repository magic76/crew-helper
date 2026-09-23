package com.crewpocket.helper;

public final class LiveTurnCoordinatorTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        LiveTurnCoordinator coordinator = new LiveTurnCoordinator();

        LiveTurnCoordinator.FinalizedTurn empty = coordinator.latest();
        check(empty.generation == -1L, "initial generation");

        coordinator.onFinalizedUserTurn(1L, " 幫我送出 ");
        LiveTurnCoordinator.FinalizedTurn first = coordinator.latest();
        check(first.generation == 1L, "records finalized generation");
        check("幫我送出".equals(first.text), "trims finalized text");
        check(coordinator.isOperationalGenerationOpen(1L),
                "finalized user turn opens its operational generation");
        coordinator.closeOperationalGeneration(1L);
        check(!coordinator.isOperationalGenerationOpen(1L),
                "closed generation cannot authorize later tool frames");
        coordinator.openOperationalGeneration(1L);
        check(coordinator.isOperationalGenerationOpen(1L),
                "Runtime continuation can explicitly reopen generation");

        Thread producer = new Thread(() -> {
            try { Thread.sleep(40L); } catch (InterruptedException ignored) {}
            coordinator.onFinalizedUserTurn(2L, "下一句");
        });
        producer.start();

        long started = System.nanoTime();
        LiveTurnCoordinator.FinalizedTurn awaited =
                coordinator.awaitNextAfter(1L, 500L);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        producer.join();

        check(awaited.generation == 2L, "waits for next finalized turn");
        check("下一句".equals(awaited.text), "returns next finalized text");
        check(elapsedMs < 450L, "wakes on finalized event instead of polling timeout");

        started = System.nanoTime();
        LiveTurnCoordinator.FinalizedTurn timedOut =
                coordinator.awaitNextAfter(2L, 60L);
        elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        check(timedOut.generation == 2L, "timeout keeps latest turn");
        check(elapsedMs >= 40L, "timeout actually waits when no newer turn");

        coordinator.onFinalizedUserTurn(1L, "stale");
        check(coordinator.latest().generation == 2L, "ignores stale finalized turn");

        System.out.println("PASS LiveTurnCoordinatorTest: " + assertions + " checks");
    }
}
