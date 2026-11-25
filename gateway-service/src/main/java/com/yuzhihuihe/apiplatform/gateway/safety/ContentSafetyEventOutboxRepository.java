package com.yuzhihuihe.apiplatform.gateway.safety;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class ContentSafetyEventOutboxRepository {

    static final String INSERT_SQL =
            """
            INSERT INTO gateway.content_safety_event_outbox(
                id, event_type, target_service, event_fingerprint, payload, payload_hash,
                status, attempt_count, next_attempt_at, created_at, updated_at)
            VALUES (:id, :eventType, 'platform-service', :fingerprint, :payload::jsonb,
                    :payloadHash, 'PENDING', 0, now(), now(), now())
            ON CONFLICT (event_fingerprint) DO NOTHING
            """;

    private final DatabaseClient database;

    public ContentSafetyEventOutboxRepository(DatabaseClient database) {
        this.database = database;
    }

    Mono<Void> insert(OutboxRecord event) {
        return database.sql(INSERT_SQL)
                .bind("id", event.id())
                .bind("eventType", event.eventType())
                .bind("fingerprint", event.fingerprint())
                .bind("payload", event.payload())
                .bind("payloadHash", event.payloadHash())
                .fetch()
                .rowsUpdated()
                .then();
    }

    record OutboxRecord(
            UUID id, String eventType, String fingerprint, String payload, String payloadHash) {}
}
