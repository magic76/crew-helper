package com.crewpocket.helper;

public final class SwipeGeometryPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        SwipeGeometryPolicy.Fractions left =
                SwipeGeometryPolicy.forPhysicalDirection(
                        "left", "normal");
        checkNear(0.80f, left.x1, "normal left starts inside right safe zone");
        checkNear(0.20f, left.x2, "normal left ends inside left safe zone");
        checkNear(0.52f, left.y1, "horizontal swipe stays away from status bar");

        SwipeGeometryPolicy.Fractions right =
                SwipeGeometryPolicy.forPhysicalDirection(
                        "right", "long");
        checkNear(0.16f, right.x1, "long right avoids left back edge");
        checkNear(0.84f, right.x2, "long right avoids right system edge");

        SwipeGeometryPolicy.Fractions up =
                SwipeGeometryPolicy.forPhysicalDirection(
                        "up", "normal");
        checkNear(0.72f, up.y1, "normal up starts above nav edge");
        checkNear(0.30f, up.y2, "normal up ends below notification edge");

        SwipeGeometryPolicy.Fractions down =
                SwipeGeometryPolicy.forPhysicalDirection(
                        "down", "long");
        checkNear(0.24f, down.y1, "long down starts below notification edge");
        checkNear(0.78f, down.y2, "long down ends above nav edge");

        SwipeGeometryPolicy.Fractions shortUp =
                SwipeGeometryPolicy.forPhysicalDirection(
                        "up", "short");
        checkNear(0.60f, shortUp.y1, "short up start");
        checkNear(0.42f, shortUp.y2, "short up end");

        checkNear(0.80f,
                SwipeGeometryPolicy.horizontalHigh("normal"),
                "carousel normal high fraction");
        checkNear(0.20f,
                SwipeGeometryPolicy.horizontalLow("normal"),
                "carousel normal low fraction");
        checkNear(0.84f,
                SwipeGeometryPolicy.horizontalHigh("page"),
                "carousel long high fraction");

        System.out.println(
                "PASS SwipeGeometryPolicyTest: "
                        + checks + " checks");
    }

    private static void checkNear(
            float expected,
            float actual,
            String message) {
        checks++;
        if (Math.abs(expected - actual) > 0.0001f) {
            throw new AssertionError(
                    message + " expected=" + expected
                            + " actual=" + actual);
        }
    }
}
