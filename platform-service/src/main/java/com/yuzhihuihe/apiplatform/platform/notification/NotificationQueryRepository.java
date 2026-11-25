package com.yuzhihuihe.apiplatform.platform.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class NotificationQueryRepository {

    private final JdbcTemplate jdbc;

    NotificationQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    NotificationPageRows list(
            UUID tenantId,
            UUID userId,
            String category,
            Boolean read,
            int size,
            int offset) {
        StringBuilder where = new StringBuilder("""
                FROM platform.in_app_notification notification
                JOIN platform.notification_event event ON event.id=notification.event_id
                WHERE notification.tenant_id=? AND notification.user_id=?
                  AND notification.archived_at IS NULL
                """);
        List<Object> arguments = new ArrayList<>(List.of(tenantId, userId));
        if (category != null) {
            where.append(" AND event.category=?");
            arguments.add(category);
        }
        if (read != null) {
            where.append(read ? " AND notification.read_at IS NOT NULL"
                    : " AND notification.read_at IS NULL");
        }

        Long total = jdbc.queryForObject("SELECT count(*) " + where, Long.class,
                arguments.toArray());
        arguments.add(size);
        arguments.add(offset);
        List<NotificationRow> items = jdbc.query("""
                SELECT notification.id,event.category,event.event_type,notification.title,
                       notification.summary,event.aggregate_type,event.aggregate_id,
                       notification.read_at,notification.archived_at,notification.created_at
                """ + where + " ORDER BY notification.created_at DESC, notification.id DESC LIMIT ? OFFSET ?",
                (result, row) -> notification(result), arguments.toArray());
        return new NotificationPageRows(items, total == null ? 0 : total);
    }

    long unreadCount(UUID tenantId, UUID userId) {
        Long value = jdbc.queryForObject("""
                SELECT count(*) FROM platform.in_app_notification
                WHERE tenant_id=? AND user_id=? AND read_at IS NULL AND archived_at IS NULL
                """, Long.class, tenantId, userId);
        return value == null ? 0 : value;
    }

    void markRead(UUID tenantId, UUID userId, UUID notificationId, Instant now) {
        jdbc.update("""
                UPDATE platform.in_app_notification SET read_at=?
                WHERE id=? AND tenant_id=? AND user_id=? AND read_at IS NULL
                """, Timestamp.from(now), notificationId, tenantId, userId);
    }

    void markAllRead(UUID tenantId, UUID userId, Instant now) {
        jdbc.update("""
                UPDATE platform.in_app_notification SET read_at=?
                WHERE tenant_id=? AND user_id=? AND read_at IS NULL AND archived_at IS NULL
                """, Timestamp.from(now), tenantId, userId);
    }

    void archive(UUID tenantId, UUID userId, UUID notificationId, Instant now) {
        jdbc.update("""
                UPDATE platform.in_app_notification SET archived_at=?
                WHERE id=? AND tenant_id=? AND user_id=? AND archived_at IS NULL
                """, Timestamp.from(now), notificationId, tenantId, userId);
    }

    private static NotificationRow notification(ResultSet result) throws SQLException {
        return new NotificationRow(
                result.getObject("id", UUID.class),
                result.getString("category"),
                result.getString("event_type"),
                result.getString("title"),
                result.getString("summary"),
                result.getString("aggregate_type"),
                result.getString("aggregate_id"),
                instant(result, "read_at"),
                instant(result, "archived_at"),
                instant(result, "created_at"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record NotificationPageRows(List<NotificationRow> items, long total) {
    }

    record NotificationRow(
            UUID id,
            String category,
            String eventType,
            String title,
            String summary,
            String aggregateType,
            String aggregateId,
            Instant readAt,
            Instant archivedAt,
            Instant createdAt) {
    }
}
