package com.crewpocket.helper;

import java.util.Locale;

/**
 * Privacy-bounded terminal intent for the current finalized user goal.
 *
 * This is model guidance only. It never grants execution authority and never
 * contains the user's query, recipient, message text, or other free-form data.
 */
final class GoalIntentKey {
    private GoalIntentKey() {}

    static String derive(String text) {
        String value = normalize(text);
        if (value.isEmpty()) return "";

        if (containsAny(value,
                "暫停", "暂停", "pause", "stopplayback", "pausemusic")) {
            return "MEDIA:PAUSE";
        }
        if (containsAny(value,
                "下一首", "下一曲", "nexttrack", "skiptrack", "skip song")) {
            return "MEDIA:NEXT";
        }
        if (containsAny(value,
                "上一首", "上一曲", "previoustrack", "previous song", "prevtrack")) {
            return "MEDIA:PREVIOUS";
        }
        if (containsAny(value,
                "導航", "导航", "開始導航", "开始导航",
                "帶我去", "带我去", "路線", "路线",
                "navigate", "navigation", "directions", "route me")) {
            return "NAVIGATION:START";
        }
        if (containsAny(value,
                "播放", "播歌", "放音樂", "放音乐",
                "play music", "play song", "playback", " play ")) {
            return "MEDIA:PLAY";
        }
        if (containsAny(value,
                "送出", "發送", "发送", "傳訊息", "传消息",
                "send message", "send it", "send this")) {
            return "MESSAGE:SEND";
        }
        if (containsAny(value,
                "只輸入", "只输入", "只打字", "填入", "輸入",
                "type only", "just type", "fill in")) {
            return "TEXT:TYPE_ONLY";
        }
        if (containsAny(value,
                "搜尋", "搜索", "查找", "找一下", "search", "find ")) {
            return "SEARCH:RESULT";
        }
        if (containsAny(value,
                "打開", "打开", "開啟", "开启", "open app", "open ")) {
            return "APP:OPEN";
        }
        return "";
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return (" " + text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。！？!「」『』\\\"'：:；;（）()_-]+", " ")
                .trim() + " ");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty()
                    && value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
