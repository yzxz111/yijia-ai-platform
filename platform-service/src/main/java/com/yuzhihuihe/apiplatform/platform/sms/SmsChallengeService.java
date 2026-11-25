package com.yuzhihuihe.apiplatform.platform.sms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "sms", name = "enabled", havingValue = "true")
public class SmsChallengeService {

    private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    private static final Duration VERIFIED_REGISTRATION_LIFETIME = Duration.ofMinutes(10);
    private final SmsChallengeRepository repository;
    private final TransactionTemplate transactions;
    /** 独立事务，专用于注册验证码校验：错码计数与已验证标记不能被调用方回滚。 */
    private final TransactionTemplate verifiedAttempts;
    private final PhoneProtectionService phones;
    private final SmsNotificationService notifications;
    private final SmsProofService proofs;
    private final Clock clock;
    private final SecureRandom random;
    private final SecretKeySpec codePepper;
    private final boolean enabled;

    @Autowired
    SmsChallengeService(
            SmsChallengeRepository repository,
            PlatformTransactionManager transactionManager,
            PhoneProtectionService phones,
            SmsNotificationService notifications,
            SmsProofService proofs,
            Clock clock,
            @Value("${sms.security.code-pepper:}") String codePepper,
            @Value("${sms.enabled:false}") boolean enabled) {
        this(repository, transactionManager, phones, notifications, proofs, clock,
                new SecureRandom(), codePepper, enabled);
    }

    SmsChallengeService(
            SmsChallengeRepository repository,
            PlatformTransactionManager transactionManager,
            PhoneProtectionService phones,
            SmsNotificationService notifications,
            SmsProofService proofs,
            Clock clock,
            SecureRandom random,
            String codePepper,
            boolean enabled) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        // 验证码校验必须与调用方事务解耦：注册入口（IdentityService.register /
        // authenticateWithPhoneChallenge）在失败时会抛异常让整个请求回滚，若校验与它在同一事务里，
        // “错码次数 +1”会被一起撤销 —— 5 次上限形同虚设，已注册手机号可被无限次爆破直到猜中。
        this.verifiedAttempts = new TransactionTemplate(transactionManager);
        this.verifiedAttempts.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.phones = phones;
        this.notifications = notifications;
        this.proofs = proofs;
        this.clock = clock;
        this.random = random;
        if (codePepper == null || codePepper.isBlank()) {
            throw new IllegalArgumentException("sms_code_pepper_required");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(codePepper);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("invalid_sms_code_pepper", invalidBase64);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("invalid_sms_code_pepper");
        }
        this.codePepper = new SecretKeySpec(decoded, "HmacSHA256");
        this.enabled = enabled;
    }

    public SmsChallenge issue(
            SmsPurpose purpose,
            String phone,
            UUID tenantId,
            UUID userId,
            String requestIpHash) {
        if (!enabled) {
            throw new IllegalStateException("sms_disabled");
        }
        requireScope(purpose, tenantId, userId);
        requireHash(requestIpHash, "invalid_request_ip_hash");
        PhoneProtectionService.ProtectedPhone protectedPhone = phones.protect(phone);
        return transactions.execute(ignored -> issueInTransaction(
                purpose, protectedPhone, tenantId, userId, requestIpHash));
    }

    public SmsChallenge issueForBoundPhone(
            SmsPurpose purpose,
            UUID tenantId,
            UUID userId,
            String requestIpHash) {
        if (purpose == null || !purpose.issuesProof()) {
            throw new IllegalArgumentException("invalid_sms_purpose");
        }
        List<byte[]> recipients = repository.boundPhoneRecipients(tenantId, userId);
        if (recipients.size() != 1) {
            throw new IllegalStateException("sms_phone_not_bound");
        }
        return issue(purpose, phones.decrypt(recipients.getFirst()), tenantId, userId, requestIpHash);
    }

    public SmsChallenge issueLogin(String phone, String requestIpHash) {
        if (!enabled) {
            throw new IllegalStateException("sms_disabled");
        }
        requireHash(requestIpHash, "invalid_request_ip_hash");
        PhoneProtectionService.ProtectedPhone protectedPhone = phones.protect(phone);
        return transactions.execute(ignored -> {
            List<SmsChallengeRepository.LoginRecipientRow> recipients =
                    repository.loginRecipients(protectedPhone.lookupHash());
            SmsChallengeRepository.LoginRecipientRow recipient =
                    recipients.size() == 1 ? recipients.getFirst() : null;
            if (recipient == null) {
                throw new IllegalArgumentException("sms_login_unavailable");
            }
            requireActiveTemplate(SmsPurpose.LOGIN);
            return issueInTransaction(
                    SmsPurpose.LOGIN,
                    protectedPhone,
                    recipient.tenantId(),
                    recipient.userId(),
                    requestIpHash);
        });
    }

    public SmsChallenge issuePasswordReset(String phone, String requestIpHash) {
        if (!enabled) {
            throw new IllegalStateException("sms_disabled");
        }
        requireHash(requestIpHash, "invalid_request_ip_hash");
        PhoneProtectionService.ProtectedPhone protectedPhone = phones.protect(phone);
        return transactions.execute(ignored -> {
            List<SmsChallengeRepository.LoginRecipientRow> recipients =
                    repository.passwordResetRecipients(protectedPhone.lookupHash());
            SmsChallengeRepository.LoginRecipientRow recipient =
                    recipients.size() == 1 ? recipients.getFirst() : null;
            if (recipient == null) {
                throw new IllegalArgumentException("password_reset_unavailable");
            }
            requireActiveTemplate(SmsPurpose.PASSWORD_RESET);
            return issueInTransaction(
                    SmsPurpose.PASSWORD_RESET,
                    protectedPhone,
                    recipient.tenantId(),
                    recipient.userId(),
                    requestIpHash);
        });
    }

    VerifiedChallenge verify(UUID challengeId, String code) {
        VerificationOutcome outcome = transactions.execute(
                ignored -> verifyInTransaction(challengeId, code, null, null, null));
        return requireVerified(outcome);
    }

    public VerifiedChallenge verifyOwned(
            UUID challengeId, String code, UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new IllegalArgumentException("invalid_sms_challenge");
        }
        VerificationOutcome outcome = transactions.execute(
                ignored -> verifyInTransaction(challengeId, code, tenantId, userId, null));
        return requireVerified(outcome);
    }

    public VerifiedChallenge verifyRegistration(UUID challengeId, String code) {
        // 校验本身留在调用方事务里：注册失败（手机号不匹配 / 唯一约束冲突）时，
        // 「已验证」标记必须随之回滚，用户才能用同一验证码重试。
        // 需要独立落库的只有错码计数，见 verifyInTransaction 里的 afterCompletion 补记。
        VerificationOutcome outcome = transactions.execute(
                ignored -> verifyInTransaction(challengeId, code, null, null, SmsPurpose.REGISTER));
        return requireVerified(outcome);
    }

    public VerifiedChallenge verifyLogin(UUID challengeId, String code) {
        VerificationOutcome outcome = transactions.execute(
                ignored -> verifyInTransaction(challengeId, code, null, null, SmsPurpose.LOGIN));
        return requireVerified(outcome);
    }

    public VerifiedChallenge verifyPasswordReset(UUID challengeId, String code) {
        VerificationOutcome outcome = transactions.execute(
                ignored -> verifyInTransaction(
                        challengeId, code, null, null, SmsPurpose.PASSWORD_RESET));
        return requireVerified(outcome);
    }

    private VerifiedChallenge requireVerified(VerificationOutcome outcome) {
        if (outcome == null) {
            throw new IllegalStateException("sms_verification_failed");
        }
        if (outcome.error() != null) {
            throw new IllegalArgumentException(outcome.error());
        }
        return outcome.verified();
    }

    public RegistrationChallenge consumeVerifiedRegistration(UUID challengeId) {
        List<RegistrationChallenge> rows = transactions.execute(ignored -> {
            Instant now = clock.instant();
            return repository.consumeRegistration(challengeId, now).stream()
                    .map(row -> new RegistrationChallenge(
                            row.challengeId(), row.phoneCiphertext(),
                            row.phoneLookupHash(), row.phoneLast4()))
                    .toList();
        });
        if (rows == null || rows.size() != 1) {
            throw new IllegalArgumentException("invalid_registration_challenge");
        }
        return rows.getFirst();
    }

    public RegistrationChallenge inspectVerifiedRegistration(UUID challengeId) {
        List<RegistrationChallenge> rows = repository.inspectVerifiedRegistration(
                        challengeId, clock.instant()).stream()
                .map(row -> new RegistrationChallenge(
                        row.challengeId(), row.phoneCiphertext(),
                        row.phoneLookupHash(), row.phoneLast4()))
                .toList();
        if (rows.size() != 1) {
            throw new IllegalArgumentException("invalid_registration_challenge");
        }
        return rows.getFirst();
    }

    public PhoneChallenge consumeVerifiedPhoneChallenge(
            UUID challengeId,
            UUID tenantId,
            UUID userId,
            SmsPurpose purpose) {
        if (purpose != SmsPurpose.PHONE_BIND && purpose != SmsPurpose.PHONE_CHANGE) {
            throw new IllegalArgumentException("invalid_phone_challenge");
        }
        List<PhoneChallenge> rows = transactions.execute(ignored -> {
            Instant now = clock.instant();
            return repository.consumePhone(
                    challengeId, tenantId, userId, purpose, now).stream()
                    .map(row -> new PhoneChallenge(
                            row.challengeId(), row.tenantId(), row.userId(), row.purpose(),
                            row.phoneCiphertext(), row.phoneLookupHash(), row.phoneLast4()))
                    .toList();
        });
        if (rows == null || rows.size() != 1) {
            throw new IllegalArgumentException("invalid_phone_challenge");
        }
        return rows.getFirst();
    }

    private SmsChallenge issueInTransaction(
            SmsPurpose purpose,
            PhoneProtectionService.ProtectedPhone phone,
            UUID tenantId,
            UUID userId,
            String requestIpHash) {
        return issueInTransaction(purpose, phone, tenantId, userId, requestIpHash, true);
    }

    private SmsChallenge issueInTransaction(
            SmsPurpose purpose,
            PhoneProtectionService.ProtectedPhone phone,
            UUID tenantId,
            UUID userId,
            String requestIpHash,
            boolean enqueueDelivery) {
        lockRateLimits(phone.lookupHash(), requestIpHash);
        if (purpose.authenticated()) {
            SmsChallengeRepository.UserPhoneRow userPhone = userPhone(tenantId, userId);
            validateRecipientOwnership(purpose, phone.lookupHash(), userPhone);
        }
        Instant now = clock.instant();
        enforceRateLimits(phone.lookupHash(), purpose, requestIpHash, now);
        UUID id = UUID.randomUUID();
        String code = "%06d".formatted(random.nextInt(1_000_000));
        Instant expiresAt = now.plus(CHALLENGE_LIFETIME);
        repository.insertChallenge(
                id, tenantId, userId, purpose,
                phone.ciphertext(), phone.lookupHash(), phone.last4(), codeHash(id, code),
                expiresAt, requestIpHash, now);
        if (enqueueDelivery) {
            notifications.enqueueVerification(
                    tenantId, userId, purpose.templateKey(), phone, Map.of("code", code),
                    "verification:" + id);
        }
        return new SmsChallenge(id, purpose, phone.maskedPhone(), expiresAt);
    }

    private VerificationOutcome verifyInTransaction(
            UUID challengeId,
            String code,
            UUID expectedTenantId,
            UUID expectedUserId,
            SmsPurpose expectedPurpose) {
        if (challengeId == null || code == null || !code.matches("[0-9]{6}")) {
            return VerificationOutcome.error("invalid_sms_code");
        }
        List<SmsChallengeRepository.ChallengeRow> rows =
                repository.lockChallenge(challengeId);
        if (rows.isEmpty()) {
            return VerificationOutcome.error("challenge_not_found");
        }
        SmsChallengeRepository.ChallengeRow row = rows.getFirst();
        if (expectedPurpose != null && row.purpose() != expectedPurpose) {
            return VerificationOutcome.error("invalid_sms_challenge");
        }
        if (expectedPurpose == SmsPurpose.REGISTER
                && (row.tenantId() != null || row.userId() != null)) {
            return VerificationOutcome.error("invalid_sms_challenge");
        }
        if ((expectedPurpose == SmsPurpose.LOGIN || expectedPurpose == SmsPurpose.PASSWORD_RESET)
                && (row.tenantId() == null || row.userId() == null)) {
            return VerificationOutcome.error("invalid_sms_challenge");
        }
        if (expectedTenantId != null && (!expectedTenantId.equals(row.tenantId())
                || !expectedUserId.equals(row.userId()))) {
            return VerificationOutcome.error("invalid_sms_challenge");
        }
        Instant now = clock.instant();
        if (row.consumedAt() != null) {
            return VerificationOutcome.error("challenge_consumed");
        }
        if (row.verifiedAt() != null) {
            // 注册验证在“待设密窗口”内幂等：同意缺失或交接失败后，允许用同一验证码重试，
            // 不必重新发码（与邮箱通道的语义对齐）。其它用途保持原来的拒绝语义。
            if (row.purpose() == SmsPurpose.REGISTER && row.expiresAt().isAfter(now)) {
                return VerificationOutcome.success(new VerifiedChallenge(
                        row.id(), row.tenantId(), row.userId(), row.purpose(),
                        phones.mask(row.phoneCiphertext()), null, null, row.expiresAt()));
            }
            return VerificationOutcome.error("challenge_already_verified");
        }
        if (now.isAfter(row.expiresAt())) {
            return VerificationOutcome.error("challenge_expired");
        }
        if (row.attemptCount() >= 5) {
            return VerificationOutcome.error("challenge_attempts_exhausted");
        }
        boolean matches = MessageDigest.isEqual(
                row.codeHash().getBytes(StandardCharsets.US_ASCII),
                codeHash(row.id(), code).getBytes(StandardCharsets.US_ASCII));
        if (!matches) {
            int attempts = row.attemptCount() + 1;
            // 错码计数必须活下来：调用方（注册/登录入口）会把这次失败转成异常并让整个事务回滚，
            // 若在这里直接 UPDATE，计数会被一起撤销 —— 5 次上限形同虚设。因此改成"外层事务
            // 结束后再用独立事务补记"，既避开行锁冲突（外层此刻还持有 FOR UPDATE），
            // 也保证计数落库。计数用数据库端相对自增，避免并发丢更新。
            if (org.springframework.transaction.support.TransactionSynchronizationManager
                    .isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .registerSynchronization(
                                new org.springframework.transaction.support.TransactionSynchronization() {
                                    @Override
                                    public void afterCompletion(int status) {
                                        verifiedAttempts.execute(ignored -> {
                                            repository.incrementAttemptCount(row.id());
                                            return null;
                                        });
                                    }
                                });
            } else {
                repository.incrementAttemptCount(row.id());
            }
            return VerificationOutcome.error(
                    attempts >= 5 ? "challenge_attempts_exhausted" : "invalid_sms_code");
        }
        if (row.purpose().issuesProof() || row.purpose() == SmsPurpose.LOGIN
                || row.purpose() == SmsPurpose.PASSWORD_RESET) {
            repository.markVerifiedAndConsumed(row.id(), now);
            SmsProofService.IssuedProof proof = row.purpose().issuesProof()
                    ? proofs.issueInTransaction(row.tenantId(), row.userId(), row.purpose())
                    : null;
            return VerificationOutcome.success(new VerifiedChallenge(
                    row.id(), row.tenantId(), row.userId(), row.purpose(),
                    phones.mask(row.phoneCiphertext()),
                    proof == null ? null : proof.token(),
                    proof == null ? null : proof.expiresAt(), null));
        }
        Instant registrationExpiresAt = null;
        if (row.purpose() == SmsPurpose.REGISTER) {
            registrationExpiresAt = now.plus(VERIFIED_REGISTRATION_LIFETIME);
            repository.markRegistrationVerified(row.id(), now, registrationExpiresAt);
        } else {
            repository.markVerified(row.id(), now);
        }
        return VerificationOutcome.success(new VerifiedChallenge(
                row.id(), row.tenantId(), row.userId(), row.purpose(),
                phones.mask(row.phoneCiphertext()), null, null, registrationExpiresAt));
    }

    private void enforceRateLimits(
            String phoneHash, SmsPurpose purpose, String requestIpHash, Instant now) {
        if (repository.recentPurposeCount(
                phoneHash, purpose, now.minusSeconds(60)) > 0) {
            throw new IllegalStateException("sms_rate_limited_60_seconds");
        }
        if (repository.phoneDayCount(phoneHash, now) >= 20) {
            throw new IllegalStateException("sms_rate_limited_phone_day");
        }
        if (repository.phoneHourPurposeCount(phoneHash, purpose, now) >= 5) {
            throw new IllegalStateException("sms_rate_limited_phone_hour");
        }
        if (repository.ipHourCount(requestIpHash, now) >= 20) {
            throw new IllegalStateException("sms_rate_limited_ip_hour");
        }
    }

    private void lockRateLimits(String phoneHash, String requestIpHash) {
        List<String> keys = List.of("phone:" + phoneHash, "ip:" + requestIpHash)
                .stream().sorted().toList();
        repository.lockRateLimits(keys);
    }

    private SmsChallengeRepository.UserPhoneRow userPhone(UUID tenantId, UUID userId) {
        List<SmsChallengeRepository.UserPhoneRow> rows =
                repository.userPhone(tenantId, userId);
        if (rows.size() != 1) {
            throw new IllegalArgumentException("invalid_sms_scope");
        }
        return rows.getFirst();
    }

    private void validateRecipientOwnership(
            SmsPurpose purpose, String targetPhoneHash,
            SmsChallengeRepository.UserPhoneRow userPhone) {
        if (purpose.issuesProof()) {
            if (!userPhone.verified() || !targetPhoneHash.equals(userPhone.lookupHash())) {
                throw new IllegalArgumentException("sms_phone_mismatch");
            }
            return;
        }
        if (purpose == SmsPurpose.PHONE_BIND && userPhone.verified()) {
            throw new IllegalStateException("phone_already_bound");
        }
        if (purpose == SmsPurpose.PHONE_CHANGE
                && (!userPhone.verified() || targetPhoneHash.equals(userPhone.lookupHash()))) {
            throw new IllegalArgumentException("invalid_phone_replacement");
        }
    }

    private String codeHash(UUID challengeId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(codePepper);
            return HexFormat.of().formatHex(mac.doFinal(
                    (challengeId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("sms_code_hash_failed", error);
        }
    }

    private void requireScope(SmsPurpose purpose, UUID tenantId, UUID userId) {
        Objects.requireNonNull(purpose, "purpose");
        if (purpose.authenticated() && (tenantId == null || userId == null)) {
            throw new IllegalArgumentException("authenticated_sms_scope_required");
        }
        if (!purpose.authenticated() && (tenantId != null || userId != null)) {
            throw new IllegalArgumentException("public_sms_scope_required");
        }
    }

    private void requireActiveTemplate(SmsPurpose purpose) {
        if (repository.activeTemplateCount(purpose) != 1) {
            throw new IllegalStateException("sms_template_not_active");
        }
    }

    private void requireHash(String value, String error) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(error);
        }
    }

    public record SmsChallenge(
            UUID id, SmsPurpose purpose, String maskedPhone, Instant expiresAt) {
        @Override
        public String toString() {
            return "SmsChallenge[id=" + id + ", purpose=" + purpose
                    + ", recipient=REDACTED, expiresAt=" + expiresAt + "]";
        }
    }

    public record VerifiedChallenge(
            UUID challengeId,
            UUID tenantId,
            UUID userId,
            SmsPurpose purpose,
            String maskedPhone,
            String proofToken,
            Instant proofExpiresAt,
            Instant registrationExpiresAt) {
        @Override
        public String toString() {
            return "VerifiedChallenge[challengeId=" + challengeId + ", purpose=" + purpose
                    + ", recipient=REDACTED, proofToken=REDACTED]";
        }
    }

    public record RegistrationChallenge(
            UUID challengeId, byte[] phoneCiphertext, String phoneLookupHash, String phoneLast4) {
        public RegistrationChallenge {
            phoneCiphertext = phoneCiphertext.clone();
        }

        @Override
        public byte[] phoneCiphertext() {
            return phoneCiphertext.clone();
        }

        @Override
        public String toString() {
            return "RegistrationChallenge[challengeId=" + challengeId + ", phone=REDACTED]";
        }
    }

    public record PhoneChallenge(
            UUID challengeId,
            UUID tenantId,
            UUID userId,
            SmsPurpose purpose,
            byte[] phoneCiphertext,
            String phoneLookupHash,
            String phoneLast4) {
        public PhoneChallenge {
            phoneCiphertext = phoneCiphertext.clone();
        }

        @Override
        public byte[] phoneCiphertext() {
            return phoneCiphertext.clone();
        }

        @Override
        public String toString() {
            return "PhoneChallenge[challengeId=" + challengeId + ", phone=REDACTED]";
        }
    }

    private record VerificationOutcome(String error, VerifiedChallenge verified) {
        static VerificationOutcome error(String error) {
            return new VerificationOutcome(error, null);
        }

        static VerificationOutcome success(VerifiedChallenge verified) {
            return new VerificationOutcome(null, verified);
        }
    }
}
