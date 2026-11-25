package com.yuzhihuihe.apiplatform.platform.refund;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RefundPolicyRepository {

    private final JdbcTemplate jdbc;

    RefundPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<PolicyRow> findEffectiveAt(Instant requestedAt) {
        return jdbc.query("""
                        SELECT version, fee_bps, minimum_fee, refund_window_days,
                               maximum_usage_bps, effective_at, published,
                               created_by, created_at
                        FROM platform.refund_policy_version
                        WHERE published AND effective_at <= ?
                        ORDER BY effective_at DESC, version DESC
                        LIMIT 1
                        """, (result, rowNumber) -> policy(result), Timestamp.from(requestedAt))
                .stream().findFirst();
    }

    List<PolicyRow> findPublished(int limit, int offset) {
        return jdbc.query("""
                        SELECT version, fee_bps, minimum_fee, refund_window_days,
                               maximum_usage_bps, effective_at, published,
                               created_by, created_at
                        FROM platform.refund_policy_version
                        WHERE published
                        ORDER BY effective_at DESC, version DESC
                        LIMIT ? OFFSET ?
                        """, (result, rowNumber) -> policy(result), limit, offset);
    }

    long countPublished() {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform.refund_policy_version WHERE published
                """, Long.class);
        return total == null ? 0 : total;
    }

    void lockVersion(int version) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, "refund-policy-version:" + version);
                statement.execute();
                return null;
            }
        });
    }

    boolean versionExists(int version) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform.refund_policy_version WHERE version = ?)
                """, Boolean.class, version));
    }

    void insertPolicy(
            int version,
            int feeBps,
            long minimumFee,
            int refundWindowDays,
            int maximumUsageBps,
            Instant effectiveAt,
            UUID actorId) {
        jdbc.update("""
                INSERT INTO platform.refund_policy_version(
                    version, fee_bps, minimum_fee, refund_window_days,
                    maximum_usage_bps, effective_at, published, created_by)
                VALUES (?, ?, ?, ?, ?, ?, true, ?)
                """, version, feeBps, minimumFee, refundWindowDays, maximumUsageBps,
                Timestamp.from(effectiveAt), actorId);
    }

    void auditPublish(
            UUID actorId,
            int version,
            int feeBps,
            long minimumFee,
            int refundWindowDays,
            int maximumUsageBps,
            Instant effectiveAt) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(
                    actor_user_id, action, resource_type, resource_id, outcome, detail)
                VALUES (?, 'REFUND_POLICY_PUBLISHED', 'REFUND_POLICY', ?, 'SUCCESS',
                        jsonb_build_object(
                            'feeBps', ?,
                            'minimumFee', ?,
                            'refundWindowDays', ?,
                            'maximumUsageBps', ?,
                            'effectiveAt', ?))
                """, actorId, Integer.toString(version), feeBps, minimumFee,
                refundWindowDays, maximumUsageBps, effectiveAt.toString());
    }

    Optional<PolicyRow> findByVersion(int version) {
        return jdbc.query("""
                        SELECT version, fee_bps, minimum_fee, refund_window_days,
                               maximum_usage_bps, effective_at, published,
                               created_by, created_at
                        FROM platform.refund_policy_version
                        WHERE version = ?
                        """, (result, rowNumber) -> policy(result), version)
                .stream().findFirst();
    }

    private PolicyRow policy(java.sql.ResultSet result) throws java.sql.SQLException {
        OffsetDateTime effectiveAt = result.getObject("effective_at", OffsetDateTime.class);
        OffsetDateTime createdAt = result.getObject("created_at", OffsetDateTime.class);
        return new PolicyRow(
                result.getInt("version"), result.getInt("fee_bps"),
                result.getLong("minimum_fee"), result.getInt("refund_window_days"),
                result.getInt("maximum_usage_bps"), effectiveAt.toInstant(),
                result.getBoolean("published"), result.getObject("created_by", UUID.class),
                createdAt.toInstant());
    }

    record PolicyRow(
            int version,
            int feeBps,
            long minimumFee,
            int refundWindowDays,
            int maximumUsageBps,
            Instant effectiveAt,
            boolean published,
            UUID createdBy,
            Instant createdAt) {
    }
}
