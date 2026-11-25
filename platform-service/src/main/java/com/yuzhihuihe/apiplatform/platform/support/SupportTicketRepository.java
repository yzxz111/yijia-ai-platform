package com.yuzhihuihe.apiplatform.platform.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class SupportTicketRepository {

    private static final String BASE_SELECT = """
            SELECT t.id, t.ticket_number, t.tenant_id, t.user_id, t.assignee_user_id,
                   t.subject, t.description, t.status, t.resolution,
                   t.completion_business_no, t.completed_at, t.created_at, t.updated_at
            FROM platform.support_ticket t
            """;

    private final JdbcTemplate jdbc;

    SupportTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertTicket(
            UUID id, String ticketNo, UUID tenantId, UUID userId, String title, String body) {
        jdbc.update("""
                INSERT INTO platform.support_ticket(
                    id, ticket_number, tenant_id, user_id, subject, description)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, ticketNo, tenantId, userId, title, body);
    }

    PageRows<TicketRow> listUser(UUID tenantId, UUID userId, int size, int offset) {
        List<TicketRow> items = jdbc.query(
                BASE_SELECT + " WHERE t.tenant_id=? AND t.user_id=?"
                        + " ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?",
                SupportTicketRepository::ticket, tenantId, userId, size, offset);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform.support_ticket t
                WHERE t.tenant_id=? AND t.user_id=?
                """, Long.class, tenantId, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    PageRows<TicketRow> listAdmin(AdminFilter filter) {
        List<Object> filters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (filter.status() != null) {
            where.append(" AND t.status=?");
            filters.add(filter.status());
        }
        if (filter.search() != null) {
            where.append(" AND (lower(t.ticket_number) LIKE ? ESCAPE '!' OR lower(t.subject) LIKE ? ESCAPE '!')");
            filters.add(filter.search());
            filters.add(filter.search());
        }
        if (filter.from() != null) {
            where.append(" AND t.created_at>=?");
            filters.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND t.created_at<=?");
            filters.add(Timestamp.from(filter.to()));
        }
        List<Object> arguments = new ArrayList<>(filters);
        arguments.add(filter.size());
        arguments.add(filter.offset());
        List<TicketRow> items = jdbc.query(
                BASE_SELECT + where
                        + " ORDER BY t.updated_at DESC, t.id DESC LIMIT ? OFFSET ?",
                SupportTicketRepository::ticket, arguments.toArray());
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform.support_ticket t" + where,
                Long.class, filters.toArray());
        return new PageRows<>(items, total == null ? 0 : total);
    }

    TicketRow findUser(UUID tenantId, UUID userId, UUID ticketId) {
        return one(BASE_SELECT + " WHERE t.id=? AND t.tenant_id=? AND t.user_id=?",
                ticketId, tenantId, userId);
    }

    TicketRow findAdmin(UUID ticketId) {
        return one(BASE_SELECT + " WHERE t.id=?", ticketId);
    }

    TicketRow lock(UUID ticketId) {
        return one(BASE_SELECT + " WHERE t.id=? FOR UPDATE OF t", ticketId);
    }

    TicketRow lockUser(UUID tenantId, UUID userId, UUID ticketId) {
        return one(BASE_SELECT + " WHERE t.id=? AND t.tenant_id=? AND t.user_id=? FOR UPDATE OF t",
                ticketId, tenantId, userId);
    }

    TicketRow findForTenant(UUID tenantId, UUID ticketId) {
        return one(BASE_SELECT + " WHERE t.id=? AND t.tenant_id=?", ticketId, tenantId);
    }

    void claim(UUID actorId, UUID ticketId, UUID tenantId) {
        jdbc.update("UPDATE platform.support_ticket SET assignee_user_id=?, updated_at=now() WHERE id=? AND tenant_id=?",
                actorId, ticketId, tenantId);
    }

    void process(UUID actorId, UUID ticketId, UUID tenantId) {
        jdbc.update("""
                UPDATE platform.support_ticket SET status='PROCESSING',
                    assignee_user_id=COALESCE(assignee_user_id, ?), updated_at=now()
                WHERE id=? AND tenant_id=?
                """, actorId, ticketId, tenantId);
    }

    void complete(
            String resolution,
            String businessNo,
            OffsetDateTime completedAt,
            UUID actorId,
            UUID ticketId,
            UUID tenantId) {
        jdbc.update("""
                UPDATE platform.support_ticket SET status='COMPLETED', resolution=?,
                    completion_business_no=?, completed_at=?,
                    assignee_user_id=COALESCE(assignee_user_id, ?), updated_at=now()
                WHERE id=? AND tenant_id=?
                """, resolution, businessNo, completedAt, actorId, ticketId, tenantId);
    }

    void close(
            String resolution,
            String businessNo,
            OffsetDateTime closedAt,
            UUID ticketId,
            UUID tenantId) {
        jdbc.update("""
                UPDATE platform.support_ticket
                SET status='CLOSED', resolution=?, completion_business_no=?,
                    completed_at=?, updated_at=now()
                WHERE id=? AND tenant_id=?
                """, resolution, businessNo, closedAt, ticketId, tenantId);
    }

    void insertComment(
            UUID commentId, UUID tenantId, UUID ticketId,
            UUID authorId, String role, String body) {
        jdbc.update("""
                INSERT INTO platform.support_ticket_comment(
                    id, ticket_id, tenant_id, author_user_id, author_role, body)
                VALUES (?, ?, ?, ?, ?, ?)
                """, commentId, ticketId, tenantId, authorId, role, body);
    }

    void audit(UUID tenantId, UUID actorId, UUID ticketId, String action, String status) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    tenant_id, actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, ?, ?, 'SUPPORT_TICKET', ?, 'SUCCESS',
                    jsonb_build_object('status', CAST(? AS text)))
                """, tenantId, actorId, action, ticketId.toString(), status);
    }

    List<CommentRow> comments(UUID tenantId, UUID ticketId) {
        return jdbc.query("""
                SELECT id, author_user_id, author_role, body, created_at
                FROM platform.support_ticket_comment
                WHERE tenant_id=? AND ticket_id=? ORDER BY created_at, id
                """, (result, row) -> new CommentRow(
                result.getObject("id", UUID.class),
                result.getObject("author_user_id", UUID.class),
                result.getString("author_role"), result.getString("body"),
                result.getObject("created_at", OffsetDateTime.class)), tenantId, ticketId);
    }

    List<AttachmentRow> attachments(UUID tenantId, UUID ticketId) {
        return jdbc.query("""
                SELECT id, comment_id, filename, content_type, size_bytes, created_at
                FROM platform.support_ticket_attachment
                WHERE tenant_id = ? AND ticket_id = ?
                ORDER BY created_at, id
                """, (result, row) -> new AttachmentRow(
                result.getObject("id", UUID.class), result.getObject("comment_id", UUID.class),
                result.getString("filename"), result.getString("content_type"),
                result.getInt("size_bytes"),
                result.getObject("created_at", OffsetDateTime.class)), tenantId, ticketId);
    }

    AttachmentContentRow attachment(UUID tenantId, UUID ticketId, UUID attachmentId) {
        return jdbc.query("""
                SELECT filename, content_type, content
                FROM platform.support_ticket_attachment
                WHERE tenant_id = ? AND ticket_id = ? AND id = ?
                """, (result, row) -> new AttachmentContentRow(
                result.getString("filename"), result.getString("content_type"),
                result.getBytes("content")), tenantId, ticketId, attachmentId)
                .stream().findFirst().orElse(null);
    }

    void insertAttachment(
            UUID id,
            UUID ticketId,
            UUID tenantId,
            UUID commentId,
            UUID uploaderUserId,
            String filename,
            String contentType,
            byte[] bytes) {
        jdbc.update("""
                INSERT INTO platform.support_ticket_attachment(
                    id, ticket_id, tenant_id, comment_id, uploader_user_id,
                    filename, content_type, size_bytes, content)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ticketId, tenantId, commentId, uploaderUserId,
                filename, contentType, bytes.length, bytes);
    }

    private TicketRow one(String sql, Object... arguments) {
        return jdbc.query(sql, SupportTicketRepository::ticket, arguments)
                .stream().findFirst().orElse(null);
    }

    private static TicketRow ticket(java.sql.ResultSet result, int row)
            throws java.sql.SQLException {
        return new TicketRow(
                result.getObject("id", UUID.class), result.getString("ticket_number"),
                result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class),
                result.getObject("assignee_user_id", UUID.class),
                result.getString("subject"), result.getString("description"),
                result.getString("status"), result.getString("resolution"),
                result.getString("completion_business_no"),
                result.getObject("completed_at", OffsetDateTime.class),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    record AdminFilter(
            String status, String search, Instant from, Instant to, int size, int offset) {
    }

    record PageRows<T>(List<T> items, long total) {
    }

    record TicketRow(
            UUID id,
            String ticketNo,
            UUID tenantId,
            UUID userId,
            UUID assigneeUserId,
            String title,
            String body,
            String status,
            String resolution,
            String completionBusinessNo,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    record CommentRow(
            UUID id,
            UUID authorUserId,
            String authorRole,
            String body,
            OffsetDateTime createdAt) {
    }

    record AttachmentRow(
            UUID id,
            UUID commentId,
            String filename,
            String contentType,
            int sizeBytes,
            OffsetDateTime createdAt) {
    }

    record AttachmentContentRow(String filename, String contentType, byte[] bytes) {
    }
}
