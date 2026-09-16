package com.crewpocket.helper;

import com.magic76.crew.agent.ModelEvent;
import com.magic76.crew.agent.ModelSession;
import com.magic76.crew.agent.SessionConfig;
import com.magic76.crew.agent.ToolCall;
import com.magic76.crew.agent.ToolResult;

/**
 * Incremental Gemini Live -> agent-core adapter.
 *
 * NativeGeminiLiveClient still owns the physical websocket/audio session during
 * the migration. This adapter attaches at the already-decoded function-call
 * boundary and lets one tool path run through the provider-neutral AgentHarness.
 */
final class GeminiLiveToolSessionAdapter implements ModelSession {
    interface ResultSink {
        void onToolResult(ToolResult result);
    }

    private final ResultSink resultSink;
    private Listener listener;
    private boolean started;
    private boolean closed;

    GeminiLiveToolSessionAdapter(ResultSink resultSink) {
        if (resultSink == null) throw new IllegalArgumentException("resultSink is null");
        this.resultSink = resultSink;
    }

    @Override
    public synchronized void start(SessionConfig config, Listener listener) {
        if (closed) throw new IllegalStateException("adapter is closed");
        if (listener == null) throw new IllegalArgumentException("listener is null");
        this.listener = listener;
        this.started = true;
    }

    /** Feed an already-decoded Gemini Live function call into AgentHarness. */
    boolean dispatchToolCall(ToolCall call) {
        final Listener target;
        synchronized (this) {
            if (!started || closed || listener == null) return false;
            target = listener;
        }
        target.onModelEvent(ModelEvent.toolCall(call));
        return true;
    }

    @Override
    public void sendToolResult(ToolResult result) {
        resultSink.onToolResult(result);
    }

    @Override
    public void interrupt() {
        final Listener target;
        synchronized (this) {
            target = !started || closed ? null : listener;
        }
        if (target != null) target.onModelEvent(ModelEvent.interrupted());
    }

    @Override
    public synchronized void close() {
        closed = true;
        started = false;
        listener = null;
    }

    // During this staged migration user text/audio still enters through the
    // existing NativeGeminiLiveClient websocket/audio path. Keeping these
    // methods unsupported prevents accidental creation of a second turn path.
    @Override
    public void sendUserText(String text) {
        throw new UnsupportedOperationException("Attached tool adapter does not own Gemini user text");
    }

    @Override
    public void sendUserAudio(byte[] audio) {
        throw new UnsupportedOperationException("Attached tool adapter does not own Gemini audio");
    }
}
