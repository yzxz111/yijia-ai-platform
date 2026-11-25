package com.yuzhihuihe.apiplatform.platform.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 持久记录用户在账号创建时所给出的服务条款与隐私政策同意。首次接受会被保留； 之后对更新文档版本的接受， 仅推进所记录的版本号。 */
@Repository
class IdentityConsentRepository {

    private final JdbcTemplate jdbc;

    IdentityConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void record(UUID tenantId, UUID userId, String document, String version, String source) {
        record(tenantId, userId, document, version, source, Instant.now());
    }

    void record(
            UUID tenantId,
            UUID userId,
            String document,
            String version,
            String source,
            Instant acceptedAt) {
        jdbc.update(
                """
                INSERT INTO platform.user_consent(
                    id, tenant_id, user_id, document, version, source, accepted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, document) DO UPDATE SET
                    version = EXCLUDED.version,
                    source = EXCLUDED.source
                WHERE platform.user_consent.version IS DISTINCT FROM EXCLUDED.version
                """,
                UUID.randomUUID(),
                tenantId,
                userId,
                document,
                version,
                source,
                Timestamp.from(acceptedAt));
    }

    Optional<RecordedConsent> find(UUID userId, String document) {
        return jdbc
                .query(
                        """
                        SELECT version, source, accepted_at
                        FROM platform.user_consent
                        WHERE user_id = ? AND document = ?
                        """,
                        (result, row) ->
                                new RecordedConsent(
                                        result.getString("version"),
                                        result.getString("source"),
                                        result.getTimestamp("accepted_at").toInstant()),
                        userId,
                        document)
                .stream()
                .findFirst();
    }

    record RecordedConsent(String version, String source, Instant acceptedAt) {}
}
