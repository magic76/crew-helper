package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure state machine for Live tool dispatch identity/coalescing.
 *
 * No Android or JSON dependency so transport invariants can be unit-tested.
 */
final class ToolCallDispatchLedger {
    static final class Recipient {
        final String id;
        final String name;

        Recipient(String id, String name) {
            this.id = safe(id);
            this.name = safe(name).isEmpty() ? "unknown" : safe(name);
        }
    }

    static final class Registration {
        final boolean accepted;
        final boolean duplicateCallId;
        final boolean coalesced;
        final String signature;

        Registration(
                boolean accepted,
                boolean duplicateCallId,
                boolean coalesced,
                String signature) {
            this.accepted = accepted;
            this.duplicateCallId = duplicateCallId;
            this.coalesced = coalesced;
            this.signature = safe(signature);
        }
    }

    private final Set<String> handledCallIds = new HashSet<String>();
    private final Set<String> inFlightSignatures = new HashSet<String>();
    private final HashMap<String, String> primarySignatures =
            new HashMap<String, String>();
    private final HashMap<String, ArrayList<Recipient>> coalescedRecipients =
            new HashMap<String, ArrayList<Recipient>>();

    synchronized Registration register(
            String id,
            String name,
            String argsIdentity,
            long generation) {
        String callId = safe(id);
        String callName = safe(name).isEmpty() ? "unknown" : safe(name);
        if (!handledCallIds.add(callId)) {
            return new Registration(false, true, false, "");
        }

        String signature = buildSignature(
                generation,
                callName,
                argsIdentity);

        if (!inFlightSignatures.add(signature)) {
            ArrayList<Recipient> recipients =
                    coalescedRecipients.get(signature);
            if (recipients == null) {
                recipients = new ArrayList<Recipient>();
                coalescedRecipients.put(signature, recipients);
            }
            recipients.add(new Recipient(callId, callName));
            return new Registration(false, false, true, signature);
        }

        primarySignatures.put(callId, signature);
        return new Registration(true, false, false, signature);
    }

    synchronized void releaseInFlight(String signature) {
        inFlightSignatures.remove(safe(signature));
    }

    synchronized List<Recipient> responseRecipients(
            String primaryId,
            String primaryName) {
        ArrayList<Recipient> out = new ArrayList<Recipient>();
        out.add(new Recipient(primaryId, primaryName));

        String signature = primarySignatures.remove(safe(primaryId));
        if (signature == null) return out;

        ArrayList<Recipient> duplicates =
                coalescedRecipients.remove(signature);
        if (duplicates != null) out.addAll(duplicates);
        return out;
    }

    synchronized void resetForNewIntent() {
        inFlightSignatures.clear();
        primarySignatures.clear();
        coalescedRecipients.clear();
    }

    synchronized int inFlightCount() {
        return inFlightSignatures.size();
    }

    synchronized int coalescedRecipientCount() {
        int count = 0;
        for (ArrayList<Recipient> recipients : coalescedRecipients.values()) {
            if (recipients != null) count += recipients.size();
        }
        return count;
    }

    static String buildSignature(
            long generation,
            String name,
            String argsIdentity) {
        return generation
                + "|"
                + (safe(name).isEmpty() ? "unknown" : safe(name))
                + ":"
                + safe(argsIdentity);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
