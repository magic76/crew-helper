package com.crewpocket.helper;

/** Privacy-conscious structural snapshot used by the pure locator scorer. */
final class UiNodeSnapshot {
    final String instanceId;
    final String actionKey;
    final String elementId;
    final String text;
    final String contentDescription;
    final String label;
    final String viewId;
    final String className;
    final String role;
    final String semanticHint;
    final String parentRole;
    final boolean clickable;
    final boolean editable;
    final boolean enabled;
    final boolean visible;
    final boolean sensitive;
    final int depth;
    final int siblingIndex;
    final int left;
    final int top;
    final int right;
    final int bottom;
    final int screenWidth;
    final int screenHeight;

    UiNodeSnapshot(String instanceId,
                   String actionKey,
                   String elementId,
                   String text,
                   String contentDescription,
                   String label,
                   String viewId,
                   String className,
                   String role,
                   String semanticHint,
                   String parentRole,
                   boolean clickable,
                   boolean editable,
                   boolean enabled,
                   boolean visible,
                   boolean sensitive,
                   int depth,
                   int siblingIndex,
                   int left,
                   int top,
                   int right,
                   int bottom,
                   int screenWidth,
                   int screenHeight) {
        this.instanceId = safe(instanceId);
        this.actionKey = safe(actionKey);
        this.elementId = safe(elementId);
        this.text = safe(text);
        this.contentDescription = safe(contentDescription);
        this.label = safe(label);
        this.viewId = safe(viewId);
        this.className = safe(className);
        this.role = safe(role);
        this.semanticHint = safe(semanticHint);
        this.parentRole = safe(parentRole);
        this.clickable = clickable;
        this.editable = editable;
        this.enabled = enabled;
        this.visible = visible;
        this.sensitive = sensitive;
        this.depth = Math.max(0, depth);
        this.siblingIndex = siblingIndex;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.screenWidth = Math.max(1, screenWidth);
        this.screenHeight = Math.max(1, screenHeight);
    }

    int centerX() { return left + Math.max(0, right - left) / 2; }
    int centerY() { return top + Math.max(0, bottom - top) / 2; }
    int width() { return Math.max(0, right - left); }
    int height() { return Math.max(0, bottom - top); }

    private static String safe(String value) { return value == null ? "" : value; }
}

