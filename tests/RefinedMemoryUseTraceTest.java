package com.crewpocket.helper;

import java.util.Arrays;

public final class RefinedMemoryUseTraceTest {
    private static int checks;

    public static void main(String[] args) {
        RefinedMemoryUseTrace trace = new RefinedMemoryUseTrace();
        long now = 10_000L;

        check(
                !trace.alreadyInjected(
                        "task-1",
                        Arrays.asList("m1")),
                "fresh task has no injected memory");

        trace.record(
                "task-1",
                7L,
                Arrays.asList("m1", "m2"),
                now);

        check(
                trace.alreadyInjected(
                        "task-1",
                        Arrays.asList("m1")),
                "task remembers injected ids");
        check(
                !trace.alreadyInjected(
                        "task-1",
                        Arrays.asList("m3")),
                "new memory id may still be injected");

        RefinedMemoryUseTrace.Snapshot correction =
                trace.consumeRecentCorrection(now + 2_000L);
        check("task-1".equals(correction.taskId),
                "correction binds to source task");
        check(correction.memoryIds.size() == 2,
                "correction contains only used memories");
        check(correction.injectionCount == 1,
                "injection count is retained");
        check(trace.consumeRecentCorrection(now + 3_000L).isEmpty(),
                "same correction cannot penalize twice");

        trace.record(
                "task-2",
                8L,
                Arrays.asList("m3"),
                now);
        check(
                trace.consumeRecentCorrection(
                        now + RefinedMemoryUseTrace.CORRECTION_WINDOW_MS + 1L)
                        .isEmpty(),
                "expired trace cannot poison old memory");

        System.out.println(
                "RefinedMemoryUseTraceTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
