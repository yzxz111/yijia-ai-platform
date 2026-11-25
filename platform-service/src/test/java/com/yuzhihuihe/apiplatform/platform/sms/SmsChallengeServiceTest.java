package com.yuzhihuihe.apiplatform.platform.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SmsChallengeServiceTest extends SmsPostgresTestSupport {

    private static final String IP_HASH = "a".repeat(64);
    private static final String CODE_PEPPER = Base64.getEncoder().encodeToString(keyBytes((byte) 0x52));

    @Test
    void normalizesProtectsLooksUpAndMasksMainlandPhoneWithoutLeakingIt() {
        assertThat(phones.normalize(" 150 1234 8963 ")).isEqualTo("+8615012348963");
        assertThat(phones.normalize("+86-150-1234-8963")).isEqualTo("+8615012348963");

        PhoneProtectionService.ProtectedPhone protectedPhone = phones.protect("15012348963");

        assertThat(protectedPhone.maskedPhone()).isEqualTo("150****8963");
        assertThat(protectedPhone.lookupHash()).matches("[0-9a-f]{64}");
        assertThat(new String(protectedPhone.ciphertext(), StandardCharsets.UTF_8))
                .doesNotContain("15012348963", "+8615012348963");
        assertThat(phones.decrypt(protectedPhone.ciphertext())).isEqualTo("+8615012348963");
        assertThat(protectedPhone.toString()).doesNotContain("15012348963", "+8615012348963");
        assertThat(phones.protect("+8615012348963").lookupHash())
                .isEqualTo(protectedPhone.lookupHash());
    }

    @Test
    void rejectsInvalidOrNonMainlandPhoneNumbersAndEmptyLookupKey() {
        assertThatThrownBy(() -> phones.normalize("12345"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_mainland_phone");
        assertThatThrownBy(() -> phones.normalize("+85215012348963"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_mainland_phone");
        assertThatThrownBy(() -> new PhoneProtectionService(encryption, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sms_phone_lookup_key_required");
        assertThatThrownBy(() -> new PhoneProtectionService(encryption, "not-base64"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_sms_phone_lookup_key");
        assertThatThrownBy(() -> new PhoneProtectionService(
                encryption, Base64.getEncoder().encodeToString(new byte[31])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_sms_phone_lookup_key");
    }

    @Test
    void issuesSixDigitChallengeWithCiphertextAndHashesOnlyThenVerifiesOnce() {
        SmsChallengeService service = challenges(new SecureRandom());

        SmsChallengeService.SmsChallenge challenge = service.issue(
                SmsPurpose.REGISTER, "15012348963", null, null, IP_HASH);

        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT phone_ciphertext, phone_lookup_hash, code_hash, expires_at,
                       attempt_count, verified_at, consumed_at
                FROM platform.sms_verification_challenge WHERE id = ?
                """, challenge.id());
        assertThat(stored.get("phone_ciphertext")).isInstanceOf(byte[].class);
        assertThat(stored.get("phone_lookup_hash")).isEqualTo(
                phones.protect("15012348963").lookupHash());
        assertThat(stored.get("code_hash").toString()).matches("[0-9a-f]{64}");
        assertThat(challenge.maskedPhone()).isEqualTo("150****8963");
        assertThat(challenge.toString()).doesNotContain("15012348963", "code");

        byte[] encryptedVariables = jdbc.queryForObject("""
                SELECT sensitive_variables_ciphertext FROM platform.sms_delivery
                WHERE deduplication_key = ?
                """, byte[].class, "verification:" + challenge.id());
        String sensitiveJson = new String(encryption.decrypt(encryptedVariables), StandardCharsets.UTF_8);
        String code = objectMapper.convertValue(readJson(sensitiveJson), Map.class).get("code").toString();
        assertThat(code).matches("[0-9]{6}");
        assertThat(stored.get("code_hash")).isNotEqualTo(code);
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_exists(variables, 'code') FROM platform.sms_delivery
                WHERE deduplication_key = ?
                """, Boolean.class, "verification:" + challenge.id())).isFalse();

        SmsChallengeService.VerifiedChallenge verified = service.verifyRegistration(challenge.id(), code);
        assertThat(verified.purpose()).isEqualTo(SmsPurpose.REGISTER);
        assertThat(verified.maskedPhone()).isEqualTo("150****8963");
        assertThat(verified.proofToken()).isNull();
        assertThat(verified.toString()).doesNotContain("15012348963", "+8615012348963");
        Map<String, Object> firstVerification = jdbc.queryForMap("""
                SELECT attempt_count, verified_at, consumed_at, expires_at
                FROM platform.sms_verification_challenge WHERE id = ?
                """, challenge.id());
        assertThat(firstVerification.get("verified_at")).isNotNull();
        assertThat(firstVerification.get("consumed_at")).isNull();
        assertThat(verified.registrationExpiresAt()).isEqualTo(
                ((java.sql.Timestamp) firstVerification.get("expires_at")).toInstant());

        // REGISTER verification remains one-shot even before account creation consumes it.
        // A replay must not renew the completion window or enqueue another SMS.
        assertThatThrownBy(() -> service.verifyRegistration(challenge.id(), code))
                .hasMessage("challenge_already_verified");
        assertThat(jdbc.queryForMap("""
                SELECT attempt_count, verified_at, consumed_at, expires_at
                FROM platform.sms_verification_challenge WHERE id = ?
                """, challenge.id())).isEqualTo(firstVerification);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_delivery WHERE deduplication_key = ?
                """, Integer.class, "verification:" + challenge.id())).isOne();

        service.consumeVerifiedRegistration(challenge.id());
        assertThatThrownBy(() -> service.verifyRegistration(challenge.id(), code))
                .hasMessage("challenge_consumed");
    }

    @Test
    void locksAfterFiveAttemptsAndRejectsExpiredChallenge() {
        SmsChallengeService service = challenges(new SecureRandom());
        SmsChallengeService.SmsChallenge challenge = service.issue(
                SmsPurpose.REGISTER, "15112348963", null, null, IP_HASH);

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThatThrownBy(() -> service.verifyRegistration(challenge.id(), "000000"))
                    .hasMessage(attempt == 5 ? "challenge_attempts_exhausted" : "invalid_sms_code");
        }
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM platform.sms_verification_challenge WHERE id = ?
                """, Integer.class, challenge.id())).isEqualTo(5);
        assertThatThrownBy(() -> service.verifyRegistration(challenge.id(), "000000"))
                .hasMessage("challenge_attempts_exhausted");

        SmsChallengeService.SmsChallenge expiring = service.issue(
                SmsPurpose.REGISTER, "15212348963", null, null, "b".repeat(64));
        clock.advance(Duration.ofMinutes(5).plusMillis(1));
        assertThatThrownBy(() -> service.verifyRegistration(expiring.id(), "000000"))
                .hasMessage("challenge_expired");
    }

    @Test
    void authenticatedPurposeConsumesChallengeAndReturnsOwnedOneTimeProof() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        bindPhone(userId, "15312348963");
        SmsChallengeService service = challenges(new SecureRandom());
        SmsChallengeService.SmsChallenge challenge = service.issue(
                SmsPurpose.CHANGE_PASSWORD, "15312348963", tenantId, userId, IP_HASH);
        String code = deliveredCode(challenge.id());

        SmsChallengeService.VerifiedChallenge verified = service.verifyOwned(
                challenge.id(), code, tenantId, userId);

        assertThat(verified.proofToken()).isNotBlank();
        assertThat(verified.maskedPhone()).isEqualTo("153****8963");
        assertThat(verified.toString()).doesNotContain(verified.proofToken(), code);
        assertThat(jdbc.queryForObject("""
                SELECT consumed_at IS NOT NULL FROM platform.sms_verification_challenge WHERE id = ?
                """, Boolean.class, challenge.id())).isTrue();
        assertThatThrownBy(() -> service.verifyOwned(challenge.id(), code, tenantId, userId))
                .hasMessage("challenge_consumed");
    }

    @Test
    void scopedVerificationRejectsAnotherUserBeforeIncrementingAttempts() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID attackerId = UUID.randomUUID();
        insertUser(tenantId, ownerId, true);
        insertUser(UUID.randomUUID(), attackerId, true);
        bindPhone(ownerId, "15312348963");
        SmsChallengeService service = challenges(new SecureRandom());
        SmsChallengeService.SmsChallenge challenge = service.issue(
                SmsPurpose.CHANGE_PASSWORD, "15312348963", tenantId, ownerId, IP_HASH);

        assertThatThrownBy(() -> service.verifyOwned(
                challenge.id(), "000000", UUID.randomUUID(), attackerId))
                .hasMessage("invalid_sms_challenge");
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM platform.sms_verification_challenge WHERE id = ?
                """, Integer.class, challenge.id())).isZero();
        assertThat(service.verifyOwned(
                challenge.id(), deliveredCode(challenge.id()), tenantId, ownerId).purpose())
                .isEqualTo(SmsPurpose.CHANGE_PASSWORD);
    }

    @Test
    void sensitiveProofChallengeMustTargetTheVerifiedBoundPhone() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        bindPhone(userId, "15312348963");
        SmsChallengeService service = challenges(new SecureRandom());

        assertThatThrownBy(() -> service.issue(
                SmsPurpose.CHANGE_PASSWORD, "15912348963", tenantId, userId, IP_HASH))
                .hasMessage("sms_phone_mismatch");
        assertThat(service.issue(
                SmsPurpose.CHANGE_PASSWORD, "15312348963", tenantId, userId, IP_HASH).purpose())
                .isEqualTo(SmsPurpose.CHANGE_PASSWORD);
    }

    @Test
    void phoneBindingChallengeIsConsumedAtomicallyWithVerifiedRecipientOwnership() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        SmsChallengeService service = challenges(new SecureRandom());
        SmsChallengeService.SmsChallenge challenge = service.issue(
                SmsPurpose.PHONE_BIND, "15612348963", tenantId, userId, IP_HASH);

        SmsChallengeService.VerifiedChallenge verified =
                service.verifyOwned(challenge.id(), deliveredCode(challenge.id()), tenantId, userId);

        assertThat(verified.proofToken()).isNull();
        assertThatThrownBy(() -> service.consumeVerifiedPhoneChallenge(
                challenge.id(), UUID.randomUUID(), userId, SmsPurpose.PHONE_BIND))
                .hasMessage("invalid_phone_challenge");
        SmsChallengeService.PhoneChallenge consumed = service.consumeVerifiedPhoneChallenge(
                challenge.id(), tenantId, userId, SmsPurpose.PHONE_BIND);
        assertThat(phones.decrypt(consumed.phoneCiphertext())).isEqualTo("+8615612348963");
        assertThat(consumed.phoneLookupHash()).isEqualTo(phones.protect("15612348963").lookupHash());
        assertThat(consumed.toString()).doesNotContain("15612348963", "+8615612348963");
        assertThatThrownBy(() -> service.consumeVerifiedPhoneChallenge(
                challenge.id(), tenantId, userId, SmsPurpose.PHONE_BIND))
                .hasMessage("invalid_phone_challenge");
    }

    @Test
    void enforcesAuthoritativeDatabaseSendLimits() {
        SmsChallengeService service = challenges(new SecureRandom());
        service.issue(SmsPurpose.REGISTER, "15412348963", null, null, IP_HASH);
        assertThatThrownBy(() -> service.issue(
                SmsPurpose.REGISTER, "15412348963", null, null, IP_HASH))
                .hasMessage("sms_rate_limited_60_seconds");

        for (int issued = 1; issued < 5; issued++) {
            clock.advance(Duration.ofSeconds(61));
            service.issue(SmsPurpose.REGISTER, "15412348963", null, null,
                    Integer.toHexString(issued).repeat(64).substring(0, 64));
        }
        clock.advance(Duration.ofSeconds(61));
        assertThatThrownBy(() -> service.issue(
                SmsPurpose.REGISTER, "15412348963", null, null, "f".repeat(64)))
                .hasMessage("sms_rate_limited_phone_hour");

        seedChallenges("15512348963", null, 20, Duration.ofMinutes(10));
        assertThatThrownBy(() -> service.issue(
                SmsPurpose.REGISTER, "15512348963", null, null, "e".repeat(64)))
                .hasMessage("sms_rate_limited_phone_day");

        seedChallenges(null, "d".repeat(64), 20, Duration.ofMinutes(2));
        assertThatThrownBy(() -> service.issue(
                SmsPurpose.REGISTER, "15612348963", null, null, "d".repeat(64)))
                .hasMessage("sms_rate_limited_ip_hour");
    }

    @Test
    void allowsSamePhoneAndPurposeAtExactSixtySecondBoundary() {
        SmsChallengeService service = challenges(new SecureRandom());
        service.issue(SmsPurpose.REGISTER, "15412348963", null, null, IP_HASH);

        clock.advance(Duration.ofSeconds(60));

        assertThat(service.issue(
                SmsPurpose.REGISTER, "15412348963", null, null, "c".repeat(64)).purpose())
                .isEqualTo(SmsPurpose.REGISTER);
    }

    @Test
    void consumesPhoneChallengeUsingOneAuthoritativeInstantAtExpiryBoundary() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        SmsChallengeService service = challenges(new SecureRandom());
        SmsChallengeService.SmsChallenge challenge = service.issue(
                SmsPurpose.PHONE_BIND, "15612348963", tenantId, userId, IP_HASH);
        service.verifyOwned(challenge.id(), deliveredCode(challenge.id()), tenantId, userId);
        java.time.Clock boundaryClock = new StepClock(
                challenge.expiresAt().minusNanos(1), challenge.expiresAt().plusNanos(1));
        SmsChallengeService boundaryService = new SmsChallengeService(
                new SmsChallengeRepository(jdbc), transactions, phones, notifications(), proofs(new SecureRandom()),
                boundaryClock, new SecureRandom(), CODE_PEPPER, true);

        assertThat(boundaryService.consumeVerifiedPhoneChallenge(
                challenge.id(), tenantId, userId, SmsPurpose.PHONE_BIND).challengeId())
                .isEqualTo(challenge.id());
    }

    @Test
    void rejectsInvalidCodePepperEncodingOrLength() {
        assertThatThrownBy(() -> new SmsChallengeService(
                new SmsChallengeRepository(jdbc), transactions, phones, notifications(), proofs(new SecureRandom()),
                clock, new SecureRandom(), "not-base64", true))
                .hasMessage("invalid_sms_code_pepper");
        assertThatThrownBy(() -> new SmsChallengeService(
                new SmsChallengeRepository(jdbc), transactions, phones, notifications(), proofs(new SecureRandom()),
                clock, new SecureRandom(), Base64.getEncoder().encodeToString(new byte[31]), true))
                .hasMessage("invalid_sms_code_pepper");
    }

    @Test
    void requestFingerprintUsesIndependentValidatedKeyAndDisabledModeBootsWithoutIt() {
        String requestKey = Base64.getEncoder().encodeToString(
                "3".repeat(32).getBytes(StandardCharsets.US_ASCII));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.41");

        assertThat(new SmsRequestFingerprintService(requestKey, true).fingerprint(request))
                .isEqualTo(hmacHex("sms-request-ip:v1:203.0.113.41", "3".repeat(32)))
                .isNotEqualTo(hmacHex("sms-request-ip:v1:203.0.113.41", "2".repeat(32)));
        assertThatThrownBy(() -> new SmsRequestFingerprintService("", true))
                .hasMessage("sms_request_ip_key_required");
        assertThatThrownBy(() -> new SmsRequestFingerprintService("not-base64", true))
                .hasMessage("invalid_sms_request_ip_key");
        assertThatThrownBy(() -> new SmsRequestFingerprintService(
                Base64.getEncoder().encodeToString(new byte[31]), true))
                .hasMessage("invalid_sms_request_ip_key");
        SmsRequestFingerprintService disabled = new SmsRequestFingerprintService("", false);
        assertThatThrownBy(() -> disabled.fingerprint(request))
                .isInstanceOfSatisfying(SmsApiException.class,
                        error -> assertThat(error.code()).isEqualTo("sms_disabled"));
    }

    @Test
    void smsApiErrorsExposeOnlyAllowlistedCodesAndStatuses() {
        SmsApiException internal = SmsApiException.from(new IllegalStateException("database_timeout"));
        assertThat(internal.code()).isEqualTo("sms_request_failed");
        assertThat(internal.status()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        SmsApiException limited = SmsApiException.from(
                new IllegalStateException("sms_rate_limited_ip_hour"));
        assertThat(limited.code()).isEqualTo("sms_rate_limited_ip_hour");
        assertThat(limited.status()).isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void validatesPurposeOwnershipAndExactActiveVerificationTemplate() {
        SmsChallengeService service = challenges(new SecureRandom());
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        bindPhone(userId, "15712348963");

        assertThatThrownBy(() -> service.issue(
                SmsPurpose.CHANGE_PASSWORD, "15712348963", null, null, IP_HASH))
                .hasMessage("authenticated_sms_scope_required");
        assertThatThrownBy(() -> service.issue(
                SmsPurpose.REGISTER, "15712348963", tenantId, userId, IP_HASH))
                .hasMessage("public_sms_scope_required");

        jdbc.update("""
                UPDATE platform.sms_template SET integration_status = 'PENDING_INTEGRATION'
                WHERE template_key = 'PASSWORD_CODE'
                """);
        assertThatThrownBy(() -> service.issue(
                SmsPurpose.CHANGE_PASSWORD, "15712348963", tenantId, userId, IP_HASH))
                .hasMessage("sms_template_not_active");
        jdbc.update("""
                UPDATE platform.sms_template SET integration_status = 'ACTIVE'
                WHERE template_key = 'PASSWORD_CODE'
                """);
    }

    @Test
    void failsClosedWhenSmsIsDisabled() {
        SmsChallengeService disabled = challenges(new SecureRandom(), false);
        assertThatThrownBy(() -> disabled.issue(
                SmsPurpose.REGISTER, "15812348963", null, null, IP_HASH))
                .hasMessage("sms_disabled");
    }

    @Test
    void issuesAndConsumesLoginChallengeForActiveVerifiedPhone() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertUser(tenantId, userId, true);
        bindPhone(userId, "15912348963");
        SmsChallengeService service = challenges(new SecureRandom());

        SmsChallengeService.SmsChallenge challenge = service.issueLogin("15912348963", IP_HASH);

        assertThat(challenge.purpose()).isEqualTo(SmsPurpose.LOGIN);
        assertThat(challenge.maskedPhone()).isEqualTo("159****8963");
        assertThat(jdbc.queryForMap("""
                SELECT tenant_id, user_id, purpose
                FROM platform.sms_verification_challenge WHERE id = ?
                """, challenge.id()))
                .containsEntry("tenant_id", tenantId)
                .containsEntry("user_id", userId)
                .containsEntry("purpose", "LOGIN");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.sms_delivery
                WHERE deduplication_key = ? AND template_key = 'LOGIN_CODE'
                """, Integer.class, "verification:" + challenge.id())).isOne();

        SmsChallengeService.VerifiedChallenge verified = service.verifyLogin(
                challenge.id(), deliveredCode(challenge.id()));

        assertThat(verified.tenantId()).isEqualTo(tenantId);
        assertThat(verified.userId()).isEqualTo(userId);
        assertThat(verified.purpose()).isEqualTo(SmsPurpose.LOGIN);
        assertThat(jdbc.queryForObject("""
                SELECT verified_at IS NOT NULL AND consumed_at IS NOT NULL
                FROM platform.sms_verification_challenge WHERE id = ?
                """, Boolean.class, challenge.id())).isTrue();
        assertThatThrownBy(() -> service.verifyLogin(challenge.id(), "000000"))
                .hasMessage("challenge_consumed");
    }

    @Test
    void unavailablePhoneIsRejectedWithoutChallengeOrDelivery() {
        SmsChallengeService service = challenges(new SecureRandom());

        assertThatThrownBy(() -> service.issueLogin("15812348963", IP_HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sms_login_unavailable");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.sms_verification_challenge",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform.sms_delivery",
                Integer.class)).isZero();
    }

    private SmsChallengeService challenges(SecureRandom random) {
        return challenges(random, true);
    }

    private SmsChallengeService challenges(SecureRandom random, boolean enabled) {
        return new SmsChallengeService(
                new SmsChallengeRepository(jdbc), transactions, phones, notifications(), proofs(new SecureRandom()),
                clock, random, CODE_PEPPER, enabled);
    }

    private String hmacHex(String value, String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private String deliveredCode(UUID challengeId) throws Exception {
        byte[] ciphertext = jdbc.queryForObject("""
                SELECT sensitive_variables_ciphertext FROM platform.sms_delivery
                WHERE deduplication_key = ?
                """, byte[].class, "verification:" + challengeId);
        return objectMapper.readTree(encryption.decrypt(ciphertext)).get("code").asText();
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private void seedChallenges(
            String phone, String requestIpHash, int count, Duration spacing) {
        String lookupHash = phones.protect(phone == null ? "15912348963" : phone).lookupHash();
        for (int index = 0; index < count; index++) {
            java.time.Instant createdAt = clock.instant().minus(spacing.multipliedBy(index + 1L));
            jdbc.update("""
                    INSERT INTO platform.sms_verification_challenge(
                        id, purpose, phone_ciphertext, phone_lookup_hash, phone_last4,
                        code_hash, expires_at, request_ip_hash, created_at)
                    VALUES (?, 'REGISTER', ?, ?, '8963', ?, ?, ?, ?)
                    """, UUID.randomUUID(), encryption.encrypt("+8615912348963".getBytes(StandardCharsets.UTF_8)),
                    lookupHash, "b".repeat(64),
                    java.sql.Timestamp.from(createdAt.plus(Duration.ofMinutes(5))),
                    requestIpHash == null ? UUID.randomUUID().toString().replace("-", "").repeat(2) : requestIpHash,
                    java.sql.Timestamp.from(createdAt));
        }
    }

    private static final class StepClock extends java.time.Clock {
        private final Instant[] values;
        private int index;

        private StepClock(Instant... values) {
            this.values = values;
        }

        @Override
        public ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return values[Math.min(index++, values.length - 1)];
        }
    }
}
