package com.crewpocket.helper;

import java.util.Locale;

/**
 * Owns the boundary between finalized transcription segments and actual
 * foreground user intents.
 *
 * Gemini Live may finalize more than one transcription segment for what the
 * human experiences as one spoken instruction. A finalized transcript is
 * authoritative text, but is not by itself authority to supersede an active
 * Agent task.
 */
final class LiveHumanTurnBoundary {
    enum Decision {
        NEW_INTENT,
        BIND_CURRENT_GENERATION,
        MERGE_CURRENT_SEGMENT
    }

    static final class Resolution {
        final Decision decision;
        final String effectiveText;
        final String reason;

        Resolution(Decision decision, String effectiveText, String reason) {
            this.decision = decision;
            this.effectiveText = effectiveText == null ? "" : effectiveText;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static final long SEGMENT_MERGE_WINDOW_MS = 4_500L;
    private static final long RELATED_SEGMENT_WINDOW_MS = 8_000L;
    private static final long LATE_DUPLICATE_WINDOW_MS = 1_500L;

    private String currentText = "";
    private long lastFinalizedAtMs;
    private boolean modelSpokenSinceFinalized;
    private boolean serverIdleSinceFinalized;
    private String interactionStatus = "";
    private boolean waitingForInput;

    synchronized Resolution resolve(
            String rawText,
            long nowMs,
            boolean hasActiveTask,
            long activeTaskGeneration,
            long currentGeneration,
            long latestFinalizedGeneration,
            String frameInteractionStatus,
            boolean frameWaitingForInput,
            boolean frameInterrupted) {
        String text = clean(rawText);
        long elapsed = lastFinalizedAtMs <= 0L
                ? Long.MAX_VALUE
                : Math.max(0L, nowMs - lastFinalizedAtMs);

        String status = normalizeStatus(frameInteractionStatus);
        if (status.isEmpty()) status = interactionStatus;
        boolean waiting = frameWaitingForInput || waitingForInput;

        boolean sameGenerationTask =
                hasActiveTask
                        && activeTaskGeneration == currentGeneration;

        if (frameInterrupted || looksLikeExplicitTakeover(text)) {
            return commit(
                    Decision.NEW_INTENT,
                    text,
                    nowMs,
                    frameInterrupted
                            ? "SERVER_INTERRUPTED"
                            : "EXPLICIT_USER_TAKEOVER");
        }

        if (sameGenerationTask
                && latestFinalizedGeneration < currentGeneration) {
            return commit(
                    Decision.BIND_CURRENT_GENERATION,
                    text,
                    nowMs,
                    "TOOL_PRECEDED_FINAL_TRANSCRIPT");
        }

        boolean related = relatedText(currentText, text);
        if (related
                && ((sameGenerationTask
                        && elapsed <= RELATED_SEGMENT_WINDOW_MS)
                    || elapsed <= LATE_DUPLICATE_WINDOW_MS)) {
            return commit(
                    Decision.MERGE_CURRENT_SEGMENT,
                    mergeText(currentText, text),
                    nowMs,
                    "RELATED_FINAL_SEGMENT");
        }

        if (sameGenerationTask
                && latestFinalizedGeneration == currentGeneration) {
            boolean interactionStillOpen =
                    "IN_PROGRESS".equals(status)
                            || waiting
                            || !serverIdleSinceFinalized;
            boolean silentSegmentGrace =
                    !modelSpokenSinceFinalized
                            && elapsed <= SEGMENT_MERGE_WINDOW_MS;

            boolean boundedLiveContinuation =
                    interactionStillOpen
                            && elapsed <= SEGMENT_MERGE_WINDOW_MS;

            if (boundedLiveContinuation || silentSegmentGrace) {
                return commit(
                        Decision.MERGE_CURRENT_SEGMENT,
                        mergeText(currentText, text),
                        nowMs,
                        boundedLiveContinuation
                                ? "LIVE_INTERACTION_CONTINUES"
                                : "SILENT_SEGMENT_GRACE");
            }
        }

        return commit(
                Decision.NEW_INTENT,
                text,
                nowMs,
                "NEW_HUMAN_TURN");
    }

    synchronized void observeServerState(
            boolean turnComplete,
            String rawInteractionStatus,
            boolean waitingForInput) {
        String status = normalizeStatus(rawInteractionStatus);
        if (!status.isEmpty()) {
            interactionStatus = status;
        }
        this.waitingForInput = waitingForInput;

        if (!turnComplete) return;
        if ("IDLE".equals(status)) {
            serverIdleSinceFinalized = true;
        } else if ("IN_PROGRESS".equals(status)) {
            serverIdleSinceFinalized = false;
        }
    }

    synchronized void noteModelSpeech() {
        modelSpokenSinceFinalized = true;
    }

    synchronized void forceNewTurn(String text, long nowMs) {
        currentText = clean(text);
        lastFinalizedAtMs = nowMs;
        modelSpokenSinceFinalized = false;
        serverIdleSinceFinalized = false;
        waitingForInput = false;
        interactionStatus = "";
    }

    synchronized void reset() {
        currentText = "";
        lastFinalizedAtMs = 0L;
        modelSpokenSinceFinalized = false;
        serverIdleSinceFinalized = false;
        waitingForInput = false;
        interactionStatus = "";
    }

    private Resolution commit(
            Decision decision,
            String effectiveText,
            long nowMs,
            String reason) {
        currentText = clean(effectiveText);
        lastFinalizedAtMs = nowMs;
        modelSpokenSinceFinalized = false;
        serverIdleSinceFinalized = false;
        waitingForInput = false;
        if (decision == Decision.NEW_INTENT) {
            interactionStatus = "";
        }
        return new Resolution(decision, currentText, reason);
    }

    static String mergeText(String previous, String next) {
        String left = clean(previous);
        String right = clean(next);
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;

        String nl = comparable(left);
        String nr = comparable(right);
        if (nl.equals(nr)) return left;
        if (nr.contains(nl)) return right.length() >= left.length() ? right : left;
        if (nl.contains(nr)) return left;
        return (left + " " + right).trim();
    }

    private static boolean relatedText(String a, String b) {
        String left = comparable(a);
        String right = comparable(b);
        if (left.isEmpty() || right.isEmpty()) return false;
        return left.equals(right)
                || left.contains(right)
                || right.contains(left);
    }

    private static boolean looksLikeExplicitTakeover(String value) {
        String text = comparable(value);
        if (text.isEmpty()) return false;
        return containsAny(
                text,
                "停止", "取消", "等等", "等一下", "不要", "不是", "改成", "換成", "换成",
                "錯了", "错了", "不對", "不对", "搞錯", "搞错", "弄錯", "弄错",
                "你做錯", "你做错", "我是說", "我是说", "我說的是", "我说的是",
                "我要的是", "應該是", "应该是", "重新來", "重新来",
                "stop", "cancel", "wait", "wrong", "that'swrong", "thatswrong",
                "imeant", "notthat", "instead", "changeto");
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(comparable(term))) return true;
        }
        return false;
    }

    private static String normalizeStatus(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String comparable(String value) {
        return clean(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()_-]+", "");
    }

    private static String clean(String value) {
        return value == null
                ? ""
                : value.replaceAll("\\s+", " ").trim();
    }
}
