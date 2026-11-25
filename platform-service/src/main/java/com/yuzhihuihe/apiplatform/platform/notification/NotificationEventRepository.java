package com.yuzhihuihe.apiplatform.platform.notification;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class NotificationEventRepository {

    private final JdbcTemplate jdbc;

    NotificationEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean insert(
            UUID eventId,
            String eventKey,
            String category,
            String eventType,
            UUID tenantId,
            UUID userId,
            String aggregateType,
            String aggregateId,
            String canonicalPayload,
            OffsetDateTime occurredAt) {
        return jdbc.update("""
                INSERT INTO platform.notification_event(
                    id, event_key, category, event_type, tenant_id, user_id,
                    aggregate_type, aggregate_id, payload, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (event_key) DO NOTHING
                """, eventId, eventKey, category, eventType, tenantId, userId,
                aggregateType, aggregateId, canonicalPayload, occurredAt) == 1;
    }

    Optional<ExistingEventRow> findByEventKey(String eventKey) {
        return jdbc.query("""
                SELECT id, event_key, event_type, category, tenant_id, user_id,
                       aggregate_type, aggregate_id, payload::text, occurred_at
                FROM platform.notification_event
                WHERE event_key = ?
                """, (result, rowNumber) -> new ExistingEventRow(
                result.getObject("id", UUID.class),
                result.getString("event_key"),
                result.getString("event_type"),
                result.getString("category"),
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getString("aggregate_type"),
                result.getString("aggregate_id"),
                result.getString("payload"),
                result.getObject("occurred_at", OffsetDateTime.class)), eventKey)
                .stream()
                .findFirst();
    }

    record ExistingEventRow(
            UUID id,
            String eventKey,
            String eventType,
            String category,
            UUID tenantId,
            UUID userId,
            String aggregateType,
            String aggregateId,
            String payload,
            OffsetDateTime occurredAt) {
    }
}
