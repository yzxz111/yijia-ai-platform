package com.yuzhihuihe.apiplatform.platform.security;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

final class JwtKeyRing {

    private static final int MIN_RSA_KEY_BITS = 2048;

    private final RSAKey activeSigningKey;
    private final JWKSet publicKeys;

    private JwtKeyRing(RSAKey activeSigningKey, JWKSet publicKeys) {
        this.activeSigningKey = activeSigningKey;
        this.publicKeys = publicKeys;
    }

    static JwtKeyRing load(Resource resource, String activeKeyId) {
        if (!StringUtils.hasText(activeKeyId)) {
            throw new IllegalStateException("security.jwt.active-key-id must be configured");
        }

        JWKSet configuredKeys;
        try (InputStream inputStream = resource.getInputStream()) {
            configuredKeys = JWKSet.load(inputStream);
        } catch (IOException | ParseException exception) {
            throw new IllegalStateException("Unable to load the configured JWT JWK Set", exception);
        }

        if (configuredKeys.isEmpty()) {
            throw new IllegalStateException("The configured JWT JWK Set must contain at least one RSA key");
        }

        Set<String> keyIds = new HashSet<>();
        List<JWK> publicKeys = new ArrayList<>(configuredKeys.size());
        RSAKey activeKey = null;
        for (JWK key : configuredKeys.getKeys()) {
            if (!(key instanceof RSAKey rsaKey)) {
                throw new IllegalStateException("The configured JWT JWK Set contains a non-RSA key");
            }
            String keyId = rsaKey.getKeyID();
            if (!StringUtils.hasText(keyId)) {
                throw new IllegalStateException("Every configured JWT key must have a non-blank key id");
            }
            if (!keyIds.add(keyId)) {
                throw new IllegalStateException("Duplicate JWT key id: " + keyId);
            }
            try {
                int keyBits = rsaKey.toRSAPublicKey().getModulus().bitLength();
                if (keyBits < MIN_RSA_KEY_BITS) {
                    throw new IllegalStateException(
                            "JWT RSA keys must be at least 2048 bits: " + keyId);
                }
            } catch (JOSEException exception) {
                throw new IllegalStateException("Invalid RSA public material for JWT key id: " + keyId, exception);
            }
            publicKeys.add(rsaKey.toPublicJWK());
            if (keyId.equals(activeKeyId)) {
                activeKey = rsaKey;
            }
        }

        if (activeKey == null) {
            throw new IllegalStateException("Active JWT key id is not present in the configured key ring: " + activeKeyId);
        }
        try {
            if (!activeKey.isPrivate() || activeKey.toRSAPrivateKey() == null) {
                throw new IllegalStateException("Active JWT key must contain RSA private material: " + activeKeyId);
            }
        } catch (JOSEException exception) {
            throw new IllegalStateException("Invalid RSA private material for active JWT key id: " + activeKeyId, exception);
        }

        return new JwtKeyRing(activeKey, new JWKSet(publicKeys));
    }

    RSAKey activeSigningKey() {
        return activeSigningKey;
    }

    JWKSet publicKeys() {
        return publicKeys;
    }
}
