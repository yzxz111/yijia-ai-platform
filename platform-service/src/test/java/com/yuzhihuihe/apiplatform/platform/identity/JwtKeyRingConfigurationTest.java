package com.yuzhihuihe.apiplatform.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yuzhihuihe.apiplatform.platform.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class JwtKeyRingConfigurationTest {

    private static final String ISSUER = "key-ring-test";

    @TempDir
    Path tempDirectory;

    @Test
    void configuredActiveKeySignsWithTheSameKidAcrossRecreatedContexts() throws Exception {
        RSAKey active = rsaKey("active-2026-07");
        Path keyRing = writeKeyRing(active, rsaKey("previous-2026-06"));
        IdentityService.UserProfile user = userProfile();

        String firstToken;
        try (AnnotationConfigApplicationContext first = startContext(keyRing, active.getKeyID())) {
            firstToken = first.getBean(JwtTokenService.class).createAccessToken(user);
        }
        String secondToken;
        try (AnnotationConfigApplicationContext second = startContext(keyRing, active.getKeyID())) {
            secondToken = second.getBean(JwtTokenService.class).createAccessToken(user);
        }

        assertSignedBy(firstToken, active);
        assertSignedBy(secondToken, active);
    }

    @Test
    void jwksPublishesActiveAndRetainedKeysWithoutPrivateParameters() throws Exception {
        RSAKey active = rsaKey("active-2026-07");
        RSAKey previous = rsaKey("previous-2026-06");
        Path keyRing = writeKeyRing(active, previous);

        Map<String, Object> response;
        try (AnnotationConfigApplicationContext context = startContext(keyRing, active.getKeyID())) {
            response = context.getBean(JwkSetController.class).jwkSet();
        }

        JWKSet published = JWKSet.parse(response);
        assertThat(published.getKeys()).extracting(JWK::getKeyID)
                .containsExactlyInAnyOrder(active.getKeyID(), previous.getKeyID());
        assertThat(published.getKeys()).allSatisfy(key -> {
            assertThat(key.isPrivate()).isFalse();
            assertThat(key.toJSONObject().keySet()).doesNotContainAnyElementsOf(
                    Set.of("d", "p", "q", "dp", "dq", "qi", "oth"));
        });
    }

    @Test
    void decoderAcceptsTokensFromActiveAndRetainedKeysDuringOverlap() throws Exception {
        RSAKey active = rsaKey("active-2026-07");
        RSAKey previous = rsaKey("previous-2026-06");
        Path keyRing = writeKeyRing(active, previous);

        try (AnnotationConfigApplicationContext context = startContext(keyRing, active.getKeyID())) {
            JwtDecoder decoder = context.getBean(JwtDecoder.class);

            assertThat(decoder.decode(signedToken(active)).getSubject()).isEqualTo("test-subject");
            assertThat(decoder.decode(signedToken(previous)).getSubject()).isEqualTo("test-subject");
        }
    }

    @Test
    void missingActiveKeyIdPropertyFailsClosed() throws Exception {
        Path keyRing = writeKeyRing(rsaKey("active-2026-07"));

        assertThatThrownBy(() -> startContext(keyRing, null))
                .isInstanceOf(BeanCreationException.class);
    }

    @Test
    void unknownActiveKeyIdFailsClosed() throws Exception {
        Path keyRing = writeKeyRing(rsaKey("active-2026-07"));

        assertThatThrownBy(() -> startContext(keyRing, "not-in-the-key-ring"))
                .isInstanceOf(BeanCreationException.class)
                .hasStackTraceContaining("not-in-the-key-ring");
    }

    @Test
    void activeKeyWithoutPrivateMaterialFailsClosed() throws Exception {
        RSAKey publicOnly = rsaKey("active-2026-07").toPublicJWK();
        Path keyRing = writeKeyRing(publicOnly);

        assertThatThrownBy(() -> startContext(keyRing, publicOnly.getKeyID()))
                .isInstanceOf(BeanCreationException.class)
                .hasStackTraceContaining("private");
    }

    @Test
    void rsaKeyBelow2048BitsFailsClosed() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey weakKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("weak-active")
                .build();
        Path keyRing = writeKeyRing(weakKey);

        assertThatThrownBy(() -> startContext(keyRing, weakKey.getKeyID()))
                .isInstanceOf(BeanCreationException.class)
                .hasStackTraceContaining("at least 2048 bits");
    }

    @Test
    void duplicateKeyIdsFailClosed() throws Exception {
        Path keyRing = writeKeyRing(rsaKey("duplicate"), rsaKey("duplicate"));

        assertThatThrownBy(() -> startContext(keyRing, "duplicate"))
                .isInstanceOf(BeanCreationException.class)
                .hasStackTraceContaining("duplicate");
    }

    @Test
    void retainedKeyWithoutIdFailsClosed() throws Exception {
        Path keyRing = writeKeyRing(rsaKey("active-2026-07"), new RSAKeyGenerator(2048).generate());

        assertThatThrownBy(() -> startContext(keyRing, "active-2026-07"))
                .isInstanceOf(BeanCreationException.class)
                .hasStackTraceContaining("key id");
    }

    private AnnotationConfigApplicationContext startContext(Path keyRing, String activeKeyId) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues properties = TestPropertyValues.of(
                "security.jwt.jwk-set-location=" + keyRing.toUri(),
                "security.jwt.issuer=" + ISSUER);
        if (activeKeyId != null) {
            properties = properties.and("security.jwt.active-key-id=" + activeKeyId);
        }
        properties.applyTo(context);
        context.register(SecurityConfiguration.class, JwtTokenService.class, JwkSetController.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException exception) {
            context.close();
            throw exception;
        }
    }

    private Path writeKeyRing(RSAKey... keys) throws Exception {
        Path path = tempDirectory.resolve(UUID.randomUUID() + "-jwks.json");
        Files.writeString(path, new JWKSet(List.of(keys)).toString(false));
        return path;
    }

    private RSAKey rsaKey(String keyId) throws Exception {
        return new RSAKeyGenerator(2048).keyID(keyId).generate();
    }

    private IdentityService.UserProfile userProfile() {
        UUID userId = UUID.randomUUID();
        return new IdentityService.UserProfile(
                userId, UUID.randomUUID(), "user@example.com", "Test User", "USER");
    }

    private void assertSignedBy(String token, RSAKey expectedKey) throws Exception {
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(expectedKey.getKeyID());
        assertThat(jwt.verify(new RSASSAVerifier(expectedKey.toRSAPublicKey()))).isTrue();
    }

    private String signedToken(RSAKey key) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("test-subject")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
