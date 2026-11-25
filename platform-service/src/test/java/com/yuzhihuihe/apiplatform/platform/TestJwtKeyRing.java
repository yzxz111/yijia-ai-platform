package com.yuzhihuihe.apiplatform.platform;

import java.nio.file.Files;
import java.nio.file.Path;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class TestJwtKeyRing {

    private static final String ACTIVE_KEY_ID = "platform-test-active";
    private static final Path JWK_SET_PATH = createJwkSet();

    private TestJwtKeyRing() {
    }

    public static void configure(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.jwk-set-location", () -> JWK_SET_PATH.toUri().toString());
        registry.add("security.jwt.active-key-id", () -> ACTIVE_KEY_ID);
    }

    private static Path createJwkSet() {
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyID(ACTIVE_KEY_ID).generate();
            Path path = Files.createTempFile("platform-test-jwt-", ".json");
            Files.writeString(path, new JWKSet(key).toString(false));
            path.toFile().deleteOnExit();
            return path;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
