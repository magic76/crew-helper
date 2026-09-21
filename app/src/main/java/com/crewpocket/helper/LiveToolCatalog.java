package com.crewpocket.helper;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 0104: model-facing Gemini Live tool catalog and mode-specific exposure.
 *
 * This is intentionally declaration/filtering only. Runtime execution,
 * authorization, verification, SEND safety and Agent lifecycle remain owned by
 * NativeGeminiLiveClient and the existing Runtime classes.
 */
final class LiveToolCatalog {
    private static final String TAG = "CrewLiveToolCatalog";

    private LiveToolCatalog() {}

    static JSONArray build(
            boolean deckMode,
            boolean createMode,
            boolean presentMode,
            boolean workspaceMode) throws Exception {
        JSONArray tools = new JSONArray();
        JSONObject phoneActionProperties = new JSONObject()
                .put("action", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray()
                                .put("OPEN_APP").put("SEARCH").put("COMMIT_SEARCH").put("TAP").put("TYPE")
                                .put("SCROLL").put("BACK").put("HOME"))
                        .put("description", "Choose exactly one semantic next action; Runtime decides Android implementation."))
                .put("target", new JSONObject().put("type", "STRING")
                        .put("description", "Human semantic target or App name. Examples: Google, Search, Wi-Fi, first result. Do not pass coordinates/resource IDs."))
                .put("text", new JSONObject().put("type", "STRING")
                        .put("description", "For TYPE: exact text to put into the current visible editable field (settings, system prompt, form, note field, search box, or chat composer). TYPE never submits. For SEARCH: the query."))
                .put("direction", new JSONObject().put("type", "STRING")
                        .put("enum", new JSONArray().put("forward").put("backward").put("left").put("right"))
                        .put("description", "Only for SCROLL. This is CONTENT/NAVIGATION direction, never finger gesture direction. forward = reveal later/below content or next page; backward = reveal earlier/above content or previous page. Use left/right only for explicitly horizontal content. Runtime converts this semantic direction into Android scrolling/swiping."))
                .put("distance", new JSONObject().put("type", "STRING")
                        .put("enum", new JSONArray().put("short").put("normal").put("long").put("page"))
                        .put("description", "Optional SCROLL distance."));
        tools.put(new JSONObject().put("name", "phone_action")
                .put("description",
                        "Perform exactly ONE semantic phone step. Available actions: OPEN_APP, SEARCH, COMMIT_SEARCH, TAP, TYPE, SCROLL, BACK, HOME. TYPE is generic text entry into the current visible editable field, including settings/system prompts/forms/search/chat composers, and never submits. COMMIT_SEARCH presses the current keyboard search/IME button only. Runtime owns selectors, focus, Android implementation and verification. For vertical SCROLL, use direction=forward to continue to later/below content or the next page, and direction=backward to return to earlier/above content or the previous page; never reason about physical finger direction. SEARCH is one Runtime transaction; do not manually TAP search then TYPE. Message submission is separate through send_text. For an explicit named-recipient request, navigation may first reach that chat; Runtime still verifies the requested recipient on the current conversation screen before allowing SEND.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", phoneActionProperties)
                        .put("required", new JSONArray().put("action"))));
        tools.put(new JSONObject().put("name", "get_selected_region").put("description",
                "METADATA FALLBACK for the latest explicit user-selected region. The frozen visual crop has already been sent to you. If the crop itself answers the user's question, answer directly and DO NOT call this tool. Call only when selected text/package/bounds metadata is actually needed. Never treat crop coordinates as phone coordinates; phone execution still uses semantic Runtime actions and normal verification."));

        tools.put(new JSONObject().put("name", "read_web_page").put("description",
                "Read a public http/https webpage as plain text for understanding or Notebook enrichment. Use this when the user explicitly asks to parse/summarize a URL, including a URL from get_selected_region. No JS, cookies, authentication, localhost, or private-network destinations.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("url", new JSONObject().put("type", "STRING")
                                        .put("description", "Public http/https URL to read")))
                        .put("required", new JSONArray().put("url"))));
        tools.put(new JSONObject().put("name", "create_note").put("description",
                "PERSISTENT CREW NOTEBOOK ONLY. Use when the user explicitly asks to save/note/remember content; unqualified 記一下/記下來/remember this means Crew Notebook unless another notes app is named. Do NOT use create_note to put text into a visible UI field such as settings, a system prompt editor, a form, search box, or chat composer; use phone_action(TYPE) for those. App-operational learning such as how the current App behaves belongs to remember_app_guidance, not Notebook. If a selected URL must be parsed and saved: get_selected_region -> read_web_page -> create_note.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("title", new JSONObject().put("type", "STRING"))
                                .put("content", new JSONObject().put("type", "STRING"))
                                .put("source_url", new JSONObject().put("type", "STRING"))
                                .put("tags", new JSONObject().put("type", "ARRAY")
                                        .put("items", new JSONObject().put("type", "STRING"))))
                        .put("required", new JSONArray()
                                .put("title")
                                .put("content"))));
        tools.put(new JSONObject().put("name", "update_note").put("description",
                "Update an existing Crew Notebook note. Use only when the user clearly asks to modify/append/organize an existing note.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("note_id", new JSONObject().put("type", "STRING"))
                                .put("title", new JSONObject().put("type", "STRING"))
                                .put("content", new JSONObject().put("type", "STRING"))
                                .put("source_url", new JSONObject().put("type", "STRING"))
                                .put("tags", new JSONObject().put("type", "ARRAY")
                                        .put("items", new JSONObject().put("type", "STRING"))))
                        .put("required", new JSONArray().put("note_id"))));
        tools.put(new JSONObject().put("name", "get_note").put("description",
                "Read one Crew Notebook note by note_id.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("note_id", new JSONObject().put("type", "STRING")))
                        .put("required", new JSONArray().put("note_id"))));
        tools.put(new JSONObject().put("name", "search_notes").put("description",
                "Search the user's explicit Crew Notebook by title, content, source URL, or tag. Use this for questions such as '我之前是不是記過...' instead of guessing from conversation memory.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("query", new JSONObject().put("type", "STRING"))
                                .put("limit", new JSONObject().put("type", "NUMBER")))
                        .put("required", new JSONArray().put("query"))));
        tools.put(new JSONObject().put("name", "list_notes").put("description",
                "List recent Crew Notebook notes with short previews.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("limit", new JSONObject().put("type", "NUMBER")))));
        tools.put(new JSONObject().put("name", "delete_note").put("description",
                "Delete one Crew Notebook note ONLY when the user explicitly asks to delete that note.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("note_id", new JSONObject().put("type", "STRING")))
                        .put("required", new JSONArray().put("note_id"))));

        tools.put(new JSONObject().put("name", "remember_app_guidance").put("description",
                "CURRENT APP ONLY. Use only when THIS turn explicitly asks Crew to learn/remember an operational fact, workflow hint, UI convention, or pitfall about the currently visible App. Runtime chooses the foreground package; never provide or infer a package. This stores guidance only, not executable code or authorization. Do not store personal facts, message bodies, credentials, OTPs, payment data, or Notebook content.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("title", new JSONObject().put("type", "STRING")
                                        .put("description", "Optional short label for the learned app guidance"))
                                .put("guidance", new JSONObject().put("type", "STRING")
                                        .put("description", "Concise reusable operational guidance for this app")))
                        .put("required", new JSONArray().put("guidance"))));
        tools.put(new JSONObject().put("name", "list_app_guidance").put("description",
                "READ ONLY. Read built-in and user-learned operational guidance for the CURRENT foreground App only when the user asks what Crew already knows/learned. NEVER use this for a request to remember/teach a new rule; teaching is a write operation owned by Runtime/remember_app_guidance. Do not use it as a required pre-step for normal phone actions."));

        tools.put(new JSONObject().put("name", "inspect_ui").put("description",
                "FULL-SCREEN VISUAL FALLBACK. Captures a fresh phone screenshot while Runtime separately keeps Accessibility state for execution. Normally use it for current full-screen prices, charts, WebView/custom UI, images or visually rendered text. SELECTED-REGION EXCEPTION: when the user just framed a region and that frozen crop can answer the question, do NOT call inspect_ui merely to see it again; answer from the crop. Call inspect_ui only if the user asks about content outside that selection or the crop genuinely lacks required evidence. Call once when needed; do not SEARCH merely because a value was absent from prior semantic tool text."));
        tools.put(new JSONObject().put("name", "wait").put("description",
                "Wait for a screen condition after an asynchronous action. Runtime polls and returns the latest state.")
                .put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject()
                        .put("condition", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("screen_change").put("element_appears").put("element_disappears"))
                                .put("description", "Condition to wait for (default screen_change)"))
                        .put("element_id", new JSONObject().put("type", "STRING")
                                .put("description", "Optional element id only when inspect_ui explicitly returned one for a wait condition."))
                        .put("timeout_ms", new JSONObject().put("type", "INTEGER")
                                .put("description", "Maximum wait milliseconds (default 5000, max 15000)")))));
        tools.put(new JSONObject()
                .put("name", "wait_then_action")
                .put("description",
                        "Create ONE background Crew Watcher only when the user explicitly asks to wait for a future phone condition and then act or notify. Runtime reacts to Accessibility events immediately, debounces event bursts, and keeps a slow polling fallback; Gemini does not stay active. Use app_opened + NOTIFY for『地圖打開就告訴我』, button_appears for『按鈕出現就叫我』, and element_enabled for『下一步可以按時通知我』. Mutating follow-ups remain same-app only. The follow-up is limited to one low-risk TAP/TYPE/BACK/HOME/COMMIT_SEARCH/NOTIFY action. Never use for sending messages, payments, purchases, deletion, account changes, or other high-risk commits. TAP must use a semantic target, never coordinates.")
                .put("parameters", new JSONObject()
                        .put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("condition", new JSONObject()
                                        .put("type", "STRING")
                                        .put("enum", new JSONArray()
                                                .put("text_appears")
                                                .put("text_disappears")
                                                .put("button_appears")
                                                .put("element_enabled")
                                                .put("screen_change")
                                                .put("app_opened"))
                                        .put("description", "Future condition to wait for."))
                                .put("condition_text", new JSONObject()
                                        .put("type", "STRING")
                                        .put("description", "For text_appears/text_disappears/button_appears/element_enabled: visible semantic text/content description/id of the target. For app_opened: App name or package such as Google Maps or com.google.android.apps.maps."))
                                .put("action", new JSONObject()
                                        .put("type", "STRING")
                                        .put("enum", new JSONArray()
                                                .put("TAP")
                                                .put("TYPE")
                                                .put("BACK")
                                                .put("HOME")
                                                .put("COMMIT_SEARCH")
                                                .put("NOTIFY"))
                                        .put("description", "Exactly one follow-up action. NOTIFY performs no phone mutation."))
                                .put("target", new JSONObject()
                                        .put("type", "STRING")
                                        .put("description", "Semantic visible target for TAP only, e.g. 下一步. Never coordinates."))
                                .put("text", new JSONObject()
                                        .put("type", "STRING")
                                        .put("description", "Text for TYPE only. Runtime requires a focused editable field and never submits it."))
                                .put("interval_seconds", new JSONObject()
                                        .put("type", "INTEGER")
                                        .put("description", "Fallback polling interval, 5–60 seconds; default 5. Accessibility events normally wake the task immediately."))
                                .put("timeout_minutes", new JSONObject()
                                        .put("type", "INTEGER")
                                        .put("description", "Expiry, 1–60 minutes; default 10."))
                                .put("label", new JSONObject()
                                        .put("type", "STRING")
                                        .put("description", "Optional short human-readable label.")))
                        .put("required", new JSONArray()
                                .put("condition")
                                .put("action"))));
        tools.put(new JSONObject().put("name", "send_text").put("description",
                "MESSAGE SUBMISSION ONLY. Use only when THIS turn explicitly asks to send a message. For an already-visible chat, pass the exact new message text once. For an explicit named-recipient request such as『跟小明說…』or『傳給小明：「…」』, first use normal semantic phone navigation (OPEN_APP/SEARCH/TAP as needed) to reach that recipient; do not infer a different person. Then call send_text once. Runtime will fail closed until Accessibility verifies both a chat composer and the explicitly requested recipient on the current conversation screen. If the user only asks to type/fill/paste without sending, use phone_action(TYPE). Standalone 送出/發送/send is Runtime-owned.")
                .put("parameters", new JSONObject().put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("text", new JSONObject().put("type", "STRING")
                                        .put("description", "Exact new message text from this user turn.")))
                        .put("required", new JSONArray().put("text"))));
        tools.put(new JSONObject().put("name", "schedule_reminder").put("description", "Set a countdown timer / reminder in seconds. When time is up, the assistant vibrates and announces the message.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("delay_seconds", new JSONObject().put("type", "NUMBER").put("description", "Delay in seconds, e.g. 300 for 5 minutes")).put("message", new JSONObject().put("type", "STRING").put("description", "Reminder text to speak when timer expires")).put("label", new JSONObject().put("type", "STRING").put("description", "Short label for the timer"))).put("required", new JSONArray().put("delay_seconds"))));
        tools.put(new JSONObject().put("name", "start_screen_monitor").put("description", "Start periodic background screen checks or wait until a specific condition/text appears on screen.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("interval_seconds", new JSONObject().put("type", "NUMBER").put("description", "Interval between checks in seconds (e.g. 60)")).put("duration_minutes", new JSONObject().put("type", "NUMBER").put("description", "Total monitoring duration in minutes (default 10)")).put("target_condition", new JSONObject().put("type", "STRING").put("description", "Optional text/word to look for on screen (e.g. '已送達', '完成')")).put("label", new JSONObject().put("type", "STRING").put("description", "Short task name"))).put("required", new JSONArray().put("interval_seconds"))));
        tools.put(new JSONObject().put("name", "list_active_schedules").put("description", "List all currently active timers, background screen monitors, and countdowns with their remaining time.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject())));
        tools.put(new JSONObject().put("name", "cancel_schedule").put("description", "Cancel one or all active timers/screen monitors.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("task_id", new JSONObject().put("type", "STRING").put("description", "Optional task ID to cancel, e.g. 'timer_1'")).put("label_hint", new JSONObject().put("type", "STRING").put("description", "Optional keyword/label of the timer to cancel")).put("cancel_all", new JSONObject().put("type", "BOOLEAN").put("description", "Set true to cancel all active timers and monitors")))));
        tools.put(new JSONObject().put("name", "take_screenshot").put("description", "Capture the phone screen ONLY when inspect_ui has no nodes (e.g. Canvas, Unity, WebGL, custom game UI) or user explicitly requests it."));
        tools.put(new JSONObject().put("name", "end_voice_session").put("description", "End the voice call only for an explicit call-ending command: '結束通話', '掛斷電話', or '退出語音助理'. Never infer this from '關閉', '退出', '再見', '先這樣', or a request to close an app, window, or feature."));
        tools.put(new JSONObject().put("name", "list_decks").put("description", "List trusted locally installed Live Decks available for a presentation, story, or teaching flow. Call before opening a deck when its ID is unknown."));
        tools.put(new JSONObject().put("name", "open_deck").put("description", "Open a trusted Live Deck by deckId and show its first card full-screen. Returns that card's concise presentation data.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("deck_id", new JSONObject().put("type", "STRING").put("description", "ID returned by list_decks"))).put("required", new JSONArray().put("deck_id"))));
        tools.put(new JSONObject().put("name", "get_deck_card").put("description", "Read concise, structured information for one card in the currently open Deck. Use its facts, speakerNotes, and allowedNext to decide the next presentation action.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("card_id", new JSONObject().put("type", "STRING").put("description", "Card ID from allowedNext; omit only to reread the visible card")))));
        tools.put(new JSONObject().put("name", "present_deck_card").put("description", "Show a selected card from the currently open Deck full-screen. Only use a card ID supplied by get_deck_card or list_decks results.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("card_id", new JSONObject().put("type", "STRING").put("description", "Card ID to display; omit to refresh current card")))));
        tools.put(new JSONObject().put("name", "advance_deck").put("description", "Advance to the next card in the currently open Deck after the current card has been explained. Read the returned card data before speaking about it."));
        tools.put(new JSONObject()
                .put("name", "list_deck_workspace_sources")
                .put("description", "List the indexed files in the currently selected Deck Workspace. Returns source IDs, paths, types, short previews, and local image assetIds. Use this before planning a source-backed presentation."));
        tools.put(new JSONObject()
                .put("name", "read_deck_workspace_source")
                .put("description", "Read the extracted text for one Deck Workspace source by sourceId. Read only sources relevant to the planned slides; do not request every file by default.")
                .put("parameters", new JSONObject()
                        .put("type", "OBJECT")
                        .put("properties", new JSONObject()
                                .put("source_id", new JSONObject()
                                        .put("type", "STRING")
                                        .put("description", "sourceId returned by list_deck_workspace_sources")))
                        .put("required", new JSONArray().put("source_id"))));
        JSONObject metricProperties = new JSONObject().put("label", new JSONObject().put("type", "STRING"))
                .put("value", new JSONObject().put("type", "STRING"));
        JSONObject cardProperties = new JSONObject()
                .put("type", new JSONObject().put("type", "STRING").put("enum", new JSONArray().put("cover").put("content").put("metric").put("timeline").put("compare")))
                .put("title", new JSONObject().put("type", "STRING"))
                .put("subtitle", new JSONObject().put("type", "STRING"))
                .put("body", new JSONObject().put("type", "STRING"))
                .put("image", new JSONObject().put("type", "STRING").put("description", "Optional HTTPS image URL or assetId"))
                .put("imageCaption", new JSONObject().put("type", "STRING").put("description", "Optional image caption"))
                .put("speakerNotes", new JSONObject().put("type", "STRING"))
                .put("facts", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING")))
                .put("items", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING")))
                .put("sources", new JSONObject().put("type", "ARRAY")
                        .put("description", "Visible source labels/paths supporting this slide. For Workspace decks, cite only files actually read.")
                        .put("items", new JSONObject().put("type", "STRING")))
                .put("metrics", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "OBJECT").put("properties", metricProperties)));
        JSONObject ephemeralProperties = new JSONObject().put("title", new JSONObject().put("type", "STRING").put("description", "Presentation title"))
                .put("cards", new JSONObject().put("type", "ARRAY").put("description", "3–8 cards in speaking order with optional HTTPS images")
                        .put("items", new JSONObject().put("type", "OBJECT").put("properties", cardProperties)));
        tools.put(new JSONObject().put("name", "create_ephemeral_deck").put("description", "Create a temporary, session-only Deck after the content plan is ready. Use 3–8 concise cards. Images may be HTTPS URLs or Workspace image assetIds. The first card is displayed immediately.")
                .put("parameters", new JSONObject().put("type", "OBJECT").put("properties", ephemeralProperties).put("required", new JSONArray().put("title").put("cards"))));
        tools.put(new JSONObject().put("name", "list_deck_images").put("description", "List images bundled inside the currently imported Deck. Returns safe assetId values; call before attaching an image. Session-only decks can directly use HTTPS image URLs."));
        tools.put(new JSONObject().put("name", "attach_deck_image").put("description", "Attach a listed imported image to a future Deck card. Current and already presented cards are locked to avoid visual disruption.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject()
                .put("card_id", new JSONObject().put("type", "STRING"))
                .put("asset_id", new JSONObject().put("type", "STRING"))
                .put("caption", new JSONObject().put("type", "STRING"))).put("required", new JSONArray().put("card_id").put("asset_id"))));
        JSONObject stringArraySchema = new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING"));
        JSONObject editProperties = new JSONObject().put("title", new JSONObject().put("type", "STRING")).put("subtitle", new JSONObject().put("type", "STRING"))
                .put("body", new JSONObject().put("type", "STRING")).put("image", new JSONObject().put("type", "STRING")).put("imageCaption", new JSONObject().put("type", "STRING"))
                .put("speakerNotes", new JSONObject().put("type", "STRING"))
                .put("facts", stringArraySchema).put("items", stringArraySchema);
        tools.put(new JSONObject().put("name", "update_deck_card").put("description", "Rewrite only a future card to adapt the remaining presentation after a user request. The current card is locked.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("card_id", new JSONObject().put("type", "STRING")).put("patch", new JSONObject().put("type", "OBJECT").put("properties", editProperties))).put("required", new JSONArray().put("card_id").put("patch"))));
        JSONObject insertedCardProperties = new JSONObject().put("type", new JSONObject().put("type", "STRING")).put("title", new JSONObject().put("type", "STRING"))
                .put("subtitle", new JSONObject().put("type", "STRING")).put("body", new JSONObject().put("type", "STRING"))
                .put("image", new JSONObject().put("type", "STRING")).put("imageCaption", new JSONObject().put("type", "STRING"))
                .put("speakerNotes", new JSONObject().put("type", "STRING"))
                .put("facts", stringArraySchema).put("items", stringArraySchema);
        tools.put(new JSONObject().put("name", "insert_deck_card").put("description", "Insert one supplementary card after the current or another future card when the user asks for a missing explanation. The inserted card becomes part of the remaining presentation.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("after_card_id", new JSONObject().put("type", "STRING")).put("card", new JSONObject().put("type", "OBJECT").put("properties", insertedCardProperties))).put("required", new JSONArray().put("after_card_id").put("card"))));
        tools.put(new JSONObject().put("name", "remove_future_deck_card").put("description", "Remove a not-yet-presented card that is now redundant. Current and already presented cards are locked.").put("parameters", new JSONObject().put("type", "OBJECT").put("properties", new JSONObject().put("card_id", new JSONObject().put("type", "STRING"))).put("required", new JSONArray().put("card_id"))));
        return filterModelFacingTools(
                tools,
                deckMode,
                createMode,
                presentMode,
                workspaceMode);
    }

    /** 0082: keep the Live model surface small and mode-specific. */
    private static JSONArray filterModelFacingTools(
            JSONArray declared,
            boolean deckMode,
            boolean createMode,
            boolean presentMode,
            boolean workspaceMode) throws Exception {
        JSONArray exposed = new JSONArray();

        for (int i = 0; i < declared.length(); i++) {
            JSONObject tool = declared.optJSONObject(i);
            if (tool == null) continue;
            String name = tool.optString("name", "");

            boolean allow;
            if (!deckMode) {
                allow = isNormalPhoneModelTool(name);
            } else if (createMode) {
                allow = "create_ephemeral_deck".equals(name)
                        || (workspaceMode
                                && ("list_deck_workspace_sources".equals(name)
                                    || "read_deck_workspace_source".equals(name)))
                        || "end_voice_session".equals(name);
            } else if (presentMode) {
                allow = "end_voice_session".equals(name)
                        || isDeckPresentationModelTool(name);
            } else {
                allow = isNormalPhoneModelTool(name) || isDeckModelTool(name);
            }

            if (allow) {
                // Gemini 3.8 Live defaults function calls to NON_BLOCKING.
                // Crew's phone Runtime intentionally preserves the proven
                // request -> tool result -> next model step ordering.
                exposed.put(tool.put("behavior", "BLOCKING"));
            }
        }

        Log.i(TAG, "0082 model tool surface: "
                + exposed.length()
                + (createMode ? " (deck create)"
                    : (presentMode ? " (deck presenter)"
                        : (deckMode ? " (legacy deck)" : " (normal phone)"))));
        return exposed;
    }

    private static boolean isNormalPhoneModelTool(String name) {
        return "phone_action".equals(name)
                || "inspect_ui".equals(name)
                // 0103: Crew Notebook is a first-class normal-mode capability.
                // Keep the surface intentionally small: create/search/list only.
                || "create_note".equals(name)
                || "search_notes".equals(name)
                || "list_notes".equals(name)
                || "remember_app_guidance".equals(name)
                || "list_app_guidance".equals(name)
                || "wait_then_action".equals(name)
                || "list_active_schedules".equals(name)
                || "cancel_schedule".equals(name)
                || "send_text".equals(name)
                || "end_voice_session".equals(name);
    }

    private static boolean isDeckPresentationModelTool(String name) {
        return "get_deck_card".equals(name)
                || "present_deck_card".equals(name)
                || "list_deck_images".equals(name)
                || "attach_deck_image".equals(name)
                || "update_deck_card".equals(name)
                || "insert_deck_card".equals(name)
                || "remove_future_deck_card".equals(name);
    }

    private static boolean isDeckModelTool(String name) {
        return "list_decks".equals(name)
                || "open_deck".equals(name)
                || "get_deck_card".equals(name)
                || "present_deck_card".equals(name)
                || "advance_deck".equals(name)
                || "create_ephemeral_deck".equals(name)
                || "list_deck_workspace_sources".equals(name)
                || "read_deck_workspace_source".equals(name)
                || "list_deck_images".equals(name)
                || "attach_deck_image".equals(name)
                || "update_deck_card".equals(name)
                || "insert_deck_card".equals(name)
                || "remove_future_deck_card".equals(name);
    }

}
