package com.magic76.crew.agent;

import java.util.Arrays;

/** Events emitted by Gemini Live or any future model adapter. */
public final class ModelEvent {
    public enum Type {
        TEXT_DELTA,
        AUDIO_CHUNK,
        TOOL_CALL,
        TURN_COMPLETED,
        INTERRUPTED,
        ERROR
    }

    private final Type type;
    private final String text;
    private final byte[] audio;
    private final ToolCall toolCall;
    private final Throwable error;

    private ModelEvent(Type type, String text, byte[] audio, ToolCall toolCall, Throwable error) {
        this.type = type;
        this.text = text == null ? "" : text;
        this.audio = audio == null ? null : Arrays.copyOf(audio, audio.length);
        this.toolCall = toolCall;
        this.error = error;
    }

    public static ModelEvent text(String text) { return new ModelEvent(Type.TEXT_DELTA, text, null, null, null); }
    public static ModelEvent audio(byte[] audio) { return new ModelEvent(Type.AUDIO_CHUNK, "", audio, null, null); }
    public static ModelEvent toolCall(ToolCall call) { return new ModelEvent(Type.TOOL_CALL, "", null, call, null); }
    public static ModelEvent turnCompleted() { return new ModelEvent(Type.TURN_COMPLETED, "", null, null, null); }
    public static ModelEvent interrupted() { return new ModelEvent(Type.INTERRUPTED, "", null, null, null); }
    public static ModelEvent error(Throwable error) { return new ModelEvent(Type.ERROR, "", null, null, error); }

    public Type type() { return type; }
    public String text() { return text; }
    public byte[] audio() { return audio == null ? null : Arrays.copyOf(audio, audio.length); }
    public ToolCall toolCall() { return toolCall; }
    public Throwable error() { return error; }
}
