package com.yuzhihuihe.apiplatform.platform.email;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class EmailVerificationRepository {

    private final JdbcTemplate jdbc;

    EmailVerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lockEmail(String email) {
        jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            try (var statement = connection.prepareStatement(
                                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                                statement.setString(1, "email:" + email);
                                statement.execute();
                            }
                            return null;
                        });
    }

    int recentChallengeCount(String email, Instant createdAfter) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM platform.email_verification_challenge
                WHERE email = ? AND created_at > ?
                """,
                Integer.class,
                email,
                Timestamp.from(createdAfter));
        return count == null ? 0 : count;
    }

    void insertChallenge(
            UUID id,
            String email,
            String codeHash,
            Instant expiresAt,
            String requestIpHash,
            Instant createdAt) {
        jdbc.update(
                """
                INSERT INTO platform.email_verification_challenge(
                    id, email, code_hash, expires_at, request_ip_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                email,
                codeHash,
                Timestamp.from(expiresAt),
                requestIpHash,
                Timestamp.from(createdAt));
    }

    Optional<ChallengeRow> lockChallenge(UUID challengeId, String email) {
        return jdbc.query(
                        """
                        SELECT email, code_hash, expires_at, attempt_count, verified_at, consumed_at
                        FROM platform.email_verification_challenge
                        WHERE id = ? AND email = ?
                        FOR UPDATE
                        """,
                        (result, rowNumber) -> new ChallengeRow(
                                result.getString("email"),
                                result.getString("code_hash"),
                                result.getObject("expires_at", OffsetDateTime.class).toInstant(),
                                result.getInt("attempt_count"),
                                result.getObject("verified_at", OffsetDateTime.class),
                                result.getObject("consumed_at", OffsetDateTime.class)),
                        challengeId,
                        email)
                .stream()
                .findFirst();
    }

    void incrementAttempt(UUID challengeId) {
        jdbc.update(
                "UPDATE platform.email_verification_challenge "
                        + "SET attempt_count = attempt_count + 1 WHERE id = ?",
                challengeId);
    }

    void markConsumed(UUID challengeId, Instant consumedAt) {
        Timestamp timestamp = Timestamp.from(consumedAt);
        jdbc.update(
                """
                UPDATE platform.email_verification_challenge
                SET verified_at = ?, consumed_at = ?
                WHERE id = ?
                """,
                timestamp,
                timestamp,
                challengeId);
    }

    void markRegistrationVerified(UUID challengeId, Instant verifiedAt, Instant expiresAt) {
        jdbc.update(
                """
                UPDATE platform.email_verification_challenge
                SET verified_at = ?, expires_at = ?
                WHERE id = ? AND verified_at IS NULL AND consumed_at IS NULL
                """,
                Timestamp.from(verifiedAt), Timestamp.from(expiresAt), challengeId);
    }

    Optional<String> consumeVerifiedRegistration(UUID challengeId, Instant now) {
        return jdbc.query(
                        """
                        UPDATE platform.email_verification_challenge
                        SET consumed_at = ?
                        WHERE id = ? AND verified_at IS NOT NULL
                          AND consumed_at IS NULL AND expires_at > ?
                        RETURNING email
                        """,
                        (result, rowNumber) -> result.getString("email"),
                        Timestamp.from(now), challengeId, Timestamp.from(now))
                .stream().findFirst();
    }

    record ChallengeRow(
            String email,
            String codeHash,
            Instant expiresAt,
            int attemptCount,
            OffsetDateTime verifiedAt,
            OffsetDateTime consumedAt) {}
}
