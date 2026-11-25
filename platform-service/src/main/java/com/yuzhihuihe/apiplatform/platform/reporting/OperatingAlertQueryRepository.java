package com.yuzhihuihe.apiplatform.platform.reporting;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperatingAlertQueryRepository {

    /**
     * 告警分页，外加每一行是被什么关闭的。
     *
     * <p>一条告警有两条通往 RESOLVED 的路径，它们对 运维而言含义不同：定时对账关闭了它（条件已消除），或者 由人关闭。告警行本身并不记录这一区别 —— {@code
     * acknowledged_by} 在监控自动解决时为空，运维人员未先确认 就解决时同样为空 —— 因此该区别从审计 轨迹中读取，监控带 NULL 操作者的 {@code
     * OPERATING_ALERT_AUTO_RESOLVE} 就存在于那里。其他每一行 RESOLVED 都是运维人员所为。
     *
     * <p>原因从该行自身的 {@code evidence} 中读取，即解决者 存放它的地方，因此无需第二次查询、也无需控制台 了解监控的词汇表，原因就能留存。
     *
     * <p>审计查询被包在 CASE 中，因此仅对 RESOLVED 分页执行： {@code status} 是对整个分页的等值过滤，所以 OPEN 与 ACKED 分页 ——
     * 即运维实际关注的那些 —— 从不为它付出代价。
     *
     * <p>已知局限，明说而非隐藏：由一次迁移解决的告警 （V61 手工解决了三条）没有任一类型的审计行，因此它读作 OPERATOR。数据无法区分它，而发明第三种来源取值
     * 会破坏控制台的契约。
     */
    static final String PAGE_SQL =
            """
            SELECT alert.id,alert.alert_type,alert.status,alert.logical_model,alert.provider,
                   alert.threshold_value,alert.observed_value,
                   alert.currency,alert.first_observed_at,alert.last_observed_at,
                   alert.evidence ->> 'resolutionReason' AS resolution_reason,
                   CASE WHEN alert.status = 'RESOLVED' THEN EXISTS (
                       SELECT 1 FROM platform.admin_audit_log audit
                       WHERE audit.resource_type = 'OPERATING_ALERT'
                         AND audit.resource_id = alert.id::text
                         AND audit.action = 'OPERATING_ALERT_AUTO_RESOLVE'
                         AND audit.actor_user_id IS NULL
                   ) ELSE false END AS machine_resolved
            FROM platform.operating_alert alert WHERE alert.status=?
            ORDER BY alert.last_observed_at DESC, alert.id DESC LIMIT ? OFFSET ?
            """;

    static final String COUNT_SQL = "SELECT COUNT(*) FROM platform.operating_alert WHERE status=?";

    private final JdbcTemplate jdbc;

    public OperatingAlertQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    AlertPage findByStatus(String status, int size, int offset) {
        List<AlertRow> items =
                jdbc.query(PAGE_SQL, OperatingAlertQueryRepository::alert, status, size, offset);
        Long total = jdbc.queryForObject(COUNT_SQL, Long.class, status);
        return new AlertPage(List.copyOf(items), total == null ? 0 : total);
    }

    private static AlertRow alert(ResultSet result, int rowNumber) throws SQLException {
        return new AlertRow(
                result.getObject("id", UUID.class),
                result.getString("alert_type"),
                result.getString("status"),
                result.getString("logical_model"),
                result.getString("provider"),
                result.getBigDecimal("threshold_value"),
                result.getBigDecimal("observed_value"),
                result.getString("currency"),
                result.getTimestamp("first_observed_at").toInstant(),
                result.getTimestamp("last_observed_at").toInstant(),
                result.getString("resolution_reason"),
                result.getBoolean("machine_resolved"));
    }

    record AlertPage(List<AlertRow> items, long total) {}

    record AlertRow(
            UUID id,
            String type,
            String status,
            String logicalModel,
            String provider,
            BigDecimal threshold,
            BigDecimal observed,
            String currency,
            Instant firstObservedAt,
            Instant lastObservedAt,
            String resolutionReason,
            boolean machineResolved) {}
}
