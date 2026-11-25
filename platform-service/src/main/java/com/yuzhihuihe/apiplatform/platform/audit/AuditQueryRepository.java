package com.yuzhihuihe.apiplatform.platform.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AuditQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "createdAt", "audit_entries.created_at",
            "action", "audit_entries.action",
            "resourceType", "audit_entries.resource_type",
            "outcome", "audit_entries.outcome");

    private final JdbcTemplate jdbc;

    AuditQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    PageRows query(
            PageSpec page,
            UUID actorUserId,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            Instant from,
            Instant to,
            SortSpec sort) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> parameters = new ArrayList<>();
        add(where, parameters, "audit_entries.actor_user_id = ?", actorUserId);
        add(where, parameters, "audit_entries.action = ?", action);
        add(where, parameters, "audit_entries.resource_type = ?", resourceType);
        add(where, parameters, "audit_entries.resource_id = ?", resourceId);
        add(where, parameters, "audit_entries.outcome = ?", outcome);
        add(where, parameters, "audit_entries.created_at >= ?", from);
        add(where, parameters, "audit_entries.created_at < ?", to);

        String source = auditSource();
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM " + source + where,
                Long.class, parameters.toArray());
        List<Object> itemParameters = new ArrayList<>(parameters);
        itemParameters.add(page.size());
        itemParameters.add(page.offset());
        List<AuditRow> items = jdbc.query("""
                        SELECT audit_entries.source, audit_entries.id, audit_entries.tenant_id,
                               audit_entries.actor_user_id, actor.user_no AS actor_user_no,
                               actor.display_name AS actor_display_name, audit_entries.action,
                               audit_entries.resource_type, audit_entries.resource_id,
                               audit_entries.request_id, audit_entries.outcome,
                               audit_entries.detail::text, audit_entries.created_at
                        FROM
                        """ + source + where
                        + order(sort) + " LIMIT ? OFFSET ?",
                (result, row) -> new AuditRow(
                        result.getLong("id"), result.getString("source"),
                        result.getObject("tenant_id", UUID.class),
                        result.getObject("actor_user_id", UUID.class),
                        result.getString("actor_user_no"), result.getString("actor_display_name"),
                        result.getString("action"), result.getString("resource_type"),
                        result.getString("resource_id"), result.getString("request_id"),
                        result.getString("outcome"), result.getString("detail"),
                        result.getTimestamp("created_at").toInstant()),
                itemParameters.toArray());
        return new PageRows(items, total == null ? 0 : total, page.page(), page.size());
    }

    private String order(SortSpec sort) {
        if (sort.defaultRequest()) {
            return " ORDER BY audit_entries.created_at DESC, audit_entries.source DESC,"
                    + " audit_entries.id DESC";
        }
        String column = SORT_COLUMNS.get(sort.key());
        if (column == null) {
            throw new IllegalArgumentException("unsupported audit sort: " + sort.key());
        }
        return " ORDER BY " + column + " " + sort.direction()
                + ", audit_entries.source " + sort.direction()
                + ", audit_entries.id " + sort.direction();
    }

    private String auditSource() {
        Boolean gatewayAuditAvailable = jdbc.queryForObject(
                "SELECT to_regclass('gateway.admin_operation_audit') IS NOT NULL",
                Boolean.class);
        if (!Boolean.TRUE.equals(gatewayAuditAvailable)) {
            return """
                    (
                        SELECT 'PLATFORM' AS source, id, tenant_id, actor_user_id,
                               action, resource_type, resource_id, request_id,
                               outcome, detail, created_at
                        FROM platform.admin_audit_log
                    ) audit_entries
                    LEFT JOIN platform.app_user actor
                      ON actor.id = audit_entries.actor_user_id
                    """;
        }
        return """
                (
                    SELECT 'PLATFORM' AS source, id, tenant_id, actor_user_id,
                           action, resource_type,
                           resource_id, request_id, outcome, detail, created_at
                    FROM platform.admin_audit_log
                    UNION ALL
                    SELECT 'GATEWAY' AS source, id, tenant_id, actor_user_id, action,
                           resource_type,
                           COALESCE(resource_id, operation_id::text) AS resource_id, request_id,
                           CASE WHEN status = 'COMPLETED' THEN 'SUCCESS' ELSE 'FAILURE' END,
                           detail || jsonb_build_object(
                               'status', status,
                               'affected', affected,
                               'failureSummary', failure_summary) AS detail,
                           COALESCE(completed_at, created_at) AS created_at
                    FROM gateway.admin_operation_audit
                    WHERE status <> 'RUNNING'
                ) audit_entries
                LEFT JOIN platform.app_user actor
                  ON actor.id = audit_entries.actor_user_id
                """;
    }

    private void add(
            StringBuilder where, List<Object> parameters, String condition, Object value) {
        if (value != null) {
            where.append(" AND ").append(condition);
            parameters.add(value instanceof Instant instant ? Timestamp.from(instant) : value);
        }
    }

    record PageSpec(int page, int size, int offset) {}

    record SortSpec(String key, String direction, boolean defaultRequest) {}

    record PageRows(List<AuditRow> items, long total, int page, int size) {}

    record AuditRow(
            long id,
            String source,
            UUID tenantId,
            UUID actorUserId,
            String actorUserNo,
            String actorDisplayName,
            String action,
            String resourceType,
            String resourceId,
            String requestId,
            String outcome,
            String detailJson,
            Instant createdAt) {}
}
