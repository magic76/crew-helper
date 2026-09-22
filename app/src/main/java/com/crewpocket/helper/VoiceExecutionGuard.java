package com.crewpocket.helper;

import org.json.JSONObject;

import java.util.List;

/**
 * Ephemeral Runtime gate for finalized voice commands.
 *
 * It never persists transcripts or tool payloads. Critical-entity confirmation
 * is a one-shot lease bound to the exact action fingerprint.
 */
final class VoiceExecutionGuard {
    enum TurnDisposition {
        NORMAL,
        CONFIRMATION_ACCEPTED,
        CONFIRMATION_REJECTED
    }

    static final class Preflight {
        final boolean allowed;
        final String code;
        final String confirmationSummary;
        final String instruction;

        private Preflight(
                boolean allowed,
                String code,
                String confirmationSummary,
                String instruction) {
            this.allowed = allowed;
            this.code = code == null ? "" : code;
            this.confirmationSummary =
                    confirmationSummary == null ? "" : confirmationSummary;
            this.instruction = instruction == null ? "" : instruction;
        }

        static Preflight allow() {
            return new Preflight(true, "ALLOW", "", "");
        }

        static Preflight block(
                String code,
                String summary,
                String instruction) {
            return new Preflight(false, code, summary, instruction);
        }
    }

    private static final long CONFIRMATION_TTL_MS = 30_000L;

    private static final class Pending {
        final String fingerprint;
        final String summary;
        final long createdAt;

        Pending(String fingerprint, String summary, long createdAt) {
            this.fingerprint = fingerprint;
            this.summary = summary;
            this.createdAt = createdAt;
        }
    }

    private long latestVoiceGeneration = -1L;
    private String latestVoiceText = "";
    private double latestVoiceConfidence = -1.0d;
    private boolean interimPending;

    private Pending pending;
    private String confirmedFingerprint = "";
    private String confirmedSummary = "";
    private long confirmedUntil = 0L;

    synchronized void onInterimVoice(String text) {
        if (text != null && !text.trim().isEmpty()) {
            interimPending = true;
        }
    }

    synchronized boolean hasPendingInterim() {
        return interimPending;
    }

    synchronized TurnDisposition onFinalizedVoiceTurn(
            long generation,
            String text) {
        return onFinalizedVoiceTurn(generation, text, -1.0d);
    }

    synchronized TurnDisposition onFinalizedVoiceTurn(
            long generation,
            String text,
            double confidence) {
        interimPending = false;
        latestVoiceGeneration = generation;
        latestVoiceText = text == null ? "" : text.trim();
        latestVoiceConfidence = confidence;

        if (pending == null) return TurnDisposition.NORMAL;

        long now = System.currentTimeMillis();
        if (now - pending.createdAt > CONFIRMATION_TTL_MS) {
            pending = null;
            confirmedFingerprint = "";
            confirmedSummary = "";
            confirmedUntil = 0L;
            return TurnDisposition.NORMAL;
        }

        if (VoiceCommandQualityPolicy.isAffirmative(latestVoiceText)) {
            confirmedFingerprint = pending.fingerprint;
            confirmedSummary = pending.summary;
            confirmedUntil = now + CONFIRMATION_TTL_MS;
            pending = null;
            return TurnDisposition.CONFIRMATION_ACCEPTED;
        }

        if (VoiceCommandQualityPolicy.isNegative(latestVoiceText)) {
            pending = null;
            confirmedFingerprint = "";
            confirmedSummary = "";
            confirmedUntil = 0L;
            return TurnDisposition.CONFIRMATION_REJECTED;
        }

        // A different utterance replaces the pending confirmation.
        pending = null;
        confirmedFingerprint = "";
        confirmedSummary = "";
        confirmedUntil = 0L;
        return TurnDisposition.NORMAL;
    }

    synchronized void onFinalizedTypedTurn(long generation, String text) {
        interimPending = false;
        latestVoiceGeneration = -1L;
        latestVoiceText = "";
        latestVoiceConfidence = -1.0d;
        pending = null;
        confirmedFingerprint = "";
        confirmedSummary = "";
        confirmedUntil = 0L;
    }

    synchronized boolean isVoiceGeneration(long generation) {
        return generation >= 0L && generation == latestVoiceGeneration;
    }

    synchronized String latestVoiceText(long generation) {
        return isVoiceGeneration(generation) ? latestVoiceText : "";
    }

    synchronized Preflight preflight(
            long generation,
            String runtimeName,
            JSONObject runtimeArgs) {
        if (!isVoiceGeneration(generation)) {
            return Preflight.allow();
        }

        String finalized = latestVoiceText;
        if (VoiceExecutionPolicy.shouldRepeat(
                finalized, latestVoiceConfidence)) {
            String code = latestVoiceConfidence >= 0.0d
                    && latestVoiceConfidence < 0.72d
                    ? "VOICE_TRANSCRIPT_LOW_CONFIDENCE"
                    : "VOICE_TRANSCRIPT_INCOMPLETE";
            return Preflight.block(
                    code,
                    "",
                    "這段語音不足以安全執行手機 mutation。不要硬做；請使用者把指令再說完整一次。");
        }

        // Critical-entity read-back is required immediately before message
        // submission or another Runtime-recognized irreversible target.
        // Navigation/search/type stay fluid.
        if (!VoiceExecutionPolicy.requiresCriticalEntityConfirmation(
                runtimeName,
                criticalTargetMetadata(runtimeArgs))) {
            return Preflight.allow();
        }

        long now = System.currentTimeMillis();

        // A confirmation utterance contains only "對/確認", not the original
        // entities. Bind the lease to the original summary + exact payload.
        if (!confirmedFingerprint.isEmpty()) {
            if (now > confirmedUntil) {
                confirmedFingerprint = "";
                confirmedSummary = "";
                confirmedUntil = 0L;
            } else {
                String confirmedAction =
                        fingerprint(
                                runtimeName,
                                runtimeArgs,
                                confirmedSummary);
                if (confirmedFingerprint.equals(confirmedAction)) {
                    confirmedFingerprint = "";
                    confirmedSummary = "";
                    confirmedUntil = 0L;
                    return Preflight.allow();
                }
                return Preflight.block(
                        "VOICE_CONFIRMATION_ACTION_MISMATCH",
                        confirmedSummary,
                        "剛才只確認了原本那一份送出內容；目前 action 已改變，Runtime 不會沿用確認。請重新複誦目前關鍵資訊。");
            }
        }

        List<String> entities =
                VoiceCommandQualityPolicy.criticalEntities(finalized);
        String summary =
                VoiceCommandQualityPolicy.summary(entities);
        if (summary.isEmpty()
                && "start_conversation_loop".equals(runtimeName)) {
            String recipient = runtimeArgs == null
                    ? "" : runtimeArgs.optString("recipient", "").trim();
            if (!recipient.isEmpty()) {
                summary = "收件人：" + recipient;
            }
        }
        if (summary.isEmpty()) {
            return Preflight.allow();
        }

        String fingerprint =
                fingerprint(runtimeName, runtimeArgs, summary);
        pending = new Pending(fingerprint, summary, now);
        return Preflight.block(
                "VOICE_CRITICAL_ENTITY_CONFIRMATION_REQUIRED",
                summary,
                "執行前先複誦確認：" + summary
                        + "。只問一次「確認嗎？」；使用者明確確認後，才可重送完全相同的 action。");
    }

    synchronized String pendingSummary() {
        return pending == null ? "" : pending.summary;
    }

    synchronized void clear() {
        interimPending = false;
        pending = null;
        confirmedFingerprint = "";
        confirmedSummary = "";
        confirmedUntil = 0L;
        latestVoiceGeneration = -1L;
        latestVoiceText = "";
        latestVoiceConfidence = -1.0d;
    }

    private static String criticalTargetMetadata(JSONObject args) {
        JSONObject safe = args == null ? new JSONObject() : args;
        return safe.optString("label", "") + " "
                + safe.optString("target", "") + " "
                + safe.optString("id", "") + " "
                + safe.optString("element_id", "") + " "
                + safe.optString("semanticHint", "");
    }

    private static String fingerprint(
            String runtimeName,
            JSONObject args,
            String summary) {
        String raw = (runtimeName == null ? "" : runtimeName)
                + "|"
                + (args == null ? "{}" : args.toString())
                + "|"
                + (summary == null ? "" : summary);
        return Integer.toHexString(raw.hashCode());
    }
}
