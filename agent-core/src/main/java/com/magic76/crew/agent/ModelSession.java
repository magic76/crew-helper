package com.magic76.crew.agent;

/** Adapter implemented by Gemini Live today and other providers later. */
public interface ModelSession {
    interface Listener {
        void onModelEvent(ModelEvent event);
    }

    void start(SessionConfig config, Listener listener);
    void sendUserText(String text);
    void sendUserAudio(byte[] pcmOrEncodedAudio);
    void sendToolResult(ToolResult result);
    void interrupt();
    void close();
}
