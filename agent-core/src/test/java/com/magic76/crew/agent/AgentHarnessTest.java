package com.magic76.crew.agent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class AgentHarnessTest {
    @Test
    public void routesDeclaredToolAndPreservesCallId() {
        FakeSession session = new FakeSession();
        ToolRegistry registry = new ToolRegistry();
        registry.register("lookup", new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("ok", true);
                completion.complete(ToolResult.success(call.id(), payload));
            }
        });

        final List<AgentEvent.Type> events = new ArrayList<AgentEvent.Type>();
        AgentHarness harness = new AgentHarness(specWithTool("lookup"), session, registry,
                new AgentHarness.Listener() {
                    @Override public void onAgentEvent(AgentEvent event) {
                        events.add(event.type());
                    }
                });

        harness.start();
        session.emit(ModelEvent.toolCall(new ToolCall("gemini-call-42", "lookup",
                Collections.<String, Object>emptyMap())));

        assertNotNull(session.lastToolResult);
        assertTrue(session.lastToolResult.success());
        assertEquals("gemini-call-42", session.lastToolResult.callId());
        assertTrue(events.contains(AgentEvent.Type.TOOL_REQUESTED));
        assertTrue(events.contains(AgentEvent.Type.TOOL_COMPLETED));
    }

    @Test
    public void rejectsUndeclaredToolBeforeExecution() {
        FakeSession session = new FakeSession();
        ToolRegistry registry = new ToolRegistry();
        registry.register("hidden", new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                completion.complete(ToolResult.success(call.id(), Collections.<String, Object>emptyMap()));
            }
        });

        AgentHarness harness = new AgentHarness(specWithTool("allowed"), session, registry, null);
        harness.start();
        session.emit(ModelEvent.toolCall(new ToolCall("call-hidden", "hidden",
                Collections.<String, Object>emptyMap())));

        assertNotNull(session.lastToolResult);
        assertEquals(false, session.lastToolResult.success());
        assertEquals("TOOL_NOT_AVAILABLE", session.lastToolResult.errorCode());
    }

    @Test
    public void duplicateCompletionIsDeliveredOnlyOnce() {
        FakeSession session = new FakeSession();
        ToolRegistry registry = new ToolRegistry();
        registry.register("once", new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                completion.complete(ToolResult.success(call.id(), Collections.<String, Object>emptyMap()));
                completion.complete(ToolResult.failure(call.id(), "SECOND", "must be ignored"));
            }
        });

        AgentHarness harness = new AgentHarness(specWithTool("once"), session, registry, null);
        harness.start();
        session.emit(ModelEvent.toolCall(new ToolCall("call-once", "once",
                Collections.<String, Object>emptyMap())));

        assertEquals(1, session.toolResultCount);
        assertTrue(session.lastToolResult.success());
    }

    private static AgentSpec specWithTool(final String name) {
        return new AgentSpec() {
            private final List<ToolSpec> tools = Collections.singletonList(
                    new ToolSpec(name, "test", "{\"type\":\"object\"}"));

            @Override public String id() { return "test-agent"; }
            @Override public String systemPrompt() { return "test"; }
            @Override public List<ToolSpec> tools() { return tools; }
        };
    }

    private static final class FakeSession implements ModelSession {
        Listener listener;
        ToolResult lastToolResult;
        int toolResultCount;

        @Override public void start(SessionConfig config, Listener listener) {
            this.listener = listener;
        }

        void emit(ModelEvent event) {
            listener.onModelEvent(event);
        }

        @Override public void sendUserText(String text) {}
        @Override public void sendUserAudio(byte[] audio) {}

        @Override public void sendToolResult(ToolResult result) {
            lastToolResult = result;
            toolResultCount++;
        }

        @Override public void interrupt() {}
        @Override public void close() {}
    }
}
