package com.crewpocket.helper;

import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

/**
 * 0012: one atomic "type + submit + verify" runtime operation.
 *
 * Gemini decides WHAT to send. Android runtime owns HOW it is entered,
 * submitted and verified.
 */
final class SendTextTransaction {
    interface Environment {
        AccessibilityNodeInfo currentRoot();
        AccessibilityNodeInfo resolveComposer(AccessibilityNodeInfo root);
        AccessibilityNodeInfo resolveSendButton(AccessibilityNodeInfo root);
        void recordSendResolutionResult(boolean success);
    }

    static final class Result {
        boolean success;
        boolean typed;
        boolean submitted;
        boolean verified;
        boolean usedExistingComposer;
        int textLength;
        String submitMethod = "NONE";
        String stage = "START";
        String error = "";
        SendVerification.Result verification;

        static Result failure(String stage, String error) {
            Result result = new Result();
            result.stage = stage == null ? "FAILED" : stage;
            result.error = error == null ? "SEND_FAILED" : error;
            return result;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("success", success);
                o.put("typed", typed);
                o.put("submitted", submitted);
                o.put("verified", verified);
                o.put("usedExistingComposer", usedExistingComposer);
                o.put("textLength", textLength);
                o.put("submitMethod", submitMethod);
                o.put("stage", stage);
                if (!error.isEmpty()) o.put("error", error);
                if (verification != null) o.put("verification", verification.toJson());
            } catch (Exception ignored) {}
            return o;
        }
    }

    private static final long INPUT_SETTLE_MS = 180L;
    private static final long SUBMIT_SETTLE_MS = 450L;
    private static final long SECOND_VERIFY_MS = 500L;

    private final Environment environment;

    SendTextTransaction(Environment environment) {
        this.environment = environment;
    }

    Result execute(String text) {
        return executeInternal(text, false);
    }

    /**
     * 0051: explicit send-only follow-up.
     *
     * When the current composer already contains text and the newest user turn
     * is only "送出/發送/send", Runtime reads that visible composer locally,
     * submits exactly once, and verifies without exposing plaintext to Gemini.
     */
    Result executeExisting() {
        return executeInternal(null, true);
    }

    private Result executeInternal(String requestedText, boolean useExistingComposer) {
        Result result = new Result();
        result.usedExistingComposer = useExistingComposer;

        if (!useExistingComposer
                && (requestedText == null || requestedText.length() == 0)) {
            result.stage = "VALIDATE";
            result.error = "EMPTY_TEXT";
            return result;
        }

        AccessibilityNodeInfo root = null;
        AccessibilityNodeInfo composer = null;
        String submittedText = requestedText;
        try {
            result.stage = "RESOLVE_COMPOSER";
            root = environment.currentRoot();
            if (root == null) {
                result.error = "NO_ACTIVE_WINDOW";
                return result;
            }

            composer = environment.resolveComposer(root);
            if (composer == null) {
                result.error = "COMPOSER_NOT_FOUND";
                return result;
            }
            if (SensitiveDataGuard.isHardBlockedInput(composer)) {
                result.error = "SENSITIVE_INPUT_BLOCKED";
                return result;
            }

            if (useExistingComposer) {
                result.stage = "READ_EXISTING_COMPOSER";
                CharSequence current = composer.getText();
                submittedText = current == null ? "" : current.toString();
                if (submittedText.trim().isEmpty()) {
                    result.error = "COMPOSER_EMPTY";
                    return result;
                }
                result.textLength = submittedText.length();
            } else {
                result.stage = "TYPE";
                if (!setText(composer, requestedText)) {
                    result.error = "INPUT_FAILED";
                    return result;
                }
                result.typed = true;
                result.textLength = requestedText.length();

                sleep(INPUT_SETTLE_MS);

                recycle(composer);
                composer = null;
                recycle(root);
                root = environment.currentRoot();
                if (root == null) {
                    result.error = "WINDOW_LOST_AFTER_INPUT";
                    return result;
                }
                composer = environment.resolveComposer(root);
                if (composer == null || !composerContains(composer, requestedText)) {
                    result.error = "INPUT_NOT_VERIFIED";
                    return result;
                }
            }

            if (!"HAS_TEXT".equals(LearnedUiMappingStore.composerState(composer))) {
                result.error = "COMPOSER_NOT_READY_FOR_SEND";
                result.stage = useExistingComposer
                        ? "COMPOSER_EMPTY"
                        : "COMPOSER_EMPTY_AFTER_TYPE";
                return result;
            }

            SendVerification.Snapshot before =
                    SendVerification.capture(root, composer);

            result.stage = "SUBMIT";
            AccessibilityNodeInfo send = null;
            try {
                send = environment.resolveSendButton(root);
                if (send != null) {
                    if (SensitiveDataGuard.isBlockedAction(send)) {
                        recycle(send);
                        send = null;
                        result.error = "SENSITIVE_TARGET_BLOCKED";
                        return result;
                    }
                    try {
                        send.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        result.submitted = true;
                        result.submitMethod = "SEMANTIC_SEND";
                    } finally {
                        recycle(send);
                        send = null;
                    }
                } else if (tryImeEnter(composer)) {
                    result.submitted = true;
                    result.submitMethod = "IME_ENTER";
                }
            } finally {
                recycle(send);
            }

            if (!result.submitted) {
                result.error = "SUBMIT_TARGET_NOT_FOUND";
                return result;
            }

            result.stage = "VERIFY";
            sleep(SUBMIT_SETTLE_MS);

            SendVerification.Result verification =
                    verifyWithFreshSnapshot(before, submittedText);
            if (verification.state == SendVerification.State.UNVERIFIED) {
                sleep(SECOND_VERIFY_MS);
                verification = verifyWithFreshSnapshot(before, submittedText);
            }

            result.verification = verification;
            boolean verifiedOrLikely =
                    verification.state == SendVerification.State.VERIFIED
                    || verification.state == SendVerification.State.LIKELY;
            environment.recordSendResolutionResult(verifiedOrLikely);

            result.verified =
                    verification.state == SendVerification.State.VERIFIED;
            result.success = verifiedOrLikely;

            if (!result.success) {
                result.error = "SEND_NOT_VERIFIED";
            }
            result.stage = "DONE";
            return result;
        } catch (Exception e) {
            result.error = e.getMessage() == null
                    ? "SEND_TRANSACTION_FAILED"
                    : e.getMessage();
            return result;
        } finally {
            recycle(composer);
            recycle(root);
        }
    }

    private SendVerification.Result verifyWithFreshSnapshot(
            SendVerification.Snapshot before,
            String submittedText) {
        AccessibilityNodeInfo freshRoot = null;
        AccessibilityNodeInfo freshComposer = null;
        try {
            freshRoot = environment.currentRoot();
            if (freshRoot == null) {
                return new SendVerification.Result(
                        SendVerification.State.UNVERIFIED,
                        false, false, false, false);
            }
            freshComposer = environment.resolveComposer(freshRoot);
            SendVerification.Snapshot after =
                    SendVerification.capture(freshRoot, freshComposer);
            return SendVerification.verify(before, after, submittedText);
        } finally {
            recycle(freshComposer);
            recycle(freshRoot);
        }
    }

    private static boolean setText(AccessibilityNodeInfo composer, String text) {
        if (composer == null) return false;
        composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text);
        boolean ok = composer.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                args);
        if (ok) {
            try {
                Bundle selection = new Bundle();
                selection.putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                        text.length());
                selection.putInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                        text.length());
                composer.performAction(
                        AccessibilityNodeInfo.ACTION_SET_SELECTION,
                        selection);
            } catch (Exception ignored) {}
        }
        return ok;
    }

    private static boolean composerContains(AccessibilityNodeInfo composer, String expected) {
        if (composer == null || SensitiveDataGuard.isSensitiveNode(composer)) return false;
        CharSequence current = composer.getText();
        return current != null && expected.equals(current.toString());
    }

    private static boolean tryImeEnter(AccessibilityNodeInfo composer) {
        if (composer == null || Build.VERSION.SDK_INT < 30) {
            return false;
        }
        try {
            int imeEnterId = 16908372;
            try {
                Object actionObj = AccessibilityNodeInfo.AccessibilityAction.class.getField("ACTION_IME_ENTER").get(null);
                if (actionObj instanceof AccessibilityNodeInfo.AccessibilityAction) {
                    imeEnterId = ((AccessibilityNodeInfo.AccessibilityAction) actionObj).getId();
                }
            } catch (Throwable ignored) {}

            for (AccessibilityNodeInfo.AccessibilityAction action : composer.getActionList()) {
                if (action != null && action.getId() == imeEnterId) {
                    return composer.performAction(action.getId());
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void recycle(AccessibilityNodeInfo node) {
        if (node != null) {
            try { node.recycle(); } catch (Exception ignored) {}
        }
    }
}
