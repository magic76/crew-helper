package com.crewpocket.helper;

public final class LivePromptTest {
    private static int checks;

    public static void main(String[] args) {
        check(LivePrompt.CORE.length() <= 5000,
                "core prompt should stay compact");
        check(LivePrompt.CORE.contains("MODEL RUNTIME CONTRACT"),
                "core prompt must explain the canonical contract");
        check(LivePrompt.CORE.contains("action.state")
                        && LivePrompt.CORE.contains("goal.state"),
                "prompt must separate action state from goal state");
        check(LivePrompt.CORE.contains("goal.requiredTool"),
                "prompt must honor exact Runtime-required tool hints");
        check(!LivePrompt.CORE.contains("get_selected_region"),
                "core prompt must not reference hidden selected-region tools");
        check(!LivePrompt.CORE.contains("taskState=")
                        && !LivePrompt.CORE.contains("nextRequirement="),
                "prompt must not teach internal Runtime state languages");
        check(LivePrompt.CORE.contains("take_screenshot"),
                "visual fallback mentioned by prompt must be model-facing");

        System.out.println(
                "LivePromptTest passed " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
