package com.yuzhihuihe.apiplatform.platform.referral;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ReferralCodeGeneratorTest {

    @Test
    void generatesThirtyTwoIndependentCaseSensitiveBase62Characters() {
        ReferralCodeGenerator generator = new ReferralCodeGenerator();
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String code = generator.generate();
            assertThat(code).matches("[A-Za-z0-9]{32}");
            assertThat(generator.valid(code)).isTrue();
            codes.add(code);
        }
        assertThat(codes).hasSize(100);
        assertThat(generator.valid("a".repeat(31))).isFalse();
        assertThat(generator.valid("a".repeat(31) + "-")).isFalse();
    }
}
