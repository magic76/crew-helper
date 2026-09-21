package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Derives reusable Crew Experience candidates from sanitized Runtime evidence.
 *
 * Identity is deterministic and Runtime-owned. Gemini never invents the key;
 * it may only compress qualified evidence into a human-readable lesson.
 */
final class ReflectionRuleEvidence {
    static final int MAX_CANDIDATES = 6;
    static final String KIND_RECOVERY = "RECOVERY";
    static final String KIND_FRICTION = "FRICTION";

    static final class Step {
        final String tool;
        final String outcome;
        final String failureCode;
        final String semanticTarget;
        final String semanticAction;

        Step(String tool,
             String outcome,
             String failureCode,
             String semanticTarget,
             String semanticAction) {
            this.tool = clean(tool);
            this.outcome = clean(outcome).toUpperCase(Locale.ROOT);
            this.failureCode = safeToken(failureCode, 80);
            this.semanticTarget = safeSemanticTarget(semanticTarget);
            this.semanticAction = safeToken(semanticAction, 32);
        }

        boolean succeeded() { return "SUCCESS".equals(outcome); }
        boolean failed() { return "FAILED".equals(outcome); }
    }

    static final class Candidate {
        final String id;
        final String ruleKey;
        final String kind;
        final String scope;
        final String condition;
        final String response;
        final String evidence;
        final int frictionScore;
        final String frictionSignals;

        Candidate(String id,
                  String ruleKey,
                  String kind,
                  String scope,
                  String condition,
                  String response,
                  String evidence,
                  int frictionScore,
                  String frictionSignals) {
            this.id = id;
            this.ruleKey = ruleKey;
            this.kind = KIND_RECOVERY.equals(kind)
                    ? KIND_RECOVERY
                    : KIND_FRICTION;
            this.scope = scope;
            this.condition = condition;
            this.response = response;
            this.evidence = evidence;
            this.frictionScore = Math.max(0, frictionScore);
            this.frictionSignals = clean(frictionSignals);
        }
    }

    private ReflectionRuleEvidence() {}

    static List<Candidate> derive(List<Step> previous, List<Step> current) {
        LinkedHashMap<String, Candidate> unique = new LinkedHashMap<String, Candidate>();

        // Current-task recovery is counted exactly once. Never replay the whole
        // previous task, otherwise old evidence would be counted again on every
        // later task in the same goal.
        deriveFailureRecovery(current, unique);
        deriveDirectRetryRecovery(current, unique);

        // Preserve only the useful cross-task case: the previous task ended on a
        // failed mutation and the current task supplied the recovery sequence.
        Step previousTail = lastMutation(previous);
        if (previousTail != null && previousTail.failed()) {
            ArrayList<Step> boundary = new ArrayList<Step>();
            boundary.add(previousTail);
            if (current != null) boundary.addAll(current);
            deriveFailureRecovery(boundary, unique);
            deriveDirectRetryRecovery(boundary, unique);
        }

        ArrayList<Candidate> out = new ArrayList<Candidate>();
        int index = 1;
        for (Candidate candidate : unique.values()) {
            if (out.size() >= MAX_CANDIDATES) break;
            out.add(new Candidate(
                    "r" + index++,
                    candidate.ruleKey,
                    candidate.kind,
                    candidate.scope,
                    candidate.condition,
                    candidate.response,
                    candidate.evidence,
                    candidate.frictionScore,
                    candidate.frictionSignals));
        }
        return out;
    }

    static Candidate findById(List<Candidate> candidates, String id) {
        if (candidates == null) return null;
        String wanted = clean(id);
        if (wanted.isEmpty()) return null;
        for (Candidate candidate : candidates) {
            if (candidate != null && wanted.equals(candidate.id)) return candidate;
        }
        return null;
    }

    private static void deriveFailureRecovery(List<Step> steps,
                                              LinkedHashMap<String, Candidate> out) {
        if (steps == null) return;
        for (int i = 0; i < steps.size(); i++) {
            Step failed = steps.get(i);
            if (failed == null
                    || !failed.failed()
                    || failed.failureCode.isEmpty()
                    || isRuntimeInternalFailure(failed.failureCode)) {
                continue;
            }

            int visualIndex = nextVisualObservation(steps, i + 1);
            if (visualIndex < 0) continue;

            int mutationIndex = nextMutation(steps, visualIndex + 1);
            if (mutationIndex < 0) continue;
            Step recovered = steps.get(mutationIndex);
            if (recovered == null || !recovered.succeeded()) continue;

            String scope = failureScope(failed);
            String condition = failed.failureCode;
            String response = "INSPECT_UI";
            add(out, KIND_FRICTION, scope, condition, response,
                    condition + " -> INSPECT_UI -> SUCCESS",
                    5,
                    "FAILED_RECOVERY|EXTRA_INSPECT|RETRY");
        }
    }

    /** Proven failed mutation followed by a compatible successful correction. */
    private static void deriveDirectRetryRecovery(
            List<Step> steps,
            LinkedHashMap<String, Candidate> out) {
        if (steps == null) return;
        for (int i = 0; i < steps.size(); i++) {
            Step failed = steps.get(i);
            if (failed == null
                    || !failed.failed()
                    || !isMutationTool(failed.tool)
                    || isRuntimeInternalFailure(failed.failureCode)) {
                continue;
            }

            boolean sawVisualObservation = false;
            Step recovered = null;
            for (int j = i + 1; j < steps.size(); j++) {
                Step candidate = steps.get(j);
                if (candidate == null) continue;
                if (isVisualTool(candidate.tool) && candidate.succeeded()) {
                    sawVisualObservation = true;
                    continue;
                }
                if (!isMutationTool(candidate.tool)) continue;
                recovered = candidate;
                break;
            }

            if (sawVisualObservation
                    || recovered == null
                    || !recovered.succeeded()
                    || !compatibleRetry(failed, recovered)) {
                continue;
            }

            String scope = !recovered.semanticTarget.isEmpty()
                    ? recovered.semanticTarget : failureScope(failed);
            String condition = failed.failureCode.isEmpty()
                    ? "PREVIOUS_ATTEMPT_FAILED" : failed.failureCode;
            String response = actionToken(recovered);
            if (response.isEmpty()) continue;

            add(out, KIND_FRICTION, scope, condition, response,
                    failureEvidence(failed) + " -> " + response + " SUCCESS",
                    4,
                    "FAILED_RECOVERY|RETRY");
        }
    }

    private static boolean compatibleRetry(Step failed, Step recovered) {
        if (failed == null || recovered == null) return false;
        if (!failed.semanticTarget.isEmpty() && !recovered.semanticTarget.isEmpty()) {
            if (semanticFamily(failed.semanticTarget)
                    .equals(semanticFamily(recovered.semanticTarget))) {
                return true;
            }
        }
        return !failed.tool.isEmpty() && failed.tool.equals(recovered.tool);
    }

    static boolean isRuntimeInternalFailure(String failureCode) {
        String code = safeToken(failureCode, 80);
        if (code.isEmpty()) return false;
        if ("OBSERVE_REQUIRED_AFTER_FAILURE".equals(code)
                || "OBSERVE_REQUIRED".equals(code)
                || "STABILITY_BLOCK".equals(code)
                || "TASK_ALREADY_FINISHED".equals(code)
                || "MESSAGE_TRANSACTION_ALREADY_HANDLED".equals(code)
                || "WAITING_USER_CHOICE".equals(code)
                || "SEND_TEXT_REQUIRED".equals(code)) {
            return true;
        }
        return code.startsWith("STALE_")
                || code.startsWith("DUPLICATE_")
                || code.startsWith("AUTHORIZATION_")
                || code.endsWith("_NOT_AUTHORIZED")
                || code.startsWith("RUNTIME_") && code.endsWith("_OWNS_EXECUTION");
    }

    private static String failureEvidence(Step failed) {
        String tool = failed == null
                ? "ACTION" : safeRulePart(failed.tool.toUpperCase(Locale.ROOT), 48);
        if (tool.isEmpty()) tool = "ACTION";
        String code = failed == null ? "" : failed.failureCode;
        return code.isEmpty() ? tool + " FAILED" : tool + " FAILED:" + code;
    }

    private static Step lastMutation(List<Step> steps) {
        if (steps == null) return null;
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            if (step != null && isMutationTool(step.tool)) return step;
        }
        return null;
    }

    private static void add(LinkedHashMap<String, Candidate> out,
                            String kind,
                            String scope,
                            String condition,
                            String response,
                            String evidence,
                            int frictionScore,
                            String frictionSignals) {
        String safeScope = safeRulePart(scope, 80);
        String safeCondition = safeRulePart(condition, 96);
        String safeResponse = safeRulePart(response, 48);
        if (safeScope.isEmpty() || safeCondition.isEmpty() || safeResponse.isEmpty()) return;

        String key = "scope=" + safeScope
                + "|when=" + safeCondition
                + "|do=" + safeResponse;
        if (out.containsKey(key)) return;
        out.put(key, new Candidate(
                "",
                key,
                kind,
                safeScope,
                safeCondition,
                safeResponse,
                safeRulePart(evidence, 160),
                frictionScore,
                safeRulePart(frictionSignals, 120)));
    }

    private static String failureScope(Step step) {
        if (step == null) return "ACTION";
        if (!step.semanticTarget.isEmpty()) return step.semanticTarget;
        if ("UI_TARGET_NOT_FOUND".equals(step.failureCode)) return "UI_TARGET";
        String tool = safeRulePart(step.tool.toUpperCase(Locale.ROOT), 48);
        return tool.isEmpty() ? "ACTION" : tool;
    }

    private static int nextVisualObservation(List<Step> steps, int start) {
        for (int i = Math.max(0, start); i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step == null) continue;
            if (isVisualTool(step.tool) && step.succeeded()) return i;
            if (isMutationTool(step.tool)) return -1;
        }
        return -1;
    }

    private static int nextMutation(List<Step> steps, int start) {
        for (int i = Math.max(0, start); i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step != null && isMutationTool(step.tool)) return i;
        }
        return -1;
    }

    private static boolean hasFailedMutationBetween(List<Step> steps, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(end, steps.size()); i++) {
            Step step = steps.get(i);
            if (step != null && step.failed() && isMutationTool(step.tool)) return true;
        }
        return false;
    }

    private static boolean isVisualTool(String tool) {
        return "inspect_ui".equals(tool) || "take_screenshot".equals(tool);
    }

    private static boolean isMutationTool(String tool) {
        if (tool == null || tool.isEmpty()) return false;
        return !isVisualTool(tool)
                && !"wait".equals(tool)
                && !"list_notes".equals(tool)
                && !"get_note".equals(tool)
                && !"search_notes".equals(tool)
                && !"list_app_guidance".equals(tool);
    }

    private static String actionToken(Step step) {
        if (step == null) return "ACTION";
        if (!step.semanticAction.isEmpty()) return step.semanticAction;
        if ("tap_screen".equals(step.tool)) return "TAP";
        if ("search_current_app".equals(step.tool)) return "SEARCH";
        if ("launch_app".equals(step.tool)) return "OPEN_APP";
        return safeRulePart(step.tool.toUpperCase(Locale.ROOT), 48);
    }

    static String semanticFamily(String target) {
        String safe = safeSemanticTarget(target);
        int colon = safe.indexOf(':');
        if (colon <= 0) return safe;
        return safe.substring(0, colon) + ":*";
    }

    private static String safeSemanticTarget(String value) {
        String clean = clean(value);
        if (clean.length() > 80) return "";
        return clean.matches("[A-Za-z0-9:_*-]{1,80}") ? clean : "";
    }

    private static String safeToken(String value, int max) {
        String clean = clean(value).toUpperCase(Locale.ROOT);
        if (clean.length() > max) return "";
        return clean.matches("[A-Z0-9:_*-]{1," + max + "}") ? clean : "";
    }

    private static String safeRulePart(String value, int max) {
        String clean = clean(value);
        if (clean.isEmpty() || clean.length() > max) return "";
        return clean.matches("[A-Za-z0-9:_*|= .>-]{1," + max + "}") ? clean : "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
