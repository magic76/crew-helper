package com.crewpocket.helper;
import android.view.accessibility.AccessibilityNodeInfo;
final class SemanticScreenState {
    static String elementId(AccessibilityNodeInfo node, int siblingIndex, int depth) {
        return "e_" + depth + "_" + siblingIndex;
    }
}
