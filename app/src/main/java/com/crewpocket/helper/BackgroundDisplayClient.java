package com.crewpocket.helper;

import android.content.Context;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Iterator;

/** Local-only, user-initiated POC. No Gemini Live tool uses this bridge. */
final class BackgroundDisplayClient {
    private BackgroundDisplayClient() {}

    static JSONObject request(Context context, JSONObject operation) {
        if (android.os.Build.VERSION.SDK_INT < 31) return error("ANDROID_12_REQUIRED");
        if (operation == null) return error("EMPTY_REQUEST");
        String op = operation.optString("op", "");
        if (!"status".equals(op)) {
            JSONObject hello = new JSONObject();
            try { hello.put("op", "status"); }
            catch (Exception invalid) { return error("INVALID_PROTOCOL_REQUEST"); }
            JSONObject check = send(context, hello);
            if (!check.optBoolean("success", false)) return check;
            if (check.optInt("protocolVersion", -1)
                    != BackgroundDisplayShell.PROTOCOL_VERSION)
                return protocolMismatch(check);
        }
        JSONObject result = send(context, operation);
        if (!result.optBoolean("success", false)) return result;
        if (result.optInt("protocolVersion", -1)
                != BackgroundDisplayShell.PROTOCOL_VERSION)
            return protocolMismatch(result);
        return result;
    }

    private static JSONObject protocolMismatch(JSONObject received) {
        JSONObject mismatch = error("HELPER_RESTART_REQUIRED");
        try {
            mismatch.put("protocolVersion", received.optInt("protocolVersion", -1))
                    .put("protocolFieldPresent", received.has("protocolVersion"))
                    .put("expectedProtocolVersion", BackgroundDisplayShell.PROTOCOL_VERSION)
                    .put("responseFields", fieldNames(received));
        } catch (Exception ignored) {}
        return mismatch;
    }

    private static String fieldNames(JSONObject object) {
        StringBuilder names = new StringBuilder();
        Iterator<String> fields = object.keys();
        while (fields.hasNext()) {
            if (names.length() > 0) names.append(',');
            names.append(fields.next());
        }
        return names.toString();
    }

    private static JSONObject send(Context context, JSONObject operation) {
        BackgroundDisplayEndpointReceiver.Endpoint endpoint =
                BackgroundDisplayEndpointReceiver.getEndpoint(context);
        if (endpoint == null)
            return transportError("HELPER_NOT_CONNECTED", "ENDPOINT_DISCOVERY",
                    "NONE", "ENDPOINT_NOT_READY");
        Socket socket = null;
        String stage = "TCP_SOCKET_CREATE";
        try {
            socket = new Socket();
            stage = "TCP_CONNECT";
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), endpoint.port), 3000);
            stage = "TCP_TIMEOUT";
            socket.setSoTimeout(8000);
            stage = "STREAM_SETUP";
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            stage = "WRITE_REQUEST";
            JSONObject authenticatedOperation = new JSONObject(operation.toString());
            authenticatedOperation.put("_session", endpoint.token);
            writer.write(authenticatedOperation.toString());
            writer.write('\n');
            writer.flush();
            stage = "READ_RESPONSE";
            String response = reader.readLine();
            if (response == null)
                return transportError("INVALID_HELPER_RESPONSE", "READ_EOF", "NONE", "NONE");
            if (response.length() > 2 * 1024 * 1024)
                return transportError("INVALID_HELPER_RESPONSE", "RESPONSE_TOO_LARGE", "NONE", "NONE");
            stage = "PARSE_RESPONSE";
            return new JSONObject(response);
        } catch (Exception failure) {
            return transportError("HELPER_NOT_CONNECTED", stage,
                    failure.getClass().getSimpleName(), transportCode(failure));
        } finally {
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static String transportCode(Exception failure) {
        String message = failure.getMessage();
        if (message == null) return "UNKNOWN";
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("eacces") || normalized.contains("permission denied")) return "EACCES";
        if (normalized.contains("econnrefused") || normalized.contains("connection refused")) return "ECONNREFUSED";
        if (normalized.contains("enoent") || normalized.contains("no such file")) return "ENOENT";
        if (normalized.contains("etimedout") || normalized.contains("timed out")) return "ETIMEDOUT";
        if (normalized.contains("econnreset") || normalized.contains("connection reset")) return "ECONNRESET";
        if (normalized.contains("eaddrinuse") || normalized.contains("address already in use")) return "EADDRINUSE";
        if (normalized.contains("eafnosupport") || normalized.contains("address family not supported")) return "EAFNOSUPPORT";
        return "UNKNOWN";
    }

    private static JSONObject transportError(String code, String stage, String exceptionType,
                                             String transportCode) {
        JSONObject result = error(code);
        try {
            result.put("transportStage", stage)
                    .put("transportException", exceptionType)
                    .put("transportCode", transportCode);
        } catch (Exception ignored) {}
        return result;
    }

    static JSONObject command(Context context, String op) {
        try { return request(context, new JSONObject().put("op", op)); }
        catch (Exception ignored) { return error("INVALID_COMMAND"); }
    }

    private static JSONObject error(String code) {
        JSONObject reply = new JSONObject();
        try { reply.put("success", false).put("error", code); }
        catch (Exception ignored) {}
        return reply;
    }
}
