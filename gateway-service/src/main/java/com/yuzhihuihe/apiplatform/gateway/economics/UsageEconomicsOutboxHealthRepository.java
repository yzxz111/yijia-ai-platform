package com.yuzhihuihe.apiplatform.gateway.economics;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class UsageEconomicsOutboxHealthRepository {

    static final String STATUS_SQL =
            """
            SELECT count(*) FILTER (WHERE status='PENDING') pending,
                   count(*) FILTER (WHERE status='SENDING') sending,
                   count(*) FILTER (WHERE status='FAILED') failed,
                   count(*) FILTER (WHERE status='DEAD') dead,
                   min(created_at) FILTER (WHERE status='PENDING') oldest_pending
            FROM gateway.usage_economics_outbox
            """;
    static final String EVENT_SQL =
            """
            SELECT event_id, payload_hash, created_at
            FROM gateway.usage_economics_outbox
            WHERE status='SENT' AND created_at >= :range_from AND created_at < :range_to
              AND (created_at, event_id) > (:cursor_created_at, :cursor_event_id)
            ORDER BY created_at, event_id
            LIMIT :fetch_limit
            """;

    private final DatabaseClient database;

    public UsageEconomicsOutboxHealthRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<StatusRow> status() {
        return database.sql(STATUS_SQL)
                .map(
                        (row, metadata) ->
                                new StatusRow(
                                        required(row.get("pending", Long.class)),
                                        required(row.get("sending", Long.class)),
                                        required(row.get("failed", Long.class)),
                                        required(row.get("dead", Long.class)),
                                        row.get("oldest_pending", Instant.class)))
                .one();
    }

    Flux<SourceEventRow> events(
            Instant from, Instant to, Instant cursorCreatedAt, UUID cursorEventId, int fetchLimit) {
        return database.sql(EVENT_SQL)
                .bind("range_from", from)
                .bind("range_to", to)
                .bind("cursor_created_at", cursorCreatedAt)
                .bind("cursor_event_id", cursorEventId)
                .bind("fetch_limit", fetchLimit)
                .map(
                        (row, metadata) ->
                                new SourceEventRow(
                                        row.get("event_id", UUID.class),
                                        row.get("payload_hash", String.class),
                                        row.get("created_at", Instant.class)))
                .all();
    }

    private static long required(Long value) {
        return value == null ? 0 : value;
    }

    record StatusRow(long pending, long sending, long failed, long dead, Instant oldestPending) {}

    record SourceEventRow(UUID eventId, String payloadHash, Instant createdAt) {}
}
