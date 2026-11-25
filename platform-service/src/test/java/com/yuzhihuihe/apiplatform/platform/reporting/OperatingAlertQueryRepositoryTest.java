package com.yuzhihuihe.apiplatform.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OperatingAlertQueryRepositoryTest {

    @Test
    void queriesAStableBoundedPageAndItsTotal() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var alert = new OperatingAlertQueryRepository.AlertRow(
                UUID.randomUUID(), "LOW_MARGIN_PACKAGE", "OPEN", "qwen-plus", "bailian",
                new BigDecimal("0.30"), new BigDecimal("0.20"), "CNY",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-02T00:00:00Z"),
                null, false);
        when(jdbc.query(
                eq(OperatingAlertQueryRepository.PAGE_SQL),
                org.mockito.ArgumentMatchers
                        .<RowMapper<OperatingAlertQueryRepository.AlertRow>>any(),
                eq("OPEN"), eq(2), eq(2)))
                .thenReturn(List.of(alert));
        when(jdbc.queryForObject(
                OperatingAlertQueryRepository.COUNT_SQL, Long.class, "OPEN"))
                .thenReturn(5L);

        var result = new OperatingAlertQueryRepository(jdbc).findByStatus("OPEN", 2, 2);

        assertThat(result.items()).containsExactly(alert);
        assertThat(result.total()).isEqualTo(5);
        assertThat(OperatingAlertQueryRepository.PAGE_SQL)
                .contains("WHERE alert.status=?",
                        "ORDER BY alert.last_observed_at DESC, alert.id DESC",
                        "LIMIT ? OFFSET ?");
        // 控制台用来区分自愈告警与人工关闭告警的两个字段：
        // 存储的原因，以及监控那条被审计的 NULL 操作者解决记录。两者
        // 都必须属于分页查询的一部分 —— 二者都无法从告警行本身推断出来。
        assertThat(OperatingAlertQueryRepository.PAGE_SQL)
                .contains("alert.evidence ->> 'resolutionReason'")
                .contains("audit.action = 'OPERATING_ALERT_AUTO_RESOLVE'")
                .contains("audit.actor_user_id IS NULL");
        verify(jdbc).queryForObject(OperatingAlertQueryRepository.COUNT_SQL, Long.class, "OPEN");
    }
}
