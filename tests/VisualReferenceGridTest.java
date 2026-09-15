package com.crewpocket.helper;

import java.util.List;

public final class VisualReferenceGridTest {
    private static int checks;

    public static void main(String[] args) {
        List<VisualReferenceGrid.Cell> primary = VisualReferenceGrid.primary(1080, 2400);
        check(primary.size() == 12, "primary grid should expose 12 shared regions");

        VisualReferenceGrid.Cell bottomRight = primary.get(11);
        check(bottomRight.left == 720 && bottomRight.top == 1800,
                "bottom-right primary cell should start at expected quarter/third boundary");
        check(bottomRight.right == 1080 && bottomRight.bottom == 2400,
                "bottom-right primary cell should reach screen edge");
        check(VisualReferenceGrid.resolve(primary, "12") == bottomRight,
                "numeric choice should resolve primary cell");
        check(VisualReferenceGrid.resolve(primary, "第十二個") == bottomRight,
                "Chinese ordinal should resolve primary cell");
        check(VisualReferenceGrid.resolve(primary, "右下角那個") == bottomRight,
                "relative position phrase should resolve primary cell");

        VisualReferenceGrid.Cell rightLowerHalf = primary.get(8);
        check(VisualReferenceGrid.resolve(primary, "右側下半") == rightLowerHalf,
                "specific relative phrase should resolve middle-lower primary cell");

        List<VisualReferenceGrid.Cell> refined = VisualReferenceGrid.refine(bottomRight);
        check(refined.size() == 9, "refinement should expose 9 smaller regions");
        for (VisualReferenceGrid.Cell cell : refined) {
            check(cell.left >= bottomRight.left && cell.right <= bottomRight.right
                            && cell.top >= bottomRight.top && cell.bottom <= bottomRight.bottom,
                    "every refined cell must stay inside chosen primary region");
        }

        VisualReferenceGrid.Cell center = refined.get(4);
        check(VisualReferenceGrid.resolve(refined, "5號") == center,
                "second-stage number should resolve refined center");
        check(VisualReferenceGrid.resolve(refined, "中間") == center,
                "second-stage relative phrase should resolve refined center");
        check(center.centerX() == 900 && center.centerY() == 2100,
                "refined center should map to deterministic screen coordinate");

        System.out.println("VisualReferenceGridTest passed " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
