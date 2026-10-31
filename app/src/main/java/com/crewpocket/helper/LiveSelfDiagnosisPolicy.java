package com.crewpocket.helper;

import java.util.HashMap;
import java.util.Locale;

/**
 * Per-live-session diagnostic correlation and strictly bounded recovery lease.
 * Pure runtime state: no network, persistence, sensitive text or execution.
 * Call IDs are keys for local correlation only; snapshots never expose them.
 */
final class LiveSelfDiagnosisPolicy {
    private static final int MAX_CALLS = 96;

    static final class Snapshot {
        final long generation;
        final String goal;
        final String lastTool;
        final String lastAction;
        final String failure;
        final int observedCalls;
        final int failures;
        final boolean recoveryOffered;
        final boolean recoveryConsumed;

        Snapshot(long generation, String goal, String lastTool,
                String lastAction, String failure, int observedCalls, int failures,
                boolean recoveryOffered, boolean recoveryConsumed) {
            this.generation = generation;
            this.goal = goal;
            this.lastTool = lastTool;
            this.lastAction = lastAction;
            this.failure = failure;
            this.observedCalls = observedCalls;
            this.failures = failures;
            this.recoveryOffered = recoveryOffered;
            this.recoveryConsumed = recoveryConsumed;
        }
    }

    static final class Option {
        final String tool;
        final String action;
        final String target;
        final String reason;

        Option(String tool, String action, String target, String reason) {
            this.tool = tool;
            this.action = action;
            this.target = target;
            this.reason = reason;
        }
    }

    private static final class Call {
        final long generation;
        final String name;
        final String action;

        Call(long generation, String name, String action) {
            this.generation = generation;
            this.name = name;
            this.action = action;
        }
    }

    private final HashMap<String, Call> calls = new HashMap<String, Call>();
    private long generation = -1L;
    private String goal = "NONE";
    private String lastTool = "NONE";
    private String lastAction = "NONE";
    private String failure = "NONE";
    private int observedCalls;
    private int failures;
    private Option recoveryOption;
    private boolean recoveryOffered;
    private boolean recoveryConsumed;

    synchronized void reset() {
        calls.clear();
        generation = -1L;
        goal = "NONE";
        lastTool = "NONE";
        lastAction = "NONE";
        failure = "NONE";
        observedCalls = 0;
        failures = 0;
        recoveryOption = null;
        recoveryOffered = false;
        recoveryConsumed = false;
    }

    synchronized void newIntent(long gen, String goalIntent) {
        generation = gen;
        goal = goalToken(goalIntent);
        lastTool = "NONE";
        lastAction = "NONE";
        failure = "NONE";
        observedCalls = 0;
        failures = 0;
        recoveryOption = null;
        recoveryOffered = false;
        recoveryConsumed = false;
        calls.clear();
    }

    synchronized void toolReceived(String callId, long gen,
            String tool, String action) {
        if (gen != generation || callId == null || callId.isEmpty()) return;
        // Diagnostic tool reads must not count as phone actions or failures.
        if (isDiagnosticTool(tool)) return;
        if (calls.containsKey(callId)) return;
        if (calls.size() == MAX_CALLS) calls.clear();
        String safeTool = toolToken(tool);
        String safeAction = actionToken(action);
        calls.put(callId, new Call(gen, safeTool, safeAction));
        lastTool = safeTool;
        lastAction = safeAction;
        observedCalls++;
    }

    synchronized void toolResponse(String callId, long gen,
            boolean success, String rawCode) {
        Call call = calls.remove(callId);
        if (call == null || call.generation != generation
                || gen != generation) return;
        // Ignore earlier generation responses and safe diagnostic lookups.
        if (success) return;
        lastTool = call.name;
        lastAction = call.action;
        failure = failureToken(rawCode);
        failures++;
    }

    synchronized Snapshot snapshot(long gen) {
        if (gen != generation) {
            return new Snapshot(gen, "NONE", "NONE", "NONE",
                    "STALE_USER_INTENT", 0, 0, false, false);
        }
        return new Snapshot(generation, goal, lastTool, lastAction,
                failure, observedCalls, failures,
                recoveryOffered, recoveryConsumed);
    }

    /**
     * One read-only suggestion per intent, and only for a known, justified,
     * low-risk recovery. Still requires the usual phone Runtime authorization.
     */
    synchronized Option offer(long gen, String finalizedUserText,
            String foregroundPackage, boolean canInspect) {
        if (gen != generation || failures == 0 || recoveryOffered
                || recoveryConsumed) return null;
        Option candidate = null;
        if ("APP:OPEN".equals(goal)
                && "phone_action".equals(lastTool)
                && !"OPEN_APP".equals(lastAction)
                && "APP_OPEN_ACTION_REQUIRED".equals(failure)) {
            String packageName = LiveAppOpenRecoveryPolicy
                    .requestedPackage(finalizedUserText);
            String label = safeAppLabel(packageName);
            if (!label.isEmpty() && !packageName.equals(foregroundPackage)) {
                candidate = new Option("phone_action", "OPEN_APP",
                        label, "WRONG_TOOL_ACTION");
            }
        } else if (canInspect && ("LOCATOR_MARGIN_TOO_SMALL".equals(failure)
                || "SEARCH_NEEDS_QUERY".equals(failure))) {
            candidate = new Option("inspect_ui", "NONE",
                    "", "FRESH_OBSERVATION_REQUIRED");
        }
        if (candidate != null) {
            recoveryOption = candidate;
            recoveryOffered = true;
        }
        return candidate;
    }

    synchronized boolean isRepeatedRecovery(long gen,
            String tool, String action) {
        return gen == generation && recoveryOffered && recoveryConsumed
                && matches(recoveryOption, tool, action);
    }

    synchronized boolean consumeRecovery(long gen,
            String tool, String action) {
        if (gen != generation || !recoveryOffered || recoveryConsumed
                || !matches(recoveryOption, tool, action)) return false;
        recoveryConsumed = true;
        return true;
    }

    private static boolean matches(Option option, String tool, String action) {
        return option != null && option.tool.equals(tool)
                && ("NONE".equals(option.action)
                    || option.action.equalsIgnoreCase(
                            action == null ? "" : action.trim()));
    }

    private static boolean isDiagnosticTool(String tool) {
        return "inspect_runtime_trace".equals(tool)
                || "get_recovery_options".equals(tool);
    }

    private static String safeAppLabel(String packageName) {
        if ("com.google.android.apps.maps".equals(packageName)) {
            return "Google Maps";
        }
        if ("com.apple.android.music".equals(packageName)) {
            return "Apple Music";
        }
        if ("com.android.chrome".equals(packageName)) return "Chrome";
        if ("com.google.android.youtube".equals(packageName)) return "YouTube";
        return "";
    }

    private static String goalToken(String value) {
        return "APP:OPEN".equals(value) ? value
                : "MEDIA:PLAY".equals(value) ? value
                : "SEARCH:RESULT".equals(value) ? value
                : "NAVIGATION:START".equals(value) ? value : "OTHER";
    }

    private static String toolToken(String value) {
        if ("phone_action".equals(value) || "inspect_ui".equals(value)
                || "launch_app".equals(value) || "tap_screen".equals(value)
                || "search_current_app".equals(value)
                || "commit_search".equals(value)) return value;
        return "OTHER";
    }

    private static String actionToken(String value) {
        if (value == null) return "NONE";
        String x = value.trim().toUpperCase(Locale.ROOT);
        if ("OPEN_APP".equals(x) || "TAP".equals(x)
                || "SEARCH".equals(x) || "TYPE".equals(x)
                || "COMMIT_SEARCH".equals(x)
                || "SCROLL".equals(x)) return x;
        return x.isEmpty() ? "NONE" : "OTHER";
    }

    private static String failureToken(String value) {
        if (value == null) return "OTHER";
        String x = value.trim().toUpperCase(Locale.ROOT);
        if ("APP_OPEN_ACTION_REQUIRED".equals(x)
                || "LOCATOR_MARGIN_TOO_SMALL".equals(x)
                || "SEARCH_NEEDS_QUERY".equals(x)
                || "OPERATIONAL_TURN_NOT_FINALIZED".equals(x)
                || "STALE_USER_INTENT".equals(x)
                || "SOCKET_CLOSED".equals(x)
                || "OBSERVE_REQUIRED".equals(x)) return x;
        return "OTHER";
    }
}
