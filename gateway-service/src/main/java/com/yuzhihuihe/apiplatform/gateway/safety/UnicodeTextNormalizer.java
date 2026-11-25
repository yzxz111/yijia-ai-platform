package com.yuzhihuihe.apiplatform.gateway.safety;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class UnicodeTextNormalizer {

    private static final Pattern ZERO_WIDTH_OR_CONTROL = Pattern.compile(
            "[\\u0000-\\u001F\\u007F\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-_/|:;,]+", Pattern.UNICODE_CHARACTER_CLASS);

    public NormalizedText normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replace('\u3000', ' ')
                .toLowerCase(Locale.ROOT);
        normalized = ZERO_WIDTH_OR_CONTROL.matcher(normalized).replaceAll("");
        normalized = SEPARATORS.matcher(normalized).replaceAll(" ");
        return new NormalizedText(normalized.trim());
    }
}
