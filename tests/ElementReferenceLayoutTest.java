package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.List;

public final class ElementReferenceLayoutTest {
    public static void main(String[] args) {
        dedupesNearlyIdenticalBounds();
        dropsScreenSizedContainerWhenRealControlsExist();
        keepsSpatialOrderAfterSelection();
        capsDenseScreens();
        System.out.println("ElementReferenceLayoutTest passed");
    }

    private static void dedupesNearlyIdenticalBounds() {
        ArrayList<ElementReferenceLayout.Item> raw = new ArrayList<ElementReferenceLayout.Item>();
        raw.add(item("parent", "", 10, 20, 110, 80, 2));
        raw.add(item("child", "Close", 11, 21, 109, 79, 5));
        List<ElementReferenceLayout.Item> out = ElementReferenceLayout.select(raw, 1080, 2400, 24);
        expect(1, out.size(), "near duplicate size");
        expect("child", out.get(0).id, "labeled/deeper candidate should win");
    }

    private static void dropsScreenSizedContainerWhenRealControlsExist() {
        ArrayList<ElementReferenceLayout.Item> raw = new ArrayList<ElementReferenceLayout.Item>();
        raw.add(item("root", "Map", 0, 0, 1080, 2350, 1));
        raw.add(item("close", "Close", 900, 2100, 1040, 2240, 6));
        List<ElementReferenceLayout.Item> out = ElementReferenceLayout.select(raw, 1080, 2400, 24);
        expect(1, out.size(), "screen container should be removed");
        expect("close", out.get(0).id, "real control remains");
    }

    private static void keepsSpatialOrderAfterSelection() {
        ArrayList<ElementReferenceLayout.Item> raw = new ArrayList<ElementReferenceLayout.Item>();
        raw.add(item("bottom", "Bottom", 500, 1800, 650, 1900, 3));
        raw.add(item("topRight", "Top right", 800, 100, 900, 180, 3));
        raw.add(item("topLeft", "Top left", 100, 110, 200, 190, 3));
        List<ElementReferenceLayout.Item> out = ElementReferenceLayout.select(raw, 1080, 2400, 24);
        expect("topLeft", out.get(0).id, "top-left first");
        expect("topRight", out.get(1).id, "top-right second");
        expect("bottom", out.get(2).id, "bottom last");
    }

    private static void capsDenseScreens() {
        ArrayList<ElementReferenceLayout.Item> raw = new ArrayList<ElementReferenceLayout.Item>();
        for (int i = 0; i < 40; i++) {
            int row = i / 5;
            int col = i % 5;
            raw.add(item("e" + i, "Item " + i,
                    col * 180, row * 180, col * 180 + 120, row * 180 + 100, 4));
        }
        List<ElementReferenceLayout.Item> out = ElementReferenceLayout.select(raw, 1080, 2400, 24);
        expect(24, out.size(), "dense screen cap");
    }

    private static ElementReferenceLayout.Item item(
            String id, String label, int left, int top, int right, int bottom, int depth) {
        return new ElementReferenceLayout.Item(id, label, left, top, right, bottom, depth);
    }

    private static void expect(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void expect(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
