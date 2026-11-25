package com.yuzhihuihe.apiplatform.platform.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class DeviceLoginRecoveryRepository {
    private final JdbcTemplate jdbc;
    DeviceLoginRecoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void insert(Request row) {
        jdbc.update("DELETE FROM platform.login_device_recovery WHERE user_id = ? AND device_hash = ?",
                row.userId(), row.deviceHash());
        jdbc.update("""
                INSERT INTO platform.login_device_recovery
                    (id, tenant_id, user_id, device_hash, token_hash, csrf_hash, auth_version, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, row.id(), row.tenantId(), row.userId(), row.deviceHash(), row.tokenHash(),
                row.csrfHash(), row.authVersion(), Timestamp.from(row.expiresAt()));
    }

    Optional<Request> find(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM platform.login_device_recovery WHERE id = ?"
                + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Request(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("device_hash"),
                        rs.getString("token_hash"), rs.getString("csrf_hash"), rs.getLong("auth_version"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()), id).stream().findFirst();
    }

    List<Device> devices(UUID userId, UUID tenantId) {
        return jdbc.query("""
                SELECT id, created_at FROM platform.terminal_session
                WHERE user_id = ? AND tenant_id = ? AND client_type = 'DESKTOP_WEB'
                  AND status = 'ACTIVE' AND expires_at > now()
                ORDER BY created_at, id
                """, (rs, n) -> new Device(rs.getObject("id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()), userId, tenantId);
    }

    void consume(UUID id) { jdbc.update("DELETE FROM platform.login_device_recovery WHERE id = ?", id); }

    void deleteExpired() { jdbc.update("DELETE FROM platform.login_device_recovery WHERE expires_at <= now()"); }

    record Request(UUID id, UUID tenantId, UUID userId, String deviceHash, String tokenHash,
            String csrfHash, long authVersion, Instant expiresAt) { }
    record Device(UUID id, Instant createdAt) { }
}
