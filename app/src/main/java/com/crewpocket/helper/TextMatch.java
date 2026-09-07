package com.crewpocket.helper;

import java.text.Normalizer;
import java.util.Locale;

/** One canonical, locale-stable fold for every user-facing text comparison. */
final class TextMatch {
    private TextMatch() {}

    static String caseFold(String value) {
        String source = value == null ? "" : value;
        return Normalizer.normalize(source, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }
}
