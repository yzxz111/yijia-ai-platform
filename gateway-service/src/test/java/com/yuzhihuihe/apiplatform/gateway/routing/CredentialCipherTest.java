package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    @Test
    void encryptsCredentialsWithAuthenticatedRandomizedCiphertext() {
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        CredentialCipher cipher = new CredentialCipher(key);

        byte[] first = cipher.encrypt("provider-secret");
        byte[] second = cipher.encrypt("provider-secret");

        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first, StandardCharsets.UTF_8)).doesNotContain("provider-secret");
        assertThat(cipher.decrypt(first)).isEqualTo("provider-secret");
    }
}
