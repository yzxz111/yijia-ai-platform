package com.yuzhihuihe.apiplatform.platform.grant;

import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PackageFulfillmentRepository {

    private final JdbcTemplate jdbc;

    PackageFulfillmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lockBusinessNumber(String businessNo) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, businessNo);
                statement.execute();
                return null;
            }
        });
    }

    Optional<GrantUserRow> findActiveUser(UUID userId, UUID tenantId) {
        return jdbc
                .query(
                        """
                        SELECT id, tenant_id, email
                        FROM platform.app_user
                        WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE'
                        FOR SHARE
                        """,
                        (result, rowNumber) ->
                                new GrantUserRow(
                                        result.getObject("id", UUID.class),
                                        result.getObject("tenant_id", UUID.class),
                                        result.getString("email")),
                        userId,
                        tenantId)
                .stream()
                .findFirst();
    }

    Optional<ExistingGrantRow> findExistingGrant(String businessNo) {
        return jdbc
                .query(
                        """
                        SELECT g.id, g.tenant_id, g.user_id, g.package_plan_version_id,
                               g.source, g.reason, g.created_by, g.status,
                               o.id AS event_id, o.payload
                        FROM platform.grant_request g
                        LEFT JOIN platform.outbox_event o
                          ON o.business_key = g.business_no
                         AND o.aggregate_type = 'GRANT'
                         AND o.aggregate_id = g.id
                        WHERE g.business_no = ?
                        FOR SHARE OF g
                        """,
                        (result, rowNumber) ->
                                new ExistingGrantRow(
                                        result.getObject("id", UUID.class),
                                        result.getObject("tenant_id", UUID.class),
                                        result.getObject("user_id", UUID.class),
                                        result.getObject("package_plan_version_id", UUID.class),
                                        result.getString("source"),
                                        result.getString("reason"),
                                        result.getObject("created_by", UUID.class),
                                        result.getString("status"),
                                        result.getObject("event_id", UUID.class),
                                        result.getString("payload")),
                        businessNo)
                .stream()
                .findFirst();
    }

    void insertGrantRequest(
            UUID grantId,
            String businessNo,
            UUID tenantId,
            UUID userId,
            UUID packageVersionId,
            String source,
            String reason,
            UUID actorId) {
        jdbc.update(
                """
                INSERT INTO platform.grant_request(
                    id, business_no, tenant_id, user_id, package_plan_version_id,
                    status, source, reason, created_by)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?)
                """,
                grantId,
                businessNo,
                tenantId,
                userId,
                packageVersionId,
                source,
                reason,
                actorId);
    }

    void insertOutboxEvent(UUID eventId, UUID grantId, String businessNo, String payloadJson) {
        jdbc.update(
                """
                INSERT INTO platform.outbox_event(
                    id, aggregate_type, aggregate_id, event_type, business_key, payload)
                VALUES (?, 'GRANT', ?, 'PACKAGE_GRANTED_V1', ?, ?::jsonb)
                """,
                eventId,
                grantId,
                businessNo,
                payloadJson);
    }

    void insertAdminAuditLog(
            UUID tenantId, UUID actorId, UUID grantId, String detailJson) {
        jdbc.update(
                """
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type,
                    resource_id, outcome, detail)
                VALUES (?, ?, 'PACKAGE_GRANT_CREATED', 'GRANT', ?, 'SUCCESS', ?::jsonb)
                """,
                tenantId,
                actorId,
                grantId.toString(),
                detailJson);
    }

    record GrantUserRow(UUID id, UUID tenantId, String email) {}

    record ExistingGrantRow(
            UUID grantId,
            UUID tenantId,
            UUID userId,
            UUID packageVersionId,
            String source,
            String reason,
            UUID actorId,
            String status,
            UUID eventId,
            String payload) {}
}
