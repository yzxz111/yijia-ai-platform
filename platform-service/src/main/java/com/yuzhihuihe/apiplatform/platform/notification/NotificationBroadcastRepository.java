package com.yuzhihuihe.apiplatform.platform.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class NotificationBroadcastRepository {

    private final JdbcTemplate jdbc;

    NotificationBroadcastRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertBroadcast(
            UUID id,
            String title,
            String body,
            String maintainTime,
            String providerBusinessNo,
            String audienceType,
            UUID tenantId,
            String audienceFilter,
            UUID actor,
            Instant createdAt,
            Instant updatedAt) {
        jdbc.update("""
                INSERT INTO platform.notification_broadcast(
                    id,broadcast_key,event_type,title,summary,maintain_time,provider_business_no,
                    audience_type,tenant_id,audience_filter,created_by,created_at,updated_at)
                VALUES (?,?,'PLATFORM_MAINTENANCE',?,?,?,?,?,?,?::jsonb,?,?,?)
                """, id, "broadcast:" + id, title, body, maintainTime, providerBusinessNo,
                audienceType, tenantId, audienceFilter, actor,
                Timestamp.from(createdAt), Timestamp.from(updatedAt));
    }

    void updateBroadcast(
            UUID id,
            String title,
            String body,
            String maintainTime,
            String providerBusinessNo,
            String audienceType,
            UUID tenantId,
            String audienceFilter,
            Instant updatedAt) {
        jdbc.update("""
                UPDATE platform.notification_broadcast
                SET title=?,summary=?,maintain_time=?,provider_business_no=?,audience_type=?,
                    tenant_id=?,audience_filter=?::jsonb,updated_at=? WHERE id=?
                """, title, body, maintainTime, providerBusinessNo, audienceType, tenantId,
                audienceFilter, Timestamp.from(updatedAt), id);
    }

    Optional<BroadcastLockRow> lock(UUID id) {
        return jdbc.query("""
                SELECT id,status,tenant_id,audience_type,audience_filter::text,paused_at
                FROM platform.notification_broadcast WHERE id=? FOR UPDATE
                """, (result, row) -> new BroadcastLockRow(
                result.getObject("id", UUID.class),
                result.getString("status"),
                result.getObject("tenant_id", UUID.class),
                result.getString("audience_type"),
                result.getString("audience_filter"),
                instant(result, "paused_at")), id).stream().findFirst();
    }

    List<UserScopeRow> findActiveRecipients(
            UUID tenantId, boolean restrictUserIds, List<UUID> userIds) {
        if (restrictUserIds && userIds.isEmpty()) {
            return List.of();
        }
        List<Object> arguments = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT tenant_id,id FROM platform.app_user WHERE status='ACTIVE'");
        if (tenantId != null) {
            sql.append(" AND tenant_id=?");
            arguments.add(tenantId);
        }
        if (restrictUserIds) {
            sql.append(" AND id IN (");
            sql.append(String.join(",", Collections.nCopies(userIds.size(), "?")));
            sql.append(")");
            arguments.addAll(userIds);
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), (result, row) -> new UserScopeRow(
                result.getObject(1, UUID.class), result.getObject(2, UUID.class)),
                arguments.toArray());
    }

    void insertRecipient(
            UUID id,
            UUID broadcastId,
            UUID tenantId,
            UUID userId,
            Instant scheduledAt,
            Instant createdAt) {
        jdbc.update("""
                INSERT INTO platform.notification_broadcast_recipient(
                    id,broadcast_id,tenant_id,user_id,recipient_snapshot,status,
                    next_attempt_at,created_at)
                VALUES (?,?,?,?,jsonb_build_object(
                    'tenantId',?::text,'userId',?::text),'PENDING',?,?)
                """, id, broadcastId, tenantId, userId, tenantId, userId,
                Timestamp.from(scheduledAt), Timestamp.from(createdAt));
    }

    void markScheduled(UUID id, Instant scheduledAt, Instant updatedAt) {
        jdbc.update("""
                UPDATE platform.notification_broadcast
                SET status='SCHEDULED',scheduled_at=?,paused_at=NULL,updated_at=? WHERE id=?
                """, Timestamp.from(scheduledAt), Timestamp.from(updatedAt), id);
    }

    void pause(UUID id, Instant pausedAt, Instant updatedAt) {
        jdbc.update("""
                UPDATE platform.notification_broadcast SET paused_at=?,job_claim_token=NULL,
                    job_claimed_until=NULL,updated_at=? WHERE id=?
                """, Timestamp.from(pausedAt), Timestamp.from(updatedAt), id);
    }

    void resetProcessingRecipients(UUID id) {
        jdbc.update("""
                UPDATE platform.notification_broadcast_recipient
                SET status='PENDING',job_claim_token=NULL,job_claimed_until=NULL
                WHERE broadcast_id=? AND status='PROCESSING'
                """, id);
    }

    void resume(UUID id, Instant scheduledAt, Instant updatedAt) {
        jdbc.update("""
                UPDATE platform.notification_broadcast
                SET paused_at=NULL,scheduled_at=?,updated_at=? WHERE id=?
                """, Timestamp.from(scheduledAt), Timestamp.from(updatedAt), id);
    }

    void reschedulePendingRecipients(UUID id, Instant scheduledAt) {
        jdbc.update("""
                UPDATE platform.notification_broadcast_recipient SET next_attempt_at=?
                WHERE broadcast_id=? AND status IN ('PENDING','FAILED')
                """, Timestamp.from(scheduledAt), id);
    }

    void cancel(UUID id, Instant updatedAt) {
        jdbc.update("""
                UPDATE platform.notification_broadcast
                SET status='CANCELED',paused_at=NULL,job_claim_token=NULL,
                    job_claimed_until=NULL,updated_at=? WHERE id=?
                """, Timestamp.from(updatedAt), id);
    }

    void cancelPendingRecipients(UUID id, Instant processedAt) {
        jdbc.update("""
                UPDATE platform.notification_broadcast_recipient
                SET status='CANCELED',processed_at=?,job_claim_token=NULL,job_claimed_until=NULL
                WHERE broadcast_id=? AND status IN ('PENDING','FAILED')
                """, Timestamp.from(processedAt), id);
    }

    BroadcastPageRows list(String status, int size, int offset) {
        String where = status == null ? "" : " WHERE status=?";
        Object[] countArguments = status == null ? new Object[0] : new Object[] {status};
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM platform.notification_broadcast" + where,
                Long.class, countArguments);
        List<Object> arguments = new ArrayList<>(List.of(countArguments));
        arguments.add(size);
        arguments.add(offset);
        List<BroadcastViewRow> items = jdbc.query("""
                SELECT broadcast.*, (SELECT count(*) FROM platform.notification_broadcast_recipient
                    recipient WHERE recipient.broadcast_id=broadcast.id) recipient_count
                FROM platform.notification_broadcast broadcast
                """ + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (result, row) -> view(result), arguments.toArray());
        return new BroadcastPageRows(items, total == null ? 0 : total);
    }

    Optional<BroadcastViewRow> findDetail(UUID id) {
        return jdbc.query("""
                SELECT broadcast.*, (SELECT count(*) FROM platform.notification_broadcast_recipient
                    recipient WHERE recipient.broadcast_id=broadcast.id) recipient_count
                FROM platform.notification_broadcast broadcast WHERE id=?
                """, (result, row) -> view(result), id).stream().findFirst();
    }

    void insertAudit(
            UUID id,
            UUID tenantId,
            UUID actor,
            String action,
            String status,
            long recipientCount) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id,actor_user_id,action,resource_type,resource_id,outcome,detail)
                VALUES (?,?,?,'NOTIFICATION_BROADCAST',?,'SUCCESS',
                    jsonb_build_object('status',?,'recipientCount',?))
                """, tenantId, actor, "NOTIFICATION_BROADCAST_" + action,
                id.toString(), status, recipientCount);
    }

    long recipientCount(UUID id) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM "
                + "platform.notification_broadcast_recipient WHERE broadcast_id=?",
                Long.class, id);
        return count == null ? 0 : count;
    }

    private static BroadcastViewRow view(ResultSet result) throws SQLException {
        return new BroadcastViewRow(
                result.getObject("id", UUID.class),
                result.getString("event_type"),
                result.getString("title"),
                result.getString("summary"),
                result.getString("maintain_time"),
                result.getString("provider_business_no"),
                result.getObject("tenant_id", UUID.class),
                result.getString("audience_type"),
                result.getString("status"),
                instant(result, "scheduled_at"),
                result.getTimestamp("paused_at") != null,
                result.getLong("recipient_count"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record BroadcastLockRow(
            UUID id,
            String status,
            UUID tenantId,
            String audienceType,
            String audienceFilter,
            Instant pausedAt) {
    }

    record UserScopeRow(UUID tenantId, UUID userId) {
    }

    record BroadcastPageRows(List<BroadcastViewRow> items, long total) {
    }

    record BroadcastViewRow(
            UUID id,
            String eventType,
            String title,
            String body,
            String maintainTime,
            String providerBusinessNo,
            UUID tenantId,
            String audienceType,
            String status,
            Instant scheduledAt,
            boolean paused,
            long recipientCount) {
    }
}
