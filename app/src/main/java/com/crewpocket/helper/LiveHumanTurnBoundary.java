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

    private static final long QUICK_SEGMENT_MERGE_WINDOW_MS = 1_800L;
    // While a foreground tool is still executing (or Runtime is waiting to
    // observe its effect), a nearby finalized segment is more likely to be a
    // split/late part of the same spoken instruction than a deliberate
    // replacement. Keep this window short; explicit takeover phrases still
    // bypass it immediately.
    private static final long ACTIVE_OPERATION_BIND_WINDOW_MS = 3_500L;
    // After a Runtime tool result is returned, Gemini commonly spends a few
    // seconds deciding the next step. A nearby finalized transcript fragment
    // during that gap must not supersede the still-active Agent task.
    private static final long ACTIVE_AGENT_THINK_BIND_WINDOW_MS = 4_000L;
    private static final long SEGMENT_MERGE_WINDOW_MS = 4_500L;
    private static final long RELATED_SEGMENT_WINDOW_MS = 8_000L;
    private static final long LATE_DUPLICATE_WINDOW_MS = 4_500L;

    private String currentText = "";
    private long lastFinalizedAtMs;
    private boolean modelSpokenSinceFinalized;
    private boolean serverIdleSinceFinalized;
    private String interactionStatus = "";
    private boolean waitingForInput;
    // Transient only: used to tell a genuinely new repeated command from a
    // finalized transcript that arrived after Runtime had already completed
    // the task. No transcript text is persisted outside this process object.
    private String lastInterimText = "";
    private long lastInterimAtMs;
    private long lastTaskCompletedAtMs;
    private long lastTaskCompletedGeneration = -1L;

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
        return resolve(
                rawText,
                nowMs,
                hasActiveTask,
                activeTaskGeneration,
                currentGeneration,
                latestFinalizedGeneration,
                frameInteractionStatus,
                frameWaitingForInput,
                frameInterrupted,
                false,
                false);
    }

    synchronized Resolution resolve(
            String rawText,
            long nowMs,
            boolean hasActiveTask,
            long activeTaskGeneration,
            long currentGeneration,
            long latestFinalizedGeneration,
            String frameInteractionStatus,
            boolean frameWaitingForInput,
            boolean frameInterrupted,
            boolean activeForegroundOperation) {
        return resolve(
                rawText,
                nowMs,
                hasActiveTask,
                activeTaskGeneration,
                currentGeneration,
                latestFinalizedGeneration,
                frameInteractionStatus,
                frameWaitingForInput,
                frameInterrupted,
                activeForegroundOperation,
                false);
    }

    synchronized Resolution resolve(
            String rawText,
            long nowMs,
            boolean hasActiveTask,
            long activeTaskGeneration,
            long currentGeneration,
            long latestFinalizedGeneration,
            String frameInteractionStatus,
            boolean frameWaitingForInput,
            boolean frameInterrupted,
            boolean activeForegroundOperation,
            boolean activeAgentThinking) {
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

        // A tool may complete before Gemini emits the authoritative final
        // transcript. If we already saw a related interim BEFORE that task
        // completed, a matching final arriving shortly afterwards belongs to
        // the completed utterance even if the model has already spoken.
        //
        // A genuine user retry after completion produces a fresh interim after
        // lastTaskCompletedAtMs, so it remains a NEW_INTENT.
        boolean lateFinalFromCompletedUtterance =
                !hasActiveTask
                        && currentGeneration == lastTaskCompletedGeneration
                        && lastTaskCompletedAtMs > 0L
                        && nowMs >= lastTaskCompletedAtMs
                        && nowMs - lastTaskCompletedAtMs
                                <= RELATED_SEGMENT_WINDOW_MS
                        && lastInterimAtMs > 0L
                        && lastInterimAtMs <= lastTaskCompletedAtMs
                        && relatedText(lastInterimText, text)
                        && (relatedText(currentText, text)
                            || (relatedText(lastInterimText, currentText)
                                && relatedText(lastInterimText, text)));
        if (lateFinalFromCompletedUtterance) {
            return commit(
                    Decision.MERGE_CURRENT_SEGMENT,
                    mergeText(currentText, text),
                    nowMs,
                    "FINAL_FROM_PRE_COMPLETION_INTERIM");
        }

        if (sameGenerationTask
                && latestFinalizedGeneration < currentGeneration) {
            return commit(
                    Decision.BIND_CURRENT_GENERATION,
                    text,
                    nowMs,
                    "TOOL_PRECEDED_FINAL_TRANSCRIPT");
        }

        // A short standalone swipe spoken after an already-finalized
        // command is a fresh foreground instruction, not a continuation of an
        // active music/search/navigation goal. This check deliberately requires
        // a finalized turn in the current generation so tool-first transcription
        // for the original command still binds normally.
        boolean standaloneGestureNewIntent =
                sameGenerationTask
                        && latestFinalizedGeneration == currentGeneration
                        && !relatedText(currentText, text)
                        && DirectGestureCompletionPolicy
                                .looksLikeStandaloneGestureIntent(text);
        if (standaloneGestureNewIntent) {
            return commit(
                    Decision.NEW_INTENT,
                    text,
                    nowMs,
                    "STANDALONE_GESTURE_NEW_INTENT");
        }

        boolean related = relatedText(currentText, text);
        if (related
                && ((sameGenerationTask
                        && elapsed <= RELATED_SEGMENT_WINDOW_MS)
                    || (!modelSpokenSinceFinalized
                        && elapsed <= LATE_DUPLICATE_WINDOW_MS))) {
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

            // Gemini may split one spoken sentence into nearby finalized
            // segments. Keep that grace short so a genuinely new command a few
            // seconds later is not silently merged into the old Agent turn.
            boolean quickSegmentContinuation =
                    elapsed <= QUICK_SEGMENT_MERGE_WINDOW_MS
                            && (interactionStillOpen
                                    || !modelSpokenSinceFinalized);

            // Natural follow-ups such as "然後導航過去" may arrive after a
            // longer pause while the same task is still active. Preserve them
            // without generation++/cancelling the in-flight task.
            boolean explicitContinuation =
                    interactionStillOpen
                            && elapsed <= SEGMENT_MERGE_WINDOW_MS
                            && looksLikeContinuationSegment(text);

            if (quickSegmentContinuation || explicitContinuation) {
                return commit(
                        Decision.MERGE_CURRENT_SEGMENT,
                        mergeText(currentText, text),
                        nowMs,
                        explicitContinuation
                                ? "EXPLICIT_CONTINUATION_SEGMENT"
                                : "QUICK_FINAL_SEGMENT");
            }

            // Do not kill an in-flight mutation merely because Gemini Live
            // finalized another nearby fragment. Runtime still owns the
            // foreground operation, so bind the text to the same generation
            // until either the short grace expires or the user explicitly
            // takes over.
            boolean operationDebounce =
                    activeForegroundOperation
                            && !modelSpokenSinceFinalized
                            && elapsed <= ACTIVE_OPERATION_BIND_WINDOW_MS;
            if (operationDebounce) {
                return commit(
                        Decision.BIND_CURRENT_GENERATION,
                        mergeText(currentText, text),
                        nowMs,
                        "ACTIVE_OPERATION_DEBOUNCE");
            }

            boolean agentThinkGrace =
                    activeAgentThinking
                            && !modelSpokenSinceFinalized
                            && elapsed <= ACTIVE_AGENT_THINK_BIND_WINDOW_MS;
            if (agentThinkGrace) {
                return commit(
                        Decision.BIND_CURRENT_GENERATION,
                        mergeText(currentText, text),
                        nowMs,
                        "ACTIVE_AGENT_THINK_GRACE");
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

    synchronized void noteInterim(
            String text,
            long nowMs) {
        lastInterimText = clean(text);
        lastInterimAtMs = Math.max(0L, nowMs);
    }

    synchronized void noteTaskCompleted(
            long generation,
            long nowMs) {
        lastTaskCompletedGeneration = generation;
        lastTaskCompletedAtMs = Math.max(0L, nowMs);
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
        lastInterimText = "";
        lastInterimAtMs = 0L;
        lastTaskCompletedAtMs = 0L;
        lastTaskCompletedGeneration = -1L;
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

    private static boolean looksLikeContinuationSegment(String value) {
        String text = comparable(value);
        if (text.isEmpty()) return false;
        return startsWithAny(
                text,
                "然後", "然后", "接著", "接着", "再", "順便", "顺便",
                "還有", "还有", "並且", "并且", "以及", "也幫我", "也帮我",
                "and", "then", "also", "plus");
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(comparable(term))) return true;
        }
        return false;
    }

    private static boolean startsWithAny(String value, String... terms) {
        for (String term : terms) {
            if (value.startsWith(comparable(term))) return true;
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
