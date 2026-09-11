package com.crewpocket.helper;
import android.view.accessibility.AccessibilityNodeInfo;
final class SensitiveDataGuard {
    static boolean isSensitiveNode(AccessibilityNodeInfo node) { return false; }
}
