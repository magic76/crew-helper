package com.crewpocket.helper;

public final class ElementReferenceChoiceTest {
    public static void main(String[] args) {
        expect(0, ElementReferenceChoice.parseIndex("1", 24), "digit one");
        expect(4, ElementReferenceChoice.parseIndex("5號", 24), "digit suffix");
        expect(2, ElementReferenceChoice.parseIndex("第三個", 24), "chinese ordinal");
        expect(11, ElementReferenceChoice.parseIndex("十二", 24), "chinese twelve");
        expect(1, ElementReferenceChoice.parseIndex("second", 24), "english ordinal");
        expect(1, ElementReferenceChoice.parseIndex("幫我點第二個", 24), "natural Chinese spoken choice");
        expect(4, ElementReferenceChoice.parseIndex("請點擊第5號", 24), "polite choose number");
        expect(9, ElementReferenceChoice.parseIndex("按第十個", 24), "number ten");
        expect(2, ElementReferenceChoice.parseIndex("選擇第三首", 24), "song list overlay choice");
        expect(-1, ElementReferenceChoice.parseIndex("點擊第0個", 24), "overlay starts at one");
        expect(-1, ElementReferenceChoice.parseIndex("搜尋第二個結果", 24), "unrelated command not consumed");
        expect(-1, ElementReferenceChoice.parseIndex("點擊播放", 24), "semantic play command not an ordinal");
        expect(-1, ElementReferenceChoice.parseIndex("右下角", 24), "relative position is grid only");
        expect(-1, ElementReferenceChoice.parseIndex("25", 24), "out of range");
        expect(-1, ElementReferenceChoice.parseIndex("", 24), "empty");

        expectTrue(ElementReferenceChoice.looksLikeChoice("5"), "digit looks like choice");
        expectTrue(ElementReferenceChoice.looksLikeChoice("第五個"), "ordinal looks like choice");
        expectTrue(ElementReferenceChoice.looksLikeChoice("請點擊第5號"), "natural voice is an overlay choice");
        expectFalse(ElementReferenceChoice.looksLikeChoice("搜尋第二個結果"), "do not hijack search command");
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
