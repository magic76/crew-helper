package com.crewpocket.helper;

import java.util.Locale;

/** Pure parser for numbered element-overlay choices. */
final class ElementReferenceChoice {
    private ElementReferenceChoice() {}

    static int parseIndex(String raw, int max) {
        if (max <= 0 || raw == null) return -1;
        String compact = compact(raw);
        if (compact.isEmpty()) return -1;

        if (compact.matches("\\d{1,2}")) {
            try {
                int value = Integer.parseInt(compact);
                return value >= 1 && value <= max ? value - 1 : -1;
            } catch (Exception ignored) {}
        }

        int chinese = chineseNumber(compact);
        if (chinese >= 1 && chinese <= max) return chinese - 1;

        if ("first".equals(compact)) return max >= 1 ? 0 : -1;
        if ("second".equals(compact)) return max >= 2 ? 1 : -1;
        if ("third".equals(compact)) return max >= 3 ? 2 : -1;
        if ("fourth".equals(compact)) return max >= 4 ? 3 : -1;
        if ("fifth".equals(compact)) return max >= 5 ? 4 : -1;
        return -1;
    }

    static boolean looksLikeChoice(String raw) {
        String compact = compact(raw);
        if (compact.isEmpty()) return false;
        if (compact.matches("\\d{1,2}")) return true;
        if (chineseNumber(compact) >= 1) return true;
        return "first".equals(compact)
                || "second".equals(compact)
                || "third".equals(compact)
                || "fourth".equals(compact)
                || "fifth".equals(compact);
    }

    private static String compact(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("[，,。.!！?？：:；;（）()\\s]", "")
                .replace("第", "")
                .replace("個", "")
                .replace("个", "")
                .replace("號", "")
                .replace("号", "")
                .replace("項", "")
                .replace("项", "")
                .trim();
    }

    private static int chineseNumber(String value) {
        if (value == null || value.isEmpty()) return -1;
        if ("十".equals(value)) return 10;
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(value.substring(0, ten));
            int ones = ten == value.length() - 1 ? 0 : digit(value.substring(ten + 1));
            if (tens < 0 || ones < 0) return -1;
            return tens * 10 + ones;
        }
        return digit(value);
    }

    private static int digit(String value) {
        if ("一".equals(value)) return 1;
        if ("二".equals(value) || "兩".equals(value) || "两".equals(value)) return 2;
        if ("三".equals(value)) return 3;
        if ("四".equals(value)) return 4;
        if ("五".equals(value)) return 5;
        if ("六".equals(value)) return 6;
        if ("七".equals(value)) return 7;
        if ("八".equals(value)) return 8;
        if ("九".equals(value)) return 9;
        return -1;
    }
}
