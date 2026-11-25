package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.Locale;
import java.util.Set;

/**
 * Validation and normalization for the locale associated with the example image.
 *
 * <p>The value is deliberately nullable: profiles written before this field existed have no
 * persisted value and desktop clients treat that absence as the historical Chinese default.
 */
final class ExpertImageLocale {

    static final String ZH = "zh";
    static final String EN = "en";
    static final String NEUTRAL = "neutral";
    static final Set<String> VALUES = Set.of(ZH, EN, NEUTRAL);

    private ExpertImageLocale() {}

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!VALUES.contains(normalized)) {
            throw new IllegalArgumentException("invalid_expert_example_image_locale");
        }
        return normalized;
    }
}
