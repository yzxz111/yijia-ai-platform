package com.yuzhihuihe.apiplatform.platform.identity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
final class DeviceIdentityService {

    private static final Pattern DEVICE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final String LOCAL_PEPPER = "local-login-risk-pepper-not-for-production";

    private final byte[] pepper;

    @Autowired
    DeviceIdentityService(
            @Value("${identity.login-risk.pepper:}") String configuredPepper,
            Environment environment) {
        this(configuredPepper, allowsLocalFixture(environment));
    }

    DeviceIdentityService(String configuredPepper, boolean allowLocalFixture) {
        String effective = configuredPepper == null || configuredPepper.isBlank()
                ? (allowLocalFixture ? LOCAL_PEPPER : null)
                : configuredPepper;
        if (effective == null || effective.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("identity_login_risk_pepper_is_required");
        }
        this.pepper = effective.getBytes(StandardCharsets.UTF_8).clone();
    }

    String hashRequired(String rawDeviceId) {
        if (rawDeviceId == null || !DEVICE_ID.matcher(rawDeviceId).matches()) {
            throw IdentityApiException.invalidDeviceId();
        }
        return hmac(rawDeviceId);
    }

    String hashOptionalForActivity(String rawDeviceId) {
        return hmac(rawDeviceId == null || rawDeviceId.isBlank() ? "UNKNOWN" : rawDeviceId.trim());
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    ("login-risk:v1:device:" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("device_hash_unavailable", error);
        }
    }

    private static boolean allowsLocalFixture(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length > 0 && Arrays.stream(profiles)
                .allMatch(profile -> "local".equals(profile) || "test".equals(profile));
    }
}
