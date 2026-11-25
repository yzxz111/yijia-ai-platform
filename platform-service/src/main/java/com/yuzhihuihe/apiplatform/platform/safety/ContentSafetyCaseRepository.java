package com.yuzhihuihe.apiplatform.platform.safety;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ContentSafetyCaseRepository {

    private final JdbcTemplate jdbc;

    ContentSafetyCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String findCaseNoByFingerprint(String fingerprint) {
        return jdbc.query(
                "SELECT case_no FROM platform.content_safety_case WHERE case_fingerprint = ?",
                result -> result.next() ? result.getString(1) : null, fingerprint);
    }

    String findInboxPayloadHash(String businessId) {
        return jdbc.query(
                "SELECT payload_hash FROM platform.content_safety_event_inbox WHERE business_id = ?",
                result -> result.next() ? result.getString(1) : null, businessId);
    }

    CaseRow findByCaseNo(String caseNo) {
        return jdbc.query("""
                SELECT case_no, tenant_id, user_id, request_id, direction, category, severity, action,
                       status, policy_version, evidence_sha256, redacted_excerpt, created_at, updated_at,
                       reviewed_at, review_note, resolution_action
                FROM platform.content_safety_case WHERE case_no = ?
                """, result -> result.next() ? new CaseRow(
                result.getString("case_no"), result.getObject("tenant_id", UUID.class),
                result.getObject("user_id", UUID.class), result.getString("request_id"),
                result.getString("direction"), result.getString("category"),
                result.getString("severity"), result.getString("action"),
                result.getString("status"), result.getLong("policy_version"),
                result.getString("evidence_sha256"), result.getString("redacted_excerpt"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant(),
                result.getTimestamp("reviewed_at") == null
                        ? null : result.getTimestamp("reviewed_at").toInstant(),
                result.getString("review_note"), result.getString("resolution_action")) : null,
                caseNo);
    }

    void ensurePolicyVersion(long version) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM platform.content_safety_policy_version WHERE version = ?",
                Integer.class, version);
        if (count == null || count == 0) {
            jdbc.update("""
                    INSERT INTO platform.content_safety_policy_version(
                        version, status, config, config_sha256, created_at, updated_at)
                    VALUES (?, 'DRAFT', '{}'::jsonb, ?, now(), now())
                    """, version, "0".repeat(64));
        }
    }

    int insertInbox(UUID id, SafetyEventRow event, String payloadHash, String payloadJson) {
        return jdbc.update("""
                INSERT INTO platform.content_safety_event_inbox(
                    id, business_id, event_type, request_id, payload_hash, payload,
                    status, attempt_count, processed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, 'PROCESSED', 1, now(), now(), now())
                ON CONFLICT (business_id) DO NOTHING
                """, id, event.businessId(), event.eventType(), event.requestId(),
                payloadHash, payloadJson);
    }

    void insertCase(UUID id, String caseNo, SafetyEventRow event,
            String evidenceHash, String excerpt, String fingerprint) {
        jdbc.update("""
                INSERT INTO platform.content_safety_case(
                    id, case_no, tenant_id, user_id, request_id, direction, source,
                    category, severity, action, status, policy_version, provider_code,
                    model_alias, provider, channel_id, evidence_sha256, redacted_excerpt,
                    case_fingerprint, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """, id, caseNo, event.tenantId(), event.userId(), event.requestId(),
                event.direction(), event.source(), event.category(), event.severity(), event.action(),
                event.policyVersion(), event.providerCode(), event.modelAlias(), event.provider(),
                event.channelId(), evidenceHash, excerpt, fingerprint);
    }

    void transition(String caseNo, String targetStatus, UUID actorUserId,
            String note, String resolutionAction) {
        jdbc.update("""
                UPDATE platform.content_safety_case
                SET status = ?, reviewed_by = ?, reviewed_at = now(), review_note = ?,
                    resolution_action = COALESCE(?, resolution_action), updated_at = now()
                WHERE case_no = ?
                """, targetStatus, actorUserId, note, resolutionAction, caseNo);
    }

    int resolve(String caseNo, String resolutionAction, UUID actorUserId, String note) {
        return jdbc.update("""
                UPDATE platform.content_safety_case
                SET status = 'RESOLVED', reviewed_by = ?, reviewed_at = now(), review_note = ?,
                    resolution_action = ?, updated_at = now()
                WHERE case_no = ? AND status = 'CONFIRMED'
                """, actorUserId, note, resolutionAction, caseNo);
    }

    record SafetyEventRow(
            String businessId, String eventType, String requestId, UUID tenantId, UUID userId,
            String direction, String source, String category, String severity, String action,
            long policyVersion, String providerCode, String modelAlias,
            String provider, String channelId) {
    }

    record CaseRow(
            String caseNo, UUID tenantId, UUID userId, String requestId,
            String direction, String category, String severity, String action,
            String status, long policyVersion, String evidenceSha256, String redactedExcerpt,
            Instant createdAt, Instant updatedAt, Instant reviewedAt,
            String reviewNote, String resolutionAction) {
    }
}
