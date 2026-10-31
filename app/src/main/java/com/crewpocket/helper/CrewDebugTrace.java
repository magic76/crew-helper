package com.crewpocket.helper;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Explicit opt-in, in-memory, privacy-bounded incident timeline.
 * No transcript, screen, arguments, model output, raw call IDs, or free text.
 * Shared state is bounded and reset when disabled.
 */
final class CrewDebugTrace {
    private static final int MAX_EVENTS = 512;
    private static final int MAX_CALLS = 128;
    private static final String EVENTS =
            "|SESSION_START|SESSION_END|INTENT_FINALIZED|INTENT_SUPERSEDED"
            + "|LIVE_FRAME|LIVE_TOOL_CALL|TOOL_QUEUED|TOOL_COALESCED"
            + "|TOOL_EXECUTING|TOOL_RESPONSE|TOOL_RESPONSE_FAILED"
            + "|TOOL_CANCELLED|RUNTIME_GATE|MODEL_SPEECH|TRANSPORT_ERROR"
            + "|RECOVERY_REQUESTED|RECOVERY_FAILED|";
    private static final String TOOLS =
            "|NONE|phone_action|inspect_ui|launch_app|tap_screen|tap_element"
            + "|search_current_app|commit_search|type_text|send_text|OTHER|";
    private static final String ACTIONS =
            "|NONE|OPEN_APP|TAP|SEARCH|COMMIT_SEARCH|TYPE|SCROLL|BACK"
            + "|HOME|TAKE_PHOTO|OTHER|";
    private static final String CODES =
            "|NONE|IDLE|IN_PROGRESS|TURN_COMPLETE|NEW_INTENT|MERGED_SEGMENT"
            + "|QUEUED|COALESCED|EXECUTING|SENT|FAILED|NO_TOOL_CALL"
            + "|STALE_USER_INTENT|FINISHED_INTENT|OPERATIONAL_TURN_NOT_FINALIZED"
            + "|APP_OPEN_ACTION_REQUIRED|PRE_FLIGHT_BLOCK|RUNTIME_BLOCK"
            + "|SUPERSEDED_BY_NEW_INTENT|TASK_CANCELLED|SOCKET_CLOSED"
            + "|SOCKET_FAILURE|RECOVERY_ONCE|APP_NOT_FOUND"
            + "|SEARCH_NEEDS_QUERY|LOCATOR_MARGIN_TOO_SMALL"
            + "|TARGET_REQUIRED|AMBIGUOUS|UNVERIFIED|OTHER|";
    private static final ArrayDeque<String> events = new ArrayDeque<String>();
    private static final HashMap<String,String> callTokens = new HashMap<String,String>();
    private static final HashMap<String,Long> callGenerations =
            new HashMap<String,Long>();
    private static boolean enabled;
    private static long startMs;
    private static long sequence;
    private static long generation = -1L;
    private static String sessionId = "";
    private static String currentGoal = "NONE";
    private static int dropped;
    private static int callSequence;
    private static int tools;
    private static int responses;
    private static int cancellations;

    private CrewDebugTrace() {}

    static synchronized void setEnabled(boolean value) {
        if (!value) {
            enabled = false;
            reset();
            return;
        }
        if (!enabled) {
            enabled = true;
            startSession();
        }
    }

    static synchronized boolean enabled() { return enabled; }

    static synchronized void startSession() {
        if (!enabled) return;
        reset();
        startMs = System.currentTimeMillis();
        sessionId = UUID.randomUUID().toString();
        append("SESSION_START", -1L, "", "NONE", "NONE", "NONE", 0);
    }

    static synchronized void intent(long newGeneration, String goal) {
        if (!enabled) return;
        generation = newGeneration;
        currentGoal = goalName(goal);
        append("INTENT_FINALIZED", newGeneration, "", "NONE", "NONE",
                "NEW_INTENT", 0);
    }

    static synchronized void record(
            String event,
            long intentGeneration,
            String callId,
            String tool,
            String action,
            String code) {
        if (!enabled) return;
        append(event, intentGeneration, callId, tool, action, code, 0);
    }

    static synchronized void count(
            String event, long intentGeneration, String code, int value) {
        if (!enabled) return;
        append(event, intentGeneration, "", "NONE", "NONE", code,
                Math.max(0, Math.min(value, 10000)));
    }

    static synchronized Snapshot snapshot() {
        StringBuilder jsonl = new StringBuilder();
        for (String entry : events) jsonl.append(entry).append('\n');
        return new Snapshot(enabled, sessionId, startMs, generation,
                currentGoal, sequence, dropped, tools, responses,
                cancellations, jsonl.toString());
    }

    private static void reset() {
        events.clear();
        callTokens.clear();
        callGenerations.clear();
        startMs = 0L;
        sequence = 0L;
        generation = -1L;
        sessionId = "";
        currentGoal = "NONE";
        dropped = 0;
        callSequence = 0;
        tools = 0;
        responses = 0;
        cancellations = 0;
    }

    private static void append(String rawEvent, long gen, String id,
            String rawTool, String rawAction, String rawCode, int count) {
        if (startMs == 0L) startSession();
        String event = enumToken(rawEvent, EVENTS, "OTHER");
        if ("OTHER".equals(event)) return;
        String tool = enumToken(rawTool, TOOLS, "OTHER");
        String action = enumToken(
                rawAction == null ? "" : rawAction.trim().toUpperCase(Locale.ROOT),
                ACTIONS, "OTHER");
        String code = enumToken(
                rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT),
                CODES, "OTHER");
        if ("LIVE_TOOL_CALL".equals(event)) tools++;
        if ("TOOL_RESPONSE".equals(event)) responses++;
        if ("TOOL_CANCELLED".equals(event)) cancellations += Math.max(1, count);
        if (id != null && !id.isEmpty()
                && ("LIVE_TOOL_CALL".equals(event)
                    || "TOOL_QUEUED".equals(event))
                && callGenerations.size() < MAX_CALLS
                && !callGenerations.containsKey(id)) {
            callGenerations.put(id, gen);
        }
        Long originalGeneration = id == null ? null : callGenerations.get(id);
        long correlatedGeneration =
                originalGeneration == null ? gen : originalGeneration;
        String callRef = tokenForCall(id);
        long now = System.currentTimeMillis();
        String item = "{\"seq\":" + (++sequence)
                + ",\"ms\":" + Math.max(0L, now - startMs)
                + ",\"generation\":" + correlatedGeneration
                + ",\"event\":\"" + event + "\""
                + ("INTENT_FINALIZED".equals(event)
                    ? ",\"goal\":\"" + currentGoal + "\"" : "")
                + ",\"call\":\"" + callRef + "\""
                + ",\"tool\":\"" + tool + "\""
                + ",\"action\":\"" + action + "\""
                + ",\"code\":\"" + code + "\""
                + ",\"count\":" + count + "}";
        if (events.size() >= MAX_EVENTS) {
            events.removeFirst();
            dropped++;
        }
        events.addLast(item);
    }

    private static String tokenForCall(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String found = callTokens.get(raw);
        if (found != null) return found;
        if (callTokens.size() >= MAX_CALLS) return "overflow";
        String generated = "c" + (++callSequence);
        callTokens.put(raw, generated);
        return generated;
    }

    private static String goalName(String raw) {
        if ("APP:OPEN".equals(raw) || "MEDIA:PLAY".equals(raw)
                || "NAVIGATION:START".equals(raw)
                || "SEARCH:RESULT".equals(raw)
                || "MESSAGE:SEND".equals(raw)
                || "TEXT:TYPE_ONLY".equals(raw)) return raw;
        return raw == null || raw.isEmpty() ? "NONE" : "OTHER";
    }

    private static String enumToken(String raw, String allowed, String fallback) {
        if (raw == null || raw.isEmpty()) return "NONE";
        return allowed.contains("|" + raw + "|") ? raw : fallback;
    }

    static final class Snapshot {
        final boolean enabled;
        final String sessionId;
        final long startedAt;
        final long generation;
        final String goal;
        final long totalEvents;
        final int dropped;
        final int toolCalls;
        final int responses;
        final int cancellations;
        final String jsonl;

        Snapshot(boolean enabled, String sessionId, long startedAt,
                long generation, String goal, long totalEvents, int dropped,
                int toolCalls, int responses, int cancellations, String jsonl) {
            this.enabled = enabled;
            this.sessionId = sessionId;
            this.startedAt = startedAt;
            this.generation = generation;
            this.goal = goal;
            this.totalEvents = totalEvents;
            this.dropped = dropped;
            this.toolCalls = toolCalls;
            this.responses = responses;
            this.cancellations = cancellations;
            this.jsonl = jsonl;
        }

        String summary() {
            return "Debug Lab: " + (enabled ? "ON" : "OFF")
                    + " · session=" + (sessionId.isEmpty() ? "none" : sessionId)
                    + " · events=" + totalEvents
                    + " · dropped=" + dropped
                    + " · tools=" + toolCalls + " · replies=" + responses
                    + " · cancelled=" + cancellations;
        }
    }
}
