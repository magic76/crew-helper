package com.crewpocket.helper;

/** Pure Java regression coverage for the Gemini Live model selector. */
public final class LiveModelSelectionTest {
    private static int checks;

    public static void main(String[] args) {
        LiveModelSelection.Config standard = LiveModelSelection.resolve(null);
        expect(LiveModelSelection.STANDARD.equals(standard.id),
                "missing preference defaults to standard");
        expect(LiveModelSelection.STANDARD_MODEL.equals(standard.model),
                "standard model ID");
        expect(!standard.usesThinking() && standard.thinkingLevel.isEmpty(),
                "standard MUST omit thinkingConfig");

        LiveModelSelection.Config invalid =
                LiveModelSelection.resolve("models/gemini-2.0");
        expect(LiveModelSelection.STANDARD.equals(invalid.id),
                "unknown model cannot be injected through preferences");
        expect(!LiveModelSelection.resolve("").usesThinking(),
                "empty preference defaults to standard");

        LiveModelSelection.Config dual =
                LiveModelSelection.resolve(LiveModelSelection.DUAL_ENGINE);
        expect(LiveModelSelection.DUAL_ENGINE.equals(dual.id),
                "dual engine must be explicitly selected");
        expect(LiveModelSelection.STANDARD_MODEL.equals(dual.model),
                "dual engine always uses Standard Live for tools");
        expect(!dual.usesThinking() && dual.thinkingLevel.isEmpty(),
                "dual engine MUST omit Live thinkingConfig");
        expect(dual.usesBackgroundPlanner() && !standard.usesBackgroundPlanner(),
                "only dual mode activates second model");

        LiveModelSelection.Config low =
                LiveModelSelection.resolve(LiveModelSelection.EXTENDED_LOW);
        expect(LiveModelSelection.EXTENDED_MODEL.equals(low.model),
                "extended LOW model ID");
        expect(low.usesThinking() && "LOW".equals(low.thinkingLevel),
                "extended LOW includes correct thinking level");

        LiveModelSelection.Config medium =
                LiveModelSelection.resolve(LiveModelSelection.EXTENDED_MEDIUM);
        expect(LiveModelSelection.EXTENDED_MODEL.equals(medium.model),
                "extended MEDIUM model ID");
        expect(medium.usesThinking() && "MEDIUM".equals(medium.thinkingLevel),
                "extended MEDIUM includes correct thinking level");

        expect(LiveModelSelection.lastRequestedSetup() == null,
                "no setup is reported before an actual buildSetup");
        LiveModelSelection.noteSetupRequested(low);
        expect(LiveModelSelection.lastRequestedSetup() == low
                        && LiveModelSelection.lastRequestedSetupSummary()
                                .contains("thinking=LOW"),
                "last requested setup reflects selected model");
        LiveModelSelection.noteSetupRequested(standard);
        expect(LiveModelSelection.lastRequestedSetup() == standard
                        && LiveModelSelection.lastRequestedSetupSummary()
                                .contains("thinking=OFF"),
                "last setup diagnostic differentiates standard from extended");

        System.out.println("PASS LiveModelSelectionTest: " + checks + " checks");
    }

    private static void expect(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
