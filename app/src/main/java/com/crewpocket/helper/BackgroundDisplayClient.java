package com.crewpocket.helper;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Local-only, user-initiated POC. No Gemini Live tool uses this bridge. */
final class BackgroundDisplayClient {
    private BackgroundDisplayClient() {}

    static JSONObject request(JSONObject operation) {
        if (android.os.Build.VERSION.SDK_INT < 31) return error("ANDROID_12_REQUIRED");
        if (operation == null) return error("EMPTY_REQUEST");
        String op = operation.optString("op", "");
        if (!"status".equals(op)) {
            JSONObject hello = new JSONObject();
            try { hello.put("op", "status"); }
            catch (Exception invalid) { return error("INVALID_PROTOCOL_REQUEST"); }
            JSONObject check = send(hello);
            if (!check.optBoolean("success", false)) return check;
            if (check.optInt("protocolVersion", -1)
                    != BackgroundDisplayShell.PROTOCOL_VERSION)
                return error("HELPER_RESTART_REQUIRED");
        }
        JSONObject result = send(operation);
        if (!result.optBoolean("success", false)) return result;
        if (result.optInt("protocolVersion", -1)
                != BackgroundDisplayShell.PROTOCOL_VERSION)
            return error("HELPER_RESTART_REQUIRED");
        return result;
    }

    private static JSONObject send(JSONObject operation) {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(BackgroundDisplayShell.SOCKET,
                    LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(8000);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            writer.write(operation.toString());
            writer.write('\n');
            writer.flush();
            String response = reader.readLine();
            if (response == null || response.length() > 2 * 1024 * 1024)
                return error("INVALID_HELPER_RESPONSE");
            return new JSONObject(response);
        } catch (Exception failure) {
            return error("HELPER_NOT_CONNECTED");
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    static JSONObject command(String op) {
        try { return request(new JSONObject().put("op", op)); }
        catch (Exception ignored) { return error("INVALID_COMMAND"); }
    }

    private static JSONObject error(String code) {
        JSONObject reply = new JSONObject();
        try { reply.put("success", false).put("error", code); }
        catch (Exception ignored) {}
        return reply;
    }
}
