package com.magic76.crew.agent;

import java.util.Arrays;

/** Normalized runtime events suitable for tracing, replay and self-improvement analysis. */
public final class AgentEvent {
    public enum Type {
        STARTED,
        USER_TEXT,
        USER_AUDIO,
        MODEL_TEXT,
        MODEL_AUDIO,
        TOOL_REQUESTED,
        TOOL_COMPLETED,
        TOOL_FAILED,
        TURN_COMPLETED,
        INTERRUPTED,
        ERROR,
        STOPPED
    }

    private final Type type;
    private final long timestampMs;
    private final String text;
    private final byte[] audio;
    private final ToolCall toolCall;
    private final ToolResult toolResult;
    private final Throwable error;

    private AgentEvent(Type type, String text, byte[] audio, ToolCall toolCall,
                       ToolResult toolResult, Throwable error) {
        this.type = type;
        this.timestampMs = System.currentTimeMillis();
        this.text = text == null ? "" : text;
        this.audio = audio == null ? null : Arrays.copyOf(audio, audio.length);
        this.toolCall = toolCall;
        this.toolResult = toolResult;
        this.error = error;
    }

    public static AgentEvent simple(Type type) { return new AgentEvent(type, "", null, null, null, null); }
    public static AgentEvent text(Type type, String text) { return new AgentEvent(type, text, null, null, null, null); }
    public static AgentEvent audio(Type type, byte[] audio) { return new AgentEvent(type, "", audio, null, null, null); }
    public static AgentEvent tool(Type type, ToolCall call, ToolResult result) { return new AgentEvent(type, "", null, call, result, null); }
    public static AgentEvent error(Throwable error) { return new AgentEvent(Type.ERROR, "", null, null, null, error); }

    public Type type() { return type; }
    public long timestampMs() { return timestampMs; }
    public String text() { return text; }
    public byte[] audio() { return audio == null ? null : Arrays.copyOf(audio, audio.length); }
    public ToolCall toolCall() { return toolCall; }
    public ToolResult toolResult() { return toolResult; }
    public Throwable error() { return error; }
}
