package com.crewpocket.helper;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** A short Gemini Live session used only to audition the exact selected voice. */
final class GeminiVoicePreviewClient {
    private static final String TAG = "CrewVoicePreview";
    private static final String MODEL = "models/gemini-3.1-flash-live-preview";
    private static final String URL = "wss://generativelanguage.googleapis.com/ws/"
            + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=";
    private static final Object LOCK = new Object();
    private static WebSocket socket;
    private static AudioTrack track;
    private static long sessionId;

    private GeminiVoicePreviewClient() { }

    static void play(Context context, String voiceName) {
        if (context == null || !isSupported(voiceName)) return;
        final Context app = context.getApplicationContext();
        final String apiKey = AppConfig.getGeminiApiKey(app);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            Toast.makeText(app, I18n.get(app, "請先設定 Gemini API Key", "Set a Gemini API Key first"), Toast.LENGTH_SHORT).show();
            return;
        }
        final long id;
        synchronized (LOCK) { stopLocked(); id = ++sessionId; }
        final String voice = canonical(voiceName);
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(10, TimeUnit.SECONDS).build();
        Request request = new Request.Builder().url(URL + apiKey.trim()).build();
        WebSocket ws = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (!active(id)) { webSocket.close(1000, "superseded"); return; }
                try {
                    JSONObject setup = new JSONObject();
                    setup.put("model", MODEL);
                    JSONObject generation = new JSONObject();
                    generation.put("responseModalities", new JSONArray().put("AUDIO"));
                    generation.put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                            .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice))));
                    setup.put("generationConfig", generation);
                    setup.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(
                            new JSONObject().put("text", "Repeat the user's sentence exactly. Do not add words."))));
                    webSocket.send(new JSONObject().put("setup", setup).toString());
                } catch (Exception e) { Log.e(TAG, "preview setup failed", e); webSocket.close(1000, "setup failed"); }
            }
            @Override public void onMessage(WebSocket ws, String text) { handle(app, ws, text, id); }
            @Override public void onMessage(WebSocket ws, ByteString bytes) { handle(app, ws, bytes.utf8(), id); }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                Log.e(TAG, "preview Live failed", error);
                if (active(id)) Toast.makeText(app, I18n.get(app, "語音試聽連線失敗", "Voice preview connection failed"), Toast.LENGTH_SHORT).show();
            }
        });
        synchronized (LOCK) { if (sessionId == id) socket = ws; else ws.close(1000, "superseded"); }
    }

    private static void handle(Context context, WebSocket ws, String text, long id) {
        if (!active(id)) return;
        try {
            JSONObject root = new JSONObject(text);
            if (root.has("setupComplete") || root.has("setup_complete")) {
                String sample = I18n.isEn(context)
                        ? "Hello, nice to meet you. If you're ready, let's begin."
                        : "你好，很高興認識你。如果你準備好了，我們現在就開始吧。";
                JSONObject turn = new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", sample)));
                JSONObject content = new JSONObject().put("turns", new JSONArray().put(turn)).put("turnComplete", true);
                ws.send(new JSONObject().put("clientContent", content).toString());
                return;
            }
            JSONObject server = root.optJSONObject("serverContent");
            if (server == null) server = root.optJSONObject("server_content");
            if (server == null) return;
            JSONObject turn = server.optJSONObject("modelTurn");
            if (turn == null) turn = server.optJSONObject("model_turn");
            if (turn != null) {
                JSONArray parts = turn.optJSONArray("parts");
                for (int i = 0; parts != null && i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part == null) continue;
                    JSONObject inline = part.optJSONObject("inlineData");
                    if (inline == null) inline = parts.optJSONObject(i).optJSONObject("inline_data");
                    if (inline != null && inline.has("data")) play(Base64.decode(inline.getString("data"), Base64.DEFAULT));
                }
            }
            if (server.optBoolean("turnComplete", server.optBoolean("turn_complete", false))) {
                ws.close(1000, "preview complete");
                synchronized (LOCK) { if (socket == ws) socket = null; }
            }
        } catch (Exception e) { Log.e(TAG, "preview parse failed", e); }
    }

    private static void play(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        synchronized (LOCK) {
            if (track == null) {
                int min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(Math.max(min, 24000)).setTransferMode(AudioTrack.MODE_STREAM).build();
                track.play();
            }
            track.write(pcm, 0, pcm.length);
        }
    }

    static void stop() { synchronized (LOCK) { ++sessionId; stopLocked(); } }
    private static boolean active(long id) { synchronized (LOCK) { return sessionId == id; } }
    private static void stopLocked() {
        if (socket != null) { try { socket.close(1000, "stop preview"); } catch (Exception ignored) { } socket = null; }
        if (track != null) { try { track.pause(); track.flush(); track.stop(); } catch (Exception ignored) { }
            try { track.release(); } catch (Exception ignored) { } track = null; }
    }
    private static boolean isSupported(String name) { return canonical(name) != null; }
    private static String canonical(String name) {
        if (name == null) return null;
        for (MainActivity.VoiceInfo voice : MainActivity.ALL_VOICES) if (voice.name.equalsIgnoreCase(name.trim())) return voice.name;
        return null;
    }
}
