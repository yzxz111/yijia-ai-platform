package com.yuzhihuihe.apiplatform.gateway.safety;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class AccountEnforcementRepository {

    static final String CURRENT_SQL =
            """
            SELECT state, reason_code, case_no, expires_at
            FROM gateway.safety_enforcement_cache
            WHERE tenant_id = :tenantId AND user_id = :userId
              AND state IN ('FROZEN', 'BANNED', 'CLEARED')
            """;

    private final DatabaseClient database;

    public AccountEnforcementRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<EnforcementRow> current(UUID tenantId, UUID userId) {
        return database.sql(CURRENT_SQL)
                .bind("tenantId", tenantId)
                .bind("userId", userId)
                .map(
                        (row, metadata) ->
                                new EnforcementRow(
                                        row.get("state", String.class),
                                        row.get("reason_code", String.class),
                                        row.get("case_no", String.class)))
                .one();
    }

    record EnforcementRow(String state, String reasonCode, String caseNo) {}
}
