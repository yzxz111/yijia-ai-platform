package com.yuzhihuihe.apiplatform.platform.sms;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class SmsChallengeRepository {

    private final JdbcTemplate jdbc;

    SmsChallengeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<byte[]> boundPhoneRecipients(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT phone_ciphertext
                FROM platform.app_user
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                  AND phone_verified_at IS NOT NULL
                """, (result, rowNumber) -> result.getBytes("phone_ciphertext"),
                tenantId, userId);
    }

    List<LoginRecipientRow> loginRecipients(String phoneLookupHash) {
        return jdbc.query("""
                SELECT tenant_id, id
                FROM platform.app_user
                WHERE phone_lookup_hash = ? AND phone_verified_at IS NOT NULL
                  AND status IN ('ACTIVE', 'DISABLED')
                LIMIT 2
                """, (result, rowNumber) -> new LoginRecipientRow(
                result.getObject("tenant_id", UUID.class),
                result.getObject("id", UUID.class)), phoneLookupHash);
    }

    List<LoginRecipientRow> passwordResetRecipients(String phoneLookupHash) {
        return jdbc.query("""
                SELECT tenant_id, id
                FROM platform.app_user
                WHERE phone_lookup_hash = ? AND phone_verified_at IS NOT NULL
                  AND status = 'ACTIVE'
                LIMIT 2
                """, (result, rowNumber) -> new LoginRecipientRow(
                result.getObject("tenant_id", UUID.class),
                result.getObject("id", UUID.class)), phoneLookupHash);
    }

    List<RegistrationRow> consumeRegistration(UUID challengeId, Instant now) {
        return jdbc.query("""
                UPDATE platform.sms_verification_challenge
                SET consumed_at = ?
                WHERE id = ? AND purpose = 'REGISTER' AND verified_at IS NOT NULL
                  AND consumed_at IS NULL AND expires_at >= ?
                RETURNING id, phone_ciphertext, phone_lookup_hash, phone_last4
                """, (result, rowNumber) -> new RegistrationRow(
                result.getObject("id", UUID.class), result.getBytes("phone_ciphertext"),
                result.getString("phone_lookup_hash"), result.getString("phone_last4")),
                Timestamp.from(now), challengeId, Timestamp.from(now));
    }

    List<RegistrationRow> inspectVerifiedRegistration(UUID challengeId, Instant now) {
        return jdbc.query("""
                SELECT id, phone_ciphertext, phone_lookup_hash, phone_last4
                FROM platform.sms_verification_challenge
                WHERE id = ? AND purpose = 'REGISTER' AND verified_at IS NOT NULL
                  AND consumed_at IS NULL AND expires_at >= ?
                """, (result, rowNumber) -> new RegistrationRow(
                result.getObject("id", UUID.class), result.getBytes("phone_ciphertext"),
                result.getString("phone_lookup_hash"), result.getString("phone_last4")),
                challengeId, Timestamp.from(now));
    }

    List<PhoneRow> consumePhone(UUID challengeId, UUID tenantId, UUID userId,
            SmsPurpose purpose, Instant now) {
        return jdbc.query("""
                UPDATE platform.sms_verification_challenge
                SET consumed_at = ?
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND purpose = ?
                  AND verified_at IS NOT NULL AND consumed_at IS NULL AND expires_at >= ?
                RETURNING id, tenant_id, user_id, purpose, phone_ciphertext,
                          phone_lookup_hash, phone_last4
                """, (result, rowNumber) -> new PhoneRow(
                result.getObject("id", UUID.class), result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                SmsPurpose.valueOf(result.getString("purpose")),
                result.getBytes("phone_ciphertext"), result.getString("phone_lookup_hash"),
                result.getString("phone_last4")), Timestamp.from(now), challengeId,
                tenantId, userId, purpose.name(), Timestamp.from(now));
    }

    void insertChallenge(UUID id, UUID tenantId, UUID userId, SmsPurpose purpose,
            byte[] phoneCiphertext, String phoneLookupHash, String phoneLast4,
            String codeHash, Instant expiresAt, String requestIpHash, Instant now) {
        jdbc.update("""
                INSERT INTO platform.sms_verification_challenge(
                    id, tenant_id, user_id, purpose, phone_ciphertext,
                    phone_lookup_hash, phone_last4, code_hash, expires_at,
                    request_ip_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, userId, purpose.name(), phoneCiphertext,
                phoneLookupHash, phoneLast4, codeHash,
                Timestamp.from(expiresAt), requestIpHash, Timestamp.from(now));
    }

    List<ChallengeRow> lockChallenge(UUID challengeId) {
        return jdbc.query("""
                SELECT id, tenant_id, user_id, purpose, phone_ciphertext, code_hash,
                       expires_at, attempt_count, verified_at, consumed_at
                FROM platform.sms_verification_challenge
                WHERE id = ? FOR UPDATE
                """, (result, rowNumber) -> new ChallengeRow(
                result.getObject("id", UUID.class), result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                SmsPurpose.valueOf(result.getString("purpose")),
                result.getBytes("phone_ciphertext"), result.getString("code_hash"),
                result.getTimestamp("expires_at").toInstant(), result.getInt("attempt_count"),
                result.getTimestamp("verified_at") == null
                        ? null : result.getTimestamp("verified_at").toInstant(),
                result.getTimestamp("consumed_at") == null
                        ? null : result.getTimestamp("consumed_at").toInstant()), challengeId);
    }

    void updateAttemptCount(UUID challengeId, int attempts) {
        jdbc.update("""
                UPDATE platform.sms_verification_challenge
                SET attempt_count = ? WHERE id = ?
                """, attempts, challengeId);
    }

    /**
     * 相对自增尝试次数（数据库端 +1）。
     *
     * <p>用在「错码计数必须落库、而外层事务注定回滚」的场景：此时既不能先读再写绝对值
     * （并发下会丢更新），也不能依赖外层事务（会被一起撤销）。
     */
    void incrementAttemptCount(UUID challengeId) {
        jdbc.update("""
                UPDATE platform.sms_verification_challenge
                SET attempt_count = attempt_count + 1 WHERE id = ?
                """, challengeId);
    }

    void markVerifiedAndConsumed(UUID challengeId, Instant now) {
        jdbc.update("""
                UPDATE platform.sms_verification_challenge
                SET verified_at = ?, consumed_at = ? WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), challengeId);
    }

    void markVerified(UUID challengeId, Instant now) {
        jdbc.update("""
                UPDATE platform.sms_verification_challenge
                SET verified_at = ? WHERE id = ?
                """, Timestamp.from(now), challengeId);
    }

    void markRegistrationVerified(UUID challengeId, Instant now, Instant completionExpiresAt) {
        jdbc.update("""
                UPDATE platform.sms_verification_challenge
                SET verified_at = ?, expires_at = ?
                WHERE id = ? AND purpose = 'REGISTER'
                """, Timestamp.from(now), Timestamp.from(completionExpiresAt), challengeId);
    }

    long recentPurposeCount(
            String phoneHash, SmsPurpose purpose, Instant after) {
        return count("""
                SELECT count(*) FROM platform.sms_verification_challenge
                WHERE phone_lookup_hash = ? AND purpose = ? AND created_at > ?
                  AND (purpose NOT IN ('REGISTER', 'LOGIN') OR consumed_at IS NULL)
                """, phoneHash, purpose.name(), Timestamp.from(after));
    }

    long phoneDayCount(String phoneHash, Instant now) {
        return count("""
                SELECT count(*) FROM platform.sms_verification_challenge
                WHERE phone_lookup_hash = ? AND created_at > ?
                """, phoneHash, Timestamp.from(now.minus(Duration.ofDays(1))));
    }

    long phoneHourPurposeCount(String phoneHash, SmsPurpose purpose, Instant now) {
        return count("""
                SELECT count(*) FROM platform.sms_verification_challenge
                WHERE phone_lookup_hash = ? AND purpose = ? AND created_at > ?
                """, phoneHash, purpose.name(), Timestamp.from(now.minus(Duration.ofHours(1))));
    }

    long ipHourCount(String requestIpHash, Instant now) {
        return count("""
                SELECT count(*) FROM platform.sms_verification_challenge
                WHERE request_ip_hash = ? AND created_at > ?
                """, requestIpHash, Timestamp.from(now.minus(Duration.ofHours(1))));
    }

    void lockRateLimits(List<String> keys) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                for (String key : keys) {
                    statement.setString(1, key);
                    statement.execute();
                }
            }
            return null;
        });
    }

    List<UserPhoneRow> userPhone(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT phone_lookup_hash, phone_verified_at IS NOT NULL AS verified
                FROM platform.app_user WHERE tenant_id = ? AND id = ?
                """, (result, rowNumber) -> new UserPhoneRow(
                result.getString("phone_lookup_hash"), result.getBoolean("verified")),
                tenantId, userId);
    }

    long activeTemplateCount(SmsPurpose purpose) {
        return count("""
                SELECT count(*) FROM platform.sms_template
                WHERE template_key = ? AND template_type = 'VERIFICATION'
                  AND integration_status = 'ACTIVE'
                """, purpose.templateKey());
    }

    private long count(String sql, Object... arguments) {
        Long count = jdbc.queryForObject(sql, Long.class, arguments);
        return count == null ? 0L : count;
    }

    record LoginRecipientRow(UUID tenantId, UUID userId) {
    }

    record RegistrationRow(
            UUID challengeId, byte[] phoneCiphertext,
            String phoneLookupHash, String phoneLast4) {
    }

    record PhoneRow(
            UUID challengeId, UUID tenantId, UUID userId, SmsPurpose purpose,
            byte[] phoneCiphertext, String phoneLookupHash, String phoneLast4) {
    }

    record ChallengeRow(
            UUID id, UUID tenantId, UUID userId, SmsPurpose purpose,
            byte[] phoneCiphertext, String codeHash, Instant expiresAt,
            int attemptCount, Instant verifiedAt, Instant consumedAt) {
    }

    record UserPhoneRow(String lookupHash, boolean verified) {
    }
}
