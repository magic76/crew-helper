package com.crewpocket.helper;

import java.util.Locale;

/**
 * Marks a narrow, explicit one-step swipe instruction as terminal after one
 * successful Runtime swipe.
 *
 * This intentionally does not apply to compound goals such as "swipe right and
 * open settings" or repeated-count instructions. Those remain model-owned.
 */
final class DirectGestureCompletionPolicy {
    private DirectGestureCompletionPolicy() {}

    static boolean shouldFinish(
            String latestUserTurn,
            String rawSemanticDirection,
            boolean success) {
        if (!success) return false;

        String direction = normalizeSemanticDirection(
                rawSemanticDirection);
        if (direction.isEmpty()) return false;

        String command = normalizeCommand(latestUserTurn);
        if (command.isEmpty()) return false;

        if (containsCompoundIntent(command)) return false;
        if (containsRepeatCount(command)) return false;

        return matchesDirection(command, direction);
    }

    private static boolean matchesDirection(String command, String direction) {
        if ("right".equals(direction)) {
            return matchesChinese(command, "右")
                    || command.equals("swiperight")
                    || command.equals("slideright")
                    || command.equals("scrollright");
        }
        if ("left".equals(direction)) {
            return matchesChinese(command, "左")
                    || command.equals("swipeleft")
                    || command.equals("slideleft")
                    || command.equals("scrollleft");
        }
        if ("forward".equals(direction)) {
            return matchesChinese(command, "下")
                    || command.equals("下一頁")
                    || command.equals("下一页")
                    || command.equals("下頁")
                    || command.equals("下页")
                    || command.equals("swipedown")
                    || command.equals("slidedown")
                    || command.equals("scrolldown")
                    || command.equals("nextpage");
        }
        if ("backward".equals(direction)) {
            return matchesChinese(command, "上")
                    || command.equals("上一頁")
                    || command.equals("上一页")
                    || command.equals("上頁")
                    || command.equals("上页")
                    || command.equals("swipeup")
                    || command.equals("slideup")
                    || command.equals("scrollup")
                    || command.equals("previouspage");
        }
        return false;
    }

    private static boolean matchesChinese(String command, String dir) {
        return command.equals(dir + "滑")
                || command.equals(dir + "滑動")
                || command.equals(dir + "滑动")
                || command.equals("往" + dir + "滑")
                || command.equals("往" + dir + "滑動")
                || command.equals("往" + dir + "滑动")
                || command.equals("向" + dir + "滑")
                || command.equals("向" + dir + "滑動")
                || command.equals("向" + dir + "滑动")
                || command.equals("滑向" + dir)
                || command.equals("滑往" + dir);
    }

    private static boolean containsCompoundIntent(String command) {
        return containsAny(
                command,
                "然後", "然后", "接著", "接着", "再", "並且", "并且",
                "之後", "之后", "找到", "尋找", "寻找", "打開", "打开",
                "點", "点", "選", "选", "播放", "導航", "导航", "搜尋", "搜索",
                "and", "then", "after", "find", "open", "tap", "play", "navigate", "search");
    }

    private static boolean containsRepeatCount(String command) {
        return command.matches(".*[2-9二三四五六七八九十兩两].*")
                || containsAny(
                        command,
                        "兩次", "两次", "幾次", "几次", "多次",
                        "twice", "times");
    }

    private static String normalizeCommand(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        text = text.replaceAll("[\\s，,。！？!「」『』\"'：:；;（）()_-]+", "");

        // Strip only harmless polite wrappers. Do not remove semantic verbs.
        String[] prefixes = new String[]{
                "請幫我", "请帮我", "麻煩幫我", "麻烦帮我",
                "幫我", "帮我", "請", "请", "可以幫我", "可以帮我"
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
                    text = text.substring(0, text.length() - suffix.length());
                    changed = true;
                    break;
                }
            }
        }
        return text;
    }

    private static String normalizeSemanticDirection(String value) {
        String text = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        if ("forward".equals(text)) return "forward";
        if ("backward".equals(text)) return "backward";
        if ("right".equals(text) || "右".equals(text)) return "right";
        if ("left".equals(text) || "左".equals(text)) return "left";
        // Backward-compatible physical directions from older Runtime paths.
        if ("up".equals(text) || "上".equals(text)) return "forward";
        if ("down".equals(text) || "下".equals(text)) return "backward";
        return "";
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }
}
