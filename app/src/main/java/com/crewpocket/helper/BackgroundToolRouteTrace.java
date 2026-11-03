package com.crewpocket.helper;

/** Bounded, in-process routing evidence; never stores transcripts or App names. */
final class BackgroundToolRouteTrace {
    private static int redirectCount, mismatchCount, disabledCount, directCount;
    private static String last = "NONE", received = "NONE", expected = "NONE";

    private BackgroundToolRouteTrace() {}

    static synchronized void record(BackgroundToolRoutePolicy.Decision decision,
                                    String tool, String action) {
        if (decision == null || decision == BackgroundToolRoutePolicy.Decision.FOREGROUND)
            return;
        last = decision.name();
        received = BackgroundToolRoutePolicy.safeReceivedTool(tool)
                + "." + BackgroundToolRoutePolicy.safeReceivedAction(action);
        expected = "background_phone."
                + BackgroundToolRoutePolicy.expectedAction(tool, action);
        switch (decision) {
            case REDIRECT_BACKGROUND_OPEN: redirectCount++; break;
            case BLOCK_BACKGROUND_ROUTE_MISMATCH: mismatchCount++; break;
            case BLOCK_BACKGROUND_DISABLED: disabledCount++; break;
            case BACKGROUND_TOOL: directCount++; break;
            default: break;
        }
    }

    static synchronized String report() {
        return "Background tool routing (current process): redirect=" + redirectCount
                + " mismatch=" + mismatchCount + " disabled=" + disabledCount
                + " correct=" + directCount + "\nLast: " + last
                + " · expected=" + expected + " · actual=" + received;
    }
}
