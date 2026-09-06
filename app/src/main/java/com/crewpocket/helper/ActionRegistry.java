package com.crewpocket.helper;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Compiles an Accessibility tree into a small semantic action map.
 * This keeps device capability knowledge in Crew Helper rather than forcing
 * the planner to infer every possible action from raw nodes.
 */
final class ActionRegistry {
    private static final int MAX_ACTIONS = 80;

    private ActionRegistry() {}

    static JSONArray build(AccessibilityNodeInfo root) {
        JSONArray actions = new JSONArray();
        collect(root, actions);
        appendComposerSendAction(root, actions);
        return actions;
    }

    private static void appendComposerSendAction(AccessibilityNodeInfo root, JSONArray actions) {
        AccessibilityNodeInfo send = ComposerSendResolver.find(root);
        if (send == null) return;
        try {
            Rect b = new Rect();
            send.getBoundsInScreen(b);
            String id = send.getViewIdResourceName() == null ? "" : send.getViewIdResourceName().toString();
            JSONObject bounds = new JSONObject();
            bounds.put("left", b.left).put("top", b.top).put("right", b.right).put("bottom", b.bottom);
            JSONObject action = new JSONObject()
                    .put("type", "CLICK")
                    .put("label", "send")
                    .put("id", id)
                    .put("role", "COMPOSER_SEND")
                    .put("confidence", ComposerSendResolver.hasSendMarker(send) ? "HIGH" : "MEDIUM")
                    .put("bounds", bounds);
            actions.put(action);
        } catch (Exception ignored) {
        } finally {
            send.recycle();
        }
    }

    private static void collect(AccessibilityNodeInfo node, JSONArray actions) {
        if (node == null || actions.length() >= MAX_ACTIONS) return;
        try {
            boolean sensitive = SensitiveDataGuard.isSensitiveNode(node);
            String text = node.getText() == null ? "" : node.getText().toString().trim();
            String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
            String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toString().trim();
            String label = !text.isEmpty() ? text : desc;
            if (sensitive) label = SensitiveDataGuard.REDACTED;

            if (node.isClickable()) {
                actions.put(action("CLICK", label, id, node, sensitive));
            }
            if (node.isEditable()) {
                actions.put(action("TYPE", label, id, node, sensitive));
            }
            if (node.isScrollable()) {
                actions.put(action("SCROLL", label, id, node, sensitive));
            }
        } catch (Exception ignored) {}

        int count = node.getChildCount();
        for (int i = 0; i < count && actions.length() < MAX_ACTIONS; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collect(child, actions);
            } finally {
                child.recycle();
            }
        }
    }

    private static JSONObject action(String type, String label, String id,
                                     AccessibilityNodeInfo node, boolean sensitive) {
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        JSONObject bounds = new JSONObject();
        JSONObject result = new JSONObject();
        try {
            bounds.put("left", b.left).put("top", b.top).put("right", b.right).put("bottom", b.bottom);
            result.put("type", type)
                    .put("label", label == null ? "" : label)
                    .put("id", id == null ? "" : id)
                    .put("sensitive", sensitive)
                    .put("bounds", bounds);
        } catch (Exception ignored) {}
        return result;
    }
}
