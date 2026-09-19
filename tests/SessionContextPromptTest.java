package com.crewpocket.helper;

public final class SessionContextPromptTest {
    private static int checks;

    public static void main(String[] args) {
        String withLocation = SessionContextPrompt.build(
                "2026-09-19T18:15:00+07:00",
                "Asia/Bangkok",
                "+07:00",
                "zh-TW",
                "13.75, 100.50 (coarse Android location)",
                "com.google.android.youtube");

        check(withLocation.contains("Captured local datetime: 2026-09-19T18:15:00+07:00"),
                "must include local datetime");
        check(withLocation.contains("Timezone: Asia/Bangkok (UTC+07:00)"),
                "must include timezone and offset");
        check(withLocation.contains("Locale: zh-TW"),
                "must include locale");
        check(withLocation.contains("Approximate location: 13.75, 100.50"),
                "must include optional location");
        check(withLocation.contains("Foreground app package: com.google.android.youtube"),
                "must include optional foreground app");
        check(withLocation.contains("do not assume UTC"),
                "must explicitly prevent UTC fallback");

        String minimal = SessionContextPrompt.build(
                "2026-09-19T19:00:00+08:00",
                "Asia/Taipei",
                "+08:00",
                "zh-TW",
                "",
                "");
        check(!minimal.contains("Approximate location:"),
                "missing location must be omitted");
        check(!minimal.contains("Foreground app package:"),
                "missing foreground app must be omitted");

        System.out.println("SessionContextPromptTest passed " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
