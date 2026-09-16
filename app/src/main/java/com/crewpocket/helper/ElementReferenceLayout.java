package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Pure layout policy for choosing distinct actionable element bounds. */
final class ElementReferenceLayout {
    static final class Item {
        final String id;
        final String label;
        final int left;
        final int top;
        final int right;
        final int bottom;
        final int depth;

        Item(String id, String label, int left, int top, int right, int bottom, int depth) {
            this.id = id == null ? "" : id;
            this.label = label == null ? "" : label.trim();
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.depth = depth;
        }

        int width() { return Math.max(0, right - left); }
        int height() { return Math.max(0, bottom - top); }
        long area() { return (long) width() * (long) height(); }
        int centerX() { return left + width() / 2; }
        int centerY() { return top + height() / 2; }
        boolean valid() { return !id.isEmpty() && width() > 2 && height() > 2; }
    }

    private ElementReferenceLayout() {}

    static List<Item> select(List<Item> raw, int screenWidth, int screenHeight, int maxItems) {
        ArrayList<Item> valid = new ArrayList<Item>();
        if (raw != null) {
            for (Item item : raw) {
                if (item == null || !item.valid()) continue;
                if (item.right <= 0 || item.bottom <= 0
                        || item.left >= screenWidth || item.top >= screenHeight) continue;
                valid.add(item);
            }
        }
        if (valid.isEmpty()) return valid;

        final long screenArea = Math.max(1L, (long) screenWidth * (long) screenHeight);
        ArrayList<Item> priority = new ArrayList<Item>(valid);
        Collections.sort(priority, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                boolean aScreenLike = a.area() * 100L >= screenArea * 88L;
                boolean bScreenLike = b.area() * 100L >= screenArea * 88L;
                if (aScreenLike != bScreenLike) return aScreenLike ? 1 : -1;
                boolean aLabeled = !a.label.isEmpty();
                boolean bLabeled = !b.label.isEmpty();
                if (aLabeled != bLabeled) return aLabeled ? -1 : 1;
                int area = Long.compare(a.area(), b.area());
                if (area != 0) return area;
                return Integer.compare(b.depth, a.depth);
            }
        });

        ArrayList<Item> distinct = new ArrayList<Item>();
        for (Item item : priority) {
            boolean duplicate = false;
            for (Item kept : distinct) {
                if (nearDuplicate(item, kept)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) distinct.add(item);
        }

        if (distinct.size() > 1) {
            for (int i = distinct.size() - 1; i >= 0; i--) {
                Item item = distinct.get(i);
                if (item.area() * 100L >= screenArea * 88L) distinct.remove(i);
            }
        }

        if (maxItems > 0 && distinct.size() > maxItems) {
            distinct = new ArrayList<Item>(distinct.subList(0, maxItems));
        }

        Collections.sort(distinct, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                int row = Integer.compare(a.centerY(), b.centerY());
                if (Math.abs(a.centerY() - b.centerY()) > 28 && row != 0) return row;
                int col = Integer.compare(a.centerX(), b.centerX());
                if (col != 0) return col;
                return Long.compare(a.area(), b.area());
            }
        });
        return distinct;
    }

    private static boolean nearDuplicate(Item a, Item b) {
        int left = Math.max(a.left, b.left);
        int top = Math.max(a.top, b.top);
        int right = Math.min(a.right, b.right);
        int bottom = Math.min(a.bottom, b.bottom);
        long intersection = (long) Math.max(0, right - left) * Math.max(0, bottom - top);
        long union = a.area() + b.area() - intersection;
        if (union > 0 && intersection * 100L >= union * 86L) return true;

        return Math.abs(a.left - b.left) <= 3
                && Math.abs(a.top - b.top) <= 3
                && Math.abs(a.right - b.right) <= 3
                && Math.abs(a.bottom - b.bottom) <= 3;
    }
}
