package com.yuzhihuihe.apiplatform.gateway.safety;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public record PolicySnapshot(
        long version, String configSha256, List<Rule> rules, Instant publishedAt, String signature) {

    private static final HexFormat HEX = HexFormat.of();

    public PolicySnapshot {
        if (version <= 0 || configSha256 == null || !configSha256.matches("[0-9a-f]{64}")
                || publishedAt == null || signature == null) {
            throw new IllegalArgumentException("invalid_content_safety_policy_snapshot");
        }
        rules = List.copyOf(rules == null ? List.of() : rules);
    }

    public static PolicySnapshot signed(long version, List<Rule> rules, Instant publishedAt, String secret) {
        String hash = sha256(canonical(version, rules, publishedAt));
        String signature = hmac(canonical(version, rules, publishedAt, hash), secret);
        return new PolicySnapshot(version, hash, rules, publishedAt, signature);
    }

    String signingPayload() {
        return canonical(version, rules, publishedAt, configSha256);
    }

    boolean hasValidSignature(String secret) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(hmac(signingPayload(), secret).getBytes(StandardCharsets.US_ASCII),
                        signature.getBytes(StandardCharsets.US_ASCII));
    }

    private static String canonical(long version, List<Rule> rules, Instant publishedAt) {
        return canonical(version, rules, publishedAt, "");
    }

    private static String canonical(long version, List<Rule> rules, Instant publishedAt, String hash) {
        StringBuilder value = new StringBuilder()
                .append(version).append('\n').append(hash).append('\n').append(publishedAt).append('\n');
        rules.stream().sorted(java.util.Comparator.comparing(Rule::ruleCode))
                .forEach(rule -> value.append(rule.ruleCode()).append('|')
                        .append(rule.ruleType()).append('|').append(rule.category()).append('|')
                        .append(rule.severity()).append('|')
                        .append(rule.action()).append('\n'));
        return value.toString();
    }

    private static String sha256(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("content_safety_hash_unavailable", exception);
        }
    }

    private static String hmac(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Objects.requireNonNull(secret, "secret").getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("content_safety_signature_unavailable", exception);
        }
    }

    public record Rule(String ruleCode, String ruleType, String category, String severity, String action) {
        public Rule(String ruleCode, String ruleType, String severity, String action) {
            this(ruleCode, ruleType, "", severity, action);
        }

        public Rule {
            Objects.requireNonNull(ruleCode, "ruleCode");
            Objects.requireNonNull(ruleType, "ruleType");
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(action, "action");
        }
    }
}
