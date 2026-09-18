package com.crewpocket.helper;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

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

    private static final class ResponseRecipient {
        final String id;
        final String name;

        ResponseRecipient(String id, String name) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "unknown" : name;
        }
    }

    private final Object lock = new Object();
    private final Host host;
    private final Set<String> handledCallIds = new HashSet<String>();
    private final Set<String> inFlightSignatures = new HashSet<String>();
    private final HashMap<String, String> primarySignatures =
            new HashMap<String, String>();
    private final HashMap<String, ArrayList<ResponseRecipient>> coalescedRecipients =
            new HashMap<String, ArrayList<ResponseRecipient>>();
    private final ArrayList<JSONObject> pending = new ArrayList<JSONObject>();

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
        final String signature =
                generation + "|" + incomingSignature(call);

        synchronized (lock) {
            if (!handledCallIds.add(id)) return false;

            if (!inFlightSignatures.add(signature)) {
                ArrayList<ResponseRecipient> recipients =
                        coalescedRecipients.get(signature);
                if (recipients == null) {
                    recipients = new ArrayList<ResponseRecipient>();
                    coalescedRecipients.put(signature, recipients);
                }
                recipients.add(new ResponseRecipient(id, name));
                host.onDuplicateIgnored(id, name, generation);
                Log.d(TAG, "coalesced duplicate call: " + signature);
                return false;
            }

            try {
                primarySignatures.put(id, signature);
                call.put("_crew_intent_generation", generation);
                call.put("_crew_inflight_signature", signature);
                pending.add(call);
            } catch (Exception error) {
                inFlightSignatures.remove(signature);
                primarySignatures.remove(id);
                handledCallIds.remove(id);
                host.onDispatchError(error);
                return false;
            }

            host.onToolQueued(id, name, generation);
        }

        drain();
        return true;
    }

    void resetForNewIntent() {
        synchronized (lock) {
            pending.clear();
            inFlightSignatures.clear();
            primarySignatures.clear();
            coalescedRecipients.clear();
        }
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
        responses.put(response(primaryId, primaryName, modelResult));

        synchronized (lock) {
            String signature = primarySignatures.remove(primaryId);
            if (signature == null) return responses;

            ArrayList<ResponseRecipient> duplicates =
                    coalescedRecipients.remove(signature);
            if (duplicates == null) return responses;

            for (ResponseRecipient duplicate : duplicates) {
                responses.put(response(
                        duplicate.id,
                        duplicate.name,
                        modelResult));
            }
        }
        return responses;
    }

    int pendingCountForTest() {
        synchronized (lock) {
            return pending.size();
        }
    }

    int inFlightCountForTest() {
        synchronized (lock) {
            return inFlightSignatures.size();
        }
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
                        inFlightSignatures.remove(
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

    private static String incomingSignature(JSONObject call) {
        JSONObject args = call == null ? null : call.optJSONObject("args");
        String name = call == null
                ? "unknown"
                : call.optString("name", "unknown");
        return name + ":" + (args == null ? "{}" : args.toString());
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
