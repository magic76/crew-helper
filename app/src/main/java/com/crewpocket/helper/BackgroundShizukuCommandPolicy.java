package com.crewpocket.helper;

/** Pure validation helpers for the narrow, shell-owned background Binder relay. */
final class BackgroundShizukuCommandPolicy {
    private BackgroundShizukuCommandPolicy() {}

    static boolean allowedOperation(String op) {
        return "status".equals(op) || "open".equals(op)
                || "preview".equals(op) || "tap".equals(op)
                || "swipe".equals(op) || "front".equals(op)
                || "close".equals(op);
    }

    /**
     * Never turn a stale or incomplete Live lease into a new display target.
     * Only manual UI commands with NEITHER identity field may be bound to
     * the current, already verified display by the shell process.
     */
    static boolean completeDisplayIdentity(boolean hasId, boolean hasPackage) {
        return hasId == hasPackage;
    }

    static boolean requiresDisplay(String op) {
        return "preview".equals(op) || "tap".equals(op)
                || "swipe".equals(op) || "front".equals(op)
                || "close".equals(op);
    }

    static boolean validCoordinate(int value) {
        return value >= 0 && value <= 8192;
    }

    static boolean validPackage(String name) {
        return name != null && name.length() < 180
                && name.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    }

    static boolean authorizedCaller(int serviceUid, int packageUid, int callerUid) {
        return serviceUid == 2000 && packageUid >= 10000
                && callerUid == packageUid;
    }
}
