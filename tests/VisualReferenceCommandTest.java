package com.crewpocket.helper;

public final class VisualReferenceCommandTest {
    public static void main(String[] args) {
        expect(true, VisualReferenceCommand.isOpenRequest("開方格"), "open grid zh");
        expect(true, VisualReferenceCommand.isOpenRequest("顯示方格"), "show grid zh");
        expect(true, VisualReferenceCommand.isOpenRequest("我來選位置"), "choose position zh");
        expect(true, VisualReferenceCommand.isOpenRequest("讓我指給你"), "point it out zh");
        expect(true, VisualReferenceCommand.isOpenRequest("visual_reference:open"), "model marker");
        expect(true, VisualReferenceCommand.isOpenRequest("Open grid"), "open grid en");

        expect(false, VisualReferenceCommand.isOpenRequest("顯示元素"), "element command must not trigger grid");
        expect(false, VisualReferenceCommand.isOpenRequest("搜尋大皇宮"), "search must not trigger");
        expect(false, VisualReferenceCommand.isOpenRequest("The Grand Palace"), "result label must not trigger");
        expect(false, VisualReferenceCommand.isOpenRequest("開始"), "start control must not trigger");
        expect(false, VisualReferenceCommand.isOpenRequest("關閉"), "close control must not trigger");
        expect(false, VisualReferenceCommand.isOpenRequest("右下角"), "position alone starts only after grid is active");
        expect(false, VisualReferenceCommand.isOpenRequest(""), "empty must not trigger");

        System.out.println("VisualReferenceCommandTest passed");
    }

    private static void expect(boolean expected, boolean actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
