package com.yuzhihuihe.apiplatform.gateway.safety;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class ContentSafetyCheckRepository {

    static final String INSERT_SQL =
            """
            INSERT INTO gateway.content_safety_check(
                id, request_id, tenant_id, user_id, api_key_id, direction, result,
                policy_version, local_result, semantic_result, file_result,
                duration_ms, safety_fingerprint, created_at)
            VALUES (:id, :requestId, :tenantId, :userId, :apiKeyId, :direction, :result,
                    :policyVersion, :localResult::jsonb, :semanticResult::jsonb, :fileResult::jsonb,
                    :durationMs, :fingerprint, now())
            """;

    private final DatabaseClient database;

    public ContentSafetyCheckRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<Void> insert(CheckRecord check) {
        return database.sql(INSERT_SQL)
                .bind("id", check.id())
                .bind("requestId", check.requestId())
                .bind("tenantId", check.tenantId())
                .bind("userId", check.userId())
                .bind("apiKeyId", check.apiKeyId())
                .bind("direction", check.direction())
                .bind("result", check.result())
                .bind("policyVersion", check.policyVersion())
                .bind("localResult", check.localResult())
                .bind("semanticResult", check.semanticResult())
                .bind("fileResult", check.fileResult())
                .bind("durationMs", check.durationMs())
                .bind("fingerprint", check.safetyFingerprint())
                .fetch()
                .rowsUpdated()
                .then();
    }

    record CheckRecord(
            UUID id,
            String requestId,
            UUID tenantId,
            UUID userId,
            UUID apiKeyId,
            String direction,
            String result,
            long policyVersion,
            String localResult,
            String semanticResult,
            String fileResult,
            long durationMs,
            String safetyFingerprint) {}
}
