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
        check(LivePrompt.CORE.contains("goal.intent"),
                "prompt must preserve bounded terminal intent");
        check(LivePrompt.CORE.contains("goal.requiredTool"),
                "prompt must honor exact Runtime-required tool hints");
        check(LivePrompt.CORE.contains("goal.requiredAction"),
                "prompt must honor exact semantic action hints");
        check(!LivePrompt.CORE.contains("get_selected_region"),
                "core prompt must not reference hidden selected-region tools");
        check(!LivePrompt.CORE.contains("taskState=")
                        && !LivePrompt.CORE.contains("nextRequirement="),
                "prompt must not teach internal Runtime state languages");
        check(!LivePrompt.CORE.contains("take_screenshot"),
                "core prompt should keep one visual observation path");
        check(LivePrompt.CORE.contains("fresh screenshot plus semantic fallback"),
                "inspect_ui should explain its complete visual evidence");

        System.out.println(
                "LivePromptTest passed " + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
