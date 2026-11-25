package com.yuzhihuihe.apiplatform.platform.legal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 带版本的服务条款与隐私政策的持久化。已发布的行由数据库触发器冻结，
 * 因此这里的每次写入都只会作用于草稿行或全新的版本。
 */
@Repository
class LegalDocumentRepository {

    private static final String COLUMNS = """
            id, kind, version, title, content::text AS content, published,
            published_at, published_by, unpublished_at, created_by, created_at, updated_at
            """;

    /** 最近一次发布事件排在最前，因此被恢复的版本会重新回到顶部。 */
    private static final String HISTORY_ORDER =
            " ORDER BY COALESCE(unpublished_at, published_at, created_at) DESC, created_at DESC";

    private final JdbcTemplate jdbc;

    LegalDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Row> findPublished(String kind) {
        return jdbc.query(
                        "SELECT " + COLUMNS + """
                        FROM platform.legal_document_version
                        WHERE kind = ? AND published
                        """,
                        (result, rowNumber) -> row(result),
                        kind)
                .stream().findFirst();
    }

    Optional<Row> findDraft(String kind) {
        return jdbc.query(
                        "SELECT " + COLUMNS + """
                        FROM platform.legal_document_version
                        WHERE kind = ? AND NOT published
                        ORDER BY updated_at DESC, created_at DESC
                        LIMIT 1
                        """,
                        (result, rowNumber) -> row(result),
                        kind)
                .stream().findFirst();
    }

    List<Row> findHistory(String kind, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + """
                        FROM platform.legal_document_version
                        WHERE kind = ?
                        """ + HISTORY_ORDER + " LIMIT ?",
                (result, rowNumber) -> row(result),
                kind,
                limit);
    }

    Optional<Row> findByVersion(String kind, String version) {
        return jdbc.query(
                        "SELECT " + COLUMNS + """
                        FROM platform.legal_document_version
                        WHERE kind = ? AND version = ?
                        """,
                        (result, rowNumber) -> row(result),
                        kind,
                        version)
                .stream().findFirst();
    }

    boolean versionExists(String kind, String version) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM platform.legal_document_version"
                        + " WHERE kind = ? AND version = ?)",
                Boolean.class,
                kind,
                version));
    }

    /**
     * 发布一个版本，先把此前已发布的行降级，使该部分唯一索引
     * 绝不会看到同一 kind 下有两行已发布记录。
     */
    void publish(
            String kind,
            String version,
            String title,
            String content,
            UUID actorId,
            Instant publishedAt,
            String auditAction) {
        jdbc.update("""
                UPDATE platform.legal_document_version
                SET published = false, unpublished_at = ?, updated_at = ?
                WHERE kind = ? AND published
                """, Timestamp.from(publishedAt), Timestamp.from(publishedAt), kind);
        jdbc.update("""
                INSERT INTO platform.legal_document_version(
                    kind, version, title, content, published,
                    published_at, published_by, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, true, ?, ?, ?, ?, ?)
                """,
                kind, version, title, content, Timestamp.from(publishedAt),
                actorId, actorId, Timestamp.from(publishedAt), Timestamp.from(publishedAt));
        audit(actorId, auditAction, kind, version, title);
    }

    /** 原样重新发布一个既有版本，用于回滚一次错误的编辑。 */
    void restore(Row source, UUID actorId, Instant publishedAt) {
        jdbc.update("""
                UPDATE platform.legal_document_version
                SET published = false, unpublished_at = ?, updated_at = ?
                WHERE kind = ? AND published
                """, Timestamp.from(publishedAt), Timestamp.from(publishedAt), source.kind());
        // 历史行是就地重新发布的：(kind, version) 唯一，并且由于该行在已发布状态下被冻结，
        // 无法改写其文本。
        jdbc.update("""
                UPDATE platform.legal_document_version
                SET published = true, published_at = ?, published_by = ?,
                    unpublished_at = NULL, updated_at = ?
                WHERE kind = ? AND version = ?
                """,
                Timestamp.from(publishedAt), actorId, Timestamp.from(publishedAt),
                source.kind(), source.version());
        audit(actorId, "LEGAL_DOCUMENT_RESTORED", source.kind(), source.version(),
                source.title());
    }

    void saveDraft(
            UUID draftId, String title, String content, UUID actorId, Instant updatedAt) {
        jdbc.update("""
                UPDATE platform.legal_document_version
                SET title = ?, content = ?::jsonb, updated_at = ?
                WHERE id = ? AND NOT published
                """, title, content, Timestamp.from(updatedAt), draftId);
        audit(actorId, "LEGAL_DOCUMENT_DRAFT_SAVED", null, null, "draftId=" + draftId);
    }

    void insertDraft(
            String kind, String version, String title, String content, UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO platform.legal_document_version(
                    kind, version, title, content, published, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, false, ?, ?, ?)
                """, kind, version, title, content, actorId, Timestamp.from(now), Timestamp.from(now));
    }

    private void audit(
            UUID actorId, String action, String kind, String version, String note) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, ?, 'LEGAL_DOCUMENT', ?,
                        'SUCCESS', jsonb_build_object('note', ?))
                """,
                actorId,
                action,
                kind == null || version == null ? null : kind + ":" + version,
                note);
    }

    private Row row(ResultSet result) throws SQLException {
        Timestamp publishedAt = result.getTimestamp("published_at");
        Timestamp unpublishedAt = result.getTimestamp("unpublished_at");
        Timestamp updatedAt = result.getTimestamp("updated_at");
        Timestamp createdAt = result.getTimestamp("created_at");
        return new Row(
                result.getObject("id", UUID.class),
                result.getString("kind"),
                result.getString("version"),
                result.getString("title"),
                result.getString("content"),
                result.getBoolean("published"),
                publishedAt == null ? null : publishedAt.toInstant(),
                result.getObject("published_by", UUID.class),
                unpublishedAt == null ? null : unpublishedAt.toInstant(),
                result.getObject("created_by", UUID.class),
                createdAt.toInstant(),
                updatedAt.toInstant());
    }

    record Row(
            UUID id,
            String kind,
            String version,
            String title,
            String content,
            boolean published,
            Instant publishedAt,
            UUID publishedBy,
            Instant unpublishedAt,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {}
}
