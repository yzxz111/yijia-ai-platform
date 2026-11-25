package com.yuzhihuihe.apiplatform.gateway.operations;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class OperationsRepository {

    private final DatabaseClient database;

    OperationsRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<Long> countExpiredReservations() {
        return count("""
                SELECT count(*) FROM gateway.quota_reservation
                WHERE status = 'RESERVED' AND expires_at <= now()
                """);
    }

    Mono<Long> countCleanupCandidates() {
        return count("""
                SELECT count(*) FROM gateway.file_object
                WHERE (status IN ('READY', 'FAILED') AND expires_at <= now())
                   OR (status = 'DELETING' AND updated_at <= now() - interval '5 minutes')
                """);
    }

    Mono<UUID> insert(
            UUID operationId,
            String action,
            UUID tenantId,
            UUID actorUserId,
            String idempotencyKey,
            String requestId) {
        return database.sql("""
                        INSERT INTO gateway.admin_operation_audit(
                            operation_id, idempotency_key, tenant_id, actor_user_id,
                            action, request_id, status)
                        VALUES (:operation_id, :idempotency_key, :tenant_id, :actor_user_id,
                            :action, :request_id, 'RUNNING')
                        ON CONFLICT (tenant_id, actor_user_id, action, idempotency_key) DO NOTHING
                        RETURNING operation_id
                        """)
                .bind("operation_id", operationId)
                .bind("idempotency_key", idempotencyKey)
                .bind("tenant_id", tenantId)
                .bind("actor_user_id", actorUserId)
                .bind("action", action)
                .bind("request_id", requestId)
                .map((row, metadata) -> row.get("operation_id", UUID.class))
                .one();
    }

    Mono<Void> complete(UUID operationId, Integer affected) {
        DatabaseClient.GenericExecuteSpec update = database.sql("""
                        UPDATE gateway.admin_operation_audit
                        SET status = 'COMPLETED', affected = :affected,
                            completed_at = now(), updated_at = now()
                        WHERE operation_id = :operation_id AND status = 'RUNNING'
                        """)
                .bind("operation_id", operationId);
        update = affected == null
                ? update.bindNull("affected", Integer.class)
                : update.bind("affected", affected);
        return update.fetch().rowsUpdated().then();
    }

    Mono<Void> fail(UUID operationId, String failureSummary) {
        return database.sql("""
                        UPDATE gateway.admin_operation_audit
                        SET status = 'FAILED', failure_summary = :failure_summary,
                            completed_at = now(), updated_at = now()
                        WHERE operation_id = :operation_id AND status = 'RUNNING'
                        """)
                .bind("operation_id", operationId)
                .bind("failure_summary", failureSummary)
                .fetch().rowsUpdated().then();
    }

    Mono<OperationRow> find(
            String action, UUID tenantId, UUID actorUserId, String idempotencyKey) {
        return database.sql("""
                        SELECT status, affected
                        FROM gateway.admin_operation_audit
                        WHERE tenant_id = :tenant_id
                          AND actor_user_id = :actor_user_id
                          AND action = :action
                          AND idempotency_key = :idempotency_key
                        """)
                .bind("tenant_id", tenantId)
                .bind("actor_user_id", actorUserId)
                .bind("action", action)
                .bind("idempotency_key", idempotencyKey)
                .map((row, metadata) -> new OperationRow(
                        row.get("status", String.class), row.get("affected", Integer.class)))
                .one();
    }

    private Mono<Long> count(String sql) {
        return database.sql(sql)
                .map((row, metadata) -> {
                    Long value = row.get(0, Long.class);
                    return value == null ? 0L : value;
                })
                .one();
    }

    record OperationRow(String status, Integer affected) {}
}
