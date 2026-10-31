package com.crewpocket.helper;

/**
 * Allowlisted Gemini Live session modes. A selected model is captured at
 * WebSocket setup, never dynamically injected into an ongoing session.
 *
 * This policy is Android-free so its compatibility contract can be tested
 * with the existing Java runtime replay suite.
 */
final class LiveModelSelection {
    static final String STANDARD = "standard";
    static final String EXTENDED_LOW = "extended_low";
    static final String EXTENDED_MEDIUM = "extended_medium";
    static final String DUAL_ENGINE = "dual_engine";

    static final String STANDARD_MODEL = "gemini-3.8-live";
    static final String EXTENDED_MODEL = "gemini-3.8-live-extended-thinking";

    static final class Config {
        final String id;
        final String model;
        final String thinkingLevel;
        final String label;

        Config(String id, String model, String thinkingLevel, String label) {
            this.id = id;
            this.model = model;
            this.thinkingLevel = thinkingLevel;
            this.label = label;
        }

        boolean usesThinking() { return !thinkingLevel.isEmpty(); }
        boolean usesBackgroundPlanner() { return DUAL_ENGINE.equals(id); }
    }

    private static volatile Config lastRequestedSetup;

    private LiveModelSelection() {}

    /** Fail closed to standard Live for old, missing or invalid preferences. */
    static Config resolve(String savedId) {
        if (DUAL_ENGINE.equals(savedId)) {
            return new Config(DUAL_ENGINE, STANDARD_MODEL, "",
                    "Standard + 3.8 Extended Thinking (experimental)");
        }
        if (EXTENDED_LOW.equals(savedId)) {
            return new Config(EXTENDED_LOW, EXTENDED_MODEL, "LOW",
                    "3.8 Extended Thinking · Low");
        }
        if (EXTENDED_MEDIUM.equals(savedId)) {
            return new Config(EXTENDED_MEDIUM, EXTENDED_MODEL, "MEDIUM",
                    "3.8 Extended Thinking · Medium");
        }
        return new Config(STANDARD, STANDARD_MODEL, "",
                "3.8 Live · Standard");
    }

    static synchronized void noteSetupRequested(Config config) {
        lastRequestedSetup = config;
    }

    static Config lastRequestedSetup() {
        return lastRequestedSetup;
    }

    static String lastRequestedSetupSummary() {
        Config value = lastRequestedSetup;
        return value == null ? "not yet created in this process"
                : value.model + " · thinking="
                    + (value.usesThinking() ? value.thinkingLevel : "OFF");
    }
}
