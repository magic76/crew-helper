package com.crewpocket.helper;

import java.util.Locale;

/**
 * Recognizes a bounded independent spoken follow-up at the Gemini Live turn
 * boundary. This class does not execute actions or choose a UI target.
 * Accessibility, model grounding and Runtime safety still own those decisions.
 */
final class NaturalVoiceFollowUpPolicy {
    private NaturalVoiceFollowUpPolicy() {}

    static boolean isIndependentAction(String raw) {
        String s = compact(raw);
        if (s.isEmpty() || s.length() > 28) return false;
        if (startsWith(s, "然後", "然后", "接著", "接着",
                "順便", "顺便", "並且", "并且", "then", "andthen")) {
            return false;
        }
        s = unwrapPoliteness(s);
        // Deictic repeats require a known, recent verified reversible action.
        if (s.matches("^再(?:一下|一點|一点|一點點|一点点|一次|來一次|来一次|做一次|滑一下|滑動一下|滑动一下)$")) {
            return true;
        }
        // Natural spatial variants; no assumption about visual swipe direction.
        if (s.matches("^(?:再)?(?:往|向)?[左右上下](?:邊|边)?(?:滑|滑動|滑动|移|移動|移动|推|拖)(?:一下|一點|一点|一點點|一点点)?$")) {
            return true;
        }
        if (s.matches("^(?:再)?(?:往|向)?[左右上下](?:邊|边)?(?:一點|一点|一點點|一点点)$")) {
            return true;
        }
        if (s.matches("^(?:點|点|按|選|选|選擇|选择)?第?[一二三四五六七八九十1-9](?:個|个|項|项|筆|笔|首)$")) {
            return true;
        }
        if (s.matches("^(?:點|点|按|選|选|選擇|选择)?(?:上面|下面|左邊|右邊|左边|右边)(?:的)?(?:那個|那个|一個|一个)$")) {
            return true;
        }
        return s.matches("^(?:上一個|上一个|下一個|下一个|上一首|下一首)$")
                || s.matches("^(?:點|点|按)(?:一下)?(?:播放|暫停|暂停|返回)$");
    }

    private static String unwrapPoliteness(String value) {
        String s = value;
        String[] prefixes = {"可以幫我", "可以帮我", "麻煩幫我", "麻烦帮我",
                "請幫我", "请帮我", "幫我", "帮我", "請", "请"};
        boolean removed;
        do {
            removed = false;
            for (String prefix : prefixes) {
                if (s.startsWith(prefix)) {
                    s = s.substring(prefix.length());
                    removed = true;
                    break;
                }
            }
        } while (removed);
        return s;
    }

    private static boolean startsWith(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String compact(String value) {
        return value == null ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("[\\s，,。！？!「」『』\"'：:；;（）()_-]+", "")
                        .trim();
    }
}
