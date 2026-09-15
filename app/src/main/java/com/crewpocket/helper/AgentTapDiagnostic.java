package com.crewpocket.helper;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Privacy-bounded extraction of TAP target labels from Runtime signatures. */
final class AgentTapDiagnostic {
    private static final int MAX_TARGET_CHARS = 80;
    private static final Pattern JSON_STRING = Pattern.compile(
            "\\\"%s\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");

    private AgentTapDiagnostic() {}

    static String targetFromRuntimeSignature(
            String runtimeTool,
            String semanticAction,
            String signature) {
        if (!"tap_screen".equals(runtimeTool)) return "";
        if (!"TAP".equalsIgnoreCase(clean(semanticAction))) return "";
        return sanitizeTarget(extractJsonString(signature, "label"));
    }

    static String semanticTargetFromRuntimeSignature(
            String runtimeTool,
            String semanticAction,
            String signature) {
        if (!"tap_screen".equals(runtimeTool)) return "";
        if (!"TAP".equalsIgnoreCase(clean(semanticAction))) return "";
        String value = clean(extractJsonString(signature, "semantic_target"));
        if (value.length() > 80) return "";
        return value.matches("[A-Za-z0-9:_-]{1,80}") ? value : "";
    }

    static String sanitizeTarget(String raw) {
        String value = clean(raw).replaceAll("\\s+", " ");
        if (value.isEmpty() || value.length() > MAX_TARGET_CHARS) return "";

        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("http://") || lower.contains("https://")
                || lower.contains("www.") || value.contains("@")) {
            return "";
        }
        if (containsAny(lower,
                "password", "passcode", "credential", "otp", "one-time password",
                "密碼", "密码", "驗證碼", "验证码", "一次性密碼", "一次性密码")) {
            return "";
        }

        int digits = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) digits++;
        }
        if (digits >= 7) return "";
        return value;
    }

    private static String extractJsonString(String signature, String key) {
        String source = signature == null ? "" : signature;
        if (source.isEmpty() || key == null || key.isEmpty()) return "";
        Pattern pattern = Pattern.compile(String.format(JSON_STRING.pattern(), Pattern.quote(key)));
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) return "";
        return unescapeJson(matcher.group(1));
    }

    private static String unescapeJson(String value) {
        if (value == null || value.indexOf('\\') < 0) return value == null ? "" : value;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            if (next == 'u' && i + 4 < value.length()) {
                try {
                    out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                    continue;
                } catch (Exception ignored) {}
            }
            switch (next) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                default: out.append(next); break;
            }
        }
        return out.toString();
    }

    private static boolean containsAny(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
