package com.crewpocket.helper;

import android.util.Log;

import com.magic76.crew.agent.AgentEvent;
import com.magic76.crew.agent.AgentHarness;
import com.magic76.crew.agent.AgentSpec;
import com.magic76.crew.agent.ToolCall;
import com.magic76.crew.agent.ToolExecutor;
import com.magic76.crew.agent.ToolRegistry;
import com.magic76.crew.agent.ToolResult;
import com.magic76.crew.agent.ToolSpec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session-level read-only Notebook consumer of the shared AgentHarness.
 *
 * Mutating notebook and phone tools intentionally remain on the legacy runtime.
 * The harness is created once with NotebookToolHandler and reused across calls.
 */
final class ReadOnlyNotebookHarness {
    interface Backend {
        JSONObject getNote(JSONObject args);
        JSONObject searchNotes(JSONObject args);
        JSONObject listNotes(JSONObject args);
    }

    private static final String TAG = "CrewNotebookHarness";
    private static final String GET_NOTE = "get_note";
    private static final String SEARCH_NOTES = "search_notes";
    private static final String LIST_NOTES = "list_notes";

    private final Backend backend;
    private final GeminiLiveToolSessionAdapter session;
    private final AgentHarness harness;
    private final AtomicLong sequence = new AtomicLong(0L);
    private final ConcurrentHashMap<String, ToolResult> completedResults =
            new ConcurrentHashMap<String, ToolResult>();

    ReadOnlyNotebookHarness(Backend backend) {
        if (backend == null) throw new IllegalArgumentException("backend is null");
        this.backend = backend;

        this.session = new GeminiLiveToolSessionAdapter(
                new GeminiLiveToolSessionAdapter.ResultSink() {
                    @Override public void onToolResult(ToolResult result) {
                        if (result == null || result.callId() == null) return;
                        completedResults.put(result.callId(), result);
                    }
                });

        ToolRegistry tools = new ToolRegistry();
        tools.register(GET_NOTE, backendExecutor(GET_NOTE));
        tools.register(SEARCH_NOTES, backendExecutor(SEARCH_NOTES));
        tools.register(LIST_NOTES, backendExecutor(LIST_NOTES));

        AgentSpec spec = new AgentSpec() {
            private final List<ToolSpec> declared = Arrays.asList(
                    new ToolSpec(
                            GET_NOTE,
                            "Read one Crew Notebook note by note_id.",
                            "{\"type\":\"object\",\"properties\":{\"note_id\":{\"type\":\"string\"}},\"required\":[\"note_id\"]}"),
                    new ToolSpec(
                            SEARCH_NOTES,
                            "Search Crew Notebook without modifying it.",
                            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}},\"required\":[\"query\"]}"),
                    new ToolSpec(
                            LIST_NOTES,
                            "List recent Crew Notebook notes without modifying them.",
                            "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}"));

            @Override public String id() { return "crew-helper-notebook-readonly"; }
            @Override public String systemPrompt() { return "Read-only Crew Notebook tools."; }
            @Override public List<ToolSpec> tools() { return declared; }
        };

        this.harness = new AgentHarness(spec, session, tools, new AgentHarness.Listener() {
            @Override public void onAgentEvent(AgentEvent event) {
                trace(event);
            }
        });
        this.harness.start();
    }

    JSONObject execute(String name, JSONObject args) {
        if (!isReadOnlyTool(name)) return error("UNSUPPORTED_READ_ONLY_NOTEBOOK_TOOL");

        final String callId = "notebook_" + name + "_" + sequence.incrementAndGet();
        try {
            completedResults.remove(callId);
            boolean accepted = session.dispatchToolCall(
                    new ToolCall(callId, name, toMap(args == null ? new JSONObject() : args)));
            if (!accepted) return error("HARNESS_SESSION_NOT_READY");

            // Current Notebook backends are synchronous. The persistent map keeps
            // result routing call-id based so the session can later become async
            // without reverting to one Harness per tool invocation.
            ToolResult result = completedResults.remove(callId);
            if (result == null) return error("HARNESS_NO_TOOL_RESULT");
            if (!result.success()) {
                return error(result.errorCode().isEmpty() ? "HARNESS_TOOL_FAILED" : result.errorCode());
            }
            return toJsonObject(result.payload());
        } catch (RuntimeException error) {
            Log.e(TAG, "tool=" + name + " call=" + callId + " failed", error);
            return error(error.getMessage() == null
                    ? "HARNESS_EXECUTION_FAILED"
                    : error.getMessage());
        } finally {
            completedResults.remove(callId);
        }
    }

    private ToolExecutor backendExecutor(final String name) {
        return new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                JSONObject args = toJsonObject(call.arguments());
                JSONObject backendResult;
                if (GET_NOTE.equals(name)) backendResult = backend.getNote(args);
                else if (SEARCH_NOTES.equals(name)) backendResult = backend.searchNotes(args);
                else backendResult = backend.listNotes(args);

                if (backendResult != null && backendResult.optBoolean("success", false)) {
                    completion.complete(ToolResult.success(call.id(), toMap(backendResult)));
                    return;
                }
                String code = backendResult == null
                        ? "NOTEBOOK_NO_RESULT"
                        : backendResult.optString("error", "NOTEBOOK_READ_FAILED");
                completion.complete(ToolResult.failure(call.id(), code, code));
            }
        };
    }

    private static boolean isReadOnlyTool(String name) {
        return GET_NOTE.equals(name) || SEARCH_NOTES.equals(name) || LIST_NOTES.equals(name);
    }

    private static void trace(AgentEvent event) {
        if (event == null) return;
        ToolCall call = event.toolCall();
        ToolResult result = event.toolResult();
        String callId = call != null ? call.id() : result != null ? result.callId() : "";
        String tool = call == null ? "" : call.name();
        String status = result == null ? "" : result.success() ? " success" : " failed=" + result.errorCode();
        Log.d(TAG, "event=" + event.type() + " call=" + callId + " tool=" + tool + status);
    }

    private static JSONObject error(String code) {
        JSONObject out = new JSONObject();
        try { out.put("success", false).put("error", code == null ? "UNKNOWN" : code); }
        catch (Exception ignored) {}
        return out;
    }

    private static Map<String, Object> toMap(JSONObject source) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (source == null) return out;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = source.opt(key);
            out.put(key, normalizeForMap(value));
        }
        return out;
    }

    private static Object normalizeForMap(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return toMap((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            ArrayList<Object> out = new ArrayList<Object>();
            for (int i = 0; i < array.length(); i++) out.add(normalizeForMap(array.opt(i)));
            return out;
        }
        return value;
    }

    private static JSONObject toJsonObject(Map<String, Object> source) {
        JSONObject out = new JSONObject();
        if (source == null) return out;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            try { out.put(entry.getKey(), normalizeForJson(entry.getValue())); }
            catch (Exception ignored) {}
        }
        return out;
    }

    private static Object normalizeForJson(Object value) {
        if (value == null) return JSONObject.NULL;
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return toJsonObject(map);
        }
        if (value instanceof List) {
            JSONArray out = new JSONArray();
            for (Object item : (List<?>) value) out.put(normalizeForJson(item));
            return out;
        }
        return value;
    }
}
