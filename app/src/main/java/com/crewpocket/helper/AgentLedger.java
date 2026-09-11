package com.crewpocket.helper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded in-memory append-only ledger.
 *
 * No persistence in v1: we intentionally avoid writing user/task contents to disk.
 */
final class AgentLedger {
    private static final int DEFAULT_CAPACITY = 256;

    interface Listener {
        void onEvent(AgentEvent event, AgentState state);
    }

    private final int capacity;
    private final ArrayDeque<AgentEvent> events = new ArrayDeque<AgentEvent>();
    private long nextSequence = 1L;
    private Listener listener;

    AgentLedger() { this(DEFAULT_CAPACITY); }

    AgentLedger(int capacity) {
        this.capacity = Math.max(32, capacity);
    }

    synchronized void setListener(Listener value) {
        listener = value;
    }

    synchronized AgentEvent append(AgentEvent input) {
        if (input == null) throw new IllegalArgumentException("event required");
        AgentEvent event = input.withSequence(nextSequence++);
        events.addLast(event);
        while (events.size() > capacity) events.removeFirst();
        return event;
    }

    synchronized List<AgentEvent> snapshot() {
        return new ArrayList<AgentEvent>(events);
    }

    synchronized List<AgentEvent> recent(int limit) {
        int wanted = Math.max(0, limit);
        ArrayList<AgentEvent> all = new ArrayList<AgentEvent>(events);
        if (wanted == 0 || all.size() <= wanted) return all;
        return new ArrayList<AgentEvent>(all.subList(all.size() - wanted, all.size()));
    }

    synchronized AgentEvent last() {
        return events.peekLast();
    }

    synchronized boolean hasCommittedAction(String actionId, long generation) {
        if (actionId == null || actionId.isEmpty()) return false;
        for (AgentEvent event : events) {
            if (event.type == AgentEvent.Type.ACTION_COMMITTED
                    && event.generation == generation
                    && actionId.equals(event.actionId)) {
                return true;
            }
        }
        return false;
    }

    synchronized String dumpRecent(int limit) {
        StringBuilder out = new StringBuilder();
        List<AgentEvent> copy = recent(limit);
        for (AgentEvent event : copy) {
            if (out.length() > 0) out.append('\n');
            out.append(event.toString());
        }
        return out.toString();
    }

    void notifyListener(AgentEvent event, AgentState state) {
        Listener current;
        synchronized (this) { current = listener; }
        if (current != null) current.onEvent(event, state);
    }
}

