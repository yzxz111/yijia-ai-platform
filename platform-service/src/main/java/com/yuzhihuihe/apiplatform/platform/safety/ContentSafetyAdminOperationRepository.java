package com.yuzhihuihe.apiplatform.platform.safety;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ContentSafetyAdminOperationRepository {

    private final JdbcTemplate jdbc;

    ContentSafetyAdminOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int tryCreate(
            UUID operationId,
            UUID actorUserId,
            String action,
            String idempotencyKey,
            String requestSha256) {
        return jdbc.update("""
                INSERT INTO platform.content_safety_admin_operation(
                    id, actor_user_id, action, idempotency_key, request_sha256)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (actor_user_id, action, idempotency_key) DO NOTHING
                """, operationId, actorUserId, action, idempotencyKey, requestSha256);
    }

    OperationRow lock(UUID actorUserId, String action, String idempotencyKey) {
        return jdbc.query("""
                        SELECT id, request_sha256, response_json::text
                        FROM platform.content_safety_admin_operation
                        WHERE actor_user_id = ? AND action = ? AND idempotency_key = ?
                        FOR UPDATE
                        """,
                (result, rowNumber) -> new OperationRow(
                        result.getObject("id", UUID.class),
                        result.getString("request_sha256"),
                        result.getString("response_json")),
                actorUserId, action, idempotencyKey).stream().findFirst().orElse(null);
    }

    void complete(
            UUID operationId,
            String responseJson,
            String resourceType,
            String resourceId) {
        int updated = jdbc.update("""
                UPDATE platform.content_safety_admin_operation
                SET response_json = ?::jsonb,
                    resource_type = ?,
                    resource_id = ?,
                    completed_at = now()
                WHERE id = ? AND completed_at IS NULL
                """, responseJson, resourceType, resourceId, operationId);
        if (updated != 1) {
            throw new IllegalStateException("content_safety_operation_completion_failed");
        }
    }

    void auditSuccess(
            UUID operationId,
            UUID tenantId,
            UUID actorUserId,
            String action,
            String resourceType,
            String resourceId,
            String idempotencyKey,
            String requestSha256) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type,
                    resource_id, request_id, outcome, detail)
                VALUES (?, ?, ?, ?, ?, ?, 'SUCCESS',
                    jsonb_build_object(
                        'operationId', ?::text,
                        'idempotencyKey', ?::text,
                        'requestSha256', ?::text))
                """, tenantId, actorUserId, action, resourceType, resourceId,
                idempotencyKey, operationId.toString(), idempotencyKey, requestSha256);
    }

    record OperationRow(UUID operationId, String requestSha256, String responseJson) {
    }
}
