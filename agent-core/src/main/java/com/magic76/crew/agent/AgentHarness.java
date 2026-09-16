package com.magic76.crew.agent;

/**
 * Common provider-neutral agent loop.
 *
 * The harness owns orchestration only. Product state, permissions, safety policy,
 * prompts and actual tool implementations remain in Teacher/Helper/Story/Mate.
 */
public final class AgentHarness {
    public interface Listener {
        void onAgentEvent(AgentEvent event);
    }

    private final AgentSpec spec;
    private final ModelSession session;
    private final ToolRegistry tools;
    private final Listener listener;
    private boolean started;
    private boolean closed;

    public AgentHarness(AgentSpec spec, ModelSession session, ToolRegistry tools, Listener listener) {
        if (spec == null) throw new IllegalArgumentException("spec is null");
        if (session == null) throw new IllegalArgumentException("session is null");
        if (tools == null) throw new IllegalArgumentException("tools is null");
        this.spec = spec;
        this.session = session;
        this.tools = tools;
        this.listener = listener;
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("harness is closed");
        if (started) return;
        started = true;
        emit(AgentEvent.simple(AgentEvent.Type.STARTED));
        try {
            session.start(SessionConfig.from(spec), new ModelSession.Listener() {
                @Override public void onModelEvent(ModelEvent event) {
                    handleModelEvent(event);
                }
            });
        } catch (RuntimeException error) {
            started = false;
            emit(AgentEvent.error(error));
            throw error;
        }
    }

    public void submitText(String text) {
        requireRunning();
        String safe = text == null ? "" : text;
        emit(AgentEvent.text(AgentEvent.Type.USER_TEXT, safe));
        session.sendUserText(safe);
    }

    public void submitAudio(byte[] audio) {
        requireRunning();
        byte[] safe = audio == null ? new byte[0] : audio;
        emit(AgentEvent.audio(AgentEvent.Type.USER_AUDIO, safe));
        session.sendUserAudio(safe);
    }

    public void interrupt() {
        requireRunning();
        session.interrupt();
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        started = false;
        try {
            session.close();
        } finally {
            emit(AgentEvent.simple(AgentEvent.Type.STOPPED));
        }
    }

    private void handleModelEvent(ModelEvent event) {
        if (event == null || isClosed()) return;
        switch (event.type()) {
            case TEXT_DELTA:
                emit(AgentEvent.text(AgentEvent.Type.MODEL_TEXT, event.text()));
                break;
            case AUDIO_CHUNK:
                emit(AgentEvent.audio(AgentEvent.Type.MODEL_AUDIO, event.audio()));
                break;
            case TOOL_CALL:
                handleToolCall(event.toolCall());
                break;
            case TURN_COMPLETED:
                emit(AgentEvent.simple(AgentEvent.Type.TURN_COMPLETED));
                break;
            case INTERRUPTED:
                emit(AgentEvent.simple(AgentEvent.Type.INTERRUPTED));
                break;
            case ERROR:
                emit(AgentEvent.error(event.error()));
                break;
            default:
                break;
        }
    }

    private void handleToolCall(final ToolCall call) {
        if (call == null) {
            ToolResult result = ToolResult.failure("", "INVALID_TOOL_CALL", "Model emitted an empty tool call");
            emit(AgentEvent.tool(AgentEvent.Type.TOOL_FAILED, null, result));
            session.sendToolResult(result);
            return;
        }

        emit(AgentEvent.tool(AgentEvent.Type.TOOL_REQUESTED, call, null));
        if (!spec.allowTool(call.name()) || !tools.contains(call.name())) {
            ToolResult result = ToolResult.failure(
                    call.id(), "TOOL_NOT_AVAILABLE", "Tool is not exposed by this agent: " + call.name());
            emit(AgentEvent.tool(AgentEvent.Type.TOOL_FAILED, call, result));
            session.sendToolResult(result);
            return;
        }

        tools.execute(call, new ToolExecutor.Completion() {
            @Override public void complete(ToolResult result) {
                if (isClosed()) return;
                ToolResult safeResult = result == null
                        ? ToolResult.failure(call.id(), "NULL_TOOL_RESULT", "Tool returned no result")
                        : result;
                AgentEvent.Type type = safeResult.success()
                        ? AgentEvent.Type.TOOL_COMPLETED
                        : AgentEvent.Type.TOOL_FAILED;
                emit(AgentEvent.tool(type, call, safeResult));
                session.sendToolResult(safeResult);
            }
        });
    }

    private synchronized boolean isClosed() { return closed; }

    private synchronized void requireRunning() {
        if (!started || closed) throw new IllegalStateException("harness is not running");
    }

    private void emit(AgentEvent event) {
        if (listener != null && event != null) listener.onAgentEvent(event);
    }
}
