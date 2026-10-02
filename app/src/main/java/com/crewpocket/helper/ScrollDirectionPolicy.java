package com.crewpocket.helper;

import java.util.Locale;

/**
 * Converts user/model-facing content direction into physical finger movement.
 *
 * Semantic direction answers: "which content should become visible?"
 * Physical direction answers: "which way should the finger move?"
 */
final class ScrollDirectionPolicy {
    private ScrollDirectionPolicy() {}

    static String normalizeSemantic(String value) {
        String direction = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (direction.isEmpty()) return "forward";

        // Backward compatibility with older sessions that used physical-looking
        // vertical words. Preserve their previous meaning as semantic navigation.
        if ("up".equals(direction)) return "forward";
        if ("down".equals(direction)) return "backward";
        return direction;
    }

    /**
     * Deterministic interpretation for narrow, standalone human swipe phrases.
     *
     * The returned value is CONTENT direction, not finger direction:
     * "往右滑" means reveal content on the right -> semantic "right";
     * Runtime later converts it to a physical finger-left gesture.
     *
     * Returns empty for generic/compound/ambiguous text so the ordinary model
     * path can keep ownership.
     */
    static String explicitSemanticFromUserText(String rawText) {
        String text = normalizeUserSwipeText(rawText);
        if (text.isEmpty()) return "";

        if (equalsAny(
                text,
                "右滑", "右滑動", "右滑动",
                "往右滑", "往右滑動", "往右滑动",
                "向右滑", "向右滑動", "向右滑动",
                "滑向右", "滑往右",
                "swiperight", "slideright", "scrollright")) {
            return "right";
        }
        if (equalsAny(
                text,
                "左滑", "左滑動", "左滑动",
                "往左滑", "往左滑動", "往左滑动",
                "向左滑", "向左滑動", "向左滑动",
                "滑向左", "滑往左",
                "swipeleft", "slideleft", "scrollleft")) {
            return "left";
        }
        if (equalsAny(
                text,
                "下滑", "下滑動", "下滑动",
                "往下滑", "往下滑動", "往下滑动",
                "向下滑", "向下滑動", "向下滑动",
                "滑向下", "滑往下",
                "下一頁", "下一页", "下頁", "下页",
                "swipedown", "slidedown", "scrolldown",
                "nextpage")) {
            return "forward";
        }
        if (equalsAny(
                text,
                "上滑", "上滑動", "上滑动",
                "往上滑", "往上滑動", "往上滑动",
                "向上滑", "向上滑動", "向上滑动",
                "滑向上", "滑往上",
                "上一頁", "上一页", "上頁", "上页",
                "swipeup", "slideup", "scrollup",
                "previouspage")) {
            return "backward";
        }
        return "";
    }

    static boolean isSupported(String semanticDirection) {
        return "forward".equals(semanticDirection)
                || "backward".equals(semanticDirection)
                || "left".equals(semanticDirection)
                || "right".equals(semanticDirection);
    }

    static String toPhysical(String semanticDirection) {
        if ("forward".equals(semanticDirection)) return "up";
        if ("backward".equals(semanticDirection)) return "down";
        if ("right".equals(semanticDirection)) return "left";
        if ("left".equals(semanticDirection)) return "right";
        return semanticDirection == null ? "" : semanticDirection;
    }

    private static String normalizeUserSwipeText(String value) {
        String text = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        text = text.replaceAll(
                "[\\s，,。！？!「」『』\"'：:；;（）()_-]+",
                "");

        String[] prefixes = new String[]{
                "請幫我", "请帮我", "麻煩幫我", "麻烦帮我",
                "幫我", "帮我", "請", "请",
                "可以幫我", "可以帮我"
        };
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : prefixes) {
                if (text.startsWith(prefix)) {
                    text = text.substring(prefix.length());
                    changed = true;
                    break;
                }
            }
        }

        String[] suffixes = new String[]{
                "一下", "一下下", "就好", "即可", "吧", "好嗎", "好吗"
        };
        changed = true;
        while (changed) {
            changed = false;
            for (String suffix : suffixes) {
                if (text.endsWith(suffix)) {
                    text = text.substring(
                            0,
                            text.length() - suffix.length());
                    changed = true;
                    break;
                }
            }
        }
        return text;
    }

    private static boolean equalsAny(
            String value,
            String... options) {
        for (String option : options) {
            if (value.equals(option)) return true;
        }
        return false;
    }

    static String fromPhysical(String physicalDirection) {
        String direction = physicalDirection == null
                ? ""
                : physicalDirection.trim().toLowerCase(Locale.ROOT);
        if ("up".equals(direction)) return "forward";
        if ("down".equals(direction)) return "backward";
        if ("left".equals(direction)) return "right";
        if ("right".equals(direction)) return "left";
        return "";
    }
}
