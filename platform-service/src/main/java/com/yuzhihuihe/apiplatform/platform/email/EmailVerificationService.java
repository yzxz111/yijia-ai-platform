package com.yuzhihuihe.apiplatform.platform.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.yuzhihuihe.apiplatform.platform.sms.SmsApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class EmailVerificationService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration VERIFIED_REGISTRATION_TTL = Duration.ofMinutes(10);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private final EmailVerificationRepository repository;
    private final ObjectProvider<EmailSender> senders;
    private final boolean enabled;
    private final byte[] pepper;
    private final Duration cooldown;
    private final SecureRandom random = new SecureRandom();

    public EmailVerificationService(
            EmailVerificationRepository repository,
            ObjectProvider<EmailSender> senders,
            @Value("${email.enabled:false}") boolean enabled,
            @Value("${email.security.code-pepper:${SMS_CODE_PEPPER:}}") String encodedPepper,
            @Value("${email.cooldown:60s}") Duration cooldown) {
        this.repository = repository;
        this.senders = senders;
        this.enabled = enabled;
        this.pepper = decodePepper(encodedPepper);
        this.cooldown = cooldown;
    }

    @Transactional
    public EmailChallenge issue(String email, String requestIp) {
        requireEnabled();
        String normalized = normalize(email);
        repository.lockEmail(normalized);
        if (repository.recentChallengeCount(normalized, Instant.now().minus(cooldown)) > 0) {
            throw new SmsApiException(HttpStatus.TOO_MANY_REQUESTS, "email_rate_limited");
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expires = now.plus(CODE_TTL);
        repository.insertChallenge(
                id, normalized, hash(id, code), expires, fingerprint(requestIp), now);
        try {
            EmailSender sender = senders.getIfAvailable();
            if (sender == null) throw new IllegalStateException("email_disabled");
            sender.sendVerificationCode(normalized, code);
        } catch (RuntimeException error) {
            if (error instanceof SmsApiException sms) throw sms;
            throw new SmsApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "email_disabled".equals(error.getMessage()) ? "email_disabled" : "email_delivery_failed");
        }
        return new EmailChallenge(id, mask(normalized), expires);
    }

    @Transactional(noRollbackFor = SmsApiException.class)
    public String consume(UUID challengeId, String email, String code) {
        requireEnabled();
        String normalized = normalize(email);
        if (challengeId == null || code == null || !code.matches("\\d{6}")) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_email_challenge");
        }
        EmailVerificationRepository.ChallengeRow row = repository
                .lockChallenge(challengeId, normalized)
                .orElseThrow(this::invalid);
        Instant now = Instant.now();
        if (row.verifiedAt() != null || row.consumedAt() != null
                || !row.expiresAt().isAfter(now) || row.attemptCount() >= 5) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(row.codeHash().getBytes(StandardCharsets.US_ASCII),
                hash(challengeId, code).getBytes(StandardCharsets.US_ASCII))) {
            repository.incrementAttempt(challengeId);
            throw invalid();
        }
        repository.markConsumed(challengeId, now);
        return normalized;
    }

    /** Verify a code without consuming the challenge while the user chooses a password. */
    @Transactional(noRollbackFor = SmsApiException.class)
    public VerifiedRegistration verifyForIdentity(UUID challengeId, String email, String code) {
        requireEnabled();
        String normalized = normalize(email);
        if (challengeId == null || code == null || !code.matches("\\d{6}")) {
            throw invalid();
        }
        EmailVerificationRepository.ChallengeRow row = repository
                .lockChallenge(challengeId, normalized)
                .orElseThrow(this::invalid);
        Instant now = Instant.now();
        if (row.consumedAt() != null || !row.expiresAt().isAfter(now)
                || row.attemptCount() >= 5) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(row.codeHash().getBytes(StandardCharsets.US_ASCII),
                hash(challengeId, code).getBytes(StandardCharsets.US_ASCII))) {
            repository.incrementAttempt(challengeId);
            throw invalid();
        }
        // Repeating a verified code is safe within the fixed completion window. This lets a
        // failed consent or handoff attempt retry without issuing another email.
        if (row.verifiedAt() != null) {
            return new VerifiedRegistration(challengeId, normalized, row.expiresAt());
        }
        Instant completionExpiresAt = now.plus(VERIFIED_REGISTRATION_TTL);
        repository.markRegistrationVerified(challengeId, now, completionExpiresAt);
        return new VerifiedRegistration(challengeId, normalized, completionExpiresAt);
    }

    /** Atomically claims a previously verified email challenge inside account creation. */
    @Transactional
    public String consumeVerifiedRegistration(UUID challengeId) {
        requireEnabled();
        if (challengeId == null) {
            throw invalidRegistration();
        }
        return repository.consumeVerifiedRegistration(challengeId, Instant.now())
                .orElseThrow(this::invalidRegistration);
    }

    public String normalize(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 320 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_email");
        }
        return normalized;
    }

    private void requireEnabled() {
        if (!enabled || pepper == null || senders.getIfAvailable() == null) {
            throw new SmsApiException(HttpStatus.SERVICE_UNAVAILABLE, "email_disabled");
        }
    }

    private String fingerprint(String requestIp) {
        String normalizedIp = requestIp == null ? "" : requestIp.trim();
        return HexFormat.of().formatHex(hmac("email-request-ip:v1:" + normalizedIp));
    }

    private String hash(UUID id, String code) {
        return HexFormat.of().formatHex(hmac(id + ":" + code));
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("email_hash_unavailable", error);
        }
    }

    private byte[] decodePepper(String encoded) {
        if (!enabled || encoded == null || encoded.isBlank()) return null;
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
            return decoded.length == 32 ? decoded : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private SmsApiException invalid() {
        return new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_email_challenge");
    }

    private SmsApiException invalidRegistration() {
        return new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "invalid_registration_challenge");
    }

    private String mask(String email) {
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String visible = local.length() <= 1 ? local : local.substring(0, 1);
        return visible + "***" + email.substring(at);
    }

    public record EmailChallenge(UUID id, String maskedEmail, Instant expiresAt) { }

    public record VerifiedRegistration(UUID challengeId, String email, Instant expiresAt) { }
}
