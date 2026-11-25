package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExpertImageLocaleTest {

    @Test
    void acceptsOnlySupportedValuesAndKeepsLegacyMissingValueNullable() {
        assertThat(ExpertImageLocale.normalize(null)).isNull();
        assertThat(ExpertImageLocale.normalize("  EN ")).isEqualTo("en");
        assertThat(ExpertImageLocale.normalize("neutral")).isEqualTo("neutral");
        assertThatThrownBy(() -> ExpertImageLocale.normalize("fr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_expert_example_image_locale");
    }
}
