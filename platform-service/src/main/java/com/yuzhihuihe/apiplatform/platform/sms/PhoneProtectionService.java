package com.yuzhihuihe.apiplatform.platform.sms;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.yuzhihuihe.apiplatform.platform.security.SecretEncryptionService;

@Service
@ConditionalOnProperty(prefix = "sms", name = "enabled", havingValue = "true")
public class PhoneProtectionService {

    private static final Pattern MAINLAND_PHONE = Pattern.compile("1[3-9][0-9]{9}");
    private final SecretEncryptionService encryption;
    private final SecretKeySpec lookupKey;

    public PhoneProtectionService(
            SecretEncryptionService encryption,
            @Value("${sms.security.phone-lookup-key:}") String lookupKey) {
        this.encryption = Objects.requireNonNull(encryption, "encryption");
        if (lookupKey == null || lookupKey.isBlank()) {
            throw new IllegalArgumentException("sms_phone_lookup_key_required");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(lookupKey);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("invalid_sms_phone_lookup_key", invalidBase64);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("invalid_sms_phone_lookup_key");
        }
        this.lookupKey = new SecretKeySpec(decoded, "HmacSHA256");
    }

    public String normalize(String candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("invalid_mainland_phone");
        }
        String compact = candidate.replaceAll("[\\s-]", "");
        String local = compact.startsWith("+86") ? compact.substring(3) : compact;
        if (!MAINLAND_PHONE.matcher(local).matches()) {
            throw new IllegalArgumentException("invalid_mainland_phone");
        }
        return "+86" + local;
    }

    public ProtectedPhone protect(String candidate) {
        String normalized = normalize(candidate);
        String local = normalized.substring(3);
        return new ProtectedPhone(
                encryption.encrypt(normalized.getBytes(StandardCharsets.UTF_8)),
                hmac(normalized),
                local.substring(local.length() - 4), maskNormalized(normalized));
    }

    public String decrypt(byte[] ciphertext) {
        return new String(encryption.decrypt(ciphertext), StandardCharsets.UTF_8);
    }

    public String mask(byte[] ciphertext) {
        return maskNormalized(normalize(decrypt(ciphertext)));
    }

    private String maskNormalized(String normalized) {
        String local = normalized.substring(3);
        return local.substring(0, 3) + "****" + local.substring(local.length() - 4);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(lookupKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("sms_phone_lookup_failed", error);
        }
    }

    public record ProtectedPhone(
            byte[] ciphertext,
            String lookupHash,
            String last4,
            String maskedPhone) {

        public ProtectedPhone {
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public String toString() {
            return "ProtectedPhone[maskedPhone=" + maskedPhone + "]";
        }
    }
}
