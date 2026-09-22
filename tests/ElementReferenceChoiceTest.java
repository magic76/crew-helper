package com.crewpocket.helper;

public final class ElementReferenceChoiceTest {
    public static void main(String[] args) {
        expect(0, ElementReferenceChoice.parseIndex("1", 24), "digit one");
        expect(4, ElementReferenceChoice.parseIndex("5號", 24), "digit suffix");
        expect(2, ElementReferenceChoice.parseIndex("第三個", 24), "chinese ordinal");
        expect(11, ElementReferenceChoice.parseIndex("十二", 24), "chinese twelve");
        expect(1, ElementReferenceChoice.parseIndex("second", 24), "english ordinal");
        expect(-1, ElementReferenceChoice.parseIndex("右下角", 24), "relative position is grid only");
        expect(-1, ElementReferenceChoice.parseIndex("25", 24), "out of range");
        expect(-1, ElementReferenceChoice.parseIndex("", 24), "empty");

        expectTrue(ElementReferenceChoice.looksLikeChoice("5"), "digit looks like choice");
        expectTrue(ElementReferenceChoice.looksLikeChoice("第五個"), "ordinal looks like choice");
        expectTrue(ElementReferenceChoice.looksLikeChoice("25"), "out-of-range digit is still a choice attempt");
        expectFalse(ElementReferenceChoice.looksLikeChoice("開始"), "semantic action must exit choice mode");
        expectFalse(ElementReferenceChoice.looksLikeChoice("路線"), "navigation action must exit choice mode");
        expectFalse(ElementReferenceChoice.looksLikeChoice("返回"), "back command must exit choice mode");

        System.out.println("ElementReferenceChoiceTest passed");
    }

    private static void expectTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectFalse(boolean value, String message) {
        if (value) throw new AssertionError(message);
    }

    private static void expect(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
