package com.crewpocket.helper;

/**
 * Pure routing boundary for the first RuntimeToolExecutor extraction.
 *
 * Keep authorization, lifecycle, mutation ownership and post-action policy in
 * NativeGeminiLiveClient. Only low-risk implementation dispatch belongs here.
 */
final class RuntimeToolRouting {
    private RuntimeToolRouting() {}

    static boolean handles(String name) {
        return "read_web_page".equals(name)
                || "schedule_reminder".equals(name)
                || "list_active_schedules".equals(name)
                || "cancel_schedule".equals(name)
                || "create_note".equals(name)
                || "update_note".equals(name)
                || "get_note".equals(name)
                || "search_notes".equals(name)
                || "list_notes".equals(name)
                || "delete_note".equals(name);
    }
}
