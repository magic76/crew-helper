package com.crewpocket.helper;

public final class HelperCoachTimeTest {
    public static void main(String[] args) {
        check(HelperCoachTime.parseMillis("1800000000123") == 1_800_000_000_123L,
                "epoch milliseconds parse");
        check(HelperCoachTime.parseMillis("1800000000") == 1_800_000_000_000L,
                "epoch seconds compatibility");
        check(HelperCoachTime.parseMillis("2027-01-15T08:00:00Z") > 0L,
                "ISO UTC parse");
        check(HelperCoachTime.parseMillis("not-a-time") < 0L,
                "invalid time rejected");
        check(HelperCoachTime.withinClockSkew(1_800_000_000_000L,
                        1_800_000_010_000L, 60_000L),
                "timestamp inside skew accepted");
        check(!HelperCoachTime.withinClockSkew(Long.MIN_VALUE,
                        1_800_000_010_000L, 60_000L),
                "extreme negative timestamp cannot overflow skew check");
        System.out.println("HelperCoachTimeTest passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
