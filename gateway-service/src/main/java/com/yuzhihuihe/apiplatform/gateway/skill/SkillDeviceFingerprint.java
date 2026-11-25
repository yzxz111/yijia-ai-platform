package com.yuzhihuihe.apiplatform.gateway.skill;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Hashes {@code X-Device-Id} with HMAC-SHA256 before it ever reaches the database (design 4.3:
 * download sessions only keep the hash, never the plain device id).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillDeviceFingerprint {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public SkillDeviceFingerprint(
            @Value("${security.internal.hmac-secret:}") String secret) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code null} when the client did not send a device id or no HMAC secret is configured. */
    public String hash(String deviceId) {
        if (!StringUtils.hasText(deviceId) || secret.length == 0) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] digest =
                    mac.doFinal(deviceId.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("device_fingerprint_unavailable", exception);
        }
    }
}

