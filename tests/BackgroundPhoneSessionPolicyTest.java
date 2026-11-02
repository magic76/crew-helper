package com.crewpocket.helper;

public final class BackgroundPhoneSessionPolicyTest {
    private static void assertTrue(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        BackgroundPhoneSessionPolicy session = new BackgroundPhoneSessionPolicy();
        assertTrue(session.preflight("INSPECT").isEmpty(), "fresh inspect");
        assertTrue(session.preflight("TAP").isEmpty(), "fresh mutation");
        String same = BackgroundPhoneSessionPolicy.actionKey("TAP", "button-play", "", "");
        String different = BackgroundPhoneSessionPolicy.actionKey("TAP", "button-next", "", "");
        session.recordMutation();
        session.recordOutcome(same, "screen-A", false);
        assertTrue(session.blocksRepeat(same, "screen-A"), "block unchanged repeat");
        assertTrue(!session.blocksRepeat(different, "screen-A"), "allow different action");
        assertTrue(!session.blocksRepeat(same, "screen-B"), "allow fresh screen");
        session.onObservedScreen("screen-B");
        assertTrue(!session.blocksRepeat(same, "screen-A"), "new screen clears replay");
        session.recordOutcome(same, "screen-A", true);
        assertTrue(!session.blocksRepeat(same, "screen-A"), "observed change clears replay");
        for (int i = 0; i < BackgroundPhoneSessionPolicy.MAX_VISION_FRAMES; i++)
            session.recordVision();
        assertTrue("BACKGROUND_VISION_BUDGET_EXHAUSTED".equals(
                session.preflight("VISION")), "bounded vision frames");
        assertTrue(BackgroundPhoneSessionPolicy.isMutation("VISUAL_TAP"),
                "visual tap counts as mutation");
        for (int i = 0; i < BackgroundPhoneSessionPolicy.MAX_INSPECTIONS; i++)
            session.recordInspection();
        assertTrue("BACKGROUND_INSPECTION_BUDGET_EXHAUSTED".equals(
                session.preflight("INSPECT")), "bound inspect loop");
        assertTrue(session.preflight("CLOSE").isEmpty(), "close always available");
        assertTrue(session.preflight("HANDOFF").isEmpty(), "handoff always available");
        for (int i = 1; i < BackgroundPhoneSessionPolicy.MAX_MUTATIONS; i++)
            session.recordMutation();
        assertTrue("BACKGROUND_MUTATION_BUDGET_EXHAUSTED".equals(
                session.preflight("TAP")), "bound tap loop");
        assertTrue("BACKGROUND_MUTATION_BUDGET_EXHAUSTED".equals(
                session.preflight("TYPE")), "bound type loop");
        assertTrue("BACKGROUND_MUTATION_BUDGET_EXHAUSTED".equals(
                session.preflight("SCROLL")), "bound scroll loop");
        assertTrue(session.preflight("STATUS").isEmpty(), "status always available");
        session.reset();
        assertTrue(session.preflight("INSPECT").isEmpty(), "reset observations");
        assertTrue(session.preflight("TYPE").isEmpty(), "reset mutations");
        assertTrue(session.inspectionCount() == 0 && session.mutationCount() == 0, "counters reset");
        assertTrue(!BackgroundPhoneSessionPolicy.actionKey("TYPE", "field", "", "hello")
                .contains("hello"), "no raw text in action signature");
        System.out.println("BackgroundPhoneSessionPolicyTest OK");
    }
}
