package com.crewpocket.helper;

public final class DelegatedSendLeaseTest {
    private static int checks;

    public static void main(String[] args) {
        DelegatedSendLease lease = new DelegatedSendLease();
        lease.start("task-a", 7L, 2, 10);

        check(lease.isActive(), "lease starts active");
        check(lease.canSend("task-a"), "owning task may send");
        check(!lease.canSend("task-b"), "different task may not borrow lease");
        check(lease.recordSend("task-a"), "first send keeps bounded lease alive");
        check(lease.sentSends() == 1, "send count increments");
        check(!lease.recordSend("task-a"), "last allowed send exhausts lease");
        check(!lease.isActive(), "lease stops at max sends");

        lease.start("task-c", 8L, 3, 10);
        lease.revoke("HUMAN_TAKEOVER");
        check(!lease.canSend("task-c"), "revoked lease cannot send");

        System.out.println(
                "PASS DelegatedSendLeaseTest: " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
