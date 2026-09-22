package com.crewpocket.helper;

public final class ModelScreenPriorityPolicyTest {
    public static void main(String[] args) {
        expect(130, ModelScreenPriorityPolicy.actionControlPriority(
                "路線", ""));
        expect(130, ModelScreenPriorityPolicy.actionControlPriority(
                "開始", ""));
        expect(130, ModelScreenPriorityPolicy.actionControlPriority(
                "Directions", ""));
        expect(95, ModelScreenPriorityPolicy.actionControlPriority(
                "下一步", ""));
        expect(0, ModelScreenPriorityPolicy.actionControlPriority(
                "4.7 stars", ""));
        expect(0, ModelScreenPriorityPolicy.actionControlPriority(
                "Open now", ""));
        System.out.println("ModelScreenPriorityPolicyTest passed");
    }

    private static void expect(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(
                    "expected=" + expected + " actual=" + actual);
        }
    }
}
