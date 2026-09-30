package com.crewpocket.helper;

import java.util.Locale;

/**
 * Short Runtime-owned lease for direct directional exploration.
 *
 * The first ordinary one-shot scroll is still model-selected. Once that scroll
 * succeeds, nearby low-information follow-ups such as "down", "continue" or
 * "a little more" can execute directly without creating an Agent task or
 * spending another Gemini tool round-trip.
 */
final class ExploreGestureLease {
    static final long TTL_MS = 15_000L;
    static final long EXPLICIT_IDLE_TTL_MS = 90_000L;

    enum Kind {
        NONE,
        START,
        STOP,
        SCROLL,
        PASS_TO_MODEL
    }

    static final class Command {
        final Kind kind;
        final String semanticDirection;
        final String distance;

        Command(Kind kind, String semanticDirection, String distance) {
            this.kind = kind == null ? Kind.NONE : kind;
            this.semanticDirection = safe(semanticDirection);
            this.distance = safe(distance);
        }

        static Command none() {
            return new Command(Kind.NONE, "", "");
        }
    }

    private long activeUntilMs;
    private boolean explicitMode;
    private String lastSemanticDirection = "";

    synchronized Command previewActive(String rawText, long nowMs) {
        if (!isActive(nowMs)) return Command.none();
        String text = normalize(rawText);
        if (text.isEmpty()) return Command.none();
        if (isInspectHandoff(text)) {
            return new Command(Kind.PASS_TO_MODEL, "", "");
        }
        return parseScroll(text);
    }

    synchronized void acceptPreviewed(
            Command command,
            long nowMs) {
        if (command == null || command.kind != Kind.SCROLL) return;
        String direction = normalizeSemanticDirection(
                command.semanticDirection);
        if (!ScrollDirectionPolicy.isSupported(direction)) return;
        explicitMode = false;
        lastSemanticDirection = direction;
        touch(nowMs);
    }

    synchronized Command interpret(String rawText, long nowMs) {
        String text = normalize(rawText);
        if (text.isEmpty()) return Command.none();

        if (isStop(text)) {
            clear();
            return new Command(Kind.STOP, "", "");
        }

        if (isStart(text)) {
            explicitMode = true;
            activeUntilMs = Math.max(0L, nowMs)
                    + EXPLICIT_IDLE_TTL_MS;
            return new Command(Kind.START, lastSemanticDirection, "");
        }

        if (!isActive(nowMs)) {
            return Command.none();
        }

        if (isInspectHandoff(text)) {
            touch(nowMs);
            return new Command(Kind.PASS_TO_MODEL, "", "");
        }

        Command scroll = parseScroll(text);
        if (scroll.kind == Kind.SCROLL) {
            lastSemanticDirection = scroll.semanticDirection;
            touch(nowMs);
            return scroll;
        }

        // A non-exploration instruction ends the lease immediately so normal
        // Agent intent handling remains authoritative.
        clear();
        return Command.none();
    }

    synchronized void armFromSuccessfulGesture(
            String rawSemanticDirection,
            long nowMs) {
        String direction = normalizeSemanticDirection(rawSemanticDirection);
        if (!ScrollDirectionPolicy.isSupported(direction)) return;
        lastSemanticDirection = direction;
        touch(nowMs);
    }

    synchronized boolean isActive(long nowMs) {
        if (activeUntilMs <= 0L) return false;
        if (nowMs > activeUntilMs) {
            clear();
            return false;
        }
        return true;
    }

    synchronized boolean isExplicitMode(long nowMs) {
        return isActive(nowMs) && explicitMode;
    }

    synchronized String lastSemanticDirection() {
        return lastSemanticDirection;
    }

    synchronized long remainingMs(long nowMs) {
        return isActive(nowMs)
                ? Math.max(0L, activeUntilMs - nowMs)
                : 0L;
    }

    synchronized void clear() {
        activeUntilMs = 0L;
        explicitMode = false;
        lastSemanticDirection = "";
    }

    private void touch(long nowMs) {
        long ttl = explicitMode
                ? EXPLICIT_IDLE_TTL_MS
                : TTL_MS;
        activeUntilMs = Math.max(0L, nowMs) + ttl;
    }

    private Command parseScroll(String text) {
        if (equalsAny(text,
                "繼續", "继续", "再來", "再来", "再滑", "再一下",
                "continue", "again")) {
            return lastSemanticDirection.isEmpty()
                    ? Command.none()
                    : new Command(
                            Kind.SCROLL,
                            lastSemanticDirection,
                            "normal");
        }

        if (equalsAny(text,
                "再一點", "再一点", "一點", "一点", "一點點", "一点点",
                "再一小段", "alittlemore")) {
            return lastSemanticDirection.isEmpty()
                    ? Command.none()
                    : new Command(
                            Kind.SCROLL,
                            lastSemanticDirection,
                            "short");
        }

        if (equalsAny(text,
                "下一頁", "下一页", "下頁", "下页",
                "往下一頁", "往下一页", "nextpage")) {
            return new Command(Kind.SCROLL, "forward", "page");
        }
        if (equalsAny(text,
                "上一頁", "上一页", "上頁", "上页",
                "往上一頁", "往上一页", "previouspage")) {
            return new Command(Kind.SCROLL, "backward", "page");
        }

        String directionalText =
                text.startsWith("再") && text.length() > 1
                        ? text.substring(1)
                        : text;
        String distance = hasShortModifier(directionalText)
                ? "short"
                : "normal";
        String base = stripShortModifier(directionalText);

        if (equalsAny(base,
                "下", "往下", "向下", "下滑", "往下滑", "向下滑",
                "滑下", "滑下去", "scrolldown", "swipedown", "down")) {
            return new Command(Kind.SCROLL, "forward", distance);
        }
        if (equalsAny(base,
                "上", "往上", "向上", "上滑", "往上滑", "向上滑",
                "滑上", "滑上去", "scrollup", "swipeup", "up")) {
            return new Command(Kind.SCROLL, "backward", distance);
        }
        if (equalsAny(base,
                "右", "往右", "向右", "右滑", "往右滑", "向右滑",
                "滑右", "swiperight", "scrollright", "right")) {
            return new Command(Kind.SCROLL, "right", distance);
        }
        if (equalsAny(base,
                "左", "往左", "向左", "左滑", "往左滑", "向左滑",
                "滑左", "swipeleft", "scrollleft", "left")) {
            return new Command(Kind.SCROLL, "left", distance);
        }

        return Command.none();
    }

    private static boolean isStart(String text) {
        return equalsAny(
                text,
                "探索模式", "進入探索模式", "进入探索模式",
                "開始探索", "开始探索", "探索一下", "幫我探索", "帮我探索",
                "exploremode", "startexploring");
    }

    private static boolean isStop(String text) {
        return equalsAny(
                text,
                "結束探索", "结束探索", "退出探索", "停止探索",
                "關閉探索模式", "关闭探索模式",
                "stopexploring", "exitexploremode");
    }

    private static boolean isInspectHandoff(String text) {
        return equalsAny(
                text,
                "看一下", "看看", "幫我看看", "帮我看看",
                "這裡有什麼", "这里有什么",
                "這是什麼", "这是什么",
                "whatshere", "lookatthis");
    }

    private static boolean hasShortModifier(String text) {
        return text.endsWith("一點")
                || text.endsWith("一点")
                || text.endsWith("一點點")
                || text.endsWith("一点点")
                || text.endsWith("一下")
                || text.endsWith("一小段");
    }

    private static String stripShortModifier(String text) {
        String value = text;
        String[] suffixes = new String[]{
                "一點點", "一点点", "一小段", "一點", "一点", "一下"
        };
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return value.substring(
                        0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private static String normalizeSemanticDirection(String value) {
        String direction = safe(value).toLowerCase(Locale.ROOT);
        if ("forward".equals(direction)
                || "backward".equals(direction)
                || "left".equals(direction)
                || "right".equals(direction)) {
            return direction;
        }
        return "";
    }

    private static boolean equalsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.equals(normalize(candidate))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        String text = safe(value).toLowerCase(Locale.ROOT);
        text = text.replaceAll(
                "[\\s，,。！？!「」『』\\\"'：:；;（）()_-]+",
                "");

        String[] prefixes = new String[]{
                "請幫我", "请帮我", "麻煩幫我", "麻烦帮我",
                "幫我", "帮我", "請", "请"
        };
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : prefixes) {
                String normalized = normalizeLiteral(prefix);
                if (!normalized.isEmpty() && text.startsWith(normalized)) {
                    text = text.substring(normalized.length());
                    changed = true;
                    break;
                }
            }
        }
        return text;
    }

    private static String normalizeLiteral(String value) {
        return safe(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll(
                        "[\\s，,。！？!「」『』\\\"'：:；;（）()_-]+",
                        "");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
