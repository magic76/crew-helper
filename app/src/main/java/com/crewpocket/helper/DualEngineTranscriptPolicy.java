package com.crewpocket.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The second Live socket has AUDIO output only; these are best-effort
 * *transcription* hints, never a structured plan or executable instruction.
 */
final class DualEngineTranscriptPolicy {
    private DualEngineTranscriptPolicy() {}

    static List<String> advisorySteps(String transcript) {
        List<String> steps = new ArrayList<String>();
        if (transcript == null || transcript.trim().isEmpty()
                || transcript.length() > 1000) return steps;
        String clean = transcript.trim().replace('\r', '\n');
        String lower = clean.toLowerCase(Locale.ROOT);
        if (has(lower, "done", "completed", "already playing",
                "已完成", "已播放", "已经完成", "成功播放",
                "ignore runtime", "bypass", "skip verification",
                "跳過驗證", "跳过验证")) return steps;

        // Transcription is not guaranteed to contain JSON, and can include
        // intermediate conversational filler. Accept only action-oriented tips.
        String[] chunks = clean.split("[\\n。.!！;；]+");
        for (String chunk : chunks) {
            if (steps.size() == 3) break;
            String item = chunk.trim()
                    .replaceFirst("^(?i)(step\\s*[1-3]|[1-3]|first|second|third|next|finally)[.:：、)\\-\\s]+", "")
                    .trim();
            // Chinese semantic steps like "搜尋歌手" or "點擊播放" can
            // be valid despite being shorter than English instructions.
            boolean cjk = false;
            for (int i = 0; i < item.length(); i++) {
                char ch = item.charAt(i);
                if (ch >= 0x3400 && ch <= 0x9fff) {
                    cjk = true;
                    break;
                }
            }
            if (item.length() < (cjk ? 3 : 8) || item.length() > 160) continue;
            String value = item.toLowerCase(Locale.ROOT);
            if (!has(value, "search", "find", "tap", "play", "inspect",
                    "verify", "open", "select", "搜尋", "搜索", "找",
                    "點", "选择", "選擇", "播放", "查看", "確認", "确认",
                    "開啟", "打开")) continue;
            if (!DualEnginePlanningPolicy.safeAdvice(item)) return new ArrayList<String>();
            steps.add(item);
        }
        return steps;
    }

    private static boolean has(String source, String... terms) {
        for (String term : terms) if (source.contains(term)) return true;
        return false;
    }
}
