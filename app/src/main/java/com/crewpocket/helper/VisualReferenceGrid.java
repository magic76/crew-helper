package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure geometry for Shared Visual Reference.
 *
 * Stage 1 divides the whole screen into 12 large cells (3 x 4). Stage 2 divides
 * the chosen cell into 9 smaller cells (3 x 3). The same labels are used by the
 * on-screen overlay and voice resolver so the user and Runtime share one frame
 * of reference without exposing raw coordinates to the model.
 */
final class VisualReferenceGrid {
    static final int PRIMARY_ROWS = 4;
    static final int PRIMARY_COLS = 3;
    static final int REFINE_ROWS = 3;
    static final int REFINE_COLS = 3;

    static final class Cell {
        final int index;
        final int left;
        final int top;
        final int right;
        final int bottom;
        final String positionLabel;

        Cell(int index, int left, int top, int right, int bottom, String positionLabel) {
            this.index = index;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.positionLabel = positionLabel == null ? "" : positionLabel;
        }

        int centerX() { return left + Math.max(0, right - left) / 2; }
        int centerY() { return top + Math.max(0, bottom - top) / 2; }
        int width() { return Math.max(0, right - left); }
        int height() { return Math.max(0, bottom - top); }
        String displayLabel() { return index + " · " + positionLabel; }
    }

    private VisualReferenceGrid() {}

    static List<Cell> primary(int width, int height) {
        return build(0, 0, width, height, PRIMARY_ROWS, PRIMARY_COLS, true);
    }

    static List<Cell> refine(Cell parent) {
        if (parent == null) return Collections.emptyList();
        return build(parent.left, parent.top, parent.right, parent.bottom,
                REFINE_ROWS, REFINE_COLS, false);
    }

    static Cell resolve(List<Cell> cells, String spoken) {
        if (cells == null || cells.isEmpty()) return null;
        String folded = fold(spoken);
        if (folded.isEmpty()) return null;

        int ordinal = ordinal(folded);
        if (ordinal >= 1 && ordinal <= cells.size()) return cells.get(ordinal - 1);

        String normalizedPosition = normalizePosition(folded);
        if (normalizedPosition.isEmpty()) return null;
        for (Cell cell : cells) {
            String label = normalizePosition(fold(cell.positionLabel));
            if (!label.isEmpty() && (normalizedPosition.contains(label)
                    || label.contains(normalizedPosition))) {
                return cell;
            }
        }
        return null;
    }

    static List<String> displayLabels(List<Cell> cells) {
        ArrayList<String> out = new ArrayList<String>();
        if (cells == null) return out;
        for (Cell cell : cells) if (cell != null) out.add(cell.displayLabel());
        return out;
    }

    private static List<Cell> build(int left, int top, int right, int bottom,
                                    int rows, int cols, boolean primary) {
        if (right <= left || bottom <= top || rows <= 0 || cols <= 0) {
            return Collections.emptyList();
        }
        ArrayList<Cell> out = new ArrayList<Cell>();
        int width = right - left;
        int height = bottom - top;
        int index = 1;
        for (int row = 0; row < rows; row++) {
            int cellTop = top + (height * row) / rows;
            int cellBottom = top + (height * (row + 1)) / rows;
            for (int col = 0; col < cols; col++) {
                int cellLeft = left + (width * col) / cols;
                int cellRight = left + (width * (col + 1)) / cols;
                out.add(new Cell(index++, cellLeft, cellTop, cellRight, cellBottom,
                        primary ? primaryLabel(row, col) : refineLabel(row, col)));
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String primaryLabel(int row, int col) {
        if (row == 0) {
            if (col == 0) return "左上";
            if (col == 1) return "上方";
            return "右上";
        }
        if (row == 1) {
            if (col == 0) return "左側上半";
            if (col == 1) return "中央上半";
            return "右側上半";
        }
        if (row == 2) {
            if (col == 0) return "左側下半";
            if (col == 1) return "中央下半";
            return "右側下半";
        }
        if (col == 0) return "左下";
        if (col == 1) return "下方";
        return "右下";
    }

    private static String refineLabel(int row, int col) {
        if (row == 0 && col == 0) return "左上";
        if (row == 0 && col == 1) return "上方";
        if (row == 0) return "右上";
        if (row == 1 && col == 0) return "左側";
        if (row == 1 && col == 1) return "中央";
        if (row == 1) return "右側";
        if (col == 0) return "左下";
        if (col == 1) return "下方";
        return "右下";
    }

    private static int ordinal(String folded) {
        String digits = folded.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
            try { return Integer.parseInt(digits); }
            catch (Exception ignored) {}
        }

        if (containsAny(folded, "第十二", "十二號", "十二号", "twelfth", "twelve")) return 12;
        if (containsAny(folded, "第十一", "十一號", "十一号", "eleventh", "eleven")) return 11;
        if (containsAny(folded, "第十", "十號", "十号", "tenth", "ten")) return 10;
        if (containsAny(folded, "第九", "九號", "九号", "ninth", "nine")) return 9;
        if (containsAny(folded, "第八", "八號", "八号", "eighth", "eight")) return 8;
        if (containsAny(folded, "第七", "七號", "七号", "seventh", "seven")) return 7;
        if (containsAny(folded, "第六", "六號", "六号", "sixth", "six")) return 6;
        if (containsAny(folded, "第五", "五號", "五号", "fifth", "five")) return 5;
        if (containsAny(folded, "第四", "四號", "四号", "fourth", "four")) return 4;
        if (containsAny(folded, "第三", "三號", "三号", "third", "three")) return 3;
        if (containsAny(folded, "第二", "二號", "二号", "second", "two")) return 2;
        if (containsAny(folded, "第一", "一號", "一号", "first", "one")) return 1;
        return -1;
    }

    private static String normalizePosition(String value) {
        String s = value == null ? "" : value;
        s = s.replace("角落", "")
                .replace("角", "")
                .replace("那個", "")
                .replace("那个", "")
                .replace("這個", "")
                .replace("这个", "")
                .replace("位置", "")
                .replace("區域", "")
                .replace("区域", "")
                .replace("邊", "")
                .replace("边", "")
                .replace("中間", "中央")
                .replace("中间", "中央")
                .replace("右邊", "右側")
                .replace("右边", "右側")
                .replace("左邊", "左側")
                .replace("左边", "左側");
        return s;
    }

    private static boolean containsAny(String value, String... markers) {
        for (String marker : markers) if (value.contains(marker)) return true;
        return false;
    }

    private static String fold(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。.!！?？：:；;（）()\\-_/]", "")
                .trim();
    }
}
