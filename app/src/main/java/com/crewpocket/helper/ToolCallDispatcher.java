package com.crewpocket.helper;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Owns Gemini Live tool-call transport orchestration.
 *
 * Responsibilities:
 * - de-duplicate redelivered tool ids
 * - coalesce same-generation equivalent function calls
 * - stamp calls with the authoritative user-intent generation
 * - serialize execution on one worker
 * - fan a primary Runtime result back out to coalesced Gemini call ids
 *
 * It intentionally does not own action policy, authorization, semantic
 * resolution, task lifecycle, or tool implementation. Those stay in the host.
 */
final class ToolCallDispatcher {
    interface Host {
        void executeTool(JSONObject call);
        void onToolQueued(String id, String name, long generation);
        void onDuplicateIgnored(String id, String name, long generation);
        void onWorkerChanged(Thread worker);
        void onDispatchError(Exception error);
    }

    private static final String TAG = "ToolCallDispatcher";

    private final Object lock = new Object();
    private final Host host;
    private final ToolCallDispatchLedger ledger =
            new ToolCallDispatchLedger();
    private final java.util.ArrayList<JSONObject> pending =
            new java.util.ArrayList<JSONObject>();

    private boolean workerRunning;
    private volatile Thread activeWorker;

    ToolCallDispatcher(Host host) {
        if (host == null) throw new IllegalArgumentException("host required");
        this.host = host;
    }

    boolean enqueue(JSONObject call, long generation) {
        if (call == null) return false;

        final String id = call.optString(
                "id", "tool_" + System.nanoTime());
        final String name = call.optString("name", "unknown");
        final String argsIdentity = call.optJSONObject("args") == null
                ? "{}"
                : call.optJSONObject("args").toString();
        synchronized (lock) {
            // Registration and pending insertion must be atomic with reset.
            final ToolCallDispatchLedger.Registration registration =
                    ledger.register(id, name, argsIdentity, generation);
            if (registration.duplicateCallId) return false;
            if (registration.coalesced) {
                host.onDuplicateIgnored(id, name, generation);
                Log.d(TAG, "coalesced duplicate call: "
                        + registration.signature);
                return false;
            }
            if (!registration.accepted) return false;
            try {
                call.put("_crew_intent_generation", generation);
                call.put(
                        "_crew_inflight_signature",
                        registration.signature);
                pending.add(call);
            } catch (Exception error) {
                ledger.releaseInFlight(registration.signature);
                host.onDispatchError(error);
                return false;
            }

            host.onToolQueued(id, name, generation);
        }

        drain();
        return true;
    }

    /**
     * Cancel queued calls without silently dropping Gemini function-call IDs.
     * The active worker is intentionally not cancelled here; it must return a
     * result under its original call ID, subject to Runtime's generation guard.
     */
    JSONArray resetForNewIntent() {
        JSONArray cancelled = new JSONArray();
        JSONObject reason = new JSONObject();
        try {
            reason.put("success", false);
            reason.put("stale", true);
            reason.put("blockedByRuntime", true);
            reason.put("taskState", "CANCELLED");
            reason.put("error", "SUPERSEDED_BY_NEW_INTENT");
        } catch (Exception ignored) {}

        synchronized (lock) {
            for (JSONObject call : pending) {
                String id = call.optString("id", "");
                String name = call.optString("name", "unknown");
                for (ToolCallDispatchLedger.Recipient recipient
                        : ledger.responseRecipients(id, name)) {
                    cancelled.put(response(recipient.id, recipient.name, reason));
                }
                ledger.releaseInFlight(call.optString(
                        "_crew_inflight_signature", ""));
            }
            pending.clear();
            // Running async calls still need their response-recipient ledger.
            ledger.resetForNewIntent();
        }
        return cancelled;
    }

    void clearPending() {
        synchronized (lock) {
            pending.clear();
        }
    }

    Thread activeWorker() {
        return activeWorker;
    }

    JSONArray expandResponses(
            String primaryId,
            String primaryName,
            JSONObject modelResult) {
        JSONArray responses = new JSONArray();
        List<ToolCallDispatchLedger.Recipient> recipients =
                ledger.responseRecipients(primaryId, primaryName);
        for (ToolCallDispatchLedger.Recipient recipient : recipients) {
            responses.put(response(
                    recipient.id,
                    recipient.name,
                    modelResult));
        }
        return responses;
    }

    int pendingCountForTest() {
        synchronized (lock) {
            return pending.size();
        }
    }

    int inFlightCountForTest() {
        return ledger.inFlightCount();
    }

    private void drain() {
        final JSONObject call;

        synchronized (lock) {
            if (workerRunning || pending.isEmpty()) return;
            workerRunning = true;
            call = pending.remove(0);
        }

        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                Thread current = Thread.currentThread();
                activeWorker = current;
                host.onWorkerChanged(current);
                try {
                    host.executeTool(call);
                } finally {
                    synchronized (lock) {
                        ledger.releaseInFlight(
                                call.optString(
                                        "_crew_inflight_signature", ""));
                        workerRunning = false;
                    }
                    activeWorker = null;
                    host.onWorkerChanged(null);
                    drain();
                }
            }
        }, "crew-native-live-agent-tool");

        worker.start();
    }

    private static JSONObject response(
            String id,
            String name,
            JSONObject modelResult) {
        JSONObject item = new JSONObject();
        try {
            item.put(
                    "response",
                    new JSONObject().put(
                            "result",
                            modelResult == null
                                    ? new JSONObject()
                                    : modelResult));
            item.put("id", id == null ? "" : id);
            item.put("name", name == null ? "unknown" : name);
        } catch (Exception ignored) {}
        return item;
    }
}
