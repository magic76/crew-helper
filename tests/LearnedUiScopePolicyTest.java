package com.crewpocket.helper;

public final class LearnedUiScopePolicyTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        check(LearnedUiScopePolicy.affinityBonus("chat-a", "chat-a") > 0,
                "same structure receives positive affinity");
        check(LearnedUiScopePolicy.affinityBonus("chat-a", "search") < 0,
                "different structure is penalized");
        check(LearnedUiScopePolicy.affinityBonus("", "chat-a") == 0,
                "legacy rule remains neutral");

        check(LearnedUiScopePolicy.sameActionSlot(
                        "com.line", "COMPOSER_SEND", "HAS_TEXT", "chat-a",
                        "com.line", "COMPOSER_SEND", "HAS_TEXT", "chat-a"),
                "same structural composer replaces old teaching");

        check(!LearnedUiScopePolicy.sameActionSlot(
                        "com.line", "COMPOSER_SEND", "HAS_TEXT", "chat-a",
                        "com.line", "COMPOSER_SEND", "HAS_TEXT", "search"),
                "different screen structures keep separate mappings");

        check(LearnedUiScopePolicy.sameActionSlot(
                        "com.line", "COMPOSER_SEND", "HAS_TEXT", "",
                        "com.line", "COMPOSER_SEND", "HAS_TEXT", "chat-a"),
                "legacy mapping is replaced on explicit reteach");

        check(!LearnedUiScopePolicy.sameActionSlot(
                        "com.line", "COMPOSER_SEND", "EMPTY", "chat-a",
                        "com.line", "COMPOSER_SEND", "HAS_TEXT", "chat-a"),
                "different known composer states stay separate");

        System.out.println("PASS LearnedUiScopePolicyTest: " + assertions + " checks");
    }
}
