package com.crewpocket.helper;

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
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * First production pilot for the shared agent harness.
 *
 * Only list_notes is exposed. Mutating notebook and phone tools intentionally
 * remain on the legacy runtime until the common loop is proven in production.
 */
final class ReadOnlyNotebookHarness {
    interface Backend {
        JSONObject listNotes(JSONObject args);
    }

    private static final String TOOL_NAME = "list_notes";
    private final Backend backend;

    ReadOnlyNotebookHarness(Backend backend) {
        if (backend == null) throw new IllegalArgumentException("backend is null");
        this.backend = backend;
    }

    JSONObject execute(JSONObject args) {
        final AtomicReference<ToolResult> resultRef = new AtomicReference<ToolResult>();
        GeminiLiveToolSessionAdapter session = new GeminiLiveToolSessionAdapter(
                new GeminiLiveToolSessionAdapter.ResultSink() {
                    @Override public void onToolResult(ToolResult result) {
                        resultRef.set(result);
                    }
                });

        ToolRegistry tools = new ToolRegistry();
        tools.register(TOOL_NAME, new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                JSONObject backendResult = backend.listNotes(toJsonObject(call.arguments()));
                if (backendResult != null && backendResult.optBoolean("success", false)) {
                    completion.complete(ToolResult.success(call.id(), toMap(backendResult)));
                    return;
                }
                String code = backendResult == null
                        ? "LIST_NOTES_NO_RESULT"
                        : backendResult.optString("error", "LIST_NOTES_FAILED");
                completion.complete(ToolResult.failure(call.id(), code, code));
            }
        });

        AgentSpec spec = new AgentSpec() {
            private final List<ToolSpec> declared = Collections.singletonList(
                    new ToolSpec(
                            TOOL_NAME,
                            "List notebook notes without modifying them.",
                            "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\"}}}"));

            @Override public String id() { return "crew-helper-notebook-readonly"; }
            @Override public String systemPrompt() { return "Read-only notebook tool pilot."; }
            @Override public List<ToolSpec> tools() { return declared; }
        };

        AgentHarness harness = new AgentHarness(spec, session, tools, null);
        harness.start();
        try {
            String callId = "harness_list_notes_" + System.nanoTime();
            boolean accepted = session.dispatchToolCall(
                    new ToolCall(callId, TOOL_NAME, toMap(args == null ? new JSONObject() : args)));
            if (!accepted) return error("HARNESS_SESSION_NOT_READY");

            ToolResult result = resultRef.get();
            if (result == null) return error("HARNESS_NO_TOOL_RESULT");
            if (!result.success()) {
                return error(result.errorCode().isEmpty() ? "HARNESS_TOOL_FAILED" : result.errorCode());
            }
            return toJsonObject(result.payload());
        } catch (RuntimeException error) {
            return error(error.getMessage() == null
                    ? "HARNESS_EXECUTION_FAILED"
                    : error.getMessage());
        } finally {
            harness.close();
        }
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
