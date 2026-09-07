package com.crewpocket.helper;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * 0028: preload persistent Memory Rules into Runtime RAM at Live-session start.
 *
 * Gemini never receives the full rule table. Only an exact matched rule is
 * injected into the current turn by NativeGeminiLiveClient.
 */
final class MemoryRuleIndex {
    private final MemoryRuleStore store;
    private volatile List<MemoryRuleStore.Rule> rules = new ArrayList<MemoryRuleStore.Rule>();

    MemoryRuleIndex(Context context) {
        store = new MemoryRuleStore(context.getApplicationContext());
        refresh();
    }

    synchronized void refresh() {
        rules = new ArrayList<MemoryRuleStore.Rule>(store.list());
    }

    int count() {
        List<MemoryRuleStore.Rule> snapshot = rules;
        return snapshot == null ? 0 : snapshot.size();
    }

    MemoryRuleStore.Rule findExact(String spokenText) {
        String input = normalize(spokenText);
        MemoryRuleStore.Rule best = null;
        List<MemoryRuleStore.Rule> snapshot = rules;
        if (snapshot == null) return null;

        for (MemoryRuleStore.Rule rule : snapshot) {
            if (rule == null || !rule.enabled) continue;
            String trigger = normalize(rule.trigger);
            if (trigger.length() < 2) continue;
            if (input.equals(trigger)
                    && (best == null || trigger.length() > normalize(best.trigger).length())) {
                best = rule;
            }
        }
        return best;
    }

    private static String normalize(String text) {
        return TextMatch.caseFold(text)
                .replaceAll("[\\s，,。！？!「」『』\\\"']", "");
    }
}
