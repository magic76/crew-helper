package com.crewpocket.helper;

public final class VisualTapLeaseTest {
    private static int checks;

    public static void main(String[] args) {
        VisualTapLease lease = new VisualTapLease();
        VisualTapLease.Snapshot snap =
                lease.arm("com.example.music", "fp-a", 1000L);
        check(snap.available(), "fresh lease available");

        check(lease.validateAndConsume(
                        snap.id,
                        "com.example.music",
                        "fp-a",
                        500,
                        600,
                        1500L).allowed,
                "matching fresh screen may consume lease");

        check(!lease.validateAndConsume(
                        snap.id,
                        "com.example.music",
                        "fp-a",
                        500,
                        600,
                        1600L).allowed,
                "lease is one-shot");

        snap = lease.arm("com.example.music", "fp-b", 2000L);
        VisualTapLease.Validation changed = lease.validateAndConsume(
                snap.id,
                "com.example.music",
                "fp-c",
                400,
                400,
                2200L);
        check(!changed.allowed
                        && "VISUAL_TAP_SCREEN_CHANGED".equals(changed.code),
                "changed screen rejects stale coordinates");

        snap = lease.arm("com.example.music", "fp-d", 3000L);
        VisualTapLease.Validation expired = lease.validateAndConsume(
                snap.id,
                "com.example.music",
                "fp-d",
                400,
                400,
                3000L + VisualTapLease.TTL_MS + 1L);
        check(!expired.allowed
                        && "VISUAL_TAP_LEASE_EXPIRED".equals(expired.code),
                "expired lease rejected");

        snap = lease.arm("com.example.music", "fp-e", 4000L);
        VisualTapLease.Validation badCoordinate = lease.validateAndConsume(
                snap.id,
                "com.example.music",
                "fp-e",
                1001,
                400,
                4200L);
        check(!badCoordinate.allowed
                        && "VISUAL_TAP_COORDINATE_INVALID".equals(
                                badCoordinate.code),
                "out of range coordinate rejected");

        System.out.println(
                "VisualTapLeaseTest passed " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
