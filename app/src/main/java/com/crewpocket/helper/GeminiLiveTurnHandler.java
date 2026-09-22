package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Gemini Live websocket protocol frames into a stable Runtime-facing shape.
 *
 * This class owns protocol aliases and frame decoding only. It does not own
 * user intent, SEND/SEARCH authorization, AgentRuntime policy, Deck policy,
 * audio lifecycle, or task completion.
 */
final class GeminiLiveTurnHandler {
    static final class ModelPart {
        final String audioBase64;
        final String text;

        ModelPart(String audioBase64, String text) {
            this.audioBase64 = audioBase64 == null ? "" : audioBase64;
            this.text = text == null ? "" : text;
        }

        boolean hasAudio() {
            return !audioBase64.isEmpty();
        }

        boolean hasText() {
            return !text.isEmpty();
        }
    }

    static final class Frame {
        boolean resumable;
        String resumptionHandle = "";
        boolean goAway;
        boolean setupComplete;

        boolean serverPresent;
        String inputText = "";
        double inputConfidence = -1.0d;
        String interimInputText = "";
        String outputText = "";
        boolean interrupted;
        boolean turnComplete;

        boolean modelTurnPresent;
        final List<ModelPart> modelParts = new ArrayList<ModelPart>();
        final JSONArray toolCalls = new JSONArray();

        boolean hasToolCalls() {
            return toolCalls.length() > 0;
        }
    }

    Frame parse(String raw) throws Exception {
        JSONObject response = new JSONObject(raw);

        JSONObject error = response.optJSONObject("error");
        if (error != null) {
            throw new Exception(
                    error.optString("message", error.toString()));
        }

        Frame frame = new Frame();

        JSONObject resume = objectAlias(
                response,
                "sessionResumptionUpdate",
                "session_resumption_update");
        if (resume != null && resume.optBoolean("resumable")) {
            frame.resumable = true;
            frame.resumptionHandle = stringAlias(
                    resume, "newHandle", "new_handle");
        }

        frame.goAway =
                response.has("goAway") || response.has("go_away");
        frame.setupComplete =
                response.has("setupComplete")
                        || response.has("setup_complete");

        JSONObject toolCall = objectAlias(
                response, "toolCall", "tool_call");
        JSONArray calls = arrayAlias(
                toolCall, "functionCalls", "function_calls");
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call != null) frame.toolCalls.put(call);
            }
        }

        JSONObject server = objectAlias(
                response, "serverContent", "server_content");
        if (server == null) return frame;

        frame.serverPresent = true;
        frame.interrupted = server.optBoolean("interrupted", false);
        frame.turnComplete = server.optBoolean(
                "turnComplete",
                server.optBoolean("turn_complete", false));

        JSONObject inputTranscript = objectAlias(
                server,
                "inputTranscription",
                "input_transcription");
        if (inputTranscript != null) {
            frame.inputText =
                    inputTranscript.optString("text", "").trim();
            if (inputTranscript.has("confidence")) {
                frame.inputConfidence =
                        inputTranscript.optDouble("confidence", -1.0d);
            }
        }

        JSONObject interimInputTranscript = objectAlias(
                server,
                "interimInputTranscription",
                "interim_input_transcription");
        if (interimInputTranscript != null) {
            frame.interimInputText =
                    interimInputTranscript.optString("text", "").trim();
        }

        JSONObject outputTranscript = objectAlias(
                server,
                "outputTranscription",
                "output_transcription");
        if (outputTranscript != null) {
            frame.outputText =
                    outputTranscript.optString("text", "");
        }

        JSONObject modelTurn = objectAlias(
                server, "modelTurn", "model_turn");
        if (modelTurn != null) {
            frame.modelTurnPresent = true;
            JSONArray parts = modelTurn.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part == null) continue;

                    JSONObject inline = objectAlias(
                            part, "inlineData", "inline_data");
                    String audio = inline == null
                            ? ""
                            : inline.optString("data", "");
                    String text = part.optString("text", "");
                    frame.modelParts.add(
                            new ModelPart(audio, text));
                }
            }
        }

        return frame;
    }

    private static JSONObject objectAlias(
            JSONObject source,
            String camel,
            String snake) {
        if (source == null) return null;
        JSONObject value = source.optJSONObject(camel);
        if (value == null) value = source.optJSONObject(snake);
        return value;
    }

    private static JSONArray arrayAlias(
            JSONObject source,
            String camel,
            String snake) {
        if (source == null) return null;
        JSONArray value = source.optJSONArray(camel);
        if (value == null) value = source.optJSONArray(snake);
        return value;
    }

    private static String stringAlias(
            JSONObject source,
            String camel,
            String snake) {
        if (source == null) return "";
        String value = source.optString(camel, "");
        if (value.isEmpty()) value = source.optString(snake, "");
        return value;
    }
}
