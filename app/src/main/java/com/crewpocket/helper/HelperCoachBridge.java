package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Authenticated Pocket Coach endpoints and the in-memory task event outbox. */
final class HelperCoachBridge {
    interface AdviceListener {
        /** Return true when the advice was accepted for asynchronous Runtime revalidation. */
        boolean onCoachAdvice(
                HelperCoachRuntimePolicy.Observation observation,
                HelperCoachRuntimePolicy.Advice advice,
                String retryTarget,
                long intentGeneration);
    }

    static final class HttpResponse {
        final int statusCode;
        final String statusText;
        final String body;
        HttpResponse(int statusCode, String statusText, JSONObject body) {
            this.statusCode = statusCode;
            this.statusText = statusText;
            this.body = body == null ? "{}" : body.toString();
        }
    }

    private static final String NONCE_PREFS = "crew_helper_coach_nonces_v1";
    private static final String NONCE_KEY = "seen";
    private static final int MAX_EVENTS = 128;
    private static final int MAX_OBSERVATIONS = 64;
    private static final int MAX_NONCES = 256;
    private static final long OBSERVATION_TTL_MS = 60_000L;
    private static final long RATE_WINDOW_MS = 60_000L;
    private static final Object INSTANCE_LOCK = new Object();
    private static HelperCoachBridge instance;

    static HelperCoachBridge get(Context context) {
        synchronized (INSTANCE_LOCK) {
            if (instance == null && context != null) {
                instance = new HelperCoachBridge(context.getApplicationContext());
            }
            return instance;
        }
    }

    private static final class StoredObservation {
        final HelperCoachRuntimePolicy.Observation policy;
        final String retryTarget;
        final long intentGeneration;
        StoredObservation(HelperCoachRuntimePolicy.Observation policy,
                          String retryTarget,
                          long intentGeneration) {
            this.policy = policy;
            this.retryTarget = retryTarget == null ? "" : retryTarget;
            this.intentGeneration = intentGeneration;
        }
    }

    private final Context appContext;
    private final SharedPreferences noncePrefs;
    private final HelperCoachRegistryStore registry;
    private final HelperCoachRuntimePolicy runtimePolicy =
            new HelperCoachRuntimePolicy();
    private final Object lock = new Object();
    private final ArrayList<JSONObject> events = new ArrayList<JSONObject>();
    private final LinkedHashMap<String, StoredObservation> observations =
            new LinkedHashMap<String, StoredObservation>();
    private final LinkedHashMap<String, Boolean> terminalAdviceEvents =
            new LinkedHashMap<String, Boolean>();
    private final Map<String, ArrayList<Long>> rateEvents =
            new HashMap<String, ArrayList<Long>>();
    private volatile AdviceListener adviceListener;
    private String activeTaskId = "";
    private long activeIntentGeneration = -1L;

    private HelperCoachBridge(Context context) {
        appContext = context;
        noncePrefs = context == null ? null
                : context.getSharedPreferences(NONCE_PREFS, Context.MODE_PRIVATE);
        registry = new HelperCoachRegistryStore(context);
    }

    void setAdviceListener(AdviceListener listener) {
        adviceListener = listener;
    }

    JSONObject recordTaskObservation(
            String taskId,
            long intentGeneration,
            String appPackage,
            String goal,
            String goalIntent,
            int stepIndex,
            String uiSummary,
            JSONArray availableActions,
            String previousAction,
            String result,
            String errorCode,
            JSONArray evidenceRefs,
            String uiFingerprint,
            String expectedPostcondition,
            boolean previousTapRetryable,
            String retryTarget) {
        if (!isEnabled() || CoachDataRedactor.isSensitivePackage(appPackage)) {
            return new JSONObject();
        }
        String safeTask = safeId(taskId);
        String safePackage = safePackage(appPackage);
        String safeFingerprint = safeId(uiFingerprint);
        if (safeTask.isEmpty() || safePackage.isEmpty() || safeFingerprint.isEmpty()) {
            return new JSONObject();
        }
        long now = System.currentTimeMillis();
        String eventId = UUID.randomUUID().toString();
        long expiresAt = now + OBSERVATION_TTL_MS;
        JSONArray safeActions = filterActions(availableActions);
        if (!safeActions.toString().contains(
                HelperCoachRuntimePolicy.ACTION_INSPECT_UI)) {
            safeActions.put(HelperCoachRuntimePolicy.ACTION_INSPECT_UI);
        }
        boolean canRetry = previousTapRetryable
                && safeActions.toString().contains(
                        HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP)
                && !CoachDataRedactor.safeLabel(retryTarget).isEmpty();
        if (!canRetry) {
            safeActions = withoutAction(
                    safeActions,
                    HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP);
        }
        HelperCoachRuntimePolicy.Observation policyObservation =
                new HelperCoachRuntimePolicy.Observation(
                        eventId, safeTask, safePackage, safeFingerprint,
                        safeId(expectedPostcondition), now, expiresAt, canRetry,
                        toList(safeActions));
        StoredObservation stored = new StoredObservation(
                policyObservation,
                canRetry ? CoachDataRedactor.safeLabel(retryTarget) : "",
                intentGeneration);

        JSONObject event = new JSONObject();
        try {
            event.put("protocol_version", 1)
                    .put("event_id", eventId)
                    .put("task_id", safeTask)
                    .put("timestamp", HelperCoachTime.formatIsoUtc(now))
                    .put("source", "helper")
                    .put("kind", "task_observation")
                    .put("app_package", safePackage)
                    .put("goal", CoachDataRedactor.safeGoal(goal, goalIntent))
                    .put("step_index", Math.max(0, stepIndex))
                    .put("ui_summary", CoachDataRedactor.safeFreeText(uiSummary, 600))
                    .put("available_actions", safeActions)
                    .put("active_skills", registry.activeSkillSummaries(safePackage))
                    .put("previous_action", CoachDataRedactor.safeFreeText(previousAction, 120))
                    .put("result", safeResult(result))
                    .put("error_code", CoachDataRedactor.safeErrorCode(errorCode))
                    .put("evidence_refs", sanitizeRefs(evidenceRefs));
        } catch (Exception ignored) {
            return new JSONObject();
        }
        synchronized (lock) {
            activeTaskId = safeTask;
            activeIntentGeneration = intentGeneration;
            observations.put(eventId, stored);
            while (observations.size() > MAX_OBSERVATIONS) {
                Iterator<String> iterator = observations.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
            appendEventLocked(event);
        }
        return cloneObject(event);
    }

    void recordCoachActionResult(
            HelperCoachRuntimePolicy.Advice advice,
            String status,
            String errorCode,
            boolean runtimeActionSuccess,
            String evidence) {
        if (!isEnabled() || advice == null) return;
        ArrayList<String> evidenceRefs = new ArrayList<String>();
        if (evidence != null && !evidence.trim().isEmpty()) {
            evidenceRefs.add(evidence.trim());
        }
        Map<String, Object> payload = HelperCoachActionResultPolicy.buildEvent(
                "coach_result_" + UUID.randomUUID().toString(),
                advice.taskId,
                HelperCoachTime.formatIsoUtc(System.currentTimeMillis()),
                advice.observationEventId,
                advice.adviceId,
                advice.suggestedActionId,
                status,
                errorCode,
                runtimeActionSuccess,
                evidenceRefs);
        if (payload == null) return;
        JSONObject event;
        try { event = new JSONObject(payload); }
        catch (Exception ignored) { return; }
        synchronized (lock) {
            if (terminalAdviceEvents.containsKey(advice.adviceId)) return;
            terminalAdviceEvents.put(advice.adviceId, Boolean.TRUE);
            while (terminalAdviceEvents.size() > 512) {
                Iterator<String> iterator = terminalAdviceEvents.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
            appendEventLocked(event);
        }
    }

    void recordTaskOutcome(
            String taskId,
            String appPackage,
            long durationMs,
            boolean verified,
            String verificationMethod,
            String completionEvidence) {
        if (!isEnabled()) return;
        long now = System.currentTimeMillis();
        JSONObject event = new JSONObject();
        try {
            event.put("protocol_version", 1)
                    .put("event_id", UUID.randomUUID().toString())
                    .put("task_id", safeId(taskId))
                    .put("timestamp", HelperCoachTime.formatIsoUtc(now))
                    .put("source", "helper")
                    .put("kind", "task_outcome")
                    .put("app_package", safePackage(appPackage))
                    .put("verified", verified)
                    .put("verification_method", CoachDataRedactor.safeErrorCode(verificationMethod))
                    .put("duration_ms", Math.max(0L, durationMs))
                    .put("result", verified ? "success" : "unknown")
                    .put("error_code", verified ? "NONE" : "TASK_NOT_VERIFIED")
                    .put("evidence_refs", safeEvidence(completionEvidence));
        } catch (Exception ignored) { return; }
        synchronized (lock) {
            appendEventLocked(event);
            if (safeId(taskId).equals(activeTaskId)) {
                activeTaskId = "";
                activeIntentGeneration = -1L;
            }
        }
        if (verified) {
            registry.recordVerifiedOutcomeForCandidates(
                    safePackage(appPackage), safeId(taskId),
                    CoachDataRedactor.safeErrorCode(completionEvidence));
        }
    }

    void clearActiveTask(String taskId) {
        synchronized (lock) {
            if (taskId == null || taskId.trim().isEmpty()
                    || taskId.trim().equals(activeTaskId)) {
                activeTaskId = "";
                activeIntentGeneration = -1L;
            }
        }
    }

    HttpResponse handleRequest(
            String method,
            String requestTarget,
            Map<String, String> headers,
            byte[] rawBody) {
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        if (body.length > HelperCoachAuthPolicy.MAX_BODY_BYTES) {
            return response(413, "Payload Too Large", "BODY_TOO_LARGE");
        }
        if (!allowRate("all", 120)) {
            return response(429, "Too Many Requests", "RATE_LIMITED");
        }
        String cleanMethod = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        String target = requestTarget == null ? "" : requestTarget.trim();
        Map<String, String> safeHeaders = headers == null
                ? new HashMap<String, String>() : headers;
        String timestamp = header(safeHeaders, "x-coach-timestamp");
        String nonce = header(safeHeaders, "x-coach-nonce");
        String signature = header(safeHeaders, "x-coach-signature");
        String secret = AppConfig.getHelperCoachPairingSecret(appContext);
        HelperCoachAuthPolicy.Result auth = HelperCoachAuthPolicy.verify(
                AppConfig.isHelperCoachEnabled(appContext),
                secret,
                cleanMethod,
                target,
                timestamp,
                nonce,
                signature,
                body,
                System.currentTimeMillis(),
                this::markNonceIfNew);
        if (auth != HelperCoachAuthPolicy.Result.VALID) {
            if (!allowRate("auth-fail", 12)) {
                return response(429, "Too Many Requests", "RATE_LIMITED");
            }
            if (auth == HelperCoachAuthPolicy.Result.DISABLED) {
                return response(403, "Forbidden", "COACHING_DISABLED");
            }
            if (auth == HelperCoachAuthPolicy.Result.MISSING_SECRET) {
                return response(409, "Conflict", "PAIRING_REQUIRED");
            }
            if (auth == HelperCoachAuthPolicy.Result.BODY_TOO_LARGE) {
                return response(413, "Payload Too Large", "BODY_TOO_LARGE");
            }
            return response(401, "Unauthorized", "COACH_AUTH_REJECTED");
        }

        String path = target;
        int queryAt = path.indexOf('?');
        String query = queryAt < 0 ? "" : path.substring(queryAt + 1);
        if (queryAt >= 0) path = path.substring(0, queryAt);
        if ("GET".equals(cleanMethod) && "/coach/v1/events".equals(path)) {
            return listEvents(query);
        }
        if (!"POST".equals(cleanMethod)) {
            return response(405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
        }
        JSONObject request;
        try {
            request = body.length == 0
                    ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Exception invalid) {
            return response(400, "Bad Request", "INVALID_JSON");
        }
        if ("/coach/v1/advice".equals(path)) return acceptAdvice(request);
        if ("/coach/v1/skill-candidate".equals(path)) return stageSkill(request);
        if ("/coach/v1/code-change-proposal".equals(path)) return stageCodeProposal(request);
        return response(404, "Not Found", "ROUTE_NOT_FOUND");
    }

    private HttpResponse listEvents(String query) {
        String cursor = queryValue(query, "since_event_id");
        int limit = Math.max(1, Math.min(100, parseInt(queryValue(query, "limit"), 20)));
        JSONArray selected = new JSONArray();
        boolean hasMore = false;
        String nextCursor = cursor;
        synchronized (lock) {
            boolean cursorExists = cursor.isEmpty();
            if (!cursorExists) {
                for (JSONObject event : events) {
                    if (cursor.equals(event.optString("event_id", ""))) {
                        cursorExists = true;
                        break;
                    }
                }
            }
            boolean cursorFound = cursor.isEmpty() || !cursorExists;
            for (JSONObject event : events) {
                if (!cursorFound) {
                    if (cursor.equals(event.optString("event_id", ""))) cursorFound = true;
                    continue;
                }
                if (selected.length() >= limit) {
                    hasMore = true;
                    break;
                }
                selected.put(cloneObject(event));
                nextCursor = event.optString("event_id", nextCursor);
            }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("protocol_version", 1)
                    .put("events", selected)
                    .put("has_more", hasMore)
                    .put("next_cursor", nextCursor);
        } catch (Exception ignored) {}
        return new HttpResponse(200, "OK", out);
    }

    private HttpResponse acceptAdvice(JSONObject request) {
        if (!validEnvelope(request, "pocket", "coach_advice")) {
            return response(400, "Bad Request", "INVALID_ENVELOPE");
        }
        String eventId = safeId(request.optString("event_id", ""));
        String observationId = safeId(request.optString("observation_event_id", ""));
        String taskId = safeId(request.optString("task_id", ""));
        String action = safeId(request.optString("suggested_action_id", ""));
        JSONObject parameters = request.optJSONObject("parameters");
        if (eventId.isEmpty() || observationId.isEmpty() || taskId.isEmpty()
                || parameters == null || parameters.length() != 0) {
            return response(400, "Bad Request", "INVALID_ADVICE_FIELDS");
        }
        String adviceTimestamp = request.optString("timestamp", "");
        long sentAt = HelperCoachTime.parseMillis(adviceTimestamp);
        long now = System.currentTimeMillis();
        if (sentAt <= 0L || !HelperCoachTime.withinClockSkew(
                sentAt, now, HelperCoachAuthPolicy.MAX_CLOCK_SKEW_MS)) {
            return response(400, "Bad Request", "STALE_ADVICE_TIMESTAMP");
        }
        long expiresAt = HelperCoachTime.parseMillis(request.optString("expires_at", ""));
        String expected = safeId(request.optString("expected_postcondition", ""));
        double confidence = request.optDouble("confidence", 0d);
        boolean confirmation = request.optBoolean("requires_user_confirmation", false);
        StoredObservation stored;
        String liveTask;
        synchronized (lock) {
            stored = observations.get(observationId);
            liveTask = activeTaskId;
        }
        if (stored == null) return response(409, "Conflict", "OBSERVATION_NOT_FOUND");
        String diagnosis = CoachDataRedactor.safeFreeText(
                request.optString("diagnosis", ""), 240);
        HelperCoachRuntimePolicy.Advice advice = new HelperCoachRuntimePolicy.Advice(
                eventId, observationId, taskId, stored.policy.appPackage,
                stored.policy.uiFingerprint, action, expected,
                confidence, expiresAt, confirmation);
        HelperCoachRuntimePolicy.Decision decision = runtimePolicy.evaluate(
                stored.policy, advice, liveTask,
                stored.policy.appPackage, stored.policy.uiFingerprint, now);
        if (decision != HelperCoachRuntimePolicy.Decision.ACCEPT) {
            return decisionResponse(decision);
        }
        AdviceListener listener = adviceListener;
        if (listener == null || !listener.onCoachAdvice(
                stored.policy, advice, stored.retryTarget,
                stored.intentGeneration)) {
            return response(503, "Service Unavailable", "RUNTIME_UNAVAILABLE");
        }
        JSONObject out = new JSONObject();
        try {
            out.put("protocol_version", 1)
                    .put("event_id", eventId)
                    .put("task_id", taskId)
                    .put("source", "helper")
                    .put("kind", "coach_advice_receipt")
                    .put("status", "accepted")
                    .put("queued", true)
                    .put("observation_event_id", observationId)
                    .put("advice_event_id", eventId)
                    .put("diagnosis_received", !diagnosis.isEmpty());
        } catch (Exception ignored) {}
        return new HttpResponse(202, "Accepted", out);
    }

    private HttpResponse stageSkill(JSONObject request) {
        if (!validEnvelope(request, "pocket", "skill_candidate")) {
            return response(400, "Bad Request", "INVALID_ENVELOPE");
        }
        if (!"candidate".equalsIgnoreCase(request.optString("status", ""))) {
            return response(400, "Bad Request", "CANDIDATE_MUST_START_INACTIVE");
        }
        if (request.optJSONArray("preconditions") == null
                || request.optJSONArray("action_sequence") == null
                || request.optJSONArray("postconditions") == null
                || request.optJSONArray("evidence_refs") == null
                || request.optBoolean("active", false)) {
            return response(400, "Bad Request", "INVALID_SKILL_FIELDS");
        }
        JSONObject result = registry.stageSkill(request);
        return new HttpResponse(result.optBoolean("success", false) ? 202 : 400,
                result.optBoolean("success", false) ? "Accepted" : "Bad Request", result);
    }

    private HttpResponse stageCodeProposal(JSONObject request) {
        if (!validEnvelope(request, "pocket", "code_change_proposal")) {
            return response(400, "Bad Request", "INVALID_ENVELOPE");
        }
        if (request.has("patch") || request.has("diff")
                || request.has("source_code") || request.has("apk")) {
            return response(400, "Bad Request", "INLINE_CODE_NOT_ACCEPTED");
        }
        JSONArray reproduction = request.optJSONArray("reproduction_steps");
        JSONArray tests = request.optJSONArray("test_plan");
        if (request.optString("diagnosis", "").trim().isEmpty()
                || reproduction == null || reproduction.length() == 0
                || tests == null || tests.length() == 0
                || request.optString("proposed_diff_ref", "").trim().isEmpty()) {
            return response(400, "Bad Request", "INVALID_PROPOSAL_FIELDS");
        }
        JSONObject result = registry.stageCodeProposal(request);
        return new HttpResponse(result.optBoolean("success", false) ? 202 : 400,
                result.optBoolean("success", false) ? "Accepted" : "Bad Request", result);
    }

    private boolean validEnvelope(JSONObject request, String source, String kind) {
        if (request == null || request.optInt("protocol_version", 0) != 1
                || !source.equals(request.optString("source", ""))
                || !kind.equals(request.optString("kind", ""))
                || safeId(request.optString("event_id", "")).isEmpty()
                || safeId(request.optString("task_id", "")).isEmpty()) return false;
        long sentAt = HelperCoachTime.parseMillis(request.optString("timestamp", ""));
        return sentAt > 0L && HelperCoachTime.withinClockSkew(
                sentAt, System.currentTimeMillis(),
                HelperCoachAuthPolicy.MAX_CLOCK_SKEW_MS);
    }

    private HttpResponse decisionResponse(HelperCoachRuntimePolicy.Decision decision) {
        int code = decision == HelperCoachRuntimePolicy.Decision.NEEDS_USER_CONFIRMATION
                ? 409 : decision == HelperCoachRuntimePolicy.Decision.DUPLICATE_ADVICE
                        ? 409 : 422;
        String status = code == 409 ? "Conflict" : "Unprocessable Entity";
        JSONObject body = new JSONObject();
        try {
            body.put("protocol_version", 1)
                    .put("status", "rejected")
                    .put("decision", decision.name());
        } catch (Exception ignored) {}
        return new HttpResponse(code, status, body);
    }

    private HttpResponse response(int code, String status, String error) {
        JSONObject body = new JSONObject();
        try { body.put("protocol_version", 1).put("success", false).put("error", error); }
        catch (Exception ignored) {}
        return new HttpResponse(code, status, body);
    }

    private boolean isEnabled() {
        return appContext != null
                && AppConfig.isHelperCoachEnabled(appContext)
                && AppConfig.hasHelperCoachPairingSecret(appContext);
    }

    private boolean markNonceIfNew(String nonce, long timestampMs, long nowMs) {
        if (noncePrefs == null) return false;
        synchronized (noncePrefs) {
            JSONObject all;
            try { all = new JSONObject(noncePrefs.getString(NONCE_KEY, "{}")); }
            catch (Exception ignored) { all = new JSONObject(); }
            ArrayList<String> keys = new ArrayList<String>();
            Iterator<String> iterator = all.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            for (String key : keys) {
                long seenAt = all.optLong(key, 0L);
                if (seenAt <= 0L || nowMs - seenAt > 120_000L) all.remove(key);
            }
            if (all.has(nonce)) return false;
            try { all.put(nonce, timestampMs); }
            catch (Exception ignored) { return false; }
            while (all.length() > MAX_NONCES) {
                Iterator<String> overflowIterator = all.keys();
                if (overflowIterator.hasNext()) {
                    String key = overflowIterator.next();
                    all.remove(key);
                } else break;
            }
            return noncePrefs.edit().putString(NONCE_KEY, all.toString()).commit();
        }
    }

    private boolean allowRate(String name, int max) {
        long now = System.currentTimeMillis();
        synchronized (rateEvents) {
            ArrayList<Long> entries = rateEvents.get(name);
            if (entries == null) {
                entries = new ArrayList<Long>();
                rateEvents.put(name, entries);
            }
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (now - entries.get(i) > RATE_WINDOW_MS) entries.remove(i);
            }
            if (entries.size() >= max) return false;
            entries.add(now);
            return true;
        }
    }

    private void appendEventLocked(JSONObject event) {
        events.add(cloneObject(event));
        while (events.size() > MAX_EVENTS) events.remove(0);
    }

    private static JSONArray filterActions(JSONArray actions) {
        JSONArray result = new JSONArray();
        if (actions == null) return result;
        for (int i = 0; i < actions.length(); i++) {
            String action = actions.optString(i, "");
            if (HelperCoachRuntimePolicy.ACTION_INSPECT_UI.equals(action)
                    || HelperCoachRuntimePolicy.ACTION_RETRY_PREVIOUS_SAFE_TAP.equals(action)) {
                if (!contains(result, action)) result.put(action);
            }
        }
        return result;
    }

    private static JSONArray withoutAction(JSONArray source, String omitted) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            String value = source.optString(i, "");
            if (!omitted.equals(value)) result.put(value);
        }
        return result;
    }

    private static ArrayList<String> toList(JSONArray array) {
        ArrayList<String> result = new ArrayList<String>();
        for (int i = 0; i < array.length(); i++) result.add(array.optString(i, ""));
        return result;
    }

    private static JSONArray sanitizeRefs(JSONArray refs) {
        JSONArray result = new JSONArray();
        if (refs == null) return result;
        for (int i = 0; i < refs.length() && i < 8; i++) {
            String ref = safeId(refs.optString(i, ""));
            if (!ref.isEmpty()) result.put(ref);
        }
        return result;
    }

    private static JSONArray safeEvidence(String evidence) {
        JSONArray refs = new JSONArray();
        String value = safeId(evidence);
        if (!value.isEmpty()) refs.put(value);
        return refs;
    }

    private static boolean contains(JSONArray values, String target) {
        for (int i = 0; i < values.length(); i++) {
            if (target.equals(values.optString(i, ""))) return true;
        }
        return false;
    }

    private static String safePackage(String value) {
        String text = value == null ? "" : value.trim();
        return text.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
                ? text : "";
    }

    private static String safeId(String value) {
        String text = value == null ? "" : value.trim();
        return text.matches("[A-Za-z0-9_.:-]{1,128}") ? text : "";
    }

    private static String safeResult(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return "success".equals(text) || "failed".equals(text) ? text : "unknown";
    }

    private static JSONObject cloneObject(JSONObject value) {
        try { return value == null ? new JSONObject() : new JSONObject(value.toString()); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static String header(Map<String, String> headers, String key) {
        if (headers == null) return "";
        String value = headers.get(key.toLowerCase(Locale.ROOT));
        return value == null ? "" : value.trim();
    }

    private static String queryValue(String query, String requested) {
        if (query == null) return "";
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            if (!requested.equals(decode(key))) continue;
            return equals < 0 ? "" : decode(part.substring(equals + 1));
        }
        return "";
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (Exception ignored) { return ""; }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (Exception ignored) { return fallback; }
    }
}
