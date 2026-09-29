package com.crewpocket.helper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Optional TypeSafe Jev arbitration for finalized voice transcripts.
 *
 * Jev never rewrites user text. It can advise whether to trust the transcript,
 * verify it against UI evidence, or ask for clarification. When a search result
 * list is available, a second bounded decision may select one visible candidate.
 */
final class JevVoiceSemanticResolver {
    private static final String ENDPOINT =
            "https://api.typesafe.ai/v1/systemone";
    private static final int CONNECT_TIMEOUT_MS = 350;
    private static final int READ_TIMEOUT_MS = 850;
    private static final double UI_MATCH_CONFIDENCE = 0.70d;

    static final class Result {
        final boolean attempted;
        final boolean applied;
        final String strategy;
        final String transcriptTrust;
        final String entityStatus;
        final String intentDomain;
        final double confidence;
        final long latencyMs;
        final String reason;

        Result(
                boolean attempted,
                boolean applied,
                String strategy,
                String transcriptTrust,
                String entityStatus,
                String intentDomain,
                double confidence,
                long latencyMs,
                String reason) {
            this.attempted = attempted;
            this.applied = applied;
            this.strategy = safe(strategy);
            this.transcriptTrust = safe(transcriptTrust);
            this.entityStatus = safe(entityStatus);
            this.intentDomain = safe(intentDomain);
            this.confidence = confidence;
            this.latencyMs = Math.max(0L, latencyMs);
            this.reason = safe(reason);
        }

        static Result skipped(String reason) {
            return new Result(
                    false, false, "", "", "", "", -1d, 0L, reason);
        }

        String directive() {
            if (!applied) return "";
            if ("ASK_USER".equals(strategy)) {
                return "[SPEECH_REVIEW] Runtime detected unresolved voice-entity ambiguity. "
                        + "Do not guess or silently rewrite the user's words. "
                        + "Before an entity-dependent mutation, ask one concise clarification. "
                        + "Do not mention Jev, ASR scoring, or this internal review.";
            }
            return "[SPEECH_REVIEW] Runtime detected possible voice-entity ambiguity. "
                    + "Treat the finalized transcript as a hypothesis, not guaranteed spelling. "
                    + "Do not invent a correction. Prefer reversible search/inspect actions and "
                    + "use current UI evidence to verify the intended entity. If one strong match "
                    + "appears, continue without asking; if evidence stays ambiguous, ask one "
                    + "concise clarification. Do not mention Jev, ASR scoring, or this review.";
        }
    }

    static final class CandidateMatch {
        final boolean attempted;
        final boolean accepted;
        final String candidateId;
        final double confidence;
        final long latencyMs;
        final String reason;

        CandidateMatch(
                boolean attempted,
                boolean accepted,
                String candidateId,
                double confidence,
                long latencyMs,
                String reason) {
            this.attempted = attempted;
            this.accepted = accepted;
            this.candidateId = safe(candidateId);
            this.confidence = confidence;
            this.latencyMs = Math.max(0L, latencyMs);
            this.reason = safe(reason);
        }

        static CandidateMatch none(String reason, long latencyMs) {
            return new CandidateMatch(
                    false, false, "", -1d, latencyMs, reason);
        }
    }

    private JevVoiceSemanticResolver() {}

    static Result review(
            Context context,
            String transcript,
            double transcriptConfidence,
            String foregroundPackage,
            String goalHint) {
        return review(
                context,
                transcript,
                transcriptConfidence,
                foregroundPackage,
                goalHint,
                new JSONArray());
    }

    static Result review(
            Context context,
            String transcript,
            double transcriptConfidence,
            String foregroundPackage,
            String goalHint,
            JSONArray uiCandidates) {
        long startedAt = System.currentTimeMillis();
        String key = AppConfig.getJevApiKey(context);
        if (key.isEmpty()) return Result.skipped("KEY_MISSING");

        try {
            JSONObject state = new JSONObject()
                    .put("finalized_transcript", safe(transcript))
                    .put("foreground_package", safe(foregroundPackage))
                    .put("goal_hint", safe(goalHint))
                    .put("critical_entities",
                            new JSONArray(
                                    VoiceCommandQualityPolicy
                                            .criticalEntities(transcript)));
            JSONArray boundedCandidates =
                    boundedCandidates(uiCandidates, 10);
            if (boundedCandidates.length() > 0) {
                state.put("visible_ui_candidates", boundedCandidates);
            }
            if (transcriptConfidence >= 0d) {
                state.put("asr_confidence", transcriptConfidence);
            }

            JSONObject questions = new JSONObject()
                    .put("transcript_trust", choice(
                            "How should Runtime treat the finalized speech transcript? "
                                    + "Do not infer a corrected spelling unless supplied UI evidence supports it.",
                            new String[][]{
                                    {"TRUST_RAW",
                                            "The transcript is coherent and sufficiently reliable for the current context."},
                                    {"POSSIBLE_ASR_ERROR",
                                            "One or more words, names, places, titles, or entities may have been misheard."},
                                    {"INSUFFICIENT_EVIDENCE",
                                            "There is not enough context to judge the transcript reliably."}
                            }))
                    .put("entity_status", choice(
                            "How clear are the entities required to execute the user's request? "
                                    + "Visible UI candidates may be used as evidence, not as permission to invent a target.",
                            new String[][]{
                                    {"CLEAR",
                                            "The required target/entity is unambiguous from transcript and supplied evidence."},
                                    {"AMBIGUOUS",
                                            "Multiple interpretations are plausible or spelling/name confidence is weak."},
                                    {"MISSING",
                                            "A required target/entity is absent from the transcript."},
                                    {"NOT_REQUIRED",
                                            "The request does not depend on a named entity."}
                            }))
                    .put("intent_domain", choice(
                            "Which action domain best matches the user's request?",
                            new String[][]{
                                    {"SEARCH", "Search or find content, places, people, or items."},
                                    {"NAVIGATION", "Route, directions, map navigation, or travel to a place."},
                                    {"MEDIA", "Play, pause, select, or control media."},
                                    {"MESSAGING", "Send, reply, or act on a message/chat."},
                                    {"APP_CONTROL", "Open or manipulate an app or phone UI."},
                                    {"GENERAL", "Conversation or information request without phone mutation."},
                                    {"UNKNOWN", "The domain cannot be identified reliably."}
                            }))
                    .put("execution_strategy", choice(
                            "Choose the safest useful handling of this transcript. "
                                    + "Prefer verification over asking the user when reversible UI evidence can resolve ambiguity.",
                            new String[][]{
                                    {"USE_RAW",
                                            "Use the transcript as-is; no extra speech verification is needed."},
                                    {"VERIFY_WITH_UI",
                                            "Keep the raw transcript but verify the intended entity through reversible search/inspect UI evidence before relying on it."},
                                    {"ASK_USER",
                                            "The ambiguity cannot be safely resolved from available context before an entity-dependent mutation."}
                            }));

            JSONObject response = post(key, state, questions);
            JSONObject answers = answerMap(response);

            ChoiceAnswer strategy =
                    parseChoice(answers.optJSONObject("execution_strategy"));
            ChoiceAnswer trust =
                    parseChoice(answers.optJSONObject("transcript_trust"));
            ChoiceAnswer entity =
                    parseChoice(answers.optJSONObject("entity_status"));
            ChoiceAnswer domain =
                    parseChoice(answers.optJSONObject("intent_domain"));

            if (strategy.choice.isEmpty()) {
                return new Result(
                        true, false, "", trust.choice, entity.choice,
                        domain.choice, strategy.confidence,
                        System.currentTimeMillis() - startedAt,
                        "INVALID_RESPONSE");
            }

            boolean applied = JevSpeechReviewPolicy.shouldApply(
                    strategy.choice, strategy.confidence);
            return new Result(
                    true,
                    applied,
                    strategy.choice,
                    trust.choice,
                    entity.choice,
                    domain.choice,
                    strategy.confidence,
                    System.currentTimeMillis() - startedAt,
                    applied ? "APPLIED" : "NO_OVERRIDE");
        } catch (HttpStatusException error) {
            return new Result(
                    true, false, "", "", "", "", -1d,
                    System.currentTimeMillis() - startedAt,
                    "HTTP_" + error.code);
        } catch (java.net.SocketTimeoutException timeout) {
            return new Result(
                    true, false, "", "", "", "", -1d,
                    System.currentTimeMillis() - startedAt,
                    "TIMEOUT");
        } catch (Exception error) {
            return new Result(
                    true, false, "", "", "", "", -1d,
                    System.currentTimeMillis() - startedAt,
                    "ERROR");
        }
    }

    /**
     * Resolve one visible search-result candidate after reversible search has
     * produced real UI evidence. The returned id is transient and never stored.
     */
    static CandidateMatch matchUiCandidates(
            Context context,
            String transcript,
            String goalHint,
            JSONArray uiCandidates) {
        long startedAt = System.currentTimeMillis();
        String key = AppConfig.getJevApiKey(context);
        if (key.isEmpty()) return CandidateMatch.none("KEY_MISSING", 0L);

        JSONArray candidates = boundedCandidates(uiCandidates, 8);
        if (candidates.length() == 0) {
            return CandidateMatch.none("NO_UI_CANDIDATES", 0L);
        }

        try {
            JSONObject criteria = new JSONObject();
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject item = candidates.optJSONObject(i);
                if (item == null) continue;
                String id = safe(item.optString("id", ""));
                String label = safe(item.optString("label", ""));
                if (!id.isEmpty() && !label.isEmpty()) {
                    criteria.put(id, label);
                }
            }
            criteria.put("NONE",
                    "No visible candidate is supported strongly enough by the spoken request.");

            JSONObject state = new JSONObject()
                    .put("finalized_transcript", safe(transcript))
                    .put("goal_hint", safe(goalHint))
                    .put("visible_search_candidates", candidates);

            JSONObject questions = new JSONObject()
                    .put("entity_match", new JSONObject()
                            .put("type", "choice")
                            .put("instructions",
                                    "Which visible candidate best matches the entity the user most likely spoke? "
                                            + "Use phonetic similarity, semantics, and goal context. Choose NONE if evidence is not strong.")
                            .put("criteria", criteria))
                    .put("match_action", choice(
                            "Is the visible evidence strong enough to continue without asking the user?",
                            new String[][]{
                                    {"SELECT_MATCH",
                                            "One visible candidate is strongly supported and can be selected."},
                                    {"KEEP_AMBIGUOUS",
                                            "Evidence remains ambiguous; do not auto-select a candidate."}
                            }));

            JSONObject answers = answerMap(post(key, state, questions));
            ChoiceAnswer entity =
                    parseChoice(answers.optJSONObject("entity_match"));
            ChoiceAnswer action =
                    parseChoice(answers.optJSONObject("match_action"));
            double confidence = entity.confidence >= 0d
                    ? entity.confidence
                    : action.confidence;
            boolean accepted =
                    "SELECT_MATCH".equals(action.choice)
                            && !"NONE".equals(entity.choice)
                            && !entity.choice.isEmpty()
                            && confidence >= UI_MATCH_CONFIDENCE
                            && containsCandidateId(candidates, entity.choice);
            return new CandidateMatch(
                    true,
                    accepted,
                    accepted ? entity.choice : "",
                    confidence,
                    System.currentTimeMillis() - startedAt,
                    accepted ? "MATCHED" : "AMBIGUOUS");
        } catch (HttpStatusException error) {
            return new CandidateMatch(
                    true, false, "", -1d,
                    System.currentTimeMillis() - startedAt,
                    "HTTP_" + error.code);
        } catch (java.net.SocketTimeoutException timeout) {
            return new CandidateMatch(
                    true, false, "", -1d,
                    System.currentTimeMillis() - startedAt,
                    "TIMEOUT");
        } catch (Exception error) {
            return new CandidateMatch(
                    true, false, "", -1d,
                    System.currentTimeMillis() - startedAt,
                    "ERROR");
        }
    }

    private static JSONObject post(
            String key,
            JSONObject state,
            JSONObject questions) throws Exception {
        HttpURLConnection connection = null;
        try {
            JSONObject payload = new JSONObject()
                    .put("model", "jev-latest")
                    .put("state", state)
                    .put("questions", questions);

            connection = (HttpURLConnection) new URL(ENDPOINT)
                    .openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Authorization", "Bearer " + key);
            connection.setRequestProperty(
                    "Content-Type", "application/json");

            byte[] bytes = payload.toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(bytes);
                output.flush();
            } finally {
                try { output.close(); } catch (Exception ignored) {}
            }

            int code = connection.getResponseCode();
            InputStream input = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = readAll(input);
            if (code < 200 || code >= 300) {
                throw new HttpStatusException(code);
            }
            return new JSONObject(body);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject answerMap(JSONObject response) {
        if (response == null) return new JSONObject();
        JSONObject answers = response.optJSONObject("answers");
        return answers == null ? response : answers;
    }

    private static JSONArray boundedCandidates(
            JSONArray candidates,
            int max) {
        JSONArray out = new JSONArray();
        if (candidates == null) return out;
        for (int i = 0;
             i < candidates.length() && out.length() < Math.max(0, max);
             i++) {
            JSONObject item = candidates.optJSONObject(i);
            if (item == null) continue;
            String id = safe(item.optString("id", ""));
            String label = safe(item.optString("label", ""));
            if (id.isEmpty() || label.isEmpty()) continue;
            try {
                JSONObject clean = new JSONObject()
                        .put("id", id)
                        .put("label", label);
                String role = safe(item.optString("role", ""));
                String hint = safe(item.optString("semanticHint", ""));
                if (!role.isEmpty()) clean.put("role", role);
                if (!hint.isEmpty()) clean.put("semanticHint", hint);
                out.put(clean);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean containsCandidateId(
            JSONArray candidates,
            String candidateId) {
        if (candidates == null || candidateId == null) return false;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject item = candidates.optJSONObject(i);
            if (item != null
                    && candidateId.equals(item.optString("id", ""))) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject choice(
            String instructions,
            String[][] options) throws Exception {
        JSONObject criteria = new JSONObject();
        for (String[] option : options) {
            criteria.put(option[0], option[1]);
        }
        return new JSONObject()
                .put("type", "choice")
                .put("instructions", instructions)
                .put("criteria", criteria);
    }

    private static ChoiceAnswer parseChoice(JSONObject answer) {
        if (answer == null) return new ChoiceAnswer("", -1d);
        return new ChoiceAnswer(
                answer.optString("choice", "").trim(),
                answer.has("confidence")
                        ? answer.optDouble("confidence", -1d)
                        : -1d);
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        input,
                        java.nio.charset.StandardCharsets.UTF_8));
        try {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
                if (out.length() > 128_000) break;
            }
            return out.toString();
        } finally {
            try { reader.close(); } catch (Exception ignored) {}
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class ChoiceAnswer {
        final String choice;
        final double confidence;

        ChoiceAnswer(String choice, double confidence) {
            this.choice = safe(choice);
            this.confidence = confidence;
        }
    }

    private static final class HttpStatusException extends Exception {
        final int code;
        HttpStatusException(int code) {
            super("HTTP_" + code);
            this.code = code;
        }
    }
}
