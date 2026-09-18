package com.crewpocket.helper;

public final class RuntimeToolRoutingTest {
    private static int checks;

    public static void main(String[] args) {
        checkHandled("read_web_page");
        checkHandled("schedule_reminder");
        checkHandled("list_active_schedules");
        checkHandled("cancel_schedule");

        checkHandled("create_note");
        checkHandled("update_note");
        checkHandled("get_note");
        checkHandled("search_notes");
        checkHandled("list_notes");
        checkHandled("delete_note");

        checkNotHandled("remember_app_guidance");
        checkNotHandled("list_app_guidance");
        checkNotHandled("send_text");
        checkNotHandled("type_text");
        checkNotHandled("search_current_app");
        checkNotHandled("commit_search");
        checkNotHandled("inspect_ui");
        checkNotHandled("tap_element");
        checkNotHandled("start_screen_monitor");
        checkNotHandled("end_voice_session");
        checkNotHandled("list_decks");
        checkNotHandled("advance_deck");

        System.out.println("RuntimeToolRoutingTest passed " + checks + " checks");
    }

    private static void checkHandled(String name) {
        checks++;
        if (!RuntimeToolRouting.handles(name)) {
            throw new AssertionError("expected executor route: " + name);
        }
    }

    private static void checkNotHandled(String name) {
        checks++;
        if (RuntimeToolRouting.handles(name)) {
            throw new AssertionError("policy/orchestration tool leaked into executor: " + name);
        }
    }
}
