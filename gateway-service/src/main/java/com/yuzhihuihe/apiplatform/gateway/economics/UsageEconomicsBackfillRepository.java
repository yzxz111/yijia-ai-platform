package com.yuzhihuihe.apiplatform.gateway.economics;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UsageEconomicsBackfillRepository {

    static final String PAGE_SQL =
            """
            SELECT usage.id, usage.created_at
            FROM gateway.usage_log usage
            JOIN gateway.quota_reservation reservation ON reservation.id = usage.reservation_id
            WHERE reservation.status = 'SETTLED'
              AND (usage.created_at, usage.id) > (:cursor_created_at, :cursor_usage_id)
            ORDER BY usage.created_at, usage.id
            LIMIT :fetch_limit
            """;

    private final DatabaseClient database;

    public UsageEconomicsBackfillRepository(DatabaseClient database) {
        this.database = database;
    }

    Flux<CursorRow> page(Instant cursorCreatedAt, UUID cursorUsageId, int fetchLimit) {
        return database.sql(PAGE_SQL)
                .bind("cursor_created_at", cursorCreatedAt)
                .bind("cursor_usage_id", cursorUsageId)
                .bind("fetch_limit", fetchLimit)
                .map(
                        (row, metadata) ->
                                new CursorRow(
                                        row.get("id", UUID.class),
                                        row.get("created_at", Instant.class)))
                .all();
    }

    record CursorRow(UUID id, Instant createdAt) {}
}
