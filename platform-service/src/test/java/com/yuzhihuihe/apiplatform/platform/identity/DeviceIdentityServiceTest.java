package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DeviceIdentityServiceTest {

    private final DeviceIdentityService devices = new DeviceIdentityService(
            "test-login-risk-pepper-at-least-32-bytes", true);

    @Test
    void hashesOnlyCanonicalBrowserDeviceIds() {
        String raw = "0123456789abcdef0123456789abcdef";
        assertThat(devices.hashRequired(raw))
                .matches("[0-9a-f]{64}")
                .isEqualTo(devices.hashRequired(raw))
                .doesNotContain(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "short",
            "0123456789ABCDEF0123456789ABCDEF",
            " 0123456789abcdef0123456789abcdef "
    })
    void rejectsMalformedDeviceIds(String value) {
        assertThatThrownBy(() -> devices.hashRequired(value))
                .isInstanceOfSatisfying(IdentityApiException.class,
                        error -> assertThat(error.code()).isEqualTo("invalid_device_id"));
    }

    @Test
    void opaqueTokensContainAtLeast256BitsAndHaveStableHashes() {
        OpaqueTokenService tokens = new OpaqueTokenService();
        String raw = tokens.generate();
        assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
        assertThat(tokens.hash(raw)).matches("[0-9a-f]{64}").isEqualTo(tokens.hash(raw));
    }
}
