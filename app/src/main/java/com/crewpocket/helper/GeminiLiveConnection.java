package com.crewpocket.helper;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Owns the Gemini Live WebSocket transport.
 *
 * Session protocol, setup payloads, resumption semantics, Agent state, audio,
 * and tool orchestration remain outside this class. This class is deliberately
 * limited to connection lifecycle and raw frame delivery.
 */
final class GeminiLiveConnection extends WebSocketListener {
    interface Listener {
        void onOpened();
        void onFrame(String text, boolean binary);
        void onClosed(int code, String reason);
        void onFailure(String detail, Throwable error);
    }

    private static final String ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/"
                    + "google.ai.generativelanguage.v1alpha.GenerativeService."
                    + "BidiGenerateContent?key=";

    private final String apiKey;
    private final Listener listener;

    private OkHttpClient httpClient;
    private volatile WebSocket socket;

    GeminiLiveConnection(String apiKey, Listener listener) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.listener = listener;
    }

    synchronized void start() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build();
        }
        connect();
    }

    synchronized void connect() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build();
        }
        Request request = new Request.Builder()
                .url(ENDPOINT + apiKey)
                .header("Origin", "https://generativelanguage.googleapis.com")
                .build();
        socket = httpClient.newWebSocket(request, this);
    }

    boolean send(String payload) {
        WebSocket current = socket;
        return current != null && payload != null && current.send(payload);
    }

    boolean isAvailable() {
        return socket != null;
    }

    void closeForReconnect(String reason) {
        WebSocket current = socket;
        if (current != null) {
            try {
                current.close(
                        1000,
                        reason == null ? "Resuming Gemini Live session" : reason);
            } catch (Exception ignored) {}
        }
    }

    synchronized void stop() {
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try { current.close(1000, "Client ended call"); }
            catch (Exception ignored) {}
        }

        OkHttpClient client = httpClient;
        httpClient = null;
        if (client != null) {
            try { client.dispatcher().executorService().shutdown(); }
            catch (Exception ignored) {}
        }
    }

    @Override public void onOpen(WebSocket webSocket, Response response) {
        socket = webSocket;
        if (listener != null) listener.onOpened();
    }

    @Override public void onMessage(WebSocket webSocket, String text) {
        if (listener != null) listener.onFrame(text == null ? "" : text, false);
    }

    @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
        if (listener != null) {
            listener.onFrame(bytes == null ? "" : bytes.utf8(), true);
        }
    }

    @Override public void onClosing(WebSocket webSocket, int code, String reason) {
        try { webSocket.close(code, null); } catch (Exception ignored) {}
    }

    @Override public void onClosed(WebSocket webSocket, int code, String reason) {
        if (socket == webSocket) socket = null;
        if (listener != null) listener.onClosed(code, reason == null ? "" : reason);
    }

    @Override public void onFailure(
            WebSocket webSocket,
            Throwable error,
            Response response) {
        if (socket == webSocket) socket = null;
        String detail = response == null
                ? (error == null ? "unknown failure" : error.getMessage())
                : "HTTP " + response.code() + " " + response.message();
        if (listener != null) listener.onFailure(detail, error);
    }
}
