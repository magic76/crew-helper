package com.crewpocket.helper;

import java.util.Locale;

/**
 * Structured user-facing Runtime state.
 *
 * UI surfaces render this object and never infer severity or phase from
 * localized text. fromLegacy()/fromLiveStatus() exist only as compatibility
 * adapters while older producers are migrated to typed state factories.
 */
final class RuntimeUiState {
    enum Phase {
        IDLE,
        CONNECTING,
        LISTENING,
        SPEAKING,
        WORKING,
        WAITING_USER,
        CONTEXT_READY,
        DONE,
        ERROR,
        INFO
    }

    enum Severity {
        NEUTRAL,
        INFO,
        SUCCESS,
        WARNING,
        ERROR
    }

    final Phase phase;
    final Severity severity;
    final String title;
    final String detail;

    RuntimeUiState(Phase phase, Severity severity, String title, String detail) {
        this.phase = phase == null ? Phase.INFO : phase;
        this.severity = severity == null ? Severity.INFO : severity;
        this.title = clean(title);
        this.detail = clean(detail);
    }

    static RuntimeUiState info(String title, String detail) {
        return new RuntimeUiState(Phase.INFO, Severity.INFO, title, detail);
    }

    static RuntimeUiState working(String title, String detail) {
        return new RuntimeUiState(Phase.WORKING, Severity.INFO, title, detail);
    }

    static RuntimeUiState waitingUser(String title, String detail) {
        return new RuntimeUiState(Phase.WAITING_USER, Severity.WARNING, title, detail);
    }

    static RuntimeUiState contextReady(String title, String detail) {
        return new RuntimeUiState(Phase.CONTEXT_READY, Severity.INFO, title, detail);
    }

    static RuntimeUiState success(String title, String detail) {
        return new RuntimeUiState(Phase.DONE, Severity.SUCCESS, title, detail);
    }

    static RuntimeUiState error(String title, String detail) {
        return new RuntimeUiState(Phase.ERROR, Severity.ERROR, title, detail);
    }

    static RuntimeUiState listening(String title) {
        return new RuntimeUiState(Phase.LISTENING, Severity.INFO, title, "");
    }

    static RuntimeUiState connecting(String title) {
        return new RuntimeUiState(Phase.CONNECTING, Severity.INFO, title, "");
    }

    static RuntimeUiState idle(String title) {
        return new RuntimeUiState(Phase.IDLE, Severity.NEUTRAL, title, "");
    }

    static RuntimeUiState fromLegacy(String title, String detail) {
        String message = (clean(title) + " " + clean(detail))
                .toLowerCase(Locale.ROOT);
        if (containsAny(message, "失敗", "錯誤", "無法", "failed", "error")) {
            return error(title, detail);
        }
        if (containsAny(message, "需要你", "需要權限", "選擇", "正在學習",
                "waiting for user", "permission required")) {
            return waitingUser(title, detail);
        }
        if (containsAny(message, "已框選", "已選取", "selected region")) {
            return contextReady(title, detail);
        }
        if (containsAny(message, "完成", "已開", "已送", "找到", "已記住",
                "done", "sent", "saved")) {
            return success(title, detail);
        }
        return info(title, detail);
    }

    static RuntimeUiState fromLiveStatus(String status, boolean active) {
        String text = clean(status);
        String lower = text.toLowerCase(Locale.ROOT);

        if (containsAny(lower, "失敗", "錯誤", "未取得", "尚未設定", "無法",
                "failed", "error")) {
            return error(text.isEmpty() ? "連線發生錯誤" : text, "");
        }
        if (!active) {
            return idle(text.isEmpty() ? "待命" : text);
        }
        if (containsAny(lower, "正在連線", "連線中", "建立 gemini websocket", "connecting")) {
            return connecting(text.isEmpty() ? "正在連線…" : text);
        }
        return listening(text.isEmpty() ? "正在聆聽" : text);
    }

    long recommendedAutoHideMs() {
        if (phase == Phase.WAITING_USER) return 6200L;
        if (phase == Phase.CONTEXT_READY) return 4200L;
        return 2300L;
    }

    boolean isError() {
        return severity == Severity.ERROR || phase == Phase.ERROR;
    }

    boolean needsAttention() {
        return phase == Phase.WAITING_USER || severity == Severity.WARNING;
    }

    boolean isSuccess() {
        return severity == Severity.SUCCESS || phase == Phase.DONE;
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isEmpty()) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty()
                    && value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
