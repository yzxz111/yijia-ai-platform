package com.yuzhihuihe.apiplatform.platform.identity;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class LoginActivityRepository {

    private final JdbcTemplate jdbc;

    LoginActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean knownDevice(UUID userId, String deviceHash) {
        return exists("device_hash", userId, deviceHash);
    }

    boolean knownNetwork(UUID userId, String subnetHmac) {
        return exists("subnet_hmac", userId, subnetHmac);
    }

    String previousRegion(UUID userId) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT region FROM platform.login_activity
                    WHERE user_id = ? AND outcome = 'SUCCEEDED'
                      AND region IS NOT NULL AND region <> 'UNKNOWN'
                    ORDER BY occurred_at DESC LIMIT 1
                    """,
                    String.class,
                    userId);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    void insert(
            UUID tenantId,
            UUID userId,
            String ipHmac,
            String subnetHmac,
            String deviceHash,
            String userAgentHash,
            String region,
            OffsetDateTime occurredAt) {
        jdbc.update(
                """
                INSERT INTO platform.login_activity(
                    id, tenant_id, user_id, ip_hmac, subnet_hmac, device_hash,
                    user_agent_hash, region, outcome, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED', ?)
                """,
                UUID.randomUUID(),
                tenantId,
                userId,
                ipHmac,
                subnetHmac,
                deviceHash,
                userAgentHash,
                region,
                occurredAt);
    }

    void lockEventKey(String eventKey) {
        jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            try (var statement =
                                    connection.prepareStatement(
                                            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                                statement.setString(1, eventKey);
                                statement.execute();
                            }
                            return null;
                        });
    }

    boolean eventExists(String eventKey) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                SELECT EXISTS(SELECT 1 FROM platform.notification_event WHERE event_key = ?)
                """,
                        Boolean.class,
                        eventKey));
    }

    private boolean exists(String column, UUID userId, String hash) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        ("""
                SELECT EXISTS(SELECT 1 FROM platform.login_activity
                WHERE user_id = ? AND %s = ? AND outcome = 'SUCCEEDED')
                """)
                                .formatted(column),
                        Boolean.class,
                        userId,
                        hash));
    }
}
