package com.yuzhihuihe.apiplatform.platform.sms;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class SmsRequestFingerprintService {

    private static final byte[] DOMAIN = "sms-request-ip:v1:".getBytes(StandardCharsets.US_ASCII);
    private final SecretKeySpec key;
    private final boolean enabled;

    public SmsRequestFingerprintService(
            @Value("${sms.security.request-ip-key:}") String encodedKey,
            @Value("${sms.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            this.key = null;
            return;
        }
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalArgumentException("sms_request_ip_key_required");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("invalid_sms_request_ip_key", invalidBase64);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("invalid_sms_request_ip_key");
        }
        this.key = new SecretKeySpec(decoded, "HmacSHA256");
    }

    public String fingerprint(HttpServletRequest request) {
        if (!enabled) {
            throw new SmsApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "sms_disabled");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(DOMAIN);
            return HexFormat.of().formatHex(mac.doFinal(
                    request.getRemoteAddr().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new SmsApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "sms_disabled");
        }
    }
}
