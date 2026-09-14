package com.crewpocket.helper;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Same-app client for Crew Helper's loopback Runtime bridge.
 *
 * The private capability token never leaves Crew Helper. Smart Planner only
 * receives compact semantic state and returns high-level semantic decisions.
 */
final class CrewRuntimeBridgeClient {
    private static final String BASE_URL = "http://127.0.0.1:8766";
    private final Context appContext;

    CrewRuntimeBridgeClient(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
    }

    JSONObject semanticScreen() throws Exception {
        return request("/semantic_screen", null, "GET", 2500);
    }

    JSONObject execute(SmartPlannerDecision decision) throws Exception {
        if (decision == null || decision.kind != SmartPlannerDecision.Kind.ACTION) {
            return new JSONObject().put("success", false).put("error", "NO_PLANNER_ACTION");
        }
        String action = decision.action;
        if (!SmartPlannerPolicy.isAllowedAction(action)
                || SmartPlannerPolicy.isNeverAllowed(action)) {
            return new JSONObject().put("success", false).put("error", "PLANNER_ACTION_BLOCKED");
        }
        if ("OPEN_APP".equals(action)) {
            return request("/launch", new JSONObject().put("app", decision.target), "POST", 5000);
        }
        if ("TAP".equals(action)) {
            return request("/semantic_tap",
                    new JSONObject().put("elementId", decision.elementId), "POST", 3000);
        }
        if ("TYPE".equals(action)) {
            return request("/type", new JSONObject().put("text", decision.text), "POST", 3500);
        }
        if ("SEARCH".equals(action)) {
            return request("/search_in_app", new JSONObject().put("query", decision.text), "POST", 4500);
        }
        if ("COMMIT_SEARCH".equals(action)) {
            return request("/commit_search", new JSONObject(), "POST", 3500);
        }
        if ("SCROLL".equals(action)) {
            return request("/scroll",
                    new JSONObject().put("direction", decision.direction), "POST", 3500);
        }
        if ("BACK".equals(action) || "HOME".equals(action)) {
            return request("/key", new JSONObject().put("key", action), "POST", 2500);
        }
        return new JSONObject().put("success", false).put("error", "PLANNER_ACTION_UNSUPPORTED");
    }

    private JSONObject request(String path, JSONObject body, String method, int timeoutMs) throws Exception {
        if (appContext == null) throw new IllegalStateException("APP_CONTEXT_UNAVAILABLE");
        String token = AppConfig.getLocalBridgeToken(appContext);
        if (token.isEmpty()) throw new IllegalStateException("BRIDGE_TOKEN_UNAVAILABLE");

        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(Math.min(timeoutMs, 2000));
        connection.setReadTimeout(timeoutMs);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Crew-Bridge-Token", token);
        if (body != null && !"GET".equals(method)) {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Content-Length", String.valueOf(payload.length));
            OutputStream out = connection.getOutputStream();
            try { out.write(payload); out.flush(); }
            finally { out.close(); }
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String text = readAll(stream);
        connection.disconnect();
        if (text.trim().isEmpty()) {
            return new JSONObject().put("success", false)
                    .put("error", "EMPTY_RUNTIME_RESPONSE").put("httpStatus", status);
        }
        JSONObject result = new JSONObject(text);
        if (status < 200 || status >= 300) result.put("httpStatus", status);
        return result;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        try {
            while ((line = reader.readLine()) != null) out.append(line);
        } finally {
            reader.close();
        }
        return out.toString();
    }
}
