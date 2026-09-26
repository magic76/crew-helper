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
        check(correction.usedMemoryIds.size() == 2,
                "conservative correction keeps both injected memories causal");
        check(correction.learnedMemoryIds.isEmpty(),
                "no learned receipt exists yet");
        check(correction.injectionCount == 1,
                "injection count is retained");

        // Deliberate conservative tradeoff: when two memories were injected
        // into one task and the user says the result was wrong, both stay in
        // the causal set. We do not add fragile culprit attribution.
        check(correction.usedMemoryIds.contains("m1")
                        && correction.usedMemoryIds.contains("m2"),
                "both injected memories remain attributable");

        check(trace.consumeRecentCorrection(now + 3_000L).isEmpty(),
                "same correction cannot penalize twice");

        trace.recordLearned(
                "task-learn",
                8L,
                "learned-1",
                now);
        RefinedMemoryUseTrace.Snapshot learnedCorrection =
                trace.consumeRecentCorrection(now + 1_000L);
        check(learnedCorrection.usedMemoryIds.isEmpty(),
                "first-learning correction does not require prior injection");
        check(learnedCorrection.learnedMemoryIds.size() == 1
                        && learnedCorrection.learnedMemoryIds.contains(
                                "learned-1"),
                "freshly learned memory id is recoverable");

        trace.record(
                "task-2",
                9L,
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
