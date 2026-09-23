package com.crewpocket.helper;

public final class ElementReferenceCommandTest {
    public static void main(String[] args) {
        expect(true, ElementReferenceCommand.isOpenRequest("顯示元素"), "show elements zh");
        expect(true, ElementReferenceCommand.isOpenRequest("顯示可點擊元素"), "show clickable zh");
        expect(true, ElementReferenceCommand.isOpenRequest("把可點的標出來"), "mark clickable zh");
        expect(true, ElementReferenceCommand.isOpenRequest("element_reference:open"), "model marker");
        expect(true, ElementReferenceCommand.isOpenRequest("visual_reference:open"), "legacy model marker");
        expect(false, ElementReferenceCommand.isUserOpenRequest("element_reference:open"), "model marker is never user authorization");
        expect(false, ElementReferenceCommand.isUserOpenRequest("visual_reference:open"), "legacy marker is never user authorization");
        expect(true, ElementReferenceCommand.isUserOpenRequest("顯示元素"), "user phrase grants overlay authorization");
        expect(true, ElementReferenceCommand.isOpenRequest("Show clickable elements"), "show elements en");

        expect(false, ElementReferenceCommand.isOpenRequest("顯示方格"), "grid command must not trigger elements");
        expect(false, ElementReferenceCommand.isOpenRequest("The Grand Palace"), "label must not trigger");
        expect(false, ElementReferenceCommand.isOpenRequest("開始"), "control must not trigger");
        expect(false, ElementReferenceCommand.isOpenRequest("5"), "choice alone must not trigger");
        expect(false, ElementReferenceCommand.isOpenRequest(""), "empty must not trigger");

        System.out.println("ElementReferenceCommandTest passed");
    }

    private static void expect(boolean expected, boolean actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
