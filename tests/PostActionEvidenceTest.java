package com.crewpocket.helper;

public final class PostActionEvidenceTest {
    public static void main(String[] args) {
        check(PostActionEvidence.usable(true, "AUTO_AFTER_ACTION", false), "normal observed action");
        check(!PostActionEvidence.usable(true, "AUTO_AFTER_ACTION", true), "Maps pending outcome must still require inspection");
        check(!PostActionEvidence.usable(false, "AUTO_AFTER_ACTION", false), "stale screen");
        check(!PostActionEvidence.usable(true, "EXPLICIT_INSPECT", false), "wrong evidence source");
        check(!PostActionEvidence.usable(false, "", true), "missing screen with pending outcome");
        check(!PostActionEvidence.usable(true, null, false), "missing source");
        check(LivePrompt.CORE.contains("verification=PENDING"), "model told about pending outcome");
        System.out.println("PASS: 7 post-action evidence checks");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
