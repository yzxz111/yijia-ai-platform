package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OperatingAlertRepository {

    static final String OPEN_ALERT_SQL = """
            INSERT INTO platform.operating_alert(id, alert_type, fingerprint, status, tenant_id,
              logical_model, provider, threshold_value, observed_value, currency, evidence,
              first_observed_at, last_observed_at)
            VALUES (?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
            ON CONFLICT (fingerprint) WHERE status IN ('OPEN','ACKED')
            DO UPDATE SET observed_value=EXCLUDED.observed_value,
                          last_observed_at=EXCLUDED.last_observed_at,
                          updated_at=now()
            """;

    private final JdbcTemplate jdbc;

    OperatingAlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean open(AlertRow alert) {
        String facts = alert.evidenceFactIds().stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(","));
        String evidence = "{\"windowStart\":\"" + alert.windowStart()
                + "\",\"windowEnd\":\"" + alert.windowEnd()
                + "\",\"factIds\":[" + facts + "]}";
        return jdbc.update(OPEN_ALERT_SQL,
                alert.id(), alert.type(), alert.fingerprint(), alert.tenantId(),
                alert.logicalModel(), alert.provider(), alert.threshold(), alert.observed(),
                alert.currency(), evidence, Timestamp.from(alert.observedAt()),
                Timestamp.from(alert.observedAt())) > 0;
    }

    /**
     * 每一个仍处于开启状态的毛利率告警所对应的版本 id，从告警的
     * fingerprint 中读出。fingerprint 由两个毛利率入口都写成
     * {@code package-margin:<versionId>}，因此它是唯一携带版本 id 的地方。
     *
     * <p>模式校验很重要：把格式错误的 fingerprint 转换成 uuid 会使
     * 整个查询失败，而一个读不到自己告警的监控器，
     * 比一个跳过它无法解析的行的监控器更糟。
     */
    List<UUID> openLowMarginPackageVersions() {
        return jdbc.query("""
                SELECT substring(fingerprint, length('package-margin:') + 1)::uuid
                FROM platform.operating_alert
                WHERE alert_type = 'LOW_MARGIN_PACKAGE'
                  AND status IN ('OPEN', 'ACKED')
                  AND fingerprint ~ '^package-margin:[0-9a-fA-F-]{36}$'
                """, (result, rowNumber) -> result.getObject(1, UUID.class));
    }

    /** 某一个版本的开启状态毛利率告警，以便调用方按 id 关闭它。 */
    Optional<UUID> openLowMarginPackageAlert(UUID packageVersionId) {
        return jdbc.query("""
                        SELECT id FROM platform.operating_alert
                        WHERE alert_type = 'LOW_MARGIN_PACKAGE'
                          AND fingerprint = ?
                          AND status IN ('OPEN', 'ACKED')
                        """,
                        (result, rowNumber) -> result.getObject("id", UUID.class),
                        "package-margin:" + packageVersionId)
                .stream().findFirst();
    }

    /**
     * 把告警推进到它的下一个生命周期状态。
     *
     * <p>{@code resolutionReason} 会写入告警自身的 {@code evidence} jsonb，
     * 以使告警为何关闭保留在数据行上，而不只是留在审计轨迹里。
     * 审计轨迹负责把一次解决归因（机器还是运维）；evidence 则是
     * 列表读取方无需关联审计轨迹即可展示的内容。原因值为 null —— 每一次
     * 运维解决都是如此 —— 时不去改动 evidence，而不是写入一个
     * 什么也没说明的键。
     */
    Transition transition(
            UUID id, String status, UUID actor, Instant at, String resolutionReason) {
        int updated;
        if ("ACKED".equals(status)) {
            updated = jdbc.update("""
                    UPDATE platform.operating_alert SET status='ACKED', acknowledged_by=?,
                      acknowledged_at=?, updated_at=? WHERE id=? AND status='OPEN'
                    """, actor, Timestamp.from(at), Timestamp.from(at), id);
        } else if (resolutionReason == null) {
            updated = jdbc.update("""
                    UPDATE platform.operating_alert SET status='RESOLVED', resolved_at=?, updated_at=?
                    WHERE id=? AND status IN ('OPEN','ACKED')
                    """, Timestamp.from(at), Timestamp.from(at), id);
        } else {
            updated = jdbc.update("""
                    UPDATE platform.operating_alert
                    SET status='RESOLVED', resolved_at=?, updated_at=?,
                        evidence = evidence || jsonb_build_object('resolutionReason', ?)
                    WHERE id=? AND status IN ('OPEN','ACKED')
                    """, Timestamp.from(at), Timestamp.from(at), resolutionReason, id);
        }
        if (updated == 1) {
            return Transition.UPDATED;
        }
        return jdbc.query(
                        "SELECT status FROM platform.operating_alert WHERE id=?",
                        (result, row) -> result.getString(1), id)
                .isEmpty() ? Transition.NOT_FOUND : Transition.INVALID_STATE;
    }

    void audit(UUID id, UUID actor, String action, Instant at) {
        jdbc.update("""
                INSERT INTO platform.admin_audit_log(actor_user_id, action, resource_type,
                  resource_id, outcome, detail, created_at)
                VALUES (?, ?, 'OPERATING_ALERT', ?, 'SUCCESS', '{}'::jsonb, ?)
                """, actor, "OPERATING_ALERT_" + action, id.toString(), Timestamp.from(at));
    }

    enum Transition {
        UPDATED,
        NOT_FOUND,
        INVALID_STATE
    }

    record AlertRow(
            UUID id,
            String type,
            String fingerprint,
            UUID tenantId,
            String logicalModel,
            String provider,
            BigDecimal threshold,
            BigDecimal observed,
            String currency,
            Instant windowStart,
            Instant windowEnd,
            List<UUID> evidenceFactIds,
            Instant observedAt) {
    }
}
