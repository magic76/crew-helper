package com.crewpocket.helper;

/**
 * Pure, deterministic risk budget for the experimental background display.
 * Independent of AgentRuntimeV2's foreground transaction counters.
 */
final class BackgroundPhoneSessionPolicy {
    static final int MAX_INSPECTIONS = 18;
    static final int MAX_MUTATIONS = 10;
    static final int MAX_VISION_FRAMES = 3;
    private int inspections;
    private int mutations;
    private int visionFrames;
    private String unverifiedAction = "";
    private String unverifiedFingerprint = "";

    void reset() {
        inspections = 0;
        mutations = 0;
        visionFrames = 0;
        unverifiedAction = "";
        unverifiedFingerprint = "";
    }

    String preflight(String action) {
        if (("INSPECT".equals(action) || "VISION".equals(action))
                && inspections >= MAX_INSPECTIONS)
            return "BACKGROUND_INSPECTION_BUDGET_EXHAUSTED";
        if ("VISION".equals(action) && visionFrames >= MAX_VISION_FRAMES)
            return "BACKGROUND_VISION_BUDGET_EXHAUSTED";
        if (isMutation(action) && mutations >= MAX_MUTATIONS)
            return "BACKGROUND_MUTATION_BUDGET_EXHAUSTED";
        return "";
    }

    void recordInspection() { inspections++; }
    void recordMutation() { mutations++; }
    void recordVision() { visionFrames++; }

    /**
     * If a previous mutation was dispatched but no evidence of change was
     * observed, do not repeat exactly the same action on the same screen.
     * Reinspection or a different action is required.
     */
    boolean blocksRepeat(String actionKey, String observedFingerprint) {
        return !unverifiedAction.isEmpty()
                && unverifiedAction.equals(actionKey)
                && !unverifiedFingerprint.isEmpty()
                && unverifiedFingerprint.equals(observedFingerprint);
    }

    void recordOutcome(String actionKey, String beforeFingerprint, boolean changed) {
        if (changed) {
            unverifiedAction = "";
            unverifiedFingerprint = "";
        } else {
            unverifiedAction = actionKey == null ? "" : actionKey;
            unverifiedFingerprint = beforeFingerprint == null ? "" : beforeFingerprint;
        }
    }

    void onObservedScreen(String fingerprint) {
        if (!unverifiedFingerprint.isEmpty()
                && fingerprint != null
                && !unverifiedFingerprint.equals(fingerprint)) {
            unverifiedAction = "";
            unverifiedFingerprint = "";
        }
    }

    static boolean isMutation(String action) {
        return "TAP".equals(action) || "VISUAL_TAP".equals(action)
                || "SCROLL".equals(action) || "TYPE".equals(action);
    }

    static String actionKey(String action, String element, String direction, String text) {
        // Never retain raw typed text in the replay signature.
        return safe(action) + "/" + safe(element) + "/" + safe(direction)
                + "/" + safe(text).hashCode();
    }

    int inspectionCount() { return inspections; }
    int mutationCount() { return mutations; }
    int visionCount() { return visionFrames; }

    private static String safe(String value) { return value == null ? "" : value; }
}
