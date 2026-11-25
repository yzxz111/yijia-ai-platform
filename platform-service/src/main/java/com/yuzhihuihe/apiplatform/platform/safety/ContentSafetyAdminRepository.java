package com.yuzhihuihe.apiplatform.platform.safety;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ContentSafetyAdminRepository {

    private final JdbcTemplate jdbc;

    ContentSafetyAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    PageRows<CaseRow> listCases(
            String status, String search, int size, int offset) {
        List<Object> predicates = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        if (status != null) {
            where.append(" WHERE status = ?");
            predicates.add(status);
        }
        if (search != null) {
            where.append(where.isEmpty() ? " WHERE " : " AND ")
                    .append("(lower(case_no) LIKE ? ESCAPE '!' ")
                    .append("OR lower(CAST(user_id AS text)) LIKE ? ESCAPE '!' ")
                    .append("OR lower(CAST(tenant_id AS text)) LIKE ? ESCAPE '!')");
            predicates.add(search);
            predicates.add(search);
            predicates.add(search);
        }
        String select = """
                SELECT c.case_no, c.tenant_id, c.user_id, c.request_id, c.direction, c.category, c.severity, c.action,
                       c.status, c.policy_version, c.redacted_excerpt, c.created_at, c.reviewed_at, c.review_note,
                       c.resolution_action, enforcement.enforcement_state, enforcement.enforcement_reason_code
                FROM platform.content_safety_case c
                LEFT JOIN LATERAL (
                    SELECT e.state AS enforcement_state, e.reason_code AS enforcement_reason_code
                    FROM platform.account_enforcement e
                    WHERE e.tenant_id = c.tenant_id AND e.user_id = c.user_id
                    ORDER BY e.version DESC
                    LIMIT 1
                ) enforcement ON TRUE
                """ + where + " ORDER BY created_at DESC, case_no DESC LIMIT ? OFFSET ?";
        List<Object> itemArguments = new ArrayList<>(predicates);
        itemArguments.add(size);
        itemArguments.add(offset);
        List<CaseRow> items = jdbc.query(select, (row, number) -> new CaseRow(
                row.getString("case_no"), row.getObject("tenant_id", UUID.class),
                row.getObject("user_id", UUID.class), row.getString("request_id"),
                row.getString("direction"), row.getString("category"),
                row.getString("severity"), row.getString("action"),
                row.getString("status"), row.getLong("policy_version"),
                row.getString("redacted_excerpt"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("reviewed_at") == null
                        ? null : row.getTimestamp("reviewed_at").toInstant(),
                row.getString("review_note"), row.getString("resolution_action"),
                row.getString("enforcement_state"),
                row.getString("enforcement_reason_code")), itemArguments.toArray());
        String countSql = "SELECT COUNT(*) FROM platform.content_safety_case" + where;
        Long total = predicates.isEmpty()
                ? jdbc.queryForObject(countSql, Long.class)
                : jdbc.queryForObject(countSql, Long.class, predicates.toArray());
        return new PageRows<>(items, total == null ? 0 : total);
    }

    boolean lockEnforcementOwner(UUID tenantId, UUID userId) {
        UUID lockedUser = jdbc.query("""
                SELECT id FROM platform.app_user
                WHERE tenant_id = ? AND id = ?
                FOR UPDATE
                """, result -> result.next() ? result.getObject(1, UUID.class) : null,
                tenantId, userId);
        return lockedUser != null;
    }

    ResolvedCaseRow resolveCase(UUID tenantId, UUID userId, String caseNo) {
        return jdbc.query("""
                SELECT id, case_no FROM platform.content_safety_case
                WHERE case_no = ? AND tenant_id = ? AND user_id = ?
                """, result -> result.next() ? new ResolvedCaseRow(
                result.getObject("id", UUID.class), result.getString("case_no")) : null,
                caseNo, tenantId, userId);
    }

    CurrentEnforcementRow lockCurrentEnforcement(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT e.id, e.state, e.version, c.case_no
                FROM platform.account_enforcement e
                LEFT JOIN platform.content_safety_case c ON c.id = e.case_id
                WHERE e.tenant_id = ? AND e.user_id = ?
                ORDER BY e.version DESC
                LIMIT 1
                FOR UPDATE OF e
                """, result -> result.next() ? new CurrentEnforcementRow(
                result.getObject("id", UUID.class), result.getString("state"),
                result.getLong("version"), result.getString("case_no")) : null,
                tenantId, userId);
    }

    void updateEnforcement(UUID id, String state, String reasonCode,
            UUID caseId, UUID actor, long version) {
        jdbc.update("""
                UPDATE platform.account_enforcement
                SET state = ?, reason_code = ?, case_id = ?, created_by = ?,
                    lifted_by = NULL, lifted_at = NULL, starts_at = now(),
                    updated_at = now(), version = ?
                WHERE id = ?
                """, state, reasonCode, caseId, actor, version, id);
    }

    void insertEnforcement(UUID id, UUID tenantId, UUID userId, String state,
            String reasonCode, UUID caseId, UUID actor, long version) {
        jdbc.update("""
                INSERT INTO platform.account_enforcement(
                    id, tenant_id, user_id, state, reason_code, case_id,
                    created_by, starts_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?)
                """, id, tenantId, userId, state, reasonCode, caseId, actor, version);
    }

    void clearEnforcement(UUID id, String reasonCode, UUID actor, long version) {
        jdbc.update("""
                UPDATE platform.account_enforcement
                SET state = 'CLEARED', reason_code = ?, lifted_by = ?, lifted_at = now(),
                    updated_at = now(), version = ?
                WHERE id = ?
                """, reasonCode, actor, version, id);
    }

    void syncGatewayCache(UUID id, UUID tenantId, UUID userId, String state,
            String reasonCode, String caseNo, long version) {
        jdbc.update("""
                INSERT INTO gateway.safety_enforcement_cache(
                    id, tenant_id, user_id, state, reason_code, case_no, event_version, effective_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (tenant_id, user_id) DO UPDATE SET state = EXCLUDED.state,
                    reason_code = EXCLUDED.reason_code, case_no = EXCLUDED.case_no,
                    event_version = EXCLUDED.event_version, effective_at = now(), updated_at = now()
                """, id, tenantId, userId, state, reasonCode, caseNo, version);
    }

    EnforcementRow enforcement(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT state, reason_code, case_id, starts_at, ends_at, lifted_at, version
                FROM platform.account_enforcement
                WHERE tenant_id = ? AND user_id = ? ORDER BY version DESC LIMIT 1
                """, result -> result.next() ? new EnforcementRow(
                result.getString("state"), result.getString("reason_code"),
                result.getObject("case_id", UUID.class), result.getLong("version")) : null,
                tenantId, userId);
    }

    PageRows<AppealRow> appeals(
            UUID tenantId, UUID userId, String search, int size, int offset) {
        List<Object> predicates = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        if (tenantId != null) {
            where.append(" WHERE tenant_id = ?");
            predicates.add(tenantId);
        }
        if (userId != null) {
            where.append(where.isEmpty() ? " WHERE " : " AND ").append("user_id = ?");
            predicates.add(userId);
        }
        if (search != null) {
            where.append(where.isEmpty() ? " WHERE " : " AND ")
                    .append("(lower(business_no) LIKE ? ESCAPE '!' ")
                    .append("OR lower(CAST(case_id AS text)) LIKE ? ESCAPE '!' ")
                    .append("OR lower(CAST(user_id AS text)) LIKE ? ESCAPE '!')");
            predicates.add(search);
            predicates.add(search);
            predicates.add(search);
        }
        String select = """
                SELECT business_no, case_id, user_id, status, reason, processing_note,
                       created_at, processed_at
                FROM platform.content_safety_appeal
                """ + where + " ORDER BY created_at DESC, business_no DESC LIMIT ? OFFSET ?";
        List<Object> itemArguments = new ArrayList<>(predicates);
        itemArguments.add(size);
        itemArguments.add(offset);
        List<AppealRow> items = jdbc.query(
                select, (row, number) -> appeal(row), itemArguments.toArray());
        String countSql = "SELECT COUNT(*) FROM platform.content_safety_appeal" + where;
        Long total = predicates.isEmpty()
                ? jdbc.queryForObject(countSql, Long.class)
                : jdbc.queryForObject(countSql, Long.class, predicates.toArray());
        return new PageRows<>(items, total == null ? 0 : total);
    }

    PageRows<UserCaseRow> userCases(UUID tenantId, UUID userId, int size, int offset) {
        String predicate = """
                FROM platform.content_safety_case c
                WHERE c.tenant_id = ? AND c.user_id = ?
                  AND c.action IN ('REVIEW', 'BLOCK')
                  AND c.status IN ('OPEN', 'INVESTIGATING', 'CONFIRMED')
                  AND NOT EXISTS (
                      SELECT 1
                      FROM platform.content_safety_appeal a
                      WHERE a.case_id = c.id AND a.status = 'PENDING'
                  )
                """;
        List<UserCaseRow> items = jdbc.query("""
                SELECT case_no, category, severity, action, status, redacted_excerpt, created_at
                """ + predicate + " ORDER BY created_at DESC, case_no DESC LIMIT ? OFFSET ?",
                (row, number) -> new UserCaseRow(
                row.getString("case_no"), row.getString("category"),
                row.getString("severity"), row.getString("action"), row.getString("status"),
                row.getString("redacted_excerpt"), row.getTimestamp("created_at").toInstant()),
                tenantId, userId, size, offset);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) " + predicate, Long.class, tenantId, userId);
        return new PageRows<>(items, total == null ? 0 : total);
    }

    UUID findCaseId(UUID tenantId, UUID userId, String caseNo) {
        return jdbc.query("SELECT id FROM platform.content_safety_case WHERE case_no = ? AND tenant_id = ? AND user_id = ?",
                result -> result.next() ? result.getObject(1, UUID.class) : null,
                caseNo, tenantId, userId);
    }

    void insertAppeal(UUID id, UUID caseId, UUID tenantId, UUID userId,
            String businessNo, String reason) {
        jdbc.update("""
                INSERT INTO platform.content_safety_appeal(
                    id, case_id, tenant_id, user_id, business_no, reason, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', now(), now())
                """, id, caseId, tenantId, userId, businessNo, reason);
    }

    AppealRow lockUserAppeal(UUID tenantId, UUID userId, String businessNo) {
        return jdbc.query("""
                SELECT business_no, case_id, user_id, status, reason, processing_note
                FROM platform.content_safety_appeal
                WHERE tenant_id = ? AND user_id = ? AND business_no = ?
                FOR UPDATE
                """, result -> result.next() ? appeal(result) : null,
                tenantId, userId, businessNo);
    }

    void withdrawUserAppeal(UUID tenantId, UUID userId, String businessNo) {
        jdbc.update("""
                UPDATE platform.content_safety_appeal
                SET status = 'WITHDRAWN', processing_note = '用户已撤回',
                    processed_at = now(), updated_at = now()
                WHERE tenant_id = ? AND user_id = ? AND business_no = ? AND status = 'PENDING'
                """, tenantId, userId, businessNo);
    }

    AppealRow appealByBusinessNo(String businessNo) {
        return jdbc.query("""
                SELECT business_no, case_id, user_id, status, reason, processing_note
                FROM platform.content_safety_appeal WHERE business_no = ?
                """, result -> result.next() ? appeal(result) : null, businessNo);
    }

    AppealRow processAppeal(String businessNo, String status, UUID actor, String note) {
        jdbc.update("""
                UPDATE platform.content_safety_appeal
                SET status = ?, processed_by = ?, processing_note = ?, processed_at = now(), updated_at = now()
                WHERE business_no = ? AND status = 'PENDING'
                """, status, actor, note, businessNo);
        return jdbc.query("""
                SELECT business_no, case_id, user_id, status, reason, processing_note
                FROM platform.content_safety_appeal WHERE business_no = ?
                """, result -> result.next() ? appeal(result) : null, businessNo);
    }

    AppealRow lockAppeal(String businessNo) {
        return jdbc.query("""
                SELECT business_no, case_id, user_id, status, reason, processing_note
                FROM platform.content_safety_appeal
                WHERE business_no = ?
                FOR UPDATE
                """, result -> result.next() ? appeal(result) : null, businessNo);
    }

    void withdrawAppealAsAdmin(String businessNo, UUID actor) {
        jdbc.update("""
                UPDATE platform.content_safety_appeal
                SET status = 'WITHDRAWN', processed_by = ?, processing_note = '管理员已撤回',
                    processed_at = now(), updated_at = now()
                WHERE business_no = ? AND status = 'PENDING'
                """, actor, businessNo);
    }

    private static AppealRow appeal(java.sql.ResultSet row) throws java.sql.SQLException {
        return new AppealRow(
                row.getString("business_no"), row.getObject("case_id", UUID.class),
                row.getObject("user_id", UUID.class), row.getString("status"),
                row.getString("reason"), row.getString("processing_note"));
    }

    record PageRows<T>(List<T> items, long total) {
    }

    record CaseRow(
            String caseNo, UUID tenantId, UUID userId, String requestId,
            String direction, String category, String severity, String action, String status,
            long policyVersion, String redactedExcerpt, Instant createdAt, Instant reviewedAt,
            String reviewNote, String resolutionAction,
            String enforcementState, String enforcementReasonCode) {
    }

    record ResolvedCaseRow(UUID id, String caseNo) {
    }

    record CurrentEnforcementRow(UUID id, String state, long version, String caseNo) {
    }

    record EnforcementRow(String state, String reasonCode, UUID caseId, long version) {
    }

    record AppealRow(
            String businessNo, UUID caseId, UUID userId,
            String status, String reason, String processingNote) {
    }

    record UserCaseRow(
            String caseNo, String category, String severity, String action,
            String status, String redactedExcerpt, Instant createdAt) {
    }
}
