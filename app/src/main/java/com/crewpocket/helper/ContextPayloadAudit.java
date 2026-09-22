package com.crewpocket.helper;

import android.util.Log;

import java.util.Locale;

/**
 * Privacy-safe model-context telemetry. Logs sizes only; never logs prompt,
 * transcript, screen text, tool args, recipients, or tool result content.
 */
final class ContextPayloadAudit {
    static final String LOG_TAG = "CrewContextAudit";
    static final String PREFIX = "CREW_CONTEXT_AUDIT";

    private final String sessionId =
            Long.toHexString(System.currentTimeMillis())
                    + Integer.toHexString(System.identityHashCode(this));

    private long generation = -1L;
    private long turnInjectedBytes;

    synchronized String sessionId() {
        return sessionId;
    }

    synchronized void beginTurn(long nextGeneration) {
        if (generation >= 0L && generation != nextGeneration) {
            logTurnTotalLocked();
        }
        generation = nextGeneration;
        turnInjectedBytes = 0L;
        log("kind=turn_start source=turn gen=" + generation + " bytes=0");
    }

    synchronized void flushTurn() {
        if (generation >= 0L) logTurnTotalLocked();
    }

    synchronized void logSetupComponent(String source, int bytes) {
        log("kind=setup_component source=setup:" + clean(source)
                + " gen=-1 bytes=" + Math.max(0, bytes));
    }

    synchronized void logSetupTotal(
            int systemBytes,
            int toolsBytes,
            int payloadBytes) {
        log("kind=setup_total source=setup gen=-1"
                + " system=" + Math.max(0, systemBytes)
                + " tools=" + Math.max(0, toolsBytes)
                + " bytes=" + Math.max(0, payloadBytes));
    }

    synchronized void logTool(
            long toolGeneration,
            String toolName,
            int rawBytes,
            int modelBytes,
            int progressBytes,
            int playbookBytes,
            int outboundBytes) {
        ensureGeneration(toolGeneration);
        turnInjectedBytes += Math.max(0, outboundBytes);
        int ratio = rawBytes <= 0
                ? 100
                : (int) Math.round(modelBytes * 100.0d / rawBytes);
        log("kind=payload source=tool:" + clean(toolName)
                + " gen=" + generation
                + " raw=" + Math.max(0, rawBytes)
                + " model=" + Math.max(0, modelBytes)
                + " progress=" + Math.max(0, progressBytes)
                + " playbook=" + Math.max(0, playbookBytes)
                + " ratioPct=" + ratio
                + " bytes=" + Math.max(0, outboundBytes)
                + " turn=" + turnInjectedBytes);
    }

    synchronized void logInternalDirective(
            long directiveGeneration,
            int textBytes,
            int outboundBytes) {
        ensureGeneration(directiveGeneration);
        turnInjectedBytes += Math.max(0, outboundBytes);
        log("kind=payload source=internal_directive"
                + " gen=" + generation
                + " text=" + Math.max(0, textBytes)
                + " bytes=" + Math.max(0, outboundBytes)
                + " turn=" + turnInjectedBytes);
    }

    synchronized void logTypedTurn(
            long turnGeneration,
            int textBytes,
            int outboundBytes) {
        ensureGeneration(turnGeneration);
        // User-authored text is useful context but is not counted as Crew
        // injected context. Keep it visible as its own source.
        log("kind=user source=user_typed"
                + " gen=" + generation
                + " text=" + Math.max(0, textBytes)
                + " bytes=" + Math.max(0, outboundBytes)
                + " turn=" + turnInjectedBytes);
    }

    synchronized void logVision(
            String source,
            int rawBytes,
            int outboundBytes) {
        log("kind=vision source=vision:" + clean(source)
                + " gen=" + generation
                + " raw=" + Math.max(0, rawBytes)
                + " bytes=" + Math.max(0, outboundBytes));
    }

    synchronized void logBudget(
            String source,
            int actualBytes,
            int budgetBytes) {
        log("kind=budget source=" + clean(source)
                + " gen=" + generation
                + " bytes=" + Math.max(0, actualBytes)
                + " budget=" + Math.max(0, budgetBytes)
                + " over=" + (actualBytes > budgetBytes ? 1 : 0));
    }

    private void ensureGeneration(long value) {
        if (value < 0L) return;
        if (generation != value) beginTurn(value);
    }

    private void logTurnTotalLocked() {
        log("kind=turn_total source=turn gen=" + generation
                + " bytes=" + turnInjectedBytes);
    }

    private void log(String body) {
        Log.i(LOG_TAG, PREFIX
                + " sid=" + sessionId + " " + body);
    }

    private static String clean(String value) {
        String out = value == null ? "unknown" : value.trim();
        if (out.isEmpty()) return "unknown";
        return out.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_:.\\-]", "_");
    }
}
