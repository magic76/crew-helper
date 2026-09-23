package com.crewpocket.helper;

public final class AgentTaskLifecycleClockTest {
    private static int checks;

    public static void main(String[] args) {
        long effective = AgentTaskLifecycleClock.effectiveStartedAt(
                1_000L, 20_000L, 50_000L, true, 80_000L);
        check(effective == 51_000L,
                "current external wait is excluded from active task time");

        long accumulated = AgentTaskLifecycleClock.accumulatedAfterResume(
                20_000L, 50_000L, 80_000L);
        check(accumulated == 50_000L,
                "resume permanently accumulates external wait duration");

        long resumedEffective = AgentTaskLifecycleClock.effectiveStartedAt(
                1_000L, accumulated, 0L, false, 90_000L);
        check(resumedEffective == 51_000L,
                "resumed task keeps the same active-time origin");

        System.out.println(
                "PASS AgentTaskLifecycleClockTest: " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
