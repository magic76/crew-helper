package com.crewpocket.helper;

import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 0014: local send verification.
 *
 * Important: composer-cleared alone is NEVER treated as send success.
 * Verification prefers a newly appeared non-editable node whose text matches
 * the submitted text. Fallback requires a non-composer conversation change.
 *
 * Raw message text never leaves this class; only short hashes/booleans are
 * returned to the model/runtime.
 */
final class SendVerification {
    enum State {
        VERIFIED,
        LIKELY,
        UNVERIFIED
    }

    static final class Snapshot {
        final String packageName;
        final String screenFingerprint;
        final String conversationFingerprint;
        final String composerTextHash;
        final boolean composerHasText;
        final Set<String> visibleNonEditableTextHashes;

        Snapshot(String packageName,
                 String screenFingerprint,
                 String conversationFingerprint,
                 String composerTextHash,
                 boolean composerHasText,
                 Set<String> visibleNonEditableTextHashes) {
            this.packageName = packageName;
            this.screenFingerprint = screenFingerprint;
            this.conversationFingerprint = conversationFingerprint;
            this.composerTextHash = composerTextHash;
            this.composerHasText = composerHasText;
            this.visibleNonEditableTextHashes = visibleNonEditableTextHashes;
        }
    }

    static final class Result {
        final State state;
        final boolean composerCleared;
        final boolean conversationChanged;
        final boolean matchingMessageAppeared;
        final boolean packageStable;

        Result(State state,
               boolean composerCleared,
               boolean conversationChanged,
               boolean matchingMessageAppeared,
               boolean packageStable) {
            this.state = state;
            this.composerCleared = composerCleared;
            this.conversationChanged = conversationChanged;
            this.matchingMessageAppeared = matchingMessageAppeared;
            this.packageStable = packageStable;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("state", state.name());
                o.put("verified", state == State.VERIFIED);
                o.put("likely", state == State.LIKELY);
                o.put("composerCleared", composerCleared);
                o.put("conversationChanged", conversationChanged);
                o.put("matchingMessageAppeared", matchingMessageAppeared);
                o.put("packageStable", packageStable);
            } catch (Exception ignored) {}
            return o;
        }
    }

    private SendVerification() {}

    static Snapshot capture(AccessibilityNodeInfo root, AccessibilityNodeInfo composer) {
        if (root == null) {
            return new Snapshot("", "", "", "", false, new HashSet<String>());
        }

        String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        String screen = ScreenFingerprint.create(root);

        String composerHash = "";
        boolean composerHasText = false;
        if (composer != null && !SensitiveDataGuard.isSensitiveNode(composer)) {
            CharSequence value = composer.getText();
            if (value != null && normalize(value.toString()).length() > 0) {
                composerHash = hash(normalize(value.toString()));
                composerHasText = true;
            }
        }

        StringBuilder conversation = new StringBuilder();
        Set<String> hashes = new HashSet<String>();
        appendConversation(root, conversation, hashes);
        return new Snapshot(
                pkg,
                screen,
                hash(conversation.toString()),
                composerHash,
                composerHasText,
                hashes
        );
    }

    static Result verify(Snapshot beforeSubmit, Snapshot afterSubmit, String submittedText) {
        if (beforeSubmit == null || afterSubmit == null) {
            return new Result(State.UNVERIFIED, false, false, false, false);
        }

        String expectedHash = hash(normalize(submittedText));
        boolean packageStable = beforeSubmit.packageName.equals(afterSubmit.packageName);
        boolean composerCleared = beforeSubmit.composerHasText && !afterSubmit.composerHasText;
        boolean conversationChanged =
                !beforeSubmit.conversationFingerprint.equals(afterSubmit.conversationFingerprint);

        boolean matchingMessageAppeared =
                expectedHash.length() > 0
                && afterSubmit.visibleNonEditableTextHashes.contains(expectedHash)
                && !beforeSubmit.visibleNonEditableTextHashes.contains(expectedHash);

        State state = State.UNVERIFIED;

        // Strong signal: submitted text newly exists outside the editable field.
        if (packageStable && matchingMessageAppeared) {
            state = State.VERIFIED;
        }
        // Conservative fallback for apps whose outgoing bubble is not exposed as
        // exact text. It requires BOTH composer clearing and a conversation-only
        // structural/content change. Clearing the composer with an X does not
        // change conversationFingerprint, so it does not pass this branch.
        else if (packageStable && composerCleared && conversationChanged) {
            state = State.LIKELY;
        }

        return new Result(
                state,
                composerCleared,
                conversationChanged,
                matchingMessageAppeared,
                packageStable
        );
    }

    private static void appendConversation(AccessibilityNodeInfo node,
                                           StringBuilder out,
                                           Set<String> textHashes) {
        if (node == null) return;

        // Ignore editable nodes and their user-entered text. This is the key
        // difference from ScreenFingerprint: composer clear/edit must not count
        // as "conversation changed".
        if (!node.isEditable() && !SensitiveDataGuard.isSensitiveNode(node)) {
            String cls = normalize(node.getClassName() == null ? "" : node.getClassName().toString());
            String id = normalize(node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName());
            String text = normalize(node.getText() == null ? "" : node.getText().toString());
            String desc = normalize(node.getContentDescription() == null ? "" : node.getContentDescription().toString());

            out.append(cls).append(':').append(id).append(':')
                    .append(hash(text)).append(':').append(hash(desc)).append(':')
                    .append(node.isClickable() ? '1' : '0').append('|');

            if (!text.isEmpty()) textHashes.add(hash(text));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                appendConversation(child, out, textHashes);
            } finally {
                child.recycle();
            }
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String hash(String value) {
        if (value == null || value.length() == 0) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            // 12 bytes / 24 hex chars is enough for local comparison and avoids
            // returning plaintext message content.
            for (int i = 0; i < 12 && i < bytes.length; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
            }
            return hex.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
