package com.crewpocket.helper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Owns low-level localhost bridge execution and physical phone primitives.
 *
 * Authorization, SEND/SEARCH policy, mutation lifecycle, working-context updates
 * and post-action verification remain in NativeGeminiLiveClient.
 */
final class PhoneRuntimeExecutor {
    static final class MutationResult {
        final JSONObject result;
        final boolean observeAfter;
        final String actionKey;

        MutationResult(JSONObject result, boolean observeAfter, String actionKey) {
            this.result = result == null ? new JSONObject() : result;
            this.observeAfter = observeAfter;
            this.actionKey = actionKey == null ? "" : actionKey;
        }
    }

    private final Context appContext;
    private final LiveVisionController visionController;
    private final ArrayList<JSONObject> lastCandidateApps =
            new ArrayList<JSONObject>();
    private volatile HttpURLConnection activeConnection;

    PhoneRuntimeExecutor(Context appContext, LiveVisionController visionController) {
        this.appContext = appContext;
        this.visionController = visionController;
    }

    void resetTransientState() {
        lastCandidateApps.clear();
    }

    void cancelActiveRequest() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            try { connection.disconnect(); } catch (Exception ignored) {}
        }
    }

    JSONObject get(String endpoint) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:8766" + endpoint).openConnection();
            activeConnection = connection;
            connection.setRequestMethod("GET");
            authenticate(connection);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            int code = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream(),
                    "UTF-8"));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            reader.close();
            return text.length() == 0
                    ? new JSONObject()
                    : new JSONObject(text.toString());
        } finally {
            if (connection != null) connection.disconnect();
            if (activeConnection == connection) activeConnection = null;
        }
    }

    JSONObject post(String endpoint, JSONObject payload) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:8766" + endpoint).openConnection();
            activeConnection = connection;
            connection.setRequestMethod("POST");
            connection.setRequestProperty(
                    "Content-Type", "application/json; charset=utf-8");
            authenticate(connection);
            connection.setDoOutput(true);
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(7000);
            byte[] body = (payload == null ? new JSONObject() : payload)
                    .toString().getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            out.write(body);
            out.close();
            int code = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream(),
                    "UTF-8"));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            reader.close();
            JSONObject response = text.length() == 0
                    ? new JSONObject()
                    : new JSONObject(text.toString());
            if (!response.has("success")) {
                response.put("success", code >= 200 && code < 300);
            }
            return response;
        } finally {
            if (connection != null) connection.disconnect();
            if (activeConnection == connection) activeConnection = null;
        }
    }

    MutationResult launchApp(JSONObject args) throws Exception {
        JSONObject safeArgs = args == null ? new JSONObject() : args;
        String app = safeArgs.optString("app", "").trim();
        int explicitIndex = safeArgs.optInt("index", -1);
        String explicitPkg = safeArgs.optString("package_name", "").trim();

        if (!explicitPkg.isEmpty()) {
            JSONObject reply = post(
                    "/launch", new JSONObject().put("package", explicitPkg));
            if (reply.optBoolean("success")) {
                lastCandidateApps.clear();
                reply.put("app", app.isEmpty() ? explicitPkg : app)
                        .put("message", "已啟動 App，以下為啟動後的最新畫面。");
            }
            return new MutationResult(
                    reply, true, "launch:" + explicitPkg);
        }

        int selectedIndex = parseOrdinalIndex(app);
        if (selectedIndex < 0 && explicitIndex > 0) {
            selectedIndex = explicitIndex - 1;
        }
        if (selectedIndex >= 0 && !lastCandidateApps.isEmpty()) {
            if (selectedIndex >= lastCandidateApps.size()) {
                return new MutationResult(
                        new JSONObject()
                                .put("success", false)
                                .put("error", "候選 App 編號超出範圍"),
                        false,
                        "");
            }
            JSONObject chosen = lastCandidateApps.get(selectedIndex);
            lastCandidateApps.clear();
            String pkg = chosen.optString("package", "");
            JSONObject reply = post(
                    "/launch", new JSONObject().put("package", pkg));
            if (reply.optBoolean("success")) {
                reply.put("app", chosen.optString("label", "App"))
                        .put("message", "已啟動 App，以下為啟動後的最新畫面。");
            }
            return new MutationResult(reply, true, "launch:" + pkg);
        }

        if (app.isEmpty()) {
            return new MutationResult(
                    new JSONObject()
                            .put("success", false)
                            .put("error", "App 名稱不可為空"),
                    false,
                    "");
        }

        JSONObject reply = post("/launch", new JSONObject().put("app", app));
        if (reply.optBoolean("success", false)) {
            lastCandidateApps.clear();
            String pkg = reply.optString("package", "");
            reply.put("app", reply.optString("label", app))
                    .put("message", "已啟動 App，以下為啟動後的最新畫面。");
            return new MutationResult(
                    reply,
                    true,
                    "launch:" + (pkg.isEmpty() ? app : pkg));
        }

        if ("MULTIPLE_MATCHES".equals(reply.optString("status", ""))) {
            JSONArray matches = reply.optJSONArray("matches");
            lastCandidateApps.clear();
            JSONArray candidates = new JSONArray();
            StringBuilder prompt =
                    new StringBuilder("找到多個相近 App，請說第幾個：\n");
            if (matches != null) {
                for (int i = 0; i < matches.length(); i++) {
                    JSONObject candidate = matches.optJSONObject(i);
                    if (candidate == null) continue;
                    lastCandidateApps.add(candidate);
                    candidates.put(new JSONObject()
                            .put("index", lastCandidateApps.size())
                            .put("label", candidate.optString("label", "App"))
                            .put("package", candidate.optString("package", "")));
                    prompt.append(lastCandidateApps.size())
                            .append(". ")
                            .append(candidate.optString("label", "App"))
                            .append("\n");
                }
            }
            reply = new JSONObject()
                    .put("success", false)
                    .put("status", "MULTIPLE_MATCHES")
                    .put("candidates", candidates)
                    .put("error", prompt.toString().trim())
                    .put("instruction",
                            "只詢問使用者要開第幾個；回答後再呼叫 phone_action(action=OPEN_APP,target=使用者選的序號)。");
        }
        return new MutationResult(reply, false, "");
    }

    JSONObject swipe(JSONObject args) throws Exception {
        JSONObject safeArgs = args == null ? new JSONObject() : args;
        String direction = safeArgs.optString("direction", "up").toLowerCase();
        String distance = safeArgs.optString("distance", "normal").toLowerCase();

        JSONObject metrics = get("/status");
        int width = metrics.optInt(
                "screenWidth", visionController.lastScreenWidth());
        int height = metrics.optInt(
                "screenHeight", visionController.lastScreenHeight());
        if (width <= 1 || height <= 1) {
            return new JSONObject()
                    .put("success", false)
                    .put("error", "無法取得目前裝置螢幕尺寸");
        }

        int x1 = Math.round(width * 0.50f);
        int y1 = Math.round(height * 0.74f);
        int x2 = Math.round(width * 0.50f);
        int y2 = Math.round(height * 0.22f);
        int duration = 320;

        if ("down".equals(direction)) {
            y1 = Math.round(height * 0.22f);
            y2 = Math.round(height * 0.74f);
        } else if ("left".equals(direction)) {
            x1 = Math.round(width * 0.87f);
            y1 = Math.round(height * 0.50f);
            x2 = Math.round(width * 0.13f);
            y2 = Math.round(height * 0.50f);
        } else if ("right".equals(direction)) {
            x1 = Math.round(width * 0.13f);
            y1 = Math.round(height * 0.50f);
            x2 = Math.round(width * 0.87f);
            y2 = Math.round(height * 0.50f);
        }

        if ("long".equals(distance)
                || "page".equals(distance)
                || "fast".equals(distance)) {
            duration = 280;
            if ("up".equals(direction)) {
                y1 = Math.round(height * 0.87f);
                y2 = Math.round(height * 0.13f);
            } else if ("down".equals(direction)) {
                y1 = Math.round(height * 0.13f);
                y2 = Math.round(height * 0.87f);
            } else if ("left".equals(direction)) {
                x1 = Math.round(width * 0.94f);
                x2 = Math.round(width * 0.06f);
            } else if ("right".equals(direction)) {
                x1 = Math.round(width * 0.06f);
                x2 = Math.round(width * 0.94f);
            }
        } else if ("short".equals(distance)
                || "little".equals(distance)) {
            duration = 260;
            if ("up".equals(direction)) {
                y1 = Math.round(height * 0.58f);
                y2 = Math.round(height * 0.38f);
            } else if ("down".equals(direction)) {
                y1 = Math.round(height * 0.38f);
                y2 = Math.round(height * 0.58f);
            } else if ("left".equals(direction)) {
                x1 = Math.round(width * 0.66f);
                x2 = Math.round(width * 0.34f);
            } else if ("right".equals(direction)) {
                x1 = Math.round(width * 0.34f);
                x2 = Math.round(width * 0.66f);
            }
        }

        JSONObject before = new JSONObject();
        try { before = get("/nodes"); } catch (Exception ignored) {}

        JSONObject reply = new JSONObject();
        String execution = "gesture";
        String currentPkg =
                before.optString("package", "").toLowerCase(Locale.ROOT);
        boolean isMapsOrCanvas = currentPkg.contains("maps")
                || currentPkg.contains("game")
                || currentPkg.contains("camera");

        if (!isMapsOrCanvas
                && ("up".equals(direction) || "down".equals(direction))) {
            reply = post(
                    "/scroll",
                    new JSONObject().put(
                            "direction",
                            "up".equals(direction) ? "forward" : "backward"));
            execution = "ui_node";
            Thread.sleep(250);
        }

        JSONObject after = new JSONObject();
        try { after = get("/nodes"); } catch (Exception ignored) {}
        boolean changed = !nodeSignature(before).equals(nodeSignature(after));

        if (!reply.optBoolean("success") || !changed) {
            reply = post(
                    "/swipe",
                    new JSONObject()
                            .put("x1", x1).put("y1", y1)
                            .put("x2", x2).put("y2", y2)
                            .put("duration", duration));
            execution = "gesture";
            Thread.sleep(350);
            try { after = get("/nodes"); } catch (Exception ignored) {}
            changed = !nodeSignature(before).equals(nodeSignature(after));
        }

        return reply
                .put("direction", direction)
                .put("distance", distance)
                .put("screenSize", width + "x" + height)
                .put("execution", execution)
                .put("screenChanged", changed);
    }

    JSONObject tap(JSONObject args) throws Exception {
        JSONObject safeArgs = args == null ? new JSONObject() : args;
        double targetX = safeArgs.optDouble("x", -1);
        double targetY = safeArgs.optDouble("y", -1);
        String label = safeArgs.optString(
                "label",
                safeArgs.optString("text", safeArgs.optString("name", "")))
                .trim();
        String id = safeArgs.optString("id", "").trim();
        String semanticHint = safeArgs.optString("semanticHint",
                safeArgs.optString("semantic_hint", "")).trim();
        String role = safeArgs.optString("role", "").trim();
        String elementId = safeArgs.optString("elementId",
                safeArgs.optString("element_id", "")).trim();
        String coordinateSpace =
                safeArgs.optString("coordinate_space", "")
                        .trim().toLowerCase();

        JSONArray fallbackTrace = new JSONArray();
        boolean resolvedFromNode = false;
        final boolean hasExplicitCoordinate = targetX >= 0 && targetY >= 0;
        boolean hasSemanticTarget = !label.isEmpty()
                || !id.isEmpty()
                || !semanticHint.isEmpty()
                || !role.isEmpty()
                || !elementId.isEmpty();

        // 1) Semantic locator first. If two candidates are too close, stop here
        // and ask the user instead of silently falling through to a guess.
        if (hasSemanticTarget) {
            try {
                JSONObject semantic = post(
                        "/click_v2",
                        new JSONObject()
                                .put("label", label)
                                .put("id", id)
                                .put("semanticHint", semanticHint)
                                .put("role", role)
                                .put("elementId", elementId));
                fallbackTrace.put("semantic_v2:"
                        + semantic.optString("decision",
                                semantic.optString("error", "UNKNOWN")));

                String semanticDecision =
                        semantic.optString("decision", "");
                LocatorFallbackPolicy.Next semanticNext =
                        LocatorFallbackPolicy.afterSemantic(
                                semanticDecision,
                                !label.isEmpty() || !id.isEmpty(),
                                hasExplicitCoordinate);

                if ("MULTIPLE_MATCHES".equals(
                                semantic.optString("status", ""))
                        || semanticNext
                                == LocatorFallbackPolicy.Next.ASK_USER) {
                    semantic.put("resolvedFrom", "semantic_v2")
                            .put("fallbackTrace", fallbackTrace)
                            .put("stepResult", "STEP_FAILED")
                            .put("taskState", "NEED_USER")
                            .put("instruction",
                                    "定位候選太接近。列出 Runtime 提供的 candidates 請使用者選；不要降級猜 label 或座標。");
                    return semantic;
                }

                if (semanticNext
                        == LocatorFallbackPolicy.Next.REOBSERVE) {
                    return semantic
                            .put("resolvedFrom", "semantic_v2")
                            .put("fallbackTrace", fallbackTrace)
                            .put("stepResult", "STEP_FAILED")
                            .put("taskState", "IN_PROGRESS")
                            .put("nextRequirement", "inspect_ui once")
                            .put("instruction",
                                    "目前定位信心不足以執行。先取得一次 fresh inspect_ui，再重新定位；不要直接降級成座標。");
                }

                if (semanticNext == LocatorFallbackPolicy.Next.STOP) {
                    return semantic
                            .put("resolvedFrom", "semantic_v2")
                            .put("fallbackTrace", fallbackTrace)
                            .put("stepResult", "STEP_FAILED");
                }

                if (semantic.optBoolean("success", false)) {
                    return semantic.put("resolvedFrom", "semantic_v2")
                            .put("fallbackTrace", fallbackTrace);
                }
            } catch (Exception error) {
                fallbackTrace.put("semantic_v2:BRIDGE_ERROR");
            }
        }

        // 2) Legacy label/id Accessibility lookup.
        if (!label.isEmpty() || !id.isEmpty()) {
            try {
                JSONObject nodeClick = post(
                        "/click",
                        new JSONObject().put("label", label).put("id", id));
                fallbackTrace.put("label_lookup:"
                        + (nodeClick.optBoolean("success")
                                ? "SUCCESS" : "MISS"));
                if (nodeClick.optBoolean("success")) {
                    return nodeClick
                            .put("resolvedFrom", "label_lookup")
                            .put("fallbackTrace", fallbackTrace);
                }

                // 3) Node-bounds degradation is allowed only for ONE
                // deterministic structural match. Never take the first of
                // several fuzzy matches.
                JSONObject nodesResp = get("/nodes");
                if (nodesResp.optBoolean("success")) {
                    JSONArray nodes = nodesResp.optJSONArray("nodes");
                    JSONObject uniqueBounds = null;
                    int structuralMatches = 0;
                    if (nodes != null) {
                        for (int i = 0; i < nodes.length(); i++) {
                            JSONObject node = nodes.getJSONObject(i);
                            String text = node.optString("text", "");
                            String desc = node.optString("desc", "");
                            String nodeId = node.optString("id", "");
                            boolean matchId = !id.isEmpty()
                                    && nodeId.toLowerCase(Locale.ROOT)
                                            .contains(id.toLowerCase(Locale.ROOT));
                            boolean matchLabel = !label.isEmpty()
                                    && (text.toLowerCase(Locale.ROOT)
                                                .contains(label.toLowerCase(Locale.ROOT))
                                        || desc.toLowerCase(Locale.ROOT)
                                                .contains(label.toLowerCase(Locale.ROOT)));
                            if (!matchId && !matchLabel) continue;
                            JSONObject bounds = node.optJSONObject("bounds");
                            if (bounds == null) continue;
                            structuralMatches++;
                            if (structuralMatches == 1) {
                                uniqueBounds = bounds;
                            }
                        }
                    }

                    if (structuralMatches > 1) {
                        fallbackTrace.put("node_bounds:AMBIGUOUS");
                        return new JSONObject()
                                .put("success", false)
                                .put("stepResult", "STEP_FAILED")
                                .put("taskState", "NEED_USER")
                                .put("error", "UI_TARGET_AMBIGUOUS_LEGACY")
                                .put("fallbackTrace", fallbackTrace)
                                .put("instruction",
                                        "legacy label/id 也命中多個元件。不要取第一個或改猜座標；請重新 inspect_ui 或請使用者選候選。");
                    }

                    LocatorFallbackPolicy.Next afterLabel =
                            LocatorFallbackPolicy.afterLabel(
                                    false,
                                    structuralMatches == 1,
                                    hasExplicitCoordinate);
                    if (afterLabel
                            == LocatorFallbackPolicy.Next.EXECUTE_SEMANTIC
                            && uniqueBounds != null) {
                        targetX = (
                                uniqueBounds.optDouble("left", 0)
                                + uniqueBounds.optDouble("right", 0))
                                / 2.0;
                        targetY = (
                                uniqueBounds.optDouble("top", 0)
                                + uniqueBounds.optDouble("bottom", 0))
                                / 2.0;
                        resolvedFromNode = true;
                        fallbackTrace.put("node_bounds:UNIQUE");
                    }
                }
            } catch (Exception ignored) {
                fallbackTrace.put("label_lookup:BRIDGE_ERROR");
            }
        }

        if (targetX < 0 || targetY < 0) {
            return new JSONObject()
                    .put("success", false)
                    .put("stepResult", "STEP_FAILED")
                    .put("error", "UI_TARGET_NOT_FOUND")
                    .put("fallbackTrace", fallbackTrace)
                    .put("instruction",
                            "語意與 label 定位都失敗，而且沒有可信座標。不要猜；請重新 inspect_ui 或請使用者指明目標。");
        }

        // 4) Explicit coordinates are the final fallback only.
        if (!resolvedFromNode && "image".equals(coordinateSpace)) {
            if (visionController.lastScreenWidth() <= 1
                    || visionController.lastScreenHeight() <= 1) {
                return new JSONObject()
                        .put("success", false)
                        .put("fallbackTrace", fallbackTrace)
                        .put("error",
                                "尚未取得目前螢幕尺寸，請先要求查看螢幕後再依影像座標點擊");
            }
            targetX = VisionCoordinateMapper.imageToScreen(
                    targetX,
                    visionController.lastVisionWidth(),
                    visionController.lastScreenWidth());
            targetY = VisionCoordinateMapper.imageToScreen(
                    targetY,
                    visionController.lastVisionHeight(),
                    visionController.lastScreenHeight());
            fallbackTrace.put("coordinate:image");
        } else if (!resolvedFromNode
                && "normalized_1000".equals(coordinateSpace)) {
            if (visionController.lastScreenWidth() <= 1
                    || visionController.lastScreenHeight() <= 1) {
                return new JSONObject()
                        .put("success", false)
                        .put("fallbackTrace", fallbackTrace)
                        .put("error",
                                "尚未取得目前螢幕尺寸，請先 inspect_ui 或查看螢幕");
            }
            targetX = (targetX / 1000.0)
                    * visionController.lastScreenWidth();
            targetY = (targetY / 1000.0)
                    * visionController.lastScreenHeight();
            fallbackTrace.put("coordinate:normalized_1000");
        } else if (!resolvedFromNode
                && coordinateSpace.isEmpty()
                && targetX <= 1.0
                && targetY <= 1.0
                && (targetX > 0 || targetY > 0)) {
            targetX = targetX
                    * Math.max(1, visionController.lastScreenWidth());
            targetY = targetY
                    * Math.max(1, visionController.lastScreenHeight());
            fallbackTrace.put("coordinate:normalized_1");
        } else if (!resolvedFromNode
                && coordinateSpace.isEmpty()
                && targetX <= 1000.0
                && targetY <= 1000.0
                && targetX > 0
                && targetY > 0
                && targetY < 1200) {
            targetX = (targetX / 1000.0)
                    * Math.max(1, visionController.lastScreenWidth());
            targetY = (targetY / 1000.0)
                    * Math.max(1, visionController.lastScreenHeight());
            fallbackTrace.put("coordinate:legacy_normalized_1000");
        } else if (!resolvedFromNode) {
            fallbackTrace.put("coordinate:screen");
        }

        JSONObject reply = post(
                "/tap",
                new JSONObject()
                        .put("x", Math.round(targetX))
                        .put("y", Math.round(targetY)));
        return reply
                .put("resolvedFrom",
                        resolvedFromNode
                                ? "node_bounds"
                                : (coordinateSpace.isEmpty()
                                        ? "coordinate"
                                        : coordinateSpace))
                .put("fallbackTrace", fallbackTrace)
                .put(
                        "visionSize",
                        visionController.lastVisionWidth()
                                + "x"
                                + visionController.lastVisionHeight())
                .put(
                        "screenSize",
                        visionController.lastScreenWidth()
                                + "x"
                                + visionController.lastScreenHeight());
    }

    JSONObject semanticTap(String elementId) throws Exception {
        try {
            return post(
                    "/semantic_tap",
                    new JSONObject().put("elementId", elementId));
        } catch (Exception error) {
            return new JSONObject()
                    .put("success", false)
                    .put(
                            "error",
                            error.getMessage() == null
                                    ? "tap failed"
                                    : error.getMessage());
        }
    }

    JSONObject typeText(String text) throws Exception {
        JSONObject first = post(
                "/type", new JSONObject().put("text", text));
        if (first.optBoolean("success", false)) {
            return first;
        }

        String error = first.optString("error", "");
        if (!isRecoverableTypeFailure(error)) {
            return first;
        }

        try {
            Thread.sleep(220L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return first;
        }

        JSONObject retry = post(
                "/type", new JSONObject().put("text", text));
        retry.put("retried", true);
        retry.put("firstError", error);
        return retry;
    }

    private boolean isRecoverableTypeFailure(String error) {
        String code = error == null ? "" : error.trim();
        return "NO_ACTIVE_WINDOW".equals(code)
                || "NO_EDITABLE_TARGET".equals(code)
                || "SET_TEXT_AND_PASTE_REJECTED".equals(code);
    }

    JSONObject pressKey(String key) throws Exception {
        String normalized = key == null ? "" : key.toUpperCase();
        if (!("HOME".equals(normalized)
                || "BACK".equals(normalized)
                || "RECENTS".equals(normalized)
                || "NOTIFICATIONS".equals(normalized)
                || "QUICK_SETTINGS".equals(normalized)
                || "POWER_DIALOG".equals(normalized))) {
            return new JSONObject()
                    .put("success", false)
                    .put("error", "不支援的系統按鍵");
        }
        return post("/key", new JSONObject().put("key", normalized));
    }

    JSONObject commitSearch() throws Exception {
        return post("/commit_search", new JSONObject());
    }

    private int parseOrdinalIndex(String input) {
        if (input == null) return -1;
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.equals("第一個") || value.equals("第1個")
                || value.equals("第 1 個") || value.equals("1")
                || value.equals("first") || value.equals("one")
                || value.equals("前一個")) return 0;
        if (value.equals("第二個") || value.equals("第2個")
                || value.equals("第 2 個") || value.equals("2")
                || value.equals("second") || value.equals("two")) return 1;
        if (value.equals("第三個") || value.equals("第3個")
                || value.equals("第 3 個") || value.equals("3")
                || value.equals("third") || value.equals("three")) return 2;
        if (value.equals("第四個") || value.equals("第4個")
                || value.equals("第 4 個") || value.equals("4")
                || value.equals("fourth") || value.equals("four")) return 3;
        if (value.equals("第五個") || value.equals("第5個")
                || value.equals("第 5 個") || value.equals("5")
                || value.equals("fifth") || value.equals("five")) return 4;
        if (value.equals("最後一個") || value.equals("最後")
                || value.equals("last")) {
            return lastCandidateApps.isEmpty()
                    ? -1
                    : lastCandidateApps.size() - 1;
        }
        return -1;
    }

    private String nodeSignature(JSONObject response) {
        if (response == null || !response.optBoolean("success")) {
            return "unavailable";
        }
        JSONArray nodes = response.optJSONArray("nodes");
        if (nodes == null) return "empty";
        StringBuilder signature = new StringBuilder();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            signature.append(node.optString("text")).append('|')
                    .append(node.optString("desc")).append('|')
                    .append(node.optString("className")).append('|');
            JSONObject bounds = node.optJSONObject("bounds");
            if (bounds != null) {
                signature.append(bounds.optInt("left")).append(',')
                        .append(bounds.optInt("top")).append(',')
                        .append(bounds.optInt("right")).append(',')
                        .append(bounds.optInt("bottom"));
            }
            signature.append(';');
        }
        return Integer.toHexString(signature.toString().hashCode());
    }

    private void authenticate(HttpURLConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "LOCAL_BRIDGE_CONNECTION_REQUIRED");
        }
        if (appContext == null) {
            throw new IllegalStateException("LOCAL_BRIDGE_CONTEXT_REQUIRED");
        }
        String token = AppConfig.getLocalBridgeToken(appContext);
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException(
                    "LOCAL_BRIDGE_TOKEN_UNAVAILABLE");
        }
        connection.setRequestProperty("X-Crew-Bridge-Token", token);
    }
}
